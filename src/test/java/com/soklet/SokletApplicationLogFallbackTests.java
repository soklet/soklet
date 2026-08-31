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
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Timeout(value = 60, unit = TimeUnit.SECONDS)
class SokletApplicationLogFallbackTests {
	@Test
	void fallbackIsBoundedValidUtf8AndEmitsOnlyTheThrowableClass()
			throws Exception {
		String messageCanary = "fallback-message-secret";
		AtomicInteger messageReads = new AtomicInteger();
		AtomicInteger stackTraceReads = new AtomicInteger();
		AtomicInteger causeReads = new AtomicInteger();
		Throwable failure = new Throwable(messageCanary) {
			@Override
			public String getMessage() {
				messageReads.incrementAndGet();
				return super.getMessage();
			}

			@Override
			public void printStackTrace(PrintStream stream) {
				stackTraceReads.incrementAndGet();
				throw new AssertionError("stack trace traversal is forbidden");
			}

			@Override
			public synchronized Throwable getCause() {
				causeReads.incrementAndGet();
				throw new AssertionError("cause traversal is forbidden");
			}
		};
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		PrintStream errorStream = new PrintStream(output, true,
				StandardCharsets.UTF_8);

		LifecycleObserverLogFallback.report(failure, errorStream);

		byte[] bytes = output.toByteArray();
		Assertions.assertTrue(bytes.length > 0);
		Assertions.assertTrue(bytes.length
				<= LifecycleObserverLogFallback.MAXIMUM_UTF8_BYTES);
		String rendered = StandardCharsets.UTF_8.newDecoder()
				.onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT)
				.decode(ByteBuffer.wrap(bytes)).toString();
		Assertions.assertEquals("soklet-lifecycle-observer-log-failure: "
				+ failure.getClass().getName() + "\n", rendered);
		Assertions.assertFalse(rendered.contains(messageCanary), rendered);
		Assertions.assertEquals(0, messageReads.get());
		Assertions.assertEquals(0, stackTraceReads.get());
		Assertions.assertEquals(0, causeReads.get());
	}

	@Test
	void observerFailureWritesDirectlyWithoutRecursivelyCallingObserver() {
		AtomicInteger observerCalls = new AtomicInteger();
		Throwable exactFailure = new IllegalStateException("observer failed");
		LifecycleObserver observer = new LifecycleObserver() {
			@Override
			public void didReceiveLogEvent(LogEvent event) {
				observerCalls.incrementAndGet();
				throw (IllegalStateException) exactFailure;
			}
		};
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		PrintStream errorStream = new PrintStream(output, true,
				StandardCharsets.UTF_8);

		try {
			observer.didReceiveLogEvent(LogEvent.with(
					LogEventType.SERVER_INTERNAL_ERROR, "original event").build());
		} catch (Throwable observerFailure) {
			Assertions.assertSame(exactFailure, observerFailure);
			LifecycleObserverLogFallback.report(observerFailure, errorStream);
		}

		Assertions.assertEquals(1, observerCalls.get());
		String rendered = output.toString(StandardCharsets.UTF_8);
		Assertions.assertEquals("soklet-lifecycle-observer-log-failure: "
				+ IllegalStateException.class.getName() + "\n", rendered);
		Assertions.assertFalse(rendered.contains("observer failed"), rendered);
	}

	@Test
	void fallbackSinkFailureIsContainedWithoutASecondChannel() {
		AtomicInteger writes = new AtomicInteger();
		PrintStream failedSink = new PrintStream(OutputStream.nullOutputStream(),
				true, StandardCharsets.UTF_8) {
			@Override
			public void write(byte[] bytes, int offset, int length) {
				writes.incrementAndGet();
				throw new AssertionError("stderr unavailable");
			}
		};

		Assertions.assertDoesNotThrow(() -> LifecycleObserverLogFallback.report(
				new IllegalStateException("observer failed"), failedSink));
		Assertions.assertEquals(1, writes.get());
	}
}
