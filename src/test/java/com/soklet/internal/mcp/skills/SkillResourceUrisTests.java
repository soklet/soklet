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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SkillResourceUrisTests {
	private static final int EXACT_URI_BYTE_CEILING = 1_048_576;
	// Explicit per-test projection budget, not an adopted Skills memory default.
	private static final long TEST_PROJECTION_BYTES = 4L * EXACT_URI_BYTE_CEILING;
	private static final SkillPaths ROOT_ONLY = SkillPaths.from(List.of("SKILL.md"));
	private static final URI ROOT = URI.create("skill://example/SKILL.md");

	@Test
	void preservesTheOriginalRootUriObjectAndCanonicalPathOrder() {
		URI root = URI.create("SKILL://HOST.invalid/example/SKILL.md");
		SkillPaths paths = SkillPaths.from(List.of("z", "\uD800\uDC00", "SKILL.md", "\uE000", "a"));
		Map<String, URI> uris = project(root, paths).uris();
		assertSame(root, uris.get("SKILL.md"));
		assertEquals(paths.paths(), List.copyOf(uris.keySet()));
		assertEquals(URI.create("SKILL://HOST.invalid/example/a"), uris.get("a"));
	}

	@Test
	void exposesAnImmutableMapAndImmutableEntries() {
		Map<String, URI> uris = project(ROOT, SkillPaths.from(List.of("SKILL.md", "file"))).uris();
		assertThrows(UnsupportedOperationException.class, () -> uris.put("extra", ROOT));
		assertThrows(UnsupportedOperationException.class, () -> uris.remove("file"));
		assertThrows(UnsupportedOperationException.class, () -> uris.entrySet().iterator().next().setValue(ROOT));
	}

	@Test
	void acceptsAbsoluteHierarchiesWithPathOrAuthoritySkillIdentity() {
		for (String raw : List.of("skill://example/SKILL.md", "skill:/example/SKILL.md", "file:///example/SKILL.md",
				"https://host.invalid/skills/example/SKILL.md", "custom+v1://example/SKILL.md")) {
			URI root = URI.create(raw);
			assertSame(root, project(root, ROOT_ONLY).uris().get("SKILL.md"));
		}
	}

	@Test
	void decodesTheFinalPathOrAuthorityIdentityWithoutRewritingTheRoot() {
		for (String raw : List.of("skill://ex%61mple/SKILL.md",
				"skill:/%65xample/SKILL.md", "skill://host.invalid/ex%61mple/SKILL.md")) {
			URI root = URI.create(raw);
			Map<String, URI> uris = project(root, SkillPaths.from(List.of("SKILL.md", "file"))).uris();
			assertSame(root, uris.get("SKILL.md"));
			assertEquals(raw.substring(0, raw.length() - "SKILL.md".length()) + "file",
					uris.get("file").toASCIIString());
		}
	}

	@Test
	void encodesUtf8WithUppercaseHexAndOnlyRfc3986UnreservedBytesLiteral() {
		String path = "refs/a b!$&'()*+,;=:@[]?#é\uD83D\uDE42.md";
		Map<String, URI> uris = project(ROOT, SkillPaths.from(List.of("SKILL.md", path,
				"AZaz09-._~"))).uris();
		assertEquals("skill://example/refs/a%20b%21%24%26%27%28%29%2A%2B%2C%3B%3D%3A%40"
				+ "%5B%5D%3F%23%C3%A9%F0%9F%99%82.md", uris.get(path).toASCIIString());
		assertEquals("skill://example/AZaz09-._~", uris.get("AZaz09-._~").toASCIIString());
	}

	@Test
	void derivedUrisStayInTheExactDirectoryPrefixAtSegmentBoundaries() {
		URI root = URI.create("skill://host.invalid/prefix/example/SKILL.md");
		SkillPaths paths = SkillPaths.from(List.of("SKILL.md", "nested/SKILL.md", ".hidden",
				"..safe/lookalike", "colon:name"));
		for (URI uri : project(root, paths).uris().values()) {
			assertTrue(uri.isAbsolute());
			assertFalse(uri.isOpaque());
			assertEquals(uri, uri.normalize());
			assertTrue(uri.toASCIIString().startsWith("skill://host.invalid/prefix/example/"));
			assertNull(uri.getRawQuery());
			assertNull(uri.getRawFragment());
		}
	}

	@Test
	void caseSensitivePathsStayDistinctWhileUriSchemeAndHostIdentityAreCaseInsensitive() {
		SkillPaths paths = SkillPaths.from(List.of("SKILL.md", "A.md", "a.md"));
		Map<String, URI> upper = project(URI.create("SKILL://HOST.invalid/example/SKILL.md"), paths).uris();
		Map<String, URI> lower = project(URI.create("skill://host.invalid/example/SKILL.md"), paths).uris();
		assertEquals(3, upper.size());
		assertNotEquals(upper.get("A.md"), upper.get("a.md"));
		for (String path : paths.paths()) assertEquals(upper.get(path), lower.get(path));
		assertNotEquals(upper.get("a.md").toString(), lower.get("a.md").toString());
	}

	@Test
	void uriIdentityIgnoresPercentHexCaseButDoesNotDecodeUnreservedAliases() {
		SkillPaths paths = SkillPaths.from(List.of("SKILL.md", "file"));
		Map<String, URI> upper = project(URI.create("skill:/%4A/example/SKILL.md"), paths).uris();
		Map<String, URI> lower = project(URI.create("skill:/%4a/example/SKILL.md"), paths).uris();
		Map<String, URI> literal = project(URI.create("skill:/J/example/SKILL.md"), paths).uris();
		for (String path : paths.paths()) {
			assertEquals(upper.get(path), lower.get(path));
			assertNotEquals(upper.get(path), literal.get(path));
		}
	}

	@Test
	void percentEncodedPercentRemainsDistinctAndIsNotDecodedTwice() {
		URI root = URI.create("skill:/percent%252F/example/SKILL.md");
		Map<String, URI> uris = project(root, SkillPaths.from(List.of("SKILL.md", "file"))).uris();
		assertSame(root, uris.get("SKILL.md"));
		assertEquals("skill:/percent%252F/example/file", uris.get("file").toASCIIString());
	}

	@Test
	void rejectsRelativeOpaqueOrNonAsciiRoots() {
		for (String root : List.of("example/SKILL.md", "/example/SKILL.md", "//example/SKILL.md",
				"skill:example/SKILL.md", "skill:/café/example/SKILL.md")) assertInvalidRoot(root);
	}

	@Test
	void rejectsAnyQueryOrFragmentEvenWhenEmpty() {
		for (String suffix : List.of("?", "?query", "#", "#fragment"))
			assertInvalidRoot(ROOT + suffix);
	}

	@Test
	void rootMustEndInTheExactLiteralSkillDocumentSegment() {
		for (String root : List.of("skill://example", "skill://example/", "skill://example/skill.md",
				"skill://example/prefixSKILL.md", "skill://example/SKILL.md/",
				"skill://example/SK%49LL.md", "skill://example/%53KILL.md")) assertInvalidRoot(root);
	}

	@Test
	void rejectsLiteralDotSegmentsAndNonNormalizedPaths() {
		for (String root : List.of("skill:/a/../example/SKILL.md", "skill:/./example/SKILL.md",
				"skill:/a/./example/SKILL.md", "skill:/a//example/SKILL.md")) assertInvalidRoot(root);
	}

	@Test
	void rejectsEscapedDotSegmentsIncludingMixedLiteralAndEscapedDots() {
		for (String segment : List.of("%2e", "%2E", "%2e%2e", ".%2E", "%2e."))
			assertInvalidRoot("skill:/" + segment + "/example/SKILL.md");
	}

	@Test
	void rejectsEncodedSlashOrBackslashAnywhereInPathSegments() {
		for (String segment : List.of("a%2fb", "a%2Fb", "a%5cb", "a%5Cb"))
			assertInvalidRoot("skill:/" + segment + "/example/SKILL.md");
	}

	@Test
	void rejectsMalformedUtf8AndDecodedControlsInAnySegment() {
		for (String segment : List.of("%80", "%C0%AF", "%C2", "%E2%82", "%ED%A0%80",
				"%F4%90%80%80", "%FF", "%00", "%0A", "%7F", "%C2%85"))
			assertInvalidRoot("skill:/" + segment + "/example/SKILL.md");
	}

	@Test
	void rejectsMalformedUtf8AndSeparatorsInAuthorityIdentity() {
		for (String authority : List.of("ex%FFample", "ex%00ample", "ex%2fample", "ex%5cample"))
			assertInvalidRoot("skill://" + authority + "/SKILL.md");
	}

	@Test
	void requiresExactDecodedDirectoryNameAgreementWithoutPrefixMatching() {
		for (String root : List.of("skill://different/SKILL.md", "skill://example/other/SKILL.md",
				"skill:/example-extra/SKILL.md", "skill:/EXAMPLE/SKILL.md", "skill:/SKILL.md"))
			assertInvalidRoot(root);
	}

	@Test
	void individualRootUriByteCeilingIsInclusive() {
		URI exact = rootOfLength(EXACT_URI_BYTE_CEILING);
		assertEquals(EXACT_URI_BYTE_CEILING, exact.toASCIIString().length());
		assertSame(exact, project(exact, ROOT_ONLY).uris().get("SKILL.md"));
		assertInvalid(() -> project(rootOfLength(EXACT_URI_BYTE_CEILING + 1), ROOT_ONLY));
	}

	@Test
	void individualDerivedUriByteCeilingIsInclusive() {
		URI root = rootOfLength(EXACT_URI_BYTE_CEILING - 1);
		SkillPaths exact = SkillPaths.from(List.of("SKILL.md", "abcdefghi"));
		assertEquals(EXACT_URI_BYTE_CEILING, project(root, exact).uris().get("abcdefghi")
				.toASCIIString().length());
		assertInvalid(() -> project(root, SkillPaths.from(List.of("SKILL.md", "abcdefghij"))));
	}

	@Test
	void summedProjectionBudgetCountsAllUrisAndIsInclusive() {
		SkillPaths paths = SkillPaths.from(List.of("SKILL.md", "a", "é"));
		long expectedBytes = ROOT.toASCIIString().length()
				+ "skill://example/a".length() + "skill://example/%C3%A9".length();
		assertEquals(3, SkillResourceUris.from(ROOT, "example", paths, expectedBytes).uris().size());
		assertInvalid(() -> SkillResourceUris.from(ROOT, "example", paths, expectedBytes - 1));
		assertSame(ROOT, SkillResourceUris.from(ROOT, "example", ROOT_ONLY,
				ROOT.toASCIIString().length()).uris().get("SKILL.md"));
		assertInvalid(() -> SkillResourceUris.from(ROOT, "example", ROOT_ONLY,
				ROOT.toASCIIString().length() - 1));
	}

	@Test
	void rejectsNonpositiveProjectionBudgets() {
		for (long budget : new long[]{0, -1, Long.MIN_VALUE})
			assertInvalid(() -> SkillResourceUris.from(ROOT, "example", ROOT_ONLY, budget));
	}

	@Test
	void longRootAndManyPathsAreRejectedByExplicitProjectionBudget() {
		List<String> files = new ArrayList<>();
		files.add("SKILL.md");
		for (int index = 1; index < 512; ++index) files.add("file-" + index);
		SkillPaths paths = SkillPaths.from(files);
		URI root = rootOfLength(200_000);
		assertInvalid(() -> SkillResourceUris.from(root, "example", paths, 524_288));
	}

	@Test
	void malformedInputDiagnosticsExcludeAuthoredUrisAndNames() {
		IllegalArgumentException first = assertInvalidRoot("skill://private-uri-canary/SKILL.md");
		IllegalArgumentException second = assertInvalidRoot("skill://other-uri-canary/SKILL.md");
		assertEquals(first.getMessage(), second.getMessage());
		assertFalse(first.getMessage().contains("private-uri-canary"));
		assertFalse(second.getMessage().contains("other-uri-canary"));
		IllegalArgumentException nameFailure = assertInvalid(() -> SkillResourceUris.from(ROOT,
				"private-name-canary", ROOT_ONLY, TEST_PROJECTION_BYTES));
		assertFalse(nameFailure.getMessage().contains("private-name-canary"));
		assertEquals("SkillResourceUris[redacted]", project(ROOT, ROOT_ONLY).toString());
	}

	@Test
	void nullUriNameAndPathsHaveFixedNullFailures() {
		assertNullFailure(() -> SkillResourceUris.from(null, "example", ROOT_ONLY, TEST_PROJECTION_BYTES));
		assertNullFailure(() -> SkillResourceUris.from(ROOT, null, ROOT_ONLY, TEST_PROJECTION_BYTES));
		assertNullFailure(() -> SkillResourceUris.from(ROOT, "example", null, TEST_PROJECTION_BYTES));
	}

	private static SkillResourceUris project(URI root, SkillPaths paths) {
		return SkillResourceUris.from(root, "example", paths, TEST_PROJECTION_BYTES);
	}

	private static URI rootOfLength(int length) {
		String prefix = "skill:/", suffix = "/example/SKILL.md";
		return URI.create(prefix + "a".repeat(length - prefix.length() - suffix.length()) + suffix);
	}

	private static IllegalArgumentException assertInvalidRoot(String raw) {
		URI root = URI.create(raw);
		return assertInvalid(() -> project(root, ROOT_ONLY));
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
