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
import com.soklet.annotation.POST;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * End-to-end loopback benchmark for Soklet's default HTTP server.
 * <p>
 * Run after {@code mvn -q clean package} in {@code benchmarks/}:
 * <pre>{@code
 * java -cp target/soklet-benchmarks.jar com.soklet.EndToEndHttpBenchmark
 * }</pre>
 * Select the streaming workloads with
 * {@code -Dsoklet.e2e.scenarios=streaming,streaming-bulk,streaming-paced}.
 * The tiny response has one 13-byte write; bulk has 64 4-KiB writes (256 KiB);
 * paced has 32 1-KiB writes (32 KiB), each preceded by a requested 1-ms sleep.
 * Sleep duration depends on the scheduler; measured latency includes that delay.
 * Writes describe producer calls, not HTTP chunk boundaries. Every successful
 * response must have status 200 and the complete, exact expected body. Validation
 * work is part of the client measurement and is identical for baseline/candidate.
 * The three {@code output-native,output-scalar,output-mixed} workloads
 * produce the same 64-KiB UTF-8 body through the current streaming API. This
 * compiled harness requires that API and cannot run against the former
 * two-argument writer ABI. Use the baseline's compatible harness for historical
 * comparisons instead of swapping only the runtime classpath.
 * Whole-JVM allocation and GC deltas include the colocated clients, server,
 * observer tails, and measurement bookkeeping; they are not server-only costs.
 * Error diagnostics use eight fixed categories and retain one example of at most
 * 200 characters per category, including the client failure phase. The optional
 * {@code soklet.e2e.socketPendingConnectionLimit} property defaults to zero,
 * preserving the server's OS-selected accept backlog unless explicitly changed.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public final class EndToEndHttpBenchmark {
	private static final String HOST = "127.0.0.1";
	private static final int ERROR_EXAMPLE_LIMIT = 200;
	private static final ErrorCategory[] ERROR_CATEGORIES = ErrorCategory.values();
	private static final byte[] POST_BODY = ascii("{\"name\":\"widget\",\"quantity\":123456}");
	private static final byte[] STREAMING_BODY = ascii("hello, soklet\n");
	private static final byte[] JSON_BODY = ascii("{\"message\":\"hello\",\"status\":\"ok\"}\n");
	private static final int BULK_WRITE_COUNT = 64;
	private static final int BULK_WRITE_SIZE = 4 * 1024;
	private static final byte[] BULK_BODY = patternedBody(BULK_WRITE_COUNT * BULK_WRITE_SIZE);
	private static final byte[][] BULK_WRITES = splitBody(BULK_BODY, BULK_WRITE_SIZE);
	private static final int PACED_WRITE_COUNT = 32;
	private static final int PACED_WRITE_SIZE = 1024;
	private static final int PACED_DELAY_MILLIS = 1;
	private static final byte[] PACED_BODY = patternedBody(PACED_WRITE_COUNT * PACED_WRITE_SIZE);
	private static final byte[][] PACED_WRITES = splitBody(PACED_BODY, PACED_WRITE_SIZE);
	private static final String OUTPUT_TEXT = "abcdefé界🙂\n".repeat(4096);
	private static final byte[] OUTPUT_BODY = OUTPUT_TEXT.getBytes(StandardCharsets.UTF_8);
	private static final int OUTPUT_WRITE_SIZE = 8192;
	private static final byte[][] OUTPUT_WRITES = splitBody(OUTPUT_BODY, OUTPUT_WRITE_SIZE);
	private static final ByteBuffer OUTPUT_DIRECT = ByteBuffer.allocateDirect(OUTPUT_BODY.length).put(OUTPUT_BODY).flip();
	private static final Map<OutputMode, MarshaledResponse> OUTPUT_RESPONSES = Map.of(
			OutputMode.NATIVE, outputResponse(OutputMode.NATIVE),
			OutputMode.SCALAR, outputResponse(OutputMode.SCALAR),
			OutputMode.MIXED, outputResponse(OutputMode.MIXED));
	private static final MarshaledResponse PLAINTEXT_RESPONSE = MarshaledResponse.withStatusCode(200)
			.headers(Map.of("Content-Type", Set.of("text/plain; charset=UTF-8")))
			.body(ascii("hello, soklet\n"))
			.build();
	private static final MarshaledResponse JSON_RESPONSE = MarshaledResponse.withStatusCode(200)
			.headers(Map.of("Content-Type", Set.of("application/json; charset=UTF-8")))
			.body(JSON_BODY)
			.build();
	private static final MarshaledResponse STREAMING_RESPONSE = MarshaledResponse.withStatusCode(200)
			.headers(Map.of("Content-Type", Set.of("text/plain; charset=UTF-8")))
			.streamingResponseBody(StreamingResponseBody.fromWriter(responseStream ->
					responseStream.write(STREAMING_BODY)))
			.build();
	private static final MarshaledResponse BULK_STREAMING_RESPONSE = MarshaledResponse.withStatusCode(200)
			.headers(Map.of("Content-Type", Set.of("application/octet-stream")))
			.streamingResponseBody(StreamingResponseBody.fromWriter(responseStream -> {
				for (byte[] write : BULK_WRITES)
					responseStream.write(write);
			}))
			.build();
	private static final MarshaledResponse PACED_STREAMING_RESPONSE = MarshaledResponse.withStatusCode(200)
			.headers(Map.of("Content-Type", Set.of("application/octet-stream")))
			.streamingResponseBody(StreamingResponseBody.fromWriter(responseStream -> {
				for (byte[] write : PACED_WRITES) {
					Thread.sleep(PACED_DELAY_MILLIS);
					responseStream.write(write);
				}
			}))
			.build();
	private static final MarshaledResponse BAD_REQUEST_RESPONSE = MarshaledResponse.withStatusCode(400)
			.headers(Map.of("Content-Type", Set.of("text/plain; charset=UTF-8")))
			.body(ascii("bad request\n"))
			.build();

	private EndToEndHttpBenchmark() {
	}

	public static void main(String[] args) throws Exception {
		Config config = Config.fromSystemProperties();
		ProcessMetrics.initialize(config.requireAllocationMetrics());
		int port = config.port() == 0 ? findFreePort() : config.port();

		BenchmarkResource resource = new BenchmarkResource();
		InstanceProvider defaultInstanceProvider = InstanceProvider.defaultInstance();
		InstanceProvider instanceProvider = new InstanceProvider() {
			@Override
			public <T> T provide(Class<T> instanceClass) {
				if (instanceClass == BenchmarkResource.class)
					return instanceClass.cast(resource);
				return defaultInstanceProvider.provide(instanceClass);
			}
		};

		HttpServer.Builder serverBuilder = HttpServer.withPort(port)
				.host(HOST)
				.socketPendingConnectionLimit(config.socketPendingConnectionLimit())
				.concurrency(config.serverConcurrency())
				.requestHandlerConcurrency(config.handlerConcurrency())
				.requestHandlerQueueCapacity(config.handlerQueueCapacity());
		DefaultHttpServer server = (DefaultHttpServer) serverBuilder.build();

		SokletConfig sokletConfig = SokletConfig.withHttpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(BenchmarkResource.class)))
				.instanceProvider(instanceProvider)
				.metricsCollector(config.metricsEnabled() ? MetricsCollector.defaultInstance() : MetricsCollector.disabledInstance())
				.build();

		try (Soklet soklet = Soklet.fromConfig(sokletConfig)) {
			soklet.start();
			Thread.sleep(250L);

			System.out.printf(Locale.ROOT,
					"Soklet end-to-end HTTP benchmark: host=%s port=%d warmup=%ds duration=%ds iterations=%d clients=%d serverConcurrency=%d handlerConcurrency=%d socketPendingConnectionLimit=%d metrics=%s%n",
					HOST,
					port,
					config.warmupSeconds(),
					config.durationSeconds(),
					config.iterations(),
					config.clients(),
					config.serverConcurrency(),
					config.handlerConcurrency(),
					config.socketPendingConnectionLimit(),
					config.metricsEnabled());
			System.out.println("Allocation scope: whole JVM, including colocated clients and server; not server-only. "
					+ "Unavailable counters remain null in JSON. Zero client errors are required for qualification.");
			for (Scenario scenario : config.scenarios())
				System.out.printf(Locale.ROOT,
						"scenario=%s expectedStatus=%d expectedBodyBytes=%d producerWrites=%d producerWriteBytes=%d requestedDelayBeforeWriteMillis=%d validation=exact-body%n",
						scenario.name(), scenario.expectedStatusCode(), scenario.expectedBody().length,
						scenario.producerWriteCount(), scenario.producerWriteSizeBytes(), scenario.delayBeforeWriteMillis());

			List<ScenarioResult> results = new ArrayList<>();
			for (int iteration = 1; iteration <= config.iterations(); iteration++) {
				List<Scenario> iterationScenarios = rotatedScenarios(config.scenarios(), iteration - 1);
				System.out.printf(Locale.ROOT,
						"Iteration %d/%d scenarioOrder=%s%n",
						iteration,
						config.iterations(),
						scenarioNames(iterationScenarios));

				if (config.warmupSeconds() > 0) {
					for (Scenario scenario : iterationScenarios)
						runScenario(iteration, scenario, config.clients(), config.warmupSeconds(), false, port, server);
				}

				for (Scenario scenario : iterationScenarios) {
					ScenarioResult result = runScenario(iteration, scenario, config.clients(), config.durationSeconds(), true, port, server);
					results.add(result);
					printResult(result);
				}
			}

			List<ScenarioSummary> summaries = summarize(config.scenarios(), results);
			printSummaries(summaries);

			if (config.outputPath() != null)
				writeJson(config, port, results, summaries);
			if (results.stream().anyMatch(result -> result.errors() != 0 || result.requests() == 0))
				throw new IllegalStateException("Benchmark qualification requires successful requests and zero client errors");
			if (config.requireAllocationMetrics() && results.stream().anyMatch(result -> result.processMetrics().allocatedBytes() == null))
				throw new IllegalStateException("Whole-JVM allocation counters became unavailable during qualification");
		}
	}

	/**
	 * Resource methods used by the end-to-end benchmark harness.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	public static final class BenchmarkResource {
		@GET("/streaming")
		public MarshaledResponse streaming() {
			return STREAMING_RESPONSE;
		}

		@GET("/streaming-bulk")
		public MarshaledResponse streamingBulk() {
			return BULK_STREAMING_RESPONSE;
		}

		@GET("/streaming-paced")
		public MarshaledResponse streamingPaced() {
			return PACED_STREAMING_RESPONSE;
		}

		@GET("/output-native")
		public MarshaledResponse outputNative() { return OUTPUT_RESPONSES.get(OutputMode.NATIVE); }

		@GET("/output-scalar")
		public MarshaledResponse outputScalar() { return OUTPUT_RESPONSES.get(OutputMode.SCALAR); }

		@GET("/output-mixed")
		public MarshaledResponse outputMixed() { return OUTPUT_RESPONSES.get(OutputMode.MIXED); }

		@GET("/plaintext")
		public MarshaledResponse plaintext() {
			return PLAINTEXT_RESPONSE;
		}

		@GET("/json")
		public MarshaledResponse json() {
			return JSON_RESPONSE;
		}

		@POST("/json")
		public MarshaledResponse postJson(Request request) {
			int bodyLength = request.getBody().map(body -> body.length).orElse(0);
			return bodyLength == POST_BODY.length ? JSON_RESPONSE : BAD_REQUEST_RESPONSE;
		}
	}

	private static ScenarioResult runScenario(int iteration,
																						Scenario scenario,
																						int clients,
																						int durationSeconds,
																						boolean recordLatencies,
																						int port, DefaultHttpServer server) throws Exception {
		ExecutorService executorService = Executors.newFixedThreadPool(clients, namedThreadFactory("soklet-e2e-client-"));
		try {
			CountDownLatch ready = new CountDownLatch(clients);
			CountDownLatch start = new CountDownLatch(1);
			List<Future<WorkerResult>> futures = new ArrayList<>(clients);
			for (int i = 0; i < clients; i++) {
				futures.add(executorService.submit(() -> {
					ready.countDown();
					start.await();
					return runWorker(scenario, port, System.nanoTime() + TimeUnit.SECONDS.toNanos(durationSeconds), recordLatencies);
				}));
			}

			ready.await();
			awaitStreamingDrain(server);
			ProcessMetrics before = ProcessMetrics.capture();
			long startedAt = System.nanoTime();
			start.countDown();
			List<WorkerResult> workerResults = new ArrayList<>(clients);
			for (Future<WorkerResult> future : futures)
				workerResults.add(future.get());

			long elapsedNanos = System.nanoTime() - startedAt;
			// Include tracked production/observer tails and client teardown in allocation
			// without extending the request-throughput interval or the next mode's window.
			executorService.shutdown();
			if (!executorService.awaitTermination(5, TimeUnit.SECONDS))
				throw new IllegalStateException("Benchmark clients did not terminate after completing their requests");
			awaitStreamingDrain(server);
			ProcessMetrics after = ProcessMetrics.capture();
			return ScenarioResult.from(iteration, scenario, clients, elapsedNanos, workerResults,
					ProcessMetrics.delta(before, after));
		} finally {
			executorService.shutdownNow();
			if (!executorService.awaitTermination(5, TimeUnit.SECONDS))
				throw new IllegalStateException("Benchmark client executor did not terminate");
		}
	}

	private static void awaitStreamingDrain(DefaultHttpServer server) throws InterruptedException {
		var coordinator = server.getStreamLifecycleCoordinatorForTests().orElseThrow();
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (true) {
			var snapshot = coordinator.snapshot();
			if (snapshot.reservations() == 0 && snapshot.queuedProducers() == 0 && snapshot.runningProducers() == 0
					&& snapshot.callbacks() == 0 && snapshot.diagnostics() == 0)
				return;
			if (System.nanoTime() - deadline >= 0L)
				throw new IllegalStateException("Streaming lifecycle did not drain between benchmark windows: " + snapshot);
			Thread.sleep(1L);
		}
	}

	private static WorkerResult runWorker(Scenario scenario,
																				int port,
																				long deadlineNanos,
																				boolean recordLatencies) {
		LatencyList latencies = recordLatencies ? new LatencyList(16_384) : null;
		long requests = 0L;
		long errors = 0L;
		ErrorDiagnostics errorDiagnostics = new ErrorDiagnostics();
		Connection connection = null;

		try {
			while (System.nanoTime() < deadlineNanos) {
				ErrorPhase errorPhase = connection == null ? ErrorPhase.CONNECT : ErrorPhase.REQUEST_WRITE;
				try {
					if (connection == null)
						connection = openConnection(port);
					errorPhase = ErrorPhase.REQUEST_WRITE;

					long startedAt = System.nanoTime();
					connection.output().write(scenario.requestBytes());
					connection.output().flush();
					errorPhase = ErrorPhase.RESPONSE_READ;
					validateResponse(connection.input(), connection.headerBuffer(), connection.bodyBuffer(),
							scenario.expectedStatusCode(), scenario.expectedBody());
					long elapsed = System.nanoTime() - startedAt;
					requests++;
					if (latencies != null)
						latencies.add(elapsed);
				} catch (IOException e) {
					errors++;
					errorDiagnostics.record(e, errorPhase);
					close(connection);
					connection = null;
				}
			}
		} finally {
			close(connection);
		}

		return new WorkerResult(requests, errors, latencies == null ? new long[0] : latencies.toArray(), errorDiagnostics);
	}

	private static Connection openConnection(int port) throws IOException {
		Socket socket = new Socket(HOST, port);
		socket.setTcpNoDelay(true);
		socket.setKeepAlive(true);
		socket.setSoTimeout(10_000);
		return new Connection(
				socket,
				new BufferedInputStream(socket.getInputStream(), 32 * 1024),
				new BufferedOutputStream(socket.getOutputStream(), 32 * 1024),
				new byte[16 * 1024],
				new byte[8192]);
	}

	static void validateResponse(BufferedInputStream input, byte[] headerBuffer, byte[] bodyBuffer,
			int expectedStatusCode, byte[] expectedBody) throws IOException {
		int headerLength = readHeaders(input, headerBuffer);
		int statusCode = parseStatusCode(headerBuffer, headerLength);
		if (statusCode != expectedStatusCode)
			throw new ResponseValidationException(ErrorCategory.HTTP_STATUS, "Unexpected HTTP status: " + statusCode);
		int contentLength = parseContentLength(headerBuffer, headerLength);

		if (contentLength >= 0) {
			if (contentLength != expectedBody.length)
				throw new ResponseValidationException(ErrorCategory.BODY_VALIDATION, "Unexpected response body length: " + contentLength);
			readExpectedBytes(input, contentLength, bodyBuffer, expectedBody, 0);
		} else {
			String headers = new String(headerBuffer, 0, headerLength, StandardCharsets.US_ASCII);
			if (!headers.toLowerCase(Locale.ROOT).contains("\r\ntransfer-encoding: chunked\r\n"))
				throw new ResponseValidationException(ErrorCategory.RESPONSE_FRAMING, "Missing Content-Length or chunked transfer encoding");
			readChunkedBody(input, bodyBuffer, expectedBody);
		}
	}

	private static void readChunkedBody(BufferedInputStream input, byte[] bodyBuffer, byte[] expectedBody) throws IOException {
		int bodyOffset = 0;
		while (true) {
			int size = 0;
			int digits = 0;
			int value;
			while ((value = readRequired(input, "chunk size")) != '\r') {
				int digit = Character.digit(value, 16);
				if (digit < 0 || ++digits > 7)
					throw new ResponseValidationException(ErrorCategory.RESPONSE_FRAMING, "Invalid chunk size");
				size = size * 16 + digit;
			}
			if (digits == 0 || readRequired(input, "chunk header") != '\n')
				throw new ResponseValidationException(ErrorCategory.RESPONSE_FRAMING, "Invalid chunk header");
			bodyOffset = readExpectedBytes(input, size, bodyBuffer, expectedBody, bodyOffset);
			if (readRequired(input, "chunk terminator") != '\r' || readRequired(input, "chunk terminator") != '\n')
				throw new ResponseValidationException(ErrorCategory.RESPONSE_FRAMING, "Invalid chunk terminator");
			if (size == 0) {
				if (bodyOffset != expectedBody.length)
					throw new ResponseValidationException(ErrorCategory.BODY_VALIDATION,
							"Truncated response body: " + bodyOffset + " of " + expectedBody.length + " bytes");
				return;
			}
		}
	}

	private static int readRequired(BufferedInputStream input, String phase) throws IOException {
		int value = input.read();
		if (value < 0)
			throw new EOFException("Unexpected end of response during " + phase);
		return value;
	}

	private static int readHeaders(BufferedInputStream input, byte[] headerBuffer) throws IOException {
		int length = 0;

		while (true) {
			int value = input.read();
			if (value < 0)
				throw new EOFException("Unexpected end of response headers");

			if (length == headerBuffer.length)
				throw new ResponseValidationException(ErrorCategory.RESPONSE_FRAMING, "Response headers too large");

			headerBuffer[length++] = (byte) value;
			if (length >= 4
					&& headerBuffer[length - 4] == '\r'
					&& headerBuffer[length - 3] == '\n'
					&& headerBuffer[length - 2] == '\r'
					&& headerBuffer[length - 1] == '\n') {
				return length;
			}
		}
	}

	private static int parseStatusCode(byte[] headers, int length) throws IOException {
		int firstSpace = indexOf(headers, length, 0, (byte) ' ');
		if (firstSpace < 0 || firstSpace + 4 > length)
			throw new ResponseValidationException(ErrorCategory.RESPONSE_FRAMING, "Malformed status line");

		return parsePositiveInt(headers, firstSpace + 1, firstSpace + 4);
	}

	private static int parseContentLength(byte[] headers, int length) throws IOException {
		int lineStart = indexOf(headers, length, 0, (byte) '\n') + 1;
		while (lineStart > 0 && lineStart < length) {
			if (headers[lineStart] == '\r' && lineStart + 1 < length && headers[lineStart + 1] == '\n')
				return -1;

			int lineEnd = crlfAt(headers, length, lineStart);
			if (lineEnd < 0)
				return -1;

			int colon = indexOf(headers, lineEnd, lineStart, (byte) ':');
			if (colon > lineStart && asciiEqualsIgnoreCase(headers, lineStart, colon, "Content-Length")) {
				int valueStart = colon + 1;
				while (valueStart < lineEnd && (headers[valueStart] == ' ' || headers[valueStart] == '\t'))
					valueStart++;
				return parsePositiveInt(headers, valueStart, lineEnd);
			}

			lineStart = lineEnd + 2;
		}

		return -1;
	}

	private static int parsePositiveInt(byte[] bytes, int start, int end) throws IOException {
		int value = 0;
		for (int i = start; i < end; i++) {
			byte b = bytes[i];
			if (b < '0' || b > '9')
				throw new ResponseValidationException(ErrorCategory.RESPONSE_FRAMING, "Invalid integer");
			value = value * 10 + (b - '0');
		}
		return value;
	}

	private static int readExpectedBytes(BufferedInputStream input, int byteCount, byte[] buffer,
			byte[] expectedBody, int bodyOffset) throws IOException {
		if (byteCount > expectedBody.length - bodyOffset)
			throw new ResponseValidationException(ErrorCategory.BODY_VALIDATION, "Response body exceeds expected length: " + expectedBody.length);
		int remaining = byteCount;
		while (remaining > 0) {
			int read = input.read(buffer, 0, Math.min(buffer.length, remaining));
			if (read < 0)
				throw new EOFException("Unexpected end of response body");
			if (Arrays.mismatch(buffer, 0, read, expectedBody, bodyOffset, bodyOffset + read) >= 0)
				throw new ResponseValidationException(ErrorCategory.BODY_VALIDATION, "Unexpected response body bytes at offset " + bodyOffset);
			bodyOffset += read;
			remaining -= read;
		}
		return bodyOffset;
	}

	private static int indexOf(byte[] bytes, int length, int start, byte value) {
		for (int i = start; i < length; i++)
			if (bytes[i] == value)
				return i;
		return -1;
	}

	private static int crlfAt(byte[] bytes, int length, int start) {
		for (int i = start; i + 1 < length; i++)
			if (bytes[i] == '\r' && bytes[i + 1] == '\n')
				return i;
		return -1;
	}

	private static boolean asciiEqualsIgnoreCase(byte[] bytes, int start, int end, String expected) {
		if (end - start != expected.length())
			return false;

		for (int i = 0; i < expected.length(); i++) {
			int actual = bytes[start + i] & 0xFF;
			int expectedChar = expected.charAt(i);
			if (actual >= 'A' && actual <= 'Z')
				actual += 'a' - 'A';
			if (expectedChar >= 'A' && expectedChar <= 'Z')
				expectedChar += 'a' - 'A';
			if (actual != expectedChar)
				return false;
		}

		return true;
	}

	private static void printResult(ScenarioResult result) {
		System.out.printf(Locale.ROOT,
				"iter=%02d %-16s requests=%9d errors=%5d throughput=%10.0f req/s body=%9.2f MiB/s latency: avg=%7.0f us p50=%7.0f us p90=%7.0f us p99=%7.0f us max=%7.0f us%n",
				result.iteration(),
				result.scenario().name(),
				result.requests(),
				result.errors(),
				result.throughputRequestsPerSecond(),
				result.throughputRequestsPerSecond() * result.scenario().expectedBody().length / (1024D * 1024D),
				nanosToMicros(result.averageLatencyNanos()),
				nanosToMicros(result.p50LatencyNanos()),
				nanosToMicros(result.p90LatencyNanos()),
				nanosToMicros(result.p99LatencyNanos()),
				nanosToMicros(result.maxLatencyNanos()));
		printErrorDiagnostics("iter=" + result.iteration() + " scenario=" + result.scenario().name(), result.errorDiagnostics());
		System.out.printf(Locale.ROOT, "iter=%02d %-16s wholeJvmAllocationBytes=%s bytes/request=%s bytes/bodyByte=%s gcCollections=%s gcMillis=%s%n",
				result.iteration(), result.scenario().name(), result.processMetrics().allocatedBytes(),
				result.processMetrics().allocatedBytesPerRequest(result.requests()),
				result.processMetrics().allocatedBytesPerBodyByte(result.requests(), result.scenario().expectedBody().length),
				result.processMetrics().gcCollections(), result.processMetrics().gcMillis());
	}

	private static void printSummaries(List<ScenarioSummary> summaries) {
		System.out.println("Summary (median across iterations)");
		for (ScenarioSummary summary : summaries) {
			System.out.printf(Locale.ROOT,
					"median %-16s runs=%2d totalRequests=%9d totalErrors=%5d throughput=%10.0f req/s body=%9.2f MiB/s latency: avg=%7.0f us p50=%7.0f us p90=%7.0f us p99=%7.0f us max=%7.0f us%n",
					summary.scenario().name(),
					summary.runs(),
					summary.totalRequests(),
					summary.totalErrors(),
					summary.medianThroughputRequestsPerSecond(),
					summary.medianThroughputRequestsPerSecond() * summary.scenario().expectedBody().length / (1024D * 1024D),
					nanosToMicros(summary.medianAverageLatencyNanos()),
					nanosToMicros(summary.medianP50LatencyNanos()),
					nanosToMicros(summary.medianP90LatencyNanos()),
					nanosToMicros(summary.medianP99LatencyNanos()),
					nanosToMicros(summary.maxLatencyNanos()));
			printErrorDiagnostics("summary scenario=" + summary.scenario().name(), summary.errorDiagnostics());
		}
	}

	private static void printErrorDiagnostics(String prefix, ErrorDiagnostics diagnostics) {
		for (ErrorCategory category : ERROR_CATEGORIES)
			if (diagnostics.count(category) > 0)
				System.out.printf(Locale.ROOT, "%s errorCategory=%s count=%d firstExample=\"%s\"%n",
						prefix, category.label, diagnostics.count(category), escape(diagnostics.firstExample(category)));
	}

	private static double nanosToMicros(double nanos) {
		return nanos / 1_000D;
	}

	private static int findFreePort() throws IOException {
		try (ServerSocket serverSocket = new ServerSocket(0, 50, InetAddress.getByName(HOST))) {
			serverSocket.setReuseAddress(true);
			return serverSocket.getLocalPort();
		}
	}

	private static void writeJson(Config config, int port, List<ScenarioResult> results, List<ScenarioSummary> summaries) throws IOException {
		java.nio.file.Path output = java.nio.file.Path.of(config.outputPath());
		java.nio.file.Path parent = output.toAbsolutePath().getParent();
		if (parent != null)
			java.nio.file.Files.createDirectories(parent);
		StringBuilder json = new StringBuilder(2048);

		json.append("{\n");
		json.append("  \"generatedAt\": \"").append(Instant.now()).append("\",\n");
		json.append("  \"javaVersion\": \"").append(escape(System.getProperty("java.version"))).append("\",\n");
		json.append("  \"availableProcessors\": ").append(Runtime.getRuntime().availableProcessors()).append(",\n");
		json.append("  \"host\": \"").append(HOST).append("\",\n");
		json.append("  \"port\": ").append(port).append(",\n");
		json.append("  \"warmupSeconds\": ").append(config.warmupSeconds()).append(",\n");
		json.append("  \"durationSeconds\": ").append(config.durationSeconds()).append(",\n");
		json.append("  \"iterations\": ").append(config.iterations()).append(",\n");
		json.append("  \"clients\": ").append(config.clients()).append(",\n");
		json.append("  \"serverConcurrency\": ").append(config.serverConcurrency()).append(",\n");
		json.append("  \"handlerConcurrency\": ").append(config.handlerConcurrency()).append(",\n");
		json.append("  \"handlerQueueCapacity\": ").append(config.handlerQueueCapacity()).append(",\n");
		json.append("  \"socketPendingConnectionLimit\": ").append(config.socketPendingConnectionLimit()).append(",\n");
		json.append("  \"metricsEnabled\": ").append(config.metricsEnabled()).append(",\n");
		json.append("  \"allocationRequired\": ").append(config.requireAllocationMetrics()).append(",\n");
		json.append("  \"allocationScope\": \"whole-jvm-including-colocated-clients-server-observer-tails-and-measurement-bookkeeping\",\n");
		json.append("  \"allocationCounter\": \"com.sun.management.ThreadMXBean.getTotalThreadAllocatedBytes\",\n");
		json.append("  \"allocationWindow\": \"clients-ready-through-client-exit-and-tracked-stream-drain-before-result-aggregation\",\n");
		json.append("  \"responseValidation\": \"exact-status-body-length-and-bytes\",\n");
		json.append("  \"scenarios\": [\n");
		for (int i = 0; i < config.scenarios().size(); i++) {
			Scenario scenario = config.scenarios().get(i);
			json.append("    {\"name\": \"").append(escape(scenario.name())).append("\", ");
			json.append("\"expectedStatusCode\": ").append(scenario.expectedStatusCode()).append(", ");
			json.append("\"expectedBodyBytes\": ").append(scenario.expectedBody().length).append(", ");
			json.append("\"producerWriteCount\": ").append(scenario.producerWriteCount()).append(", ");
			json.append("\"producerWriteSizeBytes\": ").append(scenario.producerWriteSizeBytes()).append(", ");
			json.append("\"requestedDelayBeforeWriteMillis\": ").append(scenario.delayBeforeWriteMillis()).append('}');
			if (i + 1 < config.scenarios().size())
				json.append(',');
			json.append('\n');
		}
		json.append("  ],\n");
		json.append("  \"results\": [\n");

		for (int i = 0; i < results.size(); i++) {
			ScenarioResult result = results.get(i);
			json.append("    {\n");
			json.append("      \"iteration\": ").append(result.iteration()).append(",\n");
			json.append("      \"scenario\": \"").append(escape(result.scenario().name())).append("\",\n");
			json.append("      \"requests\": ").append(result.requests()).append(",\n");
			json.append("      \"validatedBodyBytes\": ").append(result.requests() * result.scenario().expectedBody().length).append(",\n");
			json.append("      \"errors\": ").append(result.errors()).append(",\n");
			appendProcessMetrics(json, result.processMetrics(), result.requests(), result.scenario().expectedBody().length);
			appendErrorDiagnostics(json, result.errorDiagnostics());
			json.append("      \"elapsedNanos\": ").append(result.elapsedNanos()).append(",\n");
			json.append("      \"throughputRequestsPerSecond\": ").append(formatDouble(result.throughputRequestsPerSecond())).append(",\n");
			json.append("      \"throughputBodyMiBPerSecond\": ").append(formatDouble(
					result.throughputRequestsPerSecond() * result.scenario().expectedBody().length / (1024D * 1024D))).append(",\n");
			json.append("      \"latencyNanos\": {\n");
			json.append("        \"average\": ").append(formatDouble(result.averageLatencyNanos())).append(",\n");
			json.append("        \"p50\": ").append(result.p50LatencyNanos()).append(",\n");
			json.append("        \"p90\": ").append(result.p90LatencyNanos()).append(",\n");
			json.append("        \"p99\": ").append(result.p99LatencyNanos()).append(",\n");
			json.append("        \"max\": ").append(result.maxLatencyNanos()).append("\n");
			json.append("      }\n");
			json.append("    }");
			if (i + 1 < results.size())
				json.append(',');
			json.append('\n');
		}

		json.append("  ],\n");
		json.append("  \"summaries\": [\n");
		for (int i = 0; i < summaries.size(); i++) {
			ScenarioSummary summary = summaries.get(i);
			json.append("    {\n");
			json.append("      \"scenario\": \"").append(escape(summary.scenario().name())).append("\",\n");
			json.append("      \"runs\": ").append(summary.runs()).append(",\n");
			json.append("      \"totalRequests\": ").append(summary.totalRequests()).append(",\n");
			json.append("      \"validatedBodyBytes\": ").append(summary.totalRequests() * summary.scenario().expectedBody().length).append(",\n");
			json.append("      \"totalErrors\": ").append(summary.totalErrors()).append(",\n");
			appendProcessMetrics(json, summary.processMetrics(), summary.totalRequests(), summary.scenario().expectedBody().length);
			appendErrorDiagnostics(json, summary.errorDiagnostics());
			json.append("      \"medianThroughputRequestsPerSecond\": ").append(formatDouble(summary.medianThroughputRequestsPerSecond())).append(",\n");
			json.append("      \"medianThroughputBodyMiBPerSecond\": ").append(formatDouble(
					summary.medianThroughputRequestsPerSecond() * summary.scenario().expectedBody().length / (1024D * 1024D))).append(",\n");
			json.append("      \"latencyNanos\": {\n");
			json.append("        \"medianAverage\": ").append(formatDouble(summary.medianAverageLatencyNanos())).append(",\n");
			json.append("        \"medianP50\": ").append(formatDouble(summary.medianP50LatencyNanos())).append(",\n");
			json.append("        \"medianP90\": ").append(formatDouble(summary.medianP90LatencyNanos())).append(",\n");
			json.append("        \"medianP99\": ").append(formatDouble(summary.medianP99LatencyNanos())).append(",\n");
			json.append("        \"max\": ").append(summary.maxLatencyNanos()).append("\n");
			json.append("      }\n");
			json.append("    }");
			if (i + 1 < summaries.size())
				json.append(',');
			json.append('\n');
		}
		json.append("  ]\n");
		json.append("}\n");
		java.nio.file.Files.writeString(output, json.toString(), StandardCharsets.UTF_8);
		System.out.printf(Locale.ROOT, "Wrote %s%n", output);
	}

	private static String formatDouble(double value) {
		return String.format(Locale.ROOT, "%.3f", value);
	}

	static void appendProcessMetrics(StringBuilder json, ProcessMetrics metrics, long requests, int bodyBytes) {
		json.append("      \"wholeJvmAllocatedBytes\": ").append(metrics.allocatedBytes()).append(",\n");
		json.append("      \"wholeJvmAllocatedBytesPerRequest\": ").append(nullableDouble(metrics.allocatedBytesPerRequest(requests))).append(",\n");
		json.append("      \"wholeJvmAllocatedBytesPerValidatedBodyByte\": ").append(nullableDouble(metrics.allocatedBytesPerBodyByte(requests, bodyBytes))).append(",\n");
		json.append("      \"gcCollections\": ").append(metrics.gcCollections()).append(",\n");
		json.append("      \"gcCollectionMillis\": ").append(metrics.gcMillis()).append(",\n");
	}

	private static String nullableDouble(Double value) {
		return value == null ? "null" : formatDouble(value);
	}

	private static void appendErrorDiagnostics(StringBuilder json, ErrorDiagnostics diagnostics) {
		json.append("      \"errorDiagnostics\": {\n");
		for (int index = 0; index < ERROR_CATEGORIES.length; index++) {
			ErrorCategory category = ERROR_CATEGORIES[index];
			json.append("        \"").append(category.label).append("\": {\"count\": ").append(diagnostics.count(category));
			String example = diagnostics.firstExample(category);
			json.append(", \"firstExample\": ");
			if (example == null)
				json.append("null");
			else
				json.append('"').append(escape(example)).append('"');
			json.append('}');
			if (index + 1 < ERROR_CATEGORIES.length)
				json.append(',');
			json.append('\n');
		}
		json.append("      },\n");
	}

	private static String escape(String value) {
		StringBuilder escaped = new StringBuilder(value.length());
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			switch (c) {
				case '"' -> escaped.append("\\\"");
				case '\\' -> escaped.append("\\\\");
				case '\b' -> escaped.append("\\b");
				case '\f' -> escaped.append("\\f");
				case '\n' -> escaped.append("\\n");
				case '\r' -> escaped.append("\\r");
				case '\t' -> escaped.append("\\t");
				default -> {
					if (c < 0x20)
						escaped.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
					else
						escaped.append(c);
				}
			}
		}
		return escaped.toString();
	}

	private static byte[] ascii(String value) {
		return value.getBytes(StandardCharsets.US_ASCII);
	}

	private static byte[] patternedBody(int length) {
		byte[] body = new byte[length];
		for (int i = 0; i < length; i++)
			body[i] = (byte) (i * 31 ^ (i >>> 8) ^ (i >>> 16));
		return body;
	}

	private static byte[][] splitBody(byte[] body, int writeSize) {
		byte[][] writes = new byte[body.length / writeSize][];
		for (int i = 0; i < writes.length; i++)
			writes[i] = Arrays.copyOfRange(body, i * writeSize, (i + 1) * writeSize);
		return writes;
	}

	private static MarshaledResponse outputResponse(OutputMode mode) {
		return MarshaledResponse.withStatusCode(200)
				.headers(Map.of("Content-Type", Set.of("text/plain; charset=UTF-8")))
				.stream(responseStream -> writeOutput(mode, responseStream)).build();
	}

	static byte[] outputBody() { return OUTPUT_BODY.clone(); }

	static void writeOutput(OutputMode mode, ResponseStream responseStream) throws Exception {
		switch (mode) {
			case NATIVE -> {
				for (byte[] bytes : OUTPUT_WRITES)
					responseStream.write(bytes);
			}
			case SCALAR -> {
				OutputStream outputStream = responseStream.asOutputStream();
				for (byte value : OUTPUT_BODY)
					outputStream.write(value);
			}
			case MIXED -> {
				OutputStream outputStream = responseStream.asOutputStream();
				for (int offset = 0; offset < OUTPUT_BODY.length; offset += OUTPUT_WRITE_SIZE) {
					for (int scalar = offset; scalar < offset + 128; scalar++)
						outputStream.write(OUTPUT_BODY[scalar]);
					responseStream.write(OUTPUT_BODY, offset + 128, 1024);
					responseStream.write(ByteBuffer.wrap(OUTPUT_BODY, offset + 1152, 1024));
					responseStream.write(OUTPUT_DIRECT.duplicate().position(offset + 2176).limit(offset + 3200));
					responseStream.write(ByteBuffer.wrap(OUTPUT_BODY, offset + 3200, 1024).asReadOnlyBuffer());
					outputStream.write(OUTPUT_BODY, offset + 4224, OUTPUT_WRITE_SIZE - 4224);
				}
			}
		}
	}

	enum OutputMode {
		NATIVE("output-native", 8, OUTPUT_WRITE_SIZE), SCALAR("output-scalar", 65_536, 1),
		MIXED("output-mixed", 8 * 133, -1);
		final String scenarioName;
		final int writeCount;
		final int writeSize;
		OutputMode(String scenarioName, int writeCount, int writeSize) {
			this.scenarioName = scenarioName;
			this.writeCount = writeCount;
			this.writeSize = writeSize;
		}
	}

	static Scenario outputScenario(OutputMode mode) {
		return new Scenario(mode.scenarioName, ascii("GET /" + mode.scenarioName
				+ " HTTP/1.1\r\nHost: 127.0.0.1\r\nAccept: text/plain\r\nConnection: keep-alive\r\n\r\n"),
				200, OUTPUT_BODY, mode.writeCount, mode.writeSize, 0);
	}

	private static List<Scenario> rotatedScenarios(List<Scenario> scenarios, int offset) {
		if (scenarios.size() <= 1)
			return scenarios;

		List<Scenario> rotated = new ArrayList<>(scenarios.size());
		int rotation = offset % scenarios.size();
		for (int i = 0; i < scenarios.size(); i++)
			rotated.add(scenarios.get((i + rotation) % scenarios.size()));
		return rotated;
	}

	private static String scenarioNames(List<Scenario> scenarios) {
		StringBuilder names = new StringBuilder();
		for (Scenario scenario : scenarios) {
			if (!names.isEmpty())
				names.append(',');
			names.append(scenario.name());
		}
		return names.toString();
	}

	private static List<ScenarioSummary> summarize(List<Scenario> scenarios, List<ScenarioResult> results) {
		List<ScenarioSummary> summaries = new ArrayList<>(scenarios.size());
		for (Scenario scenario : scenarios) {
			List<ScenarioResult> scenarioResults = new ArrayList<>();
			for (ScenarioResult result : results)
				if (result.scenario().name().equals(scenario.name()))
					scenarioResults.add(result);

			if (!scenarioResults.isEmpty())
				summaries.add(ScenarioSummary.from(scenario, scenarioResults));
		}
		return summaries;
	}

	private static double median(double[] values) {
		if (values.length == 0)
			return 0D;

		Arrays.sort(values);
		int middle = values.length / 2;
		if (values.length % 2 == 1)
			return values[middle];
		return (values[middle - 1] / 2D) + (values[middle] / 2D);
	}

	private static double median(long[] values) {
		if (values.length == 0)
			return 0D;

		Arrays.sort(values);
		int middle = values.length / 2;
		if (values.length % 2 == 1)
			return values[middle];
		return (values[middle - 1] / 2D) + (values[middle] / 2D);
	}

	private static void close(Connection connection) {
		if (connection == null)
			return;
		try {
			connection.socket().close();
		} catch (IOException ignored) {
		}
	}

	private static ThreadFactory namedThreadFactory(String prefix) {
		return new ThreadFactory() {
			private int next;

			@Override
			public Thread newThread(Runnable runnable) {
				Thread thread = new Thread(runnable, prefix + ++next);
				thread.setDaemon(true);
				return thread;
			}
		};
	}

	private record Config(
			int port,
			int warmupSeconds,
			int durationSeconds,
			int iterations,
			int clients,
			int serverConcurrency,
			int handlerConcurrency,
			int handlerQueueCapacity,
			int socketPendingConnectionLimit,
			boolean metricsEnabled,
			boolean requireAllocationMetrics,
			List<Scenario> scenarios,
			String outputPath) {
		static Config fromSystemProperties() {
			int processors = Runtime.getRuntime().availableProcessors();
			int serverConcurrency = integerProperty("soklet.e2e.serverConcurrency", Math.max(1, processors));
			int handlerConcurrency = integerProperty("soklet.e2e.handlerConcurrency", Math.max(1, serverConcurrency * 16));
			return new Config(
					integerProperty("soklet.e2e.port", 0),
					integerProperty("soklet.e2e.warmupSeconds", 3),
					integerProperty("soklet.e2e.durationSeconds", 10),
					Math.max(1, integerProperty("soklet.e2e.iterations", 3)),
					integerProperty("soklet.e2e.clients", Math.max(1, processors * 4)),
					serverConcurrency,
					handlerConcurrency,
					integerProperty("soklet.e2e.handlerQueueCapacity", Math.max(1, handlerConcurrency * 64)),
					integerProperty("soklet.e2e.socketPendingConnectionLimit", 0),
					booleanProperty("soklet.e2e.metrics", false),
					booleanProperty("soklet.e2e.requireAllocationMetrics", false),
					scenariosProperty(),
					System.getProperty("soklet.e2e.output", "target/e2e-results.json"));
		}
	}

	record Scenario(String name, byte[] requestBytes, int expectedStatusCode, byte[] expectedBody,
			int producerWriteCount, int producerWriteSizeBytes, int delayBeforeWriteMillis) {
	}

	private record Connection(
			Socket socket,
			BufferedInputStream input,
			BufferedOutputStream output,
			byte[] headerBuffer,
			byte[] bodyBuffer) {
	}

	/** Null means unavailable, never zero; deltas cover the complete colocated JVM. */
	record ProcessMetrics(Long allocatedBytes, Long gcCollections, Long gcMillis) {
		private static final List<GarbageCollectorMXBean> GC_BEANS = ManagementFactory.getGarbageCollectorMXBeans();
		private static final com.sun.management.ThreadMXBean ALLOCATION_BEAN =
				ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean ? bean : null;
		private static final Method TOTAL_ALLOCATED_BYTES = totalAllocatedBytesMethod();

		private static Method totalAllocatedBytesMethod() {
			try {
				// Keep the module's Java 17 API target while using this newer-JDK
				// whole-JVM counter when available. Per-thread sums are not equivalent.
				return com.sun.management.ThreadMXBean.class.getMethod("getTotalThreadAllocatedBytes");
			} catch (NoSuchMethodException unavailable) {
				return null;
			}
		}

		static void initialize(boolean requireAllocation) {
			if (ALLOCATION_BEAN != null && ALLOCATION_BEAN.isThreadAllocatedMemorySupported()
					&& !ALLOCATION_BEAN.isThreadAllocatedMemoryEnabled())
				ALLOCATION_BEAN.setThreadAllocatedMemoryEnabled(true);
			if (requireAllocation && capture().allocatedBytes() == null)
				throw new IllegalStateException("This JVM does not expose total thread allocation; allocation qualification cannot run");
		}

		static ProcessMetrics capture() {
			Long allocation = null;
			if (ALLOCATION_BEAN != null && TOTAL_ALLOCATED_BYTES != null && ALLOCATION_BEAN.isThreadAllocatedMemorySupported()
					&& ALLOCATION_BEAN.isThreadAllocatedMemoryEnabled()) {
				try {
					long bytes = (long) TOTAL_ALLOCATED_BYTES.invoke(ALLOCATION_BEAN);
					if (bytes >= 0L) allocation = bytes;
				} catch (IllegalAccessException failure) {
					throw new IllegalStateException("Cannot access the JVM's public total allocation counter", failure);
				} catch (InvocationTargetException failure) {
					Throwable cause = failure.getCause();
					// Some VMs support per-thread allocation without a whole-JVM counter.
					if (!(cause instanceof UnsupportedOperationException)) {
						if (cause instanceof RuntimeException runtime) throw runtime;
						if (cause instanceof Error error) throw error;
						throw new IllegalStateException("JVM total allocation counter failed", cause);
					}
				}
			}
			Long collections = GC_BEANS.isEmpty() ? null : 0L;
			Long millis = GC_BEANS.isEmpty() ? null : 0L;
			for (GarbageCollectorMXBean bean : GC_BEANS) {
				long count = bean.getCollectionCount();
				long time = bean.getCollectionTime();
				collections = collections == null || count < 0L ? null : collections + count;
				millis = millis == null || time < 0L ? null : millis + time;
			}
			return new ProcessMetrics(allocation, collections, millis);
		}

		static ProcessMetrics delta(ProcessMetrics before, ProcessMetrics after) {
			return new ProcessMetrics(difference(before.allocatedBytes, after.allocatedBytes),
					difference(before.gcCollections, after.gcCollections), difference(before.gcMillis, after.gcMillis));
		}

		static ProcessMetrics total(List<ProcessMetrics> samples) {
			Long allocation = 0L;
			Long collections = 0L;
			Long millis = 0L;
			for (ProcessMetrics sample : samples) {
				allocation = allocation == null || sample.allocatedBytes == null ? null : allocation + sample.allocatedBytes;
				collections = collections == null || sample.gcCollections == null ? null : collections + sample.gcCollections;
				millis = millis == null || sample.gcMillis == null ? null : millis + sample.gcMillis;
			}
			return new ProcessMetrics(allocation, collections, millis);
		}

		private static Long difference(Long before, Long after) {
			return before == null || after == null || before < 0L || after < before ? null : after - before;
		}

		Double allocatedBytesPerRequest(long requests) {
			return allocatedBytes == null || requests <= 0L ? null : allocatedBytes / (double) requests;
		}

		Double allocatedBytesPerBodyByte(long requests, int bodyBytes) {
			return allocatedBytes == null || requests <= 0L || bodyBytes <= 0
					? null : allocatedBytes / ((double) requests * bodyBytes);
		}
	}

	private record WorkerResult(long requests, long errors, long[] latencies, ErrorDiagnostics errorDiagnostics) {
	}

	private record ScenarioResult(
			int iteration,
			Scenario scenario,
			int clients,
			long elapsedNanos,
			long requests,
			long errors,
			long[] latencies,
			ErrorDiagnostics errorDiagnostics,
			ProcessMetrics processMetrics) {
		static ScenarioResult from(int iteration, Scenario scenario, int clients, long elapsedNanos, List<WorkerResult> workerResults,
				ProcessMetrics processMetrics) {
			long requests = 0L;
			long errors = 0L;
			int latencyCount = 0;
			ErrorDiagnostics errorDiagnostics = new ErrorDiagnostics();

			for (WorkerResult workerResult : workerResults) {
				requests += workerResult.requests();
				errors += workerResult.errors();
				latencyCount += workerResult.latencies().length;
				errorDiagnostics.add(workerResult.errorDiagnostics());
			}

			long[] latencies = new long[latencyCount];
			int offset = 0;
			for (WorkerResult workerResult : workerResults) {
				System.arraycopy(workerResult.latencies(), 0, latencies, offset, workerResult.latencies().length);
				offset += workerResult.latencies().length;
			}
			Arrays.sort(latencies);

			return new ScenarioResult(iteration, scenario, clients, elapsedNanos, requests, errors, latencies, errorDiagnostics, processMetrics);
		}

		double throughputRequestsPerSecond() {
			return requests * 1_000_000_000D / Math.max(1D, elapsedNanos);
		}

		double averageLatencyNanos() {
			if (latencies.length == 0)
				return 0D;
			long sum = 0L;
			for (long latency : latencies)
				sum += latency;
			return sum / (double) latencies.length;
		}

		long p50LatencyNanos() {
			return percentile(0.50D);
		}

		long p90LatencyNanos() {
			return percentile(0.90D);
		}

		long p99LatencyNanos() {
			return percentile(0.99D);
		}

		long maxLatencyNanos() {
			return latencies.length == 0 ? 0L : latencies[latencies.length - 1];
		}

		private long percentile(double percentile) {
			if (latencies.length == 0)
				return 0L;
			int index = (int) Math.ceil(percentile * latencies.length) - 1;
			return latencies[Math.max(0, Math.min(index, latencies.length - 1))];
		}
	}

	private record ScenarioSummary(
			Scenario scenario,
			int runs,
			long totalRequests,
			long totalErrors,
			double medianThroughputRequestsPerSecond,
			double medianAverageLatencyNanos,
			double medianP50LatencyNanos,
			double medianP90LatencyNanos,
			double medianP99LatencyNanos,
			long maxLatencyNanos,
			ErrorDiagnostics errorDiagnostics,
			ProcessMetrics processMetrics) {
		static ScenarioSummary from(Scenario scenario, List<ScenarioResult> results) {
			long totalRequests = 0L;
			long totalErrors = 0L;
			long maxLatencyNanos = 0L;
			double[] throughputs = new double[results.size()];
			double[] averageLatencies = new double[results.size()];
			long[] p50Latencies = new long[results.size()];
			long[] p90Latencies = new long[results.size()];
			long[] p99Latencies = new long[results.size()];
			ErrorDiagnostics errorDiagnostics = new ErrorDiagnostics();

			for (int i = 0; i < results.size(); i++) {
				ScenarioResult result = results.get(i);
				totalRequests += result.requests();
				totalErrors += result.errors();
				maxLatencyNanos = Math.max(maxLatencyNanos, result.maxLatencyNanos());
				throughputs[i] = result.throughputRequestsPerSecond();
				averageLatencies[i] = result.averageLatencyNanos();
				p50Latencies[i] = result.p50LatencyNanos();
				p90Latencies[i] = result.p90LatencyNanos();
				p99Latencies[i] = result.p99LatencyNanos();
				errorDiagnostics.add(result.errorDiagnostics());
			}

			return new ScenarioSummary(
					scenario,
					results.size(),
					totalRequests,
					totalErrors,
					median(throughputs),
					median(averageLatencies),
					median(p50Latencies),
					median(p90Latencies),
					median(p99Latencies),
					maxLatencyNanos,
					errorDiagnostics,
					ProcessMetrics.total(results.stream().map(ScenarioResult::processMetrics).toList()));
		}
	}

	enum ErrorCategory {
		CONNECT_FAILURE("connect-failure"), HTTP_STATUS("http-status"), READ_TIMEOUT("read-timeout"),
		UNEXPECTED_EOF("unexpected-eof"), BODY_VALIDATION("body-validation"),
		RESPONSE_FRAMING("response-framing"), SOCKET_IO("socket-io"), OTHER_IO("other-io");

		private final String label;
		ErrorCategory(String label) { this.label = label; }
	}

	enum ErrorPhase { CONNECT, REQUEST_WRITE, RESPONSE_READ }

	private static final class ResponseValidationException extends IOException {
		private static final long serialVersionUID = 1L;
		private final ErrorCategory category;

		private ResponseValidationException(ErrorCategory category, String message) {
			super(message);
			this.category = category;
		}
	}

	static final class ErrorDiagnostics {
		private final long[] counts = new long[ERROR_CATEGORIES.length];
		private final String[] firstExamples = new String[ERROR_CATEGORIES.length];
		private final long[] firstObservedNanos = new long[ERROR_CATEGORIES.length];

		void record(IOException failure, ErrorPhase phase) {
			ErrorCategory category = classifyError(failure, phase == ErrorPhase.CONNECT);
			int index = category.ordinal();
			this.counts[index]++;
			if (this.firstExamples[index] == null) {
				this.firstObservedNanos[index] = System.nanoTime();
				StringBuilder example = new StringBuilder(ERROR_EXAMPLE_LIMIT);
				appendBounded(example, phase.name());
				appendBounded(example, ": ");
				appendBounded(example, failure.getClass().getSimpleName());
				appendBounded(example, ": ");
				if (failure.getMessage() != null)
					appendBounded(example, failure.getMessage());
				this.firstExamples[index] = example.toString();
			}
		}

		void add(ErrorDiagnostics other) {
			for (int index = 0; index < ERROR_CATEGORIES.length; index++) {
				this.counts[index] += other.counts[index];
				if (other.firstExamples[index] != null && (this.firstExamples[index] == null
						|| other.firstObservedNanos[index] < this.firstObservedNanos[index])) {
					this.firstExamples[index] = other.firstExamples[index];
					this.firstObservedNanos[index] = other.firstObservedNanos[index];
				}
			}
		}

		long count(ErrorCategory category) { return this.counts[category.ordinal()]; }
		String firstExample(ErrorCategory category) { return this.firstExamples[category.ordinal()]; }

		private static void appendBounded(StringBuilder example, String value) {
			int remaining = ERROR_EXAMPLE_LIMIT - example.length();
			if (remaining > 0)
				example.append(value, 0, Math.min(remaining, value.length()));
		}
	}

	static ErrorCategory classifyError(IOException failure, boolean connecting) {
		if (connecting)
			return ErrorCategory.CONNECT_FAILURE;
		if (failure instanceof ResponseValidationException validationException)
			return validationException.category;
		if (failure instanceof SocketTimeoutException)
			return ErrorCategory.READ_TIMEOUT;
		if (failure instanceof EOFException)
			return ErrorCategory.UNEXPECTED_EOF;
		if (failure instanceof SocketException)
			return ErrorCategory.SOCKET_IO;
		return ErrorCategory.OTHER_IO;
	}

	private static final class LatencyList {
		private long[] values;
		private int size;

		private LatencyList(int initialCapacity) {
			values = new long[initialCapacity];
		}

		private void add(long value) {
			if (size == values.length)
				values = Arrays.copyOf(values, values.length * 2);
			values[size++] = value;
		}

		private long[] toArray() {
			return Arrays.copyOf(values, size);
		}
	}

	private static List<Scenario> scenariosProperty() {
		Map<String, Scenario> scenarios = Map.of(
				"streaming", new Scenario("streaming", ascii("""
						GET /streaming HTTP/1.1\r
						Host: 127.0.0.1\r
						Accept: text/plain\r
						Connection: keep-alive\r
						\r
						"""), 200, STREAMING_BODY, 1, STREAMING_BODY.length, 0),
				"streaming-bulk", new Scenario("streaming-bulk", ascii("""
						GET /streaming-bulk HTTP/1.1\r
						Host: 127.0.0.1\r
						Accept: application/octet-stream\r
						Connection: keep-alive\r
						\r
						"""), 200, BULK_BODY, BULK_WRITE_COUNT, BULK_WRITE_SIZE, 0),
				"streaming-paced", new Scenario("streaming-paced", ascii("""
						GET /streaming-paced HTTP/1.1\r
						Host: 127.0.0.1\r
						Accept: application/octet-stream\r
						Connection: keep-alive\r
						\r
						"""), 200, PACED_BODY, PACED_WRITE_COUNT, PACED_WRITE_SIZE, PACED_DELAY_MILLIS),
				"plaintext", new Scenario("plaintext", ascii("""
						GET /plaintext HTTP/1.1\r
						Host: 127.0.0.1\r
						Accept: text/plain\r
						Connection: keep-alive\r
						\r
						"""), 200, STREAMING_BODY, 0, 0, 0),
				"json", new Scenario("json", ascii("""
						GET /json HTTP/1.1\r
						Host: 127.0.0.1\r
						Accept: application/json\r
						Connection: keep-alive\r
						\r
						"""), 200, JSON_BODY, 0, 0, 0),
				"post-json", new Scenario("post-json", ascii("""
						POST /json HTTP/1.1\r
						Host: 127.0.0.1\r
						Accept: application/json\r
						Content-Type: application/json\r
						Content-Length: 35\r
						Connection: keep-alive\r
						\r
						{"name":"widget","quantity":123456}"""), 200, JSON_BODY, 0, 0, 0),
				"output-native", outputScenario(OutputMode.NATIVE),
				"output-scalar", outputScenario(OutputMode.SCALAR),
				"output-mixed", outputScenario(OutputMode.MIXED));

		String value = System.getProperty("soklet.e2e.scenarios", "plaintext,json,post-json");
		List<Scenario> selected = new ArrayList<>();
		for (String token : value.split(",")) {
			Scenario scenario = scenarios.get(token.trim());
			if (scenario != null)
				selected.add(scenario);
		}

		if (selected.isEmpty())
			selected.addAll(scenarios.values().stream().sorted(Comparator.comparing(Scenario::name)).toList());

		return List.copyOf(selected);
	}

	private static int integerProperty(String name, int defaultValue) {
		String value = System.getProperty(name);
		if (value == null || value.isBlank())
			return defaultValue;
		return Integer.parseInt(value);
	}

	private static boolean booleanProperty(String name, boolean defaultValue) {
		String value = System.getProperty(name);
		if (value == null || value.isBlank())
			return defaultValue;
		return Boolean.parseBoolean(value);
	}
}
