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
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

import static java.util.Objects.requireNonNull;

/**
 * Admission-style origin policy for MCP transport requests.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public interface McpOriginPolicy {
	@NonNull
	Boolean isAllowed(@NonNull McpOriginCheckContext context);

	@NonNull
	static McpOriginPolicy rejectAllInstance() {
		return context -> false;
	}

	@NonNull
	static McpOriginPolicy nonBrowserClientsOnlyInstance() {
		return context -> context.origin() == null;
	}

	@NonNull
	static McpOriginPolicy acceptAllInstance() {
		return context -> true;
	}

	@NonNull
	static McpOriginPolicy fromWhitelistedOrigins(@NonNull Set<@NonNull String> whitelistedOrigins) {
		requireNonNull(whitelistedOrigins);

		Set<String> normalizedOrigins = new LinkedHashSet<>();

		for (String whitelistedOrigin : whitelistedOrigins) {
			requireNonNull(whitelistedOrigin);
			normalizedOrigins.add(normalizeOrigin(whitelistedOrigin));
		}

		return context -> {
			requireNonNull(context);

			if (context.origin() == null)
				return true;

			return normalizedOrigins.contains(normalizeOrigin(context.origin()));
		};
	}

	@NonNull
	static McpOriginPolicy fromOriginAuthorizer(@NonNull Predicate<@NonNull McpOriginCheckContext> originAuthorizer) {
		requireNonNull(originAuthorizer);
		return context -> originAuthorizer.test(context);
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
