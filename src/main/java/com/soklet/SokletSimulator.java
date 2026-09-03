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

import com.soklet.Soklet.DefaultSimulator;
import com.soklet.Soklet.MockHttpServer;
import com.soklet.Soklet.MockSseServer;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Objects.requireNonNull;

/** Runs single-use, off-network simulation configurations. */
@ThreadSafe
public final class SokletSimulator {
	/** Simulation body with checked-exception transparency. */
	@FunctionalInterface
	public interface Simulation<E extends Throwable> {
		/**
		 * Executes against the isolated simulator.
		 *
		 * @param simulator isolated simulator
		 * @throws E application-selected failure type
		 */
		void run(@NonNull Simulator simulator) throws E;
	}

	private SokletSimulator() {
	}

	/**
	 * Runs one prebuilt, single-use off-network simulation configuration.
	 *
	 * @param simulatorConfig simulator configuration; it is claimed by this call
	 * before lifecycle work begins and cannot be reused
	 * @param simulation simulation work
	 * @param <E> checked throwable type selected by the simulation
	 * @return the exact immutable scope-shutdown result
	 * @throws E if the simulation fails; a teardown failure is then suppressed on it
	 * @throws SokletShutdownIncompleteException if successful simulation execution is followed
	 * by shutdown that cannot be proven complete
	 * @throws IllegalStateException if {@code simulatorConfig} was already claimed
	 */
	@NonNull
	public static <E extends Throwable> ShutdownResult run(
			@NonNull SimulatorConfig simulatorConfig,
			@NonNull Simulation<E> simulation) throws E {
		return ShutdownResult.fromInternal(run(simulatorConfig, simulation,
				NanoClock.system(), new LifecycleWorkers()));
	}

	@NonNull
	static <E extends Throwable> InternalShutdownResult run(
			@NonNull SimulatorConfig simulatorConfig,
			@NonNull Simulation<E> simulation,
			@NonNull NanoClock clock,
			@NonNull LifecycleWorkers workers) throws E {
		NanoClock exactClock = requireNonNull(clock);
		return run(simulatorConfig, simulation, exactClock,
				new DeadlineWaiter(exactClock), workers);
	}

	@NonNull
	static <E extends Throwable> InternalShutdownResult run(
			@NonNull SimulatorConfig simulatorConfig,
			@NonNull Simulation<E> simulation,
			@NonNull NanoClock clock,
			@NonNull DeadlineWaiter waiter,
			@NonNull LifecycleWorkers workers) throws E {
		SimulatorConfig exactConfig = requireNonNull(simulatorConfig);
		Simulation<E> exactSimulation = requireNonNull(simulation);
		NanoClock exactClock = requireNonNull(clock);
		DeadlineWaiter exactWaiter = requireNonNull(waiter);
		LifecycleWorkers exactWorkers = requireNonNull(workers);
		exactConfig.claimForRun();
		return new Scope(exactConfig, exactClock, exactWaiter, exactWorkers)
				.run(exactConfig, exactSimulation);
	}

	@SuppressWarnings("unchecked")
	private static <E extends Throwable> void throwBodyFailure(
			@NonNull Throwable failure) throws E {
		throw (E) requireNonNull(failure);
	}

