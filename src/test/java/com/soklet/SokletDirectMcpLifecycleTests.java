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

import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge;
import com.soklet.internal.microhttp.EventLoop;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.Selector;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static java.util.Objects.requireNonNull;

/** Standalone and direct-owner acceptance at the real MCP lifecycle boundary. */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
final class SokletDirectMcpLifecycleTests {
	@NonNull
	private static final String HOST = "127.0.0.1";
	@NonNull
	private static final String PATH = "/mcp/direct-lifecycle";
	@NonNull
	private static final String PROTOCOL_VERSION = "2026-07-28";
	@NonNull
	private final Set<ExecutorService> executors =
			java.util.concurrent.ConcurrentHashMap.newKeySet();

	@AfterEach
	void tearDown() {
		List<ExecutorService> snapshot = List.copyOf(this.executors);
		for (ExecutorService executor : snapshot)
			executor.shutdownNow();
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
		boolean interrupted = false;
		int unterminated = 0;
		for (ExecutorService executor : snapshot) {
			while (!executor.isTerminated()) {
				long remaining = deadline - System.nanoTime();
				if (remaining <= 0L)
					break;
				try {
					executor.awaitTermination(remaining, TimeUnit.NANOSECONDS);
				} catch (InterruptedException exception) {
					interrupted = true;
				}
			}
			if (!executor.isTerminated())
				unterminated++;
		}
		this.executors.clear();
		if (interrupted)
			Thread.currentThread().interrupt();
		Assertions.assertEquals(0, unterminated,
				"Direct-MCP test executors did not terminate");
	}

	@Test
	void blockingSubscriptionPublisherTimeoutRetainsListenerUntilLateReturn()
			throws Exception {
		BlockingPublisher publisher = new BlockingPublisher();
		McpFixture fixture = blockingFixture(publisher, timeoutPolicy());
		ExecutorService executor = newExecutor();
		Future<Throwable> start = executor.submit(() ->
				captureFailure(fixture.soklet()::start));

		try {
			Assertions.assertTrue(publisher.awaitEntered(),
					"The publisher did not reach its post-bind startup callback");
			InetSocketAddress address = boundAddress(fixture.server());

			Throwable failure = start.get(5, TimeUnit.SECONDS);
			SokletStartupException startupFailure = Assertions.assertInstanceOf(
					SokletStartupException.class, failure);
			Assertions.assertEquals(InternalStartupDisposition.TIMED_OUT,
					startupFailure.getInternalStartupDisposition());
			Throwable exactTimeout = startupFailure.getCause();
			Assertions.assertEquals(TimeoutException.class,
					exactTimeout.getClass());
			Assertions.assertNull(exactTimeout.getCause());

			assertIncompleteBlockedStartup(fixture, startupFailure, address);
			Assertions.assertTrue(publisher.awaitInterrupted(),
					"The bounded owner did not interrupt the blocked startup call");
			Assertions.assertFalse(publisher.hasReturned());
			Assertions.assertFalse(runtimeLifecycleFlag(fixture.server(),
					"lifecycleQuiesceRequested"),
					"The timeout coordinator entered MCP quiesce while subscribe() was live");
			Assertions.assertFalse(runtimeLifecycleFlag(fixture.server(),
					"lifecycleForceRequested"),
					"The timeout coordinator entered MCP force while subscribe() was live");
			assertPortInUse(address);

			InternalShutdownResult result = fixture.soklet().getDirectLifecycle()
					.result().orElseThrow();
			CompletionStage<ShutdownResult> stage = fixture.soklet()
					.getDirectLifecycle().shutdown();
			Assertions.assertSame(stage,
					fixture.soklet().getDirectLifecycle().shutdown());
			Assertions.assertSame(result,
					stage.toCompletableFuture().get(3, TimeUnit.SECONDS)
							.internalResult());
			assertRepeatedIncompleteStop(fixture.soklet(), result);

			publisher.release();
			Assertions.assertTrue(publisher.awaitReturned());
			Assertions.assertTrue(publisher.awaitRegistrationClosed(),
					"The late publisher registration was not contained");
			Assertions.assertEquals(1, publisher.registrationCloseCount());
			assertLateCleanupCannotRewrite(fixture, result, stage, address);
		} finally {
			forceCleanup(fixture, publisher, start);
		}
	}

