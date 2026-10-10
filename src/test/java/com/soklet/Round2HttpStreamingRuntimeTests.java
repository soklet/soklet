package com.soklet;

import com.soklet.annotation.GET;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import static com.soklet.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class Round2HttpStreamingRuntimeTests {
	@Test void abortedFileDownloadDoesNotBecomeAServerTransportError(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
		java.nio.file.Path file = directory.resolve("download");
		try (java.io.RandomAccessFile sparse = new java.io.RandomAccessFile(file.toFile(), "rw")) { sparse.setLength(64L * 1024 * 1024); }
		for (boolean reset : List.of(false, true)) {
			Fixture fixture = new Fixture(false, Duration.ofSeconds(3));
			try (fixture) {
				fixture.resource.file = file;
				try (Socket client = fixture.request("/file", "HTTP/1.1")) {
					assertTrue(readHeaders(client).startsWith("HTTP/1.1 200"));
					if (reset) client.setSoLinger(true, 0);
				}
			}
			assertTrue(fixture.logs.stream().noneMatch(event -> event.getLogEventType() == LogEventType.SERVER_TRANSPORT_FAILURE), fixture.logs.toString());
		}
	}

	@Test
	@Timeout(value = 110, unit = TimeUnit.SECONDS)
	void zipCleanupAndInterruptibleUpstreamRemainQuietAfterLiveDisconnect() throws Exception {
		for (String path : List.of("/zip", "/zip", "/zip", "/pipe")) {
			Fixture fixture = new Fixture(false, Duration.ofSeconds(3));
			try (fixture; Socket client = fixture.request(path, "HTTP/1.1")) {
				assertTrue(readHeaders(client).startsWith("HTTP/1.1 200"));
				assertTrue(fixture.resource.producerEntered.await(2, TimeUnit.SECONDS));
				client.setSoLinger(true, 0); client.close();
				if (path.equals("/zip")) {
					await(() -> fixture.resource.token != null && fixture.resource.token.isCanceled());
					fixture.resource.releaseProducer.countDown();
				}
				await(() -> !fixture.terminals.isEmpty());
				assertEquals(StreamTerminationReason.CLIENT_DISCONNECTED, fixture.terminals.get(0).getReason());
				assertFalse(fixture.terminals.get(0).getCause().orElseThrow().getClass().getName().contains("SocketIoException"));
			}
			assertTrue(fixture.logs.stream().noneMatch(event -> event.getLogEventType() == LogEventType.RESPONSE_STREAM_FAILED
					|| event.getLogEventType() == LogEventType.RESPONSE_STREAM_CLOSE_FAILED), fixture.logs.toString());
		}
	}
	@Test void lateFiniteAndStreamingHandlersObserveTheTimeoutResponseWithoutStreamAdmission() throws Exception {
		for (boolean streaming : List.of(false, true)) {
			try (Fixture fixture = new Fixture(false, Duration.ofMillis(100)); Socket client = fixture.request("/late", "HTTP/1.1")) {
				fixture.resource.lateStreaming = streaming;
				assertTrue(fixture.resource.handlerEntered.await(2, TimeUnit.SECONDS));
				assertTrue(read(client).startsWith("HTTP/1.1 503"));
				fixture.resource.releaseHandler.countDown();
				await(() -> fixture.finishes.size() == 1);
				assertEquals(List.of(503), fixture.writes);
				assertEquals(List.of(503), fixture.finishes);
				assertTrue(fixture.terminals.isEmpty());
				assertEquals(0, fixture.resource.producerCalls.get());
				assertEquals(0, fixture.server.getStreamLifecycleCoordinatorForTests().orElseThrow().snapshot().reservations());
				assertEquals(0L, fixture.metrics.snapshot().orElseThrow().getActiveRequests());
				assertTrue(fixture.metrics.snapshot().orElseThrow().getHttpResponseStreamTerminations().isEmpty());
				assertTrue(fixture.logs.stream().noneMatch(event -> event.getLogEventType() == LogEventType.RESPONSE_STREAM_CANCELED));
			}
		}
	}

	@Test
	@org.junit.jupiter.api.condition.EnabledForJreRange(min = org.junit.jupiter.api.condition.JRE.JAVA_21)
	void classicSocketUpstreamInterruptRemainsQuietAfterLiveDisconnect() throws Exception {
		Fixture fixture = new Fixture(false, Duration.ofSeconds(3));
		try (fixture; Socket client = fixture.request("/socket", "HTTP/1.1")) {
			assertTrue(readHeaders(client).startsWith("HTTP/1.1 200"));
			assertTrue(fixture.resource.producerEntered.await(2, TimeUnit.SECONDS));
			client.setSoLinger(true, 0);
			client.close();
			await(() -> !fixture.terminals.isEmpty());
			assertEquals(StreamTerminationReason.CLIENT_DISCONNECTED, fixture.terminals.get(0).getReason());
		}
		assertTrue(fixture.logs.stream().noneMatch(event -> event.getLogEventType() == LogEventType.RESPONSE_STREAM_FAILED
				|| event.getLogEventType() == LogEventType.RESPONSE_STREAM_CLOSE_FAILED
				|| event.getLogEventType() == LogEventType.SERVER_INTERNAL_ERROR), fixture.logs.toString());
	}

	@Test void throwingWillWriteObserverStillWritesBeforeFinishForNormalAndProtocolReplacedStreams() throws Exception {
		for (String version : List.of("HTTP/1.1", "HTTP/1.0")) {
			try (Fixture fixture = new Fixture(true, Duration.ofSeconds(3)); Socket client = fixture.request("/stream", version)) {
				String wire = read(client);
				int status = version.equals("HTTP/1.0") ? 505 : 200;
				assertTrue(wire.startsWith(version + " " + status), wire);
				await(() -> fixture.metrics.snapshot().orElseThrow().getActiveRequests() == 0 && fixture.finishes.size() == 1);
				assertEquals(List.of(status), fixture.writes);
				assertEquals(List.of(status), fixture.finishes);
				assertEquals(List.of("write", "finish"), fixture.order);
				assertTrue(fixture.logs.stream().anyMatch(event -> event.getLogEventType() == LogEventType.LIFECYCLE_OBSERVER_WILL_WRITE_RESPONSE_FAILED));
				assertTrue(fixture.logs.stream().noneMatch(event -> event.getLogEventType() == LogEventType.REQUEST_INTERCEPTOR_WRAP_REQUEST_FAILED));
				assertEquals(status == 200 ? 1 : 0, fixture.resource.producerCalls.get());
				assertEquals(status == 200 ? 1 : 0, fixture.metrics.snapshot().orElseThrow().getHttpResponseStreamTerminations().size());
			}
		}
	}

	@Test void throwingWillWriteObserverDoesNotLeakMetricsOnCapacityReplacement() throws Exception {
		try (Fixture fixture = new Fixture(true, Duration.ofSeconds(3)); Socket held = fixture.request("/held", "HTTP/1.1")) {
			assertTrue(readHeaders(held).startsWith("HTTP/1.1 200"));
			assertTrue(fixture.resource.producerEntered.await(2, TimeUnit.SECONDS));
			try (Socket rejected = fixture.request("/stream", "HTTP/1.1")) {
				assertTrue(read(rejected).startsWith("HTTP/1.1 503"));
			}
			await(() -> fixture.finishes.size() == 2);
			assertEquals(List.of(200, 503), fixture.writes);
			assertEquals(1L, fixture.metrics.snapshot().orElseThrow().getActiveRequests());
			fixture.resource.releaseProducer.countDown();
			read(held);
			await(() -> fixture.metrics.snapshot().orElseThrow().getActiveRequests() == 0);
			assertEquals(1L, fixture.metrics.snapshot().orElseThrow().getHttpResponseStreamTerminations().values().stream().mapToLong(Long::longValue).sum());
		}
	}

	@Test void routineDisconnectRetainsItsTerminationWithoutCancelationStackTraceLog() throws Exception {
		try (Fixture fixture = new Fixture(false, Duration.ofSeconds(3)); Socket client = fixture.request("/writing", "HTTP/1.1")) {
			assertTrue(readHeaders(client).startsWith("HTTP/1.1 200"));
			assertTrue(fixture.resource.producerEntered.await(2, TimeUnit.SECONDS));
			client.setSoLinger(true, 0);
			client.close();
			await(() -> !fixture.terminals.isEmpty());
			assertEquals(StreamTerminationReason.CLIENT_DISCONNECTED, fixture.terminals.get(0).getReason());
			assertTrue(fixture.logs.stream().noneMatch(event -> event.getLogEventType() == LogEventType.RESPONSE_STREAM_CANCELED));
		}
	}

	private static String read(Socket socket) throws Exception {
		java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
		int value;
		while ((value = readWithDeadline(socket, deadline)) != -1) {
			assertTrue(bytes.size() < 65_536, "Response exceeded the fixture byte bound");
			bytes.write(value);
		}
		return bytes.toString(StandardCharsets.ISO_8859_1);
	}
	private static String readHeaders(Socket socket) throws Exception {
		StringBuilder result = new StringBuilder();
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
		while (!result.toString().endsWith("\r\n\r\n")) {
			assertTrue(result.length() < 16_384, "Headers exceeded the fixture byte bound");
			int value = readWithDeadline(socket, deadline);
			if (value < 0) break;
			result.append((char) value);
		}
		return result.toString();
	}
	private static int readWithDeadline(Socket socket, long deadline) throws Exception {
		long remaining = deadline - System.nanoTime();
		if (remaining <= 0) throw new java.net.SocketTimeoutException("Fixture read deadline elapsed");
		int original = socket.getSoTimeout();
		try {
			socket.setSoTimeout((int) Math.max(1, Math.min(3000, (remaining + 999_999) / 1_000_000)));
			return socket.getInputStream().read();
		} finally {
			socket.setSoTimeout(original);
		}
	}
	private static void await(BooleanSupplier condition) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
		while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
		assertTrue(condition.getAsBoolean());
	}
	public static final class Resource {
		volatile java.nio.file.Path file;
		volatile CancelationToken token;
		final CountDownLatch handlerEntered = new CountDownLatch(1), releaseHandler = new CountDownLatch(1);
		final CountDownLatch producerEntered = new CountDownLatch(1), releaseProducer = new CountDownLatch(1);
		final AtomicInteger producerCalls = new AtomicInteger();
		volatile boolean lateStreaming;
		@GET("/file") public MarshaledResponse file() throws Exception {
			return MarshaledResponse.withStatusCode(200).body(new MarshaledResponseBody.File(file, 0L, java.nio.file.Files.size(file))).build();
		}
		@GET("/zip") public MarshaledResponse zip() {
			return MarshaledResponse.withStatusCode(200).stream(writer -> {
				java.util.zip.ZipOutputStream zip = writer.own(new java.util.zip.ZipOutputStream(writer.asOutputStream()));
				zip.putNextEntry(new java.util.zip.ZipEntry("payload"));
				zip.write(new byte[]{1, 2, 3}); writer.flush(); token = writer.getCancelationToken();
				producerEntered.countDown();
				boolean interrupted = false;
				while (true) {
					try { releaseProducer.await(); break; }
					catch (InterruptedException ignored) { interrupted = true; }
				}
				if (interrupted) Thread.currentThread().interrupt();
				zip.closeEntry();
			}).build();
		}
		@GET("/pipe") public MarshaledResponse pipe() {
			return MarshaledResponse.withStatusCode(200).stream(writer -> {
				java.nio.channels.Pipe pipe = java.nio.channels.Pipe.open();
				try (var source = pipe.source(); var sink = pipe.sink()) {
					writer.write(new byte[]{1}); writer.flush(); producerEntered.countDown();
					source.read(java.nio.ByteBuffer.allocate(1));
				}
			}).build();
		}
		@GET("/stream") public MarshaledResponse stream() {
			return MarshaledResponse.withStatusCode(200).stream(writer -> producerCalls.incrementAndGet()).build();
		}
		@GET("/socket") public MarshaledResponse socket() {
			return MarshaledResponse.withStatusCode(200).stream(writer -> {
				try (java.net.ServerSocket listener = new java.net.ServerSocket()) {
					listener.bind(new java.net.InetSocketAddress("127.0.0.1", 0));
					listener.setSoTimeout(2000);
					try (Socket upstream = connectWithRetry("127.0.0.1", listener.getLocalPort(), 2000);
							 Socket peer = listener.accept()) {
						upstream.setSoTimeout(5000);
						writer.write(new byte[]{1}); writer.flush(); producerEntered.countDown();
						upstream.getInputStream().read();
					}
				}
			}).build();
		}
		@GET("/held") public MarshaledResponse held() {
			return MarshaledResponse.withStatusCode(200).stream(writer -> {
				producerCalls.incrementAndGet(); producerEntered.countDown(); releaseProducer.await();
			}).build();
		}
		@GET("/writing") public MarshaledResponse writing() {
			return MarshaledResponse.withStatusCode(200).stream(writer -> {
				producerCalls.incrementAndGet(); producerEntered.countDown();
				while (true) {
					writer.write(new byte[]{1});
					Thread.sleep(10);
				}
			}).build();
		}
		@GET("/late") public MarshaledResponse late() {
			handlerEntered.countDown();
			boolean interrupted = false;
			while (true) {
				try { releaseHandler.await(); break; }
				catch (InterruptedException ignored) { interrupted = true; }
			}
			if (interrupted) Thread.currentThread().interrupt();
			return lateStreaming ? stream() : MarshaledResponse.withStatusCode(200).body(new byte[]{1}).build();
		}
	}
	private static final class Fixture implements AutoCloseable {
		final int port = findFreePort();
		final Resource resource = new Resource();
		final DefaultHttpServer server;
		final Soklet soklet;
		final DefaultMetricsCollector metrics = DefaultMetricsCollector.defaultInstance();
		final List<Integer> writes = new CopyOnWriteArrayList<>(), finishes = new CopyOnWriteArrayList<>();
		final List<String> order = new CopyOnWriteArrayList<>();
		final List<LogEvent> logs = new CopyOnWriteArrayList<>();
		final List<StreamTermination> terminals = new CopyOnWriteArrayList<>();
		Fixture(boolean throwWillWrite, Duration timeout) throws Exception {
			server = (DefaultHttpServer) HttpServer.withPort(port).host("127.0.0.1").concurrency(2)
					.requestHandlerTimeout(timeout).streamingLifecycleCapacity(1).streamingCallbackConcurrency(1).build();
			soklet = Soklet.fromConfig(SokletConfig.withHttpServer(server)
					.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Resource.class)))
					.instanceProvider(new InstanceProvider() { @Override public <T> T provide(Class<T> type) {
						return type == Resource.class ? type.cast(resource) : InstanceProvider.defaultInstance().provide(type);
					}}).metricsCollector(metrics).lifecycleObserver(new LifecycleObserver() {
						@Override public void willWriteResponse(ServerType type, Request request, ResourceMethod method, MarshaledResponse response) {
							if (throwWillWrite) throw new IllegalStateException("observer failure");
						}
						@Override public void didWriteResponse(ServerType type, Request request, ResourceMethod method, MarshaledResponse response, Duration duration) {
							writes.add(response.getStatusCode()); order.add("write");
						}
						@Override public void didFinishRequestHandling(ServerType type, Request request, ResourceMethod method, MarshaledResponse response, Duration duration, List<Throwable> failures) {
							finishes.add(response.getStatusCode()); order.add("finish");
						}
						@Override public void didReceiveLogEvent(LogEvent event) { logs.add(event); }
						@Override public void didTerminateResponseStream(StreamingResponseHandle handle, StreamTermination termination) { terminals.add(termination); }
					}).lifecyclePolicy(LifecyclePolicy.builder().startupTimeout(Duration.ofSeconds(10))
							.startupCancelationTimeout(Duration.ofSeconds(1)).gracefulShutdownTimeout(Duration.ofSeconds(1))
							.forcedShutdownTimeout(Duration.ofSeconds(1)).build()).build());
			soklet.start();
		}
		Socket request(String path, String version) throws Exception {
			Socket socket = connectWithRetry("127.0.0.1", port, 2000); socket.setSoTimeout(3000);
			socket.getOutputStream().write(("GET " + path + " " + version + "\r\nHost: localhost\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
			return socket;
		}
		@Override public void close() { resource.releaseHandler.countDown(); resource.releaseProducer.countDown(); soklet.close(); }
	}
}
