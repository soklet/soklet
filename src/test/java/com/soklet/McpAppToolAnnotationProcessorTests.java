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
import com.soklet.annotation.McpAppTool;
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
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.soklet.McpAppToolMetadata.Visibility.APP;
import static com.soklet.McpAppToolMetadata.Visibility.MODEL;

/**
 * Compile-time diagnostics and generated registration parity for Apps tools.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class McpAppToolAnnotationProcessorTests {

	@Test
	void annotationHasOnlyReviewedElementsDefaultsAndMethodTarget()
			throws ReflectiveOperationException {
		Assertions.assertEquals(Set.of("resourceUri", "visibility"),
				Arrays.stream(McpAppTool.class.getDeclaredMethods())
						.map(Method::getName).collect(Collectors.toSet()));
		Assertions.assertEquals("", McpAppTool.class
				.getDeclaredMethod("resourceUri").getDefaultValue());
		Assertions.assertEquals(String.class, McpAppTool.class
				.getDeclaredMethod("resourceUri").getReturnType());
		Assertions.assertArrayEquals(new McpAppToolMetadata.Visibility[]{MODEL, APP},
				(McpAppToolMetadata.Visibility[]) McpAppTool.class
						.getDeclaredMethod("visibility").getDefaultValue());
		Assertions.assertEquals(McpAppToolMetadata.Visibility[].class,
				McpAppTool.class.getDeclaredMethod("visibility").getReturnType());
		Assertions.assertEquals(Set.of(ElementType.METHOD), Set.of(
				McpAppTool.class.getAnnotation(Target.class).value()));
		Assertions.assertEquals(RetentionPolicy.RUNTIME,
				McpAppTool.class.getAnnotation(Retention.class).value());
	}

	@Test
	void generatedRegistrationsPreserveMetadataAndAllThreeHandlerKinds(
			@TempDir Path temporaryDirectory) throws Exception {
		try (CompiledEndpoint compiled = compileEndpoint(temporaryDirectory,
				"LiveAppEndpoint", """
				package example;
				import com.soklet.*;
				import com.soklet.annotation.*;
				import static com.soklet.McpAppToolMetadata.Visibility.*;
				@McpServerEndpoint(path = "/apps", name = "apps", version = "test")
				public final class LiveAppEndpoint {
				  @McpTool(name = "plain")
				  public Reply plain() { return new Reply("plain"); }
				  @McpTool(name = "defaults")
				  @McpAppTool(resourceUri = "ui://orders/dashboard")
				  public Reply defaults() { return new Reply("typed"); }
				  @McpAppTool(visibility = APP)
				  @McpTool(name = "operation")
				  public McpOperationResult operation() {
				    return McpCompleteResult.fromToolText("operation");
				  }
				  @McpTool(name = "task")
				  @McpAppTool(visibility = {APP, MODEL, APP})
				  public McpTaskCreatedResult<Reply> task() {
				    return McpTaskCreatedResult.fromTaskId("app-task");
				  }
				  @McpTool(name = "hidden")
				  @McpAppTool(visibility = {})
				  public Reply hidden() { return new Reply("hidden"); }
				  @McpTool(name = "model")
				  @McpAppTool(resourceUri = "", visibility = {MODEL, MODEL})
				  public Reply model() { return new Reply("model"); }
				  @McpTool(name = "omitted")
				  @McpAppTool
				  public Reply omitted() { return new Reply("omitted"); }
				  @McpResource(uri = "ui://orders/dashboard", name = "dashboard",
				      mimeType = "text/html;profile=mcp-app")
				  public McpResourceOutput dashboard() {
				    throw new AssertionError("Descriptor discovery must not invoke resources.");
				  }
				  public record Reply(String value) {}
				}
				""")) {
			McpEndpoint endpoint = compiled.endpoint();
			Map<String, McpToolRegistration<?>> tools = endpoint.getToolRegistrations().stream()
					.collect(Collectors.toMap(McpToolRegistration::getName,
							Function.identity()));
			Assertions.assertEquals(7, tools.size());
			Assertions.assertTrue(tools.get("plain").getAppToolMetadata().isEmpty());
			assertMetadata(tools.get("defaults"), McpAppToolMetadata.builder()
					.resourceUri(URI.create("ui://orders/dashboard")).build());
			assertMetadata(tools.get("operation"), McpAppToolMetadata.builder()
					.visibility(Set.of(APP)).build());
			assertMetadata(tools.get("task"), McpAppToolMetadata.builder().build());
			Assertions.assertEquals(List.of(MODEL, APP), List.copyOf(tools.get("task")
					.getAppToolMetadata().orElseThrow().getVisibility()));
			assertMetadata(tools.get("hidden"), McpAppToolMetadata.builder()
					.visibility(Set.of()).build());
			assertMetadata(tools.get("model"), McpAppToolMetadata.builder()
					.visibility(Set.of(MODEL)).build());
			assertMetadata(tools.get("omitted"), McpAppToolMetadata.builder().build());
			Assertions.assertEquals(URI.create("ui://orders/dashboard"),
					endpoint.getResourceRegistrations().get(0).getUri().orElseThrow());
			Assertions.assertNotNull(compiled.endpointClass().getMethod("defaults")
					.getAnnotation(McpAppTool.class));

			McpCompleteResult typedResult = Assertions.assertInstanceOf(
					McpCompleteResult.class, invoke(tools.get("defaults")));
			McpToolOutput typedOutput = Assertions.assertInstanceOf(
					McpToolOutput.class, typedResult.getPayload());
			Assertions.assertEquals(McpJsonObject.builder().put("value", "typed")
					.build(), typedOutput.getStructuredContent().orElseThrow());
			Assertions.assertTrue(tools.get("defaults").getOutputSchema().isPresent());
			Assertions.assertFalse(tools.get("defaults").isTaskRequired());
			Assertions.assertEquals(McpCompleteResult.fromToolText("operation"),
					invoke(tools.get("operation")));
			Assertions.assertTrue(tools.get("operation").getOutputSchema().isEmpty());
			McpTaskCreatedResult<?> taskResult = Assertions.assertInstanceOf(
					McpTaskCreatedResult.class, invoke(tools.get("task")));
			Assertions.assertEquals("app-task", taskResult.getTaskId());
			Assertions.assertTrue(tools.get("task").isTaskRequired());
			Assertions.assertEquals(tools.get("defaults").getOutputType(),
					tools.get("task").getOutputType());
			Assertions.assertEquals(tools.get("defaults").getOutputSchema(),
					tools.get("task").getOutputSchema());
		}
	}

	@Test
	void acceptedUrisRetainProgrammaticSpellingAndValidation(
			@TempDir Path temporaryDirectory) throws Exception {
		List<String> uris = List.of("ui://orders/dashboard",
				"UI://orders/%E2%9C%93?mode=compact#pane",
				"ui://orders/view%22quoted");
		StringBuilder methods = new StringBuilder();
		for (int index = 0; index < uris.size(); ++index) {
			String uri = uris.get(index);
			Assertions.assertEquals(URI.create(uri), McpAppToolMetadata.builder()
					.resourceUri(URI.create(uri)).build().getResourceUri().orElseThrow());
			methods.append("""
				  @McpTool(name = "tool%d") @McpAppTool(resourceUri = "%s")
				  public McpOperationResult tool%d() { return null; }
				  @McpResource(uri = "%s", name = "resource%d",
				      mimeType = "text/html;profile=mcp-app")
				  public McpResourceOutput resource%d() { return null; }
				""".formatted(index, uri, index, uri, index, index));
		}
		try (CompiledEndpoint compiled = compileEndpoint(temporaryDirectory,
				"AcceptedAppUris", endpointSource("AcceptedAppUris", methods.toString()))) {
			Map<String, McpToolRegistration<?>> tools = compiled.endpoint().getToolRegistrations()
					.stream().collect(Collectors.toMap(McpToolRegistration::getName,
							Function.identity()));
			for (int index = 0; index < uris.size(); ++index)
				Assertions.assertEquals(uris.get(index), tools.get("tool" + index)
						.getAppToolMetadata().orElseThrow().getResourceUri()
						.orElseThrow().toString());
		}
	}

	@Test
	void rejectsTheSameInvalidUrisAsProgrammaticMetadataWithoutEchoingValues() {
		List<String> uris = List.of(" ", "private-relative/view",
				"https://private-orders/view", "ui:private-opaque",
				"ui:/private-no-authority", "ui://private-orders/view/../other",
				"ui://private-orders/./view", "ui://private-orders/café",
				"ui://private-orders/{view}", "ui://private-orders/%invalid");
		StringBuilder methods = new StringBuilder();
		for (int index = 0; index < uris.size(); ++index) {
			String uri = uris.get(index);
			Assertions.assertThrows(IllegalArgumentException.class,
					() -> McpAppToolMetadata.builder().resourceUri(URI.create(uri)));
			methods.append("""
				  @McpTool(name = "invalid%d") @McpAppTool(resourceUri = "%s")
				  public McpOperationResult invalid%d() { return null; }
				""".formatted(index, uri, index));
		}
		Compilation compilation = compile("InvalidAppUris",
				endpointSource("InvalidAppUris", methods.toString()));
		assertThat(compilation).failed();
		List<String> diagnostics = compilation.errors().stream()
				.map(error -> error.getMessage(Locale.ROOT)).toList();
		Assertions.assertEquals(uris.size(), diagnostics.stream()
				.filter(message -> message.contains("@McpAppTool")
						&& message.contains("resourceUri")).count(), diagnostics.toString());
		for (String diagnostic : diagnostics)
			Assertions.assertFalse(diagnostic.contains("private-"), diagnostic);
	}

	@Test
	void rejectsOrphanOnOrdinaryPromptAndResourceMethods() {
		Compilation compilation = compile("OrphanAppEndpoint",
				endpointSource("OrphanAppEndpoint", """
				  @McpAppTool
				  public McpOperationResult ordinary() { return null; }
				  @McpPrompt(name = "prompt") @McpAppTool
				  public McpPromptOutput prompt() { return null; }
				  @McpResource(uri = "ui://orders/view", name = "view") @McpAppTool
				  public McpResourceOutput resource() { return null; }
				"""));
		assertThat(compilation).failed();
		Assertions.assertEquals(3, compilation.errors().stream()
				.map(error -> error.getMessage(Locale.ROOT))
				.filter(message -> message.contains("@McpAppTool")
						&& message.contains("@McpTool")).count());
	}

	@Test
	void orphanAloneTriggersProcessorEvenWithoutAnEndpoint() {
		Compilation compilation = compile("StandaloneAppOrphan", """
				package example;
				import com.soklet.annotation.McpAppTool;
				public final class StandaloneAppOrphan {
				  @McpAppTool
				  public String orphan() { return "orphan"; }
				}
				""");
		assertThat(compilation).failed();
		Assertions.assertTrue(compilation.errors().stream()
				.map(error -> error.getMessage(Locale.ROOT))
				.anyMatch(message -> message.contains("@McpAppTool")
						&& message.contains("@McpTool")));
	}

	@Test
	void companionDoesNotRelaxToolEndpointPlacement() {
		Compilation compilation = compile("StandaloneAppTool", """
				package example;
				import com.soklet.annotation.*;
				public final class StandaloneAppTool {
				  @McpTool(name = "outside") @McpAppTool
				  public String outside() { return "outside"; }
				}
				""");
		assertThat(compilation).failed();
		assertThat(compilation).hadErrorContaining(
				"@McpTool methods must be declared directly by an @McpServerEndpoint class");
	}

	@Test
	void rejectsAnnotationOnClassesFieldsAndParameters() {
		Compilation compilation = compile("MisplacedAppAnnotation", """
				package example;
				import com.soklet.annotation.McpAppTool;
				@McpAppTool
				public final class MisplacedAppAnnotation {
				  @McpAppTool public String field;
				  public void method(@McpAppTool String parameter) {}
				}
				""");
		assertThat(compilation).failed();
		assertThat(compilation).hadErrorContaining(
				"not applicable to this kind of declaration");
	}

	@Test
	void generatedEndpointUsesTheSameResourceAssociationValidation(
			@TempDir Path temporaryDirectory) throws Exception {
		List<String> resources = List.of("", """
				  @McpResource(uri = "ui://orders/dashboard", name = "view",
				      mimeType = "text/html")
				  public McpResourceOutput resource() { return null; }
				""", """
				  @McpResource(uri = "ui://orders/{view}", name = "view",
				      mimeType = "text/html;profile=mcp-app")
				  public McpResourceOutput resource(@McpResourceUriParameter String view) {
				    return null;
				  }
				""");
		for (int index = 0; index < resources.size(); ++index) {
			String className = "InvalidAppAssociation" + index;
			String methods = """
				  @McpTool(name = "view")
				  @McpAppTool(resourceUri = "ui://orders/dashboard")
				  public McpOperationResult view() { return null; }
				""" + resources.get(index);
			try (CompiledEndpoint compiled = compileEndpoint(
					temporaryDirectory.resolve("case" + index), className,
					endpointSource(className, methods))) {
				Assertions.assertThrows(IllegalStateException.class, compiled::endpoint);
			}
		}
	}

	private static void assertMetadata(McpToolRegistration<?> registration,
			McpAppToolMetadata expected) {
		Assertions.assertEquals(expected, registration.getAppToolMetadata().orElseThrow());
		Assertions.assertEquals(McpJsonObject.emptyInstance(), registration.getMetadata());
	}

	private static McpOperationResult invoke(McpToolRegistration<?> registration)
			throws Exception {
		McpRequestContext request = (McpRequestContext) Proxy.newProxyInstance(
				McpRequestContext.class.getClassLoader(),
				new Class<?>[]{McpRequestContext.class},
				(proxy, method, arguments) -> null);
		return registration.invoke(request, McpJsonObject.emptyInstance(),
				McpInvocationFeatures.fromFeatures(Map.of()));
	}

	private static String endpointSource(String className, String methods) {
		return """
				package example;
				import com.soklet.*;
				import com.soklet.annotation.*;
				@McpServerEndpoint(path = "/apps", name = "apps", version = "test")
				public final class %s {
				%s
				}
				""".formatted(className, methods);
	}

	private static Compilation compile(String className, String source) {
		return Compiler.javac().withProcessors(new SokletProcessor())
				.withOptions("-Asoklet.cacheMode=none").compile(
						JavaFileObjects.forSourceString("example." + className, source));
	}

	private static CompiledEndpoint compileEndpoint(Path directory,
			String className, String source) throws Exception {
		Path sourceFile = directory.resolve("source/example/" + className + ".java");
		Path classes = directory.resolve("classes");
		Path generated = directory.resolve("generated");
		Files.createDirectories(sourceFile.getParent());
		Files.createDirectories(classes);
		Files.createDirectories(generated);
		Files.writeString(sourceFile, source, StandardCharsets.UTF_8);
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
		URLClassLoader classLoader = new URLClassLoader(
				new URL[]{classes.toUri().toURL()},
				McpAppToolAnnotationProcessorTests.class.getClassLoader());
		try {
			return new CompiledEndpoint(classLoader,
					Class.forName("example." + className, false, classLoader));
		} catch (ClassNotFoundException | LinkageError exception) {
			classLoader.close();
			throw exception;
		}
	}

	private record CompiledEndpoint(URLClassLoader classLoader,
			Class<?> endpointClass) implements AutoCloseable {
		McpEndpoint endpoint() {
			return McpEndpointRegistry.fromClasses(this.endpointClass)
					.getEndpoints().get(0);
		}

		@Override
		public void close() throws IOException {
			this.classLoader.close();
		}
	}
}
