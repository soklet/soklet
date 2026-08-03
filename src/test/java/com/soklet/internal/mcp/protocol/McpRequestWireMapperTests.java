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
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class McpRequestWireMapperTests {
	private static final McpJsonLimits TEST_LIMITS = new McpJsonLimits(
			65_536, 256, 16_384, 16_384, 512, 10_000, 16_384, 65_536);
	private static final McpJsonRpcEnvelopeCodec ENVELOPE_CODEC =
			new McpJsonRpcEnvelopeCodec(new McpJsonCodec(TEST_LIMITS));
	private static final McpRequestWireMapper MAPPER =
			new McpRequestWireMapper(TEST_LIMITS);

	@Test
	public void mapsAndByteRoundTripsTheMinimalUniversalRequestSpine() {
		McpJsonRpcMessage.Request mapped = mapRequest(requestWithParams("""
				{"_meta":{
				  "io.modelcontextprotocol/protocolVersion":"2026-07-28",
				  "io.modelcontextprotocol/clientCapabilities":{}
				}}
				"""));

		Assertions.assertEquals(new McpJsonRpcId.StringId("request-1"), mapped.id());
		Assertions.assertEquals("server/discover", mapped.method());
		Assertions.assertEquals("2026-07-28", mapped.params().metadata().protocolVersion());
		Assertions.assertEquals(McpClientCapabilities.empty(),
				mapped.params().metadata().clientCapabilities());
		Assertions.assertEquals(McpJsonObject.empty(), mapped.params().fields());

		McpJsonRpcEnvelope.Request encodedEnvelope = Assertions.assertInstanceOf(
				McpJsonRpcEnvelope.Request.class,
				ENVELOPE_CODEC.decode(ENVELOPE_CODEC.encode(mapped)));
		Assertions.assertEquals(mapped, MAPPER.map(encodedEnvelope));
	}

	@Test
	public void preservesUnknownMethodParametersAndValidRequestMetadata() {
		McpJsonRpcMessage.Request mapped = mapRequest(requestWithParams("""
				{"_meta":{
				  "io.modelcontextprotocol/protocolVersion":"future-version",
				  "io.modelcontextprotocol/clientCapabilities":{},
				  "io.modelcontextprotocol/future":null,
				  "com.example/request-metadata":{"future":true},
				  "unprefixed_name":7
				 },
				 "cursor":{"not":"validated-here"},
				 "arguments":["also","raw"],
				 "future":null}
				"""));

		McpRequestMetadata metadata = mapped.params().metadata();
		Assertions.assertEquals("future-version", metadata.protocolVersion());
		Assertions.assertSame(McpJsonNull.INSTANCE,
				metadata.extensionFields().members().get("io.modelcontextprotocol/future"));
		Assertions.assertEquals(new McpJsonNumber(7),
				metadata.extensionFields().members().get("unprefixed_name"));
		Assertions.assertInstanceOf(McpJsonObject.class,
				mapped.params().fields().members().get("cursor"));
		Assertions.assertInstanceOf(McpJsonArray.class,
				mapped.params().fields().members().get("arguments"));
		Assertions.assertSame(McpJsonNull.INSTANCE,
				mapped.params().fields().members().get("future"));
	}

	@Test
	public void acceptsEmptyAndUnknownProtocolVersionsForLaterVersionValidation() {
		for (String protocolVersion : List.of("", "unknown", "2025-11-25")) {
			McpJsonRpcMessage.Request mapped = mapRequest(requestWithParams("""
					{"_meta":{
					  "io.modelcontextprotocol/protocolVersion":"%s",
					  "io.modelcontextprotocol/clientCapabilities":{}
					}}
					""".formatted(protocolVersion)));
			Assertions.assertEquals(protocolVersion,
					mapped.params().metadata().protocolVersion());
		}
	}

	@Test
	public void mapsOpenClientCapabilitiesWithoutInventingSupport() {
		McpJsonRpcMessage.Request mapped = mapRequest(requestWithParams("""
				{"_meta":{
				  "io.modelcontextprotocol/protocolVersion":"2026-07-28",
				  "io.modelcontextprotocol/clientCapabilities":{
				    "elicitation":{},
				    "roots":{"futureSetting":"preserved"},
				    "sampling":{"context":{},"tools":{},"futureSetting":[true]},
				    "extensions":{"com.example/client-extension":{"enabled":true}},
				    "experimental":{"":{},"   ":{"setting":1}},
				    "futureScalar":3,
				    "futureArray":[false],
				    "futureNull":null
				  }
				}}
				"""));

		McpClientCapabilities capabilities = mapped.params().metadata().clientCapabilities();
		Assertions.assertTrue(capabilities.supports(McpCoreClientCapability.ELICITATION_FORM));
		Assertions.assertFalse(capabilities.supports(McpCoreClientCapability.ELICITATION_URL));
		Assertions.assertTrue(capabilities.supports(McpCoreClientCapability.ROOTS));
		Assertions.assertTrue(capabilities.supports(McpCoreClientCapability.SAMPLING));
		Assertions.assertTrue(capabilities.supports(McpCoreClientCapability.SAMPLING_CONTEXT));
		Assertions.assertTrue(capabilities.supports(McpCoreClientCapability.SAMPLING_TOOLS));
		Assertions.assertEquals(McpJsonBoolean.TRUE,
				capabilities.extensions().get("com.example/client-extension")
						.members().get("enabled"));
		Assertions.assertTrue(capabilities.experimental().containsKey(""));
		Assertions.assertTrue(capabilities.experimental().containsKey("   "));
		Assertions.assertEquals(new McpJsonNumber(3),
				capabilities.unknownCapabilities().get("futureScalar"));
		Assertions.assertSame(McpJsonNull.INSTANCE,
				capabilities.unknownCapabilities().get("futureNull"));
	}

	@Test
	public void mapsProgressTokensAndDeprecatedLogLevelsExactly() {
		BigInteger enormous = new BigInteger("184467440737095516160123456789");

		for (Map.Entry<String, McpProgressToken> fixture : Map.<String, McpProgressToken>of(
				"\"opaque\"", new McpProgressToken.StringToken("opaque"),
				enormous.toString(), new McpProgressToken.IntegerToken(enormous),
				"42.0", new McpProgressToken.IntegerToken(BigInteger.valueOf(42))).entrySet()) {
			McpRequestMetadata metadata = metadataWithOptionalFields(
					"\"progressToken\":" + fixture.getKey());
			Assertions.assertEquals(Optional.of(fixture.getValue()), metadata.progressToken());
		}

		for (McpRequestLogLevel level : McpRequestLogLevel.values()) {
			McpRequestMetadata metadata = metadataWithOptionalFields(
					"\"io.modelcontextprotocol/logLevel\":\"" + level.wireValue() + "\"");
			Assertions.assertEquals(Optional.of(level), metadata.deprecatedLogLevel());
		}
	}

	@Test
	public void exponentCompressedProgressTokensMustFitTheirExpandedWireForm() {
		McpRequestMetadata accepted = metadataWithOptionalFields("\"progressToken\":1e511");
		McpProgressToken.IntegerToken acceptedToken = Assertions.assertInstanceOf(
				McpProgressToken.IntegerToken.class,
				accepted.progressToken().orElseThrow());
		Assertions.assertEquals(512, acceptedToken.value().toString().length());

		for (String rejected : List.of("1e512", "-1e511", "1e10000"))
			assertInvalidParams(requestWithOptionalFields("\"progressToken\":" + rejected));
	}

	@Test
	public void mapsCompleteClientInformationIncludingIconsAndExtensions() {
		McpRequestMetadata metadata = metadataWithOptionalFields("""
				"io.modelcontextprotocol/clientInfo":{
				  "name":"",
				  "version":"",
				  "title":"Client",
				  "description":"Test client",
				  "websiteUrl":"https://example.com/client",
				  "icons":[{
				    "src":"data:image/png;base64,AA==",
				    "mimeType":"image/png",
				    "sizes":["48x48","any"],
				    "theme":"dark",
				    "future":true
				  }],
				  "future":"preserved"
				}
				""");
		McpImplementationMetadata client = metadata.clientInformation().orElseThrow();

		Assertions.assertEquals("", client.name());
		Assertions.assertEquals("", client.version());
		Assertions.assertEquals(Optional.of("Client"), client.title());
		Assertions.assertEquals(Optional.of(URI.create("https://example.com/client")),
				client.websiteUrl());
		Assertions.assertEquals(new McpJsonString("preserved"),
				client.extensionFields().members().get("future"));
		McpImplementationMetadata.Icon icon = client.icons().get(0);
		Assertions.assertEquals(URI.create("data:image/png;base64,AA=="), icon.source());
		Assertions.assertEquals(List.of("48x48", "any"), icon.sizes());
		Assertions.assertEquals(Optional.of(McpImplementationMetadata.Theme.DARK),
				icon.theme());
		Assertions.assertEquals(McpJsonBoolean.TRUE,
				icon.extensionFields().members().get("future"));
	}

	@Test
	public void rejectsMissingOrNonObjectParamsAndMetadataAsInvalidParams() {
		List<String> invalidRequests = List.of(
				"{\"jsonrpc\":\"2.0\",\"id\":\"request-1\",\"method\":\"server/discover\"}",
				requestWithParams("null"),
				requestWithParams("[]"),
				requestWithParams("{}"),
				requestWithParams("{\"_meta\":null}"),
				requestWithParams("{\"_meta\":[]}"));

		for (String invalidRequest : invalidRequests)
			assertInvalidParams(invalidRequest);
	}

	@Test
	public void rejectsMissingAndMistypedRequiredRequestMetadata() {
		for (String metadata : List.of(
				"{}",
				"{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\"}",
				"{\"io.modelcontextprotocol/clientCapabilities\":{}}",
				"{\"io.modelcontextprotocol/protocolVersion\":null,\"io.modelcontextprotocol/clientCapabilities\":{}}",
				"{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\",\"io.modelcontextprotocol/clientCapabilities\":null}",
				"{\"io.modelcontextprotocol/protocolVersion\":1,\"io.modelcontextprotocol/clientCapabilities\":{}}",
				"{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\",\"io.modelcontextprotocol/clientCapabilities\":[]}")) {
			assertInvalidParams(requestWithParams("{\"_meta\":" + metadata + "}"));
		}
	}

	@Test
	public void rejectsMalformedKnownCapabilitiesAndExtensionIdentifiers() {
		for (String capabilities : List.of(
				"{\"elicitation\":null}",
				"{\"elicitation\":{\"form\":true}}",
				"{\"roots\":[]}",
				"{\"sampling\":{\"tools\":false}}",
				"{\"extensions\":[]}",
				"{\"extensions\":{\"com.example/extension\":true}}",
				"{\"extensions\":{\"not-prefixed\":{}}}",
				"{\"experimental\":{\"future\":null}}")) {
			assertInvalidParams(requestWithCapabilities(capabilities));
		}
	}

	@Test
	public void rejectsNullFractionalAndMistypedOptionalRequestMetadata() {
		for (String optionalField : List.of(
				"\"progressToken\":null",
				"\"progressToken\":1.5",
				"\"progressToken\":true",
				"\"io.modelcontextprotocol/logLevel\":null",
				"\"io.modelcontextprotocol/logLevel\":\"verbose\"",
				"\"io.modelcontextprotocol/clientInfo\":null",
				"\"io.modelcontextprotocol/clientInfo\":[]")) {
			assertInvalidParams(requestWithOptionalFields(optionalField));
		}
	}

	@Test
	public void rejectsMalformedClientInformationAndIcons() {
		for (String clientInfo : List.of(
				"{}",
				"{\"name\":\"client\"}",
				"{\"name\":1,\"version\":\"1\"}",
				"{\"name\":\"client\",\"version\":\"1\",\"icons\":null}",
				"{\"name\":\"client\",\"version\":\"1\",\"icons\":[{}]}",
				"{\"name\":\"client\",\"version\":\"1\",\"icons\":[{\"src\":\"https://example.com/icon\",\"sizes\":[1]}]}",
				"{\"name\":\"client\",\"version\":\"1\",\"icons\":[{\"src\":\"https://example.com/icon\",\"theme\":\"auto\"}]}")) {
			assertInvalidParams(requestWithOptionalFields(
					"\"io.modelcontextprotocol/clientInfo\":" + clientInfo));
		}
	}

	@Test
	public void implementationAndIconUrisMustBeAbsolute() {
		for (String relativeValue : List.of("", "images/icon.png", "../client")) {
			assertInvalidParams(requestWithOptionalFields("""
					"io.modelcontextprotocol/clientInfo":{
					  "name":"client","version":"1","websiteUrl":"%s"
					}
					""".formatted(relativeValue)));
			assertInvalidParams(requestWithOptionalFields("""
					"io.modelcontextprotocol/clientInfo":{
					  "name":"client","version":"1","icons":[{"src":"%s"}]
					}
					""".formatted(relativeValue)));
		}

		for (String absoluteValue : List.of(
				"https://example.com/client", "data:image/png;base64,AA==", "urn:test:client")) {
			McpRequestMetadata metadata = metadataWithOptionalFields("""
					"io.modelcontextprotocol/clientInfo":{
					  "name":"client","version":"1","websiteUrl":"%s",
					  "icons":[{"src":"%s"}]
					}
					""".formatted(absoluteValue, absoluteValue));
			Assertions.assertEquals(URI.create(absoluteValue),
					metadata.clientInformation().orElseThrow().websiteUrl().orElseThrow());
		}
	}

	@Test
	public void emptyKnownContainersCanonicalizeToOmissionAfterSemanticMapping() {
		McpJsonRpcMessage.Request mapped = mapRequest(requestWithParams("""
				{"_meta":{
				  "io.modelcontextprotocol/protocolVersion":"2026-07-28",
				  "io.modelcontextprotocol/clientCapabilities":{
				    "roots":{},"extensions":{},"experimental":{}
				  },
				  "io.modelcontextprotocol/clientInfo":{
				    "name":"client","version":"1","icons":[]
				  }
				}}
				"""));
		McpJsonObject serializedParams = mapped.params().toJsonObject();
		McpJsonObject metadata = (McpJsonObject) serializedParams.members().get("_meta");
		McpJsonObject capabilities = (McpJsonObject) metadata.members().get(
				McpRequestMetadata.CLIENT_CAPABILITIES_KEY);
		McpJsonObject clientInfo = (McpJsonObject) metadata.members().get(
				McpRequestMetadata.CLIENT_INFORMATION_KEY);

		Assertions.assertTrue(capabilities.members().containsKey("roots"));
		Assertions.assertFalse(capabilities.members().containsKey("extensions"));
		Assertions.assertFalse(capabilities.members().containsKey("experimental"));
		Assertions.assertFalse(clientInfo.members().containsKey("icons"));
	}

	@Test
	public void emptyIconSizesCanonicalizeToOmissionAfterSemanticMapping() {
		McpJsonRpcMessage.Request mapped = mapRequest(requestWithParams("""
				{"_meta":{
				  "io.modelcontextprotocol/protocolVersion":"2026-07-28",
				  "io.modelcontextprotocol/clientCapabilities":{},
				  "io.modelcontextprotocol/clientInfo":{
				    "name":"client","version":"1",
				    "icons":[{"src":"urn:test:icon","sizes":[]}]
				  }
				}}
				"""));
		McpJsonObject serializedParams = mapped.params().toJsonObject();
		McpJsonObject metadata = (McpJsonObject) serializedParams.members().get("_meta");
		McpJsonObject clientInfo = (McpJsonObject) metadata.members().get(
				McpRequestMetadata.CLIENT_INFORMATION_KEY);
		McpJsonArray icons = (McpJsonArray) clientInfo.members().get("icons");
		McpJsonObject icon = (McpJsonObject) icons.values().get(0);

		Assertions.assertFalse(icon.members().containsKey("sizes"));
	}

	@Test
	public void rejectsMalformedMetadataKeysButPreservesReservedInboundKeys() {
		assertInvalidParams(requestWithOptionalFields("\"bad/key/again\":true"));
		assertInvalidParams(requestWithOptionalFields("\" bad\":true"));

		McpRequestMetadata metadata = metadataWithOptionalFields(
				"\"dev.mcp/future\":true");
		Assertions.assertEquals(McpJsonBoolean.TRUE,
				metadata.extensionFields().members().get("dev.mcp/future"));
	}

	@Test
	public void outboundConvenienceFactoryStillRejectsBlankServerInformation() {
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpImplementationMetadata.withNameAndVersion("", "3.6.0"));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpImplementationMetadata.withNameAndVersion("server", " "));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> new McpImplementationMetadata("server", "3.6.0",
						Optional.empty(), Optional.empty(),
						Optional.of(URI.create("server.html")), List.of(),
						McpJsonObject.empty()));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> new McpImplementationMetadata.Icon(URI.create("icons/server.png"),
						Optional.empty(), List.of(), Optional.empty(),
						McpJsonObject.empty()));
	}

	private McpRequestMetadata metadataWithOptionalFields(String optionalFields) {
		return mapRequest(requestWithOptionalFields(optionalFields)).params().metadata();
	}

	private String requestWithOptionalFields(String optionalFields) {
		return requestWithParams("""
				{"_meta":{
				  "io.modelcontextprotocol/protocolVersion":"2026-07-28",
				  "io.modelcontextprotocol/clientCapabilities":{},
				  %s
				}}
				""".formatted(optionalFields));
	}

	private String requestWithCapabilities(String capabilities) {
		return requestWithParams("""
				{"_meta":{
				  "io.modelcontextprotocol/protocolVersion":"2026-07-28",
				  "io.modelcontextprotocol/clientCapabilities":%s
				}}
				""".formatted(capabilities));
	}

	private String requestWithParams(String params) {
		return "{\"jsonrpc\":\"2.0\",\"id\":\"request-1\","
				+ "\"method\":\"server/discover\",\"params\":" + params + "}";
	}

	private McpJsonRpcMessage.Request mapRequest(String json) {
		McpJsonRpcEnvelope.Request request = Assertions.assertInstanceOf(
				McpJsonRpcEnvelope.Request.class, ENVELOPE_CODEC.decode(json));
		return MAPPER.map(request);
	}

	private void assertInvalidParams(String json) {
		McpJsonRpcEnvelope.Request request = Assertions.assertInstanceOf(
				McpJsonRpcEnvelope.Request.class, ENVELOPE_CODEC.decode(json));
		McpWireDecodingException exception = Assertions.assertThrows(
				McpWireDecodingException.class, () -> MAPPER.map(request));
		Assertions.assertEquals(McpWireDecodingException.Kind.INVALID_PARAMS,
				exception.kind());
		Assertions.assertEquals(Optional.of(new McpJsonRpcId.StringId("request-1")),
				exception.readableRequestId());
	}
}
