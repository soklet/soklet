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

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.util.List;

/**
 * Read-only hook methods for observing system and request lifecycle events.
 * <p>
 * Note: some of these methods are "fail-fast" - exceptions thrown will bubble out and stop execution - and for others,
 * Soklet will catch exceptions and surface separately via {@link #didReceiveLogEvent(LogEvent)}.
 * <p>
 * A standard threadsafe implementation can be acquired via the {@link #defaultInstance()} factory method.
 * <p>
 * Full documentation is available at <a href="https://www.soklet.com/docs/request-lifecycle">https://www.soklet.com/docs/request-lifecycle</a>.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public interface LifecycleObserver {
	/**
	 * Called before a {@link Soklet} instance starts.
	 */
	default void willStartSoklet(@NonNull Soklet soklet) {
		// No-op by default
	}

	/**
	 * Called after a {@link Soklet} instance starts.
	 */
	default void didStartSoklet(@NonNull Soklet soklet) {
		// No-op by default
	}

	/**
	 * Called after a {@link Soklet} instance was asked to start, but failed due to an exception.
	 */
	default void didFailToStartSoklet(@NonNull Soklet soklet,
																		@NonNull Throwable throwable) {
		// No-op by default
	}

	/**
	 * Called before a {@link Soklet} instance stops.
	 */
	default void willStopSoklet(@NonNull Soklet soklet) {
		// No-op by default
	}

	/**
	 * Called after a {@link Soklet} instance stops.
	 */
	default void didStopSoklet(@NonNull Soklet soklet) {
		// No-op by default
	}

	/**
	 * Called after a {@link Soklet} instance was asked to stop, but failed due to an exception.
	 */
	default void didFailToStopSoklet(@NonNull Soklet soklet,
																	 @NonNull Throwable throwable) {
		// No-op by default
	}

	/**
	 * Called before the server starts.
	 */
	default void willStartServer(@NonNull Server server) {
		// No-op by default
	}

	/**
	 * Called after the server starts.
	 */
	default void didStartServer(@NonNull Server server) {
		// No-op by default
	}

	/**
	 * Called after a {@link Server} instance was asked to start, but failed due to an exception.
	 */
	default void didFailToStartServer(@NonNull Server server,
																		@NonNull Throwable throwable) {
		// No-op by default
	}

	/**
	 * Called before the server stops.
	 */
	default void willStopServer(@NonNull Server server) {
		// No-op by default
	}

	/**
	 * Called after the server stops.
	 */
	default void didStopServer(@NonNull Server server) {
		// No-op by default
	}

	/**
	 * Called after a {@link Server} instance was asked to stop, but failed due to an exception.
	 */
	default void didFailToStopServer(@NonNull Server server,
																	 @NonNull Throwable throwable) {
		// No-op by default
	}

	/**
	 * Called as soon as a request is received and a <em>Resource Method</em> has been resolved to handle it.
	 */
	default void didStartRequestHandling(@NonNull Request request,
																			 @Nullable ResourceMethod resourceMethod) {
		// No-op by default
	}

	/**
	 * Called after a request finishes processing.
	 */
	default void didFinishRequestHandling(@NonNull Request request,
																				@Nullable ResourceMethod resourceMethod,
																				@NonNull MarshaledResponse marshaledResponse,
																				@NonNull Duration duration,
																				@NonNull List<@NonNull Throwable> throwables) {
		// No-op by default
	}

	/**
	 * Called before response data is written.
	 */
	default void willWriteResponse(@NonNull Request request,
															 @Nullable ResourceMethod resourceMethod,
															 @NonNull MarshaledResponse marshaledResponse) {
		// No-op by default
	}

	/**
	 * Called after response data is written.
	 */
	default void didWriteResponse(@NonNull Request request,
															@Nullable ResourceMethod resourceMethod,
															@NonNull MarshaledResponse marshaledResponse,
															@NonNull Duration responseWriteDuration) {
		// No-op by default
	}

	/**
	 * Called after response data fails to write.
	 */
	default void didFailToWriteResponse(@NonNull Request request,
																			@Nullable ResourceMethod resourceMethod,
																			@NonNull MarshaledResponse marshaledResponse,
																			@NonNull Duration responseWriteDuration,
																			@NonNull Throwable throwable) {
		// No-op by default
	}

	/**
	 * Called before the SSE server starts.
	 */
	default void willStartServerSentEventServer(@NonNull ServerSentEventServer serverSentEventServer) {
		// No-op by default
	}

	/**
	 * Called after the SSE server starts.
	 */
	default void didStartServerSentEventServer(@NonNull ServerSentEventServer serverSentEventServer) {
		// No-op by default
	}

	/**
	 * Called after a {@link ServerSentEventServer} instance was asked to start, but failed due to an exception.
	 */
	default void didFailToStartServerSentEventServer(@NonNull ServerSentEventServer serverSentEventServer,
																									 @NonNull Throwable throwable) {
		// No-op by default
	}

	/**
	 * Called before the SSE server stops.
	 */
	default void willStopServerSentEventServer(@NonNull ServerSentEventServer serverSentEventServer) {
		// No-op by default
	}

	/**
	 * Called after the SSE server stops.
	 */
	default void didStopServerSentEventServer(@NonNull ServerSentEventServer serverSentEventServer) {
		// No-op by default
	}

	/**
	 * Called after a {@link ServerSentEventServer} instance was asked to stop, but failed due to an exception.
	 */
	default void didFailToStopServerSentEventServer(@NonNull ServerSentEventServer serverSentEventServer,
																									@NonNull Throwable throwable) {
		// No-op by default
	}

	/**
	 * Called before an SSE connection is established.
	 */
	default void willEstablishServerSentEventConnection(@NonNull Request request,
																											@Nullable ResourceMethod resourceMethod) {
		// No-op by default
	}

	/**
	 * Called after an SSE connection is established.
	 */
	default void didEstablishServerSentEventConnection(@NonNull ServerSentEventConnection serverSentEventConnection) {
		// No-op by default
	}

	/**
	 * Called if an SSE connection fails to establish.
	 */
	default void didFailToEstablishServerSentEventConnection(@NonNull Request request,
																												 @Nullable ResourceMethod resourceMethod,
																												 @NonNull Throwable throwable) {
		// No-op by default
	}

	/**
	 * Called before an SSE connection is terminated.
	 */
	default void willTerminateServerSentEventConnection(@NonNull ServerSentEventConnection serverSentEventConnection,
																											ServerSentEventConnection.@NonNull TerminationReason terminationReason,
																											@Nullable Throwable throwable) {
		// No-op by default
	}

	/**
	 * Called after an SSE connection is terminated.
	 */
	default void didTerminateServerSentEventConnection(@NonNull ServerSentEventConnection serverSentEventConnection,
																										 @NonNull Duration connectionDuration,
																										 ServerSentEventConnection.@NonNull TerminationReason terminationReason,
																										 @Nullable Throwable throwable) {
		// No-op by default
	}

	/**
	 * Called before an SSE event is written.
	 */
	default void willWriteServerSentEvent(@NonNull ServerSentEventConnection serverSentEventConnection,
																			 @NonNull ServerSentEvent serverSentEvent) {
		// No-op by default
	}

	/**
	 * Called after an SSE event is written.
	 */
	default void didWriteServerSentEvent(@NonNull ServerSentEventConnection serverSentEventConnection,
																		 @NonNull ServerSentEvent serverSentEvent,
																		 @NonNull Duration writeDuration) {
		// No-op by default
	}

	/**
	 * Called after an SSE event fails to write.
	 */
	default void didFailToWriteServerSentEvent(@NonNull ServerSentEventConnection serverSentEventConnection,
																						 @NonNull ServerSentEvent serverSentEvent,
																						 @NonNull Duration writeDuration,
																						 @NonNull Throwable throwable) {
		// No-op by default
	}

	/**
	 * Called before an SSE comment is written.
	 */
	default void willWriteServerSentEventComment(@NonNull ServerSentEventConnection serverSentEventConnection,
																						 @NonNull ServerSentEventComment serverSentEventComment) {
		// No-op by default
	}

	/**
	 * Called after an SSE comment is written.
	 */
	default void didWriteServerSentEventComment(@NonNull ServerSentEventConnection serverSentEventConnection,
																							@NonNull ServerSentEventComment serverSentEventComment,
																							@NonNull Duration writeDuration) {
		// No-op by default
	}

	/**
	 * Called after an SSE comment fails to write.
	 */
	default void didFailToWriteServerSentEventComment(@NonNull ServerSentEventConnection serverSentEventConnection,
																										@NonNull ServerSentEventComment serverSentEventComment,
																										@NonNull Duration writeDuration,
																										@NonNull Throwable throwable) {
		// No-op by default
	}

	/**
	 * Called when Soklet emits a log event.
	 */
	default void didReceiveLogEvent(@NonNull LogEvent logEvent) {
		String message = logEvent.getMessage();
		Throwable throwable = logEvent.getThrowable().orElse(null);

		if (throwable == null) {
			System.err.printf("%s::didReceiveLogEvent [%s]: %s", LifecycleObserver.class.getSimpleName(), logEvent.getLogEventType().name(), message);
		} else {
			StringWriter stringWriter = new StringWriter();
			PrintWriter printWriter = new PrintWriter(stringWriter);
			throwable.printStackTrace(printWriter);
			String throwableWithStackTrace = stringWriter.toString();

			System.err.printf("%s::didReceiveLogEvent [%s]: %s\n%s\n", LifecycleObserver.class.getSimpleName(), logEvent.getLogEventType().name(), message, throwableWithStackTrace);
		}
	}

	/**
	 * Acquires a threadsafe {@link LifecycleObserver} instance with sensible defaults.
	 *
	 * @return a {@code LifecycleObserver} with default settings
	 */
	@NonNull
	static LifecycleObserver defaultInstance() {
		return DefaultLifecycleObserver.defaultInstance();
	}
}
