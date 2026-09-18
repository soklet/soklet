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

import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpSubscriptionAuthorizationApiTests {
	private static final Instant VALID_UNTIL =
			Instant.parse("2036-02-03T04:05:06.789Z");
	private final McpSubscriptionAuthorizationContext authorizationContext =
			(McpSubscriptionAuthorizationContext) Proxy.newProxyInstance(
					McpSubscriptionAuthorizationContext.class.getClassLoader(),
					new Class<?>[] { McpSubscriptionAuthorizationContext.class },
					(proxy, method, arguments) -> {
						throw new AssertionError(
								"Unexpected context access: " + method.getName());
					});
	private final McpInvocationFeatures invocationFeatures =
			McpInvocationFeatures.fromFeatures(Map.of());

	@Test
	void denyAllAuthorizerIsStableAndReturnsTheSharedDeniedValue()
			throws Exception {
		McpSubscriptionAuthorizer authorizer =
				McpSubscriptionAuthorizer.denyAllInstance();

		assertSame(authorizer, McpSubscriptionAuthorizer.denyAllInstance());
		assertSame(McpSubscriptionAuthorization.deniedInstance(),
				authorizer.authorize(this.authorizationContext,
						this.invocationFeatures));
		assertThrows(NullPointerException.class,
				() -> authorizer.authorize(null, this.invocationFeatures));
		assertThrows(NullPointerException.class,
				() -> authorizer.authorize(this.authorizationContext, null));
	}

	@Test
	void allowedFactoryAndBuilderValidateAndExposeExpiration() {
		McpSubscriptionAuthorization.Allowed factoryValue =
				McpSubscriptionAuthorization.Allowed.fromValidUntil(VALID_UNTIL);
		McpSubscriptionAuthorization.Allowed builderValue =
				McpSubscriptionAuthorization.Allowed.withValidUntil(VALID_UNTIL)
						.build();
		McpSubscriptionAuthorization.Allowed differentExpiration =
				McpSubscriptionAuthorization.Allowed.withValidUntil(
						VALID_UNTIL.plusSeconds(1)).build();

		assertSame(VALID_UNTIL, factoryValue.getValidUntil());
		assertTrue(factoryValue.getApplicationContext().isEmpty());
		assertEquals(factoryValue, builderValue);
		assertEquals(factoryValue.hashCode(), builderValue.hashCode());
		assertNotEquals(factoryValue, differentExpiration);
		assertNotEquals(factoryValue.hashCode(),
				differentExpiration.hashCode());
		assertEquals("Allowed{validUntil=" + VALID_UNTIL
				+ ", applicationContext=<redacted>}", factoryValue.toString());
		assertThrows(NullPointerException.class,
				() -> McpSubscriptionAuthorization.Allowed.fromValidUntil(null));
		assertThrows(NullPointerException.class,
				() -> McpSubscriptionAuthorization.Allowed.withValidUntil(null));
	}

	@Test
	void builderSnapshotsReplacementContextAndNullClearsIt() {
		SensitiveContext originalContext =
				new SensitiveContext("secret-application-context");
		McpSubscriptionAuthorization.Allowed.Builder builder =
				McpSubscriptionAuthorization.Allowed.withValidUntil(VALID_UNTIL)
						.applicationContext(originalContext);
		McpSubscriptionAuthorization.Allowed withContext = builder.build();
		McpSubscriptionAuthorization.Allowed equalValue =
				McpSubscriptionAuthorization.Allowed.withValidUntil(VALID_UNTIL)
						.applicationContext(
								new SensitiveContext("secret-application-context"))
						.build();

		builder.applicationContext(null);
		McpSubscriptionAuthorization.Allowed clearedContext = builder.build();

		assertSame(originalContext,
				withContext.getApplicationContext().orElseThrow());
		assertEquals(withContext, equalValue);
		assertEquals(withContext.hashCode(), equalValue.hashCode());
		assertNotEquals(withContext, clearedContext);
		assertTrue(clearedContext.getApplicationContext().isEmpty());
		assertFalse(withContext.toString().contains(originalContext.secret()));
		assertFalse(withContext.toString().contains(originalContext.toString()));
	}

	@Test
	void deniedAuthorizationIsADataFreeSingletonValue() {
		McpSubscriptionAuthorization.Denied first =
				McpSubscriptionAuthorization.deniedInstance();
		McpSubscriptionAuthorization.Denied second =
				McpSubscriptionAuthorization.deniedInstance();

		assertSame(first, second);
		assertEquals(first, second);
		assertEquals(0, first.hashCode());
		assertEquals("Denied{}", first.toString());
	}

	@Test
	void authorizationHierarchyIsClosedAndConstructorsArePrivate() {
		assertTrue(McpSubscriptionAuthorization.class.isSealed());
		assertEquals(Set.of(McpSubscriptionAuthorization.Allowed.class,
				McpSubscriptionAuthorization.Denied.class),
				Set.of(McpSubscriptionAuthorization.class.getPermittedSubclasses()));
		assertTrue(Modifier.isFinal(
				McpSubscriptionAuthorization.Allowed.class.getModifiers()));
		assertTrue(Modifier.isFinal(
				McpSubscriptionAuthorization.Denied.class.getModifiers()));
		assertEquals(2,
				McpSubscriptionAuthorization.Allowed.class
						.getDeclaredConstructors().length);
		assertTrue(Arrays.stream(McpSubscriptionAuthorization.Allowed.class
				.getDeclaredConstructors()).allMatch(constructor ->
				Modifier.isPrivate(constructor.getModifiers())));
		assertEquals(1,
				McpSubscriptionAuthorization.Denied.class
						.getDeclaredConstructors().length);
		assertTrue(Modifier.isPrivate(
				McpSubscriptionAuthorization.Denied.class
						.getDeclaredConstructors()[0].getModifiers()));
	}

	@Test
	void callbackAndContextInterfacesExposeOnlyTheAgreedMethods() {
		assertTrue(McpSubscriptionAuthorizer.class
				.isAnnotationPresent(FunctionalInterface.class));
		assertEquals(Set.of("authorize", "denyAllInstance"),
				declaredMethodNames(McpSubscriptionAuthorizer.class));
		assertEquals(Set.of("getInitialRequestContext", "getApplicationContext",
				"getPreviousValidUntil", "getDeadline",
				"isToolsListChangedIncluded", "isPromptsListChangedIncluded",
				"isResourcesListChangedIncluded", "getResourceSubscriptionUris",
				"getTaskIds"),
				declaredMethodNames(McpSubscriptionAuthorizationContext.class));
		assertEquals(Set.of("reconcileSubscriptions"),
				declaredMethodNames(McpSubscriptionReconciler.class));
	}

	private static Set<String> declaredMethodNames(Class<?> type) {
		return Arrays.stream(type.getDeclaredMethods())
				.filter(method -> !method.isSynthetic())
				.map(method -> method.getName())
				.collect(Collectors.toUnmodifiableSet());
	}

	private record SensitiveContext(String secret) {
	}
}
