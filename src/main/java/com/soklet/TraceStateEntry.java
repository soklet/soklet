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
import java.util.Objects;
import java.util.Optional;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * A validated W3C {@code tracestate} list member.
 * <p>
 * This type models only the W3C key/value envelope. Vendor-specific value contents are opaque.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class TraceStateEntry {
	@NonNull
	private final String key;
	@NonNull
	private final String value;

	/**
	 * Parses a W3C {@code tracestate} list member.
	 *
	 * @param member the list member text, for example {@code vendor=value}
	 * @return the parsed entry, or {@link Optional#empty()} if malformed
	 */
	@NonNull
	public static Optional<TraceStateEntry> fromMember(@Nullable String member) {
		String trimmedMember = trimOws(member);

		if (trimmedMember == null || trimmedMember.isEmpty())
			return Optional.empty();

		int separator = trimmedMember.indexOf('=');

		if (separator <= 0 || separator != trimmedMember.lastIndexOf('='))
			return Optional.empty();

		String key = trimmedMember.substring(0, separator);
		String value = trimmedMember.substring(separator + 1);

		if (!isValidKey(key) || !isValidValue(value))
			return Optional.empty();

		return Optional.of(new TraceStateEntry(key, value));
	}

	/**
	 * Creates a W3C {@code tracestate} list member from a key and value.
	 *
	 * @param key   the W3C {@code tracestate} key
	 * @param value the opaque W3C {@code tracestate} value
	 * @return the trace-state entry
	 */
	@NonNull
	public static TraceStateEntry fromKeyAndValue(@NonNull String key,
																								@NonNull String value) {
		requireNonNull(key);
		requireNonNull(value);

		if (!isValidKey(key))
			throw new IllegalArgumentException(format("Invalid tracestate key '%s'", key));

		if (!isValidValue(value))
			throw new IllegalArgumentException(format("Invalid tracestate value for key '%s'", key));

		return new TraceStateEntry(key, value);
	}

	private TraceStateEntry(@NonNull String key,
													@NonNull String value) {
		this.key = requireNonNull(key);
		this.value = requireNonNull(value);
	}

	/**
	 * Returns the W3C {@code tracestate} key.
	 *
	 * @return the key
	 */
	@NonNull
	public String getKey() {
		return this.key;
	}

	/**
	 * Returns the opaque W3C {@code tracestate} value.
	 *
	 * @return the value
	 */
	@NonNull
	public String getValue() {
		return this.value;
	}

	/**
	 * Returns this entry in HTTP header member form.
	 *
	 * @return the {@code key=value} representation
	 */
	@NonNull
	public String toHeaderMemberValue() {
		return format("%s=%s", getKey(), getValue());
	}

	@Override
	@NonNull
	public String toString() {
		return format("%s{key=%s, valueLength=%s}", getClass().getSimpleName(), getKey(), getValue().length());
	}

	@Override
	public boolean equals(@Nullable Object object) {
		if (this == object)
			return true;

		if (!(object instanceof TraceStateEntry traceStateEntry))
			return false;

		return Objects.equals(getKey(), traceStateEntry.getKey())
				&& Objects.equals(getValue(), traceStateEntry.getValue());
	}

	@Override
	public int hashCode() {
		return Objects.hash(getKey(), getValue());
	}

	@Nullable
	private static String trimOws(@Nullable String value) {
		if (value == null)
			return null;

		int start = 0;
		int end = value.length();

		while (start < end && isOws(value.charAt(start)))
			start++;

		while (end > start && isOws(value.charAt(end - 1)))
			end--;

		return value.substring(start, end);
	}

	private static boolean isOws(char c) {
		return c == ' ' || c == '\t';
	}

	static boolean isValidKey(@Nullable String key) {
		if (key == null || key.isEmpty())
			return false;

		int at = key.indexOf('@');

		if (at < 0)
			return isValidSimpleKey(key);

		if (at != key.lastIndexOf('@'))
			return false;

		return isValidTenantId(key.substring(0, at))
				&& isValidSystemId(key.substring(at + 1));
	}

	private static boolean isValidSimpleKey(@NonNull String key) {
		requireNonNull(key);

		if (key.length() > 256 || !isLowercaseAlpha(key.charAt(0)))
			return false;

		for (int i = 1; i < key.length(); i++)
			if (!isKeyContinuation(key.charAt(i)))
				return false;

		return true;
	}

	private static boolean isValidTenantId(@NonNull String tenantId) {
		requireNonNull(tenantId);

		if (tenantId.isEmpty() || tenantId.length() > 241 || !isLowercaseAlphaOrDigit(tenantId.charAt(0)))
			return false;

		for (int i = 1; i < tenantId.length(); i++)
			if (!isKeyContinuation(tenantId.charAt(i)))
				return false;

		return true;
	}

	private static boolean isValidSystemId(@NonNull String systemId) {
		requireNonNull(systemId);

		if (systemId.isEmpty() || systemId.length() > 14 || !isLowercaseAlpha(systemId.charAt(0)))
			return false;

		for (int i = 1; i < systemId.length(); i++)
			if (!isKeyContinuation(systemId.charAt(i)))
				return false;

		return true;
	}

	static boolean isValidValue(@Nullable String value) {
		if (value == null || value.isEmpty() || value.length() > 256 || value.charAt(value.length() - 1) == ' ')
			return false;

		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);

			if (c < 0x20 || c > 0x7E || c == ',' || c == '=')
				return false;
		}

		return true;
	}

	private static boolean isLowercaseAlpha(char c) {
		return c >= 'a' && c <= 'z';
	}

	private static boolean isLowercaseAlphaOrDigit(char c) {
		return isLowercaseAlpha(c) || isDigit(c);
	}

	private static boolean isDigit(char c) {
		return c >= '0' && c <= '9';
	}

	private static boolean isKeyContinuation(char c) {
		return isLowercaseAlphaOrDigit(c)
				|| c == '_'
				|| c == '-'
				|| c == '*'
				|| c == '/';
	}
}
