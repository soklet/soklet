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

package com.soklet.core.impl;

import com.soklet.core.Cors;
import com.soklet.core.CorsAuthorizer;
import com.soklet.core.CorsPreflight;
import com.soklet.core.CorsPreflightResponse;
import com.soklet.core.CorsResponse;
import com.soklet.core.HttpMethod;
import com.soklet.core.Request;
import com.soklet.core.ResourceMethod;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
public class AllOriginsCorsAuthorizer implements CorsAuthorizer {
	@Nonnull
	@Override
	public Optional<CorsResponse> authorize(@Nonnull Request request,
																					@Nonnull Cors cors) {
		requireNonNull(request);
		requireNonNull(cors);

		return Optional.of(CorsResponse.withAccessControlAllowOrigin("*")
				.accessControlExposeHeaders(Set.of("*"))
				.accessControlAllowCredentials(true)
				.build());
	}

	@Nonnull
	@Override
	public Optional<CorsPreflightResponse> authorizePreflight(@Nonnull Request request,
																														@Nonnull CorsPreflight corsPreflight,
																														@Nonnull Map<HttpMethod, ResourceMethod> availableResourceMethodsByHttpMethod) {
		requireNonNull(request);
		requireNonNull(corsPreflight);
		requireNonNull(availableResourceMethodsByHttpMethod);

		return Optional.of(CorsPreflightResponse.withAccessControlAllowOrigin("*")
				.accessControlAllowMethods(availableResourceMethodsByHttpMethod.keySet())
				.accessControlAllowHeaders(Set.of("*"))
				.accessControlAllowCredentials(true)
				.build());
	}
}