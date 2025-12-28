/*
 * Copyright 2022-2025 Revetware LLC.
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

package com.soklet.internal.util;

import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.ThreadSafe;

/**
 * Validates HTTP/1.1 Host header values against RFC 3986 uri-host grammar.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class HostHeaderValidator {
	private HostHeaderValidator() {
	}

	public static boolean isValidHostHeaderValue(@Nullable String value) {
		if (value == null)
			return false;

		String trimmed = trimOws(value);

		if (trimmed.isEmpty())
			return false;

		for (int i = 0; i < trimmed.length(); i++) {
			char c = trimmed.charAt(i);
			if (c > 0x7F || c == ' ' || c == '\t')
				return false;
		}

		return isValidHostPort(trimmed);
	}

	private static String trimOws(String value) {
		int start = 0;
		int end = value.length();

		while (start < end) {
			char c = value.charAt(start);
			if (c != ' ' && c != '\t')
				break;
			start++;
		}

		while (end > start) {
			char c = value.charAt(end - 1);
			if (c != ' ' && c != '\t')
				break;
			end--;
		}

		return value.substring(start, end);
	}

	private static boolean isValidHostPort(String value) {
		if (value.startsWith("[")) {
			int close = value.indexOf(']');
			if (close <= 0)
				return false;

			String literal = value.substring(1, close);
			String rest = value.substring(close + 1);

			if (!rest.isEmpty()) {
				if (!rest.startsWith(":"))
					return false;

				if (!isValidPort(rest.substring(1)))
					return false;
			}

			return isValidIpLiteral(literal);
		}

		int colon = value.indexOf(':');
		if (colon != -1) {
			if (value.indexOf(':', colon + 1) != -1)
				return false;

			String host = value.substring(0, colon);
			String port = value.substring(colon + 1);

			if (host.isEmpty() || port.isEmpty())
				return false;

			if (!isValidPort(port))
				return false;

			return isValidHostName(host);
		}

		return isValidHostName(value);
	}

	private static boolean isValidHostName(String host) {
		return isValidIpv4Address(host) || isValidRegName(host);
	}

	private static boolean isValidPort(String port) {
		if (port.isEmpty() || port.length() > 5)
			return false;

		int value = 0;
		for (int i = 0; i < port.length(); i++) {
			char c = port.charAt(i);
			if (c < '0' || c > '9')
				return false;
			value = value * 10 + (c - '0');
			if (value > 65535)
				return false;
		}

		return true;
	}

	private static boolean isValidIpv4Address(String host) {
		String[] parts = host.split("\\.", -1);
		if (parts.length != 4)
			return false;

		for (String part : parts) {
			if (part.isEmpty() || part.length() > 3)
				return false;

			int value = 0;
			for (int i = 0; i < part.length(); i++) {
				char c = part.charAt(i);
				if (c < '0' || c > '9')
					return false;
				value = value * 10 + (c - '0');
			}
			if (value > 255)
				return false;
		}

		return true;
	}

	private static boolean isValidIpLiteral(String literal) {
		if (literal.isEmpty() || literal.indexOf('%') != -1)
			return false;

		char first = literal.charAt(0);
		if (first == 'v' || first == 'V')
			return isValidIpvFuture(literal);

		return isValidIpv6Address(literal);
	}

	private static boolean isValidIpv6Address(String literal) {
		if (literal.isEmpty())
			return false;

		int doubleColon = literal.indexOf("::");
		if (doubleColon != -1 && literal.indexOf("::", doubleColon + 1) != -1)
			return false;

		if (doubleColon == -1) {
			String[] parts = literal.split(":", -1);
			if (parts.length == 0)
				return false;

			for (String part : parts) {
				if (part.isEmpty())
					return false;
			}

			boolean lastIsIpv4 = isValidIpv4Address(parts[parts.length - 1]);
			int expectedParts = lastIsIpv4 ? 7 : 8;

			if (parts.length != expectedParts)
				return false;

			for (int i = 0; i < parts.length; i++) {
				if (i == parts.length - 1 && lastIsIpv4)
					continue;
				if (!isValidH16(parts[i]))
					return false;
			}

			return true;
		}

		String left = literal.substring(0, doubleColon);
		String right = literal.substring(doubleColon + 2);

		String[] leftParts = left.isEmpty() ? new String[0] : left.split(":", -1);
		String[] rightParts = right.isEmpty() ? new String[0] : right.split(":", -1);

		for (String part : leftParts) {
			if (part.isEmpty() || !isValidH16(part))
				return false;
		}

		int segments = leftParts.length;

		for (int i = 0; i < rightParts.length; i++) {
			String part = rightParts[i];
			if (part.isEmpty())
				return false;

			boolean last = (i == rightParts.length - 1);

			if (isValidIpv4Address(part)) {
				if (!last)
					return false;
				segments += 2;
			} else if (isValidH16(part)) {
				segments += 1;
			} else {
				return false;
			}
		}

		return segments < 8;
	}

	private static boolean isValidH16(String part) {
		if (part.isEmpty() || part.length() > 4)
			return false;

		for (int i = 0; i < part.length(); i++) {
			if (!isHex(part.charAt(i)))
				return false;
		}

		return true;
	}

	private static boolean isValidIpvFuture(String literal) {
		int dot = literal.indexOf('.');
		if (dot < 2 || dot == literal.length() - 1)
			return false;

		for (int i = 1; i < dot; i++) {
			if (!isHex(literal.charAt(i)))
				return false;
		}

		for (int i = dot + 1; i < literal.length(); i++) {
			char c = literal.charAt(i);
			if (!(isUnreserved(c) || isSubDelim(c) || c == ':'))
				return false;
		}

		return true;
	}

	private static boolean isValidRegName(String host) {
		if (host.isEmpty())
			return false;

		for (int i = 0; i < host.length(); i++) {
			char c = host.charAt(i);

			if (c == '%') {
				if (i + 2 >= host.length())
					return false;
				if (!isHex(host.charAt(i + 1)) || !isHex(host.charAt(i + 2)))
					return false;
				i += 2;
				continue;
			}

			if (isUnreserved(c) || isSubDelim(c))
				continue;

			return false;
		}

		return true;
	}

	private static boolean isUnreserved(char c) {
		return (c >= 'a' && c <= 'z')
				|| (c >= 'A' && c <= 'Z')
				|| (c >= '0' && c <= '9')
				|| c == '-' || c == '.' || c == '_' || c == '~';
	}

	private static boolean isSubDelim(char c) {
		return c == '!' || c == '$' || c == '&' || c == '\'' || c == '(' || c == ')' || c == '*'
				|| c == '+' || c == ',' || c == ';' || c == '=';
	}

	private static boolean isHex(char c) {
		return (c >= '0' && c <= '9')
				|| (c >= 'A' && c <= 'F')
				|| (c >= 'a' && c <= 'f');
	}
}
