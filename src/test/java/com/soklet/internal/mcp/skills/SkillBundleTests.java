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
import com.soklet.internal.mcp.protocol.McpJsonNumber;
import com.soklet.internal.mcp.protocol.McpJsonObject;
import com.soklet.internal.mcp.protocol.McpJsonString;
import com.soklet.internal.mcp.protocol.McpJsonLimits;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;

class SkillBundleTests {
	private static final SkillYamlLimits YAML_LIMITS = new SkillYamlLimits(
			65_536, 32, 8_192, 8_192, 65_536, 2_000_000);
	private static final McpJsonLimits JSON_LIMITS = new McpJsonLimits(
			131_072, 64, 65_536, 65_536, 256, 10_000, 16_384, 131_072);
	private static final byte[] ROOT = utf8("---\nname: test-skill\ndescription: Synthetic description\n---\nOpaque body.\n");

	@Test
	void readsRequiredMetadataAndPreservesAllLegalUnknownFields() {
		String header = "name: test-skill\ndescription: Synthetic description\nlicense: Apache-2.0\n"
				+ "metadata: {author: Example, version: '01'}\n"
				+ "unknown: {exact: 9007199254740993, date: 2026-09-20, enabled: true}\n"
				+ "other: [one, two]\n";
		SkillBundle bundle = bundle(Map.of("SKILL.md", document(header)));
		assertEquals("test-skill", bundle.name());
		assertEquals("Synthetic description", bundle.description());
		assertEquals(new McpJsonString("Apache-2.0"), bundle.documentMetadata().members().get("license"));
		McpJsonObject unknown = (McpJsonObject) bundle.documentMetadata().members().get("unknown");
		assertEquals(new McpJsonNumber(new BigDecimal("9007199254740993")), unknown.members().get("exact"));
		assertEquals(new McpJsonString("2026-09-20"), unknown.members().get("date"));
		assertSame(McpJsonBoolean.TRUE, unknown.members().get("enabled"));
		assertEquals(new McpJsonArray(List.of(new McpJsonString("one"), new McpJsonString("two"))),
				bundle.documentMetadata().members().get("other"));
	}

	@Test
	void snapshotsCallerMapAndEveryCallerArray() {
		byte[] root = ROOT.clone(), binary = {0, (byte) 0xff, 3};
		byte[] expectedBinary = binary.clone();
		Map<String, byte[]> supplied = new LinkedHashMap<>();
		supplied.put("data.bin", binary);
		supplied.put("SKILL.md", root);
		SkillBundle bundle = bundle(supplied);
		Arrays.fill(root, (byte) 'x');
		Arrays.fill(binary, (byte) 'y');
		supplied.clear();
		assertEquals("test-skill", bundle.name());
		assertEquals(List.of("SKILL.md", "data.bin"), bundle.filePaths());
		assertArrayEquals(ROOT, bundle.findFileBytes("SKILL.md").orElseThrow());
		assertArrayEquals(expectedBinary, bundle.findFileBytes("data.bin").orElseThrow());
	}

	@Test
	void fileInspectionCopiesOnlyTheRequestedFileAndReturnsEmptyForMissingPaths() {
		SkillBundle bundle = bundle(Map.of("SKILL.md", ROOT, "data.bin", new byte[]{1, 2, 3}));
		byte[] first = bundle.findFileBytes("data.bin").orElseThrow();
		byte[] second = bundle.findFileBytes("data.bin").orElseThrow();
		assertNotSame(first, second);
		first[0] = 9;
		assertArrayEquals(new byte[]{1, 2, 3}, second);
		assertArrayEquals(new byte[]{1, 2, 3}, bundle.findFileBytes("data.bin").orElseThrow());
		assertTrue(bundle.findFileBytes("missing.bin").isEmpty());
	}

