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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for immutable MCP complete results and content.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
class McpResultContentTests {
	@Test
	void toolOutputPreservesContentInsertionOrderAndStructuredContent() {
		McpTextContent first = McpTextContent.fromText("first");
		McpTextContent second = McpTextContent.fromText("second");
		List<McpContentBlock> mutable = new ArrayList<>(List.of(first));
		McpJsonObject structured =
				McpJsonObject.builder().put("ok", true).build();

		McpToolOutput output = McpToolOutput.builder()
				.addContents(mutable)
				.addContent(second)
				.structuredContent(structured)
				.error(true)
				.build();
		mutable.clear();

		assertEquals(List.of(first, second), output.getContent());
		assertSame(structured, output.getStructuredContent().orElseThrow());
		assertTrue(output.isError());
		assertThrows(UnsupportedOperationException.class,
				() -> output.getContent().add(first));
	}

	@Test
	void binaryContentAndResourcesDefensivelyCopyArrays() {
		byte[] imageBytes = {1, 2, 3};
		byte[] resourceBytes = {4, 5, 6};
		McpImageContent image = McpImageContent
				.withDataAndMimeType(imageBytes, "image/png")
				.build();
		McpBlobResourceContents resource = McpBlobResourceContents
				.withUriAndData(URI.create("catalog://image"), resourceBytes)
				.mimeType("application/octet-stream")
				.build();

		imageBytes[0] = 9;
		resourceBytes[0] = 9;
		assertArrayEquals(new byte[]{1, 2, 3}, image.getData());
		assertArrayEquals(new byte[]{4, 5, 6}, resource.getData());
		byte[] returnedImage = image.getData();
		byte[] returnedResource = resource.getData();
		returnedImage[1] = 9;
		returnedResource[1] = 9;
		assertArrayEquals(new byte[]{1, 2, 3}, image.getData());
		assertArrayEquals(new byte[]{4, 5, 6}, resource.getData());
	}

	@Test
	void contentAnnotationsValidatePriorityAndPreserveOmission() {
		Instant lastModified = Instant.parse("2026-08-05T12:00:00Z");
		McpContentAnnotations annotations = McpContentAnnotations.builder()
				.audience(McpRole.USER, McpRole.ASSISTANT, McpRole.USER)
				.priority(0.75)
				.lastModified(lastModified)
				.build();

		assertEquals(List.of(McpRole.USER, McpRole.ASSISTANT),
				new ArrayList<>(annotations.getAudience()));
		assertThrows(UnsupportedOperationException.class,
				() -> annotations.getAudience().add(McpRole.USER));
		assertEquals(0.75, annotations.getPriority().orElseThrow());
		assertEquals(lastModified,
				annotations.getLastModified().orElseThrow());
		assertThrows(IllegalArgumentException.class,
				() -> McpContentAnnotations.builder().priority(-0.01));
		assertThrows(IllegalArgumentException.class,
				() -> McpContentAnnotations.builder().priority(1.01));
		assertThrows(IllegalArgumentException.class,
				() -> McpContentAnnotations.builder().priority(Double.NaN));
		assertThrows(NullPointerException.class,
				() -> McpContentAnnotations.builder().priority(null));
	}

	@Test
	void contentBlocksExposeAnnotationsAndMetadataThroughCommonContract() {
		McpContentAnnotations annotations = annotations(0.75);
		McpJsonObject metadata = metadata("shared");
		List<McpContentBlock> blocks = List.of(
				McpTextContent.withText("text")
						.annotations(annotations).metadata(metadata).build(),
				McpImageContent.withDataAndMimeType(
						new byte[]{1}, "image/png")
						.annotations(annotations).metadata(metadata).build(),
				McpAudioContent.withDataAndMimeType(
						new byte[]{2}, "audio/mpeg")
						.annotations(annotations).metadata(metadata).build(),
				McpEmbeddedResource.withResource(
						McpTextResourceContents.withUriAndText(
								URI.create("catalog://embedded"), "body").build())
						.annotations(annotations).metadata(metadata).build(),
				McpResourceLink.withUriAndName(
						URI.create("catalog://linked"), "linked")
						.annotations(annotations).metadata(metadata).build());

		for (McpContentBlock block : blocks) {
			assertSame(annotations, block.getAnnotations().orElseThrow());
			assertSame(metadata, block.getMetadata());
		}
	}

