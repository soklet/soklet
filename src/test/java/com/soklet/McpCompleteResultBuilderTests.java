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

import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Contracts for immutable complete-result construction and the default hook. */
public class McpCompleteResultBuilderTests {
	@Test
	void factoryBuildersPreserveEveryPayloadFamilyAndStartWithEmptyMetadata() {
		McpToolOutput tool = McpToolOutput.fromText("tool");
		McpPromptOutput prompt = McpPromptOutput.builder().description("prompt").build();
		McpResourceOutput resource = resourceOutput();
		assertEquals(McpCompleteResult.fromToolOutput(tool),
				McpCompleteResult.withToolOutput(tool).build());
		assertEquals(McpCompleteResult.fromPromptOutput(prompt),
				McpCompleteResult.withPromptOutput(prompt).build());
		assertEquals(McpCompleteResult.fromResourceOutput(resource),
				McpCompleteResult.withResourceOutput(resource).build());
		for (McpCompleteResult result : List.of(
				McpCompleteResult.withToolOutput(tool).build(),
				McpCompleteResult.withPromptOutput(prompt).build(),
				McpCompleteResult.withResourceOutput(resource).build()))
			assertEquals(McpJsonObject.emptyInstance(), result.getMetadata());
	}

	@Test
	void toBuilderPreservesAllFieldsAndCreatesIndependentSnapshots() {
		McpToolOutput payload = McpToolOutput.builder()
				.content(java.util.List.of(McpTextContent.fromText("first"), McpTextContent.fromText("second")))
				.structuredContent(McpJsonObject.builder().put("value", "original").build())
				.error(true).build();
		McpJsonObject metadata = McpJsonObject.builder().put("com.example/view", "secret").build();
		McpCompleteResult original = McpCompleteResult.withToolOutput(payload)
				.metadata(metadata).build();
		McpCompleteResult.Builder copy = original.toBuilder();
		McpCompleteResult unchanged = copy.build();
		assertNotSame(original, unchanged);
		assertEquals(original, unchanged);
		assertEquals(original.hashCode(), unchanged.hashCode());
		assertSame(payload, unchanged.getPayload());
		assertSame(metadata, unchanged.getMetadata());
		McpCompleteResult redacted = copy.payload(McpToolOutput.fromText("redacted"))
				.metadata(McpJsonObject.emptyInstance()).build();
		assertNotEquals(original, redacted);
		assertEquals(original, unchanged);
		assertSame(payload, original.getPayload());
		assertSame(metadata, original.getMetadata());
		assertEquals(McpJsonObject.emptyInstance(), redacted.getMetadata());
		assertEquals(McpToolOutput.fromText("redacted"), redacted.getPayload());
	}

	@Test
	void payloadReplacementSupportsAllCompleteFamiliesWithoutChangingMetadata() {
		McpJsonObject metadata = McpJsonObject.builder().put("revision", "one").build();
		McpCompleteResult.Builder builder = McpCompleteResult.withToolOutput(
				McpToolOutput.fromText("initial")).metadata(metadata);
		for (McpCompletePayload payload : List.of(McpPromptOutput.builder().build(),
				resourceOutput(), McpToolOutput.fromErrorText("error"))) {
			assertSame(builder, builder.payload(payload));
			McpCompleteResult result = builder.build();
			assertSame(payload, result.getPayload());
			assertSame(metadata, result.getMetadata());
		}
	}

	@Test
	void nullAndReservedMetadataFailuresDoNotMutateTheBuilder() {
		assertThrows(NullPointerException.class, () -> McpCompleteResult.withToolOutput(null));
		assertThrows(NullPointerException.class, () -> McpCompleteResult.withPromptOutput(null));
		assertThrows(NullPointerException.class, () -> McpCompleteResult.withResourceOutput(null));
		McpCompleteResult original = McpCompleteResult.withToolOutput(McpToolOutput.fromText("safe"))
				.metadata(McpJsonObject.builder().put("com.example/key", "value").build()).build();
		McpCompleteResult.Builder builder = original.toBuilder();
		assertThrows(NullPointerException.class, () -> builder.payload(null));
		assertThrows(NullPointerException.class, () -> builder.metadata(null));
		assertThrows(IllegalArgumentException.class, () -> builder.metadata(
				McpJsonObject.builder().put("io.modelcontextprotocol/private", "forbidden").build()));
		assertEquals(original, builder.build());
	}

	@Test
	void resultConstructorsStayPrivateAndWithMetadataAliasIsRemoved() {
		assertTrue(Arrays.stream(McpCompleteResult.class.getDeclaredConstructors())
				.allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers())));
		assertTrue(Arrays.stream(McpCompleteResult.Builder.class.getDeclaredConstructors())
				.allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers())));
		assertThrows(NoSuchMethodException.class,
				() -> McpCompleteResult.class.getMethod("withMetadata", McpJsonObject.class));
		assertThrows(ClassNotFoundException.class,
				() -> Class.forName("com.soklet.McpToolOutputSanitizer"));
	}

	@Test
	void nonSanitizingSingletonReturnsTheIdenticalCompleteResult() throws Exception {
		McpRequestContext request = (McpRequestContext) Proxy.newProxyInstance(
				McpRequestContext.class.getClassLoader(), new Class<?>[]{McpRequestContext.class},
				(proxy, method, arguments) -> null);
		McpCompleteResult result = McpCompleteResult.withToolOutput(McpToolOutput.fromText("text"))
				.metadata(McpJsonObject.builder().put("com.example/private", "value").build()).build();
		McpToolResultSanitizer sanitizer = McpToolResultSanitizer.nonSanitizingInstance();
		assertSame(sanitizer, McpToolResultSanitizer.nonSanitizingInstance());
		assertSame(result, sanitizer.sanitize(request, "tool", McpJsonObject.emptyInstance(), result));
		assertThrows(NullPointerException.class,
				() -> sanitizer.sanitize(request, "tool", McpJsonObject.emptyInstance(), null));
	}

	private static McpResourceOutput resourceOutput() {
		return McpResourceOutput.withContent(McpTextResourceContents.withUriAndText(
				URI.create("resource://example/view"), "content").build()).build();
	}
}
