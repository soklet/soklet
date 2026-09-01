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
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract coverage for future-facing settings attached before the Phase 4
 * {@link McpServer} host freezes.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
class McpServerFutureConfigurationTests {
	@Test
	void operationalControlsHavePositiveFiniteDefaultsAndAreRetained() {
		DefaultMcpServer defaults = (DefaultMcpServer) serverBuilder().build();
		DefaultMcpServer configured = (DefaultMcpServer) serverBuilder()
				.streamQueueCapacity(7)
				.writeTimeout(Duration.ofSeconds(9))
				.keepAliveInterval(Duration.ofSeconds(8))
				.maximumSubscriptionsPerPrincipal(11)
				.maximumSubscriptionDuration(Duration.ofSeconds(12))
				.logRawValidatedTraceIds(true)
				.build();

		assertEquals(128, defaults.streamQueueCapacity());
		assertEquals(Duration.ofSeconds(30), defaults.writeTimeout());
		assertEquals(Duration.ofSeconds(15), defaults.keepAliveInterval());
		assertEquals(32, defaults.maximumSubscriptionsPerPrincipal());
		assertEquals(Duration.ofHours(24),
				defaults.maximumSubscriptionDuration());
		assertFalse(defaults.logRawValidatedTraceIds());

		assertEquals(7, configured.streamQueueCapacity());
		assertEquals(Duration.ofSeconds(9), configured.writeTimeout());
		assertEquals(Duration.ofSeconds(8), configured.keepAliveInterval());
		assertEquals(11, configured.maximumSubscriptionsPerPrincipal());
		assertEquals(Duration.ofSeconds(12),
				configured.maximumSubscriptionDuration());
		assertTrue(configured.logRawValidatedTraceIds());
		assertFalse(configured.getTraceCorrelationControl().isEnabled());
	}

	@Test
	void operationalControlsRejectNonpositiveAndUnrepresentableValues() {
		McpServer.Builder builder = McpServer.withPort(0);

		assertThrows(NullPointerException.class,
				() -> builder.streamQueueCapacity(null));
		assertThrows(IllegalArgumentException.class,
				() -> builder.streamQueueCapacity(0));
		assertThrows(IllegalArgumentException.class,
				() -> builder.streamQueueCapacity(-1));
		assertThrows(IllegalArgumentException.class,
				() -> builder.maximumSubscriptionsPerPrincipal(0));
		assertThrows(IllegalArgumentException.class,
				() -> builder.maximumSubscriptionsPerPrincipal(-1));
		assertThrows(NullPointerException.class,
				() -> builder.maximumSubscriptionsPerPrincipal(null));
		assertThrows(NullPointerException.class,
				() -> builder.logRawValidatedTraceIds(null));

		for (DurationSetter setter : List.<DurationSetter>of(
				McpServer.Builder::writeTimeout,
				McpServer.Builder::keepAliveInterval,
				McpServer.Builder::maximumSubscriptionDuration)) {
			assertThrows(NullPointerException.class,
					() -> setter.set(builder, null));
			assertThrows(IllegalArgumentException.class,
					() -> setter.set(builder, Duration.ZERO));
			assertThrows(IllegalArgumentException.class,
					() -> setter.set(builder, Duration.ofNanos(-1)));
			assertThrows(IllegalArgumentException.class,
					() -> setter.set(builder,
							Duration.ofSeconds(Long.MAX_VALUE)));
		}

		assertThrows(IllegalStateException.class, () -> serverBuilder()
				.writeTimeout(Duration.ofSeconds(8))
				.keepAliveInterval(Duration.ofSeconds(8))
				.build());
		assertThrows(IllegalStateException.class, () -> serverBuilder()
				.writeTimeout(Duration.ofSeconds(8))
				.keepAliveInterval(Duration.ofSeconds(9))
				.build());
	}

