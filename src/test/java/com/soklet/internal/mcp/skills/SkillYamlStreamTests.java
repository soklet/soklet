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
package com.soklet.internal.mcp.skills;

import com.soklet.internal.mcp.protocol.McpJsonArray;
import com.soklet.internal.mcp.protocol.McpJsonLimits;
import com.soklet.internal.mcp.protocol.McpJsonString;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.soklet.internal.mcp.skills.SkillYamlException.Reason.*;
import static com.soklet.internal.mcp.skills.SkillYamlNode.*;
import static org.junit.jupiter.api.Assertions.*;

/** Authored stream checks grounded in YAML 1.2.2 section 9, not a compatibility claim. */
class SkillYamlStreamTests {

	private static final SkillYamlLimits LIMITS = new SkillYamlLimits(65_536, 32, 4_096,
			8_192, 65_536, 1_000_000);

	@Test
	void emptyStreamsPrefixesAndSuffixesDoNotCreateDocuments() {
		for (String yaml : List.of("", "\n\r\n", "# comment\n", "\uFEFF", "\uFEFF# comment\n",
				"...\n", "... # suffix\n...\n# comment\n", "\uFEFF# prefix\n...\n\uFEFF# prefix\n"))
			assertTrue(parse(yaml).isEmpty(), yaml);
	}

	@Test
	void eachExplicitEmptyDocumentProducesOneRoot() {
		for (String lineBreak : List.of("\n", "\r\n", "\r")) {
			assertEquals(List.of("", "", ""), scalars(parse("---" + lineBreak + "--- # empty" + lineBreak
					+ "..." + lineBreak + "..." + lineBreak + "---")));
		}
	}

	@Test
	void bareExplicitAndDirectiveDocumentsShareOneStream() {
		List<SkillYamlNode> roots = parse("first\n---\n# empty\n...\n%YAML 1.2\n---\nkey: value\n");
		assertEquals(3, roots.size());
		assertEquals("first", scalar(roots.get(0)).value());
		assertEquals("", scalar(roots.get(1)).value());
		Mapping last = assertInstanceOf(Mapping.class, roots.get(2));
		assertEquals("key", scalar(last.entries().get(0).key()).value());
		assertEquals("value", scalar(last.entries().get(0).value()).value());
	}

	@Test
	void endMarkersPermitFollowingBareDocumentsAndRepeatedSuffixes() {
		for (String separator : List.of("...\n", "... # end\n...\n# between\n")) {
			List<SkillYamlNode> roots = parse("--- first\n" + separator + "key: value\n");
			assertEquals(2, roots.size());
			assertEquals("first", scalar(roots.get(0)).value());
			assertInstanceOf(Mapping.class, roots.get(1));
		}
		assertEquals(List.of("one", "two", "three"), scalars(parse("one\n...\ntwo\n...\nthree\n")));
	}

	@Test
	void streamBoundariesDoNotConsumePlainOrBlockScalarContent() {
		List<SkillYamlNode> roots = parse("--- plain\ncontinuation\n--- |\nblock\n%not-a-directive\n...\n--- last\n");
		assertEquals(List.of("plain continuation", "block\n%not-a-directive\n", "last"), scalars(roots));
		assertEquals(List.of("---ordinary", "...ordinary"), scalars(parse("---ordinary\n--- ...ordinary\n")));
	}

	@Test
	void bomPrefixesSeparateDocumentsWithoutChangingTheirValues() {
		assertEquals(List.of("one", "two"), scalars(parse("\uFEFF--- one\n...\n\uFEFF# prefix\n--- two\n")));
		assertEquals(List.of("one", "two"), scalars(parse("--- one\n\uFEFF--- two\n")));
		assertEquals(List.of("one"), scalars(parse("# prefix\n\uFEFF# prefix\n\uFEFF--- one\n...\n\uFEFF")));
		assertEquals("one\uFEFFtwo", scalar(parse("--- \"one\uFEFFtwo\"\n").get(0)).value());
	}

	@Test
	void bomDoesNotReplaceADocumentSeparationMarker() {
		failure(SYNTAX, "one\n\uFEFFtwo\n");
		failure(SYNTAX, "--- [one]\n\uFEFF[two]\n");
		failure(SYNTAX, "---\n\uFEFFbare\n");
	}

	@Test
	void embeddedBomIsOnlyPermittedInQuotedScalarContent() {
		for (String yaml : List.of("--- one\uFEFFtwo\n", "--- [one, \uFEFFtwo]\n",
				"--- |\none\uFEFFtwo\n", "--- [&an\uFEFFchor one]\n",
				"--- one # comment\uFEFFtext\n", "# comment\uFEFFtext\n--- one\n"))
			failure(SYNTAX, yaml);
		for (String quote : List.of("'", "\"")) {
			List<SkillYamlNode> roots = parse("--- [" + quote + "one\uFEFFtwo" + quote + "]\n");
			Sequence sequence = assertInstanceOf(Sequence.class, roots.get(0));
			assertEquals("one\uFEFFtwo", scalar(sequence.items().get(0)).value());
		}
	}

