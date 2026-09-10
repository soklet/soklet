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

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assertions;

import javax.annotation.concurrent.ThreadSafe;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static java.util.Objects.requireNonNull;

/**
 * Shared task-request observation recorder for live and simulated public-runtime
 * tests.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class McpTaskRequestObservabilityRecorder {
	private static final int INVALID_PARAMS_ERROR_CODE = -32602;
	@NonNull
	private final List<@NonNull McpRequestContext> starts =
			new CopyOnWriteArrayList<>();
	@NonNull
	private final List<@NonNull Finish> finishes =
			new CopyOnWriteArrayList<>();
	@NonNull
	private final List<@NonNull McpMetricsEvent> metricEvents =
			new CopyOnWriteArrayList<>();
	@NonNull
	private final CountDownLatch lifecycleFinished;
	@NonNull
	private final CountDownLatch metricsFinished;
	@NonNull
	private final LifecycleObserver lifecycleObserver = new LifecycleObserver() {
		@Override
		public void didStartMcpRequestHandling(
				@NonNull McpRequestContext context) {
			McpTaskRequestObservabilityRecorder.this.starts.add(
					requireNonNull(context));
		}

		@Override
		public void didFinishMcpRequestHandling(
				@NonNull McpRequestContext context,
				@NonNull McpRequestOutcome outcome,
				@Nullable McpJsonRpcError error,
				@NonNull Duration duration,
				@NonNull List<@NonNull Throwable> throwables) {
			McpTaskRequestObservabilityRecorder.this.finishes.add(new Finish(
					context, outcome, error, duration, throwables));
			McpTaskRequestObservabilityRecorder.this.lifecycleFinished
					.countDown();
		}
	};
	@NonNull
	private final MetricsCollector metricsCollector = new MetricsCollector() {
		@Override
		public void didRecordMcpMetricsEvent(@NonNull McpMetricsEvent event) {
			McpMetricsEvent requiredEvent = requireNonNull(event);
			McpTaskRequestObservabilityRecorder.this.metricEvents.add(
					requiredEvent);
			if (requiredEvent instanceof McpMetricsEvent.RequestFinished)
				McpTaskRequestObservabilityRecorder.this.metricsFinished
						.countDown();
		}
	};

	McpTaskRequestObservabilityRecorder(int expectedRequests) {
		if (expectedRequests < 1)
			throw new IllegalArgumentException(
					"At least one observed MCP task request is required.");
		this.lifecycleFinished = new CountDownLatch(expectedRequests);
		this.metricsFinished = new CountDownLatch(expectedRequests);
	}

	@NonNull
	LifecycleObserver lifecycleObserver() {
		return this.lifecycleObserver;
	}

	@NonNull
	MetricsCollector metricsCollector() {
		return this.metricsCollector;
	}

	void awaitAndAssert(@NonNull String endpointPath,
			@NonNull List<@NonNull Expectation> expectations)
			throws InterruptedException {
		List<Expectation> requiredExpectations = List.copyOf(
				requireNonNull(expectations));
		Assertions.assertTrue(this.lifecycleFinished.await(5, TimeUnit.SECONDS),
				"Timed out awaiting MCP task lifecycle finishes.");
		Assertions.assertTrue(this.metricsFinished.await(5, TimeUnit.SECONDS),
				"Timed out awaiting MCP task metric finishes.");
		Assertions.assertEquals(requiredExpectations.size(), this.starts.size(),
				this.starts.toString());
		Assertions.assertEquals(requiredExpectations.size(), this.finishes.size(),
				this.finishes.toString());

		for (Expectation expectation : requiredExpectations) {
			List<McpRequestContext> matchingStarts = this.starts.stream()
					.filter(context -> hasRequestId(context,
							expectation.requestId()))
					.toList();
			List<Finish> matchingFinishes = this.finishes.stream()
					.filter(finish -> hasRequestId(finish.context(),
							expectation.requestId()))
					.toList();
			Assertions.assertEquals(1, matchingStarts.size(),
					matchingStarts.toString());
			Assertions.assertEquals(1, matchingFinishes.size(),
					matchingFinishes.toString());
			McpRequestContext start = matchingStarts.get(0);
			Finish finish = matchingFinishes.get(0);
			Assertions.assertSame(start, finish.context());
			Assertions.assertEquals(endpointPath,
					start.getEndpoint().getPath());
			Assertions.assertEquals(expectation.method(),
					start.getJsonRpcMethod());
			Assertions.assertEquals(expectation.operationType(),
					start.getOperationType());
			Assertions.assertEquals(Optional.ofNullable(
					expectation.operationName()), start.getOperationName());
			Assertions.assertEquals(expectation.outcome(), finish.outcome());
			Assertions.assertFalse(finish.duration().isNegative());
			Assertions.assertTrue(finish.throwables().isEmpty(),
					finish.throwables().toString());
			if (expectation.errorCode() == null) {
				Assertions.assertNull(finish.error());
			} else {
				McpJsonRpcError error = Optional.ofNullable(finish.error())
						.orElseThrow();
				Assertions.assertEquals(expectation.errorCode().intValue(),
						error.getCode());
				Assertions.assertEquals("Invalid params", error.getMessage());
				Assertions.assertTrue(error.getData().isEmpty());
			}
		}

		List<McpMetricsEvent.RequestStarted> metricStarts = this.metricEvents
				.stream()
				.filter(McpMetricsEvent.RequestStarted.class::isInstance)
				.map(McpMetricsEvent.RequestStarted.class::cast)
				.toList();
		List<McpMetricsEvent.RequestFinished> metricFinishes = this.metricEvents
				.stream()
				.filter(McpMetricsEvent.RequestFinished.class::isInstance)
				.map(McpMetricsEvent.RequestFinished.class::cast)
				.toList();
		Assertions.assertEquals(requiredExpectations.size(), metricStarts.size(),
				this.metricEvents.toString());
		Assertions.assertEquals(requiredExpectations.size(), metricFinishes.size(),
				this.metricEvents.toString());

		Map<String, Long> expectedStarts = new LinkedHashMap<>();
		Map<MetricFinish, Long> expectedFinishes = new LinkedHashMap<>();
		for (Expectation expectation : requiredExpectations) {
			expectedStarts.merge(expectation.method(), 1L, Long::sum);
			expectedFinishes.merge(new MetricFinish(expectation.method(),
					expectation.outcome()), 1L, Long::sum);
		}
		Map<String, Long> actualStarts = new LinkedHashMap<>();
		for (McpMetricsEvent.RequestStarted started : metricStarts) {
			Assertions.assertEquals(endpointPath, started.getEndpointPath());
			Assertions.assertNotEquals(
					McpMetricsEvent.UNRECOGNIZED_JSON_RPC_METHOD,
					started.getJsonRpcMethod());
			actualStarts.merge(started.getJsonRpcMethod(), 1L, Long::sum);
		}
		Map<MetricFinish, Long> actualFinishes = new LinkedHashMap<>();
		for (McpMetricsEvent.RequestFinished finished : metricFinishes) {
			Assertions.assertEquals(endpointPath, finished.getEndpointPath());
			Assertions.assertNotEquals(
					McpMetricsEvent.UNRECOGNIZED_JSON_RPC_METHOD,
					finished.getJsonRpcMethod());
			Assertions.assertFalse(finished.getDuration().isNegative());
			actualFinishes.merge(new MetricFinish(finished.getJsonRpcMethod(),
					finished.getOutcome()), 1L, Long::sum);
		}
		Assertions.assertEquals(expectedStarts, actualStarts);
		Assertions.assertEquals(expectedFinishes, actualFinishes);
	}

	@NonNull
	static Expectation complete(@NonNull String requestId,
			@NonNull String method, @Nullable String operationName,
			@NonNull McpOperationType operationType) {
		return new Expectation(requestId, method, operationName, operationType,
				McpRequestOutcome.COMPLETE, null);
	}

	@NonNull
	static Expectation protocolError(@NonNull String requestId,
			@NonNull String method, @NonNull String operationName,
			@NonNull McpOperationType operationType) {
		return new Expectation(requestId, method, operationName, operationType,
				McpRequestOutcome.PROTOCOL_ERROR,
				INVALID_PARAMS_ERROR_CODE);
	}

	private static boolean hasRequestId(@NonNull McpRequestContext context,
			@NonNull String requestId) {
		return requireNonNull(context).getRequestId().equals(
				Optional.of(McpRequestId.fromString(requireNonNull(requestId))));
	}

	record Expectation(@NonNull String requestId, @NonNull String method,
			@Nullable String operationName,
			@NonNull McpOperationType operationType,
			@NonNull McpRequestOutcome outcome, @Nullable Integer errorCode) {
		Expectation {
			requireNonNull(requestId);
			requireNonNull(method);
			requireNonNull(operationType);
			requireNonNull(outcome);
		}
	}

	private record Finish(@NonNull McpRequestContext context,
			@NonNull McpRequestOutcome outcome, @Nullable McpJsonRpcError error,
			@NonNull Duration duration,
			@NonNull List<@NonNull Throwable> throwables) {
		private Finish {
			requireNonNull(context);
			requireNonNull(outcome);
			requireNonNull(duration);
			throwables = List.copyOf(requireNonNull(throwables));
		}
	}

	private record MetricFinish(@NonNull String method,
			@NonNull McpRequestOutcome outcome) {
		private MetricFinish {
			requireNonNull(method);
			requireNonNull(outcome);
		}
	}
}
