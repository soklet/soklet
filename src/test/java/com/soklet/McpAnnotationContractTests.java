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

import com.soklet.annotation.McpHeader;
import com.soklet.annotation.McpResourceList;
import com.soklet.annotation.McpMayRequestInput;
import com.soklet.annotation.McpPrompt;
import com.soklet.annotation.McpPromptArgument;
import com.soklet.annotation.McpResource;
import com.soklet.annotation.McpResourceUriParameter;
import com.soklet.annotation.McpServerEndpoint;
import com.soklet.annotation.McpTool;
import com.soklet.annotation.McpToolArgument;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.annotation.concurrent.ThreadSafe;
import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.AnnotatedArrayType;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.net.URI;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Contract coverage for the reviewed annotated MCP API verticals.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class McpAnnotationContractTests {
	@Test
	public void annotationsHaveReviewedTargetsAndRuntimeRetention() {
		assertAnnotationContract(McpServerEndpoint.class, ElementType.TYPE);
		assertAnnotationContract(McpTool.class, ElementType.METHOD);
		assertAnnotationContract(McpToolArgument.class, ElementType.PARAMETER,
				ElementType.RECORD_COMPONENT);
		assertAnnotationContract(McpHeader.class, ElementType.PARAMETER,
				ElementType.RECORD_COMPONENT);
		assertAnnotationContract(McpPrompt.class, ElementType.METHOD);
		assertAnnotationContract(McpPromptArgument.class, ElementType.PARAMETER);
		assertAnnotationContract(McpResource.class, ElementType.METHOD);
		assertAnnotationContract(McpResourceUriParameter.class,
				ElementType.PARAMETER);
		assertAnnotationContract(McpResourceList.class, ElementType.METHOD);
		assertAnnotationContract(McpMayRequestInput.class);
	}

	@Test
	public void annotationElementsAreLimitedToReviewedMcpVerticals() {
		Assertions.assertEquals(Set.of("path", "name", "version", "title",
				"description", "websiteUrl", "instructions", "toolRateLimiter",
				"resourceListCacheTtlMs", "resourceListCacheScope",
				"resourceTemplateListCacheTtlMs",
				"resourceTemplateListCacheScope"),
				elementNames(McpServerEndpoint.class));
		Assertions.assertEquals(Set.of("name", "title", "description",
				"rateLimiter", "mirrorStructuredContentAsText",
				"mayRequestInput", "requestStateMode"),
				elementNames(McpTool.class));
		Assertions.assertEquals(Set.of("name", "title", "description"),
				elementNames(McpToolArgument.class));
		Assertions.assertEquals(Set.of("value"), elementNames(McpHeader.class));
		Assertions.assertEquals(Set.of("name", "title", "description",
				"mayRequestInput", "requestStateMode"),
				elementNames(McpPrompt.class));
		Assertions.assertEquals(Set.of("name", "title", "description"),
				elementNames(McpPromptArgument.class));
		Assertions.assertEquals(Set.of("uri", "name", "title", "description",
				"mimeType", "size", "cacheTtlMs", "cacheScope",
				"mayRequestInput", "requestStateMode"),
				elementNames(McpResource.class));
		Assertions.assertEquals(Set.of("value"),
				elementNames(McpResourceUriParameter.class));
		Assertions.assertEquals(Set.of(), elementNames(McpResourceList.class));
	}

	@Test
	public void annotationsExposeReviewedDefaultsAtRuntime() throws Exception {
		McpServerEndpoint endpoint = MinimalEndpoint.class
				.getAnnotation(McpServerEndpoint.class);
		Assertions.assertEquals("/mcp", endpoint.path());
		Assertions.assertEquals("catalog", endpoint.name());
		Assertions.assertEquals("3.6.0", endpoint.version());
		Assertions.assertEquals("", endpoint.title());
		Assertions.assertEquals("", endpoint.description());
		Assertions.assertEquals("", endpoint.websiteUrl());
		Assertions.assertEquals("", endpoint.instructions());
		Assertions.assertEquals("", endpoint.toolRateLimiter());
		Assertions.assertEquals(0, endpoint.resourceListCacheTtlMs());
		Assertions.assertEquals(McpCacheScope.PRIVATE,
				endpoint.resourceListCacheScope());
		Assertions.assertEquals(0,
				endpoint.resourceTemplateListCacheTtlMs());
		Assertions.assertEquals(McpCacheScope.PRIVATE,
				endpoint.resourceTemplateListCacheScope());

		Method method = MinimalEndpoint.class.getDeclaredMethod("search",
				String.class);
		McpTool tool = method.getAnnotation(McpTool.class);
		Assertions.assertEquals("search", tool.name());
		Assertions.assertEquals("", tool.title());
		Assertions.assertEquals("", tool.description());
		Assertions.assertEquals("", tool.rateLimiter());
		Assertions.assertTrue(tool.mirrorStructuredContentAsText());
		Assertions.assertEquals(0, tool.mayRequestInput().length);
		Assertions.assertEquals(McpRequestStateMode.NONE,
				tool.requestStateMode());

		Parameter parameter = method.getParameters()[0];
		McpToolArgument argument = parameter
				.getAnnotation(McpToolArgument.class);
		Assertions.assertEquals("", argument.name());
		Assertions.assertEquals("", argument.title());
		Assertions.assertEquals("", argument.description());
		Assertions.assertEquals("Tenant",
				parameter.getAnnotation(McpHeader.class).value());

		McpToolArgument recordComponent = AnnotatedRecord.class
				.getRecordComponents()[0].getAnnotation(McpToolArgument.class);
		Assertions.assertEquals("publishedName", recordComponent.name());
		Assertions.assertEquals("Published title", recordComponent.title());
		Assertions.assertEquals("Published description",
				recordComponent.description());
		Assertions.assertEquals("Region", AnnotatedRecord.class
				.getRecordComponents()[0].getAnnotation(McpHeader.class).value());

		Method promptMethod = MinimalEndpoint.class.getDeclaredMethod("compose",
				String.class, Optional.class);
		McpPrompt prompt = promptMethod.getAnnotation(McpPrompt.class);
		Assertions.assertEquals("compose", prompt.name());
		Assertions.assertEquals("", prompt.title());
		Assertions.assertEquals("", prompt.description());
		Assertions.assertEquals(0, prompt.mayRequestInput().length);
		Assertions.assertEquals(McpRequestStateMode.NONE,
				prompt.requestStateMode());
		McpPromptArgument promptArgument = promptMethod.getParameters()[0]
				.getAnnotation(McpPromptArgument.class);
		Assertions.assertEquals("subject", promptArgument.name());
		Assertions.assertEquals("Subject", promptArgument.title());
		Assertions.assertEquals("Subject to discuss",
				promptArgument.description());
		McpPromptArgument optionalArgument = promptMethod.getParameters()[1]
				.getAnnotation(McpPromptArgument.class);
		Assertions.assertEquals("", optionalArgument.name());
		Assertions.assertEquals("", optionalArgument.title());
		Assertions.assertEquals("", optionalArgument.description());

		Method resourceMethod = MinimalEndpoint.class.getDeclaredMethod(
				"readResource", String.class);
		McpResource resource = resourceMethod.getAnnotation(McpResource.class);
		Assertions.assertEquals("test://catalog/{identifier}", resource.uri());
		Assertions.assertEquals("catalog-entry", resource.name());
		Assertions.assertEquals("", resource.title());
		Assertions.assertEquals("", resource.description());
		Assertions.assertEquals("", resource.mimeType());
		Assertions.assertEquals(-1, resource.size());
		Assertions.assertEquals(0, resource.cacheTtlMs());
		Assertions.assertEquals(McpCacheScope.PRIVATE, resource.cacheScope());
		Assertions.assertEquals(0, resource.mayRequestInput().length);
		Assertions.assertEquals(McpRequestStateMode.NONE,
				resource.requestStateMode());
		McpResourceUriParameter uriParameter = resourceMethod.getParameters()[0]
				.getAnnotation(McpResourceUriParameter.class);
		Assertions.assertEquals("", uriParameter.value());
		Assertions.assertNotNull(MinimalEndpoint.class.getDeclaredMethod(
				"listResources", McpResourceListContext.class)
				.getAnnotation(McpResourceList.class));

		McpTool multiRoundTripTool = MinimalEndpoint.class
				.getDeclaredMethod("deleteItem")
				.getAnnotation(McpTool.class);
		Assertions.assertEquals(McpRequestStateMode.FRAMEWORK_PROTECTED,
				multiRoundTripTool.requestStateMode());
		Assertions.assertEquals(1, multiRoundTripTool.mayRequestInput().length);
		McpMayRequestInput declaration =
				multiRoundTripTool.mayRequestInput()[0];
		Assertions.assertEquals("elicitation/create", declaration.method());
		Assertions.assertArrayEquals(
				new McpClientCapability[] {
						McpClientCapability.ELICITATION_FORM},
				declaration.capabilities());
		Assertions.assertEquals(McpInputRequirement.REQUIRED,
				declaration.requirement());
	}

	@Test
	public void multiRoundTripAnnotationArraysHaveExplicitJSpecifyNullness()
			throws Exception {
		assertNonNullArray(McpMayRequestInput.class
				.getDeclaredMethod("capabilities").getAnnotatedReturnType());
		assertNonNullArray(McpTool.class
				.getDeclaredMethod("mayRequestInput").getAnnotatedReturnType());
		assertNonNullArray(McpPrompt.class
				.getDeclaredMethod("mayRequestInput").getAnnotatedReturnType());
		assertNonNullArray(McpResource.class
				.getDeclaredMethod("mayRequestInput").getAnnotatedReturnType());
	}

	private static void assertAnnotationContract(
			Class<? extends Annotation> annotationType,
			ElementType... expectedTargets) {
		Target target = annotationType.getAnnotation(Target.class);
		Retention retention = annotationType.getAnnotation(Retention.class);

		Assertions.assertNotNull(target);
		Assertions.assertEquals(Set.of(expectedTargets), Set.of(target.value()));
		Assertions.assertNotNull(retention);
		Assertions.assertEquals(RetentionPolicy.RUNTIME, retention.value());
	}

	private static Set<String> elementNames(
			Class<? extends Annotation> annotationType) {
		return Arrays.stream(annotationType.getDeclaredMethods())
				.map(Method::getName)
				.collect(Collectors.toUnmodifiableSet());
	}

	private static void assertNonNullArray(AnnotatedType annotatedType) {
		Assertions.assertTrue(annotatedType.isAnnotationPresent(NonNull.class));
		AnnotatedArrayType arrayType = Assertions.assertInstanceOf(
				AnnotatedArrayType.class, annotatedType);
		Assertions.assertTrue(arrayType.getAnnotatedGenericComponentType()
				.isAnnotationPresent(NonNull.class));
	}

	@McpServerEndpoint(path = "/mcp", name = "catalog", version = "3.6.0")
	public static final class MinimalEndpoint {
		@McpTool(name = "search")
		public SearchResult search(
				@McpToolArgument @McpHeader("Tenant") String query) {
			return new SearchResult(query);
		}

		@McpPrompt(name = "compose")
		public McpPromptOutput compose(
				@McpPromptArgument(name = "subject", title = "Subject",
						description = "Subject to discuss") String subject,
				@McpPromptArgument Optional<String> tone) {
			return McpPromptOutput.fromMessages(
					McpPromptMessage.fromUserContent(
							McpTextContent.fromText(subject)));
		}

		@McpResource(uri = "test://catalog/{identifier}",
				name = "catalog-entry")
		public McpResourceOutput readResource(
				@McpResourceUriParameter String identifier) {
			return McpResourceOutput.builder()
					.content(McpTextResourceContents.withUriAndText(
							URI.create("test://catalog/" + identifier), identifier)
							.build())
					.build();
		}

		@McpResourceList
		public McpResourcePage listResources(McpResourceListContext list) {
			return McpResourcePage.builder()
					.resources(list.getRegisteredResourceDescriptors())
					.build();
		}

		@McpTool(name = "delete", mayRequestInput = @McpMayRequestInput(
				method = "elicitation/create",
				capabilities = McpClientCapability.ELICITATION_FORM,
				requirement = McpInputRequirement.REQUIRED),
				requestStateMode = McpRequestStateMode.FRAMEWORK_PROTECTED)
		public McpCompleteResult deleteItem() {
			return McpCompleteResult.fromToolText("not invoked");
		}
	}

	/** Structured result used by the annotation fixture. */
	public record SearchResult(String query) {}

	public record AnnotatedRecord(
			@McpToolArgument(name = "publishedName",
					title = "Published title",
					description = "Published description")
			@McpHeader("Region") String javaName) {}
}
