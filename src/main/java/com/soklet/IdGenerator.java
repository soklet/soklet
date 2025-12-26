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

package com.soklet;

import javax.annotation.Nonnull;

import static java.util.Objects.requireNonNull;

/**
 * Contract for generating {@link Request} identifiers of a particular type (for example, sequential {@link Long} values, random {@link java.util.UUID}s, etc.)
 * <p>
 * Useful for incorporating request data in nonlocal deployment environments (e.g. a tracing header like <a href="https://docs.aws.amazon.com/elasticloadbalancing/latest/application/load-balancer-request-tracing.html" target="_blank">{@code X-Amzn-Trace-Id}</a>).
 * <p>
 * Implementations may or may not guarantee uniqueness, ordering, or repeatability of generated identifiers. Callers should not assume any such guarantees unless documented by the implementation.
 * <p>
 * Standard threadsafe implementations can be acquired via these factory methods:
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
	 * Generates an identifier for the given {@link Request}.
	 * <p>
	 * Implementations may choose different strategies (sequential, random, host-based, etc.)
	 * and are not required to guarantee uniqueness unless explicitly documented.
	 * <p>
	 * Implementations may override to incorporate request data (e.g. a tracing header like {@code X-Amzn-Trace-Id}).
	 *
	 * @param request the request for which an identifier is being generated
	 * @return the generated identifier (never {@code null})
	 */
	@Nonnull
	T generateId(@Nonnull Request request);

	/**
	 * Acquires a threadsafe {@link IdGenerator} with a default numeric ID range (values will wrap once maximum is reached) and a best-effort local IP prefix.
	 * <p>
	 * This method is guaranteed to return a new instance.
	 *
	 * @return an {@code IdGenerator} with default settings
	 */
	@Nonnull
	static IdGenerator<String> withDefaults() {
		return DefaultIdGenerator.withDefaults();
	}

	/**
	 * Acquires a threadsafe {@link IdGenerator} with the given minimum and maximum ID values (values will wrap once maximum is reached), and a best-effort local IP prefix.
	 * <p>
	 * This method is guaranteed to return a new instance.
	 *
	 * @param minimumId the lowest ID that may be generated (inclusive)
	 * @param maximumId the highest ID that may be generated (inclusive)
	 * @return an {@code IdGenerator} configured with the given range
	 */
	@Nonnull
	static IdGenerator<String> withRange(@Nonnull Long minimumId,
																			 @Nonnull Long maximumId) {
		requireNonNull(minimumId);
		requireNonNull(maximumId);
		return DefaultIdGenerator.withRange(minimumId, maximumId);
	}

	/**
	 * Returns a threadsafe {@link IdGenerator} with the given minimum and maximum ID values (values will wrap once maximum is reached), and the specified prefix.
	 * <p>
	 * This method is guaranteed to return a new instance.
	 *
	 * @param minimumId the lowest ID that may be generated (inclusive)
	 * @param maximumId the highest ID that may be generated (inclusive)
	 * @param prefix    a string to prepend to the generated numeric ID
	 * @return an {@code IdGenerator} configured with the given range and prefix
	 */
	@Nonnull
	static IdGenerator<String> withRangeAndPrefix(@Nonnull Long minimumId,
																								@Nonnull Long maximumId,
																								@Nonnull String prefix) {
		requireNonNull(minimumId);
		requireNonNull(maximumId);
		requireNonNull(prefix);
		return DefaultIdGenerator.withRangeAndPrefix(minimumId, maximumId, prefix);
	}

	/**
	 * Acquires a threadsafe {@link IdGenerator} with a default numeric ID range (values will wrap once maximum is reached) and the given prefix.
	 * <p>
	 * This method is guaranteed to return a new instance.
	 *
	 * @param prefix a string to prepend to the generated numeric ID
	 * @return an {@code IdGenerator} configured with the given prefix
	 */
	@Nonnull
	static IdGenerator<String> withPrefix(@Nonnull String prefix) {
		requireNonNull(prefix);
		return DefaultIdGenerator.withPrefix(prefix);
	}
}
