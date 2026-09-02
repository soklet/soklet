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
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Objects.requireNonNull;

/** Cross-transport isolation coverage for a blocked subtree-proof callback. */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
final class SokletDirectCompositionIsolationTests {
	@Test
	void blockedProofCallbackDoesNotStopIndependentTransportProgress()
			throws Throwable {
		ProgressOrder progressOrder = new ProgressOrder();
		AlternativeHttpEngine httpEngine = new AlternativeHttpEngine();
		BlockingProofHttpDecorator http =
				new BlockingProofHttpDecorator(httpEngine, progressOrder);
		IndependentSseEngine sse = new IndependentSseEngine(progressOrder);
		SokletConfig config = SokletConfig.withHttpServer(http)
				.sseServer(sse)
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(
						Set.of(InertResource.class)))
				.internalLifecyclePolicy(new InternalLifecyclePolicy(
						Optional.of(Duration.ofSeconds(2)),
						Duration.ofMillis(100), Duration.ofSeconds(2),
						Duration.ofMillis(500)))
				.build();
		Soklet soklet = Soklet.fromConfig(config);
		ExecutorService executor = Executors.newSingleThreadExecutor();
		InternalShutdownResult frozenResult;
		Throwable primaryFailure = null;
		try {
			soklet.start();

			Future<Throwable> stopping = executor.submit(
					() -> captureFailure(soklet::close));
			Assertions.assertTrue(httpEngine.awaitChildProof(2, TimeUnit.SECONDS),
					"The private child must publish proof during quiesce");
			Assertions.assertTrue(http.awaitQuiesceReturned(2, TimeUnit.SECONDS));
			Assertions.assertTrue(http.awaitCallbackEntered(2, TimeUnit.SECONDS),
					"The committed child proof must enter its handoff callback");
			Assertions.assertTrue(http.childProofStage().toCompletableFuture()
					.isDone(),
					"The private subtree stage must complete before its callback returns");
			Assertions.assertFalse(http.callbackReturned(),
					"The negative fixture must still own the blocked callback");
			Assertions.assertFalse(http.rootProofPublished(),
					"The blocked callback intentionally withholds outer proof");

			Assertions.assertTrue(sse.awaitQuiesceReturned(2, TimeUnit.SECONDS),
					"An independent transport must receive quiesce promptly");
			Assertions.assertTrue(sse.proofPublished(),
					"Independent graceful proof must not wait for the HTTP callback");
			Assertions.assertTrue(http.awaitForceReturned(4, TimeUnit.SECONDS),
					"The unresolved configured graph must progress to force");
			Assertions.assertTrue(httpEngine.awaitForceReturned(4, TimeUnit.SECONDS),
					"Force must continue through the blocked graph to its child");
			Assertions.assertTrue(progressOrder.sseQuiesceOrder()
					> progressOrder.callbackEnteredOrder(),
					"SSE quiesce/proof must progress after the callback is blocked");
			Assertions.assertTrue(progressOrder.httpForceOrder()
					> progressOrder.callbackEnteredOrder(),
					"HTTP force must progress after the callback is blocked");

			Throwable stopFailure = stopping.get(5, TimeUnit.SECONDS);
			ShutdownIncompleteException incomplete = Assertions.assertInstanceOf(
					ShutdownIncompleteException.class, stopFailure);
			frozenResult = soklet.getDirectLifecycle().result().orElseThrow();
			Assertions.assertSame(frozenResult,
					incomplete.getInternalShutdownResult());
			Assertions.assertEquals(InternalStartupDisposition.READY,
					frozenResult.startupDisposition());
			Assertions.assertEquals(InternalShutdownDisposition.INCOMPLETE,
					frozenResult.disposition());
			Assertions.assertFalse(frozenResult.isComplete());
			Assertions.assertEquals(2, frozenResult.participantResults().size());
			Assertions.assertEquals(List.of(InternalLifecycleComponentType.HTTP,
					InternalLifecycleComponentType.SSE), frozenResult.participantResults()
					.stream().map(InternalLifecycleComponentShutdownResult::kind).toList(),
					"The configured outer graphs must remain exactly two participants");

			InternalLifecycleComponentShutdownResult httpResult = frozenResult
					.participantResult(InternalLifecycleComponentType.HTTP).orElseThrow();
			Assertions.assertEquals(
					InternalLifecycleComponentShutdownDisposition.TERMINATION_UNKNOWN,
					httpResult.disposition());
			Assertions.assertTrue(httpResult.failures().isEmpty());
			Assertions.assertTrue(httpResult.residualActivity().isEmpty());
			InternalLifecycleComponentShutdownResult sseResult = frozenResult
					.participantResult(InternalLifecycleComponentType.SSE).orElseThrow();
			Assertions.assertEquals(
					InternalLifecycleComponentShutdownDisposition.GRACEFUL_TERMINATION,
					sseResult.disposition());
			Assertions.assertTrue(sseResult.failures().isEmpty());
			Assertions.assertTrue(sseResult.residualActivity().isEmpty());

			Assertions.assertEquals(1, http.quiesceCalls());
			Assertions.assertEquals(1, http.forceCalls());
			Assertions.assertEquals(1, httpEngine.quiesceCalls());
			Assertions.assertEquals(1, httpEngine.forceCalls());
			Assertions.assertEquals(1, sse.quiesceCalls());
			Assertions.assertEquals(0, sse.forceCalls(),
					"A graph proven during grace must not receive force");
			Assertions.assertFalse(http.callbackReturned(),
					"Terminal publication must not wait for a user-blocked handoff");
			Assertions.assertFalse(http.rootProofPublished());
		} catch (Throwable failure) {
			primaryFailure = failure;
			throw failure;
		} finally {
			Throwable cleanupFailure = cleanup(soklet, http, executor);
			if (cleanupFailure != null) {
				if (primaryFailure != null)
					primaryFailure.addSuppressed(cleanupFailure);
				else
					throw new AssertionError("Negative-fixture cleanup failed",
							cleanupFailure);
			}
		}

