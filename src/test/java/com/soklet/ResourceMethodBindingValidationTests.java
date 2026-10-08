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
import com.soklet.annotation.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
public class ResourceMethodBindingValidationTests {

	@Test
	void optionalPrimitiveBindingsArePositionedCompilerErrors() {
		for (String transport : List.of("GET", "SseEventSource")) {
			for (String primitive : List.of("byte", "short", "int", "long", "float", "double", "boolean", "char"))
				assertCompileFailure(transport, "/", "@QueryParameter(optional=true) " + primitive + " value", "Optional binding parameters must not use primitive types");
			for (String binding : List.of("FormParameter", "RequestHeader", "RequestCookie", "Multipart"))
				assertCompileFailure(transport, "/", "@" + binding + "(optional=true) int value", "Optional binding parameters must not use primitive types");
		}
	}

	@Test
	void conflictingBindingAnnotationsAreRejected() {
		for (String transport : List.of("GET", "SseEventSource"))
			for (String other : List.of("PathParameter", "FormParameter", "RequestHeader", "RequestCookie", "Multipart", "RequestBody"))
				assertCompileFailure(transport, other.equals("PathParameter") ? "/{value}" : "/",
						"@QueryParameter @" + other + " String value", "Only one Soklet binding annotation is allowed per parameter");
	}

	@Test
	void optionalPathBindingsAreRejected() {
		for (String transport : List.of("GET", "SseEventSource"))
			for (String type : List.of("Optional<String>", "Optional"))
				assertCompileFailure(transport, "/{value}", "@PathParameter " + type + " value", "Path parameters must not use Optional");
	}

	@Test
	void varargsBindingMustBeStringForEveryDeclaration() {
		for (String transport : List.of("GET", "SseEventSource")) {
			for (String type : List.of("int", "Integer", "Object", "List<String>", "String[]"))
				assertCompileFailure(transport, "/{value*}", "@PathParameter " + type + " value", "Varargs path parameters must use String");
			var source = source(transport, "/fixed/{value}", "@PathParameter Integer value",
					"@" + transport + "(\"/files/{value*}\")");
			var compilation = Compiler.javac().withProcessors(new SokletProcessor()).compile(source);
			assertThat(compilation).failed();
			assertThat(compilation).hadErrorContaining("Varargs path parameters must use String").inFile(source).onLine(7);
		}
	}

	@Test
	void validBindingsCustomQualifiersAndPrivateConstructionCompile() {
		for (String transport : List.of("GET", "SseEventSource")) {
			var source = JavaFileObjects.forSourceString("example.Routes", """
					package example;
					import com.soklet.*;
					import com.soklet.annotation.*;
					import java.util.*;
					@interface Qualifier {}
					class Routes {
					  private Routes(String dependency) {}
					  @%s("/{tail*}") public %s route(@PathParameter String tail,
					    @QueryParameter int required, @QueryParameter(optional=true) Integer optional,
					    @RequestHeader Optional<String> header, @RequestCookie Optional<String> cookie,
					    @FormParameter Optional<String> form, @Multipart Optional<MultipartField> file,
					    @RequestBody(optional=true) int body, @Qualifier Object dependency) { return %s; }
					}
					""".formatted(transport, returnType(transport), returnValue(transport)));
			assertThat(Compiler.javac().withProcessors(new SokletProcessor()).compile(source)).succeeded();
		}
	}

	@Test
	void defaultBindingFailuresAreRejectedBeforeStartupOrInstanceAcquisition() {
		for (Class<?> resource : List.of(OptionalPrimitive.class, ConflictingBindings.class, OptionalPath.class, NonStringVarargs.class)) {
			CONSTRUCTIONS.set(0);
			SokletConfig config = config(resource);
			try (Soklet soklet = Soklet.fromConfig(config)) {
				SokletStartupException failure = assertThrows(SokletStartupException.class, soklet::start, resource.getName());
				assertInstanceOf(IllegalArgumentException.class, failure.getCause());
			}
			assertEquals(0, CONSTRUCTIONS.get());
		}
	}

	@Test
	void directDefaultParameterProviderAlsoRejectsInvalidDeclarations() {
		for (Class<?> resource : List.of(OptionalPrimitive.class, ConflictingBindings.class, OptionalPath.class, NonStringVarargs.class)) {
			SokletConfig config = config(resource);
			ResourceMethod method = config.getResourceMethodResolver().getResourceMethods().iterator().next();
			assertThrows(IllegalArgumentException.class, () -> config.getResourceMethodParameterProvider()
					.parameterValuesForResourceMethod(Request.withPath(HttpMethod.GET, "/anything").build(), method));
		}
	}

