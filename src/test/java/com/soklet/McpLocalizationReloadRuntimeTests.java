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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.annotation.concurrent.ThreadSafe;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Off-network reload-control evidence: {@code catalogsChanged()} delivers
 * coarse per-family invalidations through the existing subscription machinery,
 * advertisement and filters stay truthful per localized surface, stale
 * pre-rendered terminal state is released, generations fence delivery, and two
 * nodes invalidate independently.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
@Timeout(30)
class McpLocalizationReloadRuntimeTests {
	private static final String LOOPBACK = "127.0.0.1";
	private static final String MCP_PATH = "/localization/reload";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final Duration WAIT = Duration.ofSeconds(5);

	@Test
	void catalogsChangedDeliversOneCoarseInvalidationPerLocalizedFamily() {
		AtomicReference<McpServer> scopedServer = new AtomicReference<>();
		List<String> frames = new ArrayList<>();
		SokletSimulator.run(transports -> {
			McpServer server = server(transports, true, localizer(
					text -> McpLocalizationResult.useDefaultText()));
			scopedServer.set(server);
			return config(server);
		}, simulator -> {
			McpSimulation simulation = simulator.startMcpRequest(
					subscriptionRequest("invalidation",
							"\"toolsListChanged\":true,"
									+ "\"promptsListChanged\":true,"
									+ "\"resourcesListChanged\":true"));
			String acknowledgment = nextFrame(simulation);
			assertTrue(acknowledgment.contains("\"toolsListChanged\":true"),
					acknowledgment);
			assertTrue(acknowledgment.contains("\"promptsListChanged\":true"),
					acknowledgment);
			assertTrue(acknowledgment.contains("\"resourcesListChanged\":true"),
					acknowledgment);

			scopedServer.get().getLocalizationControl().catalogsChanged();

			for (int index = 0; index < 3; ++index)
				frames.add(nextFrame(simulation));
		});

		String all = String.join("\n", frames);
		assertTrue(all.contains("notifications/tools/list_changed"), all);
		assertTrue(all.contains("notifications/prompts/list_changed"), all);
		assertTrue(all.contains("notifications/resources/list_changed"), all);
		// Coarse means coarse: no localized text, locale, key, or revision.
		assertFalse(all.contains("FR:"), all);
		assertFalse(all.contains("fr"), all);
		assertTrue(all.contains("\"io.modelcontextprotocol/subscriptionId\""),
				all);
	}

