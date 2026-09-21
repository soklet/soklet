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

package com.soklet.internal.mcp.skills;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/** Authored syntax checks, not full YAML compatibility qualification. */
class SkillYamlParserTests {
	private static final SkillYamlLimits LIMITS = limits(16_384, 32, 1_024,
			4_096, 16_384, 1_000_000);

	@Test
	void blockMappingContainsNestedMappingAndIndentlessSequence() {
		SkillYamlNode.Mapping root = mapping(parse("name: demo\ncustom:\n- one\n- two\nmetadata:\n  author: example\n"));
		Assertions.assertEquals(List.of("name", "custom", "metadata"), keys(root));
		Assertions.assertEquals("demo", scalar(value(root, "name")).value());
		Assertions.assertEquals(List.of("one", "two"), scalarValues(sequence(value(root, "custom"))));
		Assertions.assertEquals("example", scalar(value(mapping(value(root, "metadata")), "author")).value());
	}

	@Test
	void compactSequenceMappingsKeepContinuationEntriesAndOrder() {
		SkillYamlNode.Sequence root = sequence(parse("- name: one\n  description: first\n- name: two\n  description: second\n"));
		Assertions.assertEquals(2, root.items().size());
		Assertions.assertEquals(List.of("name", "description"), keys(mapping(root.items().get(0))));
		Assertions.assertEquals("first", scalar(value(mapping(root.items().get(0)), "description")).value());
		Assertions.assertEquals("two", scalar(value(mapping(root.items().get(1)), "name")).value());
	}

	@Test
	void flowCollectionsRetainNestedStructureAndTrailingCommas() {
		SkillYamlNode.Mapping root = mapping(parse("{list: [one, {nested: two},], empty: {}, sequence: [],}"));
		SkillYamlNode.Sequence list = sequence(value(root, "list"));
		Assertions.assertEquals("one", scalar(list.items().get(0)).value());
		Assertions.assertEquals("two", scalar(value(mapping(list.items().get(1)), "nested")).value());
		Assertions.assertTrue(mapping(value(root, "empty")).entries().isEmpty());
		Assertions.assertTrue(sequence(value(root, "sequence")).items().isEmpty());
	}

	@Test
	void plainScalarsRemainUnresolvedEvenWhenTheyLookLikeOtherTypes() {
		List<String> spellings = List.of("true", "null", "42", "0x10", "9007199254740993", ".inf", "2026-09-17");
		SkillYamlNode.Sequence root = sequence(parse("[" + String.join(", ", spellings) + "]"));
		Assertions.assertEquals(spellings, scalarValues(root));
		for (SkillYamlNode node : root.items())
			Assertions.assertEquals(SkillYamlNode.Style.PLAIN, scalar(node).style());
	}

	@Test
	void singleQuotedScalarsDecodeDoubledQuotesWithoutInterpretingEscapes() {
		SkillYamlNode.Scalar result = scalar(parse("'isn''t \\n rewritten'"));
		Assertions.assertEquals("isn't \\n rewritten", result.value());
		Assertions.assertEquals(SkillYamlNode.Style.SINGLE_QUOTED, result.style());
	}

	@Test
	void doubleQuotedScalarsDecodeYamlEscapesIncludingSupplementaryCodePoints() {
		SkillYamlNode.Scalar result = scalar(parse("\"line\\n\\t\\x41\\u0042\\U0001F680\\\"\\\\\\/\""));
		Assertions.assertEquals("line\n\tAB🚀\"\\/", result.value());
		Assertions.assertEquals(SkillYamlNode.Style.DOUBLE_QUOTED, result.style());
	}

	@Test
	void multilineQuotedScalarsFoldBreaksAndRetainParagraphBreaks() {
		for (String quote : List.of("\"", "'")) {
			SkillYamlNode.Scalar result = scalar(parse(quote + "one\n  two\n\n  three" + quote));
			Assertions.assertEquals("one two\nthree", result.value());
		}
		Assertions.assertEquals("onetwo", scalar(parse("\"one\\\n  two\"")).value());
	}

	@Test
	void escapedWhitespaceBeforePhysicalBreakSurvivesQuotedScalarFolding() {
		List<String> escapes = List.of("\\ ", "\\t", "\\u0020", "\\u2003");
		List<String> decoded = List.of(" ", "\t", " ", "\u2003");
		for (String lineBreak : List.of("\n", "\r\n", "\r")) {
			for (int index = 0; index < escapes.size(); ++index) {
				String yaml = "\"one" + escapes.get(index) + lineBreak + "  two\"";
				Assertions.assertEquals("one" + decoded.get(index) + " two", scalar(parse(yaml)).value());
			}
		}
	}

