/*
 * Copyright 2022-2024 Revetware LLC.
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

package com.soklet.core;

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
	 * Indicates {@link LifecycleInterceptor#willStartResponseWriting(Request, ResourceMethod, MarshaledResponse)} threw an exception.
	 */
	LIFECYCLE_INTERCEPTOR_WILL_START_RESPONSE_WRITING_FAILED,
	/**
	 * Indicates {@link LifecycleInterceptor#didFinishResponseWriting(Request, ResourceMethod, MarshaledResponse, Duration, Throwable)} threw an exception.
	 */
	LIFECYCLE_INTERCEPTOR_DID_FINISH_RESPONSE_WRITING_FAILED,
	/**
	 * Indicates {@link ResponseMarshaler#forThrowable(Request, Throwable, ResourceMethod)} threw an exception.
	 */
	RESPONSE_MARSHALER_FOR_THROWABLE_FAILED,
	/**
	 * Indicates <em>Resource Method</em> resolution via ({@link ResourceMethodResolver#resourceMethodForRequest(Request)} threw an exception.
	 */
	RESOURCE_METHOD_RESOLUTION_FAILED,
	/**
	 * Indicates an internal {@link Server} error occurred.
	 */
	SERVER_INTERNAL_ERROR,
	/**
	 * Indicates an error occurred when writing to a Server-Sent Event stream.
	 */
	SSE_EVENT_STREAM_WRITING_ERROR,
	/**
	 * Indicates an internal {@link ServerSentEventServer} error occurred.
	 */
	SSE_SERVER_INTERNAL_ERROR
}
