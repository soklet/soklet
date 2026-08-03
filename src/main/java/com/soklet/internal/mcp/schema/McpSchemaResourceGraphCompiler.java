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

import com.soklet.internal.mcp.protocol.McpJsonArray;
import com.soklet.internal.mcp.protocol.McpJsonBoolean;
import com.soklet.internal.mcp.protocol.McpJsonObject;
import com.soklet.internal.mcp.protocol.McpJsonString;
import com.soklet.internal.mcp.protocol.McpJsonValue;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Builds a closed, offline Draft 2020-12 schema resource graph.
 *
 * <p>This compiler discovers schema locations and resolves core reference
 * edges. It deliberately does not claim assertion, applicator, or meta-schema
 * validation; those are later bounded-evaluator slices. JSON Pointer targets
 * are indexed relative to each resource's canonical URI. The intentionally
 * unsupported, fragile form that crosses an embedded-resource boundary using
 * an enclosing resource's base URI fails closed.</p>
 */
final class McpSchemaResourceGraphCompiler {
	static final URI DRAFT_2020_12_DIALECT =
			McpSchemaDialectRegistry.DRAFT_2020_12_URI;

	private static final List<SchemaKeyword> SCHEMA_KEYWORDS = List.of(
			new SchemaKeyword("$defs", ContainerKind.MAP,
					McpSchemaVocabulary.CORE),
			new SchemaKeyword("additionalProperties", ContainerKind.SINGLE,
					McpSchemaVocabulary.APPLICATOR),
			new SchemaKeyword("allOf", ContainerKind.ARRAY,
					McpSchemaVocabulary.APPLICATOR),
			new SchemaKeyword("anyOf", ContainerKind.ARRAY,
					McpSchemaVocabulary.APPLICATOR),
			new SchemaKeyword("contains", ContainerKind.SINGLE,
					McpSchemaVocabulary.APPLICATOR),
			new SchemaKeyword("contentSchema", ContainerKind.SINGLE,
					McpSchemaVocabulary.CONTENT),
			new SchemaKeyword("dependentSchemas", ContainerKind.MAP,
					McpSchemaVocabulary.APPLICATOR),
			new SchemaKeyword("else", ContainerKind.SINGLE,
					McpSchemaVocabulary.APPLICATOR),
			new SchemaKeyword("if", ContainerKind.SINGLE,
					McpSchemaVocabulary.APPLICATOR),
			new SchemaKeyword("items", ContainerKind.SINGLE,
					McpSchemaVocabulary.APPLICATOR),
			new SchemaKeyword("not", ContainerKind.SINGLE,
					McpSchemaVocabulary.APPLICATOR),
			new SchemaKeyword("oneOf", ContainerKind.ARRAY,
					McpSchemaVocabulary.APPLICATOR),
			new SchemaKeyword("patternProperties", ContainerKind.MAP,
					McpSchemaVocabulary.APPLICATOR),
			new SchemaKeyword("prefixItems", ContainerKind.ARRAY,
					McpSchemaVocabulary.APPLICATOR),
			new SchemaKeyword("properties", ContainerKind.MAP,
					McpSchemaVocabulary.APPLICATOR),
			new SchemaKeyword("propertyNames", ContainerKind.SINGLE,
					McpSchemaVocabulary.APPLICATOR),
			new SchemaKeyword("then", ContainerKind.SINGLE,
					McpSchemaVocabulary.APPLICATOR),
			new SchemaKeyword("unevaluatedItems", ContainerKind.SINGLE,
					McpSchemaVocabulary.UNEVALUATED),
			new SchemaKeyword("unevaluatedProperties", ContainerKind.SINGLE,
					McpSchemaVocabulary.UNEVALUATED));

	private final McpSchemaCompilationLimits limits;
	private final McpSchemaDialectRegistry dialectRegistry;

	McpSchemaResourceGraphCompiler(McpSchemaCompilationLimits limits) {
		this(limits, McpSchemaDialectRegistry.draft202012());
	}

	McpSchemaResourceGraphCompiler(McpSchemaCompilationLimits limits,
			McpSchemaDialectRegistry dialectRegistry) {
		this.limits = requireNonNull(limits);
		this.dialectRegistry = requireNonNull(dialectRegistry);
	}

	McpSchemaResourceGraph compile(List<McpSchemaDocument> documents) {
		requireNonNull(documents);
		int documentCount = documents.size();
		if (documentCount > limits.maximumDocumentCount())
			throw new McpSchemaCompilationException(
					McpSchemaCompilationException.Limit.DOCUMENT_COUNT,
					"Schema document count exceeds its configured limit.", null, null);

		List<McpSchemaDocument> boundedCopy = new ArrayList<>(documentCount);
		for (int index = 0; index < documentCount; ++index)
			boundedCopy.add(requireNonNull(documents.get(index)));
		return new Compilation(List.copyOf(boundedCopy)).compile();
	}

