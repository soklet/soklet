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
import com.soklet.McpRequestContext;
import com.soklet.McpRequestOutcome;
import com.soklet.StreamTerminationReason;
import com.soklet.internal.mcp.transport.McpOutboundChannel;
import com.soklet.internal.microhttp.WritableSource;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketOption;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Deterministic ownership, timing, and bounded-channel checks for resource
 * subscriptions.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@NotThreadSafe
@Timeout(30)
public class McpSubscriptionRuntimeBoundaryTests {
	private static final String MCP_PATH = "/mcp";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final URI RESOURCE_URI =
			URI.create("test://subscription/boundary");

	@AfterEach
	public void resetTestHooks() {
		McpRequestSseStream.setTestHooks(null);
	}

	@Test
	public void maximumDurationIsAbsoluteAcrossKeepAlivesAndEvents()
			throws Exception {
		ControllableClock clock = new ControllableClock();
		TestEventSource source = new TestEventSource();
		RecordingObservationSink observations = new RecordingObservationSink();
		McpSubscriptionRuntimeConfiguration configuration =
				subscriptionConfiguration(4, Duration.ofSeconds(1),
						Duration.ofSeconds(5));
		McpHttpServerRuntime runtime = runtime(MCP_PATH, source, observations,
				clock, configuration);

		try {
			int port = runtime.start().getPort();
			try (McpChunkedHttpClient client = listen(port, "duration-boundary")) {
				assertSseHead(client.readHead());
				Assertions.assertEquals(acknowledgment("duration-boundary"),
						client.readChunkText());

				clock.advance(Duration.ofSeconds(1));
				runtime.runApplicationTimerCycle();
				Assertions.assertEquals(": keepalive\n\n", client.readChunkText());

				source.publishResourcesListChanged();
				Assertions.assertEquals(resourceListChanged("duration-boundary"),
						client.readChunkText());

				clock.advance(Duration.ofSeconds(4));
				runtime.runApplicationTimerCycle();
				Assertions.assertEquals(terminal("duration-boundary"),
						client.readChunkText());
				Assertions.assertNull(client.readChunk());
			}

			RecordingObservation observation =
					observations.observation("duration-boundary");
			observation.awaitFinish();
			Assertions.assertEquals(McpRequestOutcome.COMPLETE,
					observation.outcome());
			Assertions.assertEquals(List.of(StreamTerminationReason.RESPONSE_TIMEOUT),
					observation.streamCloseReasons());
			Assertions.assertEquals(List.of(StreamTerminationReason.RESPONSE_TIMEOUT),
					observation.subscriptionCloseReasons());
			Assertions.assertEquals(List.of(Duration.ofSeconds(5)),
					observation.streamDurations());
			Assertions.assertEquals(List.of(Duration.ofSeconds(5)),
					observation.subscriptionDurations());
			Assertions.assertEquals(1, observation.keepAliveCount());
			Assertions.assertEquals(1, observation.finishCount());
			awaitClean(runtime);
		} finally {
			runtime.close();
		}
	}

	@Test
	public void gracefulShutdownReservationBeatsConcurrentPublisherExactlyOnce()
			throws Exception {
		ControllableClock clock = new ControllableClock();
		TestEventSource source = new TestEventSource();
		RecordingObservationSink observations = new RecordingObservationSink();
		McpHttpServerRuntime runtime = runtime(MCP_PATH, source, observations,
				clock, subscriptionConfiguration(4, Duration.ofSeconds(1),
						Duration.ofMinutes(1)));
		CountDownLatch terminalReservationEntered = new CountDownLatch(1);
		CountDownLatch releaseTerminalReservation = new CountDownLatch(1);
		AtomicInteger hookInvocations = new AtomicInteger();
		McpRequestSseStream.setTestHooks(() -> {
			hookInvocations.incrementAndGet();
			terminalReservationEntered.countDown();
			try {
				if (!releaseTerminalReservation.await(5, TimeUnit.SECONDS))
					throw new AssertionError(
							"The shutdown terminal reservation was not released.");
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new AssertionError(exception);
			}
		});
		Thread stopThread = null;

		try {
			int port = runtime.start().getPort();
			try (McpChunkedHttpClient client = listen(port, "shutdown-race")) {
				assertSseHead(client.readHead());
				Assertions.assertEquals(acknowledgment("shutdown-race"),
						client.readChunkText());

				stopThread = new Thread(runtime::stop,
						"mcp-subscription-boundary-stop");
				stopThread.start();
				Assertions.assertTrue(terminalReservationEntered.await(
						5, TimeUnit.SECONDS));
				source.publishResourcesListChanged();
				releaseTerminalReservation.countDown();

				Assertions.assertEquals(terminal("shutdown-race"),
						client.readChunkText(),
						"A concurrent publisher must not append after terminal ownership.");
				Assertions.assertNull(client.readChunk());
			}
			stopThread.join(5_000L);
			Assertions.assertFalse(stopThread.isAlive());
			Assertions.assertEquals(1, hookInvocations.get());

			RecordingObservation observation = observations.observation("shutdown-race");
			observation.awaitFinish();
			Assertions.assertEquals(McpRequestOutcome.COMPLETE,
					observation.outcome());
			Assertions.assertEquals(List.of(StreamTerminationReason.SERVER_STOPPING),
					observation.streamCloseReasons());
			Assertions.assertEquals(List.of(StreamTerminationReason.SERVER_STOPPING),
					observation.subscriptionCloseReasons());
			Assertions.assertEquals(1, observation.finishCount());
		} finally {
			releaseTerminalReservation.countDown();
			runtime.close();
			if (stopThread != null && stopThread.isAlive())
				stopThread.join(5_000L);
		}
	}

