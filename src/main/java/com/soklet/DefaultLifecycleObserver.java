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

import javax.annotation.concurrent.ThreadSafe;
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

	static {
		DEFAULT_INSTANCE = new DefaultLifecycleObserver();
	}

	private DefaultLifecycleObserver() {
		// Singleton
	}

	@NonNull
	public static DefaultLifecycleObserver defaultInstance() {
		return DEFAULT_INSTANCE;
	}

	@Override
	public void didReceiveLogEvent(@NonNull LogEvent logEvent) {
		String message = logEvent.getMessage();
		Throwable throwable = logEvent.getThrowable().orElse(null);

		if (throwable == null) {
			System.err.printf("%s::didReceiveLogEvent [%s]: %s%n", LifecycleObserver.class.getSimpleName(), logEvent.getLogEventType().name(), message);
		} else if (isRoutineFailure(logEvent, throwable)) {
			// Keep the event and exact Throwable available to custom observers, while
			// avoiding stack rendering and allocation for ordinary control outcomes.
			System.err.printf("%s::didReceiveLogEvent [%s]: %s (%s: %s)%n",
					LifecycleObserver.class.getSimpleName(), logEvent.getLogEventType().name(),
					message, throwable.getClass().getName(), routineFailureMessage(throwable));
		} else {
			StringWriter stringWriter = new StringWriter();
			PrintWriter printWriter = new PrintWriter(stringWriter);
			throwable.printStackTrace(printWriter);
			String throwableWithStackTrace = stringWriter.toString();

			System.err.printf("%s::didReceiveLogEvent [%s]: %s\n%s\n", LifecycleObserver.class.getSimpleName(), logEvent.getLogEventType().name(), message, throwableWithStackTrace);
		}
	}

	private static String routineFailureMessage(Throwable throwable) {
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
	private static boolean isRoutineFailure(@NonNull LogEvent event,
			@NonNull Throwable throwable) {
		// Nested/suppressed failures can contain unexpected diagnostics even when
		// the outer exception is a routine interruption or socket close.
		if (throwable.getCause() != null || throwable.getSuppressed().length != 0)
			return false;
		return switch (event.getLogEventType()) {
			case SERVER_INTERNAL_ERROR, SSE_SERVER_INTERNAL_ERROR -> throwable instanceof RejectedExecutionException
					&& ("Request handler executor rejected task".equals(event.getMessage())
							|| "Request reader executor rejected task".equals(event.getMessage()));
			case SSE_SERVER_CONNECTION_REJECTED -> throwable instanceof RejectedExecutionException
					&& "Pending SSE handshake capacity exhausted".equals(event.getMessage());
			case REQUEST_PROCESSING_FAILED -> throwable.getClass() == InterruptedException.class;
			case SERVER_TRANSPORT_FAILURE -> isRoutineTransportEvent(event.getMessage())
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
