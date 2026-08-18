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

import com.soklet.annotation.McpHeader;
import com.soklet.converter.TypeReference;
import org.junit.jupiter.api.Test;

import javax.annotation.concurrent.ThreadSafe;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the public staged MCP tool-registration surface.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
class McpToolRegistrationTests {
	@Test
	void typedRegistrationCompilesSchemasAndInvokesThroughOneBindingPlan()
			throws Exception {
		AtomicReference<McpJsonObject> rawArguments = new AtomicReference<>();
		McpToolRegistration<Arguments> registration =
				McpToolRegistration.withName("catalog.search")
						.types(Arguments.class, Result.class)
						.handler((request, arguments, features) -> {
							rawArguments.set(arguments.getRawArguments());
							assertEquals(" exact ", arguments.getConvertedArguments().query());
							assertEquals(List.of(2, 5),
									arguments.getConvertedArguments().pageSizes());
							return new Result(List.of(new Item("a", 7)));
						})
						.title("Catalog search")
						.description("Searches the catalog")
						.metadata(McpJsonObject.builder()
								.put("owner", "catalog")
								.build())
						.build();
		McpJsonObject input = McpJsonObject.builder()
				.put("query", " exact ")
				.put("pageSizes", McpJsonArray.fromElements(List.of(
						McpJsonNumber.fromValue(java.math.BigDecimal.valueOf(2)),
						McpJsonNumber.fromValue(java.math.BigDecimal.valueOf(5)))))
				.build();

		McpCompleteResult result = assertInstanceOf(McpCompleteResult.class,
				registration.invoke(requestContext(), input,
						McpInvocationFeatures.fromFeatures(Map.of())));
		McpToolOutput output = assertInstanceOf(McpToolOutput.class,
				result.getPayload());
		McpJsonObject structured = assertInstanceOf(McpJsonObject.class,
				output.getStructuredContent().orElseThrow());

		assertSame(input, rawArguments.get());
		assertEquals("catalog.search", registration.getName());
		assertEquals("Catalog search", registration.getTitle().orElseThrow());
		assertEquals("Searches the catalog",
				registration.getDescription().orElseThrow());
		assertEquals("catalog", ((McpJsonString) registration.getMetadata()
				.find("owner").orElseThrow()).getValue());
		assertEquals(Arguments.class, registration.getArgumentType());
		assertEquals(Result.class, registration.getOutputType().orElseThrow());
		assertEquals(McpJsonString.fromValue("object"), registration.getInputSchema()
				.getDocument().find("type").orElseThrow());
		assertEquals(McpJsonString.fromValue("object"), registration.getOutputSchema()
				.orElseThrow().getDocument().find("type").orElseThrow());
		assertTrue(registration.isStructuredContentTextMirroringEnabled());
		McpJsonArray items = assertInstanceOf(McpJsonArray.class,
				structured.find("items").orElseThrow());
		McpJsonObject item = assertInstanceOf(McpJsonObject.class,
				items.getElements().get(0));
		assertEquals(McpJsonString.fromValue("a"),
				item.find("identifier").orElseThrow());
	}

	@Test
	void mirroredHeadersArePublishedAndRejectedOutsideTheirInputContract() {
		McpToolRegistration<MirroredArguments> registration =
				McpToolRegistration.withName("mirrored")
						.argumentType(MirroredArguments.class)
						.handler((request, arguments, features) ->
								McpCompleteResult.fromToolText("done"))
						.build();
		McpJsonObject inputSchema = registration.getInputSchema().getDocument();

		assertEquals(McpJsonString.fromValue("Tenant"),
				property(inputSchema, "tenant").find("x-mcp-header")
						.orElseThrow());
		McpJsonObject routing = property(inputSchema, "routing");
		assertEquals(McpJsonString.fromValue("Dry-Run"),
				property(routing, "dryRun").find("x-mcp-header")
						.orElseThrow());
		assertEquals(McpJsonString.fromValue("Shard"),
				property(routing, "shard").find("x-mcp-header")
						.orElseThrow());
		assertTrue(property(inputSchema, "unmirrored")
				.find("x-mcp-header").isEmpty());

		assertThrows(IllegalArgumentException.class,
				() -> McpToolRegistration.withName("invalid-header-name")
						.argumentType(InvalidHeaderName.class));
		assertThrows(IllegalArgumentException.class,
				() -> McpToolRegistration.withName("duplicate-header-name")
						.argumentType(DuplicateHeaders.class));
		assertThrows(IllegalArgumentException.class,
				() -> McpToolRegistration.withName("nonprimitive-header")
						.argumentType(NonprimitiveHeader.class));
		assertThrows(IllegalArgumentException.class,
				() -> McpToolRegistration.withName("mirrored-output")
						.types(MirroredArguments.class, MirroredOutput.class));
	}

