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
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class McpFiniteBoundInventoryTests {
	private static final Path INVENTORY = Path.of(
			"conformance", "mcp-finite-bound-inventory.json");

	@Test
	public void inventoryHasExactProductionOwnerAndValueParity() throws Exception {
		McpJsonObject inventory = object(new McpJsonCodec(
				McpJsonLimits.productionDefaults()).parse(
				Files.readAllBytes(INVENTORY)));
		Assertions.assertEquals(Set.of("bounds", "formatVersion", "matcherRules",
				"productionProfile", "releaseTarget", "reviewedExclusions",
				"scanRoots"), inventory.members().keySet());
		Assertions.assertEquals(BigDecimal.ONE,
				Assertions.assertInstanceOf(McpJsonNumber.class,
						inventory.members().get("formatVersion")).value());
		Assertions.assertEquals("2026-07-28",
				string(inventory, "productionProfile"));
		Assertions.assertEquals("4.0.0", string(inventory, "releaseTarget"));

		Set<String> matcherIds = new HashSet<>();
		List<String> orderedMatcherIds = new ArrayList<>();
		for (McpJsonValue value : array(inventory, "matcherRules").values()) {
			McpJsonObject matcher = object(value);
			Assertions.assertEquals(Set.of("description", "family", "id"),
					matcher.members().keySet());
			String matcherId = string(matcher, "id");
			Assertions.assertTrue(matcherIds.add(matcherId));
			orderedMatcherIds.add(matcherId);
			Assertions.assertFalse(string(matcher, "description").isBlank());
			Assertions.assertFalse(string(matcher, "family").isBlank());
		}
		Assertions.assertEquals(Set.of("FINITE-MATCH-001", "FINITE-MATCH-002",
				"FINITE-MATCH-003", "FINITE-MATCH-004"), matcherIds);
		assertStrictlySorted(orderedMatcherIds, "matcher IDs");

		Map<String, String> inventoriedValues = new LinkedHashMap<>();
		Set<String> boundIds = new HashSet<>();
		List<String> orderedBoundIds = new ArrayList<>();
		Set<String> categories = new HashSet<>();
		Set<String> sourceOwnerKeys = new HashSet<>();
		for (McpJsonValue value : array(inventory, "bounds").values()) {
			McpJsonObject bound = object(value);
			Assertions.assertEquals(Set.of("boundaryTests", "category",
					"deterministicFailure", "enforcementOwners", "id", "name",
					"positiveTests", "sourceOwners", "values"),
					bound.members().keySet());
			String boundId = string(bound, "id");
			Assertions.assertTrue(boundIds.add(boundId));
			orderedBoundIds.add(boundId);
			categories.add(string(bound, "category"));
			McpJsonObject failure = object(
					bound.members().get("deterministicFailure"));
			Assertions.assertEquals(Set.of("contract", "stage"),
					failure.members().keySet());
			Assertions.assertFalse(string(failure, "contract").isBlank());
			Assertions.assertFalse(string(failure, "stage").isBlank());
			assertEvidencePaths(array(bound, "positiveTests"));
			assertEvidencePaths(array(bound, "boundaryTests"));
			assertOwners(array(bound, "sourceOwners"), matcherIds,
					sourceOwnerKeys);
			assertOwners(array(bound, "enforcementOwners"), matcherIds, null);
			List<String> orderedValueKeys = new ArrayList<>();
			for (McpJsonValue boundValue : array(bound, "values").values()) {
				McpJsonObject row = object(boundValue);
				Assertions.assertEquals(Set.of("accounting", "key", "unit", "value"),
						row.members().keySet());
				String key = string(row, "key");
				orderedValueKeys.add(key);
				Assertions.assertNull(inventoriedValues.put(key, string(row, "value")),
						() -> "Duplicate finite-bound key " + key);
				Assertions.assertFalse(string(row, "accounting").isBlank());
				Assertions.assertFalse(string(row, "unit").isBlank());
			}
			assertStrictlySorted(orderedValueKeys, boundId + " value keys");
		}
		assertStrictlySorted(orderedBoundIds, "bound IDs");
		Assertions.assertEquals(Set.of("BODY", "CONNECTION", "CURSOR", "HEADER",
				"JSON", "OUTPUT", "PROFILE_1_COMPILER", "PROFILE_1_EVALUATOR",
				"QUEUE_STREAM", "SERIALIZED_RESULT", "TIME", "TYPED_BINDING",
				"URI_TEMPLATE"), categories);
		Assertions.assertFalse(sourceOwnerKeys.isEmpty());
		Assertions.assertEquals(sourceValues(), inventoriedValues);
	}

	@Test
	public void exclusionsAndScanScopeAreExplicitAndResolvable() throws Exception {
		McpJsonObject inventory = object(new McpJsonCodec(
				McpJsonLimits.productionDefaults()).parse(
				Files.readAllBytes(INVENTORY)));
		Assertions.assertEquals(List.of(
				"src/main/java/com/soklet/DefaultMcp*.java",
				"src/main/java/com/soklet/Mcp*.java",
				"src/main/java/com/soklet/SokletProcessor.java",
				"src/main/java/com/soklet/internal/mcp/**/*.java"),
				strings(array(inventory, "scanRoots")));
		Set<String> exclusionIds = new HashSet<>();
		Set<String> exclusionKeys = new HashSet<>();
		List<String> orderedExclusionIds = new ArrayList<>();
		List<String> orderedExclusionKeys = new ArrayList<>();
		for (McpJsonValue value : array(inventory, "reviewedExclusions").values()) {
			McpJsonObject exclusion = object(value);
			Assertions.assertEquals(Set.of("file", "id", "key", "matcherRuleId",
					"member", "owner", "rationale"), exclusion.members().keySet());
			String exclusionId = string(exclusion, "id");
			Assertions.assertTrue(exclusionId.matches("FINITE-EX-[0-9]{3}"),
					exclusionId);
			Assertions.assertTrue(exclusionIds.add(exclusionId));
			orderedExclusionIds.add(exclusionId);
			String key = ownerKey(exclusion);
			Assertions.assertEquals(key, string(exclusion, "key"));
			Assertions.assertTrue(exclusionKeys.add(key), key);
			orderedExclusionKeys.add(key);
			Assertions.assertTrue(Files.isRegularFile(Path.of(string(exclusion, "file"))));
			Assertions.assertFalse(string(exclusion, "rationale").isBlank());
		}
		Assertions.assertFalse(exclusionIds.isEmpty());
		assertStrictlySorted(orderedExclusionIds, "exclusion IDs");
		assertStrictlySorted(orderedExclusionKeys, "exclusion keys");
	}

	private static Map<String, String> sourceValues() throws Exception {
		Map<String, String> values = new LinkedHashMap<>();
		McpHttpTransportConfiguration transport =
				McpHttpTransportConfiguration.productionDefaults(0);
		Object publicBuilder = publicServerBuilder();
		put(values, "transport.accept-backlog", transport.acceptBacklog());
		put(values, "transport.read-buffer-bytes", transport.readBufferSize());
		put(values, "transport.maximum-connections", transport.maximumConnections());
		put(values, "transport.connection-writer-concurrency",
				transport.connectionWriterConcurrency());
		put(values, "transport.maximum-header-count", transport.maximumHeaderCount());
		put(values, "transport.maximum-header-bytes", transport.maximumHeaderBytes());
		put(values, "headers.mirrored-maximum-decoded-bytes",
				McpMirroredHeaderCodec.DEFAULT_MAXIMUM_DECODED_BYTES);
		put(values, "headers.custom-integer-maximum",
				staticBigInteger("com.soklet.internal.mcp.protocol."
						+ "McpCustomMirroredHeaderValidator", "MAXIMUM_SAFE_INTEGER"));
		put(values, "headers.custom-integer-minimum",
				staticBigInteger("com.soklet.internal.mcp.protocol."
						+ "McpCustomMirroredHeaderValidator", "MINIMUM_SAFE_INTEGER"));
		put(values, "propagation.baggage-maximum-bytes",
				staticNumber("com.soklet.McpRequestPropagation",
						"MAXIMUM_BAGGAGE_BYTES"));
		put(values, "propagation.baggage-maximum-entries",
				staticNumber("com.soklet.McpRequestPropagation",
						"MAXIMUM_BAGGAGE_ENTRIES"));
		put(values, "locale.accept-language-maximum-code-units",
				staticNumber("com.soklet.McpLocaleSupport",
						"MAXIMUM_ACCEPT_LANGUAGE_CODE_UNITS"));
		put(values, "locale.maximum-language-ranges",
				staticNumber("com.soklet.McpLocaleSupport",
						"MAXIMUM_LANGUAGE_RANGES"));
		put(values, "locale.maximum-language-tag-bytes",
				staticNumber("com.soklet.McpLocaleSupport",
						"MAXIMUM_LANGUAGE_TAG_BYTES"));
		put(values, "transport.maximum-request-body-bytes",
				transport.maximumRequestBodyBytes());
		put(values, "transport.maximum-aggregate-request-bytes",
				transport.maximumAggregateRequestBytes());
		put(values, "transport.http-framing-allowance-bytes",
				transport.maximumAggregateRequestBytes()
						- transport.maximumRequestBodyBytes()
						- transport.maximumHeaderBytes()
						- transport.maximumRequestTargetBytes());

		McpApplicationExecutionConfiguration application =
				McpApplicationExecutionConfiguration.productionDefaults();
		McpSubscriptionRuntimeConfiguration subscription =
				McpSubscriptionRuntimeConfiguration.productionDefaults();
		Assertions.assertEquals(application.handlerConcurrency(),
				fieldValue(publicBuilder, "requestHandlerConcurrency"));
		Assertions.assertEquals(application.handlerQueueCapacity(),
				fieldValue(publicBuilder, "requestHandlerQueueCapacity"));
		Assertions.assertEquals(subscription.streamQueueCapacity(),
				fieldValue(publicBuilder, "streamQueueCapacity"));
		Assertions.assertEquals(subscription.maximumSubscriptionsPerPartition(),
				fieldValue(publicBuilder, "maximumSubscriptionsPerPartition"));
		Assertions.assertEquals(subscription.keepAliveInterval(),
				fieldValue(publicBuilder, "keepAliveInterval"));
		Assertions.assertEquals(subscription.maximumSubscriptionDuration(),
				fieldValue(publicBuilder, "maximumSubscriptionDuration"));
		Assertions.assertEquals(subscription.writeTimeout(),
				fieldValue(publicBuilder, "writeTimeout"));
		Assertions.assertEquals(application.requestDeadline(),
				fieldValue(publicBuilder, "requestTimeout"));
		Assertions.assertEquals(McpCursorLimit.DEFAULT_MAXIMUM_SIZE_IN_BYTES,
				fieldValue(publicBuilder, "maximumCursorSizeInBytes"));
		put(values, "transport.selector-resolution-nanos",
				transport.selectorResolution().toNanos());
		put(values, "transport.request-header-timeout-nanos",
				transport.requestHeaderTimeout().toNanos());
		put(values, "transport.request-body-timeout-nanos",
				transport.requestBodyTimeout().toNanos());
		put(values, "server.response-write-idle-timeout-nanos",
				subscription.writeTimeout().toNanos());
		put(values, "server.keep-alive-interval-nanos",
				subscription.keepAliveInterval().toNanos());
		put(values, "server.shutdown-timeout-nanos",
				subscription.shutdownTimeout().toNanos());
		put(values, "application.request-deadline-nanos",
				application.requestDeadline().toNanos());
		put(values, "application.timer-resolution-nanos",
				application.timerResolution().toNanos());
		put(values, "subscription.maximum-duration-nanos",
				subscription.maximumSubscriptionDuration().toNanos());

		McpJsonLimits json = McpJsonLimits.productionDefaults();
		McpJsonLimits jsonHard = McpJsonLimits.maximumSupported();
		Assertions.assertEquals(json, staticValue(
				"com.soklet.internal.mcp.protocol.McpPublicJsonValueConverter",
				"PRODUCTION_LIMITS"));
		putPair(values, "json.input-bytes", json.maximumInputBytes(),
				jsonHard.maximumInputBytes());
		putPair(values, "json.depth", json.maximumNestingDepth(),
				jsonHard.maximumNestingDepth());
		putPair(values, "json.token-characters",
				json.maximumTokenLengthInCharacters(),
				jsonHard.maximumTokenLengthInCharacters());
		putPair(values, "json.string-characters",
				json.maximumStringLengthInCharacters(),
				jsonHard.maximumStringLengthInCharacters());
		putPair(values, "json.number-characters",
				json.maximumNumberLengthInCharacters(),
				jsonHard.maximumNumberLengthInCharacters());
		putPair(values, "json.exponent-magnitude", json.maximumExponentMagnitude(),
				jsonHard.maximumExponentMagnitude());
		putPair(values, "json.nodes", json.maximumNodeCount(),
				jsonHard.maximumNodeCount());
		putPair(values, "json.output-bytes", json.maximumOutputBytes(),
				jsonHard.maximumOutputBytes());

		Object compiler = invokeStatic(
				"com.soklet.internal.mcp.schema.McpSchemaCompilationLimits",
				"productionDefaults");
		Object compilerHard = invokeStatic(
				"com.soklet.internal.mcp.schema.McpSchemaCompilationLimits",
				"maximumSupported");
		putReflectedPair(values, "schema.compiler.nodes", compiler, compilerHard,
				"maximumSchemaNodeCount");
		putReflectedPair(values, "schema.compiler.depth", compiler, compilerHard,
				"maximumSchemaDepth");
		putReflectedPair(values, "schema.compiler.keywords", compiler, compilerHard,
				"maximumKeywordCount");
		putReflectedPair(values, "schema.compiler.anchors", compiler, compilerHard,
				"maximumAnchorCount");
		putReflectedPair(values, "schema.compiler.references", compiler,
				compilerHard, "maximumReferenceCount");
		putReflectedPair(values, "schema.compiler.anchor-name-characters", compiler,
				compilerHard, "maximumAnchorNameLengthInCharacters");
		putReflectedPair(values, "schema.compiler.reference-characters", compiler,
				compilerHard, "maximumReferenceLengthInCharacters");
		putReflectedPair(values, "schema.compiler.pointer-segments", compiler,
				compilerHard, "maximumPointerSegmentCount");
		putReflectedPair(values, "schema.compiler.collection-entries", compiler,
				compilerHard, "maximumCollectionEntryCount");
		putReflectedPair(values, "schema.compiler.name-characters", compiler,
				compilerHard, "maximumNameLengthInCharacters");
		putReflectedPair(values, "schema.compiler.pointer-segment-characters",
				compiler, compilerHard, "maximumPointerSegmentLengthInCharacters");

		Object evaluator = invokeStatic(
				"com.soklet.internal.mcp.schema.McpSchemaEvaluationLimits",
				"productionDefaults");
		Object evaluatorHard = invokeStatic(
				"com.soklet.internal.mcp.schema.McpSchemaEvaluationLimits",
				"maximumSupported");
		putReflectedPair(values, "schema.evaluator.operations", evaluator,
				evaluatorHard, "maximumEvaluationOperations");
		putReflectedPair(values, "schema.evaluator.reference-traversals", evaluator,
				evaluatorHard, "maximumReferenceTraversals");
		putReflectedPair(values, "schema.evaluator.pending-tasks", evaluator,
				evaluatorHard, "maximumPendingTaskCount");
		putReflectedPair(values, "schema.evaluator.diagnostics", evaluator,
				evaluatorHard, "maximumDiagnosticCount");
		putReflectedPair(values, "schema.evaluator.diagnostic-bytes", evaluator,
				evaluatorHard, "maximumDiagnosticUtf8Bytes");

		Object binding = invokeStatic(
				"com.soklet.internal.mcp.schema.McpTypedJsonBindingLimits",
				"productionDefaults");
		put(values, "binding.nodes.production", reflectedNumber(binding,
				"maximumNodeCount"));
		put(values, "binding.nodes.hard", staticNumber(
				"com.soklet.internal.mcp.schema.McpTypedJsonBindingLimits",
				"MAXIMUM_SUPPORTED_NODE_COUNT"));
		put(values, "binding.depth.production", reflectedNumber(binding,
				"maximumNestingDepth"));
		put(values, "binding.depth.hard", staticNumber(
				"com.soklet.internal.mcp.schema.McpTypedJsonBindingLimits",
				"MAXIMUM_SUPPORTED_NESTING_DEPTH"));
		put(values, "binding.container-entries.production", reflectedNumber(binding,
				"maximumContainerEntryCount"));
		put(values, "binding.container-entries.hard", staticNumber(
				"com.soklet.internal.mcp.schema.McpTypedJsonBindingLimits",
				"MAXIMUM_SUPPORTED_CONTAINER_ENTRY_COUNT"));
		put(values, "binding.enum-class-file-bytes", staticNumber(
				"com.soklet.internal.mcp.schema.McpRuntimeEnumNameReader",
				"MAXIMUM_CLASS_FILE_SIZE_IN_BYTES"));
		put(values, "binding.diagnostic-segment-prefix-characters", staticNumber(
				"com.soklet.internal.mcp.schema.McpTypedSchemaPath",
				"MAXIMUM_ESCAPED_SEGMENT_PREFIX_LENGTH"));

		put(values, "uri.request-target-bytes",
				McpEndpointPathLimit.MAXIMUM_REQUEST_TARGET_BYTES);
		put(values, "uri.resource-uri-bytes",
				McpLevelOneUriTemplate.MAXIMUM_RESOURCE_URI_UTF_8_BYTES);
		Assertions.assertEquals(
				McpLevelOneUriTemplate.MAXIMUM_RESOURCE_URI_UTF_8_BYTES,
				staticNumber("com.soklet.SokletProcessor",
						"MAXIMUM_MCP_RESOURCE_URI_UTF_8_BYTES"));
		put(values, "uri.annotated-resource-uri-bytes", staticNumber(
				"com.soklet.SokletProcessor",
				"MAXIMUM_MCP_ANNOTATED_RESOURCE_URI_UTF_8_BYTES"));
		put(values, "uri.template-routed-uri-bytes",
				McpLevelOneUriTemplate.MAXIMUM_TEMPLATE_ROUTED_RESOURCE_URI_UTF_8_BYTES);
		put(values, "uri.templates-per-endpoint",
				McpLevelOneUriTemplate.MAXIMUM_TEMPLATE_COUNT_PER_ENDPOINT);
		Assertions.assertEquals(McpLevelOneUriTemplate.MAXIMUM_TEMPLATE_COUNT_PER_ENDPOINT,
				staticNumber("com.soklet.SokletProcessor",
						"MAXIMUM_MCP_RESOURCE_URI_TEMPLATES"));
		put(values, "uri.template-bytes",
				McpLevelOneUriTemplate.MAXIMUM_TEMPLATE_UTF_8_BYTES);
		Assertions.assertEquals(McpLevelOneUriTemplate.MAXIMUM_TEMPLATE_UTF_8_BYTES,
				staticNumber("com.soklet.SokletProcessor",
						"MAXIMUM_MCP_RESOURCE_URI_TEMPLATE_UTF_8_BYTES"));
		put(values, "uri.variables-per-template",
				McpLevelOneUriTemplate.MAXIMUM_VARIABLE_COUNT);
		Assertions.assertEquals(McpLevelOneUriTemplate.MAXIMUM_VARIABLE_COUNT,
				staticNumber("com.soklet.SokletProcessor",
						"MAXIMUM_MCP_RESOURCE_URI_TEMPLATE_VARIABLES"));
		put(values, "uri.variable-name-bytes",
				McpLevelOneUriTemplate.MAXIMUM_VARIABLE_NAME_UTF_8_BYTES);
		Assertions.assertEquals(
				McpLevelOneUriTemplate.MAXIMUM_VARIABLE_NAME_UTF_8_BYTES,
				staticNumber("com.soklet.SokletProcessor",
						"MAXIMUM_MCP_RESOURCE_URI_TEMPLATE_VARIABLE_NAME_UTF_8_BYTES"));
		put(values, "uri.match-dp-cells",
				McpLevelOneUriTemplate.MAXIMUM_TEMPLATE_MATCH_DYNAMIC_PROGRAMMING_CELLS);
		put(values, "uri.overlap-states-per-pair",
				McpLevelOneUriTemplate.MAXIMUM_OVERLAP_COMPARISON_STATES);
		Assertions.assertEquals(McpLevelOneUriTemplate.MAXIMUM_OVERLAP_COMPARISON_STATES,
				staticNumber("com.soklet.SokletProcessor",
						"MAXIMUM_MCP_RESOURCE_URI_TEMPLATE_OVERLAP_STATES"));
		put(values, "uri.overlap-states-per-endpoint",
				McpLevelOneUriTemplate.MAXIMUM_ENDPOINT_OVERLAP_COMPARISON_STATES);
		Assertions.assertEquals(
				McpLevelOneUriTemplate.MAXIMUM_ENDPOINT_OVERLAP_COMPARISON_STATES,
				staticNumber("com.soklet.SokletProcessor",
						"MAXIMUM_MCP_ENDPOINT_RESOURCE_URI_TEMPLATE_OVERLAP_STATES"));
		put(values, "cursor.maximum-bytes.default",
				McpCursorLimit.DEFAULT_MAXIMUM_SIZE_IN_BYTES);
		put(values, "cursor.maximum-bytes.hard",
				McpCursorLimit.MAXIMUM_SUPPORTED_SIZE_IN_BYTES);

		put(values, "queue.handler-concurrency", application.handlerConcurrency());
		put(values, "queue.handler-capacity", application.handlerQueueCapacity());
		put(values, "queue.protocol-concurrency",
				transport.requestProcessorConcurrency());
		put(values, "queue.protocol-capacity",
				transport.requestProcessorQueueCapacity());
		put(values, "queue.stream-capacity", subscription.streamQueueCapacity());
		put(values, "queue.subscriptions-per-partition",
				subscription.maximumSubscriptionsPerPartition());
		McpSimulationOptions simulation = McpSimulationOptions.defaultInstance();
		put(values, "queue.simulation-item-capacity",
				simulation.getStreamItemQueueCapacity());

		int maximumFrameBytes = McpRequestSseStream.maximumFrameBytes(json);
		put(values, "stream.maximum-frame-bytes", maximumFrameBytes);
		put(values, "stream.regular-byte-capacity", maximumFrameBytes);
		put(values, "stream.terminal-byte-capacity", maximumFrameBytes);
		put(values, "result.depth", json.maximumNestingDepth());
		put(values, "result.nodes", json.maximumNodeCount());
		put(values, "result.output-bytes", json.maximumOutputBytes());
		int base64Characters = staticNumber("com.soklet.DefaultMcpServer",
				"MAXIMUM_BASE64_CHARACTERS").intValue();
		put(values, "result.base64-characters-per-scalar", base64Characters);
		put(values, "result.raw-binary-bytes-per-scalar",
				3L * (base64Characters / 4L));
		put(values, "result.aggregate-base64-characters", staticNumber(
				"com.soklet.DefaultMcpServer",
				"MAXIMUM_AGGREGATE_BASE64_CHARACTERS"));
		put(values, "output.simulation-captured-bytes",
				simulation.getMaximumCapturedSizeInBytes());
		put(values, "output.localization-lookups.default", staticNumber(
				McpLocalizer.class.getName(),
				"DEFAULT_MAXIMUM_LOCALIZABLE_TEXT_COUNT_PER_RESPONSE"));
		put(values, "output.localization-lookups.hard", staticNumber(
				McpLocalizer.class.getName(),
				"MAXIMUM_SUPPORTED_LOCALIZABLE_TEXT_COUNT_PER_RESPONSE"));
		Assertions.assertEquals(
				staticNumber(McpLocalizer.class.getName(),
						"MAXIMUM_SUPPORTED_LOCALIZABLE_TEXT_COUNT_PER_RESPONSE"),
				staticNumber("com.soklet.DefaultMcpLocalizationCatalogExtractor",
						"MAXIMUM_SUPPORTED_CALLBACK_COUNT"));
		return Map.copyOf(values);
	}

	private static void assertEvidencePaths(McpJsonArray paths) throws Exception {
		Assertions.assertFalse(paths.values().isEmpty());
		List<String> references = strings(paths);
		assertStrictlySorted(references, "evidence paths");
		for (String reference : references) {
			String[] parts = reference.split("#", 2);
			Assertions.assertEquals(2, parts.length, reference);
			Path path = Path.of(parts[0]);
			Assertions.assertTrue(Files.isRegularFile(path), reference);
			Assertions.assertTrue(Files.readString(path).contains(parts[1] + "("),
					reference);
		}
	}

	private static void assertOwners(McpJsonArray owners, Set<String> matcherIds,
			Set<String> sourceOwnerKeys) {
		Assertions.assertFalse(owners.values().isEmpty());
		List<String> ownerKeys = new ArrayList<>();
		for (McpJsonValue value : owners.values()) {
			McpJsonObject owner = object(value);
			Set<String> expectedKeys = sourceOwnerKeys != null
					? Set.of("file", "key", "matcherRuleId", "member", "owner")
					: Set.of("file", "member", "owner");
			Assertions.assertEquals(expectedKeys, owner.members().keySet());
			Assertions.assertTrue(Files.isRegularFile(Path.of(string(owner, "file"))));
			String ownerKey = sourceOwnerKeys == null
					? string(owner, "file") + "#" + string(owner, "owner")
							+ "#" + string(owner, "member")
					: ownerKey(owner);
			ownerKeys.add(ownerKey);
			if (sourceOwnerKeys != null) {
				Assertions.assertTrue(matcherIds.contains(
						string(owner, "matcherRuleId")));
				Assertions.assertEquals(ownerKey, string(owner, "key"));
				Assertions.assertTrue(sourceOwnerKeys.add(ownerKey), ownerKey);
			}
		}
		assertStrictlySorted(ownerKeys, "owner keys");
	}

	private static String ownerKey(McpJsonObject owner) {
		String member = string(owner, "member");
		Assertions.assertTrue(member.matches(
				"[A-Za-z_$][A-Za-z0-9_$]*(?:\\([^\\r\\n#]*\\))?"),
				member);
		return string(owner, "matcherRuleId") + ":" + string(owner, "file")
				+ "#" + string(owner, "owner") + "#" + member;
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
			throws Exception {
		Field field = target.getClass().getDeclaredField(fieldName);
		field.setAccessible(true);
		return field.get(target);
	}

	private static McpServer.Builder publicServerBuilder() {
		McpEndpoint endpoint = McpEndpoint.withPath("/mcp",
				McpImplementation.withNameAndVersion(
						"finite-bound-inventory-tests", "4.0.0").build())
				.build();
		return McpServer.withPort(0).endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)));
	}

	private static BigInteger staticBigInteger(String className, String fieldName)
			throws Exception {
		Field field = Class.forName(className).getDeclaredField(fieldName);
		field.setAccessible(true);
		return (BigInteger) field.get(null);
	}

	private static void putReflectedPair(Map<String, String> values, String key,
			Object production, Object hard, String accessor) throws Exception {
		putPair(values, key, reflectedNumber(production, accessor),
				reflectedNumber(hard, accessor));
	}

	private static void putPair(Map<String, String> values, String key,
			Number production, Number hard) {
		put(values, key + ".production", production);
		put(values, key + ".hard", hard);
	}

	private static void put(Map<String, String> values, String key, Number value) {
		Assertions.assertNull(values.put(key, value.toString()), key);
	}

	private static void put(Map<String, String> values, String key,
			BigInteger value) {
		Assertions.assertNull(values.put(key, value.toString()), key);
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
			values.add(Assertions.assertInstanceOf(McpJsonString.class, value).value());
		return List.copyOf(values);
	}

	private static void assertStrictlySorted(List<String> values, String label) {
		for (int index = 1; index < values.size(); ++index)
			Assertions.assertTrue(values.get(index - 1).compareTo(values.get(index)) < 0,
					() -> label + " are not strictly ASCII sorted: " + values);
	}
}
