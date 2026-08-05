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

package com.soklet.internal.mcp.generated;

import com.soklet.InstanceProvider;
import com.soklet.McpCompleteResult;
import com.soklet.McpHandlerResolver;
import com.soklet.McpInvocationFeatures;
import com.soklet.McpJsonObject;
import com.soklet.McpOperationResult;
import com.soklet.McpRequestContext;
import com.soklet.McpToolCallContext;
import com.soklet.McpToolHandler;
import com.soklet.McpToolRegistration;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.annotation.concurrent.ThreadSafe;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the strict generated-provider discovery boundary independently of
 * annotation-processor generation.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class McpGeneratedEndpointProviderLoaderTests {
	@NonNull
	private static final String FIXTURES_CLASS_NAME = "example.Fixtures";
	@NonNull
	private static final String ENDPOINT_A = FIXTURES_CLASS_NAME + "$EndpointA";
	@NonNull
	private static final String ENDPOINT_B = FIXTURES_CLASS_NAME + "$EndpointB";
	@NonNull
	private static final String UNINDEXED_ENDPOINT =
			FIXTURES_CLASS_NAME + "$UnindexedEndpoint";
	@NonNull
	private static final String PROVIDER_A = FIXTURES_CLASS_NAME + "$ProviderA";
	@NonNull
	private static final String PROVIDER_B = FIXTURES_CLASS_NAME + "$ProviderB";
	@NonNull
	private static final String BAD_CONTRACT_PROVIDER =
			FIXTURES_CLASS_NAME + "$BadContractProvider";
	@NonNull
	private static final String NO_PUBLIC_CONSTRUCTOR_PROVIDER =
			FIXTURES_CLASS_NAME + "$NoPublicConstructorProvider";
	@NonNull
	private static final String BAD_DIGEST_PROVIDER =
			FIXTURES_CLASS_NAME + "$BadDigestProvider";
	@NonNull
	private static final String DUPLICATE_PATH_PROVIDER =
			FIXTURES_CLASS_NAME + "$DuplicatePathProvider";
	@NonNull
	private static final String NON_FINAL_PROVIDER =
			FIXTURES_CLASS_NAME + "$NonFinalProvider";
	@NonNull
	private static final String STATIC_METHOD_PROVIDER =
			FIXTURES_CLASS_NAME + "$StaticMethodProvider";
	@NonNull
	private static final String NULL_ENDPOINT_CLASS_PROVIDER =
			FIXTURES_CLASS_NAME + "$NullEndpointClassProvider";
	@NonNull
	private static final String NULL_ENDPOINT_PROVIDER =
			FIXTURES_CLASS_NAME + "$NullEndpointProvider";
	@NonNull
	private static final String NULL_DIGESTS_PROVIDER =
			FIXTURES_CLASS_NAME + "$NullDigestsProvider";

	@TempDir
	private static Path temporaryDirectory;
	private static Path compiledFixtureDirectory;

	@BeforeAll
	static void compileProviderFixtures() throws IOException {
		Path sourceDirectory = temporaryDirectory.resolve("fixture-source");
		Path packageDirectory = sourceDirectory.resolve("example");
		compiledFixtureDirectory = temporaryDirectory.resolve("fixture-classes");
		Files.createDirectories(packageDirectory);
		Files.createDirectories(compiledFixtureDirectory);
		Path sourceFile = packageDirectory.resolve("Fixtures.java");
		Files.writeString(sourceFile, fixtureSource(), StandardCharsets.UTF_8);

		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		assertTrue(compiler != null, "Tests require a full JDK compiler.");
		StringWriter diagnostics = new StringWriter();
		try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(
				null, null, StandardCharsets.UTF_8)) {
			Iterable<? extends javax.tools.JavaFileObject> sources =
					fileManager.getJavaFileObjects(sourceFile.toFile());
			List<String> options = List.of(
					"--release", "17",
					"-proc:none",
					"-classpath", System.getProperty("java.class.path"),
					"-d", compiledFixtureDirectory.toString());
			boolean compiled = Boolean.TRUE.equals(compiler.getTask(diagnostics,
					fileManager, null, options, null, sources).call());
			assertTrue(compiled, diagnostics.toString());
		}
	}

	@Test
	void classpathDiscoveryMergesIndexesAndOrdersEndpointsWithoutInstances()
			throws Exception {
		String firstIndex = "# later endpoint first\n"
				+ indexLine(ENDPOINT_B, PROVIDER_B) + "\n";
		String secondIndex = "\n" + indexLine(ENDPOINT_A, PROVIDER_A)
				+ "\n";
		try (IndexedClassLoader classLoader = newClassLoader(firstIndex,
				secondIndex)) {
			CountingInstanceProvider instanceProvider =
					new CountingInstanceProvider();
			ClassLoader previous = Thread.currentThread().getContextClassLoader();
			try {
				Thread.currentThread().setContextClassLoader(classLoader);
				McpHandlerResolver resolver = McpHandlerResolver
						.fromClasspathIntrospection(instanceProvider);
				assertEquals(List.of("/a", "/b"), endpointPaths(resolver));
				assertEquals(0, instanceProvider.invocationCount());
				resolver.getEndpoints().forEach(endpoint -> {
					endpoint.getTools().forEach(tool -> {
						tool.getInputSchema().getDocument();
						tool.getOutputSchema().orElseThrow().getDocument();
					});
				});
				assertEquals(0, instanceProvider.invocationCount());
			} finally {
				Thread.currentThread().setContextClassLoader(previous);
			}
		}
	}

	@Test
	void explicitClassDiscoveryPreservesCallerOrder() throws Exception {
		try (IndexedClassLoader classLoader = newClassLoader(
				goodIndex())) {
			Class<?> endpointA = Class.forName(ENDPOINT_A, false, classLoader);
			Class<?> endpointB = Class.forName(ENDPOINT_B, false, classLoader);
			CountingInstanceProvider instanceProvider =
					new CountingInstanceProvider();

			McpHandlerResolver resolver = McpHandlerResolver.fromClasses(
					instanceProvider, endpointB, endpointA);

			assertEquals(List.of("/b", "/a"), endpointPaths(resolver));
			assertEquals(0, instanceProvider.invocationCount());
		}
	}

	@Test
	void explicitClassDiscoveryRejectsDuplicatesAndMissingDescriptors()
			throws Exception {
		try (IndexedClassLoader classLoader = newClassLoader(
				goodIndex())) {
			Class<?> endpointA = Class.forName(ENDPOINT_A, false, classLoader);
			Class<?> unindexed = Class.forName(UNINDEXED_ENDPOINT, false,
					classLoader);
			CountingInstanceProvider instanceProvider =
					new CountingInstanceProvider();

			IllegalArgumentException duplicate = assertThrows(
					IllegalArgumentException.class,
					() -> McpHandlerResolver.fromClasses(instanceProvider,
							endpointA, endpointA));
			assertTrue(duplicate.getMessage().contains(
					"Duplicate annotated MCP endpoint class"));

			IllegalArgumentException missing = assertThrows(
					IllegalArgumentException.class,
					() -> McpHandlerResolver.fromClasses(instanceProvider,
							unindexed));
			assertTrue(missing.getMessage().contains(
					"No generated MCP endpoint descriptor exists"));
			assertEquals(0, instanceProvider.invocationCount());
		}
	}

	@Test
	void malformedAndConflictingIndexesFailClosed() throws Exception {
		assertThrows(IllegalArgumentException.class,
				() -> McpGeneratedEndpointProviderIndex.formatLine(ENDPOINT_A,
						PROVIDER_A, "example.Unrelated", "/a"));
		for (String path : List.of("/", "//a/", "/{tenant}"))
			assertThrows(IllegalArgumentException.class,
					() -> McpGeneratedEndpointProviderIndex.formatLine(ENDPOINT_A,
							PROVIDER_A, FIXTURES_CLASS_NAME, path));

		try (IndexedClassLoader malformed = newClassLoader(
				"2|not-a-supported-row")) {
			IllegalStateException exception = assertThrows(
					IllegalStateException.class,
					() -> McpGeneratedEndpointProviderLoader.loadAll(malformed,
							InstanceProvider.defaultInstance()));
			assertTrue(exception.getMessage().contains(
					"Malformed generated MCP endpoint-provider index"));
		}

		String conflictingIndex = indexLine(ENDPOINT_A, PROVIDER_A) + "\n"
				+ indexLine(ENDPOINT_A, PROVIDER_B) + "\n";
		try (IndexedClassLoader conflicting = newClassLoader(
				conflictingIndex)) {
			IllegalStateException exception = assertThrows(
					IllegalStateException.class,
					() -> McpGeneratedEndpointProviderLoader.loadAll(conflicting,
							InstanceProvider.defaultInstance()));
			assertTrue(exception.getMessage().contains(
					"Conflicting generated MCP endpoint providers"));
		}

		String reusedProviderIndex = indexLine(ENDPOINT_A, PROVIDER_A) + "\n"
				+ indexLine(ENDPOINT_B, PROVIDER_A) + "\n";
		try (IndexedClassLoader reusedProvider = newClassLoader(
				reusedProviderIndex)) {
			IllegalStateException exception = assertThrows(
					IllegalStateException.class,
					() -> McpGeneratedEndpointProviderLoader.loadAll(
							reusedProvider, InstanceProvider.defaultInstance()));
			assertTrue(exception.getMessage().contains(
					"is assigned to multiple endpoint classes"));
		}

		String conflictingOwnerIndex = indexLine(ENDPOINT_A, PROVIDER_A)
				+ "\n" + indexLine(ENDPOINT_A, PROVIDER_A, ENDPOINT_A)
				+ "\n";
		try (IndexedClassLoader conflictingOwner = newClassLoader(
				conflictingOwnerIndex)) {
			IllegalStateException exception = assertThrows(
					IllegalStateException.class,
					() -> McpGeneratedEndpointProviderLoader.loadAll(
							conflictingOwner,
							InstanceProvider.defaultInstance()));
			assertTrue(exception.getMessage().contains(
					"Conflicting generated MCP endpoint providers"));
		}

		try (IndexedClassLoader incorrectOwner = newClassLoader(
				indexLine(ENDPOINT_A, PROVIDER_A, ENDPOINT_A))) {
			IllegalStateException exception = assertThrows(
					IllegalStateException.class,
					() -> McpGeneratedEndpointProviderLoader.loadAll(
							incorrectOwner,
							InstanceProvider.defaultInstance()));
			assertTrue(exception.getMessage().contains(
					"top-level owner does not match"));
		}
	}

	@Test
	void providerContractAndMissingProviderFailuresAreDeterministic()
			throws Exception {
		try (IndexedClassLoader incompatible = newClassLoader(
				indexLine(ENDPOINT_A, BAD_CONTRACT_PROVIDER))) {
			IllegalStateException exception = assertThrows(
					IllegalStateException.class,
					() -> McpGeneratedEndpointProviderLoader.loadAll(incompatible,
							InstanceProvider.defaultInstance()));
			assertTrue(exception.getMessage().contains(
					"has an incompatible provider contract"));
		}

		try (IndexedClassLoader missing = newClassLoader(
				indexLine(ENDPOINT_A, "example.MissingProvider"))) {
			IllegalStateException exception = assertThrows(
					IllegalStateException.class,
					() -> McpGeneratedEndpointProviderLoader.loadAll(missing,
							InstanceProvider.defaultInstance()));
			assertTrue(exception.getMessage().contains(
					"Unable to load generated MCP endpoint descriptor"));
		}

		try (IndexedClassLoader noPublicConstructor = newClassLoader(
				indexLine(ENDPOINT_A, NO_PUBLIC_CONSTRUCTOR_PROVIDER))) {
			IllegalStateException exception = assertThrows(
					IllegalStateException.class,
					() -> McpGeneratedEndpointProviderLoader.loadAll(
							noPublicConstructor,
							InstanceProvider.defaultInstance()));
			assertTrue(exception.getMessage().contains(
					"Unable to load generated MCP endpoint descriptor"));
			assertInstanceOf(NoSuchMethodException.class, exception.getCause());
		}

		try (IndexedClassLoader nonFinal = newClassLoader(
				indexLine(ENDPOINT_A, NON_FINAL_PROVIDER))) {
			IllegalStateException exception = assertThrows(
					IllegalStateException.class,
					() -> McpGeneratedEndpointProviderLoader.loadAll(nonFinal,
							InstanceProvider.defaultInstance()));
			assertTrue(exception.getMessage().contains("must be public and final"));
		}

		try (IndexedClassLoader staticMethods = newClassLoader(
				indexLine(ENDPOINT_A, STATIC_METHOD_PROVIDER))) {
			IllegalStateException exception = assertThrows(
					IllegalStateException.class,
					() -> McpGeneratedEndpointProviderLoader.loadAll(staticMethods,
							InstanceProvider.defaultInstance()));
			assertTrue(exception.getMessage().contains(
					"has an incompatible provider contract"));
		}
	}

	@Test
	void duplicateGeneratedEndpointPathsHaveResolverSpecificFailures()
			throws Exception {
		String index = indexLine(ENDPOINT_A, PROVIDER_A) + "\n"
				+ indexLine(ENDPOINT_B, DUPLICATE_PATH_PROVIDER,
						FIXTURES_CLASS_NAME, "/a") + "\n";
		try (IndexedClassLoader classLoader = newClassLoader(index)) {
			Class<?> endpointA = Class.forName(ENDPOINT_A, false, classLoader);
			Class<?> endpointB = Class.forName(ENDPOINT_B, false, classLoader);
			IllegalArgumentException selectedFailure = assertThrows(
					IllegalArgumentException.class,
					() -> McpHandlerResolver.fromClasses(endpointA, endpointB));
			assertTrue(selectedFailure.getMessage().contains(
					"Duplicate MCP endpoint path"));

			ClassLoader previous = Thread.currentThread().getContextClassLoader();
			try {
				Thread.currentThread().setContextClassLoader(classLoader);
				IllegalStateException classpathFailure = assertThrows(
						IllegalStateException.class,
						McpHandlerResolver::fromClasspathIntrospection);
				assertTrue(classpathFailure.getMessage().contains(
						"descriptors conflict"));
				assertInstanceOf(IllegalArgumentException.class,
						classpathFailure.getCause());
			} finally {
				Thread.currentThread().setContextClassLoader(previous);
			}
		}
	}

	@Test
	void indexedPathMustMatchGeneratedEndpointPath() throws Exception {
		try (IndexedClassLoader classLoader = newClassLoader(indexLine(ENDPOINT_A,
				PROVIDER_A, FIXTURES_CLASS_NAME, "/b"))) {
			IllegalStateException exception = assertThrows(
					IllegalStateException.class,
					() -> McpGeneratedEndpointProviderLoader.loadAll(classLoader,
							InstanceProvider.defaultInstance()));
			assertTrue(exception.getMessage().contains(
					"endpoint path does not match"));
		}
	}

	@Test
	void nullProviderContractValuesFailAsIllegalState() throws Exception {
		assertNullProviderValueFailure(NULL_ENDPOINT_CLASS_PROVIDER,
				"null endpoint class");
		assertNullProviderValueFailure(NULL_ENDPOINT_PROVIDER,
				"provider returned null");
		assertNullProviderValueFailure(NULL_DIGESTS_PROVIDER,
				"null schema digests");
	}

	private static void assertNullProviderValueFailure(
			@NonNull String providerClassName,
			@NonNull String expectedMessage) throws Exception {
		try (IndexedClassLoader classLoader = newClassLoader(
				indexLine(ENDPOINT_A, providerClassName))) {
			IllegalStateException exception = assertThrows(
					IllegalStateException.class,
					() -> McpGeneratedEndpointProviderLoader.loadAll(classLoader,
							InstanceProvider.defaultInstance()));
			assertTrue(exception.getMessage().contains(expectedMessage),
					exception.getMessage());
		}
	}

	@Test
	void schemaDigestMismatchFailsBeforeResolverEscape() throws Exception {
		try (IndexedClassLoader classLoader = newClassLoader(
				indexLine(ENDPOINT_A, BAD_DIGEST_PROVIDER))) {
			IllegalStateException exception = assertThrows(
					IllegalStateException.class,
					() -> McpGeneratedEndpointProviderLoader.loadAll(classLoader,
							InstanceProvider.defaultInstance()));
			assertTrue(exception.getMessage().contains(
					"schema does not match its runtime types"));
		}
	}

	@Test
	@SuppressWarnings({ "rawtypes", "unchecked" })
	void instanceProviderIsCalledExactlyOncePerHandlerInvocation()
			throws Exception {
		try (IndexedClassLoader classLoader = newClassLoader(
				indexLine(ENDPOINT_A, PROVIDER_A))) {
			CountingInstanceProvider instanceProvider =
					new CountingInstanceProvider();
			ClassLoader previous = Thread.currentThread().getContextClassLoader();
			try {
				Thread.currentThread().setContextClassLoader(classLoader);
				McpHandlerResolver resolver = McpHandlerResolver
						.fromClasspathIntrospection(instanceProvider);
				McpToolRegistration<?> tool = resolver.getEndpoints().get(0)
						.getTools().get(0);
				assertEquals(0, instanceProvider.invocationCount());

				Class<?> argumentsClass = Class.forName(
						ENDPOINT_A + "$Arguments", true, classLoader);
				Constructor<?> constructor = argumentsClass.getConstructor(
						String.class);
				Object arguments = constructor.newInstance("value");
				McpToolCallContext<Object> call = new McpToolCallContext<>() {
					@Override
					public Object getArguments() {
						return arguments;
					}

					@Override
					public McpJsonObject getRawArguments() {
						return McpJsonObject.emptyInstance();
					}
				};
				McpToolHandler handler = tool.getHandler();
				McpRequestContext request = requestContextFixture();
				McpInvocationFeatures features = McpInvocationFeatures
						.fromFeatures(Map.of());

				McpOperationResult first = handler.handle(request, call, features);
				assertInstanceOf(McpCompleteResult.class, first);
				assertEquals(1, instanceProvider.invocationCount());
				McpOperationResult second = handler.handle(request, call, features);
				assertInstanceOf(McpCompleteResult.class, second);
				assertEquals(2, instanceProvider.invocationCount());
			} finally {
				Thread.currentThread().setContextClassLoader(previous);
			}
		}
	}

	@NonNull
	private static List<@NonNull String> endpointPaths(
			@NonNull McpHandlerResolver resolver) {
		return resolver.getEndpoints().stream()
				.map(endpoint -> endpoint.getPath())
				.collect(Collectors.toUnmodifiableList());
	}

	@NonNull
	private static McpRequestContext requestContextFixture() {
		return (McpRequestContext) Proxy.newProxyInstance(
				McpRequestContext.class.getClassLoader(),
				new Class<?>[] { McpRequestContext.class },
				(proxy, method, arguments) -> {
					if (method.getName().equals("toString"))
						return "McpRequestContext fixture";
					throw new AssertionError(
							"Generated test handler unexpectedly consulted request context method "
									+ method.getName());
				});
	}

	@NonNull
	private static String goodIndex() {
		return indexLine(ENDPOINT_A, PROVIDER_A) + "\n"
				+ indexLine(ENDPOINT_B, PROVIDER_B) + "\n";
	}

	@NonNull
	private static String indexLine(@NonNull String endpointClassName,
			@NonNull String providerClassName) {
		return indexLine(endpointClassName, providerClassName,
				FIXTURES_CLASS_NAME, endpointClassName.equals(ENDPOINT_B)
						? "/b" : "/a");
	}

	@NonNull
	private static String indexLine(@NonNull String endpointClassName,
			@NonNull String providerClassName,
			@NonNull String topLevelClassName) {
		return indexLine(endpointClassName, providerClassName, topLevelClassName,
				endpointClassName.equals(ENDPOINT_B) ? "/b" : "/a");
	}

	@NonNull
	private static String indexLine(@NonNull String endpointClassName,
			@NonNull String providerClassName,
			@NonNull String topLevelClassName,
			@NonNull String endpointPath) {
		return McpGeneratedEndpointProviderIndex.formatLine(endpointClassName,
				providerClassName, topLevelClassName, endpointPath);
	}

	@NonNull
	private static IndexedClassLoader newClassLoader(
			String @NonNull ... indexContents) throws IOException {
		List<URL> indexResources = new ArrayList<>(indexContents.length);
		for (int index = 0; index < indexContents.length; index++) {
			Path resource = temporaryDirectory.resolve("indexes")
					.resolve(Long.toUnsignedString(System.nanoTime()))
					.resolve(Integer.toString(index))
					.resolve(McpGeneratedEndpointProviderIndex.RESOURCE_PATH);
			Files.createDirectories(resource.getParent());
			Files.writeString(resource, indexContents[index],
					StandardCharsets.UTF_8);
			indexResources.add(resource.toUri().toURL());
		}
		IndexedClassLoader classLoader = new IndexedClassLoader(
				new URL[] { compiledFixtureDirectory.toUri().toURL() },
				McpGeneratedEndpointProviderLoaderTests.class.getClassLoader(),
				indexResources);
		return classLoader;
	}

	@NonNull
	private static String fixtureSource() {
		return """
				package example;

				import com.soklet.InstanceProvider;
				import com.soklet.McpEndpoint;
				import com.soklet.McpImplementation;
				import com.soklet.McpToolRegistration;

				public final class Fixtures {
				  private static final String SCHEMA_DIGEST =
				      "c63a31070b958efa18de4b8fd2c429e6e18b3609d6ce1940afc868899a6a6b94";

				  private Fixtures() {}

				  public static final class EndpointA {
				    public EndpointA() {}
				    public Result invoke(Arguments arguments) {
				      return new Result(arguments.value());
				    }
				    public record Arguments(String value) {}
				    public record Result(String value) {}
				  }

				  public static final class EndpointB {
				    @SuppressWarnings("unused")
				    private static final int INITIALIZED = failIfInitialized();
				    public EndpointB() {}
				    private static int failIfInitialized() {
				      throw new AssertionError("EndpointB was initialized during discovery.");
				    }
				    public Result invoke(Arguments arguments) {
				      return new Result(arguments.value());
				    }
				    public record Arguments(String value) {}
				    public record Result(String value) {}
				  }

				  public static final class UnindexedEndpoint {
				    public UnindexedEndpoint() {}
				  }

				  public static final class ProviderA {
				    public ProviderA() {}
				    public Class<?> endpointClass() { return EndpointA.class; }
				    public McpEndpoint endpoint(InstanceProvider instanceProvider) {
				      McpToolRegistration<EndpointA.Arguments> tool =
				          McpToolRegistration.withName("tool-a")
				              .types(EndpointA.Arguments.class, EndpointA.Result.class)
				              .handler((request, call, features) -> instanceProvider
				                  .provide(EndpointA.class).invoke(call.getArguments()))
				              .build();
				      return McpEndpoint.withPath("/a")
				          .serverInformation(McpImplementation
				              .withNameAndVersion("endpoint-a", "1").build())
				          .tool(tool)
				          .build();
				    }
				    public String[] schemaDigests() {
				      return new String[] { "tool-a", SCHEMA_DIGEST, SCHEMA_DIGEST };
				    }
				  }

				  public static final class ProviderB {
				    public ProviderB() {}
				    public Class<?> endpointClass() { return EndpointB.class; }
				    public McpEndpoint endpoint(InstanceProvider instanceProvider) {
				      McpToolRegistration<EndpointB.Arguments> tool =
				          McpToolRegistration.withName("tool-b")
				              .types(EndpointB.Arguments.class, EndpointB.Result.class)
				              .handler((request, call, features) -> instanceProvider
				                  .provide(EndpointB.class).invoke(call.getArguments()))
				              .build();
				      return McpEndpoint.withPath("/b")
				          .serverInformation(McpImplementation
				              .withNameAndVersion("endpoint-b", "1").build())
				          .tool(tool)
				          .build();
				    }
				    public String[] schemaDigests() {
				      return new String[] { "tool-b", SCHEMA_DIGEST, SCHEMA_DIGEST };
				    }
				  }

				  public static final class BadContractProvider {
				    public BadContractProvider() {}
				    public String endpointClass() { return EndpointA.class.getName(); }
				    public McpEndpoint endpoint(InstanceProvider instanceProvider) {
				      return new ProviderA().endpoint(instanceProvider);
				    }
				    public String[] schemaDigests() {
				      return new ProviderA().schemaDigests();
				    }
				  }

				  public static final class BadDigestProvider {
				    public BadDigestProvider() {}
				    public Class<?> endpointClass() { return EndpointA.class; }
				    public McpEndpoint endpoint(InstanceProvider instanceProvider) {
				      return new ProviderA().endpoint(instanceProvider);
				    }
				    public String[] schemaDigests() {
				      return new String[] { "tool-a", "wrong", "wrong" };
				    }
				  }

				  public static final class DuplicatePathProvider {
				    public DuplicatePathProvider() {}
				    public Class<?> endpointClass() { return EndpointB.class; }
				    public McpEndpoint endpoint(InstanceProvider instanceProvider) {
				      McpEndpoint original = new ProviderB().endpoint(instanceProvider);
				      return McpEndpoint.withPath("/a")
				          .serverInformation(McpImplementation
				              .withNameAndVersion("endpoint-b", "1").build())
				          .tool(original.getTools().get(0))
				          .build();
				    }
				    public String[] schemaDigests() {
				      return new ProviderB().schemaDigests();
				    }
				  }

				  public static class NonFinalProvider {
				    public NonFinalProvider() {}
				    public Class<?> endpointClass() { return EndpointA.class; }
				    public McpEndpoint endpoint(InstanceProvider instanceProvider) {
				      return new ProviderA().endpoint(instanceProvider);
				    }
				    public String[] schemaDigests() {
				      return new ProviderA().schemaDigests();
				    }
				  }

				  public static final class StaticMethodProvider {
				    public StaticMethodProvider() {}
				    public static Class<?> endpointClass() { return EndpointA.class; }
				    public static McpEndpoint endpoint(InstanceProvider instanceProvider) {
				      return new ProviderA().endpoint(instanceProvider);
				    }
				    public static String[] schemaDigests() {
				      return new ProviderA().schemaDigests();
				    }
				  }

				  public static final class NullEndpointClassProvider {
				    public NullEndpointClassProvider() {}
				    public Class<?> endpointClass() { return null; }
				    public McpEndpoint endpoint(InstanceProvider instanceProvider) {
				      return new ProviderA().endpoint(instanceProvider);
				    }
				    public String[] schemaDigests() {
				      return new ProviderA().schemaDigests();
				    }
				  }

				  public static final class NullEndpointProvider {
				    public NullEndpointProvider() {}
				    public Class<?> endpointClass() { return EndpointA.class; }
				    public McpEndpoint endpoint(InstanceProvider instanceProvider) {
				      return null;
				    }
				    public String[] schemaDigests() {
				      return new ProviderA().schemaDigests();
				    }
				  }

				  public static final class NullDigestsProvider {
				    public NullDigestsProvider() {}
				    public Class<?> endpointClass() { return EndpointA.class; }
				    public McpEndpoint endpoint(InstanceProvider instanceProvider) {
				      return new ProviderA().endpoint(instanceProvider);
				    }
				    public String[] schemaDigests() { return null; }
				  }

				  public static final class NoPublicConstructorProvider {
				    private NoPublicConstructorProvider() {}
				    public Class<?> endpointClass() { return EndpointA.class; }
				    public McpEndpoint endpoint(InstanceProvider instanceProvider) {
				      return new ProviderA().endpoint(instanceProvider);
				    }
				    public String[] schemaDigests() {
				      return new ProviderA().schemaDigests();
				    }
				  }
				}
				""";
	}

	@ThreadSafe
	private static final class CountingInstanceProvider
			implements InstanceProvider {
		@NonNull
		private final AtomicInteger invocationCount = new AtomicInteger();

		@Override
		@NonNull
		public <T> T provide(@NonNull Class<T> instanceClass) {
			this.invocationCount.incrementAndGet();
			try {
				return instanceClass.cast(
						instanceClass.getConstructor().newInstance());
			} catch (ReflectiveOperationException exception) {
				throw new IllegalStateException(exception);
			}
		}

		int invocationCount() {
			return this.invocationCount.get();
		}
	}

	private static final class IndexedClassLoader extends URLClassLoader {
		@NonNull
		private final List<@NonNull URL> indexResources;

		private IndexedClassLoader(URL @NonNull [] urls,
				@NonNull ClassLoader parent,
				@NonNull List<@NonNull URL> indexResources) {
			super(urls, parent);
			this.indexResources = List.copyOf(indexResources);
		}

		@Override
		@NonNull
		public Enumeration<@NonNull URL> getResources(@NonNull String name)
				throws IOException {
			if (McpGeneratedEndpointProviderIndex.RESOURCE_PATH.equals(name))
				return Collections.enumeration(this.indexResources);
			return super.getResources(name);
		}
	}
}
