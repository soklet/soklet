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
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.soklet.core.Utilities.trimAggressively;
import static java.util.Objects.requireNonNull;

/**
 * {@link CorsAuthorizer} implementation which whitelists specific origins (normally what you want in production).
 * <p>
 * Use {@link #withOrigins(Set)} or {@link #withAuthorizer(Function)} to acquire instances of this class.
 * <p>
 * See <a href="https://www.soklet.com/docs/cors#authorize-whitelisted-origins" target="_blank">https://www.soklet.com/docs/cors#authorize-whitelisted-origins</a> for documentation.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class WhitelistedOriginsCorsAuthorizer implements CorsAuthorizer {
	@Nonnull
	private final Function<String, Boolean> authorizer;

	/**
	 * Creates an authorizer with a fixed set of whitelisted origins.
	 * <p>
	 * For dynamic authorization, see {@link WhitelistedOriginsCorsAuthorizer#withAuthorizer(Function)}.
	 *
	 * @param origins the set of whitelisted origins
	 * @return an instance of {@link WhitelistedOriginsCorsAuthorizer}
	 */
	@Nonnull
	public static WhitelistedOriginsCorsAuthorizer withOrigins(@Nonnull Set<String> origins) {
		requireNonNull(origins);

		Set<String> normalizedOrigins = Collections.unmodifiableSet(new TreeSet<>(origins.stream()
				.map(origin -> normalizeOrigin(origin))
				.collect(Collectors.toSet())));

		return new WhitelistedOriginsCorsAuthorizer(origin -> normalizedOrigins.contains(origin));
	}

	/**
	 * Acquires a {@link WhitelistedOriginsCorsAuthorizer} instance backed by an authorization function which supports runtime authorization decisions.
	 * <p>
	 * For static "build time" authorization, see {@link WhitelistedOriginsCorsAuthorizer#withOrigins(Set)}.
	 *
	 * @param authorizer a function that returns {@code true} if the input is a whitelisted origin and {@code false} otherwise
	 * @return an instance of {@link WhitelistedOriginsCorsAuthorizer}
	 */
	@Nonnull
	public static WhitelistedOriginsCorsAuthorizer withAuthorizer(@Nonnull Function<String, Boolean> authorizer) {
		requireNonNull(authorizer);
		return new WhitelistedOriginsCorsAuthorizer(authorizer);
	}

	private WhitelistedOriginsCorsAuthorizer(@Nonnull Function<String, Boolean> authorizer) {
		requireNonNull(authorizer);
		this.authorizer = authorizer;
	}

	@Nonnull
	@Override
	public Optional<CorsResponse> authorize(@Nonnull Request request,
																					@Nonnull Cors cors) {
		requireNonNull(request);
		requireNonNull(cors);

		if (getAuthorizer().apply(normalizeOrigin(cors.getOrigin())))
			return Optional.of(CorsResponse.withAccessControlAllowOrigin(cors.getOrigin())
					.accessControlExposeHeaders(Set.of("*"))
					.accessControlAllowCredentials(true)
					.build());

		return Optional.empty();
	}

	@Nonnull
	@Override
	public Optional<CorsPreflightResponse> authorizePreflight(@Nonnull Request request,
																														@Nonnull CorsPreflight corsPreflight,
																														@Nonnull Map<HttpMethod, ResourceMethod> availableResourceMethodsByHttpMethod) {
		requireNonNull(request);
		requireNonNull(corsPreflight);
		requireNonNull(availableResourceMethodsByHttpMethod);

		if (getAuthorizer().apply(normalizeOrigin(corsPreflight.getOrigin())))
			return Optional.of(CorsPreflightResponse.withAccessControlAllowOrigin(corsPreflight.getOrigin())
					.accessControlAllowMethods(availableResourceMethodsByHttpMethod.keySet())
					.accessControlAllowHeaders(Set.of("*"))
					.accessControlAllowCredentials(true)
					.build());

		return Optional.empty();
	}

	@Nonnull
	private static String normalizeOrigin(@Nonnull String origin) {
		requireNonNull(origin);
		return trimAggressively(origin).toLowerCase(Locale.ROOT);
	}

	@Nonnull
	private Function<String, Boolean> getAuthorizer() {
		return this.authorizer;
	}
}
