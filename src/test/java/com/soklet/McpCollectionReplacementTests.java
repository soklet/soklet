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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whole-property replacement contracts for the approved MCP collection APIs.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
class McpCollectionReplacementTests {

	@Test
	void endpointCollectionsReplaceAndRetainRegistrationOrder() {
		McpEndpoint.Builder builder = endpointBuilder();
		assertReplacement(builder::toolRegistrations,
				() -> builder.build().getToolRegistrations(),
				operationBuilder("first").build(), operationBuilder("second").build(), false);
		assertReplacement(builder::promptRegistrations,
				() -> builder.build().getPromptRegistrations(),
				promptBuilder("first").build(), promptBuilder("second").build(), false);
		assertReplacement(builder::resourceRegistrations,
				() -> builder.build().getResourceRegistrations(),
				exactBuilder("first").build(), templateBuilder("second").build(), false);
	}

	@Test
	void resourcePageReplacesDescriptorsAndPreservesDuplicates() {
		McpResourcePage.Builder builder = McpResourcePage.builder();
		assertReplacement(builder::resourceDescriptors,
				() -> builder.build().getResourceDescriptors(),
				McpResourceDescriptor.withUriAndName(URI.create("test://first"), "first").build(),
				McpResourceDescriptor.withUriAndName(URI.create("test://second"), "second").build(), true);
	}

	@Test
	void iconSizesReplaceAtomicallyAndPreserveDuplicates() {
		McpIcon.Builder builder = McpIcon.withSource(URI.create("https://example.test/icon"));
		assertReplacement(builder::sizes, () -> builder.build().getSizes(), "48x48", "any", true);
	}

	@Test
	void outputCollectionsReplaceAndConvenienceFactoriesRemainIntact() {
		McpToolOutput.Builder tool = McpToolOutput.builder();
		McpTextContent first = McpTextContent.fromText("first");
		McpTextContent second = McpTextContent.fromText("second");
		assertReplacement(tool::content, () -> tool.build().getContent(), first, second, true);
		List<McpTextContent> subtypeList = new ArrayList<>(List.of(first, second));
		McpToolOutput subtypeOutput = tool.content(subtypeList).build();
		subtypeList.clear();
		assertEquals(List.of(first, second), subtypeOutput.getContent());
		assertEquals(subtypeOutput, subtypeOutput.toBuilder().build());
		assertEquals(List.of(first), McpToolOutput.fromText("first").getContent());

		McpPromptOutput.Builder prompt = McpPromptOutput.builder();
		McpPromptMessage user = McpPromptMessage.fromUserText("first");
		McpPromptMessage assistant = McpPromptMessage.fromAssistantText("second");
		assertReplacement(prompt::messages, () -> prompt.build().getMessages(), user, assistant, true);
		assertEquals(List.of(user, assistant), McpPromptOutput.fromMessages(user, assistant).getMessages());
	}

	@Test
	void operationToolCollectionsReplaceAndToolAnnotationsUseTheAlignedName() {
		McpToolRegistration.OperationBuilder<McpJsonObject> builder = operationBuilder("operation");
		assertReplacement(builder::icons, () -> builder.build().getIcons(), icon("first"), icon("second"), true);
		assertReplacement(builder::inputRequestDeclarations,
				() -> builder.build().getInputRequestDeclarations(), form(), url(), true);
		McpToolAnnotations annotations = McpToolAnnotations.builder().readOnlyHint(true).build();
		assertSame(builder, builder.toolAnnotations(annotations));
		assertSame(annotations, builder.build().getToolAnnotations().orElseThrow());
		assertThrows(NullPointerException.class, () -> builder.toolAnnotations(null));
		assertSame(annotations, builder.build().getToolAnnotations().orElseThrow());
	}

