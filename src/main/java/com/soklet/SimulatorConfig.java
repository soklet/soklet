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

import com.soklet.Soklet.MockHttpServer;
import com.soklet.Soklet.MockSseServer;
import com.soklet.converter.ValueConverterRegistry;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static java.util.Objects.requireNonNull;

/**
 * Opaque, single-use execution configuration consumed by one
 * {@link SokletSimulator} run attempt.
 * <p>
 * Each {@link #builder()} owns a fresh set of off-network transports. A
 * successful {@link Builder#build()} seals that transport graph, and
 * {@link SokletSimulator#run(SimulatorConfig, SokletSimulator.Simulation)}
 * atomically claims the completed configuration before lifecycle work begins.
 * Sequential or concurrent reuse is rejected. Create a new configuration for
 * each simulation run.
 * <p>
 * Instances are thread-safe, but deliberately stateful: claiming a run is an
 * irreversible operation. The configuration intentionally exposes no public
 * accessors; configured transports are available from the run's
 * {@link Simulator}.
 */
@ThreadSafe
public final class SimulatorConfig {
	@NonNull
	private final SokletConfig sokletConfig;
	@NonNull
	private final SimulatorOptions simulatorOptions;
	@NonNull
	private final ConfigurationGraph configurationGraph;
	@NonNull
	private final AtomicBoolean runClaimed;

	private SimulatorConfig(@NonNull Builder builder) {
		Builder exactBuilder = requireNonNull(builder);
		this.sokletConfig = exactBuilder.sokletConfigBuilder.build();
		this.simulatorOptions = exactBuilder.simulatorOptions == null
				? SimulatorOptions.defaultInstance()
				: exactBuilder.simulatorOptions;
		this.configurationGraph = exactBuilder.configurationGraph;
		this.runClaimed = new AtomicBoolean();
	}

	/**
	 * Creates a builder backed by a fresh off-network transport graph.
	 *
	 * @return a fresh simulator-configuration builder
	 */
	@NonNull
	public static Builder builder() {
		return new Builder();
	}

	@NonNull
	SokletConfig getSokletConfig() {
		return this.sokletConfig;
	}

	@NonNull
	SimulatorOptions getSimulatorOptions() {
		return this.simulatorOptions;
	}

	void claimForRun() {
		if (!this.runClaimed.compareAndSet(false, true))
			throw new IllegalStateException(
					"The simulator configuration has already been claimed by a run");
	}

	@NonNull
	Object configurationIdentity() {
		return this.configurationGraph.configurationIdentity();
	}

	boolean belongsTo(@NonNull Object configurationIdentity) {
		return this.configurationGraph.configurationIdentity()
				== requireNonNull(configurationIdentity);
	}

	@NonNull
	MockHttpServer simulatedHttpServer() {
		return this.configurationGraph.httpServer();
	}

	@NonNull
	MockSseServer simulatedSseServer() {
		return this.configurationGraph.sseServer();
	}

	@Nullable
	DefaultMcpServer simulatedMcpServer() {
		return this.configurationGraph.mcpServer();
	}

	/**
	 * Builds a single-use configuration against fresh off-network transports.
	 * <p>
	 * A builder is sealed after a successful {@link #build()}. Builders are not
	 * thread-safe and must not be retained after building.
	 */
	@NotThreadSafe
	public static final class Builder {
		@NonNull
		private final ConfigurationGraph configurationGraph;
		private final SokletConfig.@NonNull Builder sokletConfigBuilder;
		@Nullable
		private SimulatorOptions simulatorOptions;
		private boolean built;
		private int activeTransportConfigurers;

		private Builder() {
			this.configurationGraph = new ConfigurationGraph();
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
			this.sokletConfigBuilder.httpServer(
					this.configurationGraph.httpServer());
			return this;
		}

