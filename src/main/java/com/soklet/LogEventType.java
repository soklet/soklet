/*
 * Copyright 2022-2025 Revetware LLC.
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
	 * Indicates {@link LifecycleInterceptor#wrapRequest(Request, ResourceMethod, Consumer)} threw an exception.
	 */
	LIFECYCLE_INTERCEPTOR_WRAP_REQUEST_FAILED,
	/**
	 * Indicates {@link LifecycleInterceptor#interceptRequest(Request, ResourceMethod, Function, Consumer)} threw an exception.
	 */
	LIFECYCLE_INTERCEPTOR_INTERCEPT_REQUEST_FAILED,
	/**
	 * Indicates {@link LifecycleInterceptor#didStartRequestHandling(Request, ResourceMethod)} threw an exception.
	 */
	LIFECYCLE_INTERCEPTOR_DID_START_REQUEST_HANDLING_FAILED,
	/**
	 * Indicates {@link LifecycleInterceptor#didFinishRequestHandling(Request, ResourceMethod, MarshaledResponse, Duration, List)} threw an exception.
	 */
	LIFECYCLE_INTERCEPTOR_DID_FINISH_REQUEST_HANDLING_FAILED,
	/**
	 * Indicates {@link LifecycleInterceptor#willWriteResponse(Request, ResourceMethod, MarshaledResponse)}  threw an exception.
	 */
	LIFECYCLE_INTERCEPTOR_WILL_WRITE_RESPONSE_FAILED,
	/**
	 * Indicates {@link LifecycleInterceptor#didWriteResponse(Request, ResourceMethod, MarshaledResponse, Duration)}  or {@link LifecycleInterceptor#didFailToWriteResponse(Request, ResourceMethod, MarshaledResponse, Duration, Throwable)} threw an exception.
	 */
	LIFECYCLE_INTERCEPTOR_DID_WRITE_RESPONSE_FAILED,
	/**
	 * Indicates {@link LifecycleInterceptor#willEstablishServerSentEventConnection(Request, ResourceMethod)} threw an exception.
	 */
	LIFECYCLE_INTERCEPTOR_WILL_ESTABLISH_SERVER_SENT_EVENT_CONNECTION_FAILED,
	/**
	 * Indicates {@link LifecycleInterceptor#didEstablishServerSentEventConnection(Request, ResourceMethod)} or {@link LifecycleInterceptor#didFailToEstablishServerSentEventConnection(Request, ResourceMethod, Throwable)} threw an exception.
	 */
	LIFECYCLE_INTERCEPTOR_DID_ESTABLISH_SERVER_SENT_EVENT_CONNECTION_FAILED,
	/**
	 * Indicates {@link LifecycleInterceptor#willTerminateServerSentEventConnection(Request, ResourceMethod, Throwable)} threw an exception.
	 */
	LIFECYCLE_INTERCEPTOR_WILL_TERMINATE_SERVER_SENT_EVENT_CONNECTION_FAILED,
	/**
	 * Indicates {@link LifecycleInterceptor#didTerminateServerSentEventConnection(Request, ResourceMethod, Duration, Throwable)} threw an exception.
	 */
	LIFECYCLE_INTERCEPTOR_DID_TERMINATE_SERVER_SENT_EVENT_CONNECTION_FAILED,
	/**
	 * Indicates {@link LifecycleInterceptor#willWriteServerSentEvent(Request, ResourceMethod, ServerSentEvent)} threw an exception.
	 */
	LIFECYCLE_INTERCEPTOR_WILL_WRITE_SERVER_SENT_EVENT_FAILED,
	/**
	 * Indicates {@link LifecycleInterceptor#didWriteServerSentEvent(Request, ResourceMethod, ServerSentEvent, Duration)} or {@link LifecycleInterceptor#didFailToWriteServerSentEvent(Request, ResourceMethod, ServerSentEvent, Duration, Throwable)} threw an exception.
	 */
	LIFECYCLE_INTERCEPTOR_DID_WRITE_SERVER_SENT_EVENT_FAILED,
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
	 * Indicates that the {@link ServerSentEventServer} received a handshake request with an illegal structure, such as a missing or invalid HTTP verb or an unsupported HTTP/2.0 request.
	 */
	SERVER_SENT_EVENT_SERVER_UNPARSEABLE_HANDSHAKE_REQUEST,
	/**
	 * Indicates that the {@link ServerSentEventServer} was unable to successfully write a handshake response.
	 */
	SERVER_SENT_EVENT_SERVER_FAILED_WRITING_HANDSHAKE_RESPONSE,
	/**
	 * Indicates an internal {@link ServerSentEventServer} error occurred.
	 */
	SERVER_SENT_EVENT_SERVER_INTERNAL_ERROR
}
