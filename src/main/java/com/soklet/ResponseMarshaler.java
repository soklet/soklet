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

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Prepares responses for each request scenario Soklet supports (<em>Resource Method</em>, exception, CORS preflight, etc.)
 * <p>
 * The {@link MarshaledResponse} value returned from these methods is what is ultimately sent back to
 * clients as bytes over the wire.
 * <p>
 * A standard threadsafe implementation builder can be acquired via {@link #withDefaults()} or {@link #withCharset(Charset)} builder factory methods.
 * This builder allows you to specify, for example, how to turn a <em>Resource Method</em> response object into a wire format (e.g. JSON) and is generally what you want.
 * <p>
 * A standard threadsafe implementation can be acquired via the {@link #defaultInstance()} factory method.
 * This is generally not needed unless your implementation requires dynamic "fall back to default" behavior that is not otherwise accessible.
 * <p>
 * Example implementation using {@link #withDefaults()}:
 * <pre>{@code // Let's use Gson to write response body data
 * // See https://github.com/google/gson
 * final Gson GSON = new Gson();
 *
 * // The request was matched to a Resource Method and executed non-exceptionally
 * ResourceMethodHandler resourceMethodHandler = (
 *   @NonNull Request request,
 *   @NonNull Response response,
 *   @NonNull ResourceMethod resourceMethod
 * ) -> {
 *   // Turn response body into JSON bytes with Gson
 *   Object bodyObject = response.getBody().orElse(null);
 *   byte[] body = bodyObject == null
 *     ? null
 *     : GSON.toJson(bodyObject).getBytes(StandardCharsets.UTF_8);
 *
 *   // To be a good citizen, set the Content-Type header
 *   Map<String, Set<String>> headers = new HashMap<>(response.getHeaders());
 *   headers.put("Content-Type", Set.of("application/json;charset=UTF-8"));
 *
 *   // Tell Soklet: "OK - here is the final response data to send"
 *   return MarshaledResponse.withResponse(response)
 *     .headers(headers)
 *     .body(body)
 *     .build();
 * };
 *
 * // Function to create responses for exceptions that bubble out
 * ThrowableHandler throwableHandler = (
 *   @NonNull Request request,
 *   @NonNull Throwable throwable,
 *   @Nullable ResourceMethod resourceMethod
 * ) -> {
 *   // Keep track of what to write to the response
 *   String message;
 *   int statusCode;
 *
 *   // Examine the exception that bubbled out and determine what
 *   // the HTTP status and a user-facing message should be.
 *   // Note: real systems should localize these messages
 *   switch (throwable) {
 *     // Soklet throws this exception, a specific subclass of BadRequestException
 *     case IllegalQueryParameterException e -> {
 *       message = String.format("Illegal value '%s' for parameter '%s'",
 *         e.getQueryParameterValue().orElse("[not provided]"),
 *         e.getQueryParameterName());
 *       statusCode = 400;
 *     }
 *
 *     // Generically handle other BadRequestExceptions
 *     case BadRequestException ignored -> {
 *       message = "Your request was improperly formatted.";
 *       statusCode = 400;
 *     }
 *
 *     // Something else?  Fall back to a 500
 *     default -> {
 *       message = "An unexpected error occurred.";
 *       statusCode = 500;
 *     }
 *   }
 *
 *   // Turn response body into JSON bytes with Gson.
 *   // Note: real systems should expose richer error constructs
 *   // than an object with a single message field
 *   byte[] body = GSON.toJson(Map.of("message", message))
 *     .getBytes(StandardCharsets.UTF_8);
 *
 *   // Specify our headers
 *   Map<String, Set<String>> headers = new HashMap<>();
 *   headers.put("Content-Type", Set.of("application/json;charset=UTF-8"));
 *
 *   return MarshaledResponse.withStatusCode(statusCode)
 *     .headers(headers)
 *     .body(body)
 *     .build();
 * };
 *
 * // Supply our custom handlers to the standard response marshaler
 * SokletConfig config = SokletConfig.withServer(
 *   Server.withPort(8080).build()
 * ).responseMarshaler(ResponseMarshaler.withDefaults()
 *   .resourceMethodHandler(resourceMethodHandler)
 *   .throwableHandler(throwableHandler)
 *   .build()
 * ).build();}</pre>
 * <p>
 * Full documentation is available at <a href="https://www.soklet.com/docs/response-writing">https://www.soklet.com/docs/response-writing</a>.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public interface ResponseMarshaler {
	/**
	 * Prepares a response for a request that was matched to a <em>Resource Method</em> and returned normally (i.e., without throwing an exception).
	 * <p>
	 * <strong>Note that the returned {@link Response} may represent any HTTP status (e.g., 200, 403, 404), and is not restricted to "successful" outcomes.</strong>
	 * <p>
	 * Detailed documentation is available at <a href="https://www.soklet.com/docs/response-writing#resource-method">https://www.soklet.com/docs/response-writing#resource-method</a>.
	 *
	 * @param request        the HTTP request
	 * @param response       the response provided by the <em>Resource Method</em> that handled the request
	 * @param resourceMethod the <em>Resource Method</em> that handled the request
	 * @return the response to be sent over the wire
	 */
	@NonNull
	MarshaledResponse forResourceMethod(@NonNull Request request,
																			@NonNull Response response,
																			@NonNull ResourceMethod resourceMethod);

	/**
	 * Prepares a response for a request that does not have a matching <em>Resource Method</em>, which triggers an <a href="https://httpwg.org/specs/rfc9110.html#status.404">HTTP 404 Not Found</a>.
	 * <p>
	 * Detailed documentation is available at <a href="https://www.soklet.com/docs/response-writing#404-not-found">https://www.soklet.com/docs/response-writing#404-not-found</a>.
	 *
	 * @param request the HTTP request
	 * @return the response to be sent over the wire
	 */
	@NonNull
	MarshaledResponse forNotFound(@NonNull Request request);

	/**
	 * Prepares a response for a request that triggers an
	 * <a href="https://httpwg.org/specs/rfc9110.html#status.405">HTTP 405 Method Not Allowed</a>.
	 * <p>
	 * Detailed documentation is available at <a href="https://www.soklet.com/docs/response-writing#405-method-not-allowed">https://www.soklet.com/docs/response-writing#405-method-not-allowed</a>.
	 *
	 * @param request            the HTTP request
	 * @param allowedHttpMethods appropriate HTTP methods to write to the {@code Allow} response header
	 * @return the response to be sent over the wire
	 */
	@NonNull
	MarshaledResponse forMethodNotAllowed(@NonNull Request request,
																				@NonNull Set<HttpMethod> allowedHttpMethods);

	/**
	 * Prepares a response for a request that triggers an <a href="https://httpwg.org/specs/rfc9110.html#status.413">HTTP 413 Content Too Large</a>.
	 * <p>
	 * Detailed documentation is available at <a href="https://www.soklet.com/docs/response-writing#413-content-too-large">https://www.soklet.com/docs/response-writing#413-content-too-large</a>.
	 *
	 * @param request        the HTTP request
	 * @param resourceMethod the <em>Resource Method</em> that would have handled the request, if available
	 * @return the response to be sent over the wire
	 */
	@NonNull
	MarshaledResponse forContentTooLarge(@NonNull Request request,
																			 @Nullable ResourceMethod resourceMethod);

	/**
	 * Prepares a response for a request that is rejected because the server is overloaded (e.g. connection limit reached), triggering an <a href="https://httpwg.org/specs/rfc9110.html#status.503">HTTP 503 Service Unavailable</a>.
	 * <p>
	 * Detailed documentation is available at <a href="https://www.soklet.com/docs/response-writing#503-service-unavailable">https://www.soklet.com/docs/response-writing#503-service-unavailable</a>.
	 *
	 * @param request        the HTTP request
	 * @param resourceMethod the <em>Resource Method</em> that would have handled the request, if available
	 * @return the response to be sent over the wire
	 */
	@NonNull
	MarshaledResponse forServiceUnavailable(@NonNull Request request,
																					@Nullable ResourceMethod resourceMethod);

	/**
	 * Prepares a response for an HTTP {@code OPTIONS} request.
	 * <p>
	 * Note that CORS preflight responses are handled specially by {@link #forCorsPreflightAllowed(Request, CorsPreflight, CorsPreflightResponse)}
	 * and {@link #forCorsPreflightRejected(Request, CorsPreflight)} - not this method.
	 * <p>
	 * Detailed documentation is available at <a href="https://www.soklet.com/docs/response-writing#http-options">https://www.soklet.com/docs/response-writing#http-options</a>.
	 *
	 * @param request            the HTTP request
	 * @param allowedHttpMethods appropriate HTTP methods to write to the {@code Allow} response header
	 * @return the response to be sent over the wire
	 */
	@NonNull
	MarshaledResponse forOptions(@NonNull Request request,
															 @NonNull Set<HttpMethod> allowedHttpMethods);

	/**
	 * Prepares a response for an HTTP {@code OPTIONS *} (colloquially, "{@code OPTIONS} Splat") request.
	 * <p>
	 * This is a special HTTP/1.1 request defined in RFC 7231 §4.3.7 which permits querying of server-wide capabilities, not the capabilities of a particular resource - e.g. a load balancer asking "is the system up?" without an explicit health-check URL.
	 * <p>
	 * Detailed documentation is available at <a href="https://www.soklet.com/docs/response-writing#http-options">https://www.soklet.com/docs/response-writing#http-options</a>.
	 *
	 * @param request the HTTP request
	 * @return the response to be sent over the wire
	 */
	@NonNull
	MarshaledResponse forOptionsSplat(@NonNull Request request);

	/**
	 * Prepares a response for scenarios in which an uncaught exception is encountered.
	 * <p>
	 * Detailed documentation is available at <a href="https://www.soklet.com/docs/response-writing#uncaught-exceptions">https://www.soklet.com/docs/response-writing#uncaught-exceptions</a>.
	 *
	 * @param request        the HTTP request
	 * @param throwable      the exception that was thrown
	 * @param resourceMethod the <em>Resource Method</em> that would have handled the request, if available
	 * @return the response to be sent over the wire
	 */
	@NonNull
	MarshaledResponse forThrowable(@NonNull Request request,
																 @NonNull Throwable throwable,
																 @Nullable ResourceMethod resourceMethod);

	/**
	 * Prepares a response for an HTTP {@code HEAD} request.
	 * <p>
	 * Detailed documentation is available at <a href="https://www.soklet.com/docs/response-writing#http-head">https://www.soklet.com/docs/response-writing#http-head</a>.
	 *
	 * @param request                    the HTTP request
	 * @param getMethodMarshaledResponse the binary data that would have been sent over the wire for an equivalent {@code GET} request (necessary in order to write the {@code Content-Length} header for a {@code HEAD} response)
	 * @return the response to be sent over the wire
	 */
	@NonNull
	MarshaledResponse forHead(@NonNull Request request,
														@NonNull MarshaledResponse getMethodMarshaledResponse);

	/**
	 * Prepares a response for "CORS preflight allowed" scenario when your {@link CorsAuthorizer} approves a preflight request.
	 * <p>
	 * Detailed documentation is available at <a href="https://www.soklet.com/docs/cors#writing-cors-responses">https://www.soklet.com/docs/cors#writing-cors-responses</a>.
	 *
	 * @param request               the HTTP request
	 * @param corsPreflight         the CORS preflight request data
	 * @param corsPreflightResponse the data that should be included in this CORS preflight response
	 * @return the response to be sent over the wire
	 */
	@NonNull
	MarshaledResponse forCorsPreflightAllowed(@NonNull Request request,
																						@NonNull CorsPreflight corsPreflight,
																						@NonNull CorsPreflightResponse corsPreflightResponse);

	/**
	 * Prepares a response for "CORS preflight rejected" scenario when your {@link CorsAuthorizer} denies a preflight request.
	 * <p>
	 * Detailed documentation is available at <a href="https://www.soklet.com/docs/cors#writing-cors-responses">https://www.soklet.com/docs/cors#writing-cors-responses</a>.
	 *
	 * @param request       the HTTP request
	 * @param corsPreflight the CORS preflight request data
	 * @return the response to be sent over the wire
	 */
	@NonNull
	MarshaledResponse forCorsPreflightRejected(@NonNull Request request,
																						 @NonNull CorsPreflight corsPreflight);

	/**
	 * Applies "CORS is permitted for this request" data to a response.
	 * <p>
	 * Invoked for any non-preflight CORS request that your {@link CorsAuthorizer} approves.
	 * <p>
	 * This method will normally return a copy of the {@code marshaledResponse} with these headers applied
	 * based on the values of {@code corsResponse}:
	 * <ul>
	 *   <li>{@code Access-Control-Allow-Origin} (required)</li>
	 *   <li>{@code Access-Control-Allow-Credentials} (optional)</li>
	 *   <li>{@code Access-Control-Expose-Headers} (optional)</li>
	 * </ul>
	 *
	 * @param request           the HTTP request
	 * @param cors              the CORS request data
	 * @param corsResponse      CORS response data to write as specified by {@link CorsAuthorizer}
	 * @param marshaledResponse the existing response to which we should apply relevant CORS headers
	 * @return the response to be sent over the wire
	 */
	@NonNull
	MarshaledResponse forCorsAllowed(@NonNull Request request,
																	 @NonNull Cors cors,
																	 @NonNull CorsResponse corsResponse,
																	 @NonNull MarshaledResponse marshaledResponse);

	/**
	 * Acquires a threadsafe {@link ResponseMarshaler} with a reasonable "out of the box" configuration that uses UTF-8 to write character data.
	 * <p>
	 * The returned instance is guaranteed to be a JVM-wide singleton.
	 *
	 * @return a UTF-8 {@code ResponseMarshaler} with default settings
	 */
	@NonNull
	static ResponseMarshaler defaultInstance() {
		return DefaultResponseMarshaler.defaultInstance();
	}

	/**
	 * Acquires a builder for a default {@link ResponseMarshaler} implementation that uses {@link java.nio.charset.StandardCharsets#UTF_8} encoding.
	 *
	 * @return a {@code ResponseMarshaler} builder
	 */
	@NonNull
	static Builder withDefaults() {
		return new Builder(StandardCharsets.UTF_8);
	}

	/**
	 * Acquires a builder for a default {@link ResponseMarshaler} implementation.
	 *
	 * @param charset the default charset to use when writing response data
	 * @return a {@code ResponseMarshaler} builder
	 */
	@NonNull
	static Builder withCharset(@NonNull Charset charset) {
		requireNonNull(charset);
		return new Builder(charset);
	}

	/**
	 * Builder used to construct a standard implementation of {@link ResponseMarshaler}.
	 * <p>
	 * This class is intended for use by a single thread.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	final class Builder {
		/**
		 * Function used to support pluggable implementations of {@link ResponseMarshaler#forResourceMethod(Request, Response, ResourceMethod)}.
		 */
		@FunctionalInterface
		public interface ResourceMethodHandler {
			/**
			 * Prepares a response for the scenario in which the request was matched to a <em>Resource Method</em> and executed non-exceptionally.
			 * <p>
			 * Detailed documentation is available at <a href="https://www.soklet.com/docs/response-writing#resource-method">https://www.soklet.com/docs/response-writing#resource-method</a>.
			 *
			 * @param request        the HTTP request
			 * @param response       the response provided by the <em>Resource Method</em> that handled the request
			 * @param resourceMethod the <em>Resource Method</em> that handled the request
			 * @return the response to be sent over the wire
			 */
			@NonNull
			MarshaledResponse handle(@NonNull Request request,
															 @NonNull Response response,
															 @NonNull ResourceMethod resourceMethod);
		}

		/**
		 * Function used to support pluggable implementations of {@link ResponseMarshaler#forNotFound(Request)}.
		 */
		@FunctionalInterface
		public interface NotFoundHandler {
			/**
			 * Prepares a response for a request that triggers an
			 * <a href="https://httpwg.org/specs/rfc9110.html#status.404">HTTP 404 Not Found</a>.
			 * <p>
			 * Detailed documentation is available at <a href="https://www.soklet.com/docs/response-writing#404-not-found">https://www.soklet.com/docs/response-writing#404-not-found</a>.
			 *
			 * @param request the HTTP request
			 * @return the response to be sent over the wire
			 */
			@NonNull
			MarshaledResponse handle(@NonNull Request request);
		}

		/**
		 * Function used to support pluggable implementations of {@link ResponseMarshaler#forMethodNotAllowed(Request, Set)}.
		 */
		@FunctionalInterface
		public interface MethodNotAllowedHandler {
			/**
			 * Prepares a response for a request that triggers an
			 * <a href="https://httpwg.org/specs/rfc9110.html#status.405">HTTP 405 Method Not Allowed</a>.
			 * <p>
			 * Detailed documentation is available at <a href="https://www.soklet.com/docs/response-writing#405-method-not-allowed">https://www.soklet.com/docs/response-writing#405-method-not-allowed</a>.
			 *
			 * @param request            the HTTP request
			 * @param allowedHttpMethods appropriate HTTP methods to write to the {@code Allow} response header
			 * @return the response to be sent over the wire
			 */
			@NonNull
			MarshaledResponse handle(@NonNull Request request,
															 @NonNull Set<HttpMethod> allowedHttpMethods);
		}

		/**
		 * Function used to support pluggable implementations of {@link ResponseMarshaler#forContentTooLarge(Request, ResourceMethod)}.
		 */
		@FunctionalInterface
		public interface ContentTooLargeHandler {
			/**
			 * Prepares a response for a request that triggers an <a href="https://httpwg.org/specs/rfc9110.html#status.413">HTTP 413 Content Too Large</a>.
			 * <p>
			 * Detailed documentation is available at <a href="https://www.soklet.com/docs/response-writing#413-content-too-large">https://www.soklet.com/docs/response-writing#413-content-too-large</a>.
			 *
			 * @param request        the HTTP request
			 * @param resourceMethod the <em>Resource Method</em> that would have handled the request, if available
			 * @return the response to be sent over the wire
			 */
			@NonNull
			MarshaledResponse handle(@NonNull Request request,
															 @Nullable ResourceMethod resourceMethod);
		}

		/**
		 * Function used to support pluggable implementations of {@link ResponseMarshaler#forServiceUnavailable(Request, ResourceMethod)}.
		 */
		@FunctionalInterface
		public interface ServiceUnavailableHandler {
			/**
			 * Prepares a response for a request that triggers an <a href="https://httpwg.org/specs/rfc9110.html#status.503">HTTP 503 Service Unavailable</a>.
			 * <p>
			 * Detailed documentation is available at <a href="https://www.soklet.com/docs/response-writing#503-service-unavailable">https://www.soklet.com/docs/response-writing#503-service-unavailable</a>.
			 *
			 * @param request        the HTTP request
			 * @param resourceMethod the <em>Resource Method</em> that would have handled the request, if available
			 * @return the response to be sent over the wire
			 */
			@NonNull
			MarshaledResponse handle(@NonNull Request request,
															 @Nullable ResourceMethod resourceMethod);
		}

		/**
		 * Function used to support pluggable implementations of {@link ResponseMarshaler#forOptions(Request, Set)}.
		 */
		@FunctionalInterface
		public interface OptionsHandler {
			/**
			 * Prepares a response for an HTTP {@code OPTIONS} request.
			 * <p>
			 * Note that CORS preflight responses are handled specially by {@link #forCorsPreflightAllowed(Request, CorsPreflight, CorsPreflightResponse)}
			 * and {@link #forCorsPreflightRejected(Request, CorsPreflight)} - not this method.
			 * <p>
			 * Detailed documentation is available at <a href="https://www.soklet.com/docs/response-writing#http-options">https://www.soklet.com/docs/response-writing#http-options</a>.
			 *
			 * @param request            the HTTP request
			 * @param allowedHttpMethods appropriate HTTP methods to write to the {@code Allow} response header
			 * @return the response to be sent over the wire
			 */
			@NonNull
			MarshaledResponse handle(@NonNull Request request,
															 @NonNull Set<HttpMethod> allowedHttpMethods);
		}

		/**
		 * Function used to support pluggable implementations of {@link ResponseMarshaler#forOptionsSplat(Request)}.
		 */
		@FunctionalInterface
		public interface OptionsSplatHandler {
			/**
			 * Prepares a response for an HTTP {@code OPTIONS *} (colloquially, "{@code OPTIONS} Splat") request.
			 * <p>
			 * This is a special HTTP/1.1 request defined in RFC 7231 §4.3.7 which permits querying of server-wide capabilities, not the capabilities of a particular resource - e.g. a load balancer asking "is the system up?" without an explicit health-check URL.
			 * <p>
			 * Detailed documentation is available at <a href="https://www.soklet.com/docs/response-writing#http-options">https://www.soklet.com/docs/response-writing#http-options</a>.
			 *
			 * @param request the HTTP request
			 * @return the response to be sent over the wire
			 */
			@NonNull
			MarshaledResponse handle(@NonNull Request request);
		}

		/**
		 * Function used to support pluggable implementations of {@link ResponseMarshaler#forThrowable(Request, Throwable, ResourceMethod)}.
		 */
		@FunctionalInterface
		public interface ThrowableHandler {
			/**
			 * Prepares a response for scenarios in which an uncaught exception is encountered.
			 * <p>
			 * Detailed documentation is available at <a href="https://www.soklet.com/docs/response-writing#uncaught-exceptions">https://www.soklet.com/docs/response-writing#uncaught-exceptions</a>.
			 *
			 * @param request        the HTTP request
			 * @param throwable      the exception that was thrown
			 * @param resourceMethod the <em>Resource Method</em> that would have handled the request, if available
			 * @return the response to be sent over the wire
			 */
			@NonNull
			MarshaledResponse handle(@NonNull Request request,
															 @NonNull Throwable throwable,
															 @Nullable ResourceMethod resourceMethod);
		}

		/**
		 * Function used to support pluggable implementations of {@link ResponseMarshaler#forHead(Request, MarshaledResponse)}.
		 */
		@FunctionalInterface
		public interface HeadHandler {
			/**
			 * Prepares a response for an HTTP {@code HEAD} request.
			 * <p>
			 * Detailed documentation is available at <a href="https://www.soklet.com/docs/response-writing#http-head">https://www.soklet.com/docs/response-writing#http-head</a>.
			 *
			 * @param request                    the HTTP request
			 * @param getMethodMarshaledResponse the binary data that would have been sent over the wire for an equivalent {@code GET} request (necessary in order to write the {@code Content-Length} header for a {@code HEAD} response)
			 * @return the response to be sent over the wire
			 */
			@NonNull
			MarshaledResponse handle(@NonNull Request request,
															 @NonNull MarshaledResponse getMethodMarshaledResponse);
		}

		/**
		 * Function used to support pluggable implementations of {@link ResponseMarshaler#forCorsPreflightAllowed(Request, CorsPreflight, CorsPreflightResponse)}.
		 */
		@FunctionalInterface
		public interface CorsPreflightAllowedHandler {
			/**
			 * Prepares a response for "CORS preflight allowed" scenario when your {@link CorsAuthorizer} approves a preflight request.
			 * <p>
			 * Detailed documentation is available at <a href="https://www.soklet.com/docs/cors#writing-cors-responses">https://www.soklet.com/docs/cors#writing-cors-responses</a>.
			 *
			 * @param request               the HTTP request
			 * @param corsPreflight         the CORS preflight request data
			 * @param corsPreflightResponse the data that should be included in this CORS preflight response
			 * @return the response to be sent over the wire
			 */
			@NonNull
			MarshaledResponse handle(@NonNull Request request,
															 @NonNull CorsPreflight corsPreflight,
															 @NonNull CorsPreflightResponse corsPreflightResponse);
		}

		/**
		 * Function used to support pluggable implementations of {@link ResponseMarshaler#forCorsPreflightRejected(Request, CorsPreflight)}.
		 */
		@FunctionalInterface
		public interface CorsPreflightRejectedHandler {
			/**
			 * Prepares a response for "CORS preflight rejected" scenario when your {@link CorsAuthorizer} denies a preflight request.
			 * <p>
			 * Detailed documentation is available at <a href="https://www.soklet.com/docs/cors#writing-cors-responses">https://www.soklet.com/docs/cors#writing-cors-responses</a>.
			 *
			 * @param request       the HTTP request
			 * @param corsPreflight the CORS preflight request data
			 * @return the response to be sent over the wire
			 */
			@NonNull
			MarshaledResponse handle(@NonNull Request request,
															 @NonNull CorsPreflight corsPreflight);
		}

		/**
		 * Function used to support pluggable implementations of {@link ResponseMarshaler#forCorsAllowed(Request, Cors, CorsResponse, MarshaledResponse)}.
		 */
		@FunctionalInterface
		public interface CorsAllowedHandler {
			/**
			 * Applies "CORS is permitted for this request" data to a response.
			 * <p>
			 * Invoked for any non-preflight CORS request that your {@link CorsAuthorizer} approves.
			 * <p>
			 * This method will normally return a copy of the {@code marshaledResponse} with these headers applied
			 * based on the values of {@code corsResponse}:
			 * <ul>
			 *   <li>{@code Access-Control-Allow-Origin} (required)</li>
			 *   <li>{@code Access-Control-Allow-Credentials} (optional)</li>
			 *   <li>{@code Access-Control-Expose-Headers} (optional)</li>
			 * </ul>
			 *
			 * @param request           the HTTP request
			 * @param cors              the CORS request data
			 * @param corsResponse      CORS response data to write as specified by {@link CorsAuthorizer}
			 * @param marshaledResponse the existing response to which we should apply relevant CORS headers
			 * @return the response to be sent over the wire
			 */
			@NonNull
			MarshaledResponse handle(@NonNull Request request,
															 @NonNull Cors cors,
															 @NonNull CorsResponse corsResponse,
															 @NonNull MarshaledResponse marshaledResponse);
		}

		/**
		 * Function used to support a pluggable "post-process" hook for any final customization or processing before data goes over the wire.
		 */
		@FunctionalInterface
		public interface PostProcessor {
			/**
			 * Applies an optional "post-process" hook for any final customization or processing before data goes over the wire.
			 *
			 * @param marshaledResponse the response data generated by the appropriate handler function, but not yet sent over the wire
			 * @return the response data (possibly customized) to be sent over the wire
			 */
			@NonNull
			MarshaledResponse postProcess(@NonNull MarshaledResponse marshaledResponse);
		}

		@NonNull
		Charset charset;
		@Nullable
		ResourceMethodHandler resourceMethodHandler;
		@Nullable
		NotFoundHandler notFoundHandler;
		@Nullable
		MethodNotAllowedHandler methodNotAllowedHandler;
		@Nullable
		ContentTooLargeHandler contentTooLargeHandler;
		@Nullable
		ServiceUnavailableHandler serviceUnavailableHandler;
		@Nullable
		OptionsHandler optionsHandler;
		@Nullable
		OptionsSplatHandler optionsSplatHandler;
		@Nullable
		ThrowableHandler throwableHandler;
		@Nullable
		HeadHandler headHandler;
		@Nullable
		CorsPreflightAllowedHandler corsPreflightAllowedHandler;
		@Nullable
		CorsPreflightRejectedHandler corsPreflightRejectedHandler;
		@Nullable
		CorsAllowedHandler corsAllowedHandler;
		@Nullable
		PostProcessor postProcessor;

		private Builder(@NonNull Charset charset) {
			requireNonNull(charset);
			this.charset = charset;
		}

		@NonNull
		public Builder charset(@NonNull Charset charset) {
			requireNonNull(charset);
			this.charset = charset;
			return this;
		}

		@NonNull
		public Builder resourceMethodHandler(@Nullable ResourceMethodHandler resourceMethodHandler) {
			this.resourceMethodHandler = resourceMethodHandler;
			return this;
		}

		@NonNull
		public Builder notFoundHandler(@Nullable NotFoundHandler notFoundHandler) {
			this.notFoundHandler = notFoundHandler;
			return this;
		}

		@NonNull
		public Builder methodNotAllowedHandler(@Nullable MethodNotAllowedHandler methodNotAllowedHandler) {
			this.methodNotAllowedHandler = methodNotAllowedHandler;
			return this;
		}

		@NonNull
		public Builder contentTooLargeHandler(@Nullable ContentTooLargeHandler contentTooLargeHandler) {
			this.contentTooLargeHandler = contentTooLargeHandler;
			return this;
		}

		@NonNull
		public Builder serviceUnavailableHandler(@Nullable ServiceUnavailableHandler serviceUnavailableHandler) {
			this.serviceUnavailableHandler = serviceUnavailableHandler;
			return this;
		}

		@NonNull
		public Builder optionsHandler(@Nullable OptionsHandler optionsHandler) {
			this.optionsHandler = optionsHandler;
			return this;
		}

		@NonNull
		public Builder optionsSplatHandler(@Nullable OptionsSplatHandler optionsSplatHandler) {
			this.optionsSplatHandler = optionsSplatHandler;
			return this;
		}

		@NonNull
		public Builder throwableHandler(@Nullable ThrowableHandler throwableHandler) {
			this.throwableHandler = throwableHandler;
			return this;
		}

		@NonNull
		public Builder headHandler(@Nullable HeadHandler headHandler) {
			this.headHandler = headHandler;
			return this;
		}

		@NonNull
		public Builder corsPreflightAllowedHandler(@Nullable CorsPreflightAllowedHandler corsPreflightAllowedHandler) {
			this.corsPreflightAllowedHandler = corsPreflightAllowedHandler;
			return this;
		}

		@NonNull
		public Builder corsPreflightRejectedHandler(@Nullable CorsPreflightRejectedHandler corsPreflightRejectedHandler) {
			this.corsPreflightRejectedHandler = corsPreflightRejectedHandler;
			return this;
		}

		@NonNull
		public Builder corsAllowedHandler(@Nullable CorsAllowedHandler corsAllowedHandler) {
			this.corsAllowedHandler = corsAllowedHandler;
			return this;
		}

		@NonNull
		public Builder postProcessor(@Nullable PostProcessor postProcessor) {
			this.postProcessor = postProcessor;
			return this;
		}

		@NonNull
		public ResponseMarshaler build() {
			return new DefaultResponseMarshaler(this);
		}
	}
}
