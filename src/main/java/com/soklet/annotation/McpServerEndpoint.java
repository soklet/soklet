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

package com.soklet.annotation;

import com.soklet.McpCacheScope;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a class that contributes an endpoint to Soklet's dedicated MCP
 * server.
 * <p>
 * Soklet's annotation processor derives the endpoint descriptor without
 * initializing the annotated class. A running MCP server creates endpoint
 * instances through its configured instance provider when invoking handlers.
 * Named application modules must open or export the endpoint package to
 * Soklet. Packages containing non-public record types used for typed arguments
 * or results must be open to Soklet's module for runtime conversion.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface McpServerEndpoint {
	/**
	 * The URL path exposed by the dedicated MCP server.
	 *
	 * @return the endpoint URL path
	 */
	@NonNull
	String path();

	/**
	 * The implementation name reported to MCP clients.
	 *
	 * @return the nonblank implementation name
	 */
	@NonNull
	String name();

	/**
	 * The implementation version reported to MCP clients.
	 *
	 * @return the nonblank implementation version
	 */
	@NonNull
	String version();

	/**
	 * The optional human-readable implementation title.
	 *
	 * @return the title, or an empty string if none is configured
	 */
	@Nullable
	String title() default "";

	/**
	 * The optional human-readable implementation description.
	 *
	 * @return the description, or an empty string if none is configured
	 */
	@Nullable
	String description() default "";

	/**
	 * The optional absolute website URL for the implementation.
	 *
	 * @return the website URL, or an empty string if none is configured
	 */
	@Nullable
	String websiteUrl() default "";

	/**
	 * Optional instructions that help clients use this endpoint.
	 *
	 * @return endpoint instructions, or an empty string if none are configured
	 */
	@Nullable
	String instructions() default "";

	/**
	 * Names a server-registered tool rate limiter for this endpoint.
	 * <p>
	 * An empty value inherits the server's tool rate limiter. A nonempty value
	 * must identify an entry in the server's rate-limiter registry.
	 *
	 * @return the rate-limiter name, or an empty string to inherit
	 */
	@Nullable
	String toolRateLimiter() default "";

	/**
	 * The default cache time to live for {@code resources/list} pages.
	 *
	 * @return nonnegative whole-millisecond time to live
	 */
	long resourcesListCacheTtlMs() default 0;

	/**
	 * The fixed cache scope for every {@code resources/list} page.
	 *
	 * @return resources-list cache scope
	 */
	@NonNull
	McpCacheScope resourcesListCacheScope() default McpCacheScope.PRIVATE;

	/**
	 * The default cache time to live for
	 * {@code resources/templates/list}.
	 *
	 * @return nonnegative whole-millisecond time to live
	 */
	long resourceTemplatesListCacheTtlMs() default 0;

	/**
	 * The fixed cache scope for {@code resources/templates/list}.
	 *
	 * @return resource-template-list cache scope
	 */
	@NonNull
	McpCacheScope resourceTemplatesListCacheScope()
			default McpCacheScope.PRIVATE;
}
