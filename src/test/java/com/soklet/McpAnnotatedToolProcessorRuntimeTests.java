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

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.annotation.concurrent.ThreadSafe;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.StringWriter;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * End-to-end coverage from annotated source through the generated provider and
 * the public typed-tool runtime.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class McpAnnotatedToolProcessorRuntimeTests {
	@NonNull
	private static final String LOOPBACK = "127.0.0.1";
	@NonNull
	private static final String PROTOCOL_VERSION = "2026-07-28";
	@NonNull
	private static final String INITIALIZED_PROPERTY =
			"com.soklet.tests.generated-endpoint-initialized";

	@Test
	void generatedProviderUsesOnlyPublicApisAndDefersInstancesUntilInvocation(
			@TempDir Path temporaryDirectory) throws Exception {
		Path sourceDirectory = temporaryDirectory.resolve("src/example");
		Path classDirectory = temporaryDirectory.resolve("classes");
		Path generatedDirectory = temporaryDirectory.resolve("generated");
		Files.createDirectories(sourceDirectory);
		Files.createDirectories(classDirectory);
		Files.createDirectories(generatedDirectory);
		Path endpointSource = sourceDirectory.resolve("CatalogEndpoint.java");
		Files.writeString(endpointSource, endpointSource(),
				StandardCharsets.UTF_8);

		String previousProperty = System.clearProperty(INITIALIZED_PROPERTY);
		try {
			compile(endpointSource, classDirectory, generatedDirectory);
			String generatedSource;
			try (var paths = Files.walk(generatedDirectory)) {
				Path generatedProvider = paths.filter(Files::isRegularFile)
						.filter(path -> path.getFileName().toString()
								.startsWith("SokletMcpEndpointProvider_"))
						.findFirst().orElseThrow();
				generatedSource = Files.readString(generatedProvider,
						StandardCharsets.UTF_8);
			}
			Assertions.assertFalse(generatedSource.contains("com.soklet.internal"),
					generatedSource);
			Assertions.assertTrue(generatedSource.contains(
					"public String[] schemaDigests()"), generatedSource);
			Assertions.assertFalse(generatedSource.contains("InternalMarker"),
					generatedSource);
			Assertions.assertTrue(generatedSource.contains("argument0"),
					generatedSource);
			Assertions.assertTrue(generatedSource.contains("promptBuilder0"),
					generatedSource);
			Assertions.assertTrue(generatedSource.contains(
					"McpPromptArgumentDefinition.withName(\"subject\")"),
					generatedSource);
			Assertions.assertTrue(generatedSource.contains(
					"@com.soklet.annotation.McpHeader(\"Tenant\")"),
					generatedSource);
			Assertions.assertTrue(generatedSource.contains(
					"search(request, features.require(com.soklet.CancelationToken.class), arguments.getConvertedArguments().argument0(), arguments.getConvertedArguments().argument1(), features.find(com.soklet.McpProgressReporter.class), features)"),
					generatedSource);
			Assertions.assertTrue(generatedSource.contains(
					"compose(request, features.require(com.soklet.CancelationToken.class), prompt.findArgument(\"subject\").orElseThrow(), prompt.findArgument(\"tone\"), features.find(com.soklet.McpProgressReporter.class), features)"),
					generatedSource);

			try (URLClassLoader classLoader = new URLClassLoader(
					new URL[] { classDirectory.toUri().toURL() },
					McpAnnotatedToolProcessorRuntimeTests.class.getClassLoader())) {
				Class<?> endpointClass = Class.forName("example.CatalogEndpoint",
						false, classLoader);
				AtomicInteger providedInstances = new AtomicInteger();
				InstanceProvider instanceProvider = new InstanceProvider() {
					@Override
					@NonNull
					public <T> T provide(@NonNull Class<T> instanceClass) {
						providedInstances.incrementAndGet();
						try {
							return instanceClass.cast(instanceClass.getConstructor()
									.newInstance());
						} catch (ReflectiveOperationException exception) {
							throw new IllegalStateException(exception);
						}
					}
				};

				McpEndpointRegistry registry = McpEndpointRegistry.fromClasses(
						instanceProvider, endpointClass);
				Assertions.assertNull(System.getProperty(INITIALIZED_PROPERTY));
				Assertions.assertEquals(0, providedInstances.get());
				McpEndpoint endpoint = registry.getEndpoints().get(0);
				McpPromptRegistration prompt = endpoint.getPrompts().get(0);
				Assertions.assertEquals("catalog.compose", prompt.getName());
				Assertions.assertEquals("Catalog composer",
						prompt.getTitle().orElseThrow());
				Assertions.assertEquals(2, prompt.getArguments().size());
				Assertions.assertEquals("subject",
						prompt.getArguments().get(0).getName());
				Assertions.assertTrue(prompt.getArguments().get(0).isRequired());
				Assertions.assertEquals("tone",
						prompt.getArguments().get(1).getName());
				Assertions.assertFalse(prompt.getArguments().get(1).isRequired());
				Assertions.assertEquals("/catalog/mcp", endpoint.getPath());
				Assertions.assertEquals("catalog", endpoint.getServerInformation()
						.getName());
				Assertions.assertEquals("Catalog server", endpoint
						.getServerInformation().getTitle().orElseThrow());
				Assertions.assertEquals("Use catalog.search", endpoint
						.getInstructions().orElseThrow());
				Assertions.assertEquals("catalog-endpoint", endpoint
						.getToolRateLimiterName().orElseThrow());

				McpToolRegistration<?> tool = endpoint.getTools().get(0);
				Assertions.assertEquals("catalog.search", tool.getName());
				Assertions.assertEquals("Catalog search",
						tool.getTitle().orElseThrow());
				Assertions.assertEquals("catalog-tool",
						tool.getRateLimiterName().orElseThrow());
				Assertions.assertFalse(
						tool.isStructuredContentTextMirroringEnabled());
				McpJsonObject properties = Assertions.assertInstanceOf(
						McpJsonObject.class, tool.getInputSchema().getDocument()
								.find("properties").orElseThrow());
				McpJsonObject querySchema = Assertions.assertInstanceOf(
						McpJsonObject.class,
						properties.find("query-text").orElseThrow());
				Assertions.assertEquals(McpJsonString.fromValue("Search query"),
						querySchema.find("title").orElseThrow());
				Assertions.assertEquals(McpJsonString.fromValue("Text to search for"),
						querySchema.find("description").orElseThrow());
				Assertions.assertEquals(McpJsonString.fromValue("Tenant"),
						querySchema.find("x-mcp-header").orElseThrow());
				Assertions.assertEquals(0, providedInstances.get());

				McpRateLimiter allow = context ->
						McpRateLimitDecision.allowed();
				Assertions.assertThrows(IllegalStateException.class,
						() -> serverBuilder(registry, allow,
								McpAdmissionController.acceptAllInstance()).build());
				AtomicInteger admissionInvocations = new AtomicInteger();
				AtomicInteger endpointLimiterInvocations = new AtomicInteger();
				AtomicInteger toolLimiterInvocations = new AtomicInteger();
				AtomicInteger fallbackLimiterInvocations = new AtomicInteger();
				AtomicInteger handlerInterceptorInvocations = new AtomicInteger();
					McpRateLimiterRegistry rateLimiterRegistry =
							McpRateLimiterRegistry.builder()
						.rateLimiter("catalog-endpoint", context -> {
							endpointLimiterInvocations.incrementAndGet();
							return McpRateLimitDecision.allowed();
						})
						.rateLimiter("catalog-tool", context -> {
							toolLimiterInvocations.incrementAndGet();
							return McpRateLimitDecision.allowed();
						})
						.build();
					McpServer server = serverBuilder(registry, context -> {
					fallbackLimiterInvocations.incrementAndGet();
					return McpRateLimitDecision.allowed();
				}, context -> {
					admissionInvocations.incrementAndGet();
					return McpAdmissionDecision.accepted();
					}).rateLimiterRegistry(rateLimiterRegistry)
						.handlerInterceptor((context, continuation) -> {
							handlerInterceptorInvocations.incrementAndGet();
							return continuation.proceed();
						})
						.build();
				try {
					server.start();
					int port = server.getDiagnostics().getBoundAddress()
							.orElseThrow().getPort();
					HttpResponse<String> listResponse = send(port, "tools/list",
							"{\"jsonrpc\":\"2.0\",\"id\":\"annotated-list\","
									+ "\"method\":\"tools/list\",\"params\":{\"_meta\":{"
									+ "\"io.modelcontextprotocol/protocolVersion\":\""
									+ PROTOCOL_VERSION + "\","
										+ "\"io.modelcontextprotocol/clientCapabilities\":{}}}}");
					Assertions.assertEquals(200, listResponse.statusCode(),
							listResponse.body());
					Assertions.assertTrue(listResponse.body().contains(
							"\"name\":\"catalog.search\""), listResponse.body());
					Assertions.assertTrue(listResponse.body().contains(
							"\"query-text\""), listResponse.body());
					Assertions.assertTrue(listResponse.body().contains(
							"\"x-mcp-header\":\"Tenant\""),
							listResponse.body());
					Assertions.assertEquals(0, providedInstances.get());
					Assertions.assertEquals(0,
							handlerInterceptorInvocations.get());

					HttpResponse<String> promptListResponse = send(port,
							"prompts/list",
							"{\"jsonrpc\":\"2.0\",\"id\":\"annotated-prompt-list\","
									+ "\"method\":\"prompts/list\",\"params\":{\"_meta\":{"
									+ "\"io.modelcontextprotocol/protocolVersion\":\""
									+ PROTOCOL_VERSION + "\","
									+ "\"io.modelcontextprotocol/clientCapabilities\":{}}}}");
					Assertions.assertEquals(200, promptListResponse.statusCode(),
							promptListResponse.body());
					Assertions.assertTrue(promptListResponse.body().contains(
							"\"name\":\"catalog.compose\""),
							promptListResponse.body());
					Assertions.assertTrue(promptListResponse.body().contains(
							"\"name\":\"subject\""),
							promptListResponse.body());
					Assertions.assertEquals(0, providedInstances.get());
					Assertions.assertEquals(0,
							handlerInterceptorInvocations.get());

					int admissionsBeforeMismatch = admissionInvocations.get();
					Assertions.assertEquals(2, admissionsBeforeMismatch);
					HttpResponse<String> mismatchResponse = sendToolCall(port,
							"other", toolCallBody("annotated-mismatch"));
					Assertions.assertEquals(400, mismatchResponse.statusCode(),
							mismatchResponse.body());
					Assertions.assertTrue(mismatchResponse.body().contains(
							"\"id\":\"annotated-mismatch\""),
							mismatchResponse.body());
					Assertions.assertTrue(mismatchResponse.body().contains(
							"\"code\":-32020"), mismatchResponse.body());
					Assertions.assertEquals(admissionsBeforeMismatch,
							admissionInvocations.get());
					Assertions.assertEquals(0, providedInstances.get());
					Assertions.assertNull(System.getProperty(INITIALIZED_PROPERTY));
					Assertions.assertEquals(0,
							endpointLimiterInvocations.get());
					Assertions.assertEquals(0, toolLimiterInvocations.get());
					Assertions.assertEquals(0, fallbackLimiterInvocations.get());
					Assertions.assertEquals(0,
							handlerInterceptorInvocations.get());

					HttpResponse<String> callResponse = sendToolCall(port,
							"needle", toolCallBody("annotated-call"));
					Assertions.assertEquals(200, callResponse.statusCode(),
							callResponse.body());
					Assertions.assertTrue(callResponse.body().contains(
							"\"identifier\":\"needle\""), callResponse.body());
					Assertions.assertTrue(callResponse.body().contains(
							"\"limit\":3"), callResponse.body());
					Assertions.assertTrue(callResponse.body().contains(
							"\"contextsPresent\":true"), callResponse.body());
					Assertions.assertEquals(1, providedInstances.get());
					Assertions.assertEquals("true",
							System.getProperty(INITIALIZED_PROPERTY));
					Assertions.assertEquals(0,
							endpointLimiterInvocations.get());
					Assertions.assertEquals(1, toolLimiterInvocations.get());
					Assertions.assertEquals(0,
							fallbackLimiterInvocations.get());
					Assertions.assertEquals(1,
							handlerInterceptorInvocations.get());
					Assertions.assertEquals(admissionsBeforeMismatch + 1,
							admissionInvocations.get());

					HttpResponse<String> promptResponse = send(port, "prompts/get",
							"{\"jsonrpc\":\"2.0\",\"id\":\"annotated-prompt\","
									+ "\"method\":\"prompts/get\",\"params\":{\"_meta\":{"
									+ "\"io.modelcontextprotocol/protocolVersion\":\""
									+ PROTOCOL_VERSION + "\","
									+ "\"io.modelcontextprotocol/clientCapabilities\":{}},"
									+ "\"name\":\"catalog.compose\",\"arguments\":{"
									+ "\"subject\":\"needle\"}}}");
					Assertions.assertEquals(200, promptResponse.statusCode(),
							promptResponse.body());
					Assertions.assertTrue(promptResponse.body().contains(
							"\"text\":\"needle|default|true\""),
							promptResponse.body());
					Assertions.assertEquals(2, providedInstances.get());
					Assertions.assertEquals(1, toolLimiterInvocations.get());
					Assertions.assertEquals(0,
							fallbackLimiterInvocations.get());
					Assertions.assertEquals(2,
							handlerInterceptorInvocations.get());
					Assertions.assertEquals(admissionsBeforeMismatch + 2,
							admissionInvocations.get());
				} finally {
					server.stop();
				}
			}
		} finally {
			if (previousProperty == null)
				System.clearProperty(INITIALIZED_PROPERTY);
			else
				System.setProperty(INITIALIZED_PROPERTY, previousProperty);
		}
	}

	private static void compile(@NonNull Path source,
			@NonNull Path classes, @NonNull Path generated) throws IOException {
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		Assertions.assertNotNull(compiler);
		StringWriter diagnostics = new StringWriter();
		try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(
				null, null, StandardCharsets.UTF_8)) {
			String classpath = classes + System.getProperty("path.separator")
					+ System.getProperty("java.class.path");
			JavaCompiler.CompilationTask task = compiler.getTask(diagnostics,
					fileManager, null, List.of("--release", "17", "-parameters",
						"-Asoklet.cacheMode=none", "-classpath", classpath,
						"-d", classes.toString(), "-s", generated.toString()),
					null, fileManager.getJavaFileObjects(source));
			task.setProcessors(List.of(new SokletProcessor()));
			Assertions.assertTrue(Boolean.TRUE.equals(task.call()),
					diagnostics.toString());
		}
	}

	private static McpServer.@NonNull Builder serverBuilder(
			@NonNull McpEndpointRegistry registry,
			@NonNull McpRateLimiter fallbackToolLimiter,
			@NonNull McpAdmissionController admissionController) {
		return McpServer.withPort(0)
				.host(LOOPBACK)
				.endpointRegistry(registry)
				.admissionController(admissionController)
				.toolRateLimiter(fallbackToolLimiter)
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(LOOPBACK));
	}

	@NonNull
	private static HttpResponse<String> send(int port, @NonNull String method,
			@NonNull String body) throws IOException, InterruptedException {
		return send(port, method, body, Map.of());
	}

	@NonNull
	private static HttpResponse<String> sendToolCall(int port,
			@NonNull String tenant, @NonNull String body)
			throws IOException, InterruptedException {
		return send(port, "tools/call", body, Map.of(
				"Mcp-Name", "catalog.search",
				"Mcp-Param-Tenant", tenant));
	}

	@NonNull
	private static HttpResponse<String> send(int port, @NonNull String method,
			@NonNull String body,
			@NonNull Map<@NonNull String, @NonNull String> additionalHeaders)
			throws IOException, InterruptedException {
		HttpRequest.Builder request = HttpRequest.newBuilder()
				.uri(URI.create("http://" + LOOPBACK + ":" + port
						+ "/catalog/mcp"))
				.timeout(Duration.ofSeconds(5))
				.header("Content-Type", "application/json; charset=UTF-8")
				.header("Accept", "application/json, text/event-stream")
				.header("MCP-Protocol-Version", PROTOCOL_VERSION)
				.header("Mcp-Method", method);
		if (method.equals("prompts/get"))
			request.header("Mcp-Name", "catalog.compose");
		additionalHeaders.forEach(request::header);
		return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
				.build().send(request.POST(HttpRequest.BodyPublishers.ofString(body,
						StandardCharsets.UTF_8)).build(),
						HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	@NonNull
	private static String toolCallBody(@NonNull String requestId) {
		return "{\"jsonrpc\":\"2.0\",\"id\":\"" + requestId + "\","
				+ "\"method\":\"tools/call\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}},"
				+ "\"name\":\"catalog.search\",\"arguments\":{"
				+ "\"query-text\":\"needle\",\"limit\":3}}}";
	}

	@NonNull
	private static String endpointSource() {
		return """
				package example;

				import com.soklet.CancelationToken;
				import com.soklet.McpInvocationFeatures;
				import com.soklet.McpProgressReporter;
				import com.soklet.McpPromptMessage;
				import com.soklet.McpPromptOutput;
				import com.soklet.McpRequestContext;
				import com.soklet.McpTextContent;
				import com.soklet.annotation.McpHeader;
				import com.soklet.annotation.McpPrompt;
				import com.soklet.annotation.McpPromptArgument;
				import com.soklet.annotation.McpServerEndpoint;
				import com.soklet.annotation.McpTool;
				import com.soklet.annotation.McpToolArgument;
				import java.lang.annotation.ElementType;
				import java.lang.annotation.Target;
				import java.util.List;
				import java.util.Optional;

				@McpServerEndpoint(
				    path = "/catalog/mcp",
				    name = "catalog",
				    version = "4.0.0-SNAPSHOT",
				    title = "Catalog server",
				    description = "Generated endpoint",
				    websiteUrl = "https://example.com/catalog",
				    instructions = "Use catalog.search",
				    toolRateLimiter = "catalog-endpoint")
				public final class CatalogEndpoint {
				  @Target(ElementType.TYPE_USE)
				  private @interface InternalMarker {}

				  static {
				    System.setProperty(
				        "com.soklet.tests.generated-endpoint-initialized", "true");
				  }

				  public CatalogEndpoint() {}

				  @McpTool(
				      name = "catalog.search",
				      title = "Catalog search",
				      description = "Searches the catalog",
				      rateLimiter = "catalog-tool",
				      mirrorStructuredContentAsText = false)
				  public SearchResult search(
				      McpRequestContext request,
				      CancelationToken cancelationToken,
				      @McpToolArgument(
				          name = "query-text",
				          title = "Search query",
				          description = "Text to search for")
				      @McpHeader("Tenant") @InternalMarker String toString,
				      @McpToolArgument Optional<Integer> limit,
				      Optional<McpProgressReporter> progressReporter,
				      McpInvocationFeatures features) {
					return new SearchResult(
					    List.of(new SearchItem(toString, limit.orElse(-1))),
					    request != null && features != null
					        && cancelationToken
					            == features.require(CancelationToken.class)
					        && progressReporter.equals(
					            features.find(McpProgressReporter.class)));
				  }

				  @McpPrompt(
				      name = "catalog.compose",
				      title = "Catalog composer",
				      description = "Builds a catalog prompt")
				  public McpPromptOutput compose(
				      McpRequestContext request,
				      CancelationToken cancelationToken,
				      @McpPromptArgument(
				          name = "subject",
				          title = "Prompt subject",
				          description = "Subject to discuss") String subject,
				      @McpPromptArgument Optional<String> tone,
				      Optional<McpProgressReporter> progressReporter,
				      McpInvocationFeatures features) {
				    return McpPromptOutput.fromMessages(
				        McpPromptMessage.fromUserContent(
				            McpTextContent.fromText(subject + "|"
				                + tone.orElse("default") + "|"
				                + (request != null && features != null
				                    && cancelationToken
				                        == features.require(CancelationToken.class)
				                    && progressReporter.equals(
				                        features.find(McpProgressReporter.class))))));
				  }

				  public record SearchResult(
				      List<SearchItem> items, boolean contextsPresent) {}
				  public record SearchItem(String identifier, int limit) {}
				}
				""";
	}
}
