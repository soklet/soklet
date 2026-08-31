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

import com.soklet.annotation.GET;
import com.soklet.annotation.SseEventSource;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.soklet.TestSupport.findFreePort;
import static java.util.Objects.requireNonNull;

@Timeout(value = 60, unit = TimeUnit.SECONDS)
final class SokletApplicationObservationTests {
	@Test
	void transitionDispatcherPreservesOrderAndRecordsBoundedFailure() {
		AtomicReference<Runnable> workerTask = new AtomicReference<>();
		LifecycleWorkers workers = new LifecycleWorkers((name, task) -> {
			Assertions.assertEquals("soklet-lifecycle-observer", name);
			Assertions.assertTrue(workerTask.compareAndSet(null, task));
		});
		LifecycleTransitionDispatcher dispatcher =
				new LifecycleTransitionDispatcher(workers);
		List<String> callbacks = new ArrayList<>();
		IllegalStateException failure = new IllegalStateException(
				"observer\nfailed");

		dispatcher.dispatch(() -> callbacks.add("first"));
		dispatcher.dispatch(() -> {
			callbacks.add("second");
			throw failure;
		});
		dispatcher.dispatch(() -> callbacks.add("third"));
		dispatcher.seal();

		LifecycleTransitionSnapshot queued = dispatcher.snapshot();
		Assertions.assertEquals(3, queued.acceptedRecords());
		Assertions.assertEquals(3, queued.pendingRecords());
		Assertions.assertFalse(queued.callbackActive());
		Assertions.assertTrue(queued.sealed());
		Assertions.assertEquals(0, queued.failedCallbacks());
		requireNonNull(workerTask.get()).run();

		LifecycleTransitionSnapshot drained = dispatcher.snapshot();
		Assertions.assertEquals(List.of("first", "second", "third"), callbacks);
		Assertions.assertEquals(3, drained.acceptedRecords());
		Assertions.assertEquals(0, drained.pendingRecords());
		Assertions.assertFalse(drained.callbackActive());
		Assertions.assertEquals(1, drained.failedCallbacks());
		Assertions.assertEquals(IllegalStateException.class.getName(),
				drained.firstFailureSummary().orElseThrow());
		Assertions.assertFalse(drained.firstFailureSummary().orElseThrow()
				.contains("observer"));
		Assertions.assertEquals(1,
				workers.created(LifecycleWorkers.Role.TRANSITION_OBSERVER));
		Assertions.assertEquals(0,
				workers.active(LifecycleWorkers.Role.TRANSITION_OBSERVER));
	}

	@Test
	void legalSixteenRecordTraceIsAcceptedWithoutDropping() {
		AtomicReference<Runnable> workerTask = new AtomicReference<>();
		LifecycleWorkers workers = new LifecycleWorkers((name, task) ->
				workerTask.set(task));
		LifecycleTransitionDispatcher dispatcher =
				new LifecycleTransitionDispatcher(workers);
		AtomicInteger callbacks = new AtomicInteger();

		for (int index = 0; index < 16; index++)
			dispatcher.dispatch(callbacks::incrementAndGet);
		Assertions.assertThrows(IllegalStateException.class,
				() -> dispatcher.dispatch(callbacks::incrementAndGet));
		dispatcher.seal();
		requireNonNull(workerTask.get()).run();

		LifecycleTransitionSnapshot snapshot = dispatcher.snapshot();
		Assertions.assertEquals(16, callbacks.get());
		Assertions.assertEquals(16, snapshot.acceptedRecords());
		Assertions.assertEquals(0, snapshot.pendingRecords());
		Assertions.assertEquals(0, snapshot.failedCallbacks());
		Assertions.assertTrue(snapshot.firstFailureSummary().isEmpty());
	}

	@Test
	void transitionLaunchFailurePreservesEveryAcceptedRecordForDiagnostics() {
		IllegalStateException launchFailure = new IllegalStateException(
				"observer worker rejected");
		LifecycleTransitionDispatcher dispatcher =
				new LifecycleTransitionDispatcher(new LifecycleWorkers(
						(name, task) -> { throw launchFailure; }));

		dispatcher.dispatch(() -> { });
		dispatcher.dispatch(() -> { });
		dispatcher.dispatch(() -> { });
		dispatcher.seal();

		LifecycleTransitionSnapshot snapshot = dispatcher.snapshot();
		Assertions.assertEquals(3, snapshot.acceptedRecords());
		Assertions.assertEquals(3, snapshot.pendingRecords());
		Assertions.assertTrue(snapshot.sealed());
		Assertions.assertTrue(snapshot.disabled());
		Assertions.assertEquals(0, snapshot.failedCallbacks());
	}

	@Test
	void memberDiagnosticsInspectOnlyTheBoundedPrefix() {
		InternalTerminationGroup group = new InternalTerminationGroup(
				new AdmissionFence(), () -> { }, new LifecycleWorkers());
		List<InternalTerminationGroup.Member> members = new ArrayList<>();
		members.add(group.root());
		for (int index = 0; index < 20; index++)
			members.add(group.registerChild(group.root()));
		InternalTerminationGroup.Member beyondBound = members.get(20);
		group.signalFailure(beyondBound,
				new IllegalStateException("outside diagnostic prefix"));
		group.signalTerminated(beyondBound);

		InternalTerminationGroup.DiagnosticSummary summary =
				group.diagnosticSummary();

		Assertions.assertEquals(21, summary.memberCount());
		Assertions.assertTrue(summary.truncated());
		Assertions.assertEquals(0, summary.failedMembers());
		Assertions.assertEquals(0, summary.provenMembers());
	}

