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

import com.soklet.StreamTerminationReason;
import com.soklet.internal.microhttp.MicrohttpRequest;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

@FunctionalInterface
interface McpApplicationRequestHandler {
	McpWireResult handle(McpApplicationInvocation invocation) throws Exception;
}

final class McpApplicationRequestRouter {
	private final Map<String, McpApplicationRequestHandler> handlersByMethod;

	private McpApplicationRequestRouter(Map<String, McpApplicationRequestHandler> handlersByMethod) {
		this.handlersByMethod = handlersByMethod;
	}

	static McpApplicationRequestRouter empty() {
		return new McpApplicationRequestRouter(Map.of());
	}

	static McpApplicationRequestRouter fromHandlers(
			Map<String, McpApplicationRequestHandler> handlersByMethod) {
		requireNonNull(handlersByMethod);
		Map<String, McpApplicationRequestHandler> copied =
				new LinkedHashMap<>(handlersByMethod.size());

		for (Map.Entry<String, McpApplicationRequestHandler> entry : handlersByMethod.entrySet()) {
			String method = requireNonNull(entry.getKey());
			if (method.isBlank())
				throw new IllegalArgumentException("Application MCP methods must not be blank.");
			if ("server/discover".equals(method))
				throw new IllegalArgumentException(
						"Framework-owned server/discover cannot be replaced by an application handler.");
			copied.put(method, requireNonNull(entry.getValue()));
		}

		return new McpApplicationRequestRouter(Collections.unmodifiableMap(copied));
	}

	Optional<McpApplicationRequestHandler> resolve(String method) {
		return Optional.ofNullable(handlersByMethod.get(requireNonNull(method)));
	}
}

record McpApplicationExecutionConfiguration(int handlerConcurrency,
		int handlerQueueCapacity, Duration requestDeadline, Duration timerResolution) {
	McpApplicationExecutionConfiguration {
		if (handlerConcurrency < 1)
			throw new IllegalArgumentException("Handler concurrency must be positive.");
		if (handlerQueueCapacity < 1)
			throw new IllegalArgumentException("Handler queue capacity must be positive.");
		positiveDuration(requestDeadline, "Request deadline");
		positiveDuration(timerResolution, "Timer resolution");
	}

	static McpApplicationExecutionConfiguration productionDefaults() {
		return new McpApplicationExecutionConfiguration(
				32, 128, Duration.ofSeconds(60), Duration.ofMillis(10));
	}

	private static void positiveDuration(Duration value, String description) {
		requireNonNull(value);
		if (value.isZero() || value.isNegative())
			throw new IllegalArgumentException(description + " must be positive.");
		try {
			value.toNanos();
		} catch (ArithmeticException exception) {
			throw new IllegalArgumentException(
					description + " must fit in a signed nanosecond duration.", exception);
		}
	}
}

@FunctionalInterface
interface McpApplicationClock {
	McpApplicationClock SYSTEM = System::nanoTime;

	long nanoTime();
}

@FunctionalInterface
interface McpProtocolDeadlineCycle {
	void run(long nowNanos);
}

interface McpApplicationCancellation {
	boolean isCancellationRequested();

	Optional<StreamTerminationReason> reason();
}

final class McpApplicationCancellationState implements McpApplicationCancellation {
	private final AtomicReference<StreamTerminationReason> reason;

	McpApplicationCancellationState() {
		this.reason = new AtomicReference<>();
	}

	@Override
	public boolean isCancellationRequested() {
		return reason.get() != null;
	}

	@Override
	public Optional<StreamTerminationReason> reason() {
		return Optional.ofNullable(reason.get());
	}

	boolean cancel(StreamTerminationReason reason) {
		return this.reason.compareAndSet(null, requireNonNull(reason));
	}
}

final class McpApplicationInvocation {
	private final McpJsonRpcMessage.Request request;
	private final McpApplicationCancellation cancellation;

	McpApplicationInvocation(McpJsonRpcMessage.Request request,
			McpApplicationCancellation cancellation) {
		this.request = requireNonNull(request);
		this.cancellation = requireNonNull(cancellation);
	}

	McpJsonRpcMessage.Request request() {
		return request;
	}

	boolean isCancellationRequested() {
		return cancellation.isCancellationRequested();
	}

	Optional<StreamTerminationReason> cancellationReason() {
		return cancellation.reason();
	}
}

