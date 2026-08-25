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

import static java.util.Objects.requireNonNull;

/**
 * Package-private MCP bridge into the common lifecycle foundation.  Public MCP
 * construction remains sealed and every public descriptor remains unchanged.
 */
@ThreadSafe
final class McpTransportLifecycleAdapter
		implements McpServerRuntimeBridge.LifecycleAdapter {
	@ThreadSafe
	static final class Generation
			implements McpServerRuntimeBridge.LifecycleAdapter.Generation {
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
		public void signalTerminationFailure(@NonNull Throwable cause) {
			this.owner.delegate.signalUnexpectedFailure(this.delegate,
					requireNonNull(cause));
		}
	}

	@NonNull
	private final BuiltInTransportLifecycleAdapter delegate;
	@NonNull
	private final AtomicReference<@Nullable McpServerRuntimeBridge> runtime;
	@NonNull
	private final AtomicReference<@Nullable Generation> generation;

	McpTransportLifecycleAdapter(@NonNull Duration gracefulTimeout) {
		this.runtime = new AtomicReference<>();
		this.generation = new AtomicReference<>();
		this.delegate = new BuiltInTransportLifecycleAdapter(
				InternalParticipantKind.MCP, new Operations(),
				() -> requireNonNull(gracefulTimeout));
	}

	void bindRuntime(@NonNull McpServerRuntimeBridge runtimeBridge) {
		if (!this.runtime.compareAndSet(null, requireNonNull(runtimeBridge)))
			throw new IllegalStateException("MCP lifecycle runtime was already bound");
	}

	@NonNull
	Generation beginStart() {
		Generation next = new Generation(this, this.delegate.beginStart());
		this.generation.set(next);
		return next;
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
		return requested == null ? null : exactGeneration;
	}

	void awaitStop(@Nullable Generation exactGeneration) {
		if (exactGeneration != null)
			this.delegate.awaitStop(exactGeneration.delegate);
	}

	boolean shutdownInProgress() {
		return this.delegate.shutdownInProgress();
	}

	boolean hasActiveGeneration() {
		return this.generation.get() != null && this.delegate.result().isEmpty();
	}

	@NonNull
	Optional<InternalShutdownResult> result() {
		return this.delegate.result();
	}

	@NonNull
	Optional<LifecycleRetentionSummary> retentionSummary() {
		return this.delegate.retentionSummary();
	}

	@Override
	public McpServerRuntimeBridge.LifecycleAdapter.@NonNull Generation currentGeneration() {
		return requireNonNull(this.generation.get(),
				"No MCP lifecycle generation is active");
	}

	private void requireCurrent(@NonNull Generation exactGeneration) {
		if (this.generation.get() != requireNonNull(exactGeneration))
			throw new IllegalStateException("Stale MCP lifecycle generation");
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
		public void force() {
			runtime().forceLifecycle();
		}

		@Override
		public boolean awaitTermination(long absoluteDeadlineNanos)
				throws InterruptedException {
			return runtime().awaitLifecycleTermination(absoluteDeadlineNanos);
		}

		@Override
		@NonNull
		public Set<InternalResidualActivityKind> residualActivity() {
			McpServerRuntimeBridge.LifecycleEvidence evidence =
					runtime().getLifecycleEvidence();
			EnumSet<InternalResidualActivityKind> residual =
					EnumSet.noneOf(InternalResidualActivityKind.class);
			if (evidence.eventLoop())
				residual.add(InternalResidualActivityKind.EVENT_LOOP);
			if (evidence.connection())
				residual.add(InternalResidualActivityKind.CONNECTION);
			if (evidence.executorTask() || evidence.subscriptionRegistration())
				residual.add(InternalResidualActivityKind.EXECUTOR_TASK);
			if (evidence.stream())
				residual.add(InternalResidualActivityKind.STREAM);
			if (evidence.callback())
				residual.add(InternalResidualActivityKind.CALLBACK);
			return Collections.unmodifiableSet(residual);
		}

		@Override
		public void releaseTerminatedEvidence() {
			runtime().releaseLifecycleEvidence();
		}
	}
}
