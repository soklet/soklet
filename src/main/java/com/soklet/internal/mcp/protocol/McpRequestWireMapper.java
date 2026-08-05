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
import java.math.BigInteger;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Maps the universal MCP request spine after JSON-RPC classification. Method-
 * specific parameter validation deliberately remains in operation mappers.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class McpRequestWireMapper {
	@NonNull
	private static final Set<@NonNull String> REQUEST_METADATA_FIELDS = Set.of(
			McpRequestMetadata.PROTOCOL_VERSION_KEY,
			McpRequestMetadata.CLIENT_CAPABILITIES_KEY,
			McpRequestMetadata.CLIENT_INFORMATION_KEY,
			McpRequestMetadata.LOG_LEVEL_KEY,
			McpRequestMetadata.PROGRESS_TOKEN_KEY);
	@NonNull
	private static final Set<@NonNull String> CLIENT_CAPABILITY_FIELDS = Set.of(
			"elicitation", "roots", "sampling", "extensions", "experimental");
	@NonNull
	private static final Set<@NonNull String> IMPLEMENTATION_FIELDS = Set.of(
			"name", "version", "title", "description", "websiteUrl", "icons");
	@NonNull
	private static final Set<@NonNull String> ICON_FIELDS = Set.of(
			"src", "mimeType", "sizes", "theme");
	@NonNull
	private final McpJsonLimits jsonLimits;

	McpRequestWireMapper(@NonNull McpJsonLimits jsonLimits) {
		this.jsonLimits = requireNonNull(jsonLimits);
	}

	McpJsonRpcMessage.@NonNull Request map(
			McpJsonRpcEnvelope.@NonNull Request request) {
		requireNonNull(request);
		McpJsonRpcId requestId = request.id();

		try {
			McpJsonObject params = requireObject(request.params(), "Request params");
			McpJsonObject metadataObject = requireObject(
					requiredField(params.members(), "_meta"), "Request _meta");
			McpRequestMetadata metadata = parseMetadata(metadataObject);
			McpJsonObject parameterFields = fieldsExcept(params, Set.of("_meta"));
			McpRequestParameters mappedParams =
					new McpRequestParameters(metadata, parameterFields);
			return new McpJsonRpcMessage.Request(requestId, request.method(),
					mappedParams, request.extensionFields());
		} catch (McpWireDecodingException exception) {
			throw exception;
		} catch (IllegalArgumentException exception) {
			throw invalidParams(requestId,
					"Request parameters do not match the MCP wire contract.");
		}
	}

	@NonNull
	private McpRequestMetadata parseMetadata(@NonNull McpJsonObject metadataObject) {
		Map<String, McpJsonValue> members = metadataObject.members();
		String protocolVersion = requireString(requiredField(
				members, McpRequestMetadata.PROTOCOL_VERSION_KEY),
				"Protocol version");
		McpClientCapabilities clientCapabilities = parseClientCapabilities(
				requireObject(requiredField(members,
						McpRequestMetadata.CLIENT_CAPABILITIES_KEY),
						"Client capabilities"));
		Optional<McpImplementationMetadata> clientInformation =
				optionalField(members, McpRequestMetadata.CLIENT_INFORMATION_KEY)
						.map(value -> parseImplementation(
								requireObject(value, "Client information")));
		Optional<McpRequestLogLevel> logLevel =
				optionalField(members, McpRequestMetadata.LOG_LEVEL_KEY)
						.map(value -> parseLogLevel(
								requireString(value, "Deprecated log level")));
		Optional<McpProgressToken> progressToken =
				optionalField(members, McpRequestMetadata.PROGRESS_TOKEN_KEY)
						.map(this::parseProgressToken);
		McpJsonObject extensionFields = fieldsExcept(
				metadataObject, REQUEST_METADATA_FIELDS);

		try {
			return new McpRequestMetadata(protocolVersion, clientCapabilities,
					clientInformation, logLevel, progressToken, extensionFields);
		} catch (IllegalArgumentException exception) {
			throw new IllegalArgumentException("Request metadata is invalid.");
		}
	}

	@NonNull
	private McpClientCapabilities parseClientCapabilities(@NonNull McpJsonObject object) {
		Map<String, McpJsonValue> members = object.members();
		Optional<McpJsonObject> elicitation = optionalObject(members, "elicitation",
				"Elicitation capability");
		Optional<McpJsonObject> roots = optionalObject(members, "roots",
				"Roots capability");
		Optional<McpJsonObject> sampling = optionalObject(members, "sampling",
				"Sampling capability");
		Map<String, McpJsonObject> extensions = optionalObject(members, "extensions",
				"Extension capabilities").map(value -> objectValues(value,
						"Extension capability settings")).orElseGet(Map::of);
		Map<String, McpJsonObject> experimental = optionalObject(members, "experimental",
				"Experimental capabilities").map(value -> objectValues(value,
						"Experimental capability settings")).orElseGet(Map::of);
		McpJsonObject unknownObject = fieldsExcept(object, CLIENT_CAPABILITY_FIELDS);

		try {
			return new McpClientCapabilities(elicitation, roots, sampling,
					extensions, experimental, unknownObject.members());
		} catch (IllegalArgumentException exception) {
			throw new IllegalArgumentException("Client capabilities are invalid.");
		}
	}

	@NonNull
	private McpImplementationMetadata parseImplementation(@NonNull McpJsonObject object) {
		Map<String, McpJsonValue> members = object.members();
		String name = requireString(requiredField(members, "name"),
				"Implementation name");
		String version = requireString(requiredField(members, "version"),
				"Implementation version");
		Optional<String> title = optionalString(members, "title", "Implementation title");
		Optional<String> description = optionalString(
				members, "description", "Implementation description");
		Optional<URI> websiteUrl = optionalString(
				members, "websiteUrl", "Implementation website URL")
				.map(value -> parseAbsoluteUri(value, "Implementation website URL"));
		List<McpImplementationMetadata.Icon> icons = optionalField(members, "icons")
				.map(value -> parseIcons(requireArray(value, "Implementation icons")))
				.orElseGet(List::of);
		return new McpImplementationMetadata(name, version, title, description,
				websiteUrl, icons, fieldsExcept(object, IMPLEMENTATION_FIELDS));
	}

	@NonNull
	private List<McpImplementationMetadata.@NonNull Icon> parseIcons(
			@NonNull McpJsonArray array) {
		List<McpImplementationMetadata.Icon> icons = new ArrayList<>(array.values().size());

		for (McpJsonValue value : array.values()) {
			McpJsonObject object = requireObject(value, "Implementation icon");
			Map<String, McpJsonValue> members = object.members();
			URI source = parseAbsoluteUri(requireString(
					requiredField(members, "src"), "Icon source"), "Icon source");
			Optional<String> mimeType = optionalString(members, "mimeType", "Icon MIME type");
			List<String> sizes = optionalField(members, "sizes")
					.map(item -> parseStringArray(requireArray(item, "Icon sizes"), "Icon size"))
					.orElseGet(List::of);
			Optional<McpImplementationMetadata.Theme> theme = optionalString(
					members, "theme", "Icon theme").map(this::parseTheme);
			icons.add(new McpImplementationMetadata.Icon(source, mimeType, sizes,
					theme, fieldsExcept(object, ICON_FIELDS)));
		}

		return List.copyOf(icons);
	}

	@NonNull
	private List<@NonNull String> parseStringArray(@NonNull McpJsonArray array,
			@NonNull String description) {
		List<String> strings = new ArrayList<>(array.values().size());

		for (McpJsonValue value : array.values())
			strings.add(requireString(value, description));

		return List.copyOf(strings);
	}

	@NonNull
	private McpRequestLogLevel parseLogLevel(@NonNull String wireValue) {
		for (McpRequestLogLevel level : McpRequestLogLevel.values()) {
			if (level.wireValue().equals(wireValue))
				return level;
		}

		throw new IllegalArgumentException("Deprecated log level is invalid.");
	}

	private McpImplementationMetadata.@NonNull Theme parseTheme(
			@NonNull String wireValue) {
		return switch (wireValue) {
			case "light" -> McpImplementationMetadata.Theme.LIGHT;
			case "dark" -> McpImplementationMetadata.Theme.DARK;
			default -> throw new IllegalArgumentException("Icon theme is invalid.");
		};
	}

	@NonNull
	private McpProgressToken parseProgressToken(@NonNull McpJsonValue value) {
		if (value instanceof McpJsonString string)
			return new McpProgressToken.StringToken(string.value());

		if (value instanceof McpJsonNumber number) {
			try {
				BigInteger integer = McpJsonIntegerSupport.toSerializableInteger(
						number.value(), jsonLimits);
				return new McpProgressToken.IntegerToken(integer);
			} catch (ArithmeticException exception) {
				throw new IllegalArgumentException("Progress token must be a string or integer.");
			}
		}

		throw new IllegalArgumentException("Progress token must be a string or integer.");
	}

	@NonNull
	private URI parseAbsoluteUri(@NonNull String value,
			@NonNull String description) {
		return McpProtocolSupport.requireAbsoluteUri(URI.create(value), description);
	}

	@NonNull
	private Optional<@NonNull McpJsonObject> optionalObject(
			@NonNull Map<@NonNull String, @NonNull McpJsonValue> members,
			@NonNull String fieldName, @NonNull String description) {
		return optionalField(members, fieldName)
				.map(value -> requireObject(value, description));
	}

	@NonNull
	private Map<@NonNull String, @NonNull McpJsonObject> objectValues(
			@NonNull McpJsonObject object, @NonNull String description) {
		Map<String, McpJsonObject> objects = new LinkedHashMap<>(object.members().size());

		for (Map.Entry<String, McpJsonValue> entry : object.members().entrySet())
			objects.put(entry.getKey(), requireObject(entry.getValue(), description));

		return objects;
	}

	@NonNull
	private Optional<@NonNull String> optionalString(
			@NonNull Map<@NonNull String, @NonNull McpJsonValue> members,
			@NonNull String fieldName, @NonNull String description) {
		return optionalField(members, fieldName)
				.map(value -> requireString(value, description));
	}

	@NonNull
	private Optional<@NonNull McpJsonValue> optionalField(
			@NonNull Map<@NonNull String, @NonNull McpJsonValue> members,
			@NonNull String fieldName) {
		return members.containsKey(fieldName)
				? Optional.of(members.get(fieldName))
				: Optional.empty();
	}

	@NonNull
	private McpJsonValue requiredField(
			@NonNull Map<@NonNull String, @NonNull McpJsonValue> members,
			@NonNull String fieldName) {
		if (!members.containsKey(fieldName))
			throw new IllegalArgumentException("A required request field is absent.");

		return members.get(fieldName);
	}

	@NonNull
	private McpJsonObject requireObject(
			@NonNull Optional<@NonNull McpJsonValue> optionalValue,
			@NonNull String description) {
		if (optionalValue.isEmpty())
			throw new IllegalArgumentException(description + " is required.");

		return requireObject(optionalValue.orElseThrow(), description);
	}

	@NonNull
	private McpJsonObject requireObject(@NonNull McpJsonValue value,
			@NonNull String description) {
		if (!(value instanceof McpJsonObject object))
			throw new IllegalArgumentException(description + " must be an object.");

		return object;
	}

	@NonNull
	private McpJsonArray requireArray(@NonNull McpJsonValue value,
			@NonNull String description) {
		if (!(value instanceof McpJsonArray array))
			throw new IllegalArgumentException(description + " must be an array.");

		return array;
	}

	@NonNull
	private String requireString(@NonNull McpJsonValue value,
			@NonNull String description) {
		if (!(value instanceof McpJsonString string))
			throw new IllegalArgumentException(description + " must be a string.");

		return string.value();
	}

	@NonNull
	private McpJsonObject fieldsExcept(@NonNull McpJsonObject object,
			@NonNull Set<@NonNull String> excludedFields) {
		Map<String, McpJsonValue> fields = new LinkedHashMap<>();

		for (Map.Entry<String, McpJsonValue> entry : object.members().entrySet()) {
			if (!excludedFields.contains(entry.getKey()))
				fields.put(entry.getKey(), entry.getValue());
		}

		return new McpJsonObject(fields);
	}

	@NonNull
	private McpWireDecodingException invalidParams(
			@NonNull McpJsonRpcId requestId, @NonNull String message) {
		return McpWireDecodingException.invalidParams(message, requestId);
	}
}
