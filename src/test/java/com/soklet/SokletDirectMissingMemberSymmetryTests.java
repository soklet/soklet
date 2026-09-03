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
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Objects.requireNonNull;

/** Missing-member symmetry coverage for direct composed transport graphs. */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
final class SokletDirectMissingMemberSymmetryTests {
	@NonNull
	private static final Duration PHASE_TIMEOUT = Duration.ofMillis(150);

	@Test
	void rootProofWithoutChildProofCannotCompleteConfiguredGraph()
			throws Throwable {
		OwningHttpGraph graph = new OwningHttpGraph(ProofSelection.ROOT_ONLY);
		Soklet soklet = Soklet.fromConfig(config(graph));
		Throwable primaryFailure = null;
		try {
			soklet.start();
			assertLifecycleOwningSignals(graph);

			SokletShutdownIncompleteException failure = Assertions.assertThrows(
					SokletShutdownIncompleteException.class, soklet::close);

			assertBothShutdownPhases(graph);
			Assertions.assertTrue(graph.rootProofPublished());
			Assertions.assertFalse(graph.childProofPublished());
			Assertions.assertFalse(graph.childSubtreeProofObserved(),
					"A root proof cannot satisfy the missing child subtree");
			assertOneUnknownHttpParticipant(soklet, failure);
		} catch (Throwable failure) {
			primaryFailure = failure;
			throw failure;
		} finally {
			Throwable cleanupFailure = closeAfterIncompleteResult(soklet);
			try {
				graph.releaseAllProofs();
			} catch (Throwable releaseFailure) {
				if (cleanupFailure == null)
					cleanupFailure = releaseFailure;
				else
					cleanupFailure.addSuppressed(releaseFailure);
			}
			if (cleanupFailure != null) {
				if (primaryFailure != null)
					primaryFailure.addSuppressed(cleanupFailure);
				else
					throw new AssertionError("Negative-fixture cleanup failed",
							cleanupFailure);
			}
		}
	}

	@Test
	void childProofWithoutRootProofCannotCompleteConfiguredGraph()
			throws Throwable {
		OwningHttpGraph graph = new OwningHttpGraph(ProofSelection.CHILD_ONLY);
		Soklet soklet = Soklet.fromConfig(config(graph));
		Throwable primaryFailure = null;
		try {
			soklet.start();
			assertLifecycleOwningSignals(graph);

			SokletShutdownIncompleteException failure = Assertions.assertThrows(
					SokletShutdownIncompleteException.class, soklet::close);

			assertBothShutdownPhases(graph);
			Assertions.assertTrue(graph.childProofPublished());
			Assertions.assertFalse(graph.rootProofPublished());
			Assertions.assertTrue(graph.awaitChildSubtreeProof(2,
					TimeUnit.SECONDS),
					"The child subtree proof should be independently observable");
			assertOneUnknownHttpParticipant(soklet, failure);
		} catch (Throwable failure) {
			primaryFailure = failure;
			throw failure;
		} finally {
			Throwable cleanupFailure = closeAfterIncompleteResult(soklet);
			try {
				graph.releaseAllProofs();
			} catch (Throwable releaseFailure) {
				if (cleanupFailure == null)
					cleanupFailure = releaseFailure;
				else
					cleanupFailure.addSuppressed(releaseFailure);
			}
			if (cleanupFailure != null) {
				if (primaryFailure != null)
					primaryFailure.addSuppressed(cleanupFailure);
				else
					throw new AssertionError("Negative-fixture cleanup failed",
							cleanupFailure);
			}
		}
	}