		/**
		 * Adds this run's fresh simulated HTTP server and supplies that exact
		 * server for configuration-time application dependency wiring. For access
		 * from the simulation body, prefer {@link Simulator#getHttpServer()}.
		 * The consumer runs synchronously during this call and must not retain the
		 * server for another configuration.
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
			beginTransportConfigurer();
			try {
				exactConsumer.accept(this.configurationGraph.httpServer());
			} finally {
				endTransportConfigurer();
			}
			requireMutable();
			this.sokletConfigBuilder.httpServer(
					this.configurationGraph.httpServer());
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
			this.sokletConfigBuilder.sseServer(
					this.configurationGraph.sseServer());
			return this;
		}

		/**
		 * Adds this run's fresh simulated Server-Sent Events server and supplies
		 * that exact server for configuration-time application dependency wiring.
		 * For access from the simulation body, prefer
		 * {@link Simulator#getSseServer()}. The consumer runs synchronously during
		 * this call and must not retain the server for another configuration.
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
			beginTransportConfigurer();
			try {
				exactConsumer.accept(this.configurationGraph.sseServer());
			} finally {
				endTransportConfigurer();
			}
			requireMutable();
			this.sokletConfigBuilder.sseServer(
					this.configurationGraph.sseServer());
			return this;
		}

		/**
		 * Builds and adds one fresh simulated MCP server owned by this
		 * configuration.
		 *
		 * @param port logical port in the range 0 through 65535
		 * @param endpointRegistry endpoint registry
		 * @param admissionController admission controller
		 * @return this builder
		 */
		@NonNull
		public Builder mcpServer(@NonNull Integer port,
				@NonNull McpEndpointRegistry endpointRegistry,
				@NonNull McpAdmissionController admissionController) {
			return mcpServer(port, endpointRegistry, admissionController,
					ignored -> {
					});
		}

		/**
		 * Builds and adds one fresh simulated MCP server owned by this
		 * configuration after applying optional server customizations.
		 * <p>
		 * The consumer runs synchronously and must only customize the supplied
		 * builder. This outer builder owns the call to
		 * {@link McpServer.Builder#build()}; calling it from the consumer or
		 * retaining the builder for later use is rejected.
		 *
		 * @param port logical port in the range 0 through 65535
		 * @param endpointRegistry endpoint registry
		 * @param admissionController admission controller
		 * @param mcpServerConfigurer customizes this configuration's MCP builder
		 * @return this builder
		 */
		@NonNull
		public Builder mcpServer(@NonNull Integer port,
				@NonNull McpEndpointRegistry endpointRegistry,
				@NonNull McpAdmissionController admissionController,
				@NonNull Consumer<McpServer.@NonNull Builder>
						mcpServerConfigurer) {
			Integer exactPort = requireNonNull(port);
			McpEndpointRegistry exactEndpointRegistry =
					requireNonNull(endpointRegistry);
			McpAdmissionController exactAdmissionController =
					requireNonNull(admissionController);
			Consumer<McpServer.@NonNull Builder> exactConfigurer =
					requireNonNull(mcpServerConfigurer);
			requireMutable();
			McpBuilderLease lease = this.configurationGraph
					.openMcpBuilder(exactPort);
			try {
				McpServer.Builder mcpServerBuilder = lease.builder()
						.endpointRegistry(exactEndpointRegistry)
						.admissionController(exactAdmissionController);
				beginTransportConfigurer();
				try {
					exactConfigurer.accept(mcpServerBuilder);
				} finally {
					endTransportConfigurer();
				}
				requireMutable();
				mcpServerBuilder.port(exactPort)
						.endpointRegistry(exactEndpointRegistry)
						.admissionController(exactAdmissionController);
				lease.finishConfiguration();
				McpServer mcpServer = mcpServerBuilder.build();
				requireMutable();
				this.sokletConfigBuilder.mcpServer(mcpServer);
			} finally {
				lease.close();
			}
			return this;
		}

		/**
		 * Sets the startup and shutdown deadline policy shared by every configured
		 * lifecycle component. Passing {@code null} restores the built-in default.
		 *
		 * @param lifecyclePolicy lifecycle policy, or {@code null} to use the default
		 * @return this builder
		 */
		@NonNull
		public Builder lifecyclePolicy(
				@Nullable LifecyclePolicy lifecyclePolicy) {
			requireMutable();
			this.sokletConfigBuilder.lifecyclePolicy(lifecyclePolicy);
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
		 * Builds the single-use configuration for one simulator run attempt.
		 *
		 * @return completed simulator configuration
		 * @throws IllegalStateException if the builder is sealed, was already built,
		 * or has no simulated server
		 */
		@NonNull
		public SimulatorConfig build() {
			requireMutable();
			if (this.activeTransportConfigurers != 0)
				throw new IllegalStateException(
						"A simulator configuration cannot be built from a transport configurer");
			SimulatorConfig config = new SimulatorConfig(this);
			this.built = true;
			this.configurationGraph.seal();
			return config;
		}

		private void requireMutable() {
			if (this.built)
				throw new IllegalStateException(
						"The simulator configuration has already been built");
			this.configurationGraph.requireOpen();
		}

		private void beginTransportConfigurer() {
			this.activeTransportConfigurers++;
		}

		private void endTransportConfigurer() {
			this.activeTransportConfigurers--;
		}
	}

