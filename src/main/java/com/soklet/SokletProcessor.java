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

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.lang.annotation.Annotation;
import java.lang.annotation.Repeatable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
public final class SokletProcessor extends AbstractProcessor {
	private Types types;
	private Elements elements;
	private Messager messager;
	private Filer filer;

	// Cached for SSE validation (resolved in init)
	private TypeMirror handshakeResultType; // com.soklet.HandshakeResult

	@Override
	public synchronized void init(ProcessingEnvironment processingEnv) {
		super.init(processingEnv);
		this.types = processingEnv.getTypeUtils();
		this.elements = processingEnv.getElementUtils();
		this.messager = processingEnv.getMessager();
		this.filer = processingEnv.getFiler();

		TypeElement hr = elements.getTypeElement("com.soklet.HandshakeResult");
		this.handshakeResultType = (hr == null ? null : hr.asType());
	}

	/**
	 * Dynamically declare supported annotation types: both each base repeatable annotation
	 * and (if present) its container discovered via @Repeatable.
	 */
	@Override
	public Set<String> getSupportedAnnotationTypes() {
		Set<String> out = new LinkedHashSet<>();
		for (Class<? extends Annotation> c : HTTP_AND_SSE_ANNOTATIONS) {
			out.add(c.getCanonicalName());
			Class<? extends Annotation> container = findRepeatableContainer(c);
			if (container != null) {
				out.add(container.getCanonicalName());
			}
		}
		return out;
	}

	/**
	 * Avoid warnings like:
	 * "Supported source version 'RELEASE_17' from annotation processor ... less than -source '25'"
	 */
	@Override
	public SourceVersion getSupportedSourceVersion() {
		return SourceVersion.latestSupported();
	}

	// ---- Processing -----------------------------------------------------------

	private final List<ResourceMethodDeclaration> collected = new ArrayList<>();

	@Override
	public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
		// 1) Validate SSE method return types (if we can resolve HandshakeResult)
		enforceSseReturnTypes(roundEnv);

		// 2) Collect routes for all HTTP verbs (repeatable-aware)
		collect(roundEnv, HttpMethod.GET, GET.class, false);
		collect(roundEnv, HttpMethod.POST, POST.class, false);
		collect(roundEnv, HttpMethod.PUT, PUT.class, false);
		collect(roundEnv, HttpMethod.PATCH, PATCH.class, false);
		collect(roundEnv, HttpMethod.DELETE, DELETE.class, false);
		collect(roundEnv, HttpMethod.HEAD, HEAD.class, false);
		collect(roundEnv, HttpMethod.OPTIONS, OPTIONS.class, false);
		// Treat SSE as GET with the SSE flag
		collect(roundEnv, HttpMethod.GET, ServerSentEventSource.class, true);

		// 3) On the final round, write the resource index once
		if (roundEnv.processingOver() && !collected.isEmpty()) {
			List<ResourceMethodDeclaration> routes = dedupeAndOrder(collected);
			writeRoutesIndexResource(routes);
		}

