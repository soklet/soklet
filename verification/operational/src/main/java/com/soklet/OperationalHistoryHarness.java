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

import com.soklet.MetricsCollector.MetricsFormat;
import com.soklet.MetricsCollector.SnapshotTextOptions;
import com.soklet.annotation.GET;
import com.soklet.annotation.PathParameter;
import com.soklet.annotation.SseEventSource;
import com.sun.management.UnixOperatingSystemMXBean;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Pattern;

/**
 * Candidate-JAR operational load and observation harness.
 *
 * <p>The user-facing producer supplies only the checked-in policy. This child
 * accepts a policy file generated from that registry so two independent JVMs
 * can observe process-specific HTTP and MCP/realtime resources concurrently.
 */
public final class OperationalHistoryHarness {
  static final String CANARY_PREFIX = "soklet-operational-canary-";
  private static final String HOST = "127.0.0.1";
  private static final String HTTP_PATH = "/operational/http";
  private static final String MCP_PATH = "/operational/mcp";
  private static final String MCP_TOOL = "operational.echo";
  private static final String MCP_PROTOCOL_VERSION = "2026-07-28";
  private static final String REALTIME_ROUTE = "/operational/events/{canary}";
  private static final String TRACE_LOG_PREFIX =
      "tokenFormat=soklet-mcp-trace-correlation-v1;keyId=";
  private static final byte[] TRACE_TOKEN_DOMAIN =
      "soklet-mcp-trace-correlation-v1\0".getBytes(StandardCharsets.UTF_8);
  private static final Pattern TRACE_TOKEN_PATTERN = Pattern.compile("[A-Za-z0-9_-]{22}");
  private static final Set<String> TRANSCRIPT_GROUPS = Set.of("http", "mcpAndRealtime");
  private static final Set<String> REGISTERED_LABEL_KEYS = Set.of(
      "code", "comment_type", "drop_reason", "endpoint",
      "handshake_failure_reason", "le", "method", "outcome", "reason",
      "route", "server_type", "status_class", "termination_reason");
  private static final Set<String> REGISTERED_ROUTES = Set.of(
      HTTP_PATH, REALTIME_ROUTE);
  private static final AtomicReference<CanaryTracker> HTTP_TRACKER = new AtomicReference<>();
  private static final AtomicReference<CanaryTracker> REALTIME_TRACKER = new AtomicReference<>();

  private OperationalHistoryHarness() {}

  public static void main(String[] arguments) {
    try {
      run(arguments);
    } catch (Throwable throwable) {
      throwable.printStackTrace(System.err);
      System.exit(1);
    }
  }

  private static void run(String[] arguments) throws Exception {
    Map<String, String> options = parseArguments(arguments);
    String group = options.get("--group");
    if (!TRANSCRIPT_GROUPS.contains(group))
      throw new IllegalArgumentException("--group must be http or mcpAndRealtime");
    Path output = absolutePath(options.get("--output"), "--output");
    Path policyPath = absolutePath(options.get("--policy"), "--policy");
    if (Files.exists(output))
      throw new IllegalArgumentException("Output already exists: " + output);
    Instant startAt = exactUtc(options.get("--start-at"));
    Policy policy = Policy.load(policyPath);
    if (!policy.isRegisteredProductionPolicy())
      throw new IllegalArgumentException("Operational child requires the exact registered production policy");
    if (!ManagementFactory.getOperatingSystemMXBean().getClass().getName().contains("OperatingSystem"))
      throw new IllegalStateException("Operating-system resource MXBean is unavailable");

    HarnessRun run = group.equals("http")
        ? runHttp(policy, startAt)
        : runMcpAndRealtime(policy, startAt);
    Files.writeString(output, Json.canonical(run.transcript()), StandardCharsets.UTF_8,
        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    if (!run.outcomes().isEmpty())
      throw new IllegalStateException("Operational load failed: " + run.outcomes());
    System.out.printf(Locale.ROOT,
        "operational child PASS group=%s samples=%d%n", group, run.samples().size());
  }

  private static HarnessRun runHttp(Policy policy, Instant startAt) throws Exception {
    CanaryTracker tracker = new CanaryTracker("http", policy.uniqueCanaries());
    HTTP_TRACKER.set(tracker);
    TelemetryAudit telemetry = new TelemetryAudit();
    MetricAudit metrics = new MetricAudit();
    int port = findFreePort();
    HttpServer server = HttpServer.withPort(port)
        .host(HOST)
        .concurrency(Math.max(32, policy.clientsPerScenario() * 2))
        .requestHeaderTimeout(Duration.ofSeconds(5))
        .requestBodyTimeout(Duration.ofSeconds(5))
        .requestHandlerTimeout(Duration.ofSeconds(5))
        .build();
    SokletConfig config = SokletConfig.withHttpServer(server)
        .resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(HttpResource.class)))
        .metricsCollector(metrics.collector())
        .lifecycleObserver(telemetry)
        .lifecyclePolicy(lifecyclePolicy())
        .build();
    metrics.initialize(config);
    HttpClient client = httpClient();
    List<String> outcomes = new ArrayList<>();
    List<Sample> samples = List.of();
    List<ScenarioResult> scenarios = new ArrayList<>();
    double drainSeconds = 0.0;

