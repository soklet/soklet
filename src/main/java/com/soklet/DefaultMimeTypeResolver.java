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
				// Web documents and scripts
				Map.entry("html", "text/html; charset=UTF-8"),
				Map.entry("htm", "text/html; charset=UTF-8"),
				Map.entry("css", "text/css; charset=UTF-8"),
				Map.entry("js", "text/javascript; charset=UTF-8"),
				Map.entry("mjs", "text/javascript; charset=UTF-8"),

				// Web application data
				Map.entry("json", "application/json; charset=UTF-8"),
				Map.entry("map", "application/json"),
				Map.entry("webmanifest", "application/manifest+json"),
				Map.entry("txt", "text/plain; charset=UTF-8"),
				Map.entry("xml", "application/xml; charset=UTF-8"),
				Map.entry("xhtml", "application/xhtml+xml"),
				Map.entry("atom", "application/atom+xml"),
				Map.entry("rss", "application/rss+xml"),
				Map.entry("csv", "text/csv; charset=UTF-8"),
				Map.entry("md", "text/markdown; charset=UTF-8"),
				Map.entry("markdown", "text/markdown; charset=UTF-8"),
				Map.entry("yaml", "application/yaml"),
				Map.entry("yml", "application/yaml"),
				Map.entry("jsonld", "application/ld+json"),
				Map.entry("ndjson", "application/x-ndjson"),

				// Images
				Map.entry("svg", "image/svg+xml"),
				Map.entry("png", "image/png"),
				Map.entry("jpg", "image/jpeg"),
				Map.entry("jpeg", "image/jpeg"),
				Map.entry("gif", "image/gif"),
				Map.entry("webp", "image/webp"),
				Map.entry("avif", "image/avif"),
				Map.entry("jxl", "image/jxl"),
				Map.entry("heic", "image/heic"),
				Map.entry("heif", "image/heif"),
				Map.entry("apng", "image/apng"),
				Map.entry("bmp", "image/bmp"),
				Map.entry("tiff", "image/tiff"),
				Map.entry("tif", "image/tiff"),
				Map.entry("ico", "image/x-icon"),

				// Documents and binaries commonly served by web apps
				Map.entry("pdf", "application/pdf"),
				Map.entry("wasm", "application/wasm"),

				// Fonts
				Map.entry("woff", "font/woff"),
				Map.entry("woff2", "font/woff2"),
				Map.entry("ttf", "font/ttf"),
				Map.entry("otf", "font/otf"),

				// Audio
				Map.entry("mp3", "audio/mpeg"),
				Map.entry("wav", "audio/wav"),
				Map.entry("ogg", "audio/ogg"),
				Map.entry("m4a", "audio/mp4"),
				Map.entry("aac", "audio/aac"),
				Map.entry("flac", "audio/flac"),
				Map.entry("opus", "audio/opus"),

				// Video
				Map.entry("mp4", "video/mp4"),
				Map.entry("webm", "video/webm"),
				Map.entry("ogv", "video/ogg"),
				Map.entry("mov", "video/quicktime"),
				Map.entry("m4v", "video/mp4"),

				// Streaming manifests and captions
				Map.entry("m3u8", "application/vnd.apple.mpegurl"),
				Map.entry("mpd", "application/dash+xml"),
				Map.entry("vtt", "text/vtt; charset=UTF-8")
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
