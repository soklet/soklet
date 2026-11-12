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
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class DefaultLifecycleObserver implements LifecycleObserver {
	@NonNull
	private static final DefaultLifecycleObserver DEFAULT_INSTANCE;
	private final @NonNull LifecycleLogDispatcher dispatcher;

	static {
		DEFAULT_INSTANCE = new DefaultLifecycleObserver();
	}

	private DefaultLifecycleObserver() {
		this(LifecycleLogDispatcher.defaultInstance());
	}

	private DefaultLifecycleObserver(@NonNull LifecycleLogDispatcher dispatcher) {
		this.dispatcher = dispatcher;
	}

	@NonNull
	static DefaultLifecycleObserver withDispatcher(@NonNull LifecycleLogDispatcher dispatcher) {
		return new DefaultLifecycleObserver(dispatcher);
	}

	@NonNull
	public static DefaultLifecycleObserver defaultInstance() {
		return DEFAULT_INSTANCE;
	}

	@Override
	public void didReceiveLogEvent(@NonNull LogEvent logEvent) {
		// Do not retain LogEvent: it can own an entire request/response graph.
		String message = LifecycleLogDispatcher.snapshotMessage(logEvent.getMessage());
		LogEventType type = logEvent.getLogEventType();
		Throwable throwable = logEvent.getThrowable().orElse(null);
		PrintStream errorStream = System.err;
		dispatcher.dispatch(() -> write(type, message, throwable, errorStream));
	}

	private static void write(@NonNull LogEventType type, @NonNull String message,
			@Nullable Throwable throwable, @NonNull PrintStream errorStream) {

		if (throwable == null) {
			errorStream.printf("%s::didReceiveLogEvent [%s]: %s%n", LifecycleObserver.class.getSimpleName(), type.name(), message);
		} else if (isRoutineFailure(type, message, throwable)) {
			// Keep the event and exact Throwable available to custom observers, while
			// avoiding stack rendering and allocation for ordinary control outcomes.
			errorStream.printf("%s::didReceiveLogEvent [%s]: %s (%s: %s)%n",
					LifecycleObserver.class.getSimpleName(), type.name(),
					message, throwable.getClass().getName(), routineFailureMessage(throwable));
		} else {
			StringWriter stringWriter = new StringWriter();
			PrintWriter printWriter = new PrintWriter(stringWriter);
			throwable.printStackTrace(printWriter);
			String throwableWithStackTrace = stringWriter.toString();

			errorStream.printf("%s::didReceiveLogEvent [%s]: %s\n%s\n", LifecycleObserver.class.getSimpleName(), type.name(), message, throwableWithStackTrace);
		}
	}

	private static @NonNull String routineFailureMessage(@NonNull Throwable throwable) {
		String message = throwable.getMessage();
		if (message == null)
			return "";
		StringBuilder summary = new StringBuilder(Math.min(message.length(), 512));
		for (int offset = 0; offset < message.length() && summary.length() < 512;) {
			int character = message.codePointAt(offset);
			offset += Character.charCount(character);
			if (summary.length() + Character.charCount(character) > 512)
				break;
			summary.appendCodePoint(Character.isISOControl(character) || character == 0x2028 || character == 0x2029 ? ' ' : character);
		}
		return summary.toString();
	}
	private static boolean isRoutineFailure(@NonNull LogEventType type, @NonNull String message,
			@NonNull Throwable throwable) {
		// Nested/suppressed failures can contain unexpected diagnostics even when
		// the outer exception is a routine interruption or socket close.
		if (throwable.getCause() != null || throwable.getSuppressed().length != 0)
			return false;
		return switch (type) {
			case SERVER_INTERNAL_ERROR, SSE_SERVER_INTERNAL_ERROR -> throwable instanceof RejectedExecutionException
					&& ("Request handler executor rejected task".equals(message)
							|| "Request reader executor rejected task".equals(message));
			case SSE_SERVER_CONNECTION_REJECTED -> throwable instanceof RejectedExecutionException
					&& "Pending SSE handshake capacity exhausted".equals(message);
			case REQUEST_PROCESSING_FAILED -> throwable.getClass() == InterruptedException.class;
			case SERVER_TRANSPORT_FAILURE -> isRoutineTransportEvent(message)
					&& (throwable instanceof SocketException
							|| throwable instanceof SocketTimeoutException
							|| throwable instanceof ClosedChannelException
							|| throwable instanceof TimeoutException);
			default -> false;
		};
	}

	private static boolean isRoutineTransportEvent(@NonNull String message) {
		int connectionId = message.indexOf(" (connectionId=");
		String event = connectionId < 0 ? message : message.substring(0, connectionId);
		return switch (event) {
			case "HTTP transport failure: read_error", "HTTP transport failure: write_error",
					"HTTP transport failure: request_timeout", "HTTP transport failure: response_write_idle_timeout",
					"SSE transport failure: read_error", "SSE transport failure: write_error",
					"SSE transport failure: request_timeout", "SSE transport failure: response_write_idle_timeout" -> true;
			default -> false;
		};
	}

}