	@Test
	void omittedSecurityConfigurationExposesDisabledControlViews() {
		McpServer server = serverBuilder().build();

		assertEquals(McpProtectionMode.NO_FRAMEWORK_KEYS,
				server.getProtectionControl().getProtectionMode());
		assertSame(server.getProtectionControl(), server.getTraceCorrelationControl());
		assertTrue(((DefaultMcpServer) server).protectionConfig().isEmpty());
		assertTrue(server.getProtectionControl().getKeyRingSnapshot().isEmpty());
		assertFalse(server.getTraceCorrelationControl().isEnabled());
		assertTrue(server.getTraceCorrelationControl().getActiveKeyId().isEmpty());
		assertThrows(IllegalStateException.class,
				() -> server.getProtectionControl().stageVerificationKey(
						protectionKey("disabled", 1)));
		assertThrows(IllegalStateException.class,
				() -> server.getTraceCorrelationControl().rotateActiveKey(
						traceKey("disabled", 2)));
	}

	@Test
	void reusedConfigurationProducesIndependentServerOwnedControlState() {
		McpProtectionConfig protectionConfig = McpProtectionConfig.withKeyRing(
				McpProtectionKeyRing.withActiveKey(protectionKey("active-a", 1))
						.verificationKey(protectionKey("verify-b", 2))
						.build()).build();
		McpTraceCorrelationKey initialTrace = traceKey("trace-c", 3);
		McpServer.Builder builder = serverBuilder()
				.protectionConfig(protectionConfig)
				.traceCorrelationKey(initialTrace);
		McpServer first = builder.build();
		McpServer second = builder.build();

		assertSame(protectionConfig,
				((DefaultMcpServer) first).protectionConfig().orElseThrow());
		assertNotSame(first.getProtectionControl(),
				second.getProtectionControl());
		assertEquals("active-a", first.getProtectionControl()
				.getKeyRingSnapshot().orElseThrow().getActiveKeyId());
		assertEquals("trace-c", first.getTraceCorrelationControl()
				.getActiveKeyId().orElseThrow());

		first.getProtectionControl().rotateTo(protectionKey("active-d", 4));
		first.getTraceCorrelationControl().rotateActiveKey(traceKey("trace-e", 5));

		assertEquals("active-d", first.getProtectionControl()
				.getKeyRingSnapshot().orElseThrow().getActiveKeyId());
		assertEquals("trace-e", first.getTraceCorrelationControl()
				.getActiveKeyId().orElseThrow());
		assertEquals("active-a", second.getProtectionControl()
				.getKeyRingSnapshot().orElseThrow().getActiveKeyId());
		assertEquals("trace-c", second.getTraceCorrelationControl()
				.getActiveKeyId().orElseThrow());
	}

	@Test
	void builderRejectsNullAndCrossPurposeKeyReuse() {
		McpServer.Builder builder = McpServer.withPort(0);
		assertThrows(NullPointerException.class,
				() -> builder.protectionConfig(null));
		assertThrows(NullPointerException.class,
				() -> builder.traceCorrelationKey(null));

		byte[] sharedMaterial = keyMaterial(7);
		McpProtectionConfig protectionConfig = McpProtectionConfig.withKeyRing(
				McpProtectionKeyRing.withActiveKey(
						McpProtectionKey.fromIdAndBytes("protection",
								sharedMaterial)).build()).build();
		McpTraceCorrelationKey traceKey = McpTraceCorrelationKey.fromIdAndBytes(
				"trace", sharedMaterial);

		assertThrows(IllegalArgumentException.class, () -> serverBuilder()
				.protectionConfig(protectionConfig)
				.traceCorrelationKey(traceKey)
				.build());
	}

	private static McpServer.Builder serverBuilder() {
		McpEndpoint endpoint = McpEndpoint.withPath("/mcp")
				.serverInformation(McpImplementation.withNameAndVersion(
						"future-configuration-tests", "4.0.0").build())
				.build();
		return McpServer.withPort(0)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.admissionController(
						McpAdmissionController.acceptAllInstance());
	}

	private static McpProtectionKey protectionKey(String id, int fill) {
		return McpProtectionKey.fromIdAndBytes(id, keyMaterial(fill));
	}

	private static McpTraceCorrelationKey traceKey(String id, int fill) {
		return McpTraceCorrelationKey.fromIdAndBytes(id, keyMaterial(fill));
	}

	private static byte[] keyMaterial(int fill) {
		byte[] keyMaterial = new byte[32];
		Arrays.fill(keyMaterial, (byte) fill);
		return keyMaterial;
	}

	@FunctionalInterface
	private interface DurationSetter {
		void set(McpServer.Builder builder, Duration duration);
	}
}
