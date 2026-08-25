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
import java.lang.ref.WeakReference;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Objects.requireNonNull;

enum InternalParticipantKind {
	HTTP,
	SSE,
	MCP,
	FRAMEWORK_STARTUP
}

enum InternalParticipantShutdownDisposition {
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
	CANCELLED,
	TIMED_OUT,
	FAILED
}

enum InternalResidualActivityKind {
	CALLBACK,
	STREAM,
	CONNECTION,
	EVENT_LOOP,
	EXECUTOR_TASK,
	LIFECYCLE_CALL
}

@Immutable
final class InternalParticipantShutdownResult {
	@NonNull
	private final InternalParticipantKind kind;
	@NonNull
	private final InternalParticipantShutdownDisposition disposition;
	@NonNull
	private final List<Throwable> failures;
	@NonNull
	private final Set<InternalResidualActivityKind> residualActivity;

	InternalParticipantShutdownResult(@NonNull InternalParticipantKind kind,
			@NonNull InternalParticipantShutdownDisposition disposition,
			@NonNull List<? extends Throwable> failures,
			@NonNull Set<InternalResidualActivityKind> residualActivity) {
		this.kind = requireNonNull(kind);
		this.disposition = requireNonNull(disposition);
		this.failures = List.copyOf(requireNonNull(failures));
		this.residualActivity = Collections.unmodifiableSet(
				EnumSet.copyOf(requireNonNull(residualActivity).isEmpty()
						? EnumSet.noneOf(InternalResidualActivityKind.class)
						: residualActivity));
	}

	@NonNull
	InternalParticipantKind kind() {
		return this.kind;
	}

	@NonNull
	InternalParticipantShutdownDisposition disposition() {
		return this.disposition;
	}

	@NonNull
	List<Throwable> failures() {
		return this.failures;
	}

