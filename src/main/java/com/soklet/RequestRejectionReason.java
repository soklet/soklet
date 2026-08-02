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
 * Reasons an HTTP or SSE request was rejected before application-level handling began.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public enum RequestRejectionReason {
	/**
	 * The request handler queue was full.
	 */
	REQUEST_HANDLER_QUEUE_FULL,
	/**
	 * The request handler executor was shut down.
	 */
	REQUEST_HANDLER_EXECUTOR_SHUTDOWN,
	/**
	 * An unexpected internal error occurred while attempting to enqueue the request.
	 */
	INTERNAL_ERROR
}
