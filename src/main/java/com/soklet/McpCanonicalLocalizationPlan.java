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

import com.soklet.internal.mcp.protocol.McpJsonArray;
import com.soklet.internal.mcp.protocol.McpJsonObject;
import com.soklet.internal.mcp.protocol.McpJsonString;
import com.soklet.internal.mcp.protocol.McpJsonValue;
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
 * Immutable construction-time plan for canonical framework-owned MCP text.
 * This package-private model does not enable request localization; it records
 * the exact copy-on-write targets that a later rendering phase may use.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class McpCanonicalLocalizationPlan {
	@NonNull
	private final List<@NonNull McpLocalizableText> texts;
	@NonNull
	private final List<@NonNull EndpointPlan> endpoints;

	McpCanonicalLocalizationPlan(
			@NonNull List<@NonNull McpLocalizableText> texts,
			@NonNull List<@NonNull EndpointPlan> endpoints) {
		this.texts = List.copyOf(requireNonNull(texts));
		this.endpoints = List.copyOf(requireNonNull(endpoints));
	}

	@NonNull
	List<@NonNull McpLocalizableText> texts() {
		return this.texts;
	}

	@NonNull
	List<@NonNull EndpointPlan> endpoints() {
		return this.endpoints;
	}

	/**
	 * Framework response whose canonical publication object owns slots.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	enum ResponseKind {
		DISCOVERY,
		TOOLS_LIST,
		PROMPTS_LIST,
		RESOURCES_LIST,
		RESOURCE_TEMPLATES_LIST,
		SUBSCRIPTION_TERMINAL
	}

	/**
	 * One endpoint and its nonempty response-local plans.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	record EndpointPlan(@NonNull String endpointPath,
			@NonNull List<@NonNull ResponsePlan> responses) {
		EndpointPlan {
			requireNonNull(endpointPath);
			responses = List.copyOf(requireNonNull(responses));
		}

		@NonNull
		Optional<@NonNull ResponsePlan> response(@NonNull ResponseKind kind) {
			requireNonNull(kind);
			return responses.stream()
					.filter(response -> response.kind() == kind)
					.findFirst();
		}
	}

	/**
	 * One bounded framework response and its deterministic callback order.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	record ResponsePlan(@NonNull ResponseKind kind,
			@NonNull List<@NonNull Slot> slots) {
		ResponsePlan {
			requireNonNull(kind);
			slots = List.copyOf(requireNonNull(slots));
			if (slots.isEmpty())
				throw new IllegalArgumentException(
						"A canonical MCP localization response plan must not be empty.");
		}

		/**
		 * Resolves the plan against the exact untranslated projection that will be
		 * published for this request. Collection indexes are reconstructed from
		 * stable owner identity; canonical indexes are never reused after caller
		 * filtering. Slots whose owners are absent from the projection are omitted.
		 */
		@NonNull
		List<@NonNull Slot> resolveSlots(
				@NonNull McpJsonObject projectedDocument) {
			requireNonNull(projectedDocument);
			ProjectionIndex projectionIndex = ProjectionIndex.from(kind,
					projectedDocument);
			List<Slot> resolved = new ArrayList<>(slots.size());

			for (Slot slot : slots)
				slot.resolve(projectionIndex).ifPresent(resolved::add);

			return List.copyOf(resolved);
		}
	}

	/**
	 * One canonical string, its stable owner identity, the structural target
	 * used to reconstruct a filtered wire position, and its currently resolved
	 * RFC 6901 pointer. The pointer is a rendering target, not translation
	 * identity.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	record Slot(@NonNull McpLocalizableText text,
			@NonNull McpTextOwnerType ownerType,
			@NonNull String ownerId,
			@NonNull StructuralTarget structuralTarget,
			@NonNull String targetPointer) {
		Slot {
			requireNonNull(text);
			requireNonNull(ownerType);
			requireNonNull(ownerId);
			requireNonNull(structuralTarget);
			requireNonNull(targetPointer);
			if (!targetPointer.startsWith("/"))
				throw new IllegalArgumentException(
						"A canonical MCP localization target must be an RFC 6901 pointer.");
			McpTextCoordinate coordinate = text.getCoordinate();
			if (coordinate.getOwnerType() != ownerType
					|| !coordinate.getSubjectId().equals(ownerId))
				throw new IllegalArgumentException(
						"An MCP localization slot owner must match its text coordinate.");
			if (structuralTarget.kind() == StructuralTargetKind.PROMPT_ARGUMENT
					&& ownerType != McpTextOwnerType.PROMPT)
				throw new IllegalArgumentException(
						"Only prompt text may target a prompt argument.");
		}

		/** Test/rendering convenience for a fixed, already resolved target. */
		Slot(@NonNull McpLocalizableText text,
				@NonNull String targetPointer) {
			this(text, text.getCoordinate().getOwnerType(),
					text.getCoordinate().getSubjectId(),
					StructuralTarget.fixed(), targetPointer);
		}

		@NonNull
		static Slot fixed(@NonNull McpLocalizableText text,
				@NonNull String targetPointer) {
			return new Slot(text, text.getCoordinate().getOwnerType(),
					text.getCoordinate().getSubjectId(),
					StructuralTarget.fixed(), targetPointer);
		}

		@NonNull
		static Slot ownerMember(@NonNull McpLocalizableText text,
				@NonNull String memberPointer,
				@NonNull String canonicalTargetPointer) {
			return new Slot(text, text.getCoordinate().getOwnerType(),
					text.getCoordinate().getSubjectId(),
					StructuralTarget.ownerMember(memberPointer),
					canonicalTargetPointer);
		}

		@NonNull
		static Slot promptArgumentMember(@NonNull McpLocalizableText text,
				@NonNull String argumentName,
				@NonNull String memberPointer,
				@NonNull String canonicalTargetPointer) {
			return new Slot(text, text.getCoordinate().getOwnerType(),
					text.getCoordinate().getSubjectId(),
					StructuralTarget.promptArgumentMember(argumentName,
							memberPointer), canonicalTargetPointer);
		}

		@NonNull
		private Optional<@NonNull Slot> resolve(
				@NonNull ProjectionIndex projectionIndex) {
			if (structuralTarget.kind() == StructuralTargetKind.FIXED)
				return Optional.of(this);
			return structuralTarget.resolve(ownerType, ownerId, projectionIndex)
					.map(resolvedTarget -> resolvedTarget.equals(targetPointer)
							? this
							: new Slot(text, ownerType, ownerId, structuralTarget,
									resolvedTarget));
		}
	}

	/** How a slot is positioned beneath its stable owner on the wire. */
	enum StructuralTargetKind {
		FIXED,
		OWNER_MEMBER,
		PROMPT_ARGUMENT
	}

	/**
	 * Structural target metadata compiled by the extractor. A prompt argument
	 * retains its semantic name here even though the protocol publishes an array.
	 */
	@ThreadSafe
	record StructuralTarget(@NonNull StructuralTargetKind kind,
			@NonNull String memberPointer,
			@Nullable String promptArgumentName) {
		StructuralTarget {
			requireNonNull(kind);
			requireNonNull(memberPointer);
			if (kind == StructuralTargetKind.FIXED) {
				if (!memberPointer.isEmpty() || promptArgumentName != null)
					throw new IllegalArgumentException(
							"A fixed localization target has no relative structure.");
			} else {
				if (!memberPointer.startsWith("/"))
					throw new IllegalArgumentException(
							"A structural localization member must be an RFC 6901 pointer.");
				if ((kind == StructuralTargetKind.PROMPT_ARGUMENT)
						!= (promptArgumentName != null))
					throw new IllegalArgumentException(
							"Prompt argument identity must be present exactly for argument targets.");
			}
		}

		@NonNull
		static StructuralTarget fixed() {
			return new StructuralTarget(StructuralTargetKind.FIXED, "", null);
		}

		@NonNull
		static StructuralTarget ownerMember(
				@NonNull String memberPointer) {
			return new StructuralTarget(StructuralTargetKind.OWNER_MEMBER,
					memberPointer, null);
		}

		@NonNull
		static StructuralTarget promptArgumentMember(
				@NonNull String argumentName,
				@NonNull String memberPointer) {
			return new StructuralTarget(StructuralTargetKind.PROMPT_ARGUMENT,
					memberPointer, requireNonNull(argumentName));
		}

		@NonNull
		private Optional<@NonNull String> resolve(
				@NonNull McpTextOwnerType ownerType,
				@NonNull String ownerId,
				@NonNull ProjectionIndex projectionIndex) {
			if (kind == StructuralTargetKind.FIXED)
				throw new IllegalStateException(
						"A fixed localization target is already resolved.");

			Optional<Integer> ownerIndex = projectionIndex.ownerIndex(ownerId);
			if (ownerIndex.isEmpty())
				return Optional.empty();

			String ownerPointer = McpLocalizationSchemaWalker.childPointer("",
					projectionIndex.collectionMember(),
					Integer.toString(ownerIndex.orElseThrow()));
			if (kind == StructuralTargetKind.OWNER_MEMBER)
				return Optional.of(ownerPointer + memberPointer);

			if (ownerType != McpTextOwnerType.PROMPT)
				throw new IllegalStateException(
						"Only prompt owners may resolve prompt argument targets.");
			Optional<Integer> argumentIndex = projectionIndex.argumentIndex(ownerId,
					promptArgumentName);
			if (argumentIndex.isEmpty())
				return Optional.empty();
			String argumentPointer = McpLocalizationSchemaWalker.childPointer(
					ownerPointer, "arguments",
					Integer.toString(argumentIndex.orElseThrow()));
			return Optional.of(argumentPointer + memberPointer);
		}
	}

	/** Stable owner and prompt-argument positions in one wire projection. */
	private record ProjectionIndex(@NonNull String collectionMember,
			@NonNull Map<@NonNull String, @NonNull Integer> ownerIndexes,
			@NonNull Map<@NonNull String,
					@NonNull Map<@NonNull String, @NonNull Integer>> argumentIndexes) {
		ProjectionIndex {
			requireNonNull(collectionMember);
			ownerIndexes = Map.copyOf(requireNonNull(ownerIndexes));
			argumentIndexes = Map.copyOf(requireNonNull(argumentIndexes));
		}

		@NonNull
		static ProjectionIndex from(@NonNull ResponseKind kind,
				@NonNull McpJsonObject projectedDocument) {
			return switch (kind) {
				case DISCOVERY, SUBSCRIPTION_TERMINAL ->
						new ProjectionIndex("", Map.of(), Map.of());
				case TOOLS_LIST -> fromCollection(projectedDocument, "tools",
						"name", false);
				case PROMPTS_LIST -> fromCollection(projectedDocument, "prompts",
						"name", true);
				case RESOURCES_LIST -> fromCollection(projectedDocument,
						"resources", "uri", false);
				case RESOURCE_TEMPLATES_LIST -> fromCollection(projectedDocument,
						"resourceTemplates", "uriTemplate", false);
			};
		}

		@NonNull
		private static ProjectionIndex fromCollection(
				@NonNull McpJsonObject projectedDocument,
				@NonNull String collectionMember,
				@NonNull String identityMember,
				boolean indexPromptArguments) {
			McpJsonValue collectionValue = projectedDocument.members()
					.get(collectionMember);
			if (!(collectionValue instanceof McpJsonArray collection))
				throw malformedProjection(collectionMember);

			Map<String, Integer> ownerIndexes = new LinkedHashMap<>();
			Map<String, Map<String, Integer>> argumentIndexes =
					new LinkedHashMap<>();
			for (int ownerIndex = 0; ownerIndex < collection.values().size();
					++ownerIndex) {
				McpJsonValue ownerValue = collection.values().get(ownerIndex);
				if (!(ownerValue instanceof McpJsonObject owner))
					throw malformedProjection(collectionMember);
				String ownerId = stringMember(owner, identityMember,
						collectionMember);
				if (ownerIndexes.putIfAbsent(ownerId, ownerIndex) != null)
					throw duplicateIdentity(collectionMember);
				if (indexPromptArguments)
					argumentIndexes.put(ownerId,
							promptArgumentIndexes(owner, collectionMember));
			}

			return new ProjectionIndex(collectionMember, ownerIndexes,
					argumentIndexes);
		}

		@NonNull
		private static Map<@NonNull String, @NonNull Integer>
				promptArgumentIndexes(@NonNull McpJsonObject prompt,
				@NonNull String collectionMember) {
			McpJsonValue argumentsValue = prompt.members().get("arguments");
			if (argumentsValue == null)
				return Map.of();
			if (!(argumentsValue instanceof McpJsonArray arguments))
				throw malformedProjection(collectionMember);

			Map<String, Integer> indexes = new LinkedHashMap<>();
			for (int index = 0; index < arguments.values().size(); ++index) {
				McpJsonValue argumentValue = arguments.values().get(index);
				if (!(argumentValue instanceof McpJsonObject argument))
					throw malformedProjection(collectionMember);
				String argumentName = stringMember(argument, "name",
						collectionMember);
				if (indexes.putIfAbsent(argumentName, index) != null)
					throw duplicateIdentity(collectionMember);
			}
			return Map.copyOf(indexes);
		}

		@NonNull
		private static String stringMember(@NonNull McpJsonObject object,
				@NonNull String member, @NonNull String collectionMember) {
			McpJsonValue value = object.members().get(member);
			if (!(value instanceof McpJsonString string))
				throw malformedProjection(collectionMember);
			return string.value();
		}

		@NonNull
		private static IllegalStateException malformedProjection(
				@NonNull String collectionMember) {
			return new IllegalStateException(
					"Malformed MCP localization projection for "
							+ collectionMember + ".");
		}

		@NonNull
		private static IllegalStateException duplicateIdentity(
				@NonNull String collectionMember) {
			return new IllegalStateException(
					"Duplicate stable identity in MCP localization projection for "
							+ collectionMember + ".");
		}

		@NonNull
		Optional<@NonNull Integer> ownerIndex(@NonNull String ownerId) {
			return Optional.ofNullable(ownerIndexes.get(requireNonNull(ownerId)));
		}

		@NonNull
		Optional<@NonNull Integer> argumentIndex(@NonNull String ownerId,
				@Nullable String argumentName) {
			Map<String, Integer> indexes = argumentIndexes.get(
					requireNonNull(ownerId));
			return indexes == null || argumentName == null ? Optional.empty()
					: Optional.ofNullable(indexes.get(argumentName));
		}

	}
}
