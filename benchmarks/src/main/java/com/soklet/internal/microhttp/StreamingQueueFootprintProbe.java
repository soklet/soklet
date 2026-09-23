/*
 * Copyright 2022-2026 Revetware LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.soklet.internal.microhttp;

import com.soklet.HttpMethod;
import com.soklet.Request;
import com.soklet.StreamTerminationReason;
import com.soklet.StreamingResponseBody;
import com.soklet.StreamingResponseCanceledException;
import com.soklet.internal.streaming.ManagedResponseStream;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Exploratory post-GC Java-heap probe for full HTTP streaming queues and their
 * lifecycle reservations. This is neither a throughput benchmark nor release
 * qualification evidence. No socket or body consumer is created.
 * <p>
 * In the default mode each admitted writer performs exactly 64 16-KiB writes, filling the current
 * default 1-MiB payload queue. StreamingMicrohttpResponses copies each write into
 * its own byte array; the shared 16-KiB input is not the retained queue payload.
 * Completion counts, zero queued/running producer envelopes, and the absence of
 * consumer calls establish that every filled queue remains retained for delivery.
 * This fixture relies on that internal copy/queue invariant rather than inspecting
 * private queue fields. It deliberately includes source/response wrappers, minimal
 * request objects, coordinator state, executor objects, and reference arrays.
 * <p>
 * The empty-coordinator sample includes the same eight prestarted producer threads
 * and reference arrays as the filled sample. Two smaller complete lifecycles warm
 * class loading, allocation paths, and teardown first. Each heap sample is the
 * minimum used heap following three requested GCs. Repeat in fresh JVMs; GC and VM
 * bookkeeping make these approximate retained-heap measurements, not object sizes.
 * Native thread stacks, RSS, sockets, application-owned resources, allocation rate,
 * callback backlog, and sustained-load behavior are excluded.
 * <p>
 * Example (256 MiB of payload alone requires substantially more than a 256-MiB heap):
 * <pre>{@code
 * java -Xms768m -Xmx768m -XX:+UseSerialGC \
 *   -Dsoklet.queueFootprint.capacity=256 -cp target/soklet-benchmarks.jar \
 *   com.soklet.internal.microhttp.StreamingQueueFootprintProbe
 * }</pre>
 * Capacity defaults to 256. Larger capacities require a separately chosen heap;
 * this probe never changes the JVM heap or derives a production default from it.
 * Set {@code -Dsoklet.queueFootprint.activeOutput=true} to keep the final eight
 * producers inside a native byte write against their already-full queues. Each retains
 * an allocated but drained scalar stage and a pending copied payload.
 * Earlier producers exit normally, so all capacity slots have full queues without
 * requiring one platform thread per slot. This is an eight-worker fixture, not a
 * claim about the server's CPU-dependent default producer concurrency.
 */
public final class StreamingQueueFootprintProbe {
	private static final int QUEUE_CAPACITY_BYTES = 1024 * 1024;
	private static final int CHUNK_SIZE_BYTES = 16 * 1024;
	private static final int WRITES_PER_PRODUCER = QUEUE_CAPACITY_BYTES / CHUNK_SIZE_BYTES;
	private static final int PRODUCER_THREADS = 8;
	private static final int CALLBACK_THREADS = 4;
	private static final Duration CLEANUP_GRACE = Duration.ofSeconds(5);
	private static final long WAIT_NANOS = TimeUnit.SECONDS.toNanos(20);
	private static final byte[] INPUT_CHUNK = inputChunk();
	private static final byte[] EMPTY_HEAD = new byte[0];
	private static final int ACTIVE_BUFFER_BYTES = 8192;
	private static final byte[] PENDING_BYTES = new byte[ACTIVE_BUFFER_BYTES];
	private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger();
	private static final String[] STAGES = {
			"warmed-baseline", "empty-accepting", "full-queues-producers-exited",
			"terminated-retained", "released"
	};
	private static volatile ProbeRuntime retainedRuntime;

