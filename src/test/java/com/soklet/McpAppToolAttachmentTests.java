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

import org.junit.jupiter.api.Test;

import javax.annotation.concurrent.ThreadSafe;
import java.net.URI;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Apps tool attachment and static endpoint UI-resource eligibility tests.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
class McpAppToolAttachmentTests {

	private static final URI UI_URI = URI.create("ui://private-catalog/dashboard");

	@Test
	void operationBuilderKeepsTypedAndRawMetadataSeparateAndSnapshotsReplacement() {
		McpToolRegistration.OperationBuilder<McpJsonObject> builder = operationBuilder();
		assertTrue(builder.build().getAppToolMetadata().isEmpty());
		McpJsonObject raw = unrelatedMetadata();
		McpAppToolMetadata first = appMetadata();
		McpAppToolMetadata replacement = McpAppToolMetadata.builder()
				.visibility(Set.of(McpAppToolMetadata.Visibility.APP)).build();
		assertSame(builder, builder.metadata(raw));
		assertSame(builder, builder.appToolMetadata(first));
		McpToolRegistration<McpJsonObject> snapshot = builder.build();
		builder.appToolMetadata(replacement).metadata(McpJsonObject.emptyInstance());

		assertSame(first, snapshot.getAppToolMetadata().orElseThrow());
		assertSame(raw, snapshot.getMetadata());
		assertSame(replacement, builder.build().getAppToolMetadata().orElseThrow());
		assertEquals(McpJsonObject.emptyInstance(), builder.build().getMetadata());
	}

	@Test
	void completeBuilderKeepsTypedAndRawMetadataSeparateAndSnapshotsReplacement() {
		McpToolRegistration.CompleteBuilder<Arguments> builder = completeBuilder();
		assertTrue(builder.build().getAppToolMetadata().isEmpty());
		McpJsonObject raw = unrelatedMetadata();
		McpAppToolMetadata first = appMetadata();
		McpAppToolMetadata replacement = McpAppToolMetadata.builder()
				.visibility(Set.of()).build();
		assertSame(builder, builder.appToolMetadata(first));
		assertSame(builder, builder.metadata(raw));
		McpToolRegistration<Arguments> snapshot = builder.build();
		builder.appToolMetadata(replacement).metadata(McpJsonObject.emptyInstance());

		assertSame(first, snapshot.getAppToolMetadata().orElseThrow());
		assertSame(raw, snapshot.getMetadata());
		assertSame(replacement, builder.build().getAppToolMetadata().orElseThrow());
		assertEquals(McpJsonObject.emptyInstance(), builder.build().getMetadata());
	}

	@Test
	void operationBuilderRejectsBothOwnedFieldsInEitherOrderWithoutMutation() {
		for (McpJsonObject conflicting : conflictingMetadata()) {
			McpAppToolMetadata typed = appMetadata();
			McpJsonObject safe = unrelatedMetadata();
			McpToolRegistration.OperationBuilder<McpJsonObject> typedFirst =
					operationBuilder().metadata(safe).appToolMetadata(typed);
			assertThrows(IllegalArgumentException.class,
					() -> typedFirst.metadata(conflicting));
			assertSame(safe, typedFirst.build().getMetadata());
			assertSame(typed, typedFirst.build().getAppToolMetadata().orElseThrow());

			McpToolRegistration.OperationBuilder<McpJsonObject> rawFirst =
					operationBuilder().metadata(conflicting);
			assertThrows(IllegalArgumentException.class,
					() -> rawFirst.appToolMetadata(typed));
			assertTrue(rawFirst.build().getAppToolMetadata().isEmpty());
			assertSame(conflicting, rawFirst.build().getMetadata());
			rawFirst.metadata(safe).appToolMetadata(typed);
			assertSame(typed, rawFirst.build().getAppToolMetadata().orElseThrow());
		}
	}

