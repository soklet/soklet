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

import com.soklet.internal.mcp.protocol.McpJsonLimits;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

/** Block node/property regressions; complex syntax keys remain invalid Skills metadata. */
class SkillYamlBlockNodeTests {
	private static final SkillYamlLimits LIMITS = new SkillYamlLimits(32_768, 32, 4_096,
			8_192, 32_768, 1_000_000);

	@Test
	void explicitKeysSupportMultilinePlainAndBlockScalars() {
		SkillYamlNode.Mapping root = mapping(parse("? first\n  second\n: null\n  continued\n? |\n  block key\n: value\n"));
		Assertions.assertEquals("first second", scalar(root.entries().get(0).key()).value());
		Assertions.assertEquals("null continued", scalar(root.entries().get(0).value()).value());
		Assertions.assertEquals("block key\n", scalar(root.entries().get(1).key()).value());
		Assertions.assertEquals("value", scalar(root.entries().get(1).value()).value());
	}

	@Test
	void explicitKeysAndValuesSupportCompactAndIndentlessSequences() {
		for (String yaml : List.of("? - a\n  - b\n: - c\n  - d\n", "?\n- a\n- b\n:\n- c\n- d\n")) {
			SkillYamlNode.Mapping root = mapping(parse(yaml));
			Assertions.assertEquals(List.of("a", "b"), values(sequence(root.entries().get(0).key())));
			Assertions.assertEquals(List.of("c", "d"), values(sequence(root.entries().get(0).value())));
		}
	}

	@Test
	void compactMappingsInExplicitKeysDoNotStealTheOuterValue() {
		SkillYamlNode.Mapping root = mapping(parse("? earth: blue\n: moon: white\n"));
		SkillYamlNode.Mapping key = mapping(root.entries().get(0).key());
		SkillYamlNode.Mapping value = mapping(root.entries().get(0).value());
		Assertions.assertEquals("earth", scalar(key.entries().get(0).key()).value());
		Assertions.assertEquals("blue", scalar(key.entries().get(0).value()).value());
		Assertions.assertEquals("moon", scalar(value.entries().get(0).key()).value());
		Assertions.assertEquals("white", scalar(value.entries().get(0).value()).value());
	}

	@Test
	void sameLineColonAfterExplicitKeyBelongsToCompactNestedMapping() {
		SkillYamlNode.Mapping root = mapping(parse("? []: x\n"));
		SkillYamlNode.Mapping key = mapping(root.entries().get(0).key());
		Assertions.assertTrue(sequence(key.entries().get(0).key()).items().isEmpty());
		Assertions.assertEquals("x", scalar(key.entries().get(0).value()).value());
		Assertions.assertEquals("", scalar(root.entries().get(0).value()).value());
		SkillYamlNode.Mapping nested = mapping(sequence(parse("- ? : x\n")).items().get(0));
		Assertions.assertEquals("x", scalar(mapping(nested.entries().get(0).key()).entries().get(0).value()).value());
	}

	@Test
	void missingExplicitValueDoesNotConsumeFollowingEntry() {
		SkillYamlNode.Mapping root = mapping(parse("? explicit key # Empty\n? |\n  block key\n: - one\n  - two\n"));
		Assertions.assertEquals(2, root.entries().size());
		Assertions.assertEquals("", scalar(root.entries().get(0).value()).value());
		Assertions.assertEquals(List.of("one", "two"), values(sequence(root.entries().get(1).value())));
	}

	@Test
	void compactValuesRemainForbiddenAfterImplicitColon() {
		for (String yaml : List.of("a: - b\n", "a: b: c\n", "a: ? b\n", "a: &x - b\n"))
			Assertions.assertThrows(SkillYamlException.class, () -> parse(yaml), yaml);
	}

	@Test
	void tabsMaySeparateScalarButDoNotBecomeCompactCollectionIndentation() {
		SkillYamlNode.Mapping root = mapping(parse("? a\n: -\tb\n  -  -\tc\n     - d\n"));
		SkillYamlNode.Sequence value = sequence(root.entries().get(0).value());
		Assertions.assertEquals("b", scalar(value.items().get(0)).value());
		Assertions.assertEquals(List.of("c", "d"), values(sequence(value.items().get(1))));
		for (String yaml : List.of("-\t- a\n", "?\t- a\n", "? a\n:\t- b\n"))
			Assertions.assertThrows(SkillYamlException.class, () -> parse(yaml), yaml);
	}

	@Test
	void tabsMaySeparateRootFlowAndScalarContent() {
		Assertions.assertTrue(sequence(parse("\t[\n\t]\n")).items().isEmpty());
		Assertions.assertTrue(mapping(parse("\t{}\n")).entries().isEmpty());
		Assertions.assertEquals("plain", scalar(parse("\tplain\n")).value());
		Assertions.assertEquals("quoted", scalar(parse("\t\"quoted\"\n")).value());
		Assertions.assertEquals("value\n", scalar(parse("\t|\nvalue\n")).value());
	}

	@Test
	void nestedTabsOnlySeparateAfterSufficientSpaceIndentation() {
		for (String content : List.of("bar", "\"bar\"", "[bar]", "{bar: value}", "&anchor bar")) {
			SkillYamlNode.Mapping root = mapping(parse("foo:\n \t" + content + "\n"));
			Assertions.assertEquals(1, root.entries().size());
			Assertions.assertThrows(SkillYamlException.class, () -> parse("foo:\n\t" + content + "\n"));
			Assertions.assertThrows(SkillYamlException.class, () -> parse("outer:\n  foo:\n  \t" + content + "\n"));
		}
		Assertions.assertEquals("bar", scalar(mapping(parse("foo:\n \tbar\n")).entries().get(0).value()).value());
	}

