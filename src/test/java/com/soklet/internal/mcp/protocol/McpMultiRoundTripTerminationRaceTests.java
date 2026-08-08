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
import com.soklet.McpRequestStateMode;
import com.soklet.StreamTerminationReason;
import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.RequestStateProtectionAdapter;
import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.RequestStateProtectionInput;
import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.RequestStateProtectionPlan;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.io.IOException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * Deterministic termination and branching races for framework-protected MCP
 * multi-round-trip requests.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@NotThreadSafe
@Timeout(30)
public class McpMultiRoundTripTerminationRaceTests {
	private static final String LOOPBACK = "127.0.0.1";
	private static final String PATH = "/mcp";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final String METHOD = "tools/call";
	private static final String TOOL = "mrtr.race";
	private static final Duration REQUEST_DEADLINE = Duration.ofSeconds(5);
	private static final Duration SHUTDOWN_TIMEOUT = Duration.ofMillis(100);
	private static final Duration MAXIMUM_STATE_LIFETIME = Duration.ofMinutes(5);

	@Test
	public void blockedCustomProtectorOpenMakesShutdownResidualUntilProtocolWorkExits()
			throws Exception {
		BlockingProtector protector = new BlockingProtector();
		McpFrameworkRequestStateRuntime stateRuntime = stateRuntime(protector);
		String protectedState = seedState(stateRuntime, "origin-open");
		BlockGate openGate = protector.blockNextOpens(1);
		AtomicInteger interceptors = new AtomicInteger();
		AtomicInteger handlers = new AtomicInteger();
		McpRuntimeObservationRecorder observations =
				new McpRuntimeObservationRecorder();
		McpHttpServerRuntime runtime = runtime(McpInputRequestPlan.empty(),
				stateRuntime, new ControllableClock(), 1, observations,
				interceptors, invocation -> {
					handlers.incrementAndGet();
					return complete("must not run");
				});
		McpChunkedHttpClient client = null;

		try {
			client = call(runtime.start().getPort(), "open-shutdown",
					Optional.of(protectedState), false);
			openGate.awaitEntered();
			awaitRequest(runtime, snapshot ->
					snapshot.activeIdentifiedRequestExchanges() == 1);

			long stopStarted = System.nanoTime();
			runtime.stop();
			Assertions.assertTrue(System.nanoTime() - stopStarted
					< TimeUnit.SECONDS.toNanos(2),
					"Shutdown must remain bounded around an uncooperative protector.");
			Assertions.assertFalse(runtime.isStarted());
			Assertions.assertTrue(
					runtime.lifecycleSnapshot().residualApplicationExecutions(),
					"The blocked application-supplied callback must remain residual.");
			Assertions.assertThrows(IllegalStateException.class, runtime::start,
					"Restart must remain closed while the protocol callback is live.");
			Assertions.assertThrows(IOException.class, client::readHead,
					"Shutdown must close without a protocol response body.");
			Assertions.assertEquals(0, observations.startCount(),
					"Authenticated opening precedes admitted-request observation.");
			Assertions.assertEquals(0, interceptors.get());
			Assertions.assertEquals(0, handlers.get());

			openGate.release();
			awaitCondition(() -> !runtime.lifecycleSnapshot()
					.residualApplicationExecutions());
			awaitRequest(runtime, snapshot ->
					snapshot.retainedRequestControls() == 0
							&& snapshot.queuedProtocolRequests() == 0
							&& snapshot.activeIdentifiedRequestExchanges() == 0);
			Assertions.assertEquals(1, protector.opens());
			Assertions.assertEquals(0, observations.startCount());
			Assertions.assertEquals(0, interceptors.get());
			Assertions.assertEquals(0, handlers.get());

			// Once the residual protocol callback exits, the same runtime is cleanly
			// restartable. No registered handler ran; RESIDUAL_HANDLERS is the frozen
			// compatibility label for the blocked request-state protector callback.
			runtime.start();
			runtime.stop();
		} finally {
			openGate.release();
			if (client != null)
				client.close();
			runtime.close();
		}
	}

	@Test
	public void blockedCustomProtectorOpenDiscardsLateResultAfterDeadlineOrDisconnect()
			throws Exception {
		for (OpenTermination termination : OpenTermination.values())
			blockedCustomProtectorOpenDiscardsLateResultAfterDeadlineOrDisconnect(
					termination);
	}

