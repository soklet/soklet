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

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Bounded byte-to-envelope bridge. This layer classifies JSON-RPC shape only;
 * MCP request metadata and operation parameters are validated later.
 */
final class McpJsonRpcEnvelopeCodec {
	private final McpJsonCodec jsonCodec;

	McpJsonRpcEnvelopeCodec(McpJsonCodec jsonCodec) {
		this.jsonCodec = requireNonNull(jsonCodec);
	}

	McpJsonRpcEnvelope decode(byte[] utf8) {
		requireNonNull(utf8);

		try {
			return decodeValue(jsonCodec.parse(utf8));
		} catch (McpWireDecodingException exception) {
			throw exception;
		} catch (IllegalArgumentException exception) {
			throw McpWireDecodingException.parseError(exception);
		}
	}

	McpJsonRpcEnvelope decode(String json) {
		requireNonNull(json);

		try {
			return decodeValue(jsonCodec.parse(json));
		} catch (McpWireDecodingException exception) {
			throw exception;
		} catch (IllegalArgumentException exception) {
			throw McpWireDecodingException.parseError(exception);
		}
	}

	byte[] encode(McpJsonRpcEnvelope envelope) {
		return jsonCodec.toUtf8Bytes(requireNonNull(envelope).toJsonObject());
	}

	byte[] encode(McpJsonRpcMessage message) {
		return jsonCodec.toUtf8Bytes(requireNonNull(message).toJsonObject());
	}

	String encodeToString(McpJsonRpcEnvelope envelope) {
		return jsonCodec.toJson(requireNonNull(envelope).toJsonObject());
	}

	String encodeToString(McpJsonRpcMessage message) {
		return jsonCodec.toJson(requireNonNull(message).toJsonObject());
	}

	private McpJsonRpcEnvelope decodeValue(McpJsonValue value) {
		if (!(value instanceof McpJsonObject object))
			throw invalidEnvelope("The JSON-RPC message must be an object.", Optional.empty());

		Map<String, McpJsonValue> members = object.members();
		Optional<McpJsonRpcId> readableId = readableId(members);
		McpJsonValue versionValue = members.get("jsonrpc");

		if (!(versionValue instanceof McpJsonString version)
				|| !McpJsonRpcMessage.JSON_RPC_VERSION.equals(version.value()))
			throw invalidEnvelope("The jsonrpc field must be the string '2.0'.", readableId);

		if (members.containsKey("method"))
			return decodeMethodEnvelope(members, readableId);

		if (members.containsKey("result"))
			return decodeResultEnvelope(members, readableId);

		if (members.containsKey("error"))
			return decodeErrorEnvelope(members, readableId);

		throw invalidEnvelope("The JSON-RPC message has no classifiable payload.", readableId);
	}

	private McpJsonRpcEnvelope decodeMethodEnvelope(Map<String, McpJsonValue> members,
			Optional<McpJsonRpcId> readableId) {
		rejectFields(members, readableId, "method envelope", "result", "error");
		McpJsonValue methodValue = members.get("method");

		if (!(methodValue instanceof McpJsonString method))
			throw invalidEnvelope("The method field must be a string.", readableId);

		Optional<McpJsonValue> params = optionalField(members, "params");
		McpJsonObject extensionFields = extensionFields(members);

		if (!members.containsKey("id"))
			return new McpJsonRpcEnvelope.Notification(
					method.value(), params, extensionFields);

		return new McpJsonRpcEnvelope.Request(parseId(members.get("id")),
				method.value(), params, extensionFields);
	}

	private McpJsonRpcEnvelope decodeResultEnvelope(Map<String, McpJsonValue> members,
			Optional<McpJsonRpcId> readableId) {
		rejectFields(members, readableId, "result response", "method", "params", "error");

		if (!members.containsKey("id"))
			throw invalidEnvelope("A result response requires an id field.", readableId);

		return new McpJsonRpcEnvelope.ResultResponse(
				parseId(requireNonNull(members.get("id"))),
				requireNonNull(members.get("result")), extensionFields(members));
	}

	private McpJsonRpcEnvelope decodeErrorEnvelope(Map<String, McpJsonValue> members,
			Optional<McpJsonRpcId> readableId) {
		rejectFields(members, readableId, "error response", "method", "params", "result");
		Optional<McpJsonRpcId> id = members.containsKey("id")
				? Optional.of(parseId(requireNonNull(members.get("id"))))
				: Optional.empty();
		return new McpJsonRpcEnvelope.ErrorResponse(
				id, requireNonNull(members.get("error")), extensionFields(members));
	}

	private McpJsonRpcId parseId(McpJsonValue value) {
		McpJsonRpcId id;

		if (value instanceof McpJsonString string) {
			id = new McpJsonRpcId.StringId(string.value());
			requireResponseSafeId(id);
			return id;
		}

		if (value instanceof McpJsonNumber number) {
			BigDecimal decimal = number.value();
			BigInteger integer;

			try {
				integer = McpJsonIntegerSupport.toSerializableInteger(
						decimal, jsonCodec.limits());
			} catch (IllegalArgumentException exception) {
				throw invalidEnvelope("The id field must be a string or integer.", Optional.empty());
			}

			id = new McpJsonRpcId.IntegerId(integer);
			requireResponseSafeId(id);
			return id;
		}

		throw invalidEnvelope("The id field must be a string or integer.", Optional.empty());
	}

	private void requireResponseSafeId(McpJsonRpcId id) {
		McpJsonRpcMessage.ErrorResponse fallback = new McpJsonRpcMessage.ErrorResponse(
				Optional.of(id),
				new McpJsonRpcError(McpJsonRpcError.INTERNAL_ERROR,
						"Internal error", Optional.empty()),
				McpJsonObject.empty());

		try {
			jsonCodec.toUtf8Bytes(fallback.toJsonObject());
		} catch (IllegalArgumentException exception) {
			throw invalidEnvelope("The id field cannot be correlated within the configured output limit.",
					Optional.empty());
		}
	}

	private Optional<McpJsonRpcId> readableId(Map<String, McpJsonValue> members) {
		if (!members.containsKey("id"))
			return Optional.empty();

		try {
			return Optional.of(parseId(members.get("id")));
		} catch (McpWireDecodingException exception) {
			return Optional.empty();
		}
	}

	private Optional<McpJsonValue> optionalField(
			Map<String, McpJsonValue> members, String fieldName) {
		return members.containsKey(fieldName)
				? Optional.of(members.get(fieldName))
				: Optional.empty();
	}

	private McpJsonObject extensionFields(Map<String, McpJsonValue> members) {
		Map<String, McpJsonValue> extensions = new LinkedHashMap<>();

		for (Map.Entry<String, McpJsonValue> entry : members.entrySet()) {
			if (!McpJsonRpcMessage.RESERVED_BASE_FIELDS.contains(entry.getKey()))
				extensions.put(entry.getKey(), entry.getValue());
		}

		return new McpJsonObject(extensions);
	}

	private void rejectFields(Map<String, McpJsonValue> members,
			Optional<McpJsonRpcId> readableId, String envelopeDescription,
			String... fieldNames) {
		for (String fieldName : fieldNames) {
			if (members.containsKey(fieldName))
				throw invalidEnvelope("A " + envelopeDescription
						+ " must not contain the " + fieldName + " field.", readableId);
		}
	}

	private McpWireDecodingException invalidEnvelope(String message,
			Optional<McpJsonRpcId> readableId) {
		return McpWireDecodingException.invalidRequest(message, readableId);
	}
}
