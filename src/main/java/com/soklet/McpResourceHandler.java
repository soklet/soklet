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
import java.util.Optional;

/**
 * Programmatic MCP resource-read handler contract.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public interface McpResourceHandler {
	/**
	 * Provides the resource URI or URI template handled by this resource.
	 *
	 * @return the resource URI or URI template
	 */
	@NonNull
	String getUri();

	/**
	 * Provides the MCP resource name.
	 *
	 * @return the resource name
	 */
	@NonNull
	String getName();

	/**
	 * Provides the MIME type returned by this resource handler.
	 *
	 * @return the MIME type
	 */
	@NonNull
	String getMimeType();

	/**
	 * Provides optional description metadata for the resource.
	 *
	 * @return the resource description, if available
	 */
	@NonNull
	default Optional<String> getDescription() {
		return Optional.empty();
	}

	/**
	 * Handles an MCP {@code resources/read} request.
	 *
	 * @param context the resource handler context
	 * @return the resource contents
	 * @throws Exception if the resource read fails
	 */
	@NonNull
	McpResourceContents handle(@NonNull McpResourceHandlerContext context) throws Exception;
}
