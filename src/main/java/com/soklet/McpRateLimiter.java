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
	@NonNull
	private static final BigInteger LONG_MAXIMUM =
			BigInteger.valueOf(Long.MAX_VALUE);
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
	private final ConcurrentMap<@NonNull BucketKey, @NonNull Bucket> buckets;
	@NonNull
	private final McpRateLimiterClock clock;
	private final int maximumRetainedPartitions;
	@NonNull
	private final ReentrantReadWriteLock partitionLock;

	DefaultMcpRateLimiter(@NonNull McpTokenBucketConfig configuration) {
		this(configuration, System::nanoTime, MAXIMUM_RETAINED_PARTITIONS);
	}

	DefaultMcpRateLimiter(@NonNull McpTokenBucketConfig configuration,
			@NonNull McpRateLimiterClock clock, int maximumRetainedPartitions) {
		this.configuration = requireNonNull(configuration);
		this.refillIntervalNanos = configuration.getRefillInterval().toNanos();
		this.tokenUnit = BigInteger.valueOf(this.refillIntervalNanos);
		this.capacityUnits = this.tokenUnit.multiply(
				BigInteger.valueOf(configuration.getCapacity()));
		this.refillTokens = BigInteger.valueOf(configuration.getRefillTokens());
		this.buckets = new ConcurrentHashMap<>();
		this.clock = requireNonNull(clock);
		if (maximumRetainedPartitions < 1)
			throw new IllegalArgumentException(
					"maximumRetainedPartitions must be positive");
		this.maximumRetainedPartitions = maximumRetainedPartitions;
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
			Bucket bucket = this.buckets.get(key);
			if (bucket != null)
				return bucket.acquire(nowNanos, this.capacityUnits,
						this.tokenUnit, this.refillTokens);
		} finally {
			this.partitionLock.readLock().unlock();
		}

		this.partitionLock.writeLock().lock();
		try {
			Bucket bucket = this.buckets.get(key);
			if (bucket != null)
				return bucket.acquire(nowNanos, this.capacityUnits,
						this.tokenUnit, this.refillTokens);

			if (this.buckets.size() >= this.maximumRetainedPartitions)
				reclaimOneFullBucket(nowNanos);
			if (this.buckets.size() >= this.maximumRetainedPartitions)
				return McpRateLimitDecision.denied(
						minimumTimeUntilReclaimable(nowNanos));

			Bucket newBucket = new Bucket(this.capacityUnits, nowNanos);
			McpRateLimitDecision decision = newBucket.acquire(nowNanos,
					this.capacityUnits, this.tokenUnit, this.refillTokens);
			this.buckets.put(key, newBucket);
			return decision;
		} finally {
			this.partitionLock.writeLock().unlock();
		}
	}

	private void reclaimOneFullBucket(long nowNanos) {
		for (var entry : this.buckets.entrySet()) {
			if (entry.getValue().isFull(nowNanos, this.capacityUnits,
					this.refillTokens)) {
				this.buckets.remove(entry.getKey(), entry.getValue());
				return;
			}
		}
	}

	@NonNull
	private Duration minimumTimeUntilReclaimable(long nowNanos) {
		BigInteger minimumNanos = null;
		for (Bucket bucket : this.buckets.values()) {
			BigInteger candidate = bucket.nanosUntilFull(nowNanos,
					this.capacityUnits, this.refillTokens);
			if (minimumNanos == null || candidate.compareTo(minimumNanos) < 0)
				minimumNanos = candidate;
		}
		if (minimumNanos == null)
			return this.configuration.getRefillInterval();
		long boundedNanos = minimumNanos.min(LONG_MAXIMUM).longValueExact();
		return Duration.ofNanos(Math.max(1L, boundedNanos));
	}

	void reset() {
		this.partitionLock.writeLock().lock();
		try {
			this.buckets.clear();
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

		@NonNull
		private synchronized BigInteger nanosUntilFull(long nowNanos,
				@NonNull BigInteger capacityUnits,
				@NonNull BigInteger refillTokens) {
			refill(nowNanos, capacityUnits, refillTokens);
			return ceilingDivide(capacityUnits.subtract(this.availableUnits),
					refillTokens).max(BigInteger.ONE);
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
