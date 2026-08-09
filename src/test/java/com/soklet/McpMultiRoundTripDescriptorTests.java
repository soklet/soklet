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

import com.soklet.converter.TypeReference;
import org.junit.jupiter.api.Test;

import javax.annotation.concurrent.ThreadSafe;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for provisional multi-round-trip descriptor seams.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
class McpMultiRoundTripDescriptorTests {
	@Test
	void declarationFactoriesProduceTheClosedValidatedCoreUnion() {
		Set<McpClientCapability> mutableCapabilities = new LinkedHashSet<>(
				Set.of(McpClientCapability.SAMPLING,
						McpClientCapability.SAMPLING_CONTEXT));
		McpInputRequestDeclaration copiedSampling =
				new McpInputRequestDeclaration("sampling/createMessage",
						mutableCapabilities, McpInputRequirement.CONDITIONAL);
		mutableCapabilities.clear();
		McpInputRequestDeclaration form =
				McpInputRequestDeclaration.fromElicitationForm(
						McpInputRequirement.REQUIRED);
		McpInputRequestDeclaration url =
				McpInputRequestDeclaration.fromElicitationUrl(
						McpInputRequirement.CONDITIONAL);
		McpInputRequestDeclaration sampling =
				McpInputRequestDeclaration.fromSampling(
						Set.of(McpClientCapability.SAMPLING_CONTEXT,
								McpClientCapability.SAMPLING_TOOLS),
						McpInputRequirement.CONDITIONAL);
		McpInputRequestDeclaration roots =
				McpInputRequestDeclaration.fromRoots(
						McpInputRequirement.REQUIRED);

		assertEquals("elicitation/create", form.method());
		assertEquals(Set.of(McpClientCapability.ELICITATION_FORM),
				form.capabilities());
		assertEquals(McpInputRequirement.REQUIRED, form.requirement());
		assertEquals(Set.of(McpClientCapability.ELICITATION_URL),
				url.capabilities());
		assertEquals(Set.of(McpClientCapability.SAMPLING,
				McpClientCapability.SAMPLING_CONTEXT,
				McpClientCapability.SAMPLING_TOOLS), sampling.capabilities());
		assertEquals(Set.of(McpClientCapability.SAMPLING,
				McpClientCapability.SAMPLING_CONTEXT),
				copiedSampling.capabilities());
		assertEquals("roots/list", roots.method());
		assertThrows(UnsupportedOperationException.class,
				() -> form.capabilities().clear());

		assertThrows(IllegalArgumentException.class,
				() -> new McpInputRequestDeclaration("extension/request",
						Set.of(McpClientCapability.ROOTS),
						McpInputRequirement.REQUIRED));
		assertThrows(IllegalArgumentException.class,
				() -> new McpInputRequestDeclaration("elicitation/create",
						Set.of(McpClientCapability.ELICITATION_FORM,
								McpClientCapability.ELICITATION_URL),
						McpInputRequirement.REQUIRED));
		assertThrows(IllegalArgumentException.class,
				() -> McpInputRequestDeclaration.fromSampling(
						Set.of(McpClientCapability.SAMPLING),
						McpInputRequirement.CONDITIONAL));
		assertThrows(IllegalArgumentException.class,
				() -> McpInputRequestDeclaration.fromSampling(
						Set.of(McpClientCapability.ROOTS),
						McpInputRequirement.CONDITIONAL));
		assertThrows(IllegalArgumentException.class,
				() -> new McpInputRequestDeclaration("roots/list",
						Set.of(McpClientCapability.SAMPLING),
						McpInputRequirement.REQUIRED));
		IllegalArgumentException sanitized = assertThrows(
				IllegalArgumentException.class,
				() -> new McpInputRequestDeclaration(
						"secret-extension-method",
						Set.of(McpClientCapability.ROOTS),
						McpInputRequirement.REQUIRED));
		assertFalse(String.valueOf(sanitized.getMessage())
				.contains("secret-extension-method"));
	}

