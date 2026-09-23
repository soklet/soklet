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

/**
 * Opens a resource for a streaming operation, allowing checked acquisition failures.
 * <p>
 * Each invocation must return an independently owned resource. A reusable streaming descriptor may invoke the same
 * factory concurrently for different responses; the factory is responsible for the safety of its captured state.
 * The receiving API defines when acquisition occurs and how the returned resource is closed or aborted.
 * <p>
 * If acquisition fails before returning a resource, the factory is responsible for cleaning up any partially
 * acquired state. Implementing {@link AutoCloseable} alone does not guarantee that a resource can safely be
 * closed while another thread is using it.
 *
 * @param <T> the resource type
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
@FunctionalInterface
public interface StreamResourceFactory<T extends AutoCloseable> {
	/**
	 * Opens a resource for one streaming operation.
	 *
	 * @return the newly owned resource
	 * @throws Exception if acquisition fails
	 */
	@NonNull
	T open() throws Exception;
}
