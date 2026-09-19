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

/**
 * Immutable framework-supplied input for one MCP argument-completion request.
 *
 * <p>The partial value and context arguments are untrusted client input, not
 * evidence of authorization or of a successfully completed operation. Missing
 * ordinary required arguments are permitted while completing an argument.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public interface McpCompletionContext {
	/** @return declared name of the argument being completed */
	@NonNull
	String getArgumentName();

	/** @return exact partial value supplied for the argument */
	@NonNull
	String getArgumentValue();

	/** @return immutable map of supplied, declared context arguments */
	@NonNull
	Map<@NonNull String, @NonNull String> getContextArguments();

	/**
	 * Completion input for a registered prompt.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	interface Prompt extends McpCompletionContext {
		/** @return canonical prompt registration targeted by the request */
		@NonNull
		McpPromptRegistration getPromptRegistration();
	}

	/**
	 * Completion input for a registered resource URI template.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	interface Resource extends McpCompletionContext {
		/** @return canonical resource-template registration targeted by the request */
		@NonNull
		McpResourceRegistration getResourceRegistration();
	}
}