	@Test
	void inputRequestsRetainExactCoreDeclarationsAndParameterObjects() {
		List<McpInputRequestDeclaration> declarations = List.of(
				McpInputRequestDeclaration.fromElicitationForm(
						McpInputRequirement.REQUIRED),
				McpInputRequestDeclaration.fromElicitationUrl(
						McpInputRequirement.CONDITIONAL),
				McpInputRequestDeclaration.fromSampling(Set.of(),
						McpInputRequirement.CONDITIONAL),
				McpInputRequestDeclaration.fromRoots(
						McpInputRequirement.REQUIRED));

		for (McpInputRequestDeclaration declaration : declarations) {
			String secretParams = "secret-input-request-params";
			McpJsonObject params = McpJsonObject.builder()
					.put("dev.example/extension", secretParams)
					.build();
			McpInputRequest request =
					McpInputRequest.fromDeclaration(declaration, params);

			assertSame(declaration, request.declaration());
			assertSame(params, request.params());
			assertEquals(declaration.method(), request.method());
			assertEquals("McpInputRequest{method='%s', params=<redacted>}"
					.formatted(declaration.method()), request.toString());
			assertFalse(request.toString().contains(secretParams));
		}

		McpInputRequestDeclaration roots = declarations.get(3);
		McpJsonObject emptyParams = McpJsonObject.emptyInstance();
		assertSame(emptyParams,
				McpInputRequest.fromDeclaration(roots, emptyParams).params());
		assertThrows(NullPointerException.class,
				() -> new McpInputRequest(null, emptyParams));
		assertThrows(NullPointerException.class,
				() -> new McpInputRequest(roots, null));
		assertThrows(NullPointerException.class,
				() -> McpInputRequest.fromDeclaration(null, emptyParams));
		assertThrows(NullPointerException.class,
				() -> McpInputRequest.fromDeclaration(roots, null));
	}

	@Test
	void inputRequiredResultsSupportInputStateAndCombinedForms() {
		McpInputRequestDeclaration approval =
				McpInputRequestDeclaration.fromElicitationForm(
						McpInputRequirement.REQUIRED);
		McpInputRequest request = McpInputRequest.fromDeclaration(approval,
				McpJsonObject.builder().put("future", true).build());
		IllegalStateException emptyResult = assertThrows(
				IllegalStateException.class,
				() -> McpInputRequiredResult.builder().build());
		assertFalse(String.valueOf(emptyResult.getMessage())
				.contains("McpInputRequiredResult"));
		assertThrows(IllegalStateException.class, () ->
				McpInputRequiredResult.builder()
						.metadata(McpJsonObject.builder()
								.put("dev.example/metadata-only", true)
								.build())
						.build());

		McpInputRequiredResult inputOnly = McpInputRequiredResult.builder()
				.inputRequest("approval", request)
				.build();
		assertEquals(Map.of("approval", request), inputOnly.getInputRequests());
		assertTrue(inputOnly.getRequestState().isEmpty());
		assertSame(McpJsonObject.emptyInstance(), inputOnly.getMetadata());

		McpInputRequiredResult stateOnly = McpInputRequiredResult.builder()
				.frameworkRequestState(McpJsonNull.INSTANCE)
				.build();
		assertTrue(stateOnly.getInputRequests().isEmpty());
		McpFrameworkRequestState frameworkState =
				(McpFrameworkRequestState) stateOnly.getRequestState().orElseThrow();
		assertSame(McpJsonNull.INSTANCE, frameworkState.value());

		McpJsonObject metadata = McpJsonObject.builder()
				.put("dev.example/result", "combined")
				.build();
		McpInputRequiredResult combined = McpInputRequiredResult.builder()
				.inputRequest("approval", request)
				.applicationRequestState("opaque-state")
				.metadata(metadata)
				.build();
		assertEquals(Map.of("approval", request), combined.getInputRequests());
		assertEquals("opaque-state", ((McpApplicationRequestState) combined
				.getRequestState().orElseThrow()).value());
		assertSame(metadata, combined.getMetadata());
		assertTrue(combined instanceof McpOperationResult);
	}

