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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Cold-start and memory-footprint benchmark for a minimal Soklet HTTP application.
 * <p>
 * Each iteration forks a <em>fresh</em> JVM (no warm JIT or class-cache carryover) that starts a
 * one-route Soklet server, serves a real loopback request, and reports:
 * <ul>
 * <li>{@code startedMillis} — JVM start to {@code Soklet#start()} returning</li>
 * <li>{@code firstResponseMillis} — JVM start to the first HTTP response fully read off a real socket</li>
 * <li>{@code usedHeapBytes} — used heap after startup, post-GC (approximate by nature)</li>
 * <li>{@code rssBytes} — median resident set size sampled from the OS via {@code ps} over the settle window
 * (macOS/Linux; {@code -1} elsewhere)</li>
 * <li>{@code threadCount} — live JVM threads at rest</li>
 * </ul>
 * Run after {@code mvn -q clean package} in {@code benchmarks/}:
 * <pre>{@code
 * java -cp target/soklet-benchmarks.jar com.soklet.StartupAndMemoryBenchmark
 * }</pre>
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public final class StartupAndMemoryBenchmark {
	private static final String HOST = "127.0.0.1";
	private static final String CHILD_FLAG = "--child";
	private static final long CHILD_DEADLINE_SECONDS = 60L;
	private static final String READY_MARKER = "SOKLET_STARTUP_READY ";
	private static final String FINAL_MARKER = "SOKLET_STARTUP_FINAL ";

	private StartupAndMemoryBenchmark() {
	}

	public static void main(String[] args) throws Exception {
		for (String arg : args) {
			if (CHILD_FLAG.equals(arg)) {
				runChild();
				return;
			}
		}

		runParent();
	}

	// *** Parent: forks a fresh JVM per iteration and aggregates results ***

	private static void runParent() throws Exception {
		Config config = Config.fromSystemProperties();

		System.out.printf(Locale.ROOT,
				"Soklet startup/memory benchmark: iterations=%d settleMillis=%d metrics=%s childJvmArgs=%s%n",
				config.iterations(), config.settleMillis(), config.metricsEnabled(),
				config.childJvmArgs().isEmpty() ? "(none)" : String.join(" ", config.childJvmArgs()));
		System.out.printf(Locale.ROOT, "JVM: %s %s; OS: %s %s (%s)%n",
				System.getProperty("java.vendor"), System.getProperty("java.version"),
				System.getProperty("os.name"), System.getProperty("os.version"), System.getProperty("os.arch"));

		List<IterationResult> results = new ArrayList<>(config.iterations());

		for (int iteration = 1; iteration <= config.iterations(); iteration++) {
			IterationResult result = runChildIteration(config);
			results.add(result);
			System.out.printf(Locale.ROOT,
					"Iteration %d/%d: started=%dms firstResponse=%dms usedHeap=%s rss=%s threads=%d%n",
					iteration, config.iterations(),
					result.startedMillis(), result.firstResponseMillis(),
					formatBytes(result.usedHeapBytes()), formatBytes(result.rssBytes()), result.threadCount());
		}

		Summary started = Summary.fromValues(results.stream().mapToLong(IterationResult::startedMillis).toArray());
		Summary firstResponse = Summary.fromValues(results.stream().mapToLong(IterationResult::firstResponseMillis).toArray());
		Summary usedHeap = Summary.fromValues(results.stream().mapToLong(IterationResult::usedHeapBytes).toArray());
		Summary rss = Summary.fromValues(results.stream().mapToLong(IterationResult::rssBytes).filter(value -> value >= 0).toArray());
		Summary threads = Summary.fromValues(results.stream().mapToLong(IterationResult::threadCount).toArray());

		System.out.printf(Locale.ROOT, "%nSummary over %d cold-JVM iterations (mean ± stddev [min..max]):%n", results.size());
		System.out.printf(Locale.ROOT, "  JVM start -> Soklet started:        %s ms%n", started.describeMillis());
		System.out.printf(Locale.ROOT, "  JVM start -> first response served: %s ms%n", firstResponse.describeMillis());
		System.out.printf(Locale.ROOT, "  Used heap after startup (post-GC):  %s%n", usedHeap.describeBytes());
		System.out.printf(Locale.ROOT, "  Resident set size (RSS):            %s%n",
				rss.count() == 0 ? "(unavailable on this platform)" : rss.describeBytes());
		System.out.printf(Locale.ROOT, "  Live threads at rest:               %s%n", threads.describeCount());

		writeJson(config, results, started, firstResponse, usedHeap, rss, threads);
		System.out.printf(Locale.ROOT, "%nWrote %s%n", config.outputPath());
	}

	private static IterationResult runChildIteration(Config config) throws Exception {
		List<String> command = new ArrayList<>();
		command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
		command.addAll(config.childJvmArgs());
		if (config.metricsEnabled())
			command.add("-Dsoklet.startup.metrics=true");
		command.add("-cp");
		command.add(System.getProperty("java.class.path"));
		command.add(StartupAndMemoryBenchmark.class.getName());
		command.add(CHILD_FLAG);

		// Merge stderr into stdout: the parent reads a single stream, so a chatty or crashing child
		// can never fill an unread stderr pipe and deadlock (non-marker lines pass through as [child])
		Process child = new ProcessBuilder(command)
				.redirectErrorStream(true)
				.start();

		// Watchdog: a child that hangs before printing its markers would otherwise block readLine()
		// forever; destroying it closes stdout so awaitMarker fails with a clear exception instead.
		Thread watchdog = new Thread(() -> {
			try {
				if (!child.waitFor(CHILD_DEADLINE_SECONDS, TimeUnit.SECONDS) && child.isAlive()) {
					System.err.println("Benchmark child JVM exceeded " + CHILD_DEADLINE_SECONDS + "s deadline; destroying it");
					child.destroyForcibly();
				}
			} catch (InterruptedException e) {
				// Normal cancellation path once the iteration completes
			}
		}, "startup-benchmark-watchdog");
		watchdog.setDaemon(true);
		watchdog.start();

		try (BufferedReader stdout = new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
			ChildReport ready = awaitMarker(stdout, READY_MARKER, child);

			// Child is up and has served a request; sample RSS from the OS while it idles
			long rssBytes = sampleRssBytes(child.pid(), config.settleMillis());

			// Ask the child to take its post-GC measurements and exit. A child that already died
			// makes this a broken pipe; swallow it so awaitMarker below reports the real failure.
			try {
				OutputStream childStdin = child.getOutputStream();
				childStdin.write('\n');
				childStdin.flush();
			} catch (IOException ignored) {
				// Diagnosed by the FINAL-marker wait below
			}

			ChildReport finalReport = awaitMarker(stdout, FINAL_MARKER, child);

			if (!child.waitFor(30, TimeUnit.SECONDS)) {
				child.destroyForcibly();
				throw new IllegalStateException("Benchmark child JVM did not exit");
			}

			if (child.exitValue() != 0)
				throw new IllegalStateException("Benchmark child JVM exited with status " + child.exitValue());

			return new IterationResult(
					ready.longValue("startedMillis"),
					ready.longValue("firstResponseMillis"),
					finalReport.longValue("usedHeapBytes"),
					rssBytes,
					(int) finalReport.longValue("threadCount"));
		} finally {
			watchdog.interrupt();

			if (child.isAlive())
				child.destroyForcibly();
		}
	}

	private static ChildReport awaitMarker(BufferedReader stdout, String marker, Process child) throws IOException {
		String line;

		while ((line = stdout.readLine()) != null) {
			if (line.startsWith(marker))
				return ChildReport.fromLine(line.substring(marker.length()));
			// Pass through any other child output for debuggability
			System.out.println("[child] " + line);
		}

		throw new IllegalStateException("Benchmark child JVM ended (exit="
				+ (child.isAlive() ? "running" : String.valueOf(child.exitValue())) + ") before printing " + marker.trim());
	}

	/**
	 * Samples the child's resident set size via {@code ps} over the settle window, returning the
	 * <em>median</em> observed value in bytes (robust against a transient GC spike mid-window), or
	 * {@code -1} when unavailable (e.g. Windows).
	 */
	private static long sampleRssBytes(long pid, long settleMillis) {
		String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

		if (osName.contains("win"))
			return -1L;

		List<Long> samples = new ArrayList<>(16);
		long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(settleMillis);

		do {
			long rssBytes = readRssBytesViaPs(pid);

			if (rssBytes >= 0)
				samples.add(rssBytes);

			try {
				Thread.sleep(100L);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
		} while (System.nanoTime() < deadlineNanos);

		if (samples.isEmpty())
			return -1L;

		samples.sort(null);
		return samples.get(samples.size() / 2);
	}

	private static long readRssBytesViaPs(long pid) {
		try {
			Process ps = new ProcessBuilder("ps", "-o", "rss=", "-p", Long.toString(pid))
					.redirectErrorStream(true)
					.start();

			try (BufferedReader reader = new BufferedReader(new InputStreamReader(ps.getInputStream(), StandardCharsets.UTF_8))) {
				String line = reader.readLine();

				if (!ps.waitFor(5, TimeUnit.SECONDS)) {
					ps.destroyForcibly();
					return -1L;
				}

				if (line == null)
					return -1L;

				// ps reports RSS in kilobytes
				return Long.parseLong(line.trim()) * 1_024L;
			}
		} catch (Exception e) {
			return -1L;
		}
	}

	// *** Child: a minimal one-route Soklet app that reports its own startup timings ***

	private static void runChild() throws Exception {
		int port = findFreePort();
		boolean metricsEnabled = Boolean.getBoolean("soklet.startup.metrics");

		SokletConfig sokletConfig = SokletConfig.withHttpServer(HttpServer.withPort(port).host(HOST).build())
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(PingResource.class)))
				.metricsCollector(metricsEnabled ? MetricsCollector.defaultInstance() : MetricsCollector.disabledInstance())
				// Keep child stdout clean for the parent's marker protocol
				.lifecycleObserver(new LifecycleObserver() {
					@Override
					public void didReceiveLogEvent(LogEvent logEvent) { /* no-op */ }
				})
				.build();

		try (Soklet soklet = Soklet.fromConfig(sokletConfig)) {
			soklet.start();
			long startedMillis = ManagementFactory.getRuntimeMXBean().getUptime();

			probeFirstResponse(port);
			long firstResponseMillis = ManagementFactory.getRuntimeMXBean().getUptime();

			System.out.println(READY_MARKER + "startedMillis=" + startedMillis
					+ " firstResponseMillis=" + firstResponseMillis
					+ " port=" + port);
			System.out.flush();

			// Idle until the parent (which is sampling our RSS) tells us to measure and exit
			int read = System.in.read();

			if (read == -1 && System.getenv("SOKLET_STARTUP_DEBUG") != null)
				System.out.println("stdin closed before signal; proceeding to final measurements");

			System.gc();
			Thread.sleep(100L);
			System.gc();
			Thread.sleep(100L);

			Runtime runtime = Runtime.getRuntime();
			long usedHeapBytes = runtime.totalMemory() - runtime.freeMemory();
			int threadCount = ManagementFactory.getThreadMXBean().getThreadCount();

			System.out.println(FINAL_MARKER + "usedHeapBytes=" + usedHeapBytes
					+ " threadCount=" + threadCount);
			System.out.flush();
		}
	}

	private static void probeFirstResponse(int port) throws IOException {
		try (Socket socket = new Socket(HOST, port);
				 OutputStream out = socket.getOutputStream();
				 InputStream in = socket.getInputStream()) {
			socket.setSoTimeout(10_000);
			out.write(("GET /ping HTTP/1.1\r\nHost: " + HOST + "\r\nConnection: close\r\n\r\n")
					.getBytes(StandardCharsets.US_ASCII));
			out.flush();

			// Drain the full response; Connection: close makes EOF the terminator
			byte[] buffer = new byte[4_096];
			long totalBytes = 0;
			int bytesRead;
			String responseHead = null;

			while ((bytesRead = in.read(buffer)) != -1) {
				if (responseHead == null)
					responseHead = new String(buffer, 0, Math.min(bytesRead, 15), StandardCharsets.US_ASCII);

				totalBytes += bytesRead;
			}

			if (totalBytes == 0)
				throw new IOException("No response bytes read from freshly-started server");

			if (responseHead == null || !responseHead.startsWith("HTTP/1.1 200"))
				throw new IOException("Unexpected response from freshly-started server: " + responseHead);
		}
	}

	public static class PingResource {
		@GET("/ping")
		public String ping() {
			return "pong";
		}
	}

	private static int findFreePort() throws IOException {
		try (ServerSocket serverSocket = new ServerSocket(0)) {
			return serverSocket.getLocalPort();
		}
	}

	// *** Reporting ***

	private record IterationResult(
			long startedMillis,
			long firstResponseMillis,
			long usedHeapBytes,
			long rssBytes,
			int threadCount
	) {}

	private record ChildReport(Map<String, Long> values) {
		static ChildReport fromLine(String line) {
			Map<String, Long> values = new java.util.LinkedHashMap<>();

			for (String pair : line.trim().split(" ")) {
				int equalsIndex = pair.indexOf('=');

				if (equalsIndex > 0)
					values.put(pair.substring(0, equalsIndex), Long.parseLong(pair.substring(equalsIndex + 1)));
			}

			return new ChildReport(values);
		}

		long longValue(String name) {
			Long value = values().get(name);

			if (value == null)
				throw new IllegalStateException("Benchmark child JVM did not report '" + name + "'");

			return value;
		}
	}

	private record Summary(int count, double mean, double stddev, long min, long max) {
		static Summary fromValues(long[] values) {
			if (values.length == 0)
				return new Summary(0, 0, 0, 0, 0);

			long min = Long.MAX_VALUE;
			long max = Long.MIN_VALUE;
			double sum = 0;

			for (long value : values) {
				min = Math.min(min, value);
				max = Math.max(max, value);
				sum += value;
			}

			double mean = sum / values.length;
			double sumOfSquaredDeltas = 0;

			for (long value : values)
				sumOfSquaredDeltas += (value - mean) * (value - mean);

			// Sample standard deviation
			double stddev = values.length < 2 ? 0 : Math.sqrt(sumOfSquaredDeltas / (values.length - 1));

			return new Summary(values.length, mean, stddev, min, max);
		}

		String describeMillis() {
			return String.format(Locale.ROOT, "%.0f ± %.0f [%d..%d]", mean(), stddev(), min(), max());
		}

		String describeBytes() {
			return String.format(Locale.ROOT, "%s ± %s [%s..%s]",
					formatBytes(Math.round(mean())), formatBytes(Math.round(stddev())), formatBytes(min()), formatBytes(max()));
		}

		String describeCount() {
			return String.format(Locale.ROOT, "%.1f ± %.1f [%d..%d]", mean(), stddev(), min(), max());
		}
	}

	private static String formatBytes(long bytes) {
		if (bytes < 0)
			return "n/a";
		if (bytes < 1_024L)
			return bytes + " B";
		if (bytes < 1_024L * 1_024L)
			return String.format(Locale.ROOT, "%.1f KB", bytes / 1_024.0);
		return String.format(Locale.ROOT, "%.1f MB", bytes / (1_024.0 * 1_024.0));
	}

	private static void writeJson(Config config,
																List<IterationResult> results,
																Summary started,
																Summary firstResponse,
																Summary usedHeap,
																Summary rss,
																Summary threads) throws IOException {
		StringBuilder json = new StringBuilder(2_048);
		json.append("{\n");
		json.append("  \"timestamp\": \"").append(jsonEscape(Instant.now().toString())).append("\",\n");
		json.append("  \"jvm\": \"").append(jsonEscape(System.getProperty("java.vendor") + " " + System.getProperty("java.version"))).append("\",\n");
		json.append("  \"os\": \"").append(jsonEscape(System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch"))).append("\",\n");
		json.append("  \"config\": {\n");
		json.append("    \"iterations\": ").append(config.iterations()).append(",\n");
		json.append("    \"settleMillis\": ").append(config.settleMillis()).append(",\n");
		json.append("    \"metrics\": ").append(config.metricsEnabled()).append(",\n");
		json.append("    \"childJvmArgs\": \"").append(jsonEscape(String.join(" ", config.childJvmArgs()))).append("\"\n");
		json.append("  },\n");
		json.append("  \"iterations\": [\n");

		for (int i = 0; i < results.size(); i++) {
			IterationResult result = results.get(i);
			json.append("    {\"startedMillis\": ").append(result.startedMillis())
					.append(", \"firstResponseMillis\": ").append(result.firstResponseMillis())
					.append(", \"usedHeapBytes\": ").append(result.usedHeapBytes())
					.append(", \"rssBytes\": ").append(result.rssBytes())
					.append(", \"threadCount\": ").append(result.threadCount())
					.append(i < results.size() - 1 ? "},\n" : "}\n");
		}

		json.append("  ],\n");
		json.append("  \"summary\": {\n");
		appendSummaryJson(json, "startedMillis", started, ",\n");
		appendSummaryJson(json, "firstResponseMillis", firstResponse, ",\n");
		appendSummaryJson(json, "usedHeapBytes", usedHeap, ",\n");
		appendSummaryJson(json, "rssBytes", rss, ",\n");
		appendSummaryJson(json, "threadCount", threads, "\n");
		json.append("  }\n");
		json.append("}\n");

		Path outputPath = Path.of(config.outputPath());

		if (outputPath.getParent() != null)
			Files.createDirectories(outputPath.getParent());

		Files.writeString(outputPath, json.toString(), StandardCharsets.UTF_8);
	}

	private static void appendSummaryJson(StringBuilder json, String name, Summary summary, String suffix) {
		json.append("    \"").append(name).append("\": {\"count\": ").append(summary.count())
				.append(", \"mean\": ").append(String.format(Locale.ROOT, "%.2f", summary.mean()))
				.append(", \"stddev\": ").append(String.format(Locale.ROOT, "%.2f", summary.stddev()))
				.append(", \"min\": ").append(summary.min())
				.append(", \"max\": ").append(summary.max())
				.append("}").append(suffix);
	}

	private static String jsonEscape(String value) {
		StringBuilder escaped = new StringBuilder(value.length() + 8);

		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);

			switch (c) {
				case '\\' -> escaped.append("\\\\");
				case '"' -> escaped.append("\\\"");
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

	private record Config(
			int iterations,
			long settleMillis,
			boolean metricsEnabled,
			List<String> childJvmArgs,
			String outputPath
	) {
		static Config fromSystemProperties() {
			int iterations = Integer.getInteger("soklet.startup.iterations", 5);
			long settleMillis = Long.getLong("soklet.startup.settleMillis", 1_000L);
			boolean metricsEnabled = Boolean.getBoolean("soklet.startup.metrics");
			String rawChildJvmArgs = System.getProperty("soklet.startup.childJvmArgs", "").trim();
			List<String> childJvmArgs = rawChildJvmArgs.isEmpty() ? List.of() : List.of(rawChildJvmArgs.split("\\s+"));
			String outputPath = System.getProperty("soklet.startup.output", "target/startup-results.json");

			if (iterations < 1)
				throw new IllegalArgumentException("soklet.startup.iterations must be at least 1");

			return new Config(iterations, settleMillis, metricsEnabled, childJvmArgs, outputPath);
		}
	}
}