	@Test
	public void startupFailureRollsBackRegistrationsAndCanRestart()
			throws Exception {
		AtomicInteger globalSubscriptionAttempts = new AtomicInteger();
		Runnable failSecondGlobalSubscription = () -> {
			if (globalSubscriptionAttempts.incrementAndGet() == 2)
				throw new IllegalStateException("simulated publisher failure");
		};
		TestEventSource first = new TestEventSource(failSecondGlobalSubscription);
		TestEventSource second = new TestEventSource(failSecondGlobalSubscription);
		McpHttpServerRuntime runtime = runtime(List.of(
				binding("/first", first, McpRuntimeObservationSink.disabledInstance()),
				binding("/second", second,
						McpRuntimeObservationSink.disabledInstance())),
				McpApplicationClock.SYSTEM,
				subscriptionConfiguration(4, Duration.ofSeconds(1),
						Duration.ofMinutes(1)));

		try {
			Assertions.assertThrows(IllegalStateException.class, runtime::start);
			Assertions.assertFalse(runtime.lifecycleSnapshot().started());
			Assertions.assertTrue(runtime.lifecycleSnapshot().boundAddress().isEmpty());
			Assertions.assertEquals(2, first.subscriptionAttempts()
					+ second.subscriptionAttempts());
			Assertions.assertEquals(1, first.closedRegistrationCount()
					+ second.closedRegistrationCount());
			Assertions.assertEquals(0, first.publisherCloseCount());
			Assertions.assertEquals(0, second.publisherCloseCount());

			Assertions.assertNotNull(runtime.start());
			Assertions.assertTrue(runtime.lifecycleSnapshot().started());
			Assertions.assertEquals(2, first.subscriptionAttempts());
			Assertions.assertEquals(2, second.subscriptionAttempts());
			runtime.stop();
			Assertions.assertEquals(3, first.closedRegistrationCount()
					+ second.closedRegistrationCount());
			Assertions.assertEquals(0, first.publisherCloseCount());
			Assertions.assertEquals(0, second.publisherCloseCount());
		} finally {
			runtime.close();
		}
	}

	@Test
	public void deactivatedGenerationCannotPublishIntoRestartedServer()
			throws Exception {
		TestEventSource source = new TestEventSource();
		McpHttpServerRuntime runtime = runtime(MCP_PATH, source,
				McpRuntimeObservationSink.disabledInstance(), McpApplicationClock.SYSTEM,
				subscriptionConfiguration(4, Duration.ofSeconds(1),
						Duration.ofMinutes(1)));
		McpChunkedHttpClient firstClient = null;
		McpChunkedHttpClient restartedClient = null;

		try {
			int firstPort = runtime.start().getPort();
			firstClient = listenForAllResourceEvents(firstPort, "stale-generation");
			assertSseHead(firstClient.readHead());
			Assertions.assertEquals(acknowledgmentForAllResourceEvents(
					"stale-generation"), firstClient.readChunkText());
			firstClient.closeWithReset();
			firstClient = null;
			awaitClean(runtime);
			runtime.stop();

			TestEventSource.Generation stoppedGeneration = source.generation(0);
			Assertions.assertEquals(1, stoppedGeneration.closeInvocationCount());
			Assertions.assertEquals(1, stoppedGeneration.successfulCloseCount());

			int restartedPort = runtime.start().getPort();
			restartedClient = listenForAllResourceEvents(restartedPort,
					"current-generation");
			assertSseHead(restartedClient.readHead());
			Assertions.assertEquals(acknowledgmentForAllResourceEvents(
					"current-generation"), restartedClient.readChunkText());

			stoppedGeneration.publish(
					new McpSubscriptionEventSource.Event.ResourcesListChanged());
			source.generation(1).publish(
					new McpSubscriptionEventSource.Event.ResourceUpdated(
							RESOURCE_URI, RESOURCE_URI.toString()));
			Assertions.assertEquals(resourceUpdated("current-generation"),
					restartedClient.readChunkText(),
					"A callback retained by an old publisher registration must remain fenced "
							+ "after restart.");

			restartedClient.closeWithReset();
			restartedClient = null;
			awaitClean(runtime);
			runtime.stop();
			Assertions.assertEquals(1, stoppedGeneration.closeInvocationCount(),
					"A successfully closed registration must never be closed again.");
			Assertions.assertEquals(1,
					source.generation(1).closeInvocationCount());
			Assertions.assertEquals(1,
					source.generation(1).successfulCloseCount());
			runtime.stop();
			Assertions.assertEquals(1, stoppedGeneration.closeInvocationCount());
			Assertions.assertEquals(1,
					source.generation(1).closeInvocationCount());
		} finally {
			if (firstClient != null)
				firstClient.closeWithReset();
			if (restartedClient != null)
				restartedClient.closeWithReset();
			runtime.close();
		}
	}

	@Test
	public void failedRegistrationCloseIsObservableAndBlocksRestartUntilRetry()
			throws Exception {
		TestEventSource source = TestEventSource.failingFirstClose();
		McpHttpServerRuntime runtime = runtime(MCP_PATH, source,
				McpRuntimeObservationSink.disabledInstance(), McpApplicationClock.SYSTEM,
				subscriptionConfiguration(4, Duration.ofSeconds(1),
						Duration.ofMinutes(1)));

		try {
			runtime.start();
			IllegalStateException stopFailure = Assertions.assertThrows(
					IllegalStateException.class, runtime::stop);
			assertResidualRegistrationCloseFailure(stopFailure,
					source.firstCloseFailure());
			assertResidualRegistrationLifecycle(runtime);
			assertRestartBlockedByResidualRegistration(runtime);

			TestEventSource.Generation failedGeneration = source.generation(0);
			Assertions.assertEquals(1, failedGeneration.closeInvocationCount());
			Assertions.assertEquals(0, failedGeneration.successfulCloseCount());
			runtime.stop();
			Assertions.assertFalse(runtime.lifecycleSnapshot().stopRequired());
			Assertions.assertEquals(2, failedGeneration.closeInvocationCount(),
					"A completed failure must be retried through the idempotent close contract.");
			Assertions.assertEquals(1, failedGeneration.successfulCloseCount());

			runtime.start();
			runtime.stop();
			Assertions.assertEquals(2, failedGeneration.closeInvocationCount(),
					"Later generations must not close an already resolved registration.");
			Assertions.assertEquals(1,
					source.generation(1).closeInvocationCount());
			Assertions.assertEquals(1,
					source.generation(1).successfulCloseCount());
		} finally {
			runtime.close();
		}
	}

