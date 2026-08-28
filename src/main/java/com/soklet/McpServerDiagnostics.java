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
import java.net.InetSocketAddress;
import java.util.Optional;

/**
 * An immutable point-in-time snapshot of MCP server diagnostics.
 * <p>
 * A retained snapshot never changes. Obtain a new snapshot to observe a later
 * lifecycle state.
 * <p>
 * Lifecycle, handler-capacity, and stream values are captured together at one
 * runtime linearization point. Protection and trace-correlation values are
 * captured together at a separate security-control linearization point. No
 * invariant spans those independently mutable domains.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public interface McpServerDiagnostics {
	/**
	 * The lifecycle status captured by this snapshot.
	 *
	 * @return the server status
	 */
	@NonNull
	McpServerStatus getStatus();

	/**
	 * The effective bound address captured by this snapshot.
	 * <p>
	 * The address is empty until binding succeeds and includes the
	 * operating-system-assigned port when ephemeral port {@code 0} was
	 * configured. Once published, the effective address remains available as
	 * historical evidence in later shutdown and terminal snapshots.
	 *
	 * @return the effective or historically retained bound address, or empty when
	 * this server never bound
	 */
	@NonNull
	Optional<@NonNull InetSocketAddress> getBoundAddress();

	/**
	 * The configured maximum number of application request handlers that may
	 * execute concurrently.
	 * <p>
	 * This value is stable across the owner lifecycle.
	 *
	 * @return the configured request-handler concurrency
	 */
	@NonNull
	Integer getRequestHandlerConcurrency();

	/**
	 * The configured maximum number of admitted application requests that may
	 * wait for a request-handler execution slot.
	 * <p>
	 * This value is stable across the owner lifecycle.
	 *
	 * @return the configured request-handler queue capacity
	 */
	@NonNull
	Integer getRequestHandlerQueueCapacity();

	/**
	 * The number of application request handlers occupying execution slots in
	 * this snapshot.
	 * <p>
	 * The value includes residual handlers that continue running after server
	 * stop and is between zero and {@link #getRequestHandlerConcurrency()}.
	 *
	 * @return the active handler-execution count
	 */
	@NonNull
	Integer getActiveHandlerExecutions();

	/**
	 * The number of admitted application requests waiting for a request-handler
	 * execution slot in this snapshot.
	 * <p>
	 * The value is zero after a completed server stop transition and is between
	 * zero and {@link #getRequestHandlerQueueCapacity()}. A transient residual
	 * snapshot captured while failure cleanup is draining work may remain nonzero.
	 *
	 * @return the queued-request count
	 */
	@NonNull
	Integer getQueuedRequests();

	/**
	 * The number of open request-scoped SSE streams in this snapshot.
	 * <p>
	 * This count includes a resource subscription once its acknowledgment stream
	 * has opened; it does not imply client receipt. The value is nonnegative and
	 * is zero after a completed server stop transition. A transient snapshot
	 * captured while failure cleanup is closing streams may remain nonzero.
	 *
	 * @return the active request-stream count
	 */
	@NonNull
	Integer getActiveRequestStreams();

	/**
	 * The number of open resource subscriptions whose request-scoped SSE streams
	 * remain open in this snapshot.
	 * <p>
	 * The count includes a subscription once its acknowledgment stream has
	 * opened; it does not imply client receipt. This value is nonnegative, never
	 * exceeds {@link #getActiveRequestStreams()}, and is zero after a completed
	 * server stop transition. A transient snapshot captured while failure cleanup
	 * is closing subscriptions may remain nonzero.
	 *
	 * @return the active subscription count
	 */
	@NonNull
	Integer getActiveSubscriptions();

	/**
	 * The effective framework request-state protection mode in this snapshot.
	 * <p>
	 * This value is fixed when the server is built and remains stable across
	 * the one-shot owner lifecycle.
	 *
	 * @return the effective protection mode
	 */
	@NonNull
	McpProtectionMode getProtectionMode();

	/**
	 * Whether an application-owned request-state protector is configured in this
	 * snapshot.
	 * <p>
	 * This value is {@code true} exactly when {@link #getProtectionMode()} is
	 * {@link McpProtectionMode#CUSTOM_PROTECTOR}. It reports provider selection
	 * without exposing the protector instance or its configuration. It does not
	 * report whether an operation declares
	 * {@link McpRequestStateMode#APPLICATION_PROTECTED}; it reports only custom-
	 * protector selection for canonical
	 * {@link McpRequestStateMode#FRAMEWORK_PROTECTED} state.
	 *
	 * @return whether a custom application request-state protector is configured
	 */
	@NonNull
	Boolean isApplicationRequestStateProtectorConfigured();

	/**
	 * The secret-free fingerprint of the complete live production protection key
	 * ring in this snapshot.
	 * <p>
	 * The fingerprint is present exactly when {@link #getProtectionMode()} is
	 * {@link McpProtectionMode#PRODUCTION_KEY_RING}. Successful live ring
	 * mutations are reflected only in subsequently obtained snapshots. The value
	 * excludes trace-correlation configuration, request-state epochs, and cursors,
	 * and exposes neither raw keys nor per-key fingerprint tags. It is operational
	 * deployment-comparison metadata, not an authentication input.
	 *
	 * @return the live production key-ring fingerprint, or the empty optional
	 */
	@NonNull
	Optional<@NonNull McpProtectionKeyRingFingerprint>
			getProtectionKeyRingFingerprint();

	/**
	 * The secret-free fingerprint of the active trace-correlation configuration
	 * in this snapshot.
	 * <p>
	 * The fingerprint is present exactly when trace correlation was enabled at
	 * server construction. Successful trace-key rotations are reflected only in
	 * subsequently obtained snapshots. This value is independent of the request-
	 * state protection mode, exposes no raw key material, and is operational
	 * deployment-comparison metadata rather than a token-derivation or
	 * authentication input.
	 *
	 * @return the active trace-correlation configuration fingerprint, or the empty
	 *         optional when trace correlation is disabled
	 */
	@NonNull
	Optional<@NonNull McpTraceCorrelationConfigurationFingerprint>
			getTraceCorrelationConfigurationFingerprint();
}
