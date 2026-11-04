/*
 * Copyright 2022-2026 Revetware LLC.
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class LogicalResponseBodyMisuseTests {
	private static final String CANARY = "logical-response-private-canary";
	private static final byte[] CONTENT = "payload".getBytes(StandardCharsets.UTF_8);

	@Test
	void defaultFallbackRejectsAllFiveHandleTypesWithoutRenderingThem(@TempDir Path directory) throws Exception {
		try (Bodies bodies = new Bodies(directory)) {
			String diagnostic = null;
			for (Object body : bodies.values()) {
				Response response = Response.withStatusCode(200).body(body).build();
				IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
						() -> marshal(ResponseMarshaler.defaultInstance(), response));
				assertTrue(failure.getMessage().contains("MarshaledResponse"));
				assertTrue(failure.getMessage().contains("streamingResponseBody"));
				assertFalse(failure.getMessage().contains(CANARY));
				assertNull(failure.getCause());
				if (diagnostic == null) diagnostic = failure.getMessage();
				else assertEquals(diagnostic, failure.getMessage(), "Diagnostics must not depend on an object or path value");
			}
			bodies.assertUntouched();
		}
	}

	@Test
	void explicitResourceMethodHandlerRetainsLogicalSerializationControl(@TempDir Path directory) throws Exception {
		try (Bodies bodies = new Bodies(directory)) {
			for (Object body : bodies.values()) {
				AtomicInteger calls = new AtomicInteger();
				ResponseMarshaler marshaler = ResponseMarshaler.builder().resourceMethodHandler((request, response, method) -> {
					calls.incrementAndGet();
					assertSame(body, response.getBody().orElseThrow());
					return MarshaledResponse.withStatusCode(200).body(CONTENT).build();
				}).build();
				assertArrayEquals(CONTENT, marshal(marshaler, Response.withStatusCode(200).body(body).build()).bodyBytesOrEmpty());
				assertEquals(1, calls.get());
			}
			bodies.assertUntouched();
		}
	}

	@Test
	void customResponseMarshalerRetainsLogicalSerializationControl(@TempDir Path directory) throws Exception {
		try (Bodies bodies = new Bodies(directory)) {
			for (Object body : bodies.values()) {
				AtomicInteger calls = new AtomicInteger();
				ResponseMarshaler marshaler = (ResponseMarshaler) Proxy.newProxyInstance(
						ResponseMarshaler.class.getClassLoader(), new Class<?>[]{ResponseMarshaler.class}, (proxy, method, arguments) -> {
					if (method.getName().equals("forResourceMethod")) {
						calls.incrementAndGet();
						assertSame(body, ((Response) arguments[1]).getBody().orElseThrow());
						return MarshaledResponse.withStatusCode(200).body(CONTENT).build();
					}
					try { return method.invoke(ResponseMarshaler.defaultInstance(), arguments); }
					catch (InvocationTargetException failure) { throw failure.getCause(); }
				});
				assertArrayEquals(CONTENT, marshal(marshaler, Response.withStatusCode(200).body(body).build()).bodyBytesOrEmpty());
				assertEquals(1, calls.get());
			}
			bodies.assertUntouched();
		}
	}

	@Test
	void ordinaryLogicalBodiesStillUseTheDefaultMarshaler() throws Exception {
		for (Object body : List.of("text", Integer.valueOf(42), new Object() {
			@Override public String toString() { return "logical value"; }
		})) {
			MarshaledResponse response = marshal(ResponseMarshaler.defaultInstance(), Response.withStatusCode(200).body(body).build());
			assertEquals(body.toString(), new String(response.bodyBytesOrEmpty(), StandardCharsets.UTF_8));
			assertEquals(List.of("text/plain; charset=UTF-8"), response.getHeaders().get("Content-Type"));
		}
		MarshaledResponse binary = marshal(ResponseMarshaler.defaultInstance(), Response.withStatusCode(200).body(CONTENT).build());
		assertArrayEquals(CONTENT, binary.bodyBytesOrEmpty());
		assertEquals(List.of("application/octet-stream"), binary.getHeaders().get("Content-Type"));
		assertTrue(marshal(ResponseMarshaler.defaultInstance(), Response.fromStatusCode(200)).getBody().isEmpty());
	}

	@Test
	void runtimeReportsMisuseAs500AndProperFiniteAndStreamingResponsesStillWork(@TempDir Path directory) throws Exception {
		try (Bodies bodies = new Bodies(directory)) {
			Resource resource = new Resource();
			List<Throwable> observedFailures = new ArrayList<>();
			List<LogEvent> logs = new ArrayList<>();
			ShutdownResult shutdown = SokletSimulator.run(SimulatorConfig.builder().httpServer()
					.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Resource.class)))
					.instanceProvider(new InstanceProvider() {
						@Override public <T> T provide(Class<T> type) { return type.cast(resource); }
					}).lifecycleObserver(new LifecycleObserver() {
						@Override public void didReceiveLogEvent(LogEvent event) { logs.add(event); }
						@Override public void didFinishRequestHandling(ServerType type, Request request, ResourceMethod method,
								MarshaledResponse response, Duration duration, List<Throwable> failures) { observedFailures.addAll(failures); }
					}).lifecyclePolicy(LifecyclePolicy.builder().startupTimeout(Duration.ofSeconds(5))
							.startupCancelationTimeout(Duration.ofSeconds(1)).gracefulShutdownTimeout(Duration.ofSeconds(2))
							.forcedShutdownTimeout(Duration.ofSeconds(1)).build()).build(), simulator -> {
				for (Object body : bodies.values()) {
					resource.value = Response.withStatusCode(200).body(body).build();
					MarshaledResponse response = simulator.performHttpRequest(Request.fromPath(HttpMethod.GET, "/body")).getMarshaledResponse();
					assertEquals(500, response.getStatusCode());
					assertFalse(new String(response.bodyBytesOrEmpty(), StandardCharsets.UTF_8).contains(CANARY));
				}
				assertEquals(5, observedFailures.size());
				for (Throwable failure : observedFailures) {
					assertInstanceOf(IllegalArgumentException.class, failure);
					assertTrue(failure.getMessage().contains("MarshaledResponse"));
					assertFalse(failure.getMessage().contains(CANARY));
				}
				assertTrue(logs.stream().anyMatch(event -> event.getThrowable()
						.filter(IllegalArgumentException.class::isInstance).isPresent()));
				bodies.assertUntouched();

				for (MarshaledResponse finite : List.of(
						MarshaledResponse.withStatusCode(200).body(bodies.path).build(),
						MarshaledResponse.withStatusCode(200).body(ByteBuffer.wrap(CONTENT)).build(),
						MarshaledResponse.withStatusCode(200).body(bodies.channel, 0L, (long) CONTENT.length, false).build())) {
					resource.value = finite;
					MarshaledResponse response = simulator.performHttpRequest(Request.fromPath(HttpMethod.GET, "/body")).getMarshaledResponse();
					assertEquals(200, response.getStatusCode());
					assertArrayEquals(CONTENT, response.bodyBytesOrEmpty());
				}
				resource.value = MarshaledResponse.withStatusCode(200).streamingResponseBody(
						StreamingResponseBody.fromInputStream(() -> new ByteArrayInputStream(CONTENT))).build();
				MarshaledResponse streamed = simulator.performHttpRequest(Request.fromPath(HttpMethod.GET, "/body")).getMarshaledResponse();
				assertEquals(200, streamed.getStatusCode());
				assertArrayEquals(CONTENT, streamed.bodyBytesOrEmpty());
			});
			assertTrue(shutdown.isComplete());
			assertTrue(bodies.channel.isOpen(), "A proper explicitly borrowed finite channel remains caller-owned too");
			assertEquals(0, bodies.input.closes.get());
		}
	}

	private static MarshaledResponse marshal(ResponseMarshaler marshaler, Response response) throws Exception {
		ResourceMethod method = ResourceMethod.fromComponents(HttpMethod.GET, ResourcePathDeclaration.fromPath("/body"),
				Resource.class.getMethod("body"), false);
		return marshaler.forResourceMethod(Request.fromPath(HttpMethod.GET, "/body"), response, method);
	}

	public static final class Resource {
		Object value;
		@GET("/body") public Object body() { return this.value; }
	}

	private static final class Bodies implements AutoCloseable {
		final Path path;
		final File file;
		final ByteBuffer buffer = ByteBuffer.wrap(CONTENT.clone());
		final TrackedInputStream input = new TrackedInputStream();
		final FileChannel channel;
		Bodies(Path directory) throws Exception {
			this.path = Files.write(directory.resolve(CANARY), CONTENT);
			this.file = new File(this.path.toString()) {
				@Override public String toString() { throw new AssertionError("File.toString must not be called"); }
			};
			this.buffer.position(2);
			this.channel = FileChannel.open(this.path);
			this.channel.position(2);
		}
		List<Object> values() { return List.of(this.path, this.file, this.buffer, this.input, this.channel); }
		void assertUntouched() throws Exception {
			assertTrue(Files.exists(this.path));
			assertEquals(2, this.buffer.position());
			assertEquals(CONTENT.length, this.buffer.limit());
			assertArrayEquals(CONTENT, this.buffer.array());
			assertEquals(0, this.input.reads.get());
			assertEquals(0, this.input.closes.get());
			assertTrue(this.channel.isOpen());
			assertEquals(2L, this.channel.position());
		}
		@Override public void close() throws Exception { this.channel.close(); this.input.close(); }
	}

	private static final class TrackedInputStream extends ByteArrayInputStream {
		final AtomicInteger reads = new AtomicInteger();
		final AtomicInteger closes = new AtomicInteger();
		TrackedInputStream() { super(CONTENT); }
		@Override public synchronized int read() { this.reads.incrementAndGet(); return super.read(); }
		@Override public synchronized int read(byte[] bytes, int offset, int count) { this.reads.incrementAndGet(); return super.read(bytes, offset, count); }
		@Override public void close() { this.closes.incrementAndGet(); }
		@Override public String toString() { throw new AssertionError("InputStream.toString must not be called"); }
	}
}
