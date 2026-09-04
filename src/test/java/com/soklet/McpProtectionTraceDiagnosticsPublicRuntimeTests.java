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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Focused public coverage for MCP protection and trace diagnostics.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Timeout(60)
public class McpProtectionTraceDiagnosticsPublicRuntimeTests {
	private static final String HOST = "127.0.0.1";

	@Test
	public void configurationModesAndApplicationProtectionProjectExactPresenceRules() {
		McpServer unconfigured = serverBuilder("unconfigured").build();
		assertSecurityDiagnostics(unconfigured.getDiagnostics(),
				McpProtectionMode.NONE, false,
				Optional.empty(), Optional.empty());

		McpServer traceOnly = serverBuilder("trace-only")
				.traceCorrelationKey(traceKey("trace-only", 1))
				.build();
		assertSecurityDiagnostics(traceOnly.getDiagnostics(),
				McpProtectionMode.NONE, false, Optional.empty(),
				traceOnly.getTraceCorrelationControl().getFingerprint());

		McpRequestStateProtector protector = protector("custom-protector");
		McpServer custom = serverBuilder("custom")
				.protectionConfig(McpProtectionConfig
						.withRequestStateProtector(protector).build())
				.build();
		assertSecurityDiagnostics(custom.getDiagnostics(),
				McpProtectionMode.CUSTOM_PROTECTOR, true,
				Optional.empty(), Optional.empty());

		McpServer production = serverBuilder("production")
				.protectionConfig(productionProtectionConfig())
				.build();
		assertSecurityDiagnostics(production.getDiagnostics(),
				McpProtectionMode.PRODUCTION_KEYRING, false,
				Optional.of(protectionFingerprint(production)), Optional.empty());

		McpServer development = serverBuilder("development")
				.protectionConfig(McpProtectionConfig
						.withDevelopmentEphemeralProtection().build())
				.build();
		assertSecurityDiagnostics(development.getDiagnostics(),
				McpProtectionMode.DEVELOPMENT_EPHEMERAL, false,
				Optional.empty(), Optional.empty());
	}