record McpApplicationResponse(int status, String reason,
		Optional<McpJsonRpcMessage> message) {
	McpApplicationResponse {
		if (status < 100 || status > 599)
			throw new IllegalArgumentException("HTTP status must be between 100 and 599.");
		requireNonNull(reason);
		requireNonNull(message);
	}

	static McpApplicationResponse success(McpJsonRpcId id, McpWireResult result) {
		return new McpApplicationResponse(200, "OK", Optional.of(
				new McpJsonRpcMessage.ResultResponse(requireNonNull(id), requireNonNull(result),
						McpJsonObject.empty())));
	}

	static McpApplicationResponse internalError(McpJsonRpcId id, int status, String reason) {
		return error(id, status, reason, McpJsonRpcError.INTERNAL_ERROR, "Internal error");
	}

	static McpApplicationResponse duplicateRequestId(McpJsonRpcId id) {
		// The protocol requires sender-side in-flight uniqueness but does not freeze a
		// server collision mapping. This package-private response remains provisional.
		return error(id, 400, "Bad Request", McpJsonRpcError.INVALID_REQUEST,
				"Invalid Request");
	}

	static McpApplicationResponse activeDeadline() {
		// Phase 3B.1 has no frozen pre-commit active-handler timeout wire mapping.
		// An empty 504 closes the JSON-only response lifetime without claiming one.
		return new McpApplicationResponse(504, "Gateway Timeout", Optional.empty());
	}

	private static McpApplicationResponse error(McpJsonRpcId id, int status,
			String reason, int code, String message) {
		McpJsonRpcError error = new McpJsonRpcError(code, message, Optional.empty());
		return new McpApplicationResponse(status, reason, Optional.of(
				new McpJsonRpcMessage.ErrorResponse(Optional.of(requireNonNull(id)), error,
						McpJsonObject.empty())));
	}
}

@FunctionalInterface
interface McpApplicationResponseWriter {
	boolean write(McpApplicationResponse response);
}

record McpApplicationExecutionSnapshot(int configuredHandlerConcurrency,
		int configuredHandlerQueueCapacity, int activeHandlerSlots, int queuedRequests,
		int maximumObservedActiveHandlerSlots, int maximumObservedQueuedRequests,
		int activeRequestIds, int retainedExchanges, int retainedTransportLeases,
		long admittedRequests,
		long capacityRejections, long duplicateIdRejections, long deadlineExpirations,
		long protocolDeadlineExpirations,
		long terminalResponses, long abandonedResponses, long responseCleanups,
		boolean accepting, boolean terminated) {
}

/**
 * One listener-generation's application execution state. Protocol parsing is
 * deliberately outside this type; handler admission returns immediately and
 * never consumes a protocol request-processing thread while application work
 * is queued or running.
 */
final class McpApplicationExecution {
	private record TransportLease(MicrohttpRequest transportRequest,
			McpApplicationResponseWriter responseWriter, Runnable terminalCleanup) {
		private TransportLease {
			requireNonNull(transportRequest);
			requireNonNull(responseWriter);
			requireNonNull(terminalCleanup);
		}
	}

	private enum TerminalState {
		OPEN,
		RESPONSE_OFFERED,
		ABANDONED
	}

	private final McpApplicationExecutionConfiguration configuration;
	private final McpApplicationClock clock;
	private final @Nullable McpProtocolDeadlineCycle protocolDeadlineCycle;
	private final ExecutorService handlerExecutor;
	private final McpApplicationHandlerDispatcher dispatcher;
	private final Object executionBoundaryLock;
	private final Map<MicrohttpRequest, Exchange> requestsByIdentity;
	private final ConcurrentHashMap<Long, Exchange> retainedExchanges;
	private final AtomicLong exchangeSequence;
	private final AtomicLong admittedRequests;
	private final AtomicLong capacityRejections;
	private final AtomicLong duplicateIdRejections;
	private final AtomicLong deadlineExpirations;
	private final AtomicLong protocolDeadlineExpirations;
	private final AtomicLong terminalResponses;
	private final AtomicLong abandonedResponses;
	private final AtomicLong responseCleanups;
	private final AtomicBoolean started;
	private final AtomicBoolean stopped;
	private final AtomicReference<StreamTerminationReason> stoppingReason;
	private final Thread timerThread;

	McpApplicationExecution(McpApplicationExecutionConfiguration configuration,
			McpApplicationClock clock) {
		this(configuration, clock, McpApplicationHandlerExecutorFactory.production());
	}

	McpApplicationExecution(McpApplicationExecutionConfiguration configuration,
			McpApplicationClock clock,
			McpApplicationHandlerExecutorFactory executorFactory) {
		this(configuration, clock, executorFactory, null);
	}

