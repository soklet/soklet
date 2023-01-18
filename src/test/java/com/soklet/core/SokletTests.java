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

import com.soklet.Soklet;
import com.soklet.SokletConfiguration;
import com.soklet.annotation.GET;
import com.soklet.annotation.QueryParameter;
import com.soklet.core.impl.DefaultResourceMethodResolver;
import org.junit.Assert;
import org.junit.Test;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetware.com">Mark Allen</a>
 */
@ThreadSafe
public class SokletTests {
	@Test
	public void requestHandlingBasics() throws Exception {
		// Use a mock server that we can send simulated requests to
		mockServerForResourceClasses(Set.of(RequestHandlingBasicsResource.class), (mockServer -> {
			// Response body should be "hello world" as bytes
			MarshaledResponse marshaledResponse = mockServer.simulateRequest(
					new Request.Builder(HttpMethod.GET, "/hello-world").build());

			Assert.assertArrayEquals("Response body doesn't match",
					"hello world".getBytes(StandardCharsets.UTF_8),
					marshaledResponse.getBody().get());

			// Missing query param?  It should be a 400
			marshaledResponse = mockServer.simulateRequest(
					new Request.Builder(HttpMethod.GET, "/integer-query-param")
							.build());

			Assert.assertEquals(400L, (long) marshaledResponse.getStatusCode());

			// Have the query param?  It's a 204
			marshaledResponse = mockServer.simulateRequest(
					new Request.Builder(HttpMethod.GET, "/integer-query-param?intQueryParam=123")
							.build());

			Assert.assertEquals(204L, (long) marshaledResponse.getStatusCode());

			// Have the custom-named query param?  It's a 200 and echoes back the param as a string
			marshaledResponse = mockServer.simulateRequest(
					new Request.Builder(HttpMethod.GET, "/query-param-custom-name?local_date=2023-09-30")
							.build());

			Assert.assertEquals(200L, (long) marshaledResponse.getStatusCode());
			Assert.assertArrayEquals("Response body doesn't match",
					"2023-09-30".getBytes(StandardCharsets.UTF_8),
					marshaledResponse.getBody().get());
		}));
	}

	@ThreadSafe
	public static class RequestHandlingBasicsResource {
		@GET("/hello-world")
		public String helloWorld() {
			return "hello world";
		}

		@GET("/integer-query-param")
		public Response integerQueryParam(@QueryParameter Integer intQueryParam) {
			return new Response.Builder(204).build();
		}

		@GET("/query-param-custom-name")
		public Response queryParamCustomName(@QueryParameter("local_date") LocalDate localDate) {
			// Echoes back date in ISO yyyy-MM-dd format
			return new Response.Builder(200).body(DateTimeFormatter.ISO_DATE.format(localDate)).build();
		}
	}

	@FunctionalInterface
	public interface MockServerConsumer {
		void useMockServer(@Nonnull MockServer mockServer) throws Exception;
	}

	@Nonnull
	protected void mockServerForResourceClasses(@Nonnull Set<Class<?>> resourceClasses,
																							@Nonnull MockServerConsumer mockServerConsumer) {
		requireNonNull(resourceClasses);
		requireNonNull(mockServerConsumer);

		// Use a mock server that we can send simulated requests to
		MockServer mockServer = new MockServer();

		SokletConfiguration configuration = new SokletConfiguration.Builder(mockServer)
				// Use a resource method resolver that explicitly specifies resource classes
				.resourceMethodResolver(new DefaultResourceMethodResolver(resourceClasses))
				// Quiet logging to keep the console clean
				.logHandler(new LogHandler() {
					@Override
					public void logDebug(@Nonnull String message) {
						// Quiet
					}

					@Override
					public void logError(@Nonnull String message) {
						// Quiet
					}

					@Override
					public void logError(@Nonnull String message, @Nonnull Throwable throwable) {
						// Quiet
					}
				})
				.build();

		try (Soklet soklet = new Soklet(configuration)) {
			soklet.start();
			mockServerConsumer.useMockServer(mockServer);
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
}
