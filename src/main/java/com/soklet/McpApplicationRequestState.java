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

import static java.util.Objects.requireNonNull;

/**
 * Immutable opaque request state whose protection contract is entirely
 * application-owned.
 *
 * @param value nonempty opaque state value
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public record McpApplicationRequestState(
		@NonNull String value) implements McpRequestState {
	/**
	 * Creates application-protected request state.
	 *
	 * @param value nonempty opaque state value
	 * @throws IllegalArgumentException if {@code value} is empty
	 */
	public McpApplicationRequestState {
		requireNonNull(value);
		if (value.isEmpty())
			throw new IllegalArgumentException(
					"Application request state must not be empty.");
	}

	/** @return rendering that does not expose opaque request state */
	@Override
	@NonNull
	public final String toString() {
		return "McpApplicationRequestState{value=<redacted>}";
	}
}
