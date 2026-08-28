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

import com.soklet.annotation.GET;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.annotation.concurrent.ThreadSafe;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@ThreadSafe
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class SokletSimulatorIsolationTests {
	private static final String LOOPBACK = "127.0.0.1";
	private static final String MCP_PATH = "/mcp";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final Duration WAIT = Duration.ofSeconds(5);
	private static final LifecyclePolicy TEST_LIFECYCLE_POLICY =
			LifecyclePolicy.builder()
					.startupTimeout(Duration.ofSeconds(5))
					.startupCancellationTimeout(Duration.ofSeconds(2))
					.gracefulShutdownDuration(Duration.ofSeconds(2))
					.forcedShutdownDuration(Duration.ofSeconds(1))
					.build();

	@Test
	public void factoryRunsExactlyOnceAndSequentialScopesUseFreshGraphs()
			throws Exception {
		AtomicInteger factoryCalls = new AtomicInteger();
		AtomicInteger bodyCalls = new AtomicInteger();
		List<HttpServer> httpServers = new ArrayList<>();
		SimulatorConfigFactory factory = transports -> {
			factoryCalls.incrementAndGet();
			httpServers.add(transports.getHttpServer());
			return httpConfig(transports);
		};

		for (int invocation = 0; invocation < 2; invocation++) {
			SokletSimulator.run(factory, simulator -> {
				bodyCalls.incrementAndGet();
				Assertions.assertEquals("isolated", responseBody(
						simulator.performHttpRequest(Request.withPath(
								HttpMethod.GET, "/isolation").build())));
			});
		}

		Assertions.assertEquals(2, factoryCalls.get());
		Assertions.assertEquals(2, bodyCalls.get());
		Assertions.assertEquals(2, httpServers.size());
		Assertions.assertNotSame(httpServers.get(0), httpServers.get(1));
	}

	@Test
	public void defaultParameterProviderBindsEachFreshFactoryConfig()
			throws Exception {
		ScopedInstanceProvider existingInstances =
				new ScopedInstanceProvider("existing");
		SokletConfig existingConfig = SokletConfig
				.withHttpServer(new TrackingHttpServer())
				.resourceMethodResolver(providerResourceMethods())
				.instanceProvider(existingInstances)
				.build();
		ResourceMethodParameterProvider existingParameters =
				existingConfig.getResourceMethodParameterProvider();
		List<SokletConfig> freshConfigs = new ArrayList<>();
		List<InstanceProvider> freshInstances = new ArrayList<>();
		List<ResourceMethodParameterProvider> freshParameters = new ArrayList<>();

		for (int invocation = 1; invocation <= 2; invocation++) {
			String scopeId = "default-" + invocation;
			SokletSimulator.run(transports -> {
				ScopedInstanceProvider instances =
						new ScopedInstanceProvider(scopeId);
				SokletConfig config = SokletConfig
						.withHttpServer(transports.getHttpServer())
						.resourceMethodResolver(providerResourceMethods())
						.instanceProvider(instances)
						.lifecyclePolicy(TEST_LIFECYCLE_POLICY)
						.build();
				freshConfigs.add(config);
				freshInstances.add(config.getInstanceProvider());
				freshParameters.add(
						config.getResourceMethodParameterProvider());
				return config;
			}, simulator -> Assertions.assertEquals(scopeId + ":" + scopeId,
					responseBody(simulator.performHttpRequest(Request.withPath(
							HttpMethod.GET, "/provider-scope").build()))));
		}

		Assertions.assertEquals(2, freshConfigs.size());
		Assertions.assertNotSame(freshConfigs.get(0), freshConfigs.get(1));
		Assertions.assertInstanceOf(DefaultResourceMethodParameterProvider.class,
				freshParameters.get(0));
		Assertions.assertInstanceOf(DefaultResourceMethodParameterProvider.class,
				freshParameters.get(1));
		Assertions.assertNotSame(existingParameters, freshParameters.get(0));
		Assertions.assertNotSame(existingParameters, freshParameters.get(1));
		Assertions.assertNotSame(freshParameters.get(0), freshParameters.get(1));
		Assertions.assertNotSame(existingInstances, freshInstances.get(0));
		Assertions.assertNotSame(existingInstances, freshInstances.get(1));
		Assertions.assertNotSame(freshInstances.get(0), freshInstances.get(1));
	}

	@Test
	public void customParameterAndInstanceProvidersAreFreshPerFactoryScope()
			throws Exception {
		ScopedInstanceProvider existingInstances =
				new ScopedInstanceProvider("existing");
		ScopedParameterProvider existingParameters =
				new ScopedParameterProvider("existing");
		SokletConfig existingConfig = SokletConfig
				.withHttpServer(new TrackingHttpServer())
				.resourceMethodResolver(providerResourceMethods())
				.instanceProvider(existingInstances)
				.resourceMethodParameterProvider(existingParameters)
				.build();
		List<InstanceProvider> freshInstances = new ArrayList<>();
		List<ResourceMethodParameterProvider> freshParameters = new ArrayList<>();

		for (int invocation = 1; invocation <= 2; invocation++) {
			String scopeId = "custom-" + invocation;
			SokletSimulator.run(transports -> {
				ScopedInstanceProvider instances =
						new ScopedInstanceProvider(scopeId);
				ScopedParameterProvider parameters =
						new ScopedParameterProvider(scopeId);
				SokletConfig config = SokletConfig
						.withHttpServer(transports.getHttpServer())
						.resourceMethodResolver(providerResourceMethods())
						.instanceProvider(instances)
						.resourceMethodParameterProvider(parameters)
						.lifecyclePolicy(TEST_LIFECYCLE_POLICY)
						.build();
				Assertions.assertSame(instances, config.getInstanceProvider());
				Assertions.assertSame(parameters,
						config.getResourceMethodParameterProvider());
				freshInstances.add(instances);
				freshParameters.add(parameters);
				return config;
			}, simulator -> Assertions.assertEquals(scopeId + ":" + scopeId,
					responseBody(simulator.performHttpRequest(Request.withPath(
							HttpMethod.GET, "/provider-scope").build()))));
		}

		Assertions.assertSame(existingInstances,
				existingConfig.getInstanceProvider());
		Assertions.assertSame(existingParameters,
				existingConfig.getResourceMethodParameterProvider());
		Assertions.assertNotSame(existingInstances, freshInstances.get(0));
		Assertions.assertNotSame(existingInstances, freshInstances.get(1));
		Assertions.assertNotSame(freshInstances.get(0), freshInstances.get(1));
		Assertions.assertNotSame(existingParameters, freshParameters.get(0));
		Assertions.assertNotSame(existingParameters, freshParameters.get(1));
		Assertions.assertNotSame(freshParameters.get(0), freshParameters.get(1));
	}

	@Test
	public void concurrentFreshScopesDoNotCrossDeliverCallbacks()
			throws Exception {
		CountDownLatch scopesReady = new CountDownLatch(2);
		CountDownLatch releaseScopes = new CountDownLatch(1);
		ScopeCallbackProbe firstProbe = new ScopeCallbackProbe();
		ScopeCallbackProbe secondProbe = new ScopeCallbackProbe();
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<String> first = executor.submit(() -> runConcurrentScope(
					"first", scopesReady, releaseScopes, firstProbe));
			Future<String> second = executor.submit(() -> runConcurrentScope(
					"second", scopesReady, releaseScopes, secondProbe));

			Assertions.assertTrue(scopesReady.await(WAIT.toMillis(),
					TimeUnit.MILLISECONDS), "Concurrent scopes did not become ready");
			releaseScopes.countDown();

			Assertions.assertEquals("first:first",
					first.get(WAIT.toMillis(), TimeUnit.MILLISECONDS));
			Assertions.assertEquals("second:second",
					second.get(WAIT.toMillis(), TimeUnit.MILLISECONDS));
			Assertions.assertEquals(List.of("start:first-request",
					"finish:first-request"), firstProbe.requestCallbacks);
			Assertions.assertEquals(List.of("start:second-request",
					"finish:second-request"), secondProbe.requestCallbacks);
			Assertions.assertEquals(0, firstProbe.serverTransitions.get());
			Assertions.assertEquals(0, secondProbe.serverTransitions.get());
		} finally {
			releaseScopes.countDown();
			executor.shutdownNow();
			Assertions.assertTrue(executor.awaitTermination(WAIT.toMillis(),
					TimeUnit.MILLISECONDS), "Concurrent scope workers did not stop");
		}
	}

	@Test
	public void rejectsProductionAndForeignTransportsBeforeInitialization()
			throws Exception {
		TrackingHttpServer production = new TrackingHttpServer();
		AtomicInteger bodies = new AtomicInteger();
		Assertions.assertThrows(IllegalStateException.class,
				() -> SokletSimulator.run(ignored -> SokletConfig
						.withHttpServer(production)
						.resourceMethodResolver(resourceMethods())
						.build(), simulator -> bodies.incrementAndGet()));
		Assertions.assertEquals(0, production.initializeCalls.get());
		Assertions.assertEquals(0, bodies.get());

		AtomicReference<HttpServer> firstScopeTransport = new AtomicReference<>();
		SokletSimulator.run(transports -> {
			firstScopeTransport.set(transports.getHttpServer());
			return httpConfig(transports);
		}, simulator -> simulator.performHttpRequest(Request.withPath(
				HttpMethod.GET, "/isolation").build()));

		Assertions.assertThrows(IllegalStateException.class,
				() -> SokletSimulator.run(ignored -> SokletConfig
						.withHttpServer(firstScopeTransport.get())
						.resourceMethodResolver(resourceMethods())
						.build(), simulator -> bodies.incrementAndGet()));
		Assertions.assertEquals(0, bodies.get());
	}

	@Test
	public void allSimulatorDispatchRejectsAfterScope() throws Exception {
		AtomicReference<Simulator> escaped = new AtomicReference<>();
		SokletSimulator.run(SokletSimulatorIsolationTests::httpConfig,
				escaped::set);

		Simulator simulator = escaped.get();
		Assertions.assertNotNull(simulator);
		Assertions.assertThrows(IllegalStateException.class,
				() -> simulator.performHttpRequest(Request.withPath(
						HttpMethod.GET, "/isolation").build()));
		Assertions.assertThrows(IllegalStateException.class,
				() -> simulator.performSseRequest(Request.withPath(
						HttpMethod.GET, "/events").build()));
		Assertions.assertThrows(IllegalStateException.class,
				() -> simulator.startMcpRequest(mcpRequest("closed", "complete", 0,
						Optional.empty())));
		Assertions.assertThrows(IllegalStateException.class,
				() -> simulator.onBroadcastError(ignored -> {
				}));
		Assertions.assertThrows(IllegalStateException.class,
				() -> simulator.onUnicastError(ignored -> {
				}));
	}

	@Test
	public void checkedBodyFailureRetainsExactIdentity() {
		CheckedCanary failure = new CheckedCanary();
		CheckedCanary thrown = Assertions.assertThrows(CheckedCanary.class,
				() -> SokletSimulator.run(
						SokletSimulatorIsolationTests::httpConfig,
						simulator -> {
							throw failure;
						}));
		Assertions.assertSame(failure, thrown);
		Assertions.assertEquals(0, thrown.getSuppressed().length);
	}

	@Test
	public void incompleteTeardownPreservesBodyFailurePrecedenceAndFailsSuccess()
			throws Exception {
		CheckedCanary bodyFailure = new CheckedCanary();
		DeferredLifecycleLauncher failedBodyLauncher =
				new DeferredLifecycleLauncher();
		LifecycleWorkers failedBodyWorkers =
				new LifecycleWorkers(failedBodyLauncher::launch);
		try {
			CheckedCanary thrown = Assertions.assertThrows(CheckedCanary.class,
					() -> SokletSimulator.run(
							SokletSimulatorIsolationTests::immediatelyIncompleteConfig,
							SimulatorOptions.defaultInstance(), simulator -> {
								throw bodyFailure;
							}, () -> 0L, failedBodyWorkers));
			Assertions.assertSame(bodyFailure, thrown);
			Assertions.assertEquals(1, thrown.getSuppressed().length);
			ShutdownIncompleteException suppressed = Assertions.assertInstanceOf(
					ShutdownIncompleteException.class, thrown.getSuppressed()[0]);
			assertIncompleteLifecycleCall(suppressed);
			Assertions.assertEquals(2, failedBodyLauncher.queuedTaskCount());
			Assertions.assertEquals(2, failedBodyWorkers.active(
					LifecycleWorkers.Role.LIFECYCLE_CALL));
		} finally {
			failedBodyLauncher.releaseAll();
		}
		Assertions.assertEquals(0, failedBodyWorkers.active(
				LifecycleWorkers.Role.LIFECYCLE_CALL));

		DeferredLifecycleLauncher successfulBodyLauncher =
				new DeferredLifecycleLauncher();
		LifecycleWorkers successfulBodyWorkers =
				new LifecycleWorkers(successfulBodyLauncher::launch);
		AtomicReference<SimulatorTransports> retainedScope = new AtomicReference<>();
		try {
			ShutdownIncompleteException thrown = Assertions.assertThrows(
					ShutdownIncompleteException.class,
					() -> SokletSimulator.run(
							transports -> {
								retainedScope.set(transports);
								return immediatelyIncompleteConfig(transports);
							},
							SimulatorOptions.defaultInstance(), simulator -> {
							}, () -> 0L, successfulBodyWorkers));
			assertIncompleteLifecycleCall(thrown);
			Assertions.assertTrue(thrown.retainsScopeEvidence(
					retainedScope.get()));
			Assertions.assertEquals(2, successfulBodyLauncher.queuedTaskCount());
			Assertions.assertEquals(2, successfulBodyWorkers.active(
					LifecycleWorkers.Role.LIFECYCLE_CALL));
		} finally {
			successfulBodyLauncher.releaseAll();
		}
		Assertions.assertEquals(0, successfulBodyWorkers.active(
				LifecycleWorkers.Role.LIFECYCLE_CALL));
	}

	@Test
	public void blockedFrameworkSetupUsesOneExactStartupAndRollbackSchedule()
			throws Exception {
		AtomicLong now = new AtomicLong(100L);
		CountDownLatch resolverEntered = new CountDownLatch(1);
		CountDownLatch resolverInterrupted = new CountDownLatch(1);
		CountDownLatch releaseResolver = new CountDownLatch(1);
		CountDownLatch setupWorkerDone = new CountDownLatch(1);
		AtomicInteger bodies = new AtomicInteger();
		AtomicInteger waitIndex = new AtomicInteger();
		List<Long> observedDeadlines = new CopyOnWriteArrayList<>();
		List<String> workerNames = new CopyOnWriteArrayList<>();
		ResourceMethodResolver complete = resourceMethods();
		ResourceMethodResolver blocking = new ResourceMethodResolver() {
			@Override
			@NonNull
			public Optional<ResourceMethod> resourceMethodForRequest(
					@NonNull Request request, @NonNull ServerType serverType) {
				return complete.resourceMethodForRequest(request, serverType);
			}

			@Override
			@NonNull
			public Set<@NonNull ResourceMethod> getResourceMethods() {
				resolverEntered.countDown();
				boolean released = false;
				while (!released) {
					try {
						released = releaseResolver.await(WAIT.toMillis(),
								TimeUnit.MILLISECONDS);
					} catch (InterruptedException cancellation) {
						resolverInterrupted.countDown();
					}
				}
				return complete.getResourceMethods();
			}
		};
		DeadlineWaiter waiter = new DeadlineWaiter(now::get,
				(monitor, remainingNanos) -> {
					int phase = waitIndex.getAndIncrement();
					long expectedRemaining = switch (phase) {
						case 0 -> 10L;
						case 1 -> 20L;
						case 2 -> 30L;
						case 3 -> 40L;
						default -> throw new AssertionError(
								"Unexpected simulator lifecycle wait " + phase);
					};
					Assertions.assertEquals(expectedRemaining, remainingNanos);
					if (phase == 0)
						Assertions.assertTrue(resolverEntered.await(
								WAIT.toMillis(), TimeUnit.MILLISECONDS));
					if (phase == 1)
						Assertions.assertTrue(resolverInterrupted.await(
								WAIT.toMillis(), TimeUnit.MILLISECONDS),
								"Startup cancellation must interrupt the setup call");
					if (phase == 2)
						Assertions.assertFalse(workerNames.contains(
								"lifecycle-force-framework_startup"),
								"Force cannot be submitted before the grace boundary");
					if (phase == 3)
						Assertions.assertTrue(workerNames.contains(
								"lifecycle-force-framework_startup"));
					long deadline = now.addAndGet(remainingNanos);
					observedDeadlines.add(deadline);
				});
		LifecycleWorkers workers = new LifecycleWorkers((name, task) -> {
			workerNames.add(name);
			Thread worker = new Thread(() -> {
				try {
					task.run();
				} finally {
					if (name.equals("simulator-framework-setup"))
						setupWorkerDone.countDown();
				}
			}, "simulator-exact-deadline-" + name);
			worker.setDaemon(true);
			worker.start();
		});
		InternalLifecyclePolicy policy = new InternalLifecyclePolicy(
				Optional.of(Duration.ofNanos(10L)), Duration.ofNanos(20L),
				Duration.ofNanos(30L), Duration.ofNanos(40L));

		try {
			SokletStartupException thrown = Assertions.assertThrows(
					SokletStartupException.class, () -> SokletSimulator.run(
							transports -> SokletConfig
									.withHttpServer(transports.getHttpServer())
									.resourceMethodResolver(blocking)
									.internalLifecyclePolicy(policy)
									.build(),
							SimulatorOptions.defaultInstance(),
							simulator -> bodies.incrementAndGet(), now::get,
							waiter, workers));
			Assertions.assertEquals(StartupDisposition.TIMED_OUT,
					thrown.getStartupDisposition());
			Assertions.assertInstanceOf(java.util.concurrent.TimeoutException.class,
					thrown.getCause());
			Assertions.assertEquals(List.of(110L, 130L, 160L, 200L),
					observedDeadlines);
			Assertions.assertEquals(0, bodies.get());
			InternalShutdownResult result = thrown.getInternalShutdownResult();
			Assertions.assertEquals(InternalStartupDisposition.TIMED_OUT,
					result.startupDisposition());
			InternalParticipantShutdownResult setup = result.participantResult(
					InternalParticipantKind.FRAMEWORK_STARTUP).orElseThrow();
			Assertions.assertEquals(
					InternalParticipantShutdownDisposition.TERMINATION_UNKNOWN,
					setup.disposition());
			Assertions.assertEquals(Set.of(
					InternalResidualActivityKind.LIFECYCLE_CALL),
					setup.residualActivity());
			Assertions.assertEquals(
					InternalParticipantShutdownDisposition.NOT_STARTED,
					result.participantResult(InternalParticipantKind.HTTP)
							.orElseThrow().disposition());
		} finally {
			releaseResolver.countDown();
			Assertions.assertTrue(setupWorkerDone.await(WAIT.toMillis(),
					TimeUnit.MILLISECONDS));
		}
	}

	@Test
	public void synchronousSetupFailureLeavesConfiguredIngressNotStarted() {
		IllegalStateException setupFailure = new IllegalStateException(
				"simulated resolver failure");
		ResourceMethodResolver resolver = new ResourceMethodResolver() {
			@Override
			@NonNull
			public Optional<ResourceMethod> resourceMethodForRequest(
					@NonNull Request request, @NonNull ServerType serverType) {
				return Optional.empty();
			}

			@Override
			@NonNull
			public Set<@NonNull ResourceMethod> getResourceMethods() {
				throw setupFailure;
			}
		};

		SokletStartupException thrown = Assertions.assertThrows(
				SokletStartupException.class, () -> SokletSimulator.run(
						transports -> SokletConfig
								.withHttpServer(transports.getHttpServer())
								.resourceMethodResolver(resolver)
								.build(), simulator -> Assertions.fail(
								"The body cannot run after setup failure")));

		Assertions.assertSame(setupFailure, thrown.getCause());
		Assertions.assertEquals(0, thrown.getSuppressed().length);
		InternalShutdownResult result = thrown.getInternalShutdownResult();
		Assertions.assertTrue(result.isComplete());
		Assertions.assertEquals(InternalStartupDisposition.FAILED,
				result.startupDisposition());
		Assertions.assertEquals(
				InternalParticipantShutdownDisposition.NOT_STARTED,
				result.participantResult(InternalParticipantKind.HTTP)
						.orElseThrow().disposition());
		Assertions.assertTrue(result.participantResult(
				InternalParticipantKind.FRAMEWORK_STARTUP).isEmpty());
	}

	@Test
	public void rejectedParticipantStartLaunchRemainsNotStarted() {
		LifecycleLaunchCanary launchFailure = new LifecycleLaunchCanary();
		AtomicInteger bodies = new AtomicInteger();
		LifecycleWorkers workers = new LifecycleWorkers((name, task) -> {
			if (name.equals("simulator-start-http"))
				throw launchFailure;
			task.run();
		});

		SokletStartupException thrown = Assertions.assertThrows(
				SokletStartupException.class, () -> SokletSimulator.run(
						SokletSimulatorIsolationTests::httpConfig,
						SimulatorOptions.defaultInstance(),
						simulator -> bodies.incrementAndGet(), NanoClock.system(),
						workers));

		Assertions.assertSame(launchFailure, thrown.getCause());
		Assertions.assertEquals(0, bodies.get());
		InternalShutdownResult result = thrown.getInternalShutdownResult();
		Assertions.assertTrue(result.isComplete());
		Assertions.assertEquals(
				InternalParticipantShutdownDisposition.NOT_STARTED,
				result.participantResult(InternalParticipantKind.HTTP)
						.orElseThrow().disposition());
	}

	@Test
	public void liveMcpStartQuiescesBeforeCancellationAndCatchesUpToForce()
			throws Exception {
		AtomicLong now = new AtomicLong(100L);
		CountDownLatch startEntered = new CountDownLatch(1);
		CountDownLatch startInterrupted = new CountDownLatch(1);
		CountDownLatch releaseStart = new CountDownLatch(1);
		CountDownLatch registrationClosed = new CountDownLatch(1);
		List<String> events = new CopyOnWriteArrayList<>();
		List<String> workerNames = new CopyOnWriteArrayList<>();
		AtomicInteger waitIndex = new AtomicInteger();
		McpSubscriptionEventPublisher publisher =
				new McpSubscriptionEventPublisher() {
			@Override
			public McpSubscriptionEventRegistration subscribe(
					@NonNull McpSubscriptionEventListener listener) {
				events.add("start-entered");
				startEntered.countDown();
				boolean released = false;
				while (!released) {
					try {
						released = releaseStart.await(WAIT.toMillis(),
								TimeUnit.MILLISECONDS);
					} catch (InterruptedException cancellation) {
						events.add("start-interrupted");
						startInterrupted.countDown();
					}
				}
				return registrationClosed::countDown;
			}

			@Override
			public void publish(@NonNull McpSubscriptionEvent event) {
			}
		};
		DeadlineWaiter waiter = new DeadlineWaiter(now::get,
				(monitor, remainingNanos) -> {
					int phase = waitIndex.getAndIncrement();
					if (phase == 0) {
						Assertions.assertEquals(10L, remainingNanos);
						Assertions.assertTrue(startEntered.await(WAIT.toMillis(),
								TimeUnit.MILLISECONDS));
						now.addAndGet(remainingNanos);
						return;
					}
					if (phase == 1) {
						Assertions.assertEquals(20L, remainingNanos);
						Assertions.assertTrue(startInterrupted.await(
								WAIT.toMillis(), TimeUnit.MILLISECONDS));
						int quiesce = events.indexOf("cancellation-quiesce");
						int cancellation = events.indexOf("start-interrupted");
						Assertions.assertTrue(quiesce >= 0
								&& quiesce < cancellation,
								"Safe quiesce submission must precede startup cancellation");
						now.addAndGet(remainingNanos);
						return;
					}
					if (phase == 2) {
						Assertions.assertEquals(30L, remainingNanos);
						Assertions.assertFalse(workerNames.contains(
								"lifecycle-force-mcp"));
						now.addAndGet(remainingNanos);
						return;
					}
					Assertions.assertEquals(40L, remainingNanos);
					Assertions.assertTrue(workerNames.contains(
							"lifecycle-force-mcp"));
					releaseStart.countDown();
					monitor.wait(WAIT.toMillis());
				});
		LifecycleWorkers workers = new LifecycleWorkers((name, task) -> {
			workerNames.add(name);
			if (name.equals("simulator-cancellation-quiesce-mcp"))
				events.add("cancellation-quiesce");
			if (name.equals("simulator-start-mcp")
					|| name.startsWith("simulator-mcp-termination-observer-")) {
				Thread worker = new Thread(task, "simulator-live-start-" + name);
				worker.setDaemon(true);
				worker.start();
				return;
			}
			task.run();
		});
		InternalLifecyclePolicy policy = new InternalLifecyclePolicy(
				Optional.of(Duration.ofNanos(10L)), Duration.ofNanos(20L),
				Duration.ofNanos(30L), Duration.ofNanos(40L));

		try {
			SokletStartupException thrown = Assertions.assertThrows(
					SokletStartupException.class, () -> SokletSimulator.run(
							transports -> {
								McpServer server = subscriptionServer(transports,
										List.of(subscriptionEndpoint("/blocking", publisher)));
								return SokletConfig.withMcpServer(server)
										.resourceMethodResolver(ResourceMethodResolver
												.fromMethods(Set.of()))
										.internalLifecyclePolicy(policy)
										.build();
							}, SimulatorOptions.defaultInstance(),
							simulator -> Assertions.fail(
									"The body cannot run after startup timeout"),
							now::get, waiter, workers));
			Assertions.assertEquals(StartupDisposition.TIMED_OUT,
					thrown.getStartupDisposition());
			Assertions.assertTrue(registrationClosed.await(WAIT.toMillis(),
					TimeUnit.MILLISECONDS));
			Assertions.assertFalse(workerNames.contains(
					"simulator-mcp-termination-observer-graceful"),
					"A live start returning after grace must catch up directly to force");
			Assertions.assertEquals(
					InternalParticipantShutdownDisposition.FORCED_TERMINATION,
					thrown.getInternalShutdownResult().participantResult(
							InternalParticipantKind.MCP).orElseThrow().disposition());
		} finally {
			releaseStart.countDown();
		}
	}

	@Test
	public void sealedScopeRetainsRejectedMcpSessionUntilRollbackTerminates()
			throws Exception {
		CountDownLatch executorSupplierEntered = new CountDownLatch(1);
		CountDownLatch releaseExecutorSupplier = new CountDownLatch(1);
		CountDownLatch executorTaskEntered = new CountDownLatch(1);
		CountDownLatch releaseExecutorTask = new CountDownLatch(1);
		AtomicReference<ExecutorService> handlerExecutor = new AtomicReference<>();
		McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH)
				.serverInformation(McpImplementation.withNameAndVersion(
						"rejected-session-proof-test", "4.0.0-SNAPSHOT").build())
				.build();
		DefaultMcpServer server = (DefaultMcpServer) McpServer.withPort(0)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.admissionController(McpAdmissionController.acceptAllInstance())
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.requestHandlerExecutorServiceSupplier(() -> {
					ExecutorService executor = Executors.newSingleThreadExecutor();
					handlerExecutor.set(executor);
					executor.submit(() -> {
						executorTaskEntered.countDown();
						try {
							releaseExecutorTask.await();
						} catch (InterruptedException interruption) {
							Thread.currentThread().interrupt();
						}
					});
					try {
						Assertions.assertTrue(executorTaskEntered.await(
								WAIT.toMillis(), TimeUnit.MILLISECONDS));
						executorSupplierEntered.countDown();
						Assertions.assertTrue(releaseExecutorSupplier.await(
								WAIT.toMillis(), TimeUnit.MILLISECONDS));
					} catch (InterruptedException interruption) {
						Thread.currentThread().interrupt();
						throw new IllegalStateException(
								"Interrupted while creating the test executor",
								interruption);
					}
					return executor;
				})
				.build();
		Soklet.DefaultSimulator simulator = new Soklet.DefaultSimulator(null, null,
				SimulatorOptions.defaultInstance(), server);
		ExecutorService startupWorker = Executors.newSingleThreadExecutor();

		try {
			Future<?> startup = startupWorker.submit(simulator::openMcpScope);
			Assertions.assertTrue(executorSupplierEntered.await(WAIT.toMillis(),
					TimeUnit.MILLISECONDS));
			Assertions.assertTrue(simulator.sealScope());
			releaseExecutorSupplier.countDown();

			java.util.concurrent.ExecutionException startupFailure =
					Assertions.assertThrows(java.util.concurrent.ExecutionException.class,
							() -> startup.get(WAIT.toMillis(), TimeUnit.MILLISECONDS));
			Assertions.assertEquals("The simulator scope is closed.",
					startupFailure.getCause().getMessage());
			Assertions.assertFalse(simulator.mcpScopeTerminationProven(),
					"Rejected-session rollback must remain visible while its executor lives");
			Assertions.assertEquals(Set.of(
					InternalResidualActivityKind.EXECUTOR_TASK),
					simulator.mcpScopeResidualActivity());

			releaseExecutorTask.countDown();
			Assertions.assertTrue(simulator.awaitMcpScopeTermination(
					System.nanoTime() + WAIT.toNanos(), NanoClock.system()));
			Assertions.assertTrue(simulator.mcpScopeTerminationProven());
			simulator.releaseMcpScopeEvidence();
			Assertions.assertTrue(simulator.mcpScopeResidualActivity().isEmpty());
		} finally {
			releaseExecutorSupplier.countDown();
			releaseExecutorTask.countDown();
			ExecutorService executor = handlerExecutor.get();
			if (executor != null)
				executor.shutdownNow();
			startupWorker.shutdownNow();
			startupWorker.awaitTermination(WAIT.toMillis(), TimeUnit.MILLISECONDS);
		}
	}

	@Test
	public void teardownLaunchFailureNeverReplacesPrimaryAndRetainsProofGraph() {
		CheckedCanary bodyFailure = new CheckedCanary();
		LifecycleLaunchCanary failedBodyTeardown = new LifecycleLaunchCanary();
		AtomicReference<SimulatorTransports> failedBodyScope = new AtomicReference<>();
		CheckedCanary thrown = Assertions.assertThrows(CheckedCanary.class,
				() -> SokletSimulator.run(transports -> {
					failedBodyScope.set(transports);
					return immediateTeardownConfig(transports);
				}, SimulatorOptions.defaultInstance(), simulator -> {
					throw bodyFailure;
				}, NanoClock.system(), new LifecycleWorkers((name, task) -> {
					if (isSimulatorStartupCall(name)) {
						task.run();
						return;
					}
					throw failedBodyTeardown;
				})));
		Assertions.assertSame(bodyFailure, thrown);
		Assertions.assertEquals(1, thrown.getSuppressed().length);
		ShutdownIncompleteException suppressed = Assertions.assertInstanceOf(
				ShutdownIncompleteException.class, thrown.getSuppressed()[0]);
		Assertions.assertNull(suppressed.getCause());
		Assertions.assertTrue(suppressed.retainsScopeEvidence(
				failedBodyScope.get()));
		assertUnknownTeardownFailure(suppressed, failedBodyTeardown);

		LifecycleLaunchCanary successfulBodyTeardown = new LifecycleLaunchCanary();
		AtomicReference<SimulatorTransports> successfulBodyScope =
				new AtomicReference<>();
		ShutdownIncompleteException direct = Assertions.assertThrows(
				ShutdownIncompleteException.class,
				() -> SokletSimulator.run(transports -> {
					successfulBodyScope.set(transports);
					return immediateTeardownConfig(transports);
				}, SimulatorOptions.defaultInstance(), simulator -> {
				}, NanoClock.system(), new LifecycleWorkers((name, task) -> {
					if (isSimulatorStartupCall(name)) {
						task.run();
						return;
					}
					throw successfulBodyTeardown;
				})));
		Assertions.assertNull(direct.getCause());
		Assertions.assertTrue(direct.retainsScopeEvidence(
				successfulBodyScope.get()));
		assertUnknownTeardownFailure(direct, successfulBodyTeardown);
	}

	@Test
	public void suppressesServerTransitionsRetainsRequestCallbacksAndUsesNoTransitionWorker()
			throws Exception {
		AtomicInteger serverTransitions = new AtomicInteger();
		AtomicInteger requestCallbacks = new AtomicInteger();
		LifecycleWorkers workers = new LifecycleWorkers();
		LifecycleObserver observer = new LifecycleObserver() {
			@Override
			public void willStartSoklet(@NonNull Soklet soklet) {
				serverTransitions.incrementAndGet();
			}

			@Override
			public void didStartSoklet(@NonNull Soklet soklet) {
				serverTransitions.incrementAndGet();
			}

			@Override
			public void willStartHttpServer(@NonNull HttpServer httpServer) {
				serverTransitions.incrementAndGet();
			}

			@Override
			public void didStartHttpServer(@NonNull HttpServer httpServer) {
				serverTransitions.incrementAndGet();
			}

			@Override
			public void willStopSoklet(@NonNull Soklet soklet) {
				serverTransitions.incrementAndGet();
			}

			@Override
			public void didStopSoklet(@NonNull Soklet soklet,
					@NonNull ShutdownResult result) {
				serverTransitions.incrementAndGet();
			}

			@Override
			public void didStartRequestHandling(@NonNull ServerType serverType,
					@NonNull Request request,
					@Nullable ResourceMethod resourceMethod) {
				requestCallbacks.incrementAndGet();
			}

			@Override
			public void didFinishRequestHandling(@NonNull ServerType serverType,
					@NonNull Request request,
					@Nullable ResourceMethod resourceMethod,
					@NonNull MarshaledResponse marshaledResponse,
					@NonNull Duration requestDuration,
					@NonNull List<@NonNull Throwable> throwables) {
				requestCallbacks.incrementAndGet();
			}
		};

		SokletSimulator.run(transports -> SokletConfig
				.withHttpServer(transports.getHttpServer())
				.resourceMethodResolver(resourceMethods())
				.lifecycleObserver(observer)
				.build(), SimulatorOptions.defaultInstance(), simulator ->
				simulator.performHttpRequest(Request.withPath(
						HttpMethod.GET, "/isolation").build()),
				NanoClock.system(), workers);

		Assertions.assertEquals(0, serverTransitions.get());
		Assertions.assertEquals(2, requestCallbacks.get());
		Assertions.assertEquals(0,
				workers.created(LifecycleWorkers.Role.TRANSITION_OBSERVER));
	}

	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	public void scopeMcpBuilderPreservesPortPolicyWithoutBinding() throws Exception {
		int configuredPort = 43_217;
		AtomicReference<McpServer> escapedServer = new AtomicReference<>();
		AtomicInteger handlerCalls = new AtomicInteger();
		McpToolRegistration<McpJsonObject> tool = McpToolRegistration
				.withName("complete").jsonArguments()
				.handler((request, arguments, features) -> {
					handlerCalls.incrementAndGet();
					return McpCompleteResult.fromToolText("scope complete");
				}).build();

		SokletSimulator.run(transports -> {
			McpServer server = mcpBuilder(transports, configuredPort, List.of(tool))
					.build();
			escapedServer.set(server);
			return mcpConfig(server);
		}, simulator -> {
			McpSimulation accepted = simulator.startMcpRequest(mcpRequest(
					"accepted", "complete", configuredPort,
					Optional.of("https://scope.example")));
			Assertions.assertEquals(200,
					accepted.awaitResponse(WAIT).orElseThrow().getStatusCode());
			Assertions.assertEquals(McpStreamTerminationReason.COMPLETED,
					accepted.awaitCompletion(WAIT).orElseThrow().getReason());

			McpSimulation wrongPort = simulator.startMcpRequest(mcpRequest(
					"wrong-port", "complete", configuredPort + 1,
					Optional.empty()));
			Assertions.assertEquals(421,
					wrongPort.awaitResponse(WAIT).orElseThrow().getStatusCode());
			wrongPort.awaitCompletion(WAIT).orElseThrow();
		});

		McpServer server = escapedServer.get();
		Assertions.assertNotNull(server);
		Assertions.assertEquals(1, handlerCalls.get());
		Assertions.assertEquals(McpServerStatus.TERMINATED,
				server.getDiagnostics().getStatus());
		Assertions.assertTrue(server.getDiagnostics().getBoundAddress().isEmpty());
		TransportOwnershipException conflict = Assertions.assertThrows(
				TransportOwnershipException.class,
				() -> Soklet.fromConfig(mcpConfig(server)));
		Assertions.assertEquals(ParticipantKind.MCP,
				conflict.getParticipantKind());
		Assertions.assertSame(server.getClass(), conflict.getTransportClass());
		Assertions.assertTrue(server.getDiagnostics().getBoundAddress().isEmpty());
	}

	@Test
	public void rejectsMultipleMcpBuildsAndEscapedBuilder() throws Exception {
		McpToolRegistration<McpJsonObject> tool = McpToolRegistration
				.withName("complete").jsonArguments()
				.handler((request, arguments, features) ->
						McpCompleteResult.fromToolText("complete"))
				.build();
		Assertions.assertThrows(IllegalStateException.class,
				() -> SokletSimulator.run(transports -> {
					mcpBuilder(transports, 0, List.of(tool)).build();
					McpServer second = mcpBuilder(transports, 0, List.of(tool)).build();
					return mcpConfig(second);
				}, simulator -> {
				}));

		AtomicReference<McpServer.Builder> escapedBuilder = new AtomicReference<>();
		SokletSimulator.run(transports -> {
			escapedBuilder.set(mcpBuilder(transports, 0, List.of(tool)));
			return httpConfig(transports);
		}, simulator -> {
		});
		Assertions.assertThrows(IllegalStateException.class,
				() -> escapedBuilder.get().build());
	}

	@Test
	public void mcpParticipantStartsBeforeReadinessAndUsesLifecycleClockBudget()
			throws Exception {
		AtomicInteger subscriptions = new AtomicInteger();
		AtomicInteger closes = new AtomicInteger();
		McpSubscriptionEventPublisher publisher = new McpSubscriptionEventPublisher() {
			@Override
			public McpSubscriptionEventRegistration subscribe(
					@NonNull McpSubscriptionEventListener listener) {
				subscriptions.incrementAndGet();
				return closes::incrementAndGet;
			}

			@Override
			public void publish(@NonNull McpSubscriptionEvent event) {
			}
		};
		AtomicLong systemOrigin = new AtomicLong(System.nanoTime());
		NanoClock offsetClock = () -> System.nanoTime() - systemOrigin.get();
		InternalLifecyclePolicy policy = new InternalLifecyclePolicy(
				Optional.of(Duration.ofSeconds(2)), Duration.ofSeconds(1),
				Duration.ofSeconds(2), Duration.ofSeconds(2));

		ShutdownResult result = ShutdownResult.fromInternal(SokletSimulator.run(
				transports -> {
			McpServer server = subscriptionServer(transports, List.of(
					subscriptionEndpoint("/ready", publisher)));
			return SokletConfig.withMcpServer(server)
					.resourceMethodResolver(
							ResourceMethodResolver.fromMethods(Set.of()))
					.internalLifecyclePolicy(policy)
					.build();
		}, SimulatorOptions.defaultInstance(), simulator -> {
			Assertions.assertEquals(1, subscriptions.get(),
					"The MCP generation must start before scope readiness");
		}, offsetClock, new LifecycleWorkers()));

		Assertions.assertTrue(result.isComplete());
		Assertions.assertEquals(StartupDisposition.READY,
				result.getStartupDisposition());
		Assertions.assertEquals(1, closes.get());
	}

	@Test
	public void mcpStartupFailureRetainsOwnedSessionForRegistrationRollback() {
		AtomicInteger bodies = new AtomicInteger();
		AtomicInteger firstCloses = new AtomicInteger();
		IllegalStateException startupFailure = new IllegalStateException(
				"simulated simulator MCP startup failure");
		McpSubscriptionEventPublisher first = new McpSubscriptionEventPublisher() {
			@Override
			public McpSubscriptionEventRegistration subscribe(
					@NonNull McpSubscriptionEventListener listener) {
				return firstCloses::incrementAndGet;
			}

			@Override
			public void publish(@NonNull McpSubscriptionEvent event) {
			}
		};
		McpSubscriptionEventPublisher failing = new McpSubscriptionEventPublisher() {
			@Override
			public McpSubscriptionEventRegistration subscribe(
					@NonNull McpSubscriptionEventListener listener) {
				throw startupFailure;
			}

			@Override
			public void publish(@NonNull McpSubscriptionEvent event) {
			}
		};

		SokletStartupException thrown = Assertions.assertThrows(
				SokletStartupException.class, () -> SokletSimulator.run(transports -> {
					McpServer server = subscriptionServer(transports, List.of(
							subscriptionEndpoint("/first", first),
							subscriptionEndpoint("/failing", failing)));
					return mcpConfig(server);
				}, simulator -> bodies.incrementAndGet()));

		Assertions.assertSame(startupFailure, thrown.getCause());
		Assertions.assertEquals(StartupDisposition.FAILED,
				thrown.getStartupDisposition());
		Assertions.assertEquals(0, bodies.get());
		Assertions.assertEquals(1, firstCloses.get(),
				"Rollback must prove closure of the registration created before failure");
	}

	@NonNull
	private static SokletConfig httpConfig(@NonNull SimulatorTransports transports) {
		return SokletConfig.withHttpServer(transports.getHttpServer())
				.resourceMethodResolver(resourceMethods())
				.lifecyclePolicy(TEST_LIFECYCLE_POLICY)
				.build();
	}

	@NonNull
	private static ResourceMethodResolver resourceMethods() {
		return ResourceMethodResolver.fromClasses(Set.of(IsolationResource.class));
	}

	@NonNull
	private static ResourceMethodResolver providerResourceMethods() {
		return ResourceMethodResolver.fromClasses(
				Set.of(ScopedProviderResource.class));
	}

	@NonNull
	private static SokletConfig immediatelyIncompleteConfig(
			@NonNull SimulatorTransports transports) {
		return SokletConfig.withHttpServer(transports.getHttpServer())
				.resourceMethodResolver(resourceMethods())
				.internalLifecyclePolicy(new InternalLifecyclePolicy(
						Optional.of(WAIT), Duration.ZERO,
						Duration.ZERO, Duration.ZERO))
				.build();
	}

	private static boolean isSimulatorStartupCall(@NonNull String name) {
		return name.equals("simulator-framework-setup")
				|| name.startsWith("simulator-start-");
	}

	@NonNull
	private static SokletConfig immediateTeardownConfig(
			@NonNull SimulatorTransports transports) {
		return SokletConfig.withHttpServer(transports.getHttpServer())
				.resourceMethodResolver(resourceMethods())
				.internalLifecyclePolicy(new InternalLifecyclePolicy(
						Optional.of(WAIT), Duration.ZERO,
						Duration.ZERO, Duration.ZERO))
				.build();
	}

	private static void assertIncompleteLifecycleCall(
			@NonNull ShutdownIncompleteException exception) {
		InternalShutdownResult result = exception.getInternalShutdownResult();
		Assertions.assertEquals(InternalShutdownDisposition.INCOMPLETE,
				result.disposition());
		InternalParticipantShutdownResult participant = result
				.participantResult(InternalParticipantKind.HTTP).orElseThrow();
		Assertions.assertEquals(
				InternalParticipantShutdownDisposition.RESIDUAL_ACTIVITY,
				participant.disposition());
		Assertions.assertEquals(Set.of(
				InternalResidualActivityKind.LIFECYCLE_CALL),
				participant.residualActivity());
	}

	private static void assertUnknownTeardownFailure(
			@NonNull ShutdownIncompleteException exception,
			@NonNull Throwable expectedFailure) {
		InternalShutdownResult result = exception.getInternalShutdownResult();
		Assertions.assertEquals(InternalShutdownDisposition.INCOMPLETE,
				result.disposition());
		Assertions.assertEquals(InternalStartupDisposition.READY,
				result.startupDisposition());
		InternalParticipantShutdownResult participant = result
				.participantResult(InternalParticipantKind.HTTP).orElseThrow();
		Assertions.assertEquals(
				InternalParticipantShutdownDisposition.TERMINATION_UNKNOWN,
				participant.disposition());
		Assertions.assertEquals(1, participant.failures().size());
		Assertions.assertSame(expectedFailure, participant.failures().get(0));
	}

	@NonNull
	private static String runConcurrentScope(@NonNull String scopeId,
			@NonNull CountDownLatch scopesReady,
			@NonNull CountDownLatch releaseScopes,
			@NonNull ScopeCallbackProbe probe) throws Exception {
		AtomicReference<String> response = new AtomicReference<>();
		SokletSimulator.run(transports -> {
			ScopedInstanceProvider instances =
					new ScopedInstanceProvider(scopeId);
			return SokletConfig.withHttpServer(transports.getHttpServer())
					.resourceMethodResolver(providerResourceMethods())
					.instanceProvider(instances)
					.lifecyclePolicy(TEST_LIFECYCLE_POLICY)
					.lifecycleObserver(new LifecycleObserver() {
						@Override
						public void willStartSoklet(@NonNull Soklet soklet) {
							probe.serverTransitions.incrementAndGet();
						}

						@Override
						public void didStartSoklet(@NonNull Soklet soklet) {
							probe.serverTransitions.incrementAndGet();
						}

						@Override
						public void willStopSoklet(@NonNull Soklet soklet) {
							probe.serverTransitions.incrementAndGet();
						}

						@Override
						public void didStopSoklet(@NonNull Soklet soklet,
								@NonNull ShutdownResult result) {
							probe.serverTransitions.incrementAndGet();
						}

						@Override
						public void didStartRequestHandling(
								@NonNull ServerType serverType,
								@NonNull Request request,
								@Nullable ResourceMethod resourceMethod) {
							probe.requestCallbacks.add(
									"start:" + request.getId());
						}

						@Override
						public void didFinishRequestHandling(
								@NonNull ServerType serverType,
								@NonNull Request request,
								@Nullable ResourceMethod resourceMethod,
								@NonNull MarshaledResponse marshaledResponse,
								@NonNull Duration requestDuration,
								@NonNull List<@NonNull Throwable> throwables) {
							probe.requestCallbacks.add(
									"finish:" + request.getId());
						}
					})
					.build();
		}, simulator -> {
			scopesReady.countDown();
			Assertions.assertTrue(releaseScopes.await(WAIT.toMillis(),
					TimeUnit.MILLISECONDS), "Concurrent scope was not released");
			Request request = Request.withPath(HttpMethod.GET,
					"/provider-scope").id(scopeId + "-request").build();
			response.set(responseBody(simulator.performHttpRequest(request)));
		});
		return response.get();
	}

	private static McpServer.Builder mcpBuilder(
			@NonNull SimulatorTransports transports, int port,
			@NonNull List<@NonNull McpToolRegistration<?>> tools) {
		McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH)
				.serverInformation(McpImplementation.withNameAndVersion(
						"isolated-simulator-test", "4.0.0-SNAPSHOT").build())
				.tools(tools)
				.build();
		return transports.newMcpServerBuilder(port)
				.host(LOOPBACK)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.admissionController(McpAdmissionController.acceptAllInstance())
				.requestRateLimiter(context -> McpRateLimitDecision.allowed())
				.toolRateLimiter(context -> McpRateLimitDecision.allowed())
				.corsAuthorizer(CorsAuthorizer.acceptAllInstance())
				.allowedHosts(Set.of(LOOPBACK));
	}

	@NonNull
	private static McpEndpoint subscriptionEndpoint(@NonNull String path,
			@NonNull McpSubscriptionEventPublisher publisher) {
		return McpEndpoint.withPath(path)
				.serverInformation(McpImplementation.withNameAndVersion(
						"isolated-simulator-subscription-test",
						"4.0.0-SNAPSHOT").build())
				.resourceListHandler((request, resourceList, features) ->
						McpResourcePage.builder().build())
				.subscriptions(McpSubscriptionConfig.withEventPublisher(publisher)
						.notificationType(McpSubscriptionNotificationType
								.RESOURCES_LIST_CHANGED)
						.build())
				.build();
	}

	@NonNull
	private static McpServer subscriptionServer(
			@NonNull SimulatorTransports transports,
			@NonNull List<@NonNull McpEndpoint> endpoints) {
		return transports.newMcpServerBuilder(0)
				.host(LOOPBACK)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(endpoints))
				.admissionController(McpAdmissionController.acceptAllInstance())
				.requestRateLimiter(context -> McpRateLimitDecision.allowed())
				.toolRateLimiter(context -> McpRateLimitDecision.allowed())
				.corsAuthorizer(CorsAuthorizer.acceptAllInstance())
				.allowedHosts(Set.of(LOOPBACK))
				.build();
	}

	@NonNull
	private static SokletConfig mcpConfig(@NonNull McpServer server) {
		return SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.build();
	}

	@NonNull
	private static Request mcpRequest(@NonNull String id,
			@NonNull String toolName, int port,
			@NonNull Optional<@NonNull String> origin) {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + id
				+ "\",\"method\":\"tools/call\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}},"
				+ "\"name\":\"" + toolName + "\",\"arguments\":{}}}";
		Map<String, Set<String>> headers = new LinkedHashMap<>();
		headers.put("Host", Set.of(LOOPBACK + ":" + port));
		headers.put("Content-Type", Set.of("application/json; charset=UTF-8"));
		headers.put("Accept", Set.of("application/json, text/event-stream"));
		headers.put("MCP-Protocol-Version", Set.of(PROTOCOL_VERSION));
		headers.put("Mcp-Method", Set.of("tools/call"));
		headers.put("Mcp-Name", Set.of(toolName));
		origin.ifPresent(value -> headers.put("Origin", Set.of(value)));
		return Request.withPath(HttpMethod.POST, MCP_PATH)
				.headers(headers)
				.body(body.getBytes(StandardCharsets.UTF_8))
				.build();
	}

	@NonNull
	private static String responseBody(@NonNull HttpRequestResult result) {
		return new String(result.getMarshaledResponse().bodyBytesOrEmpty(),
				StandardCharsets.UTF_8);
	}

	public static class IsolationResource {
		@GET("/isolation")
		public String isolation() {
			return "isolated";
		}
	}

	public static class ScopedProviderResource {
		@NonNull
		private final String instanceScope;

		private ScopedProviderResource(@NonNull String instanceScope) {
			this.instanceScope = instanceScope;
		}

		@GET("/provider-scope")
		public String providerScope(@NonNull ScopedDependency dependency) {
			return this.instanceScope + ":" + dependency.scopeId;
		}
	}

	private static final class ScopedDependency {
		@NonNull
		private final String scopeId;

		private ScopedDependency(@NonNull String scopeId) {
			this.scopeId = scopeId;
		}
	}

	private static final class ScopedInstanceProvider
			implements InstanceProvider {
		@NonNull
		private final String scopeId;
		@NonNull
		private final InstanceProvider fallback;

		private ScopedInstanceProvider(@NonNull String scopeId) {
			this.scopeId = scopeId;
			this.fallback = InstanceProvider.defaultInstance();
		}

		@NonNull
		@Override
		public <T> T provide(@NonNull Class<T> instanceClass) {
			if (instanceClass == ScopedProviderResource.class)
				return instanceClass.cast(new ScopedProviderResource(this.scopeId));
			if (instanceClass == ScopedDependency.class)
				return instanceClass.cast(new ScopedDependency(this.scopeId));
			return this.fallback.provide(instanceClass);
		}
	}

	private static final class ScopedParameterProvider
			implements ResourceMethodParameterProvider {
		@NonNull
		private final String scopeId;

		private ScopedParameterProvider(@NonNull String scopeId) {
			this.scopeId = scopeId;
		}

		@NonNull
		@Override
		public List<@Nullable Object> parameterValuesForResourceMethod(
				@NonNull Request request,
				@NonNull ResourceMethod resourceMethod) {
			return List.of(new ScopedDependency(this.scopeId));
		}
	}

	private static final class ScopeCallbackProbe {
		@NonNull
		private final AtomicInteger serverTransitions = new AtomicInteger();
		@NonNull
		private final List<String> requestCallbacks =
				new CopyOnWriteArrayList<>();
	}

	private static final class DeferredLifecycleLauncher {
		@NonNull
		private final Object lock = new Object();
		@NonNull
		private final List<Runnable> queuedTasks = new ArrayList<>();

		private void launch(@NonNull String name, @NonNull Runnable task) {
			if (isSimulatorStartupCall(name)) {
				task.run();
				return;
			}
			synchronized (this.lock) {
				this.queuedTasks.add(task);
			}
		}

		private int queuedTaskCount() {
			synchronized (this.lock) {
				return this.queuedTasks.size();
			}
		}

		private void releaseAll() throws InterruptedException {
			List<Runnable> tasks;
			synchronized (this.lock) {
				tasks = List.copyOf(this.queuedTasks);
				this.queuedTasks.clear();
			}
			List<Thread> workers = new ArrayList<>(tasks.size());
			for (int index = 0; index < tasks.size(); index++) {
				Thread worker = new Thread(tasks.get(index),
						"simulator-isolation-deferred-" + index);
				worker.setDaemon(true);
				workers.add(worker);
				worker.start();
			}
			for (Thread worker : workers)
				worker.join();
		}
	}

	private static final class CheckedCanary extends Exception {
	}

	private static final class LifecycleLaunchCanary extends RuntimeException {
	}

	private static final class TrackingHttpServer implements HttpServer {
		@NonNull
		private final TransportIdentity identity = TransportIdentity.create();
		@NonNull
		private final AtomicInteger initializeCalls = new AtomicInteger();

		@NonNull
		@Override
		public TransportIdentity getTransportIdentity() {
			return this.identity;
		}

		@NonNull
		@Override
		public TransportRuntime attach(
				@NonNull HttpTransportAttachmentContext context,
				@NonNull StartupContext startupContext) {
			this.initializeCalls.incrementAndGet();
			TransportTerminationSignal signal = context.getTerminationSignal();
			return new TransportRuntime() {
				@Override public void start(@NonNull StartupContext context) {
				}

				@Override public void quiesce(@NonNull ShutdownContext context) {
					signal.signalTerminated();
				}

				@Override public void force(@NonNull ShutdownContext context) {
					signal.signalTerminated();
				}
			};
		}
	}
}
