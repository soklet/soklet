package com.soklet;

import com.soklet.annotation.GET;
import com.soklet.annotation.SseEventSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class ResponseFinalizationReleaseTests {
	@Test
	void freshInterceptorSseResponsePreservesAcceptedFraming() {
		AtomicInteger calls = new AtomicInteger();
		ResponseMarshaler marshaler = ResponseMarshaler.builder().postProcessor(response -> {
			calls.incrementAndGet();
			return response.copy().headers(headers -> headers.put("X-Finalized", List.of("yes"))).finish();
		}).build();
		SokletSimulator.run(sseConfig(new SseResource(), 0).responseMarshaler(marshaler)
				.requestInterceptor(freshSseResponse()).build(), simulator -> {
			try (var accepted = assertInstanceOf(SseRequestResult.HandshakeAccepted.class,
					simulator.performSseRequest(Request.fromPath(HttpMethod.GET, "/events")))) {
				MarshaledResponse response = accepted.getHttpRequestResult().getMarshaledResponse();
				assertEquals(List.of("yes"), response.getHeaders().get("X-Finalized"));
				assertFalse(response.getHeaders().containsKey("Content-Length"));
				assertFalse(response.getHeaders().containsKey("Transfer-Encoding"));
				assertEquals(1, calls.get(), "Only the interceptor-selected response is finalized.");
			}
		});
	}

	@Test
	void finalizingFreshSseResponseMayReplaceAcceptanceWithFiniteDenial() {
		AtomicInteger calls = new AtomicInteger();
		ResponseMarshaler marshaler = ResponseMarshaler.builder().postProcessor(response -> {
			calls.incrementAndGet();
			return response.copy().statusCode(403).headers(headers -> headers.put("Content-Type", List.of("text/plain")))
					.body("denied".getBytes(StandardCharsets.UTF_8)).finish();
		}).build();
		SokletSimulator.run(sseConfig(new SseResource(), 0).responseMarshaler(marshaler)
				.requestInterceptor(freshSseResponse()).build(), simulator -> {
			var result = assertInstanceOf(SseRequestResult.RequestFailed.class,
					simulator.performSseRequest(Request.fromPath(HttpMethod.GET, "/events")));
			assertTrue(result.getHttpRequestResult().getSseHandshakeResult().isEmpty());
			MarshaledResponse response = result.getHttpRequestResult().getMarshaledResponse();
			assertEquals(403, response.getStatusCode());
			assertEquals(List.of("6"), response.getHeaders().get("Content-Length"));
			assertArrayEquals("denied".getBytes(StandardCharsets.UTF_8), response.bodyBytesOrEmpty());
			assertEquals(1, calls.get());
		});
	}

	@Test
	void freshInterceptorSseResponseReachesTheSocket() throws Exception {
		int port = TestSupport.findFreePort();
		ResponseMarshaler marshaler = ResponseMarshaler.builder().postProcessor(response -> response.copy()
				.headers(headers -> headers.put("X-Finalized", List.of("yes"))).finish()).build();
		try (Soklet app = Soklet.fromConfig(sseConfig(new SseResource(), port)
				.responseMarshaler(marshaler).requestInterceptor(freshSseResponse()).build())) {
			app.start();
			try (Socket socket = new Socket("127.0.0.1", port)) {
				socket.setSoTimeout(5000);
				socket.getOutputStream().write("GET /events HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
				var reader = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
				List<String> headers = new ArrayList<>();
				for (String line; (line = reader.readLine()) != null && !line.isEmpty();) {
					headers.add(line);
					assertTrue(headers.size() < 100, "Unexpected unbounded response headers.");
				}
				assertFalse(headers.isEmpty(), "The accepted connection must send a response.");
				assertEquals("HTTP/1.1 200 OK", headers.get(0));
				assertTrue(headers.stream().anyMatch(header -> header.equalsIgnoreCase("X-Finalized: yes")));
				assertFalse(headers.stream().anyMatch(header -> header.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")));
			}
		}
	}

	@Test
	void rejectedUnparsedReplacementsReleaseRetainedOwnersAndPreserveBorrowedChannels(@TempDir Path directory) throws Exception {
		Path path = Files.writeString(directory.resolve("unparsed-body"), "body");
		for (boolean streaming : List.of(false, true)) {
			for (boolean timedOut : List.of(false, true)) {
				try (FileChannel owned = FileChannel.open(path); FileChannel borrowed = FileChannel.open(path)) {
					AtomicBoolean active = new AtomicBoolean(true);
					AtomicInteger producers = new AtomicInteger();
					List<LogEvent> events = new ArrayList<>();
					ResponseMarshaler marshaler = ResponseMarshaler.builder().unparsedRequestHandler(request ->
							MarshaledResponse.withStatusCode(400).body(owned, 0L, 4L, true).build())
							.postProcessor(response -> {
								MarshaledResponse replacement = streaming
										? response.copy().withoutBody().stream(stream -> producers.incrementAndGet()).finish()
										: response.copy().body(borrowed, 0L, 4L, false).finish();
								if (timedOut) active.set(false);
								return replacement;
							}).build();
					assertNull(UnparsedRequestResponseSupport.marshal(unparsedRequest(), marshaler,
							new LifecycleObserver() {}, events::add, active::get));
					assertFalse(owned.isOpen(), "Fallback and timeout abandonment must release retained owners.");
					assertTrue(borrowed.isOpen(), "The replacement's borrowed channel remains application-owned.");
					assertEquals(0, producers.get());
					assertEquals(timedOut ? 0 : 1, events.size());
				}
			}
		}
	}

	@Test
	void unparsedCleanupFailurePreservesValidationAndFallback(@TempDir Path directory) throws Exception {
		Path path = Files.writeString(directory.resolve("unparsed-close-failure"), "body");
		try (FileChannel raw = FileChannel.open(path); DelegatingChannel owned = new DelegatingChannel(raw, true)) {
			List<LogEvent> events = new ArrayList<>();
			ResponseMarshaler marshaler = ResponseMarshaler.builder().unparsedRequestHandler(request ->
					MarshaledResponse.withStatusCode(400).body(owned, 0L, 4L, true).build())
					.postProcessor(response -> response.copy().withoutBody().stream(stream -> {}).finish()).build();
			assertNull(UnparsedRequestResponseSupport.marshal(unparsedRequest(), marshaler,
					new LifecycleObserver() {}, events::add, () -> true));
			Throwable failure = events.get(0).getThrowable().orElseThrow();
			assertInstanceOf(IllegalArgumentException.class, failure);
			assertEquals(1, failure.getSuppressed().length);
			assertInstanceOf(java.io.UncheckedIOException.class, failure.getSuppressed()[0]);
			assertEquals(1, owned.closes.get());
			assertFalse(raw.isOpen());
		}
	}

	private static UnparsedRequest unparsedRequest() {
		return UnparsedRequest.withServerTypeAndReason(ServerType.HTTP, UnparsedRequestReason.MALFORMED_REQUEST).build();
	}

	private static RequestInterceptor freshSseResponse() {
		return new RequestInterceptor() {
			@Override public void interceptRequest(ServerType type, Request request, ResourceMethod method,
					Function<Request, MarshaledResponse> generator, Consumer<MarshaledResponse> writer) {
				MarshaledResponse generated = generator.apply(request);
				writer.accept(MarshaledResponse.withStatusCode(generated.getStatusCode())
						.headers(generated.getHeaders()).cookies(generated.getCookies()).build());
			}
		};
	}

	private static SokletConfig.Builder sseConfig(SseResource resource, int port) {
		return SokletConfig.withSseServer(SseServer.withPort(port).host("127.0.0.1").build())
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(SseResource.class)))
				.instanceProvider(new InstanceProvider() { @Override public <T> T provide(Class<T> type) { return type.cast(resource); } })
				.lifecycleObserver(new LifecycleObserver() { @Override public void didReceiveLogEvent(LogEvent event) {} })
				.lifecyclePolicy(LifecyclePolicy.builder().startupTimeout(Duration.ofSeconds(5)).startupCancelationTimeout(Duration.ofSeconds(1))
						.gracefulShutdownTimeout(Duration.ofSeconds(1)).forcedShutdownTimeout(Duration.ofSeconds(2)).build());
	}

	public static class SseResource {
		@SseEventSource("/events") public SseHandshakeResult events() { return SseHandshakeResult.accept(); }
	}

	@Test
	void nonIdempotentPostprocessorRunsOnceForGetHeadCorsAndErrors() {
		Resource resource = new Resource();
		for (String path : List.of("/logical", "/missing", "/crash")) {
			for (boolean cors : List.of(false, true)) {
				AtomicInteger calls = new AtomicInteger();
				ResponseMarshaler marshaler = ResponseMarshaler.builder().postProcessor(response -> {
					calls.incrementAndGet();
					if (cors) assertTrue(response.getHeaders().containsKey("Access-Control-Allow-Origin"));
					return response.copy().body(("prefix:" + new String(response.bodyBytesOrEmpty(), StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8))
							.cookies(cookies -> cookies.add(ResponseCookie.withName("post").value("once").build())).finish();
				}).build();
				SokletSimulator.run(config(resource, 0).responseMarshaler(marshaler).build(), simulator -> {
					var request = Request.withPath(HttpMethod.GET, path);
					if (cors) request.headers(Map.of("Origin", List.of("https://example.test")));
					MarshaledResponse get = simulator.performHttpRequest(request.build()).getMarshaledResponse();
					assertEquals(1, calls.get());
					assertEquals(1, get.getCookies().size());
					calls.set(0);
					MarshaledResponse head = simulator.performHttpRequest(request.build().copy().httpMethod(HttpMethod.HEAD).finish()).getMarshaledResponse();
					assertEquals(1, calls.get());
					assertEquals(1, head.getCookies().size());
					assertTrue(head.getBody().isEmpty());
					assertEquals(get.getHeaders().get("Content-Length"), head.getHeaders().get("Content-Length"));
				});
			}
		}
	}

	@Test
	void postprocessorStatusAndBodyChangesRemainAvailableToHeadCompressionPlanning() throws Exception {
		Resource resource = new Resource();
		AtomicInteger plans = new AtomicInteger();
		AtomicReference<Long> plannedLength = new AtomicReference<>();
		int port = TestSupport.findFreePort();
		HttpServer server = HttpServer.withPort(port).host("127.0.0.1").responseCompressor((request, response) -> {
			plans.incrementAndGet();
			assertEquals(201, response.getStatusCode());
			plannedLength.set(response.getBodyLength());
			return ResponseCompressionPlan.none();
		}).build();
		ResponseMarshaler marshaler = ResponseMarshaler.builder().postProcessor(response -> response.copy().statusCode(201)
				.body("prefix:body".getBytes(StandardCharsets.UTF_8)).finish()).build();
		try (Soklet app = Soklet.fromConfig(config(resource, port).httpServer(server).responseMarshaler(marshaler).build())) {
			app.start();
			try (Socket socket = new Socket("127.0.0.1", port)) {
				socket.setSoTimeout(5000);
				socket.getOutputStream().write("HEAD /logical HTTP/1.1\r\nHost: localhost\r\nAccept-Encoding: gzip\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
				String wire = new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
				assertTrue(wire.startsWith("HTTP/1.1 201"), wire);
				assertTrue(wire.toLowerCase(java.util.Locale.ROOT).contains("content-length: 11\r\n"), wire);
				assertTrue(wire.endsWith("\r\n\r\n"), wire);
			}
			assertEquals(1, plans.get());
			assertEquals(11L, plannedLength.get());
		}
	}

	@Test
	void directInterceptorReplacementAndGeneratedCopiesAreProcessedOnce() {
		for (boolean generate : List.of(false, true)) {
			AtomicInteger calls = new AtomicInteger();
			Resource resource = new Resource();
			SokletSimulator.run(config(resource, 0).responseMarshaler(ResponseMarshaler.builder().postProcessor(response -> {
				calls.incrementAndGet();
				return response.copy().headers(headers -> headers.put("X-Finalized", List.of("yes"))).finish();
			}).build()).requestInterceptor(new RequestInterceptor() {
				@Override public void interceptRequest(ServerType type, Request request, ResourceMethod method,
						Function<Request, MarshaledResponse> generator, Consumer<MarshaledResponse> writer) {
					MarshaledResponse response = generate ? generator.apply(request) : MarshaledResponse.fromStatusCode(403);
					writer.accept(response.copy().headers(headers -> headers.put("X-Interceptor", List.of("yes"))).finish());
				}
			}).build(), simulator -> {
				MarshaledResponse response = simulator.performHttpRequest(Request.fromPath(HttpMethod.GET, "/logical")).getMarshaledResponse();
				assertEquals(1, calls.get());
				assertEquals(List.of("yes"), response.getHeaders().get("X-Finalized"));
				assertEquals(List.of("yes"), response.getHeaders().get("X-Interceptor"));
			});
		}
	}

	@Test
	void customHeadProjectionPreservesFinalizedMetadataWithoutRepeatingThePostprocessor() {
		AtomicInteger calls = new AtomicInteger();
		ResponseMarshaler marshaler = ResponseMarshaler.builder().postProcessor(response -> {
			calls.incrementAndGet();
			return response.copy().headers(headers -> headers.put("X-Finalized", List.of("yes")))
					.cookies(cookies -> cookies.add(ResponseCookie.withName("post").value("once").build())).finish();
		}).headHandler((request, response) -> response.copy().withoutBody()
				.headers(headers -> headers.put("Content-Length", List.of("42"))).finish()).build();
		SokletSimulator.run(config(new Resource(), 0).responseMarshaler(marshaler).build(), simulator -> {
			MarshaledResponse response = simulator.performHttpRequest(Request.withPath(HttpMethod.HEAD, "/logical")
					.headers(Map.of("Origin", List.of("https://example.test"))).build()).getMarshaledResponse();
			assertEquals(List.of("42"), response.getHeaders().get("Content-Length"));
			assertTrue(response.getHeaders().containsKey("Access-Control-Allow-Origin"));
			assertEquals(List.of("yes"), response.getHeaders().get("X-Finalized"));
			assertEquals(1, response.getCookies().size());
			assertEquals(1, calls.get());
		});
	}

	@Test
	void directMarshalingHasAnExplicitFinalizationPhase() {
		AtomicInteger calls = new AtomicInteger();
		ResponseMarshaler marshaler = ResponseMarshaler.builder().postProcessor(response -> { calls.incrementAndGet(); return response; }).build();
		MarshaledResponse raw = marshaler.forNotFound(Request.fromPath(HttpMethod.GET, "/missing"));
		assertEquals(0, calls.get());
		MarshaledResponse finished = marshaler.postProcess(raw);
		assertEquals(1, calls.get());
		assertNotNull(finished);
	}

	@Test
	void opaqueFileWrapperRetainsItsOwnedPredecessorUntilConsumption(@TempDir Path directory) throws Exception {
		Path path = Files.writeString(directory.resolve("body"), "body");
		Resource resource = new Resource();
		try (FileChannel channel = FileChannel.open(path); DelegatingChannel wrapper = new DelegatingChannel(channel)) {
			resource.response = response(channel);
			SokletSimulator.run(config(resource, 0).requestInterceptor(wrapFile(wrapper)).build(), simulator -> {
				MarshaledResponse response = simulator.performHttpRequest(Request.fromPath(HttpMethod.GET, "/body")).getMarshaledResponse();
				assertFalse(channel.isOpen(), "Finite simulation consumed the owned body before returning");
				assertArrayEquals("body".getBytes(StandardCharsets.UTF_8), response.bodyBytesOrEmpty());
				assertFalse(channel.isOpen());
				assertFalse(wrapper.isOpen());
				response.close();
				assertEquals(1, wrapper.closes.get());
			});
		}
	}

	@Test
	void invalidCopiesLeaveTheOriginalOwnedBodyUsable(@TempDir Path directory) throws Exception {
		Path path = Files.writeString(directory.resolve("body"), "body");
		try (FileChannel channel = FileChannel.open(path)) {
			MarshaledResponse original = response(channel);
			assertThrows(IllegalStateException.class, () -> original.copy().stream(stream -> {}).finish());
			assertTrue(channel.isOpen());
			assertArrayEquals("body".getBytes(StandardCharsets.UTF_8), original.bodyBytesOrEmpty());
			assertFalse(channel.isOpen());
		}
	}

	@Test
	void simulatorRetainedCleanupDoesNotHideTheProducerFailure(@TempDir Path directory) throws Exception {
		Path path = Files.writeString(directory.resolve("body"), "body");
		Resource resource = new Resource();
		try (FileChannel raw = FileChannel.open(path); DelegatingChannel channel = new DelegatingChannel(raw, true)) {
			resource.response = response(channel);
			SokletSimulator.run(config(resource, 0).requestInterceptor(wrapStream(channel, true, null)).build(), simulator -> {
				IllegalStateException failure = assertThrows(IllegalStateException.class,
						() -> simulator.performHttpRequest(Request.fromPath(HttpMethod.GET, "/body")));
				assertInstanceOf(IOException.class, failure.getCause());
				assertEquals("replacement failed", failure.getCause().getMessage());
				assertEquals(1, failure.getSuppressed().length);
				assertEquals(1, channel.closes.get());
			});
		}
	}

	@Test
	void copyingAnOwnedFileToAStreamDefersClosureThroughSuccessAndFailure(@TempDir Path directory) throws Exception {
		Path path = Files.writeString(directory.resolve("body"), "body");
		for (boolean fail : List.of(false, true)) {
			Resource resource = new Resource();
			try (FileChannel channel = FileChannel.open(path)) {
				resource.response = response(channel);
				SokletSimulator.run(config(resource, 0).requestInterceptor(wrapStream(channel, fail, null)).build(), simulator -> {
					if (fail) assertThrows(IllegalStateException.class,
							() -> simulator.performHttpRequest(Request.fromPath(HttpMethod.GET, "/body")));
					else assertArrayEquals("body".getBytes(StandardCharsets.UTF_8),
							simulator.performHttpRequest(Request.fromPath(HttpMethod.GET, "/body")).getMarshaledResponse().bodyBytesOrEmpty());
					assertFalse(channel.isOpen());
				});
			}
		}
	}

	@Test
	void postprocessorCanUseAnOwnedChannelThroughAFreshStreamingResponse(@TempDir Path directory) throws Exception {
		Path path = Files.writeString(directory.resolve("body"), "body");
		Resource resource = new Resource();
		try (FileChannel channel = FileChannel.open(path)) {
			resource.response = response(channel);
			ResponseMarshaler marshaler = ResponseMarshaler.builder()
					.resourceMethodHandler((request, logical, method) -> resource.response)
					.postProcessor(original -> MarshaledResponse.withStatusCode(200).stream(stream -> {
						assertTrue(channel.isOpen());
						ByteBuffer bytes = ByteBuffer.allocate(4);
						channel.read(bytes, 0);
						bytes.flip();
						stream.write(bytes);
					}).build()).build();
			SokletSimulator.run(config(resource, 0).responseMarshaler(marshaler).build(), simulator -> {
				assertArrayEquals("body".getBytes(StandardCharsets.UTF_8),
						simulator.performHttpRequest(Request.fromPath(HttpMethod.GET, "/logical")).getMarshaledResponse().bodyBytesOrEmpty());
				assertFalse(channel.isOpen());
			});
		}
	}

	@Test
	void customTransportClosesRetainedOwnedChannelsWithoutClosingABorrowedWrapper(@TempDir Path directory) throws Exception {
		Path path = Files.writeString(directory.resolve("body"), "body");
		Resource resource = new Resource();
		try (FileChannel raw = FileChannel.open(path); DelegatingChannel source = new DelegatingChannel(raw);
				DelegatingChannel wrapper = new DelegatingChannel(source)) {
			resource.response = response(source);
			MarshaledResponse transformed = ResponseBodyCleanup.transform(resource.response,
					original -> original.copy().body(wrapper, 0L, 4L, false).finish());
			assertTrue(source.isOpen());
			ByteBuffer buffer = ByteBuffer.allocate(4);
			transformed.getBody().map(body -> (MarshaledResponseBody.FileChannel) body).orElseThrow().getChannel().read(buffer, 0);
			assertArrayEquals("body".getBytes(StandardCharsets.UTF_8), buffer.array());
			transformed.close();
			transformed.close();
			assertFalse(source.isOpen());
			assertTrue(wrapper.isOpen());
			assertEquals(1, source.closes.get());
		}
	}

	@Test
	void wireFiniteAndStreamingWrappersDeliverTheirBytesAndCloseChannels(@TempDir Path directory) throws Exception {
		Path path = Files.writeString(directory.resolve("body"), "body");
		for (boolean streaming : List.of(false, true)) {
			Resource resource = new Resource();
			try (FileChannel channel = FileChannel.open(path); DelegatingChannel wrapper = new DelegatingChannel(channel)) {
				resource.response = response(channel);
				int port = TestSupport.findFreePort();
				try (Soklet app = Soklet.fromConfig(config(resource, port).requestInterceptor(streaming
						? wrapStream(channel, false, null) : wrapFile(wrapper)).build())) {
					app.start();
					String wire;
					try (Socket socket = new Socket("127.0.0.1", port)) {
						socket.setSoTimeout(5000);
						socket.getOutputStream().write("GET /body HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
						wire = new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
					}
					assertTrue(wire.startsWith("HTTP/1.1 200"), wire);
					assertTrue(wire.contains("body"), wire);
					awaitClosed(channel);
				}
			}
		}
	}

	@Test
	void disconnectClosesAnOwnedPredecessorOfAStreamingReplacement(@TempDir Path directory) throws Exception {
		Path path = Files.writeString(directory.resolve("body"), "body");
		Resource resource = new Resource();
		CountDownLatch producing = new CountDownLatch(1);
		try (FileChannel channel = FileChannel.open(path)) {
			resource.response = response(channel);
			int port = TestSupport.findFreePort();
			try (Soklet app = Soklet.fromConfig(config(resource, port).requestInterceptor(wrapStream(channel, false, producing)).build())) {
				app.start();
				try (Socket socket = new Socket("127.0.0.1", port)) {
					socket.setSoTimeout(5000);
					socket.getOutputStream().write("GET /body HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
					assertTrue(producing.await(5, TimeUnit.SECONDS));
					assertTrue(channel.isOpen());
					socket.setSoLinger(true, 0);
				}
				awaitClosed(channel);
			}
		}
	}

	private static void awaitClosed(FileChannel channel) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (channel.isOpen() && System.nanoTime() < deadline) Thread.sleep(5);
		assertFalse(channel.isOpen());
	}

	private static RequestInterceptor wrapFile(FileChannel wrapper) {
		return new RequestInterceptor() {
			@Override public void interceptRequest(ServerType type, Request request, ResourceMethod method,
					Function<Request, MarshaledResponse> generator, Consumer<MarshaledResponse> writer) {
				MarshaledResponse original = generator.apply(request);
				writer.accept(MarshaledResponse.withStatusCode(original.getStatusCode()).headers(original.getHeaders())
						.cookies(original.getCookies()).body(wrapper, 0L, 4L, true).build());
			}
		};
	}

	private static RequestInterceptor wrapStream(FileChannel channel, boolean fail, CountDownLatch producing) {
		return new RequestInterceptor() {
			@Override public void interceptRequest(ServerType type, Request request, ResourceMethod method,
					Function<Request, MarshaledResponse> generator, Consumer<MarshaledResponse> writer) {
				MarshaledResponse original = generator.apply(request);
				writer.accept(original.copy().withoutBody().headers(headers -> headers.remove("Content-Length")).stream(stream -> {
					assertTrue(channel.isOpen());
					ByteBuffer bytes = ByteBuffer.allocate(4);
					channel.read(bytes, 0);
					bytes.flip();
					stream.write(bytes);
					stream.flush();
					if (fail) throw new IOException("replacement failed");
					if (producing != null) {
						producing.countDown();
						new CountDownLatch(1).await();
					}
				}).finish());
			}
		};
	}

	private static MarshaledResponse response(FileChannel channel) {
		return MarshaledResponse.withStatusCode(200).body(channel, 0L, 4L, true).build();
	}

	private static SokletConfig.Builder config(Resource resource, int port) {
		return SokletConfig.withHttpServer(HttpServer.withPort(port).host("127.0.0.1").build())
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Resource.class)))
				.instanceProvider(new InstanceProvider() { @Override public <T> T provide(Class<T> type) { return type.cast(resource); } })
				.corsAuthorizer(CorsAuthorizer.acceptAllInstance())
				.lifecycleObserver(new LifecycleObserver() { @Override public void didReceiveLogEvent(LogEvent event) {} })
				.lifecyclePolicy(LifecyclePolicy.builder().startupTimeout(Duration.ofSeconds(5)).startupCancelationTimeout(Duration.ofSeconds(1))
						.gracefulShutdownTimeout(Duration.ofSeconds(1)).forcedShutdownTimeout(Duration.ofSeconds(2)).build());
	}

	public static class Resource {
		MarshaledResponse response = MarshaledResponse.withStatusCode(200).body("body".getBytes(StandardCharsets.UTF_8)).build();
		@GET("/body") public MarshaledResponse body() { return this.response; }
		@GET("/logical") public String logical() { return "body"; }
		@GET("/crash") public String crash() { throw new IllegalStateException("handler failed"); }
	}

	static final class DelegatingChannel extends FileChannel {
		final FileChannel delegate;
		final AtomicInteger closes = new AtomicInteger();
		final boolean failClose;
		DelegatingChannel(FileChannel delegate) { this(delegate, false); }
		DelegatingChannel(FileChannel delegate, boolean failClose) { this.delegate = delegate; this.failClose = failClose; }
		@Override protected void implCloseChannel() throws IOException {
			closes.incrementAndGet(); delegate.close();
			if (failClose) throw new IOException("retained close failed");
		}
		@Override public int read(ByteBuffer buffer) throws IOException { return delegate.read(buffer); }
		@Override public long read(ByteBuffer[] buffers, int offset, int length) throws IOException { return delegate.read(buffers, offset, length); }
		@Override public int write(ByteBuffer buffer) throws IOException { return delegate.write(buffer); }
		@Override public long write(ByteBuffer[] buffers, int offset, int length) throws IOException { return delegate.write(buffers, offset, length); }
		@Override public long position() throws IOException { return delegate.position(); }
		@Override public FileChannel position(long position) throws IOException { delegate.position(position); return this; }
		@Override public long size() throws IOException { return delegate.size(); }
		@Override public FileChannel truncate(long size) throws IOException { delegate.truncate(size); return this; }
		@Override public void force(boolean metadata) throws IOException { delegate.force(metadata); }
		@Override public long transferTo(long position, long count, WritableByteChannel target) throws IOException { return delegate.transferTo(position, count, target); }
		@Override public long transferFrom(ReadableByteChannel source, long position, long count) throws IOException { return delegate.transferFrom(source, position, count); }
		@Override public int read(ByteBuffer buffer, long position) throws IOException { return delegate.read(buffer, position); }
		@Override public int write(ByteBuffer buffer, long position) throws IOException { return delegate.write(buffer, position); }
		@Override public MappedByteBuffer map(MapMode mode, long position, long size) throws IOException { return delegate.map(mode, position, size); }
		@Override public FileLock lock(long position, long size, boolean shared) throws IOException { return delegate.lock(position, size, shared); }
		@Override public FileLock tryLock(long position, long size, boolean shared) throws IOException { return delegate.tryLock(position, size, shared); }
	}
}
