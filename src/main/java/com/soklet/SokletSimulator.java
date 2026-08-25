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
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.util.Objects.requireNonNull;

/** Descriptor-neutral draft of the isolated 4.x simulator owner. */
@ThreadSafe
final class SokletSimulator {
	@FunctionalInterface
	interface Body<E extends Throwable> {
		void run(@NonNull Simulator simulator) throws E;
	}

	private SokletSimulator() {
	}

	@NonNull
	static <E extends Throwable> InternalShutdownResult run(
			@NonNull SimulatorConfigFactory configFactory,
			@NonNull Body<E> body) throws E {
		return run(configFactory, SimulatorOptions.defaultInstance(), body);
	}

	@NonNull
	static <E extends Throwable> InternalShutdownResult run(
			@NonNull SimulatorConfigFactory configFactory,
			@NonNull SimulatorOptions options,
			@NonNull Body<E> body) throws E {
		return run(configFactory, options, body, NanoClock.system(),
				new LifecycleWorkers());
	}

	@NonNull
	static <E extends Throwable> InternalShutdownResult run(
			@NonNull SimulatorConfigFactory configFactory,
			@NonNull SimulatorOptions options,
			@NonNull Body<E> body,
			@NonNull NanoClock clock,
			@NonNull LifecycleWorkers workers) throws E {
		return new Scope(requireNonNull(options), requireNonNull(clock),
				requireNonNull(workers)).run(requireNonNull(configFactory),
				requireNonNull(body));
	}

	@SuppressWarnings("unchecked")
	private static <E extends Throwable> void throwBodyFailure(
			@NonNull Throwable failure) throws E {
		throw (E) requireNonNull(failure);
	}

