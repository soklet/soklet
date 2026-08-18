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
 * Immutable opaque request state whose protection contract is entirely
 * application-owned.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpApplicationRequestState implements McpRequestState {
	@NonNull
	private final String value;

	/**
	 * Creates application-protected request state from its opaque value.
	 *
	 * @param value nonempty opaque state value
	 * @return immutable application-protected request state
	 * @throws IllegalArgumentException if {@code value} is empty
	 */
	@NonNull
	public static McpApplicationRequestState fromValue(@NonNull String value) {
		return new McpApplicationRequestState(value);
	}

	private McpApplicationRequestState(@NonNull String value) {
		this.value = requireNonNull(value);
		if (value.isEmpty())
			throw new IllegalArgumentException(
					"Application request state must not be empty.");
	}

	/** @return nonempty opaque state value */
	@NonNull
	public String getValue() {
		return this.value;
	}

	/** @return whether this object contains the same opaque state value */
	@Override
	public boolean equals(@Nullable Object other) {
		if (this == other)
			return true;
		if (!(other instanceof McpApplicationRequestState state))
			return false;
		return this.value.equals(state.value);
	}

	/** @return value-based hash code */
	@Override
	public int hashCode() {
		return this.value.hashCode();
	}

	/** @return rendering that does not expose opaque request state */
	@Override
	@NonNull
	public String toString() {
		return "McpApplicationRequestState{value=<redacted>}";
	}
}
