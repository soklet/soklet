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

import java.lang.reflect.Modifier;
import java.math.BigDecimal;
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
				.withNameAndVersion("catalog", "4.0.0")
				.title("Catalog MCP")
				.description("Catalog tools and resources")
				.websiteUrl(URI.create("https://catalog.example/mcp"))
				.build();

		Assertions.assertEquals("catalog", implementation.getName());
		Assertions.assertEquals("4.0.0", implementation.getVersion());
		Assertions.assertEquals("Catalog MCP", implementation.getTitle().orElseThrow());
		Assertions.assertEquals("Catalog tools and resources",
				implementation.getDescription().orElseThrow());
		Assertions.assertEquals(URI.create("https://catalog.example/mcp"),
				implementation.getWebsiteUrl().orElseThrow());

		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpImplementation.withNameAndVersion(" ", "4.0.0"));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpImplementation.withNameAndVersion("catalog", ""));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpImplementation.withNameAndVersion("catalog", "4.0.0")
						.websiteUrl(URI.create("relative")));
	}

	@Test
	public void blankOptionalImplementationTextIsAbsent() {
		McpImplementation implementation = McpImplementation
				.withNameAndVersion("catalog", "4.0.0")
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
						McpJsonNumber.fromValue(java.math.BigDecimal.valueOf(4))));
	}

	@Test
	public void clientCapabilitiesCanBeConstructedForApplicationTests() {
		McpJsonObject extension = McpJsonObject.builder()
				.put("enabled", true)
				.build();
		McpJsonObject json = McpJsonObject.builder()
				.put("roots", McpJsonObject.emptyInstance())
				.put("sampling", McpJsonObject.builder()
						.put("context", McpJsonObject.emptyInstance())
						.build())
				.put("extensions", McpJsonObject.builder()
						.put("example.test", extension)
						.put("ignored", "not-an-object")
						.build())
				.build();

		McpClientCapabilities capabilities =
				McpClientCapabilities.fromJson(json);

		Assertions.assertTrue(capabilities.supports(McpClientCapability.ROOTS));
		Assertions.assertTrue(
				capabilities.supports(McpClientCapability.SAMPLING));
		Assertions.assertTrue(
				capabilities.supports(McpClientCapability.SAMPLING_CONTEXT));
		Assertions.assertFalse(
				capabilities.supports(McpClientCapability.SAMPLING_TOOLS));
		Assertions.assertSame(extension,
				capabilities.findExtension("example.test").orElseThrow());
		Assertions.assertTrue(capabilities.findExtension("ignored").isEmpty());
		Assertions.assertSame(json, capabilities.toJson());
		Assertions.assertThrows(UnsupportedOperationException.class,
				() -> capabilities.getExtensions().clear());
		Assertions.assertThrows(NullPointerException.class,
				() -> McpClientCapabilities.fromJson(null));
	}

	@Test
	public void jsonObjectsAndArraysHaveDeepStructuralEquality() {
		McpJsonObject first = McpJsonObject.builder()
				.put("name", "catalog")
				.put("values", McpJsonArray.builder()
						.add(1)
						.add(McpJsonObject.builder().put("enabled", true).build())
						.build())
				.build();
		McpJsonObject equalWithDifferentMemberOrder = McpJsonObject.builder()
				.put("values", McpJsonArray.builder()
						.add(1)
						.add(McpJsonObject.builder().put("enabled", true).build())
						.build())
				.put("name", "catalog")
				.build();
		McpJsonObject unequalArrayOrder = McpJsonObject.builder()
				.put("name", "catalog")
				.put("values", McpJsonArray.builder()
						.add(McpJsonObject.builder().put("enabled", true).build())
						.add(1)
						.build())
				.build();

		Assertions.assertEquals(first, equalWithDifferentMemberOrder);
		Assertions.assertEquals(first.hashCode(),
				equalWithDifferentMemberOrder.hashCode());
		Assertions.assertNotEquals(first, unequalArrayOrder);
		Assertions.assertNotEquals(first, McpJsonObject.emptyInstance());
	}

	@Test
	public void jsonArrayConveniencesMirrorJsonObjectBuilder() {
		Assertions.assertSame(McpJsonArray.emptyInstance(),
				McpJsonArray.fromElements(List.of()));
		Assertions.assertSame(McpJsonArray.emptyInstance(),
				McpJsonArray.builder().build());

		McpJsonArray values = McpJsonArray.builder()
				.add(7)
				.add(8L)
				.add(9.5D)
				.build();
		Assertions.assertEquals(List.of(
				McpJsonNumber.fromValue(BigDecimal.valueOf(7)),
				McpJsonNumber.fromValue(BigDecimal.valueOf(8)),
				McpJsonNumber.fromValue(BigDecimal.valueOf(9.5))),
				values.getElements());
		Assertions.assertThrows(NullPointerException.class,
				() -> McpJsonArray.builder().add((Integer) null));
		Assertions.assertThrows(NullPointerException.class,
				() -> McpJsonArray.builder().add((Long) null));
		Assertions.assertThrows(NullPointerException.class,
				() -> McpJsonArray.builder().add((Double) null));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpJsonArray.builder().add(Double.NaN));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpJsonArray.builder().add(Double.POSITIVE_INFINITY));
	}

	@Test
	public void jsonScalarValueObjectsHaveIntentionalValueSemanticsAndRedactedText() {
		String secret = "secret-application-data";
		McpJsonString string = McpJsonString.fromValue(secret);
		McpJsonString sameString = McpJsonString.fromValue(secret);
		McpJsonBoolean bool = McpJsonBoolean.fromValue(true);
		McpJsonBoolean sameBool = McpJsonBoolean.fromValue(true);
		McpJsonNumber number = McpJsonNumber.fromValue(
				java.math.BigDecimal.valueOf(42));
		McpJsonNumber sameNumber = McpJsonNumber.fromValue(
				java.math.BigDecimal.valueOf(42));

		Assertions.assertEquals(secret, string.getValue());
		Assertions.assertEquals(string, sameString);
		Assertions.assertEquals(string.hashCode(), sameString.hashCode());
		Assertions.assertEquals(Boolean.TRUE, bool.getValue());
		Assertions.assertEquals(bool, sameBool);
		Assertions.assertEquals(bool.hashCode(), sameBool.hashCode());
		Assertions.assertEquals(java.math.BigDecimal.valueOf(42), number.getValue());
		Assertions.assertEquals(number, sameNumber);
		Assertions.assertEquals(number.hashCode(), sameNumber.hashCode());
		Assertions.assertNotEquals(number, McpJsonNumber.fromValue(
				new java.math.BigDecimal("42.0")));
		Assertions.assertEquals("McpJsonString{value=<redacted>}", string.toString());
		Assertions.assertEquals("McpJsonBoolean{value=<redacted>}", bool.toString());
		Assertions.assertEquals("McpJsonNumber{value=<redacted>}", number.toString());
		Assertions.assertFalse(string.toString().contains(secret));
	}

	@Test
	public void jsonAndEndpointScalarInputsRejectNullReferences() {
		Assertions.assertThrows(NullPointerException.class,
				() -> McpJsonString.fromValue(null));
		Assertions.assertThrows(NullPointerException.class,
				() -> McpJsonBoolean.fromValue(null));
		Assertions.assertThrows(NullPointerException.class,
				() -> McpJsonNumber.fromValue(null));
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
		Assertions.assertThrows(NullPointerException.class,
				() -> McpJsonRpcError.fromApplication(null, "failure"));
		Assertions.assertThrows(NullPointerException.class,
				() -> McpAdmissionRejection.withStatusCodeAndError(null,
						McpJsonRpcError.fromApplication(1_000, "failure")));
		Assertions.assertThrows(NullPointerException.class,
				() -> McpAdmissionRejection.withStatusCodeAndError(400,
						McpJsonRpcError.fromApplication(1_000, "failure"))
						.statusCode(null));
	}

	@Test
	public void applicationErrorFactoriesRejectBothSokletOwnedCodesWithAndWithoutData() {
		McpJsonObject data = McpJsonObject.builder()
				.put("fixture", "reserved-code")
				.build();
		for (Integer code : List.of(
				McpJsonRpcError.SOKLET_RATE_LIMIT_ERROR_CODE,
				McpJsonRpcError.SOKLET_STRICT_UNKNOWN_MIRRORED_HEADER_ERROR_CODE)) {
			Assertions.assertThrows(IllegalArgumentException.class,
					() -> McpJsonRpcError.fromApplication(code, "reserved"));
			Assertions.assertThrows(IllegalArgumentException.class,
					() -> McpJsonRpcError.fromApplication(
							code, "reserved", data));
		}
	}

	@Test
	public void operationFreeEndpointIsValidAndNormalizesItsPath() {
		McpImplementation serverInformation = McpImplementation
				.withNameAndVersion("catalog", "4.0.0")
				.build();
		McpEndpoint endpoint = McpEndpoint.withPath(" /mcp// ", serverInformation)
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
		McpEndpoint endpoint = McpEndpoint.withPath("/mcp", McpImplementation
						.withNameAndVersion("test-server", "1.0").build())
				.serverInformationIncluded(false)
				.build();

		Assertions.assertFalse(endpoint.isServerInformationIncluded());
	}

	@Test
	public void endpointEntrypointRequiresImplementationAndAValidPath() {
		McpImplementation implementation = implementation();
		Assertions.assertThrows(NullPointerException.class,
				() -> McpEndpoint.withPath("/mcp", null));
		Assertions.assertThrows(NullPointerException.class,
				() -> McpEndpoint.withPath(null, implementation));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpEndpoint.withPath("mcp", implementation));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpEndpoint.withPath("/", implementation));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpEndpoint.withPath("/mcp?tenant=1", implementation));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpEndpoint.withPath("/mcp#fragment", implementation));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpEndpoint.withPath("/mcp", implementation)
						.instructions(" "));
	}

	@Test
	public void endpointPathUsesTheListenerWireFormAndByteBound() {
		String asciiBoundary = "/" + "a".repeat(8_191);
		String percentEncodedBoundary = "/" + "%C3%A9".repeat(1_365) + "a";

		Assertions.assertEquals(asciiBoundary,
				endpoint(asciiBoundary).getPath());
		Assertions.assertEquals(percentEncodedBoundary,
				endpoint(percentEncodedBoundary).getPath());
		Assertions.assertEquals("/caf%C3%A9",
				endpoint("/caf%C3%A9").getPath());
		McpImplementation implementation = implementation();
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpEndpoint.withPath("/café", implementation));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpEndpoint.withPath(asciiBoundary + "a", implementation));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpEndpoint.withPath(percentEncodedBoundary + "a", implementation));
	}

	@Test
	public void endpointRegistryIsAnOwnedImmutableValue() {
		Assertions.assertFalse(McpEndpointRegistry.class.isInterface());
		Assertions.assertTrue(Modifier.isFinal(
				McpEndpointRegistry.class.getModifiers()));
		McpEndpoint catalog = endpoint("/catalog");
		McpEndpoint inventory = endpoint("/inventory");
		List<McpEndpoint> mutableEndpoints = new ArrayList<>(List.of(catalog));
		McpEndpointRegistry registry = McpEndpointRegistry.fromEndpoints(mutableEndpoints);

		mutableEndpoints.add(inventory);
		Assertions.assertEquals(List.of(catalog), registry.getEndpoints());
		Assertions.assertThrows(UnsupportedOperationException.class,
				() -> registry.getEndpoints().add(inventory));

		McpEndpointRegistry expandedRegistry = registry.withEndpoint(inventory);
		Assertions.assertEquals(List.of(catalog), registry.getEndpoints());
		Assertions.assertEquals(List.of(catalog, inventory),
				expandedRegistry.getEndpoints());
	}

	@Test
	public void endpointRegistryRequiresEndpointsWithDistinctNormalizedPaths() {
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpEndpointRegistry.fromEndpoints(List.of()));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpEndpointRegistry.fromEndpoints(
						List.of(endpoint("/mcp"), endpoint("/mcp/"))));

		McpEndpointRegistry registry = McpEndpointRegistry.fromEndpoints(
				List.of(endpoint("/mcp")));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> registry.withEndpoint(endpoint("/mcp//")));
	}

	@Test
	public void lifecycleEnumsExposeTheReviewedValues() {
		Assertions.assertArrayEquals(new McpAbsentOriginPolicy[]{
				McpAbsentOriginPolicy.ALLOW,
				McpAbsentOriginPolicy.REQUIRE_ORIGIN
		}, McpAbsentOriginPolicy.values());
		Assertions.assertArrayEquals(new McpServerStatus[]{
				McpServerStatus.NOT_STARTED,
				McpServerStatus.STARTING,
				McpServerStatus.RUNNING,
				McpServerStatus.SHUTTING_DOWN,
				McpServerStatus.TERMINATED,
				McpServerStatus.RESIDUAL_ACTIVITY,
				McpServerStatus.TERMINATION_UNKNOWN
		}, McpServerStatus.values());
		Assertions.assertArrayEquals(new ShutdownComponentDisposition[]{
				ShutdownComponentDisposition.NOT_STARTED,
				ShutdownComponentDisposition.GRACEFUL_TERMINATION,
				ShutdownComponentDisposition.FORCED_TERMINATION,
				ShutdownComponentDisposition.UNEXPECTED_TERMINATION,
				ShutdownComponentDisposition.RESIDUAL_ACTIVITY,
				ShutdownComponentDisposition.TERMINATION_UNKNOWN
		}, ShutdownComponentDisposition.values());
	}

	@Test
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
		Assertions.assertNull(McpLogLevel.class.getAnnotation(Deprecated.class),
				"MCP lifecycle deprecation must not imply Java API deprecation");
	}

	private static McpEndpoint endpoint(String path) {
		return McpEndpoint.withPath(path, implementation())
				.build();
	}

	private static McpImplementation implementation() {
		return McpImplementation.withNameAndVersion("server", "4.0.0")
				.build();
	}
}
