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
 * Core MCP client capabilities that handlers and policies may inspect.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public enum McpClientCapability {
	/** Form-based elicitation. */
	ELICITATION_FORM,
	/** URL-based elicitation. */
	ELICITATION_URL,
	/** Base sampling support. */
	SAMPLING,
	/** Sampling with context inclusion. */
	SAMPLING_CONTEXT,
	/** Sampling with tool use. */
	SAMPLING_TOOLS,
	/** Client roots support. */
	ROOTS
}
