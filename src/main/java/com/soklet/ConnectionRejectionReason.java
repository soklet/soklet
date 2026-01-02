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
 * Reasons a connection attempt was rejected before a request or SSE connection could be established.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public enum ConnectionRejectionReason {
	/**
	 * The server rejected the connection because a connection limit was reached.
	 */
	MAX_CONNECTIONS,
	/**
	 * The initial request read timed out before a full request could be parsed.
	 */
	REQUEST_READ_TIMEOUT,
	/**
	 * The incoming request could not be parsed into a valid HTTP request.
	 */
	UNPARSEABLE_REQUEST,
	/**
	 * An unexpected internal error occurred while handling the connection.
	 */
	INTERNAL_ERROR
}
