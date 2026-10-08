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
 * Apply to <em>Resource Method</em> parameters to enable URL path parameter (for example, {@code /widgets/{widgetId}}) injection.
 * <p>
 * Path parameters cannot use {@code Optional<T>}; varargs path parameters must use {@code String}.
 * Apply only one Soklet binding annotation to each parameter.
 * <p>
 * Varargs placeholders match zero or more path components. With the default parameter provider and String converter,
 * a suffix with no components is injected as {@code ""}. Explicit String converters still run and may reject it.
 * <p>
 * Refer to documentation at <a href="https://www.soklet.com/docs/request-handling#path-parameters">https://www.soklet.com/docs/request-handling#path-parameters</a> for details.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface PathParameter {
	/**
	 * The name of the URL path parameter.
	 * <p>
	 * If blank, defaults to the name of the Java method parameter if your
	 * application is built with the {@code -parameters} compiler option.
	 *
	 * @return the name of the URL path parameter to inject into this <em>Resource Method</em> parameter
	 */
	@NonNull
	String name() default "";
}