	@Test
	void selfInterruptedTransitionWorkerDrainsLaterAcceptedRecord()
			throws Exception {
		AtomicReference<Thread> workerThread = new AtomicReference<>();
		CountDownLatch firstDelivered = new CountDownLatch(1);
		CountDownLatch secondDelivered = new CountDownLatch(1);
		LifecycleWorkers workers = new LifecycleWorkers((name, task) -> {
			Thread worker = new Thread(task, name);
			worker.setDaemon(true);
			Assertions.assertTrue(workerThread.compareAndSet(null, worker));
			worker.start();
		});
		LifecycleTransitionDispatcher dispatcher =
				new LifecycleTransitionDispatcher(workers);

		dispatcher.dispatch(() -> {
			Thread.currentThread().interrupt();
			firstDelivered.countDown();
		});
		Assertions.assertTrue(firstDelivered.await(5, TimeUnit.SECONDS));
		Thread worker = requireNonNull(workerThread.get());
		awaitWaiting(worker);

		dispatcher.dispatch(secondDelivered::countDown);
		dispatcher.seal();

		Assertions.assertTrue(secondDelivered.await(5, TimeUnit.SECONDS));
		worker.join(TimeUnit.SECONDS.toMillis(5));
		Assertions.assertFalse(worker.isAlive());
		LifecycleTransitionSnapshot snapshot = dispatcher.snapshot();
		Assertions.assertEquals(2, snapshot.acceptedRecords());
		Assertions.assertEquals(0, snapshot.pendingRecords());
		Assertions.assertFalse(snapshot.callbackActive());
		Assertions.assertTrue(snapshot.sealed());
		Assertions.assertEquals(0, snapshot.failedCallbacks());
	}

	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	@EnabledForJreRange(min = JRE.JAVA_21)
	void realStandaloneMixedTransportLifecyclePreservesOrderAndSealsSnapshot()
			throws Exception {
		List<String> transitions = new CopyOnWriteArrayList<>();
		CountDownLatch transitionWorkerDone = new CountDownLatch(1);
		CountDownLatch startReturned = new CountDownLatch(1);
		TransitionRecordingObserver observer =
				new TransitionRecordingObserver(transitions);
		SokletConfig config = realMixedTransportConfig(observer);
		LifecycleRuntimeServices services =
				transitionTrackingServices(transitionWorkerDone);
		RecordingProcessAccess process = new RecordingProcessAccess();
		RecordingTriggerRegistry triggers = new RecordingTriggerRegistry();
		AtomicReference<SokletApplicationRuntime> runtime = new AtomicReference<>();
		SokletApplicationRuntimeFactory factory = (exactConfig, exactServices,
				exactPublisher) -> {
			SokletApplicationRuntime direct = DirectSokletApplicationRuntime.create(
					exactConfig, exactServices, exactPublisher);
			Assertions.assertTrue(runtime.compareAndSet(null, direct));
			return new StartSignalingRuntime(direct, startReturned);
		};
		SokletApplicationEnvironment environment = new SokletApplicationEnvironment(
				services, process, triggers, ignored -> { }, factory,
				(name, task) -> new Thread(task, name));
		SokletApplicationOptions options = SokletApplicationOptions.builder()
				.additionalTrigger(ShutdownTrigger.ENTER_KEY).build();
		RunnerCall runner = startRunner(config, options, environment);

		Assertions.assertTrue(startReturned.await(5, TimeUnit.SECONDS));
		triggers.trigger();
		Assertions.assertTrue(runner.done.await(5, TimeUnit.SECONDS));
		Assertions.assertNull(runner.failure.get());
		Assertions.assertTrue(requireNonNull(runner.result.get()).isComplete());
		Assertions.assertTrue(transitionWorkerDone.await(5, TimeUnit.SECONDS));
		Assertions.assertEquals(List.of(
				"will-start-soklet",
				"will-start-http",
				"did-start-http",
				"will-start-sse",
				"did-start-sse",
				"will-start-mcp",
				"did-start-mcp",
				"did-start-soklet",
				"will-stop-soklet",
				"will-stop-http",
				"will-stop-sse",
				"will-stop-mcp",
				"did-stop-http",
				"did-stop-sse",
				"did-stop-mcp-GRACEFUL_TERMINATION",
				"did-stop-soklet"), transitions);
		LifecycleTransitionSnapshot snapshot = requireNonNull(runtime.get())
				.diagnostics().transitionSnapshot();
		Assertions.assertEquals(16, snapshot.acceptedRecords());
		Assertions.assertEquals(0, snapshot.pendingRecords());
		Assertions.assertFalse(snapshot.callbackActive());
		Assertions.assertTrue(snapshot.sealed());
		Assertions.assertFalse(snapshot.disabled());
		Assertions.assertEquals(0, snapshot.failedCallbacks());
		Assertions.assertEquals(1, services.workers().created(
				LifecycleWorkers.Role.TRANSITION_OBSERVER));
		Assertions.assertEquals(0, services.workers().active(
				LifecycleWorkers.Role.TRANSITION_OBSERVER));
	}