	@NonNull
	Set<InternalResidualActivityKind> residualActivity() {
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
	private final List<InternalParticipantShutdownResult> participantResults;
	@NonNull
	private final Map<InternalParticipantKind, InternalParticipantShutdownResult> participantResultsByKind;

	InternalShutdownResult(@NonNull InternalShutdownDisposition disposition,
			@NonNull InternalStartupDisposition startupDisposition,
			@NonNull List<InternalParticipantShutdownResult> participantResults) {
		this.disposition = requireNonNull(disposition);
		this.startupDisposition = requireNonNull(startupDisposition);
		List<InternalParticipantShutdownResult> sorted = new ArrayList<>(
				requireNonNull(participantResults));
		sorted.sort(Comparator.comparing(InternalParticipantShutdownResult::kind));
		EnumMap<InternalParticipantKind, InternalParticipantShutdownResult> indexed =
				new EnumMap<>(InternalParticipantKind.class);
		for (InternalParticipantShutdownResult result : sorted) {
			if (indexed.put(result.kind(), result) != null)
				throw new IllegalArgumentException("Duplicate lifecycle participant kind: " + result.kind());
		}
		this.participantResults = List.copyOf(sorted);
		this.participantResultsByKind = Collections.unmodifiableMap(indexed);
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
	List<InternalParticipantShutdownResult> participantResults() {
		return this.participantResults;
	}

	@NonNull
	Optional<InternalParticipantShutdownResult> participantResult(
			@NonNull InternalParticipantKind kind) {
		return Optional.ofNullable(this.participantResultsByKind.get(requireNonNull(kind)));
	}

	boolean isComplete() {
		return getDisposition() != InternalShutdownDisposition.INCOMPLETE;
	}

	@NonNull
	private InternalShutdownDisposition getDisposition() {
		return this.disposition;
	}
}

final class InternalShutdownResultAggregator {
	@NonNull
	InternalShutdownResult aggregate(@NonNull InternalStartupDisposition startupDisposition,
			@NonNull List<InternalParticipantShutdownResult> participantResults) {
		requireNonNull(startupDisposition);
		requireNonNull(participantResults);
		InternalShutdownDisposition disposition = InternalShutdownDisposition.NOT_STARTED;
		for (InternalParticipantShutdownResult participantResult : participantResults) {
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

	private void release() {
		int remaining = this.admittedWork.decrementAndGet();
		if (remaining < 0)
			throw new IllegalStateException("Admission count underflow");
		this.stateChanged.run();
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

/** Stable, reference-identity-only draft of the future public transport token. */
@Immutable
final class InternalTransportIdentity {
	private InternalTransportIdentity() {
	}

	@NonNull
	static InternalTransportIdentity create() {
		return new InternalTransportIdentity();
	}
}

/** Permanent, weak-token ownership claims with atomic all-or-none acquisition. */
@ThreadSafe
final class TransportIdentityClaimRegistry {
	@NonNull
	private final Map<InternalTransportIdentity, WeakReference<Object>> claims;

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
			WeakReference<Object> existing = this.claims.get(identity);
			if (existing == null)
				continue;
			Object existingOwner = existing.get();
			if (existingOwner != owner)
				throw new IllegalStateException("Transport identity is already owned by another lifecycle");
		}
		for (InternalTransportIdentity identity : identities)
			this.claims.put(identity, new WeakReference<>(owner));
	}

	synchronized int retainedClaimCountForTests() {
		return this.claims.size();
	}
}

/** Descriptor-neutral runtime shape used until the combined public cutover. */
interface InternalTransportRuntime {
	void start(@NonNull InternalStartupContext context);

	void quiesce(@NonNull InternalShutdownContext context);

	void force(@NonNull InternalShutdownContext context);
}

interface InternalTransportEndpoint<H> {
	@NonNull
	InternalTransportIdentity identity();

	@NonNull
	InternalTransportRuntime attach(@NonNull InternalTransportAttachmentContext<H> context,
			@NonNull InternalStartupContext startupContext);
}

/** Framework-owned, orthogonal first-failure and affirmative-proof capability. */
@ThreadSafe
final class InternalTransportTerminationSignal {
	@NonNull
	private final InternalTerminationGroup group;
	private final InternalTerminationGroup.@NonNull Member member;

	InternalTransportTerminationSignal(@NonNull InternalTerminationGroup group,
			InternalTerminationGroup.@NonNull Member member) {
		this.group = requireNonNull(group);
		this.member = requireNonNull(member);
	}

	void signalTerminated() {
		this.group.signalTerminated(this.member);
	}

	void signalTerminationFailure(@NonNull Throwable cause) {
		this.group.signalFailure(this.member, requireNonNull(cause));
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
 * Root/child termination group.  Signals remain private while the topology is
 * open and are replayed in their group-local order only after commit.
 */
@ThreadSafe
final class InternalTerminationGroup {
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
		private InternalTerminationEvent proof;
		@Nullable
		private InternalTerminationEvent attachDiagnostic;
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

	@NonNull
	private final AdmissionFence admissionFence;
	@NonNull
	private final Runnable stateChanged;
	@NonNull
	private final LifecycleWorkers workers;
	@NonNull
	private final Member root;
	@NonNull
	private final List<Member> members;
	@NonNull
	private final AtomicReference<InternalTerminationEvent> controllingEvent;
	private State state;
	private long nextSequence;
	private int trackedLifecycleCalls;
	private boolean shutdownIntent;
	private long shutdownIntentSequence;

	InternalTerminationGroup(@NonNull AdmissionFence admissionFence,
			@NonNull Runnable stateChanged, @NonNull LifecycleWorkers workers) {
		this.admissionFence = requireNonNull(admissionFence);
		this.stateChanged = requireNonNull(stateChanged);
		this.workers = requireNonNull(workers);
		this.root = new Member(0);
		this.members = new ArrayList<>();
		this.members.add(this.root);
		this.controllingEvent = new AtomicReference<>();
		this.state = State.OPEN;
	}

	@NonNull
	synchronized Member root() {
		return this.root;
	}

	@NonNull
	synchronized Member registerChild(@NonNull Member parent) {
		requireMember(parent);
		requireOpen();
		Member child = new Member(this.members.size());
		parent.children.add(child);
		this.members.add(child);
		return child;
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
					this.controllingEvent.compareAndSet(null, first);
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
					this.controllingEvent.compareAndSet(null, event);
				ready = proofHandoffsReadyToStart();
			}
		}
		if (accepted) {
			startProofHandoffs(ready);
			this.stateChanged.run();
		}
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
					this.controllingEvent.compareAndSet(null, event);
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

	boolean isBarrierComplete() {
		synchronized (this) {
			return this.state == State.COMMITTED
					&& this.trackedLifecycleCalls == 0
					&& this.admissionFence.admittedWorkCount() == 0
					&& this.members.stream().allMatch(member -> member.proof != null);
		}
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

	synchronized int memberCount() {
		return this.members.size();
	}

	synchronized int trackedLifecycleCallCount() {
		return this.trackedLifecycleCalls;
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

	private boolean subtreeBarrierComplete(@NonNull Member member) {
		return this.state == State.COMMITTED
				&& this.trackedLifecycleCalls == 0
				&& this.admissionFence.admittedWorkCount() == 0
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
			if (handoff != null)
				this.workers.start(LifecycleWorkers.Role.SUBTREE_PROOF_HANDOFF,
						"transport-subtree-proof", () -> handoff.complete(null));
		}
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
	CompletionStage<Void> whenTerminated() {
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
	private final InternalStartupContext startupContext;
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
			@NonNull InternalStartupContext startupContext) {
		this.configuration = requireNonNull(configuration);
		this.requestHandler = requireNonNull(requestHandler);
		this.configuredIdentity = requireNonNull(configuredIdentity);
		this.group = requireNonNull(group);
		this.member = requireNonNull(member);
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
		return new InternalTransportTerminationSignal(this.group, this.member);
	}

	@NonNull
	InternalTransportRuntime attachTransparentDelegate(
			@NonNull InternalTransportEndpoint<H> delegate,
			@NonNull H delegateRequestHandler) {
		return attachDelegate(delegate, delegateRequestHandler, false).runtime();
	}

	@NonNull
	InternalTransportDelegateAttachment attachLifecycleOwningDelegate(
			@NonNull InternalTransportEndpoint<H> delegate,
			@NonNull H delegateRequestHandler) {
		return attachDelegate(delegate, delegateRequestHandler, true);
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
	private InternalTransportDelegateAttachment attachDelegate(
			@NonNull InternalTransportEndpoint<H> delegate,
			@NonNull H delegateRequestHandler, boolean lifecycleOwning) {
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
			InternalTransportIdentity delegateIdentity = requireNonNull(delegate.identity(),
					"delegate.getTransportIdentity()");
			if (delegateIdentity != this.configuredIdentity)
				throw new IllegalArgumentException(
						"Delegate transport identity does not match the configured transport graph");

			InternalTerminationGroup.Member childMember;
			synchronized (this) {
				requireActiveAttachThread();
				if (this.delegated)
					throw new IllegalStateException("Transport attachment context already delegated");
				this.delegated = true;
				childMember = lifecycleOwning ? this.group.registerChild(this.member) : this.member;
			}

			InternalTransportAttachmentContext<H> childContext =
					new InternalTransportAttachmentContext<>(this.configuration,
							delegateRequestHandler, this.configuredIdentity, this.group,
							childMember, this.startupContext);
			InternalTransportRuntime runtime;
			childContext.activate();
			try (InternalTerminationGroup.TrackedLifecycleCall ignored =
							 this.group.trackLifecycleCall()) {
				try {
					runtime = delegate.attach(childContext, this.startupContext);
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
	private final InternalStartupContext startupContext;

	InternalTransportAttachmentSession(@NonNull Object configuration,
			@NonNull H requestHandler, @NonNull InternalTransportIdentity identity,
			@NonNull InternalStartupContext startupContext,
			@NonNull AdmissionFence admissionFence, @NonNull Runnable stateChanged,
			@NonNull LifecycleWorkers workers) {
		this.group = new InternalTerminationGroup(admissionFence, stateChanged, workers);
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