	@NotThreadSafe
	private static final class Scope
			implements SimulatorConfigurationScopeIdentity {
		@NonNull
		private final NanoClock clock;
		@NonNull
		private final LifecycleWorkers workers;
		@NonNull
		private final DeadlineWaiter waiter;
		@NonNull
		private final TrackedLifecycleCallRunner callRunner;
		@NonNull
		private final AtomicReference<@Nullable StartupCallParticipant>
				activeStartupParticipant;
		@NonNull
		private final AtomicBoolean startupCancelationRequired;
		@NonNull
		private final Object configurationIdentity;
		@NonNull
		private final MockHttpServer httpServer;
		@NonNull
		private final MockSseServer sseServer;
		@NonNull
		private final InternalLifecycleStateMachine stateMachine;
		@NonNull
		private final InternalControllingEventElection controllingEventElection;
		private final @Nullable DefaultMcpServer mcpServer;
		private @Nullable DefaultSimulator simulator;

		private Scope(@NonNull SimulatorConfig simulatorConfig,
				@NonNull NanoClock clock,
				@NonNull DeadlineWaiter waiter,
				@NonNull LifecycleWorkers workers) {
			SimulatorConfig exactConfig = requireNonNull(simulatorConfig);
			this.clock = requireNonNull(clock);
			this.workers = requireNonNull(workers);
			this.waiter = requireNonNull(waiter);
			this.callRunner = new TrackedLifecycleCallRunner(workers);
			this.activeStartupParticipant = new AtomicReference<>();
			this.startupCancelationRequired = new AtomicBoolean();
			this.configurationIdentity = exactConfig.configurationIdentity();
			this.httpServer = exactConfig.simulatedHttpServer();
			this.sseServer = exactConfig.simulatedSseServer();
			this.mcpServer = exactConfig.simulatedMcpServer();
			this.stateMachine = new InternalLifecycleStateMachine();
			this.controllingEventElection =
					new InternalControllingEventElection();
		}

		@NonNull
		private <E extends Throwable> InternalShutdownResult run(
				@NonNull SimulatorConfig simulatorConfig,
				@NonNull Simulation<E> simulation) throws E {
			if (!simulatorConfig.belongsTo(this.configurationIdentity))
				throw new IllegalStateException(
						"The simulator configuration belongs to a different scope");

			SokletConfig config = simulatorConfig.getSokletConfig();
			ResolvedScopeTransports resolved = validate(config);
			InternalLifecyclePolicy policy = config.getInternalLifecyclePolicy();
			long startupBeganNanos = this.clock.nanoTime();
			long startupDeadline = LifecycleDeadlines.after(startupBeganNanos,
					policy.startupTimeout());
			StartupBudget startupBudget = new StartupBudget(startupDeadline,
					policy.startupCancelationTimeout());
			this.stateMachine.claimStart();
			// Construction is deliberately lightweight.  Setup and installation run
			// below under this scope's startup context, against only its fresh mocks
			// and fresh unbound MCP graph.
			Soklet soklet = Soklet.fromConfig(config);

			Object executionOwnerToken = soklet.getDirectLifecycle()
					.executionOwnerToken();
			List<ScopeParticipant> participants = participants(resolved,
					executionOwnerToken);
			ScopeDispatchGate dispatchGate = new ScopeDispatchGate(participants);
			this.simulator = new DefaultSimulator(
					resolved.httpServer().orElse(null),
					resolved.sseServer().orElse(null),
					simulatorConfig.getSimulatorOptions(),
					resolved.mcpServer().orElse(null), dispatchGate);
			Throwable primaryFailure = null;
			boolean bodyEntered = false;
			boolean restoreStartupInterrupt = false;
			boolean startupLifecycleFailure = false;
			InternalStartupDisposition startupDisposition =
					InternalStartupDisposition.FAILED;
			StartupAttempt setupAttempt = new StartupAttempt(this,
					executionOwnerToken);
			try {
				startParticipants(soklet, participants, startupBudget,
						setupAttempt);
				ensureStartupDeadline(startupBudget.startupDeadline());
				ensureStartupMayContinue();
				if (!this.stateMachine.publishReady()) {
					ensureStartupMayContinue();
					throw new IllegalStateException(
							"The simulator scope could not publish readiness");
				}
				startupDisposition = InternalStartupDisposition.READY;
				if (!openParticipantAdmission(participants))
					requestOwnerShutdown();
				bodyEntered = true;
				simulation.run(this.simulator);
			} catch (Throwable failure) {
				primaryFailure = failure;
				if (!bodyEntered
						&& startupDisposition != InternalStartupDisposition.READY) {
					startupLifecycleFailure = true;
					if (failure instanceof TimeoutException)
						startupDisposition = InternalStartupDisposition.TIMED_OUT;
					else if (failure instanceof InterruptedException) {
						startupDisposition = InternalStartupDisposition.CANCELED;
						restoreStartupInterrupt = true;
					} else if (this.stateMachine.shutdownRequested()
							&& participants.stream().noneMatch(participant ->
									participant.terminationGroup()
											.controllingEvent().isPresent()))
						startupDisposition = InternalStartupDisposition.CANCELED;
					else
						startupDisposition = InternalStartupDisposition.FAILED;
				}
			}

			TeardownOutcome teardown = teardown(participants, setupAttempt,
					policy, startupBudget, startupDisposition);

			if (startupLifecycleFailure) {
				SokletStartupException startupFailure = new SokletStartupException(
						startupDisposition, teardown.result(),
						requireNonNull(primaryFailure));
				if (teardown.failure() != null)
					startupFailure.addSuppressed(teardown.failure());
				if (restoreStartupInterrupt)
					Thread.currentThread().interrupt();
				throw startupFailure;
			}
			if (primaryFailure != null) {
				if (teardown.failure() != null)
					primaryFailure.addSuppressed(teardown.failure());
				SokletSimulator.<E>throwBodyFailure(primaryFailure);
			}
			if (teardown.failure() != null)
				throw teardown.failure();
			return teardown.result();
		}

		@Override
		@NonNull
		public Object simulatorConfigurationIdentity() {
			return this.configurationIdentity;
		}

		@NonNull
		private ResolvedScopeTransports validate(@NonNull SokletConfig config) {
			HttpServer configuredHttp = config.getHttpServer().orElse(null);
			if (configuredHttp != null && configuredHttp != this.httpServer)
				throw new IllegalStateException(
						"The simulator config contains a foreign HTTP transport");

			SseServer configuredSse = config.getSseServer().orElse(null);
			if (configuredSse != null && configuredSse != this.sseServer)
				throw new IllegalStateException(
						"The simulator config contains a foreign SSE transport");

			McpServer configuredMcp = config.getMcpServer().orElse(null);
			DefaultMcpServer registeredMcp = this.mcpServer;
			if (configuredMcp != registeredMcp)
				throw new IllegalStateException(configuredMcp == null
						? "The scope-built MCP server was not installed in the simulator config"
						: "The simulator config contains a production or foreign MCP transport");
			if (registeredMcp != null && registeredMcp.getDiagnostics().getStatus()
					!= McpServerStatus.NOT_STARTED)
				throw new IllegalStateException(
						"A simulator MCP transport must not be started");

			return new ResolvedScopeTransports(
					Optional.ofNullable((MockHttpServer) configuredHttp),
					Optional.ofNullable((MockSseServer) configuredSse),
					Optional.ofNullable(registeredMcp));
		}

		@NonNull
		private List<ScopeParticipant> participants(
				@NonNull ResolvedScopeTransports resolved,
				@NonNull Object executionOwnerToken) {
			List<ScopeParticipant> participants = new ArrayList<>(3);
			resolved.httpServer().ifPresent(ignored -> participants.add(
					new ScopeParticipant(InternalLifecycleComponentType.HTTP, this,
							null, executionOwnerToken)));
			resolved.sseServer().ifPresent(ignored -> participants.add(
					new ScopeParticipant(InternalLifecycleComponentType.SSE, this,
							null, executionOwnerToken)));
			resolved.mcpServer().ifPresent(server -> participants.add(
					new ScopeParticipant(InternalLifecycleComponentType.MCP, this,
							server, executionOwnerToken)));
			return List.copyOf(participants);
		}

		private void startParticipants(@NonNull Soklet soklet,
				@NonNull List<ScopeParticipant> participants,
				@NonNull StartupBudget startupBudget,
				@NonNull StartupAttempt setupAttempt) throws Throwable {
			StartupBudget exactBudget = requireNonNull(startupBudget);
			StartupContext context = new StartupContext(this.clock,
					exactBudget.startupDeadline(),
					exactBudget::cancellationDeadlineNanos,
					this.stateMachine::shutdownRequested);
			runStartupCall("simulator-framework-setup", setupAttempt,
					() -> requireNonNull(soklet).initializeForSimulator(context,
							this.waiter), exactBudget.startupDeadline());
			ensureStartupDeadline(exactBudget.startupDeadline());
			ensureStartupMayContinue();

			for (ScopeParticipant participant : participants) {
				participant.commit();
				ensureStartupMayContinue();
			}
			for (ScopeParticipant participant : participants) {
				runStartupCall("simulator-start-"
							+ participant.kind().name().toLowerCase(Locale.ROOT), participant,
						() -> participant.start(context),
						exactBudget.startupDeadline());
				ensureStartupDeadline(exactBudget.startupDeadline());
				ensureStartupMayContinue();
			}
			ensureStartupDeadline(exactBudget.startupDeadline());
			ensureStartupMayContinue();
		}

		private boolean openParticipantAdmission(
				@NonNull List<ScopeParticipant> participants) {
			boolean everyAdmissionOpened = true;
			for (ScopeParticipant participant : requireNonNull(participants)) {
				if (!participant.openAdmission())
					everyAdmissionOpened = false;
			}
			return everyAdmissionOpened;
		}

		private void runStartupCall(@NonNull String name,
				@NonNull StartupCallParticipant participant,
				@NonNull Runnable action,
				long startupDeadline) throws Throwable {
			AtomicReference<Throwable> failure = new AtomicReference<>();
			StartupCallParticipant exactParticipant = requireNonNull(participant);
			exactParticipant.beginStartupCall();
			if (!this.activeStartupParticipant.compareAndSet(null,
					exactParticipant)) {
				exactParticipant.completeStartupCall();
				throw new IllegalStateException(
						"A simulator startup call is already active");
			}
			TrackedLifecycleCallRunner.Call<Void> call;
			try {
				call = this.callRunner.submit(requireNonNull(name),
						exactParticipant.terminationGroup(), () -> {
							try {
								Throwable startupStop = startupStopFailure();
								if (startupStop != null) {
									exactParticipant.terminationGroup()
											.recordShutdownIntent();
									throw startupStop;
								}
								requireNonNull(action).run();
								return null;
							} catch (Throwable throwable) {
								failure.set(throwable);
								if (throwable instanceof Exception exception)
									throw exception;
								throw (Error) throwable;
							} finally {
								exactParticipant.completeStartupCall();
								this.activeStartupParticipant.compareAndSet(
										exactParticipant, null);
							}
						});
			} catch (RuntimeException | Error launchFailure) {
				exactParticipant.completeStartupCall();
				this.activeStartupParticipant.compareAndSet(
						exactParticipant, null);
				throw launchFailure;
			}
			exactParticipant.installStartupCall(call);
			call.completion().whenComplete((ignored, ignoredFailure) ->
					this.waiter.signal());

			DeadlineWaiter.Outcome outcome;
			try {
				outcome = this.waiter.await(startupDeadline,
						() -> call.isDone()
								|| this.stateMachine.shutdownRequested());
			} catch (InterruptedException interrupted) {
				throw interrupted;
			}
			if (outcome == DeadlineWaiter.Outcome.DEADLINE_REACHED)
				throw new TimeoutException(
						"Soklet simulator startup deadline was reached");
			Throwable exactFailure = failure.get();
			if (exactFailure != null)
				throw exactFailure;
			ensureStartupMayContinue();
			if (!call.isDone())
				throw new IllegalStateException(
						"Soklet simulator startup ended before the call completed");
		}

		private void ensureStartupMayContinue() throws Throwable {
			Throwable failure = startupStopFailure();
			if (failure != null)
				throw failure;
		}

		@Nullable
		private Throwable startupStopFailure() {
			Optional<InternalTerminationEvent> event =
					this.controllingEventElection.firstEvent();
			if (event.isPresent())
				return event.orElseThrow().cause().orElseGet(() ->
						new IllegalStateException(
								"A simulator participant terminated during startup"));
			if (this.stateMachine.shutdownRequested())
				return new IllegalStateException(
						"Simulator shutdown was requested during startup");
			return null;
		}

		private void ensureStartupDeadline(long startupDeadline)
				throws TimeoutException {
			if (this.clock.nanoTime() >= startupDeadline)
				throw new TimeoutException(
						"Soklet simulator startup deadline was reached");
		}

		@NonNull
		private TeardownOutcome teardown(
				@NonNull List<ScopeParticipant> participants,
				@NonNull StartupAttempt setupAttempt,
				@NonNull InternalLifecyclePolicy policy,
				@NonNull StartupBudget startupBudget,
				@NonNull InternalStartupDisposition startupDisposition) {
			InternalShutdownResult result = null;
			List<Throwable> failures = new ArrayList<>();
			boolean restoreTeardownInterrupt = false;
			boolean startupCallActiveAtIntent = setupAttempt.startupCallActive()
					|| participants.stream().anyMatch(
							ScopeParticipant::startupCallActive);
			if (startupCallActiveAtIntent)
				this.startupCancelationRequired.set(true);
			InternalLifecycleStateMachine.ShutdownRequest shutdownRequest = null;
			AtomicReference<@Nullable Long> attemptedIntentNanos =
					new AtomicReference<>();
			try {
				shutdownRequest = this.controllingEventElection
						.publishShutdownIntent(() -> {
							long intentNanos = this.clock.nanoTime();
							attemptedIntentNanos.set(intentNanos);
							// Admission closure and the owner cutoff are one election
							// boundary relative to participant terminal events.
							try {
								requireNonNull(this.simulator).sealScope();
							} catch (Throwable failure) {
								failures.add(failure);
							}
							return this.stateMachine.requestShutdownDetailed(
									intentNanos);
						});
			} catch (Throwable failure) {
				failures.add(failure);
			}
			long intentNanos = shutdownRequest == null
					? Optional.ofNullable(attemptedIntentNanos.get())
							.orElseGet(this.clock::nanoTime)
					: shutdownRequest.intentNanos();
			boolean cancellationRequired =
					this.startupCancelationRequired.get();
			long cancellationDeadline = cancellationRequired
					? startupBudget.beginCancellation(intentNanos) : intentNanos;
			long gracefulDeadline = LifecycleDeadlines.after(cancellationDeadline,
					policy.gracefulShutdownTimeout());
			long forcedDeadline = LifecycleDeadlines.after(gracefulDeadline,
					policy.forcedShutdownTimeout());

			List<InternalLifecycleCoordinator.Participant>
					coordinatedParticipants = new ArrayList<>();
			for (ScopeParticipant participant : participants)
				if (participant.committed())
					coordinatedParticipants.add(participant);
			if (setupAttempt.startupCallActive())
				coordinatedParticipants.add(setupAttempt);
			for (InternalLifecycleCoordinator.Participant participant
					: coordinatedParticipants)
				participant.terminationGroup().recordShutdownIntent();
			for (ScopeParticipant participant : participants) {
				try {
					participant.recordShutdownIntent();
				} catch (Throwable failure) {
					failures.add(failure);
				}
			}
			if (cancellationRequired) {
				submitStartupCancelationQuiesce(participants,
						gracefulDeadline);
				setupAttempt.cancelStartupCall();
				for (ScopeParticipant participant : participants)
					participant.cancelStartupCall();
				if (setupAttempt.startupCallActive()
						|| participants.stream().anyMatch(
								ScopeParticipant::startupCallActive)) {
					try {
						this.waiter.await(cancellationDeadline,
								() -> !setupAttempt.startupCallActive()
										&& participants.stream().noneMatch(
												ScopeParticipant::startupCallActive));
					} catch (InterruptedException failure) {
						restoreTeardownInterrupt = true;
						failures.add(failure);
					}
				}
			}
			try {
				InternalShutdownResult coordinated =
						new InternalLifecycleCoordinator(this.clock, this.waiter,
								this.callRunner).shutdown(coordinatedParticipants,
								gracefulDeadline, forcedDeadline);
				result = completeConfiguredParticipantResults(coordinated,
						participants, startupDisposition);
			} catch (InterruptedException failure) {
				restoreTeardownInterrupt = true;
				failures.add(failure);
			} catch (Throwable failure) {
				failures.add(failure);
			}
			try {
				this.stateMachine.publishClosed();
			} catch (Throwable failure) {
				failures.add(failure);
			}

			if (result == null || !failures.isEmpty())
				result = incompleteResult(participants, setupAttempt,
						startupDisposition, result, failures);

			if (result.isComplete()) {
				try {
					requireNonNull(this.simulator).releaseMcpScopeEvidence();
				} catch (Throwable failure) {
					failures.add(failure);
					result = incompleteResult(participants, setupAttempt,
							startupDisposition, result, failures);
				}
			}

			InternalShutdownResult resultToPublish = result;
			try {
				for (ScopeParticipant participant : participants)
					participant.finalizeAndPublishResult(resultToPublish);
			} catch (Throwable failure) {
				failures.add(failure);
				result = incompleteResult(participants, setupAttempt,
						startupDisposition, result, failures);
				for (ScopeParticipant participant : participants) {
					try {
						participant.publishResultAfterFailure(result);
					} catch (Throwable publicationFailure) {
						if (publicationFailure != failure)
							failure.addSuppressed(publicationFailure);
					}
				}
			}
			if (result.isComplete())
				requireNonNull(this.simulator).releaseHttpAndSseScopeState();

			Throwable cause = failures.isEmpty() ? null : failures.get(0);
			for (int index = 1; index < failures.size(); index++) {
				Throwable additional = failures.get(index);
				if (additional != cause)
					requireNonNull(cause).addSuppressed(additional);
			}
			SokletShutdownIncompleteException failure = result.isComplete() ? null
					: new SokletShutdownIncompleteException(result, this, cause);
			if (restoreTeardownInterrupt)
				Thread.currentThread().interrupt();
			return new TeardownOutcome(result, failure);
		}

		private void submitStartupCancelationQuiesce(
				@NonNull List<ScopeParticipant> participants,
				long gracefulDeadlineNanos) {
			ShutdownContext context = new ShutdownContext(
					ShutdownPhase.GRACEFUL, this.clock,
					gracefulDeadlineNanos);
			for (ScopeParticipant participant : requireNonNull(participants)) {
				if (!participant.committed())
					continue;
				try {
					this.callRunner.submit("simulator-cancellation-quiesce-"
							+ participant.kind().name().toLowerCase(Locale.ROOT),
							participant.terminationGroup(), () -> {
								participant.runtime().shutdownGracefully(context);
								return null;
							});
				} catch (RuntimeException | Error launchFailure) {
					participant.terminationGroup().signalFailure(
							participant.terminationGroup().root(), launchFailure);
				}
			}
		}

		@NonNull
		private InternalShutdownResult incompleteResult(
				@NonNull List<ScopeParticipant> participants,
				@NonNull StartupAttempt setupAttempt,
				@NonNull InternalStartupDisposition startupDisposition,
				@Nullable InternalShutdownResult coordinated,
				@NonNull List<? extends Throwable> failures) {
			List<InternalLifecycleComponentShutdownResult> results;
			if (coordinated == null) {
				results = new ArrayList<>();
				for (ScopeParticipant participant : participants)
					if (participant.committed())
						participant.freezeForClassification();
				setupAttempt.freezeForClassification();
				EnumMap<InternalLifecycleComponentType,
						InternalTerminationGroup.EvidenceSnapshot> evidenceByKind =
						new EnumMap<>(InternalLifecycleComponentType.class);
				for (ScopeParticipant participant : participants)
					if (participant.committed())
						evidenceByKind.put(participant.kind(), participant
								.terminationGroup().freezeEvidence());
				InternalTerminationGroup.EvidenceSnapshot setupEvidence =
						setupAttempt.terminationGroup().freezeEvidence();
				for (ScopeParticipant participant : participants) {
					if (!participant.committed()) {
						results.add(new InternalLifecycleComponentShutdownResult(
								participant.kind(),
								InternalLifecycleComponentShutdownDisposition.NOT_STARTED,
								List.of(), Set.of()));
						continue;
					}
					EnumSet<InternalResidualActivityType> residual = EnumSet
							.noneOf(InternalResidualActivityType.class);
					residual.addAll(participant.residualActivity());
					InternalTerminationGroup.EvidenceSnapshot evidence =
							requireNonNull(evidenceByKind.get(participant.kind()));
					if (evidence.trackedLifecycleCalls() > 0)
						residual.add(InternalResidualActivityType.LIFECYCLE_CALL);
					if (evidence.admittedWork() > 0)
						residual.add(InternalResidualActivityType.CALLBACK);
					results.add(new InternalLifecycleComponentShutdownResult(
							participant.kind(),
							InternalLifecycleComponentShutdownDisposition.TERMINATION_UNKNOWN,
							mergedFailures(evidence, failures), residual));
				}
				if (setupAttempt.startupCallActive()) {
					EnumSet<InternalResidualActivityType> residual = EnumSet
							.noneOf(InternalResidualActivityType.class);
					if (setupEvidence.trackedLifecycleCalls() > 0)
						residual.add(InternalResidualActivityType.LIFECYCLE_CALL);
					if (setupEvidence.admittedWork() > 0)
						residual.add(InternalResidualActivityType.CALLBACK);
					results.add(new InternalLifecycleComponentShutdownResult(
							InternalLifecycleComponentType.FRAMEWORK,
							InternalLifecycleComponentShutdownDisposition.TERMINATION_UNKNOWN,
							mergedFailures(setupEvidence, failures), residual));
				}
				if (!failures.isEmpty() && results.stream().noneMatch(result ->
						result.disposition()
								== InternalLifecycleComponentShutdownDisposition.RESIDUAL_ACTIVITY
								|| result.disposition()
								== InternalLifecycleComponentShutdownDisposition
										.TERMINATION_UNKNOWN))
					results.add(new InternalLifecycleComponentShutdownResult(
							InternalLifecycleComponentType.FRAMEWORK,
							InternalLifecycleComponentShutdownDisposition.TERMINATION_UNKNOWN,
							failures, Set.of()));
			} else {
				results = new ArrayList<>(coordinated.participantResults());
				if (!failures.isEmpty()) {
					int targetIndex = -1;
					for (int index = 0; index < results.size(); index++) {
						InternalLifecycleComponentShutdownDisposition disposition =
								results.get(index).disposition();
						if (disposition
								== InternalLifecycleComponentShutdownDisposition.RESIDUAL_ACTIVITY
								|| disposition == InternalLifecycleComponentShutdownDisposition
										.TERMINATION_UNKNOWN) {
							targetIndex = index;
							break;
						}
					}
					if (targetIndex < 0)
						for (int index = 0; index < results.size(); index++)
							if (results.get(index).kind()
									== InternalLifecycleComponentType.MCP) {
								targetIndex = index;
								break;
							}
					if (targetIndex < 0 && !results.isEmpty())
						targetIndex = 0;
					if (targetIndex < 0) {
						results.add(new InternalLifecycleComponentShutdownResult(
								InternalLifecycleComponentType.FRAMEWORK,
								InternalLifecycleComponentShutdownDisposition.TERMINATION_UNKNOWN,
								failures, Set.of()));
					} else {
						InternalLifecycleComponentShutdownResult existing =
								results.get(targetIndex);
						List<Throwable> attributedFailures = new ArrayList<>(
								existing.failures());
						for (Throwable failure : failures)
							if (attributedFailures.stream().noneMatch(
									candidate -> candidate == failure))
								attributedFailures.add(failure);
						InternalLifecycleComponentShutdownDisposition disposition =
								existing.disposition()
										== InternalLifecycleComponentShutdownDisposition
												.RESIDUAL_ACTIVITY
										? existing.disposition()
										: InternalLifecycleComponentShutdownDisposition
												.TERMINATION_UNKNOWN;
						results.set(targetIndex,
								new InternalLifecycleComponentShutdownResult(existing.kind(),
										disposition, attributedFailures,
										existing.residualActivity()));
					}
				}
			}
			return new InternalShutdownResultAggregator().aggregate(
					startupDisposition, results);
		}

		@NonNull
		private List<Throwable> mergedFailures(
				InternalTerminationGroup.@NonNull EvidenceSnapshot evidence,
				@NonNull List<? extends Throwable> infrastructureFailures) {
			List<Throwable> merged = new ArrayList<>();
			for (InternalTerminationEvent event
					: requireNonNull(evidence).primaryEvents())
				event.cause().ifPresent(merged::add);
			for (Throwable failure : requireNonNull(infrastructureFailures))
				if (merged.stream().noneMatch(candidate -> candidate == failure))
					merged.add(failure);
			return List.copyOf(merged);
		}

		@NonNull
		private InternalShutdownResult completeConfiguredParticipantResults(
				@NonNull InternalShutdownResult result,
				@NonNull List<ScopeParticipant> participants,
				@NonNull InternalStartupDisposition startupDisposition) {
			EnumMap<InternalLifecycleComponentType, InternalLifecycleComponentShutdownResult>
					resultsByKind = new EnumMap<>(InternalLifecycleComponentType.class);
			for (InternalLifecycleComponentShutdownResult participantResult
					: requireNonNull(result).participantResults())
				resultsByKind.put(participantResult.kind(), participantResult);
			for (ScopeParticipant participant : requireNonNull(participants)) {
				InternalLifecycleComponentShutdownResult participantResult =
						resultsByKind.get(participant.kind());
				if (participantResult == null) {
					resultsByKind.put(participant.kind(),
							new InternalLifecycleComponentShutdownResult(participant.kind(),
									InternalLifecycleComponentShutdownDisposition.NOT_STARTED,
									List.of(), Set.of()));
				} else if (!participant.startAttempted()
						&& participantResult.disposition()
								== InternalLifecycleComponentShutdownDisposition
										.GRACEFUL_TERMINATION) {
					resultsByKind.put(participant.kind(),
							new InternalLifecycleComponentShutdownResult(participant.kind(),
									InternalLifecycleComponentShutdownDisposition.NOT_STARTED,
									participantResult.failures(),
									participantResult.residualActivity()));
				}
			}
			return new InternalShutdownResultAggregator().aggregate(
					requireNonNull(startupDisposition),
					List.copyOf(resultsByKind.values()));
		}

		private void requestOwnerShutdown() {
			if (this.activeStartupParticipant.get() != null)
				this.startupCancelationRequired.set(true);
			DefaultSimulator activeSimulator = this.simulator;
			try {
				this.controllingEventElection.publishShutdownIntent(() -> {
					long intentNanos = this.clock.nanoTime();
					try {
						if (activeSimulator != null)
							activeSimulator.sealScope();
					} catch (Throwable ignored) {
						// Owner intent still publishes if local sealing fails.
					}
					return this.stateMachine.requestShutdownDetailed(intentNanos);
				});
			} finally {
				this.waiter.signal();
			}
		}

		@NonNull
		private DefaultSimulator simulator() {
			return requireNonNull(this.simulator,
					"The simulator runtime has not been attached");
		}
	}

