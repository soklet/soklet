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
import com.soklet.LifecyclePolicy;
import com.soklet.McpAdmissionDecision;
import com.soklet.McpAdmissionContext;
import com.soklet.McpAdmissionIdentity;
import com.soklet.McpCompleteResult;
import com.soklet.McpEndpoint;
import com.soklet.McpEndpointRegistry;
import com.soklet.McpImplementation;
import com.soklet.McpInvocationFeatures;
import com.soklet.McpLocalizationContext;
import com.soklet.McpMetricsEvent;
import com.soklet.McpRateLimitDecision;
import com.soklet.McpRequestContext;
import com.soklet.McpResourceOutput;
import com.soklet.McpResourceRegistration;
import com.soklet.McpServer;
import com.soklet.McpSubscriptionAuthorization;
import com.soklet.McpSubscriptionAuthorizationContext;
import com.soklet.McpSubscriptionAuthorizer;
import com.soklet.McpSubscriptionConfig;
import com.soklet.McpSubscriptionEventPublisher;
import com.soklet.McpSubscriptionNotificationType;
import com.soklet.McpStreamTerminationReason;
import com.soklet.McpTextResourceContents;
import com.soklet.MetricsCollector;
import com.soklet.ResourceMethodResolver;
import com.soklet.Soklet;
import com.soklet.SokletConfig;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.annotation.concurrent.ThreadSafe;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Black-box authorization and local-reconciliation coverage for MCP
 * subscriptions.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Timeout(60)
public class McpSubscriptionAuthorizationPublicRuntimeTests {
	private static final String LOOPBACK = "127.0.0.1";
	private static final String MCP_PATH = "/mcp";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final URI FIRST_RESOURCE_URI =
			URI.create("test://subscription/authorization/first");
	private static final URI SECOND_RESOURCE_URI =
			URI.create("test://subscription/authorization/second");

	@Test
	public void initialAuthorizationPrecedesAcknowledgmentAndExposesImmutableCandidates()
			throws Exception {
		Object admissionApplicationContext = new Object();
		Object replacementApplicationContext = new Object();
		CountDownLatch authorizerEntered = new CountDownLatch(1);
		CountDownLatch releaseAuthorizer = new CountDownLatch(1);
		AtomicReference<McpSubscriptionAuthorizationContext> observedContext =
				new AtomicReference<>();
		AtomicReference<McpAdmissionContext> observedAdmission =
				new AtomicReference<>();
		AtomicReference<McpInvocationFeatures> observedFeatures =
				new AtomicReference<>();
		AtomicReference<Thread> authorizerThread = new AtomicReference<>();
		McpSubscriptionAuthorizer authorizer = (context, features) -> {
			authorizerThread.set(Thread.currentThread());
			observedContext.set(context);
			observedFeatures.set(features);
			authorizerEntered.countDown();
			Assertions.assertTrue(releaseAuthorizer.await(5, TimeUnit.SECONDS),
					"The initial authorizer was not released.");
			return allowed(replacementApplicationContext);
		};
		McpSubscriptionEventPublisher publisher =
				McpSubscriptionEventPublisher.fromInMemoryDefaults();
		McpServer server = server(publisher, authorizer,
				admissionApplicationContext, observedAdmission::set);
		Soklet owner = managedSoklet(server,
				MetricsCollector.disabledInstance());
		ExecutorService reader = Executors.newSingleThreadExecutor();
		McpChunkedHttpClient client = null;
		Future<McpChunkedHttpClient.HttpResponseHead> responseHead = null;

		try {
			owner.start();
			client = listen(boundPort(server), "\"initial-allow\"");
			McpChunkedHttpClient activeClient = client;
			responseHead = reader.submit(activeClient::readHead);
			Assertions.assertTrue(authorizerEntered.await(5, TimeUnit.SECONDS),
					"The initial authorizer did not run.");
			Assertions.assertFalse(responseHead.isDone(),
					"A subscription acknowledgment escaped before authorization.");
			McpAdmissionContext admission = observedAdmission.get();
			Assertions.assertNotNull(admission);
			Assertions.assertFalse(admission.isToolsListChangedIncluded());
			Assertions.assertFalse(admission.isPromptsListChangedIncluded());
			Assertions.assertTrue(admission.isResourcesListChangedIncluded());
			Assertions.assertTrue(admission.isResourceSubscriptionsIncluded());
			Assertions.assertEquals(List.of(FIRST_RESOURCE_URI, SECOND_RESOURCE_URI),
					admission.getRequestedResourceSubscriptionUris());
			Assertions.assertFalse(admission.isTaskIdsRequested());
			Assertions.assertTrue(admission.getRequestedTaskIds().isEmpty());
			Assertions.assertThrows(UnsupportedOperationException.class,
					() -> admission.getRequestedResourceSubscriptionUris()
							.add(URI.create("test://subscription/mutated")));
			McpSubscriptionAuthorizationContext context = observedContext.get();
			Assertions.assertNotNull(context);
			Assertions.assertSame(admissionApplicationContext,
					context.getApplicationContext().orElseThrow());
			Assertions.assertTrue(context.getPreviousValidUntil().isEmpty());
			Assertions.assertTrue(context.getDeadline().isAfter(Instant.now()));
			Assertions.assertEquals(Boolean.FALSE,
					context.isToolsListChangedIncluded());
			Assertions.assertEquals(Boolean.FALSE,
					context.isPromptsListChangedIncluded());
			Assertions.assertEquals(Boolean.TRUE,
					context.isResourcesListChangedIncluded());
			Assertions.assertEquals(Set.of(FIRST_RESOURCE_URI,
					SECOND_RESOURCE_URI), context.getResourceSubscriptionUris());
			Assertions.assertTrue(context.getTaskIds().isEmpty());
			Assertions.assertThrows(UnsupportedOperationException.class,
					() -> context.getResourceSubscriptionUris().add(
							URI.create("test://subscription/authorization/mutated")));
			Assertions.assertThrows(UnsupportedOperationException.class,
					() -> context.getTaskIds().add("mutated"));
			McpInvocationFeatures features = observedFeatures.get();
			Assertions.assertNotNull(features);
			Assertions.assertNotNull(features.getCancelationToken());
			Assertions.assertTrue(features.getProgressReporter().isEmpty());
			Assertions.assertTrue(features.getTaskCreationContext().isEmpty());
			Assertions.assertTrue(features.find(McpLocalizationContext.class)
					.isEmpty());
			McpRequestContext initialRequest = context.getInitialRequestContext();
			Assertions.assertEquals("Bearer admission-secret",
					initialRequest.getRequest().getHeader("Authorization")
							.orElseThrow());
			Assertions.assertSame(admissionApplicationContext,
					initialRequest.getAdmissionIdentity().getApplicationContext()
							.orElseThrow());
			Assertions.assertNotSame(Thread.currentThread(), authorizerThread.get());
			releaseAuthorizer.countDown();

			assertSseHead(responseHead.get(5, TimeUnit.SECONDS));
			Assertions.assertEquals(acknowledgment("\"initial-allow\""),
					client.readChunkText());
			Assertions.assertEquals(1,
					server.getDiagnostics().getActiveSubscriptions());
		} finally {
			releaseAuthorizer.countDown();
			if (responseHead != null)
				responseHead.cancel(true);
			if (client != null)
				client.closeWithReset();
			owner.close();
			reader.shutdownNow();
			Assertions.assertTrue(reader.awaitTermination(5, TimeUnit.SECONDS));
		}
	}

