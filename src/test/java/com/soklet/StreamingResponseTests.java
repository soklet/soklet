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
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.annotation.concurrent.ThreadSafe;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.soklet.TestSupport.connectWithRetry;
import static com.soklet.TestSupport.findFreePort;
import static com.soklet.TestSupport.readAll;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class StreamingResponseTests {
	@Test
	public void writer_streams_over_http_chunked_transfer_and_reports_termination() throws Exception {
		int port = findFreePort();
		CountDownLatch terminatedLatch = new CountDownLatch(1);
		AtomicReference<StreamingResponseCancelationReason> cancelationReasonRef = new AtomicReference<>();
		AtomicReference<Throwable> throwableRef = new AtomicReference<>();

		SokletConfig config = SokletConfig.withHttpServer(HttpServer.withPort(port)
						.requestTimeout(Duration.ofSeconds(5))
						.build())
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(StreamingResource.class)))
				.lifecycleObserver(new LifecycleObserver() {
					@Override
					public void didTerminateResponseStream(@NonNull ServerType serverType,
																								 @NonNull Request request,
																								 @Nullable ResourceMethod resourceMethod,
																								 @NonNull MarshaledResponse marshaledResponse,
																								 @NonNull Duration streamDuration,
																								 @Nullable StreamingResponseCancelationReason cancelationReason,
																								 @Nullable Throwable throwable) {
						cancelationReasonRef.set(cancelationReason);
						throwableRef.set(throwable);
						terminatedLatch.countDown();
					}

					@Override
					public void didReceiveLogEvent(@NonNull LogEvent logEvent) {
						// Keep test output quiet.
					}
				})
				.build();

		try (Soklet soklet = Soklet.fromConfig(config)) {
			soklet.start();

			HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/writer").openConnection();
			connection.setConnectTimeout(2_000);
			connection.setReadTimeout(2_000);

			Assertions.assertEquals(200, connection.getResponseCode());
			Assertions.assertEquals("chunked", connection.getHeaderField("Transfer-Encoding"));
			Assertions.assertNull(connection.getHeaderField("Content-Length"));
			Assertions.assertEquals("hello world", new String(readAll(connection.getInputStream()), StandardCharsets.UTF_8));
			Assertions.assertTrue(terminatedLatch.await(2, TimeUnit.SECONDS), "Stream termination lifecycle hook was not invoked");
			Assertions.assertNull(cancelationReasonRef.get());
			Assertions.assertNull(throwableRef.get());
		}
	}

	@Test
	public void http_1_0_streaming_request_is_rejected_without_chunked_transfer() throws Exception {
		int port = findFreePort();
		SokletConfig config = SokletConfig.withHttpServer(HttpServer.withPort(port)
						.requestTimeout(Duration.ofSeconds(5))
						.build())
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(StreamingResource.class)))
				.build();

		try (Soklet soklet = Soklet.fromConfig(config)) {
			soklet.start();

			try (Socket socket = connectWithRetry("127.0.0.1", port, 2_000)) {
				socket.setSoTimeout(2_000);
				writeRawRequest(socket, "GET /writer HTTP/1.0\r\n\r\n");

				String responseHeaders = readUntil(socket.getInputStream(), "\r\n\r\n", 8_192);

				Assertions.assertNotNull(responseHeaders);
				Assertions.assertTrue(responseHeaders.startsWith("HTTP/1.0 505 HTTP Version Not Supported"), responseHeaders);
				Assertions.assertTrue(responseHeaders.contains("Connection: close\r\n"), responseHeaders);
				Assertions.assertTrue(responseHeaders.contains("Content-Length: 0\r\n"), responseHeaders);
				Assertions.assertFalse(responseHeaders.contains("Transfer-Encoding:"), responseHeaders);
			}
		}
	}

	@Test
	public void input_stream_source_is_closed_when_timeout_cancels_stream() throws Exception {
		BlockingSourceResource.inputStreamClosedLatch = new CountDownLatch(1);

		assertBlockingSourceClosedOnTimeout("/blocking-input-stream", BlockingSourceResource.inputStreamClosedLatch);
	}

	@Test
	public void reader_source_is_closed_when_timeout_cancels_stream() throws Exception {
		BlockingSourceResource.readerClosedLatch = new CountDownLatch(1);

		assertBlockingSourceClosedOnTimeout("/blocking-reader", BlockingSourceResource.readerClosedLatch);
	}

	@Test
	public void simulator_materializes_streaming_response_body() {
		SokletConfig config = SokletConfig.withHttpServer(HttpServer.fromPort(0))
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(StreamingResource.class)))
				.build();

		Soklet.runSimulator(config, simulator -> {
			HttpRequestResult result = simulator.performHttpRequest(Request.withPath(HttpMethod.GET, "/input-stream").build());

			Assertions.assertFalse(result.getMarshaledResponse().isStreaming());
			Assertions.assertEquals("input stream", new String(result.getMarshaledResponse().bodyBytesOrEmpty(), StandardCharsets.UTF_8));
		});
	}

	@Test
	public void simulator_enforces_streaming_response_body_limit() {
		SokletConfig config = SokletConfig.withHttpServer(HttpServer.fromPort(0))
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(StreamingResource.class)))
				.build();

		SimulatorOptions simulatorOptions = SimulatorOptions.builder()
				.streamingResponseBodyLimitInBytes(4)
				.build();

		Assertions.assertThrows(IllegalStateException.class, () ->
				Soklet.runSimulator(config, simulatorOptions, simulator ->
						simulator.performHttpRequest(Request.withPath(HttpMethod.GET, "/writer").build())));
	}

	@Test
	public void reader_body_requires_explicit_charset_and_exposes_encoder_actions() {
		StreamingResponseBody.ReaderBody body = (StreamingResponseBody.ReaderBody) StreamingResponseBody.withReader(
						() -> new StringReader("reader"),
						StandardCharsets.UTF_8)
				.malformedInputAction(CodingErrorAction.REPLACE)
				.unmappableCharacterAction(CodingErrorAction.IGNORE)
				.bufferSizeInCharacters(16)
				.build();

		Assertions.assertEquals(StandardCharsets.UTF_8, body.getCharset());
		Assertions.assertEquals(Integer.valueOf(16), body.getBufferSizeInCharacters());
		Assertions.assertEquals(CodingErrorAction.REPLACE, body.getMalformedInputAction());
		Assertions.assertEquals(CodingErrorAction.IGNORE, body.getUnmappableCharacterAction());
		Assertions.assertEquals(CodingErrorAction.REPLACE, body.newEncoder().malformedInputAction());
		Assertions.assertEquals(CodingErrorAction.IGNORE, body.newEncoder().unmappableCharacterAction());
	}

	@Test
	public void publisher_streams_one_item_at_a_time_in_simulator() {
		SokletConfig config = SokletConfig.withHttpServer(HttpServer.fromPort(0))
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(StreamingResource.class)))
				.build();

		Soklet.runSimulator(config, simulator -> {
			HttpRequestResult result = simulator.performHttpRequest(Request.withPath(HttpMethod.GET, "/publisher").build());

			Assertions.assertEquals("published", new String(result.getMarshaledResponse().bodyBytesOrEmpty(), StandardCharsets.UTF_8));
		});
	}

	public static class StreamingResource {
		@GET("/writer")
		public MarshaledResponse writer() {
			return MarshaledResponse.withStatusCode(200)
					.headers(Map.of("Content-Type", Set.of("text/plain; charset=UTF-8")))
					.stream(StreamingResponseBody.fromWriter((output, context) -> {
						output.write("hello ".getBytes(StandardCharsets.UTF_8));
						output.flush();
						output.write(ByteBuffer.wrap("world".getBytes(StandardCharsets.UTF_8)));
					}))
					.build();
		}

		@GET("/input-stream")
		public MarshaledResponse inputStream() {
			return MarshaledResponse.withStatusCode(200)
					.headers(Map.of("Content-Type", Set.of("text/plain; charset=UTF-8")))
					.stream(StreamingResponseBody.fromInputStream(() ->
							new ByteArrayInputStream("input stream".getBytes(StandardCharsets.UTF_8))))
					.build();
		}

		@GET("/publisher")
		public MarshaledResponse publisher() {
			Flow.Publisher<ByteBuffer> publisher = subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
				private boolean completed;

				@Override
				public void request(long n) {
					if (this.completed)
						return;

					this.completed = true;
					subscriber.onNext(ByteBuffer.wrap("published".getBytes(StandardCharsets.UTF_8)));
					subscriber.onComplete();
				}

				@Override
				public void cancel() {
					this.completed = true;
				}
			});

			return MarshaledResponse.withStatusCode(200)
					.headers(Map.of("Content-Type", Set.of("text/plain; charset=UTF-8")))
					.stream(StreamingResponseBody.fromPublisher(publisher))
					.build();
		}
	}

	public static class BlockingSourceResource {
		private static volatile CountDownLatch inputStreamClosedLatch = new CountDownLatch(0);
		private static volatile CountDownLatch readerClosedLatch = new CountDownLatch(0);

		@GET("/blocking-input-stream")
		public MarshaledResponse blockingInputStream() {
			return MarshaledResponse.withStatusCode(200)
					.headers(Map.of("Content-Type", Set.of("application/octet-stream")))
					.stream(StreamingResponseBody.fromInputStream(() ->
							new BlockingInputStream(inputStreamClosedLatch)))
					.build();
		}

		@GET("/blocking-reader")
		public MarshaledResponse blockingReader() {
			return MarshaledResponse.withStatusCode(200)
					.headers(Map.of("Content-Type", Set.of("text/plain; charset=UTF-8")))
					.stream(StreamingResponseBody.fromReader(() ->
									new BlockingReader(readerClosedLatch),
							StandardCharsets.UTF_8))
					.build();
		}
	}

	private void assertBlockingSourceClosedOnTimeout(@NonNull String path,
																									@NonNull CountDownLatch closedLatch) throws Exception {
		int port = findFreePort();
		CountDownLatch terminatedLatch = new CountDownLatch(1);
		AtomicReference<StreamingResponseCancelationReason> cancelationReasonRef = new AtomicReference<>();

		SokletConfig config = SokletConfig.withHttpServer(HttpServer.withPort(port)
						.requestTimeout(Duration.ofSeconds(5))
						.streamingResponseTimeout(Duration.ofMillis(250))
						.build())
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(BlockingSourceResource.class)))
				.lifecycleObserver(new LifecycleObserver() {
					@Override
					public void didTerminateResponseStream(@NonNull ServerType serverType,
																								 @NonNull Request request,
																								 @Nullable ResourceMethod resourceMethod,
																								 @NonNull MarshaledResponse marshaledResponse,
																								 @NonNull Duration streamDuration,
																								 @Nullable StreamingResponseCancelationReason cancelationReason,
																								 @Nullable Throwable throwable) {
						cancelationReasonRef.set(cancelationReason);
						terminatedLatch.countDown();
					}

					@Override
					public void didReceiveLogEvent(@NonNull LogEvent logEvent) {
						// Keep test output quiet.
					}
				})
				.build();

		try (Soklet soklet = Soklet.fromConfig(config)) {
			soklet.start();

			try (Socket socket = connectWithRetry("127.0.0.1", port, 2_000)) {
				socket.setSoTimeout(2_000);
				writeRawRequest(socket, "GET " + path + " HTTP/1.1\r\nHost: localhost\r\n\r\n");

				String responseHeaders = readUntil(socket.getInputStream(), "\r\n\r\n", 8_192);

				Assertions.assertNotNull(responseHeaders);
				Assertions.assertTrue(responseHeaders.startsWith("HTTP/1.1 200 OK"), responseHeaders);
				Assertions.assertTrue(closedLatch.await(2, TimeUnit.SECONDS), "Source was not closed on stream cancelation");
				Assertions.assertTrue(terminatedLatch.await(2, TimeUnit.SECONDS), "Stream termination lifecycle hook was not invoked");
				Assertions.assertEquals(StreamingResponseCancelationReason.RESPONSE_TIMEOUT, cancelationReasonRef.get());
			}
		}
	}

	private static void writeRawRequest(@NonNull Socket socket,
																			@NonNull String request) throws IOException {
		OutputStream outputStream = socket.getOutputStream();
		outputStream.write(request.getBytes(StandardCharsets.ISO_8859_1));
		outputStream.flush();
	}

	@Nullable
	private static String readUntil(@NonNull InputStream inputStream,
																	@NonNull String delimiter,
																	int maxBytes) throws IOException {
		ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
		byte[] delimiterBytes = delimiter.getBytes(StandardCharsets.ISO_8859_1);
		int matched = 0;

		while (byteArrayOutputStream.size() < maxBytes) {
			int value = inputStream.read();

			if (value < 0)
				break;

			byteArrayOutputStream.write(value);

			if (value == delimiterBytes[matched]) {
				matched++;

				if (matched == delimiterBytes.length)
					return byteArrayOutputStream.toString(StandardCharsets.ISO_8859_1);
			} else {
				matched = value == delimiterBytes[0] ? 1 : 0;
			}
		}

		return byteArrayOutputStream.size() == 0
				? null
				: byteArrayOutputStream.toString(StandardCharsets.ISO_8859_1);
	}

	private static final class BlockingInputStream extends InputStream {
		@NonNull
		private final CountDownLatch closedLatch;
		private boolean closed;

		private BlockingInputStream(@NonNull CountDownLatch closedLatch) {
			this.closedLatch = closedLatch;
		}

		@Override
		public int read() throws IOException {
			return read(new byte[1], 0, 1);
		}

		@Override
		public int read(byte[] bytes, int offset, int length) throws IOException {
			synchronized (this) {
				while (!this.closed) {
					try {
						wait();
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						throw new IOException(e);
					}
				}
			}

			throw new IOException("closed");
		}

		@Override
		public void close() {
			synchronized (this) {
				this.closed = true;
				notifyAll();
			}

			this.closedLatch.countDown();
		}
	}

	private static final class BlockingReader extends Reader {
		@NonNull
		private final CountDownLatch closedLatch;
		private boolean closed;

		private BlockingReader(@NonNull CountDownLatch closedLatch) {
			this.closedLatch = closedLatch;
		}

		@Override
		public int read(char[] chars, int offset, int length) throws IOException {
			synchronized (this) {
				while (!this.closed) {
					try {
						wait();
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						throw new IOException(e);
					}
				}
			}

			throw new IOException("closed");
		}

		@Override
		public void close() {
			synchronized (this) {
				this.closed = true;
				notifyAll();
			}

			this.closedLatch.countDown();
		}
	}
}
