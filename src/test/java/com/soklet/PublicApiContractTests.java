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
import com.sun.source.tree.TypeParameterTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
import java.util.function.Function;

/** Explicit nullability for owned declarations and thread safety for the exported API. */
@ThreadSafe
class PublicApiContractTests {
	private static final String THREAD_SAFE = ThreadSafe.class.getName();
	private static final String NOT_THREAD_SAFE = NotThreadSafe.class.getName();
	private static final String IMMUTABLE = Immutable.class.getName();
	// Inherited jsr305 annotations on these extension points force consumers to
	// supply a provided-only dependency during annotation processing. Require the
	// explicit source documentation instead; consumer compilation is tested too.
	private static final Set<String> DOCUMENTED_BASE_CLASS_CONTRACTS = Set.of(
			"com.soklet.StreamingResponseCanceledException",
			"com.soklet.converter.AbstractValueConverter",
			"com.soklet.converter.FromStringValueConverter",
			"com.soklet.converter.TypeReference",
			"com.soklet.converter.ValueConversionException",
			"com.soklet.exception.BadRequestException");

	@Test
	void ownedDeclarationsHaveNullnessAndExportedTypesHaveThreadSafetyContracts()
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
					diagnostics, List.of("--release", "21", "-proc:none",
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
				boolean documentedBase = DOCUMENTED_BASE_CLASS_CONTRACTS.contains(binaryName);
				if (documentedBase) {
					String documentation = elements.getDocComment(type);
					if (!actual.isEmpty() || documentation == null
							|| !documentation.contains("<strong>Thread safety:</strong>"))
						mismatches.add(binaryName + " must document thread safety without an inherited jsr305 marker");
				}
				if ((!markerExempt && !documentedBase && actual.size() != 1)
						|| (markerExempt && actual.size() > 1))
					mismatches.add(binaryName + (markerExempt
							? " has conflicting thread-safety markers "
							: " must declare exactly one thread-safety marker; found ")
							+ actual);

			}
			inspectOwnedDeclarations(compilationUnits, trees, elements, mismatches);
			Assertions.assertTrue(mismatches.isEmpty(),
					() -> "Owned nullability/exported thread-safety mismatches:\n - "
							+ String.join("\n - ", mismatches));
		}
	}

	@Test
	void corsCallbacksAcceptNullableResultsAndNonNullResultFunctions()
			throws Exception {
		for (Method method : CorsAuthorizer.class.getDeclaredMethods()) {
			if (!method.getName().equals("fromWhitelistedOrigins")
					&& !method.getName().equals("fromWhitelistAuthorizer"))
				continue;
			for (AnnotatedType parameter : method.getAnnotatedParameterTypes()) {
				if (!(parameter instanceof AnnotatedParameterizedType callback)
						|| callback.getType().getTypeName().startsWith("java.util.Set"))
					continue;
				assertExactNullness(callback, NonNull.class);
				assertExactNullness(callback.getAnnotatedActualTypeArguments()[0], NonNull.class);
				AnnotatedWildcardType result = Assertions.assertInstanceOf(
						AnnotatedWildcardType.class, callback.getAnnotatedActualTypeArguments()[1]);
				Assertions.assertEquals(Boolean.class, result.getAnnotatedUpperBounds()[0].getType());
				assertExactNullness(result.getAnnotatedUpperBounds()[0], Nullable.class);
			}
		}
		Function<@NonNull String, @NonNull Boolean> allow = origin -> true;
		Function<@NonNull String, @Nullable Boolean> unspecified = origin -> null;
		String origin = "https://example.test";
		Request request = Request.withPath(HttpMethod.GET, "/").build();
		Cors cors = Cors.fromOrigin(HttpMethod.GET, origin);
		Assertions.assertEquals(Boolean.TRUE, CorsAuthorizer.fromWhitelistedOrigins(
				Set.of(origin), allow).authorize(request, cors).orElseThrow()
				.getAccessControlAllowCredentials().orElseThrow());
		Assertions.assertTrue(CorsAuthorizer.fromWhitelistAuthorizer(allow, unspecified)
				.authorize(request, cors).orElseThrow().getAccessControlAllowCredentials().isEmpty());
		Assertions.assertTrue(CorsAuthorizer.fromWhitelistAuthorizer(unspecified, allow)
				.authorize(request, cors).isEmpty());
	}

	@Test
	void nullnessGuardDiscoversProtectedInheritedAndNestedTypeUses(
			@TempDir Path directory) throws Exception {
		Path source = directory.resolve("NullnessProbe.java");
		Files.writeString(source, """
				package com.soklet.contractprobe;
				import java.util.List;
				import java.util.Optional;
				import org.jspecify.annotations.NonNull;
				import org.jspecify.annotations.Nullable;
				public class NullnessProbe extends Hidden {
				  public String field;
				  protected String protectedField;
				  public @Nullable @NonNull String conflicting;
				  public @Nullable int primitiveAnnotation;
				  public NullnessProbe(String missing) {}
				  public String missingReturn() { return ""; }
				  protected void protectedParameter(String missing) {}
				  public @NonNull List<String> missingArgument() { return List.of(); }
				  public @NonNull List<? extends String> missingBound() { return List.of(); }
				  public @NonNull List<@NonNull ?> wildcardAnnotation() { return List.of(); }
				  public @NonNull String[] missingArrayDimension() { return new String[0]; }
				  public String @NonNull [] missingComponent() { return new String[0]; }
				  public @Nullable byte @NonNull [] primitiveComponentAnnotation() { return new byte[0]; }
				  public @NonNull Optional<@Nullable String> nullableOptionalPayload() { return Optional.empty(); }
				  public @Nullable String nullableValue() { return null; }
				  public @NonNull List<?> wildcard() { return List.of(); }
				  public <T> @Nullable T echo(@Nullable T value) { return value; }
				  public byte @NonNull [] primitiveComponents() { return new byte[0]; }
				  public int primitive() { return 0; }
				  public interface Nested { String missing(); }
				  protected interface ProtectedNested { String missing(); }
				}
				class Hidden { public String inherited() { return ""; } }
				""", StandardCharsets.UTF_8);
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		Assertions.assertNotNull(compiler, "A full JDK is required");
		DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
		try (StandardJavaFileManager files = compiler.getStandardFileManager(
				diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
			JavacTask task = (JavacTask) compiler.getTask(null, files,
					diagnostics, List.of("--release", "21", "-proc:none",
							"-classpath", System.getProperty("java.class.path")), null,
					files.getJavaFileObjectsFromPaths(List.of(source)));
			List<CompilationUnitTree> units = new ArrayList<>();
			task.parse().forEach(units::add);
			task.analyze();
			Assertions.assertTrue(diagnostics.getDiagnostics().stream()
					.noneMatch(value -> value.getKind() == Diagnostic.Kind.ERROR),
					() -> diagnostics.getDiagnostics().toString());
			Trees trees = Trees.instance(task);
			Elements elements = task.getElements();
			List<String> failures = new ArrayList<>();
			for (TypeElement type : exportedTypes(units, trees, elements))
				inspectReferenceNullness(type, trees, elements, failures);
			for (String member : List.of("#field field", "#protectedField field",
					"#conflicting field", "#primitiveAnnotation field",
					"#<init> parameter 0", "#missingReturn return",
					"#protectedParameter parameter 0", "#missingArgument return type argument 0",
					"#missingBound return type argument 0 wildcard upper bound",
					"#wildcardAnnotation return type argument 0",
					"#missingArrayDimension return", "#missingComponent return array component",
					"#primitiveComponentAnnotation return array component",
					"#nullableOptionalPayload return type argument 0", "#inherited return",
					"$Nested#missing return", "$ProtectedNested#missing return"))
				Assertions.assertTrue(failures.stream().anyMatch(value -> value.contains(member)),
						() -> member + " was not checked: " + failures);
			Assertions.assertEquals(17, failures.size(), failures::toString);
		}
	}

	@Test
	void ownedNullnessGuardFindsPrivatePackageRecordAndExplicitBoundContracts(
			@TempDir Path directory) throws Exception {
		Path implementation = directory.resolve("ImplementationProbe.java");
		Files.writeString(implementation, """
				package com.soklet.internal.contractprobe;
				import java.util.List;
				import org.jspecify.annotations.NonNull;
				import org.jspecify.annotations.Nullable;
				final class ImplementationProbe {
				  private String privateField;
				  Object packageField;
				  private Object helper(Object value) { return value; }
				  private record Frame(String text) {}
				  private static class Bounded<T extends Number> {}
				  private static class Parametric<T extends @Nullable Object> {
				    T echo(T value) { return value; }
				  }
				  private @NonNull List<?> wildcard() { return List.of(); }
				  private void primitive(int value) {}
				}
				""", StandardCharsets.UTF_8);
		Path excluded = directory.resolve("SpringProbe.java");
		Files.writeString(excluded, """
				package com.soklet.internal.spring;
				class SpringProbe { String unchangedVendorContract() { return ""; } }
				""", StandardCharsets.UTF_8);
		Path included = directory.resolve("SpringlikeProbe.java");
		Files.writeString(included, """
				package com.soklet.internal.springlike;
				class SpringlikeProbe { String ownedContract() { return ""; } }
				""", StandardCharsets.UTF_8);
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		Assertions.assertNotNull(compiler, "A full JDK is required");
		DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
		try (StandardJavaFileManager files = compiler.getStandardFileManager(
				diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
			JavacTask task = (JavacTask) compiler.getTask(null, files,
					diagnostics, List.of("--release", "21", "-proc:none",
							"-classpath", System.getProperty("java.class.path")), null,
					files.getJavaFileObjectsFromPaths(List.of(implementation, excluded, included)));
			List<CompilationUnitTree> units = new ArrayList<>();
			task.parse().forEach(units::add);
			task.analyze();
			Assertions.assertTrue(diagnostics.getDiagnostics().stream()
					.noneMatch(value -> value.getKind() == Diagnostic.Kind.ERROR),
					() -> diagnostics.getDiagnostics().toString());
			List<String> failures = new ArrayList<>();
			inspectOwnedDeclarations(units, Trees.instance(task), task.getElements(), failures);
			for (String member : List.of("#privateField field", "#packageField field",
					"#helper return", "#helper parameter 0", "$Frame#text field",
					"Bounded type parameter T bound 0", "SpringlikeProbe#ownedContract return"))
				Assertions.assertTrue(failures.stream().anyMatch(value -> value.contains(member)),
						() -> member + " was not checked: " + failures);
			Assertions.assertEquals(7, failures.size(), failures::toString);
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
		assertTypeArgument(Soklet.class.getMethod("awaitShutdownAsync")
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

		Method run = Soklet.class.getMethod("run",
				SokletConfig.class, Soklet.RunAction.class);
		AnnotatedType action = run.getAnnotatedParameterTypes()[1];
		assertExactNullness(action, NonNull.class);
		assertTypeArgument(action, 0, NonNull.class);

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
			if ((!packageName.equals("com.soklet") && !packageName.startsWith("com.soklet."))
					|| packageName.equals("com.soklet.internal")
					|| packageName.startsWith("com.soklet.internal."))
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
					&& isVisible(nestedType))
				appendExportedType(nestedType, result);
		}
	}

	private static boolean isVisible(Element element) {
		return element.getModifiers().contains(Modifier.PUBLIC)
				|| element.getModifiers().contains(Modifier.PROTECTED);
	}

	private static void inspectOwnedDeclarations(List<CompilationUnitTree> units,
			Trees trees, Elements elements, List<String> mismatches) {
		for (CompilationUnitTree unit : units) {
			String packageName = unit.getPackageName() == null ? ""
					: unit.getPackageName().toString();
			if ((!packageName.equals("com.soklet") && !packageName.startsWith("com.soklet."))
					|| packageName.equals("com.soklet.internal.spring")
					|| packageName.startsWith("com.soklet.internal.spring."))
				continue;
			new TreePathScanner<Void, Void>() {
				@Override
				public Void visitVariable(VariableTree declaration, Void unused) {
					Element field = trees.getElement(getCurrentPath());
					if (field != null && field.getKind() == ElementKind.FIELD)
						inspectNullness(sourceOwner(field, elements) + " field", field.asType(),
								true, false, isExportedMember(field), mismatches);
					return super.visitVariable(declaration, unused);
				}

				@Override
				public Void visitMethod(MethodTree declaration, Void unused) {
					Element member = trees.getElement(getCurrentPath());
					if (member instanceof ExecutableElement method
							&& trees.getSourcePositions().getEndPosition(unit, declaration) >= 0) {
						String owner = sourceOwner(method, elements);
						boolean exported = isExportedMember(method);
						for (int index = 0; index < method.getParameters().size(); ++index)
							inspectNullness(owner + " parameter " + index,
									method.getParameters().get(index).asType(), true, false,
									exported, mismatches);
						if (method.getReturnType().getKind() != TypeKind.VOID)
							inspectNullness(owner + " return", method.getReturnType(),
									true, false, exported, mismatches);
					}
					return super.visitMethod(declaration, unused);
				}

				@Override
				public Void visitTypeParameter(TypeParameterTree declaration, Void unused) {
					Element parameter = trees.getElement(getCurrentPath());
					String owner = parameter.getEnclosingElement()
							+ " type parameter " + declaration.getName();
					for (int index = 0; index < declaration.getBounds().size(); ++index) {
						Tree bound = declaration.getBounds().get(index);
						inspectNullness(owner + " bound " + index, trees.getTypeMirror(
								new TreePath(getCurrentPath(), bound)), true, false, false, mismatches);
					}
					return super.visitTypeParameter(declaration, unused);
				}
			}.scan(unit, null);
		}
	}

	private static String sourceOwner(Element member, Elements elements) {
		Element owner = member.getEnclosingElement();
		return (owner instanceof TypeElement type ? elements.getBinaryName(type) : owner)
				+ "#" + member.getSimpleName();
	}

	private static boolean isExportedMember(Element member) {
		if (!isVisible(member))
			return false;
		Element owner = member.getEnclosingElement();
		for (; owner instanceof TypeElement; owner = owner.getEnclosingElement())
			if (!isVisible(owner))
				return false;
		String packageName = owner == null ? "" : owner.toString();
		return !packageName.equals("com.soklet.internal")
				&& !packageName.startsWith("com.soklet.internal.");
	}

	private static void inspectReferenceNullness(TypeElement type, Trees trees,
			Elements elements, List<String> mismatches) {
		String binaryName = elements.getBinaryName(type).toString();
		for (Element member : elements.getAllMembers(type)) {
			if (!isVisible(member))
				continue;
			if (member.getKind() == ElementKind.FIELD
					&& trees.getPath(member) != null)
				inspectNullness(binaryName + "#" + member.getSimpleName() + " field",
						member.asType(), true, false, mismatches);
			if (!(member instanceof ExecutableElement method)
					|| !isSourceAuthoredMethod(method, trees))
				continue;
			String owner = binaryName + "#" + method.getSimpleName();
			for (int index = 0; index < method.getParameters().size(); ++index) {
				TypeMirror parameterType = method.getParameters().get(index).asType();
				inspectNullness(owner + " parameter " + index, parameterType,
						true, false, mismatches);
			}
			if (method.getReturnType().getKind() != TypeKind.VOID)
				inspectNullness(owner + " return", method.getReturnType(),
						true, false, mismatches);
		}
	}

	private static boolean isSourceAuthoredMethod(ExecutableElement method,
			Trees trees) {
		TreePath path = trees.getPath(method);
		return path != null && path.getLeaf() instanceof MethodTree;
	}

	private static void inspectNullness(String owner, TypeMirror type,
			boolean root, boolean requireNonNull, List<String> mismatches) {
		inspectNullness(owner, type, root, requireNonNull, true, mismatches);
	}

	private static void inspectNullness(String owner, TypeMirror type,
			boolean root, boolean requireNonNull, boolean enforceOptionalPayload,
			List<String> mismatches) {
		if (type.getKind().isPrimitive() || type.getKind() == TypeKind.WILDCARD) {
			if (type.getAnnotationMirrors().stream().anyMatch(annotation ->
					annotation.getAnnotationType().toString().equals(NonNull.class.getName())
							|| annotation.getAnnotationType().toString().equals(Nullable.class.getName())))
				mismatches.add(owner + " has nullness on a "
						+ (type.getKind().isPrimitive() ? "primitive" : "wildcard") + " type: " + type);
			if (type.getKind().isPrimitive())
				return;
		}
		boolean parametric = type.getKind() == TypeKind.TYPEVAR
				&& type.getAnnotationMirrors().stream().noneMatch(annotation ->
					annotation.getAnnotationType().toString().equals(NonNull.class.getName())
							|| annotation.getAnnotationType().toString().equals(Nullable.class.getName()));
		if (!parametric && (root || requiresNestedNullness(type))
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
						arguments.get(index), false, optional && enforceOptionalPayload,
						enforceOptionalPayload, mismatches);
		} else if (type instanceof ArrayType arrayType) {
			TypeMirror componentType = arrayType.getComponentType();
			inspectNullness(owner + " array component", componentType,
					false, false, enforceOptionalPayload, mismatches);
		} else if (type instanceof WildcardType wildcardType) {
			TypeMirror extendsBound = wildcardType.getExtendsBound();
			if (extendsBound != null)
				inspectNullness(owner + " wildcard upper bound",
						extendsBound, false, requireNonNull, enforceOptionalPayload, mismatches);
			TypeMirror superBound = wildcardType.getSuperBound();
			if (superBound != null)
				inspectNullness(owner + " wildcard lower bound", superBound,
						false, requireNonNull, enforceOptionalPayload, mismatches);
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