	@Test
	void allClassAndTypeReferenceCombinationsAreAvailable() {
		TypeReference<Arguments> argumentType =
				new TypeReference<>() {};
		TypeReference<List<Item>> results =
				new TypeReference<>() {};

		assertEquals(Result.class, McpToolRegistration.withName("one")
				.types(Arguments.class, Result.class)
				.handler((request, arguments, features) ->
						new Result(List.of()))
				.build().getOutputType().orElseThrow());
		assertEquals(results.getType(), McpToolRegistration.withName("two")
				.types(Arguments.class, results)
				.handler((request, arguments, features) -> List.of())
				.build().getOutputType().orElseThrow());
		assertEquals(argumentType.getType(), McpToolRegistration.withName("three")
				.types(argumentType, Result.class)
				.handler((request, arguments, features) ->
						new Result(List.of()))
				.build().getArgumentType());
		assertEquals(results.getType(), McpToolRegistration.withName("four")
				.types(argumentType, results)
				.handler((request, arguments, features) -> List.of())
				.build().getOutputType().orElseThrow());
	}

	@Test
	void advancedAndRawRegistrationsUseTheirSelectedArgumentModels()
			throws Exception {
		McpToolRegistration<Arguments> advanced =
				McpToolRegistration.withName("advanced")
						.argumentType(Arguments.class)
						.handler((request, arguments, features) ->
								McpCompleteResult.fromToolText(
										arguments.getConvertedArguments().query()))
						.mirrorStructuredContentAsText(false)
						.build();
		McpToolRegistration<McpJsonObject> raw =
				McpToolRegistration.withName("raw")
						.jsonArguments()
						.handler((request, arguments, features) ->
								McpCompleteResult.fromToolStructuredContent(
										arguments.getConvertedArguments()))
						.build();
		McpJsonObject input = argumentsJson();
		assertThrows(NullPointerException.class, () -> McpToolRegistration
				.withName("advanced-null-mirror")
				.argumentType(Arguments.class)
				.handler((request, arguments, features) ->
						McpCompleteResult.fromToolText("done"))
				.mirrorStructuredContentAsText(null));
		assertThrows(NullPointerException.class, () -> McpToolRegistration
				.withName("typed-null-mirror")
				.types(Arguments.class, Result.class)
				.handler((request, arguments, features) -> new Result(List.of()))
				.mirrorStructuredContentAsText(null));

		McpCompleteResult advancedResult =
				assertInstanceOf(McpCompleteResult.class,
						advanced.invoke(requestContext(), input,
								McpInvocationFeatures.fromFeatures(Map.of())));
		McpCompleteResult rawResult = assertInstanceOf(McpCompleteResult.class,
				raw.invoke(requestContext(), input,
						McpInvocationFeatures.fromFeatures(Map.of())));

		assertEquals("exact", ((McpTextContent) ((McpToolOutput)
				advancedResult.getPayload()).getContent().get(0)).getText());
		assertSame(input, ((McpToolOutput) rawResult.getPayload())
				.getStructuredContent().orElseThrow());
		assertFalse(advanced.isStructuredContentTextMirroringEnabled());
		assertTrue(raw.isStructuredContentTextMirroringEnabled());
		assertEquals(McpJsonObject.class, raw.getArgumentType());
		assertEquals(McpJsonString.fromValue("object"),
				raw.getInputSchema().getDocument().find("type").orElseThrow());
		assertTrue(raw.getOutputType().isEmpty());
		assertTrue(raw.getOutputSchema().isEmpty());
	}

	@Test
	void validatesNamesTypesArgumentsAndHandlerResultsSynchronously() {
		assertThrows(IllegalArgumentException.class,
				() -> McpToolRegistration.withName(""));
		assertThrows(IllegalArgumentException.class,
				() -> McpToolRegistration.withName("contains spaces"));
		assertThrows(IllegalArgumentException.class,
				() -> McpToolRegistration.withName("a".repeat(129)));
		assertThrows(IllegalArgumentException.class,
				() -> McpToolRegistration.withName("bad-input")
						.argumentType(String.class));
		assertThrows(IllegalArgumentException.class,
				() -> McpToolRegistration.withName("bad-output")
						.types(Arguments.class, String.class));

		McpToolRegistration<Arguments> nullResult =
				McpToolRegistration.withName("null-result")
						.types(Arguments.class, Result.class)
						.handler((request, arguments, features) -> null)
						.build();
		assertThrows(NullPointerException.class, () -> nullResult.invoke(
				requestContext(), argumentsJson(),
				McpInvocationFeatures.fromFeatures(Map.of())));
	}

