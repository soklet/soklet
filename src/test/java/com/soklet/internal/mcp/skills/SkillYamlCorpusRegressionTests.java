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

import com.soklet.internal.mcp.protocol.McpJsonCodec;
import com.soklet.internal.mcp.protocol.McpJsonLimits;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Reduced/authored regressions; the full pinned corpus is run separately. */
class SkillYamlCorpusRegressionTests {
	private static final SkillYamlLimits LIMITS = new SkillYamlLimits(32_768, 32, 4_096,
			8_192, 65_536, 1_000_000);
	private static final McpJsonCodec JSON = new McpJsonCodec(McpJsonLimits.productionDefaults());

	@Test
	void singlePairFlowSequenceEntriesKeepMappingsDistinctFromScalars() {
		assertJson("[{\"name\":\"value\"},{\"quoted\":\"adjacent\"},{\"empty\":null},\"url:https://example.test\"]",
				"[name: value, \"quoted\":adjacent, empty:, url:https://example.test]");
		assertJson("[{\"first\":{\"nested\":[1,2]}},{\"second\":\"value\"}]",
				"[first: {nested: [1, 2]}, second: value,]");
	}

	@Test
	void explicitFlowPairsAllowMultilineKeysAndMissingValues() {
		assertJson("[{\"first second\":\"value\"},{\"empty\":null}]",
				"[? first\n  second : value, ? empty]");
		assertJson("[{\"first second\":\"value\"}]", "[? \"first\n  second\" : value]");
	}

	@Test
	void compactFlowPairsKeepNullAndStructuredKeyNodesForResolverValidation() {
		for (String yaml : List.of("[: value]", "[? ]", "[[first, second]: value]", "[{}: value]")) {
			SkillYamlNode.Sequence sequence = (SkillYamlNode.Sequence) syntax(yaml, LIMITS);
			assertInstanceOf(SkillYamlNode.Mapping.class, sequence.items().get(0));
			assertReason(SkillYamlException.Reason.TYPE, () -> json(yaml));
		}
	}

	@Test
	void compactFlowKeysRetainTheirOwnAnchorInsteadOfAnchoringTheImplicitMapping() {
		assertJson("[{\"key\":\"value\"},\"key\"]", "[&key key: value, *key]");
	}

	@Test
	void compactFlowPairsEnforceImplicitKeyLineAndLengthLimits() {
		for (String yaml : List.of("[key\n: value]", "[\"first\n second\": value]",
				"[" + "a".repeat(1_025) + ": value]"))
			assertReason(SkillYamlException.Reason.SYNTAX, () -> syntax(yaml, LIMITS));
		assertDoesNotThrow(() -> syntax("[" + "🚀".repeat(1_024) + ": value]", LIMITS));
	}

	@Test
	void compactFlowPairContainerAndKeyNestingAreIncludedInBudgets() {
		// Sequence + compact mapping + key + value: exactly four syntax nodes.
		assertDoesNotThrow(() -> syntax("[a: b]", limits(3, 4)));
		assertReason(SkillYamlException.Reason.NODE_LIMIT, () -> syntax("[a: b]", limits(3, 3)));
		assertReason(SkillYamlException.Reason.DEPTH_LIMIT, () -> syntax("[a: b]", limits(2, 10)));
		assertDoesNotThrow(() -> syntax("[[a]: b]", limits(4, 5)));
		assertReason(SkillYamlException.Reason.DEPTH_LIMIT, () -> syntax("[[a]: b]", limits(3, 10)));
		assertDoesNotThrow(() -> syntax("[? a: b]", limits(3, 4)));
		assertReason(SkillYamlException.Reason.DEPTH_LIMIT, () -> syntax("[? a: b]", limits(2, 10)));
	}

	@Test
	void aColonFollowedByPlainContentRemainsPartOfTheKey() {
		assertJson("{\":name\":null}", "{:name}");
		assertJson("[\":name\",{\":name\":\"value\"}]", "[:name, :name: value]");
	}

	@Test
	void bareIndicatorCannotBecomeFlowPlainScalarBeforeACollectionDelimiter() {
		// Reduced from pinned cases YJV2 and G5U8, plus adjacent indicators.
		for (String yaml : List.of("[-]", "[-, -]", "[?]", "[? , -]", "{-}"))
			assertReason(SkillYamlException.Reason.SYNTAX, () -> syntax(yaml, LIMITS));
		assertJson("[\"-name\",\"?name\",\":name\",\"-\"]", "[-name, ?name, :name, '-']");
	}

	@Test
	void columnZeroDocumentMarkersAreNotContentInsideFlowCollections() {
		// N782: document boundaries must not be consumed as flow scalar content.
		for (String marker : List.of("---", "...")) {
			assertReason(SkillYamlException.Reason.SYNTAX, () -> syntax("[\n" + marker + " , value]\n", LIMITS));
			assertReason(SkillYamlException.Reason.SYNTAX, () -> syntax("{key:\n" + marker + " }\n", LIMITS));
		}
		assertJson("[\"---\",\"...\"]", "[\n --- ,\n ...\n]");
	}

