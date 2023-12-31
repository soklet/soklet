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

import com.soklet.Soklet;
import com.soklet.SokletConfiguration;
import com.soklet.annotation.GET;
import com.soklet.annotation.HEAD;
import com.soklet.annotation.Multipart;
import com.soklet.annotation.POST;
import com.soklet.annotation.QueryParameter;
import com.soklet.core.impl.DefaultResourceMethodResolver;
import com.soklet.core.impl.MockServer;
import org.junit.Assert;
import org.junit.Test;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import static com.soklet.core.Utilities.emptyByteArray;
import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class SokletTests {
	@Test
	public void requestHandlingBasics() {
		// Use a mock server that we can send simulated requests to
		mockServerForResourceClasses(Set.of(RequestHandlingBasicsResource.class), (mockServer -> {
			// MicrohttpResponse body should be "hello world" as bytes
			MarshaledResponse marshaledResponse = mockServer.simulateRequest(
					new Request.Builder(HttpMethod.GET, "/hello-world").build());

			Assert.assertArrayEquals("MicrohttpResponse body doesn't match",
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
			Assert.assertArrayEquals("Received a response body but didn't expect one",
					emptyByteArray(),
					marshaledResponse.getBody().orElse(emptyByteArray()));

			// Have the custom-named query param?  It's a 200 and echoes back the param as a string
			marshaledResponse = mockServer.simulateRequest(
					new Request.Builder(HttpMethod.GET, "/query-param-custom-name?local_date=2023-09-30")
							.build());

			Assert.assertEquals(200L, (long) marshaledResponse.getStatusCode());
			Assert.assertArrayEquals("MicrohttpResponse body doesn't match",
					"2023-09-30".getBytes(StandardCharsets.UTF_8),
					marshaledResponse.getBody().get());

			// Optional query param, no param provided
			marshaledResponse = mockServer.simulateRequest(
					new Request.Builder(HttpMethod.POST, "/optional-query-param")
							.build());

			Assert.assertEquals(204L, (long) marshaledResponse.getStatusCode());
			Assert.assertArrayEquals("Received a response body but didn't expect one",
					emptyByteArray(),
					marshaledResponse.getBody().orElse(emptyByteArray()));

			// Optional query param, param provided
			marshaledResponse = mockServer.simulateRequest(
					new Request.Builder(HttpMethod.POST, "/optional-query-param?optionalQueryParam=123.456789")
							.build());

			Assert.assertEquals(200L, (long) marshaledResponse.getStatusCode());
			Assert.assertArrayEquals("MicrohttpResponse body doesn't match",
					"123.456789".getBytes(StandardCharsets.UTF_8),
					marshaledResponse.getBody().get());
		}));
	}

	@Test
	public void testMultipart() {
		// Use a mock server that we can send simulated requests to
		mockServerForResourceClasses(Set.of(MultipartResource.class), (mockServer -> {
			byte[] requestBody;

			try {
				requestBody = Files.readAllBytes(Path.of("src/test/resources/multipart-request-body"));
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}

			MarshaledResponse marshaledResponse = mockServer.simulateRequest(
					new Request.Builder(HttpMethod.POST, "/multipart-upload?upload_progress_id=12344")
							.headers(Map.of(
									"Content-Type", Set.of("multipart/form-data; boundary=----WebKitFormBoundary59MIY6fOE42AL48U"),
									"Content-Length", Set.of(String.valueOf(requestBody.length))
							))
							.body(requestBody)
							.build());

			Assert.assertEquals(204L, (long) marshaledResponse.getStatusCode());
		}));
	}

	@ThreadSafe
	public static class MultipartResource {
		@POST("/multipart-upload")
		public void multipartUpload(Request request,
																@Multipart("not-really-int") String notReallyAnInt,
																@Multipart("not-really-int") Optional<String> optionalNotReallyAnInt,
																@Multipart("one") List<MultipartField> oneAsList,
																MultipartField another,
																@Multipart("another") Optional<List<byte[]>> anotherAsOptionalListOfBytes,
																@Multipart("another") Optional<List<Integer>> anotherAsOptionalListOfInteger,
																@Multipart("another") String anotherAsString,
																@Multipart("another") byte[] anotherAsBytes,
																@Multipart("another") Optional<Double> anotherAsOptionalDouble,
																@Multipart("another") Optional<byte[]> anotherAsOptionalBytes) {
			Assert.assertEquals("3x", notReallyAnInt);
			Assert.assertEquals(Optional.of("3x"), optionalNotReallyAnInt);
			Assert.assertEquals(2, oneAsList.size());
			Assert.assertEquals("1", oneAsList.get(0).getDataAsString().get());
			Assert.assertEquals("2", oneAsList.get(1).getDataAsString().get());
			Assert.assertEquals("3", another.getDataAsString().get());
			Assert.assertEquals("3", anotherAsString);
			Assert.assertEquals("3", new String(anotherAsBytes, StandardCharsets.UTF_8));
			Assert.assertEquals("3", new String(anotherAsOptionalListOfBytes.get().get(0), StandardCharsets.UTF_8));
			Assert.assertEquals(3, anotherAsOptionalListOfInteger.get().get(0), 0);
			Assert.assertEquals("3", new String(anotherAsOptionalBytes.get(), StandardCharsets.UTF_8));
			Assert.assertEquals(3D, anotherAsOptionalDouble.get(), 0);
		}
	}

	@ThreadSafe
	public static class RequestHandlingBasicsResource {
		@GET("/hello-world")
		public String helloWorld() {
			return "hello world";
		}

		@GET("/integer-query-param")
		public Response integerQueryParam(@Nonnull @QueryParameter Integer intQueryParam) {
			requireNonNull(intQueryParam);
			return new Response.Builder(204).build();
		}

		@GET("/query-param-custom-name")
		public Response queryParamCustomName(@Nonnull @QueryParameter("local_date") LocalDate localDate) {
			requireNonNull(localDate);
			// Echoes back date in ISO yyyy-MM-dd format
			return new Response.Builder(200).body(DateTimeFormatter.ISO_DATE.format(localDate)).build();
		}

		@POST("/optional-query-param")
		public Response optionalQueryParam(@Nonnull @QueryParameter Optional<BigDecimal> optionalQueryParam) {
			requireNonNull(optionalQueryParam);

			if (optionalQueryParam.isPresent())
				return new Response.Builder(200)
						.body(optionalQueryParam.get())
						.build();

			return new Response.Builder(204).build();
		}
	}

	@Test
	public void httpHead() {
		// Use a mock server that we can send simulated requests to
		mockServerForResourceClasses(Set.of(HttpHeadResource.class), (mockServer -> {
			// MicrohttpResponse headers should be the same as the GET equivalent, but HTTP 204 and no response body
			MarshaledResponse getMarshaledResponse = mockServer.simulateRequest(
					new Request.Builder(HttpMethod.GET, "/hello-world").build());

			Assert.assertArrayEquals("MicrohttpResponse body doesn't match",
					"hello world".getBytes(StandardCharsets.UTF_8),
					getMarshaledResponse.getBody().get());

			// MicrohttpResponse headers should be the same as the GET equivalent, but HTTP 204 and no response body
			MarshaledResponse headMarshaledResponse = mockServer.simulateRequest(
					new Request.Builder(HttpMethod.HEAD, "/hello-world").build());

			Assert.assertEquals(200, (long) headMarshaledResponse.getStatusCode());
			Assert.assertEquals("GET and HEAD headers don't match",
					getMarshaledResponse.getHeaders(), headMarshaledResponse.getHeaders());
			Assert.assertArrayEquals("Received a response body but didn't expect one",
					emptyByteArray(),
					headMarshaledResponse.getBody().orElse(emptyByteArray()));

			// If you want to handle your own HEAD requests...you can do whatever you want,
			// including sending a body
			MarshaledResponse explicitHeadMarshaledResponse = mockServer.simulateRequest(
					new Request.Builder(HttpMethod.HEAD, "/explicit-head-handling").build());

			Assert.assertArrayEquals("If you want to handle HEAD requests explicitly, we don't stop you",
					"violating spec and returning a HEAD response body because i can".getBytes(StandardCharsets.UTF_8),
					explicitHeadMarshaledResponse.getBody().get());
		}));
	}

	@ThreadSafe
	public static class HttpHeadResource {
		@GET("/hello-world")
		public String helloWorld() {
			return "hello world";
		}

		@HEAD("/explicit-head-handling")
		public Object explicitHeadHandling() {
			return "violating spec and returning a HEAD response body because i can";
		}
	}

	@Nonnull
	protected void mockServerForResourceClasses(@Nonnull Set<Class<?>> resourceClasses,
																							@Nonnull Consumer<MockServer> mockServerConsumer) {
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
						System.err.println(message);
					}

					@Override
					public void logError(@Nonnull String message,
															 @Nonnull Throwable throwable) {
						System.err.println(message);
						throwable.printStackTrace();
					}
				})
				.build();

		try (Soklet soklet = new Soklet(configuration)) {
			soklet.start();
			mockServerConsumer.accept(mockServer);
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
}
