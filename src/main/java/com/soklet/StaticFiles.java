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
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * Safe helper for serving files from a configured root directory.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class StaticFiles {
	@NonNull
	private static final Integer MAX_RELATIVE_PATH_UTF_8_LENGTH;
	@NonNull
	private static final Integer MAX_RELATIVE_PATH_SEGMENTS;

	static {
		MAX_RELATIVE_PATH_UTF_8_LENGTH = 4096;
		MAX_RELATIVE_PATH_SEGMENTS = 256;
	}

	@NonNull
	private final Path root;
	@NonNull
	private final Path noFollowRoot;
	@NonNull
	private final Path followRoot;
	@NonNull
	private final List<@NonNull String> indexFileNames;
	@NonNull
	private final MimeTypeResolver mimeTypeResolver;
	@NonNull
	private final EntityTagResolver entityTagResolver;
	@NonNull
	private final LastModifiedResolver lastModifiedResolver;
	@NonNull
	private final CacheControlResolver cacheControlResolver;
	@NonNull
	private final HeadersResolver headersResolver;
	@NonNull
	private final RangeRequestsResolver rangeRequestsResolver;
	@NonNull
	private final Boolean followSymlinks;

	/**
	 * Begins configuration for serving files under {@code root}.
	 * <p>
	 * Relative roots are resolved against the JVM's current working directory when the builder is
	 * built. Production deployments should generally pass an absolute path.
	 * <p>
	 * File matching follows the underlying filesystem's case-sensitivity and Unicode normalization
	 * rules. Production deployments that need portable behavior across operating systems should
	 * standardize on ASCII-safe asset names.
	 *
	 * @param root the static-file root directory
	 * @return a builder for static-file responses
	 */
	@NonNull
	public static Builder withRoot(@NonNull Path root) {
		requireNonNull(root);
		return new Builder(root);
	}

	private StaticFiles(@NonNull Builder builder) {
		requireNonNull(builder);

		this.root = builder.root.toAbsolutePath().normalize();
		this.followSymlinks = builder.followSymlinks == null ? false : builder.followSymlinks;
		this.indexFileNames = List.copyOf(builder.indexFileNames == null ? List.of() : builder.indexFileNames);
		this.mimeTypeResolver = defaultingMimeTypeResolver(builder.mimeTypeResolver);
		this.entityTagResolver = builder.entityTagResolver == null ? EntityTagResolver.defaultInstance() : builder.entityTagResolver;
		this.lastModifiedResolver = builder.lastModifiedResolver == null ? LastModifiedResolver.fromAttributes() : builder.lastModifiedResolver;
		this.cacheControlResolver = builder.cacheControlResolver == null ? CacheControlResolver.disabledInstance() : builder.cacheControlResolver;
		this.headersResolver = builder.headersResolver == null ? HeadersResolver.disabledInstance() : builder.headersResolver;
		this.rangeRequestsResolver = builder.rangeRequestsResolver == null ? RangeRequestsResolver.enabledInstance() : builder.rangeRequestsResolver;

		for (String indexFileName : this.indexFileNames)
			validateIndexFileName(indexFileName);

		try {
			this.noFollowRoot = this.root.toRealPath(LinkOption.NOFOLLOW_LINKS);
			this.followRoot = this.root.toRealPath();
		} catch (IOException e) {
			throw new UncheckedIOException(format("Unable to resolve static file root '%s'.", this.root), e);
		}

		if (getFollowSymlinks()) {
			if (!Files.isDirectory(this.followRoot))
				throw new IllegalArgumentException(format("Static file root '%s' is not a directory.", this.root));
		} else if (!Files.isDirectory(this.noFollowRoot, LinkOption.NOFOLLOW_LINKS)) {
			throw new IllegalArgumentException(format("Static file root '%s' is not a directory.", this.root));
		}
	}

	/**
	 * Produces a marshaled response for {@code relativePath}, if the request targets a safe, readable
	 * file under the configured root.
	 * <p>
	 * Returns {@link Optional#empty()} for HTTP methods other than {@link HttpMethod#GET} and
	 * {@link HttpMethod#HEAD}, unsafe path input, directories without a configured index file,
	 * unreadable files, and missing files. Once a path resolves, non-{@code 200} file responses such as
	 * {@code 206}, {@code 304}, {@code 412}, and {@code 416} are returned inside the optional.
	 * <p>
	 * Resolvers receive the resolved file path after index-file resolution, not the original
	 * {@code relativePath}.
	 *
	 * @param request the incoming request
	 * @param relativePath the root-relative file path
	 * @return the marshaled response, if a static file was resolved
	 */
	@NonNull
	public Optional<MarshaledResponse> marshaledResponseFor(@NonNull Request request,
																													@NonNull String relativePath) {
		requireNonNull(request);
		requireNonNull(relativePath);

		if (request.getHttpMethod() != HttpMethod.GET && request.getHttpMethod() != HttpMethod.HEAD)
			return Optional.empty();

		ResolvedFile resolvedFile = resolvedFileFor(relativePath).orElse(null);

		if (resolvedFile == null)
			return Optional.empty();

		Path file = resolvedFile.path();
		BasicFileAttributes attributes = resolvedFile.attributes();
		EntityTag entityTag = requireNonNull(getEntityTagResolver().entityTagFor(file, attributes), "entityTagResolver returned null; use Optional.empty() to omit the header.").orElse(null);
		Instant lastModified = requireNonNull(getLastModifiedResolver().lastModifiedFor(file, attributes), "lastModifiedResolver returned null; use Optional.empty() to omit the header.").orElse(null);
		String cacheControl = requireNonNull(getCacheControlResolver().cacheControlFor(file, attributes), "cacheControlResolver returned null; use Optional.empty() to omit the header.").orElse(null);
		Map<String, Set<String>> headers = requireNonNull(getHeadersResolver().headersFor(file, attributes), "headersResolver returned null; return an empty map to omit extra headers.");
		Boolean rangeRequests = requireNonNull(getRangeRequestsResolver().rangeRequestsFor(file, attributes), "rangeRequestsResolver returned null; return false to disable range requests.");
		String contentType = requireNonNull(getMimeTypeResolver().contentTypeFor(file), "mimeTypeResolver returned null; use Optional.empty() to omit Content-Type.").orElse(null);

		MarshaledResponse response = FileResponse.withPathAndAttributes(file, attributes)
				.contentType(contentType)
				.entityTag(entityTag)
				.lastModified(lastModified)
				.cacheControl(cacheControl)
				.headers(headers)
				.rangeRequests(rangeRequests)
				.build()
				.marshaledResponseFor(request);

		return Optional.of(response);
	}

	@NonNull
	private Path getResolutionRoot() {
		return getFollowSymlinks() ? this.followRoot : this.noFollowRoot;
	}

	@NonNull
	private Boolean getFollowSymlinks() {
		return this.followSymlinks;
	}

	@NonNull
	private List<@NonNull String> getIndexFileNames() {
		return this.indexFileNames;
	}

	@NonNull
	private MimeTypeResolver getMimeTypeResolver() {
		return this.mimeTypeResolver;
	}

	@NonNull
	private EntityTagResolver getEntityTagResolver() {
		return this.entityTagResolver;
	}

	@NonNull
	private LastModifiedResolver getLastModifiedResolver() {
		return this.lastModifiedResolver;
	}

	@NonNull
	private CacheControlResolver getCacheControlResolver() {
		return this.cacheControlResolver;
	}

	@NonNull
	private HeadersResolver getHeadersResolver() {
		return this.headersResolver;
	}

	@NonNull
	private RangeRequestsResolver getRangeRequestsResolver() {
		return this.rangeRequestsResolver;
	}

	@NonNull
	private Optional<ResolvedFile> resolvedFileFor(@NonNull String relativePath) {
		requireNonNull(relativePath);

		if (relativePath.getBytes(StandardCharsets.UTF_8).length > MAX_RELATIVE_PATH_UTF_8_LENGTH)
			return Optional.empty();

		if (relativePath.split("/", -1).length > MAX_RELATIVE_PATH_SEGMENTS)
			return Optional.empty();

		if (containsControlCharacter(relativePath))
			return Optional.empty();

		if (relativePath.isEmpty() && getIndexFileNames().isEmpty())
			return Optional.empty();

		if (looksLikeWindowsDrivePath(relativePath) || looksLikeUncPath(relativePath) || relativePath.indexOf('\\') >= 0)
			return Optional.empty();

		Path relative;

		try {
			relative = FileSystems.getDefault().getPath(relativePath);
		} catch (InvalidPathException e) {
			return Optional.empty();
		}

		if (relative.isAbsolute())
			return Optional.empty();

		for (Path segment : relative) {
			if ("..".equals(segment.toString()))
				return Optional.empty();
		}

		Path candidate = getResolutionRoot().resolve(relative).normalize();

		if (!candidate.startsWith(getResolutionRoot()))
			return Optional.empty();

		return resolvedFileForCandidate(candidate);
	}

	@NonNull
	private Optional<ResolvedFile> resolvedFileForCandidate(@NonNull Path candidate) {
		requireNonNull(candidate);

		if (getFollowSymlinks())
			return followedResolvedFileForCandidate(candidate);

		return noFollowResolvedFileForCandidate(candidate);
	}

	@NonNull
	private Optional<ResolvedFile> noFollowResolvedFileForCandidate(@NonNull Path candidate) {
		requireNonNull(candidate);

		if (hasSymlinkComponent(candidate))
			return Optional.empty();

		if (Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) {
			for (String indexFileName : getIndexFileNames()) {
				Path indexCandidate = candidate.resolve(indexFileName).normalize();

				if (!indexCandidate.startsWith(getResolutionRoot()) || hasSymlinkComponent(indexCandidate))
					continue;

				Optional<ResolvedFile> resolvedIndexFile = noFollowRegularFile(indexCandidate);

				if (resolvedIndexFile.isPresent())
					return resolvedIndexFile;
			}

			return Optional.empty();
		}

		return noFollowRegularFile(candidate);
	}

	@NonNull
	private Optional<ResolvedFile> noFollowRegularFile(@NonNull Path candidate) {
		requireNonNull(candidate);

		if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(candidate))
			return Optional.empty();

		try {
			return Optional.of(new ResolvedFile(candidate, Files.readAttributes(candidate, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)));
		} catch (IOException e) {
			return Optional.empty();
		}
	}

	@NonNull
	private Optional<ResolvedFile> followedResolvedFileForCandidate(@NonNull Path candidate) {
		requireNonNull(candidate);

		Path realCandidate;

		try {
			realCandidate = candidate.toRealPath();
		} catch (IOException e) {
			return Optional.empty();
		}

		if (!realCandidate.startsWith(this.followRoot))
			return Optional.empty();

		if (Files.isDirectory(realCandidate)) {
			for (String indexFileName : getIndexFileNames()) {
				Path indexCandidate = realCandidate.resolve(indexFileName).normalize();

				if (!indexCandidate.startsWith(this.followRoot))
					continue;

				Optional<ResolvedFile> resolvedIndexFile = followedRegularFile(indexCandidate);

				if (resolvedIndexFile.isPresent())
					return resolvedIndexFile;
			}

			return Optional.empty();
		}

		return followedRegularFile(realCandidate);
	}

	@NonNull
	private Optional<ResolvedFile> followedRegularFile(@NonNull Path candidate) {
		requireNonNull(candidate);

		if (!Files.isRegularFile(candidate) || !Files.isReadable(candidate))
			return Optional.empty();

		try {
			Path realCandidate = candidate.toRealPath();

			if (!realCandidate.startsWith(this.followRoot))
				return Optional.empty();

			return Optional.of(new ResolvedFile(realCandidate, Files.readAttributes(realCandidate, BasicFileAttributes.class)));
		} catch (IOException e) {
			return Optional.empty();
		}
	}

	@NonNull
	private Boolean hasSymlinkComponent(@NonNull Path candidate) {
		requireNonNull(candidate);

		Path relative = getResolutionRoot().relativize(candidate);
		Path current = getResolutionRoot();

		for (Path segment : relative) {
			current = current.resolve(segment);

			if (Files.isSymbolicLink(current))
				return true;
		}

		return false;
	}

	private static void validateIndexFileName(@NonNull String indexFileName) {
		requireNonNull(indexFileName);

		if (indexFileName.isEmpty()
				|| containsControlCharacter(indexFileName)
				|| indexFileName.indexOf('/') >= 0
				|| indexFileName.indexOf('\\') >= 0
				|| ".".equals(indexFileName)
				|| "..".equals(indexFileName)
				|| looksLikeWindowsDrivePath(indexFileName)
				|| looksLikeUncPath(indexFileName))
			throw new IllegalArgumentException(format("Invalid index file name '%s'.", indexFileName));
	}

	@NonNull
	private static Boolean containsControlCharacter(@NonNull String value) {
		requireNonNull(value);

		for (int i = 0; i < value.length(); i++) {
			if (Character.isISOControl(value.charAt(i)))
				return true;
		}

		return false;
	}

	@NonNull
	private static Boolean looksLikeWindowsDrivePath(@NonNull String value) {
		requireNonNull(value);
		return value.length() >= 2 && Character.isLetter(value.charAt(0)) && value.charAt(1) == ':';
	}

	@NonNull
	private static Boolean looksLikeUncPath(@NonNull String value) {
		requireNonNull(value);
		return value.startsWith("//") || value.startsWith("\\\\");
	}

	@NonNull
	private static MimeTypeResolver defaultingMimeTypeResolver(@Nullable MimeTypeResolver userResolver) {
		MimeTypeResolver defaultResolver = DefaultMimeTypeResolver.defaultInstance();

		if (userResolver == null)
			return defaultResolver;

		return (path) -> {
			requireNonNull(path);
			Optional<String> contentType = requireNonNull(userResolver.contentTypeFor(path), "mimeTypeResolver returned null; use Optional.empty() to omit Content-Type.");
			return contentType.isPresent() ? contentType : defaultResolver.contentTypeFor(path);
		};
	}

	@NonNull
	private static Map<@NonNull String, @NonNull Set<@NonNull String>> copyHeaders(
			@NonNull Map<@NonNull String, @NonNull Set<@NonNull String>> headers) {
		requireNonNull(headers);

		Map<String, Set<String>> copiedHeaders = new LinkedHashMap<>();

		for (Map.Entry<String, Set<String>> entry : headers.entrySet()) {
			String headerName = requireNonNull(entry.getKey());
			Set<String> copiedHeaderValues = new LinkedHashSet<>(requireNonNull(entry.getValue()));
			copiedHeaderValues.forEach(value -> requireNonNull(value, format("Header '%s' includes a null value.", headerName)));
			copiedHeaders.put(headerName, Collections.unmodifiableSet(copiedHeaderValues));
		}

		return Collections.unmodifiableMap(copiedHeaders);
	}

	private record ResolvedFile(@NonNull Path path,
															@NonNull BasicFileAttributes attributes) {}

	@NotThreadSafe
	public static final class Builder {
		@NonNull
		private final Path root;
		@Nullable
		private List<@NonNull String> indexFileNames;
		@Nullable
		private MimeTypeResolver mimeTypeResolver;
		@Nullable
		private EntityTagResolver entityTagResolver;
		@Nullable
		private LastModifiedResolver lastModifiedResolver;
		@Nullable
		private CacheControlResolver cacheControlResolver;
		@Nullable
		private HeadersResolver headersResolver;
		@Nullable
		private RangeRequestsResolver rangeRequestsResolver;
		@Nullable
		private Boolean followSymlinks;

		private Builder(@NonNull Path root) {
			requireNonNull(root);
			this.root = root;
		}

		@NonNull
		public Builder indexFileNames(@Nullable List<@NonNull String> indexFileNames) {
			this.indexFileNames = indexFileNames;
			return this;
		}

		@NonNull
		public Builder mimeTypeResolver(@Nullable MimeTypeResolver mimeTypeResolver) {
			this.mimeTypeResolver = mimeTypeResolver;
			return this;
		}

		@NonNull
		public Builder entityTagResolver(@Nullable EntityTagResolver entityTagResolver) {
			this.entityTagResolver = entityTagResolver;
			return this;
		}

		@NonNull
		public Builder lastModifiedResolver(@Nullable LastModifiedResolver lastModifiedResolver) {
			this.lastModifiedResolver = lastModifiedResolver;
			return this;
		}

		@NonNull
		public Builder cacheControlResolver(@Nullable CacheControlResolver cacheControlResolver) {
			this.cacheControlResolver = cacheControlResolver;
			return this;
		}

		@NonNull
		public Builder headersResolver(@Nullable HeadersResolver headersResolver) {
			this.headersResolver = headersResolver;
			return this;
		}

		@NonNull
		public Builder rangeRequestsResolver(@Nullable RangeRequestsResolver rangeRequestsResolver) {
			this.rangeRequestsResolver = rangeRequestsResolver;
			return this;
		}

		@NonNull
		public Builder followSymlinks(@Nullable Boolean followSymlinks) {
			this.followSymlinks = followSymlinks;
			return this;
		}

		@NonNull
		public StaticFiles build() {
			return new StaticFiles(this);
		}
	}

	@FunctionalInterface
	public interface EntityTagResolver {
		/**
		 * Returns the default weak metadata-based ETag resolver.
		 * <p>
		 * The default resolver produces a weak ETag derived from the file's last-modified epoch second
		 * and size. This is deterministic across processes serving the same filesystem. Configure a
		 * custom resolver, such as a content-hash resolver, when serving from filesystems that do not
		 * preserve modification times, when same-second overwrites are common, or when stronger
		 * collision resistance is required.
		 *
		 * @return the default entity-tag resolver
		 */
		@NonNull
		static EntityTagResolver defaultInstance() {
			return DefaultEntityTagResolver.defaultInstance();
		}

		@NonNull
		static EntityTagResolver disabledInstance() {
			return DisabledEntityTagResolver.defaultInstance();
		}

		/**
		 * Resolves the ETag for the file being served.
		 * <p>
		 * Implementations must be thread-safe; {@link StaticFiles} invokes resolvers concurrently from
		 * request-handling threads. Resolvers run on the request-handling thread, so expensive work such
		 * as content hashing or network calls should be cached or precomputed.
		 *
		 * @param path the resolved file path being served
		 * @param attributes the file attributes read for this response
		 * @return the ETag to emit, or {@link Optional#empty()} to omit it
		 */
		@NonNull
		Optional<EntityTag> entityTagFor(@NonNull Path path,
																		 @NonNull BasicFileAttributes attributes);
	}

	@FunctionalInterface
	public interface LastModifiedResolver {
		@NonNull
		static LastModifiedResolver fromAttributes() {
			return DefaultLastModifiedResolver.defaultInstance();
		}

		@NonNull
		static LastModifiedResolver disabledInstance() {
			return DisabledLastModifiedResolver.defaultInstance();
		}

		/**
		 * Resolves the {@code Last-Modified} value for the file being served.
		 * <p>
		 * Implementations must be thread-safe; {@link StaticFiles} invokes resolvers concurrently from
		 * request-handling threads.
		 *
		 * @param path the resolved file path being served
		 * @param attributes the file attributes read for this response
		 * @return the last-modified instant to emit, or {@link Optional#empty()} to omit it
		 */
		@NonNull
		Optional<Instant> lastModifiedFor(@NonNull Path path,
																			@NonNull BasicFileAttributes attributes);
	}

	@FunctionalInterface
	public interface CacheControlResolver {
		@NonNull
		static CacheControlResolver fromValue(@NonNull String cacheControl) {
			requireNonNull(cacheControl);
			String normalizedCacheControl = Utilities.trimAggressivelyToNull(cacheControl);

			if (normalizedCacheControl == null)
				throw new IllegalArgumentException("Cache-Control value must not be blank.");

			return (path, attributes) -> Optional.of(normalizedCacheControl);
		}

		@NonNull
		static CacheControlResolver disabledInstance() {
			return DisabledCacheControlResolver.defaultInstance();
		}

		/**
		 * Resolves the {@code Cache-Control} value for the file being served.
		 * <p>
		 * Implementations must be thread-safe; {@link StaticFiles} invokes resolvers concurrently from
		 * request-handling threads.
		 *
		 * @param path the resolved file path being served
		 * @param attributes the file attributes read for this response
		 * @return the cache-control value to emit, or {@link Optional#empty()} to omit it
		 */
		@NonNull
		Optional<String> cacheControlFor(@NonNull Path path,
																		 @NonNull BasicFileAttributes attributes);
	}

	@FunctionalInterface
	public interface HeadersResolver {
		@NonNull
		static HeadersResolver fromHeaders(@NonNull Map<@NonNull String, @NonNull Set<@NonNull String>> headers) {
			requireNonNull(headers);
			Map<String, Set<String>> copiedHeaders = copyHeaders(headers);
			return (path, attributes) -> copiedHeaders;
		}

		@NonNull
		static HeadersResolver disabledInstance() {
			return DisabledHeadersResolver.defaultInstance();
		}

		/**
		 * Resolves extra response headers for the file being served.
		 * <p>
		 * Implementations must be thread-safe; {@link StaticFiles} invokes resolvers concurrently from
		 * request-handling threads.
		 *
		 * @param path the resolved file path being served
		 * @param attributes the file attributes read for this response
		 * @return extra headers to emit
		 */
		@NonNull
		Map<@NonNull String, @NonNull Set<@NonNull String>> headersFor(@NonNull Path path,
																																	 @NonNull BasicFileAttributes attributes);
	}

	@FunctionalInterface
	public interface RangeRequestsResolver {
		@NonNull
		static RangeRequestsResolver enabledInstance() {
			return EnabledRangeRequestsResolver.defaultInstance();
		}

		@NonNull
		static RangeRequestsResolver disabledInstance() {
			return DisabledRangeRequestsResolver.defaultInstance();
		}

		/**
		 * Resolves whether byte range requests are enabled for the file being served.
		 * <p>
		 * Implementations must be thread-safe; {@link StaticFiles} invokes resolvers concurrently from
		 * request-handling threads.
		 *
		 * @param path the resolved file path being served
		 * @param attributes the file attributes read for this response
		 * @return {@code true} if range requests should be honored, otherwise {@code false}
		 */
		@NonNull
		Boolean rangeRequestsFor(@NonNull Path path,
														 @NonNull BasicFileAttributes attributes);
	}

	@ThreadSafe
	private static final class DisabledEntityTagResolver implements EntityTagResolver {
		@NonNull
		private static final DisabledEntityTagResolver INSTANCE;

		static {
			INSTANCE = new DisabledEntityTagResolver();
		}

		@NonNull
		static DisabledEntityTagResolver defaultInstance() {
			return INSTANCE;
		}

		@Override
		@NonNull
		public Optional<EntityTag> entityTagFor(@NonNull Path path,
																						@NonNull BasicFileAttributes attributes) {
			requireNonNull(path);
			requireNonNull(attributes);
			return Optional.empty();
		}
	}

	@ThreadSafe
	private static final class DefaultLastModifiedResolver implements LastModifiedResolver {
		@NonNull
		private static final DefaultLastModifiedResolver INSTANCE;

		static {
			INSTANCE = new DefaultLastModifiedResolver();
		}

		@NonNull
		static DefaultLastModifiedResolver defaultInstance() {
			return INSTANCE;
		}

		@Override
		@NonNull
		public Optional<Instant> lastModifiedFor(@NonNull Path path,
																						 @NonNull BasicFileAttributes attributes) {
			requireNonNull(path);
			requireNonNull(attributes);
			return Optional.of(Instant.ofEpochSecond(attributes.lastModifiedTime().toInstant().getEpochSecond()));
		}
	}

	@ThreadSafe
	private static final class DisabledLastModifiedResolver implements LastModifiedResolver {
		@NonNull
		private static final DisabledLastModifiedResolver INSTANCE;

		static {
			INSTANCE = new DisabledLastModifiedResolver();
		}

		@NonNull
		static DisabledLastModifiedResolver defaultInstance() {
			return INSTANCE;
		}

		@Override
		@NonNull
		public Optional<Instant> lastModifiedFor(@NonNull Path path,
																						 @NonNull BasicFileAttributes attributes) {
			requireNonNull(path);
			requireNonNull(attributes);
			return Optional.empty();
		}
	}

	@ThreadSafe
	private static final class DefaultEntityTagResolver implements EntityTagResolver {
		@NonNull
		private static final DefaultEntityTagResolver INSTANCE;

		static {
			INSTANCE = new DefaultEntityTagResolver();
		}

		@NonNull
		static DefaultEntityTagResolver defaultInstance() {
			return INSTANCE;
		}

		@Override
		@NonNull
		public Optional<EntityTag> entityTagFor(@NonNull Path path,
																						@NonNull BasicFileAttributes attributes) {
			requireNonNull(path);
			requireNonNull(attributes);
			Long epochSecond = attributes.lastModifiedTime().toInstant().getEpochSecond();
			Long size = attributes.size();
			return Optional.of(EntityTag.fromWeakValue(format("mtime-%d-size-%d", epochSecond, size)));
		}
	}

	@ThreadSafe
	private static final class DisabledCacheControlResolver implements CacheControlResolver {
		@NonNull
		private static final DisabledCacheControlResolver INSTANCE;

		static {
			INSTANCE = new DisabledCacheControlResolver();
		}

		@NonNull
		static DisabledCacheControlResolver defaultInstance() {
			return INSTANCE;
		}

		@Override
		@NonNull
		public Optional<String> cacheControlFor(@NonNull Path path,
																						@NonNull BasicFileAttributes attributes) {
			requireNonNull(path);
			requireNonNull(attributes);
			return Optional.empty();
		}
	}

	@ThreadSafe
	private static final class DisabledHeadersResolver implements HeadersResolver {
		@NonNull
		private static final DisabledHeadersResolver INSTANCE;

		static {
			INSTANCE = new DisabledHeadersResolver();
		}

		@NonNull
		static DisabledHeadersResolver defaultInstance() {
			return INSTANCE;
		}

		@Override
		@NonNull
		public Map<@NonNull String, @NonNull Set<@NonNull String>> headersFor(@NonNull Path path,
																																					@NonNull BasicFileAttributes attributes) {
			requireNonNull(path);
			requireNonNull(attributes);
			return Map.of();
		}
	}

	@ThreadSafe
	private static final class EnabledRangeRequestsResolver implements RangeRequestsResolver {
		@NonNull
		private static final EnabledRangeRequestsResolver INSTANCE;

		static {
			INSTANCE = new EnabledRangeRequestsResolver();
		}

		@NonNull
		static EnabledRangeRequestsResolver defaultInstance() {
			return INSTANCE;
		}

		@Override
		@NonNull
		public Boolean rangeRequestsFor(@NonNull Path path,
																		@NonNull BasicFileAttributes attributes) {
			requireNonNull(path);
			requireNonNull(attributes);
			return true;
		}
	}

	@ThreadSafe
	private static final class DisabledRangeRequestsResolver implements RangeRequestsResolver {
		@NonNull
		private static final DisabledRangeRequestsResolver INSTANCE;

		static {
			INSTANCE = new DisabledRangeRequestsResolver();
		}

		@NonNull
		static DisabledRangeRequestsResolver defaultInstance() {
			return INSTANCE;
		}

		@Override
		@NonNull
		public Boolean rangeRequestsFor(@NonNull Path path,
																		@NonNull BasicFileAttributes attributes) {
			requireNonNull(path);
			requireNonNull(attributes);
			return false;
		}
	}

	@ThreadSafe
	private static final class DefaultMimeTypeResolver implements MimeTypeResolver {
		@NonNull
		private static final DefaultMimeTypeResolver INSTANCE;
		@NonNull
		private static final Map<@NonNull String, @NonNull String> CONTENT_TYPES_BY_EXTENSION;
		@NonNull
		private final Map<@NonNull String, @NonNull Optional<String>> probedContentTypesByExtension;

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

		private DefaultMimeTypeResolver() {
			this.probedContentTypesByExtension = new ConcurrentHashMap<>();
		}

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
				return probeContentType(path);

			Optional<String> probedContentType = this.probedContentTypesByExtension.computeIfAbsent(extension, ignored -> probeContentType(path));
			return probedContentType.isPresent() ? probedContentType : Optional.ofNullable(CONTENT_TYPES_BY_EXTENSION.get(extension));
		}

		@NonNull
		private static Optional<String> probeContentType(@NonNull Path path) {
			requireNonNull(path);

			try {
				String probedContentType = Utilities.trimAggressivelyToNull(Files.probeContentType(path));

				if (probedContentType != null)
					return Optional.of(probedContentType);
			} catch (IOException ignored) {
				// Fall through to extension-based resolution.
			}

			return Optional.empty();
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
}
