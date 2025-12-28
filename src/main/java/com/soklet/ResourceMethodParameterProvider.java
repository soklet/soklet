/*
 * Copyright 2022-2025 Revetware LLC.
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

package com.soklet;

import org.jspecify.annotations.NonNull;
import java.util.List;

/**
 * Contract for determining parameter values to inject when invoking <em>Resource Methods</em>.
 * <p>
 * It is unusual for applications to provide their own {@link ResourceMethodParameterProvider} implementations.
 * <p>
 * However, should it be necessary, documentation is available at <a href="https://www.soklet.com/docs/request-handling#resource-method-parameter-injection">https://www.soklet.com/docs/request-handling#resource-method-parameter-injection</a>.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@FunctionalInterface
public interface ResourceMethodParameterProvider {
	/**
	 * For the given {@code request} and {@code resourceMethod}, vends the list of parameters to use when invoking the
	 * underlying Java method located at {@link ResourceMethod#getMethod()}.
	 * <p>
	 * The size of the returned list of parameters must exactly match the number of parameters required by the Java method signature.
	 *
	 * @param request        the HTTP request
	 * @param resourceMethod the <em>Resource Method</em> associated with the HTTP request
	 * @return the list of parameters to use when performing Java method invocation, or the empty list if no parameters are necessary
	 */
	@NonNull
	List<Object> parameterValuesForResourceMethod(@NonNull Request request,
																								@NonNull ResourceMethod resourceMethod);
}