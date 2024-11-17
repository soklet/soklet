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
import com.soklet.annotation.RequestBody;
import com.soklet.core.impl.AllOriginsCorsAuthorizer;
import com.soklet.core.impl.DefaultInstanceProvider;
import com.soklet.core.impl.DefaultResourceMethodResolver;
import com.soklet.core.impl.DefaultServer;
import com.soklet.core.impl.DefaultServerSentEventServer;
import org.junit.Assert;
import org.junit.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.SynchronousQueue;

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
			MarshaledResponse marshaledResponse = simulator.performRequest(
					new Request.Builder(HttpMethod.GET, "/hello-world").build());

			Assert.assertArrayEquals("Response body doesn't match",
					"hello world".getBytes(StandardCharsets.UTF_8),
					marshaledResponse.getBody().get());

			// Missing query param?  It should be a 400
			marshaledResponse = simulator.performRequest(
					new Request.Builder(HttpMethod.GET, "/integer-query-param")
							.build());

			Assert.assertEquals(Integer.valueOf(400), marshaledResponse.getStatusCode());

			// Have the query param?  It's a 204
			marshaledResponse = simulator.performRequest(
					new Request.Builder(HttpMethod.GET, "/integer-query-param?intQueryParam=123")
							.build());

			Assert.assertEquals(Integer.valueOf(204), marshaledResponse.getStatusCode());
			Assert.assertArrayEquals("Received a response body but didn't expect one",
					emptyByteArray(),
					marshaledResponse.getBody().orElse(emptyByteArray()));

			// Have the custom-named query param?  It's a 200 and echoes back the param as a string
			marshaledResponse = simulator.performRequest(
					new Request.Builder(HttpMethod.GET, "/query-param-custom-name?local_date=2023-09-30")
							.build());

			Assert.assertEquals(Integer.valueOf(200), marshaledResponse.getStatusCode());
			Assert.assertArrayEquals("Response body doesn't match",
					"2023-09-30".getBytes(StandardCharsets.UTF_8),
					marshaledResponse.getBody().get());

			// Optional query param, no param provided
			marshaledResponse = simulator.performRequest(
					new Request.Builder(HttpMethod.POST, "/optional-query-param")
							.build());

			Assert.assertEquals(Integer.valueOf(204), marshaledResponse.getStatusCode());
			Assert.assertArrayEquals("Received a response body but didn't expect one",
					emptyByteArray(),
					marshaledResponse.getBody().orElse(emptyByteArray()));

			// Optional query param, param provided
			marshaledResponse = simulator.performRequest(
					new Request.Builder(HttpMethod.POST, "/optional-query-param?optionalQueryParam=123.456789")
							.build());

			Assert.assertEquals(Integer.valueOf(200), marshaledResponse.getStatusCode());
			Assert.assertArrayEquals("Response body doesn't match",
					"123.456789".getBytes(StandardCharsets.UTF_8),
					marshaledResponse.getBody().get());

			// Integer (nonprimitive) request body, integer is required but not provided
			marshaledResponse = simulator.performRequest(
					new Request.Builder(HttpMethod.POST, "/echo-integer-request-body")
							.build());

			Assert.assertEquals(Integer.valueOf(400), marshaledResponse.getStatusCode());

			// Integer (nonprimitive) request body, integer is required and provided
			marshaledResponse = simulator.performRequest(
					new Request.Builder(HttpMethod.POST, "/echo-integer-request-body")
							.body("123".getBytes(StandardCharsets.UTF_8))
							.build());

			Assert.assertEquals(Integer.valueOf(200), marshaledResponse.getStatusCode());
			Assert.assertArrayEquals("Response body doesn't match",
					"123".getBytes(StandardCharsets.UTF_8),
					marshaledResponse.getBody().get());

			// Integer (nonprimitive) request body, integer is not required and not provided.
			// This exercises Optional<T> as opposed to @RequestBody(optional=true)
			marshaledResponse = simulator.performRequest(
					new Request.Builder(HttpMethod.POST, "/echo-integer-optional-request-body-1")
							.build());

			Assert.assertEquals(Integer.valueOf(204), marshaledResponse.getStatusCode());
			Assert.assertArrayEquals("Response body doesn't match",
					null, marshaledResponse.getBody().orElse(null));

			// Integer (nonprimitive) request body, integer is not required and not provided.
			// This exercises @RequestBody(optional=true) as opposed to Optional<T>
			marshaledResponse = simulator.performRequest(
					new Request.Builder(HttpMethod.POST, "/echo-integer-optional-request-body-2")
							.build());

			Assert.assertEquals(Integer.valueOf(204), marshaledResponse.getStatusCode());
			Assert.assertArrayEquals("Response body doesn't match",
					null, marshaledResponse.getBody().orElse(null));

			// Integer (primitive) request body, integer is required and provided
			marshaledResponse = simulator.performRequest(
					new Request.Builder(HttpMethod.POST, "/echo-int-request-body")
							.body("123".getBytes(StandardCharsets.UTF_8))
							.build());

			Assert.assertEquals(Integer.valueOf(200), marshaledResponse.getStatusCode());
			Assert.assertArrayEquals("Response body doesn't match",
					"123".getBytes(StandardCharsets.UTF_8),
					marshaledResponse.getBody().get());

			// Integer (primitive) request body, integer is required but not provided
			marshaledResponse = simulator.performRequest(
					new Request.Builder(HttpMethod.POST, "/echo-int-request-body")
							.build());

			Assert.assertEquals(Integer.valueOf(400), marshaledResponse.getStatusCode());

			// Integer (primitive) request body, integer is not required and not provided
			marshaledResponse = simulator.performRequest(
					new Request.Builder(HttpMethod.POST, "/echo-int-optional-request-body")
							.build());

			Assert.assertEquals(Integer.valueOf(200), marshaledResponse.getStatusCode());
			Assert.assertArrayEquals("Response body doesn't match",
					"0".getBytes(StandardCharsets.UTF_8), // 0 is understood to be the default value for uninitialized int
					marshaledResponse.getBody().get());
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

			MarshaledResponse marshaledResponse = simulator.performRequest(
					new Request.Builder(HttpMethod.POST, "/multipart-upload?upload_progress_id=12344")
							.headers(Map.of(
									"Content-Type", Set.of("multipart/form-data; boundary=----WebKitFormBoundary59MIY6fOE42AL48U"),
									"Content-Length", Set.of(String.valueOf(requestBody.length))
							))
							.body(requestBody)
							.build());

			Assert.assertEquals(Integer.valueOf(204), marshaledResponse.getStatusCode());
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
	public void httpHead() {
		SokletConfiguration configuration = configurationForResourceClasses(Set.of(HttpHeadResource.class));
		Soklet.runSimulator(configuration, (simulator -> {
			// Response headers should be the same as the GET equivalent, but HTTP 204 and no response body
			MarshaledResponse getMethodMarshaledResponse = simulator.performRequest(
					new Request.Builder(HttpMethod.GET, "/hello-world").build());

			Assert.assertArrayEquals("Response body doesn't match",
					"hello world".getBytes(StandardCharsets.UTF_8),
					getMethodMarshaledResponse.getBody().get());

			// Response headers should be the same as the GET equivalent, but HTTP 204 and no response body
			MarshaledResponse headMarshaledResponse = simulator.performRequest(
					new Request.Builder(HttpMethod.HEAD, "/hello-world").build());

			Assert.assertEquals(Integer.valueOf(200), headMarshaledResponse.getStatusCode());
			Assert.assertEquals("GET and HEAD headers don't match",
					getMethodMarshaledResponse.getHeaders(), headMarshaledResponse.getHeaders());
			Assert.assertArrayEquals("Received a response body but didn't expect one",
					emptyByteArray(),
					headMarshaledResponse.getBody().orElse(emptyByteArray()));

			// If you want to handle your own HEAD requests, we still prevent you from trying to send a response body
			MarshaledResponse explicitHeadMarshaledResponse = simulator.performRequest(
					new Request.Builder(HttpMethod.HEAD, "/explicit-head-handling").build());

			Assert.assertArrayEquals("Received a response body but didn't expect one",
					emptyByteArray(),
					explicitHeadMarshaledResponse.getBody().orElse(emptyByteArray()));
		}));
	}

	@Test
	public void serverSentEventServer() throws InterruptedException {
		SynchronousQueue<String> shutdownQueue = new SynchronousQueue<>();

		ServerSentEventServer serverSentEventServer = DefaultServerSentEventServer.withPort(8081)
				.resourcePaths(Set.of(ResourcePath.of("/examples/{exampleId}")))
				.build();

		SokletConfiguration configuration = SokletConfiguration.withServer(DefaultServer.withPort(8080).build())
				.serverSentEventServer(serverSentEventServer)
				.resourceMethodResolver(new DefaultResourceMethodResolver(Set.of(ServerSentEventResource.class)))
				.corsAuthorizer(new AllOriginsCorsAuthorizer())
				.instanceProvider(new DefaultInstanceProvider() {
					@Nonnull
					@Override
					public <T> T provide(@Nonnull Class<T> instanceClass) {
						if (instanceClass.equals(ServerSentEventResource.class))
							return (T) new ServerSentEventResource(serverSentEventServer, () -> {
								try {
									shutdownQueue.put("poison pill");
								} catch (InterruptedException e) {
									// Nothing to do
									Thread.currentThread().interrupt();
								}
							});

						return super.provide(instanceClass);
					}
				})
				.build();

		try (Soklet soklet = new Soklet(configuration)) {
			soklet.start();
			shutdownQueue.take(); // Wait for someone to tell us to stop
		}
	}

	@ThreadSafe
	protected static class ServerSentEventResource {
		@Nonnull
		private final ServerSentEventServer serverSentEventServer;
		@Nonnull
		private final Runnable sokletStopper;

		public ServerSentEventResource(@Nonnull ServerSentEventServer serverSentEventServer,
																	 @Nonnull Runnable sokletStopper) {
			requireNonNull(serverSentEventServer);
			requireNonNull(sokletStopper);

			this.serverSentEventServer = serverSentEventServer;
			this.sokletStopper = sokletStopper;
		}

		@POST("/fire-server-sent-event")
		public void fireServerSentEvent() {
			ResourcePathInstance resourcePathInstance = ResourcePathInstance.of("/examples/abc"); // Matches /examples/{exampleId}
			ServerSentEventSource serverSentEventSource = this.serverSentEventServer.acquireEventSource(resourcePathInstance).get();

			ServerSentEvent serverSentEvent = ServerSentEvent.withEvent("test")
					.data("""
							{
							  "testing": 123,
							  "value": "abc"
							}
							""")
					.id(UUID.randomUUID().toString())
					.retry(Duration.ofSeconds(5))
					.build();

			serverSentEventSource.broadcast(serverSentEvent);
		}

		@POST("/shutdown")
		public void shutdown() {
			this.sokletStopper.run();
		}
	}

	@ThreadSafe
	protected static class HttpHeadResource {
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
		return SokletConfiguration.withMockServer()
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