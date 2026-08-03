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
import com.soklet.internal.mcp.protocol.McpJsonObject;
import com.soklet.internal.mcp.protocol.McpJsonString;
import com.soklet.internal.mcp.protocol.McpJsonValue;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class McpToolSchemaProfileManifestTests {
	private static final Set<String> EXPECTED_TOP_LEVEL_MEMBERS = Set.of(
			"manifestFormat", "profile", "profileVersion", "baseDialect",
			"rootKind", "booleanSubschemas", "unknownKeywordPolicy",
			"formatBehavior", "supportedKeywords", "keywordShapes",
			"explicitlyRejectedKeywords", "referencePolicy",
			"jsonSchemaTestSuite", "officialMcpScenario");
	private static final Map<String, String> EXPECTED_KEYWORD_SHAPES =
			Map.ofEntries(
					Map.entry("$anchor", "unique-plain-name-anchor"),
					Map.entry("$comment", "string-annotation"),
					Map.entry("$defs", "schema-map"),
					Map.entry("$ref", "local-reference"),
					Map.entry("$schema", "root-canonical-dialect"),
					Map.entry("additionalProperties", "boolean-or-schema"),
					Map.entry("allOf", "nonempty-schema-array"),
					Map.entry("anyOf", "nonempty-schema-array"),
					Map.entry("const", "json-value"),
					Map.entry("default", "json-value-annotation"),
					Map.entry("deprecated", "boolean-annotation"),
					Map.entry("description", "string-annotation"),
					Map.entry("else", "schema"),
					Map.entry("enum", "json-array"),
					Map.entry("examples", "json-array-annotation"),
					Map.entry("format", "string-nonasserting-annotation"),
					Map.entry("if", "schema"),
					Map.entry("items", "boolean-or-schema"),
					Map.entry("maximum", "json-number"),
					Map.entry("minimum", "json-number"),
					Map.entry("properties", "property-schema-map"),
					Map.entry("readOnly", "boolean-annotation"),
					Map.entry("required", "unique-string-array"),
					Map.entry("then", "schema"),
					Map.entry("title", "string-annotation"),
					Map.entry("type",
							"simple-type-or-nonempty-unique-type-array"),
					Map.entry("writeOnly", "boolean-annotation"),
					Map.entry("x-mcp-header",
							"statically-reachable-primitive-header-name"));
	private static final Set<String> EXPECTED_CHECK_IDS = Set.of(
			"json-schema-2020-12-tool-found",
			"json-schema-2020-12-$schema",
			"json-schema-2020-12-$defs",
			"json-schema-2020-12-additionalProperties",
			"sep-2106-composition-keywords-preserved",
			"sep-2106-conditional-keywords-preserved",
			"sep-2106-anchor-keyword-preserved");
	private static final String EXPECTED_FIXTURE_SHA256 =
			"172e598d4345d7688bafa08e35addf26d6b16cb50db1a36adf6e0352470fd6bc";
	private static final String EXPECTED_CASES_SHA256 =
			"879ed2eacaf208555a2db9f23e339401978c2dd0a5db131edf34f391111e02a2";

	@Test
	public void checkedInManifestExactlyMatchesTheCompilerAndProfilePolicy()
			throws IOException {
		McpToolSchemaProfileTestManifest.Manifest manifest =
				McpToolSchemaProfileTestManifest.load();

		Assertions.assertEquals(EXPECTED_TOP_LEVEL_MEMBERS,
				manifest.document().members().keySet());
		Assertions.assertEquals(1, manifest.manifestFormat());
		Assertions.assertEquals("soklet-mcp-tool-schema-profile-1",
				manifest.profile());
		Assertions.assertEquals(1, manifest.profileVersion());
		Assertions.assertEquals(McpToolSchemaProfileCompiler.DRAFT_2020_12_URI,
				manifest.baseDialect());
		Assertions.assertEquals("object", manifest.rootKind());
		Assertions.assertTrue(manifest.booleanSubschemas());
		Assertions.assertEquals("reject", manifest.unknownKeywordPolicy());
		Assertions.assertEquals("annotation-only", manifest.formatBehavior());
		Assertions.assertEquals(McpToolSchemaProfileCompiler.supportedKeywords(),
				manifest.supportedKeywords());
		Assertions.assertEquals(EXPECTED_KEYWORD_SHAPES,
				manifest.keywordShapes());
		Assertions.assertEquals(
				McpToolSchemaProfileCompiler.explicitlyRejectedKeywords(),
				manifest.explicitlyRejectedKeywords());
		Assertions.assertTrue(java.util.Collections.disjoint(
				manifest.supportedKeywords(),
				manifest.explicitlyRejectedKeywords()));

		McpToolSchemaProfileTestManifest.ReferencePolicy references =
				manifest.referencePolicy();
		Assertions.assertEquals(Set.of("#", "json-pointer-fragment",
				"plain-name-anchor-fragment"), references.localForms());
		Assertions.assertEquals("schema-bearing-locations-only",
				references.targetPolicy());
		Assertions.assertFalse(references.pointerIntoAnnotationData());
		Assertions.assertFalse(references.externalReferences());
		Assertions.assertFalse(references.multipleDocuments());
		Assertions.assertEquals(Set.of("document-root", "$defs-member",
				"properties-member", "additionalProperties", "items",
				"allOf-member", "anyOf-member", "if", "then", "else"),
				references.schemaBearingLocations());
	}

	@Test
	public void strictReaderRejectsUnexpectedMembersAtEveryObjectLevel()
			throws IOException {
		McpJsonObject document = rawManifest();
		assertManifestRejected(withUnexpectedMember(document), "manifest root");

		McpJsonObject references = object(
				document.members().get("referencePolicy"));
		assertManifestRejected(withMember(document, "referencePolicy",
				withUnexpectedMember(references)), "referencePolicy");

		McpJsonObject suite = object(
				document.members().get("jsonSchemaTestSuite"));
		assertManifestRejected(withMember(document, "jsonSchemaTestSuite",
				withUnexpectedMember(suite)), "jsonSchemaTestSuite");

		McpJsonObject scenario = object(
				document.members().get("officialMcpScenario"));
		assertManifestRejected(withMember(document, "officialMcpScenario",
				withUnexpectedMember(scenario)), "officialMcpScenario");

		McpJsonArray selections = array(suite.members().get("selections"));
		List<McpJsonValue> changedSelections =
				new ArrayList<>(selections.values());
		changedSelections.set(0, withUnexpectedMember(
				object(changedSelections.get(0))));
		McpJsonObject changedSuite = withMember(suite, "selections",
				new McpJsonArray(changedSelections));
		assertManifestRejected(withMember(document, "jsonSchemaTestSuite",
				changedSuite), "suite selection");
	}

	@Test
	public void selectionPathsMustUsePortableNormalizedForm() {
		for (String valid : List.of("ref.json", "optional/anchor.json",
				"optional/format/unknown.json"))
			Assertions.assertEquals(valid,
					McpToolSchemaProfileTestManifest
							.requireNormalizedSelectionPath(valid));

		for (String invalid : List.of("./ref.json", "optional/./anchor.json",
				"optional/x/../anchor.json", "../ref.json", "/ref.json",
				"optional//anchor.json", "optional\\anchor.json", "C:/ref.json",
				"ref.json/"))
			Assertions.assertThrows(IllegalStateException.class,
					() -> McpToolSchemaProfileTestManifest
							.requireNormalizedSelectionPath(invalid), invalid);
	}

	@Test
	public void manifestClassifiesEveryReviewedGroupAndPinsExactTotals()
			throws IOException {
		McpToolSchemaProfileTestManifest.Suite suite =
				McpToolSchemaProfileTestManifest.load().suite();
		Assertions.assertEquals(
				"https://github.com/json-schema-org/JSON-Schema-Test-Suite",
				suite.repository());
		Assertions.assertEquals(
				"0c7b65dc16dd8eaa7bd83e21099c76610c3b246a",
				suite.commit());
		Assertions.assertEquals(23, suite.selections().size());
		Assertions.assertEquals(189, suite.expectedClassifiedGroupCount());
		Assertions.assertEquals(657, suite.expectedClassifiedCaseCount());
		Assertions.assertEquals(133, suite.expectedSelectedGroupCount());
		Assertions.assertEquals(500, suite.expectedSelectedCaseCount());
		Assertions.assertEquals(56, suite.expectedRejectedGroupCount());
		Assertions.assertEquals(157, suite.expectedRejectedCaseCount());

		McpJsonObject pin = object(
				McpToolSchemaProfileTestManifest.readJson(suite.pinResource()));
		Assertions.assertEquals(suite.repository(), string(pin, "repository"));
		Assertions.assertEquals(suite.commit(), string(pin, "commit"));

		Set<String> files = new LinkedHashSet<>();
		int classifiedGroups = 0;
		int classifiedCases = 0;
		int selectedGroups = 0;
		int selectedCases = 0;
		int rejectedGroups = 0;
		int rejectedCases = 0;
		for (McpToolSchemaProfileTestManifest.Selection selection
				: suite.selections()) {
			Assertions.assertTrue(files.add(selection.file()), selection.file());
			Assertions.assertEquals(selection.file(),
					McpToolSchemaProfileTestManifest
							.requireNormalizedSelectionPath(selection.file()));
			McpJsonArray groups = array(
					McpToolSchemaProfileTestManifest.readJson(
							suite.testsRoot() + selection.file()));
			Assertions.assertEquals(selection.sourceGroupCount(),
					groups.values().size(), selection.file());
			int sourceCases = caseCount(groups, indexes(groups.values().size()));
			Assertions.assertEquals(selection.sourceCaseCount(), sourceCases,
					selection.file());

			assertAscending(selection.selectedGroupIndexes(), selection.file());
			assertAscending(selection.rejectedGroupIndexes(), selection.file());
			Set<Integer> classified = new LinkedHashSet<>(
					selection.selectedGroupIndexes());
			for (int rejected : selection.rejectedGroupIndexes())
				Assertions.assertTrue(classified.add(rejected),
						selection.file() + " group " + rejected);
			Assertions.assertEquals(new LinkedHashSet<>(
					indexes(groups.values().size())), classified, selection.file());

			int fileSelectedCases = caseCount(groups,
					selection.selectedGroupIndexes());
			Assertions.assertEquals(selection.selectedCaseCount(),
					fileSelectedCases, selection.file());
			int fileRejectedCases = caseCount(groups,
					selection.rejectedGroupIndexes());
			Assertions.assertEquals(sourceCases,
					fileSelectedCases + fileRejectedCases, selection.file());

			classifiedGroups += groups.values().size();
			classifiedCases += sourceCases;
			selectedGroups += selection.selectedGroupIndexes().size();
			selectedCases += fileSelectedCases;
			rejectedGroups += selection.rejectedGroupIndexes().size();
			rejectedCases += fileRejectedCases;
		}

		Assertions.assertEquals(suite.expectedClassifiedGroupCount(),
				classifiedGroups);
		Assertions.assertEquals(suite.expectedClassifiedCaseCount(),
				classifiedCases);
		Assertions.assertEquals(suite.expectedSelectedGroupCount(),
				selectedGroups);
		Assertions.assertEquals(suite.expectedSelectedCaseCount(), selectedCases);
		Assertions.assertEquals(suite.expectedRejectedGroupCount(),
				rejectedGroups);
		Assertions.assertEquals(suite.expectedRejectedCaseCount(), rejectedCases);

		McpToolSchemaProfileTestManifest.Selection annotationPointer =
				suite.selections().stream()
						.filter(selection -> selection.file().equals(
								"optional/refOfUnknownKeyword.json"))
						.findFirst().orElseThrow();
		Assertions.assertEquals(List.of(),
				annotationPointer.selectedGroupIndexes());
		Assertions.assertEquals(List.of(0, 1, 2, 3, 4),
				annotationPointer.rejectedGroupIndexes());
	}

	@Test
	public void officialFixtureCasesAndCheckIdsArePinnedExactly()
			throws IOException, NoSuchAlgorithmException {
		McpToolSchemaProfileTestManifest.OfficialScenario scenario =
				McpToolSchemaProfileTestManifest.load().officialScenario();
		Assertions.assertEquals("json-schema-2020-12", scenario.name());
		Assertions.assertEquals(
				"49103de6ed70804e940637bf3e9e29e4a3f54e64",
				scenario.conformanceCommit());
		Assertions.assertEquals("src/scenarios/server/json-schema-2020-12.ts",
				scenario.sourcePath());
		Assertions.assertEquals(EXPECTED_CHECK_IDS,
				scenario.expectedSuccessfulCheckIds());
		Assertions.assertEquals(EXPECTED_FIXTURE_SHA256,
				scenario.fixtureSha256());
		Assertions.assertEquals(EXPECTED_CASES_SHA256, scenario.casesSha256());
		Assertions.assertEquals(EXPECTED_FIXTURE_SHA256,
				sha256(McpToolSchemaProfileTestManifest.readBytes(
						McpToolSchemaProfileTestManifest.PROFILE_ROOT
								+ scenario.fixture())));
		Assertions.assertEquals(EXPECTED_CASES_SHA256,
				sha256(McpToolSchemaProfileTestManifest.readBytes(
						McpToolSchemaProfileTestManifest.PROFILE_ROOT
								+ scenario.cases())));

		McpJsonObject tool = object(McpToolSchemaProfileTestManifest.readJson(
				McpToolSchemaProfileTestManifest.PROFILE_ROOT + scenario.fixture()));
		Assertions.assertEquals("json_schema_2020_12_tool",
				string(tool, "name"));
		Assertions.assertEquals("Tool with JSON Schema 2020-12 features",
				string(tool, "description"));
		Assertions.assertInstanceOf(McpJsonObject.class,
				tool.members().get("inputSchema"));

		McpJsonObject cases = object(McpToolSchemaProfileTestManifest.readJson(
				McpToolSchemaProfileTestManifest.PROFILE_ROOT + scenario.cases()));
		Assertions.assertEquals(scenario.expectedValidCaseCount(),
				array(cases.members().get("valid")).values().size());
		Assertions.assertEquals(scenario.expectedInvalidCaseCount(),
				array(cases.members().get("invalid")).values().size());
		Assertions.assertEquals(5, scenario.expectedValidCaseCount());
		Assertions.assertEquals(8, scenario.expectedInvalidCaseCount());
	}

	private static int caseCount(McpJsonArray groups, List<Integer> indexes) {
		int count = 0;
		for (int index : indexes) {
			Assertions.assertTrue(index >= 0 && index < groups.values().size(),
					"group " + index);
			McpJsonObject group = object(groups.values().get(index));
			count += array(group.members().get("tests")).values().size();
		}
		return count;
	}

	private static List<Integer> indexes(int count) {
		List<Integer> indexes = new ArrayList<>(count);
		for (int index = 0; index < count; ++index)
			indexes.add(index);
		return List.copyOf(indexes);
	}

	private static void assertAscending(List<Integer> indexes, String file) {
		int previous = -1;
		for (int index : indexes) {
			Assertions.assertTrue(index > previous,
					file + " group indexes must be strictly ascending");
			previous = index;
		}
	}

	private static McpJsonObject rawManifest() throws IOException {
		return object(McpToolSchemaProfileTestManifest.readJson(
				McpToolSchemaProfileTestManifest.PROFILE_ROOT + "manifest.json"));
	}

	private static McpJsonObject withUnexpectedMember(McpJsonObject source) {
		return withMember(source, "unexpected", new McpJsonString("unexpected"));
	}

	private static McpJsonObject withMember(McpJsonObject source, String name,
			McpJsonValue value) {
		Map<String, McpJsonValue> members =
				new LinkedHashMap<>(source.members());
		members.put(name, value);
		return new McpJsonObject(members);
	}

	private static void assertManifestRejected(McpJsonObject document,
			String location) {
		Assertions.assertThrows(IllegalStateException.class,
				() -> McpToolSchemaProfileTestManifest.parse(document), location);
	}

	private static String sha256(byte[] bytes)
			throws NoSuchAlgorithmException {
		return HexFormat.of().formatHex(
				MessageDigest.getInstance("SHA-256").digest(bytes));
	}

	private static McpJsonObject object(McpJsonValue value) {
		return Assertions.assertInstanceOf(McpJsonObject.class, value);
	}

	private static McpJsonArray array(McpJsonValue value) {
		return Assertions.assertInstanceOf(McpJsonArray.class, value);
	}

	private static String string(McpJsonObject object, String name) {
		return Assertions.assertInstanceOf(McpJsonString.class,
				object.members().get(name)).value();
	}
}
