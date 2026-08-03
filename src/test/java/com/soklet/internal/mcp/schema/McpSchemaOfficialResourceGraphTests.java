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
import com.soklet.internal.mcp.protocol.McpJsonCodec;
import com.soklet.internal.mcp.protocol.McpJsonLimits;
import com.soklet.internal.mcp.protocol.McpJsonObject;
import com.soklet.internal.mcp.protocol.McpJsonValue;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Compiler-only evidence over pinned official schemas. Instance validity is
 * intentionally not asserted until the bounded evaluator exists.
 */
public class McpSchemaOfficialResourceGraphTests {
	private static final String SUITE_ROOT =
			"com/soklet/internal/mcp/schema/json-schema-test-suite/";
	private static final McpJsonCodec JSON_CODEC = new McpJsonCodec(
			new McpJsonLimits(2_000_000, 256, 1_000_000, 1_000_000,
					10_000, 100_000, 250_000, 2_000_000));
	private static final McpSchemaResourceGraphCompiler COMPILER =
			new McpSchemaResourceGraphCompiler(new McpSchemaCompilationLimits(
					32, 20_000, 256, 100_000, 2_000, 4_000,
					4_000, 20_000, 8_192, 1_024, 1_024));
	private static final List<String> REF_REMOTE_FIXTURES = List.of(
			"integer.json",
			"subSchemas.json",
			"locationIndependentIdentifier.json",
			"baseUriChange/folderInteger.json",
			"baseUriChangeFolder/folderInteger.json",
			"baseUriChangeFolderInSubschema/folderInteger.json",
			"name-defs.json",
			"ref-and-defs.json",
			"nested/foo-ref-string.json",
			"nested/string.json",
			"different-id-ref-string.json",
			"urn-ref-string.json",
			"nested-absolute-ref-to-string.json",
			"detached-ref.json");
	private static final List<String> DYNAMIC_REMOTE_FIXTURES = List.of(
			"tree.json",
			"extendible-dynamic-ref.json",
			"detached-dynamicref.json");
	private static final int OFFICIAL_METASCHEMA_REF_GROUP = 6;

	@Test
	public void compilesSelectedRequiredReferenceAndResourceGraphSchemas()
			throws IOException {
		int compiledGroups = 0;
		compiledGroups += compileGroups("ref.json", 36, 79, List.of(),
				OFFICIAL_METASCHEMA_REF_GROUP);
		compiledGroups += compileGroups("refRemote.json", 15, 31,
				REF_REMOTE_FIXTURES, -1);
		compiledGroups += compileGroups("anchor.json", 4, 8, List.of(), -1);
		compiledGroups += compileGroups("dynamicRef.json", 21, 44,
				DYNAMIC_REMOTE_FIXTURES, -1);
		compiledGroups += compileGroups("boolean_schema.json", 2, 18,
				List.of(), -1);

		Assertions.assertEquals(78, compiledGroups);
	}

	@Test
	public void officialMetaschemaDependenciesResolveFromPinnedOfflineBundle()
			throws IOException {
		for (MetaschemaSelection selection : List.of(
				new MetaschemaSelection("ref.json", OFFICIAL_METASCHEMA_REF_GROUP),
				new MetaschemaSelection("defs.json", 0))) {
			McpJsonArray groups = requiredGroups(selection.fileName());
			McpJsonValue schema = groupSchema(groups, selection.groupIndex());
			List<McpSchemaDocument> documents = new ArrayList<>(
					McpSchemaDraft202012Bundle.documents());
			URI retrievalUri = URI.create("https://soklet.invalid/official/metaschema/"
					+ selection.fileName() + "/" + selection.groupIndex());
			documents.add(new McpSchemaDocument(retrievalUri, schema));
			McpSchemaResourceGraph graph = COMPILER.compile(documents);

			Assertions.assertTrue(graph.resource(
					McpSchemaDialectRegistry.DRAFT_2020_12_URI).isPresent());
			McpCompiledSchemaNode testedRoot = graph.node(
					graph.documentRoots().get(retrievalUri));
			Assertions.assertEquals(McpSchemaDialectRegistry.DRAFT_2020_12_URI,
					testedRoot.reference().orElseThrow().resolvedUri());
		}
	}