	private final class Compilation {
		private final List<McpSchemaDocument> documents;
		private final McpSchemaUriResolver uriResolver;
		private final List<NodeBuilder> nodes;
		private final List<ResourceBuilder> resources;
		private final Map<URI, ResourceBuilder> resourcesByIdentifier;
		private final Map<URI, McpSchemaNodeId> documentRoots;
		private final List<UnresolvedReference> unresolvedReferences;
		private int keywordCount;
		private int anchorCount;
		private int referenceCount;

		private Compilation(List<McpSchemaDocument> documents) {
			this.documents = documents;
			this.uriResolver = new McpSchemaUriResolver();
			this.nodes = new ArrayList<>();
			this.resources = new ArrayList<>();
			this.resourcesByIdentifier = new LinkedHashMap<>();
			this.documentRoots = new LinkedHashMap<>();
			this.unresolvedReferences = new ArrayList<>();
		}

		private McpSchemaResourceGraph compile() {
			List<PreparedDocument> preparedDocuments = prepareDocuments();

			for (PreparedDocument document : preparedDocuments) {
				McpSchemaNodeId rootNodeId = discover(document.rootSchema(),
						McpSchemaLocation.root(document.retrievalUri()),
						document.retrievalUri(),
						dialectRegistry.defaultDialect().uri(),
						null, List.of(), 1, true, document.retrievalUri());
			documentRoots.put(document.retrievalUri(), rootNodeId);
			}

			resolveReferences();
			return freeze();
		}

		private List<PreparedDocument> prepareDocuments() {
			if (documents.size() > limits.maximumDocumentCount())
				throw limit(McpSchemaCompilationException.Limit.DOCUMENT_COUNT,
						"Schema document count exceeds its configured limit.", null, null);

			List<PreparedDocument> prepared = new ArrayList<>(documents.size());
			for (McpSchemaDocument document : documents) {
				URI retrievalUri = validateRetrievalUri(document.retrievalUri());
				if (!isSchema(document.rootSchema()))
					throw failure(McpSchemaCompilationException.Kind.INVALID_SCHEMA,
							"A schema document root must be an object or boolean.",
							McpSchemaLocation.root(retrievalUri), null);
				prepared.add(new PreparedDocument(retrievalUri, document.rootSchema()));
			}

			prepared.sort(Comparator.comparing(
					document -> document.retrievalUri().toASCIIString()));
			Set<URI> seenRetrievalUris = new LinkedHashSet<>();
			for (PreparedDocument document : prepared) {
				if (!seenRetrievalUris.add(document.retrievalUri()))
					throw failure(
							McpSchemaCompilationException.Kind.DUPLICATE_RESOURCE_IDENTIFIER,
							"Schema retrieval URIs must be unique.",
							McpSchemaLocation.root(document.retrievalUri()), null);
			}

			return List.copyOf(prepared);
		}

		private URI validateRetrievalUri(URI retrievalUri) {
			requireNonNull(retrievalUri);
			McpSchemaLocation location = McpSchemaLocation.root(retrievalUri);
			checkUriLength(retrievalUri.toString(), location, null);

			if (!retrievalUri.isAbsolute() || retrievalUri.getRawFragment() != null)
				throw failure(McpSchemaCompilationException.Kind.INVALID_RETRIEVAL_URI,
						"A retrieval URI must be absolute and fragmentless.",
						location, null);

			try {
				URI normalized = uriResolver.canonicalAbsolute(retrievalUri);
				checkUriLength(normalized.toASCIIString(), location, null);
				return normalized;
			} catch (IllegalArgumentException exception) {
				throw failure(McpSchemaCompilationException.Kind.INVALID_RETRIEVAL_URI,
						"A retrieval URI is not a valid absolute URI.", location, null);
			}
		}

