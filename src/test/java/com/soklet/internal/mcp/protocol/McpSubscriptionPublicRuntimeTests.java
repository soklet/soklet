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

import com.soklet.CorsAuthorizer;
import com.soklet.LifecycleObserver;
import com.soklet.McpAdmissionDecision;
import com.soklet.McpAdmissionIdentity;
import com.soklet.McpCompleteResult;
import com.soklet.McpEndpoint;
import com.soklet.McpEndpointRegistry;
import com.soklet.McpImplementation;
import com.soklet.McpJsonRpcError;
import com.soklet.McpMetricsEvent;
import com.soklet.McpRateLimitDecision;
import com.soklet.McpAdmissionController;
import com.soklet.McpRequestContext;
import com.soklet.McpRequestOutcome;
import com.soklet.McpResourceOutput;
import com.soklet.McpResourceRegistration;
import com.soklet.McpServer;
import com.soklet.McpStreamTerminationReason;
import com.soklet.McpSubscriptionConfig;
import com.soklet.McpSubscriptionEvent;
import com.soklet.McpSubscriptionEventListener;
import com.soklet.McpSubscriptionEventPublisher;
import com.soklet.McpSubscriptionEventRegistration;
import com.soklet.McpSubscriptionNotificationType;
import com.soklet.McpTextResourceContents;
import com.soklet.MetricsCollector;
import com.soklet.ResourceMethodResolver;
import com.soklet.Soklet;
import com.soklet.SokletConfig;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.annotation.concurrent.ThreadSafe;
import java.lang.reflect.Field;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Black-box real-listener coverage for public MCP resource subscriptions.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Timeout(30)
public class McpSubscriptionPublicRuntimeTests {
	private static final String LOOPBACK = "127.0.0.1";
	private static final String MCP_PATH = "/mcp";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final URI RESOURCE_URI =
			URI.create("test://subscription/requested");
	private static final URI SECOND_RESOURCE_URI =
			URI.create("test://subscription/requested-second");

	@Test
	public void acknowledgmentIsFirstAndPreservesExactStringAndIntegerIds()
			throws Exception {
		RecordingPublisher publisher = new RecordingPublisher();
		McpServer server = server(MCP_PATH, publisher,
				McpSubscriptionNotificationType.RESOURCES_LIST_CHANGED,
				McpSubscriptionNotificationType.RESOURCE_UPDATED);

		try {
			server.start();
			int port = boundPort(server);
			assertAcknowledgment(port, "\"subscription-string\"",
					"\"subscription-string\"");
			assertAcknowledgment(port, "37", "37");
			Assertions.assertEquals(1, publisher.subscriptionCount());
		} finally {
			server.stop();
		}
		Assertions.assertEquals(1, publisher.closedSubscriptionCount());
		Assertions.assertEquals(0, publisher.publisherCloseCount());
	}

	@Test
	public void publisherEmitsOnlyRequestedResourceEventsForMatchingUris()
			throws Exception {
		RecordingPublisher publisher = new RecordingPublisher();
		McpServer server = server(MCP_PATH, publisher,
				McpSubscriptionNotificationType.RESOURCES_LIST_CHANGED,
				McpSubscriptionNotificationType.RESOURCE_UPDATED);
		McpChunkedHttpClient client = null;

		try {
			server.start();
			client = listen(boundPort(server), "\"filtered\"",
					"{\"resourcesListChanged\":true,"
							+ "\"resourceSubscriptions\":[\""
							+ RESOURCE_URI + "\"]}");
			assertSseHead(client.readHead());
			Assertions.assertEquals(acknowledgment("\"filtered\"",
					"{\"resourcesListChanged\":true,"
							+ "\"resourceSubscriptions\":[\""
							+ RESOURCE_URI + "\"]}"),
					client.readChunkText());

			publisher.publishResourceUpdated(
					URI.create("test://subscription/not-requested"));
			publisher.publishResourcesListChanged();
			Assertions.assertEquals(resourceListChanged("\"filtered\""),
					client.readChunkText(),
					"An unmatched URI update must not overtake the requested list event.");
			publisher.publishResourceUpdated(RESOURCE_URI);
			Assertions.assertEquals(resourceUpdated("\"filtered\"", RESOURCE_URI),
					client.readChunkText());
		} finally {
			if (client != null)
				client.closeWithReset();
			server.stop();
		}
	}

	@Test
	public void supportedIntersectionOmitsToolsPromptsAndUnconfiguredResources()
			throws Exception {
		RecordingPublisher publisher = new RecordingPublisher();
		McpServer server = server(MCP_PATH, publisher,
				McpSubscriptionNotificationType.RESOURCES_LIST_CHANGED);
		McpChunkedHttpClient client = null;

		try {
			server.start();
			client = listenWithParams(boundPort(server), "\"intersection\"",
					"{\"toolsListChanged\":true,"
							+ "\"promptsListChanged\":true,"
							+ "\"resourcesListChanged\":true,"
							+ "\"resourceSubscriptions\":[\""
							+ RESOURCE_URI + "\"],"
							+ "\"com.example/filterExtension\":{\"enabled\":true}}",
					",\"com.example/paramsExtension\":{\"enabled\":true}");
			assertSseHead(client.readHead());
			Assertions.assertEquals(acknowledgment("\"intersection\"",
					"{\"resourcesListChanged\":true}"), client.readChunkText());

			publisher.publishResourceUpdated(RESOURCE_URI);
			publisher.publishResourcesListChanged();
			Assertions.assertEquals(resourceListChanged("\"intersection\""),
					client.readChunkText());
		} finally {
			if (client != null)
				client.closeWithReset();
			server.stop();
		}
	}

	@Test
	public void malformedRecognizedFilterFieldsFailBeforeAdmission()
			throws Exception {
		RecordingPublisher publisher = new RecordingPublisher();
		AtomicInteger admissionCalls = new AtomicInteger();
		McpAdmissionController admissionController = context -> {
			admissionCalls.incrementAndGet();
			return McpAdmissionController.acceptAllInstance().admit(context);
		};
		McpServer server = server(MCP_PATH, publisher, admissionController,
				McpSubscriptionNotificationType.RESOURCES_LIST_CHANGED,
				McpSubscriptionNotificationType.RESOURCE_UPDATED);

		try {
			server.start();
			int port = boundPort(server);
			List<InvalidSubscriptionParams> invalidCases = List.of(
					new InvalidSubscriptionParams("missing-notifications", ""),
					new InvalidSubscriptionParams("null-notifications",
							",\"notifications\":null"),
					new InvalidSubscriptionParams("nonobject-notifications",
							",\"notifications\":[]"),
					new InvalidSubscriptionParams("null-boolean",
							",\"notifications\":{\"resourcesListChanged\":null}"),
					new InvalidSubscriptionParams("wrong-boolean-type",
							",\"notifications\":{\"resourcesListChanged\":\"true\"}"),
					new InvalidSubscriptionParams("null-uri-list",
							",\"notifications\":{\"resourceSubscriptions\":null}"),
					new InvalidSubscriptionParams("nonarray-uri-list",
							",\"notifications\":{\"resourceSubscriptions\":{}}"),
					new InvalidSubscriptionParams("nonstring-uri-member",
							",\"notifications\":{\"resourceSubscriptions\":[37]}"),
					new InvalidSubscriptionParams("relative-uri",
							",\"notifications\":{\"resourceSubscriptions\":[\"resources/1\"]}"),
					new InvalidSubscriptionParams("nonnormalized-uri",
							",\"notifications\":{\"resourceSubscriptions\":["
									+ "\"https://example.com/a/../resources/1\"]}"));
			for (InvalidSubscriptionParams invalidCase : invalidCases)
				assertInvalidFilter(port, invalidCase);
			Assertions.assertEquals(0, admissionCalls.get(),
					"Structurally invalid filters must not reach application admission.");
		} finally {
			server.stop();
		}
		Assertions.assertEquals(1, publisher.closedSubscriptionCount());
		Assertions.assertEquals(0, publisher.publisherCloseCount());
	}

