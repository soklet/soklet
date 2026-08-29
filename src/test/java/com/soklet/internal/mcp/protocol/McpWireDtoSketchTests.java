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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class McpWireDtoSketchTests {
	@Test
	public void json_tree_is_deeply_immutable_and_distinguishes_absent_from_null() {
		Map<String, McpJsonValue> sourceMembers = new LinkedHashMap<>();
		sourceMembers.put("explicitNull", McpJsonNull.INSTANCE);
		McpJsonObject object = new McpJsonObject(sourceMembers);
		sourceMembers.put("late", McpJsonBoolean.TRUE);

		Assertions.assertSame(McpJsonNull.INSTANCE, object.members().get("explicitNull"));
		Assertions.assertFalse(object.members().containsKey("late"));
		Assertions.assertFalse(object.members().containsKey("absent"));
		Assertions.assertThrows(UnsupportedOperationException.class,
				() -> object.members().put("mutation", McpJsonNull.INSTANCE));

		List<McpJsonValue> sourceValues = new ArrayList<>();
		sourceValues.add(object);
		McpJsonArray array = new McpJsonArray(sourceValues);
		sourceValues.add(McpJsonNull.INSTANCE);
		Assertions.assertEquals(List.of(object), array.values());
		Assertions.assertThrows(UnsupportedOperationException.class,
				() -> array.values().add(McpJsonNull.INSTANCE));
	}

	@Test
	public void request_ids_preserve_string_integer_identity_and_arbitrary_precision() {
		BigInteger enormous = new BigInteger("922337203685477580812345678901234567890");
		McpJsonRpcId.StringId stringId = new McpJsonRpcId.StringId("1");
		McpJsonRpcId.IntegerId integerId = new McpJsonRpcId.IntegerId(BigInteger.ONE);
		McpJsonRpcId.IntegerId enormousId = new McpJsonRpcId.IntegerId(enormous);

		Assertions.assertNotEquals(stringId, integerId);
		Assertions.assertEquals(enormous, enormousId.value());
		Assertions.assertEquals(new McpJsonString("1"), stringId.toJsonValue());
		Assertions.assertEquals(new McpJsonNumber(new BigDecimal(enormous)),
				enormousId.toJsonValue());
		Assertions.assertThrows(NullPointerException.class,
				() -> new McpJsonRpcId.StringId(null));
		Assertions.assertThrows(NullPointerException.class,
				() -> new McpJsonRpcId.IntegerId(null));
	}

	@Test
	public void responses_serialize_the_exact_correlated_string_and_integer_request_ids() {
		McpRequestMetadata metadata =
				McpRequestMetadata.fromClientCapabilities(
						Mcp20260728ProtocolProfile.INSTANCE,
						McpClientCapabilities.empty());
		McpRequestParameters params =
				new McpRequestParameters(metadata, McpJsonObject.empty());
		List<McpJsonRpcId> requestIds = List.of(
				new McpJsonRpcId.StringId("42"),
				new McpJsonRpcId.IntegerId(
						new BigInteger("922337203685477580812345678901234567890")));

		for (McpJsonRpcId requestId : requestIds) {
			McpJsonValue serializedRequestId = new McpJsonRpcMessage.Request(
					requestId, "server/discover", params, McpJsonObject.empty())
					.toJsonObject().members().get("id");
			McpJsonValue serializedResultId = new McpJsonRpcMessage.ResultResponse(
					requestId, McpWireResult.complete(McpJsonObject.empty()), McpJsonObject.empty())
					.toJsonObject().members().get("id");
			McpJsonValue serializedErrorId = new McpJsonRpcMessage.ErrorResponse(
					Optional.of(requestId), new McpJsonRpcError(
							McpJsonRpcError.INTERNAL_ERROR, "Internal error", Optional.empty()),
					McpJsonObject.empty()).toJsonObject().members().get("id");

			Assertions.assertEquals(serializedRequestId, serializedResultId);
			Assertions.assertEquals(serializedRequestId, serializedErrorId);
		}
	}

	@Test
	public void json_rpc_variants_serialize_to_exact_exclusive_shapes() {
		McpRequestMetadata metadata =
				McpRequestMetadata.fromClientCapabilities(
						Mcp20260728ProtocolProfile.INSTANCE,
						McpClientCapabilities.empty());
		McpRequestParameters params = new McpRequestParameters(metadata,
				new McpJsonObject(Map.of("value", new McpJsonString("request"))));
		McpJsonObject extensions =
				new McpJsonObject(Map.of("com.example/envelope", McpJsonBoolean.TRUE));
		McpJsonRpcId.StringId id = new McpJsonRpcId.StringId("request-1");
		McpJsonRpcMessage.Request request =
				new McpJsonRpcMessage.Request(id, "server/discover", params, extensions);
		McpJsonRpcMessage.Notification notification = new McpJsonRpcMessage.Notification(
				"notifications/cancelled", Optional.empty(), extensions);
		McpRequestMetadata notificationMetadata = new McpRequestMetadata(
				McpProtocolVersion.CURRENT, McpClientCapabilities.empty(),
				Optional.empty(), Optional.empty(), Optional.empty(),
				new McpJsonObject(Map.of("com.example/notification-metadata",
						new McpJsonString("inside-params"))));
		McpJsonRpcMessage.Notification notificationWithMetadata =
				new McpJsonRpcMessage.Notification("notifications/future",
						Optional.of(new McpRequestParameters(notificationMetadata,
								McpJsonObject.empty()).toJsonObject()), extensions);
		McpJsonRpcMessage.ResultResponse result = new McpJsonRpcMessage.ResultResponse(
				id, McpWireResult.complete(McpJsonObject.empty()), extensions);
		McpJsonRpcMessage.ErrorResponse error = new McpJsonRpcMessage.ErrorResponse(
				Optional.of(id), new McpJsonRpcError(
						McpJsonRpcError.METHOD_NOT_FOUND, "Method not found", Optional.empty()),
				extensions);
		McpJsonRpcMessage.ErrorResponse unreadableError = new McpJsonRpcMessage.ErrorResponse(
				Optional.empty(), new McpJsonRpcError(
						McpJsonRpcError.PARSE_ERROR, "Parse error", Optional.empty()),
				McpJsonObject.empty());

		McpJsonObject requestJson = request.toJsonObject();
		Assertions.assertEquals(
				Set.of("jsonrpc", "id", "method", "params", "com.example/envelope"),
				requestJson.members().keySet());
		Assertions.assertEquals(new McpJsonString("2.0"), requestJson.members().get("jsonrpc"));
		Assertions.assertEquals(new McpJsonString("request-1"), requestJson.members().get("id"));
		Assertions.assertTrue(((McpJsonObject) requestJson.members().get("params"))
				.members().containsKey("_meta"));

		McpJsonObject notificationJson = notification.toJsonObject();
		Assertions.assertFalse(notificationJson.members().containsKey("id"));
		Assertions.assertFalse(notificationJson.members().containsKey("params"));
		Assertions.assertEquals(McpJsonBoolean.TRUE,
				notificationJson.members().get("com.example/envelope"));
		McpJsonObject notificationWithMetadataJson =
				notificationWithMetadata.toJsonObject();
		Assertions.assertEquals(Set.of("jsonrpc", "method", "params",
				"com.example/envelope"),
				notificationWithMetadataJson.members().keySet());
		Assertions.assertFalse(notificationWithMetadataJson.members()
				.containsKey("com.example/notification-metadata"));
		McpJsonObject notificationParams = (McpJsonObject)
				notificationWithMetadataJson.members().get("params");
		McpJsonObject nestedNotificationMetadata = (McpJsonObject)
				notificationParams.members().get("_meta");
		Assertions.assertEquals(new McpJsonString("inside-params"),
				nestedNotificationMetadata.members()
						.get("com.example/notification-metadata"));
		Assertions.assertEquals(McpJsonBoolean.TRUE,
				notificationWithMetadataJson.members().get("com.example/envelope"));

		McpJsonObject resultJson = result.toJsonObject();
		Assertions.assertTrue(resultJson.members().containsKey("result"));
		Assertions.assertFalse(resultJson.members().containsKey("error"));
		Assertions.assertFalse(resultJson.members().containsKey("_meta"));
		Assertions.assertEquals(new McpJsonString("complete"),
				((McpJsonObject) resultJson.members().get("result"))
						.members().get("resultType"));

		McpJsonObject errorJson = error.toJsonObject();
		Assertions.assertTrue(errorJson.members().containsKey("error"));
		Assertions.assertFalse(errorJson.members().containsKey("result"));
		Assertions.assertTrue(errorJson.members().containsKey("id"));
		Assertions.assertFalse(unreadableError.toJsonObject().members().containsKey("id"));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> new McpJsonRpcMessage.Request(id, "server/discover", params,
						new McpJsonObject(Map.of("id", new McpJsonString("collision")))));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> new McpJsonRpcMessage.Request(id, "server/discover", params,
						new McpJsonObject(Map.of("result", McpJsonObject.empty()))));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> new McpJsonRpcMessage.Notification(
						"notifications/cancelled", Optional.empty(),
						new McpJsonObject(Map.of("error", McpJsonObject.empty()))));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> new McpJsonRpcMessage.ResultResponse(id,
						McpWireResult.complete(McpJsonObject.empty()),
						new McpJsonObject(Map.of("method", new McpJsonString("collision")))));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> new McpJsonRpcMessage.ErrorResponse(Optional.of(id),
						new McpJsonRpcError(McpJsonRpcError.INTERNAL_ERROR,
								"Internal error", Optional.empty()),
						new McpJsonObject(Map.of("params", McpJsonObject.empty()))));
	}

	@Test
	public void client_capabilities_preserve_open_shapes_and_legacy_empty_elicitation() {
		McpJsonObject formSettings =
				new McpJsonObject(Map.of("presentation", new McpJsonString("compact")));
		McpJsonObject samplingContextSettings =
				new McpJsonObject(Map.of("maximumItems", new McpJsonNumber(4L)));
		McpJsonObject elicitation = new McpJsonObject(Map.of(
				"form", formSettings,
				"futureMode", new McpJsonObject(Map.of("enabled", McpJsonBoolean.TRUE))));
		McpJsonObject roots = new McpJsonObject(Map.of(
				"futureSetting", new McpJsonString("preserved")));
		McpJsonObject sampling = new McpJsonObject(Map.of(
				"context", samplingContextSettings,
				"futureSetting", new McpJsonArray(List.of(new McpJsonString("preserved")))));
		McpClientCapabilities capabilities = McpClientCapabilities.builder()
				.elicitation(elicitation)
				.roots(roots)
				.sampling(sampling)
				.extension("com.example/client-extension", formSettings)
				.experimental("draftFeature", McpJsonObject.empty())
				.unknown("futureScalar", new McpJsonString("value"))
				.unknown("futureArray", new McpJsonArray(List.of(McpJsonBoolean.TRUE)))
				.unknown("futureNull", McpJsonNull.INSTANCE)
				.build();
		McpJsonObject serialized = capabilities.toJsonObject();

		Assertions.assertSame(elicitation, serialized.members().get("elicitation"));
		Assertions.assertSame(roots, serialized.members().get("roots"));
		Assertions.assertSame(sampling, serialized.members().get("sampling"));
		Assertions.assertEquals(new McpJsonString("value"),
				serialized.members().get("futureScalar"));
		Assertions.assertSame(McpJsonNull.INSTANCE, serialized.members().get("futureNull"));
		Assertions.assertTrue(capabilities.supports(McpCoreClientCapability.ELICITATION_FORM));
		Assertions.assertFalse(capabilities.supports(McpCoreClientCapability.ELICITATION_URL));
		Assertions.assertTrue(capabilities.supports(McpCoreClientCapability.ROOTS));
		Assertions.assertTrue(capabilities.supports(McpCoreClientCapability.SAMPLING));
		Assertions.assertTrue(capabilities.supports(McpCoreClientCapability.SAMPLING_CONTEXT));
		Assertions.assertFalse(capabilities.supports(McpCoreClientCapability.SAMPLING_TOOLS));

		McpClientCapabilities legacyEmptyElicitation = McpClientCapabilities.builder()
				.elicitation(McpJsonObject.empty())
				.build();
		Assertions.assertTrue(legacyEmptyElicitation.supports(
				McpCoreClientCapability.ELICITATION_FORM));
		Assertions.assertFalse(legacyEmptyElicitation.supports(
				McpCoreClientCapability.ELICITATION_URL));
		Assertions.assertEquals(McpJsonObject.empty(),
				legacyEmptyElicitation.toJsonObject().members().get("elicitation"));
		Assertions.assertFalse(McpClientCapabilities.empty().supports(
				McpCoreClientCapability.ELICITATION_FORM));

		McpClientCapabilities schemaOpenNames = McpClientCapabilities.builder()
				.experimental("", McpJsonObject.empty())
				.experimental("   ", McpJsonObject.empty())
				.unknown("\t", McpJsonBoolean.TRUE)
				.build();
		McpJsonObject serializedOpenNames = schemaOpenNames.toJsonObject();
		McpJsonObject serializedExperimental =
				(McpJsonObject) serializedOpenNames.members().get("experimental");
		Assertions.assertTrue(serializedExperimental.members().containsKey(""));
		Assertions.assertTrue(serializedExperimental.members().containsKey("   "));
		Assertions.assertEquals(McpJsonBoolean.TRUE, serializedOpenNames.members().get("\t"));

		Assertions.assertThrows(IllegalArgumentException.class,
				() -> new McpClientCapabilities(
						Optional.of(new McpJsonObject(Map.of(
								"form", new McpJsonString("not-an-object")))),
						Optional.empty(), Optional.empty(), Map.of(), Map.of(), Map.of()));
	}

	@Test
	public void request_metadata_uses_exact_final_schema_keys_and_preserves_extensions() {
		McpClientCapabilities.Builder capabilitiesBuilder = McpClientCapabilities.builder()
				.capability(McpCoreClientCapability.ELICITATION_FORM)
				.capability(McpCoreClientCapability.SAMPLING_CONTEXT);
		McpClientCapabilities capabilities = capabilitiesBuilder.build();
		capabilitiesBuilder.capability(McpCoreClientCapability.ROOTS);
		McpImplementationMetadata clientInformation =
				McpImplementationMetadata.withNameAndVersion("test-client", "1.2.3");
		BigInteger progress = new BigInteger("184467440737095516160");
		McpRequestMetadata metadata = new McpRequestMetadata(
				McpProtocolVersion.CURRENT,
				capabilities,
				Optional.of(clientInformation),
				Optional.of(McpRequestLogLevel.WARNING),
				Optional.of(new McpProgressToken.IntegerToken(progress)),
				new McpJsonObject(Map.of(
						"com.example/request-metadata", new McpJsonString("preserved"))));
		McpJsonObject json = metadata.toJsonObject();

		Assertions.assertEquals("progressToken", McpRequestMetadata.PROGRESS_TOKEN_KEY);
		Assertions.assertEquals(Set.of(
				McpRequestMetadata.PROTOCOL_VERSION_KEY,
				McpRequestMetadata.CLIENT_CAPABILITIES_KEY,
				McpRequestMetadata.CLIENT_INFORMATION_KEY,
				McpRequestMetadata.LOG_LEVEL_KEY,
				"progressToken",
				"com.example/request-metadata"), json.members().keySet());
		Assertions.assertEquals(new McpJsonString(McpProtocolVersion.CURRENT),
				json.members().get(McpRequestMetadata.PROTOCOL_VERSION_KEY));
		Assertions.assertEquals(capabilities.toJsonObject(),
				json.members().get(McpRequestMetadata.CLIENT_CAPABILITIES_KEY));
		Assertions.assertEquals(new McpJsonString("warning"),
				json.members().get(McpRequestMetadata.LOG_LEVEL_KEY));
		Assertions.assertEquals(new McpJsonNumber(new BigDecimal(progress)),
				json.members().get("progressToken"));
		Assertions.assertFalse(json.members().containsKey(
				"io.modelcontextprotocol/progressToken"));
		Assertions.assertEquals(new McpJsonString("preserved"),
				json.members().get("com.example/request-metadata"));
		Assertions.assertTrue(capabilities.roots().isEmpty(),
				"building must defensively copy the mutable builder state");

		McpJsonObject minimal = McpRequestMetadata.fromClientCapabilities(
				Mcp20260728ProtocolProfile.INSTANCE,
				McpClientCapabilities.empty()).toJsonObject();
		Assertions.assertEquals(Set.of(
				McpRequestMetadata.PROTOCOL_VERSION_KEY,
				McpRequestMetadata.CLIENT_CAPABILITIES_KEY), minimal.members().keySet());
	}

	@Test
	public void metadata_and_extension_identifiers_follow_the_final_key_grammar() {
		McpRequestMetadata inboundReservedExtension = new McpRequestMetadata(
				McpProtocolVersion.CURRENT, McpClientCapabilities.empty(), Optional.empty(),
				Optional.empty(), Optional.empty(), new McpJsonObject(Map.of(
						"io.modelcontextprotocol/future", McpJsonBoolean.TRUE)));
		Assertions.assertEquals(McpJsonBoolean.TRUE, inboundReservedExtension.toJsonObject()
				.members().get("io.modelcontextprotocol/future"));

		Assertions.assertThrows(IllegalArgumentException.class,
				() -> new McpRequestMetadata(McpProtocolVersion.CURRENT,
						McpClientCapabilities.empty(), Optional.empty(), Optional.empty(),
						Optional.empty(), new McpJsonObject(Map.of(
								"1bad.example/value", McpJsonBoolean.TRUE))));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> new McpResultMetadata(Optional.empty(), new McpJsonObject(Map.of(
						"dev.mcp/applicationValue", McpJsonBoolean.TRUE))));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpClientCapabilities.builder()
						.extension("com..example/value", McpJsonObject.empty()));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpClientCapabilities.builder()
						.extension("unnamespaced", McpJsonObject.empty()));
		Assertions.assertDoesNotThrow(
				() -> McpClientCapabilities.builder()
						.extension("com.example/approval.v2", McpJsonObject.empty()));

		for (String key : List.of(
				"", "a", "A0", "a-b_c.d", "a/", "a/name", "A/name", "a1/name",
				"a-b/name", "com.example/name", "com.example/a-b_c.d"))
			assertInboundMetadataKeyAccepted(key);

		for (String key : List.of(
				"-name", "_name", ".name", "name-", "name_", "name.", "na me",
				"1example/name", "-example/name", "example-/name", "exa_mple/name",
				"example..com/name", "/name", "com.example/-name", "com.example/_name",
				"com.example/.name", "com.example/name-", "com.example/name_",
				"com.example/name.", "com.example/na me", "com.example/name/again"))
			assertInboundMetadataKeyRejected(key);

		for (String key : List.of(
				"com.mcp/name", "com.mcp.example/name",
				"org.modelcontextprotocol/name", "org.modelcontextprotocol.example/name"))
			assertApplicationMetadataKeyRejected(key);

		for (String key : List.of(
				"mcp.example/name", "modelcontextprotocol.example/name",
				"com.example.mcp/name", "com.example.modelcontextprotocol/name"))
			assertApplicationMetadataKeyAccepted(key);

		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpClientCapabilities.builder()
						.extension("", McpJsonObject.empty()));
		Assertions.assertDoesNotThrow(() -> McpClientCapabilities.builder()
				.extension("com.example/", McpJsonObject.empty()));
	}

	private static void assertInboundMetadataKeyAccepted(String key) {
		Assertions.assertDoesNotThrow(() -> new McpRequestMetadata(
				McpProtocolVersion.CURRENT, McpClientCapabilities.empty(), Optional.empty(),
				Optional.empty(), Optional.empty(),
				new McpJsonObject(Map.of(key, McpJsonBoolean.TRUE))), key);
	}

	private static void assertInboundMetadataKeyRejected(String key) {
		Assertions.assertThrows(IllegalArgumentException.class, () -> new McpRequestMetadata(
				McpProtocolVersion.CURRENT, McpClientCapabilities.empty(), Optional.empty(),
				Optional.empty(), Optional.empty(),
				new McpJsonObject(Map.of(key, McpJsonBoolean.TRUE))), key);
	}

	private static void assertApplicationMetadataKeyAccepted(String key) {
		Assertions.assertDoesNotThrow(() -> new McpResultMetadata(
				Optional.empty(), new McpJsonObject(Map.of(key, McpJsonBoolean.TRUE))), key);
	}

	private static void assertApplicationMetadataKeyRejected(String key) {
		Assertions.assertThrows(IllegalArgumentException.class, () -> new McpResultMetadata(
				Optional.empty(), new McpJsonObject(Map.of(key, McpJsonBoolean.TRUE))), key);
	}

	@Test
	public void input_required_results_enforce_structure_supported_methods_and_unique_keys() {
		McpInputRequestDeclaration declaration =
				McpInputRequestDeclaration.elicitationForm(McpInputRequirement.CONDITIONAL);
		McpJsonObject requestedSchema = new McpJsonObject(Map.of(
				"type", new McpJsonString("object"),
				"properties", McpJsonObject.empty()));
		McpEmbeddedInputRequest inputRequest = McpEmbeddedInputRequest.fromDeclaration(
				declaration, new McpJsonObject(Map.of(
						"message", new McpJsonString("Approve?"),
						"mode", new McpJsonString("form"),
						"requestedSchema", requestedSchema)));
		McpInputRequests.Builder requestsBuilder = McpInputRequests.builder()
				.inputRequest("approval", inputRequest);
		McpInputRequests requests = requestsBuilder.build();
		McpWireResult inputRequired = McpWireResult.inputRequired(
				"tools/call", Optional.of(requests), Optional.empty(), Optional.empty(),
				McpJsonObject.empty());
		McpJsonObject json = inputRequired.toJsonObject();

		Assertions.assertEquals(McpResultType.INPUT_REQUIRED, inputRequired.resultType());
		Assertions.assertEquals(new McpJsonString("input_required"),
				json.members().get("resultType"));
		McpJsonObject serializedRequests =
				(McpJsonObject) json.members().get("inputRequests");
		McpJsonObject serializedApproval =
				(McpJsonObject) serializedRequests.members().get("approval");
		Assertions.assertEquals(new McpJsonString("elicitation/create"),
				serializedApproval.members().get("method"));
		Assertions.assertEquals(inputRequest.params(),
				serializedApproval.members().get("params"));
		Assertions.assertEquals(Set.of("method", "params"),
				serializedApproval.members().keySet());
		Assertions.assertThrows(NullPointerException.class,
				() -> new McpEmbeddedInputRequest(declaration, null,
						McpJsonObject.empty()));
		McpEmbeddedInputRequest rootsRequest = McpEmbeddedInputRequest.fromDeclaration(
				McpInputRequestDeclaration.roots(McpInputRequirement.CONDITIONAL),
				McpJsonObject.empty());
		Assertions.assertEquals(McpJsonObject.empty(),
				rootsRequest.toJsonObject().members().get("params"));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> requestsBuilder.inputRequest("approval", inputRequest));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpWireResult.inputRequired("tools/call", Optional.empty(),
						Optional.empty(), Optional.empty(), McpJsonObject.empty()));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpWireResult.inputRequired("tools/list", Optional.of(requests),
						Optional.empty(), Optional.empty(), McpJsonObject.empty()));
		Assertions.assertDoesNotThrow(() -> McpWireResult.inputRequired(
				"prompts/get", Optional.empty(), Optional.of("opaque-state"),
				Optional.empty(), McpJsonObject.empty()));
		Assertions.assertDoesNotThrow(() -> McpWireResult.inputRequired(
				"resources/read", Optional.of(requests), Optional.of("opaque-state"),
				Optional.empty(), McpJsonObject.empty()));
		Assertions.assertFalse(McpWireResult.supportsInputRequired("resources/list"));
	}

	@Test
	public void result_type_is_open_while_core_and_extension_construction_remain_distinct() {
		McpResultType extensionType = McpResultType.extension("task");
		McpWireResult extensionResult = McpWireResult.extension(
				extensionType, new McpJsonObject(Map.of(
						"taskId", new McpJsonString("task-1"))), Optional.empty());

		Assertions.assertFalse(extensionType.isCore());
		Assertions.assertEquals(new McpJsonString("task"),
				extensionResult.toJsonObject().members().get("resultType"));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpResultType.extension("complete"));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpWireResult.extension(McpResultType.INPUT_REQUIRED,
						McpJsonObject.empty(), Optional.empty()));
	}

	@Test
	public void tasksCapabilityAndMissingCapabilityEnvelopeAreIndependentSep2663Pieces() {
		String tasksExtension = "io.modelcontextprotocol/tasks";
		McpJsonObject serializedTasksCapability = McpClientCapabilities.builder()
				.extension(tasksExtension, McpJsonObject.empty())
				.build()
				.toJsonObject();
		McpJsonObject expectedTasksCapability = new McpJsonObject(Map.of(
				"extensions", new McpJsonObject(Map.of(
						tasksExtension, McpJsonObject.empty()))));

		Assertions.assertEquals(expectedTasksCapability, serializedTasksCapability);
		McpJsonObject sepCompatibleData = new McpJsonObject(Map.of(
				"requiredCapabilities", serializedTasksCapability));
		Assertions.assertEquals(expectedTasksCapability,
				sepCompatibleData.members().get("requiredCapabilities"));

		McpJsonRpcError coreEnvelope =
				McpJsonRpcError.missingRequiredClientCapabilities(Set.of(
						McpCoreClientCapability.ELICITATION_FORM));
		McpJsonObject serializedEnvelope = coreEnvelope.toJsonObject();
		Assertions.assertEquals(new McpJsonNumber(-32021L),
				serializedEnvelope.members().get("code"));
		Assertions.assertEquals(new McpJsonString(
				"Missing required client capability"),
				serializedEnvelope.members().get("message"));
		McpJsonObject envelopeData = (McpJsonObject)
				serializedEnvelope.members().get("data");
		Assertions.assertEquals(Set.of("requiredCapabilities"),
				envelopeData.members().keySet());
		McpJsonObject coreRequirements = (McpJsonObject)
				envelopeData.members().get("requiredCapabilities");
		Assertions.assertTrue(coreRequirements.members().containsKey("elicitation"));
		Assertions.assertFalse(coreRequirements.members().containsKey("extensions"),
				"The current factory remains core-only; this is not a Tasks error path.");
		Assertions.assertArrayEquals(new Class<?>[] { McpCoreClientCapability.class },
				McpClientCapabilityRequirement.class.getPermittedSubclasses());
	}

	@Test
	public void implementation_metadata_preserves_optional_fields_icons_and_extensions() {
		McpImplementationMetadata.Icon icon = new McpImplementationMetadata.Icon(
				URI.create("https://example.com/icon.svg"),
				Optional.of("image/svg+xml"),
				List.of("any"),
				Optional.of(McpImplementationMetadata.Theme.DARK),
				new McpJsonObject(Map.of(
						"com.example/icon-purpose", new McpJsonString("maskable"))));
		McpImplementationMetadata implementation = new McpImplementationMetadata(
				"catalog",
				"4.0.0",
				Optional.of("Catalog"),
				Optional.of("Catalog MCP server"),
				Optional.of(URI.create("https://example.com")),
				List.of(icon),
				new McpJsonObject(Map.of(
						"com.example/build", new McpJsonString("abc123"))));
		McpJsonObject json = implementation.toJsonObject();

		Assertions.assertEquals(new McpJsonString("catalog"), json.members().get("name"));
		Assertions.assertEquals(new McpJsonString("Catalog"), json.members().get("title"));
		Assertions.assertEquals(new McpJsonString("abc123"),
				json.members().get("com.example/build"));
		McpJsonArray icons = (McpJsonArray) json.members().get("icons");
		McpJsonObject serializedIcon = (McpJsonObject) icons.values().get(0);
		Assertions.assertEquals(new McpJsonString("dark"),
				serializedIcon.members().get("theme"));
		Assertions.assertEquals(new McpJsonString("maskable"),
				serializedIcon.members().get("com.example/icon-purpose"));
	}

	@Test
	public void protocol_error_factories_use_exact_structured_data() {
		McpJsonRpcError unsupported =
				McpJsonRpcError.unsupportedProtocolVersion("2025-11-25",
						McpProductionProtocolProfiles.REGISTRY.revisions());
		Assertions.assertEquals(McpJsonRpcError.UNSUPPORTED_PROTOCOL_VERSION,
				unsupported.code());
		McpJsonObject unsupportedData =
				(McpJsonObject) unsupported.data().orElseThrow();
		Assertions.assertEquals(new McpJsonString("2025-11-25"),
				unsupportedData.members().get("requested"));
		Assertions.assertEquals(new McpJsonArray(
						List.of(new McpJsonString(McpProtocolVersion.CURRENT))),
				unsupportedData.members().get("supported"));

		Set<McpClientCapabilityRequirement> missing = Set.of(
				McpCoreClientCapability.SAMPLING,
				McpCoreClientCapability.SAMPLING_CONTEXT);
		McpJsonRpcError missingCapability =
				McpJsonRpcError.missingRequiredClientCapabilities(missing);
		Assertions.assertEquals(McpJsonRpcError.MISSING_REQUIRED_CLIENT_CAPABILITY,
				missingCapability.code());
		McpJsonObject missingData =
				(McpJsonObject) missingCapability.data().orElseThrow();
		McpJsonObject requiredCapabilities =
				(McpJsonObject) missingData.members().get("requiredCapabilities");
		McpJsonObject sampling =
				(McpJsonObject) requiredCapabilities.members().get("sampling");
		Assertions.assertTrue(sampling.members().containsKey("context"));
		Assertions.assertEquals(new McpJsonNumber(-32021L),
				missingCapability.toJsonObject().members().get("code"));
	}
}
