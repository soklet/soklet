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

import javax.annotation.concurrent.ThreadSafe;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Package-private RFC 9110 conditional request evaluator shared by file and dynamic responses.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class ConditionalRequestEvaluator {
	private static final int MAX_ENTITY_TAG_CONDITION_COUNT = 256;

	private ConditionalRequestEvaluator() {
		// Non-instantiable
	}

	@NonNull
	static PreconditionOutcome evaluate(@NonNull RequestContext requestContext,
																			@Nullable EntityTag entityTag,
																			@Nullable Instant lastModified,
																			boolean representationExists) {
		requireNonNull(requestContext);
		Instant truncatedLastModified = lastModified == null ? null : truncateToSeconds(lastModified);
		EntityTagCondition ifMatch = entityTagConditionFor(requestContext.ifMatchHeaderValue()).orElse(null);

		if (ifMatch != null) {
			if (!ifMatch.matchesStrong(entityTag, representationExists))
				return PreconditionOutcome.PRECONDITION_FAILED;
		} else {
			Instant ifUnmodifiedSince = HttpDate.fromHeaderValue(requestContext.ifUnmodifiedSinceHeaderValue()).orElse(null);

			if (ifUnmodifiedSince != null && truncatedLastModified != null && truncatedLastModified.isAfter(truncateToSeconds(ifUnmodifiedSince)))
				return PreconditionOutcome.PRECONDITION_FAILED;
		}

		EntityTagCondition ifNoneMatch = entityTagConditionFor(requestContext.ifNoneMatchHeaderValue()).orElse(null);

		if (ifNoneMatch != null) {
			if (ifNoneMatch.matchesWeak(entityTag, representationExists))
				return requestContext.httpMethod() == HttpMethod.GET || requestContext.httpMethod() == HttpMethod.HEAD
						? PreconditionOutcome.NOT_MODIFIED
						: PreconditionOutcome.PRECONDITION_FAILED;
		} else if (requestContext.httpMethod() == HttpMethod.GET || requestContext.httpMethod() == HttpMethod.HEAD) {
			Instant ifModifiedSince = HttpDate.fromHeaderValue(requestContext.ifModifiedSinceHeaderValue()).orElse(null);

			if (ifModifiedSince != null && truncatedLastModified != null && !truncatedLastModified.isAfter(truncateToSeconds(ifModifiedSince)))
				return PreconditionOutcome.NOT_MODIFIED;
		}

		return PreconditionOutcome.CONTINUE;
	}

	@NonNull
	static Optional<String> headerValueFor(@NonNull Request request,
																				 @NonNull String headerName) {
		requireNonNull(request);
		requireNonNull(headerName);

		return request.getHeaderValues(headerName)
				.map(values -> values.size() == 1 ? values.iterator().next() : String.join(",", values));
	}

	@NonNull
	static Instant truncateToSeconds(@NonNull Instant instant) {
		requireNonNull(instant);
		return Instant.ofEpochSecond(instant.getEpochSecond());
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

	enum PreconditionOutcome {
		CONTINUE,
		NOT_MODIFIED,
		PRECONDITION_FAILED
	}

	record RequestContext(@NonNull HttpMethod httpMethod,
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
					headerValueFor(request, "If-Match").orElse(null),
					headerValueFor(request, "If-Unmodified-Since").orElse(null),
					headerValueFor(request, "If-None-Match").orElse(null),
					headerValueFor(request, "If-Modified-Since").orElse(null)
			);
		}
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
													boolean representationExists) {
			if (wildcard())
				return representationExists;

			if (entityTag == null)
				return false;

			return entityTags().stream().anyMatch(candidate -> candidate.stronglyMatches(entityTag));
		}

		@NonNull
		Boolean matchesWeak(@Nullable EntityTag entityTag,
											 boolean representationExists) {
			if (wildcard())
				return representationExists;

			if (entityTag == null)
				return false;

			return entityTags().stream().anyMatch(candidate -> candidate.weaklyMatches(entityTag));
		}
	}
}