		// Return false so other processors can still run on these annotations if they like
		return false;
	}

	// ---- Collection helpers ---------------------------------------------------

	private static final List<Class<? extends Annotation>> HTTP_AND_SSE_ANNOTATIONS = List.of(
			GET.class, POST.class, PUT.class, PATCH.class, DELETE.class, HEAD.class, OPTIONS.class,
			ServerSentEventSource.class
	);

	/**
	 * Collects all occurrences of a repeatable annotation on methods, including through its container.
	 */
	private void collect(RoundEnvironment roundEnv,
											 HttpMethod httpMethod,
											 Class<? extends Annotation> baseAnnotation,
											 boolean serverSentEventSource) {

		// Gather candidates annotated with either the base or its container
		Set<Element> candidates = new LinkedHashSet<>();

		TypeElement base = elements.getTypeElement(baseAnnotation.getCanonicalName());
		if (base != null) {
			candidates.addAll(roundEnv.getElementsAnnotatedWith(base));
		}

		Class<? extends Annotation> containerAnn = findRepeatableContainer(baseAnnotation);
		if (containerAnn != null) {
			TypeElement container = elements.getTypeElement(containerAnn.getCanonicalName());
			if (container != null) {
				candidates.addAll(roundEnv.getElementsAnnotatedWith(container));
			}
		}

		for (Element e : candidates) {
			if (e.getKind() != ElementKind.METHOD) {
				error(e, "@%s can only be applied to methods.", baseAnnotation.getSimpleName());
				continue;
			}
			ExecutableElement method = (ExecutableElement) e;
			TypeElement owner = (TypeElement) method.getEnclosingElement();

			// Repeatable-aware read: returns ALL occurrences on the element
			Annotation[] anns = method.getAnnotationsByType(cast(baseAnnotation));
			for (Annotation a : anns) {
				String rawPath = readAnnotationStringMember(a, "value");
				if (rawPath == null || rawPath.isBlank()) {
					error(e, "@%s must have a non-empty path value", baseAnnotation.getSimpleName());
					continue;
				}

				String path = normalizePath(rawPath);
				String className = owner.getQualifiedName().toString();
				String methodName = method.getSimpleName().toString();

				String[] paramTypes = method.getParameters().stream()
						.map(p -> types.erasure(p.asType()).toString())
						.toArray(String[]::new);

				collected.add(new ResourceMethodDeclaration(
						httpMethod, path, className, methodName, paramTypes, serverSentEventSource
				));
			}
		}
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static Class<? extends Annotation> cast(Class<? extends Annotation> c) {
		return (Class) c;
	}

	private static String normalizePath(String p) {
		if (p == null || p.isEmpty()) return "/";
		if (p.charAt(0) != '/') return "/" + p;
		return p;
	}

	private static Class<? extends Annotation> findRepeatableContainer(Class<? extends Annotation> base) {
		Repeatable repeatable = base.getAnnotation(Repeatable.class);
		return (repeatable == null) ? null : repeatable.value();
	}

	private static String readAnnotationStringMember(Annotation a, String memberName) {
		try {
			Object v = a.annotationType().getMethod(memberName).invoke(a);
			return (v == null) ? null : v.toString();
		} catch (ReflectiveOperationException ex) {
			return null;
		}
	}

	private static List<ResourceMethodDeclaration> dedupeAndOrder(List<ResourceMethodDeclaration> in) {
		// De-dupe by (method, path, class, methodName, params, sse)
		Map<String, ResourceMethodDeclaration> byKey = new LinkedHashMap<>();
		for (ResourceMethodDeclaration r : in) {
			String key = r.httpMethod().name() + "|" + r.path() + "|" + r.className() + "|" +
					r.methodName() + "|" + String.join(";", r.parameterTypes()) + "|" +
					r.serverSentEventSource();
			byKey.putIfAbsent(key, r);
		}
		List<ResourceMethodDeclaration> out = new ArrayList<>(byKey.values());
		out.sort(Comparator
				.comparing((ResourceMethodDeclaration r) -> r.httpMethod().name())
				.thenComparing(ResourceMethodDeclaration::path)
				.thenComparing(ResourceMethodDeclaration::className)
				.thenComparing(ResourceMethodDeclaration::methodName));
		return out;
	}

	// ---- SSE validation -------------------------------------------------------

	private void enforceSseReturnTypes(RoundEnvironment roundEnv) {
		if (handshakeResultType == null) {
			// HandshakeResult not on the AP classpath: skip validation quietly (keeps processor usable in partial builds)
			return;
		}
		TypeElement sseAnn = elements.getTypeElement(ServerSentEventSource.class.getCanonicalName());
		if (sseAnn == null) return;

		for (Element e : roundEnv.getElementsAnnotatedWith(sseAnn)) {
			if (e.getKind() != ElementKind.METHOD) {
				error(e, "@%s can only be applied to methods.", ServerSentEventSource.class.getSimpleName());
				continue;
			}
			ExecutableElement method = (ExecutableElement) e;
			TypeMirror returnType = method.getReturnType();

			// Return type must be HandshakeResult or a subtype thereof.
			boolean assignable = types.isAssignable(returnType, handshakeResultType);
			if (!assignable) {
				error(e,
						"Soklet Resource Methods annotated with @%s must specify a return type of %s (found: %s).",
						ServerSentEventSource.class.getSimpleName(),
						"HandshakeResult",
						prettyType(returnType));
			}
		}
	}

	private static String prettyType(TypeMirror t) {
		return (t == null ? "null" : t.toString());
	}

	// ---- Resource emission ----------------------------------------------------

	static String RESOURCE_METHOD_LOOKUP_TABLE_PATH = "META-INF/soklet/resource-method-lookup-table";

	/**
	 * Emit a single resource per module. Each line has:
	 * METHOD|b64(path)|b64(class)|b64(method)|b64(param1;param2;...)|true|false
	 */
	private void writeRoutesIndexResource(List<ResourceMethodDeclaration> routes) {
		try {
			FileObject fo = filer.createResource(StandardLocation.CLASS_OUTPUT, "", RESOURCE_METHOD_LOOKUP_TABLE_PATH);
			try (Writer w = fo.openWriter()) {
				Base64.Encoder b64 = Base64.getEncoder();
				for (ResourceMethodDeclaration r : routes) {
					String params = String.join(";", r.parameterTypes());
					String line = String.join("|",
							r.httpMethod().name(),
							b64encode(b64, r.path()),
							b64encode(b64, r.className()),
							b64encode(b64, r.methodName()),
							b64encode(b64, params),
							Boolean.toString(r.serverSentEventSource())
					);
					w.write(line);
					w.write('\n');
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to write META-INF/soklet/routes.index", e);
		}
	}

	private static String b64encode(Base64.Encoder enc, String s) {
		byte[] bytes = (s == null ? new byte[0] : s.getBytes(StandardCharsets.UTF_8));
		return enc.encodeToString(bytes);
	}

	// ---- Messaging ------------------------------------------------------------

	private void error(Element e, String fmt, Object... args) {
		messager.printMessage(Diagnostic.Kind.ERROR, String.format(fmt, args), e);
	}
}