	@Test
	void distinguishesInvalidArgumentsFromApplicationHandlerFailures() {
		IllegalArgumentException expectedApplicationFailure =
				new IllegalArgumentException("application failure");
		McpToolRegistration<Arguments> registration =
				McpToolRegistration.withName("failure-classification")
						.argumentType(Arguments.class)
						.handler((request, arguments, features) -> {
							throw expectedApplicationFailure;
						})
						.build();
		McpJsonObject invalidArguments = McpJsonObject.builder()
				.put("query", "missing required pageSizes")
				.build();

		McpInvalidToolArgumentsException invalidFailure = assertThrows(
				McpInvalidToolArgumentsException.class,
				() -> registration.invoke(requestContext(), invalidArguments,
						McpInvocationFeatures.fromFeatures(Map.of())));
		assertInstanceOf(IllegalArgumentException.class,
				invalidFailure.getCause());

		IllegalArgumentException applicationFailure = assertThrows(
				IllegalArgumentException.class,
				() -> registration.invoke(requestContext(), argumentsJson(),
						McpInvocationFeatures.fromFeatures(Map.of())));
		assertSame(expectedApplicationFailure, applicationFailure);
		assertFalse(applicationFailure
				instanceof McpInvalidToolArgumentsException);
	}

	@Test
	void conformanceSchemaSeamPreservesEnforcesAndDerivesMirroredHeaders()
			throws Exception {
		McpJsonObject tenantSchema = McpJsonObject.builder()
				.put("type", "string")
				.put("x-mcp-header", "Tenant")
				.build();
		McpJsonObject emailSchema = McpJsonObject.builder()
				.put("type", "string")
				.build();
		McpJsonObject inputSchema = McpJsonObject.builder()
				.put("$schema",
						"https://json-schema.org/draft/2020-12/schema")
				.put("type", "object")
				.put("properties", McpJsonObject.builder()
						.put("tenant", tenantSchema)
						.put("email", emailSchema)
						.build())
				.put("required", McpJsonArray.fromElements(List.of(
						McpJsonString.fromValue("tenant"),
						McpJsonString.fromValue("email"))))
				.put("additionalProperties", false)
				.build();
		AtomicReference<McpJsonObject> decodedArguments = new AtomicReference<>();
		McpToolRegistration<McpJsonObject> registration =
				McpToolRegistration.withName("conformance_schema")
						.conformanceInputSchema(inputSchema)
						.handler((request, arguments, features) -> {
							decodedArguments.set(arguments.getConvertedArguments());
							return McpCompleteResult.fromToolText("done");
						})
						.build();
		McpJsonObject validArguments = McpJsonObject.builder()
				.put("tenant", "tenant-a")
				.put("email", "a@example.test")
				.build();

		assertFalse(Modifier.isPublic(McpToolRegistration.NamedBuilder.class
				.getDeclaredMethod("conformanceInputSchema", McpJsonObject.class)
				.getModifiers()));
		assertSame(inputSchema, registration.getInputSchema().getDocument());
		assertEquals(McpJsonObject.class, registration.getArgumentType());
		assertEquals(1,
				registration.getMirroredHeaderPlan().declarations().size());
		var declaration =
				registration.getMirroredHeaderPlan().declarations().get(0);
		assertEquals("Mcp-Param-Tenant", declaration.headerName());
		assertEquals(List.of("tenant"), declaration.argumentPropertyPath());

		registration.invoke(requestContext(), validArguments,
				McpInvocationFeatures.fromFeatures(Map.of()));
		assertSame(validArguments, decodedArguments.get());
		assertThrows(McpInvalidToolArgumentsException.class,
				() -> registration.invoke(requestContext(),
						McpJsonObject.builder()
								.put("tenant", "tenant-a")
								.build(),
						McpInvocationFeatures.fromFeatures(Map.of())));

		McpJsonObject unsupportedSchema = McpJsonObject.builder()
				.put("type", "object")
				.put("unknown-keyword", true)
				.build();
		assertThrows(IllegalArgumentException.class,
				() -> McpToolRegistration.withName("unsupported_schema")
						.conformanceInputSchema(unsupportedSchema));
	}

