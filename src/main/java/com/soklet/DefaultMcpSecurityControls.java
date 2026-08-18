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

import javax.annotation.concurrent.ThreadSafe;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Server-owned implementation shared by the protection and trace control
 * views so cross-purpose key-material validation is atomic.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class DefaultMcpSecurityControls
		implements McpProtectionControl, McpTraceCorrelationControl {
	/**
	 * Immutable, secret-free projection of protection and trace-correlation
	 * diagnostics captured at one security-control linearization point.
	 */
	@ThreadSafe
	record SecurityDiagnosticsState(
			@NonNull McpProtectionMode protectionMode,
			boolean applicationRequestStateProtectorConfigured,
			@NonNull Optional<@NonNull McpProtectionKeyRingFingerprint>
					protectionKeyRingFingerprint,
			@NonNull Optional<@NonNull McpTraceCorrelationConfigurationFingerprint>
					traceCorrelationConfigurationFingerprint) {
		SecurityDiagnosticsState {
			requireNonNull(protectionMode);
			requireNonNull(protectionKeyRingFingerprint);
			requireNonNull(traceCorrelationConfigurationFingerprint);
			if (applicationRequestStateProtectorConfigured
					!= (protectionMode == McpProtectionMode.CUSTOM_PROTECTOR))
				throw new IllegalArgumentException(
						"Application request-state protector presence must match custom-protector mode.");
			if (protectionKeyRingFingerprint.isPresent()
					!= (protectionMode == McpProtectionMode.PRODUCTION_KEY_RING))
				throw new IllegalArgumentException(
						"Production protection mode must have exactly one key-ring fingerprint.");
		}
	}

	/**
	 * Immutable, secret-free result of one trace-correlation key snapshot and
	 * token derivation. The pseudonymous token is intentionally redacted from
	 * diagnostic rendering.
	 */
	@ThreadSafe
	record TraceCorrelationToken(@NonNull String keyId,
			@NonNull String token) {
		TraceCorrelationToken {
			requireNonNull(keyId);
			requireNonNull(token);
		}

		@Override
		@NonNull
		public String toString() {
			return "%s{keyId='%s', token=<redacted>}"
					.formatted(getClass().getSimpleName(), keyId());
		}
	}

	@NonNull
	static final String REQUEST_STATE_PREFIX =
			"soklet-mcp-request-state-v1.";
	private static final int REQUEST_STATE_VERSION = 1;
	private static final int ACTIVATION_PREFIX_BYTES = 24;
	private static final int SEALER_EPOCH_BYTES = 32;
	private static final int NONCE_BYTES = 12;
	private static final int GCM_TAG_BYTES = 16;
	private static final int DERIVED_KEY_BYTES = 32;
	private static final int DEVELOPMENT_KEY_BYTES = 32;
	private static final long MAXIMUM_INVOCATIONS_PER_EPOCH = 1L << 32;
	@NonNull
	private static final String PROTECTION_PROFILE =
			"soklet-mcp-protection-v1";
	@NonNull
	private static final String DEVELOPMENT_KEY_ID = "development-ephemeral";
	@NonNull
	private static final byte[] PROTECTION_PROFILE_BYTES = bytes(
			PROTECTION_PROFILE);
	@NonNull
	private static final byte[] PROTECTION_PROFILE_SALT = sha256(bytes(
			PROTECTION_PROFILE + "\0"));
	@NonNull
	private static final byte[] REQUEST_STATE_KEY_LABEL = bytes(
			"soklet-mcp-request-state-aead-v1\0");
	@NonNull
	private static final byte[] REQUEST_STATE_AAD_DOMAIN = bytes(
			"soklet-mcp-request-state-gcm-aad-v1\0");
	private static final byte ACTIVE_ROLE = 0x01;
	private static final byte VERIFICATION_ROLE = 0x02;
	@NonNull
	private static final String TRACE_ALGORITHM =
			"soklet-mcp-trace-correlation-v1";
	@NonNull
	private static final byte[] TRACE_TOKEN_DOMAIN = bytes(
			TRACE_ALGORITHM + "\0");
	@NonNull
	private static final byte[] PROTECTION_ENTRY_DOMAIN = bytes(
			"soklet-mcp-key-fingerprint-v1\0");
	@NonNull
	private static final byte[] PROTECTION_RING_DOMAIN = bytes(
			"soklet-mcp-key-ring-fingerprint-v1\0");
	@NonNull
	private static final byte[] TRACE_ENTRY_DOMAIN = bytes(
			"soklet-mcp-trace-key-fingerprint-v1\0");
	@NonNull
	private static final byte[] TRACE_CONFIGURATION_DOMAIN = bytes(
			"soklet-mcp-trace-key-configuration-fingerprint-v1\0");

	@NonNull
	private final Object lock;
	@NonNull
	private final EntropySource entropySource;
	private final long maximumInvocationsPerEpoch;
	private final long initialEpochNumber;
	@NonNull
	private final McpProtectionMode protectionMode;
	@Nullable
	private final McpRequestStateProtector requestStateProtector;
	private final int maximumEncodedRequestStateBytes;
	private final int maximumDecodedRequestStateBytes;
	@Nullable
	private McpProtectionKey activeProtectionKey;
	@NonNull
	private final Map<@NonNull String, @NonNull McpProtectionKey>
			verificationProtectionKeys;
	@NonNull
	private final Map<@NonNull String, @NonNull Long>
			outstandingSealingReservations;
	@Nullable
	private SealerContext activeSealerContext;
	@Nullable
	private McpTraceCorrelationKey activeTraceCorrelationKey;

	DefaultMcpSecurityControls(@Nullable McpProtectionConfig protectionConfig,
			@Nullable McpTraceCorrelationKey traceCorrelationKey) {
		this(protectionConfig, traceCorrelationKey,
				new SecureRandomEntropySource(),
				MAXIMUM_INVOCATIONS_PER_EPOCH, 0L);
	}

	DefaultMcpSecurityControls(@Nullable McpProtectionConfig protectionConfig,
			@Nullable McpTraceCorrelationKey traceCorrelationKey,
			@NonNull EntropySource entropySource,
			long maximumInvocationsPerEpoch, long initialEpochNumber) {
		if (maximumInvocationsPerEpoch < 1
				|| maximumInvocationsPerEpoch > MAXIMUM_INVOCATIONS_PER_EPOCH)
			throw new IllegalArgumentException(
					"Maximum request-state invocations per epoch must be between 1 and 2^32.");
		this.lock = new Object();
		this.entropySource = requireNonNull(entropySource);
		this.maximumInvocationsPerEpoch = maximumInvocationsPerEpoch;
		this.initialEpochNumber = initialEpochNumber;
		this.protectionMode = protectionConfig == null
				? McpProtectionMode.NO_FRAMEWORK_KEYS
				: protectionConfig.getProtectionMode();
		this.requestStateProtector = protectionConfig == null ? null
				: protectionConfig.getRequestStateProtector().orElse(null);
		this.maximumEncodedRequestStateBytes = protectionConfig == null ? 0
				: protectionConfig.getMaximumEncodedRequestStateBytes();
		this.maximumDecodedRequestStateBytes = protectionConfig == null ? 0
				: protectionConfig.getMaximumDecodedRequestStateBytes();
		this.verificationProtectionKeys = new LinkedHashMap<>();
		this.outstandingSealingReservations = new LinkedHashMap<>();

		if (this.protectionMode == McpProtectionMode.PRODUCTION_KEY_RING) {
			McpProtectionKeyRing keyRing = requireNonNull(protectionConfig)
					.getInitialKeyRing().orElseThrow(() ->
							new IllegalArgumentException(
									"Production protection requires an initial key ring."));
			this.activeProtectionKey = keyRing.copyInitialActiveKey();
			this.verificationProtectionKeys.putAll(
					keyRing.copyInitialVerificationKeys());
		} else if (this.protectionMode
				== McpProtectionMode.DEVELOPMENT_EPHEMERAL) {
			byte[] keyMaterial = new byte[DEVELOPMENT_KEY_BYTES];
			try {
				this.entropySource.nextBytes(keyMaterial);
				this.activeProtectionKey = McpProtectionKey.fromIdAndBytes(
						DEVELOPMENT_KEY_ID, keyMaterial);
			} catch (RuntimeException exception) {
				throw new IllegalStateException(
						"Development request-state protection could not initialize.");
			} finally {
				Arrays.fill(keyMaterial, (byte) 0);
			}
		}

		this.activeTraceCorrelationKey = traceCorrelationKey == null ? null
				: copyOf(traceCorrelationKey);
		if (this.activeTraceCorrelationKey != null)
			requireDistinctFromProtectionKeys(this.activeTraceCorrelationKey);
	}

	@Override
	@NonNull
	public McpProtectionMode getProtectionMode() {
		return this.protectionMode;
	}

	/**
	 * Captures the live protection and trace-correlation diagnostics while holding
	 * their shared mutation lock.
	 *
	 * @return immutable secret-free diagnostics state
	 */
	@NonNull
	SecurityDiagnosticsState getDiagnosticsState() {
		synchronized (this.lock) {
			Optional<@NonNull McpProtectionKeyRingFingerprint>
					protectionKeyRingFingerprint =
					this.protectionMode == McpProtectionMode.PRODUCTION_KEY_RING
							? Optional.of(protectionFingerprint(
									requireNonNull(this.activeProtectionKey),
									this.verificationProtectionKeys.values()))
							: Optional.empty();
			Optional<@NonNull McpTraceCorrelationConfigurationFingerprint>
					traceCorrelationConfigurationFingerprint =
					Optional.ofNullable(this.activeTraceCorrelationKey)
							.map(DefaultMcpSecurityControls::traceFingerprint);
			return new SecurityDiagnosticsState(this.protectionMode,
					this.requestStateProtector != null,
					protectionKeyRingFingerprint,
					traceCorrelationConfigurationFingerprint);
		}
	}

	@Override
	@NonNull
	public Optional<@NonNull McpProtectionKeyRingSnapshot> getKeyRingSnapshot() {
		synchronized (this.lock) {
			if (this.protectionMode
					!= McpProtectionMode.PRODUCTION_KEY_RING)
				return Optional.empty();
			return Optional.of(new McpProtectionKeyRingSnapshot(
					requireNonNull(this.activeProtectionKey).getKeyId(),
					Set.copyOf(this.verificationProtectionKeys.keySet()),
					protectionFingerprint(this.activeProtectionKey,
							this.verificationProtectionKeys.values())));
		}
	}

	@Override
	public void stageVerificationKey(
			@NonNull McpProtectionKey verificationKey) {
		requireNonNull(verificationKey);
		synchronized (this.lock) {
			requireProductionRing();
			McpProtectionKey existing = protectionKeyWithId(
					verificationKey.getKeyId());
			if (existing != null) {
				if (existing.hasSameMaterial(verificationKey))
					return;
				throw new IllegalArgumentException(
						"Duplicate MCP protection key ID with different material.");
			}
			requireUniqueProtectionMaterial(verificationKey);
			if (this.activeTraceCorrelationKey != null
					&& verificationKey.hasSameMaterial(
							this.activeTraceCorrelationKey))
				throw new IllegalArgumentException(
						"Protection and trace-correlation keys must use distinct material.");
			McpProtectionKey copy = copyOf(verificationKey);
			this.verificationProtectionKeys.put(copy.getKeyId(), copy);
		}
	}

	@Override
	public void activateStagedKey(@NonNull String keyId) {
		requireNonNull(keyId);
		synchronized (this.lock) {
			requireProductionRing();
			if (requireNonNull(this.activeProtectionKey).getKeyId()
					.equals(keyId))
				return;
			McpProtectionKey staged = this.verificationProtectionKeys.remove(keyId);
			if (staged == null)
				throw new IllegalArgumentException(
						"Unknown staged MCP protection key ID.");
			SealerContext sealerContext;
			try {
				sealerContext = newSealerContext(staged, 0L);
			} catch (RuntimeException exception) {
				this.verificationProtectionKeys.put(keyId, staged);
				throw new IllegalStateException(
						"MCP protection key activation could not initialize.");
			}
			McpProtectionKey formerActive = this.activeProtectionKey;
			this.activeProtectionKey = staged;
			this.verificationProtectionKeys.put(formerActive.getKeyId(),
					formerActive);
			this.activeSealerContext = sealerContext;
		}
	}

	@Override
	public void rotateTo(@NonNull McpProtectionKey activeKey) {
		requireNonNull(activeKey);
		synchronized (this.lock) {
			requireProductionRing();
			McpProtectionKey current = requireNonNull(this.activeProtectionKey);
			if (current.getKeyId().equals(activeKey.getKeyId())) {
				if (current.hasSameMaterial(activeKey))
					return;
				throw new IllegalArgumentException(
						"Duplicate MCP protection key ID with different material.");
			}
			McpProtectionKey staged = this.verificationProtectionKeys.get(
					activeKey.getKeyId());
			if (staged != null) {
				if (!staged.hasSameMaterial(activeKey))
					throw new IllegalArgumentException(
							"Duplicate MCP protection key ID with different material.");
				activateStagedKey(activeKey.getKeyId());
				return;
			}
			requireUniqueProtectionMaterial(activeKey);
			if (this.activeTraceCorrelationKey != null
					&& activeKey.hasSameMaterial(this.activeTraceCorrelationKey))
				throw new IllegalArgumentException(
						"Protection and trace-correlation keys must use distinct material.");
			McpProtectionKey copy = copyOf(activeKey);
			SealerContext sealerContext;
			try {
				sealerContext = newSealerContext(copy, 0L);
			} catch (RuntimeException exception) {
				throw new IllegalStateException(
						"MCP protection key rotation could not initialize.");
			}
			this.verificationProtectionKeys.put(current.getKeyId(), current);
			this.activeProtectionKey = copy;
			this.activeSealerContext = sealerContext;
		}
	}

	@Override
	@NonNull
	public Boolean removeVerificationKey(@NonNull String keyId) {
		requireNonNull(keyId);
		synchronized (this.lock) {
			requireProductionRing();
			if (requireNonNull(this.activeProtectionKey).getKeyId()
					.equals(keyId))
				throw new IllegalArgumentException(
						"The active MCP protection key cannot be removed.");
			if (this.outstandingSealingReservations.getOrDefault(keyId, 0L)
					> 0L)
				throw new McpKeyInUseException();
			return this.verificationProtectionKeys.remove(keyId) != null;
		}
	}

	void validateRequestStateStructure(@NonNull String protectedState)
			throws McpRequestStateProtectionException {
		requireConfiguredProtection();
		validateWireValue(protectedState);
		if (isBuiltInProtection()) {
			ParsedEnvelope envelope = parseBuiltInEnvelope(protectedState);
			envelope.clear();
		}
	}

	@NonNull
	String sealRequestState(
			@NonNull McpRequestStateProtectionContext context,
			byte @NonNull [] plaintext)
			throws McpRequestStateProtectionException {
		requireConfiguredProtection();
		requireNonNull(context);
		requireNonNull(plaintext);
		if (plaintext.length == 0
				|| plaintext.length > this.maximumDecodedRequestStateBytes)
			throw new IllegalStateException(
					"Canonical MCP request state exceeds its configured size limit.");

		if (this.protectionMode == McpProtectionMode.CUSTOM_PROTECTOR) {
			byte[] protectorPlaintext = plaintext.clone();
			try {
				String protectedState = requireNonNull(this.requestStateProtector)
						.seal(context, protectorPlaintext);
				if (protectedState == null)
					throw new IllegalStateException(
							"The custom MCP request-state protector returned null.");
				validateCustomSealedValue(protectedState);
				return protectedState;
			} finally {
				Arrays.fill(protectorPlaintext, (byte) 0);
			}
		}

		SealReservation reservation = reserveSeal(plaintext.length);
		try {
			byte[] nonce = new byte[NONCE_BYTES];
			try {
				this.entropySource.nextBytes(nonce);
			} catch (RuntimeException exception) {
				throw McpRequestStateProtectionException
						.fromProtectorUnavailable();
			}
			byte[] header = builtInHeader(reservation.keyId(),
					reservation.sealerEpoch(), nonce);
			byte[] associatedData = builtInAssociatedData(header,
					context.getAssociatedData());
			byte[] ciphertext;
			try {
				ciphertext = encrypt(reservation.derivedKey(), nonce,
						associatedData, plaintext);
			} catch (GeneralSecurityException exception) {
				throw McpRequestStateProtectionException
						.fromProtectorUnavailable();
			} finally {
				Arrays.fill(nonce, (byte) 0);
				Arrays.fill(associatedData, (byte) 0);
			}
			ByteArrayOutputStream envelope = new ByteArrayOutputStream(
					header.length + ciphertext.length);
			envelope.writeBytes(header);
			envelope.writeBytes(ciphertext);
			byte[] envelopeBytes = envelope.toByteArray();
			try {
				if (envelopeBytes.length
						> this.maximumDecodedRequestStateBytes)
					throw new IllegalStateException(
							"Protected MCP request state exceeds its configured decoded-size limit.");
				String protectedState = REQUEST_STATE_PREFIX
						+ base64Url(envelopeBytes);
				if (protectedState.length()
						> this.maximumEncodedRequestStateBytes)
					throw new IllegalStateException(
							"Protected MCP request state exceeds its configured encoded-size limit.");
				return protectedState;
			} finally {
				Arrays.fill(ciphertext, (byte) 0);
				Arrays.fill(envelopeBytes, (byte) 0);
				Arrays.fill(header, (byte) 0);
			}
		} finally {
			releaseSeal(reservation);
		}
	}

	byte @NonNull [] openRequestState(
			@NonNull McpRequestStateProtectionContext context,
			@NonNull String protectedState)
			throws McpRequestStateProtectionException {
		requireConfiguredProtection();
		requireNonNull(context);
		if (this.protectionMode == McpProtectionMode.CUSTOM_PROTECTOR) {
			validateWireValue(protectedState);
			byte[] plaintext = requireNonNull(this.requestStateProtector)
					.open(context, protectedState);
			if (plaintext == null)
				throw new IllegalStateException(
						"The custom MCP request-state protector returned null.");
			if (plaintext.length == 0
					|| plaintext.length > this.maximumDecodedRequestStateBytes)
				throw McpRequestStateProtectionException.fromInvalidState();
			return plaintext;
		}

		ParsedEnvelope envelope = parseBuiltInEnvelope(protectedState);
		McpProtectionKey key = protectionKeySnapshot(envelope.keyId());
		if (key == null) {
			envelope.clear();
			throw McpRequestStateProtectionException.fromInvalidState();
		}
		byte[] masterKey = key.copyKeyMaterial();
		byte[] derivedKey = null;
		byte[] associatedData = null;
		try {
			derivedKey = deriveEpochKey(masterKey, envelope.sealerEpoch());
			associatedData = builtInAssociatedData(envelope.header(),
					context.getAssociatedData());
			byte[] plaintext;
			try {
				plaintext = decrypt(derivedKey, envelope.nonce(), associatedData,
						envelope.ciphertextAndTag());
			} catch (AEADBadTagException exception) {
				throw McpRequestStateProtectionException.fromInvalidState();
			} catch (GeneralSecurityException exception) {
				throw McpRequestStateProtectionException
						.fromProtectorUnavailable();
			}
			if (plaintext.length == 0
					|| plaintext.length > this.maximumDecodedRequestStateBytes) {
				Arrays.fill(plaintext, (byte) 0);
				throw McpRequestStateProtectionException.fromInvalidState();
			}
			return plaintext;
		} finally {
			Arrays.fill(masterKey, (byte) 0);
			if (derivedKey != null)
				Arrays.fill(derivedKey, (byte) 0);
			if (associatedData != null)
				Arrays.fill(associatedData, (byte) 0);
			envelope.clear();
		}
	}

	@Override
	@NonNull
	public Boolean isEnabled() {
		synchronized (this.lock) {
			return this.activeTraceCorrelationKey != null;
		}
	}

	@Override
	@NonNull
	public Optional<@NonNull String> getActiveKeyId() {
		synchronized (this.lock) {
			return Optional.ofNullable(this.activeTraceCorrelationKey)
					.map(McpTraceCorrelationKey::getKeyId);
		}
	}

	@Override
	@NonNull
	public Optional<@NonNull McpTraceCorrelationConfigurationFingerprint>
			getConfigurationFingerprint() {
		synchronized (this.lock) {
			return Optional.ofNullable(this.activeTraceCorrelationKey)
					.map(DefaultMcpSecurityControls::traceFingerprint);
		}
	}

	@Override
	public void rotateActiveKey(@NonNull McpTraceCorrelationKey activeKey) {
		requireNonNull(activeKey);
		synchronized (this.lock) {
			if (this.activeTraceCorrelationKey == null)
				throw new IllegalStateException(
						"MCP trace correlation was not enabled at server construction.");
			requireDistinctFromProtectionKeys(activeKey);
			if (this.activeTraceCorrelationKey.getKeyId().equals(
					activeKey.getKeyId())) {
				if (this.activeTraceCorrelationKey.hasSameMaterial(activeKey))
					return;
				throw new IllegalArgumentException(
						"Duplicate MCP trace-correlation key ID with different material.");
			}
			this.activeTraceCorrelationKey = copyOf(activeKey);
		}
	}

	/**
	 * Atomically snapshots the active trace-correlation key pair and derives the
	 * pseudonymous token for a validated trace context. Key material is copied
	 * while holding the security-control lock, but cryptography is performed
	 * after releasing it. No key material or trace identifier is retained by the
	 * returned value.
	 *
	 * @param traceContext validated W3C trace context
	 * @return the key ID and derived token, or an empty optional when trace
	 *         correlation is disabled
	 */
	@NonNull
	Optional<@NonNull TraceCorrelationToken> deriveTraceCorrelationToken(
			@NonNull TraceContext traceContext) {
		requireNonNull(traceContext);
		String keyId;
		byte[] keyMaterial;

		synchronized (this.lock) {
			McpTraceCorrelationKey activeKey = this.activeTraceCorrelationKey;
			if (activeKey == null)
				return Optional.empty();
			keyId = activeKey.getKeyId();
			keyMaterial = activeKey.copyKeyMaterial();
		}

		byte[] traceIdBytes = new byte[16];
		byte[] authenticated = null;
		byte[] digest = null;
		byte[] tokenBytes = null;
		try {
			String traceId = traceContext.getTraceId();
			if (traceId.length() != traceIdBytes.length * 2)
				throw new IllegalArgumentException(
						"Trace context must contain a validated 16-byte trace identifier.");
			for (int index = 0; index < traceIdBytes.length; ++index) {
				int high = Character.digit(traceId.charAt(index * 2), 16);
				int low = Character.digit(traceId.charAt(index * 2 + 1), 16);
				if (high < 0 || low < 0)
					throw new IllegalArgumentException(
							"Trace context must contain a validated 16-byte trace identifier.");
				traceIdBytes[index] = (byte) ((high << 4) | low);
			}

			authenticated = new byte[TRACE_TOKEN_DOMAIN.length
					+ traceIdBytes.length];
			System.arraycopy(TRACE_TOKEN_DOMAIN, 0, authenticated, 0,
					TRACE_TOKEN_DOMAIN.length);
			System.arraycopy(traceIdBytes, 0, authenticated,
					TRACE_TOKEN_DOMAIN.length, traceIdBytes.length);
			digest = hmacSha256(keyMaterial, authenticated);
			tokenBytes = Arrays.copyOf(digest, 16);
			return Optional.of(new TraceCorrelationToken(keyId,
					base64Url(tokenBytes)));
		} finally {
			Arrays.fill(keyMaterial, (byte) 0);
			Arrays.fill(traceIdBytes, (byte) 0);
			if (authenticated != null)
				Arrays.fill(authenticated, (byte) 0);
			if (digest != null)
				Arrays.fill(digest, (byte) 0);
			if (tokenBytes != null)
				Arrays.fill(tokenBytes, (byte) 0);
		}
	}

	private void requireConfiguredProtection() {
		if (this.protectionMode == McpProtectionMode.NO_FRAMEWORK_KEYS)
			throw new IllegalStateException(
					"Framework-managed MCP request-state protection is not configured.");
	}

	private boolean isBuiltInProtection() {
		return this.protectionMode == McpProtectionMode.PRODUCTION_KEY_RING
				|| this.protectionMode
				== McpProtectionMode.DEVELOPMENT_EPHEMERAL;
	}

	private void validateCustomSealedValue(@NonNull String protectedState) {
		try {
			validateWireValue(protectedState);
		} catch (McpRequestStateProtectionException exception) {
			throw new IllegalStateException(
					"The custom MCP request-state protector returned an invalid wire value.");
		}
	}

	private void validateWireValue(@NonNull String protectedState)
			throws McpRequestStateProtectionException {
		requireNonNull(protectedState);
		int encodedBytes = utf8Length(protectedState);
		if (protectedState.isEmpty() || encodedBytes < 0
				|| encodedBytes > this.maximumEncodedRequestStateBytes)
			throw McpRequestStateProtectionException.fromInvalidState();
	}

	@NonNull
	private SealReservation reserveSeal(int plaintextBytes)
			throws McpRequestStateProtectionException {
		synchronized (this.lock) {
			try {
				McpProtectionKey activeKey = requireNonNull(
						this.activeProtectionKey);
				SealerContext context = this.activeSealerContext;
				if (context == null) {
					context = newSealerContext(activeKey,
							this.initialEpochNumber);
					this.activeSealerContext = context;
				}
				if (context.invocationCount()
						>= this.maximumInvocationsPerEpoch) {
					if (context.epochNumber() == -1L)
						throw McpRequestStateProtectionException
								.fromProtectorUnavailable();
					context = nextSealerEpoch(context);
					this.activeSealerContext = context;
				}

				int decodedBytes = builtInHeaderLength(context.keyId())
						+ plaintextBytes + GCM_TAG_BYTES;
				if (decodedBytes > this.maximumDecodedRequestStateBytes
						|| encodedWireLength(decodedBytes)
						> this.maximumEncodedRequestStateBytes)
					throw new IllegalStateException(
							"Protected MCP request state exceeds its configured size limit.");

				this.activeSealerContext = context.withInvocationCount(
						context.invocationCount() + 1L);
				this.outstandingSealingReservations.merge(context.keyId(),
						1L, Long::sum);
				return new SealReservation(context.keyId(),
						context.sealerEpoch(), context.derivedKey());
			} catch (McpRequestStateProtectionException exception) {
				throw exception;
			} catch (IllegalStateException exception) {
				throw exception;
			} catch (RuntimeException exception) {
				throw McpRequestStateProtectionException
						.fromProtectorUnavailable();
			}
		}
	}

	private void releaseSeal(@NonNull SealReservation reservation) {
		synchronized (this.lock) {
			long remaining = this.outstandingSealingReservations.getOrDefault(
					reservation.keyId(), 0L) - 1L;
			if (remaining <= 0L)
				this.outstandingSealingReservations.remove(reservation.keyId());
			else
				this.outstandingSealingReservations.put(reservation.keyId(),
						remaining);
		}
		reservation.clear();
	}

	@NonNull
	private SealerContext newSealerContext(@NonNull McpProtectionKey key,
			long epochNumber) {
		byte[] activationPrefix = new byte[ACTIVATION_PREFIX_BYTES];
		byte[] masterKey = key.copyKeyMaterial();
		byte[] prk = null;
		try {
			this.entropySource.nextBytes(activationPrefix);
			prk = hkdfExtract(masterKey);
			byte[] sealerEpoch = sealerEpoch(activationPrefix, epochNumber);
			byte[] derivedKey = hkdfExpand(prk, sealerEpoch);
			return new SealerContext(key.getKeyId(), prk.clone(),
					activationPrefix.clone(), epochNumber, 0L, derivedKey);
		} catch (RuntimeException exception) {
			throw new SealerUnavailableException();
		} finally {
			Arrays.fill(masterKey, (byte) 0);
			if (prk != null)
				Arrays.fill(prk, (byte) 0);
			Arrays.fill(activationPrefix, (byte) 0);
		}
	}

	@NonNull
	private static SealerContext nextSealerEpoch(
			@NonNull SealerContext context) {
		try {
			long nextEpochNumber = context.epochNumber() + 1L;
			byte[] sealerEpoch = sealerEpoch(context.activationPrefix(),
					nextEpochNumber);
			byte[] derivedKey = hkdfExpand(context.prk(), sealerEpoch);
			return new SealerContext(context.keyId(), context.prk(),
					context.activationPrefix(), nextEpochNumber, 0L, derivedKey);
		} catch (RuntimeException exception) {
			throw new SealerUnavailableException();
		}
	}

	@NonNull
	private ParsedEnvelope parseBuiltInEnvelope(
			@NonNull String protectedState)
			throws McpRequestStateProtectionException {
		validateWireValue(protectedState);
		if (!protectedState.startsWith(REQUEST_STATE_PREFIX))
			throw McpRequestStateProtectionException.fromInvalidState();
		String suffix = protectedState.substring(REQUEST_STATE_PREFIX.length());
		if (suffix.isEmpty())
			throw McpRequestStateProtectionException.fromInvalidState();
		for (int index = 0; index < suffix.length(); ++index) {
			char value = suffix.charAt(index);
			if (!((value >= 'A' && value <= 'Z')
					|| (value >= 'a' && value <= 'z')
					|| (value >= '0' && value <= '9')
					|| value == '_' || value == '-'))
				throw McpRequestStateProtectionException.fromInvalidState();
		}
		byte[] decoded;
		try {
			decoded = Base64.getUrlDecoder().decode(suffix);
		} catch (IllegalArgumentException exception) {
			throw McpRequestStateProtectionException.fromInvalidState();
		}
		if (!base64Url(decoded).equals(suffix)
				|| decoded.length > this.maximumDecodedRequestStateBytes)
			throw McpRequestStateProtectionException.fromInvalidState();

		try {
			int offset = 0;
			if (decoded.length < builtInHeaderLength("a") + 1
					+ GCM_TAG_BYTES
					|| Byte.toUnsignedInt(decoded[offset++])
					!= REQUEST_STATE_VERSION)
				throw McpRequestStateProtectionException.fromInvalidState();
			int keyIdLength = Byte.toUnsignedInt(decoded[offset++]);
			if (keyIdLength < 1 || keyIdLength > 64
					|| offset + keyIdLength + 1 > decoded.length)
				throw McpRequestStateProtectionException.fromInvalidState();
			for (int index = offset; index < offset + keyIdLength; ++index)
				if (Byte.toUnsignedInt(decoded[index]) > 0x7F)
					throw McpRequestStateProtectionException.fromInvalidState();
			String keyId = new String(decoded, offset, keyIdLength,
					StandardCharsets.US_ASCII);
			try {
				McpKeyIdValidator.validate(keyId, "MCP protection key ID");
			} catch (IllegalArgumentException exception) {
				throw McpRequestStateProtectionException.fromInvalidState();
			}
			offset += keyIdLength;
			int profileLength = Byte.toUnsignedInt(decoded[offset++]);
			if (profileLength != PROTECTION_PROFILE_BYTES.length
					|| offset + profileLength + SEALER_EPOCH_BYTES
					+ NONCE_BYTES + 1 + GCM_TAG_BYTES > decoded.length)
				throw McpRequestStateProtectionException.fromInvalidState();
			for (int index = 0; index < profileLength; ++index)
				if (decoded[offset + index] != PROTECTION_PROFILE_BYTES[index])
					throw McpRequestStateProtectionException.fromInvalidState();
			offset += profileLength;
			byte[] sealerEpoch = Arrays.copyOfRange(decoded, offset,
					offset + SEALER_EPOCH_BYTES);
			offset += SEALER_EPOCH_BYTES;
			byte[] nonce = Arrays.copyOfRange(decoded, offset,
					offset + NONCE_BYTES);
			offset += NONCE_BYTES;
			byte[] header = Arrays.copyOfRange(decoded, 0, offset);
			byte[] ciphertextAndTag = Arrays.copyOfRange(decoded, offset,
					decoded.length);
			return new ParsedEnvelope(keyId, sealerEpoch, nonce, header,
					ciphertextAndTag);
		} finally {
			Arrays.fill(decoded, (byte) 0);
		}
	}

	@Nullable
	private McpProtectionKey protectionKeySnapshot(@NonNull String keyId) {
		synchronized (this.lock) {
			McpProtectionKey key = this.activeProtectionKey != null
					&& this.activeProtectionKey.getKeyId().equals(keyId)
					? this.activeProtectionKey
					: this.verificationProtectionKeys.get(keyId);
			return key == null ? null : copyOf(key);
		}
	}

	private static int builtInHeaderLength(@NonNull String keyId) {
		return 1 + 1 + keyId.length() + 1 + PROTECTION_PROFILE_BYTES.length
				+ SEALER_EPOCH_BYTES + NONCE_BYTES;
	}

	private static int encodedWireLength(int decodedBytes) {
		int fullGroups = decodedBytes / 3;
		int remainder = decodedBytes % 3;
		return REQUEST_STATE_PREFIX.length() + fullGroups * 4
				+ (remainder == 0 ? 0 : remainder + 1);
	}

	private static int utf8Length(@NonNull String value) {
		long length = 0L;
		for (int index = 0; index < value.length(); ++index) {
			char current = value.charAt(index);
			if (current <= 0x7F)
				++length;
			else if (current <= 0x7FF)
				length += 2L;
			else if (Character.isHighSurrogate(current)) {
				if (index + 1 >= value.length()
						|| !Character.isLowSurrogate(value.charAt(index + 1)))
					return -1;
				length += 4L;
				++index;
			} else if (Character.isLowSurrogate(current))
				return -1;
			else
				length += 3L;
			if (length > Integer.MAX_VALUE)
				return Integer.MAX_VALUE;
		}
		return (int) length;
	}

	private static byte @NonNull [] builtInHeader(@NonNull String keyId,
			byte @NonNull [] sealerEpoch, byte @NonNull [] nonce) {
		byte[] keyIdBytes = bytes(keyId);
		ByteArrayOutputStream header = new ByteArrayOutputStream(
				builtInHeaderLength(keyId));
		header.write(REQUEST_STATE_VERSION);
		header.write(keyIdBytes.length);
		header.writeBytes(keyIdBytes);
		header.write(PROTECTION_PROFILE_BYTES.length);
		header.writeBytes(PROTECTION_PROFILE_BYTES);
		header.writeBytes(sealerEpoch);
		header.writeBytes(nonce);
		return header.toByteArray();
	}

	private static byte @NonNull [] builtInAssociatedData(
			byte @NonNull [] header, byte @NonNull [] binding) {
		ByteArrayOutputStream associatedData = new ByteArrayOutputStream();
		associatedData.writeBytes(REQUEST_STATE_AAD_DOMAIN);
		writeUnsignedInt(associatedData, header.length);
		associatedData.writeBytes(header);
		writeUnsignedInt(associatedData, binding.length);
		associatedData.writeBytes(binding);
		return associatedData.toByteArray();
	}

	private static byte @NonNull [] sealerEpoch(
			byte @NonNull [] activationPrefix, long epochNumber) {
		ByteArrayOutputStream epoch = new ByteArrayOutputStream(
				SEALER_EPOCH_BYTES);
		epoch.writeBytes(activationPrefix);
		for (int shift = 56; shift >= 0; shift -= 8)
			epoch.write((int) (epochNumber >>> shift) & 0xFF);
		return epoch.toByteArray();
	}

	private static byte @NonNull [] hkdfExtract(byte @NonNull [] masterKey) {
		return hmacSha256(PROTECTION_PROFILE_SALT, masterKey);
	}

	private static byte @NonNull [] hkdfExpand(byte @NonNull [] prk,
			byte @NonNull [] sealerEpoch) {
		ByteArrayOutputStream info = new ByteArrayOutputStream();
		info.writeBytes(REQUEST_STATE_KEY_LABEL);
		info.writeBytes(sealerEpoch);
		info.write(1);
		byte[] derived = hmacSha256(prk, info.toByteArray());
		if (derived.length != DERIVED_KEY_BYTES)
			throw new IllegalStateException(
					"HKDF-SHA-256 returned an unexpected key length.");
		return derived;
	}

	private static byte @NonNull [] deriveEpochKey(byte @NonNull [] masterKey,
			byte @NonNull [] sealerEpoch) {
		byte[] prk = hkdfExtract(masterKey);
		try {
			return hkdfExpand(prk, sealerEpoch);
		} finally {
			Arrays.fill(prk, (byte) 0);
		}
	}

	private static byte @NonNull [] encrypt(byte @NonNull [] key,
			byte @NonNull [] nonce, byte @NonNull [] associatedData,
			byte @NonNull [] plaintext) throws GeneralSecurityException {
		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
				new GCMParameterSpec(GCM_TAG_BYTES * 8, nonce));
		cipher.updateAAD(associatedData);
		return cipher.doFinal(plaintext);
	}

	private static byte @NonNull [] decrypt(byte @NonNull [] key,
			byte @NonNull [] nonce, byte @NonNull [] associatedData,
			byte @NonNull [] ciphertextAndTag)
			throws GeneralSecurityException {
		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
				new GCMParameterSpec(GCM_TAG_BYTES * 8, nonce));
		cipher.updateAAD(associatedData);
		return cipher.doFinal(ciphertextAndTag);
	}

	private void requireProductionRing() {
		if (this.protectionMode != McpProtectionMode.PRODUCTION_KEY_RING)
			throw new IllegalStateException(
					"MCP protection key control requires a production key ring.");
	}

	@Nullable
	private McpProtectionKey protectionKeyWithId(@NonNull String keyId) {
		if (requireNonNull(this.activeProtectionKey).getKeyId().equals(keyId))
			return this.activeProtectionKey;
		return this.verificationProtectionKeys.get(keyId);
	}

	private void requireUniqueProtectionMaterial(
			@NonNull McpProtectionKey candidate) {
		if (requireNonNull(this.activeProtectionKey).hasSameMaterial(candidate)
				|| this.verificationProtectionKeys.values().stream()
				.anyMatch(existing -> existing.hasSameMaterial(candidate)))
			throw new IllegalArgumentException(
					"Duplicate MCP protection key material.");
	}

	private void requireDistinctFromProtectionKeys(
			@NonNull McpTraceCorrelationKey candidate) {
		if ((this.activeProtectionKey != null
				&& this.activeProtectionKey.hasSameMaterial(candidate))
				|| this.verificationProtectionKeys.values().stream()
				.anyMatch(key -> key.hasSameMaterial(candidate)))
			throw new IllegalArgumentException(
					"Protection and trace-correlation keys must use distinct material.");
	}

	@NonNull
	private static McpProtectionKey copyOf(@NonNull McpProtectionKey key) {
		byte[] keyMaterial = key.copyKeyMaterial();
		try {
			return McpProtectionKey.fromIdAndBytes(key.getKeyId(), keyMaterial);
		} finally {
			Arrays.fill(keyMaterial, (byte) 0);
		}
	}

	@NonNull
	private static McpTraceCorrelationKey copyOf(
			@NonNull McpTraceCorrelationKey key) {
		byte[] keyMaterial = key.copyKeyMaterial();
		try {
		return McpTraceCorrelationKey.fromIdAndBytes(key.getKeyId(), keyMaterial);
		} finally {
			Arrays.fill(keyMaterial, (byte) 0);
		}
	}

	@NonNull
	private static McpProtectionKeyRingFingerprint protectionFingerprint(
			@NonNull McpProtectionKey activeKey,
			@NonNull Iterable<@NonNull McpProtectionKey> verificationKeys) {
		List<FingerprintRecord> records = new ArrayList<>();
		records.add(fingerprintRecord(activeKey.getKeyId(),
				McpProtectionKeyRingFingerprint.PROFILE, ACTIVE_ROLE,
				activeKey.copyKeyMaterial(), PROTECTION_ENTRY_DOMAIN));
		for (McpProtectionKey verificationKey : verificationKeys)
			records.add(fingerprintRecord(verificationKey.getKeyId(),
					McpProtectionKeyRingFingerprint.PROFILE, VERIFICATION_ROLE,
					verificationKey.copyKeyMaterial(), PROTECTION_ENTRY_DOMAIN));
		records.sort(Comparator.comparing(FingerprintRecord::metadata,
				DefaultMcpSecurityControls::compareUnsigned));
		ByteArrayOutputStream aggregate = new ByteArrayOutputStream();
		aggregate.writeBytes(PROTECTION_RING_DOMAIN);
		writeUnsignedInt(aggregate, records.size());
		records.forEach(record -> aggregate.writeBytes(record.encoded()));
		return new McpProtectionKeyRingFingerprint(base64Url(sha256(
				aggregate.toByteArray())));
	}

	@NonNull
	private static McpTraceCorrelationConfigurationFingerprint traceFingerprint(
			@NonNull McpTraceCorrelationKey key) {
		FingerprintRecord record = fingerprintRecord(key.getKeyId(), TRACE_ALGORITHM,
				ACTIVE_ROLE, key.copyKeyMaterial(), TRACE_ENTRY_DOMAIN);
		ByteArrayOutputStream aggregate = new ByteArrayOutputStream();
		aggregate.writeBytes(TRACE_CONFIGURATION_DOMAIN);
		writeUnsignedInt(aggregate, 1);
		aggregate.writeBytes(record.encoded());
		return McpTraceCorrelationConfigurationFingerprint.fromValue(
				base64Url(sha256(aggregate.toByteArray())));
	}

	@NonNull
	private static FingerprintRecord fingerprintRecord(@NonNull String keyId,
			@NonNull String algorithm, byte role, byte @NonNull [] keyMaterial,
			byte @NonNull [] entryDomain) {
		try {
			byte[] id = bytes(keyId);
			byte[] algorithmBytes = bytes(algorithm);
			ByteArrayOutputStream metadata = new ByteArrayOutputStream();
			writeUnsignedInt(metadata, id.length);
			metadata.writeBytes(id);
			writeUnsignedInt(metadata, algorithmBytes.length);
			metadata.writeBytes(algorithmBytes);
			metadata.write(role);
			byte[] metadataBytes = metadata.toByteArray();
			ByteArrayOutputStream authenticated = new ByteArrayOutputStream();
			authenticated.writeBytes(entryDomain);
			authenticated.writeBytes(metadataBytes);
			byte[] tag = hmacSha256(keyMaterial, authenticated.toByteArray());
			ByteArrayOutputStream encoded = new ByteArrayOutputStream();
			writeUnsignedInt(encoded, metadataBytes.length);
			encoded.writeBytes(metadataBytes);
			writeUnsignedInt(encoded, tag.length);
			encoded.writeBytes(tag);
			return new FingerprintRecord(metadataBytes, encoded.toByteArray());
		} finally {
			Arrays.fill(keyMaterial, (byte) 0);
		}
	}

	private static void writeUnsignedInt(@NonNull ByteArrayOutputStream output,
			int value) {
		output.write((value >>> 24) & 0xFF);
		output.write((value >>> 16) & 0xFF);
		output.write((value >>> 8) & 0xFF);
		output.write(value & 0xFF);
	}

	private static int compareUnsigned(byte @NonNull [] first,
			byte @NonNull [] second) {
		int commonLength = Math.min(first.length, second.length);
		for (int index = 0; index < commonLength; ++index) {
			int comparison = Integer.compare(Byte.toUnsignedInt(first[index]),
					Byte.toUnsignedInt(second[index]));
			if (comparison != 0)
				return comparison;
		}
		return Integer.compare(first.length, second.length);
	}

	private static byte @NonNull [] hmacSha256(byte @NonNull [] key,
			byte @NonNull [] value) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(key, "HmacSHA256"));
			return mac.doFinal(value);
		} catch (GeneralSecurityException exception) {
			throw new IllegalStateException(
					"Required HMAC-SHA-256 support is unavailable.", exception);
		}
	}

	private static byte @NonNull [] sha256(byte @NonNull [] value) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(value);
		} catch (GeneralSecurityException exception) {
			throw new IllegalStateException(
					"Required SHA-256 support is unavailable.", exception);
		}
	}

	@NonNull
	private static String base64Url(byte @NonNull [] value) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
	}

	private static byte @NonNull [] bytes(@NonNull String value) {
		return value.getBytes(StandardCharsets.UTF_8);
	}

	@FunctionalInterface
	interface EntropySource {
		void nextBytes(byte @NonNull [] destination);
	}

	@ThreadSafe
	private static final class SecureRandomEntropySource
			implements EntropySource {
		@NonNull
		private final SecureRandom secureRandom = new SecureRandom();

		@Override
		public void nextBytes(byte @NonNull [] destination) {
			this.secureRandom.nextBytes(destination);
		}
	}

	private static final class SealerUnavailableException
			extends RuntimeException {
		private static final long serialVersionUID = 1L;
	}

	private record SealerContext(@NonNull String keyId,
			byte @NonNull [] prk, byte @NonNull [] activationPrefix,
			long epochNumber, long invocationCount,
			byte @NonNull [] derivedKey) {
		@NonNull
		private SealerContext withInvocationCount(long invocationCount) {
			return new SealerContext(this.keyId, this.prk,
					this.activationPrefix, this.epochNumber, invocationCount,
					this.derivedKey);
		}

		private byte @NonNull [] sealerEpoch() {
			return DefaultMcpSecurityControls.sealerEpoch(
					this.activationPrefix, this.epochNumber);
		}
	}

	private record SealReservation(@NonNull String keyId,
			byte @NonNull [] sealerEpoch,
			byte @NonNull [] derivedKey) {
		private SealReservation {
			sealerEpoch = sealerEpoch.clone();
			derivedKey = derivedKey.clone();
		}

		private void clear() {
			Arrays.fill(this.sealerEpoch, (byte) 0);
			Arrays.fill(this.derivedKey, (byte) 0);
		}
	}

	private record ParsedEnvelope(@NonNull String keyId,
			byte @NonNull [] sealerEpoch, byte @NonNull [] nonce,
			byte @NonNull [] header,
			byte @NonNull [] ciphertextAndTag) {
		private void clear() {
			Arrays.fill(this.sealerEpoch, (byte) 0);
			Arrays.fill(this.nonce, (byte) 0);
			Arrays.fill(this.header, (byte) 0);
			Arrays.fill(this.ciphertextAndTag, (byte) 0);
		}
	}

	/** One canonical entry plus its sorting key. */
	private record FingerprintRecord(byte @NonNull [] metadata,
			byte @NonNull [] encoded) {
	}
}
