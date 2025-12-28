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
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.soklet.Utilities.trimAggressively;
import static java.util.Objects.requireNonNull;

/**
 * {@link CorsAuthorizer} implementation which whitelists specific origins (normally what you want in production).
 * <p>
 * See <a href="https://www.soklet.com/docs/cors#authorize-whitelisted-origins" target="_blank">https://www.soklet.com/docs/cors#authorize-whitelisted-origins</a> for documentation.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class WhitelistedOriginsCorsAuthorizer implements CorsAuthorizer {
	@NonNull
	private static final Duration DEFAULT_ACCESS_CONTROL_MAX_AGE;

	static {
		DEFAULT_ACCESS_CONTROL_MAX_AGE = Duration.ofMinutes(10);
	}

	@NonNull
	private final Function<String, Boolean> authorizer;
	@NonNull
	private final Function<String, Boolean> allowCredentialsResolver;

	/**
	 * Creates an authorizer with a fixed set of whitelisted origins.
	 * <p>
	 * For dynamic authorization, see {@link WhitelistedOriginsCorsAuthorizer#withAuthorizer(Function, Function)}.
	 *
	 * @param origins                  the set of whitelisted origins
	 * @param allowCredentialsResolver function which takes a normalized {@code Origin} as input and should return {@code true} if clients are permitted to include credentials in cross-origin HTTP requests and {@code false} otherwise.
	 * @return an instance of {@link WhitelistedOriginsCorsAuthorizer}
	 */
	@NonNull
	public static WhitelistedOriginsCorsAuthorizer withOrigins(@NonNull Set<@NonNull String> origins,
																														 @NonNull Function<String, Boolean> allowCredentialsResolver) {
		requireNonNull(origins);
		requireNonNull(allowCredentialsResolver);

		Set<@NonNull String> normalizedOrigins = Collections.unmodifiableSet(new TreeSet<>(origins.stream()
				.map(origin -> normalizeOrigin(origin))
				.collect(Collectors.toSet())));

		return new WhitelistedOriginsCorsAuthorizer(origin -> normalizedOrigins.contains(origin), allowCredentialsResolver);
	}

	/**
	 * Acquires a {@link WhitelistedOriginsCorsAuthorizer} instance backed by an authorization function which supports runtime authorization decisions.
	 * <p>
	 * For static "build time" authorization, see {@link WhitelistedOriginsCorsAuthorizer#withOrigins(Set, Function)}.
	 *
	 * @param authorizer               a function that returns {@code true} if the input is a whitelisted origin and {@code false} otherwise
	 * @param allowCredentialsResolver function which takes a normalized {@code Origin} as input and should return {@code true} if clients are permitted to include credentials in cross-origin HTTP requests and {@code false} otherwise.
	 * @return an instance of {@link WhitelistedOriginsCorsAuthorizer}
	 */
	@NonNull
	public static WhitelistedOriginsCorsAuthorizer withAuthorizer(@NonNull Function<String, Boolean> authorizer,
																																@NonNull Function<String, Boolean> allowCredentialsResolver) {
		requireNonNull(authorizer);
		requireNonNull(allowCredentialsResolver);

		return new WhitelistedOriginsCorsAuthorizer(authorizer, allowCredentialsResolver);
	}

	private WhitelistedOriginsCorsAuthorizer(@NonNull Function<String, Boolean> authorizer,
																					 @NonNull Function<String, Boolean> allowCredentialsResolver) {
		requireNonNull(authorizer);
		requireNonNull(allowCredentialsResolver);

		this.authorizer = authorizer;
		this.allowCredentialsResolver = allowCredentialsResolver;
	}

	@NonNull
	@Override
	public Optional<CorsResponse> authorize(@NonNull Request request,
																								@NonNull Cors cors) {
		requireNonNull(request);
		requireNonNull(cors);

		String origin = normalizeOrigin(cors.getOrigin());

		if (isSuspiciousOrigin(origin))
			return Optional.empty();

		Boolean authorized = getAuthorizer().apply(origin);

		if (authorized != null && authorized) {
			// May be null, which is OK
			Boolean accessControlAllowCredentials = getAllowCredentialsResolver().apply(origin);

			return Optional.of(CorsResponse.withAccessControlAllowOrigin(cors.getOrigin())
					.accessControlAllowCredentials(accessControlAllowCredentials)
					.build());
		}

		return Optional.empty();
	}

	@NonNull
	@Override
	public Optional<CorsPreflightResponse> authorizePreflight(@NonNull Request request,
																																		@NonNull CorsPreflight corsPreflight,
																																		@NonNull Map<@NonNull HttpMethod, @NonNull ResourceMethod> availableResourceMethodsByHttpMethod) {
		requireNonNull(request);
		requireNonNull(corsPreflight);
		requireNonNull(availableResourceMethodsByHttpMethod);

		String origin = normalizeOrigin(corsPreflight.getOrigin());

		if (isSuspiciousOrigin(origin))
			return Optional.empty();

		Boolean authorized = getAuthorizer().apply(origin);

		if (authorized != null && authorized) {
			// May be null, which is OK
			Boolean accessControlAllowCredentials = getAllowCredentialsResolver().apply(origin);

			return Optional.of(CorsPreflightResponse.withAccessControlAllowOrigin(corsPreflight.getOrigin())
					.accessControlAllowMethods(availableResourceMethodsByHttpMethod.keySet())
					.accessControlAllowHeaders(corsPreflight.getAccessControlRequestHeaders())
					.accessControlAllowCredentials(accessControlAllowCredentials)
					.accessControlMaxAge(DEFAULT_ACCESS_CONTROL_MAX_AGE)
					.build());
		}

		return Optional.empty();
	}

	@NonNull
	private static String normalizeOrigin(@NonNull String origin) {
		requireNonNull(origin);
		return trimAggressively(origin).toLowerCase(Locale.ROOT);
	}

	@NonNull
	private Boolean isSuspiciousOrigin(@NonNull String origin) {
		requireNonNull(origin);
		return "null".equals(origin);
	}

	@NonNull
	private Function<String, Boolean> getAuthorizer() {
		return this.authorizer;
	}

	@NonNull
	private Function<String, Boolean> getAllowCredentialsResolver() {
		return this.allowCredentialsResolver;
	}
}
