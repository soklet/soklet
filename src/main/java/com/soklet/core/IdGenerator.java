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

/**
 * Contract for generating identifiers of a particular type (for example, sequential {@link Long} values, random {@link java.util.UUID}s, etc.)
 * <p>
 * Implementations may or may not guarantee uniqueness, ordering, or repeatability of generated identifiers. Callers should not assume any such guarantees unless documented by the implementation.
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
}