	McpApplicationExecution(McpApplicationExecutionConfiguration configuration,
			McpApplicationClock clock,
			McpApplicationHandlerExecutorFactory executorFactory,
			@Nullable McpProtocolDeadlineCycle protocolDeadlineCycle) {
		this.configuration = requireNonNull(configuration);
		this.clock = requireNonNull(clock);
		this.protocolDeadlineCycle = protocolDeadlineCycle;
		this.handlerExecutor = requireNonNull(requireNonNull(executorFactory).create(
				configuration.handlerConcurrency()),
				"The application handler executor factory returned null.");
		this.dispatcher = new McpApplicationHandlerDispatcher(
				configuration.handlerConcurrency(), configuration.handlerQueueCapacity(),
				handlerExecutor);
		this.executionBoundaryLock = new Object();
		this.requestsByIdentity = Collections.synchronizedMap(new IdentityHashMap<>());
		this.retainedExchanges = new ConcurrentHashMap<>();
		this.exchangeSequence = new AtomicLong();
		this.admittedRequests = new AtomicLong();
		this.capacityRejections = new AtomicLong();
		this.duplicateIdRejections = new AtomicLong();
		this.deadlineExpirations = new AtomicLong();
		this.protocolDeadlineExpirations = new AtomicLong();
		this.terminalResponses = new AtomicLong();
		this.abandonedResponses = new AtomicLong();
		this.responseCleanups = new AtomicLong();
		this.started = new AtomicBoolean();
		this.stopped = new AtomicBoolean();
		this.stoppingReason = new AtomicReference<>();
		this.timerThread = new Thread(this::runTimerLoop, "soklet-mcp-deadline");
		this.timerThread.setDaemon(false);
	}

	void start() {
		if (stopped.get() || !started.compareAndSet(false, true))
			throw new IllegalStateException("Application execution has already been started.");
		timerThread.start();
	}

	void dispatch(MicrohttpRequest transportRequest,
			McpJsonRpcMessage.Request request, McpApplicationRequestHandler handler,
			long deadlineNanos, McpApplicationResponseWriter responseWriter,
			Runnable terminalCleanup) {
		requireNonNull(transportRequest);
		requireNonNull(request);
		requireNonNull(handler);
		requireNonNull(responseWriter);
		requireNonNull(terminalCleanup);

		if (stopped.get()) {
			terminalCleanup.run();
			return;
		}

		long exchangeId = exchangeSequence.incrementAndGet();
		Exchange exchange = new Exchange(exchangeId, transportRequest, request, handler,
				deadlineNanos, responseWriter, terminalCleanup);

		McpApplicationHandlerDispatcher.Ticket ticket = dispatcher.newTicket(
				exchange::runHandler, exchange::submissionFailed);
		exchange.bindTicket(ticket);
		requestsByIdentity.put(transportRequest, exchange);
		retainedExchanges.put(exchangeId, exchange);
		if (clock.nanoTime() - deadlineNanos >= 0L) {
			exchange.onDeadline();
			return;
		}

		McpApplicationHandlerDispatcher.Admission admission = dispatcher.admit(ticket);
		switch (admission) {
			case DISPATCHED, QUEUED -> {
				admittedRequests.incrementAndGet();
				signalDeadlineTimer();
			}
			case REJECTED -> {
				capacityRejections.incrementAndGet();
				exchange.respond(McpApplicationResponse.internalError(
						request.id(), 503, "Service Unavailable"));
			}
			case CLOSED -> exchange.releaseAfterClosure();
			case CANCELED -> exchange.cleanupRetainedExchange();
		}
	}

	void recordDuplicateIdRejection() {
		duplicateIdRejections.incrementAndGet();
	}

	void recordProtocolDeadlineExpiration() {
		protocolDeadlineExpirations.incrementAndGet();
		deadlineExpirations.incrementAndGet();
	}

	/*
	 * Linearizes a bounded protocol-state mutation with this listener
	 * generation's stop boundary. Production reservations must not invoke user
	 * code or perform blocking work while the boundary is held.
	 */
	<T> Optional<T> reserveProtocolOperationIfRunning(Supplier<T> reservation) {
		requireNonNull(reservation);
		synchronized (executionBoundaryLock) {
			if (stopped.get())
				return Optional.empty();
			return Optional.of(requireNonNull(reservation.get()));
		}
	}

