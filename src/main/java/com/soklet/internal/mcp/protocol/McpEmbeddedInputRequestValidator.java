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
import java.math.BigDecimal;
import java.net.URI;
import java.util.Map;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Validates the method-specific parameters of server-to-client requests before
 * they are embedded in an input-required result.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class McpEmbeddedInputRequestValidator {
	@NonNull
	private static final String INVALID_MESSAGE =
			"Embedded MCP input-request parameters are invalid.";
	@NonNull
	private static final McpJsonLimits JSON_LIMITS =
			McpJsonLimits.productionDefaults();
	@NonNull
	private static final McpJsonCodec JSON_CODEC = new McpJsonCodec(JSON_LIMITS);

	private McpEmbeddedInputRequestValidator() {
	}

	static void validate(@NonNull McpInputRequestDeclaration declaration,
			@NonNull McpJsonObject params) {
		requireNonNull(declaration);
		requireNonNull(params);

		try {
			// Applying the production writer to the immutable tree enforces all
			// production nesting, node, string, number, and output-byte limits.
			JSON_CODEC.toUtf8Bytes(params);

			switch (declaration.method()) {
				case "elicitation/create" -> validateElicitation(declaration, params);
				default -> throw invalid();
			}
		} catch (IllegalArgumentException exception) {
			// Protocol errors cross an application boundary. Do not expose values,
			// metadata keys, URIs, or codec diagnostics in the public error message.
			throw invalid();
		}
	}

	private static void validateElicitation(
			@NonNull McpInputRequestDeclaration declaration,
			@NonNull McpJsonObject params) {
		Map<String, McpJsonValue> fields = params.members();
		requiredString(fields, "message");

		if (declaration.capabilities().contains(
				McpCoreClientCapability.ELICITATION_FORM)) {
			if (fields.containsKey("mode"))
				requireStringValue(fields.get("mode"), Set.of("form"));
			validateRequestedSchema(requiredObject(fields, "requestedSchema"));
			return;
		}

		if (!declaration.capabilities().contains(
				McpCoreClientCapability.ELICITATION_URL))
			throw invalid();

		requireStringValue(required(fields, "mode"), Set.of("url"));
		requireAbsoluteUri(requiredString(fields, "url"));
	}

	private static void validateRequestedSchema(@NonNull McpJsonObject schema) {
		Map<String, McpJsonValue> fields = schema.members();
		requireStringValue(required(fields, "type"), Set.of("object"));
		optionalString(fields, "$schema");
		optionalStringArray(fields, "required");

		for (McpJsonValue definition : requiredObject(fields, "properties")
				.members().values())
			validatePrimitiveSchema(requireObject(definition));
	}

	private static void validatePrimitiveSchema(@NonNull McpJsonObject schema) {
		Map<String, McpJsonValue> fields = schema.members();

		if (matches(() -> validateStringSchema(fields))
				|| matches(() -> validateNumberSchema(fields))
				|| matches(() -> validateBooleanSchema(fields))
				|| matches(() -> validateUntitledSingleSelectSchema(fields))
				|| matches(() -> validateTitledSingleSelectSchema(fields))
				|| matches(() -> validateLegacyTitledEnumSchema(fields))
				|| matches(() -> validateUntitledMultiSelectSchema(fields))
				|| matches(() -> validateTitledMultiSelectSchema(fields)))
			return;

		throw invalid();
	}

	private static void validateStringSchema(
			@NonNull Map<@NonNull String, @NonNull McpJsonValue> fields) {
		requireStringValue(required(fields, "type"), Set.of("string"));
		optionalString(fields, "description");
		optionalString(fields, "title");
		optionalString(fields, "default");
		optionalInteger(fields, "minLength");
		optionalInteger(fields, "maxLength");
		if (fields.containsKey("format"))
			requireStringValue(fields.get("format"),
					Set.of("date", "date-time", "email", "uri"));
	}

	private static void validateNumberSchema(
			@NonNull Map<@NonNull String, @NonNull McpJsonValue> fields) {
		requireStringValue(required(fields, "type"), Set.of("integer", "number"));
		optionalString(fields, "description");
		optionalString(fields, "title");
		optionalNumber(fields, "default");
		optionalNumber(fields, "minimum");
		optionalNumber(fields, "maximum");
	}

	private static void validateBooleanSchema(
			@NonNull Map<@NonNull String, @NonNull McpJsonValue> fields) {
		requireStringValue(required(fields, "type"), Set.of("boolean"));
		optionalString(fields, "description");
		optionalString(fields, "title");
		optionalBoolean(fields, "default");
	}

	private static void validateUntitledSingleSelectSchema(
			@NonNull Map<@NonNull String, @NonNull McpJsonValue> fields) {
		requireStringValue(required(fields, "type"), Set.of("string"));
		optionalString(fields, "description");
		optionalString(fields, "title");
		optionalString(fields, "default");
		requireStringArray(required(fields, "enum"));
	}

	private static void validateTitledSingleSelectSchema(
			@NonNull Map<@NonNull String, @NonNull McpJsonValue> fields) {
		requireStringValue(required(fields, "type"), Set.of("string"));
		optionalString(fields, "description");
		optionalString(fields, "title");
		optionalString(fields, "default");
		requireTitledOptions(required(fields, "oneOf"));
	}

	private static void validateLegacyTitledEnumSchema(
			@NonNull Map<@NonNull String, @NonNull McpJsonValue> fields) {
		requireStringValue(required(fields, "type"), Set.of("string"));
		optionalString(fields, "description");
		optionalString(fields, "title");
		optionalString(fields, "default");
		requireStringArray(required(fields, "enum"));
		optionalStringArray(fields, "enumNames");
	}

	private static void validateUntitledMultiSelectSchema(
			@NonNull Map<@NonNull String, @NonNull McpJsonValue> fields) {
		validateMultiSelectFields(fields);
		Map<String, McpJsonValue> itemFields =
				requiredObject(fields, "items").members();
		requireStringValue(required(itemFields, "type"), Set.of("string"));
		requireStringArray(required(itemFields, "enum"));
	}

	private static void validateTitledMultiSelectSchema(
			@NonNull Map<@NonNull String, @NonNull McpJsonValue> fields) {
		validateMultiSelectFields(fields);
		Map<String, McpJsonValue> itemFields =
				requiredObject(fields, "items").members();
		requireTitledOptions(required(itemFields, "anyOf"));
	}

	private static void validateMultiSelectFields(
			@NonNull Map<@NonNull String, @NonNull McpJsonValue> fields) {
		requireStringValue(required(fields, "type"), Set.of("array"));
		optionalString(fields, "description");
		optionalString(fields, "title");
		optionalStringArray(fields, "default");
		optionalInteger(fields, "minItems");
		optionalInteger(fields, "maxItems");
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
	private static McpJsonObject requiredObject(
			@NonNull Map<@NonNull String, @NonNull McpJsonValue> fields,
			@NonNull String name) {
		return requireObject(required(fields, name));
	}

	@NonNull
	private static McpJsonObject requireObject(@NonNull McpJsonValue value) {
		if (!(value instanceof McpJsonObject object))
			throw invalid();
		return object;
	}

	@NonNull
	private static McpJsonArray requireArray(@NonNull McpJsonValue value) {
		if (!(value instanceof McpJsonArray array))
			throw invalid();
		return array;
	}

	@NonNull
	private static String requiredString(
			@NonNull Map<@NonNull String, @NonNull McpJsonValue> fields,
			@NonNull String name) {
		return requireString(required(fields, name));
	}

	@NonNull
	private static String requireString(@NonNull McpJsonValue value) {
		if (!(value instanceof McpJsonString string))
			throw invalid();
		return string.value();
	}

	@NonNull
	private static String requireStringValue(@NonNull McpJsonValue value,
			@NonNull Set<@NonNull String> permittedValues) {
		String string = requireString(value);
		if (!permittedValues.contains(string))
			throw invalid();
		return string;
	}

	private static void optionalString(
			@NonNull Map<@NonNull String, @NonNull McpJsonValue> fields,
			@NonNull String name) {
		if (fields.containsKey(name))
			requireString(fields.get(name));
	}

	private static void optionalInteger(
			@NonNull Map<@NonNull String, @NonNull McpJsonValue> fields,
			@NonNull String name) {
		if (fields.containsKey(name))
			requireInteger(fields.get(name));
	}

	private static void requireInteger(@NonNull McpJsonValue value) {
		McpJsonIntegerSupport.toSerializableInteger(requireNumber(value), JSON_LIMITS);
	}

	private static void optionalNumber(
			@NonNull Map<@NonNull String, @NonNull McpJsonValue> fields,
			@NonNull String name) {
		if (fields.containsKey(name))
			requireNumber(fields.get(name));
	}

	@NonNull
	private static BigDecimal requireNumber(@NonNull McpJsonValue value) {
		if (!(value instanceof McpJsonNumber number))
			throw invalid();
		return number.value();
	}

	private static void optionalBoolean(
			@NonNull Map<@NonNull String, @NonNull McpJsonValue> fields,
			@NonNull String name) {
		if (fields.containsKey(name) && !(fields.get(name) instanceof McpJsonBoolean))
			throw invalid();
	}

	private static void optionalStringArray(
			@NonNull Map<@NonNull String, @NonNull McpJsonValue> fields,
			@NonNull String name) {
		if (fields.containsKey(name))
			requireStringArray(fields.get(name));
	}

	private static void requireStringArray(@NonNull McpJsonValue value) {
		for (McpJsonValue element : requireArray(value).values())
			requireString(element);
	}

	private static void requireTitledOptions(@NonNull McpJsonValue value) {
		for (McpJsonValue option : requireArray(value).values()) {
			Map<String, McpJsonValue> fields = requireObject(option).members();
			requiredString(fields, "const");
			requiredString(fields, "title");
		}
	}

	private static void requireAbsoluteUri(@NonNull String value) {
		McpProtocolSupport.requireAbsoluteUri(URI.create(value), "URI");
	}

	@NonNull
	private static IllegalArgumentException invalid() {
		return new IllegalArgumentException(INVALID_MESSAGE);
	}
}
