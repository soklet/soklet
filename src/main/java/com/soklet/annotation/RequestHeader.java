/*
 * Copyright 2022-2024 Revetware LLC.
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

import javax.annotation.Nullable;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Apply to Resource Method parameters to enable HTTP request header injection.
 * <p>
 * Refer to documentation at <a href="https://www.soklet.com/docs/request-handling#headers">https://www.soklet.com/docs/request-handling#headers</a> for details.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequestHeader {
	/**
	 * The name of the HTTP request header.
	 * <p>
	 * If {@code null} or blank, defaults to the name of the Java method parameter if your application is built with the {@code -parameters} compiler option.
	 *
	 * @return the name of the HTTP request header to inject into this Resource Method parameter
	 */
	@Nullable
	String name() default "";

	/**
	 * Is this HTTP request header optional or required?
	 *
	 * @return {@code true} if optional, {@code false} if required
	 */
	boolean optional() default false;
}