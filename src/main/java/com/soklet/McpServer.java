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
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * A dedicated modern MCP server managed by a core {@link Soklet} instance.
 * <p>
 * MCP always binds its own listener. It is never mounted inside Soklet's
 * ordinary {@link HttpServer} or {@link SseServer}.
 * Server lifecycle and diagnostic methods are safe to invoke concurrently.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public sealed interface McpServer extends AutoCloseable permits DefaultMcpServer {
	/**
	 * Starts this server and returns only after its listening socket is bound.
	 * A redundant start while already running is a no-op.
	 */
	void start();

	/**
	 * Stops this server. Stopping an already stopped server is a no-op.
	 */
	void stop();

	/**
	 * Is this server currently accepting MCP requests?
	 *
	 * @return {@code true} while started
	 */
	boolean isStarted();

	/**
	 * Returns the immutable endpoint resolver.
	 *
	 * @return handler resolver
	 */
	@NonNull
	McpHandlerResolver getHandlerResolver();

	/**
	 * Returns the required request-admission policy.
	 *
	 * @return admission policy
	 */
	@NonNull
	McpRequestAdmissionPolicy getRequestAdmissionPolicy();

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
	 * Captures immutable point-in-time server diagnostics.
	 *
	 * @return diagnostics snapshot
	 */
	@NonNull
	McpServerDiagnostics getDiagnostics();

	/**
	 * Stops this server, equivalent to {@link #stop()}.
	 */
	@Override
	default void close() {
		stop();
	}

	/**
	 * Vends a server builder primed with a dedicated TCP port.
	 * Port {@code 0} requests an operating-system-assigned port.
	 *
	 * @param port port in the range 0 through 65535
	 * @return server builder
	 */
	@NonNull
	static Builder withPort(int port) {
		return new Builder(requirePort(port));
	}

	/**
	 * Single-threaded builder for Soklet's built-in MCP server.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	final class Builder {
		private int port;
		@NonNull
		private String host;
		@Nullable
		private McpHandlerResolver handlerResolver;
		@Nullable
		private McpRequestAdmissionPolicy requestAdmissionPolicy;
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
		private Set<@NonNull String> allowedHosts;

		private Builder(int port) {
			this.port = port;
			this.host = "127.0.0.1";
			this.absentOriginPolicy = McpAbsentOriginPolicy.ALLOW;
			this.allowedHosts = Set.of();
			this.rateLimiterRegistry = McpRateLimiterRegistry.emptyInstance();
		}

		/**
		 * Sets the dedicated TCP port. Port {@code 0} requests an
		 * operating-system-assigned port.
		 *
		 * @param port port in the range 0 through 65535
		 * @return this builder
		 * @throws IllegalArgumentException if the port is outside the valid range
		 */
		@NonNull
		public Builder port(int port) {
			this.port = requirePort(port);
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
		 * Sets the endpoint and handler resolver.
		 *
		 * @param handlerResolver resolver containing at least one endpoint
		 * @return this builder
		 */
		@NonNull
		public Builder handlerResolver(@NonNull McpHandlerResolver handlerResolver) {
			this.handlerResolver = requireNonNull(handlerResolver);
			return this;
		}

		/**
		 * Sets the required authentication, authorization, and admission policy.
		 * Applications deliberately allowing anonymous access may use
		 * {@link McpRequestAdmissionPolicy#acceptAllInstance()}.
		 *
		 * @param requestAdmissionPolicy admission policy
		 * @return this builder
		 */
		@NonNull
		public Builder requestAdmissionPolicy(
				@NonNull McpRequestAdmissionPolicy requestAdmissionPolicy) {
			this.requestAdmissionPolicy = requireNonNull(requestAdmissionPolicy);
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
		 * @throws IllegalStateException if resolver or admission policy is absent,
		 *                               a configured limiter name is unknown, or
		 *                               tools exist without a fallback tool limiter
		 */
		@NonNull
		public McpServer build() {
			if (this.handlerResolver == null)
				throw new IllegalStateException("An MCP handler resolver must be configured.");
			if (this.requestAdmissionPolicy == null)
				throw new IllegalStateException(
						"An MCP request-admission policy must be configured.");
			if (this.handlerResolver.getEndpoints().size() != 1)
				throw new IllegalStateException(
						"This MCP implementation checkpoint supports exactly one endpoint.");
			boolean toolsPresent = this.handlerResolver.getEndpoints().stream()
					.anyMatch(endpoint -> !endpoint.getTools().isEmpty());
			if (toolsPresent && this.toolRateLimiter == null)
				throw new IllegalStateException(
						"An MCP tool rate limiter must be configured when tools are registered.");
			for (McpEndpoint endpoint : this.handlerResolver.getEndpoints()) {
				endpoint.getToolRateLimiterName().ifPresent(name ->
						requireRegisteredLimiter(name,
								"endpoint " + endpoint.getPath()));
				for (McpToolRegistration<?> tool : endpoint.getTools())
					tool.getRateLimiterName().ifPresent(name ->
							requireRegisteredLimiter(name,
									"tool " + tool.getName()));
			}
			return new DefaultMcpServer(this.port, this.host, this.handlerResolver,
					this.requestAdmissionPolicy, this.corsAuthorizer,
					this.absentOriginPolicy, this.allowedHosts,
					this.requestRateLimiter, this.toolRateLimiter,
					this.rateLimiterRegistry);
		}

		private void requireRegisteredLimiter(@NonNull String name,
				@NonNull String owner) {
			if (this.rateLimiterRegistry.find(name).isEmpty())
				throw new IllegalStateException(
						"Unknown MCP rate limiter '" + name + "' for " + owner + ".");
		}
	}

	private static int requirePort(int port) {
		if (port < 0 || port > 65_535)
			throw new IllegalArgumentException("port must be between 0 and 65535");
		return port;
	}
}
