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

import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static com.soklet.internal.mcp.protocol.McpApplicationMetadata.requireApplicationMetadata;
import static java.util.Objects.requireNonNull;

/**
 * Immutable authoritative snapshot of one MCP task.
 *
 * <p>The configured {@link McpTaskManager} returns this value after atomically
 * authorizing the current request. The origin is application-persisted
 * framework data and is never rendered on the wire. Task status determines
 * the one permitted status-specific payload: outstanding input requests for
 * {@link McpTaskStatus#INPUT_REQUIRED}, a complete result for
 * {@link McpTaskStatus#COMPLETED}, or a JSON-RPC failure for
 * {@link McpTaskStatus#FAILED}. A tool result whose {@code isError} value is
 * true is still a completed task.
 *
 * <p>This value does not contain task ownership or an authorization decision.
 * A manager must independently bind the task to the admitted principal,
 * tenant, endpoint, and other application authorization context, and must not
 * return a snapshot to an unauthorized request.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpTask {
	@NonNull
	private final String taskId;
	@NonNull
	private final McpTaskOrigin taskOrigin;
	@NonNull
	private final McpTaskStatus taskStatus;
	@Nullable
	private final String taskStatusMessage;
	@NonNull
	private final Instant createdAt;
	@NonNull
	private final Instant lastUpdatedAt;
	@Nullable
	private final Duration timeToLive;
	@Nullable
	private final Duration pollInterval;
	@NonNull
	private final Map<@NonNull String, @NonNull McpInputRequest> inputRequests;
	@Nullable
	private final McpCompleteResult completedResult;
	@Nullable
	private final McpJsonRpcError failure;
	@NonNull
	private final McpJsonObject metadata;

	/**
	 * Vends a builder primed with every required task property.
	 *
	 * @param taskId nonblank task identifier without carriage-return or newline
	 *               characters
	 * @param taskOrigin persisted framework origin
	 * @param taskStatus current task status
	 * @param createdAt task creation time
	 * @param lastUpdatedAt most recent state-update time, not before creation
	 * @return task builder
	 * @throws NullPointerException if an argument is null
	 * @throws IllegalArgumentException if the task ID or timestamps are invalid
	 */
	@NonNull
	public static Builder withTaskId(@NonNull String taskId,
			@NonNull McpTaskOrigin taskOrigin,
			@NonNull McpTaskStatus taskStatus,
			@NonNull Instant createdAt,
			@NonNull Instant lastUpdatedAt) {
		return new Builder(taskId, taskOrigin, taskStatus, createdAt,
				lastUpdatedAt);
	}

	private McpTask(@NonNull Builder builder) {
		this.taskId = requireTaskId(builder.taskId);
		this.taskOrigin = requireNonNull(builder.taskOrigin);
		this.taskStatus = requireNonNull(builder.taskStatus);
		this.taskStatusMessage = builder.taskStatusMessage;
		this.createdAt = requireNonNull(builder.createdAt);
		this.lastUpdatedAt = requireNonNull(builder.lastUpdatedAt);
		if (this.lastUpdatedAt.isBefore(this.createdAt))
			throw new IllegalArgumentException(
					"Task last-updated time must not precede its creation time.");
		this.timeToLive = requireWholeMilliseconds(builder.timeToLive,
				"Task time to live");
		this.pollInterval = requireWholeMilliseconds(builder.pollInterval,
				"Task poll interval");
		this.inputRequests = Collections.unmodifiableMap(
				new LinkedHashMap<>(builder.inputRequests));
		this.completedResult = builder.completedResult;
		this.failure = builder.failure;
		this.metadata = requireApplicationMetadata(builder.metadata);
		validateStatusPayload();
	}

	/** @return nonblank durable task identifier */
	@NonNull
	public String getTaskId() {
		return this.taskId;
	}

	/**
	 * Returns the non-wire origin persisted with this task.
	 *
	 * @return framework-derived task origin
	 */
	@NonNull
	public McpTaskOrigin getTaskOrigin() {
		return this.taskOrigin;
	}

	/** @return current task status */
	@NonNull
	public McpTaskStatus getTaskStatus() {
		return this.taskStatus;
	}

	/** @return client-visible status message, if supplied */
	@NonNull
	public Optional<@NonNull String> getTaskStatusMessage() {
		return Optional.ofNullable(this.taskStatusMessage);
	}

	/** @return task creation time */
	@NonNull
	public Instant getCreatedAt() {
		return this.createdAt;
	}

	/** @return most recent task state-update time */
	@NonNull
	public Instant getLastUpdatedAt() {
		return this.lastUpdatedAt;
	}

	/**
	 * Returns the duration after creation for which the task may be retained.
	 *
	 * @return positive whole-millisecond duration, or empty for unlimited
	 */
	@NonNull
	public Optional<@NonNull Duration> getTimeToLive() {
		return Optional.ofNullable(this.timeToLive);
	}

	/** @return positive whole-millisecond suggested polling interval, if any */
	@NonNull
	public Optional<@NonNull Duration> getPollInterval() {
		return Optional.ofNullable(this.pollInterval);
	}

	/**
	 * Returns outstanding task input requests in insertion order.
	 *
	 * @return immutable input-request map; nonempty only for
	 * {@link McpTaskStatus#INPUT_REQUIRED}
	 */
	@NonNull
	public Map<@NonNull String, @NonNull McpInputRequest> getInputRequests() {
		return this.inputRequests;
	}

	/**
	 * Returns the operation result of a completed task.
	 *
	 * @return complete result only for {@link McpTaskStatus#COMPLETED}
	 */
	@NonNull
	public Optional<@NonNull McpCompleteResult> getCompletedResult() {
		return Optional.ofNullable(this.completedResult);
	}

	/**
	 * Returns the JSON-RPC error that failed task execution.
	 *
	 * @return failure only for {@link McpTaskStatus#FAILED}
	 */
	@NonNull
	public Optional<@NonNull McpJsonRpcError> getFailure() {
		return Optional.ofNullable(this.failure);
	}

	/** @return immutable task-result protocol extension metadata */
	@NonNull
	public McpJsonObject getMetadata() {
		return this.metadata;
	}

	/** @return whether every persisted task property is structurally equal */
	@Override
	public boolean equals(@Nullable Object other) {
		if (this == other)
			return true;
		if (!(other instanceof McpTask task))
			return false;
		return this.taskId.equals(task.taskId)
				&& this.taskOrigin.equals(task.taskOrigin)
				&& this.taskStatus == task.taskStatus
				&& Objects.equals(this.taskStatusMessage,
					task.taskStatusMessage)
				&& this.createdAt.equals(task.createdAt)
				&& this.lastUpdatedAt.equals(task.lastUpdatedAt)
				&& Objects.equals(this.timeToLive, task.timeToLive)
				&& Objects.equals(this.pollInterval, task.pollInterval)
				&& this.inputRequests.equals(task.inputRequests)
				&& Objects.equals(this.completedResult, task.completedResult)
				&& Objects.equals(this.failure, task.failure)
				&& this.metadata.equals(task.metadata);
	}

	/** @return structural persisted-task hash code */
	@Override
	public int hashCode() {
		return Objects.hash(this.taskId, this.taskOrigin, this.taskStatus,
				this.taskStatusMessage, this.createdAt, this.lastUpdatedAt,
				this.timeToLive, this.pollInterval, this.inputRequests,
				this.completedResult, this.failure, this.metadata);
	}

	/** @return a diagnostic rendering that redacts task and application data */
	@Override
	@NonNull
	public String toString() {
		return "McpTask{taskId=<redacted>, taskStatus=" + this.taskStatus + "}";
	}

	@NonNull
	static String requireTaskId(@NonNull String taskId) {
		requireNonNull(taskId);
		if (taskId.isBlank())
			throw new IllegalArgumentException("MCP task IDs must not be blank.");
		if (taskId.indexOf('\r') >= 0 || taskId.indexOf('\n') >= 0)
			throw new IllegalArgumentException(
					"MCP task IDs must not contain carriage-return or newline characters.");
		return taskId;
	}

	@Nullable
	private static Duration requireWholeMilliseconds(
			@Nullable Duration duration, @NonNull String description) {
		if (duration == null)
			return null;
		requireNonNull(description);
		if (duration.isNegative() || duration.isZero())
			throw new IllegalArgumentException(description + " must be positive.");
		long milliseconds;
		try {
			milliseconds = duration.toMillis();
		} catch (ArithmeticException exception) {
			throw new IllegalArgumentException(
					description + " must fit in a signed 64-bit millisecond count.",
					exception);
		}
		if (!duration.equals(Duration.ofMillis(milliseconds)))
			throw new IllegalArgumentException(
					description + " must have whole-millisecond precision.");
		return duration;
	}

	private void validateStatusPayload() {
		boolean hasInputRequests = !this.inputRequests.isEmpty();
		boolean hasCompletedResult = this.completedResult != null;
		boolean hasFailure = this.failure != null;
		switch (this.taskStatus) {
			case INPUT_REQUIRED -> {
				if (!hasInputRequests || hasCompletedResult || hasFailure)
					throw invalidStatusPayload();
			}
			case COMPLETED -> {
				if (hasInputRequests || !hasCompletedResult || hasFailure)
					throw invalidStatusPayload();
			}
			case FAILED -> {
				if (hasInputRequests || hasCompletedResult || !hasFailure)
					throw invalidStatusPayload();
			}
			case WORKING, CANCELED -> {
				if (hasInputRequests || hasCompletedResult || hasFailure)
					throw invalidStatusPayload();
			}
		}
	}

	@NonNull
	private IllegalStateException invalidStatusPayload() {
		return new IllegalStateException(
				"Task payload does not match task status " + this.taskStatus + ".");
	}

	/**
	 * Mutable builder for an immutable task snapshot.
	 *
	 * <p>The builder is intended for use by a single thread. Its
	 * status-specific setters do not silently change the configured status;
	 * {@link #build()} rejects every mismatched payload combination.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static final class Builder {
		@NonNull
		private final String taskId;
		@NonNull
		private final McpTaskOrigin taskOrigin;
		@NonNull
		private final McpTaskStatus taskStatus;
		@NonNull
		private final Instant createdAt;
		@NonNull
		private final Instant lastUpdatedAt;
		@Nullable
		private String taskStatusMessage;
		@Nullable
		private Duration timeToLive;
		@Nullable
		private Duration pollInterval;
		@NonNull
		private final Map<@NonNull String, @NonNull McpInputRequest>
				inputRequests;
		@Nullable
		private McpCompleteResult completedResult;
		@Nullable
		private McpJsonRpcError failure;
		@NonNull
		private McpJsonObject metadata;

		private Builder(@NonNull String taskId,
				@NonNull McpTaskOrigin taskOrigin,
				@NonNull McpTaskStatus taskStatus,
				@NonNull Instant createdAt,
				@NonNull Instant lastUpdatedAt) {
			this.taskId = requireTaskId(taskId);
			this.taskOrigin = requireNonNull(taskOrigin);
			this.taskStatus = requireNonNull(taskStatus);
			this.createdAt = requireNonNull(createdAt);
			this.lastUpdatedAt = requireNonNull(lastUpdatedAt);
			if (lastUpdatedAt.isBefore(createdAt))
				throw new IllegalArgumentException(
						"Task last-updated time must not precede its creation time.");
			this.inputRequests = new LinkedHashMap<>();
			this.metadata = McpJsonObject.emptyInstance();
		}

		/**
		 * Sets or clears the client-visible status message.
		 *
		 * @param taskStatusMessage status message, or null to clear
		 * @return this builder
		 */
		@NonNull
		public Builder taskStatusMessage(
				@Nullable String taskStatusMessage) {
			this.taskStatusMessage = taskStatusMessage;
			return this;
		}

		/**
		 * Sets the task time to live or selects unlimited retention.
		 *
		 * @param timeToLive positive whole-millisecond duration, or null for
		 *                   the wire-level unlimited value
		 * @return this builder
		 * @throws IllegalArgumentException if the duration is not positive, has
		 * sub-millisecond precision, or does not fit in a signed 64-bit
		 * millisecond count
		 */
		@NonNull
		public Builder timeToLive(@Nullable Duration timeToLive) {
			this.timeToLive = requireWholeMilliseconds(timeToLive,
					"Task time to live");
			return this;
		}

		/**
		 * Sets or clears the suggested polling interval.
		 *
		 * @param pollInterval positive whole-millisecond duration, or null to
		 *                     omit the suggestion
		 * @return this builder
		 * @throws IllegalArgumentException if the duration is not positive, has
		 * sub-millisecond precision, or does not fit in a signed 64-bit
		 * millisecond count
		 */
		@NonNull
		public Builder pollInterval(@Nullable Duration pollInterval) {
			this.pollInterval = requireWholeMilliseconds(pollInterval,
					"Task poll interval");
			return this;
		}

		/**
		 * Appends one uniquely keyed outstanding input request.
		 *
		 * @param key task-lifetime-unique input-request key
		 * @param inputRequest outstanding input request
		 * @return this builder
		 * @throws NullPointerException if an argument is null
		 * @throws IllegalArgumentException if the key is already present
		 */
		@NonNull
		public Builder addInputRequest(@NonNull String key,
				@NonNull McpInputRequest inputRequest) {
			requireNonNull(key);
			requireNonNull(inputRequest);
			if (this.inputRequests.putIfAbsent(key, inputRequest) != null)
				throw new IllegalArgumentException(
						"Task input-request keys must be unique.");
			return this;
		}

		/**
		 * Appends outstanding input requests in iteration order.
		 *
		 * <p>The call fails without mutation if an entry is null or any key is
		 * already present in this builder.
		 *
		 * @param inputRequests input requests to append
		 * @return this builder
		 * @throws NullPointerException if the map, a key, or a value is null
		 * @throws IllegalArgumentException if a key is already present
		 */
		@NonNull
		public Builder addInputRequests(
				@NonNull Map<@NonNull String,
						? extends @NonNull McpInputRequest> inputRequests) {
			requireNonNull(inputRequests);
			Map<@NonNull String, @NonNull McpInputRequest> copied =
					new LinkedHashMap<>();
			for (Map.Entry<@NonNull String,
					? extends @NonNull McpInputRequest> entry
					: inputRequests.entrySet()) {
				String key = requireNonNull(entry.getKey());
				McpInputRequest inputRequest = requireNonNull(entry.getValue());
				if (this.inputRequests.containsKey(key)
						|| copied.putIfAbsent(key, inputRequest) != null)
					throw new IllegalArgumentException(
							"Task input-request keys must be unique.");
			}
			this.inputRequests.putAll(copied);
			return this;
		}

		/**
		 * Supplies the required completed operation result.
		 *
		 * @param completedResult completed operation result
		 * @return this builder
		 */
		@NonNull
		public Builder completedResult(
				@NonNull McpCompleteResult completedResult) {
			this.completedResult = requireNonNull(completedResult);
			return this;
		}

		/**
		 * Supplies the required task-execution JSON-RPC failure.
		 *
		 * @param failure client-visible JSON-RPC failure
		 * @return this builder
		 */
		@NonNull
		public Builder failure(@NonNull McpJsonRpcError failure) {
			this.failure = requireNonNull(failure);
			return this;
		}

		/**
		 * Sets task-result protocol extension metadata.
		 *
		 * @param metadata immutable metadata, or null to restore the empty default
		 * @return this builder
		 * @throws IllegalArgumentException when {@link #build()} is called if the
		 * metadata uses a reserved MCP key
		 */
		@NonNull
		public Builder metadata(@Nullable McpJsonObject metadata) {
			this.metadata = metadata == null
					? McpJsonObject.emptyInstance() : metadata;
			return this;
		}

		/**
		 * Builds and validates the immutable task snapshot.
		 *
		 * @return immutable task
		 * @throws IllegalStateException if the status-specific payload is absent
		 * or does not match the task status
		 */
		@NonNull
		public McpTask build() {
			return new McpTask(this);
		}
	}
}
