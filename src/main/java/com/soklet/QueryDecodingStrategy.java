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

import java.nio.charset.Charset;

/**
 * Strategies for decoding query strings - {@code Content-Type: application/x-www-form-urlencoded} (supports {@code "+"} for spaces) or "strict" RFC 3986 (percent-decoding only).
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 * @see com.soklet.Utilities#extractQueryParametersFromQuery(String, QueryDecodingStrategy)
 * @see com.soklet.Utilities#extractQueryParametersFromQuery(String, QueryDecodingStrategy, Charset)
 * @see com.soklet.Utilities#extractQueryParametersFromUrl(String, QueryDecodingStrategy)
 * @see com.soklet.Utilities#extractQueryParametersFromUrl(String, QueryDecodingStrategy, Charset)
 */
public enum QueryDecodingStrategy {
	/**
	 * Follow RFC 1866 (the {@code application/x-www-form-urlencoded} content type), where keys and values are percent-encoded but prefer {@code "+"} for spaces.
	 * <p>
	 * Note that {@code "%20"} values are still decoded as spaces, but any {@code "+"} values are decoded as spaces first.
	 */
	X_WWW_FORM_URLENCODED,
	/**
	 * Follow RFC 3986, where keys and values are percent-encoded and {@code "+"} values are never decoded as spaces.
	 */
	RFC_3986_STRICT
}