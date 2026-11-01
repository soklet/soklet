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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.util.Objects.requireNonNull;

/**
 * Internal-first publication of readiness and the complete process lifecycle.
 * A daemon handoff is reserved before transports are claimed, so publishing an
 * outcome never creates threads or invokes application callbacks on the owner.
 */
final class SokletRunCompletion {
	private final CountDownLatch ready = new CountDownLatch(1);
	private final CountDownLatch finished = new CountDownLatch(1);
	private final CompletableFuture<ShutdownResult> publicCompletion = new CompletableFuture<>();
	private final CompletionStage<ShutdownResult> publicView =
			this.publicCompletion.minimalCompletionStage();
	private final Object cleanupOwnerToken = new Object();
	private final AtomicBoolean publicationClaimed = new AtomicBoolean();
	private volatile @Nullable Soklet soklet;
	private volatile @Nullable ShutdownResult result;
	private volatile @Nullable Throwable failure;
	private volatile boolean reachedReadiness;
	private volatile boolean startupCancellationRequested;

	SokletRunCompletion(LifecycleWorkers.@NonNull Launcher handoffLauncher) {
		requireNonNull(handoffLauncher).launch("soklet-finalization-result-handoff",
				this::publishAsyncOutcome);
	}

	static void launchDaemonHandoff(@NonNull String name, @NonNull Runnable task) {
		Thread handoff = new Thread(requireNonNull(task), requireNonNull(name));
		handoff.setDaemon(true);
		handoff.start();
	}

	void cancelStartup() {
		this.startupCancellationRequested = true;
		Soklet bound = this.soklet;
		if (bound != null)
			bound.getDirectLifecycle().requestStartupCancellation();
	}

	void bind(@NonNull Soklet soklet) {
		this.soklet = requireNonNull(soklet);
		soklet.attachRunCompletion(this);
		// Either binding or cancellation observes the other's volatile publication.
		if (this.startupCancellationRequested)
			soklet.getDirectLifecycle().requestStartupCancellation();
	}

	void publishReady() {
		requireNonNull(this.soklet);
		this.reachedReadiness = true;
		this.ready.countDown();
	}

	@NonNull
	Soklet awaitReady() {
		boolean interrupted = false;
		try {
			for (;;) {
				try {
					this.ready.await();
					break;
				} catch (InterruptedException interruption) {
					interrupted = true;
					if (this.ready.getCount() != 0L)
						cancelStartup();
				}
			}
			if (!this.reachedReadiness)
				throwFailure(requireNonNull(this.failure));
			return requireNonNull(this.soklet);
		} finally {
			if (interrupted)
				Thread.currentThread().interrupt();
		}
	}

	void finish(@Nullable ShutdownResult result, @Nullable Throwable failure) {
		if (failure == null)
			requireNonNull(result);
		if (!this.publicationClaimed.compareAndSet(false, true))
			throw new IllegalStateException("Finalization was already published");
		this.result = result;
		this.failure = failure;
		// Only the reserved daemon may dispatch user CompletionStage callbacks.
		this.finished.countDown();
		this.ready.countDown();
	}

	private void publishAsyncOutcome() {
		boolean interrupted = false;
		try {
			for (;;) {
				try {
					this.finished.await();
					break;
				} catch (InterruptedException interruption) {
					interrupted = true;
				}
			}
		} finally {
			if (interrupted)
				Thread.currentThread().interrupt();
		}
		Throwable terminalFailure = this.failure;
		if (terminalFailure == null)
			this.publicCompletion.complete(requireNonNull(this.result));
		else
			this.publicCompletion.completeExceptionally(terminalFailure);
	}

	@NonNull
	ShutdownResult await() throws InterruptedException {
		if (this.finished.getCount() != 0L) {
			LifecycleExecutionContext.requireNonReentrantWait(this.cleanupOwnerToken);
			LifecycleExecutionContext.requireNonReentrantWait(
					requireNonNull(this.soklet).getDirectLifecycle().executionOwnerToken());
		}
		this.finished.await();
		Throwable terminalFailure = this.failure;
		if (terminalFailure != null)
			throwFailure(terminalFailure);
		return requireNonNull(this.result);
	}

	@NonNull
	CompletionStage<ShutdownResult> async() {
		return this.publicView;
	}

	@NonNull
	ShutdownCleanup guardCleanup(@NonNull ShutdownCleanup cleanup) {
		return ShutdownCleanup.fromTimeoutAndAction(cleanup.getTimeout(), result -> {
			try (LifecycleExecutionContext.Scope ignored =
						LifecycleExecutionContext.enter(this.cleanupOwnerToken)) {
				cleanup.action().performCleanup(result);
			}
		});
	}

	private static void throwFailure(@NonNull Throwable failure) {
		if (failure instanceof RuntimeException runtimeFailure)
			throw runtimeFailure;
		if (failure instanceof Error error)
			throw error;
		throw new IllegalStateException("Soklet lifecycle failed", failure);
	}
}
