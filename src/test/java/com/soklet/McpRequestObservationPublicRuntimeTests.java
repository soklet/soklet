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

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Black-box real-listener coverage for public MCP request observation.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Timeout(60)
public class McpRequestObservationPublicRuntimeTests {
	private static final String LOOPBACK = "127.0.0.1";
	private static final String MCP_PATH = "/mcp";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final String JSON_MEDIA_TYPE = "application/json";
	private static final String TOOL_NAME = "observation.echo";
	private static final String MCP_TRACEPARENT =
			"00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
	private static final String HTTP_TRACEPARENT =
			"00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-00";
	private static final String FIRST_TRACE_TOKEN =
			"btKe431RVT8H4xxLhBXgUg";
	private static final String SECOND_TRACE_TOKEN =
			"w8jQllzQpqZSIXnjPwzWIA";
	private static final int TRACE_CARDINALITY_REQUEST_COUNT = 16;
	private static final String TRACE_CARDINALITY_KEY_ID =
			"metric-key-id-canary";
	private static final String TRACE_CARDINALITY_KEY_MATERIAL =
			"metric-key-material-canary-00000";

	@Test
	public void admittedDiscoveryPublishesLifecycleAndMetricsWithoutInterception()
			throws Exception {
		RecordingLifecycleObserver observer = new RecordingLifecycleObserver();
		RecordingMetricsCollector collector = new RecordingMetricsCollector();
		AtomicInteger interceptorInvocations = new AtomicInteger();
		McpEndpoint endpoint = endpointBuilder("discovery-observation-test").build();
		McpServer server = serverBuilder(endpoint)
				.handlerInterceptor((context, features, continuation) -> {
					interceptorInvocations.incrementAndGet();
					return continuation.proceed();
				})
				.build();
		Soklet soklet = managedSoklet(server, List.of(observer), collector);

		try {
			soklet.start();
			int port = server.getDiagnostics().getBoundAddress()
					.orElseThrow().getPort();
			HttpResponse<String> response = send(port, discoverRequest("discover"),
					"server/discover", Optional.empty());
			observer.awaitFinished();

			assertSuccess(response, "discover");
			Assertions.assertEquals(0, interceptorInvocations.get());
			assertSingleCompleteLifecycle(observer, endpoint, "server/discover",
					Optional.empty(), "discover");
			assertSingleCompleteMetrics(collector, "server/discover");
		} finally {
			soklet.close();
		}
	}

	@Test
	public void successfulToolSharesOneContextAndFinishesExactlyOnce()
			throws Exception {
		RecordingLifecycleObserver observer = new RecordingLifecycleObserver();
		RecordingMetricsCollector collector = new RecordingMetricsCollector();
		AtomicReference<McpRequestContext> interceptorContext =
				new AtomicReference<>();
		AtomicReference<McpRequestContext> handlerContext = new AtomicReference<>();
		AtomicInteger handlerInvocations = new AtomicInteger();
		McpToolRegistration<McpJsonObject> tool = McpToolRegistration
				.withName(TOOL_NAME)
				.jsonArguments()
				.handler((context, arguments, features) -> {
					handlerContext.set(context);
					handlerInvocations.incrementAndGet();
					return McpCompleteResult.fromToolText("observed");
				})
				.build();
		McpEndpoint endpoint = endpointBuilder("tool-observation-test")
				.tool(tool)
				.build();
		McpServer server = serverBuilder(endpoint)
				.handlerInterceptor((context, features, continuation) -> {
					interceptorContext.set(context);
					return continuation.proceed();
				})
				.build();
		Soklet soklet = managedSoklet(server, List.of(observer), collector);

		try {
			soklet.start();
			int port = server.getDiagnostics().getBoundAddress()
					.orElseThrow().getPort();
			HttpResponse<String> response = send(port,
					toolRequest("tool", TOOL_NAME), "tools/call",
					Optional.of(TOOL_NAME));
			observer.awaitFinished();

			assertSuccess(response, "tool");
			Assertions.assertTrue(response.body().contains("\"text\":\"observed\""),
					response.body());
			Assertions.assertEquals(1, handlerInvocations.get());
			assertSingleCompleteLifecycle(observer, endpoint, "tools/call",
					Optional.of(TOOL_NAME), "tool");
			Assertions.assertSame(observer.startedContext.get(),
					interceptorContext.get());
			Assertions.assertSame(observer.startedContext.get(), handlerContext.get());
			assertSingleCompleteMetrics(collector, "tools/call");
		} finally {
			soklet.close();
		}
	}

	@Test
	public void eachAdmittedRequestCapturesOneTokenAndRetainsItAcrossRotation()
			throws Exception {
		CountDownLatch firstHandlerEntered = new CountDownLatch(1);
		CountDownLatch releaseFirstHandler = new CountDownLatch(1);
		AtomicInteger handlerInvocations = new AtomicInteger();
		List<DefaultMcpRequestContext> handlerContexts =
				new CopyOnWriteArrayList<>();
		TraceRecordingLifecycleObserver observer =
				new TraceRecordingLifecycleObserver(2);
		McpToolRegistration<McpJsonObject> tool = McpToolRegistration
				.withName(TOOL_NAME)
				.jsonArguments()
				.handler((context, arguments, features) -> {
					DefaultMcpRequestContext internalContext =
							Assertions.assertInstanceOf(
									DefaultMcpRequestContext.class, context);
					handlerContexts.add(internalContext);
					if (handlerInvocations.incrementAndGet() == 1) {
						firstHandlerEntered.countDown();
						if (!releaseFirstHandler.await(5, TimeUnit.SECONDS))
							throw new IllegalStateException(
									"Timed out awaiting the trace-rotation release.");
					}
					return McpCompleteResult.fromToolText("trace-captured");
				})
				.build();
		McpEndpoint endpoint = endpointBuilder("trace-retention-test")
				.tool(tool)
				.build();
		McpServer server = serverBuilder(endpoint)
				.traceCorrelationKey(traceKey("trace-first", 0))
				.build();
		Soklet soklet = managedSoklet(server, List.of(observer),
				new RecordingMetricsCollector());

		try {
			soklet.start();
			int port = server.getDiagnostics().getBoundAddress()
					.orElseThrow().getPort();
			CompletableFuture<HttpResponse<String>> firstResponse = sendAsync(
					port, toolRequestWithTrace("trace-first", TOOL_NAME,
							MCP_TRACEPARENT), "tools/call", Optional.of(TOOL_NAME),
					Optional.empty());
			Assertions.assertTrue(firstHandlerEntered.await(5, TimeUnit.SECONDS),
					"The first trace-capture handler did not start.");

			Assertions.assertEquals(1, observer.startedContexts.size());
			DefaultMcpRequestContext firstContext =
					observer.startedContexts.get(0);
			Assertions.assertSame(firstContext, handlerContexts.get(0));
			DefaultMcpSecurityControls.TraceCorrelationToken firstToken =
					firstContext.traceCorrelationToken().orElseThrow();
			Assertions.assertEquals("trace-first", firstToken.keyId());
			Assertions.assertEquals(FIRST_TRACE_TOKEN, firstToken.token());
			Assertions.assertSame(firstToken, handlerContexts.get(0)
					.traceCorrelationToken().orElseThrow());

			server.getTraceCorrelationControl().rotateActiveKey(
					traceKey("trace-second", 32));
			Assertions.assertEquals(Optional.of("trace-second"),
					server.getTraceCorrelationControl().getActiveKeyId());
			Assertions.assertSame(firstToken,
					firstContext.traceCorrelationToken().orElseThrow());
			releaseFirstHandler.countDown();
			HttpResponse<String> first = firstResponse.get(5, TimeUnit.SECONDS);
			assertSuccess(first, "trace-first");
			observer.awaitFirstFinished();
			Assertions.assertSame(firstContext,
					observer.finishedContexts.get(0));
			Assertions.assertSame(firstToken, observer.finishedContexts.get(0)
					.traceCorrelationToken().orElseThrow());
			Assertions.assertEquals(1, observer.logEvents.size(),
					observer.logEvents.toString());
			assertTraceLogEvent(observer.logEvents.get(0),
					"tokenFormat=soklet-mcp-trace-correlation-v1;"
							+ "keyId=trace-first;token=" + FIRST_TRACE_TOKEN);

			HttpResponse<String> second = send(port,
					toolRequestWithTrace("trace-second", TOOL_NAME,
							MCP_TRACEPARENT), "tools/call", Optional.of(TOOL_NAME));
			assertSuccess(second, "trace-second");
			observer.awaitAllFinished();
			Assertions.assertEquals(2, observer.startedContexts.size());
			Assertions.assertEquals(2, observer.finishedContexts.size());
			Assertions.assertEquals(2, handlerContexts.size());
			DefaultMcpRequestContext secondContext =
					observer.startedContexts.get(1);
			Assertions.assertSame(secondContext, handlerContexts.get(1));
			Assertions.assertSame(secondContext,
					observer.finishedContexts.get(1));
			DefaultMcpSecurityControls.TraceCorrelationToken secondToken =
					secondContext.traceCorrelationToken().orElseThrow();
			Assertions.assertEquals("trace-second", secondToken.keyId());
			Assertions.assertEquals(SECOND_TRACE_TOKEN, secondToken.token());
			Assertions.assertSame(secondToken, handlerContexts.get(1)
					.traceCorrelationToken().orElseThrow());
			Assertions.assertSame(secondToken, observer.finishedContexts.get(1)
					.traceCorrelationToken().orElseThrow());
			Assertions.assertNotSame(firstToken, secondToken);
			Assertions.assertEquals(2, observer.logEvents.size(),
					observer.logEvents.toString());
			assertTraceLogEvent(observer.logEvents.get(1),
					"tokenFormat=soklet-mcp-trace-correlation-v1;"
							+ "keyId=trace-second;token=" + SECOND_TRACE_TOKEN);
		} finally {
			releaseFirstHandler.countDown();
			soklet.close();
		}
	}

