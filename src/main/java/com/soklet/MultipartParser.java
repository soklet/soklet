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

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.Set;

/**
 * Contract for parsing HTML form fields encoded according to the <a href="https://datatracker.ietf.org/doc/html/rfc7578">{@code multipart/form-data}</a> specification.
 * <p>
 * A standard threadsafe implementation can be acquired via the {@link #defaultInstance()} factory method.
 * <p>
 * See <a href="https://www.soklet.com/docs/request-handling#multipart-form-data">https://www.soklet.com/docs/request-handling#multipart-form-data</a> for detailed documentation.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@FunctionalInterface
public interface MultipartParser {
	/**
	 * Given a request, detect all HTML form fields with <a href="https://datatracker.ietf.org/doc/html/rfc7578">{@code multipart/form-data}</a> encoding and parse their values.
	 *
	 * @param request the request to parse
	 * @return a mapping of form field names to corresponding sets of form field values
	 */
	@Nonnull
	Map<String, Set<MultipartField>> extractMultipartFields(@Nonnull Request request);

	/**
	 * Acquires a threadsafe {@link MultipartParser}.
	 * <p>
	 * The returned instance is guaranteed to be a JVM-wide singleton.
	 *
	 * @return a {@code MultipartParser} instance
	 */
	@Nonnull
	static MultipartParser defaultInstance() {
		return DefaultMultipartParser.defaultInstance();
	}
}
