/*
 * Copyright 2022-2026 Revetware LLC.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 */
package com.soklet;

import com.soklet.annotation.SseEventSource;
import com.soklet.internal.microhttp.StreamLifecycleCoordinator;
import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.concurrent.locks.LockSupport;

/**
 * Representative real-socket SSE lifecycle qualification, not a maximum-throughput
 * benchmark or long-term soak. Compiles independently of the JMH/MCP module.
 * Server and validating clients share a JVM; CPU/heap/GC costs include both.
 * Properties: soklet.sse.seconds (60), warmupSeconds (5), roundsPerSecond (20),
 * payloadBytes (1024), churnCycles (10), churnClients (64). Admission,
 * connection queue and heartbeat defaults are never overridden.
 */
public final class SseConnectionQualification {
	private static final long WAIT = TimeUnit.SECONDS.toNanos(15);
	private static final int CONNECTIONS = 256;
	private static final long[] LATENCY_MICROS = {100, 250, 500, 1_000, 2_000, 5_000,
			10_000, 20_000, 50_000, 100_000, 200_000, 500_000, 1_000_000, 2_000_000,
			5_000_000, 10_000_000, Long.MAX_VALUE};
	private final int seconds = positive("seconds", 60);
	private final int warmupSeconds = positive("warmupSeconds", 5);
	private final int roundsPerSecond = positive("roundsPerSecond", 20);
	private final int payloadBytes = positive("payloadBytes", 1024);
	private final int churnCycles = positive("churnCycles", 10);
	private final int churnClients = positive("churnClients", 64);
	private final String payload = "0123456789abcdef".repeat((this.payloadBytes + 15) / 16).substring(0, this.payloadBytes);
	private final int warmupRounds = Math.multiplyExact(this.warmupSeconds, this.roundsPerSecond);
	private final int measuredRounds = Math.multiplyExact(this.seconds, this.roundsPerSecond);
	private final long measuredLastSequence = (long) this.warmupRounds + this.measuredRounds;
	private DefaultSseServer server;
	private final List<Client> clients = new ArrayList<>();
	private final AtomicReference<Throwable> failure = new AtomicReference<>();
	private final AtomicInteger initializers = new AtomicInteger();
	private final AtomicLong measuredEvents = new AtomicLong();
	private final AtomicLong allEvents = new AtomicLong();
	private final AtomicLong maximumLatencyNanos = new AtomicLong();
	private final AtomicLongArray latencyCounts = new AtomicLongArray(LATENCY_MICROS.length);
	private final AtomicInteger diagnosticErrors = new AtomicInteger();
	private final AtomicInteger maximumSampledReservations = new AtomicInteger();
	private final ExecutorService readers = Utilities.createVirtualThreadsNewThreadPerTaskExecutor(
			"sse-qualification-client-", (thread, failure) -> this.failure.compareAndSet(null, failure));
	private long sequence;
	private long maximumPacingDelayNanos;

	public static void main(String[] args) throws Exception {
		if (args.length == 1 && args[0].equals("--self-test")) { selfTest(); return; }
		if (args.length != 0) throw new IllegalArgumentException("Use system properties or --self-test");
		new SseConnectionQualification().run();
	}

