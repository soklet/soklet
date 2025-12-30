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
import java.util.Arrays;

import static java.util.Objects.requireNonNull;

/**
 * Immutable snapshot of a {@link Histogram}.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class HistogramSnapshot {
	@NonNull
	private final long[] bucketBoundaries;
	@NonNull
	private final long[] bucketCumulativeCounts;
	private final long count;
	private final long sum;
	private final long min;
	private final long max;

	HistogramSnapshot(@NonNull long[] bucketBoundaries,
										@NonNull long[] bucketCumulativeCounts,
										long count,
										long sum,
										long min,
										long max) {
		requireNonNull(bucketBoundaries);
		requireNonNull(bucketCumulativeCounts);

		if (bucketBoundaries.length != bucketCumulativeCounts.length)
			throw new IllegalArgumentException("Bucket boundaries and cumulative counts must be the same length");

		this.bucketBoundaries = bucketBoundaries.clone();
		this.bucketCumulativeCounts = bucketCumulativeCounts.clone();
		this.count = count;
		this.sum = sum;
		this.min = min;
		this.max = max;
	}

	public int getBucketCount() {
		return this.bucketBoundaries.length;
	}

	public long getBucketBoundary(int index) {
		return this.bucketBoundaries[index];
	}

	public long getBucketCumulativeCount(int index) {
		return this.bucketCumulativeCounts[index];
	}

	public long getCount() {
		return this.count;
	}

	public long getSum() {
		return this.sum;
	}

	public long getMin() {
		return this.min;
	}

	public long getMax() {
		return this.max;
	}

	public long getPercentile(double percentile) {
		if (percentile <= 0.0)
			return this.min;
		if (percentile >= 100.0)
			return this.max;
		if (this.count == 0)
			return 0;

		long threshold = (long) Math.ceil((percentile / 100.0) * this.count);

		for (int i = 0; i < this.bucketCumulativeCounts.length; i++)
			if (this.bucketCumulativeCounts[i] >= threshold)
				return this.bucketBoundaries[i];

		return this.bucketBoundaries[this.bucketBoundaries.length - 1];
	}

	@Override
	public String toString() {
		return String.format("%s{count=%d, min=%d, max=%d, sum=%d, bucketBoundaries=%s}",
				getClass().getSimpleName(), this.count, this.min, this.max, this.sum, Arrays.toString(this.bucketBoundaries));
	}
}