	@Test
	void directiveLookingPlainContinuationIsNotAStreamBoundary() {
		assertEquals(List.of("scalar %YAML 1.2", "second"),
				scalars(parse("---\nscalar\n%YAML 1.2\n--- second\n")));
		failure(SYNTAX, "--- scalar\n# terminates the scalar\n%YAML 1.2\n--- second\n");
	}

	@Test
	void returnedDocumentListsAreImmutable() {
		for (String yaml : List.of("", "--- one\n", "--- one\n--- two\n")) {
			List<SkillYamlNode> roots = parse(yaml);
			assertThrows(UnsupportedOperationException.class,
					() -> roots.add(new Scalar("extra", Style.PLAIN, Properties.EMPTY, new Position(1, 1))));
		}
	}

	@Test
	void versionDeclarationsResetAfterEachDocument() {
		SkillYamlBudget budget = new SkillYamlBudget(LIMITS);
		List<SkillYamlNode> roots = SkillYamlParser.parseStream("%YAML 1.1\n--- yes\n...\n%YAML 1.2\n--- yes\n", budget, 1);
		SkillYamlResolver resolver = new SkillYamlResolver(budget, McpJsonLimits.productionDefaults());
		assertEquals(2, roots.size());
		for (SkillYamlNode root : roots)
			assertEquals(new McpJsonString("yes"), resolver.resolve(root));
		failure(SYNTAX, "%YAML 1.2\n--- first\n...\n%YAML 1.2\n%YAML 1.2\n--- second\n");
	}

	@Test
	void namedTagDeclarationsAreDocumentLocal() {
		String first = "%TAG !e! !first-\n--- !e!kind one\n";
		List<SkillYamlNode> roots = parse(first + "...\n%TAG !e! !second-\n--- !e!kind two\n");
		assertEquals("!<!first-kind>", roots.get(0).properties().tag());
		assertEquals("!<!second-kind>", roots.get(1).properties().tag());
		failure(SYNTAX, first + "--- !e!kind two\n");
		failure(SYNTAX, first + "...\n--- !e!kind two\n");
		failure(SYNTAX, first + "...\n%TAG !e! !second-\n%TAG !e! !second-\n--- two\n");
	}

	@Test
	void defaultTagHandlesReturnAfterAnOverride() {
		for (String handle : List.of("!", "!!")) {
			List<SkillYamlNode> roots = parse("%TAG " + handle + " tag:example.test,2026:\n--- " + handle
					+ "str one\n--- " + handle + "str two\n");
			assertEquals("!<tag:example.test,2026:str>", roots.get(0).properties().tag());
			assertEquals(handle + "str", roots.get(1).properties().tag());
		}
	}

	@Test
	void missingOrMalformedStreamSeparationRejects() {
		for (String yaml : List.of("{}\n{}\n", "[]\n[]\n", "--- first\n... trailing\n",
				"--- []\n%YAML 1.2\n--- second\n", "--- []\n%TAG !e! !local-\n--- second\n",
				"first\n...\n%YAML 1.2\n", "first\n...\n%YAML 1.2\nsecond\n",
				"first\n...\n--- key: value\n", "first\n...\n--- - item\n"))
			failure(SYNTAX, yaml);
	}

	@Test
	void documentMarkersCannotTerminateUnclosedQuotedOrFlowNodes() {
		for (String marker : List.of("---", "...")) {
			for (String prefix : List.of("[one,\n", "{key:\n", "'one\n", "\"one\n"))
				failure(SYNTAX, "--- first\n--- " + prefix + marker + "\n");
		}
	}

	@Test
	void anchorsNeverResolveAcrossDocumentsEvenWithOneResolver() {
		SkillYamlBudget budget = new SkillYamlBudget(LIMITS);
		List<SkillYamlNode> roots = SkillYamlParser.parseStream("--- &shared first\n--- *shared\n", budget, 1);
		SkillYamlResolver resolver = new SkillYamlResolver(budget, McpJsonLimits.productionDefaults());
		assertEquals(new McpJsonString("first"), resolver.resolve(roots.get(0)));
		SkillYamlException error = assertThrows(SkillYamlException.class, () -> resolver.resolve(roots.get(1)));
		assertEquals(UNDEFINED_ALIAS, error.reason());
		assertEquals(2, error.line());
	}

	@Test
	void eachDocumentCanDeclareItsOwnAnchorWithTheSameName() {
		SkillYamlBudget budget = new SkillYamlBudget(LIMITS);
		List<SkillYamlNode> roots = SkillYamlParser.parseStream("--- [&a one, *a]\n--- [&a two, *a]\n", budget, 1);
		SkillYamlResolver resolver = new SkillYamlResolver(budget, McpJsonLimits.productionDefaults());
		assertEquals(new McpJsonArray(List.of(new McpJsonString("one"), new McpJsonString("one"))), resolver.resolve(roots.get(0)));
		assertEquals(new McpJsonArray(List.of(new McpJsonString("two"), new McpJsonString("two"))), resolver.resolve(roots.get(1)));
	}

