/*
 * Copyright 2022 Revetware LLC.
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

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public enum StatusCode {
	HTTP_100(100, "Continue"),
	HTTP_101(101, "Switching Protocols"),
	HTTP_200(200, "OK"),
	HTTP_201(201, "Created"),
	HTTP_202(202, "Accepted"),
	HTTP_203(203, "Non-Authoritative Information"),
	HTTP_204(204, "No Content"),
	HTTP_205(205, "Reset Content"),
	HTTP_206(206, "Partial Content"),
	HTTP_300(300, "Multiple Choices"),
	HTTP_301(301, "Moved Permanently"),
	HTTP_302(302, "Found"),
	HTTP_303(303, "See Other"),
	HTTP_304(304, "Not Modified"),
	HTTP_305(305, "Use Proxy"),
	HTTP_307(307, "Temporary Redirect"),
	HTTP_308(308, "Permanent Redirect"),
	HTTP_400(400, "Bad Request"),
	HTTP_401(401, "Unauthorized"),
	HTTP_402(402, "Payment Required"),
	HTTP_403(403, "Forbidden"),
	HTTP_404(404, "Not Found"),
	HTTP_405(405, "Method Not Allowed"),
	HTTP_406(406, "Not Acceptable"),
	HTTP_407(407, "Proxy Authentication Required"),
	HTTP_408(408, "Request Timeout"),
	HTTP_409(409, "Conflict"),
	HTTP_410(410, "Gone"),
	HTTP_411(411, "Length Required"),
	HTTP_412(412, "Precondition Failed"),
	HTTP_413(413, "Content Too Large"),
	HTTP_414(414, "URI Too Long"),
	HTTP_415(415, "Unsupported Media Type"),
	HTTP_416(416, "Range Not Satisfiable"),
	HTTP_417(417, "Expectation Failed"),
	HTTP_421(421, "Misdirected Request"),
	HTTP_422(422, "Unprocessable Content"),
	HTTP_426(426, "Upgrade Required"),
	HTTP_500(500, "Internal Server Error"),
	HTTP_501(501, "Not Implemented"),
	HTTP_502(502, "Bad Gateway"),
	HTTP_503(503, "Service Unavailable"),
	HTTP_504(504, "Gateway Timeout"),
	HTTP_505(505, "HTTP Version not supported");

	@Nonnull
	private static final Map<Integer, StatusCode> STATUS_CODES_BY_NUMBER;

	static {
		Map<Integer, StatusCode> statusCodesByNumber = new HashMap<>();

		for (StatusCode statusCode : StatusCode.values())
			statusCodesByNumber.put(statusCode.getStatusCode(), statusCode);

		STATUS_CODES_BY_NUMBER = Collections.unmodifiableMap(statusCodesByNumber);
	}

	@Nonnull
	private final Integer statusCode;
	@Nonnull
	private final String reasonPhrase;

	StatusCode(@Nonnull Integer statusCode,
						 @Nonnull String reasonPhrase) {
		requireNonNull(statusCode);
		requireNonNull(reasonPhrase);

		this.statusCode = statusCode;
		this.reasonPhrase = reasonPhrase;
	}

	@Nonnull
	public static Optional<StatusCode> fromStatusCode(@Nonnull Integer statusCode) {
		return Optional.ofNullable(STATUS_CODES_BY_NUMBER.get(statusCode));
	}

	@Override
	public String toString() {
		return format("%s.%s{statusCode=%s, reasonPhrase=%s}", getClass().getSimpleName(), name(), getStatusCode(), getReasonPhrase());
	}

	@Nonnull
	public Integer getStatusCode() {
		return this.statusCode;
	}

	@Nonnull
	public String getReasonPhrase() {
		return this.reasonPhrase;
	}
}
