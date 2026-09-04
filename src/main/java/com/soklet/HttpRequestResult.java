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
 * Encapsulates the results of a request that would normally be handled by your {@link HttpServer} (both logical response and bytes to be sent over the wire), used for integration testing via {@link Simulator#performHttpRequest(Request)}.
 * <p>
 * Instances can be acquired via the {@link #withMarshaledResponse(MarshaledResponse)} builder factory method.
 * A convenience instance factory is also available via {@link #fromMarshaledResponse(MarshaledResponse)}.
 * <p>
 * The Server-Sent Event equivalent of this type is {@link SseRequestResult}, which is used for integration testing via {@link Simulator#performSseRequest(Request)}.
 * <p>
 * See <a href="https://www.soklet.com/docs/testing#integration-testing">https://www.soklet.com/docs/testing#integration-testing</a> for detailed documentation.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class HttpRequestResult {
	@NonNull
	private final MarshaledResponse marshaledResponse;
	@Nullable
	private final Response response;
	@Nullable
	private final CorsPreflightResponse corsPreflightResponse;
	@Nullable
	private final ResourceMethod resourceMethod;
	@Nullable
	private final SseHandshakeResult sseHandshakeResult;

	/**
	 * Acquires a builder for {@link HttpRequestResult} instances.
	 *
	 * @param marshaledResponse the bytes that will ultimately be written over the wire
	 * @return the builder
	 */
	@NonNull
	public static Builder withMarshaledResponse(@NonNull MarshaledResponse marshaledResponse) {
		requireNonNull(marshaledResponse);
		return new Builder(marshaledResponse);
	}

	/**
	 * Creates a {@link HttpRequestResult} from a marshaled response without additional customization.
	 *
	 * @param marshaledResponse the bytes that will ultimately be written over the wire
	 * @return a {@link HttpRequestResult} instance
	 */
	@NonNull
	public static HttpRequestResult fromMarshaledResponse(@NonNull MarshaledResponse marshaledResponse) {
		return withMarshaledResponse(marshaledResponse).build();
	}

	/**
	 * Vends a mutable copier seeded with this instance's data, suitable for building new instances.
	 *
	 * @return a copier for this instance
	 */
	@NonNull
	public Copier copy() {
		return new Copier(this);
	}

	protected HttpRequestResult(@NonNull Builder builder) {
		requireNonNull(builder);

		this.marshaledResponse = builder.marshaledResponse;
		this.response = builder.response;
		this.corsPreflightResponse = builder.corsPreflightResponse;
		this.resourceMethod = builder.resourceMethod;
		this.sseHandshakeResult = builder.sseHandshakeResult;
	}

	@Override
	@NonNull
	public String toString() {
		List<String> components = new ArrayList<>(5);

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

		// Hide this for now because handshake info is package-private and we don't want it to leak out

		// SseHandshakeResult sseHandshakeResult = getSseHandshakeResult().orElse(null);

		// if (sseHandshakeResult != null)
		//	components.add(format("sseHandshakeResult=%s", sseHandshakeResult));

		return format("%s{%s}", getClass().getSimpleName(), components.stream().collect(Collectors.joining(", ")));
	}

	@Override
	public boolean equals(@Nullable Object object) {
		if (this == object)
			return true;

		if (!(object instanceof HttpRequestResult requestResult))
			return false;

		return Objects.equals(getMarshaledResponse(), requestResult.getMarshaledResponse())
				&& Objects.equals(getResponse(), requestResult.getResponse())
				&& Objects.equals(getCorsPreflightResponse(), requestResult.getCorsPreflightResponse())
				&& Objects.equals(getResourceMethod(), requestResult.getResourceMethod())
				&& Objects.equals(getSseHandshakeResult(), requestResult.getSseHandshakeResult());
	}

	@Override
	public int hashCode() {
		return Objects.hash(getMarshaledResponse(), getResponse(), getCorsPreflightResponse(), getResourceMethod(), getSseHandshakeResult());
	}

	/**
	 * The final representation of the response to be written over the wire.
	 *
	 * @return the response to be written over the wire
	 */
	@NonNull
	public MarshaledResponse getMarshaledResponse() {
		return this.marshaledResponse;
	}

	/**
	 * The logical response, determined by the return value of the <em>Resource Method</em> (if available).
	 *
	 * @return the logical response
	 */
	@NonNull
	public Optional<@NonNull Response> getResponse() {
		return Optional.ofNullable(this.response);
	}

	/**
	 * The CORS preflight logical response, if applicable for the request.
	 *
	 * @return the CORS preflight logical response
	 */
	@NonNull
	public Optional<@NonNull CorsPreflightResponse> getCorsPreflightResponse() {
		return Optional.ofNullable(this.corsPreflightResponse);
	}

	/**
	 * The <em>Resource Method</em> that handled the request, if available.
	 *
	 * @return the <em>Resource Method</em> that handled the request
	 */
	@NonNull
	public Optional<@NonNull ResourceMethod> getResourceMethod() {
		return Optional.ofNullable(this.resourceMethod);
	}


	/**
	 * The SSE handshake result, if available.
	 *
	 * @return the SSE handshake result
	 */
	@NonNull
	Optional<SseHandshakeResult> getSseHandshakeResult() {
		return Optional.ofNullable(this.sseHandshakeResult);
	}

	/**
	 * Builder used to construct instances of {@link HttpRequestResult} via {@link HttpRequestResult#withMarshaledResponse(MarshaledResponse)}.
	 * <p>
	 * This class is intended for use by a single thread.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static final class Builder {
		@NonNull
		private MarshaledResponse marshaledResponse;
		@Nullable
		private Response response;
		@Nullable
		private CorsPreflightResponse corsPreflightResponse;
		@Nullable
		private ResourceMethod resourceMethod;
		@Nullable
		private SseHandshakeResult sseHandshakeResult;

		protected Builder(@NonNull MarshaledResponse marshaledResponse) {
			requireNonNull(marshaledResponse);
			this.marshaledResponse = marshaledResponse;
		}

		@NonNull
		public Builder marshaledResponse(@NonNull MarshaledResponse marshaledResponse) {
			requireNonNull(marshaledResponse);
			this.marshaledResponse = marshaledResponse;
			return this;
		}

		/**
		 * Sets the logical response that produced the marshaled response. Passing
		 * {@code null} clears any previously configured logical response.
		 *
		 * @param response the logical response, or {@code null} to clear it
		 * @return this builder
		 */
		@NonNull
		public Builder response(@Nullable Response response) {
			this.response = response;
			return this;
		}

		/**
		 * Sets the CORS preflight response associated with this result. Passing
		 * {@code null} clears any previously configured CORS preflight response.
		 *
		 * @param corsPreflightResponse the CORS preflight response, or {@code null} to clear it
		 * @return this builder
		 */
		@NonNull
		public Builder corsPreflightResponse(@Nullable CorsPreflightResponse corsPreflightResponse) {
			this.corsPreflightResponse = corsPreflightResponse;
			return this;
		}

		/**
		 * Sets the <em>Resource Method</em> that handled the request. Passing
		 * {@code null} clears any previously configured <em>Resource Method</em>.
		 *
		 * @param resourceMethod the <em>Resource Method</em>, or {@code null} to clear it
		 * @return this builder
		 */
		@NonNull
		public Builder resourceMethod(@Nullable ResourceMethod resourceMethod) {
			this.resourceMethod = resourceMethod;
			return this;
		}

		@NonNull
		Builder sseHandshakeResult(@Nullable SseHandshakeResult sseHandshakeResult) {
			this.sseHandshakeResult = sseHandshakeResult;
			return this;
		}

		@NonNull
		public HttpRequestResult build() {
			return new HttpRequestResult(this);
		}
	}

	/**
	 * Builder used to copy instances of {@link HttpRequestResult} via {@link HttpRequestResult#copy()}.
	 * <p>
	 * This class is intended for use by a single thread.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static final class Copier {
		@NonNull
		private final Builder builder;

		Copier(@NonNull HttpRequestResult requestResult) {
			requireNonNull(requestResult);

			this.builder = new Builder(requestResult.getMarshaledResponse())
					.response(requestResult.getResponse().orElse(null))
					.corsPreflightResponse(requestResult.getCorsPreflightResponse().orElse(null))
					.resourceMethod(requestResult.getResourceMethod().orElse(null))
					.sseHandshakeResult(requestResult.getSseHandshakeResult().orElse(null));
		}

		@NonNull
		public Copier marshaledResponse(@NonNull MarshaledResponse marshaledResponse) {
			requireNonNull(marshaledResponse);
			this.builder.marshaledResponse(marshaledResponse);
			return this;
		}

		@NonNull
		public Copier response(@Nullable Response response) {
			this.builder.response(response);
			return this;
		}

		@NonNull
		public Copier corsPreflightResponse(@Nullable CorsPreflightResponse corsPreflightResponse) {
			this.builder.corsPreflightResponse(corsPreflightResponse);
			return this;
		}

		@NonNull
		public Copier resourceMethod(@Nullable ResourceMethod resourceMethod) {
			this.builder.resourceMethod(resourceMethod);
			return this;
		}

		@NonNull
		Copier sseHandshakeResult(@Nullable SseHandshakeResult sseHandshakeResult) {
			this.builder.sseHandshakeResult(sseHandshakeResult);
			return this;
		}

		@NonNull
		public HttpRequestResult finish() {
			return this.builder.build();
		}
	}
}