	@Test
	void familiesWithoutALocalizedCatalogAreNeitherAcknowledgedNorDelivered() {
		// Only the prompt carries localizable text: no tools, and the resource
		// has no title or description.
		McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH)
				.serverInformation(McpImplementation
						.withNameAndVersion("reload-prompts-only", "1.0").build())
				.prompt(McpPromptRegistration.withName("reload.prompt")
						.handler((request, context, features) ->
								McpCompleteResult.fromPromptOutput(
										McpPromptOutput.fromMessages()))
						.title("Prompt title")
						.build())
				.resource(bareResource())
				// RESOURCE_UPDATED-only support: the application does not offer
				// the resources list-change family, and neither does the
				// localizer for this endpoint, so the flag must not be accepted.
				.subscriptions(McpSubscriptionConfig
						.withEventPublisher(
								McpLocalSubscriptionEventPublisher.fromDefaults())
						.notificationTypes(EnumSet.of(
								McpSubscriptionNotificationType.RESOURCE_UPDATED))
						.build())
				.build();
		AtomicReference<McpServer> scopedServer = new AtomicReference<>();
		SokletSimulator.run(transports -> {
			McpServer server = server(transports, endpoint, localizer(
					text -> McpLocalizationResult.useDefaultText()));
			scopedServer.set(server);
			return config(server);
		}, simulator -> {
			McpSimulation simulation = simulator.startMcpRequest(
					subscriptionRequest("prompts-only",
							"\"toolsListChanged\":true,"
									+ "\"promptsListChanged\":true,"
									+ "\"resourcesListChanged\":true"));
			String acknowledgment = nextFrame(simulation);
			assertFalse(acknowledgment.contains("toolsListChanged"),
					acknowledgment);
			assertTrue(acknowledgment.contains("\"promptsListChanged\":true"),
					acknowledgment);
			assertFalse(acknowledgment.contains("resourcesListChanged"),
					acknowledgment);

			scopedServer.get().getLocalizationControl().catalogsChanged();

			String frame = nextFrame(simulation);
			assertTrue(frame.contains("notifications/prompts/list_changed"),
					frame);
			assertTrue(pollFrame(simulation, Duration.ofMillis(150)).isEmpty(),
					"Only the localized prompts family may deliver.");
		});
	}

	@Test
	void discoveryAdvertisesListChangedOnlyForLocalizedCatalogsWithSubscriptions() {
		String localized = discoveryBody(true, localizer(
				text -> McpLocalizationResult.useDefaultText()));
		assertTrue(localized.contains("\"tools\":{\"listChanged\":true}"),
				localized);
		assertTrue(localized.contains("\"prompts\":{\"listChanged\":true}"),
				localized);
		assertTrue(localized.contains("\"resources\":{\"listChanged\":true"),
				localized);

		String unlocalized = discoveryBody(true, null);
		assertTrue(unlocalized.contains("\"tools\":{}"), unlocalized);
		assertTrue(unlocalized.contains("\"prompts\":{}"), unlocalized);

		// Without subscriptions/listen there is no delivery channel, so a
		// localized catalog still advertises nothing.
		String noSubscriptions = discoveryBody(false, localizer(
				text -> McpLocalizationResult.useDefaultText()));
		assertTrue(noSubscriptions.contains("\"tools\":{}"), noSubscriptions);
		assertTrue(noSubscriptions.contains("\"prompts\":{}"), noSubscriptions);
	}

	@Test
	void aStaleLocalizedTerminalIsReleasedByInvalidation() {
		AtomicReference<McpServer> scopedServer = new AtomicReference<>();
		AtomicReference<McpSimulation> escaped = new AtomicReference<>();

		SokletSimulator.run(transports -> {
			McpServer server = server(transports, true, localizer(
					text -> McpLocalizationResult.localized(
							"FR:" + text.getDefaultText())));
			scopedServer.set(server);
			return config(server);
		}, simulator -> {
			McpSimulation simulation = simulator.startMcpRequest(
					subscriptionRequest("stale-terminal",
							"\"resourcesListChanged\":true"));
			escaped.set(simulation);
			nextFrame(simulation);

			// The invalidation clears the pre-rendered localized terminal, so
			// the close that follows publishes canonical text instead of
			// retaining the obsolete translation graph.
			scopedServer.get().getLocalizationControl().catalogsChanged();
			nextFrame(simulation);

			try {
				assertEquals(McpStreamTerminationReason.COMPLETED,
						simulation.awaitCompletion(WAIT).orElseThrow(() ->
								new AssertionError("Timed out.")).getReason());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new AssertionError(e);
			}
		});

		List<String> frames = drain(escaped.get());
		String terminal = frames.get(frames.size() - 1);
		assertTrue(terminal.contains("\"title\":\"Canonical title\""), terminal);
		assertFalse(terminal.contains("FR:"), terminal);
	}

	@Test
	void invalidationDuringTerminalPreRenderCannotInstallTheOldSnapshot() {
		AtomicReference<String> snapshot = new AtomicReference<>("OLD:");
		CountDownLatch contextCaptured = new CountDownLatch(1);
		CountDownLatch releaseContext = new CountDownLatch(1);
		McpLocalizer localizer = McpLocalizer.withFallbackLocale(Locale.ENGLISH)
				.contextProvider(request -> {
					String captured = snapshot.get();
					contextCaptured.countDown();
					await(releaseContext);
					return McpLocalizationContext.withLocale(Locale.CANADA_FRENCH)
							.localizer(text -> McpLocalizationResult.localized(
									captured + text.getDefaultText()))
							.build();
				})
				.build();
		AtomicReference<McpServer> scopedServer = new AtomicReference<>();
		AtomicReference<McpSimulation> escaped = new AtomicReference<>();

		SokletSimulator.run(transports -> {
			McpServer server = server(transports, true, localizer);
			scopedServer.set(server);
			return config(server);
		}, simulator -> {
			McpSimulation simulation = simulator.startMcpRequest(
					subscriptionRequest("open-race",
							"\"resourcesListChanged\":true"));
			escaped.set(simulation);
			try {
				await(contextCaptured);
				snapshot.set("NEW:");
				scopedServer.get().getLocalizationControl().catalogsChanged();
			} finally {
				releaseContext.countDown();
			}
			nextFrame(simulation);
			try {
				assertEquals(McpStreamTerminationReason.COMPLETED,
						simulation.awaitCompletion(WAIT).orElseThrow(() ->
								new AssertionError("Timed out.")).getReason());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new AssertionError(e);
			}
		});

		List<String> frames = drain(escaped.get());
		String terminal = frames.get(frames.size() - 1);
		assertTrue(terminal.contains("\"title\":\"Canonical title\""), terminal);
		assertFalse(terminal.contains("OLD:"), terminal);
	}

	@Test
	void shutdownDuringTerminalPreRenderCannotCommitARejectedSubscription()
			throws Exception {
		CountDownLatch contextCaptured = new CountDownLatch(1);
		CountDownLatch releaseContext = new CountDownLatch(1);
		CountDownLatch contextResumed = new CountDownLatch(1);
		BlockingShutdownExecutorService handlerExecutor =
				new BlockingShutdownExecutorService();
		McpLocalizer localizer = McpLocalizer.withFallbackLocale(Locale.ENGLISH)
				.contextProvider(request -> {
					contextCaptured.countDown();
					await(releaseContext);
					contextResumed.countDown();
					return McpLocalizationContext.withLocale(Locale.CANADA_FRENCH)
							.localizer(text ->
									McpLocalizationResult.useDefaultText())
							.build();
				})
				.build();
		McpServer server = server(true, localizer,
				Optional.of(handlerExecutor), Duration.ofSeconds(2));
		CountDownLatch requestFinished = new CountDownLatch(1);
		AtomicInteger requestFinishes = new AtomicInteger();
		AtomicReference<McpRequestOutcome> requestOutcome = new AtomicReference<>();
		LifecycleObserver observer = new LifecycleObserver() {
			@Override
			public void didFinishMcpRequestHandling(McpRequestContext context,
					McpRequestOutcome outcome, McpJsonRpcError error,
					Duration duration, List<Throwable> throwables) {
				requestFinishes.incrementAndGet();
				requestOutcome.set(outcome);
				requestFinished.countDown();
			}
		};
		Soklet soklet = Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(
						ResourceMethodResolver.fromMethods(Set.of()))
				.lifecycleObserver(observer)
				.internalLifecyclePolicy(new InternalLifecyclePolicy(
						Optional.of(Duration.ofSeconds(5)), Duration.ofSeconds(2),
						Duration.ofSeconds(2), Duration.ofSeconds(1)))
				.build());
		AtomicReference<Throwable> stopFailure = new AtomicReference<>();
		Thread stopThread = new Thread(() -> {
			try {
				soklet.stop();
			} catch (Throwable throwable) {
				stopFailure.set(throwable);
			}
		}, "mcp-localization-stop-race");
		CompletableFuture<HttpResponse<InputStream>> responseFuture = null;
		boolean stopStarted = false;
		boolean clientCanceledBeforeResponseHead = false;

		try {
			soklet.start();
			McpTransportLifecycleAdapter lifecycleAdapter =
					lifecycleAdapter(server);
			McpTransportLifecycleAdapter.Generation lifecycleGeneration =
					(McpTransportLifecycleAdapter.Generation)
							lifecycleAdapter.currentGeneration();
			int port = server.getDiagnostics().getBoundAddress()
					.orElseThrow().getPort();
			Request publicRequest = subscriptionRequest("shutdown-open-race",
					"\"resourcesListChanged\":true");
			String body = new String(publicRequest.getBody().orElseThrow(),
					StandardCharsets.UTF_8);
			HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(
					"http://" + LOOPBACK + ':' + port + MCP_PATH))
					.timeout(WAIT.multipliedBy(2L))
					.header("Content-Type", "application/json; charset=UTF-8")
					.header("Accept", "application/json, text/event-stream")
					.header("MCP-Protocol-Version", PROTOCOL_VERSION)
					.header("Mcp-Method", "subscriptions/listen")
					.POST(HttpRequest.BodyPublishers.ofString(body))
					.build();
			responseFuture = HttpClient.newHttpClient().sendAsync(httpRequest,
					HttpResponse.BodyHandlers.ofInputStream());
			await(contextCaptured);
			stopThread.start();
			stopStarted = true;

			awaitShutdownRequested(lifecycleGeneration);
			assertEquals(0, handlerExecutor.shutdownInterruptions(),
					"Graceful quiesce must not interrupt executor shutdown.");
			assertEquals(0, handlerExecutor.shutdownNowCalls(),
					"Graceful quiesce must not invoke force-style executor shutdown.");
			assertEquals(1L, handlerExecutor.shutdownStarted().getCount(),
					"The active terminal pre-render must unwind before executor shutdown.");
			releaseContext.countDown();
			await(contextResumed);
			// Once terminal pre-render resumes against the fenced generation, let the
			// exact request observation and cooperative executor drain complete.
			await(handlerExecutor.shutdownStarted());
			await(requestFinished);
			handlerExecutor.releaseShutdown();
			assertEquals(1, requestFinishes.get(),
					"The request must publish exactly one terminal observation.");
			assertTrue(EnumSet.of(McpRequestOutcome.COMPLETE,
					McpRequestOutcome.CANCELED).contains(requestOutcome.get()),
					"Semantic completion or shutdown cancellation may win observation, "
							+ "but neither may commit the fenced subscription.");
			stopThread.join(WAIT.toMillis());
			assertFalse(stopThread.isAlive(), "Server stop did not finish.");
			if (!responseFuture.isDone())
				clientCanceledBeforeResponseHead = responseFuture.cancel(true);
			if (stopFailure.get() != null)
				throw new AssertionError("Server stop failed.", stopFailure.get());
			InternalShutdownResult shutdownResult = lifecycleAdapter
					.result(lifecycleGeneration).orElseThrow();
			InternalParticipantShutdownResult participant = shutdownResult
					.participantResult(InternalParticipantKind.MCP).orElseThrow();
			assertTrue(EnumSet.of(InternalShutdownDisposition.GRACEFUL,
					InternalShutdownDisposition.FORCED).contains(
							shutdownResult.disposition()),
					() -> "participant=" + participant.disposition()
							+ ", residual=" + participant.residualActivity()
							+ ", failures=" + participant.failures());
			InternalParticipantShutdownDisposition expectedParticipantDisposition =
					shutdownResult.disposition()
							== InternalShutdownDisposition.GRACEFUL
							? InternalParticipantShutdownDisposition.GRACEFUL_TERMINATION
							: InternalParticipantShutdownDisposition.FORCED_TERMINATION;
			assertEquals(expectedParticipantDisposition,
					participant.disposition(),
					"The participant phase must match the exact shared-deadline winner.");
			assertTrue(participant.residualActivity().isEmpty());
			assertTrue(participant.failures().isEmpty());
			assertEquals(0, handlerExecutor.shutdownNowCalls(),
					"Application executor shutdown remains cooperative during force.");

			if (!clientCanceledBeforeResponseHead) {
				try {
					HttpResponse<InputStream> response = responseFuture
							.orTimeout(WAIT.toMillis(),
									java.util.concurrent.TimeUnit.MILLISECONDS)
							.join();
					try (InputStream ignored = response.body()) {
						assertNotEquals(200, response.statusCode(),
								"Shutdown must not commit an SSE head after acceptance closes.");
					}
				} catch (CompletionException exception) {
					// Shutdown closes an exchange for which no response head was committed.
					assertTrue(exception.getCause() instanceof IOException,
							() -> "Expected a connection failure, but received "
									+ exception.getCause());
				}
			}
		} finally {
			releaseContext.countDown();
			handlerExecutor.releaseShutdown();
			if (stopStarted) {
				try {
					stopThread.join(WAIT.toMillis());
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new AssertionError(e);
				}
			}
			soklet.stop();
			if (responseFuture != null)
				responseFuture.cancel(true);
		}

		assertFalse(stopThread.isAlive(), "Server stop did not finish.");
		if (stopFailure.get() != null)
			throw new AssertionError("Server stop failed.", stopFailure.get());
		assertEquals(0, handlerExecutor.shutdownNowCalls(),
				"MCP force signals exchanges but preserves graceful executor shutdown.");
		assertEquals(1, requestFinishes.get());
		assertTrue(lifecycleAdapter(server).retentionSummary().isEmpty());
		McpServerDiagnostics diagnostics = server.getDiagnostics();
		assertEquals(0, diagnostics.getActiveHandlerExecutions());
		assertEquals(0, diagnostics.getQueuedRequests());
		assertEquals(0, diagnostics.getActiveRequestStreams());
		assertEquals(0, diagnostics.getActiveSubscriptions());
	}

	@Test
	void generationsFenceDeliveryAndDisabledControlStillThrows() {
		McpServer localized = server(true, localizer(
				text -> McpLocalizationResult.useDefaultText()));

		// No active listener or simulator generation: accepted as a no-op.
		localized.getLocalizationControl().catalogsChanged();

		McpServer unlocalized = server(true, null);
		assertFalse(unlocalized.getLocalizationControl().isEnabled());
		assertThrows(IllegalStateException.class,
				() -> unlocalized.getLocalizationControl().catalogsChanged());
	}

	@Test
	void twoNodesInvalidateIndependently() {
		AtomicReference<McpServer> first = new AtomicReference<>();
		AtomicReference<McpServer> second = new AtomicReference<>();

		SokletSimulator.run(firstTransports -> {
			McpServer server = server(firstTransports, true, localizer(
					text -> McpLocalizationResult.useDefaultText()));
			first.set(server);
			return config(server);
		}, firstSimulator ->
				SokletSimulator.run(secondTransports -> {
					McpServer server = server(secondTransports, true, localizer(
							text -> McpLocalizationResult.useDefaultText()));
					second.set(server);
					return config(server);
				}, secondSimulator -> {
					McpSimulation firstSubscription = firstSimulator
							.startMcpRequest(subscriptionRequest("node-one",
									"\"toolsListChanged\":true"));
					McpSimulation secondSubscription = secondSimulator
							.startMcpRequest(subscriptionRequest("node-two",
									"\"toolsListChanged\":true"));
					nextFrame(firstSubscription);
					nextFrame(secondSubscription);

					// The control is a local-server operation: each node's call
					// reaches only its own streams.
					first.get().getLocalizationControl().catalogsChanged();
					assertTrue(nextFrame(firstSubscription)
							.contains("notifications/tools/list_changed"));
					assertTrue(pollFrame(secondSubscription,
							Duration.ofMillis(150)).isEmpty(),
							"Node one's invalidation must not reach node two.");

					second.get().getLocalizationControl().catalogsChanged();
					assertTrue(nextFrame(secondSubscription)
							.contains("notifications/tools/list_changed"));
				}));
	}

	@Test
	void mixedLocalizedEndpointsShareOnePublisherAndFanOutToEveryEndpoint() {
		CountingPublisher publisher = new CountingPublisher();
		McpSubscriptionConfig subscriptions = McpSubscriptionConfig
				.withEventPublisher(publisher)
				.notificationTypes(EnumSet.of(
						McpSubscriptionNotificationType.RESOURCES_LIST_CHANGED))
				.build();
		List<McpEndpoint> endpoints = List.of(
				sharedPublisherEndpoint("/localization/shared-one", "One", subscriptions),
				sharedPublisherEndpoint("/localization/shared-two", "Two", subscriptions),
				sharedPublisherEndpoint("/localization/shared-plain", null, subscriptions));
		SokletSimulator.run(transports -> config(transports.newMcpServerBuilder(0)
				.host(LOOPBACK)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(endpoints))
				.admissionController(McpAdmissionController.acceptAllInstance())
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(LOOPBACK))
				.localizer(localizer(text ->
						McpLocalizationResult.useDefaultText()))
				.build()), simulator -> {
			List<McpSimulation> simulations = List.of(
					simulator.startMcpRequest(request(
							"/localization/shared-one", "shared-one",
							"subscriptions/listen",
							",\"notifications\":{\"resourcesListChanged\":true}")),
					simulator.startMcpRequest(request(
							"/localization/shared-two", "shared-two",
							"subscriptions/listen",
							",\"notifications\":{\"resourcesListChanged\":true}")),
					simulator.startMcpRequest(request(
							"/localization/shared-plain", "shared-plain",
							"subscriptions/listen",
							",\"notifications\":{\"resourcesListChanged\":true}")));
			for (McpSimulation simulation : simulations)
				assertTrue(nextFrame(simulation)
						.contains("\"resourcesListChanged\":true"));
			assertEquals(1, publisher.subscriptionCount(),
					"Shared application publisher must register once per server generation.");

			publisher.publishResourcesListChanged();
			for (McpSimulation simulation : simulations)
				assertTrue(nextFrame(simulation)
						.contains("notifications/resources/list_changed"));
		});

		assertEquals(1, publisher.closeCount());
	}

	private static McpEndpoint sharedPublisherEndpoint(String path,
			String title, McpSubscriptionConfig subscriptions) {
		McpImplementation.Builder information = McpImplementation
				.withNameAndVersion(path.substring(path.lastIndexOf('/') + 1), "1.0");
		if (title != null)
			information.title(title);
		return McpEndpoint.withPath(path)
				.serverInformation(information.build())
				.resource(McpResourceRegistration.withUriAndName(
						URI.create("shared:" + path), "shared")
						.handler((request, resource, features) ->
								McpCompleteResult.fromResourceOutput(
										McpResourceOutput.builder()
												.content(McpTextResourceContents
														.withUriAndText(resource.getUri(),
																"unused")
														.build())
												.build()))
						.build())
				.subscriptions(subscriptions)
				.build();
	}

	private static final class CountingPublisher
			implements McpSubscriptionEventPublisher {
		private final AtomicInteger subscriptions = new AtomicInteger();
		private final AtomicInteger closes = new AtomicInteger();
		private final CopyOnWriteArrayList<McpSubscriptionEventListener> listeners =
				new CopyOnWriteArrayList<>();

		@Override
		public McpSubscriptionEventRegistration subscribe(
				McpSubscriptionEventListener listener) {
			java.util.Objects.requireNonNull(listener);
			listeners.add(listener);
			subscriptions.incrementAndGet();
			AtomicBoolean closed = new AtomicBoolean();
			return () -> {
				if (closed.compareAndSet(false, true)) {
					listeners.remove(listener);
					closes.incrementAndGet();
				}
			};
		}

		@Override
		public void publish(McpSubscriptionEvent event) {
			java.util.Objects.requireNonNull(event);
			for (McpSubscriptionEventListener listener : listeners)
				listener.onEvent(event);
		}

		private int subscriptionCount() {
			return subscriptions.get();
		}

		private int closeCount() {
			return closes.get();
		}
	}

	private static final class BlockingShutdownExecutorService
			extends java.util.concurrent.AbstractExecutorService {
		private final java.util.concurrent.ExecutorService delegate =
				java.util.concurrent.Executors.newSingleThreadExecutor();
		private final CountDownLatch shutdownStarted = new CountDownLatch(1);
		private final CountDownLatch releaseShutdown = new CountDownLatch(1);
		private final AtomicInteger shutdownInterruptions = new AtomicInteger();
		private final AtomicInteger shutdownNowCalls = new AtomicInteger();

		@Override
		public void shutdown() {
			this.shutdownStarted.countDown();
			try {
				this.releaseShutdown.await();
			} catch (InterruptedException exception) {
				this.shutdownInterruptions.incrementAndGet();
				Thread.currentThread().interrupt();
			}
			this.delegate.shutdown();
		}

		@Override
		public List<Runnable> shutdownNow() {
			this.shutdownNowCalls.incrementAndGet();
			this.releaseShutdown.countDown();
			return this.delegate.shutdownNow();
		}

		@Override
		public boolean isShutdown() {
			return this.delegate.isShutdown();
		}

		@Override
		public boolean isTerminated() {
			return this.delegate.isTerminated();
		}

		@Override
		public boolean awaitTermination(long timeout,
				java.util.concurrent.TimeUnit unit) throws InterruptedException {
			return this.delegate.awaitTermination(timeout, unit);
		}

		@Override
		public void execute(Runnable command) {
			this.delegate.execute(command);
		}

		private CountDownLatch shutdownStarted() {
			return this.shutdownStarted;
		}

		private void releaseShutdown() {
			this.releaseShutdown.countDown();
		}

		private int shutdownInterruptions() {
			return this.shutdownInterruptions.get();
		}

		private int shutdownNowCalls() {
			return this.shutdownNowCalls.get();
		}
	}

	private static McpLocalizer localizer(
			java.util.function.Function<McpLocalizableText,
					McpLocalizationResult> provider) {
		return McpLocalizer.withFallbackLocale(Locale.ENGLISH)
				.contextProvider(request -> McpLocalizationContext
						.withLocale(Locale.CANADA_FRENCH)
						.localizer(provider)
						.build())
				.build();
	}

	private static McpServer server(boolean subscriptions,
			McpLocalizer localizer) {
		return server(subscriptions, localizer, Optional.empty());
	}

	private static McpServer server(boolean subscriptions,
			McpLocalizer localizer,
			Optional<java.util.concurrent.ExecutorService> handlerExecutor) {
		return server(subscriptions, localizer, handlerExecutor, Optional.empty());
	}

	private static McpServer server(boolean subscriptions,
			McpLocalizer localizer,
			Optional<java.util.concurrent.ExecutorService> handlerExecutor,
			Duration shutdownTimeout) {
		return server(subscriptions, localizer, handlerExecutor,
				Optional.of(shutdownTimeout));
	}

	private static McpServer server(boolean subscriptions,
			McpLocalizer localizer,
			Optional<java.util.concurrent.ExecutorService> handlerExecutor,
			Optional<Duration> shutdownTimeout) {
		return server(reloadEndpoint(subscriptions), localizer, handlerExecutor,
				shutdownTimeout);
	}

	private static McpServer server(SimulatorTransports transports,
			boolean subscriptions, McpLocalizer localizer) {
		return server(transports, reloadEndpoint(subscriptions), localizer);
	}

	private static McpEndpoint reloadEndpoint(boolean subscriptions) {
		McpEndpoint.Builder builder = McpEndpoint.withPath(MCP_PATH)
				.serverInformation(McpImplementation
						.withNameAndVersion("localization-reload", "1.0")
						.title("Canonical title")
						.description("Canonical description")
						.build())
				.tool(McpToolRegistration.withName("reload.tool")
						.jsonArguments()
						.handler((request, arguments, features) ->
								McpCompleteResult.fromToolText("unused"))
						.title("Tool title")
						.build())
				.prompt(McpPromptRegistration.withName("reload.prompt")
						.handler((request, context, features) ->
								McpCompleteResult.fromPromptOutput(
										McpPromptOutput.fromMessages()))
						.title("Prompt title")
						.build())
				.resource(McpResourceRegistration.withUriAndName(
						URI.create("reload://text"), "text")
						.handler((request, resource, features) ->
								McpCompleteResult.fromResourceOutput(
										McpResourceOutput.builder()
												.content(McpTextResourceContents
														.withUriAndText(URI.create(
																"reload://text"),
																"unused")
														.build())
												.build()))
						.title("Resource title")
						.build());

		if (subscriptions)
			builder.subscriptions(subscriptions());

		return builder.build();
	}

	private static McpServer server(McpEndpoint endpoint,
			McpLocalizer localizer) {
		return server(endpoint, localizer, Optional.empty());
	}

	private static McpServer server(McpEndpoint endpoint,
			McpLocalizer localizer,
			Optional<java.util.concurrent.ExecutorService> handlerExecutor) {
		return server(endpoint, localizer, handlerExecutor, Optional.empty());
	}

	private static McpServer server(McpEndpoint endpoint,
			McpLocalizer localizer,
			Optional<java.util.concurrent.ExecutorService> handlerExecutor,
			Optional<Duration> shutdownTimeout) {
		return server(McpServer.withPort(0), endpoint, localizer, handlerExecutor,
				shutdownTimeout);
	}

	private static McpServer server(SimulatorTransports transports,
			McpEndpoint endpoint, McpLocalizer localizer) {
		return server(transports.newMcpServerBuilder(0), endpoint, localizer,
				Optional.empty(), Optional.empty());
	}

	private static McpServer server(McpServer.Builder builder,
			McpEndpoint endpoint, McpLocalizer localizer,
			Optional<java.util.concurrent.ExecutorService> handlerExecutor,
			Optional<Duration> shutdownTimeout) {
		builder
				.host(LOOPBACK)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.admissionController(McpAdmissionController.acceptAllInstance())
				.requestRateLimiter(context -> McpRateLimitDecision.allowed())
				.toolRateLimiter(context -> McpRateLimitDecision.allowed())
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(LOOPBACK))
				.maximumSubscriptionDuration(Duration.ofMillis(400));

		if (localizer != null)
			builder.localizer(localizer);
		shutdownTimeout.ifPresent(builder::shutdownTimeout);
		handlerExecutor.ifPresent(executor -> builder
				.requestHandlerExecutorServiceSupplier(() -> executor));

		return builder.build();
	}

	private static McpSubscriptionConfig subscriptions() {
		return McpSubscriptionConfig
				.withEventPublisher(McpLocalSubscriptionEventPublisher.fromDefaults())
				.notificationTypes(EnumSet.of(
						McpSubscriptionNotificationType.RESOURCES_LIST_CHANGED))
				.build();
	}

	private static McpResourceRegistration bareResource() {
		return McpResourceRegistration.withUriAndName(
				URI.create("reload://bare"), "bare")
				.handler((request, resource, features) ->
						McpCompleteResult.fromResourceOutput(
								McpResourceOutput.builder()
										.content(McpTextResourceContents
												.withUriAndText(URI.create(
														"reload://bare"),
														"unused")
												.build())
										.build()))
				.build();
	}

	private static SokletConfig config(McpServer server) {
		return SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.build();
	}

	private static String discoveryBody(boolean subscriptions,
			McpLocalizer localizer) {
		AtomicReference<String> captured = new AtomicReference<>();

		SokletSimulator.run(transports -> config(
				server(transports, subscriptions, localizer)), simulator -> {
			McpSimulation simulation = simulator.startMcpRequest(
					request("discover", "server/discover", ""));

			try {
				McpSimulationResponse response = simulation.awaitResponse(WAIT)
						.orElseThrow(() -> new AssertionError("Timed out."));
				captured.set(new String(response.getBody().orElseThrow(),
						StandardCharsets.UTF_8));
				simulation.awaitCompletion(WAIT);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new AssertionError(e);
			}
		});

		return captured.get();
	}

	private static String nextFrame(McpSimulation simulation) {
		try {
			return new String(simulation.nextStreamItem(WAIT)
					.orElseThrow(() -> new AssertionError("Timed out."))
					.getEncodedBytes(), StandardCharsets.UTF_8);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AssertionError(e);
		}
	}

	private static Optional<McpSimulationStreamItem> pollFrame(
			McpSimulation simulation, Duration timeout) {
		try {
			return simulation.nextStreamItem(timeout);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AssertionError(e);
		}
	}

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(WAIT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS))
				throw new AssertionError("Timed out.");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AssertionError(e);
		}
	}

	private static void awaitShutdownRequested(
			McpTransportLifecycleAdapter.Generation generation) {
		long deadline = System.nanoTime() + WAIT.toNanos();
		while (!generation.shutdownRequested()
				&& System.nanoTime() - deadline < 0L)
			Thread.onSpinWait();
		assertTrue(generation.shutdownRequested(),
				"MCP shutdown intent was not published before the test deadline.");
	}

	private static McpTransportLifecycleAdapter lifecycleAdapter(
			McpServer server) throws ReflectiveOperationException {
		Field adapterField = DefaultMcpServer.class.getDeclaredField(
				"lifecycleAdapter");
		adapterField.setAccessible(true);
		return (McpTransportLifecycleAdapter) adapterField.get(server);
	}

	private static List<String> drain(McpSimulation simulation) {
		List<String> frames = new ArrayList<>();

		try {
			Optional<McpSimulationStreamItem> item;
			while ((item = simulation.nextStreamItem(Duration.ZERO)).isPresent())
				frames.add(new String(item.orElseThrow().getEncodedBytes(),
						StandardCharsets.UTF_8));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AssertionError(e);
		}

		assertTrue(!frames.isEmpty(), "Expected at least the terminal frame.");
		return frames;
	}

	private static Request subscriptionRequest(String id, String notifications) {
		return request(id, "subscriptions/listen",
				",\"notifications\":{" + notifications + "}");
	}

	private static Request request(String id, String method,
			String additionalParameters) {
		return request(MCP_PATH, id, method, additionalParameters);
	}

	private static Request request(String path, String id, String method,
			String additionalParameters) {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + id
				+ "\",\"method\":\"" + method + "\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}}"
				+ additionalParameters + "}}";
		Map<String, Set<String>> headers = new LinkedHashMap<>();
		headers.put("Host", Set.of(LOOPBACK + ":0"));
		headers.put("Content-Type", Set.of("application/json; charset=UTF-8"));
		headers.put("Accept", Set.of("application/json, text/event-stream"));
		headers.put("MCP-Protocol-Version", Set.of(PROTOCOL_VERSION));
		headers.put("Mcp-Method", Set.of(method));
		return Request.withPath(HttpMethod.POST, path)
				.headers(headers)
				.body(body.getBytes(StandardCharsets.UTF_8))
				.build();
	}
}
