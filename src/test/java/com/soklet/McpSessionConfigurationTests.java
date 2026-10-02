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

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Session configuration, exact-revision gates, and server dependency contracts. */
class McpSessionConfigurationTests {
	@Test
	void defaultsRetainTheResolverWithoutInvokingIt() {
		AtomicInteger calls = new AtomicInteger();
		McpSessionOwnerKeyResolver resolver = admissionIdentity -> {
			calls.incrementAndGet();
			return "issuer/tenant/subject";
		};
		McpSessionConfig config = McpSessionConfig.withOwnerKeyResolver(resolver).build();
		assertSame(resolver, config.getOwnerKeyResolver());
		assertEquals(256, config.getMaximumSessions());
		assertEquals(16, config.getMaximumSessionsPerOwner());
		assertEquals(Duration.ofHours(24), config.getMaximumSessionIdleDuration());
		assertEquals(Duration.ofDays(7), config.getMaximumSessionDuration());
		assertEquals(65536, config.getMaximumClientMetadataSizeInBytes());
		assertFalse(config.isAnonymousSessionsAllowed());
		assertEquals(0, calls.get());
		assertThrows(NullPointerException.class, () -> McpSessionConfig.withOwnerKeyResolver(null));
	}

	@Test
	void builtValuesAreSnapshotsAndNullRestoresEveryDefault() {
		McpSessionConfig.Builder builder = configBuilder()
				.maximumSessions(2).maximumSessionsPerOwner(1)
				.maximumSessionIdleDuration(Duration.ofSeconds(2))
				.maximumSessionDuration(Duration.ofSeconds(3))
				.maximumClientMetadataSizeInBytes(128).anonymousSessionsAllowed(true);
		McpSessionConfig configured = builder.build();
		McpSessionConfig defaults = builder.maximumSessions(null).maximumSessionsPerOwner(null)
				.maximumSessionIdleDuration(null).maximumSessionDuration(null)
				.maximumClientMetadataSizeInBytes(null).anonymousSessionsAllowed(null).build();
		assertEquals(2, configured.getMaximumSessions());
		assertEquals(1, configured.getMaximumSessionsPerOwner());
		assertEquals(Duration.ofSeconds(2), configured.getMaximumSessionIdleDuration());
		assertEquals(Duration.ofSeconds(3), configured.getMaximumSessionDuration());
		assertEquals(128, configured.getMaximumClientMetadataSizeInBytes());
		assertTrue(configured.isAnonymousSessionsAllowed());
		assertEquals(256, defaults.getMaximumSessions());
		assertEquals(16, defaults.getMaximumSessionsPerOwner());
		assertEquals(Duration.ofHours(24), defaults.getMaximumSessionIdleDuration());
		assertEquals(Duration.ofDays(7), defaults.getMaximumSessionDuration());
		assertEquals(65536, defaults.getMaximumClientMetadataSizeInBytes());
		assertFalse(defaults.isAnonymousSessionsAllowed());
		assertSame(configured.getOwnerKeyResolver(), defaults.getOwnerKeyResolver());
	}

	@Test
	void invalidReplacementDoesNotChangeThePreviousBound() {
		McpSessionConfig.Builder builder = configBuilder();
		for (Consumer<Integer> setter : List.<Consumer<Integer>>of(
				builder::maximumSessions, builder::maximumSessionsPerOwner,
				builder::maximumClientMetadataSizeInBytes)) {
			assertThrows(IllegalArgumentException.class, () -> setter.accept(0));
			assertThrows(IllegalArgumentException.class, () -> setter.accept(-1));
		}
		for (Consumer<Duration> setter : List.<Consumer<Duration>>of(
				builder::maximumSessionIdleDuration, builder::maximumSessionDuration)) {
			assertThrows(IllegalArgumentException.class, () -> setter.accept(Duration.ZERO));
			assertThrows(IllegalArgumentException.class, () -> setter.accept(Duration.ofNanos(-1)));
			assertThrows(IllegalArgumentException.class,
					() -> setter.accept(Duration.ofSeconds(Long.MAX_VALUE)));
		}
		assertEquals(256, builder.build().getMaximumSessions());
		assertEquals(16, builder.build().getMaximumSessionsPerOwner());
		assertEquals(65536, builder.build().getMaximumClientMetadataSizeInBytes());
		assertEquals(Duration.ofHours(24), builder.build().getMaximumSessionIdleDuration());
		assertEquals(Duration.ofDays(7), builder.build().getMaximumSessionDuration());
	}

	@Test
	void inconsistentBoundsFailAtBuildAndCanBeCorrectedInEitherOrder() {
		McpSessionConfig.Builder builder = configBuilder().maximumSessions(2);
		assertThrows(IllegalStateException.class, builder::build);
		assertEquals(2, builder.maximumSessionsPerOwner(2).build().getMaximumSessionsPerOwner());
		builder.maximumSessionDuration(Duration.ofNanos(1));
		assertThrows(IllegalStateException.class, builder::build);
		McpSessionConfig minimum = builder.maximumSessionIdleDuration(Duration.ofNanos(1)).build();
		assertEquals(Duration.ofNanos(1), minimum.getMaximumSessionDuration());
		assertEquals(Duration.ofNanos(1), minimum.getMaximumSessionIdleDuration());
	}

