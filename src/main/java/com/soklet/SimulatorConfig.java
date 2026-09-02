/*
 * Copyright 2022-2026 Revetware LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.soklet;

import com.soklet.converter.ValueConverterRegistry;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.util.Collection;
import java.util.function.Consumer;
import java.util.function.Function;

import static java.util.Objects.requireNonNull;

/**
 * Opaque immutable configuration token consumed by one
 * {@link SokletSimulator} run.
 * <p>
 * A {@link Builder} is created by {@link SokletSimulator#run(Function,
 * SokletSimulator.Body)} after the simulator has allocated fresh off-network
 * transports. Simulator configurations and their builders therefore belong to
 * exactly one run and cannot be created or reused independently. Application
 * code configures the supplied builder and returns {@link Builder#build()}; the
 * resulting token intentionally exposes no public accessors.
 */
@ThreadSafe
public final class SimulatorConfig {
	@NonNull
	private final SokletConfig sokletConfig;
	@NonNull
	private final SimulatorOptions simulatorOptions;
	@NonNull
	private final Object scopeIdentity;

	private SimulatorConfig(@NonNull Builder builder) {
		Builder exactBuilder = requireNonNull(builder);
		this.sokletConfig = exactBuilder.sokletConfigBuilder.build();
		this.simulatorOptions = exactBuilder.simulatorOptions == null
				? SimulatorOptions.defaultInstance()
				: exactBuilder.simulatorOptions;
		this.scopeIdentity = exactBuilder.scopeIdentity;
	}

	@NonNull
	SokletConfig getSokletConfig() {
		return this.sokletConfig;
	}

	@NonNull
	SimulatorOptions getSimulatorOptions() {
		return this.simulatorOptions;
	}

	boolean belongsTo(@NonNull Object scopeIdentity) {
		return this.scopeIdentity == requireNonNull(scopeIdentity);
	}

	/**
	 * Builds a configuration against the fresh off-network transports owned by
	 * one simulator run.
	 * <p>
	 * Instances are supplied only to a {@link SokletSimulator#run(Function,
	 * SokletSimulator.Body)} configuration function and are intended for use by
	 * that function's thread. A builder is sealed after {@link #build()} or when
	 * the configuration function returns.
	 */
	@NotThreadSafe
	public static final class Builder {
		@NonNull
		private final Object scopeIdentity;
		@NonNull
		private final HttpServer httpServer;
		@NonNull
		private final SseServer sseServer;
		@NonNull
		private final Function<@NonNull Integer, McpServer.@NonNull Builder>
				mcpServerBuilderFactory;
		@NonNull
		private final Runnable scopeGuard;
		private final SokletConfig.@NonNull Builder sokletConfigBuilder;
		@Nullable
		private SimulatorOptions simulatorOptions;
		private boolean built;

		Builder(@NonNull Object scopeIdentity,
				@NonNull HttpServer httpServer,
				@NonNull SseServer sseServer,
				@NonNull Function<@NonNull Integer,
						McpServer.@NonNull Builder> mcpServerBuilderFactory,
				@NonNull Runnable scopeGuard) {
			this.scopeIdentity = requireNonNull(scopeIdentity);
			this.httpServer = requireNonNull(httpServer);
			this.sseServer = requireNonNull(sseServer);
			this.mcpServerBuilderFactory = requireNonNull(
					mcpServerBuilderFactory);
			this.scopeGuard = requireNonNull(scopeGuard);
			this.sokletConfigBuilder = new SokletConfig.Builder();
		}

		/**
		 * Adds this run's fresh simulated HTTP server.
		 *
		 * @return this builder
		 */
		@NonNull
		public Builder httpServer() {
			requireMutable();
			this.sokletConfigBuilder.httpServer(this.httpServer);
			return this;
		}

		/**
		 * Adds this run's fresh simulated HTTP server and supplies that exact
		 * server for application dependency wiring.
		 *
		 * @param httpServerConsumer receives this run's HTTP server
		 * @return this builder
		 */
		@NonNull
		public Builder httpServer(
				@NonNull Consumer<@NonNull HttpServer> httpServerConsumer) {
			Consumer<@NonNull HttpServer> exactConsumer =
					requireNonNull(httpServerConsumer);
			requireMutable();
			exactConsumer.accept(this.httpServer);
			requireMutable();
			this.sokletConfigBuilder.httpServer(this.httpServer);
			return this;
		}

