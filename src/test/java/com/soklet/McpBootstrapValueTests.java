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

package com.soklet;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Coverage for the public MCP bootstrap value types.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public class McpBootstrapValueTests {
	@Test
	public void implementationMetadataIsImmutableAndValidatesRequiredValues() {
		McpImplementation implementation = McpImplementation
				.withNameAndVersion("catalog", "3.6.0")
				.title("Catalog MCP")
				.description("Catalog tools and resources")
				.websiteUrl(URI.create("https://catalog.example/mcp"))
				.build();

		Assertions.assertEquals("catalog", implementation.getName());
		Assertions.assertEquals("3.6.0", implementation.getVersion());
		Assertions.assertEquals("Catalog MCP", implementation.getTitle().orElseThrow());
		Assertions.assertEquals("Catalog tools and resources",
				implementation.getDescription().orElseThrow());
		Assertions.assertEquals(URI.create("https://catalog.example/mcp"),
				implementation.getWebsiteUrl().orElseThrow());

		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpImplementation.withNameAndVersion(" ", "3.6.0"));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpImplementation.withNameAndVersion("catalog", ""));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpImplementation.withNameAndVersion("catalog", "3.6.0")
						.websiteUrl(URI.create("relative")));
	}

	@Test
	public void blankOptionalImplementationTextIsAbsent() {
		McpImplementation implementation = McpImplementation
				.withNameAndVersion("catalog", "3.6.0")
				.title(" ")
				.description("\t")
				.build();

		Assertions.assertTrue(implementation.getTitle().isEmpty());
		Assertions.assertTrue(implementation.getDescription().isEmpty());
		Assertions.assertTrue(implementation.getWebsiteUrl().isEmpty());
	}

	@Test
	public void jsonObjectsAreImmutableAndPreserveInsertionOrder() {
		McpJsonObject object = McpJsonObject.builder()
				.put("third", 3)
				.put("first", 1)
				.put("second", 2)
				.build();

		Assertions.assertEquals(List.of("third", "first", "second"),
				List.copyOf(object.getMembers().keySet()));
		Assertions.assertThrows(UnsupportedOperationException.class,
				() -> object.getMembers().put("fourth",
						new McpJsonNumber(java.math.BigDecimal.valueOf(4))));
	}

	@Test
	public void jsonAndEndpointScalarInputsRejectNullReferences() {
		Assertions.assertThrows(NullPointerException.class,
				() -> new McpJsonBoolean(null));
		Assertions.assertThrows(NullPointerException.class,
				() -> McpJsonArray.builder().add((Boolean) null));
		Assertions.assertThrows(NullPointerException.class,
				() -> McpJsonObject.builder().put("value", (Integer) null));
		Assertions.assertThrows(NullPointerException.class,
				() -> McpJsonObject.builder().put("value", (Long) null));
		Assertions.assertThrows(NullPointerException.class,
				() -> McpJsonObject.builder().put("value", (Double) null));
		Assertions.assertThrows(NullPointerException.class,
				() -> McpJsonObject.builder().put("value", (Boolean) null));
		Assertions.assertSame(Boolean.TRUE,
				endpoint("/mcp").isServerInformationIncluded());
		Assertions.assertThrows(NullPointerException.class, () -> McpEndpoint
				.withPath("/mcp")
				.serverInformation(McpImplementation.withNameAndVersion(
						"server", "3.6.0").build())
				.includeServerInformation(null));
		Assertions.assertThrows(NullPointerException.class,
				() -> McpJsonRpcError.fromApplication(null, "failure"));
		Assertions.assertThrows(NullPointerException.class,
				() -> McpRequestRejection.withStatusCodeAndError(null,
						McpJsonRpcError.fromApplication(1_000, "failure")));
		Assertions.assertThrows(NullPointerException.class,
				() -> McpRequestRejection.withStatusCodeAndError(400,
						McpJsonRpcError.fromApplication(1_000, "failure"))
						.statusCode(null));
	}

	@Test
	public void operationFreeEndpointIsValidAndNormalizesItsPath() {
		McpImplementation serverInformation = McpImplementation
				.withNameAndVersion("catalog", "3.6.0")
				.build();
		McpEndpoint endpoint = McpEndpoint.withPath(" /mcp// ")
				.serverInformation(serverInformation)
				.instructions("Use this endpoint for catalog discovery.")
				.build();

		Assertions.assertEquals("/mcp", endpoint.getPath());
		Assertions.assertSame(serverInformation, endpoint.getServerInformation());
		Assertions.assertTrue(endpoint.isServerInformationIncluded());
		Assertions.assertEquals("Use this endpoint for catalog discovery.",
				endpoint.getInstructions().orElseThrow());
	}

	@Test
	public void endpointCanOmitServerInformationFromResponseMetadata() {
		McpEndpoint endpoint = McpEndpoint.withPath("/mcp")
				.serverInformation(McpImplementation
						.withNameAndVersion("test-server", "1.0").build())
				.includeServerInformation(false)
				.build();

		Assertions.assertFalse(endpoint.isServerInformationIncluded());
	}

	@Test
	public void endpointRequiresServerInformationAndAValidPath() {
		Assertions.assertThrows(IllegalStateException.class,
				() -> McpEndpoint.withPath("/mcp").build());
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpEndpoint.withPath("mcp"));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpEndpoint.withPath("/"));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpEndpoint.withPath("/mcp?tenant=1"));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpEndpoint.withPath("/mcp#fragment"));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpEndpoint.withPath("/mcp")
						.instructions(" "));
	}

	@Test
	public void resolverDefensivelyCopiesAndWithEndpointDoesNotMutateTheOriginal() {
		McpEndpoint catalog = endpoint("/catalog");
		McpEndpoint inventory = endpoint("/inventory");
		List<McpEndpoint> mutableEndpoints = new ArrayList<>(List.of(catalog));
		McpHandlerResolver resolver = McpHandlerResolver.fromEndpoints(mutableEndpoints);

		mutableEndpoints.add(inventory);
		Assertions.assertEquals(List.of(catalog), resolver.getEndpoints());
		Assertions.assertThrows(UnsupportedOperationException.class,
				() -> resolver.getEndpoints().add(inventory));

		McpHandlerResolver expandedResolver = resolver.withEndpoint(inventory);
		Assertions.assertEquals(List.of(catalog), resolver.getEndpoints());
		Assertions.assertEquals(List.of(catalog, inventory),
				expandedResolver.getEndpoints());
	}

	@Test
	public void resolverRequiresEndpointsWithDistinctNormalizedPaths() {
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpHandlerResolver.fromEndpoints(List.of()));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpHandlerResolver.fromEndpoints(
						List.of(endpoint("/mcp"), endpoint("/mcp/"))));

		McpHandlerResolver resolver = McpHandlerResolver.fromEndpoints(
				List.of(endpoint("/mcp")));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> resolver.withEndpoint(endpoint("/mcp//")));
	}

	@Test
	public void lifecycleEnumsExposeTheReviewedValues() {
		Assertions.assertArrayEquals(new McpAbsentOriginPolicy[]{
				McpAbsentOriginPolicy.ALLOW,
				McpAbsentOriginPolicy.REQUIRE_ORIGIN
		}, McpAbsentOriginPolicy.values());
		Assertions.assertArrayEquals(new McpServerStatus[]{
				McpServerStatus.STOPPED,
				McpServerStatus.STARTED,
				McpServerStatus.STOPPED_WITH_RESIDUAL_HANDLERS
		}, McpServerStatus.values());
		Assertions.assertArrayEquals(new McpShutdownOutcome[]{
				McpShutdownOutcome.CLEAN,
				McpShutdownOutcome.RESIDUAL_HANDLERS
		}, McpShutdownOutcome.values());
	}

	@Test
	@SuppressWarnings("deprecation")
	public void deprecatedLogLevelExposesTheExactWireVocabulary() {
		Assertions.assertArrayEquals(new McpLogLevel[]{
				McpLogLevel.DEBUG,
				McpLogLevel.INFO,
				McpLogLevel.NOTICE,
				McpLogLevel.WARNING,
				McpLogLevel.ERROR,
				McpLogLevel.CRITICAL,
				McpLogLevel.ALERT,
				McpLogLevel.EMERGENCY
		}, McpLogLevel.values());
		Deprecated deprecated = McpLogLevel.class.getAnnotation(Deprecated.class);
		Assertions.assertNotNull(deprecated);
		Assertions.assertEquals("3.6.0", deprecated.since());
		Assertions.assertFalse(deprecated.forRemoval());
	}

	private static McpEndpoint endpoint(String path) {
		return McpEndpoint.withPath(path)
				.serverInformation(McpImplementation
						.withNameAndVersion("server", "3.6.0")
						.build())
				.build();
	}
}
