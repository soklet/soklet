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

package com.soklet.internal.mcp.protocol;

import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.ThreadSafe;
import java.nio.charset.StandardCharsets;

import static java.util.Objects.requireNonNull;

/**
 * Bounded canonical JSON codec for the public durable task-origin boundary.
 *
 * <p>This type is public only so {@code com.soklet.McpTaskOrigin} can use the
 * shared bounded MCP JSON implementation. Its internal profile has headroom
 * for a maximum accepted public request plus retained framework state, but is
 * not a transport acceptance profile. This type is not part of Soklet's
 * supported public API.</p>
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpTaskOriginPersistedStateCodec {
	@NonNull
	private static final McpJsonLimits LIMITS =
			McpJsonLimits.durableTaskOrigin();
	@NonNull
	private static final McpJsonLimits RESTORED_ARGUMENT_LIMITS =
			McpJsonLimits.durableTaskArguments();
	// Read reflectively by the finite-bound inventory test.
	private static final int MAXIMUM_PERSISTED_BYTES =
			LIMITS.maximumOutputBytes();

	private McpTaskOriginPersistedStateCodec() {
	}

	/** Returns the canonical durable representation of an origin state. */
	@NonNull
	public static String encode(
			com.soklet.@NonNull McpJsonObject persistedState) {
		McpJsonObject internal = McpPublicJsonValueConverter
				.toInternalObjectForCanonicalization(
						requireNonNull(persistedState), LIMITS);
		return new String(McpRequestStateCanonicalJson.canonicalize(internal,
				LIMITS), StandardCharsets.UTF_8);
	}

	/**
	 * Requires restored raw arguments to retain the structural and scalar limits
	 * applied at transport admission while allowing the durable codec's byte
	 * headroom for canonical rendering.
	 */
	public static void requireRestorableRawArguments(
			com.soklet.@NonNull McpJsonObject rawArguments) {
		McpPublicJsonValueConverter.toInternalObject(
				requireNonNull(rawArguments), RESTORED_ARGUMENT_LIMITS);
	}

	/** Parses and canonicalizes a bounded durable origin representation. */
	@NonNull
	public static DecodedState decode(
			@NonNull String persistedState) {
		McpJsonValue parsed = new McpJsonCodec(LIMITS).parse(
				requireNonNull(persistedState));
		if (!(parsed instanceof McpJsonObject object))
			throw new IllegalArgumentException(
					"Persisted MCP task origin must be a JSON object.");

		McpRequestStateCanonicalJson.Canonicalization canonicalization =
				McpRequestStateCanonicalJson.canonicalizeWithNormalizedValue(
						object, LIMITS);
		McpJsonObject normalized = (McpJsonObject)
				canonicalization.normalizedValue();
		return new DecodedState((com.soklet.McpJsonObject)
				McpPublicJsonValueConverter.toPublic(normalized),
				new String(canonicalization.canonicalUtf8(),
						StandardCharsets.UTF_8));
	}

	/** Canonical state and text produced by one bounded decode pass. */
	public record DecodedState(
			com.soklet.@NonNull McpJsonObject persistedState,
			@NonNull String persistedString) {
		public DecodedState {
			requireNonNull(persistedState);
			requireNonNull(persistedString);
		}
	}
}
