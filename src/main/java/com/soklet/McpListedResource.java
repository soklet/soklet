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
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.Immutable;

import static java.util.Objects.requireNonNull;

/**
 * Resource-list entry exposed by v1 MCP endpoints.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Immutable
public record McpListedResource(
		@NonNull String uri,
		@NonNull String name,
		@NonNull String mimeType,
		@Nullable String title,
		@Nullable String description,
		@Nullable Long sizeBytes
) {
	public McpListedResource {
		requireNonNull(uri);
		requireNonNull(name);
		requireNonNull(mimeType);

		if (sizeBytes != null && sizeBytes < 0L)
			throw new IllegalArgumentException("Resource size metadata must be non-negative.");
	}

	@NonNull
	public static McpListedResource fromComponents(@NonNull String uri,
																								 @NonNull String name,
																								 @NonNull String mimeType) {
		requireNonNull(uri);
		requireNonNull(name);
		requireNonNull(mimeType);
		return new McpListedResource(uri, name, mimeType, null, null, null);
	}

	@NonNull
	public McpListedResource withTitle(@NonNull String title) {
		requireNonNull(title);
		return new McpListedResource(uri(), name(), mimeType(), title, description(), sizeBytes());
	}

	@NonNull
	public McpListedResource withDescription(@NonNull String description) {
		requireNonNull(description);
		return new McpListedResource(uri(), name(), mimeType(), title(), description, sizeBytes());
	}

	@NonNull
	public McpListedResource withSizeBytes(@NonNull Long sizeBytes) {
		requireNonNull(sizeBytes);
		return new McpListedResource(uri(), name(), mimeType(), title(), description(), sizeBytes);
	}
}
