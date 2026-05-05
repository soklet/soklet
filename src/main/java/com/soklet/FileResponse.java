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

import com.soklet.ByteRangeSelection.ByteRangeSelectionType;
import com.soklet.internal.spring.LinkedCaseInsensitiveMap;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * Low-level helper for serving one already-resolved file path.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class FileResponse {
	@NonNull
	private static final Set<@NonNull String> CONTROLLED_HEADER_NAMES;
	@NonNull
	private static final Integer MAX_ENTITY_TAG_CONDITION_COUNT;

	static {
		CONTROLLED_HEADER_NAMES = Set.of(
				"content-length",
				"content-range",
				"accept-ranges",
				"content-type",
				"content-encoding",
				"cache-control",
				"etag",
				"last-modified",
				"transfer-encoding"
		);
		MAX_ENTITY_TAG_CONDITION_COUNT = 256;
	}

	@NonNull
	private final Path path;
	@Nullable
	private final String contentType;
	@Nullable
	private final EntityTag entityTag;
	@Nullable
	private final Instant lastModified;
	@Nullable
	private final String cacheControl;
	@NonNull
	private final Map<@NonNull String, @NonNull Set<@NonNull String>> headers;
	@NonNull
	private final Boolean rangeRequests;
	@Nullable
	private final BasicFileAttributes attributes;

	@NonNull
	static Builder withPath(@NonNull Path path) {
		requireNonNull(path);
		return new Builder(path);
	}

	private FileResponse(@NonNull Builder builder) {
		requireNonNull(builder);

		this.path = builder.path;
		this.contentType = builder.contentType;
		this.entityTag = builder.entityTag;
		this.lastModified = builder.lastModified;
		this.cacheControl = builder.cacheControl;
		this.headers = copyHeaders(builder.headers == null ? Map.of() : builder.headers);
		this.rangeRequests = builder.rangeRequests == null ? true : builder.rangeRequests;
		this.attributes = builder.attributes;

		rejectControlledHeaderConflicts(this.headers);
	}

	@NonNull
	MarshaledResponse marshaledResponseFor(@NonNull RequestContext requestContext) {
		requireNonNull(requestContext);

		BasicFileAttributes attributes = getAttributes().orElseGet(this::readAttributes);
		Long fileLength = attributes.size();
		PreconditionResult preconditionResult = evaluatePreconditions(requestContext);

		if (preconditionResult == PreconditionResult.NOT_MODIFIED)
			return bodylessResponse(304);

		if (preconditionResult == PreconditionResult.PRECONDITION_FAILED)
			return bodylessResponse(412);

		if (requestContext.httpMethod() == HttpMethod.GET && getRangeRequests()) {
			String rangeHeaderValue = requestContext.rangeHeaderValue();
			ByteRangeSelection rangeSelection = shouldApplyRange(requestContext)
					? ByteRangeSelection.fromHeaderValue(rangeHeaderValue, fileLength)
					: ByteRangeSelection.fromHeaderValue(null, fileLength);

			if (rangeSelection.getType() == ByteRangeSelectionType.UNSATISFIABLE)
				return bodylessResponse(416, Map.of("Content-Range", Set.of(format("bytes */%d", fileLength))));

			if (rangeSelection.getType() == ByteRangeSelectionType.SATISFIABLE) {
				ByteRange range = rangeSelection.getRange().orElseThrow();
				return response(206, Map.of(
								"Accept-Ranges", Set.of("bytes"),
								"Content-Range", Set.of(range.toContentRangeHeaderValue(fileLength))
						),
						new MarshaledResponseBody.File(getPath(), range.getStart(), range.getLength()));
			}
		}

		Map<String, Set<String>> rangeHeaders = getRangeRequests()
				? Map.of("Accept-Ranges", Set.of("bytes"))
				: Map.of();

		return response(200, rangeHeaders, new MarshaledResponseBody.File(getPath(), 0L, fileLength));
	}

	@NonNull
	private Path getPath() {
		return this.path;
	}

	@Nullable
	private String getContentType() {
		return this.contentType;
	}

	@NonNull
	private Optional<EntityTag> getEntityTag() {
		return Optional.ofNullable(this.entityTag);
	}

	@NonNull
	private Optional<Instant> getLastModified() {
		return Optional.ofNullable(this.lastModified).map(FileResponse::truncateToSeconds);
	}

	@NonNull
	private Optional<String> getCacheControl() {
		return Optional.ofNullable(this.cacheControl);
	}

	@NonNull
	private Map<@NonNull String, @NonNull Set<@NonNull String>> getHeaders() {
		return this.headers;
	}

	@NonNull
	private Boolean getRangeRequests() {
		return this.rangeRequests;
	}

	@NonNull
	private Optional<BasicFileAttributes> getAttributes() {
		return Optional.ofNullable(this.attributes);
	}

	@NonNull
	private BasicFileAttributes readAttributes() {
		Path path = getPath();

		if (!Files.isRegularFile(path))
			throw new IllegalArgumentException(format("Path '%s' is not a regular file.", path));

		if (!Files.isReadable(path))
			throw new IllegalArgumentException(format("Path '%s' is not readable.", path));

		try {
			return Files.readAttributes(path, BasicFileAttributes.class);
		} catch (IOException e) {
			throw new UncheckedIOException(format("Unable to read attributes for file '%s'.", path), e);
		}
	}

	@NonNull
	private PreconditionResult evaluatePreconditions(@NonNull RequestContext requestContext) {
		requireNonNull(requestContext);
		EntityTag entityTag = getEntityTag().orElse(null);
		Instant lastModified = getLastModified().orElse(null);
		EntityTagCondition ifMatch = entityTagConditionFor(requestContext.ifMatchHeaderValue()).orElse(null);

		if (ifMatch != null) {
			if (!ifMatch.matchesStrong(entityTag, true))
				return PreconditionResult.PRECONDITION_FAILED;
		} else {
			Instant ifUnmodifiedSince = HttpDate.fromHeaderValue(requestContext.ifUnmodifiedSinceHeaderValue()).orElse(null);

			if (ifUnmodifiedSince != null && lastModified != null && lastModified.isAfter(truncateToSeconds(ifUnmodifiedSince)))
				return PreconditionResult.PRECONDITION_FAILED;
		}

		EntityTagCondition ifNoneMatch = entityTagConditionFor(requestContext.ifNoneMatchHeaderValue()).orElse(null);

		if (ifNoneMatch != null) {
			if (ifNoneMatch.matchesWeak(entityTag, true))
				return requestContext.httpMethod() == HttpMethod.GET || requestContext.httpMethod() == HttpMethod.HEAD
						? PreconditionResult.NOT_MODIFIED
						: PreconditionResult.PRECONDITION_FAILED;
		} else if (requestContext.httpMethod() == HttpMethod.GET || requestContext.httpMethod() == HttpMethod.HEAD) {
			Instant ifModifiedSince = HttpDate.fromHeaderValue(requestContext.ifModifiedSinceHeaderValue()).orElse(null);

			if (ifModifiedSince != null && lastModified != null && !lastModified.isAfter(truncateToSeconds(ifModifiedSince)))
				return PreconditionResult.NOT_MODIFIED;
		}

		return PreconditionResult.CONTINUE;
	}

	@NonNull
	private Boolean shouldApplyRange(@NonNull RequestContext requestContext) {
		requireNonNull(requestContext);
		String rangeHeaderValue = Utilities.trimAggressivelyToNull(requestContext.rangeHeaderValue());

		if (rangeHeaderValue == null)
			return false;

		String ifRange = Utilities.trimAggressivelyToNull(requestContext.ifRangeHeaderValue());

		if (ifRange == null)
			return true;

		return ifRangeMatches(ifRange);
	}

	@NonNull
	private Boolean ifRangeMatches(@NonNull String ifRange) {
		requireNonNull(ifRange);

		EntityTag ifRangeEntityTag = EntityTag.fromHeaderValue(ifRange).orElse(null);

		if (ifRangeEntityTag != null) {
			EntityTag currentEntityTag = getEntityTag().orElse(null);
			return currentEntityTag != null
					&& !ifRangeEntityTag.isWeak()
					&& !currentEntityTag.isWeak()
					&& ifRangeEntityTag.stronglyMatches(currentEntityTag);
		}

		Instant ifRangeDate = HttpDate.fromHeaderValue(ifRange).orElse(null);
		Instant lastModified = getLastModified().orElse(null);

		return ifRangeDate != null && lastModified != null && truncateToSeconds(ifRangeDate).equals(lastModified);
	}

	@NonNull
	private MarshaledResponse bodylessResponse(@NonNull Integer statusCode) {
		requireNonNull(statusCode);
		return bodylessResponse(statusCode, Map.of());
	}

	@NonNull
	private MarshaledResponse bodylessResponse(@NonNull Integer statusCode,
																						 @NonNull Map<@NonNull String, @NonNull Set<@NonNull String>> protocolHeaders) {
		requireNonNull(statusCode);
		requireNonNull(protocolHeaders);
		return response(statusCode, protocolHeaders, null);
	}

	@NonNull
	private MarshaledResponse response(@NonNull Integer statusCode,
																		 @NonNull Map<@NonNull String, @NonNull Set<@NonNull String>> protocolHeaders,
																		 @Nullable MarshaledResponseBody body) {
		requireNonNull(statusCode);
		requireNonNull(protocolHeaders);

		Map<String, Set<String>> headers = headers(protocolHeaders, body != null);
		MarshaledResponse.Builder builder = MarshaledResponse.withStatusCode(statusCode)
				.headers(headers);

		if (body != null)
			builder.body(body);

		return builder.build();
	}

	@NonNull
	private Map<@NonNull String, @NonNull Set<@NonNull String>> headers(
			@NonNull Map<@NonNull String, @NonNull Set<@NonNull String>> protocolHeaders,
			@NonNull Boolean includeContentType) {
		requireNonNull(protocolHeaders);
		requireNonNull(includeContentType);

		Map<String, Set<String>> headers = new LinkedCaseInsensitiveMap<>();
		headers.putAll(getHeaders());

		if (includeContentType && getContentType() != null)
			headers.put("Content-Type", Set.of(getContentType()));

		getCacheControl().ifPresent(cacheControl -> headers.put("Cache-Control", Set.of(cacheControl)));
		getEntityTag().ifPresent(entityTag -> headers.put("ETag", Set.of(entityTag.toHeaderValue())));
		getLastModified().ifPresent(lastModified -> headers.put("Last-Modified", Set.of(HttpDate.toHeaderValue(lastModified))));
		headers.putAll(protocolHeaders);

		return headers;
	}

	@NonNull
	private static Optional<EntityTagCondition> entityTagConditionFor(@Nullable String headerValue) {
		String trimmed = Utilities.trimAggressivelyToNull(headerValue);

		if (trimmed == null)
			return Optional.empty();

		if ("*".equals(trimmed))
			return Optional.of(EntityTagCondition.wildcardCondition());

		List<EntityTag> entityTags = new ArrayList<>();
		List<String> parts = commaSeparatedValues(trimmed).orElse(null);

		if (parts == null || parts.isEmpty() || parts.size() > MAX_ENTITY_TAG_CONDITION_COUNT)
			return Optional.empty();

		for (String part : parts) {
			EntityTag entityTag = EntityTag.fromHeaderValue(part).orElse(null);

			if (entityTag == null)
				return Optional.empty();

			entityTags.add(entityTag);
		}

		return Optional.of(EntityTagCondition.fromEntityTags(entityTags));
	}

	@NonNull
	private static Optional<String> headerValueFor(@NonNull Request request,
																								 @NonNull String headerName) {
		requireNonNull(request);
		requireNonNull(headerName);

		return request.getHeaderValues(headerName)
				.map(values -> values.size() == 1 ? values.iterator().next() : String.join(",", values));
	}

	@NonNull
	private static Optional<List<@NonNull String>> commaSeparatedValues(@NonNull String value) {
		requireNonNull(value);

		List<String> values = new ArrayList<>();
		StringBuilder current = new StringBuilder(value.length());
		boolean inQuotes = false;

		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);

			if (c == '"')
				inQuotes = !inQuotes;

			if (c == ',' && !inQuotes) {
				String element = Utilities.trimAggressivelyToNull(current.toString());

				if (element == null)
					return Optional.empty();

				values.add(element);
				current.setLength(0);
				continue;
			}

			current.append(c);
		}

		if (inQuotes)
			return Optional.empty();

		String element = Utilities.trimAggressivelyToNull(current.toString());

		if (element == null)
			return Optional.empty();

		values.add(element);
		return Optional.of(values);
	}

	@NonNull
	private static Instant truncateToSeconds(@NonNull Instant instant) {
		requireNonNull(instant);
		return Instant.ofEpochSecond(instant.getEpochSecond());
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

	private static void rejectControlledHeaderConflicts(@NonNull Map<@NonNull String, @NonNull Set<@NonNull String>> headers) {
		requireNonNull(headers);

		for (String headerName : headers.keySet()) {
			String normalizedHeaderName = headerName.toLowerCase(Locale.US);

			if (CONTROLLED_HEADER_NAMES.contains(normalizedHeaderName))
				throw new IllegalArgumentException(format("Header '%s' is controlled by file responses; use the dedicated builder method when available.", headerName));
		}
	}

	private enum PreconditionResult {
		CONTINUE,
		NOT_MODIFIED,
		PRECONDITION_FAILED
	}

	private record EntityTagCondition(@NonNull Boolean wildcard,
																		@NonNull List<@NonNull EntityTag> entityTags) {
		@NonNull
		static EntityTagCondition wildcardCondition() {
			return new EntityTagCondition(true, List.of());
		}

		@NonNull
		static EntityTagCondition fromEntityTags(@NonNull List<@NonNull EntityTag> entityTags) {
			requireNonNull(entityTags);
			return new EntityTagCondition(false, List.copyOf(entityTags));
		}

		@NonNull
		Boolean matchesStrong(@Nullable EntityTag entityTag,
													@NonNull Boolean representationExists) {
			requireNonNull(representationExists);

			if (wildcard())
				return representationExists;

			if (entityTag == null)
				return false;

			return entityTags().stream().anyMatch(candidate -> candidate.stronglyMatches(entityTag));
		}

		@NonNull
		Boolean matchesWeak(@Nullable EntityTag entityTag,
											 @NonNull Boolean representationExists) {
			requireNonNull(representationExists);

			if (wildcard())
				return representationExists;

			if (entityTag == null)
				return false;

			return entityTags().stream().anyMatch(candidate -> candidate.weaklyMatches(entityTag));
		}
	}

	record RequestContext(@NonNull HttpMethod httpMethod,
												@Nullable String rangeHeaderValue,
												@Nullable String ifRangeHeaderValue,
												@Nullable String ifMatchHeaderValue,
												@Nullable String ifUnmodifiedSinceHeaderValue,
												@Nullable String ifNoneMatchHeaderValue,
												@Nullable String ifModifiedSinceHeaderValue) {
		RequestContext {
			requireNonNull(httpMethod);
		}

		@NonNull
		static RequestContext fromRequest(@NonNull Request request) {
			requireNonNull(request);
			return new RequestContext(
					request.getHttpMethod(),
					headerValueFor(request, "Range").orElse(null),
					headerValueFor(request, "If-Range").orElse(null),
					headerValueFor(request, "If-Match").orElse(null),
					headerValueFor(request, "If-Unmodified-Since").orElse(null),
					headerValueFor(request, "If-None-Match").orElse(null),
					headerValueFor(request, "If-Modified-Since").orElse(null)
			);
		}
	}

	@NotThreadSafe
	static final class Builder {
		@NonNull
		private final Path path;
		@Nullable
		private String contentType;
		@Nullable
		private EntityTag entityTag;
		@Nullable
		private Instant lastModified;
		@Nullable
		private String cacheControl;
		@Nullable
		private Map<@NonNull String, @NonNull Set<@NonNull String>> headers;
		@Nullable
		private Boolean rangeRequests;
		@Nullable
		private BasicFileAttributes attributes;

		private Builder(@NonNull Path path) {
			requireNonNull(path);
			this.path = path;
		}

		@NonNull
		Builder contentType(@Nullable String contentType) {
			this.contentType = Utilities.trimAggressivelyToNull(contentType);
			return this;
		}

		@NonNull
		Builder entityTag(@Nullable EntityTag entityTag) {
			this.entityTag = entityTag;
			return this;
		}

		@NonNull
		Builder lastModified(@Nullable Instant lastModified) {
			this.lastModified = lastModified;
			return this;
		}

		@NonNull
		Builder cacheControl(@Nullable String cacheControl) {
			this.cacheControl = Utilities.trimAggressivelyToNull(cacheControl);
			return this;
		}

		@NonNull
		Builder headers(@Nullable Map<@NonNull String, @NonNull Set<@NonNull String>> headers) {
			this.headers = headers;
			return this;
		}

		@NonNull
		Builder rangeRequests(@Nullable Boolean rangeRequests) {
			this.rangeRequests = rangeRequests;
			return this;
		}

		@NonNull
		Builder attributes(@Nullable BasicFileAttributes attributes) {
			this.attributes = attributes;
			return this;
		}

		@NonNull
		FileResponse build() {
			return new FileResponse(this);
		}
	}
}
