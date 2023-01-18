/*
 * Copyright 2022 Revetware LLC.
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
import com.soklet.core.ResourceMethodParameterProvider;
import com.soklet.core.ResourceMethodResolver;
import com.soklet.core.ResponseMarshaler;
import com.soklet.core.Server;
import com.soklet.core.impl.DefaultCorsAuthorizer;
import com.soklet.core.impl.DefaultInstanceProvider;
import com.soklet.core.impl.DefaultLifecycleInterceptor;
import com.soklet.core.impl.DefaultLogHandler;
import com.soklet.core.impl.DefaultResourceMethodParameterProvider;
import com.soklet.core.impl.DefaultResourceMethodResolver;
import com.soklet.core.impl.DefaultResponseMarshaler;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;

import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetware.com">Mark Allen</a>
 */
@ThreadSafe
public class SokletConfiguration {
	@Nonnull
	private final InstanceProvider instanceProvider;
	@Nonnull
	private final ValueConverterRegistry valueConverterRegistry;
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
	@Nonnull
	private final LogHandler logHandler;
	@Nonnull
	private final Boolean startImmediately;

	public SokletConfiguration(@Nonnull Builder builder) {
		requireNonNull(builder);

		this.server = builder.server;
		this.logHandler = builder.logHandler != null ? builder.logHandler : new DefaultLogHandler();
		this.instanceProvider = builder.instanceProvider != null ? builder.instanceProvider : new DefaultInstanceProvider();
		this.valueConverterRegistry = builder.valueConverterRegistry != null ? builder.valueConverterRegistry : new ValueConverterRegistry();
		this.resourceMethodResolver = builder.resourceMethodResolver != null ? builder.resourceMethodResolver : new DefaultResourceMethodResolver();
		this.resourceMethodParameterProvider = builder.resourceMethodParameterProvider != null ? builder.resourceMethodParameterProvider : new DefaultResourceMethodParameterProvider(getInstanceProvider(), getValueConverterRegistry());
		this.responseMarshaler = builder.responseMarshaler != null ? builder.responseMarshaler : new DefaultResponseMarshaler();
		this.lifecycleInterceptor = builder.lifecycleInterceptor != null ? builder.lifecycleInterceptor : new DefaultLifecycleInterceptor();
		this.corsAuthorizer = builder.corsAuthorizer != null ? builder.corsAuthorizer : new DefaultCorsAuthorizer();
		this.startImmediately = builder.startImmediately != null ? builder.startImmediately : true;
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

	@Nonnull
	public Boolean getStartImmediately() {
		return this.startImmediately;
	}

	/**
	 * Builder used to construct instances of {@link SokletConfiguration}.
	 * <p>
	 * This class is intended for use by a single thread.
	 *
	 * @author <a href="https://www.revetware.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static class Builder {
		@Nonnull
		private final Server server;
		@Nullable
		private InstanceProvider instanceProvider;
		@Nullable
		private ValueConverterRegistry valueConverterRegistry;
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
		@Nullable
		private Boolean startImmediately;

		@Nonnull
		public Builder(@Nonnull Server server) {
			requireNonNull(server);
			this.server = server;
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
		public Builder startImmediately(@Nullable Boolean startImmediately) {
			this.startImmediately = startImmediately;
			return this;
		}

		@Nonnull
		public SokletConfiguration build() {
			return new SokletConfiguration(this);
		}
	}
}
