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
 * Reasons why a streaming HTTP response, Server-Sent Event connection, or MCP SSE stream may terminate.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public enum StreamTerminationReason {
	/**
	 * The stream completed normally.
	 */
	COMPLETED,
	/**
	 * The client disconnected before the stream completed.
	 */
	CLIENT_DISCONNECTED,
	/**
	 * The server stopped before the stream completed.
	 */
	SERVER_STOPPING,
	/**
	 * The request protocol cannot support the stream.
	 */
	PROTOCOL_UNSUPPORTED,
	/**
	 * The stream exceeded its configured total response timeout.
	 */
	RESPONSE_TIMEOUT,
	/**
	 * The stream exceeded its configured producer idle timeout.
	 */
	RESPONSE_IDLE_TIMEOUT,
	/**
	 * Producer code intentionally aborted the stream.
	 */
	APPLICATION_CANCELED,
	/**
	 * The stream was closed due to backpressure.
	 */
	BACKPRESSURE,
	/**
	 * The stream ended because its owning session was terminated.
	 */
	SESSION_TERMINATED,
	/**
	 * A write to the stream failed.
	 */
	WRITE_FAILED,
	/**
	 * The stream producer failed.
	 */
	PRODUCER_FAILED,
	/**
	 * An unexpected internal error occurred.
	 */
	INTERNAL_ERROR,
	/**
	 * The simulator refused to materialize more streaming response bytes.
	 */
	SIMULATOR_LIMIT_EXCEEDED,
	/**
	 * The stream ended for an unspecified reason.
	 */
	UNKNOWN
}
