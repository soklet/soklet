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

import com.code_intelligence.jazzer.junit.FuzzTest;

import javax.annotation.concurrent.ThreadSafe;
import java.nio.charset.StandardCharsets;

/**
 * Fuzz target for Set-Cookie parsing and canonical re-rendering.
 */
@ThreadSafe
public class ResponseCookieFuzzTest {
	private static volatile int sink;

	@FuzzTest(maxDuration = "2m")
	public void fromSetCookieHeaderRepresentationOnlyRejectsWithIllegalArgumentException(byte[] input) {
		String headerValue = new String(input, StandardCharsets.UTF_8);

		try {
			ResponseCookie.fromSetCookieHeaderRepresentation(headerValue)
					.ifPresent(ResponseCookieFuzzTest::exercise);
		} catch (IllegalArgumentException expected) {
			// Malformed Set-Cookie values are expected. Other RuntimeExceptions and Errors are fuzz findings.
		}
	}

	private static void exercise(ResponseCookie cookie) {
		String rendered = cookie.toSetCookieHeaderRepresentation();

		// A cookie Soklet accepted and rendered should remain accepted by the parser.
		ResponseCookie.fromSetCookieHeaderRepresentation(rendered)
				.orElseThrow(() -> new AssertionError("Rendered Set-Cookie value did not parse: " + rendered));

		sink += cookie.getName().length()
				+ cookie.getValue().map(String::length).orElse(0)
				+ cookie.getMaxAge().map(Duration -> Long.hashCode(Duration.toSeconds())).orElse(0)
				+ cookie.getExpires().map(instant -> Long.hashCode(instant.getEpochSecond())).orElse(0)
				+ cookie.getDomain().map(String::length).orElse(0)
				+ cookie.getPath().map(String::length).orElse(0)
				+ (cookie.getSecure() ? 1 : 0)
				+ (cookie.getHttpOnly() ? 1 : 0)
				+ cookie.getSameSite().map(Enum::ordinal).orElse(0)
				+ cookie.getPriority().map(Enum::ordinal).orElse(0)
				+ (cookie.getPartitioned() ? 1 : 0)
				+ rendered.length()
				+ cookie.hashCode();
	}
}