	@Test
	void completeBuilderRejectsBothOwnedFieldsInEitherOrderWithoutMutation() {
		for (McpJsonObject conflicting : conflictingMetadata()) {
			McpAppToolMetadata typed = appMetadata();
			McpJsonObject safe = unrelatedMetadata();
			McpToolRegistration.CompleteBuilder<Arguments> typedFirst =
					completeBuilder().metadata(safe).appToolMetadata(typed);
			assertThrows(IllegalArgumentException.class,
					() -> typedFirst.metadata(conflicting));
			assertSame(safe, typedFirst.build().getMetadata());
			assertSame(typed, typedFirst.build().getAppToolMetadata().orElseThrow());

			McpToolRegistration.CompleteBuilder<Arguments> rawFirst =
					completeBuilder().metadata(conflicting);
			assertThrows(IllegalArgumentException.class,
					() -> rawFirst.appToolMetadata(typed));
			assertTrue(rawFirst.build().getAppToolMetadata().isEmpty());
			assertSame(conflicting, rawFirst.build().getMetadata());
			rawFirst.metadata(safe).appToolMetadata(typed);
			assertSame(typed, rawFirst.build().getAppToolMetadata().orElseThrow());
		}
	}

	@Test
	void buildersRejectNullWithoutDiscardingPriorValues() {
		McpAppToolMetadata typed = appMetadata();
		McpJsonObject raw = unrelatedMetadata();
		McpToolRegistration.OperationBuilder<McpJsonObject> operation =
				operationBuilder().appToolMetadata(typed).metadata(raw);
		McpToolRegistration.CompleteBuilder<Arguments> complete =
				completeBuilder().appToolMetadata(typed).metadata(raw);

		assertThrows(NullPointerException.class, () -> operation.appToolMetadata(null));
		assertThrows(NullPointerException.class, () -> operation.metadata(null));
		assertThrows(NullPointerException.class, () -> complete.appToolMetadata(null));
		assertThrows(NullPointerException.class, () -> complete.metadata(null));
		assertSame(typed, operation.build().getAppToolMetadata().orElseThrow());
		assertSame(raw, operation.build().getMetadata());
		assertSame(typed, complete.build().getAppToolMetadata().orElseThrow());
		assertSame(raw, complete.build().getMetadata());
	}

	@Test
	void rawAppsValidationCannotBeBypassedAndRejectedMetadataDoesNotReplaceState() {
		List<McpJsonObject> invalid = List.of(
				McpJsonObject.builder().put("ui", "private-invalid-container").build(),
				ui(McpJsonObject.builder().put("resourceUri", 5).build()),
				ui(McpJsonObject.builder().put("resourceUri", "https://private.example/view").build()),
				ui(McpJsonObject.builder().put("resourceUri", "ui://private/view/../other").build()),
				ui(McpJsonObject.builder().put("visibility", "private-invalid-audience").build()),
				ui(McpJsonObject.builder().put("visibility", McpJsonArray.builder()
						.add("private-invalid-audience").build()).build()),
				ui(McpJsonObject.builder().put("visibility", McpJsonArray.builder()
						.add("model").add("model").build()).build()),
				ui(McpJsonObject.builder().put("visibility", McpJsonArray.builder()
						.add(5).build()).build()));
		McpJsonObject safe = unrelatedMetadata();
		McpToolRegistration.OperationBuilder<McpJsonObject> operation =
				operationBuilder().metadata(safe);
		McpToolRegistration.CompleteBuilder<Arguments> complete =
				completeBuilder().metadata(safe);
		for (McpJsonObject raw : invalid) {
			IllegalArgumentException operationFailure = assertThrows(
					IllegalArgumentException.class, () -> operation.metadata(raw));
			IllegalArgumentException completeFailure = assertThrows(
					IllegalArgumentException.class, () -> complete.metadata(raw));
			assertFalse(operationFailure.getMessage().contains("private"));
			assertFalse(completeFailure.getMessage().contains("private"));
			assertSame(safe, operation.build().getMetadata());
			assertSame(safe, complete.build().getMetadata());
		}
	}

	@Test
	void endpointAcceptsExactAppsResourceInEitherBuilderOrderWithoutInvokingHandlers() {
		McpToolRegistration<McpJsonObject> tool = operationBuilder()
				.appToolMetadata(appMetadata()).build();
		for (String mime : List.of("text/html;profile=mcp-app",
				" TEXT / HTML ; PROFILE = \"mcp-app\" ")) {
			McpResourceRegistration resource = resource(mime);
			McpEndpoint endpoint = endpointBuilder().toolRegistrations(java.util.List.of(tool)).resourceRegistrations(java.util.List.of(resource))
					.resourceListHandler((request, list, features) -> {
						throw new AssertionError("Must not invoke the resource-list handler.");
					}).build();
			assertSame(tool, endpoint.getToolRegistrations().get(0));
			assertSame(resource, endpoint.getResourceRegistrations().get(0));
			assertDoesNotThrow(() -> endpointBuilder().resourceRegistrations(java.util.List.of(resource)).toolRegistrations(java.util.List.of(tool)).build());
		}
	}

