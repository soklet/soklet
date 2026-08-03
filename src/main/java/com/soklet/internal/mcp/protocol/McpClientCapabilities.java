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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Presence-aware provisional representation of the open client-capability object.
 */
record McpClientCapabilities(Optional<McpJsonObject> elicitation,
		Optional<McpJsonObject> roots, Optional<McpJsonObject> sampling,
		Map<String, McpJsonObject> extensions, Map<String, McpJsonObject> experimental,
		Map<String, McpJsonValue> unknownCapabilities) {
	private static final Set<String> KNOWN_CAPABILITY_NAMES =
			Set.of("elicitation", "roots", "sampling", "extensions", "experimental");

	McpClientCapabilities {
		requireNonNull(elicitation);
		requireNonNull(roots);
		requireNonNull(sampling);
		extensions = McpProtocolSupport.immutableOpenObjectMap(extensions);
		experimental = McpProtocolSupport.immutableOpenObjectMap(experimental);
		unknownCapabilities = McpProtocolSupport.immutableOpenObjectMap(unknownCapabilities);

		for (String identifier : extensions.keySet())
			McpProtocolSupport.requireExtensionIdentifier(identifier);

		for (String name : unknownCapabilities.keySet()) {
			if (KNOWN_CAPABILITY_NAMES.contains(name))
				throw new IllegalArgumentException(
						"Unknown capability collides with known capability '" + name + "'.");
		}

		validateObjectMembers(elicitation, Set.of("form", "url"), "elicitation");
		validateObjectMembers(sampling, Set.of("context", "tools"), "sampling");
	}

	static McpClientCapabilities empty() {
		return builder().build();
	}

	static Builder builder() {
		return new Builder();
	}

	static McpClientCapabilities fromRequirements(Set<McpClientCapabilityRequirement> requirements) {
		requireNonNull(requirements);
		Builder builder = builder();

		for (McpClientCapabilityRequirement requirement : requirements)
			builder.capability((McpCoreClientCapability) requireNonNull(requirement));

		return builder.build();
	}

	boolean supports(McpClientCapabilityRequirement requirement) {
		requireNonNull(requirement);

		McpCoreClientCapability coreCapability = (McpCoreClientCapability) requirement;

		return switch (coreCapability) {
			case ELICITATION_FORM -> elicitation
					.map(value -> value.members().isEmpty()
							|| value.members().containsKey("form"))
					.orElse(false);
			case ELICITATION_URL -> elicitation
					.map(value -> value.members().containsKey("url"))
					.orElse(false);
			case ROOTS -> roots.isPresent();
			case SAMPLING -> sampling.isPresent();
			case SAMPLING_CONTEXT -> sampling
					.map(value -> value.members().containsKey("context"))
					.orElse(false);
			case SAMPLING_TOOLS -> sampling
					.map(value -> value.members().containsKey("tools"))
					.orElse(false);
		};
	}

	McpJsonObject toJsonObject() {
		Map<String, McpJsonValue> values = new LinkedHashMap<>(unknownCapabilities);
		elicitation.ifPresent(value -> values.put("elicitation", value));
		roots.ifPresent(value -> values.put("roots", value));
		sampling.ifPresent(value -> values.put("sampling", value));

		if (!extensions.isEmpty())
			values.put("extensions", objectOfObjects(extensions));

		if (!experimental.isEmpty())
			values.put("experimental", objectOfObjects(experimental));

		return new McpJsonObject(values);
	}

	private static void validateObjectMembers(Optional<McpJsonObject> capability,
			Set<String> objectMemberNames, String capabilityName) {
		capability.ifPresent(value -> {
			for (String memberName : objectMemberNames) {
				McpJsonValue member = value.members().get(memberName);

				if (member != null && !(member instanceof McpJsonObject))
					throw new IllegalArgumentException("Client capability '" + capabilityName
							+ "." + memberName + "' must be an object.");
			}
		});
	}

	private static McpJsonObject objectOfObjects(Map<String, McpJsonObject> objects) {
		Map<String, McpJsonValue> values = new LinkedHashMap<>(objects.size());
		values.putAll(objects);
		return new McpJsonObject(values);
	}

	static final class Builder {
		private Optional<McpJsonObject> elicitation;
		private Optional<McpJsonObject> roots;
		private Optional<McpJsonObject> sampling;
		private final Map<String, McpJsonObject> extensions;
		private final Map<String, McpJsonObject> experimental;
		private final Map<String, McpJsonValue> unknownCapabilities;

		private Builder() {
			this.elicitation = Optional.empty();
			this.roots = Optional.empty();
			this.sampling = Optional.empty();
			this.extensions = new LinkedHashMap<>();
			this.experimental = new LinkedHashMap<>();
			this.unknownCapabilities = new LinkedHashMap<>();
		}

		Builder capability(McpCoreClientCapability capability) {
			requireNonNull(capability);

			switch (capability) {
				case ELICITATION_FORM -> elicitation = Optional.of(
						withObjectMember(elicitation.orElseGet(McpJsonObject::empty), "form"));
				case ELICITATION_URL -> elicitation = Optional.of(
						withObjectMember(elicitation.orElseGet(McpJsonObject::empty), "url"));
				case ROOTS -> roots = Optional.of(roots.orElseGet(McpJsonObject::empty));
				case SAMPLING -> sampling = Optional.of(sampling.orElseGet(McpJsonObject::empty));
				case SAMPLING_CONTEXT -> sampling = Optional.of(
						withObjectMember(sampling.orElseGet(McpJsonObject::empty), "context"));
				case SAMPLING_TOOLS -> sampling = Optional.of(
						withObjectMember(sampling.orElseGet(McpJsonObject::empty), "tools"));
			}

			return this;
		}

		Builder elicitation(McpJsonObject settings) {
			elicitation = Optional.of(requireNonNull(settings));
			return this;
		}

		Builder roots(McpJsonObject settings) {
			roots = Optional.of(requireNonNull(settings));
			return this;
		}

		Builder sampling(McpJsonObject settings) {
			sampling = Optional.of(requireNonNull(settings));
			return this;
		}

		Builder extension(String identifier, McpJsonObject settings) {
			extensions.put(McpProtocolSupport.requireExtensionIdentifier(identifier),
					requireNonNull(settings));
			return this;
		}

		Builder experimental(String name, McpJsonObject settings) {
			experimental.put(requireNonNull(name), requireNonNull(settings));
			return this;
		}

		Builder unknown(String name, McpJsonValue settings) {
			unknownCapabilities.put(requireNonNull(name), requireNonNull(settings));
			return this;
		}

		McpClientCapabilities build() {
			return new McpClientCapabilities(elicitation, roots, sampling,
					extensions, experimental, unknownCapabilities);
		}

		private static McpJsonObject withObjectMember(
				McpJsonObject object, String memberName) {
			Map<String, McpJsonValue> values = new LinkedHashMap<>(object.members());
			values.putIfAbsent(memberName, McpJsonObject.empty());
			return new McpJsonObject(values);
		}
	}
}
