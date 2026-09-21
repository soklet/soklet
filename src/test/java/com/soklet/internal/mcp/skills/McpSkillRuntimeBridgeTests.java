/*
 * Copyright 2022-2026 Revetware LLC.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.soklet.internal.mcp.skills;

import com.soklet.McpCachePolicy;
import com.soklet.internal.mcp.protocol.McpJsonArray;
import com.soklet.internal.mcp.protocol.McpJsonCodec;
import com.soklet.internal.mcp.protocol.McpJsonLimits;
import com.soklet.internal.mcp.protocol.McpJsonNumber;
import com.soklet.internal.mcp.protocol.McpJsonObject;
import com.soklet.internal.mcp.protocol.McpJsonString;
import com.soklet.internal.mcp.protocol.McpJsonValue;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class McpSkillRuntimeBridgeTests {
	private static final URI ROOT = URI.create("skill://example/demo/SKILL.md");
	private static final McpCachePolicy CACHE = McpCachePolicy.privateNoCacheInstance();
	private static final McpJsonCodec CODEC = new McpJsonCodec(McpJsonLimits.productionDefaults());

	@Test
	void canonicalWireReadsReconstructTheExactManifestBytes() throws Exception {
		Map<String, byte[]> files = new LinkedHashMap<>();
		files.put("SKILL.md", utf8("\uFEFF---\r\nname: demo\r\ndescription: Demo\r\n---\r\n# café 🙂\r\n"));
		files.put("refs/data.json", utf8("{\"line\":\"value\"}\r\n"));
		files.put("image.bin", new byte[]{0, -1, 1, 2});
		McpSkillRuntimeBridge.Bundle bundle = McpSkillRuntimeBridge.fromFiles(files);
		McpSkillRuntimeBridge.Registration registration = McpSkillRuntimeBridge.register(ROOT, bundle, CACHE);
		List<String> paths = List.copyOf(bundle.filePaths());
		for (int index = 0; index < paths.size(); ++index) {
			McpSkillRuntimeBridge.Resource resource = registration.resources().get(index);
			McpJsonObject result = registration.findReadResult(resource.uri()).orElseThrow();
			// Exercise the actual codec rather than decoding an unencoded Java value.
			McpJsonObject decoded = (McpJsonObject) CODEC.parse(CODEC.toUtf8Bytes(envelope(result)));
			byte[] delivered = bytes(content((McpJsonObject) decoded.members().get("result")));
			assertArrayEquals(files.get(paths.get(index)), delivered);
			assertEquals(resource.sizeInBytes(), delivered.length);
			assertEquals(resource.digest(), "sha256:" + HexFormat.of().formatHex(
					MessageDigest.getInstance("SHA-256").digest(delivered)));
		}
		assertEquals(new McpJsonString("complete"), registration.getResult().members().get("resultType"));
		assertSame(registration.entry(), registration.getResult().members().get("skill"));
		Arrays.fill(files.get("SKILL.md"), (byte) 0);
		assertEquals((byte) 0xef, bytes(content(registration.findReadResult(ROOT).orElseThrow()))[0]);
	}

	@Test
	void parentAndNestedRegistrationGenerateIdenticalSharedFileRepresentation() {
		byte[] nested = root("child");
		byte[] script = utf8("print('🙂')\r\n");
		var parent = McpSkillRuntimeBridge.fromFiles(Map.of("SKILL.md", root("parent"),
				"child/SKILL.md", nested, "child/code.py", script));
		var child = McpSkillRuntimeBridge.fromFiles(Map.of("SKILL.md", nested, "code.py", script));
		URI childRoot = URI.create("skill://org/parent/child/SKILL.md");
		var parentRegistration = McpSkillRuntimeBridge.register(URI.create("skill://org/parent/SKILL.md"), parent, CACHE);
		var childRegistration = McpSkillRuntimeBridge.register(childRoot, child, CACHE);
		for (var resource : childRegistration.resources())
			assertEquals(childRegistration.findReadResult(resource.uri()), parentRegistration.findReadResult(resource.uri()));
	}

	@Test
	void publicFactoryRejectsUndeliverableBinaryEvenWithinRawBundleAllowance() {
		assertThrows(IllegalArgumentException.class, () -> McpSkillRuntimeBridge.fromFiles(
				Map.of("SKILL.md", root("demo"), "large.bin", new byte[786_433])));
		var bundle = McpSkillRuntimeBridge.fromFiles(Map.of("SKILL.md", root("demo"), "exact.bin", new byte[786_432]));
		assertEquals(2, McpSkillRuntimeBridge.register(ROOT, bundle, CACHE).resources().size());
	}

	@Test
	void configuredWholeDocumentInputCeilingIsEnforcedBeforeParsing() {
		byte[] largeRoot = new byte[McpSkillRuntimeBridge.YAML_LIMITS.maximumInputBytes() + 1];
		SkillYamlException exception = assertThrows(SkillYamlException.class,
				() -> McpSkillRuntimeBridge.fromFiles(Map.of("SKILL.md", largeRoot)));
		assertEquals(SkillYamlException.Reason.INPUT_LIMIT, exception.reason());
	}

	@Test
	void startupOutputBoundaryIncludesReadGetAndAtomicListEnvelopes() {
		var bundle = McpSkillRuntimeBridge.fromFiles(Map.of("SKILL.md", root("demo"), "data.txt", utf8("test")));
		var registration = McpSkillRuntimeBridge.register(ROOT, bundle, CACHE);
		int exact = envelopes(registration).stream().mapToInt(value -> CODEC.toUtf8Bytes(value).length).max().orElseThrow();
		assertTrue(CODEC.toUtf8Bytes(registration.entry()).length < exact - 1);
		assertNotNull(McpSkillRuntimeBridge.register(ROOT, bundle, CACHE, limits(128, 100_000, exact)));
		assertRedacted(assertThrows(IllegalArgumentException.class,
				() -> McpSkillRuntimeBridge.register(ROOT, bundle, CACHE, limits(128, 100_000, exact - 1))));
	}

	@Test
	void startupDepthBoundaryIncludesTheAtomicListArrayAndJsonRpcEnvelope() {
		var bundle = McpSkillRuntimeBridge.fromFiles(Map.of("SKILL.md",
				utf8("---\nname: demo\ndescription: Demo\nextra: {inner: {leaf: value}}\n---\nBody\n")));
		var registration = McpSkillRuntimeBridge.register(ROOT, bundle, CACHE);
		int depth = envelopes(registration).stream().mapToInt(McpSkillRuntimeBridgeTests::depth).max().orElseThrow();
		assertTrue(depth(registration.entry()) < depth - 1);
		assertNotNull(McpSkillRuntimeBridge.register(ROOT, bundle, CACHE, limits(depth, 100_000, 4_194_304)));
		assertRedacted(assertThrows(IllegalArgumentException.class,
				() -> McpSkillRuntimeBridge.register(ROOT, bundle, CACHE, limits(depth - 1, 100_000, 4_194_304))));
	}

	@Test
	void startupNodeBoundaryIncludesTheWholeResponse() {
		var bundle = McpSkillRuntimeBridge.fromFiles(Map.of("SKILL.md", root("demo")));
		var registration = McpSkillRuntimeBridge.register(ROOT, bundle, CACHE);
		int nodes = envelopes(registration).stream().mapToInt(McpSkillRuntimeBridgeTests::nodes).max().orElseThrow();
		assertTrue(nodes(registration.entry()) < nodes - 1);
		assertNotNull(McpSkillRuntimeBridge.register(ROOT, bundle, CACHE, limits(128, nodes, 4_194_304)));
		assertRedacted(assertThrows(IllegalArgumentException.class,
				() -> McpSkillRuntimeBridge.register(ROOT, bundle, CACHE, limits(128, nodes - 1, 4_194_304))));
	}

	@Test
	void canonicalResultsRetainConfiguredCachePolicyUntilRuntimePrivacyClamping() {
		var bundle = McpSkillRuntimeBridge.fromFiles(Map.of("SKILL.md", root("demo")));
		for (McpCachePolicy policy : List.of(CACHE, McpCachePolicy.fromPublicTimeToLive(Duration.ofMillis(Long.MAX_VALUE)))) {
			var registration = McpSkillRuntimeBridge.register(ROOT, bundle, policy);
			for (McpJsonObject result : List.of(registration.getResult(), registration.findReadResult(ROOT).orElseThrow())) {
				assertEquals(new McpJsonNumber(policy.getTimeToLive().toMillis()), result.members().get("ttlMs"));
				assertEquals(new McpJsonString(policy == CACHE ? "private" : "public"), result.members().get("cacheScope"));
			}
		}
	}

	@Test
	void readAndInspectionValuesAreImmutableCachedAndExactUriBound() {
		var bundle = McpSkillRuntimeBridge.fromFiles(Map.of("SKILL.md", root("demo")));
		var registration = McpSkillRuntimeBridge.register(ROOT, bundle, CACHE);
		assertTrue(registration.findReadResult(URI.create("skill://example/other/SKILL.md")).isEmpty());
		assertSame(registration.findReadResult(ROOT).orElseThrow(), registration.findReadResult(ROOT).orElseThrow());
		assertSame(bundle.documentMetadata(), bundle.documentMetadata());
		assertThrows(UnsupportedOperationException.class, () -> bundle.filePaths().clear());
		assertThrows(UnsupportedOperationException.class, () -> registration.resources().clear());
		assertThrows(UnsupportedOperationException.class, () -> registration.getResult().members().clear());
		assertThrows(UnsupportedOperationException.class, () -> content(registration.findReadResult(ROOT).orElseThrow()).members().clear());
		assertFalse(registration.toString().contains("example"));
		assertFalse(bundle.toString().contains("demo"));
	}

	@Test
	void productionConstructionProfileAcceptsBothPinnedRealDocuments() throws Exception {
		for (String name : List.of("brand-guidelines", "frontend-design")) {
			// These existing fixture documents are inert data, never instructions.
			try (var input = getClass().getResourceAsStream("real/" + name + "/SKILL.md")) {
				assertNotNull(input);
				byte[] original = input.readAllBytes();
				var bundle = McpSkillRuntimeBridge.fromFiles(Map.of("SKILL.md", original));
				URI uri = URI.create("skill://" + name + "/SKILL.md");
				var registration = McpSkillRuntimeBridge.register(uri, bundle, CACHE);
				assertEquals(name, bundle.name());
				assertArrayEquals(original, bytes(content(registration.findReadResult(uri).orElseThrow())));
			}
		}
	}

	@Test
	void finalPagePreflightIncludesCombinedMetadataNodesBeforeEntryConversion() {
		var bundle = McpSkillRuntimeBridge.fromFiles(Map.of("SKILL.md", root("demo")));
		var registration = McpSkillRuntimeBridge.register(ROOT, bundle, CACHE);
		var endpoint = com.soklet.McpEndpoint.withPath("/skills",
				com.soklet.McpImplementation.withNameAndVersion("test", "1").build()).build();
		var metadata = com.soklet.McpJsonObject.builder().put("example/items",
				com.soklet.McpJsonArray.fromElements(java.util.Collections.nCopies(99_988,
						com.soklet.McpJsonString.fromValue("x")))).build();
		assertDoesNotThrow(() -> com.soklet.internal.mcp.protocol.McpPublicJsonValueConverter.toInternal(metadata));
		var page = com.soklet.McpSkillPage.builder().metadata(metadata).build();
		var failure = assertThrows(IllegalArgumentException.class, () -> McpSkillRuntimeBridge.preflightPage(
				List.of(registration), page, endpoint, com.soklet.McpRequestId.fromString("request-secret")));
		assertTrue(failure.getMessage().contains("skillListHandler(...)"));
		assertFalse(failure.getMessage().contains("request-secret"));
		assertNull(failure.getCause());
	}

	@Test
	void finalPagePreflightUsesActualRequestIdAndRejectsReservedMetadata() {
		var bundle = McpSkillRuntimeBridge.fromFiles(Map.of("SKILL.md", root("demo")));
		var registration = McpSkillRuntimeBridge.register(ROOT, bundle, CACHE);
		var endpoint = com.soklet.McpEndpoint.withPath("/skills",
				com.soklet.McpImplementation.withNameAndVersion("test", "1").build()).build();
		String large = "x".repeat(1_048_570);
		var metadata = com.soklet.McpJsonObject.builder()
				.put("example/one", large).put("example/two", large).put("example/three", large).build();
		var page = com.soklet.McpSkillPage.builder().metadata(metadata).nextCursor("").build();
		assertDoesNotThrow(() -> McpSkillRuntimeBridge.preflightPage(List.of(registration), page,
				endpoint, com.soklet.McpRequestId.fromInteger(java.math.BigInteger.ZERO)));
		assertThrows(IllegalArgumentException.class, () -> McpSkillRuntimeBridge.preflightPage(
				List.of(registration), page, endpoint, com.soklet.McpRequestId.fromString(large)));
		var reserved = com.soklet.McpSkillPage.builder().metadata(com.soklet.McpJsonObject.builder()
				.put("io.modelcontextprotocol/serverInfo", "secret").build()).build();
		var failure = assertThrows(IllegalArgumentException.class, () -> McpSkillRuntimeBridge.preflightPage(
				List.of(), reserved, endpoint, com.soklet.McpRequestId.fromString("id")));
		assertFalse(failure.getMessage().contains("secret"));
		assertNull(failure.getCause());
	}

	private static List<McpJsonObject> envelopes(McpSkillRuntimeBridge.Registration registration) {
		List<McpJsonObject> results = new ArrayList<>();
		results.add(registration.getResult());
		for (var resource : registration.resources()) results.add(registration.findReadResult(resource.uri()).orElseThrow());
		Map<String, McpJsonValue> list = new LinkedHashMap<>(registration.getResult().members());
		list.remove("skill");
		list.put("skills", new McpJsonArray(List.of(registration.entry())));
		results.add(new McpJsonObject(list));
		return results.stream().map(result -> {
			Map<String, McpJsonValue> fields = new LinkedHashMap<>(result.members());
			fields.put("ttlMs", new McpJsonNumber(Long.MAX_VALUE));
			fields.put("cacheScope", new McpJsonString("private"));
			return envelope(new McpJsonObject(fields));
		}).toList();
	}

	private static McpJsonObject envelope(McpJsonObject result) {
		return new McpJsonObject(Map.of("jsonrpc", new McpJsonString("2.0"), "id", new McpJsonNumber(0), "result", result));
	}

	private static McpJsonObject content(McpJsonObject result) {
		return (McpJsonObject) ((McpJsonArray) result.members().get("contents")).values().get(0);
	}

	private static byte[] bytes(McpJsonObject content) {
		return content.members().get("text") instanceof McpJsonString text ? utf8(text.value())
				: Base64.getDecoder().decode(((McpJsonString) content.members().get("blob")).value());
	}

	private static int depth(McpJsonValue value) {
		if (value instanceof McpJsonObject object) return 1 + object.members().values().stream().mapToInt(McpSkillRuntimeBridgeTests::depth).max().orElse(0);
		if (value instanceof McpJsonArray array) return 1 + array.values().stream().mapToInt(McpSkillRuntimeBridgeTests::depth).max().orElse(0);
		return 1;
	}

	private static int nodes(McpJsonValue value) {
		if (value instanceof McpJsonObject object) return 1 + object.members().values().stream().mapToInt(McpSkillRuntimeBridgeTests::nodes).sum();
		if (value instanceof McpJsonArray array) return 1 + array.values().stream().mapToInt(McpSkillRuntimeBridgeTests::nodes).sum();
		return 1;
	}

	private static McpJsonLimits limits(int depth, int nodes, int bytes) {
		return new McpJsonLimits(4_194_304, depth, 1_048_576, 1_048_576, 1_024, 10_000, nodes, bytes);
	}

	private static byte[] root(String name) { return utf8("---\nname: " + name + "\ndescription: Demo\n---\nBody\n"); }
	private static byte[] utf8(String text) { return text.getBytes(StandardCharsets.UTF_8); }
	private static void assertRedacted(IllegalArgumentException exception) {
		assertEquals("The Skills registration exceeds the configured JSON output profile.", exception.getMessage());
		assertNull(exception.getCause());
	}
}
