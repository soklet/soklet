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

import com.soklet.annotation.GET;
import com.soklet.annotation.HEAD;
import com.soklet.annotation.Multipart;
import com.soklet.annotation.POST;
import com.soklet.annotation.PathParameter;
import com.soklet.annotation.QueryParameter;
import com.soklet.annotation.RequestBody;
import com.soklet.annotation.RequestHeader;
import com.soklet.exception.IllegalRequestBodyException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.soklet.Utilities.emptyByteArray;
import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class SokletTests {
	@Test
	public void requestHandlingBasics() {
		SokletConfig configuration = configurationForResourceClasses(Set.of(RequestHandlingBasicsResource.class));
		Soklet.runSimulator(configuration, (simulator -> {
			// Response body should be "hello world" as bytes
			RequestResult requestResult = simulator.performRequest(
					Request.withPath(HttpMethod.GET, "/hello-world").build());

			Assertions.assertArrayEquals("hello world".getBytes(StandardCharsets.UTF_8), requestResult.getMarshaledResponse().getBody().get(),
					"Response body doesn't match");

			// Missing query param?  It should be a 400
			requestResult = simulator.performRequest(
					Request.withPath(HttpMethod.GET, "/integer-query-param")
							.build());

			assertEquals(Integer.valueOf(400), requestResult.getMarshaledResponse().getStatusCode());

			// Have the query param?  It's a 204
			requestResult = simulator.performRequest(
					Request.withRawUrl(HttpMethod.GET, "/integer-query-param?intQueryParam=123")
							.build());

			assertEquals(Integer.valueOf(204), requestResult.getMarshaledResponse().getStatusCode());
			Assertions.assertArrayEquals(emptyByteArray(), requestResult.getMarshaledResponse().getBody().orElse(emptyByteArray()),
					"Received a response body but didn't expect one");

			// Have the custom-named query param?  It's a 200 and echoes back the param as a string
			requestResult = simulator.performRequest(
					Request.withRawUrl(HttpMethod.GET, "/query-param-custom-name?local_date=2023-09-30")
							.build());

			assertEquals(Integer.valueOf(200), requestResult.getMarshaledResponse().getStatusCode());
			Assertions.assertArrayEquals("2023-09-30".getBytes(StandardCharsets.UTF_8), requestResult.getMarshaledResponse().getBody().get(),
					"Response body doesn't match");

			// Optional query param, no param provided
			requestResult = simulator.performRequest(
					Request.withPath(HttpMethod.POST, "/optional-query-param")
							.build());

			assertEquals(Integer.valueOf(204), requestResult.getMarshaledResponse().getStatusCode());
			Assertions.assertArrayEquals(emptyByteArray(), requestResult.getMarshaledResponse().getBody().orElse(emptyByteArray()),
					"Received a response body but didn't expect one");

			// Optional query param, param provided
			requestResult = simulator.performRequest(
					Request.withRawUrl(HttpMethod.POST, "/optional-query-param?optionalQueryParam=123.456789")
							.build());

			assertEquals(Integer.valueOf(200), requestResult.getMarshaledResponse().getStatusCode());
			Assertions.assertArrayEquals("123.456789".getBytes(StandardCharsets.UTF_8), requestResult.getMarshaledResponse().getBody().get(),
					"Response body doesn't match");

			// Integer (nonprimitive) request body, integer is required but not provided
			requestResult = simulator.performRequest(
					Request.withPath(HttpMethod.POST, "/echo-integer-request-body")
							.build());

			assertEquals(Integer.valueOf(400), requestResult.getMarshaledResponse().getStatusCode());

			// Integer (nonprimitive) request body, integer is required and provided
			requestResult = simulator.performRequest(
					Request.withPath(HttpMethod.POST, "/echo-integer-request-body")
							.body("123".getBytes(StandardCharsets.UTF_8))
							.build());

			assertEquals(Integer.valueOf(200), requestResult.getMarshaledResponse().getStatusCode());
			Assertions.assertArrayEquals("123".getBytes(StandardCharsets.UTF_8), requestResult.getMarshaledResponse().getBody().get(),
					"Response body doesn't match");

			// Integer (nonprimitive) request body, integer is not required and not provided.
			// This exercises Optional<T> as opposed to @RequestBody(optional=true)
			requestResult = simulator.performRequest(
					Request.withPath(HttpMethod.POST, "/echo-integer-optional-request-body-1")
							.build());

			assertEquals(Integer.valueOf(204), requestResult.getMarshaledResponse().getStatusCode());
			Assertions.assertArrayEquals(null, requestResult.getMarshaledResponse().getBody().orElse(null),
					"Response body doesn't match");

			// Integer (nonprimitive) request body, integer is not required and not provided.
			// This exercises @RequestBody(optional=true) as opposed to Optional<T>
			requestResult = simulator.performRequest(
					Request.withPath(HttpMethod.POST, "/echo-integer-optional-request-body-2")
							.build());

			assertEquals(Integer.valueOf(204), requestResult.getMarshaledResponse().getStatusCode());
			Assertions.assertArrayEquals(null, requestResult.getMarshaledResponse().getBody().orElse(null),
					"Response body doesn't match");

			// Integer (primitive) request body, integer is required and provided
			requestResult = simulator.performRequest(
					Request.withPath(HttpMethod.POST, "/echo-int-request-body")
							.body("123".getBytes(StandardCharsets.UTF_8))
							.build());

			assertEquals(Integer.valueOf(200), requestResult.getMarshaledResponse().getStatusCode());
			Assertions.assertArrayEquals("123".getBytes(StandardCharsets.UTF_8), requestResult.getMarshaledResponse().getBody().get(),
					"Response body doesn't match");

			// Integer (primitive) request body, integer is required but not provided
			requestResult = simulator.performRequest(
					Request.withPath(HttpMethod.POST, "/echo-int-request-body")
							.build());

			assertEquals(Integer.valueOf(400), requestResult.getMarshaledResponse().getStatusCode());

			// Integer (primitive) request body, integer is not required and not provided
			requestResult = simulator.performRequest(
					Request.withPath(HttpMethod.POST, "/echo-int-optional-request-body")
							.build());

			assertEquals(Integer.valueOf(200), requestResult.getMarshaledResponse().getStatusCode());
			Assertions.assertArrayEquals("0".getBytes(StandardCharsets.UTF_8), // 0 is understood to be the default value for uninitialized int
					requestResult.getMarshaledResponse().getBody().get(),
					"Response body doesn't match");
		}));
	}

	@Test
	public void requestResults() {
		SokletConfig configuration = configurationForResourceClasses(Set.of(RequestHandlingBasicsResource.class));
		Soklet.runSimulator(configuration, (simulator -> {
			// Response body should be "hello world" as bytes
			RequestResult requestResult = simulator.performRequest(
					Request.withPath(HttpMethod.GET, "/hello-world").build());

			Response response = requestResult.getResponse().get();
			Object responseBody = response.getBody().get();

			assertEquals("hello world", responseBody, "Response body doesn't match");

			ResourceMethod resourceMethod = requestResult.getResourceMethod().get();
			Method expectedMethod;

			try {
				expectedMethod = RequestHandlingBasicsResource.class.getMethod("helloWorld");
			} catch (NoSuchMethodException e) {
				throw new RuntimeException(e);
			}

			assertEquals(expectedMethod, resourceMethod.getMethod(), "Resource method doesn't match");
		}));
	}

	@Test
	public void testMultipart() {
		SokletConfig configuration = configurationForResourceClasses(Set.of(MultipartResource.class));
		Soklet.runSimulator(configuration, (simulator -> {
			byte[] requestBody;

			try {
				requestBody = Files.readAllBytes(Path.of("src/test/resources/multipart-request-body"));
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}

			RequestResult requestResult = simulator.performRequest(
					Request.withRawUrl(HttpMethod.POST, "/multipart-upload?upload_progress_id=12344")
							.headers(Map.of(
									"Content-Type", Set.of("multipart/form-data; boundary=----WebKitFormBoundary59MIY6fOE42AL48U"),
									"Content-Length", Set.of(String.valueOf(requestBody.length))
							))
							.body(requestBody)
							.build());

			assertEquals(Integer.valueOf(204), requestResult.getMarshaledResponse().getStatusCode());
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
			assertEquals("3x", notReallyAnInt);
			assertEquals(Optional.of("3x"), optionalNotReallyAnInt);
			assertEquals(2, oneAsList.size());
			assertEquals("1", oneAsList.get(0).getDataAsString().get());
			assertEquals("2", oneAsList.get(1).getDataAsString().get());
			assertEquals("3", another.getDataAsString().get());
			assertEquals("3", anotherAsString);
			assertEquals("3", new String(anotherAsBytes, StandardCharsets.UTF_8));
			assertEquals("3", new String(anotherAsOptionalListOfBytes.get().get(0), StandardCharsets.UTF_8));
			assertEquals(3, anotherAsOptionalListOfInteger.get().get(0), 0);
			assertEquals("3", new String(anotherAsOptionalBytes.get(), StandardCharsets.UTF_8));
			assertEquals(3D, anotherAsOptionalDouble.get(), 0);
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
			return Response.withStatusCode(204).build();
		}

		@GET("/query-param-custom-name")
		public Response queryParamCustomName(@Nonnull @QueryParameter(name = "local_date") LocalDate localDate) {
			requireNonNull(localDate);
			// Echoes back date in ISO yyyy-MM-dd format
			return Response.withStatusCode(200).body(DateTimeFormatter.ISO_DATE.format(localDate)).build();
		}

		@POST("/optional-query-param")
		public Response optionalQueryParam(@Nonnull @QueryParameter Optional<BigDecimal> optionalQueryParam) {
			requireNonNull(optionalQueryParam);

			if (optionalQueryParam.isPresent())
				return Response.withStatusCode(200)
						.body(optionalQueryParam.get())
						.build();

			return Response.withStatusCode(204).build();
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
		SokletConfig configuration = configurationForResourceClasses(Set.of(VarargsResource.class));
		Soklet.runSimulator(configuration, (simulator -> {
			RequestResult requestResult = simulator.performRequest(
					Request.withPath(HttpMethod.GET, "/static/js/some/file/example.js")
							.build()
			);

			assertEquals(Integer.valueOf(200), requestResult.getMarshaledResponse().getStatusCode());
			assertEquals("js/some/file/example.js", requestResult.getResponse().get().getBody().get());


			requestResult = simulator.performRequest(
					Request.withPath(HttpMethod.GET, "/123/static/js/some/file/example.js")
							.build()
			);

			assertEquals(Integer.valueOf(200), requestResult.getMarshaledResponse().getStatusCode());
			assertEquals("123-js/some/file/example.js", requestResult.getResponse().get().getBody().get());

			requestResult = simulator.performRequest(
					Request.withPath(HttpMethod.GET, "/static2/js/some/file/example.js")
							.build()
			);

			assertEquals(Integer.valueOf(500), requestResult.getMarshaledResponse().getStatusCode());
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
		SokletConfig configuration = configurationForResourceClasses(Set.of(HttpHeadResource.class));
		Soklet.runSimulator(configuration, (simulator -> {
			// Response headers should be the same as the GET equivalent, but HTTP 204 and no response body
			RequestResult getMethodResult = simulator.performRequest(
					Request.withPath(HttpMethod.GET, "/hello-world").build());

			Assertions.assertArrayEquals("hello world".getBytes(StandardCharsets.UTF_8), getMethodResult.getMarshaledResponse().getBody().get(),
					"Response body doesn't match");

			// Response headers should be the same as the GET equivalent, but HTTP 204 and no response body
			RequestResult headMethodResult = simulator.performRequest(
					Request.withPath(HttpMethod.HEAD, "/hello-world").build());

			assertEquals(Integer.valueOf(200), headMethodResult.getMarshaledResponse().getStatusCode());
			assertEquals(getMethodResult.getMarshaledResponse().getHeaders(), headMethodResult.getMarshaledResponse().getHeaders(),
					"GET and HEAD headers don't match");
			Assertions.assertArrayEquals(emptyByteArray(), headMethodResult.getMarshaledResponse().getBody().orElse(emptyByteArray()),
					"Received a response body but didn't expect one");

			// If you want to handle your own HEAD requests, we still prevent you from trying to send a response body
			RequestResult explicitHeadMethodResult = simulator.performRequest(
					Request.withPath(HttpMethod.HEAD, "/explicit-head-handling").build());

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
	protected SokletConfig configurationForResourceClasses(@Nonnull Set<Class<?>> resourceClasses) {
		return SokletConfig.forSimulatorTesting()
				// Use a resource method resolver that explicitly specifies resource classes
				.resourceMethodResolver(ResourceMethodResolver.withClasses(resourceClasses))
				// Quiet logging to keep the console clean
				.lifecycleInterceptor(new LifecycleInterceptor() {
					@Override
					public void didReceiveLogEvent(@Nonnull LogEvent logEvent) {
						// No-op
					}
				})
				.build();
	}

	@Test
	public void verifyHeaderValidation() {
		Map<String, Set<String>> headers = new LinkedHashMap<>();
		headers.put("X-Test", Set.of("ok\r\nInjected-Header: yes"));

		assertThrows(IllegalArgumentException.class, () ->
				Response.withStatusCode(200)
						.headers(headers)
						.build());

		assertThrows(IllegalArgumentException.class, () ->
				MarshaledResponse.withStatusCode(200)
						.headers(headers)
						.build());
	}

	@Test
	void plusBecomesSpace_percent2BBecomesPlus() {
		Map<String, Set<String>> qp = Utilities.extractQueryParametersFromQueryString(
				"q=a+b%2B", QueryStringFormat.X_WWW_FORM_URLENCODED, StandardCharsets.UTF_8);
		assertEquals(Set.of("a b+"), qp.get("q"));
	}


	@Test
	public void multipleHeaderLinesAreMergedForLocales() {
		Map<String, Set<String>> headers = new LinkedHashMap<>();
		headers.put("Accept-Language", new LinkedHashSet<>(List.of(
				"en-US;q=0.5",
				"fr-CA;q=1.0"
		)));

		Request req = Request.withPath(HttpMethod.GET, "/").headers(headers).build();

		List<String> tags = req.getLocales().stream()
				.map(Locale::toLanguageTag)
				.toList();

		// Both should be present
		Assertions.assertTrue(tags.contains("en-US") && tags.contains("fr-CA"),
				"Accept-Language split across multiple header lines must be merged");
	}

	@Test
	public void multipleHeaderLinesAreMergedForLanguageRanges() {
		Map<String, Set<String>> headers = new LinkedHashMap<>();
		headers.put("Accept-Language", new LinkedHashSet<>(List.of(
				"en-US;q=0.4",
				"fr-CA;q=1.0"
		)));

		Request req = Request.withPath(HttpMethod.GET, "/").headers(headers).build();

		List<String> ranges = req.getLanguageRanges().stream()
				.map(Locale.LanguageRange::getRange)
				.toList();

		Assertions.assertTrue(ranges.contains("en-us") || ranges.contains("en-US"));
		Assertions.assertTrue(ranges.contains("fr-ca") || ranges.contains("fr-CA"));
	}

	static class SimulatorDecodingResource {
		@GET("/widgets/{id}")
		public Response echo(@PathParameter String id) {
			return Response.withStatusCode(200)
					.body(id.getBytes(StandardCharsets.UTF_8))
					.build();
		}
	}

	@Test
	public void encodedSpaceInPath_isDecodedInSimulatorToo() {
		var cfg = SokletConfig.forSimulatorTesting()
				.resourceMethodResolver(ResourceMethodResolver.withClasses(Set.of(SimulatorDecodingResource.class)))
				.build();

		Soklet.runSimulator(cfg, sim -> {
			var res = sim.performRequest(Request.withRawUrl(HttpMethod.GET, "/widgets/ab%20c").build());
			assertEquals(200, res.getMarshaledResponse().getStatusCode());
			assertEquals("ab c", new String(res.getMarshaledResponse().getBody().orElse(new byte[0]), StandardCharsets.UTF_8));

			res = sim.performRequest(Request.withPath(HttpMethod.GET, "/widgets/ab c").build());
			assertEquals(200, res.getMarshaledResponse().getStatusCode());
			assertEquals("ab c", new String(res.getMarshaledResponse().getBody().orElse(new byte[0]), StandardCharsets.UTF_8));
		});
	}

	@Test
	public void duplicatePlaceholderNames_areRejected() {
		assertThrows(IllegalArgumentException.class, () -> {
			ResourcePathDeclaration.withPath("/a/{id}/b/{id}");
		}, "Duplicate placeholder names should be illegal");
	}


	@Test
	public void emptyBoundaryThrowsException() {
		// Should throw an exception due to empty boundary
		assertThrows(IllegalRequestBodyException.class, () -> {
			// The boundary value is present but empty: "boundary="
			String requestBody = "--\r\n" +
					"Content-Disposition: form-data; name=\"field1\"\r\n\r\n" +
					"value1\r\n" +
					"--\r\n";

			Request request = Request.withPath(HttpMethod.POST, "/upload")
					.body(requestBody.getBytes(StandardCharsets.UTF_8))
					.headers(Map.of("Content-Type", Set.of("multipart/form-data; boundary=")))
					.build();

			// Trigger lazy-load of multipart parser
			request.getMultipartFields();
		}, "Empty multipart boundary should cause an exception");
	}

	@Test
	public void missingBoundaryThrowsException() {
		// Should throw an exception due to missing boundary
		assertThrows(IllegalRequestBodyException.class, () -> {
			// Create a request with no boundary parameter at all
			String requestBody = "--ABC\r\n" +
					"Content-Disposition: form-data; name=\"field1\"\r\n\r\n" +
					"value1\r\n" +
					"--ABC--\r\n";

			Request request = Request.withPath(HttpMethod.POST, "/upload")
					.body(requestBody.getBytes(StandardCharsets.UTF_8))
					.headers(Map.of("Content-Type", Set.of("multipart/form-data"))) // No boundary at all
					.build();

			// Trigger lazy-load of multipart parser
			request.getMultipartFields();

		}, "Missing multipart boundary should cause an exception");
	}

	@Test
	public void whitespaceBoundaryIsInvalid() {
		// Should throw an exception due to whitespace boundary
		assertThrows(IllegalRequestBodyException.class, () -> {
			// Create a request with whitespace boundary
			String requestBody = "--   \r\n" +
					"Content-Disposition: form-data; name=\"field1\"\r\n\r\n" +
					"value1\r\n" +
					"--   --\r\n";

			Request request = Request.withPath(HttpMethod.POST, "/upload")
					.body(requestBody.getBytes(StandardCharsets.UTF_8))
					.headers(Map.of("Content-Type", Set.of("multipart/form-data; boundary=   ")))
					.build();

			// Trigger lazy-load of multipart parser
			request.getMultipartFields();
		}, "Whitespace multipart boundary should cause an exception");
	}

	@Test
	public void validBoundarySucceeds() {
		// Arrange: Create a proper multipart request with valid boundary
		String boundary = "----WebKitFormBoundary7MA4YWxkTrZu0gW";
		String requestBody = "--" + boundary + "\r\n" +
				"Content-Disposition: form-data; name=\"field1\"\r\n\r\n" +
				"value1\r\n" +
				"--" + boundary + "--\r\n";

		Request request = Request.withPath(HttpMethod.POST, "/upload")
				.body(requestBody.getBytes(StandardCharsets.UTF_8))
				.headers(Map.of("Content-Type", Set.of("multipart/form-data; boundary=" + boundary)))
				.build();

		ResourceMethodResolver resolver = ResourceMethodResolver.withClasses(
				Set.of(MultipartResource2.class)
		);

		SokletConfig config = SokletConfig.forSimulatorTesting()
				.resourceMethodResolver(resolver)
				.build();

		// Act & Assert: Valid boundary should work correctly
		assertDoesNotThrow(() -> {
			Soklet.runSimulator(config, simulator -> {
				RequestResult requestResult = simulator.performRequest(request);
				assertEquals(200, requestResult.getMarshaledResponse().getStatusCode(), "Valid multipart request should succeed");
			});
		});
	}

	@Test
	public void quotedBoundaryIsHandled() {
		// Arrange: According to RFC 2046, boundary values can be quoted
		String boundary = "----WebKitFormBoundary";
		String requestBody = "--" + boundary + "\r\n" +
				"Content-Disposition: form-data; name=\"test\"\r\n\r\n" +
				"data\r\n" +
				"--" + boundary + "--\r\n";

		// Boundary is quoted in Content-Type header
		Request request = Request.withPath(HttpMethod.POST, "/upload")
				.body(requestBody.getBytes(StandardCharsets.UTF_8))
				.headers(Map.of("Content-Type", Set.of("multipart/form-data; boundary=\"" + boundary + "\"")))
				.build();

		ResourceMethodResolver resolver = ResourceMethodResolver.withClasses(
				Set.of(MultipartResource2.class)
		);

		SokletConfig config = SokletConfig.forSimulatorTesting()
				.resourceMethodResolver(resolver)
				.build();

		// Act & Assert: Quoted boundary should be handled correctly
		assertDoesNotThrow(() -> {
			Soklet.runSimulator(config, simulator -> {
				RequestResult requestResult = simulator.performRequest(request);
				assertEquals(200, requestResult.getMarshaledResponse().getStatusCode(), "Quoted boundary should be handled correctly");
			});
		});
	}

	// Test resource that accepts multipart uploads
	public static class MultipartResource2 {
		@POST("/upload")
		public String upload(@Multipart(name = "field1", optional = true) MultipartField field) {
			return "ok";
		}
	}

	@NotThreadSafe
	public static class DuplicateValueResource {
		@GET("/query")
		public void query(@QueryParameter String singleOnly) {
			// Nothing to do
		}

		@GET("/header")
		public void header(@RequestHeader String singleOnly) {
			// Nothing to do
		}
	}

	@Test
	public void duplicateValueTests() {
		SokletConfig config = SokletConfig.forSimulatorTesting()
				.resourceMethodResolver(ResourceMethodResolver.withClasses(Set.of(DuplicateValueResource.class)))
				.lifecycleInterceptor(new LifecycleInterceptor() {
					@Override
					public void didReceiveLogEvent(@Nonnull LogEvent logEvent) {
						// Quiet logging
					}
				})
				.build();

		Soklet.runSimulator(config, simulator -> {
			Request queryRequest = Request.withRawUrl(HttpMethod.GET, "/query?singleOnly=one&singleOnly=two")
					.build();

			RequestResult queryRequestResult = simulator.performRequest(queryRequest);
			Assertions.assertEquals(400, queryRequestResult.getMarshaledResponse().getStatusCode(), "Unexpected status code for query test");

			Request headerRequest = Request.withPath(HttpMethod.GET, "/header")
					.headers(Map.of("singleOnly", Set.of("one", "two")))
					.build();

			RequestResult headerRequestResult = simulator.performRequest(headerRequest);
			Assertions.assertEquals(400, headerRequestResult.getMarshaledResponse().getStatusCode(), "Unexpected status code for header test");
		});
	}
}