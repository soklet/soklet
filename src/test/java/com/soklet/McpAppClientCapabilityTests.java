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

import static org.junit.jupiter.api.Assertions.*;

class McpAppClientCapabilityTests {
	private static final String EXTENSION = "io.modelcontextprotocol/ui";
	private static final String MIME = "text/html;profile=mcp-app";

	@Test
	void supportUsesStructuralMimeMatchingAndPreservesAllExtensionFields() {
		McpJsonObject settings = McpJsonObject.builder()
				.put("mimeTypes", McpJsonArray.builder().add("TEXT / HTML ; PROFILE=\"mcp\\-app\"")
						.add("text/plain").build()).put("vendor-option", true).build();
		McpJsonObject json = McpJsonObject.builder().put("extensions", McpJsonObject.builder()
				.put(EXTENSION, settings).put("example.org/other", McpJsonObject.builder()
						.put("unchanged", "value").build()).build()).put("future", true).build();
		McpClientCapabilities capabilities = McpClientCapabilities.fromJson(json);
		assertTrue(capabilities.supportsAppMimeType(MIME));
		assertTrue(capabilities.supportsAppMimeType(" TEXT/PLAIN "));
		assertSame(settings, capabilities.findExtension(EXTENSION).orElseThrow());
		assertSame(json, capabilities.toJson());
		assertEquals(2, capabilities.getExtensions().size());
		assertEquals(McpClientCapabilities.fromJson(json), capabilities);
	}

	@Test
	void parameterOrderDoesNotMatterButValuesAndAdditionalParametersDo() {
		McpClientCapabilities capabilities = capabilities(McpJsonArray.builder()
				.add("text/html;charset=UTF-8;profile=mcp-app").build());
		assertTrue(capabilities.supportsAppMimeType("TEXT/HTML;PROFILE=\"mcp-app\";CHARSET=UTF-8"));
		assertFalse(capabilities.supportsAppMimeType("text/html;profile=mcp-app;charset=utf-8"));
		assertFalse(capabilities.supportsAppMimeType(MIME));
		assertFalse(capabilities(McpJsonArray.builder().add(MIME).build())
				.supportsAppMimeType("text/html;profile=MCP-APP"));
	}

	@Test
	void extensionPresenceAndUnrelatedExtensionsDoNotEstablishSupport() {
		for (McpJsonObject json : List.of(McpJsonObject.emptyInstance(),
				McpJsonObject.builder().put("extensions", McpJsonObject.builder()
						.put(EXTENSION, McpJsonObject.emptyInstance()).build()).build(),
				McpJsonObject.builder().put("extensions", McpJsonObject.builder()
						.put("example.org/ui", settings(McpJsonArray.builder().add(MIME).build())).build()).build(),
				McpJsonObject.builder().put("extensions", true).build(),
				McpJsonObject.builder().put("extensions", McpJsonObject.builder().put(EXTENSION, true).build()).build()))
			assertFalse(McpClientCapabilities.fromJson(json).supportsAppMimeType(MIME));
		assertFalse(capabilities(McpJsonArray.emptyInstance()).supportsAppMimeType(MIME));
	}

	@Test
	void malformedMimeTypesFieldNeverEstablishesSupport() {
		for (McpJsonValue value : List.of(McpJsonNull.INSTANCE, McpJsonString.fromValue(MIME),
				McpJsonObject.emptyInstance(), McpJsonBoolean.fromValue(true), McpJsonNumber.fromValue(java.math.BigDecimal.ONE)))
			assertFalse(capabilities(value).supportsAppMimeType(MIME));
	}

	@Test
	void validEntryCannotMaskInvalidSiblingBeforeOrAfterIt() {
		for (McpJsonValue invalid : List.of(McpJsonNull.INSTANCE, McpJsonBoolean.fromValue(true),
				McpJsonObject.emptyInstance(), McpJsonArray.emptyInstance(),
				McpJsonString.fromValue("text/html;profile=\"unterminated"),
				McpJsonString.fromValue("text/html;profile=mcp-app;PROFILE=mcp-app"),
				McpJsonString.fromValue("text/plain\nsecret"), McpJsonString.fromValue(""))) {
			assertFalse(capabilities(McpJsonArray.builder().add(MIME).add(invalid).build()).supportsAppMimeType(MIME));
			assertFalse(capabilities(McpJsonArray.builder().add(invalid).add(MIME).build()).supportsAppMimeType(MIME));
		}
	}

	@Test
	void equivalentDuplicateArrayEntriesRemainValid() {
		assertTrue(capabilities(McpJsonArray.builder().add(MIME).add("TEXT/HTML;profile=\"mcp-app\"")
				.build()).supportsAppMimeType(MIME));
	}

	@Test
	void invalidRequestedMimeThrowsEvenWhenNoClientSupportWasAdvertised() {
		for (McpClientCapabilities capabilities : List.of(McpClientCapabilities.fromJson(McpJsonObject.emptyInstance()),
				capabilities(McpJsonArray.builder().add(MIME).build()), capabilities(McpJsonNull.INSTANCE))) {
			for (String invalid : List.of("", "not-a-mime-type", "text/html;profile=mcp-app;PROFILE=mcp-app",
					"text/html;profile=\"unterminated", "text/html\t", "text/html;value=☃")) {
				IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
						() -> capabilities.supportsAppMimeType(invalid));
				assertEquals("Invalid MCP Apps MIME type.", exception.getMessage());
				assertNull(exception.getCause());
			}
			assertThrows(NullPointerException.class, () -> capabilities.supportsAppMimeType(null));
		}
	}

	private static McpClientCapabilities capabilities(McpJsonValue mimeTypes) {
		return McpClientCapabilities.fromJson(McpJsonObject.builder().put("extensions", McpJsonObject.builder()
				.put(EXTENSION, settings(mimeTypes)).build()).build());
	}

	private static McpJsonObject settings(McpJsonValue mimeTypes) {
		return McpJsonObject.builder().put("mimeTypes", mimeTypes).build();
	}
}
