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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Predicate;

import static java.util.Objects.requireNonNull;

/**
 * Contract for collecting operational metrics from Soklet.
 * <p>
 * Soklet's standard implementation, available via {@link #withDefaults()}, supports detailed histogram collection with
 * immutable snapshots (via {@link #snapshot()}) and provides Prometheus/OpenMetrics export helpers for convenience.
 * To disable metrics collection without a custom implementation, use {@link #disabled()}.
 * <p>
 * If you prefer OpenTelemetry, Micrometer, or another metrics system for monitoring, you might choose to create your own
 * implementation of this interface.
 * <p>
 * <p>All methods must be:
 * <ul>
 *   <li><strong>Thread-safe</strong> — called concurrently from multiple request threads</li>
 *   <li><strong>Non-blocking</strong> — should not perform I/O or acquire locks that might contend</li>
 *   <li><strong>Failure-tolerant</strong> — exceptions are caught and logged, never break request handling</li>
 * </ul>
 * <p>
 * Example usage:
 * <pre><code>
 * {@literal @}GET("/metrics")
 * public MarshaledResponse getMetrics(@NonNull MetricsCollector metricsCollector) {
 *   SnapshotTextOptions options = SnapshotTextOptions
 *     .withMetricsFormat(MetricsFormat.PROMETHEUS)
 *     .build();
 *
 *   String body = metricsCollector.snapshotText(options).orElse(null);
 *
 *   if (body == null)
 *     return MarshaledResponse.withStatusCode(204).build();
 *
 *   return MarshaledResponse.withStatusCode(200)
 *     .headers(Map.of("Content-Type", Set.of("text/plain; charset=UTF-8")))
 *     .body(body.getBytes(StandardCharsets.UTF_8))
 *     .build();
 * }
 * </code></pre>
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
																							 @NonNull ServerSentEventComment serverSentEventComment) {
		// No-op by default
	}

	/**
	 * Called after an SSE comment is written.
	 *
	 * @param serverSentEventConnection the connection the comment was written to
	 * @param serverSentEventComment    the comment that was written
	 * @param writeDuration             how long it took to write the comment
	 * @param deliveryLag               elapsed time between enqueue and write start, or {@code null} if unknown
	 * @param payloadBytes              size of the serialized payload in bytes, or {@code null} if unknown
	 * @param queueDepth                number of queued elements remaining at write time, or {@code null} if unknown
	 */
	default void didWriteServerSentEventComment(@NonNull ServerSentEventConnection serverSentEventConnection,
																							@NonNull ServerSentEventComment serverSentEventComment,
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
	 * @param serverSentEventComment    the comment that was written
	 * @param writeDuration             how long it took to attempt the write
	 * @param throwable                 the failure cause
	 * @param deliveryLag               elapsed time between enqueue and write start, or {@code null} if unknown
	 * @param payloadBytes              size of the serialized payload in bytes, or {@code null} if unknown
	 * @param queueDepth                number of queued elements remaining at write time, or {@code null} if unknown
	 */
	default void didFailToWriteServerSentEventComment(@NonNull ServerSentEventConnection serverSentEventConnection,
																										@NonNull ServerSentEventComment serverSentEventComment,
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
	 * Returns a text snapshot of metrics collected so far, if supported.
	 * <p>
	 * The default collector supports Prometheus and OpenMetrics text exposition formats.
	 *
	 * @param options the snapshot rendering options
	 * @return a textual metrics snapshot, or {@link Optional#empty()} if unsupported
	 */
	@NonNull
	default Optional<String> snapshotText(@NonNull SnapshotTextOptions options) {
		requireNonNull(options);
		return Optional.empty();
	}

	/**
	 * Resets any in-memory metrics state, if supported.
	 */
	default void reset() {
		// No-op by default
	}

	/**
	 * Text format to use for {@link #snapshotText(SnapshotTextOptions)}.
	 * <p>
	 * This controls serialization only; the underlying metrics data is unchanged.
	 */
	enum MetricsFormat {
		/**
		 * Prometheus text exposition format.
		 */
		PROMETHEUS,
		/**
		 * OpenMetrics text exposition format, including the {@code # EOF} trailer.
		 */
		OPEN_METRICS
	}

	/**
	 * Options for rendering a textual metrics snapshot.
	 * <p>
	 * Use {@link #withMetricsFormat(MetricsFormat)} to obtain a builder and customize output.
	 * <p>
	 * Key options:
	 * <ul>
	 *   <li>{@code metricFilter} allows per-sample filtering by name and labels</li>
	 *   <li>{@code histogramFormat} controls bucket vs count/sum output</li>
	 *   <li>{@code includeZeroBuckets} drops empty bucket samples when false</li>
	 * </ul>
	 */
	@ThreadSafe
	final class SnapshotTextOptions {
		@NonNull
		private final MetricsFormat metricsFormat;
		@Nullable
		private final Predicate<MetricSample> metricFilter;
		@NonNull
		private final HistogramFormat histogramFormat;
		@NonNull
		private final Boolean includeZeroBuckets;

		private SnapshotTextOptions(@NonNull Builder builder) {
			requireNonNull(builder);

			this.metricsFormat = requireNonNull(builder.metricsFormat);
			this.metricFilter = builder.metricFilter;
			this.histogramFormat = requireNonNull(builder.histogramFormat);
			this.includeZeroBuckets = builder.includeZeroBuckets == null ? true : builder.includeZeroBuckets;
		}

		/**
		 * Begins building options with the specified format.
		 *
		 * @param metricsFormat the text exposition format
		 * @return a builder seeded with the format
		 */
		@NonNull
		public static Builder withMetricsFormat(@NonNull MetricsFormat metricsFormat) {
			return new Builder(metricsFormat);
		}

		/**
		 * The text exposition format to emit.
		 *
		 * @return the metrics format
		 */
		@NonNull
		public MetricsFormat getMetricsFormat() {
			return this.metricsFormat;
		}

		/**
		 * Optional filter for rendered samples.
		 *
		 * @return the filter, if present
		 */
		@NonNull
		public Optional<Predicate<MetricSample>> getMetricFilter() {
			return Optional.ofNullable(this.metricFilter);
		}

		/**
		 * The histogram rendering strategy.
		 *
		 * @return the histogram format
		 */
		@NonNull
		public HistogramFormat getHistogramFormat() {
			return this.histogramFormat;
		}

		/**
		 * Whether zero-count buckets should be emitted.
		 *
		 * @return {@code true} if zero-count buckets are included
		 */
		@NonNull
		public Boolean getIncludeZeroBuckets() {
			return this.includeZeroBuckets;
		}

		/**
		 * Supported histogram rendering strategies.
		 */
		public enum HistogramFormat {
			/**
			 * Emit full histogram series (buckets, count, and sum).
			 */
			FULL_BUCKETS,
			/**
			 * Emit only {@code _count} and {@code _sum} samples (omit buckets).
			 */
			COUNT_SUM_ONLY,
			/**
			 * Suppress histogram output entirely.
			 */
			NONE
		}

		/**
		 * A single text-format sample with its label set.
		 * <p>
		 * Filters receive these instances for each rendered sample. For histogram buckets,
		 * the sample name includes {@code _bucket} and the labels include {@code le}.
		 * Label maps are immutable and preserve insertion order.
		 */
		public static final class MetricSample {
			@NonNull
			private final String name;
			@NonNull
			private final Map<@NonNull String, @NonNull String> labels;

			/**
			 * Creates a metrics sample definition.
			 *
			 * @param name   the sample name (e.g. {@code soklet_http_request_duration_nanos_bucket})
			 * @param labels the sample labels
			 */
			public MetricSample(@NonNull String name,
													@NonNull Map<@NonNull String, @NonNull String> labels) {
				this.name = requireNonNull(name);
				this.labels = Collections.unmodifiableMap(new LinkedHashMap<>(requireNonNull(labels)));
			}

			/**
			 * The name for this sample.
			 *
			 * @return the sample name
			 */
			@NonNull
			public String getName() {
				return this.name;
			}

			/**
			 * The label set for this sample.
			 *
			 * @return immutable labels
			 */
			@NonNull
			public Map<@NonNull String, @NonNull String> getLabels() {
				return this.labels;
			}
		}

		/**
		 * Builder for {@link SnapshotTextOptions}.
		 * <p>
		 * Defaults are {@link HistogramFormat#FULL_BUCKETS} and {@code includeZeroBuckets=true}.
		 */
		@ThreadSafe
		public static final class Builder {
			@NonNull
			private final MetricsFormat metricsFormat;
			@Nullable
			private Predicate<MetricSample> metricFilter;
			@NonNull
			private HistogramFormat histogramFormat;
			@Nullable
			private Boolean includeZeroBuckets;

			private Builder(@NonNull MetricsFormat metricsFormat) {
				this.metricsFormat = requireNonNull(metricsFormat);
				this.histogramFormat = HistogramFormat.FULL_BUCKETS;
				this.includeZeroBuckets = true;
			}

			/**
			 * Sets an optional per-sample filter.
			 *
			 * @param metricFilter the filter to apply, or {@code null} to disable filtering
			 * @return this builder
			 */
			@NonNull
			public Builder metricFilter(@Nullable Predicate<MetricSample> metricFilter) {
				this.metricFilter = metricFilter;
				return this;
			}

			/**
			 * Sets how histograms are rendered in the text snapshot.
			 *
			 * @param histogramFormat the histogram format
			 * @return this builder
			 */
			@NonNull
			public Builder histogramFormat(@NonNull HistogramFormat histogramFormat) {
				this.histogramFormat = requireNonNull(histogramFormat);
				return this;
			}

			/**
			 * Controls whether zero-count buckets are emitted.
			 *
			 * @param includeZeroBuckets {@code true} to include zero-count buckets, {@code false} to omit them
			 * @return this builder
			 */
			@NonNull
			public Builder includeZeroBuckets(@Nullable Boolean includeZeroBuckets) {
				this.includeZeroBuckets = includeZeroBuckets;
				return this;
			}

			/**
			 * Builds a {@link SnapshotTextOptions} instance.
			 *
			 * @return the built options
			 */
			@NonNull
			public SnapshotTextOptions build() {
				return new SnapshotTextOptions(this);
			}
		}
	}

	/**
	 * A thread-safe histogram with fixed bucket boundaries.
	 * <p>
	 * Negative values are ignored. Buckets use inclusive upper bounds, and snapshots include
	 * an overflow bucket represented by a {@link Snapshot#getBucketBoundary(int)} of
	 * {@link Long#MAX_VALUE}.
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

		/**
		 * Creates a histogram with the provided bucket boundaries.
		 *
		 * @param bucketBoundaries inclusive upper bounds for buckets
		 */
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

		/**
		 * Records a value into the histogram.
		 *
		 * @param value the value to record
		 */
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

		/**
		 * Captures an immutable snapshot of the histogram.
		 *
		 * @return the histogram snapshot
		 */
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

		/**
		 * Resets all counts and min/max values.
		 */
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
	 * <p>
	 * Bucket counts are cumulative. Boundaries are inclusive upper bounds, and the final
	 * boundary is {@link Long#MAX_VALUE} to represent the overflow bucket. Units are the same
	 * as values passed to {@link Histogram#record(long)}.
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

		/**
		 * Creates an immutable histogram snapshot.
		 *
		 * @param bucketBoundaries       inclusive upper bounds for buckets, including overflow
		 * @param bucketCumulativeCounts cumulative counts for each bucket
		 * @param count                  total number of samples recorded
		 * @param sum                    sum of all recorded values
		 * @param min                    smallest recorded value (or 0 if none)
		 * @param max                    largest recorded value (or 0 if none)
		 */
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

		/**
		 * Number of histogram buckets, including the overflow bucket.
		 *
		 * @return the bucket count
		 */
		public int getBucketCount() {
			return this.bucketBoundaries.length;
		}

		/**
		 * The inclusive upper bound for the bucket at the given index.
		 *
		 * @param index the bucket index
		 * @return the bucket boundary
		 */
		public long getBucketBoundary(int index) {
			return this.bucketBoundaries[index];
		}

		/**
		 * The cumulative count for the bucket at the given index.
		 *
		 * @param index the bucket index
		 * @return the cumulative count
		 */
		public long getBucketCumulativeCount(int index) {
			return this.bucketCumulativeCounts[index];
		}

		/**
		 * Total number of recorded values.
		 *
		 * @return the count
		 */
		public long getCount() {
			return this.count;
		}

		/**
		 * Sum of all recorded values.
		 *
		 * @return the sum
		 */
		public long getSum() {
			return this.sum;
		}

		/**
		 * Smallest recorded value, or 0 if no values were recorded.
		 *
		 * @return the minimum value
		 */
		public long getMin() {
			return this.min;
		}

		/**
		 * Largest recorded value, or 0 if no values were recorded.
		 *
		 * @return the maximum value
		 */
		public long getMax() {
			return this.max;
		}

		/**
		 * Returns an approximate percentile based on bucket boundaries.
		 *
		 * @param percentile percentile between 0 and 100
		 * @return the approximated percentile value
		 */
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
	enum RouteType {
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
												@NonNull RouteType routeType,
												@Nullable ResourcePathDeclaration route) {
		public ServerRouteKey {
			requireNonNull(method);
			requireNonNull(routeType);
			if (routeType == RouteType.MATCHED && route == null)
				throw new IllegalArgumentException("Route must be provided when RouteType is MATCHED");
			if (routeType == RouteType.UNMATCHED && route != null)
				throw new IllegalArgumentException("Route must be null when RouteType is UNMATCHED");
		}
	}

	/**
	 * Key for metrics grouped by HTTP method, route match information, and status class (e.g. 2xx).
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	record ServerRouteStatusKey(@NonNull HttpMethod method,
															@NonNull RouteType routeType,
															@Nullable ResourcePathDeclaration route,
															@NonNull String statusClass) {
		public ServerRouteStatusKey {
			requireNonNull(method);
			requireNonNull(routeType);
			if (routeType == RouteType.MATCHED && route == null)
				throw new IllegalArgumentException("Route must be provided when RouteType is MATCHED");
			if (routeType == RouteType.UNMATCHED && route != null)
				throw new IllegalArgumentException("Route must be null when RouteType is UNMATCHED");
			requireNonNull(statusClass);
		}
	}

	/**
	 * Key for metrics grouped by Server-Sent Event comment type and route match information.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	record ServerSentEventCommentRouteKey(@NonNull RouteType routeType,
																				@Nullable ResourcePathDeclaration route,
																				ServerSentEventComment.@NonNull CommentType commentType) {
		public ServerSentEventCommentRouteKey {
			requireNonNull(routeType);
			requireNonNull(commentType);
			if (routeType == RouteType.MATCHED && route == null)
				throw new IllegalArgumentException("Route must be provided when RouteType is MATCHED");
			if (routeType == RouteType.UNMATCHED && route != null)
				throw new IllegalArgumentException("Route must be null when RouteType is UNMATCHED");
		}
	}

	/**
	 * Key for metrics grouped by Server-Sent Event route match information.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	record ServerSentEventRouteKey(@NonNull RouteType routeType,
																 @Nullable ResourcePathDeclaration route) {
		public ServerSentEventRouteKey {
			requireNonNull(routeType);
			if (routeType == RouteType.MATCHED && route == null)
				throw new IllegalArgumentException("Route must be provided when RouteType is MATCHED");
			if (routeType == RouteType.UNMATCHED && route != null)
				throw new IllegalArgumentException("Route must be null when RouteType is UNMATCHED");
		}
	}

	/**
	 * Key for metrics grouped by Server-Sent Event route match information and termination reason.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	record ServerSentEventRouteTerminationKey(@NonNull RouteType routeType,
																						@Nullable ResourcePathDeclaration route,
																						ServerSentEventConnection.@NonNull TerminationReason terminationReason) {
		public ServerSentEventRouteTerminationKey {
			requireNonNull(routeType);
			if (routeType == RouteType.MATCHED && route == null)
				throw new IllegalArgumentException("Route must be provided when RouteType is MATCHED");
			if (routeType == RouteType.UNMATCHED && route != null)
				throw new IllegalArgumentException("Route must be null when RouteType is UNMATCHED");
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

	/**
	 * Acquires a threadsafe {@link MetricsCollector} instance that performs no work.
	 * <p>
	 * This method is useful when you want to explicitly disable metrics collection without writing your own implementation.
	 *
	 * @return a no-op {@code MetricsCollector}
	 */
	@NonNull
	static MetricsCollector disabled() {
		return DisabledMetricsCollector.defaultInstance();
	}
}
