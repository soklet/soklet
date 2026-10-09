package com.soklet;

import com.soklet.annotation.GET;
import com.soklet.annotation.PathParameter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(value = 60, unit = TimeUnit.SECONDS)
class FiniteHttpResponseSafetyTests {
	@Test
	void customHeadRepresentationLengthSurvivesAndGetStillUsesActualLength() throws Exception {
		ResponseMarshaler marshaler = ResponseMarshaler.builder().headHandler((request, response) ->
				response.copy().body((MarshaledResponseBody) null).headers(Map.of("Content-Length", List.of("4242"))).finish()).build();
		try (Fixture fixture = new Fixture(null, RequestInterceptor.defaultInstance(), marshaler)) {
			assertEquals(List.of("4242"), fixture.pipeline("HEAD", "/clean", "/clean").get(0).headers().get("content-length"));
			assertEquals(List.of("3"), fixture.pipeline("GET", "/clean", "/clean").get(0).headers().get("content-length"));
			WireResponse noContent = fixture.pipeline("HEAD", "/status/204", "/clean").get(0);
			assertEquals(204, noContent.status());
			assertFalse(noContent.headers().containsKey("content-length"));
		}
	}

	@Test
	void upgradeRequiredRetainsItsAdvertisementWithoutAcceptingUnsafeFraming() throws Exception {
		try (Fixture fixture = new Fixture(null, RequestInterceptor.defaultInstance(), ResponseMarshaler.defaultInstance())) {
			WireResponse response = fixture.pipeline("GET", "/upgrade", "/clean").get(0);
			assertEquals(426, response.status());
			assertEquals(List.of("HTTP/2.0"), response.headers().get("upgrade"));
			assertEquals(List.of("Upgrade"), response.headers().get("connection"));
			assertFalse(response.headers().containsKey("transfer-encoding"));
		}
	}
	@Test
	void applicationFramingCannotDesynchronizePipelinedFiniteResponses() throws Exception {
		try (Fixture fixture = new Fixture(null, RequestInterceptor.defaultInstance(), ResponseMarshaler.defaultInstance())) {
			for (String method : List.of("GET", "HEAD")) {
				List<WireResponse> responses = fixture.pipeline(method, "/unsafe", "/clean");
				WireResponse first = responses.get(0);
				assertEquals(200, first.status());
				assertEquals(List.of("3"), first.headers().get("content-length"));
				assertEquals(method.equals("HEAD") ? "" : "abc", first.body());
				for (String forbidden : List.of("transfer-encoding", "upgrade", "trailer", "keep-alive", "proxy-connection", "te", "x-hop"))
					assertFalse(first.headers().containsKey(forbidden), first.headers().toString());
				assertEquals(List.of("z", "a"), first.headers().get("x-order"));
				assertEquals(200, responses.get(1).status()); assertEquals("def", responses.get(1).body());
			}
		}
	}

	@Test
	void finiteHeadFileLengthUsesOriginalRepresentationInsteadOfApplicationHeader(@TempDir Path directory) throws Exception {
		Path file = directory.resolve("representation.txt"); Files.writeString(file, "abcde", StandardCharsets.US_ASCII);
		try (Fixture fixture = new Fixture(file, RequestInterceptor.defaultInstance(), ResponseMarshaler.defaultInstance())) {
			List<WireResponse> responses = fixture.pipeline("HEAD", "/file", "/clean");
			assertEquals(200, responses.get(0).status());
			assertEquals(List.of("5"), responses.get(0).headers().get("content-length"));
			assertEquals("", responses.get(0).body());
			assertEquals(200, responses.get(1).status()); assertEquals("def", responses.get(1).body());
		}
	}

	@Test
	void explicitConnectionCloseSurvivesHopHeaderFiltering() throws Exception {
		try (Fixture fixture = new Fixture(null, RequestInterceptor.defaultInstance(), ResponseMarshaler.defaultInstance());
				Socket socket = fixture.socket()) {
			socket.getOutputStream().write(request("GET", "/close", false).getBytes(StandardCharsets.US_ASCII));
			WireResponse response = readResponse(socket.getInputStream(), false);
			assertEquals(200, response.status()); assertEquals("abc", response.body());
			assertEquals(List.of("close"), response.headers().get("connection"));
			assertFalse(response.headers().containsKey("x-hop"));
			assertEquals(-1, socket.getInputStream().read());
		}
	}