	@Test
	void rateLimiterSelectionIsLastCallWins() {
		McpRateLimiter direct =
				context -> McpRateLimitDecision.allowed();
		McpToolRegistration<Arguments> named =
				McpToolRegistration.withName("named")
						.argumentType(Arguments.class)
						.handler((request, arguments, features) ->
								McpCompleteResult.fromToolText("done"))
						.rateLimiter(direct)
						.rateLimiterName("distributed")
						.build();
		McpToolRegistration<Arguments> directLast =
				McpToolRegistration.withName("direct")
						.argumentType(Arguments.class)
						.handler((request, arguments, features) ->
								McpCompleteResult.fromToolText("done"))
						.rateLimiterName("distributed")
						.rateLimiter(direct)
						.build();

		assertEquals("distributed",
				named.getRateLimiterName().orElseThrow());
		assertTrue(named.getRateLimiter().isEmpty());
		assertSame(direct, directLast.getRateLimiter().orElseThrow());
		assertTrue(directLast.getRateLimiterName().isEmpty());
		assertThrows(IllegalArgumentException.class, () ->
				McpToolRegistration.withName("blank-limiter")
						.argumentType(Arguments.class)
						.handler((request, arguments, features) ->
								McpCompleteResult.fromToolText("done"))
						.rateLimiterName(" "));
	}

	@Test
	void invocationFeaturesCopyValidateAndUseExactClassLookup() {
		Feature feature = new Feature();
		Map<Class<?>, Object> mutable = new LinkedHashMap<>();
		mutable.put(FeatureContract.class, feature);
		McpInvocationFeatures features =
				McpInvocationFeatures.fromFeatures(mutable);
		mutable.clear();

		assertSame(feature,
				features.find(FeatureContract.class).orElseThrow());
		assertSame(feature, features.require(FeatureContract.class));
		assertTrue(features.find(Feature.class).isEmpty());
		assertThrows(IllegalStateException.class,
				() -> features.require(Runnable.class));
		Map<Class<?>, Object> invalid = new LinkedHashMap<>();
		invalid.put(Runnable.class, feature);
		assertThrows(IllegalArgumentException.class,
				() -> McpInvocationFeatures.fromFeatures(invalid));
	}

	private static McpJsonObject argumentsJson() {
		return McpJsonObject.builder()
				.put("query", "exact")
				.put("pageSizes", McpJsonArray.fromElements(List.of()))
				.build();
	}

	private static McpJsonObject property(McpJsonObject schema, String name) {
		McpJsonObject properties = assertInstanceOf(McpJsonObject.class,
				schema.find("properties").orElseThrow());
		return assertInstanceOf(McpJsonObject.class,
				properties.find(name).orElseThrow());
	}

	private static McpRequestContext requestContext() {
		return new McpRequestContext() {
			@Override
			public Request getRequest() {
				throw new UnsupportedOperationException();
			}

			@Override
			public McpEndpoint getEndpoint() {
				throw new UnsupportedOperationException();
			}

			@Override
			public Map<String, String> getEndpointPathParameters() {
				return Map.of();
			}

			@Override
			public String getJsonRpcMethod() {
				return "tools/call";
			}

			@Override
			public Optional<McpRequestId> getRequestId() {
				return Optional.of(McpRequestId.fromString("test"));
			}

			@Override
			public String getProtocolVersion() {
				return "2026-07-28";
			}

			@Override
			public Optional<String> getOperationName() {
				return Optional.empty();
			}

			@Override
			public Optional<McpImplementation> getClientInfo() {
				return Optional.empty();
			}

			@Override
			public McpClientCapabilities getClientCapabilities() {
				throw new UnsupportedOperationException();
			}

			@Override
			public McpJsonObject getRequestMetadata() {
				return McpJsonObject.emptyInstance();
			}

			@Override
			@SuppressWarnings("deprecation")
			public Optional<McpLogLevel> getDeprecatedLogLevel() {
				return Optional.empty();
			}

			@Override
			public Optional<TraceContext> getTraceContext() {
				return Optional.empty();
			}

			@Override
			public Map<String, String> getBaggage() {
				return Map.of();
			}

			@Override
			public McpAdmissionIdentity getAdmissionIdentity() {
				return McpAdmissionIdentity.anonymousInstance();
			}
		};
	}

	private interface FeatureContract {
	}

	private static final class Feature implements FeatureContract {
	}

	private record Arguments(String query, List<Integer> pageSizes) {
	}

	private record Result(List<Item> items) {
	}

	private record Item(String identifier, int score) {
	}

	private record MirroredArguments(
			@McpHeader("Tenant") String tenant, Routing routing,
			String unmirrored) {
	}

	private record Routing(@McpHeader("Dry-Run") boolean dryRun,
			@McpHeader("Shard") int shard) {
	}

	private record InvalidHeaderName(@McpHeader("bad name") String value) {
	}

	private record DuplicateHeaders(@McpHeader("Tenant") String first,
			@McpHeader("tenant") boolean second) {
	}

	private record NonprimitiveHeader(@McpHeader("Routing") Routing routing) {
	}

	private record MirroredOutput(@McpHeader("Output") String value) {
	}
}
