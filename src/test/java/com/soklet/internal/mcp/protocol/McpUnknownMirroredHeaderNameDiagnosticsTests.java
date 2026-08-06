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

package com.soklet.internal.mcp.protocol;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Deterministic bounds and redaction tests for unknown mirrored-header name
 * diagnostics.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Timeout(10)
public class McpUnknownMirroredHeaderNameDiagnosticsTests {
	@Test
	public void rollingWindowUsesTheExactMonotonicBoundary() {
		AtomicLong nowNanos = new AtomicLong();
		List<Diagnostic> diagnostics = new ArrayList<>();
		McpUnknownMirroredHeaderNameDiagnostics recorder = diagnostics(
				nowNanos::get, diagnostics);

		for (int second = 0;
				second < McpUnknownMirroredHeaderNameDiagnostics.MAXIMUM_EVENTS_PER_WINDOW;
				second++) {
			nowNanos.set(TimeUnit.SECONDS.toNanos(second));
			recorder.observe("/mcp", "Mcp-Param-Initial-" + second);
		}

		nowNanos.set(McpUnknownMirroredHeaderNameDiagnostics.WINDOW_NANOSECONDS - 1L);
		recorder.observe("/mcp", "Mcp-Param-Before-Boundary");
		Assertions.assertEquals(10, diagnostics.size());

		nowNanos.set(McpUnknownMirroredHeaderNameDiagnostics.WINDOW_NANOSECONDS);
		recorder.observe("/mcp", "Mcp-Param-At-Boundary");
		Assertions.assertEquals(11, diagnostics.size());
		Assertions.assertEquals(new Diagnostic("/mcp", "Mcp-Param-At-Boundary"),
				diagnostics.get(10));

		nowNanos.set(McpUnknownMirroredHeaderNameDiagnostics.WINDOW_NANOSECONDS
				+ TimeUnit.SECONDS.toNanos(1L) - 1L);
		recorder.observe("/mcp", "Mcp-Param-Before-Second-Boundary");
		Assertions.assertEquals(11, diagnostics.size());

		nowNanos.incrementAndGet();
		recorder.observe("/mcp", "Mcp-Param-At-Second-Boundary");
		Assertions.assertEquals(12, diagnostics.size());
		Assertions.assertEquals(
				new Diagnostic("/mcp", "Mcp-Param-At-Second-Boundary"),
				diagnostics.get(11));
	}

	@Test
	public void regressedClockDoesNotMutateQuotaUntilItCatchesUp() {
		long initialTime = TimeUnit.SECONDS.toNanos(10L);
		AtomicLong nowNanos = new AtomicLong(initialTime);
		List<Diagnostic> diagnostics = new ArrayList<>();
		McpUnknownMirroredHeaderNameDiagnostics recorder = diagnostics(
				nowNanos::get, diagnostics);
		recorder.observe("/mcp", "Mcp-Param-Initial");

		nowNanos.decrementAndGet();
		for (int index = 0; index < 20; index++)
			recorder.observe("/mcp", "Mcp-Param-Regressed-" + index);
		Assertions.assertEquals(List.of(
				new Diagnostic("/mcp", "Mcp-Param-Initial")), diagnostics,
				"A regressed clock must neither deliver nor reserve quota.");

		nowNanos.set(initialTime);
		for (int index = 1;
				index < McpUnknownMirroredHeaderNameDiagnostics
						.MAXIMUM_EVENTS_PER_WINDOW;
				index++)
			recorder.observe("/mcp", "Mcp-Param-Caught-Up-" + index);
		Assertions.assertEquals(
				McpUnknownMirroredHeaderNameDiagnostics.MAXIMUM_EVENTS_PER_WINDOW,
				diagnostics.size(),
				"The regressed observations must not have consumed quota.");

		recorder.observe("/mcp", "Mcp-Param-Over-Budget");
		Assertions.assertEquals(
				McpUnknownMirroredHeaderNameDiagnostics.MAXIMUM_EVENTS_PER_WINDOW,
				diagnostics.size());
	}

