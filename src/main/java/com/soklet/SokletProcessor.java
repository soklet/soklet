/*
 * Copyright 2022-2025 Revetware LLC.
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

import com.soklet.annotation.DELETE;
import com.soklet.annotation.GET;
import com.soklet.annotation.HEAD;
import com.soklet.annotation.OPTIONS;
import com.soklet.annotation.PATCH;
import com.soklet.annotation.POST;
import com.soklet.annotation.PUT;
import com.soklet.annotation.ServerSentEventSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * Soklet's standard Annotation Processor which is used to generate lookup tables of <em>Resource Method</em> definitions at compile time as well as prevent usage errors that are detectable by static analysis.
 * <p>
 * This Annotation Processor ensures <em>Resource Methods</em> annotated with {@link ServerSentEventSource} are declared as returning an instance of {@link HandshakeResult}.
 * <p>
 * Your build system should ensure this Annotation Processor is available at compile time. Follow the instructions below to make your application conformant:
 * <p>
 * Using {@code javac} directly:
 * <pre>javac -parameters -processor com.soklet.SokletProcessor ...[rest of javac command elided]</pre>
 * Using <a href="https://maven.apache.org" target="_blank">Maven</a>:
 * <pre>{@code <plugin>
 *     <groupId>org.apache.maven.plugins</groupId>
 *     <artifactId>maven-compiler-plugin</artifactId>
 *     <version>...</version>
 *     <configuration>
 *         <release>...</release>
 *         <compilerArgs>
 *             <!-- Rest of args elided -->
 *             <arg>-parameters</arg>
 *             <arg>-processor</arg>
 *             <arg>com.soklet.SokletProcessor</arg>
 *         </compilerArgs>
 *     </configuration>
 * </plugin>}</pre>
 * Using <a href="https://gradle.org" target="_blank">Gradle</a>:
 * <pre>{@code def sokletVersion = "2.0.0" // (use your actual version)
 *
 * dependencies {
 *   // Soklet used by your code at compile/run time
 *   implementation "com.soklet:soklet:${sokletVersion}"
 *
 *   // Same artifact also provides the annotation processor
 *   annotationProcessor "com.soklet:soklet:${sokletVersion}"
 *
 *   // If tests also need processing (optional)
 *   testAnnotationProcessor "com.soklet:soklet:${sokletVersion}"
 * }}</pre>
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@SupportedSourceVersion(SourceVersion.RELEASE_17)
@SupportedAnnotationTypes({
		"com.soklet.annotation.ServerSentEventSource",
		"com.soklet.annotation.GET",
		"com.soklet.annotation.POST",
		"com.soklet.annotation.PUT",
		"com.soklet.annotation.PATCH",
		"com.soklet.annotation.DELETE",
		"com.soklet.annotation.HEAD",
		"com.soklet.annotation.OPTIONS"
})
public final class SokletProcessor extends AbstractProcessor {
	// For Resource Method indexing
	@Nonnull
	private final List<ResourceMethodDeclaration> resourceMethodDeclarations = new ArrayList<>();
	@Nonnull
	private Boolean warnedMissingHandshakeResult = false;

	// The below fields are initialized in `init`, not ctor
	@Nonnull
	private Types types;
	@Nonnull
	private Elements elements;
	@Nonnull
	private Messager messager;
	@Nonnull
	private Filer filer;
	// For SSE return-type validation
	@Nullable
	private TypeMirror handshakeResultType; // com.soklet.HandshakeResult

	@Override
	public synchronized void init(@Nonnull ProcessingEnvironment processingEnvironment) {
		requireNonNull(processingEnvironment);

		super.init(processingEnvironment);

		this.types = processingEnvironment.getTypeUtils();
		this.elements = processingEnvironment.getElementUtils();
		this.messager = processingEnvironment.getMessager();
		this.filer = processingEnvironment.getFiler();

		TypeElement handshakeResultTypeElement = elements.getTypeElement("com.soklet.HandshakeResult");

		if (handshakeResultTypeElement != null) {
			this.handshakeResultType = handshakeResultTypeElement.asType();
			this.warnedMissingHandshakeResult = false;
		} else {
			this.handshakeResultType = null;
			this.warnedMissingHandshakeResult = true;
			// If the type isn't on the annotation‐processing path, we can still proceed
			// but will skip the check (and warn once).
			this.messager.printMessage(Diagnostic.Kind.WARNING,
					format("%s: %s not found on processor classpath; SSE return-type validation will be skipped.",
							getClass().getSimpleName(), HandshakeResult.class.getName()));
		}
	}

	@Override
	public boolean process(@Nonnull Set<? extends TypeElement> annotations,
												 @Nonnull RoundEnvironment roundEnvironment) {
		// 1) Enforce @ServerSentEventSource return type
		enforceSseReturnTypes(roundEnvironment);

		// 2) Collect method-level HTTP routes
		collectResourceMethodDeclarations(roundEnvironment, HttpMethod.GET, GET.class, "com.soklet.annotation.GET");
		collectResourceMethodDeclarations(roundEnvironment, HttpMethod.POST, POST.class, "com.soklet.annotation.POST");
		collectResourceMethodDeclarations(roundEnvironment, HttpMethod.PUT, PUT.class, "com.soklet.annotation.PUT");
		collectResourceMethodDeclarations(roundEnvironment, HttpMethod.PATCH, PATCH.class, "com.soklet.annotation.PATCH");
		collectResourceMethodDeclarations(roundEnvironment, HttpMethod.DELETE, DELETE.class, "com.soklet.annotation.DELETE");
		collectResourceMethodDeclarations(roundEnvironment, HttpMethod.HEAD, HEAD.class, "com.soklet.annotation.HEAD");
		collectResourceMethodDeclarations(roundEnvironment, HttpMethod.OPTIONS, OPTIONS.class, "com.soklet.annotation.OPTIONS");
		collectResourceMethodDeclarations(roundEnvironment, HttpMethod.GET, ServerSentEventSource.class, "com.soklet.annotation.ServerSentEventSource");

		// 3) Generate on last round (aggregating processor)
		if (roundEnvironment.processingOver() && !resourceMethodDeclarations.isEmpty()) {
			// sort for deterministic bytecode
			resourceMethodDeclarations.sort(Comparator
					.comparing((ResourceMethodDeclaration r) -> r.httpMethod())
					.thenComparing(r -> r.path())
					.thenComparing(r -> r.className())
					.thenComparing(r -> r.methodName()));

			writeIndexClassAndList();
		}

		// Let others process too, if needed
		return false;
	}

	// Resource Method Declaration caching

	private void collectResourceMethodDeclarations(RoundEnvironment round,
																								 HttpMethod httpMethod,
																								 Class<?> annClass,
																								 String annFqcn) {
		boolean serverSentEventSource = annClass == ServerSentEventSource.class;

		TypeElement ann = elements.getTypeElement(annFqcn);
		if (ann == null) return;

		for (Element e : round.getElementsAnnotatedWith(ann)) {
			if (e.getKind() != ElementKind.METHOD) {
				messager.printMessage(Diagnostic.Kind.ERROR,
						format("@%s can only be applied to methods.", annClass.getSimpleName()), e);
				continue;
			}

			ExecutableElement m = (ExecutableElement) e;
			TypeElement owner = (TypeElement) m.getEnclosingElement();

			String path = readSingleStringValue(m, ann);
			if (path == null || path.isBlank()) {
				messager.printMessage(Diagnostic.Kind.ERROR,
						format("@%s must have a non-empty path value", annClass.getSimpleName()), e);
				continue;
			}

			// parameter type erasures, in declaration order
			String[] paramTypes = m.getParameters().stream()
					.map(p -> types.erasure(p.asType()).toString())
					.toArray(String[]::new);

			this.resourceMethodDeclarations.add(new ResourceMethodDeclaration(
					httpMethod,
					normalizePath(path),
					owner.getQualifiedName().toString(),
					m.getSimpleName().toString(),
					paramTypes,
					serverSentEventSource
			));
		}
	}

	private String normalizePath(String p) {
		if (p == null || p.isEmpty())
			return "/";

		// Make deterministic but don't alter semantics
		if (!p.startsWith("/"))
			p = "/" + p;

		// No trailing slash unless root
		if (p.length() > 1 && p.endsWith("/"))
			p = p.substring(0, p.length() - 1);

		return p;
	}

	private @Nullable String readSingleStringValue(Element e, TypeElement annType) {
		for (AnnotationMirror am : e.getAnnotationMirrors()) {
			if (!Objects.equals(((TypeElement) am.getAnnotationType().asElement()).getQualifiedName(),
					annType.getQualifiedName()))
				continue;

			Map<? extends ExecutableElement, ? extends AnnotationValue> values = am.getElementValues();
			// support @GET("/x") where member is "value"
			for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> ent : values.entrySet()) {
				if (ent.getKey().getSimpleName().contentEquals("value"))
					return ent.getValue().getValue().toString();
			}

			// if no "value" found but annotation has default, ElementValues will be empty; try defaults
			for (ExecutableElement member : annType.getEnclosedElements().stream()
					.filter(el -> el.getKind() == ElementKind.METHOD)
					.map(ExecutableElement.class::cast)
					.toList()) {
				if (member.getSimpleName().contentEquals("value") && member.getDefaultValue() != null)
					return member.getDefaultValue().getValue().toString();
			}
		}
		return null;
	}

	private void writeIndexClassAndList() {
		String pkg = "com.soklet";
		String cls = "SokletRouteIndex"; // stable name to avoid rebuild churn

		try {
			// Generate class
			JavaFileObject jfo = filer.createSourceFile(pkg + "." + cls);
			try (Writer w = jfo.openWriter()) {
				w.write("package " + pkg + ";\n");
				w.write("import com.soklet.ResourceMethodDeclaration;\n");
				w.write("import com.soklet.HttpMethod;\n");
				w.write("import java.util.List;\n");
				w.write("public final class " + cls + " {\n");
				w.write("  private " + cls + "() {}\n");
				w.write("  public static List<ResourceMethodDeclaration> getResourceMethodDeclarations() {\n");
				w.write("    java.util.List<ResourceMethodDeclaration> list = new java.util.ArrayList<>(" + resourceMethodDeclarations.size() + 1 + ");\n");
				for (int i = 0; i < resourceMethodDeclarations.size(); i++) {
					ResourceMethodDeclaration r = resourceMethodDeclarations.get(i);
					w.write("      list.add(new ResourceMethodDeclaration(HttpMethod." + r.httpMethod() + ", ");
					w.write(quote(r.path()) + ", ");
					w.write(quote(r.className()) + ", ");
					w.write(quote(r.methodName()) + ", ");
					w.write("new String[]{");
					for (int j = 0; j < r.parameterTypes().length; j++) {
						if (j > 0) w.write(",");
						w.write(quote(r.parameterTypes()[j]));
					}
					w.write("}, ");
					w.write(r.serverSentEventSource() + "));");
					w.write("\n");
				}
				w.write("    return list;\n");
				w.write("  }\n");
				w.write("}\n");
			}

			// Generate list resource to allow multi-module aggregation later if you want
			// (Even if only one class is generated, keeping this file is harmless and future-proof.)
			var resource = filer.createResource(
					StandardLocation.CLASS_OUTPUT, pkg, "soklet-route-index.list");

			try (Writer w = resource.openWriter()) {
				w.write(pkg + "." + cls + "\n");
			}
		} catch (Exception ex) {
			messager.printMessage(Diagnostic.Kind.ERROR, "Failed to store Soklet Resource Method index: " + ex);
			throw new RuntimeException(ex);
		}
	}

	private static String quote(String s) {
		return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
	}

	// SSE enforcement

	private void enforceSseReturnTypes(@Nonnull RoundEnvironment roundEnvironment) {
		if (handshakeResultType == null)
			return; // nothing to validate

		for (Element element : roundEnvironment.getElementsAnnotatedWith(ServerSentEventSource.class)) {
			if (element.getKind() != ElementKind.METHOD) {
				messager.printMessage(Diagnostic.Kind.ERROR, format("@%s can only be applied to methods.",
						ServerSentEventSource.class.getSimpleName()), element);
				continue;
			}

			ExecutableElement method = (ExecutableElement) element;
			TypeMirror returnType = method.getReturnType();

			// Must be: HandshakeResult or any subclass thereof (i.e., types.isAssignable(sub, super))
			boolean ok = isReturnTypeHandshakeResultOrSubtype(returnType);

			if (!ok) {
				messager.printMessage(
						Diagnostic.Kind.ERROR,
						format("Soklet Resource Methods annotated with @%s must specify a return type of %s (found: %s). " +
										"See documentation at https://www.soklet.com/docs/server-sent-events",
								ServerSentEventSource.class.getSimpleName(), HandshakeResult.class.getSimpleName(), prettyType(returnType)),
						element
				);
			}
		}
	}

	private boolean isReturnTypeHandshakeResultOrSubtype(@Nonnull TypeMirror returnType) {
		// Disallow void/primitive outright
		if (returnType.getKind().isPrimitive() || returnType.getKind() == TypeKind.VOID)
			return false;

		// Allow exact type or subclass
		// (sub -> super) assignable must be true
		return types.isAssignable(returnType, handshakeResultType);
	}

	private String prettyType(@Nullable TypeMirror typeMirror) {
		// Produces user-friendly names (handles e.g. generics if they appear)
		return typeMirror == null ? "null" : types.erasure(typeMirror).toString();
	}
}
