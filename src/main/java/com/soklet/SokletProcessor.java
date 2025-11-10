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

import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.StandardLocation;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.lang.annotation.Annotation;
import java.lang.annotation.Repeatable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
@NotThreadSafe
public final class SokletProcessor extends AbstractProcessor {
	private Types types;
	private Elements elements;
	private Messager messager;
	private Filer filer;

	// Cached mirrors resolved in init()
	private TypeMirror handshakeResultType; // com.soklet.HandshakeResult
	private TypeElement pathParameterElement; // com.soklet.annotation.PathParameter

	@Override
	public synchronized void init(ProcessingEnvironment processingEnv) {
		super.init(processingEnv);
		this.types = processingEnv.getTypeUtils();
		this.elements = processingEnv.getElementUtils();
		this.messager = processingEnv.getMessager();
		this.filer = processingEnv.getFiler();

		TypeElement hr = elements.getTypeElement("com.soklet.HandshakeResult");
		this.handshakeResultType = (hr == null ? null : hr.asType());
		this.pathParameterElement = elements.getTypeElement("com.soklet.annotation.PathParameter");
	}

	@Override
	public Set<String> getSupportedAnnotationTypes() {
		Set<String> out = new LinkedHashSet<>();
		for (Class<? extends Annotation> c : HTTP_AND_SSE_ANNOTATIONS) {
			out.add(c.getCanonicalName());
			Class<? extends Annotation> container = findRepeatableContainer(c);
			if (container != null) out.add(container.getCanonicalName());
		}
		return out;
	}

	@Override
	public SourceVersion getSupportedSourceVersion() {return SourceVersion.latestSupported();}

	private final List<ResourceMethodDeclaration> collected = new ArrayList<>();
	private final Set<String> touchedTopLevelBinaries = new LinkedHashSet<>();

	@Override
	public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
		for (Element root : roundEnv.getRootElements()) {
			if (root instanceof TypeElement te) {
				String bin = elements.getBinaryName(te).toString();
				touchedTopLevelBinaries.add(bin);
			}
		}

		// SSE-specific return type check
		enforceSseReturnTypes(roundEnv);

		// Collect + validate
		collect(roundEnv, HttpMethod.GET, GET.class, false);
		collect(roundEnv, HttpMethod.POST, POST.class, false);
		collect(roundEnv, HttpMethod.PUT, PUT.class, false);
		collect(roundEnv, HttpMethod.PATCH, PATCH.class, false);
		collect(roundEnv, HttpMethod.DELETE, DELETE.class, false);
		collect(roundEnv, HttpMethod.HEAD, HEAD.class, false);
		collect(roundEnv, HttpMethod.OPTIONS, OPTIONS.class, false);
		collect(roundEnv, HttpMethod.GET, ServerSentEventSource.class, true); // SSE as GET + flag

