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

package com.soklet.internal.mcp.transport;

import com.soklet.StreamTerminationReason;
import com.soklet.internal.microhttp.EventLoop;
import com.soklet.internal.microhttp.Handler;
import com.soklet.internal.microhttp.Header;
import com.soklet.internal.microhttp.MicrohttpRequest;
import com.soklet.internal.microhttp.MicrohttpResponse;
import com.soklet.internal.microhttp.Options;
import com.soklet.internal.microhttp.OptionsBuilder;
import com.soklet.internal.microhttp.StreamingMicrohttpResponses;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

import static java.util.Objects.requireNonNull;

final class McpTransportRuntime implements AutoCloseable {
	private static final Duration EVENT_LOOP_RESOLUTION = Duration.ofMillis(10);
	private static final Duration REQUEST_READ_TIMEOUT = Duration.ofSeconds(5);
	private static final int MAXIMUM_REQUEST_SIZE = 64 * 1_024;
	private static final int MAXIMUM_HEADER_COUNT = 64;
	private static final int MAXIMUM_HEADERS_SIZE = 16 * 1_024;
	private static final int MAXIMUM_REQUEST_TARGET_LENGTH = 2_048;
	private static final byte[] KEEP_ALIVE_EVENT = ": keepalive\n\n".getBytes(StandardCharsets.UTF_8);

	@FunctionalInterface
	interface InvocationHandler {
		void handle(Invocation invocation) throws Exception;
	}

	@FunctionalInterface
	interface TimerCycleProbe {
		void beforeExchange(String requestId);
	}

	static final class Invocation {
		private final Exchange exchange;

		private Invocation(Exchange exchange) {
			this.exchange = exchange;
		}

		String requestId() {
			return exchange.requestId;
		}

		void progress(String value) throws InterruptedException {
			exchange.progress(value);
		}

		boolean complete(String value) {
			return exchange.complete(value);
		}

		void becomeSubscription() {
			exchange.becomeSubscription();
		}

		boolean isCanceled() {
			return exchange.cancellation.get() != null;
		}

		Optional<StreamTerminationReason> cancellationReason() {
			Cancellation cancellation = exchange.cancellation.get();
			return cancellation == null ? Optional.empty() : Optional.of(cancellation.reason());
		}
	}

	record Snapshot(int configuredConnectionWriterConcurrency, int configuredMaximumConnections,
			McpThreadStrategy threadStrategy, McpHandlerDispatcher.Snapshot dispatcher,
			int liveExchanges, int activeStreams, int subscriptions,
			int bufferedFrames, int bufferedBytes, int terminalBytes,
			int maximumObservedBufferedFramesPerStream, int maximumObservedBufferedBytesPerStream,
			long admittedRequests, long rejectedRequests, long cleanupCount,
			long terminalReservations, long appliedBackpressureCount,
			int residualHandlerSlots, boolean running) {
	}

	private record Cancellation(StreamTerminationReason reason, @Nullable Throwable cause) {
		private Cancellation {
			requireNonNull(reason);
		}
	}

	private final McpTransportConfiguration configuration;
	private final InvocationHandler invocationHandler;
	private final McpMonotonicClock clock;
	private final TimerCycleProbe timerCycleProbe;
	private final ExecutorService handlerExecutor;
	private final McpHandlerDispatcher dispatcher;
	private final EventLoop eventLoop;
	private final Map<MicrohttpRequest, Exchange> requestsByIdentity;
	private final ConcurrentHashMap<Long, Exchange> exchanges;
	private final ConcurrentHashMap<Long, Exchange> subscriptions;
	private final AtomicLong exchangeSequence;
	private final AtomicLong admittedRequests;
	private final AtomicLong rejectedRequests;
	private final AtomicLong cleanupCount;
	private final AtomicLong terminalReservations;
	private final AtomicLong appliedBackpressureCount;
	private final AtomicBoolean running;
	private final AtomicBoolean stopped;
	private final Thread timerThread;

	McpTransportRuntime(McpTransportConfiguration configuration, InvocationHandler invocationHandler)
			throws IOException {
		this(configuration, invocationHandler, McpMonotonicClock.SYSTEM);
	}

	McpTransportRuntime(McpTransportConfiguration configuration, InvocationHandler invocationHandler,
			McpMonotonicClock clock) throws IOException {
		this(configuration, invocationHandler, clock, requestId -> {
			// No-op outside deterministic timer-failure tests.
		});
	}

