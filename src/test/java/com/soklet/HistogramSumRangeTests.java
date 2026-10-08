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

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Timeout(value = 15, unit = TimeUnit.SECONDS)
class HistogramSumRangeTests {
	@Test
	void observationsAfterTheLongCeilingStillGrowTheSum() {
		MetricsCollector.Histogram histogram = new MetricsCollector.Histogram(new long[]{1_000_000_000L});
		histogram.record(Long.MAX_VALUE);
		double before = histogram.snapshot().getSum();
		histogram.record(1_000_000_000L);
		double after = histogram.snapshot().getSum();
		Assertions.assertTrue(after > before, "A later observation must grow the total beyond the old ceiling");
		Assertions.assertEquals(1_000_000_000D, after - before, 1024D);
	}

	@Test
	void defaultSubscriptionExportsKeepGrowingBeyondTheLongCeiling() {
		MetricsCollector collector = MetricsCollector.defaultInstance();
		recordSubscription(collector, Duration.ofNanos(Long.MAX_VALUE));
		double before = exportedSum(collector, MetricsCollector.MetricsFormat.PROMETHEUS);
		recordSubscription(collector, Duration.ofSeconds(1));
		for (MetricsCollector.MetricsFormat format : MetricsCollector.MetricsFormat.values()) {
			double after = exportedSum(collector, format);
			Assertions.assertTrue(after > before, format.toString());
			Assertions.assertEquals(1_000_000_000D, after - before, 1024D);
		}
		Assertions.assertEquals(2L, collector.snapshot().orElseThrow().getMcpMetrics()
				.getSubscriptionDurations().values().iterator().next().getCount());
	}

	@Test
	void snapshotPublicMethodsUseReferenceTypes() throws Exception {
		for (var method : MetricsCollector.HistogramSnapshot.class.getDeclaredMethods()) {
			if (!Modifier.isPublic(method.getModifiers()))
				continue;
			Assertions.assertFalse(method.getReturnType().isPrimitive(), method.toString());
			Assertions.assertTrue(method.getAnnotatedReturnType().isAnnotationPresent(NonNull.class), method.toString());
			for (Class<?> parameter : method.getParameterTypes())
				Assertions.assertFalse(parameter.isPrimitive(), method.toString());
			for (var parameter : method.getAnnotatedParameterTypes())
				Assertions.assertTrue(parameter.isAnnotationPresent(NonNull.class), method.toString());
		}
		Map<String, Class<?>> returnTypes = Map.of("getBucketCount", Integer.class,
				"getBucketBoundary", Long.class, "getBucketCumulativeCount", Long.class,
				"getCount", Long.class, "getSum", Double.class, "getMin", Long.class,
				"getMax", Long.class, "getPercentile", Long.class);
		for (var method : MetricsCollector.HistogramSnapshot.class.getDeclaredMethods())
			if (returnTypes.containsKey(method.getName()))
				Assertions.assertEquals(returnTypes.get(method.getName()), method.getReturnType());
		var constructor = MetricsCollector.HistogramSnapshot.class.getConstructor(List.class, List.class,
				Long.class, Double.class, Long.class, Long.class);
		Assertions.assertEquals(1, MetricsCollector.HistogramSnapshot.class.getConstructors().length);
		for (var parameter : constructor.getAnnotatedParameterTypes())
			Assertions.assertTrue(parameter.isAnnotationPresent(NonNull.class));
		for (int index = 0; index < 2; index++) {
			var list = (AnnotatedParameterizedType) constructor.getAnnotatedParameterTypes()[index];
			Assertions.assertEquals(Long.class, list.getAnnotatedActualTypeArguments()[0].getType());
			Assertions.assertTrue(list.getAnnotatedActualTypeArguments()[0].isAnnotationPresent(NonNull.class));
		}
	}

	@Test
	void quiescentResetStartsANewSumAndPreservesEarlierSnapshots() {
		MetricsCollector.Histogram histogram = new MetricsCollector.Histogram(new long[0]);
		histogram.record(7);
		MetricsCollector.HistogramSnapshot before = histogram.snapshot();
		histogram.reset();
		Assertions.assertEquals(0D, (double) histogram.snapshot().getSum());
		histogram.record(12);
		Assertions.assertEquals(12D, (double) histogram.snapshot().getSum());
		Assertions.assertEquals(7D, (double) before.getSum());
	}

