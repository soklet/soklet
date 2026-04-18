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

import com.soklet.internal.microhttp.MicrohttpResponse;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static java.nio.file.StandardOpenOption.READ;

/**
 * Benchmarks conversion from Soklet's {@link MarshaledResponse} to the embedded HTTP response representation.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(3)
public class ResponseWritePathJmhBenchmark {
	@Benchmark
	public void byteArrayResponse(ResponseState state, Blackhole blackhole) {
		consume(state.server.toMicrohttpResponse(state.byteArrayResponse), blackhole);
	}

	@Benchmark
	public void dynamicByteArrayResponse(ResponseState state, Blackhole blackhole) {
		MarshaledResponse response = MarshaledResponse.withStatusCode(200)
				.headers(state.byteArrayHeaders)
				.body(state.jsonBody)
				.build();

		consume(state.server.toMicrohttpResponse(response), blackhole);
	}

	@Benchmark
	public void byteArrayResponseWithCookies(ResponseState state, Blackhole blackhole) {
		consume(state.server.toMicrohttpResponse(state.byteArrayResponseWithCookies), blackhole);
	}

	@Benchmark
	public void manyHeadersResponse(ResponseState state, Blackhole blackhole) {
		consume(state.server.toMicrohttpResponse(state.manyHeadersResponse), blackhole);
	}

	@Benchmark
	public void dynamicManyHeadersResponse(ResponseState state, Blackhole blackhole) {
		MarshaledResponse response = MarshaledResponse.withStatusCode(200)
				.headers(state.manyHeaders)
				.body(state.jsonBody)
				.build();

		consume(state.server.toMicrohttpResponse(response), blackhole);
	}

	@Benchmark
	public void fileResponse(ResponseState state, Blackhole blackhole) {
		consume(state.server.toMicrohttpResponse(state.fileResponse), blackhole);
	}

	@Benchmark
	public void fileChannelResponse(ResponseState state, Blackhole blackhole) {
		consume(state.server.toMicrohttpResponse(state.fileChannelResponse), blackhole);
	}

	@Benchmark
	public void byteBufferResponse(ResponseState state, Blackhole blackhole) {
		consume(state.server.toMicrohttpResponse(state.byteBufferResponse), blackhole);
	}

	private static void consume(MicrohttpResponse response, Blackhole blackhole) {
		blackhole.consume(response);
		blackhole.consume(response.status());
		blackhole.consume(response.reason());
		blackhole.consume(response.headers());
		blackhole.consume(response.bodyLength());
	}

	/**
	 * Per-thread benchmark state for response conversion benchmarks.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@State(Scope.Thread)
	public static class ResponseState {
		private final DefaultHttpServer server = (DefaultHttpServer) HttpServer.withPort(0).build();
		private final byte[] jsonBody = ascii("{\"ok\":true}");
		private final Map<String, Set<String>> byteArrayHeaders = Map.of(
				"Content-Type", Set.of("application/json"),
				"Cache-Control", Set.of("no-cache", "no-transform"),
				"X-Request-Id", Set.of("benchmark-response"));
		private final Map<String, Set<String>> manyHeaders = Map.of(
				"Content-Type", Set.of("application/json"),
				"Cache-Control", Set.of("no-cache", "no-transform"),
				"Vary", Set.of("Origin", "Accept-Encoding"),
				"X-Request-Id", Set.of("benchmark-response"),
				"X-Tenant-Id", Set.of("tenant-a"),
				"X-Feature", Set.of("fast-path"),
				"X-Trace-Flags", Set.of("sampled"),
				"X-Response-Mode", Set.of("benchmark"));
		private final MarshaledResponse byteArrayResponse = MarshaledResponse.withStatusCode(200)
				.headers(this.byteArrayHeaders)
				.body(this.jsonBody)
				.build();
		private final MarshaledResponse byteArrayResponseWithCookies = MarshaledResponse.withStatusCode(200)
				.headers(Map.of("Content-Type", Set.of("application/json")))
				.cookies(new LinkedHashSet<>(List.of(
						ResponseCookie.withName("session").value("abc123").httpOnly(true).secure(true).build(),
						ResponseCookie.withName("theme").value("light").path("/").build())))
				.body(this.jsonBody)
				.build();
		private final MarshaledResponse manyHeadersResponse = MarshaledResponse.withStatusCode(200)
				.headers(this.manyHeaders)
				.body(this.jsonBody)
				.build();
		private Path file;
		private FileChannel fileChannel;
		private MarshaledResponse fileResponse;
		private MarshaledResponse fileChannelResponse;
		private MarshaledResponse byteBufferResponse;

		@Setup
		public void setup() throws IOException {
			byte[] fileBytes = ascii("abcdefghijklmnopqrstuvwxyz0123456789");
			this.file = Files.createTempFile("soklet-response-benchmark-", ".txt");
			Files.write(this.file, fileBytes);
			this.fileChannel = FileChannel.open(this.file, READ);
			this.fileResponse = MarshaledResponse.withStatusCode(200)
					.headers(Map.of("Content-Type", Set.of("application/octet-stream")))
					.body(this.file)
					.build();
			this.fileChannelResponse = MarshaledResponse.withStatusCode(200)
					.headers(Map.of("Content-Type", Set.of("application/octet-stream")))
					.body(this.fileChannel, 0L, Long.valueOf(fileBytes.length), false)
					.build();
			this.byteBufferResponse = MarshaledResponse.withStatusCode(200)
					.headers(Map.of("Content-Type", Set.of("application/octet-stream")))
					.body(ByteBuffer.wrap(fileBytes))
					.build();
		}

		@TearDown
		public void tearDown() throws IOException {
			if (this.fileChannel != null)
				this.fileChannel.close();

			if (this.file != null)
				Files.deleteIfExists(this.file);
		}

		private static byte[] ascii(String value) {
			return value.getBytes(StandardCharsets.US_ASCII);
		}
	}
}
