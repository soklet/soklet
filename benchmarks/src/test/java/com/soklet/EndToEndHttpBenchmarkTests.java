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

import com.soklet.internal.streaming.ManagedResponseStream;
import org.junit.jupiter.api.Test;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EndToEndHttpBenchmarkTests {
	@Test
	void validatesExactBodiesAcrossChunkBoundariesAndKeepsNextResponseIntact() throws Exception {
		try (BufferedInputStream input = input("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
				+ "2\r\nab\r\n3\r\ncde\r\n1\r\nf\r\n0\r\n\r\n"
				+ "HTTP/1.1 200 OK\r\nContent-Length: 3\r\n\r\nxyz")) {
			validate(input, "abcdef");
			validate(input, "xyz");
			assertEquals(-1, input.read());
		}
	}

	@Test
	void rejectsWellFramedButTruncatedChunkedBody() {
		assertInvalid("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n0\r\n\r\n", "abcdef");
	}

	@Test
	void rejectsExtraOrWrongBodyBytes() {
		assertInvalid("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n4\r\nabcd\r\n0\r\n\r\n", "abc");
		assertInvalid("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nacb\r\n0\r\n\r\n", "abc");
		assertInvalid("HTTP/1.1 200 OK\r\nContent-Length: 3\r\n\r\nacb", "abc");
	}

	@Test
	void rejectsPrematureEofOrMissingTerminalChunk() {
		assertInvalid("HTTP/1.1 200 OK\r\nContent-Length: 6\r\n\r\nabc", "abcdef");
		assertInvalid("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n", "abc");
	}

	@Test
	void rejectsWrongStatusAndContentLength() {
		assertInvalid("HTTP/1.1 503 Unavailable\r\nContent-Length: 3\r\n\r\nabc", "abc");
		assertInvalid("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nab", "abc");
	}

	@Test
	void classifiesTransportFailuresWithConnectPhaseTakingPriority() {
		assertEquals(EndToEndHttpBenchmark.ErrorCategory.CONNECT_FAILURE,
				EndToEndHttpBenchmark.classifyError(new SocketTimeoutException("connect timeout"), true));
		assertEquals(EndToEndHttpBenchmark.ErrorCategory.READ_TIMEOUT,
				EndToEndHttpBenchmark.classifyError(new SocketTimeoutException("read timeout"), false));
		assertEquals(EndToEndHttpBenchmark.ErrorCategory.UNEXPECTED_EOF,
				EndToEndHttpBenchmark.classifyError(new EOFException("closed"), false));
		assertEquals(EndToEndHttpBenchmark.ErrorCategory.SOCKET_IO,
				EndToEndHttpBenchmark.classifyError(new SocketException("reset"), false));
		assertEquals(EndToEndHttpBenchmark.ErrorCategory.OTHER_IO,
				EndToEndHttpBenchmark.classifyError(new IOException("other"), false));
	}

	@Test
	void classifiesStatusBodyAndFramingFailuresWithoutParsingMessages() {
		IOException status = assertInvalid("HTTP/1.1 503 Unavailable\r\nContent-Length: 3\r\n\r\nabc", "abc");
		assertEquals(EndToEndHttpBenchmark.ErrorCategory.HTTP_STATUS, EndToEndHttpBenchmark.classifyError(status, false));
		assertTrue(status.getMessage().contains("503"));
		IOException body = assertInvalid("HTTP/1.1 200 OK\r\nContent-Length: 3\r\n\r\nxyz", "abc");
		assertEquals(EndToEndHttpBenchmark.ErrorCategory.BODY_VALIDATION, EndToEndHttpBenchmark.classifyError(body, false));
		IOException framing = assertInvalid("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\nx\r\n", "abc");
		assertEquals(EndToEndHttpBenchmark.ErrorCategory.RESPONSE_FRAMING, EndToEndHttpBenchmark.classifyError(framing, false));
		IOException eof = assertInvalid("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n", "abc");
		assertEquals(EndToEndHttpBenchmark.ErrorCategory.UNEXPECTED_EOF, EndToEndHttpBenchmark.classifyError(eof, false));
	}

	@Test
	void diagnosticCountersMergeWhileExamplesStayBoundedAndKeepTheirFirstObservation() {
		EndToEndHttpBenchmark.ErrorDiagnostics first = new EndToEndHttpBenchmark.ErrorDiagnostics();
		first.record(new IOException("x".repeat(1000)), EndToEndHttpBenchmark.ErrorPhase.RESPONSE_READ);
		String example = first.firstExample(EndToEndHttpBenchmark.ErrorCategory.OTHER_IO);
		assertEquals(200, example.length());
		assertTrue(example.startsWith("RESPONSE_READ: IOException: "));
		EndToEndHttpBenchmark.ErrorDiagnostics later = new EndToEndHttpBenchmark.ErrorDiagnostics();
		later.record(new IOException("later"), EndToEndHttpBenchmark.ErrorPhase.REQUEST_WRITE);
		later.add(first);
		assertEquals(2L, later.count(EndToEndHttpBenchmark.ErrorCategory.OTHER_IO));
		assertEquals(example, later.firstExample(EndToEndHttpBenchmark.ErrorCategory.OTHER_IO));
		assertEquals(8, EndToEndHttpBenchmark.ErrorCategory.values().length);
	}

	@Test
	void outputScenariosExerciseTheirRegisteredWritersAndProduceTheSameUnicodeBody() throws Exception {
		EndToEndHttpBenchmark.BenchmarkResource resource = new EndToEndHttpBenchmark.BenchmarkResource();
		List<MarshaledResponse> responses = List.of(resource.outputNative(), resource.outputScalar(),
				resource.outputMixed());
		byte[] expected = EndToEndHttpBenchmark.outputBody();
		assertEquals(65_536, expected.length);
		assertTrue(new String(expected, StandardCharsets.UTF_8).contains("é界🙂"));
		int[] expectedSinkCalls = {8, 8, 48};
		for (int index = 0; index < responses.size(); index++) {
			EndToEndHttpBenchmark.OutputMode mode = EndToEndHttpBenchmark.OutputMode.values()[index];
			EndToEndHttpBenchmark.Scenario scenario = EndToEndHttpBenchmark.outputScenario(mode);
			assertArrayEquals(expected, scenario.expectedBody());
			assertTrue(new String(scenario.requestBytes(), StandardCharsets.US_ASCII)
					.startsWith("GET /" + scenario.name() + " HTTP/1.1\r\n"));
			ByteArrayOutputStream captured = new ByteArrayOutputStream();
			AtomicInteger sinkCalls = new AtomicInteger();
			ManagedResponseStream.Output sink = new ManagedResponseStream.Output() {
				@Override public void write(ByteBuffer bytes) {
					sinkCalls.incrementAndGet();
					byte[] copy = new byte[bytes.remaining()];
					bytes.get(copy);
					captured.writeBytes(copy);
				}
				@Override public void flush() {}
				@Override public boolean isOpen() { return true; }
			};
			ManagedResponseStream responseStream = new ManagedResponseStream(
					Request.withPath(HttpMethod.GET, "/" + scenario.name()).build(), uncanceledToken(),
					null, null, sink, () -> {}, failure -> { throw new AssertionError(failure); },
					failure -> { throw new AssertionError(failure); });
			StreamingResponseBody.WriterBody body = (StreamingResponseBody.WriterBody)
					responses.get(index).getStreamingResponseBody().orElseThrow();
			responseStream.run(body.getWriter());
			assertArrayEquals(expected, captured.toByteArray(), scenario.name());
			assertEquals(expectedSinkCalls[index], sinkCalls.get(), scenario.name());
		}
	}

	@Test
	void processMetricDeltasPreserveUnavailableCountersAndNormalizeAgainstSuccessfulBodies() {
		var before = new EndToEndHttpBenchmark.ProcessMetrics(1_000L, 3L, 20L);
		var after = new EndToEndHttpBenchmark.ProcessMetrics(1_800L, 5L, 29L);
		var delta = EndToEndHttpBenchmark.ProcessMetrics.delta(before, after);
		assertEquals(800L, delta.allocatedBytes());
		assertEquals(2L, delta.gcCollections());
		assertEquals(9L, delta.gcMillis());
		assertEquals(200D, delta.allocatedBytesPerRequest(4));
		assertEquals(2D, delta.allocatedBytesPerBodyByte(4, 100));
		assertNull(delta.allocatedBytesPerRequest(0));
		assertNull(delta.allocatedBytesPerBodyByte(4, 0));
		var unavailable = EndToEndHttpBenchmark.ProcessMetrics.delta(before,
				new EndToEndHttpBenchmark.ProcessMetrics(null, -1L, 19L));
		assertNull(unavailable.allocatedBytes());
		assertNull(unavailable.gcCollections());
		assertNull(unavailable.gcMillis());
		var total = EndToEndHttpBenchmark.ProcessMetrics.total(List.of(delta,
				new EndToEndHttpBenchmark.ProcessMetrics(200L, 1L, 3L)));
		assertEquals(1_000L, total.allocatedBytes());
		assertEquals(3L, total.gcCollections());
		assertEquals(12L, total.gcMillis());
		assertEquals(125D, total.allocatedBytesPerRequest(8));
		assertNull(EndToEndHttpBenchmark.ProcessMetrics.total(List.of(delta, unavailable)).allocatedBytes());
	}

	@Test
	void allocationCaptureUsesTheWholeJvmCounterWhenAvailableAndFailsClosedOtherwise() throws Exception {
		EndToEndHttpBenchmark.ProcessMetrics.initialize(false);
		Method totalAllocatedBytes;
		try {
			totalAllocatedBytes = com.sun.management.ThreadMXBean.class.getMethod("getTotalThreadAllocatedBytes");
		} catch (NoSuchMethodException unavailable) {
			assertAllocationUnavailable();
			return;
		}
		if (!(ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean)
				|| !bean.isThreadAllocatedMemorySupported()) {
			assertAllocationUnavailable();
			return;
		}
		long before;
		try {
			before = (long) totalAllocatedBytes.invoke(bean);
		} catch (InvocationTargetException unsupported) {
			assertTrue(unsupported.getCause() instanceof UnsupportedOperationException);
			assertAllocationUnavailable();
			return;
		}
		if (before < 0L) {
			assertAllocationUnavailable();
			return;
		}
		Long captured = EndToEndHttpBenchmark.ProcessMetrics.capture().allocatedBytes();
		long after = (long) totalAllocatedBytes.invoke(bean);
		assertTrue(captured != null && captured >= before && captured <= after,
				"The sample must be the cumulative whole-JVM counter read between the surrounding observations");
		EndToEndHttpBenchmark.ProcessMetrics.initialize(true);
	}

	private static void assertAllocationUnavailable() {
		assertNull(EndToEndHttpBenchmark.ProcessMetrics.capture().allocatedBytes());
		assertThrows(IllegalStateException.class, () -> EndToEndHttpBenchmark.ProcessMetrics.initialize(true));
	}

	@Test
	void processMetricJsonNamesTheWholeJvmScopeAndNeverSubstitutesZeroForUnavailable() {
		StringBuilder measured = new StringBuilder();
		EndToEndHttpBenchmark.appendProcessMetrics(measured,
				new EndToEndHttpBenchmark.ProcessMetrics(800L, 2L, 9L), 4, 100);
		assertTrue(measured.toString().contains("\"wholeJvmAllocatedBytes\": 800"));
		assertTrue(measured.toString().contains("\"wholeJvmAllocatedBytesPerRequest\": 200.000"));
		assertTrue(measured.toString().contains("\"wholeJvmAllocatedBytesPerValidatedBodyByte\": 2.000"));
		StringBuilder unavailable = new StringBuilder();
		EndToEndHttpBenchmark.appendProcessMetrics(unavailable,
				new EndToEndHttpBenchmark.ProcessMetrics(null, null, null), 0, 100);
		assertTrue(unavailable.toString().contains("\"wholeJvmAllocatedBytes\": null"));
		assertTrue(unavailable.toString().contains("\"wholeJvmAllocatedBytesPerRequest\": null"));
		assertTrue(unavailable.toString().contains("\"gcCollectionMillis\": null"));
	}

	private static CancelationToken uncanceledToken() {
		return new CancelationToken() {
			@Override public Boolean isCanceled() { return false; }
			@Override public Optional<StreamTerminationReason> getCancelationReason() { return Optional.empty(); }
			@Override public Optional<Throwable> getCancelationCause() { return Optional.empty(); }
			@Override public CallbackRegistration onCancel(Runnable callback) { return () -> {}; }
		};
	}

	private static IOException assertInvalid(String response, String expectedBody) {
		return assertThrows(IOException.class, () -> {
			try (BufferedInputStream input = input(response)) {
				validate(input, expectedBody);
			}
		});
	}

	private static void validate(BufferedInputStream input, String expectedBody) throws IOException {
		EndToEndHttpBenchmark.validateResponse(input, new byte[1024], new byte[2],
				200, expectedBody.getBytes(StandardCharsets.US_ASCII));
	}

	private static BufferedInputStream input(String response) {
		return new BufferedInputStream(new ByteArrayInputStream(response.getBytes(StandardCharsets.US_ASCII)));
	}
}