	@Test
	void constructorCopiesListsAndRetainsTheSuppliedIndependentCount() {
		List<Long> boundaries = new ArrayList<>(List.of(10L, Long.MAX_VALUE));
		List<Long> counts = new ArrayList<>(List.of(1L, 2L));
		MetricsCollector.HistogramSnapshot snapshot = new MetricsCollector.HistogramSnapshot(
				boundaries, counts, 99L, 3.5D, 1L, 12L);
		boundaries.set(0, 999L);
		counts.clear();
		Assertions.assertEquals(2, snapshot.getBucketCount());
		Assertions.assertEquals(10L, snapshot.getBucketBoundary(0));
		Assertions.assertEquals(1L, snapshot.getBucketCumulativeCount(0));
		Assertions.assertEquals(99L, snapshot.getCount());
		Assertions.assertEquals(3.5D, snapshot.getSum());
	}

	@Test
	void constructorRejectsNullCollectionsElementsAndScalarInputs() {
		Assertions.assertAll(
				() -> Assertions.assertThrows(NullPointerException.class, () -> snapshot(null, List.of(0L), 0L, 0D, 0L, 0L)),
				() -> Assertions.assertThrows(NullPointerException.class, () -> snapshot(List.of(10L), null, 0L, 0D, 0L, 0L)),
				() -> Assertions.assertThrows(NullPointerException.class, () -> snapshot(Arrays.asList((Long) null), List.of(0L), 0L, 0D, 0L, 0L)),
				() -> Assertions.assertThrows(NullPointerException.class, () -> snapshot(List.of(10L), Arrays.asList((Long) null), 0L, 0D, 0L, 0L)),
				() -> Assertions.assertThrows(NullPointerException.class, () -> snapshot(List.of(10L), List.of(0L), null, 0D, 0L, 0L)),
				() -> Assertions.assertThrows(NullPointerException.class, () -> snapshot(List.of(10L), List.of(0L), 0L, null, 0L, 0L)),
				() -> Assertions.assertThrows(NullPointerException.class, () -> snapshot(List.of(10L), List.of(0L), 0L, 0D, null, 0L)),
				() -> Assertions.assertThrows(NullPointerException.class, () -> snapshot(List.of(10L), List.of(0L), 0L, 0D, 0L, null)));
	}