	private void blockedCustomProtectorOpenDiscardsLateResultAfterDeadlineOrDisconnect(
			OpenTermination termination) throws Exception {
		BlockingProtector protector = new BlockingProtector();
		McpFrameworkRequestStateRuntime stateRuntime = stateRuntime(protector);
		String protectedState = seedState(stateRuntime,
				"origin-open-" + termination.name().toLowerCase());
		BlockGate openGate = protector.blockNextOpens(1);
		ControllableClock clock = new ControllableClock();
		AtomicInteger interceptors = new AtomicInteger();
		AtomicInteger handlers = new AtomicInteger();
		McpRuntimeObservationRecorder observations =
				new McpRuntimeObservationRecorder();
		McpHttpServerRuntime runtime = runtime(McpInputRequestPlan.empty(),
				stateRuntime, clock, 1, observations, interceptors, invocation -> {
					invocation.requireHandlerEntry();
					handlers.incrementAndGet();
					return complete("peer complete");
				}, 1);
		McpChunkedHttpClient blocked = null;
		McpChunkedHttpClient peer = null;

		try {
			int port = runtime.start().getPort();
			String blockedId = "open-" + termination.name().toLowerCase();
			blocked = call(port, blockedId, Optional.of(protectedState), false);
			openGate.awaitEntered();
			awaitRequest(runtime, snapshot ->
					snapshot.activeIdentifiedRequestExchanges() == 1);

			if (termination == OpenTermination.DEADLINE) {
				clock.advance(REQUEST_DEADLINE.plusSeconds(1));
				runtime.runApplicationTimerCycle();
				McpChunkedHttpClient.HttpResponseHead head = blocked.readHead();
				Assertions.assertEquals(504, head.status(), head.raw());
				Assertions.assertEquals("no-store",
						head.singleHeader("Cache-Control"));
				Assertions.assertFalse(head.hasHeader("Content-Type"));
				Assertions.assertEquals("", blocked.readFixedBody(head));
			} else {
				blocked.closeWithReset();
			}
			awaitRequest(runtime, snapshot ->
					snapshot.retainedRequestControls() == 0
							&& snapshot.activeIdentifiedRequestExchanges() == 0);

			// One protocol processor proves the uncooperative protector still owns
			// its worker even though the request's deadline/disconnect has won.
			peer = call(port, "open-peer-" + termination.name().toLowerCase(),
					Optional.empty(), false);
			awaitRequest(runtime, snapshot ->
					snapshot.queuedProtocolRequests() == 1);
			Assertions.assertEquals(0, observations.startCount(),
					"Opening must precede admitted-request observation.");
			Assertions.assertEquals(0, interceptors.get());
			Assertions.assertEquals(0, handlers.get());

			openGate.release();
			FixedResponse peerResponse = readFixed(peer);
			Assertions.assertEquals(200, peerResponse.status(), peerResponse.body());
			Assertions.assertTrue(peerResponse.body().contains("peer complete"),
					peerResponse.body());
			// This snapshot proves request-control, transport, and identified-ID
			// cleanup only. The queued-peer boundary above separately proves that
			// the application-supplied callback retained the protocol worker.
			awaitRequest(runtime, snapshot ->
					snapshot.retainedRequestControls() == 0
							&& snapshot.queuedProtocolRequests() == 0
							&& snapshot.activeIdentifiedRequestExchanges() == 0);
			Assertions.assertEquals(1, observations.startCount(),
					"Only the queued peer may begin observation.");
			Assertions.assertEquals(1, interceptors.get());
			Assertions.assertEquals(1, handlers.get());
			Assertions.assertEquals(1, protector.opens());
		} finally {
			openGate.release();
			if (blocked != null)
				blocked.close();
			if (peer != null)
				peer.close();
			runtime.close();
		}
	}

	@Test
	public void blockedSealCannotPublishLateInputRequiredAndReleasesExactlyOnce()
			throws Exception {
		for (SealTermination termination : SealTermination.values())
			blockedSealCannotPublishLateInputRequiredAndReleasesExactlyOnce(
					termination);
	}

