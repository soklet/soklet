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
 * <p>
 * {@link StaticFiles}' default resolver uses a curated deterministic extension map for common web
 * assets. It does not call {@link java.nio.file.Files#probeContentType(Path)}. A custom resolver
 * fully replaces that default, and {@link Optional#empty()} means no {@code Content-Type} header is
 * emitted.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@FunctionalInterface
public interface MimeTypeResolver {
	/**
	 * Resolves the {@code Content-Type} for {@code path}.
	 * <p>
	 * When configured through {@link StaticFiles.Builder#mimeTypeResolver(MimeTypeResolver)}, this
	 * resolver fully replaces the default resolver. To extend Soklet's default extension map, delegate
	 * to {@link #defaultInstance()} for paths your implementation does not handle.
	 *
	 * @param path the resolved file path being served
	 * @return the content type to emit, or {@link Optional#empty()} to omit it
	 */
	@NonNull
	Optional<String> contentTypeFor(@NonNull Path path);

	/**
	 * Acquires Soklet's default threadsafe {@link MimeTypeResolver}.
	 * <p>
	 * The default resolver uses a curated deterministic extension map for common web assets and returns
	 * {@link Optional#empty()} for unknown extensions.
	 *
	 * @return the default {@code MimeTypeResolver}
	 */
	@NonNull
	static MimeTypeResolver defaultInstance() {
		return DefaultMimeTypeResolver.defaultInstance();
	}
}
