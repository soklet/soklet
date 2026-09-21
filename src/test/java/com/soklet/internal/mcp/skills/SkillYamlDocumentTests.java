/*
 * Copyright 2022-2026 Revetware LLC.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */
package com.soklet.internal.mcp.skills;

import com.soklet.internal.mcp.protocol.McpJsonLimits;
import com.soklet.internal.mcp.protocol.McpJsonString;
import com.soklet.internal.mcp.protocol.McpJsonValue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.soklet.internal.mcp.skills.SkillYamlException.Reason.*;
import static com.soklet.internal.mcp.skills.SkillYamlNode.*;
import static org.junit.jupiter.api.Assertions.*;

/** Authored document/tag checks; the single-document API is not a stream parser. */
class SkillYamlDocumentTests {
	private static final SkillYamlLimits LIMITS = new SkillYamlLimits(65_536, 32, 4_096,
			8_192, 65_536, 1_000_000);

	@Test
	void explicitDocumentHeaderAllowsSameLineFlowAndScalarNodes() {
		for (String lineBreak : List.of("\n", "\r", "\r\n")) {
			assertEquals("text", scalar(parse("--- text" + lineBreak + "..." + lineBreak)).value());
			assertEquals("text", scalar(parse("--- 'text'" + lineBreak)).value());
			assertEquals(1, assertInstanceOf(Sequence.class, parse("--- [text]" + lineBreak)).items().size());
			assertEquals(1, assertInstanceOf(Mapping.class, parse("--- {key: text}" + lineBreak)).entries().size());
			assertEquals("text\n", scalar(parse("--- |" + lineBreak + "text" + lineBreak)).value());
		}
	}

	@Test
	void documentHeaderDoesNotPermitCompactBlockCollections() {
		for (String yaml : List.of("--- key: value\n", "--- - value\n", "--- ? key\n: value\n"))
			failure(SYNTAX, yaml);
	}

	@Test
	void emptyDocumentsCommentsAndRepeatedEndMarkersAreBounded() {
		for (String yaml : List.of("---", "--- # empty\n", "---\n...\n", "...\n... # end\n", "\uFEFF# comment\n---\n"))
			assertEquals("", scalar(parse(yaml)).value());
		assertEquals("---text", scalar(parse("---text")).value());
		assertEquals("...text", scalar(parse("...text")).value());
		failure(SYNTAX, "text\n... trailing\n");
		failure(UNSUPPORTED_SYNTAX, "one\n--- two\n");
		failure(UNSUPPORTED_SYNTAX, "one\n...\ntwo\n");
	}

	@Test
	void markersInsideQuotedAndFlowNodesStillReject() {
		for (String marker : List.of("---", "...")) {
			failure(SYNTAX, "--- [one,\n" + marker + "\ntwo]\n");
			failure(SYNTAX, "--- 'one\n" + marker + "\ntwo'\n");
			for (String property : List.of("&a", "!!str", "!<tag:yaml.org,2002:str>")) {
				failure(SYNTAX, "[" + property + "\n" + marker + "\n]\n");
				failure(SYNTAX, "{key: " + property + "\n" + marker + "\n}\n");
			}
		}
	}

	@Test
	void versionDirectivesDoNotEnableYaml11ImplicitTypes() {
		for (String version : List.of("1.1", "1.2", "1.3", "01.002"))
			assertEquals(new McpJsonString("yes"), resolve("%YAML " + version + "\n--- yes\n"));
		failure(UNSUPPORTED_SYNTAX, "%YAML 2.0\n--- text\n");
		for (String version : List.of("1", "1.", ".2", "1.2.3", "1.-2", "1.２", "1.2 extra", "1.2#comment"))
			failure(SYNTAX, "%YAML " + version + "\n--- text\n");
	}

	@Test
	void directivesRequireExplicitHeaderAndRejectDuplicateDeclarations() {
		for (String yaml : List.of("%YAML 1.2\ntext\n", "%YAML 1.2\n", "%UNKNOWN ignored\ntext\n",
				"%YAML 1.2\n%YAML 1.2\n--- text\n", "%TAG !e! !local\n%TAG !e! !local\n--- text\n"))
			failure(SYNTAX, yaml);
		assertEquals("text", scalar(parse("%UNKNOWN ignored values # comment\n# separated\n%YAML 1.2\n--- text\n")).value());
	}

	@Test
	void namedPrimaryAndSecondaryHandlesExpandToStandardTags() {
		for (String handle : List.of("!e!", "!", "!!")) {
			String yaml = "%TAG " + handle + " tag:yaml.org,2002:\n--- " + handle + "str true\n";
			assertEquals("!<tag:yaml.org,2002:str>", parse(yaml).properties().tag());
			assertEquals(new McpJsonString("true"), resolve(yaml));
		}
		assertEquals(new McpJsonString("true"), resolve("%TAG ! tag:example.test,2026:\n--- ! true\n"));
	}

	@Test
	void overriddenStandardHandleNeverConstructsCustomObjects() {
		String yaml = "%TAG !! tag:example.test,2026:\n--- !!str text\n";
		assertEquals("!<tag:example.test,2026:str>", parse(yaml).properties().tag());
		assertEquals(UNSUPPORTED_TAG, assertThrows(SkillYamlException.class, () -> resolve(yaml)).reason());
		assertEquals("!<!local-kind>", parse("%TAG !e! !local-\n--- !e!kind text\n").properties().tag());
	}

