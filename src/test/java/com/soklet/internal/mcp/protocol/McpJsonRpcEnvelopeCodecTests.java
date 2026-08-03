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

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class McpJsonRpcEnvelopeCodecTests {
	private static final McpJsonLimits TEST_LIMITS = new McpJsonLimits(
			65_536, 256, 16_384, 16_384, 512, 10_000, 16_384, 65_536);
	private static final McpJsonRpcEnvelopeCodec CODEC =
			new McpJsonRpcEnvelopeCodec(new McpJsonCodec(TEST_LIMITS));

	@Test
	public void decodesRequestAndPreservesRawParametersAndOpenEnvelopeFields() {
		McpJsonRpcEnvelope decoded = CODEC.decode("""
				{"com.example/envelope":{"future":true},"":"blank-name","jsonrpc":"2.0",
				 "id":"request-1","method":"tools/call","params":{"_meta":{},"name":"weather"}}
				""");

		McpJsonRpcEnvelope.Request request =
				Assertions.assertInstanceOf(McpJsonRpcEnvelope.Request.class, decoded);
		Assertions.assertEquals(new McpJsonRpcId.StringId("request-1"), request.id());
		Assertions.assertEquals("tools/call", request.method());
		McpJsonObject params = Assertions.assertInstanceOf(
				McpJsonObject.class, request.params().orElseThrow());
		Assertions.assertEquals(new McpJsonString("weather"), params.members().get("name"));
		Assertions.assertEquals(McpJsonBoolean.TRUE,
				((McpJsonObject) request.extensionFields().members()
						.get("com.example/envelope")).members().get("future"));
		Assertions.assertEquals(new McpJsonString("blank-name"),
				request.extensionFields().members().get(""));
	}

	@Test
	public void classifiesCancellationBeforeMethodSpecificParameterValidation() {
		McpJsonRpcEnvelope decoded = CODEC.decode("""
				{"jsonrpc":"2.0","method":"notifications/cancelled","params":"malformed-but-ignored"}
				""");

		McpJsonRpcEnvelope.Notification notification =
				Assertions.assertInstanceOf(McpJsonRpcEnvelope.Notification.class, decoded);
		Assertions.assertEquals("notifications/cancelled", notification.method());
		Assertions.assertEquals(Optional.of(new McpJsonString("malformed-but-ignored")),
				notification.params());
		Assertions.assertEquals(notification, CODEC.decode(CODEC.encode(notification)));
	}

	@Test
	public void distinguishesAbsentParametersFromExplicitNull() {
		McpJsonRpcEnvelope.Notification absent = Assertions.assertInstanceOf(
				McpJsonRpcEnvelope.Notification.class,
				CODEC.decode("{\"jsonrpc\":\"2.0\",\"method\":\"future/notification\"}"));
		McpJsonRpcEnvelope.Notification explicitNull = Assertions.assertInstanceOf(
				McpJsonRpcEnvelope.Notification.class,
				CODEC.decode("{\"jsonrpc\":\"2.0\",\"method\":\"future/notification\",\"params\":null}"));

		Assertions.assertEquals(Optional.empty(), absent.params());
		Assertions.assertEquals(Optional.of(McpJsonNull.INSTANCE), explicitNull.params());
		Assertions.assertFalse(absent.toJsonObject().members().containsKey("params"));
		Assertions.assertSame(McpJsonNull.INSTANCE,
				explicitNull.toJsonObject().members().get("params"));
	}

	@Test
	public void classifiesAllFourJsonRpcEnvelopeVariants() {
		List<String> jsonMessages = List.of(
				"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"server/discover\"}",
				"{\"jsonrpc\":\"2.0\",\"method\":\"future/notification\"}",
				"{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"resultType\":\"complete\"}}",
				"{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"error\":{\"code\":-32600,\"message\":\"Invalid request\"}}",
				"{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32700,\"message\":\"Parse error\"}}"
		);

		Assertions.assertInstanceOf(McpJsonRpcEnvelope.Request.class,
				CODEC.decode(jsonMessages.get(0)));
		Assertions.assertInstanceOf(McpJsonRpcEnvelope.Notification.class,
				CODEC.decode(jsonMessages.get(1)));
		Assertions.assertInstanceOf(McpJsonRpcEnvelope.ResultResponse.class,
				CODEC.decode(jsonMessages.get(2)));
		McpJsonRpcEnvelope.ErrorResponse correlated = Assertions.assertInstanceOf(
				McpJsonRpcEnvelope.ErrorResponse.class, CODEC.decode(jsonMessages.get(3)));
		McpJsonRpcEnvelope.ErrorResponse uncorrelated = Assertions.assertInstanceOf(
				McpJsonRpcEnvelope.ErrorResponse.class, CODEC.decode(jsonMessages.get(4)));
		Assertions.assertEquals(Optional.of(new McpJsonRpcId.StringId("1")), correlated.id());
		Assertions.assertEquals(Optional.empty(), uncorrelated.id());
	}

	@Test
	public void preservesRawResponsePayloadsUntilTheResponseMappingStage() {
		McpJsonRpcEnvelope.ResultResponse result = Assertions.assertInstanceOf(
				McpJsonRpcEnvelope.ResultResponse.class,
				CODEC.decode("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":false}"));
		McpJsonRpcEnvelope.ErrorResponse error = Assertions.assertInstanceOf(
				McpJsonRpcEnvelope.ErrorResponse.class,
				CODEC.decode("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":\"raw\"}"));

		Assertions.assertSame(McpJsonBoolean.FALSE, result.result());
		Assertions.assertEquals(new McpJsonString("raw"), error.error());
		Assertions.assertEquals(result, CODEC.decode(CODEC.encode(result)));
		Assertions.assertEquals(error, CODEC.decode(CODEC.encode(error)));
	}

	@Test
	public void requestIdsPreserveStringIntegerIdentityAndArbitraryPrecision() {
		BigInteger enormous = new BigInteger("922337203685477580812345678901234567890");
		McpJsonRpcEnvelope.Request stringId = requestWithId("\"1\"");
		McpJsonRpcEnvelope.Request integerId = requestWithId("1");
		McpJsonRpcEnvelope.Request decimalIntegerId = requestWithId("1.0");
		McpJsonRpcEnvelope.Request exponentIntegerId = requestWithId("1e2");
		McpJsonRpcEnvelope.Request enormousId = requestWithId(enormous.toString());

		Assertions.assertEquals(new McpJsonRpcId.StringId("1"), stringId.id());
		Assertions.assertEquals(new McpJsonRpcId.IntegerId(BigInteger.ONE), integerId.id());
		Assertions.assertEquals(integerId.id(), decimalIntegerId.id());
		Assertions.assertEquals(new McpJsonRpcId.IntegerId(BigInteger.valueOf(100)),
				exponentIntegerId.id());
		Assertions.assertEquals(new McpJsonRpcId.IntegerId(enormous), enormousId.id());
		Assertions.assertNotEquals(stringId.id(), integerId.id());
	}

	@Test
	public void exponentCompressedIntegerIdsMustFitTheirExpandedWireForm() {
		McpJsonRpcEnvelope.Request accepted = requestWithId("1e511");
		McpJsonRpcId.IntegerId acceptedId = Assertions.assertInstanceOf(
				McpJsonRpcId.IntegerId.class, accepted.id());
		Assertions.assertEquals(512, acceptedId.value().toString().length());
		Assertions.assertEquals(accepted, CODEC.decode(CODEC.encode(accepted)));

		for (String rejectedId : List.of("1e512", "-1e511", "1e10000")) {
			McpWireDecodingException exception = Assertions.assertThrows(
					McpWireDecodingException.class, () -> requestWithId(rejectedId));
			Assertions.assertEquals(McpWireDecodingException.Kind.INVALID_REQUEST,
					exception.kind());
			Assertions.assertEquals(Optional.empty(), exception.readableRequestId());
		}
	}

	@Test
	public void stringIdsMustLeaveRoomForACorrelatedFallbackResponse() {
		String value = "\u2028".repeat(4);
		McpJsonRpcId.StringId id = new McpJsonRpcId.StringId(value);
		McpJsonRpcMessage.ErrorResponse fallback = new McpJsonRpcMessage.ErrorResponse(
				Optional.of(id),
				new McpJsonRpcError(McpJsonRpcError.INTERNAL_ERROR,
						"Internal error", Optional.empty()),
				McpJsonObject.empty());
		int exactFallbackBytes = CODEC.encode(fallback).length;
		String requestJson = "{\"jsonrpc\":\"2.0\",\"id\":\"" + value
				+ "\",\"method\":\"server/discover\"}";

		McpJsonRpcEnvelopeCodec exactCodec = codecWithOutputLimit(exactFallbackBytes);
		McpJsonRpcEnvelope.Request accepted = Assertions.assertInstanceOf(
				McpJsonRpcEnvelope.Request.class, exactCodec.decode(requestJson));
		Assertions.assertEquals(id, accepted.id());

		McpJsonRpcEnvelopeCodec oneByteShortCodec =
				codecWithOutputLimit(exactFallbackBytes - 1);
		McpWireDecodingException rejected = Assertions.assertThrows(
				McpWireDecodingException.class,
				() -> oneByteShortCodec.decode(requestJson));
		Assertions.assertEquals(McpWireDecodingException.Kind.INVALID_REQUEST,
				rejected.kind());
		Assertions.assertEquals(Optional.empty(), rejected.readableRequestId());
	}

	@Test
	public void rejectsEveryNonStringNonIntegerRequestId() {
		for (String invalidId : List.of("null", "true", "{}", "[]", "1.5", "1e-1")) {
			Assertions.assertThrows(IllegalArgumentException.class,
					() -> requestWithId(invalidId), invalidId);
		}
	}

	@Test
	public void rejectsNonObjectsBatchesAndInvalidJsonRpcVersions() {
		for (String invalidMessage : List.of(
				"null",
				"[]",
				"[{\"jsonrpc\":\"2.0\",\"method\":\"notification\"}]",
				"{}",
				"{\"jsonrpc\":2,\"method\":\"notification\"}",
				"{\"jsonrpc\":\"1.0\",\"method\":\"notification\"}",
				"{\"jsonrpc\":\"2.0\"}")) {
			Assertions.assertThrows(IllegalArgumentException.class,
					() -> CODEC.decode(invalidMessage), invalidMessage);
		}
	}

	@Test
	public void reportsTypedParseAndInvalidRequestFailuresWithoutMessageInspection() {
		McpWireDecodingException parseFailure = Assertions.assertThrows(
				McpWireDecodingException.class,
				() -> CODEC.decode("{\"jsonrpc\":\"2.0\","));
		Assertions.assertEquals(McpWireDecodingException.Kind.PARSE_ERROR,
				parseFailure.kind());
		Assertions.assertEquals(Optional.empty(), parseFailure.readableRequestId());
		Assertions.assertNotNull(parseFailure.getCause());

		McpWireDecodingException invalidRequest = Assertions.assertThrows(
				McpWireDecodingException.class,
				() -> CODEC.decode("{\"jsonrpc\":\"2.0\",\"id\":\"known\",\"method\":false}"));
		Assertions.assertEquals(McpWireDecodingException.Kind.INVALID_REQUEST,
				invalidRequest.kind());
		Assertions.assertEquals(Optional.of(new McpJsonRpcId.StringId("known")),
				invalidRequest.readableRequestId());
		Assertions.assertNull(invalidRequest.getCause());

		McpWireDecodingException invalidId = Assertions.assertThrows(
				McpWireDecodingException.class,
				() -> CODEC.decode("{\"jsonrpc\":\"2.0\",\"id\":null,\"method\":\"request\"}"));
		Assertions.assertEquals(Optional.empty(), invalidId.readableRequestId());
	}

	@Test
	public void rejectsAmbiguousAndCrossVariantReservedFields() {
		for (String invalidMessage : List.of(
				"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"request\",\"result\":{}}",
				"{\"jsonrpc\":\"2.0\",\"method\":\"notification\",\"error\":{}}",
				"{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{},\"params\":{}}",
				"{\"jsonrpc\":\"2.0\",\"error\":{},\"result\":{}}",
				"{\"jsonrpc\":\"2.0\",\"id\":1,\"params\":{}}",
				"{\"jsonrpc\":\"2.0\",\"result\":{}}",
				"{\"jsonrpc\":\"2.0\",\"method\":null}")) {
			Assertions.assertThrows(IllegalArgumentException.class,
					() -> CODEC.decode(invalidMessage), invalidMessage);
		}
	}

	@Test
	public void everyDecodedEnvelopeSurvivesAByteRoundTrip() {
		for (String json : List.of(
				"{\"future\":null,\"jsonrpc\":\"2.0\",\"id\":1.0,\"method\":\"\",\"params\":7}",
				"{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":false}",
				"{\"jsonrpc\":\"2.0\",\"id\":-4,\"result\":{\"resultType\":\"complete\",\"future\":true}}",
				"{\"jsonrpc\":\"2.0\",\"id\":\"x\",\"error\":{\"code\":-32602,\"message\":\"Invalid params\",\"future\":1}}",
				"{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32700,\"message\":\"Parse error\"}}")) {
			McpJsonRpcEnvelope decoded = CODEC.decode(json);
			Assertions.assertEquals(decoded, CODEC.decode(CODEC.encode(decoded)), json);
		}
	}

	@Test
	public void serializesValidatedOutboundMessagesThroughTheBoundedJsonCodec() {
		McpJsonRpcMessage.ErrorResponse message = new McpJsonRpcMessage.ErrorResponse(
				Optional.of(new McpJsonRpcId.IntegerId(BigInteger.valueOf(42))),
				new McpJsonRpcError(McpJsonRpcError.INVALID_PARAMS,
						"Invalid params", Optional.of(new McpJsonObject(
								Map.of("reason", new McpJsonString("missing name"))))),
				new McpJsonObject(Map.of("future", McpJsonBoolean.TRUE)));

		String encoded = CODEC.encodeToString(message);
		McpJsonRpcEnvelope.ErrorResponse decoded = Assertions.assertInstanceOf(
				McpJsonRpcEnvelope.ErrorResponse.class,
				CODEC.decode(encoded.getBytes(StandardCharsets.UTF_8)));

		Assertions.assertEquals(Optional.of(
				new McpJsonRpcId.IntegerId(BigInteger.valueOf(42))), decoded.id());
		Assertions.assertEquals(McpJsonBoolean.TRUE,
				decoded.extensionFields().members().get("future"));
		McpJsonObject error = Assertions.assertInstanceOf(
				McpJsonObject.class, decoded.error());
		Assertions.assertEquals(new McpJsonString("Invalid params"),
				error.members().get("message"));
	}

	@Test
	public void delegatesStrictUtf8AndOutputBoundsToTheJsonCodec() {
		byte[] malformedUtf8 = {
				'{', '"', 'j', 's', 'o', 'n', 'r', 'p', 'c', '"', ':', '"',
				'2', '.', '0', '"', ',', '"', 'm', 'e', 't', 'h', 'o', 'd', '"',
				':', '"', (byte) 0xC0, (byte) 0xAF, '"', '}'
		};
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> CODEC.decode(malformedUtf8));

		McpJsonRpcEnvelopeCodec tinyOutputCodec = new McpJsonRpcEnvelopeCodec(
				new McpJsonCodec(new McpJsonLimits(
						65_536, 256, 16_384, 16_384, 512, 10_000, 16_384, 32)));
		McpJsonRpcEnvelope notification = new McpJsonRpcEnvelope.Notification(
				"a-method-too-long-for-the-output-bound", Optional.empty(),
				McpJsonObject.empty());
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> tinyOutputCodec.encode(notification));
	}

	private McpJsonRpcEnvelope.Request requestWithId(String idJson) {
		return Assertions.assertInstanceOf(McpJsonRpcEnvelope.Request.class,
				CODEC.decode("{\"jsonrpc\":\"2.0\",\"id\":" + idJson
						+ ",\"method\":\"server/discover\"}"));
	}

	private McpJsonRpcEnvelopeCodec codecWithOutputLimit(int maximumOutputBytes) {
		return new McpJsonRpcEnvelopeCodec(new McpJsonCodec(new McpJsonLimits(
				65_536, 256, 16_384, 16_384, 512, 10_000, 16_384,
				maximumOutputBytes)));
	}
}
