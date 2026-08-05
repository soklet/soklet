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
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
sealed interface McpJsonRpcMessage permits McpJsonRpcMessage.Request,
		McpJsonRpcMessage.Notification, McpJsonRpcMessage.ResultResponse,
		McpJsonRpcMessage.ErrorResponse {
	@NonNull
	String JSON_RPC_VERSION = "2.0";
	@NonNull
	Set<@NonNull String> RESERVED_BASE_FIELDS =
			Set.of("jsonrpc", "id", "method", "params", "result", "error");

	@NonNull
	default String jsonRpcVersion() {
		return JSON_RPC_VERSION;
	}

	@NonNull
	McpJsonObject toJsonObject();

	record Request(@NonNull McpJsonRpcId id, @NonNull String method,
			@NonNull McpRequestParameters params,
			@NonNull McpJsonObject extensionFields) implements McpJsonRpcMessage {
		public Request {
			requireNonNull(id);
			requireNonNull(method);
			requireNonNull(params);
			extensionFields = McpProtocolSupport.requireExtensionFields(extensionFields,
					RESERVED_BASE_FIELDS);
		}

		@Override
		@NonNull
		public McpJsonObject toJsonObject() {
			Map<@NonNull String, @NonNull McpJsonValue> values =
					new LinkedHashMap<>(extensionFields.members());
			values.put("jsonrpc", new McpJsonString(JSON_RPC_VERSION));
			values.put("id", id.toJsonValue());
			values.put("method", new McpJsonString(method));
			values.put("params", params.toJsonObject());
			return new McpJsonObject(values);
		}
	}

	record Notification(@NonNull String method,
			@NonNull Optional<@NonNull McpJsonObject> params,
			@NonNull McpJsonObject extensionFields) implements McpJsonRpcMessage {
		public Notification {
			requireNonNull(method);
			requireNonNull(params);
			extensionFields = McpProtocolSupport.requireExtensionFields(extensionFields,
					RESERVED_BASE_FIELDS);
		}

		@Override
		@NonNull
		public McpJsonObject toJsonObject() {
			Map<@NonNull String, @NonNull McpJsonValue> values =
					new LinkedHashMap<>(extensionFields.members());
			values.put("jsonrpc", new McpJsonString(JSON_RPC_VERSION));
			values.put("method", new McpJsonString(method));
			params.ifPresent(value -> values.put("params", value));
			return new McpJsonObject(values);
		}
	}

	record ResultResponse(@NonNull McpJsonRpcId id, @NonNull McpWireResult result,
			@NonNull McpJsonObject extensionFields) implements McpJsonRpcMessage {
		public ResultResponse {
			requireNonNull(id);
			requireNonNull(result);
			extensionFields = McpProtocolSupport.requireExtensionFields(extensionFields,
					RESERVED_BASE_FIELDS);
		}

		@Override
		@NonNull
		public McpJsonObject toJsonObject() {
			Map<@NonNull String, @NonNull McpJsonValue> values =
					new LinkedHashMap<>(extensionFields.members());
			values.put("jsonrpc", new McpJsonString(JSON_RPC_VERSION));
			values.put("id", id.toJsonValue());
			values.put("result", result.toJsonObject());
			return new McpJsonObject(values);
		}
	}

	record ErrorResponse(@NonNull Optional<@NonNull McpJsonRpcId> id,
			@NonNull McpJsonRpcError error, @NonNull McpJsonObject extensionFields)
			implements McpJsonRpcMessage {
		public ErrorResponse {
			requireNonNull(id);
			requireNonNull(error);
			extensionFields = McpProtocolSupport.requireExtensionFields(extensionFields,
					RESERVED_BASE_FIELDS);
		}

		@Override
		@NonNull
		public McpJsonObject toJsonObject() {
			Map<@NonNull String, @NonNull McpJsonValue> values =
					new LinkedHashMap<>(extensionFields.members());
			values.put("jsonrpc", new McpJsonString(JSON_RPC_VERSION));
			id.ifPresent(value -> values.put("id", value.toJsonValue()));
			values.put("error", error.toJsonObject());
			return new McpJsonObject(values);
		}
	}
}
