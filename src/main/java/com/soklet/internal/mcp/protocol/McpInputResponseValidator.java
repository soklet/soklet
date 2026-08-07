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
 * Validates one client-supplied value against the final MCP
 * {@code InputResponse} union while preserving the schema's open-object
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
	@NonNull
	private static final Set<@NonNull String> ROLES =
			Set.of("assistant", "user");

	private McpInputResponseValidator() {
	}

	static void validate(@NonNull McpJsonValue response) {
		requireNonNull(response);
		McpJsonObject object = requireObject(response);

		if (matches(() -> validateCreateMessageResult(object))
				|| matches(() -> validateListRootsResult(object))
				|| matches(() -> validateElicitResult(object)))
			return;

		throw invalid();
	}

	private static void validateCreateMessageResult(
			@NonNull McpJsonObject response) {
		Map<String, McpJsonValue> fields = response.members();
		validateSamplingContent(required(fields, "content"));
		requiredString(fields, "model");
		requireStringValue(required(fields, "role"), ROLES);
		optionalString(fields, "stopReason");
		optionalMetadata(fields);
	}

	private static void validateSamplingContent(@NonNull McpJsonValue content) {
		if (content instanceof McpJsonArray array) {
			for (McpJsonValue block : array.values())
				validateSamplingContentBlock(requireObject(block));
			return;
		}
		validateSamplingContentBlock(requireObject(content));
	}

	private static void validateSamplingContentBlock(
			@NonNull McpJsonObject block) {
		Map<String, McpJsonValue> fields = block.members();
		String type = requiredString(fields, "type");
		optionalMetadata(fields);

		switch (type) {
			case "text" -> {
				requiredString(fields, "text");
				optionalAnnotations(fields);
			}
			case "image", "audio" -> {
				requiredString(fields, "data");
				requiredString(fields, "mimeType");
				optionalAnnotations(fields);
			}
			case "tool_use" -> {
				requiredString(fields, "id");
				requiredObject(fields, "input");
				requiredString(fields, "name");
			}
			case "tool_result" -> validateToolResultContent(fields);
			default -> throw invalid();
		}
	}

	private static void validateToolResultContent(
			@NonNull Map<@NonNull String, @NonNull McpJsonValue> fields) {
		for (McpJsonValue content : requiredArray(fields, "content").values())
			validateContentBlock(requireObject(content));
		requiredString(fields, "toolUseId");
		optionalBoolean(fields, "isError");
	}

	private static void validateContentBlock(@NonNull McpJsonObject block) {
		Map<String, McpJsonValue> fields = block.members();
		String type = requiredString(fields, "type");
		optionalMetadata(fields);

		switch (type) {
			case "text" -> {
				requiredString(fields, "text");
				optionalAnnotations(fields);
			}
			case "image", "audio" -> {
				requiredString(fields, "data");
				requiredString(fields, "mimeType");
				optionalAnnotations(fields);
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
		optionalAnnotations(fields);
		if (fields.containsKey("icons")) {
			for (McpJsonValue value : requireArray(fields.get("icons")).values())
				validateIcon(requireObject(value));
		}
	}

	private static void validateIcon(@NonNull McpJsonObject icon) {
		Map<String, McpJsonValue> fields = icon.members();
		requireAbsoluteUri(requiredString(fields, "src"));
		optionalString(fields, "mimeType");
		if (fields.containsKey("sizes")) {
			for (McpJsonValue value : requireArray(fields.get("sizes")).values())
				requireString(value);
		}
		if (fields.containsKey("theme"))
			requireStringValue(fields.get("theme"), Set.of("dark", "light"));
	}

	private static void validateEmbeddedResource(
			@NonNull Map<@NonNull String, @NonNull McpJsonValue> fields) {
		optionalAnnotations(fields);
		McpJsonObject resource = requiredObject(fields, "resource");
		if (matches(() -> validateTextResourceContents(resource))
				|| matches(() -> validateBlobResourceContents(resource)))
			return;
		throw invalid();
	}

	private static void validateTextResourceContents(
			@NonNull McpJsonObject resource) {
		validateResourceContents(resource);
		requiredString(resource.members(), "text");
	}

	private static void validateBlobResourceContents(
			@NonNull McpJsonObject resource) {
		validateResourceContents(resource);
		requiredString(resource.members(), "blob");
	}

	private static void validateResourceContents(@NonNull McpJsonObject resource) {
		Map<String, McpJsonValue> fields = resource.members();
		requireAbsoluteUri(requiredString(fields, "uri"));
		optionalString(fields, "mimeType");
		optionalMetadata(fields);
	}

	private static void optionalAnnotations(
			@NonNull Map<@NonNull String, @NonNull McpJsonValue> fields) {
		if (!fields.containsKey("annotations"))
			return;
		Map<String, McpJsonValue> annotations =
			requireObject(fields.get("annotations")).members();
		if (annotations.containsKey("audience")) {
			for (McpJsonValue audience
					: requireArray(annotations.get("audience")).values())
				requireStringValue(audience, ROLES);
		}
		optionalString(annotations, "lastModified");
		if (annotations.containsKey("priority")) {
			BigDecimal priority = requireNumber(annotations.get("priority"));
			if (priority.compareTo(BigDecimal.ZERO) < 0
					|| priority.compareTo(BigDecimal.ONE) > 0)
				throw invalid();
		}
	}

	private static void validateListRootsResult(@NonNull McpJsonObject response) {
		for (McpJsonValue value
				: requiredArray(response.members(), "roots").values()) {
			Map<String, McpJsonValue> root = requireObject(value).members();
			String uri = requiredString(root, "uri");
			requireAbsoluteUri(uri);
			if (!uri.startsWith("file://"))
				throw invalid();
			optionalString(root, "name");
			optionalMetadata(root);
		}
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
	private static McpJsonObject requiredObject(
			@NonNull Map<@NonNull String, @NonNull McpJsonValue> fields,
			@NonNull String name) {
		return requireObject(required(fields, name));
	}

	private static void optionalMetadata(
			@NonNull Map<@NonNull String, @NonNull McpJsonValue> fields) {
		if (fields.containsKey("_meta"))
			McpProtocolSupport.requireInboundMetadataFields(
					requireObject(fields.get("_meta")), Set.of());
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
			@NonNull Set<@NonNull String> values) {
		String string = requireString(value);
		if (!values.contains(string))
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
			McpJsonIntegerSupport.toSerializableInteger(
					requireNumber(fields.get(name)), JSON_LIMITS);
	}

	private static void optionalBoolean(
			@NonNull Map<@NonNull String, @NonNull McpJsonValue> fields,
			@NonNull String name) {
		if (fields.containsKey(name)
				&& !(fields.get(name) instanceof McpJsonBoolean))
			throw invalid();
	}

	@NonNull
	private static BigDecimal requireNumber(@NonNull McpJsonValue value) {
		if (!(value instanceof McpJsonNumber number))
			throw invalid();
		return number.value();
	}

	private static void requireAbsoluteUri(@NonNull String value) {
		try {
			McpProtocolSupport.requireAbsoluteUri(URI.create(value), "URI");
		} catch (IllegalArgumentException exception) {
			throw invalid();
		}
	}

	@NonNull
	private static IllegalArgumentException invalid() {
		return new IllegalArgumentException(INVALID_MESSAGE);
	}
}
