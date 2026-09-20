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

import static com.soklet.McpAppResourceMetadata.Permission.CAMERA;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for typed and raw Apps metadata on resource-content values. */
@ThreadSafe
class McpAppResourceAttachmentTests {
	private static final URI UI_URI = URI.create("ui://orders/dashboard");
	private static final String APP_MIME_TYPE = "text/html;profile=mcp-app";

	@Test
	void ordinaryContentsKeepAbsentTypedMetadataAndUnrelatedExtensions() {
		URI ordinaryUri = URI.create("catalog://orders/summary");
		McpJsonObject raw = extensionMetadata();
		for (McpResourceContents contents : List.of(
				McpTextResourceContents.withUriAndText(ordinaryUri, "summary")
						.metadata(raw).build(),
				McpBlobResourceContents.withUriAndData(ordinaryUri, new byte[]{1, 2})
						.metadata(raw).build())) {
			assertTrue(contents.getAppResourceMetadata().isEmpty());
			assertTrue(contents.getMimeType().isEmpty());
			assertEquals(raw, contents.getMetadata());
			assertSame(contents, McpEmbeddedResource.withResource(contents).build().getResource());
		}
	}

	@Test
	void typedMetadataRemainsSeparateFromRawAndMayPrecedeMimeType() {
		McpAppResourceMetadata typed = McpAppResourceMetadata.builder()
				.permissions(Set.of(CAMERA)).prefersBorder(false).build();
		McpJsonObject raw = extensionMetadata();
		McpTextResourceContents.Builder text = textBuilder();
		assertSame(text, text.appResourceMetadata(typed));
		assertSame(text, text.metadata(raw));
		McpBlobResourceContents.Builder blob = blobBuilder().metadata(raw);
		assertSame(blob, blob.appResourceMetadata(typed));
		for (McpResourceContents contents : List.of(text.mimeType(APP_MIME_TYPE).build(),
				blob.mimeType(APP_MIME_TYPE).build())) {
			assertSame(typed, contents.getAppResourceMetadata().orElseThrow());
			assertEquals(Boolean.FALSE,
					contents.getAppResourceMetadata().orElseThrow().getPrefersBorder().orElseThrow());
			assertEquals(raw, contents.getMetadata());
			McpJsonObject rawUi = (McpJsonObject) contents.getMetadata().find("ui").orElseThrow();
			assertFalse(rawUi.getMembers().containsKey("permissions"));
			assertFalse(rawUi.getMembers().containsKey("prefersBorder"));
		}
	}

	@Test
	void rawOnlyMetadataIsValidatedButDoesNotPopulateTypedGetter() {
		McpJsonObject raw = rawUi(McpJsonObject.builder()
				.put("csp", McpJsonObject.builder().put("resourceDomains",
						McpJsonArray.builder().add("https://cdn.example.com").build()).build())
				.put("permissions", McpJsonObject.builder()
						.put("camera", McpJsonObject.builder().put("vendor", "retained").build()).build())
				.put("domain", "orders.example.com").put("prefersBorder", false)
				.put("extension", "retained").build());
		for (McpResourceContents contents : List.of(
				textBuilder().metadata(raw).mimeType(APP_MIME_TYPE).build(),
				blobBuilder().metadata(raw).mimeType(APP_MIME_TYPE).build())) {
			assertTrue(contents.getAppResourceMetadata().isEmpty());
			assertEquals(raw, contents.getMetadata());
		}
	}

	@Test
	void everyOwnedRawFieldConflictsWithTypedMetadataInEitherOrderAtomically() {
		McpAppResourceMetadata typed = McpAppResourceMetadata.builder().build();
		McpJsonObject unrelated = extensionMetadata();
		for (McpJsonObject raw : recognizedRawMetadata()) {
			McpTextResourceContents.Builder typedText = textBuilder().mimeType(APP_MIME_TYPE)
					.metadata(unrelated).appResourceMetadata(typed);
			McpBlobResourceContents.Builder typedBlob = blobBuilder().mimeType(APP_MIME_TYPE)
					.metadata(unrelated).appResourceMetadata(typed);
			McpTextResourceContents textBefore = typedText.build();
			McpBlobResourceContents blobBefore = typedBlob.build();
			assertThrows(IllegalArgumentException.class, () -> typedText.metadata(raw));
			assertThrows(IllegalArgumentException.class, () -> typedBlob.metadata(raw));
			assertEquals(textBefore, typedText.build());
			assertEquals(blobBefore, typedBlob.build());

			McpTextResourceContents.Builder rawText = textBuilder().mimeType(APP_MIME_TYPE).metadata(raw);
			McpBlobResourceContents.Builder rawBlob = blobBuilder().mimeType(APP_MIME_TYPE).metadata(raw);
			assertThrows(IllegalArgumentException.class, () -> rawText.appResourceMetadata(typed));
			assertThrows(IllegalArgumentException.class, () -> rawBlob.appResourceMetadata(typed));
			assertTrue(rawText.build().getAppResourceMetadata().isEmpty());
			assertTrue(rawBlob.build().getAppResourceMetadata().isEmpty());
			assertEquals(raw, rawText.build().getMetadata());
			assertEquals(raw, rawBlob.build().getMetadata());
		}
	}

