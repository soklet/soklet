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

/**
 * Kinds of log entries that Soklet can produce.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public enum LogEntryType {
	/**
	 * Indicates Resource Method resolution via ({@link ResourceMethodResolver#resourceMethodForRequest(Request)} threw an exception.
	 */
	RESOURCE_METHOD_RESOLUTION_FAILED,
	/**
	 * Indicates {@link LifecycleInterceptor#didStartRequestHandling(Request, ResourceMethod)} threw an exception.
	 */
	LIFECYCLE_INTERCEPTOR_DID_START_REQUEST_HANDLING_FAILED,
	/**
	 * Indicates that an exception was thrown during core request processing operations.
	 */
	REQUEST_PROCESSING_FAILED,
	/**
	 * Indicates {@link ResponseMarshaler#forThrowable(Request, Throwable, ResourceMethod)} threw an exception.
	 */
	RESPONSE_MARSHALER_FOR_THROWABLE_FAILED,
	LIFECYCLE_INTERCEPTOR_INTERCEPT_REQUEST_FAILED,
}
