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
import com.soklet.internal.mcp.protocol.McpJsonCodec;
import com.soklet.internal.mcp.protocol.McpJsonLimits;
import com.soklet.internal.mcp.protocol.McpJsonNumber;
import com.soklet.internal.mcp.protocol.McpJsonObject;
import com.soklet.internal.mcp.protocol.McpJsonString;
import com.soklet.internal.mcp.protocol.McpJsonValue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Strict reader for the checked-in Profile 1 test-evidence manifest. */
final class McpToolSchemaProfileTestManifest {
	static final String PROFILE_ROOT =
			"/com/soklet/internal/mcp/schema/profile-1/";
	private static final Set<String> MANIFEST_MEMBERS = Set.of(
			"manifestFormat", "profile", "profileVersion", "baseDialect",
			"rootKind", "booleanSubschemas", "unknownKeywordPolicy",
			"formatBehavior", "supportedKeywords", "keywordShapes",
			"explicitlyRejectedKeywords", "referencePolicy",
			"jsonSchemaTestSuite", "officialMcpScenario");
	private static final Set<String> REFERENCE_POLICY_MEMBERS = Set.of(
			"localForms", "targetPolicy", "schemaBearingLocations",
			"pointerIntoAnnotationData", "externalReferences",
			"multipleDocuments");
	private static final Set<String> SUITE_MEMBERS = Set.of(
			"repository", "commit", "pinResource", "testsRoot",
			"expectedClassifiedGroupCount", "expectedClassifiedCaseCount",
			"expectedSelectedGroupCount", "expectedSelectedCaseCount",
			"expectedRejectedGroupCount", "expectedRejectedCaseCount",
			"selections");
	private static final Set<String> SELECTION_MEMBERS = Set.of(
			"file", "sourceGroupCount", "sourceCaseCount",
			"selectedGroupIndexes", "selectedCaseCount",
			"rejectedGroupIndexes");
	private static final Set<String> OFFICIAL_SCENARIO_MEMBERS = Set.of(
			"name", "conformanceCommit", "sourcePath", "fixture",
			"fixtureSha256", "cases", "casesSha256",
			"expectedValidCaseCount", "expectedInvalidCaseCount",
			"expectedSuccessfulCheckIds");
	private static final McpJsonCodec JSON_CODEC = new McpJsonCodec(
			new McpJsonLimits(2_000_000, 256, 1_000_000, 1_000_000,
					4_096, 100_000, 250_000, 2_000_000));

	private McpToolSchemaProfileTestManifest() {
	}

	static Manifest load() throws IOException {
		McpJsonObject document = object(readJson(PROFILE_ROOT + "manifest.json"),
				"manifest root");
		return parse(document);
	}

	static Manifest parse(McpJsonObject document) {
		requireExactMembers(document, MANIFEST_MEMBERS, "manifest root");
		McpJsonObject references = object(member(document, "referencePolicy"),
				"referencePolicy");
		McpJsonObject suite = object(member(document, "jsonSchemaTestSuite"),
				"jsonSchemaTestSuite");
		McpJsonObject scenario = object(member(document, "officialMcpScenario"),
				"officialMcpScenario");
		requireExactMembers(references, REFERENCE_POLICY_MEMBERS,
				"referencePolicy");
		requireExactMembers(suite, SUITE_MEMBERS, "jsonSchemaTestSuite");
		requireExactMembers(scenario, OFFICIAL_SCENARIO_MEMBERS,
				"officialMcpScenario");

		List<Selection> selections = new ArrayList<>();
		for (McpJsonValue value : array(suite, "selections").values()) {
			McpJsonObject selection = object(value, "suite selection");
			requireExactMembers(selection, SELECTION_MEMBERS, "suite selection");
			selections.add(new Selection(
					requireNormalizedSelectionPath(string(selection, "file")),
					integer(selection, "sourceGroupCount"),
					integer(selection, "sourceCaseCount"),
					integers(selection, "selectedGroupIndexes"),
					integer(selection, "selectedCaseCount"),
					integers(selection, "rejectedGroupIndexes")));
		}

		return new Manifest(document,
				integer(document, "manifestFormat"),
				string(document, "profile"),
				integer(document, "profileVersion"),
				string(document, "baseDialect"),
				string(document, "rootKind"),
				bool(document, "booleanSubschemas"),
				string(document, "unknownKeywordPolicy"),
				string(document, "formatBehavior"),
				strings(document, "supportedKeywords"),
				stringMap(document, "keywordShapes"),
				strings(document, "explicitlyRejectedKeywords"),
				new ReferencePolicy(
						strings(references, "localForms"),
						string(references, "targetPolicy"),
						strings(references, "schemaBearingLocations"),
						bool(references, "pointerIntoAnnotationData"),
						bool(references, "externalReferences"),
						bool(references, "multipleDocuments")),
				new Suite(
						string(suite, "repository"),
						string(suite, "commit"),
						string(suite, "pinResource"),
						string(suite, "testsRoot"),
						integer(suite, "expectedClassifiedGroupCount"),
						integer(suite, "expectedClassifiedCaseCount"),
						integer(suite, "expectedSelectedGroupCount"),
						integer(suite, "expectedSelectedCaseCount"),
						integer(suite, "expectedRejectedGroupCount"),
						integer(suite, "expectedRejectedCaseCount"),
						selections),
				new OfficialScenario(
						string(scenario, "name"),
						string(scenario, "conformanceCommit"),
						string(scenario, "sourcePath"),
						string(scenario, "fixture"),
						string(scenario, "fixtureSha256"),
						string(scenario, "cases"),
						string(scenario, "casesSha256"),
						integer(scenario, "expectedValidCaseCount"),
						integer(scenario, "expectedInvalidCaseCount"),
						strings(scenario, "expectedSuccessfulCheckIds")));
	}