	@Test
	void contentBlocksHaveDeepStructuralEqualityAndMatchingHashes() {
		McpContentAnnotations firstAnnotations = annotations(0.75);
		McpContentAnnotations secondAnnotations = annotations(0.75);
		McpJsonObject firstMetadata = McpJsonObject.builder()
				.put("owner", "catalog").put("revision", 4).build();
		McpJsonObject secondMetadata = McpJsonObject.builder()
				.put("revision", 4).put("owner", "catalog").build();

		assertStructurallyEqual(
				McpTextContent.withText("text").annotations(firstAnnotations)
						.metadata(firstMetadata).build(),
				McpTextContent.withText("text").annotations(secondAnnotations)
						.metadata(secondMetadata).build());
		assertStructurallyEqual(
				McpImageContent.withDataAndMimeType(
						new byte[]{1, 2, 3}, "image/png")
						.annotations(firstAnnotations).metadata(firstMetadata).build(),
				McpImageContent.withDataAndMimeType(
						new byte[]{1, 2, 3}, "image/png")
						.annotations(secondAnnotations).metadata(secondMetadata).build());
		assertStructurallyEqual(
				McpAudioContent.withDataAndMimeType(
						new byte[]{4, 5, 6}, "audio/mpeg")
						.annotations(firstAnnotations).metadata(firstMetadata).build(),
				McpAudioContent.withDataAndMimeType(
						new byte[]{4, 5, 6}, "audio/mpeg")
						.annotations(secondAnnotations).metadata(secondMetadata).build());

		McpTextResourceContents firstResource = McpTextResourceContents
				.withUriAndText(URI.create("catalog://embedded"), "body")
				.mimeType("text/plain").metadata(firstMetadata).build();
		McpTextResourceContents secondResource = McpTextResourceContents
				.withUriAndText(URI.create("catalog://embedded"), "body")
				.mimeType("text/plain").metadata(secondMetadata).build();
		assertStructurallyEqual(
				McpEmbeddedResource.withResource(firstResource)
						.annotations(firstAnnotations).metadata(firstMetadata).build(),
				McpEmbeddedResource.withResource(secondResource)
						.annotations(secondAnnotations).metadata(secondMetadata).build());

		McpIcon firstIcon = McpIcon.withSource(
				URI.create("https://catalog.example/icon.png"))
				.mimeType("image/png").sizes("32x32", "64x64")
				.theme(McpIconTheme.DARK).build();
		McpIcon secondIcon = McpIcon.withSource(
				URI.create("https://catalog.example/icon.png"))
				.mimeType("image/png").sizes("32x32", "64x64")
				.theme(McpIconTheme.DARK).build();
		McpResourceLink firstLink = McpResourceLink.withUriAndName(
				URI.create("catalog://linked"), "linked")
				.title("Linked").description("Description")
				.mimeType("text/plain").addIcon(firstIcon)
				.annotations(firstAnnotations).sizeInBytes(12L)
				.metadata(firstMetadata).build();
		McpResourceLink secondLink = McpResourceLink.withUriAndName(
				URI.create("catalog://linked"), "linked")
				.title("Linked").description("Description")
				.mimeType("text/plain").addIcon(secondIcon)
				.annotations(secondAnnotations).sizeInBytes(12L)
				.metadata(secondMetadata).build();
		assertStructurallyEqual(firstLink, secondLink);

		assertNotEquals(
				McpImageContent.withDataAndMimeType(
						new byte[]{1, 2, 3}, "image/png").build(),
				McpImageContent.withDataAndMimeType(
						new byte[]{1, 2, 4}, "image/png").build());
		assertNotEquals(firstLink, McpResourceLink.withUriAndName(
				URI.create("catalog://linked"), "linked").sizeInBytes(13L).build());
	}

