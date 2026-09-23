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

/**
 * Checked acquisition of a resource for an HTTP producer or SSE connection.
 * The owning operation controls when acquisition runs and disposes a resource
 * returned after termination. If opening throws before returning a resource,
 * the factory remains responsible for cleaning up its partial acquisition.
 * Captured state may be used by concurrent executions of a response descriptor.
 * This factory itself does not establish that concurrent close is safe.
 *
 * @param <T> the resource type
 */
@FunctionalInterface
public interface StreamResourceFactory<T extends AutoCloseable> {
	/**
	 * @return a non-null resource
	 * @throws Exception if acquisition fails
	 */
	@NonNull T open() throws Exception;
}
