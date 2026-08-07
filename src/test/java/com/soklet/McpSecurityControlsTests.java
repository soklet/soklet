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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Value, secrecy, and concurrency contracts for MCP security controls.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public class McpSecurityControlsTests {
	private static final String PROTECTION_GOLDEN_FINGERPRINT =
			"K9oRkAG6QKeHW5rCTMNcocxoaQVySSJLmnvXbD4AV90";
	private static final String TRACE_GOLDEN_FINGERPRINT =
			"q6lgRnXgzPRK0yoi_va7Qcax0EjCUuFum3A38-Vp4J4";
	private static final String REQUEST_STATE_GOLDEN =
			"soklet-mcp-request-state-v1."
			+ "AQZhY3RpdmUYc29rbGV0LW1jcC1wcm90ZWN0aW9uLXYx"
			+ "QEFCQ0RFRkdISUpLTE1OT1BRUlNUVVZXAAAAAAAAAABgYWJj"
			+ "ZGVmZ2hpams1IYFOeRW8Xb62L4ZXI9NiSNqcys3ypJOe5JyH9WQ";

	@Test
	public void keyFactoriesValidateIdsLengthsCopiesAndRedaction() {
		byte[] protectionMaterial = bytesFrom(0);
		byte[] traceMaterial = bytesFrom(32);
		McpProtectionKey protectionKey = McpProtectionKey.fromIdAndBytes(
				"protection-key", protectionMaterial);
		McpTraceCorrelationKey traceKey =
				McpTraceCorrelationKey.fromIdAndBytes("trace-key", traceMaterial);

		Arrays.fill(protectionMaterial, (byte) 127);
		Arrays.fill(traceMaterial, (byte) 127);
		byte[] exposedProtectionCopy = protectionKey.copyKeyMaterial();
		byte[] exposedTraceCopy = traceKey.copyKeyMaterial();
		Assertions.assertArrayEquals(bytesFrom(0), exposedProtectionCopy);
		Assertions.assertArrayEquals(bytesFrom(32), exposedTraceCopy);
		Arrays.fill(exposedProtectionCopy, (byte) 126);
		Arrays.fill(exposedTraceCopy, (byte) 126);
		Assertions.assertArrayEquals(bytesFrom(0),
				protectionKey.copyKeyMaterial());
		Assertions.assertArrayEquals(bytesFrom(32), traceKey.copyKeyMaterial());
		Assertions.assertEquals(
				"McpProtectionKey{keyId='protection-key', keyMaterial=<redacted>}",
				protectionKey.toString());
		Assertions.assertEquals(
				"McpTraceCorrelationKey{keyId='trace-key', keyMaterial=<redacted>}",
				traceKey.toString());

		for (String invalidId : List.of("", "contains space", "slash/",
				"café", "a".repeat(65))) {
			Assertions.assertThrows(IllegalArgumentException.class, () ->
					McpProtectionKey.fromIdAndBytes(invalidId, bytesFrom(0)));
			Assertions.assertThrows(IllegalArgumentException.class, () ->
					McpTraceCorrelationKey.fromIdAndBytes(invalidId, bytesFrom(0)));
		}
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				McpProtectionKey.fromIdAndBytes("short", new byte[31]));
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				McpTraceCorrelationKey.fromIdAndBytes("short", new byte[31]));
		Assertions.assertThrows(NullPointerException.class, () ->
				McpProtectionKey.fromIdAndBytes(null, bytesFrom(0)));
		Assertions.assertThrows(NullPointerException.class, () ->
				McpProtectionKey.fromIdAndBytes("key", null));
		Assertions.assertThrows(NullPointerException.class, () ->
				McpTraceCorrelationKey.fromIdAndBytes(null, bytesFrom(0)));
		Assertions.assertThrows(NullPointerException.class, () ->
				McpTraceCorrelationKey.fromIdAndBytes("key", null));
	}

	@Test
	public void initialRingRejectsDuplicateIdsAndMaterialAndCopiesValues() {
		McpProtectionKey active = protectionKey("active", 0);
		McpProtectionKey verification = protectionKey("verification", 32);
		McpProtectionKeyRing.Builder builder =
				McpProtectionKeyRing.withActiveKey(active)
						.verificationKey(verification);
		McpProtectionKeyRing ring = builder.build();
		builder.verificationKey(protectionKey("later", 64));

		DefaultMcpSecurityControls controls = controls(ring, null);
		McpProtectionKeyRingSnapshot snapshot = controls.getKeyRingSnapshot()
				.orElseThrow();
		Assertions.assertEquals("active", snapshot.getActiveKeyId());
		Assertions.assertEquals(Set.of("verification"),
				snapshot.getVerificationKeyIds());
		Assertions.assertThrows(UnsupportedOperationException.class, () ->
				snapshot.getVerificationKeyIds().add("forbidden"));

		Assertions.assertThrows(IllegalArgumentException.class, () ->
				McpProtectionKeyRing.withActiveKey(active)
						.verificationKey(protectionKey("active", 64)));
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				McpProtectionKeyRing.withActiveKey(active)
						.verificationKey(protectionKey("alias", 0)));
	}

	@Test
	public void bulkInitialRingMutationIsAtomicWhenALateKeyIsInvalid() {
		McpProtectionKeyRing.Builder duplicateBuilder = McpProtectionKeyRing
				.withActiveKey(protectionKey("active", 0));
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				duplicateBuilder.verificationKeys(List.of(
						protectionKey("first", 32),
						protectionKey("duplicate-material", 32))));
		duplicateBuilder.verificationKey(protectionKey("first", 32));

		McpProtectionKeyRing.Builder nullBuilder = McpProtectionKeyRing
				.withActiveKey(protectionKey("active", 0));
		List<McpProtectionKey> keysWithNull = new ArrayList<>();
		keysWithNull.add(protectionKey("first", 32));
		keysWithNull.add(null);
		Assertions.assertThrows(NullPointerException.class,
				() -> nullBuilder.verificationKeys(keysWithNull));
		nullBuilder.verificationKey(protectionKey("first", 32));

		for (McpProtectionKeyRing ring : List.of(duplicateBuilder.build(),
				nullBuilder.build()))
			Assertions.assertEquals(Set.of("first"), controls(ring, null)
					.getKeyRingSnapshot().orElseThrow()
					.getVerificationKeyIds());
	}

	@Test
	public void protectionFingerprintMatchesFrozenGoldenConstruction() {
		McpProtectionKeyRing ring = McpProtectionKeyRing
				.withActiveKey(protectionKey("active", 0))
				.verificationKeys(List.of(
						protectionKey("verify-b", 64),
						protectionKey("verify-a", 32)))
				.build();
		McpProtectionKeyRingFingerprint fingerprint = controls(ring, null)
				.getKeyRingSnapshot().orElseThrow().getFingerprint();

		Assertions.assertEquals("v1", fingerprint.getVersion());
		Assertions.assertEquals("soklet-mcp-protection-v1",
				fingerprint.getProfile());
		Assertions.assertEquals(PROTECTION_GOLDEN_FINGERPRINT,
				fingerprint.getValue());
		Assertions.assertEquals(PROTECTION_GOLDEN_FINGERPRINT,
				fingerprint.toString());
		Assertions.assertEquals(fingerprint, controls(ring, null)
				.getKeyRingSnapshot().orElseThrow().getFingerprint());
		Assertions.assertEquals(fingerprint.hashCode(), controls(ring, null)
				.getKeyRingSnapshot().orElseThrow().getFingerprint().hashCode());
		Assertions.assertNotEquals(fingerprint, controls(McpProtectionKeyRing
				.withActiveKey(protectionKey("active", 1))
				.verificationKeys(List.of(
						protectionKey("verify-a", 32),
						protectionKey("verify-b", 64)))
				.build(), null).getKeyRingSnapshot().orElseThrow()
				.getFingerprint());
	}

	@Test
	public void protectionMutationsAreRetrySafeAndRejectedMutationsAreAtomic() {
		McpProtectionKey active = protectionKey("active", 0);
		McpProtectionKey staged = protectionKey("staged", 32);
		DefaultMcpSecurityControls controls = controls(McpProtectionKeyRing
				.withActiveKey(active).verificationKey(staged).build(),
				traceKey("trace", 96));

		McpProtectionKeyRingFingerprint initialFingerprint = controls
				.getKeyRingSnapshot().orElseThrow().getFingerprint();
		controls.stageVerificationKey(protectionKey("active", 0));
		controls.stageVerificationKey(protectionKey("staged", 32));
		Assertions.assertEquals(initialFingerprint, controls.getKeyRingSnapshot()
				.orElseThrow().getFingerprint());
		assertRejectedWithoutProtectionChange(controls, () ->
				controls.stageVerificationKey(protectionKey("active", 64)));
		assertRejectedWithoutProtectionChange(controls, () ->
				controls.stageVerificationKey(protectionKey("alias", 32)));
		assertRejectedWithoutProtectionChange(controls, () ->
				controls.stageVerificationKey(protectionKey("trace-alias", 96)));

		controls.activateStagedKey("staged");
		controls.activateStagedKey("staged");
		McpProtectionKeyRingSnapshot stagedSnapshot = controls
				.getKeyRingSnapshot().orElseThrow();
		Assertions.assertEquals("staged", stagedSnapshot.getActiveKeyId());
		Assertions.assertEquals(Set.of("active"),
				stagedSnapshot.getVerificationKeyIds());
		assertRejectedWithoutProtectionChange(controls,
				() -> controls.activateStagedKey("unknown"));

		controls.rotateTo(protectionKey("rotated", 64));
		controls.rotateTo(protectionKey("rotated", 64));
		McpProtectionKeyRingSnapshot rotatedSnapshot = controls
				.getKeyRingSnapshot().orElseThrow();
		Assertions.assertEquals("rotated", rotatedSnapshot.getActiveKeyId());
		Assertions.assertEquals(Set.of("active", "staged"),
				rotatedSnapshot.getVerificationKeyIds());
		Assertions.assertFalse(controls.removeVerificationKey("absent"));
		Assertions.assertTrue(controls.removeVerificationKey("active"));
		Assertions.assertFalse(controls.removeVerificationKey("active"));
		assertRejectedWithoutProtectionChange(controls,
				() -> controls.removeVerificationKey("rotated"));
	}

	@Test
	public void protectionControlsAreIndependentPerServer() {
		McpProtectionConfig config = McpProtectionConfig.withKeyRing(
				McpProtectionKeyRing.withActiveKey(
						protectionKey("active", 0)).build()).build();
		DefaultMcpSecurityControls first =
				new DefaultMcpSecurityControls(config, null);
		DefaultMcpSecurityControls second =
				new DefaultMcpSecurityControls(config, null);

		first.rotateTo(protectionKey("rotated", 32));
		Assertions.assertEquals("rotated", first.getKeyRingSnapshot()
				.orElseThrow().getActiveKeyId());
		Assertions.assertEquals("active", second.getKeyRingSnapshot()
				.orElseThrow().getActiveKeyId());
		Assertions.assertEquals(Set.of(), second.getKeyRingSnapshot()
				.orElseThrow().getVerificationKeyIds());
	}

	@Test
	public void nonproductionModesExposeNoRingAndRejectLiveRingMutation() {
		McpRequestStateProtector protector = new McpRequestStateProtector() {
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
		};
		List<DefaultMcpSecurityControls> controls = List.of(
				new DefaultMcpSecurityControls(null, null),
				new DefaultMcpSecurityControls(McpProtectionConfig
						.withDevelopmentEphemeralProtection().build(), null),
				new DefaultMcpSecurityControls(McpProtectionConfig
						.withRequestStateProtector(protector).build(), null));
		Assertions.assertEquals(List.of(
				McpProtectionMode.NO_FRAMEWORK_KEYS,
				McpProtectionMode.DEVELOPMENT_EPHEMERAL,
				McpProtectionMode.CUSTOM_PROTECTOR), controls.stream()
				.map(DefaultMcpSecurityControls::getProtectionMode).toList());

		for (DefaultMcpSecurityControls control : controls) {
			Assertions.assertEquals(Optional.empty(), control.getKeyRingSnapshot());
			Assertions.assertThrows(IllegalStateException.class, () ->
					control.stageVerificationKey(protectionKey("key", 0)));
			Assertions.assertThrows(IllegalStateException.class, () ->
					control.activateStagedKey("key"));
			Assertions.assertThrows(IllegalStateException.class, () ->
					control.rotateTo(protectionKey("key", 0)));
			Assertions.assertThrows(IllegalStateException.class, () ->
					control.removeVerificationKey("key"));
		}
	}

	@Test
	public void protectionConfigValidatesLimitsAndPreservesProviderIdentity() {
		McpRequestStateProtector protector = new McpRequestStateProtector() {
			@Override
			public String seal(McpRequestStateProtectionContext context,
					byte[] plaintext) {
				return "opaque";
			}

			@Override
			public byte[] open(McpRequestStateProtectionContext context,
					String protectedState) {
				return new byte[0];
			}
		};
		McpProtectionConfig config = McpProtectionConfig
				.withRequestStateProtector(protector)
				.maximumEncodedRequestStateBytes(100)
				.maximumDecodedRequestStateBytes(75)
				.maximumRequestStateLifetime(Duration.ofSeconds(30))
				.maximumRequestStateRounds(4)
				.build();
		Assertions.assertEquals(McpProtectionMode.CUSTOM_PROTECTOR,
				config.getProtectionMode());
		Assertions.assertSame(protector,
				config.getRequestStateProtector().orElseThrow());
		Assertions.assertEquals(Optional.empty(), config.getInitialKeyRing());
		Assertions.assertEquals(100,
				config.getMaximumEncodedRequestStateBytes());
		Assertions.assertEquals(75,
				config.getMaximumDecodedRequestStateBytes());
		Assertions.assertEquals(Duration.ofSeconds(30),
				config.getMaximumRequestStateLifetime());
		Assertions.assertEquals(4, config.getMaximumRequestStateRounds());

		Assertions.assertThrows(IllegalArgumentException.class, () ->
				McpProtectionConfig.withDevelopmentEphemeralProtection()
						.maximumEncodedRequestStateBytes(0));
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				McpProtectionConfig.withDevelopmentEphemeralProtection()
						.maximumDecodedRequestStateBytes(-1));
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				McpProtectionConfig.withDevelopmentEphemeralProtection()
						.maximumRequestStateRounds(0));
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				McpProtectionConfig.withDevelopmentEphemeralProtection()
						.maximumRequestStateLifetime(Duration.ZERO));
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				McpProtectionConfig.withDevelopmentEphemeralProtection()
						.maximumRequestStateLifetime(Duration.ofSeconds(
								Long.MAX_VALUE)));
		Assertions.assertThrows(IllegalStateException.class, () ->
				McpProtectionConfig.withDevelopmentEphemeralProtection()
						.maximumEncodedRequestStateBytes(50)
						.maximumDecodedRequestStateBytes(51).build());
	}

	@Test
	public void protectionContextAndSanitizedFailuresExposeNoMutableState() {
		byte[] associatedData = bytesFrom(0);
		McpRequestStateProtectionContext context =
				new McpRequestStateProtectionContext("/mcp", "2026-07-28",
						"tools/call", associatedData);
		Arrays.fill(associatedData, (byte) 127);
		byte[] firstCopy = context.getAssociatedData();
		Assertions.assertArrayEquals(bytesFrom(0), firstCopy);
		Arrays.fill(firstCopy, (byte) 126);
		Assertions.assertArrayEquals(bytesFrom(0), context.getAssociatedData());
		Assertions.assertEquals("/mcp", context.getEndpointPath());
		Assertions.assertEquals("2026-07-28", context.getProtocolVersion());
		Assertions.assertEquals("tools/call", context.getMethod());

		McpRequestStateProtectionException invalid =
				McpRequestStateProtectionException.fromInvalidState();
		McpRequestStateProtectionException unavailable =
				McpRequestStateProtectionException
						.fromProtectorUnavailable();
		Assertions.assertEquals(
				McpRequestStateProtectionException.Reason.INVALID_STATE,
				invalid.getReason());
		Assertions.assertEquals("Request state is invalid.",
				invalid.getMessage());
		Assertions.assertNull(invalid.getCause());
		Assertions.assertEquals(
				McpRequestStateProtectionException.Reason.PROTECTOR_UNAVAILABLE,
				unavailable.getReason());
		Assertions.assertEquals("Request-state protection is unavailable.",
				unavailable.getMessage());
		Assertions.assertNull(unavailable.getCause());
		Assertions.assertEquals("An MCP protection key is still in use.",
				new McpKeyInUseException().getMessage());
	}

	@Test
	public void builtInRequestStateProtectionMatchesFrozenVectorAndBinding()
			throws Exception {
		McpProtectionConfig config = protectionConfig("active", 0);
		DefaultMcpSecurityControls controls = deterministicControls(config,
				bytesFromLength(64, 24), bytesFromLength(96, 12));
		McpRequestStateProtectionContext context = protectionContext(32);
		byte[] plaintext = "{\"state\":\"ok\"}"
				.getBytes(StandardCharsets.UTF_8);

		String protectedState = controls.sealRequestState(context, plaintext);
		Assertions.assertEquals(REQUEST_STATE_GOLDEN, protectedState);
		controls.validateRequestStateStructure(protectedState);
		Assertions.assertArrayEquals(plaintext,
				controls.openRequestState(context, protectedState));

		DefaultMcpSecurityControls otherInstance = deterministicControls(config,
				bytesFromLength(1, 24));
		Assertions.assertArrayEquals(plaintext,
				otherInstance.openRequestState(context, protectedState));
		assertInvalidState(() -> controls.openRequestState(
				protectionContext(33), protectedState));
		assertInvalidState(() -> controls.openRequestState(context,
				tamperLastBase64UrlCharacter(protectedState)));
	}

	@Test
	public void builtInStructureRejectsNoncanonicalAndMalformedEnvelopes()
			throws Exception {
		DefaultMcpSecurityControls controls = deterministicControls(
				protectionConfig("active", 0), bytesFromLength(64, 24),
				bytesFromLength(96, 12));
		String valid = controls.sealRequestState(protectionContext(32),
				new byte[]{1});
		String suffix = valid.substring(
				DefaultMcpSecurityControls.REQUEST_STATE_PREFIX.length());
		String alphabet =
				"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
		int finalValue = alphabet.indexOf(suffix.charAt(suffix.length() - 1));
		String noncanonicalUnusedBits =
				DefaultMcpSecurityControls.REQUEST_STATE_PREFIX
				+ suffix.substring(0, suffix.length() - 1)
				+ alphabet.charAt(finalValue + 1);

		for (String invalid : List.of("", "opaque", valid + "=",
				valid + " ", DefaultMcpSecurityControls.REQUEST_STATE_PREFIX + "A",
				noncanonicalUnusedBits,
				DefaultMcpSecurityControls.REQUEST_STATE_PREFIX
						+ suffix.substring(0, suffix.length() - 1) + "/"))
			assertInvalidState(() ->
					controls.validateRequestStateStructure(invalid));

		byte[] decoded = Base64.getUrlDecoder().decode(suffix);
		for (int offset : List.of(0, 8, 9, 34, 65)) {
			byte[] malformed = decoded.clone();
			malformed[offset] ^= 1;
			String wire = DefaultMcpSecurityControls.REQUEST_STATE_PREFIX
					+ Base64.getUrlEncoder().withoutPadding()
					.encodeToString(malformed);
			if (offset < 34)
				assertInvalidState(() ->
						controls.validateRequestStateStructure(wire));
			else {
				controls.validateRequestStateStructure(wire);
				assertInvalidState(() -> controls.openRequestState(
						protectionContext(32), wire));
			}
		}
	}

	@Test
	public void builtInProtectionEnforcesEnvelopeAndWireSizeLimits() {
		McpProtectionKeyRing ring = McpProtectionKeyRing.withActiveKey(
				protectionKey("active", 0)).build();
		DefaultMcpSecurityControls decodedLimit = deterministicControls(
				McpProtectionConfig.withKeyRing(ring)
						.maximumEncodedRequestStateBytes(200)
						.maximumDecodedRequestStateBytes(90).build(),
				bytesFromLength(64, 24));
		DefaultMcpSecurityControls encodedLimit = deterministicControls(
				McpProtectionConfig.withKeyRing(ring)
						.maximumEncodedRequestStateBytes(120)
						.maximumDecodedRequestStateBytes(100).build(),
				bytesFromLength(64, 24));

		for (DefaultMcpSecurityControls controls :
				List.of(decodedLimit, encodedLimit))
			Assertions.assertThrows(IllegalStateException.class, () ->
					controls.sealRequestState(protectionContext(32),
							new byte[]{1}));
	}

	@Test
	public void invocationCapRollsEpochAndFailedNonceConsumesItsSlot()
			throws Exception {
		McpProtectionConfig config = protectionConfig("active", 0);
		SequenceEntropy entropy = new SequenceEntropy(
				bytesFromLength(64, 24), bytesFromLength(96, 12),
				bytesFromLength(108, 12), bytesFromLength(120, 12));
		DefaultMcpSecurityControls controls = new DefaultMcpSecurityControls(
				config, null, entropy, 2L, 0L);
		McpRequestStateProtectionContext context = protectionContext(32);
		String first = controls.sealRequestState(context, new byte[]{1});
		String second = controls.sealRequestState(context, new byte[]{2});
		String third = controls.sealRequestState(context, new byte[]{3});

		Assertions.assertEquals(0L, epochNumber(first));
		Assertions.assertEquals(0L, epochNumber(second));
		Assertions.assertEquals(1L, epochNumber(third));
		Assertions.assertArrayEquals(new byte[]{1},
				controls.openRequestState(context, first));
		Assertions.assertArrayEquals(new byte[]{3},
				controls.openRequestState(context, third));

		AtomicInteger entropyCalls = new AtomicInteger();
		DefaultMcpSecurityControls failedNonce =
				new DefaultMcpSecurityControls(config, null, destination -> {
					int call = entropyCalls.getAndIncrement();
					if (call == 0)
						copyExact(bytesFromLength(8, 24), destination);
					else if (call == 1)
						throw new IllegalStateException("simulated entropy failure");
					else
						copyExact(bytesFromLength(40, 12), destination);
				}, 1L, 0L);
		assertUnavailable(() -> failedNonce.sealRequestState(context,
				new byte[]{1}));
		String afterFailure = failedNonce.sealRequestState(context,
				new byte[]{2});
		Assertions.assertEquals(1L, epochNumber(afterFailure));
	}

	@Test
	public void concurrentSealsNeverOverallocateAnEpoch() throws Exception {
		AtomicInteger entropyCalls = new AtomicInteger();
		DefaultMcpSecurityControls controls = new DefaultMcpSecurityControls(
				protectionConfig("active", 0), null, destination ->
						Arrays.fill(destination,
								(byte) entropyCalls.incrementAndGet()), 2L, 0L);
		McpRequestStateProtectionContext context = protectionContext(32);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(8);
		try {
			List<Future<String>> futures = new ArrayList<>();
			for (int index = 0; index < 8; ++index) {
				int plaintextByte = index;
				futures.add(executor.submit(() -> {
					start.await();
					return controls.sealRequestState(context,
							new byte[]{(byte) plaintextByte});
				}));
			}
			start.countDown();
			Map<Long, Integer> epochCounts = new ConcurrentHashMap<>();
			for (Future<String> future : futures) {
				String state = future.get(5, TimeUnit.SECONDS);
				epochCounts.merge(epochNumber(state), 1, Integer::sum);
				Assertions.assertEquals(1,
						controls.openRequestState(context, state).length);
			}
			Assertions.assertEquals(Set.of(0L, 1L, 2L, 3L),
					epochCounts.keySet());
			Assertions.assertTrue(epochCounts.values().stream()
					.allMatch(count -> count == 2));
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	public void unsignedEpochExhaustionFailsClosed() throws Exception {
		DefaultMcpSecurityControls controls =
				new DefaultMcpSecurityControls(protectionConfig("active", 0),
						null, new SequenceEntropy(bytesFromLength(64, 24),
						bytesFromLength(96, 12)), 1L, -1L);
		McpRequestStateProtectionContext context = protectionContext(32);
		String lastEpoch = controls.sealRequestState(context, new byte[]{1});
		Assertions.assertEquals(-1L, epochNumber(lastEpoch));
		assertUnavailable(() -> controls.sealRequestState(context,
				new byte[]{2}));
	}

	@Test
	public void inFlightSealBlocksFormerKeyRemovalButNotRotation()
			throws Exception {
		BlockingNonceEntropy entropy = new BlockingNonceEntropy();
		DefaultMcpSecurityControls controls = new DefaultMcpSecurityControls(
				protectionConfig("first", 0), null, entropy, 10L, 0L);
		McpRequestStateProtectionContext context = protectionContext(32);
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			Future<String> oldSeal = executor.submit(() ->
					controls.sealRequestState(context, new byte[]{1}));
			Assertions.assertTrue(entropy.nonceEntered.await(5, TimeUnit.SECONDS));
			controls.rotateTo(protectionKey("second", 32));
			Assertions.assertThrows(McpKeyInUseException.class,
					() -> controls.removeVerificationKey("first"));
			entropy.releaseNonce.countDown();
			String oldState = oldSeal.get(5, TimeUnit.SECONDS);
			Assertions.assertArrayEquals(new byte[]{1},
					controls.openRequestState(context, oldState));
			Assertions.assertTrue(controls.removeVerificationKey("first"));
			assertInvalidState(() -> controls.openRequestState(context,
					oldState));
		} finally {
			entropy.releaseNonce.countDown();
			executor.shutdownNow();
		}
	}

	@Test
	public void customProtectorReceivesExactContextAndEnforcesLimits()
			throws Exception {
		McpRequestStateProtectionContext context = protectionContext(32);
		byte[] original = new byte[]{1, 2, 3};
		AtomicInteger seals = new AtomicInteger();
		McpRequestStateProtector protector = new McpRequestStateProtector() {
			@Override
			public String seal(McpRequestStateProtectionContext suppliedContext,
					byte[] plaintext) {
				Assertions.assertSame(context, suppliedContext);
				Assertions.assertArrayEquals(original, plaintext);
				plaintext[0] = 99;
				seals.incrementAndGet();
				return "custom-state";
			}

			@Override
			public byte[] open(McpRequestStateProtectionContext suppliedContext,
					String protectedState) {
				Assertions.assertSame(context, suppliedContext);
				Assertions.assertEquals("custom-state", protectedState);
				return new byte[]{4, 5};
			}
		};
		DefaultMcpSecurityControls controls = new DefaultMcpSecurityControls(
				McpProtectionConfig.withRequestStateProtector(protector)
						.maximumEncodedRequestStateBytes(16)
						.maximumDecodedRequestStateBytes(8).build(), null);

		Assertions.assertEquals("custom-state",
				controls.sealRequestState(context, original));
		Assertions.assertArrayEquals(new byte[]{1, 2, 3}, original);
		Assertions.assertEquals(1, seals.get());
		controls.validateRequestStateStructure("custom-state");
		Assertions.assertArrayEquals(new byte[]{4, 5},
				controls.openRequestState(context, "custom-state"));
		assertInvalidState(() ->
				controls.validateRequestStateStructure("x".repeat(17)));
		assertInvalidState(() -> controls.validateRequestStateStructure(
				"bad\uD800"));
		Assertions.assertThrows(IllegalStateException.class, () ->
				controls.sealRequestState(context, new byte[9]));
	}

	@Test
	public void customProtectorInvalidOpenedPlaintextSizesAreInvalidState()
			throws Exception {
		AtomicInteger opens = new AtomicInteger();
		McpRequestStateProtector protector = new McpRequestStateProtector() {
			@Override
			public String seal(McpRequestStateProtectionContext context,
					byte[] plaintext) {
				return "custom-state";
			}

			@Override
			public byte[] open(McpRequestStateProtectionContext context,
					String protectedState) {
				return new byte[opens.getAndIncrement() == 0 ? 0 : 9];
			}
		};
		DefaultMcpSecurityControls controls = new DefaultMcpSecurityControls(
				McpProtectionConfig.withRequestStateProtector(protector)
						.maximumDecodedRequestStateBytes(8).build(), null);
		McpRequestStateProtectionContext context = protectionContext(32);

		assertInvalidState(() -> controls.openRequestState(context,
				"custom-state"));
		assertInvalidState(() -> controls.openRequestState(context,
				"custom-state"));
		Assertions.assertEquals(2, opens.get());
	}

	@Test
	public void developmentEphemeralStateIsProcessLocal() throws Exception {
		McpProtectionConfig config = McpProtectionConfig
				.withDevelopmentEphemeralProtection().build();
		DefaultMcpSecurityControls first = deterministicControls(config,
				bytesFromLength(0, 32), bytesFromLength(64, 24),
				bytesFromLength(96, 12));
		DefaultMcpSecurityControls second = deterministicControls(config,
				bytesFromLength(1, 32));
		McpRequestStateProtectionContext context = protectionContext(32);
		String state = first.sealRequestState(context, new byte[]{1});

		Assertions.assertEquals(McpProtectionMode.DEVELOPMENT_EPHEMERAL,
				first.getProtectionMode());
		Assertions.assertTrue(first.getKeyRingSnapshot().isEmpty());
		Assertions.assertArrayEquals(new byte[]{1},
				first.openRequestState(context, state));
		assertInvalidState(() -> second.openRequestState(context, state));
	}

	@Test
	public void traceFingerprintMatchesFrozenGoldenConstructionAndValidation() {
		DefaultMcpSecurityControls controls =
				new DefaultMcpSecurityControls(null, traceKey("trace", 96));
		McpTraceCorrelationConfigurationFingerprint fingerprint = controls
				.getConfigurationFingerprint().orElseThrow();
		Assertions.assertTrue(controls.isEnabled());
		Assertions.assertEquals(Optional.of("trace"),
				controls.getActiveKeyId());
		Assertions.assertEquals("v1",
				McpTraceCorrelationConfigurationFingerprint.VERSION);
		Assertions.assertEquals(TRACE_GOLDEN_FINGERPRINT, fingerprint.value());
		Assertions.assertEquals(fingerprint,
				new McpTraceCorrelationConfigurationFingerprint(
						TRACE_GOLDEN_FINGERPRINT));
		Assertions.assertThrows(NullPointerException.class, () ->
				new McpTraceCorrelationConfigurationFingerprint(null));
		for (String invalid : List.of("", "A".repeat(42), "A".repeat(44),
				"!" + "A".repeat(42), "A".repeat(42) + "B"))
			Assertions.assertThrows(IllegalArgumentException.class, () ->
					new McpTraceCorrelationConfigurationFingerprint(invalid));
	}

	@Test
	public void traceRotationIsAtomicRetrySafeAndCrossPurposeDistinct() {
		DefaultMcpSecurityControls disabled =
				new DefaultMcpSecurityControls(null, null);
		Assertions.assertFalse(disabled.isEnabled());
		Assertions.assertEquals(Optional.empty(), disabled.getActiveKeyId());
		Assertions.assertEquals(Optional.empty(),
				disabled.getConfigurationFingerprint());
		Assertions.assertThrows(IllegalStateException.class, () ->
				disabled.rotateActiveKey(traceKey("trace", 0)));

		DefaultMcpSecurityControls controls = controls(McpProtectionKeyRing
				.withActiveKey(protectionKey("protection", 0)).build(),
				traceKey("trace", 32));
		McpTraceCorrelationConfigurationFingerprint initialFingerprint = controls
				.getConfigurationFingerprint().orElseThrow();
		controls.rotateActiveKey(traceKey("trace", 32));
		Assertions.assertEquals(initialFingerprint,
				controls.getConfigurationFingerprint().orElseThrow());
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				controls.rotateActiveKey(traceKey("trace", 64)));
		Assertions.assertEquals(Optional.of("trace"),
				controls.getActiveKeyId());
		Assertions.assertEquals(initialFingerprint,
				controls.getConfigurationFingerprint().orElseThrow());
		controls.rotateActiveKey(traceKey("rotated", 64));
		Assertions.assertEquals(Optional.of("rotated"),
				controls.getActiveKeyId());
		Assertions.assertNotEquals(initialFingerprint,
				controls.getConfigurationFingerprint().orElseThrow());
		McpTraceCorrelationConfigurationFingerprint beforeRejected = controls
				.getConfigurationFingerprint().orElseThrow();
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				controls.rotateActiveKey(traceKey("protection-alias", 0)));
		Assertions.assertEquals(Optional.of("rotated"),
				controls.getActiveKeyId());
		Assertions.assertEquals(beforeRejected,
				controls.getConfigurationFingerprint().orElseThrow());
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				controls.stageVerificationKey(
						protectionKey("trace-alias", 64)));

		for (McpProtectionKeyRing aliasedRing : List.of(
				McpProtectionKeyRing.withActiveKey(
						protectionKey("active-alias", 96)).build(),
				McpProtectionKeyRing.withActiveKey(
						protectionKey("active", 0))
						.verificationKey(
								protectionKey("verification-alias", 96))
						.build())) {
			IllegalArgumentException exception = Assertions.assertThrows(
					IllegalArgumentException.class,
					() -> controls(aliasedRing, traceKey("trace", 96)));
			Assertions.assertEquals(
					"Protection and trace-correlation keys must use distinct material.",
					exception.getMessage());
		}
	}

	@Test
	public void concurrentProtectionRotationPublishesOnlyCompleteSnapshots()
			throws Exception {
		McpProtectionKey first = protectionKey("first", 0);
		McpProtectionKey second = protectionKey("second", 32);
		DefaultMcpSecurityControls controls = controls(McpProtectionKeyRing
				.withActiveKey(first).verificationKey(second).build(), null);
		String firstFingerprint = controls(McpProtectionKeyRing
				.withActiveKey(first).verificationKey(second).build(), null)
				.getKeyRingSnapshot().orElseThrow().getFingerprint().getValue();
		String secondFingerprint = controls(McpProtectionKeyRing
				.withActiveKey(second).verificationKey(first).build(), null)
				.getKeyRingSnapshot().orElseThrow().getFingerprint().getValue();
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(8);
		try {
			List<Future<?>> futures = new ArrayList<>();
			for (int writer = 0; writer < 4; ++writer) {
				int writerIndex = writer;
				futures.add(executor.submit(() -> {
					start.await();
					for (int iteration = 0; iteration < 1_000; ++iteration)
						controls.rotateTo((iteration + writerIndex) % 2 == 0
								? first : second);
					return null;
				}));
			}
			for (int reader = 0; reader < 4; ++reader)
				futures.add(executor.submit(() -> {
					start.await();
					for (int iteration = 0; iteration < 2_000; ++iteration) {
						McpProtectionKeyRingSnapshot snapshot = controls
								.getKeyRingSnapshot().orElseThrow();
						if (snapshot.getActiveKeyId().equals("first")) {
							Assertions.assertEquals(Set.of("second"),
									snapshot.getVerificationKeyIds());
							Assertions.assertEquals(firstFingerprint,
									snapshot.getFingerprint().getValue());
						} else {
							Assertions.assertEquals("second",
									snapshot.getActiveKeyId());
							Assertions.assertEquals(Set.of("first"),
									snapshot.getVerificationKeyIds());
							Assertions.assertEquals(secondFingerprint,
									snapshot.getFingerprint().getValue());
						}
					}
					return null;
				}));
			start.countDown();
			for (Future<?> future : futures)
				future.get();
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	public void concurrentCrossPurposeAliasAttemptsHaveOneWinner()
			throws Exception {
		DefaultMcpSecurityControls controls = controls(McpProtectionKeyRing
				.withActiveKey(protectionKey("protection", 0)).build(),
				traceKey("trace", 32));
		McpProtectionKey protectionCandidate =
				protectionKey("shared-protection", 64);
		McpTraceCorrelationKey traceCandidate =
				traceKey("shared-trace", 64);
		CountDownLatch start = new CountDownLatch(1);
		AtomicInteger successes = new AtomicInteger();
		AtomicInteger rejections = new AtomicInteger();
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<?> protectionFuture = executor.submit(() -> {
				start.await();
				try {
					controls.rotateTo(protectionCandidate);
					successes.incrementAndGet();
				} catch (IllegalArgumentException exception) {
					rejections.incrementAndGet();
				}
				return null;
			});
			Future<?> traceFuture = executor.submit(() -> {
				start.await();
				try {
					controls.rotateActiveKey(traceCandidate);
					successes.incrementAndGet();
				} catch (IllegalArgumentException exception) {
					rejections.incrementAndGet();
				}
				return null;
			});
			start.countDown();
			protectionFuture.get();
			traceFuture.get();
		} finally {
			executor.shutdownNow();
		}

		Assertions.assertEquals(1, successes.get());
		Assertions.assertEquals(1, rejections.get());
		String activeProtectionId = controls.getKeyRingSnapshot().orElseThrow()
				.getActiveKeyId();
		String activeTraceId = controls.getActiveKeyId().orElseThrow();
		Assertions.assertTrue(
				(activeProtectionId.equals("shared-protection")
						&& activeTraceId.equals("trace"))
				|| (activeProtectionId.equals("protection")
						&& activeTraceId.equals("shared-trace")));
	}

	private static void assertRejectedWithoutProtectionChange(
			DefaultMcpSecurityControls controls, Runnable mutation) {
		McpProtectionKeyRingSnapshot before = controls.getKeyRingSnapshot()
				.orElseThrow();
		Assertions.assertThrows(IllegalArgumentException.class, mutation::run);
		McpProtectionKeyRingSnapshot after = controls.getKeyRingSnapshot()
				.orElseThrow();
		Assertions.assertEquals(before.getActiveKeyId(), after.getActiveKeyId());
		Assertions.assertEquals(before.getVerificationKeyIds(),
				after.getVerificationKeyIds());
		Assertions.assertEquals(before.getFingerprint(), after.getFingerprint());
	}

	private static McpProtectionConfig protectionConfig(String keyId,
			int firstByte) {
		return McpProtectionConfig.withKeyRing(McpProtectionKeyRing
				.withActiveKey(protectionKey(keyId, firstByte)).build()).build();
	}

	private static McpRequestStateProtectionContext protectionContext(
			int firstBindingByte) {
		return new McpRequestStateProtectionContext("/mcp", "2026-07-28",
				"tools/call", bytesFrom(firstBindingByte));
	}

	private static DefaultMcpSecurityControls deterministicControls(
			McpProtectionConfig config, byte[]... entropyValues) {
		return new DefaultMcpSecurityControls(config, null,
				new SequenceEntropy(entropyValues), 1L << 32, 0L);
	}

	private static void assertInvalidState(CheckedOperation operation) {
		McpRequestStateProtectionException exception = Assertions.assertThrows(
				McpRequestStateProtectionException.class, operation::run);
		Assertions.assertEquals(
				McpRequestStateProtectionException.Reason.INVALID_STATE,
				exception.getReason());
	}

	private static void assertUnavailable(CheckedOperation operation) {
		McpRequestStateProtectionException exception = Assertions.assertThrows(
				McpRequestStateProtectionException.class, operation::run);
		Assertions.assertEquals(
				McpRequestStateProtectionException.Reason.PROTECTOR_UNAVAILABLE,
				exception.getReason());
	}

	private static String tamperLastBase64UrlCharacter(String value) {
		char replacement = value.charAt(value.length() - 1) == 'A' ? 'E' : 'A';
		return value.substring(0, value.length() - 1) + replacement;
	}

	private static long epochNumber(String protectedState) {
		String suffix = protectedState.substring(
				DefaultMcpSecurityControls.REQUEST_STATE_PREFIX.length());
		byte[] envelope = Base64.getUrlDecoder().decode(suffix);
		int keyIdLength = Byte.toUnsignedInt(envelope[1]);
		int offset = 2 + keyIdLength + 1 + 24 + 24;
		long value = 0L;
		for (int index = 0; index < 8; ++index)
			value = (value << 8) | Byte.toUnsignedLong(envelope[offset + index]);
		return value;
	}

	private static byte[] bytesFromLength(int firstByte, int length) {
		byte[] bytes = new byte[length];
		for (int index = 0; index < bytes.length; ++index)
			bytes[index] = (byte) (firstByte + index);
		return bytes;
	}

	private static void copyExact(byte[] source, byte[] destination) {
		Assertions.assertEquals(source.length, destination.length);
		System.arraycopy(source, 0, destination, 0, destination.length);
	}

	private static DefaultMcpSecurityControls controls(
			McpProtectionKeyRing ring,
			McpTraceCorrelationKey traceCorrelationKey) {
		McpProtectionConfig config = ring == null ? null
				: McpProtectionConfig.withKeyRing(ring).build();
		return new DefaultMcpSecurityControls(config, traceCorrelationKey);
	}

	private static McpProtectionKey protectionKey(String id, int firstByte) {
		return McpProtectionKey.fromIdAndBytes(id, bytesFrom(firstByte));
	}

	private static McpTraceCorrelationKey traceKey(String id, int firstByte) {
		return McpTraceCorrelationKey.fromIdAndBytes(id, bytesFrom(firstByte));
	}

	private static byte[] bytesFrom(int firstByte) {
		byte[] bytes = new byte[32];
		for (int index = 0; index < bytes.length; ++index)
			bytes[index] = (byte) (firstByte + index);
		return bytes;
	}

	@FunctionalInterface
	private interface CheckedOperation {
		void run() throws Exception;
	}

	private static final class SequenceEntropy
			implements DefaultMcpSecurityControls.EntropySource {
		private final List<byte[]> values;
		private final AtomicInteger index = new AtomicInteger();

		private SequenceEntropy(byte[]... values) {
			this.values = Arrays.stream(values).map(byte[]::clone).toList();
		}

		@Override
		public void nextBytes(byte[] destination) {
			int currentIndex = this.index.getAndIncrement();
			Assertions.assertTrue(currentIndex < this.values.size(),
					"Unexpected entropy request.");
			copyExact(this.values.get(currentIndex), destination);
		}
	}

	private static final class BlockingNonceEntropy
			implements DefaultMcpSecurityControls.EntropySource {
		private final AtomicInteger calls = new AtomicInteger();
		private final CountDownLatch nonceEntered = new CountDownLatch(1);
		private final CountDownLatch releaseNonce = new CountDownLatch(1);

		@Override
		public void nextBytes(byte[] destination) {
			int call = this.calls.getAndIncrement();
			if (call == 1) {
				this.nonceEntered.countDown();
				try {
					if (!this.releaseNonce.await(5, TimeUnit.SECONDS))
						throw new AssertionError(
								"Timed out waiting to release the nonce.");
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
					throw new AssertionError(exception);
				}
			}
			copyExact(bytesFromLength(64 + call * 32, destination.length),
					destination);
		}
	}
}
