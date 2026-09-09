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

import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

import static java.util.Objects.requireNonNull;

/**
 * Thread-safe, task-count-bounded, process-local manager for development and
 * tests.
 * <p>
 * State exists only in this manager instance and is lost when its JVM exits.
 * The manager provides no cross-process coordination, durable persistence,
 * work queue, worker, lease, fencing, retry, or crash-recovery facility. It is
 * therefore not a production task backend. Applications remain responsible
 * for executing work and explicitly advancing task state through this class.
 * <p>
 * Retention is bounded by task count. Task values remain subject to Soklet's
 * ordinary MCP request, JSON, and response limits when they cross the protocol
 * boundary; this development helper does not attempt to estimate their JVM
 * object sizes.
 * <p>
 * The manager never creates a thread and requires no close operation. Expired
 * tasks are removed opportunistically during later manager operations. It
 * never evicts an unexpired task to make room for another task.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpInMemoryTaskManager implements McpTaskManager {
	private static final int DEFAULT_MAXIMUM_RETAINED_TASKS = 1_024;
	@NonNull
	private static final Duration DEFAULT_TASK_TIME_TO_LIVE =
			Duration.ofHours(1);
	@NonNull
	private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(1);

	private final int maximumRetainedTasks;
	@NonNull
	private final Duration taskTimeToLive;
	private final long taskTimeToLiveNanos;
	@NonNull
	private final Duration pollInterval;
	@NonNull
	private final Clock clock;
	@NonNull
	private final LongSupplier nanoTime;
	@NonNull
	private final ReentrantLock lock;
	@GuardedBy("lock")
	@NonNull
	private final Map<@NonNull String, @NonNull Entry> entries;

	/**
	 * Vends a builder initialized with finite development defaults.
	 *
	 * @return in-memory task-manager builder
	 */
	@NonNull
	public static Builder builder() {
		return new Builder();
	}

	McpInMemoryTaskManager() {
		this(builder());
	}

	private McpInMemoryTaskManager(@NonNull Builder builder) {
		this(builder, Clock.systemUTC(), System::nanoTime);
	}

	McpInMemoryTaskManager(@NonNull Builder builder, @NonNull Clock clock,
			@NonNull LongSupplier nanoTime) {
		requireNonNull(builder);
		this.maximumRetainedTasks = builder.maximumRetainedTasks;
		this.taskTimeToLive = builder.taskTimeToLive;
		this.taskTimeToLiveNanos = this.taskTimeToLive.toNanos();
		this.pollInterval = builder.pollInterval;
		this.clock = requireNonNull(clock);
		this.nanoTime = requireNonNull(nanoTime);
		this.lock = new ReentrantLock();
		this.entries = new HashMap<>();
	}

	/** @return positive maximum number of simultaneously retained tasks */
	@NonNull
	public Integer getMaximumRetainedTasks() {
		return this.maximumRetainedTasks;
	}

	/** @return finite lifetime of every task, measured from its creation */
	@NonNull
	public Duration getTaskTimeToLive() {
		return this.taskTimeToLive;
	}

	/** @return positive polling interval advertised for every task */
	@NonNull
	public Duration getPollInterval() {
		return this.pollInterval;
	}

	/**
	 * Creates a working task for the current task-capable tool invocation.
	 * <p>
	 * The control supplies Soklet's immutable task origin and the current
	 * endpoint and authorization partition. The manager copies those values and
	 * never retains the control, request context, principal, or application
	 * context. The returned task is immediately available through both trusted
	 * and appropriately authorized protocol lookup.
	 *
	 * @param taskControl current task control
	 * @return newly created working task
	 * @throws NullPointerException if the control or either of its required
	 *                              values is null
	 * @throws IllegalStateException if the finite task capacity is exhausted
	 */
	@NonNull
	public McpTask createTask(@NonNull McpTaskControl taskControl) {
		requireNonNull(taskControl);
		McpRequestContext requestContext = requireNonNull(
				taskControl.getRequestContext(),
				"taskControl.getRequestContext()");
		McpTaskOrigin taskOrigin = requireNonNull(taskControl.getTaskOrigin(),
				"taskControl.getTaskOrigin()");
		String endpointPath = requestContext.getEndpoint().getPath();
		Optional<String> authorizationPartitionKey = requestContext
				.getAdmissionIdentity().getAuthorizationPartitionKey();

		this.lock.lock();
		try {
			long nowNanos = this.nanoTime.getAsLong();
			removeExpiredEntries(nowNanos);
			if (this.entries.size() >= this.maximumRetainedTasks)
				throw new IllegalStateException(
						"The in-memory MCP task-manager capacity is exhausted.");

			String taskId;
			do {
				taskId = UUID.randomUUID().toString();
			} while (this.entries.containsKey(taskId));

			Instant now = this.clock.instant();
			McpTask task = McpTask.withTaskId(taskId, taskOrigin,
						McpTaskStatus.WORKING, now, now)
					.timeToLive(this.taskTimeToLive)
					.pollInterval(this.pollInterval)
					.build();
			this.entries.put(taskId, new Entry(task, endpointPath,
					authorizationPartitionKey, nowNanos));
			return task;
		} finally {
			this.lock.unlock();
		}
	}

	/**
	 * Finds a task for trusted application or worker code.
	 * <p>
	 * This overload deliberately does not perform protocol authorization. Use of
	 * a task ID through an MCP request always goes through the context-taking SPI
	 * method, which does perform authorization.
	 *
	 * @param taskId task identifier
	 * @return immutable current task, if retained
	 */
	@NonNull
	public Optional<@NonNull McpTask> findTask(@NonNull String taskId) {
		String requiredTaskId = McpTask.requireTaskId(taskId);
		this.lock.lock();
		try {
			removeExpiredEntries(this.nanoTime.getAsLong());
			Entry entry = this.entries.get(requiredTaskId);
			return entry == null ? Optional.empty() : Optional.of(entry.task);
		} finally {
			this.lock.unlock();
		}
	}

	/**
	 * Marks a retained nonterminal task as working. Outstanding input requests
	 * are superseded; later responses for their keys are ignored.
	 *
	 * @param taskId task identifier
	 * @param taskStatusMessage optional client-visible status message
	 * @return immutable updated task
	 * @throws McpTaskNotFoundException if the task is absent or expired
	 * @throws IllegalStateException if the task is terminal
	 */
	@NonNull
	public McpTask markTaskWorking(@NonNull String taskId,
			@Nullable String taskStatusMessage) throws McpTaskNotFoundException {
		return transitionTask(taskId, McpTaskStatus.WORKING,
				taskStatusMessage, Map.of(), null, null);
	}

	/**
	 * Places a retained nonterminal task into the input-required state.
	 * <p>
	 * New request keys must not have been used previously during the task's
	 * lifetime. Repeating an identical, still-outstanding key and request is an
	 * idempotent no-op; every other reuse is rejected. Accepted client responses
	 * must be taken before another input round begins.
	 *
	 * @param taskId task identifier
	 * @param inputRequests nonempty input requests to add
	 * @param taskStatusMessage optional client-visible status message
	 * @return immutable updated task
	 * @throws McpTaskNotFoundException if the task is absent or expired
	 * @throws IllegalStateException if the task is terminal or accepted input
	 *                               remains untaken
	 * @throws IllegalArgumentException if the map is empty or reuses a key
	 */
	@NonNull
	public McpTask requestTaskInput(@NonNull String taskId,
			@NonNull Map<@NonNull String,
					? extends @NonNull McpInputRequest> inputRequests,
			@Nullable String taskStatusMessage) throws McpTaskNotFoundException {
		requireNonNull(inputRequests);
		if (inputRequests.isEmpty())
			throw new IllegalArgumentException("inputRequests must not be empty");
		String requiredTaskId = McpTask.requireTaskId(taskId);

		this.lock.lock();
		try {
			Entry entry = requireEntry(requiredTaskId,
					this.nanoTime.getAsLong());
			requireNonterminal(entry.task);
			if (!entry.pendingInputResponses.isEmpty())
				throw new IllegalStateException(
						"Accepted MCP task input responses must be taken before requesting more input.");

			Map<String, McpInputRequest> combinedInputRequests =
					new LinkedHashMap<>(entry.task.getInputRequests());
			Set<String> newlyIssuedKeys = new LinkedHashSet<>();
			for (Map.Entry<@NonNull String,
					? extends @NonNull McpInputRequest> inputRequestEntry
					: inputRequests.entrySet()) {
				String key = requireInputRequestKey(inputRequestEntry.getKey());
				McpInputRequest inputRequest = requireNonNull(
						inputRequestEntry.getValue());
				McpInputRequest outstanding = combinedInputRequests.get(key);
				if (outstanding != null) {
					if (!outstanding.equals(inputRequest))
						throw reusedInputRequestKey(key);
					continue;
				}
				if (entry.issuedInputRequestKeys.contains(key))
					throw reusedInputRequestKey(key);
				combinedInputRequests.put(key, inputRequest);
				newlyIssuedKeys.add(key);
			}
			if (newlyIssuedKeys.isEmpty()
					&& entry.task.getTaskStatus() == McpTaskStatus.INPUT_REQUIRED
					&& entry.task.getTaskStatusMessage().equals(
							Optional.ofNullable(taskStatusMessage)))
				return entry.task;

			McpTask updatedTask = buildTask(entry, McpTaskStatus.INPUT_REQUIRED,
					taskStatusMessage, combinedInputRequests, null, null);
			entry.issuedInputRequestKeys.addAll(newlyIssuedKeys);
			entry.task = updatedTask;
			return updatedTask;
		} finally {
			this.lock.unlock();
		}
	}

	/**
	 * Completes a retained nonterminal task. A tool result whose
	 * {@code isError} value is true is still a completed task result.
	 *
	 * @param taskId task identifier
	 * @param completeResult complete MCP result
	 * @param taskStatusMessage optional client-visible status message
	 * @return immutable completed task
	 * @throws McpTaskNotFoundException if the task is absent or expired
	 * @throws IllegalStateException if the task is terminal
	 */
	@NonNull
	public McpTask completeTask(@NonNull String taskId,
			@NonNull McpCompleteResult completeResult,
			@Nullable String taskStatusMessage) throws McpTaskNotFoundException {
		return transitionTask(taskId, McpTaskStatus.COMPLETED,
				taskStatusMessage, Map.of(), requireNonNull(completeResult), null);
	}

	/**
	 * Fails a retained nonterminal task with a client-visible JSON-RPC error.
	 *
	 * @param taskId task identifier
	 * @param failure task failure
	 * @param taskStatusMessage optional client-visible status message
	 * @return immutable failed task
	 * @throws McpTaskNotFoundException if the task is absent or expired
	 * @throws IllegalStateException if the task is terminal
	 */
	@NonNull
	public McpTask failTask(@NonNull String taskId,
			@NonNull McpJsonRpcError failure,
			@Nullable String taskStatusMessage) throws McpTaskNotFoundException {
		return transitionTask(taskId, McpTaskStatus.FAILED,
				taskStatusMessage, Map.of(), null, requireNonNull(failure));
	}

	/**
	 * Marks a retained nonterminal task as canceled. This worker-facing state
	 * transition is separate from the protocol's cooperative cancelation request.
	 *
	 * @param taskId task identifier
	 * @param taskStatusMessage optional client-visible status message
	 * @return immutable canceled task
	 * @throws McpTaskNotFoundException if the task is absent or expired
	 * @throws IllegalStateException if the task is terminal
	 */
	@NonNull
	public McpTask cancelTask(@NonNull String taskId,
			@Nullable String taskStatusMessage) throws McpTaskNotFoundException {
		return transitionTask(taskId, McpTaskStatus.CANCELED,
				taskStatusMessage, Map.of(), null, null);
	}

	/**
	 * Atomically takes and clears input responses accepted for a retained task.
	 * This trusted worker operation is nonblocking.
	 *
	 * @param taskId task identifier
	 * @return accepted responses, possibly empty
	 * @throws McpTaskNotFoundException if the task is absent or expired
	 */
	@NonNull
	public McpInputResponses takeTaskInputResponses(@NonNull String taskId)
			throws McpTaskNotFoundException {
		String requiredTaskId = McpTask.requireTaskId(taskId);
		this.lock.lock();
		try {
			Entry entry = requireEntry(requiredTaskId,
					this.nanoTime.getAsLong());
			McpInputResponses inputResponses = McpInputResponses.fromResponses(
					entry.pendingInputResponses);
			entry.pendingInputResponses.clear();
			return inputResponses;
		} finally {
			this.lock.unlock();
		}
	}

	/**
	 * Returns whether a client has requested cancelation of a retained task.
	 * The signal never changes task status by itself.
	 *
	 * @param taskId task identifier
	 * @return whether cancelation was requested
	 * @throws McpTaskNotFoundException if the task is absent or expired
	 */
	@NonNull
	public Boolean isTaskCancelationRequested(@NonNull String taskId)
			throws McpTaskNotFoundException {
		String requiredTaskId = McpTask.requireTaskId(taskId);
		this.lock.lock();
		try {
			return requireEntry(requiredTaskId,
					this.nanoTime.getAsLong()).cancelationRequested;
		} finally {
			this.lock.unlock();
		}
	}

	/**
	 * Finds the task visible to the requesting MCP authorization partition and
	 * endpoint. An unknown task and a task owned by another partition or
	 * endpoint are deliberately indistinguishable.
	 *
	 * @param context protocol task lookup context
	 * @return immutable current task, if retained and authorized
	 */
	@Override
	@NonNull
	public Optional<@NonNull McpTask> findTask(
			@NonNull McpTaskRequestContext context) {
		requireNonNull(context);
		this.lock.lock();
		try {
			removeExpiredEntries(this.nanoTime.getAsLong());
			Entry entry = this.entries.get(context.getTaskId());
			if (entry == null || !isAuthorized(entry,
					context.getRequestContext()))
				return Optional.empty();
			return Optional.of(entry.task);
		} finally {
			this.lock.unlock();
		}
	}

	/**
	 * Accepts responses for currently outstanding input requests visible to the
	 * requesting MCP authorization partition and endpoint. Unknown or already
	 * consumed response keys and responses whose union variant does not match the
	 * outstanding request are ignored.
	 *
	 * @param context protocol task update context
	 * @throws McpTaskNotFoundException if the task is absent, expired, or not
	 *                                  visible to this request
	 */
	@Override
	public void updateTask(@NonNull McpTaskUpdateContext context)
			throws McpTaskNotFoundException {
		requireNonNull(context);
		this.lock.lock();
		try {
			Entry entry = requireAuthorizedEntry(context.getTaskId(),
					context.getRequestContext(), this.nanoTime.getAsLong());
			if (isTerminal(entry.task.getTaskStatus())
					|| entry.task.getTaskStatus() != McpTaskStatus.INPUT_REQUIRED)
				return;

			Map<String, McpInputRequest> remainingInputRequests =
					new LinkedHashMap<>(entry.task.getInputRequests());
			Map<String, McpJsonValue> acceptedInputResponses =
					new LinkedHashMap<>();
			for (Map.Entry<@NonNull String, @NonNull McpJsonValue> response
					: context.getInputResponses().asMap().entrySet()) {
				McpInputRequest inputRequest = remainingInputRequests.get(
						response.getKey());
				if (inputRequest != null && inputRequest.matchesInputResponse(
						response.getValue())) {
					remainingInputRequests.remove(response.getKey());
					acceptedInputResponses.put(response.getKey(),
							response.getValue());
				}
			}
			if (acceptedInputResponses.isEmpty())
				return;

			McpTaskStatus status = remainingInputRequests.isEmpty()
					? McpTaskStatus.WORKING : McpTaskStatus.INPUT_REQUIRED;
			String taskStatusMessage = status == McpTaskStatus.INPUT_REQUIRED
					? entry.task.getTaskStatusMessage().orElse(null) : null;
			McpTask updatedTask = buildTask(entry, status, taskStatusMessage,
					remainingInputRequests, null, null);
			entry.pendingInputResponses.putAll(acceptedInputResponses);
			entry.task = updatedTask;
		} finally {
			this.lock.unlock();
		}
	}

	/**
	 * Records a cooperative cancelation request for a nonterminal task visible
	 * to the requesting MCP authorization partition and endpoint. This signal
	 * does not itself change the task's status.
	 *
	 * @param context protocol task cancelation context
	 * @throws McpTaskNotFoundException if the task is absent, expired, or not
	 *                                  visible to this request
	 */
	@Override
	public void requestTaskCancelation(
			@NonNull McpTaskRequestContext context)
			throws McpTaskNotFoundException {
		requireNonNull(context);
		this.lock.lock();
		try {
			Entry entry = requireAuthorizedEntry(context.getTaskId(),
					context.getRequestContext(), this.nanoTime.getAsLong());
			if (!isTerminal(entry.task.getTaskStatus()))
				entry.cancelationRequested = true;
		} finally {
			this.lock.unlock();
		}
	}

	@NonNull
	private McpTask transitionTask(@NonNull String taskId,
			@NonNull McpTaskStatus taskStatus,
			@Nullable String taskStatusMessage,
			@NonNull Map<@NonNull String,
					? extends @NonNull McpInputRequest> inputRequests,
			@Nullable McpCompleteResult completeResult,
			@Nullable McpJsonRpcError failure)
			throws McpTaskNotFoundException {
		String requiredTaskId = McpTask.requireTaskId(taskId);
		this.lock.lock();
		try {
			Entry entry = requireEntry(requiredTaskId,
					this.nanoTime.getAsLong());
			requireNonterminal(entry.task);
			McpTask updatedTask = buildTask(entry, taskStatus,
					taskStatusMessage, inputRequests, completeResult, failure);
			entry.task = updatedTask;
			if (isTerminal(taskStatus)) {
				entry.pendingInputResponses.clear();
			}
			return updatedTask;
		} finally {
			this.lock.unlock();
		}
	}

	@NonNull
	private McpTask buildTask(@NonNull Entry entry,
			@NonNull McpTaskStatus taskStatus,
			@Nullable String taskStatusMessage,
			@NonNull Map<@NonNull String,
					? extends @NonNull McpInputRequest> inputRequests,
			@Nullable McpCompleteResult completeResult,
			@Nullable McpJsonRpcError failure) {
		Instant now = this.clock.instant();
		if (now.isBefore(entry.task.getLastUpdatedAt()))
			now = entry.task.getLastUpdatedAt();
		McpTask.Builder builder = McpTask.withTaskId(entry.task.getTaskId(),
				entry.task.getTaskOrigin(), taskStatus,
				entry.task.getCreatedAt(), now)
			.taskStatusMessage(taskStatusMessage)
			.timeToLive(this.taskTimeToLive)
			.pollInterval(this.pollInterval)
			.metadata(entry.task.getMetadata());
		if (!inputRequests.isEmpty())
			builder.addInputRequests(inputRequests);
		if (completeResult != null)
			builder.completedResult(completeResult);
		if (failure != null)
			builder.failure(failure);
		return builder.build();
	}

	@NonNull
	@GuardedBy("lock")
	private Entry requireAuthorizedEntry(@NonNull String taskId,
			@NonNull McpRequestContext requestContext, long nowNanos)
			throws McpTaskNotFoundException {
		Entry entry = requireEntry(McpTask.requireTaskId(taskId), nowNanos);
		if (!isAuthorized(entry, requestContext))
			throw new McpTaskNotFoundException();
		return entry;
	}

	@NonNull
	@GuardedBy("lock")
	private Entry requireEntry(@NonNull String taskId, long nowNanos)
			throws McpTaskNotFoundException {
		removeExpiredEntries(nowNanos);
		Entry entry = this.entries.get(taskId);
		if (entry == null)
			throw new McpTaskNotFoundException();
		return entry;
	}

	private boolean isAuthorized(@NonNull Entry entry,
			@NonNull McpRequestContext requestContext) {
		return entry.endpointPath.equals(requestContext.getEndpoint().getPath())
				&& entry.authorizationPartitionKey.equals(requestContext
				.getAdmissionIdentity().getAuthorizationPartitionKey());
	}

	@GuardedBy("lock")
	private void removeExpiredEntries(long nowNanos) {
		Iterator<Entry> iterator = this.entries.values().iterator();
		while (iterator.hasNext()) {
			Entry entry = iterator.next();
			if (nowNanos - entry.createdNanos >= this.taskTimeToLiveNanos)
				iterator.remove();
		}
	}

	private static void requireNonterminal(@NonNull McpTask task) {
		if (isTerminal(task.getTaskStatus()))
			throw new IllegalStateException("An MCP task's terminal state is immutable.");
	}

	private static boolean isTerminal(@NonNull McpTaskStatus taskStatus) {
		return taskStatus == McpTaskStatus.COMPLETED
				|| taskStatus == McpTaskStatus.FAILED
				|| taskStatus == McpTaskStatus.CANCELED;
	}

	@NonNull
	private static String requireInputRequestKey(@NonNull String key) {
		return requireNonNull(key);
	}

	@NonNull
	private static IllegalArgumentException reusedInputRequestKey(
			@NonNull String key) {
		requireNonNull(key);
		return new IllegalArgumentException(
				"An MCP task input-request key must be unique for the task lifetime.");
	}

	@NonNull
	private static Duration requireWholePositiveMilliseconds(
			@NonNull Duration duration, @NonNull String name) {
		requireNonNull(duration);
		long milliseconds;
		long nanoseconds;
		try {
			milliseconds = duration.toMillis();
			nanoseconds = duration.toNanos();
		} catch (ArithmeticException exception) {
			throw new IllegalArgumentException(
					name + " must fit in a signed 64-bit nanosecond count.",
					exception);
		}
		if (milliseconds <= 0 || nanoseconds <= 0
				|| !duration.equals(Duration.ofMillis(milliseconds)))
			throw new IllegalArgumentException(
					name + " must be a positive whole-millisecond duration.");
		return duration;
	}

	/**
	 * Single-threaded builder for an in-memory task manager.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static final class Builder {
		private int maximumRetainedTasks;
		@NonNull
		private Duration taskTimeToLive;
		@NonNull
		private Duration pollInterval;

		private Builder() {
			this.maximumRetainedTasks = DEFAULT_MAXIMUM_RETAINED_TASKS;
			this.taskTimeToLive = DEFAULT_TASK_TIME_TO_LIVE;
			this.pollInterval = DEFAULT_POLL_INTERVAL;
		}

		/**
		 * Sets the positive finite task capacity. The default is {@code 1024}.
		 * A null value restores the default.
		 *
		 * @param maximumRetainedTasks maximum retained tasks, or null for default
		 * @return this builder
		 */
		@NonNull
		public Builder maximumRetainedTasks(
				@Nullable Integer maximumRetainedTasks) {
			if (maximumRetainedTasks == null) {
				this.maximumRetainedTasks = DEFAULT_MAXIMUM_RETAINED_TASKS;
				return this;
			}
			if (maximumRetainedTasks < 1)
				throw new IllegalArgumentException(
						"maximumRetainedTasks must be positive");
			this.maximumRetainedTasks = maximumRetainedTasks;
			return this;
		}

		/**
		 * Sets every task's finite lifetime from creation. The default is one
		 * hour. A null value restores the default.
		 *
		 * @param taskTimeToLive positive whole-millisecond lifetime, or null for
		 *                       default
		 * @return this builder
		 */
		@NonNull
		public Builder taskTimeToLive(@Nullable Duration taskTimeToLive) {
			this.taskTimeToLive = taskTimeToLive == null
					? DEFAULT_TASK_TIME_TO_LIVE
					: requireWholePositiveMilliseconds(taskTimeToLive,
							"taskTimeToLive");
			return this;
		}

		/**
		 * Sets every task's suggested polling interval. The default is one
		 * second. A null value restores the default.
		 *
		 * @param pollInterval positive whole-millisecond interval, or null for
		 *                     default
		 * @return this builder
		 */
		@NonNull
		public Builder pollInterval(@Nullable Duration pollInterval) {
			this.pollInterval = pollInterval == null
					? DEFAULT_POLL_INTERVAL
					: requireWholePositiveMilliseconds(pollInterval,
							"pollInterval");
			return this;
		}

		/** @return a new independent in-memory task manager */
		@NonNull
		public McpInMemoryTaskManager build() {
			return new McpInMemoryTaskManager(this);
		}
	}

	private static final class Entry {
		@NonNull
		private McpTask task;
		@NonNull
		private final String endpointPath;
		@NonNull
		private final Optional<@NonNull String> authorizationPartitionKey;
		private final long createdNanos;
		@NonNull
		private final Set<@NonNull String> issuedInputRequestKeys;
		@NonNull
		private final Map<@NonNull String, @NonNull McpJsonValue>
				pendingInputResponses;
		private boolean cancelationRequested;

		private Entry(@NonNull McpTask task, @NonNull String endpointPath,
				@NonNull Optional<@NonNull String> authorizationPartitionKey,
				long createdNanos) {
			this.task = requireNonNull(task);
			this.endpointPath = requireNonNull(endpointPath);
			this.authorizationPartitionKey = requireNonNull(
					authorizationPartitionKey);
			this.createdNanos = createdNanos;
			this.issuedInputRequestKeys = new HashSet<>();
			this.pendingInputResponses = new LinkedHashMap<>();
			this.cancelationRequested = false;
		}
	}
}