	@Test
	void invalidFinalStatusesBecomeFinite500AndLeaveNextPipelinedResponseReadable() throws Exception {
		try (Fixture fixture = new Fixture(null, RequestInterceptor.defaultInstance(), ResponseMarshaler.defaultInstance())) {
			for (int status : List.of(42, 100, 101, 199, 600, 999, 1000)) {
				List<WireResponse> responses = fixture.pipeline("GET", "/status/" + status, "/clean");
				assertEquals(500, responses.get(0).status(), "Unsafe final status " + status);
				assertEquals(200, responses.get(1).status()); assertEquals("def", responses.get(1).body());
			}
			for (int status : List.of(200, 299, 599))
				assertEquals(status, fixture.pipeline("GET", "/status/" + status, "/clean").get(0).status());
			assertEquals(0L, fixture.metrics.snapshot().orElseThrow().getActiveRequests());
		}
	}

	@Test
	void interceptorAndCustomMarshalerCannotBypassFinalStatusValidation() throws Exception {
		RequestInterceptor interceptor = new RequestInterceptor() {
			@Override public void interceptRequest(ServerType type, Request request, ResourceMethod method,
					Function<Request, MarshaledResponse> generator, Consumer<MarshaledResponse> writer) {
				if (request.getResourcePath().getPath().equals("/override"))
					writer.accept(MarshaledResponse.withStatusCode(101).build());
				else writer.accept(generator.apply(request));
			}
		};
		try (Fixture fixture = new Fixture(null, interceptor, ResponseMarshaler.defaultInstance())) {
			assertEquals(500, fixture.pipeline("GET", "/override", "/clean").get(0).status());
		}
		ResponseMarshaler marshaler = ResponseMarshaler.builder().postProcessor(response ->
				response.getStatusCode() == 500 ? response : response.copy().statusCode(101).finish()).build();
		try (Fixture fixture = new Fixture(null, RequestInterceptor.defaultInstance(), marshaler)) {
			assertEquals(500, fixture.pipeline("GET", "/not-found", "/clean").get(0).status());
		}
	}

	private static Map<String, List<String>> unsafeHeaders(String connection) {
		Map<String, List<String>> headers = new LinkedHashMap<>();
		headers.put("Content-Length", List.of("999", "1")); headers.put("Transfer-Encoding", List.of("chunked"));
		headers.put("Upgrade", List.of("other-protocol")); headers.put("Trailer", List.of("Digest"));
		headers.put("Keep-Alive", List.of("timeout=99")); headers.put("Proxy-Connection", List.of("keep-alive"));
		headers.put("TE", List.of("trailers")); headers.put("Connection", List.of(connection));
		headers.put("X-Hop", List.of("must-disappear")); headers.put("X-Order", List.of("z", "a"));
		return headers;
	}

	public static final class Resource {
		private final Path file;
		private Resource(Path file) { this.file = file; }
		@GET("/unsafe") public MarshaledResponse unsafe() {
			return MarshaledResponse.withStatusCode(200).headers(unsafeHeaders("X-Hop")).body("abc".getBytes(StandardCharsets.US_ASCII)).build();
		}
		@GET("/close") public MarshaledResponse close() {
			return MarshaledResponse.withStatusCode(200).headers(unsafeHeaders("X-Hop, CLOSE")).body("abc".getBytes(StandardCharsets.US_ASCII)).build();
		}
		@GET("/file") public MarshaledResponse file() {
			return MarshaledResponse.withStatusCode(200).headers(unsafeHeaders("X-Hop"))
					.body(new MarshaledResponseBody.File(this.file, 0L, 5L)).build();
		}
		@GET("/clean") public String clean() { return "def"; }
		@GET("/upgrade") public MarshaledResponse upgrade() {
			return MarshaledResponse.withStatusCode(426).headers(Map.of("Upgrade", List.of("HTTP/2.0"),
					"Connection", List.of("Upgrade"), "Transfer-Encoding", List.of("chunked"))).build();
		}
		@GET("/status/{value}") public MarshaledResponse status(@PathParameter(name = "value") Integer value) {
			return MarshaledResponse.withStatusCode(value).build();
		}
	}

