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

package com.soklet.annotation;

import com.soklet.McpClientCapability;
import com.soklet.McpInputRequirement;
import org.jspecify.annotations.NonNull;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares one client request that an annotated MCP operation may emit.
 *
 * <p>This annotation is used only as a nested value within an MCP operation
 * annotation.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Target({})
@Retention(RetentionPolicy.RUNTIME)
public @interface McpMayRequestInput {
	/**
	 * The client request method.
	 *
	 * @return client request method
	 */
	@NonNull
	String method();

	/**
	 * Every client capability required by the request.
	 *
	 * @return required client capabilities
	 */
	@NonNull
	McpClientCapability @NonNull [] capabilities();

	/**
	 * When the declared capabilities are required.
	 *
	 * @return input requirement
	 */
	@NonNull
	McpInputRequirement requirement();
}