	@Test
	void preservesExactBomCrLfAndBodyBytesInRootContentSizeAndDigest() {
		byte[] root = utf8("\uFEFF---\r\nname: test-skill\r\ndescription: 'café'\r\n---\r\n# Body\r\n");
		SkillBundle bundle = bundle(Map.of("SKILL.md", root));
		assertEquals("café", bundle.description());
		assertArrayEquals(root, bundle.findFileBytes("SKILL.md").orElseThrow());
		SkillBundle.Resource resource = bundle.resources().get(0);
		assertEquals("SKILL.md", resource.path());
		assertEquals(root.length, resource.size());
		assertEquals(digest(root), resource.digest());
		assertEquals(root.length, bundle.sizeInBytes());
	}

	@Test
	void arbitraryBinaryAndEmptyFilesHaveByteExactResources() {
		Map<String, byte[]> files = Map.of("SKILL.md", ROOT, "binary.bin",
				new byte[]{0, (byte) 0xff, (byte) 0xc0, (byte) 0xaf}, "empty", new byte[0]);
		SkillBundle bundle = bundle(files);
		long total = 0;
		for (SkillBundle.Resource resource : bundle.resources()) {
			byte[] bytes = files.get(resource.path());
			assertEquals(bytes.length, resource.size());
			assertEquals(digest(bytes), resource.digest());
			assertTrue(resource.digest().matches("sha256:[0-9a-f]{64}"));
			assertArrayEquals(bytes, bundle.findFileBytes(resource.path()).orElseThrow());
			total += bytes.length;
		}
		assertEquals(total, bundle.sizeInBytes());
	}

	@Test
	void nestedSkillDocumentIsAnOrdinaryFileNotAnotherMetadataRoot() {
		byte[] nested = {(byte) 0xff, 0, 1};
		SkillBundle bundle = bundle(Map.of("SKILL.md", ROOT, "nested/SKILL.md", nested));
		assertEquals("test-skill", bundle.name());
		assertArrayEquals(nested, bundle.findFileBytes("nested/SKILL.md").orElseThrow());
		assertEquals(digest(nested), bundle.resources().get(1).digest());
	}

	@Test
	void canonicalPathsAndResourcesUseUnsignedUtf8Order() {
		Map<String, byte[]> files = new HashMap<>();
		files.put("\uD800\uDC00", new byte[0]);
		files.put("\uE000", new byte[0]);
		files.put("a", new byte[0]);
		files.put("SKILL.md", ROOT);
		files.put("!first", new byte[0]);
		SkillBundle bundle = bundle(files);
		List<String> expected = List.of("SKILL.md", "!first", "a", "\uE000", "\uD800\uDC00");
		assertEquals(expected, bundle.filePaths());
		assertEquals(expected, bundle.resources().stream().map(SkillBundle.Resource::path).toList());
	}

	@Test
	void inspectionViewsAndNestedMetadataAreImmutable() {
		SkillBundle bundle = bundle(Map.of("SKILL.md", document("name: test-skill\ndescription: Demo\nunknown: [one]\n")));
		assertThrows(UnsupportedOperationException.class, () -> bundle.filePaths().clear());
		assertThrows(UnsupportedOperationException.class, () -> bundle.resources().clear());
		assertThrows(UnsupportedOperationException.class, () -> bundle.documentMetadata().members().clear());
		McpJsonArray unknown = (McpJsonArray) bundle.documentMetadata().members().get("unknown");
		assertThrows(UnsupportedOperationException.class, () -> unknown.values().clear());
	}

	@Test
	void fileCountBoundaryIncludesRootAndPermitsExactly512Files() {
		Map<String, byte[]> files = new LinkedHashMap<>();
		files.put("SKILL.md", ROOT);
		for (int index = 1; index < 512; ++index) files.put("file-" + index, new byte[0]);
		assertEquals(512, bundle(files).resources().size());
		files.put("file-512", new byte[0]);
		assertThrows(IllegalArgumentException.class, () -> bundle(files));
	}

