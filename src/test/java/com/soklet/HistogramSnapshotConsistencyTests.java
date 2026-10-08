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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Public histogram invariants, including controlled record/reset races. */
@Timeout(value = 15, unit = TimeUnit.SECONDS)
class HistogramSnapshotConsistencyTests {
	@Test
	void snapshotDuringAnUnfinishedRecordHasTheSameCountAsItsFinalBucket()
			throws Exception {
		MetricsCollector.Histogram histogram = new MetricsCollector.Histogram(new long[]{10});
		Pause pause = new Pause();
		installBucket(histogram, 0, new PausingIncrementAdder(pause));
		FutureTask<Void> record = runWorker(() -> histogram.record(7));
		try {
			pause.awaitEntry();
			MetricsCollector.HistogramSnapshot snapshot = histogram.snapshot();
			Assertions.assertEquals(0, snapshot.getBucketCumulativeCount(snapshot.getBucketCount() - 1));
			assertCountMatchesFinalBucket(snapshot);
		} finally {
			pause.release();
			record.get(3, TimeUnit.SECONDS);
		}
		MetricsCollector.HistogramSnapshot completed = histogram.snapshot();
		assertCountMatchesFinalBucket(completed);
		Assertions.assertEquals(1, completed.getCount());
		Assertions.assertEquals(7, completed.getSum());
	}

	@Test
	void aRecordOverlappingResetCannotLeavePermanentCountSkew() throws Exception {
		MetricsCollector.Histogram histogram = new MetricsCollector.Histogram(new long[]{10});
		Pause pause = new Pause();
		installBucket(histogram, 0, new PausingResetAdder(pause));
		histogram.record(7);
		FutureTask<Void> reset = runWorker(histogram::reset);
		try {
			pause.awaitEntry();
			// This record overlaps reset and can be discarded by the bucket reset.
			// Once both calls return, sample count must still describe the buckets.
			histogram.record(5);
		} finally {
			pause.release();
			reset.get(3, TimeUnit.SECONDS);
		}
		assertCountMatchesFinalBucket(histogram.snapshot());
		histogram.record(3);
		MetricsCollector.HistogramSnapshot after = histogram.snapshot();
		assertCountMatchesFinalBucket(after);
		Assertions.assertEquals(1, after.getCount());
	}

	@Test
	void inclusiveBoundariesOverflowAndNegativeSamplesRetainTheirMeaning() {
		long[] boundaries = {10, 1};
		MetricsCollector.Histogram histogram = new MetricsCollector.Histogram(boundaries);
		boundaries[0] = 99;
		for (long value : new long[]{-1, 0, 1, 2, 10, 11})
			histogram.record(value);

		MetricsCollector.HistogramSnapshot snapshot = histogram.snapshot();
		Assertions.assertEquals(3, snapshot.getBucketCount());
		Assertions.assertEquals(1, snapshot.getBucketBoundary(0));
		Assertions.assertEquals(10, snapshot.getBucketBoundary(1));
		Assertions.assertEquals(Long.MAX_VALUE, snapshot.getBucketBoundary(2));
		Assertions.assertEquals(2, snapshot.getBucketCumulativeCount(0));
		Assertions.assertEquals(4, snapshot.getBucketCumulativeCount(1));
		Assertions.assertEquals(5, snapshot.getBucketCumulativeCount(2));
		Assertions.assertEquals(5, snapshot.getCount());
		Assertions.assertEquals(24, snapshot.getSum());
		Assertions.assertEquals(0, snapshot.getMin());
		Assertions.assertEquals(11, snapshot.getMax());
		assertCountMatchesFinalBucket(snapshot);
	}