	@Test
	public void traceCaptureUsesOnlyValidMcpMetadataWithoutHttpFallback()
			throws Exception {
		TraceRecordingLifecycleObserver observer =
				new TraceRecordingLifecycleObserver(4);
		AtomicInteger handlerInvocations = new AtomicInteger();
		McpToolRegistration<McpJsonObject> tool = McpToolRegistration
				.withName(TOOL_NAME)
				.jsonArguments()
				.handler((context, arguments, features) -> {
					handlerInvocations.incrementAndGet();
					return McpCompleteResult.fromToolText("trace-source-checked");
				})
				.build();
		McpEndpoint endpoint = endpointBuilder("trace-source-test")
				.tool(tool)
				.build();
		McpServer server = serverBuilder(endpoint)
				.traceCorrelationKey(traceKey("trace-first", 0))
				.logRawValidatedTraceIds(true)
				.build();
		Soklet soklet = managedSoklet(server, List.of(observer),
				new RecordingMetricsCollector());

		try {
			soklet.start();
			int port = server.getDiagnostics().getBoundAddress()
					.orElseThrow().getPort();
			assertSuccess(sendWithHttpTraceparent(port,
					toolRequestWithTrace("trace-valid", TOOL_NAME,
							MCP_TRACEPARENT), HTTP_TRACEPARENT), "trace-valid");
			assertSuccess(sendWithHttpTraceparent(port,
					toolRequestWithTrace("trace-invalid", TOOL_NAME,
							MCP_TRACEPARENT.toUpperCase()), HTTP_TRACEPARENT),
					"trace-invalid");
			assertSuccess(sendWithHttpTraceparent(port,
					toolRequestWithTrace("trace-zero", TOOL_NAME,
							"00-00000000000000000000000000000000-"
									+ "b7ad6b7169203331-01"), HTTP_TRACEPARENT),
					"trace-zero");
			assertSuccess(sendWithHttpTraceparent(port,
					toolRequest("trace-absent", TOOL_NAME), HTTP_TRACEPARENT),
					"trace-absent");
			observer.awaitAllFinished();

			Assertions.assertEquals(4, handlerInvocations.get());
			Assertions.assertEquals(4, observer.startedContexts.size());
			for (DefaultMcpRequestContext context : observer.startedContexts)
				Assertions.assertEquals(
						"4bf92f3577b34da6a3ce929d0e0e4736",
						context.getRequest().getTraceContext().orElseThrow()
								.getTraceId());
			DefaultMcpRequestContext valid = observer.startedContexts.get(0);
			Assertions.assertEquals("0af7651916cd43dd8448eb211c80319c",
					valid.getTraceContext().orElseThrow().getTraceId());
			DefaultMcpSecurityControls.TraceCorrelationToken validToken =
					valid.traceCorrelationToken().orElseThrow();
			Assertions.assertEquals("trace-first", validToken.keyId());
			Assertions.assertEquals(FIRST_TRACE_TOKEN, validToken.token());
			for (int index = 1; index < observer.startedContexts.size(); ++index) {
				DefaultMcpRequestContext context =
						observer.startedContexts.get(index);
				Assertions.assertTrue(context.getTraceContext().isEmpty());
				Assertions.assertTrue(context.traceCorrelationToken().isEmpty());
			}
			Assertions.assertEquals(1, observer.logEvents.size(),
					observer.logEvents.toString());
			assertTraceLogEvent(observer.logEvents.get(0),
					"tokenFormat=soklet-mcp-trace-correlation-v1;"
							+ "keyId=trace-first;token=" + FIRST_TRACE_TOKEN
							+ ";traceId=0af7651916cd43dd8448eb211c80319c");
		} finally {
			soklet.close();
		}
	}

	@Test
	public void defaultOffAndIndependentRawIdOptInHaveExactLogContracts()
			throws Exception {
		McpToolRegistration<McpJsonObject> tool = McpToolRegistration
				.withName(TOOL_NAME)
				.jsonArguments()
				.handler((context, arguments, features) ->
						McpCompleteResult.fromToolText("raw-id-independent"))
				.build();
		McpEndpoint endpoint = endpointBuilder("raw-id-independence-test")
				.tool(tool)
				.build();

		TraceRecordingLifecycleObserver defaultObserver =
				new TraceRecordingLifecycleObserver(1);
		McpServer defaultServer = serverBuilder(endpoint).build();
		Soklet defaultSoklet = managedSoklet(defaultServer,
				List.of(defaultObserver), new RecordingMetricsCollector());
		try {
			defaultSoklet.start();
			int port = defaultServer.getDiagnostics().getBoundAddress()
					.orElseThrow().getPort();
			assertSuccess(send(port, toolRequestWithTrace("trace-default",
					TOOL_NAME, MCP_TRACEPARENT), "tools/call",
					Optional.of(TOOL_NAME)), "trace-default");
			defaultObserver.awaitAllFinished();
			DefaultMcpRequestContext context =
					defaultObserver.startedContexts.get(0);
			Assertions.assertTrue(context.getTraceContext().isPresent());
			Assertions.assertTrue(context.traceCorrelationToken().isEmpty());
			Assertions.assertTrue(defaultObserver.logEvents.isEmpty(),
					defaultObserver.logEvents.toString());
		} finally {
			defaultSoklet.close();
		}

		TraceRecordingLifecycleObserver rawObserver =
				new TraceRecordingLifecycleObserver(1);
		McpServer rawServer = serverBuilder(endpoint)
				.logRawValidatedTraceIds(true)
				.build();
		Soklet rawSoklet = managedSoklet(rawServer,
				List.of(rawObserver), new RecordingMetricsCollector());
		try {
			rawSoklet.start();
			int port = rawServer.getDiagnostics().getBoundAddress()
					.orElseThrow().getPort();
			assertSuccess(send(port, toolRequestWithTrace("trace-raw",
					TOOL_NAME, MCP_TRACEPARENT), "tools/call",
					Optional.of(TOOL_NAME)), "trace-raw");
			rawObserver.awaitAllFinished();
			DefaultMcpRequestContext context =
					rawObserver.startedContexts.get(0);
			Assertions.assertTrue(context.getTraceContext().isPresent());
			Assertions.assertTrue(context.traceCorrelationToken().isEmpty());
			Assertions.assertEquals(1, rawObserver.logEvents.size(),
					rawObserver.logEvents.toString());
			assertTraceLogEvent(rawObserver.logEvents.get(0),
					"traceId=0af7651916cd43dd8448eb211c80319c");
		} finally {
			rawSoklet.close();
		}

		TraceRecordingLifecycleObserver enabledObserver =
				new TraceRecordingLifecycleObserver(1);
		McpServer enabledServer = serverBuilder(endpoint)
				.traceCorrelationKey(traceKey("trace-first", 0))
				.logRawValidatedTraceIds(true)
				.build();
		Soklet enabledSoklet = managedSoklet(enabledServer,
				List.of(enabledObserver), new RecordingMetricsCollector());
		try {
			enabledSoklet.start();
			int port = enabledServer.getDiagnostics().getBoundAddress()
					.orElseThrow().getPort();
			assertSuccess(send(port, toolRequestWithTrace("trace-enabled",
					TOOL_NAME, MCP_TRACEPARENT), "tools/call",
					Optional.of(TOOL_NAME)), "trace-enabled");
			enabledObserver.awaitAllFinished();
			DefaultMcpSecurityControls.TraceCorrelationToken token =
					enabledObserver.startedContexts.get(0)
							.traceCorrelationToken().orElseThrow();
			Assertions.assertEquals("trace-first", token.keyId());
			Assertions.assertEquals(FIRST_TRACE_TOKEN, token.token());
			Assertions.assertFalse(token.toString().contains(token.token()));
			Assertions.assertFalse(token.toString().contains(
					"0af7651916cd43dd8448eb211c80319c"));
			Assertions.assertTrue(token.toString().contains("token=<redacted>"));
			Assertions.assertEquals(1, enabledObserver.logEvents.size(),
					enabledObserver.logEvents.toString());
			assertTraceLogEvent(enabledObserver.logEvents.get(0),
					"tokenFormat=soklet-mcp-trace-correlation-v1;"
							+ "keyId=trace-first;token=" + FIRST_TRACE_TOKEN
							+ ";traceId=0af7651916cd43dd8448eb211c80319c");
		} finally {
			enabledSoklet.close();
		}
	}

