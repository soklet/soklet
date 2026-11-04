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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RequestCopyRawQueryTests {

	@Test
	void pathOnlyCopiesPreserveRawQuerySpellingAndInterleaving() {
		for (String prefix : List.of("/old", "https://example.test/old")) {
			for (String query : List.of("q=a+b&flag&x=%2a&y=1&x=%2A",
					"selected=a|b&tail=%c3%a9", "&", "&&")) {
				Request original = Request.fromRawUrl(HttpMethod.GET, prefix + "?" + query);
				Request.Copier copier = original.copy().path("/new path");
				Request rewritten = copier.finish();

				assertEquals("/new path", rewritten.getPath());
				assertEquals("/new%20path", rewritten.getRawPath());
				assertEquals(original.getRawQuery(), rewritten.getRawQuery());
				assertEquals("/new%20path?" + query, rewritten.getRawPathAndQuery());
				assertEquals(original.getQueryParameters(), rewritten.getQueryParameters());
				assertEquals(rewritten.getRawPathAndQuery(), copier.finish().getRawPathAndQuery());
				assertEquals(original.getRawQuery(), copier.path("/another").finish().getRawQuery());
			}
		}
	}

	@Test
	void pathOnlyCopiesKeepExistingAbsentAndEmptyQueryBehavior() {
		for (String target : List.of("/old", "/old?")) {
			Request original = Request.fromRawUrl(HttpMethod.GET, target);
			Request rewritten = original.copy().path("/new").finish();
			assertEquals(original.getRawQuery(), rewritten.getRawQuery());
			assertEquals("/new", rewritten.getRawPathAndQuery());
		}

		Request constructed = Request.withPath(HttpMethod.GET, "/old")
				.queryParameters(Map.of("q", List.of("a+b"))).build();
		assertEquals("q=a%2Bb", constructed.getRawQuery().orElseThrow());
		assertEquals(constructed.getRawQuery(), constructed.copy().path("/new").finish().getRawQuery());
	}

	@Test
	void explicitQueryChangesStillReplaceAndClearTheRawQueryInEitherMutationOrder() {
		for (boolean pathFirst : List.of(false, true)) {
			Request original = Request.fromRawUrl(HttpMethod.GET, "/old?q=a+b&flag&x=%2a&y=1&x=%2A");
			Request.Copier copier = original.copy();
			if (pathFirst) copier.path("/new");
			copier.queryParameters(Map.of("updated", List.of("new+value")));
			if (!pathFirst) copier.path("/new");

			Request rewritten = copier.finish();
			assertEquals("/new?updated=new%2Bvalue", rewritten.getRawPathAndQuery());
			assertEquals(Map.of("updated", List.of("new+value")), rewritten.getQueryParameters());
			assertEquals(rewritten.getRawPathAndQuery(), copier.finish().getRawPathAndQuery());
			assertEquals("/new", copier.queryParameters(Map.of()).finish().getRawPathAndQuery());
			assertEquals("/new", copier.queryParameters((Map<String, List<String>>) null).finish().getRawPathAndQuery());
		}
	}
}