	@Test
	void endpointRejectsMissingOrIneligibleDeclaredMimeWithoutEchoingConfiguration() {
		McpToolRegistration<McpJsonObject> tool = operationBuilder()
				.appToolMetadata(appMetadata()).build();
		for (String mime : List.of("text/html", "text/html;profile=MCP-APP",
				"text/html;profile=mcp-app;charset=utf-8",
				"text/html;profile=mcp-app;PROFILE=mcp-app",
				"private-invalid-mime", "text/html;profile=\"private-unclosed")) {
			IllegalStateException failure = assertThrows(IllegalStateException.class,
					() -> endpointBuilder().toolRegistrations(java.util.List.of(tool)).resourceRegistrations(java.util.List.of(resource(mime))).build());
			assertFalse(failure.getMessage().contains("private"));
			assertTrue(failure.getCause() == null);
		}
		assertThrows(IllegalStateException.class, () -> endpointBuilder().toolRegistrations(java.util.List.of(tool))
				.resourceRegistrations(java.util.List.of(McpResourceRegistration.withUriAndName(UI_URI, "view")
						.handler(resourceHandler()).build())).build());
	}

	@Test
	void missingAssociationCanBeRecoveredByAddingAnEligibleRegistration() {
		McpEndpoint.Builder builder = endpointBuilder().toolRegistrations(java.util.List.of(operationBuilder()
				.appToolMetadata(appMetadata()).build()));
		IllegalStateException failure = assertThrows(IllegalStateException.class, builder::build);
		assertFalse(failure.getMessage().contains(UI_URI.toString()));
		assertDoesNotThrow(() -> builder.resourceRegistrations(java.util.List.of(resource("text/html;profile=mcp-app"))).build());
	}

	@Test
	void matchingTemplateAndCustomListDescriptorsDoNotEstablishEligibility() {
		McpToolRegistration<McpJsonObject> tool = operationBuilder()
				.appToolMetadata(appMetadata()).build();
		McpResourceRegistration template = McpResourceRegistration
				.withUriTemplateAndName("ui://private-catalog/{view}", "view")
				.handler(resourceHandler()).mimeType("text/html;profile=mcp-app").build();
		assertThrows(IllegalStateException.class,
				() -> endpointBuilder().toolRegistrations(java.util.List.of(tool)).resourceRegistrations(java.util.List.of(template)).build());
		assertThrows(IllegalStateException.class,
				() -> endpointBuilder().toolRegistrations(java.util.List.of(tool))
						.resourceListHandler((request, list, features) -> {
							throw new AssertionError("Must not ask custom listing to establish eligibility.");
						}).build());
		assertThrows(IllegalStateException.class,
				() -> endpointBuilder().toolRegistrations(java.util.List.of(tool)).resourceRegistrations(java.util.List.of(template))
						.resourceListHandler((request, list, features) -> McpResourcePage.builder()
								.resourceDescriptors(java.util.List.of(McpResourceDescriptor.withUriAndName(UI_URI, "view")
										.mimeType("text/html;profile=mcp-app").build())).build()).build());
	}

	@Test
	void anotherEndpointOrSimilarUriDoesNotEstablishEligibility() {
		McpEndpoint separate = endpointBuilder()
				.resourceRegistrations(java.util.List.of(resource("text/html;profile=mcp-app"))).build();
		assertEquals(1, separate.getResourceRegistrations().size());
		McpToolRegistration<McpJsonObject> tool = operationBuilder()
				.appToolMetadata(appMetadata()).build();
		assertThrows(IllegalStateException.class, () -> McpEndpoint
				.withPath("/other", separate.getServerInfo()).toolRegistrations(java.util.List.of(tool)).build());
		assertThrows(IllegalStateException.class, () -> endpointBuilder().toolRegistrations(java.util.List.of(tool))
				.resourceRegistrations(java.util.List.of(McpResourceRegistration.withUriAndName(
						URI.create("ui://private-catalog/dashboard-other"), "view")
						.handler(resourceHandler()).mimeType("text/html;profile=mcp-app").build()))
				.build());
	}

