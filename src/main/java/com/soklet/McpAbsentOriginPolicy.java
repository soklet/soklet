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
 * Policy for MCP HTTP requests that do not carry an {@code Origin} header.
 * <p>
 * This policy is independent of Host validation and authorization of requests
 * that do carry an Origin.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public enum McpAbsentOriginPolicy {
	/**
	 * Allows a request to proceed to the remaining transport and application
	 * checks when Origin is absent.
	 */
	ALLOW,

	/**
	 * Rejects a request when Origin is absent.
	 */
	REQUIRE_ORIGIN
}
