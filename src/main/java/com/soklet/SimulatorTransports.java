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

/** Fresh off-network transport graph owned by one simulation scope. */
public interface SimulatorTransports {
	/** @return fresh simulated HTTP transport */
	@NonNull
	HttpServer getHttpServer();

	/** @return fresh simulated Server-Sent Events transport */
	@NonNull
	SseServer getSseServer();

	/**
	 * Creates a builder for a fresh simulated MCP server.
	 *
	 * @param port logical port
	 * @return MCP server builder bound to this simulation scope
	 */
	McpServer.@NonNull Builder newMcpServerBuilder(@NonNull Integer port);
}
