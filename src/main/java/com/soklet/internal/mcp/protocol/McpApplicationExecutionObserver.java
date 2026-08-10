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

import com.soklet.MetricsCollector;
import com.soklet.McpRequestContext;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.ThreadSafe;

/**
 * Internal two-phase observation boundary for semantic MCP metrics. Record
 * methods may be invoked while runtime transition locks are held and must only
 * enqueue immutable transition state. {@link #drain()} is invoked after those
 * locks are released and may deliver the queued transitions.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public interface McpApplicationExecutionObserver {
	/** Opaque identity for a provisionally recorded metric transition. */
	interface PendingMetricRecord {
	}

	void beginDeferral();

	default void beginRequestTransitionDeferral() {
		beginDeferral();
	}

	@NonNull
	default PendingMetricRecord recordRequestAccepted() {
		return DisabledPendingMetricRecord.INSTANCE;
	}

	default void discardPendingMetric(
			@NonNull PendingMetricRecord pendingMetricRecord) {
		if (pendingMetricRecord == null)
			throw new NullPointerException("pendingMetricRecord");
	}

	default void recordRequestRejected() {
	}

	default void recordConnectionAccepted() {
	}

	default void recordConnectionRejected() {
	}

	@NonNull
	default PendingMetricRecord recordTransportFailure(
			MetricsCollector.@NonNull TransportFailureReason reason) {
		if (reason == null)
			throw new NullPointerException("reason");
		return DisabledPendingMetricRecord.INSTANCE;
	}

	@NonNull
	default PendingMetricRecord recordProtocolError(int code,
			@Nullable McpRequestContext requestContext) {
		return DisabledPendingMetricRecord.INSTANCE;
	}

	default void recordUnknownMirroredHeader(@NonNull String endpointPath,
			@NonNull String jsonRpcMethod) {
		if (endpointPath == null)
			throw new NullPointerException("endpointPath");
		if (jsonRpcMethod == null)
			throw new NullPointerException("jsonRpcMethod");
	}

	void recordHandlerExecutionStarted();

	void recordHandlerExecutionFinished();

	void recordHandlerQueued();

	void recordHandlerDequeued();

	void recordHandlerCapacityRejected();

	void drain();

	void endDeferral();

	default void endDeferralForAsynchronousDrain() {
		endDeferral();
	}

	default void drainAsynchronously() {
		drain();
	}

	@NonNull
	static McpApplicationExecutionObserver disabledInstance() {
		return DisabledMcpApplicationExecutionObserver.INSTANCE;
	}
}

/** Shared no-op provisional metric identity. */
enum DisabledPendingMetricRecord
		implements McpApplicationExecutionObserver.PendingMetricRecord {
	INSTANCE
}

/**
 * Shared no-op application-execution observer.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
enum DisabledMcpApplicationExecutionObserver
		implements McpApplicationExecutionObserver {
	INSTANCE;

	@Override
	public void beginDeferral() {
	}

	@Override
	public void recordHandlerExecutionStarted() {
	}

	@Override
	public void recordHandlerExecutionFinished() {
	}

	@Override
	public void recordHandlerQueued() {
	}

	@Override
	public void recordHandlerDequeued() {
	}

	@Override
	public void recordHandlerCapacityRejected() {
	}

	@Override
	public void drain() {
	}

	@Override
	public void endDeferral() {
	}
}