	@Test
	public void nullInitialAuthorizationFailsClosedWithoutQuotaLeak()
			throws Exception {
		assertFailedInitialAuthorizationDoesNotLeakQuota("null-result",
				(context, features) -> null);
	}

	@Test
	public void thrownInitialAuthorizationFailsClosedWithoutQuotaLeak()
			throws Exception {
		assertFailedInitialAuthorizationDoesNotLeakQuota("thrown-result",
				(context, features) -> {
					throw new IllegalStateException("Deliberate authorization failure");
				});
	}

	@Test
	public void alreadyExpiredInitialAuthorizationFailsClosedWithoutQuotaLeak()
			throws Exception {
		assertFailedInitialAuthorizationDoesNotLeakQuota("expired-result",
				(context, features) -> allowed(
						Instant.now().minusSeconds(1), null));
	}

	@Test
	public void establishingReconciliationDiscardsStaleGrantBeforeAcknowledgment()
			throws Exception {
		Object admissionApplicationContext = new Object();
		Object staleReplacementContext = new Object();
		Object freshReplacementContext = new Object();
		AtomicInteger authorizations = new AtomicInteger();
		AtomicInteger activeAuthorizers = new AtomicInteger();
		AtomicInteger maximumActiveAuthorizers = new AtomicInteger();
		AtomicReference<com.soklet.CancelationToken> staleToken =
				new AtomicReference<>();
		List<McpSubscriptionAuthorizationContext> contexts =
				new CopyOnWriteArrayList<>();
		CountDownLatch initialEntered = new CountDownLatch(1);
		CountDownLatch releaseInitial = new CountDownLatch(1);
		CountDownLatch cancellationHookEntered = new CountDownLatch(1);
		CountDownLatch releaseCancellationHook = new CountDownLatch(1);
		CountDownLatch cancellationHookExited = new CountDownLatch(1);
		CountDownLatch freshEntered = new CountDownLatch(1);
		CountDownLatch releaseFresh = new CountDownLatch(1);
		McpSubscriptionAuthorizer authorizer = (context, features) -> {
			int active = activeAuthorizers.incrementAndGet();
			maximumActiveAuthorizers.accumulateAndGet(active, Math::max);
			try {
				contexts.add(context);
				int invocation = authorizations.incrementAndGet();
				if (invocation == 1) {
					staleToken.set(features.getCancelationToken());
					features.getCancelationToken().onCancel(() -> {
						cancellationHookEntered.countDown();
						try {
							Assertions.assertTrue(releaseCancellationHook.await(
									5, TimeUnit.SECONDS),
									"The initial authorization cancellation hook was not released.");
						} catch (InterruptedException exception) {
							Thread.currentThread().interrupt();
							throw new AssertionError(exception);
						} finally {
							cancellationHookExited.countDown();
						}
					});
					initialEntered.countDown();
					while (releaseInitial.getCount() != 0L)
						try {
							releaseInitial.await(25, TimeUnit.MILLISECONDS);
						} catch (InterruptedException ignored) {
							// Return before the separately blocked cancellation hook.
						}
					return allowed(staleReplacementContext);
				}
				if (invocation == 2) {
					freshEntered.countDown();
					if (!releaseFresh.await(5, TimeUnit.SECONDS))
						throw new AssertionError(
								"The fresh authorizer was not released.");
					return allowed(freshReplacementContext);
				}
				throw new AssertionError(
						"Unexpected authorization invocation " + invocation);
			} finally {
				activeAuthorizers.decrementAndGet();
			}
		};
		RecordingMetrics metrics = new RecordingMetrics();
		McpSubscriptionEventPublisher publisher =
				McpSubscriptionEventPublisher.fromInMemoryDefaults();
		McpServer server = server(publisher, authorizer,
				admissionApplicationContext);
		Soklet owner = managedSoklet(server, metrics);
		ExecutorService reader = Executors.newSingleThreadExecutor();
		McpChunkedHttpClient client = null;
		Future<McpChunkedHttpClient.HttpResponseHead> responseHead = null;
		Future<String> eventFrame = null;

		try {
			owner.start();
			client = listen(boundPort(server), "\"establishing-reconcile\"");
			McpChunkedHttpClient activeClient = client;
			responseHead = reader.submit(activeClient::readHead);
			Assertions.assertTrue(initialEntered.await(5, TimeUnit.SECONDS),
					"The initial authorization did not enter.");
			Assertions.assertFalse(responseHead.isDone(),
					"The stream opened while initial authorization was blocked.");

			server.getSubscriptionReconciler().reconcileSubscriptions();
			metrics.awaitMaintenance(
					McpMetricsEvent.SubscriptionMaintenance.Work.RECONCILIATION,
					McpMetricsEvent.SubscriptionMaintenance.Outcome.COALESCED, 1);
			Assertions.assertTrue(staleToken.get().isCanceled(),
					"Reconciliation did not cancel the establishing generation.");
			Assertions.assertTrue(cancellationHookEntered.await(5, TimeUnit.SECONDS),
					"The establishing authorization cancellation hook did not enter.");
			publisher.publishResourceUpdated(FIRST_RESOURCE_URI);
			releaseInitial.countDown();
			Assertions.assertFalse(freshEntered.await(100, TimeUnit.MILLISECONDS),
					"Fresh initial authorization overlapped the canceled callback chain.");
			Assertions.assertEquals(1, authorizations.get());
			Assertions.assertFalse(responseHead.isDone(),
					"The stale authorization acknowledged before its cancel hook exited.");
			releaseCancellationHook.countDown();
			Assertions.assertTrue(cancellationHookExited.await(5, TimeUnit.SECONDS),
					"The establishing authorization cancellation hook did not exit.");

			Assertions.assertTrue(freshEntered.await(5, TimeUnit.SECONDS),
					"A fresh authorization generation did not run.");
			metrics.awaitMaintenance(
					McpMetricsEvent.SubscriptionMaintenance.Work.AUTHORIZATION,
					McpMetricsEvent.SubscriptionMaintenance.Outcome
							.STALE_RESULT_DISCARDED, 1);
			Assertions.assertFalse(responseHead.isDone(),
					"A stale pre-reconciliation grant acknowledged the stream.");
			Assertions.assertEquals(2, contexts.size());
			McpSubscriptionAuthorizationContext initial = contexts.get(0);
			McpSubscriptionAuthorizationContext fresh = contexts.get(1);
			Assertions.assertSame(initial.getInitialRequestContext(),
					fresh.getInitialRequestContext());
			Assertions.assertSame(admissionApplicationContext,
					fresh.getApplicationContext().orElseThrow(),
					"A stale replacement context reached the fresh generation.");
			Assertions.assertTrue(fresh.getPreviousValidUntil().isEmpty(),
					"A stale grant became historical authorization state.");
			Assertions.assertEquals(1, maximumActiveAuthorizers.get(),
					"Establishment ran overlapping authorization callbacks.");

			releaseFresh.countDown();
			assertSseHead(responseHead.get(5, TimeUnit.SECONDS));
			Assertions.assertEquals(acknowledgment(
					"\"establishing-reconcile\""), client.readChunkText());
			metrics.awaitMaintenance(
					McpMetricsEvent.SubscriptionMaintenance.Work.AUTHORIZATION,
					McpMetricsEvent.SubscriptionMaintenance.Outcome.SUCCEEDED, 1);
			Assertions.assertEquals(2, authorizations.get());

			eventFrame = reader.submit(activeClient::readChunkText);
			Assertions.assertFalse(eventFrame.isDone(),
					"An event published before activation was replayed.");
			publisher.publishResourceUpdated(SECOND_RESOURCE_URI);
			Assertions.assertEquals(resourceUpdated(
					"\"establishing-reconcile\"", SECOND_RESOURCE_URI),
					eventFrame.get(5, TimeUnit.SECONDS));
		} finally {
			releaseInitial.countDown();
			releaseCancellationHook.countDown();
			releaseFresh.countDown();
			if (responseHead != null)
				responseHead.cancel(true);
			if (eventFrame != null)
				eventFrame.cancel(true);
			if (client != null)
				client.closeWithReset();
			owner.close();
			reader.shutdownNow();
			Assertions.assertTrue(reader.awaitTermination(5, TimeUnit.SECONDS));
		}
	}

