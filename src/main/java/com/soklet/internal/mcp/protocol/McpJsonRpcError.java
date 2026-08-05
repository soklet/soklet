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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
record McpJsonRpcError(int code, @NonNull String message,
		@NonNull Optional<@NonNull McpJsonValue> data) {
	static final int PARSE_ERROR = -32700;
	static final int INVALID_REQUEST = -32600;
	static final int METHOD_NOT_FOUND = -32601;
	static final int INVALID_PARAMS = -32602;
	static final int INTERNAL_ERROR = -32603;
	static final int HEADER_MISMATCH = -32020;
	static final int MISSING_REQUIRED_CLIENT_CAPABILITY = -32021;
	static final int UNSUPPORTED_PROTOCOL_VERSION = -32022;

	McpJsonRpcError {
		requireNonNull(message);
		requireNonNull(data);
		if (code >= -32768 && code <= -32000 && !isDefinedReservedCode(code))
			throw new IllegalArgumentException(
					"The JSON-RPC/MCP reserved range may contain only defined error codes.");
	}

	private static boolean isDefinedReservedCode(int code) {
		return switch (code) {
			case PARSE_ERROR, INVALID_REQUEST, METHOD_NOT_FOUND, INVALID_PARAMS,
					INTERNAL_ERROR, HEADER_MISMATCH, MISSING_REQUIRED_CLIENT_CAPABILITY,
					UNSUPPORTED_PROTOCOL_VERSION -> true;
			default -> false;
		};
	}

	@NonNull
	McpJsonObject toJsonObject() {
		Map<@NonNull String, @NonNull McpJsonValue> values = new LinkedHashMap<>();
		values.put("code", new McpJsonNumber(code));
		values.put("message", new McpJsonString(message));
		data.ifPresent(value -> values.put("data", value));
		return new McpJsonObject(values);
	}

	@NonNull
	static McpJsonRpcError unsupportedProtocolVersion(@NonNull String requestedVersion) {
		Map<@NonNull String, @NonNull McpJsonValue> values = new LinkedHashMap<>();
		List<@NonNull McpJsonValue> supportedVersions =
				new ArrayList<>(McpProtocolVersion.SUPPORTED.size());

		for (String supportedVersion : McpProtocolVersion.SUPPORTED)
			supportedVersions.add(new McpJsonString(supportedVersion));

		values.put("supported", new McpJsonArray(supportedVersions));
		values.put("requested", new McpJsonString(requireNonNull(requestedVersion)));
		return new McpJsonRpcError(UNSUPPORTED_PROTOCOL_VERSION,
				"Unsupported protocol version", Optional.of(new McpJsonObject(values)));
	}

	@NonNull
	static McpJsonRpcError missingRequiredClientCapabilities(
			@NonNull Set<@NonNull McpClientCapabilityRequirement> missingCapabilities) {
		requireNonNull(missingCapabilities);

		if (missingCapabilities.isEmpty())
			throw new IllegalArgumentException("At least one missing capability is required.");

		McpJsonObject requiredCapabilities =
				McpClientCapabilities.fromRequirements(missingCapabilities).toJsonObject();
		McpJsonObject data = new McpJsonObject(
				Map.of("requiredCapabilities", requiredCapabilities));
		return new McpJsonRpcError(MISSING_REQUIRED_CLIENT_CAPABILITY,
				"Missing required client capability", Optional.of(data));
	}
}
