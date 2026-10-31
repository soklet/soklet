package com.soklet.internal.mcp.protocol;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class McpResourceUriNormalizationTests {
	@Test void repeatedSlashSegmentsRemainDistinctInExactAndTemplateRouting() {
		String uri = "catalog://items/a//b";
		assertEquals(uri, McpNormalizedResourceDescriptor.minimal(uri).uri());
		assertNotNull(McpLevelOneUriTemplate.normalizeResourceUriForTemplateMatching(uri));
		var registry = McpServerCapabilityRegistry.fromEndpoint(McpNormalizedEndpoint.withServerInformation(
				McpImplementationMetadata.withNameAndVersion("test", "1"))
				.exactResource(uri).exactResource("catalog://items/a/b").build());
		assertEquals(uri, registry.exactResourceDescriptor(uri).orElseThrow().uri());
		assertEquals("catalog://items/a/b", registry.exactResourceDescriptor("catalog://items/a/b").orElseThrow().uri());
		var template = McpLevelOneUriTemplate.parse("catalog://items/a//{name}");
		assertEquals("b", template.match(uri).orElseThrow().get("name"));
		assertTrue(template.match("catalog://items/a/b").isEmpty());
		for (String invalid : new String[] {"catalog://items/a//../b", "catalog://items/./b", "catalog://items/../b"})
			assertThrows(IllegalArgumentException.class, () -> McpLevelOneUriTemplate.requireValidAbsoluteUri(invalid, "Resource URI"));
	}
	@Test void overlapBudgetExhaustionDoesNotAccuseSpecificTemplatesOfOverlapping() {
		var builder = McpNormalizedEndpoint.withServerInformation(
				McpImplementationMetadata.withNameAndVersion("test", "1"))
				.resourceTemplate("x:{left}" + "a".repeat(252) + "X")
				.resourceTemplate("x:{right}" + "a".repeat(257) + "Y");
		var failure = assertThrows(IllegalArgumentException.class, builder::build);
		assertEquals("Resource URI template overlap validation exceeded its comparison budget.", failure.getMessage());
	}
}
