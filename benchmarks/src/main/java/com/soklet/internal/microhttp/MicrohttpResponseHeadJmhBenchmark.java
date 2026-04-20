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

package com.soklet.internal.microhttp;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Benchmarks embedded HTTP response head serialization.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(3)
public class MicrohttpResponseHeadJmhBenchmark {
	private static final String VERSION = "HTTP/1.1";
	private static final List<Header> CONNECTION_HEADERS = List.of(new Header("Connection", "keep-alive"));

	@Benchmark
	public byte[] smallHeaders(ResponseHeadState state) {
		return state.smallHeadersResponse.serializeHead(VERSION, CONNECTION_HEADERS);
	}

	@Benchmark
	public byte[] largeHeaders(ResponseHeadState state) {
		return state.largeHeadersResponse.serializeHead(VERSION, CONNECTION_HEADERS);
	}

	@Benchmark
	public byte[] setCookieHeaders(ResponseHeadState state) {
		return state.setCookieHeadersResponse.serializeHead(VERSION, CONNECTION_HEADERS);
	}

	/**
	 * Per-thread benchmark state for response head serialization benchmarks.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@State(Scope.Thread)
	public static class ResponseHeadState {
		private final MicrohttpResponse smallHeadersResponse = new MicrohttpResponse(200,
				"OK",
				List.of(
						new Header("Content-Type", "application/json; charset=UTF-8"),
						new Header("Content-Length", "37"),
						new Header("Date", "Mon, 01 Jan 2024 00:00:00 GMT"),
						new Header("Server", "soklet")),
				"{}".getBytes(StandardCharsets.UTF_8));
		private final MicrohttpResponse largeHeadersResponse = new MicrohttpResponse(200,
				"OK",
				largeHeaders(),
				"{}".getBytes(StandardCharsets.UTF_8));
		private final MicrohttpResponse setCookieHeadersResponse = new MicrohttpResponse(200,
				"OK",
				setCookieHeaders(),
				"{}".getBytes(StandardCharsets.UTF_8));

		private static List<Header> largeHeaders() {
			List<Header> headers = new ArrayList<>();
			headers.add(new Header("Content-Type", "application/json; charset=UTF-8"));
			headers.add(new Header("Content-Length", "37"));
			headers.add(new Header("Date", "Mon, 01 Jan 2024 00:00:00 GMT"));
			headers.add(new Header("Server", "soklet"));

			for (int i = 0; i < 28; i++)
				headers.add(new Header("X-Benchmark-Header-" + i, "benchmark-value-" + i));

			return List.copyOf(headers);
		}

		private static List<Header> setCookieHeaders() {
			List<Header> headers = new ArrayList<>();
			headers.add(new Header("Content-Type", "text/plain; charset=UTF-8"));
			headers.add(new Header("Content-Length", "2"));

			for (int i = 0; i < 12; i++)
				headers.add(new Header("Set-Cookie", "cookie" + i + "=value" + i + "; Path=/; HttpOnly; SameSite=Lax"));

			return List.copyOf(headers);
		}
	}
}
