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

package com.soklet;

/**
 * Reasons an HTTP transport can reject incoming bytes before it has enough
 * valid information to construct a {@link Request}.
 * <p>
 * These values describe why request parsing stopped; they do not prescribe an
 * HTTP response status. A {@link ResponseMarshaler} may choose any final status
 * from {@code 200} through {@code 599} for an unparsed-request response.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public enum UnparsedRequestReason {
	/**
	 * The incoming bytes do not form a valid HTTP request.
	 * The default response marshaler conventionally uses HTTP 400.
	 */
	MALFORMED_REQUEST,
	/**
	 * The request target exceeds the transport's configured limit.
	 * The default response marshaler conventionally uses HTTP 414.
	 */
	REQUEST_TARGET_TOO_LONG,
	/**
	 * The request declares an expectation the transport cannot satisfy.
	 * The default response marshaler conventionally uses HTTP 417.
	 */
	EXPECTATION_FAILED,
	/**
	 * The request header section exceeds a configured transport limit.
	 * The default response marshaler conventionally uses HTTP 431.
	 */
	REQUEST_HEADERS_TOO_LARGE
}