	@Test
	public void deniedInitialAuthorizationFailsClosedWithoutAcknowledgmentOrQuotaLeak()
			throws Exception {
		AtomicInteger authorizations = new AtomicInteger();
		McpSubscriptionAuthorizer authorizer = (context, features) ->
				authorizations.incrementAndGet() == 1
						? McpSubscriptionAuthorization.deniedInstance()
						: allowed(null);
		RecordingMetrics metrics = new RecordingMetrics();
		McpSubscriptionEventPublisher publisher =
				McpSubscriptionEventPublisher.fromInMemoryDefaults();
		McpServer server = server(publisher, authorizer, null);
		Soklet owner = managedSoklet(server, metrics);
		McpChunkedHttpClient admitted = null;

		try {
			owner.start();
			int port = boundPort(server);
			try (McpChunkedHttpClient denied = listen(port, "\"denied\"")) {
				McpChunkedHttpClient.HttpResponseHead head = denied.readHead();
				Assertions.assertNotEquals(200, head.status(), head.raw());
				String body = denied.readFixedBody(head);
				Assertions.assertFalse(body.contains(
						"notifications/subscriptions/acknowledged"), body);
			}
			metrics.awaitMaintenance(
					McpMetricsEvent.SubscriptionMaintenance.Work.AUTHORIZATION,
					McpMetricsEvent.SubscriptionMaintenance.Outcome.DENIED, 1);
			Assertions.assertEquals(0,
					server.getDiagnostics().getActiveSubscriptions());

			admitted = listen(port, "\"after-denial\"");
			assertSseHead(admitted.readHead());
			Assertions.assertEquals(acknowledgment("\"after-denial\""),
					admitted.readChunkText());
			Assertions.assertEquals(2, authorizations.get());
			Assertions.assertEquals(1,
					server.getDiagnostics().getActiveSubscriptions());
		} finally {
			if (admitted != null)
				admitted.closeWithReset();
			owner.close();
		}
	}

