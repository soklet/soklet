/*
 * Copyright 2022-2025 Revetware LLC.
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

package com.soklet.core;

import javax.annotation.Nonnull;

import static java.util.Objects.requireNonNull;

/**
 * Contract for generating identifiers of a particular type (for example, sequential {@link Long} values, random {@link java.util.UUID}s, etc.)
 * <p>
 * Implementations may or may not guarantee uniqueness, ordering, or repeatability of generated identifiers. Callers should not assume any such guarantees unless documented by the implementation.
 * <p>
 * Standard implementations can be acquired via these factory methods:
 * <ul>
 *   <li>{@link #withDefaults()}</li>
 *   <li>{@link #withPrefix(String)}</li>
 *   <li>{@link #withRange(Long, Long)} </li>
 *   <li>{@link #withRangeAndPrefix(Long, Long, String)}</li>
 * </ul>
 *
 * @param <T> the type of identifier produced
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@FunctionalInterface
public interface IdGenerator<T> {
	/**
	 * Generates an identifier.
	 * <p>
	 * Implementations may choose different strategies (sequential, random, host-based, etc.) and are not required to guarantee uniqueness unless explicitly documented.
	 *
	 * @return the generated identifier (never {@code null})
	 */
	@Nonnull
	T generateId();

	/**
	 * Acquires an {@link IdGenerator} with a default numeric ID range (values will wrap once maximum is reached) and a best-effort local IP prefix.
	 * <p>
	 * Callers should not rely on reference identity; this method may return a new or cached instance.
	 *
	 * @return an {@code IdGenerator} with default settings
	 */
	@Nonnull
	static IdGenerator withDefaults() {
		return DefaultIdGenerator.withDefaults();
	}

	/**
	 * Acquires an {@link IdGenerator} with the given minimum and maximum ID values (values will wrap once maximum is reached), and a best-effort local IP prefix.
	 * <p>
	 * Callers should not rely on reference identity; this method may return a new or cached instance.
	 *
	 * @param minimumId the lowest ID that may be generated (inclusive)
	 * @param maximumId the highest ID that may be generated (inclusive)
	 * @return an {@code IdGenerator} configured with the given range
	 */
	@Nonnull
	static IdGenerator withRange(@Nonnull Long minimumId,
															 @Nonnull Long maximumId) {
		requireNonNull(minimumId);
		requireNonNull(maximumId);
		return DefaultIdGenerator.withRange(minimumId, maximumId);
	}

	/**
	 * Returns a {@link DefaultIdGenerator} with the given minimum and maximum ID values (values will wrap once maximum is reached), and the specified prefix.
	 * <p>
	 * Callers should not rely on reference identity; this method may return a new or cached instance.
	 *
	 * @param minimumId the lowest ID that may be generated (inclusive)
	 * @param maximumId the highest ID that may be generated (inclusive)
	 * @param prefix    a string to prepend to the generated numeric ID
	 * @return an {@code IdGenerator} configured with the given range and prefix
	 */
	@Nonnull
	static IdGenerator withRangeAndPrefix(@Nonnull Long minimumId,
																				@Nonnull Long maximumId,
																				@Nonnull String prefix) {
		requireNonNull(minimumId);
		requireNonNull(maximumId);
		requireNonNull(prefix);
		return DefaultIdGenerator.withRangeAndPrefix(minimumId, maximumId, prefix);
	}

	/**
	 * Acquires an {@link IdGenerator} with a default numeric ID range (values will wrap once maximum is reached) and the given prefix.
	 * <p>
	 * Callers should not rely on reference identity; this method may return a new or cached instance.
	 *
	 * @param prefix a string to prepend to the generated numeric ID
	 * @return an {@code IdGenerator} configured with the given prefix
	 */
	@Nonnull
	static IdGenerator withPrefix(@Nonnull String prefix) {
		requireNonNull(prefix);
		return DefaultIdGenerator.withPrefix(prefix);
	}
}