	@NotThreadSafe
	private static final class Scope implements SimulatorTransports,
			SimulatorMcpBuildRegistrar {
		@NonNull
		private final SimulatorOptions options;
		@NonNull
		private final NanoClock clock;
		@NonNull
		private final LifecycleWorkers workers;
		@NonNull
		private final DeadlineWaiter waiter;
		@NonNull
		private final MockHttpServer httpServer;
		@NonNull
		private final MockSseServer sseServer;
		@NonNull
		private final InternalLifecycleStateMachine stateMachine;
		private boolean factoryOpen;
		private @Nullable DefaultMcpServer mcpServer;
		private @Nullable DefaultSimulator simulator;

		private Scope(@NonNull SimulatorOptions options, @NonNull NanoClock clock,
				@NonNull LifecycleWorkers workers) {
			this.options = requireNonNull(options);
			this.clock = requireNonNull(clock);
			this.workers = requireNonNull(workers);
			this.waiter = new DeadlineWaiter(clock);
			this.httpServer = new MockHttpServer();
			this.sseServer = new MockSseServer();
			this.stateMachine = new InternalLifecycleStateMachine();
			this.factoryOpen = true;
		}

		@NonNull
		private <E extends Throwable> InternalShutdownResult run(
				@NonNull SimulatorConfigFactory configFactory,
				@NonNull Body<E> body) throws E {
			SokletConfig config;
			try {
				config = requireNonNull(configFactory.create(this),
						"The simulator config factory returned null");
			} finally {
				sealFactory();
			}

			ResolvedScopeTransports resolved = validate(config);
			this.stateMachine.claimStart();
			// Construction performs attachment only.  It initializes the fresh mocks
			// and the fresh unbound MCP graph, never a caller-owned transport.
			Soklet.fromConfig(config);

			List<ScopeParticipant> participants = participants(resolved);
			ScopeDispatchGate dispatchGate = new ScopeDispatchGate(participants);
			this.simulator = new DefaultSimulator(
					resolved.httpServer().orElse(null),
					resolved.sseServer().orElse(null), this.options,
					resolved.mcpServer().orElse(null), dispatchGate);
			Throwable primaryFailure = null;
			InternalStartupDisposition startupDisposition =
					InternalStartupDisposition.FAILED;
			try {
				startParticipants(participants,
						config.getInternalLifecyclePolicy());
				if (!this.stateMachine.publishReady())
					throw new IllegalStateException(
							"The simulator scope could not publish readiness");
				startupDisposition = InternalStartupDisposition.READY;
				body.run(this.simulator);
			} catch (Throwable failure) {
				primaryFailure = failure;
			}

			TeardownOutcome teardown = teardown(participants,
					config.getInternalLifecyclePolicy(), startupDisposition);

			if (primaryFailure != null) {
				if (teardown.failure() != null)
					primaryFailure.addSuppressed(teardown.failure());
				SokletSimulator.<E>throwBodyFailure(primaryFailure);
			}
			if (teardown.failure() != null)
				throw teardown.failure();
			return teardown.result();
		}

		@NonNull
		@Override
		public synchronized HttpServer getHttpServer() {
			requireFactoryOpen();
			return this.httpServer;
		}

		@NonNull
		@Override
		public synchronized SseServer getSseServer() {
			requireFactoryOpen();
			return this.sseServer;
		}

		@Override
		public synchronized McpServer.@NonNull Builder newMcpServerBuilder(
				@NonNull Integer port) {
			requireFactoryOpen();
			return McpServer.withPort(requireNonNull(port))
					.simulatorBuildRegistrar(this);
		}

		@Override
		public synchronized void register(@NonNull DefaultMcpServer server) {
			requireFactoryOpen();
			if (this.mcpServer != null)
				throw new IllegalStateException(
						"A simulator scope may build at most one MCP server");
			DefaultMcpServer registered = requireNonNull(server);
			registered.claimSimulatorScope(this);
			this.mcpServer = registered;
		}

		private synchronized void sealFactory() {
			this.factoryOpen = false;
		}

		private synchronized void requireFactoryOpen() {
			if (!this.factoryOpen)
				throw new IllegalStateException(
						"The simulator transport factory scope is closed");
		}

		@NonNull
		private ResolvedScopeTransports validate(@NonNull SokletConfig config) {
			HttpServer configuredHttp = config.getHttpServer()
					.map(SokletConfig::unwrapHttpServer).orElse(null);
			if (configuredHttp != null && configuredHttp != this.httpServer)
				throw new IllegalStateException(
						"The simulator config contains a foreign HTTP transport");

			SseServer configuredSse = config.getSseServer()
					.map(SokletConfig::unwrapSseServer).orElse(null);
			if (configuredSse != null && configuredSse != this.sseServer)
				throw new IllegalStateException(
						"The simulator config contains a foreign SSE transport");

			McpServer configuredMcp = config.getMcpServer().orElse(null);
			DefaultMcpServer registeredMcp;
			synchronized (this) {
				registeredMcp = this.mcpServer;
			}
			if (configuredMcp != registeredMcp)
				throw new IllegalStateException(configuredMcp == null
						? "The scope-built MCP server was not installed in the simulator config"
						: "The simulator config contains a production or foreign MCP transport");
			if (registeredMcp != null && registeredMcp.isStarted())
				throw new IllegalStateException(
						"A simulator MCP transport must not be started");

			return new ResolvedScopeTransports(
					Optional.ofNullable((MockHttpServer) configuredHttp),
					Optional.ofNullable((MockSseServer) configuredSse),
					Optional.ofNullable(registeredMcp));
		}

		@NonNull
		private List<ScopeParticipant> participants(
				@NonNull ResolvedScopeTransports resolved) {
			List<ScopeParticipant> participants = new ArrayList<>(3);
			resolved.httpServer().ifPresent(ignored -> participants.add(
					new ScopeParticipant(InternalParticipantKind.HTTP, this)));
			resolved.sseServer().ifPresent(ignored -> participants.add(
					new ScopeParticipant(InternalParticipantKind.SSE, this)));
			resolved.mcpServer().ifPresent(ignored -> participants.add(
					new ScopeParticipant(InternalParticipantKind.MCP, this)));
			return List.copyOf(participants);
		}

		private void startParticipants(
				@NonNull List<ScopeParticipant> participants,
				@NonNull InternalLifecyclePolicy policy) {
			long now = this.clock.nanoTime();
			Optional<Long> startupDeadline = policy.startupTimeout()
					.map(duration -> LifecycleDeadlines.after(now, duration));
			long cancellationDeadline = LifecycleDeadlines.after(now,
					policy.startupCancellationTimeout());
			InternalStartupContext context = new InternalStartupContext(this.clock,
					startupDeadline, cancellationDeadline,
					this.stateMachine::shutdownRequested);
			for (ScopeParticipant participant : participants)
				participant.commitAndStart(context);
			for (ScopeParticipant participant : participants)
				participant.openAdmission();
		}

		@NonNull
		private TeardownOutcome teardown(
				@NonNull List<ScopeParticipant> participants,
				@NonNull InternalLifecyclePolicy policy,
				@NonNull InternalStartupDisposition startupDisposition) {
			InternalShutdownResult result = null;
			List<Throwable> failures = new ArrayList<>();
			try {
				this.stateMachine.requestShutdown();
			} catch (Throwable failure) {
				failures.add(failure);
			}
			try {
				requireNonNull(this.simulator).sealScope();
			} catch (Throwable failure) {
				failures.add(failure);
			}
			try {
				long intentNanos = this.clock.nanoTime();
				long gracefulDeadline = LifecycleDeadlines.after(intentNanos,
						policy.gracefulShutdownTimeout());
				long forcedDeadline = LifecycleDeadlines.after(gracefulDeadline,
						policy.forcedShutdownTimeout());
				InternalShutdownResult coordinated = new InternalLifecycleCoordinator(
						this.clock, this.waiter,
						new TrackedLifecycleCallRunner(this.workers)).shutdown(
						participants, gracefulDeadline, forcedDeadline);
				result = withStartupDisposition(coordinated, startupDisposition);
			} catch (InterruptedException failure) {
				Thread.currentThread().interrupt();
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
				result = incompleteResult(participants, startupDisposition,
						result, failures);

			if (result.isComplete()) {
				try {
					requireNonNull(this.simulator).releaseMcpScopeEvidence();
				} catch (Throwable failure) {
					failures.add(failure);
					result = incompleteResult(participants, startupDisposition,
							result, failures);
				}
			}

			Throwable cause = failures.isEmpty() ? null : failures.get(0);
			for (int index = 1; index < failures.size(); index++) {
				Throwable additional = failures.get(index);
				if (additional != cause)
					requireNonNull(cause).addSuppressed(additional);
			}
			ShutdownIncompleteException failure = result.isComplete() ? null
					: new ShutdownIncompleteException(result, this, cause);
			return new TeardownOutcome(result, failure);
		}

		@NonNull
		private InternalShutdownResult incompleteResult(
				@NonNull List<ScopeParticipant> participants,
				@NonNull InternalStartupDisposition startupDisposition,
				@Nullable InternalShutdownResult coordinated,
				@NonNull List<? extends Throwable> failures) {
			List<InternalParticipantShutdownResult> results;
			if (coordinated == null) {
				results = participants.stream()
						.map(participant -> new InternalParticipantShutdownResult(
								participant.kind(),
								InternalParticipantShutdownDisposition.TERMINATION_UNKNOWN,
								failures, participant.residualActivity()))
						.toList();
			} else {
				results = coordinated.participantResults();
			}
			return new InternalShutdownResult(InternalShutdownDisposition.INCOMPLETE,
					startupDisposition, results);
		}

		@NonNull
		private InternalShutdownResult withStartupDisposition(
				@NonNull InternalShutdownResult result,
				@NonNull InternalStartupDisposition startupDisposition) {
			return new InternalShutdownResult(result.disposition(), startupDisposition,
					result.participantResults());
		}

		@NonNull
		private DefaultSimulator simulator() {
			return requireNonNull(this.simulator,
					"The simulator runtime has not been attached");
		}
	}

	@Immutable
	private record TeardownOutcome(
			@NonNull InternalShutdownResult result,
			@Nullable ShutdownIncompleteException failure) {
		private TeardownOutcome {
			requireNonNull(result);
		}
	}

	static final class ScopeParticipant
			implements InternalLifecycleCoordinator.Participant {
		@NonNull
		private final InternalParticipantKind kind;
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

		private ScopeParticipant(@NonNull InternalParticipantKind kind,
				@NonNull Scope scope) {
			this.kind = requireNonNull(kind);
			this.scope = requireNonNull(scope);
			this.admissionFence = new AdmissionFence(false, scope.waiter::signal);
			this.terminationGroup = new InternalTerminationGroup(
					this.admissionFence, scope.waiter::signal, scope.workers);
			this.terminationSignal = new InternalTransportTerminationSignal(
					this.terminationGroup, this.terminationGroup.root());
			this.runtime = new ScopeRuntime();
		}

		private void commitAndStart(@NonNull InternalStartupContext context) {
			this.terminationGroup.commit();
			this.runtime.start(requireNonNull(context));
		}

		private void openAdmission() {
			if (!this.admissionFence.open())
				throw new IllegalStateException(
						"Simulator participant admission could not be opened");
		}

		@NonNull
		@Override
		public InternalParticipantKind kind() {
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
		public Set<InternalResidualActivityKind> residualActivity() {
			return this.kind == InternalParticipantKind.MCP
					? this.scope.simulator().mcpScopeResidualActivity() : Set.of();
		}

		private final class ScopeRuntime implements InternalTransportRuntime {
			@Override
			public void start(@NonNull InternalStartupContext context) {
				requireNonNull(context);
				// HTTP and SSE have no off-network listener to start. MCP owns one
				// fresh application/executor/subscription generation before readiness.
				if (kind == InternalParticipantKind.MCP)
					scope.simulator().openMcpScope();
			}

			@Override
			public void quiesce(@NonNull InternalShutdownContext context) {
				shutdown(requireNonNull(context), false);
			}

			@Override
			public void force(@NonNull InternalShutdownContext context) {
				shutdown(requireNonNull(context), true);
			}

			private void shutdown(@NonNull InternalShutdownContext context,
					boolean forced) {
				if (kind != InternalParticipantKind.MCP) {
					terminationSignal.signalTerminated();
					return;
				}
				DefaultSimulator simulator = scope.simulator();
				if (forced)
					simulator.forceMcpScope();
				else
					simulator.quiesceMcpScope();
				try {
					if (simulator.awaitMcpScopeTermination(context.remainingTime()))
						terminationSignal.signalTerminated();
				} catch (InterruptedException failure) {
					Thread.currentThread().interrupt();
					// The coordinator interrupts the grace observer when it advances
					// to force.  That is phase control, not transport failure.
				}
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

@FunctionalInterface
interface SimulatorConfigFactory {
	@NonNull
	SokletConfig create(@NonNull SimulatorTransports transports);
}

interface SimulatorTransports {
	@NonNull
	HttpServer getHttpServer();

	@NonNull
	SseServer getSseServer();

	McpServer.@NonNull Builder newMcpServerBuilder(@NonNull Integer port);
}

@FunctionalInterface
interface SimulatorMcpBuildRegistrar {
	void register(@NonNull DefaultMcpServer server);
}

interface SimulatorScopeDispatchGate {
	@NonNull
	Runnable enter(@NonNull InternalParticipantKind kind);

	void seal();
}

@ThreadSafe
final class ScopeDispatchGate implements SimulatorScopeDispatchGate {
	@NonNull
	private final Map<InternalParticipantKind, AdmissionFence> fences;
	@NonNull
	private final AtomicBoolean sealed;

	ScopeDispatchGate(@NonNull List<SokletSimulator.ScopeParticipant> participants) {
		EnumMap<InternalParticipantKind, AdmissionFence> fences =
				new EnumMap<>(InternalParticipantKind.class);
		for (SokletSimulator.ScopeParticipant participant
				: requireNonNull(participants))
			fences.put(participant.kind(), participant.admissionFence());
		this.fences = Collections.unmodifiableMap(fences);
		this.sealed = new AtomicBoolean();
	}

	@NonNull
	@Override
	public Runnable enter(@NonNull InternalParticipantKind kind) {
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

@Immutable
final class ShutdownIncompleteException extends IllegalStateException {
	@NonNull
	private final InternalShutdownResult shutdownResult;
	private final @Nullable Object retainedScopeEvidence;

	ShutdownIncompleteException(@NonNull InternalShutdownResult shutdownResult) {
		this(shutdownResult, null, null);
	}

	ShutdownIncompleteException(@NonNull InternalShutdownResult shutdownResult,
			@Nullable Object retainedScopeEvidence, @Nullable Throwable cause) {
		super("The simulator scope could not prove complete shutdown", cause);
		this.shutdownResult = requireNonNull(shutdownResult);
		this.retainedScopeEvidence = retainedScopeEvidence;
		if (shutdownResult.disposition() != InternalShutdownDisposition.INCOMPLETE)
			throw new IllegalArgumentException(
					"ShutdownIncompleteException requires an incomplete result");
	}

	@NonNull
	InternalShutdownResult getInternalShutdownResult() {
		return this.shutdownResult;
	}

	boolean retainsScopeEvidence(@NonNull Object candidate) {
		return this.retainedScopeEvidence == requireNonNull(candidate);
	}
}