	@Test
	void fileCountIsCheckedAgainstActualIterationNotReportedMapSize() {
		List<Map.Entry<String, byte[]>> entries = new ArrayList<>();
		entries.add(Map.entry("SKILL.md", ROOT));
		for (int index = 1; index <= 512; ++index) entries.add(Map.entry("file-" + index, new byte[0]));
		assertThrows(IllegalArgumentException.class, () -> bundle(malformedMap(entries)));
	}

	@Test
	void malformedMapDuplicateKeysAreRejectedInsteadOfOverwritingFileIdentity() {
		List<Map.Entry<String, byte[]>> entries = List.of(Map.entry("SKILL.md", ROOT),
				Map.entry("duplicate", new byte[]{1}), Map.entry("duplicate", new byte[]{2}));
		assertThrows(IllegalArgumentException.class, () -> bundle(malformedMap(entries)));
	}

	@Test
	void concurrentReadersCannotMutateTheOwnedSnapshotThroughReturnedArrays() throws Exception {
		byte[] content = {1, 2, 3};
		SkillBundle bundle = bundle(Map.of("SKILL.md", ROOT, "data", content));
		ExecutorService executor = Executors.newFixedThreadPool(8);
		CountDownLatch ready = new CountDownLatch(8), start = new CountDownLatch(1);
		List<Future<?>> tasks = new ArrayList<>();
		try {
			for (int thread = 0; thread < 8; ++thread) tasks.add(executor.submit(() -> {
				ready.countDown();
				assertTrue(start.await(10, SECONDS));
				for (int iteration = 0; iteration < 100; ++iteration) {
					byte[] inspection = bundle.findFileBytes("data").orElseThrow();
					assertArrayEquals(content, inspection);
					Arrays.fill(inspection, (byte) 9);
					assertArrayEquals(ROOT, bundle.findFileBytes("SKILL.md").orElseThrow());
					assertEquals("test-skill", bundle.name());
				}
				return null;
			}));
			assertTrue(ready.await(10, SECONDS));
			start.countDown();
			for (Future<?> task : tasks) task.get(10, SECONDS);
			assertArrayEquals(content, bundle.findFileBytes("data").orElseThrow());
		} finally {
			start.countDown();
			executor.shutdownNow();
			assertTrue(executor.awaitTermination(10, SECONDS));
		}
	}

	@Test
	void totalRawByteCeilingIsInclusiveAndCountsTheRoot() {
		assertEquals(16 * 1_024 * 1_024, SkillBundle.MAXIMUM_TOTAL_BYTES);
		byte[] exactBinary = new byte[SkillBundle.MAXIMUM_TOTAL_BYTES - ROOT.length];
		SkillBundle exact = bundle(Map.of("SKILL.md", ROOT, "binary.bin", exactBinary));
		assertEquals(SkillBundle.MAXIMUM_TOTAL_BYTES, exact.sizeInBytes());
		assertThrows(IllegalArgumentException.class,
				() -> bundle(Map.of("SKILL.md", ROOT, "binary.bin", exactBinary, "one-more-byte", new byte[1])));
	}

	@Test
	void rejectsMissingRootAndInvalidLogicalPaths() {
		assertThrows(IllegalArgumentException.class, () -> bundle(Map.of("skill.md", ROOT)));
		for (String path : List.of("../private-path-canary", "private-path-canary/%", "a//b", "cafe\u0301"))
			assertRedacted(assertThrows(IllegalArgumentException.class,
					() -> bundle(Map.of("SKILL.md", ROOT, path, new byte[0]))), "private-path-canary");
	}

	@Test
	void rejectsInvalidUtf8AnywhereInRootIncludingItsBody() {
		byte[] invalid = Arrays.copyOf(ROOT, ROOT.length + 1);
		invalid[invalid.length - 1] = (byte) 0xff;
		assertRedacted(assertThrows(IllegalArgumentException.class,
				() -> bundle(Map.of("SKILL.md", invalid))), "Synthetic description");
	}