	@Test
	void rawOnlyAssociationUsesTheSameEndpointEligibilityWithoutBecomingTyped() {
		McpJsonObject raw = ui(McpJsonObject.builder().put("resourceUri", UI_URI.toString())
				.put("visibility", McpJsonArray.builder().add("app").build()).build());
		McpToolRegistration<McpJsonObject> tool = operationBuilder().metadata(raw).build();
		assertTrue(tool.getAppToolMetadata().isEmpty());
		assertSame(raw, tool.getMetadata());
		assertThrows(IllegalStateException.class, () -> endpointBuilder().toolRegistrations(java.util.List.of(tool)).build());
		assertThrows(IllegalStateException.class,
				() -> endpointBuilder().toolRegistrations(java.util.List.of(tool)).resourceRegistrations(java.util.List.of(resource("text/html"))).build());
		assertDoesNotThrow(() -> endpointBuilder().toolRegistrations(java.util.List.of(tool))
				.resourceRegistrations(java.util.List.of(resource("text/html;profile=mcp-app"))).build());
	}

	@Test
	void appOnlyOrEmptyAudienceHelpersNeedNoUiResource() {
		for (Set<McpAppToolMetadata.Visibility> visibility : List.of(
				Set.of(McpAppToolMetadata.Visibility.APP), Set.<McpAppToolMetadata.Visibility>of())) {
			McpToolRegistration<McpJsonObject> tool = operationBuilder().appToolMetadata(
					McpAppToolMetadata.builder().visibility(visibility).build()).build();
			assertDoesNotThrow(() -> endpointBuilder().toolRegistrations(java.util.List.of(tool)).build());
			assertEquals(visibility, tool.getAppToolMetadata().orElseThrow().getVisibility());
		}
		assertDoesNotThrow(() -> endpointBuilder().toolRegistrations(java.util.List.of(operationBuilder().metadata(
				ui(McpJsonObject.builder().put("visibility", McpJsonArray.emptyInstance()).build()))
				.build())).build());
	}

	@Test
	void ordinaryResourcesRetainExistingArbitraryNonblankMimeBehavior() {
		assertDoesNotThrow(() -> endpointBuilder().toolRegistrations(java.util.List.of(operationBuilder().build()))
				.resourceRegistrations(java.util.List.of(resource("private arbitrary legacy mime"))).build());
		assertDoesNotThrow(() -> endpointBuilder().toolRegistrations(java.util.List.of(operationBuilder().appToolMetadata(
				McpAppToolMetadata.builder().build()).build()))
				.resourceRegistrations(java.util.List.of(resource("private arbitrary legacy mime"))).build());
	}

	private static McpAppToolMetadata appMetadata() {
		return McpAppToolMetadata.builder().resourceUri(UI_URI).build();
	}

	private static McpJsonObject ui(McpJsonObject ui) {
		return McpJsonObject.builder().put("ui", ui).build();
	}

	private static McpJsonObject unrelatedMetadata() {
		return McpJsonObject.builder().put("owner", "private-catalog")
				.put("ui", McpJsonObject.builder().put("vendor/example", "private-extension").build())
				.build();
	}

	private static List<McpJsonObject> conflictingMetadata() {
		return List.of(ui(McpJsonObject.builder().put("resourceUri", UI_URI.toString()).build()),
				ui(McpJsonObject.builder().put("visibility", McpJsonArray.builder()
						.add("model").add("app").build()).build()));
	}

	private static McpResourceRegistration resource(String mimeType) {
		return McpResourceRegistration.withUriAndName(UI_URI, "view")
				.handler(resourceHandler()).mimeType(mimeType).build();
	}

	private static McpResourceReadHandler resourceHandler() {
		return (request, resource, features) -> {
			throw new AssertionError("Must not invoke the resource-read handler.");
		};
	}

	private static McpToolRegistration.OperationBuilder<McpJsonObject> operationBuilder() {
		return McpToolRegistration.withName("show_view").jsonObjectArguments()
				.handler((request, arguments, features) -> {
					throw new AssertionError("Must not invoke the tool handler.");
				});
	}

	private static McpToolRegistration.CompleteBuilder<Arguments> completeBuilder() {
		return McpToolRegistration.withName("complete_view")
				.argumentAndOutputTypes(Arguments.class, Output.class)
				.handler((request, arguments, features) -> {
					throw new AssertionError("Must not invoke the complete tool handler.");
				});
	}

	private static McpEndpoint.Builder endpointBuilder() {
		return McpEndpoint.withPath("/mcp", McpImplementation
				.withNameAndVersion("apps-attachment-tests", "test").build());
	}

	private record Arguments(String value) {}

	private record Output(String value) {}
}
