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
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class WhitelistedOriginsCorsAuthorizer implements CorsAuthorizer {
	@Nonnull
	private final Function<String, Boolean> authorizer;

	public WhitelistedOriginsCorsAuthorizer(@Nonnull Set<String> whitelistedOrigins) {
		requireNonNull(whitelistedOrigins);

		Set<String> normalizedWhitelistedOrigins = Collections.unmodifiableSet(new TreeSet<>(whitelistedOrigins.stream()
				.map(whitelistedOrigin -> normalizeOrigin(whitelistedOrigin))
				.collect(Collectors.toSet())));

		this.authorizer = (origin -> normalizedWhitelistedOrigins.contains(origin));
	}

	public WhitelistedOriginsCorsAuthorizer(@Nonnull Function<String, Boolean> authorizer) {
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
	protected String normalizeOrigin(@Nonnull String origin) {
		requireNonNull(origin);
		return trimAggressively(origin).toLowerCase(Locale.ROOT);
	}

	@Nonnull
	protected Function<String, Boolean> getAuthorizer() {
		return this.authorizer;
	}
}
