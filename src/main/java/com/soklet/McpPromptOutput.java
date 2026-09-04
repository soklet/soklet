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
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Immutable output of one completed MCP prompt request.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpPromptOutput implements McpCompletePayload {
	@Nullable
	private final String description;
	@NonNull
	private final List<@NonNull McpPromptMessage> messages;

	/** @return an empty prompt-output builder */
	@NonNull
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Creates prompt output from messages.
	 *
	 * @param messages prompt messages
	 * @return immutable prompt output
	 */
	@NonNull
	public static McpPromptOutput fromMessages(
			@NonNull McpPromptMessage... messages) {
		return builder().addMessages(List.of(messages)).build();
	}

	private McpPromptOutput(@NonNull Builder builder) {
		this.description = builder.description;
		this.messages = List.copyOf(builder.messages);
	}

	/** @return prompt description, if supplied */
	@NonNull
	public Optional<@NonNull String> getDescription() {
		return Optional.ofNullable(this.description);
	}

	/** @return immutable messages in insertion order */
	@NonNull
	public List<@NonNull McpPromptMessage> getMessages() {
		return this.messages;
	}

	/**
	 * Mutable builder for immutable {@link McpPromptOutput}.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static final class Builder {
		@Nullable
		private String description;
		@NonNull
		private final List<@NonNull McpPromptMessage> messages =
				new ArrayList<>();

		private Builder() {
		}

		/** @param description prompt description
		 * @return this builder */
		@NonNull
		public Builder description(@NonNull String description) {
			this.description = requireNonNull(description);
			return this;
		}

		/**
		 * Appends one prompt message.
		 *
		 * @param message prompt message
		 * @return this builder
		 */
		@NonNull
		public Builder addMessage(@NonNull McpPromptMessage message) {
			this.messages.add(requireNonNull(message));
			return this;
		}

		/**
		 * Appends prompt messages in iteration order.
		 *
		 * @param messages prompt messages
		 * @return this builder
		 */
		@NonNull
		public Builder addMessages(
				@NonNull Collection<@NonNull McpPromptMessage> messages) {
			requireNonNull(messages);
			messages.forEach(this::addMessage);
			return this;
		}

		/** @return immutable prompt output */
		@NonNull
		public McpPromptOutput build() {
			return new McpPromptOutput(this);
		}
	}
}
