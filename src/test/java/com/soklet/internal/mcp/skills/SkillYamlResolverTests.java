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

import com.soklet.internal.mcp.protocol.McpJsonArray;
import com.soklet.internal.mcp.protocol.McpJsonBoolean;
import com.soklet.internal.mcp.protocol.McpJsonCodec;
import com.soklet.internal.mcp.protocol.McpJsonLimits;
import com.soklet.internal.mcp.protocol.McpJsonNull;
import com.soklet.internal.mcp.protocol.McpJsonNumber;
import com.soklet.internal.mcp.protocol.McpJsonObject;
import com.soklet.internal.mcp.protocol.McpJsonString;
import com.soklet.internal.mcp.protocol.McpJsonValue;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static com.soklet.internal.mcp.skills.SkillYamlException.Reason;
import static com.soklet.internal.mcp.skills.SkillYamlNode.*;
import static org.junit.jupiter.api.Assertions.*;

class SkillYamlResolverTests {
	private static final Position POSITION = new Position(3, 7);
	private static final SkillYamlLimits LIMITS = new SkillYamlLimits(
			1_000_000, 128, 100_000, 100_000, 1_000_000, 10_000_000);
	private static final McpJsonLimits JSON_LIMITS = McpJsonLimits.productionDefaults();

	@Test
	void resolvesOnlyTheCoreBooleanAndNullSpellings() {
		for (String value : List.of("", "null", "Null", "NULL", "~"))
			assertSame(McpJsonNull.INSTANCE, resolve(plain(value)), value);
		for (String value : List.of("true", "True", "TRUE"))
			assertSame(McpJsonBoolean.TRUE, resolve(plain(value)), value);
		for (String value : List.of("false", "False", "FALSE"))
			assertSame(McpJsonBoolean.FALSE, resolve(plain(value)), value);
		for (String value : List.of("yes", "no", "on", "off", "n", "y", "tRuE", "NuLl"))
			assertEquals(new McpJsonString(value), resolve(plain(value)), value);
	}

	@Test
	void datesYaml11NumbersAndSignedRadixValuesRemainStrings() {
		for (String value : List.of("2026-09-20", "2026-09-20T10:00:00Z", "1:30",
				"1_000", "0b101", "+0xA", "-0xA", "+0o7", "-0o7", "0O7", "0XFF",
				".iNF", "+.nan", "-.nan", ".", "+", "1e", "1e+", "１２"))
			assertEquals(new McpJsonString(value), resolve(plain(value)), value);
	}

	@Test
	void decimalOctalHexAndExponentValuesAreExact() {
		String[][] examples = {
				{"+0012", "12"}, {"-0012", "-12"}, {"012", "12"}, {"0o12", "10"},
				{"0xDeAdBeEf", "3735928559"}, {"9007199254740993", "9007199254740993"},
				{".5", "0.5"}, {"-.5", "-0.5"}, {"+12.", "12"},
				{"+12e03", "12000"}, {"-2E-05", "-0.00002"},
				{"0.1000000000000000000000000001", "0.1000000000000000000000000001"}
		};
		for (String[] example : examples) {
			BigDecimal actual = assertInstanceOf(McpJsonNumber.class, resolve(plain(example[0]))).value();
			assertEquals(0, actual.compareTo(new BigDecimal(example[1])), example[0]);
		}
	}

	@Test
	void nonfiniteNumbersAreRejectedRatherThanCoercedOrSilentlyQuoted() {
		for (String value : List.of(".inf", "-.Inf", "+.INF", ".nan", ".NaN", ".NAN"))
			assertReason(Reason.TYPE, plain(value));
		assertEquals(new McpJsonString(".inf"), resolve(quoted(".inf")));
	}

	@Test
	void everyNonplainStyleAndNonspecificTagDisablesImplicitScalarResolution() {
		for (Style style : List.of(Style.SINGLE_QUOTED, Style.DOUBLE_QUOTED, Style.LITERAL, Style.FOLDED))
			assertEquals(new McpJsonString("true"), resolve(new Scalar("true", style, Properties.EMPTY, POSITION)));
		assertEquals(new McpJsonString("42"), resolve(tagged("42", "!")));
		assertEquals(new McpJsonString(""), resolve(quoted("")));
	}