	@Test
	public void activeReconciliationFencesDeliveryCoalescesAndUsesFreshContext()
			throws Exception {
		Object admissionApplicationContext = new Object();
		Object firstReplacementContext = new Object();
		Object staleReplacementContext = new Object();
		AtomicInteger authorizations = new AtomicInteger();
		AtomicInteger activeAuthorizers = new AtomicInteger();
		AtomicInteger maximumActiveAuthorizers = new AtomicInteger();
		AtomicReference<com.soklet.CancelationToken> staleToken =
				new AtomicReference<>();
		List<McpSubscriptionAuthorizationContext> contexts =
				new CopyOnWriteArrayList<>();
		CountDownLatch reconciliationEntered = new CountDownLatch(1);
		CountDownLatch releaseReconciliation = new CountDownLatch(1);
		CountDownLatch cancellationHookEntered = new CountDownLatch(1);
		CountDownLatch releaseCancellationHook = new CountDownLatch(1);
		CountDownLatch cancellationHookExited = new CountDownLatch(1);
		CountDownLatch replacementEntered = new CountDownLatch(1);
		CountDownLatch clearedContextObserved = new CountDownLatch(1);
		McpSubscriptionAuthorizer authorizer = (context, features) -> {
			int active = activeAuthorizers.incrementAndGet();
			maximumActiveAuthorizers.accumulateAndGet(active, Math::max);
			try {
				contexts.add(context);
				int invocation = authorizations.incrementAndGet();
				if (invocation == 1) {
					Assertions.assertSame(admissionApplicationContext,
							context.getApplicationContext().orElseThrow());
					Assertions.assertTrue(context.getPreviousValidUntil().isEmpty());
					return allowed(firstReplacementContext);
				}
				Assertions.assertTrue(context.getPreviousValidUntil().isPresent());
				if (invocation == 2) {
					Assertions.assertSame(firstReplacementContext,
							context.getApplicationContext().orElseThrow());
					staleToken.set(features.getCancelationToken());
					features.getCancelationToken().onCancel(() -> {
						cancellationHookEntered.countDown();
						try {
							Assertions.assertTrue(releaseCancellationHook.await(
									5, TimeUnit.SECONDS),
									"The authorization cancellation hook was not released.");
						} catch (InterruptedException exception) {
							Thread.currentThread().interrupt();
							throw new AssertionError(exception);
						} finally {
							cancellationHookExited.countDown();
						}
					});
					reconciliationEntered.countDown();
					while (releaseReconciliation.getCount() != 0L)
						try {
							releaseReconciliation.await(25, TimeUnit.MILLISECONDS);
						} catch (InterruptedException ignored) {
							// Exercise a callback body that exits before its cancel hook.
						}
					return allowed(staleReplacementContext);
				}
				if (invocation == 3) {
					replacementEntered.countDown();
					Assertions.assertSame(firstReplacementContext,
							context.getApplicationContext().orElseThrow(),
							"A stale authorization result replaced current context.");
					return allowed(null);
				}
				if (invocation == 4) {
					Assertions.assertTrue(context.getApplicationContext().isEmpty(),
							"Omitting replacement context must clear the prior value.");
					clearedContextObserved.countDown();
					return allowed(null);
				}
				throw new AssertionError(
						"Unexpected authorization invocation " + invocation);
			} finally {
				activeAuthorizers.decrementAndGet();
			}
		};
		RecordingMetrics metrics = new RecordingMetrics();
		McpSubscriptionEventPublisher publisher =
				McpSubscriptionEventPublisher.fromInMemoryDefaults();
		McpServer server = server(publisher, authorizer,
				admissionApplicationContext);
		Soklet owner = managedSoklet(server, metrics);
		ExecutorService reader = Executors.newSingleThreadExecutor();
		McpChunkedHttpClient client = null;
		Future<String> nextFrame = null;

		try {
			owner.start();
			client = listen(boundPort(server), "\"reconcile\"");
			assertSseHead(client.readHead());
			Assertions.assertEquals(acknowledgment("\"reconcile\""),
					client.readChunkText());
			metrics.awaitMaintenance(
					McpMetricsEvent.SubscriptionMaintenance.Work.AUTHORIZATION,
					McpMetricsEvent.SubscriptionMaintenance.Outcome.SUCCEEDED, 1);

			server.getSubscriptionReconciler().reconcileSubscriptions();
			Assertions.assertTrue(reconciliationEntered.await(5, TimeUnit.SECONDS),
					"Reconciliation did not schedule authorization.");
			publisher.publishResourceUpdated(FIRST_RESOURCE_URI);
			McpChunkedHttpClient activeClient = client;
			nextFrame = reader.submit(activeClient::readChunkText);
			Assertions.assertFalse(nextFrame.isDone(),
					"Delivery was not fenced during reconciliation.");

			server.getSubscriptionReconciler().reconcileSubscriptions();
			Assertions.assertTrue(cancellationHookEntered.await(5, TimeUnit.SECONDS),
					"The superseded authorization cancellation hook did not enter.");
			metrics.awaitMaintenance(
					McpMetricsEvent.SubscriptionMaintenance.Work.RECONCILIATION,
					McpMetricsEvent.SubscriptionMaintenance.Outcome.COALESCED, 1);
			releaseReconciliation.countDown();
			metrics.awaitMaintenance(
					McpMetricsEvent.SubscriptionMaintenance.Work.AUTHORIZATION,
					McpMetricsEvent.SubscriptionMaintenance.Outcome
							.STALE_RESULT_DISCARDED, 1);
			Assertions.assertFalse(replacementEntered.await(100, TimeUnit.MILLISECONDS),
					"A replacement authorizer overlapped the canceled callback chain.");
			Assertions.assertEquals(2, authorizations.get());
			releaseCancellationHook.countDown();
			Assertions.assertTrue(cancellationHookExited.await(5, TimeUnit.SECONDS),
					"The authorization cancellation hook did not exit.");
			metrics.awaitMaintenance(
					McpMetricsEvent.SubscriptionMaintenance.Work.AUTHORIZATION,
					McpMetricsEvent.SubscriptionMaintenance.Outcome.SUCCEEDED, 2);
			Assertions.assertTrue(staleToken.get().isCanceled(),
					"Superseded reconciliation did not cancel its public token.");

			publisher.publishResourceUpdated(SECOND_RESOURCE_URI);
			Assertions.assertEquals(resourceUpdated("\"reconcile\"",
					SECOND_RESOURCE_URI), nextFrame.get(5, TimeUnit.SECONDS),
					"An event published under the fenced grant escaped after refresh.");
			Assertions.assertEquals(1, maximumActiveAuthorizers.get(),
					"One subscription ran overlapping authorization callbacks.");
			Assertions.assertEquals(3, authorizations.get());

			server.getSubscriptionReconciler().reconcileSubscriptions();
			Assertions.assertTrue(clearedContextObserved.await(5, TimeUnit.SECONDS),
					"The cleared application context was not observed.");
			metrics.awaitMaintenance(
					McpMetricsEvent.SubscriptionMaintenance.Work.AUTHORIZATION,
					McpMetricsEvent.SubscriptionMaintenance.Outcome.SUCCEEDED, 3);
			Assertions.assertEquals(4, contexts.size());
			McpRequestContext initial = contexts.get(0).getInitialRequestContext();
			for (McpSubscriptionAuthorizationContext context : contexts)
				Assertions.assertSame(initial, context.getInitialRequestContext(),
						"Renewal replaced the historical admission context.");
		} finally {
			releaseReconciliation.countDown();
			releaseCancellationHook.countDown();
			if (nextFrame != null)
				nextFrame.cancel(true);
			if (client != null)
				client.closeWithReset();
			owner.close();
			reader.shutdownNow();
			Assertions.assertTrue(reader.awaitTermination(5, TimeUnit.SECONDS));
		}
	}

	@Test
	public void maximumAuthorizationDurationClipsTheApplicationLease()
			throws Exception {
		Duration maximumAuthorizationDuration = Duration.ofSeconds(2);
		AtomicInteger authorizations = new AtomicInteger();
		AtomicReference<Instant> requestedValidUntil = new AtomicReference<>();
		AtomicReference<Instant> renewalObservedAt = new AtomicReference<>();
		AtomicReference<McpSubscriptionAuthorizationContext> renewalContext =
				new AtomicReference<>();
		CountDownLatch renewalEntered = new CountDownLatch(1);
		McpSubscriptionAuthorizer authorizer = (context, features) -> {
			int invocation = authorizations.incrementAndGet();
			if (invocation == 1) {
				Instant validUntil = Instant.now().plusSeconds(30);
				requestedValidUntil.set(validUntil);
				return allowed(validUntil, null);
			}
			if (invocation == 2) {
				renewalObservedAt.set(Instant.now());
				renewalContext.set(context);
				renewalEntered.countDown();
				return allowed(context.getPreviousValidUntil().orElseThrow(), null);
			}
			throw new AssertionError(
					"Unexpected authorization invocation " + invocation);
		};
		RecordingMetrics metrics = new RecordingMetrics();
		McpSubscriptionEventPublisher publisher =
				McpSubscriptionEventPublisher.fromInMemoryDefaults();
		McpServer server = server(publisher, authorizer, null,
				Duration.ofSeconds(5), maximumAuthorizationDuration,
				Duration.ofSeconds(30));
		Soklet owner = managedSoklet(server, metrics);
		McpChunkedHttpClient client = null;

		try {
			owner.start();
			client = listen(boundPort(server), "\"maximum-lease\"");
			assertSseHead(client.readHead());
			Assertions.assertEquals(acknowledgment("\"maximum-lease\""),
					client.readChunkText());
			Assertions.assertTrue(renewalEntered.await(5, TimeUnit.SECONDS),
					"The server-capped authorization did not renew.");
			metrics.awaitMaintenance(
					McpMetricsEvent.SubscriptionMaintenance.Work.AUTHORIZATION,
					McpMetricsEvent.SubscriptionMaintenance.Outcome.SUCCEEDED, 2);

			Instant previousValidUntil = renewalContext.get()
					.getPreviousValidUntil().orElseThrow();
			Assertions.assertTrue(previousValidUntil.isBefore(
					requestedValidUntil.get()),
					"The application lease was not capped by the server maximum.");
			Duration remainingAtRenewal = Duration.between(
					renewalObservedAt.get(), previousValidUntil);
			Assertions.assertFalse(remainingAtRenewal.isNegative(),
					"Renewal began after the effective authorization expired.");
			Assertions.assertTrue(remainingAtRenewal.compareTo(
					maximumAuthorizationDuration) <= 0,
					"The historical expiration exceeds the configured maximum: "
							+ remainingAtRenewal);
			Assertions.assertEquals(2, authorizations.get());
		} finally {
			if (client != null)
				client.closeWithReset();
			owner.close();
		}
	}