		if (roundEnv.processingOver()) {
			mergeAndWriteIndex(collected, touchedTopLevelBinaries);
		}
		return false;
	}

	private static final List<Class<? extends Annotation>> HTTP_AND_SSE_ANNOTATIONS = List.of(
			GET.class, POST.class, PUT.class, PATCH.class, DELETE.class, HEAD.class, OPTIONS.class,
			ServerSentEventSource.class
	);

	/**
	 * Collects and validates each annotated method occurrence (repeatable-aware).
	 */
	private void collect(RoundEnvironment roundEnv,
											 HttpMethod httpMethod,
											 Class<? extends Annotation> baseAnnotation,
											 boolean serverSentEventSource) {

		Set<Element> candidates = new LinkedHashSet<>();
		TypeElement base = elements.getTypeElement(baseAnnotation.getCanonicalName());
		if (base != null) candidates.addAll(roundEnv.getElementsAnnotatedWith(base));
		Class<? extends Annotation> containerAnn = findRepeatableContainer(baseAnnotation);
		if (containerAnn != null) {
			TypeElement container = elements.getTypeElement(containerAnn.getCanonicalName());
			if (container != null) candidates.addAll(roundEnv.getElementsAnnotatedWith(container));
		}

		for (Element e : candidates) {
			if (e.getKind() != ElementKind.METHOD) {
				error(e, "Soklet: @%s can only be applied to methods.", baseAnnotation.getSimpleName());
				continue;
			}
			ExecutableElement method = (ExecutableElement) e;
			TypeElement owner = (TypeElement) method.getEnclosingElement();

			// -- Signature validations common to all HTTP annotations --
			// 1) no static
			if (method.getModifiers().contains(Modifier.STATIC)) {
				error(method, "Soklet: Resource Method must not be static");
				// keep validating path so we can show all issues in one compile, but we won't collect if any error
			}

			// Repeatable-aware: iterate each occurrence on the same method
			Annotation[] anns = method.getAnnotationsByType(cast(baseAnnotation));
			for (Annotation a : anns) {
				String rawPath = readAnnotationStringMember(a, "value");
				if (rawPath == null || rawPath.isBlank()) {
					error(method, "Soklet: @%s must have a non-empty path value", baseAnnotation.getSimpleName());
					continue;
				}

				// 2) path normalization + validation
				String path = normalizePath(rawPath);

				ValidationResult vr = validatePathTemplate(method, path);
				// If malformed, skip further param-name checks for this occurrence
				if (!vr.ok) {
					continue;
				}

				// 3) @PathParameter bindings
				ParamBindings pb = readPathParameterBindings(method);
				// a) placeholders must be bound
				for (String placeholder : vr.placeholders) {            // <-- already normalized
					if (!pb.paramNames.contains(placeholder)) {
						// use the original token if you want to echo "{cssPath*}" in the message
						String shown = vr.original.getOrDefault(placeholder, placeholder);
						error(method, "Resource Method path parameter {" + shown + "} not bound to a @PathParameter argument");
					}
				}

				// b) annotated params must exist in template
				for (String annotated : pb.paramNames) {                // annotated names are plain ("cssPath")
					if (!vr.placeholders.contains(annotated)) {
						error(method, "No placeholder {" + annotated + "} present in resource path declaration");
					}
				}

				// Only collect if no errors were reported on this method occurrence
				if (!pb.hadError && vr.ok && !method.getModifiers().contains(Modifier.STATIC)) {
					String className = elements.getBinaryName(owner).toString();
					String methodName = method.getSimpleName().toString();
					String[] paramTypes = method.getParameters().stream()
							.map(p -> jvmTypeName(p.asType()))
							.toArray(String[]::new);

					collected.add(new ResourceMethodDeclaration(
							httpMethod, path, className, methodName, paramTypes, serverSentEventSource
					));
				}
			}
		}
	}

	// --- Helpers for parameter annotations ------------------------------------

	private static final class ParamBindings {
		final Set<String> paramNames;
		final boolean hadError;

		ParamBindings(Set<String> names, boolean hadError) {
			this.paramNames = names;
			this.hadError = hadError;
		}
	}

	private ParamBindings readPathParameterBindings(ExecutableElement method) {
		boolean hadError = false;
		Set<String> names = new LinkedHashSet<>();
		if (pathParameterElement == null) return new ParamBindings(names, false);

		for (VariableElement p : method.getParameters()) {
			for (AnnotationMirror am : p.getAnnotationMirrors()) {
				if (isAnnotationType(am, pathParameterElement)) {
					// 1) try explicit annotation member
					String name = readAnnotationStringMember(am, "name");
					// 2) default to the parameter's source name if missing/blank
					if (name == null || name.isBlank()) {
						name = p.getSimpleName().toString();
					}
					if (name != null && !name.isBlank()) {
						names.add(name);
					}
				}
			}
		}
		return new ParamBindings(names, hadError);
	}

	private static boolean isAnnotationType(AnnotationMirror am, TypeElement type) {
		return am.getAnnotationType().asElement().equals(type);
	}

	// Overload: read member from AnnotationMirror (compile-safe, no reflection)
	private static String readAnnotationStringMember(AnnotationMirror am, String member) {
		for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> e : am.getElementValues().entrySet()) {
			if (e.getKey().getSimpleName().contentEquals(member)) {
				Object v = e.getValue().getValue();
				return (v == null) ? null : v.toString();
			}
		}
		return null;
	}

	// Existing reflective reader (used for repeatable occurrences)
	private static String readAnnotationStringMember(Annotation a, String memberName) {
		try {
			Object v = a.annotationType().getMethod(memberName).invoke(a);
			return (v == null) ? null : v.toString();
		} catch (ReflectiveOperationException ex) {
			return null;
		}
	}

	// --- Path parsing/validation ----------------------------------------------

	private static final class ValidationResult {
		final boolean ok;
		final Set<String> placeholders;       // normalized names (no trailing '*')
		final Map<String, String> original;   // normalized -> original token (e.g., "cssPath" -> "cssPath*")

		ValidationResult(boolean ok, Set<String> placeholders, Map<String, String> original) {
			this.ok = ok;
			this.placeholders = placeholders;
			this.original = original;
		}
	}

	/**
	 * Validates braces and duplicate placeholders (treating {name*} as a greedy/varargs
	 * placeholder whose logical name is "name"). Duplicate detection is done on the
	 * normalized name (without the trailing '*').
	 * <p>
	 * Emits diagnostics on the element if malformed or duplicate.
	 */
	private ValidationResult validatePathTemplate(Element reportOn, String path) {
		if (path == null || path.isEmpty()) {
			return new ValidationResult(false, Collections.emptySet(), Collections.emptyMap());
		}

		Set<String> names = new LinkedHashSet<>();
		Map<String, String> originalTokens = new LinkedHashMap<>();

		int i = 0;
		while (i < path.length()) {
			char c = path.charAt(i);
			if (c == '{') {
				int close = path.indexOf('}', i + 1);
				if (close < 0) {
					error(reportOn, "Soklet: Malformed resource path declaration (unbalanced braces)");
					return new ValidationResult(false, Collections.emptySet(), Collections.emptyMap());
				}
				String token = path.substring(i + 1, close);   // e.g., "id", "cssPath*"
				if (token.isEmpty()) {
					error(reportOn, "Soklet: Malformed resource path declaration (unbalanced braces)");
					return new ValidationResult(false, Collections.emptySet(), Collections.emptyMap());
				}

				String normalized = normalizePlaceholder(token); // strip trailing '*' if present
				if (normalized.isEmpty()) {
					error(reportOn, "Soklet: Malformed resource path declaration (unbalanced braces)");
					return new ValidationResult(false, Collections.emptySet(), Collections.emptyMap());
				}

				// Duplicate on normalized name
				if (!names.add(normalized)) {
					error(reportOn, "Soklet: Duplicate @PathParameter name: " + normalized);
				}
				// keep first-seen original token for any potential messaging
				originalTokens.putIfAbsent(normalized, token);

				i = close + 1;
			} else if (c == '}') {
				error(reportOn, "Soklet: Malformed resource path declaration (unbalanced braces)");
				return new ValidationResult(false, Collections.emptySet(), Collections.emptyMap());
			} else {
				i++;
			}
		}
		return new ValidationResult(true, names, originalTokens);
	}

	/**
	 * Accepts vararg placeholders like "cssPath*" and returns the logical name "cssPath".
	 */
	private static String normalizePlaceholder(String token) {
		if (token.endsWith("*")) return token.substring(0, token.length() - 1);
		return token;
	}

	// --- Existing utilities ----------------------------------------------------

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static Class<? extends Annotation> cast(Class<? extends Annotation> c) {return (Class) c;}

	private static String normalizePath(String p) {
		if (p == null || p.isEmpty()) return "/";
		if (p.charAt(0) != '/') return "/" + p;
		return p;
	}

	private static Class<? extends Annotation> findRepeatableContainer(Class<? extends Annotation> base) {
		Repeatable repeatable = base.getAnnotation(Repeatable.class);
		return (repeatable == null) ? null : repeatable.value();
	}

	private String jvmTypeName(TypeMirror t) {
		switch (t.getKind()) {
			case BOOLEAN:
				return "boolean";
			case BYTE:
				return "byte";
			case SHORT:
				return "short";
			case CHAR:
				return "char";
			case INT:
				return "int";
			case LONG:
				return "long";
			case FLOAT:
				return "float";
			case DOUBLE:
				return "double";
			case VOID:
				return "void";
			case ARRAY:
				return "[" + jvmTypeDescriptor(((javax.lang.model.type.ArrayType) t).getComponentType());
			case DECLARED:
			default:
				TypeMirror erasure = processingEnv.getTypeUtils().erasure(t);
				Element el = processingEnv.getTypeUtils().asElement(erasure);
				if (el instanceof TypeElement te) {
					return processingEnv.getElementUtils().getBinaryName(te).toString();
				}
				return erasure.toString();
		}
	}

	private String jvmTypeDescriptor(TypeMirror t) {
		switch (t.getKind()) {
			case BOOLEAN:
				return "Z";
			case BYTE:
				return "B";
			case SHORT:
				return "S";
			case CHAR:
				return "C";
			case INT:
				return "I";
			case LONG:
				return "J";
			case FLOAT:
				return "F";
			case DOUBLE:
				return "D";
			case ARRAY:
				return "[" + jvmTypeDescriptor(((javax.lang.model.type.ArrayType) t).getComponentType());
			case DECLARED:
			default:
				TypeMirror erasure = processingEnv.getTypeUtils().erasure(t);
				Element el = processingEnv.getTypeUtils().asElement(erasure);
				if (el instanceof TypeElement te) {
					String bin = processingEnv.getElementUtils().getBinaryName(te).toString();
					return "L" + bin + ";";
				}
				return "Ljava/lang/Object;";
		}
	}

	// ---- SSE return-type validation ------------------------------------------

	private void enforceSseReturnTypes(RoundEnvironment roundEnv) {
		if (handshakeResultType == null) return;
		TypeElement sseAnn = elements.getTypeElement(ServerSentEventSource.class.getCanonicalName());
		if (sseAnn == null) return;

		for (Element e : roundEnv.getElementsAnnotatedWith(sseAnn)) {
			if (e.getKind() != ElementKind.METHOD) {
				error(e, "@%s can only be applied to methods.", ServerSentEventSource.class.getSimpleName());
				continue;
			}
			ExecutableElement method = (ExecutableElement) e;
			TypeMirror returnType = method.getReturnType();
			boolean assignable = types.isAssignable(returnType, handshakeResultType);
			if (!assignable) {
				error(e,
						"Soklet: Resource Methods annotated with @%s must specify a return type of %s (found: %s).",
						ServerSentEventSource.class.getSimpleName(), "HandshakeResult", prettyType(returnType));
			}
		}
	}

	private static String prettyType(TypeMirror t) {return (t == null ? "null" : t.toString());}

	// ---- Index read/merge/write ----------------------------------------------

	static String RESOURCE_METHOD_LOOKUP_TABLE_PATH = "META-INF/soklet/resource-method-lookup-table";

	private void mergeAndWriteIndex(List<ResourceMethodDeclaration> newlyCollected,
																	Set<String> touchedTopLevelBinaries) {
		Map<String, ResourceMethodDeclaration> merged = readExistingIndex();

		if (!touchedTopLevelBinaries.isEmpty()) {
			merged.values().removeIf(r -> {
				String ownerBin = r.className();
				for (String top : touchedTopLevelBinaries) {
					if (ownerBin.equals(top) || ownerBin.startsWith(top + "$")) return true;
				}
				return false;
			});
		}

		merged.values().removeIf(r -> {
			String canonical = binaryToCanonical(r.className());
			TypeElement te = elements.getTypeElement(canonical);
			return te == null;
		});

		for (ResourceMethodDeclaration r : dedupeAndOrder(newlyCollected)) {
			merged.put(generateKey(r), r);
		}

		List<ResourceMethodDeclaration> toWrite = new ArrayList<>(merged.values());
		toWrite.sort(Comparator
				.comparing((ResourceMethodDeclaration r) -> r.httpMethod().name())
				.thenComparing(ResourceMethodDeclaration::path)
				.thenComparing(ResourceMethodDeclaration::className)
				.thenComparing(ResourceMethodDeclaration::methodName));

		writeRoutesIndexResource(toWrite);
	}

	private static String binaryToCanonical(String binary) {return binary.replace('$', '.');}

	private Map<String, ResourceMethodDeclaration> readExistingIndex() {
		Map<String, ResourceMethodDeclaration> out = new LinkedHashMap<>();
		BufferedReader reader = null;
		try {
			FileObject fo = filer.getResource(StandardLocation.CLASS_OUTPUT, "", RESOURCE_METHOD_LOOKUP_TABLE_PATH);
			reader = new BufferedReader(new InputStreamReader(fo.openInputStream(), StandardCharsets.UTF_8));
			String line;
			while ((line = reader.readLine()) != null) {
				line = line.trim();
				if (line.isEmpty()) continue;
				ResourceMethodDeclaration r = parseIndexLine(line);
				if (r != null) out.put(generateKey(r), r);
			}
		} catch (IOException ignored) {
		} finally {
			if (reader != null) try {
				reader.close();
			} catch (IOException ignored) {
			}
		}
		return out;
	}

	private ResourceMethodDeclaration parseIndexLine(String line) {
		try {
			String[] parts = line.split("\\|", -1);
			if (parts.length < 6) return null;

			HttpMethod httpMethod = HttpMethod.valueOf(parts[0]);
			Base64.Decoder dec = Base64.getDecoder();

			String path = new String(dec.decode(parts[1]), StandardCharsets.UTF_8);
			String className = new String(dec.decode(parts[2]), StandardCharsets.UTF_8);
			String methodName = new String(dec.decode(parts[3]), StandardCharsets.UTF_8);
			String paramsJoined = new String(dec.decode(parts[4]), StandardCharsets.UTF_8);
			boolean sse = Boolean.parseBoolean(parts[5]);

			String[] paramTypes;
			if (paramsJoined.isEmpty()) {
				paramTypes = new String[0];
			} else {
				List<String> tmp = Arrays.stream(paramsJoined.split(";"))
						.filter(s -> !s.isEmpty())
						.collect(Collectors.toList());
				paramTypes = tmp.toArray(String[]::new);
			}
			return new ResourceMethodDeclaration(httpMethod, path, className, methodName, paramTypes, sse);
		} catch (Throwable t) {
			return null;
		}
	}

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
			throw new UncheckedIOException("Failed to write " + RESOURCE_METHOD_LOOKUP_TABLE_PATH, e);
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

	private static String generateKey(ResourceMethodDeclaration r) {
		return r.httpMethod().name() + "|" + r.path() + "|" + r.className() + "|" +
				r.methodName() + "|" + String.join(";", r.parameterTypes()) + "|" +
				r.serverSentEventSource();
	}

	private static List<ResourceMethodDeclaration> dedupeAndOrder(List<ResourceMethodDeclaration> in) {
		Map<String, ResourceMethodDeclaration> byKey = new LinkedHashMap<>();
		for (ResourceMethodDeclaration r : in) byKey.putIfAbsent(generateKey(r), r);
		List<ResourceMethodDeclaration> out = new ArrayList<>(byKey.values());
		out.sort(Comparator
				.comparing((ResourceMethodDeclaration r) -> r.httpMethod().name())
				.thenComparing(ResourceMethodDeclaration::path)
				.thenComparing(ResourceMethodDeclaration::className)
				.thenComparing(ResourceMethodDeclaration::methodName));
		return out;
	}
}