	McpTransportRuntime(McpTransportConfiguration configuration, InvocationHandler invocationHandler,
			McpMonotonicClock clock, TimerCycleProbe timerCycleProbe) throws IOException {
		this.configuration = requireNonNull(configuration);
		this.invocationHandler = requireNonNull(invocationHandler);
		this.clock = requireNonNull(clock);
		this.timerCycleProbe = requireNonNull(timerCycleProbe);

		if (!configuration.threadStrategy().supported())
			throw new IllegalStateException("Requested MCP handler thread strategy is not available.");

		this.handlerExecutor = configuration.threadStrategy().createExecutor(
				configuration.handlerConcurrency(),
				"soklet-mcp-handler-",
				(thread, throwable) -> {
					// The dispatcher reports handler failures through the owning exchange.
				});
		this.dispatcher = new McpHandlerDispatcher(
				configuration.handlerConcurrency(),
				configuration.handlerQueueCapacity(),
				handlerExecutor);
		this.requestsByIdentity = Collections.synchronizedMap(new IdentityHashMap<>());
		this.exchanges = new ConcurrentHashMap<>();
		this.subscriptions = new ConcurrentHashMap<>();
		this.exchangeSequence = new AtomicLong();
		this.admittedRequests = new AtomicLong();
		this.rejectedRequests = new AtomicLong();
		this.cleanupCount = new AtomicLong();
		this.terminalReservations = new AtomicLong();
		this.appliedBackpressureCount = new AtomicLong();
		this.running = new AtomicBoolean();
		this.stopped = new AtomicBoolean();
		this.timerThread = new Thread(this::runTimerLoop, "soklet-mcp-timer");

		Options options = OptionsBuilder.newBuilder()
				.withHost(configuration.host())
				.withPort(configuration.port())
				.withReuseAddr(true)
				.withResolution(EVENT_LOOP_RESOLUTION)
				.withRequestHeaderTimeout(REQUEST_READ_TIMEOUT)
				.withRequestBodyTimeout(REQUEST_READ_TIMEOUT)
				.withResponseWriteIdleTimeout(configuration.responseWriteIdleTimeout())
				.withAcceptLength(configuration.maximumConnections())
				.withMaxRequestSize(MAXIMUM_REQUEST_SIZE)
				.withMaxHeaderCount(MAXIMUM_HEADER_COUNT)
				.withMaxHeadersSize(MAXIMUM_HEADERS_SIZE)
				.withMaxRequestTargetLength(MAXIMUM_REQUEST_TARGET_LENGTH)
				.withMaxConnections(configuration.maximumConnections())
				.withConcurrency(configuration.connectionWriterConcurrency())
				.build();

		this.eventLoop = new EventLoop(options, new Handler() {
			@Override
			public void handle(MicrohttpRequest request, Consumer<MicrohttpResponse> callback) {
				handleHttpRequest(request, callback);
			}

			@Override
			public boolean monitorClientDisconnectsBeforeResponse(MicrohttpRequest request) {
				return true;
			}

			@Override
			public boolean monitorClientDisconnectsDuringStreamingResponse(MicrohttpRequest request) {
				return true;
			}

			@Override
			public void cancel(MicrohttpRequest request, StreamTerminationReason reason,
					@Nullable Throwable cause) {
				cancelHttpRequest(request, reason, cause);
			}
		});
	}

	void start() {
		if (stopped.get() || !running.compareAndSet(false, true))
			throw new IllegalStateException("MCP transport runtime has already been started.");

		timerThread.start();
		eventLoop.start();
	}

	int port() throws IOException {
		return eventLoop.getPort();
	}

	int publishSubscriptionEvent(String value) {
		requireNonNull(value);
		byte[] frame = sseEvent("resources-updated", value);
		int accepted = 0;

		for (Exchange exchange : subscriptions.values()) {
			McpOutboundChannel.OfferResult result = exchange.channel.offer(frame);

			if (result == McpOutboundChannel.OfferResult.ACCEPTED) {
				accepted++;
			} else if (result == McpOutboundChannel.OfferResult.FULL
					|| result == McpOutboundChannel.OfferResult.TOO_LARGE) {
				exchange.terminateForBackpressure();
			}
		}

		return accepted;
	}

