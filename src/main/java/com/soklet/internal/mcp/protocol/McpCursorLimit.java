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

package com.soklet.internal.mcp.protocol;

import javax.annotation.concurrent.ThreadSafe;

/**
 * Shared finite wire bounds for application-owned MCP cursors.
 *
 * <p>This type is public only so the public server builder and internal
 * protocol runtime can share one exact contract. It is not part of Soklet's
 * supported public API or published Javadocs.</p>
 *
 * <p>A decoded cursor byte can require as many as six JSON-token characters
 * when a control character is serialized as a Unicode escape. The hard
 * ceiling therefore reserves that worst-case expansion against the strict
 * production JSON token limit. Any individually in-bound cursor can cross
 * both the request and response JSON boundaries without independently
 * exceeding the decoded-string, token, input-byte, or output-byte limits.</p>
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpCursorLimit {
	/** Default maximum cursor size in UTF-8 bytes. */
	public static final int DEFAULT_MAXIMUM_SIZE_IN_BYTES = 4_096;
	/** Maximum supported cursor size in UTF-8 bytes. */
	public static final int MAXIMUM_SUPPORTED_SIZE_IN_BYTES =
			McpJsonLimits.productionDefaults()
					.maximumTokenLengthInCharacters() / 6;

	private McpCursorLimit() {
	}

	/**
	 * Requires a positive cursor limit within the reviewed JSON wire ceiling.
	 *
	 * @param maximumSizeInBytes cursor limit in UTF-8 bytes
	 * @return the supplied limit
	 * @throws IllegalArgumentException if the limit is not supported
	 */
	public static int requireSupportedMaximumSizeInBytes(
			int maximumSizeInBytes) {
		if (maximumSizeInBytes < 1)
			throw new IllegalArgumentException(
					"MCP maximum cursor size must be positive.");
		if (maximumSizeInBytes > MAXIMUM_SUPPORTED_SIZE_IN_BYTES)
			throw new IllegalArgumentException(
					"MCP maximum cursor size must not exceed "
							+ MAXIMUM_SUPPORTED_SIZE_IN_BYTES + " bytes.");
		return maximumSizeInBytes;
	}
}
