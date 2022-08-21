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
 * @author <a href="https://www.revetware.com">Mark Allen</a>
 */
public interface ResponseMarshaler {
	@Nonnull
	MarshaledResponse toDefaultMarshaledResponse(@Nonnull Request request,
																							 @Nonnull Response response,
																							 @Nonnull ResourceMethod resourceMethod);

	@Nonnull
	MarshaledResponse toNotFoundMarshaledResponse(@Nonnull Request request);

	@Nonnull
	MarshaledResponse toMethodNotAllowedMarshaledResponse(@Nonnull Request request,
																												@Nonnull Set<HttpMethod> allowedHttpMethods);

	@Nonnull
	MarshaledResponse toOptionsMarshaledResponse(@Nonnull Request request,
																							 @Nonnull Set<HttpMethod> allowedHttpMethods);

	@Nonnull
	MarshaledResponse toCorsAllowedMarshaledResponse(@Nonnull Request request,
																									 @Nonnull CorsRequest corsRequest,
																									 @Nonnull CorsResponse corsResponse);

	@Nonnull
	MarshaledResponse toCorsRejectedMarshaledResponse(@Nonnull Request request,
																										@Nonnull CorsRequest corsRequest);

	@Nonnull
	MarshaledResponse toExceptionMarshaledResponse(@Nonnull Request request,
																								 @Nonnull Throwable throwable,
																								 @Nullable ResourceMethod resourceMethod);
}