	@Test
	public void blockingRegistrationCloseIsBoundedAndNeverRetriedConcurrently()
			throws Exception {
		Duration shutdownTimeout = Duration.ofMillis(250);
		TestEventSource source = TestEventSource.blockingFirstClose();
		McpHttpServerRuntime runtime = runtime(MCP_PATH, source,
				McpRuntimeObservationSink.disabledInstance(), McpApplicationClock.SYSTEM,
				subscriptionConfiguration(4, Duration.ofSeconds(1),
						Duration.ofMinutes(1), shutdownTimeout));

		try {
			runtime.start();
			long firstStopStartedAt = System.nanoTime();
			IllegalStateException firstStopFailure = Assertions.assertThrows(
					IllegalStateException.class, runtime::stop);
			long firstStopDuration = System.nanoTime() - firstStopStartedAt;
			Assertions.assertEquals(
					McpHttpServerRuntime.RESIDUAL_SUBSCRIPTION_EVENT_SOURCE_DIAGNOSTIC,
					firstStopFailure.getMessage());
			Assertions.assertTrue(firstStopDuration
					< shutdownTimeout.plusSeconds(1).toNanos(),
					"A blocking application close exceeded the configured shutdown budget "
							+ "and scheduling tolerance: " + Duration.ofNanos(firstStopDuration));
			source.awaitFirstCloseEntered();
			assertResidualRegistrationLifecycle(runtime);
			assertRestartBlockedByResidualRegistration(runtime);

			IllegalStateException secondStopFailure = Assertions.assertThrows(
					IllegalStateException.class, runtime::stop);
			Assertions.assertEquals(
					McpHttpServerRuntime.RESIDUAL_SUBSCRIPTION_EVENT_SOURCE_DIAGNOSTIC,
					secondStopFailure.getMessage());
			TestEventSource.Generation blockedGeneration = source.generation(0);
			Assertions.assertEquals(1, blockedGeneration.closeInvocationCount(),
					"Cleanup must join an in-flight close instead of invoking it again.");
			Assertions.assertEquals(1, blockedGeneration.maximumConcurrentCloses());

			source.releaseFirstClose();
			blockedGeneration.awaitSuccessfulClose();
			runtime.stop();
			Assertions.assertFalse(runtime.lifecycleSnapshot().stopRequired());
			runtime.start();
			runtime.stop();
			Assertions.assertEquals(1, blockedGeneration.closeInvocationCount());
			Assertions.assertEquals(1, blockedGeneration.successfulCloseCount());
			Assertions.assertEquals(1,
					source.generation(1).closeInvocationCount());
		} finally {
			source.releaseFirstClose();
			runtime.close();
		}
	}

	@Test
	public void startupRollbackRetainsFailedCloseUntilSuccessfulRetry()
			throws Exception {
		TestEventSource first = TestEventSource.failingFirstClose();
		IllegalStateException subscriptionFailure =
				new IllegalStateException("simulated startup subscription failure");
		AtomicBoolean failNextSubscription = new AtomicBoolean(true);
		TestEventSource second = new TestEventSource(() -> {
			if (failNextSubscription.compareAndSet(true, false))
				throw subscriptionFailure;
		});
		McpHttpServerRuntime runtime = runtime(List.of(
				binding("/first", first, McpRuntimeObservationSink.disabledInstance()),
				binding("/second", second,
						McpRuntimeObservationSink.disabledInstance())),
				McpApplicationClock.SYSTEM,
				subscriptionConfiguration(4, Duration.ofSeconds(1),
						Duration.ofMinutes(1)));

		try {
			IllegalStateException startupFailure = Assertions.assertThrows(
					IllegalStateException.class, runtime::start);
			Assertions.assertSame(subscriptionFailure, startupFailure,
					"Publisher subscription failure must remain the primary startup error.");
			Assertions.assertEquals(1, startupFailure.getSuppressed().length);
			assertResidualRegistrationCloseFailure(
					(IllegalStateException) startupFailure.getSuppressed()[0],
					first.firstCloseFailure());
			assertResidualRegistrationLifecycle(runtime);
			assertRestartBlockedByResidualRegistration(runtime);

			TestEventSource.Generation failedGeneration = first.generation(0);
			Assertions.assertEquals(1, failedGeneration.closeInvocationCount());
			runtime.stop();
			Assertions.assertEquals(2, failedGeneration.closeInvocationCount());
			Assertions.assertEquals(1, failedGeneration.successfulCloseCount());
			Assertions.assertFalse(runtime.lifecycleSnapshot().stopRequired());

			runtime.start();
			runtime.stop();
			Assertions.assertEquals(2, failedGeneration.closeInvocationCount());
			Assertions.assertEquals(1,
					first.generation(1).closeInvocationCount());
			Assertions.assertEquals(1,
					second.generation(0).closeInvocationCount());
		} finally {
			runtime.close();
		}
	}

