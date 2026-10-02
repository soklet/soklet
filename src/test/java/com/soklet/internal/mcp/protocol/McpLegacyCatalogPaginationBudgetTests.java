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

package com.soklet.internal.mcp.protocol;

import com.soklet.CorsAuthorizer;
import com.soklet.McpRequestContext;
import com.soklet.McpRequestOutcome;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.annotation.concurrent.NotThreadSafe;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Injected output limits keep byte and JSON-node pagination evidence independent. */
@NotThreadSafe
@Timeout(180)
public class McpLegacyCatalogPaginationBudgetTests {
	private static final List<String> LEGACY = List.of("2025-06-18", "2025-11-25");
	private static final Duration WAIT = Duration.ofSeconds(5);
	private long requestDeadlineNanos;

	@BeforeEach
	void resetRequestDeadline() {
		requestDeadlineNanos = System.nanoTime() + Duration.ofSeconds(60).toNanos();
	}

	private Duration remainingRequestWait() {
		long remaining = requestDeadlineNanos - System.nanoTime();
		assertTrue(remaining > 0, "Catalog requests exceeded their shared 60-second deadline.");
		return Duration.ofNanos(Math.min(WAIT.toNanos(), remaining));
	}
	private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
	private static final McpJsonCodec JSON = new McpJsonCodec(McpJsonLimits.productionDefaults());

	@Test
	public void jsonNodeBudgetIndependentlyPagesEveryLegacyStaticCatalog() throws Exception {
		McpJsonLimits limits = limits(128, 65_536);
		McpNormalizedEndpoint endpoint = catalog(100, null);
		for (String revision : LEGACY) {
			try (McpHttpServerRuntime runtime = runtime(endpoint, Map.of(revision, endpoint), limits)) {
				int port = runtime.start().getPort();
				for (Kind kind : Kind.values()) {
					Enumeration enumeration = enumerate(port, revision, kind, limits);
					assertEquals(expected(kind, 100), enumeration.identities());
					assertTrue(enumeration.pages() > 1, kind.method);
					assertTrue(enumeration.maximumBytes() < 65_536 / 2,
							"These pages must be constrained by nodes, not bytes.");
				}
			}
		}
	}

	@Test
	public void byteBudgetIndependentlyPagesEveryLegacyStaticCatalog() throws Exception {
		McpJsonLimits limits = limits(McpJsonLimits.productionDefaults().maximumNodeCount(), 1_024);
		McpNormalizedEndpoint endpoint = catalog(12, "x".repeat(240));
		for (String revision : LEGACY) {
			try (McpHttpServerRuntime runtime = runtime(endpoint, Map.of(revision, endpoint), limits)) {
				int port = runtime.start().getPort();
				for (Kind kind : Kind.values()) {
					Enumeration enumeration = enumerate(port, revision, kind, limits);
					assertEquals(expected(kind, 12), enumeration.identities());
					assertTrue(enumeration.pages() > 1, kind.method);
					assertTrue(enumeration.maximumNodes() < limits.maximumNodeCount() / 2,
							"These pages must be constrained by bytes, not nodes.");
				}
			}
		}
	}

	@Test
	public void modernAndMixedViewsRetainWholeCatalogNodePreflight() {
		McpJsonLimits limits = limits(128, 65_536);
		McpNormalizedEndpoint endpoint = catalog(100, null);
		assertThrows(IllegalArgumentException.class, () -> runtime(endpoint,
				Map.of("2026-07-28", endpoint), limits));
		assertThrows(IllegalArgumentException.class, () -> runtime(endpoint,
				Map.of("2026-07-28", endpoint, "2025-06-18", endpoint,
						"2025-11-25", endpoint), limits));
	}

	@Test
	public void pagingDoesNotWaiveAnIndividualDescriptorsOutputBounds() {
		McpJsonLimits limits = limits(128, 1_024);
		McpNormalizedEndpoint endpoint = catalog(1, "x".repeat(2_048));
		for (String revision : LEGACY)
			assertThrows(IllegalArgumentException.class,
					() -> runtime(endpoint, Map.of(revision, endpoint), limits));
	}

