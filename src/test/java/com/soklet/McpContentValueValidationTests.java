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

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Public construction boundaries for content and JSON scalar values. */
class McpContentValueValidationTests {
	private static final URI RESOURCE = URI.create("test://content/value");
	private static final String CANARY = "private-content-canary";

	@Test
	void malformedUtf16FailsAtThePublicFactoryOrSetterWithoutRetainingInput() {
		List<Consumer<String>> factories = List.of(
				McpTextContent::fromText,
				McpTextContent::withText,
				value -> McpTextResourceContents.withUriAndText(RESOURCE, value),
				McpJsonString::fromValue,
				value -> McpJsonObject.fromMembers(Map.of(value, McpJsonNull.INSTANCE)),
				value -> McpJsonObject.builder().put(value, true),
				value -> McpPromptOutput.builder().description(value),
				value -> McpArgumentCompletionResult.withValues(List.of("valid", value)),
				value -> McpJsonRpcError.fromApplication(1000, value),
				value -> McpJsonRpcError.fromInvalidParameters(value),
				value -> McpImplementation.withNameAndVersion(value, "1"),
				value -> McpImplementation.withNameAndVersion("name", value),
				value -> McpImplementation.withNameAndVersion("name", "1").title(value),
				value -> McpImplementation.withNameAndVersion("name", "1").description(value),
				value -> McpProgressUpdate.withProgress(1D).message(value),
				McpRequestId::fromString,
				value -> McpPromptArgumentDeclaration.withName("argument").description(value),
				value -> McpPromptArgumentDeclaration.withName("argument").title(value),
				value -> McpToolAnnotations.builder().title(value),
				value -> McpResourceDescriptor.withUriAndName(RESOURCE, value),
				value -> McpEndpoint.withPath("/mcp", McpImplementation.withNameAndVersion("name", "1").build(),
						java.util.Set.of(McpProtocolVersion.V2026_07_28)).instructions(value),
				value -> McpToolRegistration.withName("tool", java.util.Set.of(McpProtocolVersion.V2026_07_28))
						.jsonObjectArguments().handler((request, arguments, features) -> { throw new AssertionError(); }).description(value),
				value -> McpPromptRegistration.withName("prompt", java.util.Set.of(McpProtocolVersion.V2026_07_28))
						.handler((request, arguments, features) -> { throw new AssertionError(); }).title(value),
				value -> McpResourceRegistration.withUriAndName(RESOURCE, "resource", java.util.Set.of(McpProtocolVersion.V2026_07_28))
						.handler((request, arguments, features) -> { throw new AssertionError(); }).description(value),
				value -> McpResourcePage.builder().nextCursor(value),
				value -> McpSkillPage.builder().nextCursor(value),
				value -> McpInputRequiredResult.withApplicationRequestState(value),
				value -> McpInputRequiredResult.withInputRequest(value, inputRequest()),
				value -> McpTask.withTaskId("task", McpTaskOrigin.fromPersistedState(McpJsonObject.emptyInstance()),
						McpTaskStatus.INPUT_REQUIRED, java.time.Instant.EPOCH, java.time.Instant.EPOCH).addInputRequest(value, inputRequest()),
				value -> McpTask.withTaskId("task", McpTaskOrigin.fromPersistedState(McpJsonObject.emptyInstance()),
						McpTaskStatus.INPUT_REQUIRED, java.time.Instant.EPOCH, java.time.Instant.EPOCH).addInputRequests(Map.of(value, inputRequest())),
				value -> McpLocalizableText.fromCoordinateAndDefaultText(
						McpTextCoordinate.fromComponents("/mcp", McpTextOwnerType.TOOL, "tool", "/title"), value),
				value -> McpToolRegistration.withName("tool", java.util.Set.of(McpProtocolVersion.V2026_07_28))
						.jsonObjectArguments().handler((request, arguments, features) -> McpCompleteResult.fromToolText("ok")).rateLimiterName(value),
				value -> McpRateLimiterRegistry.builder().addRateLimiter(value, context -> McpRateLimitDecision.allowed()),
				McpLocalizationResult::localized,
				McpTask::requireTaskId,
				value -> McpTask.withTaskId("task", McpTaskOrigin.fromPersistedState(McpJsonObject.emptyInstance()),
						McpTaskStatus.WORKING, java.time.Instant.EPOCH, java.time.Instant.EPOCH).taskStatusMessage(value));
		for (String malformed : List.of("\uD800", "\uDC00", "\uDC00\uD800",
				"\uD800\uD800", "\uDC00\uDC00", "\uD800x", "x\uDC00",
				"😀\uD800", "\uDC00😀")) {
			for (Consumer<String> factory : factories) {
				IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
						() -> factory.accept(CANARY + malformed + CANARY));
				assertFalse(exception.getMessage().contains(CANARY));
				assertNull(exception.getCause());
			}
		}
	}

	@Test
	void wellFormedTextRemainsExactIncludingControlsSupplementaryCharactersAndEmptyValues() {
		for (String value : List.of("", "\u0000\n\t", "😀𐀀\uDBFF\uDFFF",
				"e\u0301", "é", " العربية 日本語 ")) {
			assertEquals(value, McpTextContent.fromText(value).getText());
			assertEquals(value, McpImplementation.withNameAndVersion(value, value).build().getName());
			assertEquals(value, McpImplementation.withNameAndVersion(value, value).build().getVersion());
			assertEquals(value, McpProgressUpdate.withProgress(1D).message(value).build().getMessage().orElseThrow());
			assertEquals(value, McpTextResourceContents.withUriAndText(RESOURCE, value).build().getText());
			assertEquals(value, McpJsonString.fromValue(value).getValue());
			assertEquals(List.of(value), McpArgumentCompletionResult.fromValues(List.of(value)).getValues());
			assertEquals(value, McpPromptOutput.builder().description(value).build().getDescription().orElseThrow());
			assertEquals(List.of(value), List.copyOf(McpJsonObject.builder().put(value, true).build().getMembers().keySet()));
		}
	}

	private static McpInputRequest inputRequest() {
		return McpInputRequest.fromDeclaration(McpInputRequestDeclaration.fromElicitationForm(McpInputRequirement.REQUIRED),
				McpJsonObject.builder().put("mode", "form").put("message", "Continue")
						.put("requestedSchema", McpJsonObject.builder().put("type", "object").put("properties", McpJsonObject.emptyInstance()).build()).build());
	}

	@Test
	void rejectedCursorAndStateSettersPreserveTheirPriorValues() {
		McpResourcePage.Builder resources = McpResourcePage.builder().nextCursor("cursor😀");
		assertThrows(IllegalArgumentException.class, () -> resources.nextCursor("\uD800"));
		assertEquals("cursor😀", resources.build().getNextCursor().orElseThrow());
		McpSkillPage.Builder skills = McpSkillPage.builder().nextCursor("cursor😀");
		assertThrows(IllegalArgumentException.class, () -> skills.nextCursor("\uDC00"));
		assertEquals("cursor😀", skills.build().getNextCursor().orElseThrow());
		McpInputRequiredResult.Builder state = McpInputRequiredResult.withInputRequest("key😀", inputRequest())
				.applicationRequestState("opaque😀");
		assertThrows(IllegalArgumentException.class, () -> state.applicationRequestState("\uD800"));
		assertThrows(IllegalArgumentException.class, () -> state.addInputRequest("\uDC00", inputRequest()));
		assertEquals("opaque😀", state.build().getApplicationRequestState().orElseThrow());
		assertEquals(java.util.Set.of("key😀"), state.build().getInputRequests().keySet());
	}

	@Test
	void rejectedSettersLeavePreviouslyAcceptedValuesIntact() {
		McpJsonObject.Builder json = McpJsonObject.builder().put("safe", "retained");
		assertThrows(IllegalArgumentException.class, () -> json.put("safe", "\uD800"));
		assertThrows(IllegalArgumentException.class, () -> json.put("\uDC00", true));
		assertEquals(McpJsonObject.builder().put("safe", "retained").build(), json.build());
		McpPromptOutput.Builder prompt = McpPromptOutput.builder().description("retained");
		assertThrows(IllegalArgumentException.class, () -> prompt.description("\uD800"));
		assertEquals("retained", prompt.build().getDescription().orElseThrow());
		McpProgressUpdate.Builder progress = McpProgressUpdate.withProgress(1D).message("retained");
		assertThrows(IllegalArgumentException.class, () -> progress.message("\uD800"));
		assertEquals("retained", progress.build().getMessage().orElseThrow());
		McpImplementation.Builder implementation = McpImplementation.withNameAndVersion("name", "1").title("retained");
		assertThrows(IllegalArgumentException.class, () -> implementation.title("\uDC00"));
		assertEquals("retained", implementation.build().getTitle().orElseThrow());
	}

	@Test
	void constructionDoesNotPretendToValidateEventualWireSize() {
		String oversized = "x".repeat(1_048_577);
		assertSame(oversized, McpTextContent.fromText(oversized).getText());
		assertSame(oversized, McpTextResourceContents.withUriAndText(RESOURCE, oversized).build().getText());
		assertSame(oversized, McpJsonString.fromValue(oversized).getValue());
		assertEquals(786_433, McpImageContent.withDataAndMimeType(new byte[786_433], "image/png").build().getData().length);
	}

	@Test
	void imageAndAudioMimeTypesRejectIncompleteMalformedAndAmbiguousSyntax() {
		for (String mimeType : List.of("", " ", "image", "/png", "image/", "image/png;",
				"image/png;name=", "image/png;name=\"unterminated", "image/png, image/jpeg",
				"image/png;x=1;X=2", "image/png\r\n" + CANARY, "image/" + CANARY + "\uD800")) {
			for (Consumer<String> factory : List.<Consumer<String>>of(
					value -> McpImageContent.withDataAndMimeType(new byte[0], value),
					value -> McpAudioContent.withDataAndMimeType(new byte[0], value))) {
				IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
						() -> factory.accept(mimeType));
				assertFalse(exception.getMessage().contains(CANARY));
				assertNull(exception.getCause());
			}
		}
	}

	@Test
	void mimeTypesPreserveSpellingParametersAndUnregisteredFormatsWithoutSniffingBytes() {
		for (String mimeType : List.of("image/png", "IMAGE/SVG+XML; Name=\"Quoted; value\"",
				" image / x-vendor ; option = \"a\\\"b\" "))
			assertEquals(mimeType, McpImageContent.withDataAndMimeType(new byte[0], mimeType).build().getMimeType());
		for (String mimeType : List.of("audio/mpeg", "AUDIO/X-VENDOR; Codec=\"Custom; codec\"", "application/ogg"))
			assertEquals(mimeType, McpAudioContent.withDataAndMimeType(new byte[0], mimeType).build().getMimeType());
	}
}