	@Test
	void supportedExplicitTagsCheckLexicalValueAndNodeKind() {
		assertEquals(new McpJsonString("true"), resolve(tagged("true", "!!str")));
		assertEquals(new McpJsonString("true"), resolve(tagged("true", "!<tag:yaml.org,2002:str>")));
		assertSame(McpJsonBoolean.TRUE, resolve(tagged("TRUE", "!!bool")));
		assertSame(McpJsonNull.INSTANCE, resolve(tagged("~", "!!null")));
		assertEquals(new McpJsonNumber(10), resolve(tagged("0xA", "!!int")));
		assertEquals(new McpJsonNumber(-1), resolve(tagged("-1", "!!float")));
		assertEquals(new McpJsonNumber(42), resolve(new Scalar("42", Style.DOUBLE_QUOTED,
				new Properties("!!int", null), POSITION)));
		for (String[] invalid : List.of(new String[]{"yes", "!!bool"}, new String[]{"1.5", "!!int"},
				new String[]{"0xA", "!!float"}, new String[]{"~", "!!int"}, new String[]{"x", "!!null"},
				new String[]{"x", "!!map"}, new String[]{"x", "!!seq"}))
			assertReason(Reason.TYPE, tagged(invalid[0], invalid[1]));
		assertReason(Reason.TYPE, new Sequence(List.of(), new Properties("!!str", null), POSITION));
		assertReason(Reason.TYPE, new Mapping(List.of(), new Properties("!!seq", null), POSITION));
		assertEquals(new McpJsonArray(List.of()), resolve(new Sequence(List.of(), new Properties("!", null), POSITION)));
		assertEquals(McpJsonObject.empty(), resolve(new Mapping(List.of(), new Properties("!!map", null), POSITION)));
	}

	@Test
	void customObjectTimestampBinaryAndMergeTagsAreUnsupported() {
		for (String tag : List.of("!foo", "!java/object:java.lang.String", "!!timestamp", "!!binary",
				"!!set", "!!omap", "!!merge", "!<tag:example.com,2026:custom>"))
			assertReason(Reason.UNSUPPORTED_TAG, tagged("secret", tag));
	}

	@Test
	void unknownFieldsAndLiteralMergeKeysSurviveWithoutMerging() {
		McpJsonObject result = assertInstanceOf(McpJsonObject.class, resolve(mapping(
				entry("name", plain("sample")),
				entry("vendor-extension", sequence(plain("1"), plain("false"))),
				entry("<<", mapping(entry("nested", plain("value")))))));
		assertEquals(List.of("name", "vendor-extension", "<<"), new ArrayList<>(result.members().keySet()));
		assertFalse(result.members().containsKey("nested"));
		assertEquals(new McpJsonString("value"), ((McpJsonObject) result.members().get("<<")).members().get("nested"));
	}

	@Test
	void nonStringKeysAreRejectedWithoutCoercingTheirTypes() {
		for (SkillYamlNode key : List.of(plain("1"), plain("true"), plain("null"),
				sequence(plain("x")), mapping(entry("x", plain("y")))))
			assertReason(Reason.TYPE, mapping(new Entry(key, plain("value"))));
		assertEquals(new McpJsonString("value"), ((McpJsonObject) resolve(mapping(
				new Entry(tagged("true", "!!str"), plain("value"))))).members().get("true"));
	}

	@Test
	void duplicateStringKeysAreRejectedBeforeTheirLaterValuesAreConverted() {
		SkillYamlNode duplicate = new Scalar("name", Style.DOUBLE_QUOTED, Properties.EMPTY, new Position(8, 2));
		SkillYamlException exception = assertThrows(SkillYamlException.class, () -> resolve(mapping(
				entry("name", plain("first")), new Entry(duplicate, tagged("later", "!unsupported")))));
		assertEquals(Reason.DUPLICATE_KEY, exception.reason());
		assertEquals(8, exception.line());
		assertEquals(2, exception.column());
	}