	void cancel(MicrohttpRequest request, StreamTerminationReason reason,
			@Nullable Throwable cause) {
		Exchange exchange = requestsByIdentity.get(requireNonNull(request));
		if (exchange != null)
			exchange.cancel(reason, cause);
	}

	void runTimerCycle() {
		long now = clock.nanoTime();
		if (protocolDeadlineCycle != null)
			protocolDeadlineCycle.run(now);
		for (Exchange exchange : retainedExchanges.values()) {
			try {
				exchange.onTimer(now);
			} catch (Throwable throwable) {
				exchange.cancel(StreamTerminationReason.INTERNAL_ERROR, throwable);
			}
		}
	}

	McpApplicationExecutionSnapshot snapshot() {
		return snapshot(0);
	}

	McpApplicationExecutionSnapshot snapshot(int activeRequestIds) {
		McpApplicationHandlerDispatcher.Snapshot dispatcherSnapshot = dispatcher.snapshot();
		return new McpApplicationExecutionSnapshot(
				dispatcherSnapshot.concurrency(),
				dispatcherSnapshot.queueCapacity(),
				dispatcherSnapshot.activeSlots(),
				dispatcherSnapshot.queueDepth(),
				dispatcherSnapshot.maximumObservedActiveSlots(),
				dispatcherSnapshot.maximumObservedQueueDepth(),
				activeRequestIds,
				retainedExchanges.size(),
				(int) retainedExchanges.values().stream()
						.filter(Exchange::hasTransportLease).count(),
				admittedRequests.get(),
				capacityRejections.get(),
				duplicateIdRejections.get(),
				deadlineExpirations.get(),
				protocolDeadlineExpirations.get(),
				terminalResponses.get(),
				abandonedResponses.get(),
				responseCleanups.get(),
				dispatcherSnapshot.accepting(),
				isTerminated());
	}

	void stop() {
		stop(StreamTerminationReason.SERVER_STOPPING);
	}

	void stop(StreamTerminationReason reason) {
		requireNonNull(reason);
		synchronized (executionBoundaryLock) {
			if (stopped.get())
				return;
			stoppingReason.set(reason);
			stopped.set(true);
		}

		dispatcher.stopAccepting();
		for (Exchange exchange : List.copyOf(retainedExchanges.values())) {
			exchange.cancel(reason, null);
			// A terminal response may have won before shutdown while its handler is
			// still unwinding. It remains application work and receives the same
			// cooperative interruption signal.
			exchange.requestInterrupt();
		}
		// All dispatched tickets have been signaled explicitly. Graceful executor
		// shutdown is essential here: shutdownNow may discard a dispatcher-owned
		// runnable promoted while its current worker is still returning, leaving
		// the logical handler slot charged forever.
		handlerExecutor.shutdown();
		LockSupport.unpark(timerThread);
	}

	boolean awaitTermination(Duration timeout) throws InterruptedException {
		requireNonNull(timeout);
		if (timeout.isNegative())
			throw new IllegalArgumentException("Termination timeout must not be negative.");
		long timeoutNanos = timeout.toNanos();
		if (timeoutNanos == 0L)
			return isTerminated();
		long startedAt = System.nanoTime();

		if (timerThread.isAlive()) {
			long milliseconds = TimeUnit.NANOSECONDS.toMillis(timeoutNanos);
			int nanoseconds = (int) (timeoutNanos
					- TimeUnit.MILLISECONDS.toNanos(milliseconds));
			timerThread.join(milliseconds, nanoseconds);
		}

		long elapsed = System.nanoTime() - startedAt;
		long remaining = Math.max(0L, timeoutNanos - Math.max(0L, elapsed));
		if (!handlerExecutor.isTerminated() && remaining > 0L)
			handlerExecutor.awaitTermination(remaining, TimeUnit.NANOSECONDS);
		return isTerminated();
	}

	boolean isTerminated() {
		McpApplicationHandlerDispatcher.Snapshot dispatcherSnapshot =
				dispatcher.snapshot();
		return stopped.get() && !timerThread.isAlive() && handlerExecutor.isTerminated()
				&& dispatcherSnapshot.activeSlots() == 0
				&& dispatcherSnapshot.queueDepth() == 0
				&& retainedExchanges.isEmpty()
				&& requestsByIdentity.isEmpty();
	}

	private void runTimerLoop() {
		while (!stopped.get()) {
			try {
				runTimerCycle();
			} catch (Throwable ignored) {
				// One cycle failure must not permanently disable request deadlines.
			}

			if (!stopped.get())
				LockSupport.parkNanos(configuration.timerResolution().toNanos());
		}
	}

