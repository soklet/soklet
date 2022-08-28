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

package com.soklet.core.impl;

import com.soklet.core.CorsAuthorizer;
import com.soklet.core.CorsRequest;
import com.soklet.core.CorsResponse;
import com.soklet.core.HttpMethod;
import com.soklet.core.Request;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;
import java.util.Optional;
import java.util.Set;

/**
 * @author <a href="https://www.revetware.com">Mark Allen</a>
 */
@ThreadSafe
public class AllOriginsCorsAuthorizer implements CorsAuthorizer {
	@Nonnull
	@Override
	public Optional<CorsResponse> authorize(@Nonnull Request request,
																					@Nonnull CorsRequest corsRequest,
																					@Nonnull Set<HttpMethod> availableHttpMethods) {
		return Optional.of(new CorsResponse.Builder("*")
				.accessControlAllowMethods(availableHttpMethods)
				.accessControlAllowHeaders(Set.of("*"))
				.build());
	}
}