	private interface StartupCallParticipant
			extends InternalLifecycleCoordinator.Participant {
		void beginStartupCall();

		void installStartupCall(
				TrackedLifecycleCallRunner.@NonNull Call<Void> call);

		void completeStartupCall();

		void cancelStartupCall();
	}

	@ThreadSafe
	private static final class StartupBudget {
		private final long startupDeadline;
		@NonNull
		private final Duration cancellationDuration;
		@NonNull
		private final AtomicReference<@Nullable Long> cancellationDeadline;

		private StartupBudget(long startupDeadline,
				@NonNull Duration cancellationDuration) {
			this.startupDeadline = startupDeadline;
			this.cancellationDuration = requireNonNull(cancellationDuration);
			this.cancellationDeadline = new AtomicReference<>();
		}

		private long startupDeadline() {
			return this.startupDeadline;
		}

		private long beginCancellation(long shutdownIntentNanos) {
			long proposed = LifecycleDeadlines.after(shutdownIntentNanos,
					this.cancellationDuration);
			this.cancellationDeadline.compareAndSet(null, proposed);
			return requireNonNull(this.cancellationDeadline.get());
		}

		private long cancellationDeadlineNanos() {
			Long deadline = this.cancellationDeadline.get();
			return deadline == null ? Long.MAX_VALUE : deadline;
		}
	}

