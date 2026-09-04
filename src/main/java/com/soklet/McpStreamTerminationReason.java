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
 * Fixed bounded reasons for MCP request-stream and subscription termination.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public enum McpStreamTerminationReason {
	/** The stream completed normally. */
	COMPLETED,
	/** The client disconnected. */
	CLIENT_DISCONNECTED,
	/** The request was canceled. */
	REQUEST_CANCELED,
	/** The request deadline elapsed. */
	DEADLINE_EXCEEDED,
	/** Stream output could not be written. */
	WRITE_FAILED,
	/** A bounded server transport or protocol-output queue was exhausted. */
	BACKPRESSURE,
	/** The MCP server is stopping. */
	SERVER_STOPPING,
	/** The simulator's pending stream-item capacity was exhausted. */
	SIMULATOR_CAPTURE_ITEM_LIMIT_EXCEEDED,
	/** The simulator's cumulative captured-byte capacity was exhausted. */
	SIMULATOR_CAPTURE_BYTE_LIMIT_EXCEEDED,
	/** Soklet contained an unexpected stream failure. */
	INTERNAL_ERROR
}
