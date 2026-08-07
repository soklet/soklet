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
 * Declares when an MCP operation requires the client capability associated
 * with an input request.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public enum McpInputRequirement {
	/**
	 * The capability is required before Soklet admits the operation.
	 */
	REQUIRED,
	/**
	 * The capability is required only if the handler emits this input request.
	 */
	CONDITIONAL
}