	void signalDeadlineTimer() {
		LockSupport.unpark(timerThread);
	}

	private StreamTerminationReason stoppingReason() {
		return requireNonNull(stoppingReason.get(),
				"A stopped application execution must have a stopping reason.");
	}

	private final class Exchange {
		private final long exchangeId;
		private final McpJsonRpcMessage.Request request;
		private final McpApplicationRequestHandler handler;
		private final long deadlineNanos;
		private final AtomicReference<TransportLease> transportLease;
		private final Object terminalLock;
		private final McpApplicationCancellationState cancellation;
		private final AtomicBoolean handlerRunning;
		private final AtomicBoolean handlerFinished;
		private TerminalState terminalState;
		private McpApplicationHandlerDispatcher.@Nullable Ticket ticket;

		private Exchange(long exchangeId, MicrohttpRequest transportRequest,
				McpJsonRpcMessage.Request request, McpApplicationRequestHandler handler,
				long deadlineNanos, McpApplicationResponseWriter responseWriter,
				Runnable terminalCleanup) {
			this.exchangeId = exchangeId;
			this.request = request;
			this.handler = handler;
			this.deadlineNanos = deadlineNanos;
			this.transportLease = new AtomicReference<>(new TransportLease(
					transportRequest, responseWriter, terminalCleanup));
			this.terminalLock = new Object();
			this.cancellation = new McpApplicationCancellationState();
			this.handlerRunning = new AtomicBoolean();
			this.handlerFinished = new AtomicBoolean();
			this.terminalState = TerminalState.OPEN;
		}

		private void bindTicket(McpApplicationHandlerDispatcher.Ticket ticket) {
			this.ticket = requireNonNull(ticket);
		}

		private void runHandler() {
			if (!handlerRunning.compareAndSet(false, true))
				throw new IllegalStateException("An MCP exchange cannot run twice.");

			try {
				if (clock.nanoTime() - deadlineNanos >= 0L) {
					onDeadline(false);
					return;
				}
				if (!beginApplicationInvocation())
					return;

				McpWireResult result = handler.handle(
						new McpApplicationInvocation(request, cancellation));
				if (result == null)
					throw new IllegalStateException("An MCP application handler returned null.");
				respond(McpApplicationResponse.success(request.id(), result));
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				if (!cancellation.isCancellationRequested())
					respond(McpApplicationResponse.internalError(
							request.id(), 500, "Internal Server Error"));
			} catch (Throwable throwable) {
				if (!cancellation.isCancellationRequested())
					respond(McpApplicationResponse.internalError(
							request.id(), 500, "Internal Server Error"));
			} finally {
				handlerRunning.set(false);
				handlerFinished.set(true);
				cleanupRetainedExchange();
			}
		}

		private boolean beginApplicationInvocation() {
			boolean shutdown;
			synchronized (executionBoundaryLock) {
				shutdown = stopped.get();
				if (!shutdown) {
					synchronized (terminalLock) {
						if (terminalState != TerminalState.OPEN
								|| cancellation.isCancellationRequested())
							return false;
					}
				}
			}

			if (shutdown)
				cancel(stoppingReason(), null);
			return !shutdown;
		}

		private void submissionFailed(Throwable throwable) {
			try {
				if (!cancellation.isCancellationRequested())
					respond(McpApplicationResponse.internalError(
							request.id(), 500, "Internal Server Error"));
			} finally {
				// The dispatcher has already changed the ticket to REJECTED and
				// released its slot. Cancellation may also have detached the lease.
				cleanupRetainedExchange();
			}
		}

		private boolean respond(McpApplicationResponse response) {
			TransportLease lease;
			boolean shutdown;
			boolean deadlineExpired;
			synchronized (executionBoundaryLock) {
				shutdown = stopped.get();
				if (!shutdown) {
					deadlineExpired = clock.nanoTime() - deadlineNanos >= 0L;
					if (!deadlineExpired) {
						synchronized (terminalLock) {
							if (terminalState != TerminalState.OPEN
									|| cancellation.isCancellationRequested())
								return false;
							lease = requireNonNull(transportLease.get(),
									"An open exchange must retain its transport lease.");
							terminalState = TerminalState.RESPONSE_OFFERED;
						}
					} else {
						lease = null;
					}
				} else {
					deadlineExpired = false;
					lease = null;
				}
			}
			if (shutdown) {
				cancel(stoppingReason(), null);
				return false;
			}
			if (deadlineExpired) {
				onDeadline(false);
				return false;
			}

			boolean accepted = false;
			try {
				accepted = requireNonNull(lease).responseWriter().write(response);
			} catch (Throwable ignored) {
				// The terminal reservation still wins when the transport callback fails.
			} finally {
				if (accepted)
					terminalResponses.incrementAndGet();
				else
					abandonedResponses.incrementAndGet();
				releaseResponseOwnership();
			}
			return accepted;
		}

