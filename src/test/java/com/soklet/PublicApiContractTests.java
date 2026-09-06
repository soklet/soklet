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

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.Elements;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.lang.reflect.AnnotatedArrayType;
import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.AnnotatedWildcardType;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Source-inventory contracts for the exported 4.0 public API surface. */
@ThreadSafe
class PublicApiContractTests {
	private static final String THREAD_SAFE = ThreadSafe.class.getName();
	private static final String NOT_THREAD_SAFE = NotThreadSafe.class.getName();
	private static final String IMMUTABLE = Immutable.class.getName();
	private static final Set<String> EXPORTED_PACKAGES = Set.of(
			"com.soklet",
			"com.soklet.annotation",
			"com.soklet.converter",
			"com.soklet.exception");

	@Test
	void exportedSourceInventoryHasThreadSafetyAndReferenceNullnessContracts()
			throws Exception {
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		Assertions.assertNotNull(compiler,
				"PublicApiContractTests requires a full JDK");
		List<Path> sourcePaths;
		try (var paths = Files.walk(Path.of("src/main/java"))) {
			sourcePaths = paths.filter(path -> path.getFileName().toString()
					.endsWith(".java")).sorted().toList();
		}
		DiagnosticCollector<JavaFileObject> diagnostics =
				new DiagnosticCollector<>();
		try (StandardJavaFileManager fileManager = compiler
				.getStandardFileManager(diagnostics, Locale.ROOT,
						StandardCharsets.UTF_8)) {
			JavacTask task = (JavacTask) compiler.getTask(null, fileManager,
					diagnostics, List.of("--release", "17", "-proc:none",
							"-classpath", System.getProperty("java.class.path")),
					null, fileManager.getJavaFileObjectsFromPaths(sourcePaths));
			List<CompilationUnitTree> compilationUnits = new ArrayList<>();
			task.parse().forEach(compilationUnits::add);
			task.analyze();
			List<String> errors = diagnostics.getDiagnostics().stream()
					.filter(diagnostic -> diagnostic.getKind()
							== Diagnostic.Kind.ERROR)
					.map(Object::toString).toList();
			Assertions.assertTrue(errors.isEmpty(),
					() -> "Unable to analyze main sources:\n"
							+ String.join("\n", errors));

			Trees trees = Trees.instance(task);
			Elements elements = task.getElements();
			List<TypeElement> exportedTypes = exportedTypes(compilationUnits,
					trees, elements);
			List<String> mismatches = new ArrayList<>();
			for (TypeElement type : exportedTypes) {
				String binaryName = elements.getBinaryName(type).toString();
				if (type.getKind() == ElementKind.RECORD)
					mismatches.add(binaryName
							+ " must remain an encapsulated non-record type");
				List<String> actual = type.getAnnotationMirrors().stream()
						.map(annotation -> annotation.getAnnotationType().toString())
						.filter(annotation -> annotation.equals(THREAD_SAFE)
								|| annotation.equals(NOT_THREAD_SAFE)
								|| annotation.equals(IMMUTABLE))
						.sorted().toList();
				boolean markerExempt = type.getKind() == ElementKind.ENUM
						|| type.getKind() == ElementKind.ANNOTATION_TYPE;
				if ((!markerExempt && actual.size() != 1)
						|| (markerExempt && actual.size() > 1))
					mismatches.add(binaryName + (markerExempt
							? " has conflicting thread-safety markers "
							: " must declare exactly one thread-safety marker; found ")
							+ actual);

				for (Element enclosed : type.getEnclosedElements()) {
					if (enclosed.getKind() == ElementKind.FIELD
							&& enclosed.getModifiers().contains(Modifier.PUBLIC)
							&& !enclosed.asType().getKind().isPrimitive())
						inspectNullness(binaryName + "#"
								+ enclosed.getSimpleName() + " field",
								enclosed.asType(), true, false, mismatches);
					if (!(enclosed instanceof ExecutableElement method)
							|| !method.getModifiers().contains(Modifier.PUBLIC)
							|| !isSourceAuthoredMethod(method, trees))
						continue;
					String methodOwner = binaryName + "#"
							+ method.getSimpleName();
					for (int index = 0; index < method.getParameters().size();
							++index) {
						TypeMirror parameterType = method.getParameters().get(index)
								.asType();
						if (!parameterType.getKind().isPrimitive())
							inspectNullness(methodOwner + " parameter " + index,
									parameterType, true, false, mismatches);
					}
					if (method.getReturnType().getKind() != TypeKind.VOID
							&& !method.getReturnType().getKind().isPrimitive())
						inspectNullness(methodOwner + " return",
								method.getReturnType(), true, false, mismatches);
				}
			}
			Assertions.assertTrue(mismatches.isEmpty(),
					() -> "Exported source-contract mismatches:\n - "
							+ String.join("\n - ", mismatches));
		}
	}

