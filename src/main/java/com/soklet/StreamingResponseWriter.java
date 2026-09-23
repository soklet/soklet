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
 * Callback that writes a streaming HTTP response body.
 * <p>
 * A response execution invokes this callback with its own {@link ResponseStream}. Application output work
 * belongs to this callback's thread. After it returns, Soklet performs remaining normal finalization on the same
 * thread; those finalizers may write trailing bytes before successful output is sealed. Cancelation callbacks may
 * already have closed resources opened with coordinated close-as-abort. A writer reused across
 * responses may be invoked concurrently; the application is responsible for synchronizing any mutable
 * state shared by those invocations.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
@FunctionalInterface
public interface StreamingResponseWriter {
	/**
	 * Writes response bytes using the provided response stream and its runtime metadata.
	 *
	 * @param responseStream the response stream
	 * @throws Exception if the stream producer fails
	 */
	void writeTo(@NonNull ResponseStream responseStream) throws Exception;
}
