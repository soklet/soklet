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
import com.soklet.annotation.McpHeader;
import com.soklet.annotation.McpResourceList;
import com.soklet.annotation.McpPrompt;
import com.soklet.annotation.McpPromptArgument;
import com.soklet.annotation.McpResource;
import com.soklet.annotation.McpResourceUriParameter;
import com.soklet.annotation.McpServerEndpoint;
import com.soklet.annotation.McpTool;
import com.soklet.annotation.McpToolArgument;
import com.soklet.annotation.McpToolProperty;
import com.soklet.annotation.OPTIONS;
import com.soklet.annotation.PATCH;
import com.soklet.annotation.POST;
import com.soklet.annotation.PUT;
import com.soklet.annotation.SseEventSource;
import com.soklet.internal.mcp.generated.McpGeneratedEndpointProviderIndex;
import com.soklet.internal.mcp.schema.McpTypeMirrorTypedSchemaBridge;
import com.google.errorprone.annotations.FormatMethod;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

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
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.JavaFileObject;
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
import java.util.ArrayDeque;
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
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Soklet's standard annotation processor. It generates lookup tables for
 * <em>Resource Method</em> definitions, generated public-API-only MCP endpoint
 * adapters and their classpath index, and reports usage errors detectable by
 * static analysis.
 * <p>
 * This Annotation Processor ensures <em>Resource Methods</em> annotated with {@link SseEventSource} are declared as returning an instance of {@link SseHandshakeResult}.
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
 * <pre>{@code def sokletVersion = "4.0.0" // (use your actual version)
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
 *   <li>Declares wildcard annotation support, while claiming no annotations, so a compiler still invokes it when a touched type removes its final Soklet annotation.</li>
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
	private static final String MCP_PERSISTENT_CACHE_INDEX_DIR =
			"mcp-endpoints";
	private static final int MAXIMUM_MCP_RESOURCE_URI_TEMPLATE_VARIABLES = 32;

	// ---- Index paths ---------------------------------------------------------

	static final String RESOURCE_METHOD_LOOKUP_TABLE_PATH = "META-INF/soklet/resource-method-lookup-table";
	private static final String OUTPUT_ROOT_MARKER_PATH = "META-INF/soklet/.soklet-output-root";

	private static final String SIDE_CAR_DIR_NAME = "soklet";
	private static final String SIDE_CAR_INDEX_FILENAME = "resource-method-lookup-table";
	private static final String MCP_SIDE_CAR_INDEX_FILENAME =
			"mcp-endpoint-descriptor-providers";
	// Keep the unresolved-type preflight inside the production typed-schema
	// traversal envelope. Crossing either bound delegates to the schema compiler,
	// which emits the stable LIMIT_EXCEEDED diagnostic.
	private static final int MCP_SCHEMA_PREFLIGHT_MAXIMUM_DEPTH = 64;
	private static final int MCP_SCHEMA_PREFLIGHT_MAXIMUM_NODE_COUNT = 4_096;

	// ---- JSR-269 services ----------------------------------------------------

	private Types types;
	private Elements elements;
	private Messager messager;
	private Filer filer;

	private boolean debugEnabled;
	private boolean pruneDeletedEnabled;
	private CacheMode cacheMode;

	// Cached mirrors resolved in init()
	private TypeMirror sseHandshakeResultType;   // com.soklet.SseHandshakeResult
	private TypeElement pathParameterElement;    // com.soklet.annotation.PathParameter
	private TypeMirror mcpRequestContextType;
	private TypeMirror mcpInvocationFeaturesType;
	private TypeMirror cancelationTokenType;
	private TypeMirror mcpProgressReporterType;
	private TypeMirror mcpPromptOutputType;
	private TypeMirror mcpResourceOutputType;
	private TypeMirror mcpResourcePageType;
	private TypeMirror mcpResourceReadContextType;
	private TypeMirror mcpResourceListContextType;
	private TypeMirror mcpOperationResultType;
	private TypeMirror stringType;
	private TypeMirror optionalType;
	private TypeMirror exceptionType;
	private TypeMirror errorType;

	// Collected during this compilation invocation
	private final List<ResourceMethodDeclaration> collected = new ArrayList<>();
	private final Set<String> touchedTopLevelBinaries = new LinkedHashSet<>();
	private boolean resourceMethodAmbiguityDetected;
	private final List<McpEndpointProviderDeclaration> collectedMcpEndpoints =
			new ArrayList<>();
	private final Set<String> touchedMcpTopLevelBinaries =
			new LinkedHashSet<>();
	private final Set<String> generatedMcpProviderBinaries =
			new LinkedHashSet<>();
	private final Set<String> processedMcpEndpointBinaries =
			new LinkedHashSet<>();
	private final Map<String, TypeElement> pendingMcpEndpoints =
			new LinkedHashMap<>();
	private final Map<String, String> mcpEndpointBinaryByPath =
			new LinkedHashMap<>();
	private boolean mcpProcessingErrorDetected;
	private int mcpProcessingErrorCount;

	// ---- Supported annotations ----------------------------------------------

	private static final List<Class<? extends Annotation>> HTTP_AND_SSE_ANNOTATIONS = List.of(
			GET.class, POST.class, PUT.class, PATCH.class, DELETE.class, HEAD.class, OPTIONS.class,
			SseEventSource.class
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

		TypeElement hr = elements.getTypeElement("com.soklet.SseHandshakeResult");
		this.sseHandshakeResultType = (hr == null ? null : hr.asType());
		this.pathParameterElement = elements.getTypeElement("com.soklet.annotation.PathParameter");
		TypeElement mcpRequestContext =
				elements.getTypeElement("com.soklet.McpRequestContext");
		this.mcpRequestContextType = mcpRequestContext == null
				? null : mcpRequestContext.asType();
		TypeElement mcpInvocationFeatures =
				elements.getTypeElement("com.soklet.McpInvocationFeatures");
		this.mcpInvocationFeaturesType = mcpInvocationFeatures == null
				? null : mcpInvocationFeatures.asType();
		TypeElement cancelationToken =
				elements.getTypeElement("com.soklet.CancelationToken");
		this.cancelationTokenType = cancelationToken == null
				? null : cancelationToken.asType();
		TypeElement mcpProgressReporter =
				elements.getTypeElement("com.soklet.McpProgressReporter");
		this.mcpProgressReporterType = mcpProgressReporter == null
				? null : mcpProgressReporter.asType();
		TypeElement mcpPromptOutput =
				elements.getTypeElement("com.soklet.McpPromptOutput");
		this.mcpPromptOutputType = mcpPromptOutput == null
				? null : mcpPromptOutput.asType();
		TypeElement mcpResourceOutput =
				elements.getTypeElement("com.soklet.McpResourceOutput");
		this.mcpResourceOutputType = mcpResourceOutput == null
				? null : mcpResourceOutput.asType();
		TypeElement mcpResourcePage =
				elements.getTypeElement("com.soklet.McpResourcePage");
		this.mcpResourcePageType = mcpResourcePage == null
				? null : mcpResourcePage.asType();
		TypeElement mcpResourceReadContext =
				elements.getTypeElement("com.soklet.McpResourceReadContext");
		this.mcpResourceReadContextType = mcpResourceReadContext == null
				? null : mcpResourceReadContext.asType();
		TypeElement mcpResourceListContext =
				elements.getTypeElement("com.soklet.McpResourceListContext");
		this.mcpResourceListContextType = mcpResourceListContext == null
				? null : mcpResourceListContext.asType();
		TypeElement mcpOperationResult =
				elements.getTypeElement("com.soklet.McpOperationResult");
		this.mcpOperationResultType = mcpOperationResult == null
				? null : mcpOperationResult.asType();
		TypeElement string = elements.getTypeElement("java.lang.String");
		this.stringType = string == null ? null : string.asType();
		TypeElement optional = elements.getTypeElement("java.util.Optional");
		this.optionalType = optional == null ? null : optional.asType();
		TypeElement exception = elements.getTypeElement("java.lang.Exception");
		this.exceptionType = exception == null ? null : exception.asType();
		TypeElement error = elements.getTypeElement("java.lang.Error");
		this.errorType = error == null ? null : error.asType();

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
		out.add(McpServerEndpoint.class.getCanonicalName());
		out.add(McpTool.class.getCanonicalName());
		out.add(McpToolArgument.class.getCanonicalName());
		out.add(McpToolProperty.class.getCanonicalName());
		out.add(McpHeader.class.getCanonicalName());
		out.add(McpPrompt.class.getCanonicalName());
		out.add(McpPromptArgument.class.getCanonicalName());
		out.add(McpResource.class.getCanonicalName());
		out.add(McpResourceUriParameter.class.getCanonicalName());
		out.add(McpResourceList.class.getCanonicalName());
		// Keep the processor active when a touched type removes its final Soklet
		// annotation so stale generated index rows can still be pruned.
		out.add("*");
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
				if (!generatedMcpProviderBinaries.contains(bin))
					touchedMcpTopLevelBinaries.add(bin);
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
		collect(roundEnv, HttpMethod.GET, SseEventSource.class, true); // SSE as GET + flag

		collectAndGenerateMcpEndpoints(roundEnv);

		if (roundEnv.processingOver()) {
			// Critical: don't overwrite a good index with a partial/failed compile.
			if (roundEnv.errorRaised() || resourceMethodAmbiguityDetected
					|| mcpProcessingErrorDetected) {
				debug("SokletProcessor: compilation has errors; skipping index write to avoid clobbering.");
				return false;
			}
			mergeAndWriteIndex(collected, touchedTopLevelBinaries);
			mergeAndWriteMcpEndpointIndex(collectedMcpEndpoints,
					touchedMcpTopLevelBinaries);
		}

		return false;
	}

	/**
	 * Collects and validates each annotated method occurrence (repeatable-aware, without reflection).
	 */
	private void collect(RoundEnvironment roundEnv,
											 HttpMethod httpMethod,
											 Class<? extends Annotation> baseAnnotation,
											 boolean sseEventSource) {

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
						error(method, "Resource Method path parameter {%s} not bound to a @PathParameter argument", shown);
					}
				}

				// b) annotated params must exist in template
				for (String annotated : pb.paramNames) {
					if (!vr.placeholders.contains(annotated)) {
						error(method, "No placeholder {%s} present in resource path declaration", annotated);
					}
				}

				// Only collect if this method is otherwise valid
				if (!pb.hadError && vr.ok && isPublic && !isStatic) {
					String className = elements.getBinaryName(owner).toString();
					String methodName = method.getSimpleName().toString();
					String[] paramTypes = method.getParameters().stream()
							.map(p -> jvmTypeName(p.asType()))
							.toArray(String[]::new);

					ResourceMethodDeclaration declaration = new ResourceMethodDeclaration(
							httpMethod, path, className, methodName, paramTypes, sseEventSource
					);
					detectResourceMethodAmbiguity(method, declaration);
					collected.add(declaration);
				}
			}
		}
	}

	// --- MCP endpoint generation ---------------------------------------------

	private void collectAndGenerateMcpEndpoints(
			@NonNull RoundEnvironment roundEnv) {
		TypeElement endpointAnnotation = elements.getTypeElement(
				McpServerEndpoint.class.getCanonicalName());
		TypeElement toolAnnotation =
				elements.getTypeElement(McpTool.class.getCanonicalName());
		TypeElement argumentAnnotation = elements.getTypeElement(
				McpToolArgument.class.getCanonicalName());
		TypeElement headerAnnotation = elements.getTypeElement(
				McpHeader.class.getCanonicalName());
		TypeElement promptAnnotation =
				elements.getTypeElement(McpPrompt.class.getCanonicalName());
		TypeElement promptArgumentAnnotation = elements.getTypeElement(
				McpPromptArgument.class.getCanonicalName());
		TypeElement resourceAnnotation =
				elements.getTypeElement(McpResource.class.getCanonicalName());
		TypeElement resourceUriParameterAnnotation = elements.getTypeElement(
				McpResourceUriParameter.class.getCanonicalName());
		TypeElement listResourcesAnnotation = elements.getTypeElement(
				McpResourceList.class.getCanonicalName());
		if (endpointAnnotation == null || toolAnnotation == null
				|| argumentAnnotation == null || headerAnnotation == null
				|| promptAnnotation == null
				|| promptArgumentAnnotation == null || resourceAnnotation == null
				|| resourceUriParameterAnnotation == null
				|| listResourcesAnnotation == null)
			return;

		validateMcpAnnotationPlacement(roundEnv, endpointAnnotation,
				toolAnnotation, argumentAnnotation, headerAnnotation,
				promptAnnotation,
				promptArgumentAnnotation, resourceAnnotation,
				resourceUriParameterAnnotation, listResourcesAnnotation);

		for (Element element : roundEnv.getElementsAnnotatedWith(
				endpointAnnotation)) {
			if (element instanceof TypeElement type) {
				pendingMcpEndpoints.put(
						elements.getBinaryName(type).toString(),
						type);
			} else {
				mcpError(element,
						"Soklet: @McpServerEndpoint can only be applied to classes.");
			}
		}
		List<TypeElement> endpointTypes = new ArrayList<>();
		for (Map.Entry<String, TypeElement> pending
				: pendingMcpEndpoints.entrySet()) {
			if (processedMcpEndpointBinaries.contains(pending.getKey()))
				continue;
			TypeElement originalType = pending.getValue();
			TypeElement type = elements.getTypeElement(
					originalType.getQualifiedName());
			if (type != null)
				endpointTypes.add(type);
			else if (roundEnv.processingOver())
				endpointTypes.add(originalType);
		}
		endpointTypes.sort(Comparator.comparing(
				type -> elements.getBinaryName(type).toString()));

		for (TypeElement endpointType : endpointTypes) {
			String endpointBinaryName =
					elements.getBinaryName(endpointType).toString();
			if (processedMcpEndpointBinaries.contains(endpointBinaryName))
				continue;
			if (hasUnresolvedMcpOperationType(endpointType, toolAnnotation,
					promptAnnotation, resourceAnnotation,
					listResourcesAnnotation)) {
				if (roundEnv.processingOver()) {
					mcpError(endpointType,
							"Soklet: @McpServerEndpoint contains an unresolved MCP operation parameter or return type after annotation processing completed.");
					processedMcpEndpointBinaries.add(endpointBinaryName);
					pendingMcpEndpoints.remove(endpointBinaryName);
				}
				continue;
			}
			processedMcpEndpointBinaries.add(endpointBinaryName);
			pendingMcpEndpoints.remove(endpointBinaryName);

			McpEndpointModel endpoint = validateMcpEndpoint(endpointType,
					toolAnnotation, argumentAnnotation, headerAnnotation,
					promptAnnotation,
					promptArgumentAnnotation, resourceAnnotation,
					resourceUriParameterAnnotation, listResourcesAnnotation);
			if (endpoint == null)
				continue;

			String previousEndpoint = mcpEndpointBinaryByPath.putIfAbsent(
					endpoint.path(), endpoint.endpointBinaryName());
			if (previousEndpoint != null
					&& !previousEndpoint.equals(endpoint.endpointBinaryName())) {
				mcpError(endpointType,
						"Soklet: Duplicate annotated MCP endpoint path is also declared by %s.",
						previousEndpoint);
				continue;
			}

			if (generateMcpEndpointProvider(endpoint, endpointType)) {
				generatedMcpProviderBinaries.add(endpoint.providerBinaryName());
				collectedMcpEndpoints.add(new McpEndpointProviderDeclaration(
						endpoint.endpointBinaryName(), endpoint.providerBinaryName(),
						topLevelBinaryName(endpointType), endpoint.path()));
			}
		}
	}

	private boolean hasUnresolvedMcpOperationType(
			@NonNull TypeElement endpointType,
			@NonNull TypeElement toolAnnotation,
			@NonNull TypeElement promptAnnotation,
			@NonNull TypeElement resourceAnnotation,
			@NonNull TypeElement listResourcesAnnotation) {
		for (Element enclosed : endpointType.getEnclosedElements()) {
			if (enclosed.getKind() != ElementKind.METHOD
					|| (findAnnotation(enclosed, toolAnnotation) == null
					&& findAnnotation(enclosed, promptAnnotation) == null
					&& findAnnotation(enclosed, resourceAnnotation) == null
					&& findAnnotation(enclosed, listResourcesAnnotation) == null))
				continue;
			ExecutableElement method = (ExecutableElement) enclosed;
			if (containsErrorType(method.getReturnType()))
				return true;
			for (VariableElement parameter : method.getParameters())
				if (containsErrorType(parameter.asType()))
					return true;
			for (TypeMirror thrownType : method.getThrownTypes())
				if (containsErrorType(thrownType))
					return true;
		}
		return false;
	}

	private boolean containsErrorType(@NonNull TypeMirror type) {
		return containsErrorType(type, new LinkedHashSet<>(), 1,
				new int[] { 0 });
	}

	private boolean containsErrorType(@NonNull TypeMirror type,
			@NonNull Set<@NonNull TypeElement> activeRecords, int depth,
			int @NonNull [] visitedNodeCount) {
		if (depth > MCP_SCHEMA_PREFLIGHT_MAXIMUM_DEPTH
				|| visitedNodeCount[0]
				>= MCP_SCHEMA_PREFLIGHT_MAXIMUM_NODE_COUNT)
			return false;
		visitedNodeCount[0]++;
		if (type.getKind() == TypeKind.ERROR)
			return true;
		if (type instanceof ArrayType array)
			return containsErrorType(array.getComponentType(), activeRecords,
					depth + 1, visitedNodeCount);
		if (type instanceof DeclaredType declared) {
			TypeMirror enclosing = declared.getEnclosingType();
			if (enclosing.getKind() != TypeKind.NONE
					&& containsErrorType(enclosing, activeRecords, depth + 1,
							visitedNodeCount))
				return true;
			for (TypeMirror argument : declared.getTypeArguments())
				if (containsErrorType(argument, activeRecords, depth + 1,
						visitedNodeCount))
					return true;
			if (!(declared.asElement() instanceof TypeElement declaration)
					|| declaration.getKind() != ElementKind.RECORD
					|| !activeRecords.add(declaration))
				return false;
			try {
				for (RecordComponentElement component
						: declaration.getRecordComponents()) {
					TypeMirror accessorType = types.asMemberOf(declared,
							component.getAccessor());
					if (accessorType instanceof ExecutableType accessor
							&& containsErrorType(accessor.getReturnType(),
									activeRecords, depth + 1,
									visitedNodeCount))
						return true;
				}
			} finally {
				activeRecords.remove(declaration);
			}
			return false;
		}
		// Unsupported operation type variables are rejected later by the
		// signature policies. Avoid traversing self-referential generic bounds
		// while looking only for compiler ERROR placeholders that another
		// processor may resolve.
		if (type instanceof TypeVariable)
			return false;
		if (type instanceof WildcardType wildcard) {
			TypeMirror extendsBound = wildcard.getExtendsBound();
			TypeMirror superBound = wildcard.getSuperBound();
			return (extendsBound != null
					&& containsErrorType(extendsBound, activeRecords, depth + 1,
							visitedNodeCount))
					|| (superBound != null
					&& containsErrorType(superBound, activeRecords, depth + 1,
							visitedNodeCount));
		}
		return false;
	}

	private void validateMcpAnnotationPlacement(
			@NonNull RoundEnvironment roundEnv,
			@NonNull TypeElement endpointAnnotation,
			@NonNull TypeElement toolAnnotation,
			@NonNull TypeElement argumentAnnotation,
			@NonNull TypeElement headerAnnotation,
			@NonNull TypeElement promptAnnotation,
			@NonNull TypeElement promptArgumentAnnotation,
			@NonNull TypeElement resourceAnnotation,
			@NonNull TypeElement resourceUriParameterAnnotation,
			@NonNull TypeElement listResourcesAnnotation) {
		for (Element element : roundEnv.getElementsAnnotatedWith(toolAnnotation)) {
			if (element.getKind() != ElementKind.METHOD) {
				mcpError(element,
						"Soklet: @McpTool can only be applied to methods.");
				continue;
			}
			Element owner = element.getEnclosingElement();
			if (findAnnotation(owner, endpointAnnotation) == null)
				mcpError(element,
						"Soklet: @McpTool methods must be declared directly by an @McpServerEndpoint class.");
		}

		for (Element element : roundEnv.getElementsAnnotatedWith(
				promptAnnotation)) {
			if (element.getKind() != ElementKind.METHOD) {
				mcpError(element,
						"Soklet: @McpPrompt can only be applied to methods.");
				continue;
			}
			Element owner = element.getEnclosingElement();
			if (findAnnotation(owner, endpointAnnotation) == null)
				mcpError(element,
						"Soklet: @McpPrompt methods must be declared directly by an @McpServerEndpoint class.");
		}

		for (Element element : roundEnv.getElementsAnnotatedWith(
				resourceAnnotation)) {
			if (element.getKind() != ElementKind.METHOD) {
				mcpError(element,
						"Soklet: @McpResource can only be applied to methods.");
				continue;
			}
			Element owner = element.getEnclosingElement();
			if (findAnnotation(owner, endpointAnnotation) == null)
				mcpError(element,
						"Soklet: @McpResource methods must be declared directly by an @McpServerEndpoint class.");
		}

		for (Element element : roundEnv.getElementsAnnotatedWith(
				listResourcesAnnotation)) {
			if (element.getKind() != ElementKind.METHOD) {
				mcpError(element,
						"Soklet: @McpResourceList can only be applied to methods.");
				continue;
			}
			Element owner = element.getEnclosingElement();
			if (findAnnotation(owner, endpointAnnotation) == null)
				mcpError(element,
						"Soklet: @McpResourceList methods must be declared directly by an @McpServerEndpoint class.");
		}

		for (Element element : roundEnv.getElementsAnnotatedWith(
				argumentAnnotation)) {
			if (element.getKind() != ElementKind.PARAMETER) {
				mcpError(element,
						"Soklet: @McpToolArgument can only be applied to parameters.");
				continue;
			}
			Element method = element.getEnclosingElement();
			Element owner = method.getEnclosingElement();
			if (findAnnotation(method, toolAnnotation) == null
					|| findAnnotation(owner, endpointAnnotation) == null)
				mcpError(element,
						"Soklet: @McpToolArgument parameters must belong to an @McpTool method on an @McpServerEndpoint class.");
		}

		for (Element element : roundEnv.getElementsAnnotatedWith(
				headerAnnotation)) {
			if (element.getKind() == ElementKind.RECORD_COMPONENT)
				continue;
			if (element.getKind() != ElementKind.PARAMETER) {
				mcpError(element,
						"Soklet: @McpHeader can only be applied to parameters or record components.");
				continue;
			}
			Element method = element.getEnclosingElement();
			Element owner = method.getEnclosingElement();
			// A record-component annotation whose target also includes PARAMETER
			// is propagated by javac to the canonical constructor parameter.
			if (method.getKind() == ElementKind.CONSTRUCTOR
					&& owner instanceof TypeElement recordType
					&& owner.getKind() == ElementKind.RECORD) {
				boolean propagatedFromComponent = recordType.getRecordComponents()
						.stream()
						.anyMatch(component -> component.getSimpleName().contentEquals(
								element.getSimpleName())
								&& findAnnotation(component,
									headerAnnotation) != null);
				if (propagatedFromComponent)
					continue;
			}
			if (findAnnotation(element, argumentAnnotation) == null
					|| findAnnotation(method, toolAnnotation) == null
					|| findAnnotation(owner, endpointAnnotation) == null)
				mcpError(element,
						"Soklet: @McpHeader parameters must also declare @McpToolArgument and belong to an @McpTool method on an @McpServerEndpoint class.");
		}

		for (Element element : roundEnv.getElementsAnnotatedWith(
				promptArgumentAnnotation)) {
			if (element.getKind() != ElementKind.PARAMETER) {
				mcpError(element,
						"Soklet: @McpPromptArgument can only be applied to parameters.");
				continue;
			}
			Element method = element.getEnclosingElement();
			Element owner = method.getEnclosingElement();
			if (findAnnotation(method, promptAnnotation) == null
					|| findAnnotation(owner, endpointAnnotation) == null)
				mcpError(element,
						"Soklet: @McpPromptArgument parameters must belong to an @McpPrompt method on an @McpServerEndpoint class.");
		}

		for (Element element : roundEnv.getElementsAnnotatedWith(
				resourceUriParameterAnnotation)) {
			if (element.getKind() != ElementKind.PARAMETER) {
				mcpError(element,
						"Soklet: @McpResourceUriParameter can only be applied to parameters.");
				continue;
			}
			Element method = element.getEnclosingElement();
			Element owner = method.getEnclosingElement();
			if (findAnnotation(method, resourceAnnotation) == null
					|| findAnnotation(owner, endpointAnnotation) == null)
				mcpError(element,
						"Soklet: @McpResourceUriParameter parameters must belong to an @McpResource method on an @McpServerEndpoint class.");
		}
	}

	private McpEndpointModel validateMcpEndpoint(
			@NonNull TypeElement endpointType,
			@NonNull TypeElement toolAnnotation,
			@NonNull TypeElement argumentAnnotation,
			@NonNull TypeElement headerAnnotation,
			@NonNull TypeElement promptAnnotation,
			@NonNull TypeElement promptArgumentAnnotation,
			@NonNull TypeElement resourceAnnotation,
			@NonNull TypeElement resourceUriParameterAnnotation,
			@NonNull TypeElement listResourcesAnnotation) {
		int errorsBefore = mcpProcessingErrorCount;
		AnnotationMirror annotation = findAnnotation(endpointType,
				McpServerEndpoint.class.getCanonicalName());
		if (annotation == null)
			return null;

		if (endpointType.getKind() != ElementKind.CLASS)
			mcpError(endpointType,
					"Soklet: @McpServerEndpoint must annotate a concrete class.");
		if (!endpointType.getModifiers().contains(Modifier.PUBLIC))
			mcpError(endpointType,
					"Soklet: @McpServerEndpoint class must be public.");
		if (endpointType.getModifiers().contains(Modifier.ABSTRACT))
			mcpError(endpointType,
					"Soklet: @McpServerEndpoint class must be concrete.");
		if (!endpointType.getTypeParameters().isEmpty())
			mcpError(endpointType,
					"Soklet: @McpServerEndpoint class must not declare type parameters.");
		if (endpointType.getEnclosingElement() instanceof TypeElement
				&& !endpointType.getModifiers().contains(Modifier.STATIC))
			mcpError(endpointType,
					"Soklet: A nested @McpServerEndpoint class must be static.");
		for (Element enclosing = endpointType.getEnclosingElement();
				enclosing instanceof TypeElement enclosingType;
				enclosing = enclosing.getEnclosingElement()) {
			if (!enclosingType.getModifiers().contains(Modifier.PUBLIC))
				mcpError(endpointType,
						"Soklet: Every enclosing class of an @McpServerEndpoint must be public.");
		}

		String path = annotationString(annotation, "path").strip();
		if (!path.startsWith("/") || path.length() == 1
				|| path.contains("?") || path.contains("#")) {
			mcpError(endpointType,
					"Soklet: MCP endpoint path must be a non-root absolute path without a query or fragment.");
		} else if (path.indexOf('{') >= 0 || path.indexOf('}') >= 0) {
			mcpError(endpointType,
					"Soklet: MCP endpoint path parameters are not supported by the annotated MCP processor; use a fixed path.");
		} else {
			path = ResourcePathDeclaration.normalizePath(path);
			if (path.length() == 1)
				mcpError(endpointType,
						"Soklet: MCP endpoint path must not normalize to the root path.");
		}

		String name = annotationString(annotation, "name");
		String version = annotationString(annotation, "version");
		String title = annotationString(annotation, "title");
		String description = annotationString(annotation, "description");
		String websiteUrl = annotationString(annotation, "websiteUrl");
		String instructions = annotationString(annotation, "instructions");
		String toolRateLimiter = annotationString(annotation, "toolRateLimiter");
		long resourceListCacheTtlMs = annotationLong(annotation,
				"resourceListCacheTtlMs");
		String resourceListCacheScope = annotationEnumConstantName(annotation,
				"resourceListCacheScope");
		long resourceTemplateListCacheTtlMs = annotationLong(annotation,
				"resourceTemplateListCacheTtlMs");
		String resourceTemplateListCacheScope = annotationEnumConstantName(
				annotation, "resourceTemplateListCacheScope");
		if (name.isBlank())
			mcpError(endpointType,
					"Soklet: MCP implementation name must not be blank.");
		if (version.isBlank())
			mcpError(endpointType,
					"Soklet: MCP implementation version must not be blank.");
		if (!toolRateLimiter.isEmpty() && toolRateLimiter.isBlank())
			mcpError(endpointType,
					"Soklet: MCP endpoint tool rate-limiter name must not be blank.");
		if (resourceListCacheTtlMs < 0)
			mcpError(endpointType,
					"Soklet: MCP resources-list cache TTL must not be negative.");
		if (resourceTemplateListCacheTtlMs < 0)
			mcpError(endpointType,
					"Soklet: MCP resource-template-list cache TTL must not be negative.");
		if (!websiteUrl.isBlank()) {
			try {
				URI uri = URI.create(websiteUrl);
				if (!uri.isAbsolute())
					throw new IllegalArgumentException();
			} catch (IllegalArgumentException exception) {
				mcpError(endpointType,
						"Soklet: MCP implementation websiteUrl must be an absolute URI.");
			}
		}

		String endpointBinaryName =
				elements.getBinaryName(endpointType).toString();
		String packageName = elements.getPackageOf(endpointType)
				.getQualifiedName().toString();
		String providerSimpleName = "SokletMcpEndpointProvider_"
				+ hashPath(endpointBinaryName);
		String providerBinaryName = packageName.isEmpty()
				? providerSimpleName : packageName + "." + providerSimpleName;

		List<McpToolModel> tools = new ArrayList<>();
		List<McpPromptModel> prompts = new ArrayList<>();
		List<McpResourceModel> resources = new ArrayList<>();
		McpResourceListModel resourceList = null;
		for (Element enclosed : endpointType.getEnclosedElements()) {
			if (enclosed.getKind() != ElementKind.METHOD)
				continue;
			boolean tool = findAnnotation(enclosed, toolAnnotation) != null;
			boolean prompt = findAnnotation(enclosed, promptAnnotation) != null;
			boolean resource = findAnnotation(enclosed, resourceAnnotation) != null;
			boolean listResources = findAnnotation(enclosed,
					listResourcesAnnotation) != null;
			if (tool && prompt) {
				mcpError(enclosed,
						"Soklet: An MCP handler method must not declare both @McpTool and @McpPrompt.");
				continue;
			}
			if ((tool ? 1 : 0) + (prompt ? 1 : 0) + (resource ? 1 : 0)
					+ (listResources ? 1 : 0) > 1) {
				mcpError(enclosed,
						"Soklet: An MCP handler method must declare exactly one operation annotation.");
				continue;
			}
			if (tool) {
				McpToolModel model = validateMcpTool((ExecutableElement) enclosed,
						argumentAnnotation, headerAnnotation);
				if (model != null)
					tools.add(model);
			} else if (prompt) {
				McpPromptModel model = validateMcpPrompt(
						(ExecutableElement) enclosed, promptArgumentAnnotation);
				if (model != null)
					prompts.add(model);
			} else if (resource) {
				McpResourceModel model = validateMcpResource(
						(ExecutableElement) enclosed,
						resourceUriParameterAnnotation);
				if (model != null)
					resources.add(model);
			} else if (listResources) {
				McpResourceListModel model = validateMcpResourceList(
						(ExecutableElement) enclosed);
				if (model != null) {
					if (resourceList != null)
						mcpError(enclosed,
								"Soklet: An annotated MCP endpoint may declare at most one @McpResourceList method.");
					else
						resourceList = model;
				}
			}
		}

		tools.sort(Comparator.comparing(McpToolModel::name)
				.thenComparing(tool -> tool.method().getSimpleName().toString())
				.thenComparing(tool -> tool.method().getParameters().stream()
						.map(parameter -> parameter.asType().toString())
						.collect(Collectors.joining(","))));
		Set<String> toolNames = new LinkedHashSet<>();
		for (McpToolModel tool : tools) {
			if (!toolNames.add(tool.name()))
				mcpError(tool.method(),
						"Soklet: Duplicate MCP tool name '%s' in endpoint %s.",
						tool.name(), endpointBinaryName);
		}

		prompts.sort(Comparator.comparing(McpPromptModel::name)
				.thenComparing(prompt -> prompt.method().getSimpleName().toString())
				.thenComparing(prompt -> prompt.method().getParameters().stream()
						.map(parameter -> parameter.asType().toString())
						.collect(Collectors.joining(","))));
		Set<String> promptNames = new LinkedHashSet<>();
		for (McpPromptModel prompt : prompts) {
			if (!promptNames.add(prompt.name()))
				mcpError(prompt.method(),
						"Soklet: Duplicate MCP prompt name '%s' in endpoint %s.",
						prompt.name(), endpointBinaryName);
		}

		resources.sort(Comparator.comparing(McpResourceModel::address)
				.thenComparing(resource -> resource.method().getSimpleName()
						.toString())
				.thenComparing(resource -> resource.method().getParameters().stream()
						.map(parameter -> parameter.asType().toString())
						.collect(Collectors.joining(","))));
		Set<URI> exactResourceUris = new LinkedHashSet<>();
		Set<String> resourceTemplateAddresses = new LinkedHashSet<>();
		for (McpResourceModel resource : resources) {
			boolean unique = resource.template()
					? resourceTemplateAddresses.add(resource.address())
					: exactResourceUris.add(URI.create(resource.address()));
			if (!unique)
				mcpError(resource.method(),
						"Soklet: Duplicate MCP resource address in endpoint %s.",
						endpointBinaryName);
		}
		List<McpResourceModel> templates = resources.stream()
				.filter(McpResourceModel::template).toList();
		for (int leftIndex = 0; leftIndex < templates.size(); ++leftIndex) {
			for (int rightIndex = leftIndex + 1;
					rightIndex < templates.size(); ++rightIndex) {
				McpResourceModel left = templates.get(leftIndex);
				McpResourceModel right = templates.get(rightIndex);
				if (!left.address().equals(right.address())
						&& resourceTemplatesPotentiallyOverlap(left.address(),
						right.address()))
					mcpError(right.method(),
							"Soklet: Potentially overlapping MCP resource URI templates in endpoint %s.",
							endpointBinaryName);
			}
		}

		if (mcpProcessingErrorCount != errorsBefore)
			return null;
		return new McpEndpointModel(packageName,
				endpointType.getQualifiedName().toString(), endpointBinaryName,
				providerSimpleName, providerBinaryName, path, name, version,
				title, description, websiteUrl, instructions, toolRateLimiter,
				resourceListCacheTtlMs, resourceListCacheScope,
				resourceTemplateListCacheTtlMs,
				resourceTemplateListCacheScope, List.copyOf(tools),
				List.copyOf(prompts), List.copyOf(resources), resourceList);
	}

	private McpToolModel validateMcpTool(@NonNull ExecutableElement method,
			@NonNull TypeElement argumentAnnotation,
			@NonNull TypeElement headerAnnotation) {
		int errorsBefore = mcpProcessingErrorCount;
		AnnotationMirror annotation =
				findAnnotation(method, McpTool.class.getCanonicalName());
		if (annotation == null)
			return null;

		if (!method.getModifiers().contains(Modifier.PUBLIC))
			mcpError(method, "Soklet: @McpTool method must be public.");
		if (method.getModifiers().contains(Modifier.STATIC))
			mcpError(method, "Soklet: @McpTool method must not be static.");
		if (method.getModifiers().contains(Modifier.ABSTRACT)
				|| method.getModifiers().contains(Modifier.NATIVE))
			mcpError(method,
					"Soklet: @McpTool method must have a concrete Java implementation.");
		if (!method.getTypeParameters().isEmpty())
			mcpError(method,
					"Soklet: @McpTool method must not declare type parameters.");
		for (TypeMirror thrownType : method.getThrownTypes()) {
			if (!isSubtypeOf(thrownType, exceptionType)
					&& !isSubtypeOf(thrownType, errorType))
				mcpError(method,
						"Soklet: @McpTool method throws types must extend Exception or Error so the generated handler can invoke them.");
		}
		if (method.getReturnType().getKind() == TypeKind.VOID)
			mcpError(method,
					"Soklet: @McpTool method must declare a typed completion return value.");
		String providerPackage = elements.getPackageOf(method)
				.getQualifiedName().toString();
		if (method.getReturnType().getKind() != TypeKind.VOID
				&& !isTypeAccessibleFromGeneratedProvider(
						method.getReturnType(), providerPackage))
			mcpError(method,
					"Soklet: The @McpTool return type must be accessible to the generated MCP endpoint provider.");

		String name = annotationString(annotation, "name");
		String title = annotationString(annotation, "title");
		String description = annotationString(annotation, "description");
		String rateLimiter = annotationString(annotation, "rateLimiter");
		boolean mirrorStructuredContentAsText =
				annotationBoolean(annotation, "mirrorStructuredContentAsText");
		if (name.length() < 1 || name.length() > 128
				|| !name.matches("[A-Za-z0-9_.-]+"))
			mcpError(method,
					"Soklet: MCP tool names must contain 1-128 characters from [A-Za-z0-9_.-].");
		if (!rateLimiter.isEmpty() && rateLimiter.isBlank())
			mcpError(method,
					"Soklet: MCP tool rate-limiter name must not be blank.");

		List<McpParameterBinding> bindings = new ArrayList<>();
		List<McpTypeMirrorTypedSchemaBridge.ToolArgument> schemaArguments =
				new ArrayList<>();
		Set<String> publishedNames = new LinkedHashSet<>();
		boolean requestContextSeen = false;
		boolean invocationFeaturesSeen = false;
		boolean cancelationTokenSeen = false;
		boolean progressReporterSeen = false;
		int toolArgumentIndex = 0;
		for (VariableElement parameter : method.getParameters()) {
			AnnotationMirror argument = findAnnotation(parameter,
					argumentAnnotation);
			AnnotationMirror header = findAnnotation(parameter, headerAnnotation);
			boolean requestContext = isExactType(parameter.asType(),
					mcpRequestContextType);
			boolean invocationFeatures = isExactType(parameter.asType(),
					mcpInvocationFeaturesType);
			boolean cancelationToken = isExactType(parameter.asType(),
					cancelationTokenType);
			boolean progressReporter = isOptionalMcpProgressReporter(
					parameter.asType());
			boolean bareProgressReporter = isExactType(parameter.asType(),
					mcpProgressReporterType);
			if (bareProgressReporter) {
				if (argument != null)
					mcpError(parameter,
							"Soklet: Injectable MCP feature parameters must not also be annotated with @McpToolArgument.");
				mcpError(parameter,
						"Soklet: McpProgressReporter must be injected as Optional<McpProgressReporter>.");
				continue;
			}
			if (!requestContext && !invocationFeatures && !cancelationToken
					&& !progressReporter
					&& !isTypeAccessibleFromGeneratedProvider(parameter.asType(),
							providerPackage))
				mcpError(parameter,
						"Soklet: An @McpTool argument type must be accessible to the generated MCP endpoint provider.");
			if (requestContext || invocationFeatures || cancelationToken
					|| progressReporter) {
				if (argument != null) {
					if (cancelationToken || progressReporter)
						mcpError(parameter,
								"Soklet: Injectable MCP feature parameters must not also be annotated with @McpToolArgument.");
					else
						mcpError(parameter,
								"Soklet: Injectable MCP context parameters must not also be annotated with @McpToolArgument.");
				}
				if (requestContext) {
					if (requestContextSeen)
						mcpError(parameter,
								"Soklet: An @McpTool method may inject McpRequestContext at most once.");
					requestContextSeen = true;
					bindings.add(new McpParameterBinding(
							McpParameterBindingKind.REQUEST_CONTEXT, null, null,
							parameter.asType(), "", "", null));
				} else if (invocationFeatures) {
					if (invocationFeaturesSeen)
						mcpError(parameter,
								"Soklet: An @McpTool method may inject McpInvocationFeatures at most once.");
					invocationFeaturesSeen = true;
					bindings.add(new McpParameterBinding(
							McpParameterBindingKind.INVOCATION_FEATURES, null, null,
							parameter.asType(), "", "", null));
				} else if (cancelationToken) {
					if (cancelationTokenSeen)
						mcpError(parameter,
								"Soklet: An @McpTool method may inject CancelationToken at most once.");
					cancelationTokenSeen = true;
					bindings.add(new McpParameterBinding(
							McpParameterBindingKind.CANCELATION_TOKEN, null, null,
							parameter.asType(), "", "", null));
				} else {
					if (progressReporterSeen)
						mcpError(parameter,
								"Soklet: An @McpTool method may inject Optional<McpProgressReporter> at most once.");
					progressReporterSeen = true;
					bindings.add(new McpParameterBinding(
							McpParameterBindingKind.PROGRESS_REPORTER, null, null,
							parameter.asType(), "", "", null));
				}
				continue;
			}

			if (argument == null) {
				mcpError(parameter,
						"Soklet: Every non-context @McpTool parameter must be annotated with @McpToolArgument.");
				continue;
			}

			String explicitName = annotationString(argument, "name");
			String javaName = parameter.getSimpleName().toString();
			String publishedName = explicitName.isBlank()
					? javaName : explicitName;
			if (!publishedNames.add(publishedName))
				mcpError(parameter,
						"Soklet: Duplicate MCP tool argument name.");
			String argumentTitle = annotationString(argument, "title");
			String argumentDescription = annotationString(argument,
					"description");
			String headerName = header == null ? null
					: annotationString(header, "value");

			bindings.add(new McpParameterBinding(
					McpParameterBindingKind.TOOL_ARGUMENT, publishedName,
					"argument" + toolArgumentIndex++,
					parameter.asType(), argumentTitle, argumentDescription,
					headerName));
			schemaArguments.add(new McpTypeMirrorTypedSchemaBridge.ToolArgument(
					publishedName, parameter.asType(), argumentTitle,
					argumentDescription, Optional.ofNullable(headerName)));
		}

		McpTypeMirrorTypedSchemaBridge.CompiledSchemas compiledSchemas = null;
		if (mcpProcessingErrorCount == errorsBefore) {
			McpTypeMirrorTypedSchemaBridge.Result result;
			try {
				result = McpTypeMirrorTypedSchemaBridge.compileToolSchemas(types,
						elements, schemaArguments, method.getReturnType());
			} catch (RuntimeException exception) {
				mcpError(method,
						"Soklet: Unable to derive deterministic typed schemas for MCP tool '%s'.",
						name);
				result = null;
			}
			if (result instanceof McpTypeMirrorTypedSchemaBridge.RejectedSchemas rejected) {
				McpTypeMirrorTypedSchemaBridge.Diagnostic diagnostic =
						rejected.diagnostic();
				mcpError(method,
						"Soklet: MCP tool '%s' %s schema is unsupported at %s (%s).",
						name,
						diagnostic.direction() == McpTypeMirrorTypedSchemaBridge.Direction.TOOL_INPUT
								? "input" : "output",
						diagnostic.path(), diagnostic.reason());
			} else if (result instanceof McpTypeMirrorTypedSchemaBridge.CompiledSchemas compiled) {
				compiledSchemas = compiled;
			}
		}

		if (mcpProcessingErrorCount != errorsBefore || compiledSchemas == null)
			return null;
		return new McpToolModel(method, name, title, description, rateLimiter,
				mirrorStructuredContentAsText, List.copyOf(bindings),
				sha256Hex(compiledSchemas.getInputSchemaBytes()),
				sha256Hex(compiledSchemas.getOutputSchemaBytes()));
	}

	private McpPromptModel validateMcpPrompt(
			@NonNull ExecutableElement method,
			@NonNull TypeElement argumentAnnotation) {
		int errorsBefore = mcpProcessingErrorCount;
		AnnotationMirror annotation =
				findAnnotation(method, McpPrompt.class.getCanonicalName());
		if (annotation == null)
			return null;

		if (!method.getModifiers().contains(Modifier.PUBLIC))
			mcpError(method, "Soklet: @McpPrompt method must be public.");
		if (method.getModifiers().contains(Modifier.STATIC))
			mcpError(method, "Soklet: @McpPrompt method must not be static.");
		if (method.getModifiers().contains(Modifier.ABSTRACT)
				|| method.getModifiers().contains(Modifier.NATIVE))
			mcpError(method,
					"Soklet: @McpPrompt method must have a concrete Java implementation.");
		if (!method.getTypeParameters().isEmpty())
			mcpError(method,
					"Soklet: @McpPrompt method must not declare type parameters.");
		for (TypeMirror thrownType : method.getThrownTypes()) {
			if (!isSubtypeOf(thrownType, exceptionType)
					&& !isSubtypeOf(thrownType, errorType))
				mcpError(method,
						"Soklet: @McpPrompt method throws types must extend Exception or Error so the generated handler can invoke them.");
		}

		TypeMirror returnType = method.getReturnType();
		boolean promptOutputReturn = mcpPromptOutputType != null
				&& types.isSameType(returnType, mcpPromptOutputType);
		boolean operationResultReturn = isSubtypeOf(returnType,
				mcpOperationResultType);
		if (!promptOutputReturn && !operationResultReturn)
			mcpError(method,
					"Soklet: @McpPrompt method return type must be McpPromptOutput or a subtype of McpOperationResult.");
		String providerPackage = elements.getPackageOf(method)
				.getQualifiedName().toString();
		if (returnType.getKind() != TypeKind.VOID
				&& !isTypeAccessibleFromGeneratedProvider(returnType,
						providerPackage))
			mcpError(method,
					"Soklet: The @McpPrompt return type must be accessible to the generated MCP endpoint provider.");

		String name = annotationString(annotation, "name");
		String title = annotationString(annotation, "title");
		String description = annotationString(annotation, "description");
		if (name.isBlank())
			mcpError(method, "Soklet: MCP prompt name must not be blank.");

		List<McpPromptParameterBinding> bindings = new ArrayList<>();
		Set<String> publishedNames = new LinkedHashSet<>();
		boolean requestContextSeen = false;
		boolean invocationFeaturesSeen = false;
		boolean cancelationTokenSeen = false;
		boolean progressReporterSeen = false;
		for (VariableElement parameter : method.getParameters()) {
			AnnotationMirror argument = findAnnotation(parameter,
					argumentAnnotation);
			boolean requestContext = isExactType(parameter.asType(),
					mcpRequestContextType);
			boolean invocationFeatures = isExactType(parameter.asType(),
					mcpInvocationFeaturesType);
			boolean cancelationToken = isExactType(parameter.asType(),
					cancelationTokenType);
			boolean progressReporter = isOptionalMcpProgressReporter(
					parameter.asType());
			boolean bareProgressReporter = isExactType(parameter.asType(),
					mcpProgressReporterType);
			if (bareProgressReporter) {
				if (argument != null)
					mcpError(parameter,
							"Soklet: Injectable MCP feature parameters must not also be annotated with @McpPromptArgument.");
				mcpError(parameter,
						"Soklet: McpProgressReporter must be injected as Optional<McpProgressReporter>.");
				continue;
			}
			if (requestContext || invocationFeatures || cancelationToken
					|| progressReporter) {
				if (argument != null) {
					if (cancelationToken || progressReporter)
						mcpError(parameter,
								"Soklet: Injectable MCP feature parameters must not also be annotated with @McpPromptArgument.");
					else
						mcpError(parameter,
								"Soklet: Injectable MCP context parameters must not also be annotated with @McpPromptArgument.");
				}
				if (requestContext) {
					if (requestContextSeen)
						mcpError(parameter,
								"Soklet: An @McpPrompt method may inject McpRequestContext at most once.");
					requestContextSeen = true;
					bindings.add(new McpPromptParameterBinding(
							McpPromptParameterBindingKind.REQUEST_CONTEXT, null,
							"", "", false));
				} else if (invocationFeatures) {
					if (invocationFeaturesSeen)
						mcpError(parameter,
								"Soklet: An @McpPrompt method may inject McpInvocationFeatures at most once.");
					invocationFeaturesSeen = true;
					bindings.add(new McpPromptParameterBinding(
							McpPromptParameterBindingKind.INVOCATION_FEATURES,
							null, "", "", false));
				} else if (cancelationToken) {
					if (cancelationTokenSeen)
						mcpError(parameter,
								"Soklet: An @McpPrompt method may inject CancelationToken at most once.");
					cancelationTokenSeen = true;
					bindings.add(new McpPromptParameterBinding(
							McpPromptParameterBindingKind.CANCELATION_TOKEN,
							null, "", "", false));
				} else {
					if (progressReporterSeen)
						mcpError(parameter,
								"Soklet: An @McpPrompt method may inject Optional<McpProgressReporter> at most once.");
					progressReporterSeen = true;
					bindings.add(new McpPromptParameterBinding(
							McpPromptParameterBindingKind.PROGRESS_REPORTER,
							null, "", "", false));
				}
				continue;
			}

			if (argument == null) {
				mcpError(parameter,
						"Soklet: Every non-context @McpPrompt parameter must be annotated with @McpPromptArgument.");
				continue;
			}

			boolean required = stringType != null
					&& types.isSameType(parameter.asType(), stringType);
			if (!required && !isOptionalString(parameter.asType())) {
				mcpError(parameter,
						"Soklet: @McpPromptArgument parameters must be String or Optional<String>.");
				continue;
			}
			String explicitName = annotationString(argument, "name");
			String javaName = parameter.getSimpleName().toString();
			String publishedName = explicitName.isBlank()
					? javaName : explicitName;
			if (!publishedNames.add(publishedName))
				mcpError(parameter,
						"Soklet: Duplicate MCP prompt argument name.");
			String argumentTitle = annotationString(argument, "title");
			String argumentDescription = annotationString(argument,
					"description");
			bindings.add(new McpPromptParameterBinding(
					McpPromptParameterBindingKind.PROMPT_ARGUMENT,
					publishedName, argumentTitle, argumentDescription, required));
		}

		if (mcpProcessingErrorCount != errorsBefore)
			return null;
		return new McpPromptModel(method, name, title, description,
				promptOutputReturn, List.copyOf(bindings));
	}

	private McpResourceModel validateMcpResource(
			@NonNull ExecutableElement method,
			@NonNull TypeElement uriParameterAnnotation) {
		int errorsBefore = mcpProcessingErrorCount;
		AnnotationMirror annotation = findAnnotation(method,
				McpResource.class.getCanonicalName());
		if (annotation == null)
			return null;

		validateConcreteMcpHandlerMethod(method, "@McpResource");
		TypeMirror returnType = method.getReturnType();
		boolean resourceOutputReturn = mcpResourceOutputType != null
				&& types.isSameType(returnType, mcpResourceOutputType);
		boolean operationResultReturn = isSubtypeOf(returnType,
				mcpOperationResultType);
		if (!resourceOutputReturn && !operationResultReturn)
			mcpError(method,
					"Soklet: @McpResource method return type must be McpResourceOutput or a subtype of McpOperationResult.");
		String providerPackage = elements.getPackageOf(method)
				.getQualifiedName().toString();
		if (returnType.getKind() != TypeKind.VOID
				&& !isTypeAccessibleFromGeneratedProvider(returnType,
						providerPackage))
			mcpError(method,
					"Soklet: The @McpResource return type must be accessible to the generated MCP endpoint provider.");

		String address = annotationString(annotation, "uri");
		String name = annotationString(annotation, "name");
		String title = annotationString(annotation, "title");
		String description = annotationString(annotation, "description");
		String mimeType = annotationString(annotation, "mimeType");
		long size = annotationLong(annotation, "size");
		long cacheTtlMs = annotationLong(annotation, "cacheTtlMs");
		String cacheScope = annotationEnumConstantName(annotation, "cacheScope");
		if (address.isBlank())
			mcpError(method,
					"Soklet: MCP resource URI or URI template must not be blank.");
		if (name.isBlank())
			mcpError(method, "Soklet: MCP resource name must not be blank.");
		if (!mimeType.isEmpty() && mimeType.isBlank())
			mcpError(method,
					"Soklet: MCP resource MIME type must not be blank.");
		if (size < -1)
			mcpError(method,
					"Soklet: MCP exact-resource size must be nonnegative or -1 when absent.");
		if (cacheTtlMs < 0)
			mcpError(method,
					"Soklet: MCP resource cache TTL must not be negative.");

		boolean template = address.indexOf('{') >= 0
				|| address.indexOf('}') >= 0;
		List<String> templateVariables = template
				? parseLevelOneTemplateVariables(address) : List.of();
		boolean tooManyTemplateVariables = template
				&& resourceTemplateVariableExpressionCount(address)
				> MAXIMUM_MCP_RESOURCE_URI_TEMPLATE_VARIABLES;
		if (tooManyTemplateVariables)
			mcpError(method,
					"Soklet: An MCP resource URI template may declare at most %d variable expressions.",
					MAXIMUM_MCP_RESOURCE_URI_TEMPLATE_VARIABLES);
		else if (template && templateVariables == null)
			mcpError(method,
					"Soklet: @McpResource uri must be a valid absolute RFC 6570 Level 1 URI template.");
		if (!template) {
			if (!validExactMcpResourceUri(address)) {
				mcpError(method,
						"Soklet: @McpResource uri must be an absolute normalized URI in ASCII RFC 3986 wire form with valid percent triplets.");
			}
		} else if (size >= 0) {
			mcpError(method,
					"Soklet: An MCP resource URI template must not declare size.");
		}

		List<McpResourceParameterBinding> bindings = new ArrayList<>();
		Set<String> boundVariables = new LinkedHashSet<>();
		boolean requestContextSeen = false;
		boolean invocationFeaturesSeen = false;
		boolean resourceReadContextSeen = false;
		boolean cancelationTokenSeen = false;
		boolean progressReporterSeen = false;
		for (VariableElement parameter : method.getParameters()) {
			AnnotationMirror uriParameter = findAnnotation(parameter,
					uriParameterAnnotation);
			boolean requestContext = isExactType(parameter.asType(),
					mcpRequestContextType);
			boolean invocationFeatures = isExactType(parameter.asType(),
					mcpInvocationFeaturesType);
			boolean resourceReadContext = isExactType(parameter.asType(),
					mcpResourceReadContextType);
			boolean cancelationToken = isExactType(parameter.asType(),
					cancelationTokenType);
			boolean progressReporter = isOptionalMcpProgressReporter(
					parameter.asType());
			boolean bareProgressReporter = isExactType(parameter.asType(),
					mcpProgressReporterType);
			if (bareProgressReporter) {
				if (uriParameter != null)
					mcpError(parameter,
							"Soklet: Injectable MCP feature parameters must not also be annotated with @McpResourceUriParameter.");
				mcpError(parameter,
						"Soklet: McpProgressReporter must be injected as Optional<McpProgressReporter>.");
				continue;
			}
			if (requestContext || invocationFeatures || resourceReadContext
					|| cancelationToken || progressReporter) {
				if (uriParameter != null) {
					if (cancelationToken || progressReporter)
						mcpError(parameter,
								"Soklet: Injectable MCP feature parameters must not also be annotated with @McpResourceUriParameter.");
					else
						mcpError(parameter,
								"Soklet: Injectable MCP context parameters must not also be annotated with @McpResourceUriParameter.");
				}
				if (requestContext) {
					if (requestContextSeen)
						mcpError(parameter,
								"Soklet: An @McpResource method may inject McpRequestContext at most once.");
					requestContextSeen = true;
					bindings.add(new McpResourceParameterBinding(
							McpResourceParameterBindingKind.REQUEST_CONTEXT, null));
				} else if (invocationFeatures) {
					if (invocationFeaturesSeen)
						mcpError(parameter,
								"Soklet: An @McpResource method may inject McpInvocationFeatures at most once.");
					invocationFeaturesSeen = true;
					bindings.add(new McpResourceParameterBinding(
							McpResourceParameterBindingKind.INVOCATION_FEATURES,
							null));
				} else if (resourceReadContext) {
					if (resourceReadContextSeen)
						mcpError(parameter,
								"Soklet: An @McpResource method may inject McpResourceReadContext at most once.");
					resourceReadContextSeen = true;
					bindings.add(new McpResourceParameterBinding(
							McpResourceParameterBindingKind.RESOURCE_READ_CONTEXT,
							null));
				} else if (cancelationToken) {
					if (cancelationTokenSeen)
						mcpError(parameter,
								"Soklet: An @McpResource method may inject CancelationToken at most once.");
					cancelationTokenSeen = true;
					bindings.add(new McpResourceParameterBinding(
							McpResourceParameterBindingKind.CANCELATION_TOKEN,
							null));
				} else {
					if (progressReporterSeen)
						mcpError(parameter,
								"Soklet: An @McpResource method may inject Optional<McpProgressReporter> at most once.");
					progressReporterSeen = true;
					bindings.add(new McpResourceParameterBinding(
							McpResourceParameterBindingKind.PROGRESS_REPORTER,
							null));
				}
				continue;
			}

			if (uriParameter == null) {
				mcpError(parameter,
						"Soklet: Every non-context @McpResource parameter must be annotated with @McpResourceUriParameter.");
				continue;
			}
			if (stringType == null
					|| !types.isSameType(parameter.asType(), stringType)) {
				mcpError(parameter,
						"Soklet: @McpResourceUriParameter parameters must be String.");
				continue;
			}
			String explicitName = annotationString(uriParameter, "value");
			String variableName = explicitName.isBlank()
					? parameter.getSimpleName().toString() : explicitName;
			if (!boundVariables.add(variableName))
				mcpError(parameter,
						"Soklet: Duplicate MCP resource URI-template variable binding.");
			bindings.add(new McpResourceParameterBinding(
					McpResourceParameterBindingKind.URI_PARAMETER, variableName));
		}

		if (templateVariables != null) {
			Set<String> declaredVariables = new LinkedHashSet<>(templateVariables);
			if (!template && !boundVariables.isEmpty())
				mcpError(method,
						"Soklet: An exact @McpResource method must not declare URI-template parameters.");
			else if (!declaredVariables.equals(boundVariables))
				mcpError(method,
						"Soklet: Every URI-template variable must be bound exactly once and no undeclared variable may be bound.");
		}

		if (mcpProcessingErrorCount != errorsBefore)
			return null;
		return new McpResourceModel(method, address, template, name, title,
				description, mimeType, size, cacheTtlMs, cacheScope,
				resourceOutputReturn, List.copyOf(bindings));
	}

	private McpResourceListModel validateMcpResourceList(
			@NonNull ExecutableElement method) {
		int errorsBefore = mcpProcessingErrorCount;
		validateConcreteMcpHandlerMethod(method, "@McpResourceList");
		if (mcpResourcePageType == null || !types.isSameType(
				method.getReturnType(), mcpResourcePageType))
			mcpError(method,
					"Soklet: @McpResourceList method return type must be exactly McpResourcePage.");

		List<McpResourceListParameterBinding> bindings = new ArrayList<>();
		boolean requestContextSeen = false;
		boolean invocationFeaturesSeen = false;
		boolean resourceListContextSeen = false;
		boolean cancelationTokenSeen = false;
		boolean progressReporterSeen = false;
		for (VariableElement parameter : method.getParameters()) {
			if (isExactType(parameter.asType(), mcpRequestContextType)) {
				if (requestContextSeen)
					mcpError(parameter,
							"Soklet: An @McpResourceList method may inject McpRequestContext at most once.");
				requestContextSeen = true;
				bindings.add(McpResourceListParameterBinding.REQUEST_CONTEXT);
			} else if (isExactType(parameter.asType(),
					mcpInvocationFeaturesType)) {
				if (invocationFeaturesSeen)
					mcpError(parameter,
							"Soklet: An @McpResourceList method may inject McpInvocationFeatures at most once.");
				invocationFeaturesSeen = true;
				bindings.add(McpResourceListParameterBinding.INVOCATION_FEATURES);
			} else if (isExactType(parameter.asType(), cancelationTokenType)) {
				if (cancelationTokenSeen)
					mcpError(parameter,
							"Soklet: An @McpResourceList method may inject CancelationToken at most once.");
				cancelationTokenSeen = true;
				bindings.add(McpResourceListParameterBinding.CANCELATION_TOKEN);
			} else if (isOptionalMcpProgressReporter(parameter.asType())) {
				if (progressReporterSeen)
					mcpError(parameter,
							"Soklet: An @McpResourceList method may inject Optional<McpProgressReporter> at most once.");
				progressReporterSeen = true;
				bindings.add(McpResourceListParameterBinding.PROGRESS_REPORTER);
			} else if (isExactType(parameter.asType(),
					mcpProgressReporterType)) {
				mcpError(parameter,
						"Soklet: McpProgressReporter must be injected as Optional<McpProgressReporter>.");
			} else if (isExactType(parameter.asType(),
					mcpResourceListContextType)) {
				if (resourceListContextSeen)
					mcpError(parameter,
							"Soklet: An @McpResourceList method must inject McpResourceListContext exactly once.");
				resourceListContextSeen = true;
				bindings.add(McpResourceListParameterBinding.RESOURCE_LIST_CONTEXT);
			} else {
				mcpError(parameter,
						"Soklet: @McpResourceList parameters must be McpRequestContext, McpResourceListContext, McpInvocationFeatures, CancelationToken, or Optional<McpProgressReporter>.");
			}
		}
		if (!resourceListContextSeen)
			mcpError(method,
					"Soklet: An @McpResourceList method must inject McpResourceListContext exactly once.");

		if (mcpProcessingErrorCount != errorsBefore)
			return null;
		return new McpResourceListModel(method, List.copyOf(bindings));
	}

	private void validateConcreteMcpHandlerMethod(
			@NonNull ExecutableElement method, @NonNull String annotationName) {
		if (!method.getModifiers().contains(Modifier.PUBLIC))
			mcpError(method, "Soklet: %s method must be public.", annotationName);
		if (method.getModifiers().contains(Modifier.STATIC))
			mcpError(method, "Soklet: %s method must not be static.",
					annotationName);
		if (method.getModifiers().contains(Modifier.ABSTRACT)
				|| method.getModifiers().contains(Modifier.NATIVE))
			mcpError(method,
					"Soklet: %s method must have a concrete Java implementation.",
					annotationName);
		if (!method.getTypeParameters().isEmpty())
			mcpError(method,
					"Soklet: %s method must not declare type parameters.",
					annotationName);
		for (TypeMirror thrownType : method.getThrownTypes())
			if (!isSubtypeOf(thrownType, exceptionType)
					&& !isSubtypeOf(thrownType, errorType))
				mcpError(method,
						"Soklet: %s method throws types must extend Exception or Error so the generated handler can invoke them.",
						annotationName);
	}

	private static List<String> parseLevelOneTemplateVariables(
			@NonNull String template) {
		McpLevelOneResourceTemplate parsed = parseLevelOneResourceTemplate(
				template);
		return parsed == null ? null : parsed.variables();
	}

	private static McpLevelOneResourceTemplate parseLevelOneResourceTemplate(
			@NonNull String template) {
		List<String> variables = new ArrayList<>();
		List<McpTemplateOverlapAtom> overlapAtoms = new ArrayList<>();
		StringBuilder uriCandidate = new StringBuilder(template.length() + 16);
		boolean previousWasVariable = false;
		for (int index = 0; index < template.length();) {
			char character = template.charAt(index);
			if (character == '}')
				return null;
			if (character != '{') {
				McpTemplateLiteralToken token = templateLiteralToken(template,
						index);
				if (token == null)
					return null;
				uriCandidate.append(token.value());
				overlapAtoms.add(new McpTemplateOverlapAtom(token.value(),
						false, token.variableConsumable()));
				previousWasVariable = false;
				index += token.sourceLength();
				continue;
			}
			if (previousWasVariable)
				return null;
			int close = template.indexOf('}', index + 1);
			if (close < 0 || template.indexOf('{', index + 1) >= 0
					&& template.indexOf('{', index + 1) < close)
				return null;
			String variable = template.substring(index + 1, close);
			if (!validLevelOneVariableName(variable)
					|| variables.contains(variable)
					|| variables.size()
					>= MAXIMUM_MCP_RESOURCE_URI_TEMPLATE_VARIABLES)
				return null;
			variables.add(variable);
			uriCandidate.append('x');
			overlapAtoms.add(McpTemplateOverlapAtom.wildcardAtom());
			previousWasVariable = true;
			index = close + 1;
		}
		if (variables.isEmpty())
			return null;
		if (!validExactMcpResourceUri(uriCandidate.toString()))
			return null;
		return new McpLevelOneResourceTemplate(List.copyOf(variables),
				List.copyOf(overlapAtoms));
	}

	private static int resourceTemplateVariableExpressionCount(
			@NonNull String template) {
		int count = 0;
		for (int index = 0; index < template.length(); ++index)
			if (template.charAt(index) == '{')
				++count;
		return count;
	}

	private static boolean validExactMcpResourceUri(@NonNull String address) {
		if (address.isEmpty())
			return false;
		for (int index = 0; index < address.length();) {
			char character = address.charAt(index);
			if (character == '%') {
				if (index + 2 >= address.length()
						|| hexadecimal(address.charAt(index + 1)) < 0
						|| hexadecimal(address.charAt(index + 2)) < 0)
					return false;
				index += 3;
				continue;
			}
			if (!rfc3986AsciiCharacter(character))
				return false;
			++index;
		}
		try {
			URI uri = URI.create(address);
			return uri.isAbsolute() && uri.equals(uri.normalize());
		} catch (IllegalArgumentException exception) {
			return false;
		}
	}

	private static boolean rfc3986AsciiCharacter(char character) {
		return asciiLetterOrDigit(character)
				|| "-._~:/?#[]@!$&'()*+,;=".indexOf(character) >= 0;
	}

	private static McpTemplateLiteralToken templateLiteralToken(
			@NonNull String template, int index) {
		char character = template.charAt(index);
		if (character == '%')
			return percentEncodedTemplateLiteralToken(template, index);
		if (character < 128) {
			if (!rfc6570LiteralAscii(character))
				return null;
			return new McpTemplateLiteralToken(String.valueOf(character),
					levelOneUnreserved(character), 1);
		}

		int codePoint = template.codePointAt(index);
		if (!rfc6570UnicodeLiteral(codePoint))
			return null;
		return new McpTemplateLiteralToken(percentEncodeCodePoint(codePoint),
				true, Character.charCount(codePoint));
	}

	private static McpTemplateLiteralToken percentEncodedTemplateLiteralToken(
			@NonNull String template, int index) {
		int firstByte = percentEncodedByte(template, index);
		if (firstByte < 0)
			return null;
		int encodedByteCount = utf8EncodedByteCount(firstByte);
		if (encodedByteCount > 1 && validUtf8PercentSequence(template, index,
				encodedByteCount))
			return new McpTemplateLiteralToken(uppercasePercentTriplets(template,
					index, encodedByteCount), true, encodedByteCount * 3);

		String value = uppercasePercentTriplets(template, index, 1);
		boolean variableConsumable = firstByte <= 127
				&& !levelOneUnreserved((char) firstByte);
		return new McpTemplateLiteralToken(value, variableConsumable, 3);
	}

	private static int utf8EncodedByteCount(int firstByte) {
		if (firstByte >= 0xC2 && firstByte <= 0xDF)
			return 2;
		if (firstByte >= 0xE0 && firstByte <= 0xEF)
			return 3;
		if (firstByte >= 0xF0 && firstByte <= 0xF4)
			return 4;
		return 1;
	}

	private static boolean validUtf8PercentSequence(@NonNull String value,
			int index, int byteCount) {
		if (index + byteCount * 3 > value.length())
			return false;
		int firstByte = percentEncodedByte(value, index);
		int secondByte = percentEncodedByte(value, index + 3);
		if (secondByte < 0x80 || secondByte > 0xBF)
			return false;
		if (firstByte == 0xE0 && secondByte < 0xA0
				|| firstByte == 0xED && secondByte > 0x9F
				|| firstByte == 0xF0 && secondByte < 0x90
				|| firstByte == 0xF4 && secondByte > 0x8F)
			return false;
		for (int byteIndex = 2; byteIndex < byteCount; ++byteIndex) {
			int continuation = percentEncodedByte(value,
					index + byteIndex * 3);
			if (continuation < 0x80 || continuation > 0xBF)
				return false;
		}
		return true;
	}

	private static int percentEncodedByte(@NonNull String value,
			int percentIndex) {
		if (percentIndex + 2 >= value.length()
				|| value.charAt(percentIndex) != '%')
			return -1;
		int high = hexadecimal(value.charAt(percentIndex + 1));
		int low = hexadecimal(value.charAt(percentIndex + 2));
		return high < 0 || low < 0 ? -1 : high << 4 | low;
	}

	@NonNull
	private static String uppercasePercentTriplets(@NonNull String value,
			int index, int byteCount) {
		StringBuilder normalized = new StringBuilder(byteCount * 3);
		for (int byteIndex = 0; byteIndex < byteCount; ++byteIndex) {
			int encodedByte = percentEncodedByte(value, index + byteIndex * 3);
			normalized.append('%').append(uppercaseHexadecimal(encodedByte >> 4))
					.append(uppercaseHexadecimal(encodedByte & 15));
		}
		return normalized.toString();
	}

	@NonNull
	private static String percentEncodeCodePoint(int codePoint) {
		byte[] bytes = new String(Character.toChars(codePoint))
				.getBytes(StandardCharsets.UTF_8);
		StringBuilder encoded = new StringBuilder(bytes.length * 3);
		for (byte value : bytes) {
			int unsigned = Byte.toUnsignedInt(value);
			encoded.append('%').append(uppercaseHexadecimal(unsigned >> 4))
					.append(uppercaseHexadecimal(unsigned & 15));
		}
		return encoded.toString();
	}

	private static char uppercaseHexadecimal(int value) {
		return (char) (value < 10 ? '0' + value : 'A' + value - 10);
	}

	private static boolean rfc6570LiteralAscii(char character) {
		return character == 0x21 || character >= 0x23 && character <= 0x24
				|| character == 0x26
				|| character >= 0x28 && character <= 0x3B
				|| character == 0x3D
				|| character >= 0x3F && character <= 0x5B
				|| character == 0x5D || character == 0x5F
				|| character >= 0x61 && character <= 0x7A
				|| character == 0x7E;
	}

	private static boolean rfc6570UnicodeLiteral(int codePoint) {
		if (codePoint >= 0xA0 && codePoint <= 0xD7FF
				|| codePoint >= 0xE000 && codePoint <= 0xFDCF
				|| codePoint >= 0xFDF0 && codePoint <= 0xFFEF)
			return true;
		return codePoint >= 0x10000 && codePoint <= 0x10FFFD
				&& (codePoint & 0xFFFF) <= 0xFFFD;
	}

	private static boolean levelOneUnreserved(char character) {
		return asciiLetterOrDigit(character) || character == '-'
				|| character == '.' || character == '_'
				|| character == '~';
	}

	private static boolean validLevelOneVariableName(@NonNull String name) {
		if (name.isEmpty())
			return false;
		boolean segmentHasCharacter = false;
		for (int index = 0; index < name.length();) {
			char character = name.charAt(index);
			if (character == '.') {
				if (!segmentHasCharacter)
					return false;
				segmentHasCharacter = false;
				++index;
				continue;
			}
			if (asciiLetterOrDigit(character) || character == '_') {
				segmentHasCharacter = true;
				++index;
				continue;
			}
			if (character == '%' && index + 2 < name.length()
					&& hexadecimal(name.charAt(index + 1)) >= 0
					&& hexadecimal(name.charAt(index + 2)) >= 0) {
				segmentHasCharacter = true;
				index += 3;
				continue;
			}
			return false;
		}
		return segmentHasCharacter;
	}

	private static boolean asciiLetterOrDigit(char character) {
		return character >= 'A' && character <= 'Z'
				|| character >= 'a' && character <= 'z'
				|| character >= '0' && character <= '9';
	}

	private static int hexadecimal(char character) {
		if (character >= '0' && character <= '9')
			return character - '0';
		if (character >= 'A' && character <= 'F')
			return character - 'A' + 10;
		if (character >= 'a' && character <= 'f')
			return character - 'a' + 10;
		return -1;
	}

	private static boolean resourceTemplatesPotentiallyOverlap(
			@NonNull String leftTemplate, @NonNull String rightTemplate) {
		McpLevelOneResourceTemplate leftParsed =
				parseLevelOneResourceTemplate(leftTemplate);
		McpLevelOneResourceTemplate rightParsed =
				parseLevelOneResourceTemplate(rightTemplate);
		if (leftParsed == null || rightParsed == null)
			return false;
		List<McpTemplateOverlapAtom> leftAtoms = leftParsed.overlapAtoms();
		List<McpTemplateOverlapAtom> rightAtoms = rightParsed.overlapAtoms();
		ArrayDeque<McpTemplateOverlapState> pending = new ArrayDeque<>();
		Set<McpTemplateOverlapState> visited = new LinkedHashSet<>();
		pending.add(new McpTemplateOverlapState(0, 0));

		while (!pending.isEmpty()) {
			McpTemplateOverlapState state = pending.removeFirst();
			if (!visited.add(state))
				continue;
			int left = state.leftIndex();
			int right = state.rightIndex();
			if (left == leftAtoms.size() && right == rightAtoms.size())
				return true;
			if (left == leftAtoms.size()) {
				if (onlyTemplateWildcardsRemain(rightAtoms, right))
					return true;
				continue;
			}
			if (right == rightAtoms.size()) {
				if (onlyTemplateWildcardsRemain(leftAtoms, left))
					return true;
				continue;
			}

			McpTemplateOverlapAtom leftAtom = leftAtoms.get(left);
			McpTemplateOverlapAtom rightAtom = rightAtoms.get(right);
			boolean leftWildcard = leftAtom.wildcard();
			boolean rightWildcard = rightAtom.wildcard();
			if (leftWildcard) {
				pending.add(new McpTemplateOverlapState(left + 1, right));
				if (!rightWildcard && rightAtom.variableConsumable())
					pending.add(new McpTemplateOverlapState(left, right + 1));
			}
			if (rightWildcard) {
				pending.add(new McpTemplateOverlapState(left, right + 1));
				if (!leftWildcard && leftAtom.variableConsumable())
					pending.add(new McpTemplateOverlapState(left + 1, right));
			}
			if (!leftWildcard && !rightWildcard
					&& leftAtom.value().equals(rightAtom.value()))
				pending.add(new McpTemplateOverlapState(left + 1, right + 1));
		}
		return false;
	}

	private static boolean onlyTemplateWildcardsRemain(
			@NonNull List<@NonNull McpTemplateOverlapAtom> atoms, int index) {
		for (; index < atoms.size(); ++index)
			if (!atoms.get(index).wildcard())
				return false;
		return true;
	}

	private boolean isOptionalString(@NonNull TypeMirror type) {
		if (!(type instanceof DeclaredType declared) || optionalType == null
				|| !types.isSameType(types.erasure(declared),
						types.erasure(optionalType))
				|| declared.getTypeArguments().size() != 1)
			return false;
		return stringType != null && types.isSameType(
				declared.getTypeArguments().get(0), stringType);
	}

	private boolean isOptionalMcpProgressReporter(@NonNull TypeMirror type) {
		if (!(type instanceof DeclaredType declared) || optionalType == null
				|| mcpProgressReporterType == null
				|| !types.isSameType(types.erasure(declared),
						types.erasure(optionalType))
				|| declared.getTypeArguments().size() != 1)
			return false;
		return types.isSameType(declared.getTypeArguments().get(0),
				mcpProgressReporterType);
	}

	private boolean isTypeAccessibleFromGeneratedProvider(
			@NonNull TypeMirror type, @NonNull String providerPackage) {
		if (type.getKind().isPrimitive() || type.getKind() == TypeKind.VOID
				|| type.getKind() == TypeKind.ERROR
				|| type.getKind() == TypeKind.TYPEVAR
				|| type.getKind() == TypeKind.WILDCARD)
			return true;
		if (type instanceof ArrayType array)
			return isTypeAccessibleFromGeneratedProvider(array.getComponentType(),
					providerPackage);
		if (!(type instanceof DeclaredType declared)
				|| !(declared.asElement() instanceof TypeElement declaration))
			return false;

		boolean samePackage = elements.getPackageOf(declaration)
				.getQualifiedName().contentEquals(providerPackage);
		for (Element current = declaration;
				current instanceof TypeElement currentType;
				current = current.getEnclosingElement()) {
			Set<Modifier> modifiers = currentType.getModifiers();
			if (modifiers.contains(Modifier.PRIVATE)
					|| !samePackage && !modifiers.contains(Modifier.PUBLIC))
				return false;
		}
		TypeMirror enclosing = declared.getEnclosingType();
		if (enclosing.getKind() != TypeKind.NONE
				&& !isTypeAccessibleFromGeneratedProvider(enclosing,
						providerPackage))
			return false;
		for (TypeMirror argument : declared.getTypeArguments())
			if (!isTypeAccessibleFromGeneratedProvider(argument,
					providerPackage))
				return false;
		return true;
	}

	private boolean generateMcpEndpointProvider(
			@NonNull McpEndpointModel endpoint,
			@NonNull TypeElement originatingElement) {
		StringBuilder source = new StringBuilder(8192);
		if (!endpoint.packageName().isEmpty())
			source.append("package ").append(endpoint.packageName())
					.append(";\n\n");
		source.append("/** Generated by SokletProcessor. */\n")
				.append("public final class ")
				.append(endpoint.providerSimpleName()).append(" {\n")
				.append("\tpublic ").append(endpoint.providerSimpleName())
				.append("() {\n\t}\n\n")
				.append("\tpublic Class<?> endpointClass() {\n")
				.append("\t\treturn ").append(endpoint.endpointQualifiedName())
				.append(".class;\n\t}\n\n")
				.append("\tpublic String[] schemaDigests() {\n")
				.append("\t\treturn new String[] {");
		for (int index = 0; index < endpoint.tools().size(); ++index) {
			McpToolModel tool = endpoint.tools().get(index);
			if (index > 0)
				source.append(", ");
			source.append(javaStringLiteral(tool.name())).append(", ")
					.append(javaStringLiteral(tool.inputSchemaDigest()))
					.append(", ")
					.append(javaStringLiteral(tool.outputSchemaDigest()));
		}
		source.append("};\n\t}\n\n")
				.append("\tpublic com.soklet.McpEndpoint endpoint(\n")
				.append("\t\t\tcom.soklet.InstanceProvider instanceProvider) {\n")
				.append("\t\tjava.util.Objects.requireNonNull(instanceProvider);\n")
				.append("\t\tvar implementationBuilder = ")
				.append("com.soklet.McpImplementation.withNameAndVersion(")
				.append(javaStringLiteral(endpoint.name())).append(", ")
				.append(javaStringLiteral(endpoint.version())).append(");\n");
		appendOptionalBuilderCall(source, "implementationBuilder", "title",
				endpoint.title());
		appendOptionalBuilderCall(source, "implementationBuilder",
				"description", endpoint.description());
		if (!endpoint.websiteUrl().isBlank())
			source.append("\t\timplementationBuilder.websiteUrl(java.net.URI.create(")
					.append(javaStringLiteral(endpoint.websiteUrl()))
					.append("));\n");
		source.append("\t\tvar endpointBuilder = com.soklet.McpEndpoint.withPath(")
				.append(javaStringLiteral(endpoint.path()))
				.append(").serverInformation(implementationBuilder.build());\n");
		appendOptionalBuilderCall(source, "endpointBuilder", "instructions",
				endpoint.instructions());
		appendOptionalBuilderCall(source, "endpointBuilder", "toolRateLimiterName",
				endpoint.toolRateLimiter());
		if (endpoint.resourceListCacheTtlMs() != 0
				|| !"PRIVATE".equals(endpoint.resourceListCacheScope()))
			source.append("\t\tendpointBuilder.resourceListCachePolicy(")
					.append(cachePolicyExpression(
							endpoint.resourceListCacheTtlMs(),
							endpoint.resourceListCacheScope()))
					.append(");\n");
		if (endpoint.resourceTemplateListCacheTtlMs() != 0
				|| !"PRIVATE".equals(
						endpoint.resourceTemplateListCacheScope()))
			source.append("\t\tendpointBuilder.resourceTemplateListCachePolicy(")
					.append(cachePolicyExpression(
							endpoint.resourceTemplateListCacheTtlMs(),
							endpoint.resourceTemplateListCacheScope()))
					.append(");\n");

		for (int index = 0; index < endpoint.tools().size(); ++index) {
			McpToolModel tool = endpoint.tools().get(index);
			String carrierName = "Tool" + index + "Arguments";
			source.append("\t\tvar toolBuilder").append(index)
					.append(" = com.soklet.McpToolRegistration.withName(")
					.append(javaStringLiteral(tool.name())).append(")\n")
					.append("\t\t\t\t.types(").append(carrierName)
					.append(".class, ")
					.append(resultTypeExpression(tool.method().getReturnType()))
					.append(")\n")
					.append("\t\t\t\t.handler((request, arguments, features) -> ")
					.append("instanceProvider.provide(")
					.append(endpoint.endpointQualifiedName()).append(".class).")
					.append(tool.method().getSimpleName()).append('(')
					.append(invocationArguments(tool.bindings()))
					.append("));\n");
			appendOptionalBuilderCall(source, "toolBuilder" + index, "title",
					tool.title());
			appendOptionalBuilderCall(source, "toolBuilder" + index,
					"description", tool.description());
			appendOptionalBuilderCall(source, "toolBuilder" + index,
					"rateLimiterName", tool.rateLimiter());
			source.append("\t\ttoolBuilder").append(index)
					.append(".mirrorStructuredContentAsText(")
					.append(tool.mirrorStructuredContentAsText()).append(");\n")
					.append("\t\tendpointBuilder.tool(toolBuilder")
					.append(index).append(".build());\n");
		}

		for (int index = 0; index < endpoint.prompts().size(); ++index) {
			McpPromptModel prompt = endpoint.prompts().get(index);
			source.append("\t\tvar promptBuilder").append(index)
					.append(" = com.soklet.McpPromptRegistration.withName(")
					.append(javaStringLiteral(prompt.name())).append(")\n")
					.append("\t\t\t\t.handler((request, prompt, features) -> ");
			if (prompt.promptOutputReturn())
				source.append("com.soklet.McpCompleteResult.fromPromptOutput(");
			source.append("instanceProvider.provide(")
					.append(endpoint.endpointQualifiedName()).append(".class).")
					.append(prompt.method().getSimpleName()).append('(')
					.append(promptInvocationArguments(prompt.bindings()))
					.append(')');
			if (prompt.promptOutputReturn())
				source.append(')');
			source.append(");\n");
			appendOptionalBuilderCall(source, "promptBuilder" + index, "title",
					prompt.title());
			appendOptionalBuilderCall(source, "promptBuilder" + index,
					"description", prompt.description());
			int argumentIndex = 0;
			for (McpPromptParameterBinding binding : prompt.bindings()) {
				if (binding.kind()
						!= McpPromptParameterBindingKind.PROMPT_ARGUMENT)
					continue;
				String argumentBuilder = "promptArgumentBuilder" + index + "_"
						+ argumentIndex++;
				source.append("\t\tvar ").append(argumentBuilder)
						.append(" = com.soklet.McpPromptArgumentDefinition.withName(")
						.append(javaStringLiteral(binding.publishedName()))
						.append(");\n");
				appendOptionalBuilderCall(source, argumentBuilder, "title",
						binding.title());
				appendOptionalBuilderCall(source, argumentBuilder, "description",
						binding.description());
				source.append("\t\t").append(argumentBuilder).append(".required(")
						.append(binding.required()).append(");\n")
						.append("\t\tpromptBuilder").append(index)
						.append(".argument(").append(argumentBuilder)
						.append(".build());\n");
			}
			source.append("\t\tendpointBuilder.prompt(promptBuilder")
					.append(index).append(".build());\n");
		}

		for (int index = 0; index < endpoint.resources().size(); ++index) {
			McpResourceModel resource = endpoint.resources().get(index);
			source.append("\t\tvar resourceBuilder").append(index)
					.append(" = com.soklet.McpResourceRegistration.");
			if (resource.template())
				source.append("withUriTemplateAndName(")
						.append(javaStringLiteral(resource.address()));
			else
				source.append("withUriAndName(java.net.URI.create(")
						.append(javaStringLiteral(resource.address()))
						.append(')');
			source.append(", ").append(javaStringLiteral(resource.name()))
					.append(")\n")
					.append("\t\t\t\t.handler((request, resource, features) -> ");
			if (resource.resourceOutputReturn())
				source.append("com.soklet.McpCompleteResult.fromResourceOutput(");
			source.append("instanceProvider.provide(")
					.append(endpoint.endpointQualifiedName()).append(".class).")
					.append(resource.method().getSimpleName()).append('(')
					.append(resourceInvocationArguments(resource.bindings()))
					.append(')');
			if (resource.resourceOutputReturn())
				source.append(')');
			source.append(");\n");
			appendOptionalBuilderCall(source, "resourceBuilder" + index, "title",
					resource.title());
			appendOptionalBuilderCall(source, "resourceBuilder" + index,
					"description", resource.description());
			appendOptionalBuilderCall(source, "resourceBuilder" + index,
					"mimeType", resource.mimeType());
			if (!resource.template() && resource.size() >= 0)
				source.append("\t\tresourceBuilder").append(index)
						.append(".size(").append(resource.size()).append("L);\n");
			if (resource.cacheTtlMs() != 0
					|| !"PRIVATE".equals(resource.cacheScope()))
				source.append("\t\tresourceBuilder").append(index)
						.append(".cachePolicy(")
						.append(cachePolicyExpression(resource.cacheTtlMs(),
								resource.cacheScope()))
						.append(");\n");
			source.append("\t\tendpointBuilder.resource(resourceBuilder")
					.append(index).append(".build());\n");
		}

		if (endpoint.resourceList() != null) {
			McpResourceListModel resourceList = endpoint.resourceList();
			source.append("\t\tendpointBuilder.resourceListHandler(")
					.append("(request, list, features) -> instanceProvider.provide(")
					.append(endpoint.endpointQualifiedName()).append(".class).")
					.append(resourceList.method().getSimpleName()).append('(')
					.append(resourceListInvocationArguments(
							resourceList.bindings()))
					.append("));\n");
		}
		source.append("\t\treturn endpointBuilder.build();\n\t}\n");

		for (int index = 0; index < endpoint.tools().size(); ++index) {
			McpToolModel tool = endpoint.tools().get(index);
			source.append("\n\tpublic record Tool").append(index)
					.append("Arguments(");
			boolean first = true;
			for (McpParameterBinding binding : tool.bindings()) {
				if (binding.kind() != McpParameterBindingKind.TOOL_ARGUMENT)
					continue;
				if (!first)
					source.append(", ");
				first = false;
				source.append("@com.soklet.annotation.McpToolProperty(name = ")
						.append(javaStringLiteral(binding.publishedName()))
						.append(", title = ")
						.append(javaStringLiteral(binding.title()))
						.append(", description = ")
						.append(javaStringLiteral(binding.description()))
						.append(") ");
				if (binding.headerName() != null)
					source.append("@com.soklet.annotation.McpHeader(")
							.append(javaStringLiteral(binding.headerName()))
							.append(") ");
				source
						.append(mcpSourceType(binding.type())).append(' ')
						.append(binding.carrierName());
			}
			source.append(") {\n\t}\n");
		}
		source.append("}\n");

		try {
			JavaFileObject sourceFile = filer.createSourceFile(
					endpoint.providerBinaryName(), originatingElement);
			try (Writer writer = sourceFile.openWriter()) {
				writer.write(source.toString());
			}
			return true;
		} catch (IOException exception) {
			mcpError(originatingElement,
					"Soklet: Unable to generate MCP endpoint provider %s (%s).",
					endpoint.providerBinaryName(),
					exception.getClass().getSimpleName());
			return false;
		}
	}

	private static void appendOptionalBuilderCall(@NonNull StringBuilder source,
			@NonNull String builder, @NonNull String method,
			@NonNull String value) {
		if (!value.isBlank())
			source.append("\t\t").append(builder).append('.').append(method)
					.append('(').append(javaStringLiteral(value))
					.append(");\n");
	}

	@NonNull
	private static String invocationArguments(
			@NonNull List<McpParameterBinding> bindings) {
		List<String> arguments = new ArrayList<>(bindings.size());
		for (McpParameterBinding binding : bindings) {
			arguments.add(switch (binding.kind()) {
				case REQUEST_CONTEXT -> "request";
				case INVOCATION_FEATURES -> "features";
				case CANCELATION_TOKEN ->
						"features.require(com.soklet.CancelationToken.class)";
				case PROGRESS_REPORTER ->
						"features.find(com.soklet.McpProgressReporter.class)";
				case TOOL_ARGUMENT -> "arguments.getConvertedArguments()."
						+ binding.carrierName() + "()";
			});
		}
		return String.join(", ", arguments);
	}

	@NonNull
	private static String promptInvocationArguments(
			@NonNull List<McpPromptParameterBinding> bindings) {
		List<String> arguments = new ArrayList<>(bindings.size());
		for (McpPromptParameterBinding binding : bindings) {
			arguments.add(switch (binding.kind()) {
				case REQUEST_CONTEXT -> "request";
				case INVOCATION_FEATURES -> "features";
				case CANCELATION_TOKEN ->
						"features.require(com.soklet.CancelationToken.class)";
				case PROGRESS_REPORTER ->
						"features.find(com.soklet.McpProgressReporter.class)";
				case PROMPT_ARGUMENT -> binding.required()
						? "prompt.findArgument("
								+ javaStringLiteral(binding.publishedName())
								+ ").orElseThrow()"
						: "prompt.findArgument("
								+ javaStringLiteral(binding.publishedName()) + ")";
			});
		}
		return String.join(", ", arguments);
	}

	@NonNull
	private static String resourceInvocationArguments(
			@NonNull List<McpResourceParameterBinding> bindings) {
		List<String> arguments = new ArrayList<>(bindings.size());
		for (McpResourceParameterBinding binding : bindings) {
			arguments.add(switch (binding.kind()) {
				case REQUEST_CONTEXT -> "request";
				case INVOCATION_FEATURES -> "features";
				case CANCELATION_TOKEN ->
						"features.require(com.soklet.CancelationToken.class)";
				case PROGRESS_REPORTER ->
						"features.find(com.soklet.McpProgressReporter.class)";
				case RESOURCE_READ_CONTEXT -> "resource";
				case URI_PARAMETER -> "java.util.Objects.requireNonNull("
						+ "resource.getUriTemplateVariables().get("
						+ javaStringLiteral(binding.variableName()) + "))";
			});
		}
		return String.join(", ", arguments);
	}

	@NonNull
	private static String resourceListInvocationArguments(
			@NonNull List<McpResourceListParameterBinding> bindings) {
		List<String> arguments = new ArrayList<>(bindings.size());
		for (McpResourceListParameterBinding binding : bindings) {
			arguments.add(switch (binding) {
				case REQUEST_CONTEXT -> "request";
				case INVOCATION_FEATURES -> "features";
				case CANCELATION_TOKEN ->
						"features.require(com.soklet.CancelationToken.class)";
				case PROGRESS_REPORTER ->
						"features.find(com.soklet.McpProgressReporter.class)";
				case RESOURCE_LIST_CONTEXT -> "list";
			});
		}
		return String.join(", ", arguments);
	}

	@NonNull
	private static String cachePolicyExpression(long timeToLiveMs,
			@NonNull String scope) {
		String factory = switch (scope) {
			case "PRIVATE" -> "fromPrivateTimeToLive";
			case "PUBLIC" -> "fromPublicTimeToLive";
			default -> throw new IllegalArgumentException(
					"Unsupported MCP cache scope: " + scope);
		};
		return "com.soklet.McpCachePolicy." + factory
				+ "(java.time.Duration.ofMillis(" + timeToLiveMs + "L))";
	}

	@NonNull
	private static String resultTypeExpression(@NonNull TypeMirror type) {
		if (type.getKind().isPrimitive())
			return mcpSourceType(type) + ".class";
		return "new com.soklet.converter.TypeReference<" + mcpSourceType(type)
				+ ">() {}";
	}

	@NonNull
	private static String mcpSourceType(@NonNull TypeMirror type) {
		if (type instanceof ArrayType array)
			return mcpSourceType(array.getComponentType()) + "[]";
		if (type instanceof DeclaredType declared
				&& declared.asElement() instanceof TypeElement declaration) {
			StringBuilder sourceType = new StringBuilder(
					declaration.getQualifiedName());
			if (!declared.getTypeArguments().isEmpty()) {
				sourceType.append('<');
				for (int index = 0;
						index < declared.getTypeArguments().size(); ++index) {
					if (index > 0)
						sourceType.append(", ");
					sourceType.append(mcpSourceType(
							declared.getTypeArguments().get(index)));
				}
				sourceType.append('>');
			}
			return sourceType.toString();
		}
		return switch (type.getKind()) {
			case BOOLEAN -> "boolean";
			case BYTE -> "byte";
			case SHORT -> "short";
			case INT -> "int";
			case LONG -> "long";
			case CHAR -> "char";
			case FLOAT -> "float";
			case DOUBLE -> "double";
			case VOID -> "void";
			default -> type.toString();
		};
	}

	private boolean isExactType(@NonNull TypeMirror candidate,
			TypeMirror expected) {
		return expected != null && types.isSameType(types.erasure(candidate),
				types.erasure(expected));
	}

	@NonNull
	private String topLevelBinaryName(@NonNull TypeElement type) {
		TypeElement topLevel = type;
		for (Element enclosing = type.getEnclosingElement();
				enclosing instanceof TypeElement enclosingType;
				enclosing = enclosing.getEnclosingElement())
			topLevel = enclosingType;
		return elements.getBinaryName(topLevel).toString();
	}

	private boolean isSubtypeOf(@NonNull TypeMirror candidate,
			TypeMirror expected) {
		return expected != null && types.isSubtype(types.erasure(candidate),
				types.erasure(expected));
	}

	private AnnotationMirror findAnnotation(@NonNull Element element,
			@NonNull String annotationTypeName) {
		for (AnnotationMirror annotation : element.getAnnotationMirrors()) {
			Element annotationElement = annotation.getAnnotationType().asElement();
			if (annotationElement instanceof TypeElement annotationType
					&& annotationType.getQualifiedName().contentEquals(
							annotationTypeName))
				return annotation;
		}
		return null;
	}

	private AnnotationMirror findAnnotation(@NonNull Element element,
			@NonNull TypeElement annotationType) {
		for (AnnotationMirror annotation : element.getAnnotationMirrors())
			if (isAnnotationType(annotation, annotationType))
				return annotation;
		return null;
	}

	@NonNull
	private String annotationString(@NonNull AnnotationMirror annotation,
			@NonNull String member) {
		Object value = annotationMemberWithDefaults(annotation, member);
		return value == null ? "" : value.toString();
	}

	private boolean annotationBoolean(@NonNull AnnotationMirror annotation,
			@NonNull String member) {
		Object value = annotationMemberWithDefaults(annotation, member);
		return value instanceof Boolean bool && bool;
	}

	private long annotationLong(@NonNull AnnotationMirror annotation,
			@NonNull String member) {
		Object value = annotationMemberWithDefaults(annotation, member);
		return value instanceof Long number ? number : 0;
	}

	@NonNull
	private String annotationEnumConstantName(
			@NonNull AnnotationMirror annotation, @NonNull String member) {
		Object value = annotationMemberWithDefaults(annotation, member);
		if (value instanceof VariableElement constant)
			return constant.getSimpleName().toString();
		return value == null ? "" : value.toString();
	}

	private Object annotationMemberWithDefaults(
			@NonNull AnnotationMirror annotation, @NonNull String member) {
		for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue>
				entry : elements.getElementValuesWithDefaults(annotation).entrySet())
			if (entry.getKey().getSimpleName().contentEquals(member))
				return entry.getValue().getValue();
		return null;
	}

	@NonNull
	private static String javaStringLiteral(@NonNull String value) {
		StringBuilder literal = new StringBuilder(value.length() + 16);
		literal.append('"');
		for (int index = 0; index < value.length(); ++index) {
			char character = value.charAt(index);
			switch (character) {
				case '\\' -> literal.append("\\\\");
				case '"' -> literal.append("\\\"");
				case '\b' -> literal.append("\\b");
				case '\t' -> literal.append("\\t");
				case '\n' -> literal.append("\\n");
				case '\f' -> literal.append("\\f");
				case '\r' -> literal.append("\\r");
				default -> {
					int characterType = Character.getType(character);
					if (characterType == Character.FORMAT
							|| characterType == Character.LINE_SEPARATOR
							|| characterType == Character.PARAGRAPH_SEPARATOR
							|| characterType == Character.SURROGATE)
						literal.append(String.format(Locale.ROOT, "\\u%04X",
								(int) character));
					else if (character < 32
							|| character >= 127 && character <= 159)
						literal.append('\\').append(String.format("%03o",
								(int) character));
					else
						literal.append(character);
				}
			}
		}
		return literal.append('"').toString();
	}

	@NonNull
	private static String sha256Hex(byte @NonNull [] bytes) {
		try {
			return toHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 must be available.", exception);
		}
	}

	@FormatMethod
	private void mcpError(@NonNull Element element, @NonNull String format,
			Object... arguments) {
		mcpProcessingErrorDetected = true;
		mcpProcessingErrorCount++;
		error(element, format, arguments);
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
					error(reportOn, "Soklet: Duplicate @PathParameter name: %s", normalized);
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
		enforceAnnotatedReturnTypes(roundEnv, SseEventSource.class, sseHandshakeResultType, "SseHandshakeResult");
	}

	private void enforceAnnotatedReturnTypes(RoundEnvironment roundEnv,
																					 Class<? extends Annotation> annotationType,
																					 TypeMirror expectedReturnType,
																					 String expectedReturnTypeName) {
		if (expectedReturnType == null)
			return;

		TypeElement annotationElement = elements.getTypeElement(annotationType.getCanonicalName());
		if (annotationElement == null)
			return;

		for (Element element : roundEnv.getElementsAnnotatedWith(annotationElement)) {
			if (element.getKind() != ElementKind.METHOD) {
				error(element, "@%s can only be applied to methods.", annotationType.getSimpleName());
				continue;
			}

			ExecutableElement method = (ExecutableElement) element;
			TypeMirror returnType = method.getReturnType();
			boolean assignable = types.isAssignable(returnType, expectedReturnType);

			if (!assignable) {
				error(element,
						"Soklet: Methods annotated with @%s must specify a return type of %s (found: %s).",
						annotationType.getSimpleName(), expectedReturnTypeName, prettyType(returnType));
			}
		}
	}

	private static String prettyType(TypeMirror t) {
		return (t == null ? "null" : t.toString());
	}

	// ---- Index read/merge/write ----------------------------------------------

	private void detectResourceMethodAmbiguity(@NonNull Element element,
																						 @NonNull ResourceMethodDeclaration declaration) {
		for (ResourceMethodDeclaration existing : dedupeAndOrder(collected)) {
			if (resourceMethodDeclarationsAmbiguous(existing, declaration)) {
				resourceMethodAmbiguityDetected = true;
				error(element, "Soklet: Ambiguous resource method declarations detected. %s overlaps %s",
						describeResourceMethodDeclaration(declaration), describeResourceMethodDeclaration(existing));
			}
		}
	}

	private static boolean resourceMethodDeclarationsAmbiguous(@NonNull ResourceMethodDeclaration first,
																															@NonNull ResourceMethodDeclaration second) {
		if (generateKey(first).equals(generateKey(second)))
			return false;

		ResourcePathDeclaration firstPath = ResourcePathDeclaration.fromPath(first.path());
		ResourcePathDeclaration secondPath = ResourcePathDeclaration.fromPath(second.path());
		ResourceMethodSpecificityKey firstKey = specificityKey(first, firstPath);
		ResourceMethodSpecificityKey secondKey = specificityKey(second, secondPath);

		return firstKey.equals(secondKey) && resourcePathDeclarationsOverlap(firstPath, secondPath);
	}

	@NonNull
	private static ResourceMethodSpecificityKey specificityKey(@NonNull ResourceMethodDeclaration declaration,
																														 @NonNull ResourcePathDeclaration resourcePathDeclaration) {
		return new ResourceMethodSpecificityKey(
				declaration.httpMethod(),
				declaration.sseEventSource(),
				resourcePathDeclaration.getVarargsComponent().isPresent(),
				placeholderCount(resourcePathDeclaration),
				literalCount(resourcePathDeclaration));
	}

	@NonNull
	private static String describeResourceMethodDeclaration(@NonNull ResourceMethodDeclaration declaration) {
		return String.format("%s %s %s -> %s#%s(%s)",
				declaration.sseEventSource() ? "SSE" : "HTTP",
				declaration.httpMethod().name(),
				declaration.path(),
				declaration.className(),
				declaration.methodName(),
				String.join(", ", declaration.parameterTypes()));
	}

	private static long placeholderCount(@NonNull ResourcePathDeclaration declaration) {
		return declaration.getComponents().stream()
				.filter(component -> component.getType() == ResourcePathDeclaration.ComponentType.PLACEHOLDER)
				.count();
	}

	private static long literalCount(@NonNull ResourcePathDeclaration declaration) {
		return declaration.getComponents().stream()
				.filter(component -> component.getType() == ResourcePathDeclaration.ComponentType.LITERAL)
				.count();
	}

	private static boolean resourcePathDeclarationsOverlap(@NonNull ResourcePathDeclaration first,
																												 @NonNull ResourcePathDeclaration second) {
		List<ResourcePathDeclaration.Component> firstComponents = first.getComponents();
		List<ResourcePathDeclaration.Component> secondComponents = second.getComponents();

		boolean firstHasVarargs = first.getVarargsComponent().isPresent();
		boolean secondHasVarargs = second.getVarargsComponent().isPresent();

		int firstPrefixLength = firstComponents.size() - (firstHasVarargs ? 1 : 0);
		int secondPrefixLength = secondComponents.size() - (secondHasVarargs ? 1 : 0);

		if (!firstHasVarargs && !secondHasVarargs) {
			if (firstComponents.size() != secondComponents.size())
				return false;

			for (int i = 0; i < firstComponents.size(); i++)
				if (!componentsCompatible(firstComponents.get(i), secondComponents.get(i)))
					return false;

			return true;
		}

		if (firstHasVarargs && !secondHasVarargs) {
			if (secondComponents.size() < firstPrefixLength)
				return false;

			for (int i = 0; i < firstPrefixLength; i++)
				if (!componentsCompatible(firstComponents.get(i), secondComponents.get(i)))
					return false;

			return true;
		}

		if (!firstHasVarargs) {
			if (firstComponents.size() < secondPrefixLength)
				return false;

			for (int i = 0; i < secondPrefixLength; i++)
				if (!componentsCompatible(firstComponents.get(i), secondComponents.get(i)))
					return false;

			return true;
		}

		int minPrefixLength = Math.min(firstPrefixLength, secondPrefixLength);

		for (int i = 0; i < minPrefixLength; i++)
			if (!componentsCompatible(firstComponents.get(i), secondComponents.get(i)))
				return false;

		return true;
	}

	private static boolean componentsCompatible(ResourcePathDeclaration.@NonNull Component first,
																							ResourcePathDeclaration.@NonNull Component second) {
		if (first.getType() == ResourcePathDeclaration.ComponentType.LITERAL
				&& second.getType() == ResourcePathDeclaration.ComponentType.LITERAL)
			return first.getValue().equals(second.getValue());

		return true;
	}

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

	private void mergeAndWriteMcpEndpointIndex(
			@NonNull List<McpEndpointProviderDeclaration> newlyCollected,
			@NonNull Set<String> touchedTopLevelBinaries) {
		Path classOutputRoot = findClassOutputRoot();
		Path classOutputIndexPath = classOutputRoot == null ? null
				: classOutputRoot.resolve(
						McpGeneratedEndpointProviderIndex.RESOURCE_PATH);
		Path sideCarIndexPath = cacheMode == CacheMode.NONE ? null
				: mcpSideCarIndexPath(classOutputRoot);
		Path persistentIndexPath = cacheMode == CacheMode.PERSISTENT
				? mcpPersistentIndexPath(classOutputRoot) : null;

		debug("SokletProcessor: MCP classOutputIndexPath=%s",
				classOutputIndexPath);
		debug("SokletProcessor: MCP sidecarIndexPath=%s",
				sideCarIndexPath);
		debug("SokletProcessor: MCP persistentIndexPath=%s",
				persistentIndexPath);
		debug("SokletProcessor: MCP touchedTopLevels=%s",
				touchedTopLevelBinaries);

		Map<String, McpEndpointProviderDeclaration> merged =
				new LinkedHashMap<>();
		// Every index is a complete snapshot. Prefer the current compiler output;
		// only fall back to a cache when that output is absent. Unioning snapshots
		// would let an older cache resurrect a row deliberately removed by a newer
		// compilation.
		boolean existingSnapshotRead = classOutputIndexPath != null
				&& readMcpEndpointIndexFromPath(classOutputIndexPath, merged);
		if (!existingSnapshotRead)
			existingSnapshotRead = readMcpEndpointIndexFromLocation(merged);
		if (!existingSnapshotRead && sideCarIndexPath != null)
			existingSnapshotRead = readMcpEndpointIndexFromPath(
					sideCarIndexPath, merged);
		if (!existingSnapshotRead && persistentIndexPath != null)
			readMcpEndpointIndexFromPath(persistentIndexPath, merged);
		if (mcpProcessingErrorDetected)
			return;

		merged.values().removeIf(declaration -> touchedTopLevelBinaries
				.contains(declaration.topLevelBinaryName()));
		for (McpEndpointProviderDeclaration declaration : newlyCollected)
			merged.put(declaration.endpointBinaryName(), declaration);

		if (pruneDeletedEnabled && classOutputRoot != null) {
			Set<String> currentEndpointBinaries = newlyCollected.stream()
					.map(McpEndpointProviderDeclaration::endpointBinaryName)
					.collect(Collectors.toSet());
			merged.values().removeIf(declaration ->
					!currentEndpointBinaries.contains(
							declaration.endpointBinaryName())
							&& (!classFileExistsInOutputRoot(classOutputRoot,
									declaration.endpointBinaryName())
							|| !classFileExistsInOutputRoot(classOutputRoot,
									declaration.providerBinaryName())));
			debug("SokletProcessor: MCP afterPruneDeleted=%d",
					merged.size());
		}

		Map<String, String> endpointByPath = new LinkedHashMap<>();
		for (McpEndpointProviderDeclaration declaration : merged.values()) {
			String previous = endpointByPath.putIfAbsent(declaration.endpointPath(),
					declaration.endpointBinaryName());
			if (previous != null
					&& !previous.equals(declaration.endpointBinaryName())) {
				mcpError("Soklet: Duplicate annotated MCP endpoint path is declared by %s and %s.",
						previous,
						declaration.endpointBinaryName());
				return;
			}
		}

		Map<String, String> endpointByProvider = new LinkedHashMap<>();
		for (McpEndpointProviderDeclaration declaration : merged.values()) {
			String previous = endpointByProvider.putIfAbsent(
					declaration.providerBinaryName(),
					declaration.endpointBinaryName());
			if (previous != null
					&& !previous.equals(declaration.endpointBinaryName())) {
				mcpError("Soklet: Generated MCP provider %s is assigned to multiple endpoint classes.",
						declaration.providerBinaryName());
				return;
			}
		}

		List<McpEndpointProviderDeclaration> ordered =
				new ArrayList<>(merged.values());
		ordered.sort(Comparator
				.comparing(McpEndpointProviderDeclaration::endpointBinaryName)
				.thenComparing(
						McpEndpointProviderDeclaration::providerBinaryName));
		writeMcpEndpointIndexResource(ordered, classOutputIndexPath,
				touchedTopLevelBinaries, newlyCollected);
		if (mcpProcessingErrorDetected)
			return;
		if (sideCarIndexPath != null)
			writeMcpEndpointIndexCache(sideCarIndexPath, ordered);
		if (persistentIndexPath != null)
			writeMcpEndpointIndexCache(persistentIndexPath, ordered);
		debug("SokletProcessor: wroteMcpEndpointIndexSize=%d",
				ordered.size());
	}

	private boolean readMcpEndpointIndexFromLocation(
			@NonNull Map<String, McpEndpointProviderDeclaration> output) {
		boolean opened = false;
		try {
			FileObject resource = filer.getResource(StandardLocation.CLASS_OUTPUT,
					"", McpGeneratedEndpointProviderIndex.RESOURCE_PATH);
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(
					resource.openInputStream(), StandardCharsets.UTF_8))) {
				opened = true;
				Map<String, McpEndpointProviderDeclaration> parsed =
						new LinkedHashMap<>(output);
				int errorsBefore = mcpProcessingErrorCount;
				readMcpEndpointIndex(reader, parsed);
				if (mcpProcessingErrorCount == errorsBefore) {
					output.clear();
					output.putAll(parsed);
				}
				return true;
			}
		} catch (IOException exception) {
			if (opened)
				mcpError("Soklet: Unable to read the existing generated MCP endpoint-provider index.");
			// Failure to open normally means the resource does not exist during a
			// clean compilation. Once opened, any read failure is fatal so a
			// partial prior index can never replace the durable one.
			return opened;
		}
	}

	private boolean readMcpEndpointIndexFromPath(@NonNull Path path,
			@NonNull Map<String, McpEndpointProviderDeclaration> output) {
		if (!Files.isRegularFile(path))
			return false;
		try (BufferedReader reader = Files.newBufferedReader(path,
				StandardCharsets.UTF_8)) {
			Map<String, McpEndpointProviderDeclaration> parsed =
					new LinkedHashMap<>(output);
			int errorsBefore = mcpProcessingErrorCount;
			readMcpEndpointIndex(reader, parsed);
			if (mcpProcessingErrorCount == errorsBefore) {
				output.clear();
				output.putAll(parsed);
			}
			return true;
		} catch (IOException exception) {
			mcpError("Soklet: Unable to read the existing generated MCP endpoint-provider index.");
			return true;
		}
	}

	private void readMcpEndpointIndex(@NonNull BufferedReader reader,
			@NonNull Map<String, McpEndpointProviderDeclaration> output)
			throws IOException {
		for (String line; (line = reader.readLine()) != null; ) {
			String stripped = line.strip();
			if (stripped.isEmpty() || stripped.startsWith("#"))
				continue;
			McpEndpointProviderDeclaration declaration =
					parseMcpEndpointIndexLine(stripped);
			if (declaration == null) {
				mcpError("Soklet: The existing generated MCP endpoint-provider index is malformed.");
				return;
			}
			McpEndpointProviderDeclaration previous = output.putIfAbsent(
					declaration.endpointBinaryName(), declaration);
			if (previous != null && !previous.equals(declaration)) {
				mcpError("Soklet: Conflicting generated MCP endpoint providers exist for %s.",
						declaration.endpointBinaryName());
				return;
			}
		}
	}

	private static McpEndpointProviderDeclaration parseMcpEndpointIndexLine(
			@NonNull String line) {
		try {
			String[] fields = line.split("\\|", -1);
			if (fields.length != 5 || !"3".equals(fields[0]))
				return null;
			Base64.Decoder decoder = Base64.getDecoder();
			String endpoint = new String(decoder.decode(fields[1]),
					StandardCharsets.UTF_8);
			String provider = new String(decoder.decode(fields[2]),
					StandardCharsets.UTF_8);
			String topLevel = new String(decoder.decode(fields[3]),
					StandardCharsets.UTF_8);
			String endpointPath = new String(decoder.decode(fields[4]),
					StandardCharsets.UTF_8);
			McpGeneratedEndpointProviderIndex.formatLine(endpoint, provider,
					topLevel, endpointPath);
			return new McpEndpointProviderDeclaration(endpoint, provider,
					topLevel, endpointPath);
		} catch (IllegalArgumentException exception) {
			return null;
		}
	}

	private void writeMcpEndpointIndexResource(
			@NonNull List<McpEndpointProviderDeclaration> declarations,
			Path directIndexPath,
			@NonNull Set<String> touchedTopLevelBinaries,
			@NonNull List<McpEndpointProviderDeclaration> newlyCollected) {
		Element[] origins = computeMcpOriginatingElements(
				touchedTopLevelBinaries, newlyCollected);
		try {
			FileObject resource = filer.createResource(StandardLocation.CLASS_OUTPUT,
					"", McpGeneratedEndpointProviderIndex.RESOURCE_PATH, origins);
			try (Writer writer = resource.openWriter()) {
				writeMcpEndpointIndex(writer, declarations);
			}
			return;
		} catch (FilerException exists) {
			try {
				FileObject resource = filer.getResource(StandardLocation.CLASS_OUTPUT,
						"", McpGeneratedEndpointProviderIndex.RESOURCE_PATH);
				try (Writer writer = resource.openWriter()) {
					writeMcpEndpointIndex(writer, declarations);
				}
				return;
			} catch (IOException ignored) {
				// Fall through to a direct, atomic filesystem write.
			}
		} catch (IOException exception) {
			debug("SokletProcessor: MCP index Filer write failed (%s); attempting direct write.",
					exception);
		}

		if (directIndexPath == null) {
			mcpError("Soklet: Unable to write the generated MCP endpoint-provider index.");
			return;
		}
		try {
			writeMcpEndpointIndexAtomically(directIndexPath, declarations);
		} catch (IOException exception) {
			mcpError("Soklet: Unable to write the generated MCP endpoint-provider index.");
		}
	}

	private Element[] computeMcpOriginatingElements(
			@NonNull Set<String> touchedTopLevelBinaries,
			@NonNull List<McpEndpointProviderDeclaration> newlyCollected) {
		Set<Element> origins = new LinkedHashSet<>();
		for (String binaryName : touchedTopLevelBinaries) {
			TypeElement type = elements.getTypeElement(binaryName);
			if (type != null)
				origins.add(type);
		}
		for (McpEndpointProviderDeclaration declaration : newlyCollected) {
			TypeElement type = elements.getTypeElement(
					declaration.topLevelBinaryName());
			if (type != null)
				origins.add(type);
		}
		return origins.toArray(new Element[0]);
	}

	private static void writeMcpEndpointIndex(@NonNull Writer writer,
			@NonNull List<McpEndpointProviderDeclaration> declarations)
			throws IOException {
		for (McpEndpointProviderDeclaration declaration : declarations) {
			writer.write(McpGeneratedEndpointProviderIndex.formatLine(
					declaration.endpointBinaryName(),
					declaration.providerBinaryName(),
					declaration.topLevelBinaryName(),
					declaration.endpointPath()));
			writer.write('\n');
		}
	}

	private static void writeMcpEndpointIndexAtomically(@NonNull Path target,
			@NonNull List<McpEndpointProviderDeclaration> declarations)
			throws IOException {
		Path parent = target.getParent();
		if (parent != null)
			Files.createDirectories(parent);
		Path fileName = target.getFileName();
		if (fileName == null)
			throw new IOException("Unable to determine MCP index filename.");
		Path temporary = Files.createTempFile(parent == null ? Path.of(".")
				: parent, fileName.toString(), ".tmp");
		try {
			try (Writer writer = Files.newBufferedWriter(temporary,
					StandardCharsets.UTF_8)) {
				writeMcpEndpointIndex(writer, declarations);
			}
			try {
				Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING,
						StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException exception) {
				Files.move(temporary, target,
						StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	private void writeMcpEndpointIndexCache(@NonNull Path target,
			@NonNull List<McpEndpointProviderDeclaration> declarations) {
		try {
			writeMcpEndpointIndexAtomically(target, declarations);
		} catch (IOException exception) {
			try {
				Files.deleteIfExists(target);
				debug("SokletProcessor: failed to write MCP cache index %s; invalidated the stale snapshot (%s)",
						target, exception);
			} catch (IOException deletionException) {
				mcpError("Soklet: Unable to update or invalidate an MCP endpoint-provider cache index.");
			}
		}
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
			// The marker may not exist yet; create it below if possible.
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
		Path outputRootFileName = classOutputRoot.getFileName();
		if (outputRootFileName == null) return null;
		String outputRootName = outputRootFileName.toString();
		return parent.resolve(SIDE_CAR_DIR_NAME).resolve(outputRootName).resolve(SIDE_CAR_INDEX_FILENAME);
	}

	private Path mcpSideCarIndexPath(Path classOutputRoot) {
		if (classOutputRoot == null) return null;
		Path parent = classOutputRoot.getParent();
		if (parent == null) return null;
		Path outputRootFileName = classOutputRoot.getFileName();
		if (outputRootFileName == null) return null;
		String outputRootName = outputRootFileName.toString();
		return parent.resolve(SIDE_CAR_DIR_NAME).resolve(outputRootName)
				.resolve(MCP_SIDE_CAR_INDEX_FILENAME);
	}

	private Path persistentIndexPath(Path classOutputRoot) {
		if (classOutputRoot == null) return null;
		Path cacheRoot = persistentCacheRoot();
		if (cacheRoot == null) return null;

		String key = hashPath(classOutputRoot.toAbsolutePath().normalize().toString());
		return cacheRoot.resolve(PERSISTENT_CACHE_INDEX_DIR).resolve(key).resolve(SIDE_CAR_INDEX_FILENAME);
	}

	private Path mcpPersistentIndexPath(Path classOutputRoot) {
		if (classOutputRoot == null) return null;
		Path cacheRoot = persistentCacheRoot();
		if (cacheRoot == null) return null;

		String key = hashPath(classOutputRoot.toAbsolutePath().normalize()
				.toString());
		return cacheRoot.resolve(MCP_PERSISTENT_CACHE_INDEX_DIR).resolve(key)
				.resolve(MCP_SIDE_CAR_INDEX_FILENAME);
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
					Boolean.toString(r.sseEventSource())
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
		Path targetFileName = target.getFileName();
		if (targetFileName == null)
			throw new IOException("Unable to determine filename for " + target);

		Path tmp = Files.createTempFile(parent == null ? Path.of(".") : parent, targetFileName.toString(), ".tmp");
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

	@FormatMethod
	private void error(Element e, String fmt, Object... args) {
		messager.printMessage(Diagnostic.Kind.ERROR, String.format(fmt, args), e);
	}

	@FormatMethod
	private void mcpError(@NonNull String format, Object... arguments) {
		mcpProcessingErrorDetected = true;
		mcpProcessingErrorCount++;
		messager.printMessage(Diagnostic.Kind.ERROR,
				String.format(format, arguments));
	}

	@FormatMethod
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
				r.sseEventSource();
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

	private record McpEndpointProviderDeclaration(String endpointBinaryName,
			String providerBinaryName, String topLevelBinaryName,
			String endpointPath) {}

	private record McpEndpointModel(String packageName,
			String endpointQualifiedName, String endpointBinaryName,
			String providerSimpleName, String providerBinaryName, String path,
			String name, String version, String title, String description,
			String websiteUrl, String instructions, String toolRateLimiter,
			long resourceListCacheTtlMs, String resourceListCacheScope,
			long resourceTemplateListCacheTtlMs,
			String resourceTemplateListCacheScope,
			List<McpToolModel> tools, List<McpPromptModel> prompts,
			List<McpResourceModel> resources,
			@Nullable McpResourceListModel resourceList) {}

	private record McpToolModel(ExecutableElement method, String name,
			String title, String description, String rateLimiter,
			boolean mirrorStructuredContentAsText,
			List<McpParameterBinding> bindings, String inputSchemaDigest,
			String outputSchemaDigest) {}

	private record McpPromptModel(ExecutableElement method, String name,
			String title, String description, boolean promptOutputReturn,
			List<McpPromptParameterBinding> bindings) {}

	private record McpParameterBinding(McpParameterBindingKind kind,
			String publishedName, String carrierName, TypeMirror type, String title,
			String description, @Nullable String headerName) {}

	private enum McpParameterBindingKind {
		REQUEST_CONTEXT,
		INVOCATION_FEATURES,
		CANCELATION_TOKEN,
		PROGRESS_REPORTER,
		TOOL_ARGUMENT
	}

	private record McpPromptParameterBinding(
			McpPromptParameterBindingKind kind, String publishedName,
			String title, String description, boolean required) {}

	private enum McpPromptParameterBindingKind {
		REQUEST_CONTEXT,
		INVOCATION_FEATURES,
		CANCELATION_TOKEN,
		PROGRESS_REPORTER,
		PROMPT_ARGUMENT
	}

	private record McpResourceModel(ExecutableElement method, String address,
			boolean template, String name, String title, String description,
			String mimeType, long size, long cacheTtlMs, String cacheScope,
			boolean resourceOutputReturn,
			List<McpResourceParameterBinding> bindings) {}

	private record McpResourceParameterBinding(
			McpResourceParameterBindingKind kind,
			@Nullable String variableName) {}

	private enum McpResourceParameterBindingKind {
		REQUEST_CONTEXT,
		INVOCATION_FEATURES,
		CANCELATION_TOKEN,
		PROGRESS_REPORTER,
		RESOURCE_READ_CONTEXT,
		URI_PARAMETER
	}

	private record McpResourceListModel(ExecutableElement method,
			List<McpResourceListParameterBinding> bindings) {}

	private record McpLevelOneResourceTemplate(List<String> variables,
			List<McpTemplateOverlapAtom> overlapAtoms) {}

	private record McpTemplateLiteralToken(String value,
			boolean variableConsumable, int sourceLength) {}

	private record McpTemplateOverlapAtom(String value, boolean wildcard,
			boolean variableConsumable) {
		@NonNull
		private static McpTemplateOverlapAtom wildcardAtom() {
			return new McpTemplateOverlapAtom("", true, false);
		}
	}

	private record McpTemplateOverlapState(int leftIndex, int rightIndex) {}

	private enum McpResourceListParameterBinding {
		REQUEST_CONTEXT,
		INVOCATION_FEATURES,
		CANCELATION_TOKEN,
		PROGRESS_REPORTER,
		RESOURCE_LIST_CONTEXT
	}


	private record ResourceMethodSpecificityKey(HttpMethod httpMethod,
																							Boolean sseEventSource,
																							Boolean hasVarargs,
																							Long placeholderCount,
																							Long literalCount) {}

}
