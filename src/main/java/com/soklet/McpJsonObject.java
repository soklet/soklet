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

import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * An immutable JSON object.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpJsonObject implements McpJsonValue {
	@NonNull
	private static final McpJsonObject EMPTY = new McpJsonObject(Map.of());
	@NonNull
	private final Map<@NonNull String, @NonNull McpJsonValue> members;

	/**
	 * Returns the shared empty object.
	 *
	 * @return empty JSON object
	 */
	@NonNull
	public static McpJsonObject emptyInstance() {
		return EMPTY;
	}

	/**
	 * Vends a mutable builder.
	 *
	 * @return JSON object builder
	 */
	@NonNull
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Creates an object from the supplied members.
	 *
	 * @param members object members
	 * @return immutable JSON object
	 */
	@NonNull
	public static McpJsonObject fromMembers(
			@NonNull Map<@NonNull String, ? extends @NonNull McpJsonValue> members) {
		requireNonNull(members);
		if (members.isEmpty())
			return emptyInstance();
		return new McpJsonObject(members);
	}

	private McpJsonObject(
			@NonNull Map<@NonNull String, ? extends @NonNull McpJsonValue> members) {
		LinkedHashMap<@NonNull String, @NonNull McpJsonValue> copied =
				new LinkedHashMap<>();
		members.forEach((name, value) -> copied.put(requireNonNull(name), requireNonNull(value)));
		this.members = Collections.unmodifiableMap(copied);
	}

	/**
	 * Returns the immutable members in their original insertion order.
	 *
	 * @return object members in insertion order
	 */
	@NonNull
	public Map<@NonNull String, @NonNull McpJsonValue> getMembers() {
		return this.members;
	}

	/**
	 * Looks up a member by name.
	 *
	 * @param name member name
	 * @return member value, if present
	 */
	@NonNull
	public Optional<@NonNull McpJsonValue> find(@NonNull String name) {
		return Optional.ofNullable(this.members.get(requireNonNull(name)));
	}

	/**
	 * Mutable builder for {@link McpJsonObject}.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static final class Builder {
		@NonNull
		private final Map<@NonNull String, @NonNull McpJsonValue> members =
				new LinkedHashMap<>();

		private Builder() {
		}

		/**
		 * Associates a member name with a JSON value.
		 *
		 * @param name  the member name
		 * @param value the member value
		 * @return this builder
		 */
		@NonNull
		public Builder put(@NonNull String name, @NonNull McpJsonValue value) {
			this.members.put(requireNonNull(name), requireNonNull(value));
			return this;
		}

		/**
		 * Associates a member name with a JSON string.
		 *
		 * @param name  the member name
		 * @param value the string value
		 * @return this builder
		 */
		@NonNull
		public Builder put(@NonNull String name, @NonNull String value) {
			return put(name, new McpJsonString(value));
		}

		/**
		 * Associates a member name with an exactly represented JSON number.
		 *
		 * @param name  the member name
		 * @param value the number value
		 * @return this builder
		 */
		@NonNull
		public Builder put(@NonNull String name, @NonNull BigDecimal value) {
			return put(name, new McpJsonNumber(value));
		}

		/**
		 * Associates a member name with an integral JSON number.
		 *
		 * @param name  the member name
		 * @param value the integer value
		 * @return this builder
		 */
		@NonNull
		public Builder put(@NonNull String name, int value) {
			return put(name, BigDecimal.valueOf(value));
		}

		/**
		 * Associates a member name with an integral JSON number.
		 *
		 * @param name  the member name
		 * @param value the long value
		 * @return this builder
		 */
		@NonNull
		public Builder put(@NonNull String name, long value) {
			return put(name, BigDecimal.valueOf(value));
		}

		/**
		 * Associates a member name with a finite JSON number.
		 *
		 * @param name  the member name
		 * @param value the finite double value
		 * @return this builder
		 * @throws IllegalArgumentException if {@code value} is not finite
		 */
		@NonNull
		public Builder put(@NonNull String name, double value) {
			if (!Double.isFinite(value))
				throw new IllegalArgumentException("JSON numbers must be finite.");
			return put(name, BigDecimal.valueOf(value));
		}

		/**
		 * Associates a member name with a JSON boolean.
		 *
		 * @param name  the member name
		 * @param value the boolean value
		 * @return this builder
		 */
		@NonNull
		public Builder put(@NonNull String name, boolean value) {
			return put(name, new McpJsonBoolean(value));
		}

		/**
		 * Associates a member name with the JSON {@code null} value.
		 *
		 * @param name the member name
		 * @return this builder
		 */
		@NonNull
		public Builder putNull(@NonNull String name) {
			return put(name, McpJsonNull.INSTANCE);
		}

		/**
		 * Builds an immutable JSON object.
		 *
		 * @return the JSON object
		 */
		@NonNull
		public McpJsonObject build() {
			return McpJsonObject.fromMembers(this.members);
		}
	}
}
