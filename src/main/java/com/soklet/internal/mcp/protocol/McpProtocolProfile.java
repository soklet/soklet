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

import static java.util.Objects.requireNonNull;

/**
 * Immutable package-private semantic authority for one exact MCP revision.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
interface McpProtocolProfile {
	/** @return exact wire revision owned by this profile */
	@NonNull
	String revision();

	/**
	 * Maps a generically decoded request through this profile's request mapper.
	 * The selected profile is retained by every downstream request collaborator;
	 * the mapper parameter remains explicit because the mapper itself is shared,
	 * revision-neutral decode infrastructure.
	 */
	McpJsonRpcMessage.@NonNull Request mapRequest(
			@NonNull McpRequestWireMapper mapper,
			McpJsonRpcEnvelope.@NonNull Request request);

	/**
	 * Validates and extracts metadata from one already-selected notification.
	 * Method vocabulary and the cancellation-notification exception remain owned
	 * by common bootstrap.
	 */
	@NonNull
	McpNotificationMetadataValidation validateNotificationMetadata(
			McpJsonRpcEnvelope.@NonNull Notification notification);

	/** @return the profile rendering of one canonical framework result */
	@NonNull
	McpWireResult renderFrameworkResult(
			@NonNull McpProfileFrameworkResultKind kind,
			@NonNull McpWireResult canonicalResult);

	/** @return the profile rendering of one handler-produced application result */
	@NonNull
	McpWireResult renderApplicationResult(
			@NonNull McpProfileApplicationResultKind kind,
			@NonNull McpWireResult canonicalResult);

	/** @return the profile rendering of one canonical framework notification */
	McpJsonRpcMessage.@NonNull Notification renderFrameworkNotification(
			@NonNull McpProfileFrameworkNotificationKind kind,
			McpJsonRpcMessage.@NonNull Notification canonicalNotification);

	/** @return the profile rendering of one canonical framework error */
	@NonNull
	McpJsonRpcError renderFrameworkError(@NonNull McpProfileErrorKind kind,
			@NonNull McpJsonRpcError canonicalError);
}

/** Framework-owned static and long-lived control results. */
enum McpProfileFrameworkResultKind {
	DISCOVERY,
	TOOLS_LIST,
	PROMPTS_LIST,
	RESOURCES_LIST,
	RESOURCE_TEMPLATES_LIST,
	SUBSCRIPTION_TERMINAL
}

/** Handler-produced operation results owned by selected-profile rendering. */
enum McpProfileApplicationResultKind {
	TOOL,
	PROMPT,
	RESOURCE_READ,
	RESOURCE_LIST
}

/** Framework-owned progress and subscription-control notifications. */
enum McpProfileFrameworkNotificationKind {
	PROGRESS,
	SUBSCRIPTION_ACKNOWLEDGEMENT,
	SUBSCRIPTION_EVENT
}

/** Errors whose deciding rules have already selected an exact profile. */
enum McpProfileErrorKind {
	REQUEST_MAPPER,
	OPERATION,
	CONTROL
}

/** One bounded notification-metadata validation/extraction result. */
@ThreadSafe
record McpNotificationMetadataValidation(boolean valid,
		@NonNull Optional<@NonNull McpJsonObject> metadata) {
	McpNotificationMetadataValidation {
		requireNonNull(metadata);
	}
}