	@Test
	void inputRequiredBuilderPreservesOrderSnapshotsAndFailureAtomicity() {
		McpInputRequest first = inputRequest(
				McpInputRequestDeclaration.fromElicitationForm(
						McpInputRequirement.REQUIRED));
		McpInputRequest second = inputRequest(
				McpInputRequestDeclaration.fromRoots(
						McpInputRequirement.CONDITIONAL));
		McpInputRequest third = inputRequest(
				McpInputRequestDeclaration.fromSampling(Set.of(),
						McpInputRequirement.CONDITIONAL));
		String secretId = "secret-input-request-id";
		McpInputRequiredResult.Builder builder = McpInputRequiredResult.builder()
				.inputRequest("", first)
				.inputRequest("   ", second)
				.inputRequest(secretId, third);
		McpInputRequiredResult snapshot = builder.build();

		assertEquals(List.of("", "   ", secretId),
				new ArrayList<>(snapshot.getInputRequests().keySet()));
		assertThrows(UnsupportedOperationException.class,
				() -> snapshot.getInputRequests().clear());
		IllegalArgumentException duplicate = assertThrows(
				IllegalArgumentException.class,
				() -> builder.inputRequest(secretId, first));
		assertFalse(String.valueOf(duplicate.getMessage()).contains(secretId));
		assertSame(third, builder.build().getInputRequests().get(secretId));

		Map<String, McpInputRequest> beforeNullFailures =
				builder.build().getInputRequests();
		assertThrows(NullPointerException.class,
				() -> builder.inputRequest(null, first));
		String nullRequestId = "secret-null-request-id";
		NullPointerException nullRequest = assertThrows(
				NullPointerException.class,
				() -> builder.inputRequest(nullRequestId, null));
		assertFalse(String.valueOf(nullRequest.getMessage())
				.contains(nullRequestId));
		assertEquals(beforeNullFailures, builder.build().getInputRequests());

		builder.inputRequest("later", first);
		assertEquals(List.of("", "   ", secretId),
				new ArrayList<>(snapshot.getInputRequests().keySet()));
	}

	@Test
	void inputRequiredBuilderUsesLastCallWinsForStateAndMetadata() {
		McpInputRequest request = inputRequest(
				McpInputRequestDeclaration.fromRoots(
						McpInputRequirement.CONDITIONAL));
		McpJsonObject firstState = McpJsonObject.builder()
				.put("round", 1)
				.build();
		McpJsonObject secondState = McpJsonObject.builder()
				.put("round", 2)
				.build();
		McpJsonObject firstMetadata = McpJsonObject.builder()
				.put("version", 1)
				.build();
		McpJsonObject secondMetadata = McpJsonObject.builder()
				.put("version", 2)
				.build();
		McpInputRequiredResult.Builder builder = McpInputRequiredResult.builder()
				.inputRequest("roots", request)
				.frameworkRequestState(firstState)
				.metadata(firstMetadata);
		McpInputRequiredResult firstSnapshot = builder.build();

		builder.applicationRequestState("opaque")
				.metadata(secondMetadata);
		McpInputRequiredResult applicationState = builder.build();
		assertEquals("opaque", ((McpApplicationRequestState) applicationState
				.getRequestState().orElseThrow()).value());
		assertSame(secondMetadata, applicationState.getMetadata());

		builder.frameworkRequestState(secondState);
		McpInputRequiredResult finalResult = builder.build();
		assertSame(secondState, ((McpFrameworkRequestState) finalResult
				.getRequestState().orElseThrow()).value());
		assertSame(firstState, ((McpFrameworkRequestState) firstSnapshot
				.getRequestState().orElseThrow()).value());
		assertSame(firstMetadata, firstSnapshot.getMetadata());

		assertThrows(NullPointerException.class,
				() -> builder.frameworkRequestState(null));
		assertThrows(NullPointerException.class,
				() -> builder.applicationRequestState(null));
		assertThrows(IllegalArgumentException.class,
				() -> builder.applicationRequestState(""));
		assertThrows(NullPointerException.class,
				() -> builder.metadata(null));
		McpInputRequiredResult afterFailures = builder.build();
		assertSame(secondState, ((McpFrameworkRequestState) afterFailures
				.getRequestState().orElseThrow()).value());
		assertSame(secondMetadata, afterFailures.getMetadata());
	}

