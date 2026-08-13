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
 * Whole-response behavior for an unexpected framework-owned MCP catalog
 * localization failure.
 * <p>
 * This policy cannot synthesize an application localization context. A
 * context-creation failure for an application handler or its interceptor
 * fails with the fixed sanitized internal error before application entry under
 * either value.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public enum McpLocalizationFailurePolicy {
	/** Discard a partial framework overlay and publish canonical source text. */
	USE_DEFAULT_TEXT,
	/** Discard a partial framework overlay and return a fixed internal error. */
	FAIL_REQUEST
}
