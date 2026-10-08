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
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.soklet.McpMetricsEvent.SubscriptionMaintenance.Outcome.*;
import static com.soklet.McpMetricsEvent.SubscriptionMaintenance.Work.*;

/** Default-collector regressions for delivered subscription maintenance. */
public class McpSubscriptionMaintenanceMetricsAggregationTests {
	private static final String METRIC = "soklet_mcp_subscription_maintenance_total";
	private static final String HELP = "Total delivered MCP subscription-maintenance events by endpoint, work and outcome";

	@Test
	public void maintenanceOnlyEventsAppearInBothFormats() {
		DefaultMetricsCollector collector = DefaultMetricsCollector.defaultInstance();
		for (McpMetricsEvent.SubscriptionMaintenance.Work work
				: McpMetricsEvent.SubscriptionMaintenance.Work.values())
			for (McpMetricsEvent.SubscriptionMaintenance.Outcome outcome
					: McpMetricsEvent.SubscriptionMaintenance.Outcome.values())
				for (int repeat = 0; repeat < 2; repeat++)
					collector.didRecordMcpMetricsEvent(
							McpMetricsEvent.subscriptionMaintenance("/mcp", work, outcome));
		for (MetricsCollector.MetricsFormat format : MetricsCollector.MetricsFormat.values()) {
			String text = text(collector, format);
			Assertions.assertTrue(text.contains(METRIC + "{"), text);
			Assertions.assertFalse(text.contains("soklet_mcp_subscriptions_active"));
			for (McpMetricsEvent.SubscriptionMaintenance.Work work
					: McpMetricsEvent.SubscriptionMaintenance.Work.values())
				for (McpMetricsEvent.SubscriptionMaintenance.Outcome outcome
						: McpMetricsEvent.SubscriptionMaintenance.Outcome.values())
					Assertions.assertTrue(text.contains(sample(key("/mcp", work, outcome), 2)), text);
			String family = format == MetricsCollector.MetricsFormat.OPEN_METRICS_1_0
					? "soklet_mcp_subscription_maintenance" : METRIC;
			Assertions.assertTrue(text.contains("# HELP " + family + " " + HELP + "\n"));
			Assertions.assertTrue(text.contains("# TYPE " + family + " counter\n"));
			if (format == MetricsCollector.MetricsFormat.OPEN_METRICS_1_0)
				Assertions.assertTrue(text.endsWith("# EOF\n"));
		}
		Map<McpMetricsSnapshot.SubscriptionMaintenanceKey, Long> counts = snapshot(collector).getSubscriptionMaintenance();
		Assertions.assertEquals(21, counts.size());
		Assertions.assertEquals(42L, counts.values().stream().mapToLong(Long::longValue).sum());
	}

	@Test
	public void snapshotContractUsesApprovedBoxedMapAndFinalKey() throws Exception {
		Class<McpMetricsSnapshot.SubscriptionMaintenanceKey> type = McpMetricsSnapshot.SubscriptionMaintenanceKey.class;
		Assertions.assertTrue(Modifier.isPublic(type.getModifiers()));
		Assertions.assertTrue(Modifier.isFinal(type.getModifiers()));
		Assertions.assertTrue(Modifier.isStatic(type.getModifiers()));
		Assertions.assertFalse(type.isRecord());
		Assertions.assertEquals(0, type.getConstructors().length);
		Assertions.assertEquals(Set.of("fromDimensions", "getEndpointPath", "getWork", "getOutcome", "equals", "hashCode", "toString"),
				java.util.Arrays.stream(type.getDeclaredMethods()).filter(method -> Modifier.isPublic(method.getModifiers()))
						.map(Method::getName).collect(java.util.stream.Collectors.toSet()));
		Method factory = type.getMethod("fromDimensions", String.class,
				McpMetricsEvent.SubscriptionMaintenance.Work.class, McpMetricsEvent.SubscriptionMaintenance.Outcome.class);
		Assertions.assertTrue(Modifier.isStatic(factory.getModifiers()));
		Assertions.assertEquals(type, factory.getReturnType());
		for (var parameter : factory.getAnnotatedParameterTypes())
			Assertions.assertTrue(parameter.isAnnotationPresent(NonNull.class));
		for (String getter : Set.of("getEndpointPath", "getWork", "getOutcome", "toString"))
			Assertions.assertTrue(type.getMethod(getter).getAnnotatedReturnType().isAnnotationPresent(NonNull.class));
		Assertions.assertTrue(type.getMethod("equals", Object.class).getAnnotatedParameterTypes()[0].isAnnotationPresent(Nullable.class));
		Method getter = McpMetricsSnapshot.class.getMethod("getSubscriptionMaintenance");
		var map = (AnnotatedParameterizedType) getter.getAnnotatedReturnType();
		Assertions.assertTrue(map.isAnnotationPresent(NonNull.class));
		Assertions.assertEquals(type, map.getAnnotatedActualTypeArguments()[0].getType());
		Assertions.assertEquals(Long.class, map.getAnnotatedActualTypeArguments()[1].getType());
		for (var argument : map.getAnnotatedActualTypeArguments())
			Assertions.assertTrue(argument.isAnnotationPresent(NonNull.class));
		Method setter = McpMetricsSnapshot.Builder.class.getMethod("subscriptionMaintenance", Map.class);
		Assertions.assertEquals(McpMetricsSnapshot.Builder.class, setter.getReturnType());
		Assertions.assertTrue(setter.getAnnotatedReturnType().isAnnotationPresent(NonNull.class));
		Assertions.assertTrue(setter.getAnnotatedParameterTypes()[0].isAnnotationPresent(Nullable.class));
		Assertions.assertTrue(McpMetricsSnapshot.emptyInstance().getSubscriptionMaintenance().isEmpty());
	}

