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

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import com.soklet.McpLegacySessionTransportPublicRuntimeTests.Fixture;
import com.soklet.McpLegacySessionTransportPublicRuntimeTests.RawClient;
import com.soklet.McpLegacySessionTransportPublicRuntimeTests.Head;
import com.soklet.McpLegacySessionTransportPublicRuntimeTests.HeaderValue;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Live terminal transitions while semantic start observation is physically outstanding.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Timeout(120)
class McpObservationStartRaceTests {
	@TestFactory
	Stream<DynamicTest> terminalDuringStartReleasesPairedObservationAndPreservesStreamOrder() {
		return Stream.of(McpProtocolVersion.V2025_06_18, McpProtocolVersion.V2025_11_25)
				.flatMap(version -> Stream.of("deadline", "delete", "expiry", "cancel").map(terminal ->
						DynamicTest.dynamicTest(version + ": " + terminal, () -> assertTimeoutPreemptively(Duration.ofSeconds(120),
						() -> exercise(version, terminal)))));
	}

	private void exercise(McpProtocolVersion version, String terminal) throws Exception {
		CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1), finished = new CountDownLatch(1);
		AtomicInteger starts = new AtomicInteger(), finishes = new AtomicInteger(), handlers = new AtomicInteger();
		List<McpMetricsEvent> events = new CopyOnWriteArrayList<>();
		LifecycleObserver observer = new LifecycleObserver() {
			@Override public void didReceiveLogEvent(LogEvent event) {}
			@Override public void didStartMcpRequestHandling(McpRequestContext request) {
				if (!request.getJsonRpcMethod().equals("tools/call")) return;
				starts.incrementAndGet(); started.countDown();
				boolean interrupted = false;
				try {
					while (release.getCount() != 0) {
						try { release.await(); } catch (InterruptedException ignored) { interrupted = true; }
					}
				} finally { if (interrupted) Thread.currentThread().interrupt(); }
			}
			@Override public void didFinishMcpRequestHandling(McpRequestContext request, McpRequestOutcome outcome,
					McpJsonRpcError error, Duration duration, List<Throwable> failures) {
				if (request.getJsonRpcMethod().equals("tools/call")) { finishes.incrementAndGet(); finished.countDown(); }
			}
		};
		MetricsCollector collector = new MetricsCollector() {
			@Override public void didRecordMcpMetricsEvent(McpMetricsEvent event) { events.add(event); }
		};
		try (Fixture fixture = new Fixture(true, builder -> {
			builder.requestTimeout(Duration.ofSeconds(terminal.equals("deadline") ? 1 : 5));
			if (terminal.equals("expiry")) builder.sessionConfig(McpSessionConfig.withOwnerKeyResolver(identity -> "alice")
					.maximumSessionIdleDuration(Duration.ofSeconds(2)).maximumSessionDuration(Duration.ofSeconds(2))
					.transportAdmissionController(Fixture::allow).build());
		}, (request, arguments, features) -> { handlers.incrementAndGet(); return McpCompleteResult.fromToolText("unexpected"); }, collector, observer)) {
			String session = fixture.initialize("/mcp", version);
			List<HeaderValue> headers = List.of(new HeaderValue("MCP-Protocol-Version", version.getWireValue()),
					new HeaderValue("Mcp-Session-Id", session), new HeaderValue("X-Subject", "alice"));
			String request = "{\"jsonrpc\":\"2.0\",\"id\":\"blocked\",\"method\":\"tools/call\",\"params\":{\"name\":\"busy\",\"arguments\":{}}}";
			try (RawClient client = new RawClient(fixture.port, "POST", "/mcp", request, headers)) {
				assertTrue(started.await(5, TimeUnit.SECONDS));
				if (terminal.equals("delete"))
					assertEquals(204, fixture.control("DELETE", "/mcp", version, session, "alice", "", List.of()).status());
				else if (terminal.equals("cancel")) {
					String cancel = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{\"requestId\":\"blocked\"}}";
					assertEquals(202, fixture.exchange("POST", "/mcp", cancel, headers).status());
				}
				Head head = client.readHead();
				assertEquals(terminal.equals("deadline") ? 504 : 200, head.status());
				client.readBody(head);
				assertEquals(0, finishes.get(), "Finish must wait for the admitted start callback to return.");
			} finally { release.countDown(); }
			assertTrue(finished.await(5, TimeUnit.SECONDS), "The terminal request leaked its reserved semantic finish.");
			assertEquals(1, starts.get()); assertEquals(1, finishes.get()); assertEquals(0, handlers.get());
			if (terminal.equals("cancel")) {
				long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
				while (events.stream().noneMatch(McpMetricsEvent.RequestStreamClosed.class::isInstance)
						&& System.nanoTime() < until) Thread.sleep(5);
				List<Class<?>> transitions = events.stream().filter(event -> event instanceof McpMetricsEvent.RequestStreamOpened
						|| event instanceof McpMetricsEvent.RequestStreamClosed).map(Object::getClass).toList();
				assertEquals(List.of(McpMetricsEvent.RequestStreamOpened.class, McpMetricsEvent.RequestStreamClosed.class), transitions,
						"A stream opened during semantic start needs exactly one ordered open/close pair.");
			}
			// close() must prove complete physical cleanup; a leaked reservation makes it throw.
		} finally { release.countDown(); }
	}
}
