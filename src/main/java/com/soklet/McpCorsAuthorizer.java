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
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import static java.util.Objects.requireNonNull;

/**
 * CORS authorization contract for MCP transport requests.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public interface McpCorsAuthorizer {
	/**
	 * Authorizes a non-preflight browser-originated MCP request and, when allowed, supplies the CORS response metadata to apply.
	 *
	 * @param context the MCP CORS context
	 * @param cors the parsed Soklet CORS request metadata
	 * @return the CORS response metadata to apply, or {@link Optional#empty()} to withhold CORS authorization
	 */
	@NonNull
	Optional<CorsResponse> authorize(@NonNull McpCorsContext context,
																	 @NonNull Cors cors);

	/**
	 * Authorizes a browser preflight request for the MCP transport.
	 *
	 * @param context the MCP CORS context
	 * @param corsPreflight the parsed preflight metadata
	 * @param availableHttpMethods the MCP transport methods available for the current endpoint
	 * @return the preflight response metadata to apply, or {@link Optional#empty()} to reject the preflight
	 */
	@NonNull
	Optional<CorsPreflightResponse> authorizePreflight(@NonNull McpCorsContext context,
																										 @NonNull CorsPreflight corsPreflight,
																										 @NonNull Set<@NonNull HttpMethod> availableHttpMethods);

	/**
	 * Acquires an authorizer that rejects all browser-originated MCP CORS requests.
	 *
	 * @return a rejecting authorizer
	 */
	@NonNull
	static McpCorsAuthorizer rejectAllInstance() {
		return new McpCorsAuthorizer() {
			@NonNull
			@Override
			public Optional<CorsResponse> authorize(@NonNull McpCorsContext context,
																							@NonNull Cors cors) {
				requireNonNull(context);
				requireNonNull(cors);
				return Optional.empty();
			}

			@NonNull
			@Override
			public Optional<CorsPreflightResponse> authorizePreflight(@NonNull McpCorsContext context,
																														@NonNull CorsPreflight corsPreflight,
																														@NonNull Set<@NonNull HttpMethod> availableHttpMethods) {
				requireNonNull(context);
				requireNonNull(corsPreflight);
				requireNonNull(availableHttpMethods);
				return Optional.empty();
			}
		};
	}

	/**
	 * Acquires the conservative default authorizer that leaves non-browser MCP requests alone while rejecting browser CORS authorization.
	 *
	 * @return the default non-browser-only authorizer
	 */
	@NonNull
	static McpCorsAuthorizer nonBrowserClientsOnlyInstance() {
		return rejectAllInstance();
	}

	/**
	 * Acquires an authorizer that allows all browser origins and always enables credentials.
	 *
	 * @return a permissive authorizer
	 */
	@NonNull
	static McpCorsAuthorizer acceptAllInstance() {
		return fromOriginAuthorizer(context -> true, origin -> true);
	}

	/**
	 * Acquires an authorizer that allows only the provided normalized origins and disables credentials by default.
	 *
	 * @param whitelistedOrigins the origins to allow
	 * @return a whitelisting authorizer
	 */
	@NonNull
	static McpCorsAuthorizer fromWhitelistedOrigins(@NonNull Set<@NonNull String> whitelistedOrigins) {
		return fromWhitelistedOrigins(whitelistedOrigins, origin -> false);
	}

	/**
	 * Acquires an authorizer that allows only the provided normalized origins and delegates credential behavior per origin.
	 *
	 * @param whitelistedOrigins the origins to allow
	 * @param allowCredentialsResolver resolves whether credentials should be allowed for an origin
	 * @return a whitelisting authorizer
	 */
	@NonNull
	static McpCorsAuthorizer fromWhitelistedOrigins(@NonNull Set<@NonNull String> whitelistedOrigins,
																									@NonNull Function<String, Boolean> allowCredentialsResolver) {
		requireNonNull(whitelistedOrigins);
		requireNonNull(allowCredentialsResolver);

		Set<String> normalizedOrigins = new LinkedHashSet<>();

		for (String whitelistedOrigin : whitelistedOrigins) {
			requireNonNull(whitelistedOrigin);
			normalizedOrigins.add(normalizeOrigin(whitelistedOrigin));
		}

		return fromOriginAuthorizer(context -> {
			requireNonNull(context);

			if (context.origin() == null)
				return false;

			return normalizedOrigins.contains(normalizeOrigin(context.origin()));
		}, allowCredentialsResolver);
	}

	/**
	 * Acquires an authorizer backed by an origin-authorization predicate and with credentials disabled by default.
	 *
	 * @param originAuthorizer the origin predicate
	 * @return an authorizer backed by the predicate
	 */
	@NonNull
	static McpCorsAuthorizer fromOriginAuthorizer(@NonNull Predicate<@NonNull McpCorsContext> originAuthorizer) {
		return fromOriginAuthorizer(originAuthorizer, origin -> false);
	}

	/**
	 * Acquires an authorizer backed by an origin-authorization predicate plus a credentials resolver.
	 *
	 * @param originAuthorizer the origin predicate
	 * @param allowCredentialsResolver resolves whether credentials should be allowed for an origin
	 * @return an authorizer backed by the supplied callbacks
	 */
	@NonNull
	static McpCorsAuthorizer fromOriginAuthorizer(@NonNull Predicate<@NonNull McpCorsContext> originAuthorizer,
																										@NonNull Function<String, Boolean> allowCredentialsResolver) {
		requireNonNull(originAuthorizer);
		requireNonNull(allowCredentialsResolver);

		return new McpCorsAuthorizer() {
			@NonNull
			@Override
			public Optional<CorsResponse> authorize(@NonNull McpCorsContext context,
																							@NonNull Cors cors) {
				requireNonNull(context);
				requireNonNull(cors);

				if (!originAuthorizer.test(context))
					return Optional.empty();

				return Optional.of(CorsResponse.withAccessControlAllowOrigin(cors.getOrigin())
						.accessControlAllowCredentials(allowCredentialsResolver.apply(normalizeOrigin(cors.getOrigin())))
						.accessControlExposeHeaders(defaultExposedHeaders())
						.build());
			}

			@NonNull
			@Override
			public Optional<CorsPreflightResponse> authorizePreflight(@NonNull McpCorsContext context,
																														@NonNull CorsPreflight corsPreflight,
																														@NonNull Set<@NonNull HttpMethod> availableHttpMethods) {
				requireNonNull(context);
				requireNonNull(corsPreflight);
				requireNonNull(availableHttpMethods);

				if (!originAuthorizer.test(context))
					return Optional.empty();

				return Optional.of(CorsPreflightResponse.withAccessControlAllowOrigin(corsPreflight.getOrigin())
						.accessControlAllowMethods(availableHttpMethods)
						.accessControlAllowHeaders(corsPreflight.getAccessControlRequestHeaders())
						.accessControlAllowCredentials(allowCredentialsResolver.apply(normalizeOrigin(corsPreflight.getOrigin())))
						.accessControlMaxAge(Duration.ofMinutes(10))
						.build());
			}
		};
	}

	@NonNull
	private static Set<String> defaultExposedHeaders() {
		return Set.of("MCP-Session-Id", "WWW-Authenticate");
	}

	@NonNull
	private static String normalizeOrigin(@NonNull String origin) {
		requireNonNull(origin);

		if ("null".equals(origin))
			return "null";

		URI uri = URI.create(origin.trim());
		String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
		String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
		Integer port = uri.getPort() == -1 ? null : uri.getPort();

		if (("http".equals(scheme) && Integer.valueOf(80).equals(port))
				|| ("https".equals(scheme) && Integer.valueOf(443).equals(port)))
			port = null;

		return port == null ? "%s://%s".formatted(scheme, host) : "%s://%s:%s".formatted(scheme, host, port);
	}
}
