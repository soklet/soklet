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
 * Immutable, non-secret application identity for one localization catalog
 * snapshot.
 * <p>
 * A revision supports deployment comparison and cache/invalidation
 * bookkeeping. It is not an MCP wire value, metric label, log field,
 * historical-catalog request, or distributed-session identifier.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpLocalizationRevision {
	private static final int MAXIMUM_VALUE_BYTES = 128;
	@NonNull
	private final String value;

	/**
	 * Creates a revision from its bounded application value.
	 *
	 * @param value 1-128 bytes of visible ASCII
	 * @return immutable revision
	 * @throws NullPointerException if {@code value} is null
	 * @throws IllegalArgumentException if {@code value} is empty, longer than
	 * 128 bytes, or contains a character outside visible ASCII
	 */
	@NonNull
	public static McpLocalizationRevision fromValue(@NonNull String value) {
		return new McpLocalizationRevision(value);
	}

	private McpLocalizationRevision(@NonNull String value) {
		this.value = requireNonNull(value);
		if (value.isEmpty() || value.length() > MAXIMUM_VALUE_BYTES)
			throw new IllegalArgumentException(
					"MCP localization revisions must contain 1 through 128 visible ASCII bytes.");
		for (int index = 0; index < value.length(); ++index) {
			char character = value.charAt(index);
			if (character < 0x21 || character > 0x7E)
				throw new IllegalArgumentException(
						"MCP localization revisions must contain 1 through 128 visible ASCII bytes.");
		}
	}

	/** @return application-defined revision value */
	@NonNull
	public String getValue() {
		return this.value;
	}

	/** @return whether this value has the same application revision */
	@Override
	public boolean equals(@Nullable Object other) {
		if (this == other)
			return true;
		if (!(other instanceof McpLocalizationRevision revision))
			return false;
		return this.value.equals(revision.value);
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
		return "McpLocalizationRevision{value=<redacted>}";
	}
}
