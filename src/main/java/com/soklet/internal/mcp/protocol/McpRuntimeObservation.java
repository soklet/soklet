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

import com.soklet.McpRequestContext;
import com.soklet.McpRequestOutcome;
import com.soklet.McpRequestState;
import com.soklet.Request;
import com.soklet.StreamTerminationReason;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.ThreadSafe;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Internal admitted-request observation boundary. Implementations must contain
 * callback failures and must not expose protocol-runtime types to applications.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
@FunctionalInterface
interface McpRuntimeObservationSink {
	@NonNull
	McpRuntimeRequestObservation didStartRequest(
			@NonNull McpRuntimeRequestInput input);

	@NonNull
	static McpRuntimeObservationSink disabledInstance() {
		return ignored -> McpRuntimeRequestObservation.disabledInstance();
	}
}

/**
 * One admitted request's observation handle. The optional public context is
 * propagated into production application invocations so lifecycle and handler
 * callbacks observe the same immutable instance.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
interface McpRuntimeRequestObservation {
	@NonNull
	Optional<@NonNull McpRequestContext> publicContext();

	void didFinish(@NonNull McpRequestOutcome outcome,
			@Nullable McpJsonRpcError error, @NonNull Duration duration,
			@NonNull List<@NonNull Throwable> throwables);

	default void didOpenRequestStream() {
	}

	default void didCloseRequestStream(@NonNull StreamTerminationReason reason,
			@NonNull Duration duration) {
		requireNonNull(reason);
		requireNonNull(duration);
	}

	default void didOpenSubscription() {
	}

	default void didCloseSubscription(@NonNull StreamTerminationReason reason,
			@NonNull Duration duration) {
		requireNonNull(reason);
		requireNonNull(duration);
	}

	default void didEmitKeepAlive() {
	}

	@NonNull
	static McpRuntimeRequestObservation disabledInstance() {
		return DisabledMcpRuntimeRequestObservation.INSTANCE;
	}
}

/**
 * Immutable internal input for one admitted semantic request or notification.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
record McpRuntimeRequestInput(@NonNull Request request,
		@NonNull Map<@NonNull String, @NonNull String> endpointPathParameters,
		@NonNull String jsonRpcMethod,
		@NonNull Optional<@NonNull McpJsonRpcId> requestId,
		@NonNull String protocolVersion,
		@NonNull Optional<@NonNull String> operationName,
		@NonNull Optional<@NonNull McpImplementationMetadata> clientInformation,
		@NonNull McpJsonObject clientCapabilities,
		@NonNull McpJsonObject requestMetadata,
		@NonNull McpJsonObject inputResponses,
		@NonNull Optional<@NonNull McpRequestState> requestState,
		@NonNull McpAdmissionIdentity admissionIdentity) {
	McpRuntimeRequestInput(@NonNull Request request,
			@NonNull Map<@NonNull String, @NonNull String> endpointPathParameters,
			@NonNull String jsonRpcMethod,
			@NonNull Optional<@NonNull McpJsonRpcId> requestId,
			@NonNull String protocolVersion,
			@NonNull Optional<@NonNull String> operationName,
			@NonNull Optional<@NonNull McpImplementationMetadata> clientInformation,
			@NonNull McpJsonObject clientCapabilities,
			@NonNull McpJsonObject requestMetadata,
			@NonNull McpJsonObject inputResponses,
			@NonNull McpAdmissionIdentity admissionIdentity) {
		this(request, endpointPathParameters, jsonRpcMethod, requestId,
				protocolVersion, operationName, clientInformation,
				clientCapabilities, requestMetadata, inputResponses,
				Optional.empty(), admissionIdentity);
	}

	McpRuntimeRequestInput(@NonNull Request request,
			@NonNull Map<@NonNull String, @NonNull String> endpointPathParameters,
			@NonNull String jsonRpcMethod,
			@NonNull Optional<@NonNull McpJsonRpcId> requestId,
			@NonNull String protocolVersion,
			@NonNull Optional<@NonNull String> operationName,
			@NonNull Optional<@NonNull McpImplementationMetadata> clientInformation,
			@NonNull McpJsonObject clientCapabilities,
			@NonNull McpJsonObject requestMetadata,
			@NonNull McpAdmissionIdentity admissionIdentity) {
		this(request, endpointPathParameters, jsonRpcMethod, requestId,
				protocolVersion, operationName, clientInformation,
				clientCapabilities, requestMetadata, McpJsonObject.empty(),
				Optional.empty(), admissionIdentity);
	}

	McpRuntimeRequestInput {
		requireNonNull(request);
		endpointPathParameters = Map.copyOf(
				requireNonNull(endpointPathParameters));
		requireNonNull(jsonRpcMethod);
		requireNonNull(requestId);
		requireNonNull(protocolVersion);
		requireNonNull(operationName);
		requireNonNull(clientInformation);
		requireNonNull(clientCapabilities);
		requireNonNull(requestMetadata);
		requireNonNull(inputResponses);
		requireNonNull(requestState);
		requireNonNull(admissionIdentity);
	}
}

/**
 * Shared no-op observation handle.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
enum DisabledMcpRuntimeRequestObservation implements McpRuntimeRequestObservation {
	INSTANCE;

	@Override
	@NonNull
	public Optional<@NonNull McpRequestContext> publicContext() {
		return Optional.empty();
	}

	@Override
	public void didFinish(@NonNull McpRequestOutcome outcome,
			@Nullable McpJsonRpcError error, @NonNull Duration duration,
			@NonNull List<@NonNull Throwable> throwables) {
		requireNonNull(outcome);
		requireNonNull(duration);
		requireNonNull(throwables);
	}
}
