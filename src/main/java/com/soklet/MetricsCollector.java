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

import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.net.InetSocketAddress;
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
 * Soklet's standard implementation, available via {@link #defaultInstance()}, supports detailed histogram collection,
 * connection accept/reject counters, immutable snapshots (via {@link #snapshot()}), and provides Prometheus
 * (text format v0.0.4) / OpenMetrics (1.0) export helpers for convenience.
 * To disable metrics collection without a custom implementation, use {@link #disabledInstance()}.
 * <p>
 * If you prefer OpenTelemetry, Micrometer, or another metrics system for monitoring, you might choose to create your own
 * implementation of this interface.
 * <p>
 * Example configuration:
 * <pre><code>
 * SokletConfig config = SokletConfig.withServer(Server.withPort(8080).build())
 *   // This is already the default; specifying it here is optional
 *   .metricsCollector(MetricsCollector.defaultInstance())
 *   .build();
 * </code></pre>
 * <p>
 * To disable metrics collection entirely, specify Soklet's no-op implementation:
 * <pre><code>
 * SokletConfig config = SokletConfig.withServer(Server.withPort(8080).build())
 *   // Use this instead of null to disable metrics collection
 *   .metricsCollector(MetricsCollector.disabledInstance())
 *   .build();
 * </code></pre>
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
	 * Called when a server is about to accept a new TCP connection.
	 *
	 * @param serverType    the server type that is accepting the connection
	 * @param remoteAddress the best-effort remote address, or {@code null} if unavailable
	 */
	default void willAcceptConnection(@NonNull ServerType serverType,
																		@Nullable InetSocketAddress remoteAddress) {
		// No-op by default
	}

	/**
	 * Called after a server accepts a new TCP connection.
	 *
	 * @param serverType    the server type that accepted the connection
	 * @param remoteAddress the best-effort remote address, or {@code null} if unavailable
	 */
	default void didAcceptConnection(@NonNull ServerType serverType,
																	 @Nullable InetSocketAddress remoteAddress) {
		// No-op by default
	}

	/**
	 * Called after a server fails to accept a new TCP connection.
	 *
	 * @param serverType    the server type that failed to accept the connection
	 * @param remoteAddress the best-effort remote address, or {@code null} if unavailable
	 * @param reason        the failure reason
	 * @param throwable     an optional underlying cause, or {@code null} if not applicable
	 */
	default void didFailToAcceptConnection(@NonNull ServerType serverType,
																				 @Nullable InetSocketAddress remoteAddress,
																				 @NonNull ConnectionRejectionReason reason,
																				 @Nullable Throwable throwable) {
		// No-op by default
	}

	/**
	 * Called when a request is about to be accepted for application-level handling.
	 *
	 * @param serverType    the server type that received the request
	 * @param remoteAddress the best-effort remote address, or {@code null} if unavailable
	 * @param requestTarget the raw request target (path + query) if known, or {@code null} if unavailable
	 */
	default void willAcceptRequest(@NonNull ServerType serverType,
																 @Nullable InetSocketAddress remoteAddress,
																 @Nullable String requestTarget) {
		// No-op by default
	}

	/**
	 * Called after a request is accepted for application-level handling.
	 *
	 * @param serverType    the server type that received the request
	 * @param remoteAddress the best-effort remote address, or {@code null} if unavailable
	 * @param requestTarget the raw request target (path + query) if known, or {@code null} if unavailable
	 */
	default void didAcceptRequest(@NonNull ServerType serverType,
																@Nullable InetSocketAddress remoteAddress,
																@Nullable String requestTarget) {
		// No-op by default
	}

	/**
	 * Called when a request fails to be accepted before application-level handling begins.
	 *
	 * @param serverType    the server type that received the request
	 * @param remoteAddress the best-effort remote address, or {@code null} if unavailable
	 * @param requestTarget the raw request target (path + query) if known, or {@code null} if unavailable
	 * @param reason        the rejection reason
	 * @param throwable     an optional underlying cause, or {@code null} if not applicable
	 */
	default void didFailToAcceptRequest(@NonNull ServerType serverType,
																			@Nullable InetSocketAddress remoteAddress,
																			@Nullable String requestTarget,
																			@NonNull RequestRejectionReason reason,
																			@Nullable Throwable throwable) {
		// No-op by default
	}

	/**
	 * Called when Soklet is about to read or parse a request into a valid {@link Request}.
	 *
	 * @param serverType    the server type that received the request
	 * @param remoteAddress the best-effort remote address, or {@code null} if unavailable
	 * @param requestTarget the raw request target (path + query) if known, or {@code null} if unavailable
	 */
	default void willReadRequest(@NonNull ServerType serverType,
															 @Nullable InetSocketAddress remoteAddress,
															 @Nullable String requestTarget) {
		// No-op by default
	}

	/**
	 * Called when a request was successfully read or parsed into a valid {@link Request}.
	 *
	 * @param serverType    the server type that received the request
	 * @param remoteAddress the best-effort remote address, or {@code null} if unavailable
	 * @param requestTarget the raw request target (path + query) if known, or {@code null} if unavailable
	 */
	default void didReadRequest(@NonNull ServerType serverType,
															@Nullable InetSocketAddress remoteAddress,
															@Nullable String requestTarget) {
		// No-op by default
	}

	/**
	 * Called when a request could not be read or parsed into a valid {@link Request}.
	 *
	 * @param serverType    the server type that received the request
	 * @param remoteAddress the best-effort remote address, or {@code null} if unavailable
	 * @param requestTarget the raw request target (path + query) if known, or {@code null} if unavailable
	 * @param reason        the failure reason
	 * @param throwable     an optional underlying cause, or {@code null} if not applicable
	 */
	default void didFailToReadRequest(@NonNull ServerType serverType,
																		@Nullable InetSocketAddress remoteAddress,
																		@Nullable String requestTarget,
																		@NonNull RequestReadFailureReason reason,
																		@Nullable Throwable throwable) {
		// No-op by default
	}

	/**
	 * Called as soon as a request is received and a <em>Resource Method</em> has been resolved to handle it.
	 *
	 * @param serverType the server type that received the request
	 */
	default void didStartRequestHandling(@NonNull ServerType serverType,
																			 @NonNull Request request,
																			 @Nullable ResourceMethod resourceMethod) {
		// No-op by default
	}

	/**
	 * Called after a request finishes processing.
	 */
	default void didFinishRequestHandling(@NonNull ServerType serverType,
																				@NonNull Request request,
																				@Nullable ResourceMethod resourceMethod,
																				@NonNull MarshaledResponse marshaledResponse,
																				@NonNull Duration duration,
																				@NonNull List<@NonNull Throwable> throwables) {
		// No-op by default
	}

	/**
	 * Called before response data is written.
	 */
	default void willWriteResponse(@NonNull ServerType serverType,
																 @NonNull Request request,
																 @Nullable ResourceMethod resourceMethod,
																 @NonNull MarshaledResponse marshaledResponse) {
		// No-op by default
	}

	/**
	 * Called after response data is written.
	 */
	default void didWriteResponse(@NonNull ServerType serverType,
																@NonNull Request request,
																@Nullable ResourceMethod resourceMethod,
																@NonNull MarshaledResponse marshaledResponse,
																@NonNull Duration responseWriteDuration) {
		// No-op by default
	}

	/**
	 * Called after response data fails to write.
	 */
	default void didFailToWriteResponse(@NonNull ServerType serverType,
																			@NonNull Request request,
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
	 *
	 * @param reason    the handshake failure reason
	 * @param throwable an optional underlying cause, or {@code null} if not applicable
	 */
	default void didFailToEstablishServerSentEventConnection(@NonNull Request request,
																													 @Nullable ResourceMethod resourceMethod,
																													 ServerSentEventConnection.@NonNull HandshakeFailureReason reason,
																													 @Nullable Throwable throwable) {
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
	 * Called after an SSE event is dropped before it can be enqueued for delivery.
	 *
	 * @param serverSentEventConnection the connection the event was targeting
	 * @param serverSentEvent           the event that was dropped
	 * @param reason                    the drop reason
	 * @param payloadBytes              size of the serialized payload in bytes, or {@code null} if unknown
	 * @param queueDepth                number of queued elements at drop time, or {@code null} if unknown
	 */
	default void didDropServerSentEvent(@NonNull ServerSentEventConnection serverSentEventConnection,
																			@NonNull ServerSentEvent serverSentEvent,
																			@NonNull ServerSentEventDropReason reason,
																			@Nullable Integer payloadBytes,
																			@Nullable Integer queueDepth) {
		// No-op by default
	}

	/**
	 * Called after an SSE comment is dropped before it can be enqueued for delivery.
	 *
	 * @param serverSentEventConnection the connection the comment was targeting
	 * @param serverSentEventComment    the comment that was dropped
	 * @param reason                    the drop reason
	 * @param payloadBytes              size of the serialized payload in bytes, or {@code null} if unknown
	 * @param queueDepth                number of queued elements at drop time, or {@code null} if unknown
	 */
	default void didDropServerSentEventComment(@NonNull ServerSentEventConnection serverSentEventConnection,
																						 @NonNull ServerSentEventComment serverSentEventComment,
																						 @NonNull ServerSentEventDropReason reason,
																						 @Nullable Integer payloadBytes,
																						 @Nullable Integer queueDepth) {
		// No-op by default
	}

	/**
	 * Called after a broadcast attempt for a Server-Sent Event payload.
	 *
	 * @param route     the route declaration that was broadcast to
	 * @param attempted number of connections targeted
	 * @param enqueued  number of connections for which enqueue succeeded
	 * @param dropped   number of connections for which enqueue failed
	 */
	default void didBroadcastServerSentEvent(@NonNull ResourcePathDeclaration route,
																				 int attempted,
																				 int enqueued,
																				 int dropped) {
		// No-op by default
	}

	/**
	 * Called after a broadcast attempt for a Server-Sent Event comment payload.
	 *
	 * @param route       the route declaration that was broadcast to
	 * @param commentType the comment type
	 * @param attempted   number of connections targeted
	 * @param enqueued    number of connections for which enqueue succeeded
	 * @param dropped     number of connections for which enqueue failed
	 */
	default void didBroadcastServerSentEventComment(@NonNull ResourcePathDeclaration route,
																									ServerSentEventComment.@NonNull CommentType commentType,
																									int attempted,
																									int enqueued,
																									int dropped) {
		// No-op by default
	}

	/**
	 * Returns a snapshot of metrics collected so far, if supported.
	 *
	 * @return an optional metrics snapshot
	 */
	@NonNull
	default Optional<Snapshot> snapshot() {
		return Optional.empty();
	}

	/**
	 * Returns a text snapshot of metrics collected so far, if supported.
	 * <p>
	 * The default collector supports Prometheus (text format v0.0.4) and OpenMetrics (1.0) text exposition formats.
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
		 * Prometheus text exposition format (v0.0.4).
		 */
		PROMETHEUS,
		/**
		 * OpenMetrics text exposition format (1.0), including the {@code # EOF} trailer.
		 */
		OPEN_METRICS_1_0
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
		 * Creates options with the specified format and defaults for all other fields.
		 *
		 * @param metricsFormat the text exposition format
		 * @return a {@link SnapshotTextOptions} instance
		 */
		@NonNull
		public static SnapshotTextOptions fromMetricsFormat(@NonNull MetricsFormat metricsFormat) {
			return withMetricsFormat(metricsFormat).build();
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
	 * Immutable snapshot of collected metrics.
	 * <p>
	 * Durations are in nanoseconds, sizes are in bytes, and queue depths are raw counts.
	 * Histogram values are captured as {@link HistogramSnapshot} instances.
	 * Connection counts report total accepted/rejected connections for the HTTP and SSE servers.
	 * Request read failures and request rejections are reported separately for HTTP and SSE traffic.
	 * Instances are typically produced by {@link MetricsCollector#snapshot()} but can also be built
	 * manually via {@link #builder()}.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	final class Snapshot {
		@NonNull
		private final Long activeRequests;
		@NonNull
		private final Long activeSseConnections;
		@NonNull
		private final Long httpConnectionsAccepted;
		@NonNull
		private final Long httpConnectionsRejected;
		@NonNull
		private final Long sseConnectionsAccepted;
		@NonNull
		private final Long sseConnectionsRejected;
		@NonNull
		private final Map<@NonNull RequestReadFailureKey, @NonNull Long> httpRequestReadFailures;
		@NonNull
		private final Map<@NonNull RequestRejectionKey, @NonNull Long> httpRequestRejections;
		@NonNull
		private final Map<@NonNull RequestReadFailureKey, @NonNull Long> sseRequestReadFailures;
		@NonNull
		private final Map<@NonNull RequestRejectionKey, @NonNull Long> sseRequestRejections;
		@NonNull
		private final Map<@NonNull ServerRouteStatusKey, @NonNull HistogramSnapshot> httpRequestDurations;
		@NonNull
		private final Map<@NonNull ServerRouteStatusKey, @NonNull HistogramSnapshot> httpHandlerDurations;
		@NonNull
		private final Map<@NonNull ServerRouteStatusKey, @NonNull HistogramSnapshot> httpTimeToFirstByte;
		@NonNull
		private final Map<@NonNull ServerRouteKey, @NonNull HistogramSnapshot> httpRequestBodyBytes;
		@NonNull
		private final Map<@NonNull ServerRouteStatusKey, @NonNull HistogramSnapshot> httpResponseBodyBytes;
		@NonNull
		private final Map<@NonNull ServerSentEventRouteKey, @NonNull Long> sseHandshakesAccepted;
		@NonNull
		private final Map<@NonNull ServerSentEventRouteHandshakeFailureKey, @NonNull Long> sseHandshakesRejected;
		@NonNull
		private final Map<@NonNull ServerSentEventRouteEnqueueOutcomeKey, @NonNull Long> sseEventEnqueueOutcomes;
		@NonNull
		private final Map<@NonNull ServerSentEventCommentRouteEnqueueOutcomeKey, @NonNull Long> sseCommentEnqueueOutcomes;
		@NonNull
		private final Map<@NonNull ServerSentEventRouteDropKey, @NonNull Long> sseEventDrops;
		@NonNull
		private final Map<@NonNull ServerSentEventCommentRouteDropKey, @NonNull Long> sseCommentDrops;
		@NonNull
		private final Map<@NonNull ServerSentEventRouteKey, @NonNull HistogramSnapshot> sseTimeToFirstEvent;
		@NonNull
		private final Map<@NonNull ServerSentEventRouteKey, @NonNull HistogramSnapshot> sseEventWriteDurations;
		@NonNull
		private final Map<@NonNull ServerSentEventRouteKey, @NonNull HistogramSnapshot> sseEventDeliveryLag;
		@NonNull
		private final Map<@NonNull ServerSentEventRouteKey, @NonNull HistogramSnapshot> sseEventSizes;
		@NonNull
		private final Map<@NonNull ServerSentEventRouteKey, @NonNull HistogramSnapshot> sseQueueDepth;
		@NonNull
		private final Map<@NonNull ServerSentEventCommentRouteKey, @NonNull HistogramSnapshot> sseCommentDeliveryLag;
		@NonNull
		private final Map<@NonNull ServerSentEventCommentRouteKey, @NonNull HistogramSnapshot> sseCommentSizes;
		@NonNull
		private final Map<@NonNull ServerSentEventCommentRouteKey, @NonNull HistogramSnapshot> sseCommentQueueDepth;
		@NonNull
		private final Map<@NonNull ServerSentEventRouteTerminationKey, @NonNull HistogramSnapshot> sseConnectionDurations;

		/**
		 * Acquires an "empty" builder for {@link Snapshot} instances.
		 *
		 * @return the builder
		 */
		@NonNull
		public static Builder builder() {
			return new Builder();
		}

		private Snapshot(@NonNull Builder builder) {
			requireNonNull(builder);

			this.activeRequests = requireNonNull(builder.activeRequests);
			this.activeSseConnections = requireNonNull(builder.activeSseConnections);
			this.httpConnectionsAccepted = requireNonNull(builder.httpConnectionsAccepted);
			this.httpConnectionsRejected = requireNonNull(builder.httpConnectionsRejected);
			this.sseConnectionsAccepted = requireNonNull(builder.sseConnectionsAccepted);
			this.sseConnectionsRejected = requireNonNull(builder.sseConnectionsRejected);
			this.httpRequestReadFailures = copyOrEmpty(builder.httpRequestReadFailures);
			this.httpRequestRejections = copyOrEmpty(builder.httpRequestRejections);
			this.sseRequestReadFailures = copyOrEmpty(builder.sseRequestReadFailures);
			this.sseRequestRejections = copyOrEmpty(builder.sseRequestRejections);
			this.httpRequestDurations = copyOrEmpty(builder.httpRequestDurations);
			this.httpHandlerDurations = copyOrEmpty(builder.httpHandlerDurations);
			this.httpTimeToFirstByte = copyOrEmpty(builder.httpTimeToFirstByte);
			this.httpRequestBodyBytes = copyOrEmpty(builder.httpRequestBodyBytes);
			this.httpResponseBodyBytes = copyOrEmpty(builder.httpResponseBodyBytes);
			this.sseHandshakesAccepted = copyOrEmpty(builder.sseHandshakesAccepted);
			this.sseHandshakesRejected = copyOrEmpty(builder.sseHandshakesRejected);
			this.sseEventEnqueueOutcomes = copyOrEmpty(builder.sseEventEnqueueOutcomes);
			this.sseCommentEnqueueOutcomes = copyOrEmpty(builder.sseCommentEnqueueOutcomes);
			this.sseEventDrops = copyOrEmpty(builder.sseEventDrops);
			this.sseCommentDrops = copyOrEmpty(builder.sseCommentDrops);
			this.sseTimeToFirstEvent = copyOrEmpty(builder.sseTimeToFirstEvent);
			this.sseEventWriteDurations = copyOrEmpty(builder.sseEventWriteDurations);
			this.sseEventDeliveryLag = copyOrEmpty(builder.sseEventDeliveryLag);
			this.sseEventSizes = copyOrEmpty(builder.sseEventSizes);
			this.sseQueueDepth = copyOrEmpty(builder.sseQueueDepth);
			this.sseCommentDeliveryLag = copyOrEmpty(builder.sseCommentDeliveryLag);
			this.sseCommentSizes = copyOrEmpty(builder.sseCommentSizes);
			this.sseCommentQueueDepth = copyOrEmpty(builder.sseCommentQueueDepth);
			this.sseConnectionDurations = copyOrEmpty(builder.sseConnectionDurations);
		}

		/**
		 * Returns the number of active HTTP requests.
		 *
		 * @return the active HTTP request count
		 */
		@NonNull
		public Long getActiveRequests() {
			return this.activeRequests;
		}

		/**
		 * Returns the number of active server-sent event connections.
		 *
		 * @return the active SSE connection count
		 */
		@NonNull
		public Long getActiveSseConnections() {
			return this.activeSseConnections;
		}

		/**
		 * Returns the total number of accepted HTTP connections.
		 *
		 * @return total accepted HTTP connections
		 */
		@NonNull
		public Long getHttpConnectionsAccepted() {
			return this.httpConnectionsAccepted;
		}

		/**
		 * Returns the total number of rejected HTTP connections.
		 *
		 * @return total rejected HTTP connections
		 */
		@NonNull
		public Long getHttpConnectionsRejected() {
			return this.httpConnectionsRejected;
		}

		/**
		 * Returns the total number of accepted SSE connections.
		 *
		 * @return total accepted SSE connections
		 */
		@NonNull
		public Long getSseConnectionsAccepted() {
			return this.sseConnectionsAccepted;
		}

		/**
		 * Returns the total number of rejected SSE connections.
		 *
		 * @return total rejected SSE connections
		 */
		@NonNull
		public Long getSseConnectionsRejected() {
			return this.sseConnectionsRejected;
		}

		/**
		 * Returns HTTP request read failure counters keyed by failure reason.
		 *
		 * @return HTTP request read failure counters
		 */
		@NonNull
		public Map<@NonNull RequestReadFailureKey, @NonNull Long> getHttpRequestReadFailures() {
			return this.httpRequestReadFailures;
		}

		/**
		 * Returns HTTP request rejection counters keyed by rejection reason.
		 *
		 * @return HTTP request rejection counters
		 */
		@NonNull
		public Map<@NonNull RequestRejectionKey, @NonNull Long> getHttpRequestRejections() {
			return this.httpRequestRejections;
		}

		/**
		 * Returns SSE request read failure counters keyed by failure reason.
		 *
		 * @return SSE request read failure counters
		 */
		@NonNull
		public Map<@NonNull RequestReadFailureKey, @NonNull Long> getSseRequestReadFailures() {
			return this.sseRequestReadFailures;
		}

		/**
		 * Returns SSE request rejection counters keyed by rejection reason.
		 *
		 * @return SSE request rejection counters
		 */
		@NonNull
		public Map<@NonNull RequestRejectionKey, @NonNull Long> getSseRequestRejections() {
			return this.sseRequestRejections;
		}

		/**
		 * Returns HTTP request duration histograms keyed by server route and status class.
		 *
		 * @return HTTP request duration histograms
		 */
		@NonNull
		public Map<@NonNull ServerRouteStatusKey, @NonNull HistogramSnapshot> getHttpRequestDurations() {
			return this.httpRequestDurations;
		}

		/**
		 * Returns HTTP handler duration histograms keyed by server route and status class.
		 *
		 * @return HTTP handler duration histograms
		 */
		@NonNull
		public Map<@NonNull ServerRouteStatusKey, @NonNull HistogramSnapshot> getHttpHandlerDurations() {
			return this.httpHandlerDurations;
		}

		/**
		 * Returns HTTP time-to-first-byte histograms keyed by server route and status class.
		 *
		 * @return HTTP time-to-first-byte histograms
		 */
		@NonNull
		public Map<@NonNull ServerRouteStatusKey, @NonNull HistogramSnapshot> getHttpTimeToFirstByte() {
			return this.httpTimeToFirstByte;
		}

		/**
		 * Returns HTTP request body size histograms keyed by server route.
		 *
		 * @return HTTP request body size histograms
		 */
		@NonNull
		public Map<@NonNull ServerRouteKey, @NonNull HistogramSnapshot> getHttpRequestBodyBytes() {
			return this.httpRequestBodyBytes;
		}

		/**
		 * Returns HTTP response body size histograms keyed by server route and status class.
		 *
		 * @return HTTP response body size histograms
		 */
		@NonNull
		public Map<@NonNull ServerRouteStatusKey, @NonNull HistogramSnapshot> getHttpResponseBodyBytes() {
			return this.httpResponseBodyBytes;
		}

		/**
		 * Returns SSE handshake acceptance counters keyed by route.
		 *
		 * @return SSE handshake acceptance counters
		 */
		@NonNull
		public Map<@NonNull ServerSentEventRouteKey, @NonNull Long> getSseHandshakesAccepted() {
			return this.sseHandshakesAccepted;
		}

		/**
		 * Returns SSE handshake rejection counters keyed by route and failure reason.
		 *
		 * @return SSE handshake rejection counters
		 */
		@NonNull
		public Map<@NonNull ServerSentEventRouteHandshakeFailureKey, @NonNull Long> getSseHandshakesRejected() {
			return this.sseHandshakesRejected;
		}

		/**
		 * Returns SSE event enqueue outcome counters keyed by route and outcome.
		 *
		 * @return SSE event enqueue outcome counters
		 */
		@NonNull
		public Map<@NonNull ServerSentEventRouteEnqueueOutcomeKey, @NonNull Long> getSseEventEnqueueOutcomes() {
			return this.sseEventEnqueueOutcomes;
		}

		/**
		 * Returns SSE comment enqueue outcome counters keyed by route, comment type, and outcome.
		 *
		 * @return SSE comment enqueue outcome counters
		 */
		@NonNull
		public Map<@NonNull ServerSentEventCommentRouteEnqueueOutcomeKey, @NonNull Long> getSseCommentEnqueueOutcomes() {
			return this.sseCommentEnqueueOutcomes;
		}

		/**
		 * Returns SSE event drop counters keyed by route and drop reason.
		 *
		 * @return SSE event drop counters
		 */
		@NonNull
		public Map<@NonNull ServerSentEventRouteDropKey, @NonNull Long> getSseEventDrops() {
			return this.sseEventDrops;
		}

		/**
		 * Returns SSE comment drop counters keyed by route, comment type, and drop reason.
		 *
		 * @return SSE comment drop counters
		 */
		@NonNull
		public Map<@NonNull ServerSentEventCommentRouteDropKey, @NonNull Long> getSseCommentDrops() {
			return this.sseCommentDrops;
		}

		/**
		 * Returns SSE time-to-first-event histograms keyed by route.
		 *
		 * @return SSE time-to-first-event histograms
		 */
		@NonNull
		public Map<@NonNull ServerSentEventRouteKey, @NonNull HistogramSnapshot> getSseTimeToFirstEvent() {
			return this.sseTimeToFirstEvent;
		}

		/**
		 * Returns SSE event write duration histograms keyed by route.
		 *
		 * @return SSE event write duration histograms
		 */
		@NonNull
		public Map<@NonNull ServerSentEventRouteKey, @NonNull HistogramSnapshot> getSseEventWriteDurations() {
			return this.sseEventWriteDurations;
		}

		/**
		 * Returns SSE event delivery lag histograms keyed by route.
		 *
		 * @return SSE event delivery lag histograms
		 */
		@NonNull
		public Map<@NonNull ServerSentEventRouteKey, @NonNull HistogramSnapshot> getSseEventDeliveryLag() {
			return this.sseEventDeliveryLag;
		}

		/**
		 * Returns SSE event size histograms keyed by route.
		 *
		 * @return SSE event size histograms
		 */
		@NonNull
		public Map<@NonNull ServerSentEventRouteKey, @NonNull HistogramSnapshot> getSseEventSizes() {
			return this.sseEventSizes;
		}

		/**
		 * Returns SSE queue depth histograms keyed by route.
		 *
		 * @return SSE queue depth histograms
		 */
		@NonNull
		public Map<@NonNull ServerSentEventRouteKey, @NonNull HistogramSnapshot> getSseQueueDepth() {
			return this.sseQueueDepth;
		}

		/**
		 * Returns SSE comment delivery lag histograms keyed by route and comment type.
		 *
		 * @return SSE comment delivery lag histograms
		 */
		@NonNull
		public Map<@NonNull ServerSentEventCommentRouteKey, @NonNull HistogramSnapshot> getSseCommentDeliveryLag() {
			return this.sseCommentDeliveryLag;
		}

		/**
		 * Returns SSE comment size histograms keyed by route and comment type.
		 *
		 * @return SSE comment size histograms
		 */
		@NonNull
		public Map<@NonNull ServerSentEventCommentRouteKey, @NonNull HistogramSnapshot> getSseCommentSizes() {
			return this.sseCommentSizes;
		}

		/**
		 * Returns SSE comment queue depth histograms keyed by route and comment type.
		 *
		 * @return SSE comment queue depth histograms
		 */
		@NonNull
		public Map<@NonNull ServerSentEventCommentRouteKey, @NonNull HistogramSnapshot> getSseCommentQueueDepth() {
			return this.sseCommentQueueDepth;
		}

		/**
		 * Returns SSE connection duration histograms keyed by route and termination reason.
		 *
		 * @return SSE connection duration histograms
		 */
		@NonNull
		public Map<@NonNull ServerSentEventRouteTerminationKey, @NonNull HistogramSnapshot> getSseConnectionDurations() {
			return this.sseConnectionDurations;
		}

		@NonNull
		private static <K, V> Map<K, V> copyOrEmpty(@Nullable Map<K, V> map) {
			return map == null ? Map.of() : Map.copyOf(map);
		}

		/**
		 * Builder used to construct instances of {@link Snapshot}.
		 * <p>
		 * This class is intended for use by a single thread.
		 *
		 * @author <a href="https://www.revetkn.com">Mark Allen</a>
		 */
		@NotThreadSafe
		public static final class Builder {
			@NonNull
			private Long activeRequests;
			@NonNull
			private Long activeSseConnections;
			@NonNull
			private Long httpConnectionsAccepted;
			@NonNull
			private Long httpConnectionsRejected;
			@NonNull
			private Long sseConnectionsAccepted;
			@NonNull
			private Long sseConnectionsRejected;
			@Nullable
			private Map<@NonNull RequestReadFailureKey, @NonNull Long> httpRequestReadFailures;
			@Nullable
			private Map<@NonNull RequestRejectionKey, @NonNull Long> httpRequestRejections;
			@Nullable
			private Map<@NonNull RequestReadFailureKey, @NonNull Long> sseRequestReadFailures;
			@Nullable
			private Map<@NonNull RequestRejectionKey, @NonNull Long> sseRequestRejections;
			@Nullable
			private Map<@NonNull ServerRouteStatusKey, @NonNull HistogramSnapshot> httpRequestDurations;
			@Nullable
			private Map<@NonNull ServerRouteStatusKey, @NonNull HistogramSnapshot> httpHandlerDurations;
			@Nullable
			private Map<@NonNull ServerRouteStatusKey, @NonNull HistogramSnapshot> httpTimeToFirstByte;
			@Nullable
			private Map<@NonNull ServerRouteKey, @NonNull HistogramSnapshot> httpRequestBodyBytes;
			@Nullable
			private Map<@NonNull ServerRouteStatusKey, @NonNull HistogramSnapshot> httpResponseBodyBytes;
			@Nullable
			private Map<@NonNull ServerSentEventRouteKey, @NonNull Long> sseHandshakesAccepted;
			@Nullable
			private Map<@NonNull ServerSentEventRouteHandshakeFailureKey, @NonNull Long> sseHandshakesRejected;
			@Nullable
			private Map<@NonNull ServerSentEventRouteEnqueueOutcomeKey, @NonNull Long> sseEventEnqueueOutcomes;
			@Nullable
			private Map<@NonNull ServerSentEventCommentRouteEnqueueOutcomeKey, @NonNull Long> sseCommentEnqueueOutcomes;
			@Nullable
			private Map<@NonNull ServerSentEventRouteDropKey, @NonNull Long> sseEventDrops;
			@Nullable
			private Map<@NonNull ServerSentEventCommentRouteDropKey, @NonNull Long> sseCommentDrops;
			@Nullable
			private Map<@NonNull ServerSentEventRouteKey, @NonNull HistogramSnapshot> sseTimeToFirstEvent;
			@Nullable
			private Map<@NonNull ServerSentEventRouteKey, @NonNull HistogramSnapshot> sseEventWriteDurations;
			@Nullable
			private Map<@NonNull ServerSentEventRouteKey, @NonNull HistogramSnapshot> sseEventDeliveryLag;
			@Nullable
			private Map<@NonNull ServerSentEventRouteKey, @NonNull HistogramSnapshot> sseEventSizes;
			@Nullable
			private Map<@NonNull ServerSentEventRouteKey, @NonNull HistogramSnapshot> sseQueueDepth;
			@Nullable
			private Map<@NonNull ServerSentEventCommentRouteKey, @NonNull HistogramSnapshot> sseCommentDeliveryLag;
			@Nullable
			private Map<@NonNull ServerSentEventCommentRouteKey, @NonNull HistogramSnapshot> sseCommentSizes;
			@Nullable
			private Map<@NonNull ServerSentEventCommentRouteKey, @NonNull HistogramSnapshot> sseCommentQueueDepth;
			@Nullable
			private Map<@NonNull ServerSentEventRouteTerminationKey, @NonNull HistogramSnapshot> sseConnectionDurations;

			private Builder() {
				this.activeRequests = 0L;
				this.activeSseConnections = 0L;
				this.httpConnectionsAccepted = 0L;
				this.httpConnectionsRejected = 0L;
				this.sseConnectionsAccepted = 0L;
				this.sseConnectionsRejected = 0L;
			}

			/**
			 * Sets the active HTTP request count.
			 *
			 * @param activeRequests the active HTTP request count
			 * @return this builder
			 */
			@NonNull
			public Builder activeRequests(@NonNull Long activeRequests) {
				this.activeRequests = requireNonNull(activeRequests);
				return this;
			}

			/**
			 * Sets the active server-sent event connection count.
			 *
			 * @param activeSseConnections the active SSE connection count
			 * @return this builder
			 */
			@NonNull
			public Builder activeSseConnections(@NonNull Long activeSseConnections) {
				this.activeSseConnections = requireNonNull(activeSseConnections);
				return this;
			}

			/**
			 * Sets the total number of accepted HTTP connections.
			 *
			 * @param httpConnectionsAccepted total accepted HTTP connections
			 * @return this builder
			 */
			@NonNull
			public Builder httpConnectionsAccepted(@NonNull Long httpConnectionsAccepted) {
				this.httpConnectionsAccepted = requireNonNull(httpConnectionsAccepted);
				return this;
			}

			/**
			 * Sets the total number of rejected HTTP connections.
			 *
			 * @param httpConnectionsRejected total rejected HTTP connections
			 * @return this builder
			 */
			@NonNull
			public Builder httpConnectionsRejected(@NonNull Long httpConnectionsRejected) {
				this.httpConnectionsRejected = requireNonNull(httpConnectionsRejected);
				return this;
			}

			/**
			 * Sets the total number of accepted SSE connections.
			 *
			 * @param sseConnectionsAccepted total accepted SSE connections
			 * @return this builder
			 */
			@NonNull
			public Builder sseConnectionsAccepted(@NonNull Long sseConnectionsAccepted) {
				this.sseConnectionsAccepted = requireNonNull(sseConnectionsAccepted);
				return this;
			}

			/**
			 * Sets the total number of rejected SSE connections.
			 *
			 * @param sseConnectionsRejected total rejected SSE connections
			 * @return this builder
			 */
			@NonNull
			public Builder sseConnectionsRejected(@NonNull Long sseConnectionsRejected) {
				this.sseConnectionsRejected = requireNonNull(sseConnectionsRejected);
				return this;
			}

			/**
			 * Sets HTTP request read failure counters keyed by failure reason.
			 *
			 * @param httpRequestReadFailures the HTTP request read failure counters
			 * @return this builder
			 */
			@NonNull
			public Builder httpRequestReadFailures(
					@Nullable Map<@NonNull RequestReadFailureKey, @NonNull Long> httpRequestReadFailures) {
				this.httpRequestReadFailures = httpRequestReadFailures;
				return this;
			}

			/**
			 * Sets HTTP request rejection counters keyed by rejection reason.
			 *
			 * @param httpRequestRejections the HTTP request rejection counters
			 * @return this builder
			 */
			@NonNull
			public Builder httpRequestRejections(
					@Nullable Map<@NonNull RequestRejectionKey, @NonNull Long> httpRequestRejections) {
				this.httpRequestRejections = httpRequestRejections;
				return this;
			}

			/**
			 * Sets SSE request read failure counters keyed by failure reason.
			 *
			 * @param sseRequestReadFailures the SSE request read failure counters
			 * @return this builder
			 */
			@NonNull
			public Builder sseRequestReadFailures(
					@Nullable Map<@NonNull RequestReadFailureKey, @NonNull Long> sseRequestReadFailures) {
				this.sseRequestReadFailures = sseRequestReadFailures;
				return this;
			}

			/**
			 * Sets SSE request rejection counters keyed by rejection reason.
			 *
			 * @param sseRequestRejections the SSE request rejection counters
			 * @return this builder
			 */
			@NonNull
			public Builder sseRequestRejections(
					@Nullable Map<@NonNull RequestRejectionKey, @NonNull Long> sseRequestRejections) {
				this.sseRequestRejections = sseRequestRejections;
				return this;
			}

			/**
			 * Sets HTTP request duration histograms keyed by server route and status class.
			 *
			 * @param httpRequestDurations the HTTP request duration histograms
			 * @return this builder
			 */
			@NonNull
			public Builder httpRequestDurations(
					@Nullable Map<@NonNull ServerRouteStatusKey, @NonNull HistogramSnapshot> httpRequestDurations) {
				this.httpRequestDurations = httpRequestDurations;
				return this;
			}

			/**
			 * Sets HTTP handler duration histograms keyed by server route and status class.
			 *
			 * @param httpHandlerDurations the HTTP handler duration histograms
			 * @return this builder
			 */
			@NonNull
			public Builder httpHandlerDurations(
					@Nullable Map<@NonNull ServerRouteStatusKey, @NonNull HistogramSnapshot> httpHandlerDurations) {
				this.httpHandlerDurations = httpHandlerDurations;
				return this;
			}

			/**
			 * Sets HTTP time-to-first-byte histograms keyed by server route and status class.
			 *
			 * @param httpTimeToFirstByte the HTTP time-to-first-byte histograms
			 * @return this builder
			 */
			@NonNull
			public Builder httpTimeToFirstByte(
					@Nullable Map<@NonNull ServerRouteStatusKey, @NonNull HistogramSnapshot> httpTimeToFirstByte) {
				this.httpTimeToFirstByte = httpTimeToFirstByte;
				return this;
			}

			/**
			 * Sets HTTP request body size histograms keyed by server route.
			 *
			 * @param httpRequestBodyBytes the HTTP request body size histograms
			 * @return this builder
			 */
			@NonNull
			public Builder httpRequestBodyBytes(
					@Nullable Map<@NonNull ServerRouteKey, @NonNull HistogramSnapshot> httpRequestBodyBytes) {
				this.httpRequestBodyBytes = httpRequestBodyBytes;
				return this;
			}

			/**
			 * Sets HTTP response body size histograms keyed by server route and status class.
			 *
			 * @param httpResponseBodyBytes the HTTP response body size histograms
			 * @return this builder
			 */
			@NonNull
			public Builder httpResponseBodyBytes(
					@Nullable Map<@NonNull ServerRouteStatusKey, @NonNull HistogramSnapshot> httpResponseBodyBytes) {
				this.httpResponseBodyBytes = httpResponseBodyBytes;
				return this;
			}

			/**
			 * Sets SSE handshake acceptance counters keyed by route.
			 *
			 * @param sseHandshakesAccepted SSE handshake acceptance counters
			 * @return this builder
			 */
			@NonNull
			public Builder sseHandshakesAccepted(
					@Nullable Map<@NonNull ServerSentEventRouteKey, @NonNull Long> sseHandshakesAccepted) {
				this.sseHandshakesAccepted = sseHandshakesAccepted;
				return this;
			}

			/**
			 * Sets SSE handshake rejection counters keyed by route and failure reason.
			 *
			 * @param sseHandshakesRejected SSE handshake rejection counters
			 * @return this builder
			 */
			@NonNull
			public Builder sseHandshakesRejected(
					@Nullable Map<@NonNull ServerSentEventRouteHandshakeFailureKey, @NonNull Long> sseHandshakesRejected) {
				this.sseHandshakesRejected = sseHandshakesRejected;
				return this;
			}

			/**
			 * Sets SSE event enqueue outcome counters keyed by route and outcome.
			 *
			 * @param sseEventEnqueueOutcomes the SSE event enqueue outcome counters
			 * @return this builder
			 */
			@NonNull
			public Builder sseEventEnqueueOutcomes(
					@Nullable Map<@NonNull ServerSentEventRouteEnqueueOutcomeKey, @NonNull Long> sseEventEnqueueOutcomes) {
				this.sseEventEnqueueOutcomes = sseEventEnqueueOutcomes;
				return this;
			}

			/**
			 * Sets SSE comment enqueue outcome counters keyed by route, comment type, and outcome.
			 *
			 * @param sseCommentEnqueueOutcomes the SSE comment enqueue outcome counters
			 * @return this builder
			 */
			@NonNull
			public Builder sseCommentEnqueueOutcomes(
					@Nullable Map<@NonNull ServerSentEventCommentRouteEnqueueOutcomeKey, @NonNull Long> sseCommentEnqueueOutcomes) {
				this.sseCommentEnqueueOutcomes = sseCommentEnqueueOutcomes;
				return this;
			}

			/**
			 * Sets SSE event drop counters keyed by route and drop reason.
			 *
			 * @param sseEventDrops the SSE event drop counters
			 * @return this builder
			 */
			@NonNull
			public Builder sseEventDrops(
					@Nullable Map<@NonNull ServerSentEventRouteDropKey, @NonNull Long> sseEventDrops) {
				this.sseEventDrops = sseEventDrops;
				return this;
			}

			/**
			 * Sets SSE comment drop counters keyed by route, comment type, and drop reason.
			 *
			 * @param sseCommentDrops the SSE comment drop counters
			 * @return this builder
			 */
			@NonNull
			public Builder sseCommentDrops(
					@Nullable Map<@NonNull ServerSentEventCommentRouteDropKey, @NonNull Long> sseCommentDrops) {
				this.sseCommentDrops = sseCommentDrops;
				return this;
			}

			/**
			 * Sets SSE time-to-first-event histograms keyed by route.
			 *
			 * @param sseTimeToFirstEvent the SSE time-to-first-event histograms
			 * @return this builder
			 */
			@NonNull
			public Builder sseTimeToFirstEvent(
					@Nullable Map<@NonNull ServerSentEventRouteKey, @NonNull HistogramSnapshot> sseTimeToFirstEvent) {
				this.sseTimeToFirstEvent = sseTimeToFirstEvent;
				return this;
			}

			/**
			 * Sets SSE event write duration histograms keyed by route.
			 *
			 * @param sseEventWriteDurations the SSE event write duration histograms
			 * @return this builder
			 */
			@NonNull
			public Builder sseEventWriteDurations(
					@Nullable Map<@NonNull ServerSentEventRouteKey, @NonNull HistogramSnapshot> sseEventWriteDurations) {
				this.sseEventWriteDurations = sseEventWriteDurations;
				return this;
			}

			/**
			 * Sets SSE event delivery lag histograms keyed by route.
			 *
			 * @param sseEventDeliveryLag the SSE event delivery lag histograms
			 * @return this builder
			 */
			@NonNull
			public Builder sseEventDeliveryLag(
					@Nullable Map<@NonNull ServerSentEventRouteKey, @NonNull HistogramSnapshot> sseEventDeliveryLag) {
				this.sseEventDeliveryLag = sseEventDeliveryLag;
				return this;
			}

			/**
			 * Sets SSE event size histograms keyed by route.
			 *
			 * @param sseEventSizes the SSE event size histograms
			 * @return this builder
			 */
			@NonNull
			public Builder sseEventSizes(
					@Nullable Map<@NonNull ServerSentEventRouteKey, @NonNull HistogramSnapshot> sseEventSizes) {
				this.sseEventSizes = sseEventSizes;
				return this;
			}

			/**
			 * Sets SSE queue depth histograms keyed by route.
			 *
			 * @param sseQueueDepth the SSE queue depth histograms
			 * @return this builder
			 */
			@NonNull
			public Builder sseQueueDepth(
					@Nullable Map<@NonNull ServerSentEventRouteKey, @NonNull HistogramSnapshot> sseQueueDepth) {
				this.sseQueueDepth = sseQueueDepth;
				return this;
			}

			/**
			 * Sets SSE comment delivery lag histograms keyed by route and comment type.
			 *
			 * @param sseCommentDeliveryLag the SSE comment delivery lag histograms
			 * @return this builder
			 */
			@NonNull
			public Builder sseCommentDeliveryLag(
					@Nullable Map<@NonNull ServerSentEventCommentRouteKey, @NonNull HistogramSnapshot> sseCommentDeliveryLag) {
				this.sseCommentDeliveryLag = sseCommentDeliveryLag;
				return this;
			}

			/**
			 * Sets SSE comment size histograms keyed by route and comment type.
			 *
			 * @param sseCommentSizes the SSE comment size histograms
			 * @return this builder
			 */
			@NonNull
			public Builder sseCommentSizes(
					@Nullable Map<@NonNull ServerSentEventCommentRouteKey, @NonNull HistogramSnapshot> sseCommentSizes) {
				this.sseCommentSizes = sseCommentSizes;
				return this;
			}

			/**
			 * Sets SSE comment queue depth histograms keyed by route and comment type.
			 *
			 * @param sseCommentQueueDepth the SSE comment queue depth histograms
			 * @return this builder
			 */
			@NonNull
			public Builder sseCommentQueueDepth(
					@Nullable Map<@NonNull ServerSentEventCommentRouteKey, @NonNull HistogramSnapshot> sseCommentQueueDepth) {
				this.sseCommentQueueDepth = sseCommentQueueDepth;
				return this;
			}

			/**
			 * Sets SSE connection duration histograms keyed by route and termination reason.
			 *
			 * @param sseConnectionDurations the SSE connection duration histograms
			 * @return this builder
			 */
			@NonNull
			public Builder sseConnectionDurations(
					@Nullable Map<@NonNull ServerSentEventRouteTerminationKey, @NonNull HistogramSnapshot> sseConnectionDurations) {
				this.sseConnectionDurations = sseConnectionDurations;
				return this;
			}

			/**
			 * Builds a {@link Snapshot} instance.
			 *
			 * @return the built snapshot
			 */
			@NonNull
			public Snapshot build() {
				return new Snapshot(this);
			}
		}
	}

	/**
	 * A thread-safe histogram with fixed bucket boundaries.
	 * <p>
	 * Negative values are ignored. Buckets use inclusive upper bounds, and snapshots include
	 * an overflow bucket represented by a {@link HistogramSnapshot#getBucketBoundary(int)} of
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
		public HistogramSnapshot snapshot() {
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
	final class HistogramSnapshot {
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
		public HistogramSnapshot(@NonNull long[] bucketBoundaries,
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
	 * Outcomes for a Server-Sent Event enqueue attempt.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	enum ServerSentEventEnqueueOutcome {
		/**
		 * An enqueue attempt to a connection was made.
		 */
		ATTEMPTED,
		/**
		 * An enqueue attempt succeeded.
		 */
		ENQUEUED,
		/**
		 * An enqueue attempt failed because the payload was dropped.
		 */
		DROPPED
	}

	/**
	 * Reasons a Server-Sent Event payload or comment was dropped before it could be written.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	enum ServerSentEventDropReason {
		/**
		 * The per-connection write queue was full.
		 */
		QUEUE_FULL
	}

	/**
	 * Key for request read failures grouped by reason.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	record RequestReadFailureKey(@NonNull RequestReadFailureReason reason) {
		public RequestReadFailureKey {
			requireNonNull(reason);
		}
	}

	/**
	 * Key for request rejections grouped by reason.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	record RequestRejectionKey(@NonNull RequestRejectionReason reason) {
		public RequestRejectionKey {
			requireNonNull(reason);
		}
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
	 * Key for metrics grouped by Server-Sent Event route match information and handshake failure reason.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	record ServerSentEventRouteHandshakeFailureKey(@NonNull RouteType routeType,
																								 @Nullable ResourcePathDeclaration route,
																								 ServerSentEventConnection.@NonNull HandshakeFailureReason handshakeFailureReason) {
		public ServerSentEventRouteHandshakeFailureKey {
			requireNonNull(routeType);
			if (routeType == RouteType.MATCHED && route == null)
				throw new IllegalArgumentException("Route must be provided when RouteType is MATCHED");
			if (routeType == RouteType.UNMATCHED && route != null)
				throw new IllegalArgumentException("Route must be null when RouteType is UNMATCHED");
			requireNonNull(handshakeFailureReason);
		}
	}

	/**
	 * Key for metrics grouped by Server-Sent Event route match information and enqueue outcome.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	record ServerSentEventRouteEnqueueOutcomeKey(@NonNull RouteType routeType,
																								 @Nullable ResourcePathDeclaration route,
																								 @NonNull ServerSentEventEnqueueOutcome outcome) {
		public ServerSentEventRouteEnqueueOutcomeKey {
			requireNonNull(routeType);
			if (routeType == RouteType.MATCHED && route == null)
				throw new IllegalArgumentException("Route must be provided when RouteType is MATCHED");
			if (routeType == RouteType.UNMATCHED && route != null)
				throw new IllegalArgumentException("Route must be null when RouteType is UNMATCHED");
			requireNonNull(outcome);
		}
	}

	/**
	 * Key for metrics grouped by Server-Sent Event comment type, route match information, and enqueue outcome.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	record ServerSentEventCommentRouteEnqueueOutcomeKey(@NonNull RouteType routeType,
																											 @Nullable ResourcePathDeclaration route,
																											 ServerSentEventComment.@NonNull CommentType commentType,
																											 @NonNull ServerSentEventEnqueueOutcome outcome) {
		public ServerSentEventCommentRouteEnqueueOutcomeKey {
			requireNonNull(routeType);
			requireNonNull(commentType);
			if (routeType == RouteType.MATCHED && route == null)
				throw new IllegalArgumentException("Route must be provided when RouteType is MATCHED");
			if (routeType == RouteType.UNMATCHED && route != null)
				throw new IllegalArgumentException("Route must be null when RouteType is UNMATCHED");
			requireNonNull(outcome);
		}
	}

	/**
	 * Key for metrics grouped by Server-Sent Event route match information and drop reason.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	record ServerSentEventRouteDropKey(@NonNull RouteType routeType,
																		 @Nullable ResourcePathDeclaration route,
																		 @NonNull ServerSentEventDropReason dropReason) {
		public ServerSentEventRouteDropKey {
			requireNonNull(routeType);
			if (routeType == RouteType.MATCHED && route == null)
				throw new IllegalArgumentException("Route must be provided when RouteType is MATCHED");
			if (routeType == RouteType.UNMATCHED && route != null)
				throw new IllegalArgumentException("Route must be null when RouteType is UNMATCHED");
			requireNonNull(dropReason);
		}
	}

	/**
	 * Key for metrics grouped by Server-Sent Event comment type, route match information, and drop reason.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	record ServerSentEventCommentRouteDropKey(@NonNull RouteType routeType,
																					 @Nullable ResourcePathDeclaration route,
																					 ServerSentEventComment.@NonNull CommentType commentType,
																					 @NonNull ServerSentEventDropReason dropReason) {
		public ServerSentEventCommentRouteDropKey {
			requireNonNull(routeType);
			requireNonNull(commentType);
			if (routeType == RouteType.MATCHED && route == null)
				throw new IllegalArgumentException("Route must be provided when RouteType is MATCHED");
			if (routeType == RouteType.UNMATCHED && route != null)
				throw new IllegalArgumentException("Route must be null when RouteType is UNMATCHED");
			requireNonNull(dropReason);
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
	static MetricsCollector defaultInstance() {
		return DefaultMetricsCollector.defaultInstance();
	}

	/**
	 * Acquires a threadsafe {@link MetricsCollector} instance that performs no work.
	 * <p>
	 * This method is useful when you want to explicitly disable metrics collection without writing your own implementation.
	 * <p>
	 * The returned instance is guaranteed to be a JVM-wide singleton.
	 *
	 * @return a no-op {@code MetricsCollector}
	 */
	@NonNull
	static MetricsCollector disabledInstance() {
		return DisabledMetricsCollector.defaultInstance();
	}
}
