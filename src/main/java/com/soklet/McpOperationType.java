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

package com.soklet;

import org.jspecify.annotations.NonNull;

import static java.util.Objects.requireNonNull;

/**
 * High-level types of inbound MCP operations.
 *
 * <p>This semantic classification is distinct from the exact JSON-RPC method
 * name exposed by MCP request contexts. {@link #OTHER} represents an
 * unrecognized, future, or extension method without discarding its exact wire
 * value.
 *
 * <p>The recognized operation set may grow as Soklet adopts later MCP
 * profiles; callers switching over these values should retain a
 * forward-compatible default.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public enum McpOperationType {
	/** Server discovery through {@code server/discover}. */
	SERVER_DISCOVER,
	/** Tool catalog retrieval through {@code tools/list}. */
	TOOLS_LIST,
	/** Tool invocation through {@code tools/call}. */
	TOOLS_CALL,
	/** Prompt catalog retrieval through {@code prompts/list}. */
	PROMPTS_LIST,
	/** Prompt retrieval through {@code prompts/get}. */
	PROMPTS_GET,
	/** Resource catalog retrieval through {@code resources/list}. */
	RESOURCES_LIST,
	/** Resource-template catalog retrieval through {@code resources/templates/list}. */
	RESOURCES_TEMPLATES_LIST,
	/** Resource retrieval through {@code resources/read}. */
	RESOURCES_READ,
	/** Subscription stream creation through {@code subscriptions/listen}. */
	SUBSCRIPTIONS_LISTEN,
	/** Request cancelation signaling through {@code notifications/cancelled}. */
	NOTIFICATIONS_CANCELED,
	/** An unrecognized, future, or extension operation. */
	OTHER;

	@NonNull
	static McpOperationType fromJsonRpcMethod(@NonNull String jsonRpcMethod) {
		return switch (requireNonNull(jsonRpcMethod)) {
			case "server/discover" -> SERVER_DISCOVER;
			case "tools/list" -> TOOLS_LIST;
			case "tools/call" -> TOOLS_CALL;
			case "prompts/list" -> PROMPTS_LIST;
			case "prompts/get" -> PROMPTS_GET;
			case "resources/list" -> RESOURCES_LIST;
			case "resources/templates/list" -> RESOURCES_TEMPLATES_LIST;
			case "resources/read" -> RESOURCES_READ;
			case "subscriptions/listen" -> SUBSCRIPTIONS_LISTEN;
			case "notifications/cancelled" -> NOTIFICATIONS_CANCELED;
			default -> OTHER;
		};
	}
}
