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
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Benchmarks embedded HTTP request parsing hot paths.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(3)
public class MicrohttpReadPathJmhBenchmark {
	private static final InetSocketAddress REMOTE_ADDRESS = new InetSocketAddress("127.0.0.1", 12345);
	private static final int MAX_REQUEST_SIZE = 1024 * 1024;
	private static final byte[] GET_REQUEST = ascii("""
			GET /widgets/123?include=inventory HTTP/1.1\r
			Host: localhost\r
			User-Agent: soklet-benchmark\r
			Accept: application/json\r
			Connection: keep-alive\r
			X-Request-Id: benchmark-request\r
			X-Tenant-Id: tenant-a\r
			X-Feature: fast-path\r
			\r
			""");
	private static final byte[] POST_REQUEST = ascii("""
			POST /widgets HTTP/1.1\r
			Host: localhost\r
			User-Agent: soklet-benchmark\r
			Accept: application/json\r
			Content-Type: application/json\r
			Content-Length: 37\r
			Connection: keep-alive\r
			X-Request-Id: benchmark-request\r
			X-Tenant-Id: tenant-a\r
			\r
			{"name":"widget","quantity":123456}\r
			""");
	private static final byte[] PIPELINED_GET_REQUESTS = repeat(GET_REQUEST, 16);

	@Benchmark
	public void keepAliveGetNewParser(ParserState state, Blackhole blackhole) {
		state.parseOneAtATimeWithNewParser(GET_REQUEST, blackhole);
	}

	@Benchmark
	public void keepAliveGetParserReuse(ParserState state, Blackhole blackhole) {
		state.parseOneAtATimeWithParserReuse(GET_REQUEST, blackhole);
	}

	@Benchmark
	public void keepAlivePostJsonParserReuse(ParserState state, Blackhole blackhole) {
		state.parseOneAtATimeWithParserReuse(POST_REQUEST, blackhole);
	}

	@Benchmark
	@OperationsPerInvocation(16)
	public void pipelinedGet16ParserReuse(ParserState state, Blackhole blackhole) {
		state.parsePipelinedGet16(blackhole);
	}

	/**
	 * Per-thread benchmark state for embedded HTTP request parsing benchmarks.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@State(Scope.Thread)
	public static class ParserState {
		private ByteTokenizer tokenizer;
		private ByteBuffer getBuffer;
		private ByteBuffer postBuffer;
		private ByteBuffer pipelinedBuffer;
		private RequestParser parser;

		@Setup
		public void setup() {
			tokenizer = new ByteTokenizer();
			getBuffer = ByteBuffer.wrap(GET_REQUEST);
			postBuffer = ByteBuffer.wrap(POST_REQUEST);
			pipelinedBuffer = ByteBuffer.wrap(PIPELINED_GET_REQUESTS);
			parser = new RequestParser(tokenizer, REMOTE_ADDRESS, MAX_REQUEST_SIZE);
		}

		private void parseOneAtATimeWithNewParser(byte[] requestBytes, Blackhole blackhole) {
			ByteBuffer buffer = requestBytes == GET_REQUEST ? getBuffer : postBuffer;
			reset(buffer);
			tokenizer.add(buffer);
			RequestParser requestParser = new RequestParser(tokenizer, REMOTE_ADDRESS, MAX_REQUEST_SIZE);
			if (!requestParser.parse())
				throw new IllegalStateException("Unable to parse benchmark request");

			consume(requestParser.request(), blackhole);
			tokenizer.compact();
		}

		private void parseOneAtATimeWithParserReuse(byte[] requestBytes, Blackhole blackhole) {
			ByteBuffer buffer = requestBytes == GET_REQUEST ? getBuffer : postBuffer;
			reset(buffer);
			tokenizer.add(buffer);
			if (!parser.parse())
				throw new IllegalStateException("Unable to parse benchmark request");

			consume(parser.request(), blackhole);
			tokenizer.compact();
			parser.reset();
		}

		private void parsePipelinedGet16(Blackhole blackhole) {
			reset(pipelinedBuffer);
			tokenizer.add(pipelinedBuffer);

			for (int i = 0; i < 16; i++) {
				if (!parser.parse())
					throw new IllegalStateException("Unable to parse benchmark request");

				consume(parser.request(), blackhole);
				tokenizer.compact();
				parser.reset();
			}
		}
	}

	private static void consume(MicrohttpRequest request, Blackhole blackhole) {
		blackhole.consume(request.method());
		blackhole.consume(request.uri());
		blackhole.consume(request.version());
		blackhole.consume(request.headers());
		blackhole.consume(request.body());
	}

	private static void reset(ByteBuffer buffer) {
		buffer.position(0);
		buffer.limit(buffer.capacity());
	}

	private static byte[] ascii(String value) {
		return value.getBytes(StandardCharsets.US_ASCII);
	}

	private static byte[] repeat(byte[] bytes, int count) {
		byte[] repeated = new byte[bytes.length * count];
		for (int i = 0; i < count; i++)
			System.arraycopy(bytes, 0, repeated, i * bytes.length, bytes.length);
		return repeated;
	}
}
