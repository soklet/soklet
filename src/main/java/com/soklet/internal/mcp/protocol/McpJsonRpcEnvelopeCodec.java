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
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Bounded byte-to-envelope bridge. This layer classifies JSON-RPC shape only;
 * MCP request metadata and operation parameters are validated later.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class McpJsonRpcEnvelopeCodec {
	@NonNull
	private final McpJsonCodec jsonCodec;

	McpJsonRpcEnvelopeCodec(@NonNull McpJsonCodec jsonCodec) {
		this.jsonCodec = requireNonNull(jsonCodec);
	}

	@NonNull
	McpJsonRpcEnvelope decode(byte @NonNull [] utf8) {
		requireNonNull(utf8);

		try {
			return decodeValue(jsonCodec.parse(utf8));
		} catch (McpWireDecodingException exception) {
			throw exception;
		} catch (IllegalArgumentException exception) {
			throw McpWireDecodingException.parseError(exception);
		}
	}

	@NonNull
	McpJsonRpcEnvelope decode(@NonNull String json) {
		requireNonNull(json);

		try {
			return decodeValue(jsonCodec.parse(json));
		} catch (McpWireDecodingException exception) {
			throw exception;
		} catch (IllegalArgumentException exception) {
			throw McpWireDecodingException.parseError(exception);
		}
	}

	byte @NonNull [] encode(@NonNull McpJsonRpcEnvelope envelope) {
		return jsonCodec.toUtf8Bytes(requireNonNull(envelope).toJsonObject());
	}

	byte @NonNull [] encode(@NonNull McpJsonRpcMessage message) {
		return jsonCodec.toUtf8Bytes(requireNonNull(message).toJsonObject());
	}

	@NonNull
	String encodeToString(@NonNull McpJsonRpcEnvelope envelope) {
		return jsonCodec.toJson(requireNonNull(envelope).toJsonObject());
	}

	@NonNull
	String encodeToString(@NonNull McpJsonRpcMessage message) {
		return jsonCodec.toJson(requireNonNull(message).toJsonObject());
	}

	@NonNull
	private McpJsonRpcEnvelope decodeValue(@NonNull McpJsonValue value) {
		if (!(value instanceof McpJsonObject object))
			throw invalidEnvelope("The JSON-RPC message must be an object.", Optional.empty());

		Map<@NonNull String, @NonNull McpJsonValue> members = object.members();
		Optional<@NonNull McpJsonRpcId> readableId = readableId(members);
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

	@NonNull
	private McpJsonRpcEnvelope decodeMethodEnvelope(
			@NonNull Map<@NonNull String, @NonNull McpJsonValue> members,
			@NonNull Optional<@NonNull McpJsonRpcId> readableId) {
		rejectFields(members, readableId, "method envelope", "result", "error");
		McpJsonValue methodValue = members.get("method");

		if (!(methodValue instanceof McpJsonString method))
			throw invalidEnvelope("The method field must be a string.", readableId);

		Optional<@NonNull McpJsonValue> params = optionalField(members, "params");
		McpJsonObject extensionFields = extensionFields(members);

		if (!members.containsKey("id"))
			return new McpJsonRpcEnvelope.Notification(
					method.value(), params, extensionFields);

		return new McpJsonRpcEnvelope.Request(parseId(members.get("id")),
				method.value(), params, extensionFields);
	}

	@NonNull
	private McpJsonRpcEnvelope decodeResultEnvelope(
			@NonNull Map<@NonNull String, @NonNull McpJsonValue> members,
			@NonNull Optional<@NonNull McpJsonRpcId> readableId) {
		rejectFields(members, readableId, "result response", "method", "params", "error");

		if (!members.containsKey("id"))
			throw invalidEnvelope("A result response requires an id field.", readableId);

		return new McpJsonRpcEnvelope.ResultResponse(
				parseId(requireNonNull(members.get("id"))),
				requireNonNull(members.get("result")), extensionFields(members));
	}

	@NonNull
	private McpJsonRpcEnvelope decodeErrorEnvelope(
			@NonNull Map<@NonNull String, @NonNull McpJsonValue> members,
			@NonNull Optional<@NonNull McpJsonRpcId> readableId) {
		rejectFields(members, readableId, "error response", "method", "params", "result");
		Optional<@NonNull McpJsonRpcId> id = members.containsKey("id")
				? Optional.of(parseId(requireNonNull(members.get("id"))))
				: Optional.empty();
		return new McpJsonRpcEnvelope.ErrorResponse(
				id, requireNonNull(members.get("error")), extensionFields(members));
	}

	@NonNull
	private McpJsonRpcId parseId(@NonNull McpJsonValue value) {
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

	private void requireResponseSafeId(@NonNull McpJsonRpcId id) {
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

	@NonNull
	private Optional<@NonNull McpJsonRpcId> readableId(
			@NonNull Map<@NonNull String, @NonNull McpJsonValue> members) {
		if (!members.containsKey("id"))
			return Optional.empty();

		try {
			return Optional.of(parseId(members.get("id")));
		} catch (McpWireDecodingException exception) {
			return Optional.empty();
		}
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
	private McpJsonObject extensionFields(
			@NonNull Map<@NonNull String, @NonNull McpJsonValue> members) {
		Map<@NonNull String, @NonNull McpJsonValue> extensions = new LinkedHashMap<>();

		for (Map.Entry<@NonNull String, @NonNull McpJsonValue> entry : members.entrySet()) {
			if (!McpJsonRpcMessage.RESERVED_BASE_FIELDS.contains(entry.getKey()))
				extensions.put(entry.getKey(), entry.getValue());
		}

		return new McpJsonObject(extensions);
	}

	private void rejectFields(
			@NonNull Map<@NonNull String, @NonNull McpJsonValue> members,
			@NonNull Optional<@NonNull McpJsonRpcId> readableId,
			@NonNull String envelopeDescription, @NonNull String... fieldNames) {
		for (String fieldName : fieldNames) {
			if (members.containsKey(fieldName))
				throw invalidEnvelope("A " + envelopeDescription
						+ " must not contain the " + fieldName + " field.", readableId);
		}
	}

	@NonNull
	private McpWireDecodingException invalidEnvelope(@NonNull String message,
			@NonNull Optional<@NonNull McpJsonRpcId> readableId) {
		return McpWireDecodingException.invalidRequest(message, readableId);
	}
}
