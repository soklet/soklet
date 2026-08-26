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
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.Objects.requireNonNull;

/** Direct-owner acceptance coverage for composed transport identity aliases. */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
final class SokletDirectTransportAliasTests {
	@Test
	void configuredWrapperConflictsWithBaseSiblingAndNestedAliases() {
		InternalTransportIdentity identity = InternalTransportIdentity.create();
		ProbeHttpEngine base = new ProbeHttpEngine(identity);
		ProbeHttpDecorator configured = new ProbeHttpDecorator(base);
		ProbeHttpDecorator sibling = new ProbeHttpDecorator(base);
		ProbeHttpDecorator nested = new ProbeHttpDecorator(configured);

		try (Soklet owner = Soklet.fromConfig(httpConfig(configured))) {
			assertOwnershipConflict(base, InternalParticipantKind.HTTP,
					ProbeHttpEngine.class);
			assertOwnershipConflict(sibling, InternalParticipantKind.HTTP,
					ProbeHttpDecorator.class);
			assertOwnershipConflict(nested, InternalParticipantKind.HTTP,
					ProbeHttpDecorator.class);

			Assertions.assertSame(identity, base.identity());
			Assertions.assertSame(identity, configured.identity());
			Assertions.assertSame(identity, sibling.identity());
			Assertions.assertSame(identity, nested.identity());
			assertUntouched(base, configured, sibling, nested);
		}

		assertUntouched(base, configured, sibling, nested);
	}

	@Test
	void duplicateCrossKindAliasFailsAtomicallyAndTokenRemainsClaimable() {
		InternalTransportIdentity identity = InternalTransportIdentity.create();
		ProbeHttpEngine http = new ProbeHttpEngine(identity);
		ProbeSseEngine sse = new ProbeSseEngine(identity);

		IllegalArgumentException duplicate = Assertions.assertThrows(
				IllegalArgumentException.class,
				() -> Soklet.fromConfig(httpAndSseConfig(http, sse)));

		Assertions.assertEquals(
				"Transport identity appears more than once in one claim",
				duplicate.getMessage());
		assertUntouched(http, sse);

		try (Soklet owner = Soklet.fromConfig(httpConfig(http))) {
			TransportOwnershipException conflict = Assertions.assertThrows(
					TransportOwnershipException.class,
					() -> Soklet.fromConfig(sseConfig(sse)));
			Assertions.assertEquals(InternalParticipantKind.SSE,
					conflict.getInternalParticipantKind());
			Assertions.assertSame(ProbeSseEngine.class,
					conflict.getTransportClass());
			Assertions.assertEquals(
					"The SSE transport identity for "
							+ ProbeSseEngine.class.getName()
							+ " is already owned by another lifecycle",
					conflict.getMessage());
			assertUntouched(http, sse);
		}

		assertUntouched(http, sse);
	}

	@Test
	void duplicateCrossKindPreflightPrecedesExistingOwnershipConflict() {
		InternalTransportIdentity identity = InternalTransportIdentity.create();
		ProbeHttpEngine claimed = new ProbeHttpEngine(identity);
		ProbeHttpEngine duplicateHttp = new ProbeHttpEngine(identity);
		ProbeSseEngine duplicateSse = new ProbeSseEngine(identity);

		try (Soklet owner = Soklet.fromConfig(httpConfig(claimed))) {
			IllegalArgumentException duplicate = Assertions.assertThrows(
					IllegalArgumentException.class,
					() -> Soklet.fromConfig(httpAndSseConfig(duplicateHttp,
							duplicateSse)));

			Assertions.assertEquals(
					"Transport identity appears more than once in one claim",
					duplicate.getMessage());
			assertUntouched(claimed, duplicateHttp, duplicateSse);
		}

		assertUntouched(claimed, duplicateHttp, duplicateSse);
	}