	@Test
	public void startupRollbackCannotBeHeldPastItsShutdownDeadline()
			throws Exception {
		Duration shutdownTimeout = Duration.ofMillis(250);
		TestEventSource first = TestEventSource.blockingFirstClose();
		IllegalStateException subscriptionFailure =
				new IllegalStateException("simulated startup subscription failure");
		AtomicBoolean failNextSubscription = new AtomicBoolean(true);
		TestEventSource second = new TestEventSource(() -> {
			if (failNextSubscription.compareAndSet(true, false))
				throw subscriptionFailure;
		});
		McpHttpServerRuntime runtime = runtime(List.of(
				binding("/first", first, McpRuntimeObservationSink.disabledInstance()),
				binding("/second", second,
						McpRuntimeObservationSink.disabledInstance())),
				McpApplicationClock.SYSTEM,
				subscriptionConfiguration(4, Duration.ofSeconds(1),
						Duration.ofMinutes(1), shutdownTimeout));

		try {
			long startupStartedAt = System.nanoTime();
			IllegalStateException startupFailure = Assertions.assertThrows(
					IllegalStateException.class, runtime::start);
			long startupDuration = System.nanoTime() - startupStartedAt;
			Assertions.assertSame(subscriptionFailure, startupFailure);
			Assertions.assertTrue(startupDuration
					< shutdownTimeout.plusSeconds(1).toNanos(),
					"Startup rollback exceeded the global cleanup budget and scheduling "
							+ "tolerance: " + Duration.ofNanos(startupDuration));
			Assertions.assertEquals(1, startupFailure.getSuppressed().length);
			Throwable cleanupFailure = startupFailure.getSuppressed()[0];
			Assertions.assertEquals(
					McpHttpServerRuntime.RESIDUAL_SUBSCRIPTION_EVENT_SOURCE_DIAGNOSTIC,
					cleanupFailure.getMessage());
			Assertions.assertEquals(0, cleanupFailure.getSuppressed().length,
					"An in-flight close has no application failure to report yet.");
			first.awaitFirstCloseEntered();
			assertResidualRegistrationLifecycle(runtime);
			assertRestartBlockedByResidualRegistration(runtime);

			IllegalStateException cleanupRetry = Assertions.assertThrows(
					IllegalStateException.class, runtime::stop);
			Assertions.assertEquals(
					McpHttpServerRuntime.RESIDUAL_SUBSCRIPTION_EVENT_SOURCE_DIAGNOSTIC,
					cleanupRetry.getMessage());
			TestEventSource.Generation blockedGeneration = first.generation(0);
			Assertions.assertEquals(1, blockedGeneration.closeInvocationCount());
			Assertions.assertEquals(1, blockedGeneration.maximumConcurrentCloses());

			first.releaseFirstClose();
			blockedGeneration.awaitSuccessfulClose();
			runtime.stop();
			Assertions.assertFalse(runtime.lifecycleSnapshot().stopRequired());
			runtime.start();
			runtime.stop();
			Assertions.assertEquals(1, blockedGeneration.closeInvocationCount());
			Assertions.assertEquals(1,
					first.generation(1).closeInvocationCount());
			Assertions.assertEquals(1,
					second.generation(0).closeInvocationCount());
		} finally {
			first.releaseFirstClose();
			runtime.close();
		}
	}

