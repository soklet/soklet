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

import com.sun.source.doctree.DocCommentTree;
import com.sun.source.doctree.DocTree;
import com.sun.source.doctree.ParamTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.DocTrees;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreeScanner;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Completeness checks for Javadocs on the reviewed MCP public API inventory.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public class McpPublicJavadocTests {
	private static final List<Path> INCLUDE_FILES = List.of(
			Path.of("api/mcp/phase-4.includes"),
			Path.of("api/mcp/phase-5.includes"),
			Path.of("api/mcp/phase-6.includes"),
			Path.of("api/mcp/provisional.includes")
	);
	private static final Pattern TYPE_NAME = Pattern.compile(
			"[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)+");

	@Test
	public void phaseZeroReviewedIncludeUnionIsEmpty() throws IOException {
		ReviewedIncludes includes = loadReviewedIncludes();

		Assertions.assertTrue(includes.typeNames().isEmpty(),
				() -> "MCP-free Phase 0 requires empty phase/provisional include files, but found "
						+ includes.typeNames());
	}

	@Test
	public void everyReviewedMcpPublicApiElementHasDocumentation() throws IOException {
		ReviewedIncludes includes = loadReviewedIncludes();

		if (includes.typeNames().isEmpty())
			return;

		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		Assertions.assertNotNull(compiler, "McpPublicJavadocTests requires a full JDK, not a JRE");

		List<Path> sourcePaths;

		try (var paths = Files.walk(Path.of("src/main/java"))) {
			sourcePaths = paths
					.filter(path -> path.getFileName().toString().endsWith(".java"))
					.sorted()
					.toList();
		}

		Assertions.assertFalse(sourcePaths.isEmpty(), "No main Java sources were found");

		try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, Locale.ROOT,
				StandardCharsets.UTF_8)) {
			Iterable<? extends JavaFileObject> sources = fileManager.getJavaFileObjectsFromPaths(sourcePaths);
			List<String> missingDocumentation = inspectDocumentation(compiler, fileManager, sources,
					includes.typeNames());

			Assertions.assertTrue(missingDocumentation.isEmpty(),
					() -> "Missing public documentation:\n - " + String.join("\n - ", missingDocumentation));
		}
	}

	@Test
	public void compilerInspectionCoversEverySupportedPublicApiElement() throws IOException {
		String source = """
				package fixtures;

				/** Complete fixture. */
				public class CompleteApi {
					/** Extension field. */
					protected int field;

					/** Constructor. */
					protected CompleteApi() {}

					/** Method. */
					public void method() {}

					/** Nested extension type. */
					protected static class Nested {
						/** Nested constructor. */
						protected Nested() {}

						/** Nested extension method. */
						protected void extend() {}
					}

					/** Annotation type. */
					public @interface Marker {
						/** Annotation element. */
						String value();
					}

					/** Enum type. */
					public enum Choice {
						/** Enum constant. */
						FIRST
					}

					/**
					 * Record type.
					 *
					 * @param name record component and implicit-accessor documentation
					 */
					public record Item(String name) {
						/** Explicit compact canonical constructor. */
						public Item {}

						/** Explicit accessor. */
						@Override
						public String name() {
							return name;
						}
					}

					/**
					 * Record with compiler-provided constructor and accessor.
					 *
					 * @param value record component and implicit-member documentation
					 */
					public record ImplicitItem(String value) {}
				}
				""";
		Set<String> typeNames = new LinkedHashSet<>(List.of(
				"fixtures.CompleteApi",
				"fixtures.CompleteApi$Choice",
				"fixtures.CompleteApi$ImplicitItem",
				"fixtures.CompleteApi$Item",
				"fixtures.CompleteApi$Marker",
				"fixtures.CompleteApi$Nested"
		));

		Assertions.assertEquals(List.of(), inspectFixture("fixtures.CompleteApi", source, typeNames));
	}

	@Test
	public void compilerInspectionReportsEveryUndocumentedSupportedPublicApiElement() throws IOException {
		String source = """
				package fixtures;

				public class MissingApi {
					protected int field;
					protected MissingApi() {}
					public void method() {}

					protected static class Nested {
						protected Nested() {}
						protected void extend() {}
					}

					public @interface Marker {
						String value();
					}

					public enum Choice {
						FIRST
					}

					public record Item(String name) {
						public Item {}

						@Override
						public String name() {
							return name;
						}
					}
				}
				""";
		Set<String> typeNames = new LinkedHashSet<>(List.of(
				"fixtures.MissingApi",
				"fixtures.MissingApi$Choice",
				"fixtures.MissingApi$Item",
				"fixtures.MissingApi$Marker",
				"fixtures.MissingApi$Nested"
		));
		List<String> missingDocumentation = inspectFixture("fixtures.MissingApi", source, typeNames);

		assertMissing(missingDocumentation, "MissingApi [CLASS:");
		assertMissing(missingDocumentation, "MissingApi.<init> [CONSTRUCTOR:");
		assertMissing(missingDocumentation, "MissingApi.field [FIELD:");
		assertMissing(missingDocumentation, "MissingApi.method [METHOD:");
		assertMissing(missingDocumentation, "MissingApi.Nested [CLASS:");
		assertMissing(missingDocumentation, "MissingApi.Nested.<init> [CONSTRUCTOR:");
		assertMissing(missingDocumentation, "MissingApi.Nested.extend [METHOD:");
		assertMissing(missingDocumentation, "MissingApi.Marker [ANNOTATION_TYPE:");
		assertMissing(missingDocumentation, "MissingApi.Marker.value [METHOD:");
		assertMissing(missingDocumentation, "MissingApi.Choice [ENUM:");
		assertMissing(missingDocumentation, "MissingApi.Choice.FIRST [ENUM_CONSTANT:");
		assertMissing(missingDocumentation, "MissingApi.Item [RECORD:");
		assertMissing(missingDocumentation, "MissingApi.Item.name [RECORD_COMPONENT:");
		assertMissing(missingDocumentation, "MissingApi.Item.<init> [CONSTRUCTOR:");
		assertMissing(missingDocumentation, "MissingApi.Item.name [METHOD:");
		Assertions.assertTrue(missingDocumentation.stream().noneMatch(missing ->
				missing.contains("values") || missing.contains("valueOf") || missing.contains("toString") ||
						missing.contains("hashCode") || missing.contains("equals")),
				() -> "Compiler-generated members must not require authored documentation: " + missingDocumentation);
	}

	private static List<String> inspectFixture(String binaryName,
														 String source,
														 Set<String> typeNames) throws IOException {
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		Assertions.assertNotNull(compiler, "McpPublicJavadocTests requires a full JDK, not a JRE");

		try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, Locale.ROOT,
				StandardCharsets.UTF_8)) {
			return inspectDocumentation(compiler, fileManager,
					List.of(new StringJavaFileObject(binaryName, source)), typeNames);
		}
	}

	private static List<String> inspectDocumentation(JavaCompiler compiler,
															 StandardJavaFileManager fileManager,
															 Iterable<? extends JavaFileObject> sources,
															 Set<String> typeNames) throws IOException {
		DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
		List<String> options = List.of(
				"--release", "17",
				"-proc:none",
				"-classpath", System.getProperty("java.class.path")
		);
		JavacTask task = (JavacTask) compiler.getTask(null, fileManager, diagnostics, options, null, sources);
		List<CompilationUnitTree> compilationUnits = new ArrayList<>();

		task.parse().forEach(compilationUnits::add);
		Set<Tree> sourceAuthoredDeclarations = sourceAuthoredDeclarations(compilationUnits);
		task.analyze();
		List<String> compilationErrors = diagnostics.getDiagnostics().stream()
				.filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
				.map(McpPublicJavadocTests::formatDiagnostic)
				.toList();
		Assertions.assertTrue(compilationErrors.isEmpty(),
				() -> "Unable to analyze sources for MCP Javadoc completeness:\n"
						+ String.join("\n", compilationErrors));

		DocTrees docTrees = DocTrees.instance(task);
		Map<String, TypeElement> sourceTypesByBinaryName = indexSourceTypes(compilationUnits, docTrees,
				task.getElements());
		List<String> missingDocumentation = new ArrayList<>();
		Set<Element> inspected = new LinkedHashSet<>();

		for (String typeName : typeNames) {
			TypeElement type = sourceTypesByBinaryName.get(typeName);

			if (type == null) {
				missingDocumentation.add(typeName + " (reviewed type is missing or has no current source)");
				continue;
			}

			if (!isPublicOrProtected(type)) {
				missingDocumentation.add(typeName + " (reviewed type is not public or protected)");
				continue;
			}

			inspectExportedType(type, docTrees, sourceAuthoredDeclarations, inspected, missingDocumentation);
		}

		return missingDocumentation;
	}

	private static Map<String, TypeElement> indexSourceTypes(List<CompilationUnitTree> compilationUnits,
																				 DocTrees docTrees,
																				 Elements elements) {
		Map<String, TypeElement> sourceTypesByBinaryName = new LinkedHashMap<>();

		for (CompilationUnitTree compilationUnit : compilationUnits) {
			for (Tree declaration : compilationUnit.getTypeDecls()) {
				Element element = docTrees.getElement(TreePath.getPath(compilationUnit, declaration));

				if (element instanceof TypeElement type)
					indexSourceType(type, elements, sourceTypesByBinaryName);
			}
		}

		return sourceTypesByBinaryName;
	}

	private static void indexSourceType(TypeElement type,
														Elements elements,
														Map<String, TypeElement> sourceTypesByBinaryName) {
		String binaryName = elements.getBinaryName(type).toString();
		TypeElement previous = sourceTypesByBinaryName.putIfAbsent(binaryName, type);

		Assertions.assertNull(previous, () -> "Duplicate source type binary name: " + binaryName);

		for (Element element : type.getEnclosedElements()) {
			ElementKind kind = element.getKind();

			if ((kind.isClass() || kind.isInterface()) && element instanceof TypeElement nestedType)
				indexSourceType(nestedType, elements, sourceTypesByBinaryName);
		}
	}

	private static Set<Tree> sourceAuthoredDeclarations(List<CompilationUnitTree> compilationUnits) {
		Set<Tree> declarations = Collections.newSetFromMap(new IdentityHashMap<>());
		TreeScanner<Void, Void> declarationScanner = new TreeScanner<>() {
			@Override
			public Void visitMethod(MethodTree method, Void unused) {
				declarations.add(method);
				return super.visitMethod(method, unused);
			}

			@Override
			public Void visitVariable(VariableTree variable, Void unused) {
				declarations.add(variable);
				return super.visitVariable(variable, unused);
			}
		};

		for (CompilationUnitTree compilationUnit : compilationUnits)
			declarationScanner.scan(compilationUnit, null);

		return declarations;
	}

	private static void inspectExportedType(TypeElement type,
																		 DocTrees docTrees,
																		 Set<Tree> sourceAuthoredDeclarations,
																		 Set<Element> inspected,
																		 List<String> missingDocumentation) {
		if (!inspected.add(type))
			return;

		requireDocumentation(type, docTrees, missingDocumentation);

		if (type.getKind() == ElementKind.RECORD)
			requireRecordComponentDocumentation(type, docTrees, missingDocumentation);

		for (Element element : type.getEnclosedElements()) {
			ElementKind kind = element.getKind();

			if (kind.isClass() || kind.isInterface()) {
				if (isPublicOrProtected(element))
					inspectExportedType((TypeElement) element, docTrees, sourceAuthoredDeclarations, inspected,
							missingDocumentation);
				continue;
			}

			if (isDocumentedMemberKind(kind) && isPublicOrProtected(element) &&
					isSourceAuthoredMember(element, docTrees, sourceAuthoredDeclarations))
				requireDocumentation(element, docTrees, missingDocumentation);
		}
	}

	private static void requireRecordComponentDocumentation(TypeElement recordType,
																			DocTrees docTrees,
																			List<String> missingDocumentation) {
		DocCommentTree recordComment = docTrees.getDocCommentTree(recordType);
		Set<String> documentedComponents = new LinkedHashSet<>();

		if (recordComment != null) {
			for (DocTree blockTag : recordComment.getBlockTags()) {
				if (blockTag instanceof ParamTree parameter && !parameter.isTypeParameter() &&
						!parameter.getDescription().stream().map(Object::toString).reduce("", String::concat).isBlank())
					documentedComponents.add(parameter.getName().getName().toString());
			}
		}

		for (Element component : recordType.getRecordComponents()) {
			if (!documentedComponents.contains(component.getSimpleName().toString()))
				missingDocumentation.add(describe(component) +
						" (missing non-empty record-level @param documentation)");
		}
	}

	private static boolean isSourceAuthoredMember(Element element,
																DocTrees docTrees,
																Set<Tree> sourceAuthoredDeclarations) {
		TreePath path = docTrees.getPath(element);

		if (path == null)
			return false;

		Tree declaration = path.getLeaf();

		if (!sourceAuthoredDeclarations.contains(declaration))
			return false;

		ElementKind kind = element.getKind();

		if (kind == ElementKind.CONSTRUCTOR || kind == ElementKind.METHOD)
			return declaration instanceof MethodTree;

		if (kind == ElementKind.FIELD || kind == ElementKind.ENUM_CONSTANT)
			return declaration instanceof VariableTree;

		return false;
	}

	private static boolean isDocumentedMemberKind(ElementKind kind) {
		return kind == ElementKind.CONSTRUCTOR ||
				kind == ElementKind.METHOD ||
				kind == ElementKind.FIELD ||
				kind == ElementKind.ENUM_CONSTANT;
	}

	private static boolean isPublicOrProtected(Element element) {
		Set<Modifier> modifiers = element.getModifiers();
		return modifiers.contains(Modifier.PUBLIC) || modifiers.contains(Modifier.PROTECTED);
	}

	private static void requireDocumentation(Element element,
															 DocTrees docTrees,
															 List<String> missingDocumentation) {
		DocCommentTree comment = docTrees.getDocCommentTree(element);

		if (comment == null || comment.toString().isBlank())
			missingDocumentation.add(describe(element));
	}

	private static String describe(Element element) {
		List<String> names = new ArrayList<>();
		Element current = element;

		while (current != null && current.getKind() != ElementKind.PACKAGE) {
			names.add(current.getSimpleName().toString());
			current = current.getEnclosingElement();
		}

		java.util.Collections.reverse(names);
		return String.join(".", names) + " [" + element.getKind() + ": " + element + "]";
	}

	private static ReviewedIncludes loadReviewedIncludes() throws IOException {
		Map<String, Path> owners = new LinkedHashMap<>();

		for (Path includeFile : INCLUDE_FILES) {
			Assertions.assertTrue(Files.isRegularFile(includeFile),
					() -> "Missing reviewed MCP API include file: " + includeFile);
			List<String> entries = Files.readAllLines(includeFile, StandardCharsets.UTF_8).stream()
					.map(String::trim)
					.filter(line -> !line.isEmpty() && !line.startsWith("#"))
					.toList();
			List<String> sortedEntries = entries.stream().sorted(Comparator.naturalOrder()).toList();

			Assertions.assertEquals(sortedEntries, entries,
					() -> "MCP API include file must be sorted: " + includeFile);

			for (String entry : entries) {
				Assertions.assertTrue(TYPE_NAME.matcher(entry).matches(),
						() -> "Malformed MCP API type name '" + entry + "' in " + includeFile);
				Path previousOwner = owners.putIfAbsent(entry, includeFile);
				Assertions.assertNull(previousOwner,
						() -> "MCP API type '" + entry + "' appears in both " + previousOwner + " and " + includeFile);
			}
		}

		return new ReviewedIncludes(new LinkedHashSet<>(owners.keySet()));
	}

	private static String formatDiagnostic(Diagnostic<? extends JavaFileObject> diagnostic) {
		JavaFileObject source = diagnostic.getSource();
		String location = source == null ? "<unknown>" : source.getName();
		return "%s:%d: %s".formatted(location, diagnostic.getLineNumber(), diagnostic.getMessage(Locale.ROOT));
	}

	private static void assertMissing(List<String> missingDocumentation, String fragment) {
		Assertions.assertTrue(missingDocumentation.stream().anyMatch(missing -> missing.contains(fragment)),
				() -> "Expected missing-documentation entry containing '" + fragment + "', but found " +
						missingDocumentation);
	}

	private static final class StringJavaFileObject extends SimpleJavaFileObject {
		private final String source;

		private StringJavaFileObject(String binaryName, String source) {
			super(URI.create("string:///" + binaryName.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
			this.source = source;
		}

		@Override
		public CharSequence getCharContent(boolean ignoreEncodingErrors) {
			return source;
		}
	}

	private record ReviewedIncludes(Set<String> typeNames) {}
}