	@NonNull
	private static SokletConfig config(@NonNull OwningHttpGraph graph) {
		return SokletConfig.withHttpServer(graph)
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(
						Set.of(OkResource.class)))
				.internalLifecyclePolicy(new InternalLifecyclePolicy(
						Duration.ofSeconds(2), Duration.ofSeconds(2),
						PHASE_TIMEOUT, PHASE_TIMEOUT))
				.build();
	}

	private static void assertBothShutdownPhases(
			@NonNull OwningHttpGraph graph) throws InterruptedException {
		Assertions.assertTrue(graph.awaitQuiesceReturned(2, TimeUnit.SECONDS));
		Assertions.assertTrue(graph.awaitForceReturned(2, TimeUnit.SECONDS));
		Assertions.assertEquals(1, graph.quiesceCalls());
		Assertions.assertEquals(1, graph.forceCalls());
		Assertions.assertEquals(1, graph.leafQuiesceCalls());
		Assertions.assertEquals(1, graph.leafForceCalls());
	}

	private static void assertLifecycleOwningSignals(
			@NonNull OwningHttpGraph graph) {
		Assertions.assertNotSame(graph.rootSignal(), graph.childSignal(),
				"Lifecycle-owning delegation must create a distinct child member");
	}

	private static void assertOneUnknownHttpParticipant(@NonNull Soklet soklet,
			@NonNull SokletShutdownIncompleteException failure) {
		InternalShutdownResult result = soklet.getDirectLifecycle().result()
				.orElseThrow();
		Assertions.assertSame(result, failure.getInternalShutdownResult());
		Assertions.assertEquals(InternalStartupDisposition.READY,
				result.startupDisposition());
		Assertions.assertEquals(InternalShutdownDisposition.INCOMPLETE,
				result.disposition());
		Assertions.assertFalse(result.isComplete());
		Assertions.assertEquals(1, result.participantResults().size(),
				"Both graph members belong to one configured participant");
		InternalLifecycleComponentShutdownResult http = result
				.participantResult(InternalLifecycleComponentType.HTTP).orElseThrow();
		Assertions.assertEquals(
				InternalLifecycleComponentShutdownDisposition.TERMINATION_UNKNOWN,
				http.disposition());
		Assertions.assertTrue(http.failures().isEmpty());
		Assertions.assertTrue(http.residualActivity().isEmpty(),
				"A missing proof is unknown termination, not residual work");
	}

	@Nullable
	private static Throwable closeAfterIncompleteResult(
			@NonNull Soklet soklet) {
		try {
			soklet.close();
			return new AssertionError(
					"close() must replay the incomplete one-shot result");
		} catch (SokletShutdownIncompleteException ignored) {
			// close() replays the already-published immutable incomplete result.
			return null;
		} catch (Throwable unexpected) {
			return unexpected;
		}
	}

	private enum ProofSelection {
		ROOT_ONLY,
		CHILD_ONLY
	}

	private static final class OwningHttpGraph implements HttpServer {
		@NonNull
		private final TransportIdentity identity = TransportIdentity.create();
		@NonNull
		private final ProofSelection proofSelection;
		@NonNull
		private final AlternativeHttpLeaf leaf;
		@NonNull
		private final AtomicInteger quiesceCalls = new AtomicInteger();
		@NonNull
		private final AtomicInteger forceCalls = new AtomicInteger();
		@NonNull
		private final CountDownLatch quiesceReturned = new CountDownLatch(1);
		@NonNull
		private final CountDownLatch forceReturned = new CountDownLatch(1);
		@NonNull
		private final CountDownLatch childSubtreeProof = new CountDownLatch(1);
		@NonNull
		private final AtomicBoolean rootProofPublished = new AtomicBoolean();
		@NonNull
		private final AtomicReference<TransportTerminationSignal> rootSignal =
				new AtomicReference<>();

		private OwningHttpGraph(@NonNull ProofSelection proofSelection) {
			this.proofSelection = requireNonNull(proofSelection);
			this.leaf = new AlternativeHttpLeaf(this.identity, proofSelection);
		}

		int quiesceCalls() {
			return this.quiesceCalls.get();
		}

		int forceCalls() {
			return this.forceCalls.get();
		}

		int leafQuiesceCalls() {
			return this.leaf.quiesceCalls();
		}

		int leafForceCalls() {
			return this.leaf.forceCalls();
		}

		boolean awaitQuiesceReturned(long timeout, @NonNull TimeUnit unit)
				throws InterruptedException {
			return this.quiesceReturned.await(timeout, unit);
		}

		boolean awaitForceReturned(long timeout, @NonNull TimeUnit unit)
				throws InterruptedException {
			return this.forceReturned.await(timeout, unit);
		}

		boolean awaitChildSubtreeProof(long timeout, @NonNull TimeUnit unit)
				throws InterruptedException {
			return this.childSubtreeProof.await(timeout, unit);
		}

		boolean childSubtreeProofObserved() {
			return this.childSubtreeProof.getCount() == 0;
		}

		boolean rootProofPublished() {
			return this.rootProofPublished.get();
		}

		boolean childProofPublished() {
			return this.leaf.proofPublished();
		}

		@NonNull
		TransportTerminationSignal rootSignal() {
			return requireNonNull(this.rootSignal.get());
		}

		@NonNull
		TransportTerminationSignal childSignal() {
			return this.leaf.signal();
		}

		void releaseAllProofs() {
			publishRootProof();
			this.leaf.publishProof();
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
			this.rootSignal.set(context.getTerminationSignal());
			TransportDelegateAttachment attachment =
					context.attachTerminationOwningDelegate(this.leaf,
							context.getAdmissionFencedRequestHandler());
			attachment.whenTerminated().whenComplete((ignored, failure) -> {
				if (failure == null)
					this.childSubtreeProof.countDown();
			});
			return new TransportRuntime() {
				@Override
				public void start(@NonNull StartupContext context) {
					attachment.getTransportRuntime().start(context);
				}

				@Override
				public void shutdownGracefully(@NonNull ShutdownContext context) {
					quiesceCalls.incrementAndGet();
					try {
						attachment.getTransportRuntime().shutdownGracefully(context);
						publishSelectedRootProof();
					} finally {
						quiesceReturned.countDown();
					}
				}

				@Override
				public void shutdownForcibly(@NonNull ShutdownContext context) {
					forceCalls.incrementAndGet();
					try {
						attachment.getTransportRuntime().shutdownForcibly(context);
						publishSelectedRootProof();
					} finally {
						forceReturned.countDown();
					}
				}
			};
		}
		private void publishSelectedRootProof() {
			if (this.proofSelection == ProofSelection.ROOT_ONLY)
				publishRootProof();
		}

		private void publishRootProof() {
			TransportTerminationSignal signal = this.rootSignal.get();
			if (signal != null
					&& this.rootProofPublished.compareAndSet(false, true))
				signal.signalTerminated();
		}
	}

	private static final class AlternativeHttpLeaf implements HttpServer {
		@NonNull
		private final TransportIdentity identity;
		@NonNull
		private final ProofSelection proofSelection;
		@NonNull
		private final AtomicBoolean proofPublished = new AtomicBoolean();
		@NonNull
		private final AtomicInteger quiesceCalls = new AtomicInteger();
		@NonNull
		private final AtomicInteger forceCalls = new AtomicInteger();
		@NonNull
		private final AtomicReference<TransportTerminationSignal> signal =
				new AtomicReference<>();

		private AlternativeHttpLeaf(@NonNull TransportIdentity identity,
				@NonNull ProofSelection proofSelection) {
			this.identity = requireNonNull(identity);
			this.proofSelection = requireNonNull(proofSelection);
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

		@NonNull
		TransportTerminationSignal signal() {
			return requireNonNull(this.signal.get());
		}

		void publishProof() {
			TransportTerminationSignal exactSignal = this.signal.get();
			if (exactSignal != null
					&& this.proofPublished.compareAndSet(false, true))
				exactSignal.signalTerminated();
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
			this.signal.set(context.getTerminationSignal());
			return new TransportRuntime() {
				@Override
				public void start(@NonNull StartupContext context) {
				}

				@Override
				public void shutdownGracefully(@NonNull ShutdownContext context) {
					quiesceCalls.incrementAndGet();
					publishSelectedProof();
				}

				@Override
				public void shutdownForcibly(@NonNull ShutdownContext context) {
					forceCalls.incrementAndGet();
					publishSelectedProof();
				}
			};
		}

		private void publishSelectedProof() {
			if (this.proofSelection == ProofSelection.CHILD_ONLY)
				publishProof();
		}
	}

	public static final class OkResource {
		@GET("/ok")
		@NonNull
		public String ok() {
			return "ok";
		}
	}
}
