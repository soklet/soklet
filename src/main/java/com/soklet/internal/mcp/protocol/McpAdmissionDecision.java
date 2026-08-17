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
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
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
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
record McpAdmissionContext(@NonNull Request request,
		@NonNull McpNormalizedEndpoint endpoint,
		@NonNull Map<@NonNull String, @NonNull String> endpointPathParameters,
		@NonNull String jsonRpcMethod, boolean notification,
		@NonNull Optional<@NonNull McpJsonRpcId> requestId,
		@NonNull String protocolVersion,
		@NonNull Optional<@NonNull String> operationName,
		@NonNull Optional<@NonNull McpImplementationMetadata> clientInformation,
		@NonNull Optional<@NonNull McpClientCapabilities> clientCapabilities,
		@NonNull Optional<@NonNull McpJsonObject> requestMetadata) {
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
		requireNonNull(requestMetadata);
	}

	@NonNull
	private static String requireNonBlank(@NonNull String value,
			@NonNull String description) {
		requireNonNull(value);
		if (value.isBlank())
			throw new IllegalArgumentException(description + " must not be blank.");
		return value;
	}

	@Override
	@NonNull
	public String toString() {
		return "McpAdmissionContext[notification=" + notification
				+ ", requestIdPresent=" + requestId.isPresent()
				+ ", operationNamePresent=" + operationName.isPresent()
				+ ", clientInformationPresent=" + clientInformation.isPresent()
				+ "]";
	}
}

