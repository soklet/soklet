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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import static java.util.Objects.requireNonNull;

/**
 * A thread-safe histogram with fixed bucket boundaries.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class Histogram {
	@NonNull
	private final long[] bucketBoundaries;
	@NonNull
	private final LongAdder[] bucketCounts;
	@NonNull
	private final LongAdder count;
	@NonNull
	private final LongAdder sum;
	@NonNull
	private final AtomicLong min;
	@NonNull
	private final AtomicLong max;

	Histogram(@NonNull long[] bucketBoundaries) {
		requireNonNull(bucketBoundaries);

		this.bucketBoundaries = bucketBoundaries.clone();
		Arrays.sort(this.bucketBoundaries);
		this.bucketCounts = new LongAdder[this.bucketBoundaries.length + 1];
		for (int i = 0; i < this.bucketCounts.length; i++)
			this.bucketCounts[i] = new LongAdder();
		this.count = new LongAdder();
		this.sum = new LongAdder();
		this.min = new AtomicLong(Long.MAX_VALUE);
		this.max = new AtomicLong(Long.MIN_VALUE);
	}

	void record(long value) {
		if (value < 0)
			return;

		this.count.increment();
		this.sum.add(value);
		updateMin(value);
		updateMax(value);

		int bucketIndex = bucketIndex(value);
		this.bucketCounts[bucketIndex].increment();
	}

	@NonNull
	HistogramSnapshot snapshot() {
		long[] boundariesWithOverflow = Arrays.copyOf(this.bucketBoundaries, this.bucketBoundaries.length + 1);
		boundariesWithOverflow[boundariesWithOverflow.length - 1] = Long.MAX_VALUE;

		long[] cumulativeCounts = new long[this.bucketCounts.length];
		long cumulative = 0;
		for (int i = 0; i < this.bucketCounts.length; i++) {
			cumulative += this.bucketCounts[i].sum();
			cumulativeCounts[i] = cumulative;
		}

		long countSnapshot = this.count.sum();
		long sumSnapshot = this.sum.sum();
		long minSnapshot = this.min.get();
		long maxSnapshot = this.max.get();

		if (minSnapshot == Long.MAX_VALUE)
			minSnapshot = 0;
		if (maxSnapshot == Long.MIN_VALUE)
			maxSnapshot = 0;

		return new HistogramSnapshot(boundariesWithOverflow, cumulativeCounts, countSnapshot, sumSnapshot, minSnapshot, maxSnapshot);
	}

	void reset() {
		this.count.reset();
		this.sum.reset();
		this.min.set(Long.MAX_VALUE);
		this.max.set(Long.MIN_VALUE);
		for (LongAdder bucket : this.bucketCounts)
			bucket.reset();
	}

	private int bucketIndex(long value) {
		for (int i = 0; i < this.bucketBoundaries.length; i++)
			if (value <= this.bucketBoundaries[i])
				return i;

		return this.bucketBoundaries.length;
	}

	private void updateMin(long value) {
		long current;
		while (value < (current = this.min.get())) {
			if (this.min.compareAndSet(current, value))
				break;
		}
	}

	private void updateMax(long value) {
		long current;
		while (value > (current = this.max.get())) {
			if (this.max.compareAndSet(current, value))
				break;
		}
	}
}
