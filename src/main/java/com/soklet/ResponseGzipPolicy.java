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

/**
 * Policy used by the standard HTTP server to decide whether an eligible response should be gzipped.
 * <p>
 * Soklet invokes this policy only after its own HTTP protocol checks pass. For example,
 * {@code Accept-Encoding} must permit {@code gzip}, and Soklet will skip streaming, file,
 * range, already-encoded, transfer-encoded, bodyless, and otherwise ineligible responses.
 * Implementations must be thread-safe; the standard HTTP server may invoke the policy concurrently
 * from request-handling threads.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@FunctionalInterface
public interface ResponseGzipPolicy {
	/**
	 * Acquires a policy that disables response gzip.
	 *
	 * @return a disabled response gzip policy
	 */
	@NonNull
	static ResponseGzipPolicy disabledInstance() {
		return DisabledResponseGzipPolicy.defaultInstance();
	}

	/**
	 * Acquires a policy that gzips common text-like response media types when the finalized body is
	 * at least {@code minimumBodySizeInBytes}.
	 * <p>
	 * The default media-type set includes {@code text/*}, {@code application/json},
	 * {@code application/*+json}, {@code application/xml}, {@code application/*+xml},
	 * {@code application/javascript}, {@code application/graphql}, {@code application/x-www-form-urlencoded},
	 * and {@code image/svg+xml}.
	 *
	 * @param minimumBodySizeInBytes the minimum finalized body size to gzip
	 * @return a default response gzip policy
	 */
	@NonNull
	static ResponseGzipPolicy fromDefaultsWithMinimumBodySizeInBytes(@NonNull Integer minimumBodySizeInBytes) {
		return new DefaultResponseGzipPolicy(minimumBodySizeInBytes);
	}

	/**
	 * Decides whether an eligible response should be gzipped.
	 * <p>
	 * Returning {@code true} permits gzip; returning {@code false} writes the response unchanged.
	 *
	 * @param request the request being handled
	 * @param response the finalized response being written
	 * @return {@code true} to gzip the response, or {@code false} to write it unchanged
	 */
	@NonNull
	Boolean shouldGzip(@NonNull Request request,
										 @NonNull MarshaledResponse response);
}
