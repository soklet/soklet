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
package com.soklet.internal.mcp.protocol;

import com.soklet.CorsAuthorizer;
import com.soklet.McpMetricsEvent;
import com.soklet.McpProtocolVersion;
import com.soklet.McpRequestContext;
import com.soklet.McpRequestOutcome;
import com.soklet.McpSessionConfig;
import com.soklet.McpStreamTerminationReason;
import com.soklet.McpSubscriptionAuthorization;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP/session coverage with independently controlled dispatch and lease time. */
@Timeout(180)
class McpLegacySubscriptionAuthorizationDeadlineTests {
	private static final String JUNE_REVISION = "2025-06-18";
	private static final String NOVEMBER_REVISION = "2025-11-25";
	private static final String URI = "test:///exact";
	private static final Duration AUTHORIZATION_TIMEOUT = Duration.ofMillis(50);
	private static final Duration LEASE_DURATION = Duration.ofMillis(600);

	@Test
	void ignoredTimedOutJuneAuthorizationCannotExtendItsLeaseOrOverlapAnotherCallback() throws Exception {
		ignoredTimedOutUriAuthorizationCannotExtendItsLeaseOrOverlapAnotherCallback(JUNE_REVISION);
	}

	@Test
	void ignoredTimedOutNovemberAuthorizationCannotExtendItsLeaseOrOverlapAnotherCallback() throws Exception {
		ignoredTimedOutUriAuthorizationCannotExtendItsLeaseOrOverlapAnotherCallback(NOVEMBER_REVISION);
	}

	@Test
	void expiredQueuedJuneAuthorizationNeverInvokesTheCallback() throws Exception {
		authorizationThatExpiresBeforeExecutorEntryNeverInvokesTheCallback(JUNE_REVISION);
	}

	@Test
	void expiredQueuedNovemberAuthorizationNeverInvokesTheCallback() throws Exception {
		authorizationThatExpiresBeforeExecutorEntryNeverInvokesTheCallback(NOVEMBER_REVISION);
	}

	private void ignoredTimedOutUriAuthorizationCannotExtendItsLeaseOrOverlapAnotherCallback(String revision) throws Exception {
		try (Fixture fixture = new Fixture(revision)) {
			String session = fixture.initializeAndSubscribe();
			fixture.executor.arm(true);
			fixture.runtime.reconcileSubscriptions();
			fixture.runtime.runApplicationTimerCycle();
			await(fixture.executor.scheduled);
			// Dispatch is deliberately held longer than the callback's 50 ms budget.
			// The injected clock stays fixed; execute() acknowledges entry before
			// the runtime begins its bounded wait. Host scheduling cannot consume it.
			assertFalse(fixture.entered.await(100, TimeUnit.MILLISECONDS));
			fixture.executor.releaseDispatch.countDown();
			await(fixture.entered);
			try (McpChunkedHttpClient get = fixture.listen(session)) {
				fixture.clock.advance(AUTHORIZATION_TIMEOUT);
				await(fixture.canceled);
				await(fixture.observer.timedOut);
				assertTrue(fixture.running.get());
				assertEquals(2, fixture.calls.get());

				fixture.runtime.reconcileSubscriptions();
				fixture.clock.advance(LEASE_DURATION.minus(AUTHORIZATION_TIMEOUT));
				fixture.runtime.runApplicationTimerCycle();
				assertNull(get.readChunk(), "The GET must close at the original lease expiry.");
				await(fixture.observer.closed);
				assertEquals(McpStreamTerminationReason.SUBSCRIPTION_AUTHORIZATION_EXPIRED,
						fixture.observer.closeReason.get());
				fixture.assertSessionMissing(session);
				assertEquals(2, fixture.calls.get(), "The ignored callback retains the per-URI authorization slot.");
				assertTrue(fixture.running.get(), "Logical timeout and lease loss must not complete physical work.");

				fixture.releaseCallback.countDown();
				await(fixture.exited);
				awaitCondition(() -> fixture.runtime.applicationExecutionSnapshot().orElseThrow().activeHandlerSlots() == 0);
				fixture.clock.advance(Duration.ofSeconds(1));
				fixture.runtime.runApplicationTimerCycle();
				fixture.assertSessionMissing(session);
				assertEquals(2, fixture.calls.get(), "A late Allowed result must not extend or resurrect the expired grant.");
			}
		}
	}

