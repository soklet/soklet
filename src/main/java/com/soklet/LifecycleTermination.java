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

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

enum InternalLifecycleComponentType {
	HTTP,
	SSE,
	MCP,
	FRAMEWORK
}

enum InternalLifecycleComponentShutdownDisposition {
	NOT_STARTED,
	GRACEFUL_TERMINATION,
	FORCED_TERMINATION,
	UNEXPECTED_TERMINATION,
	RESIDUAL_ACTIVITY,
	TERMINATION_UNKNOWN
}

enum InternalShutdownDisposition {
	NOT_STARTED,
	GRACEFUL,
	FORCED,
	INCOMPLETE
}

enum InternalStartupDisposition {
	NOT_ATTEMPTED,
	READY,
	CANCELED,
	TIMED_OUT,
	FAILED
}

enum InternalResidualActivityType {
	CALLBACK,
	STREAM,
	CONNECTION,
	EVENT_LOOP,
	EXECUTOR_TASK,
	LIFECYCLE_CALL
}

@Immutable
final class InternalLifecycleComponentShutdownResult {
	@NonNull
	private final InternalLifecycleComponentType kind;
	@NonNull
	private final InternalLifecycleComponentShutdownDisposition disposition;
	@NonNull
	private final List<Throwable> failures;
	@NonNull
	private final Set<InternalResidualActivityType> residualActivity;

	InternalLifecycleComponentShutdownResult(@NonNull InternalLifecycleComponentType kind,
			@NonNull InternalLifecycleComponentShutdownDisposition disposition,
			@NonNull List<? extends Throwable> failures,
			@NonNull Set<InternalResidualActivityType> residualActivity) {
		this.kind = requireNonNull(kind);
		this.disposition = requireNonNull(disposition);
		this.failures = List.copyOf(requireNonNull(failures));
		this.residualActivity = Collections.unmodifiableSet(
				EnumSet.copyOf(requireNonNull(residualActivity).isEmpty()
						? EnumSet.noneOf(InternalResidualActivityType.class)
						: residualActivity));
	}

	@NonNull
	InternalLifecycleComponentType kind() {
		return this.kind;
	}

	@NonNull
	InternalLifecycleComponentShutdownDisposition disposition() {
		return this.disposition;
	}

	@NonNull
	List<Throwable> failures() {
		return this.failures;
	}

	@NonNull
	Set<InternalResidualActivityType> residualActivity() {
		return this.residualActivity;
	}
}

@Immutable
final class InternalShutdownResult {
	@NonNull
	private final InternalShutdownDisposition disposition;
	@NonNull
	private final InternalStartupDisposition startupDisposition;
	@NonNull
	private final List<InternalLifecycleComponentShutdownResult> participantResults;
	@NonNull
	private final Map<InternalLifecycleComponentType, InternalLifecycleComponentShutdownResult> participantResultsByKind;
	@Nullable
	private final LifecycleRetentionAnchor retentionAnchor;

	InternalShutdownResult(@NonNull InternalShutdownDisposition disposition,
			@NonNull InternalStartupDisposition startupDisposition,
			@NonNull List<InternalLifecycleComponentShutdownResult> participantResults) {
		this(disposition, startupDisposition, participantResults, null);
	}

	private InternalShutdownResult(@NonNull InternalShutdownDisposition disposition,
			@NonNull InternalStartupDisposition startupDisposition,
			@NonNull List<InternalLifecycleComponentShutdownResult> participantResults,
			@Nullable LifecycleRetentionAnchor retentionAnchor) {
		this.disposition = requireNonNull(disposition);
		this.startupDisposition = requireNonNull(startupDisposition);
		List<InternalLifecycleComponentShutdownResult> sorted = new ArrayList<>(
				requireNonNull(participantResults));
		sorted.sort(Comparator.comparing(InternalLifecycleComponentShutdownResult::kind));
		EnumMap<InternalLifecycleComponentType, InternalLifecycleComponentShutdownResult> indexed =
				new EnumMap<>(InternalLifecycleComponentType.class);
		for (InternalLifecycleComponentShutdownResult result : sorted) {
			if (indexed.put(result.kind(), result) != null)
				throw new IllegalArgumentException("Duplicate lifecycle participant kind: " + result.kind());
		}
		this.participantResults = List.copyOf(sorted);
		this.participantResultsByKind = Collections.unmodifiableMap(indexed);
		this.retentionAnchor = retentionAnchor;
	}

	@NonNull
	InternalShutdownDisposition disposition() {
		return this.disposition;
	}

	@NonNull
	InternalStartupDisposition startupDisposition() {
		return this.startupDisposition;
	}

	@NonNull
	List<InternalLifecycleComponentShutdownResult> participantResults() {
		return this.participantResults;
	}

	@NonNull
	Optional<InternalLifecycleComponentShutdownResult> participantResult(
			@NonNull InternalLifecycleComponentType kind) {
		return Optional.ofNullable(this.participantResultsByKind.get(requireNonNull(kind)));
	}

	boolean isComplete() {
		return getDisposition() != InternalShutdownDisposition.INCOMPLETE;
	}

	@NonNull
	InternalShutdownResult withRetentionAnchor(
			@NonNull LifecycleRetentionAnchor retentionAnchor) {
		if (this.retentionAnchor != null)
			throw new IllegalStateException(
					"Lifecycle retention evidence was already installed");
		return new InternalShutdownResult(this.disposition,
				this.startupDisposition, this.participantResults,
				requireNonNull(retentionAnchor));
	}

	@NonNull
	Optional<LifecycleRetentionSummary> retentionSummary() {
		return this.retentionAnchor == null ? Optional.empty()
				: Optional.of(LifecycleRetentionDiagnostics.read(
						this.retentionAnchor));
	}

	@NonNull
	private InternalShutdownDisposition getDisposition() {
		return this.disposition;
	}
}

final class InternalShutdownResultAggregator {
	@NonNull
	InternalShutdownResult aggregate(@NonNull InternalStartupDisposition startupDisposition,
			@NonNull List<InternalLifecycleComponentShutdownResult> participantResults) {
		requireNonNull(startupDisposition);
		requireNonNull(participantResults);
		InternalShutdownDisposition disposition = InternalShutdownDisposition.NOT_STARTED;
		for (InternalLifecycleComponentShutdownResult participantResult : participantResults) {
			switch (participantResult.disposition()) {
				case RESIDUAL_ACTIVITY, TERMINATION_UNKNOWN ->
						disposition = InternalShutdownDisposition.INCOMPLETE;
				case FORCED_TERMINATION -> {
					if (disposition != InternalShutdownDisposition.INCOMPLETE)
						disposition = InternalShutdownDisposition.FORCED;
				}
				case GRACEFUL_TERMINATION, UNEXPECTED_TERMINATION -> {
					if (disposition == InternalShutdownDisposition.NOT_STARTED)
						disposition = InternalShutdownDisposition.GRACEFUL;
				}
				case NOT_STARTED -> {
					// No aggregate escalation.
				}
			}
		}
		return new InternalShutdownResult(disposition, startupDisposition, participantResults);
	}
}

