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
import com.soklet.McpRequestStateMode;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares an exact-URI or URI-template MCP resource-read handler.
 * <p>
 * A URI containing RFC 6570 Level 1 variables is a template registration;
 * each variable is bound to exactly one {@link McpResourceUriParameter}
 * method parameter. An exact URI contributes a descriptor to the static
 * {@code resources/list} fallback. A URI template is advertised separately by
 * {@code resources/templates/list}.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface McpResource {
	/**
	 * The absolute resource URI or RFC 6570 Level 1 URI template.
	 *
	 * @return resource URI or URI template
	 */
	@NonNull
	String uri();

	/**
	 * The resource name published to MCP clients.
	 *
	 * @return nonblank resource name
	 */
	@NonNull
	String name();

	/**
	 * The optional human-readable resource title.
	 *
	 * @return title, or an empty string if none is configured
	 */
	@Nullable
	String title() default "";

	/**
	 * The optional human-readable resource description.
	 *
	 * @return description, or an empty string if none is configured
	 */
	@Nullable
	String description() default "";

	/**
	 * The optional resource MIME type.
	 *
	 * @return MIME type, or an empty string if none is configured
	 */
	@Nullable
	String mimeType() default "";

	/**
	 * The raw size of an exact resource in bytes.
	 * <p>
	 * URI templates cannot declare a size because their concrete resources may
	 * differ. The default {@code -1} means that no size is published.
	 *
	 * @return nonnegative exact-resource size, or {@code -1} when absent
	 */
	long size() default -1;

	/**
	 * The default cache time to live for reads through this registration.
	 *
	 * @return nonnegative whole-millisecond time to live
	 */
	long cacheTtlMs() default 0;

	/**
	 * The fixed cache scope for reads through this registration.
	 *
	 * @return cache scope
	 */
	@NonNull
	McpCacheScope cacheScope() default McpCacheScope.PRIVATE;

	/**
	 * Client requests this resource-read operation may emit during
	 * multi-round-trip handling.
	 *
	 * @return input-request declarations
	 */
	@NonNull
	McpMayRequestInput @NonNull [] mayRequestInput() default {};

	/**
	 * The request-state contract for this resource-read operation.
	 *
	 * @return request-state mode
	 */
	@NonNull
	McpRequestStateMode requestStateMode() default McpRequestStateMode.NONE;
}
