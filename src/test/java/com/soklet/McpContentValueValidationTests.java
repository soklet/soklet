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
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Public construction boundaries for content and JSON scalar values. */
class McpContentValueValidationTests {
	private static final URI RESOURCE = URI.create("test://content/value");
	private static final String CANARY = "private-content-canary";

	@Test
	void malformedUtf16FailsAtThePublicFactoryOrSetterWithoutRetainingInput() {
		List<Consumer<String>> factories = List.of(
				McpTextContent::fromText,
				McpTextContent::withText,
				value -> McpTextResourceContents.withUriAndText(RESOURCE, value),
				McpJsonString::fromValue,
				value -> McpJsonObject.fromMembers(Map.of(value, McpJsonNull.INSTANCE)),
				value -> McpJsonObject.builder().put(value, true),
				value -> McpPromptOutput.builder().description(value),
				value -> McpArgumentCompletionResult.withValues(List.of("valid", value)));
		for (String malformed : List.of("\uD800", "\uDC00", "\uDC00\uD800",
				"\uD800\uD800", "\uDC00\uDC00", "\uD800x", "x\uDC00",
				"😀\uD800", "\uDC00😀")) {
			for (Consumer<String> factory : factories) {
				IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
						() -> factory.accept(CANARY + malformed + CANARY));
				assertFalse(exception.getMessage().contains(CANARY));
				assertNull(exception.getCause());
			}
		}
	}

	@Test
	void wellFormedTextRemainsExactIncludingControlsSupplementaryCharactersAndEmptyValues() {
		for (String value : List.of("", "\u0000\n\t", "😀𐀀\uDBFF\uDFFF",
				"e\u0301", "é", " العربية 日本語 ")) {
			assertEquals(value, McpTextContent.fromText(value).getText());
			assertEquals(value, McpTextResourceContents.withUriAndText(RESOURCE, value).build().getText());
			assertEquals(value, McpJsonString.fromValue(value).getValue());
			assertEquals(List.of(value), McpArgumentCompletionResult.fromValues(List.of(value)).getValues());
			assertEquals(value, McpPromptOutput.builder().description(value).build().getDescription().orElseThrow());
			assertEquals(List.of(value), List.copyOf(McpJsonObject.builder().put(value, true).build().getMembers().keySet()));
		}
	}

	@Test
	void rejectedSettersLeavePreviouslyAcceptedValuesIntact() {
		McpJsonObject.Builder json = McpJsonObject.builder().put("safe", "retained");
		assertThrows(IllegalArgumentException.class, () -> json.put("safe", "\uD800"));
		assertThrows(IllegalArgumentException.class, () -> json.put("\uDC00", true));
		assertEquals(McpJsonObject.builder().put("safe", "retained").build(), json.build());
		McpPromptOutput.Builder prompt = McpPromptOutput.builder().description("retained");
		assertThrows(IllegalArgumentException.class, () -> prompt.description("\uD800"));
		assertEquals("retained", prompt.build().getDescription().orElseThrow());
	}

	@Test
	void constructionDoesNotPretendToValidateEventualWireSize() {
		String oversized = "x".repeat(1_048_577);
		assertSame(oversized, McpTextContent.fromText(oversized).getText());
		assertSame(oversized, McpTextResourceContents.withUriAndText(RESOURCE, oversized).build().getText());
		assertSame(oversized, McpJsonString.fromValue(oversized).getValue());
		assertEquals(786_433, McpImageContent.withDataAndMimeType(new byte[786_433], "image/png").build().getData().length);
	}

	@Test
	void imageAndAudioMimeTypesRejectIncompleteMalformedAndAmbiguousSyntax() {
		for (String mimeType : List.of("", " ", "image", "/png", "image/", "image/png;",
				"image/png;name=", "image/png;name=\"unterminated", "image/png, image/jpeg",
				"image/png;x=1;X=2", "image/png\r\n" + CANARY, "image/" + CANARY + "\uD800")) {
			for (Consumer<String> factory : List.<Consumer<String>>of(
					value -> McpImageContent.withDataAndMimeType(new byte[0], value),
					value -> McpAudioContent.withDataAndMimeType(new byte[0], value))) {
				IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
						() -> factory.accept(mimeType));
				assertFalse(exception.getMessage().contains(CANARY));
				assertNull(exception.getCause());
			}
		}
	}

	@Test
	void mimeTypesPreserveSpellingParametersAndUnregisteredFormatsWithoutSniffingBytes() {
		for (String mimeType : List.of("image/png", "IMAGE/SVG+XML; Name=\"Quoted; value\"",
				" image / x-vendor ; option = \"a\\\"b\" "))
			assertEquals(mimeType, McpImageContent.withDataAndMimeType(new byte[0], mimeType).build().getMimeType());
		for (String mimeType : List.of("audio/mpeg", "AUDIO/X-VENDOR; Codec=\"Custom; codec\"", "application/ogg"))
			assertEquals(mimeType, McpAudioContent.withDataAndMimeType(new byte[0], mimeType).build().getMimeType());
	}
}
