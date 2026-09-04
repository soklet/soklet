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

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Cross-type behavior contracts for nullable MCP builder resets.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public class McpBuilderResetContractTests {
	@Test
	public void endpointHandlersPoliciesAndLimitersSupportReplacementAndReset() {
		McpImplementation implementation = implementation();
		McpResourceListHandler firstHandler = (request, list, features) ->
				McpResourcePage.builder().build();
		McpResourceListHandler secondHandler = (request, list, features) ->
				McpResourcePage.builder().build();
		McpRateLimiter directLimiter = context ->
				McpRateLimitDecision.allowed();
		McpCachePolicy customCachePolicy =
				McpCachePolicy.fromPublicTimeToLive(Duration.ofMinutes(1));

		McpEndpoint replacedHandler = McpEndpoint.withPath("/mcp", implementation)
				.resourceListHandler(firstHandler)
				.resourceListHandler(secondHandler)
				.build();
		Assertions.assertSame(secondHandler,
				replacedHandler.getResourceListHandler().orElseThrow());

		McpEndpoint reset = McpEndpoint.withPath("/mcp", implementation)
				.serverInformationIncluded(false)
				.serverInformationIncluded(null)
				.instructions("Custom instructions")
				.instructions(null)
				.resourceListHandler(firstHandler)
				.resourceListHandler(null)
				.resourceListCachePolicy(customCachePolicy)
				.resourceListCachePolicy(null)
				.resourceTemplateListCachePolicy(customCachePolicy)
				.resourceTemplateListCachePolicy(null)
				.toolRateLimiterName("named")
				.toolRateLimiterName(null)
				.toolRateLimiter(directLimiter)
				.toolRateLimiter(null)
				.build();
		Assertions.assertTrue(reset.isServerInformationIncluded());
		Assertions.assertTrue(reset.getInstructions().isEmpty());
		Assertions.assertTrue(reset.getResourceListHandler().isEmpty());
		Assertions.assertSame(McpCachePolicy.privateNoCacheInstance(),
				reset.getResourceListCachePolicy());
		Assertions.assertSame(McpCachePolicy.privateNoCacheInstance(),
				reset.getResourceTemplateListCachePolicy());
		Assertions.assertTrue(reset.getToolRateLimiterName().isEmpty());
		Assertions.assertTrue(reset.getToolRateLimiter().isEmpty());

		McpEndpoint namedThenDirect = McpEndpoint
				.withPath("/named-then-direct", implementation)
				.toolRateLimiterName("named")
				.toolRateLimiter(directLimiter)
				.build();
		Assertions.assertTrue(namedThenDirect.getToolRateLimiterName().isEmpty());
		Assertions.assertSame(directLimiter,
				namedThenDirect.getToolRateLimiter().orElseThrow());

		McpEndpoint directThenNamed = McpEndpoint
				.withPath("/direct-then-named", implementation)
				.toolRateLimiter(directLimiter)
				.toolRateLimiterName("named")
				.build();
		Assertions.assertEquals("named",
				directThenNamed.getToolRateLimiterName().orElseThrow());
		Assertions.assertTrue(directThenNamed.getToolRateLimiter().isEmpty());

		McpEndpoint namedClearedByDirectReset = McpEndpoint
				.withPath("/named-cleared", implementation)
				.toolRateLimiterName("named")
				.toolRateLimiter(null)
				.build();
		Assertions.assertTrue(
				namedClearedByDirectReset.getToolRateLimiterName().isEmpty());
		Assertions.assertTrue(
				namedClearedByDirectReset.getToolRateLimiter().isEmpty());

		McpEndpoint directClearedByNamedReset = McpEndpoint
				.withPath("/direct-cleared", implementation)
				.toolRateLimiter(directLimiter)
				.toolRateLimiterName(null)
				.build();
		Assertions.assertTrue(
				directClearedByNamedReset.getToolRateLimiterName().isEmpty());
		Assertions.assertTrue(
				directClearedByNamedReset.getToolRateLimiter().isEmpty());
	}

	@Test
	public void serverOptionalAndDefaultedPropertiesResetAfterCustomization() {
		McpEndpointRegistry endpointRegistry = McpEndpointRegistry.fromEndpoints(
				List.of(McpEndpoint.withPath("/mcp", implementation()).build()));
		McpAdmissionController admissionController =
				McpAdmissionController.acceptAllInstance();
		McpRateLimiter rateLimiter = context -> McpRateLimitDecision.allowed();
		McpRateLimiterRegistry rateLimiterRegistry = McpRateLimiterRegistry
				.builder().addRateLimiter("custom", rateLimiter).build();
		McpHandlerInterceptor handlerInterceptor =
				(context, features, continuation) -> continuation.proceed();
		McpToolOutputSanitizer toolOutputSanitizer =
				(request, toolName, rawArguments, output) -> output;
		byte[] traceKeyMaterial = new byte[32];
		traceKeyMaterial[0] = 1;

		DefaultMcpServer defaults = (DefaultMcpServer) McpServer.withPort(0,
				endpointRegistry, admissionController).build();
		DefaultMcpServer reset = (DefaultMcpServer) McpServer.withPort(0,
				endpointRegistry, admissionController)
				.host("0.0.0.0")
				.host(null)
				.maximumCursorSizeInBytes(17)
				.maximumCursorSizeInBytes(null)
				.maximumSubscriptionsPerPartition(7)
				.maximumSubscriptionsPerPartition(null)
				.maximumSubscriptionDuration(Duration.ofHours(2))
				.maximumSubscriptionDuration(null)
				.requestTimeout(Duration.ofSeconds(2))
				.requestTimeout(null)
				.requestHandlerConcurrency(3)
				.requestHandlerConcurrency(null)
				.requestHandlerQueueCapacity(4)
				.requestHandlerQueueCapacity(null)
				.requestHandlerExecutorServiceSupplier(() -> {
					throw new AssertionError("custom supplier must have been reset");
				})
				.requestHandlerExecutorServiceSupplier(null)
				.streamQueueCapacity(5)
				.streamQueueCapacity(null)
				.writeTimeout(Duration.ofSeconds(9))
				.writeTimeout(null)
				.keepAliveInterval(Duration.ofSeconds(8))
				.keepAliveInterval(null)
				.handlerInterceptor(handlerInterceptor)
				.handlerInterceptor(null)
				.toolOutputSanitizer(toolOutputSanitizer)
				.toolOutputSanitizer(null)
				.requestRateLimiter(rateLimiter)
				.requestRateLimiter(null)
				.toolRateLimiter(rateLimiter)
				.toolRateLimiter(null)
				.rateLimiterRegistry(rateLimiterRegistry)
				.rateLimiterRegistry(null)
				.corsAuthorizer(CorsAuthorizer.acceptAllInstance())
				.corsAuthorizer(null)
				.absentOriginPolicy(McpAbsentOriginPolicy.REQUIRE_ORIGIN)
				.absentOriginPolicy(null)
				.unknownMirroredHeaderPolicy(
						McpUnknownMirroredHeaderPolicy.REJECT_REQUESTS)
				.unknownMirroredHeaderPolicy(null)
				.unknownMirroredHeaderNameDiagnostics(true)
				.unknownMirroredHeaderNameDiagnostics(null)
				.traceCorrelationKey(McpTraceCorrelationKey.fromIdAndBytes(
						"custom", traceKeyMaterial))
				.traceCorrelationKey(null)
				.logRawValidatedTraceIds(true)
				.logRawValidatedTraceIds(null)
				.protectionConfig(McpProtectionConfig
						.withDevelopmentEphemeralProtection().build())
				.protectionConfig(null)
				.allowedHosts(Set.of("example.test"))
				.allowedHosts(null)
				.build();

		Assertions.assertEquals(defaults.getMaximumCursorSizeInBytes(),
				reset.getMaximumCursorSizeInBytes());
		Assertions.assertEquals(defaults.getDiagnostics()
				.getRequestHandlerConcurrency(), reset.getDiagnostics()
				.getRequestHandlerConcurrency());
		Assertions.assertEquals(defaults.getDiagnostics()
				.getRequestHandlerQueueCapacity(), reset.getDiagnostics()
				.getRequestHandlerQueueCapacity());
		Assertions.assertEquals(defaults.streamQueueCapacity(),
				reset.streamQueueCapacity());
		Assertions.assertEquals(defaults.writeTimeout(), reset.writeTimeout());
		Assertions.assertEquals(defaults.keepAliveInterval(),
				reset.keepAliveInterval());
		Assertions.assertEquals(defaults.maximumSubscriptionsPerPartition(),
				reset.maximumSubscriptionsPerPartition());
		Assertions.assertEquals(defaults.maximumSubscriptionDuration(),
				reset.maximumSubscriptionDuration());
		Assertions.assertEquals(defaults.logRawValidatedTraceIds(),
				reset.logRawValidatedTraceIds());
		Assertions.assertSame(McpHandlerInterceptor.passThroughInstance(),
				reset.getHandlerInterceptor());
		Assertions.assertSame(McpToolOutputSanitizer.passThroughInstance(),
				reset.getToolOutputSanitizer());
		Assertions.assertTrue(reset.getRequestRateLimiter().isEmpty());
		Assertions.assertTrue(reset.getToolRateLimiter().isEmpty());
		Assertions.assertSame(McpRateLimiterRegistry.emptyInstance(),
				reset.getRateLimiterRegistry());
		Assertions.assertSame(CorsAuthorizer.rejectAllInstance(),
				reset.getCorsAuthorizer());
		Assertions.assertTrue(reset.protectionConfig().isEmpty());
		Assertions.assertEquals(McpProtectionMode.NONE,
				reset.getProtectionControl().getProtectionMode());
		Assertions.assertFalse(reset.getTraceCorrelationControl().isEnabled());
	}

	@Test
	public void everyMetricsMapResetsToEmptyAfterAConfiguredValue() {
		McpMetricsSnapshot.EndpointMethodKey endpointMethodKey =
				McpMetricsSnapshot.EndpointMethodKey.fromDimensions(
						"/mcp", "tools/call");
		McpMetricsSnapshot.RequestOutcomeKey requestOutcomeKey =
				McpMetricsSnapshot.RequestOutcomeKey.fromDimensions(
						"/mcp", "tools/call", McpRequestOutcome.COMPLETE);
		McpMetricsSnapshot.RequestStreamTerminationKey requestStreamKey =
				McpMetricsSnapshot.RequestStreamTerminationKey.fromDimensions(
						"/mcp", "tools/call",
						McpStreamTerminationReason.COMPLETED);
		McpMetricsSnapshot.SubscriptionTerminationKey subscriptionKey =
				McpMetricsSnapshot.SubscriptionTerminationKey.fromDimensions(
						"/mcp", McpStreamTerminationReason.COMPLETED);
		MetricsCollector.HistogramSnapshot histogram =
				new MetricsCollector.HistogramSnapshot(
						new long[]{Long.MAX_VALUE}, new long[]{1L},
						1L, 1L, 1L, 1L);

		McpMetricsSnapshot reset = McpMetricsSnapshot.builder()
				.serverStops(Map.of(
						ShutdownComponentDisposition.GRACEFUL_TERMINATION, 1L))
				.transportFailures(Map.of(
						MetricsCollector.TransportFailureReason.READ_ERROR, 1L))
				.requests(Map.of(requestOutcomeKey, 1L))
				.requestDurations(Map.of(requestOutcomeKey, histogram))
				.requestStreamDurations(Map.of(requestStreamKey, histogram))
				.subscriptionDurations(Map.of(subscriptionKey, histogram))
				.cancelationsSignaled(Map.of(endpointMethodKey, 1L))
				.progressEmitted(Map.of(endpointMethodKey, 1L))
				.protocolErrors(Map.of(-32_600, 1L))
				.unknownMirroredHeaders(Map.of(endpointMethodKey, 1L))
				.serverStops(null)
				.transportFailures(null)
				.requests(null)
				.requestDurations(null)
				.requestStreamDurations(null)
				.subscriptionDurations(null)
				.cancelationsSignaled(null)
				.progressEmitted(null)
				.protocolErrors(null)
				.unknownMirroredHeaders(null)
				.build();

		Assertions.assertTrue(reset.getServerStops().isEmpty());
		Assertions.assertTrue(reset.getTransportFailures().isEmpty());
		Assertions.assertTrue(reset.getRequests().isEmpty());
		Assertions.assertTrue(reset.getRequestDurations().isEmpty());
		Assertions.assertTrue(reset.getRequestStreamDurations().isEmpty());
		Assertions.assertTrue(reset.getSubscriptionDurations().isEmpty());
		Assertions.assertTrue(reset.getCancelationsSignaled().isEmpty());
		Assertions.assertTrue(reset.getProgressEmitted().isEmpty());
		Assertions.assertTrue(reset.getProtocolErrors().isEmpty());
		Assertions.assertTrue(reset.getUnknownMirroredHeaders().isEmpty());
	}

	private static McpImplementation implementation() {
		return McpImplementation.withNameAndVersion(
				"builder-reset-contract-tests", "4.0.0").build();
	}
}
