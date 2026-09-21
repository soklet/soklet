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
package com.soklet;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class McpSkillBundleTests {
	private static final byte[] ROOT = ("\uFEFF---\r\nname: test-skill\r\ndescription: Synthetic description\r\n"
			+ "custom: {exact: 9007199254740993, date: 2026-09-20}\r\n---\r\nOpaque body.\r\n")
			.getBytes(StandardCharsets.UTF_8);

	@Test
	void snapshotsMapAndBytesAndPreservesCompleteMetadata() {
		byte[] root = ROOT.clone(), binary = {0, (byte) 0xff, 2};
		Map<String, byte[]> files = new LinkedHashMap<>();
		files.put("binary.dat", binary);
		files.put("SKILL.md", root);
		McpSkillBundle bundle = McpSkillBundle.fromFiles(files);
		Arrays.fill(root, (byte) 'x');
		Arrays.fill(binary, (byte) 0);
		files.clear();
		assertEquals("test-skill", bundle.getName());
		assertEquals("Synthetic description", bundle.getDescription());
		assertArrayEquals(ROOT, bundle.findFileBytes("SKILL.md").orElseThrow());
		assertArrayEquals(new byte[]{0, (byte) 0xff, 2}, bundle.findFileBytes("binary.dat").orElseThrow());
		McpJsonObject custom = (McpJsonObject) bundle.getDocumentMetadata().getMembers().get("custom");
		assertEquals(McpJsonNumber.fromValue(new BigDecimal("9007199254740993")), custom.getMembers().get("exact"));
		assertEquals(McpJsonString.fromValue("2026-09-20"), custom.getMembers().get("date"));
		assertSame(bundle.getDocumentMetadata(), bundle.getDocumentMetadata());
	}

	@Test
	void inspectionCopiesAreIndependentAndMissingPathsStayAbsent() {
		McpSkillBundle bundle = McpSkillBundle.fromFiles(Map.of("SKILL.md", ROOT));
		byte[] first = bundle.findFileBytes("SKILL.md").orElseThrow();
		byte[] second = bundle.findFileBytes("SKILL.md").orElseThrow();
		assertNotSame(first, second);
		first[0] = 0;
		assertArrayEquals(ROOT, second);
		assertArrayEquals(ROOT, bundle.findFileBytes("SKILL.md").orElseThrow());
		assertTrue(bundle.findFileBytes("missing").isEmpty());
		assertThrows(NullPointerException.class, () -> bundle.findFileBytes(null));
	}

	@Test
	void pathSetUsesCanonicalUtf8OrderAndAllInspectionViewsAreImmutable() {
		McpSkillBundle bundle = McpSkillBundle.fromFiles(Map.of("SKILL.md", ROOT,
				"\uD800\uDC00", new byte[0], "\uE000", new byte[0], "!first", new byte[0]));
		assertEquals(List.of("SKILL.md", "!first", "\uE000", "\uD800\uDC00"), List.copyOf(bundle.getFilePaths()));
		assertThrows(UnsupportedOperationException.class, () -> bundle.getFilePaths().clear());
		assertThrows(UnsupportedOperationException.class, () -> bundle.getDocumentMetadata().getMembers().clear());
	}

	@Test
	void equalityUsesActualBytesAndPathsIndependentOfInputOrder() {
		Map<String, byte[]> firstFiles = new LinkedHashMap<>();
		firstFiles.put("SKILL.md", ROOT);
		firstFiles.put("binary.dat", new byte[]{1, 2});
		Map<String, byte[]> reversed = new LinkedHashMap<>();
		reversed.put("binary.dat", new byte[]{1, 2});
		reversed.put("SKILL.md", ROOT.clone());
		McpSkillBundle first = McpSkillBundle.fromFiles(firstFiles);
		McpSkillBundle equal = McpSkillBundle.fromFiles(reversed);
		assertEquals(first, equal);
		assertEquals(first.hashCode(), equal.hashCode());
		assertNotEquals(first, McpSkillBundle.fromFiles(Map.of("SKILL.md", ROOT, "binary.dat", new byte[]{1, 3})));
		assertNotEquals(first, McpSkillBundle.fromFiles(Map.of("SKILL.md", ROOT, "different.dat", new byte[]{1, 2})));
		byte[] changedBody = ROOT.clone();
		changedBody[changedBody.length - 3] = '!';
		assertNotEquals(first, McpSkillBundle.fromFiles(Map.of("SKILL.md", changedBody, "binary.dat", new byte[]{1, 2})));
		assertNotEquals(first, null);
		assertNotEquals(first, "bundle");
	}

	@Test
	void invalidInputFailsWithoutEchoingAuthoredPathsOrMetadata() {
		assertThrows(NullPointerException.class, () -> McpSkillBundle.fromFiles(null));
		Map<String, byte[]> nullBytes = new HashMap<>();
		nullBytes.put("SKILL.md", null);
		assertThrows(NullPointerException.class, () -> McpSkillBundle.fromFiles(nullBytes));
		IllegalArgumentException pathFailure = assertThrows(IllegalArgumentException.class,
				() -> McpSkillBundle.fromFiles(Map.of("SKILL.md", ROOT, "private-path-canary/%", new byte[0])));
		assertFalse(pathFailure.getMessage().contains("private-path-canary"));
		assertNull(pathFailure.getCause());
		byte[] invalid = "---\nname: Private-Canary\ndescription: Description\n---\n".getBytes(StandardCharsets.UTF_8);
		IllegalArgumentException metadataFailure = assertThrows(IllegalArgumentException.class,
				() -> McpSkillBundle.fromFiles(Map.of("SKILL.md", invalid)));
		assertFalse(metadataFailure.getMessage().contains("Private-Canary"));
		assertNull(metadataFailure.getCause());
	}

	@Test
	void diagnosticsAreFixedAndDoNotExposeOwnedContent() {
		McpSkillBundle bundle = McpSkillBundle.fromFiles(Map.of("SKILL.md", ROOT, "private-path-canary", new byte[0]));
		assertEquals("McpSkillBundle[redacted]", bundle.toString());
	}
}
