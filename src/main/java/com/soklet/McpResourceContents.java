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
import java.net.URI;
import java.util.Optional;

/**
 * Immutable text or binary contents returned for one MCP resource URI.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public sealed interface McpResourceContents
		permits McpBlobResourceContents, McpTextResourceContents {
	/** @return resource URI */
	@NonNull
	URI getUri();

	/** @return resource MIME type, if supplied */
	@NonNull
	Optional<@NonNull String> getMimeType();

	/** @return immutable extension metadata */
	@NonNull
	McpJsonObject getMetadata();
}