	@Test
	public void automaticRenewalRunsNearHalfLeaseClearsContextAndRetainsFilter()
			throws Exception {
		Object admissionApplicationContext = new Object();
		Object initialReplacementContext = new Object();
		AtomicInteger authorizations = new AtomicInteger();
		AtomicLong initialGrantReturnedAtNanos = new AtomicLong();
		AtomicLong renewalEnteredAtNanos = new AtomicLong();
		AtomicReference<Instant> initialValidUntil = new AtomicReference<>();
		List<McpSubscriptionAuthorizationContext> contexts =
				new CopyOnWriteArrayList<>();
		CountDownLatch renewalEntered = new CountDownLatch(1);
		CountDownLatch reconciliationEntered = new CountDownLatch(1);
		McpSubscriptionAuthorizer authorizer = (context, features) -> {
			contexts.add(context);
			int invocation = authorizations.incrementAndGet();
			if (invocation == 1) {
				Instant validUntil = Instant.now().plusSeconds(1);
				initialValidUntil.set(validUntil);
				initialGrantReturnedAtNanos.set(System.nanoTime());
				return allowed(validUntil, initialReplacementContext);
			}
			if (invocation == 2) {
				renewalEnteredAtNanos.set(System.nanoTime());
				renewalEntered.countDown();
				return allowed(Instant.now().plusSeconds(4), null);
			}
			if (invocation == 3) {
				reconciliationEntered.countDown();
				return allowed(Instant.now().plusSeconds(4), null);
			}
			throw new AssertionError(
					"Unexpected authorization invocation " + invocation);
		};
		RecordingMetrics metrics = new RecordingMetrics();
		McpSubscriptionEventPublisher publisher =
				McpSubscriptionEventPublisher.fromInMemoryDefaults();
		McpServer server = server(publisher, authorizer,
				admissionApplicationContext, Duration.ofSeconds(2),
				Duration.ofSeconds(5), Duration.ofSeconds(10));
		Soklet owner = managedSoklet(server, metrics);
		McpChunkedHttpClient client = null;

		try {
			owner.start();
			client = listen(boundPort(server), "\"automatic-renewal\"");
			assertSseHead(client.readHead());
			Assertions.assertEquals(acknowledgment("\"automatic-renewal\""),
					client.readChunkText());
			Assertions.assertTrue(renewalEntered.await(5, TimeUnit.SECONDS),
					"The automatic renewal did not run.");

			long renewalDelayNanos = renewalEnteredAtNanos.get()
					- initialGrantReturnedAtNanos.get();
			Assertions.assertTrue(
					renewalDelayNanos >= Duration.ofMillis(350).toNanos(),
					"Renewal ran before the approximate half-lease boundary: "
							+ Duration.ofNanos(renewalDelayNanos));
			metrics.awaitMaintenance(
					McpMetricsEvent.SubscriptionMaintenance.Work.AUTHORIZATION,
					McpMetricsEvent.SubscriptionMaintenance.Outcome.SUCCEEDED, 2);

			McpSubscriptionAuthorizationContext initial = contexts.get(0);
			McpSubscriptionAuthorizationContext renewal = contexts.get(1);
			Assertions.assertSame(admissionApplicationContext,
					initial.getApplicationContext().orElseThrow());
			Assertions.assertSame(initialReplacementContext,
					renewal.getApplicationContext().orElseThrow());
			Assertions.assertEquals(initialValidUntil.get(),
					renewal.getPreviousValidUntil().orElseThrow());
			assertAcknowledgedResourceFilter(renewal);
			Assertions.assertSame(initial.getInitialRequestContext(),
					renewal.getInitialRequestContext());

			server.getSubscriptionReconciler().reconcileSubscriptions();
			Assertions.assertTrue(reconciliationEntered.await(5, TimeUnit.SECONDS),
					"Reconciliation did not observe the renewal grant.");
			metrics.awaitMaintenance(
					McpMetricsEvent.SubscriptionMaintenance.Work.RECONCILIATION,
					McpMetricsEvent.SubscriptionMaintenance.Outcome.SUCCEEDED, 1);
			McpSubscriptionAuthorizationContext reconciliation = contexts.get(2);
			Assertions.assertTrue(reconciliation.getApplicationContext().isEmpty(),
					"Omitting renewal application context must clear the prior value.");
			assertAcknowledgedResourceFilter(reconciliation);
			Assertions.assertSame(initial.getInitialRequestContext(),
					reconciliation.getInitialRequestContext());
			Assertions.assertEquals(3, authorizations.get());

			publisher.publishResourceUpdated(SECOND_RESOURCE_URI);
			Assertions.assertEquals(resourceUpdated("\"automatic-renewal\"",
					SECOND_RESOURCE_URI), client.readChunkText());
		} finally {
			if (client != null)
				client.closeWithReset();
			owner.close();
		}
	}

	@Test
	public void ordinaryBlockedRenewalKeepsDeliveryUnderThePriorLease()
			throws Exception {
		AtomicInteger authorizations = new AtomicInteger();
		CountDownLatch renewalEntered = new CountDownLatch(1);
		CountDownLatch releaseRenewal = new CountDownLatch(1);
		CountDownLatch renewalExited = new CountDownLatch(1);
		McpSubscriptionAuthorizer authorizer = (context, features) -> {
			int invocation = authorizations.incrementAndGet();
			if (invocation == 1)
				return allowed(Instant.now().plusSeconds(4), null);
			if (invocation == 2) {
				try {
					renewalEntered.countDown();
					if (!releaseRenewal.await(5, TimeUnit.SECONDS))
						throw new AssertionError(
								"The ordinary renewal was not released.");
					return allowed(Instant.now().plusSeconds(10), null);
				} finally {
					renewalExited.countDown();
				}
			}
			throw new AssertionError(
					"Unexpected authorization invocation " + invocation);
		};
		RecordingMetrics metrics = new RecordingMetrics();
		McpSubscriptionEventPublisher publisher =
				McpSubscriptionEventPublisher.fromInMemoryDefaults();
		McpServer server = server(publisher, authorizer, null,
				Duration.ofSeconds(5), Duration.ofSeconds(10),
				Duration.ofSeconds(30));
		Soklet owner = managedSoklet(server, metrics);
		ExecutorService reader = Executors.newSingleThreadExecutor();
		McpChunkedHttpClient client = null;
		Future<String> eventFrame = null;

		try {
			owner.start();
			client = listen(boundPort(server), "\"ordinary-renewal\"");
			assertSseHead(client.readHead());
			Assertions.assertEquals(acknowledgment("\"ordinary-renewal\""),
					client.readChunkText());
			Assertions.assertTrue(renewalEntered.await(5, TimeUnit.SECONDS),
					"The ordinary renewal did not enter.");

			McpChunkedHttpClient activeClient = client;
			eventFrame = reader.submit(activeClient::readChunkText);
			publisher.publishResourceUpdated(FIRST_RESOURCE_URI);
			Assertions.assertEquals(resourceUpdated("\"ordinary-renewal\"",
					FIRST_RESOURCE_URI), eventFrame.get(1, TimeUnit.SECONDS),
					"Ordinary renewal fenced delivery under a still-valid grant.");
			Assertions.assertEquals(1L, renewalExited.getCount(),
					"The renewal callback exited before the delivery assertion.");

			releaseRenewal.countDown();
			metrics.awaitMaintenance(
					McpMetricsEvent.SubscriptionMaintenance.Work.AUTHORIZATION,
					McpMetricsEvent.SubscriptionMaintenance.Outcome.SUCCEEDED, 2);
			Assertions.assertEquals(2, authorizations.get());
		} finally {
			releaseRenewal.countDown();
			if (eventFrame != null)
				eventFrame.cancel(true);
			if (client != null)
				client.closeWithReset();
			owner.close();
			reader.shutdownNow();
			Assertions.assertTrue(reader.awaitTermination(5, TimeUnit.SECONDS));
		}
	}