		private void cancel(StreamTerminationReason reason, @Nullable Throwable cause) {
			boolean cancelBeforeDispatch;
			synchronized (terminalLock) {
				if (terminalState != TerminalState.OPEN)
					return;
				// Retain only the application-visible reason. Transport exceptions can
				// retain connection internals and are detached with the response lease.
				cancellation.cancel(requireNonNull(reason));
				terminalState = TerminalState.ABANDONED;
				cancelBeforeDispatch = dispatcher.cancelBeforeDispatch(ticket());
			}

			if (!cancelBeforeDispatch)
				ticket().requestInterrupt();
			abandonedResponses.incrementAndGet();
			releaseResponseOwnership();
		}

		private void onTimer(long now) {
			if (now - deadlineNanos >= 0L)
				onDeadline();
		}

		private void onDeadline() {
			onDeadline(true);
		}

		private void onDeadline(boolean requestInterrupt) {
			boolean canceledBeforeDispatch;
			McpApplicationResponse response;
			TransportLease lease;
			boolean shutdown;
			synchronized (executionBoundaryLock) {
				shutdown = stopped.get();
				if (!shutdown) {
					synchronized (terminalLock) {
						if (terminalState != TerminalState.OPEN)
							return;
						cancellation.cancel(StreamTerminationReason.RESPONSE_TIMEOUT);
						canceledBeforeDispatch = dispatcher.cancelBeforeDispatch(ticket());
						lease = requireNonNull(transportLease.get(),
								"An open exchange must retain its transport lease.");
						terminalState = TerminalState.RESPONSE_OFFERED;
						response = canceledBeforeDispatch
								? McpApplicationResponse.internalError(
										request.id(), 503, "Service Unavailable")
								: McpApplicationResponse.activeDeadline();
					}
				} else {
					canceledBeforeDispatch = false;
					response = null;
					lease = null;
				}
			}
			if (shutdown) {
				cancel(stoppingReason(), null);
				return;
			}

			deadlineExpirations.incrementAndGet();
			if (!canceledBeforeDispatch && requestInterrupt)
				ticket().requestInterrupt();
			boolean accepted = false;
			try {
				accepted = requireNonNull(lease).responseWriter().write(
						requireNonNull(response));
			} catch (Throwable ignored) {
				// A deadline still owns the terminal outcome when its write fails.
			} finally {
				if (accepted)
					terminalResponses.incrementAndGet();
				else
					abandonedResponses.incrementAndGet();
				releaseResponseOwnership();
			}
		}

		private void releaseAfterClosure() {
			synchronized (terminalLock) {
				if (terminalState == TerminalState.OPEN) {
					cancellation.cancel(stoppingReason());
					terminalState = TerminalState.ABANDONED;
					abandonedResponses.incrementAndGet();
				}
			}
			releaseResponseOwnership();
		}

		private void requestInterrupt() {
			ticket().requestInterrupt();
		}

		private void releaseResponseOwnership() {
			TransportLease lease = transportLease.getAndSet(null);
			if (lease == null)
				return;

			requestsByIdentity.remove(lease.transportRequest(), this);
			responseCleanups.incrementAndGet();
			try {
				try {
					lease.terminalCleanup().run();
				} catch (Throwable ignored) {
					// Cleanup failures must not retain the exchange indefinitely.
				}
			} finally {
				cleanupRetainedExchange();
			}
		}

		private void cleanupRetainedExchange() {
			McpApplicationHandlerDispatcher.TicketState ticketState = ticket().state();
			boolean handlerCannotRun = ticketState ==
					McpApplicationHandlerDispatcher.TicketState.CANCELED
					|| ticketState == McpApplicationHandlerDispatcher.TicketState.REJECTED;
			if (transportLease.get() == null && (handlerFinished.get() || handlerCannotRun))
				retainedExchanges.remove(exchangeId, this);
		}

		private boolean hasTransportLease() {
			return transportLease.get() != null;
		}

		private McpApplicationHandlerDispatcher.Ticket ticket() {
			return requireNonNull(ticket, "Application handler ticket has not been bound.");
		}
	}
}
