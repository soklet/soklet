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

package com.soklet.internal.mcp.schema;

import com.soklet.internal.mcp.protocol.McpJsonBoolean;
import com.soklet.internal.mcp.protocol.McpJsonObject;
import com.soklet.internal.mcp.protocol.McpJsonValue;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Builds a closed dialect registry from explicitly designated custom
 * meta-schema documents.
 *
 * <p>The authenticated Draft 2020-12 bundle bootstraps structural graph
 * compilation, URI resolution, and reference closure. This stage extracts
 * vocabulary declarations only from document roots in the designated custom
 * meta-schema catalog, applies the mandatory Core-only default when a
 * declaration is absent, and rejects declarations in discovered subschemas.
 * It deliberately does not claim to validate a meta-schema against its own
 * meta-schema; full bounded meta-validation is a later evaluator gate.</p>
 */
final class McpSchemaDialectRegistryCompiler {
	private final McpSchemaCompilationLimits limits;
	private final McpSchemaDialectRegistry baseRegistry;
	private final McpSchemaUriResolver uriResolver;

	McpSchemaDialectRegistryCompiler(McpSchemaCompilationLimits limits) {
		this(limits, McpSchemaDialectRegistry.draft202012());
	}

	McpSchemaDialectRegistryCompiler(McpSchemaCompilationLimits limits,
			McpSchemaDialectRegistry baseRegistry) {
		this.limits = requireNonNull(limits);
		this.baseRegistry = requireNonNull(baseRegistry);
		this.uriResolver = new McpSchemaUriResolver();
	}

	McpSchemaDialectRegistry compile(
			List<McpSchemaDocument> customMetaSchemas) {
		requireNonNull(customMetaSchemas);
		int customDocumentCount = customMetaSchemas.size();
		if (customDocumentCount > limits.maximumDocumentCount())
			throw limit(McpSchemaCompilationException.Limit.DOCUMENT_COUNT,
					"Custom meta-schema document count exceeds its configured limit.",
					null, null);

		List<McpSchemaDocument> customDocuments = new ArrayList<>(
				customDocumentCount);
		for (int index = 0; index < customDocumentCount; ++index)
			customDocuments.add(requireNonNull(customMetaSchemas.get(index)));
		customDocuments = List.copyOf(customDocuments);

		List<McpSchemaDocument> builtInDocuments =
				McpSchemaDraft202012Bundle.documents();
		long catalogSize = (long) builtInDocuments.size()
				+ customDocuments.size();
		if (catalogSize > limits.maximumDocumentCount())
			throw limit(McpSchemaCompilationException.Limit.DOCUMENT_COUNT,
					"The closed meta-schema catalog exceeds its configured document limit.",
					null, null);

		List<McpSchemaDocument> catalog = new ArrayList<>((int) catalogSize);
		catalog.addAll(builtInDocuments);
		catalog.addAll(customDocuments);
		McpSchemaResourceGraph graph = new McpSchemaResourceGraphCompiler(
				limits, baseRegistry).compile(catalog);

		Set<McpSchemaNodeId> customDocumentRoots = customDocumentRoots(
				customDocuments, graph);
		Set<McpSchemaResourceId> customDocumentResources =
				customDocumentRoots.stream()
						.map(rootNodeId -> graph.node(rootNodeId).resourceId())
						.collect(java.util.stream.Collectors.toUnmodifiableSet());
		Map<URI, McpSchemaDialect> dialects = new LinkedHashMap<>(
				baseRegistry.dialects());
		Map<URI, URI> aliases = new LinkedHashMap<>(baseRegistry.aliases());
		int vocabularyDeclarationCount = 0;

		for (McpCompiledSchemaNode node : graph.nodes()) {
			McpSchemaResource resource = graph.resource(node.resourceId());
			if (!belongsToCustomDocument(resource, graph,
					customDocumentResources))
				continue;

			McpJsonValue declaration = node.schema() instanceof McpJsonObject object
					? object.members().get("$vocabulary") : null;
			boolean documentRoot = customDocumentRoots.contains(node.id());
			if (declaration == null && !documentRoot)
				continue;
			if (!documentRoot)
				throw failure(
						McpSchemaCompilationException.Kind.INVALID_KEYWORD_VALUE,
						"The $vocabulary keyword must not appear in a subschema.",
						node.location(), "$vocabulary");

			if (!resource.dialectUri().equals(
					McpSchemaDialectRegistry.DRAFT_2020_12_URI))
				throw failure(
						McpSchemaCompilationException.Kind.UNSUPPORTED_DIALECT,
						"A custom meta-schema must itself use the built-in Draft 2020-12 dialect in this compilation stage.",
						node.location(), "$schema");

			Map<URI, Boolean> vocabularyRequirements;
			if (declaration == null) {
				vocabularyRequirements = Map.of(
						McpSchemaVocabulary.CORE.uri(), true);
			} else {
				if (!(declaration instanceof McpJsonObject vocabularyObject))
					throw invalidVocabulary(node.location());
				if ((long) vocabularyDeclarationCount
						+ vocabularyObject.members().size()
						> limits.maximumVocabularyDeclarationCount())
					throw limit(
							McpSchemaCompilationException.Limit.VOCABULARY_COUNT,
							"Vocabulary declaration count exceeds its configured limit.",
							node.location(), "$vocabulary");
				vocabularyDeclarationCount += vocabularyObject.members().size();
				vocabularyRequirements = readVocabulary(vocabularyObject,
						node.location());
			}

			McpSchemaDialect dialect = new McpSchemaDialect(
					resource.canonicalUri(), vocabularyRequirements);
			registerDialect(dialect, resource.identifiers(), dialects, aliases,
					node.location());
		}

		return new McpSchemaDialectRegistry(dialects, aliases);
	}

