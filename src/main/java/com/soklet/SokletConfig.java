/*
 * Copyright 2022-2025 Revetware LLC.
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
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Defines how a Soklet system is configured.
 * <p>
 * Threadsafe instances can be acquired via the {@link #withServer(Server)} builder factory method.
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
	private final LifecycleObserver lifecycleObserver;
	@NonNull
	private final CorsAuthorizer corsAuthorizer;
	@NonNull
	private final Server server;
	@Nullable
	private final ServerSentEventServer serverSentEventServer;

	/**
	 * Vends a configuration builder, primed with the given {@link Server}.
	 *
	 * @param server the server necessary for construction
	 * @return a builder for {@link SokletConfig} instances
	 */
	@NonNull
	public static Builder withServer(@NonNull Server server) {
		requireNonNull(server);
		return new Builder(server);
	}

	/**
	 * Package-private - used for internal Soklet tests.
	 */
	@NonNull
	static Builder forSimulatorTesting() {
		return SokletConfig.withServer(Server.withPort(0).build()).serverSentEventServer(ServerSentEventServer.withPort(0).build());
	}

	protected SokletConfig(@NonNull Builder builder) {
		requireNonNull(builder);

		// Wrap servers in proxies transparently
		ServerProxy serverProxy = new ServerProxy(builder.server);
		ServerSentEventServerProxy serverSentEventServerProxy = builder.serverSentEventServer == null ? null : new ServerSentEventServerProxy(builder.serverSentEventServer);

		this.server = serverProxy;
		this.serverSentEventServer = serverSentEventServerProxy;
		this.instanceProvider = builder.instanceProvider != null ? builder.instanceProvider : InstanceProvider.defaultInstance();
		this.valueConverterRegistry = builder.valueConverterRegistry != null ? builder.valueConverterRegistry : ValueConverterRegistry.withDefaults();
		this.requestBodyMarshaler = builder.requestBodyMarshaler != null ? builder.requestBodyMarshaler : RequestBodyMarshaler.withValueConverterRegistry(getValueConverterRegistry());
		this.resourceMethodResolver = builder.resourceMethodResolver != null ? builder.resourceMethodResolver : ResourceMethodResolver.fromClasspathIntrospection();
		this.responseMarshaler = builder.responseMarshaler != null ? builder.responseMarshaler : ResponseMarshaler.defaultInstance();
		this.requestInterceptor = builder.requestInterceptor != null ? builder.requestInterceptor : RequestInterceptor.defaultInstance();
		this.lifecycleObserver = builder.lifecycleObserver != null ? builder.lifecycleObserver : LifecycleObserver.defaultInstance();
		this.corsAuthorizer = builder.corsAuthorizer != null ? builder.corsAuthorizer : CorsAuthorizer.withRejectAllPolicy();
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

	/**
	 * How Soklet will <a href="https://www.soklet.com/docs/request-lifecycle">observe server and request lifecycle events</a>.
	 *
	 * @return the instance responsible for lifecycle observation
	 */
	@NonNull
	public LifecycleObserver getLifecycleObserver() {
		return this.lifecycleObserver;
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
	 * The server managed by Soklet.
	 *
	 * @return the server instance
	 */
	@NonNull
	public Server getServer() {
		return this.server;
	}

	/**
	 * The SSE server managed by Soklet, if configured.
	 *
	 * @return the SSE server instance, or {@link Optional#empty()} is none was configured
	 */
	@NonNull
	public Optional<ServerSentEventServer> getServerSentEventServer() {
		return Optional.ofNullable(this.serverSentEventServer);
	}

	/**
	 * Builder used to construct instances of {@link SokletConfig}.
	 * <p>
	 * Instances are created by invoking {@link SokletConfig#withServer(Server)}.
	 * <p>
	 * This class is intended for use by a single thread.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static final class Builder {
		@NonNull
		private Server server;
		@Nullable
		private ServerSentEventServer serverSentEventServer;
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
		private LifecycleObserver lifecycleObserver;
		@Nullable
		private CorsAuthorizer corsAuthorizer;

		@NonNull
		Builder(@NonNull Server server) {
			requireNonNull(server);
			this.server = server;
		}

		@NonNull
		public Builder server(@NonNull Server server) {
			requireNonNull(server);
			this.server = server;
			return this;
		}

		@NonNull
		public Builder serverSentEventServer(@Nullable ServerSentEventServer serverSentEventServer) {
			this.serverSentEventServer = serverSentEventServer;
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
			this.lifecycleObserver = lifecycleObserver;
			return this;
		}

		@NonNull
		public Builder corsAuthorizer(@Nullable CorsAuthorizer corsAuthorizer) {
			this.corsAuthorizer = corsAuthorizer;
			return this;
		}

		@NonNull
		public SokletConfig build() {
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
		 * Unwraps a Server proxy to get the underlying real implementation.
		 */
		@NonNull
		private static Server unwrapServer(@NonNull Server server) {
			requireNonNull(server);

			if (server instanceof ServerProxy)
				return ((ServerProxy) server).getRealImplementation();

			return server;
		}

		/**
		 * Unwraps a ServerSentEventServer proxy to get the underlying real implementation.
		 */
		@NonNull
		private static ServerSentEventServer unwrapServerSentEventServer(@NonNull ServerSentEventServer serverSentEventServer) {
			requireNonNull(serverSentEventServer);

			if (serverSentEventServer instanceof ServerSentEventServerProxy)
				return ((ServerSentEventServerProxy) serverSentEventServer).getRealImplementation();

			return serverSentEventServer;
		}

		Copier(@NonNull SokletConfig sokletConfig) {
			requireNonNull(sokletConfig);

			// Unwrap proxies to get the real implementations for copying
			Server realServer = unwrapServer(sokletConfig.getServer());
			ServerSentEventServer realServerSentEventServer = sokletConfig.getServerSentEventServer()
					.map(Copier::unwrapServerSentEventServer)
					.orElse(null);

			this.builder = new Builder(realServer)
					.serverSentEventServer(realServerSentEventServer)
					.instanceProvider(sokletConfig.getInstanceProvider())
					.valueConverterRegistry(sokletConfig.valueConverterRegistry)
					.requestBodyMarshaler(sokletConfig.requestBodyMarshaler)
					.resourceMethodResolver(sokletConfig.resourceMethodResolver)
					.resourceMethodParameterProvider(sokletConfig.resourceMethodParameterProvider)
					.responseMarshaler(sokletConfig.responseMarshaler)
					.requestInterceptor(sokletConfig.requestInterceptor)
					.lifecycleObserver(sokletConfig.lifecycleObserver)
					.corsAuthorizer(sokletConfig.corsAuthorizer);
		}

		@NonNull
		public Copier server(@NonNull Server server) {
			requireNonNull(server);
			this.builder.server(server);
			return this;
		}

		@NonNull
		public Copier serverSentEventServer(@Nullable ServerSentEventServer serverSentEventServer) {
			this.builder.serverSentEventServer(serverSentEventServer);
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
		public Copier corsAuthorizer(@Nullable CorsAuthorizer corsAuthorizer) {
			this.builder.corsAuthorizer(corsAuthorizer);
			return this;
		}

		@NonNull
		public SokletConfig finish() {
			return this.builder.build();
		}
	}
}
