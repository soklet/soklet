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
import com.soklet.annotation.PathParameter;
import com.soklet.annotation.QueryParameter;
import com.soklet.annotation.SseEventSource;
import com.soklet.converter.ValueConversionException;
import com.soklet.converter.ValueConverter;
import com.soklet.converter.ValueConverterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
public class EmptyVarargsBindingTests {

	@Test
	void zeroSegmentSuffixMatchesAndExtractsEmptyStringInBothDirections() {
		for (String[] sample : List.of(new String[]{"/{tail*}", "/"},
				new String[]{"/assets/{tail*}", "/assets"},
				new String[]{"/assets/{tail*}", "/assets/"},
				new String[]{"/widgets/{id}/{tail*}", "/widgets/42"})) {
			ResourcePathDeclaration declaration = ResourcePathDeclaration.fromPath(sample[0]);
			ResourcePath path = ResourcePath.fromPath(sample[1]);
			assertTrue(declaration.matches(path));
			assertTrue(path.matches(declaration));
			Map<String, String> expected = sample[0].contains("{id}")
					? Map.of("id", "42", "tail", "") : Map.of("tail", "");
			assertEquals(expected, declaration.extractPlaceholders(path));
			assertEquals(expected, path.extractPlaceholders(declaration));
		}
	}

	@Test
	void httpHandlersReceiveEmptyRootAndPrefixSuffixes() {
		SokletSimulator.run(config(Routes.class, ValueConverterRegistry.fromDefaults()), simulator -> {
			for (String[] sample : samples()) {
				HttpRequestResult result = simulator.performHttpRequest(Request.withRawUrl(HttpMethod.GET, sample[0]).build());
				assertEquals(200, result.getMarshaledResponse().getStatusCode(), sample[0]);
				assertEquals("<" + sample[1] + ">", body(result), sample[0]);
			}
		});
	}

	@Test
	void sseHandshakesReceiveEmptyRootAndPrefixSuffixes() {
		SokletSimulator.run(config(Routes.class, ValueConverterRegistry.fromDefaults()), simulator -> {
			for (String[] sample : samples()) {
				var result = simulator.performSseRequest(Request.withRawUrl(HttpMethod.GET, sample[0]).build());
				try (var accepted = assertInstanceOf(SseRequestResult.HandshakeAccepted.class, result, sample[0])) {
					assertEquals(sample[1], accepted.getSseHandshakeResult().getClientContext().orElseThrow(), sample[0]);
				}
			}
		});
	}

	@Test
	void ordinaryBlankBindingsAndNonemptySuffixConversionKeepTheirRules() {
		SokletSimulator.run(config(OrdinaryRoutes.class, ValueConverterRegistry.fromDefaults()), simulator -> {
			for (String path : List.of("/ordinary/%20/end", "/required?value="))
				assertEquals(400, simulator.performHttpRequest(Request.withRawUrl(HttpMethod.GET, path).build())
						.getMarshaledResponse().getStatusCode(), path);
			assertEquals("empty", body(simulator.performHttpRequest(Request.withRawUrl(HttpMethod.GET, "/optional?value=").build())));
			assertEquals("<name>", body(simulator.performHttpRequest(Request.withRawUrl(HttpMethod.GET, "/suffix/%20name%20").build())));
		});
	}

	@Test
	void explicitStringConverterStillReceivesEmptyAndNonemptySuffixes() {
		ValueConverterRegistry registry = ValueConverterRegistry.fromBlankSlateSupplementedBy(Set.of(new StringConverter(false, false)));
		SokletSimulator.run(config(Routes.class, registry), simulator -> {
			for (String[] sample : samples()) {
				HttpRequestResult http = simulator.performHttpRequest(Request.withRawUrl(HttpMethod.GET, sample[0]).build());
				assertEquals(200, http.getMarshaledResponse().getStatusCode());
				assertEquals("<converted:" + sample[1] + ">", body(http));
				try (var accepted = assertInstanceOf(SseRequestResult.HandshakeAccepted.class,
						simulator.performSseRequest(Request.withRawUrl(HttpMethod.GET, sample[0]).build()))) {
					assertEquals("converted:" + sample[1], accepted.getSseHandshakeResult().getClientContext().orElseThrow());
				}
			}
		});
	}