	void runTimerCycle() {
		long now = clock.nanoTime();

		for (Exchange exchange : exchanges.values()) {
			try {
				timerCycleProbe.beforeExchange(exchange.requestId);
				exchange.onTimer(now);
			} catch (Throwable throwable) {
				try {
					exchange.onTimerFailure(throwable);
				} catch (Throwable ignored) {
					// One broken exchange must never terminate the sole timer loop or skip its peers.
				}
			}
		}
	}

	boolean timerThreadAlive() {
		return timerThread.isAlive();
	}

	Snapshot snapshot() {
		McpHandlerDispatcher.Snapshot dispatcherSnapshot = dispatcher.snapshot();
		int liveExchanges = 0;
		int activeStreams = 0;
		int residualHandlerSlots = 0;
		int bufferedFrames = 0;
		int bufferedBytes = 0;
		int terminalBytes = 0;
		int maximumObservedFrames = 0;
		int maximumObservedBytes = 0;

		for (Exchange exchange : exchanges.values()) {
			if (!exchange.responseTerminated.get())
				liveExchanges++;

			if (exchange.responseClaimed.get() && !exchange.responseTerminated.get())
				activeStreams++;

			if (exchange.responseTerminated.get() && exchange.handlerRunning.get())
				residualHandlerSlots++;

			McpOutboundChannel.Snapshot channelSnapshot = exchange.channel.snapshot();
			bufferedFrames += channelSnapshot.bufferedFrames();
			bufferedBytes += channelSnapshot.bufferedBytes();
			terminalBytes += channelSnapshot.terminalBytes();
			maximumObservedFrames = Math.max(
					maximumObservedFrames,
					channelSnapshot.maximumObservedBufferedFrames());
			maximumObservedBytes = Math.max(
					maximumObservedBytes,
					channelSnapshot.maximumObservedBufferedBytes());
		}

		return new Snapshot(
				configuration.connectionWriterConcurrency(),
				configuration.maximumConnections(),
				configuration.threadStrategy(),
				dispatcherSnapshot,
				liveExchanges,
				activeStreams,
				subscriptions.size(),
				bufferedFrames,
				bufferedBytes,
				terminalBytes,
				maximumObservedFrames,
				maximumObservedBytes,
				admittedRequests.get(),
				rejectedRequests.get(),
				cleanupCount.get(),
				terminalReservations.get(),
				appliedBackpressureCount.get(),
				residualHandlerSlots,
				running.get() && !stopped.get());
	}

	void stop() {
		if (!stopped.compareAndSet(false, true))
			return;

		eventLoop.stopAccepting();
		dispatcher.stopAccepting();

		for (Exchange exchange : List.copyOf(exchanges.values()))
			exchange.cancel(StreamTerminationReason.SERVER_STOPPING, null, true);

		eventLoop.stopConnections();
		handlerExecutor.shutdown();
		running.set(false);

		LockSupport.unpark(timerThread);
	}

	void join() throws InterruptedException {
		eventLoop.join();

		if (timerThread.isAlive())
			timerThread.join();
	}

	boolean awaitHandlerTermination(Duration timeout) throws InterruptedException {
		requireNonNull(timeout);
		return handlerExecutor.awaitTermination(timeout.toNanos(), TimeUnit.NANOSECONDS);
	}

	@Override
	public void close() {
		stop();

		try {
			join();
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
		}
	}

	private void handleHttpRequest(MicrohttpRequest request, Consumer<MicrohttpResponse> callback) {
		requireNonNull(request);
		requireNonNull(callback);

		if (!"POST".equals(request.method())) {
			callback.accept(staticResponse(405, "Method Not Allowed", "text/plain", "Method Not Allowed"));
			return;
		}

		boolean subscriptionRequest;

		if ("/request".equals(request.uri())) {
			subscriptionRequest = false;
		} else if ("/subscription".equals(request.uri())) {
			subscriptionRequest = true;
		} else {
			callback.accept(staticResponse(404, "Not Found", "text/plain", "Not Found"));
			return;
		}

		long exchangeId = exchangeSequence.incrementAndGet();
		String requestId = new String(request.body(), StandardCharsets.UTF_8);
		long deadlineNanos = saturatingAdd(clock.nanoTime(), configuration.requestDeadline().toNanos());
		Exchange exchange = new Exchange(
				exchangeId,
				request,
				requestId,
				subscriptionRequest,
				deadlineNanos,
				callback);
		McpHandlerDispatcher.Ticket ticket = dispatcher.newTicket(
				exchange::runHandler,
				exchange::handlerFailed);
		exchange.ticket = ticket;
		exchanges.put(exchangeId, exchange);
		requestsByIdentity.put(request, exchange);

		McpHandlerDispatcher.Admission admission = dispatcher.admit(ticket);

		if (admission == McpHandlerDispatcher.Admission.REJECTED) {
			rejectedRequests.incrementAndGet();
			exchange.respondUnavailable();
			return;
		}

		admittedRequests.incrementAndGet();
		signalTimer();
	}

