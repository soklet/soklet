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

import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.ThreadSafe;

import static java.util.Objects.requireNonNull;

/**
 * Internal authority for MCP-reserved application-metadata prefixes.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpApplicationMetadata {
	private McpApplicationMetadata() {
	}

	/**
	 * Requires public application metadata to avoid MCP-reserved prefixes.
	 *
	 * @param metadata immutable application metadata
	 * @return the supplied metadata
	 * @throws IllegalArgumentException if a key uses an MCP-reserved prefix
	 */
	public static com.soklet.@NonNull McpJsonObject requireApplicationMetadata(
			com.soklet.@NonNull McpJsonObject metadata) {
		requireNonNull(metadata);
		requireApplicationMetadataKeys(metadata.getMembers().keySet());
		return metadata;
	}

	@NonNull
	static McpJsonObject requireApplicationMetadata(
			@NonNull McpJsonObject metadata) {
		requireNonNull(metadata);
		requireApplicationMetadataKeys(metadata.members().keySet());
		return metadata;
	}

	private static void requireApplicationMetadataKeys(
			@NonNull Iterable<@NonNull String> keys) {
		requireNonNull(keys);
		for (String key : keys) {
			requireNonNull(key);
			if (hasReservedPrefix(key))
				throw new IllegalArgumentException(
						"Application metadata must not use an MCP-reserved prefix: "
								+ key);
		}
	}

	private static boolean hasReservedPrefix(@NonNull String key) {
		int slashIndex = key.indexOf('/');

		if (slashIndex < 0)
			return false;

		String prefix = key.substring(0, slashIndex);
		int firstDotIndex = prefix.indexOf('.');

		if (firstDotIndex < 0)
			return false;

		int secondDotIndex = prefix.indexOf('.', firstDotIndex + 1);
		String secondLabel = secondDotIndex < 0
				? prefix.substring(firstDotIndex + 1)
				: prefix.substring(firstDotIndex + 1, secondDotIndex);
		return "mcp".equals(secondLabel)
				|| "modelcontextprotocol".equals(secondLabel);
	}
}