	@ThreadSafe
	private static final class StartupAttempt implements StartupCallParticipant {
		@NonNull
		private final AdmissionFence admissionFence;
		@NonNull
		private final InternalTerminationGroup terminationGroup;
		@NonNull
		private final InternalTransportTerminationSignal terminationSignal;
		@NonNull
		private final DirectParticipantPhaseGate phaseGate;
		@NonNull
		private final AtomicReference<TrackedLifecycleCallRunner.@Nullable Call<Void>>
				startupCall;

		private StartupAttempt(@NonNull Scope scope,
				@NonNull Object executionOwnerToken) {
			Scope exactScope = requireNonNull(scope);
			this.admissionFence = new AdmissionFence(false,
					exactScope.waiter::signal);
			this.terminationGroup = new InternalTerminationGroup(
					this.admissionFence, exactScope.waiter::signal,
					exactScope.workers, requireNonNull(executionOwnerToken));
			this.terminationSignal = new InternalTransportTerminationSignal(
					this.terminationGroup, this.terminationGroup.root());
			this.phaseGate = new DirectParticipantPhaseGate();
			this.startupCall = new AtomicReference<>();
			this.terminationGroup.commit();
		}

		@Override
		public void beginStartupCall() {
			if (!this.phaseGate.claimStart())
				throw new IllegalStateException(
						"Simulator framework setup was already attempted");
		}

