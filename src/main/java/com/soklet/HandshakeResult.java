/*
 * Copyright 2022-2025 Revetware LLC.
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

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * Represents the result of a {@link com.soklet.annotation.ServerSentEventSource} "handshake".
 * <p>
 * Full documentation is available at <a href="https://www.soklet.com/docs/server-sent-events">https://www.soklet.com/docs/server-sent-events</a>.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public sealed interface HandshakeResult permits HandshakeResult.Accepted, HandshakeResult.Rejected {
	@Nonnull
	static Accepted accepted() {
		return Accepted.DEFAULT_INSTANCE;
	}

	@Nonnull
	static Accepted acceptedWithHeaders(@Nonnull Map<String, Set<String>> headers) {
		requireNonNull(headers);
		return new Accepted(headers, Set.of());
	}

	@Nonnull
	static Accepted acceptedWithCookies(@Nonnull Set<ResponseCookie> cookies) {
		requireNonNull(cookies);
		return new Accepted(Map.of(), cookies);
	}

	@Nonnull
	static Accepted acceptedWith(@Nonnull Map<String, Set<String>> headers,
															 @Nonnull Set<ResponseCookie> cookies) {
		requireNonNull(headers);
		requireNonNull(cookies);
		return new Accepted(headers, cookies);
	}

	@Nonnull
	static Rejected rejectedWithResponse(@Nonnull Response response) {
		requireNonNull(response);
		return new Rejected(response);
	}

	@ThreadSafe
	final class Accepted implements HandshakeResult {
		@Nonnull
		static final Accepted DEFAULT_INSTANCE;

		static {
			DEFAULT_INSTANCE = new Accepted(Map.of(), Set.of());
		}

		@Nonnull
		private final Map<String, Set<String>> headers;
		@Nonnull
		private final Set<ResponseCookie> cookies;

		private Accepted(@Nonnull Map<String, Set<String>> headers,
										 @Nonnull Set<ResponseCookie> cookies) {
			requireNonNull(headers);
			requireNonNull(cookies);

			this.headers = Collections.unmodifiableMap(new LinkedCaseInsensitiveMap<>(headers));
			this.cookies = Collections.unmodifiableSet(new LinkedHashSet<>(cookies));
		}

		@Nonnull
		public Map<String, Set<String>> getHeaders() {
			return this.headers;
		}

		@Nonnull
		public Set<ResponseCookie> getCookies() {
			return this.cookies;
		}

		@Override
		public String toString() {
			return format("%s{headers=%s, cookies=%s}", Accepted.class.getSimpleName(), getHeaders(), getCookies());
		}

		@Override
		public boolean equals(@Nullable Object object) {
			if (this == object)
				return true;

			if (!(object instanceof Accepted accepted))
				return false;

			return Objects.equals(getHeaders(), accepted.getHeaders())
					&& Objects.equals(getCookies(), accepted.getCookies());
		}

		@Override
		public int hashCode() {
			return Objects.hash(getHeaders(), getCookies());
		}
	}

	@ThreadSafe
	final class Rejected implements HandshakeResult {
		@Nonnull
		private final Response response;

		private Rejected(@Nonnull Response response) {
			requireNonNull(response);
			this.response = response;
		}

		@Nonnull
		public Response getResponse() {
			return this.response;
		}

		@Override
		public String toString() {
			return format("%s{response=%s}", Rejected.class.getSimpleName(), getResponse());
		}

		@Override
		public boolean equals(@Nullable Object object) {
			if (this == object)
				return true;

			if (!(object instanceof Rejected rejected))
				return false;

			return Objects.equals(getResponse(), rejected.getResponse());
		}

		@Override
		public int hashCode() {
			return Objects.hash(getResponse());
		}
	}
}
