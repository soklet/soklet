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

import javax.annotation.concurrent.ThreadSafe;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for complete-input validation before builder replacement. */
@ThreadSafe
class McpAtomicBuilderSetterTests {
	@Test
	void audienceValidatesBeforeReplacementAndSnapshotsTheArray() {
		McpContentAnnotations.Builder builder = McpContentAnnotations.builder()
				.audience(McpRole.USER);
		McpRole[] supplied = {McpRole.ASSISTANT, McpRole.USER,
				McpRole.ASSISTANT};

		assertSame(builder, builder.audience(supplied));
		supplied[0] = McpRole.USER;
		assertEquals(List.of(McpRole.ASSISTANT, McpRole.USER),
				List.copyOf(builder.build().getAudience()));

		assertThrows(NullPointerException.class,
				() -> builder.audience(McpRole.USER, null));
		assertEquals(List.of(McpRole.ASSISTANT, McpRole.USER),
				List.copyOf(builder.build().getAudience()));
		assertThrows(NullPointerException.class,
				() -> builder.audience((McpRole[]) null));
		assertEquals(List.of(McpRole.ASSISTANT, McpRole.USER),
				List.copyOf(builder.build().getAudience()));

		builder.audience();
		assertTrue(builder.build().getAudience().isEmpty());
	}

	@Test
	void headersValidateBeforeReplacementAndSnapshotNestedCollections() {
		McpAdmissionRejection.Builder builder = rejectionBuilder();
		Set<String> suppliedValues = new LinkedHashSet<>(Set.of("one"));
		Map<String, Set<String>> supplied = new LinkedHashMap<>();
		supplied.put("X-Original", suppliedValues);

		assertSame(builder, builder.headers(supplied));
		suppliedValues.add("later");
		supplied.put("X-Later", Set.of("later"));
		Map<String, Set<String>> expected = Map.of("X-Original", Set.of("one"));
		assertEquals(expected, builder.build().getHeaders());

		Map<String, Set<String>> nullSet = new LinkedHashMap<>();
		nullSet.put("X-New", Set.of("new"));
		nullSet.put("X-Invalid", null);
		assertThrows(NullPointerException.class, () -> builder.headers(nullSet));
		assertEquals(expected, builder.build().getHeaders());

		Map<String, Set<String>> nullName = new LinkedHashMap<>();
		nullName.put("X-New", Set.of("new"));
		nullName.put(null, Set.of("invalid"));
		assertThrows(NullPointerException.class, () -> builder.headers(nullName));
		assertEquals(expected, builder.build().getHeaders());

		Set<String> nullValue = new LinkedHashSet<>();
		nullValue.add("new");
		nullValue.add(null);
		Map<String, Set<String>> nullMember = new LinkedHashMap<>();
		nullMember.put("X-New", Set.of("new"));
		nullMember.put("X-Invalid", nullValue);
		assertThrows(NullPointerException.class,
				() -> builder.headers(nullMember));
		assertEquals(expected, builder.build().getHeaders());

		assertThrows(NullPointerException.class, () -> builder.headers(null));
		assertEquals(expected, builder.build().getHeaders());
		builder.headers(Map.of());
		assertEquals(Map.of(), builder.build().getHeaders());
	}

	@Test
	void addHeaderRejectsNullBeforeCreatingAnEntry() {
		McpAdmissionRejection.Builder builder = rejectionBuilder()
				.addHeader("X-Original", "one");
		Map<String, Set<String>> expected = Map.of("X-Original", Set.of("one"));

		assertThrows(NullPointerException.class,
				() -> builder.addHeader("X-Invalid", null));
		assertEquals(expected, builder.build().getHeaders());
		assertThrows(NullPointerException.class,
				() -> builder.addHeader(null, "invalid"));
		assertEquals(expected, builder.build().getHeaders());
	}

	private static McpAdmissionRejection.Builder rejectionBuilder() {
		return McpAdmissionRejection.withStatusCodeAndError(403,
				McpJsonRpcError.fromApplication(-31_000, "denied"));
	}
}