    try (Soklet soklet = Soklet.fromConfig(config)) {
      soklet.start();
      warmUp(policy.clientsPerScenario(), index ->
          httpOperation(client, port, canaryFor("http", index, policy)));
      tracker.reset();
      telemetry.awaitDrained(policy.drainMaximumSeconds(), outcomes);
      metrics.beginOperationalWindow();
      telemetry.beginOperationalWindow();
      forceGc();
      long baseNanoseconds = awaitStart(startAt);
      SampleRecorder recorder = new SampleRecorder(policy, baseNanoseconds, metrics, telemetry);
      Thread sampler = recorder.start();
      ScenarioResult result = runScenario(
          "http", policy, baseNanoseconds, 0, tracker,
          (clientIndex, iteration, canary) -> httpOperation(client, port, canary),
          outcomes);
      scenarios.add(result);
      drainSeconds = Math.max(drainSeconds, result.drainSeconds());
      drainSeconds = Math.max(drainSeconds,
          drainTelemetry(telemetry, policy, outcomes));
      awaitDeadline(baseNanoseconds, policy.totalSeconds());
      sampler.join(TimeUnit.SECONDS.toMillis(policy.maximumSampleGapSeconds() * 2L));
      samples = recorder.finish(outcomes);
      drainSeconds = Math.max(drainSeconds,
          drainTelemetry(telemetry, policy, outcomes));
    } finally {
      metrics.scanMetrics();
      drainSeconds = Math.max(drainSeconds,
          drainTelemetry(telemetry, policy, outcomes));
      telemetry.close();
      HTTP_TRACKER.set(null);
    }
    long expected = expectedOperations(policy.clientsPerScenario(),
        policy.operationsPerClientPerSecond(), policy.secondsPerScenario());
    metrics.validateHttpDelivery(expected, outcomes);
    telemetry.validateTraceDelivery(0L, outcomes);
    telemetry.validateDrainMaximum(policy.drainMaximumSeconds(), outcomes);
    validateScenarioAudit(policy, scenarios, outcomes);
    return HarnessRun.from("http", policy, samples, scenarios, outcomes,
        drainSeconds, metrics, telemetry);
  }

  private static HarnessRun runMcpAndRealtime(Policy policy, Instant startAt) throws Exception {
    CanaryTracker mcpTracker = new CanaryTracker("mcp", policy.uniqueCanaries());
    CanaryTracker realtimeTracker = new CanaryTracker("realtime", policy.uniqueCanaries());
    REALTIME_TRACKER.set(realtimeTracker);
    long expectedMcpOperations = expectedOperations(policy.clientsPerScenario(),
        policy.operationsPerClientPerSecond(), policy.secondsPerScenario());
    byte[] traceKeyBytes = new byte[32];
    for (int index = 0; index < traceKeyBytes.length; ++index)
      traceKeyBytes[index] = (byte) (0x41 + index);
    TelemetryAudit telemetry = new TelemetryAudit(
        "operational-history", traceKeyBytes, 1L, expectedMcpOperations);
    MetricAudit metrics = new MetricAudit();
    int ssePort = findFreePort();
    SseServer sseServer = SseServer.withPort(ssePort)
        .host(HOST)
        .requestHeaderTimeout(Duration.ofSeconds(5))
        .requestHandlerTimeout(Duration.ofSeconds(5))
        .writeTimeout(Duration.ofSeconds(5))
        .heartbeatInterval(Duration.ofSeconds(15))
        .concurrentConnectionLimit(Math.max(64, policy.clientsPerScenario() * 4))
        .build();
    McpToolRegistration<McpJsonObject> tool = McpToolRegistration.withName(MCP_TOOL)
        .jsonObjectArguments()
        .handler((context, arguments, features) -> {
          McpJsonValue value = arguments.getRawArguments().find("canary")
              .orElseThrow(() -> new IllegalArgumentException("Missing canary"));
          if (!(value instanceof McpJsonString string))
            throw new IllegalArgumentException("Canary must be a JSON string");
          String canary = string.getValue();
          mcpTracker.observe(canary);
          return McpCompleteResult.fromToolText(canary);
        })
        .build();
    McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH, McpImplementation.withNameAndVersion(
            "soklet-operational-history", "4.0.0").build())
        .addTool(tool)
        .build();
    McpServer mcpServer = McpServer.withPort(0).endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
        .host(HOST)
        .toolRateLimiter(context -> McpRateLimitDecision.allowed())
        .corsAuthorizer(CorsAuthorizer.rejectAllInstance())
        .allowedHosts(Set.of(HOST))
        .traceCorrelationKey(McpTraceCorrelationKey.fromIdAndBytes(
            "operational-history", traceKeyBytes))
        .build();
    SokletConfig config = SokletConfig.withMcpServer(mcpServer)
        .sseServer(sseServer)
        .resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(RealtimeResource.class)))
        .metricsCollector(metrics.collector())
        .lifecycleObserver(telemetry)
        .lifecyclePolicy(lifecyclePolicy())
        .build();
    metrics.initialize(config);
    HttpClient client = httpClient();
    List<String> outcomes = new ArrayList<>();
    List<Sample> samples = List.of();
    List<ScenarioResult> scenarios = new ArrayList<>();
    double drainSeconds = 0.0;

    try (Soklet soklet = Soklet.fromConfig(config)) {
      soklet.start();
      int mcpPort = mcpServer.getDiagnostics().getBoundAddress().orElseThrow().getPort();
      warmUp(policy.clientsPerScenario(), index ->
          mcpOperation(client, mcpPort, canaryFor("mcp", index, policy),
              expectedMcpOperations + index + 1L));
      warmUp(policy.clientsPerScenario(), index ->
          realtimeOperation(ssePort, canaryFor("realtime", index, policy)));
      mcpTracker.reset();
      realtimeTracker.reset();
      telemetry.awaitDrained(policy.drainMaximumSeconds(), outcomes);
      metrics.beginOperationalWindow();
      telemetry.beginOperationalWindow();
      forceGc();
      long baseNanoseconds = awaitStart(startAt);
      SampleRecorder recorder = new SampleRecorder(policy, baseNanoseconds, metrics, telemetry);
      Thread sampler = recorder.start();
      ScenarioResult mcp = runScenario(
          "mcp", policy, baseNanoseconds, 1, mcpTracker,
          (clientIndex, iteration, canary) -> mcpOperation(
              client, mcpPort, canary,
              (long) clientIndex * policy.secondsPerScenario() + iteration + 1L,
              telemetry),
          outcomes);
      scenarios.add(mcp);
      drainSeconds = Math.max(drainSeconds, mcp.drainSeconds());
      long mcpDrainDeadline = Math.addExact(
          Math.addExact(baseNanoseconds,
              Math.multiplyExact(2L * policy.secondsPerScenario(), 1_000_000_000L)),
          TimeUnit.SECONDS.toNanos(policy.drainMaximumSeconds()));
      drainSeconds = Math.max(drainSeconds,
          telemetry.awaitExpectedTraceRecords(
              expectedMcpOperations, mcpDrainDeadline, outcomes));
      drainSeconds = Math.max(drainSeconds,
          drainTelemetry(telemetry, policy, outcomes));
      ScenarioResult realtime = runScenario(
          "realtime", policy, baseNanoseconds, 2, realtimeTracker,
          (clientIndex, iteration, canary) -> realtimeOperation(ssePort, canary),
          outcomes);
      scenarios.add(realtime);
      drainSeconds = Math.max(drainSeconds, realtime.drainSeconds());
      drainSeconds = Math.max(drainSeconds,
          drainTelemetry(telemetry, policy, outcomes));
      awaitDeadline(baseNanoseconds, policy.totalSeconds());
      sampler.join(TimeUnit.SECONDS.toMillis(policy.maximumSampleGapSeconds() * 2L));
      samples = recorder.finish(outcomes);
      drainSeconds = Math.max(drainSeconds,
          drainTelemetry(telemetry, policy, outcomes));
    } finally {
      metrics.scanMetrics();
      drainSeconds = Math.max(drainSeconds,
          drainTelemetry(telemetry, policy, outcomes));
      telemetry.close();
      REALTIME_TRACKER.set(null);
    }
    metrics.validateMcpAndRealtimeDelivery(
        expectedMcpOperations, expectedMcpOperations, outcomes);
    telemetry.validateTraceDelivery(expectedMcpOperations, outcomes);
    telemetry.validateDrainMaximum(policy.drainMaximumSeconds(), outcomes);
    validateScenarioAudit(policy, scenarios, outcomes);
    return HarnessRun.from("mcpAndRealtime", policy, samples, scenarios,
        outcomes, drainSeconds, metrics, telemetry);
  }

  private static LifecyclePolicy lifecyclePolicy() {
    return LifecyclePolicy.builder()
        .startupTimeout(Duration.ofSeconds(10))
        .startupCancelationTimeout(Duration.ofSeconds(2))
        .gracefulShutdownTimeout(Duration.ofSeconds(5))
        .forcedShutdownTimeout(Duration.ofSeconds(2))
        .build();
  }

  private static HttpClient httpClient() {
    return HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .version(HttpClient.Version.HTTP_1_1)
        .build();
  }

  static void selfTestCandidateRuntime() throws Exception {
    CanaryTracker httpTracker = new CanaryTracker("http", 10_000);
    HTTP_TRACKER.set(httpTracker);
    MetricAudit httpMetrics = new MetricAudit();
    TelemetryAudit httpTelemetry = new TelemetryAudit();
    List<String> httpOutcomes = new ArrayList<>();
    int httpPort = findFreePort();
    HttpServer httpServer = HttpServer.withPort(httpPort)
        .host(HOST)
        .requestHeaderTimeout(Duration.ofSeconds(5))
        .requestHandlerTimeout(Duration.ofSeconds(5))
        .build();
    SokletConfig httpConfig = SokletConfig.withHttpServer(httpServer)
        .resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(HttpResource.class)))
        .metricsCollector(httpMetrics.collector())
        .lifecycleObserver(httpTelemetry)
        .lifecyclePolicy(lifecyclePolicy())
        .build();
    httpMetrics.initialize(httpConfig);
    try (Soklet soklet = Soklet.fromConfig(httpConfig)) {
      soklet.start();
      httpMetrics.beginOperationalWindow();
      httpTelemetry.beginOperationalWindow();
      httpOperation(httpClient(), httpPort,
          "soklet-operational-canary-http-00001");
      httpMetrics.scanMetrics();
    } finally {
      httpTelemetry.awaitDrained(5, httpOutcomes);
      httpTelemetry.close();
      HTTP_TRACKER.set(null);
    }
    httpMetrics.validateHttpDelivery(1L, httpOutcomes);
    httpTelemetry.validateTraceDelivery(0L, httpOutcomes);
    httpTelemetry.validateDrainMaximum(5, httpOutcomes);
    if (httpTracker.uniqueCount() != 1
        || httpMetrics.metricSamplesObserved() == 0
        || httpMetrics.frameworkMetricCardinality() != 0
        || httpMetrics.rejectedMetricDeliveries() != 0
        || !httpOutcomes.isEmpty())
      throw new AssertionError("HTTP runtime telemetry self-test failed: " + httpOutcomes);

    CanaryTracker mcpTracker = new CanaryTracker("mcp", 10_000);
    CanaryTracker realtimeTracker = new CanaryTracker("realtime", 10_000);
    REALTIME_TRACKER.set(realtimeTracker);
    MetricAudit metrics = new MetricAudit();
    List<String> outcomes = new ArrayList<>();
    int ssePort = findFreePort();
    SseServer sseServer = SseServer.withPort(ssePort)
        .host(HOST)
        .requestHeaderTimeout(Duration.ofSeconds(5))
        .writeTimeout(Duration.ofSeconds(5))
        .build();
    McpToolRegistration<McpJsonObject> tool = McpToolRegistration.withName(MCP_TOOL)
        .jsonObjectArguments()
        .handler((context, arguments, features) -> {
          McpJsonValue value = arguments.getRawArguments().find("canary").orElseThrow();
          if (!(value instanceof McpJsonString string))
            throw new IllegalArgumentException("Canary must be a JSON string");
          mcpTracker.observe(string.getValue());
          return McpCompleteResult.fromToolText(string.getValue());
        })
        .build();
    McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH, McpImplementation.withNameAndVersion(
            "soklet-operational-self-test", "4.0.0").build())
        .addTool(tool)
        .build();
    byte[] traceKeyBytes = new byte[32];
    for (int index = 0; index < traceKeyBytes.length; ++index)
      traceKeyBytes[index] = (byte) (0x21 + index);
    TelemetryAudit telemetry = new TelemetryAudit(
        "operational-self-test", traceKeyBytes, 1L, 1L);
    McpServer mcpServer = McpServer.withPort(0).endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
        .host(HOST)
        .toolRateLimiter(context -> McpRateLimitDecision.allowed())
        .corsAuthorizer(CorsAuthorizer.rejectAllInstance())
        .allowedHosts(Set.of(HOST))
        .traceCorrelationKey(McpTraceCorrelationKey.fromIdAndBytes(
            "operational-self-test", traceKeyBytes))
        .build();
    SokletConfig config = SokletConfig.withMcpServer(mcpServer)
        .sseServer(sseServer)
        .resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(RealtimeResource.class)))
        .metricsCollector(metrics.collector())
        .lifecycleObserver(telemetry)
        .lifecyclePolicy(lifecyclePolicy())
        .build();
    metrics.initialize(config);
    try (Soklet soklet = Soklet.fromConfig(config)) {
      soklet.start();
      int mcpPort = mcpServer.getDiagnostics().getBoundAddress().orElseThrow().getPort();
      metrics.beginOperationalWindow();
      telemetry.beginOperationalWindow();
      mcpOperation(httpClient(), mcpPort,
          "soklet-operational-canary-mcp-00001", 1,
          telemetry);
      realtimeOperation(ssePort,
          "soklet-operational-canary-realtime-00001");
      metrics.scanMetrics();
    } finally {
      metrics.scanMetrics();
      telemetry.awaitExpectedTraceRecords(
          1L, System.nanoTime() + TimeUnit.SECONDS.toNanos(5), outcomes);
      telemetry.awaitDrained(5, outcomes);
      telemetry.close();
      REALTIME_TRACKER.set(null);
    }
    metrics.validateMcpAndRealtimeDelivery(1L, 1L, outcomes);
    telemetry.validateTraceDelivery(1L, outcomes);
    telemetry.validateDrainMaximum(5, outcomes);
    if (mcpTracker.uniqueCount() != 1 || realtimeTracker.uniqueCount() != 1
        || metrics.metricEventsObserved() != 1
        || metrics.metricSamplesObserved() == 0
        || telemetry.logRecordsObserved() != 1
        || metrics.frameworkMetricCardinality() != 0
        || metrics.rejectedMetricDeliveries() != 0
        || metrics.sensitiveCanaries() != 0
        || telemetry.sensitiveCanaries() != 0
        || telemetry.droppedLogRecords() != 0
        || !outcomes.isEmpty())
      throw new AssertionError("MCP/realtime runtime telemetry self-test failed: "
          + "mcpUnique=" + mcpTracker.uniqueCount()
          + " realtimeUnique=" + realtimeTracker.uniqueCount()
          + " events=" + metrics.metricEventsObserved()
          + " samples=" + metrics.metricSamplesObserved()
          + " logs=" + telemetry.logRecordsObserved()
          + " cardinality=" + metrics.frameworkMetricCardinality()
          + " rejected=" + metrics.rejectedMetricDeliveries()
          + " metricCanaries=" + metrics.sensitiveCanaries()
          + " logCanaries=" + telemetry.sensitiveCanaries()
          + " dropped=" + telemetry.droppedLogRecords()
          + " outcomes=" + outcomes);
  }

  private static void warmUp(int concurrency, IndexedOperation operation) throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(concurrency);
    ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
    try {
      for (int index = 0; index < concurrency; ++index) {
        int operationIndex = index;
        executor.submit(() -> {
          try {
            operation.run(operationIndex);
          } catch (Throwable throwable) {
            failures.add(throwable);
          }
        });
      }
      executor.shutdown();
      if (!awaitExecutorTermination(executor, 30L))
        throw new IllegalStateException("Operational warmup did not finish");
    } finally {
      if (!executor.isTerminated()
          && !cancelAndAwaitExecutorTermination(executor, 6L)) {
        throw new IllegalStateException(
            "Operational warmup workers did not terminate after cancellation");
      }
    }
    if (!failures.isEmpty())
      throw new IllegalStateException("Operational warmup failed", failures.peek());
  }

  static boolean cancelAndAwaitExecutorTermination(
      ExecutorService executor, long timeoutSeconds) throws InterruptedException {
    if (executor == null)
      throw new IllegalArgumentException("Executor must not be null");
    if (timeoutSeconds <= 0L)
      throw new IllegalArgumentException("Termination timeout must be positive");
    executor.shutdownNow();
    return awaitExecutorTermination(executor, timeoutSeconds);
  }

  private static boolean awaitExecutorTermination(
      ExecutorService executor, long timeoutSeconds) throws InterruptedException {
    return executor.awaitTermination(timeoutSeconds, TimeUnit.SECONDS);
  }

  private static ScenarioResult runScenario(
      String id,
      Policy policy,
      long baseNanoseconds,
      int scenarioOrdinal,
      CanaryTracker tracker,
      Operation operation,
      List<String> outcomes) throws InterruptedException {
    long phaseStart = baseNanoseconds
        + (long) scenarioOrdinal * policy.secondsPerScenario() * 1_000_000_000L;
    long phaseEnd = phaseStart + policy.secondsPerScenario() * 1_000_000_000L;
    awaitDeadlineNanoseconds(phaseStart - 1_000_000_000L);
    ExecutorService executor = Executors.newFixedThreadPool(policy.clientsPerScenario());
    CountDownLatch ready = new CountDownLatch(policy.clientsPerScenario());
    AtomicLong successful = new AtomicLong();
    AtomicInteger failureCount = new AtomicInteger();
    ConcurrentLinkedQueue<String> failureDetails = new ConcurrentLinkedQueue<>();
    for (int clientIndex = 0; clientIndex < policy.clientsPerScenario(); ++clientIndex) {
      int client = clientIndex;
      executor.submit(() -> {
        ready.countDown();
        try {
          long operationPeriod = registeredOperationPeriodNanoseconds(
              policy.operationsPerClientPerSecond());
          long previousActualStart = Math.subtractExact(phaseStart, operationPeriod);
          long phaseDrainDeadline = Math.addExact(phaseEnd,
              TimeUnit.SECONDS.toNanos(policy.drainMaximumSeconds()));
          for (int iteration = 0; iteration < policy.secondsPerScenario(); ++iteration) {
            long scheduledStart = Math.addExact(phaseStart,
                Math.multiplyExact((long) iteration, operationPeriod));
            long earliestStart = registeredEarliestOperationStartNanoseconds(
                scheduledStart, previousActualStart,
                policy.operationsPerClientPerSecond());
            if (earliestStart > phaseDrainDeadline) {
              throw new IllegalStateException(
                  "Operation timeline exceeded the scenario drain bound");
            }
            awaitDeadlineNanoseconds(earliestStart);
            long actualStart = System.nanoTime();
            if (actualStart > phaseDrainDeadline) {
              throw new IllegalStateException(
                  "Operation start exceeded the scenario drain bound");
            }
            if (!operationStartWithinRegisteredCadence(
                scheduledStart, actualStart, policy.operationsPerClientPerSecond())) {
              throw new IllegalStateException(
                  "Operation start missed the registered cadence: latenessNanoseconds="
                      + Math.subtractExact(actualStart, scheduledStart));
            }
            previousActualStart = actualStart;
            long ordinal = (long) client * policy.secondsPerScenario() + iteration;
            String canary = canaryFor(id, ordinal, policy);
            try {
              operation.run(client, iteration, canary);
              successful.incrementAndGet();
            } catch (Throwable throwable) {
              int failures = failureCount.incrementAndGet();
              if (failures <= 100) {
                failureDetails.add("scenario=%s client=%d iteration=%d %s: %s".formatted(
                    id, client, iteration, throwable.getClass().getSimpleName(),
                    Optional.ofNullable(throwable.getMessage()).orElse("<no-message>")));
              }
            }
          }
        } catch (Throwable throwable) {
          failureCount.incrementAndGet();
          failureDetails.add("scenario=%s client=%d setup %s: %s".formatted(
              id, client, throwable.getClass().getSimpleName(),
              Optional.ofNullable(throwable.getMessage()).orElse("<no-message>")));
        }
      });
    }
    if (!ready.await(10, TimeUnit.SECONDS))
      outcomes.add("Scenario clients did not become ready: " + id);
    executor.shutdown();
    long phaseDrainDeadline = Math.addExact(phaseEnd,
        TimeUnit.SECONDS.toNanos(policy.drainMaximumSeconds()));
    long waitNanoseconds = Math.max(0L, phaseDrainDeadline - System.nanoTime());
    if (!executor.awaitTermination(waitNanoseconds, TimeUnit.NANOSECONDS)) {
      outcomes.add("Scenario did not drain within policy: " + id);
      if (!cancelAndAwaitExecutorTermination(executor, 6L)) {
        throw new IllegalStateException(
            "Scenario workers did not terminate after cancellation: " + id);
      }
    }
    long finished = System.nanoTime();
    double drainSeconds = Math.max(0.0, (finished - phaseEnd) / 1_000_000_000.0);
    outcomes.addAll(failureDetails);
    if (failureCount.get() > failureDetails.size())
      outcomes.add("Scenario %s had %d additional failures".formatted(
          id, failureCount.get() - failureDetails.size()));
    return new ScenarioResult(
        id,
        expectedOperations(policy.clientsPerScenario(),
            policy.operationsPerClientPerSecond(), policy.secondsPerScenario()),
        successful.get(),
        tracker.uniqueCount(),
        drainSeconds);
  }

  private static void validateScenarioAudit(
      Policy policy, List<ScenarioResult> scenarios, List<String> outcomes) {
    long expected = expectedOperations(policy.clientsPerScenario(),
        policy.operationsPerClientPerSecond(), policy.secondsPerScenario());
    for (ScenarioResult scenario : scenarios) {
      if (scenario.expectedOperations() != expected
          || scenario.successfulOperations() != expected
          || scenario.uniqueAdversarialDimensionValues() != policy.uniqueCanaries()) {
        outcomes.add("Scenario audit failed for %s: expected=%d successful=%d unique=%d".formatted(
            scenario.id(), expected, scenario.successfulOperations(),
            scenario.uniqueAdversarialDimensionValues()));
      }
      if (scenario.drainSeconds() > policy.drainMaximumSeconds())
        outcomes.add("Scenario drain exceeded policy for " + scenario.id());
    }
  }

  static long expectedOperations(int clients, int operationsPerSecond, int seconds) {
    return Math.multiplyExact(Math.multiplyExact((long) clients, operationsPerSecond), seconds);
  }

  static long conservativeDrainSeconds(double seconds) {
    if (!Double.isFinite(seconds) || seconds < 0.0)
      throw new IllegalArgumentException("Drain seconds must be finite and nonnegative");
    return (long) Math.ceil(seconds);
  }

  static long conservativeDrainSecondsFromNanoseconds(long nanoseconds) {
    if (nanoseconds < 0L)
      throw new IllegalArgumentException("Drain nanoseconds must be nonnegative");
    long wholeSeconds = nanoseconds / 1_000_000_000L;
    return nanoseconds % 1_000_000_000L == 0L
        ? wholeSeconds
        : Math.addExact(wholeSeconds, 1L);
  }

  static long logDeliveryDrainNanoseconds(
      long acceptedNanoseconds, long drainedNanoseconds) {
    if (drainedNanoseconds < acceptedNanoseconds)
      throw new IllegalArgumentException(
          "Log drain timestamp must not precede acceptance");
    return drainedNanoseconds - acceptedNanoseconds;
  }

  static long registeredOperationPeriodNanoseconds(int operationsPerSecond) {
    if (operationsPerSecond <= 0
        || 1_000_000_000L % operationsPerSecond != 0L) {
      throw new IllegalArgumentException(
          "Operations per second must divide one second exactly");
    }
    return 1_000_000_000L / operationsPerSecond;
  }

  static boolean operationStartWithinRegisteredCadence(
      long scheduledNanoseconds, long actualNanoseconds, int operationsPerSecond) {
    if (actualNanoseconds <= scheduledNanoseconds)
      return true;
    long lateness;
    try {
      lateness = Math.subtractExact(actualNanoseconds, scheduledNanoseconds);
    } catch (ArithmeticException exception) {
      return false;
    }
    return lateness <= registeredOperationPeriodNanoseconds(operationsPerSecond);
  }

  static long registeredEarliestOperationStartNanoseconds(
      long scheduledNanoseconds,
      long previousActualNanoseconds,
      int operationsPerSecond) {
    long spacedStart = Math.addExact(previousActualNanoseconds,
        registeredOperationPeriodNanoseconds(operationsPerSecond));
    return Math.max(scheduledNanoseconds, spacedStart);
  }

  static boolean isRegisteredMcpCompletionEvent(McpMetricsEvent event) {
    if (!(event instanceof McpMetricsEvent.RequestFinished finished))
      return false;
    return finished.getEndpointPath().equals(MCP_PATH)
        && finished.getJsonRpcMethod().equals("tools/call")
        && finished.getOutcome() == McpRequestOutcome.COMPLETE
        && !finished.getDuration().isNegative();
  }

  static boolean isRegisteredTraceLog(LogEvent logEvent, String expectedKeyId) {
    return registeredTraceToken(logEvent, expectedKeyId).isPresent();
  }

  static Optional<String> registeredTraceToken(
      LogEvent logEvent, String expectedKeyId) {
    if (logEvent == null || expectedKeyId == null
        || logEvent.getLogEventType() != LogEventType.MCP_TRACE_CORRELATION
        || logEvent.getThrowable().isPresent()
        || logEvent.getRequest().isPresent()
        || logEvent.getResourceMethod().isPresent()
        || logEvent.getMarshaledResponse().isPresent()) {
      return Optional.empty();
    }
    String prefix = TRACE_LOG_PREFIX + expectedKeyId + ";token=";
    String message = logEvent.getMessage();
    if (!message.startsWith(prefix))
      return Optional.empty();
    String token = message.substring(prefix.length());
    return TRACE_TOKEN_PATTERN.matcher(token).matches()
        ? Optional.of(token)
        : Optional.empty();
  }

  static String expectedTraceToken(byte[] keyBytes, long operationOrdinal) {
    if (keyBytes == null || keyBytes.length == 0)
      throw new IllegalArgumentException("Trace-correlation key must not be empty");
    String traceId = traceIdForOperationOrdinal(operationOrdinal);
    byte[] traceIdBytes = new byte[16];
    for (int index = 0; index < traceIdBytes.length; ++index) {
      int high = Character.digit(traceId.charAt(index * 2), 16);
      int low = Character.digit(traceId.charAt(index * 2 + 1), 16);
      if (high < 0 || low < 0)
        throw new IllegalArgumentException("Trace ID must be lowercase hexadecimal");
      traceIdBytes[index] = (byte) ((high << 4) | low);
    }
    byte[] authenticated = new byte[TRACE_TOKEN_DOMAIN.length + traceIdBytes.length];
    System.arraycopy(TRACE_TOKEN_DOMAIN, 0, authenticated, 0, TRACE_TOKEN_DOMAIN.length);
    System.arraycopy(traceIdBytes, 0, authenticated,
        TRACE_TOKEN_DOMAIN.length, traceIdBytes.length);
    byte[] digest = null;
    byte[] tokenBytes = null;
    try {
      Mac hmac = Mac.getInstance("HmacSHA256");
      hmac.init(new SecretKeySpec(keyBytes, "HmacSHA256"));
      digest = hmac.doFinal(authenticated);
      tokenBytes = Arrays.copyOf(digest, 16);
      return Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
    } catch (Exception exception) {
      throw new IllegalStateException("HmacSHA256 is unavailable", exception);
    } finally {
      Arrays.fill(traceIdBytes, (byte) 0);
      Arrays.fill(authenticated, (byte) 0);
      if (digest != null)
        Arrays.fill(digest, (byte) 0);
      if (tokenBytes != null)
        Arrays.fill(tokenBytes, (byte) 0);
    }
  }

  private static String traceIdForOperationOrdinal(long operationOrdinal) {
    if (operationOrdinal < 0L)
      throw new IllegalArgumentException("Operation ordinal must be nonnegative");
    return String.format(Locale.ROOT, "%032x",
        Math.addExact(operationOrdinal, 1L));
  }

  private static double drainTelemetry(
      TelemetryAudit telemetry, Policy policy, List<String> outcomes) {
    double awaited = telemetry.awaitDrained(policy.drainMaximumSeconds(), outcomes);
    return Math.max(awaited, telemetry.maximumOperationalDrainSeconds());
  }

  static String canaryFor(String scenario, long operationOrdinal, Policy policy) {
    long index = Math.floorMod(operationOrdinal + policy.deterministicSeed(), policy.uniqueCanaries());
    return CANARY_PREFIX + scenario + "-" + String.format(Locale.ROOT, "%05d", index);
  }

  private static void httpOperation(HttpClient client, int port, String canary) throws Exception {
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create("http://" + HOST + ":" + port
            + HTTP_PATH + "?canary=" + canary))
        .timeout(Duration.ofSeconds(5))
        .header("X-Operational-Canary", canary)
        .GET()
        .build();
    HttpResponse<String> response = client.send(request,
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() != 200 || !response.body().equals(canary))
      throw new IOException("Unexpected HTTP response status/body");
  }

  private static void mcpOperation(
      HttpClient client, int port, String canary, long ordinal) throws Exception {
    mcpOperation(client, port, canary, ordinal, null);
  }

  private static void mcpOperation(
      HttpClient client,
      int port,
      String canary,
      long ordinal,
      TelemetryAudit telemetry) throws Exception {
    String id = "operational-" + ordinal;
    String traceId = traceIdForOperationOrdinal(ordinal);
    String parentId = String.format(Locale.ROOT, "%016x", ordinal + 1L);
    String traceparent = "00-" + traceId + "-" + parentId + "-01";
    String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + id
        + "\",\"method\":\"tools/call\",\"params\":{\"_meta\":{"
        + "\"io.modelcontextprotocol/protocolVersion\":\"" + MCP_PROTOCOL_VERSION
        + "\",\"io.modelcontextprotocol/clientCapabilities\":{},"
        + "\"traceparent\":\"" + traceparent + "\"},"
        + "\"name\":\"" + MCP_TOOL + "\",\"arguments\":{\"canary\":\""
        + canary + "\"}}}";
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create("http://" + HOST + ":" + port + MCP_PATH))
        .timeout(Duration.ofSeconds(5))
        .header("Content-Type", "application/json; charset=UTF-8")
        .header("Accept", "application/json, text/event-stream")
        .header("MCP-Protocol-Version", MCP_PROTOCOL_VERSION)
        .header("Mcp-Method", "tools/call")
        .header("Mcp-Name", MCP_TOOL)
        .header("X-Operational-Canary", canary)
        .header("traceparent", traceparent)
        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
        .build();
    if (telemetry != null)
      telemetry.startTraceOperation(ordinal, System.nanoTime());
    HttpResponse<String> response;
    try {
      response = client.send(request,
          HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } finally {
      if (telemetry != null)
        telemetry.completeTraceOperation(ordinal, System.nanoTime());
    }
    if (response.statusCode() != 200
        || !response.body().contains("\"id\":\"" + id + "\"")
        || !response.body().contains(canary)) {
      throw new IOException("Unexpected MCP response status/body: status="
          + response.statusCode() + " body=" + response.body());
    }
  }

  private static void realtimeOperation(int port, String canary) throws Exception {
    try (Socket socket = connectWithRetry(port)) {
      socket.setSoTimeout(5_000);
      String request = "GET /operational/events/" + canary + " HTTP/1.1\r\n"
          + "Host: " + HOST + ":" + port + "\r\n"
          + "Accept: text/event-stream\r\n"
          + "X-Operational-Canary: " + canary + "\r\n"
          + "Connection: keep-alive\r\n\r\n";
      socket.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
      socket.getOutputStream().flush();
      String headers = readUntil(socket.getInputStream(), "\r\n\r\n", 8192);
      if (headers == null || !headers.startsWith("HTTP/1.1 200")
          || !headers.toLowerCase(Locale.ROOT).contains("content-type: text/event-stream")) {
        throw new IOException("Unexpected SSE handshake: " + headers);
      }
      String event = readUntil(socket.getInputStream(), "\n\n", 4096);
      if (event == null || !event.contains("event: ready")
          || !event.contains("data: " + canary))
        throw new IOException("Unexpected SSE initial event");
      socket.setSoLinger(true, 0);
    }
  }

  private static Socket connectWithRetry(int port) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    IOException last = null;
    while (System.nanoTime() < deadline) {
      try {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(HOST, port), 2_000);
        return socket;
      } catch (IOException exception) {
        last = exception;
        Thread.sleep(25L);
      }
    }
    throw last == null ? new IOException("Unable to connect to SSE server") : last;
  }

  private static String readUntil(InputStream input, String terminator, int maximumBytes)
      throws IOException {
    byte[] terminatorBytes = terminator.getBytes(StandardCharsets.UTF_8);
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    int match = 0;
    int value;
    while (output.size() < maximumBytes && (value = input.read()) != -1) {
      output.write(value);
      if (value == terminatorBytes[match]) {
        ++match;
        if (match == terminatorBytes.length)
          return output.toString(StandardCharsets.UTF_8);
      } else {
        match = value == terminatorBytes[0] ? 1 : 0;
      }
    }
    return null;
  }

  private static long awaitStart(Instant startAt) {
    long remainingMillis = startAt.toEpochMilli() - System.currentTimeMillis();
    if (remainingMillis < 1_000L)
      throw new IllegalStateException("Operational child was not ready before the common start time");
    while (System.currentTimeMillis() < startAt.toEpochMilli())
      LockSupport.parkNanos(Math.min(TimeUnit.MILLISECONDS.toNanos(100),
          TimeUnit.MILLISECONDS.toNanos(startAt.toEpochMilli() - System.currentTimeMillis())));
    return System.nanoTime();
  }

  private static void awaitDeadline(long baseNanoseconds, long offsetSeconds) {
    awaitDeadlineNanoseconds(baseNanoseconds + offsetSeconds * 1_000_000_000L);
  }

  private static void awaitDeadline(long phaseStartNanoseconds, int offsetSeconds) {
    awaitDeadlineNanoseconds(phaseStartNanoseconds + offsetSeconds * 1_000_000_000L);
  }

  private static void awaitDeadlineNanoseconds(long deadline) {
    long remaining;
    while ((remaining = deadline - System.nanoTime()) > 0)
      LockSupport.parkNanos(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)));
  }

  private static int findFreePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      socket.setReuseAddress(true);
      return socket.getLocalPort();
    }
  }

  private static void forceGc() throws InterruptedException {
    for (int index = 0; index < 3; ++index) {
      System.gc();
      Thread.sleep(75L);
    }
  }

  private static Instant exactUtc(String value) {
    if (value == null || !Pattern.matches(
        "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z", value))
      throw new IllegalArgumentException("--start-at must be exact UTC without fractions");
    Instant instant = Instant.parse(value);
    if (!instant.toString().equals(value))
      throw new IllegalArgumentException("--start-at is not canonical UTC");
    return instant;
  }

  private static Path absolutePath(String value, String label) {
    if (value == null)
      throw new IllegalArgumentException("Missing " + label);
    Path path = Path.of(value).normalize();
    if (!path.isAbsolute())
      throw new IllegalArgumentException(label + " must be absolute");
    return path;
  }

  private static Map<String, String> parseArguments(String[] arguments) {
    if (arguments.length % 2 != 0)
      throw new IllegalArgumentException("Operational child arguments must be name/value pairs");
    Set<String> expected = Set.of("--group", "--output", "--policy", "--start-at");
    Map<String, String> options = new LinkedHashMap<>();
    for (int index = 0; index < arguments.length; index += 2) {
      String name = arguments[index];
      String value = arguments[index + 1];
      if (!expected.contains(name) || value.startsWith("--") || options.put(name, value) != null)
        throw new IllegalArgumentException("Unexpected or duplicate operational child argument: " + name);
    }
    if (!options.keySet().equals(expected))
      throw new IllegalArgumentException("Operational child requires exactly: " + expected);
    return options;
  }

  /** Actual standard-HTTP application endpoint used by the load producer. */
  public static final class HttpResource {
    @GET(HTTP_PATH)
    public String get(Request request) {
      String query = request.getQueryParameter("canary").orElseThrow();
      String header = request.getHeader("X-Operational-Canary").orElseThrow();
      if (!query.equals(header))
        throw new IllegalArgumentException("Canary carriers differ");
      CanaryTracker tracker = HTTP_TRACKER.get();
      if (tracker == null)
        throw new IllegalStateException("HTTP tracker is not installed");
      tracker.observe(query);
      return query;
    }
  }

  /** Actual SSE application endpoint used by the realtime load producer. */
  public static final class RealtimeResource {
    @SseEventSource(REALTIME_ROUTE)
    public SseHandshakeResult events(
        Request request, @PathParameter(name = "canary") String canary) {
      String header = request.getHeader("X-Operational-Canary").orElseThrow();
      if (!canary.equals(header))
        throw new IllegalArgumentException("Canary carriers differ");
      CanaryTracker tracker = REALTIME_TRACKER.get();
      if (tracker == null)
        throw new IllegalStateException("Realtime tracker is not installed");
      tracker.observe(canary);
      return SseHandshakeResult.Accepted.builder()
          .clientInitializer(unicaster -> unicaster.unicastEvent(
              SseEvent.withEvent("ready").data(canary).build()))
          .build();
    }
  }

  static final class CanaryTracker {
    private final String scenario;
    private final int uniqueCanaries;
    private final BitSet observed;

    CanaryTracker(String scenario, int uniqueCanaries) {
      this.scenario = scenario;
      this.uniqueCanaries = uniqueCanaries;
      this.observed = new BitSet(uniqueCanaries);
    }

    synchronized void observe(String canary) {
      String prefix = CANARY_PREFIX + scenario + "-";
      if (!canary.startsWith(prefix) || canary.length() != prefix.length() + 5)
        throw new IllegalArgumentException("Malformed scenario canary");
      int index;
      try {
        index = Integer.parseInt(canary.substring(prefix.length()));
      } catch (NumberFormatException exception) {
        throw new IllegalArgumentException("Malformed scenario canary", exception);
      }
      if (index < 0 || index >= uniqueCanaries)
        throw new IllegalArgumentException("Scenario canary is outside the registered range");
      observed.set(index);
    }

    synchronized int uniqueCount() {
      return observed.cardinality();
    }

    synchronized void reset() {
      observed.clear();
    }
  }

  static record Policy(
      int cadenceSeconds,
      int clientsPerScenario,
      int deterministicSeed,
      int drainMaximumSeconds,
      int durationSeconds,
      int maximumSampleGapSeconds,
      int operationsPerClientPerSecond,
      String policySha256,
      int postIntervalReserveSeconds,
      int secondsPerScenario,
      int uniqueCanaries) {
    private static final Set<String> KEYS = Set.of(
        "cadenceSeconds", "clientsPerScenario", "deterministicSeed",
        "drainMaximumSeconds", "durationSeconds", "maximumSampleGapSeconds",
        "operationsPerClientPerSecond", "policySha256",
        "postIntervalReserveSeconds", "secondsPerScenario",
        "uniqueAdversarialDimensionValuesPerScenario");

    static Policy load(Path path) throws IOException {
      if (!Files.isRegularFile(path) || Files.isSymbolicLink(path)
          || !path.toRealPath().equals(path))
        throw new IllegalArgumentException("Policy must be a real nonsymlink regular file");
      Map<String, String> values = new LinkedHashMap<>();
      for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
        int equals = line.indexOf('=');
        if (equals <= 0 || equals == line.length() - 1)
          throw new IllegalArgumentException("Malformed policy line");
        String key = line.substring(0, equals);
        if (values.put(key, line.substring(equals + 1)) != null)
          throw new IllegalArgumentException("Duplicate policy key: " + key);
      }
      if (!values.keySet().equals(KEYS))
        throw new IllegalArgumentException("Policy field set does not match the producer contract");
      return new Policy(
          positive(values, "cadenceSeconds"),
          positive(values, "clientsPerScenario"),
          nonnegative(values, "deterministicSeed"),
          positive(values, "drainMaximumSeconds"),
          positive(values, "durationSeconds"),
          positive(values, "maximumSampleGapSeconds"),
          positive(values, "operationsPerClientPerSecond"),
          requireSha256(values.get("policySha256")),
          positive(values, "postIntervalReserveSeconds"),
          positive(values, "secondsPerScenario"),
          positive(values, "uniqueAdversarialDimensionValuesPerScenario"));
    }

    private static int positive(Map<String, String> values, String name) {
      int value = Integer.parseInt(values.get(name));
      if (value <= 0)
        throw new IllegalArgumentException(name + " must be positive");
      return value;
    }

    private static int nonnegative(Map<String, String> values, String name) {
      int value = Integer.parseInt(values.get(name));
      if (value < 0)
        throw new IllegalArgumentException(name + " must be nonnegative");
      return value;
    }

    private static String requireSha256(String value) {
      if (value == null || !value.matches("[0-9a-f]{64}"))
        throw new IllegalArgumentException("policySha256 must be lowercase SHA-256");
      return value;
    }

    boolean isRegisteredProductionPolicy() {
      return cadenceSeconds == 5
          && clientsPerScenario == 16
          && deterministicSeed == 400
          && drainMaximumSeconds == 30
          && durationSeconds == 21_600
          && maximumSampleGapSeconds == 15
          && operationsPerClientPerSecond == 1
          && postIntervalReserveSeconds == 600
          && secondsPerScenario == 7_200
          && uniqueCanaries == 10_000;
    }

    long totalSeconds() {
      return (long) durationSeconds + postIntervalReserveSeconds;
    }

    int requiredSamples() {
      return Math.toIntExact(totalSeconds() / cadenceSeconds + 1L);
    }
  }

  static record ResourceSnapshot(long fileDescriptors, long heapBytes, int liveThreads) {
    static ResourceSnapshot capture() {
      java.lang.management.OperatingSystemMXBean bean =
          ManagementFactory.getOperatingSystemMXBean();
      if (!(bean instanceof UnixOperatingSystemMXBean unix))
        throw new IllegalStateException("Operational history requires a Unix MXBean");
      long descriptors = unix.getOpenFileDescriptorCount();
      long heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
      int threads = ManagementFactory.getThreadMXBean().getThreadCount();
      if (descriptors < 0 || heap < 0 || threads < 0)
        throw new IllegalStateException("Resource MXBean returned a negative observation");
      return new ResourceSnapshot(descriptors, heap, threads);
    }

    Map<String, Object> json() {
      return sortedMap(
          "fileDescriptors", fileDescriptors,
          "heapBytes", heapBytes,
          "liveThreads", liveThreads);
    }

    static ResourceSnapshot growth(ResourceSnapshot baseline, ResourceSnapshot current) {
      return new ResourceSnapshot(
          Math.max(0L, current.fileDescriptors - baseline.fileDescriptors),
          Math.max(0L, current.heapBytes - baseline.heapBytes),
          Math.max(0, current.liveThreads - baseline.liveThreads));
    }
  }

  private static final class SampleRecorder {
    private final Policy policy;
    private final long baseNanoseconds;
    private final MetricAudit metrics;
    private final TelemetryAudit telemetry;
    private final List<Sample> samples;
    private final AtomicReference<Throwable> failure;

    private SampleRecorder(
        Policy policy, long baseNanoseconds, MetricAudit metrics, TelemetryAudit telemetry) {
      this.policy = policy;
      this.baseNanoseconds = baseNanoseconds;
      this.metrics = metrics;
      this.telemetry = telemetry;
      this.samples = Collections.synchronizedList(new ArrayList<>(policy.requiredSamples()));
      this.failure = new AtomicReference<>();
    }

    Thread start() {
      Thread thread = new Thread(() -> {
        try {
          for (int index = 0; index < policy.requiredSamples(); ++index) {
            awaitDeadlineNanoseconds(baseNanoseconds
                + (long) index * policy.cadenceSeconds() * 1_000_000_000L);
            if (index == policy.requiredSamples() - 1)
              forceGc();
            metrics.scanMetrics();
            samples.add(new Sample(
                Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(),
                telemetry.droppedLogRecords(),
                metrics.frameworkMetricCardinality(),
                metrics.rejectedMetricDeliveries(),
                ResourceSnapshot.capture(),
                metrics.unregisteredMetricDimensions()));
          }
        } catch (Throwable throwable) {
          failure.compareAndSet(null, throwable);
        }
      }, "soklet-operational-sampler");
      thread.setDaemon(false);
      thread.start();
      return thread;
    }

    List<Sample> finish(List<String> outcomes) {
      Throwable throwable = failure.get();
      if (throwable != null)
        outcomes.add("Sampler failed: " + throwable);
      List<Sample> copy;
      synchronized (samples) {
        copy = List.copyOf(samples);
      }
      if (copy.size() != policy.requiredSamples())
        outcomes.add("Sampler count mismatch: expected=%d actual=%d".formatted(
            policy.requiredSamples(), copy.size()));
      Instant previous = null;
      for (Sample sample : copy) {
        Instant at = Instant.parse(sample.at());
        if (previous != null) {
          long gap = Duration.between(previous, at).toSeconds();
          if (gap <= 0 || gap > policy.maximumSampleGapSeconds())
            outcomes.add("Sampler gap violates policy: " + gap);
        }
        previous = at;
      }
      if (copy.size() == policy.requiredSamples()) {
        long span = Duration.between(
            Instant.parse(copy.get(0).at()), Instant.parse(copy.get(copy.size() - 1).at())).toSeconds();
        if (span < policy.totalSeconds())
          outcomes.add("Sampler wall-clock span is incomplete: " + span);
      }
      return copy;
    }
  }

  private static final class MetricAudit {
    private final DefaultMetricsCollector delegate;
    private final MetricsCollector collector;
    private final AtomicLong httpResponsesWritten;
    private final AtomicLong registeredMcpCompletions;
    private final AtomicLong sseConnectionsEstablished;
    private final AtomicLong metricSamplesObserved;
    private final AtomicLong rejectedMetricDeliveries;
    private final AtomicLong sensitiveCanaries;
    private final AtomicLong invalidOperationalDeliveries;
    private final AtomicBoolean operationalWindowStarted;
    private final Set<String> cardinalityViolations;
    private volatile DeliveryCounts directBaseline;
    private volatile DeliveryCounts aggregateBaseline;

    private MetricAudit() {
      this.delegate = DefaultMetricsCollector.defaultInstance();
      this.httpResponsesWritten = new AtomicLong();
      this.registeredMcpCompletions = new AtomicLong();
      this.sseConnectionsEstablished = new AtomicLong();
      this.metricSamplesObserved = new AtomicLong();
      this.rejectedMetricDeliveries = new AtomicLong();
      this.sensitiveCanaries = new AtomicLong();
      this.invalidOperationalDeliveries = new AtomicLong();
      this.operationalWindowStarted = new AtomicBoolean();
      this.cardinalityViolations = ConcurrentHashMap.newKeySet();
      this.directBaseline = new DeliveryCounts(0L, 0L, 0L);
      this.aggregateBaseline = new DeliveryCounts(0L, 0L, 0L);
      this.collector = (MetricsCollector) Proxy.newProxyInstance(
          MetricsCollector.class.getClassLoader(),
          new Class<?>[]{MetricsCollector.class},
          (proxy, method, arguments) -> invoke(method, arguments));
    }

    private Object invoke(Method method, Object[] arguments) throws Throwable {
      if (method.getName().equals("didWriteResponse")
          && arguments != null && arguments.length == 5
          && arguments[0] == ServerType.STANDARD_HTTP) {
        if (isRegisteredHttpWrite(arguments))
          httpResponsesWritten.incrementAndGet();
        else if (operationalWindowStarted.get())
          invalidOperationalDeliveries.incrementAndGet();
      }
      if (method.getName().equals("didRecordMcpMetricsEvent")
          && arguments != null && arguments.length == 1
          && arguments[0] instanceof McpMetricsEvent event) {
        if (event instanceof McpMetricsEvent.RequestFinished) {
          if (isRegisteredMcpCompletionEvent(event))
            registeredMcpCompletions.incrementAndGet();
          else if (operationalWindowStarted.get())
            invalidOperationalDeliveries.incrementAndGet();
        }
        auditEvent(event);
      }
      if (method.getName().equals("didEstablishSseConnection")
          && arguments != null && arguments.length == 1
          && arguments[0] instanceof SseConnection connection) {
        if (isRegisteredSseConnection(connection))
          sseConnectionsEstablished.incrementAndGet();
        else if (operationalWindowStarted.get())
          invalidOperationalDeliveries.incrementAndGet();
      }
      try {
        return method.invoke(delegate, arguments);
      } catch (InvocationTargetException exception) {
        rejectedMetricDeliveries.incrementAndGet();
        throw exception.getCause();
      } catch (Throwable throwable) {
        rejectedMetricDeliveries.incrementAndGet();
        throw throwable;
      }
    }

    private static boolean isRegisteredHttpWrite(Object[] arguments) {
      if (!(arguments[1] instanceof Request request)
          || !(arguments[2] instanceof ResourceMethod resourceMethod)
          || !(arguments[3] instanceof MarshaledResponse response)) {
        return false;
      }
      return request.getHttpMethod() == HttpMethod.GET
          && request.getPath().equals(HTTP_PATH)
          && resourceMethod.getHttpMethod() == HttpMethod.GET
          && resourceMethod.getResourcePathDeclaration().getPath().equals(HTTP_PATH)
          && response.getStatusCode() == 200;
    }

    private static boolean isRegisteredSseConnection(SseConnection connection) {
      return connection.getRequest().getHttpMethod() == HttpMethod.GET
          && connection.getResourceMethod().getResourcePathDeclaration().getPath()
              .equals(REALTIME_ROUTE);
    }

    private void auditEvent(McpMetricsEvent event) {
      if (event.toString().contains(CANARY_PREFIX))
        sensitiveCanaries.incrementAndGet();
      for (Method method : event.getClass().getMethods()) {
        if (method.getParameterCount() != 0 || method.getReturnType() != String.class)
          continue;
        try {
          String value = (String) method.invoke(event);
          if (value.contains(CANARY_PREFIX))
            sensitiveCanaries.incrementAndGet();
          if (method.getName().equals("getEndpointPath") && !value.equals(MCP_PATH))
            cardinalityViolations.add("event:endpoint=" + value);
          if (method.getName().equals("getJsonRpcMethod") && !value.equals("tools/call"))
            cardinalityViolations.add("event:method=" + value);
        } catch (ReflectiveOperationException exception) {
          cardinalityViolations.add("event-reflection:" + event.getClass().getName());
        }
      }
    }

    void initialize(SokletConfig config) {
      delegate.initialize(config);
    }

    synchronized void beginOperationalWindow() {
      if (operationalWindowStarted.get())
        throw new IllegalStateException("Metric operational window already started");
      directBaseline = directCounts();
      aggregateBaseline = aggregateCounts(snapshot());
      operationalWindowStarted.set(true);
    }

    MetricsCollector collector() {
      return collector;
    }

    void scanMetrics() {
      long samplesBefore = metricSamplesObserved.get();
      SnapshotTextOptions options = SnapshotTextOptions.withMetricsFormat(MetricsFormat.PROMETHEUS)
          .metricFilter(sample -> {
            auditMetricSample(sample);
            return false;
          })
          .histogramFormat(SnapshotTextOptions.HistogramFormat.COUNT_SUM_ONLY)
          .includeZeroBuckets(false)
          .build();
      delegate.snapshotText(options);
      if (metricSamplesObserved.get() == samplesBefore)
        cardinalityViolations.add("metrics-snapshot:no-audited-samples");
    }

    void validateHttpDelivery(long expectedOperations, List<String> outcomes) {
      DeliveryCounts direct = operationalDirectCounts(outcomes);
      DeliveryCounts aggregate = operationalAggregateCounts(outcomes);
      requireExactDelivery("HTTP response-write callbacks",
          expectedOperations, direct.http(), outcomes);
      requireExactDelivery("HTTP request-duration aggregate",
          expectedOperations, aggregate.http(), outcomes);
      requireNoInvalidOperationalDelivery(outcomes);
    }

    void validateMcpAndRealtimeDelivery(
        long expectedMcpOperations,
        long expectedRealtimeOperations,
        List<String> outcomes) {
      DeliveryCounts direct = operationalDirectCounts(outcomes);
      DeliveryCounts aggregate = operationalAggregateCounts(outcomes);
      requireExactDelivery("MCP COMPLETE RequestFinished callbacks",
          expectedMcpOperations, direct.mcp(), outcomes);
      requireExactDelivery("MCP COMPLETE request aggregate",
          expectedMcpOperations, aggregate.mcp(), outcomes);
      requireExactDelivery("SSE established-connection callbacks",
          expectedRealtimeOperations, direct.realtime(), outcomes);
      requireExactDelivery("SSE accepted-handshake aggregate",
          expectedRealtimeOperations, aggregate.realtime(), outcomes);
      requireNoInvalidOperationalDelivery(outcomes);
    }

    private void requireNoInvalidOperationalDelivery(List<String> outcomes) {
      long invalid = invalidOperationalDeliveries.get();
      if (invalid != 0L)
        outcomes.add("Invalid operational metric deliveries observed: " + invalid);
    }

    private static void requireExactDelivery(
        String label, long expected, long actual, List<String> outcomes) {
      if (actual != expected) {
        outcomes.add("%s mismatch: expected=%d actual=%d".formatted(
            label, expected, actual));
      }
    }

    private DeliveryCounts operationalDirectCounts(List<String> outcomes) {
      if (!operationalWindowStarted.get()) {
        outcomes.add("Metric operational window was not started");
        return new DeliveryCounts(0L, 0L, 0L);
      }
      return subtractCounts(directCounts(), directBaseline, outcomes,
          "metric callback");
    }

    private DeliveryCounts operationalAggregateCounts(List<String> outcomes) {
      if (!operationalWindowStarted.get()) {
        outcomes.add("Metric operational window was not started");
        return new DeliveryCounts(0L, 0L, 0L);
      }
      return subtractCounts(aggregateCounts(snapshot()), aggregateBaseline,
          outcomes, "metric aggregate");
    }

    private DeliveryCounts directCounts() {
      return new DeliveryCounts(
          httpResponsesWritten.get(),
          registeredMcpCompletions.get(),
          sseConnectionsEstablished.get());
    }

    private static DeliveryCounts subtractCounts(
        DeliveryCounts current,
        DeliveryCounts baseline,
        List<String> outcomes,
        String label) {
      if (current.http() < baseline.http()
          || current.mcp() < baseline.mcp()
          || current.realtime() < baseline.realtime()) {
        outcomes.add(label + " counters regressed below their warmup baseline");
        return new DeliveryCounts(0L, 0L, 0L);
      }
      return new DeliveryCounts(
          current.http() - baseline.http(),
          current.mcp() - baseline.mcp(),
          current.realtime() - baseline.realtime());
    }

    private MetricsCollector.Snapshot snapshot() {
      return delegate.snapshot().orElseThrow(() ->
          new IllegalStateException("Default metrics collector omitted its snapshot"));
    }

    private static DeliveryCounts aggregateCounts(MetricsCollector.Snapshot snapshot) {
      return new DeliveryCounts(
          registeredHttpAggregate(snapshot),
          registeredMcpAggregate(snapshot),
          registeredRealtimeAggregate(snapshot));
    }

    private static long registeredHttpAggregate(MetricsCollector.Snapshot snapshot) {
      long count = 0L;
      for (Map.Entry<MetricsCollector.HttpServerRouteStatusKey,
          MetricsCollector.HistogramSnapshot> entry
          : snapshot.getHttpRequestDurations().entrySet()) {
        MetricsCollector.HttpServerRouteStatusKey key = entry.getKey();
        if (key.method() == HttpMethod.GET
            && key.routeType() == MetricsCollector.RouteType.MATCHED
            && key.route() != null
            && key.route().getPath().equals(HTTP_PATH)
            && key.statusClass().equals("2xx")) {
          count = Math.addExact(count, entry.getValue().getCount());
        }
      }
      return count;
    }

    private static long registeredMcpAggregate(MetricsCollector.Snapshot snapshot) {
      long count = 0L;
      for (Map.Entry<McpMetricsSnapshot.RequestOutcomeKey, Long> entry
          : snapshot.getMcpMetrics().getRequests().entrySet()) {
        McpMetricsSnapshot.RequestOutcomeKey key = entry.getKey();
        if (key.getEndpointPath().equals(MCP_PATH)
            && key.getJsonRpcMethod().equals("tools/call")
            && key.getOutcome() == McpRequestOutcome.COMPLETE) {
          count = Math.addExact(count, entry.getValue());
        }
      }
      return count;
    }

    private static long registeredRealtimeAggregate(MetricsCollector.Snapshot snapshot) {
      long count = 0L;
      for (Map.Entry<MetricsCollector.SseEventRouteKey, Long> entry
          : snapshot.getSseHandshakesAccepted().entrySet()) {
        MetricsCollector.SseEventRouteKey key = entry.getKey();
        if (key.routeType() == MetricsCollector.RouteType.MATCHED
            && key.route() != null
            && key.route().getPath().equals(REALTIME_ROUTE)) {
          count = Math.addExact(count, entry.getValue());
        }
      }
      return count;
    }

    private void auditMetricSample(SnapshotTextOptions.MetricSample sample) {
      metricSamplesObserved.incrementAndGet();
      if (!sample.getName().startsWith("soklet_"))
        cardinalityViolations.add("metric-name:" + sample.getName());
      for (Map.Entry<String, String> entry : sample.getLabels().entrySet()) {
        String key = entry.getKey();
        String value = entry.getValue();
        if (value.contains(CANARY_PREFIX))
          sensitiveCanaries.incrementAndGet();
        if (!REGISTERED_LABEL_KEYS.contains(key))
          cardinalityViolations.add(sample.getName() + ":key=" + key);
        if (key.equals("route") && !REGISTERED_ROUTES.contains(value))
          cardinalityViolations.add(sample.getName() + ":route=" + value);
        if (key.equals("endpoint") && !value.equals(MCP_PATH))
          cardinalityViolations.add(sample.getName() + ":endpoint=" + value);
        if (key.equals("method") && !value.equals("GET") && !value.equals("tools/call"))
          cardinalityViolations.add(sample.getName() + ":method=" + value);
      }
    }

    long metricEventsObserved() {
      if (!operationalWindowStarted.get())
        return 0L;
      long observed = registeredMcpCompletions.get() - directBaseline.mcp();
      return Math.max(0L, observed);
    }

    long metricSamplesObserved() {
      return metricSamplesObserved.get();
    }

    long rejectedMetricDeliveries() {
      return rejectedMetricDeliveries.get();
    }

    long sensitiveCanaries() {
      return sensitiveCanaries.get();
    }

    long frameworkMetricCardinality() {
      return cardinalityViolations.size();
    }

    long unregisteredMetricDimensions() {
      return cardinalityViolations.size();
    }

    private static record DeliveryCounts(long http, long mcp, long realtime) {}
  }

  static final class TelemetryAudit implements LifecycleObserver, AutoCloseable {
    private final ArrayBlockingQueue<TelemetryRecord> records;
    private final String expectedTraceKeyId;
    private final ConcurrentHashMap<String, TraceExpectation> expectedTraceRecords;
    private final TraceExpectation[] expectedTraceRecordsByOrdinal;
    private final long firstExpectedTraceOrdinal;
    private final AtomicLong accepted;
    private final AtomicLong drained;
    private final AtomicLong dropped;
    private final AtomicLong operationalTraceRecordsDrained;
    private final AtomicLong invalidOperationalTraceRecords;
    private final AtomicLong maximumOperationalDrainNanoseconds;
    private final AtomicLong sensitiveCanaries;
    private final AtomicBoolean operationalWindowStarted;
    private final AtomicBoolean consumerStarted;
    private final AtomicBoolean running;
    private final Thread consumer;

    TelemetryAudit() {
      this(null, null, 0L, 0L);
    }

    TelemetryAudit(String expectedTraceKeyId) {
      this(expectedTraceKeyId, null, 0L, 0L);
    }

    TelemetryAudit(
        String expectedTraceKeyId,
        byte[] traceKeyBytes,
        long firstExpectedTraceOrdinal,
        long expectedTraceRecordCount) {
      if (expectedTraceRecordCount < 0L)
        throw new IllegalArgumentException("Expected trace record count must be nonnegative");
      if (expectedTraceRecordCount > 0L
          && (expectedTraceKeyId == null || traceKeyBytes == null)) {
        throw new IllegalArgumentException(
            "Expected trace records require a key ID and key bytes");
      }
      if (expectedTraceRecordCount == 0L && traceKeyBytes != null)
        throw new IllegalArgumentException("An empty trace audit must not retain key bytes");
      this.records = new ArrayBlockingQueue<>(8_192);
      this.expectedTraceKeyId = expectedTraceKeyId;
      this.expectedTraceRecords = new ConcurrentHashMap<>();
      this.expectedTraceRecordsByOrdinal =
          new TraceExpectation[Math.toIntExact(expectedTraceRecordCount)];
      this.firstExpectedTraceOrdinal = firstExpectedTraceOrdinal;
      for (int index = 0; index < expectedTraceRecordsByOrdinal.length; ++index) {
        long ordinal = Math.addExact(firstExpectedTraceOrdinal, index);
        String token = expectedTraceToken(traceKeyBytes, ordinal);
        TraceExpectation expectation = new TraceExpectation(ordinal, token);
        if (expectedTraceRecords.putIfAbsent(token, expectation) != null)
          throw new IllegalStateException("Expected trace-token collision: " + token);
        expectedTraceRecordsByOrdinal[index] = expectation;
      }
      this.accepted = new AtomicLong();
      this.drained = new AtomicLong();
      this.dropped = new AtomicLong();
      this.operationalTraceRecordsDrained = new AtomicLong();
      this.invalidOperationalTraceRecords = new AtomicLong();
      this.maximumOperationalDrainNanoseconds = new AtomicLong();
      this.sensitiveCanaries = new AtomicLong();
      this.operationalWindowStarted = new AtomicBoolean();
      this.consumerStarted = new AtomicBoolean();
      this.running = new AtomicBoolean(true);
      this.consumer = new Thread(this::consume, "soklet-operational-log-drain");
      this.consumer.setDaemon(false);
    }

    void beginOperationalWindow() {
      ensureConsumerStarted();
      if (accepted.get() != drained.get())
        throw new IllegalStateException(
            "Warmup log records were not drained before the operational window");
      if (!operationalWindowStarted.compareAndSet(false, true))
        throw new IllegalStateException("Telemetry operational window already started");
    }

    void startTraceOperation(long ordinal, long requestStartedNanoseconds) {
      if (!operationalWindowStarted.get())
        throw new IllegalStateException(
            "Trace expectation requires the operational window");
      TraceExpectation expectation = expectedTraceExpectation(ordinal);
      if (!expectation.markStarted(requestStartedNanoseconds)) {
        invalidOperationalTraceRecords.incrementAndGet();
        throw new IllegalStateException("Trace operation was started more than once");
      }
    }

    void completeTraceOperation(long ordinal, long completedNanoseconds) {
      TraceExpectation expectation = expectedTraceExpectation(ordinal);
      if (!expectation.markCompleted(completedNanoseconds)) {
        invalidOperationalTraceRecords.incrementAndGet();
        throw new IllegalStateException(
            "Trace operation completion did not match one expected token");
      }
      observeTraceDeliveryDrain(expectation);
    }

    private TraceExpectation expectedTraceExpectation(long ordinal) {
      long offset;
      try {
        offset = Math.subtractExact(ordinal, firstExpectedTraceOrdinal);
      } catch (ArithmeticException exception) {
        throw new IllegalArgumentException("Trace operation ordinal is outside the audit", exception);
      }
      if (offset < 0L || offset >= expectedTraceRecordsByOrdinal.length)
        throw new IllegalArgumentException("Trace operation ordinal is outside the audit");
      return expectedTraceRecordsByOrdinal[Math.toIntExact(offset)];
    }

    @Override
    public void didReceiveLogEvent(LogEvent logEvent) {
      long acceptedAt = System.nanoTime();
      boolean operational = operationalWindowStarted.get();
      boolean trace = logEvent.getLogEventType() == LogEventType.MCP_TRACE_CORRELATION;
      TraceExpectation traceExpectation = null;
      if (operational && trace) {
        Optional<String> token = registeredTraceToken(logEvent, expectedTraceKeyId);
        if (token.isPresent()) {
          TraceExpectation expected = expectedTraceRecords.get(token.orElseThrow());
          if (expected != null && expected.markAccepted(acceptedAt))
            traceExpectation = expected;
          else
            invalidOperationalTraceRecords.incrementAndGet();
        } else {
          invalidOperationalTraceRecords.incrementAndGet();
        }
      }
      TelemetryRecord record = new TelemetryRecord(
          renderLogRecord(logEvent), acceptedAt, operational, traceExpectation);
      accepted.incrementAndGet();
      if (!records.offer(record)) {
        accepted.decrementAndGet();
        dropped.incrementAndGet();
      }
    }

    private static String renderLogRecord(LogEvent logEvent) {
      return logEvent.getLogEventType().name() + " " + logEvent.getMessage()
          + logEvent.getThrowable().map(value -> " " + value).orElse("")
          + logEvent.getRequest().map(value -> " " + value).orElse("")
          + logEvent.getResourceMethod().map(value -> " " + value).orElse("")
          + logEvent.getMarshaledResponse().map(value -> " " + value).orElse("");
    }

    private void consume() {
      try {
        while (running.get() || !records.isEmpty()) {
          TelemetryRecord record = records.poll(100, TimeUnit.MILLISECONDS);
          if (record == null)
            continue;
          if (record.text().contains(CANARY_PREFIX))
            sensitiveCanaries.incrementAndGet();
          if (record.operational()) {
            long drainedAt = System.nanoTime();
            long elapsed = logDeliveryDrainNanoseconds(
                record.acceptedNanoseconds(), drainedAt);
            maximumOperationalDrainNanoseconds.accumulateAndGet(
                elapsed, Math::max);
            TraceExpectation expectation = record.traceExpectation();
            if (expectation != null) {
              if (expectation.markDrained(drainedAt)) {
                operationalTraceRecordsDrained.incrementAndGet();
                observeTraceDeliveryDrain(expectation);
              } else {
                invalidOperationalTraceRecords.incrementAndGet();
              }
            }
          }
          drained.incrementAndGet();
        }
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
      }
    }

    double awaitExpectedTraceRecords(
        long expectedRecords,
        long absoluteDeadlineNanoseconds,
        List<String> outcomes) {
      ensureConsumerStarted();
      if (expectedRecords < 0L)
        throw new IllegalArgumentException("Expected trace record count must be nonnegative");
      long started = System.nanoTime();
      while (System.nanoTime() < absoluteDeadlineNanoseconds) {
        if (operationalTraceRecordsDrained.get() == expectedRecords)
          return (System.nanoTime() - started) / 1_000_000_000.0;
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
      }
      long observed = operationalTraceRecordsDrained.get();
      if (observed == expectedRecords)
        return (System.nanoTime() - started) / 1_000_000_000.0;
      outcomes.add("MCP trace-log phase drain mismatch: expected=%d actual=%d".formatted(
          expectedRecords, observed));
      return Math.max(0.0,
          (System.nanoTime() - started) / 1_000_000_000.0);
    }

    double awaitDrained(int maximumSeconds, List<String> outcomes) {
      ensureConsumerStarted();
      if (drained.get() == accepted.get())
        return 0.0;
      long started = System.nanoTime();
      long deadline = started + TimeUnit.SECONDS.toNanos(maximumSeconds);
      while (System.nanoTime() < deadline) {
        if (drained.get() == accepted.get())
          return (System.nanoTime() - started) / 1_000_000_000.0;
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
      }
      if (drained.get() == accepted.get())
        return (System.nanoTime() - started) / 1_000_000_000.0;
      outcomes.add("Log queue did not drain within policy");
      return maximumSeconds;
    }

    double maximumOperationalDrainSeconds() {
      return maximumOperationalDrainNanoseconds.get() / 1_000_000_000.0;
    }

    void validateDrainMaximum(int maximumSeconds, List<String> outcomes) {
      long maximumNanoseconds = TimeUnit.SECONDS.toNanos(maximumSeconds);
      long observed = maximumOperationalDrainNanoseconds.get();
      if (observed > maximumNanoseconds) {
        outcomes.add("Log record delivery exceeded drain policy: maximumNanoseconds="
            + observed);
      }
    }

    void validateTraceDelivery(long expectedRecords, List<String> outcomes) {
      if (!operationalWindowStarted.get()) {
        outcomes.add("Telemetry operational window was not started");
        return;
      }
      long observed = operationalTraceRecordsDrained.get();
      if (observed != expectedRecords) {
        outcomes.add("MCP trace-log delivery mismatch: expected=%d actual=%d".formatted(
            expectedRecords, observed));
      }
      long invalid = invalidOperationalTraceRecords.get();
      if (invalid != 0L)
        outcomes.add("Invalid MCP trace-log records observed: " + invalid);
      long expectedTokens = expectedTraceRecords.size();
      long startedTokens = expectedTraceRecords.values().stream()
          .filter(TraceExpectation::started).count();
      long completedTokens = expectedTraceRecords.values().stream()
          .filter(TraceExpectation::completed).count();
      long acceptedTokens = expectedTraceRecords.values().stream()
          .filter(TraceExpectation::accepted).count();
      long drainedTokens = expectedTraceRecords.values().stream()
          .filter(TraceExpectation::drained).count();
      if (expectedTokens != expectedRecords
          || startedTokens != expectedRecords
          || completedTokens != expectedRecords
          || acceptedTokens != expectedRecords
          || drainedTokens != expectedRecords) {
        outcomes.add(("MCP exact trace-token audit mismatch: expected=%d "
            + "registered=%d started=%d completed=%d accepted=%d drained=%d").formatted(
                expectedRecords, expectedTokens, startedTokens, completedTokens,
                acceptedTokens, drainedTokens));
      }
    }

    long droppedLogRecords() {
      return dropped.get();
    }

    long logRecordsObserved() {
      return operationalTraceRecordsDrained.get();
    }

    long sensitiveCanaries() {
      return sensitiveCanaries.get();
    }

    @Override
    public void close() throws InterruptedException {
      running.set(false);
      if (!consumerStarted.get())
        return;
      consumer.join(TimeUnit.SECONDS.toMillis(5));
      if (consumer.isAlive()) {
        consumer.interrupt();
        consumer.join(TimeUnit.SECONDS.toMillis(1));
      }
      if (consumer.isAlive())
        throw new IllegalStateException("Operational log-drain consumer did not terminate");
    }

    private void ensureConsumerStarted() {
      if (consumerStarted.compareAndSet(false, true))
        consumer.start();
    }

    void observeTraceDeliveryDrain(TraceExpectation expectation) {
      long elapsed = expectation.deliveryDrainNanoseconds();
      if (elapsed >= 0L)
        maximumOperationalDrainNanoseconds.accumulateAndGet(elapsed, Math::max);
    }

    static final class TraceExpectation {
      private static final long UNSET = Long.MIN_VALUE;
      private final long ordinal;
      private final String token;
      private final AtomicLong requestStartedNanoseconds;
      private final AtomicLong completedNanoseconds;
      private final AtomicLong acceptedNanoseconds;
      private final AtomicLong drainedNanoseconds;

      TraceExpectation(long ordinal, String token) {
        this.ordinal = ordinal;
        this.token = token;
        this.requestStartedNanoseconds = new AtomicLong(UNSET);
        this.completedNanoseconds = new AtomicLong(UNSET);
        this.acceptedNanoseconds = new AtomicLong(UNSET);
        this.drainedNanoseconds = new AtomicLong(UNSET);
      }

      boolean markStarted(long nanoseconds) {
        return requestStartedNanoseconds.compareAndSet(UNSET, nanoseconds);
      }

      boolean markCompleted(long nanoseconds) {
        long startedAt = requestStartedNanoseconds.get();
        return startedAt != UNSET && nanoseconds >= startedAt
            && completedNanoseconds.compareAndSet(UNSET, nanoseconds);
      }

      boolean markAccepted(long nanoseconds) {
        long startedAt = requestStartedNanoseconds.get();
        return startedAt != UNSET && nanoseconds >= startedAt
            && acceptedNanoseconds.compareAndSet(UNSET, nanoseconds);
      }

      boolean started() {
        return requestStartedNanoseconds.get() != UNSET;
      }

      long ordinal() {
        return ordinal;
      }

      String token() {
        return token;
      }

      boolean markDrained(long nanoseconds) {
        long acceptedAt = acceptedNanoseconds.get();
        return acceptedAt != UNSET && nanoseconds >= acceptedAt
            && drainedNanoseconds.compareAndSet(UNSET, nanoseconds);
      }

      boolean completed() {
        return completedNanoseconds.get() != UNSET;
      }

      boolean accepted() {
        return acceptedNanoseconds.get() != UNSET;
      }

      boolean drained() {
        return drainedNanoseconds.get() != UNSET;
      }

      long deliveryDrainNanoseconds() {
        long completedAt = completedNanoseconds.get();
        long drainedAt = drainedNanoseconds.get();
        if (completedAt == UNSET || drainedAt == UNSET)
          return -1L;
        if (drainedAt <= completedAt)
          return 0L;
        return Math.subtractExact(drainedAt, completedAt);
      }
    }

    private static record TelemetryRecord(
        String text,
        long acceptedNanoseconds,
        boolean operational,
        TraceExpectation traceExpectation) {}
  }

  private static record Sample(
      String at,
      long droppedLogRecords,
      long frameworkMetricCardinality,
      long rejectedMetricDeliveries,
      ResourceSnapshot resources,
      long unregisteredMetricDimensions) {
    Map<String, Object> json() {
      return sortedMap(
          "at", at,
          "droppedLogRecords", droppedLogRecords,
          "frameworkMetricCardinality", frameworkMetricCardinality,
          "rejectedMetricDeliveries", rejectedMetricDeliveries,
          "resources", resources.json(),
          "unregisteredMetricDimensions", unregisteredMetricDimensions);
    }
  }

  private static record ScenarioResult(
      String id,
      long expectedOperations,
      long successfulOperations,
      int uniqueAdversarialDimensionValues,
      double drainSeconds) {
    Map<String, Object> json() {
      return sortedMap(
          "expectedOperations", expectedOperations,
          "id", id,
          "successfulOperations", successfulOperations,
          "uniqueAdversarialDimensionValues", uniqueAdversarialDimensionValues);
    }
  }

  private static record HarnessRun(
      String group,
      Policy policy,
      List<Sample> samples,
      List<ScenarioResult> scenarios,
      List<String> outcomes,
      double drainSeconds,
      long droppedLogRecords,
      long frameworkMetricCardinality,
      long logRecordsObserved,
      long metricEventsObserved,
      long metricSamplesObserved,
      long rejectedMetricDeliveries,
      long sensitiveCanaries,
      long terminalFrameworkCardinality,
      long unregisteredMetricDimensions) {
    static HarnessRun from(
        String group,
        Policy policy,
        List<Sample> samples,
        List<ScenarioResult> scenarios,
        List<String> outcomes,
        double drainSeconds,
        MetricAudit metrics,
        TelemetryAudit telemetry) {
      return new HarnessRun(
          group,
          policy,
          List.copyOf(samples),
          List.copyOf(scenarios),
          List.copyOf(outcomes),
          drainSeconds,
          telemetry.droppedLogRecords(),
          metrics.frameworkMetricCardinality(),
          telemetry.logRecordsObserved(),
          metrics.metricEventsObserved(),
          metrics.metricSamplesObserved(),
          metrics.rejectedMetricDeliveries(),
          metrics.sensitiveCanaries() + telemetry.sensitiveCanaries(),
          metrics.frameworkMetricCardinality(),
          metrics.unregisteredMetricDimensions());
    }

    Map<String, Object> transcript() {
      String startedAt = samples.isEmpty() ? Instant.EPOCH.toString() : samples.get(0).at();
      String finishedAt = samples.isEmpty() ? Instant.EPOCH.toString() : samples.get(samples.size() - 1).at();
      return sortedMap(
          "drainSeconds", conservativeDrainSeconds(drainSeconds),
          "droppedLogRecords", droppedLogRecords,
          "finishedAt", finishedAt,
          "formatVersion", 1,
          "frameworkMetricCardinality", frameworkMetricCardinality,
          "group", group,
          "logRecordsObserved", logRecordsObserved,
          "metricEventsObserved", metricEventsObserved,
          "metricSamplesObserved", metricSamplesObserved,
          "outcomes", outcomes,
          "policySha256", policy.policySha256(),
          "rejectedMetricDeliveries", rejectedMetricDeliveries,
          "samples", samples.stream().map(Sample::json).toList(),
          "scenarios", scenarios.stream().map(ScenarioResult::json).toList(),
          "sensitiveCanaries", sensitiveCanaries,
          "startedAt", startedAt,
          "terminalFrameworkCardinality", terminalFrameworkCardinality,
          "unregisteredMetricDimensions", unregisteredMetricDimensions);
    }
  }

  @FunctionalInterface
  private interface Operation {
    void run(int clientIndex, int iteration, String canary) throws Exception;
  }

  @FunctionalInterface
  private interface IndexedOperation {
    void run(int index) throws Exception;
  }

  @SuppressWarnings("unchecked")
  private static <T> Map<String, T> sortedMap(Object... values) {
    if (values.length % 2 != 0)
      throw new IllegalArgumentException("Map entries must be key/value pairs");
    Map<String, T> map = new TreeMap<>();
    for (int index = 0; index < values.length; index += 2)
      map.put((String) values[index], (T) values[index + 1]);
    return map;
  }

  static final class Json {
    private Json() {}

    static String canonical(Object value) {
      StringBuilder output = new StringBuilder();
      append(value, output, 0);
      return output.append('\n').toString();
    }

    private static void append(Object value, StringBuilder output, int indent) {
      if (value == null) {
        output.append("null");
      } else if (value instanceof String string) {
        appendString(string, output);
      } else if (value instanceof Boolean || value instanceof Integer
          || value instanceof Long) {
        output.append(value);
      } else if (value instanceof Map<?, ?> map) {
        appendMap(map, output, indent);
      } else if (value instanceof Iterable<?> iterable) {
        appendArray(iterable, output, indent);
      } else {
        throw new IllegalArgumentException("Unsupported JSON value: " + value.getClass());
      }
    }

    private static void appendMap(Map<?, ?> map, StringBuilder output, int indent) {
      output.append('{');
      if (!map.isEmpty()) {
        List<Map.Entry<?, ?>> entries = new ArrayList<>(map.entrySet());
        entries.sort(Comparator.comparing(entry -> (String) entry.getKey()));
        for (int index = 0; index < entries.size(); ++index) {
          Map.Entry<?, ?> entry = entries.get(index);
          output.append(index == 0 ? '\n' : ",\n");
          indent(output, indent + 2);
          appendString((String) entry.getKey(), output);
          output.append(": ");
          append(entry.getValue(), output, indent + 2);
        }
        output.append('\n');
        indent(output, indent);
      }
      output.append('}');
    }

    private static void appendArray(Iterable<?> iterable, StringBuilder output, int indent) {
      output.append('[');
      int index = 0;
      for (Object value : iterable) {
        output.append(index++ == 0 ? '\n' : ",\n");
        indent(output, indent + 2);
        append(value, output, indent + 2);
      }
      if (index > 0) {
        output.append('\n');
        indent(output, indent);
      }
      output.append(']');
    }

    private static void appendString(String value, StringBuilder output) {
      output.append('"');
      for (int index = 0; index < value.length(); ++index) {
        char character = value.charAt(index);
        switch (character) {
          case '"' -> output.append("\\\"");
          case '\\' -> output.append("\\\\");
          case '\b' -> output.append("\\b");
          case '\f' -> output.append("\\f");
          case '\n' -> output.append("\\n");
          case '\r' -> output.append("\\r");
          case '\t' -> output.append("\\t");
          default -> {
            if (character < 0x20)
              output.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
            else
              output.append(character);
          }
        }
      }
      output.append('"');
    }

    private static void indent(StringBuilder output, int spaces) {
      output.append(" ".repeat(spaces));
    }
  }
}