/** Readiness-gated, one-way admission fence with a tracked admitted-work count. */
@ThreadSafe
final class AdmissionFence {
	private enum State {
		PENDING,
		OPEN,
		CLOSED
	}

	@NonNull
	private final AtomicReference<State> state;
	@NonNull
	private final AtomicInteger admittedWork;
	@NonNull
	private final Runnable stateChanged;
	@NonNull
	private final List<Runnable> admittedWorkReleased;

	AdmissionFence() {
		this(true, () -> {
		});
	}

	AdmissionFence(@NonNull Runnable stateChanged) {
		this(true, stateChanged);
	}

	AdmissionFence(boolean initiallyOpen, @NonNull Runnable stateChanged) {
		this.state = new AtomicReference<>(initiallyOpen ? State.OPEN : State.PENDING);
		this.admittedWork = new AtomicInteger();
		this.stateChanged = requireNonNull(stateChanged);
		this.admittedWorkReleased = new CopyOnWriteArrayList<>();
	}

	@NonNull
	Optional<Admission> tryAdmit() {
		for (;;) {
			if (this.state.get() != State.OPEN)
				return Optional.empty();
			this.admittedWork.incrementAndGet();
			if (this.state.get() == State.OPEN)
				return Optional.of(new Admission(this));
			release();
		}
	}

	boolean open() {
		boolean changed = this.state.compareAndSet(State.PENDING, State.OPEN);
		if (changed)
			this.stateChanged.run();
		return changed;
	}

	boolean close() {
		for (;;) {
			State observed = this.state.get();
			if (observed == State.CLOSED)
				return false;
			if (this.state.compareAndSet(observed, State.CLOSED)) {
				this.stateChanged.run();
				return true;
			}
		}
	}

	boolean isOpen() {
		return this.state.get() == State.OPEN;
	}

	int admittedWorkCount() {
		return this.admittedWork.get();
	}

	void onAdmittedWorkReleased(@NonNull Runnable callback) {
		this.admittedWorkReleased.add(requireNonNull(callback));
	}

	private void release() {
		int remaining = this.admittedWork.decrementAndGet();
		if (remaining < 0)
			throw new IllegalStateException("Admission count underflow");
		try {
			this.stateChanged.run();
		} finally {
			if (remaining == 0) {
				for (Runnable callback : this.admittedWorkReleased)
					callback.run();
			}
		}
	}

	@ThreadSafe
	static final class Admission implements AutoCloseable {
		@NonNull
		private final AdmissionFence fence;
		@NonNull
		private final AtomicBoolean closed;

		private Admission(@NonNull AdmissionFence fence) {
			this.fence = requireNonNull(fence);
			this.closed = new AtomicBoolean();
		}

		@Override
		public void close() {
			if (this.closed.compareAndSet(false, true))
				this.fence.release();
		}
	}
}

/** Internal claim key behind one public, reference-identity-only transport token. */
@Immutable
final class InternalTransportIdentity {
	@NonNull
	private final TransportIdentity publicIdentity;

	private InternalTransportIdentity() {
		this.publicIdentity = new TransportIdentity(this);
	}

	@NonNull
	static InternalTransportIdentity create() {
		return new InternalTransportIdentity();
	}

	@NonNull
	TransportIdentity publicIdentity() {
		return this.publicIdentity;
	}
}

/** Permanent, weak-token ownership claims with atomic all-or-none acquisition. */
@ThreadSafe
final class TransportIdentityClaimRegistry {
	@NonNull
	private final Map<InternalTransportIdentity, StoredClaim> claims;

	TransportIdentityClaimRegistry() {
		this.claims = new WeakHashMap<>();
	}

	synchronized void claimAll(@NonNull List<InternalTransportIdentity> identities,
			@NonNull Object owner) {
		requireNonNull(identities);
		requireNonNull(owner);
		Set<InternalTransportIdentity> unique =
				Collections.newSetFromMap(new IdentityHashMap<>());
		for (InternalTransportIdentity identity : identities) {
			requireNonNull(identity, "identity");
			if (!unique.add(identity))
				throw new IllegalArgumentException("Transport identity appears more than once in one claim");
			if (this.claims.containsKey(identity))
				throw new IllegalStateException("Transport identity is already owned by another lifecycle");
		}
		for (InternalTransportIdentity identity : identities)
			this.claims.put(identity, new StoredClaim(null, null, new Object()));
	}

	synchronized void claimAllDescriptors(@NonNull List<ClaimDescriptor> descriptors,
			@NonNull Object owner) {
		requireNonNull(descriptors);
		requireNonNull(owner);
		List<ClaimDescriptor> exactDescriptors =
				new ArrayList<>(descriptors.size());
		Set<InternalTransportIdentity> unique =
				Collections.newSetFromMap(new IdentityHashMap<>());
		for (ClaimDescriptor descriptor : descriptors) {
			ClaimDescriptor exact = requireNonNull(descriptor, "descriptor");
			InternalTransportIdentity identity = requireNonNull(exact.identity(),
					"descriptor.identity()");
			requireNonNull(exact.participantKind(), "descriptor.participantKind()");
			requireNonNull(exact.transportClass(), "descriptor.transportClass()");
			if (!unique.add(identity))
				throw new IllegalArgumentException(
						"Transport identity appears more than once in one claim");
			exactDescriptors.add(exact);
		}
		for (ClaimDescriptor exact : exactDescriptors) {
			if (this.claims.containsKey(exact.identity()))
				throw new TransportOwnershipException(exact.participantKind(),
						exact.transportClass());
		}
		for (ClaimDescriptor descriptor : exactDescriptors)
			this.claims.put(descriptor.identity(), new StoredClaim(
					descriptor.participantKind(), descriptor.transportClass(),
					new Object()));
	}

	synchronized int retainedClaimCountForTests() {
		return this.claims.size();
	}

	record ClaimDescriptor(@NonNull InternalTransportIdentity identity,
			@NonNull InternalLifecycleComponentType participantKind,
			@NonNull Class<?> transportClass) {
		ClaimDescriptor {
			requireNonNull(identity);
			requireNonNull(participantKind);
			requireNonNull(transportClass);
		}
	}

	private record StoredClaim(@Nullable InternalLifecycleComponentType participantKind,
			@Nullable Class<?> transportClass, @NonNull Object marker) {
		private StoredClaim {
			requireNonNull(marker);
		}
	}
}

