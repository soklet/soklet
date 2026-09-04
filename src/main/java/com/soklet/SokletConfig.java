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
	@NonNull
	private final LifecyclePolicy lifecyclePolicy;

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

	SokletConfig(@NonNull Builder builder) {
		requireNonNull(builder);

		this.httpServer = builder.httpServer;
		this.sseServer = builder.sseServer;
		this.mcpServer = builder.mcpServer;
		this.lifecyclePolicy = builder.lifecyclePolicy != null
				? builder.lifecyclePolicy : LifecyclePolicy.fromDefaults();
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
	public List<@NonNull LifecycleObserver> getLifecycleObservers() {
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
	public Optional<@NonNull HttpServer> getHttpServer() {
		return Optional.ofNullable(this.httpServer);
	}

	/**
	 * The SSE server managed by Soklet, if configured.
	 *
	 * @return the SSE server instance, or {@link Optional#empty()} if none was configured
	 */
	@NonNull
	public Optional<@NonNull SseServer> getSseServer() {
		return Optional.ofNullable(this.sseServer);
	}

	/**
	 * The MCP server managed by Soklet, if configured.
	 *
	 * @return the MCP server instance, or {@link Optional#empty()} if none was configured
	 */
	@NonNull
	public Optional<@NonNull McpServer> getMcpServer() {
		return Optional.ofNullable(this.mcpServer);
	}

	/**
	 * The startup and shutdown deadline policy shared by every configured
	 * lifecycle component.
	 *
	 * @return the configured lifecycle policy
	 */
	@NonNull
	public LifecyclePolicy getLifecyclePolicy() {
		return this.lifecyclePolicy;
	}

	@NonNull
	InternalLifecyclePolicy getInternalLifecyclePolicy() {
		return this.lifecyclePolicy.toInternal();
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
		private LifecyclePolicy lifecyclePolicy;
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

		/**
		 * Sets the HTTP server managed by Soklet.
		 *
		 * @param httpServer the HTTP server, or {@code null} to remove it
		 * @return this builder
		 */
		@NonNull
		public Builder httpServer(@Nullable HttpServer httpServer) {
			this.httpServer = httpServer;
			return this;
		}

		/**
		 * Sets the SSE server managed by Soklet.
		 *
		 * @param sseServer the SSE server, or {@code null} to remove it
		 * @return this builder
		 */
		@NonNull
		public Builder sseServer(@Nullable SseServer sseServer) {
			this.sseServer = sseServer;
			return this;
		}

		/**
		 * Sets the MCP server managed by Soklet.
		 *
		 * @param mcpServer the MCP server, or {@code null} to remove it
		 * @return this builder
		 */
		@NonNull
		public Builder mcpServer(@Nullable McpServer mcpServer) {
			this.mcpServer = mcpServer;
			return this;
		}

		/**
		 * Sets the startup and shutdown deadline policy shared by every
		 * configured lifecycle component.
		 *
		 * @param lifecyclePolicy the lifecycle policy, or {@code null} to use the
		 * default
		 * @return this builder
		 */
		@NonNull
		public Builder lifecyclePolicy(
				@Nullable LifecyclePolicy lifecyclePolicy) {
			this.lifecyclePolicy = lifecyclePolicy;
			return this;
		}

		@NonNull
		Builder internalLifecyclePolicy(
				@NonNull InternalLifecyclePolicy internalLifecyclePolicy) {
			this.lifecyclePolicy = LifecyclePolicy.fromInternal(
					requireNonNull(internalLifecyclePolicy));
			return this;
		}

		/**
		 * Sets how Soklet creates application instances for HTTP, SSE, and MCP
		 * handlers and application parameter values. The provider may be called
		 * concurrently.
		 *
		 * @param instanceProvider the instance provider, or {@code null} to use the default
		 * @return this builder
		 */
		@NonNull
		public Builder instanceProvider(@Nullable InstanceProvider instanceProvider) {
			this.instanceProvider = instanceProvider;
			return this;
		}

		/**
		 * Sets the registry used for Java value conversions.
		 *
		 * @param valueConverterRegistry the conversion registry, or {@code null} to use the default registry
		 * @return this builder
		 */
		@NonNull
		public Builder valueConverterRegistry(@Nullable ValueConverterRegistry valueConverterRegistry) {
			this.valueConverterRegistry = valueConverterRegistry;
			return this;
		}

		/**
		 * Sets how request bodies are converted to application values.
		 *
		 * @param requestBodyMarshaler the request-body marshaler, or {@code null} to derive one from the configured value-converter registry
		 * @return this builder
		 */
		@NonNull
		public Builder requestBodyMarshaler(@Nullable RequestBodyMarshaler requestBodyMarshaler) {
			this.requestBodyMarshaler = requestBodyMarshaler;
			return this;
		}

		/**
		 * Sets how Soklet discovers and resolves <em>Resource Methods</em>.
		 *
		 * @param resourceMethodResolver the resource-method resolver, or {@code null} to use classpath introspection
		 * @return this builder
		 */
		@NonNull
		public Builder resourceMethodResolver(@Nullable ResourceMethodResolver resourceMethodResolver) {
			this.resourceMethodResolver = resourceMethodResolver;
			return this;
		}

		/**
		 * Sets how parameters are supplied when Soklet invokes <em>Resource Methods</em>.
		 *
		 * @param resourceMethodParameterProvider the parameter provider, or {@code null} to use Soklet's default provider
		 * @return this builder
		 */
		@NonNull
		public Builder resourceMethodParameterProvider(@Nullable ResourceMethodParameterProvider resourceMethodParameterProvider) {
			this.resourceMethodParameterProvider = resourceMethodParameterProvider;
			return this;
		}

		/**
		 * Sets how application response values are converted for transmission.
		 *
		 * @param responseMarshaler the response marshaler, or {@code null} to use the default
		 * @return this builder
		 */
		@NonNull
		public Builder responseMarshaler(@Nullable ResponseMarshaler responseMarshaler) {
			this.responseMarshaler = responseMarshaler;
			return this;
		}

		/**
		 * Sets the interceptor that wraps application request handling.
		 *
		 * @param requestInterceptor the request interceptor, or {@code null} to use the default
		 * @return this builder
		 */
		@NonNull
		public Builder requestInterceptor(@Nullable RequestInterceptor requestInterceptor) {
			this.requestInterceptor = requestInterceptor;
			return this;
		}

		/**
		 * Replaces the configured lifecycle observers with one observer.
		 *
		 * @param lifecycleObserver the sole lifecycle observer, or {@code null} to configure no observers
		 * @return this builder
		 */
		@NonNull
		public Builder lifecycleObserver(@Nullable LifecycleObserver lifecycleObserver) {
			this.lifecycleObservers = lifecycleObserver == null ? List.of() : List.of(lifecycleObserver);
			return this;
		}

		/**
		 * Replaces the configured lifecycle observers, preserving iteration order.
		 *
		 * @param lifecycleObservers the lifecycle observers, or {@code null} to configure no observers
		 * @return this builder
		 */
		@NonNull
		public Builder lifecycleObservers(
				@Nullable Collection<? extends @NonNull LifecycleObserver>
						lifecycleObservers) {
			this.lifecycleObservers = copyLifecycleObservers(lifecycleObservers);
			return this;
		}

		/**
		 * Sets how Soklet records operational metrics.
		 *
		 * @param metricsCollector the metrics collector, or {@code null} to use the default
		 * @return this builder
		 */
		@NonNull
		public Builder metricsCollector(@Nullable MetricsCollector metricsCollector) {
			this.metricsCollector = metricsCollector;
			return this;
		}

		/**
		 * Sets the authorizer used for ordinary HTTP and SSE CORS processing.
		 * <p>
		 * MCP servers retain their independently configured {@link McpServer#getCorsAuthorizer() CORS authorizer}.
		 *
		 * @param corsAuthorizer the CORS authorizer, or {@code null} to reject all cross-origin requests by default
		 * @return this builder
		 */
		@NonNull
		public Builder corsAuthorizer(@Nullable CorsAuthorizer corsAuthorizer) {
			this.corsAuthorizer = corsAuthorizer;
			return this;
		}

		/**
		 * Builds an immutable Soklet configuration.
		 *
		 * @return the completed configuration
		 * @throws IllegalStateException if no HTTP, SSE, or MCP server is configured
		 */
		@NonNull
		public SokletConfig build() {
			if (this.httpServer == null && this.sseServer == null && this.mcpServer == null)
				throw new IllegalStateException(format("At least one of %s, %s, or %s must be configured",
						HttpServer.class.getSimpleName(), SseServer.class.getSimpleName(), McpServer.class.getSimpleName()));

			return new SokletConfig(this);
		}
	}

	@NonNull
	private static List<LifecycleObserver> copyLifecycleObservers(@Nullable Collection<? extends LifecycleObserver> lifecycleObservers) {
		if (lifecycleObservers == null || lifecycleObservers.isEmpty())
			return List.of();

		return List.copyOf(lifecycleObservers);
	}
}