	private static McpNormalizedEndpoint catalog(int count, String description) {
		McpNormalizedEndpoint.Builder builder = McpNormalizedEndpoint.withServerInformation(
				McpImplementationMetadata.withNameAndVersion("legacy-pagination-budget", "4.0.0"));
		McpJsonObject fields = description == null ? McpJsonObject.empty()
				: new McpJsonObject(Map.of("description", new McpJsonString(description)));
		McpJsonObject schema = new McpJsonObject(Map.of("type", new McpJsonString("object")));
		for (int index = count - 1; index >= 0; --index) {
			String name = name(index);
			builder.tool(McpNormalizedOperation.tool(new McpNormalizedToolDescriptor(
					name, schema, Optional.empty(), fields, McpJsonObject.empty()), McpMirroredHeaderPlan.empty()));
			builder.prompt(new McpNormalizedPromptDescriptor(name, List.of(), fields, McpJsonObject.empty()));
			builder.exactResource(new McpNormalizedResourceDescriptor(identity(Kind.RESOURCES, index), name,
					fields, McpJsonObject.empty(), McpResourceCachePolicy.privateNoCache()));
			builder.resourceTemplate(new McpNormalizedResourceTemplateDescriptor(identity(Kind.TEMPLATES, index), name,
					fields, McpJsonObject.empty(), McpResourceCachePolicy.privateNoCache()));
		}
		return builder.build();
	}

