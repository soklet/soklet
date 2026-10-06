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

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class McpLegacyContentProjectionTests {
	private static final McpJsonCodec JSON = new McpJsonCodec(McpJsonLimits.productionDefaults());

	@Test
	void juneToolLinkOmitsOnlyIconsWithoutMutatingCanonicalResult() {
		McpJsonObject link = object("""
				{"type":"resource_link","uri":"test://file","name":"file","title":"File",
				"icons":[{"src":"https://example.test/icon.png"}],
				"annotations":{"priority":0.5},"_meta":{"example.test/marker":"kept"}}
				""");
		McpJsonObject metadata = object("{\"example.test/receipt\":\"once\"}");
		McpWireResult canonical = McpWireResult.complete(new McpJsonObject(Map.of(
				"content", new McpJsonArray(List.of(link)), "isError", McpJsonBoolean.TRUE,
				"structuredContent", object("{\"result\":42}"))), Optional.of(new McpResultMetadata(Optional.empty(), metadata)));
		McpWireResult june = Mcp2025ProtocolProfile.JUNE_18.renderApplicationResult(
				McpProfileApplicationResultKind.TOOL, canonical);
		McpJsonObject projected = (McpJsonObject) ((McpJsonArray) june.fields().members().get("content")).values().get(0);
		assertFalse(projected.members().containsKey("icons"));
		for (String field : List.of("type", "uri", "name", "title", "annotations", "_meta"))
			assertEquals(link.members().get(field), projected.members().get(field), field);
		assertEquals(canonical.fields().members().get("structuredContent"), june.fields().members().get("structuredContent"));
		assertEquals(McpJsonBoolean.TRUE, june.fields().members().get("isError"));
		assertEquals(canonical.metadata(), june.metadata());
		assertTrue(link.members().containsKey("icons"), "Projection must not mutate a shared application result.");
		McpWireResult november = Mcp2025ProtocolProfile.NOVEMBER_25.renderApplicationResult(
				McpProfileApplicationResultKind.TOOL, canonical);
		assertEquals(canonical, november);
		assertSame(canonical, Mcp20260728ProtocolProfile.INSTANCE.renderApplicationResult(
				McpProfileApplicationResultKind.TOOL, canonical));
	}

	@Test
	void nonObjectStructuredContentRemainsAnExplicitLegacyShapeError() {
		for (McpProtocolProfile profile : List.of(Mcp2025ProtocolProfile.JUNE_18,
				Mcp2025ProtocolProfile.NOVEMBER_25))
			for (McpJsonValue value : List.of(new McpJsonArray(List.of()),
					new McpJsonString("scalar"), new McpJsonNumber(1L), McpJsonNull.INSTANCE)) {
				McpWireResult canonical = McpWireResult.complete(new McpJsonObject(Map.of(
						"content", new McpJsonArray(List.of()), "structuredContent", value)));
				assertThrows(IllegalArgumentException.class, () -> profile.renderApplicationResult(
						McpProfileApplicationResultKind.TOOL, canonical));
			}
	}

	private static McpJsonObject object(String json) { return (McpJsonObject) JSON.parse(json); }
}
