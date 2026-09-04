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

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
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
	private static final String TRACE_TOKEN_PRIMARY_FULL_HMAC =
			"6c6143f0a20aaec093178572ace41886"
			+ "7b3142b9902ab8776b1bb39bee1f9e9f";
	private static final String TRACE_TOKEN_PRIMARY =
			"bGFD8KIKrsCTF4VyrOQYhg";
	private static final String TRACE_TOKEN_ASCII_TRACE_ID =
			"BWkgOoKAFLrEv3k4LqmfQQ";
	private static final String TRACE_TOKEN_WITHOUT_DOMAIN_NUL =
			"hd6yTQT96IhoP-XReBO2rg";
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
		McpProtectionKeyring.Builder builder =
				McpProtectionKeyring.withActiveKey(active)
						.addVerificationKey(verification);
		McpProtectionKeyring ring = builder.build();
		builder.addVerificationKey(protectionKey("later", 64));
		Assertions.assertEquals("active", ring.getActiveKeyId());
		Assertions.assertEquals(Set.of("verification"),
				ring.getVerificationKeyIds());
		Assertions.assertThrows(UnsupportedOperationException.class, () ->
				ring.getVerificationKeyIds().add("forbidden"));

		DefaultMcpSecurityControls controls = controls(ring, null);
		McpProtectionKeyringSnapshot snapshot = controls.getKeyringSnapshot()
				.orElseThrow();
		Assertions.assertEquals("active", snapshot.getActiveKeyId());
		Assertions.assertEquals(Set.of("verification"),
				snapshot.getVerificationKeyIds());
		Assertions.assertThrows(UnsupportedOperationException.class, () ->
				snapshot.getVerificationKeyIds().add("forbidden"));

		Assertions.assertThrows(IllegalArgumentException.class, () ->
				McpProtectionKeyring.withActiveKey(active)
						.addVerificationKey(protectionKey("active", 64)));
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				McpProtectionKeyring.withActiveKey(active)
						.addVerificationKey(protectionKey("alias", 0)));
	}

	@Test
	public void bulkInitialRingMutationIsAtomicWhenALateKeyIsInvalid() {
		McpProtectionKeyring.Builder duplicateBuilder = McpProtectionKeyring
				.withActiveKey(protectionKey("active", 0));
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				duplicateBuilder.addVerificationKeys(List.of(
						protectionKey("first", 32),
						protectionKey("duplicate-material", 32))));
		duplicateBuilder.addVerificationKey(protectionKey("first", 32));

		McpProtectionKeyring.Builder nullBuilder = McpProtectionKeyring
				.withActiveKey(protectionKey("active", 0));
		List<McpProtectionKey> keysWithNull = new ArrayList<>();
		keysWithNull.add(protectionKey("first", 32));
		keysWithNull.add(null);
		Assertions.assertThrows(NullPointerException.class,
				() -> nullBuilder.addVerificationKeys(keysWithNull));
		nullBuilder.addVerificationKey(protectionKey("first", 32));

		for (McpProtectionKeyring ring : List.of(duplicateBuilder.build(),
				nullBuilder.build()))
			Assertions.assertEquals(Set.of("first"), controls(ring, null)
					.getKeyringSnapshot().orElseThrow()
					.getVerificationKeyIds());
	}

	@Test
	public void protectionFingerprintMatchesFrozenGoldenConstruction() {
		McpProtectionKeyring ring = McpProtectionKeyring
				.withActiveKey(protectionKey("active", 0))
				.addVerificationKeys(List.of(
						protectionKey("verify-b", 64),
						protectionKey("verify-a", 32)))
				.build();
		McpProtectionKeyringFingerprint fingerprint = controls(ring, null)
				.getKeyringSnapshot().orElseThrow().getFingerprint();

		Assertions.assertEquals("v1", fingerprint.getVersion());
		Assertions.assertEquals("soklet-mcp-protection-v1",
				fingerprint.getProfile());
		Assertions.assertEquals(PROTECTION_GOLDEN_FINGERPRINT,
				fingerprint.getValue());
		Assertions.assertEquals(PROTECTION_GOLDEN_FINGERPRINT,
				fingerprint.toString());
		Assertions.assertEquals(fingerprint, controls(ring, null)
				.getKeyringSnapshot().orElseThrow().getFingerprint());
		Assertions.assertEquals(fingerprint.hashCode(), controls(ring, null)
				.getKeyringSnapshot().orElseThrow().getFingerprint().hashCode());
		Assertions.assertNotEquals(fingerprint, controls(McpProtectionKeyring
				.withActiveKey(protectionKey("active", 1))
				.addVerificationKeys(List.of(
						protectionKey("verify-a", 32),
						protectionKey("verify-b", 64)))
				.build(), null).getKeyringSnapshot().orElseThrow()
				.getFingerprint());
	}

	@Test
	public void protectionMutationsAreRetrySafeAndRejectedMutationsAreAtomic() {
		McpProtectionKey active = protectionKey("active", 0);
		McpProtectionKey staged = protectionKey("staged", 32);
		DefaultMcpSecurityControls controls = controls(McpProtectionKeyring
				.withActiveKey(active).addVerificationKey(staged).build(),
				traceKey("trace", 96));

		McpProtectionKeyringFingerprint initialFingerprint = controls
				.getKeyringSnapshot().orElseThrow().getFingerprint();
		controls.stageVerificationKey(protectionKey("active", 0));
		controls.stageVerificationKey(protectionKey("staged", 32));
		Assertions.assertEquals(initialFingerprint, controls.getKeyringSnapshot()
				.orElseThrow().getFingerprint());
		assertRejectedWithoutProtectionChange(controls, () ->
				controls.stageVerificationKey(protectionKey("active", 64)));
		assertRejectedWithoutProtectionChange(controls, () ->
				controls.stageVerificationKey(protectionKey("alias", 32)));
		assertRejectedWithoutProtectionChange(controls, () ->
				controls.stageVerificationKey(protectionKey("trace-alias", 96)));

		controls.activateStagedKey("staged");
		controls.activateStagedKey("staged");
		McpProtectionKeyringSnapshot stagedSnapshot = controls
				.getKeyringSnapshot().orElseThrow();
		Assertions.assertEquals("staged", stagedSnapshot.getActiveKeyId());
		Assertions.assertEquals(Set.of("active"),
				stagedSnapshot.getVerificationKeyIds());
		assertRejectedWithoutProtectionChange(controls,
				() -> controls.activateStagedKey("unknown"));

		controls.rotateActiveKey(protectionKey("rotated", 64));
		controls.rotateActiveKey(protectionKey("rotated", 64));
		McpProtectionKeyringSnapshot rotatedSnapshot = controls
				.getKeyringSnapshot().orElseThrow();
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
		McpProtectionConfig config = McpProtectionConfig.withKeyring(
				McpProtectionKeyring.withActiveKey(
						protectionKey("active", 0)).build()).build();
		DefaultMcpSecurityControls first =
				new DefaultMcpSecurityControls(config, null);
		DefaultMcpSecurityControls second =
				new DefaultMcpSecurityControls(config, null);

		first.rotateActiveKey(protectionKey("rotated", 32));
		Assertions.assertEquals("rotated", first.getKeyringSnapshot()
				.orElseThrow().getActiveKeyId());
		Assertions.assertEquals("active", second.getKeyringSnapshot()
				.orElseThrow().getActiveKeyId());
		Assertions.assertEquals(Set.of(), second.getKeyringSnapshot()
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
				McpProtectionMode.NONE,
				McpProtectionMode.DEVELOPMENT_EPHEMERAL,
				McpProtectionMode.CUSTOM_PROTECTOR), controls.stream()
				.map(DefaultMcpSecurityControls::getProtectionMode).toList());

		for (DefaultMcpSecurityControls control : controls) {
			Assertions.assertEquals(Optional.empty(), control.getKeyringSnapshot());
			Assertions.assertThrows(IllegalStateException.class, () ->
					control.stageVerificationKey(protectionKey("key", 0)));
			Assertions.assertThrows(IllegalStateException.class, () ->
					control.activateStagedKey("key"));
			Assertions.assertThrows(IllegalStateException.class, () ->
					control.rotateActiveKey(protectionKey("key", 0)));
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
				.maximumEncodedRequestStateSizeInBytes(100)
				.maximumDecodedRequestStateSizeInBytes(75)
				.maximumRequestStateLifetime(Duration.ofSeconds(30))
				.maximumRequestStateRounds(4)
				.build();
		Assertions.assertEquals(McpProtectionMode.CUSTOM_PROTECTOR,
				config.getProtectionMode());
		Assertions.assertSame(protector,
				config.getRequestStateProtector().orElseThrow());
		Assertions.assertEquals(Optional.empty(), config.getInitialKeyring());
		Assertions.assertEquals(Integer.valueOf(100),
				config.getMaximumEncodedRequestStateSizeInBytes());
		Assertions.assertEquals(Integer.valueOf(75),
				config.getMaximumDecodedRequestStateSizeInBytes());
		Assertions.assertEquals(Duration.ofSeconds(30),
				config.getMaximumRequestStateLifetime());
		Assertions.assertEquals(Integer.valueOf(4),
				config.getMaximumRequestStateRounds());

		Assertions.assertThrows(IllegalArgumentException.class, () ->
				McpProtectionConfig.withDevelopmentEphemeralProtection()
						.maximumEncodedRequestStateSizeInBytes(0));
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				McpProtectionConfig.withDevelopmentEphemeralProtection()
						.maximumDecodedRequestStateSizeInBytes(-1));
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
						.maximumEncodedRequestStateSizeInBytes(50)
						.maximumDecodedRequestStateSizeInBytes(51).build());
		McpProtectionConfig resetDefaults = McpProtectionConfig
				.withDevelopmentEphemeralProtection()
				.maximumEncodedRequestStateSizeInBytes(100)
				.maximumDecodedRequestStateSizeInBytes(75)
				.maximumRequestStateLifetime(Duration.ofSeconds(30))
				.maximumRequestStateRounds(4)
				.maximumEncodedRequestStateSizeInBytes(null)
				.maximumDecodedRequestStateSizeInBytes(null)
				.maximumRequestStateLifetime(null)
				.maximumRequestStateRounds(null)
				.build();
		Assertions.assertEquals(Integer.valueOf(65_536),
				resetDefaults.getMaximumEncodedRequestStateSizeInBytes());
		Assertions.assertEquals(Integer.valueOf(49_152),
				resetDefaults.getMaximumDecodedRequestStateSizeInBytes());
		Assertions.assertEquals(Duration.ofMinutes(15),
				resetDefaults.getMaximumRequestStateLifetime());
		Assertions.assertEquals(Integer.valueOf(10),
				resetDefaults.getMaximumRequestStateRounds());
	}

	@Test
	public void unfrozen_security_scalars_use_reference_types() throws Exception {
		Assertions.assertEquals(Integer.class, McpProtectionConfig.class
				.getMethod("getMaximumEncodedRequestStateSizeInBytes").getReturnType());
		Assertions.assertEquals(Integer.class, McpProtectionConfig.class
				.getMethod("getMaximumDecodedRequestStateSizeInBytes").getReturnType());
		Assertions.assertEquals(Integer.class, McpProtectionConfig.class
				.getMethod("getMaximumRequestStateRounds").getReturnType());
		Assertions.assertEquals(McpProtectionConfig.Builder.class,
				McpProtectionConfig.Builder.class.getMethod(
						"maximumEncodedRequestStateSizeInBytes", Integer.class)
						.getReturnType());
		Assertions.assertEquals(McpProtectionConfig.Builder.class,
				McpProtectionConfig.Builder.class.getMethod(
						"maximumDecodedRequestStateSizeInBytes", Integer.class)
						.getReturnType());
		Assertions.assertEquals(McpProtectionConfig.Builder.class,
				McpProtectionConfig.Builder.class.getMethod(
						"maximumRequestStateRounds", Integer.class)
						.getReturnType());
		Assertions.assertEquals(Boolean.class, McpProtectionControl.class
				.getMethod("removeVerificationKey", String.class).getReturnType());
		Assertions.assertEquals(Boolean.class, McpTraceCorrelationControl.class
				.getMethod("isEnabled").getReturnType());
	}

	@Test
	public void protectionContextAndSanitizedFailuresExposeNoMutableState() {
		byte[] associatedData = bytesFrom(0);
		McpRequestStateProtectionContext context =
				McpRequestStateProtectionContext.fromComponents(
						"/mcp", "2026-07-28",
						"tools/call", associatedData);
		Arrays.fill(associatedData, (byte) 127);
		byte[] firstCopy = context.getAssociatedData();
		Assertions.assertArrayEquals(bytesFrom(0), firstCopy);
		Arrays.fill(firstCopy, (byte) 126);
		Assertions.assertArrayEquals(bytesFrom(0), context.getAssociatedData());
		Assertions.assertEquals("/mcp", context.getEndpointPath());
		Assertions.assertEquals("2026-07-28", context.getProtocolVersion());
		Assertions.assertEquals("tools/call", context.getJsonRpcMethod());
		Assertions.assertThrows(NullPointerException.class,
				() -> McpRequestStateProtectionContext.fromComponents(null,
						"2026-07-28", "tools/call", bytesFrom(0)));
		Assertions.assertThrows(NullPointerException.class,
				() -> McpRequestStateProtectionContext.fromComponents("/mcp",
						null, "tools/call", bytesFrom(0)));
		Assertions.assertThrows(NullPointerException.class,
				() -> McpRequestStateProtectionContext.fromComponents("/mcp",
						"2026-07-28", null, bytesFrom(0)));
		Assertions.assertThrows(NullPointerException.class,
				() -> McpRequestStateProtectionContext.fromComponents("/mcp",
						"2026-07-28", "tools/call", null));

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
				new McpProtectionKeyInUseException().getMessage());
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
	public void builtInProfilePinsKdfAeadHeaderCiphertextAndTag()
			throws Exception {
		DefaultMcpSecurityControls controls = deterministicControls(
				protectionConfig("active", 0), bytesFromLength(64, 24),
				bytesFromLength(96, 12));
		McpRequestStateProtectionContext context = protectionContext(32);
		byte[] plaintext = "{\"state\":\"ok\"}"
				.getBytes(StandardCharsets.UTF_8);
		byte[] envelope = envelope(controls.sealRequestState(context, plaintext));
		int headerLength = 1 + 1 + "active".length() + 1
				+ "soklet-mcp-protection-v1".length() + 32 + 12;
		byte[] header = Arrays.copyOfRange(envelope, 0, headerLength);
		byte[] sealerEpoch = Arrays.copyOfRange(header,
				headerLength - 12 - 32, headerLength - 12);
		byte[] nonce = Arrays.copyOfRange(header,
				headerLength - 12, headerLength);
		byte[] ciphertext = Arrays.copyOfRange(envelope, headerLength,
				envelope.length - 16);
		byte[] tag = Arrays.copyOfRange(envelope,
				envelope.length - 16, envelope.length);

		Assertions.assertEquals(
				"010661637469766518736f6b6c65742d6d63702d70726f74656374696f6e2d7631"
						+ "404142434445464748494a4b4c4d4e4f50515253545556570000000000000000"
						+ "606162636465666768696a6b",
				HexFormat.of().formatHex(header));
		Assertions.assertEquals("3521814e7915bc5dbeb62f865723",
				HexFormat.of().formatHex(ciphertext));
		Assertions.assertEquals("d36248da9ccacdf2a4939ee49c87f564",
				HexFormat.of().formatHex(tag));

		byte[] salt = MessageDigest.getInstance("SHA-256").digest(
				"soklet-mcp-protection-v1\0"
						.getBytes(StandardCharsets.US_ASCII));
		Assertions.assertEquals(
				"e32752b853923d1dec2b9fd7f9c6768d7669006cf82dc8a4bc5ffd1fb167e99c",
				HexFormat.of().formatHex(salt));
		byte[] prk = hmacSha256(salt, bytesFrom(0));
		Assertions.assertEquals(
				"d475b6dd059c218b22c4fc1bd3277462abb8e25482a65edaa3066d1437dff53a",
				HexFormat.of().formatHex(prk));
		byte[] derivedKey = hmacSha256(prk, concatenate(
				"soklet-mcp-request-state-aead-v1\0"
						.getBytes(StandardCharsets.US_ASCII),
				sealerEpoch, new byte[]{1}));
		Assertions.assertEquals(
				"6afbea4eb96db3a304ae39bde5e8e30faa29fb5babc2220f7cfec6a9aeb5afda",
				HexFormat.of().formatHex(derivedKey));

		byte[] associatedData = associatedData(header,
				context.getAssociatedData());
		Assertions.assertEquals(
				"736f6b6c65742d6d63702d726571756573742d73746174652d67636d2d6161642d7631"
						+ "000000004d"
						+ "010661637469766518736f6b6c65742d6d63702d70726f74656374696f6e2d7631"
						+ "404142434445464748494a4b4c4d4e4f50515253545556570000000000000000"
						+ "606162636465666768696a6b"
						+ "00000020202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f",
				HexFormat.of().formatHex(associatedData));
		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(Cipher.DECRYPT_MODE,
				new SecretKeySpec(derivedKey, "AES"),
				new GCMParameterSpec(128, nonce));
		cipher.updateAAD(associatedData);
		Assertions.assertArrayEquals(plaintext,
				cipher.doFinal(envelope, headerLength,
						envelope.length - headerLength));
	}

	@Test
	public void builtInStructureRejectsInvalidAndUnknownKeyIdsAndOtherProfiles()
			throws Exception {
		DefaultMcpSecurityControls controls = deterministicControls(
				protectionConfig("active", 0), bytesFromLength(64, 24),
				bytesFromLength(96, 12));
		McpRequestStateProtectionContext context = protectionContext(32);
		byte[] valid = envelope(controls.sealRequestState(context,
				new byte[]{1}));

		byte[] emptyKeyId = valid.clone();
		emptyKeyId[1] = 0;
		assertInvalidState(() -> controls.validateRequestStateStructure(
				protectedState(emptyKeyId)));
		byte[] oversizedKeyId = valid.clone();
		oversizedKeyId[1] = 65;
		assertInvalidState(() -> controls.validateRequestStateStructure(
				protectedState(oversizedKeyId)));
		byte[] invalidKeyId = valid.clone();
		invalidKeyId[2] = ' ';
		assertInvalidState(() -> controls.validateRequestStateStructure(
				protectedState(invalidKeyId)));
		byte[] nonAsciiKeyId = valid.clone();
		nonAsciiKeyId[2] = (byte) 0x80;
		assertInvalidState(() -> controls.validateRequestStateStructure(
				protectedState(nonAsciiKeyId)));

		byte[] unknownKeyId = valid.clone();
		System.arraycopy("absent".getBytes(StandardCharsets.US_ASCII), 0,
				unknownKeyId, 2, "active".length());
		String unknownKeyState = protectedState(unknownKeyId);
		controls.validateRequestStateStructure(unknownKeyState);
		assertInvalidState(() -> controls.openRequestState(
				context, unknownKeyState));

		byte[] alternateProfile = valid.clone();
		int profileOffset = 2 + "active".length() + 1;
		System.arraycopy("soklet-mcp-protection-v2"
				.getBytes(StandardCharsets.US_ASCII), 0,
				alternateProfile, profileOffset,
				"soklet-mcp-protection-v2".length());
		assertInvalidState(() -> controls.validateRequestStateStructure(
				protectedState(alternateProfile)));
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
		McpProtectionKeyring ring = McpProtectionKeyring.withActiveKey(
				protectionKey("active", 0)).build();
		DefaultMcpSecurityControls decodedLimit = deterministicControls(
				McpProtectionConfig.withKeyring(ring)
						.maximumEncodedRequestStateSizeInBytes(200)
						.maximumDecodedRequestStateSizeInBytes(90).build(),
				bytesFromLength(64, 24));
		DefaultMcpSecurityControls encodedLimit = deterministicControls(
				McpProtectionConfig.withKeyring(ring)
						.maximumEncodedRequestStateSizeInBytes(120)
						.maximumDecodedRequestStateSizeInBytes(100).build(),
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
			byte[] formerMasterKey = activeProtectionKeyMaterialReference(
					controls);
			byte[][] retiredSealerSecrets = activeSealerSecretReferences(
					controls);
			assertLive(formerMasterKey);
			assertLive(retiredSealerSecrets);

			controls.rotateActiveKey(protectionKey("second", 32));
			assertWiped(retiredSealerSecrets);
			assertLive(formerMasterKey);
			String newState = controls.sealRequestState(context, new byte[]{2});
			Assertions.assertEquals("second", keyId(newState));
			Assertions.assertArrayEquals(new byte[]{2},
					controls.openRequestState(context, newState));
			Assertions.assertThrows(McpProtectionKeyInUseException.class,
					() -> controls.removeVerificationKey("first"));
			assertLive(formerMasterKey);
			entropy.releaseNonce.countDown();
			String oldState = oldSeal.get(5, TimeUnit.SECONDS);
			Assertions.assertArrayEquals(new byte[]{1},
					controls.openRequestState(context, oldState));
			Assertions.assertTrue(controls.removeVerificationKey("first"));
			assertWiped(formerMasterKey);
			assertInvalidState(() -> controls.openRequestState(context,
					oldState));
		} finally {
			entropy.releaseNonce.countDown();
			executor.shutdownNow();
		}
	}

	@Test
	public void stagedActivationWipesRetiredSealerContextButKeepsOldKey()
			throws Exception {
		McpProtectionKey first = protectionKey("first", 0);
		McpProtectionKey second = protectionKey("second", 32);
		DefaultMcpSecurityControls controls = new DefaultMcpSecurityControls(
				McpProtectionConfig.withKeyring(McpProtectionKeyring
						.withActiveKey(first).addVerificationKey(second).build())
						.build(), null, new SequenceEntropy(
						bytesFromLength(64, 24), bytesFromLength(96, 12),
						bytesFromLength(108, 24)), 10L, 0L);
		McpRequestStateProtectionContext context = protectionContext(64);
		String firstState = controls.sealRequestState(context, new byte[]{1});
		byte[] firstMasterKey = activeProtectionKeyMaterialReference(controls);
		byte[] secondMasterKey = verificationProtectionKeyMaterialReference(
				controls, "second");
		byte[][] retiredSealerSecrets = activeSealerSecretReferences(controls);

		controls.activateStagedKey("second");

		assertWiped(retiredSealerSecrets);
		assertLive(firstMasterKey, secondMasterKey);
		assertLive(activeSealerSecretReferences(controls));
		McpProtectionKeyringSnapshot snapshot = controls.getKeyringSnapshot()
				.orElseThrow();
		Assertions.assertEquals("second", snapshot.getActiveKeyId());
		Assertions.assertEquals(Set.of("first"),
				snapshot.getVerificationKeyIds());
		Assertions.assertArrayEquals(new byte[]{1},
				controls.openRequestState(context, firstState));
		Assertions.assertTrue(controls.removeVerificationKey("first"));
		assertWiped(firstMasterKey);
		assertLive(secondMasterKey);
		Assertions.assertArrayEquals(bytesFrom(0), first.copyKeyMaterial());
		Assertions.assertArrayEquals(bytesFrom(32), second.copyKeyMaterial());
	}

	@Test
	public void epochRolloverWipesOnlyTheRetiredDerivedKey()
			throws Exception {
		DefaultMcpSecurityControls controls = new DefaultMcpSecurityControls(
				protectionConfig("active", 0), null, new SequenceEntropy(
						bytesFromLength(64, 24), bytesFromLength(96, 12),
						bytesFromLength(108, 12)), 1L, 0L);
		McpRequestStateProtectionContext context = protectionContext(64);
		String firstState = controls.sealRequestState(context, new byte[]{1});
		byte[][] retiredSecrets = activeSealerSecretReferences(controls);

		String secondState = controls.sealRequestState(context, new byte[]{2});
		byte[][] currentSecrets = activeSealerSecretReferences(controls);

		Assertions.assertSame(retiredSecrets[0], currentSecrets[0]);
		Assertions.assertSame(retiredSecrets[1], currentSecrets[1]);
		Assertions.assertNotSame(retiredSecrets[2], currentSecrets[2]);
		assertLive(retiredSecrets[0], retiredSecrets[1], currentSecrets[2]);
		assertWiped(retiredSecrets[2]);
		Assertions.assertArrayEquals(new byte[]{1},
				controls.openRequestState(context, firstState));
		Assertions.assertArrayEquals(new byte[]{2},
				controls.openRequestState(context, secondState));
	}

	@Test
	public void failedActivationPreservesTheExistingContextAndKeyring()
			throws Exception {
		AtomicInteger entropyCalls = new AtomicInteger();
		DefaultMcpSecurityControls controls = new DefaultMcpSecurityControls(
				McpProtectionConfig.withKeyring(McpProtectionKeyring
						.withActiveKey(protectionKey("first", 0))
						.addVerificationKey(protectionKey("second", 32)).build())
						.build(), null, destination -> {
					int call = entropyCalls.getAndIncrement();
					if (call == 2)
						throw new IllegalStateException(
								"simulated activation entropy failure");
					Arrays.fill(destination, (byte) (64 + call));
				}, 10L, 0L);
		McpRequestStateProtectionContext context = protectionContext(64);
		String beforeFailure = controls.sealRequestState(context, new byte[]{1});
		Object sealerContext = activeSealerContextReference(controls);
		byte[][] sealerSecrets = activeSealerSecretReferences(controls);

		Assertions.assertThrows(IllegalStateException.class,
				() -> controls.activateStagedKey("second"));

		Assertions.assertSame(sealerContext,
				activeSealerContextReference(controls));
		assertLive(sealerSecrets);
		McpProtectionKeyringSnapshot snapshot = controls.getKeyringSnapshot()
				.orElseThrow();
		Assertions.assertEquals("first", snapshot.getActiveKeyId());
		Assertions.assertEquals(Set.of("second"),
				snapshot.getVerificationKeyIds());
		String afterFailure = controls.sealRequestState(context, new byte[]{2});
		Assertions.assertArrayEquals(new byte[]{1},
				controls.openRequestState(context, beforeFailure));
		Assertions.assertArrayEquals(new byte[]{2},
				controls.openRequestState(context, afterFailure));
	}

	@Test
	public void retiredServerOwnedKeysAreWipedWithoutMutatingCallers()
			throws Exception {
		McpProtectionKey active = protectionKey("active", 0);
		McpProtectionKey verification = protectionKey("verification", 32);
		McpTraceCorrelationKey trace = traceKey("trace", 64);
		DefaultMcpSecurityControls controls = controls(McpProtectionKeyring
				.withActiveKey(active).addVerificationKey(verification).build(), trace);
		byte[] activeServerCopy = activeProtectionKeyMaterialReference(controls);
		byte[] verificationServerCopy =
				verificationProtectionKeyMaterialReference(controls, "verification");
		byte[] traceServerCopy = activeTraceKeyMaterialReference(controls);

		controls.stageVerificationKey(verification);
		controls.rotateActiveKey(trace);
		assertLive(activeServerCopy, verificationServerCopy, traceServerCopy);

		Assertions.assertTrue(controls.removeVerificationKey("verification"));
		assertWiped(verificationServerCopy);
		controls.rotateActiveKey(traceKey("rotated-trace", 96));
		assertWiped(traceServerCopy);
		controls.rotateActiveKey(protectionKey("rotated-protection", 128));
		assertLive(activeServerCopy);
		Assertions.assertTrue(controls.removeVerificationKey("active"));
		assertWiped(activeServerCopy);

		Assertions.assertArrayEquals(bytesFrom(0), active.copyKeyMaterial());
		Assertions.assertArrayEquals(bytesFrom(32),
				verification.copyKeyMaterial());
		Assertions.assertArrayEquals(bytesFrom(64), trace.copyKeyMaterial());
		assertLive(activeProtectionKeyMaterialReference(controls),
				activeTraceKeyMaterialReference(controls));
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
						.maximumEncodedRequestStateSizeInBytes(16)
						.maximumDecodedRequestStateSizeInBytes(8).build(), null);

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
						.maximumDecodedRequestStateSizeInBytes(8).build(), null);
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
		Assertions.assertTrue(first.getKeyringSnapshot().isEmpty());
		Assertions.assertArrayEquals(new byte[]{1},
				first.openRequestState(context, state));
		assertInvalidState(() -> second.openRequestState(context, state));
	}

	@Test
	public void traceFingerprintMatchesFrozenGoldenConstructionAndValidation() {
		DefaultMcpSecurityControls controls =
				new DefaultMcpSecurityControls(null, traceKey("trace", 96));
		McpTraceCorrelationFingerprint fingerprint = controls
				.getFingerprint().orElseThrow();
		Assertions.assertTrue(controls.isEnabled());
		Assertions.assertEquals(Optional.of("trace"),
				controls.getActiveKeyId());
		Assertions.assertThrows(NoSuchFieldException.class, () ->
				McpTraceCorrelationFingerprint.class.getField("VERSION"));
		Assertions.assertEquals(TRACE_GOLDEN_FINGERPRINT, fingerprint.getValue());
		McpTraceCorrelationFingerprint equalFingerprint =
				McpTraceCorrelationFingerprint.fromValue(
						TRACE_GOLDEN_FINGERPRINT);
		Assertions.assertEquals(fingerprint, equalFingerprint);
		Assertions.assertEquals(fingerprint.hashCode(), equalFingerprint.hashCode());
		Assertions.assertEquals(TRACE_GOLDEN_FINGERPRINT, fingerprint.toString());
		Assertions.assertThrows(NullPointerException.class, () ->
				McpTraceCorrelationFingerprint.fromValue(null));
		for (String invalid : List.of("", "A".repeat(42), "A".repeat(44),
				"!" + "A".repeat(42), "A".repeat(42) + "B"))
			Assertions.assertThrows(IllegalArgumentException.class, () ->
					McpTraceCorrelationFingerprint.fromValue(invalid));
	}

	@Test
	public void disabledTraceCorrelationProducesNoToken() {
		DefaultMcpSecurityControls controls =
				new DefaultMcpSecurityControls(null, null);

		Assertions.assertEquals(Optional.empty(),
				controls.deriveTraceCorrelationToken(traceContext(
						"000102030405060708090a0b0c0d0e0f")));
	}

	@Test
	public void traceTokenMatchesFrozenDecodedIdDomainNulTruncationAndBase64UrlVectors() {
		DefaultMcpSecurityControls controls =
				new DefaultMcpSecurityControls(null, traceKey("primary", 0));
		DefaultMcpSecurityControls.TraceCorrelationToken result = controls
				.deriveTraceCorrelationToken(traceContext(
						"000102030405060708090a0b0c0d0e0f"))
				.orElseThrow();

		Assertions.assertEquals("primary", result.keyId());
		Assertions.assertEquals(TRACE_TOKEN_PRIMARY, result.token());
		Assertions.assertNotEquals(TRACE_TOKEN_ASCII_TRACE_ID, result.token(),
				"The HMAC input must contain decoded trace-ID bytes, not ASCII hex.");
		Assertions.assertNotEquals(TRACE_TOKEN_WITHOUT_DOMAIN_NUL,
				result.token(),
				"The HMAC domain must retain its terminal NUL byte.");
		Assertions.assertEquals(22, result.token().length());
		Assertions.assertTrue(result.token().matches("[A-Za-z0-9_-]{22}"));
		Assertions.assertFalse(result.token().contains("="));
		Assertions.assertArrayEquals(HexFormat.of().parseHex(
				TRACE_TOKEN_PRIMARY_FULL_HMAC.substring(0, 32)),
				Base64.getUrlDecoder().decode(result.token()),
				"The token must contain exactly the first 16 HMAC bytes.");
	}

	@Test
	public void traceTokensAgreeForSameKeyAndTraceAndSeparateDifferentInputs() {
		TraceContext firstTrace = traceContext(
				"000102030405060708090a0b0c0d0e0f");
		TraceContext secondTrace = traceContext(
				"101112131415161718191a1b1c1d1e1f");
		DefaultMcpSecurityControls first = new DefaultMcpSecurityControls(null,
				traceKey("shared", 0));
		DefaultMcpSecurityControls same = new DefaultMcpSecurityControls(null,
				traceKey("shared", 0));
		DefaultMcpSecurityControls differentKey =
				new DefaultMcpSecurityControls(null, traceKey("different", 32));

		DefaultMcpSecurityControls.TraceCorrelationToken firstResult = first
				.deriveTraceCorrelationToken(firstTrace).orElseThrow();
		Assertions.assertEquals(TRACE_TOKEN_PRIMARY, firstResult.token());
		Assertions.assertEquals(firstResult,
				same.deriveTraceCorrelationToken(firstTrace).orElseThrow());
		Assertions.assertEquals("jwPrrpAQQBmfKcF7JYD2-Q", first
				.deriveTraceCorrelationToken(secondTrace).orElseThrow().token());
		Assertions.assertEquals("2_eRfLtwlJg84LByr-y33A", differentKey
				.deriveTraceCorrelationToken(firstTrace).orElseThrow().token());
		Assertions.assertNotEquals(firstResult.token(), first
				.deriveTraceCorrelationToken(secondTrace).orElseThrow().token());
		Assertions.assertNotEquals(firstResult.token(), differentKey
				.deriveTraceCorrelationToken(firstTrace).orElseThrow().token());
	}

	@Test
	public void concurrentTraceRotationsPublishOnlyCoherentKeyTokenPairs()
			throws Exception {
		McpTraceCorrelationKey firstKey = traceKey("first", 0);
		McpTraceCorrelationKey secondKey = traceKey("second", 32);
		TraceContext traceContext = traceContext(
				"000102030405060708090a0b0c0d0e0f");
		DefaultMcpSecurityControls controls =
				new DefaultMcpSecurityControls(null, firstKey);
		Set<DefaultMcpSecurityControls.TraceCorrelationToken> expected = Set.of(
				new DefaultMcpSecurityControls(null, firstKey)
						.deriveTraceCorrelationToken(traceContext).orElseThrow(),
				new DefaultMcpSecurityControls(null, secondKey)
						.deriveTraceCorrelationToken(traceContext).orElseThrow());
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(5);
		try {
			List<Future<?>> futures = new ArrayList<>();
			futures.add(executor.submit(() -> {
				start.await();
				for (int iteration = 0; iteration < 4_000; ++iteration)
					controls.rotateActiveKey(iteration % 2 == 0
							? secondKey : firstKey);
				return null;
			}));
			for (int reader = 0; reader < 4; ++reader)
				futures.add(executor.submit(() -> {
					start.await();
					for (int iteration = 0; iteration < 8_000; ++iteration)
						Assertions.assertTrue(expected.contains(controls
								.deriveTraceCorrelationToken(traceContext)
								.orElseThrow()),
								"A token must use one complete old or new key pair.");
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
	public void traceContextRejectsInvalidAndAllZeroIdsBeforeDerivation() {
		for (String traceId : List.of(
				"00000000000000000000000000000000",
				"000102030405060708090a0b0c0d0e",
				"000102030405060708090a0b0c0d0e0g",
				"000102030405060708090A0B0C0D0E0F"))
			Assertions.assertEquals(Optional.empty(), TraceContext.fromHeaderValues(
					List.of("00-%s-1011121314151617-01".formatted(traceId)),
					List.of()));
	}

	@Test
	public void traceTokenRenderingRedactsTokenRawTraceAndKeyMaterial() {
		String keyMaterialCanary =
				"TRACE_KEY_MATERIAL_CANARY_0123456789";
		String traceIdCanary = "cafebabecafebabecafebabecafebabe";
		McpTraceCorrelationKey key = McpTraceCorrelationKey.fromIdAndBytes(
				"render-key", keyMaterialCanary.getBytes(StandardCharsets.UTF_8));
		DefaultMcpSecurityControls controls =
				new DefaultMcpSecurityControls(null, key);
		DefaultMcpSecurityControls.TraceCorrelationToken token = controls
				.deriveTraceCorrelationToken(traceContext(traceIdCanary))
				.orElseThrow();
		String rendering = "%s %s %s".formatted(key, controls, token);

		Assertions.assertFalse(rendering.contains(token.token()));
		Assertions.assertFalse(rendering.contains(traceIdCanary));
		Assertions.assertFalse(rendering.contains(keyMaterialCanary));
		Assertions.assertTrue(token.toString().contains("token=<redacted>"));
	}

	@Test
	public void traceRotationIsAtomicRetrySafeAndCrossPurposeDistinct() {
		DefaultMcpSecurityControls disabled =
				new DefaultMcpSecurityControls(null, null);
		Assertions.assertFalse(disabled.isEnabled());
		Assertions.assertEquals(Optional.empty(), disabled.getActiveKeyId());
		Assertions.assertEquals(Optional.empty(),
				disabled.getFingerprint());
		Assertions.assertThrows(IllegalStateException.class, () ->
				disabled.rotateActiveKey(traceKey("trace", 0)));

		DefaultMcpSecurityControls controls = controls(McpProtectionKeyring
				.withActiveKey(protectionKey("protection", 0)).build(),
				traceKey("trace", 32));
		McpTraceCorrelationFingerprint initialFingerprint = controls
				.getFingerprint().orElseThrow();
		controls.rotateActiveKey(traceKey("trace", 32));
		Assertions.assertEquals(initialFingerprint,
				controls.getFingerprint().orElseThrow());
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				controls.rotateActiveKey(traceKey("trace", 64)));
		Assertions.assertEquals(Optional.of("trace"),
				controls.getActiveKeyId());
		Assertions.assertEquals(initialFingerprint,
				controls.getFingerprint().orElseThrow());
		controls.rotateActiveKey(traceKey("rotated", 64));
		Assertions.assertEquals(Optional.of("rotated"),
				controls.getActiveKeyId());
		Assertions.assertNotEquals(initialFingerprint,
				controls.getFingerprint().orElseThrow());
		McpTraceCorrelationFingerprint beforeRejected = controls
				.getFingerprint().orElseThrow();
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				controls.rotateActiveKey(traceKey("protection-alias", 0)));
		Assertions.assertEquals(Optional.of("rotated"),
				controls.getActiveKeyId());
		Assertions.assertEquals(beforeRejected,
				controls.getFingerprint().orElseThrow());
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				controls.stageVerificationKey(
						protectionKey("trace-alias", 64)));

		for (McpProtectionKeyring aliasedRing : List.of(
				McpProtectionKeyring.withActiveKey(
						protectionKey("active-alias", 96)).build(),
				McpProtectionKeyring.withActiveKey(
						protectionKey("active", 0))
						.addVerificationKey(
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
		DefaultMcpSecurityControls controls = controls(McpProtectionKeyring
				.withActiveKey(first).addVerificationKey(second).build(), null);
		String firstFingerprint = controls(McpProtectionKeyring
				.withActiveKey(first).addVerificationKey(second).build(), null)
				.getKeyringSnapshot().orElseThrow().getFingerprint().getValue();
		String secondFingerprint = controls(McpProtectionKeyring
				.withActiveKey(second).addVerificationKey(first).build(), null)
				.getKeyringSnapshot().orElseThrow().getFingerprint().getValue();
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(8);
		try {
			List<Future<?>> futures = new ArrayList<>();
			for (int writer = 0; writer < 4; ++writer) {
				int writerIndex = writer;
				futures.add(executor.submit(() -> {
					start.await();
					for (int iteration = 0; iteration < 1_000; ++iteration)
						controls.rotateActiveKey((iteration + writerIndex) % 2 == 0
								? first : second);
					return null;
				}));
			}
			for (int reader = 0; reader < 4; ++reader)
				futures.add(executor.submit(() -> {
					start.await();
					for (int iteration = 0; iteration < 2_000; ++iteration) {
						McpProtectionKeyringSnapshot snapshot = controls
								.getKeyringSnapshot().orElseThrow();
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
		DefaultMcpSecurityControls controls = controls(McpProtectionKeyring
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
					controls.rotateActiveKey(protectionCandidate);
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
		String activeProtectionId = controls.getKeyringSnapshot().orElseThrow()
				.getActiveKeyId();
		String activeTraceId = controls.getActiveKeyId().orElseThrow();
		Assertions.assertTrue(
				(activeProtectionId.equals("shared-protection")
						&& activeTraceId.equals("trace"))
				|| (activeProtectionId.equals("protection")
						&& activeTraceId.equals("shared-trace")));
	}

	private static byte[] activeProtectionKeyMaterialReference(
			DefaultMcpSecurityControls controls) throws ReflectiveOperationException {
		return ownedKeyMaterialReference(fieldValue(controls,
				"activeProtectionKey"));
	}

	private static byte[] verificationProtectionKeyMaterialReference(
			DefaultMcpSecurityControls controls, String keyId)
			throws ReflectiveOperationException {
		Map<?, ?> keys = (Map<?, ?>) fieldValue(controls,
				"verificationProtectionKeys");
		Object key = keys.get(keyId);
		Assertions.assertNotNull(key);
		return ownedKeyMaterialReference(key);
	}

	private static byte[] activeTraceKeyMaterialReference(
			DefaultMcpSecurityControls controls) throws ReflectiveOperationException {
		return ownedKeyMaterialReference(fieldValue(controls,
				"activeTraceCorrelationKey"));
	}

	private static byte[] ownedKeyMaterialReference(Object ownedKey)
			throws ReflectiveOperationException {
		Assertions.assertNotNull(ownedKey);
		return (byte[]) fieldValue(ownedKey, "keyMaterial");
	}

	private static Object activeSealerContextReference(
			DefaultMcpSecurityControls controls) throws ReflectiveOperationException {
		Object context = fieldValue(controls, "activeSealerContext");
		Assertions.assertNotNull(context);
		return context;
	}

	private static byte[][] activeSealerSecretReferences(
			DefaultMcpSecurityControls controls) throws ReflectiveOperationException {
		Object context = activeSealerContextReference(controls);
		return new byte[][]{
				(byte[]) fieldValue(context, "prk"),
				(byte[]) fieldValue(context, "activationPrefix"),
				(byte[]) fieldValue(context, "derivedKey")
		};
	}

	private static Object fieldValue(Object owner, String fieldName)
			throws ReflectiveOperationException {
		Field field = owner.getClass().getDeclaredField(fieldName);
		field.setAccessible(true);
		return field.get(owner);
	}

	private static void assertLive(byte[]... values) {
		for (byte[] value : values)
			Assertions.assertFalse(Arrays.equals(new byte[value.length], value),
					"Expected retained secret bytes to remain live.");
	}

	private static void assertWiped(byte[]... values) {
		for (byte[] value : values)
			Assertions.assertArrayEquals(new byte[value.length], value,
					"Expected retained secret bytes to be wiped.");
	}

	private static void assertRejectedWithoutProtectionChange(
			DefaultMcpSecurityControls controls, Runnable mutation) {
		McpProtectionKeyringSnapshot before = controls.getKeyringSnapshot()
				.orElseThrow();
		Assertions.assertThrows(IllegalArgumentException.class, mutation::run);
		McpProtectionKeyringSnapshot after = controls.getKeyringSnapshot()
				.orElseThrow();
		Assertions.assertEquals(before.getActiveKeyId(), after.getActiveKeyId());
		Assertions.assertEquals(before.getVerificationKeyIds(),
				after.getVerificationKeyIds());
		Assertions.assertEquals(before.getFingerprint(), after.getFingerprint());
	}

	private static McpProtectionConfig protectionConfig(String keyId,
			int firstByte) {
		return McpProtectionConfig.withKeyring(McpProtectionKeyring
				.withActiveKey(protectionKey(keyId, firstByte)).build()).build();
	}

	private static McpRequestStateProtectionContext protectionContext(
			int firstBindingByte) {
		return McpRequestStateProtectionContext.fromComponents(
				"/mcp", "2026-07-28", "tools/call",
				bytesFrom(firstBindingByte));
	}

	private static TraceContext traceContext(String traceId) {
		return TraceContext.fromHeaderValues(List.of(
				"00-%s-1011121314151617-01".formatted(traceId)), List.of())
				.orElseThrow();
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
		byte[] envelope = envelope(protectedState);
		int keyIdLength = Byte.toUnsignedInt(envelope[1]);
		int offset = 2 + keyIdLength + 1 + 24 + 24;
		long value = 0L;
		for (int index = 0; index < 8; ++index)
			value = (value << 8) | Byte.toUnsignedLong(envelope[offset + index]);
		return value;
	}

	private static String keyId(String protectedState) {
		byte[] envelope = envelope(protectedState);
		int keyIdLength = Byte.toUnsignedInt(envelope[1]);
		return new String(envelope, 2, keyIdLength, StandardCharsets.US_ASCII);
	}

	private static byte[] envelope(String protectedState) {
		String suffix = protectedState.substring(
				DefaultMcpSecurityControls.REQUEST_STATE_PREFIX.length());
		return Base64.getUrlDecoder().decode(suffix);
	}

	private static String protectedState(byte[] envelope) {
		return DefaultMcpSecurityControls.REQUEST_STATE_PREFIX
				+ Base64.getUrlEncoder().withoutPadding().encodeToString(envelope);
	}

	private static byte[] hmacSha256(byte[] key, byte[] value)
			throws Exception {
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(key, "HmacSHA256"));
		return mac.doFinal(value);
	}

	private static byte[] concatenate(byte[]... values) {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		for (byte[] value : values)
			output.writeBytes(value);
		return output.toByteArray();
	}

	private static byte[] associatedData(byte[] header, byte[] binding) {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		output.writeBytes("soklet-mcp-request-state-gcm-aad-v1\0"
				.getBytes(StandardCharsets.US_ASCII));
		writeUnsignedInt(output, header.length);
		output.writeBytes(header);
		writeUnsignedInt(output, binding.length);
		output.writeBytes(binding);
		return output.toByteArray();
	}

	private static void writeUnsignedInt(ByteArrayOutputStream output,
			int value) {
		output.write((value >>> 24) & 0xFF);
		output.write((value >>> 16) & 0xFF);
		output.write((value >>> 8) & 0xFF);
		output.write(value & 0xFF);
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
			McpProtectionKeyring ring,
			McpTraceCorrelationKey traceCorrelationKey) {
		McpProtectionConfig config = ring == null ? null
				: McpProtectionConfig.withKeyring(ring).build();
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