	private void blockedSealCannotPublishLateInputRequiredAndReleasesExactlyOnce(
			SealTermination termination) throws Exception {
		BlockingProtector protector = new BlockingProtector();
		McpFrameworkRequestStateRuntime stateRuntime = stateRuntime(protector);
		BlockGate sealGate = protector.blockNextSeals(1);
		ControllableClock clock = new ControllableClock();
		AtomicInteger cancelations = new AtomicInteger();
		AtomicReference<StreamTerminationReason> reason = new AtomicReference<>();
		McpHttpServerRuntime runtime = runtime(McpInputRequestPlan.empty(),
				stateRuntime, clock, 1, new McpRuntimeObservationRecorder(),
				new AtomicInteger(), invocation -> {
					invocation.requireHandlerEntry();
					invocation.cancelationToken().onCancel(() -> {
						reason.set(invocation.cancellationReason().orElseThrow());
						cancelations.incrementAndGet();
					});
					return sealInputRequired(stateRuntime, invocation,
							new McpJsonString("blocked-seal"));
				});
		McpChunkedHttpClient client = null;
		String deadlineBody = null;

		try {
			client = call(runtime.start().getPort(), "blocked-seal",
					Optional.empty(), false);
			sealGate.awaitEntered();
			awaitApplication(runtime, snapshot ->
					snapshot.activeHandlerSlots() == 1
							&& snapshot.retainedExchanges() == 1
							&& snapshot.retainedTransportLeases() == 1);

			switch (termination) {
				case DISCONNECT -> client.closeWithReset();
				case DEADLINE -> {
					clock.advance(REQUEST_DEADLINE.plusSeconds(1));
					runtime.runApplicationTimerCycle();
					McpChunkedHttpClient.HttpResponseHead head = client.readHead();
					Assertions.assertEquals(504, head.status(), head.raw());
					deadlineBody = client.readFixedBody(head);
					Assertions.assertEquals("", deadlineBody);
				}
				case SHUTDOWN -> {
					runtime.stop();
					Assertions.assertThrows(IOException.class, client::readHead,
							"Shutdown must not publish a terminal MRTR result.");
				}
			}

			awaitCondition(() -> cancelations.get() == 1);
			Assertions.assertEquals(termination.reason(), reason.get());
			McpApplicationExecutionSnapshot retained = awaitApplication(runtime,
					snapshot -> snapshot.activeHandlerSlots() == 1
							&& snapshot.activeIdentifiedRequestExchanges() == 0
							&& snapshot.retainedExchanges() == 1
							&& snapshot.retainedTransportLeases() == 0);
			Assertions.assertEquals(1, retained.responseCleanups());
			Assertions.assertEquals(termination == SealTermination.DEADLINE ? 1 : 0,
					retained.terminalResponses());
			Assertions.assertEquals(termination == SealTermination.DEADLINE ? 0 : 1,
					retained.abandonedResponses());
			Assertions.assertEquals(termination == SealTermination.DEADLINE ? 1 : 0,
					retained.deadlineExpirations());

			sealGate.release();
			McpApplicationExecutionSnapshot released = awaitApplication(runtime,
					snapshot -> snapshot.activeHandlerSlots() == 0
							&& snapshot.queuedRequests() == 0
							&& snapshot.activeIdentifiedRequestExchanges() == 0
							&& snapshot.retainedExchanges() == 0
							&& snapshot.retainedTransportLeases() == 0);
			Assertions.assertEquals(1, cancelations.get());
			Assertions.assertEquals(1, released.responseCleanups());
			Assertions.assertEquals(1, protector.seals());
			Assertions.assertFalse(String.valueOf(deadlineBody)
					.contains("input_required"));
		} finally {
			sealGate.release();
			if (client != null)
				client.close();
			runtime.close();
		}
	}

	@Test
	public void sameAuthenticatedStateCanBranchWhileOneFreshIdTerminates()
			throws Exception {
		for (BranchResult branchResult : BranchResult.values())
			sameAuthenticatedStateCanBranchWhileOneFreshIdTerminates(branchResult);
	}