	@Test
	public void liveRotationsChangeOnlyFreshSnapshotsAcrossStopAndRestart()
			throws Exception {
		McpTraceCorrelationKey rotatedTraceKey = traceKey("trace-b", 4);
		McpServer firstServer = serverBuilder("lifecycle")
				.protectionConfig(productionProtectionConfig())
				.traceCorrelationKey(traceKey("trace-a", 3))
				.build();
		Soklet firstOwner = managedSoklet(firstServer);
		Soklet secondOwner = null;
		McpServerDiagnostics beforeStart = firstServer.getDiagnostics();
		McpProtectionKeyringFingerprint initialProtection =
				protectionFingerprint(firstServer);
		McpTraceCorrelationFingerprint initialTrace =
				traceFingerprint(firstServer);
		assertSecurityDiagnostics(beforeStart,
				McpProtectionMode.PRODUCTION_KEYRING, false,
				Optional.of(initialProtection), Optional.of(initialTrace));
		Assertions.assertEquals(McpServerStatus.NOT_STARTED,
				beforeStart.getStatus());

		try {
			firstOwner.start();
			McpServerDiagnostics started = firstServer.getDiagnostics();
			Assertions.assertEquals(McpServerStatus.RUNNING,
					started.getStatus());
			assertSecurityDiagnostics(started,
					McpProtectionMode.PRODUCTION_KEYRING, false,
					Optional.of(initialProtection), Optional.of(initialTrace));

			firstServer.getProtectionControl().activateStagedKey("protection-b");
			firstServer.getTraceCorrelationControl().rotateActiveKey(
					rotatedTraceKey);
			McpProtectionKeyringSnapshot rotatedProtectionSnapshot = firstServer
					.getProtectionControl().getKeyringSnapshot().orElseThrow();
			Assertions.assertEquals("protection-b",
					rotatedProtectionSnapshot.getActiveKeyId());
			Assertions.assertEquals(Set.of("protection-a"),
					rotatedProtectionSnapshot.getVerificationKeyIds());
			McpProtectionKeyring rotatedProtectionKeyring = McpProtectionKeyring
					.withActiveKey(protectionKey("protection-b", 2))
					.addVerificationKey(protectionKey("protection-a", 1))
					.build();
			McpProtectionKeyringFingerprint rotatedProtection =
					protectionFingerprint(firstServer);
			McpTraceCorrelationFingerprint rotatedTrace =
					traceFingerprint(firstServer);
			Assertions.assertNotEquals(initialProtection, rotatedProtection);
			Assertions.assertNotEquals(initialTrace, rotatedTrace);
			assertSecurityDiagnostics(firstServer.getDiagnostics(),
					McpProtectionMode.PRODUCTION_KEYRING, false,
					Optional.of(rotatedProtection), Optional.of(rotatedTrace));

			firstOwner.close();
			McpServerDiagnostics stopped = firstServer.getDiagnostics();
			Assertions.assertEquals(McpServerStatus.TERMINATED, stopped.getStatus());
			assertSecurityDiagnostics(stopped,
					McpProtectionMode.PRODUCTION_KEYRING, false,
					Optional.of(rotatedProtection), Optional.of(rotatedTrace));

			McpServer secondServer = serverBuilder("lifecycle")
					.protectionConfig(McpProtectionConfig
							.withKeyring(rotatedProtectionKeyring).build())
					.traceCorrelationKey(rotatedTraceKey)
					.build();
			Assertions.assertEquals(rotatedProtection,
					protectionFingerprint(secondServer));
			Assertions.assertEquals(rotatedTrace, traceFingerprint(secondServer));
			secondOwner = managedSoklet(secondServer);
			secondOwner.start();
			McpServerDiagnostics restarted = secondServer.getDiagnostics();
			Assertions.assertEquals(McpServerStatus.RUNNING,
					restarted.getStatus());
			assertSecurityDiagnostics(restarted,
					McpProtectionMode.PRODUCTION_KEYRING, false,
					Optional.of(rotatedProtection), Optional.of(rotatedTrace));

			assertSecurityDiagnostics(beforeStart,
					McpProtectionMode.PRODUCTION_KEYRING, false,
					Optional.of(initialProtection), Optional.of(initialTrace));
			assertSecurityDiagnostics(started,
					McpProtectionMode.PRODUCTION_KEYRING, false,
					Optional.of(initialProtection), Optional.of(initialTrace));
			Assertions.assertEquals(McpServerStatus.RUNNING,
					started.getStatus());
			assertSecurityDiagnostics(stopped,
					McpProtectionMode.PRODUCTION_KEYRING, false,
					Optional.of(rotatedProtection), Optional.of(rotatedTrace));
			Assertions.assertEquals(McpServerStatus.TERMINATED, stopped.getStatus());
		} finally {
			try {
				if (secondOwner != null)
					secondOwner.close();
			} finally {
				firstOwner.close();
			}
		}
	}

