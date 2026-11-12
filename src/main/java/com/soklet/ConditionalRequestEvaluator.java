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

import com.soklet.ConditionalRequestResult.NotModified;
import com.soklet.ConditionalRequestResult.Precondition;
import com.soklet.ConditionalRequestResult.PreconditionFailed;
import com.soklet.ConditionalRequestResult.PreconditionFailed.Reason;
import com.soklet.ConditionalRequestResult.Proceed;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.ThreadSafe;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Package-private conditional request evaluator shared by file and dynamic responses.
 * <p>
 * Evaluation follows RFC 9110 precedence and Soklet's fail-closed policy for malformed write validators.
 * Entity-tag parsing first performs bounded quote-aware splitting, then validates the resulting tags.
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
	static ConditionalRequestResult evaluate(@NonNull RequestContext requestContext,
											 @Nullable EntityTag entityTag,
											 @Nullable Instant lastModified,
											 boolean representationExists) {
		requireNonNull(requestContext);
		// RFC 9110 section 13.2.1: methods that neither select nor modify a representation ignore conditional
		// fields. If HttpMethod ever gains CONNECT or TRACE, they must be ignored here too.
		if (requestContext.httpMethod() == HttpMethod.OPTIONS)
			return new Proceed(Set.of());

		EnumSet<Precondition> satisfied = EnumSet.noneOf(Precondition.class);
		Instant truncatedLastModified = !representationExists || lastModified == null ? null : truncateToSeconds(lastModified);
		boolean ifMatchPresent = headerValuePresent(requestContext.ifMatchHeaderValue());

		if (ifMatchPresent) {
			EntityTagCondition ifMatch = entityTagConditionFor(requireNonNull(requestContext.ifMatchHeaderValue()));
			Reason failureReason = ifMatch.failureReason();
			if (failureReason != null)
				return new PreconditionFailed(Precondition.IF_MATCH, failureReason);
			if (!ifMatch.matchesStrong(entityTag, representationExists))
				return new PreconditionFailed(Precondition.IF_MATCH, Reason.CONDITION_FALSE);
			satisfied.add(Precondition.IF_MATCH);
		} else {
			Instant ifUnmodifiedSince = HttpDate.fromHeaderValue(requestContext.ifUnmodifiedSinceHeaderValue()).orElse(null);
			if (ifUnmodifiedSince != null && truncatedLastModified != null) {
				if (truncatedLastModified.isAfter(truncateToSeconds(ifUnmodifiedSince)))
					return new PreconditionFailed(Precondition.IF_UNMODIFIED_SINCE, Reason.CONDITION_FALSE);
				satisfied.add(Precondition.IF_UNMODIFIED_SINCE);
			}
		}

		boolean ifNoneMatchPresent = headerValuePresent(requestContext.ifNoneMatchHeaderValue());
		if (ifNoneMatchPresent) {
			EntityTagCondition ifNoneMatch = entityTagConditionFor(requireNonNull(requestContext.ifNoneMatchHeaderValue()));
			Reason failureReason = ifNoneMatch.failureReason();
			if (failureReason != null) {
				if (!isGetOrHead(requestContext.httpMethod()))
					return new PreconditionFailed(Precondition.IF_NONE_MATCH, failureReason);
				// Even ignored malformed/over-limit If-None-Match suppresses date fallback.
			} else if (ifNoneMatch.matchesWeak(entityTag, representationExists)) {
				return isGetOrHead(requestContext.httpMethod())
						? new NotModified(Precondition.IF_NONE_MATCH)
						: new PreconditionFailed(Precondition.IF_NONE_MATCH, Reason.CONDITION_FALSE);
			} else {
				satisfied.add(Precondition.IF_NONE_MATCH);
			}
		} else if (isGetOrHead(requestContext.httpMethod())) {
			Instant ifModifiedSince = HttpDate.fromHeaderValue(requestContext.ifModifiedSinceHeaderValue()).orElse(null);
			if (ifModifiedSince != null && truncatedLastModified != null) {
				if (!truncatedLastModified.isAfter(truncateToSeconds(ifModifiedSince)))
					return new NotModified(Precondition.IF_MODIFIED_SINCE);
				satisfied.add(Precondition.IF_MODIFIED_SINCE);
			}
		}

		return new Proceed(satisfied);
	}

	private static boolean isGetOrHead(@NonNull HttpMethod httpMethod) {
		requireNonNull(httpMethod);
		return httpMethod == HttpMethod.GET || httpMethod == HttpMethod.HEAD;
	}

	private static boolean headerValuePresent(@Nullable String headerValue) {
		// Even an empty supplied entity-tag list suppresses its date-based counterpart.
		return headerValue != null;
	}

	@NonNull
	static Optional<@NonNull String> headerValueFor(@NonNull Request request, @NonNull String headerName) {
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
	private static EntityTagCondition entityTagConditionFor(@NonNull String headerValue) {
		requireNonNull(headerValue);
		String trimmed = Utilities.trimHeaderWhitespace(headerValue);
		if (trimmed.isEmpty())
			return EntityTagCondition.fromEntityTags(List.of());
		if ("*".equals(trimmed))
			return EntityTagCondition.wildcardCondition();

		// Split and count before validating tags. A reached budget failure wins immediately; an
		// unclosed quote wins only if the bounded split reaches its end. Quoted commas never count.
		List<String> parts = new ArrayList<>();
		StringBuilder current = new StringBuilder(trimmed.length());
		boolean inQuotes = false;
		int elementCount = 1;
		for (int i = 0; i < trimmed.length(); i++) {
			char c = trimmed.charAt(i);
			if (c == '"')
				inQuotes = !inQuotes;
			if (c == ',' && !inQuotes) {
				if (++elementCount > MAX_ENTITY_TAG_CONDITION_COUNT)
					return EntityTagCondition.fromFailure(Reason.ENTITY_TAG_LIST_LIMIT_EXCEEDED);
				String element = Utilities.trimHeaderWhitespace(current.toString());
				if (!element.isEmpty())
					parts.add(element);
				current.setLength(0);
				continue;
			}
			current.append(c);
		}
		if (inQuotes)
			return EntityTagCondition.fromFailure(Reason.MALFORMED);
		String element = Utilities.trimHeaderWhitespace(current.toString());
		if (!element.isEmpty())
			parts.add(element);

		List<EntityTag> entityTags = new ArrayList<>();
		for (String part : parts) {
			EntityTag entityTag = EntityTag.fromHeaderValue(part).orElse(null);
			if (entityTag == null)
				return EntityTagCondition.fromFailure(Reason.MALFORMED);
			entityTags.add(entityTag);
		}
		return EntityTagCondition.fromEntityTags(entityTags);
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
			return new RequestContext(request.getHttpMethod(),
					headerValueFor(request, "If-Match").orElse(null),
					headerValueFor(request, "If-Unmodified-Since").orElse(null),
					headerValueFor(request, "If-None-Match").orElse(null),
					headerValueFor(request, "If-Modified-Since").orElse(null));
		}
	}

	private record EntityTagCondition(boolean wildcard,
									  @NonNull List<@NonNull EntityTag> entityTags,
									  @Nullable Reason failureReason) {
		EntityTagCondition {
			entityTags = List.copyOf(requireNonNull(entityTags));
			if (((wildcard || failureReason != null) && !entityTags.isEmpty())
					|| (wildcard && failureReason != null))
				throw new IllegalArgumentException("Invalid entity-tag parser result.");
		}

		@NonNull
		static EntityTagCondition wildcardCondition() {
			return new EntityTagCondition(true, List.of(), null);
		}

		@NonNull
		static EntityTagCondition fromEntityTags(@NonNull List<@NonNull EntityTag> entityTags) {
			return new EntityTagCondition(false, entityTags, null);
		}

		@NonNull
		static EntityTagCondition fromFailure(@NonNull Reason failureReason) {
			return new EntityTagCondition(false, List.of(), requireNonNull(failureReason));
		}

		boolean matchesStrong(@Nullable EntityTag entityTag, boolean representationExists) {
			if (wildcard)
				return representationExists;
			if (!representationExists || entityTag == null)
				return false;
			return entityTags.stream().anyMatch(candidate -> candidate.stronglyMatches(entityTag));
		}

		boolean matchesWeak(@Nullable EntityTag entityTag, boolean representationExists) {
			if (wildcard)
				return representationExists;
			if (!representationExists || entityTag == null)
				return false;
			return entityTags.stream().anyMatch(candidate -> candidate.weaklyMatches(entityTag));
		}
	}
}
