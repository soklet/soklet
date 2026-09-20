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
import com.soklet.annotation.McpPromptCompletion;
import com.soklet.annotation.McpResourceCompletion;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.annotation.concurrent.ThreadSafe;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.StringWriter;
import java.lang.annotation.ElementType;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.annotation.Retention;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.google.testing.compile.CompilationSubject.assertThat;

/** Processor and public annotation contracts for P2 argument completion. */
@ThreadSafe
public class McpCompletionAnnotationProcessorTests {
	@Test
	void completionAnnotationsHaveOnlyReviewedElementsAndMethodTargets() {
		Assertions.assertEquals(1,
				McpPromptCompletion.class.getDeclaredMethods().length);
		Assertions.assertEquals(1,
				McpResourceCompletion.class.getDeclaredMethods().length);
		Assertions.assertEquals(Set.of("name"), Set.of(
				McpPromptCompletion.class.getDeclaredMethods()[0].getName()));
		Assertions.assertEquals(Set.of("uri"), Set.of(
				McpResourceCompletion.class.getDeclaredMethods()[0].getName()));
		Assertions.assertNull(
				McpPromptCompletion.class.getDeclaredMethods()[0].getDefaultValue());
		Assertions.assertNull(
				McpResourceCompletion.class.getDeclaredMethods()[0].getDefaultValue());
		Assertions.assertEquals(Set.of(ElementType.METHOD), Set.of(
				McpPromptCompletion.class.getAnnotation(Target.class).value()));
		Assertions.assertEquals(Set.of(ElementType.METHOD), Set.of(
				McpResourceCompletion.class.getAnnotation(Target.class).value()));
		Assertions.assertEquals(RetentionPolicy.RUNTIME,
				McpPromptCompletion.class.getAnnotation(Retention.class).value());
		Assertions.assertEquals(RetentionPolicy.RUNTIME,
				McpResourceCompletion.class.getAnnotation(Retention.class).value());
	}

	@Test
	void generatesCallbacksForBothTargetsAndAllSupportedInjections()
			throws IOException {
		Compilation compilation = compile("example.CompletionEndpoint", """
				package example;
				import com.soklet.*;
				import com.soklet.annotation.*;
				import java.util.Optional;
				@McpServerEndpoint(path = "/mcp", name = "test", version = "1")
				public final class CompletionEndpoint {
				  @McpPrompt(name = "code_review")
				  public McpPromptOutput prompt(@McpPromptArgument String code) { return null; }
				  @McpResource(uri = "catalog://products/{sku}", name = "product")
				  public McpResourceOutput resource(@McpResourceUriParameter String sku) { return null; }
				  @McpPromptCompletion(name = "code_review")
				  public McpArgumentCompletionResult completePrompt(
				      McpRequestContext requestContext,
				      McpCompletionContext.Prompt completionContextPrompt,
				      McpInvocationFeatures invocationFeatures,
				      CancelationToken cancelationToken,
				      Optional<McpProgressReporter> progressReporter) throws Exception {
				    return null;
				  }
				  @McpResourceCompletion(uri = "catalog://products/{sku}")
				  public McpArgumentCompletionResult completeResource(
				      Optional<McpProgressReporter> progressReporter,
				      McpCompletionContext completionContextResource,
				      CancelationToken cancelationToken,
				      McpInvocationFeatures invocationFeatures,
				      McpRequestContext requestContext) { return null; }
				}
				""");
		assertThat(compilation).succeeded();
		String source = compilation.generatedSourceFiles().stream()
				.map(file -> {
					try {
						return file.getCharContent(true).toString();
					} catch (IOException exception) {
						throw new IllegalStateException(exception);
					}
				})
				.collect(Collectors.joining("\n"));
		Assertions.assertTrue(source.contains(
				"promptBuilder0.completionHandler((requestContext, completionContextPrompt, invocationFeatures)"),
				source);
		Assertions.assertTrue(source.contains(
				"resourceBuilder0.completionHandler((requestContext, completionContextResource, invocationFeatures)"),
				source);
		Assertions.assertTrue(source.contains(
				"((com.soklet.McpCompletionContext.Prompt) completionContextPrompt)"),
				source);
		Assertions.assertTrue(source.contains(
				"invocationFeatures.getCancelationToken()"), source);
		Assertions.assertTrue(source.contains(
				"invocationFeatures.getProgressReporter()"), source);
	}