	@Test
	void malformedRawUiAndKnownFieldsAreRejectedWithoutChangingBuilderState() {
		List<McpJsonObject> malformed = List.of(
				McpJsonObject.builder().put("ui", "private-ui-sentinel").build(),
				rawUi(McpJsonObject.builder().put("csp", "private-csp-sentinel").build()),
				rawUi(McpJsonObject.builder().put("permissions", McpJsonArray.emptyInstance()).build()),
				rawUi(McpJsonObject.builder().put("permissions", McpJsonObject.builder()
						.put("private-permission-sentinel", McpJsonObject.emptyInstance()).build()).build()),
				rawUi(McpJsonObject.builder().put("permissions", McpJsonObject.builder()
						.put("camera", true).build()).build()),
				rawUi(McpJsonObject.builder().put("domain", "Private-Domain-Sentinel.example").build()),
				rawUi(McpJsonObject.builder().put("prefersBorder", "private-border-sentinel").build()),
				rawUi(McpJsonObject.builder().put("csp", McpJsonObject.builder()
						.put("connectDomains", McpJsonArray.builder()
								.add("https://example.com").add("https://example.com").build()).build()).build()));
		McpTextResourceContents.Builder text = textBuilder().metadata(extensionMetadata());
		McpBlobResourceContents.Builder blob = blobBuilder().metadata(extensionMetadata());
		McpTextResourceContents beforeText = text.build();
		McpBlobResourceContents beforeBlob = blob.build();
		for (McpJsonObject raw : malformed) {
			assertThrows(IllegalArgumentException.class, () -> text.metadata(raw));
			assertThrows(IllegalArgumentException.class, () -> blob.metadata(raw));
			assertEquals(beforeText, text.build());
			assertEquals(beforeBlob, blob.build());
		}
	}

	@Test
	void nullAttachmentAndRawSettersPreservePreviousState() {
		McpAppResourceMetadata typed = McpAppResourceMetadata.builder().prefersBorder(false).build();
		McpTextResourceContents.Builder text = textBuilder().mimeType(APP_MIME_TYPE)
				.metadata(extensionMetadata()).appResourceMetadata(typed);
		McpBlobResourceContents.Builder blob = blobBuilder().mimeType(APP_MIME_TYPE)
				.metadata(extensionMetadata()).appResourceMetadata(typed);
		McpTextResourceContents beforeText = text.build();
		McpBlobResourceContents beforeBlob = blob.build();
		assertThrows(NullPointerException.class, () -> text.appResourceMetadata(null));
		assertThrows(NullPointerException.class, () -> blob.appResourceMetadata(null));
		assertThrows(NullPointerException.class, () -> text.metadata(null));
		assertThrows(NullPointerException.class, () -> blob.metadata(null));
		assertEquals(beforeText, text.build());
		assertEquals(beforeBlob, blob.build());
	}

	@Test
	void typedMetadataRequiresExactAppsMimeTypeAndBuilderMayRecoverAfterFailedBuild() {
		McpAppResourceMetadata typed = McpAppResourceMetadata.builder().build();
		McpTextResourceContents.Builder text = textBuilder().appResourceMetadata(typed);
		McpBlobResourceContents.Builder blob = blobBuilder().appResourceMetadata(typed);
		assertThrows(IllegalArgumentException.class, text::build);
		assertThrows(IllegalArgumentException.class, blob::build);
		for (String invalidMimeType : List.of("text/html", "text/plain;profile=mcp-app",
				"text/html;profile=MCP-APP", "text/html;profile=mcp-app;charset=utf-8",
				"text/html;profile=mcp-app;PROFILE=mcp-app", "text/html;profile=\"mcp-app",
				"text/html;profile=mcp-app\n")) {
			text.mimeType(invalidMimeType);
			blob.mimeType(invalidMimeType);
			assertThrows(IllegalArgumentException.class, text::build);
			assertThrows(IllegalArgumentException.class, blob::build);
		}
		assertEquals(typed, text.mimeType(APP_MIME_TYPE).build().getAppResourceMetadata().orElseThrow());
		assertEquals(typed, blob.mimeType(APP_MIME_TYPE).build().getAppResourceMetadata().orElseThrow());
	}

