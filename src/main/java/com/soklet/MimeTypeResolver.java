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

import java.nio.file.Path;
import java.util.Optional;

/**
 * Resolves a file path to an HTTP {@code Content-Type} value.
 * <p>
 * Implementations used with {@link StaticFiles} must be thread-safe; {@link StaticFiles} invokes
 * resolvers concurrently from request-handling threads.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@FunctionalInterface
public interface MimeTypeResolver {
	/**
	 * Resolves the {@code Content-Type} for {@code path}.
	 *
	 * @param path the resolved file path being served
	 * @return the content type to emit, or {@link Optional#empty()} to omit it
	 */
	@NonNull
	Optional<String> contentTypeFor(@NonNull Path path);
}
