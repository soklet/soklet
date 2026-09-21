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
package com.soklet.internal.mcp.skills;

import com.soklet.internal.mcp.protocol.McpJsonArray;
import com.soklet.internal.mcp.protocol.McpJsonBoolean;
import com.soklet.internal.mcp.protocol.McpJsonNull;
import com.soklet.internal.mcp.protocol.McpJsonNumber;
import com.soklet.internal.mcp.protocol.McpJsonObject;
import com.soklet.internal.mcp.protocol.McpJsonString;
import com.soklet.internal.mcp.protocol.McpJsonValue;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SkillDocumentMetadataTests {
	@Test
	void keepsTheOriginalImmutableDocumentAndExactRequiredStrings() {
		String name = "café-skill";
		String description = "  First line.\nSecond line.\t ";
		McpJsonObject source = document(name, description);
		SkillDocumentMetadata metadata = SkillDocumentMetadata.from(source);
		assertSame(source, metadata.metadata());
		assertSame(name, metadata.name());
		assertSame(description, metadata.description());
		assertThrows(UnsupportedOperationException.class,
				() -> metadata.metadata().members().put("changed", McpJsonNull.INSTANCE));
	}

	@Test
	void requiresBothExactCaseFieldsAndDoesNotDefaultOrCoerceThem() {
		assertInvalid(McpJsonObject.empty());
		assertInvalid(new McpJsonObject(Map.of("name", new McpJsonString("skill"))));
		assertInvalid(new McpJsonObject(Map.of("description", new McpJsonString("description"))));
		assertInvalid(new McpJsonObject(Map.of("Name", new McpJsonString("skill"),
				"description", new McpJsonString("description"))));
		assertInvalid(new McpJsonObject(Map.of("name", new McpJsonString("skill"),
				"Description", new McpJsonString("description"))));
		for (String field : List.of("name", "description"))
			for (McpJsonValue value : nonStrings()) assertInvalid(with(field, value));
	}

	@Test
	void nameLengthIsInclusiveAndCountsCodePointsNotUtf16Units() {
		for (String character : List.of("a", "\uD801\uDC28")) {
			String exact = character.repeat(64);
			assertEquals(exact, SkillDocumentMetadata.from(document(exact, "description")).name());
			assertInvalid(document(exact + character, "description"));
		}
		assertEquals("1", SkillDocumentMetadata.from(document("1", "description")).name());
		assertInvalid(document("", "description"));
	}

	@Test
	void acceptsUnicodeLowercaseAndUncasedLettersAndAllNumberCategories() {
		// Official skills-ref tests explicitly accept Chinese and lowercase Russian:
		// https://github.com/agentskills/agentskills/blob/main/skills-ref/tests/test_validator.py
		// Nd/Nl/No match the Unicode alphanumeric wording; isLetterOrDigit alone
		// would incorrectly exclude letter-numbers and other-number characters.
		for (String name : List.of("pdf-processing", "café", "技能", "мой-навык", "１２", "١٢",
				"ⅳ", "²", "⅓", "ｓｋｉｌｌ", "\uD801\uDC28"))
			assertEquals(name, SkillDocumentMetadata.from(document(name, "description")).name());
	}

	@Test
	void rejectsUppercaseAndTitlecaseIncludingSupplementaryCharacters() {
		for (String name : List.of("Skill", "CAFÉ", "НАВЫК", "Ⅳ", "ǅ", "ＳＫＩＬＬ",
				"\uD801\uDC00", "\uD835\uDC00"))
			assertInvalid(document(name, "description"));
	}

	@Test
	void rejectsLeadingTrailingAndRepeatedHyphens() {
		for (String name : List.of("-", "-skill", "skill-", "two--words", "a---b"))
			assertInvalid(document(name, "description"));
		assertEquals("a-1-b", SkillDocumentMetadata.from(document("a-1-b", "description")).name());
	}

	@Test
	void namesAreNotTrimmedNormalizedOrPermittedOtherPunctuation() {
		for (String name : List.of(" skill", "skill ", "skill\n", "two words", "two_words", "two.words",
				"two/words", "two\\words", "two%words", "a–b", "a—b", "a\u0000b", "a\u200Bb",
				"cafe\u0301", "ⓐ", "🙂"))
			assertInvalid(document(name, "description"));
		// NFKC would change these valid scalar values; the input must stay intact.
		assertEquals("ｓｋｉｌｌ", SkillDocumentMetadata.from(document("ｓｋｉｌｌ", "description")).name());
	}

	@Test
	void descriptionLengthIsInclusiveAndCountsSupplementaryCodePoints() {
		for (String character : List.of("a", "🙂")) {
			String exact = character.repeat(1_024);
			assertEquals(exact, SkillDocumentMetadata.from(document("skill", exact)).description());
			assertInvalid(document("skill", exact + character));
		}
		assertInvalid(document("skill", ""));
	}

	@Test
	void descriptionWhitespaceIsPreservedUnderTheLiteralNonemptyRule() {
		// The spec's 1–1024-character rule does not add the demonstration
		// validator's nonblank restriction. Neither trimming nor rewriting is safe.
		for (String description : List.of(" ", "\t\n", "  description  ", "\u00A0"))
			assertEquals(description, SkillDocumentMetadata.from(document("skill", description)).description());
	}

	@Test
	void compatibilityIsOptionalButMustBeAOneToFiveHundredCodePointString() {
		for (String character : List.of("a", "🙂")) {
			String exact = character.repeat(500);
			McpJsonObject source = with("compatibility", new McpJsonString(exact));
			assertSame(source, SkillDocumentMetadata.from(source).metadata());
			assertInvalid(with("compatibility", new McpJsonString(exact + character)));
		}
		assertInvalid(with("compatibility", new McpJsonString("")));
		for (McpJsonValue value : nonStrings()) assertInvalid(with("compatibility", value));
		McpJsonObject whitespace = with("compatibility", new McpJsonString(" \t\n"));
		assertSame(whitespace, SkillDocumentMetadata.from(whitespace).metadata());
	}

	@Test
	void optionalLicenseAndAllowedToolsRequireStringsWithoutInventingFurtherGrammar() {
		for (String field : List.of("license", "allowed-tools")) {
			for (McpJsonValue value : nonStrings()) assertInvalid(with(field, value));
			for (String value : List.of("", " ", "Bash(git:*) Bash(jq:*) Read", "custom\ntext", "x".repeat(2_000))) {
				McpJsonObject source = with(field, new McpJsonString(value));
				assertSame(source, SkillDocumentMetadata.from(source).metadata());
			}
		}
	}

	@Test
	void nestedMetadataMustBeAStringToStringMappingWithoutCoercion() {
		for (McpJsonValue value : List.of(McpJsonNull.INSTANCE, McpJsonBoolean.FALSE,
				new McpJsonNumber(1), new McpJsonString("text"), new McpJsonArray(List.of())))
			assertInvalid(with("metadata", value));
		for (McpJsonValue value : nonStrings())
			assertInvalid(with("metadata", new McpJsonObject(Map.of("version", value))));
		assertSame(McpJsonObject.empty(), SkillDocumentMetadata.from(with("metadata", McpJsonObject.empty()))
				.metadata().members().get("metadata"));
		McpJsonObject additional = new McpJsonObject(Map.of("", new McpJsonString(""),
				"version", new McpJsonString("1.0"), "io.modelcontextprotocol/future", new McpJsonString("preserve")));
		assertSame(additional, SkillDocumentMetadata.from(with("metadata", additional))
				.metadata().members().get("metadata"));
	}

	@Test
	void preservesUnknownTopLevelFieldsTheirValuesIdentitiesAndOrdering() {
		Map<String, McpJsonValue> members = new LinkedHashMap<>();
		members.put("custom-first", new McpJsonArray(List.of(McpJsonNull.INSTANCE,
				new McpJsonObject(Map.of("nested", McpJsonBoolean.TRUE)))));
		members.put("name", new McpJsonString("skill"));
		members.put("custom-number", new McpJsonNumber(new BigDecimal("0.1000000000000000000001")));
		members.put("description", new McpJsonString("description"));
		members.put("<<", new McpJsonObject(Map.of("future", McpJsonBoolean.FALSE)));
		members.put("custom-null", McpJsonNull.INSTANCE);
		McpJsonObject source = new McpJsonObject(members);
		SkillDocumentMetadata metadata = SkillDocumentMetadata.from(source);
		assertSame(source, metadata.metadata());
		assertEquals(new ArrayList<>(members.keySet()), new ArrayList<>(metadata.metadata().members().keySet()));
		for (Map.Entry<String, McpJsonValue> entry : members.entrySet())
			assertSame(entry.getValue(), metadata.metadata().members().get(entry.getKey()));
	}

	@Test
	void rejectsIllFormedUnicodeInBoundedFieldsWithoutTreatingSurrogatesAsCharacters() {
		for (String field : List.of("name", "description", "compatibility"))
			for (String value : List.of("\uD800", "\uDC00", "a\uD800b", "a\uDC00b", "\uDC00\uD800"))
				assertInvalid(with(field, new McpJsonString(value)));
	}

	@Test
	void diagnosticsAndRenderingNeverExposeAuthoredMetadata() {
		IllegalArgumentException first = assertInvalid(document("private-name-canary%", "private-description-canary"));
		IllegalArgumentException second = assertInvalid(with("license", new McpJsonNumber(9_876_543)));
		assertEquals(first.getMessage(), second.getMessage());
		assertEquals("The Skills document metadata is invalid.", first.getMessage());
		assertEquals("SkillDocumentMetadata[redacted]",
				SkillDocumentMetadata.from(document("private-name-canary", "private-description-canary")).toString());
	}

	@Test
	void nullDocumentHasAFixedNullFailure() {
		NullPointerException failure = assertThrows(NullPointerException.class, () -> SkillDocumentMetadata.from(null));
		assertEquals(NullPointerException.class, failure.getClass());
		assertEquals("Skills document metadata is required.", failure.getMessage());
		assertNull(failure.getCause());
	}

	private static McpJsonObject document(String name, String description) {
		return new McpJsonObject(Map.of("name", new McpJsonString(name), "description", new McpJsonString(description)));
	}

	private static McpJsonObject with(String key, McpJsonValue value) {
		Map<String, McpJsonValue> members = new LinkedHashMap<>(document("skill", "description").members());
		members.put(key, value);
		return new McpJsonObject(members);
	}

	private static List<McpJsonValue> nonStrings() {
		return List.of(McpJsonNull.INSTANCE, McpJsonBoolean.TRUE, new McpJsonNumber(1),
				new McpJsonArray(List.of()), McpJsonObject.empty());
	}

	private static IllegalArgumentException assertInvalid(McpJsonObject source) {
		IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
				() -> SkillDocumentMetadata.from(source));
		assertEquals(IllegalArgumentException.class, failure.getClass());
		assertEquals("The Skills document metadata is invalid.", failure.getMessage());
		assertNull(failure.getCause());
		return failure;
	}
}