	@Test
	void quiescentResetClearsTheNewWindowWithoutChangingEarlierSnapshots() {
		MetricsCollector.Histogram histogram = new MetricsCollector.Histogram(new long[]{10});
		histogram.record(7);
		MetricsCollector.HistogramSnapshot before = histogram.snapshot();
		histogram.reset();
		MetricsCollector.HistogramSnapshot empty = histogram.snapshot();
		assertCountMatchesFinalBucket(empty);
		Assertions.assertEquals(0, empty.getCount());
		Assertions.assertEquals(0, empty.getSum());
		Assertions.assertEquals(0, empty.getMin());
		Assertions.assertEquals(0, empty.getMax());
		histogram.record(12);
		MetricsCollector.HistogramSnapshot after = histogram.snapshot();
		assertCountMatchesFinalBucket(after);
		Assertions.assertEquals(1, after.getCount());
		Assertions.assertEquals(12, after.getSum());
		Assertions.assertEquals(12, after.getMin());
		Assertions.assertEquals(12, after.getMax());
		Assertions.assertEquals(1, before.getCount());
		Assertions.assertEquals(1, before.getBucketCumulativeCount(0));
		Assertions.assertEquals(7, before.getSum());
	}

	@Test
	void histogramWithoutFiniteBoundariesStillCountsAllNonnegativeValues() {
		MetricsCollector.Histogram histogram = new MetricsCollector.Histogram(new long[0]);
		assertCountMatchesFinalBucket(histogram.snapshot());
		for (long value : new long[]{-1, 0, 1, Long.MAX_VALUE})
			histogram.record(value);
		MetricsCollector.HistogramSnapshot snapshot = histogram.snapshot();
		Assertions.assertEquals(1, snapshot.getBucketCount());
		Assertions.assertEquals(3, snapshot.getCount());
		Assertions.assertEquals(Long.MAX_VALUE, snapshot.getSum());
		assertCountMatchesFinalBucket(snapshot);
	}

	@Test
	void maximumValueSampleRetainsItsActualMinimum() {
		MetricsCollector.Histogram histogram = new MetricsCollector.Histogram(new long[]{10});
		histogram.record(Long.MAX_VALUE);
		MetricsCollector.HistogramSnapshot snapshot = histogram.snapshot();
		assertCountMatchesFinalBucket(snapshot);
		Assertions.assertEquals(1, snapshot.getCount());
		Assertions.assertEquals(Long.MAX_VALUE, snapshot.getMin());
		Assertions.assertEquals(Long.MAX_VALUE, snapshot.getMax());
		Assertions.assertEquals(Long.MAX_VALUE, snapshot.getSum());
		histogram.reset();
		Assertions.assertEquals(0, histogram.snapshot().getMin());
		Assertions.assertEquals(Long.MAX_VALUE, snapshot.getMin());
	}

