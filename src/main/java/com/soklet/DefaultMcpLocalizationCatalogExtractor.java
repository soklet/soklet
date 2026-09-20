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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static java.util.Objects.requireNonNull;

/**
 * Extracts canonical localizable fields from the final immutable endpoint
 * registry and builds response-local copy-on-write slot plans. Nothing in this
 * class creates request context, invokes application code, or changes wire
 * output.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class DefaultMcpLocalizationCatalogExtractor {
	private static final int MAXIMUM_SUPPORTED_CALLBACK_COUNT = 100_000;
	@NonNull
	private static final String SERVER_INFORMATION_METADATA_POINTER =
			"/_meta/io.modelcontextprotocol~1serverInfo";

	private DefaultMcpLocalizationCatalogExtractor() {
	}

	/**
	 * Extracts every eligible configured source field exactly once in external-
	 * key order. Catalog extraction itself is not constrained by a server's
	 * response-local callback budget.
	 */
	@NonNull
	static List<@NonNull McpLocalizableText> extract(
			@NonNull McpEndpointRegistry endpointRegistry) {
		return build(endpointRegistry, Integer.MAX_VALUE,
				McpTextCoordinate::toExternalKey, false).texts();
	}

	/** Builds plans and validates every response against the configured budget. */
	@NonNull
	static McpCanonicalLocalizationPlan plan(
			@NonNull McpEndpointRegistry endpointRegistry,
			int maximumLocalizableTextCountPerResponse) {
		return plan(endpointRegistry, maximumLocalizableTextCountPerResponse,
				false);
	}

	/**
	 * Builds plans while optionally deferring the tool/prompt response bound to
	 * the exact caller-visible projection. Other response kinds remain static
	 * and are always validated during construction.
	 */
	@NonNull
	static McpCanonicalLocalizationPlan plan(
			@NonNull McpEndpointRegistry endpointRegistry,
			int maximumLocalizableTextCountPerResponse,
			boolean deferCallerAwareCatalogResponseBounds) {
		if (maximumLocalizableTextCountPerResponse < 1
				|| maximumLocalizableTextCountPerResponse
				> MAXIMUM_SUPPORTED_CALLBACK_COUNT)
			throw new IllegalArgumentException(
					"Maximum localizable text count per response must be between 1 and 100000.");
		return build(endpointRegistry, maximumLocalizableTextCountPerResponse,
				McpTextCoordinate::toExternalKey,
				deferCallerAwareCatalogResponseBounds);
	}

	/** Collision-test seam; production always uses the coordinate's v1 key. */
	@NonNull
	static List<@NonNull McpLocalizableText> extract(
			@NonNull McpEndpointRegistry endpointRegistry,
			@NonNull ExternalKeyFactory externalKeyFactory) {
		return build(endpointRegistry, Integer.MAX_VALUE,
				externalKeyFactory, false).texts();
	}

	@NonNull
	private static McpCanonicalLocalizationPlan build(
			@NonNull McpEndpointRegistry endpointRegistry,
			int maximumLocalizableTextCountPerResponse,
			@NonNull ExternalKeyFactory externalKeyFactory,
			boolean deferCallerAwareCatalogResponseBounds) {
		requireNonNull(endpointRegistry);
		CatalogAccumulator catalog = new CatalogAccumulator(externalKeyFactory);
		List<McpCanonicalLocalizationPlan.EndpointPlan> endpointPlans =
				new ArrayList<>();

		for (McpEndpoint endpoint : endpointRegistry.getEndpoints()) {
			List<McpCanonicalLocalizationPlan.ResponsePlan> responses =
					new ArrayList<>();
			List<McpCanonicalLocalizationPlan.Slot> discovery =
					discoverySlots(endpoint, catalog);
			addResponse(responses,
					McpCanonicalLocalizationPlan.ResponseKind.DISCOVERY,
					discovery, maximumLocalizableTextCountPerResponse);

			addResponse(responses,
					McpCanonicalLocalizationPlan.ResponseKind.TOOLS_LIST,
					toolSlots(endpoint, catalog),
					maximumLocalizableTextCountPerResponse,
					deferCallerAwareCatalogResponseBounds
							|| endpoint.getToolRegistrations().stream().anyMatch(tool ->
									McpAppMetadataSupport.effectiveToolMetadata(
											tool.getMetadata(), tool.getAppToolMetadata()
													.orElse(null)).isPresent()));
			addResponse(responses,
					McpCanonicalLocalizationPlan.ResponseKind.PROMPTS_LIST,
					promptSlots(endpoint, catalog),
					maximumLocalizableTextCountPerResponse,
					deferCallerAwareCatalogResponseBounds);
			addResponse(responses,
					McpCanonicalLocalizationPlan.ResponseKind.RESOURCES_LIST,
					exactResourceSlots(endpoint, catalog),
					maximumLocalizableTextCountPerResponse);
			addResponse(responses,
					McpCanonicalLocalizationPlan.ResponseKind.RESOURCE_TEMPLATES_LIST,
					resourceTemplateSlots(endpoint, catalog),
					maximumLocalizableTextCountPerResponse);

			if (endpoint.getSubscriptionConfig().isPresent()) {
				List<McpCanonicalLocalizationPlan.Slot> terminal =
						serverInformationSlots(endpoint, catalog);
				addResponse(responses,
						McpCanonicalLocalizationPlan.ResponseKind.SUBSCRIPTION_TERMINAL,
						terminal, maximumLocalizableTextCountPerResponse);
			}

			endpointPlans.add(new McpCanonicalLocalizationPlan.EndpointPlan(
					endpoint.getPath(), responses));
		}

		return new McpCanonicalLocalizationPlan(catalog.texts(), endpointPlans);
	}

	private static void addResponse(
			@NonNull List<McpCanonicalLocalizationPlan.ResponsePlan>
					responses,
			McpCanonicalLocalizationPlan.ResponseKind kind,
			@NonNull List<McpCanonicalLocalizationPlan.Slot> slots,
			int maximumLocalizableTextCountPerResponse) {
		addResponse(responses, kind, slots,
				maximumLocalizableTextCountPerResponse, false);
	}

	private static void addResponse(
			@NonNull List<McpCanonicalLocalizationPlan.ResponsePlan>
					responses,
			McpCanonicalLocalizationPlan.ResponseKind kind,
			@NonNull List<McpCanonicalLocalizationPlan.Slot> slots,
			int maximumLocalizableTextCountPerResponse,
			boolean deferResponseBound) {
		if (slots.isEmpty())
			return;
		if (!deferResponseBound
				&& slots.size() > maximumLocalizableTextCountPerResponse)
			throw new IllegalStateException(
					"A canonical MCP localization response plan exceeds the configured "
							+ "callback limit (kind=" + kind + ", count="
							+ slots.size() + ", limit="
							+ maximumLocalizableTextCountPerResponse + ").");
		responses.add(new McpCanonicalLocalizationPlan.ResponsePlan(kind, slots));
	}

	@NonNull
	private static List<McpCanonicalLocalizationPlan.Slot>
			discoverySlots(@NonNull McpEndpoint endpoint,
			@NonNull CatalogAccumulator catalog) {
		List<McpCanonicalLocalizationPlan.Slot> slots =
				new ArrayList<>(serverInformationSlots(endpoint, catalog));
		endpoint.getInstructions().ifPresent(text -> addFixedIfNonblank(slots,
				catalog, endpoint.getPath(), McpTextOwnerType.ENDPOINT,
				endpoint.getPath(), "/instructions", "/instructions", text));
		return List.copyOf(slots);
	}

	@NonNull
	private static List<McpCanonicalLocalizationPlan.Slot>
			serverInformationSlots(@NonNull McpEndpoint endpoint,
			@NonNull CatalogAccumulator catalog) {
		if (!endpoint.isServerInfoIncluded())
			return List.of();
		McpImplementation information = endpoint.getServerInfo();
		List<McpCanonicalLocalizationPlan.Slot> slots = new ArrayList<>();
		information.getTitle().ifPresent(text -> addFixedIfNonblank(slots, catalog,
				endpoint.getPath(), McpTextOwnerType.SERVER_INFORMATION,
				information.getName(), "/title",
				SERVER_INFORMATION_METADATA_POINTER + "/title", text));
		information.getDescription().ifPresent(text -> addFixedIfNonblank(slots,
				catalog, endpoint.getPath(),
				McpTextOwnerType.SERVER_INFORMATION,
				information.getName(), "/description",
				SERVER_INFORMATION_METADATA_POINTER + "/description", text));
		return List.copyOf(slots);
	}

	@NonNull
	private static List<McpCanonicalLocalizationPlan.Slot> toolSlots(
			@NonNull McpEndpoint endpoint,
			@NonNull CatalogAccumulator catalog) {
		List<McpCanonicalLocalizationPlan.Slot> slots = new ArrayList<>();
		for (int index = 0; index < endpoint.getToolRegistrations().size(); ++index) {
			McpToolRegistration<?> tool = endpoint.getToolRegistrations().get(index);
			String target = McpLocalizationSchemaWalker.childPointer(
					"", "tools", Integer.toString(index));
			tool.getTitle().ifPresent(text -> addOwnerIfNonblank(slots, catalog,
					endpoint.getPath(), McpTextOwnerType.TOOL,
					tool.getName(), "/title", target + "/title", text));
			tool.getDescription().ifPresent(text -> addOwnerIfNonblank(slots, catalog,
					endpoint.getPath(), McpTextOwnerType.TOOL,
					tool.getName(), "/description", target + "/description", text));
			tool.getToolAnnotations().flatMap(McpToolAnnotations::getTitle)
					.ifPresent(text -> addOwnerIfNonblank(slots, catalog,
							endpoint.getPath(), McpTextOwnerType.TOOL,
							tool.getName(), "/annotations/title",
							target + "/annotations/title", text));
			addSchemaSlots(slots, catalog, endpoint.getPath(), tool.getName(),
					"/inputSchema", target + "/inputSchema",
					tool.getInputSchema().getDocument());
			tool.getOutputSchema().ifPresent(schema -> addSchemaSlots(slots,
					catalog, endpoint.getPath(), tool.getName(), "/outputSchema",
					target + "/outputSchema", schema.getDocument()));
		}
		return List.copyOf(slots);
	}

	private static void addSchemaSlots(
			@NonNull List<McpCanonicalLocalizationPlan.Slot> slots,
			@NonNull CatalogAccumulator catalog, @NonNull String endpointPath,
			@NonNull String toolName, @NonNull String coordinatePrefix,
			@NonNull String targetPrefix, @NonNull McpJsonObject document) {
		for (McpLocalizationSchemaWalker.SchemaText schemaText
				: McpLocalizationSchemaWalker.walk(document)) {
			addOwnerIfNonblank(slots, catalog, endpointPath,
					McpTextOwnerType.TOOL, toolName,
					coordinatePrefix + schemaText.pointer(),
					targetPrefix + schemaText.pointer(), schemaText.text());
		}
	}

	@NonNull
	private static List<McpCanonicalLocalizationPlan.Slot> promptSlots(
			@NonNull McpEndpoint endpoint,
			@NonNull CatalogAccumulator catalog) {
		List<McpCanonicalLocalizationPlan.Slot> slots = new ArrayList<>();
		for (int promptIndex = 0;
				promptIndex < endpoint.getPromptRegistrations().size(); ++promptIndex) {
			McpPromptRegistration prompt = endpoint.getPromptRegistrations().get(promptIndex);
			String target = McpLocalizationSchemaWalker.childPointer(
					"", "prompts", Integer.toString(promptIndex));
			prompt.getTitle().ifPresent(text -> addOwnerIfNonblank(slots, catalog,
					endpoint.getPath(), McpTextOwnerType.PROMPT,
					prompt.getName(), "/title", target + "/title", text));
			prompt.getDescription().ifPresent(text -> addOwnerIfNonblank(slots, catalog,
					endpoint.getPath(), McpTextOwnerType.PROMPT,
					prompt.getName(), "/description", target + "/description", text));
			for (int argumentIndex = 0;
					argumentIndex < prompt.getArguments().size(); ++argumentIndex) {
				McpPromptArgumentDeclaration argument =
						prompt.getArguments().get(argumentIndex);
				String member = McpLocalizationSchemaWalker.childPointer(
						"", "arguments", argument.getName());
				String argumentTarget = McpLocalizationSchemaWalker.childPointer(
						target, "arguments", Integer.toString(argumentIndex));
				argument.getTitle().ifPresent(text -> addPromptArgumentIfNonblank(slots,
						catalog, endpoint.getPath(), McpTextOwnerType.PROMPT,
						prompt.getName(), member + "/title", argument.getName(),
						"/title", argumentTarget + "/title", text));
				argument.getDescription().ifPresent(text -> addPromptArgumentIfNonblank(slots,
						catalog, endpoint.getPath(), McpTextOwnerType.PROMPT,
						prompt.getName(), member + "/description", argument.getName(),
						"/description", argumentTarget + "/description", text));
			}
		}
		return List.copyOf(slots);
	}

	@NonNull
	private static List<McpCanonicalLocalizationPlan.Slot>
			exactResourceSlots(@NonNull McpEndpoint endpoint,
			@NonNull CatalogAccumulator catalog) {
		// A custom resources/list handler owns every descriptor in its page. Its
		// registered exact-resource helper snapshot intentionally stays canonical.
		if (endpoint.getResourceListHandler().isPresent())
			return List.of();
		List<McpCanonicalLocalizationPlan.Slot> slots = new ArrayList<>();
		int exactIndex = 0;
		for (McpResourceRegistration resource : endpoint.getResourceRegistrations()) {
			if (resource.getAddressType() != McpResourceAddressType.URI)
				continue;
			String subject = resource.getUri().orElseThrow().toString();
			String target = McpLocalizationSchemaWalker.childPointer(
					"", "resources", Integer.toString(exactIndex++));
			resource.getTitle().ifPresent(text -> addOwnerIfNonblank(slots, catalog,
					endpoint.getPath(), McpTextOwnerType.RESOURCE, subject,
					"/title", target + "/title", text));
			resource.getDescription().ifPresent(text -> addOwnerIfNonblank(slots,
					catalog, endpoint.getPath(), McpTextOwnerType.RESOURCE,
					subject, "/description", target + "/description", text));
		}
		return List.copyOf(slots);
	}

	@NonNull
	private static List<McpCanonicalLocalizationPlan.Slot>
			resourceTemplateSlots(@NonNull McpEndpoint endpoint,
			@NonNull CatalogAccumulator catalog) {
		List<McpCanonicalLocalizationPlan.Slot> slots = new ArrayList<>();
		int templateIndex = 0;
		for (McpResourceRegistration resource : endpoint.getResourceRegistrations()) {
			if (resource.getAddressType()
					!= McpResourceAddressType.URI_TEMPLATE)
				continue;
			String subject = resource.getUriTemplate().orElseThrow();
			String target = McpLocalizationSchemaWalker.childPointer(
					"", "resourceTemplates", Integer.toString(templateIndex++));
			resource.getTitle().ifPresent(text -> addOwnerIfNonblank(slots, catalog,
					endpoint.getPath(), McpTextOwnerType.RESOURCE_TEMPLATE,
					subject, "/title", target + "/title", text));
			resource.getDescription().ifPresent(text -> addOwnerIfNonblank(slots,
					catalog, endpoint.getPath(),
					McpTextOwnerType.RESOURCE_TEMPLATE, subject,
					"/description", target + "/description", text));
		}
		return List.copyOf(slots);
	}

	private static void addFixedIfNonblank(
			@NonNull List<McpCanonicalLocalizationPlan.Slot> slots,
			@NonNull CatalogAccumulator catalog, @NonNull String endpointPath,
			@NonNull McpTextOwnerType ownerType,
			@NonNull String subjectId, @NonNull String memberPath,
			@NonNull String targetPointer, @NonNull String defaultText) {
		if (defaultText.isBlank())
			return;
		McpTextCoordinate coordinate = new McpTextCoordinate(endpointPath,
				ownerType, subjectId, memberPath);
		McpLocalizableText text = catalog.register(coordinate, defaultText);
		slots.add(McpCanonicalLocalizationPlan.Slot.fixed(text, targetPointer));
	}

	private static void addOwnerIfNonblank(
			@NonNull List<McpCanonicalLocalizationPlan.Slot> slots,
			@NonNull CatalogAccumulator catalog, @NonNull String endpointPath,
			@NonNull McpTextOwnerType ownerType,
			@NonNull String subjectId, @NonNull String memberPath,
			@NonNull String targetPointer, @NonNull String defaultText) {
		if (defaultText.isBlank())
			return;
		McpTextCoordinate coordinate = new McpTextCoordinate(endpointPath,
				ownerType, subjectId, memberPath);
		McpLocalizableText text = catalog.register(coordinate, defaultText);
		slots.add(McpCanonicalLocalizationPlan.Slot.ownerMember(text,
				memberPath, targetPointer));
	}

	private static void addPromptArgumentIfNonblank(
			@NonNull List<McpCanonicalLocalizationPlan.Slot> slots,
			@NonNull CatalogAccumulator catalog, @NonNull String endpointPath,
			@NonNull McpTextOwnerType ownerType,
			@NonNull String subjectId, @NonNull String memberPath,
			@NonNull String argumentName, @NonNull String argumentMemberPointer,
			@NonNull String targetPointer,
			@NonNull String defaultText) {
		if (defaultText.isBlank())
			return;
		McpTextCoordinate coordinate = new McpTextCoordinate(endpointPath,
				ownerType, subjectId, memberPath);
		McpLocalizableText text = catalog.register(coordinate, defaultText);
		slots.add(McpCanonicalLocalizationPlan.Slot.promptArgumentMember(text,
				argumentName, argumentMemberPointer, targetPointer));
	}

	@ThreadSafe
	@FunctionalInterface
	interface ExternalKeyFactory {
		@NonNull String externalKey(@NonNull McpTextCoordinate coordinate);
	}

	private static final class CatalogAccumulator {
		@NonNull
		private final ExternalKeyFactory externalKeyFactory;
		@NonNull
		private final Map<@NonNull String, @NonNull McpLocalizableText> byKey;

		private CatalogAccumulator(
				@NonNull ExternalKeyFactory externalKeyFactory) {
			this.externalKeyFactory = requireNonNull(externalKeyFactory);
			this.byKey = new TreeMap<>();
		}

		@NonNull
		private McpLocalizableText register(
				@NonNull McpTextCoordinate coordinate,
				@NonNull String defaultText) {
			String externalKey = requireNonNull(
					this.externalKeyFactory.externalKey(coordinate));
			if (externalKey.isBlank())
				throw new IllegalStateException(
						"An MCP localization external key must not be blank.");
			McpLocalizableText previous = this.byKey.get(externalKey);
			if (previous != null) {
				if (!previous.getCoordinate().equals(coordinate))
					throw new IllegalStateException(
							"Unequal MCP text coordinates produced the same external key.");
				if (!previous.getDefaultText().equals(defaultText))
					throw new IllegalStateException(
							"One MCP text coordinate has conflicting canonical text.");
				return previous;
			}
			McpLocalizableText text = new McpLocalizableText(coordinate, defaultText);
			this.byKey.put(externalKey, text);
			return text;
		}

		@NonNull
		private List<@NonNull McpLocalizableText> texts() {
			return List.copyOf(this.byKey.values());
		}
	}
}
