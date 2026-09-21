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
import com.soklet.internal.mcp.protocol.McpJsonObject;
import com.soklet.internal.mcp.protocol.McpJsonString;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Complete upstream documents are inert test data; their instructions are never executed. */
class SkillRealDocumentTests {

	private static final SkillYamlLimits LIMITS = new SkillYamlLimits(16_384, 8,
			32, 1_024, 4_096, 1_000_000);
	private static final McpJsonLimits JSON_LIMITS = McpJsonLimits.productionDefaults();
	private static final List<Fixture> FIXTURES = List.of(
			new Fixture("frontend-design", 9_390,
					"d91970639e9f5c37682ac7ab60094d35f1c7c1f38d731bd56396563aee10c1d3",
					"Guidance for distinctive, intentional visual design when building new UI or reshaping an existing one. "
							+ "Helps with aesthetic direction, typography, and making choices that don't read as templated defaults.",
					"\n# Frontend Design\n"),
			new Fixture("brand-guidelines", 2_235,
					"1120b3769e2985cefb3d25be981b1f914abeba57ae079b83c20c666c164fa9fe",
					"Applies Anthropic's official brand colors and typography to any sort of artifact that may benefit "
							+ "from having Anthropic's look-and-feel. Use it when brand colors or style guidelines, visual formatting, "
							+ "or company design standards apply.",
					"\n# Anthropic Brand Styling\n"));

	@Test
	void completePinnedDocumentsHaveExpectedBytesAndExactMetadata() throws Exception {
		for (Fixture fixture : FIXTURES) {
			byte[] bytes = read(fixture);
			assertEquals(fixture.length(), bytes.length, fixture.name());
			assertEquals(fixture.sha256(), digest(bytes), fixture.name());
			SkillFrontmatter parsed = assertTimeoutPreemptively(Duration.ofSeconds(2),
					() -> SkillFrontmatter.parse(bytes, LIMITS, JSON_LIMITS));
			assertEquals(fixture.metadata(), parsed.metadata(), fixture.name());
			assertEquals(List.of("name", "description", "license"),
					List.copyOf(parsed.metadata().members().keySet()), fixture.name());
			assertArrayEquals(bytes, parsed.source().originalBytes(), fixture.name());
			assertEquals(fixture.sha256(), digest(parsed.source().originalBytes()), fixture.name());
			String body = new String(bytes, parsed.bodyByteOffset(),
					bytes.length - parsed.bodyByteOffset(), StandardCharsets.UTF_8);
			assertTrue(body.startsWith(fixture.bodyPrefix()), fixture.name());
			assertEquals(new String(bytes, StandardCharsets.UTF_8).indexOf(fixture.bodyPrefix()),
					parsed.bodyByteOffset(), fixture.name());
		}
	}

	@Test
	void completePinnedDocumentsProduceMatchingBundleManifests() throws Exception {
		for (Fixture fixture : FIXTURES) {
			byte[] original = read(fixture);
			SkillBundle bundle = SkillBundle.fromFiles(Map.of("SKILL.md", original), LIMITS, JSON_LIMITS);
			URI root = URI.create("skills://example/" + fixture.name() + "/SKILL.md");
			SkillManifest manifest = SkillManifest.from(root, bundle, JSON_LIMITS);
			Arrays.fill(original, (byte) 0);
			assertEquals(fixture.name(), bundle.name());
			assertEquals(fixture.metadata(), bundle.documentMetadata());
			assertSame(bundle.documentMetadata(), manifest.entry().members().get("frontmatter"));
			assertEquals(1, manifest.resources().size());
			assertSame(root, manifest.resources().get(0).uri());
			assertEquals(fixture.length(), manifest.resources().get(0).size());
			assertEquals("sha256:" + fixture.sha256(), manifest.resources().get(0).digest());
			assertEquals(fixture.sha256(), digest(bundle.findFileBytes("SKILL.md").orElseThrow()));
		}
	}