	@Test
	void aliasesBindInSerializationOrderAndRejectForwardReferences() {
		Scalar anchored = new Scalar("first", Style.PLAIN, new Properties(null, "x"), POSITION);
		McpJsonArray result = assertInstanceOf(McpJsonArray.class, resolve(sequence(anchored, alias("x"))));
		assertSame(result.values().get(0), result.values().get(1));
		assertReason(Reason.UNDEFINED_ALIAS, sequence(alias("x"), anchored));
	}

	@Test
	void anchorRedefinitionDoesNotRebindAliasesInsidePreviouslyResolvedValues() {
		Scalar first = new Scalar("first", Style.PLAIN, new Properties(null, "x"), POSITION);
		Sequence captured = new Sequence(List.of(alias("x")), new Properties(null, "captured"), POSITION);
		Scalar second = new Scalar("second", Style.PLAIN, new Properties(null, "x"), POSITION);
		McpJsonArray result = assertInstanceOf(McpJsonArray.class,
				resolve(sequence(first, captured, second, alias("captured"), alias("x"))));
		assertEquals(new McpJsonArray(List.of(new McpJsonString("first"))), result.values().get(3));
		assertEquals(new McpJsonString("second"), result.values().get(4));
	}

	@Test
	void nestedAnchorRedefinitionWinsOverTheOuterCompletedAnchor() {
		Sequence outer = new Sequence(List.of(new Scalar("inner", Style.PLAIN,
				new Properties(null, "x"), POSITION)), new Properties(null, "x"), POSITION);
		McpJsonArray result = assertInstanceOf(McpJsonArray.class, resolve(sequence(outer, alias("x"))));
		assertEquals(new McpJsonString("inner"), result.values().get(1));
	}

	@Test
	void directAndIndirectAliasCyclesAreRejected() {
		assertReason(Reason.CYCLIC_ALIAS,
				new Sequence(List.of(alias("x")), new Properties(null, "x"), POSITION));
		assertReason(Reason.CYCLIC_ALIAS, new Sequence(List.of(new Sequence(List.of(alias("outer")),
				new Properties(null, "inner"), POSITION)), new Properties(null, "outer"), POSITION));
	}

	@Test
	void aliasesCanBeStringKeysButCannotHideDuplicateOrNonStringKeys() {
		Scalar key = new Scalar("name", Style.PLAIN, new Properties(null, "key"), POSITION);
		assertReason(Reason.DUPLICATE_KEY, mapping(new Entry(key, plain("first")),
				new Entry(alias("key"), plain("second"))));
		assertReason(Reason.TYPE, sequence(new Scalar("42", Style.PLAIN, new Properties(null, "key"), POSITION),
				mapping(new Entry(alias("key"), plain("value")))));
	}

	@Test
	void repeatedAliasesChargeExpandedNodesEvenWhenResolvedObjectsAreShared() {
		Sequence shared = new Sequence(List.of(plain("a"), plain("b")), new Properties(null, "x"), POSITION);
		SkillYamlNode root = sequence(shared, alias("x"), alias("x"));
		assertReason(Reason.NODE_LIMIT, root, new SkillYamlLimits(1000, 20, 9, 100, 1000, 10000), JSON_LIMITS);
		assertInstanceOf(McpJsonArray.class, resolve(root,
				new SkillYamlLimits(1000, 20, 10, 100, 1000, 10000), JSON_LIMITS));
	}

	@Test
	void repeatedAliasesChargeExpandedTextIncludingObjectKeys() {
		Mapping shared = new Mapping(List.of(entry("abcd", plain("efgh"))), new Properties(null, "x"), POSITION);
		assertReason(Reason.SCALAR_LIMIT, sequence(shared, alias("x")),
				new SkillYamlLimits(1000, 20, 100, 100, 15, 10000), JSON_LIMITS);
	}

	@Test
	void aliasExpansionCannotBypassDepthLimits() {
		Sequence shared = new Sequence(List.of(plain("value")), new Properties(null, "x"), POSITION);
		assertReason(Reason.DEPTH_LIMIT, sequence(shared, sequence(sequence(alias("x")))),
				new SkillYamlLimits(1000, 4, 100, 100, 1000, 10000), JSON_LIMITS);
	}