	private StreamingQueueFootprintProbe() {}

	public static void main(String[] arguments) throws Exception {
		int capacity = Integer.getInteger("soklet.queueFootprint.capacity", 256);
		if (capacity <= 0 || capacity > Integer.MAX_VALUE / 2)
			throw new IllegalArgumentException("Queue footprint capacity must be positive and fit twice in an int");
		boolean activeOutput = Boolean.getBoolean("soklet.queueFootprint.activeOutput");
		long[][] samples = new long[STAGES.length][11];
		try {
			for (int iteration = 0; iteration < 2; iteration++) {
				createRuntime(Math.min(capacity, 16), activeOutput);
				fillQueues();
				stopRuntime();
				retainedRuntime = null;
				capture(samples[0]);
			}
			capture(samples[0]);
			createRuntime(capacity, activeOutput);
			capture(samples[1]);
			fillQueues();
			capture(samples[2]);
			stopRuntime();
			capture(samples[3]);
			retainedRuntime = null;
			capture(samples[4]);
			printResults(capacity, activeOutput, samples);
		} finally {
			try {
				if (retainedRuntime != null)
					stopRuntime();
			} finally {
				retainedRuntime = null;
			}
		}
	}

	private static void createRuntime(int capacity, boolean activeOutput) {
		retainedRuntime = new ProbeRuntime(capacity, activeOutput);
		// Publish ownership before thread creation, so the outer finally can tear
		// down even a partially started pool if the JVM cannot create every worker.
		retainedRuntime.producerExecutor.prestartAllCoreThreads();
	}

	private static void fillQueues() throws Exception {
		retainedRuntime.fillQueues();
	}

	private static void stopRuntime() throws InterruptedException {
		retainedRuntime.close();
	}

	private static final class ProbeRuntime implements AutoCloseable {
		private final int capacity;
		private final int activeOutputProducers;
		private final StreamLifecycleCoordinator coordinator;
		private final ThreadPoolExecutor producerExecutor;
		private final ScheduledThreadPoolExecutor timeoutExecutor;
		private final StreamLifecycleCoordinator.Reservation[] reservations;
		private final MicrohttpResponse[] responses;
		private final WritableSource[] sources;
		private final CountDownLatch producersFinished;
		private final CountDownLatch queuesFilled;
		private final AtomicInteger producersEntered = new AtomicInteger();
		private final List<Thread> activeOutputThreads = new CopyOnWriteArrayList<>();
		private final AtomicInteger successfulProducers = new AtomicInteger();
		private final AtomicInteger terminationNotifications = new AtomicInteger();
		private final AtomicReference<Throwable> failure = new AtomicReference<>();
		private int admitted;
		private boolean closed;
		private volatile boolean stopping;

		private ProbeRuntime(int capacity, boolean activeOutput) {
			this.capacity = capacity;
			this.activeOutputProducers = activeOutput ? Math.min(PRODUCER_THREADS, capacity) : 0;
			this.reservations = new StreamLifecycleCoordinator.Reservation[capacity];
			this.responses = new MicrohttpResponse[capacity];
			this.sources = new WritableSource[capacity];
			this.producersFinished = new CountDownLatch(capacity);
			this.queuesFilled = new CountDownLatch(capacity);
			this.coordinator = new StreamLifecycleCoordinator(capacity, Math.min(CALLBACK_THREADS, capacity),
					CLEANUP_GRACE, this::recordFailure);
			this.producerExecutor = new ThreadPoolExecutor(PRODUCER_THREADS, PRODUCER_THREADS,
					0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(capacity),
					threadFactory("queue-footprint-producer-"), new ThreadPoolExecutor.AbortPolicy());
			this.timeoutExecutor = new ScheduledThreadPoolExecutor(1, threadFactory("queue-footprint-timeout-"));
			this.timeoutExecutor.setRemoveOnCancelPolicy(true);
		}

