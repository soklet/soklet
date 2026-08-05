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
import java.util.Map;
import java.util.Optional;

/**
 * Immutable validated arguments for one MCP prompt invocation.
 *
 * <p>The argument map contains only arguments declared by the prompt.
 * Required arguments are guaranteed to be present before the handler runs.
 * Omission and an explicitly supplied empty string remain distinct.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public interface McpPromptGetContext {
	/**
	 * Returns supplied arguments in their wire order.
	 *
	 * @return immutable argument-name to exact string-value map
	 */
	@NonNull
	Map<@NonNull String, @NonNull String> getArguments();

	/**
	 * Finds a supplied argument by its declared name.
	 *
	 * @param name declared prompt argument name
	 * @return exact supplied value, or empty when omitted
	 */
	@NonNull
	Optional<@NonNull String> findArgument(@NonNull String name);
}
