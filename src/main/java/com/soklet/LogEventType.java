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

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Kinds of {@link LogEvent} instances that Soklet can produce.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public enum LogEventType {
	/**
	 * Indicates that a Soklet configuration option was requested but isn't supported in the current runtime/environment; behavior may differ (perhaps ignored or degraded).
	 */
	CONFIGURATION_UNSUPPORTED,
	/**
	 * Indicates that an exception was thrown during core request processing operations.
	 */
	REQUEST_PROCESSING_FAILED,
	/**
	 * Indicates {@link RequestInterceptor#wrapRequest(ServerType, Request, Consumer)} threw an exception.
	 */
	REQUEST_INTERCEPTOR_WRAP_REQUEST_FAILED,
	/**
	 * Indicates {@link RequestInterceptor#interceptRequest(ServerType, Request, ResourceMethod, Function, Consumer)} threw an exception.
	 */
	REQUEST_INTERCEPTOR_INTERCEPT_REQUEST_FAILED,
	/**
	 * Indicates {@link LifecycleObserver#willAcceptConnection(ServerType, java.net.InetSocketAddress)} threw an exception.
	 */
	LIFECYCLE_OBSERVER_WILL_ACCEPT_CONNECTION_FAILED,
	/**
	 * Indicates {@link LifecycleObserver#didAcceptConnection(ServerType, java.net.InetSocketAddress)} threw an exception.
	 */
	LIFECYCLE_OBSERVER_DID_ACCEPT_CONNECTION_FAILED,
	/**
	 * Indicates {@link LifecycleObserver#didFailToAcceptConnection(ServerType, java.net.InetSocketAddress, ConnectionRejectionReason, Throwable)} threw an exception.
	 */
	LIFECYCLE_OBSERVER_DID_FAIL_TO_ACCEPT_CONNECTION_FAILED,
	/**
	 * Indicates {@link LifecycleObserver#willAcceptRequest(ServerType, java.net.InetSocketAddress, String)} threw an exception.
	 */
	LIFECYCLE_OBSERVER_WILL_ACCEPT_REQUEST_FAILED,
	/**
	 * Indicates {@link LifecycleObserver#didAcceptRequest(ServerType, java.net.InetSocketAddress, String)} threw an exception.
	 */
	LIFECYCLE_OBSERVER_DID_ACCEPT_REQUEST_FAILED,
	/**
	 * Indicates {@link LifecycleObserver#didFailToAcceptRequest(ServerType, java.net.InetSocketAddress, String, RequestRejectionReason, Throwable)} threw an exception.
	 */
	LIFECYCLE_OBSERVER_DID_FAIL_TO_ACCEPT_REQUEST_FAILED,
	/**
	 * Indicates {@link LifecycleObserver#willReadRequest(ServerType, java.net.InetSocketAddress, String)} threw an exception.
	 */
	LIFECYCLE_OBSERVER_WILL_READ_REQUEST_FAILED,
	/**
	 * Indicates {@link LifecycleObserver#didReadRequest(ServerType, java.net.InetSocketAddress, String)} threw an exception.
	 */
	LIFECYCLE_OBSERVER_DID_READ_REQUEST_FAILED,
	/**
	 * Indicates {@link LifecycleObserver#didFailToReadRequest(ServerType, java.net.InetSocketAddress, String, RequestReadFailureReason, Throwable)} threw an exception.
	 */
	LIFECYCLE_OBSERVER_DID_FAIL_TO_READ_REQUEST_FAILED,
	/**
	 * Indicates {@link LifecycleObserver#didStartRequestHandling(ServerType, Request, ResourceMethod)} threw an exception.
	 */
	LIFECYCLE_OBSERVER_DID_START_REQUEST_HANDLING_FAILED,
	/**
	 * Indicates {@link LifecycleObserver#didFinishRequestHandling(ServerType, Request, ResourceMethod, MarshaledResponse, Duration, List)} threw an exception.
	 */
	LIFECYCLE_OBSERVER_DID_FINISH_REQUEST_HANDLING_FAILED,
	/**
	 * Indicates {@link LifecycleObserver#willWriteResponse(ServerType, Request, ResourceMethod, MarshaledResponse)}  threw an exception.
	 */
	LIFECYCLE_OBSERVER_WILL_WRITE_RESPONSE_FAILED,
	/**
	 * Indicates {@link LifecycleObserver#didWriteResponse(ServerType, Request, ResourceMethod, MarshaledResponse, Duration)}  or {@link LifecycleObserver#didFailToWriteResponse(ServerType, Request, ResourceMethod, MarshaledResponse, Duration, Throwable)} threw an exception.
	 */
	LIFECYCLE_OBSERVER_DID_WRITE_RESPONSE_FAILED,
	/**
	 * Indicates {@link LifecycleObserver#didTerminateResponseStream(ServerType, Request, ResourceMethod, MarshaledResponse, Duration, StreamingResponseCancelationReason, Throwable)} threw an exception.
	 */
	LIFECYCLE_OBSERVER_DID_TERMINATE_RESPONSE_STREAM_FAILED,
	/**
	 * Indicates {@link LifecycleObserver#didCreateMcpSession(Request, Class, String)} threw an exception.
	 */
	LIFECYCLE_OBSERVER_DID_CREATE_MCP_SESSION_FAILED,
	/**
	 * Indicates {@link LifecycleObserver#didTerminateMcpSession(Class, String, Duration, McpSessionTerminationReason, Throwable)} threw an exception.
	 */
	LIFECYCLE_OBSERVER_DID_TERMINATE_MCP_SESSION_FAILED,
	/**
	 * Indicates {@link LifecycleObserver#didStartMcpRequestHandling(Request, Class, String, String, McpJsonRpcRequestId)} threw an exception.
	 */
	LIFECYCLE_OBSERVER_DID_START_MCP_REQUEST_HANDLING_FAILED,
	/**
	 * Indicates {@link LifecycleObserver#didFinishMcpRequestHandling(Request, Class, String, String, McpJsonRpcRequestId, McpRequestOutcome, McpJsonRpcError, Duration, List)} threw an exception.
	 */
	LIFECYCLE_OBSERVER_DID_FINISH_MCP_REQUEST_HANDLING_FAILED,
	/**
	 * Indicates {@link LifecycleObserver#didEstablishMcpSseStream(Request, Class, String)} threw an exception.
	 */
	LIFECYCLE_OBSERVER_DID_ESTABLISH_MCP_SSE_STREAM_FAILED,
	/**
	 * Indicates {@link LifecycleObserver#willTerminateMcpSseStream(Request, Class, String, McpStreamTerminationReason, Throwable)} threw an exception.
	 */
	LIFECYCLE_OBSERVER_WILL_TERMINATE_MCP_SSE_STREAM_FAILED,
	/**
	 * Indicates {@link LifecycleObserver#didTerminateMcpSseStream(Request, Class, String, Duration, McpStreamTerminationReason, Throwable)} threw an exception.
	 */
	LIFECYCLE_OBSERVER_DID_TERMINATE_MCP_SSE_STREAM_FAILED,
	/**
	 * Indicates {@link LifecycleObserver#willEstablishSseConnection(Request, ResourceMethod)} threw an exception.
	 */
	LIFECYCLE_OBSERVER_WILL_ESTABLISH_SSE_CONNECTION_FAILED,
	/**
	 * Indicates {@link LifecycleObserver#didEstablishSseConnection(SseConnection)} or {@link LifecycleObserver#didFailToEstablishSseConnection(Request, ResourceMethod, SseConnection.HandshakeFailureReason, Throwable)} threw an exception.
	 */
	LIFECYCLE_OBSERVER_DID_ESTABLISH_SSE_CONNECTION_FAILED,
	/**
	 * Indicates {@link LifecycleObserver#willTerminateSseConnection(SseConnection, SseConnection.TerminationReason, Throwable)} threw an exception.
	 */
	LIFECYCLE_OBSERVER_WILL_TERMINATE_SSE_CONNECTION_FAILED,
	/**
	 * Indicates {@link LifecycleObserver#didTerminateSseConnection(SseConnection, Duration, SseConnection.TerminationReason, Throwable)} threw an exception.
	 */
	LIFECYCLE_OBSERVER_DID_TERMINATE_SSE_CONNECTION_FAILED,
	/**
	 * Indicates {@link LifecycleObserver#willWriteSseEvent(SseConnection, SseEvent)} threw an exception.
	 */
	LIFECYCLE_OBSERVER_WILL_WRITE_SSE_FAILED,
	/**
	 * Indicates {@link LifecycleObserver#didWriteSseEvent(SseConnection, SseEvent, Duration)} or {@link LifecycleObserver#didFailToWriteSseEvent(SseConnection, SseEvent, Duration, Throwable)} threw an exception.
	 */
	LIFECYCLE_OBSERVER_DID_WRITE_SSE_FAILED,
	/**
	 * Indicates {@link LifecycleObserver#willWriteSseComment(SseConnection, SseComment)} threw an exception.
	 */
	LIFECYCLE_OBSERVER_WILL_WRITE_SSE_COMMENT_FAILED,
	/**
	 * Indicates {@link LifecycleObserver#didWriteSseComment(SseConnection, SseComment, Duration)} or {@link LifecycleObserver#didFailToWriteSseComment(SseConnection, SseComment, Duration, Throwable)} threw an exception.
	 */
	LIFECYCLE_OBSERVER_DID_WRITE_SSE_COMMENT_FAILED,
	/**
	 * Indicates a {@link MetricsCollector} invocation threw an exception.
	 */
	METRICS_COLLECTOR_FAILED,
	/**
	 * Indicates {@link ResponseMarshaler#forThrowable(Request, Throwable, ResourceMethod)} threw an exception.
	 */
	RESPONSE_MARSHALER_FOR_THROWABLE_FAILED,
	/**
	 * Indicates <em>Resource Method</em> resolution via ({@link ResourceMethodResolver#resourceMethodForRequest(Request, ServerType)} threw an exception.
	 */
	RESOURCE_METHOD_RESOLUTION_FAILED,
	/**
	 * Indicates that the {@link HttpServer} received a request with an illegal structure, such as a missing or invalid HTTP verb or an unsupported HTTP/2.0 request.
	 */
	SERVER_UNPARSEABLE_REQUEST,
	/**
	 * Indicates a response stream failed while producing or writing bytes.
	 */
	RESPONSE_STREAM_FAILED,
	/**
	 * Indicates a response stream was canceled.
	 */
	RESPONSE_STREAM_CANCELED,
	/**
	 * Indicates a response stream failed while closing.
	 */
	RESPONSE_STREAM_CLOSE_FAILED,
	/**
	 * Indicates a response stream cancelation callback threw an exception.
	 */
	RESPONSE_STREAM_CANCELATION_CALLBACK_FAILED,
	/**
	 * Indicates an internal {@link HttpServer} error occurred.
	 */
	SERVER_INTERNAL_ERROR,
	/**
	 * Indicates that the {@link SseServer} received a request with an illegal structure, such as a missing or invalid HTTP verb or an unsupported HTTP/2.0 request.
	 */
	SSE_SERVER_UNPARSEABLE_REQUEST,
	/**
	 * Indicates that the {@link SseServer} was unable to successfully write a handshake response.
	 */
	SSE_SERVER_WRITING_HANDSHAKE_RESPONSE_FAILED,
	/**
	 * Indicates that the {@link SseServer} encountered an error when executing application-provided code while performing a memoized broadcast via {@link SseBroadcaster#broadcastEvent(Function, Function)} or {@link SseBroadcaster#broadcastComment(Function, Function)}.
	 */
	SSE_SERVER_BROADCAST_GENERATION_FAILED,
	/**
	 * Indicates that the {@link SseServer} rejected a connection, e.g. due to capacity limits.
	 */
	SSE_SERVER_CONNECTION_REJECTED,
	/**
	 * Indicates an internal {@link SseServer} error occurred.
	 */
	SSE_SERVER_INTERNAL_ERROR
}