	private void authorizationThatExpiresBeforeExecutorEntryNeverInvokesTheCallback(String revision) throws Exception {
		try (Fixture fixture = new Fixture(revision)) {
			String session = fixture.initializeAndSubscribe();
			fixture.executor.arm(false);
			fixture.runtime.reconcileSubscriptions();
			fixture.runtime.runApplicationTimerCycle();
			await(fixture.executor.scheduled);
			fixture.clock.advance(AUTHORIZATION_TIMEOUT);
			await(fixture.observer.timedOut);
			assertEquals(1, fixture.calls.get());
			fixture.executor.releaseDispatch.countDown();
			await(fixture.executor.finished);
			assertEquals(1, fixture.calls.get(), "An expired queued check must never enter application code.");
			try (McpChunkedHttpClient get = fixture.listen(session)) {
				fixture.clock.advance(LEASE_DURATION.minus(AUTHORIZATION_TIMEOUT));
				fixture.runtime.runApplicationTimerCycle();
				assertNull(get.readChunk());
				fixture.assertSessionMissing(session);
				assertEquals(1, fixture.calls.get());
			}
		}
	}

	private static final class Fixture implements AutoCloseable {
		private final String revision;
		private final Clock clock = new Clock();
		private final AtomicInteger calls = new AtomicInteger();
		private final AtomicBoolean running = new AtomicBoolean();
		private final CountDownLatch entered = new CountDownLatch(1);
		private final CountDownLatch canceled = new CountDownLatch(1);
		private final CountDownLatch releaseCallback = new CountDownLatch(1);
		private final CountDownLatch exited = new CountDownLatch(1);
		private final DispatchExecutor executor = new DispatchExecutor(entered);
		private final Observer observer = new Observer();
		private final McpHttpServerRuntime runtime;
		private final int port;

		private Fixture(String revision) throws Exception {
			this.revision = revision;
			McpNormalizedEndpoint endpoint = McpNormalizedEndpoint.withServerInformation(
					McpImplementationMetadata.withNameAndVersion("legacy-deadline", "test"))
					.exactResource(URI).subscriptionConfig(McpNormalizedSubscriptionConfiguration.supporting(
							McpResourceNotificationType.RESOURCE_UPDATED)).build();
			McpApplicationRequestRouter router = McpApplicationRequestRouter.fromResourceRoutes(
					Map.of(URI, new McpApplicationResourceReadRoute(invocation -> {
						throw new AssertionError("Subscribe must not invoke the read handler.");
					})), List.of(), Optional.empty());
			McpHttpEndpointBinding binding = new McpHttpEndpointBinding(
					McpHttpEndpointPolicy.forDiscovery(CorsAuthorizer.rejectAllInstance(),
							ignored -> McpAdmissionDecision.acceptedAnonymous()), endpoint, router,
					input -> new McpRuntimeRequestObservation() {
						@Override public Optional<McpRequestContext> publicContext() {
							return Optional.of(context(input));
						}
						@Override public void didFinish(McpRequestOutcome outcome, McpJsonRpcError error,
								Duration duration, List<Throwable> failures) {}
					}, List.of(new McpSubscriptionEventSource(new Object(), listener -> () -> {})),
					Optional.empty(), Map.of(revision, endpoint));
			McpSubscriptionRuntimeConfiguration subscriptions = new McpSubscriptionRuntimeConfiguration(
					8, Duration.ofSeconds(30), Duration.ofSeconds(15), Duration.ofSeconds(5), 2,
					Duration.ofSeconds(20), Duration.ofSeconds(5), AUTHORIZATION_TIMEOUT, LEASE_DURATION,
					Optional.of((context, features) -> {
						int invocation = calls.incrementAndGet();
						if (invocation > 1) {
							assertEquals(clock.instant().plus(LEASE_DURATION), context.getPreviousValidUntil().orElseThrow());
							assertTrue(running.compareAndSet(false, true), "Authorizer callbacks must not overlap.");
							features.getCancelationToken().onCancel(canceled::countDown);
							entered.countDown();
							try { awaitIgnoringInterrupts(releaseCallback); }
							finally { running.set(false); exited.countDown(); }
						}
						return McpSubscriptionAuthorization.Allowed.fromValidUntil(clock.instant().plusSeconds(30));
					}));
			this.runtime = new McpHttpServerRuntime(McpHttpTransportConfiguration.productionDefaults(0),
					List.of(binding), McpJsonLimits.productionDefaults(),
					new McpApplicationExecutionConfiguration(4, 16, Duration.ofSeconds(4), Duration.ofDays(1)),
					clock, concurrency -> executor, ignored -> {}, ignored -> {}, Optional.empty(),
					McpFrameworkRequestStateRuntime.disabledInstance(), subscriptions, observer);
			runtime.configureLegacySessions(Optional.of(McpSessionConfig.withOwnerKeyResolver(identity -> "owner")
					.anonymousSessionsAllowed(true).build()), Map.of("/mcp", Set.of(revision)), identity -> "owner");
			runtime.configureLegacySessionTransport(Optional.of((request, path, version, types, renewal, deadline, cancellation) -> {
				return new McpLegacySessionTransportAdmission.Accepted(McpAdmissionIdentity.anonymousInstance(),
						clock.instant().plusSeconds(30), types);
			}),
					Map.of("/mcp", Map.of(revision, Set.of(McpResourceNotificationType.RESOURCE_UPDATED))));
			try { this.port = runtime.start().getPort(); }
			catch (Throwable failure) { close(); throw failure; }
		}

