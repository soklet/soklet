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

import com.soklet.CorsAuthorizer;
import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.ThreadSafe;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Package-private Phase 3 transport configuration. None of these values is a
 * public MCP API contract until the owning public-API phase freezes it.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
record McpHttpTransportConfiguration(@NonNull String host, int port,
		@NonNull Duration selectorResolution,
		@NonNull Duration requestHeaderTimeout, @NonNull Duration requestBodyTimeout,
		@NonNull Duration responseWriteIdleTimeout, @NonNull Duration keepAliveInterval,
		@NonNull Duration shutdownTimeout,
		int readBufferSize, int acceptBacklog, int maximumAggregateRequestBytes,
		int maximumRequestBodyBytes, int maximumHeaderCount, int maximumHeaderBytes,
		int maximumRequestTargetBytes, int maximumConnections,
		int connectionWriterConcurrency, int requestProcessorConcurrency,
		int requestProcessorQueueCapacity, int streamQueueCapacity) {
	private static final int MEBIBYTE = 1_024 * 1_024;
	private static final int DEFAULT_MAXIMUM_REQUEST_BODY_BYTES = 4 * MEBIBYTE;
	private static final int DEFAULT_MAXIMUM_HEADER_BYTES = 64 * 1_024;
	private static final int DEFAULT_MAXIMUM_REQUEST_TARGET_BYTES = 8 * 1_024;
	private static final int HTTP_FRAMING_ALLOWANCE_BYTES = 1 * 1_024;
	private static final int DEFAULT_MAXIMUM_AGGREGATE_REQUEST_BYTES =
			DEFAULT_MAXIMUM_REQUEST_BODY_BYTES + DEFAULT_MAXIMUM_HEADER_BYTES
					+ DEFAULT_MAXIMUM_REQUEST_TARGET_BYTES + HTTP_FRAMING_ALLOWANCE_BYTES;

	McpHttpTransportConfiguration {
		host = requireNonBlank(host, "Bind host");

		if (port < 0 || port > 65_535)
			throw new IllegalArgumentException("Port must be between 0 and 65535.");

		positive(selectorResolution, "Selector resolution");
		positive(requestHeaderTimeout, "Request-header timeout");
		positive(requestBodyTimeout, "Request-body timeout");
		positive(responseWriteIdleTimeout, "Response write-idle timeout");
		positive(keepAliveInterval, "Keep-alive interval");
		positive(shutdownTimeout, "Shutdown timeout");
		if (keepAliveInterval.compareTo(responseWriteIdleTimeout) >= 0)
			throw new IllegalArgumentException(
					"Keep-alive interval must be shorter than the response write-idle timeout.");
		positive(readBufferSize, "Read-buffer size");
		positive(acceptBacklog, "Accept backlog");
		positive(maximumAggregateRequestBytes, "Maximum aggregate request bytes");
		positive(maximumRequestBodyBytes, "Maximum request-body bytes");
		positive(maximumHeaderCount, "Maximum header count");
		positive(maximumHeaderBytes, "Maximum header bytes");
		positive(maximumRequestTargetBytes, "Maximum request-target bytes");
		positive(maximumConnections, "Maximum connections");
		positive(connectionWriterConcurrency, "Connection-writer concurrency");
		positive(requestProcessorConcurrency, "Request-processor concurrency");
		positive(requestProcessorQueueCapacity, "Request-processor queue capacity");
		positive(streamQueueCapacity, "Stream queue capacity");

		long requiredAggregateBytes = (long) maximumRequestBodyBytes
				+ maximumHeaderBytes + maximumRequestTargetBytes
				+ HTTP_FRAMING_ALLOWANCE_BYTES;

		if (maximumAggregateRequestBytes < requiredAggregateBytes)
			throw new IllegalArgumentException("Maximum aggregate request bytes must accommodate "
					+ "the configured body, headers, request target, and framing allowance.");
	}

	@NonNull
	static McpHttpTransportConfiguration productionDefaults(int port) {
		return new McpHttpTransportConfiguration(
				"127.0.0.1",
				port,
				Duration.ofMillis(100),
				Duration.ofSeconds(60),
				Duration.ofSeconds(60),
				Duration.ofSeconds(60),
				Duration.ofSeconds(15),
				Duration.ofSeconds(5),
				64 * 1_024,
				8_192,
				DEFAULT_MAXIMUM_AGGREGATE_REQUEST_BYTES,
				DEFAULT_MAXIMUM_REQUEST_BODY_BYTES,
				100,
				DEFAULT_MAXIMUM_HEADER_BYTES,
				DEFAULT_MAXIMUM_REQUEST_TARGET_BYTES,
				8_192,
				1,
				32,
				128,
				64);
	}

	@NonNull
	private static String requireNonBlank(@NonNull String value,
			@NonNull String description) {
		requireNonNull(value);

		if (value.isBlank())
			throw new IllegalArgumentException(description + " must not be blank.");

		return value;
	}

	private static void positive(int value, @NonNull String description) {
		if (value < 1)
			throw new IllegalArgumentException(description + " must be positive.");
	}

	private static void positive(@NonNull Duration value,
			@NonNull String description) {
		requireNonNull(value);

		if (value.isZero() || value.isNegative())
			throw new IllegalArgumentException(description + " must be positive.");

		try {
			if (value.toNanos() < 1L)
				throw new IllegalArgumentException(description + " must be positive.");
		} catch (ArithmeticException exception) {
			throw new IllegalArgumentException(
					description + " must fit in a signed nanosecond duration.", exception);
		}
	}
}

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
record McpHttpEndpointPolicy(@NonNull String path,
		@NonNull Set<@NonNull String> allowedHosts,
		@NonNull McpAbsentOriginPolicy absentOriginPolicy,
		@NonNull CorsAuthorizer corsAuthorizer,
		@NonNull McpRequestAdmissionPolicy requestAdmissionPolicy,
		@NonNull Optional<@NonNull McpRateLimiter> requestRateLimiter,
		@NonNull McpApplicationRequestInterceptor requestInterceptor,
		@NonNull McpUnknownMirroredHeaderPolicy unknownMirroredHeaderPolicy,
		boolean corsAuthorizerExplicitlyConfigured) {
	McpHttpEndpointPolicy(@NonNull String path,
			@NonNull Set<@NonNull String> allowedHosts,
			@NonNull McpAbsentOriginPolicy absentOriginPolicy,
			@NonNull CorsAuthorizer corsAuthorizer,
			@NonNull McpRequestAdmissionPolicy requestAdmissionPolicy,
			@NonNull Optional<@NonNull McpRateLimiter> requestRateLimiter,
			@NonNull McpApplicationRequestInterceptor requestInterceptor,
			@NonNull McpUnknownMirroredHeaderPolicy unknownMirroredHeaderPolicy) {
		this(path, allowedHosts, absentOriginPolicy, corsAuthorizer,
				requestAdmissionPolicy, requestRateLimiter, requestInterceptor,
				unknownMirroredHeaderPolicy, true);
	}

	McpHttpEndpointPolicy(@NonNull String path,
			@NonNull Set<@NonNull String> allowedHosts,
			@NonNull McpAbsentOriginPolicy absentOriginPolicy,
			@NonNull CorsAuthorizer corsAuthorizer,
			@NonNull McpRequestAdmissionPolicy requestAdmissionPolicy,
			@NonNull Optional<@NonNull McpRateLimiter> requestRateLimiter,
			@NonNull McpApplicationRequestInterceptor requestInterceptor) {
		this(path, allowedHosts, absentOriginPolicy, corsAuthorizer,
				requestAdmissionPolicy, requestRateLimiter, requestInterceptor,
				McpUnknownMirroredHeaderPolicy.IGNORE);
	}

	McpHttpEndpointPolicy(@NonNull String path,
			@NonNull Set<@NonNull String> allowedHosts,
			@NonNull McpAbsentOriginPolicy absentOriginPolicy,
			@NonNull CorsAuthorizer corsAuthorizer,
			@NonNull McpRequestAdmissionPolicy requestAdmissionPolicy) {
		this(path, allowedHosts, absentOriginPolicy, corsAuthorizer,
				requestAdmissionPolicy, Optional.empty(),
				McpApplicationRequestInterceptor.passThroughInstance());
	}

	McpHttpEndpointPolicy {
		requireNonNull(path);

		if (!path.startsWith("/") || path.length() == 1 || path.contains("?")
				|| path.contains("#"))
			throw new IllegalArgumentException(
					"MCP endpoint path must be an absolute path without a query or fragment.");

		requireNonNull(allowedHosts);
		LinkedHashSet<String> copiedHosts = new LinkedHashSet<>();

		for (String allowedHost : allowedHosts)
			copiedHosts.add(requireNonNull(allowedHost));

		allowedHosts = Set.copyOf(copiedHosts);
		requireNonNull(absentOriginPolicy);
		requireNonNull(corsAuthorizer);
		requireNonNull(requestAdmissionPolicy);
		requireNonNull(requestRateLimiter);
		requireNonNull(requestInterceptor);
		requireNonNull(unknownMirroredHeaderPolicy);

		if (!corsAuthorizerExplicitlyConfigured
				&& corsAuthorizer != CorsAuthorizer.rejectAllInstance())
			throw new IllegalArgumentException(
					"An omitted CORS authorizer must use the reject-all default.");
	}

	@NonNull
	static McpHttpEndpointPolicy forDiscovery(@NonNull CorsAuthorizer corsAuthorizer,
			@NonNull McpRequestAdmissionPolicy requestAdmissionPolicy) {
		return new McpHttpEndpointPolicy("/mcp", Set.of(),
				McpAbsentOriginPolicy.ALLOW, corsAuthorizer, requestAdmissionPolicy);
	}

	@NonNull
	static McpHttpEndpointPolicy forDiscoveryWithDefaultCorsAuthorizer(
			@NonNull McpRequestAdmissionPolicy requestAdmissionPolicy) {
		return new McpHttpEndpointPolicy("/mcp", Set.of(),
				McpAbsentOriginPolicy.ALLOW, CorsAuthorizer.rejectAllInstance(),
				requireNonNull(requestAdmissionPolicy), Optional.empty(),
				McpApplicationRequestInterceptor.passThroughInstance(),
				McpUnknownMirroredHeaderPolicy.IGNORE, false);
	}

	@NonNull
	McpHttpEndpointPolicy withRequestRateLimiter(@NonNull McpRateLimiter requestRateLimiter) {
		return new McpHttpEndpointPolicy(path, allowedHosts, absentOriginPolicy,
				corsAuthorizer, requestAdmissionPolicy,
				Optional.of(requireNonNull(requestRateLimiter)), requestInterceptor,
				unknownMirroredHeaderPolicy, corsAuthorizerExplicitlyConfigured);
	}

	@NonNull
	McpHttpEndpointPolicy withRequestInterceptor(
			@NonNull McpApplicationRequestInterceptor requestInterceptor) {
		return new McpHttpEndpointPolicy(path, allowedHosts, absentOriginPolicy,
				corsAuthorizer, requestAdmissionPolicy, requestRateLimiter,
				requireNonNull(requestInterceptor), unknownMirroredHeaderPolicy,
				corsAuthorizerExplicitlyConfigured);
	}

	@NonNull
	McpHttpEndpointPolicy withUnknownMirroredHeaderPolicy(
			@NonNull McpUnknownMirroredHeaderPolicy unknownMirroredHeaderPolicy) {
		return new McpHttpEndpointPolicy(path, allowedHosts, absentOriginPolicy,
				corsAuthorizer, requestAdmissionPolicy, requestRateLimiter,
				requestInterceptor, requireNonNull(unknownMirroredHeaderPolicy),
				corsAuthorizerExplicitlyConfigured);
	}

	@Override
	@NonNull
	public String toString() {
		return "McpHttpEndpointPolicy[path=" + path
				+ ", allowedHostCount=" + allowedHosts.size()
				+ ", absentOriginPolicy=" + absentOriginPolicy
				+ ", requestRateLimiterPresent=" + requestRateLimiter.isPresent()
				+ ", unknownMirroredHeaderPolicy=" + unknownMirroredHeaderPolicy
				+ "]";
	}
}

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
enum McpAbsentOriginPolicy {
	ALLOW,
	REQUIRE_ORIGIN
}

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
enum McpUnknownMirroredHeaderPolicy {
	IGNORE,
	REJECT_REQUESTS
}
