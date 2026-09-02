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
 * One-shot lifecycle runtime returned by an HTTP or SSE transport attachment.
 * Implementations must make shutdown phase methods prompt, nonblocking,
 * idempotent, thread-safe, and monotonic.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public interface TransportRuntime {
	/**
	 * Binds the transport and returns only after it is ready.
	 *
	 * @param context startup timing and cancelation information
	 */
	void start(@NonNull StartupContext context);

	/**
	 * Stops admission and initiates graceful wind-up without waiting for it.
	 *
	 * @param context graceful shutdown timing information
	 */
	void quiesce(@NonNull ShutdownContext context);

	/**
	 * Subsumes quiesce and interrupts or cancels only runtime-owned execution.
	 *
	 * @param context forced shutdown timing information
	 */
	void force(@NonNull ShutdownContext context);
}
