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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Deterministic, short checks for the standalone operational child harness. */
public final class OperationalHistoryHarnessSelfTest {
  private OperationalHistoryHarnessSelfTest() {}

  public static void main(String[] arguments) throws Exception {
    OperationalHistoryHarness.Policy policy = new OperationalHistoryHarness.Policy(
        5, 16, 400, 30, 21_600, 15, 1, "0".repeat(64),
        600, 7_200, 10_000);
    require(policy.isRegisteredProductionPolicy(), "registered policy rejected");
    require(policy.totalSeconds() == 22_200L, "wrong total interval");
    require(policy.requiredSamples() == 4_441, "wrong sample count");
    require(OperationalHistoryHarness.expectedOperations(16, 1, 7_200)
        == 115_200L, "wrong operation count");
    require(OperationalHistoryHarness.conservativeDrainSeconds(0.0) == 0L,
        "zero drain must remain zero");
    require(OperationalHistoryHarness.conservativeDrainSeconds(0.001) == 1L,
        "fractional drain must be rounded up");
    require(OperationalHistoryHarness.conservativeDrainSeconds(29.001) == 30L,
        "drain evidence must round conservatively");
    expectFailure(() -> OperationalHistoryHarness.conservativeDrainSeconds(-0.001));
    require(OperationalHistoryHarness.registeredOperationPeriodNanoseconds(1)
        == 1_000_000_000L, "wrong registered operation period");
    require(OperationalHistoryHarness.operationStartWithinRegisteredCadence(
        10L, 1_000_000_010L, 1), "one-period lateness must remain admissible");
    require(!OperationalHistoryHarness.operationStartWithinRegisteredCadence(
        10L, 1_000_000_011L, 1), "catch-up start beyond one period must fail");
    require(OperationalHistoryHarness.registeredEarliestOperationStartNanoseconds(
        1_000_000_000L, 900_000_000L, 1) == 1_900_000_000L,
        "late client must preserve one full period before its next start");
    require(OperationalHistoryHarness.registeredEarliestOperationStartNanoseconds(
        2_000_000_000L, 500_000_000L, 1) == 2_000_000_000L,
        "on-time schedule must remain authoritative");
    expectFailure(() ->
        OperationalHistoryHarness.registeredOperationPeriodNanoseconds(3));
    require(OperationalHistoryHarness.conservativeDrainSecondsFromNanoseconds(0L) == 0L,
        "zero-nanosecond drain must remain zero");
    require(OperationalHistoryHarness.conservativeDrainSecondsFromNanoseconds(1L) == 1L,
        "subsecond record delivery must round up");
    require(OperationalHistoryHarness.conservativeDrainSecondsFromNanoseconds(
        30_000_000_000L) == 30L, "exact drain boundary must remain admissible");
    require(OperationalHistoryHarness.conservativeDrainSecondsFromNanoseconds(
        30_000_000_001L) == 31L, "over-boundary drain must fail closed");
    expectFailure(() ->
        OperationalHistoryHarness.conservativeDrainSecondsFromNanoseconds(-1L));
    require(OperationalHistoryHarness.logDeliveryDrainNanoseconds(10L, 40L) == 30L,
        "per-record delivery lag was not measured from acceptance");
    expectFailure(() ->
        OperationalHistoryHarness.logDeliveryDrainNanoseconds(40L, 10L));

    McpMetricsEvent validCompletion = McpMetricsEvent.requestFinished(
        "/operational/mcp", "tools/call", McpRequestOutcome.COMPLETE,
        Duration.ofMillis(1));
    require(OperationalHistoryHarness.isRegisteredMcpCompletionEvent(validCompletion),
        "registered MCP completion rejected");
    require(!OperationalHistoryHarness.isRegisteredMcpCompletionEvent(
        McpMetricsEvent.requestFinished(
            "/operational/mcp", "tools/call", McpRequestOutcome.INTERNAL_ERROR,
            Duration.ofMillis(1))), "non-COMPLETE MCP outcome accepted");
    require(!OperationalHistoryHarness.isRegisteredMcpCompletionEvent(
        McpMetricsEvent.requestFinished(
            "/attacker", "tools/call", McpRequestOutcome.COMPLETE,
            Duration.ofMillis(1))), "unregistered MCP endpoint accepted");

    byte[] selfTestTraceKey = sequentialBytes(0x21);
    String expectedSelfTestToken =
        OperationalHistoryHarness.expectedTraceToken(selfTestTraceKey, 1L);
    require(expectedSelfTestToken.equals("JJvCkso54b1BjU1oXbvF3A"),
        "independent self-test HMAC vector drifted");
    byte[] productionTraceKey = sequentialBytes(0x41);
    require(OperationalHistoryHarness.expectedTraceToken(productionTraceKey, 1L)
        .equals("SVUOUkOK2wzZpCjuGZDc4Q"),
        "first production HMAC vector drifted");
    require(OperationalHistoryHarness.expectedTraceToken(productionTraceKey, 115_200L)
        .equals("C6ncK64NPli9w3j2JZy53A"),
        "last production HMAC vector drifted");
    String traceMessage = "tokenFormat=soklet-mcp-trace-correlation-v1;"
        + "keyId=operational-self-test;token=" + expectedSelfTestToken;
    LogEvent traceLog = LogEvent.with(
        LogEventType.MCP_TRACE_CORRELATION, traceMessage).build();
    require(OperationalHistoryHarness.isRegisteredTraceLog(
        traceLog, "operational-self-test"), "registered trace log rejected");
    require(!OperationalHistoryHarness.isRegisteredTraceLog(LogEvent.with(
        LogEventType.MCP_TRACE_CORRELATION,
        traceMessage + ";traceId=" + "0".repeat(32)).build(),
        "operational-self-test"), "raw trace ID was accepted");
    require(!OperationalHistoryHarness.isRegisteredTraceLog(LogEvent.with(
        LogEventType.MCP_TRACE_CORRELATION, traceMessage)
        .throwable(new IllegalStateException("attached"))
        .build(), "operational-self-test"), "trace-log attachment was accepted");
    require(!OperationalHistoryHarness.isRegisteredTraceLog(
        traceLog, "wrong-key"), "wrong trace-correlation key was accepted");
    String wrongWellFormedToken = "A".repeat(22);
    LogEvent wrongWellFormedTraceLog = traceLog(
        "operational-self-test", wrongWellFormedToken);
    require(OperationalHistoryHarness.isRegisteredTraceLog(
        wrongWellFormedTraceLog, "operational-self-test"),
        "well-formed adversarial token must reach exact-token validation");

    OperationalHistoryHarness.TelemetryAudit duplicateMissingAudit =
        new OperationalHistoryHarness.TelemetryAudit(
            "operational-self-test", selfTestTraceKey, 1L, 2L);
    List<String> duplicateMissingOutcomes = new ArrayList<>();
    try {
      duplicateMissingAudit.beginOperationalWindow();
      duplicateMissingAudit.startTraceOperation(1L, System.nanoTime());
      duplicateMissingAudit.startTraceOperation(2L, System.nanoTime());
      duplicateMissingAudit.didReceiveLogEvent(traceLog);
      duplicateMissingAudit.didReceiveLogEvent(traceLog);
      duplicateMissingAudit.completeTraceOperation(1L, System.nanoTime());
      duplicateMissingAudit.completeTraceOperation(2L, System.nanoTime());
      duplicateMissingAudit.awaitDrained(1, duplicateMissingOutcomes);
      duplicateMissingAudit.validateTraceDelivery(2L, duplicateMissingOutcomes);
      require(duplicateMissingAudit.logRecordsObserved() == 1L,
          "duplicate token incorrectly canceled a missing token");
      require(containsOutcome(duplicateMissingOutcomes,
          "MCP trace-log delivery mismatch"),
          "missing exact token was not rejected");
      require(containsOutcome(duplicateMissingOutcomes,
          "Invalid MCP trace-log records observed"),
          "duplicate exact token was not rejected");
    } finally {
      duplicateMissingAudit.close();
    }

    OperationalHistoryHarness.TelemetryAudit strictTokenAudit =
        new OperationalHistoryHarness.TelemetryAudit(
            "operational-self-test", selfTestTraceKey, 1L, 1L);
    List<String> strictTokenOutcomes = new ArrayList<>();
    try {
      strictTokenAudit.beginOperationalWindow();
      strictTokenAudit.didReceiveLogEvent(traceLog);
      strictTokenAudit.startTraceOperation(1L, System.nanoTime());
      strictTokenAudit.didReceiveLogEvent(wrongWellFormedTraceLog);
      strictTokenAudit.didReceiveLogEvent(traceLog);
      strictTokenAudit.completeTraceOperation(1L, System.nanoTime());
      strictTokenAudit.awaitExpectedTraceRecords(
          1L, System.nanoTime() + TimeUnit.SECONDS.toNanos(1), strictTokenOutcomes);
      strictTokenAudit.awaitDrained(1, strictTokenOutcomes);
      strictTokenAudit.validateTraceDelivery(1L, strictTokenOutcomes);
      require(strictTokenAudit.logRecordsObserved() == 1L,
          "exact expected token was not counted once");
      require(containsOutcome(strictTokenOutcomes,
          "Invalid MCP trace-log records observed"),
          "premature or unexpected token was not rejected");
    } finally {
      strictTokenAudit.close();
    }

    OperationalHistoryHarness.TelemetryAudit missingAudit =
        new OperationalHistoryHarness.TelemetryAudit(
            "operational-self-test", selfTestTraceKey, 1L, 1L);
    List<String> missingOutcomes = new ArrayList<>();
    try {
      missingAudit.beginOperationalWindow();
      missingAudit.startTraceOperation(1L, System.nanoTime());
      missingAudit.completeTraceOperation(1L, System.nanoTime());
      missingAudit.awaitExpectedTraceRecords(
          1L, System.nanoTime(), missingOutcomes);
      missingAudit.validateTraceDelivery(1L, missingOutcomes);
      require(containsOutcome(missingOutcomes,
          "MCP trace-log phase drain mismatch"),
          "missing callback evaded the phase drain check");
    } finally {
      missingAudit.close();
    }

    OperationalHistoryHarness.TelemetryAudit.TraceExpectation boundary =
        new OperationalHistoryHarness.TelemetryAudit.TraceExpectation(
            1L, expectedSelfTestToken);
    require(boundary.markStarted(100L), "trace boundary start was rejected");
    require(boundary.markCompleted(200L), "trace boundary completion was rejected");
    require(boundary.markAccepted(200L), "trace boundary callback was rejected");
    require(boundary.markDrained(30_000_000_200L),
        "trace boundary drain was rejected");
    require(boundary.deliveryDrainNanoseconds() == 30_000_000_000L,
        "exact 30-second callback drain boundary drifted");
    OperationalHistoryHarness.TelemetryAudit.TraceExpectation late =
        new OperationalHistoryHarness.TelemetryAudit.TraceExpectation(
            1L, expectedSelfTestToken);
    require(late.markStarted(100L), "late trace start was rejected");
    require(late.markCompleted(200L), "late trace completion was rejected");
    require(late.markAccepted(200L), "late trace callback was rejected");
    require(late.markDrained(30_000_000_201L), "late trace drain was rejected");
    require(late.deliveryDrainNanoseconds() == 30_000_000_001L,
        "over-boundary callback drain was not preserved");
    OperationalHistoryHarness.TelemetryAudit lateAudit =
        new OperationalHistoryHarness.TelemetryAudit();
    List<String> lateOutcomes = new ArrayList<>();
    try {
      lateAudit.beginOperationalWindow();
      lateAudit.observeTraceDeliveryDrain(late);
      lateAudit.validateDrainMaximum(30, lateOutcomes);
      require(containsOutcome(lateOutcomes,
          "Log record delivery exceeded drain policy"),
          "over-boundary callback drain did not fail the audit");
    } finally {
      lateAudit.close();
    }

    ExecutorService blockedWarmupWorker = Executors.newSingleThreadExecutor();
    CountDownLatch warmupWorkerEntered = new CountDownLatch(1);
    CountDownLatch neverReleased = new CountDownLatch(1);
    blockedWarmupWorker.submit(() -> {
      warmupWorkerEntered.countDown();
      try {
        neverReleased.await();
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
      }
    });
    require(warmupWorkerEntered.await(1, TimeUnit.SECONDS),
        "blocked warmup worker did not start");
    require(OperationalHistoryHarness.cancelAndAwaitExecutorTermination(
        blockedWarmupWorker, 1L),
        "warmup cancellation did not await worker termination");

    Set<String> firstCycle = new java.util.HashSet<>();
    OperationalHistoryHarness.CanaryTracker tracker =
        new OperationalHistoryHarness.CanaryTracker("http", 10_000);
    for (int ordinal = 0; ordinal < 115_200; ++ordinal) {
      String canary = OperationalHistoryHarness.canaryFor("http", ordinal, policy);
      tracker.observe(canary);
      if (ordinal < 10_000)
        firstCycle.add(canary);
    }
    require(firstCycle.size() == 10_000, "first canary cycle is not unique");
    require(tracker.uniqueCount() == 10_000, "tracker did not observe all canaries");
    require(OperationalHistoryHarness.canaryFor("http", 0, policy)
        .equals("soklet-operational-canary-http-00400"),
        "deterministic seed was not applied");
    expectFailure(() -> tracker.observe("soklet-operational-canary-http-10000"));
    expectFailure(() -> tracker.observe("private-value"));
    tracker.reset();
    require(tracker.uniqueCount() == 0, "tracker reset failed");

    OperationalHistoryHarness.ResourceSnapshot baseline =
        new OperationalHistoryHarness.ResourceSnapshot(20, 10_000, 8);
    OperationalHistoryHarness.ResourceSnapshot finalSnapshot =
        new OperationalHistoryHarness.ResourceSnapshot(18, 12_000, 11);
    OperationalHistoryHarness.ResourceSnapshot growth =
        OperationalHistoryHarness.ResourceSnapshot.growth(baseline, finalSnapshot);
    require(growth.equals(new OperationalHistoryHarness.ResourceSnapshot(0, 2_000, 3)),
        "resource growth must clamp only negative deltas");

    String json = OperationalHistoryHarness.Json.canonical(Map.of(
        "z", Set.of(),
        "a", Map.of("text", "line\nvalue", "number", 3)));
    require(json.equals("""
        {
          "a": {
            "number": 3,
            "text": "line\\nvalue"
          },
          "z": []
        }
        """), "canonical JSON rendering drifted: " + json);
    String drainJson = OperationalHistoryHarness.Json.canonical(Map.of(
        "fractional", OperationalHistoryHarness.conservativeDrainSeconds(0.001),
        "zero", OperationalHistoryHarness.conservativeDrainSeconds(0.0)));
    require(drainJson.equals("""
        {
          "fractional": 1,
          "zero": 0
        }
        """), "drain JSON must use canonical integral numbers: " + drainJson);

    OperationalHistoryHarness.selfTestCandidateRuntime();

    System.out.println("OperationalHistoryHarnessSelfTest PASS assertions=63");
  }

  private static byte[] sequentialBytes(int first) {
    byte[] bytes = new byte[32];
    for (int index = 0; index < bytes.length; ++index)
      bytes[index] = (byte) (first + index);
    return bytes;
  }

  private static LogEvent traceLog(String keyId, String token) {
    return LogEvent.with(LogEventType.MCP_TRACE_CORRELATION,
        "tokenFormat=soklet-mcp-trace-correlation-v1;keyId="
            + keyId + ";token=" + token).build();
  }

  private static boolean containsOutcome(List<String> outcomes, String fragment) {
    return outcomes.stream().anyMatch(outcome -> outcome.contains(fragment));
  }

  private static void expectFailure(Runnable operation) {
    try {
      operation.run();
      throw new AssertionError("Expected operation to fail");
    } catch (IllegalArgumentException expected) {
      // Expected.
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition)
      throw new AssertionError(message);
  }
}
