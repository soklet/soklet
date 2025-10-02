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

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Defines how a Soklet system is configured.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class SokletConfig {
	@Nonnull
	private final InstanceProvider instanceProvider;
	@Nonnull
	private final ValueConverterRegistry valueConverterRegistry;
	@Nonnull
	private final RequestBodyMarshaler requestBodyMarshaler;
	@Nonnull
	private final ResourceMethodResolver resourceMethodResolver;
	@Nonnull
	private final ResourceMethodParameterProvider resourceMethodParameterProvider;
	@Nonnull
	private final ResponseMarshaler responseMarshaler;
	@Nonnull
	private final LifecycleInterceptor lifecycleInterceptor;
	@Nonnull
	private final CorsAuthorizer corsAuthorizer;
	@Nonnull
	private final Server server;
	@Nullable
	private final ServerSentEventServer serverSentEventServer;

	/**
	 * Vends a configuration builder for the given server.
	 *
	 * @param server the server necessary for construction
	 * @return a builder for {@link SokletConfig} instances
	 */
	@Nonnull
	public static Builder withServer(@Nonnull Server server) {
		requireNonNull(server);
		return new Builder(server);
	}

	/**
	 * Vends a configuration builder with mock servers, suitable for unit and integration testing.
	 *
	 * @return a builder for {@link SokletConfig} instances
	 */
	@Nonnull
	public static Builder forTesting() {
		return new Builder(new Soklet.MockServer()).serverSentEventServer(new Soklet.MockServerSentEventServer());
	}

	protected SokletConfig(@Nonnull Builder builder) {
		requireNonNull(builder);

		this.server = builder.server;
		this.serverSentEventServer = builder.serverSentEventServer;
		this.instanceProvider = builder.instanceProvider != null ? builder.instanceProvider : InstanceProvider.defaultInstance();
		this.valueConverterRegistry = builder.valueConverterRegistry != null ? builder.valueConverterRegistry : ValueConverterRegistry.withDefaults();
		this.requestBodyMarshaler = builder.requestBodyMarshaler != null ? builder.requestBodyMarshaler : RequestBodyMarshaler.withValueConverterRegistry(getValueConverterRegistry());
		this.resourceMethodResolver = builder.resourceMethodResolver != null ? builder.resourceMethodResolver : ResourceMethodResolver.withClasspathIntrospection();
		this.resourceMethodParameterProvider = builder.resourceMethodParameterProvider != null ? builder.resourceMethodParameterProvider : ResourceMethodParameterProvider.with(getInstanceProvider(), getValueConverterRegistry(), getRequestBodyMarshaler());
		this.responseMarshaler = builder.responseMarshaler != null ? builder.responseMarshaler : ResponseMarshaler.withDefaults();
		this.lifecycleInterceptor = builder.lifecycleInterceptor != null ? builder.lifecycleInterceptor : LifecycleInterceptor.withDefaults();
		this.corsAuthorizer = builder.corsAuthorizer != null ? builder.corsAuthorizer : CorsAuthorizer.withRejectAllPolicy();
	}

	/**
	 * Vends a mutable copy of this instance's configuration, suitable for building new instances.
	 *
	 * @return a mutable copy of this instance's configuration
	 */
	@Nonnull
	public Copier copy() {
		return new Copier(this);
	}

	/**
	 * How Soklet will perform <a href="https://www.soklet.com/docs/instance-creation">instance creation</a>.
	 *
	 * @return the instance responsible for instance creation
	 */
	@Nonnull
	public InstanceProvider getInstanceProvider() {
		return this.instanceProvider;
	}

	/**
	 * How Soklet will perform <a href="https://www.soklet.com/docs/value-conversions">conversions from one Java type to another</a>, like a {@link String} to a {@link java.time.LocalDate}.
	 *
	 * @return the instance responsible for value conversions
	 */
	@Nonnull
	public ValueConverterRegistry getValueConverterRegistry() {
		return this.valueConverterRegistry;
	}

	/**
	 * How Soklet will <a href="https://www.soklet.com/docs/request-handling#request-body">marshal request bodies to Java types</a>.
	 *
	 * @return the instance responsible for request body marshaling
	 */
	@Nonnull
	public RequestBodyMarshaler getRequestBodyMarshaler() {
		return this.requestBodyMarshaler;
	}

	/**
	 * How Soklet performs <a href="https://www.soklet.com/docs/request-handling#resource-method-resolution"><em>Resource Method</em> resolution</a> (experts only!)
	 *
	 * @return the instance responsible for <em>Resource Method</em> resolution
	 */
	@Nonnull
	public ResourceMethodResolver getResourceMethodResolver() {
		return this.resourceMethodResolver;
	}

	/**
	 * How Soklet performs <a href="https://www.soklet.com/docs/request-handling#resource-method-parameter-injection"><em>Resource Method</em> parameter injection</a> (experts only!)
	 *
	 * @return the instance responsible for <em>Resource Method</em> parameter injection
	 */
	@Nonnull
	public ResourceMethodParameterProvider getResourceMethodParameterProvider() {
		return this.resourceMethodParameterProvider;
	}

	/**
	 * How Soklet will <a href="https://www.soklet.com/docs/response-writing">marshal response bodies to bytes suitable for transmission over the wire</a>.
	 *
	 * @return the instance responsible for response body marshaling
	 */
	@Nonnull
	public ResponseMarshaler getResponseMarshaler() {
		return this.responseMarshaler;
	}

	/**
	 * How Soklet will <a href="https://www.soklet.com/docs/request-lifecycle">perform custom behavior during server and request lifecycle events</a>.
	 *
	 * @return the instance responsible for performing lifecycle event customization
	 */
	@Nonnull
	public LifecycleInterceptor getLifecycleInterceptor() {
		return this.lifecycleInterceptor;
	}

	/**
	 * How Soklet handles <a href="https://www.soklet.com/docs/cors">Cross-Origin Resource Sharing (CORS)</a>.
	 *
	 * @return the instance responsible for CORS-related processing
	 */
	@Nonnull
	public CorsAuthorizer getCorsAuthorizer() {
		return this.corsAuthorizer;
	}

	/**
	 * The server managed by Soklet.
	 *
	 * @return the server instance
	 */
	@Nonnull
	public Server getServer() {
		return this.server;
	}

	/**
	 * The SSE server managed by Soklet, if configured.
	 *
	 * @return the SSE server instance, or {@link Optional#empty()} is none was configured
	 */
	@Nonnull
	public Optional<ServerSentEventServer> getServerSentEventServer() {
		return Optional.ofNullable(this.serverSentEventServer);
	}

	/**
	 * Builder used to construct instances of {@link SokletConfig}.
	 * <p>
	 * Instances are created by invoking {@link SokletConfig#withServer(Server)} or {@link SokletConfig#forTesting()}.
	 * <p>
	 * This class is intended for use by a single thread.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static final class Builder {
		@Nonnull
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
		private LifecycleInterceptor lifecycleInterceptor;
		@Nullable
		private CorsAuthorizer corsAuthorizer;

		@Nonnull
		protected Builder(@Nonnull Server server) {
			requireNonNull(server);
			this.server = server;
		}

		@Nonnull
		public Builder server(@Nonnull Server server) {
			requireNonNull(server);
			this.server = server;
			return this;
		}

		@Nonnull
		public Builder serverSentEventServer(@Nullable ServerSentEventServer serverSentEventServer) {
			this.serverSentEventServer = serverSentEventServer;
			return this;
		}

		@Nonnull
		public Builder instanceProvider(@Nullable InstanceProvider instanceProvider) {
			this.instanceProvider = instanceProvider;
			return this;
		}

		@Nonnull
		public Builder valueConverterRegistry(@Nullable ValueConverterRegistry valueConverterRegistry) {
			this.valueConverterRegistry = valueConverterRegistry;
			return this;
		}

		@Nonnull
		public Builder requestBodyMarshaler(@Nullable RequestBodyMarshaler requestBodyMarshaler) {
			this.requestBodyMarshaler = requestBodyMarshaler;
			return this;
		}

		@Nonnull
		public Builder resourceMethodResolver(@Nullable ResourceMethodResolver resourceMethodResolver) {
			this.resourceMethodResolver = resourceMethodResolver;
			return this;
		}

		@Nonnull
		public Builder resourceMethodParameterProvider(@Nullable ResourceMethodParameterProvider resourceMethodParameterProvider) {
			this.resourceMethodParameterProvider = resourceMethodParameterProvider;
			return this;
		}

		@Nonnull
		public Builder responseMarshaler(@Nullable ResponseMarshaler responseMarshaler) {
			this.responseMarshaler = responseMarshaler;
			return this;
		}

		@Nonnull
		public Builder lifecycleInterceptor(@Nullable LifecycleInterceptor lifecycleInterceptor) {
			this.lifecycleInterceptor = lifecycleInterceptor;
			return this;
		}

		@Nonnull
		public Builder corsAuthorizer(@Nullable CorsAuthorizer corsAuthorizer) {
			this.corsAuthorizer = corsAuthorizer;
			return this;
		}

		@Nonnull
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
	public static class Copier {
		@Nonnull
		private final Builder builder;

		Copier(@Nonnull SokletConfig sokletConfig) {
			requireNonNull(sokletConfig);

			this.builder = new Builder(sokletConfig.getServer())
					.serverSentEventServer(sokletConfig.getServerSentEventServer().orElse(null))
					.instanceProvider(sokletConfig.getInstanceProvider())
					.valueConverterRegistry(sokletConfig.valueConverterRegistry)
					.requestBodyMarshaler(sokletConfig.requestBodyMarshaler)
					.resourceMethodResolver(sokletConfig.resourceMethodResolver)
					.resourceMethodParameterProvider(sokletConfig.resourceMethodParameterProvider)
					.responseMarshaler(sokletConfig.responseMarshaler)
					.lifecycleInterceptor(sokletConfig.lifecycleInterceptor)
					.corsAuthorizer(sokletConfig.corsAuthorizer);
		}

		@Nonnull
		public Copier server(@Nonnull Server server) {
			requireNonNull(server);
			this.builder.server = server;
			return this;
		}

		@Nonnull
		public Copier serverSentEventServer(@Nullable ServerSentEventServer serverSentEventServer) {
			this.builder.serverSentEventServer = serverSentEventServer;
			return this;
		}

		@Nonnull
		public Copier instanceProvider(@Nullable InstanceProvider instanceProvider) {
			this.builder.instanceProvider = instanceProvider;
			return this;
		}

		@Nonnull
		public Copier valueConverterRegistry(@Nullable ValueConverterRegistry valueConverterRegistry) {
			this.builder.valueConverterRegistry = valueConverterRegistry;
			return this;
		}

		@Nonnull
		public Copier requestBodyMarshaler(@Nullable RequestBodyMarshaler requestBodyMarshaler) {
			this.builder.requestBodyMarshaler = requestBodyMarshaler;
			return this;
		}

		@Nonnull
		public Copier resourceMethodResolver(@Nullable ResourceMethodResolver resourceMethodResolver) {
			this.builder.resourceMethodResolver = resourceMethodResolver;
			return this;
		}

		@Nonnull
		public Copier resourceMethodParameterProvider(@Nullable ResourceMethodParameterProvider resourceMethodParameterProvider) {
			this.builder.resourceMethodParameterProvider = resourceMethodParameterProvider;
			return this;
		}

		@Nonnull
		public Copier responseMarshaler(@Nullable ResponseMarshaler responseMarshaler) {
			this.builder.responseMarshaler = responseMarshaler;
			return this;
		}

		@Nonnull
		public Copier lifecycleInterceptor(@Nullable LifecycleInterceptor lifecycleInterceptor) {
			this.builder.lifecycleInterceptor = lifecycleInterceptor;
			return this;
		}

		@Nonnull
		public Copier corsAuthorizer(@Nullable CorsAuthorizer corsAuthorizer) {
			this.builder.corsAuthorizer = corsAuthorizer;
			return this;
		}

		@Nonnull
		public SokletConfig finish() {
			return this.builder.build();
		}
	}
}