	private void sameAuthenticatedStateCanBranchWhileOneFreshIdTerminates(
			BranchResult branchResult) throws Exception {
		BlockingProtector protector = new BlockingProtector();
		McpFrameworkRequestStateRuntime stateRuntime = stateRuntime(protector);
		String protectedState = seedState(stateRuntime, "branch-origin");
		BlockGate openGate = protector.blockNextOpens(2);
		CountDownLatch terminatedEntered = new CountDownLatch(1);
		CountDownLatch survivorEntered = new CountDownLatch(1);
		CountDownLatch releaseTerminated = new CountDownLatch(1);
		CountDownLatch releaseSurvivor = new CountDownLatch(1);
		AtomicInteger cancelations = new AtomicInteger();
		AtomicReference<StreamTerminationReason> reason = new AtomicReference<>();
		AtomicReference<Integer> continuedRound = new AtomicReference<>();
		McpHttpServerRuntime runtime = runtime(McpInputRequestPlan.empty(),
				stateRuntime, new ControllableClock(), 2,
				new McpRuntimeObservationRecorder(), new AtomicInteger(), invocation -> {
					invocation.requireHandlerEntry();
					String requestId = stringId(invocation.request().id());
					if ("branch-terminated".equals(requestId)) {
						invocation.cancelationToken().onCancel(() -> {
							reason.set(invocation.cancellationReason().orElseThrow());
							cancelations.incrementAndGet();
						});
						terminatedEntered.countDown();
						awaitUninterruptibly(releaseTerminated);
						return complete("late terminated branch");
					}
					if ("branch-survivor".equals(requestId)) {
						survivorEntered.countDown();
						releaseSurvivor.await();
						if (branchResult == BranchResult.COMPLETE)
							return complete("survivor complete");
						return sealInputRequired(stateRuntime, invocation,
								new McpJsonString("survivor-next-round"));
					}
					if ("branch-next".equals(requestId)) {
						continuedRound.set(invocation
								.frameworkRequestStateContinuation().orElseThrow()
								.round());
						return complete("next round complete");
					}
					throw new AssertionError("Unexpected request ID " + requestId);
				});
		McpChunkedHttpClient terminated = null;
		McpChunkedHttpClient survivor = null;

		try {
			int port = runtime.start().getPort();
			terminated = call(port, "branch-terminated",
					Optional.of(protectedState), false);
			survivor = call(port, "branch-survivor",
					Optional.of(protectedState), false);
			openGate.awaitEntered();
			openGate.release();
			Assertions.assertTrue(terminatedEntered.await(5, TimeUnit.SECONDS));
			Assertions.assertTrue(survivorEntered.await(5, TimeUnit.SECONDS));
			awaitApplication(runtime, snapshot ->
					snapshot.activeHandlerSlots() == 2
							&& snapshot.activeIdentifiedRequestExchanges() == 2);

			terminated.closeWithReset();
			awaitCondition(() -> cancelations.get() == 1);
			Assertions.assertEquals(StreamTerminationReason.CLIENT_DISCONNECTED,
					reason.get());
			awaitApplication(runtime, snapshot ->
					snapshot.activeHandlerSlots() == 2
							&& snapshot.activeIdentifiedRequestExchanges() == 1
							&& snapshot.retainedExchanges() == 2);

			releaseSurvivor.countDown();
			FixedResponse survivorResponse = readFixed(survivor);
			Assertions.assertEquals(200, survivorResponse.status(),
					survivorResponse.body());
			Assertions.assertTrue(survivorResponse.body().contains(
					branchResult == BranchResult.COMPLETE
							? "survivor complete" : "input_required"),
					survivorResponse.body());
			awaitApplication(runtime, snapshot ->
					snapshot.activeHandlerSlots() == 1
							&& snapshot.activeIdentifiedRequestExchanges() == 0
							&& snapshot.retainedExchanges() == 1);

			if (branchResult == BranchResult.REEMIT) {
				String nextState = requestState(survivorResponse.body());
				try (McpChunkedHttpClient next = call(port, "branch-next",
						Optional.of(nextState), false)) {
					FixedResponse nextResponse = readFixed(next);
					Assertions.assertEquals(200, nextResponse.status(),
							nextResponse.body());
					Assertions.assertTrue(nextResponse.body().contains(
							"next round complete"), nextResponse.body());
				}
				Assertions.assertEquals(2, continuedRound.get());
			}

			releaseTerminated.countDown();
			McpApplicationExecutionSnapshot released = awaitApplication(runtime,
					snapshot -> snapshot.activeHandlerSlots() == 0
							&& snapshot.activeIdentifiedRequestExchanges() == 0
							&& snapshot.retainedExchanges() == 0);
			Assertions.assertEquals(1, cancelations.get());
			Assertions.assertEquals(1, released.abandonedResponses());
			Assertions.assertEquals(branchResult == BranchResult.REEMIT ? 3 : 2,
					protector.opens());
		} finally {
			openGate.release();
			releaseSurvivor.countDown();
			releaseTerminated.countDown();
			if (terminated != null)
				terminated.close();
			if (survivor != null)
				survivor.close();
			runtime.close();
		}
	}

	@Test
	public void conditionalCapabilityHoldTerminatesWithoutProgressOrLateResult()
			throws Exception {
		for (HoldTermination termination : HoldTermination.values())
			conditionalCapabilityHoldTerminatesWithoutProgressOrLateResult(
					termination);
	}