	@Test
	public void concurrentPublicReadsExposeOnlyValidPerFieldLiveSecurityValues()
			throws Exception {
		McpProtectionKey protectionA = protectionKey("protection-a", 1);
		McpProtectionKey protectionB = protectionKey("protection-b", 2);
		McpTraceCorrelationKey traceA = traceKey("trace-a", 3);
		McpTraceCorrelationKey traceB = traceKey("trace-b", 4);
		McpServer server = serverBuilder("concurrent")
				.protectionConfig(McpProtectionConfig.withKeyring(
						McpProtectionKeyring.withActiveKey(protectionA)
								.addVerificationKey(protectionB).build()).build())
				.traceCorrelationKey(traceA)
				.build();

		McpProtectionKeyringFingerprint protectionAFingerprint =
				protectionFingerprint(server);
		server.getProtectionControl().activateStagedKey("protection-b");
		McpProtectionKeyringFingerprint protectionBFingerprint =
				protectionFingerprint(server);
		server.getProtectionControl().activateStagedKey("protection-a");
		Set<McpProtectionKeyringFingerprint> protectionFingerprints = Set.of(
				protectionAFingerprint, protectionBFingerprint);

		McpTraceCorrelationFingerprint traceAFingerprint =
				traceFingerprint(server);
		server.getTraceCorrelationControl().rotateActiveKey(traceB);
		McpTraceCorrelationFingerprint traceBFingerprint =
				traceFingerprint(server);
		server.getTraceCorrelationControl().rotateActiveKey(traceA);
		Set<McpTraceCorrelationFingerprint> traceFingerprints =
				Set.of(traceAFingerprint, traceBFingerprint);

		McpServerDiagnostics retained = server.getDiagnostics();
		// Protection and trace rotations are independently legal. This verifies
		// each public field against its own complete live-value set; structural
		// review owns the shared security-snapshot linearization proof.
		CountDownLatch start = new CountDownLatch(1);
		AtomicInteger snapshotReads = new AtomicInteger();
		ExecutorService executor = Executors.newFixedThreadPool(4);
		try {
			List<Future<?>> futures = new ArrayList<>();
			futures.add(executor.submit(() -> {
				start.await();
				for (int iteration = 0; iteration < 500; ++iteration)
					server.getProtectionControl().rotateActiveKey(
							iteration % 2 == 0 ? protectionB : protectionA);
				return null;
			}));
			futures.add(executor.submit(() -> {
				start.await();
				for (int iteration = 0; iteration < 500; ++iteration)
					server.getTraceCorrelationControl().rotateActiveKey(
							iteration % 2 == 0 ? traceB : traceA);
				return null;
			}));
			for (int reader = 0; reader < 2; ++reader)
				futures.add(executor.submit(() -> {
					start.await();
					for (int iteration = 0; iteration < 1_000; ++iteration) {
						McpServerDiagnostics diagnostics = server.getDiagnostics();
						Assertions.assertEquals(
								McpProtectionMode.PRODUCTION_KEYRING,
								diagnostics.getProtectionMode());
						Assertions.assertEquals(Boolean.FALSE, diagnostics
								.isApplicationRequestStateProtectorConfigured());
						Assertions.assertTrue(protectionFingerprints.contains(
								diagnostics.getProtectionKeyringFingerprint()
										.orElseThrow()));
						Assertions.assertTrue(traceFingerprints.contains(diagnostics
								.getTraceCorrelationFingerprint()
								.orElseThrow()));
						snapshotReads.incrementAndGet();
					}
					return null;
				}));

			start.countDown();
			for (Future<?> future : futures)
				future.get(20, TimeUnit.SECONDS);
		} finally {
			start.countDown();
			executor.shutdownNow();
		}

		Assertions.assertEquals(2_000, snapshotReads.get());
		assertSecurityDiagnostics(retained,
				McpProtectionMode.PRODUCTION_KEYRING, false,
				Optional.of(protectionAFingerprint),
				Optional.of(traceAFingerprint));
	}

	@Test
	public void diagnosticRenderingContainsNoKeyIdsOrMaterialCanaries() {
		String protectionKeyIdCanary = "PROTECTION-KEY-ID-CANARY";
		String traceKeyIdCanary = "TRACE-KEY-ID-CANARY";
		String protectionMaterialCanary =
				"PROTECTION-MATERIAL-CANARY-".repeat(2);
		String traceMaterialCanary = "TRACE-MATERIAL-CANARY-".repeat(2);
		McpServer production = serverBuilder("redaction")
				.protectionConfig(McpProtectionConfig.withKeyring(
						McpProtectionKeyring.withActiveKey(
								McpProtectionKey.fromIdAndBytes(
										protectionKeyIdCanary,
										protectionMaterialCanary.getBytes(
												StandardCharsets.US_ASCII)))
								.build()).build())
				.traceCorrelationKey(McpTraceCorrelationKey.fromIdAndBytes(
						traceKeyIdCanary, traceMaterialCanary.getBytes(
								StandardCharsets.US_ASCII)))
				.build();
		McpServerDiagnostics productionDiagnostics = production.getDiagnostics();
		String productionRendering = productionDiagnostics.toString();
		for (String canary : List.of(protectionKeyIdCanary, traceKeyIdCanary,
				protectionMaterialCanary, traceMaterialCanary))
			Assertions.assertFalse(productionRendering.contains(canary),
					productionRendering);
		Assertions.assertTrue(productionRendering.contains(
				productionDiagnostics.getProtectionKeyringFingerprint()
						.orElseThrow().getValue()));
		Assertions.assertTrue(productionRendering.contains(
				productionDiagnostics.getTraceCorrelationFingerprint()
						.orElseThrow().getValue()));

		String protectorIdentityCanary = "CUSTOM-PROTECTOR-IDENTITY-CANARY";
		McpServer custom = serverBuilder("provider-redaction")
				.protectionConfig(McpProtectionConfig.withRequestStateProtector(
						protector(protectorIdentityCanary)).build())
				.build();
		String customRendering = custom.getDiagnostics().toString();
		Assertions.assertFalse(customRendering.contains(protectorIdentityCanary),
				customRendering);
		Assertions.assertEquals(Boolean.TRUE, custom.getDiagnostics()
				.isApplicationRequestStateProtectorConfigured());
	}

