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
	private static final BigInteger BIG_INTEGER_ZERO;
	@NonNull
	private static final BigInteger BIG_INTEGER_ONE;

	static {
		BIG_INTEGER_ZERO = BigInteger.ZERO;
		BIG_INTEGER_ONE = BigInteger.ONE;
	}

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

		BigInteger representationLengthAsBigInteger = BigInteger.valueOf(representationLength);

		if (first.isEmpty())
			return suffixSelection(last, representationLength, representationLengthAsBigInteger);

		return explicitSelection(first, last, representationLength, representationLengthAsBigInteger);
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
	public Optional<ByteRange> getRange() {
		return Optional.ofNullable(this.range);
	}

	@Override
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
																										@NonNull Long representationLength,
																										@NonNull BigInteger representationLengthAsBigInteger) {
		BigInteger suffixLength = parseNonnegativeInteger(suffixLengthText).orElse(null);

		if (suffixLength == null)
			return malformed();

		if (suffixLength.equals(BIG_INTEGER_ZERO))
			return unsatisfiable();

		if (representationLength == 0)
			return unsatisfiable();

		BigInteger selectedLength = suffixLength.min(representationLengthAsBigInteger);
		BigInteger start = representationLengthAsBigInteger.subtract(selectedLength);
		BigInteger endInclusive = representationLengthAsBigInteger.subtract(BIG_INTEGER_ONE);

		return satisfiable(ByteRange.fromStartAndEndInclusive(start.longValueExact(), endInclusive.longValueExact()));
	}

	@NonNull
	private static ByteRangeSelection explicitSelection(@NonNull String firstText,
																										 @NonNull String lastText,
																										 @NonNull Long representationLength,
																										 @NonNull BigInteger representationLengthAsBigInteger) {
		BigInteger first = parseNonnegativeInteger(firstText).orElse(null);

		if (first == null)
			return malformed();

		BigInteger last = lastText.isEmpty()
				? null
				: parseNonnegativeInteger(lastText).orElse(null);

		if (!lastText.isEmpty() && last == null)
			return malformed();

		if (last != null && first.compareTo(last) > 0)
			return malformed();

		if (representationLength == 0 || first.compareTo(representationLengthAsBigInteger) >= 0)
			return unsatisfiable();

		BigInteger endInclusive = last == null
				? representationLengthAsBigInteger.subtract(BIG_INTEGER_ONE)
				: last.min(representationLengthAsBigInteger.subtract(BIG_INTEGER_ONE));

		return satisfiable(ByteRange.fromStartAndEndInclusive(first.longValueExact(), endInclusive.longValueExact()));
	}

	@NonNull
	private static Optional<BigInteger> parseNonnegativeInteger(@NonNull String text) {
		requireNonNull(text);

		if (text.isEmpty())
			return Optional.empty();

		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);

			if (c < '0' || c > '9')
				return Optional.empty();
		}

		return Optional.of(new BigInteger(text));
	}

	public enum ByteRangeSelectionType {
		ABSENT,
		MALFORMED,
		UNSUPPORTED,
		UNSATISFIABLE,
		SATISFIABLE
	}
}
