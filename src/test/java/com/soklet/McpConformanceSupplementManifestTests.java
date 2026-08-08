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

import com.soklet.internal.mcp.protocol.McpJsonArray;
import com.soklet.internal.mcp.protocol.McpJsonCodec;
import com.soklet.internal.mcp.protocol.McpJsonLimits;
import com.soklet.internal.mcp.protocol.McpJsonNumber;
import com.soklet.internal.mcp.protocol.McpJsonObject;
import com.soklet.internal.mcp.protocol.McpJsonString;
import com.soklet.internal.mcp.protocol.McpJsonValue;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.platform.commons.annotation.Testable;
import org.junit.platform.commons.support.AnnotationSupport;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Verifies that the official conformance manifest's local test evidence cannot
 * silently drift away from the compiled test suite.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public class McpConformanceSupplementManifestTests {
	private static final Path MANIFEST = Path.of("conformance", "official",
			"scenarios.json");

	@Test
	public void everyRunLocalSupplementResolvesToExactlyOneJUnitTestMethod()
			throws IOException, URISyntaxException {
		McpJsonObject manifest = object(new McpJsonCodec(
				McpJsonLimits.productionDefaults()).parse(Files.readAllBytes(MANIFEST)),
				"manifest root");
		int currentPhase = integer(member(manifest, "currentImplementationPhase"),
				"current implementation phase");
		McpJsonArray scenarios = array(member(manifest, "scenarios"),
				"manifest scenarios");
		Map<String, List<String>> classesBySimpleName = compiledTestClasses();
		List<String> failures = new ArrayList<>();
		int referenceCount = 0;

		for (McpJsonValue scenarioValue : scenarios.values()) {
			McpJsonObject scenario = object(scenarioValue, "scenario");
			String scenarioName = string(member(scenario, "name"),
					"scenario name");
			String selection = string(member(scenario, "selection"),
					"selection for " + scenarioName);
			if (!selection.equals("RUN"))
				continue;
			int earliestPhase = integer(member(scenario, "earliestPhase"),
					"earliest phase for " + scenarioName);
			if (earliestPhase <= currentPhase)
				string(member(scenario, "expectedCheckProfile"),
						"expected check profile for active scenario " + scenarioName);
			McpJsonArray supplements = array(member(scenario, "localSupplements"),
					"local supplements for " + scenarioName);

			for (McpJsonValue supplementValue : supplements.values()) {
				++referenceCount;
				String reference = string(supplementValue,
						"local supplement for " + scenarioName);
				validateReference(scenarioName, reference, classesBySimpleName,
						failures);
			}
		}

		Assertions.assertTrue(referenceCount > 0,
				"The RUN conformance scenarios do not declare any local supplements");
		Assertions.assertTrue(failures.isEmpty(), () ->
				"Invalid RUN conformance localSupplements references:\n - "
						+ String.join("\n - ", failures));
	}

	private static void validateReference(String scenarioName, String reference,
			Map<String, List<String>> classesBySimpleName, List<String> failures) {
		int separator = reference.indexOf('#');
		if (separator <= 0 || separator != reference.lastIndexOf('#')
				|| separator == reference.length() - 1
				|| !reference.equals(reference.strip())) {
			failures.add(scenarioName + ": malformed reference " + reference);
			return;
		}

		String classReference = reference.substring(0, separator);
		String methodName = reference.substring(separator + 1);
		List<String> matchingClasses = classReference.indexOf('.') >= 0
				? classesBySimpleName.values().stream().flatMap(List::stream)
				.filter(classReference::equals).toList()
				: classesBySimpleName.getOrDefault(classReference, List.of());

		if (matchingClasses.isEmpty()) {
			failures.add(scenarioName + ": missing compiled test class for "
					+ reference);
			return;
		}
		if (matchingClasses.size() > 1) {
			failures.add(scenarioName + ": ambiguous compiled test class for "
					+ reference + " (" + String.join(", ", matchingClasses) + ")");
			return;
		}

		Class<?> testClass;
		try {
			testClass = Class.forName(matchingClasses.get(0), false,
					McpConformanceSupplementManifestTests.class.getClassLoader());
		} catch (ClassNotFoundException | LinkageError exception) {
			failures.add(scenarioName + ": cannot load compiled test class for "
					+ reference + " (" + exception + ")");
			return;
		}

		List<Method> matchingMethods = List.of(testClass.getDeclaredMethods()).stream()
				.filter(method -> method.getName().equals(methodName)).toList();
		if (matchingMethods.isEmpty()) {
			failures.add(scenarioName + ": missing method for " + reference);
			return;
		}
		if (matchingMethods.size() > 1) {
			failures.add(scenarioName + ": overloaded method for " + reference);
			return;
		}
		if (!AnnotationSupport.isAnnotated(matchingMethods.get(0), Testable.class))
			failures.add(scenarioName + ": method is not annotated or meta-annotated "
					+ "as a JUnit test: " + reference);
	}

	private static Map<String, List<String>> compiledTestClasses()
			throws IOException, URISyntaxException {
		Path classesRoot = Path.of(McpConformanceSupplementManifestTests.class
				.getProtectionDomain().getCodeSource().getLocation().toURI());
		Map<String, List<String>> classesBySimpleName = new LinkedHashMap<>();

		try (var paths = Files.walk(classesRoot)) {
			for (Path path : paths.filter(Files::isRegularFile)
					.filter(candidate -> candidate.getFileName().toString()
							.endsWith(".class"))
					.filter(candidate -> !candidate.getFileName().toString()
							.contains("$"))
					.sorted().toList()) {
				String relativeName = classesRoot.relativize(path).toString();
				String qualifiedName = relativeName.substring(0,
						relativeName.length() - ".class".length())
						.replace(classesRoot.getFileSystem().getSeparator(), ".");
				int packageSeparator = qualifiedName.lastIndexOf('.');
				String simpleName = qualifiedName.substring(packageSeparator + 1);
				classesBySimpleName.computeIfAbsent(simpleName,
						ignored -> new ArrayList<>()).add(qualifiedName);
			}
		}

		return classesBySimpleName;
	}

	private static McpJsonValue member(McpJsonObject object, String name) {
		McpJsonValue value = object.members().get(name);
		if (value == null)
			throw new AssertionError("Missing JSON member: " + name);
		return value;
	}

	private static McpJsonObject object(McpJsonValue value, String description) {
		if (value instanceof McpJsonObject object)
			return object;
		throw new AssertionError(description + " must be a JSON object");
	}

	private static McpJsonArray array(McpJsonValue value, String description) {
		if (value instanceof McpJsonArray array)
			return array;
		throw new AssertionError(description + " must be a JSON array");
	}

	private static String string(McpJsonValue value, String description) {
		if (value instanceof McpJsonString string)
			return string.value();
		throw new AssertionError(description + " must be a JSON string");
	}

	private static int integer(McpJsonValue value, String description) {
		if (value instanceof McpJsonNumber number) {
			try {
				return number.value().intValueExact();
			} catch (ArithmeticException ignored) {
				// Fall through to the shape failure below.
			}
		}
		throw new AssertionError(description + " must be a JSON integer");
	}
}