		private McpSchemaNodeId discover(McpJsonValue schema,
				McpSchemaLocation location, URI inheritedBaseUri,
				URI inheritedDialectUri, ResourceBuilder enclosingResource,
				List<String> candidateResourcePointer, int depth,
				boolean documentRoot, URI retrievalAlias) {
			if (!isSchema(schema))
				throw failure(McpSchemaCompilationException.Kind.INVALID_SCHEMA,
						"A subschema must be an object or boolean.", location, null);
			checkDepth(depth, location);
			checkPointerSegments(location.pointerSegments(), location, null);

			McpJsonObject objectSchema = schema instanceof McpJsonObject object
					? object : null;
			if (objectSchema != null)
				incrementKeywords(objectSchema.members().size(), location);

			Optional<URI> declaredIdentifier = objectSchema == null
					? Optional.empty() : readIdentifier(objectSchema, inheritedBaseUri,
							location);
			URI localBaseUri = declaredIdentifier.orElse(inheritedBaseUri);
			boolean startsResource = documentRoot || declaredIdentifier.isPresent();
			URI dialectUri = readDialect(objectSchema, inheritedDialectUri,
					startsResource, location);

			checkNodeCapacity(location);
			McpSchemaNodeId nodeId = new McpSchemaNodeId(nodes.size());
			ResourceBuilder resource = enclosingResource;
			List<String> resourcePointer = candidateResourcePointer;

			if (startsResource) {
				resource = registerResource(localBaseUri, nodeId, dialectUri,
						enclosingResource, documentRoot ? retrievalAlias : null, location);
				resourcePointer = List.of();
			} else if (resource == null) {
				throw new IllegalStateException("A non-root schema must have an enclosing resource.");
			}

			checkPointerSegments(resourcePointer, location, null);
			NodeBuilder node = new NodeBuilder(nodeId, location, resource.id,
					resourcePointer, schema);
			nodes.add(node);
			McpSchemaNodeId previousPointerTarget = resource.pointerTargets.put(
					List.copyOf(resourcePointer), nodeId);
			if (previousPointerTarget != null)
				throw new IllegalStateException("A resource pointer was discovered twice.");

			if (objectSchema != null) {
				registerAnchors(objectSchema, resource, nodeId, location);
				collectReference(objectSchema, "$ref", McpSchemaReference.Kind.STATIC,
						localBaseUri, nodeId, location);
				collectReference(objectSchema, "$dynamicRef",
						McpSchemaReference.Kind.DYNAMIC, localBaseUri, nodeId, location);
				discoverChildren(objectSchema, node, localBaseUri, dialectUri,
						resource, resourcePointer, depth, location);
			}

			return nodeId;
		}

		private Optional<URI> readIdentifier(McpJsonObject schema, URI baseUri,
				McpSchemaLocation location) {
			McpJsonValue value = schema.members().get("$id");
			if (value == null)
				return Optional.empty();
			if (!(value instanceof McpJsonString identifier))
				throw failure(McpSchemaCompilationException.Kind.INVALID_IDENTIFIER,
						"The $id keyword must contain a URI-reference string.",
						location, "$id");

			URI resolved = resolveUri(baseUri, identifier.value(), location, "$id",
					McpSchemaCompilationException.Kind.INVALID_IDENTIFIER);
			String fragment = uriResolver.rawFragment(resolved);
			if (fragment != null && !fragment.isEmpty())
				throw failure(McpSchemaCompilationException.Kind.INVALID_IDENTIFIER,
						"The $id keyword must not contain a non-empty fragment.",
						location, "$id");

			URI fragmentless = uriResolver.withoutFragment(resolved);
			checkUriLength(fragmentless.toASCIIString(), location, "$id");
			return Optional.of(fragmentless);
		}

		private URI readDialect(McpJsonObject schema, URI inheritedDialectUri,
				boolean resourceRoot, McpSchemaLocation location) {
			if (schema == null)
				return inheritedDialectUri;

			McpJsonValue value = schema.members().get("$schema");
			if (value == null)
				return inheritedDialectUri;
			if (!resourceRoot)
				throw failure(McpSchemaCompilationException.Kind.MISPLACED_DIALECT,
						"The $schema keyword is only permitted at a schema resource root.",
						location, "$schema");
			if (!(value instanceof McpJsonString dialect))
				throw failure(McpSchemaCompilationException.Kind.INVALID_KEYWORD_VALUE,
						"The $schema keyword must contain a dialect URI string.",
						location, "$schema");

			checkUriLength(dialect.value(), location, "$schema");
			URI normalizedDialect;
			try {
				URI declaredDialect = URI.create(dialect.value());
				if (!declaredDialect.isAbsolute())
					throw new IllegalArgumentException("A dialect URI must be absolute.");
				normalizedDialect = uriResolver.canonicalAbsolute(declaredDialect);
				if (!normalizedDialect.toASCIIString().equals(dialect.value()))
					throw new IllegalArgumentException("A dialect URI must be normalized.");
				checkUriLength(normalizedDialect.toASCIIString(), location, "$schema");
			} catch (McpSchemaCompilationException exception) {
				throw exception;
			} catch (IllegalArgumentException exception) {
				throw failure(McpSchemaCompilationException.Kind.INVALID_KEYWORD_VALUE,
						"The $schema keyword must contain an absolute normalized URI.",
						location, "$schema");
			}

			McpSchemaDialect registeredDialect = dialectRegistry.find(normalizedDialect)
					.orElseThrow(() -> failure(
							McpSchemaCompilationException.Kind.UNSUPPORTED_DIALECT,
							"The declared JSON Schema dialect is not supported.",
							location, "$schema"));

			return registeredDialect.uri();
		}

