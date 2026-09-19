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
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import static java.util.Locale.ENGLISH;
import static java.util.Objects.requireNonNull;

/**
 * An immutable selection of no compression or one response-compression codec with an optional
 * application-supplied compressed-body provider.
 * <p>
 * Creating a plan does not compress or cache a body. Soklet first checks whether the selected coding
 * can be used for this response and client, then computes bytes only if a body will be written.
 * In particular, {@code HEAD} planning can select compressed representation headers without invoking
 * the codec or provider. A plan cannot bypass transport eligibility or content-coding negotiation.
 * <p>
 * A provider can wrap Soklet's lazy compression operation with an application-owned cache. Cache keys
 * must identify the exact uncompressed representation and the codec/configuration producing its bytes;
 * a URL or weak ETag alone is not sufficient. Cache only reusable body bytes, not another request's
 * response headers or cookies. Applications own cache capacity, expiration, invalidation, and the
 * handling of private content. Soklet provides no implicit cache.
 * <p>
 * Plans retain codec and provider references. Codecs must be thread-safe. A provider is invoked
 * synchronously on its request-handling thread; if a plan or provider is reused across requests, its
 * captured state and cache must also support concurrent use. Returned byte arrays are not copied and
 * must remain unmodified while retained for reuse or written by any response.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class ResponseCompressionPlan {
	@NonNull
	private static final ResponseCompressionPlan NONE_INSTANCE;

	static {
		NONE_INSTANCE = new ResponseCompressionPlan(null, null);
	}

	@Nullable
	private final ResponseCompressionCodec codec;
	@Nullable
	private final String contentEncoding;
	@Nullable
	private final Function<@NonNull Supplier<byte @NonNull []>, byte @NonNull []> compressedBodyProvider;

	private ResponseCompressionPlan(@Nullable ResponseCompressionCodec codec,
																		@Nullable Function<@NonNull Supplier<byte @NonNull []>, byte @NonNull []> compressedBodyProvider) {
		this.codec = codec;
		this.contentEncoding = codec == null ? null : validatedContentEncoding(codec.getContentEncoding());
		this.compressedBodyProvider = compressedBodyProvider;
	}

	/**
	 * Acquires the shared plan that declines response compression.
	 *
	 * @return the no-compression plan
	 */
	@NonNull
	public static ResponseCompressionPlan none() {
		return NONE_INSTANCE;
	}

	/**
	 * Selects a codec, letting Soklet invoke it lazily when the response needs compressed body bytes.
	 * <p>
	 * The codec's content coding is validated and snapshotted during this call; compression is not run.
	 *
	 * @param codec the codec to use
	 * @return a compression plan without a custom body provider
	 * @throws IllegalArgumentException if the codec's content coding is not a valid compression token
	 */
	@NonNull
	public static ResponseCompressionPlan compress(@NonNull ResponseCompressionCodec codec) {
		return new ResponseCompressionPlan(requireNonNull(codec), null);
	}

	/**
	 * Selects a codec and wraps its lazy body computation with application behavior, such as caching.
	 * <p>
	 * Soklet invokes the provider at most once per written response, after transport and content-coding
	 * checks pass, and never for {@code HEAD}. Its supplier is bound to this response's exact uncompressed
	 * body and the selected codec. The supplier is lazy and memoized per response: repeated successful
	 * calls return the same byte array without repeating compression. A cache hit need not call it.
	 * <p>
	 * The supplier is confined to this provider invocation and its invoking thread. Do not retain it,
	 * submit it to another thread, or call it after the provider returns. Complete all work synchronously.
	 * Cache the resulting bytes, not the supplier or request. The provider must return non-null bytes
	 * that decode to the exact response body using the selected codec's content coding; it must not
	 * mutate the supplied or returned arrays. A null result or an exception is a response-processing
	 * failure, not a request for an uncompressed fallback.
	 *
	 * @param codec the codec to use
	 * @param compressedBodyProvider the function that returns cached bytes or obtains them from Soklet's supplier
	 * @return a compression plan with a custom body provider
	 * @throws IllegalArgumentException if the codec's content coding is not a valid compression token
	 */
	@NonNull
	public static ResponseCompressionPlan compress(@NonNull ResponseCompressionCodec codec,
																									@NonNull Function<@NonNull Supplier<byte @NonNull []>, byte @NonNull []> compressedBodyProvider) {
		return new ResponseCompressionPlan(requireNonNull(codec), requireNonNull(compressedBodyProvider));
	}

	/**
	 * The selected codec, if this plan requests compression.
	 *
	 * @return the codec, or {@link Optional#empty()} for {@link #none()}
	 */
	@NonNull
	public Optional<@NonNull ResponseCompressionCodec> getCodec() {
		return Optional.ofNullable(this.codec);
	}

	/**
	 * The validated lowercase content coding captured from the codec when this plan was created.
	 * <p>
	 * Soklet uses this snapshot for content-coding negotiation and the {@code Content-Encoding} header;
	 * later changes to a codec's reported coding cannot change a plan's meaning.
	 *
	 * @return the content coding, or {@link Optional#empty()} for {@link #none()}
	 */
	@NonNull
	public Optional<@NonNull String> getContentEncoding() {
		return Optional.ofNullable(this.contentEncoding);
	}

	/**
	 * The custom compressed-body provider, if supplied.
	 * <p>
	 * An empty value means either no compression was requested, or Soklet should obtain bytes directly
	 * from the selected codec. Provider execution belongs to the transport and obeys the lifetime
	 * contract of {@link #compress(ResponseCompressionCodec, Function)}.
	 *
	 * @return the custom provider, or {@link Optional#empty()} when absent
	 */
	@NonNull
	public Optional<@NonNull Function<@NonNull Supplier<byte @NonNull []>, byte @NonNull []>> getCompressedBodyProvider() {
		return Optional.ofNullable(this.compressedBodyProvider);
	}

	@NonNull
	private static String validatedContentEncoding(@NonNull String contentEncoding) {
		requireNonNull(contentEncoding, "Compression codec content encoding must not be null.");

		if (contentEncoding.isEmpty())
			throw new IllegalArgumentException("Compression codec content encoding must be a nonempty HTTP token.");

		for (int i = 0; i < contentEncoding.length(); ++i) {
			char character = contentEncoding.charAt(i);
			boolean tokenCharacter = (character >= 'a' && character <= 'z')
					|| (character >= 'A' && character <= 'Z')
					|| (character >= '0' && character <= '9')
					|| "!#$%&'*+-.^_`|~".indexOf(character) >= 0;

			if (!tokenCharacter)
				throw new IllegalArgumentException("Compression codec content encoding must be a single ASCII HTTP token.");
		}

		String normalizedContentEncoding = contentEncoding.toLowerCase(ENGLISH);

		if (normalizedContentEncoding.equals("identity") || normalizedContentEncoding.equals("*"))
			throw new IllegalArgumentException("Compression codec content encoding must not be identity or '*'; use ResponseCompressionPlan.none() to decline compression.");

		return normalizedContentEncoding;
	}
}