/**
 * Shallowly immutable admission identity carrier.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class McpAdmissionIdentity {
	static final int MAXIMUM_PARTITION_KEY_UTF_8_BYTES = 256;
	@NonNull
	private static final McpAdmissionIdentity ANONYMOUS = new McpAdmissionIdentity(
			Optional.of("anonymous"), Optional.empty(), Optional.empty(), Optional.empty());

	@NonNull
	private final Optional<@NonNull String> rateLimitPartitionKey;
	@NonNull
	private final Optional<@NonNull String> authorizationPartitionKey;
	@NonNull
	private final Optional<@NonNull Object> principal;
	@NonNull
	private final Optional<@NonNull Object> applicationContext;

	@NonNull
	static McpAdmissionIdentity anonymousInstance() {
		return ANONYMOUS;
	}

	@NonNull
	static Builder withRateLimitPartitionKey(@NonNull String rateLimitPartitionKey) {
		return new Builder(rateLimitPartitionKey);
	}

	private McpAdmissionIdentity(
			@NonNull Optional<@NonNull String> rateLimitPartitionKey,
			@NonNull Optional<@NonNull String> authorizationPartitionKey,
			@NonNull Optional<@NonNull Object> principal,
			@NonNull Optional<@NonNull Object> applicationContext) {
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

	@NonNull
	Optional<@NonNull Object> principal() {
		return principal;
	}

	@NonNull
	Optional<@NonNull Object> applicationContext() {
		return applicationContext;
	}

	@NonNull
	Optional<@NonNull String> rateLimitPartitionKey() {
		return rateLimitPartitionKey;
	}

	@NonNull
	Optional<@NonNull String> authorizationPartitionKey() {
		return authorizationPartitionKey;
	}

	@NonNull
	private static String requirePartitionKey(@NonNull String value,
			@NonNull String name) {
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

	@NotThreadSafe
	static final class Builder {
		@NonNull
		private final String rateLimitPartitionKey;
		private @Nullable String authorizationPartitionKey;
		private @Nullable Object principal;
		private @Nullable Object applicationContext;

		private Builder(@NonNull String rateLimitPartitionKey) {
			this.rateLimitPartitionKey = requireNonNull(rateLimitPartitionKey);
		}

		@NonNull
		Builder authorizationPartitionKey(@NonNull String authorizationPartitionKey) {
			this.authorizationPartitionKey = requireNonNull(authorizationPartitionKey);
			return this;
		}

		@NonNull
		Builder principal(@NonNull Object principal) {
			this.principal = requireNonNull(principal);
			return this;
		}

		@NonNull
		Builder applicationContext(@NonNull Object applicationContext) {
			this.applicationContext = requireNonNull(applicationContext);
			return this;
		}

		@NonNull
		McpAdmissionIdentity build() {
			return new McpAdmissionIdentity(
					Optional.of(rateLimitPartitionKey),
					Optional.ofNullable(authorizationPartitionKey),
					Optional.ofNullable(principal),
					Optional.ofNullable(applicationContext));
		}
	}
}

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
record McpEffectiveAdmissionIdentity(@NonNull McpAdmissionIdentity admittedIdentity,
		@NonNull McpEffectivePartition rateLimitPartition,
		@NonNull McpEffectivePartition authorizationPartition) {
	McpEffectiveAdmissionIdentity {
		requireNonNull(admittedIdentity);
		requireNonNull(rateLimitPartition);
		requireNonNull(authorizationPartition);
	}

	@NonNull
	static McpEffectiveAdmissionIdentity resolve(@NonNull McpNormalizedEndpoint endpoint,
			@NonNull String endpointPath,
			@NonNull McpAdmissionIdentity admittedIdentity) {
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
	@NonNull
	public String toString() {
		return "McpEffectiveAdmissionIdentity[authenticated="
				+ admittedIdentity.authenticated() + "]";
	}
}

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
record McpEndpointPartitionIdentity(@NonNull String endpointPath) {
	McpEndpointPartitionIdentity {
		requireNonNull(endpointPath);
	}

	@Override
	@NonNull
	public String toString() {
		return "McpEndpointPartitionIdentity[endpointPath=" + endpointPath + "]";
	}
}

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
enum McpPartitionPurpose {
	RATE_LIMIT,
	AUTHORIZATION
}

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
record McpEffectivePartition(@NonNull McpEndpointPartitionIdentity endpointIdentity,
		@NonNull McpPartitionPurpose purpose,
		@NonNull Optional<@NonNull String> applicationKey) {
	McpEffectivePartition {
		requireNonNull(endpointIdentity);
		requireNonNull(purpose);
		requireNonNull(applicationKey);
	}

	@Override
	@NonNull
	public String toString() {
		return "McpEffectivePartition[purpose=" + purpose
				+ ", applicationKeyPresent=" + applicationKey.isPresent() + "]";
	}
}

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
sealed interface McpAdmissionDecision
		permits McpAdmissionDecision.Accepted, McpAdmissionDecision.Rejected {
	@NonNull
	static Accepted accepted(@NonNull McpAdmissionIdentity identity) {
		return new Accepted(identity);
	}

	@NonNull
	static Accepted acceptedAnonymous() {
		return accepted(McpAdmissionIdentity.anonymousInstance());
	}

	@NonNull
	static Rejected rejected(@NonNull McpAdmissionRejection rejection) {
		return new Rejected(rejection);
	}

	record Accepted(@NonNull McpAdmissionIdentity identity) implements McpAdmissionDecision {
		public Accepted {
			requireNonNull(identity);
		}
	}

	record Rejected(@NonNull McpAdmissionRejection rejection) implements McpAdmissionDecision {
		public Rejected {
			requireNonNull(rejection);
		}
	}
}

/**
 * Temporary source-compatibility constants for Phase 3 tests. The public API
 * uses {@link McpAdmissionDecision}; this class is not a public contract.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class McpRequestAdmissionDecision {
	@NonNull
	static final McpAdmissionDecision ACCEPT = McpAdmissionDecision.acceptedAnonymous();
	@NonNull
	static final McpAdmissionDecision REJECT = McpAdmissionDecision.rejected(
			new McpAdmissionRejection(403,
					new McpJsonRpcError(1_000,
							"Request rejected", Optional.empty()), Map.of()));

	private McpRequestAdmissionDecision() {
	}
}

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
@FunctionalInterface
interface McpProtocolAdmissionController {
	@NonNull
	McpAdmissionDecision admit(@NonNull McpAdmissionContext context) throws Exception;

	@NonNull
	static McpProtocolAdmissionController acceptAllInstance() {
		return ignored -> McpAdmissionDecision.acceptedAnonymous();
	}
}

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
record McpAdmissionRejection(int statusCode, @NonNull McpJsonRpcError jsonRpcError,
		@NonNull Map<@NonNull String, @NonNull List<@NonNull String>> headers) {
	McpAdmissionRejection {
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
	@NonNull
	public String toString() {
		return "McpAdmissionRejection[statusCode=" + statusCode
				+ ", jsonRpcErrorCode=" + jsonRpcError.code()
				+ ", headerCount=" + headers.size() + "]";
	}
}

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
enum McpRateLimitTarget {
	REQUEST,
	TOOL
}

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
record McpRateLimitContext(@NonNull Request request,
		@NonNull McpNormalizedEndpoint endpoint,
		@NonNull McpEffectiveAdmissionIdentity admissionIdentity,
		@NonNull McpRateLimitTarget target, @NonNull String jsonRpcMethod,
		@NonNull Optional<@NonNull String> operationName) {
	McpRateLimitContext {
		requireNonNull(request);
		requireNonNull(endpoint);
		requireNonNull(admissionIdentity);
		requireNonNull(target);
		requireNonNull(jsonRpcMethod);
		requireNonNull(operationName);
	}

	@Override
	@NonNull
	public String toString() {
		return "McpRateLimitContext[target=" + target
				+ ", operationNamePresent=" + operationName.isPresent()
				+ ", authenticated=" + admissionIdentity.admittedIdentity().authenticated()
				+ "]";
	}
}

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
sealed interface McpRateLimitDecision
		permits McpRateLimitDecision.Allowed, McpRateLimitDecision.Denied {
	@NonNull
	static Allowed allowed() {
		return new Allowed();
	}

	@NonNull
	static Denied denied(@NonNull Duration retryAfter) {
		return new Denied(retryAfter);
	}

	record Allowed() implements McpRateLimitDecision {
	}

	record Denied(@NonNull Duration retryAfter) implements McpRateLimitDecision {
		public Denied {
			requireNonNull(retryAfter);
			if (retryAfter.isNegative())
				throw new IllegalArgumentException("retryAfter must not be negative");
		}
	}
}

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
@FunctionalInterface
interface McpRateLimiter {
	@NonNull
	McpRateLimitDecision acquire(@NonNull McpRateLimitContext context) throws Exception;
}

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
@FunctionalInterface
interface McpApplicationRequestInterceptor {
	@NonNull
	McpWireResult intercept(@NonNull McpApplicationInvocation invocation,
			@NonNull McpApplicationHandlerInvocation handlerInvocation) throws Exception;

	@NonNull
	static McpApplicationRequestInterceptor passThroughInstance() {
		return (invocation, handlerInvocation) -> handlerInvocation.invoke();
	}
}

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@NotThreadSafe
@FunctionalInterface
interface McpApplicationHandlerInvocation {
	@NonNull
	McpWireResult invoke() throws Exception;
}
