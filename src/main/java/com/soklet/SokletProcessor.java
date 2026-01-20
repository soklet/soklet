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
import javax.annotation.processing.FilerException;
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
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
 * <pre>{@code def sokletVersion = "2.0.2" // (use your actual version)
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
 * <p><strong>Incremental/IDE ("IntelliJ-safe") behavior</strong>
 * <ul>
 *   <li>Never rebuilds the global index from only the currently-compiled sources. It always merges with the prior index.</li>
 *   <li>Only removes stale entries for top-level types compiled in the current compiler invocation (touched types).</li>
 *   <li>Skips writing the index entirely if compilation errors are present, preventing clobbering a good index.</li>
 *   <li>Writes with originating elements (best-effort) so incremental build tools can track dependencies.</li>
 * </ul>
 *
 * <p><strong>Processor options</strong>
 * <ul>
 *   <li><code>-Asoklet.cacheMode=none|sidecar|persistent</code> (default: <code>sidecar</code>)</li>
 *   <li><code>-Asoklet.cacheDir=/path</code> (used only when cacheMode=persistent; required to enable persistent)</li>
 *   <li><code>-Asoklet.pruneDeleted=true|false</code> (default: false; generally not IDE-safe)</li>
 *   <li><code>-Asoklet.debug=true|false</code> (default: false)</li>
 * </ul>
 *
 * <p><strong>Important</strong>: This processor will never create a project-root <code>.soklet</code> directory by default.
 * Persistent caching is only enabled when <code>cacheMode=persistent</code> <em>and</em> <code>soklet.cacheDir</code> is set.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@NotThreadSafe
public final class SokletProcessor extends AbstractProcessor {
	// ---- Options ------------------------------------------------------------

	private static final String PROCESSOR_OPTION_CACHE_MODE = "soklet.cacheMode";
	private static final String PROCESSOR_OPTION_CACHE_DIR = "soklet.cacheDir";
	private static final String PROCESSOR_OPTION_PRUNE_DELETED = "soklet.pruneDeleted";
	private static final String PROCESSOR_OPTION_DEBUG = "soklet.debug";

	private static final String PERSISTENT_CACHE_INDEX_DIR = "resource-methods";

	// ---- Index paths ---------------------------------------------------------

	static final String RESOURCE_METHOD_LOOKUP_TABLE_PATH = "META-INF/soklet/resource-method-lookup-table";
	private static final String OUTPUT_ROOT_MARKER_PATH = "META-INF/soklet/.soklet-output-root";

	private static final String SIDE_CAR_DIR_NAME = "soklet";
	private static final String SIDE_CAR_INDEX_FILENAME = "resource-method-lookup-table";

	// ---- JSR-269 services ----------------------------------------------------

	private Types types;
	private Elements elements;
	private Messager messager;
	private Filer filer;

	private boolean debugEnabled;
	private boolean pruneDeletedEnabled;
	private CacheMode cacheMode;

	// Cached mirrors resolved in init()
	private TypeMirror handshakeResultType;      // com.soklet.HandshakeResult
	private TypeElement pathParameterElement;    // com.soklet.annotation.PathParameter

	// Collected during this compilation invocation
	private final List<ResourceMethodDeclaration> collected = new ArrayList<>();
	private final Set<String> touchedTopLevelBinaries = new LinkedHashSet<>();

	// ---- Supported annotations ----------------------------------------------

	private static final List<Class<? extends Annotation>> HTTP_AND_SSE_ANNOTATIONS = List.of(
			GET.class, POST.class, PUT.class, PATCH.class, DELETE.class, HEAD.class, OPTIONS.class,
			ServerSentEventSource.class
	);

	// ---- Cache modes ---------------------------------------------------------

	private enum CacheMode {
		NONE,       // Only CLASS_OUTPUT index. No sidecar/persistent. Lowest clutter, lowest resiliency.
		SIDECAR,    // CLASS_OUTPUT + sidecar (under the class output parent directory). Default.
		PERSISTENT  // CLASS_OUTPUT + sidecar + persistent (under soklet.cacheDir). Requires soklet.cacheDir.
	}

	@Override
	public synchronized void init(ProcessingEnvironment processingEnv) {
		super.init(processingEnv);
		this.types = processingEnv.getTypeUtils();
		this.elements = processingEnv.getElementUtils();
		this.messager = processingEnv.getMessager();
		this.filer = processingEnv.getFiler();

		this.debugEnabled = parseBooleanishOption(processingEnv.getOptions().get(PROCESSOR_OPTION_DEBUG));
		this.pruneDeletedEnabled = parseBooleanishOption(processingEnv.getOptions().get(PROCESSOR_OPTION_PRUNE_DELETED));
		this.cacheMode = parseCacheMode(processingEnv.getOptions().get(PROCESSOR_OPTION_CACHE_MODE));

		TypeElement hr = elements.getTypeElement("com.soklet.HandshakeResult");
		this.handshakeResultType = (hr == null ? null : hr.asType());
		this.pathParameterElement = elements.getTypeElement("com.soklet.annotation.PathParameter");

		// If persistent mode was requested but cacheDir isn't configured, downgrade to SIDECAR.
		if (this.cacheMode == CacheMode.PERSISTENT && persistentCacheRoot() == null) {
			debug("SokletProcessor: cacheMode=persistent requested but %s not set/invalid; falling back to sidecar.",
					PROCESSOR_OPTION_CACHE_DIR);
			this.cacheMode = CacheMode.SIDECAR;
		}
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
	public SourceVersion getSupportedSourceVersion() {
		return SourceVersion.latestSupported();
	}

	@Override
	public Set<String> getSupportedOptions() {
		return new LinkedHashSet<>(List.of(
				PROCESSOR_OPTION_CACHE_MODE,
				PROCESSOR_OPTION_CACHE_DIR,
				PROCESSOR_OPTION_PRUNE_DELETED,
				PROCESSOR_OPTION_DEBUG
		));
	}

	@Override
	public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
		// Track top-level types being compiled in this invocation.
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
			// Critical: don't overwrite a good index with a partial/failed compile.
			if (roundEnv.errorRaised()) {
				debug("SokletProcessor: compilation has errors; skipping index write to avoid clobbering.");
				return false;
			}
			mergeAndWriteIndex(collected, touchedTopLevelBinaries);
		}

		return false;
	}

	/**
	 * Collects and validates each annotated method occurrence (repeatable-aware, without reflection).
	 */
	private void collect(RoundEnvironment roundEnv,
											 HttpMethod httpMethod,
											 Class<? extends Annotation> baseAnnotation,
											 boolean serverSentEventSource) {

		TypeElement base = elements.getTypeElement(baseAnnotation.getCanonicalName());
		Class<? extends Annotation> containerClass = findRepeatableContainer(baseAnnotation);
		TypeElement container = containerClass == null ? null : elements.getTypeElement(containerClass.getCanonicalName());

		Set<Element> candidates = new LinkedHashSet<>();
		if (base != null) candidates.addAll(roundEnv.getElementsAnnotatedWith(base));
		if (container != null) candidates.addAll(roundEnv.getElementsAnnotatedWith(container));

		for (Element e : candidates) {
			if (e.getKind() != ElementKind.METHOD) {
				error(e, "Soklet: @%s can only be applied to methods.", baseAnnotation.getSimpleName());
				continue;
			}

			ExecutableElement method = (ExecutableElement) e;
			TypeElement owner = (TypeElement) method.getEnclosingElement();

			boolean isPublic = method.getModifiers().contains(Modifier.PUBLIC);
			boolean isStatic = method.getModifiers().contains(Modifier.STATIC);

			if (isStatic) error(method, "Soklet: Resource Method must not be static");
			if (!isPublic) error(method, "Soklet: Resource Method must be public");

			// Extract each occurrence as an AnnotationMirror (handles repeatable containers)
			List<AnnotationMirror> occurrences = extractOccurrences(method, base, container);

			for (AnnotationMirror annMirror : occurrences) {
				String rawPath = readAnnotationStringMember(annMirror, "value");
				if (rawPath == null || rawPath.isBlank()) {
					error(method, "Soklet: @%s must have a non-empty path value", baseAnnotation.getSimpleName());
					continue;
				}

				String path = normalizePath(rawPath);

				ValidationResult vr = validatePathTemplate(method, path);
				if (!vr.ok) continue;

				ParamBindings pb = readPathParameterBindings(method);

				// a) placeholders must be bound
				for (String placeholder : vr.placeholders) {
					if (!pb.paramNames.contains(placeholder)) {
						String shown = vr.original.getOrDefault(placeholder, placeholder);
						error(method, "Resource Method path parameter {" + shown + "} not bound to a @PathParameter argument");
					}
				}

				// b) annotated params must exist in template
				for (String annotated : pb.paramNames) {
					if (!vr.placeholders.contains(annotated)) {
						error(method, "No placeholder {" + annotated + "} present in resource path declaration");
					}
				}

				// Only collect if this method is otherwise valid
				if (!pb.hadError && vr.ok && isPublic && !isStatic) {
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

	private List<AnnotationMirror> extractOccurrences(ExecutableElement method, TypeElement base, TypeElement container) {
		List<AnnotationMirror> out = new ArrayList<>();

		for (AnnotationMirror am : method.getAnnotationMirrors()) {
			if (base != null && isAnnotationType(am, base)) {
				out.add(am);
			} else if (container != null && isAnnotationType(am, container)) {
				Object v = readAnnotationMemberValue(am, "value");
				if (v instanceof List<?> list) {
					for (Object o : list) {
						if (o instanceof AnnotationValue av) {
							Object inner = av.getValue();
							if (inner instanceof AnnotationMirror innerAm) {
								out.add(innerAm);
							}
						}
					}
				}
			}
		}

		return out;
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

	private static Object readAnnotationMemberValue(AnnotationMirror am, String member) {
		for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> e : am.getElementValues().entrySet()) {
			if (e.getKey().getSimpleName().contentEquals(member)) {
				return e.getValue().getValue();
			}
		}
		return null;
	}

	private static String readAnnotationStringMember(AnnotationMirror am, String member) {
		Object v = readAnnotationMemberValue(am, member);
		return (v == null) ? null : v.toString();
	}

	// --- Path parsing/validation ----------------------------------------------

	private static final class ValidationResult {
		final boolean ok;
		final Set<String> placeholders;       // normalized names (no trailing '*')
		final Map<String, String> original;   // normalized -> original token

		ValidationResult(boolean ok, Set<String> placeholders, Map<String, String> original) {
			this.ok = ok;
			this.placeholders = placeholders;
			this.original = original;
		}
	}

	/**
	 * Validates braces and duplicate placeholders (treating {name*} as a greedy/varargs placeholder whose
	 * logical name is "name"). Duplicate detection is done on the normalized name (without trailing '*').
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

				String normalized = normalizePlaceholder(token);
				if (normalized.isEmpty()) {
					error(reportOn, "Soklet: Malformed resource path declaration (unbalanced braces)");
					return new ValidationResult(false, Collections.emptySet(), Collections.emptyMap());
				}

				if (!names.add(normalized)) {
					error(reportOn, "Soklet: Duplicate @PathParameter name: " + normalized);
				}
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

	private static String normalizePlaceholder(String token) {
		if (token.endsWith("*")) return token.substring(0, token.length() - 1);
		return token;
	}

	// --- Existing utilities ----------------------------------------------------

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

	private static String prettyType(TypeMirror t) {
		return (t == null ? "null" : t.toString());
	}

	// ---- Index read/merge/write ----------------------------------------------

	private void mergeAndWriteIndex(List<ResourceMethodDeclaration> newlyCollected,
																	Set<String> touchedTopLevelBinaries) {

		Path classOutputRoot = findClassOutputRoot();
		Path classOutputIndexPath = (classOutputRoot == null ? null : classOutputRoot.resolve(RESOURCE_METHOD_LOOKUP_TABLE_PATH));

		Path sideCarIndexPath = (cacheMode == CacheMode.NONE ? null : sideCarIndexPath(classOutputRoot));
		Path persistentIndexPath = (cacheMode == CacheMode.PERSISTENT ? persistentIndexPath(classOutputRoot) : null);

		debug("SokletProcessor: cacheMode=%s", cacheMode);
		debug("SokletProcessor: classOutputRoot=%s", classOutputRoot);
		debug("SokletProcessor: classOutputIndexPath=%s", classOutputIndexPath);
		debug("SokletProcessor: sidecarIndexPath=%s", sideCarIndexPath);
		debug("SokletProcessor: persistentIndexPath=%s", persistentIndexPath);
		debug("SokletProcessor: touchedTopLevels=%s", touchedTopLevelBinaries);

		// Always merge from ALL enabled sources. Never "fallback only if empty".
		Map<String, ResourceMethodDeclaration> merged = new LinkedHashMap<>();

		// Oldest/most durable first
		if (persistentIndexPath != null) readIndexFromPath(persistentIndexPath, merged);
		if (sideCarIndexPath != null) readIndexFromPath(sideCarIndexPath, merged);

		// Then current output dir (direct file access, if possible)
		if (classOutputIndexPath != null) readIndexFromPath(classOutputIndexPath, merged);

		// Then via filer (often works even if direct file paths don't)
		readIndexFromLocation(StandardLocation.CLASS_OUTPUT, merged);

		debug("SokletProcessor: mergedExistingIndexSize=%d", merged.size());

		// Remove stale entries for classes being recompiled now (top-level + nested)
		removeTouchedEntries(merged, touchedTopLevelBinaries);
		debug("SokletProcessor: afterRemovingTouched=%d", merged.size());

		// Add new entries
		for (ResourceMethodDeclaration r : dedupeAndOrder(newlyCollected)) {
			merged.put(generateKey(r), r);
		}

		// Optional prune by classfile existence (NOT IDE-safe by default)
		if (pruneDeletedEnabled && classOutputRoot != null) {
			merged.values().removeIf(r -> !classFileExistsInOutputRoot(classOutputRoot, r.className()));
			debug("SokletProcessor: afterPruneDeleted=%d", merged.size());
		}

		List<ResourceMethodDeclaration> toWrite = new ArrayList<>(merged.values());
		toWrite.sort(Comparator
				.comparing((ResourceMethodDeclaration r) -> r.httpMethod().name())
				.thenComparing(ResourceMethodDeclaration::path)
				.thenComparing(ResourceMethodDeclaration::className)
				.thenComparing(ResourceMethodDeclaration::methodName));

		// Write CLASS_OUTPUT index (the real output)
		writeRoutesIndexResource(toWrite, classOutputIndexPath, touchedTopLevelBinaries, newlyCollected);

		// Write caches (best-effort)
		if (sideCarIndexPath != null) writeIndexFileAtomically(sideCarIndexPath, toWrite);
		if (persistentIndexPath != null) writeIndexFileAtomically(persistentIndexPath, toWrite);

		debug("SokletProcessor: wroteIndexSize=%d", toWrite.size());
	}

	private void removeTouchedEntries(Map<String, ResourceMethodDeclaration> merged,
																		Set<String> touchedTopLevelBinaries) {
		if (touchedTopLevelBinaries == null || touchedTopLevelBinaries.isEmpty()) return;

		merged.values().removeIf(r -> {
			String ownerBin = r.className();
			for (String top : touchedTopLevelBinaries) {
				if (ownerBin.equals(top) || ownerBin.startsWith(top + "$")) return true;
			}
			return false;
		});
	}

	private boolean readIndexFromLocation(StandardLocation location, Map<String, ResourceMethodDeclaration> out) {
		try {
			FileObject fo = filer.getResource(location, "", RESOURCE_METHOD_LOOKUP_TABLE_PATH);
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(fo.openInputStream(), StandardCharsets.UTF_8))) {
				readIndexFromReader(reader, out);
			}
			return true;
		} catch (IOException ignored) {
			return false;
		}
	}

	private boolean readIndexFromPath(Path path, Map<String, ResourceMethodDeclaration> out) {
		if (path == null || !Files.isRegularFile(path)) return false;
		try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			readIndexFromReader(reader, out);
			return true;
		} catch (IOException ignored) {
			return false;
		}
	}

	private void readIndexFromReader(BufferedReader reader, Map<String, ResourceMethodDeclaration> out) throws IOException {
		String line;
		while ((line = reader.readLine()) != null) {
			line = line.trim();
			if (line.isEmpty()) continue;
			ResourceMethodDeclaration r = parseIndexLine(line);
			if (r != null) out.put(generateKey(r), r);
		}
	}

	private Path findClassOutputRoot() {
		// Try to read an existing marker file
		try {
			FileObject fo = filer.getResource(StandardLocation.CLASS_OUTPUT, "", OUTPUT_ROOT_MARKER_PATH);
			Path root = outputRootFromUri(fo.toUri(), OUTPUT_ROOT_MARKER_PATH);
			if (root != null) return root;
		} catch (IOException ignored) {
		}

		// Create marker to discover root
		try {
			FileObject fo = filer.createResource(StandardLocation.CLASS_OUTPUT, "", OUTPUT_ROOT_MARKER_PATH);
			try (Writer w = fo.openWriter()) {
				w.write("");
			}
			return outputRootFromUri(fo.toUri(), OUTPUT_ROOT_MARKER_PATH);
		} catch (IOException ignored) {
			return null;
		}
	}

	private Path sideCarIndexPath(Path classOutputRoot) {
		if (classOutputRoot == null) return null;
		Path parent = classOutputRoot.getParent();
		if (parent == null) return null;
		String outputRootName = classOutputRoot.getFileName().toString();
		return parent.resolve(SIDE_CAR_DIR_NAME).resolve(outputRootName).resolve(SIDE_CAR_INDEX_FILENAME);
	}

	private Path persistentIndexPath(Path classOutputRoot) {
		if (classOutputRoot == null) return null;
		Path cacheRoot = persistentCacheRoot();
		if (cacheRoot == null) return null;

		String key = hashPath(classOutputRoot.toAbsolutePath().normalize().toString());
		return cacheRoot.resolve(PERSISTENT_CACHE_INDEX_DIR).resolve(key).resolve(SIDE_CAR_INDEX_FILENAME);
	}

	/**
	 * Persistent caching is only enabled when soklet.cacheDir is explicitly set.
	 * This avoids writing project-root ".soklet" directories by default.
	 */
	private Path persistentCacheRoot() {
		String override = processingEnv.getOptions().get(PROCESSOR_OPTION_CACHE_DIR);
		if (override == null || override.isBlank()) return null;
		try {
			return Paths.get(override);
		} catch (RuntimeException ignored) {
			return null;
		}
	}

	private boolean classFileExistsInOutputRoot(Path root, String binaryName) {
		if (root == null) return true;
		Path classFile = root.resolve(binaryName.replace('.', '/') + ".class");
		return Files.isRegularFile(classFile);
	}

	private Path outputRootFromUri(URI uri, String pathSuffix) {
		if (uri == null || !"file".equalsIgnoreCase(uri.getScheme())) return null;
		Path file = Paths.get(uri);
		int segments = countPathSegments(pathSuffix);
		Path root = file;
		for (int i = 0; i < segments; i++) {
			root = root.getParent();
			if (root == null) return null;
		}
		return root;
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

	/**
	 * Writes the merged index to CLASS_OUTPUT.
	 * Uses originating elements (best effort) so incremental build tools can track dependencies.
	 *
	 * <p>Fallback strategy if createResource fails:
	 * <ol>
	 *   <li>Try opening a writer on filer.getResource(...)</li>
	 *   <li>Try direct filesystem write if classOutputIndexPath is available</li>
	 * </ol>
	 */
	private void writeRoutesIndexResource(List<ResourceMethodDeclaration> routes,
																				Path classOutputIndexPath,
																				Set<String> touchedTopLevelBinaries,
																				List<ResourceMethodDeclaration> newlyCollected) {
		Element[] origins = computeOriginatingElements(touchedTopLevelBinaries, newlyCollected);

		try {
			FileObject fo = filer.createResource(StandardLocation.CLASS_OUTPUT, "", RESOURCE_METHOD_LOOKUP_TABLE_PATH, origins);
			try (Writer w = fo.openWriter()) {
				writeIndexToWriter(w, routes);
			}
			return;
		} catch (FilerException exists) {
			// Try writing via getResource/openWriter
			try {
				FileObject fo = filer.getResource(StandardLocation.CLASS_OUTPUT, "", RESOURCE_METHOD_LOOKUP_TABLE_PATH);
				try (Writer w = fo.openWriter()) {
					writeIndexToWriter(w, routes);
				}
				return;
			} catch (IOException ignored) {
				// Fall through to direct path write if available
			}
		} catch (IOException e) {
			// Fall through to direct path write if available
			debug("SokletProcessor: filer.createResource/openWriter failed (%s); attempting direct write.", e);
		}

		// Direct path write (best effort)
		if (classOutputIndexPath != null) {
			try {
				writeIndexFileAtomicallyOrThrow(classOutputIndexPath, routes);
				return;
			} catch (IOException e) {
				throw new UncheckedIOException("Failed to write " + RESOURCE_METHOD_LOOKUP_TABLE_PATH, e);
			}
		}

		throw new UncheckedIOException("Failed to write " + RESOURCE_METHOD_LOOKUP_TABLE_PATH, new IOException("No writable CLASS_OUTPUT path available"));
	}

	private Element[] computeOriginatingElements(Set<String> touchedTopLevelBinaries,
																							 List<ResourceMethodDeclaration> newlyCollected) {
		Set<Element> origins = new LinkedHashSet<>();

		// Always include touched top-level types (these are definitely in this compilation)
		if (touchedTopLevelBinaries != null) {
			for (String top : touchedTopLevelBinaries) {
				TypeElement te = elements.getTypeElement(top);
				if (te != null) origins.add(te);
			}
		}

		// Also include owners of newly collected routes (top-level if possible)
		if (newlyCollected != null) {
			for (ResourceMethodDeclaration r : newlyCollected) {
				String bin = r.className();
				int dollar = bin.indexOf('$');
				String top = (dollar >= 0) ? bin.substring(0, dollar) : bin;

				TypeElement te = elements.getTypeElement(top);
				if (te != null) origins.add(te);
			}
		}

		return origins.toArray(new Element[0]);
	}

	private void writeIndexToWriter(Writer w, List<ResourceMethodDeclaration> routes) throws IOException {
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

	/**
	 * Best-effort atomic write. Failures are logged (if debug enabled) and ignored.
	 */
	private void writeIndexFileAtomically(Path target, List<ResourceMethodDeclaration> routes) {
		if (target == null) return;
		try {
			writeIndexFileAtomicallyOrThrow(target, routes);
		} catch (IOException e) {
			debug("SokletProcessor: failed to write cache index %s (%s)", target, e);
		}
	}

	private void writeIndexFileAtomicallyOrThrow(Path target, List<ResourceMethodDeclaration> routes) throws IOException {
		Path parent = target.getParent();
		if (parent != null) Files.createDirectories(parent);

		// temp file in same dir so move is atomic on most filesystems
		Path tmp = Files.createTempFile(parent, target.getFileName().toString(), ".tmp");
		try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
			writeIndexToWriter(w, routes);
		}

		try {
			Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
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

	private void debug(String fmt, Object... args) {
		if (!debugEnabled) return;
		messager.printMessage(Diagnostic.Kind.NOTE, String.format(fmt, args));
	}

	// ---- Misc helpers ---------------------------------------------------------

	private static CacheMode parseCacheMode(String option) {
		if (option == null || option.isBlank()) return CacheMode.SIDECAR;

		String normalized = option.trim().toLowerCase(Locale.ROOT);
		switch (normalized) {
			case "none":
			case "off":
			case "false":
				return CacheMode.NONE;
			case "sidecar":
				return CacheMode.SIDECAR;
			case "persistent":
			case "persist":
				return CacheMode.PERSISTENT;
			default:
				// Unknown -> default to sidecar for safety
				return CacheMode.SIDECAR;
		}
	}

	private static boolean parseBooleanishOption(String option) {
		if (option == null) return false;
		String normalized = option.trim();
		if (normalized.isEmpty()) return false;
		return !"false".equalsIgnoreCase(normalized);
	}

	private static String hashPath(String input) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-1");
			byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
			return toHex(bytes);
		} catch (NoSuchAlgorithmException e) {
			return Integer.toHexString(input.hashCode());
		}
	}

	private static String toHex(byte[] bytes) {
		char[] out = new char[bytes.length * 2];
		char[] digits = "0123456789abcdef".toCharArray();
		for (int i = 0; i < bytes.length; i++) {
			int v = bytes[i] & 0xFF;
			out[i * 2] = digits[v >>> 4];
			out[i * 2 + 1] = digits[v & 0x0F];
		}
		return new String(out);
	}

	private static int countPathSegments(String path) {
		int count = 1;
		for (int i = 0; i < path.length(); i++) {
			if (path.charAt(i) == '/') count++;
		}
		return count;
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