	@Test
	void inputResponsesDefensivelyCopyAndSupportRawAndIntrinsicTypedLookup() {
		McpJsonObject approvalJson = McpJsonObject.builder()
				.put("action", "accept")
				.build();
		McpJsonArray tagsJson = McpJsonArray.builder()
				.add("first")
				.add("second")
				.build();
		Map<String, McpJsonValue> mutableResponses = new LinkedHashMap<>();
		mutableResponses.put("approval", approvalJson);
		McpInputResponses responses = McpInputResponses.builder()
				.responses(mutableResponses)
				.response("tags", tagsJson)
				.build();
		mutableResponses.clear();

		assertSame(approvalJson, responses.find("approval").orElseThrow());
		assertEquals(new ApprovalResponse("accept"),
				responses.find("approval", ApprovalResponse.class).orElseThrow());
		assertEquals(List.of("first", "second"), responses.find("tags",
				new TypeReference<List<String>>() {}).orElseThrow());
		assertTrue(responses.find("missing").isEmpty());
		assertTrue(responses.find("missing", ApprovalResponse.class).isEmpty());
		assertEquals(List.of("approval", "tags"),
				new ArrayList<>(responses.asMap().keySet()));
		assertThrows(UnsupportedOperationException.class,
				() -> responses.asMap().clear());
		assertSame(McpInputResponses.emptyInstance(),
				McpInputResponses.fromResponses(Map.of()));
		assertSame(McpInputResponses.emptyInstance(),
				McpInputResponses.builder().build());
		IllegalArgumentException conversionFailure = assertThrows(
				IllegalArgumentException.class,
				() -> responses.find("approval", List.class));
		assertFalse(String.valueOf(conversionFailure.getMessage())
				.contains("accept"));

		McpInputResponses.Builder reusableBuilder = McpInputResponses.builder()
				.response("first", approvalJson);
		McpInputResponses firstSnapshot = reusableBuilder.build();
		reusableBuilder.response("second", tagsJson);
		assertEquals(Set.of("first"), firstSnapshot.asMap().keySet());

		Map<String, McpJsonValue> invalidBatch = new LinkedHashMap<>();
		invalidBatch.put("would-partially-append", approvalJson);
		invalidBatch.put("invalid", null);
		assertThrows(NullPointerException.class,
				() -> reusableBuilder.responses(invalidBatch));
		assertEquals(Set.of("first", "second"),
				reusableBuilder.build().asMap().keySet());
	}

	@Test
	void requestStateValuesAreClosedAndRejectTheSecondAbsenceConvention() {
		String frameworkSecret = "secret-framework-state";
		String applicationSecret = "secret-application-state";
		McpJsonValue value = new McpJsonString(frameworkSecret);
		McpRequestState framework = new McpFrameworkRequestState(value);
		McpRequestState application = new McpApplicationRequestState(
				applicationSecret);

		assertSame(value, ((McpFrameworkRequestState) framework).value());
		assertEquals(applicationSecret,
				((McpApplicationRequestState) application).value());
		assertEquals("McpFrameworkRequestState{value=<redacted>}",
				framework.toString());
		assertEquals("McpApplicationRequestState{value=<redacted>}",
				application.toString());
		assertFalse(framework.toString().contains(frameworkSecret));
		assertFalse(application.toString().contains(applicationSecret));
		assertThrows(IllegalArgumentException.class,
				() -> new McpApplicationRequestState(""));
		assertThrows(NullPointerException.class,
				() -> new McpApplicationRequestState(null));
		assertThrows(NullPointerException.class,
				() -> new McpFrameworkRequestState(null));
	}

