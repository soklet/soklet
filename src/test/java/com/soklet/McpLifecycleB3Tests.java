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

import com.soklet.internal.mcp.protocol.McpApplicationExecutionObserver;
import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge;
import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.EndpointPlan;
import com.soklet.internal.microhttp.ConnectionListener;
import com.soklet.internal.microhttp.EventLoop;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.annotation.concurrent.ThreadSafe;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.Selector;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.IntPredicate;
import java.util.function.LongConsumer;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

/** Concrete MCP acceptance matrix for lifecycle V4 milestone B3. */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class McpLifecycleB3Tests {
	private static final String HOST = "127.0.0.1";
	private static final String PATH = "/mcp-b3";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final Duration WAIT = Duration.ofSeconds(5);
	private static final LifecyclePolicy TEST_LIFECYCLE_POLICY =
			LifecyclePolicy.builder()
					.startupTimeout(Duration.ofSeconds(5))
					.startupCancelationTimeout(Duration.ofSeconds(2))
					.gracefulShutdownTimeout(Duration.ofSeconds(2))
					.forcedShutdownTimeout(Duration.ofSeconds(1))
					.build();

	@Test
	void unaryAdmissionIsGenerationScopedAndReleasedExactlyOnce() throws Exception {
		CountDownLatch admissionEntered = new CountDownLatch(1);
		CountDownLatch releaseAdmission = new CountDownLatch(1);
		McpEndpoint endpoint = endpoint(PATH);
		McpServer server = serverBuilder(endpoint, Duration.ofSeconds(2))
				.admissionController(context -> {
					admissionEntered.countDown();
					Assertions.assertTrue(releaseAdmission.await(10,
							TimeUnit.SECONDS),
							"Timed out waiting to release admission");
					return McpAdmissionDecision.accepted();
				})
				.build();
		Fixture fixture = fixture(server);
		CompletableFuture<HttpResponse<String>> response = null;

		try {
			fixture.soklet().start();
			McpTransportLifecycleAdapter.Generation generation = generation(server);
			response = discovery(boundPort(server), "unary-admission");
			Assertions.assertTrue(admissionEntered.await(WAIT.toNanos(),
					TimeUnit.NANOSECONDS));
			Assertions.assertEquals(1, admittedWork(generation));

			releaseAdmission.countDown();
			Assertions.assertEquals(200,
					response.get(WAIT.toNanos(), TimeUnit.NANOSECONDS).statusCode());
			awaitCondition(() -> admittedWork(generation) == 0,
					"The unary lifecycle admission was not released.");

			InetSocketAddress address = boundAddress(server);
			fixture.soklet().close();
			assertParticipant(server,
					InternalLifecycleComponentShutdownDisposition.GRACEFUL_TERMINATION);
			assertLegacyParity(fixture, ShutdownComponentDisposition.GRACEFUL_TERMINATION);
			Assertions.assertEquals(address, boundAddress(server));
			assertRuntimeEvidenceReleased(fixture.bridge());
			assertListenerReturned(address);
		} finally {
			releaseAdmission.countDown();
			if (response != null)
				response.cancel(true);
			fixture.close();
		}
	}

	@Test
	void cooperativeHandlerOutlivesPromptStreamClosureAndDrainsGracefully()
			throws Exception {
		CountDownLatch handlerEntered = new CountDownLatch(1);
		CountDownLatch releaseHandler = new CountDownLatch(1);
		CountDownLatch handlerExited = new CountDownLatch(1);
		AtomicInteger interruptions = new AtomicInteger();
		McpToolRegistration<McpJsonObject> tool = tool("b3.cooperative",
				(request, arguments, features) -> {
					features.require(McpProgressReporter.class).report(
							McpProgressUpdate.withProgress(1.0d).build());
					handlerEntered.countDown();
					try {
						Assertions.assertTrue(releaseHandler.await(10,
								TimeUnit.SECONDS),
								"Timed out waiting to release the cooperative handler");
					} catch (InterruptedException exception) {
						interruptions.incrementAndGet();
						throw exception;
					} finally {
						handlerExited.countDown();
					}
					return McpCompleteResult.fromToolText("cooperative");
				});
		McpServer server = serverBuilder(endpoint(PATH, tool), Duration.ofSeconds(2))
				.build();
		Fixture fixture = fixture(server);
		ExecutorService stopper = Executors.newSingleThreadExecutor();
		CompletableFuture<HttpResponse<String>> request = null;

		try {
			fixture.soklet().start();
			McpTransportLifecycleAdapter.Generation generation = generation(server);
			request = callTool(boundPort(server), "cooperative", tool.getName(), true);
			Assertions.assertTrue(handlerEntered.await(WAIT.toNanos(),
					TimeUnit.NANOSECONDS));
			Assertions.assertTrue(fixture.metrics().streamOpened.await(
					WAIT.toNanos(), TimeUnit.NANOSECONDS));
			Assertions.assertEquals(1, admittedWork(generation));

			Future<?> stop = stopper.submit(fixture.soklet()::close);
			awaitCondition(() -> server.getDiagnostics().getActiveRequestStreams() == 0,
					"Graceful quiesce did not close the public request stream.");
			Assertions.assertFalse(stop.isDone(),
					"Public stream closure is not affirmative handler proof.");
			Assertions.assertEquals(0, interruptions.get(),
					"Cooperative work was interrupted during the grace phase.");
			Assertions.assertEquals(1, admittedWork(generation),
					"The exact lifecycle admission must span handler exit.");
			Assertions.assertTrue(fixture.bridge().getLifecycleEvidence().callback());
			request.cancel(true);
			request = null;

			releaseHandler.countDown();
			Assertions.assertTrue(handlerExited.await(WAIT.toNanos(),
					TimeUnit.NANOSECONDS));
			stop.get(WAIT.toNanos(), TimeUnit.NANOSECONDS);
			Assertions.assertEquals(0, admittedWork(generation));
			assertParticipant(server,
					InternalLifecycleComponentShutdownDisposition.GRACEFUL_TERMINATION);
			assertLegacyParity(fixture, ShutdownComponentDisposition.GRACEFUL_TERMINATION);
			Assertions.assertEquals(List.of(McpStreamTerminationReason.SERVER_STOPPING),
					fixture.metrics().streamCloseReasons);
		} finally {
			releaseHandler.countDown();
			if (request != null)
				request.cancel(true);
			fixture.close();
			stopper.shutdownNow();
		}
	}

	@Test
	void forceResponsiveHandlerIsInterruptedOnlyAfterTheGraceDeadline()
			throws Exception {
		CountDownLatch handlerEntered = new CountDownLatch(1);
		AtomicInteger interruptions = new AtomicInteger();
		AtomicLong interruptedAt = new AtomicLong(Long.MIN_VALUE);
		McpToolRegistration<McpJsonObject> tool = tool("b3.forced",
				(request, arguments, features) -> {
					features.require(McpProgressReporter.class).report(
							McpProgressUpdate.withProgress(1.0d).build());
					handlerEntered.countDown();
					try {
						Assertions.assertTrue(new CountDownLatch(1).await(10,
								TimeUnit.SECONDS),
								"Forced shutdown did not interrupt the handler");
					} catch (InterruptedException expected) {
						interruptions.incrementAndGet();
						interruptedAt.compareAndSet(Long.MIN_VALUE, System.nanoTime());
					}
					return McpCompleteResult.fromToolText("forced");
				});
		McpServer server = serverBuilder(endpoint(PATH, tool),
				Duration.ofMillis(250)).build();
		Fixture fixture = fixture(server, shutdownPolicy(
				Duration.ofMillis(250), Duration.ofMillis(250)));
		CompletableFuture<HttpResponse<String>> request = null;

		try {
			fixture.soklet().start();
			McpTransportLifecycleAdapter.Generation generation = generation(server);
			InetSocketAddress address = boundAddress(server);
			request = callTool(address.getPort(), "forced", tool.getName(), true);
			Assertions.assertTrue(handlerEntered.await(WAIT.toNanos(),
					TimeUnit.NANOSECONDS));
			fixture.soklet().close();

			Assertions.assertEquals(1, interruptions.get());
			Assertions.assertTrue(interruptedAt.get() - gracefulDeadline(generation) >= 0L,
					"Owned handler cancellation occurred before the shared grace deadline.");
			assertParticipant(server,
					InternalLifecycleComponentShutdownDisposition.FORCED_TERMINATION);
			assertLegacyParity(fixture,
					ShutdownComponentDisposition.FORCED_TERMINATION);
			Assertions.assertEquals(List.of(McpStreamTerminationReason.SERVER_STOPPING),
					fixture.metrics().streamCloseReasons);
			Assertions.assertEquals(address, boundAddress(server));
			assertRuntimeEvidenceReleased(fixture.bridge());
			assertListenerReturned(address);
		} finally {
			if (request != null)
				request.cancel(true);
			fixture.close();
		}
	}

	@Test
	void noncooperativeHandlerClassifiesResidualAndRetainsItsGraphAndAddress()
			throws Exception {
		CountDownLatch handlerEntered = new CountDownLatch(1);
		CountDownLatch releaseHandler = new CountDownLatch(1);
		CountDownLatch handlerExited = new CountDownLatch(1);
		AtomicInteger interruptions = new AtomicInteger();
		McpToolRegistration<McpJsonObject> tool = tool("b3.residual",
				(request, arguments, features) -> {
					handlerEntered.countDown();
					try {
						while (releaseHandler.getCount() != 0L) {
							try {
								Assertions.assertTrue(releaseHandler.await(10,
										TimeUnit.SECONDS),
										"Timed out waiting to release the noncooperative handler");
							} catch (InterruptedException expected) {
								interruptions.incrementAndGet();
							}
						}
						return McpCompleteResult.fromToolText("released");
					} finally {
						handlerExited.countDown();
					}
				});
		McpServer server = serverBuilder(endpoint(PATH, tool),
				Duration.ofMillis(50)).build();
		Fixture fixture = fixture(server, shortShutdownPolicy());
		CompletableFuture<HttpResponse<String>> request = null;

		try {
			fixture.soklet().start();
			InetSocketAddress address = boundAddress(server);
			request = callTool(address.getPort(), "residual", tool.getName(), false);
			Assertions.assertTrue(handlerEntered.await(WAIT.toNanos(),
					TimeUnit.NANOSECONDS));
			SokletShutdownIncompleteException stopFailure = Assertions.assertThrows(
					SokletShutdownIncompleteException.class, fixture.soklet()::close);
			InternalShutdownResult result = stopFailure.getInternalShutdownResult();
			Assertions.assertSame(result, adapter(server).result().orElseThrow());

			Assertions.assertEquals(1, interruptions.get(),
					"Idempotent force must not repeatedly interrupt one handler.");
			assertParticipant(server,
					InternalLifecycleComponentShutdownDisposition.RESIDUAL_ACTIVITY);
			Assertions.assertEquals(McpServerStatus.RESIDUAL_ACTIVITY,
					server.getDiagnostics().getStatus());
			Assertions.assertEquals(address, boundAddress(server));
			assertIncompleteLegacyParity(fixture, result);
			Assertions.assertTrue(adapter(server).retentionSummary().orElseThrow()
					.counts().containsKey(InternalResidualActivityType.CALLBACK));
			Assertions.assertTrue(fixture.bridge().getLifecycleEvidence().callback());

			releaseHandler.countDown();
			Assertions.assertTrue(handlerExited.await(WAIT.toNanos(),
					TimeUnit.NANOSECONDS));
			awaitCondition(() -> server.getDiagnostics()
					.getActiveHandlerExecutions() == 0,
					"Late physical cleanup did not settle diagnostics.");
			Assertions.assertEquals(McpServerStatus.RESIDUAL_ACTIVITY,
					server.getDiagnostics().getStatus(),
					"Late cleanup must not rewrite the frozen residual result.");
			SokletShutdownIncompleteException repeatedStop = Assertions.assertThrows(
					SokletShutdownIncompleteException.class, fixture.soklet()::close);
			Assertions.assertSame(result,
					repeatedStop.getInternalShutdownResult());
			IllegalStateException restartRejection = Assertions.assertThrows(
					IllegalStateException.class, fixture.soklet()::start,
					"An immutable incomplete result permanently retains restart ownership.");
			Assertions.assertEquals(IllegalStateException.class,
					restartRejection.getClass());
			assertParticipant(server,
					InternalLifecycleComponentShutdownDisposition.RESIDUAL_ACTIVITY);
		} finally {
			releaseHandler.countDown();
			if (request != null)
				request.cancel(true);
			fixture.close();
		}
	}

	@Test
	void unexpectedEventLoopFailureFencesBeforeProofAndRetainsAddress()
			throws Exception {
		McpServer server = serverBuilder(endpoint(PATH), Duration.ofSeconds(1)).build();
		Fixture fixture = fixture(server);

		try {
			fixture.soklet().start();
			McpTransportLifecycleAdapter.Generation generation = generation(server);
			InetSocketAddress address = boundAddress(server);
			terminateUnexpectedly(eventLoop(fixture.bridge()));
			adapter(server).awaitStop(generation);

			InternalLifecycleComponentShutdownResult participant = assertParticipant(server,
					InternalLifecycleComponentShutdownDisposition.UNEXPECTED_TERMINATION);
			Assertions.assertInstanceOf(ClosedSelectorException.class,
					participant.failures().get(0));
			Assertions.assertEquals(List.of(InternalTerminationEvent.Type.FAILURE,
					InternalTerminationEvent.Type.PROOF),
					terminationEvents(server, generation).stream()
							.map(InternalTerminationEvent::type).toList());
			Assertions.assertTrue(generation.tryAdmit().isEmpty(),
					"Unexpected failure must fence this exact generation.");
			Assertions.assertEquals(address, boundAddress(server));
			assertRuntimeEvidenceReleased(fixture.bridge());
			SokletUnexpectedTerminationException stopFailure =
					Assertions.assertThrows(
							SokletUnexpectedTerminationException.class,
							fixture.soklet()::close);
			InternalShutdownResult result = stopFailure.getInternalShutdownResult();
			Assertions.assertSame(result, adapter(server).result().orElseThrow());
			Assertions.assertSame(participant.failures().get(0),
					stopFailure.getCause());
			assertLegacyParity(fixture,
					ShutdownComponentDisposition.UNEXPECTED_TERMINATION);
			SokletUnexpectedTerminationException repeatedStop =
					Assertions.assertThrows(
							SokletUnexpectedTerminationException.class,
							fixture.soklet()::close);
			Assertions.assertSame(result,
					repeatedStop.getInternalShutdownResult());
			assertListenerReturned(address);
		} finally {
			fixture.close();
		}
	}

	@Test
	void eventLoopFailureAfterRequestedStopRemainsOrthogonalEvidence()
			throws Exception {
		CountDownLatch handlerEntered = new CountDownLatch(1);
		CountDownLatch releaseHandler = new CountDownLatch(1);
		McpToolRegistration<McpJsonObject> tool = tool("b3-stop-failure",
				(request, arguments, features) -> {
					handlerEntered.countDown();
					Assertions.assertTrue(releaseHandler.await(10,
							TimeUnit.SECONDS),
							"Timed out waiting to release the stop-failure handler");
					return McpCompleteResult.fromToolText("stop-failure");
				});
		McpServer server = serverBuilder(endpoint(PATH, tool), Duration.ofSeconds(2))
				.build();
		Fixture fixture = fixture(server);
		CompletableFuture<HttpResponse<String>> request = null;
		AtomicReference<Throwable> stopFailure = new AtomicReference<>();
		Thread stopper = new Thread(() -> {
			try {
				fixture.soklet().close();
			} catch (Throwable failure) {
				stopFailure.set(failure);
			}
		}, "mcp-b3-requested-stop-event-loop-failure");
		stopper.setDaemon(true);

		try {
			fixture.soklet().start();
			McpTransportLifecycleAdapter.Generation generation = generation(server);
			request = callTool(boundPort(server), "stop-failure", tool.getName(), false);
			Assertions.assertTrue(handlerEntered.await(
					WAIT.toNanos(), TimeUnit.NANOSECONDS));
			EventLoop loop = eventLoop(fixture.bridge());

			stopper.start();
			awaitCondition(generation::shutdownRequested,
					"The exact MCP generation did not publish shutdown intent.");
			awaitCondition(() -> !fixture.bridge().getRuntimeState().started(),
					"Graceful quiesce did not publish STOPPING.");
			Throwable exactFailure = new ClosedSelectorException();
			connectionListener(loop).didTerminateEventLoop(loop, exactFailure);
			releaseHandler.countDown();
			stopper.join(WAIT.toMillis());
			Assertions.assertFalse(stopper.isAlive());
			Assertions.assertNull(stopFailure.get());

			InternalLifecycleComponentShutdownResult participant = mcpParticipant(
					adapter(server).result(generation).orElseThrow());
			Assertions.assertEquals(
					InternalLifecycleComponentShutdownDisposition.GRACEFUL_TERMINATION,
					participant.disposition());
			Assertions.assertEquals(1, participant.failures().size());
			Assertions.assertSame(exactFailure, participant.failures().get(0));
			Assertions.assertEquals(List.of(InternalTerminationEvent.Type.FAILURE,
					InternalTerminationEvent.Type.PROOF),
					terminationEvents(server, generation).stream()
							.map(InternalTerminationEvent::type).toList());
			assertLegacyParity(fixture,
					ShutdownComponentDisposition.GRACEFUL_TERMINATION);
		} finally {
			releaseHandler.countDown();
			if (request != null)
				request.cancel(true);
			stopper.join(WAIT.toMillis());
			fixture.close();
		}
	}

	@Test
	void oneShotOwnerCannotConsumeUnexpectedGenerationBeforeExactResultPublication()
			throws Exception {
		McpServer server = serverBuilder(endpoint(PATH), Duration.ofSeconds(1)).build();
		Fixture fixture = fixture(server);
		Fixture freshFixture = null;
		ExecutorService terminator = daemonSingleThreadExecutor(
				"mcp-b3-unexpected-restart-window");
		Future<?> termination = null;

		try {
			fixture.soklet().start();
			McpTransportLifecycleAdapter.Generation firstGeneration =
					generation(server);
			EventLoop firstEventLoop = eventLoop(fixture.bridge());
			InternalTerminationGroup terminationGroup =
					terminationGroup(firstGeneration);
			synchronized (terminationGroup) {
				termination = terminator.submit(() -> {
					terminateUnexpectedly(firstEventLoop);
					return null;
				});
				awaitCondition(() -> !fixture.bridge().getRuntimeState().started(),
						"The runtime did not publish its exact unexpected failure.");
				Assertions.assertTrue(fixture.bridge().getRuntimeState().stopRequired());
				Assertions.assertFalse(firstGeneration.shutdownRequested(),
						"The test must hold the failure signal before shutdown claim.");

				IllegalStateException restartRejection = Assertions.assertThrows(
						IllegalStateException.class, fixture.soklet()::start);
				Assertions.assertEquals(IllegalStateException.class,
						restartRejection.getClass());
				Assertions.assertSame(firstGeneration, pendingGeneration(server),
						"A premature restart must preserve the exact pending identity.");
				Assertions.assertSame(firstGeneration, generation(server));
				Assertions.assertTrue(adapter(server).result(firstGeneration).isEmpty());
				Assertions.assertTrue(fixture.metrics().shutdownOutcomes.isEmpty(),
						"A premature restart must not publish a false stopped outcome.");
			}

			requireNonNull(termination).get(WAIT.toNanos(), TimeUnit.NANOSECONDS);
			adapter(server).awaitStop(firstGeneration);
			Assertions.assertTrue(adapter(server).result(firstGeneration)
					.orElseThrow().isComplete());

			SokletUnexpectedTerminationException firstStop =
					Assertions.assertThrows(
							SokletUnexpectedTerminationException.class,
							fixture.soklet()::close);
			InternalShutdownResult firstResult = firstStop
					.getInternalShutdownResult();
			Assertions.assertSame(firstResult, adapter(server)
					.result(firstGeneration).orElseThrow());
			Assertions.assertSame(firstResult,
					fixture.soklet().getDirectLifecycle().result().orElseThrow());
			SokletUnexpectedTerminationException repeatedStop =
					Assertions.assertThrows(
							SokletUnexpectedTerminationException.class,
							fixture.soklet()::close);
			Assertions.assertSame(firstResult,
					repeatedStop.getInternalShutdownResult());

			McpServer freshServer = serverBuilder(endpoint(PATH),
					Duration.ofSeconds(1)).build();
			freshFixture = fixture(freshServer);
			freshFixture.soklet().start();
			McpTransportLifecycleAdapter.Generation freshGeneration =
					generation(freshServer);
			Assertions.assertNotSame(firstGeneration, freshGeneration);
			freshFixture.soklet().close();
			assertLegacyParity(freshFixture, ShutdownComponentDisposition.GRACEFUL_TERMINATION);
			freshFixture.soklet().close();
			Assertions.assertTrue(adapter(freshServer).result(freshGeneration)
					.orElseThrow().isComplete());
		} finally {
			if (termination != null)
				termination.cancel(true);
			terminator.shutdownNow();
			if (freshFixture != null)
				freshFixture.close();
			closeAfterTerminalFailure(fixture);
		}
	}

	@Test
	void readyEventLoopObserverReentrantShutdownReturnsPromptlyWithoutFalseResidual()
			throws Exception {
		AtomicReference<Soklet> ownerReference = new AtomicReference<>();
		AtomicReference<CompletionStage<ShutdownResult>> nestedShutdown =
				new AtomicReference<>();
		CountDownLatch observerReturned = new CountDownLatch(1);
		LifecycleObserver observer = new LifecycleObserver() {
			@Override
			public void didReceiveLogEvent(@NonNull LogEvent event) {
				if (event.getLogEventType() != LogEventType.SERVER_TRANSPORT_FAILURE)
					return;
				nestedShutdown.set(ownerReference.get().getDirectLifecycle()
						.shutdown());
				observerReturned.countDown();
			}
		};
		McpServer server = serverBuilder(endpoint(PATH), Duration.ofSeconds(1)).build();
		Soklet owner = Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecycleObserver(observer)
				.build());
		ownerReference.set(owner);

		try {
			owner.start();
			McpTransportLifecycleAdapter.Generation generation = generation(server);
			terminateUnexpectedly(eventLoop(bridge(server)));
			Assertions.assertTrue(observerReturned.await(
					WAIT.toNanos(), TimeUnit.NANOSECONDS));
			adapter(server).awaitStop(generation);
			InternalShutdownResult result = adapter(server).result(generation)
					.orElseThrow();
			Assertions.assertTrue(result.isComplete());
			Assertions.assertEquals(
					InternalLifecycleComponentShutdownDisposition.UNEXPECTED_TERMINATION,
					mcpParticipant(result).disposition());
			CompletionStage<ShutdownResult> stage = requireNonNull(
					nestedShutdown.get());
			Assertions.assertSame(result, stage.toCompletableFuture().get(
					WAIT.toNanos(), TimeUnit.NANOSECONDS).internalResult());
			Assertions.assertSame(result,
					owner.getDirectLifecycle().result().orElseThrow());
			SokletUnexpectedTerminationException terminalFailure =
					Assertions.assertThrows(
							SokletUnexpectedTerminationException.class,
							owner::close);
			Assertions.assertSame(result,
					terminalFailure.getInternalShutdownResult());
		} finally {
			closeAfterTerminalFailure(owner);
		}
	}

	@Test
	void preReadyEventLoopObserverReentrantShutdownPreservesFailure()
			throws Exception {
		AtomicReference<McpServer> serverReference = new AtomicReference<>();
		AtomicReference<Soklet> ownerReference = new AtomicReference<>();
		AtomicReference<CompletionStage<ShutdownResult>> nestedShutdown =
				new AtomicReference<>();
		CountDownLatch observerReturned = new CountDownLatch(1);
		LifecycleObserver observer = new LifecycleObserver() {
			@Override
			public void didReceiveLogEvent(@NonNull LogEvent event) {
				if (event.getLogEventType() != LogEventType.SERVER_TRANSPORT_FAILURE)
					return;
				nestedShutdown.set(ownerReference.get().getDirectLifecycle()
						.shutdown());
				observerReturned.countDown();
			}
		};
		McpSubscriptionEventPublisher publisher =
				new McpSubscriptionEventPublisher() {
					@Override
					public McpSubscriptionEventRegistration subscribe(
							@NonNull McpSubscriptionEventListener listener) {
						try {
							closeSelector(eventLoop(bridge(serverReference.get())));
							Assertions.assertTrue(observerReturned.await(
									WAIT.toNanos(), TimeUnit.NANOSECONDS));
						} catch (Exception exception) {
							throw new IllegalStateException(exception);
						}
						return () -> {};
					}

					@Override
					public void publish(@NonNull McpSubscriptionEvent event) {
					}
				};
		McpEndpoint endpoint = McpEndpoint.withPath(PATH,
				implementation("b3-pre-ready-self-join"))
				.resourceListHandler((request, list, features) ->
						McpResourcePage.builder().build())
				.subscriptionConfig(McpSubscriptionConfig.withEventPublisher(publisher,
						Set.of(McpSubscriptionNotificationType
								.RESOURCES_LIST_CHANGED))
						.build())
				.build();
		McpServer server = serverBuilder(endpoint, Duration.ofSeconds(1)).build();
		serverReference.set(server);
		Soklet owner = Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecycleObserver(observer)
				.build());
		ownerReference.set(owner);

		SokletStartupException startupFailure = Assertions.assertThrows(
				SokletStartupException.class, owner::start);
		ClosedSelectorException exactFailure = Assertions.assertInstanceOf(
				ClosedSelectorException.class, startupFailure.getCause());
		Assertions.assertEquals(InternalStartupDisposition.FAILED,
				startupFailure.getInternalStartupDisposition());
		InternalShutdownResult result = startupFailure.getInternalShutdownResult();
		Assertions.assertSame(result, adapter(server).result().orElseThrow());
		Assertions.assertSame(result,
				owner.getDirectLifecycle().result().orElseThrow());
		CompletionStage<ShutdownResult> stage = requireNonNull(
				nestedShutdown.get());
		Assertions.assertSame(result, stage.toCompletableFuture().get(
				WAIT.toNanos(), TimeUnit.NANOSECONDS).internalResult());
		Assertions.assertTrue(result.isComplete());
		Assertions.assertEquals(InternalStartupDisposition.FAILED,
				result.startupDisposition());
		Assertions.assertSame(exactFailure,
				mcpParticipant(result).failures().get(0));
		Assertions.assertDoesNotThrow(owner::close);
		Assertions.assertDoesNotThrow(owner::close);
	}

	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	void simultaneousStartupFailuresShareTheElectedEventLoopPrimary()
			throws Exception {
		CountDownLatch subscriptionEntered = new CountDownLatch(1);
		CountDownLatch releaseSubscription = new CountDownLatch(1);
		CountDownLatch transportFailureObserved = new CountDownLatch(1);
		AtomicReference<LogEvent> transportFailureLog = new AtomicReference<>();
		AtomicReference<Throwable> startFailure = new AtomicReference<>();
		IllegalStateException synchronousFailure = new IllegalStateException(
				"simulated synchronous subscription startup failure");
		LifecycleObserver observer = new LifecycleObserver() {
			@Override
			public void didReceiveLogEvent(@NonNull LogEvent event) {
				if (event.getLogEventType() != LogEventType.SERVER_TRANSPORT_FAILURE)
					return;
				transportFailureLog.compareAndSet(null, event);
				transportFailureObserved.countDown();
			}
		};
		McpSubscriptionEventPublisher publisher =
				new McpSubscriptionEventPublisher() {
					@Override
					public McpSubscriptionEventRegistration subscribe(
							@NonNull McpSubscriptionEventListener listener) {
						subscriptionEntered.countDown();
						awaitUninterruptibly(releaseSubscription);
						throw synchronousFailure;
					}

					@Override
					public void publish(@NonNull McpSubscriptionEvent event) {
					}
				};
		McpEndpoint endpoint = McpEndpoint.withPath(PATH,
				implementation("b3-startup-primary-election"))
				.resourceListHandler((request, list, features) ->
						McpResourcePage.builder().build())
				.subscriptionConfig(McpSubscriptionConfig.withEventPublisher(publisher,
						Set.of(McpSubscriptionNotificationType
								.RESOURCES_LIST_CHANGED))
						.build())
				.build();
		McpServer server = serverBuilder(endpoint, Duration.ofSeconds(1)).build();
		Soklet owner = Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecycleObserver(observer)
				.build());
		Thread starter = new Thread(() -> {
			try {
				owner.start();
			} catch (Throwable failure) {
				startFailure.set(failure);
			}
		}, "mcp-b3-simultaneous-startup-failures");
		starter.setDaemon(true);

		try {
			starter.start();
			Assertions.assertTrue(subscriptionEntered.await(
					WAIT.toNanos(), TimeUnit.NANOSECONDS));
			closeSelector(eventLoop(bridge(server)));
			Assertions.assertTrue(transportFailureObserved.await(
					WAIT.toNanos(), TimeUnit.NANOSECONDS));
			releaseSubscription.countDown();
			starter.join(WAIT.toMillis());
			Assertions.assertFalse(starter.isAlive());

			assertRedactedTransportFailureLog(requireNonNull(
					transportFailureLog.get()));
			SokletStartupException startupFailure = Assertions.assertInstanceOf(
					SokletStartupException.class, startFailure.get());
			Throwable exactFailure = requireNonNull(startupFailure.getCause());
			Assertions.assertEquals(InternalStartupDisposition.FAILED,
					startupFailure.getInternalStartupDisposition());
			Assertions.assertTrue(List.of(exactFailure.getSuppressed()).stream()
					.anyMatch(failure -> failure == synchronousFailure),
					"The losing synchronous startup failure must remain suppressed.");
			InternalShutdownResult result = startupFailure.getInternalShutdownResult();
			Assertions.assertSame(result, adapter(server).result().orElseThrow());
			Assertions.assertSame(result,
					owner.getDirectLifecycle().result().orElseThrow());
			Assertions.assertEquals(InternalStartupDisposition.FAILED,
					result.startupDisposition());
			Assertions.assertSame(exactFailure,
					mcpParticipant(result).failures().get(0));
		} finally {
			releaseSubscription.countDown();
			starter.join(WAIT.toMillis());
			owner.close();
		}
	}

	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	void synchronousStartupFailureWaitsForExactCauseElectionBeforeTermination()
			throws Exception {
		CountDownLatch subscriptionEntered = new CountDownLatch(1);
		CountDownLatch releaseSubscription = new CountDownLatch(1);
		CountDownLatch startupFailureThrown = new CountDownLatch(1);
		AtomicReference<Thread> startupWorker = new AtomicReference<>();
		AtomicReference<Throwable> startFailure = new AtomicReference<>();
		IllegalStateException exactFailure = new IllegalStateException(
				"simulated elected synchronous startup failure");
		McpSubscriptionEventPublisher publisher =
				new McpSubscriptionEventPublisher() {
					@Override
					public McpSubscriptionEventRegistration subscribe(
							@NonNull McpSubscriptionEventListener listener) {
						startupWorker.set(Thread.currentThread());
						subscriptionEntered.countDown();
						awaitUninterruptibly(releaseSubscription);
						startupFailureThrown.countDown();
						throw exactFailure;
					}

					@Override
					public void publish(@NonNull McpSubscriptionEvent event) {
					}
				};
		McpEndpoint endpoint = McpEndpoint.withPath(PATH,
				implementation("b3-synchronous-startup-election"))
				.resourceListHandler((request, list, features) ->
						McpResourcePage.builder().build())
				.subscriptionConfig(McpSubscriptionConfig.withEventPublisher(publisher,
						Set.of(McpSubscriptionNotificationType
								.RESOURCES_LIST_CHANGED))
						.build())
				.build();
		McpServer server = serverBuilder(endpoint, Duration.ofSeconds(1)).build();
		Soklet owner = Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.build());
		Thread starter = new Thread(() -> {
			try {
				owner.start();
			} catch (Throwable failure) {
				startFailure.set(failure);
			}
		}, "mcp-b3-synchronous-startup-election");
		starter.setDaemon(true);

		try {
			starter.start();
			Assertions.assertTrue(subscriptionEntered.await(
					WAIT.toNanos(), TimeUnit.NANOSECONDS));
			Object runtime = runtime(bridge(server));
			synchronized (runtimeLifecycleLock(runtime)) {
				releaseSubscription.countDown();
				Assertions.assertTrue(startupFailureThrown.await(
						WAIT.toNanos(), TimeUnit.NANOSECONDS));
				awaitBlocked(requireNonNull(startupWorker.get()),
						"Startup did not reach exact-cause election.");
				Object readiness = currentReadiness(runtime);
				Assertions.assertNotNull(readiness);
				Assertions.assertEquals("STARTING",
						((Enum<?>) ((AtomicReference<?>) readiness).get()).name(),
						"TERMINATED must not publish before cause election.");
			}

			starter.join(WAIT.toMillis());
			Assertions.assertFalse(starter.isAlive());
			SokletStartupException startupFailure = Assertions.assertInstanceOf(
					SokletStartupException.class, startFailure.get());
			Assertions.assertEquals(InternalStartupDisposition.FAILED,
					startupFailure.getInternalStartupDisposition());
			Assertions.assertSame(exactFailure, startupFailure.getCause());
			InternalShutdownResult result = startupFailure.getInternalShutdownResult();
			Assertions.assertSame(result, adapter(server).result().orElseThrow());
			Assertions.assertSame(result,
					owner.getDirectLifecycle().result().orElseThrow());
			Assertions.assertEquals(InternalStartupDisposition.FAILED,
					result.startupDisposition());
			Assertions.assertSame(exactFailure,
					mcpParticipant(result).failures().get(0));
		} finally {
			releaseSubscription.countDown();
			starter.join(WAIT.toMillis());
			owner.close();
		}
	}

	@Test
	void subscriptionRegistrationCloseReentrantShutdownSharesCleanResult()
			throws Exception {
		AtomicReference<Soklet> ownerReference = new AtomicReference<>();
		AtomicReference<CompletionStage<ShutdownResult>> nestedShutdown =
				new AtomicReference<>();
		AtomicInteger registrationCloses = new AtomicInteger();
		McpSubscriptionEventPublisher publisher =
				new McpSubscriptionEventPublisher() {
					@Override
					public McpSubscriptionEventRegistration subscribe(
							@NonNull McpSubscriptionEventListener listener) {
						return () -> {
							registrationCloses.incrementAndGet();
							nestedShutdown.set(ownerReference.get().getDirectLifecycle()
									.shutdown());
						};
					}

					@Override
					public void publish(@NonNull McpSubscriptionEvent event) {
					}
				};
		McpEndpoint endpoint = McpEndpoint.withPath(PATH,
				implementation("b3-registration-close-self-join"))
				.resourceListHandler((request, list, features) ->
						McpResourcePage.builder().build())
				.subscriptionConfig(McpSubscriptionConfig.withEventPublisher(publisher,
						Set.of(McpSubscriptionNotificationType
								.RESOURCES_LIST_CHANGED))
						.build())
				.build();
		McpServer server = serverBuilder(endpoint, Duration.ofSeconds(1)).build();
		Soklet owner = Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.build());
		ownerReference.set(owner);

		try {
			owner.start();
			owner.close();
			Assertions.assertEquals(1, registrationCloses.get());
			InternalShutdownResult result = adapter(server).result().orElseThrow();
			CompletionStage<ShutdownResult> stage = requireNonNull(
					nestedShutdown.get());
			Assertions.assertSame(result, stage.toCompletableFuture().get(
					WAIT.toNanos(), TimeUnit.NANOSECONDS).internalResult());
			Assertions.assertSame(result,
					owner.getDirectLifecycle().result().orElseThrow());
			Assertions.assertTrue(result.isComplete());
			Assertions.assertEquals(
					InternalLifecycleComponentShutdownDisposition.GRACEFUL_TERMINATION,
					mcpParticipant(result).disposition());
			Assertions.assertDoesNotThrow(owner::close);
		} finally {
			owner.close();
		}
	}

	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	void eventLoopFailureBetweenRuntimeAndCommonReadinessPreservesExactCause()
			throws Exception {
		CountDownLatch diagnosticEntered = new CountDownLatch(1);
		CountDownLatch releaseDiagnostic = new CountDownLatch(1);
		AtomicReference<LogEvent> transportFailureLog = new AtomicReference<>();
		AtomicReference<Throwable> startFailure = new AtomicReference<>();
		LifecycleObserver observer = new LifecycleObserver() {
			@Override
			public void didReceiveLogEvent(@NonNull LogEvent event) {
				if (event.getLogEventType() == LogEventType.MCP_SERVER_CONFIGURATION
						&& event.getMessage().startsWith("No CorsAuthorizer")) {
					diagnosticEntered.countDown();
					awaitUninterruptibly(releaseDiagnostic);
				} else if (event.getLogEventType()
						== LogEventType.SERVER_TRANSPORT_FAILURE) {
					transportFailureLog.compareAndSet(null, event);
				}
			}
		};
		McpServer server = McpServer.withPort(0, McpEndpointRegistry.fromEndpoints(
						List.of(endpoint(PATH))), McpAdmissionController.acceptAllInstance())
				.host(HOST)
				.toolRateLimiter(context -> McpRateLimitDecision.allowed())
				.allowedHosts(Set.of(HOST))
				.requestHandlerConcurrency(1)
				.requestHandlerQueueCapacity(1)
				.build();
		Soklet owner = Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecycleObserver(observer)
				.build());
		Thread starter = new Thread(() -> {
			try {
				owner.start();
			} catch (Throwable failure) {
				startFailure.set(failure);
			}
		}, "mcp-b3-runtime-common-ready-gap");
		starter.setDaemon(true);

		try {
			starter.start();
			Assertions.assertTrue(diagnosticEntered.await(
					WAIT.toNanos(), TimeUnit.NANOSECONDS));
			synchronized (serverLifecycleLock(server)) {
				releaseDiagnostic.countDown();
				awaitCondition(() -> bridge(server).getRuntimeState().started(),
						"The private runtime did not publish readiness.");
				closeSelector(eventLoop(bridge(server)));
				awaitCondition(() -> transportFailureLog.get() != null,
						"The redacted EventLoop failure log was not published.");
			}
			starter.join(WAIT.toMillis());
			Assertions.assertFalse(starter.isAlive());
			assertRedactedTransportFailureLog(requireNonNull(
					transportFailureLog.get()));
			SokletStartupException startupFailure = Assertions.assertInstanceOf(
					SokletStartupException.class, startFailure.get());
			Throwable exactFailure = requireNonNull(startupFailure.getCause());
			Assertions.assertEquals(InternalStartupDisposition.FAILED,
					startupFailure.getInternalStartupDisposition());
			InternalShutdownResult result = startupFailure.getInternalShutdownResult();
			Assertions.assertSame(result, adapter(server).result().orElseThrow());
			Assertions.assertSame(result,
					owner.getDirectLifecycle().result().orElseThrow());
			Assertions.assertEquals(InternalStartupDisposition.FAILED,
					result.startupDisposition());
			Assertions.assertSame(exactFailure,
					mcpParticipant(result).failures().get(0));
		} finally {
			releaseDiagnostic.countDown();
			starter.join(WAIT.toMillis());
			owner.close();
		}
	}

	@Test
	void unexpectedEventLoopSignalsFailureBeforeAdmittedHandlerTeardown()
			throws Exception {
		CountDownLatch handlerEntered = new CountDownLatch(1);
		CountDownLatch releaseHandler = new CountDownLatch(1);
		CountDownLatch handlerExited = new CountDownLatch(1);
		AtomicInteger interruptions = new AtomicInteger();
		McpToolRegistration<McpJsonObject> tool = tool("b3.unexpected-admitted",
				(request, arguments, features) -> {
				handlerEntered.countDown();
				try {
					Assertions.assertTrue(releaseHandler.await(10,
							TimeUnit.SECONDS),
							"Timed out waiting to release the admitted handler");
				} catch (InterruptedException exception) {
					interruptions.incrementAndGet();
					throw exception;
				} finally {
					handlerExited.countDown();
				}
				return McpCompleteResult.fromToolText("unexpected-admitted");
			});
		McpServer server = serverBuilder(endpoint(PATH, tool), Duration.ofSeconds(3))
				.build();
		Fixture fixture = fixture(server);
		CompletableFuture<HttpResponse<String>> request = null;

		try {
			fixture.soklet().start();
			McpTransportLifecycleAdapter.Generation generation = generation(server);
			request = callTool(boundPort(server), "unexpected-admitted",
					tool.getName(), false);
			Assertions.assertTrue(handlerEntered.await(WAIT.toNanos(),
					TimeUnit.NANOSECONDS));
			Assertions.assertEquals(1, admittedWork(generation));

			terminateUnexpectedly(eventLoop(fixture.bridge()));

			Assertions.assertTrue(generation.shutdownRequested());
			Assertions.assertTrue(generation.tryAdmit().isEmpty());
			Assertions.assertFalse(terminationEvents(server, generation).isEmpty());
			Assertions.assertEquals(InternalTerminationEvent.Type.FAILURE,
					terminationEvents(server, generation).get(0).type());
			Assertions.assertEquals(0, interruptions.get(),
					"Failure signaling and admission fencing must precede cancellation.");
			Assertions.assertEquals(1L, handlerExited.getCount(),
					"Unexpected transport teardown must not autonomously cancel admitted work.");
			Assertions.assertEquals(1, admittedWork(generation));

			releaseHandler.countDown();
			SokletUnexpectedTerminationException stopFailure =
					Assertions.assertThrows(
							SokletUnexpectedTerminationException.class,
							fixture.soklet()::close);
			InternalShutdownResult result = stopFailure.getInternalShutdownResult();
			Assertions.assertSame(result,
					adapter(server).result(generation).orElseThrow());
			Assertions.assertTrue(handlerExited.await(WAIT.toNanos(),
					TimeUnit.NANOSECONDS));
			Assertions.assertEquals(0, admittedWork(generation));
			InternalLifecycleComponentShutdownResult participant = assertParticipant(server,
					InternalLifecycleComponentShutdownDisposition.UNEXPECTED_TERMINATION);
			Assertions.assertEquals(List.of(InternalTerminationEvent.Type.FAILURE,
					InternalTerminationEvent.Type.PROOF),
					terminationEvents(server, generation).stream()
							.map(InternalTerminationEvent::type).toList());
			Assertions.assertSame(participant.failures().get(0),
					stopFailure.getCause());
			assertLegacyParity(fixture,
					ShutdownComponentDisposition.UNEXPECTED_TERMINATION);
		} finally {
			releaseHandler.countDown();
			if (request != null)
				request.cancel(true);
			fixture.close();
		}
	}

	@Test
	void ownerNormalizesRetainedUnexpectedGenerationOnceBeforeRejectingRestart()
			throws Exception {
		CountDownLatch handlerEntered = new CountDownLatch(1);
		CountDownLatch releaseHandler = new CountDownLatch(1);
		CountDownLatch handlerExited = new CountDownLatch(1);
		McpToolRegistration<McpJsonObject> tool = tool("b3.retained-restart",
				(request, arguments, features) -> {
					handlerEntered.countDown();
					try {
						while (releaseHandler.getCount() != 0L) {
							try {
								Assertions.assertTrue(releaseHandler.await(10,
										TimeUnit.SECONDS),
										"Timed out waiting to release the retained handler");
							} catch (InterruptedException ignored) {
							}
						}
						return McpCompleteResult.fromToolText("released");
					} finally {
						handlerExited.countDown();
					}
				});
		McpServer server = serverBuilder(endpoint(PATH, tool),
				Duration.ofMillis(50)).build();
		Fixture fixture = fixture(server, shortShutdownPolicy());
		DefaultMcpServer defaultServer = (DefaultMcpServer) server;
		CompletableFuture<HttpResponse<String>> request = null;

		try {
			fixture.soklet().start();
			McpTransportLifecycleAdapter.Generation generation = generation(server);
			request = callTool(boundPort(server), "retained-restart",
					tool.getName(), false);
			Assertions.assertTrue(handlerEntered.await(
					WAIT.toNanos(), TimeUnit.NANOSECONDS));
			terminateUnexpectedly(eventLoop(fixture.bridge()));
			SokletUnexpectedTerminationException stopFailure =
					Assertions.assertThrows(
							SokletUnexpectedTerminationException.class,
							fixture.soklet()::close);
			InternalShutdownResult result = stopFailure.getInternalShutdownResult();
			Assertions.assertSame(result,
					adapter(server).result(generation).orElseThrow());
			Assertions.assertFalse(result.isComplete());
			assertIncompleteLegacyParity(fixture, result);
			Assertions.assertFalse(adapter(server).hasActiveGeneration());
			Assertions.assertEquals(McpServerStatus.RESIDUAL_ACTIVITY,
					server.getDiagnostics().getStatus());

			releaseHandler.countDown();
			Assertions.assertTrue(handlerExited.await(
					WAIT.toNanos(), TimeUnit.NANOSECONDS));
			awaitCondition(() -> server.getDiagnostics()
					.getActiveHandlerExecutions() == 0,
					"Late retained-generation cleanup did not settle diagnostics.");
			Assertions.assertEquals(McpServerStatus.RESIDUAL_ACTIVITY,
					server.getDiagnostics().getStatus(),
					"Late cleanup must not rewrite the frozen residual result.");
			Assertions.assertSame(result,
					adapter(server).result(generation).orElseThrow());
			Assertions.assertSame(result,
					fixture.soklet().getDirectLifecycle().result().orElseThrow());

			Assertions.assertThrows(IllegalStateException.class,
					defaultServer::startForSoklet);
			Assertions.assertFalse(adapter(server).hasActiveGeneration(),
					"The owner already normalized the retained generation.");
			Assertions.assertThrows(IllegalStateException.class,
					defaultServer::startForSoklet);
			Assertions.assertSame(result,
					adapter(server).result(generation).orElseThrow(),
					"A rejected restart must not redeliver the normalized generation.");
			Assertions.assertEquals(List.of(ShutdownComponentDisposition.RESIDUAL_ACTIVITY),
					fixture.metrics().shutdownOutcomes,
					"The retained generation metric must be delivered exactly once.");
			Assertions.assertSame(generation, generation(server));
		} finally {
			releaseHandler.countDown();
			Assertions.assertTrue(handlerExited.await(
					WAIT.toNanos(), TimeUnit.NANOSECONDS));
			if (request != null)
				request.cancel(true);
			fixture.close();
		}
	}

	@Test
	void deterministicNoProofMapsToMcpUnknownAndRetainsEvidence() {
		AtomicLong now = new AtomicLong(100L);
		RecordingAdapterOperations operations = new RecordingAdapterOperations(
				attempt -> false, Set.of());
		operations.onAwait = now::set;
		McpTransportLifecycleAdapter adapter = deterministicAdapter(operations,
				Duration.ofNanos(5L), Duration.ofNanos(7L), now::get,
				inlineLifecycleWorkers());
		McpTransportLifecycleAdapter.Generation generation = adapter.beginStart();
		adapter.markReady(generation);

		adapter.awaitStop(adapter.requestStop());

		InternalShutdownResult result = adapter.result(generation).orElseThrow();
		InternalLifecycleComponentShutdownResult participant = mcpParticipant(result);
		Assertions.assertEquals(InternalStartupDisposition.READY,
				result.startupDisposition());
		Assertions.assertEquals(
				InternalLifecycleComponentShutdownDisposition.TERMINATION_UNKNOWN,
				participant.disposition());
		Assertions.assertFalse(result.isComplete());
		Assertions.assertEquals(List.of("quiesce", "await-105", "force",
				"await-112", "residual"), operations.events);
		Assertions.assertEquals(List.of(105L, 112L), operations.deadlines);
		Assertions.assertEquals(1, operations.quiesceCount.get());
		Assertions.assertEquals(1, operations.forceCount.get());
		Assertions.assertEquals(0, operations.releaseCount.get());
		Assertions.assertTrue(participant.residualActivity().isEmpty());
		Assertions.assertTrue(adapter.retentionSummary().orElseThrow()
				.counts().isEmpty());
		Assertions.assertThrows(IllegalStateException.class, adapter::beginStart);
	}

	@Test
	void exactMcpGenerationOperationsRejectForeignTokensWithoutMutation() {
		RecordingAdapterOperations firstOperations = new RecordingAdapterOperations(
				attempt -> true, Set.of());
		RecordingAdapterOperations secondOperations = new RecordingAdapterOperations(
				attempt -> true, Set.of());
		McpTransportLifecycleAdapter first = deterministicAdapter(firstOperations,
				Duration.ZERO, Duration.ZERO, () -> 0L, inlineLifecycleWorkers());
		McpTransportLifecycleAdapter second = deterministicAdapter(secondOperations,
				Duration.ZERO, Duration.ZERO, () -> 0L, inlineLifecycleWorkers());
		McpTransportLifecycleAdapter.Generation firstGeneration = first.beginStart();
		McpTransportLifecycleAdapter.Generation secondGeneration = second.beginStart();
		first.markReady(firstGeneration);
		second.markReady(secondGeneration);

		IllegalStateException resultFailure = Assertions.assertThrows(
				IllegalStateException.class, () -> first.result(secondGeneration));
		IllegalStateException waitFailure = Assertions.assertThrows(
				IllegalStateException.class, () -> first.awaitStop(secondGeneration));
		Assertions.assertEquals("Foreign MCP lifecycle generation",
				resultFailure.getMessage());
		Assertions.assertEquals("Foreign MCP lifecycle generation",
				waitFailure.getMessage());
		Assertions.assertTrue(first.result(firstGeneration).isEmpty());
		Assertions.assertTrue(second.result(secondGeneration).isEmpty());
		Assertions.assertEquals(0, firstOperations.quiesceCount.get());
		Assertions.assertEquals(0, secondOperations.quiesceCount.get());

		first.awaitStop(first.requestStop());
		second.awaitStop(second.requestStop());
		Assertions.assertTrue(first.result(firstGeneration).orElseThrow().isComplete());
		Assertions.assertTrue(second.result(secondGeneration).orElseThrow().isComplete());
	}

	@Test
	void deterministicNoProofRetainsTheExactBoundEphemeralAddress()
			throws Exception {
		AtomicLong now = new AtomicLong(500L);
		RecordingAdapterOperations operations = new RecordingAdapterOperations(
				attempt -> false, Set.of());
		operations.onAwait = now::set;
		McpTransportLifecycleAdapter adapter = deterministicAdapter(operations,
				Duration.ofNanos(5L), Duration.ofNanos(7L), now::get,
				inlineLifecycleWorkers());
		McpServer projectionSource = serverBuilder(endpoint(PATH),
				Duration.ofSeconds(1)).build();
		McpServerRuntimeBridge bridge = lifecycleBridge(
				executablePlans(projectionSource), adapter);
		adapter.bindRuntime(bridge);
		McpTransportLifecycleAdapter.Generation generation = adapter.beginStart();
		InetSocketAddress address = null;

		try {
			address = bridge.start();
			adapter.markReady(generation);
			adapter.awaitStop(adapter.requestStop());

			InternalShutdownResult result = adapter.result(generation).orElseThrow();
			Assertions.assertEquals(
					InternalLifecycleComponentShutdownDisposition.TERMINATION_UNKNOWN,
					mcpParticipant(result).disposition());
			Assertions.assertFalse(result.isComplete());
			Assertions.assertEquals(address, bridge.getBoundAddress().orElseThrow());
			Assertions.assertEquals(0, operations.releaseCount.get());
			Assertions.assertTrue(bridge.getLifecycleEvidence().eventLoop());
			Assertions.assertThrows(IllegalStateException.class, adapter::beginStart);
		} finally {
			bridge.stop();
			if (address != null)
				assertListenerReturned(address);
		}
	}

	@Test
	void blockedMcpQuiesceIsCancelledBeforeForceAndProof() throws Exception {
		CountDownLatch quiesceEntered = new CountDownLatch(1);
		CountDownLatch quiesceInterrupted = new CountDownLatch(1);
		CountDownLatch quiesceCallExited = new CountDownLatch(1);
		CountDownLatch neverReleased = new CountDownLatch(1);
		AtomicReference<Thread> quiesceWorker = new AtomicReference<>();
		RecordingAdapterOperations operations = new RecordingAdapterOperations(
				attempt -> attempt == 2, Set.of());
		operations.onQuiesce = () -> {
			operations.events.add("quiesce-enter");
			quiesceEntered.countDown();
			try {
				neverReleased.await();
			} catch (InterruptedException expected) {
				operations.events.add("quiesce-interrupted");
				quiesceInterrupted.countDown();
			}
		};
		LifecycleWorkers workers = new LifecycleWorkers((name, runnable) -> {
			if (name.equals("lifecycle-quiesce-mcp")) {
				Thread worker = new Thread(() -> {
					try {
						runnable.run();
					} finally {
						operations.events.add("quiesce-call-exited");
						quiesceCallExited.countDown();
					}
				}, "mcp-b3-blocked-quiesce");
				worker.setDaemon(true);
				quiesceWorker.set(worker);
				worker.start();
				return;
			}
			if (name.equals("lifecycle-force-mcp")) {
				awaitUninterruptibly(quiesceCallExited);
				operations.events.add("force-launch");
				runnable.run();
				return;
			}
			runnable.run();
		});
		McpTransportLifecycleAdapter adapter = deterministicAdapter(operations,
				Duration.ZERO, Duration.ZERO, () -> 0L, workers);
		McpTransportLifecycleAdapter.Generation generation = adapter.beginStart();
		adapter.markReady(generation);

		adapter.awaitStop(adapter.requestStop());
		Thread worker = quiesceWorker.get();
		Assertions.assertNotNull(worker);
		worker.join();

		Assertions.assertEquals(0L, quiesceEntered.getCount());
		Assertions.assertEquals(0L, quiesceInterrupted.getCount());
		Assertions.assertEquals(List.of("quiesce", "quiesce-enter",
				"quiesce-interrupted", "await-0", "quiesce-call-exited",
				"force-launch", "force", "await-0", "residual", "release"),
				operations.events);
		Assertions.assertEquals(
				InternalLifecycleComponentShutdownDisposition.FORCED_TERMINATION,
				mcpParticipant(adapter.result(generation).orElseThrow()).disposition());
		Assertions.assertEquals(1, operations.forceCount.get());
		Assertions.assertEquals(1, operations.releaseCount.get());
		Assertions.assertTrue(adapter.retentionSummary().isEmpty());
		Assertions.assertEquals(0,
				workers.active(LifecycleWorkers.Role.LIFECYCLE_CALL));
	}

	@Test
	void shutdownIntentFencesAdmissionBeforeDeferredMcpQuiesce() {
		AtomicReference<Runnable> deferredCoordinator = new AtomicReference<>();
		RecordingAdapterOperations operations = new RecordingAdapterOperations(
				attempt -> true, Set.of());
		LifecycleWorkers workers = new LifecycleWorkers((name, runnable) -> {
			if (name.equals("built-in-mcp-lifecycle-coordinator")) {
				Assertions.assertTrue(deferredCoordinator.compareAndSet(null, runnable));
				return;
			}
			runnable.run();
		});
		McpTransportLifecycleAdapter adapter = deterministicAdapter(operations,
				Duration.ZERO, Duration.ZERO, () -> 0L, workers);
		McpTransportLifecycleAdapter.Generation generation = adapter.beginStart();
		adapter.markReady(generation);
		Runnable admitted = generation.tryAdmit().orElseThrow();
		admitted.run();

		McpTransportLifecycleAdapter.Generation requested = adapter.requestStop();

		Assertions.assertSame(generation, requested);
		Assertions.assertTrue(generation.shutdownRequested());
		Assertions.assertTrue(generation.tryAdmit().isEmpty());
		Assertions.assertEquals(0, operations.quiesceCount.get());
		Assertions.assertTrue(adapter.result(generation).isEmpty());
		deferredCoordinator.get().run();
		adapter.awaitStop(requested);
		Assertions.assertEquals(
				InternalLifecycleComponentShutdownDisposition.GRACEFUL_TERMINATION,
				mcpParticipant(adapter.result(generation).orElseThrow()).disposition());
		Assertions.assertEquals(1, operations.releaseCount.get());
	}

	@Test
	void mcpFailureAndProofOrderingPreservesTheExactGenerationAndBarrier()
			throws Exception {
		AtomicReference<McpTransportLifecycleAdapter> failureAdapterRef =
				new AtomicReference<>();
		AtomicReference<McpTransportLifecycleAdapter.Generation> failureGenerationRef =
				new AtomicReference<>();
		Throwable earlyFailure = new AssertionError("MCP failed before quiesce");
		RecordingAdapterOperations failureOperations = new RecordingAdapterOperations(
				attempt -> true, Set.of());
		failureOperations.onQuiesce = () -> {
			McpTransportLifecycleAdapter failureAdapter = failureAdapterRef.get();
			McpTransportLifecycleAdapter.Generation failureGeneration =
					failureGenerationRef.get();
			Assertions.assertEquals(List.of(InternalTerminationEvent.Type.FAILURE),
					terminationEvents(failureAdapter, failureGeneration).stream()
							.map(InternalTerminationEvent::type).toList());
			Assertions.assertTrue(failureGeneration.tryAdmit().isEmpty());
		};
		McpTransportLifecycleAdapter failureAdapter = deterministicAdapter(
				failureOperations, Duration.ZERO, Duration.ZERO, () -> 0L,
				inlineLifecycleWorkers());
		failureAdapterRef.set(failureAdapter);
		McpTransportLifecycleAdapter.Generation failureGeneration =
				failureAdapter.beginStart();
		failureGenerationRef.set(failureGeneration);
		failureAdapter.markReady(failureGeneration);

		failureGeneration.signalTerminationFailure(earlyFailure);

		InternalLifecycleComponentShutdownResult failedParticipant = mcpParticipant(
				failureAdapter.result(failureGeneration).orElseThrow());
		Assertions.assertEquals(
				InternalLifecycleComponentShutdownDisposition.UNEXPECTED_TERMINATION,
				failedParticipant.disposition());
		Assertions.assertEquals(List.of(earlyFailure), failedParticipant.failures());
		Assertions.assertEquals(List.of(InternalTerminationEvent.Type.FAILURE,
				InternalTerminationEvent.Type.PROOF),
				terminationEvents(failureAdapter, failureGeneration).stream()
						.map(InternalTerminationEvent::type).toList());
		Assertions.assertEquals(0, failureOperations.forceCount.get());

		CountDownLatch proofAwaitEntered = new CountDownLatch(1);
		AtomicReference<Thread> coordinatorThread = new AtomicReference<>();
		RecordingAdapterOperations lateFailureOperations =
				new RecordingAdapterOperations(attempt -> true, Set.of());
		lateFailureOperations.onAwait = deadline -> proofAwaitEntered.countDown();
		LifecycleWorkers asynchronousCoordinator = new LifecycleWorkers((name, runnable) -> {
			if (name.equals("built-in-mcp-lifecycle-coordinator")) {
				Thread worker = new Thread(runnable, "mcp-b3-proof-before-failure");
				worker.setDaemon(true);
				coordinatorThread.set(worker);
				worker.start();
				return;
			}
			runnable.run();
		});
		McpTransportLifecycleAdapter lateFailureAdapter = deterministicAdapter(
				lateFailureOperations, Duration.ofSeconds(2), Duration.ZERO,
				NanoClock.system(), asynchronousCoordinator);
		McpTransportLifecycleAdapter.Generation lateFailureGeneration =
				lateFailureAdapter.beginStart();
		lateFailureAdapter.markReady(lateFailureGeneration);
		Runnable admission = lateFailureGeneration.tryAdmit().orElseThrow();
		McpTransportLifecycleAdapter.Generation requested =
				lateFailureAdapter.requestStop();
		Assertions.assertTrue(proofAwaitEntered.await(
				WAIT.toNanos(), TimeUnit.NANOSECONDS));
		awaitCondition(() -> terminationEvents(lateFailureAdapter,
				lateFailureGeneration).stream().map(InternalTerminationEvent::type)
				.toList().equals(List.of(InternalTerminationEvent.Type.PROOF)),
				"Affirmative transport proof was not recorded before the late failure.");
		Assertions.assertTrue(lateFailureAdapter.result(lateFailureGeneration).isEmpty(),
				"Affirmative transport proof cannot bypass admitted application work.");
		Assertions.assertEquals(0, lateFailureOperations.releaseCount.get());
		Throwable lateFailure = new AssertionError("MCP failed after proof");
		lateFailureGeneration.signalTerminationFailure(lateFailure);
		admission.run();
		lateFailureAdapter.awaitStop(requested);
		Thread coordinator = coordinatorThread.get();
		Assertions.assertNotNull(coordinator);
		coordinator.join();

		InternalLifecycleComponentShutdownResult lateParticipant = mcpParticipant(
				lateFailureAdapter.result(lateFailureGeneration).orElseThrow());
		Assertions.assertEquals(
				InternalLifecycleComponentShutdownDisposition.GRACEFUL_TERMINATION,
				lateParticipant.disposition());
		Assertions.assertEquals(List.of(lateFailure), lateParticipant.failures());
		Assertions.assertEquals(List.of(InternalTerminationEvent.Type.PROOF,
				InternalTerminationEvent.Type.FAILURE),
				terminationEvents(lateFailureAdapter, lateFailureGeneration).stream()
						.map(InternalTerminationEvent::type).toList());
		Assertions.assertEquals(0, lateFailureOperations.forceCount.get());
		Assertions.assertEquals(1, lateFailureOperations.releaseCount.get());
	}

	@Test
	void stopBeforeRuntimeInstallAndBeforeMarkReadyWinsDeterministically()
			throws Exception {
		McpServer neverInstalled = serverBuilder(endpoint(PATH), Duration.ofSeconds(1))
				.build();
		McpTransportLifecycleAdapter neverInstalledAdapter = adapter(neverInstalled);
		McpServerRuntimeBridge neverInstalledBridge = bridge(neverInstalled);
		McpTransportLifecycleAdapter.Generation neverInstalledGeneration =
				neverInstalledAdapter.beginStart();
		McpTransportLifecycleAdapter.Generation requested =
				neverInstalledAdapter.requestStop();
		neverInstalledAdapter.awaitStop(requested);
		Assertions.assertThrows(java.io.IOException.class,
				neverInstalledBridge::start);
		Assertions.assertThrows(IllegalStateException.class,
				() -> neverInstalledAdapter.markReady(neverInstalledGeneration));
		Assertions.assertTrue(neverInstalledBridge.getBoundAddress().isEmpty());
		Assertions.assertEquals(InternalStartupDisposition.FAILED,
				neverInstalledAdapter.result().orElseThrow().startupDisposition());

		McpServer boundNotReady = serverBuilder(endpoint(PATH), Duration.ofSeconds(1))
				.build();
		McpTransportLifecycleAdapter boundAdapter = adapter(boundNotReady);
		McpServerRuntimeBridge boundBridge = bridge(boundNotReady);
		McpTransportLifecycleAdapter.Generation boundGeneration =
				boundAdapter.beginStart();
		InetSocketAddress address = null;
		try {
			address = boundBridge.start();
			Assertions.assertEquals(503,
					discovery(address.getPort(), "before-ready").get(
							WAIT.toNanos(), TimeUnit.NANOSECONDS).statusCode());
			McpTransportLifecycleAdapter.Generation boundRequest =
					boundAdapter.requestStop();
			boundAdapter.awaitStop(boundRequest);
			Assertions.assertThrows(IllegalStateException.class,
					() -> boundAdapter.markReady(boundGeneration));
			Assertions.assertEquals(address,
					boundBridge.getBoundAddress().orElseThrow());
			Assertions.assertEquals(InternalStartupDisposition.FAILED,
					boundAdapter.result().orElseThrow().startupDisposition());
			assertListenerReturned(address);
		} finally {
			boundBridge.stop();
			if (address != null)
				assertListenerReturned(address);
		}
	}

	@Test
	void startupErrorPreservesIdentityAndFreshOwnerStartsAfterNeverBoundFailure()
			throws Exception {
		AssertionError expected = new AssertionError("b3 early executor failure");
		AtomicInteger supplies = new AtomicInteger();
		List<ExecutorService> executors = new CopyOnWriteArrayList<>();
		McpEndpoint endpoint = endpoint(PATH);
		Supplier<ExecutorService> executorSupplier = () -> {
					if (supplies.getAndIncrement() == 0)
						throw expected;
					ExecutorService executor = Executors.newSingleThreadExecutor();
					executors.add(executor);
					return executor;
				};
		McpServer failedServer = serverBuilder(endpoint, Duration.ofSeconds(1))
				.requestHandlerExecutorServiceSupplier(executorSupplier)
				.build();
		Fixture failedFixture = fixture(failedServer);
		Fixture freshFixture = null;

		try {
			SokletStartupException observed = Assertions.assertThrows(
					SokletStartupException.class, failedFixture.soklet()::start);
			Assertions.assertSame(expected, observed.getCause());
			Assertions.assertTrue(failedServer.getDiagnostics()
					.getBoundAddress().isEmpty());
			InternalShutdownResult failed = observed.getInternalShutdownResult();
			Assertions.assertSame(failed,
					adapter(failedServer).result().orElseThrow());
			Assertions.assertEquals(InternalStartupDisposition.FAILED,
					failed.startupDisposition());
			Assertions.assertSame(expected, failed.participantResult(
					InternalLifecycleComponentType.MCP).orElseThrow().failures().get(0));
			failedFixture.soklet().close();
			failedFixture.soklet().close();

			McpServer freshServer = serverBuilder(endpoint, Duration.ofSeconds(1))
					.requestHandlerExecutorServiceSupplier(executorSupplier)
					.build();
			freshFixture = fixture(freshServer);
			freshFixture.soklet().start();
			InetSocketAddress restarted = boundAddress(freshServer);
			freshFixture.soklet().close();
			freshFixture.soklet().close();
			Assertions.assertEquals(restarted, boundAddress(freshServer));
			Assertions.assertEquals(1, executors.size());
			Assertions.assertTrue(executors.get(0).isTerminated());
			assertListenerReturned(restarted);
		} finally {
			if (freshFixture != null)
				freshFixture.close();
			failedFixture.close();
			for (ExecutorService executor : executors)
				executor.shutdownNow();
		}
	}

	@Test
	void fixedPortBindIOExceptionPreservesExactCauseForFreshOwnerAfterRelease()
			throws Exception {
		int port;
		try (ServerSocket occupied = new ServerSocket()) {
			occupied.setReuseAddress(false);
			occupied.bind(new InetSocketAddress(HOST, 0));
			port = occupied.getLocalPort();
			McpServer failedServer = serverBuilder(port, endpoint(PATH),
					Duration.ofSeconds(1))
					.build();
			Fixture failedFixture = fixture(failedServer);

			SokletStartupException observed = Assertions.assertThrows(
					SokletStartupException.class, failedFixture.soklet()::start);
			Throwable exactCause = Assertions.assertInstanceOf(
					java.net.BindException.class, observed.getCause());
			InternalShutdownResult failed = observed.getInternalShutdownResult();
			Assertions.assertSame(failed,
					adapter(failedServer).result().orElseThrow());
			Assertions.assertEquals(InternalStartupDisposition.FAILED,
					failed.startupDisposition());
			Assertions.assertSame(exactCause, mcpParticipant(failed).failures().get(0));
			Assertions.assertTrue(failedServer.getDiagnostics()
					.getBoundAddress().isEmpty());
			failedFixture.soklet().close();
			failedFixture.soklet().close();
		}

		McpServer freshServer = serverBuilder(port, endpoint(PATH),
				Duration.ofSeconds(1)).build();
		Fixture freshFixture = fixture(freshServer);
		try {
			freshFixture.soklet().start();
			InetSocketAddress restarted = boundAddress(freshServer);
			Assertions.assertEquals(port, restarted.getPort());
			freshFixture.soklet().close();
			freshFixture.soklet().close();
			Assertions.assertEquals(restarted, boundAddress(freshServer));
			assertListenerReturned(restarted);
		} finally {
			freshFixture.close();
		}
	}

	@Test
	void postBindPreReadySubscriptionFailurePreservesExactIdentityAndAddress()
			throws Exception {
		IllegalStateException expected = new IllegalStateException(
				"b3 post-bind subscription failure");
		AtomicBoolean failFirstSubscription = new AtomicBoolean(true);
		AtomicInteger registrationCloses = new AtomicInteger();
		McpSubscriptionEventPublisher publisher = new McpSubscriptionEventPublisher() {
			@Override
			public McpSubscriptionEventRegistration subscribe(
					@NonNull McpSubscriptionEventListener listener) {
				if (failFirstSubscription.compareAndSet(true, false))
					throw expected;
				return registrationCloses::incrementAndGet;
			}

			@Override
			public void publish(@NonNull McpSubscriptionEvent event) {
				// No application events are needed for startup identity evidence.
			}
		};
		McpEndpoint endpoint = McpEndpoint.withPath(PATH,
				implementation("b3-post-bind-failure"))
				.resourceListHandler((request, list, features) ->
						McpResourcePage.builder().build())
				.subscriptionConfig(McpSubscriptionConfig.withEventPublisher(publisher,
						Set.of(McpSubscriptionNotificationType
								.RESOURCES_LIST_CHANGED))
						.build())
				.build();
		McpServer failedServer = serverBuilder(endpoint, Duration.ofSeconds(1)).build();
		Fixture failedFixture = fixture(failedServer);
		Fixture freshFixture = null;

		try {
			SokletStartupException observed = Assertions.assertThrows(
					SokletStartupException.class, failedFixture.soklet()::start);
			Assertions.assertSame(expected, observed.getCause());
			InetSocketAddress failedAddress = boundAddress(failedServer);
			InternalShutdownResult failed = observed.getInternalShutdownResult();
			Assertions.assertSame(failed,
					adapter(failedServer).result().orElseThrow());
			Assertions.assertEquals(InternalStartupDisposition.FAILED,
					failed.startupDisposition());
			Assertions.assertSame(expected, mcpParticipant(failed).failures().get(0));

			failedFixture.soklet().close();
			failedFixture.soklet().close();
			Assertions.assertEquals(failedAddress, boundAddress(failedServer));
			assertListenerReturned(failedAddress);

			McpServer freshServer = serverBuilder(endpoint,
					Duration.ofSeconds(1)).build();
			freshFixture = fixture(freshServer);
			freshFixture.soklet().start();
			InetSocketAddress restarted = boundAddress(freshServer);
			freshFixture.soklet().close();
			freshFixture.soklet().close();
			Assertions.assertEquals(1, registrationCloses.get());
			Assertions.assertEquals(restarted, boundAddress(freshServer));
			assertListenerReturned(restarted);
		} finally {
			if (freshFixture != null)
				freshFixture.close();
			failedFixture.close();
		}
	}

	@Test
	void executorFactoryReentrantStopWinsBeforeReadyWithoutDeadlock()
			throws Exception {
		AtomicReference<Soklet> ownerReference = new AtomicReference<>();
		AtomicBoolean reenter = new AtomicBoolean(true);
		AtomicReference<Throwable> reentrantStopFailure = new AtomicReference<>();
		List<ExecutorService> executors = new CopyOnWriteArrayList<>();
		McpServer server = serverBuilder(endpoint(PATH), Duration.ofSeconds(1))
				.requestHandlerExecutorServiceSupplier(() -> {
					if (reenter.compareAndSet(true, false)) {
						try {
							ownerReference.get().close();
						} catch (Throwable throwable) {
							reentrantStopFailure.set(throwable);
						}
					}
					ExecutorService executor = Executors.newSingleThreadExecutor();
					executors.add(executor);
					return executor;
				})
					.build();
		Soklet owner = Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.build());
		ownerReference.set(owner);
		ExecutorService starter = daemonSingleThreadExecutor(
				"mcp-b3-reentrant-stop-start");

		try {
			Future<Throwable> firstStart = starter.submit(() -> {
				try {
					owner.start();
					return null;
				} catch (Throwable throwable) {
					return throwable;
				}
			});
			Throwable failure = firstStart.get(WAIT.toNanos(), TimeUnit.NANOSECONDS);
			SokletStartupException startupFailure = Assertions.assertInstanceOf(
					SokletStartupException.class, failure,
					"Stop-before-ready must prevent the outer start from succeeding.");
			Assertions.assertEquals(InternalStartupDisposition.CANCELED,
					startupFailure.getInternalStartupDisposition());
			IllegalStateException exactCancellation = Assertions.assertInstanceOf(
					IllegalStateException.class, startupFailure.getCause());
			Assertions.assertEquals("Soklet shutdown was requested during startup",
					exactCancellation.getMessage());
			IllegalStateException nestedStopFailure = Assertions.assertInstanceOf(
					IllegalStateException.class, reentrantStopFailure.get(),
					"A same-thread stop must fail fast after publishing shutdown intent.");
			Assertions.assertEquals(
					"Lifecycle wait cannot run from tracked lifecycle execution",
					nestedStopFailure.getMessage());
			Assertions.assertTrue(server.getDiagnostics().getBoundAddress().isEmpty());
			InternalShutdownResult result = startupFailure
					.getInternalShutdownResult();
			Assertions.assertSame(result, adapter(server).result().orElseThrow());
			owner.close();
			owner.close();
			Assertions.assertEquals(1, executors.size());
			Assertions.assertTrue(executors.get(0).isTerminated());
		} finally {
			owner.close();
			starter.shutdownNow();
			for (ExecutorService executor : executors)
				executor.shutdownNow();
		}
	}

	@Test
	void executorFactoryReentrantStartFailsPromptlyWithoutCorruptingOuterStart()
			throws Exception {
		AtomicReference<Soklet> ownerReference = new AtomicReference<>();
		AtomicBoolean reenter = new AtomicBoolean(true);
		AtomicReference<Throwable> nestedFailure = new AtomicReference<>();
		List<ExecutorService> executors = new CopyOnWriteArrayList<>();
		McpServer server = serverBuilder(endpoint(PATH), Duration.ofSeconds(1))
				.requestHandlerExecutorServiceSupplier(() -> {
					if (reenter.compareAndSet(true, false)) {
						try {
							ownerReference.get().start();
						} catch (Throwable throwable) {
							nestedFailure.set(throwable);
						}
					}
					ExecutorService executor = Executors.newSingleThreadExecutor();
					executors.add(executor);
					return executor;
				})
					.build();
		Soklet owner = Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.build());
		ownerReference.set(owner);
		ExecutorService starter = daemonSingleThreadExecutor(
				"mcp-b3-reentrant-start-start");

		try {
			Future<Throwable> outerStart = starter.submit(() -> {
				try {
					owner.start();
					return null;
				} catch (Throwable throwable) {
					return throwable;
				}
			});
			Assertions.assertNull(outerStart.get(WAIT.toNanos(), TimeUnit.NANOSECONDS));
			IllegalStateException exactNestedFailure = Assertions.assertInstanceOf(
					IllegalStateException.class, nestedFailure.get());
			Assertions.assertEquals("Soklet lifecycle start was already claimed",
					exactNestedFailure.getMessage());
			InetSocketAddress address = boundAddress(server);
			Assertions.assertEquals(200, discovery(address.getPort(),
					"reentrant-start").get(WAIT.toNanos(),
					TimeUnit.NANOSECONDS).statusCode());
			owner.close();
			owner.close();
			Assertions.assertEquals(address, boundAddress(server));
			assertListenerReturned(address);
		} finally {
			owner.close();
			starter.shutdownNow();
			for (ExecutorService executor : executors)
				executor.shutdownNow();
		}
	}

	@Test
	void cancelledFreshOwnerCannotMutateAnotherPreparedOwner()
			throws Exception {
		CountDownLatch firstDiagnosticEntered = new CountDownLatch(1);
		CountDownLatch releaseFirstDiagnostic = new CountDownLatch(1);
		CountDownLatch secondDiagnosticEntered = new CountDownLatch(1);
		CountDownLatch releaseSecondDiagnostic = new CountDownLatch(1);
		AtomicInteger diagnosticGeneration = new AtomicInteger();
		McpServer firstServer = serverBuilder(endpoint(PATH), Duration.ofSeconds(1))
				.protectionConfig(McpProtectionConfig
						.withDevelopmentEphemeralProtection().build())
				.build();
		McpServer secondServer = serverBuilder(endpoint(PATH), Duration.ofSeconds(1))
				.protectionConfig(McpProtectionConfig
						.withDevelopmentEphemeralProtection().build())
				.build();
		LifecycleObserver lifecycle = new LifecycleObserver() {
			@Override
			public void didReceiveLogEvent(@NonNull LogEvent logEvent) {
				if (logEvent.getLogEventType()
						!= LogEventType.MCP_SERVER_CONFIGURATION
						|| !DefaultMcpServer
						.DEVELOPMENT_EPHEMERAL_PROTECTION_DIAGNOSTIC
						.equals(logEvent.getMessage()))
					return;
				int generation = diagnosticGeneration.incrementAndGet();
				if (generation == 1) {
					firstDiagnosticEntered.countDown();
					awaitUninterruptibly(releaseFirstDiagnostic);
				} else if (generation == 2) {
					secondDiagnosticEntered.countDown();
					awaitUninterruptibly(releaseSecondDiagnostic);
				}
			}
		};
		Soklet firstOwner = Soklet.fromConfig(SokletConfig.withMcpServer(firstServer)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecycleObserver(lifecycle)
				.lifecyclePolicy(TEST_LIFECYCLE_POLICY)
				.build());
		Soklet secondOwner = Soklet.fromConfig(SokletConfig.withMcpServer(secondServer)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecycleObserver(lifecycle)
				.lifecyclePolicy(TEST_LIFECYCLE_POLICY)
				.build());
		ExecutorService firstStarter = daemonSingleThreadExecutor(
				"mcp-b3-first-prepared-generation");
		ExecutorService secondStarter = daemonSingleThreadExecutor(
				"mcp-b3-second-prepared-generation");

		try {
			Future<Throwable> firstStart = firstStarter.submit(() -> {
				try {
					firstOwner.start();
					return null;
				} catch (Throwable throwable) {
					return throwable;
				}
			});
			Assertions.assertTrue(firstDiagnosticEntered.await(
					WAIT.toNanos(), TimeUnit.NANOSECONDS));
			McpTransportLifecycleAdapter.Generation firstGeneration =
					generation(firstServer);
			Assertions.assertTrue(firstServer.getDiagnostics()
					.getBoundAddress().isEmpty());

			Future<Throwable> secondStart = secondStarter.submit(() -> {
				try {
					secondOwner.start();
					return null;
				} catch (Throwable throwable) {
					return throwable;
				}
			});
			Assertions.assertTrue(secondDiagnosticEntered.await(
					WAIT.toNanos(), TimeUnit.NANOSECONDS));
			McpTransportLifecycleAdapter.Generation secondGeneration =
					generation(secondServer);
			Assertions.assertNotSame(firstGeneration, secondGeneration);
			Assertions.assertTrue(adapter(secondServer)
					.result(secondGeneration).isEmpty());
			Assertions.assertTrue(secondServer.getDiagnostics()
					.getBoundAddress().isEmpty());

			var firstShutdown = firstOwner.getDirectLifecycle().shutdown();
			releaseFirstDiagnostic.countDown();
			Throwable firstFailure = firstStart.get(
					WAIT.toNanos(), TimeUnit.NANOSECONDS);
			SokletStartupException cancelled = Assertions.assertInstanceOf(
					SokletStartupException.class, firstFailure);
			Assertions.assertEquals(InternalStartupDisposition.CANCELED,
					cancelled.getInternalStartupDisposition());
			IllegalStateException exactCancellation = Assertions.assertInstanceOf(
					IllegalStateException.class, cancelled.getCause());
			Assertions.assertEquals("Soklet shutdown was requested during startup",
					exactCancellation.getMessage());
			InternalShutdownResult firstResult = cancelled
					.getInternalShutdownResult();
			Assertions.assertSame(firstResult,
					firstShutdown.toCompletableFuture().get(
							WAIT.toNanos(), TimeUnit.NANOSECONDS)
							.internalResult());
			Assertions.assertSame(firstResult, firstOwner.getDirectLifecycle()
					.result().orElseThrow());
			Assertions.assertSame(firstResult, adapter(firstServer)
					.result(firstGeneration).orElseThrow());
			Assertions.assertTrue(firstResult.isComplete());
			Assertions.assertTrue(firstServer.getDiagnostics()
					.getBoundAddress().isEmpty());
			Assertions.assertFalse(secondStart.isDone(),
					"The independent prepared owner must remain gated.");
			Assertions.assertTrue(adapter(secondServer)
					.result(secondGeneration).isEmpty(),
					"The stale first caller cannot fail the independent owner.");
			Assertions.assertTrue(secondServer.getDiagnostics()
					.getBoundAddress().isEmpty(),
					"The stale first caller cannot bind the independent owner.");
			McpServerRuntimeBridge.LifecycleEvidence preparedEvidence = bridge(secondServer)
					.getLifecycleEvidence();
			Assertions.assertFalse(preparedEvidence.eventLoop());
			Assertions.assertFalse(preparedEvidence.executorTask());
			Assertions.assertTrue(preparedEvidence.callback(),
					"The later prepared startup remains independently in progress.");

			releaseSecondDiagnostic.countDown();
			Throwable secondFailure = secondStart.get(
					WAIT.toNanos(), TimeUnit.NANOSECONDS);
			if (secondFailure != null)
				throw new AssertionError("The later prepared generation must start.",
						secondFailure);
			Assertions.assertSame(secondGeneration, generation(secondServer));
			InetSocketAddress secondAddress = boundAddress(secondServer);
			Assertions.assertEquals(200, discovery(secondAddress.getPort(),
					"second-prepared-generation").get(
							WAIT.toNanos(), TimeUnit.NANOSECONDS).statusCode());

			secondOwner.close();
			secondOwner.close();
			Assertions.assertTrue(adapter(secondServer).result(secondGeneration)
					.orElseThrow().isComplete());
			Assertions.assertEquals(secondAddress, boundAddress(secondServer));
			assertListenerReturned(secondAddress);
			Assertions.assertEquals(2, diagnosticGeneration.get());
		} finally {
			releaseFirstDiagnostic.countDown();
			releaseSecondDiagnostic.countDown();
			firstStarter.shutdownNow();
			secondStarter.shutdownNow();
			firstStarter.awaitTermination(WAIT.toNanos(), TimeUnit.NANOSECONDS);
			secondStarter.awaitTermination(WAIT.toNanos(), TimeUnit.NANOSECONDS);
			firstOwner.close();
			secondOwner.close();
		}
	}

	@Test
	void oneServerStartupDoesNotMakeAnotherServerStopFailFast()
			throws Exception {
		McpServer other = serverBuilder(endpoint(PATH), Duration.ofSeconds(1)).build();
		Soklet otherOwner = Soklet.fromConfig(SokletConfig.withMcpServer(other)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecyclePolicy(TEST_LIFECYCLE_POLICY)
				.build());
		AtomicReference<Throwable> crossServerStopFailure = new AtomicReference<>();
		List<ExecutorService> executors = new CopyOnWriteArrayList<>();
		McpServer starting = serverBuilder(endpoint(PATH), Duration.ofSeconds(1))
				.requestHandlerExecutorServiceSupplier(() -> {
					try {
						otherOwner.close();
					} catch (Throwable throwable) {
						crossServerStopFailure.set(throwable);
					}
					ExecutorService executor = Executors.newSingleThreadExecutor();
					executors.add(executor);
					return executor;
				})
				.build();
		Soklet startingOwner = Soklet.fromConfig(SokletConfig.withMcpServer(starting)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecyclePolicy(TEST_LIFECYCLE_POLICY)
				.build());

		try {
			otherOwner.start();
			InetSocketAddress otherAddress = boundAddress(other);
			startingOwner.start();

			Assertions.assertNull(crossServerStopFailure.get(),
					"Server A startup must not make server B's stop look self-joining.");
			Assertions.assertEquals(McpServerStatus.TERMINATED,
					other.getDiagnostics().getStatus());
			Assertions.assertEquals(otherAddress, boundAddress(other));
			assertListenerReturned(otherAddress);
			Assertions.assertEquals(200, discovery(boundPort(starting),
					"cross-server-start").get(WAIT.toNanos(),
							TimeUnit.NANOSECONDS).statusCode());
		} finally {
			startingOwner.close();
			startingOwner.close();
			otherOwner.close();
			otherOwner.close();
			for (ExecutorService executor : executors)
				executor.shutdownNow();
		}
	}

	@Test
	void idleSubscriptionClosesPromptlyWithServerStoppedAndNoForce()
			throws Exception {
		int subscriptionCount = 3;
		AtomicInteger sourceRegistrationCloses = new AtomicInteger();
		McpSubscriptionEventPublisher publisher = new McpSubscriptionEventPublisher() {
			@Override
			public McpSubscriptionEventRegistration subscribe(
					@NonNull McpSubscriptionEventListener listener) {
				AtomicBoolean closed = new AtomicBoolean();
				return () -> {
					if (closed.compareAndSet(false, true))
						sourceRegistrationCloses.incrementAndGet();
				};
			}

			@Override
			public void publish(@NonNull McpSubscriptionEvent event) {
				// The idle lifecycle case publishes no application event.
			}
		};
		McpSubscriptionConfig subscriptions = McpSubscriptionConfig
				.withEventPublisher(publisher, Set.of(
						McpSubscriptionNotificationType.RESOURCES_LIST_CHANGED))
				.build();
		McpEndpoint endpoint = McpEndpoint.withPath(PATH,
				implementation("b3-subscription"))
				.resourceListHandler((request, list, features) ->
						McpResourcePage.builder().build())
				.subscriptionConfig(subscriptions)
				.build();
		Duration gracefulTimeout = Duration.ofSeconds(10);
		McpServer server = serverBuilder(endpoint, gracefulTimeout)
				.maximumSubscriptionsPerPartition(subscriptionCount + 1)
				.maximumSubscriptionDuration(Duration.ofDays(3_650))
				.build();
		Fixture fixture = fixture(server, withGracefulShutdownTimeout(
				TEST_LIFECYCLE_POLICY, gracefulTimeout));
		List<HttpResponse<InputStream>> responses = new ArrayList<>();

		try {
			fixture.soklet().start();
			McpTransportLifecycleAdapter.Generation generation = generation(server);
			for (int index = 0; index < subscriptionCount; index++) {
				HttpResponse<InputStream> response = subscription(boundPort(server),
						"subscription-" + index).get(
						WAIT.toNanos(), TimeUnit.NANOSECONDS);
				Assertions.assertEquals(200, response.statusCode());
				responses.add(response);
			}
			Assertions.assertTrue(fixture.metrics().subscriptionOpened.await(
					WAIT.toNanos(), TimeUnit.NANOSECONDS));
			awaitCondition(() -> server.getDiagnostics().getActiveSubscriptions()
					== subscriptionCount,
					"All idle subscriptions did not become active.");
			Assertions.assertEquals(subscriptionCount, admittedWork(generation));

			long stopStartedAt = System.nanoTime();
			fixture.soklet().close();
			Duration stopDuration = Duration.ofNanos(
					System.nanoTime() - stopStartedAt);
			Assertions.assertTrue(stopDuration.compareTo(Duration.ofSeconds(3)) < 0,
					() -> "Idle subscriptions consumed their grace budget: "
							+ stopDuration + " of " + gracefulTimeout);
			Assertions.assertEquals(0, admittedWork(generation));
			assertParticipant(server,
					InternalLifecycleComponentShutdownDisposition.GRACEFUL_TERMINATION);
			assertLegacyParity(fixture, ShutdownComponentDisposition.GRACEFUL_TERMINATION);
			Assertions.assertEquals(java.util.Collections.nCopies(subscriptionCount,
					McpStreamTerminationReason.SERVER_STOPPING),
					fixture.metrics().subscriptionCloseReasons);
			Assertions.assertEquals(1, sourceRegistrationCloses.get());
		} finally {
			for (HttpResponse<InputStream> response : responses)
				response.body().close();
			fixture.close();
		}
	}

	@Test
	void executableEndpointPlansAreImmutableAndFreshPerServerFactory()
			throws Exception {
		Supplier<PlanFixture> factory = () -> {
			AtomicInteger invocations = new AtomicInteger();
			String marker = "fixture-" + System.identityHashCode(invocations);
			McpToolRegistration<McpJsonObject> tool = tool("b3.plan",
					(request, arguments, features) -> {
						invocations.incrementAndGet();
						return McpCompleteResult.fromToolText(marker);
					});
			McpServer server = serverBuilder(endpoint(PATH, tool),
					Duration.ofSeconds(1)).build();
			Soklet owner = Soklet.fromConfig(SokletConfig.withMcpServer(server)
					.resourceMethodResolver(
							ResourceMethodResolver.fromMethods(Set.of()))
					.lifecyclePolicy(TEST_LIFECYCLE_POLICY)
					.build());
			return new PlanFixture(server, owner, invocations, marker);
		};
		PlanFixture first = factory.get();
		PlanFixture second = factory.get();
		List<EndpointPlan> firstPlans = executablePlans(first.server());
		List<EndpointPlan> secondPlans = executablePlans(second.server());

		Assertions.assertThrows(UnsupportedOperationException.class,
				() -> firstPlans.add(firstPlans.get(0)));
		Assertions.assertThrows(UnsupportedOperationException.class,
				() -> firstPlans.get(0).toolPlans().clear());
		Assertions.assertNotSame(firstPlans, secondPlans);
		Assertions.assertNotSame(firstPlans.get(0).toolPlans().get(0).invoker(),
				secondPlans.get(0).toolPlans().get(0).invoker());
		Assertions.assertTrue(first.server().getDiagnostics().getBoundAddress().isEmpty());
		Assertions.assertTrue(second.server().getDiagnostics().getBoundAddress().isEmpty());

		try {
			first.owner().start();
			Assertions.assertTrue(second.server().getDiagnostics()
					.getBoundAddress().isEmpty(),
					"Extracting one plan must not mutate another production generation.");
			HttpResponse<String> firstResponse = callTool(boundPort(first.server()),
					"first-plan", "b3.plan", false).get(
					WAIT.toNanos(), TimeUnit.NANOSECONDS);
			Assertions.assertTrue(firstResponse.body().contains(first.marker()));
			Assertions.assertFalse(firstResponse.body().contains(second.marker()));
			Assertions.assertEquals(1, first.invocations().get());
			Assertions.assertEquals(0, second.invocations().get());

			second.owner().start();
			HttpResponse<String> secondResponse = callTool(boundPort(second.server()),
					"second-plan", "b3.plan", false).get(
					WAIT.toNanos(), TimeUnit.NANOSECONDS);
			Assertions.assertTrue(secondResponse.body().contains(second.marker()));
			Assertions.assertFalse(secondResponse.body().contains(first.marker()));
			Assertions.assertEquals(1, first.invocations().get());
			Assertions.assertEquals(1, second.invocations().get());
		} finally {
			first.owner().close();
			first.owner().close();
			second.owner().close();
			second.owner().close();
		}
	}

	@NonNull
	private static Fixture fixture(@NonNull McpServer server) {
		return fixture(server, TEST_LIFECYCLE_POLICY);
	}

	@NonNull
	private static Fixture fixture(@NonNull McpServer server,
			@NonNull LifecyclePolicy lifecyclePolicy) {
		RecordingMetrics metrics = new RecordingMetrics();
		RecordingLifecycle lifecycle = new RecordingLifecycle();
		Soklet soklet = Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.metricsCollector(metrics)
				.lifecycleObserver(lifecycle)
				.lifecyclePolicy(lifecyclePolicy)
				.build());
		return new Fixture(server, soklet, metrics, lifecycle, bridge(server));
	}

	@NonNull
	private static LifecyclePolicy shortShutdownPolicy() {
		return shutdownPolicy(Duration.ofMillis(100), Duration.ofMillis(100));
	}

	@NonNull
	private static LifecyclePolicy withGracefulShutdownTimeout(
			@NonNull LifecyclePolicy policy,
			@NonNull Duration gracefulShutdownTimeout) {
		return LifecyclePolicy.builder()
				.startupTimeout(policy.getStartupTimeout())
				.startupCancelationTimeout(
						policy.getStartupCancelationTimeout())
				.gracefulShutdownTimeout(gracefulShutdownTimeout)
				.forcedShutdownTimeout(policy.getForcedShutdownTimeout())
				.build();
	}

	@NonNull
	private static LifecyclePolicy shutdownPolicy(
			@NonNull Duration gracefulTimeout,
			@NonNull Duration forcedTimeout) {
		return LifecyclePolicy.builder()
				.startupTimeout(WAIT)
				.startupCancelationTimeout(Duration.ofMillis(100))
				.gracefulShutdownTimeout(gracefulTimeout)
				.forcedShutdownTimeout(forcedTimeout)
				.build();
	}

	private static void closeAfterTerminalFailure(@NonNull Fixture fixture) {
		closeAfterTerminalFailure(fixture.soklet());
	}

	private static void closeAfterTerminalFailure(@NonNull Soklet owner) {
		try {
			owner.close();
		} catch (SokletUnexpectedTerminationException
				| SokletShutdownIncompleteException ignored) {
			// Cleanup must preserve the already-asserted immutable terminal result.
		}
	}

	private static McpServer.@NonNull Builder serverBuilder(
			@NonNull McpEndpoint endpoint,
			@NonNull Duration shutdownTimeout) {
		return serverBuilder(0, endpoint, shutdownTimeout);
	}

	private static McpServer.@NonNull Builder serverBuilder(int port,
			@NonNull McpEndpoint endpoint,
			@NonNull Duration shutdownTimeout) {
		requireNonNull(shutdownTimeout);
		return McpServer.withPort(port,
				McpEndpointRegistry.fromEndpoints(List.of(endpoint)),
				McpAdmissionController.acceptAllInstance())
				.host(HOST)
				.toolRateLimiter(context -> McpRateLimitDecision.allowed())
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(HOST))
				.requestHandlerConcurrency(1)
				.requestHandlerQueueCapacity(1);
	}

	@NonNull
	private static McpServerRuntimeBridge lifecycleBridge(
			@NonNull List<@NonNull EndpointPlan> endpointPlans,
			@NonNull McpTransportLifecycleAdapter adapter) {
		return new McpServerRuntimeBridge(HOST, 0, endpointPlans, Set.of(HOST),
				false, CorsAuthorizer.rejectAllInstance(), true,
				ignored -> McpAdmissionDecision.accepted(), Optional.empty(),
				McpUnknownMirroredHeaderPolicy.REJECT_REQUESTS, false,
				(endpointPath, method) -> {}, 1, 1, Duration.ofSeconds(1),
				Optional.empty(), ignored -> {}, ignored -> {},
				McpRequestObservationTestSupport.noOpAdapter(), Optional.empty(),
				128, Duration.ofSeconds(30), Duration.ofSeconds(15),
				Duration.ofSeconds(1), 4, Duration.ofHours(1),
				McpApplicationExecutionObserver.disabledInstance(), adapter);
	}

	@NonNull
	private static McpEndpoint endpoint(@NonNull String path) {
		return McpEndpoint.withPath(path, implementation("b3-lifecycle"))
				.build();
	}

	@NonNull
	private static McpEndpoint endpoint(@NonNull String path,
			@NonNull McpToolRegistration<McpJsonObject> tool) {
		return McpEndpoint.withPath(path, implementation("b3-lifecycle"))
				.addTool(tool)
				.build();
	}

	@NonNull
	private static McpImplementation implementation(@NonNull String name) {
		return McpImplementation.withNameAndVersion(name, "4.0.0").build();
	}

	@NonNull
	private static McpToolRegistration<McpJsonObject> tool(@NonNull String name,
			@NonNull McpToolHandler<McpJsonObject> handler) {
		return McpToolRegistration.withName(name).jsonObjectArguments()
				.handler(handler).build();
	}

	@NonNull
	private static CompletableFuture<HttpResponse<String>> discovery(int port,
			@NonNull String id) {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + id
				+ "\",\"method\":\"server/discover\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\"" + PROTOCOL_VERSION
				+ "\",\"io.modelcontextprotocol/clientCapabilities\":{}}}}";
		return send(port, "server/discover", null, body,
				HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	@NonNull
	private static CompletableFuture<HttpResponse<String>> callTool(int port,
			@NonNull String id, @NonNull String toolName, boolean progress) {
		String progressMetadata = progress ? ",\"progressToken\":\"" + id
				+ "-progress\"" : "";
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + id
				+ "\",\"method\":\"tools/call\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\"" + PROTOCOL_VERSION
				+ "\",\"io.modelcontextprotocol/clientCapabilities\":{}"
				+ progressMetadata + "},\"name\":\"" + toolName
				+ "\",\"arguments\":{}}}";
		return send(port, "tools/call", toolName, body,
				HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	@NonNull
	private static CompletableFuture<HttpResponse<InputStream>> subscription(int port) {
		return subscription(port, "subscription");
	}

	@NonNull
	private static CompletableFuture<HttpResponse<InputStream>> subscription(int port,
			@NonNull String id) {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\","
				+ "\"method\":\"subscriptions/listen\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\"" + PROTOCOL_VERSION
				+ "\",\"io.modelcontextprotocol/clientCapabilities\":{}},"
				+ "\"notifications\":{\"resourcesListChanged\":true}}}";
		return send(port, "subscriptions/listen", null, body,
				HttpResponse.BodyHandlers.ofInputStream());
	}

	@NonNull
	private static <T> CompletableFuture<HttpResponse<T>> send(int port,
			@NonNull String method, String name, @NonNull String body,
			HttpResponse.@NonNull BodyHandler<T> bodyHandler) {
		HttpRequest.Builder request = HttpRequest.newBuilder()
				.uri(URI.create("http://" + HOST + ':' + port + PATH))
				.timeout(Duration.ofSeconds(10))
				.header("Content-Type", "application/json; charset=UTF-8")
				.header("Accept", "application/json, text/event-stream")
				.header("MCP-Protocol-Version", PROTOCOL_VERSION)
				.header("Mcp-Method", method);
		if (name != null)
			request.header("Mcp-Name", name);
		return HttpClient.newBuilder().connectTimeout(WAIT)
				.version(HttpClient.Version.HTTP_1_1).build()
				.sendAsync(request.POST(HttpRequest.BodyPublishers.ofString(
						body, StandardCharsets.UTF_8)).build(), bodyHandler);
	}

	private static int boundPort(@NonNull McpServer server) {
		return boundAddress(server).getPort();
	}

	@NonNull
	private static InetSocketAddress boundAddress(@NonNull McpServer server) {
		return server.getDiagnostics().getBoundAddress().orElseThrow();
	}

	@NonNull
	private static McpTransportLifecycleAdapter adapter(@NonNull McpServer server) {
		try {
			Field field = DefaultMcpServer.class.getDeclaredField("lifecycleAdapter");
			field.setAccessible(true);
			return (McpTransportLifecycleAdapter) field.get(server);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError(exception);
		}
	}

	@NonNull
	private static Object serverLifecycleLock(@NonNull McpServer server) {
		try {
			Field field = DefaultMcpServer.class.getDeclaredField("lifecycleLock");
			field.setAccessible(true);
			return requireNonNull(field.get(requireNonNull(server)));
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError(exception);
		}
	}

	@NonNull
	private static McpServerRuntimeBridge bridge(@NonNull McpServer server) {
		try {
			Field field = DefaultMcpServer.class.getDeclaredField("runtimeBridge");
			field.setAccessible(true);
			return (McpServerRuntimeBridge) field.get(server);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError(exception);
		}
	}

	@NonNull
	private static Object runtime(@NonNull McpServerRuntimeBridge bridge) {
		try {
			Field field = McpServerRuntimeBridge.class.getDeclaredField("runtime");
			field.setAccessible(true);
			return requireNonNull(field.get(requireNonNull(bridge)));
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError(exception);
		}
	}

	@NonNull
	private static Object runtimeLifecycleLock(@NonNull Object runtime) {
		try {
			Field field = runtime.getClass().getDeclaredField("lifecycleLock");
			field.setAccessible(true);
			return requireNonNull(field.get(runtime));
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError(exception);
		}
	}

	private static @Nullable Object currentReadiness(@NonNull Object runtime) {
		try {
			Field field = runtime.getClass().getDeclaredField("currentReadiness");
			field.setAccessible(true);
			return field.get(runtime);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError(exception);
		}
	}

	private static McpTransportLifecycleAdapter.@NonNull Generation generation(
			@NonNull McpServer server) {
		return (McpTransportLifecycleAdapter.Generation)
				adapter(server).currentGeneration();
	}

	private static BuiltInTransportLifecycleAdapter.Generation builtInGeneration(
			McpTransportLifecycleAdapter.@NonNull Generation generation) {
		try {
			Field field = McpTransportLifecycleAdapter.Generation.class
					.getDeclaredField("delegate");
			field.setAccessible(true);
			return (BuiltInTransportLifecycleAdapter.Generation) field.get(generation);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError(exception);
		}
	}

	@NonNull
	private static InternalTerminationGroup terminationGroup(
			McpTransportLifecycleAdapter.@NonNull Generation generation) {
		try {
			Field field = BuiltInTransportLifecycleAdapter.Generation.class
					.getDeclaredField("group");
			field.setAccessible(true);
			return (InternalTerminationGroup) field.get(builtInGeneration(generation));
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError(exception);
		}
	}

	private static McpTransportLifecycleAdapter.@NonNull Generation pendingGeneration(
			@NonNull McpServer server) {
		try {
			Field field = DefaultMcpServer.class.getDeclaredField(
					"pendingListenerGeneration");
			field.setAccessible(true);
			return (McpTransportLifecycleAdapter.Generation) requireNonNull(
					field.get(server));
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError(exception);
		}
	}

	private static BuiltInTransportLifecycleAdapter builtInAdapter(
			@NonNull McpServer server) {
		return builtInAdapter(adapter(server));
	}

	@NonNull
	private static BuiltInTransportLifecycleAdapter builtInAdapter(
			@NonNull McpTransportLifecycleAdapter adapter) {
		try {
			Field field = McpTransportLifecycleAdapter.class.getDeclaredField("delegate");
			field.setAccessible(true);
			return (BuiltInTransportLifecycleAdapter) field.get(adapter);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError(exception);
		}
	}

	private static int admittedWork(
			McpTransportLifecycleAdapter.@NonNull Generation generation) {
		try {
			Field field = BuiltInTransportLifecycleAdapter.Generation.class
					.getDeclaredField("admissionFence");
			field.setAccessible(true);
			return ((AdmissionFence) field.get(builtInGeneration(generation)))
					.admittedWorkCount();
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError(exception);
		}
	}

	private static long gracefulDeadline(
			McpTransportLifecycleAdapter.@NonNull Generation generation) {
		try {
			Field field = BuiltInTransportLifecycleAdapter.Generation.class
					.getDeclaredField("gracefulDeadlineNanos");
			field.setAccessible(true);
			return field.getLong(builtInGeneration(generation));
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError(exception);
		}
	}

	@NonNull
	private static List<InternalTerminationEvent> terminationEvents(
			@NonNull McpServer server,
			McpTransportLifecycleAdapter.@NonNull Generation generation) {
		return builtInAdapter(server).terminationEvents(
				builtInGeneration(generation));
	}

	@NonNull
	private static List<InternalTerminationEvent> terminationEvents(
			@NonNull McpTransportLifecycleAdapter adapter,
			McpTransportLifecycleAdapter.@NonNull Generation generation) {
		return builtInAdapter(adapter).terminationEvents(builtInGeneration(generation));
	}

	@NonNull
	private static McpTransportLifecycleAdapter deterministicAdapter(
			@NonNull RecordingAdapterOperations operations,
			@NonNull Duration gracefulTimeout, @NonNull Duration forcedTimeout,
			@NonNull NanoClock clock, @NonNull LifecycleWorkers workers) {
		return new McpTransportLifecycleAdapter(gracefulTimeout, forcedTimeout,
				clock, workers, operations);
	}

	@NonNull
	private static LifecycleWorkers inlineLifecycleWorkers() {
		return new LifecycleWorkers((name, runnable) -> runnable.run());
	}

	@NonNull
	private static ExecutorService daemonSingleThreadExecutor(
			@NonNull String name) {
		return Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, name);
			thread.setDaemon(true);
			return thread;
		});
	}

	@NonNull
	private static InternalLifecycleComponentShutdownResult mcpParticipant(
			@NonNull InternalShutdownResult result) {
		return result.participantResult(InternalLifecycleComponentType.MCP).orElseThrow();
	}

	@NonNull
	private static InternalLifecycleComponentShutdownResult assertParticipant(
			@NonNull McpServer server,
			@NonNull InternalLifecycleComponentShutdownDisposition disposition) {
		InternalShutdownResult result = adapter(server).result().orElseThrow();
		InternalLifecycleComponentShutdownResult participant = result.participantResult(
				InternalLifecycleComponentType.MCP).orElseThrow();
		Assertions.assertEquals(disposition, participant.disposition());
		return participant;
	}

	private static void assertLegacyParity(@NonNull Fixture fixture,
			@NonNull ShutdownComponentDisposition expected) throws InterruptedException {
		fixture.lifecycle().awaitTerminal();
		awaitCondition(() -> fixture.metrics().shutdownOutcomes.size() == 1,
				"The terminal MCP metric was not published.");
		ShutdownResult aggregate = fixture.soklet().getShutdownResult()
				.orElseThrow();
		ShutdownComponentResult participant = aggregate.getShutdownComponentResult(
				ShutdownComponentType.MCP).orElseThrow();
		Assertions.assertSame(aggregate, fixture.lifecycle().globalResult.get());
		Assertions.assertEquals(List.of(participant),
				fixture.lifecycle().results);
		Assertions.assertEquals(expected, participant.getShutdownComponentDisposition());
		Assertions.assertEquals(List.of(expected),
				List.copyOf(fixture.metrics().shutdownOutcomes));
	}

	private static void assertIncompleteLegacyParity(@NonNull Fixture fixture,
			@NonNull InternalShutdownResult result) throws InterruptedException {
		fixture.lifecycle().awaitTerminal();
		awaitCondition(() -> fixture.metrics().shutdownOutcomes.size() == 1,
				"The residual MCP metric was not published.");
		ShutdownResult aggregate = fixture.soklet().getShutdownResult()
				.orElseThrow();
		Assertions.assertSame(result, aggregate.internalResult());
		Assertions.assertSame(aggregate, fixture.lifecycle().globalResult.get());
		ShutdownComponentResult participant = aggregate.getShutdownComponentResult(
				ShutdownComponentType.MCP).orElseThrow();
		Assertions.assertEquals(List.of(participant),
				fixture.lifecycle().results,
				"An incomplete MCP participant still publishes one exact terminal callback.");
		Assertions.assertEquals(ShutdownComponentDisposition.RESIDUAL_ACTIVITY,
				participant.getShutdownComponentDisposition());
		Assertions.assertEquals(List.of(ShutdownComponentDisposition.RESIDUAL_ACTIVITY),
				List.copyOf(fixture.metrics().shutdownOutcomes));
	}

	private static void assertRuntimeEvidenceReleased(
			@NonNull McpServerRuntimeBridge bridge) {
		McpServerRuntimeBridge.LifecycleEvidence evidence = bridge.getLifecycleEvidence();
		Assertions.assertFalse(evidence.eventLoop());
		Assertions.assertFalse(evidence.connection());
		Assertions.assertFalse(evidence.executorTask());
		Assertions.assertFalse(evidence.stream());
		Assertions.assertFalse(evidence.callback());
		Assertions.assertFalse(evidence.subscriptionRegistration());
	}

	private static void assertListenerReturned(@NonNull InetSocketAddress address)
			throws Exception {
		try (ServerSocket socket = new ServerSocket()) {
			socket.setReuseAddress(true);
			socket.bind(address);
		}
	}

	@NonNull
	private static EventLoop eventLoop(@NonNull McpServerRuntimeBridge bridge)
			throws Exception {
		Object runtime = runtime(bridge);
		Field eventLoopField = runtime.getClass().getDeclaredField("eventLoop");
		eventLoopField.setAccessible(true);
		return (EventLoop) eventLoopField.get(runtime);
	}

	private static void terminateUnexpectedly(@NonNull EventLoop eventLoop)
			throws Exception {
		closeSelector(eventLoop);
		Assertions.assertTrue(eventLoop.join(WAIT),
				"The MCP event loop did not terminate unexpectedly.");
	}

	private static void closeSelector(@NonNull EventLoop eventLoop)
			throws Exception {
		Field selectorField = EventLoop.class.getDeclaredField("selector");
		selectorField.setAccessible(true);
		((Selector) selectorField.get(eventLoop)).close();
	}

	private static void assertRedactedTransportFailureLog(
			@NonNull LogEvent event) {
		Assertions.assertEquals(LogEventType.SERVER_TRANSPORT_FAILURE,
				event.getLogEventType());
		Assertions.assertEquals("MCP transport failure: event_loop_terminate",
				event.getMessage());
		Assertions.assertTrue(event.getThrowable().isEmpty());
		Assertions.assertTrue(event.getRequest().isEmpty());
		Assertions.assertTrue(event.getResourceMethod().isEmpty());
		Assertions.assertTrue(event.getMarshaledResponse().isEmpty());
	}

	@NonNull
	private static ConnectionListener connectionListener(
			@NonNull EventLoop eventLoop) throws Exception {
		Field field = EventLoop.class.getDeclaredField("connectionListener");
		field.setAccessible(true);
		return (ConnectionListener) field.get(eventLoop);
	}

	@SuppressWarnings("unchecked")
	@NonNull
	private static List<EndpointPlan> executablePlans(@NonNull McpServer server)
			throws Exception {
		Method method = McpServerRuntimeBridge.class.getDeclaredMethod(
				"executableEndpointPlans");
		method.setAccessible(true);
		return (List<EndpointPlan>) method.invoke(bridge(server));
	}

	private static void awaitCondition(@NonNull BooleanSupplier condition,
			@NonNull String failure) throws InterruptedException {
		long deadline = System.nanoTime() + WAIT.toNanos();
		while (System.nanoTime() - deadline < 0L) {
			if (condition.getAsBoolean())
				return;
			Thread.onSpinWait();
		}
		Assertions.fail(failure);
	}

	private static void awaitBlocked(@NonNull Thread thread,
			@NonNull String failure) {
		long deadline = System.nanoTime() + WAIT.toNanos();
		do {
			Thread.State state = thread.getState();
			if (state == Thread.State.BLOCKED)
				return;
			if (state == Thread.State.TERMINATED)
				Assertions.fail(failure + " The thread terminated early.");
			Thread.onSpinWait();
		} while (System.nanoTime() - deadline < 0L);
		Assertions.fail(failure);
	}

	private static void awaitUninterruptibly(@NonNull CountDownLatch latch) {
		boolean interrupted = false;
		for (;;) {
			try {
				latch.await();
				break;
			} catch (InterruptedException exception) {
				interrupted = true;
			}
		}
		if (interrupted)
			Thread.currentThread().interrupt();
	}

	private record Fixture(@NonNull McpServer server, @NonNull Soklet soklet,
			@NonNull RecordingMetrics metrics,
			@NonNull RecordingLifecycle lifecycle,
			@NonNull McpServerRuntimeBridge bridge) implements AutoCloseable {
		@Override
		public void close() {
			closeAfterTerminalFailure(this.soklet);
		}
	}

	private record PlanFixture(@NonNull McpServer server, @NonNull Soklet owner,
			@NonNull AtomicInteger invocations, @NonNull String marker) {
	}

	@ThreadSafe
	private static final class RecordingAdapterOperations
			implements BuiltInTransportLifecycleAdapter.Operations {
		@NonNull
		private final IntPredicate proofOnAttempt;
		@NonNull
		private final Set<InternalResidualActivityType> residualActivity;
		private final AtomicInteger awaitCount = new AtomicInteger();
		private final AtomicInteger quiesceCount = new AtomicInteger();
		private final AtomicInteger forceCount = new AtomicInteger();
		private final AtomicInteger releaseCount = new AtomicInteger();
		private final List<Long> deadlines = new CopyOnWriteArrayList<>();
		private final List<String> events = new CopyOnWriteArrayList<>();
		private volatile Runnable onQuiesce = () -> {};
		private volatile LongConsumer onAwait = deadline -> {};

		private RecordingAdapterOperations(@NonNull IntPredicate proofOnAttempt,
				@NonNull Set<InternalResidualActivityType> residualActivity) {
			this.proofOnAttempt = proofOnAttempt;
			this.residualActivity = residualActivity;
		}

		@Override
		public void quiesce() {
			this.quiesceCount.incrementAndGet();
			this.events.add("quiesce");
			this.onQuiesce.run();
		}

		@Override
		public void force() {
			this.forceCount.incrementAndGet();
			this.events.add("force");
		}

		@Override
		public boolean awaitTermination(long absoluteDeadlineNanos) {
			this.deadlines.add(absoluteDeadlineNanos);
			this.events.add("await-" + absoluteDeadlineNanos);
			this.onAwait.accept(absoluteDeadlineNanos);
			return this.proofOnAttempt.test(this.awaitCount.incrementAndGet());
		}

		@Override
		@NonNull
		public Set<InternalResidualActivityType> residualActivity() {
			this.events.add("residual");
			return this.residualActivity;
		}

		@Override
		public void releaseTerminatedEvidence() {
			this.releaseCount.incrementAndGet();
			this.events.add("release");
		}
	}

	@ThreadSafe
	private static final class RecordingLifecycle implements LifecycleObserver {
		private final List<ShutdownComponentResult> results =
				new CopyOnWriteArrayList<>();
		private final AtomicReference<ShutdownResult> globalResult =
				new AtomicReference<>();
		private final CountDownLatch terminal = new CountDownLatch(1);

		private void awaitTerminal() throws InterruptedException {
			Assertions.assertTrue(this.terminal.await(
					WAIT.toNanos(), TimeUnit.NANOSECONDS),
					"The global lifecycle terminal callback was not observed.");
		}

		@Override
		public void didStopMcpServer(@NonNull McpServer server,
				@NonNull ShutdownComponentResult result) {
			ShutdownComponentResult exactResult = requireNonNull(result);
			this.results.add(exactResult);
		}

		@Override
		public void didStopSoklet(@NonNull Soklet soklet,
				@NonNull ShutdownResult result) {
			this.globalResult.set(requireNonNull(result));
			this.terminal.countDown();
		}

		@Override
		public void didReceiveLogEvent(@NonNull LogEvent logEvent) {
			// Expected lifecycle and configuration diagnostics remain quiet.
		}
	}

	@ThreadSafe
	private static final class RecordingMetrics implements MetricsCollector {
		private final CountDownLatch streamOpened = new CountDownLatch(1);
		private final CountDownLatch subscriptionOpened = new CountDownLatch(1);
		private final List<McpStreamTerminationReason> streamCloseReasons =
				new CopyOnWriteArrayList<>();
		private final List<McpStreamTerminationReason> subscriptionCloseReasons =
				new CopyOnWriteArrayList<>();
		private final List<ShutdownComponentDisposition> shutdownOutcomes =
				new CopyOnWriteArrayList<>();

		@Override
		public void didRecordMcpMetricsEvent(@NonNull McpMetricsEvent event) {
			if (event instanceof McpMetricsEvent.RequestStreamOpened)
				this.streamOpened.countDown();
			else if (event instanceof McpMetricsEvent.RequestStreamClosed closed)
				this.streamCloseReasons.add(closed.getReason());
			else if (event instanceof McpMetricsEvent.SubscriptionOpened)
				this.subscriptionOpened.countDown();
			else if (event instanceof McpMetricsEvent.SubscriptionClosed closed)
				this.subscriptionCloseReasons.add(closed.getReason());
			else if (event instanceof McpMetricsEvent.ServerStopped stopped)
				this.shutdownOutcomes.add(
						stopped.getShutdownComponentDisposition());
		}
	}
}