	private void cancelHttpRequest(MicrohttpRequest request, StreamTerminationReason reason,
			@Nullable Throwable cause) {
		Exchange exchange = requestsByIdentity.get(request);

		if (exchange != null)
			exchange.cancel(reason, cause, true);
	}

	private void cleanupResponse(Exchange exchange) {
		if (!exchange.responseCleaned.compareAndSet(false, true))
			return;

		subscriptions.remove(exchange.exchangeId, exchange);
		requestsByIdentity.remove(exchange.request, exchange);
		cleanupCount.incrementAndGet();
	}

	private void runTimerLoop() {
		while (!stopped.get()) {
			try {
				runTimerCycle();
			} catch (Throwable ignored) {
				// A clock or other cycle-wide failure must not permanently kill the timer thread.
			}

			if (!stopped.get())
				LockSupport.parkNanos(EVENT_LOOP_RESOLUTION.toNanos());
		}
	}

	private void signalTimer() {
		LockSupport.unpark(timerThread);
	}

	private static MicrohttpResponse staticResponse(int status, String reason, String contentType,
			String body) {
		return new MicrohttpResponse(
				status,
				reason,
				List.of(new Header("Content-Type", contentType)),
				body.getBytes(StandardCharsets.UTF_8));
	}

	private static MicrohttpResponse unavailableResponse(String requestId) {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + escapeJson(requestId)
				+ "\",\"error\":{\"code\":-32603,\"message\":\"Internal error\"}}";
		return staticResponse(503, "Service Unavailable", "application/json", body);
	}

	private static String escapeJson(String value) {
		StringBuilder escaped = new StringBuilder(value.length() + 8);

		for (int index = 0; index < value.length(); index++) {
			char character = value.charAt(index);

			switch (character) {
				case '"' -> escaped.append("\\\"");
				case '\\' -> escaped.append("\\\\");
				case '\b' -> escaped.append("\\b");
				case '\f' -> escaped.append("\\f");
				case '\n' -> escaped.append("\\n");
				case '\r' -> escaped.append("\\r");
				case '\t' -> escaped.append("\\t");
				default -> {
					if (character < 0x20)
						escaped.append(String.format("\\u%04x", (int) character));
					else
						escaped.append(character);
				}
			}
		}

		return escaped.toString();
	}

	private static byte[] sseEvent(String event, String data) {
		StringBuilder frame = new StringBuilder(event.length() + data.length() + 24);
		frame.append("event: ").append(event).append('\n');
		String normalized = data.replace("\r\n", "\n").replace('\r', '\n');
		String[] lines = normalized.split("\n", -1);

		for (String line : lines)
			frame.append("data: ").append(line).append('\n');

		frame.append('\n');
		return frame.toString().getBytes(StandardCharsets.UTF_8);
	}

	private static long saturatingAdd(long left, long right) {
		long result = left + right;

		if (((left ^ result) & (right ^ result)) < 0)
			return Long.MAX_VALUE;

		return result;
	}

	private final class Exchange {
		private final long exchangeId;
		private final MicrohttpRequest request;
		private final String requestId;
		private final boolean subscriptionRequest;
		private final long deadlineNanos;
		private final Consumer<MicrohttpResponse> callback;
		private final AtomicReference<Cancellation> cancellation;
		private final AtomicBoolean responseClaimed;
		private final AtomicBoolean responseTerminated;
		private final AtomicBoolean handlerRunning;
		private final AtomicBoolean subscription;
		private final AtomicBoolean responseCleaned;
		private final AtomicLong nextKeepAliveNanos;
		private final McpOutboundChannel channel;
		private volatile McpHandlerDispatcher.@Nullable Ticket ticket;