	@Test
	public void keysCompareAllDimensionsAndRedactApplicationPaths() {
		var primary = key("/mcp/private-path-canary", AUTHORIZATION, DENIED);
		var equal = key("/mcp/private-path-canary", AUTHORIZATION, DENIED);
		Assertions.assertEquals(primary, equal);
		Assertions.assertEquals(primary.hashCode(), equal.hashCode());
		Assertions.assertEquals("/mcp/private-path-canary", primary.getEndpointPath());
		Assertions.assertEquals(AUTHORIZATION, primary.getWork());
		Assertions.assertEquals(DENIED, primary.getOutcome());
		Assertions.assertNotEquals(primary, key("/other", AUTHORIZATION, DENIED));
		Assertions.assertNotEquals(primary, key(primary.getEndpointPath(), RECONCILIATION, DENIED));
		Assertions.assertNotEquals(primary, key(primary.getEndpointPath(), AUTHORIZATION, SUCCEEDED));
		Assertions.assertNotEquals(primary, null);
		Assertions.assertNotEquals(primary, "/mcp/private-path-canary");
		Assertions.assertFalse(primary.toString().contains("private-path-canary"));
		Assertions.assertTrue(primary.toString().contains("endpointPath=<redacted>"));
		Assertions.assertTrue(primary.toString().contains("AUTHORIZATION"));
		Assertions.assertTrue(primary.toString().contains("DENIED"));
		Assertions.assertThrows(IllegalArgumentException.class, () -> key("", AUTHORIZATION, DENIED));
		Assertions.assertThrows(NullPointerException.class, () -> key(null, AUTHORIZATION, DENIED));
		Assertions.assertThrows(NullPointerException.class, () -> key("/mcp", null, DENIED));
		Assertions.assertThrows(NullPointerException.class, () -> key("/mcp", AUTHORIZATION, null));
	}

	@Test
	public void builderCopiesMapsPreservesZeroAndClearsWithoutMutatingSnapshots() {
		var primary = key("/mcp", CATALOG_PROJECTION, SUCCEEDED);
		var zero = key("/mcp", CATALOG_PROJECTION, COALESCED);
		Map<McpMetricsSnapshot.SubscriptionMaintenanceKey, Long> source = new LinkedHashMap<>();
		source.put(primary, 7L);
		source.put(zero, 0L);
		var builder = McpMetricsSnapshot.builder();
		Assertions.assertSame(builder, builder.subscriptionMaintenance(source));
		source.clear();
		var captured = builder.build();
		Assertions.assertEquals(Map.of(primary, 7L, zero, 0L), captured.getSubscriptionMaintenance());
		Assertions.assertThrows(UnsupportedOperationException.class, () -> captured.getSubscriptionMaintenance().clear());
		Assertions.assertThrows(UnsupportedOperationException.class, () -> captured.getSubscriptionMaintenance().entrySet().iterator().next().setValue(9L));
		Assertions.assertTrue(builder.subscriptionMaintenance(null).build().getSubscriptionMaintenance().isEmpty());
		Assertions.assertTrue(builder.subscriptionMaintenance(Map.of(primary, 1L)).subscriptionMaintenance(Map.of()).build().getSubscriptionMaintenance().isEmpty());
		Assertions.assertEquals(7L, captured.getSubscriptionMaintenance().get(primary));
	}

