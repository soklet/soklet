/*
 * Copyright 2022-2026 Revetware LLC.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */
package com.soklet.internal.mcp.skills;

import com.soklet.internal.mcp.protocol.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import static com.soklet.internal.mcp.skills.SkillYamlException.Reason.*;
import static org.junit.jupiter.api.Assertions.*;

class SkillFrontmatterTests {
	private static final SkillYamlLimits LIMITS = new SkillYamlLimits(32_768, 32,
			4_096, 8_192, 32_768, 1_000_000);
	private static final McpJsonLimits JSON_LIMITS = McpJsonLimits.productionDefaults();

	@Test
	void parsesOriginalDocumentWithoutRewritingOrRetainingCallerArrays() {
		String document = "\uFEFF---\r\nname: example\r\ndescription: >-\r\n  First line\r\n  second line\r\n"
				+ "metadata: {author: 'café', version: '01'}\r\n"
				+ "custom: {precise: 9007199254740993, date: 2026-09-20, enabled: TRUE}\r\n---\r\n# Body\r\n";
		byte[] original = document.getBytes(StandardCharsets.UTF_8);
		byte[] supplied = original.clone();
		SkillFrontmatter result = SkillFrontmatter.parse(supplied, LIMITS, JSON_LIMITS);
		Arrays.fill(supplied, (byte) 0);
		assertArrayEquals(original, result.source().originalBytes());
		byte[] inspection = result.source().originalBytes();
		Arrays.fill(inspection, (byte) 0);
		assertArrayEquals(original, result.source().originalBytes());
		assertEquals(new McpJsonString("First line second line"), result.metadata().members().get("description"));
		McpJsonObject custom = (McpJsonObject) result.metadata().members().get("custom");
		assertEquals(new McpJsonNumber(new BigDecimal("9007199254740993")), custom.members().get("precise"));
		assertEquals(new McpJsonString("2026-09-20"), custom.members().get("date"));
		assertSame(McpJsonBoolean.TRUE, custom.members().get("enabled"));
		assertEquals("# Body\r\n", new String(original, result.bodyByteOffset(),
				original.length - result.bodyByteOffset(), StandardCharsets.UTF_8));
		assertFalse(result.toString().contains("café"));
		assertThrows(UnsupportedOperationException.class, () -> result.metadata().members().clear());
	}

	@Test
	void anchorsRetainValuesAndNeverImplicitlyMergeMappings() {
		McpJsonObject metadata = parse("base: &base {first: 1}\ncopy: *base\n"
				+ "custom: {<<: *base, second: 2}\n").metadata();
		assertSame(metadata.members().get("base"), metadata.members().get("copy"));
		McpJsonObject custom = (McpJsonObject) metadata.members().get("custom");
		assertEquals(List.of("<<", "second"), List.copyOf(custom.members().keySet()));
		assertFalse(custom.members().containsKey("first"));
	}

	@Test
	void nestedAnchoredBlockValuesLeaveFollowingSiblingEntriesIntact() {
		McpJsonObject root = parse("outer:\n  base: &base\n    first: 1\n  copy: *base\n  last: yes\n").metadata();
		McpJsonObject outer = (McpJsonObject) root.members().get("outer");
		assertEquals(List.of("base", "copy", "last"), List.copyOf(outer.members().keySet()));
		assertEquals(outer.members().get("base"), outer.members().get("copy"));
	}

	@Test
	void quotedDuplicateAndNonStringKeysAreRejectedBeforeTheyCanBeCoerced() {
		assertReason(DUPLICATE_KEY, "name: one\n'name': two\n");
		assertReason(TYPE, "1: first\n\"1\": second\n");
		assertReason(TYPE, "? [one, two]\n: value\n");
		assertReason(DUPLICATE_KEY, "\"\\u0061\": first\na: second\n");
	}

