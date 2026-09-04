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
 * Thread-safe request-local lookup against one captured immutable translation
 * snapshot.
 * <p>
 * Lookups must be bounded, in-memory, nonblocking, and deterministic. An
 * operational lookup failure is represented by
 * {@link McpLocalizationResult#failure()} rather than thrown.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
@FunctionalInterface
public interface McpLocalizationLookup {
	/**
	 * Localizes one framework-owned source-text field.
	 *
	 * @param text structured coordinate and canonical source text
	 * @return non-null localization result
	 */
	@NonNull
	McpLocalizationResult localize(@NonNull McpLocalizableText text);
}
