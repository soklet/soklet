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
 * of client capabilities required to emit it. Soklet 3.6 accepts only the
 * core {@code elicitation/create}, {@code sampling/createMessage}, and
 * {@code roots/list} methods; this type is not an extension escape hatch.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpInputRequestDeclaration {
	@NonNull
	private static final Set<@NonNull String> CORE_METHODS =
			Set.of("elicitation/create", "sampling/createMessage", "roots/list");
	@NonNull
	private static final Set<@NonNull McpClientCapability>
			OPTIONAL_SAMPLING_CAPABILITIES = Set.of(
					McpClientCapability.SAMPLING_CONTEXT,
					McpClientCapability.SAMPLING_TOOLS);
	@NonNull
	private final String method;
	@NonNull
	private final Set<@NonNull McpClientCapability> capabilities;
	@NonNull
	private final McpInputRequirement requirement;

	/**
	 * Creates and validates an input-request declaration.
	 *
	 * @param method client request method
	 * @param capabilities required client capabilities
	 * @param requirement when the capabilities are required
	 * @throws NullPointerException if an argument or capability is null
	 * @throws IllegalArgumentException if the method or capability combination
	 * is not one of Soklet 3.6's supported core declarations
	 */
	private McpInputRequestDeclaration(@NonNull String method,
			@NonNull Set<@NonNull McpClientCapability> capabilities,
			@NonNull McpInputRequirement requirement) {
		requireNonNull(method);
		requireNonNull(capabilities);
		requireNonNull(requirement);
		Set<McpClientCapability> copiedCapabilities =
				new LinkedHashSet<>(capabilities.size());
		for (McpClientCapability capability : capabilities)
			copiedCapabilities.add(requireNonNull(capability));

		if (method.isBlank())
			throw new IllegalArgumentException(
					"Input-request methods must not be blank.");
		if (copiedCapabilities.isEmpty())
			throw new IllegalArgumentException(
					"At least one input-request capability is required.");
		if (!CORE_METHODS.contains(method))
			throw new IllegalArgumentException(
					"Soklet 3.6 supports only the three core input-request methods.");

		Set<McpClientCapability> immutableCapabilities =
				Collections.unmodifiableSet(copiedCapabilities);
		if ("elicitation/create".equals(method)
				&& !(immutableCapabilities.equals(Set.of(
						McpClientCapability.ELICITATION_FORM))
				|| immutableCapabilities.equals(Set.of(
						McpClientCapability.ELICITATION_URL))))
			throw new IllegalArgumentException(
					"Elicitation declarations must select exactly form or URL capability.");

		if ("sampling/createMessage".equals(method)) {
			Set<McpClientCapability> allowed = Set.of(
					McpClientCapability.SAMPLING,
					McpClientCapability.SAMPLING_CONTEXT,
					McpClientCapability.SAMPLING_TOOLS);
			if (!immutableCapabilities.contains(McpClientCapability.SAMPLING)
					|| !allowed.containsAll(immutableCapabilities))
				throw new IllegalArgumentException(
						"Sampling declarations require SAMPLING and only sampling capabilities.");
		}

		if ("roots/list".equals(method)
				&& !immutableCapabilities.equals(Set.of(McpClientCapability.ROOTS)))
			throw new IllegalArgumentException(
					"Roots declarations require exactly the ROOTS capability.");

		this.method = method;
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
		return new McpInputRequestDeclaration("elicitation/create",
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
		return new McpInputRequestDeclaration("elicitation/create",
				Set.of(McpClientCapability.ELICITATION_URL), requirement);
	}

	/**
	 * Creates a sampling declaration.
	 *
	 * <p>Base {@link McpClientCapability#SAMPLING} support is included
	 * automatically. The supplied set may additionally contain only
	 * {@link McpClientCapability#SAMPLING_CONTEXT} and
	 * {@link McpClientCapability#SAMPLING_TOOLS}.
	 *
	 * @param optionalCapabilities optional sampling capabilities
	 * @param requirement when sampling support is required
	 * @return sampling declaration
	 * @throws NullPointerException if an argument or capability is null
	 * @throws IllegalArgumentException if anything other than an optional
	 * sampling capability is supplied
	 */
	@NonNull
	public static McpInputRequestDeclaration fromSampling(
			@NonNull Set<@NonNull McpClientCapability> optionalCapabilities,
			@NonNull McpInputRequirement requirement) {
		requireNonNull(optionalCapabilities);
		Set<McpClientCapability> capabilities = new LinkedHashSet<>();
		capabilities.add(McpClientCapability.SAMPLING);
		for (McpClientCapability capability : optionalCapabilities) {
			requireNonNull(capability);
			if (!OPTIONAL_SAMPLING_CAPABILITIES.contains(capability))
				throw new IllegalArgumentException(
						"Only optional sampling capabilities may be supplied.");
			capabilities.add(capability);
		}
		return new McpInputRequestDeclaration(
				"sampling/createMessage", capabilities, requirement);
	}

	/**
	 * Creates a roots-list declaration.
	 *
	 * @param requirement when roots support is required
	 * @return roots-list declaration
	 */
	@NonNull
	public static McpInputRequestDeclaration fromRoots(
			@NonNull McpInputRequirement requirement) {
		return new McpInputRequestDeclaration("roots/list",
				Set.of(McpClientCapability.ROOTS), requirement);
	}

	/** @return client request method */
	@NonNull
	public String getMethod() {
		return this.method;
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

	/** @return whether this value has the same method, capabilities, and requirement */
	@Override
	public boolean equals(@Nullable Object other) {
		if (this == other)
			return true;
		if (!(other instanceof McpInputRequestDeclaration declaration))
			return false;
		return this.method.equals(declaration.method)
				&& this.capabilities.equals(declaration.capabilities)
				&& this.requirement == declaration.requirement;
	}

	/** @return value-based hash code */
	@Override
	public int hashCode() {
		return Objects.hash(this.method, this.capabilities, this.requirement);
	}

	/** @return value-based diagnostic rendering */
	@Override
	@NonNull
	public String toString() {
		return "McpInputRequestDeclaration{method='%s', capabilities=%s, requirement=%s}"
				.formatted(this.method, this.capabilities, this.requirement);
	}
}