		private ResourceBuilder registerResource(URI canonicalUri,
				McpSchemaNodeId rootNodeId, URI dialectUri,
				ResourceBuilder enclosingResource, URI retrievalAlias,
				McpSchemaLocation location) {
			if (resources.size() >= limits.maximumResourceCount())
				throw limit(McpSchemaCompilationException.Limit.RESOURCE_COUNT,
						"Schema resource count exceeds its configured limit.",
						location, "$id");

			McpSchemaResourceId resourceId = new McpSchemaResourceId(resources.size());
			ResourceBuilder resource = new ResourceBuilder(resourceId, canonicalUri,
					rootNodeId, dialectUri, enclosingResource == null ? Optional.empty()
							: Optional.of(enclosingResource.id));
			resources.add(resource);
			registerResourceIdentifier(canonicalUri, resource, location);
			if (retrievalAlias != null)
				registerResourceIdentifier(retrievalAlias, resource, location);
			return resource;
		}

		private void registerResourceIdentifier(URI identifier,
				ResourceBuilder resource, McpSchemaLocation location) {
			ResourceBuilder existing = resourcesByIdentifier.get(identifier);
			if (existing == resource)
				return;
			if (existing != null)
				throw failure(
						McpSchemaCompilationException.Kind.DUPLICATE_RESOURCE_IDENTIFIER,
						"A schema resource identifier is already registered.",
						location, "$id");
			if (resourcesByIdentifier.size()
					>= limits.maximumResourceIdentifierCount())
				throw limit(
						McpSchemaCompilationException.Limit.RESOURCE_IDENTIFIER_COUNT,
						"Schema resource identifier count exceeds its configured limit.",
						location, "$id");

			resourcesByIdentifier.put(identifier, resource);
			resource.identifiers.add(identifier);
		}

		private void registerAnchors(McpJsonObject schema, ResourceBuilder resource,
				McpSchemaNodeId nodeId, McpSchemaLocation location) {
			registerAnchor(schema, resource, nodeId, location, "$anchor", false);
			registerAnchor(schema, resource, nodeId, location, "$dynamicAnchor", true);
		}

		private void registerAnchor(McpJsonObject schema, ResourceBuilder resource,
				McpSchemaNodeId nodeId, McpSchemaLocation location, String keyword,
				boolean dynamic) {
			McpJsonValue value = schema.members().get(keyword);
			if (value == null)
				return;
			if (!(value instanceof McpJsonString anchor))
				throw failure(McpSchemaCompilationException.Kind.INVALID_ANCHOR,
						"An anchor must use the Draft 2020-12 plain-name syntax.",
						location, keyword);
			checkUriLength(anchor.value(), location, keyword);
			checkComposedAnchorUriLength(resource.canonicalUri, anchor.value(),
					location, keyword);
			if (!validAnchorName(anchor.value()))
				throw failure(McpSchemaCompilationException.Kind.INVALID_ANCHOR,
						"An anchor must use the Draft 2020-12 plain-name syntax.",
						location, keyword);
			if (anchorCount >= limits.maximumAnchorCount())
				throw limit(McpSchemaCompilationException.Limit.ANCHOR_COUNT,
						"Schema anchor count exceeds its configured limit.",
						location, keyword);
			anchorCount++;

			if (resource.anchors.putIfAbsent(anchor.value(), nodeId) != null)
				throw failure(McpSchemaCompilationException.Kind.DUPLICATE_ANCHOR,
						"An anchor name is duplicated within one schema resource.",
						location, keyword);
			if (dynamic)
				resource.dynamicAnchors.put(anchor.value(), nodeId);
		}

