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
 * Immutable resource-read result payload.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Immutable
public record McpResourceContents(
		@NonNull String uri,
		@NonNull String mimeType,
		@Nullable String text,
		@Nullable String blobBase64
) {
	public McpResourceContents {
		requireNonNull(uri);
		requireNonNull(mimeType);

		if ((text == null) == (blobBase64 == null))
			throw new IllegalArgumentException("Exactly one of text or blobBase64 must be present.");
	}

	@NonNull
	public static McpResourceContents fromText(@NonNull String uri,
																						 @NonNull String text,
																						 @NonNull String mimeType) {
		requireNonNull(uri);
		requireNonNull(text);
		requireNonNull(mimeType);
		return new McpResourceContents(uri, mimeType, text, null);
	}

	@NonNull
	public static McpResourceContents fromBlob(@NonNull String uri,
																						 @NonNull String blobBase64,
																						 @NonNull String mimeType) {
		requireNonNull(uri);
		requireNonNull(blobBase64);
		requireNonNull(mimeType);
		return new McpResourceContents(uri, mimeType, null, blobBase64);
	}
}
