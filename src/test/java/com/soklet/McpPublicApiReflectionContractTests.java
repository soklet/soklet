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
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
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
import java.net.URI;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Reflection contracts for reviewed MCP public API details that ordinary
 * binary compatibility comparison does not reliably preserve.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class McpPublicApiReflectionContractTests {
	private static final Path PHASE_FOUR_INCLUDES =
			Path.of("api/mcp/phase-4.includes");
	private static final Path PHASE_FIVE_INCLUDES =
			Path.of("api/mcp/phase-5.includes");
	private static final List<Path> MCP_API_INCLUDES = List.of(
			PHASE_FOUR_INCLUDES,
			PHASE_FIVE_INCLUDES,
			Path.of("api/mcp/phase-6.includes"),
			Path.of("api/mcp/provisional.includes"));
	private static final int PHASE_FOUR_TYPE_COUNT = 133;
	private static final int PHASE_FIVE_TYPE_COUNT = 36;
	private static final int PHASE_SIX_TYPE_COUNT = 64;
	private static final int PROVISIONAL_TYPE_COUNT = 0;
	private static final int CURRENT_MCP_TYPE_COUNT = 233;
	private static final String PHASE_FOUR_NULLABILITY_SHA_256 =
			"9cfe146213f1c96cfdd1de6fe05caa58d8055f7abdb491b6141491f2dc8de646";
	private static final String PHASE_FIVE_NULLABILITY_SHA_256 =
			"6569e3b106ae11e1d30da66c045d1a9bc23aa65016f36052df6b19fc320c06d9";
	private static final String PHASE_SIX_NULLABILITY_SHA_256 =
			"15f883e66b3194974887899a090e53d33aa27a08db793f4cfd7ff78212b67aaf";
	private static final Map<String, Object> PHASE_FOUR_PRIMITIVE_CONSTANTS =
			Map.of(
					"com.soklet.McpAdmissionIdentity#MAXIMUM_PARTITION_KEY_UTF_8_BYTES",
					256,
					"com.soklet.McpJsonRpcError#SOKLET_RATE_LIMIT_ERROR_CODE",
					-31999,
					"com.soklet.McpJsonRpcError#SOKLET_STRICT_UNKNOWN_MIRRORED_HEADER_ERROR_CODE",
					-31998);
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
	private static final Map<String, Set<String>> PHASE_FIVE_PERMITTED_TYPES =
			Map.of(
					"com.soklet.McpSubscriptionEvent", Set.of(
							"com.soklet.McpSubscriptionEvent$ResourceUpdated",
							"com.soklet.McpSubscriptionEvent$ResourcesListChanged"));
	private static final Set<String> PHASE_FIVE_NON_SEALED_TYPES = Set.of();
	private static final Map<String, List<String>> PHASE_FIVE_MCP_ENUM_VALUES =
			Map.of(
					"com.soklet.McpInputRequirement",
					List.of("REQUIRED", "CONDITIONAL"),
					"com.soklet.McpProtectionMode", List.of(
							"NO_FRAMEWORK_KEYS", "CUSTOM_PROTECTOR",
							"PRODUCTION_KEY_RING", "DEVELOPMENT_EPHEMERAL"),
					"com.soklet.McpRequestStateMode", List.of(
							"NONE", "FRAMEWORK_PROTECTED", "APPLICATION_PROTECTED"),
					"com.soklet.McpRequestStateProtectionException$Reason",
					List.of("INVALID_STATE", "PROTECTOR_UNAVAILABLE"),
					"com.soklet.McpSubscriptionNotificationType",
					List.of("RESOURCES_LIST_CHANGED", "RESOURCE_UPDATED"));
	private static final Map<String, Object> PHASE_FIVE_SCALAR_CONSTANTS =
			Map.of(
					"com.soklet.McpProtectionKeyRingFingerprint#PROFILE",
					"soklet-mcp-protection-v1",
					"com.soklet.McpProtectionKeyRingFingerprint#VERSION",
					"v1");
	private static final Map<String, Set<String>> PHASE_SIX_PERMITTED_TYPES =
			Map.of(
					"com.soklet.McpLocalizationResult", Set.of(
							"com.soklet.McpLocalizationResult$Failure",
							"com.soklet.McpLocalizationResult$Localized",
							"com.soklet.McpLocalizationResult$UseDefaultText"),
					"com.soklet.McpMetricsEvent", Set.of(
							"com.soklet.McpMetricsEvent$CancelationSignaled",
							"com.soklet.McpMetricsEvent$ConnectionAccepted",
							"com.soklet.McpMetricsEvent$ConnectionRejected",
							"com.soklet.McpMetricsEvent$HandlerCapacityRejected",
							"com.soklet.McpMetricsEvent$HandlerDequeued",
							"com.soklet.McpMetricsEvent$HandlerExecutionFinished",
							"com.soklet.McpMetricsEvent$HandlerExecutionStarted",
							"com.soklet.McpMetricsEvent$HandlerQueued",
							"com.soklet.McpMetricsEvent$KeepAliveEmitted",
							"com.soklet.McpMetricsEvent$ProgressEmitted",
							"com.soklet.McpMetricsEvent$ProtocolError",
							"com.soklet.McpMetricsEvent$RequestAccepted",
							"com.soklet.McpMetricsEvent$RequestFinished",
							"com.soklet.McpMetricsEvent$RequestRejected",
							"com.soklet.McpMetricsEvent$RequestStarted",
							"com.soklet.McpMetricsEvent$RequestStreamClosed",
							"com.soklet.McpMetricsEvent$RequestStreamOpened",
							"com.soklet.McpMetricsEvent$ServerStarted",
							"com.soklet.McpMetricsEvent$ServerStopped",
							"com.soklet.McpMetricsEvent$SubscriptionClosed",
							"com.soklet.McpMetricsEvent$SubscriptionOpened",
							"com.soklet.McpMetricsEvent$TransportFailure",
							"com.soklet.McpMetricsEvent$UnknownMirroredHeader"));
	private static final Set<String> PHASE_SIX_NON_SEALED_TYPES = Set.of();
	private static final List<Class<?>> FORMER_PUBLIC_RECORD_TYPES = List.of(
			McpAdmissionDecision.Accepted.class,
			McpAdmissionDecision.Rejected.class,
			McpJsonBoolean.class,
			McpJsonNumber.class,
			McpJsonString.class,
			McpPromptMessage.class,
			McpRateLimitDecision.Allowed.class,
			McpRateLimitDecision.Denied.class,
			McpInputRequest.class,
			McpInputRequestDeclaration.class,
			McpSubscriptionEvent.ResourceUpdated.class,
			McpSubscriptionEvent.ResourcesListChanged.class,
			McpLocalizationResult.Failure.class,
			McpLocalizationResult.Localized.class,
			McpLocalizationResult.UseDefaultText.class,
			McpMetricsEvent.CancelationSignaled.class,
			McpMetricsEvent.ConnectionAccepted.class,
			McpMetricsEvent.ConnectionRejected.class,
			McpMetricsEvent.HandlerCapacityRejected.class,
			McpMetricsEvent.HandlerDequeued.class,
			McpMetricsEvent.HandlerExecutionFinished.class,
			McpMetricsEvent.HandlerExecutionStarted.class,
			McpMetricsEvent.HandlerQueued.class,
			McpMetricsEvent.KeepAliveEmitted.class,
			McpMetricsEvent.ProgressEmitted.class,
			McpMetricsEvent.ProtocolError.class,
			McpMetricsEvent.RequestAccepted.class,
			McpMetricsEvent.RequestFinished.class,
			McpMetricsEvent.RequestRejected.class,
			McpMetricsEvent.RequestStarted.class,
			McpMetricsEvent.RequestStreamClosed.class,
			McpMetricsEvent.RequestStreamOpened.class,
			McpMetricsEvent.ServerStarted.class,
			McpMetricsEvent.ServerStopped.class,
			McpMetricsEvent.SubscriptionClosed.class,
			McpMetricsEvent.SubscriptionOpened.class,
			McpMetricsEvent.TransportFailure.class,
			McpMetricsEvent.UnknownMirroredHeader.class,
			McpMetricsSnapshot.EndpointMethodKey.class,
			McpMetricsSnapshot.RequestOutcomeKey.class,
			McpMetricsSnapshot.RequestStreamTerminationKey.class,
			McpMetricsSnapshot.SubscriptionTerminationKey.class,
			McpTraceCorrelationConfigurationFingerprint.class);

	@Test
	public void phaseFourSealedHierarchyRemainsExact() throws Exception {
		assertSealedHierarchy(phaseFourTypes(), PHASE_FOUR_PERMITTED_TYPES,
				PHASE_FOUR_NON_SEALED_TYPES, "Phase 4");
	}

	@Test
	public void phaseFiveInventoryRetainsExactlyThirtySixOwners()
			throws Exception {
		Assertions.assertEquals(PHASE_FIVE_TYPE_COUNT, phaseFiveTypes().size(),
				"The reviewed Phase 5 owner count changed");
	}

	@Test
	public void phaseSixInventoryAndSharedHostDescriptorsAreExact()
			throws Exception {
		List<String> expectedPhaseSixTypes = List.of(
				"com.soklet.McpLocalizableText",
				"com.soklet.McpLocalizationCatalog",
				"com.soklet.McpLocalizationContext",
				"com.soklet.McpLocalizationContext$Builder",
				"com.soklet.McpLocalizationContextProvider",
				"com.soklet.McpLocalizationControl",
				"com.soklet.McpLocalizationFailurePolicy",
				"com.soklet.McpLocalizationRequest",
				"com.soklet.McpLocalizationResult",
				"com.soklet.McpLocalizationResult$Failure",
				"com.soklet.McpLocalizationResult$Localized",
				"com.soklet.McpLocalizationResult$UseDefaultText",
				"com.soklet.McpLocalizationRevision",
				"com.soklet.McpLocalizer",
				"com.soklet.McpLocalizer$Builder",
				"com.soklet.McpLocalizer$ContextProviderStage",
				"com.soklet.McpMetricsEvent",
				"com.soklet.McpMetricsEvent$CancelationSignaled",
				"com.soklet.McpMetricsEvent$ConnectionAccepted",
				"com.soklet.McpMetricsEvent$ConnectionRejected",
				"com.soklet.McpMetricsEvent$HandlerCapacityRejected",
				"com.soklet.McpMetricsEvent$HandlerDequeued",
				"com.soklet.McpMetricsEvent$HandlerExecutionFinished",
				"com.soklet.McpMetricsEvent$HandlerExecutionStarted",
				"com.soklet.McpMetricsEvent$HandlerQueued",
				"com.soklet.McpMetricsEvent$KeepAliveEmitted",
				"com.soklet.McpMetricsEvent$ProgressEmitted",
				"com.soklet.McpMetricsEvent$ProtocolError",
				"com.soklet.McpMetricsEvent$RequestAccepted",
				"com.soklet.McpMetricsEvent$RequestFinished",
				"com.soklet.McpMetricsEvent$RequestRejected",
				"com.soklet.McpMetricsEvent$RequestStarted",
				"com.soklet.McpMetricsEvent$RequestStreamClosed",
				"com.soklet.McpMetricsEvent$RequestStreamOpened",
				"com.soklet.McpMetricsEvent$ServerStarted",
				"com.soklet.McpMetricsEvent$ServerStopped",
				"com.soklet.McpMetricsEvent$SubscriptionClosed",
				"com.soklet.McpMetricsEvent$SubscriptionOpened",
				"com.soklet.McpMetricsEvent$TransportFailure",
				"com.soklet.McpMetricsEvent$UnknownMirroredHeader",
				"com.soklet.McpMetricsSnapshot",
				"com.soklet.McpMetricsSnapshot$Builder",
				"com.soklet.McpMetricsSnapshot$EndpointMethodKey",
				"com.soklet.McpMetricsSnapshot$RequestOutcomeKey",
				"com.soklet.McpMetricsSnapshot$RequestStreamTerminationKey",
				"com.soklet.McpMetricsSnapshot$SubscriptionTerminationKey",
				"com.soklet.McpRequestOutcome",
				"com.soklet.McpServerDiagnostics",
				"com.soklet.McpServerStatus",
				"com.soklet.McpSimulation",
				"com.soklet.McpSimulationBodyMode",
				"com.soklet.McpSimulationCompletion",
				"com.soklet.McpSimulationOptions",
				"com.soklet.McpSimulationOptions$Builder",
				"com.soklet.McpSimulationResponse",
				"com.soklet.McpSimulationStreamItem",
				"com.soklet.McpSimulationStreamItemType",
				"com.soklet.McpStreamTerminationReason",
				"com.soklet.McpTextCoordinate",
				"com.soklet.McpTextCoordinate$Kind",
				"com.soklet.McpTraceCorrelationConfigurationFingerprint",
				"com.soklet.McpTraceCorrelationControl",
				"com.soklet.McpTraceCorrelationKey",
				"com.soklet.Simulator");
		Path phaseSixIncludes = Path.of("api/mcp/phase-6.includes");
		List<String> actualPhaseSixTypes = includeTypeNames(phaseSixIncludes);
		Assertions.assertEquals(PHASE_SIX_TYPE_COUNT,
				actualPhaseSixTypes.size());
		Assertions.assertEquals(expectedPhaseSixTypes, actualPhaseSixTypes,
				"The frozen Phase 6 owner inventory changed");

		Assertions.assertEquals(PROVISIONAL_TYPE_COUNT,
				includeTypeNames(Path.of("api/mcp/provisional.includes")).size(),
				"The provisional owner inventory changed");
		Assertions.assertEquals(CURRENT_MCP_TYPE_COUNT,
				allReviewedOwnerNames().size(),
				"The reviewed current MCP owner union changed");

		Method defaultStart = assertInstanceMethod(Simulator.class,
				"startMcpRequest", McpSimulation.class, MethodShape.ABSTRACT,
				false, Request.class);
		Method configuredStart = assertInstanceMethod(Simulator.class,
				"startMcpRequest", McpSimulation.class, MethodShape.ABSTRACT,
				false, Request.class, McpSimulationOptions.class);
		assertErasedGenericSignature(defaultStart);
		assertErasedGenericSignature(configuredStart);
		assertParameterNames(defaultStart, "request");
		assertParameterNames(configuredStart, "request", "options");

		Method localizer = assertInstanceMethod(McpServer.Builder.class,
				"localizer", McpServer.Builder.class, MethodShape.CONCRETE,
				false, McpLocalizer.class);
		Method localizationControl = assertInstanceMethod(McpServer.class,
				"getLocalizationControl", McpLocalizationControl.class,
				MethodShape.ABSTRACT, false);
		assertErasedGenericSignature(localizer);
		assertErasedGenericSignature(localizationControl);
		assertParameterNames(localizer, "localizer");
	}

	@Test
	public void phaseFiveSealedHierarchyRemainsExact() throws Exception {
		assertSealedHierarchy(phaseFiveTypes(), PHASE_FIVE_PERMITTED_TYPES,
				PHASE_FIVE_NON_SEALED_TYPES, "Phase 5");
	}

	@Test
	public void phaseSixSealedHierarchyRemainsExact() throws Exception {
		assertSealedHierarchy(phaseSixTypes(), PHASE_SIX_PERMITTED_TYPES,
				PHASE_SIX_NON_SEALED_TYPES, "Phase 6");
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

		Assertions.assertEquals(PHASE_FOUR_PRIMITIVE_CONSTANTS, actualConstants,
				"Phase 4 public static-final primitive constants changed");
	}

	@Test
	public void phaseFivePublicScalarConstantsRetainExactValues()
			throws Exception {
		Map<String, Object> actualConstants = new TreeMap<>();

		for (Class<?> type : phaseFiveTypes()) {
			for (Field field : type.getDeclaredFields()) {
				int modifiers = field.getModifiers();
				if (Modifier.isPublic(modifiers) && Modifier.isStatic(modifiers)
						&& Modifier.isFinal(modifiers)
						&& (field.getType().isPrimitive()
						|| field.getType() == String.class))
					actualConstants.put(type.getName() + "#" + field.getName(),
							field.get(null));
			}
		}

		Assertions.assertEquals(PHASE_FIVE_SCALAR_CONSTANTS, actualConstants,
				"Phase 5 public static-final primitive/String constants changed");
	}

	@Test
	public void publicMcpScalarSignaturesUseReferenceTypes()
			throws Exception {
		List<String> primitiveSignatures = new ArrayList<>();

		for (Class<?> type : publicMcpTypes()) {
			if (type.isAnnotation())
				continue;

			for (Field field : type.getDeclaredFields()) {
				String fieldId = type.getName() + "#" + field.getName();
				if (isPublicOrProtected(field.getModifiers())
						&& field.getType().isPrimitive()
						&& !PHASE_FOUR_PRIMITIVE_CONSTANTS.containsKey(fieldId))
					primitiveSignatures.add("FIELD|" + fieldId + "|"
							+ field.getType().getName());
			}

			for (Constructor<?> constructor : type.getDeclaredConstructors()) {
				if (!isPublicOrProtected(constructor.getModifiers()))
					continue;
				for (Parameter parameter : constructor.getParameters()) {
					if (parameter.getType().isPrimitive())
						primitiveSignatures.add("CONSTRUCTOR|" + type.getName()
								+ "|PARAMETER|" + parameter.getName() + "|"
								+ parameter.getType().getName());
				}
			}

			for (Method method : type.getDeclaredMethods()) {
				if (!isPublicOrProtected(method.getModifiers())
						|| isJavaObjectContractMethod(method))
					continue;
				if (method.getReturnType().isPrimitive()
						&& method.getReturnType() != void.class)
					primitiveSignatures.add("METHOD|" + type.getName() + "#"
							+ method.getName() + "|RETURN|"
							+ method.getReturnType().getName());
				for (Parameter parameter : method.getParameters()) {
					if (parameter.getType().isPrimitive())
						primitiveSignatures.add("METHOD|" + type.getName() + "#"
								+ method.getName() + "|PARAMETER|"
								+ parameter.getName() + "|"
								+ parameter.getType().getName());
				}
			}

			for (RecordComponent component : type.getRecordComponents() == null
					? new RecordComponent[0] : type.getRecordComponents()) {
				if (component.getType().isPrimitive())
					primitiveSignatures.add("RECORD_COMPONENT|" + type.getName()
							+ "#" + component.getName() + "|"
							+ component.getType().getName());
			}
		}

		Assertions.assertEquals(List.of(), primitiveSignatures,
				"Public MCP scalar signatures must use reference types; "
						+ "void returns, Java annotation elements, reviewed compile-time "
						+ "constants, and Object-contract methods are the only exceptions");
	}

	@Test
	public void phaseFourMcpEnumsRetainDeclarationOrder() throws Exception {
		assertEnumValues(phaseFourTypes(), PHASE_FOUR_MCP_ENUM_VALUES,
				"Phase 4");
	}

	@Test
	public void phaseFiveMcpEnumsRetainDeclarationOrder() throws Exception {
		assertEnumValues(phaseFiveTypes(), PHASE_FIVE_MCP_ENUM_VALUES,
				"Phase 5");
	}

	@Test
	public void phaseFourJSpecifyNullabilityLayoutRemainsExact()
			throws Exception {
		assertNullabilityLayout(phaseFourTypes(),
				PHASE_FOUR_NULLABILITY_SHA_256,
				"PHASE_FOUR_NULLABILITY_SHA_256", "Phase 4");
	}

	@Test
	public void phaseFiveJSpecifyNullabilityLayoutRemainsExact()
			throws Exception {
		assertNullabilityLayout(phaseFiveTypes(),
				PHASE_FIVE_NULLABILITY_SHA_256,
				"PHASE_FIVE_NULLABILITY_SHA_256", "Phase 5");
	}

	@Test
	public void phaseSixJSpecifyNullabilityLayoutRemainsExact()
			throws Exception {
		assertNullabilityLayout(phaseSixTypes(),
				PHASE_SIX_NULLABILITY_SHA_256,
				"PHASE_SIX_NULLABILITY_SHA_256", "Phase 6");
	}

	@Test
	public void extensionPointParameterNamesRetainTheirDocumentedOrder()
			throws Exception {
		assertParameterNames(McpToolHandler.class.getMethod("handle",
				McpRequestContext.class, McpToolArguments.class,
				McpInvocationFeatures.class), "request", "arguments", "features");
		assertParameterNames(McpCompleteToolHandler.class.getMethod("handle",
				McpRequestContext.class, McpToolArguments.class,
				McpInvocationFeatures.class), "request", "arguments", "features");
		assertParameterNames(McpPromptHandler.class.getMethod("handle",
				McpRequestContext.class, McpPromptGetContext.class,
				McpInvocationFeatures.class), "request", "prompt", "features");
		assertParameterNames(McpResourceReadHandler.class.getMethod("handle",
				McpRequestContext.class, McpResourceReadContext.class,
				McpInvocationFeatures.class), "request", "resource", "features");
		assertParameterNames(McpResourceListHandler.class.getMethod("handle",
				McpRequestContext.class, McpResourceListContext.class,
				McpInvocationFeatures.class), "request", "list", "features");
		assertParameterNames(McpHandlerInterceptor.class.getMethod(
				"interceptHandler", McpRequestContext.class,
				McpInvocationFeatures.class, McpHandlerContinuation.class),
				"context", "features", "continuation");
		assertParameterNames(McpAdmissionController.class.getMethod("admit",
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
				"didStopMcpServer", McpServer.class, ParticipantShutdownResult.class),
				"mcpServer", "result");
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
	public void phaseFiveExtensionPointParameterNamesRetainTheirDocumentedOrder()
			throws Exception {
		assertParameterNames(McpProgressReporter.class.getMethod("report",
				McpProgressUpdate.class), "update");
		assertParameterNames(McpProtectionControl.class.getMethod(
				"stageVerificationKey", McpProtectionKey.class),
				"verificationKey");
		assertParameterNames(McpProtectionControl.class.getMethod(
				"activateStagedKey", String.class), "keyId");
		assertParameterNames(McpProtectionControl.class.getMethod("rotateTo",
				McpProtectionKey.class), "activeKey");
		assertParameterNames(McpProtectionControl.class.getMethod(
				"removeVerificationKey", String.class), "keyId");
		assertParameterNames(McpRequestStateProtector.class.getMethod("seal",
				McpRequestStateProtectionContext.class, byte[].class),
				"context", "plaintext");
		assertParameterNames(McpRequestStateProtector.class.getMethod("open",
				McpRequestStateProtectionContext.class, String.class),
				"context", "protectedState");
		assertParameterNames(McpSubscriptionEventListener.class.getMethod(
				"onEvent", McpSubscriptionEvent.class), "event");
		assertParameterNames(McpSubscriptionEventPublisher.class.getMethod(
				"subscribe", McpSubscriptionEventListener.class), "listener");
		assertParameterNames(McpSubscriptionEventPublisher.class.getMethod(
				"publish", McpSubscriptionEvent.class), "event");
		assertParameterNames(McpSubscriptionEventPublisher.class.getMethod(
				"publishResourceUpdated", java.net.URI.class), "resourceUri");
	}

	@Test
	public void publicMcpValueCarriersRemainEncapsulatedFinalClasses()
			throws Exception {
		Assertions.assertEquals(43, FORMER_PUBLIC_RECORD_TYPES.size(),
				"The reviewed surviving former-record carrier inventory changed");
		Assertions.assertEquals(FORMER_PUBLIC_RECORD_TYPES.size(),
				Set.copyOf(FORMER_PUBLIC_RECORD_TYPES).size(),
				"The reviewed former-record carrier inventory contains duplicates");

		List<String> publicRecords = publicMcpTypes().stream()
				.filter(Class::isRecord)
				.map(Class::getName)
				.sorted()
				.toList();
		Assertions.assertEquals(List.of(), publicRecords,
				"The reviewed public MCP API must not expose record types");

		List<String> publicConstructors = publicMcpTypes().stream()
				.flatMap(type -> Arrays.stream(type.getConstructors()))
				.map(constructor -> constructor.getDeclaringClass().getName()
						+ "(" + Arrays.stream(constructor.getParameterTypes())
						.map(Class::getName)
						.collect(java.util.stream.Collectors.joining(",")) + ")")
				.sorted()
				.toList();
		Assertions.assertEquals(List.of(
				"com.soklet.McpJsonRpcException(com.soklet.McpJsonRpcError)"),
				publicConstructors,
				"Only the throwable MCP API may expose public construction");

		for (Class<?> type : FORMER_PUBLIC_RECORD_TYPES)
			assertEncapsulatedFinalValueType(type);
	}

	@Test
	public void phaseFourValueCarrierFactoriesAndGettersRemainExact()
			throws Exception {
		assertFactory(McpAdmissionDecision.class, "accepted",
				McpAdmissionDecision.Accepted.class, List.of("identity"),
				McpAdmissionIdentity.class);
		assertFactory(McpAdmissionDecision.class, "accepted",
				McpAdmissionDecision.Accepted.class, List.of());
		assertFactory(McpAdmissionDecision.class, "rejected",
				McpAdmissionDecision.Rejected.class, List.of("rejection"),
				McpAdmissionRejection.class);
		assertGetter(McpAdmissionDecision.Accepted.class, "getIdentity",
				McpAdmissionIdentity.class);
		assertGetter(McpAdmissionDecision.Rejected.class, "getRejection",
				McpAdmissionRejection.class);

		assertFactory(McpJsonBoolean.class, "fromValue", McpJsonBoolean.class,
				List.of("value"), Boolean.class);
		assertFactory(McpJsonNumber.class, "fromValue", McpJsonNumber.class,
				List.of("value"), java.math.BigDecimal.class);
		assertFactory(McpJsonString.class, "fromValue", McpJsonString.class,
				List.of("value"), String.class);
		assertGetter(McpJsonBoolean.class, "getValue", Boolean.class);
		assertGetter(McpJsonNumber.class, "getValue", java.math.BigDecimal.class);
		assertGetter(McpJsonString.class, "getValue", String.class);

		assertFactory(McpPromptMessage.class, "fromUserContent",
				McpPromptMessage.class, List.of("content"), McpContentBlock.class);
		assertFactory(McpPromptMessage.class, "fromAssistantContent",
				McpPromptMessage.class, List.of("content"), McpContentBlock.class);
		assertGetter(McpPromptMessage.class, "getRole", McpRole.class);
		assertGetter(McpPromptMessage.class, "getContent", McpContentBlock.class);

		assertFactory(McpRateLimitDecision.class, "allowed",
				McpRateLimitDecision.Allowed.class, List.of());
		assertFactory(McpRateLimitDecision.class, "denied",
				McpRateLimitDecision.Denied.class, List.of("retryAfter"),
				Duration.class);
		assertGetter(McpRateLimitDecision.Denied.class, "getRetryAfter",
				Duration.class);
	}

	@Test
	public void phaseFiveValueCarrierFactoriesAndGettersRemainExact()
			throws Exception {
		assertFactory(McpInputRequest.class, "fromDeclaration",
				McpInputRequest.class, List.of("declaration", "params"),
				McpInputRequestDeclaration.class, McpJsonObject.class);
		assertGetter(McpInputRequest.class, "getDeclaration",
				McpInputRequestDeclaration.class);
		assertGetter(McpInputRequest.class, "getParams", McpJsonObject.class);
		assertGetter(McpInputRequest.class, "getMethod", String.class);

		assertFactory(McpInputRequestDeclaration.class, "fromElicitationForm",
				McpInputRequestDeclaration.class, List.of("requirement"),
				McpInputRequirement.class);
		assertFactory(McpInputRequestDeclaration.class, "fromElicitationUrl",
				McpInputRequestDeclaration.class, List.of("requirement"),
				McpInputRequirement.class);
		assertFactory(McpInputRequestDeclaration.class, "fromSampling",
				McpInputRequestDeclaration.class,
				List.of("optionalCapabilities", "requirement"), Set.class,
				McpInputRequirement.class);
		assertFactory(McpInputRequestDeclaration.class, "fromRoots",
				McpInputRequestDeclaration.class, List.of("requirement"),
				McpInputRequirement.class);
		assertGetter(McpInputRequestDeclaration.class, "getMethod", String.class);
		assertGetter(McpInputRequestDeclaration.class, "getCapabilities",
				Set.class);
		assertGetter(McpInputRequestDeclaration.class, "getRequirement",
				McpInputRequirement.class);

		assertFactory(McpSubscriptionEvent.class, "resourcesListChanged",
				McpSubscriptionEvent.ResourcesListChanged.class, List.of());
		assertFactory(McpSubscriptionEvent.class, "resourceUpdated",
				McpSubscriptionEvent.ResourceUpdated.class, List.of("resourceUri"),
				java.net.URI.class);
		assertGetter(McpSubscriptionEvent.ResourceUpdated.class,
				"getResourceUri", java.net.URI.class);
	}

	@Test
	public void phaseSixValueCarrierFactoriesAndGettersRemainExact()
			throws Exception {
		assertFactory(McpLocalizationResult.class, "localized",
				McpLocalizationResult.Localized.class, List.of("text"), String.class);
		assertFactory(McpLocalizationResult.class, "useDefaultText",
				McpLocalizationResult.UseDefaultText.class, List.of());
		assertFactory(McpLocalizationResult.class, "failure",
				McpLocalizationResult.Failure.class, List.of());
		assertGetter(McpLocalizationResult.Localized.class, "getText",
				String.class);

		assertMetricsEventFactoriesAndGetters();
		assertMetricsKeyFactoriesAndGetters();
		assertGetter(McpTraceCorrelationConfigurationFingerprint.class,
				"getValue", String.class);
	}

	@Test
	public void mayRequestInputAnnotationContractRemainsExact() {
		Map<String, Class<?>> actualElements = new TreeMap<>();
		for (Method element : McpMayRequestInput.class.getDeclaredMethods()) {
			actualElements.put(element.getName(), element.getReturnType());
			Assertions.assertNull(element.getDefaultValue(),
					() -> McpMayRequestInput.class.getName() + "#"
							+ element.getName() + " must not declare a default");
		}

		Assertions.assertEquals(Map.of(
				"capabilities", McpClientCapability[].class,
				"method", String.class,
				"requirement", McpInputRequirement.class), actualElements,
				"McpMayRequestInput elements or return types changed");
		Target target = McpMayRequestInput.class.getAnnotation(Target.class);
		Assertions.assertNotNull(target,
				"McpMayRequestInput must retain an explicit @Target");
		Assertions.assertArrayEquals(new ElementType[0], target.value(),
				"McpMayRequestInput must remain a nested annotation value only");
		Retention retention = McpMayRequestInput.class.getAnnotation(
				Retention.class);
		Assertions.assertNotNull(retention,
				"McpMayRequestInput must retain an explicit @Retention");
		Assertions.assertEquals(RetentionPolicy.RUNTIME, retention.value(),
				"McpMayRequestInput retention changed");
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
		Method requestedResourceSubscriptionUris = assertInstanceMethod(
				McpAdmissionContext.class,
				"getRequestedResourceSubscriptionUris", List.class,
				MethodShape.ABSTRACT, false);
		assertParameterizedType(
				requestedResourceSubscriptionUris.getGenericReturnType(), null,
				List.class, URI.class);
		assertNoGenericParameters(requestedResourceSubscriptionUris);
		assertErasedGenericSignature(assertInstanceMethod(
				McpEndpoint.Builder.class, "subscriptions",
				McpEndpoint.Builder.class, MethodShape.CONCRETE, false,
				McpSubscriptionConfig.class));

		Method resolverSubscriptions = assertInstanceMethod(
				McpEndpointRegistry.class, "withSubscriptions",
				McpEndpointRegistry.class, MethodShape.CONCRETE, false,
				Class.class, McpSubscriptionConfig.class);
		Assertions.assertEquals(McpEndpointRegistry.class,
				resolverSubscriptions.getGenericReturnType());
		Type[] resolverParameterTypes =
				resolverSubscriptions.getGenericParameterTypes();
		assertUnboundedClassWildcard(resolverParameterTypes[0]);
		Assertions.assertEquals(McpSubscriptionConfig.class,
				resolverParameterTypes[1]);

		assertErasedGenericSignature(assertInstanceMethod(
				McpRequestContext.class, "getInputResponses",
				McpInputResponses.class, MethodShape.DEFAULT, false));
		Method frameworkRequestState = assertInstanceMethod(
				McpRequestContext.class, "getFrameworkRequestState",
				Optional.class, MethodShape.DEFAULT, false);
		assertParameterizedType(frameworkRequestState.getGenericReturnType(), null,
				Optional.class, McpJsonValue.class);
		assertNoGenericParameters(frameworkRequestState);
		Method applicationRequestState = assertInstanceMethod(
				McpRequestContext.class, "getApplicationRequestState",
				Optional.class, MethodShape.DEFAULT, false);
		assertParameterizedType(applicationRequestState.getGenericReturnType(), null,
				Optional.class, String.class);
		assertNoGenericParameters(applicationRequestState);

		Method resultFrameworkRequestState = assertInstanceMethod(
				McpInputRequiredResult.class, "getFrameworkRequestState",
				Optional.class, MethodShape.CONCRETE, false);
		assertParameterizedType(resultFrameworkRequestState.getGenericReturnType(),
				null, Optional.class, McpJsonValue.class);
		assertNoGenericParameters(resultFrameworkRequestState);
		Method resultApplicationRequestState = assertInstanceMethod(
				McpInputRequiredResult.class, "getApplicationRequestState",
				Optional.class, MethodShape.CONCRETE, false);
		assertParameterizedType(resultApplicationRequestState.getGenericReturnType(),
				null, Optional.class, String.class);
		assertNoGenericParameters(resultApplicationRequestState);

		assertRegistrationDescriptors();
		assertMrtrAnnotationDefaults(McpTool.class);
		assertMrtrAnnotationDefaults(McpPrompt.class);
		assertMrtrAnnotationDefaults(McpResource.class);

		assertErasedGenericSignature(assertInstanceMethod(McpServer.class,
				"getProtectionControl", McpProtectionControl.class,
				MethodShape.ABSTRACT, false));
		assertErasedGenericSignature(assertInstanceMethod(McpServer.class,
				"getTraceCorrelationControl", McpTraceCorrelationControl.class,
				MethodShape.ABSTRACT, false));
		assertErasedGenericSignature(assertInstanceMethod(McpServer.Builder.class,
				"protectionConfig", McpServer.Builder.class,
				MethodShape.CONCRETE, false, McpProtectionConfig.class));
		assertErasedGenericSignature(assertInstanceMethod(McpServer.Builder.class,
				"traceCorrelationKey", McpServer.Builder.class,
				MethodShape.CONCRETE, false, McpTraceCorrelationKey.class));
		assertErasedGenericSignature(assertInstanceMethod(McpServer.Builder.class,
				"logRawValidatedTraceIds", McpServer.Builder.class,
				MethodShape.CONCRETE, false, Boolean.class));

		assertErasedGenericSignature(assertInstanceMethod(McpServer.Builder.class,
				"streamQueueCapacity", McpServer.Builder.class,
				MethodShape.CONCRETE, false, Integer.class));
		assertErasedGenericSignature(assertInstanceMethod(McpServer.Builder.class,
				"writeTimeout", McpServer.Builder.class, MethodShape.CONCRETE,
				false, Duration.class));
		assertErasedGenericSignature(assertInstanceMethod(McpServer.Builder.class,
				"keepAliveInterval", McpServer.Builder.class,
				MethodShape.CONCRETE, false, Duration.class));
		assertErasedGenericSignature(assertInstanceMethod(McpServer.Builder.class,
				"maximumSubscriptionsPerPrincipal", McpServer.Builder.class,
				MethodShape.CONCRETE, false, Integer.class));
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

	private static void assertMetricsEventFactoriesAndGetters()
			throws Exception {
		assertFactory(McpMetricsEvent.class, "serverStarted",
				McpMetricsEvent.ServerStarted.class, List.of());
		assertFactory(McpMetricsEvent.class, "connectionAccepted",
				McpMetricsEvent.ConnectionAccepted.class, List.of());
		assertFactory(McpMetricsEvent.class, "connectionRejected",
				McpMetricsEvent.ConnectionRejected.class, List.of());
		assertFactory(McpMetricsEvent.class, "requestAccepted",
				McpMetricsEvent.RequestAccepted.class, List.of());
		assertFactory(McpMetricsEvent.class, "requestRejected",
				McpMetricsEvent.RequestRejected.class, List.of());
		assertFactory(McpMetricsEvent.class, "requestStarted",
				McpMetricsEvent.RequestStarted.class,
				List.of("endpointPath", "jsonRpcMethod"), String.class,
				String.class);
		assertFactory(McpMetricsEvent.class, "requestFinished",
				McpMetricsEvent.RequestFinished.class,
				List.of("endpointPath", "jsonRpcMethod", "outcome", "duration"),
				String.class, String.class, McpRequestOutcome.class, Duration.class);
		assertFactory(McpMetricsEvent.class, "requestStreamOpened",
				McpMetricsEvent.RequestStreamOpened.class,
				List.of("endpointPath", "jsonRpcMethod"), String.class,
				String.class);
		assertFactory(McpMetricsEvent.class, "requestStreamClosed",
				McpMetricsEvent.RequestStreamClosed.class,
				List.of("endpointPath", "jsonRpcMethod", "reason", "duration"),
				String.class, String.class, McpStreamTerminationReason.class,
				Duration.class);
		assertFactory(McpMetricsEvent.class, "subscriptionOpened",
				McpMetricsEvent.SubscriptionOpened.class, List.of("endpointPath"),
				String.class);
		assertFactory(McpMetricsEvent.class, "subscriptionClosed",
				McpMetricsEvent.SubscriptionClosed.class,
				List.of("endpointPath", "reason", "duration"), String.class,
				McpStreamTerminationReason.class, Duration.class);
		assertFactory(McpMetricsEvent.class, "cancelationSignaled",
				McpMetricsEvent.CancelationSignaled.class,
				List.of("endpointPath", "jsonRpcMethod"), String.class,
				String.class);
		assertFactory(McpMetricsEvent.class, "progressEmitted",
				McpMetricsEvent.ProgressEmitted.class,
				List.of("endpointPath", "jsonRpcMethod"), String.class,
				String.class);
		assertFactory(McpMetricsEvent.class, "keepAliveEmitted",
				McpMetricsEvent.KeepAliveEmitted.class, List.of());
		assertFactory(McpMetricsEvent.class, "protocolError",
				McpMetricsEvent.ProtocolError.class, List.of("code"), Integer.class);
		assertFactory(McpMetricsEvent.class, "unknownMirroredHeader",
				McpMetricsEvent.UnknownMirroredHeader.class,
				List.of("endpointPath", "jsonRpcMethod"), String.class,
				String.class);
		assertFactory(McpMetricsEvent.class, "handlerExecutionStarted",
				McpMetricsEvent.HandlerExecutionStarted.class, List.of());
		assertFactory(McpMetricsEvent.class, "handlerExecutionFinished",
				McpMetricsEvent.HandlerExecutionFinished.class, List.of());
		assertFactory(McpMetricsEvent.class, "handlerQueued",
				McpMetricsEvent.HandlerQueued.class, List.of());
		assertFactory(McpMetricsEvent.class, "handlerDequeued",
				McpMetricsEvent.HandlerDequeued.class, List.of());
		assertFactory(McpMetricsEvent.class, "handlerCapacityRejected",
				McpMetricsEvent.HandlerCapacityRejected.class, List.of());
		assertFactory(McpMetricsEvent.class, "transportFailure",
				McpMetricsEvent.TransportFailure.class, List.of("reason"),
				MetricsCollector.TransportFailureReason.class);
		assertFactory(McpMetricsEvent.class, "serverStopped",
				McpMetricsEvent.ServerStopped.class, List.of("outcome"),
				ParticipantShutdownDisposition.class);

		assertRoutedMetricsGetters(McpMetricsEvent.RequestStarted.class);
		assertRoutedMetricsGetters(McpMetricsEvent.RequestFinished.class);
		assertGetter(McpMetricsEvent.RequestFinished.class, "getOutcome",
				McpRequestOutcome.class);
		assertGetter(McpMetricsEvent.RequestFinished.class, "getDuration",
				Duration.class);
		assertRoutedMetricsGetters(McpMetricsEvent.RequestStreamOpened.class);
		assertRoutedMetricsGetters(McpMetricsEvent.RequestStreamClosed.class);
		assertGetter(McpMetricsEvent.RequestStreamClosed.class, "getReason",
				McpStreamTerminationReason.class);
		assertGetter(McpMetricsEvent.RequestStreamClosed.class, "getDuration",
				Duration.class);
		assertGetter(McpMetricsEvent.SubscriptionOpened.class, "getEndpointPath",
				String.class);
		assertGetter(McpMetricsEvent.SubscriptionClosed.class, "getEndpointPath",
				String.class);
		assertGetter(McpMetricsEvent.SubscriptionClosed.class, "getReason",
				McpStreamTerminationReason.class);
		assertGetter(McpMetricsEvent.SubscriptionClosed.class, "getDuration",
				Duration.class);
		assertRoutedMetricsGetters(McpMetricsEvent.CancelationSignaled.class);
		assertRoutedMetricsGetters(McpMetricsEvent.ProgressEmitted.class);
		assertGetter(McpMetricsEvent.ProtocolError.class, "getCode",
				Integer.class);
		assertRoutedMetricsGetters(McpMetricsEvent.UnknownMirroredHeader.class);
		assertGetter(McpMetricsEvent.TransportFailure.class, "getReason",
				MetricsCollector.TransportFailureReason.class);
		assertGetter(McpMetricsEvent.ServerStopped.class, "getOutcome",
				ParticipantShutdownDisposition.class);
	}

	private static void assertMetricsKeyFactoriesAndGetters()
			throws Exception {
		assertFactory(McpMetricsSnapshot.EndpointMethodKey.class,
				"fromDimensions", McpMetricsSnapshot.EndpointMethodKey.class,
				List.of("endpointPath", "jsonRpcMethod"), String.class,
				String.class);
		assertFactory(McpMetricsSnapshot.RequestOutcomeKey.class,
				"fromDimensions", McpMetricsSnapshot.RequestOutcomeKey.class,
				List.of("endpointPath", "jsonRpcMethod", "outcome"), String.class,
				String.class, McpRequestOutcome.class);
		assertFactory(McpMetricsSnapshot.RequestStreamTerminationKey.class,
				"fromDimensions",
				McpMetricsSnapshot.RequestStreamTerminationKey.class,
				List.of("endpointPath", "jsonRpcMethod", "reason"), String.class,
				String.class, McpStreamTerminationReason.class);
		assertFactory(McpMetricsSnapshot.SubscriptionTerminationKey.class,
				"fromDimensions",
				McpMetricsSnapshot.SubscriptionTerminationKey.class,
				List.of("endpointPath", "reason"), String.class,
				McpStreamTerminationReason.class);

		assertRoutedMetricsGetters(McpMetricsSnapshot.EndpointMethodKey.class);
		assertRoutedMetricsGetters(McpMetricsSnapshot.RequestOutcomeKey.class);
		assertGetter(McpMetricsSnapshot.RequestOutcomeKey.class, "getOutcome",
				McpRequestOutcome.class);
		assertRoutedMetricsGetters(
				McpMetricsSnapshot.RequestStreamTerminationKey.class);
		assertGetter(McpMetricsSnapshot.RequestStreamTerminationKey.class,
				"getReason", McpStreamTerminationReason.class);
		assertGetter(McpMetricsSnapshot.SubscriptionTerminationKey.class,
				"getEndpointPath", String.class);
		assertGetter(McpMetricsSnapshot.SubscriptionTerminationKey.class,
				"getReason", McpStreamTerminationReason.class);
	}

	private static void assertRoutedMetricsGetters(Class<?> owner)
			throws Exception {
		assertGetter(owner, "getEndpointPath", String.class);
		assertGetter(owner, "getJsonRpcMethod", String.class);
	}

	private static void assertEncapsulatedFinalValueType(Class<?> type)
			throws Exception {
		Assertions.assertTrue(Modifier.isPublic(type.getModifiers()),
				() -> type.getName() + " must remain public");
		Assertions.assertTrue(Modifier.isFinal(type.getModifiers()),
				() -> type.getName() + " must remain final");
		Assertions.assertFalse(type.isInterface() || type.isAnnotation()
				|| type.isEnum() || type.isRecord(),
				() -> type.getName() + " must remain a non-record class");
		if (type.isMemberClass())
			Assertions.assertTrue(Modifier.isStatic(type.getModifiers()),
					() -> type.getName() + " must remain a static nested class");
		Assertions.assertArrayEquals(new Constructor<?>[0],
				type.getConstructors(),
				() -> type.getName() + " must not expose public constructors");

		assertDeclaredObjectContractMethod(type, "equals", boolean.class,
				Object.class);
		assertDeclaredObjectContractMethod(type, "hashCode", int.class);
		assertDeclaredObjectContractMethod(type, "toString", String.class);
	}

	private static void assertDeclaredObjectContractMethod(Class<?> owner,
			String name, Class<?> returnType, Class<?>... parameterTypes)
			throws Exception {
		Method method = owner.getDeclaredMethod(name, parameterTypes);
		Assertions.assertTrue(Modifier.isPublic(method.getModifiers()),
				() -> owner.getName() + "#" + name + " must remain public");
		Assertions.assertFalse(Modifier.isStatic(method.getModifiers()),
				() -> owner.getName() + "#" + name
						+ " must remain an instance method");
		Assertions.assertEquals(returnType, method.getReturnType(),
				() -> owner.getName() + "#" + name + " return type changed");
	}

	private static void assertFactory(Class<?> owner, String name,
			Class<?> returnType, List<String> parameterNames,
			Class<?>... parameterTypes) throws Exception {
		Method method = owner.getMethod(name, parameterTypes);
		String description = owner.getName() + "#" + name;

		Assertions.assertSame(owner, method.getDeclaringClass(),
				() -> description + " must remain declared on its reviewed host");
		Assertions.assertTrue(Modifier.isPublic(method.getModifiers()),
				() -> description + " must remain public");
		Assertions.assertTrue(Modifier.isStatic(method.getModifiers()),
				() -> description + " must remain static");
		Assertions.assertFalse(Modifier.isAbstract(method.getModifiers()),
				() -> description + " must remain concrete");
		Assertions.assertEquals(returnType, method.getReturnType(),
				() -> description + " erased return type changed");
		Assertions.assertArrayEquals(parameterTypes, method.getParameterTypes(),
				() -> description + " erased parameter types changed");
		Assertions.assertFalse(method.isVarArgs(),
				() -> description + " must not become varargs");
		Assertions.assertFalse(method.isBridge(),
				() -> description + " must not become a bridge method");
		Assertions.assertFalse(method.isSynthetic(),
				() -> description + " must not become synthetic");
		assertParameterNames(method, parameterNames.toArray(String[]::new));
	}

	private static void assertGetter(Class<?> owner, String name,
			Class<?> returnType) throws Exception {
		Method method = assertInstanceMethod(owner, name, returnType,
				MethodShape.CONCRETE, false);
		assertNoGenericParameters(method);
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
		return phaseTypes(PHASE_FOUR_INCLUDES, PHASE_FOUR_TYPE_COUNT,
				"Phase 4");
	}

	private static List<Class<?>> phaseFiveTypes() throws Exception {
		return phaseTypes(PHASE_FIVE_INCLUDES, PHASE_FIVE_TYPE_COUNT,
				"Phase 5");
	}

	private static List<Class<?>> phaseSixTypes() throws Exception {
		return phaseTypes(Path.of("api/mcp/phase-6.includes"),
				PHASE_SIX_TYPE_COUNT, "Phase 6");
	}

	private static List<Class<?>> phaseTypes(Path includes, int expectedCount,
			String phase) throws Exception {
		List<String> typeNames = includeTypeNames(includes);

		Assertions.assertEquals(expectedCount, typeNames.size(),
				"The reviewed " + phase + " type count changed");
		Assertions.assertEquals(typeNames.stream().sorted().toList(), typeNames,
				"The reviewed " + phase + " type inventory must remain sorted");
		Assertions.assertEquals(typeNames.size(), Set.copyOf(typeNames).size(),
				"The reviewed " + phase + " type inventory contains duplicates");

		List<Class<?>> types = new ArrayList<>(typeNames.size());
		ClassLoader classLoader =
				McpPublicApiReflectionContractTests.class.getClassLoader();

		for (String typeName : typeNames)
			types.add(Class.forName(typeName, false, classLoader));

		return List.copyOf(types);
	}

	private static List<String> includeTypeNames(Path includes) throws Exception {
		return Files.readAllLines(includes, StandardCharsets.UTF_8).stream()
				.map(String::trim)
				.filter(line -> !line.isEmpty() && !line.startsWith("#"))
				.toList();
	}

	private static void assertSealedHierarchy(List<Class<?>> types,
			Map<String, Set<String>> expectedPermittedTypes,
			Set<String> expectedNonSealedTypes, String phase) {
		Map<String, Set<String>> actualPermittedTypes = new TreeMap<>();
		Set<String> actualNonSealedTypes = new java.util.TreeSet<>();

		for (Class<?> type : types) {
			if (type.isSealed())
				actualPermittedTypes.put(type.getName(), Arrays.stream(
						type.getPermittedSubclasses()).map(Class::getName)
						.collect(java.util.stream.Collectors.toUnmodifiableSet()));
			if (isNonSealed(type))
				actualNonSealedTypes.add(type.getName());
		}

		Assertions.assertEquals(expectedPermittedTypes, actualPermittedTypes,
				phase + " sealed types or their exact permitted-subclass sets changed");
		Assertions.assertEquals(expectedNonSealedTypes, actualNonSealedTypes,
				phase + " non-sealed type declarations changed");
	}

	private static void assertEnumValues(List<Class<?>> types,
			Map<String, List<String>> expectedValues, String phase) {
		Map<String, List<String>> actualValues = new TreeMap<>();

		for (Class<?> type : types) {
			if (type.isEnum()
					&& type.getName().startsWith("com.soklet.Mcp"))
				actualValues.put(type.getName(), Arrays.stream(type.getEnumConstants())
						.map(value -> ((Enum<?>) value).name()).toList());
		}

		Assertions.assertEquals(expectedValues, actualValues,
				phase + " MCP enum declarations or value order changed");
	}

	private static List<Class<?>> publicMcpTypes() throws Exception {
		LinkedHashSet<String> typeNames = new LinkedHashSet<>();
		for (Path includes : MCP_API_INCLUDES) {
			for (String line : Files.readAllLines(includes, StandardCharsets.UTF_8)) {
				String typeName = line.trim();
				if (typeName.isEmpty() || typeName.startsWith("#"))
					continue;
				if (typeName.startsWith("com.soklet.Mcp")
						|| typeName.startsWith("com.soklet.annotation.Mcp")
						|| typeName.equals("com.soklet.DefaultMcpServer"))
					typeNames.add(typeName);
			}
		}

		ClassLoader classLoader =
				McpPublicApiReflectionContractTests.class.getClassLoader();
		List<Class<?>> types = new ArrayList<>(typeNames.size());
		for (String typeName : typeNames)
			types.add(Class.forName(typeName, false, classLoader));
		return List.copyOf(types);
	}

	private static Set<String> allReviewedOwnerNames() throws Exception {
		LinkedHashSet<String> typeNames = new LinkedHashSet<>();
		for (Path includes : MCP_API_INCLUDES)
			typeNames.addAll(includeTypeNames(includes));
		return Set.copyOf(typeNames);
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

	private static boolean isJavaObjectContractMethod(Method method) {
		return (method.getName().equals("equals")
				&& method.getReturnType() == boolean.class
				&& Arrays.equals(method.getParameterTypes(),
						new Class<?>[] { Object.class }))
				|| (method.getName().equals("hashCode")
				&& method.getReturnType() == int.class
				&& method.getParameterCount() == 0);
	}

	private static boolean directlyPermits(Class<?> parent, Class<?> child) {
		return parent.isSealed() && Arrays.asList(parent.getPermittedSubclasses())
				.contains(child);
	}

	private static void assertNullabilityLayout(List<Class<?>> types,
			String expectedDigest, String digestConstant, String phase)
			throws Exception {
		String canonicalContract = canonicalNullabilityContract(types);
		String actualDigest = sha256(canonicalContract);

		Assertions.assertEquals(expectedDigest, actualDigest,
				() -> phase + " @NonNull/@Nullable type-use layout changed. "
						+ "Review the canonical contract below, then deliberately "
						+ "update " + digestConstant + " if the change is approved."
						+ "\nExpected SHA-256: " + expectedDigest
						+ "\nActual SHA-256:   " + actualDigest
						+ "\nCanonical contract:\n" + canonicalContract);
	}

	private static String canonicalNullabilityContract(List<Class<?>> types) {
		List<String> lines = new ArrayList<>();

		for (Class<?> type : types) {
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
