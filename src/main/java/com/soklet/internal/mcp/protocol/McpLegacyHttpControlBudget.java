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
package com.soklet.internal.mcp.protocol;

import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.ThreadSafe;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * Bounded post-admission HTTP control demand. A reservation remains charged
 * until its application callback physically exits, even after logical timeout.
 * This type runs no application code and creates no execution capacity.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class McpLegacyHttpControlBudget {
	static final int MAXIMUM_RETAINED_PARTITIONS = 256;
	static final int MAXIMUM_SIMULTANEOUS_PER_PARTITION = 4;
	static final int MAXIMUM_ATTEMPTS_PER_SECOND = 64;
	static final long ATTEMPT_WINDOW_NANOS = 1_000_000_000L;

	record Snapshot(int retainedPartitions, int activeReservations, int retainedAttempts) {}

	private final Object lock = new Object();
	private final McpApplicationClock clock;
	private final Map<McpEffectivePartition, Bucket> buckets = new HashMap<>();
	private int activeReservations;

	McpLegacyHttpControlBudget(@NonNull McpApplicationClock clock) {
		this.clock = requireNonNull(clock);
	}

	/** Denied simultaneous attempts also count against the sliding rate window. */
	boolean reserve(@NonNull McpEffectivePartition partition) {
		requireNonNull(partition);
		synchronized (lock) {
			long now = clock.nanoTime();
			pruneWhileLocked(now);
			Bucket bucket = buckets.get(partition);
			if (bucket == null) {
				if (buckets.size() >= MAXIMUM_RETAINED_PARTITIONS) return false;
				bucket = new Bucket(now); buckets.put(partition, bucket);
			}
			bucket.lastActivityNanos = now;
			bucket.discardExpiredAttempts(now);
			if (bucket.attemptCount >= MAXIMUM_ATTEMPTS_PER_SECOND) return false;
			bucket.attemptTimes[(bucket.attemptHead + bucket.attemptCount) % MAXIMUM_ATTEMPTS_PER_SECOND] = now;
			bucket.attemptCount++;
			if (bucket.active >= MAXIMUM_SIMULTANEOUS_PER_PARTITION) return false;
			bucket.active++; activeReservations++;
			return true;
		}
	}

	/** Call exactly once for each accepted reservation, at physical callback exit. */
	void release(@NonNull McpEffectivePartition partition) {
		requireNonNull(partition);
		synchronized (lock) {
			Bucket bucket = buckets.get(partition);
			if (bucket == null || bucket.active == 0)
				throw new IllegalStateException("Legacy HTTP control reservation is not active.");
			bucket.active--; activeReservations--;
			bucket.lastActivityNanos = clock.nanoTime();
		}
	}

	void maintain() {
		synchronized (lock) { pruneWhileLocked(clock.nanoTime()); }
	}

	@NonNull Snapshot snapshot() {
		synchronized (lock) {
			int attempts = 0;
			for (Bucket bucket : buckets.values()) attempts += bucket.attemptCount;
			return new Snapshot(buckets.size(), activeReservations, attempts);
		}
	}

	private void pruneWhileLocked(long now) {
		Iterator<Bucket> iterator = buckets.values().iterator();
		while (iterator.hasNext()) {
			Bucket bucket = iterator.next();
			if (bucket.active == 0 && now - bucket.lastActivityNanos >= ATTEMPT_WINDOW_NANOS) iterator.remove();
			else bucket.discardExpiredAttempts(now);
		}
	}

	private static final class Bucket {
		private final long[] attemptTimes = new long[MAXIMUM_ATTEMPTS_PER_SECOND];
		private int attemptHead;
		private int attemptCount;
		private int active;
		private long lastActivityNanos;
		private Bucket(long now) { lastActivityNanos = now; }
		private void discardExpiredAttempts(long now) {
			while (attemptCount > 0 && now - attemptTimes[attemptHead] >= ATTEMPT_WINDOW_NANOS) {
				attemptHead = (attemptHead + 1) % MAXIMUM_ATTEMPTS_PER_SECOND; attemptCount--;
			}
		}
	}
}
