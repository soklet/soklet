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
import com.soklet.annotation.ServerSentEventSource;
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
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.net.Socket;
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

			Assert.assertArrayEquals("Response body doesn't match",
					"hello world".getBytes(StandardCharsets.UTF_8),
					requestResult.getMarshaledResponse().getBody().get());

			// Missing query param?  It should be a 400
			requestResult = simulator.performRequest(
					new Request.Builder(HttpMethod.GET, "/integer-query-param")
							.build());

			Assert.assertEquals(Integer.valueOf(400), requestResult.getMarshaledResponse().getStatusCode());

			// Have the query param?  It's a 204
			requestResult = simulator.performRequest(
					new Request.Builder(HttpMethod.GET, "/integer-query-param?intQueryParam=123")
							.build());

			Assert.assertEquals(Integer.valueOf(204), requestResult.getMarshaledResponse().getStatusCode());
			Assert.assertArrayEquals("Received a response body but didn't expect one",
					emptyByteArray(),
					requestResult.getMarshaledResponse().getBody().orElse(emptyByteArray()));

			// Have the custom-named query param?  It's a 200 and echoes back the param as a string
			requestResult = simulator.performRequest(
					new Request.Builder(HttpMethod.GET, "/query-param-custom-name?local_date=2023-09-30")
							.build());

			Assert.assertEquals(Integer.valueOf(200), requestResult.getMarshaledResponse().getStatusCode());
			Assert.assertArrayEquals("Response body doesn't match",
					"2023-09-30".getBytes(StandardCharsets.UTF_8),
					requestResult.getMarshaledResponse().getBody().get());

			// Optional query param, no param provided
			requestResult = simulator.performRequest(
					new Request.Builder(HttpMethod.POST, "/optional-query-param")
							.build());

			Assert.assertEquals(Integer.valueOf(204), requestResult.getMarshaledResponse().getStatusCode());
			Assert.assertArrayEquals("Received a response body but didn't expect one",
					emptyByteArray(),
					requestResult.getMarshaledResponse().getBody().orElse(emptyByteArray()));

			// Optional query param, param provided
			requestResult = simulator.performRequest(
					new Request.Builder(HttpMethod.POST, "/optional-query-param?optionalQueryParam=123.456789")
							.build());

			Assert.assertEquals(Integer.valueOf(200), requestResult.getMarshaledResponse().getStatusCode());
			Assert.assertArrayEquals("Response body doesn't match",
					"123.456789".getBytes(StandardCharsets.UTF_8),
					requestResult.getMarshaledResponse().getBody().get());

			// Integer (nonprimitive) request body, integer is required but not provided
			requestResult = simulator.performRequest(
					new Request.Builder(HttpMethod.POST, "/echo-integer-request-body")
							.build());

			Assert.assertEquals(Integer.valueOf(400), requestResult.getMarshaledResponse().getStatusCode());

			// Integer (nonprimitive) request body, integer is required and provided
			requestResult = simulator.performRequest(
					new Request.Builder(HttpMethod.POST, "/echo-integer-request-body")
							.body("123".getBytes(StandardCharsets.UTF_8))
							.build());

			Assert.assertEquals(Integer.valueOf(200), requestResult.getMarshaledResponse().getStatusCode());
			Assert.assertArrayEquals("Response body doesn't match",
					"123".getBytes(StandardCharsets.UTF_8),
					requestResult.getMarshaledResponse().getBody().get());

			// Integer (nonprimitive) request body, integer is not required and not provided.
			// This exercises Optional<T> as opposed to @RequestBody(optional=true)
			requestResult = simulator.performRequest(
					new Request.Builder(HttpMethod.POST, "/echo-integer-optional-request-body-1")
							.build());

			Assert.assertEquals(Integer.valueOf(204), requestResult.getMarshaledResponse().getStatusCode());
			Assert.assertArrayEquals("Response body doesn't match",
					null, requestResult.getMarshaledResponse().getBody().orElse(null));

			// Integer (nonprimitive) request body, integer is not required and not provided.
			// This exercises @RequestBody(optional=true) as opposed to Optional<T>
			requestResult = simulator.performRequest(
					new Request.Builder(HttpMethod.POST, "/echo-integer-optional-request-body-2")
							.build());

			Assert.assertEquals(Integer.valueOf(204), requestResult.getMarshaledResponse().getStatusCode());
			Assert.assertArrayEquals("Response body doesn't match",
					null, requestResult.getMarshaledResponse().getBody().orElse(null));

			// Integer (primitive) request body, integer is required and provided
			requestResult = simulator.performRequest(
					new Request.Builder(HttpMethod.POST, "/echo-int-request-body")
							.body("123".getBytes(StandardCharsets.UTF_8))
							.build());

			Assert.assertEquals(Integer.valueOf(200), requestResult.getMarshaledResponse().getStatusCode());
			Assert.assertArrayEquals("Response body doesn't match",
					"123".getBytes(StandardCharsets.UTF_8),
					requestResult.getMarshaledResponse().getBody().get());

			// Integer (primitive) request body, integer is required but not provided
			requestResult = simulator.performRequest(
					new Request.Builder(HttpMethod.POST, "/echo-int-request-body")
							.build());

			Assert.assertEquals(Integer.valueOf(400), requestResult.getMarshaledResponse().getStatusCode());

			// Integer (primitive) request body, integer is not required and not provided
			requestResult = simulator.performRequest(
					new Request.Builder(HttpMethod.POST, "/echo-int-optional-request-body")
							.build());

			Assert.assertEquals(Integer.valueOf(200), requestResult.getMarshaledResponse().getStatusCode());
			Assert.assertArrayEquals("Response body doesn't match",
					"0".getBytes(StandardCharsets.UTF_8), // 0 is understood to be the default value for uninitialized int
					requestResult.getMarshaledResponse().getBody().get());
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

			Assert.assertEquals("Response body doesn't match", "hello world", responseBody);

			ResourceMethod resourceMethod = requestResult.getResourceMethod().get();
			Method expectedMethod;

			try {
				expectedMethod = RequestHandlingBasicsResource.class.getMethod("helloWorld");
			} catch (NoSuchMethodException e) {
				throw new RuntimeException(e);
			}

			Assert.assertEquals("Resource method doesn't match", expectedMethod, resourceMethod.getMethod());
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

			Assert.assertEquals(Integer.valueOf(204), requestResult.getMarshaledResponse().getStatusCode());
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
	public void testVarargs() {
		SokletConfiguration configuration = configurationForResourceClasses(Set.of(VarargsResource.class));
		Soklet.runSimulator(configuration, (simulator -> {
			RequestResult requestResult = simulator.performRequest(
					new Request.Builder(HttpMethod.GET, "/static/js/some/file/example.js")
							.build()
			);

			Assert.assertEquals(Integer.valueOf(200), requestResult.getMarshaledResponse().getStatusCode());
			Assert.assertEquals("js/some/file/example.js", requestResult.getResponse().get().getBody().get());


			requestResult = simulator.performRequest(
					new Request.Builder(HttpMethod.GET, "/123/static/js/some/file/example.js")
							.build()
			);

			Assert.assertEquals(Integer.valueOf(200), requestResult.getMarshaledResponse().getStatusCode());
			Assert.assertEquals("123-js/some/file/example.js", requestResult.getResponse().get().getBody().get());

			requestResult = simulator.performRequest(
					new Request.Builder(HttpMethod.GET, "/static2/js/some/file/example.js")
							.build()
			);

			Assert.assertEquals(Integer.valueOf(500), requestResult.getMarshaledResponse().getStatusCode());
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

			Assert.assertArrayEquals("Response body doesn't match",
					"hello world".getBytes(StandardCharsets.UTF_8),
					getMethodResult.getMarshaledResponse().getBody().get());

			// Response headers should be the same as the GET equivalent, but HTTP 204 and no response body
			RequestResult headMethodResult = simulator.performRequest(
					new Request.Builder(HttpMethod.HEAD, "/hello-world").build());

			Assert.assertEquals(Integer.valueOf(200), headMethodResult.getMarshaledResponse().getStatusCode());
			Assert.assertEquals("GET and HEAD headers don't match",
					getMethodResult.getMarshaledResponse().getHeaders(), headMethodResult.getMarshaledResponse().getHeaders());
			Assert.assertArrayEquals("Received a response body but didn't expect one",
					emptyByteArray(),
					headMethodResult.getMarshaledResponse().getBody().orElse(emptyByteArray()));

			// If you want to handle your own HEAD requests, we still prevent you from trying to send a response body
			RequestResult explicitHeadMethodResult = simulator.performRequest(
					new Request.Builder(HttpMethod.HEAD, "/explicit-head-handling").build());

			Assert.assertArrayEquals("Received a response body but didn't expect one",
					emptyByteArray(),
					explicitHeadMethodResult.getMarshaledResponse().getBody().orElse(emptyByteArray()));
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

	// SSE tests below

	@Test
	public void serverSentEventServerSimulator() throws InterruptedException {
		SokletConfiguration configuration = SokletConfiguration.forTesting()
				.resourceMethodResolver(new DefaultResourceMethodResolver(Set.of(ServerSentEventSimulatorResource.class)))
				.build();

		Soklet.runSimulator(configuration, (simulator -> {
			simulator.registerServerSentEventConsumer(ResourcePath.of("/examples/abc"), (serverSentEvent -> {
				Assert.assertEquals("SSE event mismatch", "example", serverSentEvent.getEvent().get());
			}));

			// Perform initial handshake with /examples/abc and verify 200 response
			Request request = Request.with(HttpMethod.GET, "/examples/abc").build();
			RequestResult requestResult = simulator.performRequest(request);

			Assert.assertEquals(Integer.valueOf(200), requestResult.getMarshaledResponse().getStatusCode());

			// Create a server-sent event...
			ServerSentEvent serverSentEvent = ServerSentEvent.withEvent("example")
					.data("data")
					.id("abc")
					.retry(Duration.ofSeconds(10))
					.build();

			// ...and broadcast it to all /examples/abc listeners
			ServerSentEventBroadcaster broadcaster = simulator.acquireServerSentEventBroadcaster(ResourcePath.of("/examples/abc"));
			broadcaster.broadcast(serverSentEvent);
		}));
	}

	@ThreadSafe
	public static class ServerSentEventSimulatorResource {
		@ServerSentEventSource("/examples/{exampleId}")
		public Response exampleServerSentEventSource(@Nonnull Request request,
																								 @Nonnull @PathParameter String exampleId) {
			return Response.withStatusCode(200).build();
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

		@ServerSentEventSource("/examples/{exampleId}")
		public Response exampleServerSentEventSource(@Nonnull Request request,
																								 @Nonnull @PathParameter String exampleId) {
			System.out.printf("Server-Sent Event Source connection initiated for %s with exampleId value %s\n", request.getId(), exampleId);
			return Response.withStatusCode(200).build();
		}

		@POST("/fire-server-sent-event")
		public void fireServerSentEvent() {
			ResourcePath resourcePath = ResourcePath.of("/examples/abc"); // Matches /examples/{exampleId}
			ServerSentEventBroadcaster broadcaster = this.serverSentEventServer.acquireBroadcaster(resourcePath).get();

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

			broadcaster.broadcast(serverSentEvent);
		}

		@POST("/shutdown")
		public void shutdown() {
			this.sokletStopper.run();
		}
	}

	@Test(timeout = 5000)
	public void sse_startStop_doesNotHang() throws Exception {
		int httpPort = findFreePort();
		int ssePort = findFreePort();

		DefaultServerSentEventServer sse = DefaultServerSentEventServer.withPort(ssePort)
				.host("127.0.0.1")
				.requestTimeout(Duration.ofSeconds(5))
				.build();

		SokletConfiguration cfg = SokletConfiguration.withServer(DefaultServer.withPort(httpPort).build())
				.serverSentEventServer(sse)
				.resourceMethodResolver(new DefaultResourceMethodResolver(Set.of(SseNetworkResource.class)))
				.lifecycleInterceptor(new QuietLifecycle()) // no noise in test logs
				.build();

		try (Soklet app = new Soklet(cfg)) {
			app.start();
			// if stop hangs due to accept(), this test times out
		} // try-with-resources stops both HTTP and SSE servers
	}

	@Test(timeout = 10000)
	public void sse_handshakeHeaders_and_basicDelivery() throws Exception {
		int httpPort = findFreePort();
		int ssePort = findFreePort();

		DefaultServerSentEventServer sse = DefaultServerSentEventServer.withPort(ssePort)
				.host("127.0.0.1")
				.requestTimeout(Duration.ofSeconds(5))
				.build();

		SokletConfiguration cfg = SokletConfiguration.withServer(DefaultServer.withPort(httpPort).build())
				.serverSentEventServer(sse)
				.resourceMethodResolver(new DefaultResourceMethodResolver(Set.of(SseNetworkResource.class)))
				.lifecycleInterceptor(new QuietLifecycle())
				.build();

		try (Soklet app = new Soklet(cfg)) {
			app.start();

			try (Socket socket = connectWithRetry("127.0.0.1", ssePort, 2000)) {
				socket.setSoTimeout(4000);

				// Handshake
				writeHttpGet(socket, "/tests/abc", ssePort);
				String rawHeaders = readUntil(socket.getInputStream(), "\r\n\r\n", 4096);
				if (rawHeaders == null) rawHeaders = readUntil(socket.getInputStream(), "\n\n", 4096);

				Assert.assertNotNull("Did not receive HTTP response headers", rawHeaders);
				String[] headerLines = rawHeaders.split("\r?\n");
				Assert.assertTrue("Non-200 handshake", headerLines[0].startsWith("HTTP/1.1 200"));

				Map<String, String> headers = parseHeaders(headerLines);
				Assert.assertTrue("Missing text/event-stream",
						headers.getOrDefault("content-type", "").toLowerCase().contains("text/event-stream"));
				Assert.assertEquals("no", headers.getOrDefault("x-accel-buffering", "").toLowerCase());
				Assert.assertEquals("keep-alive", headers.getOrDefault("connection", "").toLowerCase());
				Assert.assertEquals("no-cache", headers.getOrDefault("cache-control", "").toLowerCase());

				// Broadcast one event and verify frame formatting
				ServerSentEventBroadcaster b = sse.acquireBroadcaster(ResourcePath.of("/tests/abc")).get();
				ServerSentEvent ev = ServerSentEvent.withEvent("test")
						.data("hello\nworld")
						.id("e1")
						.retry(Duration.ofSeconds(10))
						.build();
				b.broadcast(ev);

				String block = readUntil(socket.getInputStream(), "\n\n", 8192);
				Assert.assertNotNull("Did not receive first SSE event", block);
				List<String> lines = block.lines().map(String::trim).filter(s -> !s.isEmpty()).toList();

				Assert.assertTrue(lines.stream().anyMatch(s -> s.equals("event: test")));
				Assert.assertTrue(lines.stream().anyMatch(s -> s.equals("id: e1")));
				Assert.assertTrue(lines.stream().anyMatch(s -> s.equals("retry: 10000")));
				Assert.assertTrue(lines.stream().anyMatch(s -> s.equals("data: hello")));
				Assert.assertTrue(lines.stream().anyMatch(s -> s.equals("data: world")));
			}
		}
	}

	@Test(timeout = 20000)
	public void sse_largeEvent_isFullyWritten() throws Exception {
		int httpPort = findFreePort();
		int ssePort = findFreePort();

		DefaultServerSentEventServer sse = DefaultServerSentEventServer.withPort(ssePort)
				.host("127.0.0.1")
				.requestTimeout(Duration.ofSeconds(5))
				.build();

		SokletConfiguration cfg = SokletConfiguration.withServer(DefaultServer.withPort(httpPort).build())
				.serverSentEventServer(sse)
				.resourceMethodResolver(new DefaultResourceMethodResolver(Set.of(SseNetworkResource.class)))
				.lifecycleInterceptor(new QuietLifecycle())
				.build();

		try (Soklet app = new Soklet(cfg)) {
			app.start();

			try (Socket socket = connectWithRetry("127.0.0.1", ssePort, 2000)) {
				socket.setSoTimeout(12000);

				writeHttpGet(socket, "/tests/large", ssePort);
				// consume headers
				String hdr = readUntil(socket.getInputStream(), "\r\n\r\n", 4096);
				if (hdr == null) hdr = readUntil(socket.getInputStream(), "\n\n", 4096);
				Assert.assertNotNull(hdr);

				// Build a ~128KiB payload split across many lines
				String line = "A".repeat(64);
				int linesCount = 2048; // 2048 * 64 ~= 131072
				String bigData = java.util.stream.Stream.generate(() -> line).limit(linesCount).collect(java.util.stream.Collectors.joining("\n"));

				ServerSentEventBroadcaster b = sse.acquireBroadcaster(ResourcePath.of("/tests/large")).get();
				ServerSentEvent ev = ServerSentEvent.withEvent("big").id("big-1").data(bigData).build();
				b.broadcast(ev);

				// Read exactly one event block
				String block = readUntil(socket.getInputStream(), "\n\n", (64 + 8) * linesCount + 8192);
				Assert.assertNotNull("Did not receive large event", block);

				// Reconstruct data lines
				String reconstructed = block.lines()
						.filter(l -> l.startsWith("data: "))
						.map(l -> l.substring("data: ".length()))
						.collect(java.util.stream.Collectors.joining("\n"));

				Assert.assertEquals("Large SSE payload corrupted or truncated", bigData.length(), reconstructed.length());
				Assert.assertEquals("Large SSE payload mismatch", bigData, reconstructed);
			}
		}
	}

	@Test(timeout = 20000)
	public void sse_broadcastMany_doesNotThrow_andEventuallyDeliversLast() throws Exception {
		int httpPort = findFreePort();
		int ssePort = findFreePort();

		DefaultServerSentEventServer sse = DefaultServerSentEventServer.withPort(ssePort)
				.host("127.0.0.1")
				.requestTimeout(Duration.ofSeconds(5))
				.build();

		SokletConfiguration cfg = SokletConfiguration.withServer(DefaultServer.withPort(httpPort).build())
				.serverSentEventServer(sse)
				.resourceMethodResolver(new DefaultResourceMethodResolver(Set.of(SseNetworkResource.class)))
				.lifecycleInterceptor(new QuietLifecycle())
				.build();

		try (Soklet app = new Soklet(cfg)) {
			app.start();

			try (Socket socket = connectWithRetry("127.0.0.1", ssePort, 2000)) {
				socket.setSoTimeout(12000);

				writeHttpGet(socket, "/tests/backpressure", ssePort);
				// consume headers
				String hdr = readUntil(socket.getInputStream(), "\r\n\r\n", 4096);
				if (hdr == null) hdr = readUntil(socket.getInputStream(), "\n\n", 4096);
				Assert.assertNotNull(hdr);

				ServerSentEventBroadcaster b = sse.acquireBroadcaster(ResourcePath.of("/tests/backpressure")).get();

				// Rapidly broadcast a bunch of small events (some will be dropped under pressure, but last should survive)
				final int N = 1500;
				for (int i = 0; i < N; i++) {
					b.broadcast(ServerSentEvent.withEvent("bp").id(String.valueOf(i)).data("x").build());
				}

				// Now read until we observe the last id (or time out)
				long deadline = System.currentTimeMillis() + 12000;
				boolean sawLast = false;
				while (System.currentTimeMillis() < deadline) {
					String block = readUntil(socket.getInputStream(), "\n\n", 4096);
					if (block == null) continue;
					String idLine = block.lines().filter(l -> l.startsWith("id: ")).reduce((a, b2) -> b2).orElse(null);
					if (idLine != null && idLine.trim().equals("id: " + (N - 1))) {
						sawLast = true;
						break;
					}
				}

				Assert.assertTrue("Did not observe the last broadcast id under backpressure", sawLast);
			}
		}
	}

	@Test(timeout = 15000)
	public void sse_stopClosesConnection() throws Exception {
		int httpPort = findFreePort();
		int ssePort = findFreePort();

		DefaultServerSentEventServer sse = DefaultServerSentEventServer.withPort(ssePort)
				.host("127.0.0.1")
				.requestTimeout(Duration.ofSeconds(5))
				.build();

		SokletConfiguration cfg = SokletConfiguration.withServer(DefaultServer.withPort(httpPort).build())
				.serverSentEventServer(sse)
				.resourceMethodResolver(new DefaultResourceMethodResolver(Set.of(SseNetworkResource.class)))
				.lifecycleInterceptor(new QuietLifecycle())
				.build();

		try (Soklet app = new Soklet(cfg)) {
			app.start();

			Socket socket = connectWithRetry("127.0.0.1", ssePort, 2000);
			socket.setSoTimeout(6000);

			writeHttpGet(socket, "/tests/closeme", ssePort);
			String hdr = readUntil(socket.getInputStream(), "\r\n\r\n", 4096);
			if (hdr == null) hdr = readUntil(socket.getInputStream(), "\n\n", 4096);
			Assert.assertNotNull(hdr);

			// Kick one message through so the writer loop is active
			sse.acquireBroadcaster(ResourcePath.of("/tests/closeme")).get()
					.broadcast(ServerSentEvent.withEvent("one").id("1").data("a").build());
			readUntil(socket.getInputStream(), "\n\n", 4096); // consume it

			// Now stop the server; this should enqueue poison pills and close the channel
			app.close(); // stops both servers

			// Attempt to read again; we expect EOF (-1) within timeout
			boolean sawEof = waitForEof(socket, 6000);
			socket.close();
			Assert.assertTrue("Connection did not close after server stop", sawEof);
		}
	}

	// ---------- Helpers & mini resource ----------

	@ThreadSafe
	public static class SseNetworkResource {
		@ServerSentEventSource("/tests/{id}")
		public Response sseSource(@Nonnull Request request, @Nonnull @PathParameter String id) {
			return Response.withStatusCode(200).build();
		}
	}

	private static class QuietLifecycle implements LifecycleInterceptor {
		@Override
		public void didReceiveLogEvent(@Nonnull LogEvent logEvent) { /* no-op */ }
	}

	private static void writeHttpGet(Socket socket, String path, int port) throws IOException {
		String req = "GET " + path + " HTTP/1.1\r\n"
				+ "Host: 127.0.0.1:" + port + "\r\n"
				+ "Accept: text/event-stream\r\n"
				+ "Connection: keep-alive\r\n"
				+ "\r\n";
		socket.getOutputStream().write(req.getBytes(StandardCharsets.UTF_8));
		socket.getOutputStream().flush();
	}

	private static Socket connectWithRetry(String host, int port, int timeoutMs) throws IOException, InterruptedException {
		long deadline = System.currentTimeMillis() + timeoutMs;
		IOException last = null;
		while (System.currentTimeMillis() < deadline) {
			try {
				Socket s = new Socket();
				s.connect(new java.net.InetSocketAddress(host, port), Math.max(250, timeoutMs / 2));
				return s;
			} catch (IOException e) {
				last = e;
				Thread.sleep(30);
			}
		}
		throw (last != null ? last : new IOException("Unable to connect to " + host + ":" + port));
	}

	private static String readUntil(java.io.InputStream in, String terminator, int maxBytes) throws IOException {
		byte[] term = terminator.getBytes(StandardCharsets.UTF_8);
		java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
		int b;
		int match = 0;
		while (buf.size() < maxBytes && (b = in.read()) != -1) {
			buf.write(b);
			if (b == term[match]) {
				match++;
				if (match == term.length) {
					return buf.toString(StandardCharsets.UTF_8);
				}
			} else {
				match = (b == term[0]) ? 1 : 0;
			}
		}
		return null;
	}

	private static boolean waitForEof(Socket socket, int timeoutMs) throws IOException {
		long deadline = System.currentTimeMillis() + timeoutMs;
		java.io.InputStream in = socket.getInputStream();
		byte[] tmp = new byte[1];
		while (System.currentTimeMillis() < deadline) {
			try {
				int n = in.read(tmp);
				if (n == -1) return true;
			} catch (java.net.SocketTimeoutException e) {
				// keep trying until deadline
			}
		}
		return false;
	}

	private static Map<String, String> parseHeaders(String[] headerLines) {
		java.util.HashMap<String, String> m = new java.util.HashMap<>();
		for (int i = 1; i < headerLines.length; i++) {
			String line = headerLines[i];
			int idx = line.indexOf(':');
			if (idx <= 0) continue;
			String k = line.substring(0, idx).trim().toLowerCase(java.util.Locale.ROOT);
			String v = line.substring(idx + 1).trim();
			m.put(k, v);
		}
		return m;
	}

	private static int findFreePort() throws IOException {
		try (java.net.ServerSocket ss = new java.net.ServerSocket(0)) {
			ss.setReuseAddress(true);
			return ss.getLocalPort();
		}
	}
}