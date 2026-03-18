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

import com.soklet.annotation.McpListResources;
import com.soklet.annotation.McpPrompt;
import com.soklet.annotation.McpResource;
import com.soklet.annotation.McpServerEndpoint;
import com.soklet.annotation.McpTool;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class McpPublicApiTests {
	@Test
	public void toolResultBuilderAccumulatesContentInOrder() {
		McpToolResult result = McpToolResult.builder()
				.content(McpTextContent.fromText("first"))
				.content(List.of(McpTextContent.fromText("second"), McpTextContent.fromText("third")))
				.structuredContent(Map.of("ok", true))
				.build();

		assertEquals(List.of(
				McpTextContent.fromText("first"),
				McpTextContent.fromText("second"),
				McpTextContent.fromText("third")
		), result.getContent());
		assertEquals(Map.of("ok", true), result.getStructuredContent().orElseThrow());
		assertFalse(result.isError());
	}

	@Test
	public void sessionContextIsImmutableAndTypedLookupFailsFastOnMismatch() {
		McpSessionContext sessionContext = McpSessionContext.fromBlankSlate()
				.with("tenantId", "abc")
				.with("limit", Integer.valueOf(3));

		assertEquals("abc", sessionContext.get("tenantId", String.class).orElseThrow());
		assertEquals(Integer.valueOf(3), sessionContext.get("limit", Integer.class).orElseThrow());
		assertThrows(IllegalArgumentException.class, () -> sessionContext.get("tenantId", Integer.class));
		assertThrows(UnsupportedOperationException.class, () -> sessionContext.asMap().put("x", "y"));
	}

	@Test
	public void responseMarshalerDefaultInstancePassesThroughMcpValueAndRejectsArbitraryObjects() {
		McpResponseMarshaler marshaler = McpResponseMarshaler.defaultInstance();
		McpValue value = new McpObject(Map.of("enabled", new McpBoolean(true)));
		TestStructuredContentContext context = new TestStructuredContentContext();

		assertEquals(value, marshaler.marshalStructuredContent(value, context));
		assertEquals(McpNull.INSTANCE, marshaler.marshalStructuredContent(null, context));
		assertThrows(IllegalArgumentException.class, () -> marshaler.marshalStructuredContent(Map.of("enabled", true), context));
	}

	@Test
	public void schemaBuilderProducesObjectSchemaWithRequiredAndEnumProperties() {
		McpSchema schema = McpSchema.object()
				.required("recipeId", McpType.UUID)
				.optional("threshold", McpType.NUMBER)
				.requiredEnum("mode", "food", "beverage")
				.build();

		McpObject root = schema.toValue();

		assertEquals("object", ((McpString) root.get("type").orElseThrow()).value());
		assertEquals(Boolean.FALSE, ((McpBoolean) root.get("additionalProperties").orElseThrow()).value());

		McpObject properties = (McpObject) root.get("properties").orElseThrow();
		assertTrue(properties.get("recipeId").isPresent());
		assertTrue(properties.get("threshold").isPresent());
		assertTrue(properties.get("mode").isPresent());

		McpArray required = (McpArray) root.get("required").orElseThrow();
		assertEquals(List.of(new McpString("recipeId"), new McpString("mode")), required.values());
	}

	@Test
	public void corsAuthorizerWhitelistNormalizesOrigins() {
		Request request = Request.fromPath(HttpMethod.GET, "/mcp");
		McpCorsAuthorizer corsAuthorizer = McpCorsAuthorizer.fromWhitelistedOrigins(java.util.Set.of("https://Example.com:443"));
		CorsResponse allowedResponse = corsAuthorizer.authorize(
				new McpCorsContext(request, TestEndpoint.class, HttpMethod.GET, "https://example.com", null),
				Cors.fromOrigin(HttpMethod.GET, "https://example.com")).orElseThrow();

		assertEquals(Set.of("MCP-Session-Id", "WWW-Authenticate"), allowedResponse.getAccessControlExposeHeaders());
		assertFalse(corsAuthorizer.authorize(
				new McpCorsContext(request, TestEndpoint.class, HttpMethod.GET, "https://example.com:8443", null),
				Cors.fromOrigin(HttpMethod.GET, "https://example.com:8443")).isPresent());
	}

	@Test
	public void resourceContentsRequireExactlyOnePayloadForm() {
		assertInstanceOf(McpResourceContents.class, McpResourceContents.fromText("mcp://recipes/1", "hello", "text/plain"));
		assertInstanceOf(McpResourceContents.class, McpResourceContents.fromBlob("mcp://recipes/1", "SGVsbG8=", "application/octet-stream"));
		assertThrows(IllegalArgumentException.class, () -> new McpResourceContents("mcp://recipes/1", "text/plain", null, null));
		assertThrows(IllegalArgumentException.class, () -> new McpResourceContents("mcp://recipes/1", "text/plain", "text", "blob"));
	}

	@Test
	public void identifierWrappersExposeTypedViews() {
		McpJsonRpcRequestId requestId = McpJsonRpcRequestId.fromNumber(new BigDecimal("123"));
		McpProgressToken progressToken = McpProgressToken.fromString("abc");

		assertEquals(new BigDecimal("123"), requestId.asNumber().orElseThrow());
		assertTrue(requestId.asString().isEmpty());
		assertEquals("abc", progressToken.asString().orElseThrow());
		assertTrue(progressToken.asNumber().isEmpty());
	}

	@Test
	public void mcpRequestResultStreamOpenedBuffersMessagesAndCanBeClosed() {
		HttpRequestResult requestResult = HttpRequestResult.fromMarshaledResponse(MarshaledResponse.fromStatusCode(200));
		McpRequestResult.StreamOpened streamOpened = new McpRequestResult.StreamOpened(requestResult, null, false);
		AtomicInteger consumedCount = new AtomicInteger();

		streamOpened.emitMessage(new McpObject(Map.of("kind", new McpString("one"))));
		streamOpened.emitMessage(new McpObject(Map.of("kind", new McpString("two"))));

		streamOpened.registerMessageConsumer(message -> consumedCount.incrementAndGet());

		assertEquals(2, consumedCount.get());
		assertFalse(streamOpened.isClosed());

		streamOpened.close();

		assertTrue(streamOpened.isClosed());
	}

	@Test
	public void simulatorPerformMcpRequestFailsFastByDefault() {
		Simulator simulator = new Simulator() {
			@Override
			public HttpRequestResult performHttpRequest(Request request) {
				throw new UnsupportedOperationException("unused");
			}

			@Override
			public SseRequestResult performSseRequest(Request request) {
				throw new UnsupportedOperationException("unused");
			}
		};

		assertThrows(IllegalStateException.class, () -> simulator.performMcpRequest(Request.fromPath(HttpMethod.POST, "/mcp")));
	}

	@Test
	public void sessionStoreCreateFindAndReplaceFollowCasSemantics() {
		McpSessionStore sessionStore = McpSessionStore.fromInMemory(Duration.ZERO);
		McpStoredSession created = new McpStoredSession(
				"session-1",
				TestEndpoint.class,
				Instant.now(),
				Instant.now(),
				false,
				false,
				null,
				null,
				null,
				McpSessionContext.fromBlankSlate(),
				null,
				0L
		);
		McpStoredSession updated = new McpStoredSession(
				"session-1",
				TestEndpoint.class,
				created.createdAt(),
				Instant.now(),
				true,
				false,
				"2025-11-25",
				null,
				null,
				created.sessionContext().with("tenantId", "abc"),
				null,
				1L
		);

		sessionStore.create(created);

		assertEquals(created, sessionStore.findBySessionId("session-1").orElseThrow());
		assertTrue(sessionStore.replace(created, updated));
		assertEquals(updated, sessionStore.findBySessionId("session-1").orElseThrow());
		assertFalse(sessionStore.replace(created, updated));
	}

	@Test
	public void sessionStoreExpiresIdleSessionsUnlessTimeoutDisabled() {
		McpStoredSession staleSession = new McpStoredSession(
				"stale",
				TestEndpoint.class,
				Instant.now().minus(Duration.ofHours(2)),
				Instant.now().minus(Duration.ofHours(2)),
				true,
				true,
				null,
				null,
				null,
				McpSessionContext.fromBlankSlate(),
				null,
				0L
		);

		McpSessionStore expiringStore = McpSessionStore.fromInMemory(Duration.ofMinutes(1));
		expiringStore.create(staleSession);
		assertTrue(expiringStore.findBySessionId("stale").isEmpty());

		McpSessionStore nonExpiringStore = McpSessionStore.fromInMemory(Duration.ZERO);
		nonExpiringStore.create(staleSession);
		assertTrue(nonExpiringStore.findBySessionId("stale").isPresent());
	}

	@Test
	public void defaultInMemorySessionStoreSweepsExpiredSessionsDuringSubsequentWrites() {
		DefaultMcpSessionStore sessionStore = (DefaultMcpSessionStore) McpSessionStore.fromInMemory(Duration.ofMillis(50));
		McpStoredSession staleSession = new McpStoredSession(
				"stale",
				TestEndpoint.class,
				Instant.now().minus(Duration.ofHours(2)),
				Instant.now().minus(Duration.ofHours(2)),
				true,
				true,
				null,
				null,
				null,
				McpSessionContext.fromBlankSlate(),
				null,
				0L
		);
		McpStoredSession freshSession = new McpStoredSession(
				"fresh",
				TestEndpoint.class,
				Instant.now(),
				Instant.now(),
				false,
				false,
				null,
				null,
				null,
				McpSessionContext.fromBlankSlate(),
				null,
				0L
		);

		sessionStore.create(staleSession);
		assertTrue(sessionStore.containsSessionId("stale"));

		sleepUnchecked(80L);

		sessionStore.create(freshSession);

		assertFalse(sessionStore.containsSessionId("stale"));
		assertTrue(sessionStore.containsSessionId("fresh"));
	}

	@Test
	public void handlerResolverDiscoversAnnotatedEndpointMetadataAndNames() {
		McpHandlerResolver handlerResolver = McpHandlerResolver.fromClasses(Set.of(AnnotatedResolverEndpoint.class));
		McpEndpointRegistration endpointRegistration = handlerResolver.endpointRegistrationForClass(AnnotatedResolverEndpoint.class).orElseThrow();

		assertEquals("/tenant/{tenantId}/mcp", endpointRegistration.endpointPathDeclaration().getPath());
		assertEquals("annotated-resolver-endpoint", endpointRegistration.name());
		assertEquals("1.2.3", endpointRegistration.version());
		assertEquals(Set.of("lookup_recipe"), endpointRegistration.toolNames());
		assertEquals(Set.of("render_recipe_prompt"), endpointRegistration.promptNames());
		assertEquals(Set.of("mcp://recipes/{recipeId}"), endpointRegistration.resourceUris());
		assertTrue(endpointRegistration.hasResourceListHandler());
	}

	@Test
	public void handlerResolverOverlayDoesNotMutateBaseResolver() {
		McpHandlerResolver baseResolver = McpHandlerResolver.fromClasses(Set.of(AnnotatedResolverEndpoint.class));
		McpHandlerResolver compositeResolver = baseResolver.withTool(new TestProgrammaticToolHandler("calculate_cost"), AnnotatedResolverEndpoint.class);

		assertEquals(Set.of("lookup_recipe"),
				baseResolver.endpointRegistrationForClass(AnnotatedResolverEndpoint.class).orElseThrow().toolNames());
		assertEquals(Set.of("lookup_recipe", "calculate_cost"),
				compositeResolver.endpointRegistrationForClass(AnnotatedResolverEndpoint.class).orElseThrow().toolNames());
	}

	@Test
	public void handlerResolverRejectsDuplicateToolNamesAcrossAnnotatedAndProgrammaticHandlers() {
		McpHandlerResolver baseResolver = McpHandlerResolver.fromClasses(Set.of(AnnotatedResolverEndpoint.class));

		assertThrows(IllegalStateException.class, () ->
				baseResolver.withTool(new TestProgrammaticToolHandler("lookup_recipe"), AnnotatedResolverEndpoint.class));
	}

	@Test
	public void handlerResolverRejectsMultipleAnnotatedResourceListMethods() {
		assertThrows(IllegalStateException.class, () -> McpHandlerResolver.fromClasses(Set.of(DuplicateListEndpoint.class)));
	}

	@Test
	public void handlerResolverRejectsAmbiguousEndpointPaths() {
		assertThrows(IllegalStateException.class, () -> McpHandlerResolver.fromClasses(Set.of(
				AmbiguousEndpointOne.class,
				AmbiguousEndpointTwo.class
		)));
	}

	private static final class TestEndpoint implements McpEndpoint {}

	@McpServerEndpoint(
			path = "/tenant/{tenantId}/mcp",
			name = "annotated-resolver-endpoint",
			version = "1.2.3"
	)
	private static final class AnnotatedResolverEndpoint implements McpEndpoint {
		@McpTool(name = "lookup_recipe", description = "Find a recipe")
		public McpToolResult lookupRecipe() {
			return McpToolResult.builder()
					.content(McpTextContent.fromText("ok"))
					.build();
		}

		@McpPrompt(name = "render_recipe_prompt", description = "Render a prompt")
		public McpPromptResult renderRecipePrompt() {
			return McpPromptResult.fromMessages(McpPromptMessage.fromUserText("hello"));
		}

		@McpResource(uri = "mcp://recipes/{recipeId}", name = "recipe", mimeType = "application/json")
		public McpResourceContents recipe() {
			return McpResourceContents.fromText("mcp://recipes/1", "{\"ok\":true}", "application/json");
		}

		@McpListResources
		public McpListResourcesResult listResources() {
			return McpListResourcesResult.fromResources(List.of());
		}
	}

	@McpServerEndpoint(
			path = "/duplicate-list/mcp",
			name = "duplicate-list-endpoint",
			version = "1.0.0"
	)
	private static final class DuplicateListEndpoint implements McpEndpoint {
		@McpListResources
		public McpListResourcesResult first() {
			return McpListResourcesResult.fromResources(List.of());
		}

		@McpListResources
		public McpListResourcesResult second() {
			return McpListResourcesResult.fromResources(List.of());
		}
	}

	@McpServerEndpoint(
			path = "/ambiguous/{value}",
			name = "ambiguous-one",
			version = "1.0.0"
	)
	private static final class AmbiguousEndpointOne implements McpEndpoint {}

	@McpServerEndpoint(
			path = "/ambiguous/{other}",
			name = "ambiguous-two",
			version = "1.0.0"
	)
	private static final class AmbiguousEndpointTwo implements McpEndpoint {}

	private static final class TestStructuredContentContext implements McpStructuredContentContext {
		@Override
		public Class<? extends McpEndpoint> getEndpointClass() {
			return TestEndpoint.class;
		}

		@Override
		public String getToolName() {
			return "test";
		}

		@Override
		public McpToolCallContext getToolCallContext() {
			throw new UnsupportedOperationException("Not needed for this test");
		}

		@Override
		public McpSessionContext getSessionContext() {
			return McpSessionContext.fromBlankSlate();
		}
	}

	private static final class TestProgrammaticToolHandler implements McpToolHandler {
		private final String name;

		private TestProgrammaticToolHandler(String name) {
			this.name = name;
		}

		@Override
		public String getName() {
			return this.name;
		}

		@Override
		public String getDescription() {
			return "Programmatic tool";
		}

		@Override
		public McpSchema getInputSchema() {
			return McpSchema.object().build();
		}

		@Override
		public McpToolResult handle(McpToolHandlerContext context) {
			return McpToolResult.builder()
					.content(McpTextContent.fromText("ok"))
					.build();
		}
	}

	private static void sleepUnchecked(long durationInMillis) {
		try {
			Thread.sleep(durationInMillis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RuntimeException(e);
		}
	}
}
