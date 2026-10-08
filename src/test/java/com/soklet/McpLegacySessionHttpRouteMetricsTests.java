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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class McpLegacySessionHttpRouteMetricsTests {
	private static final List<McpProtocolVersion> LEGACY = List.of(
			McpProtocolVersion.V2025_06_18, McpProtocolVersion.V2025_11_25);
	private static final String SECRET = "request-value-must-not-be-a-route";

	@BeforeEach
	void beginRequestBudget() { McpLegacySessionTransportPublicRuntimeTests.RawClient.beginRequestBudget(); }

	@AfterEach
	void endRequestBudget() { McpLegacySessionTransportPublicRuntimeTests.RawClient.endRequestBudget(); }

	@Test
	void acceptedGetAndDeleteUseTheSelectedEndpointAndReleaseTheActiveGauge() throws Exception {
		for (McpProtocolVersion version : LEGACY) {
			MetricsCollector collector = MetricsCollector.defaultInstance();
			try (var fixture = new McpLegacySessionTransportPublicRuntimeTests.Fixture(true, builder -> {}, null, collector)) {
				String id = fixture.initialize("/mcp", version);
				assertTrue(collector.snapshot().orElseThrow().getHttpRequestDurations().isEmpty(), "POST uses semantic MCP metrics");
				try (var get = fixture.openControl("GET", "/mcp?credential=" + SECRET, version, id, "alice", "", List.of())) {
					var head = get.readHead();
					assertEquals(200, head.status());
					assertEquals(1L, collector.snapshot().orElseThrow().getActiveRequests());
					assertRequestBodyPoint(collector, HttpMethod.GET, "/mcp", 1L);
					assertEquals(204, fixture.control("DELETE", "/mcp", version, id, "alice", "", List.of()).status());
					get.readBody(head);
				}
				awaitFinished(fixture, 2);
				assertEquals(0L, collector.snapshot().orElseThrow().getActiveRequests());
				assertCompletedPoint(collector, HttpMethod.GET, "/mcp", "2xx", 1L);
				assertCompletedPoint(collector, HttpMethod.DELETE, "/mcp", "2xx", 1L);
				assertSafeExports(collector, Set.of("/mcp"));
			}
		}
	}

	@Test
	void rejectedControlsRetainMatchedRoutesAndSeparateEndpoints() throws Exception {
		for (McpProtocolVersion version : LEGACY) {
			MetricsCollector collector = MetricsCollector.defaultInstance();
			try (var fixture = new McpLegacySessionTransportPublicRuntimeTests.Fixture(true, builder -> {}, null, collector)) {
				String id = fixture.initialize("/mcp", version);
				String otherId = fixture.initialize("/other", version);
				assertEquals(404, fixture.control("GET", "/mcp?credential=" + SECRET, version,
						"unknown-session", "alice", "", List.of()).status());
				assertEquals(400, fixture.control("DELETE", "/mcp", version, id, "alice", "x", List.of()).status());
				assertEquals(405, fixture.control("GET", "/stateless", version, id, "alice", "", List.of()).status());
				assertEquals(204, fixture.control("DELETE", "/other", version, otherId, "alice", "", List.of()).status());
				awaitFinished(fixture, 4);
				assertCompletedPoint(collector, HttpMethod.GET, "/mcp", "4xx", 1L);
				assertCompletedPoint(collector, HttpMethod.DELETE, "/mcp", "4xx", 1L);
				assertCompletedPoint(collector, HttpMethod.GET, "/stateless", "4xx", 1L);
				assertCompletedPoint(collector, HttpMethod.DELETE, "/other", "2xx", 1L);
				assertSafeExports(collector, Set.of("/mcp", "/stateless", "/other"));
			}
		}
	}

	@Test
	void ordinaryUnmatchedHttpRequestsKeepTheirOwnRouteEvenAtTheSamePath() throws Exception {
		MetricsCollector collector = MetricsCollector.defaultInstance();
		try (var fixture = new McpLegacySessionTransportPublicRuntimeTests.Fixture(true, builder -> {}, null, collector)) {
			assertEquals(404, fixture.control("GET", "/mcp", LEGACY.get(0), "unknown", "alice", "", List.of()).status());
			awaitFinished(fixture, 1);
			Request request = Request.withPath(HttpMethod.GET, "/mcp")
					.queryParameters(Map.of("credential", List.of(SECRET))).build();
			MarshaledResponse response = MarshaledResponse.fromStatusCode(404);
			collector.didStartRequestHandling(ServerType.HTTP, request, null);
			collector.didFinishRequestHandling(ServerType.HTTP, request, null, response, Duration.ofMillis(1), List.of());
			assertCompletedPoint(collector, HttpMethod.GET, "/mcp", "4xx", 1L);
			var unmatched = new MetricsCollector.HttpServerRouteStatusKey(HttpMethod.GET,
					MetricsCollector.RouteType.UNMATCHED, null, "4xx");
			assertEquals(1L, collector.snapshot().orElseThrow().getHttpRequestDurations().get(unmatched).getCount());
			assertEquals(2, collector.snapshot().orElseThrow().getHttpRequestDurations().size());
			for (MetricsCollector.MetricsFormat format : MetricsCollector.MetricsFormat.values()) {
				String text = collector.snapshotText(MetricsCollector.SnapshotTextOptions.withMetricsFormat(format).build()).orElseThrow();
				assertTrue(text.contains("route=\"unmatched\""));
				assertFalse(text.contains(SECRET));
			}
		}
	}

	@Test
	void customCollectorsReceiveTheOriginalRequestWithNoInventedResourceMethod() throws Exception {
		List<Request> starts = new CopyOnWriteArrayList<>();
		List<Request> finishes = new CopyOnWriteArrayList<>();
		List<ResourceMethod> unexpectedMethods = new CopyOnWriteArrayList<>();
		List<ServerType> serverTypes = new CopyOnWriteArrayList<>();
		MetricsCollector collector = new MetricsCollector() {
			@Override public void didStartRequestHandling(ServerType serverType, Request request, ResourceMethod resourceMethod) {
				starts.add(request); serverTypes.add(serverType);
				if (resourceMethod != null) unexpectedMethods.add(resourceMethod);
			}
			@Override public void didFinishRequestHandling(ServerType serverType, Request request, ResourceMethod resourceMethod,
					MarshaledResponse response, Duration duration, List<Throwable> throwables) {
				finishes.add(request); serverTypes.add(serverType);
				if (resourceMethod != null) unexpectedMethods.add(resourceMethod);
			}
		};
		try (var fixture = new McpLegacySessionTransportPublicRuntimeTests.Fixture(true, builder -> {}, null, collector)) {
			assertEquals(404, fixture.control("GET", "/mcp?credential=" + SECRET, LEGACY.get(0), "unknown", "alice", "", List.of()).status());
			awaitFinished(fixture, 1);
			assertEquals(1, starts.size()); assertEquals(1, finishes.size());
			assertSame(starts.get(0), finishes.get(0));
			assertEquals(SECRET, starts.get(0).getQueryParameter("credential").orElseThrow());
			assertTrue(unexpectedMethods.isEmpty());
			assertEquals(List.of(ServerType.HTTP, ServerType.HTTP), serverTypes);
			assertEquals(0, fixture.rpcAdmissions.get());
		}
	}

	private static void assertRequestBodyPoint(MetricsCollector collector, HttpMethod method, String route, long count) {
		var key = new MetricsCollector.HttpServerRouteKey(method, MetricsCollector.RouteType.MATCHED,
				ResourcePathDeclaration.fromPath(route));
		var point = collector.snapshot().orElseThrow().getHttpRequestBodyBytes().get(key);
		assertNotNull(point); assertEquals(count, point.getCount());
	}

	private static void assertCompletedPoint(MetricsCollector collector, HttpMethod method, String route, String statusClass, long count) {
		var key = new MetricsCollector.HttpServerRouteStatusKey(method, MetricsCollector.RouteType.MATCHED,
				ResourcePathDeclaration.fromPath(route), statusClass);
		var snapshot = collector.snapshot().orElseThrow();
		for (var points : List.of(snapshot.getHttpRequestDurations(), snapshot.getHttpResponseBodyBytes())) {
			var point = points.get(key);
			assertNotNull(point, method + " " + route + " " + statusClass);
			assertEquals(count, point.getCount());
		}
	}

	private static void assertSafeExports(MetricsCollector collector, Set<String> routes) {
		for (MetricsCollector.MetricsFormat format : MetricsCollector.MetricsFormat.values()) {
			String text = collector.snapshotText(MetricsCollector.SnapshotTextOptions.withMetricsFormat(format).build()).orElseThrow();
			for (String route : routes) assertTrue(text.contains("route=\"" + route + "\""));
			assertFalse(text.contains("route=\"unmatched\""));
			assertFalse(text.contains(SECRET));
			assertFalse(text.contains("unknown-session"));
		}
	}

	private static void awaitFinished(McpLegacySessionTransportPublicRuntimeTests.Fixture fixture, int count) throws InterruptedException {
		long until = System.nanoTime() + Duration.ofSeconds(3).toNanos();
		while (fixture.httpFinishes.get() < count && System.nanoTime() < until) Thread.sleep(5);
		assertEquals(count, fixture.httpFinishes.get());
	}
}