	@Test
	void literalBlockScalarsHonorStripClipAndKeepChomping() {
		for (String lineBreak : List.of("\n", "\r\n", "\r")) {
			for (String indicator : List.of("|-", "|", "|+")) {
				String document = "value: " + indicator + lineBreak
						+ "  one" + lineBreak + "  two" + lineBreak + lineBreak;
				SkillYamlNode.Scalar result = scalar(value(mapping(parse(document)), "value"));
				String expected = indicator.equals("|-") ? "one\ntwo"
						: indicator.equals("|") ? "one\ntwo\n" : "one\ntwo\n\n";
				Assertions.assertEquals(expected, result.value(), indicator);
				Assertions.assertEquals(SkillYamlNode.Style.LITERAL, result.style());
			}
		}
	}

	@Test
	void foldedBlockScalarsFoldOrdinaryLinesButPreserveParagraphBreaks() {
		SkillYamlNode.Scalar result = scalar(value(mapping(parse("value: >-\n  one\n  two\n\n  three\n")), "value"));
		Assertions.assertEquals("one two\nthree", result.value());
		Assertions.assertEquals(SkillYamlNode.Style.FOLDED, result.style());
	}

	@Test
	void blockScalarsHonorExplicitIndentationIndicatorsInEitherOrder() {
		for (String indicator : List.of("|2-", "|-2")) {
			SkillYamlNode.Scalar result = scalar(value(mapping(parse("value: " + indicator + "\n   one\n   two\n")), "value"));
			Assertions.assertEquals(" one\n two", result.value(), indicator);
		}
		Assertions.assertEquals("one two", scalar(value(mapping(parse("value: >2-\n  one\n  two\n")), "value")).value());
	}

	@Test
	void blockScalarHeaderCommentsRequireSeparationFromIndicators() {
		for (String indicator : List.of("|", "|-", "|1")) {
			assertFailure(SkillYamlException.Reason.SYNTAX,
					"value: " + indicator + "# comment\n  text\n", LIMITS);
			Assertions.assertDoesNotThrow(() -> parse("value: " + indicator + " # comment\n  text\n"));
		}
	}

	@Test
	void rootBlockScalarAllowsZeroIndentationAndDoesNotConsumeDocumentEndMarker() {
		Assertions.assertEquals("foo\n", scalar(parse("|\nfoo\n")).value());
		SkillYamlNode.Scalar empty = scalar(parse("|\n...\n"));
		Assertions.assertEquals("", empty.value());
		Assertions.assertEquals(SkillYamlNode.Style.LITERAL, empty.style());
		Assertions.assertEquals("foo\n", scalar(parse("|\n foo\n")).value());
	}

	@Test
	void anchorsTagsAndAliasesSurviveWithoutExpansionOrTagResolution() {
		SkillYamlNode.Mapping root = mapping(parse("base: &entry !custom [one, two]\ncopy: *entry\ntext: !!str true\n"));
		SkillYamlNode.Sequence base = sequence(value(root, "base"));
		Assertions.assertEquals("entry", base.properties().anchor());
		Assertions.assertEquals("!custom", base.properties().tag());
		SkillYamlNode.Alias alias = Assertions.assertInstanceOf(SkillYamlNode.Alias.class, value(root, "copy"));
		Assertions.assertEquals("entry", alias.name());
		Assertions.assertEquals(SkillYamlNode.Properties.EMPTY, alias.properties());
		Assertions.assertEquals("!!str", value(root, "text").properties().tag());
		Assertions.assertEquals("true", scalar(value(root, "text")).value());
	}

	@Test
	void duplicateAndNonStringMappingKeysRemainAvailableForSemanticValidation() {
		SkillYamlNode.Mapping root = mapping(parse("{a: one, a: two, 1: number, [x, y]: sequence-key}"));
		Assertions.assertEquals(4, root.entries().size());
		Assertions.assertEquals("a", scalar(root.entries().get(0).key()).value());
		Assertions.assertEquals("a", scalar(root.entries().get(1).key()).value());
		Assertions.assertEquals("1", scalar(root.entries().get(2).key()).value());
		Assertions.assertEquals(List.of("x", "y"), scalarValues(sequence(root.entries().get(3).key())));
		Assertions.assertEquals("two", scalar(root.entries().get(1).value()).value());
	}

