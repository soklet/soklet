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
			@NonNull Optional<@NonNull McpProtectionKeyringFingerprint>
					protectionKeyringFingerprint,
			@NonNull Optional<@NonNull McpTraceCorrelationFingerprint>
					traceCorrelationFingerprint) {
		SecurityDiagnosticsState {
			requireNonNull(protectionMode);
			requireNonNull(protectionKeyringFingerprint);
			requireNonNull(traceCorrelationFingerprint);
			if (applicationRequestStateProtectorConfigured
					!= (protectionMode == McpProtectionMode.CUSTOM_PROTECTOR))
				throw new IllegalArgumentException(
						"Application request-state protector presence must match custom-protector mode.");
			if (protectionKeyringFingerprint.isPresent()
					!= (protectionMode == McpProtectionMode.PRODUCTION_KEYRING))
				throw new IllegalArgumentException(
						"Production protection mode must have exactly one keyring fingerprint.");
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
	private final int maximumEncodedRequestStateSizeInBytes;
	private final int maximumDecodedRequestStateSizeInBytes;
	@Nullable
	private OwnedSecretKey activeProtectionKey;
	@NonNull
	private final Map<@NonNull String, @NonNull OwnedSecretKey>
			verificationProtectionKeys;
	@NonNull
	private final Map<@NonNull String, @NonNull Long>
			outstandingSealingReservations;
	@Nullable
	private SealerContext activeSealerContext;
	@Nullable
	private OwnedSecretKey activeTraceCorrelationKey;

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
				? McpProtectionMode.NONE
				: protectionConfig.getProtectionMode();
		this.requestStateProtector = protectionConfig == null ? null
				: protectionConfig.getRequestStateProtector().orElse(null);
		this.maximumEncodedRequestStateSizeInBytes = protectionConfig == null ? 0
				: protectionConfig.getMaximumEncodedRequestStateSizeInBytes();
		this.maximumDecodedRequestStateSizeInBytes = protectionConfig == null ? 0
				: protectionConfig.getMaximumDecodedRequestStateSizeInBytes();
		this.verificationProtectionKeys = new LinkedHashMap<>();
		this.outstandingSealingReservations = new LinkedHashMap<>();

		try {
			if (this.protectionMode == McpProtectionMode.PRODUCTION_KEYRING) {
				McpProtectionKeyring keyring = requireNonNull(protectionConfig)
						.getInitialKeyring().orElseThrow(() ->
								new IllegalArgumentException(
										"Production protection requires an initial keyring."));
				this.activeProtectionKey = OwnedSecretKey.fromProtectionKey(
						keyring.initialActiveKey());
				for (McpProtectionKey verificationKey
						: keyring.initialVerificationKeys()) {
					OwnedSecretKey ownedVerificationKey =
							OwnedSecretKey.fromProtectionKey(verificationKey);
					try {
						this.verificationProtectionKeys.put(
								ownedVerificationKey.keyId(), ownedVerificationKey);
					} catch (RuntimeException | Error throwable) {
						ownedVerificationKey.clear();
						throw throwable;
					}
				}
			} else if (this.protectionMode
					== McpProtectionMode.DEVELOPMENT_EPHEMERAL) {
				byte[] keyMaterial = new byte[DEVELOPMENT_KEY_BYTES];
				try {
					this.entropySource.nextBytes(keyMaterial);
					this.activeProtectionKey = OwnedSecretKey.fromBytes(
							DEVELOPMENT_KEY_ID, keyMaterial);
				} catch (RuntimeException exception) {
					throw new IllegalStateException(
							"Development request-state protection could not initialize.");
				} finally {
					Arrays.fill(keyMaterial, (byte) 0);
				}
			}

			if (traceCorrelationKey != null) {
				requireDistinctFromProtectionKeys(traceCorrelationKey);
				this.activeTraceCorrelationKey =
						OwnedSecretKey.fromTraceKey(traceCorrelationKey);
			}
		} catch (RuntimeException | Error throwable) {
			clearOwnedKeyState();
			throw throwable;
		}
	}

	private void clearOwnedKeyState() {
		if (this.activeProtectionKey != null)
			this.activeProtectionKey.clear();
		this.verificationProtectionKeys.values().forEach(
				OwnedSecretKey::clear);
		this.verificationProtectionKeys.clear();
		if (this.activeTraceCorrelationKey != null)
			this.activeTraceCorrelationKey.clear();
		if (this.activeSealerContext != null)
			this.activeSealerContext.clearAll();
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
			Optional<@NonNull McpProtectionKeyringFingerprint>
					protectionKeyringFingerprint =
					this.protectionMode == McpProtectionMode.PRODUCTION_KEYRING
							? Optional.of(protectionFingerprint(
									requireNonNull(this.activeProtectionKey),
									this.verificationProtectionKeys.values()))
							: Optional.empty();
			Optional<@NonNull McpTraceCorrelationFingerprint>
					traceCorrelationFingerprint =
					Optional.ofNullable(this.activeTraceCorrelationKey)
							.map(DefaultMcpSecurityControls::traceFingerprint);
			return new SecurityDiagnosticsState(this.protectionMode,
					this.requestStateProtector != null,
					protectionKeyringFingerprint,
					traceCorrelationFingerprint);
		}
	}

	@Override
	@NonNull
	public Optional<@NonNull McpProtectionKeyringSnapshot> getKeyringSnapshot() {
		synchronized (this.lock) {
			if (this.protectionMode
					!= McpProtectionMode.PRODUCTION_KEYRING)
				return Optional.empty();
			return Optional.of(new McpProtectionKeyringSnapshot(
					requireNonNull(this.activeProtectionKey).keyId(),
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
			OwnedSecretKey existing = protectionKeyWithId(
					verificationKey.getKeyId());
			if (existing != null) {
				if (existing.hasSameMaterial(verificationKey))
					return;
				throw new IllegalArgumentException(
						"Duplicate MCP protection key ID with different material.");
			}
			requireUniqueProtectionMaterial(verificationKey);
			if (this.activeTraceCorrelationKey != null
					&& this.activeTraceCorrelationKey.hasSameMaterial(
							verificationKey))
				throw new IllegalArgumentException(
						"Protection and trace-correlation keys must use distinct material.");
			OwnedSecretKey copy =
					OwnedSecretKey.fromProtectionKey(verificationKey);
			try {
				this.verificationProtectionKeys.put(copy.keyId(), copy);
			} catch (RuntimeException | Error throwable) {
				this.verificationProtectionKeys.remove(copy.keyId());
				copy.clear();
				throw throwable;
			}
		}
	}

	@Override
	public void activateStagedKey(@NonNull String keyId) {
		requireNonNull(keyId);
		synchronized (this.lock) {
			requireProductionRing();
			if (requireNonNull(this.activeProtectionKey).keyId()
					.equals(keyId))
				return;
			OwnedSecretKey staged = this.verificationProtectionKeys.get(keyId);
			if (staged == null)
				throw new IllegalArgumentException(
						"Unknown staged MCP protection key ID.");
			SealerContext sealerContext;
			try {
				sealerContext = newSealerContext(staged, 0L);
			} catch (RuntimeException exception) {
				throw new IllegalStateException(
						"MCP protection key activation could not initialize.");
			}
			OwnedSecretKey formerActive = this.activeProtectionKey;
			try {
				this.verificationProtectionKeys.put(formerActive.keyId(),
						formerActive);
				this.verificationProtectionKeys.remove(keyId);
				this.activeProtectionKey = staged;
				replaceActiveSealerContext(sealerContext);
			} catch (RuntimeException | Error throwable) {
				this.verificationProtectionKeys.remove(formerActive.keyId());
				sealerContext.clearAll();
				throw throwable;
			}
		}
	}

	@Override
	public void rotateActiveKey(@NonNull McpProtectionKey activeKey) {
		requireNonNull(activeKey);
		synchronized (this.lock) {
			requireProductionRing();
			OwnedSecretKey current = requireNonNull(this.activeProtectionKey);
			if (current.keyId().equals(activeKey.getKeyId())) {
				if (current.hasSameMaterial(activeKey))
					return;
				throw new IllegalArgumentException(
						"Duplicate MCP protection key ID with different material.");
			}
			OwnedSecretKey staged = this.verificationProtectionKeys.get(
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
					&& this.activeTraceCorrelationKey.hasSameMaterial(activeKey))
				throw new IllegalArgumentException(
						"Protection and trace-correlation keys must use distinct material.");
			OwnedSecretKey copy = OwnedSecretKey.fromProtectionKey(activeKey);
			SealerContext sealerContext;
			try {
				sealerContext = newSealerContext(copy, 0L);
			} catch (RuntimeException exception) {
				copy.clear();
				throw new IllegalStateException(
						"MCP protection key rotation could not initialize.");
			} catch (Error error) {
				copy.clear();
				throw error;
			}
			try {
				this.verificationProtectionKeys.put(current.keyId(), current);
				this.activeProtectionKey = copy;
				replaceActiveSealerContext(sealerContext);
			} catch (RuntimeException | Error throwable) {
				this.verificationProtectionKeys.remove(current.keyId());
				copy.clear();
				sealerContext.clearAll();
				throw throwable;
			}
		}
	}

	@Override
	@NonNull
	public Boolean removeVerificationKey(@NonNull String keyId) {
		requireNonNull(keyId);
		synchronized (this.lock) {
			requireProductionRing();
			if (requireNonNull(this.activeProtectionKey).keyId()
					.equals(keyId))
				throw new IllegalArgumentException(
						"The active MCP protection key cannot be removed.");
			if (this.outstandingSealingReservations.getOrDefault(keyId, 0L)
					> 0L)
				throw new McpProtectionKeyInUseException();
			OwnedSecretKey removed =
					this.verificationProtectionKeys.remove(keyId);
			if (removed == null)
				return false;
			removed.clear();
			return true;
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
				|| plaintext.length > this.maximumDecodedRequestStateSizeInBytes)
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
			byte[] header = null;
			byte[] binding = null;
			byte[] associatedData = null;
			byte[] ciphertext = null;
			byte[] envelopeBytes = null;
			try {
				try {
					this.entropySource.nextBytes(nonce);
				} catch (RuntimeException exception) {
					throw McpRequestStateProtectionException
							.fromProtectorUnavailable();
				}
				header = builtInHeader(reservation.keyId(),
						reservation.sealerEpoch(), nonce);
				binding = context.getAssociatedData();
				associatedData = builtInAssociatedData(header, binding);
				try {
					ciphertext = encrypt(reservation.derivedKey(), nonce,
							associatedData, plaintext);
				} catch (GeneralSecurityException exception) {
					throw McpRequestStateProtectionException
							.fromProtectorUnavailable();
				}
				envelopeBytes = new byte[header.length + ciphertext.length];
				System.arraycopy(header, 0, envelopeBytes, 0, header.length);
				System.arraycopy(ciphertext, 0, envelopeBytes, header.length,
						ciphertext.length);
				if (envelopeBytes.length
						> this.maximumDecodedRequestStateSizeInBytes)
					throw new IllegalStateException(
							"Protected MCP request state exceeds its configured decoded-size limit.");
				String protectedState = REQUEST_STATE_PREFIX
						+ base64Url(envelopeBytes);
				if (protectedState.length()
						> this.maximumEncodedRequestStateSizeInBytes)
					throw new IllegalStateException(
							"Protected MCP request state exceeds its configured encoded-size limit.");
				return protectedState;
			} finally {
				Arrays.fill(nonce, (byte) 0);
				if (header != null)
					Arrays.fill(header, (byte) 0);
				if (binding != null)
					Arrays.fill(binding, (byte) 0);
				if (associatedData != null)
					Arrays.fill(associatedData, (byte) 0);
				if (ciphertext != null)
					Arrays.fill(ciphertext, (byte) 0);
				if (envelopeBytes != null)
					Arrays.fill(envelopeBytes, (byte) 0);
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
					|| plaintext.length > this.maximumDecodedRequestStateSizeInBytes)
				throw McpRequestStateProtectionException.fromInvalidState();
			return plaintext;
		}

		ParsedEnvelope envelope = parseBuiltInEnvelope(protectedState);
		byte[] masterKey = null;
		byte[] derivedKey = null;
		byte[] binding = null;
		byte[] associatedData = null;
		try {
			masterKey = protectionKeyMaterialSnapshot(envelope.keyId());
			if (masterKey == null)
				throw McpRequestStateProtectionException.fromInvalidState();
			derivedKey = deriveEpochKey(masterKey, envelope.sealerEpoch());
			binding = context.getAssociatedData();
			associatedData = builtInAssociatedData(envelope.header(), binding);
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
					|| plaintext.length > this.maximumDecodedRequestStateSizeInBytes) {
				Arrays.fill(plaintext, (byte) 0);
				throw McpRequestStateProtectionException.fromInvalidState();
			}
			return plaintext;
		} finally {
			if (masterKey != null)
				Arrays.fill(masterKey, (byte) 0);
			if (derivedKey != null)
				Arrays.fill(derivedKey, (byte) 0);
			if (binding != null)
				Arrays.fill(binding, (byte) 0);
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
					.map(OwnedSecretKey::keyId);
		}
	}

	@Override
	@NonNull
	public Optional<@NonNull McpTraceCorrelationFingerprint>
			getFingerprint() {
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
			if (this.activeTraceCorrelationKey.keyId().equals(
					activeKey.getKeyId())) {
				if (this.activeTraceCorrelationKey.hasSameMaterial(activeKey))
					return;
				throw new IllegalArgumentException(
						"Duplicate MCP trace-correlation key ID with different material.");
			}
			OwnedSecretKey replacement =
					OwnedSecretKey.fromTraceKey(activeKey);
			OwnedSecretKey retired = this.activeTraceCorrelationKey;
			this.activeTraceCorrelationKey = replacement;
			retired.clear();
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
			OwnedSecretKey activeKey = this.activeTraceCorrelationKey;
			if (activeKey == null)
				return Optional.empty();
			keyId = activeKey.keyId();
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
		if (this.protectionMode == McpProtectionMode.NONE)
			throw new IllegalStateException(
					"Framework-managed MCP request-state protection is not configured.");
	}

	private boolean isBuiltInProtection() {
		return this.protectionMode == McpProtectionMode.PRODUCTION_KEYRING
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
				|| encodedBytes > this.maximumEncodedRequestStateSizeInBytes)
			throw McpRequestStateProtectionException.fromInvalidState();
	}

	@NonNull
	private SealReservation reserveSeal(int plaintextBytes)
			throws McpRequestStateProtectionException {
		synchronized (this.lock) {
			try {
				OwnedSecretKey activeKey = requireNonNull(
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
					SealerContext retired = context;
					context = nextSealerEpoch(retired);
					this.activeSealerContext = context;
					retired.clearDerivedKey();
				}

				int decodedBytes = builtInHeaderLength(context.keyId())
						+ plaintextBytes + GCM_TAG_BYTES;
				if (decodedBytes > this.maximumDecodedRequestStateSizeInBytes
						|| encodedWireLength(decodedBytes)
						> this.maximumEncodedRequestStateSizeInBytes)
					throw new IllegalStateException(
							"Protected MCP request state exceeds its configured size limit.");

				this.activeSealerContext = context.withInvocationCount(
						context.invocationCount() + 1L);
				byte[] sealerEpoch = context.sealerEpoch();
				SealReservation reservation = null;
				try {
					reservation = new SealReservation(context.keyId(),
							sealerEpoch, context.derivedKey());
					this.outstandingSealingReservations.merge(context.keyId(),
							1L, Long::sum);
					SealReservation published = reservation;
					reservation = null;
					return published;
				} finally {
					if (reservation != null)
						reservation.clear();
					Arrays.fill(sealerEpoch, (byte) 0);
				}
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
		try {
			synchronized (this.lock) {
				long remaining = this.outstandingSealingReservations.getOrDefault(
						reservation.keyId(), 0L) - 1L;
				if (remaining <= 0L)
					this.outstandingSealingReservations.remove(
							reservation.keyId());
				else
					this.outstandingSealingReservations.put(reservation.keyId(),
							remaining);
			}
		} finally {
			reservation.clear();
		}
	}

	private void replaceActiveSealerContext(
			@NonNull SealerContext replacement) {
		SealerContext retired = this.activeSealerContext;
		this.activeSealerContext = requireNonNull(replacement);
		if (retired != null)
			retired.clearAll();
	}

	@NonNull
	private SealerContext newSealerContext(@NonNull OwnedSecretKey key,
			long epochNumber) {
		byte[] activationPrefix = new byte[ACTIVATION_PREFIX_BYTES];
		byte[] masterKey = key.copyKeyMaterial();
		byte[] prk = null;
		byte[] sealerEpoch = null;
		byte[] derivedKey = null;
		try {
			this.entropySource.nextBytes(activationPrefix);
			prk = hkdfExtract(masterKey);
			sealerEpoch = sealerEpoch(activationPrefix, epochNumber);
			derivedKey = hkdfExpand(prk, sealerEpoch);
			SealerContext context = new SealerContext(key.keyId(), prk,
					activationPrefix, epochNumber, 0L, derivedKey);
			prk = null;
			activationPrefix = null;
			derivedKey = null;
			return context;
		} catch (RuntimeException exception) {
			throw new SealerUnavailableException();
		} finally {
			Arrays.fill(masterKey, (byte) 0);
			if (prk != null)
				Arrays.fill(prk, (byte) 0);
			if (sealerEpoch != null)
				Arrays.fill(sealerEpoch, (byte) 0);
			if (derivedKey != null)
				Arrays.fill(derivedKey, (byte) 0);
			if (activationPrefix != null)
				Arrays.fill(activationPrefix, (byte) 0);
		}
	}

	@NonNull
	private static SealerContext nextSealerEpoch(
			@NonNull SealerContext context) {
		byte[] sealerEpoch = null;
		byte[] derivedKey = null;
		try {
			long nextEpochNumber = context.epochNumber() + 1L;
			sealerEpoch = sealerEpoch(context.activationPrefix(),
					nextEpochNumber);
			derivedKey = hkdfExpand(context.prk(), sealerEpoch);
			SealerContext next = new SealerContext(context.keyId(), context.prk(),
					context.activationPrefix(), nextEpochNumber, 0L, derivedKey);
			derivedKey = null;
			return next;
		} catch (RuntimeException exception) {
			throw new SealerUnavailableException();
		} finally {
			if (sealerEpoch != null)
				Arrays.fill(sealerEpoch, (byte) 0);
			if (derivedKey != null)
				Arrays.fill(derivedKey, (byte) 0);
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
		try {
			if (!base64Url(decoded).equals(suffix)
					|| decoded.length > this.maximumDecodedRequestStateSizeInBytes)
				throw McpRequestStateProtectionException.fromInvalidState();
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
	private byte @Nullable [] protectionKeyMaterialSnapshot(
			@NonNull String keyId) {
		synchronized (this.lock) {
			OwnedSecretKey key = this.activeProtectionKey != null
					&& this.activeProtectionKey.keyId().equals(keyId)
					? this.activeProtectionKey
					: this.verificationProtectionKeys.get(keyId);
			return key == null ? null : key.copyKeyMaterial();
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
		byte[] infoBytes = info.toByteArray();
		try {
			byte[] derived = hmacSha256(prk, infoBytes);
			if (derived.length != DERIVED_KEY_BYTES) {
				Arrays.fill(derived, (byte) 0);
				throw new IllegalStateException(
						"HKDF-SHA-256 returned an unexpected key length.");
			}
			return derived;
		} finally {
			Arrays.fill(infoBytes, (byte) 0);
		}
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
		if (this.protectionMode != McpProtectionMode.PRODUCTION_KEYRING)
			throw new IllegalStateException(
					"MCP protection key control requires a production keyring.");
	}

	@Nullable
	private OwnedSecretKey protectionKeyWithId(@NonNull String keyId) {
		if (requireNonNull(this.activeProtectionKey).keyId().equals(keyId))
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
	private static McpProtectionKeyringFingerprint protectionFingerprint(
			@NonNull OwnedSecretKey activeKey,
			@NonNull Iterable<@NonNull OwnedSecretKey> verificationKeys) {
		List<FingerprintRecord> records = new ArrayList<>();
		records.add(fingerprintRecord(activeKey.keyId(),
				McpProtectionKeyringFingerprint.PROFILE, ACTIVE_ROLE,
				activeKey.copyKeyMaterial(), PROTECTION_ENTRY_DOMAIN));
		for (OwnedSecretKey verificationKey : verificationKeys)
			records.add(fingerprintRecord(verificationKey.keyId(),
					McpProtectionKeyringFingerprint.PROFILE, VERIFICATION_ROLE,
					verificationKey.copyKeyMaterial(), PROTECTION_ENTRY_DOMAIN));
		records.sort(Comparator.comparing(FingerprintRecord::metadata,
				DefaultMcpSecurityControls::compareUnsigned));
		ByteArrayOutputStream aggregate = new ByteArrayOutputStream();
		aggregate.writeBytes(PROTECTION_RING_DOMAIN);
		writeUnsignedInt(aggregate, records.size());
		records.forEach(record -> aggregate.writeBytes(record.encoded()));
		return new McpProtectionKeyringFingerprint(base64Url(sha256(
				aggregate.toByteArray())));
	}

	@NonNull
	private static McpTraceCorrelationFingerprint traceFingerprint(
			@NonNull OwnedSecretKey key) {
		FingerprintRecord record = fingerprintRecord(key.keyId(), TRACE_ALGORITHM,
				ACTIVE_ROLE, key.copyKeyMaterial(), TRACE_ENTRY_DOMAIN);
		ByteArrayOutputStream aggregate = new ByteArrayOutputStream();
		aggregate.writeBytes(TRACE_CONFIGURATION_DOMAIN);
		writeUnsignedInt(aggregate, 1);
		aggregate.writeBytes(record.encoded());
		return McpTraceCorrelationFingerprint.fromValue(
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

	private static final class OwnedSecretKey {
		private static final int MINIMUM_KEY_BYTES = 32;
		@NonNull
		private final String keyId;
		private final byte @NonNull [] keyMaterial;

		@NonNull
		private static OwnedSecretKey fromProtectionKey(
				@NonNull McpProtectionKey key) {
			requireNonNull(key);
			return fromOwnedBytes(key.getKeyId(), key.copyKeyMaterial());
		}

		@NonNull
		private static OwnedSecretKey fromTraceKey(
				@NonNull McpTraceCorrelationKey key) {
			requireNonNull(key);
			return fromOwnedBytes(key.getKeyId(), key.copyKeyMaterial());
		}

		@NonNull
		private static OwnedSecretKey fromBytes(@NonNull String keyId,
				byte @NonNull [] keyMaterial) {
			return fromOwnedBytes(keyId, requireNonNull(keyMaterial).clone());
		}

		@NonNull
		private static OwnedSecretKey fromOwnedBytes(@NonNull String keyId,
				byte @NonNull [] ownedKeyMaterial) {
			try {
				return new OwnedSecretKey(keyId, ownedKeyMaterial);
			} catch (RuntimeException | Error throwable) {
				Arrays.fill(ownedKeyMaterial, (byte) 0);
				throw throwable;
			}
		}

		private OwnedSecretKey(@NonNull String keyId,
				byte @NonNull [] keyMaterial) {
			this.keyId = McpKeyIdValidator.validate(keyId, "MCP secret key ID");
			this.keyMaterial = requireNonNull(keyMaterial);
			if (keyMaterial.length < MINIMUM_KEY_BYTES)
				throw new IllegalArgumentException(
						"MCP secret keys must contain at least 32 bytes.");
		}

		@NonNull
		private String keyId() {
			return this.keyId;
		}

		private byte @NonNull [] copyKeyMaterial() {
			return this.keyMaterial.clone();
		}

		private boolean hasSameMaterial(@NonNull McpProtectionKey other) {
			byte[] otherMaterial = requireNonNull(other).copyKeyMaterial();
			try {
				return MessageDigest.isEqual(this.keyMaterial, otherMaterial);
			} finally {
				Arrays.fill(otherMaterial, (byte) 0);
			}
		}

		private boolean hasSameMaterial(@NonNull McpTraceCorrelationKey other) {
			byte[] otherMaterial = requireNonNull(other).copyKeyMaterial();
			try {
				return MessageDigest.isEqual(this.keyMaterial, otherMaterial);
			} finally {
				Arrays.fill(otherMaterial, (byte) 0);
			}
		}

		private void clear() {
			Arrays.fill(this.keyMaterial, (byte) 0);
		}
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

		private void clearDerivedKey() {
			Arrays.fill(this.derivedKey, (byte) 0);
		}

		private void clearAll() {
			Arrays.fill(this.prk, (byte) 0);
			Arrays.fill(this.activationPrefix, (byte) 0);
			clearDerivedKey();
		}
	}

	private record SealReservation(@NonNull String keyId,
			byte @NonNull [] sealerEpoch,
			byte @NonNull [] derivedKey) {
		private SealReservation {
			byte[] sealerEpochCopy = sealerEpoch.clone();
			try {
				derivedKey = derivedKey.clone();
			} catch (RuntimeException | Error throwable) {
				Arrays.fill(sealerEpochCopy, (byte) 0);
				throw throwable;
			}
			sealerEpoch = sealerEpochCopy;
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
