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
	private final McpTextOwnerType ownerType;
	@NonNull
	private final String subjectId;
	@NonNull
	private final String memberPath;

	/**
	 * Creates a text coordinate from its structured components.
	 *
	 * @param endpointPath normalized endpoint path
	 * @param ownerType MCP text owner type
	 * @param subjectId stable owner identity within the endpoint
	 * @param memberPath deterministic RFC 6901-escaped semantic member path
	 * @return immutable text coordinate
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if a string argument contains malformed
	 * UTF-16
	 */
	@NonNull
	public static McpTextCoordinate fromComponents(
			@NonNull String endpointPath,
			@NonNull McpTextOwnerType ownerType,
			@NonNull String subjectId, @NonNull String memberPath) {
		return new McpTextCoordinate(endpointPath, ownerType, subjectId,
				memberPath);
	}

	McpTextCoordinate(@NonNull String endpointPath,
			@NonNull McpTextOwnerType ownerType,
			@NonNull String subjectId, @NonNull String memberPath) {
		this.endpointPath = requireValidUnicode(endpointPath, "endpointPath");
		this.ownerType = requireNonNull(ownerType);
		this.subjectId = requireValidUnicode(subjectId, "subjectId");
		this.memberPath = requireValidUnicode(memberPath, "memberPath");
	}

	/** @return normalized endpoint path */
	@NonNull
	public String getEndpointPath() {
		return this.endpointPath;
	}

	/** @return MCP text owner type */
	@NonNull
	public McpTextOwnerType getOwnerType() {
		return this.ownerType;
	}

	/** @return stable owner identity within the endpoint */
	@NonNull
	public String getSubjectId() {
		return this.subjectId;
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
	 * endpoint path, fixed lowercase owner-type token, subject ID, and member
	 * path. Each component is preceded by its four-byte unsigned big-endian byte
	 * length. Components are encoded exactly, without normalization or case
	 * folding; malformed UTF-16 is rejected when the coordinate is constructed.
	 *
	 * @return stable versioned opaque translation key
	 */
	@NonNull
	public String toExternalKey() {
		String ownerTypeToken = ownerTypeToken(this.ownerType);
		MessageDigest digest = sha256();
		digest.update(EXTERNAL_KEY_DOMAIN);
		updateComponent(digest, this.endpointPath);
		updateComponent(digest, ownerTypeToken);
		updateComponent(digest, this.subjectId);
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
			@NonNull McpTextOwnerType ownerType) {
		return switch (ownerType) {
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
				&& this.ownerType == coordinate.ownerType
				&& this.subjectId.equals(coordinate.subjectId)
				&& this.memberPath.equals(coordinate.memberPath);
	}

	/** @return value-based hash code */
	@Override
	public int hashCode() {
		return Objects.hash(this.endpointPath, this.ownerType,
				this.subjectId, this.memberPath);
	}

	/** @return coordinate-only rendering that never includes source text */
	@Override
	@NonNull
	public String toString() {
		return "McpTextCoordinate{endpointPath=<redacted>, ownerType="
				+ this.ownerType + ", subjectId=<redacted>, "
				+ "memberPath=<redacted>}";
	}
}