	@Test
	void generatedCallbacksInvokeTypedPromptAndResourceHandlers(
			@TempDir Path temporaryDirectory) throws Exception {
		Path sourceFile = temporaryDirectory.resolve(
				"src/example/LiveCompletionEndpoint.java");
		Path classes = temporaryDirectory.resolve("classes");
		Path generated = temporaryDirectory.resolve("generated");
		Files.createDirectories(sourceFile.getParent());
		Files.createDirectories(classes);
		Files.createDirectories(generated);
		Files.writeString(sourceFile, """
				package example;
				import com.soklet.*;
				import com.soklet.annotation.*;
				import java.util.List;
				@McpServerEndpoint(path = "/mcp", name = "test", version = "1")
				public final class LiveCompletionEndpoint {
				  @McpPrompt(name = "review")
				  public McpPromptOutput prompt(@McpPromptArgument String code) { return null; }
				  @McpResource(uri = "catalog://products/{sku}", name = "product")
				  public McpResourceOutput resource(@McpResourceUriParameter String sku) { return null; }
				  @McpPromptCompletion(name = "review")
				  public McpArgumentCompletionResult promptCompletion(
				      McpCompletionContext.Prompt completionContextPrompt) {
				    return McpArgumentCompletionResult.fromValues(List.of(
				        completionContextPrompt.getPromptRegistration().getName()
				        + ":" + completionContextPrompt.getArgumentValue()));
				  }
				  @McpResourceCompletion(uri = "catalog://products/{sku}")
				  public McpArgumentCompletionResult resourceCompletion(
				      McpCompletionContext.Resource completionContextResource) {
				    return McpArgumentCompletionResult.fromValues(List.of(
				        completionContextResource.getResourceRegistration()
				            .getUriTemplate().orElseThrow()
				        + ":" + completionContextResource.getArgumentValue()));
				  }
				}
				""", StandardCharsets.UTF_8);
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		Assertions.assertNotNull(compiler);
		StringWriter diagnostics = new StringWriter();
		try (StandardJavaFileManager fileManager = compiler
				.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
			String classpath = classes + System.getProperty("path.separator")
					+ System.getProperty("java.class.path");
			JavaCompiler.CompilationTask task = compiler.getTask(diagnostics,
					fileManager, null, List.of("--release", "17", "-parameters",
						"-Asoklet.cacheMode=none", "-classpath", classpath,
						"-d", classes.toString(), "-s", generated.toString()),
					null, fileManager.getJavaFileObjects(sourceFile));
			task.setProcessors(List.of(new SokletProcessor()));
			Assertions.assertTrue(Boolean.TRUE.equals(task.call()),
					diagnostics.toString());
		}
		try (URLClassLoader classLoader = new URLClassLoader(
				new URL[] { classes.toUri().toURL() },
				McpCompletionAnnotationProcessorTests.class.getClassLoader())) {
			Class<?> endpointClass = Class.forName(
					"example.LiveCompletionEndpoint", false, classLoader);
			McpEndpoint endpoint = McpEndpointRegistry.fromClasses(endpointClass)
					.getEndpoints().get(0);
			McpPromptRegistration prompt = endpoint.getPromptRegistrations().get(0);
			McpResourceRegistration resource = endpoint.getResourceRegistrations().get(0);
			McpRequestContext request = (McpRequestContext) Proxy.newProxyInstance(
					classLoader, new Class<?>[] { McpRequestContext.class },
					(proxy, method, arguments) -> null);
			McpInvocationFeatures features =
					McpInvocationFeatures.fromFeatures(Map.of());
			McpCompletionContext.Prompt promptContext =
					new McpCompletionContext.Prompt() {
						@Override public String getArgumentName() { return "code"; }
						@Override public String getArgumentValue() { return "pr"; }
						@Override public Map<String, String> getContextArguments() {
							return Map.of();
						}
						@Override public McpPromptRegistration getPromptRegistration() {
							return prompt;
						}
					};
			McpCompletionContext.Resource resourceContext =
					new McpCompletionContext.Resource() {
						@Override public String getArgumentName() { return "sku"; }
						@Override public String getArgumentValue() { return "42"; }
						@Override public Map<String, String> getContextArguments() {
							return Map.of();
						}
						@Override public McpResourceRegistration getResourceRegistration() {
							return resource;
						}
					};
			Assertions.assertEquals(List.of("review:pr"),
					prompt.getCompletionHandler().orElseThrow()
							.handle(request, promptContext, features).getValues());
			Assertions.assertEquals(
					List.of("catalog://products/{sku}:42"),
					resource.getCompletionHandler().orElseThrow()
							.handle(request, resourceContext, features).getValues());
		}
	}

