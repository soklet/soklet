/*
 * Copyright 2022-2026 Revetware LLC.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.soklet.internal.mcp.skills;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * Private, construction-time URI projection. No resource routing, file content,
 * catalog ownership, or shared-memory/reclamation policy is introduced here.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
final class SkillResourceUris {
	// Matches the existing exact-resource ceiling in McpNormalizedResourceDescriptor.
	static final int MAXIMUM_URI_ASCII_BYTES = 1_048_576;
	private static final char[] HEX = "0123456789ABCDEF".toCharArray();
	private final Map<String, URI> uris;

	private SkillResourceUris(Map<String, URI> uris) {
		this.uris = Collections.unmodifiableMap(uris);
	}

	/**
	 * The name must already have passed bundle metadata validation. The explicit
	 * byte budget bounds this projection's URI text, not JVM heap or coexisting
	 * projections. Preflight all output lengths before constructing output URIs.
	 */
	static SkillResourceUris from(URI rootUri, String validatedSkillName, SkillPaths paths,
			long maximumTotalUriBytes) {
		requireNonNull(rootUri, "A Skills root URI is required.");
		requireNonNull(validatedSkillName, "A validated Skills name is required.");
		requireNonNull(paths, "Skills logical paths are required.");
		if (maximumTotalUriBytes <= 0)
			throw new IllegalArgumentException("The Skills URI projection budget must be positive.");
		String root = validateRoot(rootUri, validatedSkillName);
		int prefixLength = root.length() - SkillPaths.ROOT.length();
		long remaining = maximumTotalUriBytes;
		for (String path : paths.paths()) {
			int length = path.equals(SkillPaths.ROOT) ? root.length() : prefixLength + encodedLength(path);
			if (length > MAXIMUM_URI_ASCII_BYTES) throw invalid();
			if (length > remaining)
				throw new IllegalArgumentException("The Skills URI projection budget was exceeded.");
			remaining -= length;
		}

		String prefix = root.substring(0, prefixLength);
		Map<String, URI> projected = new LinkedHashMap<>();
		HashSet<URI> identities = new HashSet<>();
		for (String path : paths.paths()) {
			URI uri = path.equals(SkillPaths.ROOT) ? rootUri : derive(prefix, path);
			// Use the exact router identity, never string/case-folded identity.
			if (!identities.add(uri)) throw invalid();
			projected.put(path, uri);
		}
		return new SkillResourceUris(projected);
	}

	Map<String, URI> uris() { return this.uris; }

	private static String validateRoot(URI uri, String name) {
		String root = uri.toString();
		if (root.length() > MAXIMUM_URI_ASCII_BYTES || !uri.isAbsolute() || uri.isOpaque()
				|| uri.getRawQuery() != null || uri.getRawFragment() != null) throw invalid();
		for (int offset = 0; offset < root.length(); offset++)
			if (root.charAt(offset) > 0x7F || Character.isISOControl(root.charAt(offset))) throw invalid();
		String rawPath = uri.getRawPath();
		if (rawPath == null || !rawPath.endsWith("/" + SkillPaths.ROOT) || !uri.normalize().equals(uri)) throw invalid();
		// Encoded separators must not introduce a second interpretation of hierarchy.
		for (int offset = 0; offset < rawPath.length(); offset++) {
			if (rawPath.charAt(offset) == '%') {
				int value = octet(rawPath, offset);
				if (value == '/' || value == '\\') throw invalid();
				offset += 2;
			}
		}
		String path = decode(rawPath);
		int start = 1;
		for (int offset = 1; offset <= path.length(); offset++) {
			if (offset == path.length() || path.charAt(offset) == '/') {
				int length = offset - start;
				if (length == 0 || length == 1 && path.charAt(start) == '.'
						|| length == 2 && path.charAt(start) == '.' && path.charAt(start + 1) == '.') throw invalid();
				start = offset + 1;
			}
		}
		int directoryEnd = path.length() - SkillPaths.ROOT.length() - 1;
		String identity;
		if (directoryEnd == 0) {
			String authority = uri.getRawAuthority();
			if (authority == null) throw invalid();
			identity = decode(authority);
		} else {
			identity = path.substring(path.lastIndexOf('/', directoryEnd - 1) + 1, directoryEnd);
		}
		if (name.isEmpty() || !identity.equals(name)) throw invalid();
		return root;
	}

	/** URI component decoding is strict UTF-8, with no form-URL '+' substitution. */
	private static String decode(String raw) {
		byte[] bytes = new byte[raw.length()];
		int length = 0;
		for (int offset = 0; offset < raw.length(); offset++) {
			char c = raw.charAt(offset);
			if (c == '%') {
				bytes[length++] = (byte) octet(raw, offset);
				offset += 2;
			} else bytes[length++] = (byte) c;
		}
		try {
			String value = StandardCharsets.UTF_8.newDecoder()
					.onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT)
					.decode(ByteBuffer.wrap(bytes, 0, length)).toString();
			for (int offset = 0; offset < value.length(); offset++)
				if (value.charAt(offset) == '\\' || Character.isISOControl(value.charAt(offset))) throw invalid();
			return value;
		} catch (CharacterCodingException ignored) {
			throw invalid();
		}
	}

	private static int octet(String raw, int offset) {
		// java.net.URI has already checked percent-triplet syntax.
		return Character.digit(raw.charAt(offset + 1), 16) * 16 + Character.digit(raw.charAt(offset + 2), 16);
	}

	private static int encodedLength(String path) {
		int length = 0;
		for (int offset = 0; offset < path.length(); offset++) {
			char c = path.charAt(offset);
			if (c == '/' || unreserved(c)) length++;
			else if (Character.isHighSurrogate(c)) { length += 12; offset++; }
			else length += c < 0x80 ? 3 : c < 0x800 ? 6 : 9;
		}
		return length;
	}

	private static URI derive(String prefix, String path) {
		StringBuilder value = new StringBuilder(prefix.length() + encodedLength(path));
		value.append(prefix);
		for (byte raw : path.getBytes(StandardCharsets.UTF_8)) {
			int octet = Byte.toUnsignedInt(raw);
			if (octet == '/' || unreserved(octet)) value.append((char) octet);
			else value.append('%').append(HEX[octet >>> 4]).append(HEX[octet & 15]);
		}
		try {
			URI uri = URI.create(value.toString());
			if (!uri.isAbsolute() || uri.isOpaque() || !uri.normalize().equals(uri)
					|| !uri.toString().startsWith(prefix)) throw invalid();
			return uri;
		} catch (IllegalArgumentException ignored) {
			throw invalid();
		}
	}

	private static boolean unreserved(int c) {
		return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9'
				|| c == '-' || c == '.' || c == '_' || c == '~';
	}

	private static IllegalArgumentException invalid() {
		return new IllegalArgumentException("Invalid Skills resource URI.");
	}

	@Override
	public String toString() { return "SkillResourceUris[redacted]"; }
}
