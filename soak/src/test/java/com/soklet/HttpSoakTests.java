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
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.annotation.concurrent.ThreadSafe;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Longer-running HTTP resource-leak soak tests.
 * <p>
 * Default mode is a short smoke run. Set {@code SOKLET_SOAK=1} for the higher-volume nightly run.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class HttpSoakTests {
	private static final byte[] LARGE_BODY = "x".repeat(256 * 1024).getBytes(StandardCharsets.UTF_8);
	private static final SoakProfile PROFILE = SoakProfile.fromEnvironment();

	@Test
	public void concurrentHttpChurnReturnsResourcesAndActiveRequestsToBaseline() throws Exception {
		long startedAt = System.nanoTime();
		MetricsCollector metricsCollector = MetricsCollector.defaultInstance();
		int port = findFreePort();
		HttpServer httpServer = httpServer(port, PROFILE.serverConcurrency());
		SokletConfig config = sokletConfig(httpServer, metricsCollector);
		SoakResourceSnapshot baseline;

		try (Soklet soklet = Soklet.fromConfig(config)) {
			soklet.start();
			assertOkResponse(port);
			assertActiveRequestsReturnToZero(metricsCollector, Duration.ofSeconds(3));
			baseline = SoakResourceSnapshot.captureAfterGc();

			RunResult result = runConcurrent(PROFILE.concurrentClients(), PROFILE.cleanRequestsPerClient(), (clientIndex, iteration) -> {
				assertOkResponse(port);
			});

			Assertions.assertTrue(result.failures().isEmpty(), () -> "Unexpected clean churn failures: " + result.failures());
			Assertions.assertEquals(PROFILE.concurrentClients() * PROFILE.cleanRequestsPerClient(), result.completed());
			assertActiveRequestsReturnToZero(metricsCollector, PROFILE.settleTimeout());
			SoakResourceSnapshot finalSnapshot = SoakResourceSnapshot.assertReturnsNear(
					"concurrent HTTP churn",
					baseline,
					PROFILE.settleTimeout(),
					PROFILE.resourceTolerance());
			SoakReport.recordPassedScenario(
					"concurrent HTTP churn",
					"clients=%d, requestsPerClient=%d, serverConcurrency=%d, socketPendingConnectionLimit=%d"
							.formatted(PROFILE.concurrentClients(), PROFILE.cleanRequestsPerClient(), PROFILE.serverConcurrency(),
									PROFILE.socketPendingConnectionLimit()),
					Duration.ofNanos(System.nanoTime() - startedAt),
					baseline,
					finalSnapshot,
					PROFILE.resourceTolerance(),
					SoakReport.observations(
							"Completed operations", Integer.toString(result.completed()),
							"Active requests", activeRequests(metricsCollector).toString(),
							"Settle timeout", PROFILE.settleTimeout().toString()));
		}

		assertHttpServerStopped(httpServer);
	}

	@Test
	public void httpAbortChurnReturnsResourcesAndActiveRequestsToBaseline() throws Exception {
		long startedAt = System.nanoTime();
		MetricsCollector metricsCollector = MetricsCollector.defaultInstance();
		int port = findFreePort();
		HttpServer httpServer = httpServer(port, PROFILE.serverConcurrency());
		SokletConfig config = sokletConfig(httpServer, metricsCollector);
		SoakResourceSnapshot baseline;

		try (Soklet soklet = Soklet.fromConfig(config)) {
			soklet.start();
			assertOkResponse(port);
			assertActiveRequestsReturnToZero(metricsCollector, Duration.ofSeconds(3));
			baseline = SoakResourceSnapshot.captureAfterGc();

			RunResult result = runConcurrent(PROFILE.concurrentClients(), PROFILE.abortIterationsPerClient(), (clientIndex, iteration) -> {
				int mode = Math.floorMod(clientIndex + iteration, 3);

				if (mode == 0) {
					connectAndClose(port);
				} else if (mode == 1) {
					writePartialHeadersAndClose(port);
				} else {
					closeBeforeReadingLargeResponse(port);
				}
			});

			Assertions.assertTrue(result.failures().isEmpty(), () -> "Unexpected abort churn failures: " + result.failures());
			Assertions.assertEquals(PROFILE.concurrentClients() * PROFILE.abortIterationsPerClient(), result.completed());
			assertActiveRequestsReturnToZero(metricsCollector, PROFILE.settleTimeout());
			SoakResourceSnapshot finalSnapshot = SoakResourceSnapshot.assertReturnsNear(
					"HTTP abort churn",
					baseline,
					PROFILE.settleTimeout(),
					PROFILE.resourceTolerance());
			SoakReport.recordPassedScenario(
					"HTTP abort churn",
					"clients=%d, abortIterationsPerClient=%d, abortModes=connect-close/partial-headers/close-before-read, serverConcurrency=%d, socketPendingConnectionLimit=%d, abortConnectTimeoutMillis=%d"
							.formatted(PROFILE.concurrentClients(), PROFILE.abortIterationsPerClient(), PROFILE.serverConcurrency(),
									PROFILE.socketPendingConnectionLimit(), PROFILE.abortConnectTimeoutMillis()),
					Duration.ofNanos(System.nanoTime() - startedAt),
					baseline,
					finalSnapshot,
					PROFILE.resourceTolerance(),
					SoakReport.observations(
							"Completed operations", Integer.toString(result.completed()),
							"Active requests", activeRequests(metricsCollector).toString(),
							"Settle timeout", PROFILE.settleTimeout().toString()));
		}

		assertHttpServerStopped(httpServer);
	}

	private static HttpServer httpServer(int port, int concurrency) {
		return HttpServer.withPort(port)
				.concurrency(concurrency)
				.socketPendingConnectionLimit(PROFILE.socketPendingConnectionLimit())
				.requestHeaderTimeout(Duration.ofSeconds(2))
				.responseWriteIdleTimeout(Duration.ofSeconds(2))
				.shutdownTimeout(Duration.ofSeconds(3))
				.build();
	}

	@NonNull
	private static SokletConfig sokletConfig(@NonNull HttpServer httpServer,
																					 @NonNull MetricsCollector metricsCollector) {
		return SokletConfig.withHttpServer(httpServer)
				.metricsCollector(metricsCollector)
				.lifecycleObserver(new QuietLifecycle())
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(SoakResource.class)))
				.build();
	}

	private static RunResult runConcurrent(int clients,
																				 int iterationsPerClient,
																				 @NonNull SoakOperation operation) throws Exception {
		ExecutorService executorService = Executors.newFixedThreadPool(clients);
		CountDownLatch ready = new CountDownLatch(clients);
		CountDownLatch start = new CountDownLatch(1);
		Queue<String> failures = new ConcurrentLinkedQueue<>();
		AtomicInteger completed = new AtomicInteger();

		try {
			for (int client = 0; client < clients; client++) {
				int clientIndex = client;

				executorService.submit(() -> {
					ready.countDown();

					try {
						if (!start.await(10, TimeUnit.SECONDS))
							throw new AssertionError("Timed out waiting for start signal");

						for (int iteration = 0; iteration < iterationsPerClient; iteration++) {
							try {
								operation.run(clientIndex, iteration);
								completed.incrementAndGet();
							} catch (Throwable throwable) {
								failures.add("client=%d iteration=%d %s: %s".formatted(
										clientIndex,
										iteration,
										throwable.getClass().getSimpleName(),
										throwable.getMessage()));
							}
						}
					} catch (Throwable throwable) {
						failures.add("client=%d setup %s: %s".formatted(
								clientIndex,
								throwable.getClass().getSimpleName(),
								throwable.getMessage()));
					}
				});
			}

			Assertions.assertTrue(ready.await(10, TimeUnit.SECONDS), "Clients did not become ready");
			start.countDown();
			executorService.shutdown();
			Assertions.assertTrue(executorService.awaitTermination(PROFILE.runTimeout().toSeconds(), TimeUnit.SECONDS),
					"Timed out waiting for soak clients to finish");
		} finally {
			executorService.shutdownNow();
		}

		return new RunResult(completed.get(), failures);
	}

	private static void assertOkResponse(int port) throws Exception {
		try (Socket socket = connectWithRetry("127.0.0.1", port, 2_000)) {
			socket.setSoTimeout(2_000);
			writeAscii(socket, """
					GET /health HTTP/1.1\r
					Host: 127.0.0.1:%s\r
					Connection: close\r
					\r
					""".formatted(port));

			String response = readAscii(socket.getInputStream());
			Assertions.assertTrue(response.startsWith("HTTP/1.1 200"), "Unexpected response: " + firstLine(response));
			Assertions.assertTrue(response.endsWith("ok"), "Unexpected response body: " + response);
		}
	}

	private static void connectAndClose(int port) throws Exception {
		try (Socket socket = connectWithRetry("127.0.0.1", port, PROFILE.abortConnectTimeoutMillis())) {
			socket.setSoLinger(true, 0);
			// Open and close without sending bytes.
		}
	}

	private static void writePartialHeadersAndClose(int port) throws Exception {
		try (Socket socket = connectWithRetry("127.0.0.1", port, PROFILE.abortConnectTimeoutMillis())) {
			socket.setSoLinger(true, 0);
			writeAscii(socket, "GET /health HTTP/1.1\r\nHost: 127.0.0.1:" + port);
		}
	}

	private static void closeBeforeReadingLargeResponse(int port) throws Exception {
		try (Socket socket = connectWithRetry("127.0.0.1", port, PROFILE.abortConnectTimeoutMillis())) {
			socket.setSoLinger(true, 0);
			writeAscii(socket, """
					GET /large HTTP/1.1\r
					Host: 127.0.0.1:%s\r
					Connection: close\r
					\r
					""".formatted(port));
		}
	}

	private static void assertActiveRequestsReturnToZero(@NonNull MetricsCollector metricsCollector,
																											 @NonNull Duration timeout) throws InterruptedException {
		long deadline = System.nanoTime() + timeout.toNanos();
		Long activeRequests = activeRequests(metricsCollector);

		while (System.nanoTime() < deadline) {
			if (activeRequests.longValue() == 0L)
				return;

			Thread.sleep(100L);
			activeRequests = activeRequests(metricsCollector);
		}

		Assertions.fail("Active requests did not return to zero within %s; last=%s"
				.formatted(timeout, activeRequests));
	}

	@NonNull
	private static Long activeRequests(@NonNull MetricsCollector metricsCollector) {
		return metricsCollector.snapshot()
				.map(MetricsCollector.Snapshot::getActiveRequests)
				.orElse(0L);
	}

	private static void assertHttpServerStopped(@NonNull HttpServer httpServer) {
		DefaultHttpServer defaultHttpServer = (DefaultHttpServer) httpServer;

		Assertions.assertTrue(defaultHttpServer.getEventLoop().isEmpty(), "Event loop should be cleared after stop");
		Assertions.assertTrue(defaultHttpServer.getRequestHandlerExecutorService().isEmpty(),
				"Request handler executor should be cleared after stop");
		Assertions.assertTrue(defaultHttpServer.getRequestHandlerTimeoutScheduler().isEmpty(),
				"Timeout scheduler should be cleared after stop");
	}

	private static int findFreePort() throws IOException {
		try (ServerSocket serverSocket = new ServerSocket(0)) {
			serverSocket.setReuseAddress(true);
			return serverSocket.getLocalPort();
		}
	}

	@NonNull
	private static Socket connectWithRetry(@NonNull String host,
																				 int port,
																				 int timeoutMillis) throws IOException, InterruptedException {
		long deadline = System.currentTimeMillis() + timeoutMillis;
		IOException last = null;

		while (System.currentTimeMillis() < deadline) {
			try {
				Socket socket = new Socket();
				socket.connect(new InetSocketAddress(host, port), Math.max(250, timeoutMillis / 2));
				return socket;
			} catch (IOException e) {
				last = e;
				Thread.sleep(30L);
			}
		}

		throw last != null ? last : new IOException("Unable to connect to " + host + ":" + port);
	}

	private static void writeAscii(@NonNull Socket socket,
																 @NonNull String value) throws IOException {
		socket.getOutputStream().write(value.getBytes(StandardCharsets.ISO_8859_1));
		socket.getOutputStream().flush();
	}

	@NonNull
	private static String readAscii(@NonNull InputStream inputStream) throws IOException {
		try (InputStream stream = inputStream) {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			byte[] buffer = new byte[8192];
			int read;

			while ((read = stream.read(buffer)) != -1)
				bytes.write(buffer, 0, read);

			return bytes.toString(StandardCharsets.ISO_8859_1);
		}
	}

	@NonNull
	private static String firstLine(@NonNull String response) {
		int endOfLine = response.indexOf("\r\n");
		return endOfLine < 0 ? response : response.substring(0, endOfLine);
	}

	@ThreadSafe
	public static class SoakResource {
		@GET("/health")
		public String health() {
			return "ok";
		}

		@GET("/large")
		public Response large() {
			return Response.withStatusCode(200)
					.headers(java.util.Map.of("Content-Type", Set.of("application/octet-stream")))
					.body(LARGE_BODY)
					.build();
		}
	}

	private static class QuietLifecycle implements LifecycleObserver {
		@Override
		public void didReceiveLogEvent(@NonNull LogEvent logEvent) {
			// Keep soak-test output focused on assertion failures.
		}
	}

	private record RunResult(int completed, @NonNull Queue<String> failures) {}

	@FunctionalInterface
	private interface SoakOperation {
		void run(int clientIndex, int iteration) throws Exception;
	}

	private record SoakProfile(int concurrentClients,
														 int cleanRequestsPerClient,
														 int abortIterationsPerClient,
														 int serverConcurrency,
														 int socketPendingConnectionLimit,
														 int abortConnectTimeoutMillis,
														 @NonNull Duration runTimeout,
														 @NonNull Duration settleTimeout,
														 SoakResourceSnapshot.ResourceTolerance resourceTolerance) {
		@NonNull
		private static SoakProfile fromEnvironment() {
			boolean soak = "1".equals(System.getenv("SOKLET_SOAK"));

			if (soak) {
				return new SoakProfile(
						32,
						250,
						150,
						8,
						1_024,
						10_000,
						Duration.ofSeconds(90),
						Duration.ofSeconds(15),
						new SoakResourceSnapshot.ResourceTolerance(8L, 64L * 1024L * 1024L, 32));
			}

			return new SoakProfile(
					4,
					20,
					12,
					2,
					128,
					5_000,
					Duration.ofSeconds(20),
					Duration.ofSeconds(5),
					new SoakResourceSnapshot.ResourceTolerance(4L, 32L * 1024L * 1024L, 12));
		}
	}
}