	@Test
	public void distinctTraceMetadataDoesNotCreateMetricDimensionsOrLeakIntoRendering()
			throws Exception {
		TraceRecordingLifecycleObserver observer =
				new TraceRecordingLifecycleObserver(
						TRACE_CARDINALITY_REQUEST_COUNT);
		RecordingDefaultMetricsCollector collector =
				new RecordingDefaultMetricsCollector(
						TRACE_CARDINALITY_REQUEST_COUNT);
		McpToolRegistration<McpJsonObject> tool = McpToolRegistration
				.withName(TOOL_NAME)
				.jsonArguments()
				.handler((context, arguments, features) ->
						McpCompleteResult.fromToolText("metric-cardinality-checked"))
				.build();
		McpEndpoint endpoint = endpointBuilder("metric-cardinality-test")
				.tool(tool)
				.build();
		byte[] keyMaterial = TRACE_CARDINALITY_KEY_MATERIAL.getBytes(
				StandardCharsets.UTF_8);
		Assertions.assertEquals(32, keyMaterial.length);
		McpTraceCorrelationKey traceKey =
				McpTraceCorrelationKey.fromIdAndBytes(
						TRACE_CARDINALITY_KEY_ID, keyMaterial);
		Set<String> sensitiveCanaries = new LinkedHashSet<>();
		sensitiveCanaries.add(TRACE_CARDINALITY_KEY_ID);
		sensitiveCanaries.add(TRACE_CARDINALITY_KEY_MATERIAL);
		sensitiveCanaries.add(HexFormat.of().formatHex(keyMaterial));
		sensitiveCanaries.add(Base64.getEncoder().withoutPadding()
				.encodeToString(keyMaterial));
		Arrays.fill(keyMaterial, (byte) 0);
		McpServer server = serverBuilder(endpoint)
				.traceCorrelationKey(traceKey)
				.logRawValidatedTraceIds(true)
				.build();
		Soklet soklet = managedSoklet(server, List.of(observer), collector);
		List<String> mcpTraceIds = new java.util.ArrayList<>();
		List<String> httpTraceIds = new java.util.ArrayList<>();
		List<String> mcpTracestates = new java.util.ArrayList<>();
		List<String> httpTracestates = new java.util.ArrayList<>();
		List<String> mcpBaggage = new java.util.ArrayList<>();
		List<String> httpBaggage = new java.util.ArrayList<>();

		try {
			soklet.start();
			int port = server.getDiagnostics().getBoundAddress()
					.orElseThrow().getPort();
			for (int index = 0; index < TRACE_CARDINALITY_REQUEST_COUNT;
					++index) {
				String suffix = String.format(Locale.ROOT, "%02x", index);
				String requestId = "metric-request-id-canary-" + suffix;
				String mcpTraceId = String.format(Locale.ROOT,
						"a1%030x", index + 1);
				String mcpParentId = String.format(Locale.ROOT,
						"b1%014x", index + 1);
				String httpTraceId = String.format(Locale.ROOT,
						"c1%030x", index + 1);
				String httpParentId = String.format(Locale.ROOT,
						"d1%014x", index + 1);
				String mcpTraceparent = "00-" + mcpTraceId + "-"
						+ mcpParentId + "-01";
				String httpTraceparent = "00-" + httpTraceId + "-"
						+ httpParentId + "-00";
				String mcpTracestateKey = "mcpvendor";
				String mcpTracestateValue = "mcpstatecanary" + suffix;
				String mcpTracestate = mcpTracestateKey + "="
						+ mcpTracestateValue;
				String httpTracestateKey = "httpvendor";
				String httpTracestateValue = "httpstatecanary" + suffix;
				String httpTracestate = httpTracestateKey + "="
						+ httpTracestateValue;
				String mcpBaggageKey = "mcpbag" + suffix;
				String mcpBaggageValueToken = "mcpbagvaluecanary" + suffix;
				String mcpBaggageValue = mcpBaggageKey + "="
						+ mcpBaggageValueToken;
				String httpBaggageKey = "httpbag" + suffix;
				String httpBaggageValueToken =
						"httpbagvaluecanary" + suffix;
				String httpBaggageValue = httpBaggageKey + "="
						+ httpBaggageValueToken;
				mcpTraceIds.add(mcpTraceId);
				httpTraceIds.add(httpTraceId);
				mcpTracestates.add(mcpTracestate);
				httpTracestates.add(httpTracestate);
				mcpBaggage.add(mcpBaggageValue);
				httpBaggage.add(httpBaggageValue);
				sensitiveCanaries.addAll(List.of(requestId, mcpTraceId,
						mcpParentId, mcpTraceparent, httpTraceId,
						httpParentId, httpTraceparent, mcpTracestateKey,
						mcpTracestateValue, mcpTracestate,
						httpTracestateKey, httpTracestateValue,
						httpTracestate, mcpBaggageKey,
						mcpBaggageValueToken, mcpBaggageValue,
						httpBaggageKey, httpBaggageValueToken,
						httpBaggageValue));

				HttpResponse<String> response = sendWithTraceMetadata(port,
						toolRequestWithTraceMetadata(requestId, TOOL_NAME,
								mcpTraceparent, mcpTracestate,
								mcpBaggageValue), httpTraceparent,
						httpTracestate, httpBaggageValue);
				assertSuccess(response, requestId);
			}
			observer.awaitAllFinished();
			collector.awaitRequestFinishes();
			soklet.close();
			collector.awaitServerStopped();

			Assertions.assertEquals(TRACE_CARDINALITY_REQUEST_COUNT,
					observer.startedContexts.size());
			Set<String> derivedTokens = new LinkedHashSet<>();
			List<String> expectedTraceLogMessages = new java.util.ArrayList<>();
			for (int index = 0; index < TRACE_CARDINALITY_REQUEST_COUNT;
					++index) {
				DefaultMcpRequestContext context =
						observer.startedContexts.get(index);
				Assertions.assertSame(context,
						observer.finishedContexts.get(index));
				Assertions.assertEquals(mcpTraceIds.get(index),
						context.getTraceContext().orElseThrow().getTraceId());
				Assertions.assertEquals(mcpTracestates.get(index),
						context.getTraceContext().orElseThrow()
								.toTracestateHeaderValue().orElseThrow());
				String suffix = String.format(Locale.ROOT, "%02x", index);
				Assertions.assertEquals("mcpbagvaluecanary" + suffix,
						context.getBaggage().get("mcpbag" + suffix));
				Assertions.assertEquals(httpTraceIds.get(index),
						context.getRequest().getTraceContext().orElseThrow()
								.getTraceId());
				Assertions.assertEquals(httpTracestates.get(index),
						context.getRequest().getTraceContext().orElseThrow()
								.toTracestateHeaderValue().orElseThrow());
				Assertions.assertEquals(Set.of(httpBaggage.get(index)),
						context.getRequest().getHeaders().get("baggage"));
				DefaultMcpSecurityControls.TraceCorrelationToken token =
						context.traceCorrelationToken().orElseThrow();
				Assertions.assertEquals(TRACE_CARDINALITY_KEY_ID,
						token.keyId());
				derivedTokens.add(token.token());
				sensitiveCanaries.add(token.token());
				expectedTraceLogMessages.add(
						"tokenFormat=soklet-mcp-trace-correlation-v1;keyId="
								+ TRACE_CARDINALITY_KEY_ID + ";token="
								+ token.token() + ";traceId="
								+ mcpTraceIds.get(index));
			}
			Assertions.assertEquals(TRACE_CARDINALITY_REQUEST_COUNT,
					derivedTokens.size());
			Assertions.assertEquals(TRACE_CARDINALITY_REQUEST_COUNT,
					observer.logEvents.size(), observer.logEvents.toString());
			for (int index = 0; index < observer.logEvents.size(); ++index)
				assertTraceLogEvent(observer.logEvents.get(index),
						expectedTraceLogMessages.get(index));

			List<McpMetricsEvent> events = collector.events();
			Assertions.assertEquals(TRACE_CARDINALITY_REQUEST_COUNT,
					countEvents(events, McpMetricsEvent.RequestAccepted.class));
			Assertions.assertEquals(0,
					countEvents(events, McpMetricsEvent.RequestRejected.class));
			Assertions.assertEquals(TRACE_CARDINALITY_REQUEST_COUNT,
					countEvents(events, McpMetricsEvent.RequestStarted.class));
			Assertions.assertEquals(TRACE_CARDINALITY_REQUEST_COUNT,
					countEvents(events,
							McpMetricsEvent.HandlerExecutionStarted.class));
			Assertions.assertEquals(TRACE_CARDINALITY_REQUEST_COUNT,
					countEvents(events,
							McpMetricsEvent.HandlerExecutionFinished.class));
			Assertions.assertEquals(TRACE_CARDINALITY_REQUEST_COUNT,
					countEvents(events, McpMetricsEvent.RequestFinished.class));
			Assertions.assertEquals(1,
					countEvents(events, McpMetricsEvent.ServerStarted.class));
			Assertions.assertEquals(1,
					countEvents(events, McpMetricsEvent.ServerStopped.class));
			Assertions.assertEquals(0,
					countEvents(events,
							McpMetricsEvent.CancelationSignaled.class));
			Assertions.assertEquals(0,
					countEvents(events, McpMetricsEvent.ProgressEmitted.class));
			Assertions.assertEquals(0,
					countEvents(events, McpMetricsEvent.KeepAliveEmitted.class));
			Assertions.assertEquals(0,
					countEvents(events, McpMetricsEvent.ProtocolError.class));
			Assertions.assertEquals(0,
					countEvents(events,
							McpMetricsEvent.UnknownMirroredHeader.class));
			for (McpMetricsEvent event : events)
				assertFiniteMetricProjection(event);

			MetricsCollector.Snapshot snapshot = collector.snapshot()
					.orElseThrow();
			McpMetricsSnapshot mcpMetrics = snapshot.getMcpMetrics();
			Assertions.assertEquals(1L, mcpMetrics.getServerStarts());
			Assertions.assertEquals(0L,
					mcpMetrics.getActiveHandlerExecutions());
			Assertions.assertEquals(0L, mcpMetrics.getHandlerQueueDepth());
			Assertions.assertEquals(0L,
					mcpMetrics.getHandlerCapacityRejections());
			Assertions.assertEquals(Map.of(ShutdownComponentDisposition.GRACEFUL_TERMINATION, 1L),
					mcpMetrics.getShutdowns());
			Assertions.assertTrue(mcpMetrics.getConnectionsAccepted() > 0L);
			Assertions.assertEquals(0L,
					mcpMetrics.getConnectionsRejected());
			Assertions.assertTrue(mcpMetrics.getTransportFailures().isEmpty());
			Assertions.assertEquals(TRACE_CARDINALITY_REQUEST_COUNT,
					mcpMetrics.getRequestsAccepted());
			Assertions.assertEquals(0L, mcpMetrics.getRequestsRejected());
			McpMetricsSnapshot.RequestOutcomeKey requestOutcomeKey =
					McpMetricsSnapshot.RequestOutcomeKey.fromDimensions(MCP_PATH,
							"tools/call", McpRequestOutcome.COMPLETE);
			Assertions.assertEquals(0L, mcpMetrics.getActiveRequests());
			Assertions.assertEquals(Map.of(requestOutcomeKey,
					(long) TRACE_CARDINALITY_REQUEST_COUNT),
					mcpMetrics.getRequests());
			MetricsCollector.HistogramSnapshot requestDurations =
					mcpMetrics.getRequestDurations().get(requestOutcomeKey);
			Assertions.assertNotNull(requestDurations);
			Assertions.assertEquals(TRACE_CARDINALITY_REQUEST_COUNT,
					requestDurations.getCount());
			Assertions.assertTrue(requestDurations.getSum() >= 0L);
			Assertions.assertTrue(requestDurations.getMin() >= 0L);
			Assertions.assertTrue(requestDurations.getMax()
					>= requestDurations.getMin());
			Assertions.assertEquals(0L,
					mcpMetrics.getActiveRequestStreams());
			Assertions.assertTrue(
					mcpMetrics.getRequestStreamDurations().isEmpty());
			Assertions.assertEquals(0L,
					mcpMetrics.getActiveSubscriptions());
			Assertions.assertTrue(
					mcpMetrics.getSubscriptionDurations().isEmpty());
			Assertions.assertTrue(
					mcpMetrics.getCancelationsSignaled().isEmpty());
			Assertions.assertTrue(mcpMetrics.getProgressEmitted().isEmpty());
			Assertions.assertEquals(0L, mcpMetrics.getKeepAlivesEmitted());
			Assertions.assertTrue(mcpMetrics.getProtocolErrors().isEmpty());
			Assertions.assertTrue(
					mcpMetrics.getUnknownMirroredHeaders().isEmpty());
			List<String> filteredSamples = new CopyOnWriteArrayList<>();
			String prometheus = collector.snapshotText(
					MetricsCollector.SnapshotTextOptions.fromMetricsFormat(
							MetricsCollector.MetricsFormat.PROMETHEUS))
					.orElseThrow();
			String filteredPrometheus = collector.snapshotText(
					MetricsCollector.SnapshotTextOptions.withMetricsFormat(
							MetricsCollector.MetricsFormat.PROMETHEUS)
							.metricFilter(sample -> {
								if (!sample.getName().startsWith("soklet_mcp_"))
									return false;
								filteredSamples.add(sample.getName()
										+ sample.getLabels());
								return true;
							})
							.build()).orElseThrow();
			Set<String> expectedMcpSamples = new LinkedHashSet<>(Set.of(
					"soklet_mcp_server_starts_total{}",
					"soklet_mcp_connections_accepted_total{}",
					"soklet_mcp_connections_rejected_total{}",
					"soklet_mcp_requests_accepted_total{}",
					"soklet_mcp_requests_rejected_total{}",
					"soklet_mcp_handler_executions_active{}",
					"soklet_mcp_handler_queue_depth{}",
					"soklet_mcp_handler_capacity_rejections_total{}",
					"soklet_mcp_shutdowns_total{outcome=graceful_termination}",
					"soklet_mcp_requests_active{}",
					"soklet_mcp_request_streams_active{}",
					"soklet_mcp_subscriptions_active{}",
					"soklet_mcp_keep_alives_emitted_total{}",
					"soklet_mcp_requests_total{endpoint=/mcp, method=tools/call, outcome=complete}",
					"soklet_mcp_request_duration_nanos_count{endpoint=/mcp, method=tools/call, outcome=complete}",
					"soklet_mcp_request_duration_nanos_sum{endpoint=/mcp, method=tools/call, outcome=complete}"));
			for (String upperBound : List.of("1000000", "2000000",
					"5000000", "10000000", "25000000", "50000000",
					"100000000", "200000000", "400000000",
					"800000000", "1500000000", "3000000000",
					"7000000000", "15000000000", "+Inf"))
				expectedMcpSamples.add(
						"soklet_mcp_request_duration_nanos_bucket{endpoint=/mcp, method=tools/call, outcome=complete, le="
								+ upperBound + "}");
			Assertions.assertEquals(31, expectedMcpSamples.size());
			Assertions.assertEquals(expectedMcpSamples.size(),
					filteredSamples.size(), filteredSamples.toString());
			Assertions.assertEquals(expectedMcpSamples,
					Set.copyOf(filteredSamples));
			String openMetrics = collector.snapshotText(
					MetricsCollector.SnapshotTextOptions.fromMetricsFormat(
							MetricsCollector.MetricsFormat.OPEN_METRICS_1_0))
					.orElseThrow();
			Assertions.assertTrue(openMetrics.endsWith("# EOF\n"));

			collector.reset();
			MetricsCollector.Snapshot resetSnapshot = collector.snapshot()
					.orElseThrow();
			Assertions.assertSame(McpMetricsSnapshot.emptyInstance(),
					resetSnapshot.getMcpMetrics());
			String resetPrometheus = collector.snapshotText(
					MetricsCollector.SnapshotTextOptions.fromMetricsFormat(
							MetricsCollector.MetricsFormat.PROMETHEUS))
					.orElseThrow();
			List<String> resetFilteredSamples = new CopyOnWriteArrayList<>();
			String resetFilteredPrometheus = collector.snapshotText(
					MetricsCollector.SnapshotTextOptions.withMetricsFormat(
							MetricsCollector.MetricsFormat.PROMETHEUS)
							.metricFilter(sample -> {
								if (!sample.getName().startsWith("soklet_mcp_"))
									return false;
								resetFilteredSamples.add(sample.getName()
										+ sample.getLabels());
								return true;
							})
							.build()).orElseThrow();
			Set<String> expectedResetMcpSamples = Set.of(
					"soklet_mcp_server_starts_total{}",
					"soklet_mcp_connections_accepted_total{}",
					"soklet_mcp_connections_rejected_total{}",
					"soklet_mcp_requests_accepted_total{}",
					"soklet_mcp_requests_rejected_total{}",
					"soklet_mcp_handler_executions_active{}",
					"soklet_mcp_handler_queue_depth{}",
					"soklet_mcp_handler_capacity_rejections_total{}",
					"soklet_mcp_requests_active{}",
					"soklet_mcp_request_streams_active{}",
					"soklet_mcp_subscriptions_active{}",
					"soklet_mcp_keep_alives_emitted_total{}");
			Assertions.assertEquals(12, expectedResetMcpSamples.size());
			Assertions.assertEquals(expectedResetMcpSamples.size(),
					resetFilteredSamples.size(), resetFilteredSamples.toString());
			Assertions.assertEquals(expectedResetMcpSamples,
					Set.copyOf(resetFilteredSamples));
			String resetOpenMetrics = collector.snapshotText(
					MetricsCollector.SnapshotTextOptions.fromMetricsFormat(
							MetricsCollector.MetricsFormat.OPEN_METRICS_1_0))
					.orElseThrow();
			String builtInRendering = String.join("\n",
					events.toString(), renderSnapshotValues(snapshot),
					prometheus, filteredPrometheus, filteredSamples.toString(),
					openMetrics, renderSnapshotValues(resetSnapshot),
					resetPrometheus, resetFilteredPrometheus,
					resetFilteredSamples.toString(), resetOpenMetrics);
			assertNoSensitiveCanaries(builtInRendering, sensitiveCanaries);
		} finally {
			soklet.close();
		}
	}