	@Test
	void completeToolIconsReplaceAndToolAnnotationsUseTheAlignedName() {
		McpToolRegistration.CompleteBuilder<Arguments> builder = McpToolRegistration
				.withName("complete").argumentAndOutputTypes(Arguments.class, Result.class)
				.handler((request, arguments, features) -> new Result("result"));
		assertReplacement(builder::icons, () -> builder.build().getIcons(), icon("first"), icon("second"), true);
		McpToolAnnotations annotations = McpToolAnnotations.builder().readOnlyHint(true).build();
		assertSame(builder, builder.toolAnnotations(annotations));
		assertSame(annotations, builder.build().getToolAnnotations().orElseThrow());
		assertThrows(NullPointerException.class, () -> builder.toolAnnotations(null));
		assertSame(annotations, builder.build().getToolAnnotations().orElseThrow());
	}

	@Test
	void promptCollectionsReplaceWithArgumentsInDeclaredOrder() {
		McpPromptRegistration.Builder builder = promptBuilder("prompt");
		assertReplacement(builder::icons, () -> builder.build().getIcons(), icon("first"), icon("second"), true);
		assertReplacement(builder::arguments, () -> builder.build().getArguments(),
				McpPromptArgumentDeclaration.withName("first").build(),
				McpPromptArgumentDeclaration.withName("second").build(), false);
		assertReplacement(builder::inputRequestDeclarations,
				() -> builder.build().getInputRequestDeclarations(), form(), url(), true);
	}

	@Test
	void exactAndTemplateResourceCollectionsReplaceWithoutRenamingContentAnnotations() {
		McpResourceRegistration.ExactBuilder exact = exactBuilder("exact");
		McpResourceRegistration.TemplateBuilder template = templateBuilder("template");
		assertReplacement(exact::icons, () -> exact.build().getIcons(), icon("first"), icon("second"), true);
		assertReplacement(template::icons, () -> template.build().getIcons(), icon("first"), icon("second"), true);
		assertReplacement(exact::inputRequestDeclarations,
				() -> exact.build().getInputRequestDeclarations(), form(), url(), true);
		assertReplacement(template::inputRequestDeclarations,
				() -> template.build().getInputRequestDeclarations(), form(), url(), true);
		McpContentAnnotations annotations = McpContentAnnotations.builder().priority(0.5).build();
		assertSame(annotations, exact.annotations(annotations).build().getAnnotations().orElseThrow());
		assertSame(annotations, template.annotations(annotations).build().getAnnotations().orElseThrow());
	}

	@Test
	void duplicateNameAndAddressValidationRemainsABuildInvariant() {
		McpToolRegistration<?> tool = operationBuilder("tool").build();
		McpPromptRegistration prompt = promptBuilder("prompt").build();
		McpResourceRegistration resource = exactBuilder("resource").build();
		McpEndpoint.Builder duplicateTools = endpointBuilder().toolRegistrations(List.of(tool, tool));
		McpEndpoint.Builder duplicatePrompts = endpointBuilder().promptRegistrations(List.of(prompt, prompt));
		McpEndpoint.Builder duplicateResources = endpointBuilder().resourceRegistrations(List.of(resource, resource));
		assertThrows(IllegalStateException.class, duplicateTools::build);
		assertThrows(IllegalStateException.class, duplicatePrompts::build);
		assertThrows(IllegalStateException.class, duplicateResources::build);
		McpPromptArgumentDeclaration argument = McpPromptArgumentDeclaration.withName("argument").build();
		McpPromptRegistration.Builder duplicateArguments = promptBuilder("prompt").arguments(List.of(argument, argument));
		assertThrows(IllegalStateException.class, duplicateArguments::build);
	}

