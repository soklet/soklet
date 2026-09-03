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

import com.soklet.McpJsonValue;
import com.soklet.McpRequestOutcome;
import com.soklet.McpSimulation;
import com.soklet.McpSimulationBodyType;
import com.soklet.McpSimulationCompletion;
import com.soklet.McpSimulationOptions;
import com.soklet.McpSimulationResponse;
import com.soklet.McpSimulationStreamItem;
import com.soklet.McpSimulationStreamItemType;
import com.soklet.McpStreamTerminationReason;
import com.soklet.StreamTerminationReason;
import com.soklet.internal.mcp.transport.McpOutboundChannel;
import com.soklet.internal.microhttp.Header;
import com.soklet.internal.microhttp.MicrohttpResponse;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.ThreadSafe;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static java.util.Objects.requireNonNull;

/**
 * One internal off-network request capture and its public handle.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class McpSimulationRuntime implements McpSimulation,
		McpRequestSseStream.Channel {
	interface Controller {
		boolean cancel(@NonNull StreamTerminationReason reason);
	}

	@NonNull
	private final Object lock;
	private final int streamItemQueueCapacity;
	private final int maximumCapturedSizeInBytes;
	@NonNull
	private final Queue<@NonNull CapturedItem> items;
	@NonNull
	private final Set<@NonNull Object> pendingCoalescingKeys;
	@NonNull
	private final Queue<@NonNull CapturedItem> preResponseItems;
	@NonNull
	private final Runnable completionCallback;
	private @Nullable Controller controller;
	private McpRequestSseStream.@Nullable Listener streamListener;
	private @Nullable McpSimulationResponse response;
	private @Nullable McpSimulationCompletion completion;
	private @Nullable McpStreamTerminationReason pendingReason;
	private @Nullable Termination pendingPreResponseTermination;
	private @Nullable McpJsonValue terminalMessage;
	private @NonNull List<@NonNull Throwable> terminalThrowables;
	private int capturedBytes;
	private boolean responsePublished;
	private boolean sseResponse;
	private boolean channelTerminal;
	private boolean requestFinished;
	private boolean completionCallbackDelivered;
	private boolean cancelInFlight;
	private boolean cancelWon;

	McpSimulationRuntime(@NonNull McpSimulationOptions options,
			@NonNull Runnable completionCallback) {
		McpSimulationOptions requiredOptions = requireNonNull(options);
		this.lock = new Object();
		this.streamItemQueueCapacity =
				requiredOptions.getStreamItemQueueCapacity();
		this.maximumCapturedSizeInBytes =
				requiredOptions.getMaximumCapturedSizeInBytes();
		this.items = new ArrayDeque<>();
		this.pendingCoalescingKeys = new LinkedHashSet<>();
		this.preResponseItems = new ArrayDeque<>();
		this.completionCallback = requireNonNull(completionCallback);
		this.terminalThrowables = List.of();
	}

	void bindController(@NonNull Controller controller) {
		synchronized (this.lock) {
			if (this.controller != null)
				throw new IllegalStateException(
						"The MCP simulation controller is already bound.");
			this.controller = requireNonNull(controller);
		}
	}

	McpRequestSseStream.@NonNull Channel openChannel(
			McpRequestSseStream.@NonNull Listener listener) {
		synchronized (this.lock) {
			if (this.streamListener != null)
				throw new IllegalStateException(
						"An MCP simulation cannot open two response channels.");
			this.streamListener = requireNonNull(listener);
			this.sseResponse = true;
			return this;
		}
	}

	void acceptResponse(@NonNull MicrohttpResponse response) {
		MicrohttpResponse requiredResponse = requireNonNull(response);
		McpRequestSseStream.Listener listener = null;
		Termination termination = null;
		synchronized (this.lock) {
			if (this.responsePublished || this.requestFinished || this.cancelWon)
				return;
			this.responsePublished = true;
			if (this.sseResponse) {
				this.response = new DefaultResponse(requiredResponse.status(),
						headers(requiredResponse.headers()),
						McpSimulationBodyType.SSE, null);
				while (!this.preResponseItems.isEmpty())
					this.items.add(this.preResponseItems.remove());
				listener = this.streamListener;
				termination = this.pendingPreResponseTermination;
				this.pendingPreResponseTermination = null;
			} else {
				byte[] body = requiredResponse.body();
				McpSimulationBodyType bodyType = body.length == 0
						? McpSimulationBodyType.EMPTY : McpSimulationBodyType.JSON;
				if (body.length > this.maximumCapturedSizeInBytes) {
					this.response = new DefaultResponse(requiredResponse.status(),
							headers(requiredResponse.headers()), bodyType, null);
					if (this.pendingReason == null)
						this.pendingReason = McpStreamTerminationReason
								.SIMULATOR_CAPTURE_BYTE_LIMIT_EXCEEDED;
				} else {
					this.capturedBytes = body.length;
					this.response = new DefaultResponse(requiredResponse.status(),
							headers(requiredResponse.headers()), bodyType, body);
					if (this.pendingReason == null)
						this.pendingReason = McpStreamTerminationReason.COMPLETED;
				}
			}
			this.lock.notifyAll();
		}
		if (termination != null && listener != null)
			listener.didTerminate(termination.cancellationReason(),
					termination.observationReason(), null);
	}

	@NonNull
	Optional<@NonNull McpStreamTerminationReason> nonStreamingReason() {
		synchronized (this.lock) {
			return this.sseResponse ? Optional.empty()
					: Optional.ofNullable(this.pendingReason);
		}
	}

	void didFinishRequest(@NonNull McpRequestOutcome outcome,
			@NonNull List<@NonNull Throwable> throwables) {
		requireNonNull(outcome);
		Runnable afterUnlock = null;
		synchronized (this.lock) {
			if (this.requestFinished)
				return;
			this.requestFinished = true;
			this.terminalThrowables = List.copyOf(requireNonNull(throwables));
			if (this.pendingReason == null)
				this.pendingReason = reasonForOutcome(outcome);
			this.completion = new DefaultCompletion(this.pendingReason,
					this.terminalMessage, this.terminalThrowables);
			this.lock.notifyAll();
			afterUnlock = reserveCompletionCallbackWhileLocked();
		}
		afterUnlock.run();
	}

	void reserveRuntimeReason(@NonNull McpStreamTerminationReason reason) {
		synchronized (this.lock) {
			if (this.completion != null)
				return;
			this.pendingReason = requireNonNull(reason);
			this.cancelWon = true;
		}
	}

	@Override
	@NonNull
	public Optional<@NonNull McpSimulationResponse> awaitResponse(
			@NonNull Duration timeout) throws InterruptedException {
		long timeoutNanos = timeoutNanos(requireNonNull(timeout));
		synchronized (this.lock) {
			awaitWhile(timeoutNanos,
					() -> this.response == null && this.completion == null);
			return Optional.ofNullable(this.response);
		}
	}

	@Override
	@NonNull
	public Optional<@NonNull McpSimulationStreamItem> awaitStreamItem(
			@NonNull Duration timeout) throws InterruptedException {
		long timeoutNanos = timeoutNanos(requireNonNull(timeout));
		synchronized (this.lock) {
			awaitWhile(timeoutNanos,
					() -> this.items.isEmpty() && this.completion == null);
			CapturedItem item = this.items.poll();
			if (item == null)
				return Optional.empty();
			if (item.coalescingKey() != null)
				this.pendingCoalescingKeys.remove(item.coalescingKey());
			return Optional.of(item.item());
		}
	}

	@Override
	@NonNull
	public Optional<@NonNull McpSimulationCompletion> awaitCompletion(
			@NonNull Duration timeout) throws InterruptedException {
		long timeoutNanos = timeoutNanos(requireNonNull(timeout));
		synchronized (this.lock) {
			awaitWhile(timeoutNanos, () -> this.completion == null);
			return Optional.ofNullable(this.completion);
		}
	}

	@Override
	@NonNull
	public Boolean isComplete() {
		synchronized (this.lock) {
			return this.completion != null;
		}
	}

	@Override
	public void close() {
		Controller activeController;
		synchronized (this.lock) {
			if (this.completion != null || this.pendingReason != null
					|| this.cancelInFlight)
				return;
			activeController = requireNonNull(this.controller,
					"The MCP simulation controller is not bound.");
			this.cancelInFlight = true;
		}
		// The request control owns the terminal first-winner transition. A winning
		// controller publishes the exact public reason only after reserving that
		// transition; publishing it before this call would let a late cancel
		// overwrite an already-won runtime terminal.
		boolean won = false;
		try {
			won = activeController.cancel(
					StreamTerminationReason.CLIENT_DISCONNECTED);
		} finally {
			synchronized (this.lock) {
				this.cancelInFlight = false;
				if (won && this.completion == null) {
					this.pendingReason =
							McpStreamTerminationReason.CLIENT_DISCONNECTED;
					this.cancelWon = true;
				}
				this.lock.notifyAll();
			}
		}
	}

	@Override
	@NonNull
	public MicrohttpResponse response(@NonNull List<@NonNull Header> headers) {
		return new MicrohttpResponse(200, "OK", List.copyOf(requireNonNull(headers)),
				new byte[0]);
	}

	@Override
	public void enqueue(McpRequestSseStream.@NonNull Frame frame)
			throws InterruptedException {
		offer(frame);
	}

	@Override
	public McpOutboundChannel.@NonNull OfferResult offer(
			McpRequestSseStream.@NonNull Frame frame) {
		return offer(frame, null);
	}

	@Override
	public McpOutboundChannel.@NonNull OfferResult offerCoalescing(
			McpRequestSseStream.@NonNull Frame frame,
			@NonNull Object coalescingKey) {
		return offer(frame, requireNonNull(coalescingKey));
	}

	private McpOutboundChannel.@NonNull OfferResult offer(
			McpRequestSseStream.@NonNull Frame frame,
			@Nullable Object coalescingKey) {
		McpRequestSseStream.Listener listener;
		Termination termination = null;
		synchronized (this.lock) {
			if (this.channelTerminal || this.cancelWon)
				return McpOutboundChannel.OfferResult.CLOSED;
			if (coalescingKey != null
					&& this.pendingCoalescingKeys.contains(coalescingKey))
				return McpOutboundChannel.OfferResult.ACCEPTED;
			if (!this.responsePublished) {
				termination = captureFrameWhileLocked(frame, coalescingKey,
						this.preResponseItems);
				if (termination != null) {
					this.pendingPreResponseTermination = termination;
					// The response head is authoritative and must be published before
					// the exact capture-limit terminal. The first offending frame is
					// omitted, but its producer observes acceptance long enough to
					// commit the SSE response.
					return McpOutboundChannel.OfferResult.ACCEPTED;
				}
				listener = null;
			} else {
				termination = captureFrameWhileLocked(frame, coalescingKey,
						this.items);
				listener = requireNonNull(this.streamListener);
			}
		}
		if (termination != null && listener != null)
			listener.didTerminate(termination.cancellationReason(),
					termination.observationReason(), null);
		return termination == null ? McpOutboundChannel.OfferResult.ACCEPTED
				: McpOutboundChannel.OfferResult.CLOSED;
	}

	@Override
	public boolean complete(McpRequestSseStream.@NonNull Frame terminalFrame) {
		McpRequestSseStream.Listener listener;
		Termination termination;
		synchronized (this.lock) {
			if (this.channelTerminal || this.cancelWon)
				return false;
			if (!this.responsePublished)
				throw new IllegalStateException(
						"An MCP simulation cannot complete before its SSE response head.");
			termination = captureFrameWhileLocked(requireNonNull(terminalFrame),
					null, this.items);
			if (termination == null) {
				this.channelTerminal = true;
				if (this.pendingReason == null)
					this.pendingReason = McpStreamTerminationReason.COMPLETED;
				this.terminalMessage = McpServerRuntimeBridge.toPublic(
						requireNonNull(terminalFrame.message()).toJsonObject());
				termination = new Termination(StreamTerminationReason.COMPLETED,
						McpStreamTerminationReason.COMPLETED);
			}
			listener = requireNonNull(this.streamListener);
		}
		listener.didTerminate(termination.cancellationReason(),
				termination.observationReason(), null);
		return termination.observationReason()
				== McpStreamTerminationReason.COMPLETED;
	}

	@Override
	public boolean fail(@NonNull StreamTerminationReason reason,
			@Nullable Throwable cause) {
		McpRequestSseStream.Listener listener;
		McpStreamTerminationReason publicReason;
		synchronized (this.lock) {
			if (this.channelTerminal)
				return false;
			this.channelTerminal = true;
			publicReason = McpServerRuntimeBridge.toPublicTerminationReason(
					requireNonNull(reason));
			if (this.pendingReason == null)
				this.pendingReason = publicReason;
			listener = requireNonNull(this.streamListener);
		}
		listener.didTerminate(reason, publicReason, cause);
		return true;
	}

	@Override
	public boolean failIfDeadlineExpired(long nowNanos, long deadlineNanos,
			@NonNull StreamTerminationReason reason, @Nullable Throwable cause) {
		return nowNanos - deadlineNanos >= 0L && fail(reason, cause);
	}

	@Override
	public boolean failIfWriteIdleExpired(long nowNanos, long timeoutNanos,
			@NonNull StreamTerminationReason reason, @Nullable Throwable cause) {
		return false;
	}

	@Override
	public long responseWriteIdleDeadlineNanos(long timeoutNanos) {
		return Long.MAX_VALUE;
	}

	@Override
	public void close(@NonNull StreamTerminationReason reason,
			@Nullable Throwable cause) {
		fail(reason, cause);
	}

	@Override
	@NonNull
	public Optional<McpOutboundChannel.@NonNull Snapshot> snapshot() {
		return Optional.empty();
	}

	@Override
	public boolean isTerminalWritten() {
		synchronized (this.lock) {
			return this.channelTerminal
					&& this.pendingReason == McpStreamTerminationReason.COMPLETED;
		}
	}

	private @Nullable Termination captureFrameWhileLocked(
			McpRequestSseStream.@NonNull Frame frame,
			@Nullable Object coalescingKey,
			@NonNull Queue<@NonNull CapturedItem> destination) {
		if (!Thread.holdsLock(this.lock))
			throw new IllegalStateException("The capture lock is required.");
		byte[] encodedBytes = requireNonNull(frame).encodedBytes();
		McpStreamTerminationReason limitReason = null;
		int retainedItemCount = this.items.size() + this.preResponseItems.size();
		if (retainedItemCount >= this.streamItemQueueCapacity)
			limitReason = McpStreamTerminationReason
					.SIMULATOR_CAPTURE_ITEM_LIMIT_EXCEEDED;
		else if ((long) this.capturedBytes + encodedBytes.length
				> this.maximumCapturedSizeInBytes)
			limitReason = McpStreamTerminationReason
					.SIMULATOR_CAPTURE_BYTE_LIMIT_EXCEEDED;
		if (limitReason != null) {
			this.channelTerminal = true;
			this.pendingReason = limitReason;
			this.lock.notifyAll();
			return new Termination(StreamTerminationReason.SIMULATOR_LIMIT_EXCEEDED,
					limitReason);
		}

		McpSimulationStreamItem item;
		if (frame.type() == McpRequestSseStream.FrameType.JSON_MESSAGE) {
			McpJsonValue message = McpServerRuntimeBridge.toPublic(
					requireNonNull(frame.message()).toJsonObject());
			item = new DefaultStreamItem(McpSimulationStreamItemType.JSON_MESSAGE,
					message, null, encodedBytes);
		} else {
			item = new DefaultStreamItem(
					McpSimulationStreamItemType.KEEP_ALIVE_COMMENT,
					null, "keepalive", encodedBytes);
		}
		requireNonNull(destination).add(new CapturedItem(item, coalescingKey));
		if (coalescingKey != null)
			this.pendingCoalescingKeys.add(coalescingKey);
		this.capturedBytes += encodedBytes.length;
		this.lock.notifyAll();
		return null;
	}

	private void awaitWhile(long timeoutNanos, @NonNull Condition condition)
			throws InterruptedException {
		if (!Thread.holdsLock(this.lock))
			throw new IllegalStateException("The capture lock is required.");
		long startedAt = System.nanoTime();
		long remaining = timeoutNanos;
		while (requireNonNull(condition).evaluate() && remaining > 0L) {
			TimeUnit.NANOSECONDS.timedWait(this.lock, remaining);
			long elapsed = System.nanoTime() - startedAt;
			remaining = elapsed >= timeoutNanos ? 0L : timeoutNanos - elapsed;
		}
	}

	private static long timeoutNanos(@NonNull Duration timeout) {
		if (timeout.isNegative())
			throw new IllegalArgumentException("Timeout must not be negative.");
		try {
			return timeout.toNanos();
		} catch (ArithmeticException ignored) {
			return Long.MAX_VALUE;
		}
	}

	@NonNull
	private static McpStreamTerminationReason reasonForOutcome(
			@NonNull McpRequestOutcome outcome) {
		return switch (requireNonNull(outcome)) {
			case COMPLETE, INPUT_REQUIRED -> McpStreamTerminationReason.COMPLETED;
			case CANCELED -> McpStreamTerminationReason.REQUEST_CANCELED;
			case DEADLINE_EXCEEDED -> McpStreamTerminationReason.DEADLINE_EXCEEDED;
			case CLIENT_DISCONNECTED ->
					McpStreamTerminationReason.CLIENT_DISCONNECTED;
			case WRITE_FAILED -> McpStreamTerminationReason.WRITE_FAILED;
			case REJECTED, APPLICATION_ERROR, PROTOCOL_ERROR, INTERNAL_ERROR ->
					McpStreamTerminationReason.INTERNAL_ERROR;
		};
	}

	@NonNull
	private Runnable reserveCompletionCallbackWhileLocked() {
		if (!Thread.holdsLock(this.lock))
			throw new IllegalStateException("The capture lock is required.");
		if (this.completionCallbackDelivered)
			return () -> {};
		this.completionCallbackDelivered = true;
		return this.completionCallback;
	}

	@NonNull
	private static Map<@NonNull String, @NonNull Set<@NonNull String>> headers(
			@NonNull List<@NonNull Header> headers) {
		Map<String, LinkedHashSet<String>> mutable = new LinkedHashMap<>();
		for (Header header : List.copyOf(requireNonNull(headers))) {
			String matchingName = mutable.keySet().stream()
					.filter(name -> name.equalsIgnoreCase(header.name()))
					.findFirst().orElse(header.name());
			mutable.computeIfAbsent(matchingName, ignored -> new LinkedHashSet<>())
					.add(header.value());
		}
		Map<String, Set<String>> immutable = new LinkedHashMap<>();
		mutable.forEach((name, values) -> immutable.put(name,
				Collections.unmodifiableSet(new LinkedHashSet<>(values))));
		return Collections.unmodifiableMap(immutable);
	}

	@FunctionalInterface
	private interface Condition {
		boolean evaluate();
	}

	private record CapturedItem(@NonNull McpSimulationStreamItem item,
			@Nullable Object coalescingKey) {
		private CapturedItem {
			requireNonNull(item);
		}
	}

	private record Termination(
			@NonNull StreamTerminationReason cancellationReason,
			@NonNull McpStreamTerminationReason observationReason) {
		private Termination {
			requireNonNull(cancellationReason);
			requireNonNull(observationReason);
		}
	}

	@ThreadSafe
	private static final class DefaultResponse implements McpSimulationResponse {
		private final int statusCode;
		@NonNull
		private final Map<@NonNull String, @NonNull Set<@NonNull String>> headers;
		@NonNull
		private final McpSimulationBodyType bodyType;
		private final byte @Nullable [] body;

		private DefaultResponse(int statusCode,
				@NonNull Map<@NonNull String, @NonNull Set<@NonNull String>> headers,
				@NonNull McpSimulationBodyType bodyType,
				byte @Nullable [] body) {
			if (statusCode < 100 || statusCode > 599)
				throw new IllegalArgumentException(
						"HTTP status must be between 100 and 599.");
			this.statusCode = statusCode;
			this.headers = requireNonNull(headers);
			this.bodyType = requireNonNull(bodyType);
			this.body = body == null ? null : Arrays.copyOf(body, body.length);
		}

		@Override
		@NonNull
		public Integer getStatusCode() {
			return this.statusCode;
		}

		@Override
		@NonNull
		public Map<@NonNull String, @NonNull Set<@NonNull String>> getHeaders() {
			return this.headers;
		}

		@Override
		@NonNull
		public McpSimulationBodyType getBodyType() {
			return this.bodyType;
		}

		@Override
		@NonNull
		public Optional<byte @NonNull []> getBody() {
			return this.body == null ? Optional.empty()
					: Optional.of(Arrays.copyOf(this.body, this.body.length));
		}
	}

	@ThreadSafe
	private static final class DefaultStreamItem
			implements McpSimulationStreamItem {
		@NonNull
		private final McpSimulationStreamItemType type;
		private final @Nullable McpJsonValue message;
		private final @Nullable String comment;
		private final byte @NonNull [] encodedBytes;

		private DefaultStreamItem(@NonNull McpSimulationStreamItemType type,
				@Nullable McpJsonValue message, @Nullable String comment,
				byte @NonNull [] encodedBytes) {
			this.type = requireNonNull(type);
			this.message = message;
			this.comment = comment;
			this.encodedBytes = Arrays.copyOf(requireNonNull(encodedBytes),
					encodedBytes.length);
			if (this.encodedBytes.length == 0)
				throw new IllegalArgumentException(
						"Captured SSE bytes must not be empty.");
			if ((type == McpSimulationStreamItemType.JSON_MESSAGE)
					!= (message != null && comment == null))
				throw new IllegalArgumentException(
						"JSON items require only a message.");
			if ((type == McpSimulationStreamItemType.KEEP_ALIVE_COMMENT)
					!= (message == null && "keepalive".equals(comment)))
				throw new IllegalArgumentException(
						"Keep-alive items require the keepalive comment.");
		}

		@Override
		@NonNull
		public McpSimulationStreamItemType getType() {
			return this.type;
		}

		@Override
		@NonNull
		public Optional<@NonNull McpJsonValue> getMessage() {
			return Optional.ofNullable(this.message);
		}

		@Override
		@NonNull
		public Optional<@NonNull String> getComment() {
			return Optional.ofNullable(this.comment);
		}

		@Override
		public byte @NonNull [] getEncodedBytes() {
			return Arrays.copyOf(this.encodedBytes, this.encodedBytes.length);
		}
	}

	@ThreadSafe
	private static final class DefaultCompletion
			implements McpSimulationCompletion {
		@NonNull
		private final McpStreamTerminationReason reason;
		private final @Nullable McpJsonValue terminalMessage;
		@NonNull
		private final List<@NonNull Throwable> throwables;

		private DefaultCompletion(@NonNull McpStreamTerminationReason reason,
				@Nullable McpJsonValue terminalMessage,
				@NonNull List<@NonNull Throwable> throwables) {
			this.reason = requireNonNull(reason);
			this.terminalMessage = terminalMessage;
			this.throwables = List.copyOf(requireNonNull(throwables));
		}

		@Override
		@NonNull
		public McpStreamTerminationReason getReason() {
			return this.reason;
		}

		@Override
		@NonNull
		public Optional<@NonNull McpJsonValue> getTerminalMessage() {
			return Optional.ofNullable(this.terminalMessage);
		}

		@Override
		@NonNull
		public List<@NonNull Throwable> getThrowables() {
			return this.throwables;
		}
	}
}