	@Test
	void collectionStageAndArrayPayloadsHaveExactTypeUseNullability()
			throws Exception {
		assertTypeArgument(SokletConfig.class.getMethod("getLifecycleObservers")
				.getAnnotatedReturnType(), 0, NonNull.class);
		assertTypeArgument(SokletConfig.class.getMethod("getHttpServer")
				.getAnnotatedReturnType(), 0, NonNull.class);
		assertTypeArgument(SokletConfig.class.getMethod("getSseServer")
				.getAnnotatedReturnType(), 0, NonNull.class);
		assertTypeArgument(Soklet.class.getMethod("shutdown")
				.getAnnotatedReturnType(), 0, NonNull.class);
		assertTypeArgument(TransportDelegateAttachment.class
				.getMethod("whenTerminated").getAnnotatedReturnType(), 0,
				Nullable.class);

		AnnotatedType broadcaster = typeArgument(SseServer.class
				.getMethod("acquireBroadcaster", ResourcePath.class)
				.getAnnotatedReturnType(), 0);
		AnnotatedWildcardType broadcasterWildcard = Assertions.assertInstanceOf(
				AnnotatedWildcardType.class, broadcaster);
		assertExactNullness(broadcasterWildcard.getAnnotatedUpperBounds()[0],
				NonNull.class);

		assertConsumerPayload(HttpServer.RequestHandler.class);
		assertConsumerPayload(SseServer.RequestHandler.class);
		assertSupplierPayload(HttpServer.Builder.class);
		assertSupplierPayload(SseServer.Builder.class);

		Method run = SokletApplication.class.getMethod("run",
				ShutdownTrigger[].class);
		AnnotatedArrayType triggers = Assertions.assertInstanceOf(
				AnnotatedArrayType.class, run.getAnnotatedParameterTypes()[0]);
		assertExactNullness(triggers, NonNull.class);
		assertExactNullness(triggers.getAnnotatedGenericComponentType(),
				NonNull.class);

		AnnotatedType observers = SimulatorConfig.Builder.class.getMethod(
				"lifecycleObservers", java.util.Collection.class)
				.getAnnotatedParameterTypes()[0];
		assertExactNullness(observers, Nullable.class);
		AnnotatedWildcardType observerWildcard = Assertions.assertInstanceOf(
				AnnotatedWildcardType.class, typeArgument(observers, 0));
		assertExactNullness(observerWildcard.getAnnotatedUpperBounds()[0],
				NonNull.class);
	}

	@Test
	void simulatorServerConfigurationSurfaceRemainsExact() throws Exception {
		Set<String> serverMethodNames = Set.of("httpServer", "sseServer",
				"mcpServer", "configureMcpServer");
		Set<String> actual = java.util.Arrays.stream(
					SimulatorConfig.Builder.class.getDeclaredMethods())
				.filter(method -> java.lang.reflect.Modifier.isPublic(
						method.getModifiers()))
				.filter(method -> serverMethodNames.contains(method.getName()))
				.map(PublicApiContractTests::methodDescriptor)
				.collect(java.util.stream.Collectors.toUnmodifiableSet());
		Assertions.assertEquals(Set.of(
				"configureMcpServer(java.util.function.Consumer)",
				"httpServer()",
				"httpServer(java.util.function.Consumer)",
				"sseServer()",
				"sseServer(java.util.function.Consumer)"), actual);

		assertConsumerParameter(SimulatorConfig.Builder.class.getMethod(
				"httpServer", java.util.function.Consumer.class), HttpServer.class);
		assertConsumerParameter(SimulatorConfig.Builder.class.getMethod(
				"sseServer", java.util.function.Consumer.class), SseServer.class);
		assertConsumerParameter(SimulatorConfig.Builder.class.getMethod(
				"configureMcpServer", java.util.function.Consumer.class),
				McpServer.Builder.class);
	}

	private static void assertConsumerParameter(Method method,
			Class<?> payloadType) {
		AnnotatedType consumer = method.getAnnotatedParameterTypes()[0];
		assertExactNullness(consumer, NonNull.class);
		AnnotatedType payload = typeArgument(consumer, 0);
		Assertions.assertEquals(payloadType, payload.getType(), method::toString);
		assertExactNullness(payload, NonNull.class);
	}

	private static void assertConsumerPayload(Class<?> handlerType)
			throws Exception {
		AnnotatedType consumer = handlerType.getMethod("handleRequest",
				Request.class, java.util.function.Consumer.class)
				.getAnnotatedParameterTypes()[1];
		assertExactNullness(consumer, NonNull.class);
		assertTypeArgument(consumer, 0, NonNull.class);
	}

	private static void assertSupplierPayload(Class<?> builderType)
			throws Exception {
		AnnotatedType supplier = builderType.getMethod(
				"requestHandlerExecutorServiceSupplier",
				java.util.function.Supplier.class).getAnnotatedParameterTypes()[0];
		assertExactNullness(supplier, Nullable.class);
		assertTypeArgument(supplier, 0, NonNull.class);
	}

	private static void assertTypeArgument(AnnotatedType type, int index,
			Class<? extends java.lang.annotation.Annotation> annotation) {
		assertExactNullness(typeArgument(type, index), annotation);
	}