	@Test
	public void monotonicClockWrapPreservesTheRollingWindow() {
		long initialTime = Long.MAX_VALUE - TimeUnit.SECONDS.toNanos(1L);
		AtomicLong nowNanos = new AtomicLong(initialTime);
		List<Diagnostic> diagnostics = new ArrayList<>();
		McpUnknownMirroredHeaderNameDiagnostics recorder = diagnostics(
				nowNanos::get, diagnostics);

		for (int index = 0;
				index < McpUnknownMirroredHeaderNameDiagnostics
						.MAXIMUM_EVENTS_PER_WINDOW;
				index++)
			recorder.observe("/mcp", "Mcp-Param-Before-Wrap-" + index);

		long beforeBoundary = initialTime
				+ McpUnknownMirroredHeaderNameDiagnostics.WINDOW_NANOSECONDS - 1L;
		Assertions.assertTrue(beforeBoundary < 0,
				"The test clock must cross the signed-long wrap boundary.");
		nowNanos.set(beforeBoundary);
		recorder.observe("/mcp", "Mcp-Param-Wrapped-Before-Boundary");
		Assertions.assertEquals(10, diagnostics.size());

		nowNanos.incrementAndGet();
		recorder.observe("/mcp", "Mcp-Param-Wrapped-At-Boundary");
		Assertions.assertEquals(11, diagnostics.size());
		Assertions.assertEquals(
				new Diagnostic("/mcp", "Mcp-Param-Wrapped-At-Boundary"),
				diagnostics.get(10));
	}

	@Test
	public void throwingClockIsContainedAndDoesNotConsumeQuota() {
		AtomicInteger clockInvocations = new AtomicInteger();
		List<Diagnostic> diagnostics = new ArrayList<>();
		McpUnknownMirroredHeaderNameDiagnostics recorder = diagnostics(() -> {
			if (clockInvocations.getAndIncrement() == 0)
				throw new AssertionError("expected clock failure");
			return 0L;
		}, diagnostics);

		Assertions.assertDoesNotThrow(() -> recorder.observe(
				"/mcp", "Mcp-Param-Clock-Failed"));
		Assertions.assertTrue(diagnostics.isEmpty());

		for (int index = 0;
				index < McpUnknownMirroredHeaderNameDiagnostics
						.MAXIMUM_EVENTS_PER_WINDOW;
				index++)
			recorder.observe("/mcp", "Mcp-Param-After-Clock-Failure-" + index);
		Assertions.assertEquals(
				McpUnknownMirroredHeaderNameDiagnostics.MAXIMUM_EVENTS_PER_WINDOW,
				diagnostics.size(),
				"A failed clock read must not reserve diagnostic quota.");

		recorder.observe("/mcp", "Mcp-Param-Over-Budget");
		Assertions.assertEquals(
				McpUnknownMirroredHeaderNameDiagnostics.MAXIMUM_EVENTS_PER_WINDOW,
				diagnostics.size());
	}

	@Test
	public void concurrentDuplicateNamesShareOneAtomicServerBudget()
			throws Exception {
		int observationCount = 64;
		ConcurrentLinkedQueue<Diagnostic> diagnostics = new ConcurrentLinkedQueue<>();
		McpUnknownMirroredHeaderNameDiagnostics recorder =
				new McpUnknownMirroredHeaderNameDiagnostics(() -> 0L,
						Optional.of((endpointPath, headerName) -> diagnostics.add(
								new Diagnostic(endpointPath, headerName))));
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(16);
		List<Future<?>> observations = new ArrayList<>();

		try {
			for (int index = 0; index < observationCount; index++)
				observations.add(executor.submit(() -> {
					start.await();
					recorder.observe("/mcp", "Mcp-Param-Repeated");
					return null;
				}));
			start.countDown();
			for (Future<?> observation : observations)
				observation.get(5, TimeUnit.SECONDS);
		} finally {
			start.countDown();
			executor.shutdownNow();
		}

		Assertions.assertEquals(
				McpUnknownMirroredHeaderNameDiagnostics.MAXIMUM_EVENTS_PER_WINDOW,
				diagnostics.size());
		Assertions.assertTrue(diagnostics.stream().allMatch(diagnostic ->
				diagnostic.equals(new Diagnostic("/mcp", "Mcp-Param-Repeated"))));
	}