	@Test
	void nestedDoublingAliasesStopUnderTheSharedWorkBudget() {
		List<SkillYamlNode> nodes = new ArrayList<>();
		nodes.add(new Scalar("x", Style.PLAIN, new Properties(null, "a0"), POSITION));
		for (int index = 1; index <= 18; ++index)
			nodes.add(new Sequence(List.of(alias("a" + (index - 1)), alias("a" + (index - 1))),
					new Properties(null, "a" + index), POSITION));
		assertReason(Reason.WORK_LIMIT, sequence(nodes.toArray(SkillYamlNode[]::new)),
				new SkillYamlLimits(1000, 128, 100_000, 1000, 1_000_000, 1000), JSON_LIMITS);
	}

	@Test
	void syntaxAndResolutionCanShareOneBudgetAndNoAnchorsLeakBetweenDocuments() {
		SkillYamlBudget budget = new SkillYamlBudget(new SkillYamlLimits(1000, 20, 2, 100, 1000, 1000));
		budget.node(1, POSITION);
		SkillYamlResolver resolver = new SkillYamlResolver(budget, JSON_LIMITS);
		resolver.resolve(new Scalar("value", Style.PLAIN, new Properties(null, "private"), POSITION));
		assertEquals(Reason.UNDEFINED_ALIAS, assertThrows(SkillYamlException.class,
				() -> resolver.resolve(alias("private"))).reason());
		assertEquals(Reason.NODE_LIMIT, assertThrows(SkillYamlException.class,
				() -> resolver.resolve(plain("another"))).reason());
	}

	@Test
	void numberLengthAndExponentLimitsApplyBeforeArithmetic() {
		McpJsonLimits limits = jsonLimits(100, 100, 4, 5, 100, 1000);
		assertReason(Reason.NUMBER_LIMIT, plain("12345"), LIMITS, limits);
		assertReason(Reason.NUMBER_LIMIT, plain("1e6"), LIMITS, limits);
		assertReason(Reason.NUMBER_LIMIT, plain("1e-6"), LIMITS, limits);
		assertReason(Reason.NUMBER_LIMIT, plain("0xFF"), LIMITS, jsonLimits(100, 100, 4, 1, 100, 1000));
		assertEquals(new McpJsonNumber(new BigDecimal("1e5")), resolve(plain("1e5"), LIMITS, limits));
		assertReason(Reason.WORK_LIMIT, plain("9".repeat(100)),
				new SkillYamlLimits(1000, 20, 100, 1000, 1000, 1000), JSON_LIMITS);
	}

	@Test
	void canonicalNumericLengthAndAdjustedExponentAreBoundedToo() {
		assertReason(Reason.NUMBER_LIMIT, plain(".5"), LIMITS, jsonLimits(100, 100, 2, 100, 100, 1000));
		assertReason(Reason.NUMBER_LIMIT, plain("999e5"), LIMITS, jsonLimits(100, 100, 100, 5, 100, 1000));
		assertReason(Reason.NUMBER_LIMIT, plain("0.00001"), LIMITS, jsonLimits(100, 100, 100, 4, 100, 1000));
		assertReason(Reason.NUMBER_LIMIT, plain("0xffffffffffff"), LIMITS, jsonLimits(100, 100, 14, 100, 100, 1000));
	}

	@Test
	void decodedAndEscapedStringLimitsAndUnicodeAreValidated() {
		assertReason(Reason.SCALAR_LIMIT, quoted("abcd"), LIMITS, jsonLimits(100, 3, 100, 100, 100, 1000));
		assertReason(Reason.SCALAR_LIMIT, quoted("\u0000"), LIMITS, jsonLimits(5, 100, 100, 100, 100, 1000));
		assertReason(Reason.TYPE, quoted("\ud800"));
		assertReason(Reason.TYPE, quoted("\udc00"));
		assertEquals(new McpJsonString("😀"), resolve(quoted("😀")));
	}

	@Test
	void exactSerializedUtf8ByteCeilingAppliesAcrossAliases() {
		Scalar shared = new Scalar("é", Style.PLAIN, new Properties(null, "x"), POSITION);
		SkillYamlNode root = sequence(shared, alias("x"));
		// ["é","é"] occupies 11 UTF-8 bytes, including quotes and punctuation.
		assertReason(Reason.OUTPUT_LIMIT, root, LIMITS, jsonLimits(100, 100, 100, 100, 100, 10));
		assertInstanceOf(McpJsonArray.class, resolve(root, LIMITS, jsonLimits(100, 100, 100, 100, 100, 11)));
	}