	@Test
	void rejectsMissingOrInvalidRequiredMetadataWithoutEchoingAuthoredValues() {
		for (String header : List.of("description: Demo\n", "name: test-skill\n",
				"name: Private-Canary\ndescription: Demo\n", "name: test-skill\ndescription: 42\n",
				"name: test-skill\ndescription: Demo\nextra: *private-alias-canary\n"))
			assertRedacted(assertThrows(IllegalArgumentException.class,
					() -> bundle(Map.of("SKILL.md", document(header)))), "Private-Canary", "private-alias-canary");
	}

	@Test
	void suppliedParserAndJsonLimitsAreNotSilentlyWidened() {
		SkillYamlLimits tinyInput = new SkillYamlLimits(ROOT.length - 1, 32, 8_192, 8_192, 65_536, 2_000_000);
		assertThrows(IllegalArgumentException.class,
				() -> SkillBundle.fromFiles(Map.of("SKILL.md", ROOT), tinyInput, JSON_LIMITS));
		McpJsonLimits tinyOutput = new McpJsonLimits(131_072, 64, 65_536, 65_536, 256, 10_000, 16_384, 8);
		assertThrows(IllegalArgumentException.class,
				() -> SkillBundle.fromFiles(Map.of("SKILL.md", ROOT), YAML_LIMITS, tinyOutput));
	}

	@Test
	void nullArgumentsAndFileValuesAreRejected() {
		assertThrows(NullPointerException.class, () -> SkillBundle.fromFiles(null, YAML_LIMITS, JSON_LIMITS));
		assertThrows(NullPointerException.class, () -> SkillBundle.fromFiles(Map.of("SKILL.md", ROOT), null, JSON_LIMITS));
		assertThrows(NullPointerException.class, () -> SkillBundle.fromFiles(Map.of("SKILL.md", ROOT), YAML_LIMITS, null));
		Map<String, byte[]> files = new HashMap<>();
		files.put("SKILL.md", ROOT);
		files.put("empty", null);
		assertThrows(NullPointerException.class, () -> bundle(files));
	}

	@Test
	void bundleDiagnosticRepresentationDoesNotRevealContentOrPaths() {
		SkillBundle bundle = bundle(Map.of("SKILL.md", document("name: test-skill\ndescription: private-metadata-canary\n"),
				"private-path-canary", utf8("private-body-canary")));
		for (String canary : List.of("test-skill", "private-metadata-canary", "private-path-canary", "private-body-canary"))
			assertFalse(bundle.toString().contains(canary));
	}

	private static SkillBundle bundle(Map<String, byte[]> files) {
		return SkillBundle.fromFiles(files, YAML_LIMITS, JSON_LIMITS);
	}

	private static Map<String, byte[]> malformedMap(List<Map.Entry<String, byte[]>> entries) {
		return new AbstractMap<>() {
			@Override public int size() { return 0; }
			@Override public Set<Entry<String, byte[]>> entrySet() {
				return new AbstractSet<>() {
					@Override public int size() { return 0; }
					@Override public Iterator<Entry<String, byte[]>> iterator() { return entries.iterator(); }
				};
			}
		};
	}

	private static byte[] document(String header) {
		return utf8("---\n" + header + "---\nOpaque body.\n");
	}

	private static byte[] utf8(String value) { return value.getBytes(StandardCharsets.UTF_8); }

	private static String digest(byte[] bytes) {
		try {
			return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException impossible) {
			throw new AssertionError(impossible);
		}
	}

	private static void assertRedacted(RuntimeException failure, String... authoredValues) {
		assertNotNull(failure.getMessage());
		assertFalse(failure.getMessage().isBlank());
		assertNull(failure.getCause());
		for (String authored : authoredValues) assertFalse(failure.getMessage().contains(authored));
	}
}
