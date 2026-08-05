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
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static com.google.testing.compile.CompilationSubject.assertThat;

/**
 * Focused validation and annotation-processing-round coverage for annotated
 * MCP endpoints.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class McpAnnotationProcessorValidationTests {
	@Test
	void rejectsSignatureTypesInaccessibleToGeneratedProvider() {
		JavaFileObject source = JavaFileObjects.forSourceString(
				"example.InaccessibleEndpoint", """
						package example;

						import com.soklet.annotation.McpServerEndpoint;
						import com.soklet.annotation.McpTool;
						import com.soklet.annotation.McpToolArgument;

						@McpServerEndpoint(path = "/mcp", name = "test", version = "1")
						public final class InaccessibleEndpoint {
						  @McpTool(name = "hidden")
						  public Hidden hidden(@McpToolArgument Hidden input) {
						    return input;
						  }

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
		assertThat(compilation).hadErrorContaining(
				"@McpTool argument type must be accessible to the generated MCP endpoint provider")
				.inFile(source);
	}

	@Test
	void rejectsThrownTypesOutsideGeneratedHandlerContract() {
		JavaFileObject source = JavaFileObjects.forSourceString(
				"example.ThrowableEndpoint", """
						package example;

						import com.soklet.annotation.McpServerEndpoint;
						import com.soklet.annotation.McpTool;

						@McpServerEndpoint(path = "/mcp", name = "test", version = "1")
						public final class ThrowableEndpoint {
						  @McpTool(name = "invalid")
						  public Result invalid() throws Throwable {
						    return new Result("value");
						  }

						  public record Result(String value) {}
						}
						""");

		Compilation compilation = Compiler.javac()
				.withProcessors(new SokletProcessor())
				.compile(source);

		assertThat(compilation).failed();
		assertThat(compilation).hadErrorContaining(
				"@McpTool method throws types must extend Exception or Error")
				.inFile(source);
	}

	@Test
	void rejectsRecordConstructorParameterOnlyToolArgumentAnnotation() {
		JavaFileObject source = JavaFileObjects.forSourceString(
				"example.ParameterOnlyRecord", """
						package example;

						import com.soklet.annotation.McpToolArgument;

						public record ParameterOnlyRecord(String value) {
						  public ParameterOnlyRecord(@McpToolArgument String value) {
						    this.value = value;
						  }
						}
						""");

		Compilation compilation = Compiler.javac()
				.withProcessors(new SokletProcessor())
				.compile(source);

		assertThat(compilation).failed();
		assertThat(compilation).hadErrorContaining(
				"@McpToolArgument parameters must belong to an @McpTool method")
				.inFile(source);
	}

	@Test
	void duplicateArgumentDiagnosticDoesNotRenderUnsafePublishedName() {
		String bidiEscape = String.format("\\u%04X", 0x202E);
		String unsafeLiteral = "\"duplicate\\n\\t" + bidiEscape + "\"";
		JavaFileObject source = JavaFileObjects.forSourceString(
				"example.DuplicateArgumentsEndpoint", """
						package example;

						import com.soklet.annotation.McpServerEndpoint;
						import com.soklet.annotation.McpTool;
						import com.soklet.annotation.McpToolArgument;

						@McpServerEndpoint(path = "/mcp", name = "test", version = "1")
						public final class DuplicateArgumentsEndpoint {
						  @McpTool(name = "duplicate")
						  public Result duplicate(
						      @McpToolArgument(name = %s) String first,
						      @McpToolArgument(name = %s) String second) {
						    return new Result(first + second);
						  }

						  public record Result(String value) {}
						}
						""".formatted(unsafeLiteral, unsafeLiteral));

		Compilation compilation = Compiler.javac()
				.withProcessors(new SokletProcessor())
				.compile(source);

		assertThat(compilation).failed();
		assertThat(compilation).hadErrorContaining(
				"Duplicate MCP tool argument name").inFile(source);
		String diagnostics = compilation.errors().toString();
		Assertions.assertFalse(diagnostics.contains("\n\t"), diagnostics);
		Assertions.assertFalse(diagnostics.contains("\u202E"), diagnostics);
	}

	@Test
	void deeplyNestedRecordGraphProducesBoundedSchemaDiagnostic() {
		StringBuilder records = new StringBuilder();
		for (int index = 0; index < 200; ++index) {
			String componentType = index == 199 ? "String"
					: "Node" + (index + 1);
			records.append("  public record Node").append(index)
					.append('(').append(componentType)
					.append(" value) {}\n");
		}
		JavaFileObject source = JavaFileObjects.forSourceString(
				"example.DeepEndpoint", """
						package example;

						import com.soklet.annotation.McpServerEndpoint;
						import com.soklet.annotation.McpTool;

						@McpServerEndpoint(path = "/mcp", name = "test", version = "1")
						public final class DeepEndpoint {
						  @McpTool(name = "deep")
						  public Node0 deep() { return null; }
						%s
						}
						""".formatted(records));

		Compilation compilation = Compiler.javac()
				.withProcessors(new SokletProcessor())
				.compile(source);

		assertThat(compilation).failed();
		assertThat(compilation).hadErrorContaining("LIMIT_EXCEEDED")
				.inFile(source);
	}

	@Test
	void defersEndpointUntilGeneratedDtoGraphResolves(
			@TempDir Path temporaryDirectory) throws IOException {
		Path sourceDirectory = temporaryDirectory.resolve("src/example");
		Path classDirectory = temporaryDirectory.resolve("classes");
		Path generatedDirectory = temporaryDirectory.resolve("generated");
		Files.createDirectories(sourceDirectory);
		Files.createDirectories(classDirectory);
		Files.createDirectories(generatedDirectory);
		Path endpointSource = sourceDirectory.resolve("GeneratedEndpoint.java");
		Files.writeString(endpointSource, """
				package example;

				import com.soklet.annotation.McpServerEndpoint;
				import com.soklet.annotation.McpTool;
				import com.soklet.annotation.McpToolArgument;

				@McpServerEndpoint(path = "/generated", name = "test", version = "1")
				public final class GeneratedEndpoint {
				  @McpTool(name = "generated")
				  public Envelope generated(
				      @McpToolArgument GeneratedDto input)
				      throws GeneratedException {
				    return new Envelope(input);
				  }

				  public record Envelope(GeneratedDto value) {}
				}
				""", StandardCharsets.UTF_8);

		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		Assertions.assertNotNull(compiler);
		DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
		try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(
				diagnostics, null, StandardCharsets.UTF_8)) {
			JavaCompiler.CompilationTask task = compiler.getTask(null, fileManager,
					diagnostics, List.of("--release", "17", "-parameters",
						"-Asoklet.cacheMode=none", "-classpath",
						System.getProperty("java.class.path"), "-d",
						classDirectory.toString(), "-s",
						generatedDirectory.toString()), null,
					fileManager.getJavaFileObjects(endpointSource));
			task.setProcessors(List.of(new SokletProcessor(),
					new GeneratedDtoProcessor()));
			Assertions.assertTrue(Boolean.TRUE.equals(task.call()),
					diagnostics.getDiagnostics().toString());
		}

		Path index = classDirectory.resolve(
				"META-INF/soklet/mcp-endpoint-descriptor-providers");
		Assertions.assertTrue(Files.isRegularFile(index));
		Assertions.assertEquals(1,
				Files.readAllLines(index, StandardCharsets.UTF_8).size());
		try (var paths = Files.walk(generatedDirectory)) {
			Assertions.assertTrue(paths.anyMatch(path -> path.getFileName()
					.toString().startsWith("SokletMcpEndpointProvider_")));
		}
	}

	private static final class GeneratedDtoProcessor extends AbstractProcessor {
		private boolean generated;

		@Override
		public Set<String> getSupportedAnnotationTypes() {
			return Set.of("*");
		}

		@Override
		public SourceVersion getSupportedSourceVersion() {
			return SourceVersion.latestSupported();
		}

		@Override
		public boolean process(Set<? extends TypeElement> annotations,
				@NonNull RoundEnvironment roundEnvironment) {
			if (generated || roundEnvironment.processingOver())
				return false;
			generated = true;
			try {
				try (Writer writer = processingEnv.getFiler()
						.createSourceFile("example.GeneratedDto").openWriter()) {
					writer.write("package example; public record GeneratedDto(String value) {}\n");
				}
				try (Writer writer = processingEnv.getFiler()
						.createSourceFile("example.GeneratedException")
						.openWriter()) {
					writer.write("package example; public final class GeneratedException extends Exception {}\n");
				}
			} catch (IOException exception) {
				throw new IllegalStateException(exception);
			}
			return false;
		}
	}
}