	@Test
	public void cancellationResistantRenewalCannotExtendLeaseOrOverlapReplacement()
			throws Exception {
		AtomicInteger authorizations = new AtomicInteger();
		AtomicInteger activeAuthorizers = new AtomicInteger();
		AtomicInteger maximumActiveAuthorizers = new AtomicInteger();
		AtomicReference<com.soklet.CancelationToken> renewalToken =
				new AtomicReference<>();
		CountDownLatch renewalEntered = new CountDownLatch(1);
		CountDownLatch releaseRenewal = new CountDownLatch(1);
		CountDownLatch renewalExited = new CountDownLatch(1);
		McpSubscriptionAuthorizer authorizer = (context, features) -> {
			int active = activeAuthorizers.incrementAndGet();
			maximumActiveAuthorizers.accumulateAndGet(active, Math::max);
			int invocation = authorizations.incrementAndGet();
			try {
				if (invocation == 1)
					return allowed(Instant.now().plusMillis(900), null);
				if (invocation == 2) {
					renewalToken.set(features.getCancelationToken());
					renewalEntered.countDown();
					while (releaseRenewal.getCount() != 0L) {
						try {
							releaseRenewal.await(25, TimeUnit.MILLISECONDS);
						} catch (InterruptedException ignored) {
							// Deliberately resist cooperative and thread cancellation.
						}
					}
					return allowed(Instant.now().plusSeconds(5), new Object());
				}
				throw new AssertionError(
						"Unexpected replacement authorization " + invocation);
			} finally {
				activeAuthorizers.decrementAndGet();
				if (invocation == 2)
					renewalExited.countDown();
			}
		};
		RecordingMetrics metrics = new RecordingMetrics();
		McpSubscriptionEventPublisher publisher =
				McpSubscriptionEventPublisher.fromInMemoryDefaults();
		McpServer server = server(publisher, authorizer, null,
				Duration.ofSeconds(2), Duration.ofSeconds(5),
				Duration.ofSeconds(5));
		Soklet owner = managedSoklet(server, metrics);
		McpChunkedHttpClient client = null;

		try {
			owner.start();
			client = listen(boundPort(server), "\"resistant-renewal\"");
			assertSseHead(client.readHead());
			Assertions.assertEquals(acknowledgment("\"resistant-renewal\""),
					client.readChunkText());
			Assertions.assertTrue(renewalEntered.await(5, TimeUnit.SECONDS),
					"The renewal callback did not enter.");

			server.getSubscriptionReconciler().reconcileSubscriptions();
			long cancellationDeadline = System.nanoTime()
					+ TimeUnit.SECONDS.toNanos(2);
			while (!renewalToken.get().isCanceled()
					&& System.nanoTime() - cancellationDeadline < 0L)
				Thread.sleep(1L);
			Assertions.assertTrue(renewalToken.get().isCanceled(),
					"Reconciliation did not cancel the resistant renewal token.");
			metrics.awaitSubscriptionClosed(
					McpStreamTerminationReason.SUBSCRIPTION_AUTHORIZATION_EXPIRED);
			Assertions.assertEquals(2, authorizations.get(),
					"A replacement callback overlapped the resistant renewal.");
			Assertions.assertEquals(1, maximumActiveAuthorizers.get(),
					"One subscription ran overlapping authorizer callbacks.");
			Assertions.assertEquals(0,
					server.getDiagnostics().getActiveSubscriptions());
		} finally {
			releaseRenewal.countDown();
			Assertions.assertTrue(renewalExited.await(5, TimeUnit.SECONDS),
					"The resistant renewal did not exit after release.");
			if (client != null)
				client.closeWithReset();
			owner.close();
		}
	}

	@Test
	public void sameExpirationRenewalDoesNotScheduleAnotherRenewal()
			throws Exception {
		AtomicInteger authorizations = new AtomicInteger();
		AtomicReference<Instant> initialValidUntil = new AtomicReference<>();
		AtomicReference<Instant> renewalPreviousValidUntil =
				new AtomicReference<>();
		CountDownLatch renewalEntered = new CountDownLatch(1);
		McpSubscriptionAuthorizer authorizer = (context, features) -> {
			int invocation = authorizations.incrementAndGet();
			if (invocation == 1) {
				Instant validUntil = Instant.now().plusSeconds(2);
				initialValidUntil.set(validUntil);
				return allowed(validUntil, null);
			}
			if (invocation == 2) {
				renewalPreviousValidUntil.set(
						context.getPreviousValidUntil().orElseThrow());
				renewalEntered.countDown();
				return allowed(initialValidUntil.get(), null);
			}
			throw new AssertionError(
					"A same-expiration grant spun renewal invocation " + invocation);
		};
		RecordingMetrics metrics = new RecordingMetrics();
		McpSubscriptionEventPublisher publisher =
				McpSubscriptionEventPublisher.fromInMemoryDefaults();
		McpServer server = server(publisher, authorizer, null,
				Duration.ofSeconds(2), Duration.ofSeconds(5),
				Duration.ofSeconds(5));
		Soklet owner = managedSoklet(server, metrics);
		McpChunkedHttpClient client = null;

		try {
			owner.start();
			client = listen(boundPort(server), "\"same-expiration-renewal\"");
			assertSseHead(client.readHead());
			Assertions.assertEquals(acknowledgment(
					"\"same-expiration-renewal\""), client.readChunkText());
			Assertions.assertTrue(renewalEntered.await(5, TimeUnit.SECONDS),
					"The same-expiration renewal did not run.");
			Assertions.assertEquals(initialValidUntil.get(),
					renewalPreviousValidUntil.get());
			metrics.awaitMaintenance(
					McpMetricsEvent.SubscriptionMaintenance.Work.AUTHORIZATION,
					McpMetricsEvent.SubscriptionMaintenance.Outcome.SUCCEEDED, 2);
			metrics.awaitSubscriptionClosed(
					McpStreamTerminationReason.SUBSCRIPTION_AUTHORIZATION_EXPIRED);
			Assertions.assertEquals(2, authorizations.get(),
					"A same-expiration grant scheduled another renewal.");
		} finally {
			if (client != null)
				client.closeWithReset();
			owner.close();
		}
	}

