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

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Apply to <em>Resource Method</em> parameters to enable HTTP request body injection.
 * <p>
 * Refer to documentation at <a href="https://www.soklet.com/docs/request-handling#request-body">https://www.soklet.com/docs/request-handling#request-body</a> for details.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequestBody {
	/**
	 * Is this HTTP request body optional or required?
	 *
	 * @return {@code true} if optional, {@code false} if required
	 */
	boolean optional() default false;
}