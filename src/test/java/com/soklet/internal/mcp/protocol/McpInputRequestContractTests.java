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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class McpInputRequestContractTests {
	@Test
	public void core_declarations_enforce_their_exact_capability_shapes() {
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> new McpInputRequestDeclaration(
						"elicitation/create",
						Set.of(McpCoreClientCapability.ELICITATION_FORM,
								McpCoreClientCapability.ELICITATION_URL),
						McpInputRequirement.REQUIRED));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> new McpInputRequestDeclaration(
						"sampling/createMessage",
						Set.of(McpCoreClientCapability.SAMPLING_CONTEXT),
						McpInputRequirement.REQUIRED));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> new McpInputRequestDeclaration(
						"roots/list",
						Set.of(McpCoreClientCapability.SAMPLING),
						McpInputRequirement.REQUIRED));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> new McpInputRequestDeclaration(
						"com.example/requestApproval",
						Set.of(McpCoreClientCapability.ELICITATION_FORM),
						McpInputRequirement.REQUIRED));
	}

	@Test
	public void required_capabilities_preflight_while_conditional_capabilities_wait_for_emission() {
		McpInputRequestDeclaration required =
				McpInputRequestDeclaration.elicitationForm(McpInputRequirement.REQUIRED);
		McpInputRequestDeclaration conditional =
				McpInputRequestDeclaration.roots(McpInputRequirement.CONDITIONAL);
		McpInputRequestPlan plan =
				new McpInputRequestPlan(List.of(required, conditional));
		McpClientCapabilities emptyCapabilities = McpClientCapabilities.empty();

		Assertions.assertEquals(Set.of(McpCoreClientCapability.ELICITATION_FORM),
				plan.missingAtAdmission(emptyCapabilities));
		Assertions.assertTrue(plan.requiresUncommittedResponse(emptyCapabilities));
		Assertions.assertEquals(Set.of(McpCoreClientCapability.ROOTS),
				plan.missingForEmission(conditional, emptyCapabilities));

		McpClientCapabilities completeCapabilities = McpClientCapabilities.builder()
				.capability(McpCoreClientCapability.ELICITATION_FORM)
				.capability(McpCoreClientCapability.ROOTS)
				.build();
		Assertions.assertTrue(plan.missingAtAdmission(completeCapabilities).isEmpty());
		Assertions.assertFalse(plan.requiresUncommittedResponse(completeCapabilities));
		Assertions.assertTrue(plan.missingForEmission(
				conditional, completeCapabilities).isEmpty());
		McpInputRequestDeclaration undeclared =
				McpInputRequestDeclaration.sampling(
						Set.of(), McpInputRequirement.CONDITIONAL);
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> plan.missingForEmission(undeclared, completeCapabilities));
		Assertions.assertDoesNotThrow(() -> plan.missingForEmission(
				McpInputRequestDeclaration.roots(McpInputRequirement.CONDITIONAL),
				completeCapabilities));
	}

	@Test
	public void input_request_declarations_are_limited_to_final_core_schema_methods() {
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> new McpInputRequestDeclaration(
						"com.example/requestApproval",
						Set.of(McpCoreClientCapability.ELICITATION_FORM),
						McpInputRequirement.CONDITIONAL));
		Assertions.assertDoesNotThrow(() ->
				McpInputRequestDeclaration.elicitationForm(McpInputRequirement.REQUIRED));
		Assertions.assertDoesNotThrow(() ->
				McpInputRequestDeclaration.elicitationUrl(McpInputRequirement.CONDITIONAL));
		Assertions.assertDoesNotThrow(() ->
				McpInputRequestDeclaration.sampling(
						Set.of(McpCoreClientCapability.SAMPLING_TOOLS),
						McpInputRequirement.CONDITIONAL));
		Assertions.assertDoesNotThrow(() ->
				McpInputRequestDeclaration.roots(McpInputRequirement.REQUIRED));
	}

	@Test
	public void sampling_subcapabilities_imply_sampling_in_builder_but_not_raw_wire_shape() {
		McpClientCapabilities built = McpClientCapabilities.builder()
				.capability(McpCoreClientCapability.SAMPLING_TOOLS)
				.build();
		Assertions.assertTrue(built.supports(McpCoreClientCapability.SAMPLING));
		Assertions.assertTrue(built.supports(McpCoreClientCapability.SAMPLING_TOOLS));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> new McpClientCapabilities(
						Optional.empty(), Optional.empty(), Optional.of(
								new McpJsonObject(Map.of(
										"tools", new McpJsonString("not-an-object")))),
						Map.of(), Map.of(), Map.of()));
	}

	@Test
	public void capability_preflight_uses_only_the_current_request() {
		McpInputRequestDeclaration declaration =
				McpInputRequestDeclaration.elicitationForm(McpInputRequirement.REQUIRED);
		McpInputRequestPlan plan = new McpInputRequestPlan(List.of(declaration));
		McpClientCapabilities requestA = McpClientCapabilities.builder()
				.capability(McpCoreClientCapability.ELICITATION_FORM)
				.build();
		McpClientCapabilities requestB = McpClientCapabilities.empty();

		Assertions.assertTrue(plan.missingAtAdmission(requestA).isEmpty());
		Assertions.assertEquals(Set.of(McpCoreClientCapability.ELICITATION_FORM),
				plan.missingAtAdmission(requestB));
		Assertions.assertTrue(plan.missingAtAdmission(requestA).isEmpty());
	}

	@Test
	public void framework_state_prior_id_evidence_rejects_only_exact_id_reuse() {
		McpRetryIdentity stringIdentity =
				new McpRetryIdentity(new McpJsonRpcId.StringId("1"));
		McpRetryIdentity integerIdentity =
				new McpRetryIdentity(new McpJsonRpcId.IntegerId(java.math.BigInteger.ONE));

		Assertions.assertThrows(IllegalArgumentException.class,
				() -> stringIdentity.requireFreshRequestId(new McpJsonRpcId.StringId("1")));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> integerIdentity.requireFreshRequestId(
						new McpJsonRpcId.IntegerId(java.math.BigInteger.ONE)));
		Assertions.assertDoesNotThrow(
				() -> stringIdentity.requireFreshRequestId(
						new McpJsonRpcId.IntegerId(java.math.BigInteger.ONE)));
		Assertions.assertDoesNotThrow(
				() -> stringIdentity.requireFreshRequestId(new McpJsonRpcId.StringId("2")));
	}
}
