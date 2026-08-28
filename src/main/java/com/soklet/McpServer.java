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
	 * application cursor.
	 *
	 * @return positive cursor-size limit in bytes
	 */
	@NonNull Integer getMaximumCursorSizeInBytes();

	/**
	 * Returns this server's request-state protection control plane.
	 * <p>
	 * The control never exposes configured key material. Mutation methods reject
	 * calls unless this server was built with a production key ring.
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
	 * Vends a server builder primed with a dedicated TCP port.
	 * Port {@code 0} requests an operating-system-assigned port.
	 *
	 * @param port port in the range 0 through 65535
	 * @return server builder
	 * @throws NullPointerException if {@code port} is null
	 */
	@NonNull
	static Builder withPort(@NonNull Integer port) {
		return new Builder(requirePort(requireNonNull(port)));
	}

	/**
	 * Single-threaded builder for Soklet's built-in MCP server.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	final class Builder {
		private static final int DEFAULT_MAXIMUM_CURSOR_SIZE_IN_BYTES = 4_096;
		private static final int DEFAULT_MAXIMUM_SUBSCRIPTIONS_PER_PRINCIPAL = 32;
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
		private int maximumSubscriptionsPerPrincipal;
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
		@Nullable
		private McpEndpointRegistry endpointRegistry;
		@Nullable
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

		private Builder(int port) {
			this.port = port;
			this.maximumCursorSizeInBytes = DEFAULT_MAXIMUM_CURSOR_SIZE_IN_BYTES;
			this.maximumSubscriptionsPerPrincipal =
					DEFAULT_MAXIMUM_SUBSCRIPTIONS_PER_PRINCIPAL;
			this.requestHandlerConcurrency = DEFAULT_REQUEST_HANDLER_CONCURRENCY;
			this.requestHandlerQueueCapacity =
					DEFAULT_REQUEST_HANDLER_QUEUE_CAPACITY;
			this.streamQueueCapacity = DEFAULT_STREAM_QUEUE_CAPACITY;
			this.host = "127.0.0.1";
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
		 * @param host nonblank bind host
		 * @return this builder
		 */
		@NonNull
		public Builder host(@NonNull String host) {
			requireNonNull(host);
			if (host.isBlank())
				throw new IllegalArgumentException("MCP bind host must not be blank.");
			this.host = host;
			return this;
		}

		/**
		 * Sets the maximum UTF-8 encoded size of an incoming or outgoing
		 * application cursor. The default is {@code 4096} bytes.
		 *
		 * @param maximumCursorSizeInBytes positive cursor-size limit in bytes
		 * @return this builder
		 * @throws NullPointerException if {@code maximumCursorSizeInBytes} is null
		 * @throws IllegalArgumentException if the limit is not positive
		 */
		@NonNull
		public Builder maximumCursorSizeInBytes(
				@NonNull Integer maximumCursorSizeInBytes) {
			requireNonNull(maximumCursorSizeInBytes);
			if (maximumCursorSizeInBytes < 1)
				throw new IllegalArgumentException(
						"MCP maximum cursor size must be positive.");
			this.maximumCursorSizeInBytes = maximumCursorSizeInBytes;
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
		 * @param maximumSubscriptionsPerPrincipal subscription limit per endpoint
		 *                                          and authorization/quota partition
		 * @return this builder
		 * @throws NullPointerException if {@code maximumSubscriptionsPerPrincipal}
		 *                              is null
		 * @throws IllegalArgumentException if the value is not positive
		 */
		@NonNull
		public Builder maximumSubscriptionsPerPrincipal(
				@NonNull Integer maximumSubscriptionsPerPrincipal) {
			requireNonNull(maximumSubscriptionsPerPrincipal);
			if (maximumSubscriptionsPerPrincipal < 1)
				throw new IllegalArgumentException(
						"MCP maximum subscriptions per principal must be positive.");
			this.maximumSubscriptionsPerPrincipal =
					maximumSubscriptionsPerPrincipal;
			return this;
		}

		/**
		 * Sets the positive finite lifetime of one subscription. The default is
		 * 24 hours. This setting has neutral behavior until subscriptions are
		 * active.
		 *
		 * @param maximumSubscriptionDuration maximum subscription lifetime
		 * @return this builder
		 * @throws IllegalArgumentException if the duration is not positive and
		 *                                  representable as signed nanoseconds
		 */
		@NonNull
		public Builder maximumSubscriptionDuration(
				@NonNull Duration maximumSubscriptionDuration) {
			this.maximumSubscriptionDuration = requirePositiveDuration(
					maximumSubscriptionDuration,
					"MCP maximum subscription duration");
			return this;
		}

		/**
		 * Sets the absolute client-visible request deadline. The deadline does not
		 * forcibly terminate application code that ignores interruption.
		 * The default is 60 seconds.
		 *
		 * @param requestTimeout positive finite request timeout
		 * @return this builder
		 * @throws IllegalArgumentException if the timeout is zero, negative, below
		 *                                  one nanosecond, or too large to represent
		 *                                  as signed nanoseconds
		 */
		@NonNull
		public Builder requestTimeout(@NonNull Duration requestTimeout) {
			this.requestTimeout = requirePositiveDuration(requestTimeout,
					"MCP request timeout");
			return this;
		}

		/**
		 * Sets the positive, finite number of application handler dispatches that
		 * may hold server-wide execution slots. The default is {@code 32}.
		 *
		 * @param requestHandlerConcurrency maximum active handler dispatches
		 * @return this builder
		 * @throws NullPointerException if {@code requestHandlerConcurrency} is null
		 * @throws IllegalArgumentException if the value is not positive
		 */
		@NonNull
		public Builder requestHandlerConcurrency(
				@NonNull Integer requestHandlerConcurrency) {
			requireNonNull(requestHandlerConcurrency);
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
		 * @param requestHandlerQueueCapacity maximum queued handler dispatches
		 * @return this builder
		 * @throws NullPointerException if {@code requestHandlerQueueCapacity} is null
		 * @throws IllegalArgumentException if the value is not positive
		 */
		@NonNull
		public Builder requestHandlerQueueCapacity(
				@NonNull Integer requestHandlerQueueCapacity) {
			requireNonNull(requestHandlerQueueCapacity);
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
		 * @param requestHandlerExecutorServiceSupplier executor supplier
		 * @return this builder
		 */
		@NonNull
		public Builder requestHandlerExecutorServiceSupplier(
				@NonNull Supplier<@NonNull ExecutorService>
						requestHandlerExecutorServiceSupplier) {
			this.requestHandlerExecutorServiceSupplier = requireNonNull(
					requestHandlerExecutorServiceSupplier);
			return this;
		}

		/**
		 * Sets the positive, finite number of pending messages retained for one
		 * MCP response or subscription stream. The default is {@code 128}.
		 * This setting has neutral behavior until its streaming owner is active.
		 *
		 * @param streamQueueCapacity maximum pending messages per stream
		 * @return this builder
		 * @throws NullPointerException if {@code streamQueueCapacity} is null
		 * @throws IllegalArgumentException if the value is not positive
		 */
		@NonNull
		public Builder streamQueueCapacity(@NonNull Integer streamQueueCapacity) {
			requireNonNull(streamQueueCapacity);
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
		 * @param writeTimeout maximum interval without a stream write
		 * @return this builder
		 * @throws IllegalArgumentException if the duration is not positive and
		 *                                  representable as signed nanoseconds
		 */
		@NonNull
		public Builder writeTimeout(@NonNull Duration writeTimeout) {
			this.writeTimeout = requirePositiveDuration(writeTimeout,
					"MCP write timeout");
			return this;
		}

		/**
		 * Sets the positive finite interval between idle SSE keep-alive comments.
		 * The default is 15 seconds. This setting has neutral behavior until its
		 * streaming owner is active.
		 *
		 * @param keepAliveInterval SSE keep-alive interval
		 * @return this builder
		 * @throws IllegalArgumentException if the duration is not positive and
		 *                                  representable as signed nanoseconds
		 */
		@NonNull
		public Builder keepAliveInterval(@NonNull Duration keepAliveInterval) {
			this.keepAliveInterval = requirePositiveDuration(keepAliveInterval,
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
		 * @param localizer immutable localization behavior and policy
		 * @return this builder
		 * @throws NullPointerException if {@code localizer} is null
		 */
		@NonNull
		public Builder localizer(@NonNull McpLocalizer localizer) {
			this.localizer = requireNonNull(localizer);
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
		 * @param handlerInterceptor application-owned handler interceptor
		 * @return this builder
		 */
		@NonNull
		public Builder handlerInterceptor(
				@NonNull McpHandlerInterceptor handlerInterceptor) {
			this.handlerInterceptor = requireNonNull(handlerInterceptor);
			return this;
		}

		/**
		 * Configures the server-level complete tool-output sanitizer. The default
		 * preserves output unchanged. Soklet may invoke one sanitizer instance
		 * concurrently for independent tool calls.
		 *
		 * @param toolOutputSanitizer application-owned tool-output sanitizer
		 * @return this builder
		 */
		@NonNull
		public Builder toolOutputSanitizer(
				@NonNull McpToolOutputSanitizer toolOutputSanitizer) {
			this.toolOutputSanitizer = requireNonNull(toolOutputSanitizer);
			return this;
		}

		/**
		 * Configures the optional limiter applied once to every admitted MCP
		 * request or notification.
		 *
		 * @param requestRateLimiter application-owned request limiter
		 * @return this builder
		 */
		@NonNull
		public Builder requestRateLimiter(
				@NonNull McpRateLimiter requestRateLimiter) {
			this.requestRateLimiter = requireNonNull(requestRateLimiter);
			return this;
		}

		/**
		 * Configures the server-level fallback tool limiter. A fallback is required
		 * when any endpoint exposes a tool; endpoint and tool overrides replace it
		 * instead of adding another charge.
		 *
		 * @param toolRateLimiter application-owned fallback tool limiter
		 * @return this builder
		 */
		@NonNull
		public Builder toolRateLimiter(@NonNull McpRateLimiter toolRateLimiter) {
			this.toolRateLimiter = requireNonNull(toolRateLimiter);
			return this;
		}

		/**
		 * Configures the immutable registry used to resolve named endpoint and tool
		 * limiter overrides.
		 *
		 * @param rateLimiterRegistry rate-limiter registry
		 * @return this builder
		 */
		@NonNull
		public Builder rateLimiterRegistry(
				@NonNull McpRateLimiterRegistry rateLimiterRegistry) {
			this.rateLimiterRegistry = requireNonNull(rateLimiterRegistry);
			return this;
		}

		/**
		 * Sets the authorizer used when a request carries an Origin. Omission uses
		 * the secure reject-all default and emits one startup diagnostic per
		 * successful listener generation. Soklet may invoke the authorizer
		 * concurrently for independent requests, so custom implementations must be
		 * thread-safe.
		 *
		 * @param corsAuthorizer Origin authorizer
		 * @return this builder
		 */
		@NonNull
		public Builder corsAuthorizer(@NonNull CorsAuthorizer corsAuthorizer) {
			this.corsAuthorizer = requireNonNull(corsAuthorizer);
			return this;
		}

		/**
		 * Sets the policy for requests that omit Origin. The default is
		 * {@link McpAbsentOriginPolicy#ALLOW}.
		 *
		 * @param absentOriginPolicy absent-Origin policy
		 * @return this builder
		 */
		@NonNull
		public Builder absentOriginPolicy(
				@NonNull McpAbsentOriginPolicy absentOriginPolicy) {
			this.absentOriginPolicy = requireNonNull(absentOriginPolicy);
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
		 * @param unknownMirroredHeaderPolicy unknown-header policy
		 * @return this builder
		 */
		@NonNull
		public Builder unknownMirroredHeaderPolicy(
				@NonNull McpUnknownMirroredHeaderPolicy
						unknownMirroredHeaderPolicy) {
			this.unknownMirroredHeaderPolicy = requireNonNull(
					unknownMirroredHeaderPolicy);
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
		 * @param enabled whether bounded name-bearing diagnostics are enabled
		 * @return this builder
		 * @throws NullPointerException if {@code enabled} is null
		 */
		@NonNull
		public Builder unknownMirroredHeaderNameDiagnostics(
				@NonNull Boolean enabled) {
			this.unknownMirroredHeaderNameDiagnostics = requireNonNull(enabled);
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
		 * @param traceCorrelationKey initial trace-correlation key
		 * @return this builder
		 */
		@NonNull
		public Builder traceCorrelationKey(
				@NonNull McpTraceCorrelationKey traceCorrelationKey) {
			this.traceCorrelationKey = requireNonNull(traceCorrelationKey);
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
		 * @param enabled whether raw validated trace IDs may appear in logs
		 * @return this builder
		 * @throws NullPointerException if {@code enabled} is null
		 */
		@NonNull
		public Builder logRawValidatedTraceIds(@NonNull Boolean enabled) {
			this.logRawValidatedTraceIds = requireNonNull(enabled);
			return this;
		}

		/**
		 * Configures framework request-state protection. Omission leaves framework
		 * protection unconfigured. A production key ring is copied into independent
		 * server-owned live state; runtime rotation is available only through
		 * {@link McpServer#getProtectionControl()}.
		 *
		 * @param protectionConfig initial protection configuration
		 * @return this builder
		 */
		@NonNull
		public Builder protectionConfig(
				@NonNull McpProtectionConfig protectionConfig) {
			this.protectionConfig = requireNonNull(protectionConfig);
			return this;
		}

		/**
		 * Adds hostname-only values accepted by MCP Host validation. Host ports
		 * must still equal the effective bound port.
		 *
		 * @param allowedHosts additional allowed hostnames or IP literals
		 * @return this builder
		 */
		@NonNull
		public Builder allowedHosts(@NonNull Set<@NonNull String> allowedHosts) {
			requireNonNull(allowedHosts);
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
		 *                               than the write timeout, registry or admission
		 *                               controller is absent, a configured limiter name is
		 *                               unknown, or tools exist without a fallback
		 *                               tool limiter, or a configured localization
		 *                               response exceeds its provider-lookup limit
		 */
		@NonNull
		public McpServer build() {
			if (this.keepAliveInterval.compareTo(this.writeTimeout) >= 0)
				throw new IllegalStateException(
						"The MCP keep-alive interval must be shorter than the write timeout.");
			if (this.endpointRegistry == null)
				throw new IllegalStateException("An MCP endpoint registry must be configured.");
			if (this.admissionController == null)
				throw new IllegalStateException(
						"An MCP admission controller must be configured.");
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
					this.maximumSubscriptionsPerPrincipal,
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
			if (this.simulatorBuildRegistrar != null)
				this.simulatorBuildRegistrar.register(server);
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