	private void conditionalCapabilityHoldTerminatesWithoutProgressOrLateResult(
			HoldTermination termination) throws Exception {
		McpInputRequestDeclaration roots = McpInputRequestDeclaration.roots(
				McpInputRequirement.CONDITIONAL);
		McpInputRequestPlan plan = new McpInputRequestPlan(List.of(roots));
		BlockingProtector protector = new BlockingProtector();
		ControllableClock clock = new ControllableClock();
		CountDownLatch handlerEntered = new CountDownLatch(1);
		CountDownLatch releaseHandler = new CountDownLatch(1);
		AtomicInteger cancelations = new AtomicInteger();
		AtomicReference<StreamTerminationReason> reason = new AtomicReference<>();
		AtomicReference<Boolean> progressSuppressed = new AtomicReference<>();
		McpHttpServerRuntime runtime = runtime(plan, stateRuntime(protector),
				clock, 1, new McpRuntimeObservationRecorder(), new AtomicInteger(),
				invocation -> {
					invocation.requireHandlerEntry();
					progressSuppressed.set(McpServerRuntimeBridge
							.progressEmitterFor(invocation, plan).isEmpty());
					invocation.cancelationToken().onCancel(() -> {
						reason.set(invocation.cancellationReason().orElseThrow());
						cancelations.incrementAndGet();
					});
					handlerEntered.countDown();
					awaitUninterruptibly(releaseHandler);
					return inputRequired(roots);
				});
		McpChunkedHttpClient client = null;

		try {
			client = call(runtime.start().getPort(), "conditional-hold",
					Optional.empty(), true);
			Assertions.assertTrue(handlerEntered.await(5, TimeUnit.SECONDS));
			Assertions.assertEquals(Boolean.TRUE, progressSuppressed.get());
			McpRequestExecutionSnapshot held = runtime.requestExecutionSnapshot();
			Assertions.assertEquals(0, held.activeResponseStreams());
			Assertions.assertEquals(0, held.bufferedStreamFrames());
			Assertions.assertEquals(0, held.bufferedStreamBytes());
			Assertions.assertEquals(0, held.terminalStreamBytes());

			if (termination == HoldTermination.DISCONNECT) {
				client.closeWithReset();
			} else {
				clock.advance(REQUEST_DEADLINE.plusSeconds(1));
				runtime.runApplicationTimerCycle();
				McpChunkedHttpClient.HttpResponseHead head = client.readHead();
				Assertions.assertEquals(504, head.status(), head.raw());
				Assertions.assertEquals("", client.readFixedBody(head));
			}

			awaitCondition(() -> cancelations.get() == 1);
			Assertions.assertEquals(termination.reason(), reason.get());
			awaitApplication(runtime, snapshot ->
					snapshot.activeHandlerSlots() == 1
							&& snapshot.activeIdentifiedRequestExchanges() == 0
							&& snapshot.retainedTransportLeases() == 0);
			releaseHandler.countDown();
			McpApplicationExecutionSnapshot released = awaitApplication(runtime,
					snapshot -> snapshot.activeHandlerSlots() == 0
							&& snapshot.activeIdentifiedRequestExchanges() == 0
							&& snapshot.retainedExchanges() == 0);
			Assertions.assertEquals(1, cancelations.get());
			Assertions.assertEquals(1, released.responseCleanups());
			Assertions.assertEquals(0, runtime.requestExecutionSnapshot()
					.activeResponseStreams());
			Assertions.assertEquals(0, runtime.requestExecutionSnapshot()
					.bufferedStreamFrames());
			Assertions.assertEquals(0, protector.seals());
			Assertions.assertEquals(0, protector.opens());
		} finally {
			releaseHandler.countDown();
			if (client != null)
				client.close();
			runtime.close();
		}
	}

	private static McpHttpServerRuntime runtime(McpInputRequestPlan inputPlan,
			McpFrameworkRequestStateRuntime stateRuntime, ControllableClock clock,
			int handlerConcurrency, McpRuntimeObservationSink observations,
			AtomicInteger interceptors, McpApplicationRequestHandler handler) {
		return runtime(inputPlan, stateRuntime, clock, handlerConcurrency,
				observations, interceptors, handler,
				McpHttpTransportConfiguration.productionDefaults(0)
						.requestProcessorConcurrency());
	}

