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

import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.ThreadSafe;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

import static java.util.Objects.requireNonNull;

/**
 * Owned, bounded UTF-8 source and byte-exact Skills frontmatter framing.
 *
 * <p>This class does not validate YAML syntax or select YAML scalar semantics.
 * An initial UTF-8 byte-order mark is omitted only from the decoded text view;
 * neither line endings nor the owned original bytes are rewritten.</p>
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class SkillSource {
	private final byte @NonNull [] originalBytes;
	@NonNull
	private final String text;
	private final int initialByteOffset;

	private SkillSource(byte @NonNull [] originalBytes, @NonNull String text,
			int initialByteOffset) {
		this.originalBytes = originalBytes;
		this.text = text;
		this.initialByteOffset = initialByteOffset;
	}

	/**
	 * Checks the byte ceiling before copying or decoding, then decodes only the
	 * owned snapshot. The entire file, including the Markdown body, must be UTF-8.
	 */
	@NonNull
	static SkillSource fromBytes(byte @NonNull [] bytes, int maximumBytes) {
		requireNonNull(bytes);

		if (maximumBytes <= 0)
			throw new IllegalArgumentException("maximumBytes must be positive.");

		if (bytes.length > maximumBytes)
			throw new SkillYamlException(SkillYamlException.Reason.INPUT_LIMIT, 1, 1);

		byte[] snapshot = bytes.clone();
		ByteBuffer input = ByteBuffer.wrap(snapshot);
		// A UTF-8 input byte can contribute at most one UTF-16 code unit.
		CharBuffer output = CharBuffer.allocate(snapshot.length);
		var decoder = StandardCharsets.UTF_8.newDecoder()
				.onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT);
		CoderResult result = decoder.decode(input, output, true);

		if (result.isError())
			throw invalidUtf8(output);

		result = decoder.flush(output);

		if (result.isError())
			throw invalidUtf8(output);

		output.flip();
		String text = output.toString();
		int initialByteOffset = 0;

		if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
			initialByteOffset = 3;
			text = text.substring(1);
		}

		return new SkillSource(snapshot, text, initialByteOffset);
	}

	byte @NonNull [] originalBytes() {
		return this.originalBytes.clone();
	}

	@NonNull
	String text() {
		return this.text;
	}

	/**
	 * Finds exact column-zero {@code ---} delimiter lines. CR, LF, and CRLF are
	 * recognized without normalization. Indented or suffixed delimiter lookalikes
	 * remain part of the header. The closing marker may end at EOF.
	 */
	@NonNull
	Frontmatter frontmatter() {
		Line opening = lineAt(this.initialByteOffset);

		if (!isDelimiter(opening) || opening.nextOffset() == opening.endOffset())
			throw new SkillYamlException(SkillYamlException.Reason.SYNTAX, 1, 1);

		int firstByteOffset = opening.nextOffset();
		int lineNumber = 2;
		int offset = firstByteOffset;

		while (offset < this.originalBytes.length) {
			Line line = lineAt(offset);

			if (isDelimiter(line))
				return new Frontmatter(new String(this.originalBytes,
						firstByteOffset, offset - firstByteOffset,
						StandardCharsets.UTF_8), 2, line.nextOffset());

			offset = line.nextOffset();

			if (line.nextOffset() > line.endOffset())
				lineNumber++;
		}

		throw new SkillYamlException(SkillYamlException.Reason.SYNTAX,
				lineNumber, 1);
	}

	@NonNull
	private Line lineAt(int startOffset) {
		int endOffset = startOffset;

		while (endOffset < this.originalBytes.length
				&& this.originalBytes[endOffset] != '\r'
				&& this.originalBytes[endOffset] != '\n')
			endOffset++;

		int nextOffset = endOffset;

		if (nextOffset < this.originalBytes.length) {
			byte lineBreak = this.originalBytes[nextOffset++];

			if (lineBreak == '\r' && nextOffset < this.originalBytes.length
					&& this.originalBytes[nextOffset] == '\n')
				nextOffset++;
		}

		return new Line(startOffset, endOffset, nextOffset);
	}

	private boolean isDelimiter(@NonNull Line line) {
		return line.endOffset() - line.startOffset() == 3
				&& this.originalBytes[line.startOffset()] == '-'
				&& this.originalBytes[line.startOffset() + 1] == '-'
				&& this.originalBytes[line.startOffset() + 2] == '-';
	}

	@NonNull
	private static SkillYamlException invalidUtf8(@NonNull CharBuffer decodedPrefix) {
		int line = 1;
		int column = 1;
		int length = decodedPrefix.position();
		int offset = length > 0 && decodedPrefix.get(0) == '\uFEFF' ? 1 : 0;

		while (offset < length) {
			char character = decodedPrefix.get(offset++);

			if (character == '\r') {
				if (offset < length && decodedPrefix.get(offset) == '\n')
					offset++;

				line++;
				column = 1;
			} else if (character == '\n') {
				line++;
				column = 1;
			} else {
				if (Character.isHighSurrogate(character) && offset < length
						&& Character.isLowSurrogate(decodedPrefix.get(offset)))
					offset++;

				column++;
			}
		}

		return new SkillYamlException(SkillYamlException.Reason.INVALID_UTF8,
				line, column);
	}

	/**
	 * Header text excludes both delimiters and includes its authored final line
	 * break. {@code bodyByteOffset} indexes the unchanged original byte snapshot
	 * immediately after the closing delimiter and its optional line break.
	 */
	record Frontmatter(@NonNull String text, int firstLine, int bodyByteOffset) {
		@Override
		public String toString() {
			return "Frontmatter[redacted]";
		}
	}

	private record Line(int startOffset, int endOffset, int nextOffset) {}
}