	@Test
	public void validListenUsesAdmissionAndRequestLimiterOnly()
			throws Exception {
		RecordingPublisher publisher = new RecordingPublisher();
		McpEndpoint endpoint = endpoint(MCP_PATH, publisher,
				McpSubscriptionNotificationType.RESOURCES_LIST_CHANGED);
		AtomicInteger admissionCalls = new AtomicInteger();
		AtomicInteger requestLimiterCalls = new AtomicInteger();
		AtomicInteger toolLimiterCalls = new AtomicInteger();
		AtomicInteger interceptorCalls = new AtomicInteger();
		McpServer allowed = serverBuilder(List.of(endpoint), context -> {
			admissionCalls.incrementAndGet();
			return McpAdmissionController.acceptAllInstance().admit(context);
		})
				.requestRateLimiter(context -> {
					requestLimiterCalls.incrementAndGet();
					return McpRateLimitDecision.allowed();
				})
				.toolRateLimiter(context -> {
					toolLimiterCalls.incrementAndGet();
					return McpRateLimitDecision.allowed();
				})
				.handlerInterceptor((context, continuation) -> {
					interceptorCalls.incrementAndGet();
					return continuation.proceed();
				})
				.build();

		try {
			allowed.start();
			try (McpChunkedHttpClient client = listen(boundPort(allowed),
					"\"policy-allowed\"", "{\"resourcesListChanged\":true}")) {
				assertSseHead(client.readHead());
				Assertions.assertEquals(acknowledgment("\"policy-allowed\"",
						"{\"resourcesListChanged\":true}"),
						client.readChunkText());
				client.closeWithReset();
			}
		} finally {
			allowed.stop();
		}
		Assertions.assertEquals(1, admissionCalls.get());
		Assertions.assertEquals(1, requestLimiterCalls.get());
		Assertions.assertEquals(0, toolLimiterCalls.get());
		Assertions.assertEquals(0, interceptorCalls.get());

		AtomicInteger deniedAdmissionCalls = new AtomicInteger();
		AtomicInteger deniedLimiterCalls = new AtomicInteger();
		McpServer denied = serverBuilder(List.of(endpoint), context -> {
			deniedAdmissionCalls.incrementAndGet();
			return McpAdmissionController.acceptAllInstance().admit(context);
		})
				.requestRateLimiter(context -> {
					deniedLimiterCalls.incrementAndGet();
					return McpRateLimitDecision.denied(Duration.ofMillis(1));
				})
				.build();
		try {
			denied.start();
			try (McpChunkedHttpClient client = listen(boundPort(denied),
					"\"policy-denied\"", "{\"resourcesListChanged\":true}")) {
				McpChunkedHttpClient.HttpResponseHead head = client.readHead();
				Assertions.assertEquals(429, head.status(), head.raw());
				Assertions.assertEquals("1", head.singleHeader("Retry-After"));
				Assertions.assertEquals("application/json",
						head.singleHeader("Content-Type"));
				Assertions.assertEquals("{\"jsonrpc\":\"2.0\","
						+ "\"id\":\"policy-denied\",\"error\":{"
						+ "\"code\":-31999,\"message\":\"Rate limited\"}}",
						client.readFixedBody(head));
			}
		} finally {
			denied.stop();
		}
		Assertions.assertEquals(1, deniedAdmissionCalls.get());
		Assertions.assertEquals(1, deniedLimiterCalls.get());
	}

	@Test
	public void liveSubscriptionDoesNotConsumeTheConfiguredHandlerSlot()
			throws Exception {
		RecordingPublisher publisher = new RecordingPublisher();
		McpEndpoint endpoint = endpoint(MCP_PATH, publisher,
				McpSubscriptionNotificationType.RESOURCES_LIST_CHANGED);
		McpServer server = serverBuilder(List.of(endpoint),
				McpAdmissionController.acceptAllInstance())
				.requestHandlerConcurrency(1)
				.requestHandlerQueueCapacity(1)
				.build();
		McpChunkedHttpClient subscription = null;

		try {
			server.start();
			int port = boundPort(server);
			subscription = listen(port, "\"handler-slot\"",
					"{\"resourcesListChanged\":true}");
			assertSseHead(subscription.readHead());
			Assertions.assertEquals(acknowledgment("\"handler-slot\"",
					"{\"resourcesListChanged\":true}"),
					subscription.readChunkText());

			String body = "{\"jsonrpc\":\"2.0\","
					+ "\"id\":\"read-while-listening\","
					+ "\"method\":\"resources/read\",\"params\":{\"_meta\":{"
					+ "\"io.modelcontextprotocol/protocolVersion\":\""
					+ PROTOCOL_VERSION + "\","
					+ "\"io.modelcontextprotocol/clientCapabilities\":{}},"
					+ "\"uri\":\"" + RESOURCE_URI + "\"}}";
			try (McpChunkedHttpClient read = McpChunkedHttpClient.postMcpMessage(
					port, body, List.of(
							new McpChunkedHttpClient.RequestHeader(
									"MCP-Protocol-Version", PROTOCOL_VERSION),
							new McpChunkedHttpClient.RequestHeader(
									"Mcp-Method", "resources/read"),
							new McpChunkedHttpClient.RequestHeader(
									"Mcp-Name", RESOURCE_URI.toString())))) {
				McpChunkedHttpClient.HttpResponseHead head = read.readHead();
				Assertions.assertEquals(200, head.status(), head.raw());
				String response = read.readFixedBody(head);
				Assertions.assertTrue(response.contains(
						"\"id\":\"read-while-listening\""), response);
				Assertions.assertTrue(response.contains(
						"\"resultType\":\"complete\""), response);
			}
		} finally {
			if (subscription != null)
				subscription.closeWithReset();
			server.stop();
		}
	}

