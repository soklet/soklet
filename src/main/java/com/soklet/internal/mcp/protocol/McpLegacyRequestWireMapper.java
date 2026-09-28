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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Adapts 2025-era request metadata into the canonical request spine. Later
 * HTTP requests intentionally carry no remembered initialization capabilities.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class McpLegacyRequestWireMapper {
	private McpLegacyRequestWireMapper() {
	}

	static McpJsonRpcMessage.@NonNull Request map(
			@NonNull McpRequestWireMapper mapper,
			@NonNull String selectedRevision,
			McpJsonRpcEnvelope.@NonNull Request request) {
		requireNonNull(mapper);
		requireNonNull(selectedRevision);
		requireNonNull(request);
		if (!McpLegacyHttpWire.isLegacyRevision(selectedRevision))
			throw new IllegalArgumentException("An exact 2025 revision is required.");

		McpJsonObject params;
		if (request.params().isEmpty())
			params = McpJsonObject.empty();
		else if (request.params().orElseThrow() instanceof McpJsonObject object)
			params = object;
		else
			throw invalidParams(request);

		Optional<Initialization> initialization = "initialize".equals(request.method())
				? Optional.of(parseInitialization(params, request)) : Optional.empty();

		Map<String, McpJsonValue> fields = new LinkedHashMap<>(params.members());
		McpJsonValue rawMetadata = fields.remove("_meta");
		Map<String, McpJsonValue> metadata = new LinkedHashMap<>();
		if (rawMetadata != null) {
			if (!(rawMetadata instanceof McpJsonObject object))
				throw invalidParams(request);
			if (object.members().containsKey(McpRequestMetadata.PROTOCOL_VERSION_KEY)
					|| object.members().containsKey(McpRequestMetadata.CLIENT_CAPABILITIES_KEY)
					|| object.members().containsKey(McpRequestMetadata.CLIENT_INFORMATION_KEY)
					|| object.members().containsKey(McpRequestMetadata.LOG_LEVEL_KEY))
				throw invalidParams(request);
			metadata.putAll(object.members());
		}
		metadata.put(McpRequestMetadata.PROTOCOL_VERSION_KEY,
				new McpJsonString(selectedRevision));
		metadata.put(McpRequestMetadata.CLIENT_CAPABILITIES_KEY,
				initialization.map(Initialization::clientCapabilities)
						.orElseGet(() -> McpClientCapabilities.empty().toJsonObject()));
		initialization.ifPresent(value -> metadata.put(
				McpRequestMetadata.CLIENT_INFORMATION_KEY,
				value.clientInformation()));
		fields.put("_meta", new McpJsonObject(metadata));
		try {
			return mapper.map(new McpJsonRpcEnvelope.Request(request.id(),
					request.method(), Optional.of(new McpJsonObject(fields)),
					request.extensionFields()));
		} catch (McpWireDecodingException exception) {
			throw exception;
		} catch (IllegalArgumentException exception) {
			throw invalidParams(request);
		}
	}

	static @NonNull Initialization parseInitialization(
			@NonNull McpJsonObject params,
			McpJsonRpcEnvelope.@NonNull Request request) {
		requireNonNull(params);
		requireNonNull(request);
		Map<String, McpJsonValue> fields = params.members();
		if (!(fields.get("protocolVersion") instanceof McpJsonString version)
				|| version.value().isBlank()
				|| !(fields.get("capabilities") instanceof McpJsonObject capabilities)
				|| !(fields.get("clientInfo") instanceof McpJsonObject clientInfo)
				|| !(clientInfo.members().get("name") instanceof McpJsonString name)
				|| !(clientInfo.members().get("version") instanceof McpJsonString clientVersion)
				|| name.value().isBlank() || clientVersion.value().isBlank())
			throw invalidParams(request);
		validateCapabilities(capabilities, request);
		return new Initialization(version.value(), capabilities, clientInfo);
	}

	private static void validateCapabilities(@NonNull McpJsonObject capabilities,
			McpJsonRpcEnvelope.@NonNull Request request) {
		Map<String, McpJsonValue> fields = capabilities.members();
		McpJsonObject roots = optionalObject(fields, "roots", request).orElse(null);
		if (roots != null && roots.members().containsKey("listChanged")
				&& !(roots.members().get("listChanged") instanceof McpJsonBoolean))
			throw invalidParams(request);
		optionalObject(fields, "sampling", request);
		optionalObject(fields, "elicitation", request);
		optionalObject(fields, "experimental", request).ifPresent(experimental -> {
			for (McpJsonValue value : experimental.members().values())
				if (!(value instanceof McpJsonObject))
					throw invalidParams(request);
		});
		optionalObject(fields, "tasks", request).ifPresent(tasks -> {
			optionalObject(tasks.members(), "list", request);
			optionalObject(tasks.members(), "cancel", request);
			optionalObject(tasks.members(), "requests", request).ifPresent(requests -> {
				optionalObject(requests.members(), "sampling", request)
						.ifPresent(sampling -> optionalObject(sampling.members(),
								"createMessage", request));
				optionalObject(requests.members(), "elicitation", request)
						.ifPresent(elicitation -> optionalObject(elicitation.members(),
								"create", request));
			});
		});
	}

	private static Optional<McpJsonObject> optionalObject(
			@NonNull Map<String, McpJsonValue> fields, @NonNull String name,
			McpJsonRpcEnvelope.@NonNull Request request) {
		McpJsonValue value = fields.get(name);
		if (value == null)
			return Optional.empty();
		if (!(value instanceof McpJsonObject object))
			throw invalidParams(request);
		return Optional.of(object);
	}

	private static McpWireDecodingException invalidParams(
			McpJsonRpcEnvelope.@NonNull Request request) {
		return McpWireDecodingException.invalidParams(
				"Request parameters do not match the 2025 MCP wire contract.",
				requireNonNull(request).id());
	}

	record Initialization(@NonNull String requestedRevision,
			@NonNull McpJsonObject clientCapabilities,
			@NonNull McpJsonObject clientInformation) {
		Initialization {
			requireNonNull(requestedRevision);
			requireNonNull(clientCapabilities);
			requireNonNull(clientInformation);
		}
	}
}