/** Descriptor-neutral runtime shape used until the combined public cutover. */
interface InternalTransportRuntime {
	void start(@NonNull StartupContext context);

	void shutdownGracefully(@NonNull ShutdownContext context);

	void shutdownForcibly(@NonNull ShutdownContext context);
}

/** Bridges a public custom runtime into the coordinator's private runtime shape. */
@ThreadSafe
final class InternalPublicTransportRuntime implements InternalTransportRuntime {
	@NonNull
	private final TransportRuntime runtime;

	InternalPublicTransportRuntime(@NonNull TransportRuntime runtime) {
		this.runtime = requireNonNull(runtime);
	}

	@NonNull
	TransportRuntime publicRuntime() {
		return this.runtime;
	}

	@Override
	public void start(@NonNull StartupContext context) {
		this.runtime.start(requireNonNull(context));
	}

	@Override
	public void shutdownGracefully(@NonNull ShutdownContext context) {
		this.runtime.shutdownGracefully(requireNonNull(context));
	}

	@Override
	public void shutdownForcibly(@NonNull ShutdownContext context) {
		this.runtime.shutdownForcibly(requireNonNull(context));
	}
}

interface InternalTransportEndpoint<H> {
	@NonNull
	InternalTransportIdentity identity();

	@NonNull
	InternalTransportRuntime attach(@NonNull InternalTransportAttachmentContext<H> context,
			@NonNull StartupContext startupContext);
}

/** Framework-owned, orthogonal first-failure and affirmative-proof capability. */
@ThreadSafe
final class InternalTransportTerminationSignal {
	@NonNull
	private final InternalTerminationGroup group;
	private final InternalTerminationGroup.@NonNull Member member;
	@NonNull
	private final TransportTerminationSignal publicSignal;

	InternalTransportTerminationSignal(@NonNull InternalTerminationGroup group,
			InternalTerminationGroup.@NonNull Member member) {
		this.group = requireNonNull(group);
		this.member = requireNonNull(member);
		this.publicSignal = new TransportTerminationSignal(this);
	}

	void signalTerminated() {
		this.group.signalTerminated(this.member);
	}

	void signalTerminationFailure(@NonNull Throwable cause) {
		this.group.signalFailure(this.member, requireNonNull(cause));
	}

	@NonNull
	TransportTerminationSignal publicSignal() {
		return this.publicSignal;
	}
}

@Immutable
final class InternalTerminationEvent {
	enum Type {
		FAILURE,
		PROOF
	}

	private final long sequence;
	@NonNull
	private final Type type;
	private final InternalTerminationGroup.@NonNull Member member;
	@Nullable
	private final Throwable cause;

	InternalTerminationEvent(long sequence, @NonNull Type type,
			InternalTerminationGroup.@NonNull Member member,
			@Nullable Throwable cause) {
		this.sequence = sequence;
		this.type = requireNonNull(type);
		this.member = requireNonNull(member);
		this.cause = cause;
	}

	long sequence() {
		return this.sequence;
	}

	@NonNull
	Type type() {
		return this.type;
	}

	InternalTerminationGroup.@NonNull Member member() {
		return this.member;
	}

	@NonNull
	Optional<Throwable> cause() {
		return Optional.ofNullable(this.cause);
	}
}

/**
 * One owner-wide ordering boundary among shutdown intent, synchronous startup
 * failure, and premature participant events.  Every event accepted before the
 * cutoff may remain a participant-local controlling event; only the first
 * becomes the owner's singular unexpected-termination event.  Events accepted
 * after the cutoff remain ordinary shutdown evidence.
 */
@ThreadSafe
final class InternalControllingEventElection {
	@Nullable
	private InternalTerminationEvent firstEvent;
	private boolean startupCallFailurePublished;
	private boolean shutdownIntentPublished;

	/** Returns whether this event linearized before another owner outcome. */
	synchronized boolean electBeforeShutdown(
			@NonNull InternalTerminationEvent event) {
		InternalTerminationEvent exactEvent = requireNonNull(event);
		if (this.startupCallFailurePublished || this.shutdownIntentPublished)
			return false;
		if (this.firstEvent == null)
			this.firstEvent = exactEvent;
		return true;
	}

	/**
	 * Publishes a genuine startup-call failure only when no earlier participant
	 * event or owner stop already controls startup.
	 */
	synchronized boolean electStartupCallFailure(
			@NonNull BooleanSupplier publication) {
		BooleanSupplier exactPublication = requireNonNull(publication);
		if (this.firstEvent != null || this.startupCallFailurePublished
				|| this.shutdownIntentPublished)
			return false;
		if (!exactPublication.getAsBoolean())
			return false;
		this.startupCallFailurePublished = true;
		return true;
	}

	/**
	 * Publishes the owner state transition and cutoff as one operation relative
	 * to participant event election.
	 */
	@NonNull
	synchronized <T> T publishShutdownIntent(
			@NonNull Supplier<@NonNull T> publication) {
		return publishShutdownIntent(() -> { }, publication);
	}

	/**
	 * Publishes the first owner-stop outcome and state transition atomically
	 * relative to participant events and startup-call failure election.
	 */
	@NonNull
	synchronized <T> T publishShutdownIntent(
			@NonNull Runnable outcomePublication,
			@NonNull Supplier<@NonNull T> statePublication) {
		Runnable exactOutcomePublication = requireNonNull(outcomePublication);
		Supplier<@NonNull T> exactStatePublication =
				requireNonNull(statePublication);
		if (this.firstEvent == null && !this.startupCallFailurePublished
				&& !this.shutdownIntentPublished)
			exactOutcomePublication.run();
		T result = requireNonNull(exactStatePublication.get());
		this.shutdownIntentPublished = true;
		return result;
	}

	@NonNull
	synchronized Optional<InternalTerminationEvent> firstEvent() {
		return Optional.ofNullable(this.firstEvent);
	}
}

/**
 * Root/child termination group.  Signals remain private while the topology is
 * open and are replayed in their group-local order only after commit.
 */
@ThreadSafe
final class InternalTerminationGroup {
	private static final int MAXIMUM_DIAGNOSTIC_MEMBERS = 16;
	private enum State {
		OPEN,
		COMMITTED,
		DISCARDED
	}

	@ThreadSafe
	static final class Member {
		private final int id;
		@NonNull
		private final List<Member> children;
		@Nullable
		private InternalTerminationEvent failure;
		@Nullable
		private InternalTerminationEvent failureDiagnostic;
		@Nullable
		private InternalTerminationEvent proof;
		@Nullable
		private InternalTerminationEvent attachDiagnostic;
		@Nullable
		private InternalTerminationEvent proofHandoffDiagnostic;
		@Nullable
		private CompletableFuture<Void> proofHandoff;
		@Nullable
		private CompletionStage<Void> proofView;
		private boolean proofHandoffStarted;

