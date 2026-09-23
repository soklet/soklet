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

import com.soklet.internal.microhttp.StreamLifecycleCoordinator;

import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Exploratory, single-process heap and thread probe for the streaming lifecycle
 * coordinator. This is not a JMH benchmark or release qualification evidence.
 * <p>
 * It warms the same code paths before measurement, holds strong references at
 * each stage, and samples the minimum used Java heap after three explicit GCs.
 * Idle reservations include the probe's 1,024-element reference array. Cancelation
 * dispatches two empty terminal jobs per reservation and waits for physical drain.
 * No sockets, response buffers, producer executors, application payloads, native
 * thread stacks, RSS, allocation rate, or callback backlog are measured.
 * <p>
 * Run in a fresh JVM with {@code -Xms128m -Xmx128m -XX:+UseSerialGC}. Repeat in
 * separate JVMs; small heap deltas can be distorted by VM bookkeeping and GC.
 */
public final class StreamLifecycleFootprintProbe {
	private static final int CAPACITY = 1_024;
	private static final int CALLBACK_CONCURRENCY = 4;
	private static final Duration CLEANUP_GRACE = Duration.ofSeconds(5);
	private static final long WAIT_NANOS = TimeUnit.SECONDS.toNanos(5);
	private static final Runnable EMPTY_CALLBACK = () -> {};
	private static final AtomicInteger DIAGNOSTICS = new AtomicInteger();
	private static final String[] STAGES = {
			"warmed-baseline", "empty-accepting", "1024-idle-reservations",
			"cancelations-drained-accepting", "terminated-retained", "released"
	};
	private static volatile StreamLifecycleCoordinator retainedCoordinator;
	private static volatile StreamLifecycleCoordinator.Reservation[] retainedReservations;

	private StreamLifecycleFootprintProbe() {}

	public static void main(String[] arguments) throws Exception {
		long[][] samples = new long[STAGES.length][7];
		try {
			// Exercise class loading, cancelation dispatch, and thread teardown before
			// measuring the first coordinator. Reuse the measurement path as well.
			for (int iteration = 0; iteration < 5; iteration++) {
				createCoordinator();
				reserveCapacity();
				cancelReservations();
				awaitDrained();
				stopCoordinator();
				retainedCoordinator = null;
				capture(samples[0]);
			}
			if (DIAGNOSTICS.get() != 0)
				throw new IllegalStateException("Warm-up produced unexpected lifecycle diagnostics");

			capture(samples[0]);
			createCoordinator();
			capture(samples[1]);
			reserveCapacity();
			capture(samples[2]);
			cancelReservations();
			awaitDrained();
			capture(samples[3]);
			stopCoordinator();
			capture(samples[4]);
			retainedCoordinator = null;
			capture(samples[5]);

			System.out.printf(Locale.ROOT,
					"EXPLORATORY_STREAM_LIFECYCLE_FOOTPRINT capacity=%d callbacks=%d cleanupGraceMillis=%d%n",
					CAPACITY, CALLBACK_CONCURRENCY, CLEANUP_GRACE.toMillis());
			System.out.printf(Locale.ROOT, "java=%s vendor=%s os=%s arch=%s%n",
					System.getProperty("java.version"), System.getProperty("java.vendor"),
					System.getProperty("os.name"), System.getProperty("os.arch"));
			System.out.println("jvmArgs=" + ManagementFactory.getRuntimeMXBean().getInputArguments());
			System.out.println("coordinatorCodeSource=" + StreamLifecycleCoordinator.class
					.getProtectionDomain().getCodeSource().getLocation());
			System.out.println("coordinatorBytecodeSha256=" + coordinatorBytecodeSha256());
			System.out.println("stage,usedHeapBytes,deltaFromBaselineBytes,reservations,callbackJobs,ownedCallbackThreads,ownedDiagnosticThreads,ownedSupervisorThreads,totalJvmThreads");
			for (int index = 0; index < STAGES.length; index++) {
				long[] sample = samples[index];
				System.out.printf(Locale.ROOT, "%s,%d,%d,%d,%d,%d,%d,%d,%d%n",
						STAGES[index], sample[0], sample[0] - samples[0][0], sample[1],
						sample[2], sample[3], sample[4], sample[5], sample[6]);
			}
			System.out.printf(Locale.ROOT,
					"idleReservationIncrementBytes=%d approximateBytesPerReservation=%.2f diagnostics=%d%n",
					samples[2][0] - samples[1][0],
					(samples[2][0] - samples[1][0]) / (double) CAPACITY, DIAGNOSTICS.get());
			System.out.println("LIMITS: approximate post-GC Java heap only; synthetic idle reservations and empty callbacks; no HTTP/application payloads/native stack/RSS/allocation-rate/backlog measurement; provisional limits are not qualified by this probe.");
		} finally {
			StreamLifecycleCoordinator.Reservation[] reservations = retainedReservations;
			if (reservations != null) {
				for (StreamLifecycleCoordinator.Reservation reservation : reservations)
					if (reservation != null)
						reservation.abandon();
				retainedReservations = null;
			}
			if (retainedCoordinator != null) {
				stopCoordinator();
				retainedCoordinator = null;
			}
		}
	}

