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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import com.soklet.McpLegacySessionTransportPublicRuntimeTests.Fixture;
import com.soklet.McpLegacySessionTransportPublicRuntimeTests.Head;
import com.soklet.McpLegacySessionTransportPublicRuntimeTests.RawClient;
import com.soklet.McpLegacySessionTransportPublicRuntimeTests.Response;

import static org.junit.jupiter.api.Assertions.*;

/** Real sockets verify bounded retry and physical maintenance ownership. */
@Timeout(180)
class McpLegacySessionTransportCapacityRuntimeTests {
	private static final List<McpProtocolVersion> LEGACY = List.of(
			McpProtocolVersion.V2025_06_18, McpProtocolVersion.V2025_11_25);

	@BeforeEach
	void beginRequestBudget() { RawClient.beginRequestBudget(); }

	@AfterEach
	void endRequestBudget() { RawClient.endRequestBudget(); }

	@Test
	void temporary_handler_capacity_keeps_get_fenced_and_retries_without_replacing_its_stream() throws Exception {
		for (McpProtocolVersion version : LEGACY) {
			CountDownLatch admissionEntered = new CountDownLatch(1);
			CountDownLatch admissionRelease = new CountDownLatch(1);
			AtomicInteger renewalEntries = new AtomicInteger();
			AtomicReference<Response> firstAdmission = new AtomicReference<>();
			AtomicReference<Response> secondAdmission = new AtomicReference<>();
			AtomicReference<Throwable> failure = new AtomicReference<>();
			List<Thread> callers = new ArrayList<>();
			try (Fixture fixture = new Fixture(true, builder -> builder
					.requestHandlerConcurrency(1).requestHandlerQueueCapacity(1)
					.maximumSubscriptionAuthorizationDuration(Duration.ofSeconds(5))
					.maximumSubscriptionDuration(Duration.ofSeconds(10))
					.subscriptionAuthorizationTimeout(Duration.ofSeconds(4)), (requestContext, arguments, features) -> {
				admissionEntered.countDown(); awaitRelease(admissionRelease); return McpCompleteResult.fromToolText("done");
			})) {
				String id = fixture.initialize("/mcp", version);
				try (RawClient get = fixture.openControl("GET", "/mcp", version, id, "alice", "", List.of())) {
					assertSse(get.readHead());
					fixture.policy.set((context, features) -> {
						if (context.isReauthorization()) renewalEntries.incrementAndGet();
						return Fixture.allow(context, features);
					});
					callers.add(toolCaller(fixture, version, id, firstAdmission, failure));
					callers.get(0).start(); assertTrue(admissionEntered.await(5, TimeUnit.SECONDS));
					callers.add(toolCaller(fixture, version, id, secondAdmission, failure)); callers.get(1).start();
					await(() -> fixture.server.getDiagnostics().getRequestHandlerQueueDepth() == 1);
					fixture.server.getSubscriptionReconciler().reconcileSubscriptions();
					await(() -> maintenance(fixture, McpMetricsEvent.SubscriptionMaintenance.Outcome.CAPACITY_REJECTED));
					assertEquals(0, renewalEntries.get(), "The full dispatcher must suppress application entry.");
					assertEquals(1, fixture.server.getDiagnostics().getActiveSubscriptions(),
							"Temporary capacity cannot retire the still-live fenced GET lease.");
					assertNotNull(get.readChunk(), "Keepalive remains available while notification authorization is fenced.");
					admissionRelease.countDown();
					for (Thread caller : callers) { caller.join(5000); assertFalse(caller.isAlive()); }
					assertNull(failure.get()); assertEquals(200, firstAdmission.get().status()); assertEquals(200, secondAdmission.get().status());
					await(() -> renewalEntries.get() >= 1
							&& maintenance(fixture, McpMetricsEvent.SubscriptionMaintenance.Outcome.SUCCEEDED));
					assertEquals(1, fixture.server.getDiagnostics().getActiveSubscriptions());
					assertEquals(1, fixture.events.stream().filter(McpMetricsEvent.SubscriptionOpened.class::isInstance).count(),
							"Retry renews the same GET instead of opening a replacement.");
					assertEquals(0, fixture.events.stream().filter(McpMetricsEvent.SubscriptionClosed.class::isInstance).count());
				} finally {
					admissionRelease.countDown();
					for (Thread caller : callers) { caller.interrupt(); caller.join(5000); }
				}
			} finally { admissionRelease.countDown(); }
		}
	}

