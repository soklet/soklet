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

package com.soklet.internal.mcp.schema;

import org.jspecify.annotations.NonNull;

/**
 * Adapter from one Java type system to the shared typed-schema descriptor.
 *
 * @param <T> the Java type representation
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
interface McpTypedTypeModel<T> {
	@NonNull
	McpTypedTypeDescriptor<@NonNull T> describe(@NonNull T type);
}
