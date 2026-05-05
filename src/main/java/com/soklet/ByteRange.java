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

import static java.lang.String.format;
import static java.lang.Math.addExact;
import static java.lang.Math.subtractExact;
import static java.util.Objects.requireNonNull;

/**
 * Immutable representation of one satisfiable byte range.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class ByteRange {
	@NonNull
	private final Long start;
	@NonNull
	private final Long endInclusive;
	@NonNull
	private final Long length;

	@NonNull
	public static ByteRange fromStartAndEndInclusive(@NonNull Long start,
																									 @NonNull Long endInclusive) {
		requireNonNull(start);
		requireNonNull(endInclusive);
		return new ByteRange(start, endInclusive);
	}

	private ByteRange(@NonNull Long start,
										@NonNull Long endInclusive) {
		requireNonNull(start);
		requireNonNull(endInclusive);

		if (start < 0)
			throw new IllegalArgumentException("Range start must be >= 0.");

		if (endInclusive < 0)
			throw new IllegalArgumentException("Range end must be >= 0.");

		if (start > endInclusive)
			throw new IllegalArgumentException("Range start must be <= range end.");

		this.start = start;
		this.endInclusive = endInclusive;

		try {
			this.length = addExact(subtractExact(endInclusive, start), 1L);
		} catch (ArithmeticException e) {
			throw new IllegalArgumentException("Range length exceeds maximum supported value.", e);
		}
	}

	@NonNull
	public Long getStart() {
		return this.start;
	}

	@NonNull
	public Long getEndInclusive() {
		return this.endInclusive;
	}

	@NonNull
	public Long getLength() {
		return this.length;
	}

	@NonNull
	public String toContentRangeHeaderValue(@NonNull Long representationLength) {
		requireNonNull(representationLength);

		if (representationLength < 0)
			throw new IllegalArgumentException("Representation length must be >= 0.");

		if (getEndInclusive() >= representationLength)
			throw new IllegalArgumentException("Range end must be less than representation length.");

		return format("bytes %d-%d/%d", getStart(), getEndInclusive(), representationLength);
	}

	@Override
	public String toString() {
		return format("%s{start=%s, endInclusive=%s}", getClass().getSimpleName(), getStart(), getEndInclusive());
	}

	@Override
	public boolean equals(@Nullable Object object) {
		if (this == object)
			return true;

		if (!(object instanceof ByteRange byteRange))
			return false;

		return Objects.equals(getStart(), byteRange.getStart())
				&& Objects.equals(getEndInclusive(), byteRange.getEndInclusive());
	}

	@Override
	public int hashCode() {
		return Objects.hash(getStart(), getEndInclusive());
	}
}