	@Test
	void countAndCumulativeBucketsRemainConsistentDuringConcurrentUpdates()
			throws Exception {
		MetricsCollector.Histogram histogram = new MetricsCollector.Histogram(new long[]{0, 1, 5, 10});
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService workers = Executors.newFixedThreadPool(2);
		try {
			Future<?> records = workers.submit(() -> {
				await(start);
				for (int index = 0; index < 1_000; index++)
					histogram.record(index % 13);
			});
			Future<?> resets = workers.submit(() -> {
				await(start);
				for (int index = 0; index < 128; index++)
					histogram.reset();
			});
			start.countDown();
			for (int index = 0; index < 1_000; index++)
				assertCountMatchesFinalBucket(histogram.snapshot());
			records.get(3, TimeUnit.SECONDS);
			resets.get(3, TimeUnit.SECONDS);
			assertCountMatchesFinalBucket(histogram.snapshot());
		} finally {
			start.countDown();
			workers.shutdownNow();
			Assertions.assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS));
		}
	}

	@Test
	void httpAndMcpExportsRetainCountAndInfinityEqualityAcrossReset() {
		DefaultMetricsCollector collector = DefaultMetricsCollector.defaultInstance();
		Request request = Request.fromPath(HttpMethod.GET, "/histogram-test");
		collector.didStartRequestHandling(ServerType.HTTP, request, null);
		collector.didFinishRequestHandling(ServerType.HTTP, request, null,
				MarshaledResponse.fromStatusCode(200), Duration.ofMillis(5), List.of());
		for (int window = 0; window < 2; window++) {
			// MCP resets retire optional families until another event is observed.
			collector.didRecordMcpMetricsEvent(McpMetricsEvent.requestStarted("/mcp", "tools/call"));
			collector.didRecordMcpMetricsEvent(McpMetricsEvent.requestFinished("/mcp", "tools/call",
					McpRequestOutcome.COMPLETE, Duration.ofMillis(7)));
			for (MetricsCollector.MetricsFormat format : MetricsCollector.MetricsFormat.values()) {
				String text = collector.snapshotText(MetricsCollector.SnapshotTextOptions
						.withMetricsFormat(format).build()).orElseThrow();
				assertExportedCountMatchesInfinity(text, "soklet_http_request_duration_nanos");
				assertExportedCountMatchesInfinity(text, "soklet_mcp_request_duration_nanos");
			}
			collector.reset();
		}
	}

	private static void assertCountMatchesFinalBucket(MetricsCollector.HistogramSnapshot snapshot) {
		long previous = 0;
		for (int bucket = 0; bucket < snapshot.getBucketCount(); bucket++) {
			long count = snapshot.getBucketCumulativeCount(bucket);
			Assertions.assertTrue(count >= previous, "Cumulative buckets must be nonnegative and ordered");
			previous = count;
		}
		Assertions.assertEquals(previous, snapshot.getCount(), "Sample count must equal the final cumulative bucket");
	}

	private static void assertExportedCountMatchesInfinity(String text, String family) {
		Matcher infinity = Pattern.compile("^" + family
				+ "_bucket\\{(.*),le=\"\\+Inf\"\\} (\\d+)$", Pattern.MULTILINE).matcher(text);
		Assertions.assertTrue(infinity.find(), "Missing infinity bucket for " + family);
		String expected = family + "_count{" + infinity.group(1) + "} " + infinity.group(2);
		Assertions.assertTrue(text.lines().anyMatch(expected::equals), expected);
	}

	/** Test-only pause points; the production histogram needs no callback or reflection hook. */
	private static void installBucket(MetricsCollector.Histogram histogram, int index, LongAdder bucket)
			throws ReflectiveOperationException {
		Field field = MetricsCollector.Histogram.class.getDeclaredField("bucketCounts");
		field.setAccessible(true);
		((LongAdder[]) field.get(histogram))[index] = bucket;
	}

	private static FutureTask<Void> runWorker(Runnable action) {
		FutureTask<Void> task = new FutureTask<>(action, null);
		Thread worker = new Thread(task, "histogram-consistency-test");
		worker.setDaemon(true);
		worker.start();
		return task;
	}

	private static void await(CountDownLatch latch) {
		try {
			Assertions.assertTrue(latch.await(3, TimeUnit.SECONDS), "Pause point did not release");
		} catch (InterruptedException failure) {
			Thread.currentThread().interrupt();
			throw new AssertionError(failure);
		}
	}

	private static final class Pause {
		private final CountDownLatch entered = new CountDownLatch(1);
		private final CountDownLatch released = new CountDownLatch(1);

		void stop() {
			this.entered.countDown();
			await(this.released);
		}

		void awaitEntry() throws InterruptedException {
			Assertions.assertTrue(this.entered.await(3, TimeUnit.SECONDS), "Worker did not reach the pause point");
		}

		void release() {
			this.released.countDown();
		}
	}

	private static final class PausingIncrementAdder extends LongAdder {
		private static final long serialVersionUID = 1L;
		private final transient Pause pause;

		PausingIncrementAdder(Pause pause) {
			this.pause = pause;
		}

		@Override
		public void increment() {
			this.pause.stop();
			super.increment();
		}
	}

	private static final class PausingResetAdder extends LongAdder {
		private static final long serialVersionUID = 1L;
		private final transient Pause pause;

		PausingResetAdder(Pause pause) {
			this.pause = pause;
		}

		@Override
		public void reset() {
			this.pause.stop();
			super.reset();
		}
	}
}
