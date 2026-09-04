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

import com.soklet.McpRequestStateMode;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares an MCP tool handler method.
 * <p>
 * Soklet's annotation processor derives the input schema from parameters
 * annotated with {@link McpToolArgument}. For an ordinary typed-completion
 * method, it derives the output schema from the declared return type. A method
 * returning {@code McpOperationResult} or a subtype instead uses the advanced
 * result path and has no derived output schema. {@link McpToolProperty}
 * customizes property metadata on ordinary typed input and output records.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface McpTool {
	/**
	 * The tool name published to MCP clients.
	 *
	 * @return the nonblank tool name
	 */
	@NonNull
	String name();

	/**
	 * The optional human-readable tool title.
	 *
	 * @return the title, or an empty string if none is configured
	 */
	@Nullable
	String title() default "";

	/**
	 * The optional human-readable tool description.
	 *
	 * @return the description, or an empty string if none is configured
	 */
	@Nullable
	String description() default "";

	/**
	 * Names a server-registered rate limiter for this tool.
	 * <p>
	 * An empty value inherits the endpoint or server tool rate limiter. A
	 * nonempty value must identify an entry in the server's rate-limiter
	 * registry.
	 *
	 * @return the rate-limiter name, or an empty string to inherit
	 */
	@Nullable
	String rateLimiterName() default "";

	/**
	 * Whether Soklet mirrors structured tool output into a text content block.
	 *
	 * @return {@code true} to mirror structured content as text
	 */
	boolean structuredContentMirroredAsText() default true;

	/**
	 * Client requests this tool may emit during multi-round-trip handling.
	 *
	 * <p>A nonempty declaration requires the method to return
	 * {@code McpOperationResult} or a subtype.
	 *
	 * @return input-request declarations
	 */
	@NonNull
	McpMayRequestInput @NonNull [] mayRequestInput() default {};

	/**
	 * The request-state contract for this tool.
	 *
	 * <p>A mode other than {@link McpRequestStateMode#NONE} requires the method
	 * to return {@code McpOperationResult} or a subtype.
	 *
	 * @return request-state mode
	 */
	@NonNull
	McpRequestStateMode requestStateMode() default McpRequestStateMode.NONE;
}
