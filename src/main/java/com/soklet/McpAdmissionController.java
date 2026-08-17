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

/**
 * Thread-safe authentication, authorization, and admission controller for MCP
 * requests and notifications. Soklet may invoke one controller instance
 * concurrently for independent messages.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
@FunctionalInterface
public interface McpAdmissionController {
	/**
	 * Makes the admission decision for one structurally valid request or
	 * notification.
	 *
	 * @param context bounded pre-handler admission context
	 * @return a non-null accepted or rejected decision
	 * @throws Exception if application admission logic fails; Soklet fails closed
	 */
	@NonNull
	McpAdmissionDecision admit(@NonNull McpAdmissionContext context) throws Exception;

	/**
	 * Returns the shared controller that accepts every request or notification
	 * as anonymous.
	 *
	 * @return shared accept-all controller
	 */
	@NonNull
	static McpAdmissionController acceptAllInstance() {
		return AcceptAllMcpAdmissionController.INSTANCE;
	}
}

/**
 * Thread-safe accept-all MCP admission controller.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class AcceptAllMcpAdmissionController implements McpAdmissionController {
	@NonNull
	static final AcceptAllMcpAdmissionController INSTANCE =
			new AcceptAllMcpAdmissionController();

	private AcceptAllMcpAdmissionController() {
	}

	@Override
	@NonNull
	public McpAdmissionDecision admit(@NonNull McpAdmissionContext context) {
		return McpAdmissionDecision.accepted();
	}
}
