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

import static com.google.testing.compile.CompilationSubject.assertThat;

/**
 * Compile-time and generated-registration coverage for annotated task-only
 * tools with typed eventual output.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class McpAnnotatedTaskProcessorTests {
	@Test
	@SuppressWarnings("unchecked")
	void generatedTaskToolRetainsEventualOutputContract(
			@TempDir Path temporaryDirectory) throws Exception {
		Path sourceDirectory = temporaryDirectory.resolve("source/example");
		Path classDirectory = temporaryDirectory.resolve("classes");
		Path generatedDirectory = temporaryDirectory.resolve("generated");
		Files.createDirectories(sourceDirectory);
		Files.createDirectories(classDirectory);
		Files.createDirectories(generatedDirectory);
		Path source = sourceDirectory.resolve("TaskEndpoint.java");
		Files.writeString(source, """
				package example;

				import com.soklet.McpTaskControl;
				import com.soklet.McpTaskCreatedResult;
				import com.soklet.annotation.McpServerEndpoint;
				import com.soklet.annotation.McpTool;
				import java.util.List;

				@McpServerEndpoint(path = "/tasks", name = "tasks", version = "1")
				public final class TaskEndpoint {
				  @McpTool(name = "reports.generate")
				  public McpTaskCreatedResult<Report> generate(
				      McpTaskControl taskControl) {
				    return McpTaskCreatedResult.fromTaskId(
				        taskControl == null ? "invalid" : "durable-task");
				  }

				  public record Report(List<Line> lines) {}
				  public record Line(String summary) {}
				}
				""", StandardCharsets.UTF_8);

		compile(source, classDirectory, generatedDirectory);
		String generatedSource;
		try (var paths = Files.walk(generatedDirectory)) {
			Path generatedProvider = paths.filter(Files::isRegularFile)
					.filter(path -> path.getFileName().toString()
							.startsWith("SokletMcpEndpointProvider_"))
					.findFirst().orElseThrow();
			generatedSource = Files.readString(generatedProvider,
					StandardCharsets.UTF_8);
		}
		Assertions.assertTrue(generatedSource.contains(
				".argumentAndOutputTypes(Tool0Arguments.class, new com.soklet.converter.TypeReference<example.TaskEndpoint.Report>() {}"),
				generatedSource);
		Assertions.assertTrue(generatedSource.contains(
				".operationHandler((request, arguments, features) ->"),
				generatedSource);
		Assertions.assertTrue(generatedSource.contains(
				"generate(features.getTaskControl().orElseThrow())"),
				generatedSource);
		Assertions.assertFalse(generatedSource.contains("NO_OUTPUT_SCHEMA"),
				generatedSource);

		try (URLClassLoader classLoader = new URLClassLoader(
				new URL[] {classDirectory.toUri().toURL()},
				McpAnnotatedTaskProcessorTests.class.getClassLoader())) {
			Class<?> endpointClass = Class.forName("example.TaskEndpoint", false,
					classLoader);
			Class<?> reportClass = Class.forName("example.TaskEndpoint$Report",
					false, classLoader);
			McpToolRegistration<?> untypedRegistration = McpEndpointRegistry
					.fromClasses(endpointClass).getEndpoints().get(0).getTools().get(0);
			Assertions.assertEquals(reportClass,
					untypedRegistration.getOutputType().orElseThrow());
			Assertions.assertTrue(untypedRegistration.isTaskRequired());
			McpJsonObject properties = Assertions.assertInstanceOf(
					McpJsonObject.class,
					untypedRegistration.getOutputSchema().orElseThrow().getDocument()
							.find("properties").orElseThrow());
			Assertions.assertTrue(properties.find("lines").isPresent());

			McpRequestContext requestContext = (McpRequestContext)
					Proxy.newProxyInstance(McpRequestContext.class.getClassLoader(),
							new Class<?>[] {McpRequestContext.class},
							(proxy, method, arguments) -> null);
			McpToolRegistration<Object> registration =
					(McpToolRegistration<Object>) untypedRegistration;
			McpTaskControl taskControl = new McpTaskControl() {
				@Override
				public McpRequestContext getRequestContext() {
					return requestContext;
				}

				@Override
				public McpTaskOrigin getTaskOrigin() {
					return McpTaskOrigin.fromPersistedState(
							McpJsonObject.emptyInstance());
				}
			};
			McpTaskCreatedResult<?> result = Assertions.assertInstanceOf(
					McpTaskCreatedResult.class,
					registration.invoke(requestContext, McpJsonObject.emptyInstance(),
							McpInvocationFeatures.fromFeatures(Map.of(
									McpTaskControl.class, taskControl))));
			Assertions.assertEquals("durable-task", result.getTaskId());
		}
	}

	@Test
	void rejectsRawWildcardAndTypeVariableTaskOutputContracts() {
		List<String> returnTypes = List.of(
				"McpTaskCreatedResult",
				"McpTaskCreatedResult<?> ",
				"McpTaskCreatedResult<java.util.List<T>>");
		for (int index = 0; index < returnTypes.size(); ++index) {
			String typeParameter = index == 2 ? "<T>" : "";
			JavaFileObject source = JavaFileObjects.forSourceString(
					"example.InvalidTaskEndpoint" + index, """
							package example;

							import com.soklet.McpTaskCreatedResult;
							import com.soklet.annotation.McpServerEndpoint;
							import com.soklet.annotation.McpTool;

							@McpServerEndpoint(path = "/tasks%s", name = "tasks", version = "1")
							public final class InvalidTaskEndpoint%s%s {
							  @McpTool(name = "reports.generate")
							  public %s generate() { return null; }
							}
							""".formatted(index, index, typeParameter,
							returnTypes.get(index)));

			Compilation compilation = Compiler.javac()
					.withProcessors(new SokletProcessor())
					.compile(source);

			assertThat(compilation).failed();
			assertThat(compilation).hadErrorContaining(
					"McpTaskCreatedResult return type must declare exactly one concrete eventual output type")
					.inFile(source);
		}
	}

	@Test
	void rejectsInaccessibleTaskOutputContract() {
		JavaFileObject source = JavaFileObjects.forSourceString(
				"example.InaccessibleTaskEndpoint", """
						package example;

						import com.soklet.McpTaskCreatedResult;
						import com.soklet.annotation.McpServerEndpoint;
						import com.soklet.annotation.McpTool;

						@McpServerEndpoint(path = "/tasks", name = "tasks", version = "1")
						public final class InaccessibleTaskEndpoint {
						  @McpTool(name = "reports.generate")
						  public McpTaskCreatedResult<Hidden> generate() { return null; }

						  private record Hidden(String value) {}
						}
						""");

		Compilation compilation = Compiler.javac()
				.withProcessors(new SokletProcessor())
				.compile(source);

		assertThat(compilation).failed();
		assertThat(compilation).hadErrorContaining(
				"@McpTool return type must be accessible to the generated MCP endpoint provider")
				.inFile(source);
	}

	@Test
	void rejectsUnsupportedTaskOutputSchema() {
		JavaFileObject source = JavaFileObjects.forSourceString(
				"example.UnsupportedTaskEndpoint", """
						package example;

						import com.soklet.McpTaskCreatedResult;
						import com.soklet.annotation.McpServerEndpoint;
						import com.soklet.annotation.McpTool;

						@McpServerEndpoint(path = "/tasks", name = "tasks", version = "1")
						public final class UnsupportedTaskEndpoint {
						  @McpTool(name = "reports.generate")
						  public McpTaskCreatedResult<Object> generate() { return null; }
						}
						""");

		Compilation compilation = Compiler.javac()
				.withProcessors(new SokletProcessor())
				.compile(source);

		assertThat(compilation).failed();
		assertThat(compilation).hadErrorContaining(
				"MCP tool 'reports.generate' output schema is unsupported")
				.inFile(source);
		assertThat(compilation).hadErrorContaining("OBJECT_TYPE").inFile(source);
	}

	@Test
	void validatesDirectTaskControlInjection() {
		JavaFileObject source = JavaFileObjects.forSourceString(
				"example.InvalidTaskControlEndpoint", """
						package example;

						import com.soklet.McpCompleteResult;
						import com.soklet.McpTaskControl;
						import com.soklet.McpTaskCreatedResult;
						import com.soklet.annotation.McpServerEndpoint;
						import com.soklet.annotation.McpTool;
						import com.soklet.annotation.McpToolArgument;

						@McpServerEndpoint(path = "/tasks", name = "tasks", version = "1")
						public final class InvalidTaskControlEndpoint {
						  @McpTool(name = "inline")
						  public McpCompleteResult inline(McpTaskControl taskControl) {
						    return null;
						  }

						  @McpTool(name = "duplicate")
						  public McpTaskCreatedResult<Report> duplicate(
						      McpTaskControl first, McpTaskControl second) {
						    return null;
						  }

						  @McpTool(name = "annotated")
						  public McpTaskCreatedResult<Report> annotated(
						      @McpToolArgument McpTaskControl taskControl) {
						    return null;
						  }

						  public record Report(String value) {}
						}
						""");

		Compilation compilation = Compiler.javac()
				.withProcessors(new SokletProcessor())
				.compile(source);

		assertThat(compilation).failed();
		assertThat(compilation).hadErrorContaining(
				"McpTaskControl may be injected only into an @McpTool method that returns McpTaskCreatedResult<R>")
				.inFile(source);
		assertThat(compilation).hadErrorContaining(
				"An @McpTool method may inject McpTaskControl at most once")
				.inFile(source);
		assertThat(compilation).hadErrorContaining(
				"Injectable MCP feature parameters must not also be annotated with @McpToolArgument")
				.inFile(source);
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
