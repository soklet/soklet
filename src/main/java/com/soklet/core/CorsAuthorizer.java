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
import java.util.Map;
import java.util.Optional;

/**
 * Contract for types that authorize <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/CORS">CORS</a> requests.
 * <p>
 * See <a href="https://www.soklet.com/docs/cors#authorizing-cors-requests">https://www.soklet.com/docs/cors#authorizing-cors-requests</a> for detailed documentation.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public interface CorsAuthorizer {
	/**
	 * Authorizes a <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/CORS">non-preflight CORS</a> request.
	 *
	 * @param request the request to authorize
	 * @param cors    the CORS data provided in the request
	 * @return a {@link CorsResponse} if authorized, or {@link Optional#empty()} if not authorized
	 */
	@Nonnull
	Optional<CorsResponse> authorize(@Nonnull Request request,
																	 @Nonnull Cors cors);

	/**
	 * Authorizes a <a href="https://developer.mozilla.org/en-US/docs/Glossary/Preflight_request">CORS preflight</a> request.
	 *
	 * @param request                              the preflight request to authorize
	 * @param corsPreflight                        the CORS preflight data provided in the request
	 * @param availableResourceMethodsByHttpMethod <em>Resource Methods</em> that are available to serve requests according to parameters specified by the preflight data
	 * @return a {@link CorsPreflightResponse} if authorized, or {@link Optional#empty()} if not authorized
	 */
	@Nonnull
	Optional<CorsPreflightResponse> authorizePreflight(@Nonnull Request request,
																										 @Nonnull CorsPreflight corsPreflight,
																										 @Nonnull Map<HttpMethod, ResourceMethod> availableResourceMethodsByHttpMethod);
}
