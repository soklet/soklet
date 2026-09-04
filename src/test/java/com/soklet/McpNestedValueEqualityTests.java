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
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Tests for values nested by structurally comparable public MCP content.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
class McpNestedValueEqualityTests {
	@Test
	void contentBlocksCompareAnnotationsStructurally() {
		Instant lastModified = Instant.parse("2026-09-03T12:00:00Z");
		McpContentAnnotations firstAnnotations = McpContentAnnotations.builder()
				.audience(McpRole.USER, McpRole.ASSISTANT)
				.priority(0.75)
				.lastModified(lastModified)
				.build();
		McpContentAnnotations equalAnnotations = McpContentAnnotations.builder()
				.audience(McpRole.ASSISTANT, McpRole.USER)
				.priority(0.75)
				.lastModified(lastModified)
				.build();
		McpContentAnnotations differentAnnotations = McpContentAnnotations.builder()
				.audience(McpRole.USER, McpRole.ASSISTANT)
				.priority(0.5)
				.lastModified(lastModified)
				.build();
		McpTextContent first = McpTextContent.withText("text")
				.annotations(firstAnnotations)
				.build();
		McpTextContent equal = McpTextContent.withText("text")
				.annotations(equalAnnotations)
				.build();
		McpTextContent different = McpTextContent.withText("text")
				.annotations(differentAnnotations)
				.build();

		assertEquals(first, equal);
		assertEquals(first.hashCode(), equal.hashCode());
		assertNotEquals(first, different);
	}

	@Test
	void resourceLinksCompareIconsStructurallyAndInOrder() {
		URI source = URI.create("https://example.com/icon.png");
		McpIcon small = icon(source, "32x32");
		McpIcon large = icon(source, "64x64");
		McpResourceLink first = resourceLink(small, large);
		McpResourceLink equal = resourceLink(
				icon(source, "32x32"), icon(source, "64x64"));
		McpResourceLink reordered = resourceLink(
				icon(source, "64x64"), icon(source, "32x32"));

		assertEquals(first, equal);
		assertEquals(first.hashCode(), equal.hashCode());
		assertNotEquals(first, reordered);
	}

	@Test
	void embeddedTextResourcesCompareEveryPropertyStructurally() {
		URI uri = URI.create("catalog://readme");
		McpEmbeddedResource first = McpEmbeddedResource.withResource(
				textResource(uri, "contents", metadata("first", "second")))
				.build();
		McpEmbeddedResource equal = McpEmbeddedResource.withResource(
				textResource(uri, "contents", metadata("second", "first")))
				.build();
		McpEmbeddedResource different = McpEmbeddedResource.withResource(
				textResource(uri, "different", metadata("first", "second")))
				.build();

		assertEquals(first, equal);
		assertEquals(first.hashCode(), equal.hashCode());
		assertNotEquals(first, different);
	}

	@Test
	void embeddedBlobResourcesCompareDefensiveByteContents() {
		URI uri = URI.create("catalog://blob");
		byte[] firstBytes = {1, 2, 3};
		byte[] equalBytes = {1, 2, 3};
		McpEmbeddedResource first = McpEmbeddedResource.withResource(
				blobResource(uri, firstBytes, metadata("first", "second")))
				.build();
		McpEmbeddedResource equal = McpEmbeddedResource.withResource(
				blobResource(uri, equalBytes, metadata("second", "first")))
				.build();
		McpEmbeddedResource different = McpEmbeddedResource.withResource(
				blobResource(uri, new byte[]{1, 2, 4},
						metadata("first", "second")))
				.build();

		firstBytes[0] = 9;
		equalBytes[0] = 8;
		assertEquals(first, equal);
		assertEquals(first.hashCode(), equal.hashCode());
		assertNotEquals(first, different);
		assertNotEquals(first, McpEmbeddedResource.withResource(
				textResource(uri, "\u0001\u0002\u0003",
						metadata("first", "second"))).build());
	}

	private static McpIcon icon(URI source, String size) {
		return McpIcon.withSource(source)
				.mimeType("image/png")
				.sizes(size)
				.theme(McpIconTheme.DARK)
				.build();
	}

	private static McpResourceLink resourceLink(McpIcon first, McpIcon second) {
		return McpResourceLink.withUriAndName(
				URI.create("catalog://linked"), "linked")
				.addIcon(first)
				.addIcon(second)
				.build();
	}

	private static McpTextResourceContents textResource(URI uri, String text,
			McpJsonObject metadata) {
		return McpTextResourceContents.withUriAndText(uri, text)
				.mimeType("text/plain")
				.metadata(metadata)
				.build();
	}

	private static McpBlobResourceContents blobResource(URI uri, byte[] data,
			McpJsonObject metadata) {
		return McpBlobResourceContents.withUriAndData(uri, data)
				.mimeType("application/octet-stream")
				.metadata(metadata)
				.build();
	}

	private static McpJsonObject metadata(String first, String second) {
		return McpJsonObject.builder()
				.put(first, first)
				.put(second, second)
				.build();
	}
}