	@Test
	void mixedIncompleteAndNotStartedTerminalTraceIsOrderedAndComplete()
			throws Exception {
		CountDownLatch attachEntered = new CountDownLatch(1);
		CountDownLatch releaseAttach = new CountDownLatch(1);
		CountDownLatch attachReturned = new CountDownLatch(1);
		CountDownLatch attachWorkerDone = new CountDownLatch(1);
		CountDownLatch transitionWorkerDone = new CountDownLatch(1);
		BlockingAttachHttpEndpoint http = new BlockingAttachHttpEndpoint(
				attachEntered, releaseAttach, attachReturned);
		List<String> transitions = new CopyOnWriteArrayList<>();
		List<ShutdownResult> terminalResults =
				new CopyOnWriteArrayList<>();
		List<ParticipantShutdownResult> participantResults =
				new CopyOnWriteArrayList<>();
		AtomicReference<ShutdownResult> terminalSokletResult =
				new AtomicReference<>();
		LifecycleObserver observer = new LifecycleObserver() {
			private void participant(@NonNull String transition,
					@NonNull ParticipantShutdownResult result) {
				transitions.add(transition);
				participantResults.add(result);
			}

			@Override public void willStartSoklet(@NonNull Soklet soklet) {
				transitions.add("will-start-soklet");
			}

			@Override public void didFailToStartSoklet(@NonNull Soklet soklet,
					@NonNull Throwable throwable) {
				transitions.add("did-fail-start-soklet");
			}

			@Override public void willStopSoklet(@NonNull Soklet soklet) {
				transitions.add("will-stop-soklet");
			}

			@Override public void willStopHttpServer(@NonNull HttpServer server) {
				transitions.add("will-stop-http");
			}

			@Override public void willStopSseServer(@NonNull SseServer server) {
				transitions.add("will-stop-sse");
			}

			@Override public void willStopMcpServer(@NonNull McpServer server) {
				transitions.add("will-stop-mcp");
			}

			@Override public void didStopHttpServer(@NonNull HttpServer server,
					@NonNull ParticipantShutdownResult result) {
				participant("did-stop-http", result);
			}

			@Override public void didStopSseServer(@NonNull SseServer server,
					@NonNull ParticipantShutdownResult result) {
				participant("did-stop-sse", result);
			}

			@Override public void didStopMcpServer(@NonNull McpServer server,
					@NonNull ParticipantShutdownResult result) {
				participant("did-stop-mcp", result);
			}

			@Override public void didStopSoklet(@NonNull Soklet soklet,
					@NonNull ShutdownResult result) {
				terminalSokletResult.set(result);
				terminalResults.add(result);
				transitions.add("did-stop-soklet");
			}
		};
		McpEndpoint mcpEndpoint = McpEndpoint.withPath("/mcp")
				.serverInformation(McpImplementation.withNameAndVersion(
						"mixed-observation-test", "4.0.0-SNAPSHOT").build())
				.build();
		SokletConfig config = SokletConfig.withHttpServer(http)
				.sseServer(SseServer.withPort(0).build())
				.mcpServer(McpServer.withPort(0)
						.endpointRegistry(McpEndpointRegistry.fromEndpoints(
								List.of(mcpEndpoint)))
						.admissionController(
								McpAdmissionController.acceptAllInstance())
						.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
						.build())
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(
						ObservationResource.class, ObservationSseResource.class)))
				.lifecycleObserver(observer)
				.internalLifecyclePolicy(immediateShutdownPolicy())
				.build();
		LifecycleWorkers workers = new LifecycleWorkers((name, task) -> {
			Thread worker = new Thread(() -> {
				try {
					task.run();
				} finally {
					if (name.equals("soklet-attach-http"))
						attachWorkerDone.countDown();
					if (name.equals("soklet-lifecycle-observer"))
						transitionWorkerDone.countDown();
				}
			}, name);
			worker.setDaemon(true);
			worker.start();
		});
		LifecycleRuntimeServices services = new LifecycleRuntimeServices(
				NanoClock.system(), workers);
		RecordingTriggerRegistry triggers = new RecordingTriggerRegistry();
		AtomicReference<SokletApplicationRuntime> runtime = new AtomicReference<>();
		SokletApplicationRuntimeFactory factory = (exactConfig, exactServices,
				exactPublisher) -> {
			SokletApplicationRuntime direct = DirectSokletApplicationRuntime.create(
					exactConfig, exactServices, exactPublisher);
			runtime.set(direct);
			return direct;
		};
		SokletApplicationEnvironment environment = new SokletApplicationEnvironment(
				services, new RecordingProcessAccess(), triggers, ignored -> { },
				factory, (name, task) -> new Thread(task, name));
		RunnerCall runner = startRunner(config, SokletApplicationOptions.builder()
				.additionalTrigger(ShutdownTrigger.ENTER_KEY).build(), environment);

		try {
			Assertions.assertTrue(attachEntered.await(5, TimeUnit.SECONDS));
			triggers.trigger();
			Assertions.assertTrue(runner.done.await(5, TimeUnit.SECONDS));
			ShutdownIncompleteException failure = Assertions.assertInstanceOf(
					ShutdownIncompleteException.class, runner.failure.get());
			InternalShutdownResult result = failure.getInternalShutdownResult();
			ShutdownResult publicResult = failure.getShutdownResult();
			Assertions.assertSame(result, publicResult.internalResult());
			Assertions.assertNull(runner.result.get());
			Assertions.assertTrue(transitionWorkerDone.await(5, TimeUnit.SECONDS));

			Assertions.assertEquals(InternalParticipantShutdownDisposition
						.TERMINATION_UNKNOWN,
					result.participantResult(InternalParticipantKind.HTTP)
							.orElseThrow().disposition());
			Assertions.assertTrue(result.participantResult(
					InternalParticipantKind.HTTP).orElseThrow().residualActivity()
					.contains(InternalResidualActivityKind.LIFECYCLE_CALL));
			Assertions.assertEquals(InternalParticipantShutdownDisposition.NOT_STARTED,
					result.participantResult(InternalParticipantKind.SSE)
							.orElseThrow().disposition());
			Assertions.assertEquals(InternalParticipantShutdownDisposition.NOT_STARTED,
					result.participantResult(InternalParticipantKind.MCP)
							.orElseThrow().disposition());
			Assertions.assertEquals(List.of(
					"will-start-soklet",
					"did-fail-start-soklet",
					"will-stop-soklet",
					"will-stop-http",
					"will-stop-sse",
					"will-stop-mcp",
					"did-stop-http",
					"did-stop-sse",
					"did-stop-mcp",
					"did-stop-soklet"), transitions);
			Assertions.assertEquals(List.of(publicResult), terminalResults);
			Assertions.assertSame(publicResult, terminalSokletResult.get());
			Assertions.assertEquals(3, participantResults.size());
			for (ParticipantShutdownResult participant : participantResults)
				Assertions.assertSame(participant, publicResult.getParticipantResult(
						participant.getParticipantKind()).orElseThrow());
			ParticipantShutdownResult publicHttp = publicResult
					.getParticipantResult(ParticipantKind.HTTP).orElseThrow();
			Assertions.assertEquals(
					ParticipantShutdownDisposition.TERMINATION_UNKNOWN,
					publicHttp.getDisposition());
			Assertions.assertTrue(publicHttp.getResidualActivityEvidence()
					.orElseThrow().getActivityKinds()
					.contains(ResidualActivityKind.LIFECYCLE_CALL));
			Assertions.assertEquals(ParticipantShutdownDisposition.NOT_STARTED,
					publicResult.getParticipantResult(ParticipantKind.SSE).orElseThrow()
							.getDisposition());
			Assertions.assertEquals(ParticipantShutdownDisposition.NOT_STARTED,
					publicResult.getParticipantResult(ParticipantKind.MCP).orElseThrow()
							.getDisposition());
			LifecycleTransitionSnapshot snapshot = requireNonNull(runtime.get())
					.diagnostics().transitionSnapshot();
			Assertions.assertEquals(10, snapshot.acceptedRecords());
			Assertions.assertEquals(0, snapshot.pendingRecords());
			Assertions.assertFalse(snapshot.callbackActive());
			Assertions.assertTrue(snapshot.sealed());
			Assertions.assertEquals(0, snapshot.failedCallbacks());
		} finally {
			releaseAttach.countDown();
			Assertions.assertTrue(attachReturned.await(5, TimeUnit.SECONDS));
			Assertions.assertTrue(attachWorkerDone.await(5, TimeUnit.SECONDS));
		}
	}

	@Test
	void throwingHotPathStartCallbackDoesNotSuppressItsFinishPair()
			throws Exception {
		int httpPort = findFreePort();
		IllegalStateException startFailure = new IllegalStateException(
				"hot-path start callback failed");
		List<String> callbacks = new CopyOnWriteArrayList<>();
		AtomicReference<Thread> startThread = new AtomicReference<>();
		AtomicReference<Thread> logThread = new AtomicReference<>();
		AtomicReference<Thread> finishThread = new AtomicReference<>();
		AtomicReference<LogEvent> failureLog = new AtomicReference<>();
		AtomicReference<List<Throwable>> finishThrowables = new AtomicReference<>();
		CountDownLatch finishDelivered = new CountDownLatch(1);
		LifecycleObserver observer = new LifecycleObserver() {
			@Override public void didStartRequestHandling(
					@NonNull ServerType serverType, @NonNull Request request,
					ResourceMethod resourceMethod) {
				callbacks.add("start");
				startThread.set(Thread.currentThread());
				throw startFailure;
			}

			@Override public void didReceiveLogEvent(@NonNull LogEvent logEvent) {
				if (logEvent.getLogEventType() == LogEventType
						.LIFECYCLE_OBSERVER_DID_START_REQUEST_HANDLING_FAILED) {
					callbacks.add("log");
					logThread.set(Thread.currentThread());
					failureLog.set(logEvent);
				}
			}

			@Override public void didFinishRequestHandling(
					@NonNull ServerType serverType, @NonNull Request request,
					ResourceMethod resourceMethod,
					@NonNull MarshaledResponse marshaledResponse,
					@NonNull Duration duration,
					@NonNull List<@NonNull Throwable> throwables) {
				callbacks.add("finish");
				finishThread.set(Thread.currentThread());
				finishThrowables.set(throwables);
				finishDelivered.countDown();
			}
		};
		LifecycleRuntimeServices services = LifecycleRuntimeServices.system();
		HotPathObservationResource.handlerThread.set(null);
		Soklet soklet = Soklet.fromConfig(SokletConfig
				.withHttpServer(HttpServer.withPort(httpPort)
						.host("127.0.0.1").build())
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(
						Set.of(HotPathObservationResource.class)))
				.lifecycleObserver(observer)
				.build(), services, ignored -> { });

		try {
			soklet.start();
			int acceptedBeforeRequest = soklet.getDirectLifecycle()
					.transitionSnapshot().acceptedRecords();
			HttpURLConnection connection = (HttpURLConnection) URI.create(
					"http://127.0.0.1:" + httpPort + "/observation")
					.toURL().openConnection();
			try {
				connection.setConnectTimeout(5_000);
				connection.setReadTimeout(5_000);
				Assertions.assertEquals(200, connection.getResponseCode());
				Assertions.assertEquals("ok", new String(
						connection.getInputStream().readAllBytes(),
						StandardCharsets.UTF_8));
			} finally {
				connection.disconnect();
			}

			Assertions.assertTrue(finishDelivered.await(5, TimeUnit.SECONDS));
			Assertions.assertEquals(List.of("start", "log", "finish"), callbacks);
			Assertions.assertSame(startFailure,
					requireNonNull(failureLog.get()).getThrowable().orElseThrow());
			Assertions.assertTrue(requireNonNull(finishThrowables.get())
					.contains(startFailure));
			Assertions.assertSame(startThread.get(), logThread.get());
			Assertions.assertSame(startThread.get(), finishThread.get());
			Assertions.assertSame(startThread.get(),
					HotPathObservationResource.handlerThread.get());
			Assertions.assertEquals(acceptedBeforeRequest,
					soklet.getDirectLifecycle().transitionSnapshot().acceptedRecords(),
					"Hot-path callbacks and their failure log must not enter the "
							+ "transition queue");

			soklet.getDirectLifecycle().shutdown();
			Assertions.assertTrue(soklet.getDirectLifecycle().awaitCompletion()
					.isComplete());
		} finally {
			soklet.getDirectLifecycle().shutdown();
			soklet.getDirectLifecycle().awaitCompletion();
		}
	}

	@Test
	@Timeout(120)
	void transportLogDuringAttachIsInlineNonqueuedAndTracked()
			throws Exception {
		LogEvent exactLog = LogEvent.with(LogEventType.SERVER_TRANSPORT_FAILURE,
				"custom HTTP attachment diagnostic").build();
		CountDownLatch transitionEntered = new CountDownLatch(1);
		CountDownLatch releaseTransition = new CountDownLatch(1);
		CountDownLatch transitionWorkerDone = new CountDownLatch(1);
		CountDownLatch logDelivered = new CountDownLatch(1);
		AtomicReference<Object> ownerToken = new AtomicReference<>();
		AtomicReference<Thread> transitionThread = new AtomicReference<>();
		AtomicReference<Thread> logThread = new AtomicReference<>();
		AtomicReference<Boolean> logWasTracked = new AtomicReference<>();
		List<LogEvent> logs = new CopyOnWriteArrayList<>();
		LifecycleObserver observer = new LifecycleObserver() {
			@Override public void willStartSoklet(@NonNull Soklet soklet) {
				transitionThread.set(Thread.currentThread());
				transitionEntered.countDown();
				awaitUninterruptibly(releaseTransition);
			}

			@Override public void didReceiveLogEvent(@NonNull LogEvent logEvent) {
				logs.add(logEvent);
				logThread.set(Thread.currentThread());
				logWasTracked.set(LifecycleExecutionContext.isMarked(
						requireNonNull(ownerToken.get())));
				logDelivered.countDown();
			}
		};
		LoggingAttachHttpServer http = new LoggingAttachHttpServer(exactLog);
		LifecycleRuntimeServices services =
				transitionTrackingServices(transitionWorkerDone);
		Soklet soklet = Soklet.fromConfig(SokletConfig.withHttpServer(http)
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(
						Set.of(ObservationResource.class)))
				.lifecycleObserver(observer)
				.build(), services, ignored -> { });
		ownerToken.set(soklet.getDirectLifecycle().executionOwnerToken());
		AtomicReference<Throwable> startFailure = new AtomicReference<>();
		CountDownLatch startDone = new CountDownLatch(1);
		Thread starter = new Thread(() -> {
			try {
				soklet.start();
			} catch (Throwable failure) {
				startFailure.set(failure);
			} finally {
				startDone.countDown();
			}
		}, "tracked-log-soklet-starter");
		starter.setDaemon(true);
		starter.start();

		try {
			Assertions.assertTrue(transitionEntered.await(5, TimeUnit.SECONDS));
			Assertions.assertTrue(http.attachEntered.await(5, TimeUnit.SECONDS));
			Assertions.assertTrue(logDelivered.await(5, TimeUnit.SECONDS),
					"The transport log was queued behind the blocked transition");
			Assertions.assertEquals(List.of(exactLog), logs);
			Assertions.assertSame(http.attachThread.get(), logThread.get());
			Assertions.assertNotSame(transitionThread.get(), logThread.get());
			Assertions.assertEquals("soklet-attach-http",
					requireNonNull(logThread.get()).getName());
			Assertions.assertEquals(Boolean.TRUE, logWasTracked.get(),
					"The inline log callback must remain inside the tracked "
							+ "attachment call");
			LifecycleTransitionSnapshot blocked = soklet.getDirectLifecycle()
					.transitionSnapshot();
			Assertions.assertEquals(1, blocked.acceptedRecords());
			Assertions.assertEquals(0, blocked.pendingRecords());
			Assertions.assertTrue(blocked.callbackActive());
			Assertions.assertFalse(blocked.sealed());

			http.releaseAttach.countDown();
			Assertions.assertTrue(startDone.await(5, TimeUnit.SECONDS));
			Assertions.assertNull(startFailure.get());
			soklet.getDirectLifecycle().shutdown();
			InternalShutdownResult result =
					soklet.getDirectLifecycle().awaitCompletion();
			Assertions.assertTrue(result.isComplete());
			LifecycleTransitionSnapshot terminal = soklet.getDirectLifecycle()
					.transitionSnapshot();
			Assertions.assertEquals(8, terminal.acceptedRecords());
			Assertions.assertEquals(7, terminal.pendingRecords());
			Assertions.assertTrue(terminal.callbackActive());
			Assertions.assertTrue(terminal.sealed());
			Assertions.assertEquals(0, terminal.failedCallbacks());
		} finally {
			http.releaseAttach.countDown();
			soklet.getDirectLifecycle().shutdown();
			releaseTransition.countDown();
			soklet.getDirectLifecycle().awaitCompletion();
			Assertions.assertTrue(startDone.await(5, TimeUnit.SECONDS));
			Assertions.assertTrue(transitionWorkerDone.await(5, TimeUnit.SECONDS));
		}
	}

	@Test
	void coordinatorLifecycleFactsNeverUseLogCallback() throws Exception {
		IllegalStateException observerFailure = new IllegalStateException(
				"expected transition observer failure");
		List<LogEvent> logEvents = new CopyOnWriteArrayList<>();
		CountDownLatch transitionWorkerDone = new CountDownLatch(1);
		LifecycleObserver observer = new LifecycleObserver() {
			@Override
			public void willStartSoklet(@NonNull Soklet soklet) {
				throw observerFailure;
			}

			@Override
			public void didReceiveLogEvent(@NonNull LogEvent logEvent) {
				logEvents.add(logEvent);
			}
		};
		LifecycleRuntimeServices services =
				transitionTrackingServices(transitionWorkerDone);
		Soklet soklet = Soklet.fromConfig(SokletConfig
				.withHttpServer(HttpServer.withPort(0).build())
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(
						Set.of(ObservationResource.class)))
				.lifecycleObserver(observer)
				.build(), services, ignored -> { });

		try {
			soklet.start();
			soklet.getDirectLifecycle().shutdown();
			InternalShutdownResult result =
					soklet.getDirectLifecycle().awaitCompletion();
			Assertions.assertTrue(result.isComplete());
			Assertions.assertTrue(transitionWorkerDone.await(5, TimeUnit.SECONDS));
			LifecycleTransitionSnapshot snapshot =
					soklet.getDirectLifecycle().transitionSnapshot();
			Assertions.assertEquals(1, snapshot.failedCallbacks());
			Assertions.assertEquals(IllegalStateException.class.getName(),
					snapshot.firstFailureSummary().orElseThrow());
			Assertions.assertFalse(snapshot.firstFailureSummary().orElseThrow()
					.contains("expected transition observer failure"));
			Assertions.assertTrue(logEvents.isEmpty(), logEvents::toString);
		} finally {
			soklet.getDirectLifecycle().shutdown();
			soklet.getDirectLifecycle().awaitCompletion();
		}
	}

	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	void standaloneCleanupNeverClosesStatefulObserver() throws Exception {
		CloseTrackingObserver observer = new CloseTrackingObserver();
		CountDownLatch transitionWorkerDone = new CountDownLatch(1);
		CountDownLatch startReturned = new CountDownLatch(1);
		LifecycleRuntimeServices services =
				transitionTrackingServices(transitionWorkerDone);
		RecordingTriggerRegistry triggers = new RecordingTriggerRegistry();
		AtomicInteger cleanupCalls = new AtomicInteger();
		SokletConfig config = SokletConfig
				.withHttpServer(HttpServer.withPort(0).build())
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(
						Set.of(ObservationResource.class)))
				.lifecycleObserver(observer)
				.build();
		SokletApplicationOptions options = SokletApplicationOptions.builder()
				.additionalTrigger(ShutdownTrigger.ENTER_KEY)
				.afterCompleteShutdown(Duration.ofSeconds(2), result -> {
					if (observer.closeCalls.get() != 0)
						throw new AssertionError("Observer was closed before cleanup");
					cleanupCalls.incrementAndGet();
				}).build();
		SokletApplicationRuntimeFactory factory = (exactConfig, exactServices,
				exactPublisher) -> new StartSignalingRuntime(
					DirectSokletApplicationRuntime.create(exactConfig, exactServices,
							exactPublisher), startReturned);
		SokletApplicationEnvironment environment = new SokletApplicationEnvironment(
				services, new RecordingProcessAccess(), triggers, ignored -> { },
				factory, (name, task) -> new Thread(task, name));
		RunnerCall runner = startRunner(config, options, environment);

		Assertions.assertTrue(startReturned.await(5, TimeUnit.SECONDS));
		triggers.trigger();
		Assertions.assertTrue(runner.done.await(5, TimeUnit.SECONDS));
		Assertions.assertNull(runner.failure.get());
		Assertions.assertTrue(requireNonNull(runner.result.get()).isComplete());
		Assertions.assertTrue(transitionWorkerDone.await(5, TimeUnit.SECONDS));
		Assertions.assertEquals(1, cleanupCalls.get());
		Assertions.assertEquals(List.of("started", "stopped"), observer.state);
		Assertions.assertEquals(0, observer.closeCalls.get());
	}

	@Test
	@Timeout(120)
	void blockedTransitionCannotDelayRunnerCleanupOrTerminalReport()
			throws Exception {
		int httpPort = findFreePort();
		CountDownLatch observerEntered = new CountDownLatch(1);
		CountDownLatch releaseObserver = new CountDownLatch(1);
		CountDownLatch terminalCallback = new CountDownLatch(1);
		CountDownLatch peerTerminalCallback = new CountDownLatch(1);
		CountDownLatch startReturned = new CountDownLatch(1);
		CountDownLatch reportReceived = new CountDownLatch(1);
		CountDownLatch requestStarted = new CountDownLatch(1);
		CountDownLatch requestFinished = new CountDownLatch(1);
		List<String> transitions = new CopyOnWriteArrayList<>();
		AtomicReference<ShutdownResult> terminalCallbackResult =
				new AtomicReference<>();
		AtomicReference<Thread> requestStartThread = new AtomicReference<>();
		AtomicReference<Thread> requestFinishThread = new AtomicReference<>();
		AtomicReference<ServerType> requestStartServer = new AtomicReference<>();
		AtomicReference<ServerType> requestFinishServer = new AtomicReference<>();
		AtomicInteger peerCallbacks = new AtomicInteger();
		HotPathObservationResource.handlerThread.set(null);
		LifecycleObserver blockingObserver = new LifecycleObserver() {
			@Override
			public void willStartSoklet(@NonNull Soklet soklet) {
				transitions.add("will-start-soklet");
				observerEntered.countDown();
				awaitUninterruptibly(releaseObserver);
			}

			@Override
			public void willStartHttpServer(@NonNull HttpServer httpServer) {
				transitions.add("will-start-http");
			}

			@Override
			public void didStartHttpServer(@NonNull HttpServer httpServer) {
				transitions.add("did-start-http");
			}

			@Override
			public void didStartSoklet(@NonNull Soklet soklet) {
				transitions.add("did-start-soklet");
			}

			@Override
			public void didStartRequestHandling(@NonNull ServerType serverType,
					@NonNull Request request, ResourceMethod resourceMethod) {
				requestStartServer.set(serverType);
				requestStartThread.set(Thread.currentThread());
				requestStarted.countDown();
			}

			@Override
			public void didFinishRequestHandling(@NonNull ServerType serverType,
					@NonNull Request request, ResourceMethod resourceMethod,
					@NonNull MarshaledResponse marshaledResponse,
					@NonNull Duration duration,
					@NonNull List<@NonNull Throwable> throwables) {
				requestFinishServer.set(serverType);
				requestFinishThread.set(Thread.currentThread());
				requestFinished.countDown();
			}

			@Override
			public void willStopSoklet(@NonNull Soklet soklet) {
				transitions.add("will-stop-soklet");
			}

			@Override
			public void willStopHttpServer(@NonNull HttpServer httpServer) {
				transitions.add("will-stop-http");
			}

			@Override
			public void didStopHttpServer(@NonNull HttpServer httpServer,
					@NonNull ParticipantShutdownResult result) {
				transitions.add("did-stop-http");
			}

			@Override
			public void didStopSoklet(@NonNull Soklet soklet,
					@NonNull ShutdownResult result) {
				transitions.add("did-stop-soklet");
				terminalCallbackResult.set(result);
				terminalCallback.countDown();
			}
		};
		LifecycleObserver peerObserver = new LifecycleObserver() {
			@Override public void willStartSoklet(@NonNull Soklet soklet) {
				peerCallbacks.incrementAndGet();
			}
			@Override public void willStartHttpServer(@NonNull HttpServer server) {
				peerCallbacks.incrementAndGet();
			}
			@Override public void didStartHttpServer(@NonNull HttpServer server) {
				peerCallbacks.incrementAndGet();
			}
			@Override public void didStartSoklet(@NonNull Soklet soklet) {
				peerCallbacks.incrementAndGet();
			}
			@Override public void willStopSoklet(@NonNull Soklet soklet) {
				peerCallbacks.incrementAndGet();
			}
			@Override public void willStopHttpServer(@NonNull HttpServer server) {
				peerCallbacks.incrementAndGet();
			}
			@Override public void didStopHttpServer(@NonNull HttpServer server,
					@NonNull ParticipantShutdownResult result) {
				peerCallbacks.incrementAndGet();
			}
			@Override public void didStopSoklet(@NonNull Soklet soklet,
					@NonNull ShutdownResult result) {
				peerCallbacks.incrementAndGet();
				peerTerminalCallback.countDown();
			}
		};
		SokletConfig config = SokletConfig
				.withHttpServer(HttpServer.withPort(httpPort)
						.host("127.0.0.1").build())
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(
						Set.of(HotPathObservationResource.class)))
				.lifecycleObservers(List.of(blockingObserver, peerObserver))
				.build();
		LifecycleRuntimeServices services = LifecycleRuntimeServices.system();
		RecordingProcessAccess process = new RecordingProcessAccess();
		RecordingTriggerRegistry triggers = new RecordingTriggerRegistry();
		AtomicInteger cleanupCalls = new AtomicInteger();
		AtomicReference<ShutdownResult> cleanupResult =
				new AtomicReference<>();
		AtomicReference<SokletApplicationTerminalSnapshot> report =
				new AtomicReference<>();
		SokletApplicationOptions options = SokletApplicationOptions.builder()
				.additionalTrigger(ShutdownTrigger.ENTER_KEY)
				.afterCompleteShutdown(Duration.ofSeconds(2), result -> {
					cleanupResult.set(result);
					cleanupCalls.incrementAndGet();
				}).build();
		SokletApplicationRuntimeFactory factory = (exactConfig, exactServices,
				exactPublisher) -> new StartSignalingRuntime(
					DirectSokletApplicationRuntime.create(exactConfig, exactServices,
							exactPublisher), startReturned);
		SokletApplicationEnvironment environment = new SokletApplicationEnvironment(
				services, process, triggers, snapshot -> {
				report.set(snapshot);
				reportReceived.countDown();
			}, factory, (name, task) -> new Thread(task, name));
		RunnerCall runner = startRunner(config, options, environment);

		try {
			Assertions.assertTrue(observerEntered.await(5, TimeUnit.SECONDS));
			Assertions.assertTrue(startReturned.await(5, TimeUnit.SECONDS));
			HttpURLConnection connection = (HttpURLConnection) URI.create(
					"http://127.0.0.1:" + httpPort + "/observation")
					.toURL().openConnection();
			try {
				connection.setConnectTimeout(5_000);
				connection.setReadTimeout(5_000);
				connection.setRequestProperty("Accept", "text/plain");
				Assertions.assertEquals(200, connection.getResponseCode());
				Assertions.assertEquals("ok", new String(
						connection.getInputStream().readAllBytes(),
						StandardCharsets.UTF_8));
			} finally {
				connection.disconnect();
			}
			Assertions.assertTrue(requestStarted.await(5, TimeUnit.SECONDS));
			Assertions.assertTrue(requestFinished.await(5, TimeUnit.SECONDS));
			Assertions.assertEquals(ServerType.STANDARD_HTTP,
					requestStartServer.get());
			Assertions.assertEquals(ServerType.STANDARD_HTTP,
					requestFinishServer.get());
			Assertions.assertSame(requestStartThread.get(),
					requestFinishThread.get());
			Assertions.assertSame(HotPathObservationResource.handlerThread.get(),
					requestStartThread.get());
			Assertions.assertNotEquals("soklet-lifecycle-observer",
					requireNonNull(requestStartThread.get()).getName());
			Assertions.assertEquals(1L, releaseObserver.getCount());
			triggers.trigger();
			Assertions.assertTrue(reportReceived.await(5, TimeUnit.SECONDS));
			Assertions.assertTrue(runner.done.await(5, TimeUnit.SECONDS));
			Assertions.assertNull(runner.failure.get());
			InternalShutdownResult result = requireNonNull(runner.result.get());
			Assertions.assertTrue(result.isComplete());
			Assertions.assertSame(result, cleanupResult.get().internalResult());
			Assertions.assertEquals(1, cleanupCalls.get());
			LifecycleTransitionSnapshot observerSnapshot = requireNonNull(report.get())
					.coreDiagnostics().transitionSnapshot();
			Assertions.assertEquals(8, observerSnapshot.acceptedRecords());
			Assertions.assertEquals(7, observerSnapshot.pendingRecords());
			Assertions.assertTrue(observerSnapshot.callbackActive());
			Assertions.assertTrue(observerSnapshot.sealed());
			Assertions.assertEquals(0, observerSnapshot.failedCallbacks());
			Assertions.assertEquals(1, services.workers().created(
					LifecycleWorkers.Role.TRANSITION_OBSERVER));
			Assertions.assertEquals(1, services.workers().active(
					LifecycleWorkers.Role.TRANSITION_OBSERVER));
			Assertions.assertEquals(1, process.addCalls.get());
			Assertions.assertEquals(1, process.removeCalls.get());
			Assertions.assertEquals(1, triggers.unregisterCalls.get());
		} finally {
			releaseObserver.countDown();
		}

		Assertions.assertTrue(terminalCallback.await(5, TimeUnit.SECONDS));
		Assertions.assertTrue(peerTerminalCallback.await(5, TimeUnit.SECONDS));
		Assertions.assertSame(runner.result.get(),
				terminalCallbackResult.get().internalResult());
		Assertions.assertEquals(List.of("will-start-soklet", "will-start-http",
				"did-start-http", "did-start-soklet", "will-stop-soklet",
				"will-stop-http", "did-stop-http", "did-stop-soklet"),
				transitions);
		Assertions.assertEquals(8, peerCallbacks.get());
	}

	@Test
	void defaultObserverCreatesNoTransitionWorker() throws Exception {
		LifecycleRuntimeServices services = LifecycleRuntimeServices.system();
		Soklet soklet = Soklet.fromConfig(SokletConfig
				.withHttpServer(HttpServer.withPort(0).build())
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(
						Set.of(ObservationResource.class)))
				.build(), services, ignored -> { });

		soklet.start();
		soklet.getDirectLifecycle().shutdown();
		InternalShutdownResult result = soklet.getDirectLifecycle().awaitCompletion();

		Assertions.assertTrue(result.isComplete());
		Assertions.assertEquals(0, services.workers().created(
				LifecycleWorkers.Role.TRANSITION_OBSERVER));
	}

	@NonNull
	private static InternalLifecyclePolicy immediateShutdownPolicy() {
		return new InternalLifecyclePolicy(Optional.empty(), Duration.ZERO,
				Duration.ZERO, Duration.ZERO);
	}

	private static void awaitWaiting(@NonNull Thread worker) {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		for (;;) {
			Thread.State state = requireNonNull(worker).getState();
			if (state == Thread.State.WAITING)
				return;
			if (state == Thread.State.TERMINATED)
				Assertions.fail("Self-interrupted transition worker terminated");
			if (System.nanoTime() - deadline >= 0L)
				Assertions.fail("Transition worker did not return to its monitor wait");
			Thread.onSpinWait();
		}
	}

	@NonNull
	private static LifecycleRuntimeServices transitionTrackingServices(
			@NonNull CountDownLatch transitionWorkerDone) {
		LifecycleWorkers workers = new LifecycleWorkers((name, task) -> {
			Thread worker = new Thread(() -> {
				try {
					task.run();
				} finally {
					if (name.equals("soklet-lifecycle-observer"))
						transitionWorkerDone.countDown();
				}
			}, name);
			worker.setDaemon(true);
			worker.start();
		});
		return new LifecycleRuntimeServices(NanoClock.system(), workers);
	}

	@NonNull
	private static SokletConfig realMixedTransportConfig(
			@NonNull LifecycleObserver observer) {
		McpEndpoint endpoint = McpEndpoint.withPath("/mcp")
				.serverInformation(McpImplementation.withNameAndVersion(
						"observation-test", "4.0.0-SNAPSHOT").build())
				.build();
		McpServer mcpServer = McpServer.withPort(0)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.admissionController(McpAdmissionController.acceptAllInstance())
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.build();
		return SokletConfig.withHttpServer(HttpServer.withPort(0).build())
				.sseServer(SseServer.withPort(0).build())
				.mcpServer(mcpServer)
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(
						Set.of(ObservationResource.class,
								ObservationSseResource.class)))
				.lifecycleObserver(observer)
				.build();
	}

	@NonNull
	private static RunnerCall startRunner(@NonNull SokletConfig config,
			@NonNull SokletApplicationOptions options,
			@NonNull SokletApplicationEnvironment environment) {
		RunnerCall call = new RunnerCall();
		Thread thread = new Thread(() -> {
			try {
				call.result.set(SokletApplication.run(config, options, environment));
			} catch (Throwable failure) {
				call.failure.set(failure);
			} finally {
				call.done.countDown();
			}
		}, "soklet-application-observation-runner");
		thread.setDaemon(true);
		thread.start();
		return call;
	}

	private static void awaitUninterruptibly(@NonNull CountDownLatch latch) {
		boolean interrupted = false;
		for (;;) {
			try {
				requireNonNull(latch).await();
				break;
			} catch (InterruptedException ignored) {
				interrupted = true;
			}
		}
		if (interrupted)
			Thread.currentThread().interrupt();
	}

	private static final class RunnerCall {
		@NonNull private final AtomicReference<InternalShutdownResult> result =
				new AtomicReference<>();
		@NonNull private final AtomicReference<Throwable> failure =
				new AtomicReference<>();
		@NonNull private final CountDownLatch done = new CountDownLatch(1);
	}

	private static final class StartSignalingRuntime
			implements SokletApplicationRuntime {
		@NonNull private final SokletApplicationRuntime delegate;
		@NonNull private final CountDownLatch startReturned;

		private StartSignalingRuntime(@NonNull SokletApplicationRuntime delegate,
				@NonNull CountDownLatch startReturned) {
			this.delegate = requireNonNull(delegate);
			this.startReturned = requireNonNull(startReturned);
		}

		@Override public void start() {
			this.delegate.start();
			this.startReturned.countDown();
		}

		@Override public void shutdown() {
			this.delegate.shutdown();
		}

		@Override @NonNull public InternalLifecycleCoreSnapshot awaitCore()
				throws InterruptedException {
			return this.delegate.awaitCore();
		}

		@Override @NonNull public Optional<RuntimeException> terminalFailure(
				@NonNull InternalShutdownResult result) {
			return this.delegate.terminalFailure(result);
		}

		@Override @NonNull public SokletApplicationCoreDiagnostics diagnostics() {
			return this.delegate.diagnostics();
		}
	}

	private static final class TransitionRecordingObserver
			implements LifecycleObserver {
		@NonNull private final List<String> transitions;

		private TransitionRecordingObserver(
				@NonNull List<String> transitions) {
			this.transitions = requireNonNull(transitions);
		}

		@Override
		public void willStartSoklet(@NonNull Soklet soklet) {
			this.transitions.add("will-start-soklet");
		}

		@Override
		public void willStartHttpServer(@NonNull HttpServer httpServer) {
			this.transitions.add("will-start-http");
		}

		@Override
		public void didStartHttpServer(@NonNull HttpServer httpServer) {
			this.transitions.add("did-start-http");
		}

		@Override
		public void willStartSseServer(@NonNull SseServer sseServer) {
			this.transitions.add("will-start-sse");
		}

		@Override
		public void didStartSseServer(@NonNull SseServer sseServer) {
			this.transitions.add("did-start-sse");
		}

		@Override
		public void willStartMcpServer(@NonNull McpServer mcpServer) {
			this.transitions.add("will-start-mcp");
		}

		@Override
		public void didStartMcpServer(@NonNull McpServer mcpServer) {
			this.transitions.add("did-start-mcp");
		}

		@Override
		public void didStartSoklet(@NonNull Soklet soklet) {
			this.transitions.add("did-start-soklet");
		}

		@Override
		public void willStopSoklet(@NonNull Soklet soklet) {
			this.transitions.add("will-stop-soklet");
		}

		@Override
		public void willStopHttpServer(@NonNull HttpServer httpServer) {
			this.transitions.add("will-stop-http");
		}

		@Override
		public void willStopSseServer(@NonNull SseServer sseServer) {
			this.transitions.add("will-stop-sse");
		}

		@Override
		public void willStopMcpServer(@NonNull McpServer mcpServer) {
			this.transitions.add("will-stop-mcp");
		}

		@Override
		public void didStopHttpServer(@NonNull HttpServer httpServer,
				@NonNull ParticipantShutdownResult result) {
			this.transitions.add("did-stop-http");
		}

		@Override
		public void didStopSseServer(@NonNull SseServer sseServer,
				@NonNull ParticipantShutdownResult result) {
			this.transitions.add("did-stop-sse");
		}

		@Override
		public void didStopMcpServer(@NonNull McpServer mcpServer,
				@NonNull ParticipantShutdownResult result) {
			this.transitions.add("did-stop-mcp-" + result.getDisposition().name());
		}

		@Override
		public void didStopSoklet(@NonNull Soklet soklet,
				@NonNull ShutdownResult result) {
			this.transitions.add("did-stop-soklet");
		}

		@Override
		public void didReceiveLogEvent(@NonNull LogEvent logEvent) {
			// Transport diagnostics are not lifecycle-transition records.
		}
	}

	private static final class CloseTrackingObserver
			implements LifecycleObserver, AutoCloseable {
		@NonNull private final List<String> state =
				new CopyOnWriteArrayList<>();
		@NonNull private final AtomicInteger closeCalls = new AtomicInteger();

		@Override
		public void didStartSoklet(@NonNull Soklet soklet) {
			this.state.add("started");
		}

		@Override
		public void didStopSoklet(@NonNull Soklet soklet,
				@NonNull ShutdownResult result) {
			this.state.add("stopped");
		}

		@Override
		public void close() {
			this.closeCalls.incrementAndGet();
		}
	}

	private static final class BlockingAttachHttpEndpoint implements HttpServer {
		@NonNull private final TransportIdentity identity =
				TransportIdentity.create();
		@NonNull private final CountDownLatch attachEntered;
		@NonNull private final CountDownLatch releaseAttach;
		@NonNull private final CountDownLatch attachReturned;

		private BlockingAttachHttpEndpoint(
				@NonNull CountDownLatch attachEntered,
				@NonNull CountDownLatch releaseAttach,
				@NonNull CountDownLatch attachReturned) {
			this.attachEntered = requireNonNull(attachEntered);
			this.releaseAttach = requireNonNull(releaseAttach);
			this.attachReturned = requireNonNull(attachReturned);
		}

		@Override @NonNull public TransportIdentity getTransportIdentity() {
			return this.identity;
		}

		@Override @NonNull public TransportRuntime attach(
				@NonNull HttpTransportAttachmentContext context,
				@NonNull StartupContext startupContext) {
			this.attachEntered.countDown();
			awaitUninterruptibly(this.releaseAttach);
			this.attachReturned.countDown();
			return new TransportRuntime() {
				@Override public void start(@NonNull StartupContext ignored) { }
				@Override public void quiesce(
						@NonNull ShutdownContext ignored) { }
				@Override public void force(
						@NonNull ShutdownContext ignored) { }
			};
		}
	}

	private static final class LoggingAttachHttpServer implements HttpServer {
		@NonNull private final TransportIdentity identity =
				TransportIdentity.create();
		@NonNull private final LogEvent logEvent;
		@NonNull private final CountDownLatch attachEntered = new CountDownLatch(1);
		@NonNull private final CountDownLatch releaseAttach = new CountDownLatch(1);
		@NonNull private final AtomicReference<Thread> attachThread =
				new AtomicReference<>();
		@NonNull private final AtomicBoolean terminationSignalled =
				new AtomicBoolean();

		private LoggingAttachHttpServer(@NonNull LogEvent logEvent) {
			this.logEvent = requireNonNull(logEvent);
		}

		@Override @NonNull public TransportIdentity getTransportIdentity() {
			return this.identity;
		}

		@Override @NonNull public TransportRuntime attach(
				@NonNull HttpTransportAttachmentContext attachmentContext,
				@NonNull StartupContext startupContext) {
			requireNonNull(attachmentContext
					.getAdmissionFencedRequestHandler());
			requireNonNull(startupContext);
			this.attachThread.set(Thread.currentThread());
			this.attachEntered.countDown();
			attachmentContext.getSokletConfig().getAggregateLifecycleObserver()
					.didReceiveLogEvent(this.logEvent);
			awaitUninterruptibly(this.releaseAttach);
			TransportTerminationSignal terminationSignal =
					attachmentContext.getTerminationSignal();
			return new TransportRuntime() {
				@Override public void start(@NonNull StartupContext context) {
					requireNonNull(context);
				}

				@Override public void quiesce(@NonNull ShutdownContext context) {
					requireNonNull(context);
					terminate(terminationSignal);
				}

				@Override public void force(@NonNull ShutdownContext context) {
					requireNonNull(context);
					terminate(terminationSignal);
				}
			};
		}

		private void terminate(
				@NonNull TransportTerminationSignal terminationSignal) {
			if (this.terminationSignalled.compareAndSet(false, true))
				terminationSignal.signalTerminated();
		}
	}

	private static final class RecordingProcessAccess
			implements LifecycleProcessAccess {
		@NonNull private final AtomicInteger addCalls = new AtomicInteger();
		@NonNull private final AtomicInteger removeCalls = new AtomicInteger();

		@Override @NonNull public Optional<InputStream> standardInput() {
			return Optional.empty();
		}

		@Override public void addShutdownHook(@NonNull Thread hook) {
			requireNonNull(hook);
			this.addCalls.incrementAndGet();
		}

		@Override public boolean removeShutdownHook(@NonNull Thread hook) {
			requireNonNull(hook);
			this.removeCalls.incrementAndGet();
			return true;
		}

		@Override public void reportConfigurationWarning(@NonNull String message) {
			throw new AssertionError("No process warning expected: " + message);
		}
	}

	private static final class RecordingTriggerRegistry
			implements SokletApplicationTriggerRegistry {
		@NonNull private final AtomicReference<Runnable> shutdownIntent =
				new AtomicReference<>();
		@NonNull private final AtomicInteger unregisterCalls = new AtomicInteger();

		@Override @NonNull public SokletApplicationTriggerRegistration register(
				@NonNull Runnable shutdownIntent) {
			Assertions.assertTrue(this.shutdownIntent.compareAndSet(null,
					requireNonNull(shutdownIntent)));
			return this.unregisterCalls::incrementAndGet;
		}

		void trigger() {
			requireNonNull(this.shutdownIntent.get()).run();
		}
	}

	public static final class ObservationResource {
		@GET("/observation")
		@NonNull
		public String observation() {
			return "ok";
		}
	}

	public static final class HotPathObservationResource {
		@NonNull private static final AtomicReference<Thread> handlerThread =
				new AtomicReference<>();

		@GET("/observation")
		@NonNull
		public String observation() {
			handlerThread.set(Thread.currentThread());
			return "ok";
		}
	}

	public static final class ObservationSseResource {
		@SseEventSource("/events")
		@NonNull
		public SseHandshakeResult events() {
			return SseHandshakeResult.accept();
		}
	}
}
