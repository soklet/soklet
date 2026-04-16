package com.soklet;

import com.soklet.internal.microhttp.Header;
import com.soklet.internal.microhttp.MicrohttpRequest;

import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static java.lang.String.format;

/**
 * Small dependency-free benchmark for the default HTTP server request conversion path.
 * <p>
 * This is intentionally not a JUnit test. Run after {@code mvn test-compile}:
 * <pre>{@code
 * java -cp target/classes:target/test-classes com.soklet.DefaultHttpServerRequestConversionBenchmark
 * }</pre>
 */
public final class DefaultHttpServerRequestConversionBenchmark {
	private static final MicrohttpRequest REQUEST = new MicrohttpRequest(
			"GET",
			"/widgets/123?include=inventory",
			"HTTP/1.1",
			List.of(
					new Header("Host", "localhost"),
					new Header("User-Agent", "soklet-benchmark"),
					new Header("Accept", "application/json, text/plain"),
					new Header("Accept-Encoding", "gzip, br"),
					new Header("Cache-Control", "no-cache, no-store"),
					new Header("Cookie", "a=b; c=d"),
					new Header("Connection", "keep-alive"),
					new Header("X-Request-Id", "benchmark-request"),
					new Header("X-Tenant-Id", "tenant-a")),
			new byte[0],
			false,
			new InetSocketAddress("127.0.0.1", 12345));
	private static final DefaultHttpServer SERVER = (DefaultHttpServer) HttpServer.withPort(0).build();
	private static final com.sun.management.ThreadMXBean THREAD_MX_BEAN =
			(com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

	private static volatile long blackhole;

	private DefaultHttpServerRequestConversionBenchmark() {
	}

	public static void main(String[] args) throws Exception {
		int warmups = integerProperty("soklet.benchmark.warmups", 5);
		int measurements = integerProperty("soklet.benchmark.measurements", 7);
		int operations = integerProperty("soklet.benchmark.operations", 250_000);

		enableThreadAllocatedBytes();
		System.out.printf(Locale.ROOT,
				"Default HTTP request-conversion benchmark: warmups=%d measurements=%d operations=%d allocationTracking=%s%n",
				warmups, measurements, operations, allocationTrackingAvailable());

		runScenario("rawHeaderRoundTrip", warmups, measurements, operations, DefaultHttpServerRequestConversionBenchmark::rawHeaderRoundTrip);
		runScenario("parsedHeaderDirect", warmups, measurements, operations, DefaultHttpServerRequestConversionBenchmark::parsedHeaderDirect);

		System.out.println("blackhole=" + blackhole);
	}

	private static void runScenario(String name, int warmups, int measurements, int operations, Scenario scenario) throws Exception {
		for (int i = 0; i < warmups; i++)
			scenario.run(operations);

		long[] nanosPerOperation = new long[measurements];
		long[] bytesPerOperation = new long[measurements];

		for (int i = 0; i < measurements; i++) {
			System.gc();
			Thread.sleep(50L);

			long allocatedBefore = threadAllocatedBytes();
			long startedAt = System.nanoTime();
			scenario.run(operations);
			long elapsed = System.nanoTime() - startedAt;
			long allocatedAfter = threadAllocatedBytes();

			nanosPerOperation[i] = elapsed / operations;
			bytesPerOperation[i] = allocatedBefore >= 0L && allocatedAfter >= 0L
					? (allocatedAfter - allocatedBefore) / operations
					: -1L;
		}

		Arrays.sort(nanosPerOperation);
		Arrays.sort(bytesPerOperation);

		long medianNs = nanosPerOperation[nanosPerOperation.length / 2];
		long medianBytes = bytesPerOperation[bytesPerOperation.length / 2];
		double operationsPerSecond = 1_000_000_000D / Math.max(1D, medianNs);

		System.out.printf(Locale.ROOT,
				"%-20s median=%8d ns/op throughput=%10.0f ops/s allocated=%6s bytes/op%n",
				name, medianNs, operationsPerSecond, medianBytes < 0L ? "n/a" : Long.toString(medianBytes));
	}

	private static void rawHeaderRoundTrip(int operations) {
		for (int i = 0; i < operations; i++) {
			List<String> rawHeaderLines = REQUEST.headers().stream()
					.map(header -> format("%s: %s", header.name(), header.value() == null ? "" : header.value()))
					.collect(Collectors.toList());
			consume(Utilities.extractHeadersFromRawHeaderLines(rawHeaderLines));
		}
	}

	private static void parsedHeaderDirect(int operations) {
		for (int i = 0; i < operations; i++)
			consume(SERVER.headersFromMicrohttpRequest(REQUEST));
	}

	private static void consume(Map<String, Set<String>> headers) {
		long value = headers.size();
		for (Map.Entry<String, Set<String>> entry : headers.entrySet()) {
			value = value * 31L + entry.getKey().length();
			value = value * 31L + entry.getValue().size();
		}
		blackhole ^= value;
	}

	private static int integerProperty(String name, int defaultValue) {
		String value = System.getProperty(name);
		if (value == null || value.isBlank())
			return defaultValue;
		return Integer.parseInt(value);
	}

	private static void enableThreadAllocatedBytes() {
		if (THREAD_MX_BEAN.isThreadAllocatedMemorySupported() && !THREAD_MX_BEAN.isThreadAllocatedMemoryEnabled())
			THREAD_MX_BEAN.setThreadAllocatedMemoryEnabled(true);
	}

	private static boolean allocationTrackingAvailable() {
		return THREAD_MX_BEAN.isThreadAllocatedMemorySupported() && THREAD_MX_BEAN.isThreadAllocatedMemoryEnabled();
	}

	private static long threadAllocatedBytes() {
		if (!allocationTrackingAvailable())
			return -1L;
		return THREAD_MX_BEAN.getThreadAllocatedBytes(Thread.currentThread().getId());
	}

	@FunctionalInterface
	private interface Scenario {
		void run(int operations) throws Exception;
	}
}
