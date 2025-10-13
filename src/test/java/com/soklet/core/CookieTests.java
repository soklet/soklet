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

import com.soklet.ResponseCookie;
import com.soklet.ResponseCookie.SameSite;
import com.soklet.Utilities;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.annotation.concurrent.ThreadSafe;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class CookieTests {
	@Test
	public void cookieHeaderTests() {
		Map<String, Set<String>> happyPath = Utilities.extractCookiesFromHeaders(Map.of(
				"Cookie", Set.of("a=1")
		));

		Assertions.assertEquals(Set.of("1"), happyPath.get("a"));

		Map<String, Set<String>> multipleValues = Utilities.extractCookiesFromHeaders(Map.of(
				"Cookie", Set.of("a=1; b=2; a=2;")
		));

		Assertions.assertEquals(Set.of("1", "2"), multipleValues.get("a"));
		Assertions.assertEquals(Set.of("2"), multipleValues.get("b"));

		Map<String, Set<String>> blank = Utilities.extractCookiesFromHeaders(Map.of(
				"Cookie", Set.of()
		));

		Assertions.assertEquals(Set.of(), blank.keySet());

		Map<String, Set<String>> missingValue = Utilities.extractCookiesFromHeaders(Map.of(
				"Cookie", Set.of("a=")
		));

		Assertions.assertEquals(Set.of(), missingValue.get("a"));
	}

	@Test
	public void setCookieHeaderRepresentationTests() {
		ResponseCookie basicResponseCookie = ResponseCookie.with("name", "value").build();
		Assertions.assertEquals("name=value", basicResponseCookie.toSetCookieHeaderRepresentation());

		ResponseCookie valuelessResponseCookie = ResponseCookie.withName("name").build();
		Assertions.assertEquals("name=", valuelessResponseCookie.toSetCookieHeaderRepresentation());

		ResponseCookie everythingResponseCookie = ResponseCookie.with("name", "value")
				.domain("www.soklet.com")
				.path("/")
				.httpOnly(true)
				.secure(true)
				.maxAge(Duration.ofSeconds(3600))
				.sameSite(SameSite.STRICT)
				.build();

		Assertions.assertEquals("name=value; Path=/; Domain=www.soklet.com; Max-Age=3600; Secure; HttpOnly; SameSite=Strict",
				everythingResponseCookie.toSetCookieHeaderRepresentation());
	}

	@Test
	public void cookieRejectsCrLfInName() {
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				ResponseCookie.with("bad\r\nName", "v").build());
	}

	@Test
	public void cookieRejectsCrLfInValue() {
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				ResponseCookie.with("name", "v\r\nInjected: evil").build());
	}

	@Test
	public void sameSiteNoneRequiresSecure() {
		Assertions.assertThrows(IllegalArgumentException.class, () -> {
			ResponseCookie ignored = ResponseCookie.with("sid", "v")
					.sameSite(SameSite.NONE)
					// .secure(true) // omitted on purpose
					.build();
		}, "SameSite=None must also set Secure");
	}
}
