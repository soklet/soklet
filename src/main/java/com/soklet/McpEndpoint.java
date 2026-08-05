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
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * An immutable MCP endpoint registration.
 * <p>
 * An endpoint may contain only its path and server information. Such an
 * operation-free endpoint remains valid and advertises no optional operation
 * capabilities.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpEndpoint {
	@NonNull
	private final String path;
	@NonNull
	private final McpImplementation serverInformation;
	@Nullable
	private final String instructions;
	@NonNull
	private final List<@NonNull McpToolRegistration<?>> tools;
	@Nullable
	private final String toolRateLimiterName;
	@Nullable
	private final McpRateLimiter toolRateLimiter;

	/**
	 * Vends a builder primed with an MCP endpoint path.
	 *
	 * @param path the absolute endpoint path
	 * @return a builder for endpoint registrations
	 * @throws IllegalArgumentException if the path is not a non-root absolute
	 *                                  path or contains a query or fragment
	 */
	@NonNull
	public static Builder withPath(@NonNull String path) {
		return new Builder(normalizePath(path));
	}

	private McpEndpoint(@NonNull Builder builder) {
		requireNonNull(builder);

		if (builder.serverInformation == null)
			throw new IllegalStateException(
					"MCP endpoint server information must be configured.");

		this.path = builder.path;
		this.serverInformation = builder.serverInformation;
		this.instructions = builder.instructions;
		this.tools = List.copyOf(builder.tools);
		this.toolRateLimiterName = builder.toolRateLimiterName;
		this.toolRateLimiter = builder.toolRateLimiter;

		Set<String> toolNames = new LinkedHashSet<>();
		for (McpToolRegistration<?> tool : this.tools) {
			if (!toolNames.add(tool.getName()))
				throw new IllegalStateException(
						"Duplicate MCP tool name: " + tool.getName());
		}
	}

	/**
	 * The normalized absolute endpoint path.
	 *
	 * @return the endpoint path
	 */
	@NonNull
	public String getPath() {
		return this.path;
	}

	/**
	 * The required implementation information advertised by this endpoint.
	 *
	 * @return the server implementation information
	 */
	@NonNull
	public McpImplementation getServerInformation() {
		return this.serverInformation;
	}

	/**
	 * Optional human-readable instructions for clients using this endpoint.
	 *
	 * @return the instructions, or the empty optional if none were configured
	 */
	@NonNull
	public Optional<@NonNull String> getInstructions() {
		return Optional.ofNullable(this.instructions);
	}

	/**
	 * Returns the tools exposed by this endpoint in registration order.
	 *
	 * @return immutable tool registrations
	 */
	@NonNull
	public List<@NonNull McpToolRegistration<?>> getTools() {
		return this.tools;
	}

	/**
	 * Returns the named tool-limiter override.
	 * <p>
	 * At most one of this value and {@link #getToolRateLimiter()} is present.
	 *
	 * @return registry limiter name, or the empty optional for a direct or
	 * inherited limiter
	 */
	@NonNull
	public Optional<@NonNull String> getToolRateLimiterName() {
		return Optional.ofNullable(this.toolRateLimiterName);
	}

	/**
	 * Returns the direct tool-limiter override.
	 * <p>
	 * At most one of this value and {@link #getToolRateLimiterName()} is present.
	 *
	 * @return direct limiter, or the empty optional for a named or inherited
	 * limiter
	 */
	@NonNull
	public Optional<@NonNull McpRateLimiter> getToolRateLimiter() {
		return Optional.ofNullable(this.toolRateLimiter);
	}

	@NonNull
	private static String normalizePath(@NonNull String path) {
		requireNonNull(path);
		String strippedPath = path.strip();

		if (!strippedPath.startsWith("/") || strippedPath.length() == 1
				|| strippedPath.contains("?") || strippedPath.contains("#"))
			throw new IllegalArgumentException(
					"MCP endpoint path must be a non-root absolute path without a query or fragment.");

		String normalizedPath = ResourcePathDeclaration.normalizePath(strippedPath);

		if (normalizedPath.length() == 1)
			throw new IllegalArgumentException("MCP endpoint path must not be the root path.");

		return normalizedPath;
	}

	/**
	 * Builder for immutable {@link McpEndpoint} registrations.
	 * <p>
	 * This class is intended for use by a single thread.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static final class Builder {
		@NonNull
		private final String path;
		@Nullable
		private McpImplementation serverInformation;
		@Nullable
		private String instructions;
		@NonNull
		private final List<@NonNull McpToolRegistration<?>> tools;
		@Nullable
		private String toolRateLimiterName;
		@Nullable
		private McpRateLimiter toolRateLimiter;

		private Builder(@NonNull String path) {
			this.path = requireNonNull(path);
			this.tools = new ArrayList<>();
		}

		/**
		 * Sets the required implementation information advertised by this endpoint.
		 *
		 * @param serverInformation the server implementation information
		 * @return this builder
		 */
		@NonNull
		public Builder serverInformation(
				@NonNull McpImplementation serverInformation) {
			this.serverInformation = requireNonNull(serverInformation);
			return this;
		}

		/**
		 * Sets nonblank human-readable instructions for clients using this endpoint.
		 *
		 * @param instructions the endpoint instructions
		 * @return this builder
		 * @throws IllegalArgumentException if the instructions are blank
		 */
		@NonNull
		public Builder instructions(@NonNull String instructions) {
			requireNonNull(instructions);

			if (instructions.isBlank())
				throw new IllegalArgumentException(
						"MCP endpoint instructions must not be blank.");

			this.instructions = instructions;
			return this;
		}

		/**
		 * Adds a tool registration.
		 *
		 * @param tool tool registration
		 * @return this builder
		 */
		@NonNull
		public Builder tool(@NonNull McpToolRegistration<?> tool) {
			this.tools.add(requireNonNull(tool));
			return this;
		}

		/**
		 * Adds tool registrations in iteration order.
		 *
		 * @param tools tool registrations
		 * @return this builder
		 */
		@NonNull
		public Builder tools(
				@NonNull Collection<? extends @NonNull McpToolRegistration<?>> tools) {
			requireNonNull(tools);
			for (McpToolRegistration<?> tool : tools)
				tool(tool);
			return this;
		}

		/**
		 * Sets a named tool-limiter override.
		 * <p>
		 * Sequential named and direct setter calls are last-call-wins. This call
		 * clears any direct limiter previously configured on this builder.
		 *
		 * @param limiterName nonblank name in the server limiter registry
		 * @return this builder
		 */
		@NonNull
		public Builder toolRateLimiter(@NonNull String limiterName) {
			requireNonNull(limiterName);
			if (limiterName.isBlank())
				throw new IllegalArgumentException(
						"MCP rate-limiter name must not be blank.");
			this.toolRateLimiterName = limiterName;
			this.toolRateLimiter = null;
			return this;
		}

		/**
		 * Sets a direct tool-limiter override.
		 * <p>
		 * Sequential named and direct setter calls are last-call-wins. This call
		 * clears any limiter name previously configured on this builder.
		 *
		 * @param toolRateLimiter direct tool limiter
		 * @return this builder
		 */
		@NonNull
		public Builder toolRateLimiter(@NonNull McpRateLimiter toolRateLimiter) {
			this.toolRateLimiter = requireNonNull(toolRateLimiter);
			this.toolRateLimiterName = null;
			return this;
		}

		/**
		 * Builds an immutable endpoint.
		 * <p>
		 * No tool, prompt, or resource operation is required. Tool names must be
		 * unique within the endpoint.
		 *
		 * @return the endpoint
		 * @throws IllegalStateException if server information was not configured or
		 *                               a tool name is duplicated
		 */
		@NonNull
		public McpEndpoint build() {
			return new McpEndpoint(this);
		}
	}
}