	@Test
	public void configuredPerPrincipalCapRejectsWithoutDisturbingAndRecovers()
			throws Exception {
		RecordingPublisher publisher = new RecordingPublisher();
		McpEndpoint endpoint = endpoint(MCP_PATH, publisher,
				McpSubscriptionNotificationType.RESOURCES_LIST_CHANGED);
		McpServer server = serverBuilder(List.of(endpoint),
				McpAdmissionController.acceptAllInstance())
				.maximumSubscriptionsPerPrincipal(1)
				.build();
		McpChunkedHttpClient first = null;

		try {
			server.start();
			int port = boundPort(server);
			first = listen(port, "\"cap-first\"",
					"{\"resourcesListChanged\":true}");
			assertSseHead(first.readHead());
			Assertions.assertEquals(acknowledgment("\"cap-first\"",
					"{\"resourcesListChanged\":true}"), first.readChunkText());

			assertCapacityRejected(port, "cap-rejected");
			publisher.publishResourcesListChanged();
			Assertions.assertEquals(resourceListChanged("\"cap-first\""),
					first.readChunkText(),
					"A rejected peer must not disturb the admitted stream.");

			first.closeWithReset();
			first = null;
			awaitRecoveredSubscription(port, "cap-recovered");
		} finally {
			if (first != null)
				first.closeWithReset();
			server.stop();
		}
	}

	@Test
	public void sameIdSubscriptionsAreIsolatedAcrossAdmissionPartitionsAndCapRelease()
			throws Exception {
		RecordingPublisher publisher = new RecordingPublisher();
		McpEndpoint endpoint = endpoint(MCP_PATH, publisher,
				McpSubscriptionNotificationType.RESOURCES_LIST_CHANGED);
		McpAdmissionController partitionedAdmission = context -> {
			String tenant = context.getRequest().getHeader("X-Test-Tenant")
					.orElseThrow();
			McpAdmissionIdentity identity = McpAdmissionIdentity
					.withRateLimitPartitionKey("rate-" + tenant)
					.authorizationPartitionKey("authorization-" + tenant)
					.principal(tenant)
					.build();
			return McpAdmissionDecision.accepted(identity);
		};
		McpServer server = serverBuilder(List.of(endpoint), partitionedAdmission)
				.maximumSubscriptionsPerPrincipal(1)
				.build();
		McpChunkedHttpClient alpha = null;
		McpChunkedHttpClient beta = null;
		McpChunkedHttpClient alphaReplacement = null;

		try {
			server.start();
			int port = boundPort(server);
			alpha = listenForTenant(port, "\"shared-subscription\"",
					"alpha");
			beta = listenForTenant(port, "\"shared-subscription\"",
					"beta");
			assertSseHead(alpha.readHead());
			assertSseHead(beta.readHead());
			String expectedAcknowledgment = acknowledgment(
					"\"shared-subscription\"",
					"{\"resourcesListChanged\":true}");
			Assertions.assertEquals(expectedAcknowledgment,
					alpha.readChunkText());
			Assertions.assertEquals(expectedAcknowledgment,
					beta.readChunkText());

			publisher.publishResourcesListChanged();
			String expectedEvent = resourceListChanged(
					"\"shared-subscription\"");
			Assertions.assertEquals(expectedEvent, alpha.readChunkText());
			Assertions.assertEquals(expectedEvent, beta.readChunkText());

			alpha.closeWithReset();
			alpha = null;
			alphaReplacement = awaitRecoveredTenantSubscription(
					port, "alpha-replacement", "alpha");
			publisher.publishResourcesListChanged();
			Assertions.assertEquals(expectedEvent, beta.readChunkText(),
					"Closing the same-ID alpha subscription must not affect beta.");
			Assertions.assertEquals(resourceListChanged(
					"\"alpha-replacement\""),
					alphaReplacement.readChunkText(),
					"Only alpha's partition slot should be released and reused.");
		} finally {
			if (alpha != null)
				alpha.closeWithReset();
			if (beta != null)
				beta.closeWithReset();
			if (alphaReplacement != null)
				alphaReplacement.closeWithReset();
			server.stop();
		}
	}

	@Test
	public void publisherIdentityIsGroupedPerServerAndSharedAcrossServers()
			throws Exception {
		RecordingPublisher groupedPublisher = new RecordingPublisher();
		McpEndpoint firstEndpoint = endpoint("/first", groupedPublisher,
				McpSubscriptionNotificationType.RESOURCES_LIST_CHANGED);
		McpEndpoint secondEndpoint = endpoint("/second", groupedPublisher,
				McpSubscriptionNotificationType.RESOURCE_UPDATED);
		McpServer groupedServer = server(List.of(firstEndpoint, secondEndpoint));
		try {
			groupedServer.start();
			Assertions.assertEquals(1, groupedPublisher.subscriptionCount(),
					"One server must register once for one publisher identity.");
		} finally {
			groupedServer.stop();
		}
		Assertions.assertEquals(1,
				groupedPublisher.closedSubscriptionCount());
		Assertions.assertEquals(0, groupedPublisher.publisherCloseCount());

		RecordingPublisher distributedPublisher = new RecordingPublisher();
		McpServer firstServer = server(MCP_PATH, distributedPublisher,
				McpSubscriptionNotificationType.RESOURCES_LIST_CHANGED,
				McpSubscriptionNotificationType.RESOURCE_UPDATED);
		McpServer secondServer = server(MCP_PATH, distributedPublisher,
				McpSubscriptionNotificationType.RESOURCES_LIST_CHANGED,
				McpSubscriptionNotificationType.RESOURCE_UPDATED);
		McpChunkedHttpClient first = null;
		McpChunkedHttpClient second = null;
		try {
			firstServer.start();
			secondServer.start();
			Assertions.assertEquals(2, distributedPublisher.subscriptionCount());
			first = listen(boundPort(firstServer), "\"first-server\"",
					"{\"resourcesListChanged\":true,"
							+ "\"resourceSubscriptions\":[\""
							+ RESOURCE_URI + "\"]}");
			second = listen(boundPort(secondServer), "\"second-server\"",
					"{\"resourcesListChanged\":true,"
							+ "\"resourceSubscriptions\":[\""
							+ RESOURCE_URI + "\"]}");
			assertSseHead(first.readHead());
			assertSseHead(second.readHead());
			Assertions.assertEquals(acknowledgment("\"first-server\"",
					"{\"resourcesListChanged\":true,"
							+ "\"resourceSubscriptions\":[\""
							+ RESOURCE_URI + "\"]}"), first.readChunkText());
			Assertions.assertEquals(acknowledgment("\"second-server\"",
					"{\"resourcesListChanged\":true,"
							+ "\"resourceSubscriptions\":[\""
							+ RESOURCE_URI + "\"]}"), second.readChunkText());

			distributedPublisher.publishResourcesListChanged();
			distributedPublisher.publishResourceUpdated(RESOURCE_URI);
			Assertions.assertEquals(resourceListChanged("\"first-server\""),
					first.readChunkText());
			Assertions.assertEquals(resourceUpdated("\"first-server\"", RESOURCE_URI),
					first.readChunkText());
			Assertions.assertEquals(resourceListChanged("\"second-server\""),
					second.readChunkText());
			Assertions.assertEquals(resourceUpdated("\"second-server\"", RESOURCE_URI),
					second.readChunkText());
		} finally {
			if (first != null)
				first.closeWithReset();
			if (second != null)
				second.closeWithReset();
			firstServer.stop();
			secondServer.stop();
		}
		Assertions.assertEquals(2,
				distributedPublisher.closedSubscriptionCount());
		Assertions.assertEquals(0, distributedPublisher.publisherCloseCount());
	}

