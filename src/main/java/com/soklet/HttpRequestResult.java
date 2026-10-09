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
 * This type is also delivered to the response consumers of {@link HttpServer.RequestHandler}
 * and {@link SseServer.RequestHandler}. Custom SSE transports use {@link #getSseHandshakeResult()}
 * to obtain the logical handshake and any accepted client initializer or client context.
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
	// Transport-only HEAD planning input, deliberately kept out of lifecycle/log response objects.
	@Nullable
	private final MarshaledResponseBody headResponseCompressionBody;
	@Nullable
	private final Long headResponseBodyLength;
	// Internal SSE establishment classification; omitted from public rendering/equality.
	@Nullable
	private final Throwable requestHandlingFailure;
	@Nullable private final HttpResponseStreamObservation responseStreamObservation;

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
		this.headResponseCompressionBody = builder.headResponseCompressionBody;
		this.headResponseBodyLength = builder.headResponseBodyLength;
		this.requestHandlingFailure = builder.requestHandlingFailure;
		this.responseStreamObservation = builder.responseStreamObservation;
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

		// The handshake retains application-owned context and an executable initializer.
		// Deliberately omit it from diagnostic rendering.

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


	@NonNull
	Optional<MarshaledResponseBody> getHeadResponseCompressionBody() {
		return Optional.ofNullable(this.headResponseCompressionBody);
	}

	@NonNull
	Optional<Long> getHeadResponseBodyLength() {
		return Optional.ofNullable(this.headResponseBodyLength);
	}

	@NonNull
	Optional<Throwable> getRequestHandlingFailure() {
		return Optional.ofNullable(this.requestHandlingFailure);
	}

	@Nullable HttpResponseStreamObservation getResponseStreamObservation() { return this.responseStreamObservation; }

	/**
	 * Returns the logical SSE handshake result, if request processing produced one.
	 * <p>
	 * Custom {@link SseServer} transports use an {@link SseHandshakeResult.Accepted}
	 * result to retrieve its {@link SseHandshakeResult.Accepted#getClientInitializer() client initializer}
	 * and {@link SseHandshakeResult.Accepted#getClientContext() client context}.
	 * Acceptance is the application's decision; it does not prove that response headers
	 * were written, initialization succeeded, or the connection joined a broadcaster.
	 * Use {@link #getMarshaledResponse()} for the offered HTTP response and honor the
	 * transport's framing, admission and shutdown requirements before activation.
	 * <p>
	 * Reading this accessor does not invoke the initializer or activate a connection.
	 * Initializers and client contexts are application-owned references, not defensive
	 * copies. The transport owns invoking an initializer once after the accepted response
	 * is written, keeping initialization bounded, and preserving its context for that
	 * connection. Queued initialization writes and broadcaster activation must wait for
	 * successful initialization; failure or prior termination prevents activation.
	 * Callers own any retention, logging or disclosure of those references.
	 *
	 * @return the logical SSE handshake result, or {@link Optional#empty()} when unavailable
	 */
	@NonNull
	public Optional<@NonNull SseHandshakeResult> getSseHandshakeResult() {
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
		@Nullable
		private MarshaledResponseBody headResponseCompressionBody;
		@Nullable
		private Long headResponseBodyLength;
		@Nullable
		private Throwable requestHandlingFailure;
		@Nullable private HttpResponseStreamObservation responseStreamObservation;

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
		Builder headResponseCompressionBody(@Nullable MarshaledResponseBody headResponseCompressionBody) {
			this.headResponseCompressionBody = headResponseCompressionBody;
			return this;
		}

		@NonNull
		Builder headResponseBodyLength(@Nullable Long headResponseBodyLength) {
			this.headResponseBodyLength = headResponseBodyLength;
			return this;
		}

		@NonNull
		Builder requestHandlingFailure(@Nullable Throwable requestHandlingFailure) {
			this.requestHandlingFailure = requestHandlingFailure;
			return this;
		}

		@NonNull Builder responseStreamObservation(@Nullable HttpResponseStreamObservation observation) {
			this.responseStreamObservation = observation;
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
					.sseHandshakeResult(requestResult.getSseHandshakeResult().orElse(null))
					.headResponseCompressionBody(requestResult.getHeadResponseCompressionBody().orElse(null))
					.headResponseBodyLength(requestResult.getHeadResponseBodyLength().orElse(null))
					.requestHandlingFailure(requestResult.getRequestHandlingFailure().orElse(null))
					.responseStreamObservation(requestResult.getResponseStreamObservation());
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
		Copier headResponseCompressionBody(@Nullable MarshaledResponseBody headResponseCompressionBody) {
			this.builder.headResponseCompressionBody(headResponseCompressionBody);
			return this;
		}

		@NonNull
		Copier headResponseBodyLength(@Nullable Long headResponseBodyLength) {
			this.builder.headResponseBodyLength(headResponseBodyLength);
			return this;
		}

		@NonNull
		Copier requestHandlingFailure(@Nullable Throwable requestHandlingFailure) {
			this.builder.requestHandlingFailure(requestHandlingFailure);
			return this;
		}

		@NonNull Copier responseStreamObservation(@Nullable HttpResponseStreamObservation observation) {
			this.builder.responseStreamObservation(observation);
			return this;
		}

		@NonNull
		public HttpRequestResult finish() {
			return this.builder.build();
		}
	}
}