		private void collectReference(McpJsonObject schema, String keyword,
				McpSchemaReference.Kind kind, URI baseUri, McpSchemaNodeId nodeId,
				McpSchemaLocation location) {
			McpJsonValue value = schema.members().get(keyword);
			if (value == null)
				return;
			if (!(value instanceof McpJsonString reference))
				throw failure(McpSchemaCompilationException.Kind.INVALID_REFERENCE,
						"A schema reference must be a URI-reference string.",
						location, keyword);
			if (referenceCount >= limits.maximumReferenceCount())
				throw limit(McpSchemaCompilationException.Limit.REFERENCE_COUNT,
						"Schema reference count exceeds its configured limit.",
						location, keyword);
			referenceCount++;

			URI resolvedUri = resolveUri(baseUri, reference.value(), location,
					keyword, McpSchemaCompilationException.Kind.INVALID_REFERENCE);
			unresolvedReferences.add(new UnresolvedReference(kind, nodeId,
					resolvedUri, location, keyword));
		}

		private URI resolveUri(URI baseUri, String lexicalReference,
				McpSchemaLocation location, String keyword,
				McpSchemaCompilationException.Kind failureKind) {
			checkUriLength(lexicalReference, location, keyword);

			try {
				URI reference = URI.create(lexicalReference);
				URI resolved = uriResolver.resolve(baseUri, reference);
				if (!resolved.isAbsolute())
					throw new IllegalArgumentException("Resolution produced a relative URI.");
				checkUriLength(resolved.toASCIIString(), location, keyword);
				return resolved;
			} catch (McpSchemaCompilationException exception) {
				throw exception;
			} catch (IllegalArgumentException exception) {
				throw failure(failureKind,
						"A schema URI-reference is malformed or cannot be resolved.",
						location, keyword);
			}
		}

		private void discoverChildren(McpJsonObject schema, NodeBuilder parent,
				URI baseUri, URI dialectUri, ResourceBuilder resource,
				List<String> resourcePointer, int parentDepth,
				McpSchemaLocation location) {
			McpSchemaDialect dialect = dialectRegistry.find(dialectUri)
					.orElseThrow(() -> new IllegalStateException(
							"A discovered schema dialect is not registered."));
			for (SchemaKeyword schemaKeyword : SCHEMA_KEYWORDS) {
				if (!dialect.uses(schemaKeyword.vocabulary()))
					continue;
				McpJsonValue value = schema.members().get(schemaKeyword.name());
				if (value == null)
					continue;

				switch (schemaKeyword.containerKind()) {
					case SINGLE -> discoverSingleChild(value, schemaKeyword.name(),
							parent, baseUri, dialectUri, resource, resourcePointer,
							parentDepth, location);
					case ARRAY -> discoverArrayChildren(value, schemaKeyword.name(),
							parent, baseUri, dialectUri, resource, resourcePointer,
							parentDepth, location);
					case MAP -> discoverMapChildren(value, schemaKeyword.name(),
							parent, baseUri, dialectUri, resource, resourcePointer,
							parentDepth, location);
				}
			}
		}

		private void discoverSingleChild(McpJsonValue value, String keyword,
				NodeBuilder parent, URI baseUri, URI dialectUri,
				ResourceBuilder resource, List<String> resourcePointer,
				int parentDepth, McpSchemaLocation location) {
			if (!isSchema(value))
				throw invalidSchemaContainer(location, keyword);

			McpSchemaNodeId childNodeId = discover(value, location.child(keyword),
					baseUri, dialectUri, resource, append(resourcePointer, keyword),
					parentDepth + 1, false, null);
			parent.childNodeIds.add(childNodeId);
		}

		private void discoverArrayChildren(McpJsonValue value, String keyword,
				NodeBuilder parent, URI baseUri, URI dialectUri,
				ResourceBuilder resource, List<String> resourcePointer,
				int parentDepth, McpSchemaLocation location) {
			if (!(value instanceof McpJsonArray array) || array.values().isEmpty())
				throw invalidSchemaContainer(location, keyword);
			checkChildNodeCapacity(array.values().size(), location, keyword);

			for (int index = 0; index < array.values().size(); ++index) {
				McpJsonValue child = array.values().get(index);
				if (!isSchema(child))
					throw invalidSchemaContainer(location, keyword);
				String indexSegment = Integer.toString(index);
				McpSchemaNodeId childNodeId = discover(child,
						location.child(keyword, indexSegment), baseUri, dialectUri,
						resource, append(resourcePointer, keyword, indexSegment),
						parentDepth + 1, false, null);
				parent.childNodeIds.add(childNodeId);
			}
		}

