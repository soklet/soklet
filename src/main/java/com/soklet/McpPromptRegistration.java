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

import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Immutable programmatic registration for one MCP prompt.
 *
 * <p>Registration is staged so a handler must be supplied before optional
 * descriptor metadata can be configured and the registration built. Prompt
 * arguments are flat strings and do not use JSON Schema.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpPromptRegistration {
	@NonNull
	private final String name;
	@Nullable
	private final String title;
	@Nullable
	private final String description;
	@NonNull
	private final List<@NonNull McpIcon> icons;
	@NonNull
	private final List<@NonNull McpPromptArgumentDefinition> arguments;
	@NonNull
	private final List<@NonNull McpInputRequestDeclaration> inputRequestDeclarations;
	@NonNull
	private final McpRequestStateMode requestStateMode;
	@NonNull
	private final McpJsonObject metadata;
	@NonNull
	private final McpPromptHandler handler;

	/**
	 * Begins a staged registration for a named prompt.
	 *
	 * @param name nonblank prompt name published to MCP clients
	 * @return required-handler stage
	 * @throws IllegalArgumentException if {@code name} is blank
	 */
	@NonNull
	public static NamedBuilder withName(@NonNull String name) {
		return new NamedBuilder(requireName(name));
	}

	private McpPromptRegistration(@NonNull Builder builder) {
		this.name = builder.name;
		this.title = builder.title;
		this.description = builder.description;
		this.icons = List.copyOf(builder.icons);
		this.arguments = immutableArguments(builder.arguments);
		this.inputRequestDeclarations =
				List.copyOf(builder.inputRequestDeclarations);
		this.requestStateMode = builder.requestStateMode;
		this.metadata = builder.metadata;
		this.handler = builder.handler;
	}

	/** @return published prompt name */
	@NonNull
	public String getName() {
		return this.name;
	}

	/** @return human-readable title, if configured */
	@NonNull
	public Optional<@NonNull String> getTitle() {
		return Optional.ofNullable(this.title);
	}

	/** @return human-readable description, if configured */
	@NonNull
	public Optional<@NonNull String> getDescription() {
		return Optional.ofNullable(this.description);
	}

	/** @return immutable icon list in registration order */
	@NonNull
	public List<@NonNull McpIcon> getIcons() {
		return this.icons;
	}

	/** @return immutable argument definitions in registration order */
	@NonNull
	public List<@NonNull McpPromptArgumentDefinition> getArguments() {
		return this.arguments;
	}

	/**
	 * Returns the input requests this prompt operation may emit.
	 *
	 * @return immutable declarations in registration order
	 */
	@NonNull
	public List<@NonNull McpInputRequestDeclaration>
			getInputRequestDeclarations() {
		return this.inputRequestDeclarations;
	}

	/**
	 * Returns the request-state contract for this prompt operation.
	 *
	 * @return request-state mode
	 */
	@NonNull
	public McpRequestStateMode getRequestStateMode() {
		return this.requestStateMode;
	}

	/** @return immutable protocol extension metadata */
	@NonNull
	public McpJsonObject getMetadata() {
		return this.metadata;
	}

	/** @return prompt handler */
	@NonNull
	public McpPromptHandler getHandler() {
		return this.handler;
	}

	/**
	 * Validates raw string arguments and invokes the prompt handler.
	 */
	@NonNull
	McpOperationResult invoke(@NonNull McpRequestContext request,
			@NonNull McpJsonObject rawArguments,
			@NonNull McpInvocationFeatures features) throws Exception {
		requireNonNull(request);
		requireNonNull(rawArguments);
		requireNonNull(features);

		Map<String, McpPromptArgumentDefinition> definitions =
				new LinkedHashMap<>();
		for (McpPromptArgumentDefinition argument : this.arguments)
			definitions.put(argument.getName(), argument);

		Map<String, String> values = new LinkedHashMap<>();
		for (Map.Entry<String, McpJsonValue> entry
				: rawArguments.getMembers().entrySet()) {
			if (!definitions.containsKey(entry.getKey())
					|| !(entry.getValue() instanceof McpJsonString string))
				throw new McpInvalidPromptArgumentsException();
			values.put(entry.getKey(), string.getValue());
		}
		for (McpPromptArgumentDefinition argument : this.arguments) {
			if (argument.isRequired() && !values.containsKey(argument.getName()))
				throw new McpInvalidPromptArgumentsException();
		}

		McpPromptGetContext prompt = new DefaultPromptGetContext(values);
		return requireNonNull(this.handler.handle(request, prompt, features),
				"The MCP prompt handler returned null.");
	}

	@NonNull
	private static String requireName(@NonNull String name) {
		requireNonNull(name);
		if (name.isBlank())
			throw new IllegalArgumentException(
					"MCP prompt names must not be blank.");
		return name;
	}

	@NonNull
	private static List<@NonNull McpPromptArgumentDefinition> immutableArguments(
			@NonNull List<@NonNull McpPromptArgumentDefinition> arguments) {
		List<McpPromptArgumentDefinition> copied = List.copyOf(arguments);
		Set<String> names = new LinkedHashSet<>();
		for (McpPromptArgumentDefinition argument : copied) {
			if (!names.add(argument.getName()))
				throw new IllegalStateException(
						"Duplicate MCP prompt argument name: "
								+ argument.getName());
		}
		return copied;
	}

	/**
	 * Required-handler stage for a named prompt.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static final class NamedBuilder {
		@NonNull
		private final String name;

		private NamedBuilder(@NonNull String name) {
			this.name = requireNonNull(name);
		}

		/**
		 * Supplies the required prompt handler.
		 *
		 * @param handler prompt handler
		 * @return optional-metadata builder
		 */
		@NonNull
		public Builder handler(@NonNull McpPromptHandler handler) {
			return new Builder(this.name, requireNonNull(handler));
		}
	}

	/**
	 * Mutable builder for an immutable prompt registration.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static final class Builder {
		@NonNull
		private final String name;
		@NonNull
		private final McpPromptHandler handler;
		@Nullable
		private String title;
		@Nullable
		private String description;
		@NonNull
		private final List<@NonNull McpIcon> icons = new ArrayList<>();
		@NonNull
		private final List<@NonNull McpPromptArgumentDefinition> arguments =
				new ArrayList<>();
		@NonNull
		private final List<@NonNull McpInputRequestDeclaration>
				inputRequestDeclarations = new ArrayList<>();
		@NonNull
		private McpRequestStateMode requestStateMode = McpRequestStateMode.NONE;
		@NonNull
		private McpJsonObject metadata = McpJsonObject.emptyInstance();

		private Builder(@NonNull String name,
				@NonNull McpPromptHandler handler) {
			this.name = requireNonNull(name);
			this.handler = requireNonNull(handler);
		}

		/** @param title human-readable title
		 * @return this builder */
		@NonNull
		public Builder title(@NonNull String title) {
			this.title = requireNonNull(title);
			return this;
		}

		/** @param description human-readable description
		 * @return this builder */
		@NonNull
		public Builder description(@NonNull String description) {
			this.description = requireNonNull(description);
			return this;
		}

		/** @param icon icon descriptor to append
		 * @return this builder */
		@NonNull
		public Builder icon(@NonNull McpIcon icon) {
			this.icons.add(requireNonNull(icon));
			return this;
		}

		/** @param argument argument definition to append
		 * @return this builder */
		@NonNull
		public Builder argument(
				@NonNull McpPromptArgumentDefinition argument) {
			this.arguments.add(requireNonNull(argument));
			return this;
		}

		/**
		 * Appends input-request declarations for this prompt operation.
		 *
		 * <p>Repeated calls append declarations in order.
		 *
		 * @param declarations declarations to append
		 * @return this builder
		 * @throws NullPointerException if the array or a declaration is null
		 */
		@NonNull
		public Builder mayRequestInput(
				@NonNull McpInputRequestDeclaration @NonNull ... declarations) {
			requireNonNull(declarations);
			List<McpInputRequestDeclaration> copiedDeclarations =
					new ArrayList<>(declarations.length);
			for (McpInputRequestDeclaration declaration : declarations)
				copiedDeclarations.add(requireNonNull(declaration));
			this.inputRequestDeclarations.addAll(copiedDeclarations);
			return this;
		}

		/**
		 * Sets the request-state contract for this prompt operation.
		 *
		 * @param requestStateMode request-state mode
		 * @return this builder
		 */
		@NonNull
		public Builder requestStateMode(
				@NonNull McpRequestStateMode requestStateMode) {
			this.requestStateMode = requireNonNull(requestStateMode);
			return this;
		}

		/** @param metadata protocol extension metadata
		 * @return this builder */
		@NonNull
		public Builder metadata(@NonNull McpJsonObject metadata) {
			this.metadata = requireNonNull(metadata);
			return this;
		}

		/**
		 * Builds an immutable prompt registration.
		 *
		 * @return prompt registration
		 * @throws IllegalStateException if argument names are duplicated
		 */
		@NonNull
		public McpPromptRegistration build() {
			return new McpPromptRegistration(this);
		}
	}

	/**
	 * Immutable prompt argument projection.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	private static final class DefaultPromptGetContext
			implements McpPromptGetContext {
		@NonNull
		private final Map<@NonNull String, @NonNull String> arguments;

		private DefaultPromptGetContext(
				@NonNull Map<@NonNull String, @NonNull String> arguments) {
			this.arguments = Collections.unmodifiableMap(
					new LinkedHashMap<>(requireNonNull(arguments)));
		}

		@Override
		@NonNull
		public Map<@NonNull String, @NonNull String> getArguments() {
			return this.arguments;
		}

		@Override
		@NonNull
		public Optional<@NonNull String> findArgument(@NonNull String name) {
			return Optional.ofNullable(this.arguments.get(requireNonNull(name)));
		}
	}
}
