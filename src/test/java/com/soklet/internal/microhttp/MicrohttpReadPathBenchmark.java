package com.soklet.internal.microhttp;

import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

/**
 * Small dependency-free benchmark for the microhttp parser/tokenizer read path.
 * <p>
 * This is intentionally not a JUnit test. Run after {@code mvn test-compile}:
 * <pre>{@code
 * java -cp target/classes:target/test-classes com.soklet.internal.microhttp.MicrohttpReadPathBenchmark
 * }</pre>
 */
public final class MicrohttpReadPathBenchmark {
	private static final InetSocketAddress REMOTE_ADDRESS = new InetSocketAddress("127.0.0.1", 12345);
	private static final int MAX_REQUEST_SIZE = 1024 * 1024;
	private static final byte[] GET_REQUEST = ascii("""
			GET /widgets/123?include=inventory HTTP/1.1\r
			Host: localhost\r
			User-Agent: soklet-benchmark\r
			Accept: application/json\r
			Connection: keep-alive\r
			X-Request-Id: benchmark-request\r
			X-Tenant-Id: tenant-a\r
			X-Feature: fast-path\r
			\r
			""");
	private static final byte[] POST_REQUEST = ascii("""
			POST /widgets HTTP/1.1\r
			Host: localhost\r
			User-Agent: soklet-benchmark\r
			Accept: application/json\r
			Content-Type: application/json\r
			Content-Length: 37\r
			Connection: keep-alive\r
			X-Request-Id: benchmark-request\r
			X-Tenant-Id: tenant-a\r
			\r
			{"name":"widget","quantity":123456}\r
			""");
	private static final byte[] PIPELINED_GET_REQUESTS = repeat(GET_REQUEST, 16);
	private static final com.sun.management.ThreadMXBean THREAD_MX_BEAN =
			(com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

	private static volatile long blackhole;

	private MicrohttpReadPathBenchmark() {
	}

	public static void main(String[] args) throws Exception {
		int warmups = integerProperty("soklet.benchmark.warmups", 5);
		int measurements = integerProperty("soklet.benchmark.measurements", 7);
		int operations = integerProperty("soklet.benchmark.operations", 250_000);

		enableThreadAllocatedBytes();
		System.out.printf(Locale.ROOT,
				"Microhttp read-path benchmark: warmups=%d measurements=%d operations=%d allocationTracking=%s%n",
				warmups, measurements, operations, allocationTrackingAvailable());

		runScenario("keepAliveGetNewParser", warmups, measurements, operations, MicrohttpReadPathBenchmark::keepAliveGetNewParser);
		runScenario("keepAliveGetReuse", warmups, measurements, operations, MicrohttpReadPathBenchmark::keepAliveGetReuse);
		runScenario("keepAlivePostJsonReuse", warmups, measurements, operations, MicrohttpReadPathBenchmark::keepAlivePostJsonReuse);
		runScenario("pipelinedGet16Reuse", warmups, measurements, operations, MicrohttpReadPathBenchmark::pipelinedGet16Reuse);

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

	private static void keepAliveGetNewParser(int operations) {
		parseOneAtATimeWithNewParser(GET_REQUEST, operations);
	}

	private static void keepAliveGetReuse(int operations) {
		parseOneAtATimeWithParserReuse(GET_REQUEST, operations);
	}

	private static void keepAlivePostJsonReuse(int operations) {
		parseOneAtATimeWithParserReuse(POST_REQUEST, operations);
	}

	private static void pipelinedGet16Reuse(int operations) {
		ByteTokenizer tokenizer = new ByteTokenizer();
		ByteBuffer buffer = ByteBuffer.wrap(PIPELINED_GET_REQUESTS);
		RequestParser parser = new RequestParser(tokenizer, REMOTE_ADDRESS, MAX_REQUEST_SIZE);
		int completed = 0;

		while (completed < operations) {
			reset(buffer);
			tokenizer.add(buffer);

			for (int i = 0; i < 16 && completed < operations; i++) {
				if (!parser.parse())
					throw new IllegalStateException("Unable to parse benchmark request");

				consume(parser.request());
				tokenizer.compact();
				parser.reset();
				completed++;
			}
		}
	}

	private static void parseOneAtATimeWithNewParser(byte[] requestBytes, int operations) {
		ByteTokenizer tokenizer = new ByteTokenizer();
		ByteBuffer buffer = ByteBuffer.wrap(requestBytes);

		for (int i = 0; i < operations; i++) {
			reset(buffer);
			tokenizer.add(buffer);
			RequestParser parser = new RequestParser(tokenizer, REMOTE_ADDRESS, MAX_REQUEST_SIZE);
			if (!parser.parse())
				throw new IllegalStateException("Unable to parse benchmark request");

			consume(parser.request());
			tokenizer.compact();
		}
	}

	private static void parseOneAtATimeWithParserReuse(byte[] requestBytes, int operations) {
		ByteTokenizer tokenizer = new ByteTokenizer();
		ByteBuffer buffer = ByteBuffer.wrap(requestBytes);
		RequestParser parser = new RequestParser(tokenizer, REMOTE_ADDRESS, MAX_REQUEST_SIZE);

		for (int i = 0; i < operations; i++) {
			reset(buffer);
			tokenizer.add(buffer);
			if (!parser.parse())
				throw new IllegalStateException("Unable to parse benchmark request");

			consume(parser.request());
			tokenizer.compact();
			parser.reset();
		}
	}

	private static void consume(MicrohttpRequest request) {
		long value = request.method().length();
		value = value * 31L + request.uri().length();
		value = value * 31L + request.version().length();
		value = value * 31L + request.headers().size();
		value = value * 31L + request.body().length;
		blackhole ^= value;
	}

	private static void reset(ByteBuffer buffer) {
		buffer.position(0);
		buffer.limit(buffer.capacity());
	}

	private static byte[] ascii(String value) {
		return value.getBytes(StandardCharsets.US_ASCII);
	}

	private static byte[] repeat(byte[] bytes, int count) {
		byte[] repeated = new byte[bytes.length * count];
		for (int i = 0; i < count; i++)
			System.arraycopy(bytes, 0, repeated, i * bytes.length, bytes.length);
		return repeated;
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
