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

package com.soklet.core;

import com.soklet.converter.ValueConverterRegistry;

import javax.annotation.Nonnull;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Contract for converting request body bytes into a corresponding Java type.
 * <p>
 * For example, if your <em>Resource Methods</em> expect JSON request bodies like this (note the {@link com.soklet.annotation.RequestBody} annotation):
 * <pre>{@code  @POST("/find-biggest")
 * public Integer findBiggest(@RequestBody List<Integer> numbers) {
 *   // JSON request body [1,2,3] results in 3 being returned
 *   return Collections.max(numbers);
 * }}</pre>
 * <p>
 * You might implement a {@link RequestBodyMarshaler} to accept JSON like this:
 * <pre>{@code  SokletConfiguration config = SokletConfiguration.withServer(
 *   DefaultServer.withPort(8080).build()
 * ).requestBodyMarshaler(new RequestBodyMarshaler() {
 *   // This example uses Google's GSON
 *   static final Gson GSON = new Gson();
 *
 *   @Nonnull
 *   @Override
 *   public Optional<Object> marshalRequestBody(
 *     @Nonnull Request request,
 *     @Nonnull ResourceMethod resourceMethod,
 *     @Nonnull Parameter parameter,
 *     @Nonnull Type requestBodyType
 *   ) {
 *     // Let GSON turn the request body into an instance
 *     // of the specified type.
 *     //
 *     // Note that this method has access to all runtime information
 *     // about the request, which provides the opportunity to, for example,
 *     // examine annotations on the method/parameter which might
 *     // inform custom marshaling strategies.
 *     return Optional.of(GSON.fromJson(
 *       request.getBodyAsString().get(),
 *       requestBodyType
 *     ));
 *   }
 * }).build();}</pre>
 * <p>
 * Standard implementations can also be acquired via these factory methods:
 * <ul>
 *   <li>{@link #withDefaults()}</li>
 *   <li>{@link #withValueConverterRegistry(ValueConverterRegistry)}</li>
 * </ul>
 * <p>
 * See <a href="https://www.soklet.com/docs/request-handling#request-body">https://www.soklet.com/docs/request-handling#request-body</a> for detailed documentation.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@FunctionalInterface
public interface RequestBodyMarshaler {
	/**
	 * Given a request, the <em>Resource Method</em> that will handle it, and a {@link com.soklet.annotation.RequestBody}-annotated parameter + its type, convert the request body bytes into an instance of type {@code requestBodyType}.
	 * <p>
	 * This instance will be injected by Soklet when it invokes the <em>Resource Method</em> to handle the request.
	 *
	 * @param request         the request whose body should be converted into a Java type
	 * @param resourceMethod  the <em>Resource Method</em> that is configured to handle the request
	 * @param parameter       the <em>Resource Method</em> parameter into which the returned instance will be injected
	 * @param requestBodyType the type of the <em>Resource Method</em> parameter (provided for convenience)
	 * @return the Java instance that corresponds to the request body bytes suitable for assignment to the <em>Resource Method</em> parameter, or {@link Optional#empty()} if no instance should be marshaled
	 */
	@Nonnull
	Optional<Object> marshalRequestBody(@Nonnull Request request,
																			@Nonnull ResourceMethod resourceMethod,
																			@Nonnull Parameter parameter,
																			@Nonnull Type requestBodyType);

	/**
	 * Acquires a basic {@link RequestBodyMarshaler} which knows how to convert request body data using {@link ValueConverterRegistry#sharedInstance()}.
	 * <p>
	 * You will likely want to provide your own implementation of {@link RequestBodyMarshaler} instead if your system accepts, for example, JSON request bodies.
	 * <p>
	 * Callers should not rely on reference identity; this method may return a new or cached instance.
	 *
	 * @return a {@code RequestBodyMarshaler} with default settings
	 */
	@Nonnull
	static RequestBodyMarshaler withDefaults() {
		return DefaultRequestBodyMarshaler.defaultInstance();
	}

	/**
	 * Acquires a basic {@link RequestBodyMarshaler} which knows how to convert request body data using the provided {@link ValueConverterRegistry}.
	 * <p>
	 * You will likely want to provide your own implementation of {@link RequestBodyMarshaler} instead if your system accepts, for example, JSON request bodies.
	 * <p>
	 * Callers should not rely on reference identity; this method may return a new or cached instance.
	 *
	 * @param valueConverterRegistry a registry of converters that can transform {@link String} types to arbitrary Java types
	 * @return a default {@code RequestBodyMarshaler} backed by the given {@link ValueConverterRegistry}
	 */
	@Nonnull
	static RequestBodyMarshaler withValueConverterRegistry(@Nonnull ValueConverterRegistry valueConverterRegistry) {
		requireNonNull(valueConverterRegistry);
		return new DefaultRequestBodyMarshaler(valueConverterRegistry);
	}
}