	@Test
	void equivalentMimeSpellingIsAcceptedAndPreservedForBothContentKinds() {
		McpAppResourceMetadata typed = McpAppResourceMetadata.builder().build();
		for (String mimeType : List.of(APP_MIME_TYPE, "TEXT/HTML; PROFILE=\"mcp-app\"",
				" text / html ; profile = mcp-app ")) {
			for (McpResourceContents contents : List.of(
					textBuilder().mimeType(mimeType).appResourceMetadata(typed).build(),
					blobBuilder().appResourceMetadata(typed).mimeType(mimeType).build())) {
				assertEquals(mimeType, contents.getMimeType().orElseThrow());
				assertEquals(typed, contents.getAppResourceMetadata().orElseThrow());
			}
		}
	}

	@Test
	void rawOnlyKnownFieldsRequireUiUriAndAppsMimeIncludingEmptyPolicies() {
		for (McpJsonObject raw : recognizedRawMetadata()) {
			assertThrows(IllegalArgumentException.class, () -> textBuilder().metadata(raw).build());
			assertThrows(IllegalArgumentException.class, () -> blobBuilder().metadata(raw).build());
			assertThrows(IllegalArgumentException.class,
					() -> textBuilder().mimeType("text/html").metadata(raw).build());
			assertThrows(IllegalArgumentException.class,
					() -> blobBuilder().mimeType("text/html").metadata(raw).build());
			for (URI uri : List.of(URI.create("https://example.com/dashboard"),
					URI.create("ui:dashboard"), URI.create("ui:/dashboard"))) {
				assertThrows(IllegalArgumentException.class, () -> McpTextResourceContents
						.withUriAndText(uri, "html").mimeType(APP_MIME_TYPE).metadata(raw).build());
				assertThrows(IllegalArgumentException.class, () -> McpBlobResourceContents
						.withUriAndData(uri, new byte[]{1}).mimeType(APP_MIME_TYPE).metadata(raw).build());
			}
		}
	}

	@Test
	void typedMetadataRequiresConcreteUiUriAndRetainsExistingAsciiNormalizationChecks() {
		McpAppResourceMetadata typed = McpAppResourceMetadata.builder().build();
		for (URI uri : List.of(URI.create("https://example.com/dashboard"),
				URI.create("ui:dashboard"), URI.create("ui:/dashboard"), URI.create("dashboard"),
				URI.create("ui://orders/path/../dashboard"), URI.create("ui://orders/café"))) {
			assertThrows(IllegalArgumentException.class, () -> McpTextResourceContents
					.withUriAndText(uri, "html").mimeType(APP_MIME_TYPE).appResourceMetadata(typed).build());
			assertThrows(IllegalArgumentException.class, () -> McpBlobResourceContents
					.withUriAndData(uri, new byte[]{1}).mimeType(APP_MIME_TYPE).appResourceMetadata(typed).build());
		}
	}

	@Test
	void textRebuildAndNestedResourceOutputPreserveTypedSecurityMetadata() {
		McpAppResourceMetadata original = McpAppResourceMetadata.builder()
				.domain("orders.example.com").permissions(Set.of(CAMERA)).build();
		McpTextResourceContents.Builder builder = textBuilder().mimeType(APP_MIME_TYPE)
				.appResourceMetadata(original).metadata(extensionMetadata());
		McpTextResourceContents first = builder.build();
		McpTextResourceContents copy = McpTextResourceContents.withUriAndText(first.getUri(), first.getText())
				.mimeType(first.getMimeType().orElseThrow()).metadata(first.getMetadata())
				.appResourceMetadata(first.getAppResourceMetadata().orElseThrow()).build();
		assertEqualContents(first, copy);
		McpTextResourceContents changed = builder.appResourceMetadata(
				McpAppResourceMetadata.builder().domain("other.example.com").build()).build();
		assertEquals(original, first.getAppResourceMetadata().orElseThrow());
		assertDifferentContents(first, changed);
	}

