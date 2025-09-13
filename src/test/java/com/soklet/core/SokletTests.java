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

import com.soklet.Soklet;
import com.soklet.SokletConfiguration;
import com.soklet.annotation.GET;
import com.soklet.annotation.HEAD;
import com.soklet.annotation.Multipart;
import com.soklet.annotation.POST;
import com.soklet.annotation.PathParameter;
import com.soklet.annotation.QueryParameter;
import com.soklet.annotation.RequestBody;
import com.soklet.core.impl.DefaultResourceMethodResolver;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
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

import static com.soklet.core.Utilities.emptyByteArray;
import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class SokletTests {
	@Test
	public void requestHandlingBasics() {
		SokletConfiguration configuration = configurationForResourceClasses(Set.of(RequestHandlingBasicsResource.class));
		Soklet.runSimulator(configuration, (simulator -> {
			// Response body should be "hello world" as bytes
			RequestResult requestResult = simulator.performRequest(
					new Request.Builder(HttpMethod.GET, "/hello-world").build());

			Assertions.assertArrayEquals("hello world".getBytes(StandardCharsets.UTF_8), requestResult.getMarshaledResponse().getBody().get(),
					"Response body doesn't match");

			// Missing query param?  It should be a 400
			requestResult = simulator.performRequest(
					new Request.Builder(HttpMethod.GET, "/integer-query-param")
							.build());

			Assertions.assertEquals(Integer.valueOf(400), requestResult.getMarshaledResponse().getStatusCode());

			// Have the query param?  It's a 204
			requestResult = simulator.performRequest(
					new Request.Builder(HttpMethod.GET, "/integer-query-param?intQueryParam=123")
							.build());

			Assertions.assertEquals(Integer.valueOf(204), requestResult.getMarshaledResponse().getStatusCode());
			Assertions.assertArrayEquals(emptyByteArray(), requestResult.getMarshaledResponse().getBody().orElse(emptyByteArray()),
					"Received a response body but didn't expect one");

			// Have the custom-named query param?  It's a 200 and echoes back the param as a string
			requestResult = simulator.performRequest(
					new Request.Builder(HttpMethod.GET, "/query-param-custom-name?local_date=2023-09-30")
							.build());

			Assertions.assertEquals(Integer.valueOf(200), requestResult.getMarshaledResponse().getStatusCode());
			Assertions.assertArrayEquals("2023-09-30".getBytes(StandardCharsets.UTF_8), requestResult.getMarshaledResponse().getBody().get(),
					"Response body doesn't match");

			// Optional query param, no param provided
			requestResult = simulator.performRequest(
					new Request.Builder(HttpMethod.POST, "/optional-query-param")
							.build());

			Assertions.assertEquals(Integer.valueOf(204), requestResult.getMarshaledResponse().getStatusCode());
			Assertions.assertArrayEquals(emptyByteArray(), requestResult.getMarshaledResponse().getBody().orElse(emptyByteArray()),
					"Received a response body but didn't expect one");

			// Optional query param, param provided
			requestResult = simulator.performRequest(
					new Request.Builder(HttpMethod.POST, "/optional-query-param?optionalQueryParam=123.456789")
							.build());

			Assertions.assertEquals(Integer.valueOf(200), requestResult.getMarshaledResponse().getStatusCode());
			Assertions.assertArrayEquals("123.456789".getBytes(StandardCharsets.UTF_8), requestResult.getMarshaledResponse().getBody().get(),
					"Response body doesn't match");

			// Integer (nonprimitive) request body, integer is required but not provided
			requestResult = simulator.performRequest(
					new Request.Builder(HttpMethod.POST, "/echo-integer-request-body")
							.build());

			Assertions.assertEquals(Integer.valueOf(400), requestResult.getMarshaledResponse().getStatusCode());

			// Integer (nonprimitive) request body, integer is required and provided
			requestResult = simulator.performRequest(
					new Request.Builder(HttpMethod.POST, "/echo-integer-request-body")
							.body("123".getBytes(StandardCharsets.UTF_8))
							.build());

			Assertions.assertEquals(Integer.valueOf(200), requestResult.getMarshaledResponse().getStatusCode());
			Assertions.assertArrayEquals("123".getBytes(StandardCharsets.UTF_8), requestResult.getMarshaledResponse().getBody().get(),
					"Response body doesn't match");

			// Integer (nonprimitive) request body, integer is not required and not provided.
			// This exercises Optional<T> as opposed to @RequestBody(optional=true)
			requestResult = simulator.performRequest(
					new Request.Builder(HttpMethod.POST, "/echo-integer-optional-request-body-1")
							.build());

			Assertions.assertEquals(Integer.valueOf(204), requestResult.getMarshaledResponse().getStatusCode());
			Assertions.assertArrayEquals(null, requestResult.getMarshaledResponse().getBody().orElse(null),
					"Response body doesn't match");

			// Integer (nonprimitive) request body, integer is not required and not provided.
			// This exercises @RequestBody(optional=true) as opposed to Optional<T>
			requestResult = simulator.performRequest(
					new Request.Builder(HttpMethod.POST, "/echo-integer-optional-request-body-2")
							.build());

			Assertions.assertEquals(Integer.valueOf(204), requestResult.getMarshaledResponse().getStatusCode());
			Assertions.assertArrayEquals(null, requestResult.getMarshaledResponse().getBody().orElse(null),
					"Response body doesn't match");

			// Integer (primitive) request body, integer is required and provided
			requestResult = simulator.performRequest(
					new Request.Builder(HttpMethod.POST, "/echo-int-request-body")
							.body("123".getBytes(StandardCharsets.UTF_8))
							.build());

			Assertions.assertEquals(Integer.valueOf(200), requestResult.getMarshaledResponse().getStatusCode());
			Assertions.assertArrayEquals("123".getBytes(StandardCharsets.UTF_8), requestResult.getMarshaledResponse().getBody().get(),
					"Response body doesn't match");

			// Integer (primitive) request body, integer is required but not provided
			requestResult = simulator.performRequest(
					new Request.Builder(HttpMethod.POST, "/echo-int-request-body")
							.build());

			Assertions.assertEquals(Integer.valueOf(400), requestResult.getMarshaledResponse().getStatusCode());

			// Integer (primitive) request body, integer is not required and not provided
			requestResult = simulator.performRequest(
					new Request.Builder(HttpMethod.POST, "/echo-int-optional-request-body")
							.build());

			Assertions.assertEquals(Integer.valueOf(200), requestResult.getMarshaledResponse().getStatusCode());
			Assertions.assertArrayEquals("0".getBytes(StandardCharsets.UTF_8), // 0 is understood to be the default value for uninitialized int
					requestResult.getMarshaledResponse().getBody().get(),
					"Response body doesn't match");
		}));
	}

	@Test
	public void requestResults() {
		SokletConfiguration configuration = configurationForResourceClasses(Set.of(RequestHandlingBasicsResource.class));
		Soklet.runSimulator(configuration, (simulator -> {
			// Response body should be "hello world" as bytes
			RequestResult requestResult = simulator.performRequest(
					new Request.Builder(HttpMethod.GET, "/hello-world").build());

			Response response = requestResult.getResponse().get();
			Object responseBody = response.getBody().get();

			Assertions.assertEquals("hello world", responseBody, "Response body doesn't match");

			ResourceMethod resourceMethod = requestResult.getResourceMethod().get();
			Method expectedMethod;

			try {
				expectedMethod = RequestHandlingBasicsResource.class.getMethod("helloWorld");
			} catch (NoSuchMethodException e) {
				throw new RuntimeException(e);
			}

			Assertions.assertEquals(expectedMethod, resourceMethod.getMethod(), "Resource method doesn't match");
		}));
	}

	@Test
	public void testMultipart() {
		SokletConfiguration configuration = configurationForResourceClasses(Set.of(MultipartResource.class));
		Soklet.runSimulator(configuration, (simulator -> {
			byte[] requestBody;

			try {
				requestBody = Files.readAllBytes(Path.of("src/test/resources/multipart-request-body"));
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}

			RequestResult requestResult = simulator.performRequest(
					new Request.Builder(HttpMethod.POST, "/multipart-upload?upload_progress_id=12344")
							.headers(Map.of(
									"Content-Type", Set.of("multipart/form-data; boundary=----WebKitFormBoundary59MIY6fOE42AL48U"),
									"Content-Length", Set.of(String.valueOf(requestBody.length))
							))
							.body(requestBody)
							.build());

			Assertions.assertEquals(Integer.valueOf(204), requestResult.getMarshaledResponse().getStatusCode());
		}));
	}

	@ThreadSafe
	public static class MultipartResource {
		@POST("/multipart-upload")
		public void multipartUpload(Request request,
																@Multipart(name = "not-really-int") String notReallyAnInt,
																@Multipart(name = "not-really-int") Optional<String> optionalNotReallyAnInt,
																@Multipart(name = "one") List<MultipartField> oneAsList,
																MultipartField another,
																@Multipart(name = "another") Optional<List<byte[]>> anotherAsOptionalListOfBytes,
																@Multipart(name = "another") Optional<List<Integer>> anotherAsOptionalListOfInteger,
																@Multipart(name = "another") String anotherAsString,
																@Multipart(name = "another") byte[] anotherAsBytes,
																@Multipart(name = "another") Optional<Double> anotherAsOptionalDouble,
																@Multipart(name = "another") Optional<byte[]> anotherAsOptionalBytes) {
			Assertions.assertEquals("3x", notReallyAnInt);
			Assertions.assertEquals(Optional.of("3x"), optionalNotReallyAnInt);
			Assertions.assertEquals(2, oneAsList.size());
			Assertions.assertEquals("1", oneAsList.get(0).getDataAsString().get());
			Assertions.assertEquals("2", oneAsList.get(1).getDataAsString().get());
			Assertions.assertEquals("3", another.getDataAsString().get());
			Assertions.assertEquals("3", anotherAsString);
			Assertions.assertEquals("3", new String(anotherAsBytes, StandardCharsets.UTF_8));
			Assertions.assertEquals("3", new String(anotherAsOptionalListOfBytes.get().get(0), StandardCharsets.UTF_8));
			Assertions.assertEquals(3, anotherAsOptionalListOfInteger.get().get(0), 0);
			Assertions.assertEquals("3", new String(anotherAsOptionalBytes.get(), StandardCharsets.UTF_8));
			Assertions.assertEquals(3D, anotherAsOptionalDouble.get(), 0);
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
		public Response queryParamCustomName(@Nonnull @QueryParameter(name = "local_date") LocalDate localDate) {
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

		@POST("/echo-integer-request-body")
		public Integer echoIntegerRequestBody(@Nonnull @RequestBody Integer requestBody) {
			requireNonNull(requestBody);
			return requestBody;
		}

		@POST("/echo-integer-optional-request-body-1")
		public void echoIntegerOptionalRequestBody1(@Nonnull @RequestBody Optional<Integer> requestBody) {
			requireNonNull(requestBody);
		}

		@POST("/echo-integer-optional-request-body-2")
		public void echoIntegerOptionalRequestBody2(@Nullable @RequestBody(optional = true) Integer requestBody) {
			if (requestBody != null)
				throw new IllegalArgumentException("Request body should have been null");
		}

		@POST("/echo-int-request-body")
		public Integer echoIntRequestBody(@RequestBody int requestBody) {
			requireNonNull(requestBody);
			return requestBody;
		}

		@POST("/echo-int-optional-request-body")
		public int echoIntOptionalRequestBody(@RequestBody(optional = true) int requestBody) {
			return requestBody;
		}
	}

	@Test
	public void testVarargs() {
		SokletConfiguration configuration = configurationForResourceClasses(Set.of(VarargsResource.class));
		Soklet.runSimulator(configuration, (simulator -> {
			RequestResult requestResult = simulator.performRequest(
					new Request.Builder(HttpMethod.GET, "/static/js/some/file/example.js")
							.build()
			);

			Assertions.assertEquals(Integer.valueOf(200), requestResult.getMarshaledResponse().getStatusCode());
			Assertions.assertEquals("js/some/file/example.js", requestResult.getResponse().get().getBody().get());


			requestResult = simulator.performRequest(
					new Request.Builder(HttpMethod.GET, "/123/static/js/some/file/example.js")
							.build()
			);

			Assertions.assertEquals(Integer.valueOf(200), requestResult.getMarshaledResponse().getStatusCode());
			Assertions.assertEquals("123-js/some/file/example.js", requestResult.getResponse().get().getBody().get());

			requestResult = simulator.performRequest(
					new Request.Builder(HttpMethod.GET, "/static2/js/some/file/example.js")
							.build()
			);

			Assertions.assertEquals(Integer.valueOf(500), requestResult.getMarshaledResponse().getStatusCode());
		}));
	}

	@ThreadSafe
	public static class VarargsResource {
		@GET("/static/{path*}")
		public String basicVarargs(@PathParameter String path) {
			return path;
		}

		@GET("/{something}/static/{path*}")
		public String complexVarargs(@PathParameter Integer something,
																 @PathParameter String path) {
			return something + "-" + path;
		}

		@GET("/static2/{anotherPath*}")
		public Integer illegalVarargsType(@PathParameter Integer anotherPath /* only String is supported */) {
			return anotherPath;
		}
	}

	@Test
	public void httpHead() {
		SokletConfiguration configuration = configurationForResourceClasses(Set.of(HttpHeadResource.class));
		Soklet.runSimulator(configuration, (simulator -> {
			// Response headers should be the same as the GET equivalent, but HTTP 204 and no response body
			RequestResult getMethodResult = simulator.performRequest(
					new Request.Builder(HttpMethod.GET, "/hello-world").build());

			Assertions.assertArrayEquals("hello world".getBytes(StandardCharsets.UTF_8), getMethodResult.getMarshaledResponse().getBody().get(),
					"Response body doesn't match");

			// Response headers should be the same as the GET equivalent, but HTTP 204 and no response body
			RequestResult headMethodResult = simulator.performRequest(
					new Request.Builder(HttpMethod.HEAD, "/hello-world").build());

			Assertions.assertEquals(Integer.valueOf(200), headMethodResult.getMarshaledResponse().getStatusCode());
			Assertions.assertEquals(getMethodResult.getMarshaledResponse().getHeaders(), headMethodResult.getMarshaledResponse().getHeaders(),
					"GET and HEAD headers don't match");
			Assertions.assertArrayEquals(emptyByteArray(), headMethodResult.getMarshaledResponse().getBody().orElse(emptyByteArray()),
					"Received a response body but didn't expect one");

			// If you want to handle your own HEAD requests, we still prevent you from trying to send a response body
			RequestResult explicitHeadMethodResult = simulator.performRequest(
					new Request.Builder(HttpMethod.HEAD, "/explicit-head-handling").build());

			Assertions.assertArrayEquals(emptyByteArray(), explicitHeadMethodResult.getMarshaledResponse().getBody().orElse(emptyByteArray()),
					"Received a response body but didn't expect one");
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
			return "violating spec by trying to return a HEAD response body";
		}
	}

	@Nonnull
	protected SokletConfiguration configurationForResourceClasses(@Nonnull Set<Class<?>> resourceClasses) {
		return SokletConfiguration.forTesting()
				// Use a resource method resolver that explicitly specifies resource classes
				.resourceMethodResolver(new DefaultResourceMethodResolver(resourceClasses))
				// Quiet logging to keep the console clean
				.lifecycleInterceptor(new LifecycleInterceptor() {
					@Override
					public void didReceiveLogEvent(@Nonnull LogEvent logEvent) {
						// No-op
					}
				})
				.build();
	}
}