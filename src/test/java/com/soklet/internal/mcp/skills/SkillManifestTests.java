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
import com.soklet.internal.mcp.protocol.McpJsonCodec;
import com.soklet.internal.mcp.protocol.McpJsonLimits;
import com.soklet.internal.mcp.protocol.McpJsonNumber;
import com.soklet.internal.mcp.protocol.McpJsonObject;
import com.soklet.internal.mcp.protocol.McpJsonString;
import com.soklet.internal.mcp.protocol.McpJsonValue;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class SkillManifestTests {
	private static final SkillYamlLimits YAML_LIMITS = new SkillYamlLimits(
			65_536, 32, 8_192, 8_192, 65_536, 2_000_000);
	private static final McpJsonLimits JSON_LIMITS = limits(64, 16_384, 131_072);
	private static final McpJsonCodec JSON_CODEC = new McpJsonCodec(JSON_LIMITS);
	private static final URI ROOT_URI = URI.create("skill://test-skill/SKILL.md");
	private static final byte[] ROOT = ("---\nname: test-skill\ndescription: Synthetic description\n"
			+ "unknown: {date: 2026-09-20, exact: 9007199254740993}\n---\nOpaque body.\n")
			.getBytes(StandardCharsets.UTF_8);

	@Test
	void entryContainsRootUriCompleteFrontmatterAndCanonicalResources() {
		SkillBundle bundle = bundle(Map.of("SKILL.md", ROOT, "data.bin", new byte[]{0, (byte) 0xff}));
		SkillManifest manifest = manifest(ROOT_URI, bundle);
		McpJsonObject entry = manifest.entry();
		assertEquals(Set.of("uri", "frontmatter", "resources"), entry.members().keySet());
		assertEquals(new McpJsonString(ROOT_URI.toASCIIString()), entry.members().get("uri"));
		assertEquals(bundle.documentMetadata(), entry.members().get("frontmatter"));
		assertTrue(((McpJsonObject) entry.members().get("frontmatter")).members().containsKey("unknown"));
		assertEquals(2, ((McpJsonArray) entry.members().get("resources")).values().size());
		assertEquals(entry, JSON_CODEC.parse(JSON_CODEC.toUtf8Bytes(entry)));
	}

	@Test
	void originalRootUriObjectIsRetainedInManifestAndRootResource() {
		URI root = URI.create("SKILL://HOST.invalid/test-skill/SKILL.md");
		SkillManifest manifest = manifest(root, bundle(Map.of("SKILL.md", ROOT)));
		assertSame(root, manifest.uri());
		assertSame(root, manifest.resources().get(0).uri());
		assertEquals(root.toString(), ((McpJsonString) manifest.entry().members().get("uri")).value());
	}

	@Test
	void resourceUrisEncodeUtf8AndReservedCharactersWithoutChangingLogicalIdentity() {
		String path = "refs/café space+#?.bin";
		SkillManifest manifest = manifest(ROOT_URI, bundle(Map.of("SKILL.md", ROOT, path, new byte[0])));
		assertEquals(URI.create("skill://test-skill/refs/caf%C3%A9%20space%2B%23%3F.bin"),
				manifest.resources().get(1).uri());
		assertEquals(2, manifest.resources().size());
	}

	@Test
	void manifestOrderIsRootFirstThenUnsignedUtf8LogicalPathOrder() {
		SkillBundle bundle = bundle(Map.of("\uD800\uDC00", new byte[0], "\uE000", new byte[0],
				"!first", new byte[0], "SKILL.md", ROOT));
		SkillManifest manifest = manifest(ROOT_URI, bundle);
		assertEquals(List.of(ROOT_URI, URI.create("skill://test-skill/%21first"),
				URI.create("skill://test-skill/%EE%80%80"), URI.create("skill://test-skill/%F0%90%80%80")),
				manifest.resources().stream().map(SkillManifest.Resource::uri).toList());
	}

	@Test
	void resourceObjectsAndJsonAgreeExactlyWithBundleDigestsAndSizes() {
		SkillBundle bundle = bundle(Map.of("SKILL.md", ROOT, "binary.bin", new byte[]{1, 2, 3}, "empty", new byte[0]));
		SkillManifest manifest = manifest(ROOT_URI, bundle);
		List<McpJsonValue> json = ((McpJsonArray) manifest.entry().members().get("resources")).values();
		assertEquals(bundle.resources().size(), manifest.resources().size());
		for (int index = 0; index < bundle.resources().size(); ++index) {
			SkillBundle.Resource source = bundle.resources().get(index);
			SkillManifest.Resource resource = manifest.resources().get(index);
			assertEquals(source.digest(), resource.digest());
			assertEquals(source.size(), resource.size());
			McpJsonObject object = (McpJsonObject) json.get(index);
			assertEquals(Set.of("uri", "digest", "size"), object.members().keySet());
			assertEquals(new McpJsonString(resource.uri().toASCIIString()), object.members().get("uri"));
			assertEquals(new McpJsonString(resource.digest()), object.members().get("digest"));
			assertEquals(new McpJsonNumber(resource.size()), object.members().get("size"));
		}
	}

	@Test
	void hashLinkedAndTreeMapInputsProduceByteIdenticalManifestEntries() {
		Map<String, byte[]> original = new LinkedHashMap<>();
		original.put("z", new byte[]{3});
		original.put("SKILL.md", ROOT);
		original.put("a", new byte[]{1});
		original.put("\uE000", new byte[]{2});
		byte[] expected = JSON_CODEC.toUtf8Bytes(manifest(ROOT_URI, bundle(original)).entry());
		List<String> reversedKeys = new ArrayList<>(original.keySet());
		Collections.reverse(reversedKeys);
		Map<String, byte[]> reversed = new LinkedHashMap<>();
		for (String key : reversedKeys) reversed.put(key, original.get(key));
		for (Map<String, byte[]> input : List.of(new HashMap<>(original), new TreeMap<>(original), reversed))
			assertArrayEquals(expected, JSON_CODEC.toUtf8Bytes(manifest(ROOT_URI, bundle(input)).entry()));
	}

	@Test
	void viewsAndNestedJsonCollectionsAreImmutable() {
		SkillManifest manifest = manifest(ROOT_URI, bundle(Map.of("SKILL.md", ROOT)));
		assertThrows(UnsupportedOperationException.class, () -> manifest.resources().clear());
		assertThrows(UnsupportedOperationException.class, () -> manifest.entry().members().clear());
		McpJsonArray resources = (McpJsonArray) manifest.entry().members().get("resources");
		assertThrows(UnsupportedOperationException.class, () -> resources.values().clear());
		assertThrows(UnsupportedOperationException.class, () -> ((McpJsonObject) resources.values().get(0)).members().clear());
	}

	@Test
	void wrongDirectoryNameOrInvalidRootShapeIsRejectedWithoutLeakingUri() {
		SkillBundle bundle = bundle(Map.of("SKILL.md", ROOT));
		for (String raw : List.of("skill://private-uri-canary/SKILL.md", "skill://test-skill/wrong.md",
				"skill://test-skill/SKILL.md?private-query-canary", "skill:/../test-skill/SKILL.md")) {
			IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
					() -> manifest(URI.create(raw), bundle));
			assertNull(failure.getCause());
			assertNotNull(failure.getMessage());
			assertFalse(failure.getMessage().contains("private-uri-canary"));
			assertFalse(failure.getMessage().contains("private-query-canary"));
		}
	}

	@Test
	void wholeEntryDepthLimitIncludesItsEnvelopeAndResourceObjects() {
		SkillBundle bundle = bundle(Map.of("SKILL.md", ROOT));
		assertNotNull(SkillManifest.from(ROOT_URI, bundle, limits(4, 16_384, 131_072)));
		assertThrows(IllegalArgumentException.class,
				() -> SkillManifest.from(ROOT_URI, bundle, limits(3, 16_384, 131_072)));
	}

	@Test
	void wholeEntryNodeLimitIsInclusiveNotJustTheMetadataNodeCount() {
		SkillBundle bundle = bundle(Map.of("SKILL.md", ROOT, "data", new byte[0]));
		SkillManifest manifest = manifest(ROOT_URI, bundle);
		int nodes = countNodes(manifest.entry());
		assertTrue(nodes > countNodes(bundle.documentMetadata()));
		assertEquals(manifest.entry(), SkillManifest.from(ROOT_URI, bundle, limits(64, nodes, 131_072)).entry());
		assertThrows(IllegalArgumentException.class,
				() -> SkillManifest.from(ROOT_URI, bundle, limits(64, nodes - 1, 131_072)));
	}

	@Test
	void wholeEntryOutputLimitIsInclusiveAndIncludesManifestOverhead() {
		SkillBundle bundle = bundle(Map.of("SKILL.md", ROOT));
		SkillManifest manifest = manifest(ROOT_URI, bundle);
		int exactBytes = JSON_CODEC.toUtf8Bytes(manifest.entry()).length;
		int metadataBytes = JSON_CODEC.toUtf8Bytes(bundle.documentMetadata()).length;
		assertTrue(exactBytes > metadataBytes);
		assertEquals(manifest.entry(), SkillManifest.from(ROOT_URI, bundle, limits(64, 16_384, exactBytes)).entry());
		assertThrows(IllegalArgumentException.class,
				() -> SkillManifest.from(ROOT_URI, bundle, limits(64, 16_384, exactBytes - 1)));
		assertThrows(IllegalArgumentException.class,
				() -> SkillManifest.from(ROOT_URI, bundle, limits(64, 16_384, metadataBytes)));
	}

	@Test
	void generatedManifestRemainsStableAfterInputAndInspectedBytesAreChanged() {
		byte[] root = ROOT.clone(), binary = {1, 2};
		Map<String, byte[]> files = new HashMap<>(Map.of("SKILL.md", root, "data", binary));
		SkillBundle bundle = bundle(files);
		SkillManifest manifest = manifest(ROOT_URI, bundle);
		byte[] before = JSON_CODEC.toUtf8Bytes(manifest.entry());
		root[0] = 0;
		binary[0] = 0;
		files.clear();
		bundle.findFileBytes("data").orElseThrow()[0] = 0;
		assertArrayEquals(before, JSON_CODEC.toUtf8Bytes(manifest.entry()));
		assertArrayEquals(before, JSON_CODEC.toUtf8Bytes(manifest(ROOT_URI, bundle).entry()));
	}

	@Test
	void nullArgumentsAreRejected() {
		SkillBundle bundle = bundle(Map.of("SKILL.md", ROOT));
		assertThrows(NullPointerException.class, () -> SkillManifest.from(null, bundle, JSON_LIMITS));
		assertThrows(NullPointerException.class, () -> SkillManifest.from(ROOT_URI, null, JSON_LIMITS));
		assertThrows(NullPointerException.class, () -> SkillManifest.from(ROOT_URI, bundle, null));
	}

	@Test
	void manifestDiagnosticRepresentationDoesNotExposeContentOrRegistrationUri() {
		SkillManifest manifest = manifest(ROOT_URI, bundle(Map.of("SKILL.md", ROOT, "private-path-canary", new byte[0])));
		for (String authored : List.of("test-skill", "private-path-canary", "Synthetic description", "9007199254740993"))
			assertFalse(manifest.toString().contains(authored));
	}

	private static SkillBundle bundle(Map<String, byte[]> files) {
		return SkillBundle.fromFiles(files, YAML_LIMITS, JSON_LIMITS);
	}

	private static SkillManifest manifest(URI root, SkillBundle bundle) {
		return SkillManifest.from(root, bundle, JSON_LIMITS);
	}

	private static McpJsonLimits limits(int depth, int nodes, int outputBytes) {
		return new McpJsonLimits(131_072, depth, 65_536, 65_536, 256, 10_000, nodes, outputBytes);
	}

	private static int countNodes(McpJsonValue value) {
		if (value instanceof McpJsonArray array)
			return 1 + array.values().stream().mapToInt(SkillManifestTests::countNodes).sum();
		if (value instanceof McpJsonObject object)
			return 1 + object.members().values().stream().mapToInt(SkillManifestTests::countNodes).sum();
		return 1;
	}
}
