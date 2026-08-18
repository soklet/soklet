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

import static java.util.Objects.requireNonNull;

/**
 * An immutable JSON string.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpJsonString implements McpJsonValue {
	@NonNull
	private final String value;

	/**
	 * Creates an immutable JSON string from its value.
	 *
	 * @param value the non-null string value
	 * @return immutable JSON string
	 */
	@NonNull
	public static McpJsonString fromValue(@NonNull String value) {
		return new McpJsonString(value);
	}

	private McpJsonString(@NonNull String value) {
		this.value = requireNonNull(value);
	}

	/** @return string value */
	@NonNull
	public String getValue() {
		return this.value;
	}

	/** @return whether this object contains the same string value */
	@Override
	public boolean equals(@Nullable Object other) {
		if (this == other)
			return true;
		if (!(other instanceof McpJsonString string))
			return false;
		return this.value.equals(string.value);
	}

	/** @return value-based hash code */
	@Override
	public int hashCode() {
		return this.value.hashCode();
	}

	/** @return redacted diagnostic rendering */
	@Override
	@NonNull
	public String toString() {
		return "McpJsonString{value=<redacted>}";
	}
}
