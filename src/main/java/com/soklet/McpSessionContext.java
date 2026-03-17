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

import javax.annotation.concurrent.ThreadSafe;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static java.util.Collections.unmodifiableMap;
import static java.util.Objects.requireNonNull;

/**
 * Immutable key/value bag associated with an MCP session.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public interface McpSessionContext {
	/**
	 * Retrieves a stored session value without type conversion.
	 *
	 * @param key the session key
	 * @return the stored value, if present
	 */
	@NonNull
	Optional<Object> get(@NonNull String key);

	/**
	 * Retrieves a stored session value with an assignability check.
	 *
	 * @param key the session key
	 * @param type the desired type
	 * @param <T> the desired type
	 * @return the stored value cast to {@code type}, if present
	 * @throws IllegalArgumentException if the stored value exists but is not assignable to {@code type}
	 */
	@NonNull
	<T> Optional<T> get(@NonNull String key,
											@NonNull Class<T> type);

	/**
	 * Checks whether a session value is present for the given key.
	 *
	 * @param key the session key
	 * @return {@code true} if the key is present
	 */
	@NonNull
	Boolean contains(@NonNull String key);

	/**
	 * Returns a new session context containing the given key/value pair.
	 *
	 * @param key the session key
	 * @param value the session value
	 * @return a new session context including the provided value
	 */
	@NonNull
	McpSessionContext with(@NonNull String key,
												 @NonNull Object value);

	/**
	 * Returns a new session context without the given key.
	 *
	 * @param key the session key to remove
	 * @return a new session context without the key, or this instance if the key was absent
	 */
	@NonNull
	McpSessionContext without(@NonNull String key);

	/**
	 * Provides an immutable snapshot of all stored session values.
	 *
	 * @return an immutable map view of the stored session values
	 */
	@NonNull
	Map<@NonNull String, @NonNull Object> asMap();

	/**
	 * Creates an empty session context.
	 *
	 * @return a blank session context
	 */
	@NonNull
	static McpSessionContext fromBlankSlate() {
		return new DefaultMcpSessionContext(Map.of());
	}

	/**
	 * Creates a session context from the provided values.
	 *
	 * @param values the values to seed into the new session context
	 * @return a new immutable session context containing {@code values}
	 */
	@NonNull
	static McpSessionContext fromValues(@NonNull Map<@NonNull String, @NonNull Object> values) {
		requireNonNull(values);
		return new DefaultMcpSessionContext(values);
	}
}

final class DefaultMcpSessionContext implements McpSessionContext {
	@NonNull
	private final Map<@NonNull String, @NonNull Object> values;

	DefaultMcpSessionContext(@NonNull Map<@NonNull String, @NonNull Object> values) {
		requireNonNull(values);
		this.values = unmodifiableMap(new LinkedHashMap<>(values));
	}

	@NonNull
	@Override
	public Optional<Object> get(@NonNull String key) {
		requireNonNull(key);
		return Optional.ofNullable(this.values.get(key));
	}

	@NonNull
	@Override
	public <T> Optional<T> get(@NonNull String key,
														 @NonNull Class<T> type) {
		requireNonNull(key);
		requireNonNull(type);

		Object value = this.values.get(key);

		if (value == null)
			return Optional.empty();

		if (!type.isInstance(value))
			throw new IllegalArgumentException("Session value for key '%s' is not assignable to %s".formatted(key, type.getName()));

		return Optional.of(type.cast(value));
	}

	@NonNull
	@Override
	public Boolean contains(@NonNull String key) {
		requireNonNull(key);
		return this.values.containsKey(key);
	}

	@NonNull
	@Override
	public McpSessionContext with(@NonNull String key,
																@NonNull Object value) {
		requireNonNull(key);
		requireNonNull(value);

		Map<String, Object> updatedValues = new LinkedHashMap<>(this.values);
		updatedValues.put(key, value);
		return new DefaultMcpSessionContext(updatedValues);
	}

	@NonNull
	@Override
	public McpSessionContext without(@NonNull String key) {
		requireNonNull(key);

		if (!this.values.containsKey(key))
			return this;

		Map<String, Object> updatedValues = new LinkedHashMap<>(this.values);
		updatedValues.remove(key);
		return new DefaultMcpSessionContext(updatedValues);
	}

	@NonNull
	@Override
	public Map<@NonNull String, @NonNull Object> asMap() {
		return this.values;
	}
}
