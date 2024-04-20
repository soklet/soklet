/*
 * Copyright 2022-2024 Revetware LLC.
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
import com.soklet.core.CorsAuthorizer;
import com.soklet.core.InstanceProvider;
import com.soklet.core.LifecycleInterceptor;
import com.soklet.core.LogHandler;
import com.soklet.core.RequestBodyMarshaler;
import com.soklet.core.ResourceMethodParameterProvider;
import com.soklet.core.ResourceMethodResolver;
import com.soklet.core.ResponseMarshaler;
import com.soklet.core.Server;
import com.soklet.core.impl.DefaultInstanceProvider;
import com.soklet.core.impl.DefaultLifecycleInterceptor;
import com.soklet.core.impl.DefaultLogHandler;
import com.soklet.core.impl.DefaultRequestBodyMarshaler;
import com.soklet.core.impl.DefaultResourceMethodParameterProvider;
import com.soklet.core.impl.DefaultResourceMethodResolver;
import com.soklet.core.impl.DefaultResponseMarshaler;
import com.soklet.core.impl.NoOriginsCorsAuthorizer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;

import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class SokletConfiguration {
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
	private final LogHandler logHandler;
	@Nonnull
	private final Server server;

	@Nonnull
	public static Builder withServer(@Nonnull Server server) {
		requireNonNull(server);
		return new Builder(server);
	}

	@Nonnull
	public static Builder withMockServer() {
		return new Builder(new Soklet.MockServer());
	}

	public SokletConfiguration(@Nonnull Builder builder) {
		requireNonNull(builder);

		this.server = builder.server;
		this.logHandler = builder.logHandler != null ? builder.logHandler : DefaultLogHandler.sharedInstance();
		this.instanceProvider = builder.instanceProvider != null ? builder.instanceProvider : DefaultInstanceProvider.sharedInstance();
		this.valueConverterRegistry = builder.valueConverterRegistry != null ? builder.valueConverterRegistry : ValueConverterRegistry.sharedInstance();
		this.requestBodyMarshaler = builder.requestBodyMarshaler != null ? builder.requestBodyMarshaler : new DefaultRequestBodyMarshaler(getValueConverterRegistry());
		this.resourceMethodResolver = builder.resourceMethodResolver != null ? builder.resourceMethodResolver : DefaultResourceMethodResolver.sharedInstance();
		this.resourceMethodParameterProvider = builder.resourceMethodParameterProvider != null ? builder.resourceMethodParameterProvider : new DefaultResourceMethodParameterProvider(getInstanceProvider(), getValueConverterRegistry(), getRequestBodyMarshaler());
		this.responseMarshaler = builder.responseMarshaler != null ? builder.responseMarshaler : DefaultResponseMarshaler.sharedInstance();
		this.lifecycleInterceptor = builder.lifecycleInterceptor != null ? builder.lifecycleInterceptor : DefaultLifecycleInterceptor.sharedInstance();
		this.corsAuthorizer = builder.corsAuthorizer != null ? builder.corsAuthorizer : NoOriginsCorsAuthorizer.sharedInstance();
	}

	@Nonnull
	public Copier copy() {
		return new Copier(this);
	}

	@Nonnull
	public InstanceProvider getInstanceProvider() {
		return this.instanceProvider;
	}

	@Nonnull
	public ValueConverterRegistry getValueConverterRegistry() {
		return this.valueConverterRegistry;
	}

	@Nonnull
	public RequestBodyMarshaler getRequestBodyMarshaler() {
		return this.requestBodyMarshaler;
	}

	@Nonnull
	public ResourceMethodResolver getResourceMethodResolver() {
		return this.resourceMethodResolver;
	}

	@Nonnull
	public ResourceMethodParameterProvider getResourceMethodParameterProvider() {
		return this.resourceMethodParameterProvider;
	}

	@Nonnull
	public ResponseMarshaler getResponseMarshaler() {
		return this.responseMarshaler;
	}

	@Nonnull
	public LifecycleInterceptor getLifecycleInterceptor() {
		return this.lifecycleInterceptor;
	}

	@Nonnull
	public CorsAuthorizer getCorsAuthorizer() {
		return this.corsAuthorizer;
	}

	@Nonnull
	public Server getServer() {
		return this.server;
	}

	@Nonnull
	public LogHandler getLogHandler() {
		return this.logHandler;
	}

	/**
	 * Builder used to construct instances of {@link SokletConfiguration}.
	 * <p>
	 * This class is intended for use by a single thread.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static class Builder {
		@Nonnull
		private Server server;
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
		@Nullable
		private LogHandler logHandler;

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
		public Builder logHandler(@Nullable LogHandler logHandler) {
			this.logHandler = logHandler;
			return this;
		}

		@Nonnull
		public SokletConfiguration build() {
			return new SokletConfiguration(this);
		}
	}

	/**
	 * Builder used to copy instances of {@link SokletConfiguration}.
	 * <p>
	 * This class is intended for use by a single thread.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static class Copier {
		@Nonnull
		private SokletConfiguration.Builder builder;

		Copier(@Nonnull SokletConfiguration sokletConfiguration) {
			requireNonNull(sokletConfiguration);

			this.builder = new SokletConfiguration.Builder(sokletConfiguration.getServer());
			this.builder.instanceProvider = sokletConfiguration.getInstanceProvider();
			this.builder.valueConverterRegistry = sokletConfiguration.valueConverterRegistry;
			this.builder.requestBodyMarshaler = sokletConfiguration.requestBodyMarshaler;
			this.builder.resourceMethodResolver = sokletConfiguration.resourceMethodResolver;
			this.builder.resourceMethodParameterProvider = sokletConfiguration.resourceMethodParameterProvider;
			this.builder.responseMarshaler = sokletConfiguration.responseMarshaler;
			this.builder.lifecycleInterceptor = sokletConfiguration.lifecycleInterceptor;
			this.builder.corsAuthorizer = sokletConfiguration.corsAuthorizer;
			this.builder.logHandler = sokletConfiguration.logHandler;
		}

		@Nonnull
		public Copier server(@Nonnull Server server) {
			requireNonNull(server);
			this.builder.server = server;
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
		public Copier logHandler(@Nullable LogHandler logHandler) {
			this.builder.logHandler = logHandler;
			return this;
		}

		@Nonnull
		public SokletConfiguration finish() {
			return this.builder.build();
		}
	}
}