	@Test
	public void sanitizerPreservesAsciiTokensAndTruncatesTo128Bytes() {
		String tokenName = "Mcp-Param-AZaz09!#$%&'*+-.^_`|~";
		Assertions.assertEquals(tokenName,
				McpUnknownMirroredHeaderNameDiagnostics.sanitizeHeaderName(tokenName));
		Assertions.assertEquals("Mcp-Param-a_b_c____",
				McpUnknownMirroredHeaderNameDiagnostics.sanitizeHeaderName(
						"Mcp-Param-a b:c/\t\u00E9\u03A9"));

		String maximumName = "Mcp-Param-" + "x".repeat(
				McpUnknownMirroredHeaderNameDiagnostics.MAXIMUM_DISPLAYED_NAME_BYTES
						- "Mcp-Param-".length());
		String overlongName = maximumName + "discarded";
		String sanitized = McpUnknownMirroredHeaderNameDiagnostics
				.sanitizeHeaderName(overlongName);

		Assertions.assertEquals(maximumName, sanitized);
		Assertions.assertEquals(
				McpUnknownMirroredHeaderNameDiagnostics.MAXIMUM_DISPLAYED_NAME_BYTES,
				sanitized.getBytes(StandardCharsets.US_ASCII).length);
		Assertions.assertFalse(sanitized.contains("discarded"));
	}

	@Test
	public void disabledAndThrowingConsumersAreContainedAndAttemptsConsumeBudget() {
		AtomicInteger disabledClockCalls = new AtomicInteger();
		McpUnknownMirroredHeaderNameDiagnostics disabled =
				new McpUnknownMirroredHeaderNameDiagnostics(() -> {
					disabledClockCalls.incrementAndGet();
					return 0L;
				}, Optional.empty());
		Assertions.assertDoesNotThrow(() -> disabled.observe(
				"/mcp", "Mcp-Param-Disabled"));
		Assertions.assertEquals(0, disabledClockCalls.get(),
				"Disabled diagnostics must not consult the clock.");

		AtomicLong nowNanos = new AtomicLong();
		AtomicInteger attempts = new AtomicInteger();
		McpUnknownMirroredHeaderNameDiagnostics throwing =
				new McpUnknownMirroredHeaderNameDiagnostics(nowNanos::get,
						Optional.of((endpointPath, headerName) -> {
							attempts.incrementAndGet();
							throw new AssertionError("expected diagnostic sink failure");
						}));

		Assertions.assertDoesNotThrow(() -> {
			for (int index = 0; index < 20; index++)
				throwing.observe("/mcp", "Mcp-Param-Throwing-" + index);
		});
		Assertions.assertEquals(10, attempts.get(),
				"Failed delivery attempts must consume the bounded budget.");

		nowNanos.set(McpUnknownMirroredHeaderNameDiagnostics.WINDOW_NANOSECONDS);
		Assertions.assertDoesNotThrow(() -> throwing.observe(
				"/mcp", "Mcp-Param-After-Window"));
		Assertions.assertEquals(11, attempts.get());
	}

	private static McpUnknownMirroredHeaderNameDiagnostics diagnostics(
			McpApplicationClock clock, List<Diagnostic> diagnostics) {
		return new McpUnknownMirroredHeaderNameDiagnostics(clock,
				Optional.of((endpointPath, headerName) -> diagnostics.add(
						new Diagnostic(endpointPath, headerName))));
	}

	private record Diagnostic(String endpointPath, String headerName) {
	}
}
