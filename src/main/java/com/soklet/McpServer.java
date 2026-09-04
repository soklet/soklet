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

import com.soklet.internal.mcp.protocol.McpCursorLimit;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

/**
 * A dedicated modern MCP server managed by a core {@link Soklet} instance.
 * <p>
 * MCP always binds its own listener. It is never mounted inside Soklet's
 * ordinary {@link HttpServer} or {@link SseServer}.
 * Server diagnostics are safe to invoke concurrently. Lifecycle is owned by
 * the {@link Soklet} configured with this server.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public sealed interface McpServer permits DefaultMcpServer {
	/**
	 * Returns the immutable endpoint registry.
	 *
	 * @return endpoint registry
	 */
	@NonNull
	McpEndpointRegistry getEndpointRegistry();

	/**
	 * Returns the required admission controller.
	 *
	 * @return admission controller
	 */
	@NonNull
	McpAdmissionController getAdmissionController();

	/**
	 * Returns the server-level application-handler interceptor. When omitted
	 * during construction this is {@link McpHandlerInterceptor#passThroughInstance()}.
	 *
	 * @return handler interceptor
	 */
	@NonNull
	McpHandlerInterceptor getHandlerInterceptor();

	/**
	 * Returns the server-level tool-output sanitizer. When omitted during
	 * construction this is
	 * {@link McpToolOutputSanitizer#passThroughInstance()}.
	 *
	 * @return tool-output sanitizer
	 */
	@NonNull
	McpToolOutputSanitizer getToolOutputSanitizer();

	/**
	 * Returns the optional limiter applied once to every admitted request or
	 * notification.
	 *
	 * @return request limiter, or the empty optional when request-wide limiting
	 * is disabled
	 */
	@NonNull
	Optional<@NonNull McpRateLimiter> getRequestRateLimiter();

	/**
	 * Returns the server-level fallback tool limiter.
	 * <p>
	 * This value is required when the server exposes any tool. Endpoint and tool
	 * overrides replace it according to the documented resolution order.
	 *
	 * @return fallback limiter, or the empty optional for a tool-free server
	 */
	@NonNull
	Optional<@NonNull McpRateLimiter> getToolRateLimiter();

	/**
	 * Returns the immutable registry used to resolve named limiter overrides.
	 *
	 * @return rate-limiter registry
	 */
	@NonNull
	McpRateLimiterRegistry getRateLimiterRegistry();

	/**
	 * Returns the Origin authorizer. When omitted during construction this is
	 * {@link CorsAuthorizer#rejectAllInstance()}.
	 * Soklet may invoke the authorizer concurrently for independent requests;
	 * custom implementations must therefore be thread-safe.
	 *
	 * @return CORS authorizer
	 */
	@NonNull
	CorsAuthorizer getCorsAuthorizer();

	/**
	 * Returns the maximum UTF-8 encoded size of an incoming or outgoing
	 * application cursor. The value is in the range {@code 1..174762}; the
	 * upper bound keeps every individually in-bound cursor representable after
	 * worst-case JSON escaping.
	 *
	 * @return cursor-size limit in bytes
	 */
	@NonNull Integer getMaximumCursorSizeInBytes();

	/**
	 * Returns this server's request-state protection control plane.
	 * <p>
	 * The control never exposes configured key material. Mutation methods reject
	 * calls unless this server was built with a production keyring.
	 *
	 * @return server-owned protection control
	 */
	@NonNull
	McpProtectionControl getProtectionControl();

	/**
	 * Returns this server's trace-correlation control plane.
	 * <p>
	 * The control reports disabled state when no trace-correlation key was
	 * supplied during construction.
	 *
	 * @return server-owned trace-correlation control
	 */
	@NonNull
	McpTraceCorrelationControl getTraceCorrelationControl();

	/**
	 * Returns this server's localization control plane.
	 * <p>
	 * The handle reports disabled state when no localizer was supplied.
	 * Catalog-change signaling is local to this server instance; distributed
	 * applications invoke it on every applicable instance after atomically
	 * installing a new immutable translation snapshot.
	 *
	 * @return this server's localization control plane
	 */
	@NonNull
	McpLocalizationControl getLocalizationControl();

	/**
	 * Captures immutable point-in-time server diagnostics.
	 *
	 * @return diagnostics snapshot
	 */
	@NonNull
	McpServerDiagnostics getDiagnostics();

	/**
	 * Vends a server builder primed with its required construction values.
	 * Port {@code 0} requests an operating-system-assigned port.
	 *
	 * @param port port in the range 0 through 65535
	 * @param endpointRegistry registry containing at least one endpoint
	 * @param admissionController authentication, authorization, and admission
	 *                            controller
	 * @return server builder
	 * @throws NullPointerException if an argument is null
	 */
	@NonNull
	static Builder withPort(@NonNull Integer port,
			@NonNull McpEndpointRegistry endpointRegistry,
			@NonNull McpAdmissionController admissionController) {
		return new Builder(requirePort(requireNonNull(port)), endpointRegistry,
				admissionController);
	}

	/**
	 * Single-threaded builder for Soklet's built-in MCP server.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	final class Builder {
		@NonNull
		private static final String DEFAULT_HOST = "127.0.0.1";
		private static final int DEFAULT_MAXIMUM_SUBSCRIPTIONS_PER_PARTITION = 32;
		private static final int DEFAULT_REQUEST_HANDLER_CONCURRENCY = 32;
		private static final int DEFAULT_REQUEST_HANDLER_QUEUE_CAPACITY = 128;
		private static final int DEFAULT_STREAM_QUEUE_CAPACITY = 128;
		@NonNull
		private static final Duration DEFAULT_KEEP_ALIVE_INTERVAL =
				Duration.ofSeconds(15);
		@NonNull
		private static final Duration DEFAULT_MAXIMUM_SUBSCRIPTION_DURATION =
				Duration.ofHours(24);
		@NonNull
		private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(60);
		private static final Duration DEFAULT_WRITE_TIMEOUT =
				Duration.ofSeconds(30);
		private int port;
		private int maximumCursorSizeInBytes;
		private int maximumSubscriptionsPerPartition;
		private int requestHandlerConcurrency;
		private int requestHandlerQueueCapacity;
		private int streamQueueCapacity;
		@NonNull
		private String host;
		@NonNull
		private Duration keepAliveInterval;
		@NonNull
		private Duration maximumSubscriptionDuration;
		@NonNull
		private Duration requestTimeout;
		@NonNull
		private Duration writeTimeout;
		@Nullable
		private Supplier<@NonNull ExecutorService> requestHandlerExecutorServiceSupplier;
		@NonNull
		private McpEndpointRegistry endpointRegistry;
		@NonNull
		private McpAdmissionController admissionController;
		@NonNull
		private McpHandlerInterceptor handlerInterceptor;
		@NonNull
		private McpToolOutputSanitizer toolOutputSanitizer;
		@Nullable
		private CorsAuthorizer corsAuthorizer;
		@Nullable
		private McpRateLimiter requestRateLimiter;
		@Nullable
		private McpRateLimiter toolRateLimiter;
		@NonNull
		private McpRateLimiterRegistry rateLimiterRegistry;
		@NonNull
		private McpAbsentOriginPolicy absentOriginPolicy;
		@NonNull
		private McpUnknownMirroredHeaderPolicy unknownMirroredHeaderPolicy;
		private boolean logRawValidatedTraceIds;
		private boolean unknownMirroredHeaderNameDiagnostics;
		@Nullable
		private McpProtectionConfig protectionConfig;
		@Nullable
		private McpTraceCorrelationKey traceCorrelationKey;
		@Nullable
		private McpLocalizer localizer;
		@NonNull
		private Set<@NonNull String> allowedHosts;
		@Nullable
		private SimulatorMcpBuildRegistrar simulatorBuildRegistrar;

		private Builder(int port,
				@NonNull McpEndpointRegistry endpointRegistry,
				@NonNull McpAdmissionController admissionController) {
			this.port = port;
			this.endpointRegistry = requireNonNull(endpointRegistry);
			this.admissionController = requireNonNull(admissionController);
			this.maximumCursorSizeInBytes =
					McpCursorLimit.DEFAULT_MAXIMUM_SIZE_IN_BYTES;
			this.maximumSubscriptionsPerPartition =
					DEFAULT_MAXIMUM_SUBSCRIPTIONS_PER_PARTITION;
			this.requestHandlerConcurrency = DEFAULT_REQUEST_HANDLER_CONCURRENCY;
			this.requestHandlerQueueCapacity =
					DEFAULT_REQUEST_HANDLER_QUEUE_CAPACITY;
			this.streamQueueCapacity = DEFAULT_STREAM_QUEUE_CAPACITY;
			this.host = DEFAULT_HOST;
			this.keepAliveInterval = DEFAULT_KEEP_ALIVE_INTERVAL;
			this.maximumSubscriptionDuration =
					DEFAULT_MAXIMUM_SUBSCRIPTION_DURATION;
			this.requestTimeout = DEFAULT_REQUEST_TIMEOUT;
			this.writeTimeout = DEFAULT_WRITE_TIMEOUT;
			this.absentOriginPolicy = McpAbsentOriginPolicy.ALLOW;
			this.unknownMirroredHeaderPolicy =
					McpUnknownMirroredHeaderPolicy.IGNORE;
			this.logRawValidatedTraceIds = false;
			this.unknownMirroredHeaderNameDiagnostics = false;
			this.allowedHosts = Set.of();
			this.rateLimiterRegistry = McpRateLimiterRegistry.emptyInstance();
			this.handlerInterceptor = McpHandlerInterceptor.passThroughInstance();
			this.toolOutputSanitizer =
					McpToolOutputSanitizer.passThroughInstance();
		}

		@NonNull
		Builder simulatorBuildRegistrar(
				@NonNull SimulatorMcpBuildRegistrar simulatorBuildRegistrar) {
			if (this.simulatorBuildRegistrar != null)
				throw new IllegalStateException(
						"The MCP builder is already assigned to a simulator scope");
			this.simulatorBuildRegistrar = requireNonNull(
					simulatorBuildRegistrar);
			return this;
		}

		/**
		 * Sets the dedicated TCP port. Port {@code 0} requests an
		 * operating-system-assigned port.
		 *
		 * @param port port in the range 0 through 65535
		 * @return this builder
		 * @throws NullPointerException if {@code port} is null
		 * @throws IllegalArgumentException if the port is outside the valid range
		 */
		@NonNull
		public Builder port(@NonNull Integer port) {
			this.port = requirePort(requireNonNull(port));
			return this;
		}

		/**
		 * Sets the dedicated bind host. The default is {@code 127.0.0.1}.
		 *
		 * @param host nonblank bind host, or null to restore the default
		 * @return this builder
		 */
		@NonNull
		public Builder host(@Nullable String host) {
			if (host == null) {
				this.host = DEFAULT_HOST;
				return this;
			}
			if (host.isBlank())
				throw new IllegalArgumentException("MCP bind host must not be blank.");
			this.host = host;
			return this;
		}

		/**
		 * Sets the maximum UTF-8 encoded size of an incoming or outgoing
		 * application cursor. The default is {@code 4096} bytes and the maximum
		 * supported value is {@code 174762} bytes. The hard ceiling accounts for
		 * worst-case JSON escaping so an individually in-bound cursor remains
		 * representable by Soklet's strict request and response JSON profiles.
		 *
		 * @param maximumCursorSizeInBytes positive cursor-size limit of at most
		 *                                 {@code 174762} bytes, or null to restore
		 *                                 the default
		 * @return this builder
		 * @throws IllegalArgumentException if the limit is outside the supported
		 *                                  range
		 */
		@NonNull
		public Builder maximumCursorSizeInBytes(
				@Nullable Integer maximumCursorSizeInBytes) {
			this.maximumCursorSizeInBytes = maximumCursorSizeInBytes == null
					? McpCursorLimit.DEFAULT_MAXIMUM_SIZE_IN_BYTES
					: McpCursorLimit.requireSupportedMaximumSizeInBytes(
							maximumCursorSizeInBytes);
			return this;
		}

		/**
		 * Sets the positive, finite number of concurrently live subscriptions for
		 * one admitted authorization/quota partition. The default is {@code 32}.
		 * All callers accepted without an explicit partition key at one endpoint
		 * share that endpoint's empty anonymous partition, so one anonymous client
		 * can exhaust the bucket for every other anonymous caller. This setting has
		 * neutral behavior until subscriptions are active.
		 *
		 * @param maximumSubscriptionsPerPartition subscription limit per endpoint
		 *                                          and authorization/quota partition,
		 *                                          or null to restore the default
		 * @return this builder
		 * @throws IllegalArgumentException if the value is not positive
		 */
		@NonNull
		public Builder maximumSubscriptionsPerPartition(
				@Nullable Integer maximumSubscriptionsPerPartition) {
			if (maximumSubscriptionsPerPartition == null) {
				this.maximumSubscriptionsPerPartition =
						DEFAULT_MAXIMUM_SUBSCRIPTIONS_PER_PARTITION;
				return this;
			}
			if (maximumSubscriptionsPerPartition < 1)
				throw new IllegalArgumentException(
						"MCP maximum subscriptions per partition must be positive.");
			this.maximumSubscriptionsPerPartition =
					maximumSubscriptionsPerPartition;
			return this;
		}

		/**
		 * Sets the positive finite lifetime of one subscription. The default is
		 * 24 hours. This setting has neutral behavior until subscriptions are
		 * active.
		 *
		 * @param maximumSubscriptionDuration maximum subscription lifetime, or
		 *                                    null to restore the default
		 * @return this builder
		 * @throws IllegalArgumentException if the duration is not positive and
		 *                                  representable as signed nanoseconds
		 */
		@NonNull
		public Builder maximumSubscriptionDuration(
				@Nullable Duration maximumSubscriptionDuration) {
			this.maximumSubscriptionDuration = maximumSubscriptionDuration == null
					? DEFAULT_MAXIMUM_SUBSCRIPTION_DURATION
					: requirePositiveDuration(maximumSubscriptionDuration,
							"MCP maximum subscription duration");
			return this;
		}

		/**
		 * Sets the absolute client-visible request deadline. The deadline does not
		 * forcibly terminate application code that ignores interruption.
		 * The default is 60 seconds.
		 *
		 * @param requestTimeout positive finite request timeout, or null to restore
		 *                       the default
		 * @return this builder
		 * @throws IllegalArgumentException if the timeout is zero, negative, below
		 *                                  one nanosecond, or too large to represent
		 *                                  as signed nanoseconds
		 */
		@NonNull
		public Builder requestTimeout(@Nullable Duration requestTimeout) {
			this.requestTimeout = requestTimeout == null ? DEFAULT_REQUEST_TIMEOUT
					: requirePositiveDuration(requestTimeout, "MCP request timeout");
			return this;
		}

		/**
		 * Sets the positive, finite number of application handler dispatches that
		 * may hold server-wide execution slots. The default is {@code 32}.
		 *
		 * @param requestHandlerConcurrency maximum active handler dispatches, or
		 *                                  null to restore the default
		 * @return this builder
		 * @throws IllegalArgumentException if the value is not positive
		 */
		@NonNull
		public Builder requestHandlerConcurrency(
				@Nullable Integer requestHandlerConcurrency) {
			if (requestHandlerConcurrency == null) {
				this.requestHandlerConcurrency = DEFAULT_REQUEST_HANDLER_CONCURRENCY;
				return this;
			}
			if (requestHandlerConcurrency < 1)
				throw new IllegalArgumentException(
						"MCP request-handler concurrency must be positive.");
			this.requestHandlerConcurrency = requestHandlerConcurrency;
			return this;
		}

		/**
		 * Sets the positive, finite number of admitted requests that may wait for
		 * an application handler slot. The default is {@code 128}.
		 *
		 * @param requestHandlerQueueCapacity maximum queued handler dispatches, or
		 *                                    null to restore the default
		 * @return this builder
		 * @throws IllegalArgumentException if the value is not positive
		 */
		@NonNull
		public Builder requestHandlerQueueCapacity(
				@Nullable Integer requestHandlerQueueCapacity) {
			if (requestHandlerQueueCapacity == null) {
				this.requestHandlerQueueCapacity =
						DEFAULT_REQUEST_HANDLER_QUEUE_CAPACITY;
				return this;
			}
			if (requestHandlerQueueCapacity < 1)
				throw new IllegalArgumentException(
						"MCP request-handler queue capacity must be positive.");
			this.requestHandlerQueueCapacity = requestHandlerQueueCapacity;
			return this;
		}

		/**
		 * Sets a supplier for the application handler executor used by each
		 * listener generation. The supplier is invoked during {@link Soklet#start()}
		 * and must return a fresh, running executor each time. Soklet owns and shuts
		 * down every returned executor. Its own handler-slot and queue bounds remain
		 * authoritative regardless of executor capacity.
		 *
		 * @param requestHandlerExecutorServiceSupplier executor supplier, or null
		 *                                              to use Soklet's default
		 * @return this builder
		 */
		@NonNull
		public Builder requestHandlerExecutorServiceSupplier(
				@Nullable Supplier<@NonNull ExecutorService>
						requestHandlerExecutorServiceSupplier) {
			this.requestHandlerExecutorServiceSupplier =
					requestHandlerExecutorServiceSupplier;
			return this;
		}

		/**
		 * Sets the positive, finite number of pending messages retained for one
		 * MCP response or subscription stream. The default is {@code 128}.
		 * This setting has neutral behavior until its streaming owner is active.
		 *
		 * @param streamQueueCapacity maximum pending messages per stream, or null
		 *                            to restore the default
		 * @return this builder
		 * @throws IllegalArgumentException if the value is not positive
		 */
		@NonNull
		public Builder streamQueueCapacity(@Nullable Integer streamQueueCapacity) {
			if (streamQueueCapacity == null) {
				this.streamQueueCapacity = DEFAULT_STREAM_QUEUE_CAPACITY;
				return this;
			}
			if (streamQueueCapacity < 1)
				throw new IllegalArgumentException(
						"MCP stream queue capacity must be positive.");
			this.streamQueueCapacity = streamQueueCapacity;
			return this;
		}

		/**
		 * Sets the positive finite interval for which a live response stream may
		 * write no bytes before Soklet closes it. The default is 30 seconds. The
		 * configured keep-alive interval must be shorter than this interval.
		 *
		 * @param writeTimeout maximum interval without a stream write, or null to
		 *                     restore the default
		 * @return this builder
		 * @throws IllegalArgumentException if the duration is not positive and
		 *                                  representable as signed nanoseconds
		 */
		@NonNull
		public Builder writeTimeout(@Nullable Duration writeTimeout) {
			this.writeTimeout = writeTimeout == null ? DEFAULT_WRITE_TIMEOUT
					: requirePositiveDuration(writeTimeout, "MCP write timeout");
			return this;
		}

		/**
		 * Sets the positive finite interval between idle SSE keep-alive comments.
		 * The default is 15 seconds. This setting has neutral behavior until its
		 * streaming owner is active.
		 *
		 * @param keepAliveInterval SSE keep-alive interval, or null to restore the
		 *                          default
		 * @return this builder
		 * @throws IllegalArgumentException if the duration is not positive and
		 *                                  representable as signed nanoseconds
		 */
		@NonNull
		public Builder keepAliveInterval(@Nullable Duration keepAliveInterval) {
			this.keepAliveInterval = keepAliveInterval == null
					? DEFAULT_KEEP_ALIVE_INTERVAL
					: requirePositiveDuration(keepAliveInterval,
							"MCP keep-alive interval");
			return this;
		}

		/**
		 * Sets the endpoint registry.
		 *
		 * @param endpointRegistry registry containing at least one endpoint
		 * @return this builder
		 */
		@NonNull
		public Builder endpointRegistry(@NonNull McpEndpointRegistry endpointRegistry) {
			this.endpointRegistry = requireNonNull(endpointRegistry);
			return this;
		}

		/**
		 * Enables request-scoped localization of framework-owned MCP presentation
		 * text. Omission preserves the canonical source text and existing wire
		 * behavior.
		 *
		 * @param localizer immutable localization behavior and policy, or null to
		 *                  disable localization
		 * @return this builder
		 */
		@NonNull
		public Builder localizer(@Nullable McpLocalizer localizer) {
			this.localizer = localizer;
			return this;
		}

		/**
		 * Sets the required authentication, authorization, and admission controller.
		 * Applications deliberately allowing anonymous access may use
		 * {@link McpAdmissionController#acceptAllInstance()}.
		 *
		 * @param admissionController admission controller
		 * @return this builder
		 */
		@NonNull
		public Builder admissionController(
				@NonNull McpAdmissionController admissionController) {
			this.admissionController = requireNonNull(admissionController);
			return this;
		}

		/**
		 * Configures the server-level application-handler interceptor. The default
		 * invokes the downstream continuation without transforming its result. Soklet
		 * may invoke one interceptor instance concurrently for independent handlers.
		 *
		 * @param handlerInterceptor application-owned handler interceptor, or null
		 *                           to restore pass-through behavior
		 * @return this builder
		 */
		@NonNull
		public Builder handlerInterceptor(
				@Nullable McpHandlerInterceptor handlerInterceptor) {
			this.handlerInterceptor = handlerInterceptor == null
					? McpHandlerInterceptor.passThroughInstance()
					: handlerInterceptor;
			return this;
		}

		/**
		 * Configures the server-level complete tool-output sanitizer. The default
		 * preserves output unchanged. Soklet may invoke one sanitizer instance
		 * concurrently for independent tool calls.
		 *
		 * @param toolOutputSanitizer application-owned tool-output sanitizer, or
		 *                            null to restore pass-through behavior
		 * @return this builder
		 */
		@NonNull
		public Builder toolOutputSanitizer(
				@Nullable McpToolOutputSanitizer toolOutputSanitizer) {
			this.toolOutputSanitizer = toolOutputSanitizer == null
					? McpToolOutputSanitizer.passThroughInstance()
					: toolOutputSanitizer;
			return this;
		}

		/**
		 * Configures the optional limiter applied once to every admitted MCP
		 * request or notification.
		 *
		 * @param requestRateLimiter application-owned request limiter, or null to
		 *                           disable request-wide limiting
		 * @return this builder
		 */
		@NonNull
		public Builder requestRateLimiter(
				@Nullable McpRateLimiter requestRateLimiter) {
			this.requestRateLimiter = requestRateLimiter;
			return this;
		}

		/**
		 * Configures the server-level fallback tool limiter. A fallback is required
		 * when any endpoint exposes a tool; endpoint and tool overrides replace it
		 * instead of adding another charge.
		 *
		 * @param toolRateLimiter application-owned fallback tool limiter, or null
		 *                        to clear it
		 * @return this builder
		 */
		@NonNull
		public Builder toolRateLimiter(@Nullable McpRateLimiter toolRateLimiter) {
			this.toolRateLimiter = toolRateLimiter;
			return this;
		}

		/**
		 * Configures the immutable registry used to resolve named endpoint and tool
		 * limiter overrides.
		 *
		 * @param rateLimiterRegistry rate-limiter registry, or null to restore the
		 *                            empty registry
		 * @return this builder
		 */
		@NonNull
		public Builder rateLimiterRegistry(
				@Nullable McpRateLimiterRegistry rateLimiterRegistry) {
			this.rateLimiterRegistry = rateLimiterRegistry == null
					? McpRateLimiterRegistry.emptyInstance()
					: rateLimiterRegistry;
			return this;
		}

		/**
		 * Sets the authorizer used when a request carries an Origin. Omission uses
		 * the secure reject-all default and emits one startup diagnostic per
		 * successful listener generation. Soklet may invoke the authorizer
		 * concurrently for independent requests, so custom implementations must be
		 * thread-safe.
		 *
		 * @param corsAuthorizer Origin authorizer, or null to restore secure
		 *                       reject-all behavior
		 * @return this builder
		 */
		@NonNull
		public Builder corsAuthorizer(@Nullable CorsAuthorizer corsAuthorizer) {
			this.corsAuthorizer = corsAuthorizer;
			return this;
		}

		/**
		 * Sets the policy for requests that omit Origin. The default is
		 * {@link McpAbsentOriginPolicy#ALLOW}.
		 *
		 * @param absentOriginPolicy absent-Origin policy, or null to restore the
		 *                           default
		 * @return this builder
		 */
		@NonNull
		public Builder absentOriginPolicy(
				@Nullable McpAbsentOriginPolicy absentOriginPolicy) {
			this.absentOriginPolicy = absentOriginPolicy == null
					? McpAbsentOriginPolicy.ALLOW
					: absentOriginPolicy;
			return this;
		}

		/**
		 * Sets the handling policy for unregistered {@code Mcp-Param-*} headers on
		 * JSON-RPC requests. The default is
		 * {@link McpUnknownMirroredHeaderPolicy#IGNORE}.
		 * <p>
		 * Unknown headers are never trusted as tool arguments. Strict rejection is
		 * an origin-server policy for deployments whose upstream components make
		 * routing or authorization decisions from mirrored headers; it does not
		 * apply to MCP notifications.
		 *
		 * @param unknownMirroredHeaderPolicy unknown-header policy, or null to
		 *                                    restore the default
		 * @return this builder
		 */
		@NonNull
		public Builder unknownMirroredHeaderPolicy(
				@Nullable McpUnknownMirroredHeaderPolicy
						unknownMirroredHeaderPolicy) {
			this.unknownMirroredHeaderPolicy =
					unknownMirroredHeaderPolicy == null
							? McpUnknownMirroredHeaderPolicy.IGNORE
							: unknownMirroredHeaderPolicy;
			return this;
		}

		/**
		 * Enables or disables bounded diagnostics that identify unregistered
		 * {@code Mcp-Param-*} request-header names. The default is {@code false}.
		 * <p>
		 * When enabled, Soklet emits at most ten
		 * {@link LogEventType#MCP_UNKNOWN_MIRRORED_HEADER} events for this server in
		 * any monotonic 60-second window. Each event contains the registered endpoint
		 * path and only the request header's name. ASCII HTTP token characters and
		 * casing are preserved, every other displayed character is replaced with
		 * {@code _}, and the result is truncated to 128 bytes. Header values are never
		 * logged, no {@link Request} is attached, and Soklet retains no observed-name
		 * set or cache. Repeated names are not deduplicated: each occurrence is
		 * independently eligible for the shared bound, and a failed event delivery
		 * consumes its attempted slot. The configured {@link LifecycleObserver}
		 * controls external retention of delivered events.
		 * Diagnostics are independent of {@link #unknownMirroredHeaderPolicy(
		 * McpUnknownMirroredHeaderPolicy)} and therefore apply under both policies;
		 * MCP notifications never produce them. Enabling diagnostics changes neither
		 * client-visible responses nor metric dimensions. The exact message format is
		 * {@code Unknown MCP mirrored header: endpointPath=<path>,
		 * headerName=<name>}, where {@code <path>} is the registered endpoint path
		 * and {@code <name>} is the sanitized, truncated received field name.
		 *
		 * @param unknownMirroredHeaderNameDiagnostics whether bounded name-bearing
		 *                                             diagnostics are enabled, or
		 *                                             null to restore the default
		 * @return this builder
		 */
		@NonNull
		public Builder unknownMirroredHeaderNameDiagnostics(
				@Nullable Boolean unknownMirroredHeaderNameDiagnostics) {
			this.unknownMirroredHeaderNameDiagnostics =
					unknownMirroredHeaderNameDiagnostics == null ? false
							: unknownMirroredHeaderNameDiagnostics;
			return this;
		}

		/**
		 * Enables pseudonymous trace correlation with exactly one initial active
		 * key. Omission leaves correlation disabled. The configured key is copied
		 * into server-owned state. A request carrying validated MCP trace metadata
		 * then produces a bounded {@link LogEventType#MCP_TRACE_CORRELATION} event
		 * at its exactly-once finish authority. The event carries the non-secret key
		 * ID, token-format version, and pseudonymous token, but no raw trace ID unless
		 * {@link #logRawValidatedTraceIds(Boolean)} is enabled independently.
		 *
		 * @param traceCorrelationKey initial trace-correlation key, or null to
		 *                            disable trace correlation
		 * @return this builder
		 */
		@NonNull
		public Builder traceCorrelationKey(
				@Nullable McpTraceCorrelationKey traceCorrelationKey) {
			this.traceCorrelationKey = traceCorrelationKey;
			return this;
		}

		/**
		 * Enables or disables the separate high-cardinality, log-only opt-in for
		 * raw validated trace IDs. The default is {@code false}. When enabled, only
		 * the 32-character trace ID from validated MCP request metadata may appear in
		 * {@link LogEventType#MCP_TRACE_CORRELATION}; Soklet never falls back to the
		 * HTTP request trace context and never logs the full {@code traceparent},
		 * parent/span ID, trace flags, {@code tracestate}, or {@code baggage}. This
		 * never enables pseudonymous correlation and never controls metric dimensions.
		 *
		 * <p>A validated trace ID remains client-controlled, sensitive cross-system
		 * correlation data; validation does not establish trust. Operators must
		 * restrict log access and retention to the minimum required for the intended
		 * APM join and account for the identifier's high cardinality and correlation
		 * reach.</p>
		 *
		 * @param logRawValidatedTraceIds whether raw validated trace IDs may appear
		 *                                in logs, or null to restore the default
		 * @return this builder
		 */
		@NonNull
		public Builder logRawValidatedTraceIds(
				@Nullable Boolean logRawValidatedTraceIds) {
			this.logRawValidatedTraceIds = logRawValidatedTraceIds == null ? false
					: logRawValidatedTraceIds;
			return this;
		}

		/**
		 * Configures framework request-state protection. Omission leaves framework
		 * protection unconfigured. A production keyring is copied into independent
		 * server-owned live state; runtime rotation is available only through
		 * {@link McpServer#getProtectionControl()}.
		 *
		 * @param protectionConfig initial protection configuration, or null to
		 *                         disable framework request-state protection
		 * @return this builder
		 */
		@NonNull
		public Builder protectionConfig(
				@Nullable McpProtectionConfig protectionConfig) {
			this.protectionConfig = protectionConfig;
			return this;
		}

		/**
		 * Replaces the hostname-only values accepted by MCP Host validation. Host
		 * ports must still equal the effective bound port. Each invocation replaces,
		 * rather than appends to, the previous set. Soklet snapshots the supplied
		 * values during the call. The default is the empty set.
		 *
		 * @param allowedHosts allowed hostnames or IP literals, or null to restore
		 *                     the empty set
		 * @return this builder
		 */
		@NonNull
		public Builder allowedHosts(
				@Nullable Set<@NonNull String> allowedHosts) {
			if (allowedHosts == null) {
				this.allowedHosts = Set.of();
				return this;
			}
			LinkedHashSet<@NonNull String> copied = new LinkedHashSet<>();
			allowedHosts.forEach(host -> copied.add(requireNonNull(host)));
			this.allowedHosts = Set.copyOf(copied);
			return this;
		}

		/**
		 * Builds a stopped MCP server.
		 *
		 * @return configured server
		 * @throws IllegalStateException if the keep-alive interval is not shorter
		 *                               than the write timeout, a configured limiter
		 *                               name is unknown, or tools exist without a fallback
		 *                               tool limiter, or a configured localization
		 *                               response exceeds its provider-lookup limit
		 */
		@NonNull
		public McpServer build() {
			SimulatorMcpBuildRegistrar exactSimulatorBuildRegistrar =
					this.simulatorBuildRegistrar;
			if (exactSimulatorBuildRegistrar != null)
				exactSimulatorBuildRegistrar.verifyBuildAllowed();
			if (this.keepAliveInterval.compareTo(this.writeTimeout) >= 0)
				throw new IllegalStateException(
						"The MCP keep-alive interval must be shorter than the write timeout.");
			boolean toolsPresent = this.endpointRegistry.getEndpoints().stream()
					.anyMatch(endpoint -> !endpoint.getTools().isEmpty());
			if (toolsPresent && this.toolRateLimiter == null)
				throw new IllegalStateException(
						"An MCP tool rate limiter must be configured when tools are registered.");
			for (McpEndpoint endpoint : this.endpointRegistry.getEndpoints()) {
				endpoint.getToolRateLimiterName().ifPresent(name ->
						requireRegisteredLimiter(name,
								"endpoint " + endpoint.getPath()));
				for (McpToolRegistration<?> tool : endpoint.getTools())
					tool.getRateLimiterName().ifPresent(name ->
							requireRegisteredLimiter(name,
									"tool " + tool.getName()));
			}
			DefaultMcpServer server = new DefaultMcpServer(this.port, this.host,
					this.maximumCursorSizeInBytes,
					this.requestHandlerConcurrency,
					this.requestHandlerQueueCapacity, this.requestTimeout,
					this.requestHandlerExecutorServiceSupplier,
					this.streamQueueCapacity, this.writeTimeout,
					this.keepAliveInterval,
					this.maximumSubscriptionsPerPartition,
					this.maximumSubscriptionDuration,
					this.endpointRegistry,
					this.admissionController, this.handlerInterceptor,
					this.toolOutputSanitizer, this.corsAuthorizer,
					this.absentOriginPolicy, this.unknownMirroredHeaderPolicy,
					this.unknownMirroredHeaderNameDiagnostics,
					this.logRawValidatedTraceIds,
					this.allowedHosts,
					this.requestRateLimiter, this.toolRateLimiter,
					this.rateLimiterRegistry, this.protectionConfig,
					this.traceCorrelationKey, this.localizer);
			if (exactSimulatorBuildRegistrar != null)
				exactSimulatorBuildRegistrar.register(server);
			return server;
		}

		private void requireRegisteredLimiter(@NonNull String name,
				@NonNull String owner) {
			if (this.rateLimiterRegistry.find(name).isEmpty())
				throw new IllegalStateException(
						"Unknown MCP rate limiter '" + name + "' for " + owner + ".");
		}

		@NonNull
		private static Duration requirePositiveDuration(@NonNull Duration value,
				@NonNull String description) {
			requireNonNull(value);
			if (value.isZero() || value.isNegative())
				throw new IllegalArgumentException(description + " must be positive.");
			try {
				if (value.toNanos() < 1L)
					throw new IllegalArgumentException(
							description + " must be positive.");
			} catch (ArithmeticException exception) {
				throw new IllegalArgumentException(
						description + " must fit in a signed nanosecond duration.",
						exception);
			}
			return value;
		}
	}

	private static int requirePort(int port) {
		if (port < 0 || port > 65_535)
			throw new IllegalArgumentException("port must be between 0 and 65535");
		return port;
	}
}