	@Test
	void bracedFlowMappingKeysMaySpanLinesWithoutExplicitQuestionIndicator() {
		for (String yaml : List.of("{a\n: value}", "{? a\n: value}", "{\"a\"\n: value}")) {
			SkillYamlNode.Mapping root = mapping(parse(yaml));
			Assertions.assertEquals(List.of("a"), keys(root));
			Assertions.assertEquals("value", scalar(root.entries().get(0).value()).value());
		}
		Assertions.assertEquals(List.of("a b"), keys(mapping(parse("{\"a\n  b\": value}"))));
	}

	@Test
	void blockImplicitKeysHave1024CodePointLimitButBracedFlowMappingsDoNot() {
		for (String codePoint : List.of("a", "🚀")) {
			String acceptedKey = codePoint.repeat(1_024);
			String rejectedKey = codePoint.repeat(1_025);
			Assertions.assertEquals(List.of(acceptedKey), keys(mapping(parse(acceptedKey + ": value\n"))));
			assertFailure(SkillYamlException.Reason.SYNTAX, rejectedKey + ": value\n", LIMITS);
			Assertions.assertEquals(List.of(rejectedKey), keys(mapping(parse("{" + rejectedKey + ": value}"))));
		}
	}

	@Test
	void commentsRespectPlainAndQuotedColonAndHashBoundaries() {
		SkillYamlNode.Mapping root = mapping(parse("# before\nurl: https://example.test/a#fragment # trailing\nidentifier: abc#literal\nnote: \"a: b # literal\"\n"));
		Assertions.assertEquals("https://example.test/a#fragment", scalar(value(root, "url")).value());
		Assertions.assertEquals("abc#literal", scalar(value(root, "identifier")).value());
		Assertions.assertEquals("a: b # literal", scalar(value(root, "note")).value());
	}

	@Test
	void emptyNodesRetainPlainEmptyScalarsDistinctFromEmptyCollections() {
		SkillYamlNode.Mapping root = mapping(parse("a:\nb: []\nc: {}\n"));
		Assertions.assertEquals("", scalar(value(root, "a")).value());
		Assertions.assertEquals(SkillYamlNode.Style.PLAIN, scalar(value(root, "a")).style());
		Assertions.assertTrue(sequence(value(root, "b")).items().isEmpty());
		Assertions.assertTrue(mapping(value(root, "c")).entries().isEmpty());
		Assertions.assertEquals(List.of("", "value"), scalarValues(sequence(parse("-\n- value\n"))));
		Assertions.assertEquals("", scalar(parse("# comment only\n")).value());
	}

	@Test
	void multilinePlainScalarsFoldContinuationAndParagraphBreaks() {
		SkillYamlNode.Mapping root = mapping(parse("description: one\n  two\n\n  three\n"));
		Assertions.assertEquals("one two\nthree", scalar(value(root, "description")).value());
	}

	@Test
	void tabsCannotProvidePlainScalarContinuationIndentation() {
		for (String indentation : List.of("\t", "\t "))
			assertFailure(SkillYamlException.Reason.SYNTAX,
					"description: one\n" + indentation + "two\n", LIMITS);
		Assertions.assertEquals("one two", scalar(value(mapping(parse(
				"description: one\n \ttwo\n")), "description")).value());
	}

	@Test
	void sourceLinesAreMappedAndSyntaxDiagnosticsDoNotExposeAuthoredText() {
		SkillYamlNode.Mapping root = mapping(parse("name: demo\nvalue: plain\n", LIMITS, 7));
		Assertions.assertEquals(7, root.position().line());
		Assertions.assertEquals(8, value(root, "value").position().line());
		SkillYamlException exception = Assertions.assertThrows(SkillYamlException.class,
				() -> parse("name: demo\nvalue: \"private-canary", LIMITS, 7));
		Assertions.assertEquals(SkillYamlException.Reason.SYNTAX, exception.reason());
		Assertions.assertEquals(8, exception.line());
		Assertions.assertTrue(exception.column() >= 1);
		Assertions.assertFalse(exception.getMessage().contains("private-canary"));
		Assertions.assertNull(exception.getCause());
	}