		private void discoverMapChildren(McpJsonValue value, String keyword,
				NodeBuilder parent, URI baseUri, URI dialectUri,
				ResourceBuilder resource, List<String> resourcePointer,
				int parentDepth, McpSchemaLocation location) {
			if (!(value instanceof McpJsonObject map))
				throw invalidSchemaContainer(location, keyword);
			checkChildNodeCapacity(map.members().size(), location, keyword);

			List<Map.Entry<String, McpJsonValue>> entries =
					new ArrayList<>(map.members().entrySet());
			entries.sort(Map.Entry.comparingByKey());
			for (Map.Entry<String, McpJsonValue> entry : entries) {
				if (!isSchema(entry.getValue()))
					throw invalidSchemaContainer(location, keyword);
				McpSchemaNodeId childNodeId = discover(entry.getValue(),
						location.child(keyword, entry.getKey()), baseUri, dialectUri,
						resource, append(resourcePointer, keyword, entry.getKey()),
						parentDepth + 1, false, null);
				parent.childNodeIds.add(childNodeId);
			}
		}

		private void checkChildNodeCapacity(int childCount,
				McpSchemaLocation location, String keyword) {
			int remaining = limits.maximumSchemaNodeCount() - nodes.size();
			if (childCount > remaining)
				throw limit(McpSchemaCompilationException.Limit.SCHEMA_NODE_COUNT,
						"Schema node count exceeds its configured limit.",
						location, keyword);
		}

		private McpSchemaCompilationException invalidSchemaContainer(
				McpSchemaLocation location, String keyword) {
			return failure(McpSchemaCompilationException.Kind.INVALID_KEYWORD_VALUE,
					"A schema-bearing keyword has an invalid container or subschema.",
					location, keyword);
		}

		private void resolveReferences() {
			for (UnresolvedReference unresolved : unresolvedReferences) {
				McpSchemaReference resolved = resolveReference(unresolved);
				NodeBuilder node = nodes.get(unresolved.sourceNodeId().value());
				if (resolved.kind() == McpSchemaReference.Kind.STATIC)
					node.reference = Optional.of(resolved);
				else
					node.dynamicReference = Optional.of(resolved);
			}
		}

		private McpSchemaReference resolveReference(UnresolvedReference unresolved) {
			URI resourceIdentifier = uriResolver.withoutFragment(unresolved.resolvedUri());
			ResourceBuilder resource = resourcesByIdentifier.get(resourceIdentifier);
			if (resource == null)
				throw unresolvedReference(unresolved);

			String rawFragment = uriResolver.rawFragment(unresolved.resolvedUri());
			String fragment = decodeFragment(rawFragment, unresolved);
			McpSchemaNodeId target;
			Optional<String> dynamicAnchorName = Optional.empty();

			if (fragment == null || fragment.isEmpty()) {
				target = resource.rootNodeId;
			} else if (fragment.startsWith("/")) {
				List<String> pointer = decodeJsonPointer(fragment, unresolved);
				target = resource.pointerTargets.get(pointer);
				if (target == null)
					throw unresolvedReference(unresolved);
			} else {
				target = resource.anchors.get(fragment);
				if (target == null)
					throw unresolvedReference(unresolved);
				if (unresolved.kind() == McpSchemaReference.Kind.DYNAMIC
						&& target.equals(resource.dynamicAnchors.get(fragment)))
					dynamicAnchorName = Optional.of(fragment);
			}

			return new McpSchemaReference(unresolved.kind(), unresolved.resolvedUri(),
					target, dynamicAnchorName);
		}

		private String decodeFragment(String rawFragment,
				UnresolvedReference unresolved) {
			if (rawFragment == null)
				return null;

			StringBuilder decoded = new StringBuilder(rawFragment.length());
			for (int index = 0; index < rawFragment.length();) {
				char character = rawFragment.charAt(index);
				if (character != '%') {
					decoded.append(character);
					index++;
					continue;
				}

				ByteArrayOutputStream bytes = new ByteArrayOutputStream();
				while (index < rawFragment.length()
						&& rawFragment.charAt(index) == '%') {
					int high = Character.digit(rawFragment.charAt(index + 1), 16);
					int low = Character.digit(rawFragment.charAt(index + 2), 16);
					bytes.write(high * 16 + low);
					index += 3;
				}

				try {
					decoded.append(StandardCharsets.UTF_8.newDecoder()
							.onMalformedInput(CodingErrorAction.REPORT)
							.onUnmappableCharacter(CodingErrorAction.REPORT)
							.decode(ByteBuffer.wrap(bytes.toByteArray())));
				} catch (CharacterCodingException exception) {
					throw failure(McpSchemaCompilationException.Kind.INVALID_REFERENCE,
							"A schema reference fragment contains malformed UTF-8.",
							unresolved.location(), unresolved.keyword());
				}
			}

			return decoded.toString();
		}

