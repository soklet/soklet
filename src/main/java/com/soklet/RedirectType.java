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

import static java.util.Objects.requireNonNull;

/**
 * Typesafe representation of <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Redirections">HTTP Redirect</a> types ({@code 301, 302, 303, 307, 308}).
 * <p>
 * Detailed documentation is available at <a href="https://www.soklet.com/docs/response-writing#redirects">https://www.soklet.com/docs/response-writing#redirects</a>.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public enum RedirectType {
	/**
	 * Represents <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/301">{@code 301 Moved Permanently}</a>.
	 */
	HTTP_301_MOVED_PERMANENTLY(StatusCode.HTTP_301),
	/**
	 * Represents <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/302">{@code 302 Found}</a>.
	 */
	HTTP_302_FOUND(StatusCode.HTTP_302),
	/**
	 * Represents <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/303">{@code 303 See Other}</a>.
	 */
	HTTP_303_SEE_OTHER(StatusCode.HTTP_303),
	/**
	 * Represents <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/307">{@code 307 Temporary Redirect}</a>.
	 */
	HTTP_307_TEMPORARY_REDIRECT(StatusCode.HTTP_307),
	/**
	 * Represents <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/308">{@code 308 Permanent Redirect}</a>.
	 */
	HTTP_308_PERMANENT_REDIRECT(StatusCode.HTTP_308);

	@NonNull
	private final StatusCode statusCode;

	RedirectType(@NonNull StatusCode statusCode) {
		requireNonNull(statusCode);
		this.statusCode = statusCode;
	}

	/**
	 * The HTTP status code for this redirect type.
	 *
	 * @return the status code
	 */
	@NonNull
	public StatusCode getStatusCode() {
		return this.statusCode;
	}
}