		private void fillQueues() throws Exception {
			StreamingResponseBody body = StreamingResponseBody.fromWriter(responseStream -> {
				try {
					boolean retainOutputBuffers = this.producersEntered.incrementAndGet()
							> this.capacity - this.activeOutputProducers;
					if (retainOutputBuffers) {
						// Allocate the lazy stage, then drain it. Its backing array remains
						// owned by the live stream while the native write is blocked below.
						OutputStream outputStream = responseStream.asOutputStream();
						outputStream.write(0);
						outputStream.flush();
						for (int remaining = QUEUE_CAPACITY_BYTES - 1; remaining > 0;) {
							int length = Math.min(remaining, INPUT_CHUNK.length);
							responseStream.write(INPUT_CHUNK, 0, length);
							remaining -= length;
						}
						this.queuesFilled.countDown();
						this.activeOutputThreads.add(Thread.currentThread());
						responseStream.write(PENDING_BYTES);
						throw new IllegalStateException("The native write escaped a full queue without a body consumer");
					}
					for (int write = 0; write < WRITES_PER_PRODUCER; write++) responseStream.write(INPUT_CHUNK);
					this.successfulProducers.incrementAndGet();
					this.queuesFilled.countDown();
				} finally {
					this.producersFinished.countDown();
				}
			});
			for (int index = 0; index < this.capacity; index++) {
				StreamLifecycleCoordinator.Reservation reservation = this.coordinator.tryReserve();
				if (reservation == null)
					throw new IllegalStateException("Lifecycle admission rejected stream " + index);
				this.reservations[index] = reservation;
				MicrohttpResponse response = StreamingMicrohttpResponses.withStreamingBody(200, "OK", List.of(),
						Request.withPath(HttpMethod.GET, "/queue-footprint").build(), body,
						this.producerExecutor, this.timeoutExecutor, QUEUE_CAPACITY_BYTES, CHUNK_SIZE_BYTES,
						null, null, () -> false,
						(establishedAt, duration, reason, throwable) -> {
							this.terminationNotifications.incrementAndGet();
							if (reason != StreamTerminationReason.SERVER_STOPPING || throwable != null)
								this.failure.compareAndSet(null, new IllegalStateException(
										"Unexpected streaming termination: " + reason, throwable));
						}, this::recordFailure, reservation);
				this.responses[index] = response;
				this.admitted++;
				// An empty synthetic head permits package-local transport initialization
				// without reflection, a socket, or consuming any queued body bytes.
				this.sources[index] = response.writableSource(EMPTY_HEAD);
				this.sources[index].start();
			}
			if (!this.queuesFilled.await(WAIT_NANOS, TimeUnit.NANOSECONDS))
				throw new IllegalStateException("Writers did not fill all payload queues before the deadline");
			long deadline = System.nanoTime() + WAIT_NANOS;
			while (true) {
				StreamLifecycleCoordinator.Snapshot snapshot = this.coordinator.snapshot();
				if (snapshot.queuedProducers() == 0 && snapshot.runningProducers() == this.activeOutputProducers
						&& allActiveOutputCallsWaiting())
					break;
				if (System.nanoTime() - deadline >= 0L)
					throw new IllegalStateException("Producer envelopes did not reach the required exited/blocked state: " + snapshot);
				Thread.sleep(1L);
			}
			checkFailure();
			StreamLifecycleCoordinator.Snapshot snapshot = this.coordinator.snapshot();
			if (this.successfulProducers.get() != this.capacity - this.activeOutputProducers
					|| this.producersFinished.getCount() != this.activeOutputProducers || snapshot.reservations() != this.capacity
					|| snapshot.callbacks() != 0 || this.terminationNotifications.get() != 0)
				throw new IllegalStateException("Filled queues were not retained through delivery: " + snapshot);
			for (WritableSource source : this.sources)
				if (!source.hasRemaining() || !source.isReadyToWrite())
					throw new IllegalStateException("A filled source lost its undelivered bytes");
			StreamLifecycleCoordinator.Reservation excess = this.coordinator.tryReserve();
			if (excess != null) {
				excess.abandon();
				throw new IllegalStateException("Capacity-plus-one admission unexpectedly succeeded");
			}
		}