	@Test
	public void compilesSupplementalRequiredSchemasCoveringRemainingSchemaLocations()
			throws IOException {
		List<GroupSelection> selections = List.of(
				new GroupSelection("additionalProperties.json", 7, 2, Set.of(
						"", "/additionalProperties", "/propertyNames")),
				new GroupSelection("content.json", 3, 8, Set.of(
						"", "/contentSchema", "/contentSchema/properties/foo")),
				new GroupSelection("patternProperties.json", 4, 1, Set.of(
						"", "/patternProperties/^.*bar$")),
				new GroupSelection("unevaluatedItems.json", 22, 2, Set.of(
						"", "/allOf/0", "/allOf/0/contains", "/allOf/1",
						"/allOf/1/contains", "/unevaluatedItems")),
				new GroupSelection("unevaluatedProperties.json", 17, 2, Set.of(
						"", "/dependentSchemas/foo",
						"/dependentSchemas/foo/properties/bar", "/properties/foo",
						"/unevaluatedProperties")),
				new GroupSelection("allOf.json", 11, 8, Set.of(
						"", "/allOf/0", "/anyOf/0", "/oneOf/0")));
		int instanceCaseCount = 0;

		for (GroupSelection selection : selections) {
			McpJsonArray groups = requiredGroups(selection.fileName());
			McpJsonObject group = (McpJsonObject) groups.values()
					.get(selection.groupIndex());
			McpJsonArray cases = (McpJsonArray) group.members().get("tests");
			Assertions.assertEquals(selection.expectedInstanceCaseCount(),
					cases.values().size(), selection.fileName());
			instanceCaseCount += cases.values().size();

			McpSchemaResourceGraph graph = COMPILER.compile(List.of(
					new McpSchemaDocument(URI.create("https://soklet.invalid/official/coverage/"
							+ selection.fileName() + "/" + selection.groupIndex()),
							group.members().get("schema"))));
			Set<String> actualPointers = graph.nodes().stream()
					.map(node -> node.location().jsonPointer())
					.collect(java.util.stream.Collectors.toCollection(
							LinkedHashSet::new));
			Assertions.assertEquals(selection.expectedPointers(), actualPointers,
					selection.fileName());
		}

		Assertions.assertEquals(6, selections.size());
		Assertions.assertEquals(23, instanceCaseCount);
	}

	@Test
	public void compilesBothRequiredCustomVocabularySchemasWithClosedDialects()
			throws IOException {
		McpSchemaDialectRegistry registry =
				new McpSchemaDialectRegistryCompiler(new McpSchemaCompilationLimits(
						32, 20_000, 256, 100_000, 2_000, 4_000,
						4_000, 20_000, 8_192, 1_024, 1_024)).compile(List.of(
						new McpSchemaDocument(URI.create(
								"http://localhost:1234/draft2020-12/metaschema-no-validation.json"),
								readJson("remotes/draft2020-12/"
										+ "metaschema-no-validation.json")),
						new McpSchemaDocument(URI.create(
								"http://localhost:1234/draft2020-12/metaschema-optional-vocabulary.json"),
								readJson("remotes/draft2020-12/"
										+ "metaschema-optional-vocabulary.json"))));
		McpJsonArray groups = requiredGroups("vocabulary.json");
		int instanceCaseCount = 0;

		for (int groupIndex = 0; groupIndex < groups.values().size(); ++groupIndex) {
			McpJsonObject group = (McpJsonObject) groups.values().get(groupIndex);
			instanceCaseCount += ((McpJsonArray) group.members().get("tests"))
					.values().size();
			URI retrievalUri = URI.create(
					"https://soklet.invalid/official/vocabulary-graph/" + groupIndex);
			McpSchemaResourceGraph graph = new McpSchemaResourceGraphCompiler(
					new McpSchemaCompilationLimits(32, 20_000, 256, 100_000,
							2_000, 4_000, 4_000, 20_000, 8_192, 1_024,
							1_024), registry).compile(List.of(
							new McpSchemaDocument(retrievalUri,
									group.members().get("schema"))));
			Assertions.assertFalse(graph.nodes().isEmpty());
			Assertions.assertNotEquals(
					McpSchemaDialectRegistry.DRAFT_2020_12_URI,
					graph.resource(graph.node(graph.documentRoots().get(retrievalUri))
							.resourceId()).dialectUri());
		}

		Assertions.assertEquals(2, groups.values().size());
		Assertions.assertEquals(5, instanceCaseCount);
	}

