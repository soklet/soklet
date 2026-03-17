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

import org.jspecify.annotations.Nullable;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Applies to classes that expose an MCP endpoint.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface McpServerEndpoint {
	/**
	 * Declares the HTTP path for the MCP transport endpoint.
	 *
	 * @return the MCP endpoint path
	 */
    String path();

	/**
	 * Declares the MCP server name.
	 *
	 * @return the server name exposed during initialization
	 */
    String name();

	/**
	 * Declares the MCP server version.
	 *
	 * @return the server version exposed during initialization
	 */
    String version();

	/**
	 * Declares optional server instructions metadata.
	 *
	 * @return the server instructions, or an empty string if unspecified
	 */
    @Nullable
    String instructions() default "";

	/**
	 * Declares optional server title metadata.
	 *
	 * @return the server title, or an empty string if unspecified
	 */
    @Nullable
    String title() default "";

	/**
	 * Declares optional server description metadata.
	 *
	 * @return the server description, or an empty string if unspecified
	 */
    @Nullable
    String description() default "";

	/**
	 * Declares optional server website URL metadata.
	 *
	 * @return the server website URL, or an empty string if unspecified
	 */
    @Nullable
    String websiteUrl() default "";
}