		private Member(int id) {
			this.id = id;
			this.children = new ArrayList<>();
		}

		int id() {
			return this.id;
		}
	}

	/** One immutable classification boundary for coordinator publication. */
	@Immutable
	static final class EvidenceSnapshot {
		private final boolean barrierComplete;
		private final int trackedLifecycleCalls;
		private final int admittedWork;
		@Nullable
		private final InternalTerminationEvent controllingEvent;
		@NonNull
		private final List<InternalTerminationEvent> primaryEvents;

		private EvidenceSnapshot(boolean barrierComplete,
				int trackedLifecycleCalls, int admittedWork,
				@Nullable InternalTerminationEvent controllingEvent,
				@NonNull List<InternalTerminationEvent> primaryEvents) {
			this.barrierComplete = barrierComplete;
			this.trackedLifecycleCalls = trackedLifecycleCalls;
			this.admittedWork = admittedWork;
			this.controllingEvent = controllingEvent;
			this.primaryEvents = List.copyOf(requireNonNull(primaryEvents));
		}

		boolean barrierComplete() {
			return this.barrierComplete;
		}

		int trackedLifecycleCalls() {
			return this.trackedLifecycleCalls;
		}

		int admittedWork() {
			return this.admittedWork;
		}

		@NonNull
		Optional<InternalTerminationEvent> controllingEvent() {
			return Optional.ofNullable(this.controllingEvent);
		}

		@NonNull
		List<InternalTerminationEvent> primaryEvents() {
			return this.primaryEvents;
		}
	}

	/** Aggregate-only member diagnostics; no signal or throwable escapes. */
	@Immutable
	record DiagnosticSummary(int memberCount, int failedMembers,
			int provenMembers, boolean truncated) {
		DiagnosticSummary {
			if (memberCount < 0 || failedMembers < 0 || provenMembers < 0)
				throw new IllegalArgumentException(
						"Termination member counts must be >= 0");
		}
	}

	@NonNull
	private final AdmissionFence admissionFence;
	@NonNull
	private final Runnable stateChanged;
	@NonNull
	private final LifecycleWorkers workers;
	@NonNull
	private final Object executionOwnerToken;
	@NonNull
	private final Member root;
	@NonNull
	private final List<Member> members;
	@NonNull
	private final AtomicReference<InternalTerminationEvent> controllingEvent;
	@Nullable
	private final InternalControllingEventElection ownerEventElection;
	@Nullable
	private EvidenceSnapshot frozenEvidence;
	private State state;
	private long nextSequence;
	private int trackedLifecycleCalls;
	private boolean shutdownIntent;
	private long shutdownIntentSequence;
	private boolean forceSubmissionClaimed;
	private boolean forceSubmissionResolved;

	InternalTerminationGroup(@NonNull AdmissionFence admissionFence,
			@NonNull Runnable stateChanged, @NonNull LifecycleWorkers workers) {
		this(admissionFence, stateChanged, workers,
				LifecycleExecutionContext.legacyOwnerToken(), null);
	}

	InternalTerminationGroup(@NonNull AdmissionFence admissionFence,
			@NonNull Runnable stateChanged, @NonNull LifecycleWorkers workers,
			@NonNull Object executionOwnerToken) {
		this(admissionFence, stateChanged, workers, executionOwnerToken, null);
	}

	InternalTerminationGroup(@NonNull AdmissionFence admissionFence,
			@NonNull Runnable stateChanged, @NonNull LifecycleWorkers workers,
			@NonNull Object executionOwnerToken,
			@Nullable InternalControllingEventElection ownerEventElection) {
		this.admissionFence = requireNonNull(admissionFence);
		this.stateChanged = requireNonNull(stateChanged);
		this.workers = requireNonNull(workers);
		this.executionOwnerToken = requireNonNull(executionOwnerToken);
		this.root = new Member(0);
		this.members = new ArrayList<>();
		this.members.add(this.root);
		this.controllingEvent = new AtomicReference<>();
		this.ownerEventElection = ownerEventElection;
		this.state = State.OPEN;
		this.admissionFence.onAdmittedWorkReleased(this::admittedWorkReleased);
	}

	@NonNull
	synchronized Member root() {
		return this.root;
	}

	@NonNull
	synchronized DiagnosticSummary diagnosticSummary() {
		int failed = 0;
		int proven = 0;
		int inspectedMembers = Math.min(this.members.size(),
				MAXIMUM_DIAGNOSTIC_MEMBERS);
		for (int index = 0; index < inspectedMembers; index++) {
			Member member = this.members.get(index);
			if (member.failure != null || member.failureDiagnostic != null
					|| member.attachDiagnostic != null
					|| member.proofHandoffDiagnostic != null)
				failed++;
			if (member.proof != null)
				proven++;
		}
		return new DiagnosticSummary(this.members.size(), failed, proven,
				this.members.size() > MAXIMUM_DIAGNOSTIC_MEMBERS);
	}

	@NonNull
	synchronized Member registerChild(@NonNull Member parent) {
		requireMember(parent);
		requireOpen();
		return registerChildWhileOpen(parent);
	}

	@Nullable
	synchronized Member consumeDelegationSlot(@NonNull Member parent,
			boolean lifecycleOwning) {
		requireMember(parent);
		if (this.state != State.OPEN)
			return null;
		return lifecycleOwning ? registerChildWhileOpen(parent) : parent;
	}

	synchronized boolean isOpen() {
		return this.state == State.OPEN;
	}

	void commit() {
		List<Member> ready;
		synchronized (this) {
			requireOpen();
			this.state = State.COMMITTED;
			List<InternalTerminationEvent> pending = primaryEventsInSequence();
			if (!pending.isEmpty()) {
				this.admissionFence.close();
				InternalTerminationEvent first = pending.get(0);
				if (!this.shutdownIntent || first.sequence() < this.shutdownIntentSequence)
					electControllingEvent(first);
			}
			ready = proofHandoffsReadyToStart();
		}
		startProofHandoffs(ready);
		this.stateChanged.run();
	}

	void discard() {
		synchronized (this) {
			if (this.state == State.DISCARDED)
				return;
			if (this.state != State.OPEN)
				throw new IllegalStateException("Committed termination group cannot be discarded");
			this.state = State.DISCARDED;
		}
		this.stateChanged.run();
	}

	void recordShutdownIntent() {
		synchronized (this) {
			if (this.state == State.DISCARDED)
				return;
			if (!this.shutdownIntent) {
				this.shutdownIntent = true;
				this.shutdownIntentSequence = ++this.nextSequence;
			}
			this.admissionFence.close();
		}
		this.stateChanged.run();
	}