	private static void assertSecurityDiagnostics(
			McpServerDiagnostics diagnostics, McpProtectionMode protectionMode,
			boolean applicationRequestStateProtectorConfigured,
			Optional<McpProtectionKeyringFingerprint> protectionFingerprint,
			Optional<McpTraceCorrelationFingerprint> traceFingerprint) {
		Assertions.assertEquals(protectionMode, diagnostics.getProtectionMode());
		Assertions.assertEquals(
				Boolean.valueOf(applicationRequestStateProtectorConfigured),
				diagnostics.isApplicationRequestStateProtectorConfigured());
		Assertions.assertEquals(protectionFingerprint,
				diagnostics.getProtectionKeyringFingerprint());
		Assertions.assertEquals(traceFingerprint,
				diagnostics.getTraceCorrelationFingerprint());
	}

	private static McpProtectionKeyringFingerprint protectionFingerprint(
			McpServer server) {
		return server.getProtectionControl().getKeyringSnapshot().orElseThrow()
				.getFingerprint();
	}

	private static McpTraceCorrelationFingerprint traceFingerprint(
			McpServer server) {
		return server.getTraceCorrelationControl().getFingerprint()
				.orElseThrow();
	}

	private static McpProtectionConfig productionProtectionConfig() {
		return McpProtectionConfig.withKeyring(McpProtectionKeyring
				.withActiveKey(protectionKey("protection-a", 1))
				.addVerificationKey(protectionKey("protection-b", 2))
				.build()).build();
	}

	private static McpProtectionKey protectionKey(String keyId, int fill) {
		return McpProtectionKey.fromIdAndBytes(keyId, keyMaterial(fill));
	}

	private static McpTraceCorrelationKey traceKey(String keyId, int fill) {
		return McpTraceCorrelationKey.fromIdAndBytes(keyId, keyMaterial(fill));
	}

	private static byte[] keyMaterial(int fill) {
		byte[] keyMaterial = new byte[32];
		Arrays.fill(keyMaterial, (byte) fill);
		return keyMaterial;
	}

	private static McpRequestStateProtector protector(String identity) {
		return new McpRequestStateProtector() {
			@Override
			public String seal(McpRequestStateProtectionContext context,
					byte[] plaintext) {
				return "opaque";
			}

			@Override
			public byte[] open(McpRequestStateProtectionContext context,
					String protectedState) {
				return new byte[]{1};
			}

			@Override
			public String toString() {
				return identity;
			}
		};
	}

	private static McpServer.Builder serverBuilder(String implementationName) {
		McpEndpoint endpoint = McpEndpoint.withPath("/mcp", McpImplementation.withNameAndVersion(
						implementationName, "4.0.0").build())
				.build();
		return McpServer.withPort(0).endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.host(HOST)
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(HOST));
	}

	private static Soklet managedSoklet(McpServer server) {
		return Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(
						ResourceMethodResolver.fromMethods(Set.of()))
				.lifecyclePolicy(LifecyclePolicy.builder()
						.startupTimeout(Duration.ofSeconds(5))
						.startupCancelationTimeout(Duration.ofSeconds(2))
						.gracefulShutdownTimeout(Duration.ofSeconds(2))
						.forcedShutdownTimeout(Duration.ofSeconds(1))
						.build())
				.build());
	}
}
