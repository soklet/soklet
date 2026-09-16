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

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.ThreadSafe;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Immutable declaration of an MCP client request that an operation may emit.
 *
 * <p>The declaration binds a core client request method to the complete set
 * of client capabilities required to emit it. Soklet supports only form and
 * URL elicitation through {@code elicitation/create}; this type is not an
 * extension escape hatch.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpInputRequestDeclaration {
	@NonNull
	private final McpInputRequestType inputRequestType;
	@NonNull
	private final String jsonRpcMethod;
	@NonNull
	private final Set<@NonNull McpClientCapability> capabilities;
	@NonNull
	private final McpInputRequirement requirement;

	/**
	 * Creates and validates an input-request declaration.
	 *
	 * @param inputRequestType client input-request type
	 * @param capabilities required client capabilities
	 * @param requirement when the capabilities are required
	 * @throws NullPointerException if an argument or capability is null
	 * @throws IllegalArgumentException if the type or capability combination
	 * is not one of Soklet's supported core declarations
	 */
	private McpInputRequestDeclaration(
			@NonNull McpInputRequestType inputRequestType,
			@NonNull Set<@NonNull McpClientCapability> capabilities,
			@NonNull McpInputRequirement requirement) {
		requireNonNull(inputRequestType);
		requireNonNull(capabilities);
		requireNonNull(requirement);
		Set<McpClientCapability> copiedCapabilities =
				new LinkedHashSet<>(capabilities.size());
		for (McpClientCapability capability : capabilities)
			copiedCapabilities.add(requireNonNull(capability));

		if (copiedCapabilities.isEmpty())
			throw new IllegalArgumentException(
					"At least one input-request capability is required.");

		Set<McpClientCapability> immutableCapabilities =
				Collections.unmodifiableSet(copiedCapabilities);
		validateCapabilities(inputRequestType, immutableCapabilities);

		this.inputRequestType = inputRequestType;
		this.jsonRpcMethod = jsonRpcMethod(inputRequestType);
		this.capabilities = immutableCapabilities;
		this.requirement = requirement;
	}

	/**
	 * Creates a form-based elicitation declaration.
	 *
	 * @param requirement when form elicitation support is required
	 * @return form-based elicitation declaration
	 */
	@NonNull
	public static McpInputRequestDeclaration fromElicitationForm(
			@NonNull McpInputRequirement requirement) {
		return new McpInputRequestDeclaration(McpInputRequestType.ELICITATION_FORM,
				Set.of(McpClientCapability.ELICITATION_FORM), requirement);
	}

	/**
	 * Creates a URL-based elicitation declaration.
	 *
	 * @param requirement when URL elicitation support is required
	 * @return URL-based elicitation declaration
	 */
	@NonNull
	public static McpInputRequestDeclaration fromElicitationUrl(
			@NonNull McpInputRequirement requirement) {
		return new McpInputRequestDeclaration(McpInputRequestType.ELICITATION_URL,
				Set.of(McpClientCapability.ELICITATION_URL), requirement);
	}

	/** @return core client-input request type */
	@NonNull
	public McpInputRequestType getInputRequestType() {
		return this.inputRequestType;
	}

	/** @return derived JSON-RPC client request method */
	@NonNull
	public String getJsonRpcMethod() {
		return this.jsonRpcMethod;
	}

	/** @return immutable required client capabilities */
	@NonNull
	public Set<@NonNull McpClientCapability> getCapabilities() {
		return this.capabilities;
	}

	/** @return when the client capabilities are required */
	@NonNull
	public McpInputRequirement getRequirement() {
		return this.requirement;
	}

	/** @return whether this value has the same type, capabilities, and requirement */
	@Override
	public boolean equals(@Nullable Object other) {
		if (this == other)
			return true;
		if (!(other instanceof McpInputRequestDeclaration declaration))
			return false;
		return this.inputRequestType == declaration.inputRequestType
				&& this.capabilities.equals(declaration.capabilities)
				&& this.requirement == declaration.requirement;
	}

	/** @return value-based hash code */
	@Override
	public int hashCode() {
		return Objects.hash(this.inputRequestType, this.capabilities,
				this.requirement);
	}

	/** @return value-based diagnostic rendering */
	@Override
	@NonNull
	public String toString() {
		return "McpInputRequestDeclaration{inputRequestType=%s, jsonRpcMethod='%s', capabilities=%s, requirement=%s}"
				.formatted(this.inputRequestType, this.jsonRpcMethod,
						this.capabilities, this.requirement);
	}

	private static void validateCapabilities(
			@NonNull McpInputRequestType inputRequestType,
			@NonNull Set<@NonNull McpClientCapability> capabilities) {
		switch (inputRequestType) {
			case ELICITATION_FORM -> requireExactCapabilities(capabilities,
					McpClientCapability.ELICITATION_FORM,
					"Form-elicitation declarations require exactly the ELICITATION_FORM capability.");
			case ELICITATION_URL -> requireExactCapabilities(capabilities,
					McpClientCapability.ELICITATION_URL,
					"URL-elicitation declarations require exactly the ELICITATION_URL capability.");
		}
	}

	private static void requireExactCapabilities(
			@NonNull Set<@NonNull McpClientCapability> capabilities,
			@NonNull McpClientCapability capability,
			@NonNull String message) {
		if (!capabilities.equals(Set.of(capability)))
			throw new IllegalArgumentException(message);
	}

	@NonNull
	private static String jsonRpcMethod(
			@NonNull McpInputRequestType inputRequestType) {
		return switch (inputRequestType) {
			case ELICITATION_FORM, ELICITATION_URL -> "elicitation/create";
		};
	}
}