	private static McpHttpServerRuntime runtime(McpInputRequestPlan inputPlan,
			McpFrameworkRequestStateRuntime stateRuntime, ControllableClock clock,
			int handlerConcurrency, McpRuntimeObservationSink observations,
			AtomicInteger interceptors, McpApplicationRequestHandler handler,
			int requestProcessorConcurrency) {
		McpNormalizedToolDescriptor descriptor = new McpNormalizedToolDescriptor(
				TOOL, objectSchema(), Optional.empty(), McpJsonObject.empty(),
				McpJsonObject.empty());
		McpNormalizedEndpoint endpoint = McpNormalizedEndpoint
				.withServerInformation(McpImplementationMetadata.withNameAndVersion(
						"mrtr-termination-race-test", "3.6.0-SNAPSHOT"))
				.tool(McpNormalizedOperation.tool(descriptor, inputPlan,
						McpMirroredHeaderPlan.empty()))
				.build();
		McpApplicationRequestRouter router =
				McpApplicationRequestRouter.fromToolRoutes(Map.of(TOOL,
						new McpApplicationToolRoute(handler,
								ignored -> McpRateLimitDecision.allowed(), inputPlan,
								McpRequestStateMode.FRAMEWORK_PROTECTED)));
		McpHttpEndpointPolicy policy = McpHttpEndpointPolicy.forDiscovery(
				CorsAuthorizer.rejectAllInstance(),
				McpRequestAdmissionPolicy.acceptAllInstance())
				.withRequestInterceptor((invocation, downstream) -> {
					interceptors.incrementAndGet();
					return downstream.invoke();
				});
		McpHttpEndpointBinding binding = new McpHttpEndpointBinding(policy,
				endpoint, router, observations);
		return new McpHttpServerRuntime(
				transportConfiguration(requestProcessorConcurrency), List.of(binding),
				McpJsonLimits.productionDefaults(),
				new McpApplicationExecutionConfiguration(handlerConcurrency, 4,
						REQUEST_DEADLINE, Duration.ofDays(1)),
				clock, McpApplicationHandlerExecutorFactory.production(),
				ignored -> {}, ignored -> {}, Optional.empty(), stateRuntime);
	}

	private static McpHttpTransportConfiguration transportConfiguration(
			int requestProcessorConcurrency) {
		McpHttpTransportConfiguration defaults =
				McpHttpTransportConfiguration.productionDefaults(0);
		return new McpHttpTransportConfiguration(defaults.host(), defaults.port(),
				defaults.selectorResolution(), defaults.requestHeaderTimeout(),
				defaults.requestBodyTimeout(), defaults.responseWriteIdleTimeout(),
				defaults.keepAliveInterval(), SHUTDOWN_TIMEOUT,
				defaults.readBufferSize(), defaults.acceptBacklog(),
				defaults.maximumAggregateRequestBytes(),
				defaults.maximumRequestBodyBytes(), defaults.maximumHeaderCount(),
				defaults.maximumHeaderBytes(), defaults.maximumRequestTargetBytes(),
				defaults.maximumConnections(), defaults.connectionWriterConcurrency(),
				requestProcessorConcurrency,
				defaults.requestProcessorQueueCapacity(),
				defaults.streamQueueCapacity());
	}

	private static McpFrameworkRequestStateRuntime stateRuntime(
			BlockingProtector protector) {
		return new McpFrameworkRequestStateRuntime(Optional.of(
				new RequestStateProtectionPlan(8_192, 8_192,
						MAXIMUM_STATE_LIFETIME, 4, protector)),
				Clock.fixed(Instant.parse("2026-08-08T12:00:00Z"),
						ZoneOffset.UTC));
	}

	private static String seedState(McpFrameworkRequestStateRuntime stateRuntime,
			String originatingId) throws McpRequestStateUnavailableException {
		return stateRuntime.seal(PATH, PROTOCOL_VERSION, METHOD, Optional.empty(),
				operationParams(false), stringId(originatingId),
				new McpJsonString("shared-authenticated-state"), Optional.empty());
	}

	private static McpWireResult sealInputRequired(
			McpFrameworkRequestStateRuntime stateRuntime,
			McpApplicationInvocation invocation, McpJsonValue state)
			throws McpRequestStateUnavailableException {
		McpJsonRpcMessage.Request request = invocation.request();
		String protectedState = stateRuntime.seal(PATH,
				request.params().metadata().protocolVersion(), request.method(),
				invocation.admissionIdentity().authorizationPartition()
						.applicationKey(),
				request.params().toJsonObject(), request.id(), state,
				invocation.frameworkRequestStateContinuation());
		return McpWireResult.inputRequired(METHOD, Optional.empty(),
				Optional.of(protectedState), Optional.empty(), McpJsonObject.empty());
	}

	private static McpWireResult inputRequired(
			McpInputRequestDeclaration declaration) {
		McpInputRequests requests = McpInputRequests.builder()
				.inputRequest("roots", McpEmbeddedInputRequest.fromDeclaration(
						declaration, McpJsonObject.empty()))
				.build();
		return McpWireResult.inputRequired(METHOD, Optional.of(requests),
				Optional.empty(), Optional.empty(), McpJsonObject.empty());
	}

