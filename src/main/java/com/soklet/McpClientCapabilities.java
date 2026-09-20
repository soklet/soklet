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

import com.soklet.internal.mcp.protocol.McpAppMimeType;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.ThreadSafe;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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

	/**
	 * Creates client capabilities from their immutable JSON representation.
	 * This factory is useful when unit-testing application code that consumes
	 * client capabilities outside a live MCP request.
	 *
	 * @param json client-capability JSON object
	 * @return immutable client capabilities
	 * @throws NullPointerException if {@code json} is null
	 */
	@NonNull
	public static McpClientCapabilities fromJson(
			@NonNull McpJsonObject json) {
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
	@NonNull
	public Boolean supports(@NonNull McpClientCapability capability) {
		requireNonNull(capability);
		return switch (capability) {
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
	 * Determines whether the client's MCP Apps extension advertises a structurally
	 * equivalent MIME type. Extension presence alone does not establish support.
	 * Missing or malformed {@code settings.mimeTypes} properties, including any malformed
	 * array member, do not establish support. Unrelated extension fields are retained.
	 *
	 * <p>Media type names and parameter names are ASCII case-insensitive; parameter
	 * order and spaces around separators are ignored. Quoted parameter values are
	 * decoded before comparison, but parameter-value case remains significant.</p>
	 *
	 * @param mimeType MIME type whose support is required
	 * @return whether the client advertised support for the MIME type
	 * @throws NullPointerException if {@code mimeType} is null
	 * @throws IllegalArgumentException if {@code mimeType} contains malformed syntax,
	 *                                  duplicate parameters, controls, or non-ASCII
	 *                                  characters
	 */
	@NonNull
	public Boolean supportsAppMimeType(@NonNull String mimeType) {
		McpJsonValue advertised = findExtension("io.modelcontextprotocol/ui")
				.flatMap(extension -> extension.find("mimeTypes")).orElse(null);
		List<@Nullable String> mimeTypes = null;
		if (advertised instanceof McpJsonArray array) {
			mimeTypes = new ArrayList<>(array.getElements().size());
			for (McpJsonValue value : array.getElements())
				mimeTypes.add(value instanceof McpJsonString string ? string.getValue() : null);
		}
		return McpAppMimeType.supportsMimeType(mimeType, mimeTypes);
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

	/** @return whether every advertised capability is structurally equal */
	@Override
	public boolean equals(@Nullable Object other) {
		return this == other
				|| other instanceof McpClientCapabilities capabilities
				&& this.json.equals(capabilities.json);
	}

	/** @return structural capability hash code */
	@Override
	public int hashCode() {
		return this.json.hashCode();
	}

	@NonNull
	private Optional<@NonNull McpJsonObject> object(@NonNull String name) {
		return this.json.find(name).filter(McpJsonObject.class::isInstance)
				.map(McpJsonObject.class::cast);
	}
}