		/**
		 * Adds this run's fresh simulated Server-Sent Events server.
		 *
		 * @return this builder
		 */
		@NonNull
		public Builder sseServer() {
			requireMutable();
			this.sokletConfigBuilder.sseServer(this.sseServer);
			return this;
		}

		/**
		 * Adds this run's fresh simulated Server-Sent Events server and supplies
		 * that exact server for application dependency wiring.
		 *
		 * @param sseServerConsumer receives this run's SSE server
		 * @return this builder
		 */
		@NonNull
		public Builder sseServer(
				@NonNull Consumer<@NonNull SseServer> sseServerConsumer) {
			Consumer<@NonNull SseServer> exactConsumer =
					requireNonNull(sseServerConsumer);
			requireMutable();
			exactConsumer.accept(this.sseServer);
			requireMutable();
			this.sokletConfigBuilder.sseServer(this.sseServer);
			return this;
		}

		/**
		 * Builds and adds one fresh simulated MCP server owned by this run.
		 * The supplied function must invoke {@link McpServer.Builder#build()}
		 * and return the resulting server.
		 *
		 * @param port logical port in the range 0 through 65535
		 * @param mcpServerBuilder builds the server from this run's MCP builder
		 * @return this builder
		 */
		@NonNull
		public Builder mcpServer(@NonNull Integer port,
				@NonNull Function<McpServer.@NonNull Builder,
						@NonNull McpServer> mcpServerBuilder) {
			Integer exactPort = requireNonNull(port);
			Function<McpServer.@NonNull Builder, @NonNull McpServer>
					exactMcpServerBuilder = requireNonNull(mcpServerBuilder);
			requireMutable();
			McpServer.Builder scopedMcpServerBuilder = requireNonNull(
					this.mcpServerBuilderFactory.apply(exactPort));
			McpServer mcpServer = requireNonNull(
					exactMcpServerBuilder.apply(scopedMcpServerBuilder),
					"The simulator MCP server builder returned null");
			requireMutable();
			this.sokletConfigBuilder.mcpServer(mcpServer);
			return this;
		}

		/**
		 * Sets the startup and shutdown deadline policy shared by every configured
		 * lifecycle component.
		 *
		 * @param lifecyclePolicy lifecycle policy
		 * @return this builder
		 */
		@NonNull
		public Builder lifecyclePolicy(
				@NonNull LifecyclePolicy lifecyclePolicy) {
			requireMutable();
			this.sokletConfigBuilder.lifecyclePolicy(
					requireNonNull(lifecyclePolicy));
			return this;
		}

		@NonNull
		Builder internalLifecyclePolicy(
				@NonNull InternalLifecyclePolicy internalLifecyclePolicy) {
			requireMutable();
			this.sokletConfigBuilder.internalLifecyclePolicy(
					requireNonNull(internalLifecyclePolicy));
			return this;
		}

		/**
		 * Sets how Soklet creates application instances.
		 *
		 * @param instanceProvider instance provider, or {@code null} for the default
		 * @return this builder
		 */
		@NonNull
		public Builder instanceProvider(
				@Nullable InstanceProvider instanceProvider) {
			requireMutable();
			this.sokletConfigBuilder.instanceProvider(instanceProvider);
			return this;
		}

		/**
		 * Sets the registry used for Java value conversions.
		 *
		 * @param valueConverterRegistry registry, or {@code null} for the default
		 * @return this builder
		 */
		@NonNull
		public Builder valueConverterRegistry(
				@Nullable ValueConverterRegistry valueConverterRegistry) {
			requireMutable();
			this.sokletConfigBuilder.valueConverterRegistry(valueConverterRegistry);
			return this;
		}

		/**
		 * Sets how request bodies are converted to application values.
		 *
		 * @param requestBodyMarshaler marshaler, or {@code null} to derive one
		 * @return this builder
		 */
		@NonNull
		public Builder requestBodyMarshaler(
				@Nullable RequestBodyMarshaler requestBodyMarshaler) {
			requireMutable();
			this.sokletConfigBuilder.requestBodyMarshaler(requestBodyMarshaler);
			return this;
		}