	private static McpWireResult complete(String value) {
		return McpWireResult.complete(new McpJsonObject(
				Map.of("value", new McpJsonString(value))));
	}

	private static McpJsonObject objectSchema() {
		return new McpJsonObject(Map.of("type", new McpJsonString("object")));
	}

	private static McpJsonObject operationParams(boolean progressToken) {
		Map<String, McpJsonValue> metadata = new LinkedHashMap<>();
		metadata.put("io.modelcontextprotocol/protocolVersion",
				new McpJsonString(PROTOCOL_VERSION));
		metadata.put("io.modelcontextprotocol/clientCapabilities",
				McpJsonObject.empty());
		if (progressToken)
			metadata.put("progressToken", new McpJsonString("held-progress"));
		Map<String, McpJsonValue> fields = new LinkedHashMap<>();
		fields.put("_meta", new McpJsonObject(metadata));
		fields.put("name", new McpJsonString(TOOL));
		fields.put("arguments", McpJsonObject.empty());
		return new McpJsonObject(fields);
	}

	private static McpChunkedHttpClient call(int port, String requestId,
			Optional<String> requestState, boolean progressToken) throws IOException {
		String state = requestState
				.map(value -> ",\"requestState\":\"" + value + "\"")
				.orElse("");
		String progress = progressToken
				? ",\"progressToken\":\"held-progress\"" : "";
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + requestId
				+ "\",\"method\":\"" + METHOD + "\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}"
				+ progress + "},\"name\":\"" + TOOL
				+ "\",\"arguments\":{}" + state + "}}";
		return McpChunkedHttpClient.postMcpMessage(port, body, List.of(
				new McpChunkedHttpClient.RequestHeader(
						"MCP-Protocol-Version", PROTOCOL_VERSION),
				new McpChunkedHttpClient.RequestHeader("Mcp-Method", METHOD),
				new McpChunkedHttpClient.RequestHeader("Mcp-Name", TOOL)));
	}

	private static FixedResponse readFixed(McpChunkedHttpClient client)
			throws IOException {
		McpChunkedHttpClient.HttpResponseHead head = client.readHead();
		return new FixedResponse(head.status(), client.readFixedBody(head));
	}

	private static String requestState(String body) {
		String prefix = "\"requestState\":\"";
		int start = body.indexOf(prefix);
		if (start < 0)
			throw new AssertionError("No requestState in " + body);
		start += prefix.length();
		int end = body.indexOf('"', start);
		if (end < 0)
			throw new AssertionError("Unterminated requestState in " + body);
		return body.substring(start, end);
	}

	private static McpApplicationExecutionSnapshot awaitApplication(
			McpHttpServerRuntime runtime,
			Predicate<McpApplicationExecutionSnapshot> condition) throws Exception {
		AtomicReference<McpApplicationExecutionSnapshot> latest =
				new AtomicReference<>();
		awaitCondition(() -> runtime.applicationExecutionSnapshot()
				.map(snapshot -> {
					latest.set(snapshot);
					return condition.test(snapshot);
				}).orElse(false));
		return latest.get();
	}

	private static McpRequestExecutionSnapshot awaitRequest(
			McpHttpServerRuntime runtime,
			Predicate<McpRequestExecutionSnapshot> condition) throws Exception {
		AtomicReference<McpRequestExecutionSnapshot> latest =
				new AtomicReference<>();
		awaitCondition(() -> {
			McpRequestExecutionSnapshot snapshot = runtime.requestExecutionSnapshot();
			latest.set(snapshot);
			return condition.test(snapshot);
		});
		return latest.get();
	}

