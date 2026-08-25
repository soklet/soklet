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
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Sole production MCP profile for Soklet 4.0.x.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class Mcp20260728ProtocolProfile implements McpProtocolProfile {
	@NonNull
	static final Mcp20260728ProtocolProfile INSTANCE =
			new Mcp20260728ProtocolProfile();

	private Mcp20260728ProtocolProfile() {
	}

	@Override
	@NonNull
	public String revision() {
		return McpProtocolVersion.CURRENT;
	}

	@Override
	public McpJsonRpcMessage.@NonNull Request mapRequest(
			@NonNull McpRequestWireMapper mapper,
			McpJsonRpcEnvelope.@NonNull Request request) {
		return requireNonNull(mapper).map(requireNonNull(request));
	}

	@Override
	@NonNull
	public McpNotificationMetadataValidation validateNotificationMetadata(
			McpJsonRpcEnvelope.@NonNull Notification notification) {
		Optional<McpJsonValue> params = requireNonNull(notification).params();
		if (params.isEmpty()
				|| !(params.orElseThrow() instanceof McpJsonObject object))
			return new McpNotificationMetadataValidation(true, Optional.empty());

		McpJsonValue metadataValue = object.members().get("_meta");
		if (metadataValue == null)
			return new McpNotificationMetadataValidation(true, Optional.empty());
		if (!(metadataValue instanceof McpJsonObject metadata))
			return new McpNotificationMetadataValidation(false, Optional.empty());

		try {
			McpProtocolSupport.requireInboundMetadataFields(metadata, Set.of());
			return new McpNotificationMetadataValidation(true,
					Optional.of(metadata));
		} catch (IllegalArgumentException exception) {
			return new McpNotificationMetadataValidation(false,
					Optional.of(metadata));
		}
	}

	@Override
	@NonNull
	public McpWireResult renderFrameworkResult(
			@NonNull McpProfileFrameworkResultKind kind,
			@NonNull McpWireResult canonicalResult) {
		requireNonNull(kind);
		return requireNonNull(canonicalResult);
	}

	@Override
	@NonNull
	public McpWireResult renderApplicationResult(
			@NonNull McpProfileApplicationResultKind kind,
			@NonNull McpWireResult canonicalResult) {
		requireNonNull(kind);
		return requireNonNull(canonicalResult);
	}

	@Override
	public McpJsonRpcMessage.@NonNull Notification renderFrameworkNotification(
			@NonNull McpProfileFrameworkNotificationKind kind,
			McpJsonRpcMessage.@NonNull Notification canonicalNotification) {
		requireNonNull(kind);
		return requireNonNull(canonicalNotification);
	}

	@Override
	@NonNull
	public McpJsonRpcError renderFrameworkError(
			@NonNull McpProfileErrorKind kind,
			@NonNull McpJsonRpcError canonicalError) {
		requireNonNull(kind);
		return requireNonNull(canonicalError);
	}
}
