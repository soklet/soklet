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
import com.soklet.internal.mcp.protocol.McpJsonValue;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * Immutable canonical contents of one already-owned bundle file. Classification
 * uses only its final filename and bytes, so enclosing and nested skills agree.
 * This retains no byte array and performs no filesystem access or MIME sniffing.
 *
 * <p>The supplied bytes must be an immutable owned snapshot. Scalar limits are
 * checked here; the URI-bound content and full response envelope must still be
 * checked by registration/runtime code before publication or delivery.</p>
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
final class SkillFileContents {
	private final String mimeType;
	private final boolean text;
	private final String value;

	private SkillFileContents(String mimeType, boolean text, String value) {
		this.mimeType = mimeType;
		this.text = text;
		this.value = value;
	}

	static SkillFileContents from(String path, byte[] bytes, McpJsonLimits limits) {
		requireNonNull(path, "A Skills logical path is required.");
		requireNonNull(bytes, "Skills file bytes are required.");
		requireNonNull(limits, "Skills JSON limits are required.");
		Mime mime = mime(path);
		if (mime.text()) {
			// Every valid UTF-8 sequence contributes at most three bytes per UTF-16
			// code unit. A serialized token needs at least as many characters as the
			// decoded string, and its UTF-8 scalar output needs two quote bytes.
			long maximumCharacters = Math.min(limits.maximumStringLengthInCharacters(),
					limits.maximumTokenLengthInCharacters());
			if (bytes.length > 3L * maximumCharacters
					|| bytes.length > (long) limits.maximumOutputBytes() - 2) throw invalid();
			String decoded = decode(bytes);
			if (decoded != null) {
				validateScalar(decoded, limits);
				return new SkillFileContents(mime.value(), true, decoded);
			}
		}

		long encodedLength = 4L * ((bytes.length + 2L) / 3L);
		if (encodedLength > limits.maximumStringLengthInCharacters()
				|| encodedLength > limits.maximumTokenLengthInCharacters()
				|| encodedLength + 2 > limits.maximumOutputBytes()) throw invalid();
		return new SkillFileContents(mime.value(), false, Base64.getEncoder().encodeToString(bytes));
	}

	McpJsonObject atUri(URI uri) {
		requireNonNull(uri, "A Skills resource URI is required.");
		Map<String, McpJsonValue> members = new LinkedHashMap<>();
		members.put("uri", new McpJsonString(uri.toString()));
		members.put("mimeType", new McpJsonString(this.mimeType));
		members.put(this.text ? "text" : "blob", new McpJsonString(this.value));
		return new McpJsonObject(members);
	}

	String mimeType() { return this.mimeType; }
	boolean isText() { return this.text; }
	String value() { return this.value; }

	/** Strict UTF-8 preserves BOMs and authored line endings; malformed text is binary. */
	private static String decode(byte[] bytes) {
		try {
			return StandardCharsets.UTF_8.newDecoder()
					.onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT)
					.decode(ByteBuffer.wrap(bytes)).toString();
		} catch (CharacterCodingException ignored) {
			return null;
		}
	}

	private static void validateScalar(String value, McpJsonLimits limits) {
		try {
			new McpJsonCodec(limits).toUtf8Bytes(new McpJsonString(value));
		} catch (IllegalArgumentException ignored) {
			// A valid text file that exceeds its selected representation's limits
			// is rejected, not silently changed to a different representation.
			throw invalid();
		}
	}

	private static Mime mime(String path) {
		String name = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
		int dot = name.lastIndexOf('.');
		String suffix = dot < 0 ? "" : name.substring(dot);
		return switch (suffix) {
			case ".md", ".markdown" -> new Mime("text/markdown", true);
			case ".json" -> new Mime("application/json", true);
			case ".html", ".htm" -> new Mime("text/html", true);
			case ".css" -> new Mime("text/css", true);
			case ".xml" -> new Mime("application/xml", true);
			case ".txt", ".yaml", ".yml", ".toml", ".csv", ".tsv", ".py", ".js",
					".ts", ".jsx", ".tsx", ".sh", ".bash", ".zsh", ".sql" -> new Mime("text/plain", true);
			case ".png" -> new Mime("image/png", false);
			case ".jpg", ".jpeg" -> new Mime("image/jpeg", false);
			case ".gif" -> new Mime("image/gif", false);
			case ".webp" -> new Mime("image/webp", false);
			case ".pdf" -> new Mime("application/pdf", false);
			case ".zip" -> new Mime("application/zip", false);
			default -> new Mime("application/octet-stream", false);
		};
	}

	private static IllegalArgumentException invalid() {
		return new IllegalArgumentException("The Skills file contents exceed the configured JSON profile.");
	}

	@Override
	public String toString() { return "SkillFileContents[redacted]"; }

	private record Mime(String value, boolean text) {}
}