	private static void assertExactNullness(AnnotatedType type,
			Class<? extends java.lang.annotation.Annotation> annotation) {
		Assertions.assertTrue(hasExactNullness(type, annotation),
				type.getType().getTypeName());
	}

	private static boolean hasExactNullness(AnnotatedType type,
			Class<? extends java.lang.annotation.Annotation> annotation) {
		return type.isAnnotationPresent(annotation)
				&& !type.isAnnotationPresent(annotation == NonNull.class
				? Nullable.class : NonNull.class);
	}

	private static List<TypeElement> exportedTypes(
			List<CompilationUnitTree> compilationUnits, Trees trees,
			Elements elements) {
		List<TypeElement> result = new ArrayList<>();
		for (CompilationUnitTree compilationUnit : compilationUnits) {
			String packageName = compilationUnit.getPackageName() == null ? ""
					: compilationUnit.getPackageName().toString();
			if (!EXPORTED_PACKAGES.contains(packageName))
				continue;
			for (Tree declaration : compilationUnit.getTypeDecls()) {
				Element element = trees.getElement(
						TreePath.getPath(compilationUnit, declaration));
				if (element instanceof TypeElement type
						&& type.getModifiers().contains(Modifier.PUBLIC))
					appendExportedType(type, result);
			}
		}
		result.sort(Comparator.comparing(
				type -> elements.getBinaryName(type).toString()));
		return List.copyOf(result);
	}

	private static void appendExportedType(TypeElement type,
			List<TypeElement> result) {
		result.add(type);
		for (Element enclosed : type.getEnclosedElements()) {
			if (enclosed instanceof TypeElement nestedType
					&& nestedType.getModifiers().contains(Modifier.PUBLIC))
				appendExportedType(nestedType, result);
		}
	}

	private static boolean isSourceAuthoredMethod(ExecutableElement method,
			Trees trees) {
		TreePath path = trees.getPath(method);
		return path != null && path.getLeaf() instanceof MethodTree;
	}

	private static void inspectNullness(String owner, TypeMirror type,
			boolean root, boolean requireNonNull, List<String> mismatches) {
		if ((root || requiresNestedNullness(type))
				&& !(requireNonNull ? hasExactNullness(type, NonNull.class)
						: hasAnyExactNullness(type)))
			mismatches.add(owner + (root ? "" : " nested type")
					+ " lacks " + (requireNonNull ? "@NonNull" : "nullness")
					+ " at " + type);

		if (type instanceof DeclaredType declaredType) {
			boolean optional = ((TypeElement) declaredType.asElement())
					.getQualifiedName().contentEquals("java.util.Optional");
			List<? extends TypeMirror> arguments = declaredType.getTypeArguments();
			for (int index = 0; index < arguments.size(); ++index)
				inspectNullness(owner + " type argument " + index,
						arguments.get(index), false, optional, mismatches);
		} else if (type instanceof ArrayType arrayType) {
			TypeMirror componentType = arrayType.getComponentType();
			if (!componentType.getKind().isPrimitive())
				inspectNullness(owner + " array component", componentType,
						false, false, mismatches);
		} else if (type instanceof WildcardType wildcardType) {
			TypeMirror extendsBound = wildcardType.getExtendsBound();
			if (extendsBound != null)
				inspectNullness(owner + " wildcard upper bound",
						extendsBound, false, requireNonNull, mismatches);
			TypeMirror superBound = wildcardType.getSuperBound();
			if (superBound != null)
				inspectNullness(owner + " wildcard lower bound", superBound,
						false, requireNonNull, mismatches);
		}
	}

	private static boolean requiresNestedNullness(TypeMirror type) {
		return type.getKind() != TypeKind.WILDCARD
				&& !type.getKind().isPrimitive();
	}

	private static boolean hasAnyExactNullness(TypeMirror type) {
		return hasExactNullness(type, NonNull.class)
				|| hasExactNullness(type, Nullable.class);
	}

	private static boolean hasExactNullness(TypeMirror type,
			Class<? extends java.lang.annotation.Annotation> annotation) {
		String expected = annotation.getName();
		String opposite = (annotation == NonNull.class ? Nullable.class
				: NonNull.class).getName();
		Set<String> annotations = type.getAnnotationMirrors().stream()
				.map(value -> value.getAnnotationType().toString())
				.collect(java.util.stream.Collectors.toUnmodifiableSet());
		return annotations.contains(expected) && !annotations.contains(opposite);
	}

	private static AnnotatedType typeArgument(AnnotatedType type, int index) {
		AnnotatedParameterizedType parameterized = Assertions.assertInstanceOf(
				AnnotatedParameterizedType.class, type);
		return parameterized.getAnnotatedActualTypeArguments()[index];
	}

	private static String methodDescriptor(Method method) {
		return method.getName() + java.util.Arrays.stream(method.getParameterTypes())
				.map(Class::getName)
				.collect(java.util.stream.Collectors.joining(",", "(", ")"));
	}

}