	@Test
	void externalShutdownCancelsBlockingPublisherWithSameTerminalIdentity()
			throws Exception {
		BlockingPublisher publisher = new BlockingPublisher();
		McpFixture fixture = blockingFixture(publisher, cancellationPolicy());
		ExecutorService executor = newExecutor();
		Future<Throwable> start = executor.submit(() ->
				captureFailure(fixture.soklet()::start));

		try {
			Assertions.assertTrue(publisher.awaitEntered(),
					"The publisher did not reach its post-bind startup callback");
			InetSocketAddress address = boundAddress(fixture.server());
			EventLoop loop = eventLoop(fixture.server());
			Assertions.assertTrue(loop.isAccepting());
			CompletionStage<ShutdownResult> stage = fixture.soklet()
					.getDirectLifecycle().shutdown();
			Assertions.assertSame(stage,
					fixture.soklet().getDirectLifecycle().shutdown());

			Throwable failure = start.get(5, TimeUnit.SECONDS);
			SokletStartupException startupFailure = Assertions.assertInstanceOf(
					SokletStartupException.class, failure);
			Assertions.assertEquals(InternalStartupDisposition.CANCELLED,
					startupFailure.getInternalStartupDisposition());
			Throwable exactCancellation = startupFailure.getCause();
			Assertions.assertEquals(IllegalStateException.class,
					exactCancellation.getClass());
			Assertions.assertEquals(
					"Soklet shutdown was requested during startup",
					exactCancellation.getMessage());
			Assertions.assertNull(exactCancellation.getCause());

			assertIncompleteBlockedStartup(fixture, startupFailure, address);
			Assertions.assertTrue(publisher.awaitInterrupted(),
					"External shutdown did not interrupt the blocked startup call");
			Assertions.assertFalse(publisher.hasReturned());
			Assertions.assertFalse(runtimeLifecycleFlag(fixture.server(),
					"lifecycleQuiesceRequested"),
					"The coordinator entered MCP quiesce while subscribe() was live");
			Assertions.assertFalse(runtimeLifecycleFlag(fixture.server(),
					"lifecycleForceRequested"),
					"The coordinator entered MCP force while subscribe() was live");
			Assertions.assertFalse(runtimeLifecycleFlag(fixture.server(),
					"lifecycleQuiesced"),
					"Graceful cleanup entered while subscribe() was live");
			Assertions.assertFalse(runtimeLifecycleFlag(fixture.server(),
					"lifecycleForced"),
					"Forced cleanup entered while subscribe() was live");
			Assertions.assertTrue(loop.isAccepting(),
					"The listener was wound up while subscribe() was live");
			assertPortInUse(address);

			InternalShutdownResult result = fixture.soklet().getDirectLifecycle()
					.result().orElseThrow();
			Assertions.assertSame(result,
					stage.toCompletableFuture().get(3, TimeUnit.SECONDS)
							.internalResult());
			assertRepeatedIncompleteStop(fixture.soklet(), result);

			publisher.release();
			Assertions.assertTrue(publisher.awaitReturned());
			Assertions.assertTrue(publisher.awaitRegistrationClosed(),
					"The late publisher registration was not contained");
			Assertions.assertEquals(1, publisher.registrationCloseCount());
			assertLateCleanupCannotRewrite(fixture, result, stage, address);
		} finally {
			forceCleanup(fixture, publisher, start);
		}
	}

	@Test
	void synchronousMcpStartupCleanupFailureRemainsBoundedSecondaryEvidence()
			throws Exception {
		IllegalStateException startupFailure = new IllegalStateException(
				"subscription startup failure");
		IllegalStateException cleanupFailure = new IllegalStateException(
				"application-executor cleanup failure");
		McpSubscriptionEventPublisher publisher =
				new McpSubscriptionEventPublisher() {
					@Override
					@NonNull
					public McpSubscriptionEventRegistration subscribe(
							@NonNull McpSubscriptionEventListener listener) {
						throw startupFailure;
					}

					@Override
					public void publish(@NonNull McpSubscriptionEvent event) {
						// Startup-only fixture; no events are published.
					}
				};
		ThrowingShutdownExecutorService applicationExecutor =
				new ThrowingShutdownExecutorService(cleanupFailure);
		this.executors.add(applicationExecutor);
		McpEndpoint endpoint = McpEndpoint.withPath(PATH)
				.serverInformation(implementation("direct-cleanup-evidence"))
				.resourceListHandler((request, list, features) ->
						McpResourcePage.builder().build())
				.subscriptions(McpSubscriptionConfig.withEventPublisher(publisher)
						.notificationType(McpSubscriptionNotificationType
								.RESOURCES_LIST_CHANGED).build())
				.build();
		McpServer server = serverBuilder(endpoint)
				.requestHandlerExecutorServiceSupplier(() -> applicationExecutor)
				.build();
		Soklet soklet = Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecyclePolicy(cancellationPolicy()).build());

