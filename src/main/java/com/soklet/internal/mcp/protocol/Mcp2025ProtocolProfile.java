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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Exact 2025 Streamable HTTP profiles for the first synchronous-tool slice.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class Mcp2025ProtocolProfile implements McpProtocolProfile {
	@NonNull
	static final Mcp2025ProtocolProfile JUNE_18 =
			new Mcp2025ProtocolProfile("2025-06-18");
	@NonNull
	static final Mcp2025ProtocolProfile NOVEMBER_25 =
			new Mcp2025ProtocolProfile("2025-11-25");
	@NonNull
	private static final Set<@NonNull String> LEGACY_TOOL_FIELDS = Set.of(
			"name", "title", "description", "inputSchema", "outputSchema",
			"annotations", "_meta");
	@NonNull
	private static final Set<@NonNull String> LEGACY_CONTENT_TYPES = Set.of(
			"text", "image", "audio", "resource_link", "resource");
	@NonNull
	private static final Set<@NonNull String> LEGACY_TOOL_RESULT_FIELDS = Set.of(
			"content", "structuredContent", "isError");
	@NonNull
	private final String revision;

	private Mcp2025ProtocolProfile(@NonNull String revision) {
		if (!McpLegacyHttpWire.isLegacyRevision(requireNonNull(revision)))
			throw new IllegalArgumentException("Unsupported 2025 MCP revision.");
		this.revision = revision;
	}

	@Override
	public @NonNull String revision() {
		return revision;
	}

	@Override
	public McpJsonRpcMessage.@NonNull Request mapRequest(
			@NonNull McpRequestWireMapper mapper,
			McpJsonRpcEnvelope.@NonNull Request request) {
		return McpLegacyRequestWireMapper.map(mapper, revision, request);
	}

	@Override
	public @NonNull McpNotificationMetadataValidation validateNotificationMetadata(
			McpJsonRpcEnvelope.@NonNull Notification notification) {
		requireNonNull(notification);
		if (notification.params().isEmpty())
			return new McpNotificationMetadataValidation(true, Optional.empty());
		if (!(notification.params().orElseThrow() instanceof McpJsonObject object))
			return new McpNotificationMetadataValidation(false, Optional.empty());
		McpJsonValue rawMetadata = object.members().get("_meta");
		if (rawMetadata == null)
			return new McpNotificationMetadataValidation(true, Optional.empty());
		if (!(rawMetadata instanceof McpJsonObject metadata)
				|| metadata.members().containsKey(McpRequestMetadata.PROTOCOL_VERSION_KEY)
				|| metadata.members().containsKey(McpRequestMetadata.CLIENT_CAPABILITIES_KEY))
			return new McpNotificationMetadataValidation(false, Optional.empty());
		return new McpNotificationMetadataValidation(true, Optional.of(metadata));
	}

	@Override
	public @NonNull McpWireResult renderFrameworkResult(
			@NonNull McpProfileFrameworkResultKind kind,
			@NonNull McpWireResult canonicalResult) {
		requireNonNull(kind);
		requireNonNull(canonicalResult);
		if (kind != McpProfileFrameworkResultKind.TOOLS_LIST)
			return canonicalResult;
		McpJsonValue rawTools = canonicalResult.fields().members().get("tools");
		if (!(rawTools instanceof McpJsonArray tools))
			throw new IllegalArgumentException("The tool catalog is not an array.");
		List<McpJsonValue> projectedTools = tools.values().stream()
				.map(this::projectToolDescriptor).toList();
		return McpWireResult.complete(new McpJsonObject(Map.of(
				"tools", new McpJsonArray(projectedTools))));
	}

	private @NonNull McpJsonValue projectToolDescriptor(
			@NonNull McpJsonValue rawTool) {
		if (!(requireNonNull(rawTool) instanceof McpJsonObject tool))
			throw new IllegalArgumentException("A tool descriptor is not an object.");
		McpJsonValue metadata = tool.members().get("_meta");
		if (metadata != null) {
			if (!(metadata instanceof McpJsonObject object))
				throw new IllegalArgumentException("Tool metadata is not an object.");
			if (object.members().get("ui") instanceof McpJsonObject ui
					&& (ui.members().containsKey("resourceUri")
							|| ui.members().containsKey("visibility")))
				throw new IllegalArgumentException(
						"The 2025 tool adapter cannot advertise Apps metadata.");
		}
		McpJsonValue execution = tool.members().get("execution");
		if (execution != null) {
			if (!(execution instanceof McpJsonObject object))
				throw new IllegalArgumentException(
						"Tool execution metadata is not an object.");
			if (new McpJsonString("required").equals(
						object.members().get("taskSupport")))
				throw new IllegalArgumentException(
						"A task-required tool cannot be served by the 2025 adapter.");
		}
		McpJsonValue outputSchema = tool.members().get("outputSchema");
		if (outputSchema != null
				&& (!(outputSchema instanceof McpJsonObject object)
						|| !new McpJsonString("object").equals(
								object.members().get("type"))))
			throw new IllegalArgumentException(
					"A 2025 tool output schema must have object type.");
		Map<String, McpJsonValue> fields = new LinkedHashMap<>();
		for (Map.Entry<String, McpJsonValue> entry : tool.members().entrySet())
			if (LEGACY_TOOL_FIELDS.contains(entry.getKey())
					|| "2025-11-25".equals(revision)
					&& "icons".equals(entry.getKey()))
				fields.put(entry.getKey(), entry.getValue());
		return new McpJsonObject(fields);
	}

	@Override
	public @NonNull McpWireResult renderApplicationResult(
			@NonNull McpProfileApplicationResultKind kind,
			@NonNull McpWireResult canonicalResult) {
		requireNonNull(kind);
		requireNonNull(canonicalResult);
		if (!McpResultType.COMPLETE.equals(canonicalResult.resultType()))
			throw new IllegalArgumentException(
					"The 2025 tool adapter requires a complete result.");
		if (kind != McpProfileApplicationResultKind.TOOL)
			return canonicalResult;
		if (!LEGACY_TOOL_RESULT_FIELDS.containsAll(
				canonicalResult.fields().members().keySet()))
			throw new IllegalArgumentException(
					"The tool result contains unsupported 2025 fields.");
		McpJsonValue rawContent = canonicalResult.fields().members().get("content");
		if (!(rawContent instanceof McpJsonArray content))
			throw new IllegalArgumentException("A tool result requires content.");
		McpJsonValue structuredContent = canonicalResult.fields().members()
				.get("structuredContent");
		if (structuredContent != null
				&& !(structuredContent instanceof McpJsonObject))
			throw new IllegalArgumentException(
					"A 2025 tool result requires object structuredContent.");
		McpJsonValue isError = canonicalResult.fields().members().get("isError");
		if (isError != null && !(isError instanceof McpJsonBoolean))
			throw new IllegalArgumentException(
					"A 2025 tool result requires boolean isError.");
		for (McpJsonValue item : content.values()) {
			if (!(item instanceof McpJsonObject object)
					|| !(object.members().get("type") instanceof McpJsonString type)
					|| !LEGACY_CONTENT_TYPES.contains(type.value()))
				throw new IllegalArgumentException(
						"The tool result contains unsupported 2025 content.");
			if ("2025-06-18".equals(revision)
					&& "resource_link".equals(type.value())
					&& object.members().containsKey("icons"))
				throw new IllegalArgumentException(
						"A 2025-06-18 resource link cannot include icons.");
		}
		return canonicalResult;
	}

	@Override
	public McpJsonRpcMessage.@NonNull Notification renderFrameworkNotification(
			@NonNull McpProfileFrameworkNotificationKind kind,
			McpJsonRpcMessage.@NonNull Notification canonicalNotification) {
		requireNonNull(kind);
		return requireNonNull(canonicalNotification);
	}

	@Override
	public @NonNull McpJsonRpcError renderFrameworkError(
			@NonNull McpProfileErrorKind kind,
			@NonNull McpJsonRpcError canonicalError) {
		requireNonNull(kind);
		return requireNonNull(canonicalError);
	}
}
