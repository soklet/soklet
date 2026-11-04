/*
 * Copyright 2022-2026 Revetware LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.soklet;

import com.soklet.annotation.PathParameter;
import com.soklet.annotation.SseEventSource;
import com.soklet.exception.IllegalRequestException;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.annotation.concurrent.NotThreadSafe;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.FileChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

@NotThreadSafe
@Timeout(180)
class SseHandshakeValidationTests {
	private static final LifecyclePolicy POLICY = LifecyclePolicy.builder()
			.startupTimeout(Duration.ofSeconds(5))
			.startupCancelationTimeout(Duration.ofSeconds(1))
			.gracefulShutdownTimeout(Duration.ofSeconds(2))
			.forcedShutdownTimeout(Duration.ofSeconds(1)).build();

	@Test
	void originalAcceptedContentTypeFailsWithDiagnosticWhileInterceptorsCanDeny()
			throws Exception {
		for (boolean live : List.of(false, true)) {
			Fixture fixture = new Fixture(ResponseMarshaler.defaultInstance());
			fixture.run(live, () -> {
				for (String mode : List.of("json", "empty", "multiple", "comma")) {
					Outcome result = fixture.request(mode, Map.of(), null);
					assertEquals(500, result.status, mode);
					assertFalse(result.accepted, mode);
				}
				for (String mode : List.of("default", "case"))
					assertTrue(fixture.request(mode, Map.of(), null).accepted, mode);
				Outcome denied = fixture.request("denial", Map.of(), null);
				assertEquals(200, denied.status);
				assertFalse(denied.accepted);
				assertEquals("denied", denied.body);
				await(() -> fixture.failures.size() == 5 && fixture.resource.initializers.get() == 2);
				assertEquals(7, fixture.resource.calls.get());
				for (Failure failure : fixture.failures) {
					if (failure.path.endsWith("/denial")) {
						assertEquals(SseConnection.HandshakeFailureReason.HANDSHAKE_REJECTED, failure.reason);
						assertNull(failure.cause);
					} else {
						assertEquals(SseConnection.HandshakeFailureReason.INTERNAL_ERROR, failure.reason);
						assertInstanceOf(IllegalArgumentException.class, failure.cause);
						assertTrue(failure.cause.getMessage().contains("Content-Type"));
					}
				}
				assertTrue(fixture.logs.stream().anyMatch(event -> event.getThrowable()
						.map(failure -> failure.getMessage() != null && failure.getMessage().contains("Content-Type")).orElse(false)));
			});
		}
	}

	@Test
	void bodyAndFramingAreRejectedBeforeHandlersInSimulationAndLive()
			throws Exception {
		List<Map<String, List<String>>> invalid = List.of(
				Map.of("Content-Length", List.of("1")),
				Map.of("Content-Length", List.of("+0")),
				Map.of("Content-Length", List.of("0", "0")),
				Map.of("Content-Length", List.of("0,0")),
				Map.of("Transfer-Encoding", List.of("chunked")),
				Map.of("Transfer-Encoding", List.of("")));
		for (boolean live : List.of(false, true)) {
			Fixture fixture = new Fixture(ResponseMarshaler.defaultInstance());
			fixture.run(live, () -> {
				for (Map<String, List<String>> headers : invalid) {
					Outcome result = fixture.request("default", headers, null);
					assertEquals(400, result.status, headers.toString());
					assertFalse(result.accepted);
				}
				if (!live) {
					for (Map<String, List<String>> headers : List.of(
							Map.<String, List<String>>of(), Map.of("Content-Length", List.of("0"))))
						assertEquals(400, fixture.request("default", headers, new byte[]{1}).status);
				}
				assertEquals(0, fixture.resource.calls.get());
				assertEquals(0, fixture.interceptions.get());
				for (Map<String, List<String>> headers : List.of(Map.<String, List<String>>of(),
						Map.of("Content-Length", List.of("0")), Map.of("Content-Length", List.of("000")),
						Map.of("Content-Length", List.of(" \t0\t "))))
					assertTrue(fixture.request("default", headers, new byte[0]).accepted, headers.toString());
				await(() -> fixture.failures.size() == (live ? 6 : 8) && fixture.resource.initializers.get() == 4);
				assertEquals(4, fixture.resource.calls.get());
				assertEquals(4, fixture.interceptions.get());
				for (Failure failure : fixture.failures) {
					assertEquals(SseConnection.HandshakeFailureReason.HANDSHAKE_REJECTED, failure.reason);
					assertInstanceOf(IllegalRequestException.class, failure.cause);
				}
			});
		}
	}

	@Test
	void admissionFailureUsesCustomThrowableHandlerAndPostProcessorExactlyOnce()
			throws Exception {
		for (boolean live : List.of(false, true)) {
			AtomicInteger handled = new AtomicInteger();
			AtomicInteger processed = new AtomicInteger();
			ResponseMarshaler marshaler = ResponseMarshaler.builder().throwableHandler((request, failure, method) -> {
				assertInstanceOf(IllegalRequestException.class, failure);
				assertNull(method);
				handled.incrementAndGet();
				return MarshaledResponse.withStatusCode(422).body("custom".getBytes(StandardCharsets.UTF_8)).build();
			}).postProcessor(response -> {
				processed.incrementAndGet();
				return response.copy().headers(headers -> headers.put("X-Processed", List.of("once"))).finish();
			}).build();
			Fixture fixture = new Fixture(marshaler);
			fixture.run(live, () -> {
				Outcome result = fixture.request("default", Map.of("Content-Length", List.of("1")), null);
				assertEquals(422, result.status);
				assertEquals("custom", result.body);
				assertEquals(List.of("once"), result.headers.get("X-Processed"));
				assertEquals(1, handled.get());
				assertEquals(1, processed.get());
				assertEquals(0, fixture.resource.calls.get());
				assertEquals(0, fixture.interceptions.get());
				await(() -> fixture.failures.size() == 1);
			});
		}
	}

	@Test
	void failedAdmissionPreparationReleasesOnlyOwnedChannels(@TempDir Path directory)
			throws Exception {
		Path file = Files.writeString(directory.resolve("response.txt"), "custom");
		for (boolean owned : List.of(false, true)) {
			for (String phase : List.of("invalid-status", "runtime-post", "checked-post")) {
				Throwable expected = phase.equals("checked-post")
						? new Exception("checked post-process canary") : new IllegalStateException("post-process canary");
				try (FileChannel channel = FileChannel.open(file)) {
					ResponseMarshaler marshaler = ResponseMarshaler.builder()
							.throwableHandler((request, failure, method) -> MarshaledResponse.withStatusCode(phase.equals("invalid-status") ? 100 : 422)
									.body(channel, 0L, 6L, owned).build())
							.postProcessor(response -> { if (!phase.equals("invalid-status")) throw unchecked(expected); return response; }).build();
					Fixture fixture = new Fixture(marshaler);
					fixture.run(false, () -> {
						Throwable observed = assertThrows(Throwable.class,
								() -> fixture.request("default", Map.of("Content-Length", List.of("1")), null));
						if (!phase.equals("invalid-status")) assertSame(expected, observed);
						else assertInstanceOf(IllegalStateException.class, observed);
						assertEquals(!owned, channel.isOpen());
						await(() -> fixture.failures.size() == 1);
					});
				}
			}
		}
	}

	@Test
	void failedErrorMarshalerStillReportsAdmissionRejection() throws Exception {
		IllegalStateException expected = new IllegalStateException("error-marshaler canary");
		Fixture fixture = new Fixture(ResponseMarshaler.builder().throwableHandler((request, failure, method) -> { throw expected; }).build());
		fixture.run(false, () -> {
			assertSame(expected, assertThrows(IllegalStateException.class,
					() -> fixture.request("default", Map.of("Content-Length", List.of("1")), null)));
			await(() -> fixture.failures.size() == 1);
			assertEquals(SseConnection.HandshakeFailureReason.HANDSHAKE_REJECTED, fixture.failures.get(0).reason);
			assertInstanceOf(IllegalRequestException.class, fixture.failures.get(0).cause);
		});
	}

	@SuppressWarnings("unchecked")
	private static <T extends Throwable> RuntimeException unchecked(Throwable failure) throws T {
		throw (T) failure;
	}

	private static void await(BooleanSupplier condition) {
		long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
		while (!condition.getAsBoolean()) {
			long remaining = deadline - System.nanoTime();
			assertTrue(remaining > 0L, "SSE observation did not complete before its deadline");
			LockSupport.parkNanos(Math.min(remaining, Duration.ofMillis(1).toNanos()));
		}
	}

	private record Outcome(int status, Map<String, List<String>> headers, String body, boolean accepted) {}
	private record Failure(String path, SseConnection.HandshakeFailureReason reason, Throwable cause) {}
	@FunctionalInterface private interface Action { void run() throws Exception; }

	private static final class Fixture {
		private final Resource resource = new Resource();
		private final AtomicInteger interceptions = new AtomicInteger();
		private final List<Failure> failures = new CopyOnWriteArrayList<>();
		private final List<LogEvent> logs = new CopyOnWriteArrayList<>();
		private final DefaultSseServer server;
		private final SokletConfig config;
		private Simulator simulator;
		private InetSocketAddress address;

		private Fixture(ResponseMarshaler marshaler) {
			this.server = (DefaultSseServer) SseServer.withPort(0).host("127.0.0.1")
					.requestHandlerTimeout(Duration.ofSeconds(5)).build();
			this.config = SokletConfig.withSseServer(this.server).resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Resource.class)))
					.instanceProvider(new InstanceProvider() {
						@Override public <T> T provide(@NonNull Class<T> type) { return type.cast(resource); }
					}).responseMarshaler(marshaler).requestInterceptor(new RequestInterceptor() {
						@Override public void interceptRequest(ServerType type, Request request, ResourceMethod method,
								Function<Request, MarshaledResponse> generator, Consumer<MarshaledResponse> writer) {
							interceptions.incrementAndGet();
							MarshaledResponse response = generator.apply(request);
							writer.accept(request.getPath().endsWith("/denial")
									? MarshaledResponse.withStatusCode(200).headers(Map.of("Content-Type", List.of("application/json")))
											.body("denied".getBytes(StandardCharsets.UTF_8)).build() : response);
						}
					}).lifecycleObserver(new LifecycleObserver() {
						@Override public void didReceiveLogEvent(LogEvent event) { logs.add(event); }
						@Override public void didFailToEstablishSseConnection(Request request, ResourceMethod method,
								SseConnection.HandshakeFailureReason reason, Throwable cause) { failures.add(new Failure(request.getPath(), reason, cause)); }
					}).lifecyclePolicy(POLICY).build();
		}

		private void run(boolean live, Action action) throws Exception {
			ShutdownResult result;
			if (live) {
				result = Soklet.run(this.config, () -> {
					Field field = DefaultSseServer.class.getDeclaredField("serverSocketChannel");
					field.setAccessible(true);
					this.address = (InetSocketAddress) ((ServerSocketChannel) field.get(this.server)).getLocalAddress();
					action.run();
				});
				try (ServerSocket rebound = new ServerSocket()) { rebound.setReuseAddress(true); rebound.bind(this.address); }
			} else {
				result = SokletSimulator.run(SimulatorConfig.fromSokletConfig(this.config), simulator -> {
					this.simulator = simulator;
					action.run();
				});
			}
			assertTrue(result.isComplete());
		}

		private Outcome request(String mode, Map<String, List<String>> headers, byte[] body) throws Exception {
			String path = "/events/" + mode;
			if (this.simulator != null) {
				SseRequestResult result = this.simulator.performSseRequest(Request.withPath(HttpMethod.GET, path).headers(headers).body(body).build());
				HttpRequestResult http;
				boolean accepted = result instanceof SseRequestResult.HandshakeAccepted;
				if (result instanceof SseRequestResult.HandshakeAccepted success) { http = success.getHttpRequestResult(); success.close(); }
				else if (result instanceof SseRequestResult.HandshakeRejected rejected) http = rejected.getHttpRequestResult();
				else http = ((SseRequestResult.RequestFailed) result).getHttpRequestResult();
				return new Outcome(http.getMarshaledResponse().getStatusCode(), http.getMarshaledResponse().getHeaders(),
						new String(http.getMarshaledResponse().bodyBytesOrEmpty(), StandardCharsets.UTF_8), accepted);
			}
			try (Socket socket = new Socket()) {
				socket.connect(this.address, 2_000);
				long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
				StringBuilder raw = new StringBuilder("GET " + path + " HTTP/1.1\r\nHost: localhost\r\n");
				headers.forEach((name, values) -> values.forEach(value -> raw.append(name).append(": ").append(value).append("\r\n")));
				socket.getOutputStream().write(raw.append("\r\n").toString().getBytes(StandardCharsets.ISO_8859_1));
				ByteArrayOutputStream bytes = new ByteArrayOutputStream();
				while (true) {
					bytes.write(read(socket, deadline));
					byte[] current = bytes.toByteArray(); int n = current.length;
					assertTrue(n < 65_536);
					if (n >= 4 && current[n-4] == 13 && current[n-3] == 10 && current[n-2] == 13 && current[n-1] == 10) break;
				}
				String[] lines = bytes.toString(StandardCharsets.ISO_8859_1).split("\r\n");
				int status = Integer.parseInt(lines[0].split(" ")[1]);
				Map<String, List<String>> responseHeaders = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
				for (int i=1; i<lines.length; i++) { int colon=lines[i].indexOf(':'); responseHeaders.computeIfAbsent(lines[i].substring(0,colon), ignored -> new ArrayList<>()).add(lines[i].substring(colon+1).trim()); }
				ByteArrayOutputStream payload = new ByteArrayOutputStream();
				int length = Integer.parseInt(responseHeaders.getOrDefault("Content-Length", List.of("0")).get(0));
				for (int i=0; i<length; i++) payload.write(read(socket, deadline));
				String contentType = responseHeaders.getOrDefault("Content-Type", List.of("")).get(0).split(";",2)[0].trim();
				return new Outcome(status, responseHeaders, payload.toString(StandardCharsets.UTF_8),
						status == 200 && contentType.equalsIgnoreCase("text/event-stream") && !responseHeaders.containsKey("Content-Length"));
			}
		}

		private static int read(Socket socket, long deadline) throws Exception {
			long remaining = deadline - System.nanoTime();
			assertTrue(remaining > 0L, "SSE response exceeded its absolute deadline");
			socket.setSoTimeout((int) Math.max(1L, Math.min(2_000L, (remaining + 999_999L) / 1_000_000L)));
			int value = socket.getInputStream().read();
			assertTrue(value >= 0, "SSE response ended before expected headers/body");
			return value;
		}
	}

	public static final class Resource {
		private final AtomicInteger calls = new AtomicInteger();
		private final AtomicInteger initializers = new AtomicInteger();
		@SseEventSource("/events/{mode}")
		public SseHandshakeResult events(@PathParameter String mode) {
			this.calls.incrementAndGet();
			Map<String, List<String>> headers = switch (mode) {
				case "json" -> Map.of("Content-Type", List.of("application/json"));
				case "empty" -> Map.of("Content-Type", List.of());
				case "multiple" -> Map.of("Content-Type", List.of("text/event-stream", "text/event-stream"));
				case "comma" -> Map.of("Content-Type", List.of("text/event-stream, text/event-stream"));
				case "case" -> Map.of("content-type", List.of("Text/Event-Stream;charset=utf-8"));
				default -> Map.of();
			};
			return SseHandshakeResult.Accepted.builder().headers(headers)
					.clientInitializer(unicaster -> this.initializers.incrementAndGet()).build();
		}
	}
}
