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
import com.soklet.McpAdmissionIdentity;
import com.soklet.McpEndpoint;
import com.soklet.McpImplementation;
import com.soklet.McpRequestContext;
import com.soklet.McpRequestOutcome;
import com.soklet.McpStreamTerminationReason;
import com.soklet.McpSubscriptionAuthorization;
import com.soklet.McpSubscriptionAuthorizer;
import com.soklet.Request;
import com.soklet.StreamTerminationReason;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.annotation.concurrent.NotThreadSafe;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Deterministic subscription-deadline coverage across {@link System#nanoTime()}
 * wraparound.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@NotThreadSafe
@Timeout(60)
public class McpSubscriptionDeadlineWraparoundTests {
	private static final String TOOL_NAME = "wrap.stable";
	private static final String CONDITIONAL_TOOL_NAME = "wrap.conditional";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final Duration WAIT = Duration.ofSeconds(5);
	private static final long INITIAL_NANOS = Long.MAX_VALUE
			- Duration.ofSeconds(2).toNanos();
	private static final Instant INITIAL_WALL_TIME =
			Instant.parse("2026-09-18T12:00:00Z");
	private static final McpEndpoint PUBLIC_ENDPOINT = McpEndpoint.withPath("/mcp",
			McpImplementation.withNameAndVersion(
					"subscription-deadline-wrap-test", "4.0.0").build()).build();

	@Test
	public void catalogProjectionDeadlineRemainsOrderedAcrossNanoTimeWrap()
			throws Exception {
		WrapClock clock = new WrapClock();
		TestEventSource events = new TestEventSource();
		AtomicBoolean conditionalVisible = new AtomicBoolean();
		AtomicBoolean advanceProjection = new AtomicBoolean();
		McpServerRuntimeBridge.CatalogAccessAdapter catalog = ignored ->
				new McpServerRuntimeBridge.CatalogAccessSession() {
					@Override
					public boolean isToolAccessible(@NonNull String toolName) {
						if (CONDITIONAL_TOOL_NAME.equals(toolName)
								&& advanceProjection.compareAndSet(true, false))
							clock.advance(Duration.ofSeconds(3));
						return TOOL_NAME.equals(toolName)
								|| conditionalVisible.get();
					}

					@Override
					public boolean isPromptAccessible(@NonNull String promptName) {
						return true;
					}

					@Override
					@NonNull
					public Optional<com.soklet.@NonNull McpLocalizationContext>
							localizationContext() {
						return Optional.empty();
					}
				};
		McpSubscriptionAuthorizer authorizer = (context, features) ->
				allowed(clock.instant().plus(Duration.ofHours(1)));
		RecordingObservation observation = new RecordingObservation();
		McpHttpServerRuntime runtime = runtime(clock, events,
				McpResourceNotificationType.TOOLS_LIST_CHANGED,
				Optional.of(catalog), authorizer, observation);
		McpChunkedHttpClient client = null;

		try {
			client = listen(runtime.start().getPort(), "catalog-wrap",
					"{\"toolsListChanged\":true}");
			assertSseHead(client.readHead());
			Assertions.assertTrue(client.readChunkText().contains(
					"notifications/subscriptions/acknowledged"));

			conditionalVisible.set(true);
			advanceProjection.set(true);
			events.publish(new McpSubscriptionEventSource.Event.ToolsListChanged());

			String notification;
			try {
				notification = readDataChunkContaining(client,
						"notifications/tools/list_changed");
			} catch (Exception exception) {
				throw new AssertionError("Catalog stream ended after nanoTime wrap: "
						+ observation.diagnostic(), exception);
			}
			Assertions.assertTrue(notification.contains("catalog-wrap"));
			Assertions.assertTrue(clock.nanoTime() < 0L,
					"The projection did not cross the signed nanoTime boundary.");
		} finally {
			if (client != null)
				client.closeWithReset();
			runtime.close();
		}
	}

	@Test
	@Timeout(70)
	public void renewalAndSameExpirationSuppressionRemainOrderedAcrossNanoTimeWrap()
			throws Exception {
		WrapClock clock = new WrapClock();
		AtomicInteger authorizations = new AtomicInteger();
		AtomicReference<Instant> validUntil = new AtomicReference<>();
		McpSubscriptionAuthorizer authorizer = (context, features) -> {
			int invocation = authorizations.incrementAndGet();
			if (invocation == 1) {
				Instant expiration = clock.instant().plusSeconds(10);
				validUntil.set(expiration);
				return allowed(expiration);
			}
			if (invocation == 2)
				return allowed(validUntil.get());
			throw new AssertionError(
					"A same-expiration grant spun renewal invocation " + invocation);
		};
		McpHttpServerRuntime runtime = runtime(clock, new TestEventSource(),
				McpResourceNotificationType.RESOURCES_LIST_CHANGED, Optional.empty(),
				authorizer, new RecordingObservation());
		McpChunkedHttpClient client = null;

		try {
			client = listen(runtime.start().getPort(), "authorization-wrap",
					"{\"resourcesListChanged\":true}");
			assertSseHead(client.readHead());
			Assertions.assertTrue(client.readChunkText().contains(
					"notifications/subscriptions/acknowledged"));
			Object control = soleRequestControl(runtime);
			long originalExpiry = longField(control,
					"subscriptionAuthorizationExpiryNanos");
			Assertions.assertTrue(originalExpiry < 0L,
					"The authorization expiry did not cross nanoTime wraparound.");

			clock.advance(Duration.ofSeconds(6));
			invokeTimer(control, clock.nanoTime());
			awaitCondition(() -> authorizations.get() == 2
					&& nullableField(control,
							"subscriptionAuthorizationCheck") == null,
					"The wrapped authorization renewal did not complete.");
			Assertions.assertEquals(originalExpiry, longField(control,
					"subscriptionAuthorizationExpiryNanos"));
			Assertions.assertFalse(booleanField(control,
					"subscriptionAuthorizationRenewalScheduled"));

			clock.advance(Duration.ofSeconds(1));
			invokeTimer(control, clock.nanoTime());
			Thread.sleep(100L);
			Assertions.assertEquals(2, authorizations.get(),
					"A disabled renewal sentinel became due after nanoTime wrapped.");
		} finally {
			if (client != null)
				client.closeWithReset();
			runtime.close();
		}
	}

	@Test
	@Timeout(70)
	public void backwardWallClockCannotExtendWrappedMonotonicAuthorizationLease()
			throws Exception {
		WrapClock clock = new WrapClock();
		AtomicInteger authorizations = new AtomicInteger();
		AtomicReference<Instant> validUntil = new AtomicReference<>();
		McpSubscriptionAuthorizer authorizer = (context, features) -> {
			int invocation = authorizations.incrementAndGet();
			if (invocation == 1) {
				Instant expiration = clock.instant().plusSeconds(10);
				validUntil.set(expiration);
				return allowed(expiration);
			}
			if (invocation == 2)
				return allowed(validUntil.get());
			throw new AssertionError(
					"A non-extending grant spun renewal invocation " + invocation);
		};
		RecordingObservation observation = new RecordingObservation();
		McpHttpServerRuntime runtime = runtime(clock, new TestEventSource(),
				McpResourceNotificationType.RESOURCES_LIST_CHANGED, Optional.empty(),
				authorizer, observation);
		McpChunkedHttpClient client = null;

		try {
			client = listen(runtime.start().getPort(), "wall-clock-wrap",
					"{\"resourcesListChanged\":true}");
			assertSseHead(client.readHead());
			Assertions.assertTrue(client.readChunkText().contains(
					"notifications/subscriptions/acknowledged"));
			Object control = soleRequestControl(runtime);
			long originalExpiry = longField(control,
					"subscriptionAuthorizationExpiryNanos");
			long renewalNanos = longField(control,
					"subscriptionAuthorizationRenewalNanos");

			clock.advanceMonotonic(Duration.ofSeconds(6));
			clock.rewindWall(Duration.ofHours(1));
			Assertions.assertTrue(clock.nanoTime() - renewalNanos >= 0L,
					"The renewal boundary was not due: now=" + clock.nanoTime()
							+ ", renewal=" + renewalNanos);
			Assertions.assertFalse(clock.nanoTime() - originalExpiry >= 0L,
					"The authorization unexpectedly expired before renewal.");
			Assertions.assertTrue(booleanField(control,
					"subscriptionAuthorizationRenewalScheduled"));
			invokeTimer(control, clock.nanoTime());
			awaitCondition(() -> authorizations.get() == 2,
					"The wrapped authorization renewal did not start after wall-clock rollback.");
			awaitCondition(() -> nullableField(control,
					"subscriptionAuthorizationCheck") == null,
					"The wrapped authorization renewal did not finish after wall-clock rollback.");
			Assertions.assertEquals(originalExpiry, longField(control,
					"subscriptionAuthorizationExpiryNanos"),
					"Wall-clock rollback extended the monotonic authorization lease.");
			Assertions.assertFalse(booleanField(control,
					"subscriptionAuthorizationRenewalScheduled"));

			clock.advanceMonotonic(Duration.ofSeconds(4));
			invokeTimer(control, clock.nanoTime());
			observation.awaitClosed();
			Assertions.assertEquals(
					McpStreamTerminationReason.SUBSCRIPTION_AUTHORIZATION_EXPIRED,
					observation.exactCloseReason());
			Assertions.assertEquals(2, authorizations.get());
		} finally {
			if (client != null)
				client.closeWithReset();
			runtime.close();
		}
	}

	@NonNull
	private static McpHttpServerRuntime runtime(@NonNull WrapClock clock,
			@NonNull TestEventSource events,
			@NonNull McpResourceNotificationType notificationType,
			@NonNull Optional<McpServerRuntimeBridge.@NonNull CatalogAccessAdapter>
					catalogAccessAdapter,
			@NonNull McpSubscriptionAuthorizer authorizer,
			@NonNull RecordingObservation observation) {
		McpNormalizedEndpoint.Builder endpointBuilder = McpNormalizedEndpoint
				.withServerInformation(McpImplementationMetadata.withNameAndVersion(
						"subscription-deadline-wrap-test", "4.0.0"))
				.subscriptionConfig(McpNormalizedSubscriptionConfiguration.supporting(
						notificationType));
		if (notificationType == McpResourceNotificationType.TOOLS_LIST_CHANGED) {
			endpointBuilder.tool(McpNormalizedOperation.named(TOOL_NAME));
			endpointBuilder.tool(McpNormalizedOperation.named(
					CONDITIONAL_TOOL_NAME));
		} else
			endpointBuilder.exactResource("test://subscription/deadline-wrap");
		catalogAccessAdapter.ifPresent(endpointBuilder::catalogAccessAdapter);
		McpHttpEndpointPolicy policy = McpHttpEndpointPolicy.forDiscovery(
				CorsAuthorizer.rejectAllInstance(),
				ignored -> McpAdmissionDecision.acceptedAnonymous());
		McpHttpEndpointBinding binding = new McpHttpEndpointBinding(policy,
				endpointBuilder.build(), McpApplicationRequestRouter.empty(),
				observation.sink(), List.of(events.source()), Optional.empty());
		McpSubscriptionRuntimeConfiguration subscriptions =
				new McpSubscriptionRuntimeConfiguration(8, Duration.ofSeconds(30),
						Duration.ofSeconds(15), Duration.ofSeconds(5), 2,
						Duration.ofHours(2), Duration.ofSeconds(5),
						Duration.ofSeconds(5), Duration.ofHours(2),
						Optional.of(authorizer));
		return new McpHttpServerRuntime(
				McpHttpTransportConfiguration.productionDefaults(0),
				List.of(binding), McpJsonLimits.productionDefaults(),
				McpApplicationExecutionConfiguration.productionDefaults(), clock,
				McpApplicationHandlerExecutorFactory.production(), ignored -> {},
				ignored -> {}, Optional.empty(),
				McpFrameworkRequestStateRuntime.disabledInstance(), subscriptions);
	}

	@NonNull
	private static McpSubscriptionAuthorization allowed(
			@NonNull Instant validUntil) {
		return McpSubscriptionAuthorization.Allowed.fromValidUntil(validUntil);
	}

	@NonNull
	private static McpChunkedHttpClient listen(int port, @NonNull String id,
			@NonNull String notifications) throws Exception {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + id
				+ "\",\"method\":\"subscriptions/listen\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}},"
				+ "\"notifications\":" + notifications + "}}";
		return McpChunkedHttpClient.postMcpMessage(port, body, List.of(
				new McpChunkedHttpClient.RequestHeader(
						"MCP-Protocol-Version", PROTOCOL_VERSION),
				new McpChunkedHttpClient.RequestHeader(
						"Mcp-Method", "subscriptions/listen")));
	}

	private static void assertSseHead(
			McpChunkedHttpClient.@NonNull HttpResponseHead head) {
		Assertions.assertEquals(200, head.status(), head.raw());
		Assertions.assertEquals("text/event-stream",
				head.singleHeader("Content-Type"));
	}

	@NonNull
	private static String readDataChunkContaining(
			@NonNull McpChunkedHttpClient client, @NonNull String expected)
			throws Exception {
		for (int attempt = 0; attempt < 4; attempt++) {
			String chunk = client.readChunkText();
			Assertions.assertNotNull(chunk,
					"The subscription closed before the expected notification.");
			if (chunk.contains(expected))
				return chunk;
		}
		throw new AssertionError("Missing stream data containing " + expected);
	}

	@NonNull
	private static Object soleRequestControl(@NonNull McpHttpServerRuntime runtime)
			throws Exception {
		Map<?, ?> controls = (Map<?, ?>) field(runtime, "requestControls");
		awaitCondition(() -> controls.size() == 1,
				"Expected exactly one active subscription request control.");
		return controls.values().iterator().next();
	}

	private static void invokeTimer(@NonNull Object control, long nowNanos)
			throws Exception {
		Method method = control.getClass().getDeclaredMethod("onTimer", long.class);
		method.setAccessible(true);
		try {
			method.invoke(control, nowNanos);
		} catch (InvocationTargetException exception) {
			Throwable cause = exception.getCause();
			if (cause instanceof Exception checked)
				throw checked;
			if (cause instanceof Error error)
				throw error;
			throw new AssertionError(cause);
		}
	}

	private static long longField(@NonNull Object target, @NonNull String name)
			throws Exception {
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.getLong(target);
	}

	private static boolean booleanField(@NonNull Object target,
			@NonNull String name) throws Exception {
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.getBoolean(target);
	}

	private static @Nullable Object nullableField(@NonNull Object target,
			@NonNull String name) {
		try {
			return field(target, name);
		} catch (Exception exception) {
			throw new AssertionError(exception);
		}
	}

	private static @Nullable Object field(@NonNull Object target,
			@NonNull String name) throws Exception {
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(target);
	}

	private static void awaitCondition(@NonNull BooleanSupplier condition,
			@NonNull String failureMessage) throws Exception {
		long deadline = System.nanoTime() + WAIT.toNanos();
		do {
			if (condition.getAsBoolean())
				return;
			Thread.sleep(1L);
		} while (System.nanoTime() - deadline < 0L);
		throw new AssertionError(failureMessage);
	}

	private static final class WrapClock implements McpApplicationClock {
		@NonNull
		private final AtomicLong nowNanos = new AtomicLong(INITIAL_NANOS);
		@NonNull
		private final AtomicReference<@NonNull Instant> now =
				new AtomicReference<>(INITIAL_WALL_TIME);

		@Override
		public long nanoTime() {
			return nowNanos.get();
		}

		@Override
		@NonNull
		public Instant instant() {
			return now.get();
		}

		private void advance(@NonNull Duration duration) {
			advanceMonotonic(duration);
			now.updateAndGet(value -> value.plus(duration));
		}

		private void advanceMonotonic(@NonNull Duration duration) {
			nowNanos.addAndGet(duration.toNanos());
		}

		private void rewindWall(@NonNull Duration duration) {
			now.updateAndGet(value -> value.minus(duration));
		}
	}

	private static final class TestEventSource {
		@NonNull
		private final AtomicReference<McpSubscriptionEventSource.@Nullable Listener>
				listener = new AtomicReference<>();

		@NonNull
		private McpSubscriptionEventSource source() {
			return new McpSubscriptionEventSource(this, next -> {
				listener.set(next);
				return () -> listener.compareAndSet(next, null);
			});
		}

		private void publish(McpSubscriptionEventSource.@NonNull Event event) {
			McpSubscriptionEventSource.Listener current = listener.get();
			Assertions.assertNotNull(current,
					"The subscription event source was not registered.");
			current.onEvent(event);
		}
	}

	private static final class RecordingObservation {
		@NonNull
		private final AtomicReference<McpStreamTerminationReason> exactCloseReason =
				new AtomicReference<>();
		@NonNull
		private final AtomicReference<McpRequestOutcome> outcome =
				new AtomicReference<>();
		@NonNull
		private final AtomicReference<List<Throwable>> failures =
				new AtomicReference<>(List.of());
		@NonNull
		private final CountDownLatch closed = new CountDownLatch(1);

		@NonNull
		private McpRuntimeObservationSink sink() {
			return input -> new McpRuntimeRequestObservation() {
				@Override
				@NonNull
				public Optional<@NonNull McpRequestContext> publicContext() {
					return Optional.of(RecordingObservation.publicContext(input));
				}

				@Override
				public void didFinish(@NonNull McpRequestOutcome outcome,
						McpJsonRpcError error, @NonNull Duration duration,
						@NonNull List<@NonNull Throwable> throwables) {
					RecordingObservation.this.outcome.set(outcome);
					RecordingObservation.this.failures.set(List.copyOf(throwables));
				}

				@Override
				public void didCloseSubscription(
						@NonNull StreamTerminationReason reason,
						@Nullable McpStreamTerminationReason exactReason,
						@NonNull Duration duration) {
					if (exactReason != null)
						exactCloseReason.compareAndSet(null, exactReason);
					closed.countDown();
				}
			};
		}

		private void awaitClosed() throws InterruptedException {
			Assertions.assertTrue(closed.await(WAIT.toNanos(), TimeUnit.NANOSECONDS),
					"The subscription did not close at its original monotonic expiry.");
		}

		private @Nullable McpStreamTerminationReason exactCloseReason() {
			return exactCloseReason.get();
		}

		@NonNull
		private String diagnostic() {
			return "outcome=" + outcome.get() + ", exactCloseReason="
					+ exactCloseReason.get() + ", failures=" + failures.get();
		}

		@NonNull
		private static McpRequestContext publicContext(
				@NonNull McpRuntimeRequestInput input) {
			return (McpRequestContext) Proxy.newProxyInstance(
					McpRequestContext.class.getClassLoader(),
					new Class<?>[]{McpRequestContext.class},
					(proxy, method, arguments) -> switch (method.getName()) {
					case "getRequest" -> input.request();
					case "getEndpoint" -> PUBLIC_ENDPOINT;
					case "getAdmissionIdentity" ->
							McpAdmissionIdentity.anonymousInstance();
					case "getJsonRpcMethod" -> input.jsonRpcMethod();
					case "getProtocolVersion" -> input.protocolVersion();
					case "getEndpointPathParameters", "getBaggage" -> Map.of();
					case "getRequestId", "getOperationName", "getClientInfo",
							"getFrameworkRequestState", "getApplicationRequestState",
							"getTraceContext" -> Optional.empty();
					case "toString" -> "McpSubscriptionDeadlineWraparoundContext";
					case "hashCode" -> System.identityHashCode(proxy);
					case "equals" -> proxy == arguments[0];
					default -> defaultValue(method.getReturnType());
					});
		}

		private static @Nullable Object defaultValue(@NonNull Class<?> type) {
			if (type == boolean.class || type == Boolean.class)
				return false;
			if (type == Map.class)
				return Map.of();
			if (type == Optional.class)
				return Optional.empty();
			if (type == Request.class)
				throw new AssertionError("A request value requires explicit handling.");
			return null;
		}
	}
}
