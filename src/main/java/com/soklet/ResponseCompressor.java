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

/**
 * Selects whether and how the standard HTTP server should compress a finalized in-memory response.
 * <p>
 * A compressor returns a {@link ResponseCompressionPlan}; producing compressed bytes is deferred until
 * the transport needs a response body. The same planning step can therefore select representation
 * metadata for {@code HEAD} without invoking a codec or compressed-body provider.
 * <p>
 * Soklet retains HTTP protocol checks, content-coding negotiation, and response-header handling.
 * Streaming, file, file-channel, ranged, already-encoded, transfer-encoded, and otherwise ineligible
 * responses are not compressed. The selected codec's coding is checked against {@code Accept-Encoding}
 * after planning; a plan does not force a coding the client cannot accept. Selecting one codec does not
 * register alternative codecs or provide automatic fallback to another compression format.
 * <p>
 * This hook belongs to the standard HTTP transport. It does not compress SSE or MCP transport responses,
 * and the simulator does not perform transport compression. Compression is disabled by default; use
 * {@link HttpServer.Builder#responseCompressor(ResponseCompressor)} to opt in.
 * <p>
 * Implementations must be thread-safe: Soklet can invoke a compressor concurrently from request-handling
 * threads. Soklet does not create a response cache or retain application data for reuse. Applications
 * that cache compressed bodies own cache keys, bounds, expiration, invalidation, and any separation
 * required for private responses; see {@link ResponseCompressionPlan}.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@FunctionalInterface
@ThreadSafe
public interface ResponseCompressor {
	/**
	 * Acquires a compressor that always selects {@link ResponseCompressionPlan#none()}.
	 *
	 * @return a disabled response compressor
	 */
	@NonNull
	static ResponseCompressor disabledInstance() {
		return DisabledResponseCompressor.defaultInstance();
	}

	/**
	 * Acquires a compressor that selects Soklet's built-in gzip codec for common text-like response
	 * media types when the finalized uncompressed body is at least {@code minimumBodySizeInBytes}.
	 * <p>
	 * The default media-type set includes {@code text/*}, {@code application/json},
	 * {@code application/*+json}, {@code application/xml}, {@code application/*+xml},
	 * {@code application/javascript}, {@code application/graphql}, {@code application/x-www-form-urlencoded},
	 * and {@code image/svg+xml}. This factory provides no cache.
	 *
	 * @param minimumBodySizeInBytes the minimum uncompressed body size to gzip
	 * @return a default response compressor
	 * @throws IllegalArgumentException if the minimum body size is negative
	 */
	@NonNull
	static ResponseCompressor fromDefaultsWithMinimumBodySizeInBytes(@NonNull Integer minimumBodySizeInBytes) {
		return new DefaultResponseCompressor(minimumBodySizeInBytes);
	}

	/**
	 * Selects a compression plan for the finalized uncompressed representation.
	 * <p>
	 * The response exposes its actual uncompressed body, including during planning for {@code HEAD};
	 * it has not yet been reduced to a bodyless HEAD response. Return {@link ResponseCompressionPlan#none()}
	 * to decline compression, or select a codec with {@link ResponseCompressionPlan#compress(ResponseCompressionCodec)}.
	 * A custom compressed-body provider can reuse application-cached bytes without replacing Soklet's
	 * HTTP header handling.
	 * <p>
	 * Planning must not compress the body eagerly. Put compression or cache-loading work in the plan's
	 * provider so it is skipped when the client cannot accept the selected coding or no body is written.
	 * Neither this method nor a plan may mutate the response or its body. A null result or an exception
	 * is a response-processing failure, not an instruction to use an uncompressed fallback.
	 *
	 * @param request the request being handled
	 * @param marshaledResponse the finalized uncompressed response representation
	 * @return a non-null compression plan
	 */
	@NonNull
	ResponseCompressionPlan plan(@NonNull Request request,
															 @NonNull MarshaledResponse marshaledResponse);
}
