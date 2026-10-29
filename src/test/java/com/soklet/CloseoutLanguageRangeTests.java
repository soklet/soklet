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

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloseoutLanguageRangeTests {

	@Test
	void emptyListMembersDoNotDiscardValidPreferences() {
		for (String value : List.of("en,,fr", ",en, \t,fr,", " \t,en,fr, \t"))
			assertEquals(List.of(Locale.ENGLISH, Locale.FRENCH),
					Utilities.extractLocalesFromAcceptLanguageHeaderValue(value), value);
		assertEquals(List.of(Locale.FRENCH, Locale.ENGLISH),
				Utilities.extractLocalesFromAcceptLanguageHeaderValue(",en;q=0.5,,fr;q=1,"));
	}

	@Test
	void emptyMembersPreserveWeightsAndDeduplication() {
		assertEquals(List.of(Locale.FRENCH),
				Utilities.extractLocalesFromAcceptLanguageHeaderValue("en;q=0,,fr,fr;q=0.5,"));
		assertTrue(Utilities.extractLocalesFromAcceptLanguageHeaderValue(", \t,,").isEmpty());
		assertTrue(Utilities.extractLocalesFromAcceptLanguageHeaderValue("fr,,x_bad,en").isEmpty());
	}

	@Test
	void repeatedPhysicalHeadersPreserveBothRequestViews() {
		Request request = Request.withPath(HttpMethod.GET, "/")
				.headers(Map.of("Accept-Language", List.of("en,", " \t", ",fr"))).build();
		assertEquals(List.of(Locale.ENGLISH, Locale.FRENCH), request.getLocales());
		assertEquals(List.of("en", "fr"), request.getLanguageRanges().stream()
				.map(Locale.LanguageRange::getRange).toList());
	}

	@Test
	void emptyMembersCannotBypassExistingWorkBounds() {
		assertEquals(List.of(Locale.ENGLISH),
				Utilities.extractLocalesFromAcceptLanguageHeaderValue(",".repeat(31) + "en"));
		assertTrue(Utilities.extractLocalesFromAcceptLanguageHeaderValue(",".repeat(32) + "en").isEmpty());
		assertTrue(Utilities.extractLocalesFromAcceptLanguageHeaderValue(" ".repeat(4_096) + "en").isEmpty());
	}
}