		private Exchange(long exchangeId, MicrohttpRequest request, String requestId,
				boolean subscriptionRequest, long deadlineNanos, Consumer<MicrohttpResponse> callback) {
			this.exchangeId = exchangeId;
			this.request = requireNonNull(request);
			this.requestId = requireNonNull(requestId);
			this.subscriptionRequest = subscriptionRequest;
			this.deadlineNanos = deadlineNanos;
			this.callback = requireNonNull(callback);
			this.cancellation = new AtomicReference<>();
			this.responseClaimed = new AtomicBoolean();
			this.responseTerminated = new AtomicBoolean();
			this.handlerRunning = new AtomicBoolean();
			this.subscription = new AtomicBoolean();
			this.responseCleaned = new AtomicBoolean();
			this.nextKeepAliveNanos = new AtomicLong(Long.MAX_VALUE);
			this.channel = new McpOutboundChannel(
					configuration.outboundFrameCapacity(),
					configuration.outboundByteCapacity(),
					configuration.terminalByteCapacity(),
					clock,
					new McpOutboundChannel.Listener() {
						@Override
						public void didWrite(long byteCount, long timestampNanos) {
							signalTimer();
						}

						@Override
						public void didApplyBackpressure() {
							appliedBackpressureCount.incrementAndGet();
						}

						@Override
						public void didTerminate(StreamTerminationReason reason, @Nullable Throwable cause) {
							onChannelTerminated(reason, cause);
						}
					});
		}

		private void runHandler() throws Exception {
			if (!handlerRunning.compareAndSet(false, true))
				throw new IllegalStateException("An MCP exchange cannot run more than one handler.");

			try {
				runHandlerWhileTracked();
			} finally {
				handlerRunning.set(false);
				cleanupAfterResponseAndHandler();
			}
		}

		private void runHandlerWhileTracked() throws Exception {
			if (cancellation.get() != null)
				return;

			// A slot can become available while the timer thread is still walking other expired
			// exchanges. Revalidate at the queued-to-running boundary so an already-expired ticket
			// can never commit a stream or reach application code merely because it won that race.
			if (clock.nanoTime() - deadlineNanos >= 0L) {
				onRequestDeadline();
				return;
			}

			if (!responseClaimed.compareAndSet(false, true))
				return;

			callback.accept(StreamingMicrohttpResponses.withWritableSourceBody(
					200,
					"OK",
					List.of(
							new Header("Content-Type", "text/event-stream"),
							new Header("Cache-Control", "no-cache"),
							new Header("Connection", "close")),
					() -> channel));

			if (cancellation.get() != null)
				return;

			invocationHandler.handle(new Invocation(this));

			if (cancellation.get() != null)
				return;

			if (subscription.get()) {
				subscriptions.put(exchangeId, this);

				if (responseCleaned.get() || cancellation.get() != null) {
					subscriptions.remove(exchangeId, this);
				} else {
					nextKeepAliveNanos.set(saturatingAdd(
						clock.nanoTime(),
						configuration.keepAliveInterval().toNanos()));
					signalTimer();
				}
			} else {
				complete("complete");
			}
		}

		private void progress(String value) throws InterruptedException {
			requireNonNull(value);

			if (cancellation.get() != null)
				throw new InterruptedException("MCP invocation was canceled.");

			channel.enqueue(sseEvent("progress", value));
		}

		private boolean complete(String value) {
			requireNonNull(value);
			boolean won = channel.complete(sseEvent("result", value));

			if (won)
				terminalReservations.incrementAndGet();

			return won;
		}

		private void becomeSubscription() {
			if (!subscriptionRequest)
				throw new IllegalStateException("Only the subscription endpoint can become a subscription.");

			subscription.set(true);
		}

		private void respondUnavailable() {
			try {
				if (responseClaimed.compareAndSet(false, true))
					callback.accept(unavailableResponse(requestId));
			} finally {
				markResponseTerminated();
			}
		}

		private void handlerFailed(Throwable throwable) {
			if (cancellation.get() != null)
				return;

			if (responseClaimed.get()) {
				if (channel.complete(sseEvent("error", "Internal error")))
					terminalReservations.incrementAndGet();
			} else {
				respondUnavailable();
			}
		}

