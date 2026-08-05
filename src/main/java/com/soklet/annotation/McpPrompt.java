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

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares an MCP prompt handler method.
 * <p>
 * Soklet's annotation processor derives the prompt argument descriptors from
 * parameters annotated with {@link McpPromptArgument}. Prompt arguments are
 * strings and do not use JSON Schema.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface McpPrompt {
	/**
	 * The prompt name published to MCP clients.
	 *
	 * @return the nonblank prompt name
	 */
	@NonNull
	String name();

	/**
	 * The optional human-readable prompt title.
	 *
	 * @return the title, or an empty string if none is configured
	 */
	@Nullable
	String title() default "";

	/**
	 * The optional human-readable prompt description.
	 *
	 * @return the description, or an empty string if none is configured
	 */
	@Nullable
	String description() default "";
}