	@Test
	public void deprecatedWireLogLevelProjectsToTheRealRequestContext()
			throws Exception {
		RecordingLifecycleObserver observer = new RecordingLifecycleObserver();
		RecordingMetricsCollector collector = new RecordingMetricsCollector();
		AtomicReference<McpRequestContext> handlerContext = new AtomicReference<>();
		McpToolRegistration<McpJsonObject> tool = McpToolRegistration
				.withName(TOOL_NAME)
				.jsonArguments()
				.handler((context, arguments, features) -> {
					handlerContext.set(context);
					return McpCompleteResult.fromToolText("log-level-observed");
				})
				.build();
		McpEndpoint endpoint = endpointBuilder("log-level-observation-test")
				.tool(tool)
				.build();
		McpServer server = serverBuilder(endpoint).build();
		Soklet soklet = managedSoklet(server, List.of(observer), collector);

		try {
			soklet.start();
			int port = server.getDiagnostics().getBoundAddress()
					.orElseThrow().getPort();
			String body = request("log-level", "tools/call",
					",\"io.modelcontextprotocol/logLevel\":\"warning\"",
					",\"name\":\"" + TOOL_NAME + "\",\"arguments\":{}");
			HttpResponse<String> response = send(port, body, "tools/call",
					Optional.of(TOOL_NAME));
			observer.awaitFinished();

			assertSuccess(response, "log-level");
			McpRequestContext context = observer.startedContext.get();
			Assertions.assertNotNull(context);
			Assertions.assertSame(context, handlerContext.get());
			Assertions.assertEquals(Optional.of(McpLogLevel.WARNING),
					context.getDeprecatedLogLevel());
		} finally {
			soklet.close();
		}
	}

