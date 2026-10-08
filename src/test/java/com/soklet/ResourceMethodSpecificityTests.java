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

import javax.tools.ToolProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(30)
public class ResourceMethodSpecificityTests {

	@Test
	void specificVarargsPrefixesBeatCatchAlls() {
		ResourceMethodResolver resolver = ResourceMethodResolver.fromClasses(Set.of(PrefixRoutes.class));
		assertRoute(resolver, "/widgets/42/details", ServerType.HTTP, "widget");
		assertRoute(resolver, "/api/v1/42/details", ServerType.HTTP, "versioned");
		assertRoute(resolver, "/a/b/c/details", ServerType.HTTP, "nested");
		assertRoute(resolver, "/api/other", ServerType.HTTP, "api");
		assertRoute(resolver, "/elsewhere", ServerType.HTTP, "catchAll");
	}

	@Test
	void varargsCompareComponentPositionsBeforeTotals() {
		ResourceMethodResolver resolver = ResourceMethodResolver.fromClasses(Set.of(PositionalRoutes.class));
		assertRoute(resolver, "/a/b/file", ServerType.HTTP, "literalFirst");
		assertRoute(resolver, "/z/b/file", ServerType.HTTP, "literalSecond");
		// The longer prefix does not defeat a literal at an earlier component.
		ResourceMethodResolver longer = ResourceMethodResolver.fromClasses(Set.of(LongerRoutes.class));
		assertRoute(longer, "/a/b/c/file", ServerType.HTTP, "earlyLiteral");
		assertRoute(longer, "/a/z/c/file", ServerType.HTTP, "longerPrefix");
	}

	@Test
	void fixedRoutesStillBeatVarargsRoutes() {
		ResourceMethodResolver resolver = ResourceMethodResolver.fromClasses(Set.of(FixedRoutes.class));
		assertRoute(resolver, "/a/b", ServerType.HTTP, "fixed");
		assertRoute(resolver, "/a/b/c", ServerType.HTTP, "varargs");
	}

	@Test
	void equallySpecificOverlappingVarargsRemainAmbiguous() {
		IllegalStateException failure = assertThrows(IllegalStateException.class,
				() -> ResourceMethodResolver.fromClasses(Set.of(AmbiguousRoutes.class)));
		assertTrue(failure.getMessage().contains("Ambiguous"));
		assertTrue(failure.getMessage().contains("/{x}/{rest*}"));
		assertTrue(failure.getMessage().contains("/{y}/{tail*}"));
	}

	@Test
	void disjointLiteralRoutesAndHttpSsePartitionsRemainIndependent() {
		ResourceMethodResolver resolver = ResourceMethodResolver.fromClasses(Set.of(PartitionedRoutes.class));
		assertRoute(resolver, "/a/file", ServerType.HTTP, "httpA");
		assertRoute(resolver, "/b/file", ServerType.HTTP, "httpB");
		assertRoute(resolver, "/a/file", ServerType.SSE, "sseA");
		assertRoute(resolver, "/b/file", ServerType.SSE, "sseB");
	}

	@Test
	void compilerAcceptsPositionalVarargsPrecedence() {
		var source = JavaFileObjects.forSourceString("example.Ordered", """
				package example;
				import com.soklet.annotation.GET;
				import com.soklet.annotation.PathParameter;
				import com.soklet.annotation.SseEventSource;
				import com.soklet.SseHandshakeResult;
				public class Ordered {
				  @GET("/a/{x}/{rest*}") public String first(@PathParameter String x, @PathParameter String rest) { return "first"; }
				  @GET("/{x}/b/{rest*}") public String second(@PathParameter String x, @PathParameter String rest) { return "second"; }
				  @GET("/{rest*}") public String catchAll(@PathParameter String rest) { return "catchAll"; }
				  @SseEventSource("/a/{x}/{rest*}") public SseHandshakeResult events(@PathParameter String x, @PathParameter String rest) { return SseHandshakeResult.accept(); }
				}
				""");
		assertThat(Compiler.javac().withProcessors(new SokletProcessor()).compile(source)).succeeded();
	}