	@Test
	public void gracefulHttpShutdownEndsWithOnlyTheTerminalCompleteResult()
			throws Exception {
		RecordingPublisher publisher = new RecordingPublisher();
		McpEndpoint endpoint = endpoint(MCP_PATH, publisher,
				McpSubscriptionNotificationType.RESOURCES_LIST_CHANGED);
		McpServer server = serverBuilder(List.of(endpoint),
				McpAdmissionController.acceptAllInstance())
				.maximumSubscriptionsPerPrincipal(1)
				.build();
		McpChunkedHttpClient client = null;
		Thread stopThread = null;

		try {
			server.start();
			client = listen(boundPort(server), "\"shutdown\"",
					"{\"resourcesListChanged\":true}");
			assertSseHead(client.readHead());
			Assertions.assertEquals(acknowledgment("\"shutdown\"",
					"{\"resourcesListChanged\":true}"), client.readChunkText());

			stopThread = new Thread(server::stop, "mcp-subscription-test-stop");
			stopThread.start();
			Assertions.assertEquals(terminal("\"shutdown\""),
					client.readChunkText(),
					"HTTP teardown must not prepend a server-sent cancellation notification.");
			Assertions.assertNull(client.readChunk());
			stopThread.join(5_000L);
			Assertions.assertFalse(stopThread.isAlive());
			Assertions.assertFalse(server.isStarted());

			server.start();
			Assertions.assertEquals(2, publisher.subscriptionCount(),
					"Restart must create exactly one fresh publisher registration.");
			try (McpChunkedHttpClient restarted = listen(boundPort(server),
					"\"shutdown\"", "{\"resourcesListChanged\":true}")) {
				assertSseHead(restarted.readHead());
				Assertions.assertEquals(acknowledgment("\"shutdown\"",
						"{\"resourcesListChanged\":true}"),
						restarted.readChunkText(),
						"Restart must release the old subscription partition state.");
				assertCapacityRejected(boundPort(server),
						"restart-full-cap");
				restarted.closeWithReset();
			}
			server.stop();
		} finally {
			if (client != null)
				client.close();
			server.stop();
			if (stopThread != null && stopThread.isAlive())
				stopThread.join(5_000L);
		}
		Assertions.assertEquals(2, publisher.closedSubscriptionCount());
		Assertions.assertEquals(0, publisher.publisherCloseCount());
	}

	@Test
	public void configuredMaximumDurationPublishesExactLifecycleAndMetrics()
			throws Exception {
		RecordingPublisher publisher = new RecordingPublisher();
		McpEndpoint endpoint = endpoint(MCP_PATH, publisher,
				McpSubscriptionNotificationType.RESOURCES_LIST_CHANGED);
		SubscriptionObservations observations = new SubscriptionObservations();
		McpServer server = serverBuilder(List.of(endpoint),
				McpAdmissionController.acceptAllInstance())
				.writeTimeout(Duration.ofSeconds(2))
				.keepAliveInterval(Duration.ofMillis(100))
				.maximumSubscriptionDuration(Duration.ofSeconds(1))
				.build();
		Soklet soklet = managedSoklet(server, observations);

		try {
			soklet.start();
			try (McpChunkedHttpClient client = listen(boundPort(server),
					"\"duration\"", "{\"resourcesListChanged\":true}")) {
				assertSseHead(client.readHead());
				Assertions.assertEquals(acknowledgment("\"duration\"",
						"{\"resourcesListChanged\":true}"),
						client.readChunkText());

				String expectedTerminal = terminal("\"duration\"");
				while (true) {
					String chunk = client.readChunkText();
					if (": keepalive\n\n".equals(chunk))
						continue;
					Assertions.assertEquals(expectedTerminal, chunk);
					break;
				}
				Assertions.assertNull(client.readChunk());
			}

			observations.awaitFinish();
			observations.assertRequest(McpRequestOutcome.COMPLETE,
					"duration");
			observations.assertStreamMetrics(
					McpStreamTerminationReason.DEADLINE_EXCEEDED, null);
		} finally {
			soklet.stop();
		}
	}

	@Test
	public void clientDisconnectReleasesStateAndPublishesExactlyOnce()
			throws Exception {
		RecordingPublisher publisher = new RecordingPublisher();
		McpEndpoint endpoint = endpoint(MCP_PATH, publisher,
				McpSubscriptionNotificationType.RESOURCES_LIST_CHANGED);
		SubscriptionObservations observations = new SubscriptionObservations();
		McpServer server = serverBuilder(List.of(endpoint),
				McpAdmissionController.acceptAllInstance()).build();
		Soklet soklet = managedSoklet(server, observations);
		McpChunkedHttpClient client = null;

		try {
			soklet.start();
			client = listen(boundPort(server), "\"disconnect\"",
					"{\"resourcesListChanged\":true}");
			assertSseHead(client.readHead());
			Assertions.assertEquals(acknowledgment("\"disconnect\"",
					"{\"resourcesListChanged\":true}"),
					client.readChunkText());
			client.closeWithReset();
			client = null;

			observations.awaitFinish();
			observations.assertRequest(McpRequestOutcome.CLIENT_DISCONNECTED,
					"disconnect");
			observations.assertStreamMetrics(
					McpStreamTerminationReason.CLIENT_DISCONNECTED, false);
			awaitRecoveredSubscription(boundPort(server), "disconnect-recovered");
			Assertions.assertEquals(1, observations.finishCount());
		} finally {
			if (client != null)
				client.close();
			soklet.stop();
		}
	}

