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
import java.util.Set;

/**
 * Prepares responses for each request scenario Soklet supports (happy path, exception, CORS preflight, etc.)
 * <p>
 * The {@link MarshaledResponse} value returned from these methods is what is ultimately sent back to
 * clients as bytes over the wire.
 *
 * @author <a href="https://www.revetware.com">Mark Allen</a>
 */
public interface ResponseMarshaler {
	/**
	 * Writes a "happy path" response - the request was matched to a resource method and executed non-exceptionally.
	 *
	 * @param request        the HTTP request
	 * @param response       the response provided by the resource method that handled the request
	 * @param resourceMethod the resource method that handled the request
	 * @return the response to send over the wire
	 */
	@Nonnull
	MarshaledResponse forHappyPath(@Nonnull Request request,
																 @Nonnull Response response,
																 @Nonnull ResourceMethod resourceMethod);

	/**
	 * Writes a response for a request that triggers an
	 * <a href="https://httpwg.org/specs/rfc9110.html#status.404">HTTP 404 Not Found</a>.
	 *
	 * @param request the HTTP request
	 * @return the response to send over the wire
	 */
	@Nonnull
	MarshaledResponse forNotFound(@Nonnull Request request);

	/**
	 * Writes a response for a request that triggers an
	 * <a href="https://httpwg.org/specs/rfc9110.html#status.405">HTTP 405 Method Not Allowed</a>.
	 *
	 * @param request            the HTTP request
	 * @param allowedHttpMethods appropriate HTTP methods to write to the {@code Allow} response header
	 * @return the response to send over the wire
	 */
	@Nonnull
	MarshaledResponse forMethodNotAllowed(@Nonnull Request request,
																				@Nonnull Set<HttpMethod> allowedHttpMethods);

	/**
	 * Writes a response for an HTTP {@code OPTIONS} request.
	 * <p>
	 * Note that CORS preflight responses are handled specially by {@link #forCorsPreflightAllowed(Request, CorsPreflightResponse)}
	 * and {@link #forCorsPreflightRejected(Request)} - not this method.
	 *
	 * @param request            the HTTP request
	 * @param allowedHttpMethods appropriate HTTP methods to write to the {@code Allow} response header
	 * @return the response to send over the wire
	 */
	@Nonnull
	MarshaledResponse forOptions(@Nonnull Request request,
															 @Nonnull Set<HttpMethod> allowedHttpMethods);

	@Nonnull
	MarshaledResponse forException(@Nonnull Request request,
																 @Nonnull Throwable throwable,
																 @Nullable ResourceMethod resourceMethod);

	@Nonnull
	MarshaledResponse forCorsPreflightAllowed(@Nonnull Request request,
																						@Nonnull CorsPreflightResponse corsPreflightResponse);

	/**
	 * @param request
	 * @return the response to send over the wire
	 */
	@Nonnull
	MarshaledResponse forCorsPreflightRejected(@Nonnull Request request);

	/**
	 * Applies "CORS is permitted for this request" data to the response.
	 * <p>
	 * Invoked for any non-preflight CORS request that {@link CorsAuthorizer} approves.
	 * <p>
	 * This method will normally return a copy of the {@code marshaledResponse} with these headers applied
	 * based on the values of {@corsResponse}:
	 * <ul>
	 *   <li>{@code Access-Control-Allow-Origin} (required)</li>
	 *   <li>{@code Access-Control-Allow-Credentials} (optional)</li>
	 *   <li>{@code Access-Control-Expose-Headers} (optional)</li>
	 * </ul>
	 *
	 * @param request           the HTTP request
	 * @param corsResponse      CORS response data to write as specified by {@link CorsAuthorizer}
	 * @param marshaledResponse the existing response to which we should apply relevant CORS headers
	 * @return the response to send over the wire
	 */
	@Nonnull
	MarshaledResponse forCorsAllowed(@Nonnull Request request,
																	 @Nonnull CorsResponse corsResponse,
																	 @Nonnull MarshaledResponse marshaledResponse);
}
