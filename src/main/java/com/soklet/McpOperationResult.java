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

import javax.annotation.concurrent.ThreadSafe;

/**
 * Sealed result family for programmatic MCP handlers and handler interception.
 *
 * <p>The framework-owned result family includes {@link McpCompleteResult},
 * {@link McpInputRequiredResult}, {@link McpTaskCreatedResult},
 * {@link McpResourcePage}, {@link McpSkillPage}, and {@link McpArgumentCompletionResult}.
 * Applications cannot implement this interface directly. Each MCP method
 * accepts only its corresponding result implementation; sealing does not make
 * every permitted result valid for every operation. Reviewed future operations
 * may add framework-owned result types, so callers should retain a default
 * branch when switching on the result family.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public sealed interface McpOperationResult permits McpCompleteResult,
		McpInputRequiredResult, McpTaskCreatedResult, McpResourcePage,
		McpArgumentCompletionResult, McpSkillPage {
}
