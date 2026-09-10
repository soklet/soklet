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
import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static java.util.Objects.requireNonNull;

/**
 * Thread-safe application rate limiter for MCP requests or tool invocations.
 * <p>
 * Soklet invokes the configured request limiter once for every admitted
 * request or notification. It additionally invokes the resolved tool limiter
 * for a tool call. A {@code null} result or exception fails closed. Successful
 * acquisitions are never refunded after later denial, failure, cancelation,
 * timeout, or response-write failure.
 * <p>
 * Implementations may keep state in-process or delegate to a distributed
 * service. Soklet does not own or close application-supplied limiters.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
@FunctionalInterface
public interface McpRateLimiter {
	/**
	 * Attempts to acquire permission for one request or tool invocation.
	 *
	 * @param context immutable rate-limit context
	 * @return a non-null allowed or denied decision
	 * @throws Exception if the backing rate-limit service fails
	 */
	@NonNull
	McpRateLimitDecision acquire(@NonNull McpRateLimitContext context) throws Exception;

	/**
	 * Creates Soklet's built-in in-memory token-bucket limiter with documented
	 * defaults.
	 *
	 * @return a new independent in-memory limiter
	 */
	@NonNull
	static McpRateLimiter fromInMemoryDefaults() {
		return fromInMemoryTokenBucket(McpTokenBucketConfig.fromDefaults());
	}

	/**
	 * Creates Soklet's built-in in-memory token-bucket limiter.
	 * <p>
	 * The returned limiter partitions state by normalized endpoint path, the
	 * accepted admission identity's stable rate-limit partition key, and the
	 * context target. Retained partition state is bounded and a new partition
	 * fails closed when no fully replenished partition can be reclaimed. The
	 * limiter is local to this JVM and provides no cross-instance coordination.
	 *
	 * @param tokenBucketConfig finite token-bucket configuration
	 * @return a new independent in-memory limiter
	 */
	@NonNull
	static McpRateLimiter fromInMemoryTokenBucket(
			@NonNull McpTokenBucketConfig tokenBucketConfig) {
		return new DefaultMcpRateLimiter(requireNonNull(tokenBucketConfig));
	}
}