	private void registerDialect(McpSchemaDialect dialect,
			Set<URI> identifiers, Map<URI, McpSchemaDialect> dialects,
			Map<URI, URI> aliases, McpSchemaLocation location) {
		if (dialects.containsKey(dialect.uri())
				|| aliases.containsKey(dialect.uri()))
			throw duplicateDialect(location);
		dialects.put(dialect.uri(), dialect);

		for (URI identifier : identifiers) {
			if (identifier.equals(dialect.uri()))
				continue;
			if (dialects.containsKey(identifier))
				throw duplicateDialect(location);
			URI existing = aliases.putIfAbsent(identifier, dialect.uri());
			if (existing != null && !existing.equals(dialect.uri()))
				throw duplicateDialect(location);
		}
	}

	private McpSchemaCompilationException duplicateDialect(
			McpSchemaLocation location) {
		return failure(
				McpSchemaCompilationException.Kind.DUPLICATE_RESOURCE_IDENTIFIER,
				"A custom dialect identifier is already registered.",
				location, "$id");
	}

	private Set<McpSchemaNodeId> customDocumentRoots(
			List<McpSchemaDocument> customDocuments,
			McpSchemaResourceGraph graph) {
		Set<McpSchemaNodeId> rootNodeIds = new LinkedHashSet<>();
		for (McpSchemaDocument document : customDocuments) {
			URI retrievalUri = canonicalRetrievalUri(document.retrievalUri());
			McpSchemaNodeId rootNodeId = graph.documentRoots().get(retrievalUri);
			if (rootNodeId == null)
				throw new IllegalStateException(
						"A compiled custom meta-schema document root is missing.");
			rootNodeIds.add(rootNodeId);
		}
		return Set.copyOf(rootNodeIds);
	}

	private boolean belongsToCustomDocument(McpSchemaResource resource,
			McpSchemaResourceGraph graph,
			Set<McpSchemaResourceId> customDocumentResources) {
		McpSchemaResource current = resource;
		while (true) {
			if (customDocumentResources.contains(current.id()))
				return true;
			if (current.enclosingResourceId().isEmpty())
				return false;
			current = graph.resource(current.enclosingResourceId().get());
		}
	}

	private Map<URI, Boolean> readVocabulary(McpJsonObject declaration,
			McpSchemaLocation location) {
		Map<URI, Boolean> requirements = new LinkedHashMap<>();
		List<Map.Entry<String, McpJsonValue>> entries = declaration.members()
				.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList();

		for (Map.Entry<String, McpJsonValue> entry : entries) {
			URI vocabularyUri = readVocabularyUri(entry.getKey(), location);
			if (!(entry.getValue() instanceof McpJsonBoolean requirement))
				throw invalidVocabulary(location);
			boolean required = requirement == McpJsonBoolean.TRUE;
			if (required && McpSchemaVocabulary.fromUri(vocabularyUri).isEmpty())
				throw failure(
						McpSchemaCompilationException.Kind.UNSUPPORTED_VOCABULARY,
						"A required JSON Schema vocabulary is not supported.",
						location, "$vocabulary");
			requirements.put(vocabularyUri, required);
		}

		if (!Boolean.TRUE.equals(requirements.get(
				McpSchemaVocabulary.CORE.uri())))
			throw invalidVocabulary(location);
		return requirements;
	}

	private URI readVocabularyUri(String lexicalUri,
			McpSchemaLocation location) {
		if (lexicalUri.length() > limits.maximumUriLengthInCharacters())
			throw limit(McpSchemaCompilationException.Limit.URI_LENGTH,
					"A vocabulary URI exceeds its configured character limit.",
					location, "$vocabulary");

		try {
			URI declaredUri = URI.create(lexicalUri);
			if (!declaredUri.isAbsolute())
				throw new IllegalArgumentException(
						"A vocabulary URI must be absolute.");
			URI normalizedUri = uriResolver.canonicalAbsolute(declaredUri);
			if (!normalizedUri.toASCIIString().equals(lexicalUri))
				throw new IllegalArgumentException(
						"A vocabulary URI must be normalized.");
			if (normalizedUri.toASCIIString().length()
					> limits.maximumUriLengthInCharacters())
				throw limit(McpSchemaCompilationException.Limit.URI_LENGTH,
						"A vocabulary URI exceeds its configured character limit.",
						location, "$vocabulary");
			return normalizedUri;
		} catch (McpSchemaCompilationException exception) {
			throw exception;
		} catch (IllegalArgumentException exception) {
			throw invalidVocabulary(location);
		}
	}

	private URI canonicalRetrievalUri(URI retrievalUri) {
		try {
			return uriResolver.canonicalAbsolute(requireNonNull(retrievalUri));
		} catch (IllegalArgumentException exception) {
			throw failure(
					McpSchemaCompilationException.Kind.INVALID_RETRIEVAL_URI,
					"A custom meta-schema retrieval URI is invalid.",
					McpSchemaLocation.root(retrievalUri), null);
		}
	}

	private McpSchemaCompilationException invalidVocabulary(
			McpSchemaLocation location) {
		return failure(McpSchemaCompilationException.Kind.INVALID_KEYWORD_VALUE,
				"The $vocabulary keyword must contain normalized absolute URI keys, boolean values, and required Core support.",
				location, "$vocabulary");
	}

	private McpSchemaCompilationException failure(
			McpSchemaCompilationException.Kind kind, String message,
			McpSchemaLocation location, String keyword) {
		return new McpSchemaCompilationException(kind, message, location, keyword);
	}

	private McpSchemaCompilationException limit(
			McpSchemaCompilationException.Limit limit, String message,
			McpSchemaLocation location, String keyword) {
		return new McpSchemaCompilationException(limit, message, location, keyword);
	}
}
