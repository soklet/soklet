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

package com.soklet;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.ThreadSafe;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static java.util.Objects.requireNonNull;

/**
 * Injection-safe, non-resolving validation shared by MCP Apps metadata.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class McpAppMetadataValidation {
	private McpAppMetadataValidation() {
	}

	@NonNull
	static URI requireResourceUri(@NonNull URI resourceUri) {
		McpResourceValueSupport.requireAbsoluteNormalizedUri(resourceUri);
		String authority = resourceUri.getRawAuthority();
		if (!"ui".equalsIgnoreCase(resourceUri.getScheme())
				|| resourceUri.isOpaque() || authority == null || authority.isEmpty())
			throw new IllegalArgumentException(
					"MCP Apps resource URIs must be concrete hierarchical ui:// URIs with an authority.");
		return resourceUri;
	}

	@NonNull
	static String requireDomain(@NonNull String domain) {
		requireNonNull(domain);
		if (!isDnsName(domain))
			throw new IllegalArgumentException(
					"MCP Apps domains must be lowercase ASCII DNS names of at most 253 characters with 1–63 character LDH labels.");
		return domain;
	}

	@NonNull
	static Set<@NonNull String> immutableOrigins(
			@NonNull Set<@NonNull String> origins, boolean connectDomains) {
		requireNonNull(origins);
		Set<String> sorted = new TreeSet<>();
		for (String origin : origins) {
			requireNonNull(origin);
			if (!isOrigin(origin, connectDomains))
				throw new IllegalArgumentException(
						"MCP Apps origins must be canonical ASCII origins with a supported scheme, host, and port.");
			sorted.add(origin);
		}
		// Every accepted character is ASCII, so String order equals unsigned
		// lexicographic ASCII-byte order. Do not expose TreeSet mutation methods.
		return Collections.unmodifiableSet(new LinkedHashSet<>(sorted));
	}

	private static boolean isOrigin(String origin, boolean connectDomains) {
		for (int index = 0; index < origin.length(); ++index) {
			char character = origin.charAt(index);
			if (character <= 0x20 || character >= 0x7F || character == ','
					|| character == ';' || character == '\\' || character == '%')
				return false;
		}
		int separator = origin.indexOf("://");
		if (separator < 0)
			return false;
		String scheme = origin.substring(0, separator);
		boolean insecure = scheme.equals("http") || scheme.equals("ws");
		if (!scheme.equals("https") && !scheme.equals("http")
				&& !(connectDomains && (scheme.equals("wss") || scheme.equals("ws"))))
			return false;

		String authority = origin.substring(separator + 3);
		String host;
		String port = null;
		boolean loopback;
		if (authority.startsWith("[")) {
			int closingBracket = authority.indexOf(']');
			if (closingBracket < 0)
				return false;
			host = authority.substring(1, closingBracket);
			String rest = authority.substring(closingBracket + 1);
			if (!rest.isEmpty()) {
				if (!rest.startsWith(":"))
					return false;
				port = rest.substring(1);
			}
			int[] address = parseIpv6(host);
			if (address == null || !host.equals(canonicalIpv6(address)))
				return false;
			loopback = host.equals("::1");
		} else {
			int colon = authority.indexOf(':');
			host = colon < 0 ? authority : authority.substring(0, colon);
			if (colon >= 0)
				port = authority.substring(colon + 1);
			boolean wildcard = host.startsWith("*.");
			String dnsName = wildcard ? host.substring(2) : host;
			int[] address = parseIpv4(dnsName);
			if (address != null) {
				if (wildcard)
					return false;
				loopback = address[0] == 127;
			} else {
				if (host.length() > 253 || !isDnsName(dnsName)
						|| isLegacyIpv4(dnsName))
					return false;
				loopback = !wildcard && host.equals("localhost");
			}
		}
		return (!insecure || loopback) && (port == null || isPort(port));
	}

	private static boolean isDnsName(String value) {
		if (value.isEmpty() || value.length() > 253)
			return false;
		for (String label : value.split("\\.", -1)) {
			if (label.isEmpty() || label.length() > 63 || label.startsWith("-")
					|| label.endsWith("-"))
				return false;
			for (int index = 0; index < label.length(); ++index) {
				char character = label.charAt(index);
				if (!(character >= 'a' && character <= 'z')
						&& !(character >= '0' && character <= '9') && character != '-')
					return false;
			}
		}
		return true;
	}

	private static boolean isPort(String port) {
		if (port.isEmpty() || port.length() > 5 || port.charAt(0) == '0')
			return false;
		int value = 0;
		for (int index = 0; index < port.length(); ++index) {
			char character = port.charAt(index);
			if (character < '0' || character > '9')
				return false;
			value = value * 10 + character - '0';
		}
		return value <= 65535;
	}

	/** Reject legacy integer, octal, hexadecimal, and shortened IPv4 spellings. */
	private static boolean isLegacyIpv4(String host) {
		for (String label : host.split("\\.", -1)) {
			boolean hexadecimal = label.startsWith("0x");
			int start = hexadecimal ? 2 : 0;
			if (label.length() == start)
				return false;
			for (int index = start; index < label.length(); ++index) {
				char character = label.charAt(index);
				if (!(character >= '0' && character <= '9')
						&& !(hexadecimal && character >= 'a' && character <= 'f'))
					return false;
			}
		}
		return true;
	}

	private static int @Nullable [] parseIpv4(String host) {
		String[] parts = host.split("\\.", -1);
		if (parts.length != 4)
			return null;
		int[] address = new int[4];
		for (int index = 0; index < parts.length; ++index) {
			String part = parts[index];
			if (part.isEmpty() || part.length() > 3
					|| (part.length() > 1 && part.charAt(0) == '0'))
				return null;
			int value = 0;
			for (int characterIndex = 0; characterIndex < part.length(); ++characterIndex) {
				char character = part.charAt(characterIndex);
				if (character < '0' || character > '9')
					return null;
				value = value * 10 + character - '0';
			}
			if (value > 255)
				return null;
			address[index] = value;
		}
		return address;
	}

	/** Parse literal bytes locally; never pass untrusted names to a resolver. */
	private static int @Nullable [] parseIpv6(String host) {
		int compression = host.indexOf("::");
		if (compression >= 0 && host.indexOf("::", compression + 2) >= 0)
			return null;
		String left = compression < 0 ? host : host.substring(0, compression);
		String right = compression < 0 ? "" : host.substring(compression + 2);
		List<Integer> leftGroups = parseIpv6Groups(left, compression < 0);
		List<Integer> rightGroups = parseIpv6Groups(right, true);
		if (leftGroups == null || rightGroups == null)
			return null;
		int count = leftGroups.size() + rightGroups.size();
		if (compression < 0 ? count != 8 : count >= 8)
			return null;
		int[] address = new int[8];
		for (int index = 0; index < leftGroups.size(); ++index)
			address[index] = leftGroups.get(index);
		for (int index = 0; index < rightGroups.size(); ++index)
			address[8 - rightGroups.size() + index] = rightGroups.get(index);
		return address;
	}

	@Nullable
	private static List<Integer> parseIpv6Groups(String part, boolean allowIpv4Tail) {
		List<Integer> groups = new ArrayList<>();
		if (part.isEmpty())
			return groups;
		String[] tokens = part.split(":", -1);
		for (int index = 0; index < tokens.length; ++index) {
			String token = tokens[index];
			if (token.indexOf('.') >= 0) {
				int[] tail = parseIpv4(token);
				if (!allowIpv4Tail || index != tokens.length - 1 || tail == null)
					return null;
				groups.add((tail[0] << 8) | tail[1]);
				groups.add((tail[2] << 8) | tail[3]);
			} else {
				if (token.isEmpty() || token.length() > 4)
					return null;
				int value = 0;
				for (int characterIndex = 0; characterIndex < token.length(); ++characterIndex) {
					char character = token.charAt(characterIndex);
					int digit = character >= '0' && character <= '9' ? character - '0'
							: character >= 'a' && character <= 'f' ? character - 'a' + 10 : -1;
					if (digit < 0)
						return null;
					value = value * 16 + digit;
				}
				groups.add(value);
			}
			if (groups.size() > 8)
				return null;
		}
		return groups;
	}

	/** RFC 5952 zero compression; mapped IPv4 addresses use a dotted tail. */
	private static String canonicalIpv6(int[] address) {
		if (address[0] == 0 && address[1] == 0 && address[2] == 0
				&& address[3] == 0 && address[4] == 0 && address[5] == 0xffff)
			return "::ffff:" + (address[6] >> 8) + "." + (address[6] & 0xff)
					+ "." + (address[7] >> 8) + "." + (address[7] & 0xff);

		int bestStart = -1;
		int bestLength = 1;
		for (int index = 0; index < address.length;) {
			if (address[index] != 0) {
				++index;
				continue;
			}
			int start = index;
			while (index < address.length && address[index] == 0)
				++index;
			if (index - start > bestLength) {
				bestStart = start;
				bestLength = index - start;
			}
		}
		StringBuilder result = new StringBuilder();
		for (int index = 0; index < address.length;) {
			if (index == bestStart) {
				result.append("::");
				index += bestLength;
			} else {
				if (result.length() > 0 && result.charAt(result.length() - 1) != ':')
					result.append(':');
				result.append(Integer.toHexString(address[index++]));
			}
		}
		return result.toString();
	}
}
