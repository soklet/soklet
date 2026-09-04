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

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.annotation.concurrent.ThreadSafe;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.google.testing.compile.CompilationSubject.assertThat;

/**
 * End-to-end coverage for typed annotated MCP input-request declarations.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class McpTypedInputRequestAnnotationTests {
	@Test
	@SuppressWarnings({"rawtypes", "unchecked"})
	void generatedRegistrationsDeriveMethodsAndBaseCapabilities(
			@TempDir Path temporaryDirectory) throws Exception {
		Path sourceDirectory = temporaryDirectory.resolve("source/example");
		Path classDirectory = temporaryDirectory.resolve("classes");
		Path generatedDirectory = temporaryDirectory.resolve("generated");
		Files.createDirectories(sourceDirectory);
		Files.createDirectories(classDirectory);
		Files.createDirectories(generatedDirectory);
		Path source = sourceDirectory.resolve("TypedInputEndpoint.java");
		Files.writeString(source, """
				package example;

				import com.soklet.McpClientCapability;
				import com.soklet.McpInputRequirement;
				import com.soklet.McpInputRequiredResult;
				import com.soklet.McpInputRequestType;
				import com.soklet.McpJsonString;
				import com.soklet.McpOperationResult;
				import com.soklet.McpRequestStateMode;
				import com.soklet.annotation.McpMayRequestInput;
				import com.soklet.annotation.McpPrompt;
				import com.soklet.annotation.McpResource;
				import com.soklet.annotation.McpServerEndpoint;
				import com.soklet.annotation.McpTool;

				@McpServerEndpoint(path = "/mcp", name = "typed-input", version = "1")
				public final class TypedInputEndpoint {
				  @McpTool(name = "sample", mayRequestInput = @McpMayRequestInput(
				      type = McpInputRequestType.SAMPLING,
				      samplingCapabilities = {
				          McpClientCapability.SAMPLING_CONTEXT,
				          McpClientCapability.SAMPLING_TOOLS},
				      requirement = McpInputRequirement.CONDITIONAL),
				      requestStateMode = McpRequestStateMode.FRAMEWORK_PROTECTED)
				  public McpOperationResult sample() {
				    return McpInputRequiredResult.withFrameworkRequestState(
				        McpJsonString.fromValue("sample-state")).build();
				  }

				  @McpTool(name = "state-only-tool",
				      requestStateMode = McpRequestStateMode.APPLICATION_PROTECTED)
				  public McpOperationResult stateOnlyTool() { return null; }

				  @McpPrompt(name = "form", mayRequestInput = @McpMayRequestInput(
				      type = McpInputRequestType.ELICITATION_FORM,
				      requirement = McpInputRequirement.REQUIRED),
				      requestStateMode = McpRequestStateMode.APPLICATION_PROTECTED)
				  public McpOperationResult form() { return null; }

				  @McpPrompt(name = "url", mayRequestInput = @McpMayRequestInput(
				      type = McpInputRequestType.ELICITATION_URL,
				      requirement = McpInputRequirement.CONDITIONAL))
				  public McpOperationResult url() { return null; }

				  @McpPrompt(name = "state-only-prompt",
				      requestStateMode = McpRequestStateMode.FRAMEWORK_PROTECTED)
				  public McpOperationResult stateOnlyPrompt() { return null; }

				  @McpResource(uri = "test://roots", name = "roots",
				      mayRequestInput = @McpMayRequestInput(
				          type = McpInputRequestType.ROOTS,
				          requirement = McpInputRequirement.CONDITIONAL),
				      requestStateMode = McpRequestStateMode.FRAMEWORK_PROTECTED)
				  public McpOperationResult roots() { return null; }

				  @McpResource(uri = "test://state-only", name = "state-only-resource",
				      requestStateMode = McpRequestStateMode.APPLICATION_PROTECTED)
				  public McpOperationResult stateOnlyResource() { return null; }
				}
				""", StandardCharsets.UTF_8);

		compile(source, classDirectory, generatedDirectory);
		String generatedSource;
		try (var paths = Files.walk(generatedDirectory)) {
			Path generatedProvider = paths
					.filter(path -> path.getFileName().toString()
							.startsWith("SokletMcpEndpointProvider_"))
					.findFirst().orElseThrow();
			generatedSource = Files.readString(generatedProvider,
					StandardCharsets.UTF_8);
		}
		Assertions.assertTrue(generatedSource.contains(
				".argumentType(Tool0Arguments.class)"), generatedSource);
		Assertions.assertTrue(generatedSource.contains(
				"toolBuilder0.requestStateMode(com.soklet.McpRequestStateMode.FRAMEWORK_PROTECTED)"),
				generatedSource);
		Assertions.assertTrue(generatedSource.contains(
				"promptBuilder0.requestStateMode(com.soklet.McpRequestStateMode.APPLICATION_PROTECTED)"),
				generatedSource);
		Assertions.assertTrue(generatedSource.contains(
				"resourceBuilder0.requestStateMode(com.soklet.McpRequestStateMode.FRAMEWORK_PROTECTED)"),
				generatedSource);
		for (String factory : List.of("fromSampling", "fromElicitationForm",
				"fromElicitationUrl", "fromRoots"))
			Assertions.assertTrue(generatedSource.contains(
					"McpInputRequestDeclaration." + factory + "("),
					generatedSource);

		try (URLClassLoader classLoader = new URLClassLoader(
				new URL[] {classDirectory.toUri().toURL()},
				McpTypedInputRequestAnnotationTests.class.getClassLoader())) {
			Class<?> endpointClass = Class.forName("example.TypedInputEndpoint",
					false, classLoader);
			McpEndpoint endpoint = McpEndpointRegistry.fromClasses(endpointClass)
					.getEndpoints().get(0);
			Map<String, McpToolRegistration<?>> tools = endpoint.getTools().stream()
					.collect(Collectors.toMap(McpToolRegistration::getName,
							Function.identity()));
			McpToolRegistration<?> tool = tools.get("sample");
			Assertions.assertTrue(tool.getOutputSchema().isEmpty());
			Assertions.assertEquals(McpRequestStateMode.FRAMEWORK_PROTECTED,
					tool.getRequestStateMode());
			assertDeclaration(tool.getInputRequestDeclarations().get(0),
					McpInputRequestType.SAMPLING, "sampling/createMessage",
					Set.of(McpClientCapability.SAMPLING,
							McpClientCapability.SAMPLING_CONTEXT,
							McpClientCapability.SAMPLING_TOOLS),
					McpInputRequirement.CONDITIONAL);
			McpToolRegistration<?> stateOnlyTool = tools.get("state-only-tool");
			Assertions.assertEquals(McpRequestStateMode.APPLICATION_PROTECTED,
					stateOnlyTool.getRequestStateMode());
			Assertions.assertTrue(stateOnlyTool.getInputRequestDeclarations()
					.isEmpty());

			Assertions.assertTrue(tool.getArgumentType() instanceof Class<?>);
			Object convertedArguments = ((Class<?>) tool.getArgumentType())
					.getConstructor().newInstance();
			McpToolArguments<Object> arguments = new McpToolArguments<>() {
				@Override
				public Object getConvertedArguments() {
					return convertedArguments;
				}

				@Override
				public McpJsonObject getRawArguments() {
					return McpJsonObject.emptyInstance();
				}
			};
			McpRequestContext requestContext = (McpRequestContext)
					Proxy.newProxyInstance(McpRequestContext.class.getClassLoader(),
							new Class<?>[] {McpRequestContext.class},
							(proxy, method, methodArguments) -> null);
			McpToolHandler<Object> handler = (McpToolHandler) tool.getHandler();
			McpInputRequiredResult result = Assertions.assertInstanceOf(
					McpInputRequiredResult.class,
					handler.handle(requestContext, arguments,
							McpInvocationFeatures.fromFeatures(Map.of())));
			McpJsonString state = Assertions.assertInstanceOf(McpJsonString.class,
					result.getFrameworkRequestState().orElseThrow());
			Assertions.assertEquals("sample-state", state.getValue());

			Map<String, McpPromptRegistration> prompts = endpoint.getPrompts()
					.stream().collect(Collectors.toMap(
							McpPromptRegistration::getName, Function.identity()));
			assertDeclaration(prompts.get("form")
					.getInputRequestDeclarations().get(0),
					McpInputRequestType.ELICITATION_FORM,
					"elicitation/create",
					Set.of(McpClientCapability.ELICITATION_FORM),
					McpInputRequirement.REQUIRED);
			Assertions.assertEquals(McpRequestStateMode.APPLICATION_PROTECTED,
					prompts.get("form").getRequestStateMode());
			assertDeclaration(prompts.get("url")
					.getInputRequestDeclarations().get(0),
					McpInputRequestType.ELICITATION_URL,
					"elicitation/create",
					Set.of(McpClientCapability.ELICITATION_URL),
					McpInputRequirement.CONDITIONAL);
			Assertions.assertEquals(McpRequestStateMode.FRAMEWORK_PROTECTED,
					prompts.get("state-only-prompt").getRequestStateMode());
			Assertions.assertTrue(prompts.get("state-only-prompt")
					.getInputRequestDeclarations().isEmpty());

			Map<String, McpResourceRegistration> resources = endpoint.getResources()
					.stream().collect(Collectors.toMap(
							McpResourceRegistration::getName,
							Function.identity()));
			assertDeclaration(resources.get("roots")
					.getInputRequestDeclarations().get(0),
					McpInputRequestType.ROOTS, "roots/list",
					Set.of(McpClientCapability.ROOTS),
					McpInputRequirement.CONDITIONAL);
			Assertions.assertEquals(McpRequestStateMode.FRAMEWORK_PROTECTED,
					resources.get("roots").getRequestStateMode());
			Assertions.assertEquals(McpRequestStateMode.APPLICATION_PROTECTED,
					resources.get("state-only-resource").getRequestStateMode());
			Assertions.assertTrue(resources.get("state-only-resource")
					.getInputRequestDeclarations().isEmpty());
		}
	}

	@Test
	void rejectsSamplingCapabilitiesForNonSamplingTypesAndForeignCapabilities() {
		JavaFileObject source = JavaFileObjects.forSourceString(
				"example.InvalidTypedInputEndpoint", """
						package example;

						import com.soklet.McpClientCapability;
						import com.soklet.McpInputRequirement;
						import com.soklet.McpInputRequestType;
						import com.soklet.McpOperationResult;
						import com.soklet.annotation.McpMayRequestInput;
						import com.soklet.annotation.McpPrompt;
						import com.soklet.annotation.McpServerEndpoint;

						@McpServerEndpoint(path = "/mcp", name = "invalid", version = "1")
						public final class InvalidTypedInputEndpoint {
						  @McpPrompt(name = "roots", mayRequestInput = @McpMayRequestInput(
						      type = McpInputRequestType.ROOTS,
						      samplingCapabilities = McpClientCapability.SAMPLING_CONTEXT,
						      requirement = McpInputRequirement.CONDITIONAL))
						  public McpOperationResult roots() { return null; }

						  @McpPrompt(name = "sampling", mayRequestInput = @McpMayRequestInput(
						      type = McpInputRequestType.SAMPLING,
						      samplingCapabilities = McpClientCapability.ELICITATION_FORM,
						      requirement = McpInputRequirement.CONDITIONAL))
						  public McpOperationResult sampling() { return null; }

						  @McpPrompt(name = "duplicate", mayRequestInput = @McpMayRequestInput(
						      type = McpInputRequestType.SAMPLING,
						      samplingCapabilities = {
						          McpClientCapability.SAMPLING_TOOLS,
						          McpClientCapability.SAMPLING_TOOLS},
						      requirement = McpInputRequirement.CONDITIONAL))
						  public McpOperationResult duplicate() { return null; }
						}
						""");

		Compilation compilation = Compiler.javac()
				.withProcessors(new SokletProcessor())
				.compile(source);

		assertThat(compilation).failed();
		assertThat(compilation).hadErrorContaining(
				"samplingCapabilities may be declared only for SAMPLING")
				.inFile(source);
		assertThat(compilation).hadErrorContaining(
				"samplingCapabilities accepts only SAMPLING_CONTEXT and SAMPLING_TOOLS")
				.inFile(source);
		assertThat(compilation).hadErrorContaining(
				"samplingCapabilities must not contain duplicates")
				.inFile(source);
	}

	@Test
	void annotatedToolsThatMayRequestInputUseTheOperationResultPath() {
		JavaFileObject source = JavaFileObjects.forSourceString(
				"example.InvalidTypedCompletionEndpoint", """
						package example;

						import com.soklet.McpInputRequirement;
						import com.soklet.McpInputRequestType;
						import com.soklet.annotation.McpMayRequestInput;
						import com.soklet.annotation.McpServerEndpoint;
						import com.soklet.annotation.McpTool;

						@McpServerEndpoint(path = "/mcp", name = "invalid", version = "1")
						public final class InvalidTypedCompletionEndpoint {
						  @McpTool(name = "typed", mayRequestInput = @McpMayRequestInput(
						      type = McpInputRequestType.ELICITATION_FORM,
						      requirement = McpInputRequirement.CONDITIONAL))
						  public Result typed() { return new Result("done"); }

						  public record Result(String value) {}
						}
						""");

		Compilation compilation = Compiler.javac()
				.withProcessors(new SokletProcessor())
				.compile(source);

		assertThat(compilation).failed();
		assertThat(compilation).hadErrorContaining(
				"declares input requests or request state must return McpOperationResult or a subtype")
				.inFile(source);
	}

	@Test
	void promptAndResourceMrtrMetadataRequireTheOperationResultPath() {
		JavaFileObject source = JavaFileObjects.forSourceString(
				"example.InvalidPromptAndResourceEndpoint", """
						package example;

						import com.soklet.McpInputRequirement;
						import com.soklet.McpInputRequestType;
						import com.soklet.McpPromptOutput;
						import com.soklet.McpRequestStateMode;
						import com.soklet.McpResourceOutput;
						import com.soklet.annotation.McpMayRequestInput;
						import com.soklet.annotation.McpPrompt;
						import com.soklet.annotation.McpResource;
						import com.soklet.annotation.McpServerEndpoint;

						@McpServerEndpoint(path = "/mcp", name = "invalid", version = "1")
						public final class InvalidPromptAndResourceEndpoint {
						  @McpPrompt(name = "prompt", mayRequestInput = @McpMayRequestInput(
						      type = McpInputRequestType.ELICITATION_FORM,
						      requirement = McpInputRequirement.CONDITIONAL))
						  public McpPromptOutput prompt() { return null; }

						  @McpResource(uri = "test://resource", name = "resource",
						      requestStateMode = McpRequestStateMode.FRAMEWORK_PROTECTED)
						  public McpResourceOutput resource() { return null; }
						}
						""");

		Compilation compilation = Compiler.javac()
				.withProcessors(new SokletProcessor())
				.compile(source);

		assertThat(compilation).failed();
		assertThat(compilation).hadErrorContaining(
				"An @McpPrompt method that declares input requests or request state must return McpOperationResult or a subtype")
				.inFile(source);
		assertThat(compilation).hadErrorContaining(
				"An @McpResource method that declares input requests or request state must return McpOperationResult or a subtype")
				.inFile(source);
	}

	private static void assertDeclaration(
			@NonNull McpInputRequestDeclaration declaration,
			@NonNull McpInputRequestType inputRequestType,
			@NonNull String jsonRpcMethod,
			@NonNull Set<@NonNull McpClientCapability> capabilities,
			@NonNull McpInputRequirement requirement) {
		Assertions.assertEquals(inputRequestType,
				declaration.getInputRequestType());
		Assertions.assertEquals(jsonRpcMethod,
				declaration.getJsonRpcMethod());
		Assertions.assertEquals(capabilities, declaration.getCapabilities());
		Assertions.assertEquals(requirement, declaration.getRequirement());
	}

	private static void compile(@NonNull Path source,
			@NonNull Path classes, @NonNull Path generated) throws IOException {
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		Assertions.assertNotNull(compiler);
		StringWriter diagnostics = new StringWriter();
		try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(
				null, null, StandardCharsets.UTF_8)) {
			String classpath = classes + System.getProperty("path.separator")
					+ System.getProperty("java.class.path");
			JavaCompiler.CompilationTask task = compiler.getTask(diagnostics,
					fileManager, null, List.of("--release", "17", "-parameters",
						"-Asoklet.cacheMode=none", "-classpath", classpath,
						"-d", classes.toString(), "-s", generated.toString()),
					null, fileManager.getJavaFileObjects(source));
			task.setProcessors(List.of(new SokletProcessor()));
			Assertions.assertTrue(Boolean.TRUE.equals(task.call()),
					diagnostics.toString());
		}
	}
}
