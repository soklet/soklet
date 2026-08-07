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

import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.ThreadSafe;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
sealed interface McpClientCapabilityRequirement permits McpCoreClientCapability {
}

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
enum McpCoreClientCapability implements McpClientCapabilityRequirement {
	ELICITATION_FORM,
	ELICITATION_URL,
	SAMPLING,
	SAMPLING_CONTEXT,
	SAMPLING_TOOLS,
	ROOTS
}

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
enum McpInputRequirement {
	REQUIRED,
	CONDITIONAL
}

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
record McpInputRequestDeclaration(@NonNull String method,
		@NonNull Set<@NonNull McpClientCapabilityRequirement> capabilities,
		@NonNull McpInputRequirement requirement) {
	@NonNull
	private static final Set<@NonNull String> CORE_METHODS =
			Set.of("elicitation/create", "sampling/createMessage", "roots/list");

	McpInputRequestDeclaration {
		method = McpProtocolSupport.requireNonBlank(method, "Input-request method");
		requireNonNull(capabilities);
		requireNonNull(requirement);
		Set<McpClientCapabilityRequirement> copiedCapabilities =
				new LinkedHashSet<>(capabilities.size());

		for (McpClientCapabilityRequirement capability : capabilities)
			copiedCapabilities.add(requireNonNull(capability));

		if (copiedCapabilities.isEmpty())
			throw new IllegalArgumentException(
					"At least one input-request capability is required.");

		capabilities = Collections.unmodifiableSet(copiedCapabilities);

		if (!CORE_METHODS.contains(method))
			throw new IllegalArgumentException(
					"Soklet 3.6 supports only the three core input-request methods.");

		if ("elicitation/create".equals(method)
				&& !(capabilities.equals(Set.of(McpCoreClientCapability.ELICITATION_FORM))
				|| capabilities.equals(Set.of(McpCoreClientCapability.ELICITATION_URL))))
			throw new IllegalArgumentException(
					"Elicitation declarations must select exactly form or URL capability.");

		if ("sampling/createMessage".equals(method)) {
			Set<McpClientCapabilityRequirement> allowed = Set.of(
					McpCoreClientCapability.SAMPLING,
					McpCoreClientCapability.SAMPLING_CONTEXT,
					McpCoreClientCapability.SAMPLING_TOOLS);

			if (!capabilities.contains(McpCoreClientCapability.SAMPLING)
					|| !allowed.containsAll(capabilities))
				throw new IllegalArgumentException(
						"Sampling declarations require SAMPLING and only sampling capabilities.");
		}

		if ("roots/list".equals(method)
				&& !capabilities.equals(Set.of(McpCoreClientCapability.ROOTS)))
			throw new IllegalArgumentException(
					"Roots declarations require exactly the ROOTS capability.");
	}

	@NonNull
	static McpInputRequestDeclaration elicitationForm(
			@NonNull McpInputRequirement requirement) {
		return new McpInputRequestDeclaration("elicitation/create",
				Set.of(McpCoreClientCapability.ELICITATION_FORM), requirement);
	}

	@NonNull
	static McpInputRequestDeclaration elicitationUrl(
			@NonNull McpInputRequirement requirement) {
		return new McpInputRequestDeclaration("elicitation/create",
				Set.of(McpCoreClientCapability.ELICITATION_URL), requirement);
	}

	@NonNull
	static McpInputRequestDeclaration sampling(
			@NonNull Set<@NonNull McpCoreClientCapability> optionalCapabilities,
			@NonNull McpInputRequirement requirement) {
		requireNonNull(optionalCapabilities);
		Set<McpClientCapabilityRequirement> capabilities = new LinkedHashSet<>();
		capabilities.add(McpCoreClientCapability.SAMPLING);
		capabilities.addAll(optionalCapabilities);
		return new McpInputRequestDeclaration(
				"sampling/createMessage", capabilities, requirement);
	}

	@NonNull
	static McpInputRequestDeclaration roots(@NonNull McpInputRequirement requirement) {
		return new McpInputRequestDeclaration("roots/list",
				Set.of(McpCoreClientCapability.ROOTS), requirement);
	}

}

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
record McpInputRequestPlan(
		@NonNull List<@NonNull McpInputRequestDeclaration> declarations) {
	McpInputRequestPlan {
		declarations = List.copyOf(requireNonNull(declarations));

		for (McpInputRequestDeclaration declaration : declarations)
			requireNonNull(declaration);
	}

	@NonNull
	static McpInputRequestPlan empty() {
		return new McpInputRequestPlan(List.of());
	}

	@NonNull
	Set<@NonNull McpClientCapabilityRequirement> missingAtAdmission(
			@NonNull McpClientCapabilities clientCapabilities) {
		requireNonNull(clientCapabilities);
		Set<McpClientCapabilityRequirement> missingCapabilities = new LinkedHashSet<>();

		for (McpInputRequestDeclaration declaration : declarations) {
			if (declaration.requirement() == McpInputRequirement.REQUIRED)
				addMissing(declaration, clientCapabilities, missingCapabilities);
		}

		return Collections.unmodifiableSet(missingCapabilities);
	}

	boolean requiresUncommittedResponse(
			@NonNull McpClientCapabilities clientCapabilities) {
		requireNonNull(clientCapabilities);

		for (McpInputRequestDeclaration declaration : declarations) {
			if (declaration.requirement() == McpInputRequirement.CONDITIONAL
					&& !missingForEmission(declaration, clientCapabilities).isEmpty())
				return true;
		}

		return false;
	}

	@NonNull
	Set<@NonNull McpClientCapabilityRequirement> missingForEmission(
			@NonNull McpInputRequestDeclaration declaration,
			@NonNull McpClientCapabilities clientCapabilities) {
		requireNonNull(declaration);
		requireNonNull(clientCapabilities);
		requireDeclared(declaration);

		Set<McpClientCapabilityRequirement> missingCapabilities = new LinkedHashSet<>();
		addMissing(declaration, clientCapabilities, missingCapabilities);
		return Collections.unmodifiableSet(missingCapabilities);
	}

	void requireDeclared(@NonNull McpInputRequestDeclaration declaration) {
		if (!declarations.contains(requireNonNull(declaration)))
			throw new IllegalArgumentException(
					"Input request was not declared by this operation.");
	}

	private static void addMissing(@NonNull McpInputRequestDeclaration declaration,
			@NonNull McpClientCapabilities clientCapabilities,
			@NonNull Set<@NonNull McpClientCapabilityRequirement> missingCapabilities) {
		for (McpClientCapabilityRequirement capability : declaration.capabilities()) {
			if (!clientCapabilities.supports(capability))
				missingCapabilities.add(capability);
		}
	}
}

/**
 * Phase 1 contract for the prior-ID evidence stored in framework-protected
 * request state. Phase 5 adds the authenticated envelope and context binding.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
record McpRetryIdentity(@NonNull McpJsonRpcId originatingRequestId) {
	McpRetryIdentity {
		requireNonNull(originatingRequestId);
	}

	void requireFreshRequestId(@NonNull McpJsonRpcId retryRequestId) {
		requireNonNull(retryRequestId);

		if (originatingRequestId.equals(retryRequestId))
			throw new IllegalArgumentException(
					"An MRTR retry must use a fresh JSON-RPC request ID.");
	}
}
