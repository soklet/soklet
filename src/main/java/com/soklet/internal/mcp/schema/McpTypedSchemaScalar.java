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

package com.soklet.internal.mcp.schema;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Scalar entries in the closed typed-Java derivation table.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
enum McpTypedSchemaScalar {
	BOOLEAN("boolean"),
	BYTE("integer", BigDecimal.valueOf(Byte.MIN_VALUE),
			BigDecimal.valueOf(Byte.MAX_VALUE)),
	SHORT("integer", BigDecimal.valueOf(Short.MIN_VALUE),
			BigDecimal.valueOf(Short.MAX_VALUE)),
	INT("integer", BigDecimal.valueOf(Integer.MIN_VALUE),
			BigDecimal.valueOf(Integer.MAX_VALUE)),
	LONG("integer", BigDecimal.valueOf(Long.MIN_VALUE),
			BigDecimal.valueOf(Long.MAX_VALUE)),
	BIG_INTEGER("integer"),
	FLOAT("number"),
	DOUBLE("number"),
	BIG_DECIMAL("number"),
	STRING("string");

	@NonNull
	private final String jsonType;
	@NonNull
	private final Optional<@NonNull BigDecimal> minimum;
	@NonNull
	private final Optional<@NonNull BigDecimal> maximum;

	McpTypedSchemaScalar(@NonNull String jsonType) {
		this(jsonType, null, null);
	}

	McpTypedSchemaScalar(@NonNull String jsonType,
			@Nullable BigDecimal minimum,
			@Nullable BigDecimal maximum) {
		this.jsonType = requireNonNull(jsonType);
		this.minimum = Optional.ofNullable(minimum);
		this.maximum = Optional.ofNullable(maximum);
		if (this.minimum.isPresent() != this.maximum.isPresent())
			throw new IllegalArgumentException(
					"Scalar numeric bounds must be both present or both absent.");
	}

	@NonNull
	String jsonType() {
		return jsonType;
	}

	@NonNull
	Optional<@NonNull BigDecimal> minimum() {
		return minimum;
	}

	@NonNull
	Optional<@NonNull BigDecimal> maximum() {
		return maximum;
	}
}
