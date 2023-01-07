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

package com.soklet.core.impl;

import com.soklet.core.CorsAuthorizer;
import com.soklet.core.CorsPreflightResponse;
import com.soklet.core.CorsResponse;
import com.soklet.core.HttpMethod;
import com.soklet.core.Request;
import com.soklet.core.Request.Cors;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;
import java.util.Collections;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetware.com">Mark Allen</a>
 */
@ThreadSafe
public class WhitelistedOriginsCorsAuthorizer implements CorsAuthorizer {
	@Nonnull
	private final Set<String> whitelistedOrigins;

	public WhitelistedOriginsCorsAuthorizer(@Nonnull Set<String> whitelistedOrigins) {
		requireNonNull(whitelistedOrigins);
		this.whitelistedOrigins = Collections.unmodifiableSet(new TreeSet<>(whitelistedOrigins.stream()
				.map(whitelistedOrigin -> normalizeOrigin(whitelistedOrigin))
				.collect(Collectors.toSet())));
	}

	@Nonnull
	@Override
	public Optional<CorsResponse> authorize(@Nonnull Request request) {
		requireNonNull(request);

		Cors cors = request.getCors().orElse(null);

		if (cors == null)
			return Optional.empty();

		if (getWhitelistedOrigins().contains(normalizeOrigin(cors.getOrigin())))
			return Optional.of(new CorsResponse.Builder(cors.getOrigin())
					.accessControlExposeHeaders(Set.of("*"))
					.build());

		return Optional.empty();
	}

	@Nonnull
	@Override
	public Optional<CorsPreflightResponse> authorizePreflight(@Nonnull Request request,
																														@Nonnull Set<HttpMethod> availableHttpMethods) {
		requireNonNull(request);
		requireNonNull(availableHttpMethods);

		Cors cors = request.getCors().orElse(null);

		if (cors == null)
			return Optional.empty();

		if (getWhitelistedOrigins().contains(normalizeOrigin(cors.getOrigin())))
			return Optional.of(new CorsPreflightResponse.Builder(cors.getOrigin())
					.accessControlAllowMethods(availableHttpMethods)
					.accessControlAllowHeaders(Set.of("*"))
					.build());

		return Optional.empty();
	}

	@Nonnull
	protected String normalizeOrigin(@Nonnull String origin) {
		requireNonNull(origin);
		return origin.trim().toLowerCase(Locale.ROOT);
	}

	@Nonnull
	protected Set<String> getWhitelistedOrigins() {
		return this.whitelistedOrigins;
	}
}
