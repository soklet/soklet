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
import java.util.Map.Entry;
import java.util.Set;

import static java.util.Locale.ENGLISH;
import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class DefaultResponseGzipPolicy implements ResponseGzipPolicy {
	@NonNull
	private final Integer minimumBodySizeInBytes;

	DefaultResponseGzipPolicy(@NonNull Integer minimumBodySizeInBytes) {
		this.minimumBodySizeInBytes = requireNonNull(minimumBodySizeInBytes);

		if (minimumBodySizeInBytes < 0)
			throw new IllegalArgumentException("Minimum body size must be >= 0");
	}

	@Override
	@NonNull
	public Boolean shouldGzip(@NonNull Request request,
														@NonNull MarshaledResponse response) {
		requireNonNull(request);
		requireNonNull(response);

		return effectiveBodyLength(request, response) >= getMinimumBodySizeInBytes()
				&& hasCompressibleContentType(response);
	}

	@NonNull
	protected Integer getMinimumBodySizeInBytes() {
		return this.minimumBodySizeInBytes;
	}

	@NonNull
	private Boolean hasCompressibleContentType(@NonNull MarshaledResponse response) {
		requireNonNull(response);

		for (Entry<String, Set<String>> entry : response.getHeaders().entrySet()) {
			if (!"Content-Type".equalsIgnoreCase(entry.getKey()))
				continue;

			for (String contentType : entry.getValue()) {
				if (isCompressibleContentType(contentType))
					return true;
			}
		}

		return false;
	}

	@NonNull
	private Long effectiveBodyLength(@NonNull Request request,
																	 @NonNull MarshaledResponse response) {
		requireNonNull(request);
		requireNonNull(response);

		Long bodyLength = response.getBodyLength();

		if (bodyLength > 0 || request.getHttpMethod() != HttpMethod.HEAD)
			return bodyLength;

		if (!response.isHeadResponseGzipCandidate())
			return 0L;

		for (Entry<String, Set<String>> entry : response.getHeaders().entrySet()) {
			if (!"Content-Length".equalsIgnoreCase(entry.getKey()) || entry.getValue().size() != 1)
				continue;

			try {
				Long contentLength = Long.valueOf(entry.getValue().iterator().next());
				return contentLength < 0 ? 0L : contentLength;
			} catch (NumberFormatException ignored) {
				return 0L;
			}
		}

		return 0L;
	}

	@NonNull
	private Boolean isCompressibleContentType(@NonNull String contentType) {
		requireNonNull(contentType);
		String normalizedContentType = Utilities.trimAggressivelyToNull(contentType);

		if (normalizedContentType == null)
			return false;

		int semicolonIndex = normalizedContentType.indexOf(';');

		if (semicolonIndex >= 0)
			normalizedContentType = normalizedContentType.substring(0, semicolonIndex);

		normalizedContentType = Utilities.trimAggressivelyToEmpty(normalizedContentType).toLowerCase(ENGLISH);

		return normalizedContentType.startsWith("text/")
				|| normalizedContentType.equals("application/json")
				|| (normalizedContentType.startsWith("application/") && normalizedContentType.endsWith("+json"))
				|| normalizedContentType.equals("application/xml")
				|| (normalizedContentType.startsWith("application/") && normalizedContentType.endsWith("+xml"))
				|| normalizedContentType.equals("application/javascript")
				|| normalizedContentType.equals("application/graphql")
				|| normalizedContentType.equals("application/x-www-form-urlencoded")
				|| normalizedContentType.equals("image/svg+xml");
	}
}
