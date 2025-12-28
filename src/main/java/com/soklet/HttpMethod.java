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
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Typesafe representation of <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Methods">HTTP request methods</a>.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public enum HttpMethod {
	/**
	 * The HTTP <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Methods/GET">{@code GET}</a> request method.
	 */
	GET,
	/**
	 * The HTTP <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Methods/POST">{@code POST}</a> request method.
	 */
	POST,
	/**
	 * The HTTP <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Methods/PUT">{@code PUT}</a> request method.
	 */
	PUT,
	/**
	 * The HTTP <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Methods/PATCH">{@code PATCH}</a> request method.
	 */
	PATCH,
	/**
	 * The HTTP <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Methods/OPTIONS">{@code OPTIONS}</a> request method.
	 */
	OPTIONS,
	/**
	 * The HTTP <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Methods/HEAD">{@code HEAD}</a> request method.
	 */
	HEAD,
	/**
	 * The HTTP <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Methods/DELETE">{@code DELETE}</a> request method.
	 */
	DELETE;

	@NonNull
	private static final Set<HttpMethod> VALUES_AS_SET;

	static {
		VALUES_AS_SET = Arrays.stream(HttpMethod.values()).collect(Collectors.toUnmodifiableSet());
	}

	/**
	 * Exposes {@link HttpMethod#values()} as a {@link Set} for convenience.
	 *
	 * @return a {@link Set} representation of this enum's values
	 */
	@NonNull
	public static Set<HttpMethod> valuesAsSet() {
		return VALUES_AS_SET;
	}
}