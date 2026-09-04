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
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Immutable declaration of one string-valued MCP prompt argument.
 *
 * <p>Prompt arguments are not JSON Schema values. A present argument is
 * always delivered to the handler as its exact wire string; Soklet performs
 * no coercion or domain-specific validation.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpPromptArgumentDeclaration {
	@NonNull
	private final String name;
	@Nullable
	private final String title;
	@Nullable
	private final String description;
	private final boolean required;

	/**
	 * Vends a builder primed with the published argument name.
	 *
	 * @param name nonblank published argument name
	 * @return argument-declaration builder
	 * @throws IllegalArgumentException if {@code name} is blank
	 */
	@NonNull
	public static Builder withName(@NonNull String name) {
		return new Builder(requireName(name));
	}

	private McpPromptArgumentDeclaration(@NonNull Builder builder) {
		this.name = builder.name;
		this.title = builder.title;
		this.description = builder.description;
		this.required = builder.required;
	}

	/** @return published argument name */
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

	/** @return whether the argument must be present in {@code prompts/get} */
	@NonNull
	public Boolean isRequired() {
		return this.required;
	}

	@NonNull
	private static String requireName(@NonNull String name) {
		requireNonNull(name);
		if (name.isBlank())
			throw new IllegalArgumentException(
					"MCP prompt argument names must not be blank.");
		return name;
	}

	/**
	 * Mutable builder for an immutable prompt argument declaration.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static final class Builder {
		@NonNull
		private final String name;
		@Nullable
		private String title;
		@Nullable
		private String description;
		private boolean required;

		private Builder(@NonNull String name) {
			this.name = requireNonNull(name);
		}

		/**
		 * Sets the human-readable argument title.
		 *
		 * @param title human-readable title
		 * @return this builder
		 */
		@NonNull
		public Builder title(@NonNull String title) {
			this.title = requireNonNull(title);
			return this;
		}

		/**
		 * Sets the human-readable argument description.
		 *
		 * @param description human-readable description
		 * @return this builder
		 */
		@NonNull
		public Builder description(@NonNull String description) {
			this.description = requireNonNull(description);
			return this;
		}

		/**
		 * Sets whether the argument is required.
		 *
		 * @param required whether callers must supply this argument
		 * @return this builder
		 * @throws NullPointerException if {@code required} is null
		 */
		@NonNull
		public Builder required(@NonNull Boolean required) {
			this.required = requireNonNull(required);
			return this;
		}

		/** @return immutable prompt argument declaration */
		@NonNull
		public McpPromptArgumentDeclaration build() {
			return new McpPromptArgumentDeclaration(this);
		}
	}
}
