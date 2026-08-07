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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
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
	@NonNull
	private static final Set<@NonNull String> ROLES = Set.of("assistant", "user");

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
				case "sampling/createMessage" -> validateSampling(declaration, params);
				case "roots/list" -> validateRoots(params);
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

	private static void validateSampling(
			@NonNull McpInputRequestDeclaration declaration,
			@NonNull McpJsonObject params) {
		Map<String, McpJsonValue> fields = params.members();
		McpJsonArray messages = requiredArray(fields, "messages");
		requiredInteger(fields, "maxTokens");
		optionalString(fields, "systemPrompt");
		optionalNumber(fields, "temperature");
		optionalStringArray(fields, "stopSequences");

		if (fields.containsKey("includeContext")) {
			String includeContext = requireStringValue(fields.get("includeContext"),
					Set.of("none", "thisServer", "allServers"));
			if (!"none".equals(includeContext))
				requireCapability(declaration,
						McpCoreClientCapability.SAMPLING_CONTEXT);
		}

		if (fields.containsKey("metadata"))
			validateJsonObject(requiredObject(fields, "metadata"));
		if (fields.containsKey("modelPreferences"))
			validateModelPreferences(requiredObject(fields, "modelPreferences"));
		if (fields.containsKey("toolChoice")) {
			validateToolChoice(requiredObject(fields, "toolChoice"));
			requireCapability(declaration, McpCoreClientCapability.SAMPLING_TOOLS);
		}
		if (fields.containsKey("tools")) {
			for (McpJsonValue tool : requiredArray(fields, "tools").values())
				validateTool(requireObject(tool));
			requireCapability(declaration, McpCoreClientCapability.SAMPLING_TOOLS);
		}

		if (validateSamplingMessages(messages))
			requireCapability(declaration, McpCoreClientCapability.SAMPLING_TOOLS);
	}

	private static void validateModelPreferences(
			@NonNull McpJsonObject preferences) {
		Map<String, McpJsonValue> fields = preferences.members();
		optionalUnitInterval(fields, "costPriority");
		optionalUnitInterval(fields, "intelligencePriority");
		optionalUnitInterval(fields, "speedPriority");

		if (fields.containsKey("hints")) {
			for (McpJsonValue hint : requireArray(fields.get("hints")).values())
				optionalString(requireObject(hint).members(), "name");
		}
	}

	private static void validateToolChoice(@NonNull McpJsonObject toolChoice) {
		Map<String, McpJsonValue> fields = toolChoice.members();
		if (fields.containsKey("mode"))
			requireStringValue(fields.get("mode"),
					Set.of("auto", "required", "none"));
	}

	private static void validateTool(@NonNull McpJsonObject tool) {
		Map<String, McpJsonValue> fields = tool.members();
		requiredString(fields, "name");
		optionalString(fields, "title");
		optionalString(fields, "description");
		optionalMetadata(fields, "_meta");
		optionalIcons(fields, "icons");

		McpJsonObject inputSchema = requiredObject(fields, "inputSchema");
		requireStringValue(required(inputSchema.members(), "type"), Set.of("object"));
		optionalString(inputSchema.members(), "$schema");

		if (fields.containsKey("outputSchema"))
			optionalString(requiredObject(fields, "outputSchema").members(), "$schema");

		if (fields.containsKey("annotations")) {
			Map<String, McpJsonValue> annotations =
					requiredObject(fields, "annotations").members();
			optionalString(annotations, "title");
			optionalBoolean(annotations, "readOnlyHint");
			optionalBoolean(annotations, "destructiveHint");
			optionalBoolean(annotations, "idempotentHint");
			optionalBoolean(annotations, "openWorldHint");
		}
	}

	private static boolean validateSamplingMessages(@NonNull McpJsonArray messages) {
		Map<String, Integer> pendingToolUses = Map.of();
		Set<String> observedToolUseIds = new LinkedHashSet<>();
		boolean usesTools = false;

		for (McpJsonValue value : messages.values()) {
			McpJsonObject message = requireObject(value);
			Map<String, McpJsonValue> fields = message.members();
			String role = requireStringValue(required(fields, "role"), ROLES);
			optionalMetadata(fields, "_meta");
			List<McpJsonValue> blocks = contentBlocks(required(fields, "content"));
			Map<String, Integer> toolUses = new LinkedHashMap<>();
			Map<String, Integer> toolResults = new LinkedHashMap<>();

			for (McpJsonValue block : blocks) {
				String toolIdentifier = validateSamplingContentBlock(
						requireObject(block), role);
				String type = requiredString(requireObject(block).members(), "type");
				if ("tool_use".equals(type)) {
					if (!observedToolUseIds.add(toolIdentifier))
						throw invalid();
					toolUses.merge(toolIdentifier, 1, Integer::sum);
				} else if ("tool_result".equals(type)) {
					toolResults.merge(toolIdentifier, 1, Integer::sum);
				}
			}

			if (!toolResults.isEmpty()) {
				usesTools = true;
				if (!"user".equals(role) || toolResults.size() > blocks.size()
						|| toolResults.values().stream().mapToInt(Integer::intValue).sum()
								!= blocks.size()
						|| !pendingToolUses.equals(toolResults))
					throw invalid();
				pendingToolUses = Map.of();
			} else if (!pendingToolUses.isEmpty()) {
				throw invalid();
			}

			if (!toolUses.isEmpty()) {
				usesTools = true;
				if (!"assistant".equals(role) || !pendingToolUses.isEmpty())
					throw invalid();
				pendingToolUses = Map.copyOf(toolUses);
			}
		}

		if (!pendingToolUses.isEmpty())
			throw invalid();

		return usesTools;
	}

	@NonNull
	private static List<@NonNull McpJsonValue> contentBlocks(
			@NonNull McpJsonValue content) {
		if (content instanceof McpJsonArray array)
			return array.values();
		return List.of(requireObject(content));
	}

	@NonNull
	private static String validateSamplingContentBlock(@NonNull McpJsonObject block,
			@NonNull String role) {
		Map<String, McpJsonValue> fields = block.members();
		String type = requiredString(fields, "type");
		optionalMetadata(fields, "_meta");

		switch (type) {
			case "text" -> {
				requiredString(fields, "text");
				optionalAnnotations(fields, "annotations");
			}
			case "image", "audio" -> {
				requiredString(fields, "data");
				requiredString(fields, "mimeType");
				optionalAnnotations(fields, "annotations");
			}
			case "tool_use" -> {
				if (!"assistant".equals(role))
					throw invalid();
				requiredString(fields, "name");
				requiredObject(fields, "input");
				return requiredString(fields, "id");
			}
			case "tool_result" -> {
				if (!"user".equals(role))
					throw invalid();
				for (McpJsonValue content : requiredArray(fields, "content").values())
					validateToolResultContentBlock(requireObject(content));
				optionalBoolean(fields, "isError");
				return requiredString(fields, "toolUseId");
			}
			default -> throw invalid();
		}

		return "";
	}

	private static void validateToolResultContentBlock(@NonNull McpJsonObject block) {
		Map<String, McpJsonValue> fields = block.members();
		String type = requiredString(fields, "type");
		optionalMetadata(fields, "_meta");

		switch (type) {
			case "text" -> {
				requiredString(fields, "text");
				optionalAnnotations(fields, "annotations");
			}
			case "image", "audio" -> {
				requiredString(fields, "data");
				requiredString(fields, "mimeType");
				optionalAnnotations(fields, "annotations");
			}
			case "resource_link" -> validateResourceLink(fields);
			case "resource" -> validateEmbeddedResource(fields);
			default -> throw invalid();
		}
	}

	private static void validateResourceLink(
			@NonNull Map<@NonNull String, @NonNull McpJsonValue> fields) {
		requiredString(fields, "name");
		requireAbsoluteUri(requiredString(fields, "uri"));
		optionalString(fields, "title");
		optionalString(fields, "description");
		optionalString(fields, "mimeType");
		optionalInteger(fields, "size");
		optionalIcons(fields, "icons");
		optionalAnnotations(fields, "annotations");
	}

	private static void validateEmbeddedResource(
			@NonNull Map<@NonNull String, @NonNull McpJsonValue> fields) {
		optionalAnnotations(fields, "annotations");
		Map<String, McpJsonValue> resource =
				requiredObject(fields, "resource").members();

		if (matches(() -> validateTextResourceContents(resource))
				|| matches(() -> validateBlobResourceContents(resource)))
			return;

		throw invalid();
	}

	private static void validateTextResourceContents(
			@NonNull Map<@NonNull String, @NonNull McpJsonValue> fields) {
		validateResourceContents(fields);
		requiredString(fields, "text");
	}

	private static void validateBlobResourceContents(
			@NonNull Map<@NonNull String, @NonNull McpJsonValue> fields) {
		validateResourceContents(fields);
		requiredString(fields, "blob");
	}

	private static void validateResourceContents(
			@NonNull Map<@NonNull String, @NonNull McpJsonValue> resource) {
		requireAbsoluteUri(requiredString(resource, "uri"));
		optionalString(resource, "mimeType");
		optionalMetadata(resource, "_meta");
	}

	private static void optionalAnnotations(
			@NonNull Map<@NonNull String, @NonNull McpJsonValue> fields,
			@NonNull String name) {
		if (!fields.containsKey(name))
			return;
		Map<String, McpJsonValue> annotations =
				requireObject(fields.get(name)).members();
		optionalStringArrayValues(annotations, "audience", ROLES);
		optionalString(annotations, "lastModified");
		optionalUnitInterval(annotations, "priority");
	}

	private static void optionalIcons(
			@NonNull Map<@NonNull String, @NonNull McpJsonValue> fields,
			@NonNull String name) {
		if (!fields.containsKey(name))
			return;
		for (McpJsonValue value : requireArray(fields.get(name)).values()) {
			Map<String, McpJsonValue> icon = requireObject(value).members();
			requireAbsoluteUri(requiredString(icon, "src"));
			optionalString(icon, "mimeType");
			optionalStringArray(icon, "sizes");
			if (icon.containsKey("theme"))
				requireStringValue(icon.get("theme"), Set.of("dark", "light"));
		}
	}

	private static void validateRoots(@NonNull McpJsonObject params) {
		optionalMetadata(params.members(), "_meta");
	}

	private static void optionalMetadata(
			@NonNull Map<@NonNull String, @NonNull McpJsonValue> fields,
			@NonNull String name) {
		if (fields.containsKey(name))
			McpProtocolSupport.requireApplicationMetadataFields(
					requireObject(fields.get(name)), Set.of());
	}

	private static void validateJsonObject(@NonNull McpJsonObject object) {
		for (McpJsonValue value : object.members().values())
			validateJsonValue(value);
	}

	private static void validateJsonValue(@NonNull McpJsonValue value) {
		if (value instanceof McpJsonObject object) {
			validateJsonObject(object);
		} else if (value instanceof McpJsonArray array) {
			for (McpJsonValue element : array.values())
				validateJsonValue(element);
		} else if (value instanceof McpJsonNumber number) {
			McpJsonIntegerSupport.toSerializableInteger(number.value(), JSON_LIMITS);
		} else if (!(value instanceof McpJsonString)
				&& !(value instanceof McpJsonBoolean)) {
			throw invalid();
		}
	}

	private static void requireCapability(
			@NonNull McpInputRequestDeclaration declaration,
			@NonNull McpCoreClientCapability capability) {
		if (!declaration.capabilities().contains(capability))
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
	private static McpJsonArray requiredArray(
			@NonNull Map<@NonNull String, @NonNull McpJsonValue> fields,
			@NonNull String name) {
		return requireArray(required(fields, name));
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

	private static void requiredInteger(
			@NonNull Map<@NonNull String, @NonNull McpJsonValue> fields,
			@NonNull String name) {
		requireInteger(required(fields, name));
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

	private static void optionalUnitInterval(
			@NonNull Map<@NonNull String, @NonNull McpJsonValue> fields,
			@NonNull String name) {
		if (!fields.containsKey(name))
			return;
		BigDecimal value = requireNumber(fields.get(name));
		if (value.compareTo(BigDecimal.ZERO) < 0
				|| value.compareTo(BigDecimal.ONE) > 0)
			throw invalid();
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

	private static void optionalStringArrayValues(
			@NonNull Map<@NonNull String, @NonNull McpJsonValue> fields,
			@NonNull String name, @NonNull Set<@NonNull String> permittedValues) {
		if (!fields.containsKey(name))
			return;
		for (McpJsonValue element : requireArray(fields.get(name)).values())
			requireStringValue(element, permittedValues);
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
