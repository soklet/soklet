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

package com.soklet.internal.mcp.protocol;

import com.soklet.McpRequestContext;
import com.soklet.McpRequestOutcome;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.ThreadSafe;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.Objects.requireNonNull;

/**
 * Thread-safe request-observation recorder for deterministic protocol-runtime
 * tests whose requests use unique string JSON-RPC IDs.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class McpRuntimeObservationRecorder implements McpRuntimeObservationSink {
	@NonNull
	private final ConcurrentMap<@NonNull McpJsonRpcId, @NonNull Observation>
			observations;

	McpRuntimeObservationRecorder() {
		this.observations = new ConcurrentHashMap<>();
	}

	@Override
	@NonNull
	public McpRuntimeRequestObservation didStartRequest(
			@NonNull McpRuntimeRequestInput input) {
		McpJsonRpcId requestId = input.requestId().orElseThrow(() ->
				new IllegalArgumentException(
						"The recording observation sink requires a request ID."));
		Observation observation = new Observation(input);
		if (this.observations.putIfAbsent(requestId, observation) != null)
			throw new IllegalStateException(
					"The recording observation sink requires unique request IDs.");
		return observation;
	}

	int startCount() {
		return this.observations.size();
	}

	@NonNull
	Observation observation(@NonNull String requestId) {
		Observation observation = this.observations.get(
				new McpJsonRpcId.StringId(requireNonNull(requestId)));
		if (observation == null)
			throw new AssertionError(
					"No MCP request observation started for ID '" + requestId + "'.");
		return observation;
	}

	/**
	 * One request's recorded start input and terminal deliveries.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	static final class Observation implements McpRuntimeRequestObservation {
		@NonNull
		private final McpRuntimeRequestInput input;
		@NonNull
		private final List<@NonNull Finish> finishes;
		@NonNull
		private final AtomicInteger finishCount;
		@NonNull
		private final CountDownLatch finished;

		private Observation(@NonNull McpRuntimeRequestInput input) {
			this.input = requireNonNull(input);
			this.finishes = new CopyOnWriteArrayList<>();
			this.finishCount = new AtomicInteger();
			this.finished = new CountDownLatch(1);
		}

		@NonNull
		McpRuntimeRequestInput input() {
			return this.input;
		}

		@Override
		@NonNull
		public Optional<@NonNull McpRequestContext> publicContext() {
			return Optional.empty();
		}

		@Override
		public void didFinish(@NonNull McpRequestOutcome outcome,
				@Nullable McpJsonRpcError error, @NonNull Duration duration,
				@NonNull List<@NonNull Throwable> throwables) {
			this.finishes.add(new Finish(outcome, error, duration, throwables));
			this.finishCount.incrementAndGet();
			this.finished.countDown();
		}

		@NonNull
		Finish awaitFinish() throws InterruptedException {
			if (!this.finished.await(5, TimeUnit.SECONDS))
				throw new AssertionError("The MCP request finish observation did not arrive.");
			return this.finishes.get(0);
		}

		int finishCount() {
			return this.finishCount.get();
		}
	}

	/**
	 * One immutable terminal delivery.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	record Finish(@NonNull McpRequestOutcome outcome,
			@Nullable McpJsonRpcError error, @NonNull Duration duration,
			@NonNull List<@NonNull Throwable> throwables) {
		Finish {
			requireNonNull(outcome);
			requireNonNull(duration);
			throwables = List.copyOf(requireNonNull(throwables));
		}
	}
}