	@Test
	void fixtureSnapshotsAreIndependentOfCallerAndInspectionArrays() throws Exception {
		for (Fixture fixture : FIXTURES) {
			byte[] supplied = read(fixture);
			SkillFrontmatter parsed = SkillFrontmatter.parse(supplied, LIMITS, JSON_LIMITS);
			Arrays.fill(supplied, (byte) 0);
			byte[] inspection = parsed.source().originalBytes();
			Arrays.fill(inspection, (byte) 0);
			assertEquals(fixture.sha256(), digest(parsed.source().originalBytes()), fixture.name());
			assertEquals(fixture.metadata(), parsed.metadata(), fixture.name());
		}
	}

	@Test
	void malformedYamlAfterTheFrontmatterIsOpaqueBodyText() throws Exception {
		for (Fixture fixture : FIXTURES) {
			byte[] original = read(fixture);
			SkillFrontmatter unmodified = SkillFrontmatter.parse(original, LIMITS, JSON_LIMITS);
			String appendix = "\n---\nname: duplicate\nname: duplicate-again\n"
					+ "broken: [unterminated\nobject: !!java/object value\n";
			byte[] appended = (new String(original, StandardCharsets.UTF_8) + appendix)
					.getBytes(StandardCharsets.UTF_8);
			SkillFrontmatter parsed = SkillFrontmatter.parse(appended, LIMITS, JSON_LIMITS);
			assertEquals(fixture.metadata(), parsed.metadata(), fixture.name());
			assertEquals(unmodified.bodyByteOffset(), parsed.bodyByteOffset(), fixture.name());
			assertArrayEquals(appended, parsed.source().originalBytes(), fixture.name());
		}
	}

	@Test
	void inputByteLimitIncludesTheFullMarkdownBody() throws Exception {
		for (Fixture fixture : FIXTURES) {
			byte[] original = read(fixture);
			SkillYamlLimits exact = limitsWithInputBytes(original.length);
			assertEquals(fixture.metadata(), SkillFrontmatter.parse(original, exact, JSON_LIMITS).metadata());
			SkillYamlException failure = assertThrows(SkillYamlException.class,
					() -> SkillFrontmatter.parse(original, limitsWithInputBytes(original.length - 1), JSON_LIMITS));
			assertEquals(SkillYamlException.Reason.INPUT_LIMIT, failure.reason(), fixture.name());
		}
	}

	@Test
	void workLimitIncludesSnapshotAndFramingOfTheFullDocument() throws Exception {
		for (Fixture fixture : FIXTURES) {
			byte[] original = read(fixture);
			SkillYamlLimits constrained = new SkillYamlLimits(LIMITS.maximumInputBytes(),
					LIMITS.maximumNestingDepth(), LIMITS.maximumNodes(), LIMITS.maximumScalarCharacters(),
					LIMITS.maximumTotalScalarCharacters(), 3L * original.length);
			SkillYamlException failure = assertThrows(SkillYamlException.class,
					() -> SkillFrontmatter.parse(original, constrained, JSON_LIMITS));
			assertEquals(SkillYamlException.Reason.WORK_LIMIT, failure.reason(), fixture.name());
		}
	}

	private static SkillYamlLimits limitsWithInputBytes(int bytes) {
		return new SkillYamlLimits(bytes, LIMITS.maximumNestingDepth(), LIMITS.maximumNodes(),
				LIMITS.maximumScalarCharacters(), LIMITS.maximumTotalScalarCharacters(), LIMITS.maximumWork());
	}

	private static byte[] read(Fixture fixture) throws IOException {
		try (InputStream input = SkillRealDocumentTests.class.getResourceAsStream("real/" + fixture.name() + "/SKILL.md")) {
			assertNotNull(input, fixture.name());
			return input.readAllBytes();
		}
	}

	private static String digest(byte[] bytes) throws NoSuchAlgorithmException {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
	}

	private record Fixture(String name, int length, String sha256, String description, String bodyPrefix) {
		McpJsonObject metadata() {
			return new McpJsonObject(Map.of("name", new McpJsonString(this.name),
					"description", new McpJsonString(this.description),
					"license", new McpJsonString("Complete terms in LICENSE.txt")));
		}
	}
}
