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
 * Lifecycle status reported by an immutable MCP server diagnostics snapshot.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public enum McpServerStatus {
	/**
	 * MCP attachment has not begun, or startup failed before attachment was
	 * committed.
	 */
	NOT_STARTED,

	/**
	 * Attachment or transport startup is in progress before readiness.
	 */
	STARTING,

	/**
	 * The MCP transport generation is ready and admission is open. An
	 * off-network simulator generation can be running without a bound address.
	 */
	RUNNING,

	/** Shutdown or rollback is in progress and final proof is pending. */
	SHUTTING_DOWN,

	/** Final normal, forced, or unexpected termination is proven. */
	TERMINATED,

	/** Positive MCP activity remained at the final shutdown boundary. */
	RESIDUAL_ACTIVITY,

	/** Final MCP termination proof could not be established. */
	TERMINATION_UNKNOWN
}
