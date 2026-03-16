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
	@NonNull
	Optional<Object> get(@NonNull String key);

	@NonNull
	<T> Optional<T> get(@NonNull String key,
											@NonNull Class<T> type);

	@NonNull
	Boolean contains(@NonNull String key);

	@NonNull
	McpSessionContext with(@NonNull String key,
												 @NonNull Object value);

	@NonNull
	McpSessionContext without(@NonNull String key);

	@NonNull
	Map<@NonNull String, @NonNull Object> asMap();

	@NonNull
	static McpSessionContext fromBlankSlate() {
		return new DefaultMcpSessionContext(Map.of());
	}

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