		/**
		 * Sets how Soklet discovers and resolves Resource Methods.
		 *
		 * @param resourceMethodResolver resolver, or {@code null} for the default
		 * @return this builder
		 */
		@NonNull
		public Builder resourceMethodResolver(
				@Nullable ResourceMethodResolver resourceMethodResolver) {
			requireMutable();
			this.sokletConfigBuilder.resourceMethodResolver(resourceMethodResolver);
			return this;
		}

		/**
		 * Sets how parameters are supplied for Resource Method invocation.
		 *
		 * @param resourceMethodParameterProvider provider, or {@code null} for the
		 * default
		 * @return this builder
		 */
		@NonNull
		public Builder resourceMethodParameterProvider(
				@Nullable ResourceMethodParameterProvider
						resourceMethodParameterProvider) {
			requireMutable();
			this.sokletConfigBuilder.resourceMethodParameterProvider(
					resourceMethodParameterProvider);
			return this;
		}

		/**
		 * Sets how application response values are converted for transmission.
		 *
		 * @param responseMarshaler marshaler, or {@code null} for the default
		 * @return this builder
		 */
		@NonNull
		public Builder responseMarshaler(
				@Nullable ResponseMarshaler responseMarshaler) {
			requireMutable();
			this.sokletConfigBuilder.responseMarshaler(responseMarshaler);
			return this;
		}

		/**
		 * Sets the interceptor around application request handling.
		 *
		 * @param requestInterceptor interceptor, or {@code null} for the default
		 * @return this builder
		 */
		@NonNull
		public Builder requestInterceptor(
				@Nullable RequestInterceptor requestInterceptor) {
			requireMutable();
			this.sokletConfigBuilder.requestInterceptor(requestInterceptor);
			return this;
		}

		/**
		 * Replaces the configured lifecycle observers with one observer.
		 *
		 * @param lifecycleObserver observer, or {@code null} for no observers
		 * @return this builder
		 */
		@NonNull
		public Builder lifecycleObserver(
				@Nullable LifecycleObserver lifecycleObserver) {
			requireMutable();
			this.sokletConfigBuilder.lifecycleObserver(lifecycleObserver);
			return this;
		}

		/**
		 * Replaces the configured lifecycle observers in iteration order.
		 *
		 * @param lifecycleObservers observers, or {@code null} for no observers
		 * @return this builder
		 */
		@NonNull
		public Builder lifecycleObservers(
				@Nullable Collection<? extends LifecycleObserver>
						lifecycleObservers) {
			requireMutable();
			this.sokletConfigBuilder.lifecycleObservers(lifecycleObservers);
			return this;
		}

		/**
		 * Sets how Soklet records operational metrics.
		 *
		 * @param metricsCollector collector, or {@code null} for the default
		 * @return this builder
		 */
		@NonNull
		public Builder metricsCollector(
				@Nullable MetricsCollector metricsCollector) {
			requireMutable();
			this.sokletConfigBuilder.metricsCollector(metricsCollector);
			return this;
		}

		/**
		 * Sets the authorizer for ordinary HTTP and SSE CORS processing.
		 *
		 * @param corsAuthorizer authorizer, or {@code null} to reject all
		 * cross-origin requests
		 * @return this builder
		 */
		@NonNull
		public Builder corsAuthorizer(
				@Nullable CorsAuthorizer corsAuthorizer) {
			requireMutable();
			this.sokletConfigBuilder.corsAuthorizer(corsAuthorizer);
			return this;
		}

		/**
		 * Sets simulator request and response behavior options.
		 *
		 * @param simulatorOptions options, or {@code null} for defaults
		 * @return this builder
		 */
		@NonNull
		public Builder simulatorOptions(
				@Nullable SimulatorOptions simulatorOptions) {
			requireMutable();
			this.simulatorOptions = simulatorOptions;
			return this;
		}

		/**
		 * Builds the immutable configuration for this simulator run.
		 *
		 * @return completed simulator configuration
		 * @throws IllegalStateException if the builder is sealed, was already built,
		 * or has no simulated server
		 */
		@NonNull
		public SimulatorConfig build() {
			requireMutable();
			SimulatorConfig config = new SimulatorConfig(this);
			this.built = true;
			return config;
		}

		private void requireMutable() {
			this.scopeGuard.run();
			if (this.built)
				throw new IllegalStateException(
						"The simulator configuration has already been built");
		}
	}
}
