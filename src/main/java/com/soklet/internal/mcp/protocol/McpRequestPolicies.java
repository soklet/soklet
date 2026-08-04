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

import com.soklet.Request;
import org.jspecify.annotations.Nullable;

import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Package-private Phase 3 policy contracts. The companion public API remains
 * provisional until the Phase 4 production vertical slice freezes it.
 */
record McpAdmissionContext(Request request, McpNormalizedEndpoint endpoint,
		Map<String, String> endpointPathParameters, String jsonRpcMethod,
		boolean notification, Optional<McpJsonRpcId> requestId,
		String protocolVersion, Optional<String> operationName,
		Optional<McpImplementationMetadata> clientInformation,
		Optional<McpClientCapabilities> clientCapabilities) {
	McpAdmissionContext {
		requireNonNull(request);
		requireNonNull(endpoint);
		endpointPathParameters = Map.copyOf(requireNonNull(endpointPathParameters));
		jsonRpcMethod = notification
				? requireNonNull(jsonRpcMethod)
				: requireNonBlank(jsonRpcMethod, "JSON-RPC method");
		requireNonNull(requestId);
		protocolVersion = requireNonBlank(protocolVersion, "Protocol version");
		requireNonNull(operationName);
		requireNonNull(clientInformation);
		requireNonNull(clientCapabilities);
	}

	private static String requireNonBlank(String value, String description) {
		requireNonNull(value);
		if (value.isBlank())
			throw new IllegalArgumentException(description + " must not be blank.");
		return value;
	}

	@Override
	public String toString() {
		return "McpAdmissionContext[notification=" + notification
				+ ", requestIdPresent=" + requestId.isPresent()
				+ ", operationNamePresent=" + operationName.isPresent()
				+ ", clientInformationPresent=" + clientInformation.isPresent()
				+ "]";
	}
}

final class McpAdmissionIdentity {
	static final int MAXIMUM_PARTITION_KEY_UTF_8_BYTES = 256;
	private static final McpAdmissionIdentity ANONYMOUS = new McpAdmissionIdentity(
			Optional.of("anonymous"), Optional.empty(), Optional.empty(), Optional.empty());

	private final Optional<String> rateLimitPartitionKey;
	private final Optional<String> authorizationPartitionKey;
	private final Optional<Object> principal;
	private final Optional<Object> applicationContext;

	static McpAdmissionIdentity anonymousInstance() {
		return ANONYMOUS;
	}

	static Builder withRateLimitPartitionKey(String rateLimitPartitionKey) {
		return new Builder(rateLimitPartitionKey);
	}

	private McpAdmissionIdentity(Optional<String> rateLimitPartitionKey,
			Optional<String> authorizationPartitionKey, Optional<Object> principal,
			Optional<Object> applicationContext) {
		this.rateLimitPartitionKey = requireNonNull(rateLimitPartitionKey)
				.map(value -> requirePartitionKey(value, "rateLimitPartitionKey"));
		this.authorizationPartitionKey = requireNonNull(authorizationPartitionKey)
				.map(value -> requirePartitionKey(value, "authorizationPartitionKey"));
		this.principal = requireNonNull(principal);
		this.applicationContext = requireNonNull(applicationContext);
		if (principal.isPresent() && this.authorizationPartitionKey.isEmpty())
			throw new IllegalStateException(
					"authorizationPartitionKey is required when principal is present");
		if (principal.isPresent() && this.rateLimitPartitionKey.isEmpty())
			throw new IllegalStateException(
					"rateLimitPartitionKey is required when principal is present");
	}

	boolean authenticated() {
		return principal.isPresent();
	}

	Optional<Object> principal() {
		return principal;
	}

	Optional<Object> applicationContext() {
		return applicationContext;
	}

	Optional<String> rateLimitPartitionKey() {
		return rateLimitPartitionKey;
	}

	Optional<String> authorizationPartitionKey() {
		return authorizationPartitionKey;
	}

	private static String requirePartitionKey(String value, String name) {
		requireNonNull(value);
		if (value.isBlank())
			throw new IllegalArgumentException(name + " must not be blank");

		int encodedLength;
		try {
			encodedLength = StandardCharsets.UTF_8.newEncoder()
					.onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT)
					.encode(CharBuffer.wrap(value))
					.remaining();
		} catch (CharacterCodingException exception) {
			throw new IllegalArgumentException(
					name + " must contain valid Unicode text", exception);
		}

