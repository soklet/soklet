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
package com.soklet;

import com.soklet.annotation.SseEventSource;
import com.soklet.internal.microhttp.StreamLifecycleCoordinator;
import com.soklet.internal.streaming.ManagedSseLifecycle;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

/**
 * Standalone exploratory retained-Java-heap probe using real built-in SSE sockets.
 * Requires Java 21+, fresh compiled runtime classes, and no concurrent benchmark.
 * See docs/sse-lifecycle-footprint-probe.md for assumptions and reproduction.
 */
public final class SseLifecycleFootprintProbe {
	private static final int DEFAULT_CONNECTIONS = 256;
	private static final int QUEUE_CAPACITY = 128;
	private static final long WAIT_NANOS = TimeUnit.SECONDS.toNanos(20);
	private static final ResourcePath RESOURCE_PATH = ResourcePath.fromPath("/footprint");
	private static volatile ProbeRuntime retainedRuntime;

	private SseLifecycleFootprintProbe() {}

	public static void main(String[] arguments) throws Exception {
		Options options = Options.parse(arguments);
		if (Runtime.version().feature() < 21)
			throw new IllegalStateException("Real built-in SSE requires Java 21 or newer");
		System.out.println("{\"type\":\"metadata\",\"java\":" + quote(System.getProperty("java.version"))
				+ ",\"vendor\":" + quote(System.getProperty("java.vendor"))
				+ ",\"os\":" + quote(System.getProperty("os.name"))
				+ ",\"arch\":" + quote(System.getProperty("os.arch"))
				+ ",\"jvmArgs\":" + quote(ManagementFactory.getRuntimeMXBean().getInputArguments().toString())
				+ ",\"runtimeCodeSource\":" + quote(DefaultSseServer.class.getProtectionDomain().getCodeSource().getLocation().toString())
				+ ",\"runtimeAndProbeBytecodeSha256\":" + quote(bytecodeSha256()) + "}");
		System.out.printf(Locale.ROOT,
				"{\"type\":\"config\",\"mode\":%s,\"connections\":%d,\"dataBytesPerEvent\":%d,\"queueElementsPerClient\":%d,\"deliveryMode\":\"broadcaster\",\"usesUnmodifiedLifecycleDefaults\":%s,\"warmupLifecycles\":2}%n",
				quote(options.mode), options.connections, options.payloadBytes, QUEUE_CAPACITY, options.connections == DEFAULT_CONNECTIONS);
		List<Sample> samples = new ArrayList<>();
		try {
			for (int iteration = 0; iteration < 2; iteration++) {
				createRuntime(new Options("queued", Math.min(16, options.connections), options.payloadBytes));
				retainedRuntime.start();
				retainedRuntime.connectClients();
				retainedRuntime.fillQueues();
				retainedRuntime.verifyLive(true);
				retainedRuntime.stop();
				retainedRuntime.verifyStopped();
				retainedRuntime = null;
				capture("warmup-released");
			}
			samples.add(capture("warmed-baseline"));
			createRuntime(options);
			retainedRuntime.start();
			samples.add(capture("empty-started"));
			retainedRuntime.connectClients();
			retainedRuntime.verifyLive(false);
			samples.add(capture("idle-connected"));
			if (options.mode.equals("queued")) {
				retainedRuntime.fillQueues();
				retainedRuntime.verifyLive(true);
				samples.add(capture("full-queues-and-one-inflight-per-client"));
			}
			retainedRuntime.stop();
			retainedRuntime.verifyStopped();
			samples.add(capture("terminated-runtime-retained"));
			retainedRuntime = null;
			samples.add(capture("all-probe-runtime-references-released"));
			for (Sample sample : samples)
				sample.print(samples.get(0).usedHeapBytes);
			long emptyHeap = samples.get(1).usedHeapBytes;
			long idleHeap = samples.get(2).usedHeapBytes;
			long fullIncrement = options.mode.equals("queued") ? samples.get(3).usedHeapBytes - idleHeap : 0L;
			System.out.printf(Locale.ROOT,
					"{\"type\":\"result\",\"idleIncrementOverStartedBytes\":%d,\"fullQueueIncrementOverIdleBytes\":%d,\"capacityPlusOne503BeforeInitializer\":true,\"measuredQueuedPayloadIdentitiesVerified\":%s,\"allConnectionsUnregistered\":true,\"allPayloadQueuesReleased\":true,\"coordinatorAndExecutorsTerminated\":true,\"trackedWritersExited\":true}%n",
					idleHeap - emptyHeap, fullIncrement, options.mode.equals("queued"));
			System.out.println("{\"type\":\"limits\",\"text\":" + quote(
					"Approximate post-GC whole-JVM Java heap, including loopback client Socket objects, server/request/queue state, probe reference arrays, and tracked virtual Thread objects. No RSS, native socket buffers, native stacks, allocation rate, retained-size graph, or universal per-connection bound. Queue capacity limits event count, not bytes. A queued sample includes one in-flight serialized event and its SseEvent/String per client in addition to 128 queue elements. ThreadMXBean/getAllStackTraces counts are platform threads only; tracked writer counts include explicit virtual threads. Idle timers are set to one hour and verification heartbeats disabled for deterministic queue state. Fresh-JVM repetitions and complementary saturation/throughput evidence are required to qualify defaults.") + "}");
		} finally {
			if (retainedRuntime != null) {
				try { retainedRuntime.stop(); }
				finally { retainedRuntime = null; }
			}
		}
	}

