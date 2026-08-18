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
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * An immutable JSON array.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpJsonArray implements McpJsonValue {
	@NonNull
	private final List<@NonNull McpJsonValue> elements;

	/**
	 * Vends a mutable builder.
	 *
	 * @return JSON array builder
	 */
	@NonNull
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Creates an array from the supplied elements.
	 *
	 * @param elements array elements
	 * @return immutable JSON array
	 */
	@NonNull
	public static McpJsonArray fromElements(
			@NonNull Collection<? extends @NonNull McpJsonValue> elements) {
		requireNonNull(elements);
		return new McpJsonArray(elements);
	}

	private McpJsonArray(
			@NonNull Collection<? extends @NonNull McpJsonValue> elements) {
		this.elements = List.copyOf(elements);
	}

	/**
	 * Returns the immutable array elements.
	 *
	 * @return array elements
	 */
	@NonNull
	public List<@NonNull McpJsonValue> getElements() {
		return this.elements;
	}

	/**
	 * Mutable builder for {@link McpJsonArray}.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static final class Builder {
		@NonNull
		private final List<@NonNull McpJsonValue> elements = new ArrayList<>();

		private Builder() {
		}

		/**
		 * Appends a JSON value.
		 *
		 * @param value the value to append
		 * @return this builder
		 */
		@NonNull
		public Builder add(@NonNull McpJsonValue value) {
			this.elements.add(requireNonNull(value));
			return this;
		}

		/**
		 * Appends a JSON string.
		 *
		 * @param value the string value to append
		 * @return this builder
		 */
		@NonNull
		public Builder add(@NonNull String value) {
			return add(McpJsonString.fromValue(value));
		}

		/**
		 * Appends an exactly represented JSON number.
		 *
		 * @param value the number to append
		 * @return this builder
		 */
		@NonNull
		public Builder add(@NonNull BigDecimal value) {
			return add(McpJsonNumber.fromValue(value));
		}

		/**
		 * Appends a JSON boolean.
		 *
		 * @param value the boolean to append
		 * @return this builder
		 * @throws NullPointerException if {@code value} is null
		 */
		@NonNull
		public Builder add(@NonNull Boolean value) {
			return add(McpJsonBoolean.fromValue(requireNonNull(value)));
		}

		/**
		 * Appends the JSON {@code null} value.
		 *
		 * @return this builder
		 */
		@NonNull
		public Builder addNull() {
			return add(McpJsonNull.INSTANCE);
		}

		/**
		 * Builds an immutable JSON array in append order.
		 *
		 * @return the JSON array
		 */
		@NonNull
		public McpJsonArray build() {
			return McpJsonArray.fromElements(this.elements);
		}
	}
}
