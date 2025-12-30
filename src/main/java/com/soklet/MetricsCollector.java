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
import java.util.List;

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
	 */
	default void didWriteServerSentEvent(@NonNull ServerSentEventConnection serverSentEventConnection,
																			 @NonNull ServerSentEvent serverSentEvent,
																			 @NonNull Duration writeDuration) {
		// No-op by default
	}

	/**
	 * Called after an SSE event fails to write.
	 */
	default void didFailToWriteServerSentEvent(@NonNull ServerSentEventConnection serverSentEventConnection,
																						 @NonNull ServerSentEvent serverSentEvent,
																						 @NonNull Duration writeDuration,
																						 @NonNull Throwable throwable) {
		// No-op by default
	}

	/**
	 * Called before an SSE comment is written.
	 */
	default void willWriteServerSentEventComment(@NonNull ServerSentEventConnection serverSentEventConnection,
																							 @NonNull String comment) {
		// No-op by default
	}

	/**
	 * Called after an SSE comment is written.
	 */
	default void didWriteServerSentEventComment(@NonNull ServerSentEventConnection serverSentEventConnection,
																							@NonNull String comment,
																							@NonNull Duration writeDuration) {
		// No-op by default
	}

	/**
	 * Called after an SSE comment fails to write.
	 */
	default void didFailToWriteServerSentEventComment(@NonNull ServerSentEventConnection serverSentEventConnection,
																										@NonNull String comment,
																										@NonNull Duration writeDuration,
																										@NonNull Throwable throwable) {
		// No-op by default
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
