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

import com.soklet.internal.spring.LinkedCaseInsensitiveMap;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.ThreadSafe;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * Utility methods for evaluating HTTP conditional requests against a selected dynamic representation.
 * <p>
 * This helper supports cache validation and optimistic concurrency for application responses without forcing resource
 * methods into {@link MarshaledResponse}. Applications still own representation validators: choose the current
 * {@link EntityTag}, {@code Last-Modified} instant, cache headers, and normal success response.
 * <p>
 * When a request precondition requires an immediate response, {@link #responseFor(Request, EntityTag, Instant)}
 * returns a bodyless {@link Response} with status {@code 304 Not Modified} or {@code 412 Precondition Failed}.
 * Otherwise it returns {@link Optional#empty()} and the application should build its normal response.
 * <p>
 * Malformed entity-tag preconditions fail closed when they protect writes: malformed {@code If-Match} returns
 * {@code 412 Precondition Failed}, and malformed {@code If-None-Match} does the same for non-{@code GET}/{@code HEAD}
 * requests. Malformed {@code If-None-Match} on {@code GET} and {@code HEAD} is treated as a cache miss.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class ConditionalRequests {
	@NonNull
	private static final Set<@NonNull String> CONTROLLED_EXTRA_HEADER_NAMES;

	static {
		CONTROLLED_EXTRA_HEADER_NAMES = Set.of(
				"content-length",
				"content-type",
				"etag",
				"last-modified",
				"transfer-encoding"
		);
	}

	private ConditionalRequests() {
		// Non-instantiable
	}

	/**
	 * Evaluates conditional request headers against the supplied validators.
	 *
	 * @param request      the request whose conditional headers should be evaluated
	 * @param entityTag    the current representation's entity tag, or {@code null} if unavailable
	 * @param lastModified the current representation's last-modified instant, or {@code null} if unavailable
	 * @return a short-circuit response, or {@link Optional#empty()} when the application should continue normally
	 */
	@NonNull
	public static Optional<Response> responseFor(@NonNull Request request,
																						 @Nullable EntityTag entityTag,
																						 @Nullable Instant lastModified) {
		return responseFor(request, entityTag, lastModified, null);
	}

	/**
	 * Evaluates conditional request headers against the supplied validators.
	 * <p>
	 * {@code extraHeaders} are included on short-circuit {@code 304} and {@code 412} responses. They are intended for
	 * response metadata such as {@code Cache-Control} or {@code Vary}; validator and body-framing headers are rejected
	 * because they are controlled by this helper.
	 *
	 * @param request      the request whose conditional headers should be evaluated
	 * @param entityTag    the current representation's entity tag, or {@code null} if unavailable
	 * @param lastModified the current representation's last-modified instant, or {@code null} if unavailable
	 * @param extraHeaders endpoint-specific metadata headers to include on short-circuit responses
	 * @return a short-circuit response, or {@link Optional#empty()} when the application should continue normally
	 */
	@NonNull
	public static Optional<Response> responseFor(@NonNull Request request,
																						 @Nullable EntityTag entityTag,
																						 @Nullable Instant lastModified,
																						 @Nullable Map<@NonNull String, @NonNull Set<@NonNull String>> extraHeaders) {
		requireNonNull(request);
		Map<String, Set<String>> copiedExtraHeaders = copyExtraHeaders(extraHeaders);
		Instant truncatedLastModified = lastModified == null ? null : ConditionalRequestEvaluator.truncateToSeconds(lastModified);
		ConditionalRequestEvaluator.PreconditionOutcome preconditionOutcome = ConditionalRequestEvaluator.evaluate(
				ConditionalRequestEvaluator.RequestContext.fromRequest(request),
				entityTag,
				truncatedLastModified,
				true
		);

		return switch (preconditionOutcome) {
			case CONTINUE -> Optional.empty();
			case NOT_MODIFIED -> Optional.of(bodylessResponse(304, entityTag, truncatedLastModified, copiedExtraHeaders));
			case PRECONDITION_FAILED -> Optional.of(bodylessResponse(412, entityTag, truncatedLastModified, copiedExtraHeaders));
		};
	}

	/**
	 * Builds validator headers for the supplied representation validators.
	 *
	 * @param entityTag    the current representation's entity tag, or {@code null} if unavailable
	 * @param lastModified the current representation's last-modified instant, or {@code null} if unavailable
	 * @return immutable {@code ETag} and {@code Last-Modified} headers for the supplied validators
	 */
	@NonNull
	public static Map<@NonNull String, @NonNull Set<@NonNull String>> validatorHeaders(@Nullable EntityTag entityTag,
																																										 @Nullable Instant lastModified) {
		return validatorHeadersFor(entityTag, lastModified == null ? null : ConditionalRequestEvaluator.truncateToSeconds(lastModified));
	}

	/**
	 * Builds validator headers plus endpoint-specific metadata headers.
	 * <p>
	 * {@code extraHeaders} are intended for response metadata such as {@code Cache-Control} or {@code Vary}; validator
	 * and body-framing headers are rejected because they are controlled by this helper.
	 *
	 * @param entityTag    the current representation's entity tag, or {@code null} if unavailable
	 * @param lastModified the current representation's last-modified instant, or {@code null} if unavailable
	 * @param extraHeaders endpoint-specific metadata headers to include with the validators
	 * @return immutable combined headers
	 */
	@NonNull
	public static Map<@NonNull String, @NonNull Set<@NonNull String>> validatorHeaders(@Nullable EntityTag entityTag,
																																										 @Nullable Instant lastModified,
																																										 @Nullable Map<@NonNull String, @NonNull Set<@NonNull String>> extraHeaders) {
		return responseHeaders(
				entityTag,
				lastModified == null ? null : ConditionalRequestEvaluator.truncateToSeconds(lastModified),
				copyExtraHeaders(extraHeaders)
		);
	}

	@NonNull
	private static Response bodylessResponse(@NonNull Integer statusCode,
																					 @Nullable EntityTag entityTag,
																					 @Nullable Instant lastModified,
																					 @NonNull Map<@NonNull String, @NonNull Set<@NonNull String>> extraHeaders) {
		requireNonNull(statusCode);
		requireNonNull(extraHeaders);

		return Response.withStatusCode(statusCode)
				.headers(responseHeaders(entityTag, lastModified, extraHeaders))
				.build();
	}

	@NonNull
	private static Map<@NonNull String, @NonNull Set<@NonNull String>> responseHeaders(@Nullable EntityTag entityTag,
																																										 @Nullable Instant lastModified,
																																										 @NonNull Map<@NonNull String, @NonNull Set<@NonNull String>> extraHeaders) {
		requireNonNull(extraHeaders);
		Map<String, Set<String>> headers = new LinkedCaseInsensitiveMap<>();
		headers.putAll(validatorHeadersFor(entityTag, lastModified));
		headers.putAll(extraHeaders);
		return Collections.unmodifiableMap(headers);
	}

	@NonNull
	private static Map<@NonNull String, @NonNull Set<@NonNull String>> validatorHeadersFor(@Nullable EntityTag entityTag,
																																												@Nullable Instant truncatedLastModified) {
		Map<String, Set<String>> headers = new LinkedCaseInsensitiveMap<>();

		if (entityTag != null)
			headers.put("ETag", Set.of(entityTag.toHeaderValue()));

		if (truncatedLastModified != null)
			headers.put("Last-Modified", Set.of(HttpDate.toHeaderValue(truncatedLastModified)));

		return Collections.unmodifiableMap(headers);
	}

	@NonNull
	private static Map<@NonNull String, @NonNull Set<@NonNull String>> copyExtraHeaders(
			@Nullable Map<@NonNull String, @NonNull Set<@NonNull String>> extraHeaders) {
		if (extraHeaders == null || extraHeaders.isEmpty())
			return Map.of();

		Map<String, Set<String>> copiedHeaders = new LinkedCaseInsensitiveMap<>();

		for (Map.Entry<String, Set<String>> entry : extraHeaders.entrySet()) {
			String headerName = requireNonNull(entry.getKey());
			rejectControlledExtraHeader(headerName);

			Set<String> copiedHeaderValues = new LinkedHashSet<>(requireNonNull(entry.getValue()));
			copiedHeaderValues.forEach(value -> {
				requireNonNull(value, format("Header '%s' includes a null value.", headerName));
				Utilities.validateHeaderNameAndValue(headerName, value);
			});
			copiedHeaders.put(headerName, Collections.unmodifiableSet(copiedHeaderValues));
		}

		return Collections.unmodifiableMap(copiedHeaders);
	}

	private static void rejectControlledExtraHeader(@NonNull String headerName) {
		requireNonNull(headerName);
		String normalizedHeaderName = headerName.toLowerCase(java.util.Locale.US);

		if (CONTROLLED_EXTRA_HEADER_NAMES.contains(normalizedHeaderName))
			throw new IllegalArgumentException(format("Header '%s' is controlled by conditional request responses; supply validators through the dedicated arguments.", headerName));
	}
}
