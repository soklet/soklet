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

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds a parameter of an {@link McpPrompt} method as a published prompt
 * argument.
 * <p>
 * Prompt arguments must be declared as {@code String} or
 * {@code Optional<String>}. Requiredness is inferred from the declared type:
 * {@code String} is required and {@code Optional<String>} is optional. A blank
 * {@link #name()} uses the source-level Java parameter name. Explicit names
 * provide a stable public contract across Java refactoring.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface McpPromptArgument {
	/**
	 * The published argument name.
	 *
	 * @return the argument name, or an empty string to use the Java parameter
	 * name
	 */
	@NonNull
	String name() default "";

	/**
	 * The optional human-readable argument title.
	 *
	 * @return the title, or an empty string if none is configured
	 */
	@NonNull
	String title() default "";

	/**
	 * The optional human-readable argument description.
	 *
	 * @return the description, or an empty string if none is configured
	 */
	@NonNull
	String description() default "";
}