	@Test
	void constructorRequiresFiniteNonnegativeSumsAndMatchingBucketLists() {
		for (Double sum : List.of(-1D, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY))
			Assertions.assertThrows(IllegalArgumentException.class,
					() -> snapshot(List.of(10L), List.of(0L), 0L, sum, 0L, 0L));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> snapshot(List.of(10L), List.of(), 0L, 0D, 0L, 0L));
		Assertions.assertEquals(0L, Double.doubleToRawLongBits(
				snapshot(List.of(10L), List.of(0L), 0L, -0D, 0L, 0L).getSum()));
	}

	@Test
	void indexedAndPercentileAccessorsRejectNullInputs() {
		MetricsCollector.HistogramSnapshot snapshot = snapshot(List.of(10L), List.of(0L), 0L, 0D, 0L, 0L);
		Assertions.assertThrows(NullPointerException.class, () -> snapshot.getBucketBoundary(null));
		Assertions.assertThrows(NullPointerException.class, () -> snapshot.getBucketCumulativeCount(null));
		Assertions.assertThrows(NullPointerException.class, () -> snapshot.getPercentile(null));
		Assertions.assertThrows(IndexOutOfBoundsException.class, () -> snapshot.getBucketBoundary(-1));
		Assertions.assertThrows(IndexOutOfBoundsException.class, () -> snapshot.getBucketCumulativeCount(1));
	}

	@Test
	void percentilesRetainInclusiveBoundsAndObservedOverflowMaximum() {
		MetricsCollector.Histogram histogram = new MetricsCollector.Histogram(new long[]{1L, 10L});
		for (long value : new long[]{0L, 2L, 11L})
			histogram.record(value);
		MetricsCollector.HistogramSnapshot snapshot = histogram.snapshot();
		Assertions.assertEquals(0L, snapshot.getPercentile(0D));
		Assertions.assertEquals(10L, snapshot.getPercentile(50D));
		Assertions.assertEquals(11L, snapshot.getPercentile(99D));
		Assertions.assertEquals(11L, snapshot.getPercentile(100D));
	}

	@Test
	void integerFieldsKeepTheirFullLongPrecision() {
		long exact = Long.MAX_VALUE - 7L;
		MetricsCollector.HistogramSnapshot snapshot = snapshot(List.of(exact), List.of(exact),
				exact, 1D, exact, exact);
		Assertions.assertEquals(exact, snapshot.getBucketBoundary(0));
		Assertions.assertEquals(exact, snapshot.getBucketCumulativeCount(0));
		Assertions.assertEquals(exact, snapshot.getCount());
		Assertions.assertEquals(exact, snapshot.getMin());
		Assertions.assertEquals(exact, snapshot.getMax());
	}

	@Test
	void concurrentWritersAccumulateTheSumAndCount() throws Exception {
		MetricsCollector.Histogram histogram = new MetricsCollector.Histogram(new long[]{1000L});
		var workers = Executors.newFixedThreadPool(2);
		CountDownLatch start = new CountDownLatch(1);
		try {
			List<java.util.concurrent.Future<?>> work = new ArrayList<>();
			for (int worker = 0; worker < 2; worker++)
				work.add(workers.submit(() -> {
					try {
						Assertions.assertTrue(start.await(3, TimeUnit.SECONDS));
					} catch (InterruptedException failure) {
						Thread.currentThread().interrupt();
						throw new AssertionError(failure);
					}
					for (int index = 0; index < 1000; index++)
						histogram.record(1000L);
				}));
			start.countDown();
			for (var future : work)
				future.get(3, TimeUnit.SECONDS);
			MetricsCollector.HistogramSnapshot snapshot = histogram.snapshot();
			Assertions.assertEquals(2000L, snapshot.getCount());
			Assertions.assertEquals(2_000_000D, snapshot.getSum());
			Assertions.assertEquals(snapshot.getCount(), snapshot.getBucketCumulativeCount(1));
		} finally {
			start.countDown();
			workers.shutdownNow();
			Assertions.assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS));
		}
	}

	@Test
	void diagnosticSummaryAcceptsAFloatingPointSum() {
		MetricsCollector.HistogramSnapshot snapshot = snapshot(List.of(10L), List.of(1L), 1L, 3.5D, 3L, 4L);
		Assertions.assertTrue(snapshot.toString().contains("sum=3.5"));
	}

	@Test
	void independentlyConstructedEmptySnapshotsStillReturnZeroValues() {
		MetricsCollector.HistogramSnapshot snapshot = snapshot(List.of(), List.of(), 0L, 0D, 0L, 0L);
		Assertions.assertEquals(0, snapshot.getBucketCount());
		Assertions.assertEquals(0L, snapshot.getCount());
		Assertions.assertEquals(0D, snapshot.getSum());
		Assertions.assertEquals(0L, snapshot.getPercentile(50D));
	}

	private static MetricsCollector.HistogramSnapshot snapshot(List<Long> boundaries, List<Long> counts,
			Long count, Double sum, Long min, Long max) {
		return new MetricsCollector.HistogramSnapshot(boundaries, counts, count, sum, min, max);
	}

	private static void recordSubscription(MetricsCollector collector, Duration duration) {
		collector.didRecordMcpMetricsEvent(McpMetricsEvent.subscriptionOpened("/sum-range"));
		collector.didRecordMcpMetricsEvent(McpMetricsEvent.subscriptionClosed("/sum-range",
				McpStreamTerminationReason.COMPLETED, duration));
	}

	private static double exportedSum(MetricsCollector collector, MetricsCollector.MetricsFormat format) {
		String text = collector.snapshotText(MetricsCollector.SnapshotTextOptions.withMetricsFormat(format).build())
				.orElseThrow();
		return text.lines().filter(line -> line.startsWith("soklet_mcp_subscription_duration_nanos_sum{"))
				.mapToDouble(line -> Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1)))
				.findFirst().orElseThrow();
	}
}
