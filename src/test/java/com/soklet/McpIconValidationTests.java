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

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Public icon declaration validation without fetching or interpreting icons. */
class McpIconValidationTests {
	private static final URI SOURCE = URI.create("https://icons.example/icon.png");
	private static final String CANARY = "private-icon-canary";

	@Test
	void relativeSourcesAreRejectedAtTheFactoryWithoutRetainingTheUri() {
		for (String source : List.of("", CANARY + ".png", "/" + CANARY,
				"//icons.example/" + CANARY, "../" + CANARY, "#" + CANARY))
			assertPrivateRejection(() -> McpIcon.withSource(URI.create(source)));
	}

	@Test
	void absoluteSourcesKeepTheirSpellingWithoutNormalizationOrSchemePolicy() {
		for (String source : List.of("HTTPS://Icons.Example/a/../icon%2f.png?theme=dark#preview",
				"http://127.0.0.1:8080/icon.png", "data:image/png;base64,AA==",
				"urn:example:icon", "custom-icon://catalog/preview", "file:///tmp/icon.png",
				"javascript:opaque-test-value")) {
			URI uri = URI.create(source);
			assertSame(uri, McpIcon.withSource(uri).build().getSource());
			assertEquals(source, McpIcon.withSource(uri).build().getSource().toString());
		}
	}

	@Test
	void malformedSourceUnicodeFailsEarlyWithoutRetainingTheUri() {
		assertPrivateRejection(() -> McpIcon.withSource(URI.create("https://icons.example/" + CANARY + "\uD800")));
	}

	@Test
	void invalidMimeTypesFailAtTheSetterAndLeaveThePriorValueIntact() {
		McpIcon.Builder builder = McpIcon.withSource(SOURCE).mimeType("image/png");
		for (String mimeType : List.of("", " ", CANARY, "image/", "image/png;name=",
				"image/png;name=\"" + CANARY, "image/png;option=first;OPTION=second",
				"image/png\r\n" + CANARY, "image/" + CANARY + "\uD800")) {
			assertPrivateRejection(() -> builder.mimeType(mimeType));
			assertEquals("image/png", builder.build().getMimeType().orElseThrow());
		}
	}

	@Test
	void validMimeTypesRemainExactAndAreNotRestrictedToAnImageRegistry() {
		for (String mimeType : List.of("IMAGE/PNG", "image/svg+xml; Name=\"Quoted; value\"",
				" image / x-vendor ; Style = \"a\\\"b\" ", "application/octet-stream"))
			assertEquals(mimeType, McpIcon.withSource(SOURCE).mimeType(mimeType).build().getMimeType().orElseThrow());
	}

	@Test
	void invalidSizesRejectTheEntireReplacementAndPreservePriorValues() {
		McpIcon.Builder builder = McpIcon.withSource(SOURCE).sizes(List.of("48x48", "any"));
		for (String size : List.of("", " ", CANARY, "ANY", "48", "x48", "48x",
				"48X48", "48x48x48", "-1x48", "+1x48", "1.5x48", "48 x48",
				"48x48 ", "４８x４８", "48x\uD800")) {
			assertPrivateRejection(() -> builder.sizes(List.of("64x64", size)));
			assertEquals(List.of("48x48", "any"), builder.build().getSizes());
		}
	}

	@Test
	void validSizesPreserveOrderDuplicatesAndExactDecimalSpelling() {
		List<String> sizes = new ArrayList<>(List.of("001x02", "any", "001x02",
				"0x0", "999999999999999999999999999999x1"));
		McpIcon.Builder builder = McpIcon.withSource(SOURCE).sizes(sizes);
		sizes.clear();
		McpIcon first = builder.build();
		assertEquals(List.of("001x02", "any", "001x02", "0x0", "999999999999999999999999999999x1"), first.getSizes());
		assertThrows(UnsupportedOperationException.class, () -> first.getSizes().add("32x32"));
		builder.sizes(List.of("32x32"));
		assertEquals(5, first.getSizes().size());
		assertEquals(List.of("32x32"), builder.build().getSizes());
		assertTrue(builder.sizes(null).build().getSizes().isEmpty());
		assertTrue(builder.sizes(List.of()).build().getSizes().isEmpty());
	}

	@Test
	void omissionsAndNullContractsRemainUnchangedAndReplacementsAreAtomic() {
		McpIcon empty = McpIcon.withSource(SOURCE).build();
		assertTrue(empty.getMimeType().isEmpty());
		assertTrue(empty.getSizes().isEmpty());
		assertTrue(empty.getTheme().isEmpty());
		assertThrows(NullPointerException.class, () -> McpIcon.withSource(null));
		McpIcon.Builder builder = McpIcon.withSource(SOURCE).mimeType("image/png")
				.sizes(List.of("48x48")).theme(McpIconTheme.DARK);
		assertThrows(NullPointerException.class, () -> builder.mimeType(null));
		assertThrows(NullPointerException.class, () -> builder.sizes(Arrays.asList("64x64", null)));
		assertThrows(NullPointerException.class, () -> builder.theme(null));
		assertEquals("image/png", builder.build().getMimeType().orElseThrow());
		assertEquals(List.of("48x48"), builder.build().getSizes());
		assertEquals(McpIconTheme.DARK, builder.build().getTheme().orElseThrow());
	}

	private static void assertPrivateRejection(Runnable action) {
		IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, action::run);
		assertFalse(exception.getMessage().contains(CANARY));
		assertNull(exception.getCause());
	}
}
