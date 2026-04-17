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

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Benchmarks Server-Sent Event payload serialization hot paths.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(3)
public class SsePayloadSerializationJmhBenchmark {
	private static final int CONNECTION_COUNT = 1_024;

	@Benchmark
	public String formatComment(CommentAndEventState state) {
		return DefaultSseServer.formatCommentForResponse(state.commentText);
	}

	@Benchmark
	public byte[] formatCommentUtf8Bytes(CommentAndEventState state) {
		return DefaultSseServer.formatCommentForResponse(state.commentText).getBytes(StandardCharsets.UTF_8);
	}

	@Benchmark
	public byte[] formatEventUtf8Bytes(CommentAndEventState state) throws Throwable {
		String formatted = (String) state.eventFormatter.invokeExact(state.event, state.eventStringBuilder);
		return formatted.getBytes(StandardCharsets.UTF_8);
	}

	@Benchmark
	public Object preSerializeCommentProductionPath(CommentAndEventState state) throws Throwable {
		return state.commentPreSerializer.invokeExact(state.comment);
	}

	@Benchmark
	public Object preSerializeEventProductionPath(CommentAndEventState state) throws Throwable {
		return state.eventPreSerializer.invokeExact(state.event);
	}

	@Benchmark
	public void commentPayloadOldPerBroadcast1024(CommentAndEventState state,
																								Blackhole blackhole) {
		for (int i = 0; i < CONNECTION_COUNT; i++)
			blackhole.consume(DefaultSseServer.formatCommentForResponse(state.commentText).getBytes(StandardCharsets.UTF_8));
	}

	@Benchmark
	public void commentPayloadPreSerializedPerBroadcast1024(CommentAndEventState state,
																													Blackhole blackhole) throws Throwable {
		Object preSerializedPayload = state.commentPreSerializer.invokeExact(state.comment);

		for (int i = 0; i < CONNECTION_COUNT; i++)
			blackhole.consume(preSerializedPayload);
	}

	/**
	 * Per-thread benchmark state for SSE payload serialization benchmarks.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@State(Scope.Thread)
	public static class CommentAndEventState {
		private static final MethodType PRE_SERIALIZE_COMMENT_SIGNATURE = MethodType.methodType(Object.class, SseComment.class);
		private static final MethodType PRE_SERIALIZE_EVENT_SIGNATURE = MethodType.methodType(Object.class, SseEvent.class);
		private static final MethodType FORMAT_EVENT_SIGNATURE = MethodType.methodType(String.class, SseEvent.class, StringBuilder.class);

		private final String commentText;
		private final SseComment comment;
		private final SseEvent event;
		private final StringBuilder eventStringBuilder;
		private MethodHandle commentPreSerializer;
		private MethodHandle eventPreSerializer;
		private MethodHandle eventFormatter;

		public CommentAndEventState() {
			this.commentText = """
					inventory update
					sku=ABC-123
					quantity=42
					warehouse=east
					""";
			this.comment = SseComment.fromComment(this.commentText);
			this.event = SseEvent.withEvent("inventory-updated")
					.id("evt-123456789")
					.retry(Duration.ofSeconds(5))
					.data("""
							{"sku":"ABC-123","quantity":42}
							{"sku":"XYZ-789","quantity":7}
							""")
					.build();
			this.eventStringBuilder = new StringBuilder(256);
		}

		@Setup
		public void setup() throws ReflectiveOperationException {
			MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(DefaultSseServer.class, MethodHandles.lookup());
			Class<?> preSerializedPayloadClass =
					Class.forName("com.soklet.DefaultSseServer$DefaultSseConnection$PreSerializedPayload");

			this.commentPreSerializer = lookup.findStatic(
							DefaultSseServer.class,
							"preSerializeSseComment",
							MethodType.methodType(preSerializedPayloadClass, SseComment.class))
					.asType(PRE_SERIALIZE_COMMENT_SIGNATURE);
			this.eventPreSerializer = lookup.findStatic(
							DefaultSseServer.class,
							"preSerializeSseEvent",
							MethodType.methodType(preSerializedPayloadClass, SseEvent.class))
					.asType(PRE_SERIALIZE_EVENT_SIGNATURE);
			this.eventFormatter = lookup.findStatic(
					DefaultSseServer.class,
					"formatSseEventForResponse",
					FORMAT_EVENT_SIGNATURE);
		}
	}
}