	private void run() throws Exception {
		if (this.churnClients > CONNECTIONS || this.roundsPerSecond > 1_000 || this.payloadBytes > 1_048_576)
			throw new IllegalArgumentException("Unsupported qualification configuration");
		int port;
		try (ServerSocket available = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) { port = available.getLocalPort(); }
		DefaultSseServer server = (DefaultSseServer) SseServer.withPort(port).host("127.0.0.1").build();
		this.server = server;
		check(server.getStreamingLifecycleCapacity() == CONNECTIONS && server.getConnectionQueueCapacity() == 128,
				"Qualification must run against the selected defaults");
		Resource resource = new Resource(this);
		Soklet app = Soklet.fromConfig(SokletConfig.withSseServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Resource.class)))
				.instanceProvider(new InstanceProvider() {
					@Override public <T> T provide(Class<T> type) {
						return type == Resource.class ? type.cast(resource) : InstanceProvider.defaultInstance().provide(type);
					}
				}).lifecycleObserver(new LifecycleObserver() {
					@Override public void didReceiveLogEvent(LogEvent event) {
						if (event.getLogEventType() == LogEventType.SSE_SERVER_INTERNAL_ERROR) diagnosticErrors.incrementAndGet();
					}
				}).build());
		try {
			app.start();
			StreamLifecycleCoordinator coordinator = server.getStreamLifecycleCoordinatorForTests().orElseThrow();
			long establishmentStart = System.nanoTime();
			for (int index = 0; index < CONNECTIONS; index++) this.clients.add(connect(port, 1));
			await(() -> this.initializers.get() == CONNECTIONS && server.getGlobalConnections().size() == CONNECTIONS);
			long establishmentNanos = System.nanoTime() - establishmentStart;
			assertFullAndReject(coordinator, port);
			System.out.printf(Locale.ROOT, "PHASE established clients=%d mode=broadcaster%n", this.clients.size());
			rounds(this.warmupRounds, false);
			awaitDelivered(this.sequence);
			long measuredStart = System.nanoTime();
			long gcCountBefore = gcCount();
			long gcMillisBefore = gcMillis();
			long cpuBefore = processCpuTime();
			rounds(this.measuredRounds, true);
			awaitDelivered(this.sequence);
			long measuredNanos = System.nanoTime() - measuredStart;
			long gcCountDelta = gcCount() - gcCountBefore;
			long gcMillisDelta = gcMillis() - gcMillisBefore;
			long cpuNanosDelta = processCpuTime() - cpuBefore;
			check(this.measuredEvents.get() == (long) CONNECTIONS * this.measuredRounds, "Missing measured events");
			check(this.initializers.get() == CONNECTIONS && server.getGlobalConnections().size() == CONNECTIONS,
					"A healthy connection terminated during steady state");
			assertFullAndReject(coordinator, port);
			System.out.printf(Locale.ROOT, "PHASE steady-complete measuredEvents=%d elapsedSeconds=%.3f%n",
					this.measuredEvents.get(), measuredNanos / 1e9);

			long churnStart = System.nanoTime();
			for (int cycle = 0; cycle < this.churnCycles; cycle++) {
				int expectedClosed = (cycle + 1) * this.churnClients;
				for (int index = 0; index < this.churnClients; index++) {
					Client client = this.clients.remove(0);
					client.expectedClose.set(true); client.socket.setSoLinger(true, 0); client.socket.close();
					client.reader.get(15, TimeUnit.SECONDS);
				}
				// A reset becomes detectable on a write. Continue exact-sequence probes
				// until all selected clients physically retire before opening replacements.
				long deadline = System.nanoTime() + WAIT;
				while (coordinator.snapshot().reservations() != CONNECTIONS - this.churnClients
						|| server.getGlobalConnections().size() != CONNECTIONS - this.churnClients) {
					check(System.nanoTime() < deadline, "Disconnected lifetimes did not retire");
					publish(); Thread.sleep(25); failIfNeeded();
				}
				awaitDelivered(this.sequence);
				for (int index = 0; index < this.churnClients; index++) this.clients.add(connect(port, this.sequence + 1));
				await(() -> server.getGlobalConnections().size() == CONNECTIONS);
				publish(); awaitDelivered(this.sequence);
				assertFullAndReject(coordinator, port);
				check(this.initializers.get() == CONNECTIONS + expectedClosed,
						"Incorrect reconnect initialization accounting");
				System.out.printf(Locale.ROOT, "PHASE churn cycle=%d replacements=%d%n", cycle + 1, expectedClosed);
			}
			long churnNanos = System.nanoTime() - churnStart;
			for (Client client : this.clients) client.expectedClose.set(true);
			long shutdownStart = System.nanoTime();
			ShutdownResult shutdown = app.shutdown().toCompletableFuture().get(20, TimeUnit.SECONDS);
			long shutdownNanos = System.nanoTime() - shutdownStart;
			check(shutdown.isComplete(), "Shutdown left residual work");
			for (Client client : this.clients) client.reader.get(15, TimeUnit.SECONDS);
			this.readers.shutdown(); check(this.readers.awaitTermination(15, TimeUnit.SECONDS), "Client readers did not stop");
			failIfNeeded();
			StreamLifecycleCoordinator.Snapshot terminal = coordinator.snapshot();
			check(coordinator.isTerminated() && terminal.reservations() == 0 && terminal.retainedWork() == 0
					&& terminal.callbacks() == 0 && terminal.diagnostics() == 0, "Coordinator did not physically retire");
			check(server.getGlobalConnections().isEmpty(), "SSE connections survived shutdown");
			check(this.diagnosticErrors.get() == 0, "Unexpected SSE lifecycle diagnostics");
			System.out.printf(Locale.ROOT, "RESULT {\"passed\":true,\"java\":\"%s\",\"connections\":%d,\"deliveryMode\":\"broadcaster\",\"queueCapacity\":128,\"payloadBytes\":%d,\"roundsPerSecond\":%d,\"warmupRounds\":%d,\"measuredRounds\":%d,\"measuredEvents\":%d,\"allValidatedEvents\":%d,\"measuredSeconds\":%.6f,\"eventsPerSecond\":%.3f,\"establishmentMillis\":%.3f,\"p50LatencyUpperMicros\":%d,\"p95LatencyUpperMicros\":%d,\"p99LatencyUpperMicros\":%d,\"maximumLatencyMicros\":%.3f,\"maximumPacingDelayMillis\":%.3f,\"wholeJvmCpuSeconds\":%.6f,\"wholeJvmGcCount\":%d,\"wholeJvmGcMillis\":%d,\"churnCycles\":%d,\"replacedClients\":%d,\"churnSeconds\":%.6f,\"shutdownMillis\":%.3f,\"initializers\":%d,\"maximumSampledReservations\":%d,\"remainingReservations\":%d,\"remainingWork\":%d,\"diagnosticErrors\":%d}%n",
					System.getProperty("java.version"), CONNECTIONS, this.payloadBytes, this.roundsPerSecond,
					this.warmupRounds, this.measuredRounds, this.measuredEvents.get(), this.allEvents.get(), measuredNanos / 1e9,
					this.measuredEvents.get() / (measuredNanos / 1e9), establishmentNanos / 1e6,
					percentile(.5), percentile(.95), percentile(.99), this.maximumLatencyNanos.get() / 1e3,
					this.maximumPacingDelayNanos / 1e6, cpuNanosDelta / 1e9, gcCountDelta, gcMillisDelta,
					this.churnCycles, this.churnCycles * this.churnClients, churnNanos / 1e9, shutdownNanos / 1e6,
					this.initializers.get(), this.maximumSampledReservations.get(),
					terminal.reservations(), terminal.retainedWork(), this.diagnosticErrors.get());
			System.out.println("LATENCY_BUCKET_UPPER_MICROS=" + java.util.Arrays.toString(LATENCY_MICROS));
			System.out.println("LATENCY_BUCKET_COUNTS=" + this.latencyCounts);
		} finally {
			for (Client client : this.clients) { client.expectedClose.set(true); try { client.socket.close(); } catch (IOException ignored) {} }
			this.readers.shutdownNow();
			app.close();
		}
	}

	private Client connect(int port, long firstSequence) throws Exception {
		Socket socket = new Socket("127.0.0.1", port); socket.setSoTimeout(15_000);
		try {
			BufferedReader input = request(socket);
			check(headers(input).startsWith("HTTP/1.1 200 "), "SSE handshake rejected");
			Client client = new Client(socket, firstSequence - 1);
			client.reader = this.readers.submit(() -> {
				try {
					while (true) {
						Frame frame = readFrame(input, this.payloadBytes + 512);
						if (frame == null) { check(client.expectedClose.get(), "Unexpected SSE EOF"); return; }
						validate(frame, client.lastSequence.get() + 1, this.payload);
						long elapsed = System.nanoTime() - frame.sentNanos;
						check(elapsed >= 0, "Event timestamp is in the future");
						this.allEvents.incrementAndGet();
						if (frame.sequence > this.warmupRounds && frame.sequence <= this.measuredLastSequence) {
							this.measuredEvents.incrementAndGet(); this.maximumLatencyNanos.accumulateAndGet(elapsed, Math::max);
							long micros = elapsed / 1_000 + (elapsed % 1_000 == 0 ? 0 : 1);
							for (int i = 0; i < LATENCY_MICROS.length; i++) if (micros <= LATENCY_MICROS[i]) { this.latencyCounts.incrementAndGet(i); break; }
						}
						client.lastSequence.set(frame.sequence);
					}
				} catch (java.net.SocketException error) {
					if (!client.expectedClose.get()) this.failure.compareAndSet(null, error);
				} catch (Throwable error) { this.failure.compareAndSet(null, error); }
			});
			return client;
		} catch (Throwable error) { socket.close(); throw error; }
	}

	private static BufferedReader request(Socket socket) throws IOException {
		socket.getOutputStream().write("GET /qualification HTTP/1.1\r\nHost: localhost\r\nAccept: text/event-stream\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
		return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
	}
	private static String headers(BufferedReader reader) throws IOException {
		String first = line(reader, 8_192); if (first == null) throw new EOFException("Missing status line");
		int size = first.length();
		while (true) { String line = line(reader, 8_192); if (line == null) throw new EOFException("Truncated headers"); size += line.length(); check(size < 32_768, "Oversized headers"); if (line.isEmpty()) return first; }
	}
	private void assertFullAndReject(StreamLifecycleCoordinator coordinator, int port) throws Exception {
		int initialized = this.initializers.get();
		await(() -> coordinator.snapshot().reservations() == CONNECTIONS);
		this.maximumSampledReservations.accumulateAndGet(coordinator.snapshot().reservations(), Math::max);
		try (Socket rejected = new Socket("127.0.0.1", port)) {
			rejected.setSoTimeout(15_000); check(headers(request(rejected)).startsWith("HTTP/1.1 503 "), "257th client was not rejected before acceptance");
		}
		check(this.initializers.get() == initialized, "Capacity rejection entered an initializer");
	}
	private void rounds(int count, boolean measured) throws Exception {
		long step = TimeUnit.SECONDS.toNanos(1) / this.roundsPerSecond;
		long next = System.nanoTime();
		for (int i = 0; i < count; i++) {
			parkUntil(next); if (measured) this.maximumPacingDelayNanos = Math.max(this.maximumPacingDelayNanos, System.nanoTime() - next);
			publish(); next += step; failIfNeeded();
		}
		parkUntil(next);
	}
	private void publish() {
		long sent = System.nanoTime(); this.sequence++;
		SseEvent event = SseEvent.withEvent("qualification").id(Long.toString(this.sequence)).data(sent + "|" + this.payload).build();
		var broadcaster = this.server.acquireBroadcaster(ResourcePath.fromPath("/qualification"));
		if (broadcaster.isPresent()) broadcaster.get().broadcastEvent(event);
		else check(this.server.getGlobalConnections().isEmpty(), "A live SSE connection has no broadcaster");
	}
	private void awaitDelivered(long sequence) throws Exception { await(() -> this.clients.stream().allMatch(client -> client.lastSequence.get() == sequence)); }
	private void await(BooleanSupplier condition) throws Exception {
		long deadline = System.nanoTime() + WAIT;
		while (!condition.getAsBoolean()) { failIfNeeded(); check(System.nanoTime() < deadline, "Qualification wait expired"); Thread.sleep(2); }
		failIfNeeded();
	}
	private void failIfNeeded() { Throwable error = this.failure.get(); if (error != null) throw new IllegalStateException("Client validation failed", error); }
	private long percentile(double fraction) {
		long target = (long) Math.ceil(this.measuredEvents.get() * fraction), count = 0;
		for (int i = 0; i < LATENCY_MICROS.length; i++) { count += this.latencyCounts.get(i); if (count >= target) return LATENCY_MICROS[i]; }
		throw new IllegalStateException("Incomplete latency histogram");
	}
	private static void parkUntil(long deadline) throws InterruptedException {
		for (long remaining; (remaining = deadline - System.nanoTime()) > 0;) { LockSupport.parkNanos(remaining); if (Thread.interrupted()) throw new InterruptedException(); }
	}
	private static long gcCount() { return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(bean -> Math.max(0, bean.getCollectionCount())).sum(); }
	private static long gcMillis() { return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(bean -> Math.max(0, bean.getCollectionTime())).sum(); }
	private static long processCpuTime() { return ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean()).getProcessCpuTime(); }
	private static int positive(String name, int fallback) { int value = Integer.getInteger("soklet.sse." + name, fallback); if (value <= 0) throw new IllegalArgumentException(name + " must be positive"); return value; }
	private static void check(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }

	public static final class Resource {
		private final SseConnectionQualification owner;
		private Resource(SseConnectionQualification owner) { this.owner = owner; }
		@SseEventSource("/qualification") public SseHandshakeResult stream() {
			return SseHandshakeResult.Accepted.builder().clientInitializer(sseUnicaster -> {
				this.owner.initializers.incrementAndGet();
			}).build();
		}
	}
	private static final class Client {
		private final Socket socket;
		private final AtomicLong lastSequence;
		private final AtomicBoolean expectedClose = new AtomicBoolean();
		private Future<?> reader;
		private Client(Socket socket, long last) { this.socket = socket; this.lastSequence = new AtomicLong(last); }
	}
	private record Frame(long sequence, long sentNanos, String payload) {}
	private static Frame readFrame(BufferedReader reader, int limit) throws IOException {
		String id = null, data = null, event = null; int bytes = 0;
		while (true) {
			String line = line(reader, limit);
			if (line == null) { if (id != null || data != null || event != null) throw new EOFException("Truncated SSE frame"); return null; }
			bytes += line.length(); check(bytes <= limit, "Oversized SSE frame");
			if (line.isEmpty()) {
				if (id == null && data == null && event == null) { bytes = 0; continue; }
				check(id != null && data != null && "qualification".equals(event), "Incomplete or unexpected SSE frame");
				int split = data.indexOf('|'); check(split > 0, "Missing event timestamp");
				return new Frame(Long.parseLong(id), Long.parseLong(data.substring(0, split)), data.substring(split + 1));
			}
			if (line.startsWith(":")) continue;
			if (line.startsWith("id: ")) { check(id == null, "Duplicate id"); id = line.substring(4); }
			else if (line.startsWith("event: ")) { check(event == null, "Duplicate event"); event = line.substring(7); }
			else if (line.startsWith("data: ")) { check(data == null, "Duplicate data"); data = line.substring(6); }
			else throw new IllegalStateException("Unexpected SSE line");
		}
	}
	private static String line(BufferedReader reader, int limit) throws IOException {
		StringBuilder text = new StringBuilder();
		for (int value; (value = reader.read()) != -1;) {
			if (value == '\n') { if (!text.isEmpty() && text.charAt(text.length() - 1) == '\r') text.setLength(text.length() - 1); return text.toString(); }
			check(text.length() < limit, "Oversized line"); text.append((char) value);
		}
		if (!text.isEmpty()) throw new EOFException("Unterminated line");
		return null;
	}
	private static void validate(Frame frame, long expectedSequence, String expectedPayload) {
		check(frame.sequence == expectedSequence, "Missing, reordered, or duplicate event");
		check(frame.payload.equals(expectedPayload), "Invalid event payload");
	}
	private static void selfTest() throws Exception {
		Frame valid = readFrame(new BufferedReader(new StringReader(":\n\nid: 1\nevent: qualification\ndata: 123|hello\n\n")), 200);
		validate(valid, 1, "hello");
		int failures = 0;
		for (String malformed : List.of("id: 1", "id: 1\n", "id: 1\nevent: qualification\ndata: 123|hello\n", "data: 123|hello\n\n", "id: 1\nid: 1\nevent: qualification\ndata: 123|hello\n\n", "id: 1\nevent: wrong\ndata: 123|hello\n\n", "id: 1\nevent: qualification\ndata: missing-timestamp\n\n")) {
			try { readFrame(new BufferedReader(new StringReader(malformed)), 200); }
			catch (IOException | IllegalStateException rejected) { failures++; }
		}
		check(failures == 7, "Parser accepted a malformed frame");
		try { validate(valid, 2, "hello"); throw new AssertionError("Sequence mismatch accepted"); } catch (IllegalStateException expected) {}
		try { validate(valid, 1, "different"); throw new AssertionError("Payload mismatch accepted"); } catch (IllegalStateException expected) {}
		System.out.println("SELF_TEST passed=10");
	}
}