	@Test
	void invalidYamlCharactersUseActualSourceLineAndCodePointColumn() {
		for (String lineBreak : List.of("\n", "\r\n", "\r")) {
			SkillYamlException exception = Assertions.assertThrows(SkillYamlException.class,
					() -> parse("first: ok" + lineBreak + "value: 🚀\u0001", LIMITS, 7));
			Assertions.assertEquals(SkillYamlException.Reason.SYNTAX, exception.reason());
			Assertions.assertEquals(8, exception.line());
			Assertions.assertEquals(9, exception.column());
		}
	}

	@Test
	void initialUnsupportedGrammarIsExplicitlyClassified() {
		for (String yaml : List.of("a: one\n---\nb: two\n"))
			assertFailure(SkillYamlException.Reason.UNSUPPORTED_SYNTAX, yaml, LIMITS);
	}

	@Test
	void inputByteLimitCountsUtf8BytesInclusively() {
		Assertions.assertEquals("é", scalar(parse("é", limits(2, 4, 4, 4, 4, 1_000), 1)).value());
		assertFailure(SkillYamlException.Reason.INPUT_LIMIT, "é", limits(1, 4, 4, 4, 4, 1_000));
	}

	@Test
	void nodeLimitIncludesContainersAndIsInclusive() {
		Assertions.assertEquals(List.of("a"), scalarValues(sequence(parse("[a]", limits(32, 4, 2, 8, 8, 1_000), 1))));
		assertFailure(SkillYamlException.Reason.NODE_LIMIT, "[a]", limits(32, 4, 1, 8, 8, 1_000));
		Assertions.assertDoesNotThrow(() -> parse("[a,b]", limits(32, 4, 3, 8, 8, 1_000), 1));
		assertFailure(SkillYamlException.Reason.NODE_LIMIT, "[a,b]", limits(32, 4, 2, 8, 8, 1_000));
	}

	@Test
	void depthLimitIncludesRootAndScalarDepthInclusively() {
		Assertions.assertDoesNotThrow(() -> parse("[[a]]", limits(32, 3, 8, 8, 8, 1_000), 1));
		assertFailure(SkillYamlException.Reason.DEPTH_LIMIT, "[[a]]", limits(32, 2, 8, 8, 8, 1_000));
	}

	@Test
	void scalarLimitCountsDecodedUtf16UnitsInclusively() {
		Assertions.assertEquals("🚀", scalar(parse("🚀", limits(32, 4, 8, 2, 8, 1_000), 1)).value());
		assertFailure(SkillYamlException.Reason.SCALAR_LIMIT, "🚀", limits(32, 4, 8, 1, 8, 1_000));
		Assertions.assertEquals("a", scalar(parse("\"\\u0061\"", limits(32, 4, 8, 1, 8, 1_000), 1)).value());
	}

	@Test
	void totalTextLimitAccumulatesAcrossScalarsInclusively() {
		Assertions.assertDoesNotThrow(() -> parse("[a,b]", limits(32, 4, 8, 8, 2, 1_000), 1));
		assertFailure(SkillYamlException.Reason.SCALAR_LIMIT, "[a,b]", limits(32, 4, 8, 8, 1, 1_000));
	}

	@Test
	void workLimitStopsParsingWithoutExposingInput() {
		Assertions.assertDoesNotThrow(() -> parse("[one,two]", limits(64, 4, 8, 8, 16, 10_000), 1));
		SkillYamlException exception = Assertions.assertThrows(SkillYamlException.class,
				() -> parse("[private-canary]", limits(64, 4, 8, 32, 32, 1), 1));
		Assertions.assertEquals(SkillYamlException.Reason.WORK_LIMIT, exception.reason());
		Assertions.assertFalse(exception.getMessage().contains("private-canary"));
	}

