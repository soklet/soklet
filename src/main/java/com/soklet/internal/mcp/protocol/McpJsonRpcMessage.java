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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

sealed interface McpJsonRpcMessage permits McpJsonRpcMessage.Request,
		McpJsonRpcMessage.Notification, McpJsonRpcMessage.ResultResponse,
		McpJsonRpcMessage.ErrorResponse {
	String JSON_RPC_VERSION = "2.0";
	Set<String> RESERVED_BASE_FIELDS =
			Set.of("jsonrpc", "id", "method", "params", "result", "error");

	default String jsonRpcVersion() {
		return JSON_RPC_VERSION;
	}

	McpJsonObject toJsonObject();

	record Request(McpJsonRpcId id, String method, McpRequestParameters params,
			McpJsonObject extensionFields) implements McpJsonRpcMessage {
		public Request {
			requireNonNull(id);
			requireNonNull(method);
			requireNonNull(params);
			extensionFields = McpProtocolSupport.requireExtensionFields(extensionFields,
					RESERVED_BASE_FIELDS);
		}

		@Override
		public McpJsonObject toJsonObject() {
			Map<String, McpJsonValue> values = new LinkedHashMap<>(extensionFields.members());
			values.put("jsonrpc", new McpJsonString(JSON_RPC_VERSION));
			values.put("id", id.toJsonValue());
			values.put("method", new McpJsonString(method));
			values.put("params", params.toJsonObject());
			return new McpJsonObject(values);
		}
	}

	record Notification(String method, Optional<McpJsonObject> params,
			McpJsonObject extensionFields) implements McpJsonRpcMessage {
		public Notification {
			requireNonNull(method);
			requireNonNull(params);
			extensionFields = McpProtocolSupport.requireExtensionFields(extensionFields,
					RESERVED_BASE_FIELDS);
		}

		@Override
		public McpJsonObject toJsonObject() {
			Map<String, McpJsonValue> values = new LinkedHashMap<>(extensionFields.members());
			values.put("jsonrpc", new McpJsonString(JSON_RPC_VERSION));
			values.put("method", new McpJsonString(method));
			params.ifPresent(value -> values.put("params", value));
			return new McpJsonObject(values);
		}
	}

	record ResultResponse(McpJsonRpcId id, McpWireResult result,
			McpJsonObject extensionFields) implements McpJsonRpcMessage {
		public ResultResponse {
			requireNonNull(id);
			requireNonNull(result);
			extensionFields = McpProtocolSupport.requireExtensionFields(extensionFields,
					RESERVED_BASE_FIELDS);
		}

		@Override
		public McpJsonObject toJsonObject() {
			Map<String, McpJsonValue> values = new LinkedHashMap<>(extensionFields.members());
			values.put("jsonrpc", new McpJsonString(JSON_RPC_VERSION));
			values.put("id", id.toJsonValue());
			values.put("result", result.toJsonObject());
			return new McpJsonObject(values);
		}
	}

	record ErrorResponse(Optional<McpJsonRpcId> id, McpJsonRpcError error,
			McpJsonObject extensionFields) implements McpJsonRpcMessage {
		public ErrorResponse {
			requireNonNull(id);
			requireNonNull(error);
			extensionFields = McpProtocolSupport.requireExtensionFields(extensionFields,
					RESERVED_BASE_FIELDS);
		}

		@Override
		public McpJsonObject toJsonObject() {
			Map<String, McpJsonValue> values = new LinkedHashMap<>(extensionFields.members());
			values.put("jsonrpc", new McpJsonString(JSON_RPC_VERSION));
			id.ifPresent(value -> values.put("id", value.toJsonValue()));
			values.put("error", error.toJsonObject());
			return new McpJsonObject(values);
		}
	}
}