	@Test
	void endpointsRequireAnExplicitEligibleSubsetAndSnapshotTheInput() {
		Set<McpProtocolVersion> selected = EnumSet.of(McpProtocolVersion.V2025_06_18);
		McpEndpoint.Builder builder = endpointBuilder(Set.of(McpProtocolVersion.V2025_06_18,
				McpProtocolVersion.V2025_11_25, McpProtocolVersion.V2026_07_28));
		assertTrue(builder.build().getSessionProtocolVersions().isEmpty());
		builder.sessionProtocolVersions(selected);
		selected.add(McpProtocolVersion.V2026_07_28);
		McpEndpoint june = builder.build();
		assertEquals(Set.of(McpProtocolVersion.V2025_06_18), june.getSessionProtocolVersions());
		assertThrows(UnsupportedOperationException.class,
				() -> june.getSessionProtocolVersions().add(McpProtocolVersion.V2025_11_25));
		assertThrows(NullPointerException.class, () -> builder.sessionProtocolVersions(null));
		assertThrows(IllegalStateException.class,
				() -> builder.sessionProtocolVersions(Set.of(McpProtocolVersion.V2026_07_28)).build());
		assertTrue(builder.sessionProtocolVersions(Set.of()).build().getSessionProtocolVersions().isEmpty());
		assertThrows(IllegalStateException.class, () -> endpointBuilder(Set.of(McpProtocolVersion.V2025_06_18))
				.sessionProtocolVersions(Set.of(McpProtocolVersion.V2025_11_25)).build());
		assertThrows(IllegalStateException.class, () -> endpointBuilder(Set.of(McpProtocolVersion.V2025_06_18))
				.sessionProtocolVersions(Set.of(McpProtocolVersion.V2025_03_26)).build());
	}

	@Test
	void serverRequiresBothConfigurationAndAnEnabledEndpoint() {
		McpSessionConfig config = configBuilder().build();
		McpEndpoint stateless = endpointBuilder(Set.of(McpProtocolVersion.V2025_06_18,
				McpProtocolVersion.V2026_07_28)).build();
		McpEndpoint sessions = endpointBuilder(Set.of(McpProtocolVersion.V2025_06_18,
				McpProtocolVersion.V2025_11_25, McpProtocolVersion.V2026_07_28))
				.sessionProtocolVersions(Set.of(McpProtocolVersion.V2025_06_18,
						McpProtocolVersion.V2025_11_25)).build();
		assertTrue(serverBuilder(stateless).build().getSessionConfig().isEmpty());
		assertThrows(IllegalStateException.class, () -> serverBuilder(stateless).sessionConfig(config).build());
		assertThrows(IllegalStateException.class, () -> serverBuilder(sessions).build());
		assertSame(config, serverBuilder(sessions).sessionConfig(config).build().getSessionConfig().orElseThrow());
		assertTrue(serverBuilder(stateless).sessionConfig(config).sessionConfig(null).build().getSessionConfig().isEmpty());
	}

	@Test
	void configuredSessionsDoNotEnableOtherEndpointRevisionsOrOtherPaths() {
		McpSessionConfig config = configBuilder().build();
		McpEndpoint mixed = endpointBuilder(Set.of(McpProtocolVersion.V2025_06_18,
				McpProtocolVersion.V2025_11_25, McpProtocolVersion.V2026_07_28))
				.sessionProtocolVersions(Set.of(McpProtocolVersion.V2025_11_25)).build();
		McpEndpoint stateless = McpEndpoint.withPath("/stateless",
				McpImplementation.withNameAndVersion("catalog", "1").build(),
				Set.of(McpProtocolVersion.V2025_11_25)).build();
		McpServer server = McpServer.withPort(0)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(mixed, stateless)))
				.sessionConfig(config).build();
		assertSame(config, server.getSessionConfig().orElseThrow());
		assertEquals(Set.of(McpProtocolVersion.V2025_11_25), mixed.getSessionProtocolVersions());
		assertTrue(stateless.getSessionProtocolVersions().isEmpty());
	}

	private static McpSessionConfig.Builder configBuilder() {
		return McpSessionConfig.withOwnerKeyResolver(admissionIdentity -> "subject");
	}

	private static McpEndpoint.Builder endpointBuilder(Set<McpProtocolVersion> versions) {
		return McpEndpoint.withPath("/mcp", McpImplementation.withNameAndVersion("catalog", "1").build(), versions);
	}

	private static McpServer.Builder serverBuilder(McpEndpoint endpoint) {
		return McpServer.withPort(0).endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)));
	}
}
