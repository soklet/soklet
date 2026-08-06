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

package com.soklet.internal.mcp.protocol;

import com.soklet.internal.microhttp.Header;
import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.ThreadSafe;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Validates request-only custom argument mirrors from a precompiled plan.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class McpCustomMirroredHeaderValidator {
	@NonNull
	private static final String NORMALIZED_HEADER_PREFIX = "mcp-param-";
	@NonNull
	private static final BigInteger MAXIMUM_SAFE_INTEGER =
			BigInteger.valueOf(9_007_199_254_740_991L);
	@NonNull
	private static final BigInteger MINIMUM_SAFE_INTEGER = MAXIMUM_SAFE_INTEGER.negate();

	@NonNull
	private final McpMirroredHeaderCodec codec;

	McpCustomMirroredHeaderValidator(@NonNull McpMirroredHeaderCodec codec) {
		this.codec = requireNonNull(codec);
	}

	@NonNull
	McpCustomMirroredHeaderValidation validate(
			@NonNull List<@NonNull Header> headers,
			McpJsonRpcEnvelope.@NonNull Request request,
			@NonNull McpServerCapabilityRegistry registry,
			@NonNull McpUnknownMirroredHeaderPolicy unknownHeaderPolicy,
			boolean captureUnknownHeaderNames) {
		requireNonNull(headers);
		requireNonNull(request);
		requireNonNull(registry);
		requireNonNull(unknownHeaderPolicy);

		McpMirroredHeaderPlan plan = selectedPlan(request, registry)
				.orElseGet(McpMirroredHeaderPlan::empty);
		Map<String, McpMirroredHeaderDeclaration> declarationsByHeader =
				new LinkedHashMap<>();
		for (McpMirroredHeaderDeclaration declaration : plan.declarations())
			declarationsByHeader.put(
					declaration.headerName().toLowerCase(Locale.ROOT), declaration);

		Map<String, List<String>> recognizedValues = new LinkedHashMap<>();
		int unknownHeaderCount = 0;
		List<String> unknownHeaderNames = captureUnknownHeaderNames
				? new ArrayList<>() : List.of();
		for (Header header : headers) {
			String normalizedName = header.name().toLowerCase(Locale.ROOT);
			if (!normalizedName.startsWith(NORMALIZED_HEADER_PREFIX))
				continue;
			if (!declarationsByHeader.containsKey(normalizedName)) {
				unknownHeaderCount++;
				if (captureUnknownHeaderNames)
					unknownHeaderNames.add(header.name());
				continue;
			}
			recognizedValues.computeIfAbsent(normalizedName,
					ignored -> new ArrayList<>()).add(trimOptionalWhitespace(header.value()));
		}

		Optional<McpJsonObject> arguments = toolArguments(request);
		for (Map.Entry<String, McpMirroredHeaderDeclaration> entry
				: declarationsByHeader.entrySet()) {
			List<String> values = recognizedValues.getOrDefault(entry.getKey(), List.of());
			Optional<McpJsonValue> bodyValue = arguments.flatMap(value ->
					valueAtPath(value, entry.getValue().argumentPropertyPath()));
			if (bodyValue.isEmpty() || bodyValue.orElseThrow() == McpJsonNull.INSTANCE) {
				if (!values.isEmpty())
					return mismatch(unknownHeaderCount, unknownHeaderNames);
				continue;
			}
			if (values.size() != 1
					|| !matches(entry.getValue(), values.get(0), bodyValue.orElseThrow()))
				return mismatch(unknownHeaderCount, unknownHeaderNames);
		}

		if (unknownHeaderCount > 0
				&& unknownHeaderPolicy == McpUnknownMirroredHeaderPolicy.REJECT_REQUESTS)
			return new McpCustomMirroredHeaderValidation(
					McpCustomMirroredHeaderOutcome.STRICT_UNKNOWN, unknownHeaderCount,
					unknownHeaderNames);

		return new McpCustomMirroredHeaderValidation(
				McpCustomMirroredHeaderOutcome.VALID, unknownHeaderCount,
				unknownHeaderNames);
	}

	@NonNull
	private Optional<@NonNull McpMirroredHeaderPlan> selectedPlan(
			McpJsonRpcEnvelope.@NonNull Request request,
			@NonNull McpServerCapabilityRegistry registry) {
		if (!"tools/call".equals(request.method()))
			return Optional.empty();
		Optional<String> toolName = toolName(request);
		if (toolName.isEmpty())
			return Optional.empty();
		return registry.toolMirroredHeaderPlan(toolName.orElseThrow());
	}

	@NonNull
	private Optional<@NonNull String> toolName(
			McpJsonRpcEnvelope.@NonNull Request request) {
		if (request.params().isEmpty()
				|| !(request.params().orElseThrow() instanceof McpJsonObject params))
			return Optional.empty();
		McpJsonValue value = params.members().get("name");
		return value instanceof McpJsonString name
				? Optional.of(name.value())
				: Optional.empty();
	}

	@NonNull
	private Optional<@NonNull McpJsonObject> toolArguments(
			McpJsonRpcEnvelope.@NonNull Request request) {
		if (request.params().isEmpty()
				|| !(request.params().orElseThrow() instanceof McpJsonObject params))
			return Optional.empty();
		McpJsonValue value = params.members().get("arguments");
		return value instanceof McpJsonObject arguments
				? Optional.of(arguments)
				: Optional.empty();
	}

	@NonNull
	private Optional<@NonNull McpJsonValue> valueAtPath(@NonNull McpJsonObject root,
			@NonNull List<@NonNull String> path) {
		McpJsonValue current = root;
		for (String property : path) {
			if (!(current instanceof McpJsonObject object)
					|| !object.members().containsKey(property))
				return Optional.empty();
			current = object.members().get(property);
		}
		return Optional.of(current);
	}

	private boolean matches(@NonNull McpMirroredHeaderDeclaration declaration,
			@NonNull String headerValue, @NonNull McpJsonValue bodyValue) {
		try {
			return switch (declaration.valueType()) {
				case STRING -> bodyValue instanceof McpJsonString string
						&& codec.decodeString(headerValue).equals(string.value());
				case BOOLEAN -> bodyValue instanceof McpJsonBoolean bool
						&& codec.decodeString(headerValue).equals(
								bool == McpJsonBoolean.TRUE ? "true" : "false");
				case INTEGER -> bodyValue instanceof McpJsonNumber number
						&& integerMatches(headerValue, number);
			};
		} catch (IllegalArgumentException exception) {
			return false;
		}
	}

	private boolean integerMatches(@NonNull String headerValue,
			@NonNull McpJsonNumber bodyValue) {
		String decodedValue = codec.decodeString(headerValue);
		if (!decimalInteger(decodedValue))
			return false;

		BigInteger bodyInteger;
		try {
			bodyInteger = bodyValue.value().toBigIntegerExact();
		} catch (ArithmeticException exception) {
			return false;
		}
		if (bodyInteger.compareTo(MINIMUM_SAFE_INTEGER) < 0
				|| bodyInteger.compareTo(MAXIMUM_SAFE_INTEGER) > 0)
			return false;
		return new BigInteger(decodedValue).equals(bodyInteger);
	}

	private boolean decimalInteger(@NonNull String value) {
		if ("0".equals(value))
			return true;
		int firstDigit = value.startsWith("-") ? 1 : 0;
		if (firstDigit == value.length() || value.charAt(firstDigit) < '1'
				|| value.charAt(firstDigit) > '9')
			return false;
		for (int index = firstDigit + 1; index < value.length(); index++) {
			char character = value.charAt(index);
			if (character < '0' || character > '9')
				return false;
		}
		return true;
	}

	@NonNull
	private String trimOptionalWhitespace(@NonNull String value) {
		int start = 0;
		int end = value.length();
		while (start < end && (value.charAt(start) == ' ' || value.charAt(start) == '\t'))
			start++;
		while (end > start && (value.charAt(end - 1) == ' '
				|| value.charAt(end - 1) == '\t'))
			end--;
		return value.substring(start, end);
	}

	@NonNull
	private McpCustomMirroredHeaderValidation mismatch(int unknownHeaderCount,
			@NonNull List<@NonNull String> unknownHeaderNames) {
		return new McpCustomMirroredHeaderValidation(
				McpCustomMirroredHeaderOutcome.HEADER_MISMATCH, unknownHeaderCount,
				unknownHeaderNames);
	}
}

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
record McpCustomMirroredHeaderValidation(
		@NonNull McpCustomMirroredHeaderOutcome outcome, int unknownHeaderCount,
		@NonNull List<@NonNull String> unknownHeaderNames) {
	McpCustomMirroredHeaderValidation {
		requireNonNull(outcome);
		if (unknownHeaderCount < 0)
			throw new IllegalArgumentException("Unknown header count must not be negative.");
		unknownHeaderNames = List.copyOf(requireNonNull(unknownHeaderNames));
		if (unknownHeaderNames.size() > unknownHeaderCount)
			throw new IllegalArgumentException(
					"Captured unknown header names must not exceed the occurrence count.");
	}
}

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
enum McpCustomMirroredHeaderOutcome {
	VALID,
	HEADER_MISMATCH,
	STRICT_UNKNOWN
}