	private static void createRuntime(Options options) throws Exception {
		retainedRuntime = new ProbeRuntime(options);
	}

	private record Options(String mode, int connections, int payloadBytes) {
		private static Options parse(String[] arguments) {
			String mode = "queued";
			int connections = DEFAULT_CONNECTIONS;
			int payloadBytes = 64;
			for (String argument : arguments) {
				if (argument.startsWith("--mode=")) mode = argument.substring("--mode=".length());
				else if (argument.startsWith("--connections=")) connections = Integer.parseInt(argument.substring("--connections=".length()));
				else if (argument.startsWith("--payload-bytes=")) payloadBytes = Integer.parseInt(argument.substring("--payload-bytes=".length()));
				else throw new IllegalArgumentException("Unknown argument: " + argument);
			}
			if (!mode.equals("idle") && !mode.equals("queued")) throw new IllegalArgumentException("mode must be idle or queued");
			if (connections < 1 || connections > DEFAULT_CONNECTIONS) throw new IllegalArgumentException("connections must be 1..256; smaller populations are smoke checks with reduced admission");
			if (payloadBytes < 32 || payloadBytes > 1_048_576) throw new IllegalArgumentException("payload-bytes must be 32..1048576; select JVM heap separately");
			return new Options(mode, connections, payloadBytes);
		}
	}

	private static final class ProbeRuntime {
		private final Options options;
		private final Fixture fixture;
		private final DefaultSseServer server;
		private final Soklet app;
		private final Socket[] clients;
		private final int port;
		private final List<Object> connections = new ArrayList<>();
		private StreamLifecycleCoordinator coordinator;
		private ExecutorService handlers;
		private ExecutorService readers;
		private ExecutorService writers;
		private Thread eventLoop;
		private PayloadState payloadState = PayloadState.empty();
		private boolean started;
		private boolean stopped;