	private static int compileGroups(String fileName, int expectedGroupCount,
			int expectedInstanceCaseCount, List<String> remoteFixtures,
			int bundleBackedGroup) throws IOException {
		McpJsonArray groups = requiredGroups(fileName);
		Assertions.assertEquals(expectedGroupCount, groups.values().size(), fileName);
		int actualInstanceCaseCount = 0;
		int compiledGroupCount = 0;

		for (int groupIndex = 0; groupIndex < groups.values().size(); ++groupIndex) {
			McpJsonObject group = (McpJsonObject) groups.values().get(groupIndex);
			actualInstanceCaseCount += ((McpJsonArray) group.members().get("tests"))
					.values().size();
			List<McpSchemaDocument> documents = loadRemoteDocuments(remoteFixtures);
			if (groupIndex == bundleBackedGroup)
				documents.addAll(McpSchemaDraft202012Bundle.documents());
			String stem = fileName.substring(0, fileName.length() - ".json".length());
			documents.add(new McpSchemaDocument(URI.create(
					"https://soklet.invalid/official/" + stem + "/" + groupIndex),
					group.members().get("schema")));
			McpSchemaResourceGraph graph = COMPILER.compile(documents);
			Assertions.assertFalse(graph.nodes().isEmpty(),
					fileName + " group " + groupIndex);
			compiledGroupCount++;
		}

		Assertions.assertEquals(expectedInstanceCaseCount, actualInstanceCaseCount,
				fileName);
		return compiledGroupCount;
	}

	private static List<McpSchemaDocument> loadRemoteDocuments(
			List<String> relativePaths) throws IOException {
		List<McpSchemaDocument> documents = new ArrayList<>(relativePaths.size() + 1);

		for (String relativePath : relativePaths) {
			documents.add(new McpSchemaDocument(
					URI.create("http://localhost:1234/draft2020-12/" + relativePath),
					readJson("remotes/draft2020-12/" + relativePath)));
		}

		return documents;
	}

	private static McpJsonArray requiredGroups(String fileName) throws IOException {
		return (McpJsonArray) readJson("tests/draft2020-12/" + fileName);
	}

	private static McpJsonValue groupSchema(McpJsonArray groups, int index) {
		return ((McpJsonObject) groups.values().get(index)).members().get("schema");
	}

	private static McpJsonValue readJson(String relativePath) throws IOException {
		String resourceName = SUITE_ROOT + relativePath;
		try (InputStream input = McpSchemaOfficialResourceGraphTests.class
				.getClassLoader().getResourceAsStream(resourceName)) {
			Assertions.assertNotNull(input, resourceName);
			return JSON_CODEC.parse(input.readAllBytes());
		}
	}

	private record GroupSelection(String fileName, int groupIndex,
			int expectedInstanceCaseCount, Set<String> expectedPointers) {
	}

	private record MetaschemaSelection(String fileName, int groupIndex) {
	}
}
