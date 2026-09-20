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

package com.soklet.internal.mcp.protocol;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class McpAppMimeTypeClientCapabilityTests {
	private static final String EXTENSION = "io.modelcontextprotocol/ui";
	private static final String MIME = "text/html;profile=mcp-app";
	private static final McpExtensionMimeTypeClientCapability REQUIREMENT =
			new McpExtensionMimeTypeClientCapability(EXTENSION, MIME);

	@Test
	void requirementCanonicalizesEquivalentMimeValuesAndPreservesCaseSensitiveValues() {
		assertEquals(REQUIREMENT, new McpExtensionMimeTypeClientCapability(EXTENSION,
				" TEXT / HTML ; PROFILE = \"mcp\\-app\" "));
		assertEquals(MIME, REQUIREMENT.mimeType());
		assertEquals(EXTENSION, REQUIREMENT.identifier());
		assertEquals("McpExtensionMimeTypeClientCapability[identifier=" + EXTENSION
				+ ", mimeType=" + MIME + "]", REQUIREMENT.toString());
		assertEquals("application/example;a=\"\";b=\"quoted value\";z=UPPER",
				new McpExtensionMimeTypeClientCapability(EXTENSION,
						"APPLICATION/EXAMPLE;Z=UPPER;B=\"quoted value\";A=\"\"").mimeType());
		assertNotEquals(REQUIREMENT, new McpExtensionMimeTypeClientCapability(EXTENSION,
				"text/html;profile=MCP-APP"));
		assertNotEquals(REQUIREMENT, new McpExtensionMimeTypeClientCapability(EXTENSION,
				"text/html;profile=mcp-app;charset=utf-8"));
	}

	@Test
	void invalidRequirementInputsAreRejectedWithoutEchoingMime() {
		for (String invalid : List.of("secret", "text/html;secret=\"unterminated", "text/plain\nsecret")) {
			IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
					() -> new McpExtensionMimeTypeClientCapability(EXTENSION, invalid));
			assertEquals("Invalid MCP Apps MIME type.", exception.getMessage());
			assertNull(exception.getCause());
		}
		assertThrows(IllegalArgumentException.class, () -> new McpExtensionMimeTypeClientCapability("bad", MIME));
		assertThrows(NullPointerException.class, () -> new McpExtensionMimeTypeClientCapability(EXTENSION, null));
	}

	@Test
	void internalCapabilitySupportRequiresValidWholeArrayAndEquivalentEntry() {
		assertFalse(McpClientCapabilities.empty().supports(REQUIREMENT));
		assertFalse(McpClientCapabilities.builder().extension(EXTENSION, McpJsonObject.empty()).build().supports(REQUIREMENT));
		assertFalse(McpClientCapabilities.builder().extension("example.org/other", settings(array(MIME)))
				.build().supports(REQUIREMENT));
		assertTrue(capabilities(array("application/json", "TEXT/HTML;PROFILE=\"mcp-app\"")).supports(REQUIREMENT));
		assertTrue(capabilities(array(MIME, MIME)).supports(REQUIREMENT));
		assertFalse(capabilities(array("text/html;profile=MCP-APP")).supports(REQUIREMENT));
		assertFalse(capabilities(array("text/html;profile=mcp-app;charset=utf-8")).supports(REQUIREMENT));
		assertFalse(capabilities(array()).supports(REQUIREMENT));
		for (McpJsonValue invalid : List.of(McpJsonNull.INSTANCE, McpJsonBoolean.TRUE,
				McpJsonObject.empty(), array(), new McpJsonString(""), new McpJsonString("text/plain\nsecret"),
				new McpJsonString("text/html;profile=mcp-app;PROFILE=mcp-app"))) {
			assertFalse(capabilities(invalid).supports(REQUIREMENT));
			assertFalse(capabilities(new McpJsonArray(List.of(new McpJsonString(MIME), invalid))).supports(REQUIREMENT));
			assertFalse(capabilities(new McpJsonArray(List.of(invalid, new McpJsonString(MIME)))).supports(REQUIREMENT));
		}
	}

	@Test
	void genericExtensionSupportRemainsPresenceAwareAndUnrelatedSettingsArePreserved() {
		McpJsonObject settings = new McpJsonObject(Map.of("mimeTypes", array(MIME),
				"vendor-setting", new McpJsonString("preserved")));
		McpClientCapabilities capabilities = McpClientCapabilities.builder().extension(EXTENSION, settings).build();
		assertTrue(capabilities.supports(new McpExtensionClientCapability(EXTENSION)));
		assertTrue(capabilities.supports(REQUIREMENT));
		assertSame(settings, capabilities.extensions().get(EXTENSION));
		assertEquals(settings, ((McpJsonObject) capabilities.toJsonObject().members().get("extensions"))
				.members().get(EXTENSION));
		assertTrue(McpClientCapabilities.builder().extension(EXTENSION, McpJsonObject.empty()).build()
				.supports(new McpExtensionClientCapability(EXTENSION)));
	}

	@Test
	void requirementSerializationMergesGenericAndMultipleMimeRequirementsDeterministically() {
		List<McpClientCapabilityRequirement> requirements = List.of(
				new McpExtensionClientCapability(EXTENSION), REQUIREMENT,
				new McpExtensionMimeTypeClientCapability(EXTENSION, "TEXT/PLAIN"),
				new McpExtensionMimeTypeClientCapability(EXTENSION, "TEXT/HTML;PROFILE=\"mcp-app\""),
				new McpExtensionClientCapability("z.example/extension"),
				new McpExtensionMimeTypeClientCapability("a.example/extension", "APPLICATION/JSON"));
		McpClientCapabilities forward = McpClientCapabilities.fromRequirements(new LinkedHashSet<>(requirements));
		List<McpClientCapabilityRequirement> reverse = new ArrayList<>(requirements);
		java.util.Collections.reverse(reverse);
		McpClientCapabilities backward = McpClientCapabilities.fromRequirements(new LinkedHashSet<>(reverse));
		assertEquals(forward, backward);
		assertEquals(List.of("a.example/extension", EXTENSION, "z.example/extension"),
				List.copyOf(forward.extensions().keySet()));
		assertEquals(List.copyOf(forward.extensions().keySet()), List.copyOf(backward.extensions().keySet()));
		assertEquals(array(MIME, "text/plain"), forward.extensions().get(EXTENSION).members().get("mimeTypes"));
		assertEquals(McpJsonObject.empty(), forward.extensions().get("z.example/extension"));
		assertEquals(array("application/json"), forward.extensions().get("a.example/extension").members().get("mimeTypes"));
		for (McpClientCapabilityRequirement requirement : requirements)
			assertTrue(forward.supports(requirement));
	}

	@Test
	void missingCapabilityErrorCarriesMergedMimeRequirementsAndCoreCapabilities() {
		Set<McpClientCapabilityRequirement> requirements = Set.of(REQUIREMENT,
				new McpExtensionMimeTypeClientCapability(EXTENSION, "text/plain"),
				new McpExtensionClientCapability(EXTENSION), McpCoreClientCapability.ELICITATION_FORM,
				McpCoreClientCapability.ELICITATION_URL);
		McpClientCapabilities capabilities = McpClientCapabilities.fromRequirements(requirements);
		assertTrue(capabilities.supports(McpCoreClientCapability.ELICITATION_FORM));
		assertTrue(capabilities.supports(McpCoreClientCapability.ELICITATION_URL));
		McpJsonRpcError error = McpJsonRpcError.missingRequiredClientCapabilities(requirements);
		assertEquals(-32021, error.code());
		assertEquals(capabilities.toJsonObject(), ((McpJsonObject) error.data().orElseThrow())
				.members().get("requiredCapabilities"));
	}

	@Test
	void canonicalMimeEncodingPreservesQuotedPunctuationAndIsIdempotent() {
		for (String value : List.of("text/html;z=\"\";a=\"quoted;separators=are/values\"",
				"text/plain;value=\"\\\"\\\\\"", " text / plain ; VALUE = \"UPPER lower\" ")) {
			String canonical = McpAppMimeType.canonicalize(value);
			assertEquals(canonical, McpAppMimeType.canonicalize(canonical));
			assertTrue(McpAppMimeType.supportsMimeType(value, List.of(canonical)));
		}
	}

	private static McpClientCapabilities capabilities(McpJsonValue mimeTypes) {
		return McpClientCapabilities.builder().extension(EXTENSION, settings(mimeTypes)).build();
	}

	private static McpJsonObject settings(McpJsonValue mimeTypes) {
		return new McpJsonObject(Map.of("mimeTypes", mimeTypes));
	}

	private static McpJsonArray array(String... values) {
		return new McpJsonArray(java.util.Arrays.stream(values).<McpJsonValue>map(McpJsonString::new).toList());
	}
}
