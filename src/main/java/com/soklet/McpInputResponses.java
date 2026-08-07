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

import com.soklet.converter.TypeReference;
import com.soklet.internal.mcp.schema.McpRuntimeTypedSchemaBridge;
import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Immutable input responses supplied with an MCP multi-round-trip retry.
 *
 * <p>Raw lookup preserves the exact MCP JSON value. Typed lookup uses
 * Soklet's closed intrinsic MCP binding and is provided both to live request
 * contexts and to application-created test fixtures.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpInputResponses {
	@NonNull
	private static final McpInputResponses EMPTY =
			new McpInputResponses(Map.of());
	@NonNull
	private final Map<@NonNull String, @NonNull McpJsonValue> responses;

	/**
	 * Returns the shared empty response collection.
	 *
	 * @return empty input responses
	 */
	@NonNull
	public static McpInputResponses emptyInstance() {
		return EMPTY;
	}

	/**
	 * Creates an application-test fixture from exact JSON responses.
	 *
	 * @param responses response keys and exact JSON values
	 * @return immutable input responses
	 * @throws NullPointerException if the map, a key, or a value is null
	 */
	@NonNull
	public static McpInputResponses fromResponses(
			@NonNull Map<@NonNull String,
					? extends @NonNull McpJsonValue> responses) {
		requireNonNull(responses);
		if (responses.isEmpty())
			return emptyInstance();
		return new McpInputResponses(responses);
	}

	/**
	 * Vends a mutable builder for an application-test fixture.
	 *
	 * @return input-response builder
	 */
	@NonNull
	public static Builder builder() {
		return new Builder();
	}

	private McpInputResponses(
			@NonNull Map<@NonNull String,
					? extends @NonNull McpJsonValue> responses) {
		Map<String, McpJsonValue> copiedResponses =
				new LinkedHashMap<>(responses.size());
		for (Map.Entry<@NonNull String,
				? extends @NonNull McpJsonValue> entry : responses.entrySet())
			copiedResponses.put(requireNonNull(entry.getKey()),
					requireNonNull(entry.getValue()));
		this.responses = Collections.unmodifiableMap(copiedResponses);
	}

	/**
	 * Finds an exact JSON response by its application-assigned key.
	 *
	 * @param key response key
	 * @return exact JSON response, if present
	 * @throws NullPointerException if {@code key} is null
	 */
	@NonNull
	public Optional<@NonNull McpJsonValue> find(@NonNull String key) {
		return Optional.ofNullable(this.responses.get(requireNonNull(key)));
	}

	/**
	 * Finds and converts a response with Soklet's intrinsic MCP binding.
	 *
	 * @param key response key
	 * @param type requested Java type
	 * @param <T> requested Java type
	 * @return converted response, if the key is present
	 * @throws NullPointerException if {@code key} or {@code type} is null
	 * @throws IllegalArgumentException if the type is unsupported or the
	 * response cannot be converted to it
	 */
	@NonNull
	public <T> Optional<@NonNull T> find(@NonNull String key,
			@NonNull Class<T> type) {
		return findConverted(key, requireNonNull(type));
	}

	/**
	 * Finds and converts a response to a generic type with Soklet's intrinsic
	 * MCP binding.
	 *
	 * @param key response key
	 * @param type requested generic Java type
	 * @param <T> requested Java type
	 * @return converted response, if the key is present
	 * @throws NullPointerException if {@code key} or {@code type} is null
	 * @throws IllegalArgumentException if the type is unsupported or the
	 * response cannot be converted to it
	 */
	@NonNull
	public <T> Optional<@NonNull T> find(@NonNull String key,
			@NonNull TypeReference<T> type) {
		return findConverted(key, requireNonNull(type).getType());
	}

	/**
	 * Returns the immutable response map in wire order.
	 *
	 * @return immutable responses
	 */
	@NonNull
	public Map<@NonNull String, @NonNull McpJsonValue> asMap() {
		return this.responses;
	}

	@NonNull
	private <T> Optional<@NonNull T> findConverted(@NonNull String key,
			@NonNull Type type) {
		McpJsonValue response = this.responses.get(requireNonNull(key));
		requireNonNull(type);
		if (response == null)
			return Optional.empty();
		McpRuntimeTypedSchemaBridge<T> bridge =
				McpRuntimeTypedSchemaBridge.compileJsonValue(type);
		return Optional.of(bridge.decode(response));
	}

	/**
	 * Mutable builder for immutable application-test input responses.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static final class Builder {
		@NonNull
		private final Map<@NonNull String, @NonNull McpJsonValue> responses =
				new LinkedHashMap<>();

		private Builder() {
		}

		/**
		 * Associates a response key with an exact JSON value.
		 *
		 * <p>Repeated keys are last-call-wins.
		 *
		 * @param key response key
		 * @param response exact JSON response
		 * @return this builder
		 */
		@NonNull
		public Builder response(@NonNull String key,
				@NonNull McpJsonValue response) {
			this.responses.put(requireNonNull(key), requireNonNull(response));
			return this;
		}

		/**
		 * Associates all supplied response entries.
		 *
		 * <p>Entries are applied in iteration order and repeated keys are
		 * last-call-wins.
		 *
		 * @param responses response entries
		 * @return this builder
		 * @throws NullPointerException if the map, a key, or a value is null
		 */
		@NonNull
		public Builder responses(
				@NonNull Map<@NonNull String,
						? extends @NonNull McpJsonValue> responses) {
			McpInputResponses copiedResponses =
					McpInputResponses.fromResponses(requireNonNull(responses));
			this.responses.putAll(copiedResponses.responses);
			return this;
		}

		/**
		 * Builds immutable input responses.
		 *
		 * @return immutable input responses
		 */
		@NonNull
		public McpInputResponses build() {
			return McpInputResponses.fromResponses(this.responses);
		}
	}
}