	@Test
	void explicitConverterMayRejectEmptySuffix() {
		for (boolean throwing : List.of(false, true)) {
			var registry = ValueConverterRegistry.fromBlankSlateSupplementedBy(Set.of(new StringConverter(true, throwing)));
			SokletSimulator.run(config(Routes.class, registry), simulator -> {
				assertEquals(400, simulator.performHttpRequest(Request.withPath(HttpMethod.GET, "/assets").build())
						.getMarshaledResponse().getStatusCode());
			});
		}
	}

	@Test
	void missingConverterIsNotSilentlyBypassed() {
		SokletConfig config = SokletConfig.forSimulatorTesting()
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Routes.class)))
				.valueConverterRegistry(ValueConverterRegistry.fromBlankSlate()).build();
		Request request = Request.withPath(HttpMethod.GET, "/assets").build();
		ResourceMethod method = config.getResourceMethodResolver().resourceMethodForRequest(request, ServerType.HTTP).orElseThrow();
		assertThrows(IllegalArgumentException.class,
				() -> config.getResourceMethodParameterProvider().parameterValuesForResourceMethod(request, method));
	}

	@Test
	void fixedPrefixHandlerStillWinsOverEmptyVarargsMatch() {
		SokletSimulator.run(config(FixedRoutes.class, ValueConverterRegistry.fromDefaults()), simulator -> {
			assertEquals("fixed", body(simulator.performHttpRequest(Request.withPath(HttpMethod.GET, "/assets").build())));
			assertEquals("<file>", body(simulator.performHttpRequest(Request.withPath(HttpMethod.GET, "/assets/file").build())));
		});
	}

	@Test
	void generatedIndexBindsEmptySuffixesInFreshJvm(@TempDir Path directory) throws Exception {
		Path source = directory.resolve("Routes.java");
		Path classes = Files.createDirectories(directory.resolve("classes"));
		Files.writeString(source, GENERATED_ROUTES, StandardCharsets.UTF_8);
		var compiler = ToolProvider.getSystemJavaCompiler();
		assertNotNull(compiler);
		try (var manager = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
			var task = compiler.getTask(null, manager, null,
					List.of("--release", "17", "-parameters", "-Asoklet.cacheMode=none",
							"-classpath", System.getProperty("java.class.path"), "-d", classes.toString()),
					null, manager.getJavaFileObjects(source));
			task.setProcessors(List.of(new SokletProcessor()));
			assertTrue(task.call());
		}
		Path output = directory.resolve("verification.log");
		Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
				"-Xmx128m", "-XX:ActiveProcessorCount=2", "-cp",
				classes + System.getProperty("path.separator") + System.getProperty("java.class.path"), "example.Routes")
				.redirectErrorStream(true).redirectOutput(output.toFile()).start();
		try {
			assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Index binding subprocess must finish");
			assertEquals(0, process.exitValue(), Files.readString(output));
		} finally {
			if (process.isAlive()) process.destroyForcibly();
		}
	}

	private static List<String[]> samples() {
		return List.of(new String[]{"/", ""}, new String[]{"/assets", ""}, new String[]{"/assets/", ""},
				new String[]{"/assets/a/b.txt", "a/b.txt"}, new String[]{"/assets/a%20b.txt", "a b.txt"});
	}

	private static SimulatorConfig config(Class<?> resource, ValueConverterRegistry registry) {
		return SimulatorConfig.builder().httpServer().sseServer()
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(resource)))
				.valueConverterRegistry(registry).lifecycleObserver(new LifecycleObserver() {
					@Override public void didReceiveLogEvent(LogEvent logEvent) {}
				}).build();
	}

	private static String body(HttpRequestResult result) {
		return new String(result.getMarshaledResponse().bodyBytesOrEmpty(), StandardCharsets.UTF_8);
	}

	public static class Routes {
		@GET("/{tail*}") public String root(@PathParameter String tail) { return "<" + tail + ">"; }
		@GET("/assets/{tail*}") public String assets(@PathParameter(name="tail") String suffix) { return "<" + suffix + ">"; }
		@SseEventSource("/{tail*}") public SseHandshakeResult rootSse(@PathParameter String tail) {
			return SseHandshakeResult.Accepted.builder().clientContext(tail).build();
		}
		@SseEventSource("/assets/{tail*}") public SseHandshakeResult assetsSse(@PathParameter(name="tail") String suffix) {
			return SseHandshakeResult.Accepted.builder().clientContext(suffix).build();
		}
	}

	public static class OrdinaryRoutes {
		@GET("/ordinary/{value}/end") public String ordinary(@PathParameter String value) { return value; }
		@GET("/required") public String required(@QueryParameter String value) { return value; }
		@GET("/optional") public String optional(@QueryParameter Optional<String> value) { return value.orElse("empty"); }
		@GET("/suffix/{tail*}") public String suffix(@PathParameter String tail) { return "<" + tail + ">"; }
	}

	public static class FixedRoutes {
		@GET("/assets") public String fixed() { return "fixed"; }
		@GET("/assets/{tail*}") public String suffix(@PathParameter String tail) { return "<" + tail + ">"; }
	}

	private record StringConverter(boolean rejectEmpty, boolean throwing) implements ValueConverter<String, String> {
		@Override public Optional<String> convert(String from) throws ValueConversionException {
			if (rejectEmpty && from.isEmpty()) {
				if (throwing) throw new ValueConversionException("Rejected suffix", String.class, from, String.class);
				return Optional.empty();
			}
			return Optional.of("converted:" + from);
		}
		@Override public Type getFromType() { return String.class; }
		@Override public Type getToType() { return String.class; }
	}

	private static final String GENERATED_ROUTES = """
			package example;
			import com.soklet.*;
			import com.soklet.annotation.*;
			import java.util.*;
			public class Routes {
			  @GET("/{tail*}") public String root(@PathParameter String tail) { return "<" + tail + ">"; }
			  @GET("/assets/{tail*}") public String assets(@PathParameter String tail) { return "<" + tail + ">"; }
			  @SseEventSource("/{tail*}") public SseHandshakeResult rootSse(@PathParameter String tail) {
			    return SseHandshakeResult.Accepted.builder().clientContext(tail).build();
			  }
			  public static void main(String[] args) throws Exception {
			    ClassLoader parent = Routes.class.getClassLoader();
			    try (var loader = new java.net.URLClassLoader(new java.net.URL[]{Routes.class.getProtectionDomain().getCodeSource().getLocation()}, parent) {
			      @Override public Enumeration<java.net.URL> getResources(String name) throws java.io.IOException {
			        return name.equals("META-INF/soklet/resource-method-lookup-table") ? findResources(name) : super.getResources(name);
			      }
			    }) {
			      Thread.currentThread().setContextClassLoader(loader);
			      var methods = new HashSet<java.lang.reflect.Method>(Arrays.asList(Routes.class.getDeclaredMethods()));
			      for (var resolver : List.of(ResourceMethodResolver.fromClasspathIntrospection(),
			          ResourceMethodResolver.fromClasses(Set.of(Routes.class)), ResourceMethodResolver.fromMethods(methods))) {
			        SokletSimulator.run(SimulatorConfig.builder().httpServer().sseServer().resourceMethodResolver(resolver).build(), simulator -> {
			          for (String path : List.of("/", "/assets", "/assets/")) {
			            var result = simulator.performHttpRequest(Request.withPath(HttpMethod.GET, path).build());
			            String body = new String(((MarshaledResponseBody.Bytes) result.getMarshaledResponse().getBody().orElseThrow()).getBytes(), java.nio.charset.StandardCharsets.UTF_8);
			            if (result.getMarshaledResponse().getStatusCode() != 200 || !body.equals("<>")) throw new AssertionError(path + ": " + body);
			          }
			          try (var accepted = (SseRequestResult.HandshakeAccepted) simulator.performSseRequest(Request.withPath(HttpMethod.GET, "/").build())) {
			            if (!accepted.getSseHandshakeResult().getClientContext().orElseThrow().equals("")) throw new AssertionError("SSE suffix");
			          }
			        });
			      }
			    }
			  }
			}
			""";
}
