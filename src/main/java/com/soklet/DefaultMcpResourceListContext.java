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
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Package-private immutable dynamic resource-list context.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class DefaultMcpResourceListContext implements McpResourceListContext {
	@NonNull
	private final Optional<@NonNull String> cursor;
	@NonNull
	private final List<@NonNull McpResourceDescriptor> registeredResourceDescriptors;

	DefaultMcpResourceListContext(@NonNull Optional<@NonNull String> cursor,
			@NonNull List<@NonNull McpResourceDescriptor> registeredResourceDescriptors) {
		this.cursor = requireNonNull(cursor);
		this.registeredResourceDescriptors = List.copyOf(
				requireNonNull(registeredResourceDescriptors));
	}

	@Override
	@NonNull
	public Optional<@NonNull String> getCursor() {
		return this.cursor;
	}

	@Override
	@NonNull
	public List<@NonNull McpResourceDescriptor> getRegisteredResourceDescriptors() {
		return this.registeredResourceDescriptors;
	}
}