	@Test
	void blobRebuildRetainsSecurityMetadataAndDefensiveByteCopies() {
		byte[] source = new byte[]{1, 2, 3};
		McpAppResourceMetadata original = McpAppResourceMetadata.builder()
				.contentSecurityPolicy(McpAppResourceMetadata.ContentSecurityPolicy.builder()
						.resourceDomains(Set.of("https://cdn.example.com")).build()).build();
		McpBlobResourceContents.Builder builder = McpBlobResourceContents.withUriAndData(UI_URI, source)
				.mimeType(APP_MIME_TYPE).appResourceMetadata(original).metadata(extensionMetadata());
		source[0] = 9;
		McpBlobResourceContents first = builder.build();
		byte[] copiedData = first.getData();
		copiedData[1] = 9;
		assertArrayEquals(new byte[]{1, 2, 3}, first.getData());
		McpBlobResourceContents copy = McpBlobResourceContents.withUriAndData(first.getUri(), first.getData())
				.mimeType(first.getMimeType().orElseThrow()).metadata(first.getMetadata())
				.appResourceMetadata(first.getAppResourceMetadata().orElseThrow()).build();
		assertEqualContents(first, copy);
		McpBlobResourceContents changed = builder.appResourceMetadata(
				McpAppResourceMetadata.builder().build()).build();
		assertEquals(original, first.getAppResourceMetadata().orElseThrow());
		assertDifferentContents(first, changed);
	}

	@Test
	void metadataPresenceAndEverySecurityPropertyParticipateInValueSemantics() {
		McpTextResourceContents textWithout = textBuilder().mimeType(APP_MIME_TYPE).build();
		McpBlobResourceContents blobWithout = blobBuilder().mimeType(APP_MIME_TYPE).build();
		McpAppResourceMetadata empty = McpAppResourceMetadata.builder().build();
		McpTextResourceContents textEmpty = textBuilder().mimeType(APP_MIME_TYPE).appResourceMetadata(empty).build();
		McpBlobResourceContents blobEmpty = blobBuilder().mimeType(APP_MIME_TYPE).appResourceMetadata(empty).build();
		assertDifferentContents(textWithout, textEmpty);
		assertDifferentContents(blobWithout, blobEmpty);
		for (McpAppResourceMetadata changed : List.of(
				McpAppResourceMetadata.builder().prefersBorder(false).build(),
				McpAppResourceMetadata.builder().domain("orders.example.com").build(),
				McpAppResourceMetadata.builder().permissions(Set.of(CAMERA)).build(),
				McpAppResourceMetadata.builder().contentSecurityPolicy(
						McpAppResourceMetadata.ContentSecurityPolicy.builder().build()).build())) {
			assertDifferentContents(textEmpty, textBuilder().mimeType(APP_MIME_TYPE)
					.appResourceMetadata(changed).build());
			assertDifferentContents(blobEmpty, blobBuilder().mimeType(APP_MIME_TYPE)
					.appResourceMetadata(changed).build());
		}
	}

	@Test
	void embeddedResourcesRejectTypedMetadataIncludingExplicitEmptyMetadata() {
		for (McpAppResourceMetadata typed : List.of(McpAppResourceMetadata.builder().build(),
				McpAppResourceMetadata.builder().permissions(Set.of(CAMERA)).build(),
				McpAppResourceMetadata.builder().prefersBorder(false).build())) {
			for (McpResourceContents contents : List.of(
					textBuilder().mimeType(APP_MIME_TYPE).appResourceMetadata(typed).build(),
					blobBuilder().mimeType(APP_MIME_TYPE).appResourceMetadata(typed).build())) {
				McpEmbeddedResource.Builder builder = McpEmbeddedResource.withResource(contents);
				assertThrows(IllegalArgumentException.class, builder::build);
			}
		}
	}

