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

import com.soklet.annotation.McpMayRequestInput;
import com.soklet.annotation.McpPrompt;
import com.soklet.annotation.McpResource;
import com.soklet.annotation.McpTool;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.annotation.concurrent.ThreadSafe;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedArrayType;
import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.AnnotatedWildcardType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Reflection contracts for Phase 4 public API details that ordinary binary
 * compatibility comparison does not reliably preserve.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class McpPublicApiReflectionContractTests {
	private static final Path PHASE_FOUR_INCLUDES =
			Path.of("api/mcp/phase-4.includes");
	private static final int PHASE_FOUR_TYPE_COUNT = 133;
	private static final String PHASE_FOUR_NULLABILITY_SHA_256 =
			"ad66bd34619a7b769bc637124c6eb49fe44b27ec6a8e214e1b88b7b4ccf657a1";
	private static final Map<String, Set<String>> PHASE_FOUR_PERMITTED_TYPES =
			Map.of(
					"com.soklet.McpAdmissionDecision", Set.of(
							"com.soklet.McpAdmissionDecision$Accepted",
							"com.soklet.McpAdmissionDecision$Rejected"),
					"com.soklet.McpCompletePayload", Set.of(
							"com.soklet.McpPromptOutput",
							"com.soklet.McpResourceOutput",
							"com.soklet.McpToolOutput"),
					"com.soklet.McpContentBlock", Set.of(
							"com.soklet.McpAudioContent",
							"com.soklet.McpEmbeddedResource",
							"com.soklet.McpImageContent",
							"com.soklet.McpResourceLink",
							"com.soklet.McpTextContent"),
					"com.soklet.McpJsonValue", Set.of(
							"com.soklet.McpJsonArray",
							"com.soklet.McpJsonBoolean",
							"com.soklet.McpJsonNull",
							"com.soklet.McpJsonNumber",
							"com.soklet.McpJsonObject",
							"com.soklet.McpJsonString"),
					"com.soklet.McpRateLimitDecision", Set.of(
							"com.soklet.McpRateLimitDecision$Allowed",
							"com.soklet.McpRateLimitDecision$Denied"),
					"com.soklet.McpResourceContents", Set.of(
							"com.soklet.McpBlobResourceContents",
							"com.soklet.McpTextResourceContents"),
					"com.soklet.McpServer", Set.of(
							"com.soklet.DefaultMcpServer"));
	private static final Set<String> PHASE_FOUR_NON_SEALED_TYPES = Set.of();
	private static final Map<String, List<String>> PHASE_FOUR_MCP_ENUM_VALUES =
			Map.ofEntries(
					Map.entry("com.soklet.McpAbsentOriginPolicy",
							List.of("ALLOW", "REQUIRE_ORIGIN")),
					Map.entry("com.soklet.McpCacheScope",
							List.of("PUBLIC", "PRIVATE")),
					Map.entry("com.soklet.McpClientCapability", List.of(
							"ELICITATION_FORM", "ELICITATION_URL", "SAMPLING",
							"SAMPLING_CONTEXT", "SAMPLING_TOOLS", "ROOTS")),
					Map.entry("com.soklet.McpIconTheme",
							List.of("LIGHT", "DARK")),
					Map.entry("com.soklet.McpJsonNull", List.of("INSTANCE")),
					Map.entry("com.soklet.McpLogLevel", List.of(
							"DEBUG", "INFO", "NOTICE", "WARNING", "ERROR",
							"CRITICAL", "ALERT", "EMERGENCY")),
					Map.entry("com.soklet.McpRateLimitTarget",
							List.of("REQUEST", "TOOL")),
					Map.entry("com.soklet.McpResourceAddressType",
							List.of("URI", "URI_TEMPLATE")),
					Map.entry("com.soklet.McpRole",
							List.of("USER", "ASSISTANT")),
					Map.entry("com.soklet.McpUnknownMirroredHeaderPolicy",
							List.of("IGNORE", "REJECT_REQUESTS")));

	@Test
	public void phaseFourSealedHierarchyRemainsExact() throws Exception {
		Map<String, Set<String>> actualPermittedTypes = new TreeMap<>();
		Set<String> actualNonSealedTypes = new java.util.TreeSet<>();

		for (Class<?> type : phaseFourTypes()) {
			if (type.isSealed())
				actualPermittedTypes.put(type.getName(), Arrays.stream(
						type.getPermittedSubclasses()).map(Class::getName)
						.collect(java.util.stream.Collectors.toUnmodifiableSet()));
			if (isNonSealed(type))
				actualNonSealedTypes.add(type.getName());
		}

		Assertions.assertEquals(PHASE_FOUR_PERMITTED_TYPES,
				actualPermittedTypes,
				"Phase 4 sealed types or their exact permitted-subclass sets changed");
		Assertions.assertEquals(PHASE_FOUR_NON_SEALED_TYPES,
				actualNonSealedTypes,
				"Phase 4 non-sealed type declarations changed");
	}

	@Test
	public void phaseFourPublicPrimitiveConstantsRetainExactValues()
			throws Exception {
		Map<String, Object> actualConstants = new TreeMap<>();

		for (Class<?> type : phaseFourTypes()) {
			for (Field field : type.getDeclaredFields()) {
				int modifiers = field.getModifiers();
				if (Modifier.isPublic(modifiers) && Modifier.isStatic(modifiers)
						&& Modifier.isFinal(modifiers)
						&& field.getType().isPrimitive())
					actualConstants.put(type.getName() + "#" + field.getName(),
							field.get(null));
			}
		}

		Assertions.assertEquals(Map.of(
				"com.soklet.McpAdmissionIdentity#MAXIMUM_PARTITION_KEY_UTF_8_BYTES",
				256,
				"com.soklet.McpJsonRpcError#SOKLET_RATE_LIMIT_ERROR_CODE",
				-31999,
				"com.soklet.McpJsonRpcError#SOKLET_STRICT_UNKNOWN_MIRRORED_HEADER_ERROR_CODE",
				-31998), actualConstants,
				"Phase 4 public static-final primitive constants changed");
	}

	@Test
	public void phaseFourMcpEnumsRetainDeclarationOrder() throws Exception {
		Map<String, List<String>> actualValues = new TreeMap<>();

		for (Class<?> type : phaseFourTypes()) {
			if (type.isEnum() && type.getSimpleName().startsWith("Mcp"))
				actualValues.put(type.getName(), Arrays.stream(type.getEnumConstants())
						.map(value -> ((Enum<?>) value).name()).toList());
		}

		Assertions.assertEquals(PHASE_FOUR_MCP_ENUM_VALUES, actualValues,
				"Phase 4 MCP enum declarations or value order changed");
	}

	@Test
	public void phaseFourJSpecifyNullabilityLayoutRemainsExact()
			throws Exception {
		String canonicalContract = canonicalPhaseFourNullabilityContract();
		String actualDigest = sha256(canonicalContract);

		Assertions.assertEquals(PHASE_FOUR_NULLABILITY_SHA_256, actualDigest,
				() -> "Phase 4 @NonNull/@Nullable type-use layout changed. "
						+ "Review the canonical contract below, then deliberately "
						+ "update PHASE_FOUR_NULLABILITY_SHA_256 if the change is "
						+ "approved.\nExpected SHA-256: "
						+ PHASE_FOUR_NULLABILITY_SHA_256 + "\nActual SHA-256:   "
						+ actualDigest + "\nCanonical contract:\n"
						+ canonicalContract);
	}

	@Test
	public void extensionPointParameterNamesRetainTheirDocumentedOrder()
			throws Exception {
		assertParameterNames(McpToolHandler.class.getMethod("handle",
				McpRequestContext.class, McpToolCallContext.class,
				McpInvocationFeatures.class), "request", "call", "features");
		assertParameterNames(McpCompleteToolHandler.class.getMethod("handle",
				McpRequestContext.class, McpToolCallContext.class,
				McpInvocationFeatures.class), "request", "call", "features");
		assertParameterNames(McpPromptHandler.class.getMethod("handle",
				McpRequestContext.class, McpPromptGetContext.class,
				McpInvocationFeatures.class), "request", "prompt", "features");
		assertParameterNames(McpResourceHandler.class.getMethod("handle",
				McpRequestContext.class, McpResourceReadContext.class,
				McpInvocationFeatures.class), "request", "resource", "features");
		assertParameterNames(McpResourceListHandler.class.getMethod("handle",
				McpRequestContext.class, McpResourceListContext.class,
				McpInvocationFeatures.class), "request", "list", "features");
		assertParameterNames(McpHandlerInterceptor.class.getMethod(
				"interceptHandler", McpRequestContext.class,
				McpHandlerInvocation.class), "context", "invocation");
		assertParameterNames(McpRequestAdmissionPolicy.class.getMethod("admit",
				McpAdmissionContext.class), "context");
		assertParameterNames(McpRateLimiter.class.getMethod("acquire",
				McpRateLimitContext.class), "context");
		assertParameterNames(McpToolOutputSanitizer.class.getMethod("sanitize",
				McpRequestContext.class, String.class, McpJsonObject.class,
				McpToolOutput.class), "request", "toolName", "rawArguments",
				"output");

		assertParameterNames(CorsAuthorizer.class.getMethod("authorizePreflight",
				Request.class, CorsPreflight.class, Set.class), "request",
				"corsPreflight", "availableHttpMethods");
		assertParameterNames(LifecycleObserver.class.getMethod(
				"willStartMcpServer", McpServer.class), "mcpServer");
		assertParameterNames(LifecycleObserver.class.getMethod(
				"didStartMcpServer", McpServer.class), "mcpServer");
		assertParameterNames(LifecycleObserver.class.getMethod(
				"didFailToStartMcpServer", McpServer.class, Throwable.class),
				"mcpServer", "throwable");
		assertParameterNames(LifecycleObserver.class.getMethod(
				"willStopMcpServer", McpServer.class), "mcpServer");
		assertParameterNames(LifecycleObserver.class.getMethod(
				"didStopMcpServer", McpServer.class, McpShutdownOutcome.class),
				"mcpServer", "shutdownOutcome");
		assertParameterNames(LifecycleObserver.class.getMethod(
				"didFailToStopMcpServer", McpServer.class, Throwable.class),
				"mcpServer", "throwable");
		assertParameterNames(LifecycleObserver.class.getMethod(
				"didStartMcpRequestHandling", McpRequestContext.class), "context");
		assertParameterNames(LifecycleObserver.class.getMethod(
				"didFinishMcpRequestHandling", McpRequestContext.class,
				McpRequestOutcome.class, McpJsonRpcError.class, Duration.class,
				List.class), "context", "outcome", "error", "duration",
				"throwables");
		assertParameterNames(MetricsCollector.class.getMethod(
				"didRecordMcpMetricsEvent", McpMetricsEvent.class), "event");
	}

	@Test
	public void publicRecordComponentsRetainTheirNamesAndDeclarationOrder()
			throws Exception {
		assertRecordContract(McpAdmissionDecision.Accepted.class, "identity");
		assertRecordContract(McpAdmissionDecision.Rejected.class, "rejection");
		assertRecordContract(McpJsonBoolean.class, "value");
		assertRecordContract(McpJsonNumber.class, "value");
		assertRecordContract(McpJsonString.class, "value");
		assertRecordContract(McpPromptMessage.class, "role", "content");
		assertRecordContract(McpRateLimitDecision.Allowed.class);
		assertRecordContract(McpRateLimitDecision.Denied.class, "retryAfter");
	}

	@Test
	public void corsAuthorizerOptionalPayloadsRemainExplicitlyNonNull()
			throws Exception {
		assertNonNullOptionalPayload(CorsAuthorizer.class.getMethod("authorize",
				Request.class, Cors.class), CorsResponse.class);
		assertNonNullOptionalPayload(CorsAuthorizer.class.getMethod(
				"authorizePreflight", Request.class, CorsPreflight.class,
				Map.class), CorsPreflightResponse.class);
		assertNonNullOptionalPayload(CorsAuthorizer.class.getMethod(
				"authorizePreflight", Request.class, CorsPreflight.class,
				Set.class), CorsPreflightResponse.class);
	}

	@Test
	public void laterPhaseDescriptorsOnPhaseFourHostsRemainExact()
			throws Exception {
		Method endpointSubscriptions = assertInstanceMethod(
				McpEndpoint.class, "getSubscriptions", Optional.class,
				MethodShape.CONCRETE, false);
		assertParameterizedType(endpointSubscriptions.getGenericReturnType(),
				null, Optional.class, McpSubscriptionConfig.class);
		assertNoGenericParameters(endpointSubscriptions);
		assertErasedGenericSignature(assertInstanceMethod(
				McpEndpoint.Builder.class, "subscriptions",
				McpEndpoint.Builder.class, MethodShape.CONCRETE, false,
				McpSubscriptionConfig.class));

		Method resolverSubscriptions = assertInstanceMethod(
				McpHandlerResolver.class, "withSubscriptions",
				McpHandlerResolver.class, MethodShape.ABSTRACT, false,
				Class.class, McpSubscriptionConfig.class);
		Assertions.assertEquals(McpHandlerResolver.class,
				resolverSubscriptions.getGenericReturnType());
		Type[] resolverParameterTypes =
				resolverSubscriptions.getGenericParameterTypes();
		assertUnboundedClassWildcard(resolverParameterTypes[0]);
		Assertions.assertEquals(McpSubscriptionConfig.class,
				resolverParameterTypes[1]);

		assertErasedGenericSignature(assertInstanceMethod(
				McpRequestContext.class, "getInputResponses",
				McpInputResponses.class, MethodShape.DEFAULT, false));
		Method requestState = assertInstanceMethod(McpRequestContext.class,
				"getRequestState", Optional.class, MethodShape.DEFAULT, false);
		assertParameterizedType(requestState.getGenericReturnType(), null,
				Optional.class, McpRequestState.class);
		assertNoGenericParameters(requestState);

		assertRegistrationDescriptors();
		assertMrtrAnnotationDefaults(McpTool.class);
		assertMrtrAnnotationDefaults(McpPrompt.class);
		assertMrtrAnnotationDefaults(McpResource.class);

		assertErasedGenericSignature(assertInstanceMethod(McpServer.class,
				"getProtectionControl", McpProtectionControl.class,
				MethodShape.ABSTRACT, false));
		assertErasedGenericSignature(assertInstanceMethod(McpServer.class,
				"getTraceCorrelation", McpTraceCorrelation.class,
				MethodShape.ABSTRACT, false));
		assertErasedGenericSignature(assertInstanceMethod(McpServer.Builder.class,
				"protectionConfig", McpServer.Builder.class,
				MethodShape.CONCRETE, false, McpProtectionConfig.class));
		assertErasedGenericSignature(assertInstanceMethod(McpServer.Builder.class,
				"traceCorrelationKey", McpServer.Builder.class,
				MethodShape.CONCRETE, false, McpTraceCorrelationKey.class));
		assertErasedGenericSignature(assertInstanceMethod(McpServer.Builder.class,
				"logRawValidatedTraceIds", McpServer.Builder.class,
				MethodShape.CONCRETE, false, boolean.class));

		assertErasedGenericSignature(assertInstanceMethod(McpServer.Builder.class,
				"streamQueueCapacity", McpServer.Builder.class,
				MethodShape.CONCRETE, false, int.class));
		assertErasedGenericSignature(assertInstanceMethod(McpServer.Builder.class,
				"writeTimeout", McpServer.Builder.class, MethodShape.CONCRETE,
				false, Duration.class));
		assertErasedGenericSignature(assertInstanceMethod(McpServer.Builder.class,
				"keepAliveInterval", McpServer.Builder.class,
				MethodShape.CONCRETE, false, Duration.class));
		assertErasedGenericSignature(assertInstanceMethod(McpServer.Builder.class,
				"shutdownTimeout", McpServer.Builder.class,
				MethodShape.CONCRETE, false, Duration.class));
		assertErasedGenericSignature(assertInstanceMethod(McpServer.Builder.class,
				"maximumSubscriptionsPerPrincipal", McpServer.Builder.class,
				MethodShape.CONCRETE, false, int.class));
		assertErasedGenericSignature(assertInstanceMethod(McpServer.Builder.class,
				"maximumSubscriptionDuration", McpServer.Builder.class,
				MethodShape.CONCRETE, false, Duration.class));
	}

	private static void assertRegistrationDescriptors() throws Exception {
		assertInputRequestDeclarationsGetter(McpToolRegistration.class);
		assertErasedGenericSignature(assertInstanceMethod(
				McpToolRegistration.class, "getRequestStateMode",
				McpRequestStateMode.class, MethodShape.CONCRETE, false));

		Type toolBuilderVariable =
				McpToolRegistration.Builder.class.getTypeParameters()[0];
		Method toolMayRequestInput = assertInstanceMethod(
				McpToolRegistration.Builder.class, "mayRequestInput",
				McpToolRegistration.Builder.class, MethodShape.CONCRETE, true,
				McpInputRequestDeclaration[].class);
		assertParameterizedType(toolMayRequestInput.getGenericReturnType(),
				McpToolRegistration.class, McpToolRegistration.Builder.class,
				toolBuilderVariable);
		assertNoGenericParameterChanges(toolMayRequestInput);
		Method toolRequestStateMode = assertInstanceMethod(
				McpToolRegistration.Builder.class, "requestStateMode",
				McpToolRegistration.Builder.class, MethodShape.CONCRETE, false,
				McpRequestStateMode.class);
		assertParameterizedType(toolRequestStateMode.getGenericReturnType(),
				McpToolRegistration.class, McpToolRegistration.Builder.class,
				toolBuilderVariable);
		assertNoGenericParameterChanges(toolRequestStateMode);

		assertInputRequestDeclarationsGetter(McpPromptRegistration.class);
		assertErasedGenericSignature(assertInstanceMethod(
				McpPromptRegistration.class, "getRequestStateMode",
				McpRequestStateMode.class, MethodShape.CONCRETE, false));
		assertErasedGenericSignature(assertInstanceMethod(
				McpPromptRegistration.Builder.class, "mayRequestInput",
				McpPromptRegistration.Builder.class, MethodShape.CONCRETE, true,
				McpInputRequestDeclaration[].class));
		assertErasedGenericSignature(assertInstanceMethod(
				McpPromptRegistration.Builder.class, "requestStateMode",
				McpPromptRegistration.Builder.class, MethodShape.CONCRETE, false,
				McpRequestStateMode.class));

		assertInputRequestDeclarationsGetter(McpResourceRegistration.class);
		assertErasedGenericSignature(assertInstanceMethod(
				McpResourceRegistration.class, "getRequestStateMode",
				McpRequestStateMode.class, MethodShape.CONCRETE, false));
		assertErasedGenericSignature(assertInstanceMethod(
				McpResourceRegistration.ExactBuilder.class, "mayRequestInput",
				McpResourceRegistration.ExactBuilder.class,
				MethodShape.CONCRETE, true,
				McpInputRequestDeclaration[].class));
		assertErasedGenericSignature(assertInstanceMethod(
				McpResourceRegistration.ExactBuilder.class, "requestStateMode",
				McpResourceRegistration.ExactBuilder.class,
				MethodShape.CONCRETE, false, McpRequestStateMode.class));
		assertErasedGenericSignature(assertInstanceMethod(
				McpResourceRegistration.TemplateBuilder.class, "mayRequestInput",
				McpResourceRegistration.TemplateBuilder.class,
				MethodShape.CONCRETE, true,
				McpInputRequestDeclaration[].class));
		assertErasedGenericSignature(assertInstanceMethod(
				McpResourceRegistration.TemplateBuilder.class,
				"requestStateMode",
				McpResourceRegistration.TemplateBuilder.class,
				MethodShape.CONCRETE, false, McpRequestStateMode.class));
	}

	private static void assertInputRequestDeclarationsGetter(Class<?> owner)
			throws Exception {
		Method method = assertInstanceMethod(owner,
				"getInputRequestDeclarations", List.class, MethodShape.CONCRETE,
				false);
		assertParameterizedType(method.getGenericReturnType(), null, List.class,
				McpInputRequestDeclaration.class);
		assertNoGenericParameters(method);
	}

	private static void assertMrtrAnnotationDefaults(
			Class<? extends Annotation> annotationType) throws Exception {
		Method mayRequestInput = assertInstanceMethod(annotationType,
				"mayRequestInput", McpMayRequestInput[].class,
				MethodShape.ABSTRACT, false);
		assertErasedGenericSignature(mayRequestInput);
		McpMayRequestInput[] mayRequestInputDefault = Assertions.assertInstanceOf(
				McpMayRequestInput[].class, mayRequestInput.getDefaultValue());
		Assertions.assertArrayEquals(new McpMayRequestInput[0],
				mayRequestInputDefault,
				() -> annotationType.getName()
						+ "#mayRequestInput() default changed");

		Method requestStateMode = assertInstanceMethod(annotationType,
				"requestStateMode", McpRequestStateMode.class,
				MethodShape.ABSTRACT, false);
		assertErasedGenericSignature(requestStateMode);
		Assertions.assertSame(McpRequestStateMode.NONE,
				requestStateMode.getDefaultValue(),
				() -> annotationType.getName()
						+ "#requestStateMode() default changed");
	}

	private enum MethodShape {
		CONCRETE,
		ABSTRACT,
		DEFAULT
	}

	private static Method assertInstanceMethod(Class<?> owner, String name,
			Class<?> returnType, MethodShape shape, boolean varArgs,
			Class<?>... parameterTypes) throws NoSuchMethodException {
		Method method = owner.getMethod(name, parameterTypes);
		String description = owner.getName() + "#" + name;

		Assertions.assertSame(owner, method.getDeclaringClass(),
				() -> description + " must remain declared on its reviewed host");
		Assertions.assertTrue(Modifier.isPublic(method.getModifiers()),
				() -> description + " must remain public");
		Assertions.assertFalse(Modifier.isStatic(method.getModifiers()),
				() -> description + " must remain an instance method");
		Assertions.assertEquals(returnType, method.getReturnType(),
				() -> description + " erased return type changed");
		Assertions.assertArrayEquals(parameterTypes, method.getParameterTypes(),
				() -> description + " erased parameter types changed");
		Assertions.assertEquals(shape == MethodShape.ABSTRACT,
				Modifier.isAbstract(method.getModifiers()),
				() -> description + " abstract/concrete shape changed");
		Assertions.assertEquals(shape == MethodShape.DEFAULT, method.isDefault(),
				() -> description + " default-method shape changed");
		Assertions.assertEquals(varArgs, method.isVarArgs(),
				() -> description + " varargs shape changed");
		Assertions.assertFalse(method.isBridge(),
				() -> description + " must not become a bridge method");
		Assertions.assertFalse(method.isSynthetic(),
				() -> description + " must not become synthetic");
		return method;
	}

	private static void assertErasedGenericSignature(Method method) {
		String description = method.getDeclaringClass().getName() + "#"
				+ method.getName();
		Assertions.assertEquals(method.getReturnType(),
				method.getGenericReturnType(),
				() -> description + " generic return type changed");
		assertNoGenericParameterChanges(method);
	}

	private static void assertNoGenericParameterChanges(Method method) {
		Assertions.assertArrayEquals(method.getParameterTypes(),
				method.getGenericParameterTypes(),
				() -> method.getDeclaringClass().getName() + "#"
						+ method.getName() + " generic parameter types changed");
	}

	private static void assertNoGenericParameters(Method method) {
		Assertions.assertEquals(0, method.getGenericParameterTypes().length,
				() -> method.getDeclaringClass().getName() + "#"
						+ method.getName() + " gained parameters");
	}

	private static void assertParameterizedType(Type type, Type expectedOwner,
			Class<?> expectedRawType, Type... expectedArguments) {
		ParameterizedType parameterizedType = Assertions.assertInstanceOf(
				ParameterizedType.class, type);
		Assertions.assertEquals(expectedOwner, parameterizedType.getOwnerType(),
				"Generic owner type changed");
		Assertions.assertEquals(expectedRawType, parameterizedType.getRawType(),
				"Generic raw type changed");
		Assertions.assertArrayEquals(expectedArguments,
				parameterizedType.getActualTypeArguments(),
				"Generic type arguments changed");
	}

	private static void assertUnboundedClassWildcard(Type type) {
		ParameterizedType parameterizedType = Assertions.assertInstanceOf(
				ParameterizedType.class, type);
		Assertions.assertNull(parameterizedType.getOwnerType(),
				"Class<?> owner type changed");
		Assertions.assertEquals(Class.class, parameterizedType.getRawType(),
				"Class<?> raw type changed");
		Type[] arguments = parameterizedType.getActualTypeArguments();
		Assertions.assertEquals(1, arguments.length,
				"Class<?> type-argument count changed");
		WildcardType wildcard = Assertions.assertInstanceOf(WildcardType.class,
				arguments[0]);
		Assertions.assertArrayEquals(new Type[0], wildcard.getLowerBounds(),
				"Class<?> gained a lower bound");
		Assertions.assertArrayEquals(new Type[] { Object.class },
				wildcard.getUpperBounds(), "Class<?> upper bound changed");
	}

	private static List<Class<?>> phaseFourTypes() throws Exception {
		List<String> typeNames = Files.readAllLines(PHASE_FOUR_INCLUDES,
				StandardCharsets.UTF_8).stream()
				.map(String::trim)
				.filter(line -> !line.isEmpty() && !line.startsWith("#"))
				.toList();

		Assertions.assertEquals(PHASE_FOUR_TYPE_COUNT, typeNames.size(),
				"The reviewed Phase 4 type count changed");
		Assertions.assertEquals(typeNames.stream().sorted().toList(), typeNames,
				"The reviewed Phase 4 type inventory must remain sorted");
		Assertions.assertEquals(typeNames.size(), Set.copyOf(typeNames).size(),
				"The reviewed Phase 4 type inventory contains duplicates");

		List<Class<?>> types = new ArrayList<>(typeNames.size());
		ClassLoader classLoader =
				McpPublicApiReflectionContractTests.class.getClassLoader();

		for (String typeName : typeNames)
			types.add(Class.forName(typeName, false, classLoader));

		return List.copyOf(types);
	}

	private static boolean isNonSealed(Class<?> type) {
		if (type.isSealed() || Modifier.isFinal(type.getModifiers()))
			return false;

		Class<?> superclass = type.getSuperclass();
		if (superclass != null && directlyPermits(superclass, type))
			return true;

		return Arrays.stream(type.getInterfaces())
				.anyMatch(parent -> directlyPermits(parent, type));
	}

	private static boolean directlyPermits(Class<?> parent, Class<?> child) {
		return parent.isSealed() && Arrays.asList(parent.getPermittedSubclasses())
				.contains(child);
	}

	private static String canonicalPhaseFourNullabilityContract()
			throws Exception {
		List<String> lines = new ArrayList<>();

		for (Class<?> type : phaseFourTypes()) {
			String typeOwner = "TYPE|" + type.getName();
			lines.add(typeOwner);
			appendTypeParameters(lines, typeOwner, type.getTypeParameters());

			AnnotatedType superclass = type.getAnnotatedSuperclass();
			if (superclass != null)
				lines.add(typeOwner + "|SUPER|" + canonical(superclass));

			Arrays.stream(type.getAnnotatedInterfaces())
					.map(McpPublicApiReflectionContractTests::canonical)
					.sorted()
					.forEach(contract -> lines.add(
							typeOwner + "|INTERFACE|" + contract));

			Arrays.stream(type.getDeclaredFields())
					.filter(field -> isPublicOrProtected(field.getModifiers()))
					.map(field -> "FIELD|" + type.getName() + "#"
							+ field.getName() + "|TYPE|"
							+ canonical(field.getAnnotatedType()))
					.sorted()
					.forEach(lines::add);

			for (Constructor<?> constructor : Arrays.stream(
					type.getDeclaredConstructors())
					.filter(candidate -> isPublicOrProtected(
							candidate.getModifiers()))
					.sorted(Comparator.comparing(
							McpPublicApiReflectionContractTests::executableId))
					.toList())
				appendExecutable(lines, constructor,
						"CONSTRUCTOR|" + executableId(constructor));

			for (Method method : Arrays.stream(type.getDeclaredMethods())
					.filter(candidate -> isPublicOrProtected(
							candidate.getModifiers()))
					.sorted(Comparator.comparing(
							McpPublicApiReflectionContractTests::methodId))
					.toList()) {
				String methodOwner = "METHOD|" + methodId(method);
				lines.add(methodOwner + "|RETURN|"
						+ canonical(method.getAnnotatedReturnType()));
				appendExecutable(lines, method, methodOwner);
			}

			RecordComponent[] recordComponents = type.getRecordComponents();
			if (recordComponents != null) {
				for (int index = 0; index < recordComponents.length; ++index) {
					RecordComponent component = recordComponents[index];
					lines.add("RECORD_COMPONENT|" + type.getName() + "|"
							+ index + ":" + component.getName() + "|TYPE|"
							+ canonical(component.getAnnotatedType()));
				}
			}
		}

		lines.sort(Comparator.naturalOrder());
		return String.join("\n", lines) + "\n";
	}

	private static void appendExecutable(List<String> lines,
			Executable executable, String owner) {
		lines.add(owner);
		appendTypeParameters(lines, owner, executable.getTypeParameters());

		AnnotatedType receiver = executable.getAnnotatedReceiverType();
		if (receiver != null)
			lines.add(owner + "|RECEIVER|" + canonical(receiver));

		AnnotatedType[] parameterTypes = executable.getAnnotatedParameterTypes();
		for (int index = 0; index < parameterTypes.length; ++index)
			lines.add(owner + "|PARAMETER[" + index + "]|"
					+ canonical(parameterTypes[index]));

		Arrays.stream(executable.getAnnotatedExceptionTypes())
				.map(McpPublicApiReflectionContractTests::canonical)
				.sorted()
				.forEach(contract -> lines.add(owner + "|THROWS|" + contract));
	}

	private static void appendTypeParameters(List<String> lines, String owner,
			TypeVariable<?>[] typeParameters) {
		for (int parameterIndex = 0; parameterIndex < typeParameters.length;
				++parameterIndex) {
			TypeVariable<?> typeParameter = typeParameters[parameterIndex];
			String parameterOwner = owner + "|TYPE_PARAMETER[" + parameterIndex
					+ "]";
			lines.add(parameterOwner);

			AnnotatedType[] bounds = typeParameter.getAnnotatedBounds();
			for (int boundIndex = 0; boundIndex < bounds.length; ++boundIndex)
				lines.add(parameterOwner + "|BOUND[" + boundIndex + "]|"
						+ canonical(bounds[boundIndex]));
		}
	}

	private static boolean isPublicOrProtected(int modifiers) {
		return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers);
	}

	private static String executableId(Executable executable) {
		return executable.getDeclaringClass().getName() + "#<init>("
				+ Arrays.stream(executable.getParameterTypes())
						.map(Class::getName).collect(
								java.util.stream.Collectors.joining(","))
				+ ")";
	}

	private static String methodId(Method method) {
		return method.getDeclaringClass().getName() + "#" + method.getName()
				+ "(" + Arrays.stream(method.getParameterTypes())
						.map(Class::getName).collect(
								java.util.stream.Collectors.joining(","))
				+ ")->" + method.getReturnType().getName()
				+ "|bridge=" + method.isBridge()
				+ "|synthetic=" + method.isSynthetic();
	}

	private static String canonical(AnnotatedType annotatedType) {
		List<String> annotations = new ArrayList<>();
		appendNullabilityAnnotations(annotations, annotatedType, "root");
		annotations.sort(Comparator.naturalOrder());
		return canonical(annotatedType.getType()) + "|nullness=["
				+ String.join(",", annotations) + "]";
	}

	private static void appendNullabilityAnnotations(List<String> annotations,
			AnnotatedType annotatedType, String path) {
		Arrays.stream(annotatedType.getAnnotations())
				.map(Annotation::annotationType)
				.filter(annotationType -> annotationType == NonNull.class
						|| annotationType == Nullable.class)
				.map(Class::getSimpleName)
				.sorted()
				.forEach(annotation -> annotations.add(path + "=" + annotation));

		AnnotatedType owner = annotatedType.getAnnotatedOwnerType();
		if (owner != null)
			appendNullabilityAnnotations(annotations, owner, path + ".owner");

		if (annotatedType instanceof AnnotatedParameterizedType parameterized) {
			AnnotatedType[] arguments =
					parameterized.getAnnotatedActualTypeArguments();
			for (int index = 0; index < arguments.length; ++index)
				appendNullabilityAnnotations(annotations, arguments[index],
						path + ".argument[" + index + "]");
		} else if (annotatedType instanceof AnnotatedArrayType array) {
			appendNullabilityAnnotations(annotations,
					array.getAnnotatedGenericComponentType(), path + ".component");
		} else if (annotatedType instanceof AnnotatedWildcardType wildcard) {
			AnnotatedType[] lowerBounds = wildcard.getAnnotatedLowerBounds();
			for (int index = 0; index < lowerBounds.length; ++index)
				appendNullabilityAnnotations(annotations, lowerBounds[index],
						path + ".lower[" + index + "]");
			AnnotatedType[] upperBounds = wildcard.getAnnotatedUpperBounds();
			for (int index = 0; index < upperBounds.length; ++index)
				appendNullabilityAnnotations(annotations, upperBounds[index],
						path + ".upper[" + index + "]");
		}
	}

	private static String canonical(Type type) {
		if (type instanceof Class<?> classType) {
			if (classType.isArray())
				return "array(" + canonical(classType.getComponentType()) + ")";
			return "class(" + classType.getName() + ")";
		}
		if (type instanceof ParameterizedType parameterized) {
			String owner = parameterized.getOwnerType() == null ? "none"
					: canonical(parameterized.getOwnerType());
			return "parameterized(raw=" + canonical(parameterized.getRawType())
					+ ",owner=" + owner + ",arguments=["
					+ Arrays.stream(parameterized.getActualTypeArguments())
							.map(McpPublicApiReflectionContractTests::canonical)
							.collect(java.util.stream.Collectors.joining(","))
					+ "])";
		}
		if (type instanceof GenericArrayType array)
			return "array(" + canonical(array.getGenericComponentType()) + ")";
		if (type instanceof TypeVariable<?> variable)
			return canonical(variable);
		if (type instanceof WildcardType wildcard)
			return "wildcard(lower=[" + Arrays.stream(wildcard.getLowerBounds())
					.map(McpPublicApiReflectionContractTests::canonical)
					.collect(java.util.stream.Collectors.joining(","))
					+ "],upper=[" + Arrays.stream(wildcard.getUpperBounds())
					.map(McpPublicApiReflectionContractTests::canonical)
					.collect(java.util.stream.Collectors.joining(",")) + "])";

		throw new IllegalArgumentException("Unsupported reflective type: " + type);
	}

	private static String canonical(TypeVariable<?> variable) {
		Object declaration = variable.getGenericDeclaration();
		String declarationId;

		if (declaration instanceof Class<?> declaringType)
			declarationId = "type(" + declaringType.getName() + ")";
		else if (declaration instanceof Method declaringMethod)
			declarationId = "method(" + methodId(declaringMethod) + ")";
		else if (declaration instanceof Constructor<?> declaringConstructor)
			declarationId = "constructor("
					+ executableId(declaringConstructor) + ")";
		else
			throw new IllegalArgumentException(
					"Unsupported generic declaration: " + declaration);

		TypeVariable<?>[] parameters = variable.getGenericDeclaration()
				.getTypeParameters();
		int index = Arrays.asList(parameters).indexOf(variable);
		if (index < 0)
			throw new IllegalArgumentException(
					"Type variable is absent from its declaration: " + variable);

		return "variable(declaration=" + declarationId + ",index=" + index + ")";
	}

	private static String sha256(String value)
			throws NoSuchAlgorithmException {
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		return HexFormat.of().formatHex(digest.digest(
				value.getBytes(StandardCharsets.UTF_8)));
	}

	private static void assertNonNullOptionalPayload(Method method,
			Class<?> expectedPayloadType) {
		Assertions.assertEquals(Optional.class, method.getReturnType());
		AnnotatedType returnType = method.getAnnotatedReturnType();
		Assertions.assertTrue(returnType.isAnnotationPresent(NonNull.class));
		Assertions.assertInstanceOf(AnnotatedParameterizedType.class, returnType);
		AnnotatedType[] arguments = ((AnnotatedParameterizedType) returnType)
				.getAnnotatedActualTypeArguments();
		Assertions.assertEquals(1, arguments.length);
		Assertions.assertEquals(expectedPayloadType, arguments[0].getType());
		Assertions.assertTrue(arguments[0].isAnnotationPresent(NonNull.class));
	}

	private static void assertRecordContract(Class<?> recordType,
			String... expectedNames) throws Exception {
		Assertions.assertTrue(recordType.isRecord(),
				() -> recordType.getName() + " must remain a record");
		RecordComponent[] components = recordType.getRecordComponents();
		Assertions.assertArrayEquals(expectedNames, Arrays.stream(components)
				.map(RecordComponent::getName).toArray(String[]::new),
				() -> recordType.getName()
						+ " record components changed name or declaration order");

		Class<?>[] componentTypes = Arrays.stream(components)
				.map(RecordComponent::getType).toArray(Class<?>[]::new);
		assertParameterNames(recordType.getDeclaredConstructor(componentTypes),
				expectedNames);
	}

	private static void assertParameterNames(Executable executable,
			String... expectedNames) {
		Parameter[] parameters = executable.getParameters();
		String description = executable.getDeclaringClass().getName() + "#"
				+ executable.getName();
		Assertions.assertTrue(Arrays.stream(parameters)
				.allMatch(Parameter::isNamePresent),
				() -> description + " must retain MethodParameters metadata");
		Assertions.assertArrayEquals(expectedNames, Arrays.stream(parameters)
				.map(Parameter::getName).toArray(String[]::new),
				() -> description + " parameter names or order changed");
	}
}
