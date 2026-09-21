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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

/** Authored block-scalar regression checks; not general YAML qualification. */
class SkillYamlBlockScalarTests {
	private static final SkillYamlLimits LIMITS = new SkillYamlLimits(
			16_384, 32, 1_024, 4_096, 16_384, 1_000_000);

	@Test
	void bareDocumentScalarContentMayBeginAtColumnZero() {
		// YAML 1.2.2 production 207 uses parent indentation -1; Example 9.3
		// explicitly demonstrates the unindented PostScript scalar below.
		Assertions.assertEquals("foo\n", scalar(parse("|\nfoo\n")).value());
		Assertions.assertEquals("%!PS-Adobe-2.0\n", scalar(parse("|\n%!PS-Adobe-2.0\n")).value());
		Assertions.assertEquals("first second\n", scalar(parse(">\nfirst\nsecond\n")).value());
	}

	@Test
	void explicitDocumentScalarContentMayBeginAtColumnZero() {
		// Pinned corpus FP8R and DK3J: a content-level hash is not a comment.
		Assertions.assertEquals("line1 line2 line3\n", scalar(parse("--- >\nline1\nline2\nline3\n")).value());
		Assertions.assertEquals("line1 # no comment line3\n", scalar(parse(
				"--- >\nline1\n# no comment\nline3\n")).value());
	}

	@Test
	void explicitIndentationIsRelativeToTheDocumentParent() {
		Assertions.assertEquals("foo\n", scalar(parse("|1\nfoo\n")).value());
		Assertions.assertEquals("foo\n", scalar(parse("|2\n foo\n")).value());
		Assertions.assertEquals(" foo\n", scalar(parse("|1\n foo\n")).value());
		Assertions.assertEquals("one two", scalar(parse("--- >1-\none\ntwo\n")).value());
	}

	@Test
	void zeroIndentationContentCanStartWithATab() {
		Assertions.assertEquals("\ttext\n", scalar(parse("|\n\ttext\n")).value());
		Assertions.assertEquals("one\n\ttwo\nthree\n", scalar(parse(">\none\n\ttwo\nthree\n")).value());
		assertSyntax("value: |\n\ttext\n");
	}

	@Test
	void zeroIndentationScalarsStillRespectDocumentMarkers() {
		Assertions.assertEquals("", scalar(parse("|\n...\n")).value());
		Assertions.assertEquals("text\n", scalar(parse("|\ntext\n... # done\n")).value());
		Assertions.assertThrows(SkillYamlException.class, () -> parse("|\ntext\n---\nsecond\n"));
		Assertions.assertEquals("---text\n...text\n", scalar(parse("|\n---text\n...text\n")).value());
	}

	@Test
	void literalScalarStopsAtADedentedTrailingComment() {
		// Pinned corpus DWX9, including leading blanks and a whitespace-only
		// more-indented content line. The trailing hash belongs to the document.
		Assertions.assertEquals("\n\nliteral\n \n\ntext\n", scalar(parse(
				"|\n \n  \n  literal\n   \n  \n  text\n\n # Comment\n")).value());
	}

	@Test
	void trailingCommentsDoNotChangeStripClipOrKeep() {
		// Pinned corpus F8F9: only the first trailing comment is indentation
		// constrained; later comments may be as indented as the old content.
		SkillYamlNode.Mapping root = mapping(parse(" # Strip\n  # Comments:\n"
				+ "strip: |-\n  # text\n  \n # Clip\n  # comments:\n\n"
				+ "clip: |\n  # text\n \n # Keep\n  # comments:\n\n"
				+ "keep: |+\n  # text\n\n # Trail\n  # comments.\n"));
		Assertions.assertEquals(List.of("# text", "# text\n", "# text\n\n"), root.entries().stream()
				.map(entry -> scalar(entry.value()).value()).toList());
	}

	@Test
	void hashAtOrAboveContentIndentationRemainsScalarData() {
		Assertions.assertEquals("one\n# data\n # more data\n", scalar(parse(
				"|\n  one\n  # data\n   # more data\n")).value());
		Assertions.assertEquals("one # data\n # more data\n", scalar(parse(
				">\n  one\n  # data\n   # more data\n")).value());
	}

	@Test
	void dedentedOrdinaryTextAndOverindentedLeadingBlankRemainInvalid() {
		assertSyntax("value: |\n  one\n text\n");
		assertSyntax("value: |2\n text\n");
		assertSyntax("|\n  \n one\n");
		assertSyntax("value: |\n  \n text\n");
	}

	@Test
	void lineBreakNormalizationAndChompingPreservePhysicalEof() {
		for (String lineBreak : List.of("\n", "\r\n", "\r")) {
			for (String header : List.of("|", "|-", "|+")) {
				Assertions.assertEquals("text", scalar(parse(header + lineBreak + "text")).value());
				Assertions.assertEquals(header.equals("|-") ? "text" : "text\n",
						scalar(parse(header + lineBreak + "text" + lineBreak)).value());
			}
		}
		// No synthetic newline is added after whitespace content at physical EOF.
		Assertions.assertEquals("text\n ", scalar(parse("|\n  text\n   ")).value());
	}

	@Test
	void commentLookaheadAndScalarOutputStayBounded() {
		SkillYamlLimits lowWork = new SkillYamlLimits(16_384, 32, 1_024, 4_096, 16_384, 120);
		SkillYamlException work = Assertions.assertThrows(SkillYamlException.class, () ->
				SkillYamlParser.parse("|\n  text\n " + " ".repeat(100) + "# comment\n",
						new SkillYamlBudget(lowWork), 1));
		Assertions.assertEquals(SkillYamlException.Reason.WORK_LIMIT, work.reason());
		SkillYamlLimits shortScalar = new SkillYamlLimits(16_384, 32, 1_024, 8, 16_384, 1_000_000);
		SkillYamlException scalarLimit = Assertions.assertThrows(SkillYamlException.class, () ->
				SkillYamlParser.parse("|\n123456789\n", new SkillYamlBudget(shortScalar), 1));
		Assertions.assertEquals(SkillYamlException.Reason.SCALAR_LIMIT, scalarLimit.reason());
	}

	private static SkillYamlNode parse(String yaml) {
		return SkillYamlParser.parse(yaml, new SkillYamlBudget(LIMITS), 1);
	}

	private static SkillYamlNode.Scalar scalar(SkillYamlNode node) {
		return Assertions.assertInstanceOf(SkillYamlNode.Scalar.class, node);
	}

	private static SkillYamlNode.Mapping mapping(SkillYamlNode node) {
		return Assertions.assertInstanceOf(SkillYamlNode.Mapping.class, node);
	}

	private static void assertSyntax(String yaml) {
		SkillYamlException exception = Assertions.assertThrows(SkillYamlException.class, () -> parse(yaml));
		Assertions.assertEquals(SkillYamlException.Reason.SYNTAX, exception.reason());
	}
}
