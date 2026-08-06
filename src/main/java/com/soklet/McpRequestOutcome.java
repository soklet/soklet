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
 * Fixed client-visible terminal outcomes for admitted MCP requests.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public enum McpRequestOutcome {
	/**
	 * A complete MCP result was produced, including a tool result with
	 * {@code isError=true}.
	 */
	COMPLETE,
	/**
	 * The request completed with an MCP {@code input_required} result.
	 */
	INPUT_REQUIRED,
	/**
	 * Post-admission rate, capacity, or application policy rejected the request.
	 */
	REJECTED,
	/**
	 * Application code intentionally produced a client-visible JSON-RPC error.
	 */
	APPLICATION_ERROR,
	/**
	 * Protocol or operation input was invalid after semantic handling began.
	 */
	PROTOCOL_ERROR,
	/**
	 * Soklet contained an unexpected framework or application failure.
	 */
	INTERNAL_ERROR,
	/**
	 * The request was canceled before another terminal outcome won.
	 */
	CANCELED,
	/**
	 * The absolute request deadline elapsed.
	 */
	DEADLINE_EXCEEDED,
	/**
	 * The client disconnected before request completion.
	 */
	CLIENT_DISCONNECTED,
	/**
	 * The terminal response or stream could not be written.
	 */
	WRITE_FAILED
}
