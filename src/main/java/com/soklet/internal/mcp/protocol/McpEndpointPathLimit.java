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

package com.soklet.internal.mcp.protocol;

import com.soklet.ResourcePathDeclaration;
import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.ThreadSafe;
import java.net.URI;
import java.net.URISyntaxException;

import static java.util.Objects.requireNonNull;

/**
 * Shared finite wire bound for MCP endpoint paths.
 *
 * <p>This type is public only so the public builder, annotation processor,
 * generated-index boundary, and transport runtime can share one exact limit.
 * It is not part of Soklet's supported public API or published Javadocs.</p>
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpEndpointPathLimit {
	/** Maximum ASCII request-target bytes accepted by the MCP listener. */
	public static final int MAXIMUM_REQUEST_TARGET_BYTES = 8_192;

	private McpEndpointPathLimit() {
	}

	/**
	 * Returns whether an endpoint path fits the listener's request-target bound.
	 *
	 * @param path endpoint path
	 * @return {@code true} when the ASCII request-target is at most the maximum
	 */
	public static boolean isWithinLimit(@NonNull String path) {
		return requireNonNull(path).length() <= MAXIMUM_REQUEST_TARGET_BYTES;
	}

	/**
	 * Returns whether a value is a normalized ASCII raw URI path.
	 *
	 * @param path endpoint path
	 * @return {@code true} when the path is a valid normalized wire value
	 */
	public static boolean isValidWirePath(@NonNull String path) {
		requireNonNull(path);
		if (!path.startsWith("/") || path.length() == 1
				|| path.codePoints().anyMatch(character -> character > 0x7F))
			return false;
		try {
			URI uri = new URI(path);
			return uri.getScheme() == null && uri.getRawAuthority() == null
					&& uri.getRawQuery() == null && uri.getRawFragment() == null
					&& path.equals(uri.getRawPath()) && uri.normalize().equals(uri)
					&& ResourcePathDeclaration.fromPath(path).getPath()
							.equals(path);
		} catch (IllegalArgumentException | URISyntaxException exception) {
			return false;
		}
	}

	/**
	 * Requires an endpoint path to fit the listener's request-target bound.
	 *
	 * @param path endpoint path
	 * @return the supplied path
	 * @throws IllegalArgumentException if the ASCII request-target is too large
	 */
	@NonNull
	public static String requireWithinLimit(@NonNull String path) {
		requireNonNull(path);
		if (!isWithinLimit(path))
			throw new IllegalArgumentException(
					"MCP endpoint path must not exceed 8192 ASCII request-target bytes.");
		return path;
	}

	/**
	 * Requires a normalized endpoint path that can appear verbatim on the wire.
	 *
	 * @param path endpoint path
	 * @return the supplied path
	 * @throws IllegalArgumentException if the path is not a normalized ASCII raw
	 *                                  URI path or exceeds the listener bound
	 */
	@NonNull
	public static String requireValidWirePath(@NonNull String path) {
		requireNonNull(path);
		if (!isValidWirePath(path))
			throw new IllegalArgumentException(
					"MCP endpoint path must be a normalized ASCII raw URI path; percent-encode non-ASCII characters.");
		return requireWithinLimit(path);
	}
}