	static String requireNormalizedSelectionPath(String path) {
		if (path == null)
			throw new IllegalStateException("A suite selection path is required.");
		Path parsed;
		try {
			parsed = Path.of(path);
		} catch (InvalidPathException exception) {
			throw new IllegalStateException(
					"A suite selection path must be a portable relative path.",
					exception);
		}
		String normalized = portablePath(parsed.normalize());
		if (parsed.isAbsolute() || path.startsWith("/") || path.contains("\\")
				|| path.contains("//") || !path.endsWith(".json")
				|| hasDotSegment(parsed) || !portableSelectionCharacters(path)
				|| !path.equals(normalized))
			throw new IllegalStateException(
					"A suite selection path must be a normalized portable relative JSON path: "
							+ path);
		return path;
	}

	private static boolean hasDotSegment(Path path) {
		for (Path element : path) {
			String name = element.toString();
			if (name.equals(".") || name.equals(".."))
				return true;
		}
		return false;
	}

	private static boolean portableSelectionCharacters(String path) {
		for (int index = 0; index < path.length(); ++index) {
			char character = path.charAt(index);
			if (!(character >= 'A' && character <= 'Z')
					&& !(character >= 'a' && character <= 'z')
					&& !(character >= '0' && character <= '9')
					&& character != '.' && character != '_'
					&& character != '-' && character != '/')
				return false;
		}
		return true;
	}

	static McpJsonValue readJson(String resource) throws IOException {
		return JSON_CODEC.parse(readBytes(resource));
	}

	static byte[] readBytes(String resource) throws IOException {
		try (InputStream input = McpToolSchemaProfileTestManifest.class
				.getResourceAsStream(resource)) {
			if (input == null)
				throw new IOException("Missing test resource " + resource + ".");
			return input.readAllBytes();
		}
	}

	private static McpJsonValue member(McpJsonObject object, String name) {
		McpJsonValue value = object.members().get(name);
		if (value == null)
			throw new IllegalStateException("Missing manifest member " + name + ".");
		return value;
	}

	private static void requireExactMembers(McpJsonObject object,
			Set<String> expected, String name) {
		if (!object.members().keySet().equals(expected))
			throw new IllegalStateException(name
					+ " must contain exactly the reviewed member set.");
	}

	private static String portablePath(Path path) {
		List<String> elements = new ArrayList<>();
		path.forEach(element -> elements.add(element.toString()));
		return String.join("/", elements);
	}

	private static McpJsonObject object(McpJsonValue value, String name) {
		if (!(value instanceof McpJsonObject object))
			throw new IllegalStateException(name + " must be an object.");
		return object;
	}

	private static McpJsonArray array(McpJsonObject object, String name) {
		McpJsonValue value = member(object, name);
		if (!(value instanceof McpJsonArray array))
			throw new IllegalStateException(name + " must be an array.");
		return array;
	}

	private static String string(McpJsonObject object, String name) {
		McpJsonValue value = member(object, name);
		if (!(value instanceof McpJsonString string))
			throw new IllegalStateException(name + " must be a string.");
		return string.value();
	}