	@Test
	void emptyDocumentRootsConsumeTheSharedNodeBudget() {
		SkillYamlLimits limits = new SkillYamlLimits(4096, 4, 4, 100, 4096, 100_000);
		assertEquals(4, parse("---\n".repeat(4), limits, 1).size());
		assertEquals(NODE_LIMIT, assertThrows(SkillYamlException.class, () -> parse("---\n".repeat(5), limits, 1)).reason());
		assertTrue(parse("...\n".repeat(100), limits, 1).isEmpty());
	}

	@Test
	void syntaxScalarTextIsAnAggregateStreamBudget() {
		SkillYamlLimits limits = new SkillYamlLimits(4096, 4, 100, 3, 5, 100_000);
		assertDoesNotThrow(() -> parse("--- abc\n", limits, 1));
		assertDoesNotThrow(() -> parse("--- def\n", limits, 1));
		assertEquals(SCALAR_LIMIT, assertThrows(SkillYamlException.class, () -> parse("--- abc\n--- def\n", limits, 1)).reason());
	}

	@Test
	void inputAndScanWorkLimitsCoverTheWholeStream() {
		String yaml = "--- é\n--- é\n";
		SkillYamlLimits input = new SkillYamlLimits(13, 4, 100, 100, 4096, 100_000);
		assertDoesNotThrow(() -> parse("--- é\n", input, 1));
		assertEquals(INPUT_LIMIT, assertThrows(SkillYamlException.class, () -> parse(yaml, input, 1)).reason());
		String many = "---\n".repeat(100);
		SkillYamlLimits work = new SkillYamlLimits(4096, 4, 1000, 100, 4096, many.length() + 20);
		assertEquals(WORK_LIMIT, assertThrows(SkillYamlException.class, () -> parse(many, work, 1)).reason());
	}

	@Test
	void nestingDepthResetsButBudgetCountersDoNot() {
		SkillYamlLimits limits = new SkillYamlLimits(4096, 2, 4, 100, 4096, 100_000);
		assertEquals(2, parse("--- [one]\n--- [two]\n", limits, 1).size());
		assertEquals(NODE_LIMIT, assertThrows(SkillYamlException.class,
				() -> parse("--- [one]\n--- [two]\n--- three\n", limits, 1)).reason());
		assertEquals(DEPTH_LIMIT, assertThrows(SkillYamlException.class,
				() -> parse("--- [one]\n--- [[two]]\n", limits, 1)).reason());
	}

	@Test
	void positionsRemainRelativeToTheOriginalSourceAndDiagnosticsStayRedacted() {
		List<SkillYamlNode> roots = parse("--- first\n...\n---\nkey: second\n", LIMITS, 7);
		assertEquals(new Position(7, 5), roots.get(0).position());
		assertEquals(new Position(10, 1), roots.get(1).position());
		SkillYamlException error = assertThrows(SkillYamlException.class,
				() -> parse("--- first\n...\n--- !private!CANARY value\n", LIMITS, 7));
		assertEquals(SYNTAX, error.reason());
		assertEquals(9, error.line());
		assertEquals(5, error.column());
		assertFalse(error.getMessage().contains("CANARY"));
		assertFalse(error.getMessage().contains("private"));
		assertNull(error.getCause());
	}

	@Test
	void singleDocumentEntryPointDoesNotSilentlyDiscardLaterDocuments() {
		for (String yaml : List.of("first\n--- second\n", "first\n...\nsecond\n", "---\n---\n"))
			assertEquals(UNSUPPORTED_SYNTAX, assertThrows(SkillYamlException.class,
					() -> SkillYamlParser.parse(yaml, new SkillYamlBudget(LIMITS), 1)).reason());
		assertEquals("", scalar(SkillYamlParser.parse("# empty\n", new SkillYamlBudget(LIMITS), 1)).value());
	}

	private static List<SkillYamlNode> parse(String yaml) { return parse(yaml, LIMITS, 1); }
	private static List<SkillYamlNode> parse(String yaml, SkillYamlLimits limits, int firstLine) {
		return SkillYamlParser.parseStream(yaml, new SkillYamlBudget(limits), firstLine);
	}
	private static Scalar scalar(SkillYamlNode node) { return assertInstanceOf(Scalar.class, node); }
	private static List<String> scalars(List<SkillYamlNode> nodes) {
		return nodes.stream().map(node -> scalar(node).value()).toList();
	}
	private static void failure(SkillYamlException.Reason reason, String yaml) {
		assertEquals(reason, assertThrows(SkillYamlException.class, () -> parse(yaml), yaml).reason(), yaml);
	}
}
