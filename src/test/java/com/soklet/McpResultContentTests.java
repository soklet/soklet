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
				.content(mutable)
				.content(second)
				.structuredContent(structured)
				.isError(true)
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

		assertEquals(java.util.Set.of(McpRole.USER, McpRole.ASSISTANT),
				annotations.getAudience());
		assertEquals(0.75, annotations.getPriority().orElseThrow());
		assertEquals(lastModified,
				annotations.getLastModified().orElseThrow());
		assertThrows(IllegalArgumentException.class,
				() -> McpContentAnnotations.builder().priority(-0.01));
		assertThrows(IllegalArgumentException.class,
				() -> McpContentAnnotations.builder().priority(1.01));
		assertThrows(IllegalArgumentException.class,
				() -> McpContentAnnotations.builder().priority(Double.NaN));
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
	void resourceOutputRequiresContentAndWholeMillisecondTtl() {
		assertThrows(IllegalStateException.class,
				() -> McpResourceOutput.builder().build());
		assertThrows(IllegalArgumentException.class, () ->
				McpResourceOutput.builder()
						.cacheTimeToLiveOverride(Duration.ofNanos(1)));

		McpTextResourceContents contents = McpTextResourceContents
				.withUriAndText(URI.create("catalog://readme"), "hello")
				.mimeType("text/plain")
				.build();
		McpResourceOutput output = McpResourceOutput.builder()
				.content(contents)
				.cacheTimeToLiveOverride(Duration.ofMillis(250))
				.build();

		assertEquals(List.of(contents), output.getContents());
		assertEquals(Duration.ofMillis(250),
				output.getCacheTimeToLiveOverride().orElseThrow());
	}
}
