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

package com.soklet.internal.mcp.schema;

import java.net.URI;
import java.util.Locale;

import static java.util.Objects.requireNonNull;

/**
 * Raw-component RFC 3986 resolution for both hierarchical and Java-opaque
 * identifiers such as URNs.
 */
final class McpSchemaUriResolver {
	URI canonicalAbsolute(URI uri) {
		requireNonNull(uri);
		RawUri rawUri = RawUri.parse(uri.toASCIIString());

		if (rawUri.scheme() == null)
			throw new IllegalArgumentException("An absolute URI is required.");

		return URI.create(normalize(rawUri).render());
	}

	URI resolve(URI absoluteBaseUri, URI reference) {
		requireNonNull(absoluteBaseUri);
		requireNonNull(reference);
		RawUri base = RawUri.parse(absoluteBaseUri.toASCIIString());
		RawUri relative = RawUri.parse(reference.toASCIIString());

		if (base.scheme() == null)
			throw new IllegalArgumentException("An absolute base URI is required.");

		RawUri target;
		if (relative.scheme() != null) {
			target = new RawUri(relative.scheme(), relative.authority(),
					removeDotSegments(relative.path()), relative.query(),
					relative.fragment());
		} else if (relative.authority() != null) {
			target = new RawUri(base.scheme(), relative.authority(),
					removeDotSegments(relative.path()), relative.query(),
					relative.fragment());
		} else if (relative.path().isEmpty()) {
			target = new RawUri(base.scheme(), base.authority(), base.path(),
					relative.query() == null ? base.query() : relative.query(),
					relative.fragment());
		} else {
			String path = relative.path().startsWith("/") ? relative.path()
					: merge(base, relative.path());
			target = new RawUri(base.scheme(), base.authority(),
					removeDotSegments(path), relative.query(), relative.fragment());
		}

		return URI.create(normalize(target).render());
	}

	URI withoutFragment(URI uri) {
		requireNonNull(uri);
		RawUri rawUri = RawUri.parse(uri.toASCIIString());
		return URI.create(new RawUri(rawUri.scheme(), rawUri.authority(),
				rawUri.path(), rawUri.query(), null).render());
	}

	String rawFragment(URI uri) {
		return RawUri.parse(requireNonNull(uri).toASCIIString()).fragment();
	}

	private static RawUri normalize(RawUri rawUri) {
		return new RawUri(rawUri.scheme().toLowerCase(Locale.ROOT),
				normalizeAuthority(rawUri.authority()),
				removeDotSegments(normalizePercentEncoding(rawUri.path())),
				rawUri.query() == null ? null
						: normalizePercentEncoding(rawUri.query()),
				rawUri.fragment() == null ? null
						: normalizePercentEncoding(rawUri.fragment()));
	}

	private static String merge(RawUri base, String relativePath) {
		if (base.authority() != null && base.path().isEmpty())
			return "/" + relativePath;

		int lastSlash = base.path().lastIndexOf('/');
		return lastSlash < 0 ? relativePath
				: base.path().substring(0, lastSlash + 1) + relativePath;
	}

	private static String removeDotSegments(String path) {
		StringBuilder input = new StringBuilder(path);
		StringBuilder output = new StringBuilder(path.length());

		while (!input.isEmpty()) {
			if (startsWith(input, "../")) {
				input.delete(0, 3);
			} else if (startsWith(input, "./")) {
				input.delete(0, 2);
			} else if (startsWith(input, "/./")) {
				input.delete(0, 2);
			} else if (input.toString().equals("/.")) {
				input.replace(0, input.length(), "/");
			} else if (startsWith(input, "/../")) {
				input.delete(0, 3);
				removeLastSegment(output);
			} else if (input.toString().equals("/..")) {
				input.replace(0, input.length(), "/");
				removeLastSegment(output);
			} else if (input.toString().equals(".")
					|| input.toString().equals("..")) {
				input.setLength(0);
			} else {
				moveFirstSegment(input, output);
			}
		}

		return output.toString();
	}

	private static boolean startsWith(StringBuilder value, String prefix) {
		if (value.length() < prefix.length())
			return false;

		for (int index = 0; index < prefix.length(); ++index) {
			if (value.charAt(index) != prefix.charAt(index))
				return false;
		}

		return true;
	}