	@Test
	public void keepAliveAcceptanceSharesStreamTransitionWithCloseObservation()
			throws Exception {
		RecordingPublisher publisher = new RecordingPublisher();
		McpEndpoint endpoint = endpoint(MCP_PATH, publisher,
				McpSubscriptionNotificationType.RESOURCES_LIST_CHANGED);
		SubscriptionObservations observations = new SubscriptionObservations();
		McpServer server = serverBuilder(List.of(endpoint),
				McpAdmissionController.acceptAllInstance())
				.writeTimeout(Duration.ofHours(2))
				.keepAliveInterval(Duration.ofHours(1))
				.build();
		Soklet soklet = managedSoklet(server, observations);
		ExecutorService readerExecutor = Executors.newSingleThreadExecutor();
		McpChunkedHttpClient client = null;
		Future<String> keepAliveRead = null;

		try {
			soklet.start();
			client = listen(boundPort(server), "\"keepalive-transition\"",
					"{\"resourcesListChanged\":true}");
			assertSseHead(client.readHead());
			Assertions.assertEquals(acknowledgment("\"keepalive-transition\"",
					"{\"resourcesListChanged\":true}"),
					client.readChunkText());

			Object runtime = runtime(server);
			Object requestControl = soleRequestControl(runtime);
			Object transitionLock = field(requestControl,
					"streamObservationTransitionLock");
			Object applicationExecution = field(runtime, "applicationExecution");
			Thread timerThread = (Thread) field(applicationExecution,
					"timerThread");
			McpApplicationClock clock = (McpApplicationClock) field(runtime,
					"applicationClock");
			McpChunkedHttpClient activeClient = client;
			keepAliveRead = readerExecutor.submit(activeClient::readChunkText);

			synchronized (transitionLock) {
				setLongField(requestControl, "nextKeepAliveNanos",
						clock.nanoTime());
				LockSupport.unpark(timerThread);
				awaitBlocked(timerThread);
				Assertions.assertEquals(0L, observations.keepAliveCount(),
						"Keep-alive observation crossed the held stream-transition boundary.");
				Assertions.assertFalse(keepAliveRead.isDone(),
						"Wire keep-alive acceptance crossed the held stream-transition boundary.");
			}

			Assertions.assertEquals(": keepalive\n\n",
					keepAliveRead.get(5, TimeUnit.SECONDS));
			client.closeWithReset();
			client = null;
			observations.awaitFinish();
			observations.assertRequest(McpRequestOutcome.CLIENT_DISCONNECTED,
					"keepalive-transition");
			observations.assertStreamMetrics(
					McpStreamTerminationReason.CLIENT_DISCONNECTED, true);
		} finally {
			if (keepAliveRead != null)
				keepAliveRead.cancel(true);
			if (client != null)
				client.close();
			soklet.stop();
			readerExecutor.shutdownNow();
			Assertions.assertTrue(readerExecutor.awaitTermination(
					5, TimeUnit.SECONDS));
		}
	}

	@Test
	public void publisherVisibilityBeginsAfterAcknowledgmentActivation()
			throws Exception {
		RecordingPublisher publisher = new RecordingPublisher();
		McpEndpoint endpoint = endpoint(MCP_PATH, publisher,
				List.of(RESOURCE_URI, SECOND_RESOURCE_URI),
				McpSubscriptionNotificationType.RESOURCES_LIST_CHANGED,
				McpSubscriptionNotificationType.RESOURCE_UPDATED);
		McpServer server = serverBuilder(List.of(endpoint),
				McpAdmissionController.acceptAllInstance())
				.streamQueueCapacity(4)
				.build();
		Soklet soklet = managedSoklet(server,
				LifecycleObserver.defaultInstance(),
				MetricsCollector.disabledInstance());
		McpChunkedHttpClient client = null;

		try {
			soklet.start();
			client = listen(boundPort(server), "\"ack-race\"",
					"{\"resourceSubscriptions\":[\"" + RESOURCE_URI
							+ "\",\"" + SECOND_RESOURCE_URI + "\"]}");
			assertSseHead(client.readHead());
			Assertions.assertEquals(acknowledgment("\"ack-race\"",
					"{\"resourceSubscriptions\":[\"" + RESOURCE_URI
							+ "\",\"" + SECOND_RESOURCE_URI + "\"]}"),
					client.readChunkText());
			publisher.publishResourceUpdated(SECOND_RESOURCE_URI);
			Assertions.assertEquals(resourceUpdated(
					"\"ack-race\"", SECOND_RESOURCE_URI),
					client.readChunkText());
		} finally {
			if (client != null)
				client.closeWithReset();
			soklet.stop();
		}
	}

	@Test
	public void configuredQueueContainsBackpressureAndReleasesTheFullCap()
			throws Exception {
		RecordingPublisher publisher = new RecordingPublisher();
		URI firstLargeUri = URI.create("test://subscription/backpressure/first/"
				+ "a".repeat(900_000));
		URI secondLargeUri = URI.create("test://subscription/backpressure/second/"
				+ "b".repeat(900_000));
		URI thirdLargeUri = URI.create("test://subscription/backpressure/third/"
				+ "c".repeat(900_000));
		URI fourthLargeUri = URI.create("test://subscription/backpressure/fourth/"
				+ "d".repeat(900_000));
		List<URI> largeUris = List.of(firstLargeUri, secondLargeUri,
				thirdLargeUri, fourthLargeUri);
		String largeSubscriptionFilter = "{\"resourceSubscriptions\":[\""
				+ firstLargeUri + "\",\"" + secondLargeUri + "\",\""
				+ thirdLargeUri + "\",\"" + fourthLargeUri + "\"]}";
		McpEndpoint endpoint = endpoint(MCP_PATH, publisher,
				largeUris,
				McpSubscriptionNotificationType.RESOURCES_LIST_CHANGED,
				McpSubscriptionNotificationType.RESOURCE_UPDATED);
		McpServer server = serverBuilder(List.of(endpoint),
				McpAdmissionController.acceptAllInstance())
				.streamQueueCapacity(1)
				.maximumSubscriptionsPerPrincipal(1)
				.build();
		McpChunkedHttpClient backpressured = null;

		try {
			server.start();
			int port = boundPort(server);
			backpressured = listen(port, "\"backpressured\"",
					largeSubscriptionFilter, 1_024);
			assertSseHead(backpressured.readHead());
			Assertions.assertEquals(acknowledgment("\"backpressured\"",
					largeSubscriptionFilter),
					backpressured.readChunkText());

			for (URI largeUri : largeUris)
				publisher.publishResourceUpdated(largeUri);
			awaitRecoveredSubscription(port, "fast-after-backpressure");
		} finally {
			if (backpressured != null)
				backpressured.closeWithReset();
			server.stop();
		}
	}

	private static void assertAcknowledgment(int port, String idJson,
			String expectedIdJson) throws Exception {
		McpChunkedHttpClient client = listen(port, idJson,
				"{\"toolsListChanged\":true,"
						+ "\"promptsListChanged\":true,"
						+ "\"resourcesListChanged\":true,"
						+ "\"resourceSubscriptions\":[\""
						+ RESOURCE_URI + "\"]}");
		try {
			assertSseHead(client.readHead());
			Assertions.assertEquals(acknowledgment(expectedIdJson,
					"{\"resourcesListChanged\":true,"
							+ "\"resourceSubscriptions\":[\""
							+ RESOURCE_URI + "\"]}"), client.readChunkText());
		} finally {
			client.closeWithReset();
		}
	}

	private static McpChunkedHttpClient listen(int port, String idJson,
			String notificationsJson) throws Exception {
		return listen(port, idJson, notificationsJson, 0);
	}

	private static McpChunkedHttpClient listen(int port, String idJson,
			String notificationsJson, int receiveBufferBytes) throws Exception {
		return listenWithParams(port, idJson, notificationsJson, "",
				receiveBufferBytes);
	}

	private static McpChunkedHttpClient listenWithParams(int port,
			String idJson, String notificationsJson,
			String additionalParamsJson) throws Exception {
		return listenWithParams(port, idJson, notificationsJson,
				additionalParamsJson, 0);
	}

