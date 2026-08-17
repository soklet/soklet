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

import static java.util.Objects.requireNonNull;

/**
 * Immutable schema generated under Soklet MCP Tool Schema Profile 1.
 *
 * <p>Applications may inspect a generated schema but cannot construct,
 * compile, or replace one. Profile 1 is a closed generation and validation
 * profile based on JSON Schema Draft 2020-12, not a complete Draft 2020-12
 * implementation.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpToolSchema {
	@NonNull
	private final McpJsonObject document;

	McpToolSchema(@NonNull McpJsonObject document) {
		this.document = requireNonNull(document);
	}

	/**
	 * Returns the preserved schema document published on the MCP wire.
	 *
	 * @return immutable schema object
	 */
	@NonNull
	public McpJsonObject getDocument() {
		return this.document;
	}
}