		private List<String> decodeJsonPointer(String fragment,
				UnresolvedReference unresolved) {
			int segmentCount = 1;
			for (int index = 1; index < fragment.length(); ++index) {
				if (fragment.charAt(index) == '/'
						&& ++segmentCount
						> limits.maximumPointerSegmentCount())
					throw pointerSegmentLimit(unresolved);
			}
			if (segmentCount > limits.maximumPointerSegmentCount())
				throw pointerSegmentLimit(unresolved);

			List<String> segments = new ArrayList<>(segmentCount);
			int segmentStart = 1;
			for (int segmentEnd = 1; segmentEnd <= fragment.length(); ++segmentEnd) {
				if (segmentEnd < fragment.length()
						&& fragment.charAt(segmentEnd) != '/')
					continue;
				StringBuilder segment = new StringBuilder(segmentEnd - segmentStart);
				for (int index = segmentStart; index < segmentEnd; ++index) {
					char character = fragment.charAt(index);
					if (character != '~') {
						segment.append(character);
						continue;
					}

					if (index + 1 >= segmentEnd)
						throw invalidPointer(unresolved);
					char escaped = fragment.charAt(++index);
					if (escaped == '0')
						segment.append('~');
					else if (escaped == '1')
						segment.append('/');
					else
						throw invalidPointer(unresolved);
				}
				segments.add(segment.toString());
				segmentStart = segmentEnd + 1;
			}

			return List.copyOf(segments);
		}

		private McpSchemaCompilationException pointerSegmentLimit(
				UnresolvedReference unresolved) {
			return limit(McpSchemaCompilationException.Limit.POINTER_SEGMENT_COUNT,
					"A schema reference pointer exceeds its configured segment limit.",
					unresolved.location(), unresolved.keyword());
		}

		private McpSchemaCompilationException invalidPointer(
				UnresolvedReference unresolved) {
			return failure(McpSchemaCompilationException.Kind.INVALID_REFERENCE,
					"A schema reference contains an invalid JSON Pointer escape.",
					unresolved.location(), unresolved.keyword());
		}

		private McpSchemaCompilationException unresolvedReference(
				UnresolvedReference unresolved) {
			return failure(McpSchemaCompilationException.Kind.UNRESOLVED_REFERENCE,
					"A schema reference does not resolve within the offline catalog.",
					unresolved.location(), unresolved.keyword());
		}

		private McpSchemaResourceGraph freeze() {
			List<McpCompiledSchemaNode> frozenNodes = nodes.stream()
					.map(NodeBuilder::freeze)
					.toList();
			List<McpSchemaResource> frozenResources = resources.stream()
					.map(ResourceBuilder::freeze)
					.toList();
			Map<URI, McpSchemaResourceId> identifiers = new LinkedHashMap<>();
			for (Map.Entry<URI, ResourceBuilder> entry
					: resourcesByIdentifier.entrySet())
				identifiers.put(entry.getKey(), entry.getValue().id);

			return new McpSchemaResourceGraph(frozenNodes, frozenResources,
					identifiers, documentRoots);
		}

		private void incrementKeywords(int increment, McpSchemaLocation location) {
			if ((long) keywordCount + increment > limits.maximumKeywordCount())
				throw limit(McpSchemaCompilationException.Limit.KEYWORD_COUNT,
						"Schema keyword count exceeds its configured limit.",
						location, null);
			keywordCount += increment;
		}

		private void checkNodeCapacity(McpSchemaLocation location) {
			if (nodes.size() >= limits.maximumSchemaNodeCount())
				throw limit(McpSchemaCompilationException.Limit.SCHEMA_NODE_COUNT,
						"Schema node count exceeds its configured limit.", location, null);
		}

		private void checkDepth(int depth, McpSchemaLocation location) {
			if (depth > limits.maximumSchemaDepth())
				throw limit(McpSchemaCompilationException.Limit.SCHEMA_DEPTH,
						"Schema depth exceeds its configured limit.", location, null);
		}

		private void checkPointerSegments(List<String> pointerSegments,
				McpSchemaLocation location, String keyword) {
			if (pointerSegments.size() > limits.maximumPointerSegmentCount())
				throw limit(McpSchemaCompilationException.Limit.POINTER_SEGMENT_COUNT,
						"A schema location exceeds its configured pointer segment limit.",
						location, keyword);
		}

		private void checkUriLength(String value, McpSchemaLocation location,
				String keyword) {
			if (value.length() > limits.maximumUriLengthInCharacters())
				throw limit(McpSchemaCompilationException.Limit.URI_LENGTH,
						"A schema URI exceeds its configured character limit.",
						location, keyword);
		}