	private static McpChunkedHttpClient listenWithParams(int port,
			String idJson, String notificationsJson,
			String additionalParamsJson, int receiveBufferBytes) throws Exception {
		return listenWithParams(port, idJson, notificationsJson,
				additionalParamsJson, receiveBufferBytes, List.of());
	}

	private static McpChunkedHttpClient listenForTenant(int port,
			String idJson, String tenant) throws Exception {
		return listenWithParams(port, idJson,
				"{\"resourcesListChanged\":true}", "", 0,
				List.of(new McpChunkedHttpClient.RequestHeader(
						"X-Test-Tenant", tenant)));
	}

	private static McpChunkedHttpClient listenWithParams(int port,
			String idJson, String notificationsJson,
			String additionalParamsJson, int receiveBufferBytes,
			List<McpChunkedHttpClient.RequestHeader> additionalHeaders)
			throws Exception {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":" + idJson
				+ ",\"method\":\"subscriptions/listen\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}},"
				+ "\"notifications\":" + notificationsJson
				+ additionalParamsJson + "}}";
		List<McpChunkedHttpClient.RequestHeader> headers = new ArrayList<>(List.of(
				new McpChunkedHttpClient.RequestHeader(
						"MCP-Protocol-Version", PROTOCOL_VERSION),
				new McpChunkedHttpClient.RequestHeader(
						"Mcp-Method", "subscriptions/listen")));
		headers.addAll(additionalHeaders);
		return McpChunkedHttpClient.postMcpMessage(port, body, headers,
				receiveBufferBytes);
	}

	private static void assertInvalidFilter(int port,
			InvalidSubscriptionParams invalidCase) throws Exception {
		String id = invalidCase.id();
		try (McpChunkedHttpClient client = listenWithRawParams(port,
				"\"" + id + "\"", invalidCase.paramsJson())) {
			McpChunkedHttpClient.HttpResponseHead head = client.readHead();
			Assertions.assertEquals(400, head.status(), head.raw());
			Assertions.assertEquals("application/json",
					head.singleHeader("Content-Type"));
			Assertions.assertEquals("no-store",
					head.singleHeader("Cache-Control"));
			Assertions.assertEquals("{\"jsonrpc\":\"2.0\",\"id\":\""
					+ id + "\",\"error\":{\"code\":-32602,"
					+ "\"message\":\"Invalid params\"}}",
					client.readFixedBody(head));
		}
	}

	private static McpChunkedHttpClient listenWithRawParams(int port,
			String idJson, String paramsJson) throws Exception {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":" + idJson
				+ ",\"method\":\"subscriptions/listen\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}}"
				+ paramsJson + "}}";
		return McpChunkedHttpClient.postMcpMessage(port, body, List.of(
				new McpChunkedHttpClient.RequestHeader(
						"MCP-Protocol-Version", PROTOCOL_VERSION),
				new McpChunkedHttpClient.RequestHeader(
						"Mcp-Method", "subscriptions/listen")));
	}

	private static void assertCapacityRejected(int port, String id)
			throws Exception {
		try (McpChunkedHttpClient client = listen(port, "\"" + id + "\"",
				"{\"resourcesListChanged\":true}")) {
			McpChunkedHttpClient.HttpResponseHead head = client.readHead();
			Assertions.assertEquals(503, head.status(), head.raw());
			Assertions.assertEquals("application/json",
					head.singleHeader("Content-Type"));
			Assertions.assertEquals("{\"jsonrpc\":\"2.0\",\"id\":\""
					+ id + "\",\"error\":{\"code\":-32603,"
					+ "\"message\":\"Internal error\"}}",
					client.readFixedBody(head));
		}
	}

	private static void awaitRecoveredSubscription(int port, String id)
			throws Exception {
		long deadline = System.nanoTime() + 5_000_000_000L;
		while (true) {
			McpChunkedHttpClient client = listen(port, "\"" + id + "\"",
					"{\"resourcesListChanged\":true}");
			McpChunkedHttpClient.HttpResponseHead head = client.readHead();
			if (head.status() == 200) {
				try {
					assertSseHead(head);
					Assertions.assertEquals(acknowledgment("\"" + id + "\"",
							"{\"resourcesListChanged\":true}"),
							client.readChunkText());
				} finally {
					client.closeWithReset();
				}
				return;
			}

			try (client) {
				Assertions.assertEquals(503, head.status(), head.raw());
				client.readFixedBody(head);
			}
			if (System.nanoTime() - deadline >= 0L)
				throw new AssertionError(
						"The subscription cap did not recover after stream closure.");
			Thread.sleep(10L);
		}
	}

	private static McpChunkedHttpClient awaitRecoveredTenantSubscription(
			int port, String id, String tenant) throws Exception {
		long deadline = System.nanoTime() + 5_000_000_000L;
		while (true) {
			McpChunkedHttpClient client = listenForTenant(port,
					"\"" + id + "\"", tenant);
			McpChunkedHttpClient.HttpResponseHead head = client.readHead();
			if (head.status() == 200) {
				assertSseHead(head);
				Assertions.assertEquals(acknowledgment("\"" + id + "\"",
						"{\"resourcesListChanged\":true}"),
						client.readChunkText());
				return client;
			}

			try (client) {
				Assertions.assertEquals(503, head.status(), head.raw());
				client.readFixedBody(head);
			}
			if (System.nanoTime() - deadline >= 0L)
				throw new AssertionError(
						"The tenant subscription cap did not recover after stream closure.");
			Thread.sleep(10L);
		}
	}

	private static String acknowledgment(String subscriptionIdJson,
			String notificationsJson) {
		return sse("{\"jsonrpc\":\"2.0\","
				+ "\"method\":\"notifications/subscriptions/acknowledged\","
				+ "\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/subscriptionId\":"
				+ subscriptionIdJson + "},\"notifications\":"
				+ notificationsJson + "}}");
	}

	private static String resourceListChanged(String subscriptionIdJson) {
		return sse("{\"jsonrpc\":\"2.0\","
				+ "\"method\":\"notifications/resources/list_changed\","
				+ "\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/subscriptionId\":"
				+ subscriptionIdJson + "}}}");
	}

	private static String resourceUpdated(String subscriptionIdJson, URI uri) {
		return sse("{\"jsonrpc\":\"2.0\","
				+ "\"method\":\"notifications/resources/updated\","
				+ "\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/subscriptionId\":"
				+ subscriptionIdJson + "},\"uri\":\"" + uri + "\"}}");
	}

	private static String terminal(String subscriptionIdJson) {
		return sse("{\"jsonrpc\":\"2.0\",\"id\":" + subscriptionIdJson
				+ ",\"result\":{\"resultType\":\"complete\",\"_meta\":{"
				+ "\"io.modelcontextprotocol/subscriptionId\":"
				+ subscriptionIdJson + ","
				+ "\"io.modelcontextprotocol/serverInfo\":{"
				+ "\"name\":\"subscription-public-runtime-test\","
				+ "\"version\":\"3.6.0-SNAPSHOT\"}}}}");
	}

