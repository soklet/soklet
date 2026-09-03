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
import org.junit.jupiter.api.Timeout;

import javax.annotation.concurrent.ThreadSafe;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.channels.Selector;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static com.soklet.TestSupport.connectWithRetry;
import static com.soklet.TestSupport.findFreePort;
import static com.soklet.TestSupport.readAll;
import static java.util.Objects.requireNonNull;

/** Owner-lifecycle coverage for the built-in HTTP transport. */
@ThreadSafe
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class HttpServerLifecycleTests {
	@Test
	void ownerLifecycleAttachesServesAndPublishesOneGracefulResult()
			throws Exception {
		int port = findFreePort();
		HttpServer server = HttpServer.withPort(port)
				.host("127.0.0.1")
				.requestHeaderTimeout(Duration.ofSeconds(5))
				.build();
		TransportIdentity identity = server.getTransportIdentity();
		SokletConfig config = configuration(server, HealthResource.class);

		Assertions.assertSame(server, config.getHttpServer().orElseThrow(),
				"The configuration must retain the exact outer transport");
		Assertions.assertSame(identity, server.getTransportIdentity(),
				"A transport identity must be stable before ownership");
		Soklet soklet = Soklet.fromConfig(config);
		Assertions.assertEquals(SokletStatus.NEW, soklet.getStatus());
		Assertions.assertTrue(soklet.getShutdownResult().isEmpty());

		soklet.start();
		Assertions.assertEquals(SokletStatus.RUNNING, soklet.getStatus());
		Assertions.assertSame(identity, server.getTransportIdentity());
		HttpURLConnection connection = open("GET",
				new URL("http://127.0.0.1:" + port + "/health"),
				Map.of("Accept", "text/plain"));
		Assertions.assertEquals(200, connection.getResponseCode());
		Assertions.assertEquals("ok", new String(
				readAll(connection.getInputStream()), StandardCharsets.UTF_8));

		CompletionStage<ShutdownResult> stage = soklet.shutdown();
		ShutdownResult result = soklet.awaitShutdown();
		Assertions.assertSame(result, stage.toCompletableFuture().join());
		Assertions.assertSame(result, soklet.getShutdownResult().orElseThrow());
		Assertions.assertEquals(SokletStatus.CLOSED, soklet.getStatus());
		assertGracefulHttpResult(result);
		soklet.close();
	}

	@Test
	void ownerStartupFailurePublishesExactResultAndReleasesBuiltInState()
			throws Exception {
		int port = findFreePort();
		try (ServerSocket socket = new ServerSocket(port)) {
			socket.setReuseAddress(true);
			HttpServer server = HttpServer.withPort(port).build();
			DefaultHttpServer builtIn = (DefaultHttpServer) server;
			Soklet soklet = Soklet.fromConfig(configuration(server,
					HealthResource.class));

			SokletStartupException failure = Assertions.assertThrows(
					SokletStartupException.class, soklet::start);
			Assertions.assertEquals(StartupDisposition.FAILED,
					failure.getStartupDisposition());
			Assertions.assertInstanceOf(UncheckedIOException.class,
					failure.getCause());
			Assertions.assertSame(failure.getShutdownResult(),
					soklet.getShutdownResult().orElseThrow());
			Assertions.assertEquals(SokletStatus.CLOSED, soklet.getStatus());
			Assertions.assertTrue(builtIn.getEventLoop().isEmpty());
			Assertions.assertTrue(
					builtIn.getRequestHandlerExecutorService().isEmpty());
		}
	}

	@Test
	void unexpectedBuiltInTerminationClosesTheOwnerWithPublicEvidence()
			throws Exception {
		HttpServer server = HttpServer.withPort(findFreePort())
				.host("127.0.0.1").build();
		Soklet soklet = Soklet.fromConfig(configuration(server,
				HealthResource.class));
		soklet.start();
		EventLoop eventLoop = ((DefaultHttpServer) server).getEventLoop()
				.orElseThrow();

		Field selectorField = EventLoop.class.getDeclaredField("selector");
		selectorField.setAccessible(true);
		((Selector) selectorField.get(eventLoop)).close();
		ShutdownResult result = soklet.awaitShutdown();
		Assertions.assertEquals(SokletStatus.CLOSED, soklet.getStatus());
		Assertions.assertEquals(ShutdownComponentType.HTTP,
				result.getUnexpectedShutdownComponentTermination().orElseThrow()
						.getShutdownComponentType());
		Assertions.assertEquals(
				ShutdownComponentDisposition.UNEXPECTED_TERMINATION,
				result.getShutdownComponentResult(ShutdownComponentType.HTTP).orElseThrow()
						.getShutdownComponentDisposition());
		SokletUnexpectedTerminationException replay = Assertions.assertThrows(
				SokletUnexpectedTerminationException.class, soklet::close);
		Assertions.assertSame(result, replay.getShutdownResult());
	}

	@Test
	void ownerShutdownDrainsInFlightResponseBeforeClosingConnection()
			throws Exception {
		int port = findFreePort();
		SlowInvocation invocation = new SlowInvocation();
		SlowResource.INVOCATION.set(invocation);
		HttpServer server = HttpServer.withPort(port).host("127.0.0.1").build();
		Soklet soklet = Soklet.fromConfig(configuration(server,
				SlowResource.class));
		soklet.start();

		try (Socket socket = connectWithRetry("127.0.0.1", port, 2000)) {
			socket.setSoTimeout(4000);
			OutputStream output = socket.getOutputStream();
			output.write(("""
					GET /slow HTTP/1.1\r
					Host: localhost\r
					Connection: keep-alive\r
					\r
					""").getBytes(StandardCharsets.ISO_8859_1));
			output.flush();
			Assertions.assertTrue(invocation.started.await(2, TimeUnit.SECONDS));

			CompletionStage<ShutdownResult> shutdown = soklet.shutdown();
			EventLoop eventLoop = ((DefaultHttpServer) server).getEventLoop()
					.orElseThrow();
			assertEventually(() -> !eventLoop.isAccepting(),
					Duration.ofSeconds(2),
					"HTTP admission must close before the response is released");
			invocation.release.countDown();

			String response = new String(readAll(socket.getInputStream()),
					StandardCharsets.ISO_8859_1);
			Assertions.assertTrue(response.startsWith("HTTP/1.1 200 OK"),
					response);
			Assertions.assertTrue(response.contains("Connection: close\r\n"),
					response);
			Assertions.assertTrue(response.endsWith("\r\n\r\ndone"), response);
			assertGracefulHttpResult(shutdown.toCompletableFuture()
					.get(3, TimeUnit.SECONDS));
		} finally {
			invocation.release.countDown();
			SlowResource.INVOCATION.compareAndSet(invocation, null);
			soklet.close();
		}
	}

	@Test
	void ownerShutdownClosesIdleKeepAliveConnection() throws Exception {
		int port = findFreePort();
		HttpServer server = HttpServer.withPort(port).host("127.0.0.1").build();
		Soklet soklet = Soklet.fromConfig(configuration(server,
				HealthResource.class));
		soklet.start();

		try (Socket socket = connectWithRetry("127.0.0.1", port, 2000)) {
			socket.setSoTimeout(4000);
			OutputStream output = socket.getOutputStream();
			InputStream input = socket.getInputStream();
			output.write(("""
					GET /health HTTP/1.1\r
					Host: localhost\r
					Connection: keep-alive\r
					\r
					""").getBytes(StandardCharsets.ISO_8859_1));
			output.flush();
			Assertions.assertTrue(readUntil(input, "ok", 8192).endsWith("ok"));

			ShutdownResult result = soklet.shutdown().toCompletableFuture()
					.get(3, TimeUnit.SECONDS);
			Assertions.assertEquals(-1, input.read());
			assertGracefulHttpResult(result);
		} finally {
			soklet.close();
		}
	}

	@Test
	void rejectedRequestExecutorReturns503UnderOwnerLifecycle()
			throws Exception {
		int port = findFreePort();
		ExecutorService executor = Executors.newSingleThreadExecutor();
		executor.shutdown();
		HttpServer server = HttpServer.withPort(port)
				.requestHeaderTimeout(Duration.ofSeconds(5))
				.requestHandlerExecutorServiceSupplier(() -> executor)
				.build();
		try (Soklet soklet = Soklet.fromConfig(configuration(server,
				HealthResource.class))) {
			soklet.start();
			HttpURLConnection connection = open("GET",
					new URL("http://127.0.0.1:" + port + "/health"), Map.of());
			Assertions.assertEquals(503, connection.getResponseCode());
			InputStream input = connection.getErrorStream();
			if (input == null)
				input = connection.getInputStream();
			Assertions.assertTrue(new String(readAll(input),
					StandardCharsets.UTF_8).contains("HTTP 503"));
		}
	}

	@Test
	void defaultRequestHandlerExecutorUsesRuntimeThreadStrategy()
			throws Exception {
		int port = findFreePort();
		AtomicReference<Boolean> handlerThreadVirtual = new AtomicReference<>();
		ThreadRecordingResource.HANDLER_THREAD_VIRTUAL.set(handlerThreadVirtual);
		HttpServer server = HttpServer.withPort(port)
				.requestHeaderTimeout(Duration.ofSeconds(5)).build();
		try (Soklet soklet = Soklet.fromConfig(configuration(server,
				ThreadRecordingResource.class))) {
			soklet.start();
			HttpURLConnection connection = open("GET",
					new URL("http://127.0.0.1:" + port + "/thread"), Map.of());
			Assertions.assertEquals(200, connection.getResponseCode());
			Assertions.assertEquals(Boolean.valueOf(
					Utilities.virtualThreadsAvailable()),
					handlerThreadVirtual.get());
		} finally {
			ThreadRecordingResource.HANDLER_THREAD_VIRTUAL.set(null);
		}
	}

	@Test
	void transportLoggerEmitsLogEventAndMetricAfterOwnerAttachment() {
		List<LogEvent> logEvents = new ArrayList<>();
		DefaultMetricsCollector metrics = DefaultMetricsCollector.defaultInstance();
		HttpServer server = HttpServer.withPort(0).build();
		SokletConfig config = SokletConfig.withHttpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(
						Set.of(HealthResource.class)))
				.lifecycleObserver(new QuietLifecycle() {
					@Override
					public void didReceiveLogEvent(@NonNull LogEvent event) {
						logEvents.add(event);
					}
				})
				.metricsCollector(metrics).build();

		try (Soklet soklet = Soklet.fromConfig(config)) {
			soklet.start();
			AssertionError throwable = new AssertionError("boom");
			Logger logger = ((DefaultHttpServer) server).transportLogger();
			logger.logFailure(throwable,
					new LogEntry("event", "response_ready_error"),
					new LogEntry("id", "7"));
			logger.logFailure(
					new LogEntry("event", "response_write_idle_timeout"),
					new LogEntry("id", "8"));

			Assertions.assertEquals(2, logEvents.size());
			Assertions.assertTrue(logEvents.stream().allMatch(event ->
					event.getLogEventType()
							== LogEventType.SERVER_TRANSPORT_FAILURE));
			Assertions.assertSame(throwable,
					logEvents.get(0).getThrowable().orElseThrow());
			MetricsCollector.Snapshot snapshot = metrics.snapshot().orElseThrow();
			Assertions.assertEquals(1L,
					snapshot.getTransportFailures().get(
							new MetricsCollector.TransportFailureKey(
									ServerType.STANDARD_HTTP,
									MetricsCollector.TransportFailureReason
											.RESPONSE_READY_ERROR)));
			Assertions.assertEquals(1L,
					snapshot.getTransportFailures().get(
							new MetricsCollector.TransportFailureKey(
									ServerType.STANDARD_HTTP,
									MetricsCollector.TransportFailureReason
											.RESPONSE_WRITE_IDLE_TIMEOUT)));
		}
	}

	@Test
	void requestHandlerDefaultsAndValidationRemainTransportLocal() {
		HttpServer concurrency = HttpServer.withPort(0).concurrency(3).build();
		DefaultHttpServer defaults = (DefaultHttpServer) concurrency;
		int expectedConcurrency = Boolean.TRUE.equals(
				Utilities.virtualThreadsAvailable()) ? 48 : 3;
		Assertions.assertEquals(expectedConcurrency,
				defaults.getRequestHandlerConcurrency());
		Assertions.assertEquals(expectedConcurrency * 64,
				defaults.getRequestHandlerQueueCapacity());

		HttpServer explicit = HttpServer.withPort(0)
				.requestHandlerConcurrency(4).build();
		Assertions.assertEquals(4,
				((DefaultHttpServer) explicit).getRequestHandlerConcurrency());
		Assertions.assertEquals(4 * 64,
				((DefaultHttpServer) explicit).getRequestHandlerQueueCapacity());
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				HttpServer.withPort(0).requestHandlerConcurrency(0).build());
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				HttpServer.withPort(0).requestHandlerQueueCapacity(0).build());
	}

	@NonNull
	private static SokletConfig configuration(@NonNull HttpServer server,
			@NonNull Class<?> resourceClass) {
		return SokletConfig.withHttpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(
						Set.of(resourceClass)))
				.lifecyclePolicy(LifecyclePolicy.builder()
						.gracefulShutdownTimeout(Duration.ofSeconds(3))
						.forcedShutdownTimeout(Duration.ofSeconds(1)).build())
				.lifecycleObserver(new QuietLifecycle()).build();
	}

	private static void assertGracefulHttpResult(@NonNull ShutdownResult result) {
		Assertions.assertEquals(StartupDisposition.READY,
				result.getStartupDisposition());
		Assertions.assertEquals(ShutdownDisposition.GRACEFUL,
				result.getShutdownDisposition());
		Assertions.assertTrue(result.isComplete());
		ShutdownComponentResult http = result
				.getShutdownComponentResult(ShutdownComponentType.HTTP).orElseThrow();
		Assertions.assertEquals(
				ShutdownComponentDisposition.GRACEFUL_TERMINATION,
				http.getShutdownComponentDisposition());
		Assertions.assertTrue(http.getThrowables().isEmpty());
		Assertions.assertTrue(http.getResidualActivityEvidence().isEmpty());
	}

	private static HttpURLConnection open(@NonNull String method,
			@NonNull URL url, @NonNull Map<String, String> headers)
			throws IOException {
		HttpURLConnection connection = (HttpURLConnection) url.openConnection();
		connection.setRequestMethod(method);
		connection.setConnectTimeout(2000);
		connection.setReadTimeout(4000);
		for (Map.Entry<String, String> header : headers.entrySet())
			connection.setRequestProperty(header.getKey(), header.getValue());
		return connection;
	}

	@NonNull
	private static String readUntil(@NonNull InputStream input,
			@NonNull String delimiter, int maxBytes) throws IOException {
		byte[] delimiterBytes = delimiter.getBytes(StandardCharsets.ISO_8859_1);
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		int matched = 0;
		while (output.size() < maxBytes) {
			int value = input.read();
			if (value < 0)
				break;
			output.write(value);
			if ((byte) value == delimiterBytes[matched]) {
				if (++matched == delimiterBytes.length)
					break;
			} else {
				matched = (byte) value == delimiterBytes[0] ? 1 : 0;
			}
		}
		return output.toString(StandardCharsets.ISO_8859_1);
	}

	private static void assertEventually(@NonNull BooleanSupplier condition,
			@NonNull Duration timeout, @NonNull String message)
			throws InterruptedException {
		long deadline = System.nanoTime() + timeout.toNanos();
		while (System.nanoTime() < deadline) {
			if (condition.getAsBoolean())
				return;
			Thread.sleep(10);
		}
		Assertions.assertTrue(condition.getAsBoolean(), message);
	}

	public static class HealthResource {
		@GET("/health")
		@NonNull
		public String health() {
			return "ok";
		}
	}

	public static class SlowResource {
		private static final AtomicReference<SlowInvocation> INVOCATION =
				new AtomicReference<>();

		@GET("/slow")
		@NonNull
		public String slow() {
			SlowInvocation invocation = requireNonNull(INVOCATION.get());
			invocation.started.countDown();
			boolean interrupted = false;
			try {
				for (;;) {
					try {
						invocation.release.await();
						return "done";
					} catch (InterruptedException ignored) {
						interrupted = true;
					}
				}
			} finally {
				if (interrupted)
					Thread.currentThread().interrupt();
			}
		}
	}

	public static class ThreadRecordingResource {
		private static final AtomicReference<AtomicReference<Boolean>>
				HANDLER_THREAD_VIRTUAL = new AtomicReference<>();

		@GET("/thread")
		@NonNull
		public String thread() {
			requireNonNull(HANDLER_THREAD_VIRTUAL.get())
					.set(currentThreadIsVirtual());
			return "ok";
		}
	}

	private static final class SlowInvocation {
		@NonNull private final CountDownLatch started = new CountDownLatch(1);
		@NonNull private final CountDownLatch release = new CountDownLatch(1);
	}

	private static boolean currentThreadIsVirtual() {
		try {
			Method isVirtual = Thread.class.getMethod("isVirtual");
			return Boolean.TRUE.equals(isVirtual.invoke(Thread.currentThread()));
		} catch (NoSuchMethodException ignored) {
			return false;
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError(
					"Unable to determine whether current thread is virtual",
					exception);
		}
	}

	private static class QuietLifecycle implements LifecycleObserver {
		@Override
		public void didReceiveLogEvent(@NonNull LogEvent logEvent) {
			// No-op.
		}
	}
}