	@Test
	void blockIndentationCannotBeSuppliedByTabs() {
		// Y79Y/000, /004 and /005.
		for (String yaml : List.of("foo: |\n\t\nbar: 1\n", "-\t-\n", "- \t-\n", "-\tkey: value\n"))
			assertReason(SkillYamlException.Reason.SYNTAX, () -> syntax(yaml, LIMITS));
		assertJson("[\"text\"]", "-\ttext\n");
		assertJson("{\"foo\":\"\\t\\n\"}", "foo: |1\n \t\n");
	}

	@Test
	void whitespaceOnlyEofDoesNotInventAnAuthoredLineBreak() {
		// The pinned corpus JEF9/02 and L24T/01 expects a synthesized LF.
		// YAML 1.2.2 §8.1.1.2's EOF alternative and Engine 3.0.1 disagree:
		// retain current behavior as an explicit oracle conflict, not a corpus pass.
		assertJson("[\"\"]", "- |+\n   ");
		assertJson("{\"foo\":\"x\\n \"}", "foo: |\n  x\n   ");
	}

	@Test
	void anAuthoredFinalBreakChangesTheWhitespaceEofFixtures() {
		// Keep a positive paired control for JEF9/02 and L24T/01: a physical
		// break is normalized to LF, unlike the zero-width EOF alternative.
		for (String lineBreak : List.of("\n", "\r\n", "\r")) {
			assertJson("[\"\"]", "- |+" + lineBreak + "   ");
			assertJson("[\"\\n\"]", "- |+" + lineBreak + "   " + lineBreak);
			assertJson("{\"foo\":\"x\\n \"}", "foo: |" + lineBreak + "  x" + lineBreak + "   ");
			assertJson("{\"foo\":\"x\\n \\n\"}", "foo: |" + lineBreak + "  x" + lineBreak + "   " + lineBreak);
		}
	}

	@Test
	void anchorOnEmptySequenceItemDoesNotConsumeItsFollowingSibling() {
		assertJson("[null,\"two\",null]", "- &one\n- two\n- *one\n");
		assertJson("{\"a\":[null,\"b\"]}", "a:\n- &a\n- b\n");
		assertJson("[\"\",\"two\"]", "- !!str\n- two\n");
		// An indentless sequence remains valid for a block mapping value.
		assertJson("{\"a\":[\"one\"],\"b\":[\"one\"]}", "a: &a\n- one\nb: *a\n");
		assertJson("[\"one\"]", "&root\n- one\n");
	}

	@Test
	void blockPlainKeysKeepEmbeddedPunctuationAndLeadingNonIndicatorColons() {
		assertJson("{\":foo\":\"value\",\":\":null,\"a[b\":\"c\",\"a]b\":\"d\",\"a\\\"b\":\"e\"}",
				":foo: value\n::\na[b: c\na]b: d\na\"b: e\n");
		assertJson("[{\"bla\\\"keks\":\"foo\"},{\"bla]keks\":\"foo\"}]", "- bla\"keks: foo\n- bla]keks: foo\n");
		assertJson("{\"text\":\"value\"}", "&map {text: value}");
		assertJson("{\"text\":\"value\"}", "!<tag:yaml.org,2002:map> {text: value}");
		assertJson("{\"a[b\":\"value\"}", "&key a[b: value\n");
	}

	@Test
	void aliasKeysRequireSeparatedValuesEvenWhenTheReferencedValueIsAString() {
		for (String yaml : List.of("[&a name, *a :bad]", "{first: &a name, *a :bad}"))
			assertReason(SkillYamlException.Reason.SYNTAX, () -> syntax(yaml, LIMITS));
		assertJson("[\"name\",{\"name\":\"good\"}]", "[&a name, *a : good]");
		assertJson("{\"first\":\"name\",\"name\":\"good\"}", "{first: &a name, *a : good}");
		assertJson("[{\"name\":\"adjacent\"}]", "[\"name\":adjacent]");
	}

	private static SkillYamlLimits limits(int depth, int nodes) {
		return new SkillYamlLimits(32_768, depth, nodes, 8_192, 65_536, 1_000_000);
	}
	private static SkillYamlNode syntax(String yaml, SkillYamlLimits limits) {
		return SkillYamlParser.parse(yaml, new SkillYamlBudget(limits), 1);
	}
	private static String json(String yaml) {
		SkillYamlBudget budget = new SkillYamlBudget(LIMITS);
		return JSON.toJson(new SkillYamlResolver(budget, JSON.limits()).resolve(SkillYamlParser.parse(yaml, budget, 1)));
	}
	private static void assertJson(String expected, String yaml) {
		assertEquals(JSON.parse(expected), JSON.parse(json(yaml)));
	}
	private static void assertReason(SkillYamlException.Reason reason, org.junit.jupiter.api.function.Executable action) {
		assertEquals(reason, assertThrows(SkillYamlException.class, action).reason());
	}
}
