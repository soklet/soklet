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
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.annotation.concurrent.ThreadSafe;
import java.lang.reflect.AnnotatedArrayType;
import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Exact provisional public API contracts for MCP 2025 sessions and HTTP admission.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class McpSessionProvisionalApiContractTests {
	@Test
	public void sessionAndTransportAdmissionOwnersRemainProvisional()
			throws Exception {
		List<String> owners = List.of(McpSessionConfig.class.getName(),
				McpSessionConfig.Builder.class.getName(),
				McpSessionOwnerKeyResolver.class.getName(),
				McpSessionTransportAdmissionContext.class.getName(),
				McpSessionTransportAdmissionController.class.getName(),
				McpSessionTransportAdmissionDecision.class.getName(),
				McpSessionTransportAdmissionDecision.Accepted.class.getName(),
				McpSessionTransportAdmissionDecision.Rejected.class.getName());
		List<String> provisional = Files.readAllLines(
				Path.of("api/mcp/provisional.includes"), StandardCharsets.UTF_8);
		for (String owner : owners) {
			Assertions.assertEquals(1, provisional.stream().filter(owner::equals).count());
			for (int phase : List.of(4, 5, 6))
				Assertions.assertFalse(Files.readAllLines(Path.of(
						"api/mcp/phase-" + phase + ".includes"), StandardCharsets.UTF_8)
						.contains(owner), "A new session owner must not change a frozen phase partition.");
		}
	}

	@Test
	public void newOwnersExposeOnlyTheReviewedMinimumPackage() throws Exception {
		assertPrivateConstruction(McpSessionConfig.class);
		assertPrivateConstruction(McpSessionConfig.Builder.class);
		Assertions.assertTrue(Modifier.isStatic(McpSessionConfig.Builder.class.getModifiers()));
		Assertions.assertTrue(McpSessionOwnerKeyResolver.class.isInterface());
		Assertions.assertTrue(McpSessionOwnerKeyResolver.class
				.isAnnotationPresent(FunctionalInterface.class));
		assertMethods(McpSessionConfig.class, List.of(
				"getMaximumClientMetadataSizeInBytes():java.lang.Integer",
				"getMaximumSessionDuration():java.time.Duration",
				"getMaximumSessionIdleDuration():java.time.Duration",
				"getMaximumSessions():java.lang.Integer",
				"getMaximumSessionsPerOwner():java.lang.Integer",
				"getOwnerKeyResolver():com.soklet.McpSessionOwnerKeyResolver",
				"getTransportAdmissionController():java.util.Optional",
				"isAnonymousSessionsAllowed():java.lang.Boolean",
				"withOwnerKeyResolver(com.soklet.McpSessionOwnerKeyResolver):com.soklet.McpSessionConfig$Builder"));
		assertMethods(McpSessionConfig.Builder.class, List.of(
				"anonymousSessionsAllowed(java.lang.Boolean):com.soklet.McpSessionConfig$Builder",
				"build():com.soklet.McpSessionConfig",
				"maximumClientMetadataSizeInBytes(java.lang.Integer):com.soklet.McpSessionConfig$Builder",
				"maximumSessionDuration(java.time.Duration):com.soklet.McpSessionConfig$Builder",
				"maximumSessionIdleDuration(java.time.Duration):com.soklet.McpSessionConfig$Builder",
				"maximumSessions(java.lang.Integer):com.soklet.McpSessionConfig$Builder",
				"maximumSessionsPerOwner(java.lang.Integer):com.soklet.McpSessionConfig$Builder",
				"transportAdmissionController(com.soklet.McpSessionTransportAdmissionController):com.soklet.McpSessionConfig$Builder"));
		assertMethods(McpSessionOwnerKeyResolver.class,
				List.of("resolve(com.soklet.McpAdmissionIdentity):java.lang.String"));
		Method resolve = McpSessionOwnerKeyResolver.class
				.getMethod("resolve", McpAdmissionIdentity.class);
		Assertions.assertArrayEquals(new Class<?>[] { Exception.class }, resolve.getExceptionTypes());
		Assertions.assertTrue(Modifier.isAbstract(resolve.getModifiers()));
		Assertions.assertFalse(resolve.isDefault());
	}

	@Test
	public void configurationScalarsAndResolverHaveExactNullabilityAndParameterNames()
			throws Exception {
		Method factory = McpSessionConfig.class.getMethod("withOwnerKeyResolver",
				McpSessionOwnerKeyResolver.class);
		Assertions.assertTrue(Modifier.isStatic(factory.getModifiers()));
		assertRequiredReturn(factory, McpSessionConfig.Builder.class);
		assertParameter(factory, "sessionOwnerKeyResolver", McpSessionOwnerKeyResolver.class, false);
		for (Method method : McpSessionConfig.class.getDeclaredMethods())
			if (Modifier.isPublic(method.getModifiers()) && !isObjectContractMethod(method))
				assertNonNull(method.getAnnotatedReturnType());
		for (String name : List.of("maximumSessions", "maximumSessionsPerOwner",
				"maximumClientMetadataSizeInBytes"))
			assertOptionalTuning(name, Integer.class);
		for (String name : List.of("maximumSessionIdleDuration", "maximumSessionDuration"))
			assertOptionalTuning(name, Duration.class);
		assertOptionalTuning("anonymousSessionsAllowed", Boolean.class);
		assertRequiredGenericReturn(McpSessionConfig.class.getMethod(
				"getTransportAdmissionController"), Optional.class,
				McpSessionTransportAdmissionController.class);
		Method transportSetter = McpSessionConfig.Builder.class.getMethod(
				"transportAdmissionController", McpSessionTransportAdmissionController.class);
		assertRequiredReturn(transportSetter, McpSessionConfig.Builder.class);
		assertParameter(transportSetter, "sessionTransportAdmissionController",
				McpSessionTransportAdmissionController.class, true);
		assertRequiredReturn(McpSessionConfig.Builder.class.getMethod("build"), McpSessionConfig.class);
		Method resolve = McpSessionOwnerKeyResolver.class.getMethod("resolve", McpAdmissionIdentity.class);
		assertRequiredReturn(resolve, String.class);
		assertParameter(resolve, "admissionIdentity", McpAdmissionIdentity.class, false);
	}

	@Test
	public void transportAdmissionHasExactContextControllerAndClosedDecisionDomain()
			throws Exception {
		assertMethods(McpSessionTransportAdmissionContext.class, List.of(
				"getDeadline():java.time.Instant", "getEndpoint():com.soklet.McpEndpoint",
				"getNotificationTypes():java.util.Set",
				"getProtocolVersion():com.soklet.McpProtocolVersion",
				"getRequest():com.soklet.Request", "isReauthorization():java.lang.Boolean"));
		assertMethods(McpSessionTransportAdmissionController.class, List.of(
				"admit(com.soklet.McpSessionTransportAdmissionContext,com.soklet.McpInvocationFeatures):com.soklet.McpSessionTransportAdmissionDecision"));
		Assertions.assertTrue(McpSessionTransportAdmissionController.class
				.isAnnotationPresent(FunctionalInterface.class));
		Assertions.assertTrue(McpSessionTransportAdmissionDecision.class.isSealed());
		Assertions.assertEquals(Set.of(McpSessionTransportAdmissionDecision.Accepted.class,
				McpSessionTransportAdmissionDecision.Rejected.class), Set.of(
				McpSessionTransportAdmissionDecision.class.getPermittedSubclasses()));
		assertMethods(McpSessionTransportAdmissionDecision.class, List.of(
				"accepted(com.soklet.McpAdmissionIdentity,java.time.Instant,java.util.Set):com.soklet.McpSessionTransportAdmissionDecision$Accepted",
				"rejected(com.soklet.McpAdmissionRejection):com.soklet.McpSessionTransportAdmissionDecision$Rejected"));
		assertPrivateConstruction(McpSessionTransportAdmissionDecision.Accepted.class);
		assertPrivateConstruction(McpSessionTransportAdmissionDecision.Rejected.class);
		assertMethods(McpSessionTransportAdmissionDecision.Accepted.class, List.of(
				"getIdentity():com.soklet.McpAdmissionIdentity",
				"getNotificationTypes():java.util.Set", "getValidUntil():java.time.Instant"));
		assertMethods(McpSessionTransportAdmissionDecision.Rejected.class,
				List.of("getRejection():com.soklet.McpAdmissionRejection"));
	}

	@Test
	public void transportAdmissionRequiresExplicitExpiryAndExactNestedNullability()
			throws Exception {
		for (Method method : McpSessionTransportAdmissionContext.class.getDeclaredMethods()) {
			Assertions.assertTrue(Modifier.isAbstract(method.getModifiers()));
			Assertions.assertEquals(0, method.getParameterCount());
			assertNonNull(method.getAnnotatedReturnType());
		}
		assertRequiredGenericReturn(McpSessionTransportAdmissionContext.class.getMethod(
				"getNotificationTypes"), Set.class, McpSubscriptionNotificationType.class);
		assertRequiredGenericReturn(McpSessionTransportAdmissionDecision.Accepted.class.getMethod(
				"getNotificationTypes"), Set.class, McpSubscriptionNotificationType.class);
		Method admit = McpSessionTransportAdmissionController.class.getMethod("admit",
				McpSessionTransportAdmissionContext.class, McpInvocationFeatures.class);
		Assertions.assertTrue(Modifier.isAbstract(admit.getModifiers()));
		Assertions.assertFalse(admit.isDefault());
		Assertions.assertArrayEquals(new Class<?>[] { Exception.class }, admit.getExceptionTypes());
		assertRequiredReturn(admit, McpSessionTransportAdmissionDecision.class);
		assertParameter(admit, 0, "sessionTransportAdmissionContext",
				McpSessionTransportAdmissionContext.class, false);
		assertParameter(admit, 1, "invocationFeatures", McpInvocationFeatures.class, false);
		Method accepted = McpSessionTransportAdmissionDecision.class.getMethod("accepted",
				McpAdmissionIdentity.class, Instant.class, Set.class);
		Assertions.assertTrue(Modifier.isStatic(accepted.getModifiers()));
		assertRequiredReturn(accepted, McpSessionTransportAdmissionDecision.Accepted.class);
		assertParameter(accepted, 0, "admissionIdentity", McpAdmissionIdentity.class, false);
		assertParameter(accepted, 1, "validUntil", Instant.class, false);
		assertParameter(accepted, 2, "notificationTypes", Set.class, false);
		AnnotatedParameterizedType selection = Assertions.assertInstanceOf(
				AnnotatedParameterizedType.class, accepted.getAnnotatedParameterTypes()[2]);
		assertNonNull(selection.getAnnotatedActualTypeArguments()[0]);
		Assertions.assertEquals(McpSubscriptionNotificationType.class,
				selection.getAnnotatedActualTypeArguments()[0].getType());
		Method rejected = McpSessionTransportAdmissionDecision.class.getMethod("rejected",
				McpAdmissionRejection.class);
		Assertions.assertTrue(Modifier.isStatic(rejected.getModifiers()));
		assertRequiredReturn(rejected, McpSessionTransportAdmissionDecision.Rejected.class);
		assertParameter(rejected, "admissionRejection", McpAdmissionRejection.class, false);
		for (Class<?> type : List.of(McpSessionTransportAdmissionDecision.Accepted.class,
				McpSessionTransportAdmissionDecision.Rejected.class))
			for (Method method : type.getDeclaredMethods())
				if (method.getName().startsWith("get")) assertNonNull(method.getAnnotatedReturnType());
	}

	@Test
	public void existingOwnersRetainNestedSessionOptionalAndRevisionSetContracts()
			throws Exception {
		Method configuration = McpServer.class.getMethod("getSessionConfig");
		Assertions.assertTrue(Modifier.isAbstract(configuration.getModifiers()));
		assertRequiredGenericReturn(configuration, Optional.class, McpSessionConfig.class);
		assertRequiredGenericReturn(DefaultMcpServer.class.getMethod("getSessionConfig"),
				Optional.class, McpSessionConfig.class);
		Method serverSetter = McpServer.Builder.class.getMethod("sessionConfig", McpSessionConfig.class);
		assertRequiredReturn(serverSetter, McpServer.Builder.class);
		assertParameter(serverSetter, "sessionConfig", McpSessionConfig.class, true);
		Method revisions = McpEndpoint.class.getMethod("getSessionProtocolVersions");
		assertRequiredGenericReturn(revisions, Set.class, McpProtocolVersion.class);
		Method endpointSetter = McpEndpoint.Builder.class.getMethod("sessionProtocolVersions", Set.class);
		assertRequiredReturn(endpointSetter, McpEndpoint.Builder.class);
		assertParameter(endpointSetter, "protocolVersions", Set.class, false);
		AnnotatedParameterizedType selected = Assertions.assertInstanceOf(
				AnnotatedParameterizedType.class, endpointSetter.getAnnotatedParameterTypes()[0]);
		assertNonNull(selected.getAnnotatedActualTypeArguments()[0]);
		Assertions.assertEquals(McpProtocolVersion.class,
				selected.getAnnotatedActualTypeArguments()[0].getType());
	}

	@Test
	public void endpointAnnotationRetainsAnEmptyNonNullRevisionArray() throws Exception {
		Method revisions = McpServerEndpoint.class.getMethod("sessionProtocolVersions");
		Assertions.assertEquals(McpProtocolVersion[].class, revisions.getReturnType());
		Assertions.assertArrayEquals(new McpProtocolVersion[0],
				(McpProtocolVersion[]) revisions.getDefaultValue());
		AnnotatedArrayType array = Assertions.assertInstanceOf(
				AnnotatedArrayType.class, revisions.getAnnotatedReturnType());
		assertNonNull(array);
		assertNonNull(array.getAnnotatedGenericComponentType());
	}

	private static void assertPrivateConstruction(Class<?> type) {
		Assertions.assertTrue(Modifier.isPublic(type.getModifiers()));
		Assertions.assertTrue(Modifier.isFinal(type.getModifiers()));
		Assertions.assertFalse(type.isRecord());
		Assertions.assertEquals(0, type.getConstructors().length);
		Assertions.assertTrue(Arrays.stream(type.getDeclaredConstructors())
				.allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers())));
	}

	private static void assertMethods(Class<?> type, List<String> expected) {
		Assertions.assertTrue(Arrays.stream(type.getDeclaredFields())
				.noneMatch(field -> Modifier.isPublic(field.getModifiers())
						|| Modifier.isProtected(field.getModifiers())), type.getName());
		List<String> actual = Arrays.stream(type.getDeclaredMethods())
				.filter(method -> Modifier.isPublic(method.getModifiers())
						|| Modifier.isProtected(method.getModifiers()))
				.filter(method -> !isObjectContractMethod(method))
				.map(method -> method.getName() + "(" + String.join(",",
						Arrays.stream(method.getParameterTypes()).map(Class::getName).toList())
						+ "):" + method.getReturnType().getName())
				.sorted().toList();
		Assertions.assertEquals(expected.stream().sorted().toList(), actual, type.getName());
	}

	private static boolean isObjectContractMethod(Method method) {
		return (method.getName().equals("equals") && method.getReturnType() == boolean.class
				&& Arrays.equals(method.getParameterTypes(), new Class<?>[] { Object.class }))
				|| (method.getName().equals("hashCode") && method.getReturnType() == int.class
				&& method.getParameterCount() == 0)
				|| (method.getName().equals("toString") && method.getReturnType() == String.class
				&& method.getParameterCount() == 0);
	}

	private static void assertOptionalTuning(String name, Class<?> type) throws Exception {
		Method method = McpSessionConfig.Builder.class.getMethod(name, type);
		assertRequiredReturn(method, McpSessionConfig.Builder.class);
		assertParameter(method, name, type, true);
	}

	private static void assertRequiredReturn(Method method, Class<?> type) {
		Assertions.assertEquals(type, method.getReturnType());
		assertNonNull(method.getAnnotatedReturnType());
	}

	private static void assertRequiredGenericReturn(Method method, Class<?> raw, Class<?> item) {
		Assertions.assertEquals(raw, method.getReturnType());
		AnnotatedParameterizedType result = Assertions.assertInstanceOf(
				AnnotatedParameterizedType.class, method.getAnnotatedReturnType());
		assertNonNull(result);
		Assertions.assertEquals(1, result.getAnnotatedActualTypeArguments().length);
		Assertions.assertEquals(item, result.getAnnotatedActualTypeArguments()[0].getType());
		assertNonNull(result.getAnnotatedActualTypeArguments()[0]);
	}

	private static void assertParameter(Method method, String name, Class<?> type, boolean nullable) {
		Assertions.assertEquals(1, method.getParameterCount());
		assertParameter(method, 0, name, type, nullable);
	}

	private static void assertParameter(Method method, int index, String name,
			Class<?> type, boolean nullable) {
		Assertions.assertEquals(type, method.getParameterTypes()[index]);
		Assertions.assertTrue(method.getParameters()[index].isNamePresent());
		Assertions.assertEquals(name, method.getParameters()[index].getName());
		AnnotatedType parameter = method.getAnnotatedParameterTypes()[index];
		Assertions.assertEquals(nullable, parameter.isAnnotationPresent(Nullable.class));
		Assertions.assertEquals(!nullable, parameter.isAnnotationPresent(NonNull.class));
	}

	private static void assertNonNull(AnnotatedType type) {
		Assertions.assertTrue(type.isAnnotationPresent(NonNull.class), type.toString());
		Assertions.assertFalse(type.isAnnotationPresent(Nullable.class), type.toString());
	}
}
