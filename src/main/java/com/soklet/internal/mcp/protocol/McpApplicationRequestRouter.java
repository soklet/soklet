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

import com.soklet.Request;
import com.soklet.StreamTerminationReason;
import com.soklet.internal.microhttp.MicrohttpRequest;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.ThreadSafe;
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

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
@FunctionalInterface
interface McpApplicationRequestHandler {
	@NonNull
	McpWireResult handle(@NonNull McpApplicationInvocation invocation) throws Exception;
}

/**
 * Internal control signal for a validated JSON-RPC request whose application
 * input failed schema validation or binding before its typed handler ran.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
final class McpInvalidApplicationInputException extends Exception {
	McpInvalidApplicationInputException() {
		super(null, null, false, false);
	}
}

/**
 * Exact executable route for one registered tool.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
record McpApplicationToolRoute(@NonNull McpApplicationRequestHandler handler,
		@NonNull McpRateLimiter rateLimiter) {
	McpApplicationToolRoute {
		requireNonNull(handler);
		requireNonNull(rateLimiter);
	}
}

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class McpApplicationRequestRouter {
	@NonNull
	private final Map<@NonNull String, @NonNull McpApplicationRequestHandler> handlersByMethod;
	@NonNull
	private final Map<@NonNull String, @NonNull McpApplicationToolRoute> toolRoutesByName;

	private McpApplicationRequestRouter(
			@NonNull Map<@NonNull String, @NonNull McpApplicationRequestHandler> handlersByMethod,
			@NonNull Map<@NonNull String, @NonNull McpApplicationToolRoute> toolRoutesByName) {
		this.handlersByMethod = handlersByMethod;
		this.toolRoutesByName = toolRoutesByName;
	}

	@NonNull
	static McpApplicationRequestRouter empty() {
		return new McpApplicationRequestRouter(Map.of(), Map.of());
	}

	@NonNull
	static McpApplicationRequestRouter fromHandlers(
			@NonNull Map<@NonNull String, @NonNull McpApplicationRequestHandler> handlersByMethod) {
		return fromHandlersAndToolRoutes(handlersByMethod, Map.of());
	}

	@NonNull
	static McpApplicationRequestRouter fromToolRoutes(
			@NonNull Map<@NonNull String, @NonNull McpApplicationToolRoute> toolRoutesByName) {
		return fromHandlersAndToolRoutes(Map.of(), toolRoutesByName);
	}

	@NonNull
	static McpApplicationRequestRouter fromHandlersAndToolRoutes(
			@NonNull Map<@NonNull String, @NonNull McpApplicationRequestHandler> handlersByMethod,
			@NonNull Map<@NonNull String, @NonNull McpApplicationToolRoute> toolRoutesByName) {
		requireNonNull(handlersByMethod);
		requireNonNull(toolRoutesByName);
		Map<String, McpApplicationRequestHandler> copied =
				new LinkedHashMap<>(handlersByMethod.size());

		for (Map.Entry<String, McpApplicationRequestHandler> entry : handlersByMethod.entrySet()) {
			String method = requireNonNull(entry.getKey());
			if (method.isBlank())
				throw new IllegalArgumentException("Application MCP methods must not be blank.");
			if ("server/discover".equals(method) || "tools/list".equals(method))
				throw new IllegalArgumentException(
						"Framework-owned MCP methods cannot be replaced by an application handler.");
			copied.put(method, requireNonNull(entry.getValue()));
		}

		Map<String, McpApplicationToolRoute> copiedToolRoutes =
				new LinkedHashMap<>(toolRoutesByName.size());
		for (Map.Entry<String, McpApplicationToolRoute> entry
				: toolRoutesByName.entrySet()) {
			String name = McpProtocolSupport.requireNonBlank(
					requireNonNull(entry.getKey()), "Tool route name");
			copiedToolRoutes.put(name, requireNonNull(entry.getValue()));
		}

		return new McpApplicationRequestRouter(
				Collections.unmodifiableMap(copied),
				Collections.unmodifiableMap(copiedToolRoutes));
	}

	@NonNull
	Optional<@NonNull McpApplicationRequestHandler> resolve(@NonNull String method) {
		return Optional.ofNullable(handlersByMethod.get(requireNonNull(method)));
	}

	@NonNull
	Optional<@NonNull McpApplicationToolRoute> resolveTool(@NonNull String name) {
		return Optional.ofNullable(toolRoutesByName.get(requireNonNull(name)));
	}

	boolean hasToolRoutes() {
		return !toolRoutesByName.isEmpty();
	}
}

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
record McpApplicationExecutionConfiguration(int handlerConcurrency,
		int handlerQueueCapacity, @NonNull Duration requestDeadline,
		@NonNull Duration timerResolution) {
	McpApplicationExecutionConfiguration {
		if (handlerConcurrency < 1)
			throw new IllegalArgumentException("Handler concurrency must be positive.");
		if (handlerQueueCapacity < 1)
			throw new IllegalArgumentException("Handler queue capacity must be positive.");
		positiveDuration(requestDeadline, "Request deadline");
		positiveDuration(timerResolution, "Timer resolution");
	}

	@NonNull
	static McpApplicationExecutionConfiguration productionDefaults() {
		return new McpApplicationExecutionConfiguration(
				32, 128, Duration.ofSeconds(60), Duration.ofMillis(10));
	}

	private static void positiveDuration(@NonNull Duration value,
			@NonNull String description) {
		requireNonNull(value);
		if (value.isZero() || value.isNegative())
			throw new IllegalArgumentException(description + " must be positive.");
		try {
			if (value.toNanos() < 1L)
				throw new IllegalArgumentException(description + " must be positive.");
		} catch (ArithmeticException exception) {
			throw new IllegalArgumentException(
					description + " must fit in a signed nanosecond duration.", exception);
		}
	}
}

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
@FunctionalInterface
interface McpApplicationClock {
	@NonNull
	McpApplicationClock SYSTEM = System::nanoTime;

	long nanoTime();
}

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@FunctionalInterface
interface McpProtocolDeadlineCycle {
	void run(long nowNanos);
}

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
interface McpApplicationCancellation {
	boolean isCancellationRequested();

	@NonNull
	Optional<@NonNull StreamTerminationReason> reason();
}

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class McpApplicationCancellationState implements McpApplicationCancellation {
	@NonNull
	private final AtomicReference<@Nullable StreamTerminationReason> reason;

	McpApplicationCancellationState() {
		this.reason = new AtomicReference<>();
	}

	@Override
	public boolean isCancellationRequested() {
		return reason.get() != null;
	}

	@Override
	@NonNull
	public Optional<@NonNull StreamTerminationReason> reason() {
		return Optional.ofNullable(reason.get());
	}

	boolean cancel(@NonNull StreamTerminationReason reason) {
		return this.reason.compareAndSet(null, requireNonNull(reason));
	}
}

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class McpApplicationInvocation {
	private final @Nullable Request sokletRequest;
	private final McpJsonRpcMessage.@NonNull Request request;
	@NonNull
	private final McpEffectiveAdmissionIdentity admissionIdentity;
	@NonNull
	private final McpApplicationCancellation cancellation;
	@NonNull
	private final McpApplicationNotificationWriter notificationWriter;

	McpApplicationInvocation(@Nullable Request sokletRequest,
			McpJsonRpcMessage.@NonNull Request request,
			@NonNull McpEffectiveAdmissionIdentity admissionIdentity,
			@NonNull McpApplicationCancellation cancellation,
			@NonNull McpApplicationNotificationWriter notificationWriter) {
		this.sokletRequest = sokletRequest;
		this.request = requireNonNull(request);
		this.admissionIdentity = requireNonNull(admissionIdentity);
		this.cancellation = requireNonNull(cancellation);
		this.notificationWriter = requireNonNull(notificationWriter);
	}

	McpJsonRpcMessage.@NonNull Request request() {
		return request;
	}

	@NonNull
	Optional<@NonNull Request> sokletRequest() {
		return Optional.ofNullable(sokletRequest);
	}

	@NonNull
	McpEffectiveAdmissionIdentity admissionIdentity() {
		return admissionIdentity;
	}

	boolean isCancellationRequested() {
		return cancellation.isCancellationRequested();
	}

	@NonNull
	Optional<@NonNull StreamTerminationReason> cancellationReason() {
		return cancellation.reason();
	}

	boolean sendNotification(McpJsonRpcMessage.@NonNull Notification notification)
			throws InterruptedException {
		return notificationWriter.write(requireNonNull(notification));
	}
}

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
@FunctionalInterface
interface McpApplicationNotificationWriter {
	boolean write(McpJsonRpcMessage.@NonNull Notification notification)
			throws InterruptedException;
}

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
record McpApplicationResponse(int status, @NonNull String reason,
		@NonNull Optional<@NonNull McpJsonRpcMessage> message) {
	McpApplicationResponse {
		if (status < 100 || status > 599)
			throw new IllegalArgumentException("HTTP status must be between 100 and 599.");
		requireNonNull(reason);
		requireNonNull(message);
	}

	@NonNull
	static McpApplicationResponse success(@NonNull McpJsonRpcId id,
			@NonNull McpWireResult result) {
		return new McpApplicationResponse(200, "OK", Optional.of(
				new McpJsonRpcMessage.ResultResponse(requireNonNull(id), requireNonNull(result),
						McpJsonObject.empty())));
	}

	@NonNull
	static McpApplicationResponse internalError(@NonNull McpJsonRpcId id,
			int status, @NonNull String reason) {
		return error(id, status, reason, McpJsonRpcError.INTERNAL_ERROR, "Internal error");
	}

	@NonNull
	static McpApplicationResponse duplicateRequestId(@NonNull McpJsonRpcId id) {
		// The protocol requires sender-side in-flight uniqueness but does not freeze a
		// server collision mapping. This package-private response remains provisional.
		return error(id, 400, "Bad Request", McpJsonRpcError.INVALID_REQUEST,
				"Invalid Request");
	}

	@NonNull
	static McpApplicationResponse invalidParams(@NonNull McpJsonRpcId id) {
		return error(id, 400, "Bad Request", McpJsonRpcError.INVALID_PARAMS,
				"Invalid params");
	}

	@NonNull
	static McpApplicationResponse activeDeadline() {
		// Phase 3B.1 has no frozen pre-commit active-handler timeout wire mapping.
		// An empty 504 closes the JSON-only response lifetime without claiming one.
		return new McpApplicationResponse(504, "Gateway Timeout", Optional.empty());
	}

	@NonNull
	private static McpApplicationResponse error(@NonNull McpJsonRpcId id,
			int status, @NonNull String reason, int code, @NonNull String message) {
		McpJsonRpcError error = new McpJsonRpcError(code, message, Optional.empty());
		return new McpApplicationResponse(status, reason, Optional.of(
				new McpJsonRpcMessage.ErrorResponse(Optional.of(requireNonNull(id)), error,
						McpJsonObject.empty())));
	}
}

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
@FunctionalInterface
interface McpApplicationResponseWriter {
	boolean write(@NonNull McpApplicationResponse response);

	default boolean writeNotification(McpJsonRpcMessage.@NonNull Notification notification)
			throws InterruptedException {
		return false;
	}
}

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
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
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class McpApplicationExecution {
	@ThreadSafe
	private record TransportLease(@NonNull MicrohttpRequest transportRequest,
			@NonNull McpApplicationResponseWriter responseWriter,
			@NonNull Runnable terminalCleanup) {
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

	@NonNull
	private final McpApplicationExecutionConfiguration configuration;
	@NonNull
	private final McpApplicationClock clock;
	private final @Nullable McpProtocolDeadlineCycle protocolDeadlineCycle;
	@NonNull
	private final McpApplicationRequestInterceptor requestInterceptor;
	@NonNull
	private final ExecutorService handlerExecutor;
	@NonNull
	private final McpApplicationHandlerDispatcher dispatcher;
	@NonNull
	private final Object executionBoundaryLock;
	@NonNull
	private final Map<@NonNull MicrohttpRequest, @NonNull Exchange> requestsByIdentity;
	@NonNull
	private final ConcurrentHashMap<@NonNull Long, @NonNull Exchange> retainedExchanges;
	@NonNull
	private final AtomicLong exchangeSequence;
	@NonNull
	private final AtomicLong admittedRequests;
	@NonNull
	private final AtomicLong capacityRejections;
	@NonNull
	private final AtomicLong duplicateIdRejections;
	@NonNull
	private final AtomicLong deadlineExpirations;
	@NonNull
	private final AtomicLong protocolDeadlineExpirations;
	@NonNull
	private final AtomicLong terminalResponses;
	@NonNull
	private final AtomicLong abandonedResponses;
	@NonNull
	private final AtomicLong responseCleanups;
	@NonNull
	private final AtomicBoolean started;
	@NonNull
	private final AtomicBoolean stopped;
	@NonNull
	private final AtomicReference<@Nullable StreamTerminationReason> stoppingReason;
	@NonNull
	private final Thread timerThread;

	McpApplicationExecution(
			@NonNull McpApplicationExecutionConfiguration configuration,
			@NonNull McpApplicationClock clock) {
		this(configuration, clock, McpApplicationHandlerExecutorFactory.production());
	}

	McpApplicationExecution(
			@NonNull McpApplicationExecutionConfiguration configuration,
			@NonNull McpApplicationClock clock,
			@NonNull McpApplicationHandlerExecutorFactory executorFactory) {
		this(configuration, clock, executorFactory, null);
	}

	McpApplicationExecution(
			@NonNull McpApplicationExecutionConfiguration configuration,
			@NonNull McpApplicationClock clock,
			@NonNull McpApplicationHandlerExecutorFactory executorFactory,
			@Nullable McpProtocolDeadlineCycle protocolDeadlineCycle) {
		this(configuration, clock, executorFactory, protocolDeadlineCycle,
				McpApplicationRequestInterceptor.passThroughInstance());
	}

	McpApplicationExecution(
			@NonNull McpApplicationExecutionConfiguration configuration,
			@NonNull McpApplicationClock clock,
			@NonNull McpApplicationHandlerExecutorFactory executorFactory,
			@Nullable McpProtocolDeadlineCycle protocolDeadlineCycle,
			@NonNull McpApplicationRequestInterceptor requestInterceptor) {
		this.configuration = requireNonNull(configuration);
		this.clock = requireNonNull(clock);
		this.protocolDeadlineCycle = protocolDeadlineCycle;
		this.requestInterceptor = requireNonNull(requestInterceptor);
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

	void dispatch(@NonNull MicrohttpRequest transportRequest,
			McpJsonRpcMessage.@NonNull Request request,
			@NonNull McpEffectiveAdmissionIdentity admissionIdentity,
			@NonNull McpApplicationRequestHandler handler,
			long deadlineNanos, @NonNull McpApplicationResponseWriter responseWriter,
			@NonNull Runnable terminalCleanup) {
		dispatchInternal(transportRequest, null, request, admissionIdentity, handler,
				deadlineNanos, responseWriter, terminalCleanup);
	}

	void dispatchWithSokletRequest(@NonNull MicrohttpRequest transportRequest,
			@NonNull Request sokletRequest,
			McpJsonRpcMessage.@NonNull Request request,
			@NonNull McpEffectiveAdmissionIdentity admissionIdentity,
			@NonNull McpApplicationRequestHandler handler,
			long deadlineNanos, @NonNull McpApplicationResponseWriter responseWriter,
			@NonNull Runnable terminalCleanup) {
		dispatchInternal(transportRequest, requireNonNull(sokletRequest), request,
				admissionIdentity, handler, deadlineNanos, responseWriter,
				terminalCleanup);
	}

	private void dispatchInternal(@NonNull MicrohttpRequest transportRequest,
			@Nullable Request sokletRequest,
			McpJsonRpcMessage.@NonNull Request request,
			@NonNull McpEffectiveAdmissionIdentity admissionIdentity,
			@NonNull McpApplicationRequestHandler handler,
			long deadlineNanos, @NonNull McpApplicationResponseWriter responseWriter,
			@NonNull Runnable terminalCleanup) {
		requireNonNull(transportRequest);
		requireNonNull(request);
		requireNonNull(admissionIdentity);
		requireNonNull(handler);
		requireNonNull(responseWriter);
		requireNonNull(terminalCleanup);

		if (stopped.get()) {
			terminalCleanup.run();
			return;
		}

		long exchangeId = exchangeSequence.incrementAndGet();
		Exchange exchange = new Exchange(exchangeId, transportRequest, sokletRequest, request,
				admissionIdentity, handler, deadlineNanos, responseWriter, terminalCleanup);

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

	void recordStreamDeadlineExpiration() {
		deadlineExpirations.incrementAndGet();
	}

	/*
	 * Linearizes a bounded protocol-state mutation with this listener
	 * generation's stop boundary. Production reservations must not invoke user
	 * code or perform blocking work while the boundary is held.
	 */
	@NonNull
	<T extends @NonNull Object> Optional<@NonNull T> reserveProtocolOperationIfRunning(
			@NonNull Supplier<@NonNull T> reservation) {
		requireNonNull(reservation);
		synchronized (executionBoundaryLock) {
			if (stopped.get())
				return Optional.empty();
			return Optional.of(requireNonNull(reservation.get()));
		}
	}

	void cancel(@NonNull MicrohttpRequest request,
			@NonNull StreamTerminationReason reason,
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

	@NonNull
	McpApplicationExecutionSnapshot snapshot() {
		return snapshot(0);
	}

	@NonNull
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

	void stop(@NonNull StreamTerminationReason reason) {
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

	boolean awaitTermination(@NonNull Duration timeout) throws InterruptedException {
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

	@NonNull
	private StreamTerminationReason stoppingReason() {
		return requireNonNull(stoppingReason.get(),
				"A stopped application execution must have a stopping reason.");
	}

	@ThreadSafe
	private final class Exchange {
		private final long exchangeId;
		private final @Nullable Request sokletRequest;
		private final McpJsonRpcMessage.@NonNull Request request;
		@NonNull
		private final McpEffectiveAdmissionIdentity admissionIdentity;
		@NonNull
		private final McpApplicationRequestHandler handler;
		private final long deadlineNanos;
		@NonNull
		private final AtomicReference<@Nullable TransportLease> transportLease;
		@NonNull
		private final Object terminalLock;
		@NonNull
		private final McpApplicationCancellationState cancellation;
		@NonNull
		private final AtomicBoolean handlerRunning;
		@NonNull
		private final AtomicBoolean handlerFinished;
		@NonNull
		private TerminalState terminalState;
		private boolean handlerEntryCommitted;
		private McpApplicationHandlerDispatcher.@Nullable Ticket ticket;

		private Exchange(long exchangeId, @NonNull MicrohttpRequest transportRequest,
				@Nullable Request sokletRequest,
				McpJsonRpcMessage.@NonNull Request request,
				@NonNull McpEffectiveAdmissionIdentity admissionIdentity,
				@NonNull McpApplicationRequestHandler handler,
				long deadlineNanos,
				@NonNull McpApplicationResponseWriter responseWriter,
				@NonNull Runnable terminalCleanup) {
			this.exchangeId = exchangeId;
			this.sokletRequest = sokletRequest;
			this.request = request;
			this.admissionIdentity = admissionIdentity;
			this.handler = handler;
			this.deadlineNanos = deadlineNanos;
			this.transportLease = new AtomicReference<>(new TransportLease(
					transportRequest, responseWriter, terminalCleanup));
			this.terminalLock = new Object();
			this.cancellation = new McpApplicationCancellationState();
			this.handlerRunning = new AtomicBoolean();
			this.handlerFinished = new AtomicBoolean();
			this.terminalState = TerminalState.OPEN;
			this.handlerEntryCommitted = false;
		}

		private void bindTicket(
				McpApplicationHandlerDispatcher.@NonNull Ticket ticket) {
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

				McpApplicationInvocation invocation = new McpApplicationInvocation(
						sokletRequest, request, admissionIdentity, cancellation,
						this::writeNotification);
				AtomicBoolean handlerInvoked = new AtomicBoolean();
				AtomicBoolean interceptorActive = new AtomicBoolean(true);
				Thread interceptorThread = Thread.currentThread();
				McpWireResult result;
				try {
					result = requestInterceptor.intercept(invocation, () -> {
						if (!interceptorActive.get())
							throw new IllegalStateException(
									"An MCP interceptor continuation cannot be invoked after interception returns.");
						if (Thread.currentThread() != interceptorThread)
							throw new IllegalStateException(
									"An MCP interceptor continuation must be invoked on the interceptor thread.");
						if (!handlerInvoked.compareAndSet(false, true))
							throw new IllegalStateException(
									"An MCP interceptor continuation may be invoked only once.");
						if (!commitDownstreamInvocation())
							throw new InterruptedException(
									"The MCP request was canceled before handler invocation.");
						return handler.handle(invocation);
					});
				} finally {
					interceptorActive.set(false);
				}
				if (result == null)
					throw new IllegalStateException(
							"An MCP application interceptor or handler returned null.");
				respond(McpApplicationResponse.success(request.id(), result));
			} catch (McpInvalidApplicationInputException exception) {
				if (!cancellation.isCancellationRequested())
					respond(McpApplicationResponse.invalidParams(request.id()));
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

		private boolean commitDownstreamInvocation() {
			if (clock.nanoTime() - deadlineNanos >= 0L) {
				onDeadline(false);
				return false;
			}

			boolean shutdown;
			synchronized (executionBoundaryLock) {
				shutdown = stopped.get();
				if (!shutdown) {
					synchronized (terminalLock) {
						if (terminalState != TerminalState.OPEN
								|| cancellation.isCancellationRequested())
							return false;
						if (handlerEntryCommitted)
							throw new IllegalStateException(
									"An MCP handler entry cannot be committed twice.");
						handlerEntryCommitted = true;
					}
				}
			}

			if (shutdown)
				cancel(stoppingReason(), null);
			return !shutdown;
		}

		private void submissionFailed(@NonNull Throwable throwable) {
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

		private boolean respond(@NonNull McpApplicationResponse response) {
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

		private boolean writeNotification(
				McpJsonRpcMessage.@NonNull Notification notification)
				throws InterruptedException {
			requireNonNull(notification);
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

			return requireNonNull(lease).responseWriter()
					.writeNotification(notification);
		}

		private void cancel(@NonNull StreamTerminationReason reason,
				@Nullable Throwable ignored) {
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

		private McpApplicationHandlerDispatcher.@NonNull Ticket ticket() {
			return requireNonNull(ticket, "Application handler ticket has not been bound.");
		}
	}
}