		@Override
		public void installStartupCall(
				TrackedLifecycleCallRunner.@NonNull Call<Void> call) {
			TrackedLifecycleCallRunner.Call<Void> exactCall = requireNonNull(call);
			this.startupCall.set(exactCall);
			if (!this.phaseGate.startupCallActive())
				this.startupCall.compareAndSet(exactCall, null);
		}

		@Override
		public void completeStartupCall() {
			this.startupCall.set(null);
			this.phaseGate.completeStartCall();
			this.terminationGroup.recordShutdownIntent();
			this.terminationSignal.signalTerminated();
		}

		@Override
		public void cancelStartupCall() {
			TrackedLifecycleCallRunner.Call<Void> call = this.startupCall.get();
			if (call != null)
				call.cancel();
		}

		@Override
		public boolean startupCallActive() {
			return this.phaseGate.startupCallActive();
		}

		@Override
		public void freezeForClassification() {
			this.phaseGate.freezeForClassification();
		}

		@NonNull
		@Override
		public InternalLifecycleComponentType kind() {
			return InternalLifecycleComponentType.FRAMEWORK;
		}

		@NonNull
		@Override
		public AdmissionFence admissionFence() {
			return this.admissionFence;
		}

		@NonNull
		@Override
		public InternalTerminationGroup terminationGroup() {
			return this.terminationGroup;
		}