		private void cancel(StreamTerminationReason reason, @Nullable Throwable cause,
				boolean interruptHandler) {
			requireNonNull(reason);
			Cancellation value = new Cancellation(reason, cause);

			if (!cancellation.compareAndSet(null, value))
				return;

			McpHandlerDispatcher.Ticket activeTicket = ticket();
			boolean removedFromQueue = dispatcher.cancelQueued(activeTicket);

			if (interruptHandler && !removedFromQueue)
				activeTicket.requestInterrupt();

			if (responseClaimed.get())
				channel.close(reason, cause);
			else
				markResponseTerminated();
		}

		private void terminateForBackpressure() {
			Cancellation value = new Cancellation(StreamTerminationReason.BACKPRESSURE, null);
			cancellation.compareAndSet(null, value);
			ticket().requestInterrupt();
			channel.fail(StreamTerminationReason.BACKPRESSURE, null);
		}

		private void onChannelTerminated(StreamTerminationReason reason, @Nullable Throwable cause) {
			if (reason != StreamTerminationReason.COMPLETED) {
				cancellation.compareAndSet(null, new Cancellation(reason, cause));
				ticket().requestInterrupt();
			}

			markResponseTerminated();
		}

		private void markResponseTerminated() {
			responseTerminated.set(true);
			cleanupAfterResponseAndHandler();
		}

		private void cleanupAfterResponseAndHandler() {
			if (responseTerminated.get())
				cleanupResponse(this);

			if (responseCleaned.get() && !handlerRunning.get())
				exchanges.remove(exchangeId, this);
		}

		private void onTimer(long now) {
			if (responseTerminated.get() && !handlerRunning.get())
				return;

			if (now - deadlineNanos >= 0L) {
				onRequestDeadline();
				return;
			}

			if (responseClaimed.get()) {
				long writeIdleDeadline = channel.responseWriteIdleDeadlineNanos(
						configuration.responseWriteIdleTimeout().toNanos());

				if (writeIdleDeadline != Long.MAX_VALUE && now - writeIdleDeadline >= 0L) {
					Cancellation value = new Cancellation(StreamTerminationReason.RESPONSE_IDLE_TIMEOUT, null);
					cancellation.compareAndSet(null, value);
					ticket().requestInterrupt();
					channel.fail(StreamTerminationReason.RESPONSE_IDLE_TIMEOUT, null);
					return;
				}
			}

			if (subscription.get() && now - nextKeepAliveNanos.get() >= 0L) {
				nextKeepAliveNanos.set(saturatingAdd(now, configuration.keepAliveInterval().toNanos()));
				McpOutboundChannel.OfferResult result = channel.offer(KEEP_ALIVE_EVENT);

				if (result == McpOutboundChannel.OfferResult.FULL
						|| result == McpOutboundChannel.OfferResult.TOO_LARGE)
					terminateForBackpressure();
			}
		}

		private void onTimerFailure(Throwable throwable) {
			if (responseTerminated.get() && !handlerRunning.get())
				return;

			Cancellation value = new Cancellation(StreamTerminationReason.INTERNAL_ERROR, throwable);

			if (!cancellation.compareAndSet(null, value))
				return;

			McpHandlerDispatcher.Ticket activeTicket = ticket();
			boolean removedFromQueue = dispatcher.cancelQueued(activeTicket);

			if (!removedFromQueue)
				activeTicket.requestInterrupt();

			if (responseClaimed.get())
				channel.fail(StreamTerminationReason.INTERNAL_ERROR, throwable);
			else
				respondUnavailable();
		}

		private void onRequestDeadline() {
			Cancellation value = new Cancellation(StreamTerminationReason.RESPONSE_TIMEOUT, null);

			if (!cancellation.compareAndSet(null, value))
				return;

			McpHandlerDispatcher.Ticket activeTicket = ticket();
			dispatcher.cancelQueued(activeTicket);
			activeTicket.requestInterrupt();

			if (!responseClaimed.get()) {
				respondUnavailable();
				return;
			}

			if (channel.complete(sseEvent("error", "Request deadline exceeded"))) {
				terminalReservations.incrementAndGet();
			} else {
				// A previously reserved application terminal may still be queued behind regular data.
				// Once the absolute deadline wins, that stale terminal must not drain to the client.
				channel.fail(StreamTerminationReason.RESPONSE_TIMEOUT, null);
			}
		}

		private McpHandlerDispatcher.Ticket ticket() {
			return requireNonNull(ticket, "Exchange ticket has not been initialized");
		}
	}
}
