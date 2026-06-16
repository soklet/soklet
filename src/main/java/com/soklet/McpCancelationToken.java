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

import javax.annotation.concurrent.ThreadSafe;
import java.util.Optional;

/**
 * Cooperative cancelation signal for an in-flight MCP JSON-RPC request.
 * <p>
 * Soklet does not interrupt handler threads when a client sends
 * {@code notifications/cancelled}. Long-running handlers can poll this token
 * and stop work at a safe application-defined point.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public interface McpCancelationToken {
	/**
	 * Indicates whether the client has requested cancelation for the current MCP request.
	 *
	 * @return {@code true} if cancelation was requested
	 */
	@NonNull
	Boolean isCancelationRequested();

	/**
	 * Provides the client-supplied cancelation reason.
	 *
	 * @return the cancelation reason, if supplied
	 */
	@NonNull
	Optional<String> getReason();
}
