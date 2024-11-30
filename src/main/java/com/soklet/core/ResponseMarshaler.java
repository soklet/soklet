/*
 * Copyright 2022-2024 Revetware LLC.
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
import java.util.Set;

/**
 * Prepares responses for each request scenario Soklet supports (happy path, exception, CORS preflight, etc.)
 * <p>
 * The {@link MarshaledResponse} value returned from these methods is what is ultimately sent back to
 * clients as bytes over the wire.
 * <p>
 * Full documentation is available at <a href="https://www.soklet.com/docs/response-writing">https://www.soklet.com/docs/response-writing</a>.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public interface ResponseMarshaler {
	/**
	 * Prepares a "happy path" response - the request was matched to a <em>Resource Method</em> and executed non-exceptionally.
	 * <p>
	 * Detailed documentation is available at <a href="https://www.soklet.com/docs/response-writing#happy-path">https://www.soklet.com/docs/response-writing#happy-path</a>.
	 *
	 * @param request        the HTTP request
	 * @param response       the response provided by the <em>Resource Method</em> that handled the request
	 * @param resourceMethod the <em>Resource Method</em> that handled the request
	 * @return the response to be sent over the wire
	 */
	@Nonnull
	MarshaledResponse forHappyPath(@Nonnull Request request,
																 @Nonnull Response response,
																 @Nonnull ResourceMethod resourceMethod);

	/**
	 * Prepares a response for a request that triggers an
	 * <a href="https://httpwg.org/specs/rfc9110.html#status.404">HTTP 404 Not Found</a>.
	 * <p>
	 * Detailed documentation is available at <a href="https://www.soklet.com/docs/response-writing#404-not-found">https://www.soklet.com/docs/response-writing#404-not-found</a>.
	 *
	 * @param request the HTTP request
	 * @return the response to be sent over the wire
	 */
	@Nonnull
	MarshaledResponse forNotFound(@Nonnull Request request);

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
	@Nonnull
	MarshaledResponse forMethodNotAllowed(@Nonnull Request request,
																				@Nonnull Set<HttpMethod> allowedHttpMethods);

	/**
	 * Prepares a response for a request that triggers an <a href="https://httpwg.org/specs/rfc9110.html#status.413">HTTP 413 Content Too Large</a>.
	 * <p>
	 * Detailed documentation is available at <a href="https://www.soklet.com/docs/response-writing#413-content-too-large">https://www.soklet.com/docs/response-writing#413-content-too-large</a>.
	 *
	 * @param request        the HTTP request
	 * @param resourceMethod the <em>Resource Method</em> that would have handled the request, if available
	 * @return the response to be sent over the wire
	 */
	@Nonnull
	MarshaledResponse forContentTooLarge(@Nonnull Request request,
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
	@Nonnull
	MarshaledResponse forOptions(@Nonnull Request request,
															 @Nonnull Set<HttpMethod> allowedHttpMethods);

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
	@Nonnull
	MarshaledResponse forThrowable(@Nonnull Request request,
																 @Nonnull Throwable throwable,
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
	@Nonnull
	MarshaledResponse forHead(@Nonnull Request request,
														@Nonnull MarshaledResponse getMethodMarshaledResponse);

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
	@Nonnull
	MarshaledResponse forCorsPreflightAllowed(@Nonnull Request request,
																						@Nonnull CorsPreflight corsPreflight,
																						@Nonnull CorsPreflightResponse corsPreflightResponse);

	/**
	 * Prepares a response for "CORS preflight rejected" scenario when your {@link CorsAuthorizer} denies a preflight request.
	 * <p>
	 * Detailed documentation is available at <a href="https://www.soklet.com/docs/cors#writing-cors-responses">https://www.soklet.com/docs/cors#writing-cors-responses</a>.
	 *
	 * @param request       the HTTP request
	 * @param corsPreflight the CORS preflight request data
	 * @return the response to be sent over the wire
	 */
	@Nonnull
	MarshaledResponse forCorsPreflightRejected(@Nonnull Request request,
																						 @Nonnull CorsPreflight corsPreflight);

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
	@Nonnull
	MarshaledResponse forCorsAllowed(@Nonnull Request request,
																	 @Nonnull Cors cors,
																	 @Nonnull CorsResponse corsResponse,
																	 @Nonnull MarshaledResponse marshaledResponse);
}
