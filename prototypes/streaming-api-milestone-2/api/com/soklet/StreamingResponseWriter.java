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
 * Produces bytes and acquires owned resources on one HTTP producer thread.
 * The same callback object can execute concurrently for separate responses;
 * captured application state is the caller's responsibility. Returning begins
 * managed finalization; it does not itself seal successful output.
 */
@FunctionalInterface
public interface StreamingResponseWriter {
	/**
	 * @param responseStream this execution's output, context, and resource owner
	 * @throws Exception if production fails
	 */
	void writeTo(@NonNull ResponseStream responseStream) throws Exception;
}