	@Test
	void rejectsMissingExactAndDuplicateTargets() {
		Compilation compilation = compile("example.BadTargets", """
				package example;
				import com.soklet.*;
				import com.soklet.annotation.*;
				@McpServerEndpoint(path = "/mcp", name = "test", version = "1")
				public final class BadTargets {
				  @McpPrompt(name = "present")
				  public McpPromptOutput prompt() { return null; }
				  @McpResource(uri = "catalog://exact", name = "exact")
				  public McpResourceOutput exact() { return null; }
				  @McpPromptCompletion(name = "missing")
				  public McpArgumentCompletionResult missing(McpCompletionContext.Prompt context) { return null; }
				  @McpPromptCompletion(name = "present")
				  public McpArgumentCompletionResult first(McpCompletionContext.Prompt context) { return null; }
				  @McpPromptCompletion(name = "present")
				  public McpArgumentCompletionResult second(McpCompletionContext.Prompt context) { return null; }
				  @McpResourceCompletion(uri = "catalog://exact")
				  public McpArgumentCompletionResult exactCompletion(McpCompletionContext.Resource context) { return null; }
				}
				""");
		assertThat(compilation).failed();
		assertThat(compilation).hadErrorContaining(
				"@McpPromptCompletion target 'missing' has no @McpPrompt registration");
		assertThat(compilation).hadErrorContaining(
				"Duplicate @McpPromptCompletion for prompt 'present'");
		assertThat(compilation).hadErrorContaining(
				"@McpResourceCompletion target 'catalog://exact' has no @McpResource URI-template registration");
	}

	@Test
	void rejectsWrongReturnsContextsBindingsAndDuplicateInjections() {
		Compilation compilation = compile("example.BadCompletionSignatures", """
				package example;
				import com.soklet.*;
				import com.soklet.annotation.*;
				import org.jspecify.annotations.Nullable;
				@McpServerEndpoint(path = "/mcp", name = "test", version = "1")
				public final class BadCompletionSignatures {
				  @McpPrompt(name = "prompt")
				  public McpPromptOutput prompt() { return null; }
				  @McpResource(uri = "catalog://products/{sku}", name = "product")
				  public McpResourceOutput resource(@McpResourceUriParameter String sku) { return null; }
				  @McpPromptCompletion(name = "prompt")
				  public McpOperationResult wrongReturn(
				      McpCompletionContext.Resource wrongContext,
				      @McpPromptArgument String ordinaryArgument) { return null; }
				  @McpResourceCompletion(uri = "catalog://products/{sku}")
				  public @Nullable McpArgumentCompletionResult wrongParameters(
				      McpCompletionContext context,
				      McpCompletionContext.Resource duplicateContext,
				      McpInvocationFeatures features,
				      McpInvocationFeatures duplicateFeatures) { return null; }
				}
				""");
		assertThat(compilation).failed();
		assertThat(compilation).hadErrorContaining(
				"return type must be exactly McpArgumentCompletionResult");
		assertThat(compilation).hadErrorContaining(
				"must not inject the other target's McpCompletionContext type");
		assertThat(compilation).hadErrorContaining(
				"must not declare binding annotations");
		assertThat(compilation).hadErrorContaining(
				"must return a nonnull McpArgumentCompletionResult");
		assertThat(compilation).hadErrorContaining(
				"must inject exactly one McpCompletionContext parameter");
		assertThat(compilation).hadErrorContaining(
				"McpInvocationFeatures at most once");
	}

	private static Compilation compile(String binaryName, String source) {
		return Compiler.javac().withProcessors(new SokletProcessor()).compile(
				JavaFileObjects.forSourceString(binaryName, source));
	}
}
