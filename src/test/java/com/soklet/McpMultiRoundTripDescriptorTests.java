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
				Set.of(McpClientCapability.SAMPLING_CONTEXT));
		McpInputRequestDeclaration copiedSampling =
				McpInputRequestDeclaration.fromSampling(mutableCapabilities,
						McpInputRequirement.CONDITIONAL);
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

		assertEquals(McpInputRequestType.ELICITATION_FORM,
				form.getInputRequestType());
		assertEquals("elicitation/create", form.getJsonRpcMethod());
		assertEquals(Set.of(McpClientCapability.ELICITATION_FORM),
				form.getCapabilities());
		assertEquals(McpInputRequirement.REQUIRED, form.getRequirement());
		assertEquals(Set.of(McpClientCapability.ELICITATION_URL),
				url.getCapabilities());
		assertEquals(Set.of(McpClientCapability.SAMPLING,
				McpClientCapability.SAMPLING_CONTEXT,
				McpClientCapability.SAMPLING_TOOLS), sampling.getCapabilities());
		assertEquals(Set.of(McpClientCapability.SAMPLING,
				McpClientCapability.SAMPLING_CONTEXT),
				copiedSampling.getCapabilities());
		assertEquals(McpInputRequestType.ELICITATION_URL,
				url.getInputRequestType());
		assertEquals(McpInputRequestType.SAMPLING,
				sampling.getInputRequestType());
		assertEquals(McpInputRequestType.ROOTS,
				roots.getInputRequestType());
		assertEquals("roots/list", roots.getJsonRpcMethod());
		assertThrows(UnsupportedOperationException.class,
				() -> form.getCapabilities().clear());
		assertEquals(form, McpInputRequestDeclaration.fromElicitationForm(
				McpInputRequirement.REQUIRED));
		assertEquals(form.hashCode(), McpInputRequestDeclaration
				.fromElicitationForm(McpInputRequirement.REQUIRED).hashCode());

		assertThrows(IllegalArgumentException.class,
				() -> McpInputRequestDeclaration.fromSampling(
						Set.of(McpClientCapability.SAMPLING),
						McpInputRequirement.CONDITIONAL));
		assertThrows(IllegalArgumentException.class,
				() -> McpInputRequestDeclaration.fromSampling(
						Set.of(McpClientCapability.ROOTS),
						McpInputRequirement.CONDITIONAL));
		assertThrows(NullPointerException.class,
				() -> McpInputRequestDeclaration.fromElicitationForm(null));
		assertThrows(NullPointerException.class,
				() -> McpInputRequestDeclaration.fromSampling(null,
						McpInputRequirement.CONDITIONAL));
		assertThrows(NullPointerException.class,
				() -> McpInputRequestDeclaration.fromRoots(null));
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

			assertSame(declaration, request.getDeclaration());
			assertSame(params, request.getParams());
			assertEquals(declaration.getJsonRpcMethod(), request.getMethod());
			assertEquals(request,
					McpInputRequest.fromDeclaration(declaration, params));
			assertEquals(request.hashCode(),
					McpInputRequest.fromDeclaration(declaration, params).hashCode());
			assertEquals("McpInputRequest{method='%s', params=<redacted>}"
					.formatted(declaration.getJsonRpcMethod()), request.toString());
			assertFalse(request.toString().contains(secretParams));
		}

		McpInputRequestDeclaration roots = declarations.get(3);
		McpJsonObject emptyParams = McpJsonObject.emptyInstance();
		assertSame(emptyParams,
				McpInputRequest.fromDeclaration(roots, emptyParams).getParams());
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
		assertThrows(NullPointerException.class,
				() -> McpInputRequiredResult.withInputRequest(null, request));
		assertThrows(NullPointerException.class,
				() -> McpInputRequiredResult.withInputRequest("approval", null));
		assertThrows(NullPointerException.class,
				() -> McpInputRequiredResult.withFrameworkRequestState(null));
		assertThrows(NullPointerException.class,
				() -> McpInputRequiredResult.withApplicationRequestState(null));
		assertThrows(IllegalArgumentException.class,
				() -> McpInputRequiredResult.withApplicationRequestState(""));

		McpInputRequiredResult inputOnly = McpInputRequiredResult.withInputRequest("approval", request)
				.build();
		assertEquals(Map.of("approval", request), inputOnly.getInputRequests());
		assertTrue(inputOnly.getFrameworkRequestState().isEmpty());
		assertTrue(inputOnly.getApplicationRequestState().isEmpty());
		assertSame(McpJsonObject.emptyInstance(), inputOnly.getMetadata());

		McpInputRequiredResult stateOnly = McpInputRequiredResult.withFrameworkRequestState(McpJsonNull.INSTANCE)
				.build();
		assertTrue(stateOnly.getInputRequests().isEmpty());
		assertSame(McpJsonNull.INSTANCE,
				stateOnly.getFrameworkRequestState().orElseThrow());
		assertTrue(stateOnly.getApplicationRequestState().isEmpty());

		McpJsonObject metadata = McpJsonObject.builder()
				.put("dev.example/result", "combined")
				.build();
		McpInputRequiredResult combined = McpInputRequiredResult.withInputRequest("approval", request)
				.applicationRequestState("opaque-state")
				.metadata(metadata)
				.build();
		assertEquals(Map.of("approval", request), combined.getInputRequests());
		assertEquals("opaque-state",
				combined.getApplicationRequestState().orElseThrow());
		assertTrue(combined.getFrameworkRequestState().isEmpty());
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
		McpInputRequiredResult.Builder builder = McpInputRequiredResult.withInputRequest("", first)
				.addInputRequest("   ", second)
				.addInputRequest(secretId, third);
		McpInputRequiredResult snapshot = builder.build();

		assertEquals(List.of("", "   ", secretId),
				new ArrayList<>(snapshot.getInputRequests().keySet()));
		assertThrows(UnsupportedOperationException.class,
				() -> snapshot.getInputRequests().clear());
		IllegalArgumentException duplicate = assertThrows(
				IllegalArgumentException.class,
				() -> builder.addInputRequest(secretId, first));
		assertFalse(String.valueOf(duplicate.getMessage()).contains(secretId));
		assertSame(third, builder.build().getInputRequests().get(secretId));

		Map<String, McpInputRequest> beforeNullFailures =
				builder.build().getInputRequests();
		assertThrows(NullPointerException.class,
				() -> builder.addInputRequest(null, first));
		String nullRequestId = "secret-null-request-id";
		NullPointerException nullRequest = assertThrows(
				NullPointerException.class,
				() -> builder.addInputRequest(nullRequestId, null));
		assertFalse(String.valueOf(nullRequest.getMessage())
				.contains(nullRequestId));
		assertEquals(beforeNullFailures, builder.build().getInputRequests());

		builder.addInputRequest("later", first);
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
		McpInputRequiredResult.Builder builder = McpInputRequiredResult.withInputRequest("roots", request)
				.frameworkRequestState(firstState)
				.metadata(firstMetadata);
		McpInputRequiredResult firstSnapshot = builder.build();

		builder.applicationRequestState("opaque")
				.metadata(secondMetadata);
		McpInputRequiredResult applicationState = builder.build();
		assertEquals("opaque",
				applicationState.getApplicationRequestState().orElseThrow());
		assertTrue(applicationState.getFrameworkRequestState().isEmpty());
		assertSame(secondMetadata, applicationState.getMetadata());

		builder.frameworkRequestState(secondState);
		McpInputRequiredResult finalResult = builder.build();
		assertSame(secondState,
				finalResult.getFrameworkRequestState().orElseThrow());
		assertTrue(finalResult.getApplicationRequestState().isEmpty());
		assertSame(firstState,
				firstSnapshot.getFrameworkRequestState().orElseThrow());
		assertTrue(firstSnapshot.getApplicationRequestState().isEmpty());
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
		assertSame(secondState,
				afterFailures.getFrameworkRequestState().orElseThrow());
		assertTrue(afterFailures.getApplicationRequestState().isEmpty());
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
				.addResponses(mutableResponses)
				.addResponse("tags", tagsJson)
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
				.addResponse("first", approvalJson);
		McpInputResponses firstSnapshot = reusableBuilder.build();
		reusableBuilder.addResponse("second", tagsJson);
		assertEquals(Set.of("first"), firstSnapshot.asMap().keySet());

		Map<String, McpJsonValue> invalidBatch = new LinkedHashMap<>();
		invalidBatch.put("would-partially-append", approvalJson);
		invalidBatch.put("invalid", null);
		assertThrows(NullPointerException.class,
				() -> reusableBuilder.addResponses(invalidBatch));
		assertEquals(Set.of("first", "second"),
				reusableBuilder.build().asMap().keySet());
	}

	@Test
	void requestStateAccessorsAreTypedAndRejectTheSecondAbsenceConvention() {
		String frameworkSecret = "secret-framework-state";
		String applicationSecret = "secret-application-state";
		McpJsonValue value = McpJsonString.fromValue(frameworkSecret);
		McpInputRequiredResult framework = McpInputRequiredResult.withFrameworkRequestState(value)
				.build();
		McpInputRequiredResult application = McpInputRequiredResult.withApplicationRequestState(applicationSecret)
				.build();

		assertSame(value, framework.getFrameworkRequestState().orElseThrow());
		assertTrue(framework.getApplicationRequestState().isEmpty());
		assertEquals(applicationSecret,
				application.getApplicationRequestState().orElseThrow());
		assertTrue(application.getFrameworkRequestState().isEmpty());
		assertThrows(IllegalArgumentException.class,
				() -> McpInputRequiredResult.withApplicationRequestState(""));
		assertThrows(NullPointerException.class,
				() -> McpInputRequiredResult.withApplicationRequestState(null));
		assertThrows(NullPointerException.class,
				() -> McpInputRequiredResult.withFrameworkRequestState(null));
	}

	@Test
	void invalidDescriptorBatchesDoNotPartiallyMutateRegistrationBuilders() {
		McpInputRequestDeclaration approval =
				McpInputRequestDeclaration.fromElicitationForm(
						McpInputRequirement.REQUIRED);

		McpToolRegistration.OperationBuilder<McpJsonObject> toolBuilder =
				McpToolRegistration.withName("catalog.delete")
						.jsonObjectArguments()
						.handler((request, arguments, features) ->
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
				toolBuilder.addInputRequestDeclarations(approval, null));
		assertThrows(NullPointerException.class, () ->
				promptBuilder.addInputRequestDeclarations(approval, null));
		assertThrows(NullPointerException.class, () ->
				exactBuilder.addInputRequestDeclarations(approval, null));
		assertThrows(NullPointerException.class, () ->
				templateBuilder.addInputRequestDeclarations(approval, null));

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
				.jsonObjectArguments()
				.handler((request, arguments, features) ->
						McpCompleteResult.fromToolText("done"))
				.addInputRequestDeclarations(mutableDeclarations)
				.addInputRequestDeclarations(roots)
				.requestStateMode(McpRequestStateMode.FRAMEWORK_PROTECTED)
				.build();
		mutableDeclarations[0] = roots;

		McpPromptRegistration prompt = McpPromptRegistration
				.withName("confirm")
				.handler((request, get, features) ->
						McpCompleteResult.fromPromptOutput(
								McpPromptOutput.fromMessages()))
				.addInputRequestDeclarations(approval)
				.requestStateMode(McpRequestStateMode.APPLICATION_PROTECTED)
				.build();
		McpResourceRegistration exact = McpResourceRegistration
				.withUriAndName(URI.create("catalog://item/42"), "item")
				.handler(resourceHandler())
				.addInputRequestDeclarations(approval)
				.requestStateMode(McpRequestStateMode.FRAMEWORK_PROTECTED)
				.build();
		McpResourceRegistration template = McpResourceRegistration
				.withUriTemplateAndName("catalog://item/{itemId}", "item")
				.handler(resourceHandler())
				.addInputRequestDeclarations(roots)
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
				.argumentAndOutputTypes(Arguments.class, CompleteOutput.class)
				.handler((request, arguments, features) ->
						new CompleteOutput(arguments.getConvertedArguments().identifier()))
				.build();
		Object completeBuilder = McpToolRegistration
				.withName("catalog.other")
				.argumentAndOutputTypes(Arguments.class, CompleteOutput.class)
				.handler((request, arguments, features) ->
						new CompleteOutput(arguments.getConvertedArguments().identifier()));
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

	private static McpInputRequest inputRequest(
			McpInputRequestDeclaration declaration) {
		return McpInputRequest.fromDeclaration(declaration,
				McpJsonObject.builder().put("future", true).build());
	}

	private static McpResourceReadHandler resourceHandler() {
		return (request, resource, features) ->
				McpCompleteResult.fromResourceOutput(McpResourceOutput.withContent(McpTextResourceContents.withUriAndText(
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
