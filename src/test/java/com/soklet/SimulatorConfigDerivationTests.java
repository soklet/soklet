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
import com.soklet.converter.ValueConverterRegistry;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.annotation.concurrent.ThreadSafe;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** Behavioral contracts for simulator configurations derived from applications. */
@ThreadSafe
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class SimulatorConfigDerivationTests {
	private static final String LOOPBACK = "127.0.0.1";

	@Test
	void derivationCreatesFreshTransportsAndLeavesSourceReusable()
			throws Exception {
		HttpServer sourceHttpServer = HttpServer.fromPort(0);
		SseServer sourceSseServer = SseServer.fromPort(0);
		McpServer sourceMcpServer = configuredMcpServer(0,
				endpointRegistry("/source"));
		SokletConfig sourceConfig = SokletConfig
				.withHttpServer(sourceHttpServer)
				.sseServer(sourceSseServer)
				.mcpServer(sourceMcpServer)
				.resourceMethodResolver(testResourceMethods())
				.lifecycleObserver(quietObserver())
				.lifecyclePolicy(testLifecyclePolicy())
				.build();

		SimulatorConfig first = SimulatorConfig.fromSokletConfig(sourceConfig);
		SimulatorConfig second = SimulatorConfig.fromSokletConfig(sourceConfig);

		assertFreshTransportGraph(sourceConfig, first);
		assertFreshTransportGraph(sourceConfig, second);
		Assertions.assertNotSame(first.simulatedHttpServer(),
				second.simulatedHttpServer());
		Assertions.assertNotSame(first.simulatedSseServer(),
				second.simulatedSseServer());
		Assertions.assertNotSame(first.simulatedMcpServer(),
				second.simulatedMcpServer());

		SokletSimulator.run(first, simulator -> {
			Assertions.assertSame(first.simulatedHttpServer(),
					simulator.getHttpServer().orElseThrow());
			Assertions.assertSame(first.simulatedSseServer(),
					simulator.getSseServer().orElseThrow());
			Assertions.assertSame(first.simulatedMcpServer(),
					simulator.getMcpServer().orElseThrow());
		});
		SokletSimulator.run(second, simulator -> {
			Assertions.assertSame(second.simulatedHttpServer(),
					simulator.getHttpServer().orElseThrow());
			Assertions.assertSame(second.simulatedSseServer(),
					simulator.getSseServer().orElseThrow());
			Assertions.assertSame(second.simulatedMcpServer(),
					simulator.getMcpServer().orElseThrow());
		});

		Assertions.assertFalse(((DefaultHttpServer) sourceHttpServer).isStarted());
		Assertions.assertFalse(((DefaultSseServer) sourceSseServer).isStarted());
		Assertions.assertEquals(McpServerStatus.NOT_STARTED,
				sourceMcpServer.getDiagnostics().getStatus());

		// Simulator derivation did not claim the production transports: the exact
		// source configuration can still own and complete its normal lifecycle.
		try (Soklet soklet = Soklet.fromConfig(sourceConfig)) {
			soklet.start();
			Assertions.assertEquals(SokletStatus.RUNNING, soklet.getStatus());
		}
		Assertions.assertEquals(McpServerStatus.TERMINATED,
				sourceMcpServer.getDiagnostics().getStatus());
	}

	@Test
	void explicitApplicationSettingsAreImportedByIdentityAndOrder() {
		LifecyclePolicy lifecyclePolicy = LifecyclePolicy.builder()
				.startupTimeout(Duration.ofSeconds(4))
				.startupCancelationTimeout(Duration.ofSeconds(1))
				.gracefulShutdownTimeout(Duration.ofSeconds(3))
				.forcedShutdownTimeout(Duration.ofSeconds(2))
				.build();
		InstanceProvider instanceProvider = new InstanceProvider() {
			@Override
			@NonNull
			public <T> T provide(@NonNull Class<T> instanceClass) {
				throw new UnsupportedOperationException();
			}
		};
		ValueConverterRegistry valueConverterRegistry =
				ValueConverterRegistry.fromBlankSlate();
		RequestBodyMarshaler requestBodyMarshaler =
				(request, resourceMethod, parameter, requestBodyType) ->
						Optional.empty();
		ResourceMethodResolver resourceMethodResolver = emptyResourceMethods();
		ResourceMethodParameterProvider parameterProvider =
				(request, resourceMethod) -> List.of();
		ResponseMarshaler responseMarshaler = ResponseMarshaler.builder().build();
		RequestInterceptor requestInterceptor = new RequestInterceptor() {
			// Marker collaborator.
		};
		LifecycleObserver firstObserver = new LifecycleObserver() {
			// Marker observer.
		};
		LifecycleObserver secondObserver = new LifecycleObserver() {
			// Marker observer.
		};
		MetricsCollector metricsCollector = new MetricsCollector() {
			// Marker collector.
		};
		CorsAuthorizer corsAuthorizer = CorsAuthorizer.acceptAllInstance();
		SokletConfig sourceConfig = SokletConfig
				.withHttpServer(HttpServer.fromPort(0))
				.lifecyclePolicy(lifecyclePolicy)
				.instanceProvider(instanceProvider)
				.valueConverterRegistry(valueConverterRegistry)
				.requestBodyMarshaler(requestBodyMarshaler)
				.resourceMethodResolver(resourceMethodResolver)
				.resourceMethodParameterProvider(parameterProvider)
				.responseMarshaler(responseMarshaler)
				.requestInterceptor(requestInterceptor)
				.lifecycleObservers(List.of(firstObserver, secondObserver))
				.metricsCollector(metricsCollector)
				.corsAuthorizer(corsAuthorizer)
				.build();

		SokletConfig derivedConfig = SimulatorConfig
				.fromSokletConfig(sourceConfig).getSokletConfig();

		Assertions.assertSame(lifecyclePolicy,
				derivedConfig.getLifecyclePolicy());
		Assertions.assertSame(instanceProvider,
				derivedConfig.getInstanceProvider());
		Assertions.assertSame(valueConverterRegistry,
				derivedConfig.getValueConverterRegistry());
		Assertions.assertSame(requestBodyMarshaler,
				derivedConfig.getRequestBodyMarshaler());
		Assertions.assertSame(resourceMethodResolver,
				derivedConfig.getResourceMethodResolver());
		Assertions.assertSame(parameterProvider,
				derivedConfig.getResourceMethodParameterProvider());
		Assertions.assertSame(responseMarshaler,
				derivedConfig.getResponseMarshaler());
		Assertions.assertSame(requestInterceptor,
				derivedConfig.getRequestInterceptor());
		Assertions.assertEquals(List.of(firstObserver, secondObserver),
				derivedConfig.getLifecycleObservers());
		Assertions.assertSame(firstObserver,
				derivedConfig.getLifecycleObservers().get(0));
		Assertions.assertSame(secondObserver,
				derivedConfig.getLifecycleObservers().get(1));
		Assertions.assertSame(metricsCollector,
				derivedConfig.getMetricsCollector());
		Assertions.assertSame(corsAuthorizer,
				derivedConfig.getCorsAuthorizer());
	}

	@Test
	void unsetConfigurationDependentDefaultsAreDerivedAgain() {
		SokletConfig sourceConfig = SokletConfig
				.withHttpServer(HttpServer.fromPort(0)).build();

		SokletConfig derivedConfig = SimulatorConfig
				.fromSokletConfig(sourceConfig).getSokletConfig();

		Assertions.assertNotSame(sourceConfig.getValueConverterRegistry(),
				derivedConfig.getValueConverterRegistry());
		Assertions.assertNotSame(sourceConfig.getRequestBodyMarshaler(),
				derivedConfig.getRequestBodyMarshaler());
		DefaultRequestBodyMarshaler derivedBodyMarshaler =
				Assertions.assertInstanceOf(DefaultRequestBodyMarshaler.class,
						derivedConfig.getRequestBodyMarshaler());
		Assertions.assertSame(derivedConfig.getValueConverterRegistry(),
				derivedBodyMarshaler.getValueConverterRegistry());
		Assertions.assertNotSame(sourceConfig.getMetricsCollector(),
				derivedConfig.getMetricsCollector());
		Assertions.assertNotSame(
				sourceConfig.getResourceMethodParameterProvider(),
				derivedConfig.getResourceMethodParameterProvider());
		DefaultResourceMethodParameterProvider derivedParameterProvider =
				Assertions.assertInstanceOf(
						DefaultResourceMethodParameterProvider.class,
						derivedConfig.getResourceMethodParameterProvider());
		Assertions.assertSame(derivedConfig,
				derivedParameterProvider.getSokletConfig());
	}

	@Test
	void laterBuilderCallsOverrideImportedSettingsAndNullRestoresDefaults() {
		InstanceProvider sourceInstanceProvider = newInstanceProvider();
		ValueConverterRegistry sourceValueConverters =
				ValueConverterRegistry.fromBlankSlate();
		RequestBodyMarshaler sourceBodyMarshaler =
				(request, resourceMethod, parameter, requestBodyType) ->
						Optional.empty();
		MetricsCollector sourceMetrics = new MetricsCollector() {
			// Marker collector.
		};
		LifecyclePolicy sourceLifecyclePolicy = LifecyclePolicy.builder()
				.startupTimeout(Duration.ofMillis(17)).build();
		ResourceMethodResolver replacementResolver = emptyResourceMethods();
		SokletConfig sourceConfig = SokletConfig
				.withHttpServer(HttpServer.fromPort(0))
				.instanceProvider(sourceInstanceProvider)
				.valueConverterRegistry(sourceValueConverters)
				.requestBodyMarshaler(sourceBodyMarshaler)
				.metricsCollector(sourceMetrics)
				.lifecyclePolicy(sourceLifecyclePolicy)
				.lifecycleObserver(quietObserver())
				.build();

		SokletConfig derivedConfig = SimulatorConfig
				.withSokletConfig(sourceConfig)
				.instanceProvider(null)
				.valueConverterRegistry(null)
				.requestBodyMarshaler(null)
				.metricsCollector(null)
				.lifecyclePolicy(null)
				.lifecycleObserver(null)
				.resourceMethodResolver(replacementResolver)
				.build().getSokletConfig();

		Assertions.assertSame(InstanceProvider.defaultInstance(),
				derivedConfig.getInstanceProvider());
		Assertions.assertNotSame(sourceValueConverters,
				derivedConfig.getValueConverterRegistry());
		Assertions.assertNotSame(sourceBodyMarshaler,
				derivedConfig.getRequestBodyMarshaler());
		Assertions.assertSame(derivedConfig.getValueConverterRegistry(),
				((DefaultRequestBodyMarshaler) derivedConfig
						.getRequestBodyMarshaler()).getValueConverterRegistry());
		Assertions.assertNotSame(sourceMetrics,
				derivedConfig.getMetricsCollector());
		LifecyclePolicy defaultLifecyclePolicy = LifecyclePolicy.fromDefaults();
		Assertions.assertNotSame(sourceLifecyclePolicy,
				derivedConfig.getLifecyclePolicy());
		Assertions.assertEquals(defaultLifecyclePolicy.getStartupTimeout(),
				derivedConfig.getLifecyclePolicy().getStartupTimeout());
		Assertions.assertEquals(
				defaultLifecyclePolicy.getStartupCancelationTimeout(),
				derivedConfig.getLifecyclePolicy()
						.getStartupCancelationTimeout());
		Assertions.assertEquals(
				defaultLifecyclePolicy.getGracefulShutdownTimeout(),
				derivedConfig.getLifecyclePolicy()
						.getGracefulShutdownTimeout());
		Assertions.assertEquals(defaultLifecyclePolicy.getForcedShutdownTimeout(),
				derivedConfig.getLifecyclePolicy().getForcedShutdownTimeout());
		Assertions.assertTrue(derivedConfig.getLifecycleObservers().isEmpty());
		Assertions.assertSame(replacementResolver,
				derivedConfig.getResourceMethodResolver());
	}

	@Test
	void importedMcpConstructionIsClonedAndCanBeCustomized() {
		McpEndpointRegistry endpointRegistry = endpointRegistry("/imported");
		McpAdmissionController sourceAdmission = context ->
				McpAdmissionDecision.accepted();
		McpAdmissionController replacementAdmission = context ->
				McpAdmissionDecision.accepted();
		McpHandlerInterceptor handlerInterceptor =
				(context, features, continuation) -> continuation.proceed();
		McpToolOutputSanitizer sanitizer =
				(request, toolName, arguments, output) -> output;
		McpRateLimiter requestRateLimiter = context ->
				McpRateLimitDecision.allowed();
		McpRateLimiterRegistry limiterRegistry = McpRateLimiterRegistry.builder()
				.addRateLimiter("shared", requestRateLimiter).build();
		CorsAuthorizer corsAuthorizer = CorsAuthorizer.acceptAllInstance();
		DefaultMcpServer sourceMcpServer = (DefaultMcpServer) McpServer
				.withPort(41821)
				.endpointRegistry(endpointRegistry)
				.admissionController(sourceAdmission)
				.handlerInterceptor(handlerInterceptor)
				.toolOutputSanitizer(sanitizer)
				.requestRateLimiter(requestRateLimiter)
				.rateLimiterRegistry(limiterRegistry)
				.corsAuthorizer(corsAuthorizer)
				.allowedHosts(Set.of(LOOPBACK))
				.maximumCursorSizeInBytes(2048)
				.streamQueueCapacity(17)
				.writeTimeout(Duration.ofSeconds(20))
				.keepAliveInterval(Duration.ofSeconds(3))
				.maximumSubscriptionsPerPartition(7)
				.maximumSubscriptionDuration(Duration.ofHours(2))
				.logRawValidatedTraceIds(true)
				.build();
		SokletConfig sourceConfig = SokletConfig
				.withMcpServer(sourceMcpServer)
				.resourceMethodResolver(emptyResourceMethods())
				.lifecycleObserver(quietObserver())
				.lifecyclePolicy(testLifecyclePolicy())
				.build();

		SimulatorConfig simulatorConfig = SimulatorConfig
				.withSokletConfig(sourceConfig)
				.configureMcpServer(builder -> builder
						.maximumCursorSizeInBytes(8192)
						.admissionController(replacementAdmission))
				.build();
		DefaultMcpServer derivedMcpServer = simulatorConfig
				.simulatedMcpServer();

		Assertions.assertNotNull(derivedMcpServer);
		Assertions.assertNotSame(sourceMcpServer, derivedMcpServer);
		Assertions.assertSame(endpointRegistry,
				derivedMcpServer.getEndpointRegistry());
		Assertions.assertSame(replacementAdmission,
				derivedMcpServer.getAdmissionController());
		Assertions.assertSame(sourceAdmission,
				sourceMcpServer.getAdmissionController());
		Assertions.assertSame(handlerInterceptor,
				derivedMcpServer.getHandlerInterceptor());
		Assertions.assertSame(sanitizer,
				derivedMcpServer.getToolOutputSanitizer());
		Assertions.assertSame(requestRateLimiter,
				derivedMcpServer.getRequestRateLimiter().orElseThrow());
		Assertions.assertSame(limiterRegistry,
				derivedMcpServer.getRateLimiterRegistry());
		Assertions.assertSame(corsAuthorizer,
				derivedMcpServer.getCorsAuthorizer());
		Assertions.assertEquals(8192,
				derivedMcpServer.getMaximumCursorSizeInBytes());
		Assertions.assertEquals(2048,
				sourceMcpServer.getMaximumCursorSizeInBytes());
		Assertions.assertEquals(sourceMcpServer.streamQueueCapacity(),
				derivedMcpServer.streamQueueCapacity());
		Assertions.assertEquals(sourceMcpServer.writeTimeout(),
				derivedMcpServer.writeTimeout());
		Assertions.assertEquals(sourceMcpServer.keepAliveInterval(),
				derivedMcpServer.keepAliveInterval());
		Assertions.assertEquals(
				sourceMcpServer.maximumSubscriptionsPerPartition(),
				derivedMcpServer.maximumSubscriptionsPerPartition());
		Assertions.assertEquals(sourceMcpServer.maximumSubscriptionDuration(),
				derivedMcpServer.maximumSubscriptionDuration());
		Assertions.assertEquals(sourceMcpServer.logRawValidatedTraceIds(),
				derivedMcpServer.logRawValidatedTraceIds());
		Assertions.assertNotSame(sourceMcpServer.getProtectionControl(),
				derivedMcpServer.getProtectionControl());
		Assertions.assertNotSame(sourceMcpServer.getTraceCorrelationControl(),
				derivedMcpServer.getTraceCorrelationControl());
		Assertions.assertNotSame(sourceMcpServer.getLocalizationControl(),
				derivedMcpServer.getLocalizationControl());

		SokletSimulator.run(simulatorConfig, simulator -> Assertions.assertSame(
				derivedMcpServer, simulator.getMcpServer().orElseThrow()));
		Assertions.assertEquals(McpServerStatus.TERMINATED,
				derivedMcpServer.getDiagnostics().getStatus());
		Assertions.assertEquals(McpServerStatus.NOT_STARTED,
				sourceMcpServer.getDiagnostics().getStatus());
	}

	@Test
	void importedMcpConstructionSnapshotCoversEveryBuilderField()
			throws Exception {
		McpEndpointRegistry endpointRegistry = endpointRegistry("/complete-snapshot");
		McpAdmissionController admissionController = context ->
				McpAdmissionDecision.accepted();
		McpHandlerInterceptor handlerInterceptor =
				(context, features, continuation) -> continuation.proceed();
		McpToolOutputSanitizer sanitizer =
				(request, toolName, arguments, output) -> output;
		McpRateLimiter requestRateLimiter = context ->
				McpRateLimitDecision.allowed();
		McpRateLimiter toolRateLimiter = context ->
				McpRateLimitDecision.allowed();
		McpRateLimiterRegistry rateLimiterRegistry = McpRateLimiterRegistry
				.builder().addRateLimiter("snapshot", requestRateLimiter).build();
		CorsAuthorizer corsAuthorizer = CorsAuthorizer.acceptAllInstance();
		Supplier<@NonNull ExecutorService> executorSupplier =
				Executors::newSingleThreadExecutor;
		McpProtectionConfig protectionConfig = McpProtectionConfig
				.withKeyring(McpProtectionKeyring.withActiveKey(
						McpProtectionKey.fromIdAndBytes("protection-snapshot",
								keyMaterial((byte) 11))).build()).build();
		McpTraceCorrelationKey traceCorrelationKey =
				McpTraceCorrelationKey.fromIdAndBytes("trace-snapshot",
						keyMaterial((byte) 29));
		McpLocalizer localizer = McpLocalizer.withFallbackLocale(Locale.ENGLISH,
				request -> {
					throw new AssertionError("The snapshot test performs no request");
				}).build();
		DefaultMcpServer sourceMcpServer = (DefaultMcpServer) McpServer
				.withPort(41822)
				.host("0.0.0.0")
				.maximumCursorSizeInBytes(2001)
				.maximumSubscriptionsPerPartition(9)
				.requestHandlerConcurrency(4)
				.requestHandlerQueueCapacity(13)
				.streamQueueCapacity(23)
				.keepAliveInterval(Duration.ofSeconds(2))
				.maximumSubscriptionDuration(Duration.ofHours(3))
				.requestTimeout(Duration.ofSeconds(7))
				.writeTimeout(Duration.ofSeconds(9))
				.requestHandlerExecutorServiceSupplier(executorSupplier)
				.endpointRegistry(endpointRegistry)
				.admissionController(admissionController)
				.handlerInterceptor(handlerInterceptor)
				.toolOutputSanitizer(sanitizer)
				.corsAuthorizer(corsAuthorizer)
				.requestRateLimiter(requestRateLimiter)
				.toolRateLimiter(toolRateLimiter)
				.rateLimiterRegistry(rateLimiterRegistry)
				.absentOriginPolicy(McpAbsentOriginPolicy.REQUIRE_ORIGIN)
				.unknownMirroredHeaderPolicy(
						McpUnknownMirroredHeaderPolicy.REJECT_REQUESTS)
				.logRawValidatedTraceIds(true)
				.unknownMirroredHeaderNameDiagnostics(true)
				.protectionConfig(protectionConfig)
				.traceCorrelationKey(traceCorrelationKey)
				.localizer(localizer)
				.allowedHosts(Set.of("example.test"))
				.build();
		SokletConfig sourceConfig = SokletConfig
				.withMcpServer(sourceMcpServer).build();

		DefaultMcpServer derivedMcpServer = SimulatorConfig
				.fromSokletConfig(sourceConfig).simulatedMcpServer();

		Assertions.assertNotNull(derivedMcpServer);
		assertCompleteMcpBuilderFieldInventory();
		assertMcpConstructionTemplatesMatch(sourceMcpServer, derivedMcpServer);
		Assertions.assertNotSame(sourceMcpServer.getProtectionControl(),
				derivedMcpServer.getProtectionControl());
		Assertions.assertEquals(
				sourceMcpServer.getDiagnostics().getProtectionKeyringFingerprint(),
				derivedMcpServer.getDiagnostics().getProtectionKeyringFingerprint());
		Assertions.assertNotSame(sourceMcpServer.getTraceCorrelationControl(),
				derivedMcpServer.getTraceCorrelationControl());
		Assertions.assertEquals(
				sourceMcpServer.getDiagnostics().getTraceCorrelationFingerprint(),
				derivedMcpServer.getDiagnostics().getTraceCorrelationFingerprint());
		Assertions.assertNotSame(sourceMcpServer.getLocalizationControl(),
				derivedMcpServer.getLocalizationControl());
		Assertions.assertSame(localizer,
				derivedMcpServer.localizer().orElseThrow());
		Assertions.assertSame(toolRateLimiter,
				derivedMcpServer.getToolRateLimiter().orElseThrow());
		Assertions.assertEquals(4, derivedMcpServer.getDiagnostics()
				.getRequestHandlerConcurrency());
		Assertions.assertEquals(13, derivedMcpServer.getDiagnostics()
				.getRequestHandlerQueueCapacity());
	}

	@Test
	void explicitMcpConfigurationReplacesAnImportedServer() {
		McpEndpointRegistry sourceRegistry = endpointRegistry("/source");
		McpEndpointRegistry replacementRegistry = endpointRegistry("/replacement");
		McpServer sourceMcpServer = configuredMcpServer(4811, sourceRegistry);
		SokletConfig sourceConfig = SokletConfig
				.withMcpServer(sourceMcpServer).build();

		SimulatorConfig derived = SimulatorConfig.withSokletConfig(sourceConfig)
				.configureMcpServer(builder -> builder
						.port(0)
						.endpointRegistry(replacementRegistry)
						.admissionController(
								McpAdmissionController.acceptAllInstance()))
				.build();

		Assertions.assertSame(replacementRegistry,
				derived.simulatedMcpServer().getEndpointRegistry());
		Assertions.assertSame(sourceRegistry,
				sourceMcpServer.getEndpointRegistry());
	}

	@Test
	void directRunCreatesAConfigurationPerInvocationAndBuilderAcceptsOptions() {
		SokletConfig sourceConfig = SokletConfig
				.withHttpServer(HttpServer.fromPort(0))
				.resourceMethodResolver(testResourceMethods())
				.lifecycleObserver(quietObserver())
				.lifecyclePolicy(testLifecyclePolicy())
				.build();
		List<HttpServer> simulatedServers = new ArrayList<>();

		SokletSimulator.run(sourceConfig, simulator -> simulatedServers.add(
				simulator.getHttpServer().orElseThrow()));
		SokletSimulator.run(sourceConfig, simulator -> simulatedServers.add(
				simulator.getHttpServer().orElseThrow()));

		Assertions.assertEquals(2, simulatedServers.size());
		Assertions.assertNotSame(simulatedServers.get(0), simulatedServers.get(1));
		Assertions.assertNotSame(sourceConfig.getHttpServer().orElseThrow(),
				simulatedServers.get(0));
		Assertions.assertNotSame(sourceConfig.getHttpServer().orElseThrow(),
				simulatedServers.get(1));

		SimulatorOptions simulatorOptions = SimulatorOptions.builder()
				.streamingResponseBodyLimitInBytes(37).build();
		SimulatorConfig configuredOptions = SimulatorConfig
				.withSokletConfig(sourceConfig)
				.simulatorOptions(simulatorOptions)
				.build();
		SokletSimulator.run(configuredOptions, simulator -> Assertions.assertSame(
				simulatorOptions, ((Soklet.DefaultSimulator) simulator)
						.getSimulatorOptions()));
	}

	@Test
	void mcpBuilderDefaultsMatchProductionDefaultsAndGuardLeases() {
		ClassLoader originalClassLoader = Thread.currentThread()
				.getContextClassLoader();
		ClassLoader emptyClassLoader = new ClassLoader(null) {
			// No generated endpoint index is visible.
		};
		SimulatorConfig.Builder discoveryBuilder = SimulatorConfig.builder();
		try {
			Thread.currentThread().setContextClassLoader(emptyClassLoader);
			IllegalStateException directFailure = Assertions.assertThrows(
					IllegalStateException.class,
					() -> McpServer.withPort(0).build());
			IllegalStateException simulatorFailure = Assertions.assertThrows(
					IllegalStateException.class,
					() -> discoveryBuilder.configureMcpServer(
							builder -> builder.port(0)));
			Assertions.assertEquals(directFailure.getMessage(),
					simulatorFailure.getMessage());
		} finally {
			Thread.currentThread().setContextClassLoader(originalClassLoader);
		}

		McpEndpointRegistry endpointRegistry = endpointRegistry("/defaults");
		SimulatorConfig recovered = discoveryBuilder.configureMcpServer(builder ->
				builder.port(0)
						.endpointRegistry(endpointRegistry)
						.maximumCursorSizeInBytes(1234)).build();
		McpServer recoveredServer = recovered.simulatedMcpServer();
		Assertions.assertSame(endpointRegistry,
				recoveredServer.getEndpointRegistry());
		Assertions.assertSame(McpAdmissionController.acceptAllInstance(),
				recoveredServer.getAdmissionController());
		Assertions.assertEquals(1234,
				recoveredServer.getMaximumCursorSizeInBytes());

		SimulatorConfig.Builder manualBuildOwner = SimulatorConfig.builder();
		IllegalStateException manualBuildFailure = Assertions.assertThrows(
				IllegalStateException.class,
				() -> manualBuildOwner.configureMcpServer(builder -> builder
						.port(0)
						.endpointRegistry(endpointRegistry).build()));
		Assertions.assertEquals(
				"Only SimulatorConfig.Builder may build the simulator MCP server",
				manualBuildFailure.getMessage());
		Assertions.assertNotNull(manualBuildOwner.configureMcpServer(
				builder -> builder.port(0).endpointRegistry(endpointRegistry))
				.build());

		AtomicReference<McpServer.Builder> escapedBuilder = new AtomicReference<>();
		RuntimeException configurerFailure = new RuntimeException("expected");
		SimulatorConfig.Builder escapedBuilderOwner = SimulatorConfig.builder();
		RuntimeException actualFailure = Assertions.assertThrows(
				RuntimeException.class,
				() -> escapedBuilderOwner.configureMcpServer(builder -> {
					escapedBuilder.set(builder.port(0)
							.endpointRegistry(endpointRegistry));
					throw configurerFailure;
				}));
		Assertions.assertSame(configurerFailure, actualFailure);
		IllegalStateException staleBuilderFailure = Assertions.assertThrows(
				IllegalStateException.class,
				() -> escapedBuilder.get().build());
		Assertions.assertEquals("The simulator MCP builder is no longer active",
				staleBuilderFailure.getMessage());
	}

	@Test
	void mcpConfigurationAddsMcpWithDefaultPortWhenSourceHasNone()
			throws Exception {
		SokletConfig sourceConfig = SokletConfig
				.withHttpServer(HttpServer.fromPort(0)).build();
		McpEndpointRegistry endpointRegistry = endpointRegistry("/added");

		SimulatorConfig simulatorConfig = SimulatorConfig
				.withSokletConfig(sourceConfig)
				.configureMcpServer(builder -> builder
						.endpointRegistry(endpointRegistry))
				.build();

		Assertions.assertSame(endpointRegistry,
				simulatorConfig.simulatedMcpServer().getEndpointRegistry());
		Assertions.assertTrue(sourceConfig.getMcpServer().isEmpty());

		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"default-port\","
				+ "\"method\":\"server/discover\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}}}}";
		Request request = Request.withPath(HttpMethod.POST, "/added")
				.headers(Map.of(
						"Host", Set.of(LOOPBACK + ":0"),
						"Content-Type", Set.of("application/json; charset=UTF-8"),
						"Accept", Set.of("application/json, text/event-stream"),
						"MCP-Protocol-Version", Set.of("2026-07-28"),
						"Mcp-Method", Set.of("server/discover")))
				.body(body.getBytes(StandardCharsets.UTF_8))
				.build();
		SokletSimulator.run(simulatorConfig, simulator -> {
			try (McpSimulation simulation = simulator.startMcpRequest(request)) {
				Assertions.assertEquals(200, simulation.awaitResponse(
						Duration.ofSeconds(5)).orElseThrow().getStatusCode());
				Assertions.assertTrue(simulation.awaitCompletion(
						Duration.ofSeconds(5)).isPresent());
			}
		});
	}

	private static void assertFreshTransportGraph(
			@NonNull SokletConfig sourceConfig,
			@NonNull SimulatorConfig simulatorConfig) {
		SokletConfig derivedConfig = simulatorConfig.getSokletConfig();
		Assertions.assertNotSame(sourceConfig.getHttpServer().orElseThrow(),
				derivedConfig.getHttpServer().orElseThrow());
		Assertions.assertNotSame(sourceConfig.getSseServer().orElseThrow(),
				derivedConfig.getSseServer().orElseThrow());
		Assertions.assertNotSame(sourceConfig.getMcpServer().orElseThrow(),
				derivedConfig.getMcpServer().orElseThrow());
		Assertions.assertSame(simulatorConfig.simulatedHttpServer(),
				derivedConfig.getHttpServer().orElseThrow());
		Assertions.assertSame(simulatorConfig.simulatedSseServer(),
				derivedConfig.getSseServer().orElseThrow());
		Assertions.assertSame(simulatorConfig.simulatedMcpServer(),
				derivedConfig.getMcpServer().orElseThrow());
	}

	private static void assertCompleteMcpBuilderFieldInventory() {
		Set<String> actualFields = Arrays.stream(
				McpServer.Builder.class.getDeclaredFields())
				.filter(field -> !Modifier.isStatic(field.getModifiers()))
				.map(Field::getName)
				.collect(Collectors.toUnmodifiableSet());
		Assertions.assertEquals(Set.of(
				"port", "maximumCursorSizeInBytes",
				"maximumSubscriptionsPerPartition", "requestHandlerConcurrency",
				"requestHandlerQueueCapacity", "streamQueueCapacity", "host",
				"keepAliveInterval", "maximumSubscriptionDuration",
				"requestTimeout", "writeTimeout",
				"requestHandlerExecutorServiceSupplier", "endpointRegistry",
				"admissionController", "handlerInterceptor",
				"toolOutputSanitizer", "corsAuthorizer", "requestRateLimiter",
				"toolRateLimiter", "rateLimiterRegistry", "absentOriginPolicy",
				"unknownMirroredHeaderPolicy", "logRawValidatedTraceIds",
				"unknownMirroredHeaderNameDiagnostics", "protectionConfig",
				"traceCorrelationKey", "localizer", "allowedHosts",
				"simulatorBuildRegistrar"), actualFields,
				"MCP Builder construction fields changed; review simulator cloning");
	}

	private static void assertMcpConstructionTemplatesMatch(
			@NonNull DefaultMcpServer sourceMcpServer,
			@NonNull DefaultMcpServer derivedMcpServer) throws Exception {
		Field templateField = DefaultMcpServer.class
				.getDeclaredField("simulatorBuilderTemplate");
		templateField.setAccessible(true);
		Object sourceTemplate = templateField.get(sourceMcpServer);
		Object derivedTemplate = templateField.get(derivedMcpServer);
		for (Field field : McpServer.Builder.class.getDeclaredFields()) {
			if (Modifier.isStatic(field.getModifiers()))
				continue;
			field.setAccessible(true);
			Assertions.assertEquals(field.get(sourceTemplate),
					field.get(derivedTemplate), field.getName());
		}
	}

	private static byte @NonNull [] keyMaterial(byte value) {
		byte[] keyMaterial = new byte[32];
		Arrays.fill(keyMaterial, value);
		return keyMaterial;
	}

	@NonNull
	private static InstanceProvider newInstanceProvider() {
		return new InstanceProvider() {
			@Override
			@NonNull
			public <T> T provide(@NonNull Class<T> instanceClass) {
				throw new UnsupportedOperationException();
			}
		};
	}

	@NonNull
	private static McpServer configuredMcpServer(int port,
			@NonNull McpEndpointRegistry endpointRegistry) {
		return McpServer.withPort(port)
				.endpointRegistry(endpointRegistry)
				.corsAuthorizer(CorsAuthorizer.acceptAllInstance())
				.allowedHosts(Set.of(LOOPBACK))
				.build();
	}

	@NonNull
	private static McpEndpointRegistry endpointRegistry(@NonNull String path) {
		McpEndpoint endpoint = McpEndpoint.withPath(path,
				McpImplementation.withNameAndVersion(
						"simulator-config-derivation-test", "4.0.0").build())
				.build();
		return McpEndpointRegistry.fromEndpoints(List.of(endpoint));
	}

	@NonNull
	private static ResourceMethodResolver emptyResourceMethods() {
		return ResourceMethodResolver.fromMethods(Set.of());
	}

	@NonNull
	private static ResourceMethodResolver testResourceMethods() {
		return ResourceMethodResolver.fromClasses(Set.of(DerivationResource.class));
	}

	@NonNull
	private static LifecycleObserver quietObserver() {
		return new LifecycleObserver() {
			@Override
			public void didReceiveLogEvent(@NonNull LogEvent logEvent) {
				// Keep expected transport diagnostics out of test output.
			}
		};
	}

	@NonNull
	private static LifecyclePolicy testLifecyclePolicy() {
		return LifecyclePolicy.builder()
				.startupTimeout(Duration.ofSeconds(5))
				.startupCancelationTimeout(Duration.ofSeconds(2))
				.gracefulShutdownTimeout(Duration.ofSeconds(2))
				.forcedShutdownTimeout(Duration.ofSeconds(1))
				.build();
	}

	public static final class DerivationResource {
		@GET("/derivation")
		@NonNull
		public String derive() {
			return "derived";
		}
	}
}
