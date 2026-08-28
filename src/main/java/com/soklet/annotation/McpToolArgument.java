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
 * Binds a parameter of an {@link McpTool} method as a published tool
 * argument.
 * <p>
 * Requiredness is inferred from the declared parameter type: {@code T} is
 * required and {@code Optional<T>} is optional. A blank {@link #name()} uses
 * the source-level Java parameter name. Explicit names provide a stable public
 * contract across Java refactoring. Use {@link McpToolProperty} to supply the
 * corresponding schema metadata for a component of an ordinary typed input or
 * output record.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface McpToolArgument {
	/**
	 * The published argument name.
	 *
	 * @return the argument name, or an empty string to use the Java
	 * parameter name
	 */
	@Nullable
	String name() default "";

	/**
	 * The optional human-readable argument title.
	 *
	 * @return the title, or an empty string if none is configured
	 */
	@Nullable
	String title() default "";

	/**
	 * The optional human-readable argument description.
	 *
	 * @return the description, or an empty string if none is configured
	 */
	@Nullable
	String description() default "";
}