	private static void createCoordinator() {
		retainedCoordinator = new StreamLifecycleCoordinator(CAPACITY, CALLBACK_CONCURRENCY,
				CLEANUP_GRACE, failure -> DIAGNOSTICS.incrementAndGet());
	}

	private static String coordinatorBytecodeSha256() throws Exception {
		List<Class<?>> classes = new ArrayList<>();
		collectDeclaredClasses(StreamLifecycleCoordinator.class, classes);
		classes.sort(Comparator.comparing(Class::getName));
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		for (Class<?> type : classes) {
			digest.update(type.getName().getBytes(StandardCharsets.UTF_8));
			digest.update((byte) 0);
			String resource = "/" + type.getName().replace('.', '/') + ".class";
			try (InputStream bytecode = type.getResourceAsStream(resource)) {
				if (bytecode == null)
					throw new IllegalStateException("Loaded coordinator bytecode is unavailable: " + resource);
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

	private static void reserveCapacity() {
		retainedReservations = new StreamLifecycleCoordinator.Reservation[CAPACITY];
		for (int index = 0; index < CAPACITY; index++) {
			retainedReservations[index] = retainedCoordinator.tryReserve();
			if (retainedReservations[index] == null)
				throw new IllegalStateException("Capacity was rejected before the configured limit");
		}
		if (retainedCoordinator.tryReserve() != null)
			throw new IllegalStateException("Configured admission capacity was exceeded");
	}

	private static void cancelReservations() {
		for (StreamLifecycleCoordinator.Reservation reservation : retainedReservations) {
			reservation.bindTermination((reason, cause) -> {
				reservation.dispatchCallbacks(EMPTY_CALLBACK);
				reservation.dispatchTermination(EMPTY_CALLBACK);
				reservation.complete();
			});
			if (!reservation.cancel(StreamTerminationReason.APPLICATION_CANCELED, null))
				throw new IllegalStateException("Reservation did not accept cancelation");
		}
		// Drop the probe's handles. The coordinator must independently release every
		// reservation once both terminal jobs have physically returned.
		retainedReservations = null;
	}

	private static void awaitDrained() throws InterruptedException {
		long deadline = System.nanoTime() + WAIT_NANOS;
		while (retainedCoordinator.snapshot().reservations() != 0) {
			if (System.nanoTime() - deadline >= 0L)
				throw new IllegalStateException("Cancelation did not drain within five seconds");
			Thread.sleep(1);
		}
	}

	private static void stopCoordinator() throws InterruptedException {
		retainedCoordinator.stopAdmission();
		if (!retainedCoordinator.awaitTermination(System.nanoTime() + WAIT_NANOS))
			throw new IllegalStateException("Coordinator did not terminate within five seconds");
		long deadline = System.nanoTime() + WAIT_NANOS;
		while (ownedThreadsAlive()) {
			if (System.nanoTime() - deadline >= 0L)
				throw new IllegalStateException("A terminated coordinator retained a live owned thread");
			Thread.sleep(1);
		}
	}

	private static boolean ownedThreadsAlive() {
		for (Thread thread : Thread.getAllStackTraces().keySet())
			if (thread.isAlive() && ownedThreadIndex(thread.getName()) >= 0)
				return true;
		return false;
	}

	private static int ownedThreadIndex(String name) {
		if (name.startsWith("stream-callback-"))
			return 3;
		if (name.startsWith("stream-diagnostic-"))
			return 4;
		if (name.startsWith("stream-supervisor-"))
			return 5;
		return -1;
	}

	private static void capture(long[] sample) throws InterruptedException {
		captureCounts(sample);
		long minimumUsedHeap = Long.MAX_VALUE;
		for (int attempt = 0; attempt < 3; attempt++) {
			System.gc();
			Thread.sleep(50);
			Runtime runtime = Runtime.getRuntime();
			minimumUsedHeap = Math.min(minimumUsedHeap, runtime.totalMemory() - runtime.freeMemory());
		}
		sample[0] = minimumUsedHeap;
	}

	private static void captureCounts(long[] sample) {
		if (retainedCoordinator != null) {
			StreamLifecycleCoordinator.Snapshot snapshot = retainedCoordinator.snapshot();
			sample[1] = snapshot.reservations();
			sample[2] = snapshot.callbacks();
		} else {
			sample[1] = 0;
			sample[2] = 0;
		}
		sample[3] = sample[4] = sample[5] = 0;
		for (Thread thread : Thread.getAllStackTraces().keySet()) {
			int index = ownedThreadIndex(thread.getName());
			if (thread.isAlive() && index >= 0)
				sample[index]++;
		}
		sample[6] = ManagementFactory.getThreadMXBean().getThreadCount();
	}
}