	@Test
	void syntaxNodesSnapshotCollectionsAndRedactDiagnosticRepresentations() {
		SkillYamlNode.Position position = new SkillYamlNode.Position(1, 1);
		SkillYamlNode.Properties properties = new SkillYamlNode.Properties("!private-canary", "private-canary");
		SkillYamlNode.Scalar scalar = new SkillYamlNode.Scalar("private-canary",
				SkillYamlNode.Style.PLAIN, properties, position);
		List<SkillYamlNode> items = new ArrayList<>(List.of(scalar));
		SkillYamlNode.Sequence sequence = new SkillYamlNode.Sequence(items, properties, position);
		items.clear();
		Assertions.assertEquals(1, sequence.items().size());
		Assertions.assertThrows(UnsupportedOperationException.class, () -> sequence.items().clear());
		SkillYamlNode.Entry entry = new SkillYamlNode.Entry(scalar, scalar);
		List<SkillYamlNode.Entry> entries = new ArrayList<>(List.of(entry));
		SkillYamlNode.Mapping mapping = new SkillYamlNode.Mapping(entries, properties, position);
		entries.clear();
		Assertions.assertEquals(1, mapping.entries().size());
		Assertions.assertThrows(UnsupportedOperationException.class, () -> mapping.entries().clear());
		for (Object object : List.of(properties, scalar, sequence, entry, mapping,
				new SkillYamlNode.Alias("private-canary", position)))
			Assertions.assertFalse(object.toString().contains("private-canary"));
		Assertions.assertThrows(IllegalArgumentException.class, () -> new SkillYamlNode.Position(0, 1));
		Assertions.assertThrows(IllegalArgumentException.class, () -> new SkillYamlNode.Position(1, 0));
		Assertions.assertThrows(NullPointerException.class,
				() -> new SkillYamlNode.Scalar(null, SkillYamlNode.Style.PLAIN, properties, position));
		Assertions.assertThrows(NullPointerException.class,
				() -> new SkillYamlNode.Entry(scalar, null));
	}

	@Test
	void privateLimitConfigurationRequiresPositiveBoundsAndBoundedDepth() {
		Assertions.assertDoesNotThrow(() -> limits(1, 1, 1, 1, 1, 1));
		Assertions.assertDoesNotThrow(() -> limits(1, 256, 1, 1, 1, 1));
		Assertions.assertThrows(IllegalArgumentException.class, () -> limits(0, 1, 1, 1, 1, 1));
		Assertions.assertThrows(IllegalArgumentException.class, () -> limits(1, 0, 1, 1, 1, 1));
		Assertions.assertThrows(IllegalArgumentException.class, () -> limits(1, 257, 1, 1, 1, 1));
		Assertions.assertThrows(IllegalArgumentException.class, () -> limits(1, 1, 0, 1, 1, 1));
		Assertions.assertThrows(IllegalArgumentException.class, () -> limits(1, 1, 1, 0, 1, 1));
		Assertions.assertThrows(IllegalArgumentException.class, () -> limits(1, 1, 1, 1, 0, 1));
		Assertions.assertThrows(IllegalArgumentException.class, () -> limits(1, 1, 1, 1, 1, 0));
	}

	private static SkillYamlNode parse(String yaml) {
		return parse(yaml, LIMITS, 1);
	}

	private static SkillYamlNode parse(String yaml, SkillYamlLimits limits, int firstLine) {
		return SkillYamlParser.parse(yaml, new SkillYamlBudget(limits), firstLine);
	}

	private static SkillYamlLimits limits(int inputBytes, int depth, int nodes,
			int scalarCharacters, long totalCharacters, long work) {
		return new SkillYamlLimits(inputBytes, depth, nodes, scalarCharacters, totalCharacters, work);
	}

	private static void assertFailure(SkillYamlException.Reason reason, String yaml,
			SkillYamlLimits limits) {
		SkillYamlException exception = Assertions.assertThrows(SkillYamlException.class,
				() -> parse(yaml, limits, 1));
		Assertions.assertEquals(reason, exception.reason());
	}

	private static SkillYamlNode.Scalar scalar(SkillYamlNode node) {
		return Assertions.assertInstanceOf(SkillYamlNode.Scalar.class, node);
	}

	private static SkillYamlNode.Sequence sequence(SkillYamlNode node) {
		return Assertions.assertInstanceOf(SkillYamlNode.Sequence.class, node);
	}

	private static SkillYamlNode.Mapping mapping(SkillYamlNode node) {
		return Assertions.assertInstanceOf(SkillYamlNode.Mapping.class, node);
	}

	private static SkillYamlNode value(SkillYamlNode.Mapping mapping, String key) {
		return mapping.entries().stream()
				.filter(entry -> entry.key() instanceof SkillYamlNode.Scalar scalar && scalar.value().equals(key))
				.findFirst().orElseThrow().value();
	}

	private static List<String> keys(SkillYamlNode.Mapping mapping) {
		return mapping.entries().stream().map(entry -> scalar(entry.key()).value()).toList();
	}

	private static List<String> scalarValues(SkillYamlNode.Sequence sequence) {
		return sequence.items().stream().map(node -> scalar(node).value()).toList();
	}
}
