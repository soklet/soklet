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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Objects;

import static java.util.Objects.requireNonNull;

/**
 * Immutable stable identity for one framework-owned localizable MCP field.
 * <p>
 * Coordinates contain registration identity and a deterministic semantic
 * member path, never source or translated text. {@link #toExternalKey()}
 * returns a versioned representation suitable for an application-owned opaque
 * translation-key strategy.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpTextCoordinate {
	private static final byte @NonNull [] EXTERNAL_KEY_DOMAIN =
			"soklet-mcp-text-coordinate-v1\0"
					.getBytes(StandardCharsets.US_ASCII);
	@NonNull
	private static final String EXTERNAL_KEY_PREFIX = "soklet-mcp-text-v1.";
	@NonNull
	private final String endpointPath;
	@NonNull
	private final McpTextOwnerType mcpTextOwnerType;
	@NonNull
	private final String subjectIdentifier;
	@NonNull
	private final String memberPath;

	McpTextCoordinate(@NonNull String endpointPath,
			@NonNull McpTextOwnerType mcpTextOwnerType,
			@NonNull String subjectIdentifier, @NonNull String memberPath) {
		this.endpointPath = requireValidUnicode(endpointPath, "endpointPath");
		this.mcpTextOwnerType = requireNonNull(mcpTextOwnerType);
		this.subjectIdentifier = requireValidUnicode(subjectIdentifier,
				"subjectIdentifier");
		this.memberPath = requireValidUnicode(memberPath, "memberPath");
	}

	/** @return normalized endpoint path */
	@NonNull
	public String getEndpointPath() {
		return this.endpointPath;
	}

	/** @return MCP text owner type */
	@NonNull
	public McpTextOwnerType getMcpTextOwnerType() {
		return this.mcpTextOwnerType;
	}

	/** @return stable owner identity within the endpoint */
	@NonNull
	public String getSubjectIdentifier() {
		return this.subjectIdentifier;
	}

	/** @return deterministic RFC 6901-escaped semantic member path */
	@NonNull
	public String getMemberPath() {
		return this.memberPath;
	}

	/**
	 * Returns the bounded opaque external key
	 * {@code soklet-mcp-text-v1.<owner-type-token>.<digest>}. The digest is unpadded
	 * Base64URL SHA-256 over the ASCII domain
	 * {@code soklet-mcp-text-coordinate-v1\0}, followed by four UTF-8 components:
	 * endpoint path, fixed lowercase owner-type token, subject identifier, and member
	 * path. Each component is preceded by its four-byte unsigned big-endian byte
	 * length. Components are encoded exactly, without normalization or case
	 * folding; malformed UTF-16 is rejected when the coordinate is constructed.
	 *
	 * @return stable versioned opaque translation key
	 */
	@NonNull
	public String toExternalKey() {
		String ownerTypeToken = ownerTypeToken(this.mcpTextOwnerType);
		MessageDigest digest = sha256();
		digest.update(EXTERNAL_KEY_DOMAIN);
		updateComponent(digest, this.endpointPath);
		updateComponent(digest, ownerTypeToken);
		updateComponent(digest, this.subjectIdentifier);
		updateComponent(digest, this.memberPath);
		String encodedDigest = Base64.getUrlEncoder().withoutPadding()
				.encodeToString(digest.digest());
		return EXTERNAL_KEY_PREFIX + ownerTypeToken + "." + encodedDigest;
	}

	@NonNull
	private static MessageDigest sha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException exception) {
			throw new ExceptionInInitializerError(exception);
		}
	}

	private static void updateComponent(@NonNull MessageDigest digest,
			@NonNull String value) {
		byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
		int length = bytes.length;
		digest.update((byte) (length >>> 24));
		digest.update((byte) (length >>> 16));
		digest.update((byte) (length >>> 8));
		digest.update((byte) length);
		digest.update(bytes);
	}

	@NonNull
	private static String ownerTypeToken(
			@NonNull McpTextOwnerType mcpTextOwnerType) {
		return switch (mcpTextOwnerType) {
			case SERVER_INFORMATION -> "server-information";
			case ENDPOINT -> "endpoint";
			case TOOL -> "tool";
			case PROMPT -> "prompt";
			case RESOURCE -> "resource";
			case RESOURCE_TEMPLATE -> "resource-template";
		};
	}

	@NonNull
	private static String requireValidUnicode(@NonNull String value,
			@NonNull String name) {
		requireNonNull(value, name);
		for (int index = 0; index < value.length(); ++index) {
			char character = value.charAt(index);
			if (Character.isHighSurrogate(character)) {
				if (++index >= value.length()
						|| !Character.isLowSurrogate(value.charAt(index)))
					throw new IllegalArgumentException(
							"MCP text coordinate components must contain valid Unicode.");
			} else if (Character.isLowSurrogate(character)) {
				throw new IllegalArgumentException(
						"MCP text coordinate components must contain valid Unicode.");
			}
		}
		return value;
	}

	/** @return whether every structured coordinate component is equal */
	@Override
	public boolean equals(@Nullable Object other) {
		if (this == other)
			return true;
		if (!(other instanceof McpTextCoordinate coordinate))
			return false;
		return this.endpointPath.equals(coordinate.endpointPath)
				&& this.mcpTextOwnerType == coordinate.mcpTextOwnerType
				&& this.subjectIdentifier.equals(coordinate.subjectIdentifier)
				&& this.memberPath.equals(coordinate.memberPath);
	}

	/** @return value-based hash code */
	@Override
	public int hashCode() {
		return Objects.hash(this.endpointPath, this.mcpTextOwnerType,
				this.subjectIdentifier, this.memberPath);
	}

	/** @return coordinate-only rendering that never includes source text */
	@Override
	@NonNull
	public String toString() {
		return "McpTextCoordinate{endpointPath=<redacted>, mcpTextOwnerType="
				+ this.mcpTextOwnerType + ", subjectIdentifier=<redacted>, "
				+ "memberPath=<redacted>}";
	}
}