		@NonNull
		@Override
		public InternalTransportRuntime runtime() {
			return new InternalTransportRuntime() {
				@Override
				public void start(@NonNull StartupContext context) {
				}

				@Override
				public void shutdownGracefully(@NonNull ShutdownContext context) {
				}

				@Override
				public void shutdownForcibly(@NonNull ShutdownContext context) {
				}
			};
		}

		@NonNull
		@Override
		public Set<InternalResidualActivityType> residualActivity() {
			return Set.of();
		}
	}

	@Immutable
	private record TeardownOutcome(
			@NonNull InternalShutdownResult result,
			@Nullable SokletShutdownIncompleteException failure) {
		private TeardownOutcome {
			requireNonNull(result);
		}
	}

	static final class ScopeParticipant
			implements StartupCallParticipant {
		@NonNull
		private final InternalLifecycleComponentType kind;
		@NonNull
		private final Scope scope;
		@NonNull
		private final AdmissionFence admissionFence;
		@NonNull
		private final InternalTerminationGroup terminationGroup;
		@NonNull
		private final InternalTransportTerminationSignal terminationSignal;
		@NonNull
		private final InternalTransportRuntime runtime;
		private final @Nullable McpTransportLifecycleAdapter mcpLifecycleAdapter;
		private final McpTransportLifecycleAdapter.@Nullable Generation
				mcpGeneration;
		@NonNull
		private final DirectParticipantPhaseGate phaseGate;
		@NonNull
		private final AtomicReference<TrackedLifecycleCallRunner.@Nullable Call<Void>>
				startupCall;
		@NonNull
		private final AtomicReference<TrackedLifecycleCallRunner.@Nullable Call<Void>>
				proofObservation;
		@NonNull
		private final AtomicBoolean startAttempted;
		@NonNull
		private final Object phaseDeliveryLock;
		private boolean committed;
		private boolean mcpGenerationCommitted;

		private ScopeParticipant(@NonNull InternalLifecycleComponentType kind,
				@NonNull Scope scope, @Nullable DefaultMcpServer mcpServer,
				@NonNull Object executionOwnerToken) {
			this.kind = requireNonNull(kind);
			this.scope = requireNonNull(scope);
			if (this.kind == InternalLifecycleComponentType.MCP) {
				DefaultMcpServer exactServer = requireNonNull(mcpServer,
						"The MCP simulator participant requires a server");
				this.mcpLifecycleAdapter = exactServer.getLifecycleAdapter();
				this.mcpGeneration = this.mcpLifecycleAdapter
						.newExternallyCoordinatedGeneration(scope.waiter,
								scope.workers, requireNonNull(executionOwnerToken),
								scope::requestOwnerShutdown,
								scope::requestOwnerShutdown,
								scope.controllingEventElection);
				this.admissionFence = this.mcpGeneration.admissionFence();
				this.terminationGroup = this.mcpGeneration.terminationGroup();
			} else {
				if (mcpServer != null)
					throw new IllegalArgumentException(
							"Only an MCP simulator participant may receive an MCP server");
				this.mcpLifecycleAdapter = null;
				this.mcpGeneration = null;
				this.admissionFence = new AdmissionFence(false,
						scope.waiter::signal);
				this.terminationGroup = new InternalTerminationGroup(
						this.admissionFence, scope.waiter::signal, scope.workers);
			}
			this.terminationSignal = new InternalTransportTerminationSignal(
					this.terminationGroup, this.terminationGroup.root());
			this.runtime = new ScopeRuntime();
			this.phaseGate = new DirectParticipantPhaseGate();
			this.startupCall = new AtomicReference<>();
			this.proofObservation = new AtomicReference<>();
			this.startAttempted = new AtomicBoolean();
			this.phaseDeliveryLock = new Object();
		}

		private void commit() {
			if (this.mcpLifecycleAdapter == null)
				this.terminationGroup.commit();
			else {
				this.mcpLifecycleAdapter.commitExternallyCoordinatedGeneration(
						requireNonNull(this.mcpGeneration));
				this.mcpGenerationCommitted = true;
			}
			this.committed = true;
		}

		private void start(@NonNull StartupContext context) {
			this.startAttempted.set(true);
			this.runtime.start(requireNonNull(context));
		}

		private boolean startAttempted() {
			return this.startAttempted.get();
		}

		private boolean committed() {
			return this.committed;
		}

		private boolean openAdmission() {
			return this.mcpLifecycleAdapter == null
					? this.admissionFence.open()
					: this.mcpLifecycleAdapter.openExternallyCoordinatedAdmission(
							requireNonNull(this.mcpGeneration));
		}

		private void recordShutdownIntent() {
			if (this.mcpLifecycleAdapter != null && this.mcpGenerationCommitted)
				this.mcpLifecycleAdapter.recordExternallyCoordinatedShutdownIntent(
						requireNonNull(this.mcpGeneration));
		}

		private void finalizeAndPublishResult(
				@NonNull InternalShutdownResult result) {
			if (this.mcpLifecycleAdapter == null)
				return;
			if (!this.mcpGenerationCommitted) {
				this.mcpLifecycleAdapter.discardExternallyCoordinatedGeneration(
						requireNonNull(this.mcpGeneration));
				return;
			}
			InternalLifecycleComponentShutdownResult participantResult = requireNonNull(
					result).participantResult(InternalLifecycleComponentType.MCP)
					.orElseThrow(() -> new IllegalArgumentException(
							"The simulator result is missing its MCP participant"));
			Throwable finalizationFailure = this.mcpLifecycleAdapter
					.finalizeExternallyCoordinatedEvidence(
							requireNonNull(this.mcpGeneration), participantResult)
					.orElse(null);
			if (finalizationFailure != null)
				throw new IllegalStateException(
						"Unable to finalize MCP simulator lifecycle evidence",
						finalizationFailure);
			this.mcpLifecycleAdapter.publishExternallyCoordinatedResult(
					requireNonNull(this.mcpGeneration), result);
		}

		private void publishResultAfterFailure(
				@NonNull InternalShutdownResult result) {
			if (this.mcpLifecycleAdapter != null && this.mcpGenerationCommitted)
				this.mcpLifecycleAdapter
						.publishExternallyCoordinatedOwnerResultAfterFailure(
								requireNonNull(this.mcpGeneration),
								requireNonNull(result));
		}

		@Override
		public void beginStartupCall() {
			if (!this.phaseGate.claimStart())
				throw new IllegalStateException(
						"Simulator participant startup is already active");
		}

		@Override
		public void installStartupCall(
				TrackedLifecycleCallRunner.@NonNull Call<Void> call) {
			TrackedLifecycleCallRunner.Call<Void> exactCall = requireNonNull(call);
			this.startupCall.set(exactCall);
			if (!this.phaseGate.startupCallActive())
				this.startupCall.compareAndSet(exactCall, null);
		}

		@Override
		public void completeStartupCall() {
			this.startupCall.set(null);
			synchronized (this.phaseDeliveryLock) {
				ShutdownContext catchUp = this.phaseGate.completeStartCall();
				try {
					if (catchUp != null)
						applyShutdownPhase(catchUp);
				} catch (Throwable failure) {
					this.terminationGroup.signalFailure(
							this.terminationGroup.root(), failure);
				}
			}
			this.scope.waiter.signal();
		}

		@Override
		public void cancelStartupCall() {
			TrackedLifecycleCallRunner.Call<Void> call = this.startupCall.get();
			if (call != null)
				call.cancel();
		}

		@Override
		public boolean startupCallActive() {
			return this.phaseGate.startupCallActive();
		}

		@Override
		public void freezeForClassification() {
			synchronized (this.phaseDeliveryLock) {
				this.phaseGate.freezeForClassification();
				TrackedLifecycleCallRunner.Call<Void> observer =
						this.proofObservation.getAndSet(null);
				if (observer != null && !observer.isDone())
					observer.cancel();
			}
		}

		private void requestShutdownPhase(
				@NonNull ShutdownContext context) {
			synchronized (this.phaseDeliveryLock) {
				ShutdownContext delivery = this.phaseGate.requestPhase(
						requireNonNull(context));
				if (delivery != null)
					applyShutdownPhase(delivery);
			}
		}

		private void applyShutdownPhase(
				@NonNull ShutdownContext context) {
			if (this.kind != InternalLifecycleComponentType.MCP) {
				this.terminationSignal.signalTerminated();
				return;
			}
			DefaultSimulator simulator = this.scope.simulator();
			if (requireNonNull(context).getShutdownPhase() == ShutdownPhase.FORCED)
				simulator.forceMcpScope();
			else
				simulator.quiesceMcpScope();
			beginMcpProofObservation(context);
		}

		private void beginMcpProofObservation(
				@NonNull ShutdownContext context) {
			if (this.scope.simulator().mcpScopeTerminationProven()) {
				this.terminationSignal.signalTerminated();
				return;
			}
			ShutdownContext exactContext = requireNonNull(context);
			TrackedLifecycleCallRunner.Call<Void> previous =
					this.proofObservation.getAndSet(null);
			if (previous != null && !previous.isDone())
				previous.cancel();
			TrackedLifecycleCallRunner.Call<Void> observer;
			try {
				observer = this.scope.callRunner.submit(
						"simulator-mcp-termination-observer-"
								+ exactContext.getShutdownPhase().name().toLowerCase(Locale.ROOT),
						this.terminationGroup, () -> {
							try {
								if (this.scope.simulator().awaitMcpScopeTermination(
										exactContext.absoluteDeadlineNanos(),
										this.scope.clock))
									this.terminationSignal.signalTerminated();
							} catch (InterruptedException phaseAdvance) {
								// A phase advance or result freeze ends only this observation.
							}
							return null;
						});
			} catch (RuntimeException | Error launchFailure) {
				throw launchFailure;
			}
			this.proofObservation.set(observer);
			observer.completion().whenComplete((ignored, ignoredFailure) -> {
				this.proofObservation.compareAndSet(observer, null);
				this.scope.waiter.signal();
			});
			if (observer.isDone())
				this.proofObservation.compareAndSet(observer, null);
		}

		@NonNull
		@Override
		public InternalLifecycleComponentType kind() {
			return this.kind;
		}

		@NonNull
		@Override
		public AdmissionFence admissionFence() {
			return this.admissionFence;
		}

		@NonNull
		@Override
		public InternalTerminationGroup terminationGroup() {
			return this.terminationGroup;
		}

		@NonNull
		@Override
		public InternalTransportRuntime runtime() {
			return this.runtime;
		}

		@NonNull
		@Override
		public Set<InternalResidualActivityType> residualActivity() {
			return this.kind == InternalLifecycleComponentType.MCP
					? this.scope.simulator().mcpScopeResidualActivity() : Set.of();
		}

		private final class ScopeRuntime implements InternalTransportRuntime {
			@Override
			public void start(@NonNull StartupContext context) {
				requireNonNull(context);
				// HTTP and SSE have no off-network listener to start. MCP owns one
				// fresh application/executor/subscription generation before readiness.
				if (kind == InternalLifecycleComponentType.MCP) {
					McpTransportLifecycleAdapter adapter = requireNonNull(
							mcpLifecycleAdapter);
					McpTransportLifecycleAdapter.Generation generation =
							requireNonNull(mcpGeneration);
					adapter.runExternallyCoordinatedStart(generation, () -> {
						try {
							adapter.beginStart();
							scope.simulator().openMcpScope();
						} catch (RuntimeException | Error failure) {
							adapter.failedStart(generation, failure, false);
							throw failure;
						}
					});
					adapter.markReady(generation);
				}
			}

			@Override
			public void shutdownGracefully(@NonNull ShutdownContext context) {
				requestShutdownPhase(requireNonNull(context));
			}

			@Override
			public void shutdownForcibly(@NonNull ShutdownContext context) {
				requestShutdownPhase(requireNonNull(context));
			}
		}
	}

