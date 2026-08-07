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
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
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
		implements McpProtectionControl, McpTraceCorrelation {
	private static final byte ACTIVE_ROLE = 0x01;
	private static final byte VERIFICATION_ROLE = 0x02;
	@NonNull
	private static final String TRACE_ALGORITHM =
			"soklet-mcp-trace-correlation-v1";
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
	private final McpProtectionMode protectionMode;
	@Nullable
	private McpProtectionKey activeProtectionKey;
	@NonNull
	private final Map<@NonNull String, @NonNull McpProtectionKey>
			verificationProtectionKeys;
	@Nullable
	private McpTraceCorrelationKey activeTraceCorrelationKey;

	DefaultMcpSecurityControls(@Nullable McpProtectionConfig protectionConfig,
			@Nullable McpTraceCorrelationKey traceCorrelationKey) {
		this.lock = new Object();
		this.protectionMode = protectionConfig == null
				? McpProtectionMode.NO_FRAMEWORK_KEYS
				: protectionConfig.getProtectionMode();
		this.verificationProtectionKeys = new LinkedHashMap<>();

		if (this.protectionMode == McpProtectionMode.PRODUCTION_KEY_RING) {
			McpProtectionKeyRing keyRing = requireNonNull(protectionConfig)
					.getInitialKeyRing().orElseThrow(() ->
							new IllegalArgumentException(
									"Production protection requires an initial key ring."));
			this.activeProtectionKey = keyRing.copyInitialActiveKey();
			this.verificationProtectionKeys.putAll(
					keyRing.copyInitialVerificationKeys());
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

	@Override
	@NonNull
	public Optional<@NonNull McpProtectionKeyRingSnapshot> getKeyRingSnapshot() {
		synchronized (this.lock) {
			if (this.activeProtectionKey == null)
				return Optional.empty();
			return Optional.of(new McpProtectionKeyRingSnapshot(
					this.activeProtectionKey.getKeyId(),
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
			McpProtectionKey formerActive = this.activeProtectionKey;
			this.activeProtectionKey = staged;
			this.verificationProtectionKeys.put(formerActive.getKeyId(),
					formerActive);
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
			this.verificationProtectionKeys.put(copy.getKeyId(), copy);
			activateStagedKey(copy.getKeyId());
		}
	}

	@Override
	public boolean removeVerificationKey(@NonNull String keyId) {
		requireNonNull(keyId);
		synchronized (this.lock) {
			requireProductionRing();
			if (requireNonNull(this.activeProtectionKey).getKeyId()
					.equals(keyId))
				throw new IllegalArgumentException(
						"The active MCP protection key cannot be removed.");
			return this.verificationProtectionKeys.remove(keyId) != null;
		}
	}

	@Override
	public boolean isEnabled() {
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

	private void requireProductionRing() {
		if (this.activeProtectionKey == null)
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
		return new McpTraceCorrelationConfigurationFingerprint(
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

	/** One canonical entry plus its sorting key. */
	private record FingerprintRecord(byte @NonNull [] metadata,
			byte @NonNull [] encoded) {
	}
}
