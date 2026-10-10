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

import javax.annotation.concurrent.ThreadSafe;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;

/**
 * Read-only hook methods for observing system and request lifecycle events.
 * <p>
 * Lifecycle-transition callbacks ({@code willStart*}, {@code didStart*},
 * {@code didFailToStart*}, {@code willStop*}, and {@code didStop*}) are purely
 * observational. Soklet contains their failures; they cannot veto, delay, or
 * change startup, shutdown, or the published lifecycle result. Other callbacks
 * retain the inline behavior documented on their individual methods.
 * <p>
 * Transition callbacks are delivered serially by a daemon observer worker;
 * returning from shutdown or {@link SokletApplication#run(SokletConfig)} does
 * not join their delivery. They may be lost when the JVM exits. Essential
 * application cleanup belongs in bounded {@link ShutdownCleanup}, with an
 * application-owned delivery barrier if it depends on observer state.
 * <p>
 * Soklet may invoke callbacks concurrently from lifecycle, transport, and
 * request-handling threads. Implementations must therefore be thread-safe. A
 * standard implementation can be acquired via the {@link #defaultInstance()}
 * factory method.
 * <p>
 * Full documentation is available at <a href="https://www.soklet.com/docs/request-lifecycle">https://www.soklet.com/docs/request-lifecycle</a>.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public interface LifecycleObserver {
	/**
	 * Called before a {@link Soklet} instance starts.
	 * This lifecycle-transition callback is observational; exceptions are contained.
	 */
	default void willStartSoklet(@NonNull Soklet soklet) {
		// No-op by default
	}

	/**
	 * Called after a {@link Soklet} instance starts.
	 * This lifecycle-transition callback is observational; exceptions are contained.
	 */
	default void didStartSoklet(@NonNull Soklet soklet) {
		// No-op by default
	}

	/**
	 * Called after a {@link Soklet} instance was asked to start but did not reach
	 * readiness because startup failed, timed out, or was canceled. The
	 * {@code throwable} is the same cause exposed by
	 * {@link ShutdownResult#getStartupFailureCause()} and, when startup is driven
	 * directly, by {@link SokletStartupException#getCause()}. The subsequent
	 * {@link #didStopSoklet(Soklet, ShutdownResult)} callback supplies the
	 * structured {@link StartupDisposition}.
	 * This lifecycle-transition callback is observational; exceptions are contained.
	 *
	 * @param soklet Soklet whose startup did not reach readiness
	 * @param throwable failed, timed-out, or canceled startup cause
	 */
	default void didFailToStartSoklet(@NonNull Soklet soklet,
																		@NonNull Throwable throwable) {
		// No-op by default
	}

	/**
	 * Called before a {@link Soklet} instance stops.
	 * This lifecycle-transition callback is observational; exceptions are contained.
	 */
	default void willStopSoklet(@NonNull Soklet soklet) {
		// No-op by default
	}

	/**
	 * Called after a {@link Soklet} instance publishes its immutable result.
	 * This lifecycle-transition callback is observational; exceptions are contained.
	 *
	 * @param soklet stopped Soklet
	 * @param shutdownResult aggregate lifecycle result
	 */
	default void didStopSoklet(@NonNull Soklet soklet,
			@NonNull ShutdownResult shutdownResult) {
		// No-op by default
	}

	/**
	 * Called before the server starts.
	 * This lifecycle-transition callback is observational; exceptions are contained.
	 */
	default void willStartHttpServer(@NonNull HttpServer httpServer) {
		// No-op by default
	}

	/**
	 * Called after the server starts.
	 * This lifecycle-transition callback is observational; exceptions are contained.
	 */
	default void didStartHttpServer(@NonNull HttpServer httpServer) {
		// No-op by default
	}

	/**
	 * Called after an {@link HttpServer} instance was asked to start but did not
	 * reach readiness because startup failed, timed out, or was canceled. The
	 * subsequent {@link #didStopHttpServer(HttpServer, ShutdownComponentResult)}
	 * callback supplies its structured terminal evidence.
	 * This lifecycle-transition callback is observational; exceptions are contained.
	 *
	 * @param httpServer HTTP server whose startup did not reach readiness
	 * @param throwable failed, timed-out, or canceled startup cause
	 */
	default void didFailToStartHttpServer(@NonNull HttpServer httpServer,
																		@NonNull Throwable throwable) {
		// No-op by default
	}

	/**
	 * Called before the server stops.
	 * This lifecycle-transition callback is observational; exceptions are contained.
	 */
	default void willStopHttpServer(@NonNull HttpServer httpServer) {
		// No-op by default
	}

	/**
	 * Called after the server publishes terminal evidence.
	 * This lifecycle-transition callback is observational; exceptions are contained.
	 *
	 * @param httpServer stopped HTTP server
	 * @param shutdownComponentResult lifecycle component shutdown result
	 */
	default void didStopHttpServer(@NonNull HttpServer httpServer,
			@NonNull ShutdownComponentResult shutdownComponentResult) {
		// No-op by default
	}

	/**
	 * Called when a server is about to accept a new TCP connection.
	 *
	 * @param serverType    the server type that is accepting the connection
	 * @param remoteAddress the best-effort remote address, or {@code null} if unavailable
	 */
	default void willAcceptConnection(@NonNull ServerType serverType,
																		@Nullable InetSocketAddress remoteAddress) {
		// No-op by default
	}

	/**
	 * Called after a server accepts a new TCP connection.
	 *
	 * @param serverType    the server type that accepted the connection
	 * @param remoteAddress the best-effort remote address, or {@code null} if unavailable
	 */
	default void didAcceptConnection(@NonNull ServerType serverType,
																	 @Nullable InetSocketAddress remoteAddress) {
		// No-op by default
	}

	/**
	 * Called after a server fails to accept a new TCP connection.
	 *
	 * @param serverType    the server type that failed to accept the connection
	 * @param remoteAddress the best-effort remote address, or {@code null} if unavailable
	 * @param reason        the failure reason
	 * @param throwable     an optional underlying cause, or {@code null} if not applicable
	 */
	default void didFailToAcceptConnection(@NonNull ServerType serverType,
																				 @Nullable InetSocketAddress remoteAddress,
																				 @NonNull ConnectionRejectionReason reason,
																				 @Nullable Throwable throwable) {
		// No-op by default
	}

	/**
	 * Called when a request is about to be accepted for application-level handling.
	 *
	 * @param serverType    the server type that received the request
	 * @param remoteAddress the best-effort remote address, or {@code null} if unavailable
	 * @param requestTarget the raw request target (path + query) if known, or {@code null} if unavailable
	 */
	default void willAcceptRequest(@NonNull ServerType serverType,
																 @Nullable InetSocketAddress remoteAddress,
																 @Nullable String requestTarget) {
		// No-op by default
	}

	/**
	 * Called after a request is accepted for application-level handling.
	 *
	 * @param serverType    the server type that received the request
	 * @param remoteAddress the best-effort remote address, or {@code null} if unavailable
	 * @param requestTarget the raw request target (path + query) if known, or {@code null} if unavailable
	 */
	default void didAcceptRequest(@NonNull ServerType serverType,
																@Nullable InetSocketAddress remoteAddress,
																@Nullable String requestTarget) {
		// No-op by default
	}

	/**
	 * Called when a request fails to be accepted before application-level handling begins.
	 *
	 * @param serverType    the server type that received the request
	 * @param remoteAddress the best-effort remote address, or {@code null} if unavailable
	 * @param requestTarget the raw request target (path + query) if known, or {@code null} if unavailable
	 * @param reason        the rejection reason
	 * @param throwable     an optional underlying cause, or {@code null} if not applicable
	 */
	default void didFailToAcceptRequest(@NonNull ServerType serverType,
																			@Nullable InetSocketAddress remoteAddress,
																			@Nullable String requestTarget,
																			@NonNull RequestRejectionReason reason,
																			@Nullable Throwable throwable) {
		// No-op by default
	}

	/**
	 * Called when Soklet is about to read or parse a request into a valid {@link Request}.
	 *
	 * @param serverType    the server type that received the request
	 * @param remoteAddress the best-effort remote address, or {@code null} if unavailable
	 * @param requestTarget the raw request target (path + query) if known, or {@code null} if unavailable
	 */
	default void willReadRequest(@NonNull ServerType serverType,
															 @Nullable InetSocketAddress remoteAddress,
															 @Nullable String requestTarget) {
		// No-op by default
	}

	/**
	 * Called when a request was successfully read or parsed into a valid {@link Request}.
	 *
	 * @param serverType    the server type that received the request
	 * @param remoteAddress the best-effort remote address, or {@code null} if unavailable
	 * @param requestTarget the raw request target (path + query) if known, or {@code null} if unavailable
	 */
	default void didReadRequest(@NonNull ServerType serverType,
															@Nullable InetSocketAddress remoteAddress,
															@Nullable String requestTarget) {
		// No-op by default
	}

	/**
	 * Called when a request could not be read or parsed into a valid {@link Request}.
	 *
	 * @param serverType    the server type that received the request
	 * @param remoteAddress the best-effort remote address, or {@code null} if unavailable
	 * @param requestTarget the raw request target (path + query) if known, or {@code null} if unavailable
	 * @param reason        the failure reason
	 * @param throwable     an optional underlying cause, or {@code null} if not applicable
	 */
	default void didFailToReadRequest(@NonNull ServerType serverType,
																		@Nullable InetSocketAddress remoteAddress,
																		@Nullable String requestTarget,
																		@NonNull RequestReadFailureReason reason,
																		@Nullable Throwable throwable) {
		// No-op by default
	}

	/**
	 * Called when a server exposes an input rejection that occurred before a
	 * valid {@link Request} could be constructed.
	 * <p>
	 * Captured bytes are untrusted and may contain credentials or other sensitive
	 * values. Implementations should apply appropriate redaction and retention
	 * policies before logging or storing them.
	 * <p>
	 * The built-in HTTP and SSE transports use this callback for malformed
	 * requests, overlong request targets, unsupported expectations, oversized
	 * request headers, partial-request read timeouts and early aggregate-size
	 * violations. HTTP construction failures such as invalid URI encoding,
	 * unsupported content coding and malformed compressed bodies also use this
	 * callback; those later failures provide an observed wire count with an empty,
	 * truncated capture. Idle timeouts and EOF before complete headers close quietly.
	 * Admission, shutdown and broken-socket failsafes do not use this callback.
	 * <p>
	 * The transport invokes this callback at most once for a rejected request whose
	 * detail task is accepted. It dispatches the callback to the configured
	 * request-handler executor and never invokes it inline on the selector thread.
	 * The framework-managed default executor has bounded
	 * concurrency and queue capacity; a custom executor controls its own capacity.
	 * If timeout budget remains afterward, the transport invokes
	 * {@link ResponseMarshaler#forUnparsedRequest(UnparsedRequest)}. The callback
	 * should perform bounded work and must not block. The request-handler timeout
	 * bounds how long the transport waits, using the remaining budget for later
	 * HTTP validation failures and SSE rejections; cancellation interrupts the worker but
	 * is cooperative if application code ignores interruption. If application
	 * capacity is unavailable, both callbacks may be skipped and the transport
	 * writes its built-in bodyless response instead. Exceptions are contained and
	 * reported as
	 * {@link LogEventType#LIFECYCLE_OBSERVER_DID_REJECT_UNPARSED_REQUEST_FAILED};
	 * they do not alter the rejection response.
	 *
	 * @param request immutable snapshot of the rejected unparsed request
	 */
	default void didRejectUnparsedRequest(@NonNull UnparsedRequest request) {
		// No-op by default
	}

	/**
	 * Called as soon as a request is received and a <em>Resource Method</em> has been resolved to handle it.
	 *
	 * @param serverType the server type that received the request
	 */
	default void didStartRequestHandling(@NonNull ServerType serverType,
																			 @NonNull Request request,
																			 @Nullable ResourceMethod resourceMethod) {
		// No-op by default
	}

	/**
	 * Called after a request finishes processing.
	 */
	default void didFinishRequestHandling(@NonNull ServerType serverType,
																				@NonNull Request request,
																				@Nullable ResourceMethod resourceMethod,
																				@NonNull MarshaledResponse marshaledResponse,
																				@NonNull Duration duration,
																				@NonNull List<@NonNull Throwable> throwables) {
		// No-op by default
	}

	/**
	 * Called before response data is written.
	 * <p>
	 * This callback sees the logical response before transport preparation. The built-in HTTP
	 * transport may subsequently replace it (for example, an HTTP/1.0 streaming response with
	 * a finite 505 rejection). {@link #didWriteResponse} and {@link #didFinishRequestHandling}
	 * receive the replacement; the stream termination handle retains the original streaming response.
	 */
	default void willWriteResponse(@NonNull ServerType serverType,
																 @NonNull Request request,
																 @Nullable ResourceMethod resourceMethod,
																 @NonNull MarshaledResponse marshaledResponse) {
		// No-op by default
	}

	/**
	 * Called after a response is handed to the transport for writing. For an HTTP stream, this does not
	 * mean that its body has finished; use {@link #didTerminateResponseStream} for stream termination.
	 * When the built-in HTTP transport replaces a response before commitment, this callback receives
	 * the finite response actually offered, including its status and headers.
	 */
	default void didWriteResponse(@NonNull ServerType serverType,
																@NonNull Request request,
																@Nullable ResourceMethod resourceMethod,
																@NonNull MarshaledResponse marshaledResponse,
																@NonNull Duration responseWriteDuration) {
		// No-op by default
	}

	/**
	 * Called after response data fails to write.
	 */
	default void didFailToWriteResponse(@NonNull ServerType serverType,
																			@NonNull Request request,
																			@Nullable ResourceMethod resourceMethod,
																			@NonNull MarshaledResponse marshaledResponse,
																			@NonNull Duration responseWriteDuration,
																			@NonNull Throwable throwable) {
		// No-op by default
	}

	/**
	 * Called once before an admitted HTTP response stream is offered for writing and before
	 * its producer starts and before {@link #didFinishRequestHandling}. The handle retains the
	 * original dispatched {@link Request} object by identity. Preparation does
	 * not mean headers or body bytes reached the client. Finite replacements and suppressed
	 * bodies do not invoke this callback. It is paired with stream termination, including
	 * failures before production starts. Implementations must return promptly; their failures
	 * are contained and do not cancel the stream. Custom transports own the corresponding
	 * preparation and terminal notifications using the exact same handle instance and original
	 * dispatched request. Stream termination notifications may arrive before handling finish;
	 * observers that combine those events must retain the pending outcome and return without
	 * waiting. Delegating observers must forward this preparation callback and the terminal
	 * callbacks to preserve stream lifetime accounting.
	 *
	 * @param streamingResponseHandle the admitted HTTP response stream
	 */
	default void willWriteResponseStream(@NonNull StreamingResponseHandle streamingResponseHandle) {
		// No-op by default
	}

	/**
	 * Called before a streaming response termination is reported as complete.
	 * <p>
	 * This is paired with {@link #didTerminateResponseStream(StreamingResponseHandle, StreamTermination)}. Admitted HTTP stream notifications follow metrics handling finish
	 * and terminal metrics delivery, and may precede lifecycle handling finish. Earlier handling/write callbacks
	 * must return to allow this ordering to advance. For standard
	 * HTTP response streams, the two callbacks are normally invoked back-to-back because there is no broadcaster or
	 * session registry cleanup phase between them.
	 *
	 * @param streamingResponseHandle the stream that is terminating
	 * @param streamTermination       why and when the stream terminated
	 */
	default void willTerminateResponseStream(@NonNull StreamingResponseHandle streamingResponseHandle,
																					 @NonNull StreamTermination streamTermination) {
		// No-op by default
	}

	/**
	 * Called after a streaming response terminates.
	 * <p>
	 * If a stream is rejected before body bytes are written, {@link StreamingResponseHandle#getMarshaledResponse()}
	 * returns the original application-provided streaming response. For example, an HTTP/1.0 request for a streaming
	 * response is rejected on the wire with {@code 505 HTTP Version Not Supported}, while this callback still receives
	 * the original streaming response that was rejected. Admission and producer-executor rejection likewise
	 * offer one notification retaining the original stream. The finite failsafe response and synchronous
	 * request-handling finish do not wait for it. Unadmitted-stream observation uses a separate bounded
	 * allowance on the managed callback executor. Capacity exhaustion while accepting omits that
	 * observation and logs {@link LogEventType#RESPONSE_STREAM_CANCELED}; omissions during graceful
	 * drain or forced shutdown are not logged. Admitted streams retain their reserved callback jobs.
	 * <p>
	 * Admitted HTTP streaming notifications have independent worker capacity, bounded by
	 * {@link HttpServer.Builder#streamingLifecycleCapacity(Integer)}. They can run more concurrently than
	 * {@link HttpServer.Builder#streamingCallbackConcurrency(Integer)}, which bounds cancelation batches and
	 * unadmitted rejection observers. A blocked admitted observer retains its own lifetime through physical exit,
	 * while other admitted streams can report termination and retire. Cleanup diagnostics use another independently
	 * bounded executor. The built-in simulator waits for its own termination observer, without holding up other
	 * streams' observers. Implementations must support concurrent delivery and return promptly.
	 *
	 * @param streamingResponseHandle the stream that terminated
	 * @param streamTermination       why and when the stream terminated
	 */
	default void didTerminateResponseStream(@NonNull StreamingResponseHandle streamingResponseHandle,
																					@NonNull StreamTermination streamTermination) {
		// No-op by default
	}

	/**
	 * Called before the SSE server starts.
	 * This lifecycle-transition callback is observational; exceptions are contained.
	 */
	default void willStartSseServer(@NonNull SseServer sseServer) {
		// No-op by default
	}

	/**
	 * Called after the SSE server starts.
	 * This lifecycle-transition callback is observational; exceptions are contained.
	 */
	default void didStartSseServer(@NonNull SseServer sseServer) {
		// No-op by default
	}

	/**
	 * Called after an {@link SseServer} instance was asked to start but did not
	 * reach readiness because startup failed, timed out, or was canceled. The
	 * subsequent {@link #didStopSseServer(SseServer, ShutdownComponentResult)}
	 * callback supplies its structured terminal evidence.
	 * This lifecycle-transition callback is observational; exceptions are contained.
	 *
	 * @param sseServer SSE server whose startup did not reach readiness
	 * @param throwable failed, timed-out, or canceled startup cause
	 */
	default void didFailToStartSseServer(@NonNull SseServer sseServer,
																									 @NonNull Throwable throwable) {
		// No-op by default
	}

	/**
	 * Called before the SSE server stops.
	 * This lifecycle-transition callback is observational; exceptions are contained.
	 */
	default void willStopSseServer(@NonNull SseServer sseServer) {
		// No-op by default
	}

	/**
	 * Called after the SSE server publishes terminal evidence.
	 * This lifecycle-transition callback is observational; exceptions are contained.
	 *
	 * @param sseServer stopped SSE server
	 * @param shutdownComponentResult lifecycle component shutdown result
	 */
	default void didStopSseServer(@NonNull SseServer sseServer,
			@NonNull ShutdownComponentResult shutdownComponentResult) {
		// No-op by default
	}

	/**
	 * Called before the MCP server starts.
	 * This lifecycle-transition callback is observational; exceptions are contained.
	 *
	 * @param mcpServer the MCP server that will start
	 */
	default void willStartMcpServer(@NonNull McpServer mcpServer) {
		// No-op by default
	}

	/**
	 * Called after the MCP server starts.
	 * This lifecycle-transition callback is observational; exceptions are contained.
	 *
	 * @param mcpServer the MCP server that started
	 */
	default void didStartMcpServer(@NonNull McpServer mcpServer) {
		// No-op by default
	}

	/**
	 * Called after an {@link McpServer} instance was asked to start but did not
	 * reach readiness because startup failed, timed out, or was canceled. The
	 * subsequent {@link #didStopMcpServer(McpServer, ShutdownComponentResult)}
	 * callback supplies its structured terminal evidence.
	 * This lifecycle-transition callback is observational; exceptions are contained.
	 *
	 * @param mcpServer MCP server whose startup did not reach readiness
	 * @param throwable failed, timed-out, or canceled startup cause
	 */
	default void didFailToStartMcpServer(@NonNull McpServer mcpServer,
																		@NonNull Throwable throwable) {
		// No-op by default
	}

	/**
	 * Called before the MCP server stops.
	 * This lifecycle-transition callback is observational; exceptions are contained.
	 *
	 * @param mcpServer the MCP server that will stop
	 */
	default void willStopMcpServer(@NonNull McpServer mcpServer) {
		// No-op by default
	}

	/**
	 * Called after the MCP server stops.
	 * This lifecycle-transition callback is observational; exceptions are contained.
	 *
	 * @param mcpServer       the MCP server that stopped
	 * @param shutdownComponentResult lifecycle component shutdown result
	 */
	default void didStopMcpServer(@NonNull McpServer mcpServer,
			@NonNull ShutdownComponentResult shutdownComponentResult) {
		// No-op by default
	}

	/**
	 * Called when handling begins for an admitted semantic MCP request or
	 * notification.
	 * <p>
	 * This callback runs after request admission and before request or tool rate
	 * limiting, handler queue admission, framework response generation,
	 * application interception, typed-input validation, or handler entry.
	 * Framework-owned discovery and static catalog operations also invoke this
	 * callback. Soklet may invoke this callback concurrently for independent MCP
	 * requests. Implementations must be safe for the server's configured request
	 * concurrency, and the corresponding finish callback is not guaranteed to run
	 * on the same thread. Exceptions are contained and do not alter the wire
	 * result.
	 *
	 * @param requestContext immutable admitted-request context
	 */
	default void didStartMcpRequestHandling(@NonNull McpRequestContext requestContext) {
		// No-op by default
	}

	/**
	 * Called exactly once when an admitted semantic MCP request or notification
	 * reaches its client-visible terminal outcome.
	 * <p>
	 * This is a request-finish callback, not a handler-exit callback: application
	 * code that does not cooperate with cancelation may continue after this
	 * method runs. The supplied context is the same instance passed to
	 * {@link #didStartMcpRequestHandling(McpRequestContext)}. Soklet may invoke
	 * this callback concurrently for independent MCP requests, and it is not
	 * guaranteed to run on the same thread as the corresponding start callback.
	 * Implementations must be safe for the server's configured request
	 * concurrency. Exceptions are contained and do not alter the wire result.
	 *
	 * @param requestContext immutable admitted-request context
	 * @param requestOutcome fixed client-visible terminal outcome
	 * @param jsonRpcError   exact client-visible JSON-RPC error, or {@code null} when
	 *                   the terminal outcome has no JSON-RPC error
	 * @param requestDuration total admitted-request duration
	 * @param throwables immutable failures observed while handling the request
	 */
	default void didFinishMcpRequestHandling(@NonNull McpRequestContext requestContext,
			@NonNull McpRequestOutcome requestOutcome,
			@Nullable McpJsonRpcError jsonRpcError,
			@NonNull Duration requestDuration,
			@NonNull List<@NonNull Throwable> throwables) {
		// No-op by default
	}

	/**
	 * Called before an SSE connection is established.
	 */
	default void willEstablishSseConnection(@NonNull Request request,
																											@Nullable ResourceMethod resourceMethod) {
		// No-op by default
	}

	/**
	 * Called after an SSE connection is established.
	 * <p>
	 * If client initialization fails after the accepted response has been written, the built-in SSE server
	 * invokes this callback immediately before the paired termination callbacks. Such a connection never
	 * becomes available to a broadcaster. The termination retains its elected reason and cause.
	 */
	default void didEstablishSseConnection(@NonNull SseConnection sseConnection) {
		// No-op by default
	}

	/**
	 * Called if an SSE connection fails to establish before an accepted response has been written.
	 * <p>
	 * Capacity rejection is {@link SseConnection.HandshakeFailureReason#CAPACITY_EXCEEDED}; request processing,
	 * response preparation and response writing failures use {@link SseConnection.HandshakeFailureReason#INTERNAL_ERROR}
	 * with their cause. {@link SseConnection.HandshakeFailureReason#HANDSHAKE_REJECTED}
	 * also covers finite route/method mismatch responses on the SSE listener,
	 * including HEAD and OPTIONS; an event-source method need not have run.
	 * Failure during client initialization after acceptance uses the stream-termination callbacks.
	 *
	 * @param connectionHandshakeFailureReason    the handshake failure reason
	 * @param throwable an optional underlying cause, or {@code null} if not applicable
	 */
	default void didFailToEstablishSseConnection(@NonNull Request request,
																													 @Nullable ResourceMethod resourceMethod,
																													 SseConnection.@NonNull HandshakeFailureReason connectionHandshakeFailureReason,
																													 @Nullable Throwable throwable) {
		// No-op by default
	}

	/**
	 * Called before an SSE connection is terminated.
	 */
	default void willTerminateSseConnection(@NonNull SseConnection sseConnection,
																											@NonNull StreamTermination streamTermination) {
		// No-op by default
	}

	/**
	 * Called after an SSE connection is terminated.
	 */
	default void didTerminateSseConnection(@NonNull SseConnection sseConnection,
																										 @NonNull StreamTermination streamTermination) {
		// No-op by default
	}

	/**
	 * Called before an SSE event is written.
	 */
	default void willWriteSseEvent(@NonNull SseConnection sseConnection,
																				@NonNull SseEvent sseEvent) {
		// No-op by default
	}

	/**
	 * Called after an SSE event is written.
	 */
	default void didWriteSseEvent(@NonNull SseConnection sseConnection,
																			 @NonNull SseEvent sseEvent,
																			 @NonNull Duration writeDuration) {
		// No-op by default
	}

	/**
	 * Called after an SSE event fails to write.
	 */
	default void didFailToWriteSseEvent(@NonNull SseConnection sseConnection,
																						 @NonNull SseEvent sseEvent,
																						 @NonNull Duration writeDuration,
																						 @NonNull Throwable throwable) {
		// No-op by default
	}

	/**
	 * Called before an SSE comment is written.
	 */
	default void willWriteSseComment(@NonNull SseConnection sseConnection,
																							 @NonNull SseComment sseComment) {
		// No-op by default
	}

	/**
	 * Called after an SSE comment is written.
	 */
	default void didWriteSseComment(@NonNull SseConnection sseConnection,
																							@NonNull SseComment sseComment,
																							@NonNull Duration writeDuration) {
		// No-op by default
	}

	/**
	 * Called after an SSE comment fails to write.
	 */
	default void didFailToWriteSseComment(@NonNull SseConnection sseConnection,
																										@NonNull SseComment sseComment,
																										@NonNull Duration writeDuration,
																										@NonNull Throwable throwable) {
		// No-op by default
	}

	/**
	 * Called when Soklet emits a log event.
	 * The interface default is a no-op. Override this method to route events
	 * through application logging, or explicitly configure
	 * {@link #defaultInstance()} for stderr logging. Custom observers do not
	 * inherit an implicit stderr logger.
	 */
	default void didReceiveLogEvent(@NonNull LogEvent logEvent) {
		// No-op by default
	}

	/**
	 * Acquires a threadsafe {@link LifecycleObserver} instance with sensible defaults.
	 * This instance writes log events and attached Throwable stack traces to
	 * stderr. {@link SokletConfig} selects it when no lifecycle-observer
	 * configuration is supplied. Configuring custom observers replaces that
	 * default; include this instance explicitly to retain stderr logging.
	 *
	 * @return a {@code LifecycleObserver} with default settings
	 */
	@NonNull
	static LifecycleObserver defaultInstance() {
		return DefaultLifecycleObserver.defaultInstance();
	}
}