	@Test
	void tabsCannotSupplyBlockCollectionIndentationAtAnyEntry() {
		for (String yaml : List.of("\t- a\n", " \t- a\n", "\tfoo: bar\n", "\t? a\n",
				" a: b\n\tc: d\n", " - a\n\t- b\n", "foo:\n \tkey: value\n", "foo:\n \t- value\n"))
			Assertions.assertThrows(SkillYamlException.class, () -> parse(yaml), yaml);
	}

	@Test
	void emptyTaggedAndAnchoredBlockNodesKeepTheirProperties() {
		SkillYamlNode.Sequence sequence = sequence(parse("- &a\n- a\n-\n  &key : value\n  b: &b\n-\n  ? &d\n-\n  ? &e\n  : &v\n"));
		Assertions.assertEquals("a", sequence.items().get(0).properties().anchor());
		Assertions.assertEquals("", scalar(sequence.items().get(0)).value());
		SkillYamlNode.Mapping mapping = mapping(sequence.items().get(2));
		Assertions.assertEquals("key", mapping.entries().get(0).key().properties().anchor());
		Assertions.assertEquals("", scalar(mapping.entries().get(0).key()).value());
		Assertions.assertEquals("b", mapping.entries().get(1).value().properties().anchor());
		Assertions.assertEquals("d", mapping(sequence.items().get(3)).entries().get(0).key().properties().anchor());
		Assertions.assertEquals("v", mapping(sequence.items().get(4)).entries().get(0).value().properties().anchor());
	}

	@Test
	void emptyTaggedFlowNodesMayTouchCommasAndClosingDelimiters() {
		SkillYamlNode.Mapping root = mapping(parse("{foo: !!str, !!str : bar, last: [!!str, &a]}"));
		Assertions.assertEquals("", scalar(root.entries().get(0).value()).value());
		Assertions.assertNotNull(root.entries().get(0).value().properties().tag());
		Assertions.assertEquals("", scalar(root.entries().get(1).key()).value());
		SkillYamlNode.Sequence last = sequence(root.entries().get(2).value());
		Assertions.assertEquals("", scalar(last.items().get(0)).value());
		Assertions.assertEquals("a", last.items().get(1).properties().anchor());
	}

	@Test
	void flowPropertiesMaySpanSeparationLines() {
		SkillYamlNode.Mapping root = mapping(parse("!!map {\n  k: !!seq\n  [ a, !!str b]\n}\n"));
		Assertions.assertEquals(List.of("a", "b"), values(sequence(root.entries().get(0).value())));
		SkillYamlNode.Sequence anchored = sequence(parse("[!!str\n &a\n value, *a]"));
		Assertions.assertEquals("value", scalar(anchored.items().get(0)).value());
		Assertions.assertEquals("a", anchored.items().get(0).properties().anchor());
		Assertions.assertInstanceOf(SkillYamlNode.Alias.class, anchored.items().get(1));
	}

	@Test
	void propertiesOnlyLinesPreserveMappingParentIndentation() {
		SkillYamlNode.Mapping root = mapping(parse("seq:\n &anchor\n- a\n- b\nnext: value\n"));
		SkillYamlNode.Sequence sequence = sequence(root.entries().get(0).value());
		Assertions.assertEquals("anchor", sequence.properties().anchor());
		Assertions.assertEquals(List.of("a", "b"), values(sequence));
		Assertions.assertEquals("next", scalar(root.entries().get(1).key()).value());
		Assertions.assertEquals("root", sequence(parse("&root\n- a\n")).properties().anchor());
		SkillYamlNode.Mapping scalarRoot = mapping(parse("value:\n   !!str\n  >1\n text\n"));
		Assertions.assertEquals("text\n", scalar(scalarRoot.entries().get(0).value()).value());
	}

	@Test
	void scalarHeaderIndentationDoesNotBecomeTheContentIndentationBase() {
		Assertions.assertEquals("text\n", scalar(parse("  |\ntext\n")).value());
		SkillYamlNode.Mapping root = mapping(parse("a:\n  |1\n text\n"));
		Assertions.assertEquals("text\n", scalar(root.entries().get(0).value()).value());
		SkillYamlNode.Mapping example = mapping(parse("literal: |2\n  value\nfolded:\n   !foo\n  >1\n value\n"));
		Assertions.assertEquals("value\n", scalar(example.entries().get(0).value()).value());
		Assertions.assertEquals("value\n", scalar(example.entries().get(1).value()).value());
	}

	@Test
	void complexKeysAreSyntaxOnlyAndStillRejectedByMetadataResolver() {
		SkillYamlBudget budget = new SkillYamlBudget(LIMITS);
		SkillYamlNode root = SkillYamlParser.parse("? - one\n  - two\n: value\n", budget, 1);
		SkillYamlException error = Assertions.assertThrows(SkillYamlException.class,
				() -> new SkillYamlResolver(budget, McpJsonLimits.productionDefaults()).resolve(root));
		Assertions.assertEquals(SkillYamlException.Reason.TYPE, error.reason());
	}

	private static SkillYamlNode parse(String yaml) { return SkillYamlParser.parse(yaml, new SkillYamlBudget(LIMITS), 1); }
	private static SkillYamlNode.Mapping mapping(SkillYamlNode node) { return Assertions.assertInstanceOf(SkillYamlNode.Mapping.class, node); }
	private static SkillYamlNode.Sequence sequence(SkillYamlNode node) { return Assertions.assertInstanceOf(SkillYamlNode.Sequence.class, node); }
	private static SkillYamlNode.Scalar scalar(SkillYamlNode node) { return Assertions.assertInstanceOf(SkillYamlNode.Scalar.class, node); }
	private static List<String> values(SkillYamlNode.Sequence sequence) { return sequence.items().stream().map(node -> scalar(node).value()).toList(); }
}
