/*
 * Copyright 2022-2026 Revetware LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.soklet.internal.mcp.skills;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.soklet.internal.mcp.protocol.McpJsonCodec;
import com.soklet.internal.mcp.protocol.McpJsonLimits;
import com.soklet.internal.mcp.protocol.McpJsonString;
import com.soklet.internal.mcp.protocol.McpJsonValue;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.annotation.concurrent.ThreadSafe;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Coverage-guided qualification checks for the private Skills YAML pipeline.
 * All limits below are small test-only exploration settings, not public Skills
 * defaults, aggregate memory admission, or a completed fuzz-qualification claim.
 */
@ThreadSafe
public class SkillYamlFuzzTest {
	private static final SkillYamlLimits YAML_LIMITS = new SkillYamlLimits(
			16_384, 32, 2_048, 4_096, 65_536, 1_000_000);
	private static final McpJsonLimits JSON_LIMITS = new McpJsonLimits(
			65_536, 32, 16_384, 4_096, 512, 10_000, 2_048, 65_536);
	private static final McpJsonCodec JSON_CODEC = new McpJsonCodec(JSON_LIMITS);
	private static final byte[] OPENING = "---\n".getBytes(StandardCharsets.UTF_8);
	private static final byte[] CLOSING = "\n---\n# Fuzz body\n".getBytes(StandardCharsets.UTF_8);
	private static final String STREAM_METHOD = "streamParsingAndResolutionRemainTypedAndBounded";
	private static final String FRONTMATTER_METHOD = "frontmatterRemainsTypedBoundedAndByteExact";

	@FuzzTest(maxDuration = "2m")
	public void streamParsingAndResolutionRemainTypedAndBounded(byte[] input) {
		ParsedStream parsed;
		try {
			parsed = parseStream(input);
		} catch (SkillYamlException expected) {
			assertTypedFailure(expected);
			return;
		}
		Assertions.assertTrue(parsed.documents().size() <= YAML_LIMITS.maximumNodes());
		SkillYamlResolver resolver = new SkillYamlResolver(parsed.budget(), JSON_LIMITS);
		for (SkillYamlNode document : parsed.documents()) {
			McpJsonValue value;
			try {
				// Each resolve has document-local anchors while all documents share
				// parse/expansion work, node and text accounting.
				value = resolver.resolve(document);
			} catch (SkillYamlException expected) {
				assertTypedFailure(expected);
				continue;
			}
			// A successful resolver promises codec-compatible bounded JSON. A
			// serializer/reparse failure is a finding, not an expected rejection.
			assertJsonRoundTrip(value);
		}
	}

	@FuzzTest(maxDuration = "2m")
	public void frontmatterRemainsTypedBoundedAndByteExact(byte[] input) {
		checkFrontmatter(input);
		// Also treat the bytes as a header candidate. This path keeps grammar
		// mutations reachable without requiring every mutation to preserve both
		// delimiters. Oversize candidates are never copied or truncated here.
		if (input.length > YAML_LIMITS.maximumInputBytes() - OPENING.length - CLOSING.length)
			return;
		byte[] framed = new byte[OPENING.length + input.length + CLOSING.length];
		System.arraycopy(OPENING, 0, framed, 0, OPENING.length);
		System.arraycopy(input, 0, framed, OPENING.length, input.length);
		System.arraycopy(CLOSING, 0, framed, OPENING.length + input.length, CLOSING.length);
		checkFrontmatter(framed);
	}

