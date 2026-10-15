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
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.FileSystemException;
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * Helper for serving files from an explicitly trusted, immutable root directory.
 * The builder requires {@link Builder#trustedImmutableRoot(Boolean) trustedImmutableRoot(true)}:
 * only the application operator may control the root, its ancestors, links, and files, and they
 * must remain unchanged while the helper or its responses are in use. Upload directories and
 * other attacker-writable trees are not supported.
 * <p>
 * Delivery uses public NIO APIs, with {@link java.nio.file.SecureDirectoryStream} where available
 * and a checked pathname fallback otherwise (including macOS Java 21 and Windows). Providers
 * without file keys use creation time, size, and modification time to detect observed replacement.
 * Checks reject observed changes; they do not make concurrent mutation safe. Public Java APIs cannot bind regular-file attributes to an opened channel, and copying
 * bytes does not close that identity race. No JDK internals, native-access flags, or retained
 * directory descriptors are used.
 * <p>
 * The resolved root identity is captured at construction. Rebuild the helper when intentionally
 * switching deployment roots, after in-flight responses have completed. Search-only directories
 * use checked pathname access when public secure-directory handles cannot be acquired.
 *

 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class StaticFiles {
	@NonNull
	private static final Integer MAX_RELATIVE_PATH_UTF_8_LENGTH;
	@NonNull
	private static final Integer MAX_RELATIVE_PATH_SEGMENTS;
	@NonNull
	private static final Integer CONTENT_HASH_BUFFER_SIZE;
	@NonNull
	private static final HexFormat HEX_FORMAT;

	static {
		MAX_RELATIVE_PATH_UTF_8_LENGTH = 4096;
		MAX_RELATIVE_PATH_SEGMENTS = 256;
		CONTENT_HASH_BUFFER_SIZE = 8192;
		HEX_FORMAT = HexFormat.of();
	}

	@NonNull
	private final Path root;
	@NonNull
	private final Path noFollowRoot;
	@NonNull
	private final Path followRoot;
	@NonNull
	private final SecureStaticFileRoot secureRoot;
	@NonNull
	private final List<@NonNull String> indexFileNames;
	@NonNull
	private final MimeTypeResolver mimeTypeResolver;
	@NonNull
	private final EntityTagResolver entityTagResolver;
	@NonNull
	private final AccessResolver accessResolver;
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

		if (!Boolean.TRUE.equals(builder.trustedImmutableRoot))
			throw new IllegalArgumentException("StaticFiles requires trustedImmutableRoot(true): the root, ancestors, links, and files must be operator-controlled and immutable while in use.");

		this.root = builder.root.toAbsolutePath().normalize();
		this.followSymlinks = builder.followSymlinks == null ? false : builder.followSymlinks;
		this.indexFileNames = List.copyOf(builder.indexFileNames == null ? List.of() : builder.indexFileNames);
		this.mimeTypeResolver = defaultingMimeTypeResolver(builder.mimeTypeResolver);
		this.entityTagResolver = builder.entityTagResolver == null ? EntityTagResolver.defaultInstance() : builder.entityTagResolver;
		this.accessResolver = builder.accessResolver == null ? AccessResolver.allowAllInstance() : builder.accessResolver;
		this.lastModifiedResolver = builder.lastModifiedResolver == null ? LastModifiedResolver.fromAttributes() : builder.lastModifiedResolver;
		this.cacheControlResolver = builder.cacheControlResolver == null ? CacheControlResolver.disabledInstance() : builder.cacheControlResolver;
		this.headersResolver = builder.headersResolver == null ? HeadersResolver.disabledInstance() : builder.headersResolver;
		this.rangeRequestsResolver = builder.rangeRequestsResolver == null ? RangeRequestsResolver.enabledInstance() : builder.rangeRequestsResolver;

		for (String indexFileName : this.indexFileNames)
			validateIndexFileName(indexFileName);

		try {
			this.followRoot = this.root.toRealPath();
			// Preserve the configured spelling for no-follow resolver paths. The
			// delivery root identity is captured below, so observed replacement fails closed.
			this.noFollowRoot = this.root;
		} catch (IOException e) {
			throw new UncheckedIOException(format("Unable to resolve static file root '%s'.", this.root), e);
		}

		if (getFollowSymlinks()) {
			if (!Files.isDirectory(this.followRoot))
				throw new IllegalArgumentException(format("Static file root '%s' is not a directory.", this.root));
		} else if (Files.isSymbolicLink(this.noFollowRoot)) {
			throw new IllegalArgumentException(format("Static file root '%s' is a symbolic link; enable followSymlinks to use it.", this.root));
		} else if (!Files.isDirectory(this.noFollowRoot, LinkOption.NOFOLLOW_LINKS)) {
			throw new IllegalArgumentException(format("Static file root '%s' is not a directory.", this.root));
		}
		this.secureRoot = new SecureStaticFileRoot(getResolutionRoot(), this.root);
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
	 * @param relativePath the root-relative file path
	 * @param request the incoming request
	 * @return the marshaled response, if a static file was resolved
	 * @throws UncheckedIOException if filesystem access fails for a reason other than a missing,
	 *         inaccessible, or changed file
	 */
	@NonNull
	public Optional<@NonNull MarshaledResponse> marshaledResponseFor(@NonNull String relativePath,
																													@NonNull Request request) {
		requireNonNull(relativePath);
		requireNonNull(request);

		if (request.getHttpMethod() != HttpMethod.GET && request.getHttpMethod() != HttpMethod.HEAD)
			return Optional.empty();

		ResolvedFile resolvedFile = resolvedFileFor(relativePath).orElse(null);

		if (resolvedFile == null)
			return Optional.empty();

		Path file = resolvedFile.path();
		BasicFileAttributes attributes = resolvedFile.attributes();
		// Path resolution can race a rename of an ancestor. Check the selected
		// object against the captured root before any application resolver sees its
		// metadata. These checks supplement the required immutable-root contract.
		try (SeekableByteChannel ignored = secureRoot.open(file, attributes)) {
			// The selected path still matches its expected identity and containment.
		} catch (UnsupportedOperationException failure) {
			return Optional.empty();
		} catch (IOException failure) {
			return unresolvedFile(failure);
		}
		Access access = requireNonNull(getAccessResolver().accessFor(file, attributes), "accessResolver returned null; return Access.ALLOW, Access.DENY, or Access.HIDE.");

		if (access == Access.HIDE)
			return Optional.empty();

		if (access == Access.DENY)
			// Access denial is not a file representation, so validators and file headers do not apply.
			return Optional.of(MarshaledResponse.fromStatusCode(StatusCode.HTTP_403.getStatusCode()));

		EntityTag entityTag;
		if (getEntityTagResolver() == ContentHashEntityTagResolver.defaultInstance()) {
			// The built-in content hash must read the same approved object class as
			// delivery. In particular, do not follow a link swapped in by a resolver.
			try (SeekableByteChannel channel = secureRoot.open(file, attributes)) {
				entityTag = ContentHashEntityTagResolver.entityTagFor(channel);
			} catch (UnsupportedOperationException failure) {
				return Optional.empty();
			} catch (IOException failure) {
				return unresolvedFile(failure);
			}
		} else {
			entityTag = requireNonNull(getEntityTagResolver().entityTagFor(file, attributes),
					"entityTagResolver returned null; use Optional.empty() to omit the header.").orElse(null);
		}
		Instant lastModified = requireNonNull(getLastModifiedResolver().lastModifiedFor(file, attributes), "lastModifiedResolver returned null; use Optional.empty() to omit the header.").orElse(null);
		String cacheControl = requireNonNull(getCacheControlResolver().cacheControlFor(file, attributes), "cacheControlResolver returned null; use Optional.empty() to omit the header.").orElse(null);
		Map<String, List<String>> headers = requireNonNull(getHeadersResolver().headersFor(file, attributes), "headersResolver returned null; return an empty map to omit extra headers.");
		FileResponse.rejectControlledHeaderConflicts(headers, "StaticFiles.HeadersResolver");
		Boolean rangeRequests = requireNonNull(getRangeRequestsResolver().rangeRequestsFor(file, attributes), "rangeRequestsResolver returned null; return false to disable range requests.");
		String contentType = requireNonNull(getMimeTypeResolver().contentTypeFor(file), "mimeTypeResolver returned null; use Optional.empty() to omit Content-Type.").orElse(null);

		// Resolvers can execute application code. Recheck the selected path and identity
		// before response creation; delivery checks again against the captured root.
		ResolvedFile current = getFollowSymlinks()
				? followedRegularFile(file).orElse(null)
				: noFollowResolvedFileForCandidate(file).orElse(null);
		if (current == null || !current.path().equals(file)
				|| !sameFileIdentity(attributes, current.attributes()))
			return Optional.empty();
		try (SeekableByteChannel ignored = secureRoot.open(file, attributes)) {
			// Bodyless responses need the same post-resolver validation as body responses.
		} catch (UnsupportedOperationException failure) {
			return Optional.empty();
		} catch (IOException failure) {
			return unresolvedFile(failure);
		}

		MarshaledResponse response = MarshaledResponse.withFile(file, request, attributes)
				.contentType(contentType)
				.entityTag(entityTag)
				.lastModified(lastModified)
				.cacheControl(cacheControl)
				.headers(headers)
				.rangeRequests(rangeRequests)
				.build();
		if (response.getBody().orElse(null) instanceof MarshaledResponseBody.File fileBody) {
			response = response.copy().body(new MarshaledResponseBody.SecureFile(
					secureRoot, fileBody.getPath(),
					fileBody.getOffset(), fileBody.getCount(), attributes)).finish();
		}

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
	private AccessResolver getAccessResolver() {
		return this.accessResolver;
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
			relative = getResolutionRoot().getFileSystem().getPath(relativePath);
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

		Path canonicalCandidate = canonicalNoFollowPath(candidate).orElse(null);
		if (canonicalCandidate == null || hasSymlinkComponent(canonicalCandidate))
			return Optional.empty();
		candidate = canonicalCandidate;

		BasicFileAttributes attributes;
		try {
			attributes = Files.readAttributes(candidate, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
		} catch (IOException failure) {
			return unresolvedFile(failure);
		}
		if (attributes.isDirectory()) {
			for (String indexFileName : getIndexFileNames()) {
				Path indexCandidate = canonicalNoFollowPath(candidate.resolve(indexFileName).normalize()).orElse(null);

				if (indexCandidate == null || !indexCandidate.startsWith(getResolutionRoot()) || hasSymlinkComponent(indexCandidate))
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
	private Optional<Path> canonicalNoFollowPath(@NonNull Path candidate) {
		if (!candidate.startsWith(this.noFollowRoot) || Files.isSymbolicLink(this.noFollowRoot)
				|| hasSymlinkComponent(candidate))
			return Optional.empty();
		try {
			Path realRoot = this.noFollowRoot.toRealPath();
			Path realCandidate = candidate.toRealPath();
			if (!realCandidate.startsWith(realRoot))
				return Optional.empty();
			// Native real-path resolution avoids directory scans. Alias spelling depends
			// on the filesystem provider; preserve distinct hard-link names as policy paths.
			Path canonical = this.noFollowRoot.resolve(realRoot.relativize(realCandidate));
			return Files.isSymbolicLink(this.noFollowRoot) || hasSymlinkComponent(candidate)
					|| hasSymlinkComponent(canonical) ? Optional.empty() : Optional.of(canonical);
		} catch (IOException e) {
			return unresolvedFile(e, candidate);
		}
	}

	private <T> Optional<T> unresolvedFile(IOException failure, Path candidate) {
		// The Unix provider reports ENOTDIR as a generic FileSystemException.
		// Inspect existing ancestors rather than relying on localized reason text.
		if (failure instanceof FileSystemException) {
			for (Path parent = candidate.getParent(); parent != null && parent.startsWith(getResolutionRoot());
					parent = parent.getParent()) {
				try {
					if (!Files.readAttributes(parent, BasicFileAttributes.class).isDirectory())
						return Optional.empty();
				} catch (NoSuchFileException | NotDirectoryException missing) {
					return Optional.empty();
				} catch (IOException ignored) {
					// Preserve the original acquisition failure when this probe also fails.
				}
			}
		}
		return unresolvedFile(failure);
	}

	private static <T> Optional<T> unresolvedFile(IOException failure) {
		if (failure instanceof NoSuchFileException || failure instanceof NotDirectoryException
				|| failure instanceof AccessDeniedException || failure instanceof SecureStaticFileRoot.FileChangedException)
			return Optional.empty();
		throw new UncheckedIOException("Unable to acquire static file.", failure);
	}

	private static boolean sameFileIdentity(@NonNull BasicFileAttributes first, @NonNull BasicFileAttributes second) {
		return SecureStaticFileRoot.sameIdentity(first, second);
	}

	@NonNull
	private Optional<ResolvedFile> noFollowRegularFile(@NonNull Path candidate) {
		requireNonNull(candidate);

		try {
			BasicFileAttributes attributes = Files.readAttributes(candidate, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
			return attributes.isRegularFile() ? Optional.of(new ResolvedFile(candidate, attributes)) : Optional.empty();
		} catch (IOException e) {
			return unresolvedFile(e, candidate);
		}
	}

	@NonNull
	private Optional<ResolvedFile> followedResolvedFileForCandidate(@NonNull Path candidate) {
		requireNonNull(candidate);

		Path realCandidate;

		try {
			realCandidate = candidate.toRealPath();
		} catch (IOException e) {
			return unresolvedFile(e, candidate);
		}

		if (!realCandidate.startsWith(this.followRoot))
			return Optional.empty();

		BasicFileAttributes attributes;
		try {
			attributes = Files.readAttributes(realCandidate, BasicFileAttributes.class);
		} catch (IOException failure) {
			return unresolvedFile(failure);
		}
		if (attributes.isDirectory()) {
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

		try {
			Path realCandidate = candidate.toRealPath();

			if (!realCandidate.startsWith(this.followRoot))
				return Optional.empty();

			BasicFileAttributes attributes = Files.readAttributes(realCandidate, BasicFileAttributes.class);
			return attributes.isRegularFile() ? Optional.of(new ResolvedFile(realCandidate, attributes)) : Optional.empty();
		} catch (IOException e) {
			return unresolvedFile(e, candidate);
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
		return userResolver == null ? MimeTypeResolver.defaultInstance() : userResolver;
	}

	@NonNull
	private static Map<@NonNull String, @NonNull List<@NonNull String>> copyHeaders(
			@NonNull Map<@NonNull String, @NonNull List<@NonNull String>> headers) {
		requireNonNull(headers);

		Map<String, List<String>> copiedHeaders = new LinkedHashMap<>();

		for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
			String headerName = requireNonNull(entry.getKey());
			List<String> copiedHeaderValues = new ArrayList<>(requireNonNull(entry.getValue()));
			copiedHeaderValues.forEach(value -> requireNonNull(value, format("Header '%s' includes a null value.", headerName)));
			copiedHeaders.put(headerName, Collections.unmodifiableList(copiedHeaderValues));
		}

		return Collections.unmodifiableMap(copiedHeaders);
	}

	private record ResolvedFile(@NonNull Path path,
															@NonNull BasicFileAttributes attributes) {}

	/**
	 * Builder for {@link StaticFiles} instances.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
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
		private AccessResolver accessResolver;
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
		@Nullable
		private Boolean trustedImmutableRoot;

		private Builder(@NonNull Path root) {
			requireNonNull(root);
			this.root = root;
		}

		/**
		 * Declares that the root, its ancestors, links, and files are controlled exclusively by the
		 * application operator and remain unchanged while this helper or its responses are in use.
		 * <p>
		 * This declaration is required on every platform. It does not set filesystem permissions or
		 * freeze files. Do not enable it for uploads, tenant-writable directories, or any tree that
		 * untrusted code can mutate. Public NIO checks cannot exclude all path-replacement races.
		 * Passing {@code false} or {@code null} withdraws the declaration and makes {@link #build()}
		 * fail closed. Deploy a new helper for a new immutable asset tree.
		 *
		 * @param trustedImmutableRoot {@code true} to accept the immutable-root contract
		 * @return this builder
		 */
		@NonNull
		public Builder trustedImmutableRoot(@Nullable Boolean trustedImmutableRoot) {
			this.trustedImmutableRoot = trustedImmutableRoot;
			return this;
		}

		/**
		 * Sets the index file names to try when a request path resolves to a directory.
		 * <p>
		 * Names are tried in list order. Each name must be a single file name, not a path; blank
		 * names, {@code .}, {@code ..}, path separators, control characters, Windows drive paths, and
		 * UNC-style paths are rejected when the builder is built.
		 * <p>
		 * Each call replaces the complete previously configured list; it does not append. Passing
		 * {@code null} or an empty list clears the index file names.
		 *
		 * @param indexFileNames the replacement index file names, or {@code null} to clear them
		 * @return this builder
		 */
		@NonNull
		public Builder indexFileNames(@Nullable List<@NonNull String> indexFileNames) {
			this.indexFileNames = indexFileNames;
			return this;
		}

		/**
		 * Sets the resolver used to produce {@code Content-Type} values.
		 * <p>
		 * The configured resolver fully replaces the default resolver. Soklet's default resolver uses a
		 * curated deterministic set of common web-asset extensions, returns {@link Optional#empty()} for
		 * unknown extensions, and does not call {@link Files#probeContentType(Path)}. Applications that
		 * need OS-level MIME database behavior can configure a resolver that calls
		 * {@code Files.probeContentType(...)} directly.
		 *
		 * @param mimeTypeResolver the resolver to use, or {@code null} to restore the default resolver
		 * @return this builder
		 */
		@NonNull
		public Builder mimeTypeResolver(@Nullable MimeTypeResolver mimeTypeResolver) {
			this.mimeTypeResolver = mimeTypeResolver;
			return this;
		}

		/**
		 * Sets the resolver used to produce {@code ETag} values.
		 * <p>
		 * Passing {@code null} restores the default weak metadata-based resolver.
		 *
		 * @param entityTagResolver the resolver to use, or {@code null} to restore the default resolver
		 * @return this builder
		 */
		@NonNull
		public Builder entityTagResolver(@Nullable EntityTagResolver entityTagResolver) {
			this.entityTagResolver = entityTagResolver;
			return this;
		}

		/**
		 * Sets the resolver used to decide whether resolved files are served, denied, or hidden.
		 * <p>
		 * The access resolver runs after path safety, index-file resolution, symlink policy,
		 * readability, regular-file checks, and attribute reads, but before MIME, validator,
		 * cache-control, extra-header, and range-request resolvers. It receives the resolved file path,
		 * not the original request path.
		 *
		 * @param accessResolver the resolver to use, or {@code null} to restore the allow-all default
		 * @return this builder
		 */
		@NonNull
		public Builder accessResolver(@Nullable AccessResolver accessResolver) {
			this.accessResolver = accessResolver;
			return this;
		}

		/**
		 * Sets the resolver used to produce {@code Last-Modified} values.
		 * <p>
		 * Passing {@code null} restores the default resolver, which uses the file attributes read for
		 * the response.
		 *
		 * @param lastModifiedResolver the resolver to use, or {@code null} to restore the default resolver
		 * @return this builder
		 */
		@NonNull
		public Builder lastModifiedResolver(@Nullable LastModifiedResolver lastModifiedResolver) {
			this.lastModifiedResolver = lastModifiedResolver;
			return this;
		}

		/**
		 * Sets the resolver used to produce {@code Cache-Control} values.
		 * <p>
		 * Passing {@code null} restores the disabled resolver, which omits {@code Cache-Control}.
		 *
		 * @param cacheControlResolver the resolver to use, or {@code null} to omit {@code Cache-Control}
		 * @return this builder
		 */
		@NonNull
		public Builder cacheControlResolver(@Nullable CacheControlResolver cacheControlResolver) {
			this.cacheControlResolver = cacheControlResolver;
			return this;
		}

		/**
		 * Sets the resolver used to produce extra response headers.
		 * <p>
		 * Passing {@code null} restores the disabled resolver, which emits no extra headers.
		 * Managed file headers cannot be supplied here; see {@link HeadersResolver} for the
		 * controlled names and dedicated resolver alternatives.
		 *
		 * @param headersResolver the resolver to use, or {@code null} to omit extra headers
		 * @return this builder
		 */
		@NonNull
		public Builder headersResolver(@Nullable HeadersResolver headersResolver) {
			this.headersResolver = headersResolver;
			return this;
		}

		/**
		 * Sets the resolver used to decide whether byte range requests are honored.
		 * <p>
		 * Passing {@code null} restores the default resolver, which enables range requests.
		 *
		 * @param rangeRequestsResolver the resolver to use, or {@code null} to enable range requests
		 * @return this builder
		 */
		@NonNull
		public Builder rangeRequestsResolver(@Nullable RangeRequestsResolver rangeRequestsResolver) {
			this.rangeRequestsResolver = rangeRequestsResolver;
			return this;
		}

		/**
		 * Sets whether symbolic links are followed while resolving static-file paths.
		 * <p>
		 * Passing {@code null} restores the default of {@code false}. When disabled, any symbolic-link
		 * component under the static root causes the candidate path to be hidden. Enabling this option
		 * resolves links inside the configured root. Links that resolve outside the root are hidden.
		 * The required trusted immutable-root contract applies to links and their targets too;
		 * observed replacement links are rejected during delivery.
		 *
		 * @param followSymlinks {@code true} to follow symlinks, {@code false} to reject them, or
		 * {@code null} for the default
		 * @return this builder
		 */
		@NonNull
		public Builder followSymlinks(@Nullable Boolean followSymlinks) {
			this.followSymlinks = followSymlinks;
			return this;
		}

		/**
		 * Builds the static-file helper.
		 * <p>
		 * The configured root is validated as a directory when this method is called.
		 * With {@code followSymlinks} enabled it is resolved to a real path; otherwise
		 * a symbolic-link root is rejected. The resolved root identity is captured without retaining
		 * an open directory handle. Both modes require {@link #trustedImmutableRoot(Boolean)} to be
		 * explicitly set to {@code true} and a provider with stable file identities. Rebuild the helper
		 * when intentionally switching deployment roots, after in-flight responses have completed.
		 * Pass the root's {@link Path#toRealPath} value explicitly to use its
		 * resolved directory without enabling symlink traversal.
		 *
		 * @return the configured static-file helper
		 */
		@NonNull
		public StaticFiles build() {
			return new StaticFiles(this);
		}
	}

	/**
	 * Resolves {@code ETag} values for static-file responses.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@FunctionalInterface
	@ThreadSafe
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

		/**
		 * Returns a resolver that omits {@code ETag}.
		 *
		 * @return the disabled entity-tag resolver
		 */
		@NonNull
		static EntityTagResolver disabledInstance() {
			return DisabledEntityTagResolver.defaultInstance();
		}

		/**
		 * Returns a strong content-hash ETag resolver.
		 * <p>
		 * This resolver streams the served file through SHA-256 on the request-handling thread and
		 * emits strong ETags with values of the form {@code sha256-<lowercase-hex>}. When used by
		 * {@link StaticFiles}, it hashes a channel acquired under the helper's root and file checks. It does not
		 * cache digests and it fully reads the file for {@link HttpMethod#HEAD} requests as well as
		 * {@link HttpMethod#GET} requests. Applications serving large files or HEAD-heavy traffic should
		 * prefer a manifest-backed resolver.
		 *
		 * @return a strong content-hash entity-tag resolver
		 */
		@NonNull
		static EntityTagResolver fromContentHash() {
			return ContentHashEntityTagResolver.defaultInstance();
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
		Optional<@NonNull EntityTag> entityTagFor(@NonNull Path path,
																		 @NonNull BasicFileAttributes attributes);
	}

	/**
	 * Access outcome for a resolved static file.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	public enum Access {
		/**
		 * Continue normal static-file response generation.
		 */
		ALLOW,
		/**
		 * Return a bodyless {@code 403 Forbidden} response.
		 */
		DENY,
		/**
		 * Return {@link Optional#empty()} from {@link StaticFiles#marshaledResponseFor(String, Request)}.
		 */
		HIDE
	}

	/**
	 * Resolves whether a resolved static file should be served, denied, or hidden.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@FunctionalInterface
	@ThreadSafe
	public interface AccessResolver {
		/**
		 * Returns a resolver that allows all resolved static files.
		 *
		 * @return the allow-all access resolver
		 */
		@NonNull
		static AccessResolver allowAllInstance() {
			return AllowAllAccessResolver.defaultInstance();
		}

		/**
		 * Resolves static-file access for the file being served.
		 * <p>
		 * Implementations must be thread-safe; {@link StaticFiles} invokes resolvers concurrently from
		 * request-handling threads. This resolver is intentionally path- and attribute-based. For
		 * request-aware access decisions, gate {@link StaticFiles#marshaledResponseFor(String, Request)}
		 * from the resource method or another higher application layer.
		 *
		 * @param path the resolved file path being served
		 * @param attributes the file attributes read for this response
		 * @return the access outcome
		 */
		@NonNull
		Access accessFor(@NonNull Path path,
										 @NonNull BasicFileAttributes attributes);
	}

	/**
	 * Resolves {@code Last-Modified} values for static-file responses.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@FunctionalInterface
	@ThreadSafe
	public interface LastModifiedResolver {
		/**
		 * Returns a resolver that emits the file's last-modified timestamp from its attributes.
		 *
		 * @return the attribute-based last-modified resolver
		 */
		@NonNull
		static LastModifiedResolver fromAttributes() {
			return DefaultLastModifiedResolver.defaultInstance();
		}

		/**
		 * Returns a resolver that omits {@code Last-Modified}.
		 *
		 * @return the disabled last-modified resolver
		 */
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
		Optional<@NonNull Instant> lastModifiedFor(@NonNull Path path,
																			@NonNull BasicFileAttributes attributes);
	}

	/**
	 * Resolves {@code Cache-Control} values for static-file responses.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@FunctionalInterface
	@ThreadSafe
	public interface CacheControlResolver {
		/**
		 * Returns a resolver that always emits the supplied {@code Cache-Control} value.
		 *
		 * @param cacheControl the cache-control value to emit
		 * @return the constant cache-control resolver
		 */
		@NonNull
		static CacheControlResolver fromValue(@NonNull String cacheControl) {
			requireNonNull(cacheControl);
			String normalizedCacheControl = Utilities.trimAggressivelyToNull(cacheControl);

			if (normalizedCacheControl == null)
				throw new IllegalArgumentException("Cache-Control value must not be blank.");

			return (path, attributes) -> Optional.of(normalizedCacheControl);
		}

		/**
		 * Returns a resolver that omits {@code Cache-Control}.
		 *
		 * @return the disabled cache-control resolver
		 */
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
		Optional<@NonNull String> cacheControlFor(@NonNull Path path,
																		 @NonNull BasicFileAttributes attributes);
	}

	/**
	 * Resolves extra response headers for static-file responses.
	 * <p>
	 * Header names are checked case-insensitively. {@code Content-Length}, {@code Content-Range},
	 * {@code Accept-Ranges}, {@code Content-Type}, {@code Content-Encoding}, {@code Cache-Control},
	 * {@code ETag}, {@code Last-Modified}, and {@code Transfer-Encoding} are managed by file responses
	 * and must not be returned here. Use the builder's {@link Builder#mimeTypeResolver(MimeTypeResolver)},
	 * {@link Builder#cacheControlResolver(CacheControlResolver)}, {@link Builder#entityTagResolver(EntityTagResolver)},
	 * {@link Builder#lastModifiedResolver(LastModifiedResolver)}, or
	 * {@link Builder#rangeRequestsResolver(RangeRequestsResolver)} where applicable; framing is computed by the transport.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@FunctionalInterface
	@ThreadSafe
	public interface HeadersResolver {
		/**
		 * Returns a resolver that always emits the supplied headers.
		 * <p>
		 * The header map and nested value sets are defensively copied.
		 * Managed headers listed on {@link HeadersResolver} are rejected immediately.
		 *
		 * @param headers the headers to emit
		 * @return the constant headers resolver
		 * @throws IllegalArgumentException if a header is controlled by file responses
		 */
		@NonNull
		static HeadersResolver fromHeaders(@NonNull Map<@NonNull String, @NonNull List<@NonNull String>> headers) {
			requireNonNull(headers);
			Map<String, List<String>> copiedHeaders = copyHeaders(headers);
			FileResponse.rejectControlledHeaderConflicts(copiedHeaders, "StaticFiles.HeadersResolver");
			return (path, attributes) -> copiedHeaders;
		}

		/**
		 * Returns a resolver that emits no extra headers.
		 *
		 * @return the disabled headers resolver
		 */
		@NonNull
		static HeadersResolver disabledInstance() {
			return DisabledHeadersResolver.defaultInstance();
		}

		/**
		 * Resolves extra response headers for the file being served.
		 * Managed headers listed on {@link HeadersResolver} are rejected when the response is produced.
		 * <p>
		 * Implementations must be thread-safe; {@link StaticFiles} invokes resolvers concurrently from
		 * request-handling threads.
		 *
		 * @param path the resolved file path being served
		 * @param attributes the file attributes read for this response
		 * @return extra headers to emit
		 */
		@NonNull
		Map<@NonNull String, @NonNull List<@NonNull String>> headersFor(@NonNull Path path,
																																	 @NonNull BasicFileAttributes attributes);
	}

	/**
	 * Resolves whether byte range requests are honored for static-file responses.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@FunctionalInterface
	@ThreadSafe
	public interface RangeRequestsResolver {
		/**
		 * Returns a resolver that enables byte range requests.
		 *
		 * @return the range-request-enabled resolver
		 */
		@NonNull
		static RangeRequestsResolver enabledInstance() {
			return EnabledRangeRequestsResolver.defaultInstance();
		}

		/**
		 * Returns a resolver that disables byte range requests.
		 *
		 * @return the range-request-disabled resolver
		 */
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
	private static final class ContentHashEntityTagResolver implements EntityTagResolver {
		@NonNull
		private static final ContentHashEntityTagResolver INSTANCE;

		static {
			INSTANCE = new ContentHashEntityTagResolver();
		}

		@NonNull
		static ContentHashEntityTagResolver defaultInstance() {
			return INSTANCE;
		}

		@Override
		@NonNull
		public Optional<EntityTag> entityTagFor(@NonNull Path path,
																						@NonNull BasicFileAttributes attributes) {
			requireNonNull(path);
			requireNonNull(attributes);
			try (InputStream inputStream = Files.newInputStream(path)) {
				return Optional.of(hash(inputStream));
			} catch (IOException e) {
				throw new UncheckedIOException(format("Unable to hash static file '%s'.", path), e);
			}
		}

		@NonNull
		static EntityTag entityTagFor(@NonNull SeekableByteChannel channel) throws IOException {
			return hash(Channels.newInputStream(channel));
		}

		@NonNull
		private static EntityTag hash(@NonNull InputStream inputStream) throws IOException {
			MessageDigest messageDigest;

			try {
				messageDigest = MessageDigest.getInstance("SHA-256");
			} catch (NoSuchAlgorithmException e) {
				throw new IllegalStateException("SHA-256 message digest is not available.", e);
			}

			byte[] buffer = new byte[CONTENT_HASH_BUFFER_SIZE];

			for (int bytesRead = inputStream.read(buffer); bytesRead >= 0; bytesRead = inputStream.read(buffer))
				if (bytesRead > 0)
					messageDigest.update(buffer, 0, bytesRead);

			return EntityTag.fromStrongValue(format("sha256-%s", HEX_FORMAT.formatHex(messageDigest.digest())));
		}
	}

	@ThreadSafe
	private static final class AllowAllAccessResolver implements AccessResolver {
		@NonNull
		private static final AllowAllAccessResolver INSTANCE;

		static {
			INSTANCE = new AllowAllAccessResolver();
		}

		@NonNull
		static AllowAllAccessResolver defaultInstance() {
			return INSTANCE;
		}

		@Override
		@NonNull
		public Access accessFor(@NonNull Path path,
														@NonNull BasicFileAttributes attributes) {
			requireNonNull(path);
			requireNonNull(attributes);
			return Access.ALLOW;
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
			return Optional.of(EntityTag.fromWeakValue("mtime-" + epochSecond + "-size-" + size));
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
		public Map<@NonNull String, @NonNull List<@NonNull String>> headersFor(@NonNull Path path,
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

}