		if (encodedLength > MAXIMUM_PARTITION_KEY_UTF_8_BYTES)
			throw new IllegalArgumentException(name + " must contain at most "
					+ MAXIMUM_PARTITION_KEY_UTF_8_BYTES + " UTF-8 bytes");
		return value;
	}

	static final class Builder {
		private final String rateLimitPartitionKey;
		private @Nullable String authorizationPartitionKey;
		private @Nullable Object principal;
		private @Nullable Object applicationContext;

		private Builder(String rateLimitPartitionKey) {
			this.rateLimitPartitionKey = requireNonNull(rateLimitPartitionKey);
		}

		Builder authorizationPartitionKey(String authorizationPartitionKey) {
			this.authorizationPartitionKey = requireNonNull(authorizationPartitionKey);
			return this;
		}

		Builder principal(Object principal) {
			this.principal = requireNonNull(principal);
			return this;
		}

		Builder applicationContext(Object applicationContext) {
			this.applicationContext = requireNonNull(applicationContext);
			return this;
		}

		McpAdmissionIdentity build() {
			return new McpAdmissionIdentity(
					Optional.of(rateLimitPartitionKey),
					Optional.ofNullable(authorizationPartitionKey),
					Optional.ofNullable(principal),
					Optional.ofNullable(applicationContext));
		}
	}
}

record McpEffectiveAdmissionIdentity(McpAdmissionIdentity admittedIdentity,
		McpEffectivePartition rateLimitPartition,
		McpEffectivePartition authorizationPartition) {
	McpEffectiveAdmissionIdentity {
		requireNonNull(admittedIdentity);
		requireNonNull(rateLimitPartition);
		requireNonNull(authorizationPartition);
	}

	static McpEffectiveAdmissionIdentity resolve(McpNormalizedEndpoint endpoint,
			String endpointPath, McpAdmissionIdentity admittedIdentity) {
		requireNonNull(endpoint);
		requireNonNull(endpointPath);
		requireNonNull(admittedIdentity);
		McpEndpointPartitionIdentity endpointIdentity =
				new McpEndpointPartitionIdentity(endpointPath);
		return new McpEffectiveAdmissionIdentity(admittedIdentity,
				new McpEffectivePartition(endpointIdentity,
						McpPartitionPurpose.RATE_LIMIT,
						admittedIdentity.rateLimitPartitionKey()),
				new McpEffectivePartition(endpointIdentity,
						McpPartitionPurpose.AUTHORIZATION,
						admittedIdentity.authorizationPartitionKey()));
	}

	@Override
	public String toString() {
		return "McpEffectiveAdmissionIdentity[authenticated="
				+ admittedIdentity.authenticated() + "]";
	}
}

record McpEndpointPartitionIdentity(String endpointPath) {
	McpEndpointPartitionIdentity {
		endpointPath = requireNonNull(endpointPath);
	}

	@Override
	public String toString() {
		return "McpEndpointPartitionIdentity[endpointPath=" + endpointPath + "]";
	}
}

enum McpPartitionPurpose {
	RATE_LIMIT,
	AUTHORIZATION
}

record McpEffectivePartition(McpEndpointPartitionIdentity endpointIdentity,
		McpPartitionPurpose purpose, Optional<String> applicationKey) {
	McpEffectivePartition {
		requireNonNull(endpointIdentity);
		requireNonNull(purpose);
		requireNonNull(applicationKey);
	}

	@Override
	public String toString() {
		return "McpEffectivePartition[purpose=" + purpose
				+ ", applicationKeyPresent=" + applicationKey.isPresent() + "]";
	}
}