		@Override
		public void close() throws InterruptedException {
			if (this.closed)
				return;
			this.stopping = true;
			try {
				this.coordinator.force();
			} catch (Throwable throwable) {
				this.failure.compareAndSet(null, throwable);
			}
			for (int index = 0; index < this.capacity; index++) {
				try {
					if (this.responses[index] != null)
						StreamingMicrohttpResponses.discard(this.responses[index]);
					else if (this.reservations[index] != null)
						this.reservations[index].abandon();
				} catch (Throwable throwable) {
					this.failure.compareAndSet(null, throwable);
				}
			}
			try {
				this.coordinator.retireQueuedTasks(this.producerExecutor.shutdownNow());
			} finally {
				this.timeoutExecutor.shutdownNow();
			}
			long deadline = System.nanoTime() + WAIT_NANOS;
			if (!awaitExecutor(this.producerExecutor, deadline) || !awaitExecutor(this.timeoutExecutor, deadline)
					|| !this.coordinator.awaitTermination(deadline))
				throw new IllegalStateException("Probe-owned work did not physically terminate: " + this.coordinator.snapshot());
			while (ownedThreadCount() != 0) {
				if (System.nanoTime() - deadline >= 0L)
					throw new IllegalStateException("Probe-owned threads remained alive after executor termination");
				Thread.sleep(1L);
			}
			this.closed = true;
			if (this.coordinator.snapshot().reservations() != 0
					|| this.terminationNotifications.get() != this.admitted)
				throw new IllegalStateException("Discard failed to retire every admitted stream exactly once");
			checkFailure();
		}

		private void checkFailure() {
			Throwable throwable = this.failure.get();
			if (throwable != null)
				throw new IllegalStateException("Unexpected probe lifecycle failure", throwable);
		}

		private boolean allActiveOutputCallsWaiting() {
			if (this.activeOutputThreads.size() != this.activeOutputProducers)
				return false;
			for (Thread thread : this.activeOutputThreads) {
				if (thread.getState() != Thread.State.WAITING)
					return false;
				boolean enqueue = false;
			boolean nativeWrite = false;
				for (StackTraceElement frame : thread.getStackTrace()) {
					enqueue |= frame.getClassName().startsWith(StreamingMicrohttpResponses.class.getName())
							&& frame.getMethodName().equals("enqueue");
				nativeWrite |= frame.getClassName().equals(ManagedResponseStream.class.getName())
						&& frame.getMethodName().equals("write");
				}
			if (!enqueue || !nativeWrite)
					return false;
			}
			return true;
		}

		private void recordFailure(Throwable throwable) {
			// Forced teardown interrupts the deliberately blocked write. The runtime
			// may retain that interruption as secondary cancelation evidence.
			if (this.stopping && expectedStopEvidence(throwable))
				return;
			this.failure.compareAndSet(null, throwable);
		}

		private static boolean expectedStopEvidence(Throwable throwable) {
			boolean expected = throwable instanceof InterruptedException
					|| throwable instanceof StreamingResponseCanceledException canceled
					&& canceled.getCancelationReason() == StreamTerminationReason.SERVER_STOPPING;
			if (!expected || throwable.getCause() != null)
				return false;
			for (Throwable suppressed : throwable.getSuppressed())
				if (!expectedStopEvidence(suppressed))
					return false;
			return true;
		}
	}