	private static String request(String method, String path, boolean close) {
		return method + " " + path + " HTTP/1.1\r\nHost: localhost\r\n" + (close ? "Connection: close\r\n" : "") + "\r\n";
	}
	private record WireResponse(int status, Map<String, List<String>> headers, String body) {}
	private static WireResponse readResponse(InputStream input, boolean head) throws Exception {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		String text;
		do {
			int next = input.read(); assertTrue(next >= 0, "Response ended before headers"); bytes.write(next);
			assertTrue(bytes.size() < 16_384, "Response headers exceeded fixture bound");
			text = bytes.toString(StandardCharsets.ISO_8859_1);
		} while (!text.endsWith("\r\n\r\n"));
		String[] lines = text.split("\r\n");
		int status = Integer.parseInt(lines[0].split(" ")[1]);
		Map<String, List<String>> headers = new LinkedHashMap<>();
		for (int i = 1; i < lines.length; i++) {
			int separator = lines[i].indexOf(':'); assertTrue(separator > 0, lines[i]);
			headers.computeIfAbsent(lines[i].substring(0, separator).toLowerCase(java.util.Locale.ENGLISH), name -> new ArrayList<>())
					.add(lines[i].substring(separator + 1).trim());
		}
		int length = Integer.parseInt(headers.getOrDefault("content-length", List.of("0")).get(0));
		assertTrue(length >= 0 && length <= 65_536, "Response body exceeded fixture bound");
		byte[] body = head ? new byte[0] : input.readNBytes(length);
		assertEquals(head ? 0 : length, body.length);
		return new WireResponse(status, headers, new String(body, StandardCharsets.US_ASCII));
	}

	private static final class Fixture implements AutoCloseable {
		private final int port;
		private final Soklet soklet;
		private final DefaultMetricsCollector metrics = DefaultMetricsCollector.defaultInstance();
		private final AtomicInteger finishes = new AtomicInteger();
		private int expectedFinishes;
		private Fixture(Path file, RequestInterceptor interceptor, ResponseMarshaler marshaler) throws Exception {
			try (ServerSocket reserved = new ServerSocket(0)) { this.port = reserved.getLocalPort(); }
			Resource resource = new Resource(file);
			SokletConfig config = SokletConfig.withHttpServer(HttpServer.withPort(this.port).host("127.0.0.1").build())
					.lifecyclePolicy(LifecyclePolicy.builder().startupTimeout(java.time.Duration.ofSeconds(10))
							.startupCancelationTimeout(java.time.Duration.ofSeconds(1)).gracefulShutdownTimeout(java.time.Duration.ofSeconds(1))
							.forcedShutdownTimeout(java.time.Duration.ofSeconds(1)).build())
					.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Resource.class))).metricsCollector(this.metrics)
					.requestInterceptor(interceptor).responseMarshaler(marshaler)
					.lifecycleObserver(new LifecycleObserver() {
						@Override public void didReceiveLogEvent(LogEvent event) {}
						@Override public void didFinishRequestHandling(ServerType type, Request request, ResourceMethod method,
								MarshaledResponse response, java.time.Duration duration, List<Throwable> throwables) {
							synchronized (finishes) { finishes.incrementAndGet(); finishes.notifyAll(); }
						}
					})
					.instanceProvider(new InstanceProvider() { @Override public <T> T provide(Class<T> type) { return type.cast(resource); } }).build();
			this.soklet = Soklet.fromConfig(config); this.soklet.start();
		}
		private Socket socket() throws Exception { Socket socket = new Socket("127.0.0.1", this.port); socket.setSoTimeout(5000); return socket; }
		private List<WireResponse> pipeline(String method, String firstPath, String secondPath) throws Exception {
			this.expectedFinishes += 2;
			try (Socket socket = socket()) {
				socket.getOutputStream().write((request(method, firstPath, false) + request("GET", secondPath, true)).getBytes(StandardCharsets.US_ASCII));
				WireResponse first = readResponse(socket.getInputStream(), method.equals("HEAD"));
				WireResponse second = readResponse(socket.getInputStream(), false);
				assertEquals(-1, socket.getInputStream().read());
				long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
				synchronized (this.finishes) {
					while (this.finishes.get() < this.expectedFinishes) {
						long remaining = deadline - System.nanoTime(); assertTrue(remaining > 0, "Handling finish did not arrive");
						TimeUnit.NANOSECONDS.timedWait(this.finishes, remaining);
					}
				}
				return List.of(first, second);
			}
		}
		@Override public void close() { this.soklet.close(); }
	}
}
