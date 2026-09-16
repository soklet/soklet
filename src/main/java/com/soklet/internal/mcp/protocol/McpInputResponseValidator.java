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

import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.ThreadSafe;
import java.util.Map;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Validates one client-supplied value against the supported elicitation
 * {@code InputResponse} branch while preserving the schema's open-object
 * semantics.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class McpInputResponseValidator {
	@NonNull
	private static final String INVALID_MESSAGE = "MCP input response is invalid.";
	@NonNull
	private static final McpJsonLimits JSON_LIMITS =
			McpJsonLimits.productionDefaults();

	private McpInputResponseValidator() {
	}

	static boolean matches(
			@NonNull McpInputRequestDeclaration declaration,
			@NonNull McpJsonValue response) {
		requireNonNull(declaration);
		requireNonNull(response);
		if (!(response instanceof McpJsonObject object))
			return false;
		return switch (declaration.method()) {
			case "elicitation/create" ->
					matches(() -> validateElicitResult(object));
			default -> false;
		};
	}

	static void validate(@NonNull McpJsonValue response) {
		requireNonNull(response);
		McpJsonObject object = requireObject(response);

		if (matches(() -> validateElicitResult(object)))
			return;

		throw invalid();
	}

	private static void validateElicitResult(@NonNull McpJsonObject response) {
		Map<String, McpJsonValue> fields = response.members();
		requireStringValue(required(fields, "action"),
				Set.of("accept", "cancel", "decline"));
		if (!fields.containsKey("content"))
			return;
		for (McpJsonValue value
				: requireObject(fields.get("content")).members().values())
			validateElicitationValue(value);
	}

	private static void validateElicitationValue(@NonNull McpJsonValue value) {
		if (value instanceof McpJsonArray array) {
			for (McpJsonValue element : array.values())
				requireString(element);
			return;
		}
		if (value instanceof McpJsonString || value instanceof McpJsonBoolean)
			return;
		if (value instanceof McpJsonNumber number) {
			McpJsonIntegerSupport.toSerializableInteger(number.value(), JSON_LIMITS);
			return;
		}
		throw invalid();
	}

	private static boolean matches(@NonNull Runnable validator) {
		try {
			validator.run();
			return true;
		} catch (IllegalArgumentException exception) {
			return false;
		}
	}

	@NonNull
	private static McpJsonValue required(
			@NonNull Map<@NonNull String, @NonNull McpJsonValue> fields,
			@NonNull String name) {
		McpJsonValue value = fields.get(name);
		if (value == null)
			throw invalid();
		return value;
	}

	@NonNull
	private static McpJsonObject requireObject(@NonNull McpJsonValue value) {
		if (!(value instanceof McpJsonObject object))
			throw invalid();
		return object;
	}

	@NonNull
	private static String requireString(@NonNull McpJsonValue value) {
		if (!(value instanceof McpJsonString string))
			throw invalid();
		return string.value();
	}

	@NonNull
	private static String requireStringValue(@NonNull McpJsonValue value,
			@NonNull Set<@NonNull String> values) {
		String string = requireString(value);
		if (!values.contains(string))
			throw invalid();
		return string;
	}

	@NonNull
	private static IllegalArgumentException invalid() {
		return new IllegalArgumentException(INVALID_MESSAGE);
	}
}
