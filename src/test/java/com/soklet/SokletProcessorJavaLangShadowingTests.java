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
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Application types may shadow java.lang names without changing generated infrastructure. */
@Timeout(60)
class SokletProcessorJavaLangShadowingTests {
	@Test
	void mcpProviderCompilesAndLoadsBesideAnApplicationClassType(@TempDir Path directory) throws Exception {
		assertMcpProviderSurvivesShadow(directory, "Class");
	}

	@Test
	void mcpProviderCompilesAndLoadsBesideAnApplicationStringType(@TempDir Path directory) throws Exception {
		assertMcpProviderSurvivesShadow(directory, "String");
	}

	@Test
	void httpIndexPreservesApplicationClassAndStringParameterTypes(@TempDir Path directory) throws Exception {
		try (URLClassLoader loader = compile(directory, Map.of(
				"Class", shadowRecord("Class"),
				"String", shadowRecord("String"),
				"Resource", """
						package shadow;
						public class Resource {
						  @com.soklet.annotation.GET("/shadow")
						  public java.lang.String value(
						      @com.soklet.annotation.QueryParameter Class first,
						      @com.soklet.annotation.QueryParameter String second) {
						    return first.value() + second.value();
						  }
						}
						"""))) {
			Method load = DefaultResourceMethodResolver.class.getDeclaredMethod(
					"fromClasspathIntrospection", ClassLoader.class);
			load.setAccessible(true);
			ResourceMethodResolver resolver = (ResourceMethodResolver) load.invoke(null, loader);
			assertEquals(1, resolver.getResourceMethods().size());
			Method route = resolver.getResourceMethods().iterator().next().getMethod();
			assertEquals(loader.loadClass("shadow.Resource"), route.getDeclaringClass());
			assertArrayEquals(new Class<?>[] { loader.loadClass("shadow.Class"),
					loader.loadClass("shadow.String") }, route.getParameterTypes());
			assertEquals(String.class, route.getReturnType());
		}
	}

	private static void assertMcpProviderSurvivesShadow(Path directory, String shadowName) throws Exception {
		try (URLClassLoader loader = compile(directory, Map.of(
				shadowName, shadowRecord(shadowName),
				"Endpoint", """
						package shadow;
						@com.soklet.annotation.McpServerEndpoint(
						    path="/mcp", name="shadow-test", version="1.0.0",
						    protocolVersions=com.soklet.McpProtocolVersion.V2026_07_28)
						public class Endpoint {
						  public record Greeting(java.lang.String message) {}
						  @com.soklet.annotation.McpTool(name="greet",
						      protocolVersions=com.soklet.McpProtocolVersion.V2026_07_28)
						  public Greeting greet(@com.soklet.annotation.McpToolArgument %s target) {
						    return new Greeting("Hello, " + target.value());
						  }
						}
						""".formatted(shadowName)))) {
			Class<?> endpointClass = loader.loadClass("shadow.Endpoint");
			McpEndpointRegistry registry = McpEndpointRegistry.fromClasses(endpointClass);
			assertEquals(1, registry.getEndpoints().size());
			McpEndpoint endpoint = registry.getEndpoints().getFirst();
			assertEquals("/mcp", endpoint.getPath());
			assertEquals("shadow-test", endpoint.getServerInfo().getName());
			assertEquals(1, endpoint.getToolRegistrations().size());
			McpToolRegistration<?> tool = endpoint.getToolRegistrations().getFirst();
			assertEquals("greet", tool.getName());
			Class<?> carrier = assertInstanceOf(Class.class, tool.getArgumentType());
			assertEquals(loader.loadClass("shadow." + shadowName),
					carrier.getRecordComponents()[0].getType(),
					"Only generated infrastructure should use java.lang types; application types retain their identity.");
		}
	}

	private static String shadowRecord(String name) {
		return "package shadow; public record " + name + "(java.lang.String value) {}";
	}

	private static URLClassLoader compile(Path directory, Map<String, String> sources) throws IOException {
		Path sourceDirectory = Files.createDirectories(directory.resolve("src/shadow"));
		Path classDirectory = Files.createDirectories(directory.resolve("classes"));
		Path generatedDirectory = Files.createDirectories(directory.resolve("generated"));
		List<Path> sourceFiles = new ArrayList<>();
		for (var source : sources.entrySet()) {
			Path file = sourceDirectory.resolve(source.getKey() + ".java");
			Files.writeString(file, source.getValue(), StandardCharsets.UTF_8);
			sourceFiles.add(file);
		}
		var compiler = ToolProvider.getSystemJavaCompiler();
		assertNotNull(compiler);
		DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
		try (var manager = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
			var task = compiler.getTask(null, manager, diagnostics,
					List.of("--release", "21", "-parameters", "-Asoklet.cacheMode=none",
							"-classpath", System.getProperty("java.class.path"),
							"-d", classDirectory.toString(), "-s", generatedDirectory.toString()),
					null, manager.getJavaFileObjectsFromPaths(sourceFiles));
			task.setProcessors(List.of(new SokletProcessor()));
			assertTrue(Boolean.TRUE.equals(task.call()), diagnostics.getDiagnostics().toString());
		}
		return new URLClassLoader(new URL[] { classDirectory.toUri().toURL() },
				SokletProcessorJavaLangShadowingTests.class.getClassLoader()) {
			@Override
			public Enumeration<URL> getResources(String name) throws IOException {
				if (name.equals("META-INF/soklet/resource-method-lookup-table")
						|| name.equals("META-INF/soklet/mcp-endpoint-descriptor-providers"))
					return findResources(name);
				return super.getResources(name);
			}
		};
	}
}