	@Test
	void jsonNodeLimitCountsValuesButNotMappingKeys() {
		SkillYamlNode root = mapping(entry("first", plain("a")), entry("second", plain("b")));
		assertInstanceOf(McpJsonObject.class, resolve(root, LIMITS, jsonLimits(100, 100, 100, 100, 3, 1000)));
		assertReason(Reason.NODE_LIMIT, root, LIMITS, jsonLimits(100, 100, 100, 100, 2, 1000));
	}

	@Test
	void resolvedOutputAccountingMatchesTheExistingJsonCodec() {
		List<SkillYamlNode> roots = List.of(quoted("\u0000\b\f\n\r\t\\\"é日😀"),
				plain("0.000001"), plain("0.0000001"), plain("0e100"), plain("0.000000000"),
				plain("-10.0100"), plain("-1e20"), plain("+0xF"),
				mapping(entry("escaped\nkey", sequence(plain("null"), plain("false"), plain("true")))));
		McpJsonCodec codec = new McpJsonCodec(JSON_LIMITS);
		for (SkillYamlNode root : roots) {
			McpJsonValue result = resolve(root);
			int bytes = codec.toUtf8Bytes(result).length;
			assertEquals(result, resolve(root, LIMITS, jsonLimits(1000, 1000, 1000, 1000, 1000, bytes)));
			assertReason(Reason.OUTPUT_LIMIT, root, LIMITS, jsonLimits(1000, 1000, 1000, 1000, 1000, bytes - 1));
		}
	}

	@Test
	void diagnosticNeverContainsAuthoredValuesKeysTagsOrAliasNames() {
		String secret = "private-password-value";
		for (SkillYamlNode node : List.of(alias(secret), tagged(secret, "!" + secret),
				mapping(entry(secret, plain("first")), entry(secret, plain("second"))))) {
			SkillYamlException exception = assertThrows(SkillYamlException.class, () -> resolve(node));
			assertFalse(exception.getMessage().contains(secret));
			assertNull(exception.getCause());
		}
	}

	private static McpJsonValue resolve(SkillYamlNode node) {
		return resolve(node, LIMITS, JSON_LIMITS);
	}

	private static McpJsonValue resolve(SkillYamlNode node, SkillYamlLimits limits, McpJsonLimits jsonLimits) {
		return new SkillYamlResolver(new SkillYamlBudget(limits), jsonLimits).resolve(node);
	}

	private static void assertReason(Reason reason, SkillYamlNode node) {
		assertReason(reason, node, LIMITS, JSON_LIMITS);
	}

	private static void assertReason(Reason reason, SkillYamlNode node, SkillYamlLimits limits, McpJsonLimits jsonLimits) {
		assertEquals(reason, assertThrows(SkillYamlException.class, () -> resolve(node, limits, jsonLimits)).reason());
	}

	private static McpJsonLimits jsonLimits(int token, int string, int number, int exponent, int nodes, int output) {
		return new McpJsonLimits(1000, 100, token, string, number, exponent, nodes, output);
	}

	private static Scalar plain(String value) { return new Scalar(value, Style.PLAIN, Properties.EMPTY, POSITION); }
	private static Scalar quoted(String value) { return new Scalar(value, Style.DOUBLE_QUOTED, Properties.EMPTY, POSITION); }
	private static Scalar tagged(String value, String tag) { return new Scalar(value, Style.PLAIN, new Properties(tag, null), POSITION); }
	private static Alias alias(String name) { return new Alias(name, POSITION); }
	private static Sequence sequence(SkillYamlNode... values) { return new Sequence(List.of(values), Properties.EMPTY, POSITION); }
	private static Mapping mapping(Entry... entries) { return new Mapping(List.of(entries), Properties.EMPTY, POSITION); }
	private static Entry entry(String key, SkillYamlNode value) { return new Entry(plain(key), value); }
}