	private static void moveFirstSegment(StringBuilder input,
			StringBuilder output) {
		int segmentEnd;
		if (input.charAt(0) == '/') {
			int nextSlash = input.indexOf("/", 1);
			segmentEnd = nextSlash < 0 ? input.length() : nextSlash;
		} else {
			int nextSlash = input.indexOf("/");
			segmentEnd = nextSlash < 0 ? input.length() : nextSlash;
		}

		output.append(input, 0, segmentEnd);
		input.delete(0, segmentEnd);
	}

	private static void removeLastSegment(StringBuilder output) {
		int lastSlash = output.lastIndexOf("/");
		output.setLength(lastSlash < 0 ? 0 : lastSlash);
	}

	private static String normalizeAuthority(String authority) {
		if (authority == null)
			return null;

		String normalized = normalizePercentEncoding(authority);
		int userInfoEnd = normalized.lastIndexOf('@');
		String prefix = userInfoEnd < 0 ? "" : normalized.substring(0, userInfoEnd + 1);
		String hostAndPort = normalized.substring(userInfoEnd + 1);

		if (hostAndPort.startsWith("[")) {
			int bracketEnd = hostAndPort.indexOf(']');
			if (bracketEnd < 0)
				return normalized;
			return prefix + hostAndPort.substring(0, bracketEnd + 1).toLowerCase(Locale.ROOT)
					+ hostAndPort.substring(bracketEnd + 1);
		}

		int colon = hostAndPort.lastIndexOf(':');
		String host = colon < 0 ? hostAndPort : hostAndPort.substring(0, colon);
		String port = colon < 0 ? "" : hostAndPort.substring(colon);
		return prefix + host.toLowerCase(Locale.ROOT) + port;
	}

	private static String normalizePercentEncoding(String value) {
		StringBuilder normalized = new StringBuilder(value.length());

		for (int index = 0; index < value.length(); ++index) {
			char character = value.charAt(index);

			if (character != '%') {
				normalized.append(character);
				continue;
			}

			int high = Character.digit(value.charAt(index + 1), 16);
			int low = Character.digit(value.charAt(index + 2), 16);
			int decoded = high * 16 + low;

			if (asciiUnreserved(decoded)) {
				normalized.append((char) decoded);
			} else {
				normalized.append('%')
						.append(Character.toUpperCase(value.charAt(index + 1)))
						.append(Character.toUpperCase(value.charAt(index + 2)));
			}

			index += 2;
		}

		return normalized.toString();
	}

	private static boolean asciiUnreserved(int value) {
		return value >= 'A' && value <= 'Z'
				|| value >= 'a' && value <= 'z'
				|| value >= '0' && value <= '9'
				|| value == '-' || value == '.' || value == '_' || value == '~';
	}

	private record RawUri(String scheme, String authority, String path,
			String query, String fragment) {
		private RawUri {
			requireNonNull(path);
		}

		private static RawUri parse(String value) {
			String remaining = requireNonNull(value);
			String fragment = null;
			int fragmentStart = remaining.indexOf('#');
			if (fragmentStart >= 0) {
				fragment = remaining.substring(fragmentStart + 1);
				remaining = remaining.substring(0, fragmentStart);
			}

			String scheme = null;
			int schemeEnd = schemeEnd(remaining);
			if (schemeEnd >= 0) {
				scheme = remaining.substring(0, schemeEnd);
				remaining = remaining.substring(schemeEnd + 1);
			}

			String query = null;
			int queryStart = remaining.indexOf('?');
			if (queryStart >= 0) {
				query = remaining.substring(queryStart + 1);
				remaining = remaining.substring(0, queryStart);
			}

			String authority = null;
			if (remaining.startsWith("//")) {
				int pathStart = remaining.indexOf('/', 2);
				if (pathStart < 0) {
					authority = remaining.substring(2);
					remaining = "";
				} else {
					authority = remaining.substring(2, pathStart);
					remaining = remaining.substring(pathStart);
				}
			}

			return new RawUri(scheme, authority, remaining, query, fragment);
		}

		private String render() {
			StringBuilder rendered = new StringBuilder();
			if (scheme != null)
				rendered.append(scheme).append(':');
			if (authority != null)
				rendered.append("//").append(authority);
			rendered.append(path);
			if (query != null)
				rendered.append('?').append(query);
			if (fragment != null)
				rendered.append('#').append(fragment);
			return rendered.toString();
		}

		private static int schemeEnd(String value) {
			for (int index = 0; index < value.length(); ++index) {
				char character = value.charAt(index);
				if (character == ':')
					return index;
				if (character == '/' || character == '?')
					return -1;
			}
			return -1;
		}
	}
}