sealed interface McpAdmissionDecision
		permits McpAdmissionDecision.Accepted, McpAdmissionDecision.Rejected {
	static Accepted accepted(McpAdmissionIdentity identity) {
		return new Accepted(identity);
	}

	static Accepted acceptedAnonymous() {
		return accepted(McpAdmissionIdentity.anonymousInstance());
	}

	static Rejected rejected(McpRequestRejection rejection) {
		return new Rejected(rejection);
	}

	record Accepted(McpAdmissionIdentity identity) implements McpAdmissionDecision {
		public Accepted {
			requireNonNull(identity);
		}
	}

	record Rejected(McpRequestRejection rejection) implements McpAdmissionDecision {
		public Rejected {
			requireNonNull(rejection);
		}
	}
}

/**
 * Temporary source-compatibility constants for Phase 3 tests. The public API
 * uses {@link McpAdmissionDecision}; this class is not a public contract.
 */
final class McpRequestAdmissionDecision {
	static final McpAdmissionDecision ACCEPT = McpAdmissionDecision.acceptedAnonymous();
	static final McpAdmissionDecision REJECT = McpAdmissionDecision.rejected(
			new McpRequestRejection(403,
					new McpJsonRpcError(1_000,
							"Request rejected", Optional.empty()), Map.of()));

	private McpRequestAdmissionDecision() {
	}
}

@FunctionalInterface
interface McpRequestAdmissionPolicy {
	McpAdmissionDecision admit(McpAdmissionContext context) throws Exception;

	static McpRequestAdmissionPolicy acceptAllInstance() {
		return ignored -> McpAdmissionDecision.acceptedAnonymous();
	}
}

record McpRequestRejection(int statusCode, McpJsonRpcError jsonRpcError,
		Map<String, List<String>> headers) {
	McpRequestRejection {
		if (statusCode < 400 || statusCode > 599)
			throw new IllegalArgumentException(
					"Admission rejection status must be between 400 and 599.");
		requireNonNull(jsonRpcError);
		requireNonNull(headers);
		Map<String, List<String>> copied = new LinkedHashMap<>();
		headers.forEach((name, values) -> copied.put(
				requireNonNull(name), List.copyOf(requireNonNull(values))));
		headers = Map.copyOf(copied);
	}

	@Override
	public String toString() {
		return "McpRequestRejection[statusCode=" + statusCode
				+ ", jsonRpcErrorCode=" + jsonRpcError.code()
				+ ", headerCount=" + headers.size() + "]";
	}
}

enum McpRateLimitTarget {
	REQUEST,
	TOOL
}

record McpRateLimitContext(Request request, McpNormalizedEndpoint endpoint,
		McpEffectiveAdmissionIdentity admissionIdentity,
		McpRateLimitTarget target, String jsonRpcMethod,
		Optional<String> operationName) {
	McpRateLimitContext {
		requireNonNull(request);
		requireNonNull(endpoint);
		requireNonNull(admissionIdentity);
		requireNonNull(target);
		jsonRpcMethod = requireNonNull(jsonRpcMethod);
		requireNonNull(operationName);
	}

	@Override
	public String toString() {
		return "McpRateLimitContext[target=" + target
				+ ", operationNamePresent=" + operationName.isPresent()
				+ ", authenticated=" + admissionIdentity.admittedIdentity().authenticated()
				+ "]";
	}
}

sealed interface McpRateLimitDecision
		permits McpRateLimitDecision.Allowed, McpRateLimitDecision.Denied {
	static Allowed allowed() {
		return new Allowed();
	}

	static Denied denied(Duration retryAfter) {
		return new Denied(retryAfter);
	}

	record Allowed() implements McpRateLimitDecision {
	}

	record Denied(Duration retryAfter) implements McpRateLimitDecision {
		public Denied {
			requireNonNull(retryAfter);
			if (retryAfter.isNegative())
				throw new IllegalArgumentException("retryAfter must not be negative");
		}
	}
}

@FunctionalInterface
interface McpRateLimiter {
	McpRateLimitDecision acquire(McpRateLimitContext context) throws Exception;
}

@FunctionalInterface
interface McpApplicationRequestInterceptor {
	McpWireResult intercept(McpApplicationInvocation invocation,
			McpApplicationHandlerInvocation handlerInvocation) throws Exception;

	static McpApplicationRequestInterceptor passThroughInstance() {
		return (invocation, handlerInvocation) -> handlerInvocation.invoke();
	}
}

@FunctionalInterface
interface McpApplicationHandlerInvocation {
	McpWireResult invoke() throws Exception;
}
