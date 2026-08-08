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

/**
 * Result of one real MCP server stop transition.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public enum McpShutdownOutcome {
	/**
	 * Transport shutdown completed and no application-supplied MCP
	 * request-processing execution remains.
	 */
	CLEAN,

	/**
	 * Transport shutdown completed, but at least one application-supplied MCP
	 * request-processing execution remains after the bounded shutdown deadline.
	 * The compatibility name covers both registered handlers and request pipeline
	 * callbacks, such as admission, rate-limiting, or request-state protection
	 * code, that ignore cancellation or interruption.
	 */
	RESIDUAL_HANDLERS
}
