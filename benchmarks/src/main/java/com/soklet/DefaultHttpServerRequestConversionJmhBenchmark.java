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

import com.soklet.internal.microhttp.Header;
import com.soklet.internal.microhttp.MicrohttpRequest;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static java.lang.String.format;

/**
 * Benchmarks conversion from the embedded HTTP request representation into Soklet's public header map.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(3)
public class DefaultHttpServerRequestConversionJmhBenchmark {
	@Benchmark
	public Map<String, Set<String>> rawHeaderRoundTrip(RequestState state) {
		List<String> rawHeaderLines = state.request.headers().stream()
				.map(header -> format("%s: %s", header.name(), header.value() == null ? "" : header.value()))
				.collect(Collectors.toList());
		return Utilities.extractHeadersFromRawHeaderLines(rawHeaderLines);
	}

	@Benchmark
	public Map<String, Set<String>> parsedHeaderDirect(RequestState state) {
		return state.server.headersFromMicrohttpRequest(state.request);
	}

	/**
	 * Per-thread benchmark state for request header conversion benchmarks.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@State(Scope.Thread)
	public static class RequestState {
		private final MicrohttpRequest request = new MicrohttpRequest(
				"GET",
				"/widgets/123?include=inventory",
				"HTTP/1.1",
				List.of(
						new Header("Host", "localhost"),
						new Header("User-Agent", "soklet-benchmark"),
						new Header("Accept", "application/json, text/plain"),
						new Header("Accept-Encoding", "gzip, br"),
						new Header("Cache-Control", "no-cache, no-store"),
						new Header("Cookie", "a=b; c=d"),
						new Header("Connection", "keep-alive"),
						new Header("X-Request-Id", "benchmark-request"),
						new Header("X-Tenant-Id", "tenant-a")),
				new byte[0],
				false,
				new InetSocketAddress("127.0.0.1", 12345));
		private final DefaultHttpServer server = (DefaultHttpServer) HttpServer.withPort(0).build();
	}
}