	@Test
	void invalidDescriptorBatchesDoNotPartiallyMutateRegistrationBuilders() {
		McpInputRequestDeclaration approval =
				McpInputRequestDeclaration.fromElicitationForm(
						McpInputRequirement.REQUIRED);

		McpToolRegistration.Builder<McpJsonObject> toolBuilder =
				McpToolRegistration.withName("catalog.delete")
						.jsonArguments()
						.handler((request, call, features) ->
								McpCompleteResult.fromToolText("done"));
		McpPromptRegistration.Builder promptBuilder = McpPromptRegistration
				.withName("confirm")
				.handler((request, get, features) ->
						McpCompleteResult.fromPromptOutput(
								McpPromptOutput.fromMessages()));
		McpResourceRegistration.ExactBuilder exactBuilder =
				McpResourceRegistration
						.withUriAndName(URI.create("catalog://item/42"), "item")
						.handler(resourceHandler());
		McpResourceRegistration.TemplateBuilder templateBuilder =
				McpResourceRegistration
						.withUriTemplateAndName(
								"catalog://item/{itemId}", "item")
						.handler(resourceHandler());

		assertThrows(NullPointerException.class, () ->
				toolBuilder.mayRequestInput(approval, null));
		assertThrows(NullPointerException.class, () ->
				promptBuilder.mayRequestInput(approval, null));
		assertThrows(NullPointerException.class, () ->
				exactBuilder.mayRequestInput(approval, null));
		assertThrows(NullPointerException.class, () ->
				templateBuilder.mayRequestInput(approval, null));

		assertTrue(toolBuilder.build().getInputRequestDeclarations().isEmpty());
		assertTrue(promptBuilder.build().getInputRequestDeclarations().isEmpty());
		assertTrue(exactBuilder.build().getInputRequestDeclarations().isEmpty());
		assertTrue(templateBuilder.build()
				.getInputRequestDeclarations().isEmpty());
	}

	@Test
	void advancedRegistrationsStoreDescriptorsWithoutChangingDefaults() {
		McpInputRequestDeclaration approval =
				McpInputRequestDeclaration.fromElicitationForm(
						McpInputRequirement.REQUIRED);
		McpInputRequestDeclaration roots =
				McpInputRequestDeclaration.fromRoots(
						McpInputRequirement.CONDITIONAL);
		McpInputRequestDeclaration[] mutableDeclarations = {approval};

		McpToolRegistration<McpJsonObject> tool = McpToolRegistration
				.withName("catalog.delete")
				.jsonArguments()
				.handler((request, call, features) ->
						McpCompleteResult.fromToolText("done"))
				.mayRequestInput(mutableDeclarations)
				.mayRequestInput(roots)
				.requestStateMode(McpRequestStateMode.FRAMEWORK_PROTECTED)
				.build();
		mutableDeclarations[0] = roots;

		McpPromptRegistration prompt = McpPromptRegistration
				.withName("confirm")
				.handler((request, get, features) ->
						McpCompleteResult.fromPromptOutput(
								McpPromptOutput.fromMessages()))
				.mayRequestInput(approval)
				.requestStateMode(McpRequestStateMode.APPLICATION_PROTECTED)
				.build();
		McpResourceRegistration exact = McpResourceRegistration
				.withUriAndName(URI.create("catalog://item/42"), "item")
				.handler(resourceHandler())
				.mayRequestInput(approval)
				.requestStateMode(McpRequestStateMode.FRAMEWORK_PROTECTED)
				.build();
		McpResourceRegistration template = McpResourceRegistration
				.withUriTemplateAndName("catalog://item/{itemId}", "item")
				.handler(resourceHandler())
				.mayRequestInput(roots)
				.requestStateMode(McpRequestStateMode.APPLICATION_PROTECTED)
				.build();

		assertEquals(List.of(approval, roots),
				tool.getInputRequestDeclarations());
		assertEquals(McpRequestStateMode.FRAMEWORK_PROTECTED,
				tool.getRequestStateMode());
		assertEquals(List.of(approval), prompt.getInputRequestDeclarations());
		assertEquals(McpRequestStateMode.APPLICATION_PROTECTED,
				prompt.getRequestStateMode());
		assertEquals(List.of(approval), exact.getInputRequestDeclarations());
		assertEquals(List.of(roots), template.getInputRequestDeclarations());
		assertThrows(UnsupportedOperationException.class,
				() -> tool.getInputRequestDeclarations().clear());
	}

