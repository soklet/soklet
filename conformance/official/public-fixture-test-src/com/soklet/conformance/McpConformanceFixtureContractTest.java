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

package com.soklet.conformance;

import com.soklet.McpCompleteResult;
import com.soklet.McpEndpoint;
import com.soklet.McpFrameworkRequestState;
import com.soklet.McpInputRequest;
import com.soklet.McpInputRequiredResult;
import com.soklet.McpInputResponses;
import com.soklet.McpInvocationFeatures;
import com.soklet.McpJsonObject;
import com.soklet.McpJsonString;
import com.soklet.McpJsonValue;
import com.soklet.McpOperationResult;
import com.soklet.McpPromptGetContext;
import com.soklet.McpPromptRegistration;
import com.soklet.McpRequestContext;
import com.soklet.McpRequestState;
import com.soklet.McpRequestStateMode;
import com.soklet.McpToolArguments;
import com.soklet.McpToolRegistration;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Standalone public-API contract test for the packaged Phase 5 fixture.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public final class McpConformanceFixtureContractTest {
	private static final String ELICITATION =
			"test_input_required_result_elicitation";
	private static final String SAMPLING =
			"test_input_required_result_sampling";
	private static final String ROOTS =
			"test_input_required_result_list_roots";
	private static final String REQUEST_STATE =
			"test_input_required_result_request_state";
	private static final String MULTIPLE =
			"test_input_required_result_multiple_inputs";
	private static final String MULTI_ROUND =
			"test_input_required_result_multi_round";
	private static final String TAMPERED =
			"test_input_required_result_tampered_state";
	private static final String CAPABILITIES =
			"test_input_required_result_capabilities";
	private static final String PROMPT =
			"test_input_required_result_prompt";
	private static final Set<String> PHASE_5_TOOL_NAMES = Set.of(
			ELICITATION, SAMPLING, ROOTS, REQUEST_STATE, MULTIPLE,
			MULTI_ROUND, TAMPERED, CAPABILITIES);
	private static final Map<String, String> SCENARIO_TO_TOOL = Map.ofEntries(
			Map.entry("input-required-result-basic-elicitation", ELICITATION),
			Map.entry("input-required-result-basic-sampling", SAMPLING),
			Map.entry("input-required-result-basic-list-roots", ROOTS),
			Map.entry("input-required-result-request-state", REQUEST_STATE),
			Map.entry("input-required-result-multiple-input-requests", MULTIPLE),
			Map.entry("input-required-result-multi-round", MULTI_ROUND),
			Map.entry("input-required-result-missing-input-response", ELICITATION),
			Map.entry("input-required-result-result-type", ELICITATION),
			Map.entry("input-required-result-tampered-state", TAMPERED),
			Map.entry("input-required-result-capability-check", CAPABILITIES),
			Map.entry("input-required-result-ignore-extra-params", ELICITATION),
			Map.entry("input-required-result-validate-input", ELICITATION));
	private static final Set<String> PHASE_5_SCENARIOS = Set.of(
			"input-required-result-basic-elicitation",
			"input-required-result-basic-sampling",
			"input-required-result-basic-list-roots",
			"input-required-result-request-state",
			"input-required-result-multiple-input-requests",
			"input-required-result-multi-round",
			"input-required-result-missing-input-response",
			"input-required-result-non-tool-request",
			"input-required-result-result-type",
			"input-required-result-unsupported-methods",
			"input-required-result-tampered-state",
			"input-required-result-capability-check",
			"input-required-result-ignore-extra-params",
			"input-required-result-validate-input");
	private static final McpInvocationFeatures NO_FEATURES =
			McpInvocationFeatures.fromFeatures(Map.of());

	private McpConformanceFixtureContractTest() {
	}

	public static void main(String[] arguments) throws Exception {
		registrationsAreExactAndScenarioScoped();
		basicHandlersCompleteOnlyAfterTheirExpectedResponses();
		frameworkStateHandlersAdvanceAndCompleteDeterministically();
		promptHandlerUsesTheUniversalInputRequiredResultContract();
	}

	private static void registrationsAreExactAndScenarioScoped() {
		for (String scenario : PHASE_5_SCENARIOS) {
			McpEndpoint endpoint = McpConformanceFixture
					.endpointForScenario(scenario);
			long phase5Tools = endpoint.getTools().stream()
					.filter(tool -> PHASE_5_TOOL_NAMES.contains(tool.getName()))
					.count();
			String expectedTool = SCENARIO_TO_TOOL.get(scenario);
			assertEquals(expectedTool == null ? 0L : 1L, phase5Tools,
					"Unexpected Phase 5 tool count for " + scenario);
			if (expectedTool != null)
				assertEquals(expectedTool, tool(endpoint, expectedTool).getName(),
						"Wrong tool for " + scenario);

			long phase5Prompts = endpoint.getPrompts().stream()
					.filter(prompt -> PROMPT.equals(prompt.getName()))
					.count();
			assertEquals("input-required-result-non-tool-request".equals(scenario)
					? 1L : 0L, phase5Prompts,
					"Unexpected Phase 5 prompt count for " + scenario);
		}

		McpEndpoint phase4 = McpConformanceFixture.endpointForScenario("tools-list");
		assertEquals(0L, phase4.getTools().stream()
				.filter(tool -> PHASE_5_TOOL_NAMES.contains(tool.getName())).count(),
				"Phase 5 tools leaked into the reviewed Phase 4 catalog");
		assertEquals(0L, phase4.getPrompts().stream()
				.filter(prompt -> PROMPT.equals(prompt.getName())).count(),
				"The Phase 5 prompt leaked into the reviewed Phase 4 catalog");

		assertRegistration("input-required-result-basic-elicitation", ELICITATION,
				McpRequestStateMode.NONE, List.of("elicitation/create"));
		assertRegistration("input-required-result-basic-sampling", SAMPLING,
				McpRequestStateMode.NONE, List.of("sampling/createMessage"));
		assertRegistration("input-required-result-basic-list-roots", ROOTS,
				McpRequestStateMode.NONE, List.of("roots/list"));
		assertRegistration("input-required-result-request-state", REQUEST_STATE,
				McpRequestStateMode.FRAMEWORK_PROTECTED,
				List.of("elicitation/create"));
		assertRegistration("input-required-result-multiple-input-requests", MULTIPLE,
				McpRequestStateMode.FRAMEWORK_PROTECTED,
				List.of("elicitation/create", "sampling/createMessage", "roots/list"));
		assertRegistration("input-required-result-multi-round", MULTI_ROUND,
				McpRequestStateMode.FRAMEWORK_PROTECTED,
				List.of("elicitation/create"));
		assertRegistration("input-required-result-tampered-state", TAMPERED,
				McpRequestStateMode.FRAMEWORK_PROTECTED,
				List.of("elicitation/create"));
		assertRegistration("input-required-result-capability-check", CAPABILITIES,
				McpRequestStateMode.NONE, List.of("sampling/createMessage"));
	}

	private static void basicHandlersCompleteOnlyAfterTheirExpectedResponses()
			throws Exception {
		McpInputRequiredResult elicitation = assertInputRequired(invokeTool(
				"input-required-result-basic-elicitation", ELICITATION,
				context(responses(), null)));
		assertRequests(elicitation, List.of("user_name"),
				List.of("elicitation/create"), null);
		assertComplete(invokeTool("input-required-result-basic-elicitation",
				ELICITATION, context(responses("user_name"), null)));

		McpInputRequiredResult missing = assertInputRequired(invokeTool(
				"input-required-result-missing-input-response", ELICITATION,
				context(responses("wrong_key"), null)));
		assertRequests(missing, List.of("user_name"),
				List.of("elicitation/create"), null);
		assertComplete(invokeTool("input-required-result-ignore-extra-params",
				ELICITATION,
				context(responses("user_name", "unknown_extra_key"), null)));

		McpInputRequiredResult sampling = assertInputRequired(invokeTool(
				"input-required-result-basic-sampling", SAMPLING,
				context(responses(), null)));
		assertRequests(sampling, List.of("capital_question"),
				List.of("sampling/createMessage"), null);
		assertComplete(invokeTool("input-required-result-basic-sampling", SAMPLING,
				context(responses("capital_question"), null)));

		McpInputRequiredResult roots = assertInputRequired(invokeTool(
				"input-required-result-basic-list-roots", ROOTS,
				context(responses(), null)));
		assertRequests(roots, List.of("client_roots"), List.of("roots/list"), null);
		assertComplete(invokeTool("input-required-result-basic-list-roots", ROOTS,
				context(responses("client_roots"), null)));

		McpInputRequiredResult capability = assertInputRequired(invokeTool(
				"input-required-result-capability-check", CAPABILITIES,
				context(responses(), null)));
		assertRequests(capability, List.of("sampling"),
				List.of("sampling/createMessage"), null);
	}

	private static void frameworkStateHandlersAdvanceAndCompleteDeterministically()
			throws Exception {
		McpInputRequiredResult state = assertInputRequired(invokeTool(
				"input-required-result-request-state", REQUEST_STATE,
				context(responses(), null)));
		assertRequests(state, List.of("confirm"), List.of("elicitation/create"),
				"request-state");
		assertComplete(invokeTool("input-required-result-request-state",
				REQUEST_STATE, context(responses("confirm"), state("request-state"))));

		McpInputRequiredResult multiple = assertInputRequired(invokeTool(
				"input-required-result-multiple-input-requests", MULTIPLE,
				context(responses(), null)));
		assertRequests(multiple, List.of("user_name", "greeting", "client_roots"),
				List.of("elicitation/create", "sampling/createMessage", "roots/list"),
				"multiple-inputs");
		assertComplete(invokeTool("input-required-result-multiple-input-requests",
				MULTIPLE, context(responses("user_name", "greeting", "client_roots"),
						state("multiple-inputs"))));

		McpInputRequiredResult round1 = assertInputRequired(invokeTool(
				"input-required-result-multi-round", MULTI_ROUND,
				context(responses(), null)));
		assertRequests(round1, List.of("step1"), List.of("elicitation/create"),
				"round-1");
		McpInputRequiredResult round2 = assertInputRequired(invokeTool(
				"input-required-result-multi-round", MULTI_ROUND,
				context(responses("step1"), state("round-1"))));
		assertRequests(round2, List.of("step2"), List.of("elicitation/create"),
				"round-2");
		assertComplete(invokeTool("input-required-result-multi-round", MULTI_ROUND,
				context(responses("step2"), state("round-2"))));

		McpInputRequiredResult tampered = assertInputRequired(invokeTool(
				"input-required-result-tampered-state", TAMPERED,
				context(responses(), null)));
		assertRequests(tampered, List.of("confirm"), List.of("elicitation/create"),
				"tamper-check");
	}

	private static void promptHandlerUsesTheUniversalInputRequiredResultContract()
			throws Exception {
		McpEndpoint endpoint = McpConformanceFixture.endpointForScenario(
				"input-required-result-non-tool-request");
		McpPromptRegistration registration = endpoint.getPrompts().stream()
				.filter(prompt -> PROMPT.equals(prompt.getName()))
				.findFirst().orElseThrow();
		McpInputRequiredResult initial = assertInputRequired(
				registration.getHandler().handle(context(responses(), null),
						promptContext(), NO_FEATURES));
		assertRequests(initial, List.of("user_context"),
				List.of("elicitation/create"), null);
		assertComplete(registration.getHandler().handle(
				context(responses("user_context"), null), promptContext(), NO_FEATURES));
	}

	private static void assertRegistration(String scenario, String toolName,
			McpRequestStateMode expectedStateMode, List<String> expectedMethods) {
		McpToolRegistration<McpJsonObject> registration = tool(
				McpConformanceFixture.endpointForScenario(scenario), toolName);
		assertEquals(expectedStateMode, registration.getRequestStateMode(),
				"Wrong request-state mode for " + toolName);
		assertEquals(expectedMethods, registration.getInputRequestDeclarations()
				.stream().map(declaration -> declaration.method()).toList(),
				"Wrong input declarations for " + toolName);
	}

	private static McpOperationResult invokeTool(String scenario, String name,
			McpRequestContext context) throws Exception {
		return tool(McpConformanceFixture.endpointForScenario(scenario), name)
				.getHandler().handle(context, toolArguments(), NO_FEATURES);
	}

	@SuppressWarnings("unchecked")
	private static McpToolRegistration<McpJsonObject> tool(McpEndpoint endpoint,
			String name) {
		return (McpToolRegistration<McpJsonObject>) endpoint.getTools().stream()
				.filter(tool -> name.equals(tool.getName()))
				.findFirst().orElseThrow();
	}

	private static McpRequestContext context(McpInputResponses inputResponses,
			McpRequestState requestState) {
		return (McpRequestContext) Proxy.newProxyInstance(
				McpRequestContext.class.getClassLoader(),
				new Class<?>[] {McpRequestContext.class},
				(proxy, method, arguments) -> switch (method.getName()) {
					case "getInputResponses" -> inputResponses;
					case "getRequestState" -> Optional.ofNullable(requestState);
					case "toString" -> "McpConformanceFixtureContractContext";
					default -> throw new AssertionError(
							"Fixture handler unexpectedly called " + method.getName());
				});
	}

	private static McpToolArguments<McpJsonObject> toolArguments() {
		return new McpToolArguments<>() {
			@Override
			public McpJsonObject getArguments() {
				return McpJsonObject.emptyInstance();
			}

			@Override
			public McpJsonObject getRawArguments() {
				return McpJsonObject.emptyInstance();
			}
		};
	}

	private static McpPromptGetContext promptContext() {
		return new McpPromptGetContext() {
			@Override
			public Map<String, String> getArguments() {
				return Map.of();
			}

			@Override
			public Optional<String> findArgument(String name) {
				return Optional.empty();
			}
		};
	}

	private static McpInputResponses responses(String... keys) {
		McpInputResponses.Builder builder = McpInputResponses.builder();
		for (String key : keys)
			builder.response(key, McpJsonObject.emptyInstance());
		return builder.build();
	}

	private static McpFrameworkRequestState state(String value) {
		return new McpFrameworkRequestState(new McpJsonString(value));
	}

	private static McpInputRequiredResult assertInputRequired(
			McpOperationResult result) {
		if (!(result instanceof McpInputRequiredResult inputRequired))
			throw new AssertionError("Expected input_required, got " + result);
		return inputRequired;
	}

	private static void assertComplete(McpOperationResult result) {
		if (!(result instanceof McpCompleteResult))
			throw new AssertionError("Expected complete result, got " + result);
	}

	private static void assertRequests(McpInputRequiredResult result,
			List<String> expectedKeys, List<String> expectedMethods,
			String expectedState) {
		assertEquals(expectedKeys, List.copyOf(result.getInputRequests().keySet()),
				"Wrong input-request keys");
		assertEquals(expectedMethods, result.getInputRequests().values().stream()
				.map(McpInputRequest::method).toList(), "Wrong input-request methods");
		if (expectedState == null) {
			assertEquals(Optional.empty(), result.getRequestState(),
					"Unexpected request state");
			return;
		}
		McpFrameworkRequestState frameworkState = result.getRequestState()
				.filter(McpFrameworkRequestState.class::isInstance)
				.map(McpFrameworkRequestState.class::cast)
				.orElseThrow(() -> new AssertionError(
						"Expected framework-protected request state"));
		McpJsonValue value = frameworkState.value();
		if (!(value instanceof McpJsonString stringValue))
			throw new AssertionError("Expected a string application state");
		assertEquals(expectedState, stringValue.value(), "Wrong application state");
	}

	private static void assertEquals(Object expected, Object actual,
			String message) {
		if (!expected.equals(actual))
			throw new AssertionError(message + ": expected=" + expected
					+ ", actual=" + actual);
	}
}
