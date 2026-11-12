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

import com.soklet.internal.microhttp.Header;
import com.sun.source.tree.AnnotatedTypeTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.TreeScanner;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
	 * Negative checks preserving modern statelessness,
 * replay absence, and the exact provisional minimum 2025 session surface.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Timeout(60)
public class McpLegacySessionNegativeInventoryTests {
	private static final Path PUBLIC_SOURCE_ROOT =
			Path.of("src/main/java/com/soklet");
	private static final Path INTERNAL_MCP_SOURCE_ROOT =
			PUBLIC_SOURCE_ROOT.resolve("internal/mcp");
	private static final Path HTTP_RUNTIME_SOURCE = INTERNAL_MCP_SOURCE_ROOT
			.resolve("protocol/McpHttpServerRuntime.java");
	private static final Path MODERN_PROFILE_SOURCE = INTERNAL_MCP_SOURCE_ROOT
			.resolve("protocol/Mcp20260728ProtocolProfile.java");
	private static final Pattern REPLAY_STATE_IDENTIFIER = Pattern.compile(
			"\\b[A-Za-z0-9_]*(?:last_?event_?id|replay)[A-Za-z0-9_]*\\b",
			Pattern.CASE_INSENSITIVE);
	private static final Pattern LEGACY_STATE_IDENTIFIER = Pattern.compile(
			"\\b[A-Za-z0-9_]*(?:mcp_?session"
					+ "|session_?(?:id|store|state|cache|registry|map|table|cursor"
					+ "|token|manager|storage|repository|record|context)"
					+ "|last_?event_?id|replay)[A-Za-z0-9_]*\\b",
			Pattern.CASE_INSENSITIVE);

	@Test
	public void modernMcpSourceHasNoLegacySessionOrReplayState()
			throws Exception {
		List<Path> sourcePaths = mcpProductionSourcePaths();
		Assertions.assertFalse(sourcePaths.isEmpty(),
				"MCP production source inventory must not be empty.");
		for (Path sourcePath : sourcePaths) {
			String source = Files.readString(sourcePath, StandardCharsets.UTF_8);
			// Header safety Javadoc lists the exact denylist; it is not wire state.
			if (sourcePath.equals(Path.of("src/main/java/com/soklet/McpAdmissionRejection.java")))
				source = source.replace("{@code Mcp-Session-Id}", "{@code reserved session header}")
						.replace("{@code Last-Event-ID}", "{@code reserved transport header}");
			assertNoReplayStateIdentifier(sourcePath, source);
			if (sourcePath.equals(MODERN_PROFILE_SOURCE))
				assertNoLegacyStateIdentifier(sourcePath, source);
			if (!sourcePath.equals(HTTP_RUNTIME_SOURCE)) {
				String lowerSource = source.toLowerCase(Locale.ROOT);
				Assertions.assertFalse(lowerSource.contains("mcp-session-id"),
						() -> "Legacy session header escaped its denylist: "
								+ sourcePath);
				Assertions.assertFalse(lowerSource.contains("last-event-id"),
						() -> "Legacy replay header escaped its denylist: "
								+ sourcePath);
			}
		}

	}