	@Test
	public void builderRejectsNullEntriesAndNegativeCountsWithoutLeakingDimensions() {
		var primary = key("/mcp/invalid-count-canary", RECONCILIATION, FAILED);
		var exception = Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpMetricsSnapshot.builder().subscriptionMaintenance(Map.of(primary, -1L)));
		Assertions.assertFalse(exception.getMessage().contains("invalid-count-canary"));
		Map<McpMetricsSnapshot.SubscriptionMaintenanceKey, Long> source = new HashMap<>();
		source.put(null, 1L);
		Assertions.assertThrows(NullPointerException.class, () -> McpMetricsSnapshot.builder().subscriptionMaintenance(source));
		source.clear();
		source.put(primary, null);
		Assertions.assertThrows(NullPointerException.class, () -> McpMetricsSnapshot.builder().subscriptionMaintenance(source));
	}

	@Test
	public void countersSeparateEndpointsAndFilterExactFixedDimensions() {
		DefaultMetricsCollector collector = DefaultMetricsCollector.defaultInstance();
		var primary = key("/mcp/a", RECONCILIATION, STALE_RESULT_DISCARDED);
		var secondary = key("/mcp/b", RECONCILIATION, STALE_RESULT_DISCARDED);
		record(collector, primary);
		record(collector, primary);
		record(collector, secondary);
		Assertions.assertEquals(Map.of(primary, 2L, secondary, 1L), snapshot(collector).getSubscriptionMaintenance());
		for (MetricsCollector.MetricsFormat format : MetricsCollector.MetricsFormat.values()) {
			String selected = collector.snapshotText(MetricsCollector.SnapshotTextOptions.withMetricsFormat(format)
					.metricFilter(metric -> {
						if (!metric.getName().equals(METRIC)) return false;
						Assertions.assertEquals(Set.of("endpoint", "work", "outcome"), metric.getLabels().keySet());
						Assertions.assertEquals("reconciliation", metric.getLabels().get("work"));
						Assertions.assertEquals("stale_result_discarded", metric.getLabels().get("outcome"));
						return metric.getLabels().get("endpoint").equals("/mcp/a");
					}).build()).orElseThrow();
			Assertions.assertTrue(selected.contains(sample(primary, 2)));
			Assertions.assertFalse(selected.contains("/mcp/b"));
			String rejected = collector.snapshotText(MetricsCollector.SnapshotTextOptions.withMetricsFormat(format)
					.metricFilter(metric -> !metric.getName().equals(METRIC)).build()).orElseThrow();
			Assertions.assertFalse(rejected.contains("soklet_mcp_subscription_maintenance"));
		}
	}

	@Test
	public void resetClearsMaintenanceCountsAndRetainsLiveSubscriptionBookkeeping() {
		DefaultMetricsCollector collector = DefaultMetricsCollector.defaultInstance();
		var primary = key("/mcp", AUTHORIZATION, SUCCEEDED);
		Assertions.assertSame(McpMetricsSnapshot.emptyInstance(), snapshot(collector));
		record(collector, primary);
		var before = snapshot(collector);
		Assertions.assertNotSame(McpMetricsSnapshot.emptyInstance(), before);
		collector.didRecordMcpMetricsEvent(McpMetricsEvent.subscriptionOpened("/mcp"));
		collector.reset();
		Assertions.assertTrue(snapshot(collector).getSubscriptionMaintenance().isEmpty());
		Assertions.assertEquals(1L, snapshot(collector).getActiveSubscriptions());
		for (MetricsCollector.MetricsFormat format : MetricsCollector.MetricsFormat.values())
			Assertions.assertFalse(text(collector, format).contains("soklet_mcp_subscription_maintenance"));
		record(collector, primary);
		Assertions.assertEquals(Map.of(primary, 1L), snapshot(collector).getSubscriptionMaintenance());
		Assertions.assertEquals(Map.of(primary, 1L), before.getSubscriptionMaintenance());
		collector.didRecordMcpMetricsEvent(McpMetricsEvent.subscriptionClosed("/mcp",
				McpStreamTerminationReason.CLIENT_DISCONNECTED, java.time.Duration.ofNanos(1)));
		Assertions.assertEquals(0L, snapshot(collector).getActiveSubscriptions());
		collector.reset();
		Assertions.assertSame(McpMetricsSnapshot.emptyInstance(), snapshot(collector));
	}

	@Test
	@Timeout(10)
	public void concurrentDeliveredEventsHaveExactQuiescentCounts() throws Exception {
		DefaultMetricsCollector collector = DefaultMetricsCollector.defaultInstance();
		var primary = key("/mcp", AUTHORIZATION, TIMED_OUT);
		var executor = Executors.newFixedThreadPool(2);
		CountDownLatch start = new CountDownLatch(1);
		try {
			var first = executor.submit(() -> writeCounts(collector, primary, start));
			var second = executor.submit(() -> writeCounts(collector, primary, start));
			start.countDown();
			first.get(5, TimeUnit.SECONDS);
			second.get(5, TimeUnit.SECONDS);
			Assertions.assertEquals(Map.of(primary, 2_000L), snapshot(collector).getSubscriptionMaintenance());
			collector.reset();
			Assertions.assertTrue(snapshot(collector).getSubscriptionMaintenance().isEmpty());
		} finally {
			start.countDown();
			executor.shutdownNow();
			Assertions.assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
		}
	}

	@Test
	public void maintenanceRetentionUsesExistingBoundedCapacity() {
		DefaultMetricsCollector collector = DefaultMetricsCollector.defaultInstance();
		for (int index = 0; index < 8_193; index++)
			record(collector, key("/configured-endpoint/" + index, AUTHORIZATION, CAPACITY_REJECTED));
		var counts = snapshot(collector).getSubscriptionMaintenance();
		Assertions.assertEquals(8_192, counts.size());
		Assertions.assertFalse(counts.containsKey(key("/configured-endpoint/0", AUTHORIZATION, CAPACITY_REJECTED)));
		Assertions.assertEquals(1L, counts.get(key("/configured-endpoint/8192", AUTHORIZATION, CAPACITY_REJECTED)));
	}

	private static void writeCounts(DefaultMetricsCollector collector,
			McpMetricsSnapshot.SubscriptionMaintenanceKey key, CountDownLatch start) {
		try {
			Assertions.assertTrue(start.await(5, TimeUnit.SECONDS));
			for (int index = 0; index < 1_000; index++) record(collector, key);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError(exception);
		}
	}

	private static McpMetricsSnapshot.SubscriptionMaintenanceKey key(String endpointPath,
			McpMetricsEvent.SubscriptionMaintenance.Work maintenanceWork,
			McpMetricsEvent.SubscriptionMaintenance.Outcome maintenanceOutcome) {
		return McpMetricsSnapshot.SubscriptionMaintenanceKey.fromDimensions(endpointPath, maintenanceWork, maintenanceOutcome);
	}

	private static void record(DefaultMetricsCollector collector, McpMetricsSnapshot.SubscriptionMaintenanceKey key) {
		collector.didRecordMcpMetricsEvent(McpMetricsEvent.subscriptionMaintenance(key.getEndpointPath(), key.getWork(), key.getOutcome()));
	}

	private static McpMetricsSnapshot snapshot(DefaultMetricsCollector collector) {
		return collector.snapshot().orElseThrow().getMcpMetrics();
	}

	private static String text(DefaultMetricsCollector collector, MetricsCollector.MetricsFormat format) {
		return collector.snapshotText(MetricsCollector.SnapshotTextOptions.withMetricsFormat(format).build()).orElseThrow();
	}

	private static String sample(McpMetricsSnapshot.SubscriptionMaintenanceKey key, long count) {
		return METRIC + "{endpoint=\"" + key.getEndpointPath() + "\",work=\"" + key.getWork().name().toLowerCase(Locale.ROOT)
				+ "\",outcome=\"" + key.getOutcome().name().toLowerCase(Locale.ROOT) + "\"} " + count + "\n";
	}
}
