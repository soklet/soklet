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
 * Effective protection mode for framework-owned MCP request state.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public enum McpProtectionMode {
	/** No Soklet-managed protection keys or application protector are configured. */
	NONE,

	/** A custom application-owned {@link McpRequestStateProtector} is configured. */
	CUSTOM_PROTECTOR,

	/** A stable production {@link McpProtectionKeyring} is configured. */
	PRODUCTION_KEYRING,

	/** Explicit, process-local development protection is configured. */
	DEVELOPMENT_EPHEMERAL
}
