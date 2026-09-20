/*
 * Copyright 2022-2026 Revetware LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.soklet.internal.mcp.skills;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

class SkillSourceTests {
	@Test
	void snapshotsCallerBytesAndDoesNotExposeOwnedBytes() {
		byte[] bytes = utf8("---\nname: demo\n---\nBody café 🚀\n");
		byte[] expected = bytes.clone();
		SkillSource source = SkillSource.fromBytes(bytes, bytes.length);
		Arrays.fill(bytes, (byte) 'x');
		byte[] returned = source.originalBytes();
		Arrays.fill(returned, (byte) 'y');

		Assertions.assertArrayEquals(expected, source.originalBytes());
		Assertions.assertEquals(new String(expected, StandardCharsets.UTF_8), source.text());
		Assertions.assertEquals("name: demo\n", source.frontmatter().text());
	}

	@Test
	void byteLimitIsInclusiveAndCheckedBeforeMalformedUtf8() {
		byte[] bytes = utf8("é");
		Assertions.assertEquals("é", SkillSource.fromBytes(bytes, 2).text());
		assertReason(SkillYamlException.Reason.INPUT_LIMIT,
				Assertions.assertThrows(SkillYamlException.class,
						() -> SkillSource.fromBytes(bytes, 1)));
		assertReason(SkillYamlException.Reason.INPUT_LIMIT,
				Assertions.assertThrows(SkillYamlException.class,
						() -> SkillSource.fromBytes(new byte[]{(byte) 0xFF, (byte) 0xFF}, 1)));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> SkillSource.fromBytes(bytes, 0));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> SkillSource.fromBytes(bytes, -1));
		Assertions.assertThrows(NullPointerException.class,
				() -> SkillSource.fromBytes(null, 1));
	}

	@Test
	void decodesEmptySourceButRequiresFrontmatterWhenRequested() {
		SkillSource source = SkillSource.fromBytes(new byte[0], 1);
		Assertions.assertEquals("", source.text());
		assertSyntax(source, 1);
	}

	@Test
	void retainsOriginalLineEndingsAndExactBodyByteOffsets() {
		for (String lineBreak : List.of("\n", "\r\n", "\r")) {
			String header = "name: café" + lineBreak + "description: 🚀" + lineBreak;
			String prefix = "---" + lineBreak + header + "---" + lineBreak;
			String body = "# Markdown" + lineBreak + "Body";
			byte[] bytes = utf8(prefix + body);
			SkillSource source = SkillSource.fromBytes(bytes, bytes.length);
			SkillSource.Frontmatter frontmatter = source.frontmatter();

			Assertions.assertEquals(header, frontmatter.text());
			Assertions.assertEquals(2, frontmatter.firstLine());
			Assertions.assertEquals(utf8(prefix).length, frontmatter.bodyByteOffset());
			Assertions.assertEquals(body, new String(source.originalBytes(),
					frontmatter.bodyByteOffset(), bytes.length - frontmatter.bodyByteOffset(),
					StandardCharsets.UTF_8));
			Assertions.assertArrayEquals(bytes, source.originalBytes());
		}
	}

	@Test
	void omitsOnlyTheInitialBomFromDecodedView() {
		String document = "\uFEFF---\r\nname: demo\r\ndescription: \uFEFFinside\r\n---\r\n\uFEFFbody";
		byte[] bytes = utf8(document);
		SkillSource source = SkillSource.fromBytes(bytes, bytes.length);
		SkillSource.Frontmatter frontmatter = source.frontmatter();

		Assertions.assertEquals(document.substring(1), source.text());
		Assertions.assertEquals("name: demo\r\ndescription: \uFEFFinside\r\n", frontmatter.text());
		Assertions.assertEquals(utf8(document.substring(0, document.indexOf("\uFEFFbody"))).length,
				frontmatter.bodyByteOffset());
		Assertions.assertArrayEquals(bytes, source.originalBytes());
		assertSyntax(source("\uFEFF\uFEFF---\n---\n"), 1);
	}

	@Test
	void framingAcceptsEmptyHeaderAndClosingDelimiterAtEof() {
		for (String document : List.of("---\n---", "---\n---\n", "---\r\n---\r\n")) {
			SkillSource source = source(document);
			SkillSource.Frontmatter frontmatter = source.frontmatter();
			Assertions.assertEquals("", frontmatter.text());
			Assertions.assertEquals(utf8(document).length, frontmatter.bodyByteOffset());
		}
	}

	@Test
	void onlyExactColumnZeroDelimiterLinesTerminateHeader() {
		String header = "description: |\n  ---\n  ----\n---suffix\n--- \n--- # comment\n\t---\n";
		SkillSource source = source("---\n" + header + "---\nbody\n---\nmore");

		Assertions.assertEquals(header, source.frontmatter().text());
		Assertions.assertEquals(utf8("---\n" + header + "---\n").length,
				source.frontmatter().bodyByteOffset());
	}

	@Test
	void requiresOpeningMarkerAtStartAndASeparateClosingMarker() {
		for (String document : List.of("name: demo\n---\n", "\n---\n---\n",
				" ---\n---\n", "--- \n---\n", "---# comment\n---\n", "---"))
			assertSyntax(source(document), 1);

		assertSyntax(source("---\n"), 2);
		assertSyntax(source("---\nname: demo"), 2);
		assertSyntax(source("---\r\nname: demo\r\n"), 3);
		assertSyntax(source("---\rname: demo\rdescription: value\r"), 4);
	}

	@Test
	void framingDoesNotValidateYamlOrInterpretMarkdown() {
		String document = "---\nnot: [valid YAML\n---\nMarkdown\u0000\u0001\n";
		SkillSource source = source(document);
		Assertions.assertEquals("not: [valid YAML\n", source.frontmatter().text());
		Assertions.assertEquals(document, source.text());
	}

	@Test
	void sourceAndFrontmatterDiagnosticRepresentationsExcludeAuthoredContent() {
		SkillSource source = source("---\nname: private-header-canary\n---\nprivate-body-canary");
		Assertions.assertEquals("name: private-header-canary\n", source.frontmatter().text());
		for (Object value : List.of(source, source.frontmatter())) {
			Assertions.assertFalse(value.toString().contains("private-header-canary"));
			Assertions.assertFalse(value.toString().contains("private-body-canary"));
		}
	}

	@Test
	void rejectsMalformedUtf8IncludingTheBodyWithoutSubstitution() {
		for (byte[] invalid : List.of(
				new byte[]{(byte) 0x80},
				new byte[]{(byte) 0xC0, (byte) 0xAF},
				new byte[]{(byte) 0xC2},
				new byte[]{(byte) 0xE2, (byte) 0x82},
				new byte[]{(byte) 0xED, (byte) 0xA0, (byte) 0x80},
				new byte[]{(byte) 0xF4, (byte) 0x90, (byte) 0x80, (byte) 0x80},
				new byte[]{(byte) 0xFF})) {
			assertReason(SkillYamlException.Reason.INVALID_UTF8,
					Assertions.assertThrows(SkillYamlException.class,
							() -> SkillSource.fromBytes(invalid, invalid.length)));

			byte[] prefix = utf8("---\nname: demo\n---\nbody ");
			byte[] document = Arrays.copyOf(prefix, prefix.length + invalid.length);
			System.arraycopy(invalid, 0, document, prefix.length, invalid.length);
			SkillYamlException exception = Assertions.assertThrows(SkillYamlException.class,
					() -> SkillSource.fromBytes(document, document.length));
			assertReason(SkillYamlException.Reason.INVALID_UTF8, exception);
			Assertions.assertEquals(4, exception.line());
			Assertions.assertEquals(6, exception.column());
		}
	}

	@Test
	void malformedUtf8CoordinatesCountCodePointsAndCrlfOnce() {
		for (String lineBreak : List.of("\n", "\r\n", "\r")) {
			byte[] prefix = utf8("\uFEFFone" + lineBreak + "café 🚀");
			byte[] bytes = Arrays.copyOf(prefix, prefix.length + 1);
			bytes[bytes.length - 1] = (byte) 0xFF;
			SkillYamlException exception = Assertions.assertThrows(SkillYamlException.class,
					() -> SkillSource.fromBytes(bytes, bytes.length));
			assertReason(SkillYamlException.Reason.INVALID_UTF8, exception);
			Assertions.assertEquals(2, exception.line());
			Assertions.assertEquals(7, exception.column());
			Assertions.assertFalse(exception.getMessage().contains("café"));
		}
	}

	private static void assertSyntax(SkillSource source, int line) {
		SkillYamlException exception = Assertions.assertThrows(SkillYamlException.class,
				source::frontmatter);
		assertReason(SkillYamlException.Reason.SYNTAX, exception);
		Assertions.assertEquals(line, exception.line());
		Assertions.assertEquals(1, exception.column());
	}

	private static void assertReason(SkillYamlException.Reason expected,
			SkillYamlException exception) {
		Assertions.assertEquals(expected, exception.reason());
	}

	private static SkillSource source(String text) {
		byte[] bytes = utf8(text);
		return SkillSource.fromBytes(bytes, Math.max(1, bytes.length));
	}

	private static byte[] utf8(String text) {
		return text.getBytes(StandardCharsets.UTF_8);
	}
}
