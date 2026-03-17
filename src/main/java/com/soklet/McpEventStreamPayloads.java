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
import java.nio.charset.StandardCharsets;
import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * Internal utility for encoding MCP event-stream messages into SSE payload bytes.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class McpEventStreamPayloads {
	@NonNull
	static byte[] fromMessage(@NonNull McpObject message) {
		requireNonNull(message);
		return fromMessages(List.of(message));
	}

	@NonNull
	static byte[] fromMessages(@NonNull List<@NonNull McpObject> messages) {
		requireNonNull(messages);

		StringBuilder stringBuilder = new StringBuilder(Math.max(64, messages.size() * 96));

		for (McpObject message : messages) {
			requireNonNull(message);
			stringBuilder.append("data: ")
					.append(McpJsonCodec.toJson(message))
					.append('\n')
					.append('\n');
		}

		return stringBuilder.toString().getBytes(StandardCharsets.UTF_8);
	}

	private McpEventStreamPayloads() {
		// Utility class
	}
}