	void signalFailure(@NonNull Member member, @NonNull Throwable cause) {
		requireNonNull(cause);
		boolean accepted = false;
		List<Member> ready = List.of();
		synchronized (this) {
			requireMember(member);
			if (this.state == State.DISCARDED || member.failure != null)
				return;
			InternalTerminationEvent event = new InternalTerminationEvent(++this.nextSequence,
					InternalTerminationEvent.Type.FAILURE, member, cause);
			member.failure = event;
			accepted = true;
			if (this.state == State.COMMITTED) {
				this.admissionFence.close();
				if (!this.shutdownIntent)
					electControllingEvent(event);
				ready = proofHandoffsReadyToStart();
			}
		}
		if (accepted) {
			startProofHandoffs(ready);
			this.stateChanged.run();
		}
	}

	/** Retains one bounded competing failure without racing evidence freeze. */
	synchronized boolean trySuppressFailureBeforeFreeze(@NonNull Member member,
			@NonNull Throwable primary, @NonNull Throwable secondary) {
		requireMember(member);
		Throwable exactPrimary = requireNonNull(primary);
		Throwable exactSecondary = requireNonNull(secondary);
		if (this.state == State.DISCARDED || this.frozenEvidence != null
				|| exactPrimary == exactSecondary || member.failure == null
				|| member.failure.cause().orElseThrow() != exactPrimary
				|| member.failureDiagnostic != null)
			return false;
		member.failureDiagnostic = new InternalTerminationEvent(
				++this.nextSequence, InternalTerminationEvent.Type.FAILURE,
				member, exactSecondary);
		exactPrimary.addSuppressed(exactSecondary);
		return true;
	}

	void signalTerminated(@NonNull Member member) {
		boolean accepted = false;
		List<Member> ready = List.of();
		synchronized (this) {
			requireMember(member);
			if (this.state == State.DISCARDED || member.proof != null)
				return;
			InternalTerminationEvent event = new InternalTerminationEvent(++this.nextSequence,
					InternalTerminationEvent.Type.PROOF, member, null);
			member.proof = event;
			accepted = true;
			if (this.state == State.COMMITTED) {
				this.admissionFence.close();
				if (!this.shutdownIntent)
					electControllingEvent(event);
				ready = proofHandoffsReadyToStart();
			}
		}
		if (accepted) {
			startProofHandoffs(ready);
			this.stateChanged.run();
		}
	}

	void recordSyntheticAttachFailure(@NonNull Member member,
			@NonNull Throwable cause) {
		requireNonNull(cause);
		synchronized (this) {
			requireMember(member);
			if (this.state == State.DISCARDED)
				return;
			InternalTerminationEvent event = new InternalTerminationEvent(++this.nextSequence,
					InternalTerminationEvent.Type.FAILURE, member, cause);
			if (member.failure == null)
				member.failure = event;
			else if (member.attachDiagnostic == null)
				member.attachDiagnostic = event;
		}
		this.stateChanged.run();
	}

	@NonNull
	CompletionStage<Void> subtreeProofStage(@NonNull Member member) {
		synchronized (this) {
			requireMember(member);
			if (this.state == State.DISCARDED)
				throw new IllegalStateException("Transport delegate attachment is no longer active");
			if (member.proofView != null)
				return member.proofView;
			member.proofHandoff = new CompletableFuture<>();
			if (subtreeBarrierComplete(member)) {
				member.proofHandoff.complete(null);
				member.proofHandoffStarted = true;
			}
			member.proofView = member.proofHandoff.minimalCompletionStage();
			return member.proofView;
		}
	}

	@NonNull
	TrackedLifecycleCall trackLifecycleCall() {
		synchronized (this) {
			if (this.state == State.DISCARDED)
				throw new IllegalStateException("Termination group is discarded");
			this.trackedLifecycleCalls++;
		}
		return new TrackedLifecycleCall(this);
	}

	synchronized boolean isBarrierComplete() {
		return barrierComplete(this.admissionFence.admittedWorkCount());
	}

	/** Linearizes a pending force submission against final termination proof. */
	synchronized boolean tryClaimForceSubmission() {
		if (this.forceSubmissionClaimed
				|| barrierComplete(this.admissionFence.admittedWorkCount()))
			return false;
		this.forceSubmissionClaimed = true;
		return true;
	}

	void resolveForceSubmission() {
		List<Member> ready;
		synchronized (this) {
			if (!this.forceSubmissionClaimed)
				throw new IllegalStateException(
						"Lifecycle force submission was not claimed");
			if (this.forceSubmissionResolved)
				return;
			this.forceSubmissionResolved = true;
			ready = proofHandoffsReadyToStart();
		}
		startProofHandoffs(ready);
		this.stateChanged.run();
	}

	boolean hasFailure() {
		synchronized (this) {
			return this.members.stream().anyMatch(member -> member.failure != null);
		}
	}

	@NonNull
	List<InternalTerminationEvent> primaryEventsInSequence() {
		synchronized (this) {
			List<InternalTerminationEvent> events = new ArrayList<>();
			for (Member member : this.members) {
				if (member.failure != null)
					events.add(member.failure);
				if (member.proof != null)
					events.add(member.proof);
			}
			events.sort(Comparator.comparingLong(InternalTerminationEvent::sequence));
			return List.copyOf(events);
		}
	}

	@NonNull
	Optional<InternalTerminationEvent> controllingEvent() {
		return Optional.ofNullable(this.controllingEvent.get());
	}

	private void electControllingEvent(
			@NonNull InternalTerminationEvent event) {
		InternalTerminationEvent exactEvent = requireNonNull(event);
		if (this.frozenEvidence != null || this.controllingEvent.get() != null)
			return;
		InternalControllingEventElection ownerElection =
				this.ownerEventElection;
		if (ownerElection != null
				&& !ownerElection.electBeforeShutdown(exactEvent))
			return;
		this.controllingEvent.compareAndSet(null, exactEvent);
	}

	/**
	 * Freezes the exact group/admission facts used by the terminal result.
	 * Later bounded signal diagnostics may still be retained by the group, but
	 * cannot change this cached classification boundary.
	 */
	@NonNull
	synchronized EvidenceSnapshot freezeEvidence() {
		if (this.frozenEvidence != null)
			return this.frozenEvidence;
		int admittedWork = this.admissionFence.admittedWorkCount();
		boolean barrierComplete = barrierComplete(admittedWork);
		this.frozenEvidence = new EvidenceSnapshot(barrierComplete,
				this.trackedLifecycleCalls, admittedWork,
				this.controllingEvent.get(), primaryEventsInSequence());
		return this.frozenEvidence;
	}

