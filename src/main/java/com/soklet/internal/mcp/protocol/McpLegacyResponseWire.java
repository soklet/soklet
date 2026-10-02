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

import static java.util.Objects.requireNonNull;

/**
 * Converts canonical 2026 result envelopes at the final 2025 HTTP boundary.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class McpLegacyResponseWire {
	private McpLegacyResponseWire() {
	}

	static byte @NonNull [] encode(@NonNull McpJsonCodec jsonCodec,
			McpJsonRpcMessage.@NonNull ResultResponse response) {
		return requireNonNull(jsonCodec).toUtf8Bytes(projectEnvelope(response));
	}

	static @NonNull McpJsonObject projectEnvelope(
			McpJsonRpcMessage.@NonNull ResultResponse response) {
		requireNonNull(response);
		Map<String, McpJsonValue> envelope = new LinkedHashMap<>();
		envelope.put("jsonrpc", new McpJsonString(McpJsonRpcMessage.JSON_RPC_VERSION));
		envelope.put("id", response.id().toJsonValue());
		envelope.put("result", projectResult(response.result()));
		return new McpJsonObject(envelope);
	}

	static @NonNull McpJsonObject projectResult(@NonNull McpWireResult result) {
		requireNonNull(result);
		if (!McpResultType.COMPLETE.equals(result.resultType()))
			throw new IllegalArgumentException(
					"The 2025 adapter cannot encode a non-complete result.");
		Map<String, McpJsonValue> fields = new LinkedHashMap<>(
				result.toJsonObject().members());
		fields.remove("resultType");
		fields.remove("_meta");
		result.metadata().map(McpResultMetadata::extensionFields)
				.filter(value -> !value.members().isEmpty())
				.ifPresent(value -> fields.put("_meta", value));
		return new McpJsonObject(fields);
	}

	static @NonNull McpJsonObject projectServerInformation(
			@NonNull String revision,
			@NonNull McpImplementationMetadata serverInformation) {
		if (!McpLegacyHttpWire.isLegacyRevision(requireNonNull(revision)))
			throw new IllegalArgumentException("A 2025 revision is required.");
		McpImplementationMetadata information = requireNonNull(serverInformation);
		if ("2025-11-25".equals(revision))
			return information.toJsonObject();
		Map<String, McpJsonValue> fields = new LinkedHashMap<>();
		fields.put("name", new McpJsonString(information.name()));
		fields.put("version", new McpJsonString(information.version()));
		information.title().ifPresent(value ->
				fields.put("title", new McpJsonString(value)));
		return new McpJsonObject(fields);
	}
}
