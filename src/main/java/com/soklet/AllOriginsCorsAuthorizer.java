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
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * {@link CorsAuthorizer} implementation which whitelists all origins and headers, useful for local development and experimentation.
 * <p>
 * See <a href="https://www.soklet.com/docs/cors#authorize-all-origins" target="_blank">https://www.soklet.com/docs/cors#authorize-all-origins</a> for documentation.
 * <p>
 * <strong>Note: this implementation is generally unsafe for production - prefer {@link WhitelistedOriginsCorsAuthorizer} for a more secure option.</strong>
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class AllOriginsCorsAuthorizer implements CorsAuthorizer {
	@NonNull
	private static final Duration DEFAULT_ACCESS_CONTROL_MAX_AGE;
	@NonNull
	private static final AllOriginsCorsAuthorizer DEFAULT_INSTANCE;

	static {
		DEFAULT_ACCESS_CONTROL_MAX_AGE = Duration.ofMinutes(10);
		DEFAULT_INSTANCE = new AllOriginsCorsAuthorizer();
	}

	/**
	 * Acquires an {@link AllOriginsCorsAuthorizer} instance.
	 *
	 * @return an instance of {@link AllOriginsCorsAuthorizer}
	 */
	@NonNull
	public static AllOriginsCorsAuthorizer defaultInstance() {
		return DEFAULT_INSTANCE;
	}

	private AllOriginsCorsAuthorizer() {
		// Nothing to do
	}

	@NonNull
	@Override
	public Optional<CorsResponse> authorize(@NonNull Request request,
																					@NonNull Cors cors) {
		requireNonNull(request);
		requireNonNull(cors);

		return Optional.of(CorsResponse.withAccessControlAllowOrigin(cors.getOrigin())
				.accessControlAllowCredentials(true)
				.build());
	}

	@NonNull
	@Override
	public Optional<CorsPreflightResponse> authorizePreflight(@NonNull Request request,
																														@NonNull CorsPreflight corsPreflight,
																														@NonNull Map<HttpMethod, ResourceMethod> availableResourceMethodsByHttpMethod) {
		requireNonNull(request);
		requireNonNull(corsPreflight);
		requireNonNull(availableResourceMethodsByHttpMethod);

		// Reflect the requested header names (no "*"), allow methods for the resource, and set a cache TTL.
		return Optional.of(CorsPreflightResponse.withAccessControlAllowOrigin(corsPreflight.getOrigin())
				.accessControlAllowMethods(availableResourceMethodsByHttpMethod.keySet())
				.accessControlAllowHeaders(corsPreflight.getAccessControlRequestHeaders())
				.accessControlMaxAge(DEFAULT_ACCESS_CONTROL_MAX_AGE)
				.accessControlAllowCredentials(true)
				.build());
	}
}