		try {
			SokletStartupException thrown = Assertions.assertInstanceOf(
					SokletStartupException.class,
					captureFailure(soklet::start));
			Assertions.assertSame(startupFailure, thrown.getCause());
			Assertions.assertTrue(applicationExecutor.awaitShutdownAttempted(),
					"Failed-start unwind did not reach executor cleanup");
			Throwable[] suppressed = startupFailure.getSuppressed();
			Assertions.assertEquals(1, suppressed.length);
			Assertions.assertSame(cleanupFailure, suppressed[0]);
			InternalParticipantShutdownResult mcp = thrown
					.getInternalShutdownResult()
					.participantResult(InternalParticipantKind.MCP).orElseThrow();
			Assertions.assertSame(startupFailure, mcp.failures().get(0));
			Assertions.assertSame(thrown.getInternalShutdownResult(),
					soklet.getDirectLifecycle().result().orElseThrow());
		} finally {
			forceRuntimeQuietly(server);
			soklet.getDirectLifecycle().shutdown();
		}
	}

	@Test
	void lateMcpStartupFailuresCannotMutateFrozenEventLoopPrimary()
			throws Exception {
		IllegalStateException lateStartupFailure = new IllegalStateException(
				"late subscription startup failure");
		IllegalStateException lateCleanupFailure = new IllegalStateException(
				"late application-executor cleanup failure");
		LateFailingPublisher publisher = new LateFailingPublisher(
				lateStartupFailure);
		ThrowingShutdownExecutorService applicationExecutor =
				new ThrowingShutdownExecutorService(lateCleanupFailure);
		this.executors.add(applicationExecutor);
		CountDownLatch transportFailureObserved = new CountDownLatch(1);
		AtomicReference<LogEvent> transportFailureLog = new AtomicReference<>();
		LifecycleObserver observer = new LifecycleObserver() {
			@Override
			public void didReceiveLogEvent(@NonNull LogEvent event) {
				if (event.getLogEventType()
						!= LogEventType.SERVER_TRANSPORT_FAILURE)
					return;
				transportFailureLog.compareAndSet(null, event);
				transportFailureObserved.countDown();
			}
		};
		McpEndpoint endpoint = McpEndpoint.withPath(PATH)
				.serverInformation(implementation("direct-frozen-mcp-primary"))
				.resourceListHandler((request, list, features) ->
						McpResourcePage.builder().build())
				.subscriptions(McpSubscriptionConfig.withEventPublisher(publisher)
						.notificationType(McpSubscriptionNotificationType
								.RESOURCES_LIST_CHANGED).build())
				.build();
		McpServer server = serverBuilder(endpoint)
				.requestHandlerExecutorServiceSupplier(() -> applicationExecutor)
				.build();
		Soklet soklet = Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecycleObserver(observer)
				.lifecyclePolicy(cancellationPolicy()).build());
		ExecutorService executor = newExecutor();
		Future<Throwable> start = executor.submit(() ->
				captureFailure(soklet::start));

		try {
			Assertions.assertTrue(publisher.awaitEntered(),
					"The late-failing publisher did not enter startup");
			closeEventLoopSelector(server);
			Assertions.assertTrue(transportFailureObserved.await(5,
					TimeUnit.SECONDS),
					"The startup EventLoop failure was not observed");

			SokletStartupException startupFailure = Assertions.assertInstanceOf(
					SokletStartupException.class,
					start.get(5, TimeUnit.SECONDS));
			Assertions.assertTrue(publisher.awaitInterrupted(),
					"The blocked startup call was not canceled before result freeze");
			Assertions.assertFalse(publisher.hasReturned(),
					"The publisher must remain live through result freeze");
			LogEvent transportFailure = requireNonNull(transportFailureLog.get());
			Assertions.assertEquals("MCP transport failure: event_loop_terminate",
					transportFailure.getMessage());
			Assertions.assertTrue(transportFailure.getThrowable().isEmpty());
			Assertions.assertTrue(transportFailure.getRequest().isEmpty());
			Assertions.assertTrue(transportFailure.getResourceMethod().isEmpty());
			Assertions.assertTrue(transportFailure.getMarshaledResponse().isEmpty());
			Throwable exactPrimary = requireNonNull(startupFailure.getCause());
			InternalShutdownResult frozenResult = startupFailure
					.getInternalShutdownResult();
			InternalParticipantShutdownResult mcp = frozenResult
					.participantResult(InternalParticipantKind.MCP).orElseThrow();
			Assertions.assertSame(exactPrimary, mcp.failures().get(0));
			Throwable[] frozenSuppressed = exactPrimary.getSuppressed();

			publisher.release();
			Assertions.assertTrue(publisher.awaitReturned());
			Assertions.assertTrue(applicationExecutor.awaitShutdownAttempted(),
					"Late startup unwind did not reach executor cleanup");
			awaitCondition(() -> !runtimeLifecycleFlag(server,
					"lifecycleStartupInProgress"),
					"Late MCP startup did not finish unwinding");
			Assertions.assertTrue(runtimeLifecycleFlag(server,
					"lifecycleQuiesced"),
					"Late startup did not unwind its owned resources");
			Assertions.assertFalse(runtimeLifecycleFlag(server,
					"lifecycleForceRequested"),
					"A frozen phase cannot be queued after result publication");
			Assertions.assertFalse(runtimeLifecycleFlag(server,
					"lifecycleForced"),
					"A late startup return cannot replay force after result freeze");

			assertSameThrowableArray(frozenSuppressed,
					exactPrimary.getSuppressed());
			Assertions.assertFalse(List.of(exactPrimary.getSuppressed())
					.contains(lateStartupFailure));
			Assertions.assertFalse(List.of(exactPrimary.getSuppressed())
					.contains(lateCleanupFailure));
			Assertions.assertSame(frozenResult, soklet.getDirectLifecycle()
					.result().orElseThrow());
		} finally {
			publisher.release();
			try {
				start.get(3, TimeUnit.SECONDS);
			} catch (Throwable ignored) {
				start.cancel(true);
			}
			forceRuntimeQuietly(server);
			soklet.getDirectLifecycle().shutdown();
		}
	}

	@Test
	void admittedMcpHandlerSelfStopPublishesIntentAndFailsFastWithoutSelfJoin()
			throws Exception {
		CountDownLatch handlerReachedGate = new CountDownLatch(1);
		CountDownLatch releaseHandler = new CountDownLatch(1);
		CountDownLatch handlerInterrupted = new CountDownLatch(1);
		CountDownLatch handlerExited = new CountDownLatch(1);
		AtomicReference<Soklet> ownerReference = new AtomicReference<>();
		AtomicReference<InternalLifecycleStateMachine.State> handlerState =
				new AtomicReference<>();
		AtomicReference<CompletionStage<ShutdownResult>> handlerStage =
				new AtomicReference<>();
		AtomicBoolean repeatedHandlerStageIdentity = new AtomicBoolean();
		AtomicInteger willStopMcp = new AtomicInteger();
		AtomicInteger didStopMcp = new AtomicInteger();
		AtomicReference<ParticipantShutdownDisposition> stopOutcome = new AtomicReference<>();
		String toolName = "direct.self-stop";
		McpToolRegistration<McpJsonObject> tool = McpToolRegistration
				.withName(toolName).jsonArguments()
				.handler((request, arguments, features) -> {
					Soklet owner = ownerReference.get();
					CompletionStage<ShutdownResult> stage = owner.shutdown();
					repeatedHandlerStageIdentity.set(stage == owner.shutdown());
					handlerStage.set(stage);
					handlerState.set(owner.getDirectLifecycle().state());
					handlerReachedGate.countDown();
					try {
						awaitIgnoringInterrupts(releaseHandler,
								handlerInterrupted);
						return McpCompleteResult.fromToolText("released");
					} finally {
						handlerExited.countDown();
					}
				}).build();
		McpEndpoint endpoint = McpEndpoint.withPath(PATH)
				.serverInformation(implementation("direct-handler-self-stop"))
				.tool(tool).build();
		McpServer server = serverBuilder(endpoint).build();
		LifecycleObserver observer = new LifecycleObserver() {
			@Override
			public void willStopMcpServer(@NonNull McpServer ignored) {
				willStopMcp.incrementAndGet();
			}

			@Override
			public void didStopMcpServer(@NonNull McpServer ignored,
					@NonNull ParticipantShutdownResult result) {
				didStopMcp.incrementAndGet();
				stopOutcome.set(result.getDisposition());
			}
		};
		Soklet soklet = Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecycleObserver(observer)
				.lifecyclePolicy(handlerPolicy()).build());
		ownerReference.set(soklet);
		ExecutorService executor = newExecutor();
		CompletableFuture<HttpResponse<String>> request = null;
		Future<Throwable> externalStop = null;

		try {
			soklet.start();
			InetSocketAddress address = boundAddress(server);
			request = callTool(address.getPort(), toolName);
			Assertions.assertTrue(handlerReachedGate.await(5, TimeUnit.SECONDS),
					"The real MCP application handler did not reach self-stop");

			Assertions.assertEquals(
					InternalLifecycleStateMachine.State.SHUTTING_DOWN,
					handlerState.get());
			Assertions.assertEquals(SokletStatus.SHUTTING_DOWN,
					soklet.getStatus());
			Assertions.assertTrue(repeatedHandlerStageIdentity.get());

			HttpResponse<String> quiescedResponse = request.get(3,
					TimeUnit.SECONDS);
			Assertions.assertEquals(503, quiescedResponse.statusCode());
			Assertions.assertTrue(quiescedResponse.body().isEmpty());
			awaitCondition(() -> server.getDiagnostics().getStatus()
					== McpServerStatus.SHUTTING_DOWN
					&& server.getDiagnostics().getActiveHandlerExecutions() == 1,
					"MCP diagnostics did not retain the gated handler");
			Assertions.assertEquals(address, boundAddress(server));
			Assertions.assertEquals(1L, handlerInterrupted.getCount(),
					"Graceful shutdown must not interrupt the admitted handler");
			Assertions.assertEquals(1L, handlerExited.getCount());

			externalStop = executor.submit(() -> captureFailure(soklet::close));
			Assertions.assertFalse(externalStop.isDone());
			releaseHandler.countDown();
			Assertions.assertTrue(handlerExited.await(3, TimeUnit.SECONDS));
			Assertions.assertNull(externalStop.get(3, TimeUnit.SECONDS));

			InternalShutdownResult result = soklet.getDirectLifecycle()
					.awaitCompletion();
			CompletionStage<ShutdownResult> stage = handlerStage.get();
			Assertions.assertSame(stage, soklet.shutdown());
			Assertions.assertSame(result, stage.toCompletableFuture()
					.get(3, TimeUnit.SECONDS).internalResult());
			Assertions.assertSame(result,
					soklet.getDirectLifecycle().result().orElseThrow());
			Assertions.assertSame(result,
					((DefaultMcpServer) server).getLifecycleAdapter()
							.result().orElseThrow());
			Assertions.assertEquals(InternalLifecycleStateMachine.State.CLOSED,
					soklet.getDirectLifecycle().state());
			Assertions.assertEquals(InternalStartupDisposition.READY,
					result.startupDisposition());
			Assertions.assertEquals(InternalShutdownDisposition.GRACEFUL,
					result.disposition());
			Assertions.assertEquals(1, result.participantResults().size());
			InternalParticipantShutdownResult mcp = result.participantResult(
					InternalParticipantKind.MCP).orElseThrow();
			Assertions.assertEquals(
					InternalParticipantShutdownDisposition.GRACEFUL_TERMINATION,
					mcp.disposition());
			Assertions.assertTrue(mcp.failures().isEmpty());
			Assertions.assertTrue(mcp.residualActivity().isEmpty());
			Assertions.assertEquals(McpServerStatus.TERMINATED,
					server.getDiagnostics().getStatus());
			Assertions.assertEquals(address, boundAddress(server));
			Assertions.assertEquals(0,
					server.getDiagnostics().getActiveHandlerExecutions());
			Assertions.assertEquals(0,
					server.getDiagnostics().getQueuedRequests());
			Assertions.assertEquals(0,
					server.getDiagnostics().getActiveRequestStreams());
			Assertions.assertEquals(0,
					server.getDiagnostics().getActiveSubscriptions());
			awaitCondition(() -> willStopMcp.get() == 1
					&& didStopMcp.get() == 1,
					"MCP stop callbacks did not publish exactly once");
			Assertions.assertEquals(ParticipantShutdownDisposition.GRACEFUL_TERMINATION,
					stopOutcome.get());
			Assertions.assertDoesNotThrow(soklet::close);
		} finally {
			releaseHandler.countDown();
			if (request != null)
				request.cancel(true);
			if (externalStop != null)
				externalStop.cancel(true);
			forceRuntimeQuietly(server);
			soklet.getDirectLifecycle().shutdown();
		}
	}

	private static void assertIncompleteBlockedStartup(@NonNull McpFixture fixture,
			@NonNull SokletStartupException startupFailure,
			@NonNull InetSocketAddress address) {
		InternalShutdownResult result = startupFailure.getInternalShutdownResult();
		Assertions.assertSame(result, fixture.soklet().getDirectLifecycle()
				.result().orElseThrow());
		Assertions.assertSame(result,
				((DefaultMcpServer) fixture.server()).getLifecycleAdapter()
						.result().orElseThrow());
		Assertions.assertEquals(InternalLifecycleStateMachine.State.CLOSED,
				fixture.soklet().getDirectLifecycle().state());
		Assertions.assertEquals(SokletStatus.CLOSED,
				fixture.soklet().getStatus());
		Assertions.assertEquals(InternalShutdownDisposition.INCOMPLETE,
				result.disposition());
		Assertions.assertFalse(result.isComplete());
		Assertions.assertEquals(1, result.participantResults().size());
		InternalParticipantShutdownResult mcp = result.participantResult(
				InternalParticipantKind.MCP).orElseThrow();
		Assertions.assertEquals(
				InternalParticipantShutdownDisposition.TERMINATION_UNKNOWN,
				mcp.disposition());
		Assertions.assertTrue(mcp.failures().isEmpty());
		Assertions.assertEquals(Set.of(InternalResidualActivityKind.EVENT_LOOP,
				InternalResidualActivityKind.EXECUTOR_TASK,
				InternalResidualActivityKind.CALLBACK,
				InternalResidualActivityKind.LIFECYCLE_CALL),
				mcp.residualActivity());
		Assertions.assertEquals(McpServerStatus.TERMINATION_UNKNOWN,
				fixture.server().getDiagnostics().getStatus());
		Assertions.assertEquals(address, boundAddress(fixture.server()));
	}

	private static void assertRepeatedIncompleteStop(@NonNull Soklet soklet,
			@NonNull InternalShutdownResult result) {
		ShutdownIncompleteException stopFailure = Assertions.assertThrows(
				ShutdownIncompleteException.class, soklet::close);
		Assertions.assertSame(result, stopFailure.getInternalShutdownResult());
		ShutdownIncompleteException closeFailure = Assertions.assertThrows(
				ShutdownIncompleteException.class, soklet::close);
		Assertions.assertSame(result, closeFailure.getInternalShutdownResult());
	}

	private static void assertLateCleanupCannotRewrite(@NonNull McpFixture fixture,
			@NonNull InternalShutdownResult result,
			@NonNull CompletionStage<ShutdownResult> stage,
			@NonNull InetSocketAddress address) throws Exception {
		awaitCondition(() -> fixture.server().getDiagnostics()
				.getActiveHandlerExecutions() == 0,
				"Late MCP startup cleanup did not settle diagnostics");
		awaitCondition(() -> !runtimeLifecycleFlag(fixture.server(),
				"lifecycleStartupInProgress"),
				"Late MCP startup did not complete its cleanup handoff");
		Assertions.assertTrue(runtimeLifecycleFlag(fixture.server(),
				"lifecycleQuiesced"),
				"The returning startup worker did not unwind its owned resources");
		Assertions.assertFalse(runtimeLifecycleFlag(fixture.server(),
				"lifecycleForceRequested"),
				"A phase frozen while start was live cannot be queued into MCP");
		Assertions.assertFalse(runtimeLifecycleFlag(fixture.server(),
				"lifecycleForced"),
				"A return after result freeze cannot replay the forced phase");
		awaitCondition(() -> isPortReusable(address),
				"Late MCP startup unwind did not release the listener");
		Assertions.assertSame(result, fixture.soklet().getDirectLifecycle()
				.result().orElseThrow());
		Assertions.assertSame(stage,
				fixture.soklet().getDirectLifecycle().shutdown());
		Assertions.assertEquals(address, boundAddress(fixture.server()));
		Assertions.assertEquals(SokletStatus.CLOSED,
				fixture.soklet().getStatus());
		Assertions.assertThrows(IllegalStateException.class,
				fixture.soklet()::start);
		assertPortReusable(address);
	}

	@NonNull
	private static McpFixture blockingFixture(@NonNull BlockingPublisher publisher,
			@NonNull LifecyclePolicy policy) {
		McpEndpoint endpoint = McpEndpoint.withPath(PATH)
				.serverInformation(implementation("direct-blocked-publisher"))
				.resourceListHandler((request, list, features) ->
						McpResourcePage.builder().build())
				.subscriptions(McpSubscriptionConfig.withEventPublisher(publisher)
						.notificationType(McpSubscriptionNotificationType
								.RESOURCES_LIST_CHANGED).build())
				.build();
		McpServer server = serverBuilder(endpoint).build();
		Soklet soklet = Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecyclePolicy(policy).build());
		return new McpFixture(server, soklet);
	}

	private static McpServer.@NonNull Builder serverBuilder(
			@NonNull McpEndpoint endpoint) {
		return McpServer.withPort(0).host(HOST)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.admissionController(McpAdmissionController.acceptAllInstance())
				.toolRateLimiter(context -> McpRateLimitDecision.allowed())
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(HOST))
				.requestHandlerConcurrency(1)
				.requestHandlerQueueCapacity(1);
	}

	@NonNull
	private static McpImplementation implementation(@NonNull String name) {
		return McpImplementation.withNameAndVersion(name, "4.0.0-SNAPSHOT").build();
	}

	@NonNull
	private static LifecyclePolicy timeoutPolicy() {
		return LifecyclePolicy.builder()
				.startupTimeout(Duration.ofSeconds(1))
				.startupCancellationTimeout(Duration.ofMillis(150))
				.gracefulShutdownDuration(Duration.ofMillis(150))
				.forcedShutdownDuration(Duration.ofMillis(250))
				.build();
	}

	@NonNull
	private static LifecyclePolicy cancellationPolicy() {
		return LifecyclePolicy.builder()
				.startupTimeout(Duration.ofSeconds(10))
				.startupCancellationTimeout(Duration.ofMillis(150))
				.gracefulShutdownDuration(Duration.ofMillis(150))
				.forcedShutdownDuration(Duration.ofMillis(250))
				.build();
	}

	@NonNull
	private static LifecyclePolicy handlerPolicy() {
		return LifecyclePolicy.builder()
				.startupTimeout(Duration.ofSeconds(5))
				.startupCancellationTimeout(Duration.ofMillis(250))
				.gracefulShutdownDuration(Duration.ofSeconds(5))
				.forcedShutdownDuration(Duration.ofSeconds(1))
				.build();
	}

	@NonNull
	private static CompletableFuture<HttpResponse<String>> callTool(int port,
			@NonNull String toolName) {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"direct-self-stop\","
				+ "\"method\":\"tools/call\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}},"
				+ "\"name\":\"" + toolName + "\",\"arguments\":{}}}";
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("http://" + HOST + ':' + port + PATH))
				.timeout(Duration.ofSeconds(10))
				.header("Content-Type", "application/json; charset=UTF-8")
				.header("Accept", "application/json, text/event-stream")
				.header("MCP-Protocol-Version", PROTOCOL_VERSION)
				.header("Mcp-Method", "tools/call")
				.header("Mcp-Name", toolName)
				.POST(HttpRequest.BodyPublishers.ofString(body,
						StandardCharsets.UTF_8)).build();
		return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
				.version(HttpClient.Version.HTTP_1_1).build()
				.sendAsync(request, HttpResponse.BodyHandlers.ofString(
						StandardCharsets.UTF_8));
	}

	private ExecutorService newExecutor() {
		ExecutorService executor = Executors.newCachedThreadPool();
		this.executors.add(executor);
		return executor;
	}

	private static void awaitCondition(@NonNull BooleanSupplier condition,
			@NonNull String failureMessage) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (System.nanoTime() - deadline < 0L) {
			if (condition.getAsBoolean())
				return;
			Thread.sleep(10);
		}
		Assertions.assertTrue(condition.getAsBoolean(), failureMessage);
	}

	private static void awaitIgnoringInterrupts(@NonNull CountDownLatch release,
			@NonNull CountDownLatch interruptedSignal) {
		boolean interrupted = false;
		while (release.getCount() != 0L) {
			try {
				release.await();
			} catch (InterruptedException exception) {
				interrupted = true;
				interruptedSignal.countDown();
			}
		}
		if (interrupted)
			Thread.currentThread().interrupt();
	}

	@NonNull
	private static Throwable captureFailure(@NonNull Runnable invocation) {
		try {
			invocation.run();
			return null;
		} catch (Throwable failure) {
			return failure;
		}
	}

	@NonNull
	private static InetSocketAddress boundAddress(@NonNull McpServer server) {
		return server.getDiagnostics().getBoundAddress().orElseThrow();
	}

	private static void assertPortReusable(@NonNull InetSocketAddress address)
			throws Exception {
		try (ServerSocket socket = new ServerSocket()) {
			socket.setReuseAddress(true);
			socket.bind(address);
		}
	}

	private static void assertPortInUse(@NonNull InetSocketAddress address) {
		Assertions.assertFalse(isPortReusable(address),
				"A live startup call must retain its bound listener until it returns");
	}

	private static boolean isPortReusable(@NonNull InetSocketAddress address) {
		try (ServerSocket socket = new ServerSocket()) {
			socket.setReuseAddress(true);
			socket.bind(address);
			return true;
		} catch (IOException expected) {
			return false;
		}
	}

	private static void forceCleanup(@NonNull McpFixture fixture,
			@NonNull BlockingPublisher publisher, @NonNull Future<?> start) {
		publisher.release();
		try {
			publisher.awaitReturned();
			publisher.awaitRegistrationClosed();
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
		}
		try {
			start.get(3, TimeUnit.SECONDS);
		} catch (Throwable ignored) {
			start.cancel(true);
		}
		forceRuntimeQuietly(fixture.server());
		fixture.soklet().getDirectLifecycle().shutdown();
	}

	private static void forceRuntimeQuietly(@NonNull McpServer server) {
		try {
			runtimeBridge(server).forceLifecycle();
		} catch (Throwable ignored) {
			// Failure-path cleanup must not hide the primary assertion.
		}
	}

	@NonNull
	private static McpServerRuntimeBridge runtimeBridge(
			@NonNull McpServer server) throws ReflectiveOperationException {
		Field field = DefaultMcpServer.class.getDeclaredField("runtimeBridge");
		field.setAccessible(true);
		return (McpServerRuntimeBridge) field.get(server);
	}

	@NonNull
	private static Object runtime(@NonNull McpServer server)
			throws ReflectiveOperationException {
		McpServerRuntimeBridge bridge = runtimeBridge(server);
		Field field = McpServerRuntimeBridge.class.getDeclaredField("runtime");
		field.setAccessible(true);
		return field.get(bridge);
	}

	@NonNull
	private static EventLoop eventLoop(@NonNull McpServer server)
			throws ReflectiveOperationException {
		Object runtime = runtime(server);
		Field lockField = runtime.getClass().getDeclaredField("lifecycleLock");
		Field eventLoopField = runtime.getClass().getDeclaredField("eventLoop");
		lockField.setAccessible(true);
		eventLoopField.setAccessible(true);
		Object lock = lockField.get(runtime);
		synchronized (lock) {
			return (EventLoop) eventLoopField.get(runtime);
		}
	}

	private static boolean runtimeLifecycleFlag(@NonNull McpServer server,
			@NonNull String name) {
		try {
			Object runtime = runtime(server);
			Field lockField = runtime.getClass().getDeclaredField("lifecycleLock");
			Field flagField = runtime.getClass().getDeclaredField(name);
			lockField.setAccessible(true);
			flagField.setAccessible(true);
			Object lock = lockField.get(runtime);
			synchronized (lock) {
				return flagField.getBoolean(runtime);
			}
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError(exception);
		}
	}

	private static void closeEventLoopSelector(@NonNull McpServer server)
			throws Exception {
		EventLoop loop = eventLoop(server);
		Field field = EventLoop.class.getDeclaredField("selector");
		field.setAccessible(true);
		((Selector) field.get(loop)).close();
	}

	private static void assertSameThrowableArray(Throwable @NonNull [] expected,
			Throwable @NonNull [] actual) {
		Assertions.assertEquals(expected.length, actual.length,
				"Frozen primary suppression count changed after result publication");
		for (int index = 0; index < expected.length; index++)
			Assertions.assertSame(expected[index], actual[index],
					"Frozen primary suppression identity changed at index " + index);
	}

	private record McpFixture(@NonNull McpServer server,
			@NonNull Soklet soklet) {
	}

	private static final class BlockingPublisher
			implements McpSubscriptionEventPublisher {
		@NonNull
		private final CountDownLatch entered = new CountDownLatch(1);
		@NonNull
		private final CountDownLatch interrupted = new CountDownLatch(1);
		@NonNull
		private final CountDownLatch release = new CountDownLatch(1);
		@NonNull
		private final CountDownLatch returned = new CountDownLatch(1);
		@NonNull
		private final CountDownLatch registrationClosed = new CountDownLatch(1);
		@NonNull
		private final AtomicBoolean registrationCloseClaimed = new AtomicBoolean();
		@NonNull
		private final AtomicInteger registrationCloseCount = new AtomicInteger();

		@Override
		@NonNull
		public McpSubscriptionEventRegistration subscribe(
				@NonNull McpSubscriptionEventListener listener) {
			this.entered.countDown();
			awaitIgnoringInterrupts(this.release, this.interrupted);
			this.returned.countDown();
			return () -> {
				if (this.registrationCloseClaimed.compareAndSet(false, true)) {
					this.registrationCloseCount.incrementAndGet();
					this.registrationClosed.countDown();
				}
			};
		}

		@Override
		public void publish(@NonNull McpSubscriptionEvent event) {
			// Startup-only fixture; no events are published.
		}

		boolean awaitEntered() throws InterruptedException {
			return this.entered.await(5, TimeUnit.SECONDS);
		}

		boolean awaitInterrupted() throws InterruptedException {
			return this.interrupted.await(5, TimeUnit.SECONDS);
		}

		boolean awaitReturned() throws InterruptedException {
			return this.returned.await(5, TimeUnit.SECONDS);
		}

		boolean awaitRegistrationClosed() throws InterruptedException {
			return this.registrationClosed.await(5, TimeUnit.SECONDS);
		}

		boolean hasReturned() {
			return this.returned.getCount() == 0L;
		}

		int registrationCloseCount() {
			return this.registrationCloseCount.get();
		}

		void release() {
			this.release.countDown();
		}
	}

	private static final class LateFailingPublisher
			implements McpSubscriptionEventPublisher {
		@NonNull
		private final RuntimeException failure;
		@NonNull
		private final CountDownLatch entered = new CountDownLatch(1);
		@NonNull
		private final CountDownLatch release = new CountDownLatch(1);
		@NonNull
		private final CountDownLatch returned = new CountDownLatch(1);
		@NonNull
		private final CountDownLatch interrupted = new CountDownLatch(1);

		private LateFailingPublisher(@NonNull RuntimeException failure) {
			this.failure = failure;
		}

		@Override
		@NonNull
		public McpSubscriptionEventRegistration subscribe(
				@NonNull McpSubscriptionEventListener listener) {
			entered.countDown();
			awaitIgnoringInterrupts(release, interrupted);
			returned.countDown();
			throw failure;
		}

		@Override
		public void publish(@NonNull McpSubscriptionEvent event) {
			// Startup-only fixture; no events are published.
		}

		private boolean awaitEntered() throws InterruptedException {
			return entered.await(5, TimeUnit.SECONDS);
		}

		private boolean awaitReturned() throws InterruptedException {
			return returned.await(5, TimeUnit.SECONDS);
		}

		private boolean awaitInterrupted() throws InterruptedException {
			return interrupted.await(5, TimeUnit.SECONDS);
		}

		private boolean hasReturned() {
			return returned.getCount() == 0L;
		}

		private void release() {
			release.countDown();
		}
	}

	private static final class ThrowingShutdownExecutorService
			extends AbstractExecutorService {
		@NonNull
		private final ExecutorService delegate = Executors.newSingleThreadExecutor();
		@NonNull
		private final RuntimeException shutdownFailure;
		@NonNull
		private final CountDownLatch shutdownAttempted = new CountDownLatch(1);

		private ThrowingShutdownExecutorService(
				@NonNull RuntimeException shutdownFailure) {
			this.shutdownFailure = shutdownFailure;
		}

		@Override
		public void shutdown() {
			shutdownAttempted.countDown();
			throw shutdownFailure;
		}

		@Override
		@NonNull
		public List<Runnable> shutdownNow() {
			return delegate.shutdownNow();
		}

		@Override
		public boolean isShutdown() {
			return delegate.isShutdown();
		}

		@Override
		public boolean isTerminated() {
			return delegate.isTerminated();
		}

		@Override
		public boolean awaitTermination(long timeout, @NonNull TimeUnit unit)
				throws InterruptedException {
			return delegate.awaitTermination(timeout, unit);
		}

		@Override
		public void execute(@NonNull Runnable command) {
			delegate.execute(command);
		}

		private boolean awaitShutdownAttempted() throws InterruptedException {
			return shutdownAttempted.await(5, TimeUnit.SECONDS);
		}
	}
}
