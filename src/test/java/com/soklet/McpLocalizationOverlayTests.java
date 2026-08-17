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

// These single-type imports deliberately shadow the same-named public value
// types: the overlay operates on the internal model that produces wire bytes.
import com.soklet.internal.mcp.protocol.McpJsonArray;
import com.soklet.internal.mcp.protocol.McpJsonCodec;
import com.soklet.internal.mcp.protocol.McpJsonLimits;
import com.soklet.internal.mcp.protocol.McpJsonNumber;
import com.soklet.internal.mcp.protocol.McpJsonObject;
import com.soklet.internal.mcp.protocol.McpJsonString;
import com.soklet.internal.mcp.protocol.McpJsonValue;
import org.junit.jupiter.api.Test;

import javax.annotation.concurrent.ThreadSafe;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;

/**
 * Copy-on-write overlay behavior: exact targeting, structural sharing, byte
 * order preservation, and loud rejection of impossible plan targets.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
class McpLocalizationOverlayTests {
	private static final McpJsonCodec CODEC =
			new McpJsonCodec(McpJsonLimits.productionDefaults());

	@Test
	void replacesTopLevelNestedAndArrayTargets() {
		McpJsonObject document = catalog();

		assertEquals("Translated instructions", stringAt(
				McpLocalizationOverlay.withReplacements(document, List.of(
						replacement("/instructions", "Translated instructions"))),
				"/instructions"));

		assertEquals("Translated server title", stringAt(
				McpLocalizationOverlay.withReplacements(document, List.of(
						replacement("/_meta/io.modelcontextprotocol~1serverInfo/title",
								"Translated server title"))),
				"/_meta/io.modelcontextprotocol~1serverInfo/title"));

		assertEquals("Translated argument title", stringAt(
				McpLocalizationOverlay.withReplacements(document, List.of(
						replacement("/tools/1/inputSchema/properties/query~1text/title",
								"Translated argument title"))),
				"/tools/1/inputSchema/properties/query~1text/title"));
	}

	@Test
	void pointerTokensAreUnescapedInTheRfc6901Order() {
		McpJsonObject document = new McpJsonObject(members(
				"tilde~name", new McpJsonString("original tilde"),
				"slash/name", new McpJsonString("original slash"),
				"literal~1name", new McpJsonString("original literal")));

		McpJsonObject replaced = McpLocalizationOverlay.withReplacements(document,
				List.of(replacement("/tilde~0name", "new tilde"),
						replacement("/slash~1name", "new slash"),
						// "~01" must decode to "~1", not to a slash.
						replacement("/literal~01name", "new literal")));

		assertEquals("new tilde", stringAt(replaced, "/tilde~0name"));
		assertEquals("new slash", stringAt(replaced, "/slash~1name"));
		assertEquals("new literal", stringAt(replaced, "/literal~01name"));
	}

	@Test
	void untouchedSubtreesAreSharedRatherThanCopied() {
		McpJsonObject document = catalog();
		McpJsonObject replaced = McpLocalizationOverlay.withReplacements(document,
				List.of(replacement("/tools/0/title", "Translated tool title")));

		assertNotSame(document, replaced);
		// The sibling array element and the whole _meta subtree are untouched.
		assertSame(arrayAt(document, "/tools").values().get(1),
				arrayAt(replaced, "/tools").values().get(1));
		assertSame(document.members().get("_meta"),
				replaced.members().get("_meta"));
		assertSame(document.members().get("instructions"),
				replaced.members().get("instructions"));
	}

	@Test
	void theCanonicalDocumentIsNeverMutated() {
		McpJsonObject document = catalog();
		byte[] before = CODEC.toUtf8Bytes(document);

		McpLocalizationOverlay.withReplacements(document, List.of(
				replacement("/instructions", "Translated instructions"),
				replacement("/tools/0/title", "Translated tool title")));

		assertEquals(new String(before, StandardCharsets.UTF_8),
				new String(CODEC.toUtf8Bytes(document), StandardCharsets.UTF_8));
	}

	@Test
	void memberOrderSurvivesReplacementSoOnlyTheTargetedBytesChange() {
		McpJsonObject document = catalog();
		String canonical = CODEC.toJson(document);
		String localized = CODEC.toJson(McpLocalizationOverlay.withReplacements(
				document, List.of(replacement("/instructions", "XYZ"))));

		assertEquals(canonical.replace("\"Use canonical instructions.\"", "\"XYZ\""),
				localized, "Only the targeted string bytes may differ.");
	}

	@Test
	void everyReplacementIsAppliedAndLaterOnesSeeEarlierResults() {
		McpJsonObject replaced = McpLocalizationOverlay.withReplacements(catalog(),
				List.of(replacement("/instructions", "first"),
						replacement("/tools/0/title", "second"),
						replacement("/instructions", "third")));

		assertEquals("third", stringAt(replaced, "/instructions"));
		assertEquals("second", stringAt(replaced, "/tools/0/title"));
	}

	@Test
	void manyReplacementsUnderOneLargeContainerRemainLinear() {
		int propertyCount = 4_096;
		Map<String, McpJsonValue> properties =
				new LinkedHashMap<>(propertyCount);
		List<McpLocalizationOverlay.Replacement> replacements =
				new ArrayList<>(propertyCount);

		for (int index = 0; index < propertyCount; ++index) {
			String propertyName = "property-" + index;
			properties.put(propertyName, new McpJsonObject(members(
					"title", new McpJsonString("Original " + index),
					"constant", new McpJsonString("Shared"))));
			replacements.add(replacement(
					"/properties/" + propertyName + "/title",
					"Translated " + index));
		}

		McpJsonObject untouched = new McpJsonObject(members(
				"value", new McpJsonString("Canonical")));
		McpJsonObject canonicalProperties = new McpJsonObject(properties);
		McpJsonObject document = new McpJsonObject(members(
				"properties", canonicalProperties,
				"untouched", untouched));
		McpJsonObject replaced = assertTimeout(Duration.ofSeconds(5),
				() -> McpLocalizationOverlay.withReplacements(document,
						replacements));
		McpJsonObject replacedProperties =
				(McpJsonObject) replaced.members().get("properties");

		assertEquals(List.copyOf(canonicalProperties.members().keySet()),
				List.copyOf(replacedProperties.members().keySet()),
				"The large container's member order must remain unchanged.");
		assertEquals("Translated 0", stringAt(replaced,
				"/properties/property-0/title"));
		assertEquals("Translated 2048", stringAt(replaced,
				"/properties/property-2048/title"));
		assertEquals("Translated 4095", stringAt(replaced,
				"/properties/property-4095/title"));
		assertEquals("Original 0", stringAt(document,
				"/properties/property-0/title"));
		assertSame(untouched, replaced.members().get("untouched"));
	}

	@Test
	void firstInvalidReplacementStillDeterminesTheFailure() {
		IllegalStateException exception = assertThrows(IllegalStateException.class,
				() -> McpLocalizationOverlay.withReplacements(catalog(), List.of(
						replacement("/tools/9/title", "missing first"),
						replacement("not-a-pointer", "malformed later"))));

		assertEquals("Localization target /tools/9/title does not exist.",
				exception.getMessage());
	}

	@Test
	void absentTargetsAreRejected() {
		for (String pointer : List.of("/missing", "/tools/9/title",
				"/instructions/deeper", "/_meta/missing/title", "/tools/0/missing"))
			assertThrows(IllegalStateException.class,
					() -> McpLocalizationOverlay.withReplacements(catalog(),
							List.of(replacement(pointer, "value"))),
					pointer);
	}

	@Test
	void nonStringTargetsAreRejected() {
		for (String pointer : List.of("/tools", "/tools/0", "/_meta", "/count"))
			assertThrows(IllegalStateException.class,
					() -> McpLocalizationOverlay.withReplacements(catalog(),
							List.of(replacement(pointer, "value"))),
					pointer);
	}

	@Test
	void malformedPointersAndArrayIndicesAreRejected() {
		for (String pointer : List.of("", "instructions", "tools/0/title",
				"/tools/00/title", "/tools/x/title", "/tools/-1/title",
				"/tools/ /title"))
			assertThrows(IllegalStateException.class,
					() -> McpLocalizationOverlay.withReplacements(catalog(),
							List.of(replacement(pointer, "value"))),
					pointer);
	}

	private static McpLocalizationOverlay.Replacement replacement(String pointer,
			String text) {
		return new McpLocalizationOverlay.Replacement(pointer, text);
	}

	private static McpJsonObject catalog() {
		return new McpJsonObject(members(
				"instructions", new McpJsonString("Use canonical instructions."),
				"count", new McpJsonNumber(BigDecimal.valueOf(2)),
				"tools", new McpJsonArray(List.of(
						new McpJsonObject(members(
								"name", new McpJsonString("alpha"),
								"title", new McpJsonString("Alpha title"))),
						new McpJsonObject(members(
								"name", new McpJsonString("beta"),
								"inputSchema", new McpJsonObject(members(
										"properties", new McpJsonObject(members(
												"query/text", new McpJsonObject(members(
														"title", new McpJsonString("Input title"))))))))))),
				"_meta", new McpJsonObject(members(
						"io.modelcontextprotocol/serverInfo", new McpJsonObject(members(
								"name", new McpJsonString("server"),
								"title", new McpJsonString("Server title")))))));
	}

	private static Map<String, McpJsonValue> members(Object... nameThenValue) {
		Map<String, McpJsonValue> members = new LinkedHashMap<>();

		for (int index = 0; index < nameThenValue.length; index += 2)
			members.put((String) nameThenValue[index],
					(McpJsonValue) nameThenValue[index + 1]);

		return members;
	}

	private static String stringAt(McpJsonObject document, String pointer) {
		return ((McpJsonString) valueAt(document, pointer)).value();
	}

	private static McpJsonArray arrayAt(McpJsonObject document, String pointer) {
		return (McpJsonArray) valueAt(document, pointer);
	}

	private static McpJsonValue valueAt(McpJsonObject document, String pointer) {
		McpJsonValue node = document;

		for (String rawToken : pointer.substring(1).split("/", -1)) {
			String token = rawToken.replace("~1", "/").replace("~0", "~");

			if (node instanceof McpJsonObject object)
				node = object.members().get(token);
			else if (node instanceof McpJsonArray array)
				node = array.values().get(Integer.parseInt(token));
			else
				throw new AssertionError("No value at " + pointer);
		}

		return node;
	}
}