	@Immutable
	private record ResolvedScopeTransports(
			@NonNull Optional<MockHttpServer> httpServer,
			@NonNull Optional<MockSseServer> sseServer,
			@NonNull Optional<DefaultMcpServer> mcpServer) {
		private ResolvedScopeTransports {
			requireNonNull(httpServer);
			requireNonNull(sseServer);
			requireNonNull(mcpServer);
		}
	}
}

interface SimulatorMcpBuildRegistrar {
	void verifyBuildAllowed();

	void register(@NonNull DefaultMcpServer server);
}

interface SimulatorConfigurationScopeIdentity {
	@NonNull
	Object simulatorConfigurationIdentity();
}

interface SimulatorScopeDispatchGate {
	@NonNull
	Runnable enter(@NonNull InternalLifecycleComponentType kind);

	void seal();
}

@ThreadSafe
final class ScopeDispatchGate implements SimulatorScopeDispatchGate {
	@NonNull
	private final Map<InternalLifecycleComponentType, AdmissionFence> fences;
	@NonNull
	private final AtomicBoolean sealed;

	ScopeDispatchGate(@NonNull List<SokletSimulator.ScopeParticipant> participants) {
		EnumMap<InternalLifecycleComponentType, AdmissionFence> fences =
				new EnumMap<>(InternalLifecycleComponentType.class);
		for (SokletSimulator.ScopeParticipant participant
				: requireNonNull(participants))
			fences.put(participant.kind(), participant.admissionFence());
		this.fences = Collections.unmodifiableMap(fences);
		this.sealed = new AtomicBoolean();
	}

	@NonNull
	@Override
	public Runnable enter(@NonNull InternalLifecycleComponentType kind) {
		if (this.sealed.get())
			throw new IllegalStateException("The simulator scope is closed.");
		AdmissionFence fence = this.fences.get(requireNonNull(kind));
		if (fence == null)
			return () -> {
			};
		return fence.tryAdmit()
				.<Runnable>map(admission -> admission::close)
				.orElseThrow(() -> new IllegalStateException(
						"The simulator scope is closed."));
	}

	@Override
	public void seal() {
		if (!this.sealed.compareAndSet(false, true))
			return;
		for (AdmissionFence fence : this.fences.values())
			fence.close();
	}
}
