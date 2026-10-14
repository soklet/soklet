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

package com.soklet.internal.mcp.protocol;

import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.ThreadSafe;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@FunctionalInterface
interface McpApplicationHandlerExecutorFactory {
	@NonNull
	ExecutorService create(int concurrency);

	@NonNull
	static McpApplicationHandlerExecutorFactory production() {
		return McpApplicationHandlerExecutors::newProductionExecutor;
	}
}

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class McpApplicationHandlerExecutors {
	@NonNull
	private static final String THREAD_NAME_PREFIX = "soklet-mcp-handler-";

	private McpApplicationHandlerExecutors() {
	}

	@NonNull
	static ExecutorService newProductionExecutor(int concurrency) {
		if (concurrency < 1)
			throw new IllegalArgumentException("Handler concurrency must be positive.");

		// The application dispatcher owns concurrency and queue admission limits.
		return Executors.newThreadPerTaskExecutor(
				Thread.ofVirtual().name(THREAD_NAME_PREFIX, 1L).factory());
	}
}
