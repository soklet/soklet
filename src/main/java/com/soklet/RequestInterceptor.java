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

import java.util.function.Consumer;
import java.util.function.Function;

import static java.util.Objects.requireNonNull;

/**
 * Hook methods that can adjust Soklet's request processing flow.
 * <p>
 * A standard threadsafe implementation can be acquired via {@link #defaultInstance()}.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public interface RequestInterceptor {
	/**
	 * Called before Soklet begins request processing, allowing the request to be wrapped or replaced.
	 * <p>
	 * Routing happens after this callback, so changes to the HTTP method or path affect which
	 * <em>Resource Method</em> is selected.
	 * <p>
	 * You must call {@code requestConsumer.accept(...)} exactly once before returning to advance processing.
	 * If you do not, Soklet logs the error and returns a 500 response.
	 * <p>
	 * This method <strong>is not</strong> fail-fast. If an exception occurs when Soklet invokes this method,
	 * Soklet will catch it and surface separately via {@link LifecycleObserver#didReceiveLogEvent(LogEvent)}
	 * with type {@link LogEventType#REQUEST_INTERCEPTOR_WRAP_REQUEST_FAILED}.
	 *
	 * @param serverType      the server type that received the request
	 * @param request         the request that was received
	 * @param requestConsumer receives the request to use for subsequent processing
	 */
	default void wrapRequest(@NonNull ServerType serverType,
													 @NonNull Request request,
													 @NonNull Consumer<Request> requestConsumer) {
		requireNonNull(serverType);
		requireNonNull(request);
		requireNonNull(requestConsumer);
		requestConsumer.accept(request);
	}

	/**
	 * Intercepts request processing, allowing the request to be replaced and/or the response to be transformed.
	 * <p>
	 * This method <strong>is not</strong> fail-fast. If an exception occurs when Soklet invokes this method,
	 * Soklet will catch it and surface separately via {@link LifecycleObserver#didReceiveLogEvent(LogEvent)}
	 * with type {@link LogEventType#REQUEST_INTERCEPTOR_INTERCEPT_REQUEST_FAILED}.
	 * <p>
	 * You must call {@code marshaledResponseConsumer.accept(...)} exactly once before returning to send a response.
	 * If you do not, Soklet logs the error and returns a 500 response.
	 *
	 * @param serverType                the server type that received the request
	 * @param request                   the request that was received
	 * @param resourceMethod            the <em>Resource Method</em> that will handle the request
	 * @param requestHandler            function that performs standard request handling and returns a response
	 * @param marshaledResponseConsumer receives the response to send to the client
	 */
	default void interceptRequest(@NonNull ServerType serverType,
																	@NonNull Request request,
																	@Nullable ResourceMethod resourceMethod,
																	@NonNull Function<Request, MarshaledResponse> requestHandler,
																	@NonNull Consumer<MarshaledResponse> marshaledResponseConsumer) {
		requireNonNull(serverType);
		requireNonNull(request);
		requireNonNull(requestHandler);
		requireNonNull(marshaledResponseConsumer);
		marshaledResponseConsumer.accept(requestHandler.apply(request));
	}

	/**
	 * Acquires a threadsafe {@link RequestInterceptor} instance with sensible defaults.
	 * <p>
	 * The returned instance is guaranteed to be a JVM-wide singleton.
	 *
	 * @return a {@code RequestInterceptor} with default settings
	 */
	@NonNull
	static RequestInterceptor defaultInstance() {
		return DefaultRequestInterceptor.defaultInstance();
	}
}
