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

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.util.Objects.requireNonNull;

/**
 * Isolated reflective access to the released and candidate MCP runtimes.
 *
 * <p>The release benchmark must not resolve either Soklet implementation from
 * the benchmark harness class path. Each runtime is therefore loaded from its
 * exact artifact in a child whose parent is the platform class loader. This
 * also lets the same benchmark exercise the package-private 3.5.1 codec and
 * the reorganized 4.0.0 codec without compiling against either shape.</p>
 */
final class McpReleaseBenchmarkRuntime implements AutoCloseable {
	static final String BASELINE_ARTIFACT = "3.5.1";
	static final String CANDIDATE_ARTIFACT = "4.0.0";
	static final String BASELINE_JAR_ENVIRONMENT =
			"SOKLET_BENCHMARK_BASELINE_JAR";
	static final String CANDIDATE_JAR_ENVIRONMENT =
			"SOKLET_BENCHMARK_CANDIDATE_JAR";

	static final byte[] JSON_PAYLOAD = ("{\"jsonrpc\":\"2.0\",\"id\":17,"
			+ "\"method\":\"tools/call\",\"params\":{\"name\":"
			+ "\"inventory.search\",\"arguments\":{\"query\":"
			+ "\"espresso beans\",\"limit\":25,\"includeUnavailable\":false,"
			+ "\"warehouses\":[\"iad-1\",\"ord-2\",\"pdx-1\"],"
			+ "\"filters\":{\"roast\":[\"light\",\"medium\"],"
			+ "\"minimumRating\":4.25,\"origin\":null}}}}")
			.getBytes(StandardCharsets.UTF_8);

