/*
 * Copyright 2022-2026 Revetware LLC.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.soklet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class McpSkillEndpointPreflightTests {
	@Test
	void automaticPagesPermitThirtyTwoSlotsAndRejectThirtyThreeWithoutCallingHandlers() {
		List<McpSkillRegistration> registrations = registrations(33);
		assertEquals(32, builder().skillRegistrations(registrations.subList(0, 32)).build().getSkillRegistrations().size());
		assertPaginationRequired(() -> builder().skillRegistrations(registrations).build());
	}

	@Test
	void customHandlerPermitsLargerCatalogAndNullResetRestoresAutomaticPreflight() {
		List<McpSkillRegistration> registrations = registrations(33);
		McpSkillListHandler handler = handler();
		McpEndpoint.Builder builder = builder().skillRegistrations(registrations).skillListHandler(handler);
		McpEndpoint endpoint = assertDoesNotThrow(builder::build);
		assertSame(handler, endpoint.getSkillListHandler().orElseThrow());
		assertEquals(33, endpoint.getSkillRegistrations().size());
		assertPaginationRequired(() -> builder.skillListHandler(null).build());
	}

	@Test
	void groupsContributeOneAutomaticSlotAndEmptyGroupsContributeNone() {
		List<McpSkillRegistration> registrations = registrations(31);
		McpSkillGroup variants = group("grouped", "", "");
		McpEndpoint endpoint = builder().skillRegistrations(registrations).skillGroups(List.of(variants,
				McpSkillGroup.fromKeyAndSkillRegistrations("empty", List.of()))).build();
		assertEquals(33, endpoint.skillIndex().registrations().size());
		assertPaginationRequired(() -> builder().skillRegistrations(registrations(32)).skillGroups(List.of(variants)).build());
	}

	@Test
	void automaticPreflightUsesLargestByteVariantEvenWhenSmallerVariantComesFirst() {
		// Every individual entry/root read is below the 1 Mi-character scalar and
		// 4 MiB output profile. Five selected large variants exceed page output.
		String large = "extra: " + "x".repeat(850_000) + "\n";
		List<McpSkillGroup> groups = new ArrayList<>();
		for (int index = 0; index < 5; ++index) groups.add(group("bytes-" + index, "", large));
		assertPaginationRequired(() -> builder().skillGroups(groups).build());
		assertEquals(5, assertDoesNotThrow(() -> builder().skillGroups(groups).skillListHandler(handler()).build())
				.getSkillGroups().size());
	}

	@Test
	void automaticNodeMaximumIsIndependentOfTheLargestByteVariant() {
		// Byte-heavy alternatives win byte accounting but contain few JSON nodes;
		// separate node-heavy alternatives jointly exceed 100,000 production nodes.
		String byteHeavy = "extra: " + "x".repeat(100_000) + "\n";
		String nodeHeavy = "extra: [" + "0,".repeat(9_999) + "0]\n";
		List<McpSkillGroup> groups = new ArrayList<>();
		for (int index = 0; index < 11; ++index) groups.add(group("nodes-" + index, byteHeavy, nodeHeavy));
		assertPaginationRequired(() -> builder().skillGroups(groups).build());
		assertEquals(11, assertDoesNotThrow(() -> builder().skillGroups(groups).skillListHandler(handler()).build())
				.getSkillGroups().size());
	}

	@Test
	void customHandlerCannotBypassIndividualEnvelopeLimitsWithServerMetadata() {
		McpSkillRegistration registration = registration("one", "variant", null,
				"extra: " + "x".repeat(850_000) + "\n");
		String serverField = "x".repeat(900_000);
		McpImplementation information = McpImplementation.withNameAndVersion(serverField, serverField)
				.title(serverField).description(serverField).build();
		McpEndpoint.Builder builder = McpEndpoint.withPath("/skills", information)
				.skillRegistrations(List.of(registration)).skillListHandler(handler());
		IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, builder::build);
		assertEquals("An MCP Skills registration with endpoint metadata exceeds the JSON output profile.", failure.getMessage());
		assertNull(failure.getCause());
		assertSame(registration, assertDoesNotThrow(() -> builder.serverInfoIncluded(false).build())
				.getSkillRegistrations().get(0));
	}

	private static McpEndpoint.Builder builder() {
		return McpEndpoint.withPath("/skills", McpImplementation.withNameAndVersion("test", "1").build());
	}

	private static List<McpSkillRegistration> registrations(int count) {
		List<McpSkillRegistration> registrations = new ArrayList<>();
		for (int index = 0; index < count; ++index)
			registrations.add(registration("skill-" + index, "variant", null, ""));
		return List.copyOf(registrations);
	}

	private static McpSkillGroup group(String name, String firstExtra, String secondExtra) {
		return McpSkillGroup.fromKeyAndSkillRegistrations(name, List.of(
				registration(name, "en", Locale.ENGLISH, firstExtra),
				registration(name, "fr", Locale.FRENCH, secondExtra)));
	}

	private static McpSkillRegistration registration(String name, String variant, Locale locale, String extra) {
		byte[] root = ("---\nname: " + name + "\ndescription: Synthetic description\n" + extra + "---\nOpaque body.\n")
				.getBytes(StandardCharsets.UTF_8);
		McpSkillRegistration.Builder builder = McpSkillRegistration.withUriAndSkillBundle(
				URI.create("skill://host.invalid/" + variant + "/" + name + "/SKILL.md"), McpSkillBundle.fromFiles(Map.of("SKILL.md", root)));
		if (locale != null) builder.locale(locale);
		return builder.build();
	}

	private static McpSkillListHandler handler() {
		return (request, context, features) -> { throw new AssertionError("Preflight must not invoke a handler."); };
	}

	private static void assertPaginationRequired(Executable operation) {
		IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, operation);
		assertEquals("The automatic MCP Skills page exceeds the output profile; configure skillListHandler(...).", failure.getMessage());
		assertNull(failure.getCause());
	}
}