	@Test
	public void legacyHeadersRemainPolicyForbiddenWithFrameworkOnlySessionPublication()
			throws Exception {
		Class<?> runtimeType = Class.forName("com.soklet.internal.mcp.protocol.McpHttpServerRuntime");
		var sessionHeader = runtimeType.getDeclaredField("MCP_SESSION_ID");
		Assertions.assertEquals(String.class, sessionHeader.getType());
		Assertions.assertTrue(Modifier.isPrivate(sessionHeader.getModifiers()));
		Assertions.assertTrue(Modifier.isStatic(sessionHeader.getModifiers()));
		Assertions.assertTrue(Modifier.isFinal(sessionHeader.getModifiers()));
		sessionHeader.setAccessible(true);
		Assertions.assertEquals("Mcp-Session-Id", sessionHeader.get(null));

		var forbiddenHeaders = runtimeType
				.getDeclaredField("FORBIDDEN_LEGACY_MCP_POLICY_HEADERS");
		forbiddenHeaders.setAccessible(true);
		Set<?> denylist = Assertions.assertInstanceOf(Set.class, forbiddenHeaders.get(null));
		Assertions.assertTrue(denylist.containsAll(Set.of("mcp-session-id", "last-event-id")));

		var validator = runtimeType.getDeclaredMethod("validatedPolicyHeaders", Map.class);
		validator.setAccessible(true);
		for (String header : List.of("Mcp-Session-Id", "mcp-session-id", "mCp-SeSsIoN-iD",
				"Last-Event-ID", "last-event-id", "lAsT-eVeNt-iD")) {
			InvocationTargetException failure = Assertions.assertThrows(InvocationTargetException.class,
					() -> validator.invoke(null, Map.of(header, List.of("must-not-escape"))), header);
			Assertions.assertInstanceOf(IllegalArgumentException.class, failure.getCause(), header);
		}
		List<?> allowed = Assertions.assertInstanceOf(List.class,
				validator.invoke(null, Map.of("X-Policy", List.of("first", "second"))));
		Assertions.assertEquals(List.of(new Header("X-Policy", "first"), new Header("X-Policy", "second")), allowed);
		Assertions.assertThrows(UnsupportedOperationException.class, allowed::clear);

		assertLegacyHeaderStructure(parseRuntime(Files.readString(HTTP_RUNTIME_SOURCE, StandardCharsets.UTF_8)));
	}

	@Test
	public void structuralGuardRejectsLegacyPublicationAndValidatorBypasses() throws Exception {
		String runtime = """
				class McpHttpServerRuntime {
				  private static final @org.jspecify.annotations.NonNull String MCP_SESSION_ID = "Mcp-Session-Id";
				  private static final Set<String> FORBIDDEN_LEGACY_MCP_POLICY_HEADERS = Set.of("mcp-session-id", "last-event-id");
				  Set<String> mcpPreflightRequestHeaders(EndpointRuntime configuredEndpoint) {
				    Set<String> result = new HashSet<>();
				    if (legacyHttpMethods(configuredEndpoint, null).contains(HttpMethod.GET)) {
				      result.add("Last-Event-ID");
				    }
				    return result;
				  }
				  void admissionRejection(McpAdmissionRejection policyResult) {
				    validatedPolicyHeaders(policyResult.headers());
				  }
				  void notificationAdmissionRejection(McpAdmissionRejection policyResult) {
				    validatedPolicyHeaders(policyResult.headers());
				  }
				}
				""";
		assertLegacyHeaderStructure(parseRuntime(runtime));
		Assertions.assertThrows(AssertionError.class, () -> assertLegacyHeaderStructure(parseRuntime(
				runtime.replace("legacyHttpMethods(configuredEndpoint, null).contains(HttpMethod.GET)", "true"))));
		Assertions.assertThrows(AssertionError.class, () -> assertLegacyHeaderStructure(parseRuntime(
				runtime.replace("void notificationAdmissionRejection(McpAdmissionRejection policyResult) {\n"
						+ "    validatedPolicyHeaders(policyResult.headers());",
						"void notificationAdmissionRejection(McpAdmissionRejection policyResult) {"))));
		Assertions.assertThrows(AssertionError.class, () -> assertLegacyHeaderStructure(parseRuntime(
				runtime.replace("Set<String> result = new HashSet<>();",
						"Set<String> result = new HashSet<>(); result.add(\"Mcp-Session-Id\");"))));
	}

	private static List<Path> mcpProductionSourcePaths() throws IOException {
		try (var paths = Files.walk(PUBLIC_SOURCE_ROOT)) {
			return paths.filter(Files::isRegularFile)
					.filter(path -> path.getFileName().toString().endsWith(".java"))
					.filter(path -> path.startsWith(INTERNAL_MCP_SOURCE_ROOT)
							|| path.getFileName().toString().startsWith("Mcp")
							|| path.getFileName().toString().startsWith("DefaultMcp"))
					.sorted()
					.toList();
		}
	}