	@Test
	void four_ignoring_renewals_hold_global_maintenance_capacity_after_their_gets_expire() throws Exception {
		for (McpProtocolVersion version : LEGACY) {
			CountDownLatch entered = new CountDownLatch(4);
			CountDownLatch release = new CountDownLatch(1);
			AtomicInteger renewalEntries = new AtomicInteger();
			List<RawClient> streams = new ArrayList<>();
			List<Head> heads = new ArrayList<>();
			try (Fixture fixture = new Fixture(true, builder -> builder
					.requestHandlerConcurrency(8).requestHandlerQueueCapacity(8)
					.maximumSubscriptionAuthorizationDuration(Duration.ofSeconds(2))
					.maximumSubscriptionDuration(Duration.ofSeconds(6))
					.subscriptionAuthorizationTimeout(Duration.ofSeconds(4)))) {
				fixture.policy.set((context, features) -> {
					if (context.isReauthorization()) {
						renewalEntries.incrementAndGet(); entered.countDown(); awaitRelease(release);
					}
					return Fixture.allow(context, features);
				});
				try {
					for (int index = 0; index < 5; index++) {
						String id = fixture.initialize("/mcp", version);
						RawClient stream = fixture.openControl("GET", "/mcp", version, id, "alice", "", List.of());
						streams.add(stream); Head head = stream.readHead(); heads.add(head); assertSse(head);
					}
					fixture.server.getSubscriptionReconciler().reconcileSubscriptions();
					assertTrue(entered.await(5, TimeUnit.SECONDS));
					for (int index = 0; index < streams.size(); index++) {
						assertFalse(streams.get(index).readBody(heads.get(index)).contains("data:"));
						assertTrue(streams.get(index).terminalRead);
					}
					await(() -> fixture.server.getDiagnostics().getActiveSubscriptions() == 0);
					assertEquals(4, renewalEntries.get());
					assertEquals(4, fixture.server.getDiagnostics().getActiveHandlerExecutions(),
							"Expired streams must not release ignoring callbacks' physical maintenance slots.");
					String fresh = fixture.initialize("/mcp", version);
					Response samePartition = fixture.control("GET", "/mcp", version, fresh, "alice", "", List.of());
					assertEquals(503, samePartition.status(), "All four physical control reservations remain charged to this partition.");
					assertNull(samePartition.header("Retry-After"));
					fixture.policy.set((context, features) -> {
						if (context.isReauthorization()) {
							renewalEntries.incrementAndGet(); entered.countDown(); awaitRelease(release);
						}
						return McpSessionTransportAdmissionDecision.accepted(
								McpAdmissionIdentity.withRateLimitPartitionKey("shared-quota")
										.authorizationPartitionKey("other-authorization").principal("alice").build(),
								Instant.now().plusSeconds(30), context.getNotificationTypes());
					});
					try (RawClient later = fixture.openControl("GET", "/mcp", version, fresh, "alice", "", List.of())) {
						Head head = later.readHead(); assertSse(head);
						assertFalse(later.readBody(head).contains("data:")); assertTrue(later.terminalRead);
					}
					assertEquals(4, renewalEntries.get(), "Logical EOF cannot admit a fifth physical maintenance callback.");
					release.countDown();
					await(() -> fixture.server.getDiagnostics().getActiveHandlerExecutions() == 0);
					await(() -> fixture.server.getDiagnostics().getActiveRequestStreams() == 0);
				} finally {
					release.countDown(); for (RawClient stream : streams) stream.close();
				}
			} finally { release.countDown(); }
		}
	}

	private static Thread toolCaller(Fixture fixture, McpProtocolVersion version, String id,
			AtomicReference<Response> result, AtomicReference<Throwable> failure) {
		return new Thread(() -> {
			try { result.set(fixture.control("POST", "/mcp", version, id, "alice",
					"{\"jsonrpc\":\"2.0\",\"id\":\"" + Thread.currentThread().getName() + "\",\"method\":\"tools/call\",\"params\":{\"name\":\"busy\",\"arguments\":{}}}", List.of())); }
			catch (Throwable throwable) { failure.compareAndSet(null, throwable); }
		}, "mcp-legacy-handler-capacity-" + System.nanoTime());
	}
	private static boolean maintenance(Fixture fixture, McpMetricsEvent.SubscriptionMaintenance.Outcome outcome) {
		return fixture.events.stream().filter(McpMetricsEvent.SubscriptionMaintenance.class::isInstance)
				.map(McpMetricsEvent.SubscriptionMaintenance.class::cast)
				.anyMatch(event -> event.getOutcome() == outcome);
	}
	private static void assertSse(Head head) {
		assertEquals(200, head.status()); assertEquals("text/event-stream", head.header("Content-Type"));
		assertEquals("chunked", head.header("Transfer-Encoding"));
	}
	private static void await(BooleanSupplier condition) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (!condition.getAsBoolean() && System.nanoTime() - deadline < 0L) Thread.sleep(5);
		assertTrue(condition.getAsBoolean(), "Expected bounded HTTP maintenance transition did not complete.");
	}
	private static void awaitRelease(CountDownLatch latch) {
		boolean interrupted = false;
		try {
			for (;;) {
				try {
					if (!latch.await(15, TimeUnit.SECONDS)) throw new AssertionError("Missing test release.");
					return;
				} catch (InterruptedException ignored) { interrupted = true; }
			}
		} finally { if (interrupted) Thread.currentThread().interrupt(); }
	}
}
