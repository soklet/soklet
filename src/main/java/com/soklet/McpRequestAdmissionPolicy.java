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
 * Thread-safe authentication, authorization, and admission hook for MCP requests.
 * Soklet may invoke one policy instance concurrently for independent requests.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
@FunctionalInterface
public interface McpRequestAdmissionPolicy {
	/**
	 * Makes the admission decision for one structurally valid request.
	 *
	 * @param context bounded pre-handler admission context
	 * @return a non-null accepted or rejected decision
	 * @throws Exception if application admission logic fails; Soklet fails closed
	 */
	@NonNull
	McpAdmissionDecision admit(@NonNull McpAdmissionContext context) throws Exception;

	/**
	 * Returns the shared policy that accepts every request as anonymous.
	 *
	 * @return shared accept-all policy
	 */
	@NonNull
	static McpRequestAdmissionPolicy acceptAllInstance() {
		return AcceptAllMcpRequestAdmissionPolicy.INSTANCE;
	}
}

/**
 * Thread-safe accept-all MCP request-admission policy.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class AcceptAllMcpRequestAdmissionPolicy implements McpRequestAdmissionPolicy {
	@NonNull
	static final AcceptAllMcpRequestAdmissionPolicy INSTANCE =
			new AcceptAllMcpRequestAdmissionPolicy();

	private AcceptAllMcpRequestAdmissionPolicy() {
	}

	@Override
	@NonNull
	public McpAdmissionDecision admit(@NonNull McpAdmissionContext context) {
		return McpAdmissionDecision.fromAnonymousIdentity();
	}
}