		private String initializeAndSubscribe() throws Exception {
			try (McpChunkedHttpClient client = post(null, "initialize", "{\"protocolVersion\":\"" + revision
					+ "\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}")) {
				var head = client.readHead(); assertEquals(200, head.status()); client.readFixedBody(head);
				String session = head.singleHeader("Mcp-Session-Id");
				try (McpChunkedHttpClient subscribe = post(session, "resources/subscribe", "{\"uri\":\"" + URI + "\"}")) {
					var subscribed = subscribe.readHead(); assertEquals(200, subscribed.status());
					String body = subscribe.readFixedBody(subscribed);
					assertTrue(body.contains("\"result\":{}"), body);
				}
				assertEquals(1, calls.get());
				awaitCondition(() -> runtime.applicationExecutionSnapshot().orElseThrow().activeHandlerSlots() == 0);
				return session;
			}
		}

		private McpChunkedHttpClient listen(String session) throws Exception {
			McpChunkedHttpClient client = post(session, "ping", "{}");
			try {
				var ping = client.readHead(); assertEquals(200, ping.status()); client.readFixedBody(ping);
				client.writeRequest("GET", "/mcp", "127.0.0.1:" + port, "", headers(session));
				assertEquals(200, client.readHead().status());
				return client;
			} catch (Throwable failure) { client.close(); throw failure; }
		}

		private void assertSessionMissing(String session) throws Exception {
			try (McpChunkedHttpClient client = post(session, "ping", "{}")) {
				assertEquals(404, client.readHead().status(), "Expired authorization requires fresh initialization.");
			}
		}

		private McpChunkedHttpClient post(String session, String method, String params) throws Exception {
			return McpChunkedHttpClient.postMcpMessage(port, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\""
					+ method + "\",\"params\":" + params + "}", headers(session));
		}

		private List<McpChunkedHttpClient.RequestHeader> headers(String session) {
			var version = new McpChunkedHttpClient.RequestHeader("MCP-Protocol-Version", revision);
			return session == null ? List.of(version) : List.of(version,
					new McpChunkedHttpClient.RequestHeader("Mcp-Session-Id", session));
		}

