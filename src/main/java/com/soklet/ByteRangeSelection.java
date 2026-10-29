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
 * The result of applying a {@code Range} header to a known-length representation.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class ByteRangeSelection {
	@NonNull
	private final ByteRangeSelectionType type;
	@Nullable
	private final ByteRange range;

	@NonNull
	public static ByteRangeSelection fromHeaderValue(@Nullable String rangeHeaderValue,
																									 @NonNull Long representationLength) {
		requireNonNull(representationLength);

		if (representationLength < 0)
			throw new IllegalArgumentException("Representation length must be >= 0.");

		String headerValue = Utilities.trimAggressivelyToNull(rangeHeaderValue);

		if (headerValue == null)
			return absent();

		int equalsIndex = headerValue.indexOf('=');

		if (equalsIndex <= 0)
			return malformed();

		String unit = headerValue.substring(0, equalsIndex).trim();
		String rangeSet = headerValue.substring(equalsIndex + 1).trim();

		if (!"bytes".equalsIgnoreCase(unit))
			return unsupported();

		if (rangeSet.isEmpty())
			return malformed();

		if (rangeSet.indexOf(',') >= 0)
			return unsupported();

		int dashIndex = rangeSet.indexOf('-');

		if (dashIndex < 0 || dashIndex != rangeSet.lastIndexOf('-'))
			return malformed();

		String first = rangeSet.substring(0, dashIndex).trim();
		String last = rangeSet.substring(dashIndex + 1).trim();

		if (first.isEmpty() && last.isEmpty())
			return malformed();

		if (first.isEmpty())
			return suffixSelection(last, representationLength);

		return explicitSelection(first, last, representationLength);
	}

	private ByteRangeSelection(@NonNull ByteRangeSelectionType type,
														 @Nullable ByteRange range) {
		requireNonNull(type);

		if (type == ByteRangeSelectionType.SATISFIABLE && range == null)
			throw new IllegalArgumentException("A satisfiable byte range selection must include a range.");

		if (type != ByteRangeSelectionType.SATISFIABLE && range != null)
			throw new IllegalArgumentException("Only satisfiable byte range selections may include a range.");

		this.type = type;
		this.range = range;
	}

	@NonNull
	public ByteRangeSelectionType getType() {
		return this.type;
	}

	@NonNull
	public Optional<@NonNull ByteRange> getRange() {
		return Optional.ofNullable(this.range);
	}

	@Override
	@NonNull
	public String toString() {
		return format("%s{type=%s, range=%s}", getClass().getSimpleName(), getType(), getRange().orElse(null));
	}

	@Override
	public boolean equals(@Nullable Object object) {
		if (this == object)
			return true;

		if (!(object instanceof ByteRangeSelection byteRangeSelection))
			return false;

		return getType() == byteRangeSelection.getType()
				&& Objects.equals(getRange(), byteRangeSelection.getRange());
	}

	@Override
	public int hashCode() {
		return Objects.hash(getType(), getRange());
	}

	@NonNull
	private static ByteRangeSelection absent() {
		return new ByteRangeSelection(ByteRangeSelectionType.ABSENT, null);
	}

	@NonNull
	private static ByteRangeSelection malformed() {
		return new ByteRangeSelection(ByteRangeSelectionType.MALFORMED, null);
	}

	@NonNull
	private static ByteRangeSelection unsupported() {
		return new ByteRangeSelection(ByteRangeSelectionType.UNSUPPORTED, null);
	}

	@NonNull
	private static ByteRangeSelection unsatisfiable() {
		return new ByteRangeSelection(ByteRangeSelectionType.UNSATISFIABLE, null);
	}

	@NonNull
	private static ByteRangeSelection satisfiable(@NonNull ByteRange range) {
		requireNonNull(range);
		return new ByteRangeSelection(ByteRangeSelectionType.SATISFIABLE, range);
	}

	@NonNull
	private static ByteRangeSelection suffixSelection(@NonNull String suffixLengthText,
			@NonNull Long representationLength) {
		DecimalDigits suffixLength = parseNonnegativeInteger(suffixLengthText).orElse(null);
		if (suffixLength == null)
			return malformed();
		if (suffixLength.value().equals("0"))
			return unsatisfiable();
		// A positive suffix is satisfiable even for an empty representation.
		// There is no nonempty byte interval to send, so ignore this range form.
		if (representationLength == 0)
			return unsupported();
		long selectedLength = Math.min(suffixLength.cappedLongValue(), representationLength);
		return satisfiable(ByteRange.fromStartAndEndInclusive(representationLength - selectedLength, representationLength - 1));
	}

	@NonNull
	private static ByteRangeSelection explicitSelection(@NonNull String firstText,
			@NonNull String lastText, @NonNull Long representationLength) {
		DecimalDigits first = parseNonnegativeInteger(firstText).orElse(null);
		if (first == null)
			return malformed();
		DecimalDigits last = lastText.isEmpty() ? null : parseNonnegativeInteger(lastText).orElse(null);
		if (!lastText.isEmpty() && last == null)
			return malformed();
		if (last != null && first.compareTo(last) > 0)
			return malformed();
		long start = first.cappedLongValue();
		if (representationLength == 0 || start >= representationLength)
			return unsatisfiable();
		long endInclusive = last == null ? representationLength - 1
				: Math.min(last.cappedLongValue(), representationLength - 1);
		return satisfiable(ByteRange.fromStartAndEndInclusive(start, endInclusive));
	}

	@NonNull
	private static Optional<DecimalDigits> parseNonnegativeInteger(@NonNull String text) {
		requireNonNull(text);
		if (text.isEmpty())
			return Optional.empty();
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c < '0' || c > '9')
				return Optional.empty();
		}
		int significantStart = 0;
		while (significantStart < text.length() - 1 && text.charAt(significantStart) == '0')
			significantStart++;
		return Optional.of(new DecimalDigits(text.substring(significantStart)));
	}

	/** Keeps arbitrary-size range ordering exact without constructing arbitrary-precision integers. */
	private record DecimalDigits(String value) implements Comparable<DecimalDigits> {
		@Override public int compareTo(DecimalDigits other) {
			int lengthOrder = Integer.compare(value.length(), other.value.length());
			return lengthOrder != 0 ? lengthOrder : value.compareTo(other.value);
		}

		long cappedLongValue() {
			String maximum = "9223372036854775807";
			return value.length() > maximum.length()
					|| (value.length() == maximum.length() && value.compareTo(maximum) > 0)
					? Long.MAX_VALUE : Long.parseLong(value);
		}
	}

	/**
	 * Classification of a parsed byte-range selection.
	 */
	public enum ByteRangeSelectionType {
		/**
		 * No {@code Range} header value was provided.
		 */
		ABSENT,
		/**
		 * The {@code Range} header value used byte-range syntax but was malformed.
		 */
		MALFORMED,
		/**
		 * The {@code Range} header value requested a range form Soklet does not serve, such as a non-byte unit or multiple ranges.
		 */
		UNSUPPORTED,
		/**
		 * The {@code Range} header value was valid but selected no bytes from the representation.
		 */
		UNSATISFIABLE,
		/**
		 * The {@code Range} header value selected a byte range that can be served.
		 */
		SATISFIABLE
	}
}