	@Test
	void bindingDiagnosticsDoNotRetainRequestOrAnnotationValues() {
		SokletConfig config = config(ConflictingBindings.class);
		ResourceMethod method = config.getResourceMethodResolver().getResourceMethods().iterator().next();
		IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> config.getResourceMethodParameterProvider()
				.parameterValuesForResourceMethod(Request.withRawUrl(HttpMethod.GET, "/PRIVATE_PATH?value=PRIVATE_VALUE").build(), method));
		assertFalse(failure.getMessage().contains("PRIVATE_"));
		assertNull(failure.getCause());
	}

	@Test
	void dynamicallyResolvedInvalidBindingsFailBeforeInstanceAcquisition() {
		ResourceMethod stable = config(ValidRuntimeBindings.class).getResourceMethodResolver().getResourceMethods().stream()
				.filter(method -> method.getMethod().getName().equals("optional")).findFirst().orElseThrow();
		ResourceMethod invalid = config(OptionalPrimitive.class).getResourceMethodResolver().getResourceMethods().iterator().next();
		AtomicInteger calls = new AtomicInteger();
		SokletConfig config = configBuilder(ValidRuntimeBindings.class).resourceMethodResolver(new ResourceMethodResolver() {
			@Override public Set<ResourceMethod> getResourceMethods() { return Set.of(stable); }
			@Override public Optional<ResourceMethod> resourceMethodForRequest(Request request, ServerType serverType) { return Optional.of(invalid); }
		}).instanceProvider(new InstanceProvider() {
			@Override public <T> T provide(Class<T> type) { calls.incrementAndGet(); return InstanceProvider.defaultInstance().provide(type); }
		}).build();
		SokletSimulator.run(SimulatorConfig.fromSokletConfig(config), simulator ->
				assertEquals(500, simulator.performHttpRequest(Request.withPath(HttpMethod.GET, "/dynamic").build()).getMarshaledResponse().getStatusCode()));
		assertEquals(0, calls.get());
	}

	@Test
	void incompatibleDefaultConstructorsFailDuringSetupWithoutConstructingAnything() {
		for (Class<?> resource : List.of(PrivateConstructor.class, NoDefaultConstructor.class, AbstractResource.class)) {
			CONSTRUCTIONS.set(0);
			try (Soklet soklet = Soklet.fromConfig(config(resource))) {
				SokletStartupException failure = assertThrows(SokletStartupException.class, soklet::start, resource.getName());
				assertTrue(failure.getCause().getMessage().contains("InstanceProvider"));
			}
			assertEquals(0, CONSTRUCTIONS.get());
		}
	}

	@Test
	void customInstanceProviderCanStillCreateResourcesWithPrivateConstructors() {
		PrivateConstructor instance = new PrivateConstructor();
		SokletConfig config = configBuilder(PrivateConstructor.class).instanceProvider(new InstanceProvider() {
			@Override @SuppressWarnings("unchecked") public <T> T provide(Class<T> type) { return (T) instance; }
		}).build();
		SokletSimulator.run(SimulatorConfig.fromSokletConfig(config), simulator ->
				assertEquals(200, simulator.performHttpRequest(Request.withPath(HttpMethod.GET, "/").build()).getMarshaledResponse().getStatusCode()));
	}

	@Test
	void customParameterProviderRetainsControlOfExplicitlyRegisteredMethods() {
		SokletConfig config = configBuilder(OptionalPrimitive.class)
				.resourceMethodParameterProvider((request, method) -> List.of(9)).build();
		SokletSimulator.run(SimulatorConfig.fromSokletConfig(config), simulator ->
				assertEquals("9", body(simulator.performHttpRequest(Request.withPath(HttpMethod.GET, "/").build()))));
	}

	@Test
	void absentAndBlankOptionalValuesStillReachTheHandlerAsEmpty() {
		SokletSimulator.run(SimulatorConfig.fromSokletConfig(config(ValidRuntimeBindings.class)), simulator -> {
			for (Request request : List.of(Request.withPath(HttpMethod.GET, "/optional").build(),
					Request.withRawUrl(HttpMethod.GET, "/optional?value=").headers(Map.of("X-Value", List.of(""), "Cookie", List.of("value="))).build()))
				assertEquals("true:true:true", body(simulator.performHttpRequest(request)));
			assertEquals("true", body(simulator.performHttpRequest(Request.withRawUrl(HttpMethod.POST, "/form")
					.headers(Map.of("Content-Type", List.of("application/x-www-form-urlencoded"))).body("value=".getBytes(java.nio.charset.StandardCharsets.UTF_8)).build())));
			assertEquals("0:false", body(simulator.performHttpRequest(Request.withPath(HttpMethod.POST, "/body-default").build())));
			assertEquals("true", body(simulator.performHttpRequest(Request.withPath(HttpMethod.POST, "/multipart").build())));
		});
	}

	private static void assertCompileFailure(String transport, String path, String parameters, String message) {
		var source = source(transport, path, parameters, "");
		var compilation = Compiler.javac().withProcessors(new SokletProcessor()).compile(source);
		assertThat(compilation).failed();
		assertThat(compilation).hadErrorContaining(message).inFile(source).onLine(7);
	}

	private static javax.tools.JavaFileObject source(String transport, String path, String parameters, String extraAnnotation) {
		return JavaFileObjects.forSourceString("example.Routes", """
				package example;
				import com.soklet.annotation.*;
				import com.soklet.SseHandshakeResult;
				import java.util.*;
				public class Routes {
				  @%s("%s") %s
				  public %s route(%s) { return %s; }
				}
				""".formatted(transport, path, extraAnnotation, returnType(transport), parameters, returnValue(transport)));
	}
	private static String returnType(String transport) { return transport.equals("GET") ? "String" : "SseHandshakeResult"; }
	private static String returnValue(String transport) { return transport.equals("GET") ? "\"ok\"" : "SseHandshakeResult.accept()"; }
	private static SokletConfig.Builder configBuilder(Class<?> resource) { return SokletConfig.withHttpServer(HttpServer.withPort(0).build())
			.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(resource))); }
	private static SokletConfig config(Class<?> resource) { return configBuilder(resource).build(); }
	private static String body(HttpRequestResult result) { assertEquals(200, result.getMarshaledResponse().getStatusCode());
		return new String(result.getMarshaledResponse().bodyBytesOrEmpty(), java.nio.charset.StandardCharsets.UTF_8); }
	private static final AtomicInteger CONSTRUCTIONS = new AtomicInteger();
	public static class OptionalPrimitive { public OptionalPrimitive() { CONSTRUCTIONS.incrementAndGet(); }
		@GET("/") public String route(@QueryParameter(optional=true) int value) { return String.valueOf(value); } }
	public static class ConflictingBindings { public ConflictingBindings() { CONSTRUCTIONS.incrementAndGet(); }
		@GET("/") public String route(@QueryParameter(name="PRIVATE_QUERY_NAME") @RequestHeader(name="PRIVATE_HEADER_NAME") String value) { return value; } }
	public static class OptionalPath { public OptionalPath() { CONSTRUCTIONS.incrementAndGet(); }
		@GET("/{value}") public String route(@PathParameter Optional<String> value) { return value.orElse(""); } }
	public static class NonStringVarargs { public NonStringVarargs() { CONSTRUCTIONS.incrementAndGet(); }
		@GET("/{value*}") public String route(@PathParameter Integer value) { return String.valueOf(value); } }
	public static class PrivateConstructor { private PrivateConstructor() { CONSTRUCTIONS.incrementAndGet(); }
		@GET("/") public String route() { return "ok"; } }
	public static class NoDefaultConstructor { public NoDefaultConstructor(String value) { CONSTRUCTIONS.incrementAndGet(); }
		@GET("/") public String route() { return "ok"; } }
	public abstract static class AbstractResource { public AbstractResource() { CONSTRUCTIONS.incrementAndGet(); }
		@GET("/") public String route() { return "ok"; } }
	public static class ValidRuntimeBindings {
		@GET("/optional") public String optional(@QueryParameter Optional<String> value,
				@RequestHeader(name="X-Value") Optional<String> header, @RequestCookie(name="value") Optional<String> cookie) {
			return value.isEmpty()+":"+header.isEmpty()+":"+cookie.isEmpty(); }
		@POST("/form") public String form(@FormParameter Optional<String> value) { return String.valueOf(value.isEmpty()); }
		@POST("/body-default") public String body(@RequestBody(optional=true) int number, @RequestBody(optional=true) boolean flag) { return number+":"+flag; }
		@POST("/multipart") public String multipart(@Multipart Optional<MultipartField> value) { return String.valueOf(value.isEmpty()); }
	}
}