	@Test
	public void curatedSeedsReachResolutionAndDocumentIsolation() throws IOException {
		ParsedStream valid = parseStream(readSeed(STREAM_METHOD, "directives-stream.yaml"));
		Assertions.assertEquals(3, valid.documents().size());
		SkillYamlResolver resolver = new SkillYamlResolver(valid.budget(), JSON_LIMITS);
		Assertions.assertEquals(new McpJsonString("true"), resolver.resolve(valid.documents().get(0)));
		for (int index = 1; index < valid.documents().size(); ++index)
			assertJsonRoundTrip(resolver.resolve(valid.documents().get(index)));

		ParsedStream isolated = parseStream(readSeed(STREAM_METHOD, "document-local-alias.yaml"));
		Assertions.assertEquals(2, isolated.documents().size());
		SkillYamlResolver isolatedResolver = new SkillYamlResolver(isolated.budget(), JSON_LIMITS);
		assertJsonRoundTrip(isolatedResolver.resolve(isolated.documents().get(0)));
		Assertions.assertEquals(SkillYamlException.Reason.UNDEFINED_ALIAS,
				Assertions.assertThrows(SkillYamlException.class,
						() -> isolatedResolver.resolve(isolated.documents().get(1))).reason());

		ParsedStream complex = parseStream(readSeed(STREAM_METHOD, "complex-key.yaml"));
		Assertions.assertEquals(SkillYamlException.Reason.TYPE,
				Assertions.assertThrows(SkillYamlException.class,
						() -> new SkillYamlResolver(complex.budget(), JSON_LIMITS)
								.resolve(complex.documents().get(0))).reason());

		for (String seed : List.of("metadata.md", "bom.md", "literal-description.md")) {
			byte[] bytes = readSeed(FRONTMATTER_METHOD, seed);
			SkillFrontmatter parsed = SkillFrontmatter.parse(bytes, YAML_LIMITS, JSON_LIMITS);
			Assertions.assertTrue(parsed.metadata().members().containsKey("name"));
			Assertions.assertTrue(parsed.bodyByteOffset() < bytes.length);
			assertJsonRoundTrip(parsed.metadata());
		}
	}

	private static ParsedStream parseStream(byte[] input) {
		if (input.length > YAML_LIMITS.maximumInputBytes())
			throw new SkillYamlException(SkillYamlException.Reason.INPUT_LIMIT, 1, 1);
		SkillYamlBudget budget = new SkillYamlBudget(YAML_LIMITS);
		budget.work(3L * input.length, new SkillYamlNode.Position(1, 1));
		// Strict decoding preserves invalid UTF-8 as a typed failure; replacement
		// decoding would silently remove an important boundary from exploration.
		SkillSource source = SkillSource.fromBytes(input, YAML_LIMITS.maximumInputBytes());
		return new ParsedStream(SkillYamlParser.parseStream(source.text(), budget, 1), budget);
	}

	private static void checkFrontmatter(byte[] input) {
		SkillFrontmatter parsed;
		try {
			parsed = SkillFrontmatter.parse(input, YAML_LIMITS, JSON_LIMITS);
		} catch (SkillYamlException expected) {
			assertTypedFailure(expected);
			return;
		}
		Assertions.assertArrayEquals(input, parsed.source().originalBytes());
		Assertions.assertTrue(parsed.bodyByteOffset() >= 0);
		Assertions.assertTrue(parsed.bodyByteOffset() <= input.length);
		Assertions.assertEquals("SkillFrontmatter[redacted]", parsed.toString());
		byte[] inspection = parsed.source().originalBytes();
		if (inspection.length > 0) {
			inspection[0] ^= 1;
			Assertions.assertArrayEquals(input, parsed.source().originalBytes());
		}
		assertJsonRoundTrip(parsed.metadata());
	}

	private static void assertJsonRoundTrip(McpJsonValue value) {
		byte[] serialized = JSON_CODEC.toUtf8Bytes(value);
		Assertions.assertTrue(serialized.length <= JSON_LIMITS.maximumOutputBytes());
		Assertions.assertEquals(value, JSON_CODEC.parse(serialized));
	}

	private static void assertTypedFailure(SkillYamlException expected) {
		Assertions.assertNotNull(expected.reason());
		Assertions.assertTrue(expected.line() >= 1);
		Assertions.assertTrue(expected.column() >= 1);
		Assertions.assertNull(expected.getCause());
		Assertions.assertEquals("Invalid skill frontmatter (" + expected.reason().name()
				+ " at " + expected.line() + ":" + expected.column() + ").", expected.getMessage());
	}

	private static byte[] readSeed(String method, String name) throws IOException {
		String resource = "SkillYamlFuzzTestInputs/" + method + "/" + name;
		try (InputStream input = SkillYamlFuzzTest.class.getResourceAsStream(resource)) {
			Assertions.assertNotNull(input, resource);
			return input.readAllBytes();
		}
	}

	private record ParsedStream(List<SkillYamlNode> documents, SkillYamlBudget budget) {}
}
