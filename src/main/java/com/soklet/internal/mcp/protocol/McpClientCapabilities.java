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

import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Presence-aware provisional representation of the open client-capability object.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
record McpClientCapabilities(@NonNull Optional<@NonNull McpJsonObject> elicitation,
		@NonNull Optional<@NonNull McpJsonObject> roots,
		@NonNull Optional<@NonNull McpJsonObject> sampling,
		@NonNull Map<@NonNull String, @NonNull McpJsonObject> extensions,
		@NonNull Map<@NonNull String, @NonNull McpJsonObject> experimental,
		@NonNull Map<@NonNull String, @NonNull McpJsonValue> unknownCapabilities) {
	@NonNull
	private static final Set<@NonNull String> KNOWN_CAPABILITY_NAMES =
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

	@NonNull
	static McpClientCapabilities empty() {
		return builder().build();
	}

	@NonNull
	static Builder builder() {
		return new Builder();
	}

	@NonNull
	static McpClientCapabilities fromRequirements(
			@NonNull Set<@NonNull McpClientCapabilityRequirement> requirements) {
		requireNonNull(requirements);
		Builder builder = builder();

		for (McpClientCapabilityRequirement requirement : requirements)
			builder.capability((McpCoreClientCapability) requireNonNull(requirement));

		return builder.build();
	}

	boolean supports(@NonNull McpClientCapabilityRequirement requirement) {
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

	@NonNull
	McpJsonObject toJsonObject() {
		Map<@NonNull String, @NonNull McpJsonValue> values =
				new LinkedHashMap<>(unknownCapabilities);
		elicitation.ifPresent(value -> values.put("elicitation", value));
		roots.ifPresent(value -> values.put("roots", value));
		sampling.ifPresent(value -> values.put("sampling", value));

		if (!extensions.isEmpty())
			values.put("extensions", objectOfObjects(extensions));

		if (!experimental.isEmpty())
			values.put("experimental", objectOfObjects(experimental));

		return new McpJsonObject(values);
	}

	private static void validateObjectMembers(
			@NonNull Optional<@NonNull McpJsonObject> capability,
			@NonNull Set<@NonNull String> objectMemberNames,
			@NonNull String capabilityName) {
		capability.ifPresent(value -> {
			for (String memberName : objectMemberNames) {
				McpJsonValue member = value.members().get(memberName);

				if (member != null && !(member instanceof McpJsonObject))
					throw new IllegalArgumentException("Client capability '" + capabilityName
							+ "." + memberName + "' must be an object.");
			}
		});
	}

	@NonNull
	private static McpJsonObject objectOfObjects(
			@NonNull Map<@NonNull String, @NonNull McpJsonObject> objects) {
		Map<@NonNull String, @NonNull McpJsonValue> values =
				new LinkedHashMap<>(objects.size());
		values.putAll(objects);
		return new McpJsonObject(values);
	}

	@NotThreadSafe
	static final class Builder {
		@NonNull
		private Optional<@NonNull McpJsonObject> elicitation;
		@NonNull
		private Optional<@NonNull McpJsonObject> roots;
		@NonNull
		private Optional<@NonNull McpJsonObject> sampling;
		@NonNull
		private final Map<@NonNull String, @NonNull McpJsonObject> extensions;
		@NonNull
		private final Map<@NonNull String, @NonNull McpJsonObject> experimental;
		@NonNull
		private final Map<@NonNull String, @NonNull McpJsonValue> unknownCapabilities;

		private Builder() {
			this.elicitation = Optional.empty();
			this.roots = Optional.empty();
			this.sampling = Optional.empty();
			this.extensions = new LinkedHashMap<>();
			this.experimental = new LinkedHashMap<>();
			this.unknownCapabilities = new LinkedHashMap<>();
		}

		@NonNull
		Builder capability(@NonNull McpCoreClientCapability capability) {
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

		@NonNull
		Builder elicitation(@NonNull McpJsonObject settings) {
			elicitation = Optional.of(requireNonNull(settings));
			return this;
		}

		@NonNull
		Builder roots(@NonNull McpJsonObject settings) {
			roots = Optional.of(requireNonNull(settings));
			return this;
		}

		@NonNull
		Builder sampling(@NonNull McpJsonObject settings) {
			sampling = Optional.of(requireNonNull(settings));
			return this;
		}

		@NonNull
		Builder extension(@NonNull String identifier, @NonNull McpJsonObject settings) {
			extensions.put(McpProtocolSupport.requireExtensionIdentifier(identifier),
					requireNonNull(settings));
			return this;
		}

		@NonNull
		Builder experimental(@NonNull String name, @NonNull McpJsonObject settings) {
			experimental.put(requireNonNull(name), requireNonNull(settings));
			return this;
		}

		@NonNull
		Builder unknown(@NonNull String name, @NonNull McpJsonValue settings) {
			unknownCapabilities.put(requireNonNull(name), requireNonNull(settings));
			return this;
		}

		@NonNull
		McpClientCapabilities build() {
			return new McpClientCapabilities(elicitation, roots, sampling,
					extensions, experimental, unknownCapabilities);
		}

		@NonNull
		private static McpJsonObject withObjectMember(
				@NonNull McpJsonObject object, @NonNull String memberName) {
			Map<@NonNull String, @NonNull McpJsonValue> values =
					new LinkedHashMap<>(object.members());
			values.putIfAbsent(memberName, McpJsonObject.empty());
			return new McpJsonObject(values);
		}
	}
}