/**
 * Built-in process-local token-bucket implementation.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class DefaultMcpRateLimiter implements McpRateLimiter {
	private static final int MAXIMUM_RETAINED_PARTITIONS = 16_384;
	private static final int MAXIMUM_PARTITION_RECLAIM_PROBES = 64;
	@NonNull
	private final McpTokenBucketConfig configuration;
	private final long refillIntervalNanos;
	@NonNull
	private final BigInteger tokenUnit;
	@NonNull
	private final BigInteger capacityUnits;
	@NonNull
	private final BigInteger refillTokens;
	@NonNull
	private final ConcurrentMap<@NonNull BucketKey, @NonNull RetainedBucket> buckets;
	@NonNull
	private final List<@NonNull RetainedBucket> bucketSlots;
	@NonNull
	private final McpRateLimiterClock clock;
	private final int maximumRetainedPartitions;
	private final int maximumPartitionReclaimProbes;
	@NonNull
	private final ReentrantReadWriteLock partitionLock;
	private int nextPartitionReclaimProbeSlot;

	DefaultMcpRateLimiter(@NonNull McpTokenBucketConfig configuration) {
		this(configuration, System::nanoTime, MAXIMUM_RETAINED_PARTITIONS);
	}

	DefaultMcpRateLimiter(@NonNull McpTokenBucketConfig configuration,
			@NonNull McpRateLimiterClock clock, int maximumRetainedPartitions) {
		this(configuration, clock, maximumRetainedPartitions,
				Math.min(MAXIMUM_PARTITION_RECLAIM_PROBES,
						maximumRetainedPartitions));
	}

	DefaultMcpRateLimiter(@NonNull McpTokenBucketConfig configuration,
			@NonNull McpRateLimiterClock clock, int maximumRetainedPartitions,
			int maximumPartitionReclaimProbes) {
		this.configuration = requireNonNull(configuration);
		this.refillIntervalNanos = configuration.getRefillInterval().toNanos();
		this.tokenUnit = BigInteger.valueOf(this.refillIntervalNanos);
		this.capacityUnits = this.tokenUnit.multiply(
				BigInteger.valueOf(configuration.getCapacity()));
		this.refillTokens = BigInteger.valueOf(configuration.getRefillTokens());
		if (maximumRetainedPartitions < 1)
			throw new IllegalArgumentException(
					"maximumRetainedPartitions must be positive");
		if (maximumPartitionReclaimProbes < 1)
			throw new IllegalArgumentException(
					"maximumPartitionReclaimProbes must be positive");
		this.buckets = new ConcurrentHashMap<>();
		this.bucketSlots = new ArrayList<>(maximumRetainedPartitions);
		this.clock = requireNonNull(clock);
		this.maximumRetainedPartitions = maximumRetainedPartitions;
		this.maximumPartitionReclaimProbes = Math.min(
				maximumPartitionReclaimProbes, maximumRetainedPartitions);
		this.partitionLock = new ReentrantReadWriteLock();
	}

	@Override
	@NonNull
	public McpRateLimitDecision acquire(@NonNull McpRateLimitContext context) {
		requireNonNull(context);
		BucketKey key = new BucketKey(
				context.getEndpoint().getPath(),
				context.getAdmissionIdentity().getRateLimitPartitionKey(),
				context.getTarget());
		long nowNanos = this.clock.nanoTime();

		this.partitionLock.readLock().lock();
		try {
			RetainedBucket retainedBucket = this.buckets.get(key);
			if (retainedBucket != null)
				return retainedBucket.bucket().acquire(nowNanos, this.capacityUnits,
						this.tokenUnit, this.refillTokens);
		} finally {
			this.partitionLock.readLock().unlock();
		}

		this.partitionLock.writeLock().lock();
		try {
			RetainedBucket retainedBucket = this.buckets.get(key);
			if (retainedBucket != null)
				return retainedBucket.bucket().acquire(nowNanos, this.capacityUnits,
						this.tokenUnit, this.refillTokens);

			int reclaimedSlot = -1;
			if (this.buckets.size() >= this.maximumRetainedPartitions)
				reclaimedSlot = reclaimOneFullBucket(nowNanos);
			if (this.buckets.size() >= this.maximumRetainedPartitions)
				return McpRateLimitDecision.denied(
						this.configuration.getRefillInterval());

			Bucket newBucket = new Bucket(this.capacityUnits, nowNanos);
			McpRateLimitDecision decision = newBucket.acquire(nowNanos,
					this.capacityUnits, this.tokenUnit, this.refillTokens);
			RetainedBucket newRetainedBucket = new RetainedBucket(key, newBucket);
			this.buckets.put(key, newRetainedBucket);
			if (reclaimedSlot < 0)
				this.bucketSlots.add(newRetainedBucket);
			else
				this.bucketSlots.set(reclaimedSlot, newRetainedBucket);
			return decision;
		} finally {
			this.partitionLock.writeLock().unlock();
		}
	}

	private int reclaimOneFullBucket(long nowNanos) {
		int slotCount = this.bucketSlots.size();
		int probeCount = Math.min(slotCount,
				this.maximumPartitionReclaimProbes);
		for (int probe = 0; probe < probeCount; probe++) {
			int slot = this.nextPartitionReclaimProbeSlot;
			this.nextPartitionReclaimProbeSlot = slot + 1 == slotCount
					? 0 : slot + 1;
			RetainedBucket candidate = this.bucketSlots.get(slot);
			if (!candidate.bucket().isFull(nowNanos, this.capacityUnits,
					this.refillTokens))
				continue;
			if (!this.buckets.remove(candidate.key(), candidate))
				throw new IllegalStateException(
						"A retained rate-limit partition could not be reclaimed.");
			return slot;
		}
		return -1;
	}

	void reset() {
		this.partitionLock.writeLock().lock();
		try {
			this.buckets.clear();
			this.bucketSlots.clear();
			this.nextPartitionReclaimProbeSlot = 0;
		} finally {
			this.partitionLock.writeLock().unlock();
		}
	}

	int retainedPartitionCount() {
		this.partitionLock.readLock().lock();
		try {
			return this.buckets.size();
		} finally {
			this.partitionLock.readLock().unlock();
		}
	}

	/**
	 * Immutable bucket-partition identity.
	 *
	 * @param endpointPath normalized endpoint path
	 * @param partitionKey stable admission partition key
	 * @param target rate-limit stage
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	private record BucketKey(@NonNull String endpointPath,
			@NonNull String partitionKey, @NonNull McpRateLimitTarget target) {
		private BucketKey {
			requireNonNull(endpointPath);
			requireNonNull(partitionKey);
			requireNonNull(target);
		}
	}

	private record RetainedBucket(@NonNull BucketKey key,
			@NonNull Bucket bucket) {
		private RetainedBucket {
			requireNonNull(key);
			requireNonNull(bucket);
		}
	}

	/**
	 * One independently synchronized mutable token bucket.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	private static final class Bucket {
		@NonNull
		private BigInteger availableUnits;
		private long lastRefillNanos;

		private Bucket(@NonNull BigInteger capacityUnits, long nowNanos) {
			this.availableUnits = requireNonNull(capacityUnits);
			this.lastRefillNanos = nowNanos;
		}

		@NonNull
		private synchronized McpRateLimitDecision acquire(long nowNanos,
				@NonNull BigInteger capacityUnits,
				@NonNull BigInteger tokenUnit,
				@NonNull BigInteger refillTokens) {
			refill(nowNanos, capacityUnits, refillTokens);

			if (this.availableUnits.compareTo(tokenUnit) >= 0) {
				this.availableUnits = this.availableUnits.subtract(tokenUnit);
				return McpRateLimitDecision.allowed();
			}

			BigInteger missingUnits = tokenUnit.subtract(this.availableUnits);
			long retryNanos = ceilingDivide(missingUnits, refillTokens).longValueExact();
			return McpRateLimitDecision.denied(Duration.ofNanos(retryNanos));
		}

		private synchronized boolean isFull(long nowNanos,
				@NonNull BigInteger capacityUnits,
				@NonNull BigInteger refillTokens) {
			refill(nowNanos, capacityUnits, refillTokens);
			return this.availableUnits.equals(capacityUnits);
		}

		private void refill(long nowNanos, @NonNull BigInteger capacityUnits,
				@NonNull BigInteger refillTokens) {
			long elapsedNanos = nowNanos - this.lastRefillNanos;
			if (elapsedNanos <= 0)
				return;
			BigInteger replenishedUnits = BigInteger.valueOf(elapsedNanos)
					.multiply(refillTokens);
			this.availableUnits = this.availableUnits.add(replenishedUnits)
					.min(capacityUnits);
			this.lastRefillNanos = nowNanos;
		}

		@NonNull
		private static BigInteger ceilingDivide(@NonNull BigInteger dividend,
				@NonNull BigInteger divisor) {
			BigInteger[] quotientAndRemainder = dividend.divideAndRemainder(divisor);
			return quotientAndRemainder[1].signum() == 0
					? quotientAndRemainder[0]
					: quotientAndRemainder[0].add(BigInteger.ONE);
		}
	}
}

/**
 * Package-private monotonic clock seam for deterministic limiter tests.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
@FunctionalInterface
interface McpRateLimiterClock {
	long nanoTime();
}
