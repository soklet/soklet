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

import com.soklet.McpRequestStateProtectionException;
import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.RequestStateProtectionInput;
import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.RequestStateProtectionPlan;
import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Stateless canonicalization, binding, and protection coordinator for
 * framework-managed MCP request state.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class McpFrameworkRequestStateRuntime {
	@NonNull
	private final Optional<@NonNull RequestStateProtectionPlan> protectionPlan;
	@NonNull
	private final Clock clock;

	McpFrameworkRequestStateRuntime(
			@NonNull Optional<@NonNull RequestStateProtectionPlan> protectionPlan,
			@NonNull Clock clock) {
		this.protectionPlan = requireNonNull(protectionPlan);
		this.clock = requireNonNull(clock);
	}

	@NonNull
	static McpFrameworkRequestStateRuntime disabledInstance() {
		return new McpFrameworkRequestStateRuntime(Optional.empty(),
				Clock.systemUTC());
	}

	void validateStructure(@NonNull String protectedState)
			throws McpInvalidRequestStateException,
			McpRequestStateUnavailableException {
		RequestStateProtectionPlan plan = requirePlan();
		try {
			if (requireNonNull(protectedState).isEmpty())
				throw new IllegalArgumentException();
			McpRequestStateCanonicalJson.strictUtf8(protectedState,
					plan.maximumEncodedRequestStateBytes(),
					"Protected MCP request state");
		} catch (IllegalArgumentException exception) {
			throw new McpInvalidRequestStateException();
		}
		try {
			plan.adapter().validateStructure(protectedState);
		} catch (McpRequestStateProtectionException exception) {
			throwMappedOpenFailure(exception);
		}
	}

	@NonNull
	OpenedState open(@NonNull String endpointPath,
			@NonNull String protocolVersion, @NonNull String method,
			@NonNull Optional<@NonNull String> authorizationPartitionKey,
			@NonNull McpJsonObject completeValidatedParameters,
			@NonNull McpJsonRpcId currentRequestId,
			@NonNull String protectedState)
			throws McpInvalidRequestStateException,
			McpRequestStateUnavailableException {
		RequestStateProtectionPlan plan = requirePlan();
		McpRequestStateBinding binding = McpRequestStateBinding.create(
				endpointPath, protocolVersion, method,
				authorizationPartitionKey, completeValidatedParameters);
		RequestStateProtectionInput input = protectionInput(endpointPath,
				protocolVersion, method, binding);
		byte[] plaintext;
		try {
			plaintext = requireNonNull(plan.adapter().open(input,
					protectedState),
					"The MCP request-state protection adapter returned null.");
		} catch (McpRequestStateProtectionException exception) {
			throwMappedOpenFailure(exception);
			throw new AssertionError("Unreachable request-state failure mapping.");
		}

		try {
			McpFrameworkRequestStateContinuation continuation;
			try {
				continuation = McpRequestStatePlaintextCodec.decode(
						plaintext, binding,
						plan.maximumDecodedRequestStateBytes(),
						plan.maximumRequestStateLifetime(),
						plan.maximumRequestStateRounds(), clock.instant(),
						currentRequestId);
			} catch (IllegalArgumentException exception) {
				throw new McpInvalidRequestStateException();
			}
			return new OpenedState(continuation.state(), continuation);
		} finally {
			Arrays.fill(plaintext, (byte) 0);
		}
	}

	@NonNull
	String seal(@NonNull String endpointPath,
			@NonNull String protocolVersion, @NonNull String method,
			@NonNull Optional<@NonNull String> authorizationPartitionKey,
			@NonNull McpJsonObject completeValidatedParameters,
			@NonNull McpJsonRpcId currentRequestId,
			@NonNull McpJsonValue state,
			@NonNull Optional<@NonNull McpFrameworkRequestStateContinuation>
					priorContinuation,
			@NonNull Optional<@NonNull String> selectedLocale)
			throws McpRequestStateUnavailableException {
		RequestStateProtectionPlan plan = requirePlan();
		McpRequestStateBinding binding = McpRequestStateBinding.create(
				endpointPath, protocolVersion, method,
				authorizationPartitionKey, completeValidatedParameters);
		Instant now = clock.instant();
		McpFrameworkRequestStateContinuation continuation;
		if (priorContinuation.isPresent()) {
			McpFrameworkRequestStateContinuation prior =
					priorContinuation.orElseThrow();
			if (McpRequestStateTimestamp.fromInstant(now)
					.compareTo(prior.expiresAt()) >= 0)
				throw new IllegalArgumentException(
						"Expired framework request state cannot be re-emitted.");
			// Carry-forward keeps the original continuation's language exactly;
			// the current selection was already enforced equal on open.
			continuation = prior.next(state, currentRequestId,
					plan.maximumRequestStateRounds());
		} else {
			// A localized flow mints version 2 from its first input_required
			// round; without localization the exact version-1 bytes persist.
			continuation = McpFrameworkRequestStateContinuation.initial(
					state, now, plan.maximumRequestStateLifetime(),
					currentRequestId, selectedLocale.orElse(null));
		}

		byte[] plaintext = McpRequestStatePlaintextCodec.encode(
				continuation, binding,
				plan.maximumDecodedRequestStateBytes(),
				plan.maximumRequestStateLifetime(),
				plan.maximumRequestStateRounds());
		try {
			RequestStateProtectionInput input = protectionInput(endpointPath,
					protocolVersion, method, binding);
			try {
				String protectedState = requireNonNull(
						plan.adapter().seal(input, plaintext),
						"The MCP request-state protection adapter returned null.");
				if (protectedState.isEmpty())
					throw new IllegalStateException(
							"The MCP request-state protection adapter returned empty state.");
				McpRequestStateCanonicalJson.strictUtf8(protectedState,
						plan.maximumEncodedRequestStateBytes(),
						"Protected MCP request state");
				return protectedState;
			} catch (McpRequestStateProtectionException exception) {
				if (exception.getReason()
						== McpRequestStateProtectionException.Reason
								.PROTECTOR_UNAVAILABLE)
					throw new McpRequestStateUnavailableException();
				throw new IllegalStateException(
						"The MCP request-state protection adapter rejected framework output.");
			}
		} finally {
			Arrays.fill(plaintext, (byte) 0);
		}
	}

	@NonNull
	private RequestStateProtectionPlan requirePlan() {
		return protectionPlan.orElseThrow(() -> new IllegalStateException(
				"Framework request-state protection is not configured."));
	}

	@NonNull
	private static RequestStateProtectionInput protectionInput(
			@NonNull String endpointPath, @NonNull String protocolVersion,
			@NonNull String method, @NonNull McpRequestStateBinding binding) {
		return new RequestStateProtectionInput(endpointPath, protocolVersion,
				method, binding.bytes());
	}

	private static void throwMappedOpenFailure(
			@NonNull McpRequestStateProtectionException exception)
			throws McpInvalidRequestStateException,
			McpRequestStateUnavailableException {
		if (requireNonNull(exception).getReason()
				== McpRequestStateProtectionException.Reason.INVALID_STATE)
			throw new McpInvalidRequestStateException();
		throw new McpRequestStateUnavailableException();
	}

	/** Verified state plus the hidden continuation needed by another round. */
	@ThreadSafe
	record OpenedState(@NonNull McpJsonValue state,
			@NonNull McpFrameworkRequestStateContinuation continuation) {
		OpenedState {
			requireNonNull(state);
			requireNonNull(continuation);
		}
	}
}

/** Sanitized attacker-controlled invalid-state control signal. */
@NotThreadSafe
final class McpInvalidRequestStateException extends Exception {
	private static final long serialVersionUID = 1L;

	McpInvalidRequestStateException() {
		super(null, null, false, false);
	}
}

/** Sanitized temporary protection-unavailability control signal. */
@NotThreadSafe
final class McpRequestStateUnavailableException extends Exception {
	private static final long serialVersionUID = 1L;

	McpRequestStateUnavailableException() {
		super(null, null, false, false);
	}
}