	@Test
	void registrationDefaultsRemainNeutralAndCompleteToolsCannotDeclareMrtr() {
		McpToolRegistration<Arguments> complete = McpToolRegistration
				.withName("catalog.get")
				.types(Arguments.class, CompleteOutput.class)
				.handler((request, call, features) ->
						new CompleteOutput(call.getArguments().identifier()))
				.build();
		Object completeBuilder = McpToolRegistration
				.withName("catalog.other")
				.types(Arguments.class, CompleteOutput.class)
				.handler((request, call, features) ->
						new CompleteOutput(call.getArguments().identifier()));
		McpPromptRegistration prompt = McpPromptRegistration
				.withName("plain")
				.handler((request, get, features) ->
						McpCompleteResult.fromPromptOutput(
								McpPromptOutput.fromMessages()))
				.build();
		McpResourceRegistration resource = McpResourceRegistration
				.withUriAndName(URI.create("catalog://plain"), "plain")
				.handler(resourceHandler())
				.build();

		assertTrue(complete.getInputRequestDeclarations().isEmpty());
		assertEquals(McpRequestStateMode.NONE, complete.getRequestStateMode());
		assertTrue(prompt.getInputRequestDeclarations().isEmpty());
		assertEquals(McpRequestStateMode.NONE, prompt.getRequestStateMode());
		assertTrue(resource.getInputRequestDeclarations().isEmpty());
		assertEquals(McpRequestStateMode.NONE, resource.getRequestStateMode());
		assertFalse(List.of(completeBuilder.getClass().getMethods()).stream()
				.anyMatch(method -> method.getName().equals("mayRequestInput")
						|| method.getName().equals("requestStateMode")));
	}

	@Test
	void requestContextCompatibilityDefaultsExposeNoMrtrData() {
		McpRequestContext context = (McpRequestContext) Proxy.newProxyInstance(
				McpRequestContext.class.getClassLoader(),
				new Class<?>[] {McpRequestContext.class},
				(proxy, method, arguments) -> {
					if (method.isDefault())
						return InvocationHandler.invokeDefault(
								proxy, method, arguments);
					throw new UnsupportedOperationException(method.getName());
				});

		assertSame(McpInputResponses.emptyInstance(),
				context.getInputResponses());
		assertTrue(context.getRequestState().isEmpty());
	}

	private static McpInputRequest inputRequest(
			McpInputRequestDeclaration declaration) {
		return McpInputRequest.fromDeclaration(declaration,
				McpJsonObject.builder().put("future", true).build());
	}

	private static McpResourceHandler resourceHandler() {
		return (request, resource, features) ->
				McpCompleteResult.fromResourceOutput(McpResourceOutput.builder()
						.content(McpTextResourceContents.withUriAndText(
								resource.getUri(), "value").build())
						.build());
	}

	private record ApprovalResponse(String action) {
	}

	private record Arguments(String identifier) {
	}

	private record CompleteOutput(String identifier) {
	}
}