	private static boolean awaitExecutor(java.util.concurrent.ExecutorService executor, long deadline) throws InterruptedException {
		long remaining = deadline - System.nanoTime();
		return executor.isTerminated() || remaining > 0L && executor.awaitTermination(remaining, TimeUnit.NANOSECONDS);
	}

	private static ThreadFactory threadFactory(String prefix) {
		return runnable -> new Thread(runnable, prefix + THREAD_SEQUENCE.incrementAndGet());
	}

	private static byte[] inputChunk() {
		byte[] bytes = new byte[CHUNK_SIZE_BYTES];
		for (int index = 0; index < bytes.length; index++)
			bytes[index] = (byte) (index * 31 ^ index >>> 8);
		return bytes;
	}

	private static void capture(long[] sample) throws InterruptedException {
		Arrays.fill(sample, 0L);
		if (retainedRuntime != null) {
			StreamLifecycleCoordinator.Snapshot snapshot = retainedRuntime.coordinator.snapshot();
			sample[1] = snapshot.reservations();
			sample[2] = snapshot.queuedProducers();
			sample[3] = snapshot.runningProducers();
			sample[4] = snapshot.callbacks();
		}
		for (Thread thread : Thread.getAllStackTraces().keySet()) {
			int index = ownedThreadIndex(thread.getName());
			if (thread.isAlive() && index >= 0)
				sample[index]++;
		}
		sample[10] = ManagementFactory.getThreadMXBean().getThreadCount();
		long minimumUsedHeap = Long.MAX_VALUE;
		for (int attempt = 0; attempt < 3; attempt++) {
			System.gc();
			Thread.sleep(50L);
			Runtime runtime = Runtime.getRuntime();
			minimumUsedHeap = Math.min(minimumUsedHeap, runtime.totalMemory() - runtime.freeMemory());
		}
		sample[0] = minimumUsedHeap;
	}

	private static int ownedThreadCount() {
		int count = 0;
		for (Thread thread : Thread.getAllStackTraces().keySet())
			if (thread.isAlive() && ownedThreadIndex(thread.getName()) >= 0)
				count++;
		return count;
	}

	private static int ownedThreadIndex(String name) {
		if (name.startsWith("queue-footprint-producer-")) return 5;
		if (name.startsWith("stream-callback-")) return 6;
		if (name.startsWith("stream-diagnostic-")) return 7;
		if (name.startsWith("stream-supervisor-")) return 8;
		if (name.startsWith("queue-footprint-timeout-")) return 9;
		return -1;
	}

