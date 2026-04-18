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
import org.openjdk.jmh.infra.Blackhole;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Benchmarks construction of Soklet's public {@link Request} representation from embedded HTTP requests.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(3)
public class RequestConstructionJmhBenchmark {
	@Benchmark
	public void publicHeadersGetNoHeaderAccess(RequestState state, Blackhole blackhole) {
		Request request = state.buildWithPublicHeaders(state.getRequest);
		consumeRoutingFields(request, blackhole);
	}

	@Benchmark
	public void microhttpHeadersGetNoHeaderAccess(RequestState state, Blackhole blackhole) {
		Request request = state.buildWithMicrohttpHeaders(state.getRequest);
		consumeRoutingFields(request, blackhole);
	}

	@Benchmark
	public void microhttpHeadersGetSingleHeaderAccess(RequestState state, Blackhole blackhole) {
		Request request = state.buildWithMicrohttpHeaders(state.getRequest);
		blackhole.consume(request.getHeader("X-Request-Id"));
	}

	@Benchmark
	public void microhttpHeadersGetAllHeadersAccess(RequestState state, Blackhole blackhole) {
		Request request = state.buildWithMicrohttpHeaders(state.getRequest);
		blackhole.consume(request.getHeaders());
	}

	@Benchmark
	public void microhttpHeadersGetWithQueryNoQueryAccess(RequestState state, Blackhole blackhole) {
		Request request = state.buildWithMicrohttpHeaders(state.getWithQueryRequest);
		consumeRoutingFields(request, blackhole);
	}

	@Benchmark
	public void microhttpHeadersGetWithQuerySingleQueryAccess(RequestState state, Blackhole blackhole) {
		Request request = state.buildWithMicrohttpHeaders(state.getWithQueryRequest);
		blackhole.consume(request.getQueryParameter("include"));
	}

	@Benchmark
	public void microhttpHeadersGetWithQueryTwoQueryAccess(RequestState state, Blackhole blackhole) {
		Request request = state.buildWithMicrohttpHeaders(state.getWithQueryRequest);
		blackhole.consume(request.getQueryParameter("include"));
		blackhole.consume(request.getQueryParameter("tenant"));
	}

	@Benchmark
	public void microhttpHeadersPostJsonNoHeaderAccess(RequestState state, Blackhole blackhole) {
		Request request = state.buildWithMicrohttpHeaders(state.postJsonRequest);
		consumeRoutingFields(request, blackhole);
		blackhole.consume(request.getBody().map(body -> body.length).orElse(0));
	}

	@Benchmark
	public void microhttpHeadersPostFormNoFormAccess(RequestState state, Blackhole blackhole) {
		Request request = state.buildWithMicrohttpHeaders(state.postFormRequest);
		consumeRoutingFields(request, blackhole);
	}

	@Benchmark
	public void microhttpHeadersPostFormSingleFormAccess(RequestState state, Blackhole blackhole) {
		Request request = state.buildWithMicrohttpHeaders(state.postFormRequest);
		blackhole.consume(request.getFormParameter("name"));
	}

	private static void consumeRoutingFields(Request request, Blackhole blackhole) {
		blackhole.consume(request.getHttpMethod());
		blackhole.consume(request.getPath());
		blackhole.consume(request.getResourcePath());
	}

	/**
	 * Per-thread benchmark state for public request construction benchmarks.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@State(Scope.Thread)
	public static class RequestState {
		private static final InetSocketAddress REMOTE_ADDRESS = new InetSocketAddress("127.0.0.1", 12345);
		private static final byte[] POST_JSON_BODY = ascii("{\"name\":\"widget\",\"quantity\":123456}");
		private static final byte[] POST_FORM_BODY = ascii("name=widget&quantity=123456");

		private final DefaultHttpServer server = (DefaultHttpServer) HttpServer.withPort(0).build();
		private final MicrohttpRequest getRequest = new MicrohttpRequest(
				"GET",
				"/widgets/123",
				"HTTP/1.1",
				commonHeaders(),
				new byte[0],
				false,
				REMOTE_ADDRESS);
		private final MicrohttpRequest getWithQueryRequest = new MicrohttpRequest(
				"GET",
				"/widgets/123?include=inventory&tenant=tenant-a",
				"HTTP/1.1",
				commonHeaders(),
				new byte[0],
				false,
				REMOTE_ADDRESS);
		private final MicrohttpRequest postJsonRequest = new MicrohttpRequest(
				"POST",
				"/widgets",
				"HTTP/1.1",
				headersWithContent("application/json", POST_JSON_BODY.length),
				POST_JSON_BODY,
				false,
				REMOTE_ADDRESS);
		private final MicrohttpRequest postFormRequest = new MicrohttpRequest(
				"POST",
				"/widgets",
				"HTTP/1.1",
				headersWithContent("application/x-www-form-urlencoded; charset=UTF-8", POST_FORM_BODY.length),
				POST_FORM_BODY,
				false,
				REMOTE_ADDRESS);

		private Request buildWithPublicHeaders(MicrohttpRequest microhttpRequest) {
			return builder(microhttpRequest)
					.headers(server.headersFromMicrohttpRequest(microhttpRequest))
					.build();
		}

		private Request buildWithMicrohttpHeaders(MicrohttpRequest microhttpRequest) {
			return builder(microhttpRequest)
					.microhttpHeaders(microhttpRequest.headers())
					.build();
		}

		private Request.RawBuilder builder(MicrohttpRequest microhttpRequest) {
			byte[] body = microhttpRequest.body();
			if (body != null && body.length == 0)
				body = null;

			return Request.withRawUrl(HttpMethod.valueOf(microhttpRequest.method()), microhttpRequest.uri())
					.id("benchmark-request")
					.body(body)
					.remoteAddress(microhttpRequest.remoteAddress())
					.contentTooLarge(microhttpRequest.contentTooLarge());
		}

		private static List<Header> commonHeaders() {
			return List.of(
					new Header("Host", "localhost"),
					new Header("User-Agent", "soklet-benchmark"),
					new Header("Accept", "application/json"),
					new Header("Connection", "keep-alive"),
					new Header("X-Request-Id", "benchmark-request"),
					new Header("X-Tenant-Id", "tenant-a"),
					new Header("X-Feature", "fast-path"));
		}

		private static List<Header> headersWithContent(String contentType, int contentLength) {
			return List.of(
					new Header("Host", "localhost"),
					new Header("User-Agent", "soklet-benchmark"),
					new Header("Accept", "application/json"),
					new Header("Content-Type", contentType),
					new Header("Content-Length", Integer.toString(contentLength)),
					new Header("Connection", "keep-alive"),
					new Header("X-Request-Id", "benchmark-request"),
					new Header("X-Tenant-Id", "tenant-a"));
		}

		private static byte[] ascii(String value) {
			return value.getBytes(StandardCharsets.US_ASCII);
		}
	}
}
