package com.soklet.internal.microhttp;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Small dependency-free benchmark for response head serialization.
 * <p>
 * This is intentionally not a JUnit test. Run after {@code mvn test-compile}:
 * <pre>{@code
 * java -cp target/classes:target/test-classes com.soklet.internal.microhttp.MicrohttpResponseHeadBenchmark
 * }</pre>
 */
public final class MicrohttpResponseHeadBenchmark {
	private static final String VERSION = "HTTP/1.1";
	private static final List<Header> CONNECTION_HEADERS = List.of(new Header("Connection", "keep-alive"));
	private static final MicrohttpResponse SMALL_HEADERS_RESPONSE = new MicrohttpResponse(200,
			"OK",
			List.of(
					new Header("Content-Type", "application/json; charset=UTF-8"),
					new Header("Content-Length", "37"),
					new Header("Date", "Mon, 01 Jan 2024 00:00:00 GMT"),
					new Header("Server", "soklet")),
			"{}".getBytes(StandardCharsets.UTF_8));
	private static final MicrohttpResponse LARGE_HEADERS_RESPONSE = new MicrohttpResponse(200,
			"OK",
			largeHeaders(),
			"{}".getBytes(StandardCharsets.UTF_8));
	private static final MicrohttpResponse SET_COOKIE_HEADERS_RESPONSE = new MicrohttpResponse(200,
			"OK",
			setCookieHeaders(),
			"{}".getBytes(StandardCharsets.UTF_8));
	private static final com.sun.management.ThreadMXBean THREAD_MX_BEAN =
			(com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

	private static volatile long blackhole;

	private MicrohttpResponseHeadBenchmark() {
	}

	public static void main(String[] args) throws Exception {
		int warmups = integerProperty("soklet.benchmark.warmups", 5);
		int measurements = integerProperty("soklet.benchmark.measurements", 7);
		int operations = integerProperty("soklet.benchmark.operations", 250_000);

		enableThreadAllocatedBytes();
		System.out.printf(Locale.ROOT,
				"Microhttp response-head benchmark: warmups=%d measurements=%d operations=%d allocationTracking=%s%n",
				warmups, measurements, operations, allocationTrackingAvailable());

		runScenario("smallHeaders", warmups, measurements, operations,
				operationCount -> serializeHead(SMALL_HEADERS_RESPONSE, operationCount));
		runScenario("largeHeaders", warmups, measurements, operations,
				operationCount -> serializeHead(LARGE_HEADERS_RESPONSE, operationCount));
		runScenario("setCookieHeaders", warmups, measurements, operations,
				operationCount -> serializeHead(SET_COOKIE_HEADERS_RESPONSE, operationCount));

		System.out.println("blackhole=" + blackhole);
	}

	private static void runScenario(String name,
																	int warmups,
																	int measurements,
																	int operations,
																	Scenario scenario) throws Exception {
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

	private static void serializeHead(MicrohttpResponse response,
																		int operations) {
		for (int i = 0; i < operations; i++)
			consume(response.serializeHead(VERSION, CONNECTION_HEADERS));
	}

	private static void consume(byte[] bytes) {
		long value = bytes.length;
		value = value * 31L + bytes[0];
		value = value * 31L + bytes[bytes.length - 1];
		blackhole ^= value;
	}

	private static List<Header> largeHeaders() {
		List<Header> headers = new ArrayList<>();
		headers.add(new Header("Content-Type", "application/json; charset=UTF-8"));
		headers.add(new Header("Content-Length", "37"));
		headers.add(new Header("Date", "Mon, 01 Jan 2024 00:00:00 GMT"));
		headers.add(new Header("Server", "soklet"));

		for (int i = 0; i < 28; i++)
			headers.add(new Header("X-Benchmark-Header-" + i, "benchmark-value-" + i));

		return List.copyOf(headers);
	}

	private static List<Header> setCookieHeaders() {
		List<Header> headers = new ArrayList<>();
		headers.add(new Header("Content-Type", "text/plain; charset=UTF-8"));
		headers.add(new Header("Content-Length", "2"));

		for (int i = 0; i < 12; i++)
			headers.add(new Header("Set-Cookie", "cookie" + i + "=value" + i + "; Path=/; HttpOnly; SameSite=Lax"));

		return List.copyOf(headers);
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
