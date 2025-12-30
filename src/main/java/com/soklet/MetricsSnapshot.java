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

import com.soklet.MetricsCollector.ServerRouteKey;
import com.soklet.MetricsCollector.ServerRouteStatusKey;
import com.soklet.MetricsCollector.ServerSentEventCommentRouteKey;
import com.soklet.MetricsCollector.ServerSentEventRouteKey;
import com.soklet.MetricsCollector.ServerSentEventRouteTerminationKey;
import com.soklet.MetricsCollector.Snapshot;
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
	private final Map<@NonNull ServerRouteStatusKey, @NonNull Snapshot> httpRequestDurations;
	@NonNull
	private final Map<@NonNull ServerRouteStatusKey, @NonNull Snapshot> httpHandlerDurations;
	@NonNull
	private final Map<@NonNull ServerRouteStatusKey, @NonNull Snapshot> httpTimeToFirstByte;
	@NonNull
	private final Map<@NonNull ServerRouteKey, @NonNull Snapshot> httpRequestBodyBytes;
	@NonNull
	private final Map<@NonNull ServerRouteStatusKey, @NonNull Snapshot> httpResponseBodyBytes;
	@NonNull
	private final Map<@NonNull ServerSentEventRouteKey, @NonNull Snapshot> sseTimeToFirstEvent;
	@NonNull
	private final Map<@NonNull ServerSentEventRouteKey, @NonNull Snapshot> sseEventWriteDurations;
	@NonNull
	private final Map<@NonNull ServerSentEventRouteKey, @NonNull Snapshot> sseEventDeliveryLag;
	@NonNull
	private final Map<@NonNull ServerSentEventRouteKey, @NonNull Snapshot> sseEventSizes;
	@NonNull
	private final Map<@NonNull ServerSentEventRouteKey, @NonNull Snapshot> sseQueueDepth;
	@NonNull
	private final Map<@NonNull ServerSentEventCommentRouteKey, @NonNull Snapshot> sseCommentDeliveryLag;
	@NonNull
	private final Map<@NonNull ServerSentEventCommentRouteKey, @NonNull Snapshot> sseCommentSizes;
	@NonNull
	private final Map<@NonNull ServerSentEventCommentRouteKey, @NonNull Snapshot> sseCommentQueueDepth;
	@NonNull
	private final Map<@NonNull ServerSentEventRouteTerminationKey, @NonNull Snapshot> sseConnectionDurations;

	public MetricsSnapshot(long activeRequests,
												 long activeSseConnections,
												 @NonNull Map<@NonNull ServerRouteStatusKey, @NonNull Snapshot> httpRequestDurations,
												 @NonNull Map<@NonNull ServerRouteStatusKey, @NonNull Snapshot> httpHandlerDurations,
												 @NonNull Map<@NonNull ServerRouteStatusKey, @NonNull Snapshot> httpTimeToFirstByte,
												 @NonNull Map<@NonNull ServerRouteKey, @NonNull Snapshot> httpRequestBodyBytes,
												 @NonNull Map<@NonNull ServerRouteStatusKey, @NonNull Snapshot> httpResponseBodyBytes,
												 @NonNull Map<@NonNull ServerSentEventRouteKey, @NonNull Snapshot> sseTimeToFirstEvent,
												 @NonNull Map<@NonNull ServerSentEventRouteKey, @NonNull Snapshot> sseEventWriteDurations,
												 @NonNull Map<@NonNull ServerSentEventRouteKey, @NonNull Snapshot> sseEventDeliveryLag,
												 @NonNull Map<@NonNull ServerSentEventRouteKey, @NonNull Snapshot> sseEventSizes,
												 @NonNull Map<@NonNull ServerSentEventRouteKey, @NonNull Snapshot> sseQueueDepth,
												 @NonNull Map<@NonNull ServerSentEventCommentRouteKey, @NonNull Snapshot> sseCommentDeliveryLag,
												 @NonNull Map<@NonNull ServerSentEventCommentRouteKey, @NonNull Snapshot> sseCommentSizes,
												 @NonNull Map<@NonNull ServerSentEventCommentRouteKey, @NonNull Snapshot> sseCommentQueueDepth,
												 @NonNull Map<@NonNull ServerSentEventRouteTerminationKey, @NonNull Snapshot> sseConnectionDurations) {
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
		this.sseCommentDeliveryLag = Map.copyOf(requireNonNull(sseCommentDeliveryLag));
		this.sseCommentSizes = Map.copyOf(requireNonNull(sseCommentSizes));
		this.sseCommentQueueDepth = Map.copyOf(requireNonNull(sseCommentQueueDepth));
		this.sseConnectionDurations = Map.copyOf(requireNonNull(sseConnectionDurations));
	}

	public long getActiveRequests() {
		return this.activeRequests;
	}

	public long getActiveSseConnections() {
		return this.activeSseConnections;
	}

	@NonNull
	public Map<@NonNull ServerRouteStatusKey, @NonNull Snapshot> getHttpRequestDurations() {
		return this.httpRequestDurations;
	}

	@NonNull
	public Map<@NonNull ServerRouteStatusKey, @NonNull Snapshot> getHttpHandlerDurations() {
		return this.httpHandlerDurations;
	}

	@NonNull
	public Map<@NonNull ServerRouteStatusKey, @NonNull Snapshot> getHttpTimeToFirstByte() {
		return this.httpTimeToFirstByte;
	}

	@NonNull
	public Map<@NonNull ServerRouteKey, @NonNull Snapshot> getHttpRequestBodyBytes() {
		return this.httpRequestBodyBytes;
	}

	@NonNull
	public Map<@NonNull ServerRouteStatusKey, @NonNull Snapshot> getHttpResponseBodyBytes() {
		return this.httpResponseBodyBytes;
	}

	@NonNull
	public Map<@NonNull ServerSentEventRouteKey, @NonNull Snapshot> getSseTimeToFirstEvent() {
		return this.sseTimeToFirstEvent;
	}

	@NonNull
	public Map<@NonNull ServerSentEventRouteKey, @NonNull Snapshot> getSseEventWriteDurations() {
		return this.sseEventWriteDurations;
	}

	@NonNull
	public Map<@NonNull ServerSentEventRouteKey, @NonNull Snapshot> getSseEventDeliveryLag() {
		return this.sseEventDeliveryLag;
	}

	@NonNull
	public Map<@NonNull ServerSentEventRouteKey, @NonNull Snapshot> getSseEventSizes() {
		return this.sseEventSizes;
	}

	@NonNull
	public Map<@NonNull ServerSentEventRouteKey, @NonNull Snapshot> getSseQueueDepth() {
		return this.sseQueueDepth;
	}

	@NonNull
	public Map<@NonNull ServerSentEventCommentRouteKey, @NonNull Snapshot> getSseCommentDeliveryLag() {
		return this.sseCommentDeliveryLag;
	}

	@NonNull
	public Map<@NonNull ServerSentEventCommentRouteKey, @NonNull Snapshot> getSseCommentSizes() {
		return this.sseCommentSizes;
	}

	@NonNull
	public Map<@NonNull ServerSentEventCommentRouteKey, @NonNull Snapshot> getSseCommentQueueDepth() {
		return this.sseCommentQueueDepth;
	}

	@NonNull
	public Map<@NonNull ServerSentEventRouteTerminationKey, @NonNull Snapshot> getSseConnectionDurations() {
		return this.sseConnectionDurations;
	}
}
