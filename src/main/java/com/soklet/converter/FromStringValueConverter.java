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

package com.soklet.converter;

import javax.annotation.concurrent.ThreadSafe;

/**
 * Convenience superclass which provides default implementations of {@link ValueConverter} methods that convert from {@link String} to other types.
 * <p>
 * For example:
 * <pre>{@code  public record Jwt(
 *   String header,
 *   String payload,
 *   String signature
 * ) {}
 *
 * ValueConverter<String, Jwt> jwtVc = new FromStringValueConverter<>() {
 *   @Nonnull
 *   public Optional<Jwt> performConversion(@Nullable String from) throws Exception {
 *     if(from == null)
 *       return Optional.empty();
 *
 *     // JWT is of the form "a.b.c", break it into pieces
 *     String[] components = from.split("\\.");
 *     Jwt jwt = new Jwt(components[0], components[1], components[2]);
 *     return Optional.of(jwt);
 *   }
 * };}</pre>
 * See detailed documentation at <a href="https://www.soklet.com/docs/value-conversions#custom-conversions">https://www.soklet.com/docs/value-conversions#custom-conversions</a>.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public abstract class FromStringValueConverter<T> extends AbstractValueConverter<String, T> {
	/**
	 * Ensures that the 'from' type of this converter is {@link String}.
	 */
	public FromStringValueConverter() {
		super(String.class);
	}
}