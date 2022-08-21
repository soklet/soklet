/*
 * Copyright 2022 Revetware LLC.
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

package com.soklet.core;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetware.com">Mark Allen</a>
 */
@ThreadSafe
public class CorsRequest {
	@Nonnull
	private final String origin;
	@Nonnull
	private final HttpMethod accessControlRequestMethod;
	@Nonnull
	private final Set<String> accessControlRequestHeaders;

	public CorsRequest(@Nonnull String origin,
										 @Nonnull HttpMethod accessControlRequestMethod) {
		this(origin, accessControlRequestMethod, null);
	}

	public CorsRequest(@Nonnull String origin,
										 @Nonnull HttpMethod accessControlRequestMethod,
										 @Nullable Set<String> accessControlRequestHeaders) {
		requireNonNull(origin);
		requireNonNull(accessControlRequestMethod);

		this.origin = origin;
		this.accessControlRequestMethod = accessControlRequestMethod;
		this.accessControlRequestHeaders = accessControlRequestHeaders == null ?
				Set.of() : Collections.unmodifiableSet(new HashSet<>(accessControlRequestHeaders));
	}

	@Override
	public String toString() {
		return format("%s{origin=%s, accessControlRequestMethod=%s, accessControlRequestHeaders=%}", getClass().getSimpleName(),
				getOrigin(), getAccessControlRequestMethod(), getAccessControlRequestHeaders());
	}

	@Override
	public boolean equals(@Nullable Object object) {
		if (this == object)
			return true;

		if (!(object instanceof CorsRequest))
			return false;

		CorsRequest corsRequest = (CorsRequest) object;

		return Objects.equals(getOrigin(), corsRequest.getOrigin())
				&& Objects.equals(getAccessControlRequestMethod(), corsRequest.getAccessControlRequestMethod())
				&& Objects.equals(getAccessControlRequestHeaders(), corsRequest.getAccessControlRequestHeaders());
	}

	@Override
	public int hashCode() {
		return Objects.hash(getOrigin(), getAccessControlRequestMethod(), getAccessControlRequestHeaders());
	}

	@Nonnull
	public String getOrigin() {
		return this.origin;
	}

	@Nonnull
	public HttpMethod getAccessControlRequestMethod() {
		return this.accessControlRequestMethod;
	}

	@Nonnull
	public Set<String> getAccessControlRequestHeaders() {
		return this.accessControlRequestHeaders;
	}
}
