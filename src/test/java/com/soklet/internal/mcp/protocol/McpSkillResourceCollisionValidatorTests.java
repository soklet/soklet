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
package com.soklet.internal.mcp.protocol;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

public class McpSkillResourceCollisionValidatorTests {
	@Test
	void rejectsRootAndSupportingFileMatches() {
		assertRejected(List.of("skill://host/{name}/SKILL.md"),
				URI.create("skill://host/example/SKILL.md"));
		assertRejected(List.of("skill://host/example/references/{file}"),
				URI.create("skill://host/example/references/guide.txt"));
	}

	@Test
	void acceptsUnrelatedRoutesWithoutTreatingVariablesAsRecursiveGlobs() {
		Assertions.assertDoesNotThrow(() -> validate(
				List.of("skill://host/{name}/SKILL.md"),
				URI.create("skill://host/parent/child/SKILL.md")));
		Assertions.assertDoesNotThrow(() -> validate(
				List.of("skill://host/{name}/README.md"),
				URI.create("skill://host/example/SKILL.md")));
	}

	@Test
	void reusesSchemeServerHostAndPercentTripletCaseNormalization() {
		URI uppercase = URI.create("SKILL://HOST/example/%C3%A9.txt");
		URI lowercase = URI.create("skill://host/example/%c3%a9.txt");
		Assertions.assertEquals(uppercase, lowercase);
		assertRejected(List.of("skill://host/{name}/%c3%a9.txt"), uppercase);
		assertRejected(List.of("SKILL://HOST/{name}/%C3%A9.txt"), lowercase);
	}

	@Test
	void doesNotBroadenPathOrRegistryAuthorityIdentity() {
		Assertions.assertDoesNotThrow(() -> validate(
				List.of("skill://host/Example/{file}"),
				URI.create("skill://host/example/SKILL.md")));
		Assertions.assertDoesNotThrow(() -> validate(
				List.of("skill://host/%61/{file}"),
				URI.create("skill://host/a/SKILL.md")));
		Assertions.assertDoesNotThrow(() -> validate(
				List.of("skill://REGISTRY:catalog/{name}/SKILL.md"),
				URI.create("skill://registry:catalog/example/SKILL.md")));
		assertRejected(List.of("skill://REGISTRY:catalog/{name}/SKILL.md"),
				URI.create("skill://REGISTRY:catalog/example/SKILL.md"));
	}

	@Test
	void checksEveryFileAndEveryTemplate() {
		assertRejected(List.of("skill://other/{name}/SKILL.md",
				"skill://host/example/{file}"),
				URI.create("skill://unrelated/example/SKILL.md"),
				URI.create("skill://host/example/readme.txt"));
	}

	@Test
	void emptyTemplateSetDoesNotImposeTheTemplateRoutedUriCeiling() {
		URI longUri = uriOfLength(65_536);
		Assertions.assertDoesNotThrow(() -> validate(List.of(), longUri));
		Assertions.assertDoesNotThrow(() -> validate(List.of()));
	}

	@Test
	void matchingUncertaintyFailsClosedAtExistingUriBoundary() {
		List<String> unrelated = List.of("skill://other/{name}/SKILL.md");
		Assertions.assertDoesNotThrow(() -> validate(unrelated, uriOfLength(65_535)));
		assertRejected(unrelated, uriOfLength(65_536));
		assertRejected(unrelated, URI.create("relative/SKILL.md"));
	}

	@Test
	void compilesAndBoundsTemplatesEvenWhenNoFileNeedsMatching() {
		List<String> maximum = new ArrayList<>();
		for (int index = 0; index < 256; ++index)
			maximum.add("skill://host/" + index + "/{file}");
		Assertions.assertDoesNotThrow(() -> validate(maximum));
		maximum.add("skill://host/overflow/{file}");
		assertRejected(maximum);
		assertRejected(List.of("skill://host/{duplicate-secret}/{duplicate-secret}"));
		assertRejected(List.of("skill://host/" + "a".repeat(8_192) + "/{file}"));
	}

	@Test
	void countsActualTemplateIterationRatherThanReportedSize() {
		List<String> deceptive = new AbstractList<>() {
			@Override public String get(int index) { throw new AssertionError(); }
			@Override public int size() { return 0; }
			@Override public Iterator<String> iterator() {
				return java.util.Collections.nCopies(257, "skill://host/{file}").iterator();
			}
		};
		assertRejected(deceptive);
	}

	@Test
	void sharesExistingMatchingWorkBudgetAcrossAllCandidatesForOneUri() {
		// Every candidate passes literal-prefix/suffix pruning, but a Level-1
		// variable cannot consume the intervening raw slash. Each costs exactly
		// four times 65,536 cells; 32 reaches the router's 8,388,608-cell ceiling.
		List<String> candidates = new ArrayList<>();
		for (int index = 0; index < 32; ++index)
			candidates.add("skill://host/{name" + index + "}/SKILL.md");
		URI uri = uriOfLength(65_535);
		Assertions.assertDoesNotThrow(() -> validate(candidates, uri));
		candidates.add("skill://host/{overflow}/SKILL.md");
		assertRejected(candidates, uri);
	}

	@Test
	void prunesUnrelatedTemplatesBeforeChargingMatchingWork() {
		List<String> unrelated = new ArrayList<>();
		for (int index = 0; index < 256; ++index)
			unrelated.add("skill://other/" + index + "/{file}");
		Assertions.assertDoesNotThrow(() -> validate(unrelated, uriOfLength(65_535)));
	}

	@Test
	void failuresDoNotExposeUrisTemplatesOrParserCauses() {
		IllegalArgumentException collision = assertRejected(
				List.of("skill://host/{name}/SKILL.md"),
				URI.create("skill://host/private-secret/SKILL.md"));
		IllegalArgumentException syntax = assertRejected(
				List.of("skill://host/{parser-secret}/{parser-secret}"));
		Assertions.assertEquals(collision.getMessage(), syntax.getMessage());
		Assertions.assertFalse(collision.toString().contains("secret"));
		Assertions.assertFalse(syntax.toString().contains("secret"));
		Assertions.assertNull(collision.getCause());
		Assertions.assertNull(syntax.getCause());
	}

	@Test
	void nullContractsFailWithFixedDiagnostics() {
		Assertions.assertThrows(NullPointerException.class, () ->
				McpSkillResourceCollisionValidator.requireNoTemplateCollisions(null, List.of()));
		Assertions.assertThrows(NullPointerException.class, () ->
				McpSkillResourceCollisionValidator.requireNoTemplateCollisions(List.of(), null));
		Assertions.assertThrows(NullPointerException.class, () ->
				validate(Arrays.asList((String) null)));
		Assertions.assertThrows(NullPointerException.class, () ->
				validate(List.of("skill://host/{file}"), (URI) null));
	}

	private static URI uriOfLength(int length) {
		String prefix = "skill://host/";
		String suffix = "/nested/SKILL.md";
		return URI.create(prefix + "a".repeat(length - prefix.length() - suffix.length()) + suffix);
	}

	private static void validate(List<String> templates, URI... uris) {
		McpSkillResourceCollisionValidator.requireNoTemplateCollisions(templates, Arrays.asList(uris));
	}

	private static IllegalArgumentException assertRejected(List<String> templates, URI... uris) {
		return Assertions.assertThrows(IllegalArgumentException.class, () -> validate(templates, uris));
	}
}
