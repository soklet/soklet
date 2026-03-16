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

import javax.annotation.concurrent.ThreadSafe;

import static java.util.Objects.requireNonNull;

/**
 * Converts app-owned structured content objects into {@link McpValue}.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public interface McpResponseMarshaler {
	@NonNull
	McpValue marshalStructuredContent(@Nullable Object value,
																		@NonNull McpStructuredContentContext context);

	@NonNull
	static McpResponseMarshaler defaultInstance() {
		return DefaultMcpResponseMarshaler.INSTANCE;
	}
}

enum DefaultMcpResponseMarshaler implements McpResponseMarshaler {
	INSTANCE;

	@NonNull
	@Override
	public McpValue marshalStructuredContent(@Nullable Object value,
																					 @NonNull McpStructuredContentContext context) {
		requireNonNull(context);

		if (value == null)
			return McpNull.INSTANCE;

		if (value instanceof McpValue mcpValue)
			return mcpValue;

		throw new IllegalArgumentException("Structured content value '%s' is not an McpValue. Supply a custom McpResponseMarshaler to handle arbitrary objects.".formatted(value.getClass().getName()));
	}
}
