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

import com.soklet.converter.ValueConverterRegistry;

import javax.annotation.Nonnull;
import java.util.List;

/**
 * Contract for determining parameter values to inject when invoking <em>Resource Methods</em>.
 * <p>
 * Standard implementations can also be acquired via these factory methods:
 * <ul>
 *   <li>{@link #withDefaults()} (sufficient for most applications)</li>
 *   <li>{@link #with(InstanceProvider, ValueConverterRegistry, RequestBodyMarshaler)} </li>
 * </ul>
 * <p>
 * However, should a custom implementation be necessary for your application, documentation is available at <a href="https://www.soklet.com/docs/request-handling#resource-method-parameter-injection">https://www.soklet.com/docs/request-handling#resource-method-parameter-injection</a>.
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
	@Nonnull
	List<Object> parameterValuesForResourceMethod(@Nonnull Request request,
																								@Nonnull ResourceMethod resourceMethod);

	/**
	 * Acquires a basic {@link ResourceMethodParameterProvider} with sensible defaults.
	 * <p>
	 * Callers should not rely on reference identity; this method may return a new or cached instance.
	 *
	 * @return a {@code ResourceMethodParameterProvider} with default settings
	 */
	@Nonnull
	static ResourceMethodParameterProvider withDefaults() {
		return DefaultResourceMethodParameterProvider.defaultInstance();
	}

	/**
	 * Acquires a basic {@link ResourceMethodParameterProvider} with sensible defaults.
	 * <p>
	 * Callers should not rely on reference identity; this method may return a new or cached instance.
	 *
	 * @param instanceProvider       controls how the parameter provider creates instances of objects
	 * @param valueConverterRegistry controls how the parameter provider converts values from strings to Java types expected by the <em>Resource Method</em>
	 * @param requestBodyMarshaler   controls how parameters annotated with {@link com.soklet.annotation.RequestBody} are converted to the Java type expected by the <em>Resource Method</em>
	 * @return a {@code ResourceMethodParameterProvider} with default settings
	 */
	@Nonnull
	static ResourceMethodParameterProvider with(@Nonnull InstanceProvider instanceProvider,
																							@Nonnull ValueConverterRegistry valueConverterRegistry,
																							@Nonnull RequestBodyMarshaler requestBodyMarshaler) {
		return new DefaultResourceMethodParameterProvider(instanceProvider, valueConverterRegistry, requestBodyMarshaler);
	}
}