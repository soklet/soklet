/*
 * Copyright 2022-2026 Revetware LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.soklet.internal.mcp.protocol;

import com.soklet.McpLocalizer;
import com.soklet.McpEndpoint;
import com.soklet.McpEndpointRegistry;
import com.soklet.McpImplementation;
import com.soklet.McpServer;
import com.soklet.McpSimulationOptions;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class McpLimitsAndAccountingTests {
	private static final Path DECISIONS = Path.of(
			"conformance", "mcp-limits-and-accounting.json");
	private static final List<String> CROSS_JDK_GATES = List.of(
			"candidate-build", "core-jdk-21", "core-jdk-25");

	@Test
	public void decisionsHaveIndependentExactLiveValueAndUnitParity()
			throws Exception {
		McpJsonObject document = object(new McpJsonCodec(
				McpJsonLimits.productionDefaults()).parse(
				Files.readAllBytes(DECISIONS)));
		Assertions.assertEquals(Set.of("decisions", "formatVersion",
				"numericBoundsAuthority", "productionProfile", "releaseTarget"),
				document.members().keySet());
		Assertions.assertEquals(BigDecimal.ONE,
				Assertions.assertInstanceOf(McpJsonNumber.class,
						document.members().get("formatVersion")).value());
		Assertions.assertEquals("2026-07-28",
				string(document, "productionProfile"));
		Assertions.assertEquals("4.0.0", string(document, "releaseTarget"));

		McpJsonObject authority = object(
				document.members().get("numericBoundsAuthority"));
		Assertions.assertEquals(Set.of("path", "sha256"),
				authority.members().keySet());
		Assertions.assertEquals("../mcp/PROFILE_1_NUMERIC_BOUNDS.md",
				string(authority, "path"));
		Assertions.assertEquals(
				"9477f26dd0d2bbc2f790b8428dd5ad5de7f9d672ba152cfd33fbbf0ae6a78b70",
				string(authority, "sha256"));

		Set<String> knownBoundIds = finiteBoundIds();
		Set<String> decisionIds = new HashSet<>();
		List<String> orderedDecisionIds = new ArrayList<>();
		Set<String> topics = new HashSet<>();
		for (McpJsonValue value : array(document, "decisions").values()) {
			McpJsonObject decision = object(value);
			Assertions.assertEquals(Set.of("accounting", "adversarialTests",
					"boundaryTests", "crossJdkGates", "finiteBoundIds", "id",
					"productionOwners", "rationale", "topic", "values"),
					decision.members().keySet());
			String decisionId = string(decision, "id");
			Assertions.assertTrue(decisionIds.add(decisionId));
			orderedDecisionIds.add(decisionId);
			String topic = string(decision, "topic");
			Assertions.assertTrue(topics.add(topic));
			Assertions.assertFalse(string(decision, "rationale").isBlank());
			assertNonblankStrings(array(decision, "accounting"));
			assertEvidencePaths(array(decision, "adversarialTests"));
			assertEvidencePaths(array(decision, "boundaryTests"));
			Assertions.assertEquals(CROSS_JDK_GATES,
					strings(array(decision, "crossJdkGates")));
			List<String> decisionBoundIds = strings(
					array(decision, "finiteBoundIds"));
			assertStrictlySorted(decisionBoundIds, decisionId + " finite-bound IDs");
			for (String boundId : decisionBoundIds)
				Assertions.assertTrue(knownBoundIds.contains(boundId), boundId);
			assertOwners(array(decision, "productionOwners"));
			Assertions.assertEquals(expectedValues(topic),
					values(array(decision, "values")), topic);
		}

		Assertions.assertEquals(Set.of("LIMITS-CURSOR-ADJACENT-OUTPUT",
				"LIMITS-NUMERIC-PARSER", "LIMITS-SCHEMA-COMPILER-EVALUATOR",
				"LIMITS-SERIALIZED-RESULT", "LIMITS-TRANSPORT",
				"LIMITS-URI-TEMPLATE"), decisionIds);
		Assertions.assertEquals(Set.of("CURSOR_ADJACENT_OUTPUT", "NUMERIC_PARSER",
				"SCHEMA_COMPILER_EVALUATOR", "SERIALIZED_RESULT", "TRANSPORT",
				"URI_TEMPLATE"), topics);
		assertStrictlySorted(orderedDecisionIds, "decision IDs");
	}

	private static Map<String, Bound> expectedValues(String topic)
			throws Exception {
		return switch (topic) {
			case "URI_TEMPLATE" -> uriTemplateValues();
			case "TRANSPORT" -> transportValues();
			case "CURSOR_ADJACENT_OUTPUT" -> cursorOutputValues();
			case "NUMERIC_PARSER" -> numericParserValues();
			case "SCHEMA_COMPILER_EVALUATOR" -> schemaValues();
			case "SERIALIZED_RESULT" -> serializedResultValues();
			default -> throw new AssertionError("Unknown decision topic " + topic);
		};
	}

	private static Map<String, Bound> uriTemplateValues() throws Exception {
		Map<String, Bound> values = new LinkedHashMap<>();
		put(values, "request-target-bytes", "ASCII_BYTES",
				McpEndpointPathLimit.MAXIMUM_REQUEST_TARGET_BYTES);
		put(values, "runtime-exact-uri-bytes", "UTF8_BYTES",
				McpLevelOneUriTemplate.MAXIMUM_RESOURCE_URI_UTF_8_BYTES);
		put(values, "annotated-exact-uri-bytes", "UTF8_BYTES", staticNumber(
				"com.soklet.SokletProcessor",
				"MAXIMUM_MCP_ANNOTATED_RESOURCE_URI_UTF_8_BYTES"));
		put(values, "template-routed-uri-bytes", "UTF8_BYTES",
				McpLevelOneUriTemplate.MAXIMUM_TEMPLATE_ROUTED_RESOURCE_URI_UTF_8_BYTES);
		put(values, "templates-per-endpoint", "TEMPLATES",
				McpLevelOneUriTemplate.MAXIMUM_TEMPLATE_COUNT_PER_ENDPOINT);
		put(values, "template-source-or-expansion-bytes", "UTF8_BYTES",
				McpLevelOneUriTemplate.MAXIMUM_TEMPLATE_UTF_8_BYTES);
		put(values, "variables-per-template", "VARIABLES",
				McpLevelOneUriTemplate.MAXIMUM_VARIABLE_COUNT);
		put(values, "variable-name-bytes", "UTF8_BYTES",
				McpLevelOneUriTemplate.MAXIMUM_VARIABLE_NAME_UTF_8_BYTES);
		put(values, "match-dynamic-programming-cells", "CELLS_PER_REQUEST",
				McpLevelOneUriTemplate.MAXIMUM_TEMPLATE_MATCH_DYNAMIC_PROGRAMMING_CELLS);
		put(values, "overlap-states-per-pair", "STATES_PER_PAIR",
				McpLevelOneUriTemplate.MAXIMUM_OVERLAP_COMPARISON_STATES);
		put(values, "overlap-states-per-endpoint", "STATES_PER_ENDPOINT",
				McpLevelOneUriTemplate.MAXIMUM_ENDPOINT_OVERLAP_COMPARISON_STATES);
		return Map.copyOf(values);
	}

	private static Map<String, Bound> transportValues() throws Exception {
		Map<String, Bound> values = new LinkedHashMap<>();
		McpHttpTransportConfiguration transport =
				McpHttpTransportConfiguration.productionDefaults(0);
		McpSubscriptionRuntimeConfiguration subscription =
				McpSubscriptionRuntimeConfiguration.productionDefaults();
		Object publicBuilder = publicServerBuilder();
		Assertions.assertEquals(subscription.streamQueueCapacity(),
				fieldValue(publicBuilder, "streamQueueCapacity"));
		Assertions.assertEquals(subscription.writeTimeout(),
				fieldValue(publicBuilder, "writeTimeout"));
		Assertions.assertEquals(subscription.keepAliveInterval(),
				fieldValue(publicBuilder, "keepAliveInterval"));
		put(values, "maximum-connections", "CONNECTIONS",
				transport.maximumConnections());
		put(values, "connection-writer-concurrency", "WORKERS",
				transport.connectionWriterConcurrency());
		put(values, "maximum-header-count", "HEADER_FIELDS",
				transport.maximumHeaderCount());
		put(values, "maximum-header-bytes", "WIRE_BYTES",
				transport.maximumHeaderBytes());
		put(values, "maximum-request-body-bytes", "BYTES",
				transport.maximumRequestBodyBytes());
		put(values, "maximum-request-target-bytes", "ASCII_BYTES",
				transport.maximumRequestTargetBytes());
		put(values, "http-framing-allowance-bytes", "WIRE_BYTES",
				transport.maximumAggregateRequestBytes()
						- transport.maximumRequestBodyBytes()
						- transport.maximumHeaderBytes()
						- transport.maximumRequestTargetBytes());
		put(values, "maximum-aggregate-request-bytes", "WIRE_BYTES",
				transport.maximumAggregateRequestBytes());
		put(values, "read-buffer-bytes", "BYTES_PER_CONNECTION",
				transport.readBufferSize());
		put(values, "accept-backlog", "CONNECTIONS", transport.acceptBacklog());
		put(values, "request-processor-concurrency", "TASKS",
				transport.requestProcessorConcurrency());
		put(values, "request-processor-queue-capacity", "TASKS",
				transport.requestProcessorQueueCapacity());
		put(values, "selector-resolution-nanos", "NANOSECONDS",
				transport.selectorResolution().toNanos());
		put(values, "request-header-timeout-nanos", "NANOSECONDS",
				transport.requestHeaderTimeout().toNanos());
		put(values, "request-body-timeout-nanos", "NANOSECONDS",
				transport.requestBodyTimeout().toNanos());
		put(values, "direct-write-idle-timeout-nanos", "NANOSECONDS",
				transport.responseWriteIdleTimeout().toNanos());
		put(values, "direct-shutdown-timeout-nanos", "NANOSECONDS",
				transport.shutdownTimeout().toNanos());
		put(values, "direct-stream-queue-capacity", "FRAMES",
				transport.streamQueueCapacity());
		put(values, "effective-write-idle-timeout-nanos", "NANOSECONDS",
				subscription.writeTimeout().toNanos());
		put(values, "effective-keep-alive-interval-nanos", "NANOSECONDS",
				subscription.keepAliveInterval().toNanos());
		put(values, "effective-shutdown-timeout-nanos", "NANOSECONDS",
				subscription.shutdownTimeout().toNanos());
		put(values, "effective-stream-queue-capacity", "FRAMES",
				subscription.streamQueueCapacity());
		return Map.copyOf(values);
	}

	private static Map<String, Bound> cursorOutputValues() {
		Map<String, Bound> values = new LinkedHashMap<>();
		McpJsonLimits json = McpJsonLimits.productionDefaults();
		put(values, "cursor-default-bytes", "UTF8_BYTES",
				McpCursorLimit.DEFAULT_MAXIMUM_SIZE_IN_BYTES);
		put(values, "cursor-hard-bytes", "UTF8_BYTES",
				McpCursorLimit.MAXIMUM_SUPPORTED_SIZE_IN_BYTES);
		put(values, "json-token-characters", "UTF16_CODE_UNITS",
				json.maximumTokenLengthInCharacters());
		put(values, "json-string-characters", "UTF16_CODE_UNITS",
				json.maximumStringLengthInCharacters());
		put(values, "json-output-bytes", "UTF8_BYTES", json.maximumOutputBytes());
		int frameBytes = McpRequestSseStream.maximumFrameBytes(json);
		put(values, "sse-frame-bytes", "BYTES", frameBytes);
		put(values, "regular-lane-bytes", "BYTES", frameBytes);
		put(values, "terminal-lane-bytes", "BYTES", frameBytes);
		return Map.copyOf(values);
	}

	private static Map<String, Bound> numericParserValues() {
		Map<String, Bound> values = new LinkedHashMap<>();
		McpJsonLimits production = McpJsonLimits.productionDefaults();
		McpJsonLimits hard = McpJsonLimits.maximumSupported();
		put(values, "number-characters-production", "UTF16_CODE_UNITS",
				production.maximumNumberLengthInCharacters());
		put(values, "number-characters-hard", "UTF16_CODE_UNITS",
				hard.maximumNumberLengthInCharacters());
		put(values, "exponent-magnitude-production", "INTEGER",
				production.maximumExponentMagnitude());
		put(values, "exponent-magnitude-hard", "INTEGER",
				hard.maximumExponentMagnitude());
		return Map.copyOf(values);
	}

	private static Map<String, Bound> schemaValues() throws Exception {
		Map<String, Bound> values = new LinkedHashMap<>();
		Object compiler = invokeStatic(
				"com.soklet.internal.mcp.schema.McpSchemaCompilationLimits",
				"productionDefaults");
		Object compilerHard = invokeStatic(
				"com.soklet.internal.mcp.schema.McpSchemaCompilationLimits",
				"maximumSupported");
		putPair(values, "compiler-nodes", "NODES", compiler, compilerHard,
				"maximumSchemaNodeCount");
		putPair(values, "compiler-depth", "LEVELS", compiler, compilerHard,
				"maximumSchemaDepth");
		putPair(values, "compiler-keywords", "KEYWORDS", compiler, compilerHard,
				"maximumKeywordCount");
		putPair(values, "compiler-anchors", "ANCHORS", compiler, compilerHard,
				"maximumAnchorCount");
		putPair(values, "compiler-references", "REFERENCES", compiler,
				compilerHard, "maximumReferenceCount");
		putPair(values, "compiler-anchor-name-characters", "UTF16_CODE_UNITS",
				compiler, compilerHard, "maximumAnchorNameLengthInCharacters");
		putPair(values, "compiler-reference-characters", "UTF16_CODE_UNITS",
				compiler, compilerHard, "maximumReferenceLengthInCharacters");
		putPair(values, "compiler-pointer-segments", "SEGMENTS", compiler,
				compilerHard, "maximumPointerSegmentCount");
		putPair(values, "compiler-collection-entries", "ENTRIES", compiler,
				compilerHard, "maximumCollectionEntryCount");
		putPair(values, "compiler-name-characters", "UTF16_CODE_UNITS", compiler,
				compilerHard, "maximumNameLengthInCharacters");
		putPair(values, "compiler-pointer-segment-characters",
				"UTF16_CODE_UNITS", compiler, compilerHard,
				"maximumPointerSegmentLengthInCharacters");

		Object evaluator = invokeStatic(
				"com.soklet.internal.mcp.schema.McpSchemaEvaluationLimits",
				"productionDefaults");
		Object evaluatorHard = invokeStatic(
				"com.soklet.internal.mcp.schema.McpSchemaEvaluationLimits",
				"maximumSupported");
		putPair(values, "evaluator-operations", "OPERATIONS", evaluator,
				evaluatorHard, "maximumEvaluationOperations");
		putPair(values, "evaluator-reference-traversals", "TRAVERSALS",
				evaluator, evaluatorHard, "maximumReferenceTraversals");
		putPair(values, "evaluator-pending-tasks", "TASKS", evaluator,
				evaluatorHard, "maximumPendingTaskCount");
		putPair(values, "evaluator-diagnostics", "DIAGNOSTICS", evaluator,
				evaluatorHard, "maximumDiagnosticCount");
		putPair(values, "evaluator-diagnostic-bytes", "UTF8_BYTES", evaluator,
				evaluatorHard, "maximumDiagnosticUtf8Bytes");
		return Map.copyOf(values);
	}

	private static Map<String, Bound> serializedResultValues() throws Exception {
		Map<String, Bound> values = new LinkedHashMap<>();
		McpJsonLimits json = McpJsonLimits.productionDefaults();
		Assertions.assertEquals(json, staticValue(
				"com.soklet.internal.mcp.protocol.McpPublicJsonValueConverter",
				"PRODUCTION_LIMITS"));
		put(values, "result-depth", "LEVELS", json.maximumNestingDepth());
		put(values, "result-nodes", "NODES", json.maximumNodeCount());
		put(values, "result-output-bytes", "UTF8_BYTES",
				json.maximumOutputBytes());
		int base64Characters = staticNumber("com.soklet.DefaultMcpServer",
				"MAXIMUM_BASE64_CHARACTERS").intValue();
		put(values, "base64-characters-per-scalar", "ASCII_CHARACTERS",
				base64Characters);
		put(values, "raw-binary-bytes-per-scalar", "BYTES",
				3L * (base64Characters / 4L));
		put(values, "aggregate-base64-characters", "ASCII_CHARACTERS",
				staticNumber("com.soklet.DefaultMcpServer",
						"MAXIMUM_AGGREGATE_BASE64_CHARACTERS"));
		put(values, "simulation-captured-bytes", "BYTES",
				McpSimulationOptions.defaultInstance().getMaximumCapturedSizeInBytes());
		put(values, "localization-lookups-default", "LOOKUPS", staticNumber(
				McpLocalizer.class.getName(),
				"DEFAULT_MAXIMUM_LOCALIZABLE_TEXT_COUNT_PER_RESPONSE"));
		put(values, "localization-lookups-hard", "LOOKUPS", staticNumber(
				McpLocalizer.class.getName(),
				"MAXIMUM_SUPPORTED_LOCALIZABLE_TEXT_COUNT_PER_RESPONSE"));
		Assertions.assertEquals(staticNumber(McpLocalizer.class.getName(),
					"MAXIMUM_SUPPORTED_LOCALIZABLE_TEXT_COUNT_PER_RESPONSE"),
				staticNumber("com.soklet.DefaultMcpLocalizationCatalogExtractor",
						"MAXIMUM_SUPPORTED_CALLBACK_COUNT"));
		return Map.copyOf(values);
	}

	private static Set<String> finiteBoundIds() throws Exception {
		McpJsonObject inventory = object(new McpJsonCodec(
				McpJsonLimits.productionDefaults()).parse(Files.readAllBytes(Path.of(
				"conformance", "mcp-finite-bound-inventory.json"))));
		Set<String> ids = new HashSet<>();
		for (McpJsonValue value : array(inventory, "bounds").values())
			Assertions.assertTrue(ids.add(string(object(value), "id")));
		return Set.copyOf(ids);
	}

	private static Map<String, Bound> values(McpJsonArray rows) {
		Map<String, Bound> values = new LinkedHashMap<>();
		List<String> orderedKeys = new ArrayList<>();
		for (McpJsonValue value : rows.values()) {
			McpJsonObject row = object(value);
			Assertions.assertEquals(Set.of("key", "unit", "value"),
					row.members().keySet());
			String key = string(row, "key");
			orderedKeys.add(key);
			Assertions.assertNull(values.put(key,
					new Bound(string(row, "unit"), string(row, "value"))), key);
		}
		assertStrictlySorted(orderedKeys, "decision value keys");
		return Map.copyOf(values);
	}

	private static void assertNonblankStrings(McpJsonArray values) {
		Assertions.assertFalse(values.values().isEmpty());
		for (String value : strings(values))
			Assertions.assertFalse(value.isBlank());
	}

	private static void assertEvidencePaths(McpJsonArray paths) throws Exception {
		Assertions.assertFalse(paths.values().isEmpty());
		List<String> references = strings(paths);
		assertStrictlySorted(references, "decision evidence paths");
		for (String reference : references) {
			String[] parts = reference.split("#", 2);
			Assertions.assertEquals(2, parts.length, reference);
			Path path = Path.of(parts[0]);
			Assertions.assertTrue(Files.isRegularFile(path), reference);
			Assertions.assertTrue(Files.readString(path).contains(parts[1] + "("),
					reference);
		}
	}

	private static void assertOwners(McpJsonArray owners) {
		Assertions.assertFalse(owners.values().isEmpty());
		List<String> ownerKeys = new ArrayList<>();
		for (McpJsonValue value : owners.values()) {
			McpJsonObject owner = object(value);
			Assertions.assertEquals(Set.of("file", "member", "owner"),
					owner.members().keySet());
			Assertions.assertTrue(Files.isRegularFile(
					Path.of(string(owner, "file"))));
			Assertions.assertFalse(string(owner, "member").isBlank());
			Assertions.assertFalse(string(owner, "owner").isBlank());
			ownerKeys.add(string(owner, "file") + "#" + string(owner, "owner")
					+ "#" + string(owner, "member"));
		}
		assertStrictlySorted(ownerKeys, "decision owner keys");
	}

	private static Object invokeStatic(String className, String methodName)
			throws Exception {
		Method method = Class.forName(className).getDeclaredMethod(methodName);
		method.setAccessible(true);
		return method.invoke(null);
	}

	private static Number reflectedNumber(Object target, String methodName)
			throws Exception {
		Method method = target.getClass().getDeclaredMethod(methodName);
		method.setAccessible(true);
		return (Number) method.invoke(target);
	}

	private static Number staticNumber(String className, String fieldName)
			throws Exception {
		return (Number) staticValue(className, fieldName);
	}

	private static Object staticValue(String className, String fieldName)
			throws Exception {
		Field field = Class.forName(className).getDeclaredField(fieldName);
		field.setAccessible(true);
		return field.get(null);
	}

	private static Object fieldValue(Object target, String fieldName)
			throws ReflectiveOperationException {
		Field field = target.getClass().getDeclaredField(fieldName);
		field.setAccessible(true);
		return field.get(target);
	}

	private static McpServer.Builder publicServerBuilder() {
		McpEndpoint endpoint = McpEndpoint.withPath("/mcp",
				McpImplementation.withNameAndVersion(
						"limits-and-accounting-tests", "4.0.0").build())
				.build();
		return McpServer.withPort(0).endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)));
	}

	private static void putPair(Map<String, Bound> values, String key,
			String unit, Object production, Object hard, String accessor)
			throws Exception {
		put(values, key + "-production", unit,
				reflectedNumber(production, accessor));
		put(values, key + "-hard", unit, reflectedNumber(hard, accessor));
	}

	private static void put(Map<String, Bound> values, String key, String unit,
			Number value) {
		Assertions.assertNull(values.put(key, new Bound(unit, value.toString())),
				key);
	}

	private static McpJsonArray array(McpJsonObject object, String name) {
		return Assertions.assertInstanceOf(McpJsonArray.class,
				object.members().get(name));
	}

	private static McpJsonObject object(McpJsonValue value) {
		return Assertions.assertInstanceOf(McpJsonObject.class, value);
	}

	private static String string(McpJsonObject object, String name) {
		return Assertions.assertInstanceOf(McpJsonString.class,
				object.members().get(name)).value();
	}

	private static List<String> strings(McpJsonArray array) {
		List<String> values = new ArrayList<>();
		for (McpJsonValue value : array.values())
			values.add(Assertions.assertInstanceOf(McpJsonString.class, value)
					.value());
		return List.copyOf(values);
	}

	private static void assertStrictlySorted(List<String> values, String label) {
		for (int index = 1; index < values.size(); ++index)
			Assertions.assertTrue(values.get(index - 1).compareTo(values.get(index)) < 0,
					() -> label + " are not strictly ASCII sorted: " + values);
	}

	private record Bound(String unit, String value) {
	}
}
