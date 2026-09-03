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
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable response-head and bounded nonstreaming-body projection.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public interface McpSimulationResponse {
	/** @return the HTTP status code, between 100 and 599 */
	@NonNull
	Integer getStatusCode();

	/**
	 * Returns an immutable insertion-ordered header projection. Header names are
	 * coalesced case-insensitively under the first observed spelling, and values
	 * retain insertion order.
	 *
	 * @return immutable response headers
	 */
	@NonNull
	Map<@NonNull String, @NonNull Set<@NonNull String>> getHeaders();

	/** @return the captured response-body type */
	@NonNull
	McpSimulationBodyType getBodyType();

	/**
	 * Returns a fresh copy for successfully captured EMPTY and JSON bodies.
	 * EMPTY returns a present zero-length array. SSE responses and JSON responses
	 * that exceed the byte bound return empty.
	 *
	 * @return a fresh body copy when the bounded body is available
	 */
	@NonNull
	Optional<byte @NonNull []> getBody();
}
