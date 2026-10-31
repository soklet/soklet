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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.concurrent.RejectedExecutionException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Verifies logging ownership independently of other observation callbacks. */
@ResourceLock("java.lang.System.err")
class LifecycleObserverLoggingTests {

	@Test
	void routineFrameworkConditionsDoNotRenderStackTraces() {
		for (LogEvent event : List.of(
				LogEvent.with(LogEventType.SERVER_INTERNAL_ERROR, "Request handler executor rejected task")
						.throwable(new RejectedExecutionException("full")).build(),
				LogEvent.with(LogEventType.SSE_SERVER_INTERNAL_ERROR, "Request handler executor rejected task")
						.throwable(new RejectedExecutionException("full")).build(),
				LogEvent.with(LogEventType.SSE_SERVER_CONNECTION_REJECTED, "Pending SSE handshake capacity exhausted")
						.throwable(new RejectedExecutionException("full")).build(),
				LogEvent.with(LogEventType.REQUEST_PROCESSING_FAILED, "request interrupted")
						.throwable(new InterruptedException("deadline")).build(),
				LogEvent.with(LogEventType.SERVER_TRANSPORT_FAILURE, "HTTP transport failure: read_error")
						.throwable(new SocketException("Connection reset")).build())) {
			String output = captureStandardError(() -> LifecycleObserver.defaultInstance().didReceiveLogEvent(event));
			Assertions.assertEquals(1, output.lines().count(), output);
			Assertions.assertTrue(output.contains(event.getThrowable().orElseThrow().getClass().getName()), output);
			Assertions.assertFalse(output.contains("routineFrameworkConditionsDoNotRenderStackTraces"), output);
		}
	}

	@Test
	void compactDiagnosticsRetainFailureReasonAndBoundMessagesToOneLine() {
		for (Throwable failure : List.of(new RejectedExecutionException("executor is shut down"),
				new SocketTimeoutException("Read timed out"))) {
			LogEvent event = failure instanceof SocketTimeoutException
					? LogEvent.with(LogEventType.SERVER_TRANSPORT_FAILURE, "HTTP transport failure: request_timeout").throwable(failure).build()
					: LogEvent.with(LogEventType.SERVER_INTERNAL_ERROR, "Request handler executor rejected task").throwable(failure).build();
			String output = captureStandardError(() -> LifecycleObserver.defaultInstance().didReceiveLogEvent(event));
			Assertions.assertEquals(1, output.lines().count());
			Assertions.assertTrue(output.contains(failure.getMessage()), output);
		}
		LogEvent hostile = LogEvent.with(LogEventType.SERVER_INTERNAL_ERROR, "Request handler executor rejected task")
				.throwable(new RejectedExecutionException("shutdown\r\n" + "x".repeat(2000))).build();
		String output = captureStandardError(() -> LifecycleObserver.defaultInstance().didReceiveLogEvent(hostile));
		Assertions.assertEquals(1, output.lines().count());
		Assertions.assertTrue(output.length() < 800);
	}

	@Test
	void unexpectedFailuresKeepTheirOriginalStacks() {
		for (LogEvent event : List.of(
				LogEvent.with(LogEventType.SERVER_INTERNAL_ERROR, "unexpected executor failure")
						.throwable(new RejectedExecutionException("diagnostic")).build(),
				LogEvent.with(LogEventType.REQUEST_PROCESSING_FAILED, "unexpected application failure")
						.throwable(new IllegalStateException("diagnostic")).build(),
				LogEvent.with(LogEventType.REQUEST_PROCESSING_FAILED, "application executor rejected")
						.throwable(new RejectedExecutionException("application diagnostic")).build(),
				LogEvent.with(LogEventType.SERVER_TRANSPORT_FAILURE, "unexpected transport failure")
						.throwable(new IllegalStateException("diagnostic")).build(),
				LogEvent.with(LogEventType.SERVER_TRANSPORT_FAILURE, "HTTP transport failure: accept_loop_error")
						.throwable(new SocketException("unexpected listening socket failure")).build())) {
			String output = captureStandardError(() -> LifecycleObserver.defaultInstance().didReceiveLogEvent(event));
			Assertions.assertTrue(output.contains("unexpectedFailuresKeepTheirOriginalStacks"), output);
		}
	}

	@Test
	void routineFailuresWithNestedDiagnosticsStillRenderStacks() {
		SocketException failure = new SocketException("Connection reset");
		failure.addSuppressed(new IllegalStateException("diagnostic"));
		LogEvent event = LogEvent.with(LogEventType.SERVER_TRANSPORT_FAILURE, "HTTP transport failure: read_error")
				.throwable(failure).build();
		String output = captureStandardError(() -> LifecycleObserver.defaultInstance().didReceiveLogEvent(event));
		Assertions.assertTrue(output.contains("Suppressed: java.lang.IllegalStateException: diagnostic"), output);
	}


	@Test
	void interfaceDefaultIsSilentAndDoesNotInspectTheThrowable() {
		AtomicInteger stackReads = new AtomicInteger();
		Throwable failure = new IllegalStateException("application-secret") {
			@Override
			public void printStackTrace(PrintWriter writer) {
				stackReads.incrementAndGet();
				writer.print("application-secret-stack");
			}
		};
		LogEvent event = event(failure);
		String output = captureStandardError(() ->
				new LifecycleObserver() {}.didReceiveLogEvent(event));

		Assertions.assertEquals("", output);
		Assertions.assertEquals(0, stackReads.get());
	}

