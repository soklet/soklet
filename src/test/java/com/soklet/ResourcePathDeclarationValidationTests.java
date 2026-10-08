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

import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import com.soklet.annotation.GET;
import com.soklet.annotation.SseEventSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(30)
public class ResourcePathDeclarationValidationTests {

	private static final List<String> MALFORMED_PATHS = List.of(
			"/users/prefix{id}", "/users/{id}suffix", "/users/{id}{other}",
			"/users/{{id}}", "/users/{id", "/users/id}", "/users/{}", "/users/{*}",
			"/users/{id/other}", "/users/{rest*}/details", "/{first*}/{second*}");

	@Test
	void malformedDeclarationsFailAtThePublicFactoryWithoutEchoingValues() {
		for (String path : MALFORMED_PATHS) {
			IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
					() -> ResourcePathDeclaration.fromPath(path), path);
			assertFalse(failure.getMessage().contains(path), failure.getMessage());
		}
		IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
				() -> ResourcePathDeclaration.fromPath("/PRIVATE_CONFIG/prefix{PRIVATE_NAME}"));
		assertFalse(failure.getMessage().contains("PRIVATE_"));
		assertEquals(null, failure.getCause());
	}

	@Test
	void simpleAndVarargsNamesShareDuplicateValidation() {
		for (String path : List.of("/{id}/{id}", "/{id}/{id*}", "/{id*}/{id}"))
			assertThrows(IllegalArgumentException.class, () -> ResourcePathDeclaration.fromPath(path), path);
	}

	@Test
	void compilerReportsMalformedPathsOnHttpAndSseMethods() {
		for (String annotation : List.of("GET", "SseEventSource")) {
			for (String path : MALFORMED_PATHS.subList(0, 9)) {
				var source = routeSource(annotation, path, false);
				var compilation = Compiler.javac().withProcessors(new SokletProcessor()).compile(source);
				assertThat(compilation).failed();
				String reason = path.contains("prefix") || path.contains("suffix") ? "entire path component"
						: path.endsWith("/{}") || path.endsWith("/{*}") ? "must not be empty" : "Malformed resource path declaration";
				assertThat(compilation).hadErrorContaining(reason).inFile(source).onLine(7);
			}
		}
	}

	@Test
	void compilerReportsAnIsolatedNonFinalVarargsRoute() {
		assertNonFinalVarargsDiagnosed(false);
	}

	@Test
	void compilerReportsNonFinalVarargsAlongsideOtherRoutes() {
		assertNonFinalVarargsDiagnosed(true);
	}

	private static void assertNonFinalVarargsDiagnosed(boolean neighbor) {
		for (String annotation : List.of("GET", "SseEventSource")) {
			var source = routeSource(annotation, "/users/{rest*}/details", neighbor);
			var compilation = Compiler.javac().withProcessors(new SokletProcessor()).compile(source);
			assertThat(compilation).failed();
			assertThat(compilation).hadErrorContaining("Varargs placeholder must be the last component").inFile(source).onLine(7);
		}
	}

	@Test
	void compilerRejectsDuplicateSimpleAndVarargsNames() {
		for (String annotation : List.of("GET", "SseEventSource")) {
			var source = routeSource(annotation, "/{id}/{id*}", false);
			var compilation = Compiler.javac().withProcessors(new SokletProcessor()).compile(source);
			assertThat(compilation).failed();
			assertThat(compilation).hadErrorContaining("Duplicate placeholder").inFile(source).onLine(7);
		}
	}

	@Test
	void repeatableAnnotationsCannotHideAnInvalidDeclaration() {
		for (String annotation : List.of("GET", "SseEventSource")) {
			var source = JavaFileObjects.forSourceString("example.Routes", """
					package example;
					import com.soklet.annotation.*;
					import com.soklet.SseHandshakeResult;
					public class Routes {
					  @%s("/valid/{id}") @%s("/prefix{id}")
					  public %s route(@PathParameter String id) { return %s; }
					}
					""".formatted(annotation, annotation, returnType(annotation), returnValue(annotation)));
			var compilation = Compiler.javac().withProcessors(new SokletProcessor()).compile(source);
			assertThat(compilation).failed();
			assertThat(compilation).hadErrorContaining("entire path component").inFile(source).onLine(6);
		}
	}

	@Test
	void validNormalizedPathsAndOpaqueNamesRemainSupported() {
		ResourcePathDeclaration declaration = ResourcePathDeclaration.fromPath("  users//{item-id}/{tail*}/  ");
		assertEquals("/users/{item-id}/{tail*}", declaration.getPath());
		assertTrue(declaration.matches(ResourcePath.fromPath("/users/42/a/b")));
		assertEquals("tail", declaration.getVarargsComponent().orElseThrow().getValue());
		assertTrue(ResourcePathDeclaration.fromPath("/percent%7Bid%7D").isLiteral());
		assertEquals("two words", ResourcePathDeclaration.fromPath("/{two words}").getComponents().get(0).getValue());
		assertEquals("internal*name", ResourcePathDeclaration.fromPath("/{internal*name}").getComponents().get(0).getValue());
		assertTrue(ResourcePathDeclaration.fromPath("/").matches(ResourcePath.fromPath("/")));
		for (String annotation : List.of("GET", "SseEventSource")) {
			var source = JavaFileObjects.forSourceString("example.Valid", """
					package example;
					import com.soklet.annotation.*;
					import com.soklet.SseHandshakeResult;
					public class Valid {
					  @%s("/") public %s root() { return %s; }
					  @%s("/literal/file.json") public %s literal() { return %s; }
					  @%s("/users//{item-id}/{tail*}/") @%s("/aliases/{item-id}/{tail*}")
					  public %s route(@PathParameter(name="item-id") String id, @PathParameter String tail) { return %s; }
					}
					""".formatted(annotation, returnType(annotation), returnValue(annotation),
					annotation, returnType(annotation), returnValue(annotation), annotation, annotation,
					returnType(annotation), returnValue(annotation)));
			assertThat(Compiler.javac().withProcessors(new SokletProcessor()).compile(source)).succeeded();
		}
	}

	@Test
	void explicitClassAndMethodResolversRejectMalformedPaths() {
		for (Class<?> type : List.of(PartialHttp.class, PartialSse.class, NonFinalHttp.class)) {
			assertThrows(IllegalArgumentException.class, () -> ResourceMethodResolver.fromClasses(Set.of(type)), type.getName());
			assertThrows(IllegalArgumentException.class,
					() -> ResourceMethodResolver.fromMethods(Set.copyOf(Arrays.asList(type.getDeclaredMethods()))), type.getName());
		}
	}

	@Test
	void malformedIncrementalCompilationPreservesTheLastGoodIndex(@TempDir Path directory) throws Exception {
		Path classes = Files.createDirectories(directory.resolve("classes"));
		Path source = directory.resolve("Routes.java");
		Files.writeString(source, """
				package example; import com.soklet.annotation.GET;
				public class Routes { @GET("/good") public void route() {} }
				""");
		assertTrue(compile(source, classes, true));
		Path index = classes.resolve(SokletProcessor.RESOURCE_METHOD_LOOKUP_TABLE_PATH);
		String before = Files.readString(index);
		Files.writeString(source, """
				package example; import com.soklet.annotation.*;
				public class Routes { @GET("/{rest*}/bad") public void route(@PathParameter String rest) {} }
				""");
		assertFalse(compile(source, classes, true));
		assertEquals(before, Files.readString(index));
	}

	@Test
	void stalePackagedIndexRejectsMalformedPathsBeforeRouteUse(@TempDir Path directory) throws Exception {
		Path classes = Files.createDirectories(directory.resolve("classes"));
		Path source = directory.resolve("Routes.java");
		Files.writeString(source, """
				package example;
				import com.soklet.*;
				import java.net.*;
				import java.util.*;
				public class Routes {
				  public void route() {}
				  public static void main(String[] arguments) throws Exception {
				    URL root = Routes.class.getProtectionDomain().getCodeSource().getLocation();
				    try (URLClassLoader loader = new URLClassLoader(new URL[]{root}, Routes.class.getClassLoader()) {
				      @Override public Enumeration<URL> getResources(String name) throws java.io.IOException {
				        return name.equals("META-INF/soklet/resource-method-lookup-table") ? findResources(name) : super.getResources(name);
				      }
				    }) {
				      Thread.currentThread().setContextClassLoader(loader);
				      try {
				        ResourceMethodResolver.fromClasspathIntrospection().getResourceMethods();
				        throw new AssertionError("Malformed packaged route must fail before serving");
				      } catch (IllegalArgumentException expected) {
				        if (!expected.getMessage().contains("entire path component")) throw expected;
				      }
				    }
				  }
				}
				""");
		assertTrue(compile(source, classes, false));
		Path index = classes.resolve(SokletProcessor.RESOURCE_METHOD_LOOKUP_TABLE_PATH);
		Files.createDirectories(index.getParent());
		Files.writeString(index, "GET|" + encoded("/prefix{id}") + "|" + encoded("example.Routes") + "|" + encoded("route") + "||false\n");
		Path output = directory.resolve("verification.log");
		Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
				"-Xmx128m", "-XX:ActiveProcessorCount=2", "-cp", classes + System.getProperty("path.separator") + System.getProperty("java.class.path"),
				"example.Routes").redirectErrorStream(true).redirectOutput(output.toFile()).start();
		try {
			assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Route verification subprocess must finish");
			assertEquals(0, process.exitValue(), Files.readString(output));
		} finally {
			if (process.isAlive()) process.destroyForcibly();
		}
	}

	private static JavaFileObject routeSource(String annotation, String path, boolean neighbor) {
		return JavaFileObjects.forSourceString("example.Routes", """
				package example;
				import com.soklet.annotation.*;
				import com.soklet.SseHandshakeResult;
				public class Routes {
				
				  @%s("%s")
				  public %s route(@PathParameter String %s) { return %s; }
				  %s
				}
				""".formatted(annotation, path, returnType(annotation), path.contains("rest") ? "rest" : "id", returnValue(annotation),
				neighbor ? "@" + annotation + "(\"/other\") public " + returnType(annotation) + " other() { return " + returnValue(annotation) + "; }" : ""));
	}

	private static String returnType(String annotation) { return annotation.equals("GET") ? "String" : "SseHandshakeResult"; }
	private static String returnValue(String annotation) { return annotation.equals("GET") ? "\"ok\"" : "SseHandshakeResult.accept()"; }
	private static String encoded(String text) { return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8)); }

	private static boolean compile(Path source, Path classes, boolean process) throws Exception {
		var compiler = ToolProvider.getSystemJavaCompiler();
		assertNotNull(compiler);
		var diagnostics = new DiagnosticCollector<JavaFileObject>();
		try (var manager = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
			var task = compiler.getTask(null, manager, diagnostics, process
					? List.of("--release", "17", "-parameters", "-classpath", System.getProperty("java.class.path"), "-d", classes.toString(), "-Asoklet.cacheMode=none")
					: List.of("--release", "17", "-proc:none", "-classpath", System.getProperty("java.class.path"), "-d", classes.toString()),
					null, manager.getJavaFileObjectsFromPaths(List.of(source)));
			if (process) task.setProcessors(List.of(new SokletProcessor()));
			return task.call();
		}
	}

	public static class PartialHttp { @GET("/prefix{id}") public void route() {} }
	public static class PartialSse { @SseEventSource("/prefix{id}") public SseHandshakeResult route() { return SseHandshakeResult.accept(); } }
	public static class NonFinalHttp { @GET("/{rest*}/bad") public void route() {} }
}
