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

import com.soklet.annotation.GET;
import com.soklet.internal.microhttp.EventLoop;
import com.soklet.internal.microhttp.LogEntry;
import com.soklet.internal.microhttp.Logger;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.annotation.concurrent.ThreadSafe;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static com.soklet.TestSupport.connectWithRetry;
import static com.soklet.TestSupport.findFreePort;
import static com.soklet.TestSupport.readAll;

/*
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class HttpServerLifecycleTests {
	private static HttpURLConnection open(String method, URL url, Map<String, String> headers) throws IOException {
		HttpURLConnection c = (HttpURLConnection) url.openConnection();
		c.setRequestMethod(method);
		c.setConnectTimeout(2000);
		c.setReadTimeout(2000);
		for (Map.Entry<String, String> e : headers.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
		return c;
	}

	@Test
	public void start_stop_isStarted_toggles_and_serves_requests() throws Exception {
		int port = findFreePort();
		SokletConfig cfg = SokletConfig.withHttpServer(HttpServer.withPort(port)
						.requestHeaderTimeout(Duration.ofSeconds(5))
						.build())
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(HealthResource.class)))
				.lifecycleObserver(new LifecycleObserver() {
					@Override
					public void didReceiveLogEvent(@NonNull LogEvent logEvent) { /* quiet */ }
				})
				.build();

		try (Soklet app = Soklet.fromConfig(cfg)) {
			Assertions.assertFalse(app.isStarted());
			app.start();
			Assertions.assertTrue(app.isStarted());

			URL url = new URL("http://127.0.0.1:" + port + "/health");
			HttpURLConnection c = open("GET", url, Map.of("Accept", "text/plain"));
			Assertions.assertEquals(200, c.getResponseCode());
			Assertions.assertEquals("ok", new String(readAll(c.getInputStream()), StandardCharsets.UTF_8));
		}
		// try-with-resources calls close(), which stops the server
		// Can't call isStarted() after close() directly; create again to check false
		Soklet app2 = Soklet.fromConfig(cfg);
		try {
			Assertions.assertFalse(app2.isStarted());
		} finally {
			app2.close();
		}
	}

	@Test
	public void start_without_initialize_fails_fast() {
		HttpServer httpServer = HttpServer.withPort(0).build();

		IllegalStateException exception =
				Assertions.assertThrows(IllegalStateException.class, httpServer::start);
		Assertions.assertTrue(exception.getMessage().contains("RequestHandler"));
		Assertions.assertFalse(httpServer.isStarted());
	}

	@Test
	public void start_port_in_use_cleans_up_state() throws Exception {
		int port = findFreePort();

		try (ServerSocket ss = new ServerSocket(port)) {
			ss.setReuseAddress(true);

			HttpServer httpServer = HttpServer.withPort(port).build();

			SokletConfig cfg = SokletConfig.withHttpServer(httpServer)
					.lifecycleObserver(new QuietLifecycle())
					.build();

			httpServer.initialize(cfg, (request, consumer) -> {
				MarshaledResponse response = MarshaledResponse.withStatusCode(200)
						.headers(Map.of("Content-Type", Set.of("text/plain")))
						.body("ok".getBytes(StandardCharsets.UTF_8))
						.build();
				consumer.accept(HttpRequestResult.withMarshaledResponse(response).build());
			});

			Assertions.assertThrows(UncheckedIOException.class, httpServer::start);
			Assertions.assertFalse(httpServer.isStarted());

			DefaultHttpServer internal = (DefaultHttpServer) httpServer;
			Assertions.assertTrue(internal.getEventLoop().isEmpty());
			Assertions.assertTrue(internal.getRequestHandlerExecutorService().isEmpty());
		}
	}

	@Test
	public void isStartedReflectsTerminatedHttpEventLoopAndStopStillCleansUp() throws Exception {
		int port = findFreePort();
		HttpServer httpServer = HttpServer.withPort(port)
				.host("127.0.0.1")
				.build();
		SokletConfig cfg = SokletConfig.withHttpServer(httpServer)
				.lifecycleObserver(new QuietLifecycle())
				.build();
		httpServer.initialize(cfg, (request, consumer) -> {
			MarshaledResponse response = MarshaledResponse.withStatusCode(200)
					.headers(Map.of("Content-Type", Set.of("text/plain")))
					.body("ok".getBytes(StandardCharsets.UTF_8))
					.build();
			consumer.accept(HttpRequestResult.withMarshaledResponse(response).build());
		});

		httpServer.start();
		DefaultHttpServer internal = (DefaultHttpServer) httpServer;
		EventLoop eventLoop = internal.getEventLoop().orElseThrow();
		eventLoop.stop();

		Assertions.assertFalse(httpServer.isStarted());

		httpServer.stop();
		Assertions.assertTrue(internal.getEventLoop().isEmpty());
		Assertions.assertTrue(internal.getRequestHandlerExecutorService().isEmpty());
	}

	@Test
	public void stopDrainsInFlightHttpResponseBeforeClosingConnection() throws Exception {
		int port = findFreePort();
		CountDownLatch handlerStarted = new CountDownLatch(1);
		CountDownLatch releaseHandler = new CountDownLatch(1);
		AtomicReference<Throwable> stopFailure = new AtomicReference<>();
		HttpServer httpServer = HttpServer.withPort(port)
				.host("127.0.0.1")
				.shutdownTimeout(Duration.ofSeconds(3))
				.build();
		SokletConfig cfg = SokletConfig.withHttpServer(httpServer)
				.lifecycleObserver(new QuietLifecycle())
				.build();
		httpServer.initialize(cfg, (request, consumer) -> {
			handlerStarted.countDown();
			try {
				if (!releaseHandler.await(2, TimeUnit.SECONDS))
					throw new AssertionError("Timed out waiting to release handler");
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}

			MarshaledResponse response = MarshaledResponse.withStatusCode(200)
					.headers(Map.of("Content-Type", Set.of("text/plain; charset=UTF-8")))
					.body("done".getBytes(StandardCharsets.UTF_8))
					.build();
			consumer.accept(HttpRequestResult.withMarshaledResponse(response).build());
		});

		httpServer.start();

		try (Socket socket = connectWithRetry("127.0.0.1", port, 2000)) {
			socket.setSoTimeout(4000);
			OutputStream outputStream = socket.getOutputStream();
			outputStream.write(("""
					GET /slow HTTP/1.1\r
					Host: localhost\r
					Connection: keep-alive\r
					\r
					""").getBytes(StandardCharsets.ISO_8859_1));
			outputStream.flush();

			Assertions.assertTrue(handlerStarted.await(2, TimeUnit.SECONDS), "Expected request handler to start");

			EventLoop eventLoop = ((DefaultHttpServer) httpServer).getEventLoop().orElseThrow();
			Thread stopThread = new Thread(() -> {
				try {
					httpServer.stop();
				} catch (Throwable t) {
					stopFailure.set(t);
				}
			}, "http-graceful-stop-test");
			stopThread.start();

			assertEventually(() -> !eventLoop.isAccepting(), Duration.ofSeconds(2),
					"Expected HTTP accept loop to stop before handler response is released");

			releaseHandler.countDown();

			String response = new String(readAll(socket.getInputStream()), StandardCharsets.ISO_8859_1);
			Assertions.assertTrue(response.startsWith("HTTP/1.1 200 OK"), response);
			Assertions.assertTrue(response.contains("Connection: close\r\n"), response);
			Assertions.assertTrue(response.endsWith("\r\n\r\ndone"), response);

			stopThread.join(3000);
			Assertions.assertFalse(stopThread.isAlive(), "HTTP stop did not complete after response drain");
			if (stopFailure.get() != null)
				throw new AssertionError("HTTP stop failed", stopFailure.get());
		} finally {
			httpServer.stop();
		}
	}

	@Test
	public void stopClosesIdleHttpKeepAliveConnections() throws Exception {
		int port = findFreePort();
		HttpServer httpServer = HttpServer.withPort(port)
				.host("127.0.0.1")
				.shutdownTimeout(Duration.ofSeconds(3))
				.build();
		SokletConfig cfg = SokletConfig.withHttpServer(httpServer)
				.lifecycleObserver(new QuietLifecycle())
				.build();
		httpServer.initialize(cfg, (request, consumer) -> {
			MarshaledResponse response = MarshaledResponse.withStatusCode(200)
					.headers(Map.of("Content-Type", Set.of("text/plain; charset=UTF-8")))
					.body("ok".getBytes(StandardCharsets.UTF_8))
					.build();
			consumer.accept(HttpRequestResult.withMarshaledResponse(response).build());
		});

		httpServer.start();

		try (Socket socket = connectWithRetry("127.0.0.1", port, 2000)) {
			socket.setSoTimeout(4000);
			OutputStream outputStream = socket.getOutputStream();
			InputStream inputStream = socket.getInputStream();
			outputStream.write(("""
					GET /health HTTP/1.1\r
					Host: localhost\r
					Connection: keep-alive\r
					\r
					""").getBytes(StandardCharsets.ISO_8859_1));
			outputStream.flush();

			String response = readUntil(inputStream, "ok", 8192);
			Assertions.assertTrue(response.startsWith("HTTP/1.1 200 OK"), response);
			Assertions.assertTrue(response.endsWith("ok"), response);

			httpServer.stop();

			Assertions.assertEquals(-1, inputStream.read(), "Idle keep-alive connection should close on stop");
		} finally {
			httpServer.stop();
		}
	}

	@Test
	public void requestHandlerTimeoutDoesNotInterruptReusedHandlerThreadAfterTaskReturns() throws Exception {
		int port = findFreePort();
		ExecutorService executorService = Executors.newSingleThreadExecutor();
		CountDownLatch handlerReturned = new CountDownLatch(1);
		CountDownLatch sentinelStarted = new CountDownLatch(1);
		CountDownLatch releaseSentinel = new CountDownLatch(1);
		AtomicBoolean sentinelInterrupted = new AtomicBoolean(false);
		HttpServer httpServer = HttpServer.withPort(port)
				.host("127.0.0.1")
				.requestHandlerTimeout(Duration.ofMillis(500))
				.requestHandlerExecutorServiceSupplier(() -> executorService)
				.build();
		SokletConfig cfg = SokletConfig.withHttpServer(httpServer)
				.lifecycleObserver(new QuietLifecycle())
				.build();
		httpServer.initialize(cfg, (request, consumer) -> handlerReturned.countDown());

		try {
			httpServer.start();

			try (Socket socket = connectWithRetry("127.0.0.1", port, 2000)) {
				socket.setSoTimeout(3000);
				OutputStream outputStream = socket.getOutputStream();
				outputStream.write(("""
						GET /timeout HTTP/1.1\r
						Host: localhost\r
						Connection: close\r
						\r
						""").getBytes(StandardCharsets.ISO_8859_1));
				outputStream.flush();

				Assertions.assertTrue(handlerReturned.await(2, TimeUnit.SECONDS), "Expected request handler to return");
				Future<?> sentinel = executorService.submit(() -> {
					sentinelStarted.countDown();
					try {
						releaseSentinel.await(2, TimeUnit.SECONDS);
					} catch (InterruptedException e) {
						sentinelInterrupted.set(true);
						Thread.currentThread().interrupt();
					}
				});

				Assertions.assertTrue(sentinelStarted.await(2, TimeUnit.SECONDS), "Expected sentinel task to start");

				String response = readUntil(socket.getInputStream(), "\r\n\r\n", 8192);
				Assertions.assertNotNull(response, "Expected timeout response");
				Assertions.assertTrue(response.startsWith("HTTP/1.1 503"), response);

				releaseSentinel.countDown();
				sentinel.get(2, TimeUnit.SECONDS);
				Assertions.assertFalse(sentinelInterrupted.get(), "Stale timeout task interrupted a reused handler thread");
			}
		} finally {
			releaseSentinel.countDown();
			httpServer.stop();
			executorService.shutdownNow();
		}
	}

	@Test
	public void rejectedExecutor_returns_503() throws Exception {
		int port = findFreePort();
		ExecutorService executor = Executors.newSingleThreadExecutor();
		executor.shutdown();

		HttpServer httpServer = HttpServer.withPort(port)
				.requestHeaderTimeout(Duration.ofSeconds(5))
				.requestHandlerExecutorServiceSupplier(() -> executor)
				.build();

		SokletConfig cfg = SokletConfig.withHttpServer(httpServer)
				.lifecycleObserver(new QuietLifecycle())
				.build();

		httpServer.initialize(cfg, (request, consumer) -> {
			MarshaledResponse response = MarshaledResponse.withStatusCode(200)
					.headers(Map.of("Content-Type", Set.of("text/plain")))
					.body("ok".getBytes(StandardCharsets.UTF_8))
					.build();
			consumer.accept(HttpRequestResult.withMarshaledResponse(response).build());
		});

		httpServer.start();

		try {
			URL url = new URL("http://127.0.0.1:" + port + "/health");
			HttpURLConnection c = open("GET", url, Map.of("Accept", "text/plain"));
			int status = c.getResponseCode();
			Assertions.assertEquals(503, status);

			java.io.InputStream in = c.getErrorStream();
			if (in == null) in = c.getInputStream();
			String body = new String(readAll(in), StandardCharsets.UTF_8);
			Assertions.assertTrue(body.contains("HTTP 503"));
		} finally {
			httpServer.stop();
		}
	}

	@Test
	public void requestHandlerDefaults_useExpectedConcurrencyAndQueueCapacity() {
		int concurrency = 3;
		HttpServer httpServer = HttpServer.withPort(0)
				.concurrency(concurrency)
				.build();

		DefaultHttpServer internal = (DefaultHttpServer) httpServer;

		boolean virtualThreadsAvailable = Boolean.TRUE.equals(Utilities.virtualThreadsAvailable());
		int expectedConcurrency = virtualThreadsAvailable ? concurrency * 16 : concurrency;
		int expectedQueueCapacity = expectedConcurrency * 64;

		Assertions.assertEquals(Integer.valueOf(expectedConcurrency), internal.getRequestHandlerConcurrency());
		Assertions.assertEquals(Integer.valueOf(expectedQueueCapacity), internal.getRequestHandlerQueueCapacity());
	}

	@Test
	public void defaultRequestHandlerExecutorUsesRuntimeThreadStrategy() throws Exception {
		int port = findFreePort();
		AtomicReference<Boolean> handlerThreadVirtual = new AtomicReference<>();

		HttpServer httpServer = HttpServer.withPort(port)
				.requestHeaderTimeout(Duration.ofSeconds(5))
				.build();

		SokletConfig cfg = SokletConfig.withHttpServer(httpServer)
				.lifecycleObserver(new QuietLifecycle())
				.build();

		httpServer.initialize(cfg, (request, consumer) -> {
			handlerThreadVirtual.set(currentThreadIsVirtual());
			MarshaledResponse response = MarshaledResponse.withStatusCode(200)
					.headers(Map.of("Content-Type", Set.of("text/plain")))
					.body("ok".getBytes(StandardCharsets.UTF_8))
					.build();
			consumer.accept(HttpRequestResult.withMarshaledResponse(response).build());
		});

		httpServer.start();

		try {
			URL url = new URL("http://127.0.0.1:" + port + "/health");
			HttpURLConnection connection = open("GET", url, Map.of("Accept", "text/plain"));
			Assertions.assertEquals(200, connection.getResponseCode());
			Assertions.assertEquals(Boolean.valueOf(Utilities.virtualThreadsAvailable()), handlerThreadVirtual.get());
		} finally {
			httpServer.stop();
		}
	}

	@Test
	public void enterKeyShutdownTriggerRequiresInteractiveConsole() throws Exception {
		Soklet.KeypressManager.reset();
		Soklet.KeypressManager.interactiveConsoleAvailableOverride(false);
		RecordingLifecycle lifecycleObserver = new RecordingLifecycle();
		AtomicReference<Throwable> awaitFailure = new AtomicReference<>();

		try (Soklet app = Soklet.fromConfig(lifecycleTestConfig(lifecycleObserver))) {
			app.start();
			Thread awaitThread = awaitShutdownOnBackgroundThread(app, awaitFailure);

			try {
				Assertions.assertTrue(lifecycleObserver.awaitConfigurationUnsupported(Duration.ofSeconds(2)),
						"Expected ENTER_KEY to be reported as unsupported without an interactive console");
				Assertions.assertTrue(app.isStarted(), "ENTER_KEY should be ignored when no interactive console exists");
			} finally {
				app.stop();
				joinAwaitThread(awaitThread);
			}
		} finally {
			Soklet.KeypressManager.reset();
		}

		Assertions.assertNull(awaitFailure.get());
	}

	@Test
	public void enterKeyShutdownTriggerTreatsStdinEofAsUnsupported() throws Exception {
		InputStream originalIn = System.in;
		Soklet.KeypressManager.reset();
		Soklet.KeypressManager.interactiveConsoleAvailableOverride(true);
		System.setIn(new ByteArrayInputStream(new byte[0]));
		RecordingLifecycle lifecycleObserver = new RecordingLifecycle();
		AtomicReference<Throwable> awaitFailure = new AtomicReference<>();

		try (Soklet app = Soklet.fromConfig(lifecycleTestConfig(lifecycleObserver))) {
			app.start();
			Thread awaitThread = awaitShutdownOnBackgroundThread(app, awaitFailure);

			try {
				Assertions.assertTrue(lifecycleObserver.awaitConfigurationUnsupported(Duration.ofSeconds(2)),
						"Expected EOF on stdin to be reported as unsupported");
				assertEventually(() -> !Soklet.KeypressManager.isListenerStarted(), Duration.ofSeconds(2),
						"Expected ENTER_KEY listener flag to reset after stdin EOF");
				Assertions.assertTrue(app.isStarted(), "stdin EOF must not stop Soklet");
			} finally {
				app.stop();
				joinAwaitThread(awaitThread);
			}
		} finally {
			System.setIn(originalIn);
			Soklet.KeypressManager.reset();
		}

		Assertions.assertNull(awaitFailure.get());
	}

	@Test
	public void enterKeyShutdownTriggerStopsOnNewlineAndResetsListener() throws Exception {
		InputStream originalIn = System.in;
		Soklet.KeypressManager.reset();
		Soklet.KeypressManager.interactiveConsoleAvailableOverride(true);
		System.setIn(new ByteArrayInputStream("\n".getBytes(StandardCharsets.UTF_8)));
		RecordingLifecycle lifecycleObserver = new RecordingLifecycle();
		AtomicReference<Throwable> awaitFailure = new AtomicReference<>();

		try (Soklet app = Soklet.fromConfig(lifecycleTestConfig(lifecycleObserver))) {
			app.start();
			Thread awaitThread = awaitShutdownOnBackgroundThread(app, awaitFailure);

			assertEventually(() -> !app.isStarted(), Duration.ofSeconds(2),
					"Expected newline on stdin to stop Soklet");
			joinAwaitThread(awaitThread);
			assertEventually(() -> !Soklet.KeypressManager.isListenerStarted(), Duration.ofSeconds(2),
					"Expected ENTER_KEY listener flag to reset after newline shutdown");
			Assertions.assertFalse(lifecycleObserver.hasConfigurationUnsupported());
		} finally {
			System.setIn(originalIn);
			Soklet.KeypressManager.reset();
		}

		Assertions.assertNull(awaitFailure.get());
	}

	@Test
	public void transportLoggerEmitsLogEventAndMetric() {
		List<LogEvent> logEvents = new ArrayList<>();
		DefaultMetricsCollector metricsCollector = DefaultMetricsCollector.defaultInstance();
		HttpServer httpServer = HttpServer.withPort(0).build();
		SokletConfig cfg = SokletConfig.withHttpServer(httpServer)
				.lifecycleObserver(new QuietLifecycle() {
					@Override
					public void didReceiveLogEvent(@NonNull LogEvent logEvent) {
						logEvents.add(logEvent);
					}
				})
				.metricsCollector(metricsCollector)
				.build();

		httpServer.initialize(cfg, (request, consumer) -> {
			throw new AssertionError("not used");
		});

		AssertionError throwable = new AssertionError("boom");
		Logger logger = ((DefaultHttpServer) httpServer).transportLogger();
		logger.logFailure(throwable,
				new LogEntry("event", "response_ready_error"),
				new LogEntry("id", "7"));
		logger.logFailure(
				new LogEntry("event", "response_write_idle_timeout"),
				new LogEntry("id", "8"));

		Assertions.assertEquals(2, logEvents.size());
		Assertions.assertTrue(logEvents.stream().allMatch(logEvent ->
				logEvent.getLogEventType() == LogEventType.SERVER_TRANSPORT_FAILURE));
		Assertions.assertTrue(logEvents.get(0).getMessage().contains("response_ready_error"));
		Assertions.assertTrue(logEvents.get(0).getMessage().contains("connectionId=7"));
		Assertions.assertSame(throwable, logEvents.get(0).getThrowable().orElse(null));

		MetricsCollector.Snapshot snapshot = metricsCollector.snapshot().orElseThrow();
		Assertions.assertEquals(1L, snapshot.getTransportFailures().get(new MetricsCollector.TransportFailureKey(
				ServerType.STANDARD_HTTP, MetricsCollector.TransportFailureReason.RESPONSE_READY_ERROR)));
		Assertions.assertEquals(1L, snapshot.getTransportFailures().get(new MetricsCollector.TransportFailureKey(
				ServerType.STANDARD_HTTP, MetricsCollector.TransportFailureReason.RESPONSE_WRITE_IDLE_TIMEOUT)));
	}

	@Test
	public void requestHandlerQueueCapacity_defaultsFromExplicitConcurrency() {
		HttpServer httpServer = HttpServer.withPort(0)
				.requestHandlerConcurrency(4)
				.build();

		DefaultHttpServer internal = (DefaultHttpServer) httpServer;
		Assertions.assertEquals(Integer.valueOf(4), internal.getRequestHandlerConcurrency());
		Assertions.assertEquals(Integer.valueOf(4 * 64), internal.getRequestHandlerQueueCapacity());
	}

	@Test
	public void requestHandlerConcurrency_requiresPositiveValue() {
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				HttpServer.withPort(0)
						.requestHandlerConcurrency(0)
						.build());
	}

	@Test
	public void requestHandlerQueueCapacity_requiresPositiveValue() {
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				HttpServer.withPort(0)
						.requestHandlerQueueCapacity(0)
						.build());
	}

	public static class HealthResource {
		@GET("/health")
		public String health() {return "ok";}
	}

	private static boolean currentThreadIsVirtual() {
		try {
			Method isVirtual = Thread.class.getMethod("isVirtual");
			return Boolean.TRUE.equals(isVirtual.invoke(Thread.currentThread()));
		} catch (NoSuchMethodException e) {
			return false;
		} catch (ReflectiveOperationException e) {
			throw new AssertionError("Unable to determine whether current thread is virtual", e);
		}
	}

	private static class QuietLifecycle implements LifecycleObserver {
		@Override
		public void didReceiveLogEvent(@NonNull LogEvent logEvent) { /* no-op */ }
	}

	private static final class RecordingLifecycle extends QuietLifecycle {
		private final List<LogEvent> logEvents;
		private final CountDownLatch configurationUnsupportedLatch;

		private RecordingLifecycle() {
			this.logEvents = new CopyOnWriteArrayList<>();
			this.configurationUnsupportedLatch = new CountDownLatch(1);
		}

		@Override
		public void didReceiveLogEvent(@NonNull LogEvent logEvent) {
			this.logEvents.add(logEvent);

			if (logEvent.getLogEventType() == LogEventType.CONFIGURATION_UNSUPPORTED)
				this.configurationUnsupportedLatch.countDown();
		}

		private boolean awaitConfigurationUnsupported(@NonNull Duration timeout) throws InterruptedException {
			return this.configurationUnsupportedLatch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
		}

		private boolean hasConfigurationUnsupported() {
			return this.logEvents.stream()
					.anyMatch(logEvent -> logEvent.getLogEventType() == LogEventType.CONFIGURATION_UNSUPPORTED);
		}
	}

	private static SokletConfig lifecycleTestConfig(@NonNull LifecycleObserver lifecycleObserver) throws IOException {
		return SokletConfig.withHttpServer(HttpServer.withPort(findFreePort())
						.requestHeaderTimeout(Duration.ofSeconds(5))
						.build())
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(HealthResource.class)))
				.lifecycleObserver(lifecycleObserver)
				.build();
	}

	private static Thread awaitShutdownOnBackgroundThread(@NonNull Soklet soklet,
																												@NonNull AtomicReference<Throwable> awaitFailure) {
		Thread awaitThread = new Thread(() -> {
			try {
				soklet.awaitShutdown(ShutdownTrigger.ENTER_KEY);
			} catch (Throwable t) {
				awaitFailure.set(t);
			}
		}, "soklet-await-shutdown-test");
		awaitThread.start();
		return awaitThread;
	}

	private static void joinAwaitThread(@NonNull Thread awaitThread) throws InterruptedException {
		awaitThread.join(2000);

		if (awaitThread.isAlive()) {
			awaitThread.interrupt();
			awaitThread.join(1000);
		}

		Assertions.assertFalse(awaitThread.isAlive(), "awaitShutdown test thread did not finish");
	}

	@NonNull
	private static String readUntil(@NonNull InputStream inputStream,
																	@NonNull String delimiter,
																	int maxBytes) throws IOException {
		byte[] delimiterBytes = delimiter.getBytes(StandardCharsets.ISO_8859_1);
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		int matched = 0;

		while (output.size() < maxBytes) {
			int value = inputStream.read();

			if (value < 0)
				break;

			output.write(value);

			if ((byte) value == delimiterBytes[matched]) {
				matched++;

				if (matched == delimiterBytes.length)
					break;
			} else {
				matched = (byte) value == delimiterBytes[0] ? 1 : 0;
			}
		}

		return output.toString(StandardCharsets.ISO_8859_1);
	}

	private static void assertEventually(@NonNull BooleanSupplier condition,
																			 @NonNull Duration timeout,
																			 @NonNull String message) throws InterruptedException {
		long deadline = System.nanoTime() + timeout.toNanos();

		while (System.nanoTime() < deadline) {
			if (condition.getAsBoolean())
				return;

			Thread.sleep(10);
		}

		Assertions.assertTrue(condition.getAsBoolean(), message);
	}
}