	@Test
	void compilerRetainsVarargsAndFixedRouteAmbiguityDiagnostics() {
		for (String[] paths : List.of(new String[]{"/{x}/{rest*}", "/{y}/{tail*}"},
				new String[]{"/a/{x}", "/{y}/b"})) {
			String firstParameters = "@PathParameter String x" + (paths[0].contains("*") ? ", @PathParameter String rest" : "");
			String secondParameters = "@PathParameter String y" + (paths[1].contains("*") ? ", @PathParameter String tail" : "");
			var source = JavaFileObjects.forSourceString("example.Ambiguous", """
					package example;
					import com.soklet.annotation.GET;
					import com.soklet.annotation.PathParameter;
					public class Ambiguous {
					  @GET("%s") public String first(%s) { return "first"; }
					  @GET("%s") public String second(%s) { return "second"; }
					}
					""".formatted(paths[0], firstParameters, paths[1], secondParameters));
			var compilation = Compiler.javac().withProcessors(new SokletProcessor()).compile(source);
			assertThat(compilation).failed();
			assertThat(compilation).hadErrorContaining("Ambiguous resource method declarations");
		}
	}

	@Test
	void compiledIndexAndExplicitResolversSelectAndInvokeTheSameHandlers(@TempDir Path temporaryDirectory) throws Exception {
		Path source = temporaryDirectory.resolve("Routes.java");
		Path classes = Files.createDirectories(temporaryDirectory.resolve("classes"));
		Path generated = Files.createDirectories(temporaryDirectory.resolve("generated"));
		Files.writeString(source, GENERATED_ROUTES, StandardCharsets.UTF_8);
		var compiler = ToolProvider.getSystemJavaCompiler();
		assertNotNull(compiler);
		try (var manager = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
			var task = compiler.getTask(null, manager, null,
					List.of("--release", "17", "-parameters", "-Asoklet.cacheMode=none",
							"-classpath", System.getProperty("java.class.path"), "-d", classes.toString(), "-s", generated.toString()),
					null, manager.getJavaFileObjectsFromPaths(List.of(source)));
			task.setProcessors(List.of(new SokletProcessor()));
			assertTrue(task.call(), "The route fixture must compile with SokletProcessor");
		}
		assertTrue(Files.isRegularFile(classes.resolve(SokletProcessor.RESOURCE_METHOD_LOOKUP_TABLE_PATH)));
		// A fresh JVM exercises the actual public singleton without contaminating another test's classpath snapshot.
		Path output = temporaryDirectory.resolve("verification.log");
		Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
				"-cp", classes + System.getProperty("path.separator") + System.getProperty("java.class.path"),
				"example.Routes").redirectErrorStream(true).redirectOutput(output.toFile()).start();
		try {
			assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Route verification subprocess must finish");
			assertEquals(0, process.exitValue(), Files.readString(output));
		} finally {
			if (process.isAlive()) process.destroyForcibly();
		}
	}

	private static void assertRoute(ResourceMethodResolver resolver, String path, ServerType serverType, String method) {
		ResourceMethod selected = resolver.resourceMethodForRequest(Request.withPath(HttpMethod.GET, path).build(), serverType).orElseThrow();
		assertEquals(method, selected.getMethod().getName(), path);
	}

	public static class PrefixRoutes {
		@GET("/{rest*}") public String catchAll() { return "catchAll"; }
		@GET("/widgets/{id}/{rest*}") public String widget() { return "widget"; }
		@GET("/api/{rest*}") public String api() { return "api"; }
		@GET("/api/v1/{id}/{rest*}") public String versioned() { return "versioned"; }
		@GET("/a/{rest*}") public String a() { return "a"; }
		@GET("/a/{x}/c/{rest*}") public String nested() { return "nested"; }
	}
	public static class PositionalRoutes {
		@GET("/a/{x}/{rest*}") public String literalFirst() { return "first"; }
		@GET("/{x}/b/{rest*}") public String literalSecond() { return "second"; }
	}
	public static class LongerRoutes {
		@GET("/a/b/{rest*}") public String earlyLiteral() { return "early"; }
		@GET("/a/{x}/c/{rest*}") public String longerPrefix() { return "longer"; }
	}
	public static class FixedRoutes {
		@GET("/{x}/{y}") public String fixed() { return "fixed"; }
		@GET("/a/{rest*}") public String varargs() { return "varargs"; }
	}
	public static class AmbiguousRoutes {
		@GET("/{x}/{rest*}") public String first() { return "first"; }
		@GET("/{y}/{tail*}") public String second() { return "second"; }
	}
	public static class PartitionedRoutes {
		@GET("/a/{rest*}") public String httpA() { return "httpA"; }
		@GET("/b/{rest*}") public String httpB() { return "httpB"; }
		@SseEventSource("/a/{rest*}") public SseHandshakeResult sseA() { return SseHandshakeResult.accept(); }
		@SseEventSource("/b/{rest*}") public SseHandshakeResult sseB() { return SseHandshakeResult.accept(); }
	}

	private static final String GENERATED_ROUTES = """
			package example;
			import com.soklet.*;
			import com.soklet.annotation.GET;
			import com.soklet.annotation.PathParameter;
			import java.util.*;
			import java.net.*;
			import java.nio.charset.StandardCharsets;
			public class Routes {
			  @GET("/{rest*}") public String catchAll(@PathParameter String rest) { return "catchAll"; }
			  @GET("/widgets/{id}/{rest*}") public String widget(@PathParameter String id, @PathParameter String rest) { return "widget"; }
			  @GET("/api/{rest*}") public String api(@PathParameter String rest) { return "api"; }
			  @GET("/api/v1/{id}/{rest*}") public String versioned(@PathParameter String id, @PathParameter String rest) { return "versioned"; }
			  @GET("/a/{x}/{rest*}") public String literalFirst(@PathParameter String x, @PathParameter String rest) { return "literalFirst"; }
			  @GET("/{x}/b/{rest*}") public String literalSecond(@PathParameter String x, @PathParameter String rest) { return "literalSecond"; }
			  public static void main(String[] arguments) throws Exception {
			    URL location = Routes.class.getProtectionDomain().getCodeSource().getLocation();
			    try (URLClassLoader loader = new URLClassLoader(new URL[]{location}, Routes.class.getClassLoader()) {
			      @Override public Enumeration<URL> getResources(String name) throws java.io.IOException {
			        return name.equals("META-INF/soklet/resource-method-lookup-table") ? findResources(name) : super.getResources(name);
			      }
			    }) {
			      Thread.currentThread().setContextClassLoader(loader);
			      Set<java.lang.reflect.Method> methods = new HashSet<>(Arrays.asList(Routes.class.getDeclaredMethods()));
			      for (ResourceMethodResolver resolver : List.of(ResourceMethodResolver.fromClasspathIntrospection(),
			          ResourceMethodResolver.fromClasses(Set.of(Routes.class)), ResourceMethodResolver.fromMethods(methods))) {
			        SokletConfig config = SokletConfig.withHttpServer(HttpServer.withPort(0).build()).resourceMethodResolver(resolver).build();
			        SokletSimulator.run(SimulatorConfig.fromSokletConfig(config), simulator -> {
			          for (String[] sample : List.of(new String[]{"/widgets/42/details", "widget"},
			              new String[]{"/api/v1/42/details", "versioned"}, new String[]{"/api/other", "api"},
			              new String[]{"/elsewhere", "catchAll"},
			              new String[]{"/a/b/file", "literalFirst"}, new String[]{"/z/b/file", "literalSecond"})) {
			            Request request = Request.withPath(HttpMethod.GET, sample[0]).build();
			            HttpRequestResult result = simulator.performHttpRequest(request);
			            String method = result.getResourceMethod().orElseThrow().getMethod().getName();
			            String body = new String(((MarshaledResponseBody.Bytes) result.getMarshaledResponse().getBody().orElseThrow()).getBytes(), StandardCharsets.UTF_8);
			            if (result.getMarshaledResponse().getStatusCode() != 200 || !sample[1].equals(method) || !sample[1].equals(body))
			              throw new AssertionError(sample[0] + " resolved to " + method + " with body " + body);
			          }
			        });
			      }
			    }
			  }
			}
			""";
}