	@Test
	void rejectsCustomObjectBinaryAndTimestampTagsAndNonfiniteNumbers() {
		for (String tag : List.of("!application", "!!java/object:java.lang.ProcessBuilder",
				"!!binary", "!!timestamp")) assertReason(UNSUPPORTED_TAG, "custom: " + tag + " value\n");
		for (String number : List.of(".inf", "-.Inf", "+.INF", ".nan")) assertReason(TYPE, "custom: " + number + "\n");
		assertEquals(new McpJsonString("true"), parse("value: !!str true\n").metadata().members().get("value"));
	}

	@Test
	void undefinedCyclicAndExpandedAliasesFailWithoutEchoingNamesOrContents() {
		assertReason(UNDEFINED_ALIAS, "custom: *private-canary\n");
		assertReason(CYCLIC_ALIAS, "custom: &private-canary [*private-canary]\n");
		StringBuilder yaml = new StringBuilder("a: &a [x,x,x,x,x,x,x,x,x,x]\n");
		for (char c = 'b'; c <= 'j'; ++c)
			yaml.append(c).append(": &").append(c).append(" [")
					.append(String.join(",", java.util.Collections.nCopies(10, "*" + (char) (c - 1)))).append("]\n");
		assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
			SkillYamlException failure = assertThrows(SkillYamlException.class, () -> parse(yaml.toString()));
			assertTrue(List.of(NODE_LIMIT, WORK_LIMIT, SCALAR_LIMIT, OUTPUT_LIMIT).contains(failure.reason()));
		});
	}

	@Test
	void framingAndParsingUseOneWorkBudgetAndDocumentLineNumbers() {
		byte[] bytes = "---\na: b\n---\nbody".getBytes(StandardCharsets.UTF_8);
		SkillYamlLimits tiny = new SkillYamlLimits(100, 8, 100, 100, 100, 3L * bytes.length);
		assertEquals(WORK_LIMIT, assertThrows(SkillYamlException.class,
				() -> SkillFrontmatter.parse(bytes, tiny, JSON_LIMITS)).reason());
		SkillYamlException failure = assertThrows(SkillYamlException.class,
				() -> parse("name: example\nvalue: *private-canary\n"));
		assertEquals(3, failure.line());
		assertEquals(8, failure.column());
		assertFalse(failure.getMessage().contains("private-canary"));
		assertNull(failure.getCause());
	}

	@Test
	void metadataMustBeAMappingAndInvalidInputDoesNotPoisonTheNextParse() {
		assertReason(TYPE, "[one, two]\n");
		assertReason(TYPE, "42\n");
		assertReason(SYNTAX, "x: [one\n");
		assertEquals(new McpJsonString("ok"), parse("name: ok\n").metadata().members().get("name"));
	}

	@Test
	void authoredMalformedCharacterSmokeAlwaysTerminatesWithinExplicitBudgets() {
		// Deterministic smoke only, not a claim of coverage-guided fuzz qualification.
		assertTimeoutPreemptively(Duration.ofSeconds(4), () -> {
			Random random = new Random(20260920);
			String alphabet = "abc012 :,-?[]{}'\"!&*|>\\\n\t";
			for (int example = 0; example < 1_000; ++example) {
				StringBuilder yaml = new StringBuilder();
				for (int length = random.nextInt(128); length > 0; --length)
					yaml.append(alphabet.charAt(random.nextInt(alphabet.length())));
				// Keep the closing delimiter on its own line so this reaches YAML
				// parsing instead of mostly exercising failed frontmatter framing.
				yaml.append('\n');
				try { parse(yaml.toString()); }
				catch (SkillYamlException expected) { assertNull(expected.getCause()); }
			}
		});
	}

	private static SkillFrontmatter parse(String yaml) {
		return SkillFrontmatter.parse(("---\n" + yaml + "---\n# Body\n").getBytes(StandardCharsets.UTF_8), LIMITS, JSON_LIMITS);
	}
	private static void assertReason(SkillYamlException.Reason reason, String yaml) {
		assertEquals(reason, assertThrows(SkillYamlException.class, () -> parse(yaml)).reason());
	}
}
