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
import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.net.HttpURLConnection;
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
}