		private void checkComposedAnchorUriLength(URI resourceUri,
				String anchorName, McpSchemaLocation location, String keyword) {
			if ((long) resourceUri.toASCIIString().length() + 1 + anchorName.length()
					> limits.maximumUriLengthInCharacters())
				throw limit(McpSchemaCompilationException.Limit.URI_LENGTH,
						"A schema anchor URI exceeds its configured character limit.",
						location, keyword);
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

	private static boolean isSchema(McpJsonValue value) {
		return value instanceof McpJsonObject || value instanceof McpJsonBoolean;
	}

	private static boolean validAnchorName(String value) {
		if (value.isEmpty() || !anchorInitial(value.charAt(0)))
			return false;

		for (int index = 1; index < value.length(); ++index) {
			char character = value.charAt(index);
			if (!anchorInitial(character) && !(character >= '0' && character <= '9')
					&& character != '-' && character != '.')
				return false;
		}

		return true;
	}

	private static boolean anchorInitial(char character) {
		return character >= 'A' && character <= 'Z'
				|| character >= 'a' && character <= 'z'
				|| character == '_';
	}

	private static List<String> append(List<String> source, String... additions) {
		List<String> result = new ArrayList<>(source.size() + additions.length);
		result.addAll(source);
		for (String addition : additions)
			result.add(addition);
		return List.copyOf(result);
	}

	private enum ContainerKind {
		SINGLE,
		ARRAY,
		MAP
	}

	private record SchemaKeyword(String name, ContainerKind containerKind,
			McpSchemaVocabulary vocabulary) {
		private SchemaKeyword {
			requireNonNull(name);
			requireNonNull(containerKind);
			requireNonNull(vocabulary);
		}
	}

	private record PreparedDocument(URI retrievalUri, McpJsonValue rootSchema) {
	}

	private record UnresolvedReference(McpSchemaReference.Kind kind,
			McpSchemaNodeId sourceNodeId, URI resolvedUri,
			McpSchemaLocation location, String keyword) {
	}

	private static final class NodeBuilder {
		private final McpSchemaNodeId id;
		private final McpSchemaLocation location;
		private final McpSchemaResourceId resourceId;
		private final List<String> resourcePointerSegments;
		private final McpJsonValue schema;
		private final List<McpSchemaNodeId> childNodeIds;
		private Optional<McpSchemaReference> reference;
		private Optional<McpSchemaReference> dynamicReference;

		private NodeBuilder(McpSchemaNodeId id, McpSchemaLocation location,
				McpSchemaResourceId resourceId, List<String> resourcePointerSegments,
				McpJsonValue schema) {
			this.id = id;
			this.location = location;
			this.resourceId = resourceId;
			this.resourcePointerSegments = List.copyOf(resourcePointerSegments);
			this.schema = schema;
			this.childNodeIds = new ArrayList<>();
			this.reference = Optional.empty();
			this.dynamicReference = Optional.empty();
		}

		private McpCompiledSchemaNode freeze() {
			return new McpCompiledSchemaNode(id, location, resourceId,
					resourcePointerSegments, schema, childNodeIds, reference,
					dynamicReference);
		}
	}

	private static final class ResourceBuilder {
		private final McpSchemaResourceId id;
		private final URI canonicalUri;
		private final Set<URI> identifiers;
		private final McpSchemaNodeId rootNodeId;
		private final URI dialectUri;
		private final Optional<McpSchemaResourceId> enclosingResourceId;
		private final Map<String, McpSchemaNodeId> anchors;
		private final Map<String, McpSchemaNodeId> dynamicAnchors;
		private final Map<List<String>, McpSchemaNodeId> pointerTargets;

		private ResourceBuilder(McpSchemaResourceId id, URI canonicalUri,
				McpSchemaNodeId rootNodeId, URI dialectUri,
				Optional<McpSchemaResourceId> enclosingResourceId) {
			this.id = id;
			this.canonicalUri = canonicalUri;
			this.identifiers = new LinkedHashSet<>();
			this.rootNodeId = rootNodeId;
			this.dialectUri = dialectUri;
			this.enclosingResourceId = enclosingResourceId;
			this.anchors = new LinkedHashMap<>();
			this.dynamicAnchors = new LinkedHashMap<>();
			this.pointerTargets = new LinkedHashMap<>();
		}

		private McpSchemaResource freeze() {
			return new McpSchemaResource(id, canonicalUri, identifiers, rootNodeId,
					dialectUri, enclosingResourceId, anchors, dynamicAnchors,
					pointerTargets);
		}
	}
}
