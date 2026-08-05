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

import com.soklet.converter.TypeReference;
import org.junit.jupiter.api.Test;

import javax.annotation.concurrent.ThreadSafe;
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
						.handler((request, call, features) -> {
							rawArguments.set(call.getRawArguments());
							assertEquals(" exact ", call.getArguments().query());
							assertEquals(List.of(2, 5),
									call.getArguments().pageSizes());
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
						new McpJsonNumber(java.math.BigDecimal.valueOf(2)),
						new McpJsonNumber(java.math.BigDecimal.valueOf(5)))))
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
				.find("owner").orElseThrow()).value());
		assertEquals(Arguments.class, registration.getArgumentType());
		assertEquals(Result.class, registration.getOutputType().orElseThrow());
		assertEquals(new McpJsonString("object"), registration.getInputSchema()
				.getDocument().find("type").orElseThrow());
		assertEquals(new McpJsonString("object"), registration.getOutputSchema()
				.orElseThrow().getDocument().find("type").orElseThrow());
		assertTrue(registration.isStructuredContentTextMirroringEnabled());
		McpJsonArray items = assertInstanceOf(McpJsonArray.class,
				structured.find("items").orElseThrow());
		McpJsonObject item = assertInstanceOf(McpJsonObject.class,
				items.getElements().get(0));
		assertEquals(new McpJsonString("a"),
				item.find("identifier").orElseThrow());
	}

	@Test
	void allClassAndTypeReferenceCombinationsAreAvailable() {
		TypeReference<Arguments> arguments =
				new TypeReference<>() {};
		TypeReference<List<Item>> results =
				new TypeReference<>() {};

		assertEquals(Result.class, McpToolRegistration.withName("one")
				.types(Arguments.class, Result.class)
				.handler((request, call, features) ->
						new Result(List.of()))
				.build().getOutputType().orElseThrow());
		assertEquals(results.getType(), McpToolRegistration.withName("two")
				.types(Arguments.class, results)
				.handler((request, call, features) -> List.of())
				.build().getOutputType().orElseThrow());
		assertEquals(arguments.getType(), McpToolRegistration.withName("three")
				.types(arguments, Result.class)
				.handler((request, call, features) ->
						new Result(List.of()))
				.build().getArgumentType());
		assertEquals(results.getType(), McpToolRegistration.withName("four")
				.types(arguments, results)
				.handler((request, call, features) -> List.of())
				.build().getOutputType().orElseThrow());
	}

	@Test
	void advancedAndRawRegistrationsUseTheirSelectedArgumentModels()
			throws Exception {
		McpToolRegistration<Arguments> advanced =
				McpToolRegistration.withName("advanced")
						.argumentType(Arguments.class)
						.handler((request, call, features) ->
								McpCompleteResult.fromToolText(
										call.getArguments().query()))
						.mirrorStructuredContentAsText(false)
						.build();
		McpToolRegistration<McpJsonObject> raw =
				McpToolRegistration.withName("raw")
						.jsonArguments()
						.handler((request, call, features) ->
								McpCompleteResult.fromToolStructuredContent(
										call.getArguments()))
						.build();
		McpJsonObject input = argumentsJson();

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
		assertEquals(new McpJsonString("object"),
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
						.handler((request, call, features) -> null)
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
						.handler((request, call, features) -> {
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
	void rateLimiterSelectionIsLastCallWins() {
		McpRateLimiter direct =
				context -> McpRateLimitDecision.fromAllowed();
		McpToolRegistration<Arguments> named =
				McpToolRegistration.withName("named")
						.argumentType(Arguments.class)
						.handler((request, call, features) ->
								McpCompleteResult.fromToolText("done"))
						.rateLimiter(direct)
						.rateLimiter("distributed")
						.build();
		McpToolRegistration<Arguments> directLast =
				McpToolRegistration.withName("direct")
						.argumentType(Arguments.class)
						.handler((request, call, features) ->
								McpCompleteResult.fromToolText("done"))
						.rateLimiter("distributed")
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
						.handler((request, call, features) ->
								McpCompleteResult.fromToolText("done"))
						.rateLimiter(" "));
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
}