		Assertions.assertTrue(http.awaitCallbackReturned(2, TimeUnit.SECONDS),
				"The test must release its deliberately blocked callback");
		Assertions.assertTrue(http.rootProofPublished(),
				"The released callback may publish its deliberately late root proof");
		Assertions.assertSame(frozenResult,
				soklet.getDirectLifecycle().result().orElseThrow());
		Assertions.assertEquals(InternalShutdownDisposition.INCOMPLETE,
				frozenResult.disposition(),
				"Late root proof cannot rewrite immutable terminal evidence");
	}

	@Nullable
	private static Throwable cleanup(@NonNull Soklet soklet,
			@NonNull BlockingProofHttpDecorator http,
			@NonNull ExecutorService executor) {
		Throwable failure = null;
		try {
			soklet.close();
			failure = new AssertionError(
					"close() must replay the incomplete one-shot result");
		} catch (ShutdownIncompleteException expected) {
			// Expected both after the asserted result and on an early assertion path.
		} catch (Throwable unexpected) {
			failure = unexpected;
		} finally {
			http.releaseCallback();
		}
		executor.shutdownNow();
		try {
			if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
				AssertionError terminationFailure = new AssertionError(
						"The test-owned stop executor must terminate");
				if (failure == null)
					failure = terminationFailure;
				else
					failure.addSuppressed(terminationFailure);
			}
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			if (failure == null)
				failure = interrupted;
			else
				failure.addSuppressed(interrupted);
		}
		return failure;
	}

	@Nullable
	private static Throwable captureFailure(@NonNull Runnable operation) {
		try {
			requireNonNull(operation).run();
			return null;
		} catch (Throwable throwable) {
			return throwable;
		}
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

	private static final class ProgressOrder {
		@NonNull private final AtomicInteger sequence = new AtomicInteger();
		@NonNull private final AtomicInteger callbackEntered = new AtomicInteger();
		@NonNull private final AtomicInteger sseQuiesce = new AtomicInteger();
		@NonNull private final AtomicInteger httpForce = new AtomicInteger();
		@NonNull private final CountDownLatch callbackEnteredLatch =
				new CountDownLatch(1);

		int callbackEnteredOrder() {
			return this.callbackEntered.get();
		}

		int sseQuiesceOrder() {
			return this.sseQuiesce.get();
		}

		int httpForceOrder() {
			return this.httpForce.get();
		}

		void recordCallbackEntered() {
			this.callbackEntered.compareAndSet(0,
					this.sequence.incrementAndGet());
			this.callbackEnteredLatch.countDown();
		}

		void recordSseQuiesceAfterCallback() {
			awaitCallbackEntered();
			this.sseQuiesce.compareAndSet(0, this.sequence.incrementAndGet());
		}

		void recordHttpForceAfterCallback() {
			awaitCallbackEntered();
			this.httpForce.compareAndSet(0, this.sequence.incrementAndGet());
		}

		private void awaitCallbackEntered() {
			boolean interrupted = false;
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
			try {
				for (;;) {
					long remaining = deadline - System.nanoTime();
					if (remaining <= 0L)
						throw new AssertionError(
								"The proof callback did not enter before independent progress");
					try {
						if (!this.callbackEnteredLatch.await(remaining,
								TimeUnit.NANOSECONDS))
							throw new AssertionError(
									"The proof callback did not enter before independent progress");
						return;
					} catch (InterruptedException ignored) {
						interrupted = true;
					}
				}
			} finally {
				if (interrupted)
					Thread.currentThread().interrupt();
			}
		}
	}

	public static final class InertResource {
		@GET("/ok")
		@NonNull
		public String ok() {
			return "ok";
		}
	}

	private interface ComposedHttpEndpoint extends HttpServer {
	}

	private static final class AlternativeHttpEngine
			implements ComposedHttpEndpoint {
		@NonNull private final TransportIdentity identity =
				TransportIdentity.create();
		@NonNull private final AtomicBoolean proofPublished = new AtomicBoolean();
		@NonNull private final AtomicInteger quiesceCalls = new AtomicInteger();
		@NonNull private final AtomicInteger forceCalls = new AtomicInteger();
		@NonNull private final CountDownLatch childProof = new CountDownLatch(1);
		@NonNull private final CountDownLatch forceReturned = new CountDownLatch(1);
		@NonNull private final AtomicReference<TransportTerminationSignal>
				terminationSignal = new AtomicReference<>();

		int quiesceCalls() {
			return this.quiesceCalls.get();
		}

		int forceCalls() {
			return this.forceCalls.get();
		}

		boolean awaitChildProof(long timeout, @NonNull TimeUnit unit)
				throws InterruptedException {
			return this.childProof.await(timeout, unit);
		}

		boolean awaitForceReturned(long timeout, @NonNull TimeUnit unit)
				throws InterruptedException {
			return this.forceReturned.await(timeout, unit);
		}

		@Override
		@NonNull
		public TransportIdentity getTransportIdentity() {
			return this.identity;
		}

		@Override
		@NonNull
		public TransportRuntime attach(
				@NonNull HttpTransportAttachmentContext context,
				@NonNull StartupContext startupContext) {
			this.terminationSignal.set(context.getTerminationSignal());
			return new TransportRuntime() {
				@Override
				public void start(@NonNull StartupContext context) {
				}

				@Override
				public void quiesce(@NonNull ShutdownContext context) {
					quiesceCalls.incrementAndGet();
					publishProof();
				}

				@Override
				public void force(@NonNull ShutdownContext context) {
					forceCalls.incrementAndGet();
					try {
						publishProof();
					} finally {
						forceReturned.countDown();
					}
				}
			};
		}

		private void publishProof() {
			if (this.proofPublished.compareAndSet(false, true)) {
				requireNonNull(this.terminationSignal.get()).signalTerminated();
				this.childProof.countDown();
			}
		}
	}

	private static final class BlockingProofHttpDecorator
			implements ComposedHttpEndpoint {
		@NonNull private final AlternativeHttpEngine delegate;
		@NonNull private final ProgressOrder progressOrder;
		@NonNull private final TransportIdentity identity;
		@NonNull private final AtomicBoolean callbackReturned = new AtomicBoolean();
		@NonNull private final AtomicBoolean rootProofPublished = new AtomicBoolean();
		@NonNull private final AtomicInteger quiesceCalls = new AtomicInteger();
		@NonNull private final AtomicInteger forceCalls = new AtomicInteger();
		@NonNull private final CountDownLatch callbackEntered = new CountDownLatch(1);
		@NonNull private final CountDownLatch releaseCallback = new CountDownLatch(1);
		@NonNull private final CountDownLatch callbackExit = new CountDownLatch(1);
		@NonNull private final CountDownLatch quiesceReturned = new CountDownLatch(1);
		@NonNull private final CountDownLatch forceReturned = new CountDownLatch(1);
		@NonNull private final AtomicReference<CompletionStage<Void>> childProofStage =
				new AtomicReference<>();

		private BlockingProofHttpDecorator(
				@NonNull AlternativeHttpEngine delegate,
				@NonNull ProgressOrder progressOrder) {
			this.delegate = requireNonNull(delegate);
			this.progressOrder = requireNonNull(progressOrder);
			this.identity = delegate.getTransportIdentity();
		}

		int quiesceCalls() {
			return this.quiesceCalls.get();
		}

		int forceCalls() {
			return this.forceCalls.get();
		}

		boolean callbackReturned() {
			return this.callbackReturned.get();
		}

		boolean rootProofPublished() {
			return this.rootProofPublished.get();
		}

		boolean awaitCallbackEntered(long timeout, @NonNull TimeUnit unit)
				throws InterruptedException {
			return this.callbackEntered.await(timeout, unit);
		}

		boolean awaitCallbackReturned(long timeout, @NonNull TimeUnit unit)
				throws InterruptedException {
			return this.callbackExit.await(timeout, unit);
		}

		boolean awaitQuiesceReturned(long timeout, @NonNull TimeUnit unit)
				throws InterruptedException {
			return this.quiesceReturned.await(timeout, unit);
		}

		boolean awaitForceReturned(long timeout, @NonNull TimeUnit unit)
				throws InterruptedException {
			return this.forceReturned.await(timeout, unit);
		}

		@NonNull
		CompletionStage<Void> childProofStage() {
			return requireNonNull(this.childProofStage.get());
		}

		void releaseCallback() {
			this.releaseCallback.countDown();
		}

		@Override
		@NonNull
		public TransportIdentity getTransportIdentity() {
			return this.identity;
		}

		@Override
		@NonNull
		public TransportRuntime attach(
				@NonNull HttpTransportAttachmentContext context,
				@NonNull StartupContext startupContext) {
			TransportTerminationSignal rootSignal =
					context.getTerminationSignal();
			TransportDelegateAttachment attachment = context
					.attachLifecycleOwningDelegate(this.delegate,
							context.getAdmissionFencedRequestHandler());
			CompletionStage<Void> proofStage = attachment.whenTerminated();
			this.childProofStage.set(proofStage);
			proofStage.whenComplete((ignored, failure) -> {
				this.progressOrder.recordCallbackEntered();
				this.callbackEntered.countDown();
				awaitUninterruptibly(this.releaseCallback);
				try {
					if (failure == null
							&& this.rootProofPublished.compareAndSet(false, true))
						rootSignal.signalTerminated();
				} finally {
					this.callbackReturned.set(true);
					this.callbackExit.countDown();
				}
			});
			TransportRuntime delegateRuntime = attachment.getTransportRuntime();
			return new TransportRuntime() {
				@Override
				public void start(@NonNull StartupContext context) {
					delegateRuntime.start(context);
				}

				@Override
				public void quiesce(@NonNull ShutdownContext context) {
					quiesceCalls.incrementAndGet();
					try {
						delegateRuntime.quiesce(context);
					} finally {
						quiesceReturned.countDown();
					}
				}

				@Override
				public void force(@NonNull ShutdownContext context) {
					progressOrder.recordHttpForceAfterCallback();
					forceCalls.incrementAndGet();
					try {
						delegateRuntime.force(context);
					} finally {
						forceReturned.countDown();
					}
				}
			};
		}

	}

	private static final class IndependentSseEngine implements SseServer {
		@NonNull private final ProgressOrder progressOrder;
		@NonNull private final TransportIdentity identity =
				TransportIdentity.create();
		@NonNull private final AtomicBoolean proofPublished = new AtomicBoolean();
		@NonNull private final AtomicInteger quiesceCalls = new AtomicInteger();
		@NonNull private final AtomicInteger forceCalls = new AtomicInteger();
		@NonNull private final CountDownLatch quiesceReturned = new CountDownLatch(1);
		@NonNull private final AtomicReference<TransportTerminationSignal>
				terminationSignal = new AtomicReference<>();

		private IndependentSseEngine(@NonNull ProgressOrder progressOrder) {
			this.progressOrder = requireNonNull(progressOrder);
		}

		int quiesceCalls() {
			return this.quiesceCalls.get();
		}

		int forceCalls() {
			return this.forceCalls.get();
		}

		boolean proofPublished() {
			return this.proofPublished.get();
		}

		boolean awaitQuiesceReturned(long timeout, @NonNull TimeUnit unit)
				throws InterruptedException {
			return this.quiesceReturned.await(timeout, unit);
		}

		@Override
		@NonNull
		public TransportIdentity getTransportIdentity() {
			return this.identity;
		}

		@Override
		@NonNull
		public TransportRuntime attach(
				@NonNull SseTransportAttachmentContext context,
				@NonNull StartupContext startupContext) {
			this.terminationSignal.set(context.getTerminationSignal());
			return new TransportRuntime() {
				@Override
				public void start(@NonNull StartupContext context) {
				}

				@Override
				public void quiesce(@NonNull ShutdownContext context) {
					progressOrder.recordSseQuiesceAfterCallback();
					quiesceCalls.incrementAndGet();
					try {
						publishProof();
					} finally {
						quiesceReturned.countDown();
					}
				}

				@Override
				public void force(@NonNull ShutdownContext context) {
					forceCalls.incrementAndGet();
					publishProof();
				}
			};
		}

		@Override
		@NonNull
		public Optional<? extends SseBroadcaster> acquireBroadcaster(
				@Nullable ResourcePath resourcePath) {
			return Optional.empty();
		}

		private void publishProof() {
			if (this.proofPublished.compareAndSet(false, true))
				requireNonNull(this.terminationSignal.get()).signalTerminated();
		}
	}
}
