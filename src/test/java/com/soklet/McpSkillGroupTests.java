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

import java.lang.reflect.Modifier;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class McpSkillGroupTests {
	@Test
	void emptyGroupsAndUndeclaredLocaleSingletonsPreserveOpaqueKeys() {
		String key = "  app/local:%café  ";
		McpSkillGroup empty = McpSkillGroup.fromKeyAndSkillRegistrations(key, List.of());
		assertEquals(key, empty.getKey());
		assertTrue(empty.getSkillRegistrations().isEmpty());
		McpSkillRegistration registration = registration("test-skill", "one", null);
		McpSkillGroup singleton = McpSkillGroup.fromKeyAndSkillRegistrations(key, List.of(registration));
		assertSame(registration, singleton.getSkillRegistrations().get(0));
		assertTrue(singleton.getSkillRegistrations().get(0).getLocale().isEmpty());
	}

	@Test
	void snapshotsMemberOrderAndExposesAnImmutableList() {
		McpSkillRegistration french = registration("test-skill", "french", Locale.FRENCH);
		McpSkillRegistration english = registration("test-skill", "english", Locale.ENGLISH);
		List<McpSkillRegistration> supplied = new ArrayList<>(List.of(french, english));
		McpSkillGroup group = McpSkillGroup.fromKeyAndSkillRegistrations("variants", supplied);
		supplied.clear();
		assertEquals(List.of(french, english), group.getSkillRegistrations());
		assertSame(group.getSkillRegistrations(), group.getSkillRegistrations());
		assertThrows(UnsupportedOperationException.class, () -> group.getSkillRegistrations().clear());
	}

	@Test
	void rejectsDifferentSkillNamesWithinOneGroup() {
		assertThrows(IllegalArgumentException.class, () -> McpSkillGroup.fromKeyAndSkillRegistrations("group",
				List.of(registration("test-skill", "one", Locale.ENGLISH),
						registration("other-skill", "two", Locale.FRENCH))));
	}

	@Test
	void rejectsDuplicateDeclaredOrUndeclaredLocalesButAllowsOneOfEach() {
		assertThrows(IllegalArgumentException.class, () -> McpSkillGroup.fromKeyAndSkillRegistrations("group",
				List.of(registration("test-skill", "one", Locale.US),
						registration("test-skill", "two", Locale.forLanguageTag("en-US")))));
		assertThrows(IllegalArgumentException.class, () -> McpSkillGroup.fromKeyAndSkillRegistrations("group",
				List.of(registration("test-skill", "one", null), registration("test-skill", "two", null))));
		assertEquals(2, McpSkillGroup.fromKeyAndSkillRegistrations("group",
				List.of(registration("test-skill", "one", null), registration("test-skill", "two", Locale.ENGLISH)))
				.getSkillRegistrations().size());
	}

	@Test
	void rejectsDuplicateUrisUsingUriEqualsRatherThanStringSpelling() {
		McpSkillBundle bundle = bundle("test-skill");
		McpSkillRegistration first = McpSkillRegistration.withUriAndSkillBundle(
				URI.create("SKILL://HOST.invalid/test-skill/SKILL.md"), bundle).locale(Locale.ENGLISH).build();
		McpSkillRegistration duplicate = McpSkillRegistration.withUriAndSkillBundle(
				URI.create("skill://host.invalid/test-skill/SKILL.md"), bundle).locale(Locale.FRENCH).build();
		assertThrows(IllegalArgumentException.class,
				() -> McpSkillGroup.fromKeyAndSkillRegistrations("group", List.of(first, duplicate)));
	}

	@Test
	void rejectsBlankKeysAndNullInputsWithoutLeakingAuthoredValues() {
		for (String key : List.of("", " ", "\t\r\n"))
			assertThrows(IllegalArgumentException.class, () -> McpSkillGroup.fromKeyAndSkillRegistrations(key, List.of()));
		assertThrows(NullPointerException.class, () -> McpSkillGroup.fromKeyAndSkillRegistrations(null, List.of()));
		assertThrows(NullPointerException.class, () -> McpSkillGroup.fromKeyAndSkillRegistrations("group", null));
		assertThrows(NullPointerException.class,
				() -> McpSkillGroup.fromKeyAndSkillRegistrations("group", Arrays.asList((McpSkillRegistration) null)));
		IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
				() -> McpSkillGroup.fromKeyAndSkillRegistrations("private-key-canary", List.of(
						registration("test-skill", "one", Locale.US), registration("test-skill", "two", Locale.US))));
		assertEquals("Invalid Skills group.", failure.getMessage());
		assertNull(failure.getCause());
	}

	@Test
	void equalityIncludesExactKeyAndOrderedStructuralRegistrationValues() {
		McpSkillRegistration first = registration("test-skill", "one", Locale.ENGLISH);
		McpSkillRegistration second = registration("test-skill", "two", Locale.FRENCH);
		McpSkillGroup group = McpSkillGroup.fromKeyAndSkillRegistrations("group", List.of(first, second));
		McpSkillGroup equal = McpSkillGroup.fromKeyAndSkillRegistrations("group", List.of(
				registration("test-skill", "one", Locale.ENGLISH), registration("test-skill", "two", Locale.FRENCH)));
		assertEquals(group, equal);
		assertEquals(group.hashCode(), equal.hashCode());
		assertNotEquals(group, McpSkillGroup.fromKeyAndSkillRegistrations(" group ", List.of(first, second)));
		assertNotEquals(group, McpSkillGroup.fromKeyAndSkillRegistrations("group", List.of(second, first)));
		assertNotEquals(group, null);
		assertNotEquals(group, "group");
	}

	@Test
	void constructionIsPrivateAndDiagnosticsAreRedacted() {
		assertTrue(Modifier.isFinal(McpSkillGroup.class.getModifiers()));
		assertTrue(Arrays.stream(McpSkillGroup.class.getDeclaredConstructors())
				.allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers())));
		assertEquals("McpSkillGroup[redacted]", McpSkillGroup.fromKeyAndSkillRegistrations(
				"private-key-canary", List.of(registration("test-skill", "private-uri-canary", Locale.ENGLISH))).toString());
	}

	private static McpSkillRegistration registration(String name, String variant, Locale locale) {
		McpSkillRegistration.Builder builder = McpSkillRegistration.withUriAndSkillBundle(
				URI.create("skill://host.invalid/" + variant + "/" + name + "/SKILL.md"), bundle(name));
		if (locale != null) builder.locale(locale);
		return builder.build();
	}

	private static McpSkillBundle bundle(String name) {
		return McpSkillBundle.fromFiles(Map.of("SKILL.md", ("---\nname: " + name
				+ "\ndescription: Synthetic description\n---\nBody\n").getBytes(StandardCharsets.UTF_8)));
	}
}
