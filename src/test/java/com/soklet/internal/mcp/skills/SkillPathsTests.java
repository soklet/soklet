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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.nio.charset.StandardCharsets;
import java.util.AbstractCollection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SkillPathsTests {
	@Test
	void rootDocumentIsRequiredExactlyOnceWithExactCase() {
		assertEquals(List.of("SKILL.md"), SkillPaths.from(List.of("SKILL.md")).paths());
		for (List<String> paths : List.of(List.<String>of(), List.of("skill.md"),
				List.of("nested/SKILL.md"), List.of("SKILL.md", "SKILL.md")))
			assertInvalid(() -> SkillPaths.from(paths));
	}

	@Test
	void canonicalOrderIsRootFirstThenUnsignedUtf8NotUtf16() {
		List<String> expected = List.of("SKILL.md", "!first", "Z", "a", "z",
				"é", "\uE000", "\uD800\uDC00");
		List<String> reversed = new ArrayList<>(expected);
		java.util.Collections.reverse(reversed);
		assertEquals(expected, SkillPaths.from(reversed).paths());
		assertEquals(expected, SkillPaths.from(new HashSet<>(reversed)).paths());
		for (Map<String, Integer> map : List.<Map<String, Integer>>of(new java.util.HashMap<>(),
				new java.util.LinkedHashMap<>(), new java.util.TreeMap<>())) {
			for (String path : reversed) map.put(path, 0);
			assertEquals(expected, SkillPaths.from(map.keySet()).paths());
		}
	}

	@Test
	void snapshotsInputAndExposesOnlyImmutableOrderedPaths() {
		List<String> supplied = new ArrayList<>(List.of("z", "SKILL.md", "a"));
		SkillPaths paths = SkillPaths.from(supplied);
		supplied.clear();
		assertEquals(List.of("SKILL.md", "a", "z"), paths.paths());
		assertThrows(UnsupportedOperationException.class, () -> paths.paths().add("extra"));
		assertThrows(UnsupportedOperationException.class, () -> paths.paths().set(0, "changed"));
		assertThrows(UnsupportedOperationException.class, () -> paths.paths().remove(0));
	}

	@Test
	void fileCountCeilingIncludesTheRootAndIsInclusive() {
		List<String> paths = new ArrayList<>();
		paths.add("SKILL.md");
		for (int index = 1; index < 512; ++index) paths.add("file-" + index);
		assertEquals(512, SkillPaths.from(paths).paths().size());
		paths.add("file-512");
		assertInvalid(() -> SkillPaths.from(paths));
	}

	@Test
	void fileCountCeilingDoesNotTrustAnUnderreportedCollectionSize() {
		List<String> paths = new ArrayList<>();
		paths.add("SKILL.md");
		for (int index = 1; index <= 512; ++index) paths.add("file-" + index);
		assertInvalid(() -> SkillPaths.from(new AbstractCollection<>() {
			@Override public int size() { return 0; }
			@Override public Iterator<String> iterator() { return paths.iterator(); }
		}));
	}

	@Test
	void asciiPathByteCeilingIsInclusive() {
		String exact = "a".repeat(8_192);
		assertEquals(exact, SkillPaths.from(List.of("SKILL.md", exact)).paths().get(1));
		assertInvalidPath(exact + "a");
	}

	@Test
	void utf8ByteCeilingCountsMultibyteAndSupplementaryScalars() {
		for (String exact : List.of("é".repeat(4_096), "\uD83D\uDE42".repeat(2_048))) {
			assertEquals(8_192, exact.getBytes(StandardCharsets.UTF_8).length);
			assertTrue(SkillPaths.from(List.of("SKILL.md", exact)).paths().contains(exact));
			assertInvalidPath(exact + "a");
		}
	}

	@Test
	void requiresNfcWithoutSilentlyNormalizingOrCaseFolding() {
		assertInvalidPath("cafe\u0301.md");
		assertInvalidPath("\u1100\u1161.md");
		assertEquals(List.of("SKILL.md", "A.md", "a.md", "café.md", "가.md"),
				SkillPaths.from(List.of("가.md", "a.md", "SKILL.md", "café.md", "A.md")).paths());
		assertInvalid(() -> SkillPaths.from(List.of("SKILL.md", "café.md", "café.md")));
	}

	@Test
	void rejectsEmptyLeadingTrailingAndRepeatedSlashSegments() {
		for (String path : List.of("", "/file", "file/", "a//b", "//", "/"))
			assertInvalidPath(path);
	}

	@Test
	void rejectsOnlyCompleteDotAndDotDotSegments() {
		for (String path : List.of(".", "..", "./a", "../a", "a/.", "a/..", "a/./b", "a/../b"))
			assertInvalidPath(path);
		for (String path : List.of(".hidden", "..hidden", "a...b", "dir/.../file"))
			assertTrue(SkillPaths.from(List.of("SKILL.md", path)).paths().contains(path));
	}

	@Test
	void rejectsBackslashesAndAllPercentSpellingsBeforeUriDerivation() {
		for (String path : List.of("a\\b", "\\server\\file", "%", "a%20b", "a%2fb", "%2e%2e/file"))
			assertInvalidPath(path);
	}

	@Test
	void rejectsEveryIsoControlCharacter() {
		for (int value = 0; value <= 0x9f; ++value)
			if (Character.isISOControl(value)) assertInvalidPath("a" + (char) value + "b");
	}

	@Test
	void rejectsUnpairedSurrogatesButKeepsValidUnicodeScalarPairs() {
		for (String path : List.of("\uD800", "\uDC00", "a\uD800b", "a\uDC00b", "\uDC00\uD800"))
			assertInvalidPath(path);
		assertTrue(SkillPaths.from(List.of("SKILL.md", "notes/\uD83D\uDE42.md")).paths()
				.contains("notes/\uD83D\uDE42.md"));
	}

	@Test
	void allowsReservedUriCharactersAsLiteralLogicalPathText() {
		String path = "docs/a b!$&'()*+,;=:@[]?#.md";
		assertTrue(SkillPaths.from(List.of("SKILL.md", path)).paths().contains(path));
	}

	@Test
	void malformedInputDiagnosticsAreFixedAndRedacted() {
		IllegalArgumentException first = assertInvalidPath("private-path-canary/%");
		IllegalArgumentException second = assertInvalidPath("different-private-canary/%");
		assertEquals(first.getMessage(), second.getMessage());
		assertFalse(first.getMessage().contains("private-path-canary"));
		assertFalse(second.getMessage().contains("different-private-canary"));
		assertEquals("SkillPaths[redacted]",
				SkillPaths.from(List.of("SKILL.md", "private-path-canary")).toString());
	}

	@Test
	void nullCollectionAndElementsHaveFixedNullFailures() {
		assertNullFailure(() -> SkillPaths.from(null));
		assertNullFailure(() -> SkillPaths.from(Arrays.asList("SKILL.md", null)));
	}

	private static IllegalArgumentException assertInvalidPath(String path) {
		return assertInvalid(() -> SkillPaths.from(List.of("SKILL.md", path)));
	}

	private static IllegalArgumentException assertInvalid(Executable executable) {
		IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, executable);
		assertEquals(IllegalArgumentException.class, failure.getClass());
		assertNotNull(failure.getMessage());
		assertFalse(failure.getMessage().isBlank());
		assertNull(failure.getCause());
		return failure;
	}

	private static void assertNullFailure(Executable executable) {
		NullPointerException failure = assertThrows(NullPointerException.class, executable);
		assertEquals(NullPointerException.class, failure.getClass());
		assertNotNull(failure.getMessage());
		assertFalse(failure.getMessage().isBlank());
		assertNull(failure.getCause());
	}
}
