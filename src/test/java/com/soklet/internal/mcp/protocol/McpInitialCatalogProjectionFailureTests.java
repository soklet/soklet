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

import com.soklet.HttpMethod;
import com.soklet.LifecycleObserver;
import com.soklet.LifecyclePolicy;
import com.soklet.McpAdmissionController;
import com.soklet.McpCatalogAccessPolicy;
import com.soklet.McpCompleteResult;
import com.soklet.McpEndpoint;
import com.soklet.McpEndpointRegistry;
import com.soklet.McpImplementation;
import com.soklet.McpJsonObject;
import com.soklet.McpJsonRpcError;
import com.soklet.McpPromptOutput;
import com.soklet.McpPromptRegistration;
import com.soklet.McpProtocolVersion;
import com.soklet.McpRateLimitDecision;
import com.soklet.McpRequestContext;
import com.soklet.McpRequestOutcome;
import com.soklet.McpSimulation;
import com.soklet.McpSimulationBodyType;
import com.soklet.McpSimulationResponse;
import com.soklet.McpSubscriptionAuthorization;
import com.soklet.McpSubscriptionConfig;
import com.soklet.McpSubscriptionEventPublisher;
import com.soklet.McpSubscriptionNotificationType;
import com.soklet.McpToolRegistration;
import com.soklet.Request;
import com.soklet.ResourceMethodResolver;
import com.soklet.ShutdownResult;
import com.soklet.SimulatorConfig;
import com.soklet.SokletSimulator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class McpInitialCatalogProjectionFailureTests {
    private static final Set<McpProtocolVersion> VERSIONS = Set.of(McpProtocolVersion.V2026_07_28);

    @AfterEach
    void resetHooks() { McpHttpServerRuntime.setSubscriptionAuthorizationSchedulingTestHooks(null); }

    @Test
    void exhaustedCatalogCapacityReturns503AndReleasesTheSubscriptionReservation() throws Exception {
        assertOpeningFailure(Mode.CAPACITY);
    }

    @Test
    void queuedCatalogDeadlineReturns503AndReleasesTheSubscriptionReservation() throws Exception {
        assertOpeningFailure(Mode.QUEUED_TIMEOUT);
    }

    @Test
    void activeCatalogDeadlineReturns504AndRetainsPhysicalWorkUntilExit() throws Exception {
        assertOpeningFailure(Mode.ACTIVE_TIMEOUT);
    }

    @Test
    void failedCatalogPolicyKeepsTheOriginalThrowableAndSanitizesTheResponse() throws Exception {
        assertOpeningFailure(Mode.FAILED);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void projectionOutcomeDiagnosticsDoNotRenderApplicationThrowables() throws Exception {
        Throwable privateFailure = new Throwable("private-catalog-canary") {
            @Override public String toString() { throw new AssertionError("Application Throwable was rendered"); }
        };
        Class<?> disposition = Class.forName("com.soklet.internal.mcp.protocol.McpHttpServerRuntime$CatalogProjectionDisposition");
        Class<?> projection = Class.forName("com.soklet.internal.mcp.protocol.McpHttpServerRuntime$InitialCatalogProjectionResult");
        var projectionConstructor = projection.getDeclaredConstructors()[0];
        projectionConstructor.setAccessible(true);
        Object projected = projectionConstructor.newInstance(Enum.valueOf((Class) disposition, "FAILED"), null, privateFailure, false);
        Class<?> result = Class.forName("com.soklet.internal.mcp.protocol.McpHttpServerRuntime$SubscriptionOpenResult");
        Class<?> outcome = Class.forName("com.soklet.internal.mcp.protocol.McpHttpServerRuntime$SubscriptionOpenOutcome");
        var outcomeConstructor = outcome.getDeclaredConstructors()[0];
        outcomeConstructor.setAccessible(true);
        Object opened = outcomeConstructor.newInstance(Enum.valueOf((Class) result, "CATALOG_PROJECTION_FAILED"), projected);
        for (Object value : List.of(projected, opened)) {
            var render = value.getClass().getMethod("toString");
            render.setAccessible(true);
            String diagnostic = (String) render.invoke(value);
            assertFalse(diagnostic.contains("private-catalog-canary"));
            assertTrue(diagnostic.length() < 256);
        }
    }

    private static void assertOpeningFailure(Mode mode) throws Exception {
        for (boolean prompts : List.of(false, true)) {
            CountDownLatch holdEntered = new CountDownLatch(1);
            CountDownLatch releaseHold = new CountDownLatch(1);
            CountDownLatch policyEntered = new CountDownLatch(1);
            CountDownLatch policyReturned = new CountDownLatch(1);
            AtomicReference<McpApplicationExecution> application = new AtomicReference<>();
            CountDownLatch releasePolicy = new CountDownLatch(1);
            CountDownLatch observed = new CountDownLatch(1);
            CountDownLatch openingPaused = new CountDownLatch(1);
            CountDownLatch releaseOpening = new CountDownLatch(1);
            AtomicBoolean failOpening = new AtomicBoolean(true);
            AtomicReference<McpRequestOutcome> outcome = new AtomicReference<>();
            AtomicReference<List<Throwable>> failures = new AtomicReference<>();
            RuntimeException original = new IllegalStateException("private-catalog-failure");
            LifecycleObserver observer = new LifecycleObserver() {
                @Override public void didFinishMcpRequestHandling(McpRequestContext context,
                        McpRequestOutcome result, McpJsonRpcError error, Duration duration, List<Throwable> throwables) {
                    if (context.getJsonRpcMethod().equals("subscriptions/listen") && outcome.compareAndSet(null, result)) {
                        failures.set(throwables);
                        observed.countDown();
                    }
                }
            };
            McpCatalogAccessPolicy policy = McpCatalogAccessPolicy.fromEvaluators(
                    (request, registration, features) -> project(request, mode, failOpening, policyEntered, policyReturned, releasePolicy, original),
                    (request, registration, features) -> project(request, mode, failOpening, policyEntered, policyReturned, releasePolicy, original));
            McpToolRegistration<McpJsonObject> hold = McpToolRegistration.withName("hold", VERSIONS)
                    .jsonObjectArguments().handler((request, arguments, features) -> {
                        holdEntered.countDown();
                        awaitUninterruptibly(releaseHold);
                        return McpCompleteResult.fromToolText("released");
                    }).build();
            McpEndpoint endpoint = McpEndpoint.withPath("/mcp",
                    McpImplementation.withNameAndVersion("initial-catalog-failure", "1").build(), VERSIONS)
                    .toolRegistrations(List.of(hold))
                    .promptRegistrations(List.of(McpPromptRegistration.withName("prompt", VERSIONS)
                            .handler((request, prompt, features) -> McpCompleteResult.fromPromptOutput(McpPromptOutput.fromMessages()))
                            .build()))
                    .subscriptionProtocolVersions(VERSIONS)
                    .subscriptionConfig(McpSubscriptionConfig.withEventPublisherAndNotificationTypes(
                            McpSubscriptionEventPublisher.fromInMemoryDefaults(),
                            Set.of(McpSubscriptionNotificationType.TOOLS_LIST_CHANGED,
                                    McpSubscriptionNotificationType.PROMPTS_LIST_CHANGED)).build()).build();
            McpHttpServerRuntime.setSubscriptionAuthorizationSchedulingTestHooks(
                    new McpHttpServerRuntime.SubscriptionAuthorizationSchedulingTestHooks() {
                        @Override public void beforeInitialCatalogProjection(McpApplicationExecution execution) {
                            application.set(execution);
                            if (failOpening.get() && (mode == Mode.CAPACITY || mode == Mode.QUEUED_TIMEOUT)) {
                                openingPaused.countDown();
                                awaitUninterruptibly(releaseOpening);
                            }
                        }
                    });
            SimulatorConfig config = SimulatorConfig.builder().configureMcpServer(builder -> builder
                    .port(0).host("127.0.0.1").allowedHosts(Set.of("127.0.0.1"))
                    .endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
                    .admissionController(McpAdmissionController.acceptAllInstance())
                    .requestRateLimiter(context -> McpRateLimitDecision.allowed())
                    .toolRateLimiter(context -> McpRateLimitDecision.allowed())
                    .subscriptionAuthorizer((context, features) -> McpSubscriptionAuthorization.Allowed
                            .fromValidUntil(Instant.now().plusSeconds(60)))
                    .catalogAccessPolicy(policy).requestHandlerConcurrency(1).requestHandlerQueueCapacity(1)
                    .requestTimeout(Duration.ofSeconds(5))
                    .subscriptionCatalogProjectionTimeout(mode == Mode.FAILED || mode == Mode.CAPACITY
                            ? Duration.ofSeconds(2) : Duration.ofMillis(100))
                    .maximumSubscriptionsPerPartition(1))
                    .resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
                    .lifecycleObservers(List.of(observer))
                    .lifecyclePolicy(LifecyclePolicy.builder().startupTimeout(Duration.ofSeconds(3))
                            .startupCancelationTimeout(Duration.ofMillis(200))
                            .gracefulShutdownTimeout(Duration.ofMillis(200))
                            .forcedShutdownTimeout(Duration.ofMillis(200)).build()).build();
            ShutdownResult shutdown = SokletSimulator.run(config, simulator -> {
                McpSimulation first = null;
                Thread queuedCaller = null;
                FutureTask<Boolean> queuedWork = null;
                McpSimulation failed = null;
                try {
                    if (mode == Mode.CAPACITY || mode == Mode.QUEUED_TIMEOUT) {
                        failed = simulator.startMcpRequest(request("failed", true, prompts));
                        assertTrue(openingPaused.await(3, TimeUnit.SECONDS));
                        await(() -> application.get().snapshot().activeHandlerSlots() == 0);
                        first = simulator.startMcpRequest(request("hold-first", false, false));
                        assertTrue(holdEntered.await(3, TimeUnit.SECONDS));
                        if (mode == Mode.CAPACITY) {
                            // A generic policy caller fills the queue behind this held handler
                            // without depending on the direct-caller waiting allowance. It must
                            // exhaust the same bound.
                            queuedWork = new FutureTask<>(() -> application.get().invokeBoundedPolicy(
                                    () -> Boolean.TRUE, System.nanoTime() + TimeUnit.SECONDS.toNanos(5)));
                            queuedCaller = new Thread(queuedWork, "mcp-initial-projection-queued-holder");
                            queuedCaller.start();
                            await(() -> application.get().snapshot().queuedRequests() == 1);
                            assertFalse(queuedWork.isDone(), "The generic policy caller must remain queued.");
                        }
                        releaseOpening.countDown();
                    } else failed = simulator.startMcpRequest(request("failed", true, prompts));
                    if (mode == Mode.ACTIVE_TIMEOUT) assertTrue(policyEntered.await(3, TimeUnit.SECONDS));
                    McpSimulationResponse response = failed.awaitResponse(Duration.ofSeconds(3)).orElseThrow();
                    int status = mode == Mode.ACTIVE_TIMEOUT ? 504 : mode == Mode.FAILED ? 500 : 503;
                    assertEquals(status, response.getStatusCode());
                    assertEquals("{\"jsonrpc\":\"2.0\",\"id\":\"failed\",\"error\":{\"code\":-32603,\"message\":\"Internal error\"}}",
                            new String(response.getBody().orElseThrow(), StandardCharsets.UTF_8));
                    assertEquals(List.of("no-store"), response.getHeaders().get("Cache-Control"));
                    assertTrue(observed.await(3, TimeUnit.SECONDS));
                    assertEquals(mode == Mode.FAILED ? McpRequestOutcome.INTERNAL_ERROR
                            : mode == Mode.CAPACITY ? McpRequestOutcome.REJECTED : McpRequestOutcome.DEADLINE_EXCEEDED,
                            outcome.get());
                    if (mode == Mode.FAILED) assertTrue(failures.get().stream().anyMatch(failure -> failure == original));
                    else assertTrue(failures.get().isEmpty());
                    if (mode == Mode.ACTIVE_TIMEOUT) {
                        assertEquals(1L, policyReturned.getCount(), "Timed-out catalog application code remains physically blocked.");
                        assertEquals(1, application.get().snapshot().activeHandlerSlots(), "Timed-out policy work remains physically retained.");
                    }
                    if (mode == Mode.CAPACITY || mode == Mode.QUEUED_TIMEOUT)
                        assertEquals(1L, policyEntered.getCount(), "Unadmitted policy work cannot enter application code.");
                    failOpening.set(false);
                    releasePolicy.countDown();
                    releaseHold.countDown();
                    FutureTask<Boolean> queuedCompletion = queuedWork;
                    Thread queuedThread = queuedCaller;
                    await(() -> application.get().snapshot().activeHandlerSlots() == 0
                            && application.get().snapshot().queuedRequests() == 0
                            && (queuedCompletion == null || queuedCompletion.isDone()
                                    && queuedThread != null && !queuedThread.isAlive()));
                    if (queuedWork != null)
                        assertEquals(Boolean.TRUE, queuedWork.get(0, TimeUnit.NANOSECONDS),
                                "Generic queued work must still run after the holder releases.");
                    try (McpSimulation recovery = simulator.startMcpRequest(request("recovered", true, prompts))) {
                        McpSimulationResponse recovered = recovery.awaitResponse(Duration.ofSeconds(3)).orElseThrow();
                        assertEquals(200, recovered.getStatusCode(), "The failed opening leaked its single subscription reservation.");
                        assertEquals(McpSimulationBodyType.SSE, recovered.getBodyType());
                    }
                } finally {
                    releaseOpening.countDown();
                    failOpening.set(false);
                    releasePolicy.countDown();
                    releaseHold.countDown();
                    if (failed != null) failed.close();
                    if (queuedCaller != null) {
                        queuedCaller.join(TimeUnit.SECONDS.toMillis(1));
                        assertFalse(queuedCaller.isAlive(), "The generic queued policy caller did not retire.");
                    }
                    if (first != null) first.close();
                }
            });
            assertTrue(shutdown.isComplete(), shutdown.toString());
        }
    }

    private static boolean project(McpRequestContext request, Mode mode, AtomicBoolean failOpening,
            CountDownLatch entered, CountDownLatch returned, CountDownLatch release, RuntimeException failure) {
        if (failOpening.get() && (request.getJsonRpcMethod().equals("tools/list")
                || request.getJsonRpcMethod().equals("prompts/list"))) {
            entered.countDown();
            try {
                if (mode == Mode.FAILED) throw failure;
                awaitUninterruptibly(release);
            } finally { returned.countDown(); }
        }
        return true;
    }

    private static Request request(String id, boolean subscription, boolean prompts) {
        String method = subscription ? "subscriptions/listen" : "tools/call";
        String fields = subscription ? "\"notifications\":{\"" + (prompts ? "promptsListChanged" : "toolsListChanged") + "\":true}"
                : "\"name\":\"hold\",\"arguments\":{}";
        String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\",\"method\":\"" + method + "\",\"params\":{" + fields
                + ",\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\",\"io.modelcontextprotocol/clientCapabilities\":{}}}}";
        var headers = new java.util.LinkedHashMap<String, List<String>>();
        headers.put("Host", List.of("127.0.0.1:0"));
        headers.put("Content-Type", List.of("application/json"));
        headers.put("Accept", List.of("application/json, text/event-stream"));
        headers.put("MCP-Protocol-Version", List.of("2026-07-28"));
        headers.put("Mcp-Method", List.of(method));
        if (!subscription) headers.put("Mcp-Name", List.of("hold"));
        return Request.withPath(HttpMethod.POST, "/mcp").headers(headers)
                .body(body.getBytes(StandardCharsets.UTF_8)).build();
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() - deadline < 0) Thread.sleep(5L);
        assertTrue(condition.getAsBoolean(), "Runtime work did not reach its expected state.");
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        while (true) {
            try { assertTrue(latch.await(5, TimeUnit.SECONDS)); return; }
            catch (InterruptedException ignored) { /* The test releases physical work explicitly. */ }
        }
    }

    private enum Mode { CAPACITY, QUEUED_TIMEOUT, ACTIVE_TIMEOUT, FAILED }
}