	private static void assertFailedInitialAuthorizationDoesNotLeakQuota(
			@NonNull String subscriptionId,
			@NonNull McpSubscriptionAuthorizer failingAttempt) throws Exception {
		AtomicInteger authorizations = new AtomicInteger();
		McpSubscriptionAuthorizer authorizer = (context, features) ->
				authorizations.incrementAndGet() == 1
						? failingAttempt.authorize(context, features)
						: allowed(null);
		RecordingMetrics metrics = new RecordingMetrics();
		McpSubscriptionEventPublisher publisher =
				McpSubscriptionEventPublisher.fromInMemoryDefaults();
		McpServer server = server(publisher, authorizer, null);
		Soklet owner = managedSoklet(server, metrics);
		McpChunkedHttpClient admitted = null;

		try {
			owner.start();
			int port = boundPort(server);
			try (McpChunkedHttpClient failed = listen(port,
					"\"" + subscriptionId + "\"")) {
				McpChunkedHttpClient.HttpResponseHead head = failed.readHead();
				Assertions.assertEquals(500, head.status(), head.raw());
				String body = failed.readFixedBody(head);
				Assertions.assertFalse(body.contains(
						"notifications/subscriptions/acknowledged"), body);
			}
			metrics.awaitMaintenance(
					McpMetricsEvent.SubscriptionMaintenance.Work.AUTHORIZATION,
					McpMetricsEvent.SubscriptionMaintenance.Outcome.FAILED, 1);
			Assertions.assertEquals(1L, metrics.maintenanceCount(
					McpMetricsEvent.SubscriptionMaintenance.Work.AUTHORIZATION,
					McpMetricsEvent.SubscriptionMaintenance.Outcome.FAILED));
			Assertions.assertEquals(0L, metrics.subscriptionClosedCount(),
					"Initial authorization failure opened a subscription lifecycle.");
			Assertions.assertEquals(0,
					server.getDiagnostics().getActiveSubscriptions());

			String admittedId = "\"" + subscriptionId + "-after-failure\"";
			admitted = listen(port, admittedId);
			assertSseHead(admitted.readHead());
			Assertions.assertEquals(acknowledgment(admittedId),
					admitted.readChunkText());
			Assertions.assertEquals(2, authorizations.get());
			Assertions.assertEquals(1,
					server.getDiagnostics().getActiveSubscriptions());
		} finally {
			if (admitted != null)
				admitted.closeWithReset();
			owner.close();
		}
	}

	private static void assertAcknowledgedResourceFilter(
			@NonNull McpSubscriptionAuthorizationContext context) {
		Assertions.assertTrue(context.isResourcesListChangedIncluded());
		Assertions.assertFalse(context.isToolsListChangedIncluded());
		Assertions.assertFalse(context.isPromptsListChangedIncluded());
		Assertions.assertEquals(Set.of(FIRST_RESOURCE_URI, SECOND_RESOURCE_URI),
				context.getResourceSubscriptionUris());
		Assertions.assertTrue(context.getTaskIds().isEmpty());
	}

	@NonNull
	private static McpSubscriptionAuthorization allowed(
			@Nullable Object applicationContext) {
		return allowed(Instant.now().plus(Duration.ofHours(1)),
				applicationContext);
	}

	@NonNull
	private static McpSubscriptionAuthorization allowed(
			@NonNull Instant validUntil,
			@Nullable Object applicationContext) {
		McpSubscriptionAuthorization.Allowed.Builder builder =
				McpSubscriptionAuthorization.Allowed.withValidUntil(validUntil);
		if (applicationContext != null)
			builder.applicationContext(applicationContext);
		return builder.build();
	}

	@NonNull
	private static McpServer server(
			@NonNull McpSubscriptionEventPublisher publisher,
			@NonNull McpSubscriptionAuthorizer authorizer,
			@Nullable Object admissionApplicationContext) {
		return server(publisher, authorizer, admissionApplicationContext,
				ignored -> {});
	}

	@NonNull
	private static McpServer server(
			@NonNull McpSubscriptionEventPublisher publisher,
			@NonNull McpSubscriptionAuthorizer authorizer,
			@Nullable Object admissionApplicationContext,
			@NonNull Consumer<@NonNull McpAdmissionContext> admissionObserver) {
		return server(publisher, authorizer, admissionApplicationContext,
				Duration.ofMinutes(5), Duration.ofMinutes(5),
				Duration.ofHours(24), admissionObserver);
	}

	@NonNull
	private static McpServer server(
			@NonNull McpSubscriptionEventPublisher publisher,
			@NonNull McpSubscriptionAuthorizer authorizer,
			@Nullable Object admissionApplicationContext,
			@NonNull Duration authorizationTimeout,
			@NonNull Duration maximumAuthorizationDuration,
			@NonNull Duration maximumSubscriptionDuration) {
		return server(publisher, authorizer, admissionApplicationContext,
				authorizationTimeout, maximumAuthorizationDuration,
				maximumSubscriptionDuration, ignored -> {});
	}

