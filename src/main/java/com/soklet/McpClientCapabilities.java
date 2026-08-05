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

import javax.annotation.concurrent.ThreadSafe;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Immutable, presence-aware MCP client capabilities.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpClientCapabilities {
	@NonNull
	private final McpJsonObject json;
	@NonNull
	private final Map<@NonNull String, @NonNull McpJsonObject> extensions;

	@NonNull
	static McpClientCapabilities fromJson(@NonNull McpJsonObject json) {
		return new McpClientCapabilities(json);
	}

	private McpClientCapabilities(@NonNull McpJsonObject json) {
		this.json = requireNonNull(json);
		Map<String, McpJsonObject> extensions = new LinkedHashMap<>();
		json.find("extensions")
				.filter(McpJsonObject.class::isInstance)
				.map(McpJsonObject.class::cast)
				.ifPresent(object -> object.getMembers().forEach((name, value) -> {
					if (value instanceof McpJsonObject settings)
						extensions.put(name, settings);
				}));
		this.extensions = Map.copyOf(extensions);
	}

	/**
	 * Determines whether a core capability is present.
	 *
	 * @param capability capability to inspect
	 * @return whether the client advertised it
	 */
	public boolean supports(@NonNull McpClientCapability capability) {
		requireNonNull(capability);
		return switch (capability) {
			case ROOTS -> this.json.find("roots").filter(McpJsonObject.class::isInstance).isPresent();
			case SAMPLING -> object("sampling").isPresent();
			case SAMPLING_CONTEXT -> object("sampling")
					.map(value -> value.find("context").filter(McpJsonObject.class::isInstance).isPresent())
					.orElse(false);
			case SAMPLING_TOOLS -> object("sampling")
					.map(value -> value.find("tools").filter(McpJsonObject.class::isInstance).isPresent())
					.orElse(false);
			case ELICITATION_FORM -> object("elicitation")
					.map(value -> value.getMembers().isEmpty()
							|| value.find("form").filter(McpJsonObject.class::isInstance).isPresent())
					.orElse(false);
			case ELICITATION_URL -> object("elicitation")
					.map(value -> value.find("url").filter(McpJsonObject.class::isInstance).isPresent())
					.orElse(false);
		};
	}

	/**
	 * Finds settings for an advertised namespaced extension.
	 *
	 * @param extensionIdentifier extension identifier
	 * @return immutable extension settings, when advertised
	 */
	@NonNull
	public Optional<@NonNull McpJsonObject> findExtension(
			@NonNull String extensionIdentifier) {
		return Optional.ofNullable(this.extensions.get(requireNonNull(extensionIdentifier)));
	}

	/** @return immutable extension settings keyed by extension identifier */
	@NonNull
	public Map<@NonNull String, @NonNull McpJsonObject> getExtensions() {
		return this.extensions;
	}

	/** @return immutable JSON representation of every advertised capability */
	@NonNull
	public McpJsonObject toJson() {
		return this.json;
	}

	@NonNull
	private Optional<@NonNull McpJsonObject> object(@NonNull String name) {
		return this.json.find(name).filter(McpJsonObject.class::isInstance)
				.map(McpJsonObject.class::cast);
	}
}
