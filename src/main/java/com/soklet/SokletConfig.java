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

import com.soklet.converter.ValueConverterRegistry;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * Defines how a Soklet system is configured.
 * <p>
 * Threadsafe instances can be acquired via one of the builder factory methods such as {@link #withHttpServer(HttpServer)},
 * {@link #withSseServer(SseServer)}, or {@link #withMcpServer(McpServer)}.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class SokletConfig {
	@NonNull
	private final InstanceProvider instanceProvider;
	@NonNull
	private final ValueConverterRegistry valueConverterRegistry;
	@NonNull
	private final RequestBodyMarshaler requestBodyMarshaler;
	@NonNull
	private final ResourceMethodResolver resourceMethodResolver;
	@NonNull
	private final ResourceMethodParameterProvider resourceMethodParameterProvider;
	@NonNull
	private final ResponseMarshaler responseMarshaler;
	@NonNull
	private final RequestInterceptor requestInterceptor;
	@NonNull
	private final List<LifecycleObserver> lifecycleObservers;
	@NonNull
	private final LifecycleObserver aggregateLifecycleObserver;
	@NonNull
	private final MetricsCollector metricsCollector;
	@NonNull
	private final CorsAuthorizer corsAuthorizer;
	@Nullable
	private final HttpServer httpServer;
	@Nullable
	private final SseServer sseServer;
	@Nullable
	private final McpServer mcpServer;

	/**
	 * Vends a configuration builder, primed with the given HTTP {@link HttpServer}.
	 *
	 * @param httpServer the HTTP server necessary for construction
	 * @return a builder for {@link SokletConfig} instances
	 */
	@NonNull
	public static Builder withHttpServer(@NonNull HttpServer httpServer) {
		requireNonNull(httpServer);
		return new Builder().httpServer(httpServer);
	}

	/**
	 * Vends a configuration builder, primed with the given {@link SseServer}.
	 *
	 * @param sseServer the SSE server necessary for construction
	 * @return a builder for {@link SokletConfig} instances
	 */
	@NonNull
	public static Builder withSseServer(@NonNull SseServer sseServer) {
		requireNonNull(sseServer);
		return new Builder().sseServer(sseServer);
	}

	/**
	 * Vends a configuration builder, primed with the given {@link McpServer}.
	 *
	 * @param mcpServer the MCP server necessary for construction
	 * @return a builder for {@link SokletConfig} instances
	 */
	@NonNull
	public static Builder withMcpServer(@NonNull McpServer mcpServer) {
		requireNonNull(mcpServer);
		return new Builder().mcpServer(mcpServer);
	}

	/**
	 * Package-private - used for internal Soklet tests.
	 */
	@NonNull
	static Builder forSimulatorTesting() {
		return SokletConfig.withHttpServer(HttpServer.withPort(0).build()).sseServer(SseServer.withPort(0).build());
	}

	protected SokletConfig(@NonNull Builder builder) {
		requireNonNull(builder);

		// Wrap servers in proxies transparently
		HttpServerProxy httpServerProxy = builder.httpServer == null ? null : new HttpServerProxy(builder.httpServer);
		SseServerProxy sseServerProxy = builder.sseServer == null ? null : new SseServerProxy(builder.sseServer);
		McpServerProxy mcpServerProxy = builder.mcpServer == null ? null : new McpServerProxy(builder.mcpServer);

		this.httpServer = httpServerProxy;
		this.sseServer = sseServerProxy;
		this.mcpServer = mcpServerProxy;
		this.instanceProvider = builder.instanceProvider != null ? builder.instanceProvider : InstanceProvider.defaultInstance();
		this.valueConverterRegistry = builder.valueConverterRegistry != null ? builder.valueConverterRegistry : ValueConverterRegistry.fromDefaults();
		this.requestBodyMarshaler = builder.requestBodyMarshaler != null ? builder.requestBodyMarshaler : RequestBodyMarshaler.fromValueConverterRegistry(getValueConverterRegistry());
		this.resourceMethodResolver = builder.resourceMethodResolver != null ? builder.resourceMethodResolver : ResourceMethodResolver.fromClasspathIntrospection();
		this.responseMarshaler = builder.responseMarshaler != null ? builder.responseMarshaler : ResponseMarshaler.defaultInstance();
		this.requestInterceptor = builder.requestInterceptor != null ? builder.requestInterceptor : RequestInterceptor.defaultInstance();
		this.lifecycleObservers = builder.lifecycleObservers != null ? builder.lifecycleObservers : List.of(LifecycleObserver.defaultInstance());
		this.aggregateLifecycleObserver = LifecycleObservers.aggregate(this.lifecycleObservers);
		this.metricsCollector = builder.metricsCollector != null ? builder.metricsCollector : MetricsCollector.defaultInstance();
		this.corsAuthorizer = builder.corsAuthorizer != null ? builder.corsAuthorizer : CorsAuthorizer.rejectAllInstance();
		this.resourceMethodParameterProvider = builder.resourceMethodParameterProvider != null ? builder.resourceMethodParameterProvider : new DefaultResourceMethodParameterProvider(this);
	}

	/**
	 * Vends a mutable copy of this instance's configuration, suitable for building new instances.
	 *
	 * @return a mutable copy of this instance's configuration
	 */
	@NonNull
	public Copier copy() {
		return new Copier(this);
	}

	/**
	 * How Soklet will perform <a href="https://www.soklet.com/docs/instance-creation">instance creation</a>.
	 *
	 * @return the instance responsible for instance creation
	 */
	@NonNull
	public InstanceProvider getInstanceProvider() {
		return this.instanceProvider;
	}

	/**
	 * How Soklet will perform <a href="https://www.soklet.com/docs/value-conversions">conversions from one Java type to another</a>, like a {@link String} to a {@link java.time.LocalDate}.
	 *
	 * @return the instance responsible for value conversions
	 */
	@NonNull
	public ValueConverterRegistry getValueConverterRegistry() {
		return this.valueConverterRegistry;
	}

	/**
	 * How Soklet will <a href="https://www.soklet.com/docs/request-handling#request-body">marshal request bodies to Java types</a>.
	 *
	 * @return the instance responsible for request body marshaling
	 */
	@NonNull
	public RequestBodyMarshaler getRequestBodyMarshaler() {
		return this.requestBodyMarshaler;
	}

	/**
	 * How Soklet performs <a href="https://www.soklet.com/docs/request-handling#resource-method-resolution"><em>Resource Method</em> resolution</a> (experts only!)
	 *
	 * @return the instance responsible for <em>Resource Method</em> resolution
	 */
	@NonNull
	public ResourceMethodResolver getResourceMethodResolver() {
		return this.resourceMethodResolver;
	}

	/**
	 * How Soklet performs <a href="https://www.soklet.com/docs/request-handling#resource-method-parameter-injection"><em>Resource Method</em> parameter injection</a> (experts only!)
	 *
	 * @return the instance responsible for <em>Resource Method</em> parameter injection
	 */
	@NonNull
	public ResourceMethodParameterProvider getResourceMethodParameterProvider() {
		return this.resourceMethodParameterProvider;
	}

	/**
	 * How Soklet will <a href="https://www.soklet.com/docs/response-writing">marshal response bodies to bytes suitable for transmission over the wire</a>.
	 *
	 * @return the instance responsible for response body marshaling
	 */
	@NonNull
	public ResponseMarshaler getResponseMarshaler() {
		return this.responseMarshaler;
	}

	/**
	 * How Soklet will <a href="https://www.soklet.com/docs/request-lifecycle">perform custom behavior during request handling</a>.
	 *
	 * @return the instance responsible for request interceptor behavior
	 */
	@NonNull
	public RequestInterceptor getRequestInterceptor() {
		return this.requestInterceptor;
	}

	@NonNull
	LifecycleObserver getAggregateLifecycleObserver() {
		return this.aggregateLifecycleObserver;
	}

	/**
	 * How Soklet will <a href="https://www.soklet.com/docs/request-lifecycle">observe server and request lifecycle events</a>.
	 *
	 * @return the lifecycle observers that are invoked in registration order
	 */
	@NonNull
	public List<LifecycleObserver> getLifecycleObservers() {
		return this.lifecycleObservers;
	}

	/**
	 * How Soklet will collect operational metrics.
	 *
	 * @return the instance responsible for metrics collection
	 */
	@NonNull
	public MetricsCollector getMetricsCollector() {
		return this.metricsCollector;
	}

	/**
	 * How Soklet handles <a href="https://www.soklet.com/docs/cors">Cross-Origin Resource Sharing (CORS)</a>.
	 *
	 * @return the instance responsible for CORS-related processing
	 */
	@NonNull
	public CorsAuthorizer getCorsAuthorizer() {
		return this.corsAuthorizer;
	}

	/**
	 * The HTTP server managed by Soklet, if configured.
	 *
	 * @return the HTTP server, if configured
	 */
	@NonNull
	public Optional<HttpServer> getHttpServer() {
		return Optional.ofNullable(this.httpServer);
	}

	/**
	 * The SSE server managed by Soklet, if configured.
	 *
	 * @return the SSE server instance, or {@link Optional#empty()} if none was configured
	 */
	@NonNull
	public Optional<SseServer> getSseServer() {
		return Optional.ofNullable(this.sseServer);
	}

	/**
	 * The MCP server managed by Soklet, if configured.
	 *
	 * @return the MCP server, if configured
	 */
	@NonNull
	public Optional<McpServer> getMcpServer() {
		return Optional.ofNullable(this.mcpServer);
	}

	/**
	 * Builder used to construct instances of {@link SokletConfig}.
	 * <p>
	 * Instances are created by invoking one of the static factory methods on {@link SokletConfig}.
	 * <p>
	 * This class is intended for use by a single thread.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static final class Builder {
		@Nullable
		private HttpServer httpServer;
		@Nullable
		private SseServer sseServer;
		@Nullable
		private McpServer mcpServer;
		@Nullable
		private InstanceProvider instanceProvider;
		@Nullable
		private ValueConverterRegistry valueConverterRegistry;
		@Nullable
		private RequestBodyMarshaler requestBodyMarshaler;
		@Nullable
		private ResourceMethodResolver resourceMethodResolver;
		@Nullable
		private ResourceMethodParameterProvider resourceMethodParameterProvider;
		@Nullable
		private ResponseMarshaler responseMarshaler;
		@Nullable
		private RequestInterceptor requestInterceptor;
		@Nullable
		private List<LifecycleObserver> lifecycleObservers;
		@Nullable
		private MetricsCollector metricsCollector;
		@Nullable
		private CorsAuthorizer corsAuthorizer;

		Builder() {
			// No-op
		}

		@NonNull
		public Builder httpServer(@Nullable HttpServer httpServer) {
			this.httpServer = httpServer;
			return this;
		}

		@NonNull
		public Builder sseServer(@Nullable SseServer sseServer) {
			this.sseServer = sseServer;
			return this;
		}

		@NonNull
		public Builder mcpServer(@Nullable McpServer mcpServer) {
			this.mcpServer = mcpServer;
			return this;
		}

		@NonNull
		public Builder instanceProvider(@Nullable InstanceProvider instanceProvider) {
			this.instanceProvider = instanceProvider;
			return this;
		}

		@NonNull
		public Builder valueConverterRegistry(@Nullable ValueConverterRegistry valueConverterRegistry) {
			this.valueConverterRegistry = valueConverterRegistry;
			return this;
		}

		@NonNull
		public Builder requestBodyMarshaler(@Nullable RequestBodyMarshaler requestBodyMarshaler) {
			this.requestBodyMarshaler = requestBodyMarshaler;
			return this;
		}

		@NonNull
		public Builder resourceMethodResolver(@Nullable ResourceMethodResolver resourceMethodResolver) {
			this.resourceMethodResolver = resourceMethodResolver;
			return this;
		}

		@NonNull
		public Builder resourceMethodParameterProvider(@Nullable ResourceMethodParameterProvider resourceMethodParameterProvider) {
			this.resourceMethodParameterProvider = resourceMethodParameterProvider;
			return this;
		}

		@NonNull
		public Builder responseMarshaler(@Nullable ResponseMarshaler responseMarshaler) {
			this.responseMarshaler = responseMarshaler;
			return this;
		}

		@NonNull
		public Builder requestInterceptor(@Nullable RequestInterceptor requestInterceptor) {
			this.requestInterceptor = requestInterceptor;
			return this;
		}

		@NonNull
		public Builder lifecycleObserver(@Nullable LifecycleObserver lifecycleObserver) {
			this.lifecycleObservers = lifecycleObserver == null ? List.of() : List.of(lifecycleObserver);
			return this;
		}

		@NonNull
		public Builder lifecycleObservers(@Nullable Collection<? extends LifecycleObserver> lifecycleObservers) {
			this.lifecycleObservers = copyLifecycleObservers(lifecycleObservers);
			return this;
		}

		@NonNull
		public Builder metricsCollector(@Nullable MetricsCollector metricsCollector) {
			this.metricsCollector = metricsCollector;
			return this;
		}

		@NonNull
		public Builder corsAuthorizer(@Nullable CorsAuthorizer corsAuthorizer) {
			this.corsAuthorizer = corsAuthorizer;
			return this;
		}

		@NonNull
		public SokletConfig build() {
			if (this.httpServer == null && this.sseServer == null && this.mcpServer == null)
				throw new IllegalStateException(format("At least one of %s, %s, or %s must be configured",
						HttpServer.class.getSimpleName(), SseServer.class.getSimpleName(), McpServer.class.getSimpleName()));

			return new SokletConfig(this);
		}
	}

	/**
	 * Builder used to copy instances of {@link SokletConfig}.
	 * <p>
	 * Instances are created by invoking {@link SokletConfig#copy()}.
	 * <p>
	 * This class is intended for use by a single thread.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static final class Copier {
		@NonNull
		private final Builder builder;

		/**
		 * Unwraps a HttpServer proxy to get the underlying real implementation.
		 */
		@NonNull
		private static HttpServer unwrapHttpServer(@NonNull HttpServer httpServer) {
			requireNonNull(httpServer);

			if (httpServer instanceof HttpServerProxy)
				return ((HttpServerProxy) httpServer).getRealImplementation();

			return httpServer;
		}

		/**
		 * Unwraps a SseServer proxy to get the underlying real implementation.
		 */
		@NonNull
		private static SseServer unwrapSseServer(@NonNull SseServer sseServer) {
			requireNonNull(sseServer);

			if (sseServer instanceof SseServerProxy)
				return ((SseServerProxy) sseServer).getRealImplementation();

			return sseServer;
		}

		/**
		 * Unwraps an McpServer proxy to get the underlying real implementation.
		 */
		@NonNull
		private static McpServer unwrapMcpServer(@NonNull McpServer mcpServer) {
			requireNonNull(mcpServer);

			if (mcpServer instanceof McpServerProxy)
				return ((McpServerProxy) mcpServer).getRealImplementation();

			return mcpServer;
		}

		Copier(@NonNull SokletConfig sokletConfig) {
			requireNonNull(sokletConfig);

			// Unwrap proxies to get the real implementations for copying
			HttpServer realHttpServer = sokletConfig.getHttpServer()
					.map(Copier::unwrapHttpServer)
					.orElse(null);
			SseServer realSseServer = sokletConfig.getSseServer()
					.map(Copier::unwrapSseServer)
					.orElse(null);
			McpServer realMcpServer = sokletConfig.getMcpServer()
					.map(Copier::unwrapMcpServer)
					.orElse(null);

			this.builder = new Builder()
					.httpServer(realHttpServer)
					.sseServer(realSseServer)
					.mcpServer(realMcpServer)
					.instanceProvider(sokletConfig.getInstanceProvider())
					.valueConverterRegistry(sokletConfig.valueConverterRegistry)
					.requestBodyMarshaler(sokletConfig.requestBodyMarshaler)
					.resourceMethodResolver(sokletConfig.resourceMethodResolver)
					.resourceMethodParameterProvider(sokletConfig.resourceMethodParameterProvider)
					.responseMarshaler(sokletConfig.responseMarshaler)
					.requestInterceptor(sokletConfig.requestInterceptor)
					.lifecycleObservers(sokletConfig.lifecycleObservers)
					.metricsCollector(sokletConfig.metricsCollector)
					.corsAuthorizer(sokletConfig.corsAuthorizer);
		}

		@NonNull
		public Copier httpServer(@Nullable HttpServer httpServer) {
			this.builder.httpServer(httpServer);
			return this;
		}

		@NonNull
		public Copier sseServer(@Nullable SseServer sseServer) {
			this.builder.sseServer(sseServer);
			return this;
		}

		@NonNull
		public Copier mcpServer(@Nullable McpServer mcpServer) {
			this.builder.mcpServer(mcpServer);
			return this;
		}

		@NonNull
		public Copier instanceProvider(@Nullable InstanceProvider instanceProvider) {
			this.builder.instanceProvider(instanceProvider);
			return this;
		}

		@NonNull
		public Copier valueConverterRegistry(@Nullable ValueConverterRegistry valueConverterRegistry) {
			this.builder.valueConverterRegistry(valueConverterRegistry);
			return this;
		}

		@NonNull
		public Copier requestBodyMarshaler(@Nullable RequestBodyMarshaler requestBodyMarshaler) {
			this.builder.requestBodyMarshaler(requestBodyMarshaler);
			return this;
		}

		@NonNull
		public Copier resourceMethodResolver(@Nullable ResourceMethodResolver resourceMethodResolver) {
			this.builder.resourceMethodResolver(resourceMethodResolver);
			return this;
		}

		@NonNull
		public Copier resourceMethodParameterProvider(@Nullable ResourceMethodParameterProvider resourceMethodParameterProvider) {
			this.builder.resourceMethodParameterProvider(resourceMethodParameterProvider);
			return this;
		}

		@NonNull
		public Copier responseMarshaler(@Nullable ResponseMarshaler responseMarshaler) {
			this.builder.responseMarshaler(responseMarshaler);
			return this;
		}

		@NonNull
		public Copier requestInterceptor(@Nullable RequestInterceptor requestInterceptor) {
			this.builder.requestInterceptor(requestInterceptor);
			return this;
		}

		@NonNull
		public Copier lifecycleObserver(@Nullable LifecycleObserver lifecycleObserver) {
			this.builder.lifecycleObserver(lifecycleObserver);
			return this;
		}

		@NonNull
		public Copier lifecycleObservers(@Nullable Collection<? extends LifecycleObserver> lifecycleObservers) {
			this.builder.lifecycleObservers(lifecycleObservers);
			return this;
		}

		@NonNull
		public Copier metricsCollector(@Nullable MetricsCollector metricsCollector) {
			this.builder.metricsCollector(metricsCollector);
			return this;
		}

		@NonNull
		public Copier corsAuthorizer(@Nullable CorsAuthorizer corsAuthorizer) {
			this.builder.corsAuthorizer(corsAuthorizer);
			return this;
		}

		@NonNull
		public SokletConfig finish() {
			return this.builder.build();
		}
	}

	@NonNull
	private static List<LifecycleObserver> copyLifecycleObservers(@Nullable Collection<? extends LifecycleObserver> lifecycleObservers) {
		if (lifecycleObservers == null || lifecycleObservers.isEmpty())
			return List.of();

		return List.copyOf(lifecycleObservers);
	}
}
