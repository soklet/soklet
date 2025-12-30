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
	 * Indicates {@link RequestInterceptor#wrapRequest(Request, Consumer)} threw an exception.
	 */
	REQUEST_INTERCEPTOR_WRAP_REQUEST_FAILED,
	/**
	 * Indicates {@link RequestInterceptor#interceptRequest(Request, ResourceMethod, Function, Consumer)} threw an exception.
	 */
	REQUEST_INTERCEPTOR_INTERCEPT_REQUEST_FAILED,
	/**
	 * Indicates {@link LifecycleObserver#didStartRequestHandling(Request, ResourceMethod)} threw an exception.
	 */
	LIFECYCLE_OBSERVER_DID_START_REQUEST_HANDLING_FAILED,
	/**
	 * Indicates {@link LifecycleObserver#didFinishRequestHandling(Request, ResourceMethod, MarshaledResponse, Duration, List)} threw an exception.
	 */
	LIFECYCLE_OBSERVER_DID_FINISH_REQUEST_HANDLING_FAILED,
	/**
	 * Indicates {@link LifecycleObserver#willWriteResponse(Request, ResourceMethod, MarshaledResponse)}  threw an exception.
	 */
	LIFECYCLE_OBSERVER_WILL_WRITE_RESPONSE_FAILED,
	/**
	 * Indicates {@link LifecycleObserver#didWriteResponse(Request, ResourceMethod, MarshaledResponse, Duration)}  or {@link LifecycleObserver#didFailToWriteResponse(Request, ResourceMethod, MarshaledResponse, Duration, Throwable)} threw an exception.
	 */
	LIFECYCLE_OBSERVER_DID_WRITE_RESPONSE_FAILED,
	/**
	 * Indicates {@link LifecycleObserver#willEstablishServerSentEventConnection(Request, ResourceMethod)} threw an exception.
	 */
	LIFECYCLE_OBSERVER_WILL_ESTABLISH_SERVER_SENT_EVENT_CONNECTION_FAILED,
	/**
	 * Indicates {@link LifecycleObserver#didEstablishServerSentEventConnection(ServerSentEventConnection)} or {@link LifecycleObserver#didFailToEstablishServerSentEventConnection(Request, ResourceMethod, Throwable)} threw an exception.
	 */
	LIFECYCLE_OBSERVER_DID_ESTABLISH_SERVER_SENT_EVENT_CONNECTION_FAILED,
	/**
	 * Indicates {@link LifecycleObserver#willTerminateServerSentEventConnection(ServerSentEventConnection, ServerSentEventConnection.TerminationReason, Throwable)} threw an exception.
	 */
	LIFECYCLE_OBSERVER_WILL_TERMINATE_SERVER_SENT_EVENT_CONNECTION_FAILED,
	/**
	 * Indicates {@link LifecycleObserver#didTerminateServerSentEventConnection(ServerSentEventConnection, Duration, ServerSentEventConnection.TerminationReason, Throwable)} threw an exception.
	 */
	LIFECYCLE_OBSERVER_DID_TERMINATE_SERVER_SENT_EVENT_CONNECTION_FAILED,
	/**
	 * Indicates {@link LifecycleObserver#willWriteServerSentEvent(ServerSentEventConnection, ServerSentEvent)} threw an exception.
	 */
	LIFECYCLE_OBSERVER_WILL_WRITE_SERVER_SENT_EVENT_FAILED,
	/**
	 * Indicates {@link LifecycleObserver#didWriteServerSentEvent(ServerSentEventConnection, ServerSentEvent, Duration)} or {@link LifecycleObserver#didFailToWriteServerSentEvent(ServerSentEventConnection, ServerSentEvent, Duration, Throwable)} threw an exception.
	 */
	LIFECYCLE_OBSERVER_DID_WRITE_SERVER_SENT_EVENT_FAILED,
	/**
	 * Indicates {@link LifecycleObserver#willWriteServerSentEventComment(ServerSentEventConnection, ServerSentEventComment)} threw an exception.
	 */
	LIFECYCLE_OBSERVER_WILL_WRITE_SERVER_SENT_EVENT_COMMENT_FAILED,
	/**
	 * Indicates {@link LifecycleObserver#didWriteServerSentEventComment(ServerSentEventConnection, ServerSentEventComment, Duration)} or {@link LifecycleObserver#didFailToWriteServerSentEventComment(ServerSentEventConnection, ServerSentEventComment, Duration, Throwable)} threw an exception.
	 */
	LIFECYCLE_OBSERVER_DID_WRITE_SERVER_SENT_EVENT_COMMENT_FAILED,
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
	 * Indicates that the {@link Server} received a request with an illegal structure, such as a missing or invalid HTTP verb or an unsupported HTTP/2.0 request.
	 */
	SERVER_UNPARSEABLE_REQUEST,
	/**
	 * Indicates an internal {@link Server} error occurred.
	 */
	SERVER_INTERNAL_ERROR,
	/**
	 * Indicates that the {@link ServerSentEventServer} received a request with an illegal structure, such as a missing or invalid HTTP verb or an unsupported HTTP/2.0 request.
	 */
	SERVER_SENT_EVENT_SERVER_UNPARSEABLE_REQUEST,
	/**
	 * Indicates that the {@link ServerSentEventServer} was unable to successfully write a handshake response.
	 */
	SERVER_SENT_EVENT_SERVER_WRITING_HANDSHAKE_RESPONSE_FAILED,
	/**
	 * Indicates that the {@link ServerSentEventServer} encountered an error when executing application-provided code while performing a memoized broadcast via {@link ServerSentEventBroadcaster#broadcastEvent(Function, Function)} or {@link ServerSentEventBroadcaster#broadcastComment(Function, Function)}.
	 */
	SERVER_SENT_EVENT_SERVER_BROADCAST_GENERATION_FAILED,
	/**
	 * Indicates that the {@link ServerSentEventServer} rejected a connection, e.g. due to capacity limits.
	 */
	SERVER_SENT_EVENT_SERVER_CONNECTION_REJECTED,
	/**
	 * Indicates an internal {@link ServerSentEventServer} error occurred.
	 */
	SERVER_SENT_EVENT_SERVER_INTERNAL_ERROR
}