		@Override public void close() {
			executor.releaseDispatch.countDown(); releaseCallback.countDown();
			try { runtime.close(); }
			finally {
				executor.shutdownNow();
				try { assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)); }
				catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
			}
		}
	}

	private static final class Clock implements McpApplicationClock {
		private final AtomicLong now = new AtomicLong();
		@Override public long nanoTime() { return now.get(); }
		@Override public Instant instant() { return Instant.EPOCH.plusNanos(now.get()); }
		private void advance(Duration duration) { now.addAndGet(duration.toNanos()); }
	}

	/** Keeps scheduling and callback entry independent of the wall-time policy wait. */
	private static final class DispatchExecutor extends AbstractExecutorService {
		private final ExecutorService delegate = Executors.newVirtualThreadPerTaskExecutor();
		private final CountDownLatch callbackEntered;
		private final AtomicBoolean armed = new AtomicBoolean();
		private final CountDownLatch scheduled = new CountDownLatch(1);
		private final CountDownLatch releaseDispatch = new CountDownLatch(1);
		private final CountDownLatch finished = new CountDownLatch(1);
		private volatile boolean acknowledgeEntry;
		private DispatchExecutor(CountDownLatch callbackEntered) { this.callbackEntered = callbackEntered; }
		private void arm(boolean acknowledgeEntry) { this.acknowledgeEntry = acknowledgeEntry; armed.set(true); }
		@Override public void execute(Runnable command) {
			boolean gated = armed.compareAndSet(true, false);
			AtomicReference<Thread> worker = new AtomicReference<>();
			CountDownLatch completed = new CountDownLatch(1);
			delegate.execute(() -> {
				worker.set(Thread.currentThread());
				try {
					if (gated) { scheduled.countDown(); awaitIgnoringInterrupts(releaseDispatch); }
					command.run();
				} finally { completed.countDown(); if (gated) finished.countDown(); }
			});
			if (!gated) {
				// The policy publishes its logical result before waiting here for
				// its submitting caller to release the callback chain. Waiting for
				// command.run() itself to finish would deadlock execute() and that
				// caller. This stable parked frame proves result publication, so
				// even a long host deschedule cannot consume the 50 ms test budget.
				try { awaitCondition(() -> completed.getCount() == 0 || resultPublished(worker.get())); }
				catch (InterruptedException failure) {
					Thread.currentThread().interrupt(); throw new AssertionError(failure);
				}
			}
			else if (acknowledgeEntry) awaitIgnoringInterrupts(callbackEntered);
		}
		private static boolean resultPublished(Thread worker) {
			if (worker == null) return false;
			for (StackTraceElement frame : worker.getStackTrace())
				if (frame.getClassName().equals(McpApplicationExecution.BoundedPolicyCancellation.class.getName())
						&& frame.getMethodName().equals("awaitCallbackChainRelease")) return true;
			return false;
		}

		@Override public void shutdown() { delegate.shutdown(); }
		@Override public List<Runnable> shutdownNow() { releaseDispatch.countDown(); return delegate.shutdownNow(); }
		@Override public boolean isShutdown() { return delegate.isShutdown(); }
		@Override public boolean isTerminated() { return delegate.isTerminated(); }
		@Override public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
			return delegate.awaitTermination(timeout, unit);
		}
	}

	private static final class Observer implements McpApplicationExecutionObserver {
		private final CountDownLatch timedOut = new CountDownLatch(1);
		private final CountDownLatch closed = new CountDownLatch(1);
		private final AtomicReference<McpStreamTerminationReason> closeReason = new AtomicReference<>();
		@Override public void recordSubscriptionMaintenance(String path, McpMetricsEvent.SubscriptionMaintenance.Work work,
				McpMetricsEvent.SubscriptionMaintenance.Outcome outcome) {
			if (outcome == McpMetricsEvent.SubscriptionMaintenance.Outcome.TIMED_OUT) timedOut.countDown();
		}
		@Override public void recordSubscriptionClosed(String path, McpStreamTerminationReason reason, Duration duration) {
			closeReason.set(reason); closed.countDown();
		}
		@Override public void beginDeferral() {}
		@Override public void endDeferral() {}
		@Override public void drain() {}
		@Override public void recordHandlerExecutionStarted() {}
		@Override public void recordHandlerExecutionFinished() {}
		@Override public void recordHandlerQueued() {}
		@Override public void recordHandlerDequeued() {}
		@Override public void recordHandlerCapacityRejected() {}
	}

	private static McpRequestContext context(McpRuntimeRequestInput input) {
		return (McpRequestContext) Proxy.newProxyInstance(McpRequestContext.class.getClassLoader(),
				new Class<?>[]{McpRequestContext.class}, (proxy, method, args) -> switch (method.getName()) {
			case "getRequest" -> input.request();
			case "getJsonRpcMethod" -> input.jsonRpcMethod();
			case "getProtocolVersion" -> McpProtocolVersion.fromWireValue(input.protocolVersion()).orElseThrow();
			case "getAdmissionIdentity" -> com.soklet.McpAdmissionIdentity.anonymousInstance();
			case "getEndpointPathParameters", "getBaggage" -> Map.of();
			case "getRequestId", "getOperationName", "getClientInfo", "getFrameworkRequestState",
					"getApplicationRequestState", "getTraceContext" -> Optional.empty();
			case "toString" -> "LegacySubscriptionDeadlineContext";
			case "hashCode" -> System.identityHashCode(proxy);
			case "equals" -> proxy == args[0];
			default -> throw new AssertionError("Unexpected context method: " + method);
		});
	}

	private static void await(CountDownLatch latch) throws InterruptedException {
		assertTrue(latch.await(5, TimeUnit.SECONDS), "The controlled transition did not complete.");
	}

	private static void awaitCondition(BooleanSupplier condition) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (!condition.getAsBoolean() && System.nanoTime() - deadline < 0L) Thread.sleep(1);
		assertTrue(condition.getAsBoolean(), "Physical callback ownership was not released.");
	}

	private static void awaitIgnoringInterrupts(CountDownLatch latch) {
		boolean interrupted = false;
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		try {
			while (latch.getCount() != 0) {
				long remaining = deadline - System.nanoTime();
				assertTrue(remaining > 0, "The test did not release its callback or executor.");
				try { latch.await(remaining, TimeUnit.NANOSECONDS); }
				catch (InterruptedException ignored) { interrupted = true; }
			}
		} finally { if (interrupted) Thread.currentThread().interrupt(); }
	}
}
