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
import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * Immutable snapshot of collected metrics.
 * <p>
 * Durations are in nanoseconds, sizes are in bytes, and queue depths are raw counts.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class MetricsSnapshot {
	private final long activeRequests;
	private final long activeSseConnections;
	@NonNull
	private final Map<@NonNull HttpMethodRouteStatusKey, @NonNull HistogramSnapshot> httpRequestDurations;
	@NonNull
	private final Map<@NonNull HttpMethodRouteStatusKey, @NonNull HistogramSnapshot> httpHandlerDurations;
	@NonNull
	private final Map<@NonNull HttpMethodRouteStatusKey, @NonNull HistogramSnapshot> httpTimeToFirstByte;
	@NonNull
	private final Map<@NonNull HttpMethodRouteKey, @NonNull HistogramSnapshot> httpRequestBodyBytes;
	@NonNull
	private final Map<@NonNull HttpMethodRouteStatusKey, @NonNull HistogramSnapshot> httpResponseBodyBytes;
	@NonNull
	private final Map<@NonNull SseRouteKey, @NonNull HistogramSnapshot> sseTimeToFirstEvent;
	@NonNull
	private final Map<@NonNull SseRouteKey, @NonNull HistogramSnapshot> sseEventWriteDurations;
	@NonNull
	private final Map<@NonNull SseRouteKey, @NonNull HistogramSnapshot> sseEventDeliveryLag;
	@NonNull
	private final Map<@NonNull SseRouteKey, @NonNull HistogramSnapshot> sseEventSizes;
	@NonNull
	private final Map<@NonNull SseRouteKey, @NonNull HistogramSnapshot> sseQueueDepth;
	@NonNull
	private final Map<@NonNull SseRouteTerminationKey, @NonNull HistogramSnapshot> sseConnectionDurations;

	public MetricsSnapshot(long activeRequests,
												 long activeSseConnections,
												 @NonNull Map<@NonNull HttpMethodRouteStatusKey, @NonNull HistogramSnapshot> httpRequestDurations,
												 @NonNull Map<@NonNull HttpMethodRouteStatusKey, @NonNull HistogramSnapshot> httpHandlerDurations,
												 @NonNull Map<@NonNull HttpMethodRouteStatusKey, @NonNull HistogramSnapshot> httpTimeToFirstByte,
												 @NonNull Map<@NonNull HttpMethodRouteKey, @NonNull HistogramSnapshot> httpRequestBodyBytes,
												 @NonNull Map<@NonNull HttpMethodRouteStatusKey, @NonNull HistogramSnapshot> httpResponseBodyBytes,
												 @NonNull Map<@NonNull SseRouteKey, @NonNull HistogramSnapshot> sseTimeToFirstEvent,
												 @NonNull Map<@NonNull SseRouteKey, @NonNull HistogramSnapshot> sseEventWriteDurations,
												 @NonNull Map<@NonNull SseRouteKey, @NonNull HistogramSnapshot> sseEventDeliveryLag,
												 @NonNull Map<@NonNull SseRouteKey, @NonNull HistogramSnapshot> sseEventSizes,
												 @NonNull Map<@NonNull SseRouteKey, @NonNull HistogramSnapshot> sseQueueDepth,
												 @NonNull Map<@NonNull SseRouteTerminationKey, @NonNull HistogramSnapshot> sseConnectionDurations) {
		this.activeRequests = activeRequests;
		this.activeSseConnections = activeSseConnections;
		this.httpRequestDurations = Map.copyOf(requireNonNull(httpRequestDurations));
		this.httpHandlerDurations = Map.copyOf(requireNonNull(httpHandlerDurations));
		this.httpTimeToFirstByte = Map.copyOf(requireNonNull(httpTimeToFirstByte));
		this.httpRequestBodyBytes = Map.copyOf(requireNonNull(httpRequestBodyBytes));
		this.httpResponseBodyBytes = Map.copyOf(requireNonNull(httpResponseBodyBytes));
		this.sseTimeToFirstEvent = Map.copyOf(requireNonNull(sseTimeToFirstEvent));
		this.sseEventWriteDurations = Map.copyOf(requireNonNull(sseEventWriteDurations));
		this.sseEventDeliveryLag = Map.copyOf(requireNonNull(sseEventDeliveryLag));
		this.sseEventSizes = Map.copyOf(requireNonNull(sseEventSizes));
		this.sseQueueDepth = Map.copyOf(requireNonNull(sseQueueDepth));
		this.sseConnectionDurations = Map.copyOf(requireNonNull(sseConnectionDurations));
	}

	public long getActiveRequests() {
		return this.activeRequests;
	}

	public long getActiveSseConnections() {
		return this.activeSseConnections;
	}

	@NonNull
	public Map<@NonNull HttpMethodRouteStatusKey, @NonNull HistogramSnapshot> getHttpRequestDurations() {
		return this.httpRequestDurations;
	}

	@NonNull
	public Map<@NonNull HttpMethodRouteStatusKey, @NonNull HistogramSnapshot> getHttpHandlerDurations() {
		return this.httpHandlerDurations;
	}

	@NonNull
	public Map<@NonNull HttpMethodRouteStatusKey, @NonNull HistogramSnapshot> getHttpTimeToFirstByte() {
		return this.httpTimeToFirstByte;
	}

	@NonNull
	public Map<@NonNull HttpMethodRouteKey, @NonNull HistogramSnapshot> getHttpRequestBodyBytes() {
		return this.httpRequestBodyBytes;
	}

	@NonNull
	public Map<@NonNull HttpMethodRouteStatusKey, @NonNull HistogramSnapshot> getHttpResponseBodyBytes() {
		return this.httpResponseBodyBytes;
	}

	@NonNull
	public Map<@NonNull SseRouteKey, @NonNull HistogramSnapshot> getSseTimeToFirstEvent() {
		return this.sseTimeToFirstEvent;
	}

	@NonNull
	public Map<@NonNull SseRouteKey, @NonNull HistogramSnapshot> getSseEventWriteDurations() {
		return this.sseEventWriteDurations;
	}

	@NonNull
	public Map<@NonNull SseRouteKey, @NonNull HistogramSnapshot> getSseEventDeliveryLag() {
		return this.sseEventDeliveryLag;
	}

	@NonNull
	public Map<@NonNull SseRouteKey, @NonNull HistogramSnapshot> getSseEventSizes() {
		return this.sseEventSizes;
	}

	@NonNull
	public Map<@NonNull SseRouteKey, @NonNull HistogramSnapshot> getSseQueueDepth() {
		return this.sseQueueDepth;
	}

	@NonNull
	public Map<@NonNull SseRouteTerminationKey, @NonNull HistogramSnapshot> getSseConnectionDurations() {
		return this.sseConnectionDurations;
	}
}
