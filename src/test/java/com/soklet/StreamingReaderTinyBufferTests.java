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
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static com.soklet.TestSupport.findFreePort;
import static com.soklet.TestSupport.readAll;

/** Exercises the smallest supported reader buffer across both streaming runtimes. */
public class StreamingReaderTinyBufferTests {
	private static final String TEXT = "a\uD83D\uDE00b";

	@Test
	public void live_reader_preserves_a_surrogate_pair_with_single_character_reads() throws Exception {
		TinyReaderResource.closed.set(0);
		int port = findFreePort();
		SokletConfig config = SokletConfig.withHttpServer(HttpServer.withPort(port).build())
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(TinyReaderResource.class)))
				.build();

		try (Soklet soklet = Soklet.fromConfig(config)) {
			soklet.start();
			HttpURLConnection connection = (HttpURLConnection)
					new URL("http://127.0.0.1:" + port + "/tiny-reader").openConnection();
			connection.setConnectTimeout(2_000);
			connection.setReadTimeout(2_000);
			try {
				Assertions.assertEquals(200, connection.getResponseCode());
				Assertions.assertArrayEquals(TEXT.getBytes(StandardCharsets.UTF_8),
						readAll(connection.getInputStream()));
			} finally {
				connection.disconnect();
			}
			Assertions.assertEquals(1, TinyReaderResource.closed.get());
		}
	}

	@Test
	public void simulator_reader_preserves_a_surrogate_pair_with_single_character_reads() {
		TinyReaderResource.closed.set(0);
		SokletSimulator.run(SimulatorConfig.builder().httpServer()
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(TinyReaderResource.class)))
				.build(), simulator -> {
			HttpRequestResult result = simulator.performHttpRequest(
					Request.withPath(HttpMethod.GET, "/tiny-reader").build());
			Assertions.assertArrayEquals(TEXT.getBytes(StandardCharsets.UTF_8),
					result.getMarshaledResponse().bodyBytesOrEmpty());
		});
		Assertions.assertEquals(1, TinyReaderResource.closed.get());
	}

	@Test
	public void simulator_reader_flushes_a_lone_surrogate_at_eof() {
		TinyReaderResource.closed.set(0);
		SokletSimulator.run(SimulatorConfig.builder().httpServer()
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(TinyReaderResource.class)))
				.build(), simulator -> {
			HttpRequestResult result = simulator.performHttpRequest(
					Request.withPath(HttpMethod.GET, "/tiny-reader-malformed").build());
			Assertions.assertArrayEquals("?".getBytes(StandardCharsets.UTF_8),
					result.getMarshaledResponse().bodyBytesOrEmpty());
		});
		Assertions.assertEquals(1, TinyReaderResource.closed.get());
	}

	public static class TinyReaderResource {
		static final AtomicInteger closed = new AtomicInteger();

		@GET("/tiny-reader")
		public MarshaledResponse tinyReader() {
			return MarshaledResponse.withStatusCode(200)
					.streamingResponseBody(StreamingResponseBody.withReader(
							() -> new SingleCharacterReader(TEXT), StandardCharsets.UTF_8)
							.bufferSizeInCharacters(1).build())
					.build();
		}

		@GET("/tiny-reader-malformed")
		public MarshaledResponse tinyReaderMalformed() {
			return MarshaledResponse.withStatusCode(200)
					.streamingResponseBody(StreamingResponseBody.withReader(
							() -> new SingleCharacterReader("\uD83D"), StandardCharsets.UTF_8)
							.bufferSizeInCharacters(1)
							.malformedInputAction(CodingErrorAction.REPLACE).build())
					.build();
		}
	}

	private static final class SingleCharacterReader extends Reader {
		private final String text;
		private int nextCharacter;
		private boolean closed;

		private SingleCharacterReader(String text) {
			this.text = text;
		}

		@Override
		public int read(CharBuffer target) throws IOException {
			if (target.remaining() != 1)
				throw new IOException("Configured one-character reads must retain one free slot");
			return super.read(target);
		}

		@Override
		public int read(char[] target, int offset, int length) {
			if (nextCharacter == text.length())
				return -1;
			target[offset] = text.charAt(nextCharacter++);
			return 1;
		}

		@Override
		public void close() {
			if (!closed) {
				closed = true;
				TinyReaderResource.closed.incrementAndGet();
			}
		}
	}
}