	@Test
	void embeddedResourcesRejectRecognizedRawMetadataButAllowUnknownUiExtensions() {
		for (McpJsonObject raw : recognizedRawMetadata()) {
			for (McpResourceContents contents : List.of(
					textBuilder().mimeType(APP_MIME_TYPE).metadata(raw).build(),
					blobBuilder().mimeType(APP_MIME_TYPE).metadata(raw).build()))
				assertThrows(IllegalArgumentException.class,
						() -> McpEmbeddedResource.withResource(contents).build());
		}
		for (McpJsonObject raw : List.of(rawUi(McpJsonObject.emptyInstance()), extensionMetadata())) {
			McpTextResourceContents text = textBuilder().mimeType(APP_MIME_TYPE).metadata(raw).build();
			McpBlobResourceContents blob = blobBuilder().mimeType(APP_MIME_TYPE).metadata(raw).build();
			assertEquals(raw, McpEmbeddedResource.withResource(text).build().getResource().getMetadata());
			assertEquals(raw, McpEmbeddedResource.withResource(blob).build().getResource().getMetadata());
		}
	}

	@Test
	void attachmentValidationMessagesDoNotExposeResourceOrMetadataValues() {
		String sentinel = "private-app-sentinel";
		McpAppResourceMetadata typed = McpAppResourceMetadata.builder().domain(sentinel + ".example").build();
		URI wrongUri = URI.create("https://example.com/" + sentinel);
		List<IllegalArgumentException> errors = List.of(
				assertThrows(IllegalArgumentException.class, () -> McpTextResourceContents
						.withUriAndText(wrongUri, sentinel).mimeType(APP_MIME_TYPE)
						.appResourceMetadata(typed).build()),
				assertThrows(IllegalArgumentException.class, () -> blobBuilder().mimeType(sentinel)
						.appResourceMetadata(typed).build()),
				assertThrows(IllegalArgumentException.class, () -> textBuilder()
						.appResourceMetadata(typed).metadata(rawUi(McpJsonObject.builder()
								.put("domain", sentinel + ".example").build()))),
				assertThrows(IllegalArgumentException.class, () -> McpEmbeddedResource.withResource(
						textBuilder().mimeType(APP_MIME_TYPE).appResourceMetadata(typed).build()).build()));
		for (IllegalArgumentException error : errors)
			assertFalse(error.getMessage().contains(sentinel));
	}

	private static McpTextResourceContents.Builder textBuilder() {
		return McpTextResourceContents.withUriAndText(UI_URI, "<html>orders</html>");
	}

	private static McpBlobResourceContents.Builder blobBuilder() {
		return McpBlobResourceContents.withUriAndData(UI_URI, new byte[]{1, 2, 3});
	}

	private static McpJsonObject rawUi(McpJsonObject ui) {
		return McpJsonObject.builder().put("ui", ui).build();
	}

	private static McpJsonObject extensionMetadata() {
		return McpJsonObject.builder().put("application-extension", "retained")
				.put("ui", McpJsonObject.builder().put("unknown-extension", "retained").build()).build();
	}

	private static List<McpJsonObject> recognizedRawMetadata() {
		return List.of(
				rawUi(McpJsonObject.builder().put("csp", McpJsonObject.emptyInstance()).build()),
				rawUi(McpJsonObject.builder().put("permissions", McpJsonObject.emptyInstance()).build()),
				rawUi(McpJsonObject.builder().put("domain", "orders.example.com").build()),
				rawUi(McpJsonObject.builder().put("prefersBorder", false).build()));
	}

	private static void assertEqualContents(McpResourceContents first, McpResourceContents second) {
		assertEquals(first, second);
		assertEquals(first.hashCode(), second.hashCode());
		assertTrue(McpContentValueSupport.resourceContentsEqual(first, second));
		assertEquals(McpContentValueSupport.resourceContentsHashCode(first),
				McpContentValueSupport.resourceContentsHashCode(second));
		assertEquals(McpResourceOutput.fromContent(first), McpResourceOutput.fromContent(second));
		assertEquals(McpResourceOutput.fromContent(first).hashCode(),
				McpResourceOutput.fromContent(second).hashCode());
	}

	private static void assertDifferentContents(McpResourceContents first, McpResourceContents second) {
		assertNotEquals(first, second);
		assertNotEquals(first.hashCode(), second.hashCode());
		assertFalse(McpContentValueSupport.resourceContentsEqual(first, second));
		assertNotEquals(McpContentValueSupport.resourceContentsHashCode(first),
				McpContentValueSupport.resourceContentsHashCode(second));
		assertNotEquals(McpResourceOutput.fromContent(first), McpResourceOutput.fromContent(second));
		assertNotEquals(McpResourceOutput.fromContent(first).hashCode(),
				McpResourceOutput.fromContent(second).hashCode());
	}
}
