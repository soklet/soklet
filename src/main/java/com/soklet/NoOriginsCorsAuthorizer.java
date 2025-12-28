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

import javax.annotation.concurrent.ThreadSafe;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * {@link CorsAuthorizer} implementation which rejects all CORS authorization attempts.
 * <p>
 * Use {@link #defaultInstance()} to acquire the singleton instance of this class.
 * <p>
 * See <a href="https://www.soklet.com/docs/cors#authorize-no-origins" target="_blank">https://www.soklet.com/docs/cors#authorize-no-origins</a> for documentation.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
class NoOriginsCorsAuthorizer implements CorsAuthorizer {
	@NonNull
	private static final NoOriginsCorsAuthorizer DEFAULT_INSTANCE;

	static {
		DEFAULT_INSTANCE = new NoOriginsCorsAuthorizer();
	}

	/**
	 * Acquires a {@link NoOriginsCorsAuthorizer} instance.
	 *
	 * @return an instance of {@link NoOriginsCorsAuthorizer}
	 */
	@NonNull
	public static NoOriginsCorsAuthorizer defaultInstance() {
		return DEFAULT_INSTANCE;
	}

	private NoOriginsCorsAuthorizer() {
		// Nothing to do
	}

	@NonNull
	@Override
	public Optional<@NonNull CorsResponse> authorize(@NonNull Request request,
																								@NonNull Cors cors) {
		requireNonNull(request);
		requireNonNull(cors);

		return Optional.empty();
	}

	@NonNull
	@Override
	public Optional<@NonNull CorsPreflightResponse> authorizePreflight(@NonNull Request request,
																																		@NonNull CorsPreflight corsPreflight,
																																		@NonNull Map<@NonNull HttpMethod, @NonNull ResourceMethod> availableResourceMethodsByHttpMethod) {
		requireNonNull(request);
		requireNonNull(corsPreflight);
		requireNonNull(availableResourceMethodsByHttpMethod);

		return Optional.empty();
	}
}
