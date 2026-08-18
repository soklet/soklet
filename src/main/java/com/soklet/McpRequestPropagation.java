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

import javax.annotation.concurrent.ThreadSafe;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Validated propagation data extracted exclusively from MCP request metadata.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
record McpRequestPropagation(
		@NonNull Optional<@NonNull TraceContext> traceContext,
		@NonNull Map<@NonNull String, @NonNull String> baggage) {
	@NonNull
	private static final String TRACEPARENT_KEY = "traceparent";
	@NonNull
	private static final String TRACESTATE_KEY = "tracestate";
	@NonNull
	private static final String BAGGAGE_KEY = "baggage";
	private static final int MAXIMUM_BAGGAGE_BYTES = 8_192;
	private static final int MAXIMUM_BAGGAGE_ENTRIES = 64;

	McpRequestPropagation {
		requireNonNull(traceContext);
		baggage = Collections.unmodifiableMap(
				new LinkedHashMap<>(requireNonNull(baggage)));
	}

	@NonNull
	static McpRequestPropagation fromMetadata(@NonNull McpJsonObject metadata) {
		requireNonNull(metadata);
		Optional<String> traceparent = stringValue(metadata, TRACEPARENT_KEY);
		Optional<String> tracestate = stringValue(metadata, TRACESTATE_KEY);
		Optional<TraceContext> traceContext = traceparent.flatMap(value ->
				TraceContext.fromHeaderValues(List.of(value),
						tracestate.map(List::of).orElse(null)));
		Map<String, String> baggage = stringValue(metadata, BAGGAGE_KEY)
				.map(McpRequestPropagation::parseBaggage)
				.orElseGet(Map::of);
		return new McpRequestPropagation(traceContext, baggage);
	}

	@NonNull
	private static Optional<@NonNull String> stringValue(
			@NonNull McpJsonObject metadata, @NonNull String key) {
		return metadata.find(key)
				.filter(McpJsonString.class::isInstance)
				.map(McpJsonString.class::cast)
				.map(McpJsonString::getValue);
	}

	@NonNull
	private static Map<@NonNull String, @NonNull String> parseBaggage(
			@NonNull String value) {
		requireNonNull(value);
		if (value.length() > MAXIMUM_BAGGAGE_BYTES)
			return Map.of();

		Map<String, String> entries = new LinkedHashMap<>();
		for (String member : value.split(",", -1)) {
			parseBaggageMember(member).ifPresent(entry ->
					entries.putIfAbsent(entry.key(), entry.value()));
			if (entries.size() == MAXIMUM_BAGGAGE_ENTRIES)
				break;
		}
		return Collections.unmodifiableMap(entries);
	}

	@NonNull
	private static Optional<@NonNull BaggageEntry> parseBaggageMember(
			@NonNull String member) {
		String[] components = member.split(";", -1);
		String pair = trimOptionalWhitespace(components[0]);
		int separator = pair.indexOf('=');
		if (separator < 1)
			return Optional.empty();

		String key = trimOptionalWhitespace(pair.substring(0, separator));
		String encodedValue = trimOptionalWhitespace(pair.substring(separator + 1));
		if (!isToken(key) || !isEncodedValue(encodedValue))
			return Optional.empty();

		for (int i = 1; i < components.length; i++)
			if (!isProperty(components[i]))
				return Optional.empty();

		return Optional.of(new BaggageEntry(key, decodeValue(encodedValue)));
	}

	private static boolean isProperty(@NonNull String component) {
		String property = trimOptionalWhitespace(component);
		int separator = property.indexOf('=');
		if (separator < 0)
			return isToken(property);

		String key = trimOptionalWhitespace(property.substring(0, separator));
		String value = trimOptionalWhitespace(property.substring(separator + 1));
		return isToken(key) && isEncodedValue(value);
	}

	private static boolean isToken(@NonNull String value) {
		if (value.isEmpty())
			return false;

		for (int i = 0; i < value.length(); i++) {
			char character = value.charAt(i);
			if (!isAsciiLetterOrDigit(character)
					&& "!#$%&'*+-.^_`|~".indexOf(character) < 0)
				return false;
		}
		return true;
	}

	private static boolean isEncodedValue(@NonNull String value) {
		for (int i = 0; i < value.length(); i++) {
			char character = value.charAt(i);
			if (character == '%') {
				if (i + 2 >= value.length()
						|| !isHexDigit(value.charAt(i + 1))
						|| !isHexDigit(value.charAt(i + 2)))
					return false;
				i += 2;
			} else if (!isBaggageOctet(character)) {
				return false;
			}
		}
		return true;
	}

	@NonNull
	private static String decodeValue(@NonNull String value) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream(value.length());
		for (int i = 0; i < value.length(); i++) {
			char character = value.charAt(i);
			if (character == '%') {
				bytes.write((hexValue(value.charAt(i + 1)) << 4)
						| hexValue(value.charAt(i + 2)));
				i += 2;
			} else {
				bytes.write(character);
			}
		}
		return bytes.toString(StandardCharsets.UTF_8);
	}

	@NonNull
	private static String trimOptionalWhitespace(@NonNull String value) {
		int first = 0;
		while (first < value.length() && isOptionalWhitespace(value.charAt(first)))
			first++;
		int last = value.length();
		while (last > first && isOptionalWhitespace(value.charAt(last - 1)))
			last--;
		return value.substring(first, last);
	}

	private static boolean isOptionalWhitespace(char character) {
		return character == ' ' || character == '\t';
	}

	private static boolean isAsciiLetterOrDigit(char character) {
		return character >= 'a' && character <= 'z'
				|| character >= 'A' && character <= 'Z'
				|| character >= '0' && character <= '9';
	}

	private static boolean isBaggageOctet(char character) {
		return character == 0x21
				|| character >= 0x23 && character <= 0x2B
				|| character >= 0x2D && character <= 0x3A
				|| character >= 0x3C && character <= 0x5B
				|| character >= 0x5D && character <= 0x7E;
	}

	private static boolean isHexDigit(char character) {
		return character >= '0' && character <= '9'
				|| character >= 'a' && character <= 'f'
				|| character >= 'A' && character <= 'F';
	}

	private static int hexValue(char character) {
		if (character >= '0' && character <= '9')
			return character - '0';
		if (character >= 'a' && character <= 'f')
			return character - 'a' + 10;
		return character - 'A' + 10;
	}

	/**
	 * One validated baggage entry after percent decoding.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	private record BaggageEntry(@NonNull String key, @NonNull String value) {
		private BaggageEntry {
			requireNonNull(key);
			requireNonNull(value);
		}
	}
}
