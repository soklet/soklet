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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Focused state, authorization, bound, and concurrency tests for the built-in
 * in-memory MCP task manager.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public class McpInMemoryTaskManagerTests {
	@Test
	public void defaultsAndBuilderResetsAreFiniteAndValidated() {
		McpInMemoryTaskManager defaults =
				McpTaskManager.fromInMemoryDefaults();
		Assertions.assertEquals(Integer.valueOf(1_024),
				defaults.getMaximumRetainedTasks());
		Assertions.assertEquals(Duration.ofHours(1),
				defaults.getTaskTimeToLive());
		Assertions.assertEquals(Duration.ofSeconds(1),
				defaults.getPollInterval());

		McpInMemoryTaskManager reset = McpInMemoryTaskManager.builder()
				.maximumRetainedTasks(3)
				.taskTimeToLive(Duration.ofMinutes(2))
				.pollInterval(Duration.ofMillis(25))
				.maximumRetainedTasks(null)
				.taskTimeToLive(null)
				.pollInterval(null)
				.build();
		Assertions.assertEquals(defaults.getMaximumRetainedTasks(),
				reset.getMaximumRetainedTasks());
		Assertions.assertEquals(defaults.getTaskTimeToLive(),
				reset.getTaskTimeToLive());
		Assertions.assertEquals(defaults.getPollInterval(),
				reset.getPollInterval());

		Assertions.assertThrows(IllegalArgumentException.class, () ->
				McpInMemoryTaskManager.builder().maximumRetainedTasks(0));
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				McpInMemoryTaskManager.builder().taskTimeToLive(Duration.ZERO));
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				McpInMemoryTaskManager.builder().pollInterval(Duration.ZERO));
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				McpInMemoryTaskManager.builder()
						.taskTimeToLive(Duration.ofNanos(1)));
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				McpInMemoryTaskManager.builder()
						.pollInterval(Duration.ofMillis(1).plusNanos(1)));
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				McpInMemoryTaskManager.builder()
						.taskTimeToLive(Duration.ofSeconds(Long.MAX_VALUE)));
	}

	@Test
	public void creationIsImmediatelyReadableAndCapacityNeverEvictsLiveTasks()
			throws Exception {
		MutableTime time = new MutableTime(1_000L, 10L);
		McpInMemoryTaskManager manager = manager(1, Duration.ofSeconds(2),
				time);
		McpTaskControl control = control("/one", "owner", "first");

		McpTask first = manager.createTask(control);
		UUID taskUuid = UUID.fromString(first.getTaskId());
		Assertions.assertEquals(4, taskUuid.version());
		Assertions.assertEquals(2, taskUuid.variant());
		Assertions.assertEquals(McpTaskStatus.WORKING,
				first.getTaskStatus());
		Assertions.assertEquals(Instant.ofEpochMilli(1_000L),
				first.getCreatedAt());
		Assertions.assertEquals(Duration.ofSeconds(2),
				first.getTimeToLive().orElseThrow());
		Assertions.assertEquals(Duration.ofMillis(25),
				first.getPollInterval().orElseThrow());
		Assertions.assertEquals(control.getTaskOrigin(), first.getTaskOrigin());
		Assertions.assertSame(first,
				manager.findTask(first.getTaskId()).orElseThrow());
		Assertions.assertSame(first, manager.findTask(requestContext(
				control.getRequestContext(), first.getTaskId())).orElseThrow());

		Assertions.assertThrows(IllegalStateException.class,
				() -> manager.createTask(control("/one", "owner", "second")));
		Assertions.assertSame(first,
				manager.findTask(first.getTaskId()).orElseThrow());

		time.advance(Duration.ofMillis(1_999));
		Assertions.assertTrue(manager.findTask(first.getTaskId()).isPresent());
		time.advance(Duration.ofMillis(1));
		Assertions.assertTrue(manager.findTask(first.getTaskId()).isEmpty());
		McpTask replacement = manager.createTask(
				control("/one", "owner", "replacement"));
		Assertions.assertTrue(manager.findTask(replacement.getTaskId()).isPresent());
	}

	@Test
	public void malformedTaskControlFailsBeforeCapacityIsConsumed() {
		McpInMemoryTaskManager manager = McpInMemoryTaskManager.builder()
				.maximumRetainedTasks(1)
				.build();
		McpRequestContext requestContext = requestContext("/one", "owner");

		NullPointerException missingRequestContext = Assertions.assertThrows(
				NullPointerException.class,
				() -> manager.createTask(malformedControl(null, null)));
		Assertions.assertEquals("taskControl.getRequestContext()",
				missingRequestContext.getMessage());
		NullPointerException missingOrigin = Assertions.assertThrows(
				NullPointerException.class,
				() -> manager.createTask(malformedControl(requestContext, null)));
		Assertions.assertEquals("taskControl.getTaskOrigin()",
				missingOrigin.getMessage());

		McpTask validTask = manager.createTask(
				control("/one", "owner", "valid"));
		Assertions.assertTrue(manager.findTask(validTask.getTaskId()).isPresent());
	}

	@Test
	public void protocolAuthorizationUsesEndpointAndExactAuthorizationPartition()
			throws Exception {
		McpInMemoryTaskManager manager =
				McpInMemoryTaskManager.builder().build();
		McpTaskControl ownerControl = control("/one", "owner", "origin");
		McpTask task = manager.createTask(ownerControl);

		McpRequestContext sameOwnerNewRequest = requestContext("/one", "owner");
		Assertions.assertTrue(manager.findTask(requestContext(sameOwnerNewRequest,
				task.getTaskId())).isPresent());
		Assertions.assertTrue(manager.findTask(requestContext(
				requestContext("/one", "other"), task.getTaskId())).isEmpty());
		Assertions.assertTrue(manager.findTask(requestContext(
				requestContext("/two", "owner"), task.getTaskId())).isEmpty());
		Assertions.assertTrue(manager.findTask(requestContext(
				requestContext("/one", null), task.getTaskId())).isEmpty());

		McpTaskRequestContext unauthorized = requestContext(
				requestContext("/one", "other"), task.getTaskId());
		McpTaskRequestContext unknown = requestContext(sameOwnerNewRequest,
				"00000000-0000-4000-8000-000000000000");
		McpTaskNotFoundException unauthorizedFailure = Assertions.assertThrows(
				McpTaskNotFoundException.class,
				() -> manager.requestTaskCancelation(unauthorized));
		McpTaskNotFoundException unknownFailure = Assertions.assertThrows(
				McpTaskNotFoundException.class,
				() -> manager.requestTaskCancelation(unknown));
		Assertions.assertEquals(unknownFailure.getMessage(),
				unauthorizedFailure.getMessage());
		Assertions.assertFalse(unauthorizedFailure.getMessage().contains(
				task.getTaskId()));
		Assertions.assertFalse(unauthorizedFailure.getMessage().contains("owner"));
		Assertions.assertFalse(manager.isTaskCancelationRequested(
				task.getTaskId()));

		manager.requestTaskInput(task.getTaskId(),
				Map.of("answer", inputRequest("answer")), null);
		McpTaskNotFoundException unauthorizedUpdateFailure =
				Assertions.assertThrows(McpTaskNotFoundException.class, () ->
						manager.updateTask(updateContext(
								requestContext("/one", "other"), task.getTaskId(),
								Map.of("answer", inputResponse("secret")))));
		Assertions.assertEquals(unknownFailure.getMessage(),
				unauthorizedUpdateFailure.getMessage());
		Assertions.assertTrue(manager.findTask(task.getTaskId()).orElseThrow()
				.getInputRequests().containsKey("answer"));
		Assertions.assertTrue(manager.takeTaskInputResponses(task.getTaskId())
				.asMap().isEmpty());

		McpTask anonymous = manager.createTask(
				control("/anonymous", null, "anonymous"));
		Assertions.assertTrue(manager.findTask(requestContext(
				requestContext("/anonymous", null), anonymous.getTaskId()))
				.isPresent());
		Assertions.assertTrue(manager.findTask(requestContext(
				requestContext("/another", null), anonymous.getTaskId()))
				.isEmpty());
	}

	@Test
	public void inputResponsesArePartialIdempotentAndTakenAtomically()
			throws Exception {
		McpInMemoryTaskManager manager =
				McpInMemoryTaskManager.builder().build();
		McpTaskControl control = control("/one", "owner", "origin");
		McpTask task = manager.createTask(control);
		McpInputRequest firstRequest = inputRequest("first");
		McpInputRequest secondRequest = inputRequest("second");

		McpTask waiting = manager.requestTaskInput(task.getTaskId(), Map.of(
				"first", firstRequest, "second", secondRequest), "Waiting");
		Assertions.assertEquals(McpTaskStatus.INPUT_REQUIRED,
				waiting.getTaskStatus());
		Assertions.assertEquals("Waiting",
				waiting.getTaskStatusMessage().orElseThrow());
		Assertions.assertEquals(2, waiting.getInputRequests().size());

		McpTask idempotent = manager.requestTaskInput(task.getTaskId(),
				Map.of("first", firstRequest), "Waiting");
		Assertions.assertSame(waiting, idempotent);
		Assertions.assertEquals(waiting.getInputRequests(),
				idempotent.getInputRequests());
		IllegalArgumentException reusedKeyFailure = Assertions.assertThrows(
				IllegalArgumentException.class, () ->
				manager.requestTaskInput(task.getTaskId(),
						Map.of("first", inputRequest("different")), null));
		Assertions.assertFalse(reusedKeyFailure.getMessage().contains("first"));
		Assertions.assertFalse(reusedKeyFailure.getMessage().contains(
				task.getTaskId()));

		manager.updateTask(updateContext(control.getRequestContext(),
				task.getTaskId(), Map.of("first", McpJsonObject.builder()
						.put("roots", McpJsonArray.emptyInstance()).build())));
		Assertions.assertEquals(waiting,
				manager.findTask(task.getTaskId()).orElseThrow());
		Assertions.assertTrue(manager.takeTaskInputResponses(task.getTaskId())
				.asMap().isEmpty());

		manager.updateTask(updateContext(control.getRequestContext(),
				task.getTaskId(), Map.of(
					"first", inputResponse("answer-one"),
					"unknown", inputResponse("ignored"))));
		McpTask partiallyAnswered = manager.findTask(task.getTaskId())
				.orElseThrow();
		Assertions.assertEquals(McpTaskStatus.INPUT_REQUIRED,
				partiallyAnswered.getTaskStatus());
		Assertions.assertEquals(List.of("second"),
				List.copyOf(partiallyAnswered.getInputRequests().keySet()));
		Assertions.assertThrows(IllegalStateException.class, () ->
				manager.requestTaskInput(task.getTaskId(),
						Map.of("third", inputRequest("third")), null));

		McpInputResponses firstResponses = manager.takeTaskInputResponses(
				task.getTaskId());
		Assertions.assertEquals(inputResponse("answer-one"),
				firstResponses.find("first").orElseThrow());
		Assertions.assertTrue(firstResponses.find("unknown").isEmpty());
		Assertions.assertTrue(manager.takeTaskInputResponses(task.getTaskId())
				.asMap().isEmpty());

		manager.updateTask(updateContext(control.getRequestContext(),
				task.getTaskId(), Map.of(
					"first", inputResponse("duplicate"),
					"second", inputResponse("answer-two"))));
		McpTask resumed = manager.findTask(task.getTaskId()).orElseThrow();
		Assertions.assertEquals(McpTaskStatus.WORKING, resumed.getTaskStatus());
		Assertions.assertTrue(resumed.getInputRequests().isEmpty());
		Assertions.assertEquals(inputResponse("answer-two"),
				manager.takeTaskInputResponses(task.getTaskId())
						.find("second").orElseThrow());
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				manager.requestTaskInput(task.getTaskId(),
						Map.of("first", firstRequest), null));

		McpTask arbitraryKeyTask = manager.createTask(
				control("/one", "owner", "arbitrary-keys"));
		McpTask arbitraryKeys = manager.requestTaskInput(
				arbitraryKeyTask.getTaskId(),
				Map.of("", inputRequest("empty"),
						"   ", inputRequest("whitespace")), null);
		Assertions.assertTrue(arbitraryKeys.getInputRequests().containsKey(""));
		Assertions.assertTrue(arbitraryKeys.getInputRequests().containsKey("   "));

		McpTask supersededTask = manager.createTask(
				control("/one", "owner", "superseded"));
		manager.requestTaskInput(supersededTask.getTaskId(),
				Map.of("superseded", inputRequest("superseded")), null);
		manager.markTaskWorking(supersededTask.getTaskId(), null);
		manager.updateTask(updateContext(control.getRequestContext(),
				supersededTask.getTaskId(), Map.of("superseded",
						inputResponse("ignored"))));
		Assertions.assertTrue(manager.takeTaskInputResponses(
				supersededTask.getTaskId()).asMap().isEmpty());
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				manager.requestTaskInput(supersededTask.getTaskId(),
						Map.of("superseded", inputRequest("new")), null));
	}

	@Test
	public void failedTaskUpdateLeavesInputAndResponseHandoffUnchanged()
			throws Exception {
		MutableTime time = new MutableTime(100L, 200L);
		McpInMemoryTaskManager manager = manager(2, Duration.ofMinutes(1), time);
		McpTaskControl control = control("/one", "owner", "origin");
		McpTask task = manager.createTask(control);
		manager.requestTaskInput(task.getTaskId(),
				Map.of("answer", inputRequest("answer")), "Waiting");

		time.failNextInstant();
		Assertions.assertThrows(IllegalStateException.class, () ->
				manager.updateTask(updateContext(control.getRequestContext(),
						task.getTaskId(), Map.of("answer",
								inputResponse("first try")))));
		McpTask unchanged = manager.findTask(task.getTaskId()).orElseThrow();
		Assertions.assertEquals(McpTaskStatus.INPUT_REQUIRED,
				unchanged.getTaskStatus());
		Assertions.assertTrue(unchanged.getInputRequests().containsKey("answer"));
		Assertions.assertTrue(manager.takeTaskInputResponses(task.getTaskId())
				.asMap().isEmpty());

		manager.updateTask(updateContext(control.getRequestContext(),
				task.getTaskId(), Map.of("answer",
						inputResponse("second try"))));
		Assertions.assertEquals(inputResponse("second try"),
				manager.takeTaskInputResponses(task.getTaskId())
						.find("answer").orElseThrow());
	}

	@Test
	public void workerTransitionsPreserveOriginAndMakeTerminalStateImmutable()
			throws Exception {
		MutableTime time = new MutableTime(100L, 200L);
		McpInMemoryTaskManager manager = manager(8, Duration.ofMinutes(1), time);
		McpTaskControl control = control("/one", "owner", "origin");
		McpTask task = manager.createTask(control);

		time.advance(Duration.ofMillis(5));
		McpTask working = manager.markTaskWorking(task.getTaskId(), "Running");
		Assertions.assertEquals("Running",
				working.getTaskStatusMessage().orElseThrow());
		Assertions.assertEquals(task.getTaskOrigin(), working.getTaskOrigin());
		Assertions.assertEquals(task.getCreatedAt(), working.getCreatedAt());
		Assertions.assertEquals(Instant.ofEpochMilli(105L),
				working.getLastUpdatedAt());

		McpCompleteResult result = McpCompleteResult.fromToolText("done");
		McpTask completed = manager.completeTask(task.getTaskId(), result,
				"Complete");
		Assertions.assertEquals(McpTaskStatus.COMPLETED,
				completed.getTaskStatus());
		Assertions.assertSame(result,
				completed.getCompletedResult().orElseThrow());
		Assertions.assertThrows(IllegalStateException.class, () ->
				manager.failTask(task.getTaskId(),
						McpJsonRpcError.fromApplication(1, "late"), null));
		Assertions.assertThrows(IllegalStateException.class,
				() -> manager.cancelTask(task.getTaskId(), null));
		Assertions.assertThrows(IllegalStateException.class,
				() -> manager.markTaskWorking(task.getTaskId(), null));

		McpTask failedSource = manager.createTask(
				control("/one", "owner", "failure"));
		McpJsonRpcError failure = McpJsonRpcError.fromApplication(2, "failed");
		McpTask failed = manager.failTask(failedSource.getTaskId(), failure,
				"Could not finish");
		Assertions.assertEquals(McpTaskStatus.FAILED, failed.getTaskStatus());
		Assertions.assertSame(failure, failed.getFailure().orElseThrow());

		McpTask canceledSource = manager.createTask(
				control("/one", "owner", "cancel"));
		McpTask canceled = manager.cancelTask(canceledSource.getTaskId(),
				"Canceled by worker");
		Assertions.assertEquals(McpTaskStatus.CANCELED,
				canceled.getTaskStatus());
	}

	@Test
	public void protocolCancelationRecordsIntentWithoutChangingTaskStatus()
			throws Exception {
		McpInMemoryTaskManager manager =
				McpInMemoryTaskManager.builder().build();
		McpTaskControl control = control("/one", "owner", "origin");
		McpTask task = manager.createTask(control);
		McpTaskRequestContext requestContext = requestContext(
				control.getRequestContext(), task.getTaskId());

		manager.requestTaskCancelation(requestContext);
		manager.requestTaskCancelation(requestContext);
		Assertions.assertTrue(manager.isTaskCancelationRequested(task.getTaskId()));
		Assertions.assertEquals(McpTaskStatus.WORKING,
				manager.findTask(task.getTaskId()).orElseThrow().getTaskStatus());

		manager.completeTask(task.getTaskId(),
				McpCompleteResult.fromToolText("won the race"), null);
		Assertions.assertTrue(manager.isTaskCancelationRequested(task.getTaskId()));
		manager.requestTaskCancelation(requestContext);
		Assertions.assertEquals(McpTaskStatus.COMPLETED,
				manager.findTask(task.getTaskId()).orElseThrow().getTaskStatus());
	}

	@Test
	public void competingTerminalTransitionsHaveExactlyOneWinner()
			throws Exception {
		McpInMemoryTaskManager manager =
				McpInMemoryTaskManager.builder().build();
		McpTask task = manager.createTask(control("/one", "owner", "origin"));
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(12);
		try {
			List<Callable<Boolean>> transitions = new ArrayList<>();
			for (int index = 0; index < 12; index++) {
				int selected = index % 3;
				transitions.add(() -> {
					start.await();
					try {
						if (selected == 0)
							manager.completeTask(task.getTaskId(),
									McpCompleteResult.fromToolText("done"), null);
						else if (selected == 1)
							manager.failTask(task.getTaskId(),
									McpJsonRpcError.fromApplication(5, "failed"),
									null);
						else
							manager.cancelTask(task.getTaskId(), null);
						return true;
					} catch (IllegalStateException exception) {
						return false;
					}
				});
			}
			List<Future<Boolean>> futures = new ArrayList<>();
			for (Callable<Boolean> transition : transitions)
				futures.add(executor.submit(transition));
			start.countDown();
			long winners = 0;
			for (Future<Boolean> future : futures)
				if (future.get())
					winners++;
			Assertions.assertEquals(1L, winners);
			Assertions.assertTrue(switch (manager.findTask(task.getTaskId())
					.orElseThrow().getTaskStatus()) {
				case COMPLETED, FAILED, CANCELED -> true;
				case WORKING, INPUT_REQUIRED -> false;
			});
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	public void expirationUsesMonotonicElapsedTimeAcrossNanoTimeWrap()
			throws Exception {
		MutableTime time = new MutableTime(0L, Long.MAX_VALUE - 500_000L);
		McpInMemoryTaskManager manager = manager(1, Duration.ofMillis(1), time);
		McpTask task = manager.createTask(control("/one", "owner", "origin"));

		time.advance(Duration.ofNanos(999_999L));
		Assertions.assertTrue(manager.findTask(task.getTaskId()).isPresent());
		time.advance(Duration.ofNanos(1L));
		Assertions.assertTrue(manager.findTask(task.getTaskId()).isEmpty());
		Assertions.assertThrows(McpTaskNotFoundException.class,
				() -> manager.markTaskWorking(task.getTaskId(), null));
	}

	@NonNull
	private static McpInMemoryTaskManager manager(int maximumRetainedTasks,
			@NonNull Duration taskTimeToLive, @NonNull MutableTime time) {
		McpInMemoryTaskManager.Builder builder = McpInMemoryTaskManager.builder()
				.maximumRetainedTasks(maximumRetainedTasks)
				.taskTimeToLive(taskTimeToLive)
				.pollInterval(Duration.ofMillis(25));
		return new McpInMemoryTaskManager(builder, time, time);
	}

	@NonNull
	private static McpTaskControl control(@NonNull String endpointPath,
			@Nullable String authorizationPartitionKey,
			@NonNull String originValue) {
		McpRequestContext requestContext = requestContext(endpointPath,
				authorizationPartitionKey);
		McpTaskOrigin taskOrigin = McpTaskOrigin.fromPersistedState(
				McpJsonObject.builder().put("origin", originValue).build());
		return new McpTaskControl() {
			@Override
			@NonNull
			public McpRequestContext getRequestContext() {
				return requestContext;
			}

			@Override
			@NonNull
			public McpTaskOrigin getTaskOrigin() {
				return taskOrigin;
			}
		};
	}

	@NonNull
	private static McpTaskControl malformedControl(
			@Nullable McpRequestContext requestContext,
			@Nullable McpTaskOrigin taskOrigin) {
		return (McpTaskControl) Proxy.newProxyInstance(
				McpTaskControl.class.getClassLoader(),
				new Class<?>[]{McpTaskControl.class},
				(proxy, method, arguments) -> switch (method.getName()) {
					case "getRequestContext" -> requestContext;
					case "getTaskOrigin" -> taskOrigin;
					case "toString" -> "MalformedMcpTaskControlTestFixture";
					case "hashCode" -> System.identityHashCode(proxy);
					case "equals" -> proxy == arguments[0];
					default -> throw new UnsupportedOperationException(
							method.getName());
				});
	}

	@NonNull
	private static McpRequestContext requestContext(@NonNull String endpointPath,
			@Nullable String authorizationPartitionKey) {
		McpEndpoint endpoint = McpEndpoint.withPath(endpointPath,
				McpImplementation.withNameAndVersion("test", "1").build())
				.build();
		McpAdmissionIdentity identity;
		if (authorizationPartitionKey == null) {
			identity = McpAdmissionIdentity.anonymousInstance();
		} else {
			identity = McpAdmissionIdentity
					.withRateLimitPartitionKey("rate-" + authorizationPartitionKey)
					.authorizationPartitionKey(authorizationPartitionKey)
					.build();
		}
		return (McpRequestContext) Proxy.newProxyInstance(
				McpRequestContext.class.getClassLoader(),
				new Class<?>[]{McpRequestContext.class},
				(proxy, method, arguments) -> switch (method.getName()) {
					case "getEndpoint" -> endpoint;
					case "getAdmissionIdentity" -> identity;
					case "toString" -> "McpRequestContextTestFixture";
					case "hashCode" -> System.identityHashCode(proxy);
					case "equals" -> proxy == arguments[0];
					default -> throw new UnsupportedOperationException(
							method.getName());
				});
	}

	@NonNull
	private static McpTaskRequestContext requestContext(
			@NonNull McpRequestContext requestContext,
			@NonNull String taskId) {
		return new McpTaskRequestContext(requestContext, taskId);
	}

	@NonNull
	private static McpTaskUpdateContext updateContext(
			@NonNull McpRequestContext requestContext,
			@NonNull String taskId,
			@NonNull Map<@NonNull String,
					? extends @NonNull McpJsonValue> responses) {
		return new McpTaskUpdateContext(requestContext, taskId,
				McpInputResponses.fromResponses(responses));
	}

	@NonNull
	private static McpInputRequest inputRequest(@NonNull String value) {
		return McpInputRequest.fromDeclaration(
				McpInputRequestDeclaration.fromElicitationForm(
						McpInputRequirement.CONDITIONAL),
				McpJsonObject.builder()
						.put("mode", "form")
						.put("message", value)
						.put("requestedSchema", McpJsonObject.builder()
								.put("type", "object")
								.put("properties", McpJsonObject.builder()
										.put("value", McpJsonObject.builder()
												.put("type", "string")
												.build())
										.build())
								.build())
						.build());
	}

	@NonNull
	private static McpJsonObject inputResponse(@NonNull String value) {
		return McpJsonObject.builder()
				.put("action", "accept")
				.put("content", McpJsonObject.builder()
						.put("value", value)
						.build())
				.build();
	}

	private static final class MutableTime extends Clock
			implements LongSupplier {
		@NonNull
		private final AtomicLong epochNanos;
		@NonNull
		private final AtomicLong monotonicNanos;
		@NonNull
		private final AtomicBoolean failNextInstant;

		private MutableTime(long epochMillis, long monotonicNanos) {
			this.epochNanos = new AtomicLong(
					Duration.ofMillis(epochMillis).toNanos());
			this.monotonicNanos = new AtomicLong(monotonicNanos);
			this.failNextInstant = new AtomicBoolean();
		}

		private void failNextInstant() {
			this.failNextInstant.set(true);
		}

		private void advance(@NonNull Duration duration) {
			long nanos = duration.toNanos();
			this.epochNanos.addAndGet(nanos);
			this.monotonicNanos.addAndGet(nanos);
		}

		@Override
		@NonNull
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		@NonNull
		public Clock withZone(@NonNull ZoneId zone) {
			if (!ZoneOffset.UTC.equals(zone))
				throw new IllegalArgumentException("Only UTC is supported.");
			return this;
		}

		@Override
		@NonNull
		public Instant instant() {
			if (this.failNextInstant.compareAndSet(true, false))
				throw new IllegalStateException("Synthetic clock failure.");
			long nanos = this.epochNanos.get();
			return Instant.ofEpochSecond(Math.floorDiv(nanos, 1_000_000_000L),
					Math.floorMod(nanos, 1_000_000_000L));
		}

		@Override
		public long getAsLong() {
			return this.monotonicNanos.get();
		}
	}
}
