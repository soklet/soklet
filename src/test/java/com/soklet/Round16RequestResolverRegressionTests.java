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

import com.soklet.annotation.GET;
import com.soklet.annotation.QueryParameter;
import com.soklet.exception.IllegalRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import java.lang.reflect.Method;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class Round16RequestResolverRegressionTests {
	private static final AtomicInteger TARGET_INVOCATIONS = new AtomicInteger();

	@Test
	void rawRequestFactoriesAndBuilderUpdatesRejectLiteralFragments() {
		for (String target : malformedTargets()) {
			IllegalRequestException failure = assertThrows(IllegalRequestException.class,
					() -> Request.fromRawUrl(HttpMethod.GET, target));
			assertFalse(failure.getMessage().contains("secret"));
			assertFalse(failure.getMessage().contains("%FF"));
			assertNull(failure.getCause());
			assertThrows(IllegalRequestException.class, () -> Request.withRawUrl(HttpMethod.GET, target).build());
			assertThrows(IllegalRequestException.class, () -> Request.withRawUrl(HttpMethod.GET, "/admin").rawUrl(target).build());
		}
		assertThrows(IllegalRequestException.class, () -> Request.fromRawUrl(HttpMethod.OPTIONS, "*#secret"));
	}

	@Test
	void decodedHashesAndEncodedHashRequestTargetsRemainValid() {
		Request decoded = Request.withPath(HttpMethod.GET, "/literal#name")
				.queryParameters(Map.of("selected", List.of("one#two"))).build();
		assertEquals("/literal%23name?selected=one%23two", decoded.getRawPathAndQuery());
		for (String target : List.of(decoded.getRawPathAndQuery(),
				"http://example.test" + decoded.getRawPathAndQuery())) {
			Request raw = Request.fromRawUrl(HttpMethod.GET, target);
			assertEquals(decoded.getPath(), raw.getPath());
			assertEquals(decoded.getQueryParameters(), raw.getQueryParameters());
			assertEquals(decoded.getRawPathAndQuery(), raw.getRawPathAndQuery());
			assertEquals(raw.getRawPathAndQuery(), raw.copy().finish().getRawPathAndQuery());
		}
		// General URL extraction still accepts fragments; raw HTTP targets do not.
		assertEquals("/admin", Utilities.extractPathFromUrl("/admin#fragment", true));
		assertEquals(List.of("ok"), Utilities.extractQueryParametersFromUrl("/?selected=ok#fragment",
				QueryFormat.RFC_3986_STRICT).get("selected"));
	}

	@Test
	void liveHttpRejectsFragmentsBeforeDispatchAndAcceptsEncodedHashes() throws Exception {
		int port = TestSupport.findFreePort();
		TARGET_INVOCATIONS.set(0);
		SokletConfig config = SokletConfig.withHttpServer(HttpServer.withPort(port).host("127.0.0.1").concurrency(1).build())
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(TargetResource.class)))
				.lifecyclePolicy(LifecyclePolicy.builder().startupTimeout(Duration.ofSeconds(5))
						.startupCancelationTimeout(Duration.ofSeconds(2)).gracefulShutdownTimeout(Duration.ofSeconds(1))
						.forcedShutdownTimeout(Duration.ofSeconds(1)).build())
				.lifecycleObserver(new LifecycleObserver() {
					@Override public void didReceiveLogEvent(LogEvent event) {}
				}).build();
		try (Soklet soklet = Soklet.fromConfig(config)) {
			soklet.start();
			for (String target : malformedTargets()) {
				String wire = exchange(port, target);
				assertTrue(wire.startsWith("HTTP/1.1 400"), wire);
				assertEquals(0, TARGET_INVOCATIONS.get());
			}
			for (String target : List.of("/literal%23name?selected=one%23two",
					"http://example.test/literal%23name?selected=one%23two")) {
				String wire = exchange(port, target);
				assertTrue(wire.startsWith("HTTP/1.1 200"), wire);
				assertEquals("one#two", wire.substring(wire.indexOf("\r\n\r\n") + 4));
			}
			assertEquals(2, TARGET_INVOCATIONS.get());
		}
	}

	@Test
	void automaticDiscoveryUsesTheRealGenericAndCovariantDeclarations() throws Exception {
		assertTrue(Arrays.stream(GenericResource.class.getDeclaredMethods()).anyMatch(Method::isBridge));
		assertTrue(Arrays.stream(CovariantResource.class.getDeclaredMethods()).anyMatch(Method::isBridge));
		ResourceMethodResolver resolver = ResourceMethodResolver.fromClasses(Set.of(GenericResource.class, CovariantResource.class));
		assertEquals(2, resolver.getResourceMethods().size());
		assertTrue(resolver.getResourceMethods().stream().noneMatch(route -> route.getMethod().isBridge()));
		assertEquals(GenericResource.class.getDeclaredMethod("handle", String.class),
				resolver.resourceMethodForRequest(Request.fromRawUrl(HttpMethod.GET, "/generic?value=hello"), ServerType.HTTP)
						.orElseThrow().getMethod());
		assertResponses(resolver);
	}

	@Test
	void explicitMethodSelectionRetainsTheSelectedBridge() throws Exception {
		Method bridge = Arrays.stream(CovariantResource.class.getDeclaredMethods()).filter(Method::isBridge).findFirst().orElseThrow();
		Method real = CovariantResource.class.getDeclaredMethod("get");
		ResourceMethodResolver explicit = ResourceMethodResolver.fromMethods(Set.of(bridge));
		assertEquals(1, explicit.getResourceMethods().size());
		assertEquals(bridge, explicit.resourceMethodForRequest(Request.fromPath(HttpMethod.GET, "/covariant"), ServerType.HTTP)
				.orElseThrow().getMethod());
		assertThrows(IllegalStateException.class, () -> ResourceMethodResolver.fromMethods(Set.of(bridge, real)));
		SokletSimulator.run(SimulatorConfig.builder().httpServer().resourceMethodResolver(explicit).build(), simulator ->
				assertEquals("covariant", responseBody(simulator.performHttpRequest(Request.fromPath(HttpMethod.GET, "/covariant")))));
	}

	@Test
	void javacIndexedAndReflectiveGenericResourcesHaveTheSameRoutes(@TempDir Path directory) throws Exception {
		Path source = directory.resolve("BridgeParity.java");
		Files.writeString(source, """
				package round16;
				import com.soklet.*;
				import com.soklet.annotation.*;
				import java.net.*;
				import java.util.*;
				public class BridgeParity {
				  interface GenericApi<T> { T handle(T value); }
				  interface ResultFactory<T> { T get(); }
				  public static class Resource implements GenericApi<String> {
				    @GET("/generic") public String handle(@QueryParameter(name="value") String value) { return value; }
				  }
				  public static class Covariant implements ResultFactory<String> {
				    @GET("/covariant") public String get() { return "covariant"; }
				  }
				  public static void main(String[] args) throws Exception {
				    ClassLoader parent=Thread.currentThread().getContextClassLoader();
				    try(URLClassLoader loader=new URLClassLoader(new URL[]{new URL(args[0])},parent) {
				      @Override public Enumeration<URL> getResources(String name) throws java.io.IOException {
				        return name.equals("META-INF/soklet/resource-method-lookup-table") ? findResources(name) : super.getResources(name);
				      }
				    }) {
				      Thread.currentThread().setContextClassLoader(loader);
				      var indexed=ResourceMethodResolver.fromClasspathIntrospection();
				      var reflected=ResourceMethodResolver.fromClasses(Set.of(Resource.class,Covariant.class));
				      if(indexed.getResourceMethods().size()!=2 || !indexed.getResourceMethods().equals(reflected.getResourceMethods()))
				        throw new AssertionError("Indexed and reflective routes differ");
				      if(reflected.getResourceMethods().stream().anyMatch(route->route.getMethod().isBridge()))
				        throw new AssertionError("Compiler bridge registered as an automatic route");
				      for(var resolver:List.of(indexed,reflected))
				        SokletSimulator.run(SimulatorConfig.builder().httpServer().resourceMethodResolver(resolver).build(), simulator -> {
				          for(String path:List.of("/generic?value=hello","/covariant")) {
				            var response=simulator.performHttpRequest(Request.fromRawUrl(HttpMethod.GET,path)).getMarshaledResponse();
				            String body=new String(((MarshaledResponseBody.Bytes)response.getBody().orElseThrow()).getBytes(),java.nio.charset.StandardCharsets.UTF_8);
				            if(response.getStatusCode()!=200 || !body.equals(path.startsWith("/generic")?"hello":"covariant"))
				              throw new AssertionError("Unexpected generic response");
				          }
				        });
				      Thread.currentThread().setContextClassLoader(parent);
				    }
				    System.out.println("PASS indexed/reflection generic and covariant routes");
				  }
				}
				""", StandardCharsets.UTF_8);
		Path classes = Files.createDirectories(directory.resolve("classes"));
		var compiler = ToolProvider.getSystemJavaCompiler();
		assertNotNull(compiler);
		var diagnostics = new DiagnosticCollector<JavaFileObject>();
		try (var files = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
			var task = compiler.getTask(null, files, diagnostics,
					List.of("--release", "21", "-parameters", "-classpath", System.getProperty("java.class.path"),
							"-d", classes.toString(), "-Asoklet.cacheMode=none"), null, files.getJavaFileObjects(source));
			task.setProcessors(List.of(new SokletProcessor()));
			assertTrue(task.call(), diagnostics.getDiagnostics().toString());
		}
		Path log = directory.resolve("runtime.log");
		String classpath = classes + System.getProperty("path.separator") + System.getProperty("java.class.path");
		Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
				"-cp", classpath, "round16.BridgeParity", classes.toUri().toString())
				.redirectErrorStream(true).redirectOutput(log.toFile()).start();
		try {
			assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Generic route subprocess did not finish");
			assertEquals(0, process.exitValue(), Files.readString(log));
			assertTrue(Files.readString(log).contains("PASS indexed/reflection"));
		} finally {
			if (process.isAlive()) process.destroyForcibly();
		}
	}

	private static List<String> malformedTargets() {
		return List.of("/admin#secret", "/admin?selected=ok#secret&unselected=%FF",
				"//admin#secret", "http://example.test/admin#secret",
				"http://example.test/admin?selected=ok#secret&unselected=%FF");
	}

	private static String exchange(int port, String target) throws Exception {
		try (Socket socket = new Socket("127.0.0.1", port)) {
			socket.setSoTimeout(5_000);
			socket.getOutputStream().write(("GET " + target + " HTTP/1.1\r\nHost: example.test\r\nConnection: close\r\n\r\n")
					.getBytes(StandardCharsets.ISO_8859_1));
			socket.getOutputStream().flush();
			return new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
		}
	}

	private static void assertResponses(ResourceMethodResolver resolver) {
		SokletSimulator.run(SimulatorConfig.builder().httpServer().resourceMethodResolver(resolver).build(), simulator -> {
			assertEquals("hello", responseBody(simulator.performHttpRequest(Request.fromRawUrl(HttpMethod.GET, "/generic?value=hello"))));
			assertEquals("covariant", responseBody(simulator.performHttpRequest(Request.fromPath(HttpMethod.GET, "/covariant"))));
		});
	}

	private static String responseBody(HttpRequestResult result) {
		assertEquals(200, result.getMarshaledResponse().getStatusCode());
		return new String(((MarshaledResponseBody.Bytes) result.getMarshaledResponse().getBody().orElseThrow()).getBytes(), StandardCharsets.UTF_8);
	}

	interface GenericApi<T> { T handle(T value); }
	interface ResultFactory<T> { T get(); }
	public static class GenericResource implements GenericApi<String> {
		@GET("/generic") public String handle(@QueryParameter(name = "value") String value) { return value; }
	}
	public static class CovariantResource implements ResultFactory<String> {
		@GET("/covariant") public String get() { return "covariant"; }
	}
	public static class TargetResource {
		@GET("/admin") public String admin() { TARGET_INVOCATIONS.incrementAndGet(); return "admin"; }
		@GET("/literal#name") public String literal(@QueryParameter(name = "selected") String selected) {
			TARGET_INVOCATIONS.incrementAndGet(); return selected;
		}
	}
}
