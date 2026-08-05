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
import java.math.BigInteger;
import java.util.Objects;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * An immutable MCP JSON-RPC request identifier.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpRequestId {
	@Nullable
	private final String stringValue;
	@Nullable
	private final BigInteger integerValue;

	/**
	 * Creates a request identifier from its string wire value.
	 *
	 * @param value the string identifier
	 * @return the request identifier
	 */
	@NonNull
	public static McpRequestId fromString(@NonNull String value) {
		return new McpRequestId(requireNonNull(value), null);
	}

	/**
	 * Creates a request identifier from its integral numeric wire value.
	 *
	 * @param value the integral identifier
	 * @return the request identifier
	 */
	@NonNull
	public static McpRequestId fromInteger(@NonNull BigInteger value) {
		return new McpRequestId(null, requireNonNull(value));
	}

	private McpRequestId(@Nullable String stringValue, @Nullable BigInteger integerValue) {
		this.stringValue = stringValue;
		this.integerValue = integerValue;
	}

	/**
	 * Returns the string representation when this is a string request ID.
	 *
	 * @return the string value, or the empty optional for an integer request ID
	 */
	@NonNull
	public Optional<@NonNull String> asString() {
		return Optional.ofNullable(this.stringValue);
	}

	/**
	 * Returns the numeric representation when this is an integer request ID.
	 *
	 * @return the integer value, or the empty optional for a string request ID
	 */
	@NonNull
	public Optional<@NonNull BigInteger> asInteger() {
		return Optional.ofNullable(this.integerValue);
	}

	/**
	 * Compares request IDs by wire type and value.
	 *
	 * @param object the object to compare
	 * @return {@code true} if the object is an equal request ID
	 */
	@Override
	public boolean equals(@Nullable Object object) {
		if (this == object)
			return true;
		if (!(object instanceof McpRequestId other))
			return false;
		return Objects.equals(this.stringValue, other.stringValue)
				&& Objects.equals(this.integerValue, other.integerValue);
	}

	/**
	 * Returns a hash code derived from the request ID's wire type and value.
	 *
	 * @return the request ID hash code
	 */
	@Override
	public int hashCode() {
		return Objects.hash(this.stringValue, this.integerValue);
	}

	/**
	 * Returns a bounded, control-character-sanitized diagnostic representation.
	 *
	 * @return the safe diagnostic representation
	 */
	@Override
	@NonNull
	public String toString() {
		String source = this.integerValue != null
				? this.integerValue.toString() : requireNonNull(this.stringValue);
		StringBuilder bounded = new StringBuilder();
		source.codePoints().limit(128).forEach(codePoint -> bounded.appendCodePoint(
				Character.isISOControl(codePoint) ? '\uFFFD' : codePoint));
		if (source.codePointCount(0, source.length()) > 128)
			bounded.append('\u2026');
		return this.integerValue == null
				? "McpRequestId{string='" + bounded + "'}"
				: "McpRequestId{integer=" + bounded + "}";
	}
}
