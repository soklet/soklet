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
 * shared production MCP JSON implementation. It is not part of Soklet's
 * supported public API.</p>
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpTaskOriginPersistedStateCodec {
	private static final int MAXIMUM_PERSISTED_BYTES =
			McpJsonLimits.productionDefaults().maximumOutputBytes();

	private McpTaskOriginPersistedStateCodec() {
	}

	/** Returns the canonical durable representation of an origin state. */
	@NonNull
	public static String encode(
			com.soklet.@NonNull McpJsonObject persistedState) {
		McpJsonObject internal = McpPublicJsonValueConverter.toInternalObject(
				requireNonNull(persistedState));
		return new String(McpRequestStateCanonicalJson.canonicalize(internal,
				MAXIMUM_PERSISTED_BYTES), StandardCharsets.UTF_8);
	}

	/** Parses and canonicalizes a bounded durable origin representation. */
	public static com.soklet.@NonNull McpJsonObject decode(
			@NonNull String persistedState) {
		McpJsonLimits limits = McpJsonLimits.productionDefaults();
		McpJsonValue parsed = new McpJsonCodec(limits).parse(
				requireNonNull(persistedState));
		if (!(parsed instanceof McpJsonObject object))
			throw new IllegalArgumentException(
					"Persisted MCP task origin must be a JSON object.");

		byte[] canonical = McpRequestStateCanonicalJson.canonicalize(object,
				MAXIMUM_PERSISTED_BYTES);
		McpJsonObject normalized = (McpJsonObject) new McpJsonCodec(limits)
				.parse(canonical);
		return (com.soklet.McpJsonObject)
				McpPublicJsonValueConverter.toPublic(normalized);
	}
}