	@Test
	void groupConflictDoesNotInstallOtherwiseFreeClaim() {
		InternalTransportIdentity claimedIdentity =
				InternalTransportIdentity.create();
		InternalTransportIdentity freeIdentity =
				InternalTransportIdentity.create();
		ProbeHttpEngine claimed = new ProbeHttpEngine(claimedIdentity);
		ProbeHttpEngine freeHttp = new ProbeHttpEngine(freeIdentity);
		ProbeSseEngine conflictingSse = new ProbeSseEngine(claimedIdentity);

		try (Soklet firstOwner = Soklet.fromConfig(httpConfig(claimed))) {
			TransportOwnershipException conflict = Assertions.assertThrows(
					TransportOwnershipException.class,
					() -> Soklet.fromConfig(httpAndSseConfig(freeHttp,
							conflictingSse)));
			Assertions.assertEquals(InternalParticipantKind.SSE,
					conflict.getInternalParticipantKind());
			Assertions.assertSame(ProbeSseEngine.class,
					conflict.getTransportClass());
			Assertions.assertEquals(
					"The SSE transport identity for "
							+ ProbeSseEngine.class.getName()
							+ " is already owned by another lifecycle",
					conflict.getMessage());
			assertUntouched(claimed, freeHttp, conflictingSse);

			try (Soklet secondOwner = Soklet.fromConfig(httpConfig(freeHttp))) {
				Assertions.assertNotSame(firstOwner, secondOwner);
				assertUntouched(claimed, freeHttp, conflictingSse);
			}

			assertUntouched(claimed, freeHttp, conflictingSse);
		}

		assertUntouched(claimed, freeHttp, conflictingSse);
	}

	private static void assertOwnershipConflict(@NonNull HttpServer candidate,
			@NonNull InternalParticipantKind expectedKind,
			@NonNull Class<?> expectedClass) {
		TransportOwnershipException conflict = Assertions.assertThrows(
				TransportOwnershipException.class,
				() -> Soklet.fromConfig(httpConfig(candidate)));
		Assertions.assertEquals(expectedKind,
				conflict.getInternalParticipantKind());
		Assertions.assertSame(expectedClass, conflict.getTransportClass());
		Assertions.assertEquals(
				"The " + expectedKind + " transport identity for "
						+ expectedClass.getName()
						+ " is already owned by another lifecycle",
				conflict.getMessage());
	}

	@NonNull
	private static SokletConfig httpConfig(@NonNull HttpServer http) {
		return SokletConfig.withHttpServer(http)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.build();
	}

	@NonNull
	private static SokletConfig sseConfig(@NonNull SseServer sse) {
		return SokletConfig.withSseServer(sse)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.build();
	}

	@NonNull
	private static SokletConfig httpAndSseConfig(@NonNull HttpServer http,
			@NonNull SseServer sse) {
		return SokletConfig.withHttpServer(http)
				.sseServer(sse)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.build();
	}

	private static void assertUntouched(@NonNull ProbeEndpoint... endpoints) {
		for (ProbeEndpoint endpoint : endpoints)
			endpoint.probe().assertUntouched();
	}

	private interface ProbeEndpoint {
		@NonNull
		LifecycleProbe probe();
	}

	private interface ProbeHttpEndpoint
			extends HttpServer, InternalHttpTransportEndpoint, ProbeEndpoint {
	}

	private abstract static class AbstractProbeHttpEndpoint
			implements ProbeHttpEndpoint {
		@NonNull private final InternalTransportIdentity identity;
		@NonNull private final LifecycleProbe probe;

		private AbstractProbeHttpEndpoint(
				@NonNull InternalTransportIdentity identity) {
			this.identity = requireNonNull(identity);
			this.probe = new LifecycleProbe();
		}

		@Override
		@NonNull
		public final InternalTransportIdentity identity() {
			return this.identity;
		}

		@Override
		@NonNull
		public final LifecycleProbe probe() {
			return this.probe;
		}

		@Override
		public final void start() {
			this.probe.legacyStarts.incrementAndGet();
		}

		@Override
		public final void stop() {
			this.probe.legacyStops.incrementAndGet();
		}

		@Override
		@NonNull
		public final Boolean isStarted() {
			return false;
		}

		@Override
		public final void initialize(@NonNull SokletConfig sokletConfig,
				HttpServer.@NonNull RequestHandler requestHandler) {
			this.probe.initializations.incrementAndGet();
		}

		@NonNull
		final InternalTransportRuntime newRuntime() {
			return this.probe.newRuntime();
		}
	}