	@Test
	public void handlerFailurePublishesExactInternalErrorAndImmutableThrowable()
			throws Exception {
		RecordingLifecycleObserver observer = new RecordingLifecycleObserver();
		RecordingMetricsCollector collector = new RecordingMetricsCollector();
		IllegalStateException handlerFailure = new IllegalStateException(
				"sentinel-handler-failure");
		McpToolRegistration<McpJsonObject> tool = McpToolRegistration
				.withName(TOOL_NAME)
				.jsonArguments()
				.handler((context, arguments, features) -> {
					throw handlerFailure;
				})
				.build();
		McpEndpoint endpoint = endpointBuilder("handler-failure-observation-test")
				.tool(tool)
				.build();
		McpServer server = serverBuilder(endpoint).build();
		Soklet soklet = managedSoklet(server, List.of(observer), collector);

		try {
			soklet.start();
			int port = server.getDiagnostics().getBoundAddress()
					.orElseThrow().getPort();
			HttpResponse<String> response = send(port,
					toolRequest("handler-failure", TOOL_NAME), "tools/call",
					Optional.of(TOOL_NAME));
			observer.awaitFinished();

			Assertions.assertEquals(500, response.statusCode(), response.body());
			Assertions.assertEquals("{\"jsonrpc\":\"2.0\",\"id\":"
					+ "\"handler-failure\",\"error\":{\"code\":-32603,"
					+ "\"message\":\"Internal error\"}}", response.body());
			assertSingleLifecycle(observer, endpoint, "tools/call",
					Optional.of(TOOL_NAME), "handler-failure",
					McpRequestOutcome.INTERNAL_ERROR);
			McpJsonRpcError error = Optional.ofNullable(observer.error.get())
					.orElseThrow();
			Assertions.assertEquals(-32603, error.getCode());
			Assertions.assertEquals("Internal error", error.getMessage());
			Assertions.assertEquals(Optional.empty(), error.getData());
			List<Throwable> finishThrowables = observer.finishThrowables.get();
			Assertions.assertEquals(List.of(handlerFailure), finishThrowables);
			Assertions.assertThrows(UnsupportedOperationException.class,
					() -> finishThrowables.add(
							new RuntimeException("must-not-add")));
			assertSingleMetrics(collector, "tools/call",
					McpRequestOutcome.INTERNAL_ERROR);
		} finally {
			soklet.close();
		}
	}

	@Test
	public void admissionRejectionDoesNotPublishAdmittedRequestObservation()
			throws Exception {
		RecordingLifecycleObserver observer = new RecordingLifecycleObserver();
		RecordingMetricsCollector collector = new RecordingMetricsCollector();
		McpEndpoint endpoint = endpointBuilder("rejected-observation-test").build();
		McpAdmissionRejection rejection = McpAdmissionRejection
				.withStatusCodeAndError(401,
						McpJsonRpcError.fromApplication(1_001,
								"Authentication required"))
				.header("WWW-Authenticate", "Bearer realm=soklet-mcp")
				.build();
		McpServer server = McpServer.withPort(0)
				.host(LOOPBACK)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.admissionController(context ->
						McpAdmissionDecision.rejected(rejection))
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(LOOPBACK))
				.build();
		Soklet soklet = managedSoklet(server, List.of(observer), collector);

