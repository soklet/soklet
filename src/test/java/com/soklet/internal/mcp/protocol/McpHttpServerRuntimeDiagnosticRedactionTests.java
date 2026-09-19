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

import com.soklet.McpRequestContext;
import com.soklet.McpStreamTerminationReason;
import com.soklet.McpSubscriptionAuthorization;
import com.soklet.internal.microhttp.Header;
import com.soklet.internal.microhttp.MicrohttpResponse;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.FutureTask;
import java.util.function.Consumer;

/**
 * Privacy canaries for private runtime coordination carriers. These carriers
 * can reach diagnostic tooling during failure handling, so their renderings
 * must never delegate to application or wire values.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public class McpHttpServerRuntimeDiagnosticRedactionTests {
	private static final String SECRET =
			"private-runtime-carrier-secret-9017";

	@Test
	public void privateCoordinationCarriersRenderOnlyBoundedMetadata()
			throws Exception {
		PoisonValue applicationContext = new PoisonValue();
		PoisonThrowable failure = new PoisonThrowable();
		McpRequestContext requestContext = poisonProxy(McpRequestContext.class);
		McpProtocolProfile protocolProfile = poisonProxy(McpProtocolProfile.class);
		McpApplicationExecution.BoundedPolicyCancellation cancellation =
				boundedPolicyCancellation();
		Object physicalExit = construct("BoundedPolicyPhysicalExit");

		Object catalogCheck = construct("CatalogProjectionCheck",
				McpCatalogProjectionQueue.Family.TOOLS, null, 3L, 5L,
				"/" + SECRET, protocolProfile, requestContext, cancellation,
				physicalExit, 7L);
		Object catalogExecution = construct("CatalogProjectionExecution",
				enumConstant("CatalogProjectionDisposition", "FAILED"), null,
				failure, false);
		McpCatalogProjectionQueue.Digest digest =
				new McpCatalogProjectionQueue.Digest(new byte[32]);
		Object baselines = construct("InitialCatalogBaselines", digest, digest);
		Object initialProjection = construct("InitialCatalogProjectionResult",
				enumConstant("CatalogProjectionDisposition", "SUCCEEDED"), baselines);

		URI resourceUri = URI.create("https://example.invalid/" + SECRET);
		Object context = construct("SubscriptionAuthorizationContextSnapshot",
				requestContext, Optional.of(applicationContext),
				Optional.of(Instant.parse("2026-09-18T12:00:00Z")),
				Instant.parse("2026-09-18T12:01:00Z"), true, true, true,
				Set.of(resourceUri), Set.of(SECRET));
		Object resource = construct("SubscriptionResource", resourceUri, SECRET);
		Object filter = construct("AcceptedSubscriptionFilter", true, true, true,
				true, Map.of(resourceUri, resource), true, List.of(SECRET),
				Set.of(SECRET), McpClientCapabilities.empty());
		Object authorizationCheck = construct("SubscriptionAuthorizationCheck",
				enumConstant("SubscriptionAuthorizationCheckKind", "RENEWAL"),
				11L, 13L, "/" + SECRET, new McpJsonRpcId.StringId(SECRET),
				filter, context, cancellation, physicalExit, 17L, 19L);

		McpSubscriptionAuthorization.Allowed allowed =
				McpSubscriptionAuthorization.Allowed
						.withValidUntil(Instant.parse("2026-09-18T12:02:00Z"))
						.applicationContext(applicationContext)
						.build();
		Object callbackResult = construct("SubscriptionAuthorizationCallbackResult",
				allowed, Set.of(SECRET));
		Object authorizationExecution = construct("SubscriptionAuthorizationExecution",
				enumConstant("SubscriptionAuthorizationDisposition", "FAILED"),
				null, Set.of(), failure, false);
		Object authorizationResult = construct("SubscriptionAuthorizationResult",
				enumConstant("SubscriptionAuthorizationDisposition", "FAILED"),
				Set.of(), failure, false);
		Object effectiveGrant = construct("EffectiveSubscriptionAuthorizationGrant",
				Instant.parse("2026-09-18T12:03:00Z"), 23L, 29L,
				Optional.of(applicationContext));
		Object authorizationFailure = construct("SubscriptionAuthorizationFailure",
				authorizationCheck, catalogCheck,
				McpStreamTerminationReason.INTERNAL_ERROR, true);

		FutureTask<Void> poisonTask = new FutureTask<>(() -> null) {
			@Override
			public String toString() {
				throw new AssertionError("FutureTask rendering invoked");
			}
		};
		Consumer<MicrohttpResponse> poisonCallback = new Consumer<>() {
			@Override
			public void accept(MicrohttpResponse ignored) {
				throw new AssertionError("Callback invoked");
			}

			@Override
			public String toString() {
				throw new AssertionError("Callback rendering invoked");
			}
		};
		Object deadlineExpiration = construct("ProtocolDeadlineExpiration",
				poisonTask, poisonCallback, List.of(new Header(SECRET, SECRET)),
				null, authorizationCheck, catalogCheck);

		assertRendering(catalogCheck,
				"CatalogProjectionCheck{family=TOOLS, projectionPresent=false}");
		assertRendering(catalogExecution,
				"CatalogProjectionExecution{disposition=FAILED, digestPresent=false, "
						+ "failurePresent=true, queuedTimeout=false}");
		assertRendering(baselines,
				"InitialCatalogBaselines{toolsPresent=true, promptsPresent=true}");
		assertRendering(initialProjection,
				"InitialCatalogProjectionResult{disposition=SUCCEEDED, "
						+ "baselinesPresent=true}");
		assertRendering(authorizationCheck,
				"SubscriptionAuthorizationCheck{kind=RENEWAL}");
		assertRendering(callbackResult,
				"SubscriptionAuthorizationCallbackResult{acceptedTaskIdCount=1}");
		assertRendering(authorizationExecution,
				"SubscriptionAuthorizationExecution{disposition=FAILED, "
						+ "acceptedTaskIdCount=0, failurePresent=true, queuedTimeout=false}");
		assertRendering(authorizationResult,
				"SubscriptionAuthorizationResult{disposition=FAILED, "
						+ "acceptedTaskIdCount=0, failurePresent=true, queuedTimeout=false}");
		assertRendering(effectiveGrant,
				"EffectiveSubscriptionAuthorizationGrant{applicationContextPresent=true}");
		assertRendering(authorizationFailure,
				"SubscriptionAuthorizationFailure{authorizationCheckPresent=true, "
						+ "catalogCheckPresent=true, exactReason=INTERNAL_ERROR, "
						+ "signalTimer=true}");
		assertRendering(context,
				"SubscriptionAuthorizationContextSnapshot{applicationContextPresent=true, "
						+ "previousValidUntilPresent=true, toolsListChangedIncluded=true, "
						+ "promptsListChangedIncluded=true, resourcesListChangedIncluded=true, "
						+ "resourceSubscriptionUriCount=1, taskIdCount=1}");
		assertRendering(deadlineExpiration,
				"ProtocolDeadlineExpiration{taskPresent=true, responseHeaderCount=1, "
						+ "subscriptionCapReservationPresent=false, "
						+ "authorizationCheckPresent=true, catalogCheckPresent=true}");
	}

	private static McpApplicationExecution.BoundedPolicyCancellation
	boundedPolicyCancellation() throws InterruptedException {
		McpApplicationExecution execution = new McpApplicationExecution(
				new McpApplicationExecutionConfiguration(
						1, 1, Duration.ofSeconds(30), Duration.ofDays(1)),
				McpApplicationClock.SYSTEM);
		McpApplicationExecution.BoundedPolicyCancellation cancellation =
				execution.newBoundedPolicyCancellation();
		execution.stop();
		Assertions.assertTrue(execution.awaitTermination(Duration.ofSeconds(5)));
		return cancellation;
	}

	private static void assertRendering(Object value, String expected) {
		String rendering = Assertions.assertDoesNotThrow(value::toString);
		Assertions.assertEquals(expected, rendering);
		Assertions.assertFalse(rendering.contains(SECRET), rendering);
		Assertions.assertTrue(rendering.length() < 512, rendering);
	}

	private static Object construct(String simpleName, Object... arguments)
			throws Exception {
		Class<?> type = nestedType(simpleName);
		Constructor<?> constructor = Arrays.stream(type.getDeclaredConstructors())
				.filter(candidate -> candidate.getParameterCount() == arguments.length)
				.findFirst()
				.orElseThrow();
		constructor.setAccessible(true);
		try {
			return constructor.newInstance(arguments);
		} catch (InvocationTargetException exception) {
			Throwable cause = exception.getCause();
			if (cause instanceof Exception checked)
				throw checked;
			if (cause instanceof Error error)
				throw error;
			throw exception;
		}
	}

	private static Object enumConstant(String simpleName, String name) {
		Class<?> type = nestedType(simpleName);
		return Arrays.stream(type.getEnumConstants())
				.filter(value -> ((Enum<?>) value).name().equals(name))
				.findFirst()
				.orElseThrow();
	}

	private static Class<?> nestedType(String simpleName) {
		return Arrays.stream(McpHttpServerRuntime.class.getDeclaredClasses())
				.filter(type -> type.getSimpleName().equals(simpleName))
				.findFirst()
				.orElseThrow();
	}

	@SuppressWarnings("unchecked")
	private static <T> T poisonProxy(Class<T> interfaceType) {
		return (T) Proxy.newProxyInstance(interfaceType.getClassLoader(),
				new Class<?>[]{interfaceType}, (proxy, method, arguments) -> {
					throw new AssertionError(interfaceType.getSimpleName()
							+ " method invoked: " + method.getName());
				});
	}

	private static final class PoisonValue {
		@Override
		public String toString() {
			throw new AssertionError("Application-context rendering invoked: "
					+ SECRET);
		}
	}

	private static final class PoisonThrowable extends RuntimeException {
		private PoisonThrowable() {
			super(SECRET);
		}

		@Override
		public String toString() {
			throw new AssertionError("Throwable rendering invoked: " + SECRET);
		}
	}
}