	@Test
	void invalidAndUndeclaredHandlesTagsAndEscapesReject() {
		for (String yaml : List.of("!e!kind text", "%TAG !bad_name! !local-\n--- text", "%TAG e !local\n--- text",
				"%TAG !e! !local- extra\n--- text", "!e! text", "!! text", "!a!b!c text",
				"!<relative> text", "!<!> text", "!<$:?> text", "!<tag:example.test,%zz> text",
				"!bad% text", "!bad%0 text", "!bad%xy text", "!é text"))
			failure(SYNTAX, yaml);
	}

	@Test
	void percentEscapesPreserveIdentityWithoutPromotingEncodedCoreTags() {
		assertEquals("!<tag:example.test,2026:kind%21>",
				parse("%TAG !e! tag:example.test,2026:\n--- !e!kind%21 text\n").properties().tag());
		assertEquals(UNSUPPORTED_TAG, assertThrows(SkillYamlException.class, () -> resolve("!!%73tr true")).reason());
		assertEquals(UNSUPPORTED_TAG, assertThrows(SkillYamlException.class,
				() -> resolve("!<tag:yaml.org,2002:%73tr> true")).reason());
	}

	@Test
	void verbatimAndDirectivePrefixTagEscapesRemainLiteralAndCaseSensitive() {
		// §5.6 preserves tag spelling even where Example 6.26 disagrees.
		for (String escape : List.of("%21", "%2f", "%2F", "%25")) {
			String identity = "tag:example.test,2026:" + escape + "kind";
			assertEquals("!<" + identity + ">", parse("!<" + identity + "> text").properties().tag());
			assertEquals("!<" + identity + ">", parse("%TAG !e! tag:example.test,2026:"
					+ escape + "\n--- !e!kind text\n").properties().tag());
		}
		assertNotEquals(parse("!<tag:example.test,%2f> text").properties().tag(),
				parse("!<tag:example.test,%2F> text").properties().tag());
		assertEquals(UNSUPPORTED_TAG, assertThrows(SkillYamlException.class, () ->
				resolve("%TAG !e! tag:yaml.org,2002:%73\n--- !e!tr true\n")).reason());
	}

	@Test
	void propertiesRequireSeparationBeforeNonemptyFlowCollections() {
		for (String yaml : List.of("[!!seq[]]", "[&a[]]", "[!<tag:yaml.org,2002:seq>[]]", "[&a{}]"))
			failure(SYNTAX, yaml);
		for (String yaml : List.of("[!!str, text]", "[&a, *a]", "[!!seq []]", "[&a {}]"))
			assertDoesNotThrow(() -> parse(yaml));
	}

	@Test
	void tagExpansionChecksPerScalarAndAggregateLimitsBeforeAllocation() {
		String prefix = "!" + "x".repeat(30);
		String yaml = "%TAG !e! " + prefix + "\n--- !e!" + "y".repeat(12) + " text\n";
		assertEquals(SCALAR_LIMIT, assertThrows(SkillYamlException.class, () -> parse(yaml,
				new SkillYamlLimits(4096, 32, 100, 40, 4096, 100_000), 1)).reason());
		String repeated = "%TAG !e! " + prefix + "\n--- [" + "!e!x text, ".repeat(16) + "]\n";
		assertEquals(SCALAR_LIMIT, assertThrows(SkillYamlException.class, () -> parse(repeated,
				new SkillYamlLimits(4096, 32, 100, 100, 400, 100_000), 1)).reason());
		assertDoesNotThrow(() -> parse(repeated));
	}

	@Test
	void directiveScanningIsWorkBoundedAndDiagnosticsAreRedacted() {
		String yaml = "%PRIVATE_CANARY " + "parameter ".repeat(100) + "\n--- text\n";
		assertEquals(WORK_LIMIT, assertThrows(SkillYamlException.class, () -> parse(yaml,
				new SkillYamlLimits(65_536, 32, 100, 4096, 65_536, yaml.length() + 100), 1)).reason());
		SkillYamlException exception = assertThrows(SkillYamlException.class,
				() -> parse("%TAG !bad_name! !PRIVATE_CANARY\n--- text\n", LIMITS, 7));
		assertEquals(7, exception.line());
		assertFalse(exception.getMessage().contains("PRIVATE_CANARY"));
		assertNull(exception.getCause());
	}

	@Test
	void aliasColonNamesDoNotBecomeBlockMappingDelimiters() {
		Mapping root = assertInstanceOf(Mapping.class, parse("&a: key: &a value\nfoo:\n  *a:\n"));
		assertEquals("a:", assertInstanceOf(Alias.class, root.entries().get(1).value()).name());
		assertEquals(new McpJsonString("key"),
				((com.soklet.internal.mcp.protocol.McpJsonObject) resolve("&a: key: &a value\nfoo:\n  *a:\n")).members().get("foo"));
		assertDoesNotThrow(() -> parse("&a key: one\n*a : two\n"));
	}

	private static SkillYamlNode parse(String yaml) { return parse(yaml, LIMITS, 1); }
	private static SkillYamlNode parse(String yaml, SkillYamlLimits limits, int firstLine) {
		return SkillYamlParser.parse(yaml, new SkillYamlBudget(limits), firstLine);
	}
	private static Scalar scalar(SkillYamlNode node) { return assertInstanceOf(Scalar.class, node); }
	private static McpJsonValue resolve(String yaml) {
		SkillYamlBudget budget = new SkillYamlBudget(LIMITS);
		return new SkillYamlResolver(budget, McpJsonLimits.productionDefaults()).resolve(SkillYamlParser.parse(yaml, budget, 1));
	}
	private static void failure(SkillYamlException.Reason reason, String yaml) {
		assertEquals(reason, assertThrows(SkillYamlException.class, () -> parse(yaml), yaml).reason(), yaml);
	}
}