	@Test
	void replacedAdditiveMethodsAndBarePluralGettersHaveNoAliases() throws Exception {
		Set<String> removed = Set.of("addTool", "addTools", "addPrompt", "addPrompts",
				"addResource", "addResources", "addIcon", "addArgument",
				"addInputRequestDeclaration", "addInputRequestDeclarations",
				"addContent", "addContents", "addMessage", "addMessages");
		for (Class<?> type : List.of(McpEndpoint.Builder.class, McpResourcePage.Builder.class,
				McpToolOutput.Builder.class, McpPromptOutput.Builder.class,
				McpToolRegistration.OperationBuilder.class, McpToolRegistration.CompleteBuilder.class,
				McpPromptRegistration.Builder.class, McpResourceRegistration.ExactBuilder.class,
				McpResourceRegistration.TemplateBuilder.class)) {
			assertTrue(Arrays.stream(type.getMethods()).noneMatch(method -> removed.contains(method.getName())), type.getName());
		}
		for (String name : List.of("getTools", "getPrompts", "getResources"))
			assertThrows(NoSuchMethodException.class, () -> McpEndpoint.class.getMethod(name));
		assertThrows(NoSuchMethodException.class, () -> McpResourcePage.class.getMethod("getResources"));
		assertThrows(NoSuchMethodException.class, () -> McpToolRegistration.class.getMethod("getAnnotations"));
		for (Class<?> type : List.of(McpToolRegistration.OperationBuilder.class, McpToolRegistration.CompleteBuilder.class))
			assertThrows(NoSuchMethodException.class, () -> type.getMethod("annotations", McpToolAnnotations.class));
		assertFalse(McpIcon.Builder.class.getMethod("sizes", List.class).isVarArgs());
		assertThrows(NoSuchMethodException.class, () -> McpIcon.Builder.class.getMethod("sizes", String[].class));
	}

	private static <T> void assertReplacement(Consumer<List<T>> setter, Supplier<List<T>> getter,
			T first, T second, boolean duplicatesAllowed) {
		List<T> ordered = duplicatesAllowed ? List.of(second, first, second) : List.of(second, first);
		List<T> mutable = new ArrayList<>(ordered);
		setter.accept(mutable);
		mutable.clear();
		List<T> built = getter.get();
		assertEquals(ordered, built);
		assertThrows(UnsupportedOperationException.class, () -> built.add(first));
		setter.accept(List.of(first));
		setter.accept(List.of(first));
		assertEquals(List.of(first), getter.get());
		assertEquals(ordered, built, "Previously built values must not change with the builder");
		for (List<T> invalid : List.of(Arrays.asList(null, second), Arrays.asList(second, null))) {
			assertThrows(NullPointerException.class, () -> setter.accept(invalid));
			assertEquals(List.of(first), getter.get(), "Failed replacement must preserve the prior value");
		}
		setter.accept(List.of());
		assertTrue(getter.get().isEmpty());
		setter.accept(List.of(second));
		setter.accept(null);
		assertTrue(getter.get().isEmpty());
		setter.accept(ordered);
		assertEquals(ordered, getter.get());
	}

	private static McpEndpoint.Builder endpointBuilder() {
		return McpEndpoint.withPath("/mcp", McpImplementation.withNameAndVersion("test", "1").build());
	}

	private static McpToolRegistration.OperationBuilder<McpJsonObject> operationBuilder(String name) {
		return McpToolRegistration.withName(name).jsonObjectArguments().handler((request, arguments, features) -> {
			throw new AssertionError("Collection replacement must not invoke application handlers");
		});
	}

	private static McpPromptRegistration.Builder promptBuilder(String name) {
		return McpPromptRegistration.withName(name).handler((request, prompt, features) -> {
			throw new AssertionError("Collection replacement must not invoke application handlers");
		});
	}

	private static McpResourceRegistration.ExactBuilder exactBuilder(String name) {
		return McpResourceRegistration.withUriAndName(URI.create("test://" + name), name)
				.handler((request, resource, features) -> {
					throw new AssertionError("Collection replacement must not invoke application handlers");
				});
	}

	private static McpResourceRegistration.TemplateBuilder templateBuilder(String name) {
		return McpResourceRegistration.withUriTemplateAndName("test://" + name + "/{id}", name)
				.handler((request, resource, features) -> {
					throw new AssertionError("Collection replacement must not invoke application handlers");
				});
	}

	private static McpIcon icon(String name) {
		return McpIcon.withSource(URI.create("https://example.test/" + name)).build();
	}

	private static McpInputRequestDeclaration form() {
		return McpInputRequestDeclaration.fromElicitationForm(McpInputRequirement.CONDITIONAL);
	}

	private static McpInputRequestDeclaration url() {
		return McpInputRequestDeclaration.fromElicitationUrl(McpInputRequirement.CONDITIONAL);
	}

	public record Arguments(String value) {}
	public record Result(String value) {}
}