	static final byte[] PROFILE_SCHEMA = ("{"
			+ "\"$schema\":\"https://json-schema.org/draft/2020-12/schema\","
			+ "\"type\":\"object\","
			+ "\"required\":[\"query\",\"limit\",\"routing\"],"
			+ "\"properties\":{"
			+ "\"query\":{\"type\":\"string\"},"
			+ "\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":100},"
			+ "\"routing\":{\"type\":\"object\","
			+ "\"required\":[\"region\",\"replicas\"],"
			+ "\"properties\":{"
			+ "\"region\":{\"enum\":[\"us-east\",\"us-west\",\"eu-central\"]},"
			+ "\"replicas\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}},"
			+ "\"additionalProperties\":false}},"
			+ "\"additionalProperties\":false}")
			.getBytes(StandardCharsets.UTF_8);

	static final byte[] PROFILE_INSTANCE = ("{\"query\":\"espresso beans\","
			+ "\"limit\":25,\"routing\":{\"region\":\"us-east\","
			+ "\"replicas\":[\"iad-1\",\"iad-2\"]}}")
			.getBytes(StandardCharsets.UTF_8);

	private final URLClassLoader classLoader;
	private final MethodHandle parse;
	private final MethodHandle write;
	private final MethodHandle compile;
	private final MethodHandle evaluate;
	private final Object compilationLimits;
	private final Object evaluationLimits;

	private McpReleaseBenchmarkRuntime(URLClassLoader classLoader,
			MethodHandle parse, MethodHandle write, MethodHandle compile,
			MethodHandle evaluate, Object compilationLimits,
			Object evaluationLimits) {
		this.classLoader = classLoader;
		this.parse = parse;
		this.write = write;
		this.compile = compile;
		this.evaluate = evaluate;
		this.compilationLimits = compilationLimits;
		this.evaluationLimits = evaluationLimits;
	}

	static McpReleaseBenchmarkRuntime open(String artifact) {
		requireNonNull(artifact);
		if (!artifact.equals(BASELINE_ARTIFACT)
				&& !artifact.equals(CANDIDATE_ARTIFACT))
			throw new IllegalArgumentException("Unsupported Soklet artifact: " + artifact);

		String environmentName = artifact.equals(BASELINE_ARTIFACT)
				? BASELINE_JAR_ENVIRONMENT : CANDIDATE_JAR_ENVIRONMENT;
		String configuredPath = System.getenv(environmentName);
		if (configuredPath == null || configuredPath.isBlank())
			throw new IllegalStateException(environmentName + " is required.");
		return open(artifact, Path.of(configuredPath));
	}

	static McpReleaseBenchmarkRuntime open(String artifact, Path artifactPath) {
		requireNonNull(artifact);
		requireNonNull(artifactPath);
		if (!artifact.equals(BASELINE_ARTIFACT)
				&& !artifact.equals(CANDIDATE_ARTIFACT))
			throw new IllegalArgumentException("Unsupported Soklet artifact: " + artifact);
		try {
			Path exactPath = artifactPath.toRealPath();
			if (!(Files.isRegularFile(exactPath) || Files.isDirectory(exactPath)))
				throw new IllegalArgumentException(
						"Soklet benchmark artifact is not a file or classes directory: "
								+ exactPath);
			URLClassLoader loader = new URLClassLoader(
					new URL[] { exactPath.toUri().toURL() },
					ClassLoader.getPlatformClassLoader());
			try {
				return artifact.equals(BASELINE_ARTIFACT)
						? baseline(loader) : candidate(loader);
			} catch (Throwable throwable) {
				loader.close();
				throw throwable;
			}
		} catch (RuntimeException exception) {
			throw exception;
		} catch (Throwable throwable) {
			throw new IllegalStateException(
					"Unable to initialize isolated Soklet " + artifact + " runtime.",
					throwable);
		}
	}

	private static McpReleaseBenchmarkRuntime baseline(URLClassLoader loader)
			throws Throwable {
		Class<?> codecClass = loader.loadClass("com.soklet.McpJsonCodec");
		Method parseMethod = declaredMethod(codecClass, "parse", byte[].class);
		Method writeMethod = declaredSingleArgumentMethod(codecClass,
				"toUtf8Bytes");
		MethodHandles.Lookup lookup = MethodHandles.lookup();
		MethodHandle parse = lookup.unreflect(parseMethod).asType(
				MethodType.methodType(Object.class, byte[].class));
		MethodHandle write = lookup.unreflect(writeMethod).asType(
				MethodType.methodType(byte[].class, Object.class));
		return new McpReleaseBenchmarkRuntime(loader, parse, write,
				null, null, null, null);
	}

	private static McpReleaseBenchmarkRuntime candidate(URLClassLoader loader)
			throws Throwable {
		Class<?> limitsClass = loader.loadClass(
				"com.soklet.internal.mcp.protocol.McpJsonLimits");
		Object jsonLimits = limitsClass.getMethod("productionDefaults")
				.invoke(null);
		Class<?> codecClass = loader.loadClass(
				"com.soklet.internal.mcp.protocol.McpJsonCodec");
		Object codec = codecClass.getConstructor(limitsClass).newInstance(jsonLimits);
		MethodHandles.Lookup lookup = MethodHandles.lookup();
		MethodHandle parse = lookup.unreflect(
				codecClass.getMethod("parse", byte[].class)).bindTo(codec).asType(
				MethodType.methodType(Object.class, byte[].class));
		MethodHandle write = lookup.unreflect(
				declaredSingleArgumentMethod(codecClass, "toUtf8Bytes"))
				.bindTo(codec).asType(
						MethodType.methodType(byte[].class, Object.class));

		Class<?> compilationLimitsClass = loader.loadClass(
				"com.soklet.internal.mcp.schema.McpSchemaCompilationLimits");
		Object compilationLimits = declaredMethod(compilationLimitsClass,
				"productionDefaults").invoke(null);
		Class<?> compilerClass = loader.loadClass(
				"com.soklet.internal.mcp.schema.McpToolSchemaProfileCompiler");
		Constructor<?> compilerConstructor =
				compilerClass.getDeclaredConstructor(compilationLimitsClass);
		compilerConstructor.setAccessible(true);
		Object compiler = compilerConstructor.newInstance(compilationLimits);
		MethodHandle compile = lookup.unreflect(
				declaredSingleArgumentMethod(compilerClass, "compile"))
				.bindTo(compiler).asType(
						MethodType.methodType(Object.class, Object.class));

		Class<?> evaluationLimitsClass = loader.loadClass(
				"com.soklet.internal.mcp.schema.McpSchemaEvaluationLimits");
		Object evaluationLimits = declaredMethod(evaluationLimitsClass,
				"productionDefaults").invoke(null);
		Class<?> evaluatorClass = loader.loadClass(
				"com.soklet.internal.mcp.schema.McpToolSchemaProfileEvaluator");
		Constructor<?> evaluatorConstructor = evaluatorClass.getDeclaredConstructor();
		evaluatorConstructor.setAccessible(true);
		Object evaluator = evaluatorConstructor.newInstance();
		Method evaluateMethod = null;
		for (Method method : evaluatorClass.getDeclaredMethods()) {
			if (method.getName().equals("evaluate")
					&& method.getParameterCount() == 3) {
				evaluateMethod = method;
				break;
			}
		}
		if (evaluateMethod == null)
			throw new NoSuchMethodException(evaluatorClass.getName() + ".evaluate");
		evaluateMethod.setAccessible(true);
		MethodHandle evaluate = lookup.unreflect(evaluateMethod).bindTo(evaluator)
				.asType(MethodType.methodType(Object.class, Object.class,
						Object.class, Object.class));
		return new McpReleaseBenchmarkRuntime(loader, parse, write, compile,
				evaluate, compilationLimits, evaluationLimits);
	}

	Object parse(byte[] input) {
		try {
			return (Object) parse.invokeExact(input);
		} catch (Throwable throwable) {
			throw benchmarkFailure("parse MCP JSON", throwable);
		}
	}

	byte[] write(Object value) {
		try {
			return (byte[]) write.invokeExact(value);
		} catch (Throwable throwable) {
			throw benchmarkFailure("write MCP JSON", throwable);
		}
	}

	Object compile(Object schema) {
		if (compile == null)
			throw new IllegalStateException(
					"Profile 1 is not part of the 3.5.1 comparison artifact.");
		try {
			return (Object) compile.invokeExact(schema);
		} catch (Throwable throwable) {
			throw benchmarkFailure("compile the MCP Tool Schema Profile", throwable);
		}
	}

	Object evaluate(Object program, Object instance) {
		if (evaluate == null)
			throw new IllegalStateException(
					"Profile 1 is not part of the 3.5.1 comparison artifact.");
		try {
			Object result = (Object) evaluate.invokeExact(program, instance,
					evaluationLimits);
			if (!result.getClass().getSimpleName().equals("Valid"))
				throw new IllegalStateException(
						"Profile 1 benchmark instance did not validate: "
								+ result.getClass().getName());
			return result;
		} catch (Throwable throwable) {
			throw benchmarkFailure("evaluate the MCP Tool Schema Profile", throwable);
		}
	}

	Object compilationLimits() {
		return compilationLimits;
	}

	private static Method declaredMethod(Class<?> type, String name,
			Class<?>... parameterTypes) throws NoSuchMethodException {
		Method method = type.getDeclaredMethod(name, parameterTypes);
		method.setAccessible(true);
		return method;
	}

	private static Method declaredSingleArgumentMethod(Class<?> type, String name)
			throws NoSuchMethodException {
		for (Method method : type.getDeclaredMethods()) {
			if (method.getName().equals(name) && method.getParameterCount() == 1) {
				method.setAccessible(true);
				return method;
			}
		}
		throw new NoSuchMethodException(type.getName() + "." + name);
	}

	private static IllegalStateException benchmarkFailure(String operation,
			Throwable throwable) {
		return new IllegalStateException("Unable to " + operation + ".", throwable);
	}

	@Override
	public void close() {
		try {
			classLoader.close();
		} catch (IOException exception) {
			throw new IllegalStateException(
					"Unable to close the isolated Soklet benchmark runtime.", exception);
		}
	}
}
