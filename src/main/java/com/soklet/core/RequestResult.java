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
 * Encapsulates the results of a request (both logical response and bytes to be sent over the wire),
 * useful for integration testing via {@link Simulator#performRequest(Request)}.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class RequestResult {
	@Nonnull
	private final MarshaledResponse marshaledResponse;
	@Nullable
	private final Response response;
	@Nullable
	private final CorsPreflightResponse corsPreflightResponse;
	@Nullable
	private final ResourceMethod resourceMethod;

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
		this.corsPreflightResponse = builder.corsPreflightResponse;
		this.resourceMethod = builder.resourceMethod;
	}

	@Override
	public String toString() {
		List<String> components = new ArrayList<>(4);

		components.add(format("marshaledResponse=%s", getMarshaledResponse()));

		Response response = getResponse().orElse(null);

		if (response != null)
			components.add(format("response=%s", response));

		CorsPreflightResponse corsPreflightResponse = getCorsPreflightResponse().orElse(null);

		if (corsPreflightResponse != null)
			components.add(format("corsPreflightResponse=%s", corsPreflightResponse));

		ResourceMethod resourceMethod = getResourceMethod().orElse(null);

		if (resourceMethod != null)
			components.add(format("resourceMethod=%s", resourceMethod));

		return format("%s{%s}", getClass().getSimpleName(), components.stream().collect(Collectors.joining(", ")));
	}

	@Override
	public boolean equals(@Nullable Object object) {
		if (this == object)
			return true;

		if (!(object instanceof RequestResult requestResult))
			return false;

		return Objects.equals(getMarshaledResponse(), requestResult.getMarshaledResponse())
				&& Objects.equals(getResponse(), requestResult.getResponse())
				&& Objects.equals(getCorsPreflightResponse(), requestResult.getCorsPreflightResponse())
				&& Objects.equals(getResourceMethod(), requestResult.getResourceMethod());
	}

	@Override
	public int hashCode() {
		return Objects.hash(getMarshaledResponse(), getResponse(), getCorsPreflightResponse(), getResourceMethod());
	}

	/**
	 * The final representation of the response to be written over the wire.
	 *
	 * @return the response to be written over the wire
	 */
	@Nonnull
	public MarshaledResponse getMarshaledResponse() {
		return this.marshaledResponse;
	}

	/**
	 * The logical response, determined by the return value of the <em>Resource Method</em> (if available).
	 *
	 * @return the logical response
	 */
	@Nonnull
	public Optional<Response> getResponse() {
		return Optional.ofNullable(this.response);
	}

	/**
	 * The CORS preflight logical response, if applicable for the request.
	 *
	 * @return the CORS preflight logical response
	 */
	@Nonnull
	public Optional<CorsPreflightResponse> getCorsPreflightResponse() {
		return Optional.ofNullable(this.corsPreflightResponse);
	}

	/**
	 * The <em>Resource Method</em> that handled the request, if available.
	 *
	 * @return the <em>Resource Method</em> that handled the request
	 */
	@Nonnull
	public Optional<ResourceMethod> getResourceMethod() {
		return Optional.ofNullable(this.resourceMethod);
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
		@Nullable
		private CorsPreflightResponse corsPreflightResponse;
		@Nullable
		private ResourceMethod resourceMethod;

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
		public Builder corsPreflightResponse(@Nullable CorsPreflightResponse corsPreflightResponse) {
			this.corsPreflightResponse = corsPreflightResponse;
			return this;
		}

		@Nonnull
		public Builder resourceMethod(@Nullable ResourceMethod resourceMethod) {
			this.resourceMethod = resourceMethod;
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
					.response(requestResult.getResponse().orElse(null))
					.corsPreflightResponse(requestResult.getCorsPreflightResponse().orElse(null))
					.resourceMethod(requestResult.getResourceMethod().orElse(null));
		}

		@Nonnull
		public Copier marshaledResponse(@Nonnull MarshaledResponse marshaledResponse) {
			requireNonNull(marshaledResponse);
			this.builder.marshaledResponse(marshaledResponse);
			return this;
		}

		@Nonnull
		public Copier response(@Nullable Response response) {
			this.builder.response(response);
			return this;
		}

		@Nonnull
		public Copier corsPreflightResponse(@Nullable CorsPreflightResponse corsPreflightResponse) {
			this.builder.corsPreflightResponse(corsPreflightResponse);
			return this;
		}

		@Nonnull
		public Copier resourceMethod(@Nullable ResourceMethod resourceMethod) {
			this.builder.resourceMethod(resourceMethod);
			return this;
		}

		@Nonnull
		public RequestResult finish() {
			return this.builder.build();
		}
	}
}