	@Test
	void customLoggerReceivesTheExactEventWithoutPeerStderrCopies() {
		List<LogEvent> received = new ArrayList<>();
		LogEvent event = event(new IllegalStateException("application-secret"));
		LifecycleObserver logger = new LifecycleObserver() {
			@Override
			public void didReceiveLogEvent(LogEvent logEvent) {
				received.add(logEvent);
			}
		};
		SokletConfig config = configuration().lifecycleObservers(List.of(
				new LifecycleObserver() {}, logger, new LifecycleObserver() {})).build();

		String output = captureStandardError(() ->
				config.getAggregateLifecycleObserver().didReceiveLogEvent(event));

		Assertions.assertEquals("", output);
		Assertions.assertEquals(1, received.size());
		Assertions.assertSame(event, received.get(0));
		Assertions.assertSame(event.getThrowable().orElseThrow(),
				received.get(0).getThrowable().orElseThrow());
	}

	@Test
	void unconfiguredApplicationStillPrintsOneEventAndItsOriginalStackTrace() {
		Throwable failure = new IllegalStateException("diagnostic failure");
		LogEvent event = event(failure);
		SokletConfig config = configuration().build();
		String output = captureStandardError(() ->
				config.getAggregateLifecycleObserver().didReceiveLogEvent(event));

		Assertions.assertEquals(List.of(LifecycleObserver.defaultInstance()),
				config.getLifecycleObservers());
		Assertions.assertEquals(1, output.lines().filter(line -> line.startsWith(
				"LifecycleObserver::didReceiveLogEvent [SERVER_INTERNAL_ERROR]: diagnostic event")).count());
		Assertions.assertTrue(output.contains("java.lang.IllegalStateException: diagnostic failure"), output);
		Assertions.assertTrue(output.contains("unconfiguredApplicationStillPrintsOneEventAndItsOriginalStackTrace"), output);
	}

	@Test
	void defaultFactoryKeepsTheExistingRecordWithoutAThrowable() {
		String output = captureStandardError(() -> LifecycleObserver.defaultInstance()
				.didReceiveLogEvent(event(null)));

		Assertions.assertEquals("LifecycleObserver::didReceiveLogEvent "
				+ "[SERVER_INTERNAL_ERROR]: diagnostic event" + System.lineSeparator(), output);
	}

	@Test
	void explicitlySelectedDefaultObserverPrintsOnceAlongsideSilentPeers() {
		SokletConfig config = configuration().lifecycleObservers(List.of(
				new LifecycleObserver() {}, LifecycleObserver.defaultInstance(),
				new LifecycleObserver() {})).build();
		String output = captureStandardError(() ->
				config.getAggregateLifecycleObserver().didReceiveLogEvent(event(null)));

		Assertions.assertEquals("LifecycleObserver::didReceiveLogEvent "
				+ "[SERVER_INTERNAL_ERROR]: diagnostic event" + System.lineSeparator(), output);
	}

	@Test
	void replacedAndClearedObserverConfigurationsHaveNoImplicitLogger() {
		List<SokletConfig> configurations = List.of(
				configuration().lifecycleObserver(new LifecycleObserver() {}).build(),
				configuration().lifecycleObserver(null).build(),
				configuration().lifecycleObservers(null).build(),
				configuration().lifecycleObservers(List.of()).build());

		String output = captureStandardError(() -> configurations.forEach(config ->
				config.getAggregateLifecycleObserver().didReceiveLogEvent(event(null))));

		Assertions.assertEquals("", output);
	}

	@Test
	void failingLogObserversPreserveFanOutOrderAndExactFailureIdentity() {
		RuntimeException firstFailure = new IllegalStateException("first");
		RuntimeException secondFailure = new IllegalArgumentException("second");
		List<String> calls = new ArrayList<>();
		LifecycleObserver first = new LifecycleObserver() {
			@Override
			public void didReceiveLogEvent(LogEvent logEvent) {
				calls.add("first");
				throw firstFailure;
			}
		};
		LifecycleObserver second = new LifecycleObserver() {
			@Override
			public void didReceiveLogEvent(LogEvent logEvent) {
				calls.add("second");
				throw secondFailure;
			}
		};
		LifecycleObserver last = new LifecycleObserver() {
			@Override
			public void didReceiveLogEvent(LogEvent logEvent) {
				calls.add("last");
			}
		};
		SokletConfig config = configuration().lifecycleObservers(List.of(
				first, new LifecycleObserver() {}, second, last)).build();
		String output = captureStandardError(() -> {
			RuntimeException actual = Assertions.assertThrows(RuntimeException.class,
					() -> config.getAggregateLifecycleObserver().didReceiveLogEvent(event(null)));
			Assertions.assertSame(firstFailure, actual);
			Assertions.assertArrayEquals(new Throwable[]{secondFailure}, actual.getSuppressed());
		});

		Assertions.assertEquals(List.of("first", "second", "last"), calls);
		Assertions.assertEquals("", output);
	}

	private static SokletConfig.Builder configuration() {
		return SokletConfig.withHttpServer(HttpServer.fromPort(0));
	}

	private static LogEvent event(Throwable failure) {
		return LogEvent.with(LogEventType.SERVER_INTERNAL_ERROR, "diagnostic event")
				.throwable(failure).build();
	}

	private static String captureStandardError(Runnable action) {
		PrintStream originalError = System.err;
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (PrintStream capturedError = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
			System.setErr(capturedError);
			try {
				action.run();
			} finally {
				System.setErr(originalError);
			}
		}
		return bytes.toString(StandardCharsets.UTF_8);
	}
}
