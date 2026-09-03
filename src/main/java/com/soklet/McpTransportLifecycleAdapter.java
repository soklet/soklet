/*
 * Copyright 2022-2026 Revetware LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.soklet;

import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.ThreadSafe;
import java.time.Duration;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

/**
 * Package-private MCP bridge into the common lifecycle foundation. Public MCP
 * construction remains sealed while lifecycle status, results, and metrics are
 * projected onto the shared Soklet 4.0 vocabulary.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class McpTransportLifecycleAdapter
		implements McpServerRuntimeBridge.LifecycleAdapter {
	@ThreadSafe
	static final class Generation
			implements McpServerRuntimeBridge.LifecycleAdapter.Generation,
			InternalLifecycleCoordinator.Participant {
		@NonNull
		private final McpTransportLifecycleAdapter owner;
		private final BuiltInTransportLifecycleAdapter.@NonNull Generation delegate;

		private Generation(@NonNull McpTransportLifecycleAdapter owner,
				BuiltInTransportLifecycleAdapter.@NonNull Generation delegate) {
			this.owner = requireNonNull(owner);
			this.delegate = requireNonNull(delegate);
		}

		@Override
		@NonNull
		public Optional<@NonNull Runnable> tryAdmit() {
			return this.owner.delegate.tryAdmit(this.delegate)
					.map(admission -> admission::close);
		}

		@Override
		public boolean shutdownRequested() {
			return this.owner.delegate.shutdownRequested(this.delegate);
		}

		@Override
		public boolean tracksAdmissionLifetime() {
			return true;
		}

		@Override
		public boolean coordinatorOwnsUnexpectedTermination() {
			return true;
		}

		@Override
		public void signalTerminationFailure(@NonNull Throwable cause) {
			this.owner.delegate.signalUnexpectedFailure(this.delegate,
					requireNonNull(cause));
		}

		@Override
		@NonNull
		public InternalLifecycleComponentType kind() {
			return this.delegate.kind();
		}

		@Override
		@NonNull
		public AdmissionFence admissionFence() {
			return this.delegate.admissionFence();
		}

		@Override
		@NonNull
		public InternalTerminationGroup terminationGroup() {
			return this.delegate.terminationGroup();
		}

		@Override
		@NonNull
		public InternalTransportRuntime runtime() {
			return this.delegate.runtime();
		}

		@Override
		@NonNull
		public Set<InternalResidualActivityType> residualActivity() {
			return this.delegate.residualActivity();
		}

		boolean startAttempted() {
			return this.delegate.startAttempted();
		}
	}

	@NonNull
	private final BuiltInTransportLifecycleAdapter delegate;
	@NonNull
	private final AtomicReference<@Nullable McpServerRuntimeBridge> runtime;
	@NonNull
	private final AtomicReference<@Nullable Generation> generation;
	@NonNull
	private final ThreadLocal<Generation> externalStartInvocation;

	McpTransportLifecycleAdapter(
			@NonNull Supplier<@NonNull Duration> gracefulTimeout,
			@NonNull Supplier<@NonNull Duration> forcedTimeout) {
		this.runtime = new AtomicReference<>();
		this.generation = new AtomicReference<>();
		this.externalStartInvocation = new ThreadLocal<>();
		this.delegate = new BuiltInTransportLifecycleAdapter(
				InternalLifecycleComponentType.MCP, new Operations(),
				requireNonNull(gracefulTimeout), requireNonNull(forcedTimeout));
	}

	/** Deterministic package-private lifecycle seam; production uses the runtime-bound form. */
	McpTransportLifecycleAdapter(@NonNull Duration gracefulTimeout,
			@NonNull Duration forcedTimeout, @NonNull NanoClock clock,
			@NonNull LifecycleWorkers workers,
			BuiltInTransportLifecycleAdapter.@NonNull Operations operations) {
		this.runtime = new AtomicReference<>();
		this.generation = new AtomicReference<>();
		this.externalStartInvocation = new ThreadLocal<>();
		this.delegate = new BuiltInTransportLifecycleAdapter(
				InternalLifecycleComponentType.MCP, requireNonNull(operations),
				() -> requireNonNull(gracefulTimeout), requireNonNull(forcedTimeout),
				requireNonNull(clock), requireNonNull(workers));
	}

	void bindRuntime(@NonNull McpServerRuntimeBridge runtimeBridge) {
		if (!this.runtime.compareAndSet(null, requireNonNull(runtimeBridge)))
			throw new IllegalStateException("MCP lifecycle runtime was already bound");
	}

	@NonNull
	synchronized Generation beginStart() {
		Generation externalGeneration = this.externalStartInvocation.get();
		BuiltInTransportLifecycleAdapter.Generation delegateGeneration =
				this.delegate.beginStart();
		if (externalGeneration != null) {
			requireCurrent(externalGeneration);
			if (externalGeneration.delegate != delegateGeneration)
				throw new IllegalStateException(
						"MCP lifecycle consumed a different external generation");
			return externalGeneration;
		}
		Generation next = new Generation(this, delegateGeneration);
		this.generation.set(next);
		return next;
	}

	@NonNull
	Generation newExternallyCoordinatedGeneration(
			@NonNull DeadlineWaiter waiter, @NonNull LifecycleWorkers workers,
			@NonNull Object executionOwnerToken,
			@NonNull Runnable externalShutdownRequested,
			@NonNull Runnable externalUnexpectedTermination) {
		return new Generation(this,
				this.delegate.newExternallyCoordinatedGeneration(
						requireNonNull(waiter), requireNonNull(workers),
						requireNonNull(executionOwnerToken),
						requireNonNull(externalShutdownRequested),
						requireNonNull(externalUnexpectedTermination)));
	}

	@NonNull
	Generation newExternallyCoordinatedGeneration(
			@NonNull DeadlineWaiter waiter, @NonNull LifecycleWorkers workers,
			@NonNull Object executionOwnerToken,
			@NonNull Runnable externalShutdownRequested,
			@NonNull Runnable externalUnexpectedTermination,
			@NonNull InternalControllingEventElection ownerEventElection) {
		return new Generation(this,
				this.delegate.newExternallyCoordinatedGeneration(
						requireNonNull(waiter), requireNonNull(workers),
					requireNonNull(executionOwnerToken),
					requireNonNull(externalShutdownRequested),
					requireNonNull(externalUnexpectedTermination),
					requireNonNull(ownerEventElection)));
	}

	synchronized void commitExternallyCoordinatedGeneration(
			@NonNull Generation exactGeneration) {
		requireOwned(exactGeneration);
		this.delegate.commitExternallyCoordinatedGeneration(
				exactGeneration.delegate);
		this.generation.set(exactGeneration);
	}

	void discardExternallyCoordinatedGeneration(
			@NonNull Generation exactGeneration) {
		requireOwned(exactGeneration);
		this.delegate.discardExternallyCoordinatedGeneration(
				exactGeneration.delegate);
	}

	void runExternallyCoordinatedStart(@NonNull Generation exactGeneration,
			@NonNull Runnable startAction) {
		requireCurrent(exactGeneration);
		if (this.externalStartInvocation.get() != null)
			throw new IllegalStateException(
					"Externally coordinated MCP start is already active on this thread");
		this.externalStartInvocation.set(exactGeneration);
		try {
			this.delegate.runExternallyCoordinatedStart(exactGeneration.delegate,
					requireNonNull(startAction));
		} finally {
			this.externalStartInvocation.remove();
		}
	}

	boolean openExternallyCoordinatedAdmission(
			@NonNull Generation exactGeneration) {
		requireCurrent(exactGeneration);
		return this.delegate.openExternallyCoordinatedAdmission(
				exactGeneration.delegate);
	}

	boolean recordExternallyCoordinatedShutdownIntent(
			@NonNull Generation exactGeneration) {
		requireCurrent(exactGeneration);
		return this.delegate.recordExternallyCoordinatedShutdownIntent(
				exactGeneration.delegate);
	}

	@NonNull
	Optional<Throwable> finalizeExternallyCoordinatedEvidence(
			@NonNull Generation exactGeneration,
			@NonNull InternalLifecycleComponentShutdownResult participantResult) {
		requireCurrent(exactGeneration);
		return this.delegate.finalizeExternallyCoordinatedEvidence(
				exactGeneration.delegate, requireNonNull(participantResult));
	}

	void publishExternallyCoordinatedResult(
			@NonNull Generation exactGeneration,
			@NonNull InternalShutdownResult exactResult) {
		requireCurrent(exactGeneration);
		this.delegate.publishExternallyCoordinatedResult(exactGeneration.delegate,
				requireNonNull(exactResult));
	}

	void publishExternallyCoordinatedOwnerResultAfterFailure(
			@NonNull Generation exactGeneration,
			@NonNull InternalShutdownResult exactResult) {
		requireOwned(exactGeneration);
		this.delegate.publishExternallyCoordinatedOwnerResultAfterFailure(
				exactGeneration.delegate, requireNonNull(exactResult));
	}

	void markReady(@NonNull Generation exactGeneration) {
		requireCurrent(exactGeneration);
		this.delegate.markReady(exactGeneration.delegate);
	}

	void failedStart(@NonNull Generation exactGeneration,
			@NonNull Throwable cause, boolean terminationProven) {
		requireCurrent(exactGeneration);
		this.delegate.failedStart(exactGeneration.delegate, requireNonNull(cause),
				terminationProven);
	}

	@Nullable
	Generation requestStop() {
		Generation exactGeneration = this.generation.get();
		if (exactGeneration == null)
			return null;
		BuiltInTransportLifecycleAdapter.Generation requested =
				this.delegate.requestStop();
		if (requested == null)
			return null;
		if (requested != exactGeneration.delegate)
			throw new IllegalStateException(
					"MCP lifecycle requested a different active generation");
		return exactGeneration;
	}

	void awaitStop(@Nullable Generation exactGeneration) {
		if (exactGeneration != null) {
			requireOwned(exactGeneration);
			this.delegate.awaitStop(exactGeneration.delegate);
		}
	}

	boolean shutdownInProgress() {
		return this.delegate.shutdownInProgress();
	}

	boolean hasActiveGeneration() {
		return this.generation.get() != null && this.delegate.result().isEmpty();
	}

	boolean admissionOpen() {
		Generation exactGeneration = this.generation.get();
		return exactGeneration != null
				&& this.delegate.admissionOpen(exactGeneration.delegate);
	}

	@NonNull
	Optional<InternalShutdownResult> result() {
		return this.delegate.result();
	}

	@NonNull
	Optional<InternalShutdownResult> result(@NonNull Generation exactGeneration) {
		requireOwned(exactGeneration);
		return this.delegate.result(exactGeneration.delegate);
	}

	@NonNull
	Optional<LifecycleRetentionSummary> retentionSummary() {
		return this.delegate.retentionSummary();
	}

	@NonNull
	InternalTransportIdentity identity() {
		return this.delegate.identity();
	}

	@Override
	public McpServerRuntimeBridge.LifecycleAdapter.@NonNull Generation currentGeneration() {
		return requireNonNull(this.generation.get(),
				"No MCP lifecycle generation is active");
	}

	private void requireCurrent(@NonNull Generation exactGeneration) {
		requireOwned(exactGeneration);
		if (this.generation.get() != exactGeneration)
			throw new IllegalStateException("Stale MCP lifecycle generation");
	}

	private void requireOwned(@NonNull Generation exactGeneration) {
		if (requireNonNull(exactGeneration).owner != this)
			throw new IllegalStateException("Foreign MCP lifecycle generation");
	}

	@NonNull
	private McpServerRuntimeBridge runtime() {
		return requireNonNull(this.runtime.get(), "MCP lifecycle runtime is not bound");
	}

	private final class Operations
			implements BuiltInTransportLifecycleAdapter.Operations {
		@Override
		public void quiesce() {
			runtime().quiesceLifecycle();
		}

		@Override
		public void shutdownGracefully(@NonNull ShutdownContext context) {
			runtime().quiesceLifecycle(
					requireNonNull(context).absoluteDeadlineNanos());
		}

		@Override
		public void force() {
			runtime().forceLifecycle();
		}

		@Override
		public void shutdownForcibly(@NonNull ShutdownContext context) {
			runtime().forceLifecycle(
					requireNonNull(context).absoluteDeadlineNanos());
		}

		@Override
		public boolean awaitTermination(long absoluteDeadlineNanos)
				throws InterruptedException {
			return runtime().awaitLifecycleTermination(absoluteDeadlineNanos);
		}

		@Override
		@NonNull
		public Set<InternalResidualActivityType> residualActivity() {
			McpServerRuntimeBridge.LifecycleEvidence evidence =
					runtime().getLifecycleEvidence();
			EnumSet<InternalResidualActivityType> residual =
					EnumSet.noneOf(InternalResidualActivityType.class);
			if (evidence.eventLoop())
				residual.add(InternalResidualActivityType.EVENT_LOOP);
			if (evidence.connection())
				residual.add(InternalResidualActivityType.CONNECTION);
			if (evidence.executorTask() || evidence.subscriptionRegistration())
				residual.add(InternalResidualActivityType.EXECUTOR_TASK);
			if (evidence.stream())
				residual.add(InternalResidualActivityType.STREAM);
			if (evidence.callback())
				residual.add(InternalResidualActivityType.CALLBACK);
			return Collections.unmodifiableSet(residual);
		}

		@Override
		public void releaseTerminatedEvidence() {
			runtime().releaseLifecycleEvidence();
		}
	}
}