	private static void assertNoReplayStateIdentifier(Path path, String source) {
		Matcher matcher = REPLAY_STATE_IDENTIFIER.matcher(source);
		Assertions.assertFalse(matcher.find(), () -> "Replay state identifier '"
				+ matcher.group() + "' found in " + path);
	}

	private static void assertNoLegacyStateIdentifier(Path path, String source) {
		Matcher matcher = LEGACY_STATE_IDENTIFIER.matcher(source);
		Assertions.assertFalse(matcher.find(), () -> "Legacy MCP session/replay state "
				+ "identifier '" + matcher.group() + "' found in " + path);
	}

	private static CompilationUnitTree parseRuntime(String source) throws IOException {
		var compiler = ToolProvider.getSystemJavaCompiler();
		Assertions.assertNotNull(compiler, "A full JDK is required for protocol source checks");
		var diagnostics = new DiagnosticCollector<JavaFileObject>();
		try (var files = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
			var input = new SimpleJavaFileObject(URI.create("string:///McpHttpServerRuntime.java"),
					JavaFileObject.Kind.SOURCE) {
				@Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return source; }
			};
			JavacTask task = (JavacTask) compiler.getTask(null, files, diagnostics,
					List.of("--release", "21", "-proc:none"), null, List.of(input));
			CompilationUnitTree result = task.parse().iterator().next();
			Assertions.assertTrue(diagnostics.getDiagnostics().stream()
					.noneMatch(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR),
					() -> diagnostics.getDiagnostics().toString());
			return result;
		}
	}

	private static void assertLegacyHeaderStructure(CompilationUnitTree runtime) {
		Set<String> admissionPaths = Set.of("admissionRejection", "notificationAdmissionRejection");
		Set<String> validatedPaths = new HashSet<>();
		boolean[] configuredGetHeaderFound = {false};
		new TreePathScanner<Void, Void>() {
			@Override
			public Void visitMethod(MethodTree method, Void unused) {
				if (admissionPaths.contains(method.getName().toString())) {
					String rejectionParameter = parameterName(method, "McpAdmissionRejection");
					boolean[] validatesRejection = {false};
					new TreeScanner<Void, Void>() {
						@Override public Void visitMethodInvocation(MethodInvocationTree invocation, Void ignored) {
							if (simpleName(invocation.getMethodSelect()).equals("validatedPolicyHeaders")
									&& invocation.getArguments().size() == 1
									&& unwrap(invocation.getArguments().get(0)) instanceof MethodInvocationTree headers
									&& headers.getArguments().isEmpty()
									&& headers.getMethodSelect() instanceof MemberSelectTree accessor
									&& accessor.getIdentifier().contentEquals("headers")
									&& simpleName(accessor.getExpression()).equals(rejectionParameter))
								validatesRejection[0] = true;
							return super.visitMethodInvocation(invocation, ignored);
						}
					}.scan(method.getBody(), null);
					Assertions.assertTrue(validatesRejection[0], () -> method.getName()
							+ " must validate its admission policy's response headers");
					validatedPaths.add(method.getName().toString());
				}
				return super.visitMethod(method, unused);
			}

			@Override
			public Void visitLiteral(LiteralTree literal, Void unused) {
				if (!(literal.getValue() instanceof String value))
					return super.visitLiteral(literal, unused);
				if (value.equalsIgnoreCase("mcp-session-id"))
					Assertions.assertTrue(isFieldLiteral(getCurrentPath(), "MCP_SESSION_ID")
							|| isFieldLiteral(getCurrentPath(), "FORBIDDEN_LEGACY_MCP_POLICY_HEADERS"),
							"Legacy session header literals must stay in framework-owned constants");
				if (value.equalsIgnoreCase("last-event-id")
						&& !isFieldLiteral(getCurrentPath(), "FORBIDDEN_LEGACY_MCP_POLICY_HEADERS")) {
					Assertions.assertTrue(isConfiguredGetHeader(getCurrentPath()),
							"Ignored event-header input must be added only for configured legacy GET");
					configuredGetHeaderFound[0] = true;
				}
				return super.visitLiteral(literal, unused);
			}
		}.scan(runtime, null);
		Assertions.assertTrue(validatedPaths.containsAll(admissionPaths),
				"Both request and notification admission paths must remain validated");
		Assertions.assertTrue(configuredGetHeaderFound[0],
				"Configured legacy GET must retain its ignored event-header preflight input");
	}

	private static boolean isFieldLiteral(TreePath literal, String fieldName) {
		for (TreePath path = literal; path.getParentPath() != null; path = path.getParentPath())
			if (path.getLeaf() instanceof VariableTree field
					&& field.getName().contentEquals(fieldName)
					&& path.getParentPath().getLeaf() instanceof ClassTree owner
					&& owner.getSimpleName().contentEquals("McpHttpServerRuntime"))
				return true;
		return false;
	}

	private static boolean isConfiguredGetHeader(TreePath literal) {
		if (!(literal.getParentPath().getLeaf() instanceof MethodInvocationTree addition)
				|| !simpleName(addition.getMethodSelect()).equals("add"))
			return false;
		MethodTree preflight = null;
		for (TreePath path = literal; path != null; path = path.getParentPath())
			if (path.getLeaf() instanceof MethodTree method) {
				preflight = method;
				break;
			}
		if (preflight == null || !preflight.getName().contentEquals("mcpPreflightRequestHeaders"))
			return false;
		String endpointParameter = parameterName(preflight, "EndpointRuntime");
		for (TreePath child = literal; child.getParentPath() != null; child = child.getParentPath())
			if (child.getParentPath().getLeaf() instanceof IfTree branch
					&& branch.getThenStatement() == child.getLeaf()
					&& requiresConfiguredGet(branch.getCondition(), endpointParameter))
				return true;
		return false;
	}

	private static boolean requiresConfiguredGet(Tree condition, String endpointParameter) {
		condition = unwrap(condition);
		if (condition instanceof BinaryTree binary && binary.getKind() == Tree.Kind.CONDITIONAL_AND)
			return requiresConfiguredGet(binary.getLeftOperand(), endpointParameter)
					|| requiresConfiguredGet(binary.getRightOperand(), endpointParameter);
		return condition instanceof MethodInvocationTree contains
				&& contains.getArguments().size() == 1
				&& contains.getMethodSelect() instanceof MemberSelectTree selected
				&& selected.getIdentifier().contentEquals("contains")
				&& unwrap(selected.getExpression()) instanceof MethodInvocationTree methods
				&& simpleName(methods.getMethodSelect()).equals("legacyHttpMethods")
				&& methods.getArguments().size() == 2
				&& simpleName(methods.getArguments().get(0)).equals(endpointParameter)
				&& methods.getArguments().get(1).getKind() == Tree.Kind.NULL_LITERAL
				&& unwrap(contains.getArguments().get(0)) instanceof MemberSelectTree get
				&& get.getIdentifier().contentEquals("GET")
				&& simpleName(get.getExpression()).equals("HttpMethod");
	}

	private static String parameterName(MethodTree method, String typeName) {
		return method.getParameters().stream()
				.filter(parameter -> simpleName(parameter.getType()).equals(typeName))
				.map(parameter -> parameter.getName().toString()).findFirst()
				.orElseThrow(() -> new AssertionError("Missing " + typeName + " parameter in " + method.getName()));
	}

	private static String simpleName(Tree tree) {
		tree = unwrap(tree);
		if (tree instanceof IdentifierTree identifier)
			return identifier.getName().toString();
		if (tree instanceof MemberSelectTree selected)
			return selected.getIdentifier().toString();
		return "";
	}

	private static Tree unwrap(Tree tree) {
		while (true) {
			if (tree instanceof ParenthesizedTree parenthesized)
				tree = parenthesized.getExpression();
			else if (tree instanceof AnnotatedTypeTree annotated)
				tree = annotated.getUnderlyingType();
			else return tree;
		}
	}
}