	private static final class ProbeHttpEngine
			extends AbstractProbeHttpEndpoint {
		private ProbeHttpEngine(@NonNull InternalTransportIdentity identity) {
			super(identity);
		}

		@Override
		@NonNull
		public InternalTransportRuntime attach(
				@NonNull InternalTransportAttachmentContext<HttpServer.RequestHandler> context,
				@NonNull InternalStartupContext startupContext) {
			probe().attachments.incrementAndGet();
			return newRuntime();
		}
	}

	private static final class ProbeHttpDecorator
			extends AbstractProbeHttpEndpoint {
		@NonNull private final ProbeHttpEndpoint delegate;

		private ProbeHttpDecorator(@NonNull ProbeHttpEndpoint delegate) {
			super(requireNonNull(delegate).identity());
			this.delegate = delegate;
		}

		@Override
		@NonNull
		public InternalTransportRuntime attach(
				@NonNull InternalTransportAttachmentContext<HttpServer.RequestHandler> context,
				@NonNull InternalStartupContext startupContext) {
			probe().attachments.incrementAndGet();
			return context.attachTransparentDelegate(this.delegate,
					context.requestHandler());
		}
	}

	private static final class ProbeSseEngine
			implements SseServer, InternalSseTransportEndpoint, ProbeEndpoint {
		@NonNull private final InternalTransportIdentity identity;
		@NonNull private final LifecycleProbe probe;

		private ProbeSseEngine(@NonNull InternalTransportIdentity identity) {
			this.identity = requireNonNull(identity);
			this.probe = new LifecycleProbe();
		}

		@Override
		@NonNull
		public InternalTransportIdentity identity() {
			return this.identity;
		}

		@Override
		@NonNull
		public LifecycleProbe probe() {
			return this.probe;
		}

		@Override
		@NonNull
		public InternalTransportRuntime attach(
				@NonNull InternalTransportAttachmentContext<SseServer.RequestHandler> context,
				@NonNull InternalStartupContext startupContext) {
			this.probe.attachments.incrementAndGet();
			return this.probe.newRuntime();
		}

		@Override
		public void start() {
			this.probe.legacyStarts.incrementAndGet();
		}

		@Override
		public void stop() {
			this.probe.legacyStops.incrementAndGet();
		}

		@Override
		@NonNull
		public Boolean isStarted() {
			return false;
		}

		@Override
		@NonNull
		public Optional<? extends SseBroadcaster> acquireBroadcaster(
				@Nullable ResourcePath resourcePath) {
			return Optional.empty();
		}

		@Override
		public void initialize(@NonNull SokletConfig sokletConfig,
				SseServer.@NonNull RequestHandler requestHandler) {
			this.probe.initializations.incrementAndGet();
		}
	}

	private static final class LifecycleProbe {
		@NonNull private final AtomicInteger attachments = new AtomicInteger();
		@NonNull private final AtomicInteger runtimeStarts = new AtomicInteger();
		@NonNull private final AtomicInteger quiesces = new AtomicInteger();
		@NonNull private final AtomicInteger forces = new AtomicInteger();
		@NonNull private final AtomicInteger initializations = new AtomicInteger();
		@NonNull private final AtomicInteger legacyStarts = new AtomicInteger();
		@NonNull private final AtomicInteger legacyStops = new AtomicInteger();

		@NonNull
		private InternalTransportRuntime newRuntime() {
			return new InternalTransportRuntime() {
				@Override
				public void start(@NonNull InternalStartupContext context) {
					runtimeStarts.incrementAndGet();
				}

				@Override
				public void quiesce(@NonNull InternalShutdownContext context) {
					quiesces.incrementAndGet();
				}

				@Override
				public void force(@NonNull InternalShutdownContext context) {
					forces.incrementAndGet();
				}
			};
		}

		private void assertUntouched() {
			Assertions.assertAll(
					() -> Assertions.assertEquals(0, this.attachments.get()),
					() -> Assertions.assertEquals(0, this.runtimeStarts.get()),
					() -> Assertions.assertEquals(0, this.quiesces.get()),
					() -> Assertions.assertEquals(0, this.forces.get()),
					() -> Assertions.assertEquals(0, this.initializations.get()),
					() -> Assertions.assertEquals(0, this.legacyStarts.get()),
					() -> Assertions.assertEquals(0, this.legacyStops.get()));
		}
	}
}