	private static boolean bool(McpJsonObject object, String name) {
		McpJsonValue value = member(object, name);
		if (!(value instanceof McpJsonBoolean bool))
			throw new IllegalStateException(name + " must be a boolean.");
		return bool == McpJsonBoolean.TRUE;
	}

	private static int integer(McpJsonObject object, String name) {
		McpJsonValue value = member(object, name);
		if (!(value instanceof McpJsonNumber number))
			throw new IllegalStateException(name + " must be an integer.");
		try {
			return number.value().intValueExact();
		} catch (ArithmeticException exception) {
			throw new IllegalStateException(name + " must be an exact int.",
					exception);
		}
	}

	private static List<Integer> integers(McpJsonObject object, String name) {
		List<Integer> result = new ArrayList<>();
		Set<Integer> unique = new LinkedHashSet<>();
		for (McpJsonValue value : array(object, name).values()) {
			if (!(value instanceof McpJsonNumber number))
				throw new IllegalStateException(name + " must contain integers.");
			int integer;
			try {
				integer = number.value().intValueExact();
			} catch (ArithmeticException exception) {
				throw new IllegalStateException(name + " must contain exact ints.",
						exception);
			}
			if (!unique.add(integer))
				throw new IllegalStateException(name + " contains a duplicate.");
			result.add(integer);
		}
		return List.copyOf(result);
	}

	private static Set<String> strings(McpJsonObject object, String name) {
		Set<String> result = new LinkedHashSet<>();
		for (McpJsonValue value : array(object, name).values()) {
			if (!(value instanceof McpJsonString string))
				throw new IllegalStateException(name + " must contain strings.");
			if (!result.add(string.value()))
				throw new IllegalStateException(name + " contains a duplicate.");
		}
		return Set.copyOf(result);
	}

	private static Map<String, String> stringMap(McpJsonObject object,
			String name) {
		McpJsonObject source = object(member(object, name), name);
		Map<String, String> result = new LinkedHashMap<>();
		for (Map.Entry<String, McpJsonValue> entry : source.members().entrySet()) {
			if (!(entry.getValue() instanceof McpJsonString string))
				throw new IllegalStateException(name + " values must be strings.");
			result.put(entry.getKey(), string.value());
		}
		return Map.copyOf(result);
	}

	record Manifest(McpJsonObject document, int manifestFormat, String profile,
			int profileVersion, String baseDialect, String rootKind,
			boolean booleanSubschemas, String unknownKeywordPolicy,
			String formatBehavior, Set<String> supportedKeywords,
			Map<String, String> keywordShapes,
			Set<String> explicitlyRejectedKeywords,
			ReferencePolicy referencePolicy, Suite suite,
			OfficialScenario officialScenario) {
		Manifest {
			supportedKeywords = Set.copyOf(supportedKeywords);
			keywordShapes = Map.copyOf(keywordShapes);
			explicitlyRejectedKeywords = Set.copyOf(explicitlyRejectedKeywords);
		}
	}

	record ReferencePolicy(Set<String> localForms, String targetPolicy,
			Set<String> schemaBearingLocations,
			boolean pointerIntoAnnotationData, boolean externalReferences,
			boolean multipleDocuments) {
		ReferencePolicy {
			localForms = Set.copyOf(localForms);
			schemaBearingLocations = Set.copyOf(schemaBearingLocations);
		}
	}

	record Suite(String repository, String commit, String pinResource,
			String testsRoot, int expectedClassifiedGroupCount,
			int expectedClassifiedCaseCount, int expectedSelectedGroupCount,
			int expectedSelectedCaseCount, int expectedRejectedGroupCount,
			int expectedRejectedCaseCount, List<Selection> selections) {
		Suite {
			selections = List.copyOf(selections);
		}
	}

	record Selection(String file, int sourceGroupCount, int sourceCaseCount,
			List<Integer> selectedGroupIndexes, int selectedCaseCount,
			List<Integer> rejectedGroupIndexes) {
		Selection {
			selectedGroupIndexes = List.copyOf(selectedGroupIndexes);
			rejectedGroupIndexes = List.copyOf(rejectedGroupIndexes);
		}
	}

	record OfficialScenario(String name, String conformanceCommit,
			String sourcePath, String fixture, String fixtureSha256, String cases,
			String casesSha256, int expectedValidCaseCount,
			int expectedInvalidCaseCount,
			Set<String> expectedSuccessfulCheckIds) {
		OfficialScenario {
			expectedSuccessfulCheckIds = Set.copyOf(expectedSuccessfulCheckIds);
		}
	}
}