	private boolean barrierComplete(int admittedWork) {
		return this.state == State.COMMITTED
				&& this.trackedLifecycleCalls == 0
				&& admittedWork == 0
				&& (!this.forceSubmissionClaimed
						|| this.forceSubmissionResolved)
				&& this.members.stream().allMatch(member -> member.proof != null);
	}

	synchronized int memberCount() {
		return this.members.size();
	}

	synchronized int trackedLifecycleCallCount() {
		return this.trackedLifecycleCalls;
	}

	@NonNull
	Object executionOwnerToken() {
		return this.executionOwnerToken;
	}

	@NonNull
	synchronized Optional<InternalTerminationEvent> attachDiagnostic(@NonNull Member member) {
		requireMember(member);
		return Optional.ofNullable(member.attachDiagnostic);
	}

	private void releaseTrackedLifecycleCall() {
		List<Member> ready;
		synchronized (this) {
			if (this.trackedLifecycleCalls <= 0)
				throw new IllegalStateException("Tracked lifecycle call count underflow");
			this.trackedLifecycleCalls--;
			ready = proofHandoffsReadyToStart();
		}
		startProofHandoffs(ready);
		this.stateChanged.run();
	}

	private void admittedWorkReleased() {
		List<Member> ready;
		synchronized (this) {
			ready = proofHandoffsReadyToStart();
		}
		startProofHandoffs(ready);
	}

	private boolean subtreeBarrierComplete(@NonNull Member member) {
		return this.state == State.COMMITTED
				&& this.trackedLifecycleCalls == 0
				&& this.admissionFence.admittedWorkCount() == 0
				&& (!this.forceSubmissionClaimed
						|| this.forceSubmissionResolved)
				&& subtreeMembers(member).stream().allMatch(candidate -> candidate.proof != null);
	}

	@NonNull
	private List<Member> subtreeMembers(@NonNull Member member) {
		List<Member> result = new ArrayList<>();
		result.add(member);
		for (Member child : member.children)
			result.addAll(subtreeMembers(child));
		return result;
	}

	@NonNull
	private List<Member> proofHandoffsReadyToStart() {
		if (this.state != State.COMMITTED)
			return List.of();
		List<Member> result = new ArrayList<>();
		for (Member member : this.members) {
			if (member.proofHandoff != null && !member.proofHandoffStarted
					&& subtreeBarrierComplete(member)) {
				member.proofHandoffStarted = true;
				result.add(member);
			}
		}
		return result;
	}

	private void startProofHandoffs(@NonNull List<Member> members) {
		for (Member member : members) {
			CompletableFuture<Void> handoff = member.proofHandoff;
			if (handoff == null)
				continue;
			try {
				this.workers.start(LifecycleWorkers.Role.SUBTREE_PROOF_HANDOFF,
						"transport-subtree-proof", () -> handoff.complete(null));
			} catch (RuntimeException | Error launchFailure) {
				recordProofHandoffLaunchFailure(member, launchFailure);
			}
		}
	}

	private void recordProofHandoffLaunchFailure(@NonNull Member member,
			@NonNull Throwable cause) {
		boolean accepted = false;
		synchronized (this) {
			requireMember(member);
			if (this.state == State.DISCARDED)
				return;
			InternalTerminationEvent event = new InternalTerminationEvent(
					++this.nextSequence, InternalTerminationEvent.Type.FAILURE,
					member, requireNonNull(cause));
			if (member.failure == null) {
				member.failure = event;
				accepted = true;
			} else if (member.proofHandoffDiagnostic == null) {
				member.proofHandoffDiagnostic = event;
				accepted = true;
			}
			if (accepted && this.state == State.COMMITTED) {
				this.admissionFence.close();
				if (!this.shutdownIntent)
					electControllingEvent(event);
			}
		}
		if (accepted)
			this.stateChanged.run();
	}

	@NonNull
	private Member registerChildWhileOpen(@NonNull Member parent) {
		Member child = new Member(this.members.size());
		parent.children.add(child);
		this.members.add(child);
		return child;
	}

	private void requireOpen() {
		if (this.state != State.OPEN)
			throw new IllegalStateException("Termination group is not open");
	}

	private void requireMember(@NonNull Member member) {
		requireNonNull(member);
		if (!this.members.contains(member))
			throw new IllegalArgumentException("Foreign termination-group member");
	}

	@ThreadSafe
	static final class TrackedLifecycleCall implements AutoCloseable {
		@NonNull
		private final InternalTerminationGroup group;
		@NonNull
		private final AtomicBoolean closed;

		private TrackedLifecycleCall(@NonNull InternalTerminationGroup group) {
			this.group = requireNonNull(group);
			this.closed = new AtomicBoolean();
		}

		@Override
		public void close() {
			if (this.closed.compareAndSet(false, true))
				this.group.releaseTrackedLifecycleCall();
		}
	}
}

@Immutable
final class InternalTransportDelegateAttachment {
	@NonNull
	private final InternalTransportRuntime runtime;
	@NonNull
	private final InternalTerminationGroup group;
	private final InternalTerminationGroup.@NonNull Member member;

	InternalTransportDelegateAttachment(@NonNull InternalTransportRuntime runtime,
			@NonNull InternalTerminationGroup group,
			InternalTerminationGroup.@NonNull Member member) {
		this.runtime = requireNonNull(runtime);
		this.group = requireNonNull(group);
		this.member = requireNonNull(member);
	}

	@NonNull
	InternalTransportRuntime runtime() {
		return this.runtime;
	}

	@NonNull
	CompletionStage<@Nullable Void> whenTerminated() {
		return this.group.subtreeProofStage(this.member);
	}
}

/** Same-thread, dynamic-extent-only unary delegate mediation. */
@ThreadSafe
final class InternalTransportAttachmentContext<H> {
	@NonNull
	private final Object configuration;
	@NonNull
	private final H requestHandler;
	@NonNull
	private final InternalTransportIdentity configuredIdentity;
	@NonNull
	private final InternalTerminationGroup group;
	private final InternalTerminationGroup.@NonNull Member member;
	@NonNull
	private final InternalTransportTerminationSignal terminationSignal;
	@NonNull
	private final StartupContext startupContext;
	@NonNull
	private final AtomicBoolean mediationGuard;
	@Nullable
	private Thread activeThread;
	private boolean active;
	private boolean delegated;

	InternalTransportAttachmentContext(@NonNull Object configuration,
			@NonNull H requestHandler,
			@NonNull InternalTransportIdentity configuredIdentity,
			@NonNull InternalTerminationGroup group,
			InternalTerminationGroup.@NonNull Member member,
			@NonNull StartupContext startupContext) {
		this(configuration, requestHandler, configuredIdentity, group, member,
				new InternalTransportTerminationSignal(group, member), startupContext);
	}

