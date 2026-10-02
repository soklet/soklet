/*
 * Copyright 2022-2026 Revetware LLC.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.soklet.internal.mcp.protocol;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class McpLegacyCatalogPagerTests {
	private final McpJsonCodec codec = new McpJsonCodec(McpJsonLimits.productionDefaults());
	private final McpJsonRpcId id = new McpJsonRpcId.IntegerId(BigInteger.ONE);

	@Test
	void equivalent_nodes_accept_cursors_despite_registration_member_and_numeric_spelling_order() throws Exception {
		for (McpProtocolProfile profile : List.of(Mcp2025ProtocolProfile.JUNE_18, Mcp2025ProtocolProfile.NOVEMBER_25)) {
			McpLegacyCatalogPager first = pager("/mcp", profile, false, "canonical");
			McpLegacyCatalogPager second = pager("/mcp", profile, true, "canonical");
			McpLegacyCatalogPager.Page page = select(first, null, "pt-BR", 1, owner -> true);
			assertEquals("a", page.entries().get(0).ownerId());
			String token = ((McpJsonString) first.document(page.entries(), page.more(), "pt-BR")
					.members().get("nextCursor")).value();
			McpLegacyCatalogPager.Page continuation = select(second,
					McpLegacyCatalogPager.decode(token).orElseThrow(), "pt-BR", 1, owner -> true);
			assertFalse(continuation.invalidCursor());
			assertEquals("b", continuation.entries().get(0).ownerId());
			assertFalse(continuation.more());
		}
	}

	@Test
	void cursor_scope_and_current_anchor_authority_are_rechecked_without_exposing_keys() throws Exception {
		McpLegacyCatalogPager original = pager("/mcp", Mcp2025ProtocolProfile.JUNE_18, false, "canonical");
		McpLegacyCatalogPager.Page page = select(original, null, "en-US", 1, owner -> true);
		String token = ((McpJsonString) original.document(page.entries(), true, "en-US")
				.members().get("nextCursor")).value();
		McpLegacyCatalogPager.Cursor cursor = McpLegacyCatalogPager.decode(token).orElseThrow();
		assertTrue(select(pager("/other", Mcp2025ProtocolProfile.JUNE_18, false, "canonical"), cursor,
				"en-US", 1, owner -> true).invalidCursor());
		assertTrue(select(pager("/mcp", Mcp2025ProtocolProfile.NOVEMBER_25, false, "canonical"), cursor,
				"en-US", 1, owner -> true).invalidCursor());
		assertTrue(select(pager("/mcp", Mcp2025ProtocolProfile.JUNE_18, false, "changed"), cursor,
				"en-US", 1, owner -> true).invalidCursor());
		assertTrue(select(original, cursor, "pt-BR", 1, owner -> true).invalidCursor());
		assertTrue(select(original, cursor, "en-US", 1, owner -> !owner.equals("a")).invalidCursor());
		assertFalse(cursor.toString().contains(token));
		assertFalse(cursor.toString().contains(cursor.key()));
		for (String malformed : List.of("", "=", token + "=", "x".repeat(2049),
				"not a cursor", "___")) assertTrue(McpLegacyCatalogPager.decode(malformed).isEmpty());
	}

	@Test
	void fitting_catalog_has_no_cursor_and_candidate_work_does_not_restart_at_the_front() throws Exception {
		McpLegacyCatalogPager pager = pager("/mcp", Mcp2025ProtocolProfile.JUNE_18, false, "canonical");
		AtomicInteger checked = new AtomicInteger();
		McpLegacyCatalogPager.Page complete = select(pager, null, "", 100,
				owner -> { checked.incrementAndGet(); return true; });
		assertEquals(2, checked.get());
		assertFalse(complete.more());
		assertFalse(pager.document(complete.entries(), false, "").members().containsKey("nextCursor"));
		McpLegacyCatalogPager.Page first = select(pager, null, "", 1, owner -> true);
		String token = ((McpJsonString) pager.document(first.entries(), true, "")
				.members().get("nextCursor")).value();
		List<String> checkedNames = new ArrayList<>();
		McpLegacyCatalogPager.Page next = select(pager, McpLegacyCatalogPager.decode(token).orElseThrow(),
				"", 1, owner -> { checkedNames.add(owner); return true; });
		assertEquals(List.of("a", "b"), checkedNames); // current anchor, then only the suffix
		assertEquals(1, next.entries().size());
	}

	private McpLegacyCatalogPager.Page select(McpLegacyCatalogPager pager,
			McpLegacyCatalogPager.Cursor cursor, String locale, int maximumSlots,
			McpLegacyCatalogPager.Access access) throws Exception {
		McpJsonObject empty = McpLegacyCatalogPager.envelope(id, pager.document(List.of(), false, locale));
		return pager.select(cursor, locale, codec.toUtf8Bytes(empty).length,
				McpLegacyCatalogPager.nodes(empty), 4096, 1000, maximumSlots, owner -> 1, access);
	}

	private McpLegacyCatalogPager pager(String path, McpProtocolProfile profile, boolean reversed,
			String description) {
		List<McpJsonValue> descriptors = new ArrayList<>();
		for (String name : reversed ? List.of("b", "a") : List.of("a", "b")) {
			Map<String, McpJsonValue> schema = new LinkedHashMap<>();
			if (reversed) {
				schema.put("minimum", new McpJsonNumber(new BigDecimal("1.00")));
				schema.put("type", new McpJsonString("object"));
			} else {
				schema.put("type", new McpJsonString("object"));
				schema.put("minimum", new McpJsonNumber(new BigDecimal("1")));
			}
			descriptors.add(new McpJsonObject(Map.of("name", new McpJsonString(name),
					"description", new McpJsonString(description), "inputSchema", new McpJsonObject(schema))));
		}
		return new McpLegacyCatalogPager(path, profile, McpLegacyCatalogPager.Kind.TOOLS,
				McpWireResult.complete(new McpJsonObject(Map.of("tools", new McpJsonArray(descriptors)))), codec);
	}
}