	@Test
	public void trustedSubscriptionMetadataCannotBeApplicationSpoofed() {
		McpJsonObject stringSpoof = new McpJsonObject(Map.of(
				McpResultMetadata.SUBSCRIPTION_ID_KEY,
				new McpJsonString("subscription-string")));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> new McpResultMetadata(Optional.empty(), stringSpoof));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> new McpResultMetadata(Optional.empty(),
						new McpJsonObject(Map.of(
								McpResultMetadata.SERVER_INFORMATION_KEY,
								McpJsonObject.empty()))));

		McpResultMetadata stringMetadata = McpResultMetadata.withSubscriptionId(
				new McpJsonRpcId.StringId("subscription-string"), Optional.empty());
		Assertions.assertEquals(Set.of(McpResultMetadata.SUBSCRIPTION_ID_KEY),
				stringMetadata.extensionFields().members().keySet());
		Assertions.assertEquals(new McpJsonString("subscription-string"),
				stringMetadata.extensionFields().members().get(
						McpResultMetadata.SUBSCRIPTION_ID_KEY));

		McpResultMetadata integerMetadata = McpResultMetadata.withSubscriptionId(
				new McpJsonRpcId.IntegerId(BigInteger.valueOf(37L)), Optional.empty());
		Assertions.assertEquals(Set.of(McpResultMetadata.SUBSCRIPTION_ID_KEY),
				integerMetadata.extensionFields().members().keySet());
		Assertions.assertEquals(new McpJsonNumber(BigDecimal.valueOf(37L)),
				integerMetadata.extensionFields().members().get(
						McpResultMetadata.SUBSCRIPTION_ID_KEY));
	}

	@Test
	public void outboundCoalescingRetainsKeysUntilFullWriteAndClearsOnClose()
			throws Exception {
		McpOutboundChannel channel = new McpOutboundChannel(
				2, 16, 16, System::nanoTime, new NoOpChannelListener());
		WritableSource source = channel.newWritableSource();
		Object firstKey = new Object();
		Object secondKey = new Object();
		Object thirdKey = new Object();

		Assertions.assertEquals(McpOutboundChannel.OfferResult.ACCEPTED,
				channel.offerCoalescing(ascii("one"), firstKey));
		Assertions.assertEquals(McpOutboundChannel.OfferResult.ACCEPTED,
				channel.offerCoalescing(ascii("duplicate"), firstKey));
		Assertions.assertEquals(1, channel.snapshot().bufferedFrames(),
				"A pending duplicate must be represented by the first frame.");
		Assertions.assertEquals(McpOutboundChannel.OfferResult.ACCEPTED,
				channel.offerCoalescing(ascii("two"), secondKey));
		Assertions.assertEquals(McpOutboundChannel.OfferResult.FULL,
				channel.offerCoalescing(ascii("x"), thirdKey));
		Assertions.assertEquals(McpOutboundChannel.OfferResult.TOO_LARGE,
				channel.offerCoalescing(ascii("x".repeat(17)), thirdKey));

		source.writeReadyCallback(() -> {
			// The test drives writes directly.
		});
		source.start();
		PartialWriteSocketChannel socket = new PartialWriteSocketChannel(1);
		source.writeTo(socket, 1L);
		Assertions.assertEquals(McpOutboundChannel.OfferResult.ACCEPTED,
				channel.offerCoalescing(ascii("still-duplicate"), firstKey));
		Assertions.assertEquals(2, channel.snapshot().bufferedFrames(),
				"A partially in-flight key must remain coalesced.");

		source.writeTo(socket, 8L);
		Assertions.assertEquals(1, channel.snapshot().bufferedFrames());
		Assertions.assertEquals(McpOutboundChannel.OfferResult.ACCEPTED,
				channel.offerCoalescing(ascii("new"), firstKey));
		Assertions.assertEquals(2, channel.snapshot().bufferedFrames(),
				"A fully written key must become eligible again.");

		source.close(StreamTerminationReason.CLIENT_DISCONNECTED, null);
		Assertions.assertEquals(0, channel.snapshot().bufferedFrames());
		Assertions.assertEquals(0, channel.snapshot().bufferedBytes());
		Assertions.assertEquals(McpOutboundChannel.OfferResult.CLOSED,
				channel.offerCoalescing(ascii("closed"), firstKey));

		McpOutboundChannel failed = new McpOutboundChannel(
				1, 16, 16, System::nanoTime, new NoOpChannelListener());
		Assertions.assertEquals(McpOutboundChannel.OfferResult.ACCEPTED,
				failed.offerCoalescing(ascii("one"), firstKey));
		Assertions.assertTrue(failed.fail(StreamTerminationReason.BACKPRESSURE, null));
		Assertions.assertEquals(0, failed.snapshot().bufferedFrames());
		Assertions.assertEquals(McpOutboundChannel.OfferResult.CLOSED,
				failed.offerCoalescing(ascii("after-failure"), firstKey));
	}

	@NonNull
	private static McpHttpServerRuntime runtime(@NonNull String path,
			@NonNull TestEventSource source,
			@NonNull McpRuntimeObservationSink observations,
			@NonNull McpApplicationClock clock,
			@NonNull McpSubscriptionRuntimeConfiguration configuration) {
		return runtime(List.of(binding(path, source, observations)), clock,
				configuration);
	}

	@NonNull
	private static McpHttpServerRuntime runtime(
			@NonNull List<@NonNull McpHttpEndpointBinding> bindings,
			@NonNull McpApplicationClock clock,
			@NonNull McpSubscriptionRuntimeConfiguration configuration) {
		McpHttpTransportConfiguration defaults =
				McpHttpTransportConfiguration.productionDefaults(0);
		McpHttpTransportConfiguration transport = new McpHttpTransportConfiguration(
				defaults.host(), defaults.port(), defaults.selectorResolution(),
				defaults.requestHeaderTimeout(), defaults.requestBodyTimeout(),
				configuration.writeTimeout(), configuration.keepAliveInterval(),
				configuration.shutdownTimeout(), defaults.readBufferSize(),
				defaults.acceptBacklog(), defaults.maximumAggregateRequestBytes(),
				defaults.maximumRequestBodyBytes(), defaults.maximumHeaderCount(),
				defaults.maximumHeaderBytes(), defaults.maximumRequestTargetBytes(),
				defaults.maximumConnections(), defaults.connectionWriterConcurrency(),
				defaults.requestProcessorConcurrency(),
				defaults.requestProcessorQueueCapacity(),
				configuration.streamQueueCapacity());
		return new McpHttpServerRuntime(transport, bindings,
				McpJsonLimits.productionDefaults(),
				McpApplicationExecutionConfiguration.productionDefaults(), clock,
				McpApplicationHandlerExecutorFactory.production(),
				ignored -> {}, ignored -> {}, Optional.empty(),
				McpFrameworkRequestStateRuntime.disabledInstance(), configuration);
	}

	@NonNull
	private static McpHttpEndpointBinding binding(@NonNull String path,
			@NonNull TestEventSource source,
			@NonNull McpRuntimeObservationSink observations) {
		McpNormalizedEndpoint endpoint = McpNormalizedEndpoint
				.withServerInformation(McpImplementationMetadata.withNameAndVersion(
						"subscription-boundary-test", "3.6.0-SNAPSHOT"))
				.exactResource(RESOURCE_URI.toString())
				.subscriptions(McpNormalizedSubscriptionConfiguration.supporting(
						McpResourceNotificationType.RESOURCES_LIST_CHANGED,
						McpResourceNotificationType.RESOURCE_UPDATED))
				.build();
		McpHttpEndpointPolicy policy = new McpHttpEndpointPolicy(path, Set.of(),
				McpAbsentOriginPolicy.ALLOW, CorsAuthorizer.rejectAllInstance(),
				request -> McpRequestAdmissionDecision.ACCEPT);
		return new McpHttpEndpointBinding(policy, endpoint,
				McpApplicationRequestRouter.empty(), observations,
				Optional.of(source.source()));
	}

	@NonNull
	private static McpSubscriptionRuntimeConfiguration subscriptionConfiguration(
			int queueCapacity, @NonNull Duration keepAliveInterval,
			@NonNull Duration maximumDuration) {
		return subscriptionConfiguration(queueCapacity, keepAliveInterval,
				maximumDuration, Duration.ofSeconds(5));
	}

	@NonNull
	private static McpSubscriptionRuntimeConfiguration subscriptionConfiguration(
			int queueCapacity, @NonNull Duration keepAliveInterval,
			@NonNull Duration maximumDuration,
			@NonNull Duration shutdownTimeout) {
		return new McpSubscriptionRuntimeConfiguration(queueCapacity,
				Duration.ofSeconds(10), keepAliveInterval, shutdownTimeout,
				2, maximumDuration);
	}

	@NonNull
	private static McpChunkedHttpClient listen(int port, @NonNull String id)
			throws IOException {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + id
				+ "\",\"method\":\"subscriptions/listen\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}},"
				+ "\"notifications\":{\"resourcesListChanged\":true}}}";
		return McpChunkedHttpClient.postMcpMessage(port, body, List.of(
				new McpChunkedHttpClient.RequestHeader(
						"MCP-Protocol-Version", PROTOCOL_VERSION),
				new McpChunkedHttpClient.RequestHeader(
						"Mcp-Method", "subscriptions/listen")));
	}

	@NonNull
	private static McpChunkedHttpClient listenForAllResourceEvents(int port,
			@NonNull String id) throws IOException {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + id
				+ "\",\"method\":\"subscriptions/listen\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}},"
				+ "\"notifications\":{\"resourcesListChanged\":true,"
				+ "\"resourceSubscriptions\":[\"" + RESOURCE_URI + "\"]}}}";
		return McpChunkedHttpClient.postMcpMessage(port, body, List.of(
				new McpChunkedHttpClient.RequestHeader(
						"MCP-Protocol-Version", PROTOCOL_VERSION),
				new McpChunkedHttpClient.RequestHeader(
						"Mcp-Method", "subscriptions/listen")));
	}

	private static void assertResidualRegistrationLifecycle(
			@NonNull McpHttpServerRuntime runtime) {
		McpHttpServerLifecycleSnapshot lifecycle = runtime.lifecycleSnapshot();
		Assertions.assertFalse(lifecycle.started());
		Assertions.assertTrue(lifecycle.boundAddress().isEmpty());
		Assertions.assertTrue(lifecycle.stopRequired(),
				"A residual publisher registration must retain cleanup ownership.");
	}

	private static void assertRestartBlockedByResidualRegistration(
			@NonNull McpHttpServerRuntime runtime) {
		IllegalStateException restartFailure = Assertions.assertThrows(
				IllegalStateException.class, runtime::start);
		Assertions.assertEquals(
				McpHttpServerRuntime.RESIDUAL_SUBSCRIPTION_EVENT_SOURCE_RESTART_DIAGNOSTIC,
				restartFailure.getMessage());
	}

	private static void assertResidualRegistrationCloseFailure(
			@NonNull IllegalStateException failure,
			@NonNull Throwable expectedApplicationFailure) {
		Assertions.assertEquals(
				McpHttpServerRuntime.RESIDUAL_SUBSCRIPTION_EVENT_SOURCE_DIAGNOSTIC,
				failure.getMessage());
		Assertions.assertEquals(1, failure.getSuppressed().length);
		Assertions.assertSame(expectedApplicationFailure,
				failure.getSuppressed()[0],
				"The application's close failure must remain observable.");
	}

	private static void assertSseHead(
			McpChunkedHttpClient.@NonNull HttpResponseHead head) {
		Assertions.assertEquals(200, head.status(), head.raw());
		Assertions.assertEquals("text/event-stream",
				head.singleHeader("Content-Type"));
		Assertions.assertEquals("chunked",
				head.singleHeader("Transfer-Encoding"));
	}

	@NonNull
	private static String acknowledgment(@NonNull String id) {
		return sse("{\"jsonrpc\":\"2.0\","
				+ "\"method\":\"notifications/subscriptions/acknowledged\","
				+ "\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/subscriptionId\":\"" + id
				+ "\"},\"notifications\":{\"resourcesListChanged\":true}}}");
	}

	@NonNull
	private static String acknowledgmentForAllResourceEvents(
			@NonNull String id) {
		return sse("{\"jsonrpc\":\"2.0\","
				+ "\"method\":\"notifications/subscriptions/acknowledged\","
				+ "\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/subscriptionId\":\"" + id
				+ "\"},\"notifications\":{\"resourcesListChanged\":true,"
				+ "\"resourceSubscriptions\":[\"" + RESOURCE_URI + "\"]}}}");
	}

	@NonNull
	private static String resourceListChanged(@NonNull String id) {
		return sse("{\"jsonrpc\":\"2.0\","
				+ "\"method\":\"notifications/resources/list_changed\","
				+ "\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/subscriptionId\":\"" + id
				+ "\"}}}");
	}

	@NonNull
	private static String resourceUpdated(@NonNull String id) {
		return sse("{\"jsonrpc\":\"2.0\","
				+ "\"method\":\"notifications/resources/updated\","
				+ "\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/subscriptionId\":\"" + id
				+ "\"},\"uri\":\"" + RESOURCE_URI + "\"}}");
	}

	@NonNull
	private static String terminal(@NonNull String id) {
		return sse("{\"jsonrpc\":\"2.0\",\"id\":\"" + id
				+ "\",\"result\":{\"resultType\":\"complete\",\"_meta\":{"
				+ "\"io.modelcontextprotocol/subscriptionId\":\"" + id
				+ "\",\"io.modelcontextprotocol/serverInfo\":{"
				+ "\"name\":\"subscription-boundary-test\","
				+ "\"version\":\"3.6.0-SNAPSHOT\"}}}}");
	}

	@NonNull
	private static String sse(@NonNull String json) {
		return "data: " + json + "\n\n";
	}

	private static void awaitClean(@NonNull McpHttpServerRuntime runtime)
			throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		McpRequestExecutionSnapshot snapshot;
		do {
			snapshot = runtime.requestExecutionSnapshot();
			if (snapshot.retainedRequestControls() == 0
					&& snapshot.activeIdentifiedRequestExchanges() == 0)
				return;
			Thread.sleep(5L);
		} while (System.nanoTime() - deadline < 0L);
		throw new AssertionError("Subscription state was retained: " + snapshot);
	}

	private static byte @NonNull [] ascii(@NonNull String value) {
		return value.getBytes(StandardCharsets.US_ASCII);
	}

		@ThreadSafe
		private static final class TestEventSource implements AutoCloseable {
			@NonNull
			private final CopyOnWriteArrayList<@NonNull Generation> generations =
					new CopyOnWriteArrayList<>();
			@NonNull
			private final AtomicReference<@Nullable Generation> currentGeneration =
					new AtomicReference<>();
			@NonNull
			private final AtomicInteger subscriptionAttempts = new AtomicInteger();
			@NonNull
			private final AtomicInteger closedRegistrations = new AtomicInteger();
		@NonNull
			private final AtomicInteger publisherCloses = new AtomicInteger();
			@NonNull
			private final Runnable beforeRegistration;
			@NonNull
			private final FirstCloseBehavior firstCloseBehavior;
			@NonNull
			private final IllegalStateException firstCloseFailure =
					new IllegalStateException("simulated registration close failure");
			@NonNull
			private final CountDownLatch firstCloseEntered = new CountDownLatch(1);
			@NonNull
			private final CountDownLatch firstCloseRelease = new CountDownLatch(1);

			private TestEventSource() {
				this(() -> {
					// No-op for an ordinary publisher.
				}, FirstCloseBehavior.SUCCEED);
			}

			private TestEventSource(@NonNull Runnable beforeRegistration) {
				this(beforeRegistration, FirstCloseBehavior.SUCCEED);
			}

			private TestEventSource(@NonNull Runnable beforeRegistration,
					@NonNull FirstCloseBehavior firstCloseBehavior) {
				this.beforeRegistration = java.util.Objects.requireNonNull(
						beforeRegistration);
				this.firstCloseBehavior = java.util.Objects.requireNonNull(
						firstCloseBehavior);
			}

			@NonNull
			private static TestEventSource failingFirstClose() {
				return new TestEventSource(() -> {
					// No-op before registration.
				}, FirstCloseBehavior.FAIL_ONCE);
			}

			@NonNull
			private static TestEventSource blockingFirstClose() {
				return new TestEventSource(() -> {
					// No-op before registration.
				}, FirstCloseBehavior.BLOCK_UNTIL_RELEASED);
			}

			@NonNull
			private McpSubscriptionEventSource source() {
				return new McpSubscriptionEventSource(this, nextListener -> {
					this.subscriptionAttempts.incrementAndGet();
					this.beforeRegistration.run();
					FirstCloseBehavior closeBehavior = this.generations.isEmpty()
							? this.firstCloseBehavior : FirstCloseBehavior.SUCCEED;
					Generation generation = new Generation(nextListener, closeBehavior);
					this.generations.add(generation);
					this.currentGeneration.set(generation);
					return generation;
				});
			}

			private void publishResourcesListChanged() {
				Generation current = this.currentGeneration.get();
				if (current != null)
					current.publish(
							new McpSubscriptionEventSource.Event.ResourcesListChanged());
			}

			@NonNull
			private Generation generation(int index) {
				return this.generations.get(index);
			}

			@NonNull
			private IllegalStateException firstCloseFailure() {
				return this.firstCloseFailure;
			}

			private void awaitFirstCloseEntered() throws InterruptedException {
				Assertions.assertTrue(this.firstCloseEntered.await(5, TimeUnit.SECONDS),
						"The registration close did not reach its blocking boundary.");
			}

			private void releaseFirstClose() {
				this.firstCloseRelease.countDown();
			}

		private int subscriptionAttempts() {
			return this.subscriptionAttempts.get();
		}

		private int closedRegistrationCount() {
			return this.closedRegistrations.get();
		}

		private int publisherCloseCount() {
			return this.publisherCloses.get();
		}

			@Override
			public void close() {
				this.publisherCloses.incrementAndGet();
			}

			private enum FirstCloseBehavior {
				SUCCEED,
				FAIL_ONCE,
				BLOCK_UNTIL_RELEASED
			}

			@ThreadSafe
			private final class Generation
					implements McpSubscriptionEventSource.Registration {
				private final McpSubscriptionEventSource.@NonNull Listener listener;
				@NonNull
				private final FirstCloseBehavior closeBehavior;
				@NonNull
				private final AtomicBoolean closeFailureRemaining;
				@NonNull
				private final AtomicBoolean closed = new AtomicBoolean();
				@NonNull
				private final AtomicInteger closeInvocations = new AtomicInteger();
				@NonNull
				private final AtomicInteger successfulCloses = new AtomicInteger();
				@NonNull
				private final AtomicInteger activeCloses = new AtomicInteger();
				@NonNull
				private final AtomicInteger maximumConcurrentCloses = new AtomicInteger();
				@NonNull
				private final CountDownLatch successfulClose = new CountDownLatch(1);

				private Generation(
						McpSubscriptionEventSource.@NonNull Listener listener,
						@NonNull FirstCloseBehavior closeBehavior) {
					this.listener = java.util.Objects.requireNonNull(listener);
					this.closeBehavior = java.util.Objects.requireNonNull(closeBehavior);
					this.closeFailureRemaining = new AtomicBoolean(
							closeBehavior == FirstCloseBehavior.FAIL_ONCE);
				}

				private void publish(McpSubscriptionEventSource.@NonNull Event event) {
					this.listener.onEvent(event);
				}

				@Override
				public void close() {
					this.closeInvocations.incrementAndGet();
					int concurrentCloses = this.activeCloses.incrementAndGet();
					this.maximumConcurrentCloses.accumulateAndGet(concurrentCloses,
							Math::max);
					try {
						if (this.closed.get())
							return;
						if (this.closeBehavior == FirstCloseBehavior.FAIL_ONCE
								&& this.closeFailureRemaining.compareAndSet(true, false))
							throw firstCloseFailure;
						if (this.closeBehavior
								== FirstCloseBehavior.BLOCK_UNTIL_RELEASED) {
							firstCloseEntered.countDown();
							awaitFirstCloseReleaseUninterruptibly();
						}
						if (this.closed.compareAndSet(false, true)) {
							currentGeneration.compareAndSet(this, null);
							closedRegistrations.incrementAndGet();
							this.successfulCloses.incrementAndGet();
							this.successfulClose.countDown();
						}
					} finally {
						this.activeCloses.decrementAndGet();
					}
				}

				private void awaitFirstCloseReleaseUninterruptibly() {
					boolean interrupted = false;
					while (true) {
						try {
							firstCloseRelease.await();
							break;
						} catch (InterruptedException exception) {
							interrupted = true;
						}
					}
					if (interrupted)
						Thread.currentThread().interrupt();
				}

				private void awaitSuccessfulClose() throws InterruptedException {
					Assertions.assertTrue(this.successfulClose.await(5, TimeUnit.SECONDS),
							"The registration did not complete its successful close.");
				}

				private int closeInvocationCount() {
					return this.closeInvocations.get();
				}

				private int successfulCloseCount() {
					return this.successfulCloses.get();
				}

				private int maximumConcurrentCloses() {
					return this.maximumConcurrentCloses.get();
				}
			}
		}

	@ThreadSafe
	private static final class RecordingObservationSink
			implements McpRuntimeObservationSink {
		@NonNull
		private final AtomicReference<@Nullable RecordingObservation> observation =
				new AtomicReference<>();

		@Override
		@NonNull
		public McpRuntimeRequestObservation didStartRequest(
				@NonNull McpRuntimeRequestInput input) {
			RecordingObservation created = new RecordingObservation(input);
			if (!this.observation.compareAndSet(null, created))
				throw new IllegalStateException(
						"The boundary recorder supports one request.");
			return created;
		}

		@NonNull
		private RecordingObservation observation(@NonNull String id) {
			RecordingObservation current = this.observation.get();
			Assertions.assertNotNull(current);
			Assertions.assertEquals(new McpJsonRpcId.StringId(id),
					current.input().requestId().orElseThrow());
			return current;
		}
	}

	@ThreadSafe
	private static final class RecordingObservation
			implements McpRuntimeRequestObservation {
		@NonNull
		private final McpRuntimeRequestInput input;
		@NonNull
		private final List<@NonNull StreamTerminationReason> streamCloseReasons =
				new CopyOnWriteArrayList<>();
		@NonNull
		private final List<@NonNull StreamTerminationReason>
				subscriptionCloseReasons = new CopyOnWriteArrayList<>();
		@NonNull
		private final List<@NonNull Duration> streamDurations =
				new CopyOnWriteArrayList<>();
		@NonNull
		private final List<@NonNull Duration> subscriptionDurations =
				new CopyOnWriteArrayList<>();
		@NonNull
		private final AtomicInteger keepAlives = new AtomicInteger();
		@NonNull
		private final AtomicInteger finishes = new AtomicInteger();
		@NonNull
		private final AtomicReference<@Nullable McpRequestOutcome> outcome =
				new AtomicReference<>();
		@NonNull
		private final CountDownLatch finished = new CountDownLatch(1);

		private RecordingObservation(@NonNull McpRuntimeRequestInput input) {
			this.input = input;
		}

		@NonNull
		private McpRuntimeRequestInput input() {
			return this.input;
		}

		@Override
		@NonNull
		public Optional<@NonNull McpRequestContext> publicContext() {
			return Optional.empty();
		}

		@Override
		public void didFinish(@NonNull McpRequestOutcome outcome,
				@Nullable McpJsonRpcError error, @NonNull Duration duration,
				@NonNull List<@NonNull Throwable> throwables) {
			this.outcome.set(outcome);
			this.finishes.incrementAndGet();
			this.finished.countDown();
		}

		@Override
		public void didCloseRequestStream(
				@NonNull StreamTerminationReason reason,
				@NonNull Duration duration) {
			this.streamCloseReasons.add(reason);
			this.streamDurations.add(duration);
		}

		@Override
		public void didCloseSubscription(
				@NonNull StreamTerminationReason reason,
				@NonNull Duration duration) {
			this.subscriptionCloseReasons.add(reason);
			this.subscriptionDurations.add(duration);
		}

		@Override
		public void didEmitKeepAlive() {
			this.keepAlives.incrementAndGet();
		}

		private void awaitFinish() throws InterruptedException {
			Assertions.assertTrue(this.finished.await(5, TimeUnit.SECONDS));
		}

		private McpRequestOutcome outcome() {
			return this.outcome.get();
		}

		private List<StreamTerminationReason> streamCloseReasons() {
			return List.copyOf(this.streamCloseReasons);
		}

		private List<StreamTerminationReason> subscriptionCloseReasons() {
			return List.copyOf(this.subscriptionCloseReasons);
		}

		private List<Duration> streamDurations() {
			return List.copyOf(this.streamDurations);
		}

		private List<Duration> subscriptionDurations() {
			return List.copyOf(this.subscriptionDurations);
		}

		private int keepAliveCount() {
			return this.keepAlives.get();
		}

		private int finishCount() {
			return this.finishes.get();
		}
	}

	private static final class ControllableClock implements McpApplicationClock {
		@NonNull
		private final AtomicLong nanoseconds = new AtomicLong();

		@Override
		public long nanoTime() {
			return this.nanoseconds.get();
		}

		private void advance(@NonNull Duration duration) {
			this.nanoseconds.addAndGet(duration.toNanos());
		}
	}

	private static final class NoOpChannelListener
			implements McpOutboundChannel.Listener {
		@Override
		public void didWrite(long byteCount, long timestampNanos) {
		}

		@Override
		public void didApplyBackpressure() {
		}

		@Override
		public void didTerminate(@NonNull StreamTerminationReason reason,
				@Nullable Throwable cause) {
		}
	}

	private static final class PartialWriteSocketChannel extends SocketChannel {
		@NonNull
		private final ByteArrayOutputStream output = new ByteArrayOutputStream();
		private final int maximumBytesPerWrite;

		private PartialWriteSocketChannel(int maximumBytesPerWrite) {
			super(SelectorProvider.provider());
			this.maximumBytesPerWrite = maximumBytesPerWrite;
		}

		@Override
		public int write(@NonNull ByteBuffer source) {
			int byteCount = Math.min(source.remaining(), this.maximumBytesPerWrite);
			byte[] bytes = new byte[byteCount];
			source.get(bytes);
			this.output.writeBytes(bytes);
			return byteCount;
		}

		@Override
		public long write(ByteBuffer @NonNull [] sources, int offset, int length) {
			long written = 0L;
			for (int index = offset; index < offset + length; index++)
				written += write(sources[index]);
			return written;
		}

		@Override
		public int read(@NonNull ByteBuffer destination) {
			throw new UnsupportedOperationException();
		}

		@Override
		public long read(ByteBuffer @NonNull [] destinations, int offset,
				int length) {
			throw new UnsupportedOperationException();
		}

		@Override
		public SocketChannel bind(@Nullable SocketAddress localAddress) {
			return this;
		}

		@Override
		public <T> SocketChannel setOption(@NonNull SocketOption<T> option,
				T value) {
			return this;
		}

		@Override
		public <T> T getOption(@NonNull SocketOption<T> option) {
			throw new UnsupportedOperationException();
		}

		@Override
		public Set<SocketOption<?>> supportedOptions() {
			return Set.of();
		}

		@Override
		public SocketChannel shutdownInput() {
			return this;
		}

		@Override
		public SocketChannel shutdownOutput() {
			return this;
		}

		@Override
		public Socket socket() {
			throw new UnsupportedOperationException();
		}

		@Override
		public boolean isConnected() {
			return true;
		}

		@Override
		public boolean isConnectionPending() {
			return false;
		}

		@Override
		public boolean connect(@NonNull SocketAddress remoteAddress) {
			return true;
		}

		@Override
		public boolean finishConnect() {
			return true;
		}

		@Override
		public @Nullable SocketAddress getRemoteAddress() {
			return null;
		}

		@Override
		public @Nullable SocketAddress getLocalAddress() {
			return null;
		}

		@Override
		protected void implCloseSelectableChannel() {
		}

		@Override
		protected void implConfigureBlocking(boolean blocking) {
		}
	}
}
