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
import java.util.Optional;

/**
 * One immutable, exactly encoded simulated SSE item.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public interface McpSimulationStreamItem {
	/** @return the item kind */
	@NonNull
	McpSimulationStreamItemType getType();

	/** @return the JSON value for a JSON-message item, otherwise empty */
	@NonNull
	Optional<@NonNull McpJsonValue> getMessage();

	/**
	 * @return exactly {@code keepalive} for a keep-alive comment, otherwise empty
	 */
	@NonNull
	Optional<@NonNull String> getComment();

	/**
	 * The bytes are the exact canonical unchunked SSE frame corresponding to the
	 * mutually exclusive message or comment projection.
	 *
	 * @return a fresh copy of the exact unchunked SSE frame
	 */
	byte @NonNull [] getEncodedBytes();
}