		try {
			soklet.start();
			int port = server.getDiagnostics().getBoundAddress()
					.orElseThrow().getPort();
			HttpResponse<String> response = send(port, discoverRequest("rejected"),
					"server/discover", Optional.empty());

			Assertions.assertEquals(401, response.statusCode(), response.body());
			Assertions.assertEquals("Bearer realm=soklet-mcp",
					response.headers().firstValue("WWW-Authenticate").orElseThrow());
			Assertions.assertTrue(response.body().contains("\"id\":\"rejected\""),
					response.body());
			Assertions.assertEquals(0, observer.starts.get());
			Assertions.assertEquals(0, observer.finishes.get());
			Assertions.assertTrue(collector.requestStartedEvents().isEmpty());
			Assertions.assertTrue(collector.requestFinishedEvents().isEmpty());
		} finally {
			soklet.close();
		}
	}

	@Test
	public void postAdmissionRequestRateRejectionPublishesExactError()
			throws Exception {
		RecordingLifecycleObserver observer = new RecordingLifecycleObserver();
		RecordingMetricsCollector collector = new RecordingMetricsCollector();
		McpEndpoint endpoint = endpointBuilder("rate-rejection-observation-test")
				.build();
		McpServer server = serverBuilder(endpoint)
				.requestRateLimiter(context -> {
					Assertions.assertEquals(McpRateLimitTarget.REQUEST,
							context.getTarget());
					return McpRateLimitDecision.denied(Duration.ofMillis(1));
				})
				.build();
		Soklet soklet = managedSoklet(server, List.of(observer), collector);

		try {
			soklet.start();
			int port = server.getDiagnostics().getBoundAddress()
					.orElseThrow().getPort();
			HttpResponse<String> response = send(port,
					discoverRequest("rate-rejected"), "server/discover",
					Optional.empty());
			observer.awaitFinished();

			Assertions.assertEquals(429, response.statusCode(), response.body());
			Assertions.assertEquals("1",
					response.headers().firstValue("Retry-After").orElseThrow());
			Assertions.assertEquals("{\"jsonrpc\":\"2.0\",\"id\":"
					+ "\"rate-rejected\",\"error\":{\"code\":-31999,"
					+ "\"message\":\"Rate limited\"}}", response.body());
			assertSingleLifecycle(observer, endpoint, "server/discover",
					Optional.empty(), "rate-rejected", McpRequestOutcome.REJECTED);
			McpJsonRpcError error = Optional.ofNullable(observer.error.get())
					.orElseThrow();
			Assertions.assertEquals(McpJsonRpcError.SOKLET_RATE_LIMIT_ERROR_CODE,
					error.getCode());
			Assertions.assertEquals("Rate limited", error.getMessage());
			Assertions.assertEquals(Optional.empty(), error.getData());
			Assertions.assertEquals(List.of(), observer.finishThrowables.get());
			assertSingleMetrics(collector, "server/discover",
					McpRequestOutcome.REJECTED);
		} finally {
			soklet.close();
		}
	}

	@Test
	public void unsupportedNotificationRetainsRawLifecycleMethodAndBoundsMetrics()
			throws Exception {
		String unsupportedMethod = "vendor.example/future-notification";
		RecordingLifecycleObserver observer = new RecordingLifecycleObserver();
		RecordingMetricsCollector collector = new RecordingMetricsCollector();
		McpEndpoint endpoint = endpointBuilder(
				"unsupported-notification-observation-test").build();
		McpServer server = serverBuilder(endpoint)
				.requestRateLimiter(context -> McpRateLimitDecision.allowed())
				.build();
		Soklet soklet = managedSoklet(server, List.of(observer), collector);

		try {
			soklet.start();
			int port = server.getDiagnostics().getBoundAddress()
					.orElseThrow().getPort();
			HttpResponse<String> response = send(port,
					notification(unsupportedMethod), unsupportedMethod,
					Optional.empty());
			observer.awaitFinished();

			Assertions.assertEquals(400, response.statusCode(), response.body());
			Assertions.assertTrue(response.body().isEmpty(), response.body());
			Assertions.assertEquals(1, observer.starts.get());
			Assertions.assertEquals(1, observer.finishes.get());
			Assertions.assertSame(observer.startedContext.get(),
					observer.finishedContext.get());
			McpRequestContext context = observer.startedContext.get();
			Assertions.assertNotNull(context);
			Assertions.assertSame(endpoint, context.getEndpoint());
			Assertions.assertEquals(unsupportedMethod,
					context.getJsonRpcMethod());
			Assertions.assertEquals(Optional.empty(), context.getRequestId());
			Assertions.assertEquals(Optional.empty(), context.getOperationName());
			Assertions.assertEquals(McpRequestOutcome.PROTOCOL_ERROR,
					observer.outcome.get());
			Assertions.assertNull(observer.error.get());
			Assertions.assertEquals(List.of(), observer.finishThrowables.get());
			assertSingleMetrics(collector,
					McpMetricsEvent.UNRECOGNIZED_JSON_RPC_METHOD,
					McpRequestOutcome.PROTOCOL_ERROR);
		} finally {
			soklet.close();
		}
	}

	@Test
	public void throwingObservationCallbacksKeepRawCarriersApplicationOwnedAndLogsRedacted()
			throws Exception {
		String requestHeaderCanary = "mcp-request-header-canary";
		String requestBodyCanary = "mcp-request-body-canary";
		RuntimeException startFailure = new RuntimeException(
				"lifecycle-start-secret");
		RuntimeException finishFailure = new RuntimeException(
				"lifecycle-finish-secret");
		RuntimeException metricsFailure = new RuntimeException("metrics-secret");
		LifecycleObserver throwingObserver = new LifecycleObserver() {
			@Override
			public void didStartMcpRequestHandling(
					@NonNull McpRequestContext context) {
				throw startFailure;
			}

			@Override
			public void didFinishMcpRequestHandling(
					@NonNull McpRequestContext context,
					@NonNull McpRequestOutcome outcome,
					@Nullable McpJsonRpcError error,
					@NonNull Duration duration,
					@NonNull List<@NonNull Throwable> throwables) {
				throw finishFailure;
			}

			@Override
			public void didReceiveLogEvent(@NonNull LogEvent logEvent) {
				// Keep the deliberately throwing observer quiet while another records.
			}
		};
		RecordingLifecycleObserver recordingObserver =
				new RecordingLifecycleObserver();
		MetricsCollector throwingCollector = new MetricsCollector() {
			@Override
			public void didRecordMcpMetricsEvent(@NonNull McpMetricsEvent event) {
				if (event instanceof McpMetricsEvent.RequestStarted
						|| event instanceof McpMetricsEvent.RequestFinished)
					throw metricsFailure;
			}
		};
		McpEndpoint endpoint = endpointBuilder("throwing-observation-test").build();
		McpServer server = serverBuilder(endpoint).build();
		Soklet soklet = managedSoklet(server,
				List.of(throwingObserver, recordingObserver), throwingCollector);

		try {
			soklet.start();
			int port = server.getDiagnostics().getBoundAddress()
					.orElseThrow().getPort();
			HttpResponse<String> response = send(port,
					discoverRequest(requestBodyCanary), "server/discover",
					Optional.empty(), Map.of("Authorization",
							"Bearer " + requestHeaderCanary));
			recordingObserver.awaitFinished();
			awaitLogCount(recordingObserver.logEvents,
					LogEventType.LIFECYCLE_OBSERVER_DID_START_MCP_REQUEST_HANDLING_FAILED,
					1);
			awaitLogCount(recordingObserver.logEvents,
					LogEventType.LIFECYCLE_OBSERVER_DID_FINISH_MCP_REQUEST_HANDLING_FAILED,
					1);
			awaitLogCount(recordingObserver.logEvents,
					LogEventType.METRICS_COLLECTOR_FAILED, 2);

			assertSuccess(response, requestBodyCanary);
			Assertions.assertFalse(response.body().contains("secret"), response.body());
			Assertions.assertEquals(1, recordingObserver.starts.get());
			Assertions.assertEquals(1, recordingObserver.finishes.get());
			Assertions.assertSame(recordingObserver.startedContext.get(),
					recordingObserver.finishedContext.get());
			List<Throwable> finishThrowables =
					recordingObserver.finishThrowables.get();
			Assertions.assertEquals(List.of(startFailure), finishThrowables);
			Assertions.assertFalse(finishThrowables.contains(metricsFailure));
			Assertions.assertThrows(UnsupportedOperationException.class,
					() -> finishThrowables.add(new RuntimeException("must-not-add")));
			Request applicationOwnedRequest = recordingObserver.startedContext.get()
					.getRequest();
			Assertions.assertEquals(Set.of("Bearer " + requestHeaderCanary),
					applicationOwnedRequest.getHeaders().get("Authorization"));
			Assertions.assertTrue(applicationOwnedRequest.getBodyAsString().orElseThrow()
					.contains(requestBodyCanary));

			assertRedactedLogCount(recordingObserver.logEvents,
					LogEventType.LIFECYCLE_OBSERVER_DID_START_MCP_REQUEST_HANDLING_FAILED,
					1);
			assertRedactedLogCount(recordingObserver.logEvents,
					LogEventType.LIFECYCLE_OBSERVER_DID_FINISH_MCP_REQUEST_HANDLING_FAILED,
					1);
			assertRedactedLogCount(recordingObserver.logEvents,
					LogEventType.METRICS_COLLECTOR_FAILED, 2);
			Assertions.assertEquals(4, recordingObserver.logEvents.size(),
					recordingObserver.logEvents.toString());
			String ownedLogs = recordingObserver.logEvents.toString();
			for (String canary : List.of(requestHeaderCanary, requestBodyCanary,
					startFailure.getMessage(), finishFailure.getMessage(),
					metricsFailure.getMessage()))
				Assertions.assertFalse(ownedLogs.contains(canary), ownedLogs);
		} finally {
			soklet.close();
		}
	}

	private static McpEndpoint.@NonNull Builder endpointBuilder(
			@NonNull String implementationName) {
		return McpEndpoint.withPath(MCP_PATH)
				.serverInformation(McpImplementation.withNameAndVersion(
						implementationName, "4.0.0").build());
	}

	private static McpServer.@NonNull Builder serverBuilder(
			@NonNull McpEndpoint endpoint) {
		return McpServer.withPort(0)
				.host(LOOPBACK)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.admissionController(
						McpAdmissionController.acceptAllInstance())
				.toolRateLimiter(context -> McpRateLimitDecision.allowed())
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(LOOPBACK));
	}

	@NonNull
	private static Soklet managedSoklet(@NonNull McpServer server,
			@NonNull List<@NonNull LifecycleObserver> observers,
			@NonNull MetricsCollector collector) {
		SokletConfig config = SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecycleObservers(observers)
				.metricsCollector(collector)
				.lifecyclePolicy(LifecyclePolicy.builder()
						.startupTimeout(Duration.ofSeconds(5))
						.startupCancelationTimeout(Duration.ofSeconds(2))
						.gracefulShutdownDuration(Duration.ofSeconds(2))
						.forcedShutdownDuration(Duration.ofSeconds(1))
						.build())
				.build();
		if (collector instanceof RecordingDefaultMetricsCollector recording)
			recording.initialize(config);
		return Soklet.fromConfig(config);
	}

	@NonNull
	private static HttpResponse<String> send(int port, @NonNull String body,
			@NonNull String method,
			@NonNull Optional<@NonNull String> operationName) throws Exception {
		return send(port, body, method, operationName, Map.of());
	}

	@NonNull
	private static HttpResponse<String> send(int port, @NonNull String body,
			@NonNull String method,
			@NonNull Optional<@NonNull String> operationName,
			@NonNull Map<@NonNull String, @NonNull String> additionalHeaders)
			throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder()
				.uri(URI.create("http://" + LOOPBACK + ":" + port + MCP_PATH))
				.timeout(Duration.ofSeconds(5))
				.header("Content-Type", JSON_MEDIA_TYPE + "; charset=UTF-8")
				.header("Accept", JSON_MEDIA_TYPE + ", text/event-stream")
				.header("MCP-Protocol-Version", PROTOCOL_VERSION)
				.header("Mcp-Method", method);
		operationName.ifPresent(value -> request.header("Mcp-Name", value));
		additionalHeaders.forEach(request::header);
		return HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(5))
				.version(HttpClient.Version.HTTP_1_1)
				.build()
				.send(request.POST(HttpRequest.BodyPublishers.ofString(
						body, StandardCharsets.UTF_8)).build(),
						HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	@NonNull
	private static CompletableFuture<HttpResponse<String>> sendAsync(int port,
			@NonNull String body, @NonNull String method,
			@NonNull Optional<@NonNull String> operationName,
			@NonNull Optional<@NonNull String> httpTraceparent) {
		HttpRequest.Builder request = HttpRequest.newBuilder()
				.uri(URI.create("http://" + LOOPBACK + ":" + port + MCP_PATH))
				.timeout(Duration.ofSeconds(5))
				.header("Content-Type", JSON_MEDIA_TYPE + "; charset=UTF-8")
				.header("Accept", JSON_MEDIA_TYPE + ", text/event-stream")
				.header("MCP-Protocol-Version", PROTOCOL_VERSION)
				.header("Mcp-Method", method);
		operationName.ifPresent(value -> request.header("Mcp-Name", value));
		httpTraceparent.ifPresent(value -> request.header("traceparent", value));
		return HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(5))
				.version(HttpClient.Version.HTTP_1_1)
				.build()
				.sendAsync(request.POST(HttpRequest.BodyPublishers.ofString(
						body, StandardCharsets.UTF_8)).build(),
						HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	@NonNull
	private static HttpResponse<String> sendWithHttpTraceparent(int port,
			@NonNull String body, @NonNull String traceparent) throws Exception {
		return sendAsync(port, body, "tools/call", Optional.of(TOOL_NAME),
				Optional.of(traceparent)).get(5, TimeUnit.SECONDS);
	}

	@NonNull
	private static String discoverRequest(@NonNull String id) {
		return request(id, "server/discover", "");
	}

	@NonNull
	private static String toolRequest(@NonNull String id,
			@NonNull String toolName) {
		return request(id, "tools/call", ",\"name\":\"" + toolName
				+ "\",\"arguments\":{}");
	}

	@NonNull
	private static String toolRequestWithTrace(@NonNull String id,
			@NonNull String toolName, @NonNull String traceparent) {
		return request(id, "tools/call",
				",\"traceparent\":\"" + traceparent + "\"",
				",\"name\":\"" + toolName + "\",\"arguments\":{}");
	}

	@NonNull
	private static String toolRequestWithTraceMetadata(@NonNull String id,
			@NonNull String toolName, @NonNull String traceparent,
			@NonNull String tracestate, @NonNull String baggage) {
		return request(id, "tools/call",
				",\"traceparent\":\"" + traceparent + "\""
						+ ",\"tracestate\":\"" + tracestate + "\""
						+ ",\"baggage\":\"" + baggage + "\"",
				",\"name\":\"" + toolName + "\",\"arguments\":{}");
	}

	@NonNull
	private static HttpResponse<String> sendWithTraceMetadata(int port,
			@NonNull String body, @NonNull String traceparent,
			@NonNull String tracestate, @NonNull String baggage) throws Exception {
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("http://" + LOOPBACK + ":" + port + MCP_PATH))
				.timeout(Duration.ofSeconds(5))
				.header("Content-Type", JSON_MEDIA_TYPE + "; charset=UTF-8")
				.header("Accept", JSON_MEDIA_TYPE + ", text/event-stream")
				.header("MCP-Protocol-Version", PROTOCOL_VERSION)
				.header("Mcp-Method", "tools/call")
				.header("Mcp-Name", TOOL_NAME)
				.header("traceparent", traceparent)
				.header("tracestate", tracestate)
				.header("baggage", baggage)
				.POST(HttpRequest.BodyPublishers.ofString(
						body, StandardCharsets.UTF_8))
				.build();
		return HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(5))
				.version(HttpClient.Version.HTTP_1_1)
				.build()
				.send(request, HttpResponse.BodyHandlers.ofString(
						StandardCharsets.UTF_8));
	}

	@NonNull
	private static String notification(@NonNull String method) {
		return "{\"jsonrpc\":\"2.0\",\"method\":\"" + method + "\"}";
	}

	@NonNull
	private static String request(@NonNull String id, @NonNull String method,
			@NonNull String additionalParameters) {
		return request(id, method, "", additionalParameters);
	}

	@NonNull
	private static String request(@NonNull String id, @NonNull String method,
			@NonNull String additionalMetadata,
			@NonNull String additionalParameters) {
		return "{\"jsonrpc\":\"2.0\",\"id\":\"" + id
				+ "\",\"method\":\"" + method
				+ "\",\"params\":{\"_meta\":"
				+ "{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}"
				+ additionalMetadata + "}"
				+ additionalParameters + "}}";
	}

	@NonNull
	private static McpTraceCorrelationKey traceKey(@NonNull String keyId,
			int firstByte) {
		byte[] keyMaterial = new byte[32];
		for (int index = 0; index < keyMaterial.length; ++index)
			keyMaterial[index] = (byte) (firstByte + index);
		return McpTraceCorrelationKey.fromIdAndBytes(keyId, keyMaterial);
	}

	private static void assertSuccess(@NonNull HttpResponse<String> response,
			@NonNull String expectedId) {
		Assertions.assertEquals(200, response.statusCode(), response.body());
		Assertions.assertEquals(JSON_MEDIA_TYPE,
				response.headers().firstValue("Content-Type").orElseThrow());
		Assertions.assertEquals("no-store",
				response.headers().firstValue("Cache-Control").orElseThrow());
		Assertions.assertTrue(response.body().contains(
				"\"id\":\"" + expectedId + "\""), response.body());
	}

	private static void assertTraceLogEvent(@NonNull LogEvent event,
			@NonNull String expectedMessage) {
		Assertions.assertEquals(LogEventType.MCP_TRACE_CORRELATION,
				event.getLogEventType());
		Assertions.assertEquals(expectedMessage, event.getMessage());
		Assertions.assertTrue(event.getThrowable().isEmpty());
		Assertions.assertTrue(event.getRequest().isEmpty());
		Assertions.assertTrue(event.getResourceMethod().isEmpty());
		Assertions.assertTrue(event.getMarshaledResponse().isEmpty());
	}

	private static void assertSingleCompleteLifecycle(
			@NonNull RecordingLifecycleObserver observer,
			@NonNull McpEndpoint expectedEndpoint, @NonNull String expectedMethod,
			@NonNull Optional<@NonNull String> expectedOperation,
			@NonNull String expectedRequestId) {
		assertSingleLifecycle(observer, expectedEndpoint, expectedMethod,
				expectedOperation, expectedRequestId, McpRequestOutcome.COMPLETE);
		Assertions.assertNull(observer.error.get());
		Assertions.assertEquals(List.of(), observer.finishThrowables.get());
	}

	private static void assertSingleLifecycle(
			@NonNull RecordingLifecycleObserver observer,
			@NonNull McpEndpoint expectedEndpoint, @NonNull String expectedMethod,
			@NonNull Optional<@NonNull String> expectedOperation,
			@NonNull String expectedRequestId,
			@NonNull McpRequestOutcome expectedOutcome) {
		Assertions.assertEquals(1, observer.starts.get());
		Assertions.assertEquals(1, observer.finishes.get());
		Assertions.assertSame(observer.startedContext.get(),
				observer.finishedContext.get());
		McpRequestContext context = observer.startedContext.get();
		Assertions.assertNotNull(context);
		Assertions.assertSame(expectedEndpoint, context.getEndpoint());
		Assertions.assertEquals(expectedMethod, context.getJsonRpcMethod());
		Assertions.assertEquals(expectedOperation, context.getOperationName());
		Assertions.assertEquals(McpRequestId.fromString(expectedRequestId),
				context.getRequestId().orElseThrow());
		Assertions.assertEquals(expectedOutcome, observer.outcome.get());
		Assertions.assertFalse(observer.duration.get().isNegative());
	}

	private static void assertSingleCompleteMetrics(
			@NonNull RecordingMetricsCollector collector,
			@NonNull String expectedMethod) throws InterruptedException {
		assertSingleMetrics(collector, expectedMethod,
				McpRequestOutcome.COMPLETE);
	}

	private static void assertSingleMetrics(
			@NonNull RecordingMetricsCollector collector,
			@NonNull String expectedMethod,
			@NonNull McpRequestOutcome expectedOutcome)
			throws InterruptedException {
		collector.awaitRequestFinished();
		List<McpMetricsEvent.RequestStarted> started =
				collector.requestStartedEvents();
		List<McpMetricsEvent.RequestFinished> finished =
				collector.requestFinishedEvents();
		Assertions.assertEquals(1, started.size(), started.toString());
		Assertions.assertEquals(MCP_PATH, started.get(0).getEndpointPath());
		Assertions.assertEquals(expectedMethod,
				started.get(0).getJsonRpcMethod());
		Assertions.assertEquals(1, finished.size(), finished.toString());
		Assertions.assertEquals(MCP_PATH, finished.get(0).getEndpointPath());
		Assertions.assertEquals(expectedMethod,
				finished.get(0).getJsonRpcMethod());
		Assertions.assertEquals(expectedOutcome, finished.get(0).getOutcome());
		Assertions.assertFalse(finished.get(0).getDuration().isNegative());
	}

	private static void assertRedactedLogCount(
			@NonNull List<@NonNull LogEvent> events,
			@NonNull LogEventType eventType, int expectedCount) {
		List<LogEvent> matching = events.stream()
				.filter(event -> event.getLogEventType() == eventType)
				.toList();
		Assertions.assertEquals(expectedCount, matching.size(), events.toString());
		for (LogEvent event : matching) {
			Assertions.assertTrue(event.getThrowable().isEmpty(), event.toString());
			Assertions.assertTrue(event.getRequest().isEmpty(), event.toString());
			Assertions.assertTrue(event.getResourceMethod().isEmpty(), event.toString());
			Assertions.assertTrue(event.getMarshaledResponse().isEmpty(),
					event.toString());
		}
	}

	private static void awaitLogCount(
			@NonNull List<@NonNull LogEvent> events,
			@NonNull LogEventType eventType, int expectedCount)
			throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (System.nanoTime() - deadline < 0L) {
			long count = events.stream()
					.filter(event -> event.getLogEventType() == eventType)
					.count();
			if (count == expectedCount)
				return;
			Thread.sleep(10L);
		}
		Assertions.assertEquals(expectedCount, events.stream()
				.filter(event -> event.getLogEventType() == eventType)
				.count(), events.toString());
	}

	private static long countEvents(@NonNull List<@NonNull McpMetricsEvent> events,
			@NonNull Class<? extends McpMetricsEvent> eventType) {
		return events.stream().filter(eventType::isInstance).count();
	}

	private static void assertFiniteMetricProjection(
			@NonNull McpMetricsEvent event) throws ReflectiveOperationException {
		Set<Integer> protocolErrorCodes = Set.of(-32700, -32600, -32601,
				-32602, -32603, -32020, -32021, -32022, -31999, -31998);
		for (java.lang.reflect.Method getter
				: event.getClass().getDeclaredMethods()) {
			if (!java.lang.reflect.Modifier.isPublic(getter.getModifiers())
					|| getter.getParameterCount() != 0
					|| !getter.getName().startsWith("get"))
				continue;
			String dimensionName = Character.toLowerCase(
					getter.getName().charAt(3)) + getter.getName().substring(4);
			Object value = getter.invoke(event);
			Assertions.assertNotNull(value, getter.toString());
			switch (dimensionName) {
				case "endpointPath" -> Assertions.assertEquals(MCP_PATH, value);
				case "jsonRpcMethod" ->
						Assertions.assertEquals("tools/call", value);
				case "outcome" -> Assertions.assertTrue(
						value instanceof McpRequestOutcome
								|| value instanceof ShutdownComponentDisposition,
						value.toString());
				case "reason" -> Assertions.assertTrue(
						value instanceof McpStreamTerminationReason
								|| value instanceof
								MetricsCollector.TransportFailureReason,
						value.toString());
				case "duration" -> Assertions.assertFalse(
						((Duration) value).isNegative(), value.toString());
				case "code" -> Assertions.assertTrue(
						protocolErrorCodes.contains((Integer) value),
						value.toString());
				default -> Assertions.fail(
						"Unexpected built-in metric dimension: "
								+ dimensionName);
			}
		}
	}

	@NonNull
	private static String renderSnapshotValues(
			MetricsCollector.@NonNull Snapshot snapshot) throws Exception {
		List<String> values = new java.util.ArrayList<>();
		for (java.lang.reflect.Method method
				: MetricsCollector.Snapshot.class.getDeclaredMethods()) {
			if (java.lang.reflect.Modifier.isPublic(method.getModifiers())
					&& method.getParameterCount() == 0
					&& method.getName().startsWith("get"))
				values.add(method.getName() + "=" + method.invoke(snapshot));
		}
		McpMetricsSnapshot mcpMetrics = snapshot.getMcpMetrics();
		values.add("mcpServerStarts=" + mcpMetrics.getServerStarts());
		values.add("mcpActive=" + mcpMetrics.getActiveHandlerExecutions());
		values.add("mcpQueued=" + mcpMetrics.getHandlerQueueDepth());
		values.add("mcpRejected="
				+ mcpMetrics.getHandlerCapacityRejections());
		values.add("mcpShutdowns=" + mcpMetrics.getShutdowns());
		values.add("mcpConnectionsAccepted="
				+ mcpMetrics.getConnectionsAccepted());
		values.add("mcpConnectionsRejected="
				+ mcpMetrics.getConnectionsRejected());
		values.add("mcpTransportFailures="
				+ mcpMetrics.getTransportFailures());
		values.add("mcpRequestsAccepted="
				+ mcpMetrics.getRequestsAccepted());
		values.add("mcpRequestsRejected="
				+ mcpMetrics.getRequestsRejected());
		values.add("mcpActiveRequests=" + mcpMetrics.getActiveRequests());
		values.add("mcpRequests=" + mcpMetrics.getRequests());
		values.add("mcpRequestDurations="
				+ mcpMetrics.getRequestDurations());
		values.add("mcpActiveRequestStreams="
				+ mcpMetrics.getActiveRequestStreams());
		values.add("mcpRequestStreamDurations="
				+ mcpMetrics.getRequestStreamDurations());
		values.add("mcpActiveSubscriptions="
				+ mcpMetrics.getActiveSubscriptions());
		values.add("mcpSubscriptionDurations="
				+ mcpMetrics.getSubscriptionDurations());
		values.add("mcpCancelationsSignaled="
				+ mcpMetrics.getCancelationsSignaled());
		values.add("mcpProgressEmitted="
				+ mcpMetrics.getProgressEmitted());
		values.add("mcpKeepAlivesEmitted="
				+ mcpMetrics.getKeepAlivesEmitted());
		values.add("mcpProtocolErrors=" + mcpMetrics.getProtocolErrors());
		values.add("mcpUnknownMirroredHeaders="
				+ mcpMetrics.getUnknownMirroredHeaders());
		return values.toString();
	}

	private static void assertNoSensitiveCanaries(@NonNull String rendering,
			@NonNull Set<@NonNull String> sensitiveCanaries) {
		int index = 0;
		for (String canary : sensitiveCanaries) {
			int canaryIndex = index++;
			Assertions.assertFalse(rendering.contains(canary),
					() -> "Built-in metric rendering leaked sensitive canary "
							+ canaryIndex + ".");
		}
	}

	private static final class TraceRecordingLifecycleObserver
			implements LifecycleObserver {
		private final CountDownLatch firstFinished = new CountDownLatch(1);
		private final CountDownLatch allFinished;
		private final List<DefaultMcpRequestContext> startedContexts =
				new CopyOnWriteArrayList<>();
		private final List<DefaultMcpRequestContext> finishedContexts =
				new CopyOnWriteArrayList<>();
		private final List<LogEvent> logEvents = new CopyOnWriteArrayList<>();

		private TraceRecordingLifecycleObserver(int expectedRequests) {
			if (expectedRequests < 1)
				throw new IllegalArgumentException(
						"At least one trace-capture request is required.");
			this.allFinished = new CountDownLatch(expectedRequests);
		}

		@Override
		public void didStartMcpRequestHandling(
				@NonNull McpRequestContext context) {
			this.startedContexts.add(Assertions.assertInstanceOf(
					DefaultMcpRequestContext.class, context));
		}

		@Override
		public void didFinishMcpRequestHandling(
				@NonNull McpRequestContext context,
				@NonNull McpRequestOutcome outcome,
				@Nullable McpJsonRpcError error,
				@NonNull Duration duration,
				@NonNull List<@NonNull Throwable> throwables) {
			this.finishedContexts.add(Assertions.assertInstanceOf(
					DefaultMcpRequestContext.class, context));
			if (this.finishedContexts.size() == 1)
				this.firstFinished.countDown();
			this.allFinished.countDown();
		}

		@Override
		public void didReceiveLogEvent(@NonNull LogEvent logEvent) {
			this.logEvents.add(logEvent);
		}

		private void awaitFirstFinished() throws InterruptedException {
			Assertions.assertTrue(this.firstFinished.await(5, TimeUnit.SECONDS),
					"The first trace-capture request did not finish.");
		}

		private void awaitAllFinished() throws InterruptedException {
			Assertions.assertTrue(this.allFinished.await(5, TimeUnit.SECONDS),
					"The trace-capture requests did not all finish.");
		}
	}

	private static final class RecordingLifecycleObserver
			implements LifecycleObserver {
		private final AtomicInteger starts = new AtomicInteger();
		private final AtomicInteger finishes = new AtomicInteger();
		private final CountDownLatch finished = new CountDownLatch(1);
		private final AtomicReference<McpRequestContext> startedContext =
				new AtomicReference<>();
		private final AtomicReference<McpRequestContext> finishedContext =
				new AtomicReference<>();
		private final AtomicReference<McpRequestOutcome> outcome =
				new AtomicReference<>();
		private final AtomicReference<McpJsonRpcError> error =
				new AtomicReference<>();
		private final AtomicReference<Duration> duration = new AtomicReference<>();
		private final AtomicReference<List<Throwable>> finishThrowables =
				new AtomicReference<>();
		private final List<LogEvent> logEvents = new CopyOnWriteArrayList<>();

		@Override
		public void didStartMcpRequestHandling(
				@NonNull McpRequestContext context) {
			this.startedContext.set(context);
			this.starts.incrementAndGet();
		}

		@Override
		public void didFinishMcpRequestHandling(
				@NonNull McpRequestContext context,
				@NonNull McpRequestOutcome outcome,
				@Nullable McpJsonRpcError error,
				@NonNull Duration duration,
				@NonNull List<@NonNull Throwable> throwables) {
			this.finishedContext.set(context);
			this.outcome.set(outcome);
			this.error.set(error);
			this.duration.set(duration);
			this.finishThrowables.set(throwables);
			this.finishes.incrementAndGet();
			this.finished.countDown();
		}

		@Override
		public void didReceiveLogEvent(@NonNull LogEvent logEvent) {
			this.logEvents.add(logEvent);
		}

		private void awaitFinished() throws InterruptedException {
			Assertions.assertTrue(this.finished.await(5, TimeUnit.SECONDS),
					"The MCP request finish callback did not arrive.");
		}
	}

	private static final class RecordingMetricsCollector
			implements MetricsCollector {
		private final List<McpMetricsEvent> events = new CopyOnWriteArrayList<>();
		private final CountDownLatch requestFinished = new CountDownLatch(1);

		@Override
		public void didRecordMcpMetricsEvent(@NonNull McpMetricsEvent event) {
			this.events.add(event);
			if (event instanceof McpMetricsEvent.RequestFinished)
				this.requestFinished.countDown();
		}

		private void awaitRequestFinished() throws InterruptedException {
			Assertions.assertTrue(this.requestFinished.await(5, TimeUnit.SECONDS),
					"The MCP request-finished metric did not arrive.");
		}

		@NonNull
		private List<McpMetricsEvent.RequestStarted> requestStartedEvents() {
			return this.events.stream()
					.filter(McpMetricsEvent.RequestStarted.class::isInstance)
					.map(McpMetricsEvent.RequestStarted.class::cast)
					.toList();
		}

		@NonNull
		private List<McpMetricsEvent.RequestFinished> requestFinishedEvents() {
			return this.events.stream()
					.filter(McpMetricsEvent.RequestFinished.class::isInstance)
					.map(McpMetricsEvent.RequestFinished.class::cast)
					.toList();
		}
	}

	private static final class RecordingDefaultMetricsCollector
			implements MetricsCollector {
		private final DefaultMetricsCollector delegate =
				DefaultMetricsCollector.defaultInstance();
		private final List<McpMetricsEvent> events =
				new CopyOnWriteArrayList<>();
		private final CountDownLatch requestFinishes;
		private final CountDownLatch serverStopped = new CountDownLatch(1);

		private RecordingDefaultMetricsCollector(int expectedRequestFinishes) {
			this.requestFinishes = new CountDownLatch(expectedRequestFinishes);
		}

		private void initialize(@NonNull SokletConfig config) {
			this.delegate.initialize(config);
		}

		@Override
		public void didRecordMcpMetricsEvent(@NonNull McpMetricsEvent event) {
			McpMetricsEvent requiredEvent =
					java.util.Objects.requireNonNull(event);
			this.events.add(requiredEvent);
			this.delegate.didRecordMcpMetricsEvent(requiredEvent);
			if (requiredEvent instanceof McpMetricsEvent.RequestFinished)
				this.requestFinishes.countDown();
			if (requiredEvent instanceof McpMetricsEvent.ServerStopped)
				this.serverStopped.countDown();
		}

		@Override
		@NonNull
		public Optional<Snapshot> snapshot() {
			return this.delegate.snapshot();
		}

		@Override
		@NonNull
		public Optional<String> snapshotText(
				@NonNull SnapshotTextOptions options) {
			return this.delegate.snapshotText(options);
		}

		@Override
		public void reset() {
			this.delegate.reset();
		}

		@NonNull
		private List<McpMetricsEvent> events() {
			return List.copyOf(this.events);
		}

		private void awaitRequestFinishes() throws InterruptedException {
			Assertions.assertTrue(this.requestFinishes.await(5, TimeUnit.SECONDS),
					"The 16 request-finished metric events did not arrive.");
		}

		private void awaitServerStopped() throws InterruptedException {
			Assertions.assertTrue(this.serverStopped.await(5, TimeUnit.SECONDS),
					"The server-stopped metric event did not arrive.");
		}
	}
}