	@Test
	void toolAnnotationBooleansPreserveAbsentAndExplicitFalse() {
		McpToolAnnotations annotations = McpToolAnnotations.builder()
				.readOnlyHint(false)
				.idempotentHint(true)
				.build();

		assertFalse(annotations.getReadOnlyHint().orElseThrow());
		assertTrue(annotations.getIdempotentHint().orElseThrow());
		assertTrue(annotations.getDestructiveHint().isEmpty());
		assertTrue(annotations.getOpenWorldHint().isEmpty());
		assertThrows(NullPointerException.class,
				() -> McpToolAnnotations.builder().readOnlyHint(null));
		assertThrows(NullPointerException.class,
				() -> McpToolAnnotations.builder().destructiveHint(null));
		assertThrows(NullPointerException.class,
				() -> McpToolAnnotations.builder().idempotentHint(null));
		assertThrows(NullPointerException.class,
				() -> McpToolAnnotations.builder().openWorldHint(null));
		assertThrows(NullPointerException.class,
				() -> McpToolOutput.builder().error(null));
	}

	@Test
	void completeResultCopiesWithMetadataWithoutChangingPayload() {
		McpToolOutput output = McpToolOutput.fromText("done");
		McpCompleteResult original =
				McpCompleteResult.fromToolOutput(output);
		McpJsonObject metadata =
				McpJsonObject.builder().put("revision", "7").build();
		McpCompleteResult copied = original.withMetadata(metadata);

		assertSame(output, original.getPayload());
		assertSame(output, copied.getPayload());
		assertTrue(original.getMetadata().getMembers().isEmpty());
		assertSame(metadata, copied.getMetadata());
	}

	@Test
	void resourceOutputEntrypointRequiresContentAndWholeMillisecondTtl() {
		McpTextResourceContents contents = McpTextResourceContents
				.withUriAndText(URI.create("catalog://readme"), "hello")
				.mimeType("text/plain")
				.build();
		assertThrows(NullPointerException.class,
				() -> McpResourceOutput.withContent(null));
		assertThrows(IllegalArgumentException.class, () ->
				McpResourceOutput.withContent(contents)
						.cacheTimeToLiveOverride(Duration.ofNanos(1)));

		McpResourceOutput output = McpResourceOutput.withContent(contents)
				.cacheTimeToLiveOverride(Duration.ofMillis(250))
				.build();

		assertEquals(List.of(contents), output.getContents());
		assertEquals(Duration.ofMillis(250),
				output.getCacheTimeToLiveOverride().orElseThrow());
	}

	@Test
	void resourceValuesRejectRelativeOrUnnormalizedUrisAndBlankScalars() {
		assertThrows(IllegalArgumentException.class, () ->
				McpTextResourceContents.withUriAndText(
						URI.create("relative"), "text"));
		assertThrows(IllegalArgumentException.class, () ->
				McpBlobResourceContents.withUriAndData(
						URI.create("catalog://items/a/../b"), new byte[0]));
		assertThrows(IllegalArgumentException.class, () ->
				McpTextResourceContents.withUriAndText(
						URI.create("catalog://items/café"), "text"));
		assertEquals(URI.create("catalog://items/%FF"),
				McpTextResourceContents.withUriAndText(
						URI.create("catalog://items/%FF"), "text")
						.build().getUri());
		assertThrows(IllegalArgumentException.class, () ->
				McpTextResourceContents.withUriAndText(
						URI.create("catalog://readme"), "text")
						.mimeType(" "));
		assertThrows(IllegalArgumentException.class, () ->
				McpBlobResourceContents.withUriAndData(
						URI.create("catalog://blob"), new byte[0])
						.mimeType(" "));
		assertThrows(IllegalArgumentException.class, () ->
				McpResourceLink.withUriAndName(
						URI.create("catalog://linked"), " "));
		assertThrows(IllegalArgumentException.class, () ->
				McpResourceLink.withUriAndName(
						URI.create("catalog://linked"), "linked")
						.mimeType(" "));
	}

	private static McpContentAnnotations annotations(Double priority) {
		return McpContentAnnotations.builder()
				.audience(McpRole.USER, McpRole.ASSISTANT)
				.priority(priority)
				.lastModified(Instant.parse("2026-08-05T12:00:00Z"))
				.build();
	}

	private static McpJsonObject metadata(String owner) {
		return McpJsonObject.builder().put("owner", owner).build();
	}

	private static void assertStructurallyEqual(Object first, Object second) {
		assertEquals(first, second);
		assertEquals(second, first);
		assertEquals(first.hashCode(), second.hashCode());
	}
}
