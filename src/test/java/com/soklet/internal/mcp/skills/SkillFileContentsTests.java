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

import com.soklet.internal.mcp.protocol.McpJsonCodec;
import com.soklet.internal.mcp.protocol.McpJsonLimits;
import com.soklet.internal.mcp.protocol.McpJsonObject;
import com.soklet.internal.mcp.protocol.McpJsonString;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SkillFileContentsTests {
	private static final McpJsonLimits LIMITS = McpJsonLimits.productionDefaults();
	private static final URI URI_VALUE = URI.create("skill://parent/child/SKILL.md");

	@Test
	void textRoundTripsOriginalBomLineEndingsUnicodeAndDigest() throws Exception {
		for (String value : List.of("", "\uFEFF---\r\nname: skill\r\ndescription: text\r\n---\r\n",
				"a\rb\nc\r\nd", "café 技能 🙂\n", "\u0000\b\f\t\\\"")) {
			byte[] bytes = utf8(value);
			SkillFileContents contents = SkillFileContents.from("SKILL.md", bytes, LIMITS);
			assertTrue(contents.isText());
			assertEquals("text/markdown", contents.mimeType());
			assertEquals(value, contents.value());
			byte[] restored = utf8(contents.value());
			assertArrayEquals(bytes, restored);
			assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(bytes),
					MessageDigest.getInstance("SHA-256").digest(restored));
		}
	}

	@Test
	void recognizesOnlyTheDocumentedTextSuffixTableCaseInsensitively() {
		Map<String, List<String>> suffixes = Map.of(
				"text/markdown", List.of("md", "markdown"),
				"application/json", List.of("json"), "text/html", List.of("html", "htm"),
				"text/css", List.of("css"), "application/xml", List.of("xml"),
				"text/plain", List.of("txt", "yaml", "yml", "toml", "csv", "tsv", "py", "js",
						"ts", "jsx", "tsx", "sh", "bash", "zsh", "sql"));
		for (Map.Entry<String, List<String>> entry : suffixes.entrySet())
			for (String suffix : entry.getValue())
				for (String spelling : List.of(suffix, suffix.toUpperCase(java.util.Locale.ROOT))) {
					SkillFileContents contents = SkillFileContents.from("docs/file." + spelling, utf8("text"), LIMITS);
					assertTrue(contents.isText(), spelling);
					assertEquals(entry.getKey(), contents.mimeType(), spelling);
				}
	}

	@Test
	void binaryAndUnknownTypesRemainBlobEvenForValidUtf8AndEmptyBytes() {
		Map<String, String> suffixes = Map.ofEntries(Map.entry("png", "image/png"),
				Map.entry("jpg", "image/jpeg"), Map.entry("jpeg", "image/jpeg"),
				Map.entry("gif", "image/gif"), Map.entry("webp", "image/webp"),
				Map.entry("pdf", "application/pdf"), Map.entry("zip", "application/zip"),
				Map.entry("unknown", "application/octet-stream"));
		for (Map.Entry<String, String> entry : suffixes.entrySet())
			for (byte[] bytes : List.of(new byte[0], utf8("valid text\n"))) {
				SkillFileContents contents = SkillFileContents.from("file." + entry.getKey(), bytes, LIMITS);
				assertFalse(contents.isText());
				assertEquals(entry.getValue(), contents.mimeType());
				assertArrayEquals(bytes, Base64.getDecoder().decode(contents.value()));
			}
		for (String path : List.of("LICENSE", "file.", "document.md.bin", "parent.md/file", "file.svg")) {
			SkillFileContents contents = SkillFileContents.from(path, utf8("text"), LIMITS);
			assertFalse(contents.isText());
			assertEquals("application/octet-stream", contents.mimeType());
		}
	}

	@Test
	void malformedUtf8FallsBackToBlobWithoutChangingRecognizedMimeOrBytes() {
		for (byte[] bytes : List.of(new byte[]{(byte) 0xff}, new byte[]{(byte) 0xc0, (byte) 0xaf},
				new byte[]{(byte) 0xed, (byte) 0xa0, (byte) 0x80}, new byte[]{(byte) 0xe2, (byte) 0x82},
				new byte[]{'a', (byte) 0x80, 'b'})) {
			SkillFileContents contents = SkillFileContents.from("file.json", bytes, LIMITS);
			assertFalse(contents.isText());
			assertEquals("application/json", contents.mimeType());
			assertArrayEquals(bytes, Base64.getDecoder().decode(contents.value()));
			assertEquals(Base64.getEncoder().encodeToString(bytes), contents.value());
		}
	}

	@Test
	void parentAndNestedSkillsUseIdenticalRepresentationsForTheSameBasename() {
		byte[] bytes = utf8("\uFEFF---\r\nname: child\r\ndescription: text\r\n---\r\n");
		SkillFileContents parent = SkillFileContents.from("child/SKILL.md", bytes, LIMITS);
		SkillFileContents child = SkillFileContents.from("SKILL.md", bytes, LIMITS);
		assertEquals(parent.mimeType(), child.mimeType());
		assertEquals(parent.isText(), child.isText());
		assertEquals(parent.value(), child.value());
		assertEquals(parent.atUri(URI_VALUE), child.atUri(URI_VALUE));
	}

	@Test
	void projectionHasFixedOrderAndExactlyOneContentFieldAndRetainsNoMutableBytes() {
		for (String path : List.of("file.md", "file.bin")) {
			byte[] bytes = utf8("private-content-canary");
			SkillFileContents contents = SkillFileContents.from(path, bytes, LIMITS);
			String originalValue = contents.value();
			Arrays.fill(bytes, (byte) 'x');
			assertEquals(originalValue, contents.value());
			McpJsonObject projected = contents.atUri(URI_VALUE);
			String field = contents.isText() ? "text" : "blob";
			assertEquals(List.of("uri", "mimeType", field), new ArrayList<>(projected.members().keySet()));
			assertEquals(new McpJsonString(URI_VALUE.toString()), projected.members().get("uri"));
			assertEquals(new McpJsonString(contents.mimeType()), projected.members().get("mimeType"));
			assertEquals(new McpJsonString(originalValue), projected.members().get(field));
			assertThrows(UnsupportedOperationException.class, () -> projected.members().clear());
			new McpJsonCodec(LIMITS).toUtf8Bytes(projected);
		}
	}

	@Test
	void textDecodedLengthAndRawBytePreflightRespectUtf16RatherThanCodepointCounts() {
		McpJsonLimits limits = limits(4, 4, 100);
		for (String value : List.of("aaaa", "éééé", "技技技技", "🙂🙂"))
			assertEquals(value, SkillFileContents.from("file.txt", utf8(value), limits).value());
		for (String value : List.of("aaaaa", "ééééé", "技技技技技", "🙂🙂a"))
			assertInvalid(() -> SkillFileContents.from("file.txt", utf8(value), limits));
	}

	@Test
	void textEscapedTokenLimitRejectsInsteadOfFallingBackToSmallerBlob() {
		McpJsonLimits limits = limits(20, 6, 100);
		for (String value : List.of("\n\n\n", "\"\"\"", "\\\\\\", "\u0000"))
			assertEquals(value, SkillFileContents.from("file.txt", utf8(value), limits).value());
		for (String value : List.of("\n\n\n\n", "\"\"\"\"", "\\\\\\\\", "\u0000a"))
			assertInvalid(() -> SkillFileContents.from("file.txt", utf8(value), limits));
		// The two-byte input would fit as four base64 characters; text selection
		// is canonical and does not change when its escaped token is too large.
		assertTrue(Base64.getEncoder().encodeToString(utf8("\u0000a")).length() < 6);
	}

	@Test
	void scalarOutputByteLimitIncludesQuotesAndUtf8OrEscapeExpansion() {
		McpJsonLimits limits = limits(100, 100, 8);
		for (String value : List.of("abcdef", "技技", "\u0000"))
			assertEquals(value, SkillFileContents.from("file.txt", utf8(value), limits).value());
		for (String value : List.of("abcdefg", "技技a", "\u0000a"))
			assertInvalid(() -> SkillFileContents.from("file.txt", utf8(value), limits));
		assertEquals(4, SkillFileContents.from("file.bin", new byte[3], limits).value().length());
		assertInvalid(() -> SkillFileContents.from("file.bin", new byte[4], limits));
	}

	@Test
	void canonicalBase64HasPaddingNoLinesAndChecksStringTokenAndProductionBoundary() {
		for (byte[] bytes : List.of(new byte[]{0}, new byte[]{0, 1}, new byte[]{0, 1, 2}, new byte[80])) {
			SkillFileContents contents = SkillFileContents.from("file.bin", bytes, LIMITS);
			assertEquals(Base64.getEncoder().encodeToString(bytes), contents.value());
			assertFalse(contents.value().contains("\n"));
			assertFalse(contents.value().contains("\r"));
			assertArrayEquals(bytes, Base64.getDecoder().decode(contents.value()));
		}
		assertEquals(1_048_576, SkillFileContents.from("file.bin", new byte[786_432], LIMITS).value().length());
		assertInvalid(() -> SkillFileContents.from("file.bin", new byte[786_433], LIMITS));
		assertEquals(4, SkillFileContents.from("file.bin", new byte[3], limits(4, 8, 100)).value().length());
		assertInvalid(() -> SkillFileContents.from("file.bin", new byte[4], limits(4, 8, 100)));
		assertInvalid(() -> SkillFileContents.from("file.bin", new byte[4], limits(8, 4, 100)));
	}

	@Test
	void diagnosticsAndToStringAreFixedAndRedacted() {
		IllegalArgumentException first = assertInvalid(() -> SkillFileContents.from("private-name-canary.txt",
				utf8("private-content-canary"), limits(1, 1, 100)));
		IllegalArgumentException second = assertInvalid(() -> SkillFileContents.from("different-name.bin",
				new byte[4], limits(4, 4, 100)));
		assertEquals(first.getMessage(), second.getMessage());
		assertEquals("SkillFileContents[redacted]",
				SkillFileContents.from("private-name-canary.txt", utf8("private-content-canary"), LIMITS).toString());
	}

	@Test
	void nullInputsAndUriHaveFixedNullFailures() {
		assertNullFailure(() -> SkillFileContents.from(null, new byte[0], LIMITS));
		assertNullFailure(() -> SkillFileContents.from("file.txt", null, LIMITS));
		assertNullFailure(() -> SkillFileContents.from("file.txt", new byte[0], null));
		assertNullFailure(() -> SkillFileContents.from("file.txt", new byte[0], LIMITS).atUri(null));
	}

	private static byte[] utf8(String value) { return value.getBytes(StandardCharsets.UTF_8); }

	private static McpJsonLimits limits(int stringCharacters, int tokenCharacters, int outputBytes) {
		return new McpJsonLimits(4 * 1_024 * 1_024, 128, tokenCharacters, stringCharacters,
				1_024, 10_000, 100_000, outputBytes);
	}

	private static IllegalArgumentException assertInvalid(Executable executable) {
		IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, executable);
		assertEquals(IllegalArgumentException.class, failure.getClass());
		assertEquals("The Skills file contents exceed the configured JSON profile.", failure.getMessage());
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