	private static void awaitCondition(BooleanSupplier condition)
			throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		do {
			if (condition.getAsBoolean())
				return;
			Thread.sleep(5);
		} while (System.nanoTime() - deadline < 0L);
		throw new AssertionError("Timed out waiting for the race boundary.");
	}

	private static void awaitUninterruptibly(CountDownLatch latch) {
		while (true) {
			try {
				latch.await();
				return;
			} catch (InterruptedException ignored) {
				// Model application code that ignores cooperative cancelation.
			}
		}
	}

	private static McpJsonRpcId stringId(String value) {
		return new McpJsonRpcId.StringId(value);
	}

	private static String stringId(McpJsonRpcId id) {
		return ((McpJsonRpcId.StringId) id).value();
	}

	private enum OpenTermination {
		DEADLINE,
		DISCONNECT
	}

	private enum SealTermination {
		DISCONNECT(StreamTerminationReason.CLIENT_DISCONNECTED),
		DEADLINE(StreamTerminationReason.RESPONSE_TIMEOUT),
		SHUTDOWN(StreamTerminationReason.SERVER_STOPPING);

		private final StreamTerminationReason reason;

		SealTermination(StreamTerminationReason reason) {
			this.reason = reason;
		}

		private StreamTerminationReason reason() {
			return reason;
		}
	}

	private enum HoldTermination {
		DISCONNECT(StreamTerminationReason.CLIENT_DISCONNECTED),
		DEADLINE(StreamTerminationReason.RESPONSE_TIMEOUT);

		private final StreamTerminationReason reason;

		HoldTermination(StreamTerminationReason reason) {
			this.reason = reason;
		}

		private StreamTerminationReason reason() {
			return reason;
		}
	}

	private enum BranchResult {
		COMPLETE,
		REEMIT
	}

	private record FixedResponse(int status, String body) {
	}

	@ThreadSafe
	private static final class ControllableClock implements McpApplicationClock {
		private final AtomicLong nanoseconds = new AtomicLong();

		@Override
		public long nanoTime() {
			return nanoseconds.get();
		}

		private void advance(Duration duration) {
			nanoseconds.addAndGet(duration.toNanos());
		}
	}

	@ThreadSafe
	private static final class BlockGate {
		private final AtomicInteger claims;
		private final CountDownLatch entered;
		private final CountDownLatch release;

		private BlockGate(int participants) {
			this.claims = new AtomicInteger(participants);
			this.entered = new CountDownLatch(participants);
			this.release = new CountDownLatch(1);
		}

		private void blockIfClaimed() {
			int remaining;
			do {
				remaining = claims.get();
				if (remaining == 0)
					return;
			} while (!claims.compareAndSet(remaining, remaining - 1));
			entered.countDown();
			awaitUninterruptibly(release);
		}

		private void awaitEntered() throws InterruptedException {
			Assertions.assertTrue(entered.await(5, TimeUnit.SECONDS),
					"The protected-state callback did not enter its gate.");
		}

		private void release() {
			release.countDown();
		}
	}

	@ThreadSafe
	private static final class BlockingProtector
			implements RequestStateProtectionAdapter {
		private final AtomicInteger sequence = new AtomicInteger();
		private final AtomicInteger seals = new AtomicInteger();
		private final AtomicInteger opens = new AtomicInteger();
		private final Map<String, ProtectedState> states =
				new ConcurrentHashMap<>();
		private final AtomicReference<BlockGate> sealGate = new AtomicReference<>();
		private final AtomicReference<BlockGate> openGate = new AtomicReference<>();

		private BlockGate blockNextSeals(int participants) {
			BlockGate gate = new BlockGate(participants);
			sealGate.set(gate);
			return gate;
		}

		private BlockGate blockNextOpens(int participants) {
			BlockGate gate = new BlockGate(participants);
			openGate.set(gate);
			return gate;
		}

		@Override
		public void validateStructure(@NonNull String protectedState) {
			if (protectedState.isEmpty())
				throw new IllegalArgumentException("Protected state must not be empty.");
		}

		@Override
		@NonNull
		public String seal(@NonNull RequestStateProtectionInput input,
				byte @NonNull [] canonicalPlaintext) {
			seals.incrementAndGet();
			BlockGate gate = sealGate.get();
			if (gate != null)
				gate.blockIfClaimed();
			String protectedState = "race-state-" + sequence.incrementAndGet();
			states.put(protectedState, new ProtectedState(
					input.associatedData(), canonicalPlaintext));
			return protectedState;
		}

		@Override
		public byte @NonNull [] open(@NonNull RequestStateProtectionInput input,
				@NonNull String protectedState)
				throws com.soklet.McpRequestStateProtectionException {
			opens.incrementAndGet();
			BlockGate gate = openGate.get();
			if (gate != null)
				gate.blockIfClaimed();
			ProtectedState state = states.get(protectedState);
			if (state == null || !state.matches(input.associatedData()))
				throw com.soklet.McpRequestStateProtectionException
						.fromInvalidState();
			return state.plaintext();
		}

		private int seals() {
			return seals.get();
		}

		private int opens() {
			return opens.get();
		}
	}

	@ThreadSafe
	private record ProtectedState(byte @NonNull [] associatedData,
			byte @NonNull [] plaintext) {
		private ProtectedState {
			associatedData = associatedData.clone();
			plaintext = plaintext.clone();
		}

		private boolean matches(byte[] candidate) {
			return MessageDigest.isEqual(associatedData, candidate);
		}

		@Override
		public byte @NonNull [] plaintext() {
			return plaintext.clone();
		}
	}
}
