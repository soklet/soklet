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
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.annotation.concurrent.ThreadSafe;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import static com.google.testing.compile.CompilationSubject.assertThat;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class SokletProcessorTests {
	@Test
	void validEndpointCompiles() {
		JavaFileObject src = JavaFileObjects.forSourceString("example.Ok",
				"""
						import org.jspecify.annotations.NonNull;
						import com.soklet.annotation.SseEventSource;
						import com.soklet.annotation.PathParameter;
						import com.soklet.SseHandshakeResult;
						
						public class Ok {
							@SseEventSource("/widgets/{id}")
							public SseHandshakeResult sse(@NonNull @PathParameter(name="id") Integer id) {
								return SseHandshakeResult.Accepted.builder().build();
							}
						}
						""");

		Compilation compilation = Compiler.javac()
				.withProcessors(new SokletProcessor())
				.compile(src);

		assertThat(compilation).succeeded();
	}

	@Test
	void rejectsNonPublicResourceMethod() {
		JavaFileObject src = JavaFileObjects.forSourceString("example.NonPublic",
				"""
						import com.soklet.annotation.GET;
						
						public class NonPublic {
							@GET("/hidden")
							private String hidden() { return "x"; }
						}
						""");

		Compilation compilation = Compiler.javac()
				.withProcessors(new SokletProcessor())
				.compile(src);

		assertThat(compilation).failed();
		assertThat(compilation).hadErrorContaining("Resource Method must be public")
				.inFile(src)
				.onLine(5);
	}

	@Test
	void rejectsWrongReturnType() {
		JavaFileObject src = JavaFileObjects.forSourceString("example.BadReturn",
				"""
						import com.soklet.annotation.SseEventSource;
						
						public class BadReturn {
							@SseEventSource("/a/{id}")
							public void sse(int id) {}
						}
						""");

		Compilation compilation = Compiler.javac()
				.withProcessors(new SokletProcessor())
				.compile(src);

		assertThat(compilation).failed();
		assertThat(compilation).hadErrorContaining("must specify a return type of SseHandshakeResult")
				.inFile(src)
				.onLine(5);

		JavaFileObject responseSrc = JavaFileObjects.forSourceString("example.ResponseBadReturn",
				"""
						import com.soklet.annotation.SseEventSource;
						import com.soklet.Response;
						
						public class ResponseBadReturn {
							@SseEventSource("/a/{id}")
							public Response sse(int id) {
							  return Response.withStatusCode(200).build();
							}
						}
						""");

		Compilation responseCompilation = Compiler.javac()
				.withProcessors(new SokletProcessor())
				.compile(responseSrc);

		assertThat(responseCompilation).failed();
		assertThat(responseCompilation).hadErrorContaining("must specify a return type of SseHandshakeResult")
				.inFile(responseSrc)
				.onLine(6);
	}

	@Test
	void rejectsMissingPathParameter() {
		JavaFileObject src = JavaFileObjects.forSourceString("example.MissingParam",
				"""
						import com.soklet.annotation.SseEventSource;
						import com.soklet.SseHandshakeResult;
						
						public class MissingParam {
							@SseEventSource("/widgets/{id}")
							public SseHandshakeResult sse() { return SseHandshakeResult.Accepted.builder().build(); }
						}
						""");

		Compilation compilation = Compiler.javac()
				.withProcessors(new SokletProcessor())
				.compile(src);

		assertThat(compilation).failed();
		assertThat(compilation).hadErrorContaining("Resource Method path parameter {id} not bound to a @PathParameter argument");

		JavaFileObject getSrc = JavaFileObjects.forSourceString("example.MissingParamGet",
				"""
						import com.soklet.annotation.GET;
						
						public class MissingParamGet {
							@GET("/widgets/{id}")
							public void widget() { /* nothing to do */ }
						}
						""");

		Compilation getCompilation = Compiler.javac()
				.withProcessors(new SokletProcessor())
				.compile(getSrc);

		assertThat(getCompilation).failed();
		assertThat(getCompilation).hadErrorContaining("Resource Method path parameter {id} not bound to a @PathParameter argument");
	}

	@Test
	void rejectsUnmatchedAnnotatedParam() {
		JavaFileObject src = JavaFileObjects.forSourceString("example.Unmatched",
				"""
						import com.soklet.annotation.GET;
						import com.soklet.annotation.PathParameter;
						
						public class Unmatched {
							@GET("/widgets")
							public void widgets(@PathParameter(name="id") int id) {
								/* nothing to do */
							}
						}
						""");

		Compilation compilation = Compiler.javac()
				.withProcessors(new SokletProcessor())
				.compile(src);

		assertThat(compilation).failed();
		assertThat(compilation).hadErrorContaining("No placeholder {id} present in resource path declaration");
	}

	@Test
	void rejectsAmbiguousResourceMethodsAtCompileTime() {
		JavaFileObject src = JavaFileObjects.forSourceString("example.Ambiguous",
				"""
						import com.soklet.annotation.GET;
						import com.soklet.annotation.PathParameter;
						public class Ambiguous {
							@GET("/items/{id}")
							public String getById(@PathParameter String id) {
								return id;
							}
							@GET("/items/{name}")
							public String getByName(@PathParameter String name) {
								return name;
							}
						}
						""");

		Compilation compilation = Compiler.javac()
				.withProcessors(new SokletProcessor())
				.compile(src);

		assertThat(compilation).failed();
		assertThat(compilation).hadErrorContaining("Ambiguous resource method declarations detected")
				.inFile(src)
				.onLine(9);
	}

	@Test
	void duplicateResourceMethodDeclarationCompiles() {
		JavaFileObject src = JavaFileObjects.forSourceString("example.DuplicateDeclaration",
				"""
						import com.soklet.annotation.GET;
						import com.soklet.annotation.PathParameter;
						public class DuplicateDeclaration {
							@GET("/items/{id}")
							@GET("/items/{id}")
							public String getById(@PathParameter String id) {
								return id;
							}
						}
						""");

		Compilation compilation = Compiler.javac()
				.withProcessors(new SokletProcessor())
				.compile(src);

		assertThat(compilation).succeeded();
	}

	@Test
	void rejectsDuplicatePlaceholders() {
		JavaFileObject src = JavaFileObjects.forSourceString("example.DupPlaceholders",
				"""
						import com.soklet.annotation.GET;
						
						public class DupPlaceholders {
							@GET("/x/{id}/y/{id}")
							public void widget(int id) {
								/* nothing to do */
							}
						}
						""");

		Compilation compilation = Compiler.javac()
				.withProcessors(new SokletProcessor())
				.compile(src);

		assertThat(compilation).failed();
		assertThat(compilation).hadErrorContaining("Duplicate @PathParameter name: id");
	}

	@Test
	void rejectsStaticMethod() {
		JavaFileObject src = JavaFileObjects.forSourceString("example.StaticBad",
				"""
						import com.soklet.annotation.GET;
						
						public class StaticBad {
							@GET("/x/{id}")
							public static void widget(int id) {
								/* nothing to do */
							}
						}
						""");

		Compilation compilation = Compiler.javac()
				.withProcessors(new SokletProcessor())
				.compile(src);

		assertThat(compilation).failed();
		assertThat(compilation).hadErrorContaining("Method must not be static");
	}

	@Test
	void rejectsBadPathTemplate() {
		JavaFileObject src = JavaFileObjects.forSourceString("example.BadPath",
				"""
						import com.soklet.annotation.GET;
						
						public class BadPath {
							@GET("/x/{id")
							public void widget(int id) { /* nothing to do */ }
						}
						""");

		Compilation compilation = Compiler.javac()
				.withProcessors(new SokletProcessor())
				.compile(src);

		assertThat(compilation).failed();
		assertThat(compilation).hadErrorContaining("Malformed resource path declaration (unbalanced braces)");
	}

	@Test
	void supportsVarargPathParameter() {
		JavaFileObject src = JavaFileObjects.forSourceString("example.VarargOk",
				"""
						import org.jspecify.annotations.NonNull;
						import com.soklet.annotation.GET;
						import com.soklet.annotation.PathParameter;
						
						public class VarargOk {
							@GET("/static/css/{cssPath*}")
							public void staticCssFile(@NonNull @PathParameter String cssPath) {
								/* ok */
							}
						}
						""");

		Compilation compilation = Compiler.javac()
				.withProcessors(new SokletProcessor())
				.compile(src);

		assertThat(compilation).succeeded();
	}

	@Test
	void rejectsUnboundVarargPlaceholder() {
		JavaFileObject src = JavaFileObjects.forSourceString("example.VarargBad",
				"""
						import org.jspecify.annotations.NonNull;
						import com.soklet.annotation.GET;
						import com.soklet.annotation.PathParameter;
						
						public class VarargBad {
							@GET("/static/css/{cssPath*}")
							public void staticCssFile(@NonNull @PathParameter String other) {
								/* name mismatch: 'other' != 'cssPath' */
							}
						}
						""");

		Compilation compilation = Compiler.javac()
				.withProcessors(new SokletProcessor())
				.compile(src);

		assertThat(compilation).failed();
		assertThat(compilation)
				.hadErrorContaining("Resource Method path parameter {cssPath*} not bound to a @PathParameter argument")
				.inFile(src);
	}

	@Test
	void removingFinalSokletAnnotationPrunesOnlyTouchedMcpIndexRow(
			@TempDir Path temporaryDirectory) throws IOException {
		Path sourceDirectory = temporaryDirectory.resolve("src/example");
		Path classDirectory = temporaryDirectory.resolve("classes");
		Path generatedDirectory = temporaryDirectory.resolve("generated");
		Files.createDirectories(sourceDirectory);
		Files.createDirectories(classDirectory);
		Files.createDirectories(generatedDirectory);
		Path firstEndpoint = sourceDirectory.resolve("AEndpoint.java");
		Path secondEndpoint = sourceDirectory.resolve("BEndpoint.java");
		Files.writeString(firstEndpoint, """
				package example;
				import com.soklet.annotation.McpServerEndpoint;
				@McpServerEndpoint(path="/a", name="a", version="1")
				public final class AEndpoint {}
				""", StandardCharsets.UTF_8);
		Files.writeString(secondEndpoint, """
				package example;
				import com.soklet.annotation.McpServerEndpoint;
				@McpServerEndpoint(path="/b", name="b", version="1")
				public final class BEndpoint {}
				""", StandardCharsets.UTF_8);

		Assertions.assertTrue(compileWithSokletProcessor(classDirectory,
				generatedDirectory, List.of(firstEndpoint, secondEndpoint)));
		Files.writeString(firstEndpoint, """
				package example;
				public final class AEndpoint {}
				""", StandardCharsets.UTF_8);
		Assertions.assertTrue(compileWithSokletProcessor(classDirectory,
				generatedDirectory, List.of(firstEndpoint)));

		Path index = classDirectory.resolve(
				"META-INF/soklet/mcp-endpoint-descriptor-providers");
		List<String> rows = Files.readAllLines(index, StandardCharsets.UTF_8);
		Assertions.assertEquals(1, rows.size());
		String[] fields = rows.get(0).split("\\|", -1);
		Assertions.assertEquals(5, fields.length);
		Assertions.assertEquals("example.BEndpoint", new String(
				Base64.getDecoder().decode(fields[1]), StandardCharsets.UTF_8));
		Assertions.assertEquals("example.BEndpoint", new String(
				Base64.getDecoder().decode(fields[3]), StandardCharsets.UTF_8));
		Assertions.assertEquals("/b", new String(
				Base64.getDecoder().decode(fields[4]), StandardCharsets.UTF_8));
	}

	private static boolean compileWithSokletProcessor(Path classes,
			Path generated, List<Path> sources)
			throws IOException {
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		Assertions.assertNotNull(compiler);
		try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(
				null, null, StandardCharsets.UTF_8)) {
			String classpath = classes + System.getProperty("path.separator")
					+ System.getProperty("java.class.path");
			JavaCompiler.CompilationTask task = compiler.getTask(null, fileManager,
					null, List.of("--release", "17", "-parameters",
						"-Asoklet.cacheMode=none", "-classpath", classpath,
						"-d", classes.toString(), "-s", generated.toString()),
					null, fileManager.getJavaFileObjectsFromPaths(sources));
			task.setProcessors(List.of(new SokletProcessor()));
			return task.call();
		}
	}

}
