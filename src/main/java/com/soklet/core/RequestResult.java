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

package com.soklet.core;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * TODO
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class RequestResult {
	@Nonnull
	private final MarshaledResponse marshaledResponse;
	@Nullable
	private final Response response;

	/**
	 * Acquires a builder for {@link RequestResult} instances.
	 *
	 * @param marshaledResponse the bytes that will ultimately be written over the wire
	 * @return the builder
	 */
	@Nonnull
	public static Builder withMarshaledResponse(@Nonnull MarshaledResponse marshaledResponse) {
		requireNonNull(marshaledResponse);
		return new Builder(marshaledResponse);
	}

	/**
	 * Vends a mutable copier seeded with this instance's data, suitable for building new instances.
	 *
	 * @return a copier for this instance
	 */
	@Nonnull
	public Copier copy() {
		return new Copier(this);
	}

	protected RequestResult(@Nonnull Builder builder) {
		requireNonNull(builder);

		this.marshaledResponse = builder.marshaledResponse;
		this.response = builder.response;
	}

	@Override
	public String toString() {
		List<String> components = new ArrayList<>(4);

		components.add(format("marshaledResponse=%s", getMarshaledResponse()));

		Response response = getResponse().orElse(null);

		if (response != null)
			components.add(format("response=%s", response));

		return format("%s{%s}", getClass().getSimpleName(), components.stream().collect(Collectors.joining(", ")));
	}

	@Override
	public boolean equals(@Nullable Object object) {
		if (this == object)
			return true;

		if (!(object instanceof RequestResult requestResult))
			return false;

		return Objects.equals(getMarshaledResponse(), requestResult.getMarshaledResponse())
				&& Objects.equals(getResponse(), requestResult.getResponse());
	}

	@Override
	public int hashCode() {
		return Objects.hash(getMarshaledResponse(), getResponse());
	}

	@Nonnull
	public MarshaledResponse getMarshaledResponse() {
		return this.marshaledResponse;
	}


	@Nonnull
	public Optional<Response> getResponse() {
		return Optional.ofNullable(this.response);
	}

	/**
	 * Builder used to construct instances of {@link RequestResult} via {@link RequestResult#withMarshaledResponse(MarshaledResponse)}.
	 * <p>
	 * This class is intended for use by a single thread.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static class Builder {
		@Nonnull
		private MarshaledResponse marshaledResponse;
		@Nullable
		private Response response;

		protected Builder(@Nonnull MarshaledResponse marshaledResponse) {
			requireNonNull(marshaledResponse);
			this.marshaledResponse = marshaledResponse;
		}

		@Nonnull
		public Builder marshaledResponse(@Nonnull MarshaledResponse marshaledResponse) {
			requireNonNull(marshaledResponse);
			this.marshaledResponse = marshaledResponse;
			return this;
		}

		@Nonnull
		public Builder response(@Nullable Response response) {
			this.response = response;
			return this;
		}

		@Nonnull
		public RequestResult build() {
			return new RequestResult(this);
		}
	}

	/**
	 * Builder used to copy instances of {@link RequestResult} via {@link RequestResult#copy()}.
	 * <p>
	 * This class is intended for use by a single thread.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static class Copier {
		@Nonnull
		private final Builder builder;

		Copier(@Nonnull RequestResult requestResult) {
			requireNonNull(requestResult);

			this.builder = new Builder(requestResult.getMarshaledResponse())
					.response(requestResult.getResponse().orElse(null));
		}

		@Nonnull
		public Copier marshaledResponse(@Nonnull MarshaledResponse marshaledResponse) {
			requireNonNull(marshaledResponse);
			this.builder.marshaledResponse(marshaledResponse);
			return this;
		}

		@Nonnull
		public Copier accessControlAllowCredentials(@Nullable Response response) {
			this.builder.response(response);
			return this;
		}

		@Nonnull
		public RequestResult finish() {
			return this.builder.build();
		}
	}
}
