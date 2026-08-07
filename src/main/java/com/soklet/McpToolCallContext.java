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
 * Shallowly immutable arguments for one MCP tool invocation.
 * <p>
 * The context carrier is safe for concurrent access. The converted argument
 * value is application-defined and retains its own mutability and thread-safety
 * contract; this type's {@link ThreadSafe} annotation does not make that value
 * thread-safe.
 *
 * @param <A> bound argument type
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public interface McpToolCallContext<A> {
	/**
	 * Returns arguments converted to the registration's declared Java type. The
	 * returned application value retains its own mutability and thread-safety
	 * contract.
	 *
	 * @return converted arguments
	 */
	@NonNull
	A getArguments();

	/**
	 * Returns the validated immutable JSON object received on the wire.
	 *
	 * @return raw arguments
	 */
	@NonNull
	McpJsonObject getRawArguments();
}