	private static String sse(String json) {
		return "data: " + json + "\n\n";
	}

	private static McpServer server(String path,
			McpSubscriptionEventPublisher publisher,
			McpSubscriptionNotificationType first,
			McpSubscriptionNotificationType... remaining) {
		return server(path, publisher,
				McpAdmissionController.acceptAllInstance(), first, remaining);
	}

	private static McpServer server(String path,
			McpSubscriptionEventPublisher publisher,
			McpAdmissionController admissionController,
			McpSubscriptionNotificationType first,
			McpSubscriptionNotificationType... remaining) {
		return server(List.of(endpoint(path, publisher, first, remaining)),
				admissionController);
	}

	private static McpServer server(List<McpEndpoint> endpoints) {
		return server(endpoints, McpAdmissionController.acceptAllInstance());
	}

	private static McpServer server(List<McpEndpoint> endpoints,
			McpAdmissionController admissionController) {
		return serverBuilder(endpoints, admissionController).build();
	}

	private static McpServer.Builder serverBuilder(List<McpEndpoint> endpoints,
			McpAdmissionController admissionController) {
		return McpServer.withPort(0)
				.host(LOOPBACK)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(endpoints))
				.admissionController(admissionController)
				.requestRateLimiter(context ->
						McpRateLimitDecision.allowed())
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(LOOPBACK));
	}

	private static McpEndpoint endpoint(String path,
			McpSubscriptionEventPublisher publisher,
			McpSubscriptionNotificationType first,
			McpSubscriptionNotificationType... remaining) {
		return endpoint(path, publisher, List.of(RESOURCE_URI), first, remaining);
	}

	private static McpEndpoint endpoint(String path,
			McpSubscriptionEventPublisher publisher,
			List<URI> resourceUris,
			McpSubscriptionNotificationType first,
			McpSubscriptionNotificationType... remaining) {
		EnumSet<McpSubscriptionNotificationType> notificationTypes =
				EnumSet.of(first, remaining);
		McpSubscriptionConfig subscriptions = McpSubscriptionConfig
				.withEventPublisher(publisher)
				.notificationTypes(notificationTypes)
				.build();
		McpEndpoint.Builder builder = McpEndpoint.withPath(path)
				.serverInformation(McpImplementation.withNameAndVersion(
						"subscription-public-runtime-test",
						"3.6.0-SNAPSHOT").build())
				.subscriptions(subscriptions);
		for (URI resourceUri : resourceUris) {
			builder.resource(McpResourceRegistration
					.withUriAndName(resourceUri,
							"Subscription test resource")
					.handler((request, read, features) ->
							McpCompleteResult.fromResourceOutput(
									McpResourceOutput.builder()
											.content(McpTextResourceContents
													.withUriAndText(read.getUri(), "test")
													.build())
											.build()))
					.build());
		}
		return builder.build();
	}

	private static int boundPort(McpServer server) {
		return server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
	}

	private static Soklet managedSoklet(McpServer server,
			SubscriptionObservations observations) {
		return managedSoklet(server, observations,
				observations.metricsCollector());
	}

	private static Soklet managedSoklet(McpServer server,
			LifecycleObserver lifecycleObserver,
			MetricsCollector metricsCollector) {
		return Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(
						ResourceMethodResolver.fromMethods(Set.of()))
				.lifecycleObservers(List.of(lifecycleObserver))
				.metricsCollector(metricsCollector)
				.build());
	}

	private static void assertSseHead(
			McpChunkedHttpClient.HttpResponseHead head) {
		Assertions.assertEquals(200, head.status(), head.raw());
		Assertions.assertEquals("text/event-stream",
				head.singleHeader("Content-Type"));
		Assertions.assertEquals("no-store",
				head.singleHeader("Cache-Control"));
		Assertions.assertEquals("no",
				head.singleHeader("X-Accel-Buffering"));
		Assertions.assertEquals("chunked",
				head.singleHeader("Transfer-Encoding"));
		Assertions.assertFalse(head.hasHeader("Content-Length"));
	}

	@NonNull
	private static Object runtime(@NonNull McpServer server) throws Exception {
		return field(field(server, "runtimeBridge"), "runtime");
	}

	@NonNull
	private static Object soleRequestControl(@NonNull Object runtime)
			throws Exception {
		Object value = field(runtime, "requestControls");
		Assertions.assertInstanceOf(Map.class, value);
		Map<?, ?> controls = (Map<?, ?>) value;
		synchronized (controls) {
			Assertions.assertEquals(1, controls.size(),
					"Expected exactly one active subscription request control.");
			return controls.values().iterator().next();
		}
	}

	@NonNull
	private static Object field(@NonNull Object target, @NonNull String name)
			throws Exception {
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(target);
	}

	private static void setLongField(@NonNull Object target,
			@NonNull String name, long value) throws Exception {
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		field.setLong(target, value);
	}

	private static void awaitBlocked(@NonNull Thread thread) {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (thread.getState() != Thread.State.BLOCKED
				&& System.nanoTime() - deadline < 0L)
			Thread.onSpinWait();
		Assertions.assertEquals(Thread.State.BLOCKED, thread.getState(),
				"The timer did not contend for the stream-transition boundary.");
	}

	private record InvalidSubscriptionParams(@NonNull String id,
			@NonNull String paramsJson) {
		private InvalidSubscriptionParams {
			Assertions.assertFalse(id.isBlank());
		}
	}

	@ThreadSafe
	private static final class RecordingPublisher
			implements McpSubscriptionEventPublisher, AutoCloseable {
		@NonNull
		private final CopyOnWriteArrayList<@NonNull Registration> registrations =
				new CopyOnWriteArrayList<>();
		@NonNull
		private final AtomicInteger subscriptionCount = new AtomicInteger();
		@NonNull
		private final AtomicInteger closedSubscriptionCount = new AtomicInteger();
		@NonNull
		private final AtomicInteger publisherCloseCount = new AtomicInteger();

		@Override
		@NonNull
		public McpSubscriptionEventRegistration subscribe(
				@NonNull McpSubscriptionEventListener listener) {
			Registration registration = new Registration(listener);
			this.registrations.add(registration);
			this.subscriptionCount.incrementAndGet();
			return registration;
		}

		@Override
		public void publish(@NonNull McpSubscriptionEvent event) {
			for (Registration registration : this.registrations)
				registration.deliver(event);
		}

		@Override
		public void close() {
			this.publisherCloseCount.incrementAndGet();
		}

		private int subscriptionCount() {
			return this.subscriptionCount.get();
		}

		private int closedSubscriptionCount() {
			return this.closedSubscriptionCount.get();
		}

		private int publisherCloseCount() {
			return this.publisherCloseCount.get();
		}

		@ThreadSafe
		private final class Registration
				implements McpSubscriptionEventRegistration {
			@NonNull
			private final McpSubscriptionEventListener listener;
			@NonNull
			private final AtomicBoolean open = new AtomicBoolean(true);

			private Registration(
					@NonNull McpSubscriptionEventListener listener) {
				this.listener = listener;
			}

			private void deliver(@NonNull McpSubscriptionEvent event) {
				if (this.open.get())
					this.listener.onEvent(event);
			}

			@Override
			public void close() {
				if (this.open.compareAndSet(true, false)) {
					registrations.remove(this);
					closedSubscriptionCount.incrementAndGet();
				}
			}
		}
	}

	@ThreadSafe
	private static final class SubscriptionObservations
			implements LifecycleObserver {
		@NonNull
		private final List<@NonNull McpMetricsEvent> metrics =
				new CopyOnWriteArrayList<>();
		@NonNull
		private final AtomicInteger activeMetricCallbacks = new AtomicInteger();
		@NonNull
		private final AtomicInteger maximumConcurrentMetricCallbacks =
				new AtomicInteger();
		@NonNull
		private final MetricsCollector metricsCollector = new MetricsCollector() {
			@Override
			public void didRecordMcpMetricsEvent(
					@NonNull McpMetricsEvent event) {
				int active = activeMetricCallbacks.incrementAndGet();
				maximumConcurrentMetricCallbacks.accumulateAndGet(active, Math::max);
				try {
					metrics.add(event);
					if (event instanceof McpMetricsEvent.RequestFinished)
						requestFinishedMetric.countDown();
				} finally {
					activeMetricCallbacks.decrementAndGet();
				}
			}
		};
		@NonNull
		private final AtomicInteger starts = new AtomicInteger();
		@NonNull
		private final AtomicInteger finishes = new AtomicInteger();
		@NonNull
		private final CountDownLatch finished = new CountDownLatch(1);
		@NonNull
		private final CountDownLatch requestFinishedMetric =
				new CountDownLatch(1);
		@NonNull
		private final AtomicReference<@Nullable McpRequestContext> startedContext =
				new AtomicReference<>();
		@NonNull
		private final AtomicReference<@Nullable McpRequestContext> finishedContext =
				new AtomicReference<>();
		@NonNull
		private final AtomicReference<@Nullable McpRequestOutcome> outcome =
				new AtomicReference<>();
		@NonNull
		private final AtomicReference<@Nullable McpJsonRpcError> error =
				new AtomicReference<>();

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
			this.finishes.incrementAndGet();
			this.finished.countDown();
		}

		@NonNull
		private MetricsCollector metricsCollector() {
			return this.metricsCollector;
		}

		private void awaitFinish() throws InterruptedException {
			Assertions.assertTrue(this.finished.await(5, TimeUnit.SECONDS),
					"The subscription request finish observation did not arrive.");
		}

		private int finishCount() {
			return this.finishes.get();
		}

		private long keepAliveCount() {
			return this.metrics.stream()
					.filter(McpMetricsEvent.KeepAliveEmitted.class::isInstance)
					.count();
		}

		private void assertRequest(@NonNull McpRequestOutcome expectedOutcome,
				@NonNull String expectedRequestId) {
			Assertions.assertEquals(1, this.starts.get());
			Assertions.assertEquals(1, this.finishes.get());
			Assertions.assertSame(this.startedContext.get(),
					this.finishedContext.get());
			McpRequestContext context = this.startedContext.get();
			Assertions.assertNotNull(context);
			Assertions.assertEquals("subscriptions/listen",
					context.getJsonRpcMethod());
			Assertions.assertEquals(expectedRequestId,
					context.getRequestId().orElseThrow().asString()
							.orElseThrow());
			Assertions.assertEquals(expectedOutcome, this.outcome.get());
			Assertions.assertNull(this.error.get());
		}

		private void assertStreamMetrics(
				@NonNull McpStreamTerminationReason expectedReason,
				@Nullable Boolean expectKeepAlive) throws InterruptedException {
			Assertions.assertTrue(this.requestFinishedMetric.await(
					5, TimeUnit.SECONDS),
					"The request-finished metric did not arrive.");
			List<McpMetricsEvent> events = List.copyOf(this.metrics);
			List<McpMetricsEvent> withoutKeepAlives = events.stream()
					.filter(event -> !(event instanceof McpMetricsEvent.KeepAliveEmitted))
					.toList();
			Assertions.assertEquals(List.of(
					McpMetricsEvent.ServerStarted.class,
					McpMetricsEvent.ConnectionAccepted.class,
					McpMetricsEvent.RequestAccepted.class,
					McpMetricsEvent.RequestStarted.class,
					McpMetricsEvent.RequestStreamOpened.class,
					McpMetricsEvent.SubscriptionOpened.class,
					McpMetricsEvent.RequestStreamClosed.class,
					McpMetricsEvent.SubscriptionClosed.class,
					McpMetricsEvent.RequestFinished.class),
					withoutKeepAlives.stream().map(Object::getClass).toList());
			McpMetricsEvent.RequestStreamClosed streamClosed = withoutKeepAlives
					.stream()
					.filter(McpMetricsEvent.RequestStreamClosed.class::isInstance)
					.map(McpMetricsEvent.RequestStreamClosed.class::cast)
					.findFirst().orElseThrow();
			McpMetricsEvent.SubscriptionClosed subscriptionClosed =
					withoutKeepAlives.stream()
							.filter(McpMetricsEvent.SubscriptionClosed.class::isInstance)
							.map(McpMetricsEvent.SubscriptionClosed.class::cast)
							.findFirst().orElseThrow();
			Assertions.assertEquals(expectedReason, streamClosed.getReason());
			Assertions.assertEquals(expectedReason,
					subscriptionClosed.getReason());
			long keepAliveCount = events.stream()
					.filter(McpMetricsEvent.KeepAliveEmitted.class::isInstance)
					.count();
			if (Boolean.TRUE.equals(expectKeepAlive))
				Assertions.assertTrue(keepAliveCount > 0);
			else if (Boolean.FALSE.equals(expectKeepAlive))
				Assertions.assertEquals(0, keepAliveCount);
			Assertions.assertEquals(1,
					this.maximumConcurrentMetricCallbacks.get(),
					"Subscription lifecycle and keep-alive callbacks must share one serialized FIFO.");

			int subscriptionOpenedIndex = indexOfEvent(events,
					McpMetricsEvent.SubscriptionOpened.class);
			int requestStreamClosedIndex = indexOfEvent(events,
					McpMetricsEvent.RequestStreamClosed.class);
			for (int index = 0; index < events.size(); index++) {
				if (events.get(index) instanceof McpMetricsEvent.KeepAliveEmitted) {
					Assertions.assertTrue(index > subscriptionOpenedIndex,
							"Keep-alive delivery preceded subscription activation.");
					Assertions.assertTrue(index < requestStreamClosedIndex,
							"Keep-alive delivery followed stream closure.");
				}
			}
		}

		private static int indexOfEvent(
				@NonNull List<@NonNull McpMetricsEvent> events,
				@NonNull Class<? extends McpMetricsEvent> eventType) {
			for (int index = 0; index < events.size(); index++) {
				if (eventType.isInstance(events.get(index)))
					return index;
			}
			throw new AssertionError("Missing MCP metric event: "
					+ eventType.getSimpleName());
		}
	}
}
