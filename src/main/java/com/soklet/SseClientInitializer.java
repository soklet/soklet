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
 * Checked setup or catch-up work for an accepted Server-Sent Event client.
 * <p>
 * Events and comments queued by the initializer are delivered only after it returns successfully, subject to
 * {@link SseServer.Builder#connectionQueueCapacity(Integer)}. Initialization must be bounded; this callback
 * is not an indefinite producer running after connection activation. An initializer failure terminates the
 * connection; queued writes are discarded. Do not retain the unicaster after this callback returns.
 * <p>
 * One initializer instance may be reused concurrently for different clients. Captured application state must
 * support that reuse.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
@FunctionalInterface
public interface SseClientInitializer {
	/**
	 * Initializes an accepted client before its queued writes are delivered.
	 *
	 * @param sseUnicaster the unicaster for this one-time initialization
	 * @throws Exception if initialization fails
	 */
	void initialize(@NonNull SseUnicaster sseUnicaster) throws Exception;
}