	@ThreadSafe
	private static final class ConfigurationGraph {
		@NonNull
		private final Object configurationIdentity;
		@NonNull
		private final MockHttpServer httpServer;
		@NonNull
		private final MockSseServer sseServer;
		private boolean open;
		private @Nullable DefaultMcpServer mcpServer;
		private @Nullable McpBuilderLease activeMcpBuilderLease;

		private ConfigurationGraph() {
			this.configurationIdentity = new Object();
			this.httpServer = new MockHttpServer();
			this.sseServer = new MockSseServer();
			this.open = true;
		}

		@NonNull
		private Object configurationIdentity() {
			return this.configurationIdentity;
		}

		@NonNull
		private MockHttpServer httpServer() {
			return this.httpServer;
		}

		@NonNull
		private MockSseServer sseServer() {
			return this.sseServer;
		}

		@Nullable
		private synchronized DefaultMcpServer mcpServer() {
			return this.mcpServer;
		}

		@NonNull
		private synchronized McpBuilderLease openMcpBuilder(
				@NonNull Integer port) {
			requireOpen();
			if (this.mcpServer != null)
				throw new IllegalStateException(
						"A simulator configuration may build at most one MCP server");
			if (this.activeMcpBuilderLease != null)
				throw new IllegalStateException(
						"A simulator MCP builder is already active");
			McpBuilderLease lease = new McpBuilderLease(this,
					requireNonNull(port));
			this.activeMcpBuilderLease = lease;
			return lease;
		}

		private synchronized void verifyBuildAllowed(
				@NonNull McpBuilderLease lease) {
			if (this.activeMcpBuilderLease != requireNonNull(lease))
				throw new IllegalStateException(
						"The simulator MCP builder is no longer active");
			requireOpen();
			if (!lease.buildAllowed())
				throw new IllegalStateException(
						"Only SimulatorConfig.Builder may build the simulator MCP server");
			if (this.mcpServer != null)
				throw new IllegalStateException(
						"A simulator configuration may build at most one MCP server");
		}

		private synchronized void register(@NonNull McpBuilderLease lease,
				@NonNull DefaultMcpServer server) {
			verifyBuildAllowed(lease);
			DefaultMcpServer registered = requireNonNull(server);
			registered.claimSimulatorScope(this);
			this.mcpServer = registered;
		}

		private synchronized void closeLease(@NonNull McpBuilderLease lease) {
			if (this.activeMcpBuilderLease == requireNonNull(lease))
				this.activeMcpBuilderLease = null;
		}

		private synchronized void seal() {
			this.open = false;
		}

		private synchronized void requireOpen() {
			if (!this.open)
				throw new IllegalStateException(
						"The simulator configuration has been sealed");
		}
	}

	@ThreadSafe
	private static final class McpBuilderLease
			implements SimulatorMcpBuildRegistrar, AutoCloseable {
		@NonNull
		private final ConfigurationGraph configurationGraph;
		private final McpServer.@NonNull Builder builder;
		private volatile @Nullable Thread buildThread;

		private McpBuilderLease(@NonNull ConfigurationGraph configurationGraph,
				@NonNull Integer port) {
			this.configurationGraph = requireNonNull(configurationGraph);
			this.builder = McpServer.withPort(requireNonNull(port))
					.simulatorBuildRegistrar(this);
		}

		private McpServer.@NonNull Builder builder() {
			return this.builder;
		}

		private void finishConfiguration() {
			this.buildThread = Thread.currentThread();
		}

		private boolean buildAllowed() {
			return this.buildThread == Thread.currentThread();
		}

		@Override
		public void verifyBuildAllowed() {
			this.configurationGraph.verifyBuildAllowed(this);
		}

		@Override
		public void register(@NonNull DefaultMcpServer server) {
			this.configurationGraph.register(this, requireNonNull(server));
		}

		@Override
		public void close() {
			this.buildThread = null;
			this.configurationGraph.closeLease(this);
		}
	}
}
