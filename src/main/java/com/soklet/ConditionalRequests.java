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
import java.util.ArrayList;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
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
 * {@link #evaluate(Request, EntityTag, Instant)} exposes the same decision as immutable data for application-owned
 * responses; {@link #evaluateWithoutRepresentation(Request)} supports conditional creation against an absent resource.
 * <p>
 * Perform normal authorization, routing, and resource checks first. These helpers do not override an otherwise
 * applicable failure such as {@code 401}, {@code 403}, {@code 404} on a non-creating endpoint, or {@code 405}.
 * A typed {@link com.soklet.annotation.RequestBody} is unmarshaled before its resource method is invoked, so in-method
 * evaluation does not promise header-before-body-validation ordering.
 * <p>
 * Evaluation describes a snapshot, not an atomic write. After a known no-effect storage race, reload fresh state and
 * re-evaluate the client's conditions, or enforce an explicitly equivalent predicate atomically. A failed internal
 * version compare-and-set alone does not justify {@code 412}. Under snapshot isolation an application may need a new
 * transaction; uncertain commit outcomes require application recovery rather than blind retries.
 * <p>
 * {@code OPTIONS} ignores conditional values. Malformed entity-tag preconditions fail closed when they protect writes:
 * malformed {@code If-Match} returns
 * {@code 412 Precondition Failed}, and malformed {@code If-None-Match} does the same for non-{@code GET}/{@code HEAD}
 * requests to applicable methods. Malformed {@code If-None-Match} on {@code GET} and {@code HEAD} is treated as a
 * cache miss and still suppresses {@code If-Modified-Since}. The same policy applies to parser-budget exhaustion.
 * Applications choose required-precondition policies such as {@code 428}; this API neither imposes them nor treats a
 * nonempty satisfied set as adequate lost-update protection. Strong {@code If-Match} comparison is never weakened for
 * transport compression: a compressed response's weak ETag cannot satisfy it.
 * <p>
 * Applications constructing {@code 304} responses own the selected representation's required cache and validator
 * metadata. RFC 9110 permits an application-established already-applied successful response for false
 * {@code If-Match}/{@code If-Unmodified-Since}, not a failed {@code If-None-Match}; the evaluator does not infer it.
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
	 * Evaluates conditional fields against an existing selected representation, without constructing a response.
	 * <p>
	 * Either validator may be unavailable. In particular, {@code null}/{@code null} means an existing representation
	 * without validators, never a missing representation. Use {@link #evaluateWithoutRepresentation(Request)} for absence.
	 * A wildcard {@code If-Match} succeeds for an existing representation even with no current ETag; a wildcard
	 * {@code If-None-Match} does not. With no ETag, valid tag lists cannot match. With a weak current tag, no valid
	 * {@code If-Match} list can strongly match; weak comparison still applies to {@code If-None-Match}.
	 * <p>
	 * Repeated entity-tag fields are combined in occurrence order. Valid empty lists match nothing. Tag fields suppress
	 * their corresponding date fields even when empty or malformed. Invalid/unavailable dates are ignored, and valid
	 * dates are compared at whole-second precision. The first decisive precondition is returned; ignored or later fields
	 * are not inspected for diagnostics. {@code OPTIONS} returns an empty {@link ConditionalRequestResult.Proceed}
	 * without parsing conditional values.
	 * <p>
	 * Entity-tag parsing first performs quote-aware bounded splitting, counting empty elements but not quoted commas.
	 * The budget is currently 256 elements. A reached limit immediately reports
	 * {@link ConditionalRequestResult.PreconditionFailed.Reason#ENTITY_TAG_LIST_LIMIT_EXCEEDED}; otherwise an unclosed
	 * quote reports {@link ConditionalRequestResult.PreconditionFailed.Reason#MALFORMED}, then invalid individual tags do
	 * the same. No blanket malformed-versus-limit priority applies when input has both problems.
	 *
	 * @param request the request whose conditional fields should be evaluated
	 * @param entityTag the existing representation's entity tag, or {@code null} if unavailable
	 * @param lastModified its last-modified instant, or {@code null} if unavailable
	 * @return immutable conditional decision for the supplied snapshot
	 */
	@NonNull
	public static ConditionalRequestResult evaluate(@NonNull Request request,
			@Nullable EntityTag entityTag, @Nullable Instant lastModified) {
		requireNonNull(request);
		return ConditionalRequestEvaluator.evaluate(ConditionalRequestEvaluator.RequestContext.fromRequest(request),
				entityTag, lastModified, true);
	}

	/**
	 * Evaluates conditional fields when no current representation exists and the request can otherwise proceed,
	 * such as a creating PUT. Do not use null validators with {@link #evaluate(Request, EntityTag, Instant)} to mean absence.
	 * <p>
	 * On applicable methods, any valid {@code If-Match}, including a wildcard or empty list, fails. Any valid
	 * {@code If-None-Match} evaluates true, including a wildcard or empty list. Date fields are ignored because no
	 * modification time exists. This entry point cannot return {@link ConditionalRequestResult.NotModified}; its
	 * {@link ConditionalRequestResult.Proceed} has either an empty satisfied set or just {@code IF_NONE_MATCH}.
	 * Existing malformed/over-limit policies still apply, and {@code OPTIONS} always proceeds with an empty set.
	 * <p>
	 * Non-creating endpoints should ordinarily return their normal missing-resource response before evaluation.
	 * Conditional creation still requires an atomic insert-if-absent and fresh evaluation after a known target-identity
	 * conflict; arbitrary database errors or uncertain commits are not blind-retry signals.
	 *
	 * @param request the request to evaluate against no current representation
	 * @return immutable decision; never {@link ConditionalRequestResult.NotModified}
	 */
	@NonNull
	public static ConditionalRequestResult evaluateWithoutRepresentation(@NonNull Request request) {
		requireNonNull(request);
		return ConditionalRequestEvaluator.evaluate(ConditionalRequestEvaluator.RequestContext.fromRequest(request),
				null, null, false);
	}

	/**
	 * Evaluates conditional request headers against an existing representation and builds the default response.
	 * <p>
	 * Null validators mean unavailable validators, never a missing representation. {@code OPTIONS} proceeds without
	 * parsing conditional values. See {@link #evaluate(Request, EntityTag, Instant)} to construct application-owned
	 * responses, and {@link #evaluateWithoutRepresentation(Request)} for a missing representation.
	 *
	 * @param request      the request whose conditional headers should be evaluated
	 * @param entityTag    the current representation's entity tag, or {@code null} if unavailable
	 * @param lastModified the current representation's last-modified instant, or {@code null} if unavailable
	 * @return a short-circuit response, or {@link Optional#empty()} when the application should continue normally
	 */
	@NonNull
	public static Optional<@NonNull Response> responseFor(@NonNull Request request,
																						 @Nullable EntityTag entityTag,
																						 @Nullable Instant lastModified) {
		return responseFor(request, entityTag, lastModified, null);
	}

	/**
	 * Evaluates conditional request headers against an existing representation and builds the default response.
	 * <p>
	 * Null validators mean unavailable validators, never a missing representation. {@code OPTIONS} proceeds without
	 * parsing conditional values. All failure reasons map to a bodyless {@code 412}; see
	 * {@link #evaluate(Request, EntityTag, Instant)} for a fine-grained decision.
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
	public static Optional<@NonNull Response> responseFor(@NonNull Request request,
																						 @Nullable EntityTag entityTag,
																						 @Nullable Instant lastModified,
																						 @Nullable Map<@NonNull String, @NonNull List<@NonNull String>> extraHeaders) {
		requireNonNull(request);
		Map<String, List<String>> copiedExtraHeaders = copyExtraHeaders(extraHeaders);
		Instant truncatedLastModified = lastModified == null ? null : ConditionalRequestEvaluator.truncateToSeconds(lastModified);
		ConditionalRequestResult result = evaluate(request, entityTag, truncatedLastModified);

		return switch (result) {
			case ConditionalRequestResult.Proceed ignored -> Optional.empty();
			case ConditionalRequestResult.NotModified ignored -> Optional.of(bodylessResponse(304, entityTag, truncatedLastModified, copiedExtraHeaders));
			case ConditionalRequestResult.PreconditionFailed ignored -> Optional.of(bodylessResponse(412, entityTag, truncatedLastModified, copiedExtraHeaders));
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
	public static Map<@NonNull String, @NonNull List<@NonNull String>> validatorHeaders(@Nullable EntityTag entityTag,
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
	public static Map<@NonNull String, @NonNull List<@NonNull String>> validatorHeaders(@Nullable EntityTag entityTag,
																																										 @Nullable Instant lastModified,
																																										 @Nullable Map<@NonNull String, @NonNull List<@NonNull String>> extraHeaders) {
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
																					 @NonNull Map<@NonNull String, @NonNull List<@NonNull String>> extraHeaders) {
		requireNonNull(statusCode);
		requireNonNull(extraHeaders);

		return Response.withStatusCode(statusCode)
				.headers(responseHeaders(entityTag, lastModified, extraHeaders))
				.build();
	}

	@NonNull
	private static Map<@NonNull String, @NonNull List<@NonNull String>> responseHeaders(@Nullable EntityTag entityTag,
																																										 @Nullable Instant lastModified,
																																										 @NonNull Map<@NonNull String, @NonNull List<@NonNull String>> extraHeaders) {
		requireNonNull(extraHeaders);
		Map<String, List<String>> headers = new LinkedCaseInsensitiveMap<>();
		headers.putAll(validatorHeadersFor(entityTag, lastModified));
		headers.putAll(extraHeaders);
		return Collections.unmodifiableMap(headers);
	}

	@NonNull
	private static Map<@NonNull String, @NonNull List<@NonNull String>> validatorHeadersFor(@Nullable EntityTag entityTag,
																																												@Nullable Instant truncatedLastModified) {
		Map<String, List<String>> headers = new LinkedCaseInsensitiveMap<>();

		if (entityTag != null)
			headers.put("ETag", List.of(entityTag.toHeaderValue()));

		if (truncatedLastModified != null)
			headers.put("Last-Modified", List.of(HttpDate.toHeaderValue(truncatedLastModified)));

		return Collections.unmodifiableMap(headers);
	}

	@NonNull
	private static Map<@NonNull String, @NonNull List<@NonNull String>> copyExtraHeaders(
			@Nullable Map<@NonNull String, @NonNull List<@NonNull String>> extraHeaders) {
		if (extraHeaders == null || extraHeaders.isEmpty())
			return Map.of();

		Map<String, List<String>> copiedHeaders = new LinkedCaseInsensitiveMap<>();

		for (Map.Entry<String, List<String>> entry : Utilities.immutableValueLists(extraHeaders, true).entrySet()) {
			String headerName = requireNonNull(entry.getKey());
			rejectControlledExtraHeader(headerName);

			List<String> copiedHeaderValues = new ArrayList<>(requireNonNull(entry.getValue()));
			copiedHeaderValues.forEach(value -> {
				requireNonNull(value, format("Header '%s' includes a null value.", headerName));
				Utilities.validateHeaderNameAndValue(headerName, value);
			});
			copiedHeaders.put(headerName, List.copyOf(copiedHeaderValues));
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
