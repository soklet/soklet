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
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class DefaultMimeTypeResolver implements MimeTypeResolver {
	@NonNull
	private static final DefaultMimeTypeResolver INSTANCE;
	@NonNull
	private static final Map<@NonNull String, @NonNull String> CONTENT_TYPES_BY_EXTENSION;

	static {
		INSTANCE = new DefaultMimeTypeResolver();
		CONTENT_TYPES_BY_EXTENSION = Map.ofEntries(
				Map.entry("html", "text/html; charset=UTF-8"),
				Map.entry("htm", "text/html; charset=UTF-8"),
				Map.entry("css", "text/css; charset=UTF-8"),
				Map.entry("js", "text/javascript; charset=UTF-8"),
				Map.entry("mjs", "text/javascript; charset=UTF-8"),
				Map.entry("json", "application/json; charset=UTF-8"),
				Map.entry("txt", "text/plain; charset=UTF-8"),
				Map.entry("xml", "application/xml; charset=UTF-8"),
				Map.entry("svg", "image/svg+xml"),
				Map.entry("png", "image/png"),
				Map.entry("jpg", "image/jpeg"),
				Map.entry("jpeg", "image/jpeg"),
				Map.entry("gif", "image/gif"),
				Map.entry("webp", "image/webp"),
				Map.entry("avif", "image/avif"),
				Map.entry("ico", "image/x-icon"),
				Map.entry("pdf", "application/pdf"),
				Map.entry("wasm", "application/wasm"),
				Map.entry("woff", "font/woff"),
				Map.entry("woff2", "font/woff2")
		);
	}

	private DefaultMimeTypeResolver() {}

	@NonNull
	static DefaultMimeTypeResolver defaultInstance() {
		return INSTANCE;
	}

	@Override
	@NonNull
	public Optional<String> contentTypeFor(@NonNull Path path) {
		requireNonNull(path);

		String extension = extensionFor(path).orElse(null);

		if (extension == null)
			return Optional.empty();

		return Optional.ofNullable(CONTENT_TYPES_BY_EXTENSION.get(extension));
	}

	@NonNull
	private static Optional<String> extensionFor(@NonNull Path path) {
		requireNonNull(path);

		String filename = path.getFileName() == null ? "" : path.getFileName().toString();
		int lastDotIndex = filename.lastIndexOf('.');

		if (lastDotIndex < 0 || lastDotIndex == filename.length() - 1)
			return Optional.empty();

		String extension = filename.substring(lastDotIndex + 1).toLowerCase(Locale.US);
		return Optional.of(extension);
	}
}
