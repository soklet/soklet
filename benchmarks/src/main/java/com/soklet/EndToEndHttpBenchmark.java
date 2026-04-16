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
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
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
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public final class EndToEndHttpBenchmark {
	private static final String HOST = "127.0.0.1";
	private static final byte[] POST_BODY = ascii("{\"name\":\"widget\",\"quantity\":123456}");
	private static final MarshaledResponse PLAINTEXT_RESPONSE = MarshaledResponse.withStatusCode(200)
			.headers(Map.of("Content-Type", Set.of("text/plain; charset=UTF-8")))
			.body(ascii("hello, soklet\n"))
			.build();
	private static final MarshaledResponse JSON_RESPONSE = MarshaledResponse.withStatusCode(200)
			.headers(Map.of("Content-Type", Set.of("application/json; charset=UTF-8")))
			.body(ascii("{\"message\":\"hello\",\"status\":\"ok\"}\n"))
			.build();
	private static final MarshaledResponse BAD_REQUEST_RESPONSE = MarshaledResponse.withStatusCode(400)
			.headers(Map.of("Content-Type", Set.of("text/plain; charset=UTF-8")))
			.body(ascii("bad request\n"))
			.build();

	private EndToEndHttpBenchmark() {
	}

	public static void main(String[] args) throws Exception {
		Config config = Config.fromSystemProperties();
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
				.concurrency(config.serverConcurrency())
				.requestHandlerConcurrency(config.handlerConcurrency())
				.requestHandlerQueueCapacity(config.handlerQueueCapacity());

		SokletConfig sokletConfig = SokletConfig.withHttpServer(serverBuilder.build())
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(BenchmarkResource.class)))
				.instanceProvider(instanceProvider)
				.metricsCollector(config.metricsEnabled() ? MetricsCollector.defaultInstance() : MetricsCollector.disabledInstance())
				.build();

		try (Soklet soklet = Soklet.fromConfig(sokletConfig)) {
			soklet.start();
			Thread.sleep(250L);

			System.out.printf(Locale.ROOT,
					"Soklet end-to-end HTTP benchmark: host=%s port=%d warmup=%ds duration=%ds iterations=%d clients=%d serverConcurrency=%d handlerConcurrency=%d metrics=%s%n",
					HOST,
					port,
					config.warmupSeconds(),
					config.durationSeconds(),
					config.iterations(),
					config.clients(),
					config.serverConcurrency(),
					config.handlerConcurrency(),
					config.metricsEnabled());

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
						runScenario(iteration, scenario, config.clients(), config.warmupSeconds(), false, port);
				}

				for (Scenario scenario : iterationScenarios) {
					ScenarioResult result = runScenario(iteration, scenario, config.clients(), config.durationSeconds(), true, port);
					results.add(result);
					printResult(result);
				}
			}

			List<ScenarioSummary> summaries = summarize(config.scenarios(), results);
			printSummaries(summaries);

			if (config.outputPath() != null)
				writeJson(config, port, results, summaries);
		}
	}

	/**
	 * Resource methods used by the end-to-end benchmark harness.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	public static final class BenchmarkResource {
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
																						int port) throws Exception {
		ExecutorService executorService = Executors.newFixedThreadPool(clients, namedThreadFactory("soklet-e2e-client-"));
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
		long startedAt = System.nanoTime();
		start.countDown();

		List<WorkerResult> workerResults = new ArrayList<>(clients);
		for (Future<WorkerResult> future : futures)
			workerResults.add(future.get());

		long elapsedNanos = System.nanoTime() - startedAt;
		executorService.shutdownNow();

		return ScenarioResult.from(iteration, scenario, clients, elapsedNanos, workerResults);
	}

	private static WorkerResult runWorker(Scenario scenario,
																				int port,
																				long deadlineNanos,
																				boolean recordLatencies) {
		LatencyList latencies = recordLatencies ? new LatencyList(16_384) : null;
		long requests = 0L;
		long errors = 0L;
		Connection connection = null;

		try {
			while (System.nanoTime() < deadlineNanos) {
				try {
					if (connection == null)
						connection = openConnection(port);

					long startedAt = System.nanoTime();
					connection.output().write(scenario.requestBytes());
					connection.output().flush();
					boolean ok = readResponse(connection);
					long elapsed = System.nanoTime() - startedAt;

					if (ok) {
						requests++;
						if (latencies != null)
							latencies.add(elapsed);
					} else {
						errors++;
					}
				} catch (IOException e) {
					errors++;
					close(connection);
					connection = null;
				}
			}
		} finally {
			close(connection);
		}

		return new WorkerResult(requests, errors, latencies == null ? new long[0] : latencies.toArray());
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

	private static boolean readResponse(Connection connection) throws IOException {
		int headerLength = readHeaders(connection.input(), connection.headerBuffer());
		int statusCode = parseStatusCode(connection.headerBuffer(), headerLength);
		int contentLength = parseContentLength(connection.headerBuffer(), headerLength);

		if (contentLength < 0)
			throw new IOException("Missing Content-Length");

		discard(connection.input(), contentLength, connection.bodyBuffer());
		return statusCode == 200;
	}

	private static int readHeaders(BufferedInputStream input, byte[] headerBuffer) throws IOException {
		int length = 0;

		while (true) {
			int value = input.read();
			if (value < 0)
				throw new IOException("Unexpected end of stream");

			if (length == headerBuffer.length)
				throw new IOException("Response headers too large");

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
			throw new IOException("Malformed status line");

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
				throw new IOException("Invalid integer");
			value = value * 10 + (b - '0');
		}
		return value;
	}

	private static void discard(BufferedInputStream input, int byteCount, byte[] buffer) throws IOException {
		int remaining = byteCount;
		while (remaining > 0) {
			int read = input.read(buffer, 0, Math.min(buffer.length, remaining));
			if (read < 0)
				throw new IOException("Unexpected end of response body");
			remaining -= read;
		}
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
				"iter=%02d %-14s requests=%9d errors=%5d throughput=%10.0f req/s latency: avg=%7.0f us p50=%7.0f us p90=%7.0f us p99=%7.0f us max=%7.0f us%n",
				result.iteration(),
				result.scenario().name(),
				result.requests(),
				result.errors(),
				result.throughputRequestsPerSecond(),
				nanosToMicros(result.averageLatencyNanos()),
				nanosToMicros(result.p50LatencyNanos()),
				nanosToMicros(result.p90LatencyNanos()),
				nanosToMicros(result.p99LatencyNanos()),
				nanosToMicros(result.maxLatencyNanos()));
	}

	private static void printSummaries(List<ScenarioSummary> summaries) {
		System.out.println("Summary (median across iterations)");
		for (ScenarioSummary summary : summaries) {
			System.out.printf(Locale.ROOT,
					"median %-14s runs=%2d totalRequests=%9d totalErrors=%5d throughput=%10.0f req/s latency: avg=%7.0f us p50=%7.0f us p90=%7.0f us p99=%7.0f us max=%7.0f us%n",
					summary.scenario().name(),
					summary.runs(),
					summary.totalRequests(),
					summary.totalErrors(),
					summary.medianThroughputRequestsPerSecond(),
					nanosToMicros(summary.medianAverageLatencyNanos()),
					nanosToMicros(summary.medianP50LatencyNanos()),
					nanosToMicros(summary.medianP90LatencyNanos()),
					nanosToMicros(summary.medianP99LatencyNanos()),
					nanosToMicros(summary.maxLatencyNanos()));
		}
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
		json.append("  \"metricsEnabled\": ").append(config.metricsEnabled()).append(",\n");
		json.append("  \"results\": [\n");

		for (int i = 0; i < results.size(); i++) {
			ScenarioResult result = results.get(i);
			json.append("    {\n");
			json.append("      \"iteration\": ").append(result.iteration()).append(",\n");
			json.append("      \"scenario\": \"").append(escape(result.scenario().name())).append("\",\n");
			json.append("      \"requests\": ").append(result.requests()).append(",\n");
			json.append("      \"errors\": ").append(result.errors()).append(",\n");
			json.append("      \"elapsedNanos\": ").append(result.elapsedNanos()).append(",\n");
			json.append("      \"throughputRequestsPerSecond\": ").append(formatDouble(result.throughputRequestsPerSecond())).append(",\n");
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
			json.append("      \"totalErrors\": ").append(summary.totalErrors()).append(",\n");
			json.append("      \"medianThroughputRequestsPerSecond\": ").append(formatDouble(summary.medianThroughputRequestsPerSecond())).append(",\n");
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

	private static String escape(String value) {
		return value.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	private static byte[] ascii(String value) {
		return value.getBytes(StandardCharsets.US_ASCII);
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
			boolean metricsEnabled,
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
					booleanProperty("soklet.e2e.metrics", false),
					scenariosProperty(),
					System.getProperty("soklet.e2e.output", "target/e2e-results.json"));
		}
	}

	private record Scenario(String name, byte[] requestBytes) {
	}

	private record Connection(
			Socket socket,
			BufferedInputStream input,
			BufferedOutputStream output,
			byte[] headerBuffer,
			byte[] bodyBuffer) {
	}

	private record WorkerResult(long requests, long errors, long[] latencies) {
	}

	private record ScenarioResult(
			int iteration,
			Scenario scenario,
			int clients,
			long elapsedNanos,
			long requests,
			long errors,
			long[] latencies) {
		static ScenarioResult from(int iteration, Scenario scenario, int clients, long elapsedNanos, List<WorkerResult> workerResults) {
			long requests = 0L;
			long errors = 0L;
			int latencyCount = 0;

			for (WorkerResult workerResult : workerResults) {
				requests += workerResult.requests();
				errors += workerResult.errors();
				latencyCount += workerResult.latencies().length;
			}

			long[] latencies = new long[latencyCount];
			int offset = 0;
			for (WorkerResult workerResult : workerResults) {
				System.arraycopy(workerResult.latencies(), 0, latencies, offset, workerResult.latencies().length);
				offset += workerResult.latencies().length;
			}
			Arrays.sort(latencies);

			return new ScenarioResult(iteration, scenario, clients, elapsedNanos, requests, errors, latencies);
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
			long maxLatencyNanos) {
		static ScenarioSummary from(Scenario scenario, List<ScenarioResult> results) {
			long totalRequests = 0L;
			long totalErrors = 0L;
			long maxLatencyNanos = 0L;
			double[] throughputs = new double[results.size()];
			double[] averageLatencies = new double[results.size()];
			long[] p50Latencies = new long[results.size()];
			long[] p90Latencies = new long[results.size()];
			long[] p99Latencies = new long[results.size()];

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
					maxLatencyNanos);
		}
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
				"plaintext", new Scenario("plaintext", ascii("""
						GET /plaintext HTTP/1.1\r
						Host: 127.0.0.1\r
						Accept: text/plain\r
						Connection: keep-alive\r
						\r
						""")),
				"json", new Scenario("json", ascii("""
						GET /json HTTP/1.1\r
						Host: 127.0.0.1\r
						Accept: application/json\r
						Connection: keep-alive\r
						\r
						""")),
				"post-json", new Scenario("post-json", ascii("""
						POST /json HTTP/1.1\r
						Host: 127.0.0.1\r
						Accept: application/json\r
						Content-Type: application/json\r
						Content-Length: 35\r
						Connection: keep-alive\r
						\r
						{"name":"widget","quantity":123456}""")));

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
