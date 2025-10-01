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

import com.soklet.HttpMethod;
import com.soklet.LifecycleInterceptor;
import com.soklet.LogEvent;
import com.soklet.Request;
import com.soklet.RequestResult;
import com.soklet.ResourceMethodResolver;
import com.soklet.Soklet;
import com.soklet.SokletConfig;
import com.soklet.annotation.GET;
import com.soklet.annotation.Resource;
import com.soklet.exception.IllegalRequestBodyException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;
import java.util.Set;

/*
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class ResponseErrorMappingTests {
	@Test
	public void runtime_exception_maps_to_500() {
		SokletConfig cfg = SokletConfig.forTesting()
				.resourceMethodResolver(ResourceMethodResolver.withResourceClasses(Set.of(ExplodeResource.class)))
				.lifecycleInterceptor(new LifecycleInterceptor() {
					@Override
					public void didReceiveLogEvent(@Nonnull LogEvent logEvent) { /* quiet */ }
				})
				.build();

		Soklet.runSimulator(cfg, simulator -> {
			RequestResult result = simulator.performRequest(Request.with(HttpMethod.GET, "/explode").build());
			Assertions.assertEquals(500, result.getMarshaledResponse().getStatusCode());
		});
	}

	@Test
	public void bad_request_exception_maps_to_400() {
		SokletConfig cfg = SokletConfig.forTesting()
				.resourceMethodResolver(ResourceMethodResolver.withResourceClasses(Set.of(ExplodeResource.class)))
				.lifecycleInterceptor(new LifecycleInterceptor() {
					@Override
					public void didReceiveLogEvent(@Nonnull LogEvent logEvent) { /* quiet */ }
				})
				.build();

		Soklet.runSimulator(cfg, simulator -> {
			RequestResult result = simulator.performRequest(Request.with(HttpMethod.GET, "/bad-request").build());
			Assertions.assertEquals(400, result.getMarshaledResponse().getStatusCode());
		});
	}

	@Resource
	public static class ExplodeResource {
		@GET("/explode")
		public String explode() {
			throw new RuntimeException("boom");
		}

		@GET("/bad-request")
		public String badRequest() {
			throw new IllegalRequestBodyException("nope");
		}
	}
}