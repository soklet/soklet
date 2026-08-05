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
 * Binds a {@link McpResource} method parameter to a URI-template variable.
 * <p>
 * Resource URI parameters are strings. A blank {@link #value()} uses the
 * source-level Java parameter name captured by Soklet's annotation processor;
 * an explicit value keeps the public template contract stable across Java
 * refactoring.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface McpResourceUriParameter {
	/**
	 * The URI-template variable name.
	 *
	 * @return variable name, or an empty string to use the Java parameter name
	 */
	@Nullable
	String value() default "";
}