		private ProbeRuntime(Options options) throws Exception {
			this.options = options;
			this.clients = new Socket[options.connections];
			this.fixture = new Fixture(options);
			try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
				this.port = listener.getLocalPort();
			}
			SseServer.Builder builder = SseServer.withPort(this.port).host("127.0.0.1")
					.heartbeatInterval(Duration.ofHours(1)).verifyConnectionOnceEstablished(false);
			if (options.connections != DEFAULT_CONNECTIONS)
				builder.streamingLifecycleCapacity(options.connections);
			this.server = (DefaultSseServer) builder.build();
			check(this.server.getStreamingLifecycleCapacity() == options.connections, "Unexpected lifecycle default");
			check(this.server.getConnectionQueueCapacity() == QUEUE_CAPACITY, "Unexpected queue default");
			this.app = Soklet.fromConfig(SokletConfig.withSseServer(this.server)
					.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Resource.class)))
					.instanceProvider(this.fixture).lifecycleObserver(this.fixture)
					.lifecyclePolicy(LifecyclePolicy.builder().gracefulShutdownTimeout(Duration.ofSeconds(10))
							.forcedShutdownTimeout(Duration.ofSeconds(5)).build()).build());
		}

		private void start() {
			this.app.start();
			this.started = true;
			this.coordinator = this.server.getStreamLifecycleCoordinatorForTests().orElseThrow();
			this.handlers = this.server.getRequestHandlerExecutorService().orElseThrow();
			this.readers = this.server.getRequestReaderExecutorService().orElseThrow();
			this.writers = this.server.getConnectionExecutorService().orElseThrow();
			this.eventLoop = this.server.getEventLoopThread().orElseThrow();
		}

		private void connectClients() throws Exception {
			for (int index = 0; index < this.clients.length; index++) {
				Socket client = connect(index);
				this.clients[index] = client;
				check(readHeaders(client).startsWith("HTTP/1.1 200"), "Client handshake was not accepted");
			}
			await(this.fixture.established, "all connections established");
			awaitCondition(() -> this.coordinator.snapshot().runningProducers() == 0, "initializer physical exit");
			check(this.fixture.initializers.get() == this.options.connections, "Unexpected initializer count");
			try (Socket excess = connect(this.options.connections)) {
				check(readHeaders(excess).startsWith("HTTP/1.1 503"), "Capacity+1 was not rejected before commitment");
			}
			check(this.fixture.initializers.get() == this.options.connections, "Rejected admission invoked initializer");
			this.connections.addAll(this.server.getGlobalConnections().keySet());
			check(this.connections.size() == this.options.connections, "Missing connection registration");
			check(this.server.acquireBroadcaster(RESOURCE_PATH).orElseThrow().getClientCount() == this.options.connections,
					"Broadcaster client count mismatch");
		}

		private Socket connect(int index) throws Exception {
			Socket socket = new Socket();
			try {
				socket.setSoTimeout(10_000);
				socket.connect(new InetSocketAddress("127.0.0.1", this.port), 10_000);
				socket.getOutputStream().write(("GET /footprint?client=" + index + " HTTP/1.1\r\nHost: 127.0.0.1:"
						+ this.port + "\r\nAccept: text/event-stream\r\nConnection: keep-alive\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
				return socket;
			} catch (Throwable failure) {
				socket.close();
				throw failure;
			}
		}

		private void fillQueues() throws Exception {
			this.fixture.holdWriters = true;
			SseBroadcaster broadcaster = this.server.acquireBroadcaster(RESOURCE_PATH).orElseThrow();
			broadcaster.broadcastEvent(context -> (Integer) context,
					index -> event(index, -1, this.options.payloadBytes));
			await(this.fixture.writersBlocked, "one in-flight event per connection");
			for (int eventIndex = 0; eventIndex < QUEUE_CAPACITY; eventIndex++) {
				int sequence = eventIndex;
				broadcaster.broadcastEvent(context -> (Integer) context,
						index -> event(index, sequence, this.options.payloadBytes));
			}
			this.payloadState = inspectFullQueues();
		}

		private PayloadState inspectFullQueues() {
			IdentityHashMap<Object, Boolean> eventIdentities = new IdentityHashMap<>();
			IdentityHashMap<Object, Boolean> stringIdentities = new IdentityHashMap<>();
			IdentityHashMap<Object, Boolean> serializedIdentities = new IdentityHashMap<>();
			long bytes = 0;
			for (Object connection : this.connections) {
				int client = clientIndex(((SseConnection) Access.invoke(Access.SNAPSHOT, connection)).getRequest());
				check(Access.queue(connection).size() == QUEUE_CAPACITY, "Queue is not exactly full");
				int eventIndex = 0;
				for (Object element : Access.queue(connection)) {
					check(!(Boolean) Access.invoke(Access.POISON, element), "Unexpected terminal queue marker");
					Object payload = Access.invoke(Access.PAYLOAD, element);
					check(payload != null && Access.invoke(Access.EVENT, payload) != null && Access.invoke(Access.COMMENT, payload) == null, "Expected queued application event");
					SseEvent event = (SseEvent) Access.invoke(Access.EVENT, payload);
					String data = event.getData().orElseThrow();
					check(data.equals(data(client, eventIndex++, this.options.payloadBytes)), "Queue order/client payload mismatch");
					check(eventIdentities.put(event, true) == null, "Shared event identity would understate retained payload");
					check(stringIdentities.put(data, true) == null, "Shared String identity would understate retained payload");
					check(serializedIdentities.put(((byte[]) Access.invoke(Access.BYTES, payload)), true) == null, "Shared serialized byte array");
					check(Arrays.equals(((byte[]) Access.invoke(Access.BYTES, payload)), ("data: " + data + "\n\n").getBytes(StandardCharsets.UTF_8)), "Unexpected SSE serialization");
					bytes += ((byte[]) Access.invoke(Access.BYTES, payload)).length;
				}
				SseEvent inFlight = this.fixture.inFlightEvents[client];
				check(inFlight != null && inFlight.getData().orElseThrow().equals(data(client, -1, this.options.payloadBytes)), "Missing in-flight event");
				check(eventIdentities.put(inFlight, true) == null, "In-flight event was shared");
				check(stringIdentities.put(inFlight.getData().orElseThrow(), true) == null, "In-flight String was shared");
			}
			long queuedEvents = (long) this.options.connections * QUEUE_CAPACITY;
			check(serializedIdentities.size() == queuedEvents, "Distinct serialized queue payload count mismatch");
			return new PayloadState(queuedEvents, bytes, this.options.connections,
					(long) this.options.connections * (this.options.payloadBytes + 8), eventIdentities.size(),
					stringIdentities.size(), (queuedEvents + this.options.connections) * this.options.payloadBytes);
		}

		private void verifyLive(boolean queued) {
			check(this.fixture.failure.get() == null, "Application/lifecycle self-check failed: " + this.fixture.failure.get());
			StreamLifecycleCoordinator.Snapshot snapshot = this.coordinator.snapshot();
			check(snapshot.reservations() == this.options.connections, "Unexpected live reservations");
			check(snapshot.callbacks() == 0 && snapshot.overdue() == 0 && snapshot.diagnostics() == 0, "Live clients unexpectedly entered cleanup");
			check(this.server.getGlobalConnections().size() == this.options.connections, "Missing live connections");
			for (int index = 0; index < this.options.connections; index++) {
				check(this.fixture.writerThreads[index] != null && this.fixture.writerThreads[index].isAlive(), "Missing live writer thread");
			}
			if (!queued)
				for (Object connection : this.connections)
					check(Access.queue(connection).isEmpty(), "Idle queue has payload");
		}

		private void stop() throws Exception {
			if (this.stopped) return;
			this.fixture.stopping = true;
			try {
				if (this.started) {
					var shutdown = this.app.shutdown().toCompletableFuture();
					// Termination closes sockets/clears queues first. Releasing the held
					// observers afterward avoids draining bodies into unread client sockets.
					try { awaitCondition(this::allKnownClientsTerminal, "terminal clients before writer release"); }
					finally { this.fixture.releaseWriters.countDown(); }
					check(shutdown.get(20, TimeUnit.SECONDS).isComplete(), "Shutdown reported residual work");
				}
			} finally {
				this.fixture.releaseWriters.countDown();
				for (Socket client : this.clients) if (client != null) client.close();
				this.app.close();
				this.stopped = true;
			}
		}

		private boolean allKnownClientsTerminal() {
			return this.server.getGlobalConnections().isEmpty();
		}

		private void verifyStopped() throws Exception {
			check(this.fixture.failure.get() == null, "Application/lifecycle self-check failed: " + this.fixture.failure.get());
			check(this.coordinator.isTerminated(), "Lifecycle coordinator still has physical work");
			check(this.handlers.isTerminated() && this.readers.isTerminated() && this.writers.isTerminated(), "Owned SSE executor has not terminated");
			check(!this.eventLoop.isAlive() && this.server.getGlobalConnections().isEmpty(), "Listener/connection registration survived shutdown");
			for (Thread thread : this.fixture.writerThreads) check(thread != null && !thread.isAlive(), "Tracked virtual writer still alive");
			for (Object connection : this.connections)
				for (Object element : Access.queue(connection))
					check(Access.invoke(Access.PAYLOAD, element) == null, "Queue retained application payload after termination");
			Arrays.fill(this.fixture.inFlightEvents, null);
			this.payloadState = PayloadState.empty();
			check(this.coordinator.snapshot().reservations() == 0, "Retired capacity was not released");
		}
	}

	private static final class Fixture implements LifecycleObserver, InstanceProvider {
		private final Options options;
		private final Resource resource = new Resource(this);
		private final Thread[] writerThreads;
		private final SseEvent[] inFlightEvents;
		private final CountDownLatch established;
		private final CountDownLatch writersBlocked;
		private final CountDownLatch releaseWriters = new CountDownLatch(1);
		private final AtomicInteger initializers = new AtomicInteger();
		private final AtomicReference<Throwable> failure = new AtomicReference<>();
		private volatile boolean holdWriters;
		private volatile boolean stopping;

		private Fixture(Options options) {
			this.options = options;
			this.writerThreads = new Thread[options.connections];
			this.inFlightEvents = new SseEvent[options.connections];
			this.established = new CountDownLatch(options.connections);
			this.writersBlocked = new CountDownLatch(options.connections);
		}

		@Override public <T> T provide(Class<T> type) {
			return type == Resource.class ? type.cast(this.resource) : InstanceProvider.defaultInstance().provide(type);
		}

		@Override public void didEstablishSseConnection(SseConnection connection) {
			int index = clientIndex(connection.getRequest());
			this.writerThreads[index] = Thread.currentThread();
			this.established.countDown();
		}

		@Override public void willWriteSseEvent(SseConnection connection, SseEvent event) {
			if (!this.holdWriters) return;
			int index = clientIndex(connection.getRequest());
			if (this.inFlightEvents[index] != null) {
				this.failure.compareAndSet(null, new IllegalStateException("A held writer reached a second event"));
				return;
			}
			this.inFlightEvents[index] = event;
			this.writersBlocked.countDown();
			awaitUninterruptibly(this.releaseWriters);
		}

		@Override public void didReceiveLogEvent(LogEvent event) {
			if (!this.stopping)
				event.getThrowable().ifPresent(throwable -> this.failure.compareAndSet(null, throwable));
		}
	}

	public static final class Resource {
		private final Fixture fixture;
		private Resource(Fixture fixture) { this.fixture = fixture; }
		@SseEventSource("/footprint") public SseHandshakeResult events(Request request) {
			int index = clientIndex(request);
			return SseHandshakeResult.Accepted.builder().clientContext(index).clientInitializer(client -> {
				this.fixture.initializers.incrementAndGet();
				check(index < this.fixture.options.connections, "Rejected request invoked initializer");
			}).build();
		}
	}

	/** Read-only, fail-fast inspection of private queue carriers; no production hook is added. */
	private static final class Access {
		private static final String CONNECTION = DefaultSseServer.class.getName() + "$DefaultSseConnection";
		private static final Method IS_VIRTUAL = method(Thread.class.getName(), "isVirtual");
		private static final Method QUEUE = method(CONNECTION, "getWriteQueue");
		private static final Method SNAPSHOT = method(CONNECTION, "getSnapshot");
		private static final Method POISON = method(CONNECTION + "$WriteQueueElement", "isPoisonPill");
		private static final Method PAYLOAD = method(CONNECTION + "$WriteQueueElement", "getPreSerializedPayload");
		private static final Method EVENT = method(CONNECTION + "$PreSerializedPayload", "getSseEvent");
		private static final Method COMMENT = method(CONNECTION + "$PreSerializedPayload", "getSseComment");
		private static final Method BYTES = method(CONNECTION + "$PreSerializedPayload", "getPayloadBytes");
		private static Method method(String type, String name) {
			try {
				Method method = Class.forName(type).getDeclaredMethod(name);
				method.setAccessible(true);
				return method;
			} catch (ReflectiveOperationException failure) { throw new IllegalStateException("SSE queue inspection ABI changed", failure); }
		}
		private static Object invoke(Method method, Object instance) {
			try { return method.invoke(instance); }
			catch (ReflectiveOperationException failure) { throw new IllegalStateException("Unable to inspect SSE queue", failure); }
		}
		private static BlockingQueue<?> queue(Object connection) { return (BlockingQueue<?>) invoke(QUEUE, connection); }
	}

	private record PayloadState(long queuedEvents, long queuedSerializedBytes, long inFlightEvents,
			long inferredInFlightSerializedBytes, long distinctEventObjects, long distinctDataStrings, long dataCharacters) {
		private static PayloadState empty() { return new PayloadState(0, 0, 0, 0, 0, 0, 0); }
	}

	private record Sample(String stage, long usedHeapBytes, int reservations, long retainedWork, int callbackJobs,
			int clientsOpen, long queuePayloads,
			long queuedSerializedBytes, long inFlightEvents, long inferredInFlightSerializedBytes, long distinctEventObjects,
			long distinctDataStrings, long dataCharacters, int liveTrackedVirtualWriters, int liveTrackedPlatformWriters,
			int requestHandlerPoolSize, int requestReaderPoolSize, int ownedPlatformThreads, int totalPlatformThreads) {
		private void print(long baseline) {
			System.out.printf(Locale.ROOT,
					"{\"type\":\"sample\",\"stage\":%s,\"usedHeapBytes\":%d,\"deltaFromBaselineBytes\":%d,\"reservations\":%d,\"retainedWork\":%d,\"callbackJobs\":%d,\"clientsOpen\":%d,\"queuedEvents\":%d,\"queuedSerializedBytes\":%d,\"inFlightEvents\":%d,\"inferredInFlightSerializedBytes\":%d,\"distinctEventObjects\":%d,\"distinctDataStrings\":%d,\"dataCharacters\":%d,\"liveTrackedVirtualWriters\":%d,\"liveTrackedPlatformWriters\":%d,\"requestHandlerPoolSize\":%d,\"requestReaderPoolSize\":%d,\"ownedPlatformThreads\":%d,\"totalPlatformThreads\":%d}%n",
					quote(this.stage), this.usedHeapBytes, this.usedHeapBytes - baseline, this.reservations, this.retainedWork,
					this.callbackJobs, this.clientsOpen,
					this.queuePayloads, this.queuedSerializedBytes, this.inFlightEvents, this.inferredInFlightSerializedBytes,
					this.distinctEventObjects, this.distinctDataStrings, this.dataCharacters, this.liveTrackedVirtualWriters,
					this.liveTrackedPlatformWriters, this.requestHandlerPoolSize, this.requestReaderPoolSize,
					this.ownedPlatformThreads, this.totalPlatformThreads);
		}
	}

	private static Sample capture(String stage) throws InterruptedException {
		long minimumHeap = Long.MAX_VALUE;
		for (int attempt = 0; attempt < 3; attempt++) {
			System.gc();
			Thread.sleep(50L);
			minimumHeap = Math.min(minimumHeap, Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
		}
		ProbeRuntime runtime = retainedRuntime;
		StreamLifecycleCoordinator.Snapshot snapshot = runtime == null || runtime.coordinator == null ? null : runtime.coordinator.snapshot();
		PayloadState payload = runtime == null ? PayloadState.empty() : runtime.payloadState;
		int open = 0, virtual = 0, platform = 0;
		if (runtime != null) {
			open = runtime.server.getGlobalConnections().size();
			for (Thread writer : runtime.fixture.writerThreads) if (writer != null && writer.isAlive()) {
				if ((Boolean) Access.invoke(Access.IS_VIRTUAL, writer)) virtual++; else platform++;
			}
		}
		int ownedPlatformThreads = 0;
		for (Thread thread : Thread.getAllStackTraces().keySet())
			if (thread.isAlive() && (thread.getName().startsWith("sse-") || thread.getName().startsWith("stream-"))) ownedPlatformThreads++;
		return new Sample(stage, minimumHeap, snapshot == null ? 0 : snapshot.reservations(), snapshot == null ? 0 : snapshot.retainedWork(),
				snapshot == null ? 0 : snapshot.callbacks(), open,
				payload.queuedEvents, payload.queuedSerializedBytes, payload.inFlightEvents, payload.inferredInFlightSerializedBytes,
				payload.distinctEventObjects, payload.distinctDataStrings, payload.dataCharacters, virtual, platform,
				runtime != null && runtime.handlers instanceof ThreadPoolExecutor pool ? pool.getPoolSize() : 0,
				runtime != null && runtime.readers instanceof ThreadPoolExecutor pool ? pool.getPoolSize() : 0,
				ownedPlatformThreads, ManagementFactory.getThreadMXBean().getThreadCount());
	}

	private static int clientIndex(Request request) { return Integer.parseInt(request.getQueryParameter("client").orElseThrow()); }
	private static SseEvent event(int client, int event, int bytes) { return SseEvent.withData(data(client, event, bytes)).build(); }
	private static String data(int client, int event, int bytes) {
		char[] characters = new char[bytes];
		Arrays.fill(characters, 'x');
		String prefix = String.format(Locale.ROOT, "%08x:%08x:", client, event);
		prefix.getChars(0, prefix.length(), characters, 0);
		return new String(characters);
	}

	private static String readHeaders(Socket socket) throws Exception {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		InputStream input = socket.getInputStream();
		int previous = 0;
		while (bytes.size() < 32 * 1024) {
			int next = input.read();
			if (next < 0) throw new IllegalStateException("EOF before HTTP response headers");
			bytes.write(next);
			previous = previous << 8 | next;
			if (previous == 0x0d0a0d0a) return bytes.toString(StandardCharsets.US_ASCII);
		}
		throw new IllegalStateException("HTTP response headers exceeded probe bound");
	}

	private static void await(CountDownLatch latch, String stage) throws InterruptedException {
		check(latch.await(WAIT_NANOS, TimeUnit.NANOSECONDS), "Timed out waiting for " + stage);
	}
	private static void awaitCondition(BooleanSupplier condition, String stage) throws InterruptedException {
		long deadline = System.nanoTime() + WAIT_NANOS;
		while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
			if (Thread.interrupted()) throw new InterruptedException(stage);
			LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
		}
		check(condition.getAsBoolean(), "Timed out waiting for " + stage);
	}
	private static void awaitUninterruptibly(CountDownLatch latch) {
		boolean interrupted = false;
		for (;;) {
			try { latch.await(); break; }
			catch (InterruptedException ignored) { interrupted = true; }
		}
		if (interrupted) Thread.currentThread().interrupt();
	}
	private static void check(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
	private static String quote(String value) { return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""; }

	private static String bytecodeSha256() throws Exception {
		List<Class<?>> classes = new ArrayList<>();
		for (Class<?> root : List.of(DefaultSseServer.class, ManagedSseLifecycle.class, StreamLifecycleCoordinator.class,
				SseEvent.class, SseLifecycleFootprintProbe.class)) collectDeclaredClasses(root, classes);
		classes.sort(Comparator.comparing(Class::getName));
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		for (Class<?> type : classes) {
			digest.update(type.getName().getBytes(StandardCharsets.UTF_8));
			digest.update((byte) 0);
			try (InputStream bytes = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
				if (bytes == null) throw new IllegalStateException("Loaded bytecode unavailable: " + type.getName());
				digest.update(bytes.readAllBytes());
			}
		}
		return HexFormat.of().formatHex(digest.digest());
	}
	private static void collectDeclaredClasses(Class<?> type, List<Class<?>> classes) {
		classes.add(type);
		for (Class<?> nested : type.getDeclaredClasses()) collectDeclaredClasses(nested, classes);
	}
}
