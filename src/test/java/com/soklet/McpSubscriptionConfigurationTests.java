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

import com.soklet.annotation.McpServerEndpoint;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Public value and attachment contracts for provisional MCP subscriptions.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public class McpSubscriptionConfigurationTests {
	private static final AtomicBoolean GENERATED_ENDPOINT_INITIALIZED =
			new AtomicBoolean();

	@Test
	public void configurationDefensivelyCopiesNotificationTypes() {
		McpSubscriptionEventPublisher publisher =
				McpLocalSubscriptionEventPublisher.fromDefaults();
		EnumSet<McpSubscriptionNotificationType> mutableTypes = EnumSet.of(
				McpSubscriptionNotificationType.RESOURCE_UPDATED,
				McpSubscriptionNotificationType.RESOURCES_LIST_CHANGED);

		McpSubscriptionConfig configuration = McpSubscriptionConfig
				.withEventPublisher(publisher)
				.notificationTypes(mutableTypes)
				.build();
		mutableTypes.clear();

		Assertions.assertSame(publisher, configuration.getEventPublisher());
		Assertions.assertEquals(List.of(
				McpSubscriptionNotificationType.RESOURCES_LIST_CHANGED,
				McpSubscriptionNotificationType.RESOURCE_UPDATED),
				List.copyOf(configuration.getNotificationTypes()));
		Assertions.assertThrows(UnsupportedOperationException.class,
				() -> configuration.getNotificationTypes().clear());
	}

	@Test
	public void configurationValidatesPublisherAndNotificationTypesAtomically() {
		Assertions.assertThrows(NullPointerException.class,
				() -> McpSubscriptionConfig.withEventPublisher(null));
		McpSubscriptionConfig.Builder builder = McpSubscriptionConfig
				.withEventPublisher(
						McpLocalSubscriptionEventPublisher.fromDefaults());
		Assertions.assertThrows(IllegalStateException.class, builder::build);
		Assertions.assertThrows(NullPointerException.class,
				() -> builder.notificationType(null));
		Assertions.assertThrows(NullPointerException.class,
				() -> builder.notificationTypes(null));

		builder.notificationType(
				McpSubscriptionNotificationType.RESOURCES_LIST_CHANGED);
		Set<McpSubscriptionNotificationType> invalidTypes = new HashSet<>();
		invalidTypes.add(McpSubscriptionNotificationType.RESOURCE_UPDATED);
		invalidTypes.add(null);
		Assertions.assertThrows(NullPointerException.class,
				() -> builder.notificationTypes(invalidTypes));
		Assertions.assertEquals(Set.of(
				McpSubscriptionNotificationType.RESOURCES_LIST_CHANGED),
				builder.build().getNotificationTypes());
	}

	@Test
	public void localPublisherBroadcastsAndSubscriptionsCloseIdempotently() {
		McpLocalSubscriptionEventPublisher publisher =
				McpLocalSubscriptionEventPublisher.fromDefaults();
		List<McpSubscriptionEvent> firstEvents = new ArrayList<>();
		List<McpSubscriptionEvent> secondEvents = new ArrayList<>();
		McpSubscriptionEventSubscription first = publisher.subscribe(
				firstEvents::add);
		McpSubscriptionEventSubscription second = publisher.subscribe(
				secondEvents::add);

		publisher.publishResourcesListChanged();
		first.close();
		first.close();
		URI resourceUri = URI.create("urn:soklet:resource:1");
		publisher.publishResourceUpdated(resourceUri);

		Assertions.assertEquals(List.of(
				McpSubscriptionEvent.resourcesListChanged()), firstEvents);
		Assertions.assertEquals(List.of(
				McpSubscriptionEvent.resourcesListChanged(),
				McpSubscriptionEvent.resourceUpdated(resourceUri)), secondEvents);
		second.close();
	}

	@Test
	public void localPublisherAttemptsEveryListenerBeforeRethrowingFailure() {
		McpLocalSubscriptionEventPublisher publisher =
				McpLocalSubscriptionEventPublisher.fromDefaults();
		IllegalStateException failure = new IllegalStateException("listener failed");
		AtomicInteger successfulDeliveries = new AtomicInteger();
		McpSubscriptionEventSubscription failing = publisher.subscribe(event -> {
			throw failure;
		});
		McpSubscriptionEventSubscription succeeding = publisher.subscribe(
				event -> successfulDeliveries.incrementAndGet());

		Assertions.assertSame(failure, Assertions.assertThrows(
				IllegalStateException.class,
				publisher::publishResourcesListChanged));
		Assertions.assertEquals(1, successfulDeliveries.get());
		failing.close();
		succeeding.close();
		Assertions.assertThrows(NullPointerException.class,
				() -> publisher.subscribe(null));
		Assertions.assertThrows(NullPointerException.class,
				() -> publisher.publish(null));
	}

	@Test
	public void localPublisherBroadcastsConcurrentEventsToEveryCurrentListener()
			throws Exception {
		McpLocalSubscriptionEventPublisher publisher =
				McpLocalSubscriptionEventPublisher.fromDefaults();
		List<McpSubscriptionEvent> firstEvents = new CopyOnWriteArrayList<>();
		List<McpSubscriptionEvent> secondEvents = new CopyOnWriteArrayList<>();
		McpSubscriptionEventSubscription first = publisher.subscribe(
				firstEvents::add);
		McpSubscriptionEventSubscription second = publisher.subscribe(
				secondEvents::add);
		int eventCount = 32;
		Set<McpSubscriptionEvent> expectedEvents = new HashSet<>();
		for (int index = 0; index < eventCount; index++)
			expectedEvents.add(McpSubscriptionEvent.resourceUpdated(
					URI.create("test://concurrent/" + index)));
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(8);

		try {
			List<Future<?>> futures = new ArrayList<>();
			for (int index = 0; index < eventCount; index++) {
				URI uri = URI.create("test://concurrent/" + index);
				futures.add(executor.submit(() -> {
					start.await();
					publisher.publishResourceUpdated(uri);
					return null;
				}));
			}
			start.countDown();
			for (Future<?> future : futures)
				future.get(5, TimeUnit.SECONDS);
		} finally {
			executor.shutdownNow();
			Assertions.assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
		}

		Assertions.assertEquals(eventCount, firstEvents.size());
		Assertions.assertEquals(eventCount, secondEvents.size());
		Assertions.assertEquals(expectedEvents, new HashSet<>(firstEvents));
		Assertions.assertEquals(expectedEvents, new HashSet<>(secondEvents));
		first.close();
		publisher.publishResourcesListChanged();
		Assertions.assertEquals(eventCount, firstEvents.size());
		Assertions.assertEquals(eventCount + 1, secondEvents.size());
		second.close();
	}

	@Test
	public void localPublisherCloseAllowsInFlightDeliveryAndFencesLaterEvents()
			throws Exception {
		McpLocalSubscriptionEventPublisher publisher =
				McpLocalSubscriptionEventPublisher.fromDefaults();
		CountDownLatch listenerEntered = new CountDownLatch(1);
		CountDownLatch releaseListener = new CountDownLatch(1);
		AtomicInteger deliveries = new AtomicInteger();
		McpSubscriptionEventSubscription subscription = publisher.subscribe(event -> {
			deliveries.incrementAndGet();
			listenerEntered.countDown();
			try {
				if (!releaseListener.await(5, TimeUnit.SECONDS))
					throw new AssertionError("Timed out waiting to release listener");
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new AssertionError(exception);
			}
		});
		ExecutorService executor = Executors.newSingleThreadExecutor();

		try {
			Future<?> publish = executor.submit(
					publisher::publishResourcesListChanged);
			Assertions.assertTrue(listenerEntered.await(5, TimeUnit.SECONDS));
			subscription.close();
			Assertions.assertFalse(publish.isDone());
			releaseListener.countDown();
			publish.get(5, TimeUnit.SECONDS);

			publisher.publishResourcesListChanged();
			Assertions.assertEquals(1, deliveries.get());
		} finally {
			releaseListener.countDown();
			subscription.close();
			executor.shutdownNow();
			Assertions.assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
		}
	}

	@Test
	public void publicPublisherSurfaceContainsOnlyResourceEventFamilies() {
		Assertions.assertArrayEquals(new McpSubscriptionNotificationType[]{
				McpSubscriptionNotificationType.RESOURCES_LIST_CHANGED,
				McpSubscriptionNotificationType.RESOURCE_UPDATED
		}, McpSubscriptionNotificationType.values());
		Assertions.assertEquals(Set.of(
				McpSubscriptionEvent.ResourcesListChanged.class,
				McpSubscriptionEvent.ResourceUpdated.class),
				Set.of(McpSubscriptionEvent.class.getPermittedSubclasses()));
		Assertions.assertEquals(Set.of(
				"publish(McpSubscriptionEvent)",
				"publishResourceUpdated(URI)",
				"publishResourcesListChanged()",
				"subscribe(McpSubscriptionEventListener)"),
				Arrays.stream(McpSubscriptionEventPublisher.class
						.getDeclaredMethods())
						.map(method -> method.getName() + "("
								+ Arrays.stream(method.getParameterTypes())
								.map(Class::getSimpleName)
								.collect(Collectors.joining(",")) + ")")
						.collect(Collectors.toSet()));
	}

	@Test
	public void resourceUpdatedEventRequiresAbsoluteNormalizedAsciiUri() {
		URI uri = URI.create(
				"https://example.com/resources/caf%C3%A9");
		McpSubscriptionEvent.ResourceUpdated event =
				McpSubscriptionEvent.resourceUpdated(uri);

		Assertions.assertEquals(uri, event.resourceUri());
		Assertions.assertEquals(uri,
				new McpSubscriptionEvent.ResourceUpdated(uri).resourceUri());
		URI sensitiveUri = URI.create(
				"https://user:secret@example.com/resources/1?token=secret-token");
		McpSubscriptionEvent.ResourceUpdated sensitiveEvent =
				McpSubscriptionEvent.resourceUpdated(sensitiveUri);
		Assertions.assertEquals(
				"ResourceUpdated{resourceUri=<redacted>}",
				sensitiveEvent.toString());
		Assertions.assertFalse(sensitiveEvent.toString().contains("secret"));
		Assertions.assertThrows(NullPointerException.class,
				() -> McpSubscriptionEvent.resourceUpdated(null));
		Assertions.assertThrows(NullPointerException.class,
				() -> new McpSubscriptionEvent.ResourceUpdated(null));

		for (URI invalid : List.of(
				URI.create("resources/1"),
				URI.create("https://example.com/a/../resources/1"),
				URI.create("https://example.com/resources/café"))) {
			Assertions.assertThrows(IllegalArgumentException.class,
					() -> McpSubscriptionEvent.resourceUpdated(invalid),
					invalid.toString());
			Assertions.assertThrows(IllegalArgumentException.class,
					() -> new McpSubscriptionEvent.ResourceUpdated(invalid),
					invalid.toString());
		}
	}

	@Test
	public void endpointStoresTheImmutableConfigurationByReference() {
		McpSubscriptionConfig first = configuration(
				McpSubscriptionNotificationType.RESOURCES_LIST_CHANGED);
		McpSubscriptionConfig second = configuration(
				McpSubscriptionNotificationType.RESOURCE_UPDATED);
		McpEndpoint withoutSubscriptions = endpoint("/without");
		McpEndpoint endpoint = McpEndpoint.withPath("/mcp")
				.serverInformation(serverInformation())
				.subscriptions(first)
				.subscriptions(second)
				.build();

		Assertions.assertTrue(withoutSubscriptions.getSubscriptions().isEmpty());
		Assertions.assertSame(second, endpoint.getSubscriptions().orElseThrow());
		Assertions.assertThrows(NullPointerException.class,
				() -> McpEndpoint.withPath("/mcp")
						.subscriptions(null));
	}

	@Test
	public void resolverOverlayCopiesOnlyTheSelectedGeneratedEndpoint() {
		McpToolRegistration<McpJsonObject> tool = McpToolRegistration
				.withName("copy-tool")
				.jsonArguments()
				.handler((request, call, features) -> {
					throw new AssertionError("The copy fixture must not execute.");
				})
				.build();
		McpPromptRegistration prompt = McpPromptRegistration
				.withName("copy-prompt")
				.handler((request, get, features) -> {
					throw new AssertionError("The copy fixture must not execute.");
				})
				.build();
		McpResourceRegistration resource = McpResourceRegistration
				.withUriAndName(URI.create("test://copy-resource"),
						"copy-resource")
				.handler((request, read, features) -> {
					throw new AssertionError("The copy fixture must not execute.");
				})
				.build();
		McpResourceListHandler resourceListHandler = (request, list, features) -> {
			throw new AssertionError("The copy fixture must not execute.");
		};
		McpCachePolicy resourcesListCachePolicy =
				McpCachePolicy.fromPrivateTimeToLive(Duration.ofSeconds(7));
		McpCachePolicy resourceTemplatesListCachePolicy =
				McpCachePolicy.fromPublicTimeToLive(Duration.ofSeconds(11));
		McpRateLimiter directToolRateLimiter = context ->
				McpRateLimitDecision.fromAllowed();
		McpEndpoint generated = McpEndpoint.withPath("/generated")
				.serverInformation(serverInformation())
				.includeServerInformation(false)
				.instructions("generated instructions")
				.tool(tool)
				.prompt(prompt)
				.resource(resource)
				.resourceListHandler(resourceListHandler)
				.resourcesListCachePolicy(resourcesListCachePolicy)
				.resourceTemplatesListCachePolicy(
						resourceTemplatesListCachePolicy)
				.toolRateLimiter(directToolRateLimiter)
				.build();
		McpEndpoint namedLimiter = McpEndpoint.withPath("/named-limiter")
				.serverInformation(serverInformation())
				.toolRateLimiter("named-limiter")
				.build();
		McpEndpoint other = endpoint("/other");
		Map<Class<?>, McpEndpoint> generatedEndpoints = new LinkedHashMap<>();
		generatedEndpoints.put(GeneratedEndpoint.class, generated);
		generatedEndpoints.put(NamedLimiterGeneratedEndpoint.class,
				namedLimiter);
		McpHandlerResolver resolver = new DefaultMcpHandlerResolver(
				generatedEndpoints).withEndpoint(other);
		McpSubscriptionConfig subscriptions = configuration(
				McpSubscriptionNotificationType.RESOURCE_UPDATED);
		McpSubscriptionConfig namedSubscriptions = configuration(
				McpSubscriptionNotificationType.RESOURCES_LIST_CHANGED);

		Assertions.assertFalse(GENERATED_ENDPOINT_INITIALIZED.get());
		McpHandlerResolver directOverlaid = resolver.withSubscriptions(
				GeneratedEndpoint.class, subscriptions);
		McpHandlerResolver overlaid = directOverlaid.withSubscriptions(
				NamedLimiterGeneratedEndpoint.class, namedSubscriptions);
		Assertions.assertFalse(GENERATED_ENDPOINT_INITIALIZED.get());

		McpEndpoint replaced = overlaid.getEndpoints().get(0);
		McpEndpoint namedReplaced = overlaid.getEndpoints().get(1);
		Assertions.assertEquals(List.of(generated, namedLimiter, other),
				resolver.getEndpoints());
		Assertions.assertTrue(generated.getSubscriptions().isEmpty());
		Assertions.assertTrue(namedLimiter.getSubscriptions().isEmpty());
		Assertions.assertNotSame(generated, replaced);
		Assertions.assertNotSame(namedLimiter, namedReplaced);
		Assertions.assertSame(other, overlaid.getEndpoints().get(2));
		Assertions.assertEquals(generated.getPath(), replaced.getPath());
		Assertions.assertSame(generated.getServerInformation(),
				replaced.getServerInformation());
		Assertions.assertEquals(generated.isServerInformationIncluded(),
				replaced.isServerInformationIncluded());
		Assertions.assertEquals(generated.getInstructions(),
				replaced.getInstructions());
		Assertions.assertSame(generated.getTools(), replaced.getTools());
		Assertions.assertSame(generated.getPrompts(), replaced.getPrompts());
		Assertions.assertSame(generated.getResources(), replaced.getResources());
		Assertions.assertEquals(generated.getResourceListHandler(),
				replaced.getResourceListHandler());
		Assertions.assertSame(generated.getResourcesListCachePolicy(),
				replaced.getResourcesListCachePolicy());
		Assertions.assertSame(generated.getResourceTemplatesListCachePolicy(),
				replaced.getResourceTemplatesListCachePolicy());
		Assertions.assertEquals(generated.getToolRateLimiterName(),
				replaced.getToolRateLimiterName());
		Assertions.assertEquals(generated.getToolRateLimiter(),
				replaced.getToolRateLimiter());
		Assertions.assertSame(subscriptions,
				replaced.getSubscriptions().orElseThrow());
		Assertions.assertEquals(namedLimiter.getPath(), namedReplaced.getPath());
		Assertions.assertEquals(namedLimiter.getToolRateLimiterName(),
				namedReplaced.getToolRateLimiterName());
		Assertions.assertEquals(namedLimiter.getToolRateLimiter(),
				namedReplaced.getToolRateLimiter());
		Assertions.assertSame(namedSubscriptions,
				namedReplaced.getSubscriptions().orElseThrow());
		Assertions.assertSame(replaced,
				directOverlaid.getEndpoints().get(0));
		Assertions.assertSame(namedLimiter,
				directOverlaid.getEndpoints().get(1));
		Assertions.assertThrows(UnsupportedOperationException.class,
				() -> overlaid.getEndpoints().clear());
	}

	@Test
	public void resolverOverlayRequiresAnOwnedGeneratedEndpoint() {
		McpSubscriptionConfig subscriptions = configuration(
				McpSubscriptionNotificationType.RESOURCES_LIST_CHANGED);
		McpHandlerResolver programmatic = McpHandlerResolver.fromEndpoints(
				List.of(endpoint("/generated")));
		McpEndpoint generatedEndpoint = endpoint("/generated");
		McpHandlerResolver generated = new DefaultMcpHandlerResolver(
				Map.of(GeneratedEndpoint.class, generatedEndpoint));

		Assertions.assertThrows(IllegalArgumentException.class,
				() -> programmatic.withSubscriptions(GeneratedEndpoint.class,
						subscriptions));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> generated.withSubscriptions(MissingGeneratedEndpoint.class,
						subscriptions));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> generated.withSubscriptions(SamePathImpostorEndpoint.class,
						subscriptions));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> generated.withSubscriptions(String.class, subscriptions));
		Assertions.assertThrows(NullPointerException.class,
				() -> generated.withSubscriptions(null, subscriptions));
		Assertions.assertThrows(NullPointerException.class,
				() -> generated.withSubscriptions(GeneratedEndpoint.class, null));
	}

	private static McpSubscriptionConfig configuration(
			McpSubscriptionNotificationType type) {
		return McpSubscriptionConfig.withEventPublisher(
				McpLocalSubscriptionEventPublisher.fromDefaults())
				.notificationType(type)
				.build();
	}

	private static McpImplementation serverInformation() {
		return McpImplementation.withNameAndVersion("test-server", "3.6.0")
				.build();
	}

	private static McpEndpoint endpoint(String path) {
		return McpEndpoint.withPath(path)
				.serverInformation(serverInformation())
				.build();
	}

	@McpServerEndpoint(path = "/generated//", name = "generated", version = "1")
	public static final class GeneratedEndpoint {
		static {
			GENERATED_ENDPOINT_INITIALIZED.set(true);
		}
	}

	@McpServerEndpoint(path = "/missing", name = "missing", version = "1")
	public static final class MissingGeneratedEndpoint {
	}

	@McpServerEndpoint(path = "/generated", name = "impostor", version = "1")
	public static final class SamePathImpostorEndpoint {
	}

	@McpServerEndpoint(path = "/named-limiter", name = "named", version = "1")
	public static final class NamedLimiterGeneratedEndpoint {
	}
}