	private static void printResults(int capacity, boolean activeOutput, long[][] samples) throws Exception {
		int activeProducers = activeOutput ? Math.min(PRODUCER_THREADS, capacity) : 0;
		System.out.printf(Locale.ROOT,
				"EXPLORATORY_STREAMING_QUEUE_FOOTPRINT capacity=%d queueBytes=%d chunkBytes=%d normalWritesPerProducer=%d producerThreads=%d callbackThreads=%d cleanupGraceMillis=%d activeOutputProducers=%d%n",
				capacity, QUEUE_CAPACITY_BYTES, CHUNK_SIZE_BYTES, WRITES_PER_PRODUCER,
				PRODUCER_THREADS, Math.min(CALLBACK_THREADS, capacity), CLEANUP_GRACE.toMillis(), activeProducers);
		System.out.printf(Locale.ROOT, "java=%s vendor=%s os=%s arch=%s%n",
				System.getProperty("java.version"), System.getProperty("java.vendor"),
				System.getProperty("os.name"), System.getProperty("os.arch"));
		System.out.println("jvmArgs=" + ManagementFactory.getRuntimeMXBean().getInputArguments());
		System.out.println("runtimeCodeSource=" + StreamingMicrohttpResponses.class.getProtectionDomain().getCodeSource().getLocation());
		System.out.println("runtimeAndProbeBytecodeSha256=" + bytecodeSha256());
		System.out.println("stage,usedHeapBytes,deltaFromBaselineBytes,reservations,queuedProducers,runningProducers,callbackJobs,ownedProducerThreads,ownedCallbackThreads,ownedDiagnosticThreads,ownedSupervisorThreads,ownedTimeoutThreads,totalJvmThreads");
		for (int index = 0; index < STAGES.length; index++) {
			long[] sample = samples[index];
			String stage = index == 2 && activeOutput ? "full-queues-eight-worker-output-retained" : STAGES[index];
			System.out.printf(Locale.ROOT, "%s,%d,%d", stage, sample[0], sample[0] - samples[0][0]);
			for (int value = 1; value < sample.length; value++)
				System.out.printf(Locale.ROOT, ",%d", sample[value]);
			System.out.println();
		}
		long retainedDelta = samples[2][0] - samples[1][0];
		long payloadBytes = capacity * (long) QUEUE_CAPACITY_BYTES;
		long activeBufferBytes = activeProducers * (long) ACTIVE_BUFFER_BYTES;
		System.out.printf(Locale.ROOT,
				"expectedQueuedPayloadBytes=%d filledQueueIncrementBytes=%d approximateBytesPerStream=%.2f incrementBeyondPayloadBytes=%d capacityPlusOneRejected=true allReservationsRetired=true allOwnedThreadsExited=true%n",
				payloadBytes, retainedDelta, retainedDelta / (double) capacity, retainedDelta - payloadBytes);
		System.out.printf(Locale.ROOT,
				"measuredActiveProducerCount=%d retainedScalarStageBytes=%d retainedPendingPayloadBytes=%d expectedFrameworkPayloadBytes=%d activeQueueWaitsVerified=%s%n",
				activeProducers, activeBufferBytes, activeBufferBytes,
				payloadBytes + activeBufferBytes * 2, activeOutput);
		System.out.printf(Locale.ROOT,
				"analyticAllCapacityActiveScalarStageBytes=%d analyticAllCapacityActiveMaximumPendingPayloadBytes=%d analyticBoundsAreNotMeasuredSimultaneousWork=true%n",
				capacity * (long) ACTIVE_BUFFER_BYTES,
				capacity * (long) CHUNK_SIZE_BYTES);
		System.out.println("LIMITS: approximate post-GC Java heap only; synthetic full queues and minimal requests with the same eight-worker pool retained in empty/filled samples. Active-output mode keeps at most eight writers waiting inside a native byte write with allocated-but-drained scalar staging and a copied pending payload; all other writers physically exited. The default mode retains no active producer output buffers. Stack/state checks establish active queue waits; package-internal copy invariants establish payload without private-field inspection. No sockets, native stacks, RSS, arbitrary application resources, allocation rate, callback backlog, or 256-simultaneous-producer measurement; this probe alone does not qualify production defaults.");
	}

	private static String bytecodeSha256() throws Exception {
		List<Class<?>> classes = new ArrayList<>();
		for (Class<?> root : List.of(StreamLifecycleCoordinator.class, StreamingMicrohttpResponses.class, ManagedResponseStream.class,
				MicrohttpResponse.class, StreamingQueueFootprintProbe.class))
			collectDeclaredClasses(root, classes);
		classes.sort(Comparator.comparing(Class::getName));
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		for (Class<?> type : classes) {
			digest.update(type.getName().getBytes(StandardCharsets.UTF_8));
			digest.update((byte) 0);
			String resource = "/" + type.getName().replace('.', '/') + ".class";
			try (InputStream bytecode = type.getResourceAsStream(resource)) {
				if (bytecode == null)
					throw new IllegalStateException("Loaded bytecode is unavailable: " + resource);
				digest.update(bytecode.readAllBytes());
			}
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	private static void collectDeclaredClasses(Class<?> type, List<Class<?>> classes) {
		classes.add(type);
		for (Class<?> nested : type.getDeclaredClasses())
			collectDeclaredClasses(nested, classes);
	}
}
