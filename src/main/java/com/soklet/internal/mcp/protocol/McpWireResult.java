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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Provisional open wire result. Core factories enforce the invariants of the
 * result type they emit; the extension factory keeps the representation open
 * without advertising or enabling an extension in Soklet 3.6.
 */
final class McpWireResult {
	private static final Set<String> INPUT_REQUIRED_CLIENT_METHODS =
			Set.of("tools/call", "prompts/get", "resources/read");

	private final McpResultType resultType;
	private final McpJsonObject fields;
	private final Optional<McpResultMetadata> metadata;

	private McpWireResult(McpResultType resultType, McpJsonObject fields,
			Optional<McpResultMetadata> metadata) {
		this.resultType = requireNonNull(resultType);
		this.fields = McpProtocolSupport.requireExtensionFields(
				fields, Set.of("resultType", "_meta"));
		this.metadata = requireNonNull(metadata);
	}

	static McpWireResult complete(McpJsonObject fields) {
		return complete(fields, Optional.empty());
	}

	static McpWireResult complete(McpJsonObject fields,
			Optional<McpResultMetadata> metadata) {
		return new McpWireResult(McpResultType.COMPLETE, fields, metadata);
	}

	static McpWireResult inputRequired(String clientRequestMethod,
			Optional<McpInputRequests> inputRequests, Optional<String> requestState,
			Optional<McpResultMetadata> metadata, McpJsonObject extensionFields) {
		requireNonNull(clientRequestMethod);
		requireNonNull(inputRequests);
		requireNonNull(requestState);
		requireNonNull(metadata);

		if (!supportsInputRequired(clientRequestMethod))
			throw new IllegalArgumentException(
					"Input-required results are not permitted for " + clientRequestMethod + ".");

		if (inputRequests.isEmpty() && requestState.isEmpty())
			throw new IllegalArgumentException(
					"Input-required results need inputRequests, requestState, or both.");

		McpJsonObject fields = McpProtocolSupport.requireExtensionFields(
				extensionFields, Set.of("inputRequests", "requestState"));
		Map<String, McpJsonValue> values = new LinkedHashMap<>(fields.members());
		inputRequests.ifPresent(value -> values.put("inputRequests", value.toJsonObject()));
		requestState.ifPresent(value -> values.put("requestState", new McpJsonString(value)));
		return new McpWireResult(McpResultType.INPUT_REQUIRED,
				new McpJsonObject(values), metadata);
	}

	static McpWireResult extension(McpResultType resultType, McpJsonObject fields,
			Optional<McpResultMetadata> metadata) {
		requireNonNull(resultType);

		if (resultType.isCore())
			throw new IllegalArgumentException(
					"Use a core result factory for result type '" + resultType.wireValue() + "'.");

		return new McpWireResult(resultType, fields, metadata);
	}

	static boolean supportsInputRequired(String clientRequestMethod) {
		return INPUT_REQUIRED_CLIENT_METHODS.contains(requireNonNull(clientRequestMethod));
	}

	McpResultType resultType() {
		return resultType;
	}

	McpJsonObject fields() {
		return fields;
	}

	Optional<McpResultMetadata> metadata() {
		return metadata;
	}

	McpJsonObject toJsonObject() {
		Map<String, McpJsonValue> values = new LinkedHashMap<>(fields.members());
		values.put("resultType", new McpJsonString(resultType.wireValue()));
		metadata.filter(value -> !value.isEmpty())
				.ifPresent(value -> values.put("_meta", value.toJsonObject()));
		return new McpJsonObject(values);
	}
}

record McpResultType(String wireValue) {
	static final McpResultType COMPLETE = new McpResultType("complete");
	static final McpResultType INPUT_REQUIRED = new McpResultType("input_required");

	McpResultType {
		wireValue = McpProtocolSupport.requireNonBlank(wireValue, "Result type");
	}

	static McpResultType extension(String wireValue) {
		McpResultType resultType = new McpResultType(wireValue);

		if (resultType.isCore())
			throw new IllegalArgumentException("Core result type is not an extension.");

		return resultType;
	}

	boolean isCore() {
		return COMPLETE.equals(this) || INPUT_REQUIRED.equals(this);
	}
}

record McpEmbeddedInputRequest(McpInputRequestDeclaration declaration,
		McpJsonObject params, McpJsonObject extensionFields) {
	McpEmbeddedInputRequest {
		requireNonNull(declaration);
		requireNonNull(params);
		extensionFields = McpProtocolSupport.requireExtensionFields(
				extensionFields, Set.of("method", "params"));
	}

	static McpEmbeddedInputRequest fromDeclaration(
			McpInputRequestDeclaration declaration, McpJsonObject params) {
		return new McpEmbeddedInputRequest(declaration, requireNonNull(params),
				McpJsonObject.empty());
	}

	McpJsonObject toJsonObject() {
		Map<String, McpJsonValue> values = new LinkedHashMap<>(extensionFields.members());
		values.put("method", new McpJsonString(declaration.method()));
		values.put("params", params);
		return new McpJsonObject(values);
	}
}

final class McpInputRequests {
	private final Map<String, McpEmbeddedInputRequest> requests;

	private McpInputRequests(Map<String, McpEmbeddedInputRequest> requests) {
		requireNonNull(requests);
		Map<String, McpEmbeddedInputRequest> copiedRequests =
				new LinkedHashMap<>(requests.size());

		for (Map.Entry<String, McpEmbeddedInputRequest> entry : requests.entrySet())
			copiedRequests.put(requireNonNull(entry.getKey()), requireNonNull(entry.getValue()));

		this.requests = Collections.unmodifiableMap(copiedRequests);
	}

	static Builder builder() {
		return new Builder();
	}

	Map<String, McpEmbeddedInputRequest> requests() {
		return requests;
	}

	McpJsonObject toJsonObject() {
		Map<String, McpJsonValue> values = new LinkedHashMap<>(requests.size());

		for (Map.Entry<String, McpEmbeddedInputRequest> entry : requests.entrySet())
			values.put(entry.getKey(), entry.getValue().toJsonObject());

		return new McpJsonObject(values);
	}

	static final class Builder {
		private final Map<String, McpEmbeddedInputRequest> requests = new LinkedHashMap<>();

		Builder inputRequest(String key, McpEmbeddedInputRequest inputRequest) {
			requireNonNull(key);
			requireNonNull(inputRequest);

			if (requests.putIfAbsent(key, inputRequest) != null)
				throw new IllegalArgumentException("Duplicate input-request key '" + key + "'.");

			return this;
		}

		McpInputRequests build() {
			return new McpInputRequests(requests);
		}
	}
}
