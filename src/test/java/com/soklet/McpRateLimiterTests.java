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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tests for the public MCP rate-limiter contract and built-in implementation.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public class McpRateLimiterTests {
	@Test
	public void customLimiterIsDirectlyUsableAsAFunctionalInterface()
			throws Exception {
		McpRateLimitDecision expected = McpRateLimitDecision.allowed();
		McpRateLimiter limiter = context -> expected;

		Assertions.assertSame(expected, limiter.acquire(context(
				"/one", "principal", McpRateLimitTarget.REQUEST)));
	}

	@Test
	public void inMemoryLimiterEnforcesCapacityAndSeparatesPartitions()
			throws Exception {
		McpRateLimiter limiter = McpRateLimiter.fromInMemoryTokenBucket(
				McpTokenBucketConfig.withCapacity(1L)
						.refillTokens(1L)
						.refillPeriod(Duration.ofDays(1))
						.build());

		McpRateLimitContext request = context(
				"/one", "principal", McpRateLimitTarget.REQUEST);
		Assertions.assertInstanceOf(McpRateLimitDecision.Allowed.class,
				limiter.acquire(request));
		McpRateLimitDecision.Denied denied = Assertions.assertInstanceOf(
				McpRateLimitDecision.Denied.class, limiter.acquire(request));
		Assertions.assertFalse(denied.getRetryAfter().isZero());
		Assertions.assertFalse(denied.getRetryAfter().isNegative());

		Assertions.assertInstanceOf(McpRateLimitDecision.Allowed.class,
				limiter.acquire(context(
						"/one", "principal", McpRateLimitTarget.TOOL)));
		Assertions.assertInstanceOf(McpRateLimitDecision.Allowed.class,
				limiter.acquire(context(
						"/one", "another-principal", McpRateLimitTarget.REQUEST)));
		Assertions.assertInstanceOf(McpRateLimitDecision.Allowed.class,
				limiter.acquire(context(
						"/two", "principal", McpRateLimitTarget.REQUEST)));
	}

	@Test
	public void tokenBucketConfigurationIsFiniteAndValidated() {
		McpTokenBucketConfig defaults = McpTokenBucketConfig.fromDefaults();
		Assertions.assertEquals(Long.valueOf(20), defaults.getCapacity());
		Assertions.assertEquals(Long.valueOf(60), defaults.getRefillTokens());
		Assertions.assertEquals(Duration.ofMinutes(1), defaults.getRefillPeriod());

		Assertions.assertThrows(IllegalArgumentException.class, () ->
				McpTokenBucketConfig.withCapacity(0L)
						.refillTokens(1L)
						.refillPeriod(Duration.ofSeconds(1))
						.build());
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				McpTokenBucketConfig.withCapacity(1L)
						.refillTokens(0L)
						.refillPeriod(Duration.ofSeconds(1))
						.build());
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				McpTokenBucketConfig.withCapacity(1L)
						.refillTokens(1L)
						.refillPeriod(Duration.ZERO)
						.build());
		Assertions.assertThrows(NullPointerException.class, () ->
				McpTokenBucketConfig.withCapacity(1L)
						.refillTokens(1L)
						.build());
		Assertions.assertThrows(NullPointerException.class,
				() -> McpTokenBucketConfig.withCapacity(null));
		Assertions.assertThrows(NullPointerException.class,
				() -> McpTokenBucketConfig.withCapacity(1L).refillTokens(null));
	}

	@Test
	public void rateLimiterRegistryIsImmutableAndRejectsBadNames() {
		McpRateLimiter limiter = context -> McpRateLimitDecision.allowed();
		McpRateLimiterRegistry registry = McpRateLimiterRegistry.builder()
				.rateLimiter("shared", limiter)
				.build();

		Assertions.assertSame(limiter, registry.find("shared").orElseThrow());
		Assertions.assertTrue(registry.find("missing").isEmpty());
		Assertions.assertThrows(UnsupportedOperationException.class, () ->
				registry.getRateLimiters().put("another", limiter));
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				McpRateLimiterRegistry.builder().rateLimiter(" ", limiter));
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				McpRateLimiterRegistry.builder()
						.rateLimiter("shared", limiter)
						.rateLimiter("shared", limiter));
	}

	@Test
	public void deniedDecisionRejectsNegativeRetryDelay() {
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				McpRateLimitDecision.denied(Duration.ofNanos(-1)));
		Assertions.assertEquals(Duration.ZERO,
				McpRateLimitDecision.denied(Duration.ZERO).getRetryAfter());
	}

	@Test
	public void deterministicClockProvidesExactRefillAndRetryBoundaries()
			throws Exception {
		MutableClock clock = new MutableClock(0);
		DefaultMcpRateLimiter limiter = new DefaultMcpRateLimiter(
				McpTokenBucketConfig.withCapacity(1L)
						.refillTokens(3L)
						.refillPeriod(Duration.ofNanos(10))
						.build(), clock, 8);
		McpRateLimitContext context = context(
				"/one", "principal", McpRateLimitTarget.REQUEST);

		Assertions.assertInstanceOf(McpRateLimitDecision.Allowed.class,
				limiter.acquire(context));
		Assertions.assertEquals(Duration.ofNanos(4),
				Assertions.assertInstanceOf(McpRateLimitDecision.Denied.class,
						limiter.acquire(context)).getRetryAfter());
		clock.set(3);
		Assertions.assertEquals(Duration.ofNanos(1),
				Assertions.assertInstanceOf(McpRateLimitDecision.Denied.class,
						limiter.acquire(context)).getRetryAfter());
		clock.set(4);
		Assertions.assertInstanceOf(McpRateLimitDecision.Allowed.class,
				limiter.acquire(context));
	}

	@Test
	public void deterministicClockHandlesSignedNanoTimeWrap() throws Exception {
		MutableClock clock = new MutableClock(Long.MAX_VALUE - 5);
		DefaultMcpRateLimiter limiter = new DefaultMcpRateLimiter(
				McpTokenBucketConfig.withCapacity(1L)
						.refillTokens(1L)
						.refillPeriod(Duration.ofNanos(10))
						.build(), clock, 8);
		McpRateLimitContext context = context(
				"/one", "principal", McpRateLimitTarget.REQUEST);

		Assertions.assertInstanceOf(McpRateLimitDecision.Allowed.class,
				limiter.acquire(context));
		Assertions.assertInstanceOf(McpRateLimitDecision.Denied.class,
				limiter.acquire(context));
		clock.set(Long.MIN_VALUE + 4);
		Assertions.assertInstanceOf(McpRateLimitDecision.Allowed.class,
				limiter.acquire(context));
	}

	@Test
	public void retainedPartitionsAreBoundedReclaimedAndResettable()
			throws Exception {
		MutableClock clock = new MutableClock(0);
		DefaultMcpRateLimiter limiter = new DefaultMcpRateLimiter(
				McpTokenBucketConfig.withCapacity(1L)
						.refillTokens(1L)
						.refillPeriod(Duration.ofNanos(10))
						.build(), clock, 2);

		Assertions.assertInstanceOf(McpRateLimitDecision.Allowed.class,
				limiter.acquire(context(
						"/one", "one", McpRateLimitTarget.REQUEST)));
		Assertions.assertInstanceOf(McpRateLimitDecision.Allowed.class,
				limiter.acquire(context(
						"/one", "two", McpRateLimitTarget.REQUEST)));
		McpRateLimitDecision.Denied saturated = Assertions.assertInstanceOf(
				McpRateLimitDecision.Denied.class, limiter.acquire(context(
						"/one", "three", McpRateLimitTarget.REQUEST)));
		Assertions.assertEquals(Duration.ofNanos(10), saturated.getRetryAfter());
		Assertions.assertEquals(2, limiter.retainedPartitionCount());

		clock.set(10);
		Assertions.assertInstanceOf(McpRateLimitDecision.Allowed.class,
				limiter.acquire(context(
						"/one", "three", McpRateLimitTarget.REQUEST)));
		Assertions.assertEquals(2, limiter.retainedPartitionCount());

		limiter.reset();
		Assertions.assertEquals(0, limiter.retainedPartitionCount());
		Assertions.assertInstanceOf(McpRateLimitDecision.Allowed.class,
				limiter.acquire(context(
						"/one", "three", McpRateLimitTarget.REQUEST)));
	}

	@Test
	public void samePartitionAcquisitionsAreLinearizableUnderConcurrency()
			throws Exception {
		MutableClock clock = new MutableClock(0);
		DefaultMcpRateLimiter limiter = new DefaultMcpRateLimiter(
				McpTokenBucketConfig.withCapacity(100L)
						.refillTokens(1L)
						.refillPeriod(Duration.ofDays(1))
						.build(), clock, 8);
		McpRateLimitContext context = context(
				"/one", "principal", McpRateLimitTarget.REQUEST);
		ExecutorService executor = Executors.newFixedThreadPool(16);
		try {
			List<Callable<McpRateLimitDecision>> tasks = java.util.stream.IntStream
					.range(0, 400)
					.mapToObj(ignored -> (Callable<McpRateLimitDecision>) () ->
							limiter.acquire(context))
					.toList();
			long allowed = executor.invokeAll(tasks).stream()
					.map(future -> {
						try {
							return future.get();
						} catch (Exception exception) {
							throw new AssertionError(exception);
						}
					})
					.filter(McpRateLimitDecision.Allowed.class::isInstance)
					.count();
			Assertions.assertEquals(100, allowed);
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	public void maximumLongRefillQuantityUsesExactAccounting() throws Exception {
		MutableClock clock = new MutableClock(0);
		DefaultMcpRateLimiter limiter = new DefaultMcpRateLimiter(
				McpTokenBucketConfig.withCapacity(Long.MAX_VALUE)
						.refillTokens(Long.MAX_VALUE)
						.refillPeriod(Duration.ofNanos(Long.MAX_VALUE))
						.build(), clock, 8);
		McpRateLimitContext context = context(
				"/one", "principal", McpRateLimitTarget.REQUEST);

		Assertions.assertInstanceOf(McpRateLimitDecision.Allowed.class,
				limiter.acquire(context));
		clock.set(1);
		Assertions.assertInstanceOf(McpRateLimitDecision.Allowed.class,
				limiter.acquire(context));
	}

	private static McpRateLimitContext context(String endpointPath,
			String partitionKey, McpRateLimitTarget target) {
		McpEndpoint endpoint = McpEndpoint.withPath(endpointPath)
				.serverInformation(McpImplementation.withNameAndVersion(
						"test", "1").build())
				.build();
		McpAdmissionIdentity identity = McpAdmissionIdentity
				.withRateLimitPartitionKey(partitionKey)
				.build();
		Request request = Request.fromPath(HttpMethod.POST, endpointPath);
		return new McpRateLimitContext() {
			@Override
			public Request getRequest() {
				return request;
			}

			@Override
			public McpEndpoint getEndpoint() {
				return endpoint;
			}

			@Override
			public McpAdmissionIdentity getAdmissionIdentity() {
				return identity;
			}

			@Override
			public McpRateLimitTarget getTarget() {
				return target;
			}

			@Override
			public String getJsonRpcMethod() {
				return "tools/call";
			}

			@Override
			public Optional<String> getOperationName() {
				return Optional.of("add");
			}
		};
	}

	private static final class MutableClock implements McpRateLimiterClock {
		private final AtomicLong nowNanos;

		private MutableClock(long nowNanos) {
			this.nowNanos = new AtomicLong(nowNanos);
		}

		@Override
		public long nanoTime() {
			return this.nowNanos.get();
		}

		private void set(long nowNanos) {
			this.nowNanos.set(nowNanos);
		}
	}
}