	@NonNull
	private static McpServer server(
			@NonNull McpSubscriptionEventPublisher publisher,
			@NonNull McpSubscriptionAuthorizer authorizer,
			@Nullable Object admissionApplicationContext,
			@NonNull Duration authorizationTimeout,
			@NonNull Duration maximumAuthorizationDuration,
			@NonNull Duration maximumSubscriptionDuration,
			@NonNull Consumer<@NonNull McpAdmissionContext> admissionObserver) {
		McpSubscriptionConfig subscriptions = McpSubscriptionConfig
				.withEventPublisherAndNotificationTypes(publisher,
						Set.of(McpSubscriptionNotificationType
								.RESOURCES_LIST_CHANGED,
								McpSubscriptionNotificationType.RESOURCE_UPDATED))
				.build();
		McpEndpoint.Builder endpoint = McpEndpoint.withPath(MCP_PATH,
				McpImplementation.withNameAndVersion(
						"subscription-authorization-runtime-test", "4.0.0")
						.build())
				.subscriptionConfig(subscriptions);
		endpoint.resourceRegistrations(List.of(resource(FIRST_RESOURCE_URI),
				resource(SECOND_RESOURCE_URI)));
		Assertions.assertEquals(2, endpoint.build().getResourceRegistrations().size());
		return McpServer.withPort(0)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(
						List.of(endpoint.build())))
				.admissionController(context -> {
					admissionObserver.accept(context);
					McpAdmissionIdentity.Builder identity = McpAdmissionIdentity
							.withRateLimitPartitionKey("authorization-runtime-rate")
							.authorizationPartitionKey(
									"authorization-runtime-auth")
							.principal("authorization-runtime-principal");
					if (admissionApplicationContext != null)
						identity.applicationContext(admissionApplicationContext);
					return McpAdmissionDecision.accepted(identity.build());
				})
				.subscriptionAuthorizer(authorizer)
				.maximumSubscriptionsPerPartition(1)
				.subscriptionAuthorizationTimeout(authorizationTimeout)
				.maximumSubscriptionAuthorizationDuration(
						maximumAuthorizationDuration)
				.maximumSubscriptionDuration(maximumSubscriptionDuration)
				.host(LOOPBACK)
				.requestRateLimiter(context -> McpRateLimitDecision.allowed())
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(LOOPBACK))
				.build();
	}

	@NonNull
	private static McpResourceRegistration resource(@NonNull URI resourceUri) {
		return McpResourceRegistration
				.withUriAndName(resourceUri, "Subscription authorization resource")
				.handler((request, read, features) ->
						McpCompleteResult.fromResourceOutput(
								McpResourceOutput.withContent(
										McpTextResourceContents.withUriAndText(
												read.getUri(), "test").build())
										.build()))
				.build();
	}

	@NonNull
	private static Soklet managedSoklet(@NonNull McpServer server,
			@NonNull MetricsCollector metricsCollector) {
		return Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.metricsCollector(metricsCollector)
				.lifecyclePolicy(LifecyclePolicy.builder()
						.startupTimeout(Duration.ofSeconds(5))
						.startupCancelationTimeout(Duration.ofSeconds(2))
						.gracefulShutdownTimeout(Duration.ofSeconds(2))
						.forcedShutdownTimeout(Duration.ofSeconds(1))
						.build())
				.build());
	}

	@NonNull
	private static McpChunkedHttpClient listen(int port,
			@NonNull String idJson) throws Exception {
		String notifications = "{\"resourcesListChanged\":true,"
				+ "\"resourceSubscriptions\":[\"" + FIRST_RESOURCE_URI
				+ "\",\"" + SECOND_RESOURCE_URI + "\",\""
				+ FIRST_RESOURCE_URI + "\"]}";
		String body = "{\"jsonrpc\":\"2.0\",\"id\":" + idJson
				+ ",\"method\":\"subscriptions/listen\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}},"
				+ "\"notifications\":" + notifications + "}}";
		return McpChunkedHttpClient.postMcpMessage(port, body, List.of(
				new McpChunkedHttpClient.RequestHeader(
						"MCP-Protocol-Version", PROTOCOL_VERSION),
				new McpChunkedHttpClient.RequestHeader(
						"Mcp-Method", "subscriptions/listen"),
				new McpChunkedHttpClient.RequestHeader(
						"Authorization", "Bearer admission-secret")));
	}

	private static String acknowledgment(@NonNull String subscriptionIdJson) {
		return sse("{\"jsonrpc\":\"2.0\","
				+ "\"method\":\"notifications/subscriptions/acknowledged\","
				+ "\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/subscriptionId\":"
				+ subscriptionIdJson + "},\"notifications\":{"
				+ "\"resourcesListChanged\":true,"
				+ "\"resourceSubscriptions\":[\"" + FIRST_RESOURCE_URI
				+ "\",\"" + SECOND_RESOURCE_URI + "\"]}}}");
	}

	private static String resourceUpdated(@NonNull String subscriptionIdJson,
			@NonNull URI resourceUri) {
		return sse("{\"jsonrpc\":\"2.0\","
				+ "\"method\":\"notifications/resources/updated\","
				+ "\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/subscriptionId\":"
				+ subscriptionIdJson + "},\"uri\":\"" + resourceUri + "\"}}");
	}

	private static String sse(@NonNull String json) {
		return "data: " + json + "\n\n";
	}

	private static void assertSseHead(
			McpChunkedHttpClient.@NonNull HttpResponseHead head) {
		Assertions.assertEquals(200, head.status(), head.raw());
		Assertions.assertEquals("text/event-stream",
				head.singleHeader("Content-Type"));
		Assertions.assertEquals("no-store", head.singleHeader("Cache-Control"));
		Assertions.assertEquals("chunked",
				head.singleHeader("Transfer-Encoding"));
		Assertions.assertFalse(head.hasHeader("Content-Length"));
	}

	private static int boundPort(@NonNull McpServer server) {
		return server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
	}

	@ThreadSafe
	private static final class RecordingMetrics implements MetricsCollector {
		@NonNull
		private final List<@NonNull McpMetricsEvent> events =
				new CopyOnWriteArrayList<>();

		@Override
		public void didRecordMcpMetricsEvent(@NonNull McpMetricsEvent event) {
			this.events.add(event);
		}

		private void awaitMaintenance(
				McpMetricsEvent.SubscriptionMaintenance.@NonNull Work work,
				McpMetricsEvent.SubscriptionMaintenance.@NonNull Outcome outcome,
				int expectedCount) throws InterruptedException {
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
			while (maintenanceCount(work, outcome) < expectedCount
					&& System.nanoTime() - deadline < 0L)
				Thread.sleep(1L);
			Assertions.assertTrue(maintenanceCount(work, outcome) >= expectedCount,
					"Missing subscription maintenance event " + work + '/' + outcome
							+ "; events=" + this.events);
		}

		private long maintenanceCount(
				McpMetricsEvent.SubscriptionMaintenance.@NonNull Work work,
				McpMetricsEvent.SubscriptionMaintenance.@NonNull Outcome outcome) {
			return this.events.stream()
					.filter(McpMetricsEvent.SubscriptionMaintenance.class::isInstance)
					.map(McpMetricsEvent.SubscriptionMaintenance.class::cast)
					.filter(event -> event.getWork() == work
							&& event.getOutcome() == outcome)
					.count();
		}

		private void awaitSubscriptionClosed(
				@NonNull McpStreamTerminationReason expectedReason)
				throws InterruptedException {
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
			while (!hasSubscriptionClose(expectedReason)
					&& System.nanoTime() - deadline < 0L)
				Thread.sleep(1L);
			Assertions.assertTrue(hasSubscriptionClose(expectedReason),
					"Missing subscription close reason " + expectedReason
							+ "; events=" + this.events);
		}

		private boolean hasSubscriptionClose(
				@NonNull McpStreamTerminationReason expectedReason) {
			return this.events.stream()
					.filter(McpMetricsEvent.SubscriptionClosed.class::isInstance)
					.map(McpMetricsEvent.SubscriptionClosed.class::cast)
					.anyMatch(event -> event.getReason() == expectedReason);
		}

		private long subscriptionClosedCount() {
			return this.events.stream()
					.filter(McpMetricsEvent.SubscriptionClosed.class::isInstance)
					.count();
		}
	}
}