	private InternalTransportAttachmentContext(@NonNull Object configuration,
			@NonNull H requestHandler,
			@NonNull InternalTransportIdentity configuredIdentity,
			@NonNull InternalTerminationGroup group,
			InternalTerminationGroup.@NonNull Member member,
			@NonNull InternalTransportTerminationSignal terminationSignal,
			@NonNull StartupContext startupContext) {
		this.configuration = requireNonNull(configuration);
		this.requestHandler = requireNonNull(requestHandler);
		this.configuredIdentity = requireNonNull(configuredIdentity);
		this.group = requireNonNull(group);
		this.member = requireNonNull(member);
		this.terminationSignal = requireNonNull(terminationSignal);
		this.startupContext = requireNonNull(startupContext);
		this.mediationGuard = new AtomicBoolean();
	}

	@NonNull
	Object configuration() {
		return this.configuration;
	}

	@NonNull
	H requestHandler() {
		return this.requestHandler;
	}

	@NonNull
	InternalTransportTerminationSignal terminationSignal() {
		return this.terminationSignal;
	}

	@NonNull
	InternalTransportRuntime attachTransparentDelegate(
			@NonNull InternalTransportEndpoint<H> delegate,
			@NonNull H delegateRequestHandler) {
		return attachDelegate(delegate, delegateRequestHandler, false,
				new DelegateAccess<>() {
					@Override
					public InternalTransportIdentity identity(
							@NonNull InternalTransportEndpoint<H> exactDelegate) {
						return exactDelegate.identity();
					}

					@Override
					public InternalTransportRuntime attach(
							@NonNull InternalTransportEndpoint<H> exactDelegate,
							@NonNull InternalTransportAttachmentContext<H> context,
							@NonNull StartupContext startupContext) {
						return exactDelegate.attach(context, startupContext);
					}
				}).runtime();
	}

	@NonNull
	InternalTransportDelegateAttachment attachTerminationOwningDelegate(
			@NonNull InternalTransportEndpoint<H> delegate,
			@NonNull H delegateRequestHandler) {
		return attachDelegate(delegate, delegateRequestHandler, true,
				new DelegateAccess<>() {
					@Override
					public InternalTransportIdentity identity(
							@NonNull InternalTransportEndpoint<H> exactDelegate) {
						return exactDelegate.identity();
					}

					@Override
					public InternalTransportRuntime attach(
							@NonNull InternalTransportEndpoint<H> exactDelegate,
							@NonNull InternalTransportAttachmentContext<H> context,
							@NonNull StartupContext startupContext) {
						return exactDelegate.attach(context, startupContext);
					}
				});
	}

	@NonNull
	@SuppressWarnings("unchecked")
	TransportRuntime attachTransparentHttpDelegate(@NonNull HttpServer delegate,
			HttpServer.@NonNull RequestHandler delegateRequestHandler) {
		InternalTransportDelegateAttachment attachment = attachDelegate(delegate,
				(H) delegateRequestHandler, false, new DelegateAccess<>() {
					@Override
					public InternalTransportIdentity identity(
							@NonNull HttpServer exactDelegate) {
						TransportIdentity identity = exactDelegate.getTransportIdentity();
						return identity == null ? null : identity.internalIdentity();
					}

					@Override
					public InternalTransportRuntime attach(@NonNull HttpServer exactDelegate,
							@NonNull InternalTransportAttachmentContext<H> context,
							@NonNull StartupContext startupContext) {
						TransportRuntime runtime = exactDelegate.attach(
								new HttpTransportAttachmentContext(
										(InternalTransportAttachmentContext<HttpServer.RequestHandler>)
												(InternalTransportAttachmentContext<?>) context),
								startupContext);
						return runtime == null ? null : new InternalPublicTransportRuntime(runtime);
					}
				});
		return ((InternalPublicTransportRuntime) attachment.runtime()).publicRuntime();
	}

	@NonNull
	@SuppressWarnings("unchecked")
	TransportDelegateAttachment attachLifecycleOwningHttpDelegate(
			@NonNull HttpServer delegate,
			HttpServer.@NonNull RequestHandler delegateRequestHandler) {
		InternalTransportDelegateAttachment attachment = attachDelegate(delegate,
				(H) delegateRequestHandler, true, new DelegateAccess<>() {
					@Override
					public InternalTransportIdentity identity(
							@NonNull HttpServer exactDelegate) {
						TransportIdentity identity = exactDelegate.getTransportIdentity();
						return identity == null ? null : identity.internalIdentity();
					}

					@Override
					public InternalTransportRuntime attach(@NonNull HttpServer exactDelegate,
							@NonNull InternalTransportAttachmentContext<H> context,
							@NonNull StartupContext startupContext) {
						TransportRuntime runtime = exactDelegate.attach(
								new HttpTransportAttachmentContext(
										(InternalTransportAttachmentContext<HttpServer.RequestHandler>)
												(InternalTransportAttachmentContext<?>) context),
								startupContext);
						return runtime == null ? null : new InternalPublicTransportRuntime(runtime);
					}
				});
		TransportRuntime runtime = ((InternalPublicTransportRuntime)
				attachment.runtime()).publicRuntime();
		return new TransportDelegateAttachment(runtime, attachment);
	}

	@NonNull
	@SuppressWarnings("unchecked")
	TransportRuntime attachTransparentSseDelegate(@NonNull SseServer delegate,
			SseServer.@NonNull RequestHandler delegateRequestHandler) {
		InternalTransportDelegateAttachment attachment = attachDelegate(delegate,
				(H) delegateRequestHandler, false, new DelegateAccess<>() {
					@Override
					public InternalTransportIdentity identity(
							@NonNull SseServer exactDelegate) {
						TransportIdentity identity = exactDelegate.getTransportIdentity();
						return identity == null ? null : identity.internalIdentity();
					}

					@Override
					public InternalTransportRuntime attach(@NonNull SseServer exactDelegate,
							@NonNull InternalTransportAttachmentContext<H> context,
							@NonNull StartupContext startupContext) {
						TransportRuntime runtime = exactDelegate.attach(
								new SseTransportAttachmentContext(
										(InternalTransportAttachmentContext<SseServer.RequestHandler>)
												(InternalTransportAttachmentContext<?>) context),
								startupContext);
						return runtime == null ? null : new InternalPublicTransportRuntime(runtime);
					}
				});
		return ((InternalPublicTransportRuntime) attachment.runtime()).publicRuntime();
	}

