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
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import static java.util.Objects.requireNonNull;

/**
 * Contract for collecting operational metrics from Soklet.
 * <p>
 * Implementations might bridge to external systems (OpenTelemetry, Micrometer, Prometheus, etc.)
 * <p>
 * <p>All methods must be:
 * <ul>
 *   <li><strong>Thread-safe</strong> — called concurrently from multiple request threads</li>
 *   <li><strong>Non-blocking</strong> — should not perform I/O or acquire locks that might contend</li>
 *   <li><strong>Failure-tolerant</strong> — exceptions are caught and logged, never break request handling</li>
 * </ul>
 * <p>
 * See <a href="https://www.soklet.com/docs/metrics-collection">https://www.soklet.com/docs/metrics-collection</a> for detailed documentation.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public interface MetricsCollector {
	/**
	 * Called as soon as a request is received and a <em>Resource Method</em> has been resolved to handle it.
	 */
	default void didStartRequestHandling(@NonNull Request request,
																			 @Nullable ResourceMethod resourceMethod) {
		// No-op by default
	}

	/**
	 * Called after a request finishes processing.
	 */
	default void didFinishRequestHandling(@NonNull Request request,
																				@Nullable ResourceMethod resourceMethod,
																				@NonNull MarshaledResponse marshaledResponse,
																				@NonNull Duration duration,
																				@NonNull List<@NonNull Throwable> throwables) {
		// No-op by default
	}

	/**
	 * Called before response data is written.
	 */
	default void willWriteResponse(@NonNull Request request,
																 @Nullable ResourceMethod resourceMethod,
																 @NonNull MarshaledResponse marshaledResponse) {
		// No-op by default
	}

	/**
	 * Called after response data is written.
	 */
	default void didWriteResponse(@NonNull Request request,
																@Nullable ResourceMethod resourceMethod,
																@NonNull MarshaledResponse marshaledResponse,
																@NonNull Duration responseWriteDuration) {
		// No-op by default
	}

	/**
	 * Called after response data fails to write.
	 */
	default void didFailToWriteResponse(@NonNull Request request,
																			@Nullable ResourceMethod resourceMethod,
																			@NonNull MarshaledResponse marshaledResponse,
																			@NonNull Duration responseWriteDuration,
																			@NonNull Throwable throwable) {
		// No-op by default
	}

	/**
	 * Called before an SSE connection is established.
	 */
	default void willEstablishServerSentEventConnection(@NonNull Request request,
																											@Nullable ResourceMethod resourceMethod) {
		// No-op by default
	}

	/**
	 * Called after an SSE connection is established.
	 */
	default void didEstablishServerSentEventConnection(@NonNull ServerSentEventConnection serverSentEventConnection) {
		// No-op by default
	}

	/**
	 * Called if an SSE connection fails to establish.
	 */
	default void didFailToEstablishServerSentEventConnection(@NonNull Request request,
																													 @Nullable ResourceMethod resourceMethod,
																													 @NonNull Throwable throwable) {
		// No-op by default
	}

	/**
	 * Called before an SSE connection is terminated.
	 */
	default void willTerminateServerSentEventConnection(@NonNull ServerSentEventConnection serverSentEventConnection,
																											ServerSentEventConnection.@NonNull TerminationReason terminationReason,
																											@Nullable Throwable throwable) {
		// No-op by default
	}

	/**
	 * Called after an SSE connection is terminated.
	 */
	default void didTerminateServerSentEventConnection(@NonNull ServerSentEventConnection serverSentEventConnection,
																										 @NonNull Duration connectionDuration,
																										 ServerSentEventConnection.@NonNull TerminationReason terminationReason,
																										 @Nullable Throwable throwable) {
		// No-op by default
	}

	/**
	 * Called before an SSE event is written.
	 */
	default void willWriteServerSentEvent(@NonNull ServerSentEventConnection serverSentEventConnection,
																				@NonNull ServerSentEvent serverSentEvent) {
		// No-op by default
	}

	/**
	 * Called after an SSE event is written.
	 *
	 * @param serverSentEventConnection the connection the event was written to
	 * @param serverSentEvent           the event that was written
	 * @param writeDuration             how long it took to write the event
	 * @param deliveryLag               elapsed time between enqueue and write start, or {@code null} if unknown
	 * @param payloadBytes              size of the serialized payload in bytes, or {@code null} if unknown
	 * @param queueDepth                number of queued elements remaining at write time, or {@code null} if unknown
	 */
	default void didWriteServerSentEvent(@NonNull ServerSentEventConnection serverSentEventConnection,
																			 @NonNull ServerSentEvent serverSentEvent,
																			 @NonNull Duration writeDuration,
																			 @Nullable Duration deliveryLag,
																			 @Nullable Integer payloadBytes,
																			 @Nullable Integer queueDepth) {
		// No-op by default
	}

	/**
	 * Called after an SSE event fails to write.
	 *
	 * @param serverSentEventConnection the connection the event was written to
	 * @param serverSentEvent           the event that was written
	 * @param writeDuration             how long it took to attempt the write
	 * @param throwable                 the failure cause
	 * @param deliveryLag               elapsed time between enqueue and write start, or {@code null} if unknown
	 * @param payloadBytes              size of the serialized payload in bytes, or {@code null} if unknown
	 * @param queueDepth                number of queued elements remaining at write time, or {@code null} if unknown
	 */
	default void didFailToWriteServerSentEvent(@NonNull ServerSentEventConnection serverSentEventConnection,
																						 @NonNull ServerSentEvent serverSentEvent,
																						 @NonNull Duration writeDuration,
																						 @NonNull Throwable throwable,
																						 @Nullable Duration deliveryLag,
																						 @Nullable Integer payloadBytes,
																						 @Nullable Integer queueDepth) {
		// No-op by default
	}

	/**
	 * Called before an SSE comment is written.
	 */
	default void willWriteServerSentEventComment(@NonNull ServerSentEventConnection serverSentEventConnection,
																							 @NonNull String comment,
																							 @NonNull ServerSentEventCommentKind commentKind) {
		// No-op by default
	}

	/**
	 * Called after an SSE comment is written.
	 *
	 * @param serverSentEventConnection the connection the comment was written to
	 * @param comment                   the comment that was written
	 * @param commentKind               whether the comment is a heartbeat or application comment
	 * @param writeDuration             how long it took to write the comment
	 * @param deliveryLag               elapsed time between enqueue and write start, or {@code null} if unknown
	 * @param payloadBytes              size of the serialized payload in bytes, or {@code null} if unknown
	 * @param queueDepth                number of queued elements remaining at write time, or {@code null} if unknown
	 */
	default void didWriteServerSentEventComment(@NonNull ServerSentEventConnection serverSentEventConnection,
																							@NonNull String comment,
																							@NonNull ServerSentEventCommentKind commentKind,
																							@NonNull Duration writeDuration,
																							@Nullable Duration deliveryLag,
																							@Nullable Integer payloadBytes,
																							@Nullable Integer queueDepth) {
		// No-op by default
	}

	/**
	 * Called after an SSE comment fails to write.
	 *
	 * @param serverSentEventConnection the connection the comment was written to
	 * @param comment                   the comment that was written
	 * @param commentKind               whether the comment is a heartbeat or application comment
	 * @param writeDuration             how long it took to attempt the write
	 * @param throwable                 the failure cause
	 * @param deliveryLag               elapsed time between enqueue and write start, or {@code null} if unknown
	 * @param payloadBytes              size of the serialized payload in bytes, or {@code null} if unknown
	 * @param queueDepth                number of queued elements remaining at write time, or {@code null} if unknown
	 */
	default void didFailToWriteServerSentEventComment(@NonNull ServerSentEventConnection serverSentEventConnection,
																										@NonNull String comment,
																										@NonNull ServerSentEventCommentKind commentKind,
																										@NonNull Duration writeDuration,
																										@NonNull Throwable throwable,
																										@Nullable Duration deliveryLag,
																										@Nullable Integer payloadBytes,
																										@Nullable Integer queueDepth) {
		// No-op by default
	}

	/**
	 * Returns a snapshot of metrics collected so far, if supported.
	 *
	 * @return an optional metrics snapshot
	 */
	@NonNull
	default Optional<MetricsSnapshot> snapshot() {
		return Optional.empty();
	}

	/**
	 * Resets any in-memory metrics state, if supported.
	 */
	default void reset() {
		// No-op by default
	}

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

		public Histogram(@NonNull long[] bucketBoundaries) {
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

		public void record(long value) {
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
		public Snapshot snapshot() {
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

			return new Snapshot(boundariesWithOverflow, cumulativeCounts, countSnapshot, sumSnapshot, minSnapshot, maxSnapshot);
		}

		public void reset() {
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

	/**
	 * Immutable snapshot of a {@link Histogram}.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	final class Snapshot {
		@NonNull
		private final long[] bucketBoundaries;
		@NonNull
		private final long[] bucketCumulativeCounts;
		private final long count;
		private final long sum;
		private final long min;
		private final long max;

		public Snapshot(@NonNull long[] bucketBoundaries,
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

	/**
	 * Indicates whether a request was matched to a {@link ResourcePathDeclaration}.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	enum RouteKind {
		/**
		 * The request matched a {@link ResourcePathDeclaration}.
		 */
		MATCHED,
		/**
		 * The request did not match any {@link ResourcePathDeclaration}.
		 */
		UNMATCHED
	}

	/**
	 * Key for metrics grouped by HTTP method and route match information.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	record ServerRouteKey(@NonNull HttpMethod method,
														@NonNull RouteKind routeKind,
														@Nullable ResourcePathDeclaration route) {
		public ServerRouteKey {
			requireNonNull(method);
			requireNonNull(routeKind);
			if (routeKind == RouteKind.MATCHED && route == null)
				throw new IllegalArgumentException("Route must be provided when RouteKind is MATCHED");
			if (routeKind == RouteKind.UNMATCHED && route != null)
				throw new IllegalArgumentException("Route must be null when RouteKind is UNMATCHED");
		}
	}

	/**
	 * Key for metrics grouped by HTTP method, route match information, and status class (e.g. 2xx).
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	record ServerRouteStatusKey(@NonNull HttpMethod method,
																	@NonNull RouteKind routeKind,
																	@Nullable ResourcePathDeclaration route,
																	@NonNull String statusClass) {
		public ServerRouteStatusKey {
			requireNonNull(method);
			requireNonNull(routeKind);
			if (routeKind == RouteKind.MATCHED && route == null)
				throw new IllegalArgumentException("Route must be provided when RouteKind is MATCHED");
			if (routeKind == RouteKind.UNMATCHED && route != null)
				throw new IllegalArgumentException("Route must be null when RouteKind is UNMATCHED");
			requireNonNull(statusClass);
		}
	}

	/**
	 * Indicates whether a Server-Sent Event comment is a keep-alive heartbeat or an application comment.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	enum ServerSentEventCommentKind {
		/**
		 * Application-provided comment.
		 */
		COMMENT,
		/**
		 * Keep-alive/heartbeat comment.
		 */
		HEARTBEAT
	}

	/**
	 * Key for metrics grouped by Server-Sent Event comment kind and route match information.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	record ServerSentEventCommentRouteKey(@NonNull RouteKind routeKind,
																				@Nullable ResourcePathDeclaration route,
																				@NonNull ServerSentEventCommentKind commentKind) {
		public ServerSentEventCommentRouteKey {
			requireNonNull(routeKind);
			requireNonNull(commentKind);
			if (routeKind == RouteKind.MATCHED && route == null)
				throw new IllegalArgumentException("Route must be provided when RouteKind is MATCHED");
			if (routeKind == RouteKind.UNMATCHED && route != null)
				throw new IllegalArgumentException("Route must be null when RouteKind is UNMATCHED");
		}
	}

	/**
	 * Key for metrics grouped by Server-Sent Event route match information.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	record ServerSentEventRouteKey(@NonNull RouteKind routeKind,
																 @Nullable ResourcePathDeclaration route) {
		public ServerSentEventRouteKey {
			requireNonNull(routeKind);
			if (routeKind == RouteKind.MATCHED && route == null)
				throw new IllegalArgumentException("Route must be provided when RouteKind is MATCHED");
			if (routeKind == RouteKind.UNMATCHED && route != null)
				throw new IllegalArgumentException("Route must be null when RouteKind is UNMATCHED");
		}
	}

	/**
	 * Key for metrics grouped by Server-Sent Event route match information and termination reason.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	record ServerSentEventRouteTerminationKey(@NonNull RouteKind routeKind,
																						@Nullable ResourcePathDeclaration route,
																						ServerSentEventConnection.@NonNull TerminationReason terminationReason) {
		public ServerSentEventRouteTerminationKey {
			requireNonNull(routeKind);
			if (routeKind == RouteKind.MATCHED && route == null)
				throw new IllegalArgumentException("Route must be provided when RouteKind is MATCHED");
			if (routeKind == RouteKind.UNMATCHED && route != null)
				throw new IllegalArgumentException("Route must be null when RouteKind is UNMATCHED");
			requireNonNull(terminationReason);
		}
	}

	/**
	 * Acquires a threadsafe {@link MetricsCollector} instance with sensible defaults.
	 * <p>
	 * This method is guaranteed to return a new instance.
	 *
	 * @return a {@code MetricsCollector} with default settings
	 */
	@NonNull
	static MetricsCollector withDefaults() {
		return DefaultMetricsCollector.withDefaults();
	}
}
