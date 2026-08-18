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
 * Immutable application-defined JSON state whose wire representation is
 * protected by Soklet.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpFrameworkRequestState implements McpRequestState {
	@NonNull
	private final McpJsonValue value;

	/**
	 * Creates framework-protected request state from its JSON value.
	 *
	 * @param value decrypted application-defined JSON value
	 * @return immutable framework-protected request state
	 */
	@NonNull
	public static McpFrameworkRequestState fromValue(@NonNull McpJsonValue value) {
		return new McpFrameworkRequestState(value);
	}

	private McpFrameworkRequestState(@NonNull McpJsonValue value) {
		this.value = requireNonNull(value);
	}

	/** @return decrypted application-defined JSON value */
	@NonNull
	public McpJsonValue getValue() {
		return this.value;
	}

	/** @return whether this object contains the same JSON value */
	@Override
	public boolean equals(@Nullable Object other) {
		if (this == other)
			return true;
		if (!(other instanceof McpFrameworkRequestState state))
			return false;
		return this.value.equals(state.value);
	}

	/** @return value-based hash code */
	@Override
	public int hashCode() {
		return this.value.hashCode();
	}

	/** @return rendering that does not expose decrypted request state */
	@Override
	@NonNull
	public String toString() {
		return "McpFrameworkRequestState{value=<redacted>}";
	}
}