	@NonNull
	@SuppressWarnings("unchecked")
	TransportDelegateAttachment attachLifecycleOwningSseDelegate(
			@NonNull SseServer delegate,
			SseServer.@NonNull RequestHandler delegateRequestHandler) {
		InternalTransportDelegateAttachment attachment = attachDelegate(delegate,
				(H) delegateRequestHandler, true, new DelegateAccess<>() {
					@Override
					public InternalTransportIdentity identity(
							@NonNull SseServer exactDelegate) {
						TransportIdentity identity = exactDelegate.getTransportIdentity();
						return identity == null ? null : identity.internalIdentity();
					}

					@Override
					public InternalTransportRuntime attach(@NonNull SseServer exactDelegate,
							@NonNull InternalTransportAttachmentContext<H> context,
							@NonNull StartupContext startupContext) {
						TransportRuntime runtime = exactDelegate.attach(
								new SseTransportAttachmentContext(
										(InternalTransportAttachmentContext<SseServer.RequestHandler>)
												(InternalTransportAttachmentContext<?>) context),
								startupContext);
						return runtime == null ? null : new InternalPublicTransportRuntime(runtime);
					}
				});
		TransportRuntime runtime = ((InternalPublicTransportRuntime)
				attachment.runtime()).publicRuntime();
		return new TransportDelegateAttachment(runtime, attachment);
	}

	void activate() {
		synchronized (this) {
			if (this.active)
				throw new IllegalStateException("Transport attachment context is already active");
			this.active = true;
			this.activeThread = Thread.currentThread();
		}
	}

	void deactivate() {
		synchronized (this) {
			this.active = false;
			this.activeThread = null;
		}
	}

	@NonNull
	private <D> InternalTransportDelegateAttachment attachDelegate(
			@NonNull D delegate, @NonNull H delegateRequestHandler,
			boolean lifecycleOwning, @NonNull DelegateAccess<D, H> delegateAccess) {
		if (!this.mediationGuard.compareAndSet(false, true))
			throw inactiveException();

		try {
			synchronized (this) {
				requireActiveAttachThread();
				if (this.delegated)
					throw new IllegalStateException("Transport attachment context already delegated");
			}

			requireNonNull(delegate, "delegate");
			requireNonNull(delegateRequestHandler, "delegateRequestHandler");
			DelegateAccess<D, H> exactDelegateAccess = requireNonNull(delegateAccess);
			InternalTransportIdentity delegateIdentity = requireNonNull(
					exactDelegateAccess.identity(delegate),
					"delegate.getTransportIdentity()");
			if (delegateIdentity != this.configuredIdentity)
				throw new IllegalArgumentException(
						"Delegate transport identity does not match the configured transport graph");

			InternalTerminationGroup.Member childMember;
			synchronized (this) {
				requireActiveAttachThread();
				if (this.delegated)
					throw new IllegalStateException("Transport attachment context already delegated");
				childMember = this.group.consumeDelegationSlot(this.member,
						lifecycleOwning);
				if (childMember == null)
					throw inactiveException();
				this.delegated = true;
			}

			InternalTransportTerminationSignal childSignal = lifecycleOwning
					? new InternalTransportTerminationSignal(this.group, childMember)
					: this.terminationSignal;
			InternalTransportAttachmentContext<H> childContext =
					new InternalTransportAttachmentContext<>(this.configuration,
							delegateRequestHandler, this.configuredIdentity, this.group,
							childMember, childSignal, this.startupContext);
			InternalTransportRuntime runtime;
			childContext.activate();
			try (InternalTerminationGroup.TrackedLifecycleCall ignored =
						 this.group.trackLifecycleCall()) {
				try {
					runtime = exactDelegateAccess.attach(delegate, childContext,
							this.startupContext);
				} catch (RuntimeException | Error throwable) {
					this.group.recordSyntheticAttachFailure(childMember, throwable);
					throw throwable;
				}
			} finally {
				childContext.deactivate();
			}

			if (runtime == null) {
				IllegalStateException exception =
						new IllegalStateException("Delegate attach(...) returned null");
				this.group.recordSyntheticAttachFailure(childMember, exception);
				throw exception;
			}
			return new InternalTransportDelegateAttachment(runtime, this.group, childMember);
		} finally {
			this.mediationGuard.set(false);
		}
	}

	private interface DelegateAccess<D, H> {
		@Nullable
		InternalTransportIdentity identity(@NonNull D delegate);

		@Nullable
		InternalTransportRuntime attach(@NonNull D delegate,
				@NonNull InternalTransportAttachmentContext<H> context,
				@NonNull StartupContext startupContext);
	}

	private void requireActiveAttachThread() {
		if (!this.active || this.activeThread != Thread.currentThread() || !this.group.isOpen())
			throw inactiveException();
	}

	@NonNull
	private static IllegalStateException inactiveException() {
		return new IllegalStateException(
				"Transport delegate attachment is not active on this attach thread");
	}
}

/** Owns the open/commit/discard transition for one configured attachment. */
final class InternalTransportAttachmentSession<H> {
	@NonNull
	private final InternalTerminationGroup group;
	@NonNull
	private final InternalTransportAttachmentContext<H> rootContext;
	@NonNull
	private final StartupContext startupContext;

	InternalTransportAttachmentSession(@NonNull Object configuration,
			@NonNull H requestHandler, @NonNull InternalTransportIdentity identity,
			@NonNull StartupContext startupContext,
			@NonNull AdmissionFence admissionFence, @NonNull Runnable stateChanged,
			@NonNull LifecycleWorkers workers) {
		this(configuration, requestHandler, identity, startupContext, admissionFence,
				stateChanged, workers, LifecycleExecutionContext.legacyOwnerToken());
	}

	InternalTransportAttachmentSession(@NonNull Object configuration,
			@NonNull H requestHandler, @NonNull InternalTransportIdentity identity,
			@NonNull StartupContext startupContext,
			@NonNull AdmissionFence admissionFence, @NonNull Runnable stateChanged,
			@NonNull LifecycleWorkers workers, @NonNull Object executionOwnerToken) {
		this.group = new InternalTerminationGroup(admissionFence, stateChanged, workers,
				requireNonNull(executionOwnerToken));
		this.rootContext = new InternalTransportAttachmentContext<>(configuration,
				requestHandler, identity, this.group, this.group.root(), startupContext);
		this.startupContext = startupContext;
	}

	@NonNull
	InternalTransportRuntime attach(@NonNull InternalTransportEndpoint<H> endpoint) {
		requireNonNull(endpoint);
		this.rootContext.activate();
		try (InternalTerminationGroup.TrackedLifecycleCall ignored =
					 this.group.trackLifecycleCall()) {
			InternalTransportRuntime runtime = endpoint.attach(this.rootContext,
					this.startupContext);
			if (runtime == null)
				throw new IllegalStateException("Configured attach(...) returned null");
			this.group.commit();
			return runtime;
		} catch (RuntimeException | Error throwable) {
			this.group.discard();
			throw throwable;
		} finally {
			this.rootContext.deactivate();
		}
	}

	@NonNull
	InternalTerminationGroup group() {
		return this.group;
	}
}