	private Enumeration enumerate(int port, String revision, Kind kind, McpJsonLimits limits) throws Exception {
		List<String> identities = new ArrayList<>();
		Set<String> cursors = new HashSet<>();
		String cursor = null;
		int pages = 0;
		int maximumBytes = 0;
		long maximumNodes = 0;
		do {
			String params = cursor == null ? "" : ",\"params\":{\"cursor\":" + JSON.toJson(new McpJsonString(cursor)) + "}";
			String body = "{\"jsonrpc\":\"2.0\",\"id\":\"page\",\"method\":\"" + kind.method + "\"" + params + "}";
			HttpResponse<byte[]> response = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
					.timeout(remainingRequestWait()).header("Content-Type", "application/json")
					.header("Accept", "application/json, text/event-stream").header("MCP-Protocol-Version", revision)
					.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
					HttpResponse.BodyHandlers.ofByteArray());
			String responseText = new String(response.body(), StandardCharsets.UTF_8);
			assertEquals(200, response.statusCode(), responseText);
			assertTrue(response.body().length <= limits.maximumOutputBytes());
			McpJsonObject envelope = (McpJsonObject) JSON.parse(response.body());
			assertFalse(envelope.members().containsKey("error"), responseText);
			long nodeCount = nodes(envelope);
			assertTrue(nodeCount <= limits.maximumNodeCount(), "The full envelope counts against the node budget.");
			maximumBytes = Math.max(maximumBytes, response.body().length);
			maximumNodes = Math.max(maximumNodes, nodeCount);
			McpJsonObject result = (McpJsonObject) envelope.members().get("result");
			McpJsonArray descriptors = (McpJsonArray) result.members().get(kind.member);
			List<String> pageIdentities = descriptors.values().stream().map(value -> (McpJsonObject) value)
					.map(value -> ((McpJsonString) value.members().get(kind.key)).value()).toList();
			identities.addAll(pageIdentities);
			cursor = result.members().containsKey("nextCursor")
					? ((McpJsonString) result.members().get("nextCursor")).value() : null;
			if (cursor != null) {
				assertFalse(pageIdentities.isEmpty());
				assertTrue(cursors.add(cursor));
				assertTrue(cursor.getBytes(StandardCharsets.UTF_8).length <= 2_048);
			}
			assertTrue(++pages <= 100, "Continuation must terminate.");
		} while (cursor != null);
		assertEquals(identities.size(), new HashSet<>(identities).size());
		return new Enumeration(identities, pages, maximumBytes, maximumNodes);
	}

	private static McpJsonLimits limits(int maximumNodes, int maximumBytes) {
		McpJsonLimits production = McpJsonLimits.productionDefaults();
		return new McpJsonLimits(production.maximumInputBytes(), production.maximumNestingDepth(),
				production.maximumTokenLengthInCharacters(), production.maximumStringLengthInCharacters(),
				production.maximumNumberLengthInCharacters(), production.maximumExponentMagnitude(),
				maximumNodes, maximumBytes);
	}

	private static McpHttpServerRuntime runtime(McpNormalizedEndpoint endpoint,
			Map<String, McpNormalizedEndpoint> revisionEndpoints, McpJsonLimits limits) {
		McpHttpEndpointPolicy policy = McpHttpEndpointPolicy.forDiscovery(CorsAuthorizer.rejectAllInstance(),
				ignored -> McpAdmissionDecision.acceptedAnonymous());
		McpHttpEndpointBinding binding = new McpHttpEndpointBinding(policy, endpoint, McpApplicationRequestRouter.empty(),
				observationWithPublicContext(), List.of(), Optional.empty(), revisionEndpoints);
		return new McpHttpServerRuntime(McpHttpTransportConfiguration.productionDefaults(0), List.of(binding), limits,
				McpApplicationExecutionConfiguration.productionDefaults(), McpApplicationClock.SYSTEM,
				McpApplicationHandlerExecutorFactory.production(), ignored -> {}, ignored -> {});
	}

	private static McpRuntimeObservationSink observationWithPublicContext() {
		McpRequestContext context = (McpRequestContext) Proxy.newProxyInstance(McpRequestContext.class.getClassLoader(),
				new Class<?>[]{McpRequestContext.class}, (proxy, method, arguments) -> {
			if (method.getReturnType() == Optional.class) return Optional.empty();
			if (method.getReturnType() == Map.class) return Map.of();
			if (method.getReturnType() == String.class) return "legacy-pagination-budget";
			if (method.getReturnType() == boolean.class) return false;
			return null;
		});
		return ignored -> new McpRuntimeRequestObservation() {
			@Override public @NonNull Optional<@NonNull McpRequestContext> publicContext() { return Optional.of(context); }
			@Override public void didFinish(@NonNull McpRequestOutcome outcome, McpJsonRpcError error,
					@NonNull Duration duration, @NonNull List<@NonNull Throwable> throwables) { }
		};
	}

	private static long nodes(McpJsonValue value) {
		long count = 1;
		if (value instanceof McpJsonObject object)
			for (McpJsonValue member : object.members().values()) count += nodes(member);
		else if (value instanceof McpJsonArray array)
			for (McpJsonValue member : array.values()) count += nodes(member);
		return count;
	}
	private static String name(int index) { return "item-" + String.format(Locale.ROOT, "%05d", index); }
	private static String identity(Kind kind, int index) {
		return switch (kind) {
			case TOOLS, PROMPTS -> name(index);
			case RESOURCES -> "catalog://" + name(index);
			case TEMPLATES -> "catalog://" + name(index) + "/{value}";
		};
	}
	private static List<String> expected(Kind kind, int count) {
		List<String> identities = new ArrayList<>(count);
		for (int index = 0; index < count; ++index) identities.add(identity(kind, index));
		return identities;
	}
	private enum Kind {
		TOOLS("tools/list", "tools", "name"), PROMPTS("prompts/list", "prompts", "name"),
		RESOURCES("resources/list", "resources", "uri"), TEMPLATES("resources/templates/list", "resourceTemplates", "uriTemplate");
		final String method;
		final String member;
		final String key;
		Kind(String method, String member, String key) { this.method = method; this.member = member; this.key = key; }
	}
	private record Enumeration(List<String> identities, int pages, int maximumBytes, long maximumNodes) { }
}
