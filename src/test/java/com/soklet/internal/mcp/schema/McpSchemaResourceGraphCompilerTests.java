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
import com.soklet.internal.mcp.protocol.McpJsonCodec;
import com.soklet.internal.mcp.protocol.McpJsonLimits;
import com.soklet.internal.mcp.protocol.McpJsonObject;
import com.soklet.internal.mcp.protocol.McpJsonString;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class McpSchemaResourceGraphCompilerTests {
	private static final URI DEFAULT_RETRIEVAL_URI =
			URI.create("https://schemas.example.test/root.json");
	private static final McpJsonCodec JSON_CODEC = new McpJsonCodec(
			new McpJsonLimits(1_000_000, 256, 200_000, 200_000, 10_000,
					100_000, 100_000, 1_000_000));
	private static final McpSchemaCompilationLimits COMPILATION_LIMITS =
			new McpSchemaCompilationLimits(100, 10_000, 256, 100_000,
					10_000, 20_000, 10_000, 10_000, 20_000, 1_000,
					10_000);

	@Test
	public void objectAndBooleanDocumentRootsCompileWithDefaultDialect() {
		McpSchemaResourceGraph graph = compile(
				document("https://schemas.example.test/z.json", "true"),
				document("https://schemas.example.test/a.json", "{}"),
				document("https://schemas.example.test/m.json", "false"));

		Assertions.assertEquals(3, graph.nodes().size());
		Assertions.assertEquals(3, graph.resources().size());
		Assertions.assertInstanceOf(McpJsonObject.class,
				rootNode(graph, "https://schemas.example.test/a.json").schema());
		Assertions.assertEquals(McpJsonBoolean.FALSE,
				rootNode(graph, "https://schemas.example.test/m.json").schema());
		Assertions.assertEquals(McpJsonBoolean.TRUE,
				rootNode(graph, "https://schemas.example.test/z.json").schema());

		for (McpSchemaResource resource : graph.resources())
			Assertions.assertEquals(
					McpSchemaResourceGraphCompiler.DRAFT_2020_12_DIALECT,
					resource.dialectUri());
	}

	@Test
	public void nonSchemaDocumentRootsAreRejected() {
		for (String invalidRoot : List.of("null", "0", "\"schema\"", "[]"))
			assertCompilationFails(McpSchemaCompilationException.Kind.INVALID_SCHEMA,
					invalidRoot);
	}

	@Test
	public void retrievalUrisMustBeAbsoluteFragmentlessAndUniqueAfterNormalization() {
		for (URI invalid : List.of(URI.create("relative/schema.json"),
				URI.create("https://schemas.example.test/root.json#fragment"))) {
			McpSchemaCompilationException exception = Assertions.assertThrows(
					McpSchemaCompilationException.class,
					() -> compile(new McpSchemaDocument(invalid, JSON_CODEC.parse("{}"))));
			Assertions.assertEquals(
					McpSchemaCompilationException.Kind.INVALID_RETRIEVAL_URI,
					exception.kind());
		}

		McpSchemaCompilationException duplicate = Assertions.assertThrows(
				McpSchemaCompilationException.class,
				() -> compile(
						document("HTTPS://SCHEMAS.EXAMPLE.TEST/a.json", "{}"),
						document("https://schemas.example.test/a.json", "{}")));
		Assertions.assertEquals(
				McpSchemaCompilationException.Kind.DUPLICATE_RESOURCE_IDENTIFIER,
				duplicate.kind());
	}

	@Test
	public void exactDraftDialectIsAcceptedAndInheritedByEmbeddedResources() {
		McpSchemaResourceGraph graph = compile(document(DEFAULT_RETRIEVAL_URI, """
				{
				  "$schema": "https://json-schema.org/draft/2020-12/schema",
				  "$defs": {
				    "inherited": {"$id": "inherited.json"},
				    "explicit": {
				      "$id": "explicit.json",
				      "$schema": "https://json-schema.org/draft/2020-12/schema"
				    }
				  }
				}
				"""));

		Assertions.assertEquals(3, graph.resources().size());
		for (McpSchemaResource resource : graph.resources())
			Assertions.assertEquals(
					McpSchemaResourceGraphCompiler.DRAFT_2020_12_DIALECT,
					resource.dialectUri());
	}

	@Test
	public void unsupportedNonStringAndMisplacedDialectsAreTypedFailures() {
		McpSchemaCompilationException unsupported = assertCompilationFails(
				McpSchemaCompilationException.Kind.UNSUPPORTED_DIALECT,
				"{\"$schema\":\"https://json-schema.org/draft/2019-09/schema\"}");
		Assertions.assertEquals("$schema", unsupported.keyword().orElseThrow());

		McpSchemaCompilationException nonString = assertCompilationFails(
				McpSchemaCompilationException.Kind.INVALID_KEYWORD_VALUE,
				"{\"$schema\":true}");
		Assertions.assertEquals("$schema", nonString.keyword().orElseThrow());

		for (String invalidDialect : List.of("not a URI", "relative/schema",
				"HTTPS://JSON-SCHEMA.ORG/draft/2020-12/schema")) {
			McpSchemaCompilationException invalid = assertCompilationFails(
					McpSchemaCompilationException.Kind.INVALID_KEYWORD_VALUE,
					"{\"$schema\":" + jsonString(invalidDialect) + "}");
			Assertions.assertEquals("$schema", invalid.keyword().orElseThrow());
		}

		McpSchemaCompilationException misplaced = assertCompilationFails(
				McpSchemaCompilationException.Kind.MISPLACED_DIALECT, """
						{"properties":{"nested":{
						  "$schema":"https://json-schema.org/draft/2020-12/schema"
						}}}
						""");
		Assertions.assertEquals("$schema", misplaced.keyword().orElseThrow());
		Assertions.assertEquals("/properties/nested",
				misplaced.location().orElseThrow().jsonPointer());
	}

	@Test
	public void rootIdentifierCreatesCanonicalIdentifierAndRetrievalAlias() {
		URI retrievalUri = URI.create(
				"https://z-retrieval.example.test/catalog/root.json");
		URI canonicalUri = URI.create(
				"https://z-retrieval.example.test/canonical/schema");
		URI clientUri = URI.create("https://a-client.example.test/use.json");
		McpSchemaResourceGraph graph = compile(
				document(clientUri, "{\"$ref\":\"" + retrievalUri + "\"}"),
				document(retrievalUri, "{\"$id\":\"../canonical/schema#\"}"));

		McpSchemaResource canonicalResource = graph.resource(canonicalUri)
				.orElseThrow();
		Assertions.assertEquals(Set.of(canonicalUri, retrievalUri),
				canonicalResource.identifiers());
		Assertions.assertEquals(canonicalResource,
				graph.resource(retrievalUri).orElseThrow());

		McpSchemaReference aliasReference = rootNode(graph, clientUri)
				.reference().orElseThrow();
		Assertions.assertEquals(rootNode(graph, retrievalUri).id(),
				aliasReference.initialTargetNodeId());
	}

	@Test
	public void embeddedIdentifiersCreateNestedResourcesWithTheirOwnPointerRoots() {
		McpSchemaResourceGraph graph = compile(document(
				"https://schemas.example.test/root/root.json", """
				{
				  "$defs": {
				    "container": {
				      "$id": "folder/child.json",
				      "$defs": {"leaf": {"$id": "leaf.json"}}
				    }
				  }
				}
				"""));

		McpSchemaResource root = graph.resource(
				URI.create("https://schemas.example.test/root/root.json"))
				.orElseThrow();
		McpSchemaResource child = graph.resource(
				URI.create("https://schemas.example.test/root/folder/child.json"))
				.orElseThrow();
		McpSchemaResource leaf = graph.resource(
				URI.create("https://schemas.example.test/root/folder/leaf.json"))
				.orElseThrow();

		Assertions.assertEquals(3, graph.nodes().size());
		Assertions.assertEquals(3, graph.resources().size());
		Assertions.assertTrue(root.enclosingResourceId().isEmpty());
		Assertions.assertEquals(root.id(), child.enclosingResourceId().orElseThrow());
		Assertions.assertEquals(child.id(), leaf.enclosingResourceId().orElseThrow());
		Assertions.assertEquals(Set.of(List.of()), root.pointerTargets().keySet());
		Assertions.assertEquals(Set.of(List.of()), child.pointerTargets().keySet());
		Assertions.assertEquals(Set.of(List.of()), leaf.pointerTargets().keySet());
		Assertions.assertEquals(child.id(), nodeAt(graph,
				"https://schemas.example.test/root/root.json",
				"/$defs/container").resourceId());
		Assertions.assertEquals(leaf.id(), nodeAt(graph,
				"https://schemas.example.test/root/root.json",
				"/$defs/container/$defs/leaf").resourceId());
	}

	@Test
	public void invalidAndDuplicateIdentifiersAreTypedFailures() {
		for (String invalid : List.of(
				"{\"$id\":1}",
				"{\"$id\":\"#named\"}",
				"{\"$id\":\"child.json#named\"}",
				"{\"$id\":\"%ZZ\"}"))
			assertCompilationFails(
					McpSchemaCompilationException.Kind.INVALID_IDENTIFIER, invalid);

		McpSchemaResourceGraph emptyFragment = compile(document(
				DEFAULT_RETRIEVAL_URI, "{\"$id\":\"#\"}"));
		Assertions.assertTrue(emptyFragment.resource(DEFAULT_RETRIEVAL_URI).isPresent());

		McpSchemaCompilationException duplicate = assertCompilationFails(
				McpSchemaCompilationException.Kind.DUPLICATE_RESOURCE_IDENTIFIER,
				"{\"$defs\":{\"a\":{\"$id\":\"same.json\"},"
						+ "\"b\":{\"$id\":\"same.json\"}}}");
		Assertions.assertEquals("/$defs/b",
				duplicate.location().orElseThrow().jsonPointer());
	}

	@Test
	public void everyDraftSchemaBearingKeywordIsTraversedButDataKeywordsAreNot() {
		McpSchemaResourceGraph graph = compile(document(DEFAULT_RETRIEVAL_URI, """
				{
				  "$defs": {"entry": true},
				  "additionalProperties": true,
				  "allOf": [true],
				  "anyOf": [true],
				  "contains": true,
				  "contentSchema": true,
				  "dependentSchemas": {"entry": true},
				  "else": true,
				  "if": true,
				  "items": true,
				  "not": true,
				  "oneOf": [true],
				  "patternProperties": {"entry": true},
				  "prefixItems": [true],
				  "properties": {"entry": true},
				  "propertyNames": true,
				  "then": true,
				  "unevaluatedItems": true,
				  "unevaluatedProperties": true,
				  "const": {"$id": "ignored-const.json", "$ref": "missing.json"},
				  "enum": [{"$id": "ignored-enum.json", "$ref": "missing.json"}],
				  "definitions": {"legacy": {"$id": "ignored-legacy.json"}},
				  "x-ignored": {
				    "$id": "ignored-custom.json",
				    "properties": {"phantom": {"$ref": "missing.json"}}
				  }
				}
				"""));

		Set<String> actualPointers = graph.nodes().stream().skip(1)
				.map(node -> node.location().jsonPointer())
				.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
		Set<String> expectedPointers = Set.of(
				"/$defs/entry", "/additionalProperties", "/allOf/0", "/anyOf/0",
				"/contains", "/contentSchema", "/dependentSchemas/entry", "/else",
				"/if", "/items", "/not", "/oneOf/0",
				"/patternProperties/entry", "/prefixItems/0",
				"/properties/entry", "/propertyNames", "/then",
				"/unevaluatedItems", "/unevaluatedProperties");

		Assertions.assertEquals(expectedPointers, actualPointers);
		Assertions.assertEquals(20, graph.nodes().size());
		Assertions.assertEquals(1, graph.resources().size());
		Assertions.assertEquals(
				IntStream.range(1, 20).mapToObj(McpSchemaNodeId::new).toList(),
				graph.nodes().get(0).childNodeIds());
	}

	@Test
	public void schemaBearingKeywordContainersAreValidatedPrecisely() {
		for (String invalidMember : List.of(
				"\"additionalProperties\":[]",
				"\"items\":[]",
				"\"allOf\":[]",
				"\"anyOf\":[]",
				"\"oneOf\":[]",
				"\"prefixItems\":[]",
				"\"prefixItems\":{}",
				"\"prefixItems\":[1]",
				"\"properties\":[]",
				"\"properties\":{\"x\":1}"))
			assertCompilationFails(
					McpSchemaCompilationException.Kind.INVALID_KEYWORD_VALUE,
					"{" + invalidMember + "}");
	}

	@Test
	public void anchorsUseOnePerResourceNamespaceAndDynamicAnchorsAreAlsoPlain() {
		McpSchemaResourceGraph graph = compile(document(DEFAULT_RETRIEVAL_URI, """
				{
				  "$anchor": "A-1.x",
				  "$defs": {
				    "dynamic": {"$dynamicAnchor": "_D.2"},
				    "embedded": {"$id": "child.json", "$anchor": "A-1.x"}
				  }
				}
				"""));

		McpSchemaResource root = graph.resource(DEFAULT_RETRIEVAL_URI).orElseThrow();
		McpSchemaResource embedded = graph.resource(
				URI.create("https://schemas.example.test/child.json")).orElseThrow();
		Assertions.assertEquals(Set.of("A-1.x", "_D.2"), root.anchors().keySet());
		Assertions.assertEquals(Set.of("_D.2"), root.dynamicAnchors().keySet());
		Assertions.assertEquals(Set.of("A-1.x"), embedded.anchors().keySet());
	}

	@Test
	public void malformedDuplicateAndCrossKindAnchorNamesAreRejected() {
		for (String name : List.of("", "1bad", "-bad", "bad:name", "é"))
			assertCompilationFails(McpSchemaCompilationException.Kind.INVALID_ANCHOR,
					"{\"$anchor\":" + jsonString(name) + "}");

		assertCompilationFails(McpSchemaCompilationException.Kind.DUPLICATE_ANCHOR,
				"{\"$anchor\":\"same\",\"properties\":{"
						+ "\"child\":{\"$anchor\":\"same\"}}}");
		assertCompilationFails(McpSchemaCompilationException.Kind.DUPLICATE_ANCHOR,
				"{\"$anchor\":\"same\",\"$dynamicAnchor\":\"same\"}");
	}

	@Test
	public void localAndExternalPointersAndAnchorsResolveOffline() {
		URI mainUri = URI.create("https://schemas.example.test/main.json");
		URI otherUri = URI.create("https://schemas.example.test/other.json");
		McpSchemaResourceGraph graph = compile(
				document(mainUri, """
						{
						  "$defs": {
						    "café": true,
						    "local-anchor-target": {"$anchor": "here"},
						    "local-anchor-use": {"$ref": "#here"},
						    "local-pointer-use": {"$ref": "#/$defs/slash~1~0key"},
						    "slash/~key": false,
						    "utf8-use": {"$ref": "#/$defs/caf%C3%A9"},
						    "external-anchor-use": {"$ref": "other.json#there"},
						    "external-pointer-use": {"$ref": "other.json#/$defs/target"},
						    "external-root-use": {"$ref": "other.json"}
						  }
						}
						"""),
				document(otherUri,
						"{\"$defs\":{\"target\":{\"$anchor\":\"there\"}}}"));

		McpSchemaNodeId localAnchorTarget = nodeAt(graph, mainUri,
				"/$defs/local-anchor-target").id();
		McpSchemaNodeId localPointerTarget = nodeAt(graph, mainUri,
				"/$defs/slash~1~0key").id();
		McpSchemaNodeId utf8Target = nodeAt(graph, mainUri, "/$defs/café").id();
		McpSchemaNodeId externalTarget = nodeAt(graph, otherUri,
				"/$defs/target").id();

		assertStaticTarget(graph, mainUri, "/$defs/local-anchor-use",
				localAnchorTarget);
		assertStaticTarget(graph, mainUri, "/$defs/local-pointer-use",
				localPointerTarget);
		assertStaticTarget(graph, mainUri, "/$defs/utf8-use", utf8Target);
		assertStaticTarget(graph, mainUri, "/$defs/external-anchor-use",
				externalTarget);
		assertStaticTarget(graph, mainUri, "/$defs/external-pointer-use",
				externalTarget);
		assertStaticTarget(graph, mainUri, "/$defs/external-root-use",
				rootNode(graph, otherUri).id());
	}

	@Test
	public void referenceFragmentsArePercentDecodedExactlyOnce() {
		McpSchemaResourceGraph graph = compile(document(DEFAULT_RETRIEVAL_URI, """
				{
				  "$defs": {
				    "%2F": true,
				    "~1": false,
				    "percent-use": {"$ref": "#/$defs/%252F"},
				    "tilde-use": {"$ref": "#/$defs/~01"}
				  }
				}
				"""));

		assertStaticTarget(graph, DEFAULT_RETRIEVAL_URI, "/$defs/percent-use",
				nodeAt(graph, DEFAULT_RETRIEVAL_URI, "/$defs/%2F").id());
		assertStaticTarget(graph, DEFAULT_RETRIEVAL_URI, "/$defs/tilde-use",
				nodeAt(graph, DEFAULT_RETRIEVAL_URI, "/$defs/~01").id());
	}

	@Test
	public void invalidPointerEscapesPercentEncodingAndUtf8AreRejectedStrictly() {
		for (String reference : List.of(
				"#/$defs/target~2", "#/%ZZ", "#/%C3%28", "#/%C0%AF")) {
			McpSchemaCompilationException exception = assertCompilationFails(
					McpSchemaCompilationException.Kind.INVALID_REFERENCE,
					"{\"$defs\":{\"target\":true},\"$ref\":"
							+ jsonString(reference) + "}");
			Assertions.assertEquals("$ref", exception.keyword().orElseThrow());
		}
	}

	@Test
	public void unresolvedReferencesFailClosedWithoutExternalRetrieval() {
		McpSchemaCompilationException exception = assertCompilationFails(
				McpSchemaCompilationException.Kind.UNRESOLVED_REFERENCE,
				"{\"$ref\":\"https://unavailable.example.test/schema.json\"}");
		Assertions.assertEquals("$ref", exception.keyword().orElseThrow());
		Assertions.assertEquals("", exception.location().orElseThrow().jsonPointer());
	}

	@Test
	public void referenceSiblingsAndRecursiveCyclesRemainInTheGraph() {
		McpSchemaResourceGraph graph = compile(document(DEFAULT_RETRIEVAL_URI, """
				{
				  "$ref": "#",
				  "$defs": {
				    "a": {"$ref": "#/$defs/b"},
				    "b": {"$ref": "#/$defs/a"}
				  },
				  "properties": {"sibling": true}
				}
				"""));

		McpCompiledSchemaNode root = rootNode(graph, DEFAULT_RETRIEVAL_URI);
		McpCompiledSchemaNode a = nodeAt(graph, DEFAULT_RETRIEVAL_URI, "/$defs/a");
		McpCompiledSchemaNode b = nodeAt(graph, DEFAULT_RETRIEVAL_URI, "/$defs/b");
		McpCompiledSchemaNode sibling = nodeAt(graph, DEFAULT_RETRIEVAL_URI,
				"/properties/sibling");

		Assertions.assertEquals(root.id(),
				root.reference().orElseThrow().initialTargetNodeId());
		Assertions.assertEquals(b.id(),
				a.reference().orElseThrow().initialTargetNodeId());
		Assertions.assertEquals(a.id(),
				b.reference().orElseThrow().initialTargetNodeId());
		Assertions.assertTrue(root.childNodeIds().contains(sibling.id()));
	}

	@Test
	public void dynamicReferencesClassifyOnlyPlainDynamicAnchorTargetsAsDynamic() {
		McpSchemaResourceGraph graph = compile(document(DEFAULT_RETRIEVAL_URI, """
				{
				  "$defs": {
				    "dynamic-target": {"$dynamicAnchor": "slot"},
				    "dynamic-use": {"$dynamicRef": "#slot"},
				    "plain-target": {"$anchor": "plain"},
				    "plain-use": {"$dynamicRef": "#plain"},
				    "pointer-use": {"$dynamicRef": "#/$defs/dynamic-target"},
				    "static-use": {"$ref": "#slot"}
				  }
				}
				"""));

		McpSchemaNodeId dynamicTarget = nodeAt(graph, DEFAULT_RETRIEVAL_URI,
				"/$defs/dynamic-target").id();
		McpSchemaNodeId plainTarget = nodeAt(graph, DEFAULT_RETRIEVAL_URI,
				"/$defs/plain-target").id();
		McpSchemaReference dynamic = nodeAt(graph, DEFAULT_RETRIEVAL_URI,
				"/$defs/dynamic-use").dynamicReference().orElseThrow();
		McpSchemaReference plain = nodeAt(graph, DEFAULT_RETRIEVAL_URI,
				"/$defs/plain-use").dynamicReference().orElseThrow();
		McpSchemaReference pointer = nodeAt(graph, DEFAULT_RETRIEVAL_URI,
				"/$defs/pointer-use").dynamicReference().orElseThrow();
		McpSchemaReference staticReference = nodeAt(graph, DEFAULT_RETRIEVAL_URI,
				"/$defs/static-use").reference().orElseThrow();

		Assertions.assertEquals(McpSchemaReference.Kind.DYNAMIC, dynamic.kind());
		Assertions.assertEquals(dynamicTarget, dynamic.initialTargetNodeId());
		Assertions.assertEquals("slot", dynamic.dynamicAnchorName().orElseThrow());
		Assertions.assertEquals(plainTarget, plain.initialTargetNodeId());
		Assertions.assertTrue(plain.dynamicAnchorName().isEmpty());
		Assertions.assertEquals(dynamicTarget, pointer.initialTargetNodeId());
		Assertions.assertTrue(pointer.dynamicAnchorName().isEmpty());
		Assertions.assertEquals(McpSchemaReference.Kind.STATIC, staticReference.kind());
		Assertions.assertTrue(staticReference.dynamicAnchorName().isEmpty());
	}

	@Test
	public void dynamicResolutionUsesOutermostMatchingResourceAndOtherwiseFallsBack() {
		McpSchemaResourceGraph graph = compile(document(DEFAULT_RETRIEVAL_URI, """
				{
				  "$dynamicAnchor": "slot",
				  "$defs": {
				    "inner": {
				      "$id": "inner.json",
				      "$dynamicAnchor": "slot",
				      "properties": {"use": {"$dynamicRef": "#slot"}}
				    }
				  }
				}
				"""));

		McpSchemaResource outer = graph.resource(DEFAULT_RETRIEVAL_URI).orElseThrow();
		McpSchemaResource inner = graph.resource(
				URI.create("https://schemas.example.test/inner.json")).orElseThrow();
		McpSchemaReference reference = nodeAt(graph, DEFAULT_RETRIEVAL_URI,
				"/$defs/inner/properties/use").dynamicReference().orElseThrow();
		McpDynamicReferenceResolver resolver = new McpDynamicReferenceResolver();

		Assertions.assertEquals(outer.rootNodeId(), resolver.resolve(graph, reference,
				List.of(outer.id(), inner.id())));
		Assertions.assertEquals(inner.rootNodeId(), resolver.resolve(graph, reference,
				List.of(inner.id(), outer.id())));
		Assertions.assertEquals(inner.rootNodeId(),
				resolver.resolve(graph, reference, List.of()));
	}

	@Test
	public void parentResourcePointersCannotCrossAnEmbeddedResourceBoundary() {
		McpSchemaCompilationException exception = assertCompilationFails(
				McpSchemaCompilationException.Kind.UNRESOLVED_REFERENCE, """
						{
						  "$defs": {
						    "embedded": {"$id": "child.json", "properties": {"x": true}},
						    "use": {"$ref": "#/$defs/embedded"}
						  }
						}
						""");
		Assertions.assertEquals("/$defs/use",
				exception.location().orElseThrow().jsonPointer());
		Assertions.assertEquals("$ref", exception.keyword().orElseThrow());
	}

	@Test
	public void canonicalEmbeddedResourceReferencesRemainAvailable() {
		McpSchemaResourceGraph graph = compile(document(DEFAULT_RETRIEVAL_URI, """
				{
				  "$defs": {
				    "embedded": {"$id": "child.json", "properties": {"x": true}},
				    "use": {"$ref": "child.json#/properties/x"}
				  }
				}
				"""));

		assertStaticTarget(graph, DEFAULT_RETRIEVAL_URI, "/$defs/use",
				nodeAt(graph, DEFAULT_RETRIEVAL_URI,
						"/$defs/embedded/properties/x").id());
	}

	@Test
	public void inactiveVocabularyKeywordContentsAreCompletelyOpaque() {
		URI dialectUri = URI.create("https://meta.example.test/core-only");
		McpSchemaDialectRegistry registry = registry(metaSchema(dialectUri,
				McpSchemaVocabulary.CORE));
		McpSchemaResourceGraph graph = compile(registry, document(
				DEFAULT_RETRIEVAL_URI, """
						{
						  "$schema":"https://meta.example.test/core-only",
						  "$defs":{"visible":true},
						  "properties":1,
						  "unevaluatedProperties":{
						    "$id":"https://hidden.example.test/unevaluated",
						    "$ref":"https://missing.example.test/unevaluated"
						  },
						  "contentSchema":{
						    "$id":"https://hidden.example.test/content",
						    "$ref":"https://missing.example.test/content"
						  }
						}
						"""));

		Assertions.assertEquals(List.of("", "/$defs/visible"),
				graph.nodes().stream()
						.map(node -> node.location().jsonPointer()).toList());
		Assertions.assertEquals(1, graph.resources().size());
		Assertions.assertEquals(1, graph.resourceIdentifiers().size());
		Assertions.assertTrue(graph.nodes().stream()
				.allMatch(node -> node.reference().isEmpty()));
	}

	@Test
	public void unknownOptionalVocabularyKeywordContentsAreCompletelyOpaque() {
		URI dialectUri = URI.create(
				"https://meta.example.test/unknown-optional");
		McpSchemaDialectRegistry registry = registry(document(dialectUri, """
				{
				  "$schema":"https://json-schema.org/draft/2020-12/schema",
				  "$id":"https://meta.example.test/unknown-optional",
				  "$vocabulary":{
				    "https://json-schema.org/draft/2020-12/vocab/core":true,
				    "https://vocabulary.example.test/unknown":false
				  }
				}
				"""));
		McpSchemaResourceGraph graph = compile(registry, document(
				DEFAULT_RETRIEVAL_URI, """
					{
					  "$schema":"https://meta.example.test/unknown-optional",
					  "$defs":{"visible":true},
					  "customSchemas":{
					    "hidden":{
					      "$id":"https://hidden.example.test/custom",
					      "$ref":"https://missing.example.test/custom",
					      "properties":1
					    }
					  }
					}
					"""));

		Assertions.assertEquals(List.of("", "/$defs/visible"),
				graph.nodes().stream()
						.map(node -> node.location().jsonPointer()).toList());
		Assertions.assertEquals(1, graph.resources().size());
		Assertions.assertEquals(1, graph.resourceIdentifiers().size());
		Assertions.assertTrue(graph.nodes().stream()
				.allMatch(node -> node.reference().isEmpty()));
	}

	@Test
	public void eachKnownVocabularyControlsOnlyItsSchemaBearingLocations() {
		URI applicatorUri = URI.create(
				"https://meta.example.test/core-applicator");
		URI unevaluatedUri = URI.create(
				"https://meta.example.test/core-unevaluated");
		URI contentUri = URI.create("https://meta.example.test/core-content");
		McpSchemaDialectRegistry registry = registry(
				metaSchema(applicatorUri, McpSchemaVocabulary.CORE,
						McpSchemaVocabulary.APPLICATOR),
				metaSchema(unevaluatedUri, McpSchemaVocabulary.CORE,
						McpSchemaVocabulary.UNEVALUATED),
				metaSchema(contentUri, McpSchemaVocabulary.CORE,
						McpSchemaVocabulary.CONTENT));

		McpSchemaResourceGraph applicator = compile(registry, document(
				"https://schema.example.test/applicator", """
						{"$schema":"https://meta.example.test/core-applicator",
						 "properties":{"active":true},
						 "unevaluatedProperties":1,"contentSchema":1}
						"""));
		Assertions.assertEquals(List.of("", "/properties/active"),
				applicator.nodes().stream()
						.map(node -> node.location().jsonPointer()).toList());

		McpSchemaResourceGraph unevaluated = compile(registry, document(
				"https://schema.example.test/unevaluated", """
						{"$schema":"https://meta.example.test/core-unevaluated",
						 "properties":1,"unevaluatedProperties":true,
						 "contentSchema":1}
						"""));
		Assertions.assertEquals(List.of("", "/unevaluatedProperties"),
				unevaluated.nodes().stream()
						.map(node -> node.location().jsonPointer()).toList());

		McpSchemaResourceGraph content = compile(registry, document(
				"https://schema.example.test/content", """
						{"$schema":"https://meta.example.test/core-content",
						 "properties":1,"unevaluatedProperties":1,
						 "contentSchema":true}
						"""));
		Assertions.assertEquals(List.of("", "/contentSchema"),
				content.nodes().stream()
						.map(node -> node.location().jsonPointer()).toList());
	}

	@Test
	public void embeddedResourcesInheritCustomDialectAndMayOverrideAtTheirRoot() {
		URI dialectUri = URI.create(
				"https://meta.example.test/inherited-applicator");
		McpSchemaDialectRegistry registry = registry(metaSchema(dialectUri,
				McpSchemaVocabulary.CORE, McpSchemaVocabulary.APPLICATOR));
		McpSchemaResourceGraph graph = compile(registry, document(
				DEFAULT_RETRIEVAL_URI, """
						{
						  "$schema":"https://meta.example.test/inherited-applicator",
						  "$defs":{
						    "inherited":{
						      "$id":"inherited.json",
						      "properties":{"value":true}
						    },
						    "overridden":{
						      "$id":"overridden.json",
						      "$schema":"https://json-schema.org/draft/2020-12/schema",
						      "contentSchema":true
						    }
						  }
						}
						"""));

		McpSchemaResource root = graph.resource(DEFAULT_RETRIEVAL_URI)
				.orElseThrow();
		McpSchemaResource inherited = graph.resource(URI.create(
				"https://schemas.example.test/inherited.json")).orElseThrow();
		McpSchemaResource overridden = graph.resource(URI.create(
				"https://schemas.example.test/overridden.json")).orElseThrow();
		Assertions.assertEquals(dialectUri, root.dialectUri());
		Assertions.assertEquals(dialectUri, inherited.dialectUri());
		Assertions.assertEquals(McpSchemaDialectRegistry.DRAFT_2020_12_URI,
				overridden.dialectUri());
		Assertions.assertEquals(List.of(
				"", "/$defs/inherited", "/$defs/inherited/properties/value",
				"/$defs/overridden", "/$defs/overridden/contentSchema"),
				graph.nodes().stream()
						.map(node -> node.location().jsonPointer()).toList());
	}

	@Test
	public void compiledGraphAndEveryExposedCollectionAreImmutable() {
		McpSchemaResourceGraph graph = compile(document(DEFAULT_RETRIEVAL_URI, """
				{
				  "$anchor": "root",
				  "$defs": {"child": {"$dynamicAnchor": "dynamic"}},
				  "properties": {"value": true}
				}
				"""));
		McpCompiledSchemaNode root = rootNode(graph, DEFAULT_RETRIEVAL_URI);
		McpSchemaResource resource = graph.resource(DEFAULT_RETRIEVAL_URI)
				.orElseThrow();

		Stream.<Runnable>of(
				() -> graph.nodes().add(root),
				() -> graph.resources().clear(),
				() -> graph.resourceIdentifiers().clear(),
				() -> graph.documentRoots().clear(),
				() -> root.childNodeIds().clear(),
				() -> root.resourcePointerSegments().add("mutation"),
				() -> root.location().pointerSegments().add("mutation"),
				() -> resource.identifiers().clear(),
				() -> resource.anchors().clear(),
				() -> resource.dynamicAnchors().clear(),
				() -> resource.pointerTargets().clear(),
				() -> resource.pointerTargets().keySet().iterator().next()
						.add("mutation"),
				() -> ((McpJsonObject) root.schema()).members()
						.put("mutation", McpJsonBoolean.TRUE))
				.forEach(mutation -> Assertions.assertThrows(
						UnsupportedOperationException.class, mutation::run));
	}

	@Test
	public void nodeAndResourceIdsAreDeterministicAcrossInputAndMemberOrder() {
		McpSchemaDocument aFirstOrder = document(
				"https://schemas.example.test/a.json", """
						{
						  "properties": {"z": false, "a": true},
						  "$defs": {"b": {}, "a": {}}
						}
						""");
		McpSchemaDocument aSecondOrder = document(
				"https://schemas.example.test/a.json", """
						{
						  "$defs": {"a": {}, "b": {}},
						  "properties": {"a": true, "z": false}
						}
						""");
		McpSchemaDocument z = document("https://schemas.example.test/z.json",
				"{\"allOf\":[true]}");

		McpSchemaResourceGraph first = compile(z, aFirstOrder);
		McpSchemaResourceGraph second = compile(aSecondOrder, z);

		Assertions.assertEquals(first, second);
		Assertions.assertEquals(List.of(
				"", "/$defs/a", "/$defs/b", "/properties/a", "/properties/z",
				"", "/allOf/0"),
				first.nodes().stream().map(node -> node.location().jsonPointer()).toList());
		Assertions.assertEquals(
				IntStream.range(0, 7).mapToObj(McpSchemaNodeId::new).toList(),
				first.nodes().stream().map(McpCompiledSchemaNode::id).toList());
		Assertions.assertEquals(List.of(new McpSchemaResourceId(0),
				new McpSchemaResourceId(1)),
				first.resources().stream().map(McpSchemaResource::id).toList());
		Assertions.assertEquals(new McpSchemaNodeId(0), first.documentRoots().get(
				URI.create("https://schemas.example.test/a.json")));
		Assertions.assertEquals(new McpSchemaNodeId(5), first.documentRoots().get(
				URI.create("https://schemas.example.test/z.json")));
	}

	private static McpSchemaResourceGraph compile(McpSchemaDocument... documents) {
		return new McpSchemaResourceGraphCompiler(COMPILATION_LIMITS)
				.compile(List.of(documents));
	}

	private static McpSchemaResourceGraph compile(
			McpSchemaDialectRegistry registry,
			McpSchemaDocument... documents) {
		return new McpSchemaResourceGraphCompiler(COMPILATION_LIMITS, registry)
				.compile(List.of(documents));
	}

	private static McpSchemaDialectRegistry registry(
			McpSchemaDocument... metaSchemas) {
		return new McpSchemaDialectRegistryCompiler(COMPILATION_LIMITS)
				.compile(List.of(metaSchemas));
	}

	private static McpSchemaDocument metaSchema(URI uri,
			McpSchemaVocabulary... vocabularies) {
		String vocabularyMembers = java.util.Arrays.stream(vocabularies)
				.map(vocabulary -> jsonString(vocabulary.uri().toASCIIString())
						+ ":true")
				.collect(java.util.stream.Collectors.joining(","));
		return document(uri, """
				{
				  "$schema":"https://json-schema.org/draft/2020-12/schema",
				  "$id":%s,
				  "$vocabulary":{%s}
				}
				""".formatted(jsonString(uri.toASCIIString()), vocabularyMembers));
	}

	private static McpSchemaDocument document(String retrievalUri, String schema) {
		return document(URI.create(retrievalUri), schema);
	}

	private static McpSchemaDocument document(URI retrievalUri, String schema) {
		return new McpSchemaDocument(retrievalUri, JSON_CODEC.parse(schema));
	}

	private static McpCompiledSchemaNode rootNode(McpSchemaResourceGraph graph,
			String retrievalUri) {
		return rootNode(graph, URI.create(retrievalUri));
	}

	private static McpCompiledSchemaNode rootNode(McpSchemaResourceGraph graph,
			URI retrievalUri) {
		return graph.node(graph.documentRoots().get(retrievalUri));
	}

	private static McpCompiledSchemaNode nodeAt(McpSchemaResourceGraph graph,
			String retrievalUri, String jsonPointer) {
		return nodeAt(graph, URI.create(retrievalUri), jsonPointer);
	}

	private static McpCompiledSchemaNode nodeAt(McpSchemaResourceGraph graph,
			URI retrievalUri, String jsonPointer) {
		return graph.nodes().stream()
				.filter(node -> node.location().retrievalUri().equals(retrievalUri))
				.filter(node -> node.location().jsonPointer().equals(jsonPointer))
				.findFirst()
				.orElseThrow(() -> new AssertionError(
						"No schema node exists at " + retrievalUri + "#" + jsonPointer));
	}

	private static void assertStaticTarget(McpSchemaResourceGraph graph,
			URI retrievalUri, String sourcePointer, McpSchemaNodeId expectedTarget) {
		McpSchemaReference reference = nodeAt(graph, retrievalUri, sourcePointer)
				.reference().orElseThrow();
		Assertions.assertEquals(McpSchemaReference.Kind.STATIC, reference.kind());
		Assertions.assertEquals(expectedTarget, reference.initialTargetNodeId());
		Assertions.assertTrue(reference.dynamicAnchorName().isEmpty());
	}

	private static McpSchemaCompilationException assertCompilationFails(
			McpSchemaCompilationException.Kind expectedKind, String schema) {
		McpSchemaCompilationException exception = Assertions.assertThrows(
				McpSchemaCompilationException.class,
				() -> compile(document(DEFAULT_RETRIEVAL_URI, schema)));
		Assertions.assertEquals(expectedKind, exception.kind(), schema);
		return exception;
	}

	private static String jsonString(String value) {
		return JSON_CODEC.toJson(new McpJsonString(value));
	}
}
