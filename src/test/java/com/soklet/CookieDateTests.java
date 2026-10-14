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

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CookieDateTests {
	@Test
	void expiryUsesTheFixedCookieCenturyAndIgnoresWeekdays() {
		for (int year = 0; year < 100; year++) {
			int fullYear = year >= 70 ? 1900 + year : 2000 + year;
			Instant expected = Instant.parse(fullYear + "-01-01T00:00:00Z");
			for (String date : List.of(String.format(java.util.Locale.ROOT, "Thu, 01 Jan %02d 00:00:00 GMT", year),
					String.format(java.util.Locale.ROOT, "Thursday, 01-Jan-%02d 00:00:00 GMT", year))) {
				ResponseCookie cookie = ResponseCookie.fromSetCookieHeaderRepresentation("a=; Expires=" + date + "; Path=/").orElseThrow();
				assertEquals(expected, cookie.getExpires().orElseThrow(), date);
				assertTrue(cookie.getMaxAge().isEmpty(), "Expires must not synthesize a conflicting Max-Age: " + date);
				assertTrue(cookie.toSetCookieHeaderRepresentation().contains("Expires=" + HttpDate.toHeaderValue(expected)));
			}
		}
	}

	@Test
	void cookieDateTokensAreValidatedWithoutHttpDateWeekdayOrZoneRules() {
		for (String date : List.of("Wed Oct 21 07:28:00 2015", "WRONG, 21-Oct-2015 7:28:0 GMT",
				"21/Oct/2015 07:28:00", "07:28:00 21 October 2015"))
			assertEquals(Instant.parse("2015-10-21T07:28:00Z"), CookieDate.parse(date).orElseThrow(), date);
		for (String date : List.of("01 Jan 1600 00:00:00", "31 Feb 2026 00:00:00", "29 Feb 2100 00:00:00",
				"01 Jan 2026 24:00:00", "01 Jan 2026 00:60:00", "01 Jan 2026 00:00:60", "01 Jan 2026", ""))
			assertTrue(CookieDate.parse(date).isEmpty(), date);
		assertEquals(Instant.parse("2000-02-29T00:00:00Z"), CookieDate.parse("29 Feb 00 00:00:00").orElseThrow());
	}

	@Test
	void explicitNegativeMaxAgeExpiresTheCookieRegardlessOfAttributeOrder() {
		for (String age : List.of("-1", "-5", "-9223372036854775808")) {
			for (String attributes : List.of("Max-Age=" + age + "; Expires=Thu, 01 Jan 70 00:00:00 GMT",
					"Expires=Thu, 01 Jan 70 00:00:00 GMT; Max-Age=" + age,
					"Max-Age=" + age + "; Expires=Thu, 01 Jan 69 00:00:00 GMT",
					"Expires=Thu, 01 Jan 69 00:00:00 GMT; Max-Age=" + age, "Max-Age=" + age)) {
				ResponseCookie cookie = ResponseCookie.fromSetCookieHeaderRepresentation("a=; " + attributes).orElseThrow();
				assertEquals(Duration.ZERO, cookie.getMaxAge().orElseThrow(), attributes);
				assertTrue(cookie.toSetCookieHeaderRepresentation().contains("Max-Age=0"));
			}
		}
		assertTrue(ResponseCookie.fromSetCookieHeaderRepresentation("a=b").orElseThrow().getMaxAge().isEmpty());
	}

	@Test
	void explicitMaxAgeRemainsIndependentOfExpires() {
		for (String attributes : List.of("Expires=Thu, 01 Jan 70 00:00:00 GMT; Max-Age=3600",
				"Max-Age=3600; Expires=Thu, 01 Jan 70 00:00:00 GMT"))
			assertEquals(Duration.ofHours(1), ResponseCookie.fromSetCookieHeaderRepresentation("a=b; " + attributes)
					.orElseThrow().getMaxAge().orElseThrow());
		assertTrue(ResponseCookie.fromSetCookieHeaderRepresentation("a=b; Expires=invalid").orElseThrow().getMaxAge().isEmpty());
	}

	@Test
	void cookieAttributesTrimTabsAndDoNotTreatStrayQuotesAsDelimiters() {
		for (String header : List.of("a=;\tMax-Age=0; Path=/", "a=; Path=/;\tMax-Age=0",
				"a=; HttpOnly;\tMax-Age=0", "a=; Foo=\"bar; Max-Age=0", "a=; Foo=bar\\; Max-Age=0",
				"a=;\t Max-Age \t=\t 0\t ; Path=/", "a=;\u00a0\tMax-Age=0\t\u00a0; Path=/")) {
			ResponseCookie cookie = ResponseCookie.fromSetCookieHeaderRepresentation(header).orElseThrow();
			assertEquals(Duration.ZERO, cookie.getMaxAge().orElseThrow(), header);
			assertTrue(cookie.toSetCookieHeaderRepresentation().contains("Max-Age=0"), header);
		}
		assertEquals(Duration.ofSeconds(60), ResponseCookie.fromSetCookieHeaderRepresentation(
				"a=b; Max-Age=60\t; Path=/").orElseThrow().getMaxAge().orElseThrow());
		ResponseCookie cookie = ResponseCookie.fromSetCookieHeaderRepresentation(
				"a=;\tExpires=Thu, 01 Jan 1970 00:00:00 GMT\t; Path=/").orElseThrow();
		assertEquals(Instant.EPOCH, cookie.getExpires().orElseThrow());
		assertTrue(cookie.getMaxAge().isEmpty());
	}

	@Test
	void invalidMaxAgeIsIgnoredWithoutDiscardingTheCookieOrItsExpiry() {
		for (String age : List.of("", "-", "+60", "60seconds", "6 0", "5, c=d", "999999999999999999999x")) {
			ResponseCookie cookie = ResponseCookie.fromSetCookieHeaderRepresentation(
					"a=b; Max-Age=" + age + "; Expires=Thu, 01 Jan 70 00:00:00 GMT; Path=/; HttpOnly").orElseThrow();
			assertEquals("a", cookie.getName());
			assertEquals("b", cookie.getValue().orElseThrow());
			assertTrue(cookie.getMaxAge().isEmpty(), age);
			assertEquals(Instant.EPOCH, cookie.getExpires().orElseThrow(), age);
			assertEquals("/", cookie.getPath().orElseThrow(), age);
			assertTrue(cookie.getHttpOnly(), age);
		}
		assertEquals(Duration.ZERO, ResponseCookie.fromSetCookieHeaderRepresentation(
				"a=; Max-Age=invalid; Max-Age=0").orElseThrow().getMaxAge().orElseThrow());
	}

	@Test
	void veryLargeMaxAgesSaturateWithoutOverflow() {
		assertEquals(Duration.ofSeconds(Long.MAX_VALUE), ResponseCookie.fromSetCookieHeaderRepresentation(
				"a=b; Max-Age=999999999999999999999").orElseThrow().getMaxAge().orElseThrow());
		assertEquals(Duration.ZERO, ResponseCookie.fromSetCookieHeaderRepresentation(
				"a=; Max-Age=-999999999999999999999").orElseThrow().getMaxAge().orElseThrow());
	}
}
