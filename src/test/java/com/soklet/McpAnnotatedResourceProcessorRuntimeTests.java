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
import org.jspecify.annotations.Nullable;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * End-to-end coverage from annotated resource source through generated public
 * endpoint adapters and application handler invocation.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class McpAnnotatedResourceProcessorRuntimeTests {
	@Test
	void generatedProviderPreservesResourceContractsAndInvocationBindings(
			@TempDir Path temporaryDirectory) throws Exception {
		Path sourceDirectory = temporaryDirectory.resolve("src/example");
		Path classDirectory = temporaryDirectory.resolve("classes");
		Path generatedDirectory = temporaryDirectory.resolve("generated");
		Files.createDirectories(sourceDirectory);
		Files.createDirectories(classDirectory);
		Files.createDirectories(generatedDirectory);
		Path endpointSource = sourceDirectory.resolve("ResourceEndpoint.java");
		Files.writeString(endpointSource, endpointSource(), StandardCharsets.UTF_8);

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
		Assertions.assertFalse(generatedSource.contains(
				"com.soklet.InstanceProvider"), generatedSource);
		Assertions.assertTrue(generatedSource.contains(
				"java.util.function.Function<com.soklet.McpRequestContext, example.ResourceEndpoint> instanceResolver"),
				generatedSource);
		Assertions.assertTrue(generatedSource.contains(
				"McpResourceRegistration.withUriAndName"), generatedSource);
		Assertions.assertTrue(generatedSource.contains(
				"McpResourceRegistration.withUriTemplateAndName"), generatedSource);
		Assertions.assertTrue(generatedSource.contains(
				"McpCompleteResult.fromResourceOutput"), generatedSource);
		Assertions.assertTrue(generatedSource.contains(
				"endpointBuilder.resourceListHandler"), generatedSource);
		Assertions.assertTrue(generatedSource.contains(
				"resource.getUriTemplateVariables().get(\"identifier\")"),
				generatedSource);
		Assertions.assertTrue(generatedSource.contains(
				"resource.getUriTemplateVariables().get(\"section\")"),
				generatedSource);
		Assertions.assertTrue(generatedSource.contains(
				"exactResource(resource, features.getCancelationToken(), features.getProgressReporter(), features)"),
				generatedSource);
		Assertions.assertTrue(generatedSource.contains(
				"templateResource(request, java.util.Objects.requireNonNull(resource.getUriTemplateVariables().get(\"identifier\")), resource, java.util.Objects.requireNonNull(resource.getUriTemplateVariables().get(\"section\")), features.getCancelationToken(), features.getProgressReporter(), features)"),
				generatedSource);
		Assertions.assertTrue(generatedSource.contains(
				"resources(features, features.getCancelationToken(), list, features.getProgressReporter(), request)"),
				generatedSource);

		try (URLClassLoader classLoader = new URLClassLoader(
				new URL[] { classDirectory.toUri().toURL() },
				McpAnnotatedResourceProcessorRuntimeTests.class.getClassLoader())) {
			Class<?> endpointClass = Class.forName("example.ResourceEndpoint",
					false, classLoader);
			AtomicInteger providedInstances = new AtomicInteger();
			InstanceProvider instanceProvider = new InstanceProvider() {
				@Override
				@NonNull
				public <T> T provide(@NonNull Class<T> instanceClass) {
					Assertions.assertSame(endpointClass, instanceClass);
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
					endpointClass);
			McpEndpoint endpoint = registry.getEndpoints().get(0);
			Assertions.assertEquals(0, providedInstances.get());
			Assertions.assertEquals(Duration.ofMillis(300), endpoint
					.getResourceListCachePolicy().getTimeToLive());
			Assertions.assertEquals(McpCacheScope.PUBLIC, endpoint
					.getResourceListCachePolicy().getScope());
			Assertions.assertEquals(Duration.ofMillis(450), endpoint
					.getResourceTemplateListCachePolicy().getTimeToLive());
			Assertions.assertEquals(McpCacheScope.PRIVATE, endpoint
					.getResourceTemplateListCachePolicy().getScope());
			Assertions.assertEquals(2, endpoint.getResources().size());

			McpResourceRegistration exact = endpoint.getResources().stream()
					.filter(resource -> resource.getAddressType()
							== McpResourceAddressType.URI)
					.findFirst().orElseThrow();
			Assertions.assertEquals(URI.create("test://catalog/static"),
					exact.getUri().orElseThrow());
			Assertions.assertEquals("Static catalog", exact.getTitle().orElseThrow());
			Assertions.assertEquals("text/plain", exact.getMimeType().orElseThrow());
			Assertions.assertEquals(7, exact.getSizeInBytes().orElseThrow());
			Assertions.assertEquals(Duration.ofMillis(125),
					exact.getCachePolicy().getTimeToLive());
			Assertions.assertEquals(McpCacheScope.PUBLIC,
					exact.getCachePolicy().getScope());

			McpResourceRegistration template = endpoint.getResources().stream()
					.filter(resource -> resource.getAddressType()
							== McpResourceAddressType.URI_TEMPLATE)
					.findFirst().orElseThrow();
			Assertions.assertEquals(
					"test://catalog/item/{identifier}/{section}",
					template.getUriTemplate().orElseThrow());
			Assertions.assertTrue(template.getSizeInBytes().isEmpty());
			Assertions.assertEquals(Duration.ofMillis(250),
					template.getCachePolicy().getTimeToLive());

			SimulatorConfig simulatorConfig = SimulatorConfig.builder()
					.mcpServer(0, registry,
							McpAdmissionController.acceptAllInstance(), builder -> builder
									.host("127.0.0.1")
									.corsAuthorizer(
											CorsAuthorizer.acceptAllInstance())
									.allowedHosts(Set.of("127.0.0.1")))
					.resourceMethodResolver(
							ResourceMethodResolver.fromMethods(Set.of()))
					.instanceProvider(instanceProvider)
					.build();
			SokletSimulator.run(simulatorConfig, simulator -> {
				String exactBody = responseBody(simulator, "resource-exact",
						"resources/read", "test://catalog/static",
						",\"uri\":\"test://catalog/static\"");
				Assertions.assertTrue(exactBody.contains(
						"\"text\":\"exact|test://catalog/static|true\""),
						exactBody);

				String templateBody = responseBody(simulator, "resource-template",
						"resources/read", "test://catalog/item/42/summary",
						",\"uri\":\"test://catalog/item/42/summary\"");
				Assertions.assertTrue(templateBody.contains(
						"\"text\":\"42|summary|test://catalog/item/42/summary|true|true\""),
						templateBody);

				String listBody = responseBody(simulator, "resource-list",
						"resources/list", null, ",\"cursor\":\"cursor-1\"");
				Assertions.assertTrue(listBody.contains(
						"\"nextCursor\":\"cursor-1-next\""), listBody);
				Assertions.assertTrue(listBody.contains(
						"\"uri\":\"test://catalog/static\""), listBody);
			});
			Assertions.assertEquals(3, providedInstances.get());
		}
	}

	@NonNull
	private static CancelationToken uncanceledToken() {
		return new CancelationToken() {
			@Override
			@NonNull
			public Boolean isCanceled() {
				return false;
			}

			@Override
			@NonNull
			public Optional<@NonNull StreamTerminationReason>
					getCancelationReason() {
				return Optional.empty();
			}

			@Override
			@NonNull
			public Optional<@NonNull Throwable> getCancelationCause() {
				return Optional.empty();
			}

			@Override
			@NonNull
			public AutoCloseable onCancel(@NonNull Runnable callback) {
				return () -> {};
			}
		};
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

	private static McpResourceReadContext readContext(@NonNull URI uri,
			@NonNull Map<@NonNull String, @NonNull String> variables) {
		return new McpResourceReadContext() {
			@Override
			@NonNull
			public URI getUri() {
				return uri;
			}

			@Override
			@NonNull
			public Map<@NonNull String, @NonNull String>
					getUriTemplateVariables() {
				return Map.copyOf(variables);
			}
		};
	}

	private static McpResourceListContext listContext(@NonNull String cursor,
			@NonNull List<@NonNull McpResourceDescriptor> resources) {
		return new McpResourceListContext() {
			@Override
			@NonNull
			public Optional<@NonNull String> getCursor() {
				return Optional.of(cursor);
			}

			@Override
			@NonNull
			public List<@NonNull McpResourceDescriptor>
					getRegisteredResourceDescriptors() {
				return List.copyOf(resources);
			}
		};
	}

	private static McpRequestContext requestContext(@NonNull McpEndpoint endpoint,
			@NonNull String method) {
		return new McpRequestContext() {
			@Override public Request getRequest() {
				throw new UnsupportedOperationException();
			}
			@Override public McpEndpoint getEndpoint() {
				return endpoint;
			}
			@Override public Map<String, String> getEndpointPathParameters() {
				return Map.of();
			}
			@Override public String getJsonRpcMethod() {
				return method;
			}
			@Override public Optional<McpRequestId> getRequestId() {
				return Optional.of(McpRequestId.fromString("resource-test"));
			}
			@Override public String getProtocolVersion() {
				return "2026-07-28";
			}
			@Override public Optional<String> getOperationName() {
				return Optional.empty();
			}
			@Override public Optional<McpImplementation> getClientInfo() {
				return Optional.empty();
			}
			@Override public McpClientCapabilities getClientCapabilities() {
				throw new UnsupportedOperationException();
			}
			@Override public McpJsonObject getRequestMetadata() {
				return McpJsonObject.emptyInstance();
			}
			@Override public McpInputResponses getInputResponses() {
				return McpInputResponses.emptyInstance();
			}
			@Override public Optional<McpJsonValue> getFrameworkRequestState() {
				return Optional.empty();
			}
			@Override public Optional<String> getApplicationRequestState() {
				return Optional.empty();
			}
			@Override
			public Optional<McpLogLevel> getLogLevel() {
				return Optional.empty();
			}
			@Override public Optional<TraceContext> getTraceContext() {
				return Optional.empty();
			}
			@Override public Map<String, String> getBaggage() {
				return Map.of();
			}
			@Override public McpAdmissionIdentity getAdmissionIdentity() {
				return McpAdmissionIdentity.anonymousInstance();
			}
		};
	}

	@NonNull
	private static String responseBody(@NonNull Simulator simulator,
			@NonNull String requestId, @NonNull String method,
			@Nullable String operationName, @NonNull String parameters) {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + requestId
				+ "\",\"method\":\"" + method + "\",\"params\":{"
				+ "\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{},"
				+ "\"progressToken\":\"" + requestId + "-progress\"}"
				+ parameters + "}}";
		Map<String, Set<String>> headers = new LinkedHashMap<>();
		headers.put("Host", Set.of("127.0.0.1:0"));
		headers.put("Content-Type", Set.of("application/json; charset=UTF-8"));
		headers.put("Accept", Set.of("application/json, text/event-stream"));
		headers.put("MCP-Protocol-Version", Set.of("2026-07-28"));
		headers.put("Mcp-Method", Set.of(method));
		if (operationName != null)
			headers.put("Mcp-Name", Set.of(operationName));
		McpSimulation simulation = simulator.startMcpRequest(Request
				.withPath(HttpMethod.POST, "/resources/mcp")
				.headers(headers)
				.body(body.getBytes(StandardCharsets.UTF_8))
				.build());
		McpSimulationResponse response;
		try {
			response = simulation.awaitResponse(Duration.ofSeconds(5))
					.orElseThrow(() -> new AssertionError(
							"Timed out awaiting MCP simulator response."));
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError(exception);
		}
		byte[] responseBytes = response.getBody().orElseThrow();
		String responseBody = new String(responseBytes, StandardCharsets.UTF_8);
		Assertions.assertEquals(200, response.getStatusCode(), responseBody);
		Assertions.assertEquals(McpSimulationBodyType.JSON,
				response.getBodyType());
		return responseBody;
	}

	@NonNull
	private static String endpointSource() {
		return """
				package example;

				import com.soklet.CancelationToken;
				import com.soklet.McpCacheScope;
				import com.soklet.McpCompleteResult;
				import com.soklet.McpInvocationFeatures;
				import com.soklet.McpProgressReporter;
				import com.soklet.McpRequestContext;
				import com.soklet.McpResourceListContext;
				import com.soklet.McpResourceOutput;
				import com.soklet.McpResourcePage;
				import com.soklet.McpResourceReadContext;
				import com.soklet.McpTextResourceContents;
				import com.soklet.annotation.McpResourceList;
				import com.soklet.annotation.McpResource;
				import com.soklet.annotation.McpResourceUriParameter;
				import com.soklet.annotation.McpServerEndpoint;
				import java.time.Duration;

				@McpServerEndpoint(
				    path = "/resources/mcp",
				    name = "resource-catalog",
				    version = "4.0.0",
				    resourceListCacheTimeToLiveInMilliseconds = 300,
				    resourceListCacheScope = McpCacheScope.PUBLIC,
				    resourceTemplateListCacheTimeToLiveInMilliseconds = 450)
				public final class ResourceEndpoint {
				  public ResourceEndpoint() {}

				  @McpResource(
				      uri = "test://catalog/static",
				      name = "static-catalog",
				      title = "Static catalog",
				      description = "Static catalog contents",
				      mimeType = "text/plain",
				      sizeInBytes = 7,
				      cacheTimeToLiveInMilliseconds = 125,
				      cacheScope = McpCacheScope.PUBLIC)
				  public McpResourceOutput exactResource(
				      McpResourceReadContext resource,
				      CancelationToken cancelationToken,
				      java.util.Optional<McpProgressReporter> progressReporter,
				      McpInvocationFeatures features) {
				    return McpResourceOutput.withContent(
				        McpTextResourceContents.withUriAndText(
				            resource.getUri(), "exact|" + resource.getUri() + "|"
				                + (features != null
				                    && cancelationToken
				                        == features.require(CancelationToken.class)
				                    && progressReporter.orElseThrow()
				                        == features.require(McpProgressReporter.class)))
				                .build())
				        .build();
				  }

				  @McpResource(
				      uri = "test://catalog/item/{identifier}/{section}",
				      name = "catalog-item",
				      cacheTimeToLiveInMilliseconds = 250)
				  public McpCompleteResult templateResource(
				      McpRequestContext request,
				      @McpResourceUriParameter String identifier,
				      McpResourceReadContext resource,
				      @McpResourceUriParameter(name = "section") String part,
				      CancelationToken cancelationToken,
				      java.util.Optional<McpProgressReporter> progressReporter,
				      McpInvocationFeatures features) {
				    McpResourceOutput output = McpResourceOutput.withContent(
				        McpTextResourceContents.withUriAndText(
				            resource.getUri(), identifier + "|" + part + "|"
				                + resource.getUri() + "|" + (request != null) + "|"
				                + (features != null
				                    && cancelationToken
				                        == features.require(CancelationToken.class)
				                    && progressReporter.orElseThrow()
				                        == features.require(McpProgressReporter.class)))
				                .build())
				        .build();
				    return McpCompleteResult.fromResourceOutput(output);
				  }

				  @McpResourceList
				  public McpResourcePage resources(
				      McpInvocationFeatures features,
				      CancelationToken cancelationToken,
				      McpResourceListContext list,
				      java.util.Optional<McpProgressReporter> progressReporter,
				      McpRequestContext request) {
				    if (features == null || request == null
				        || cancelationToken != features.require(CancelationToken.class)
				        || progressReporter.orElseThrow()
				            != features.require(McpProgressReporter.class))
				      throw new IllegalStateException("Missing injected context");
				    return McpResourcePage.builder()
				        .addResources(list.getRegisteredResourceDescriptors())
				        .nextCursor(list.getCursor().orElse("missing") + "-next")
				        .cacheTimeToLiveOverride(Duration.ofMillis(25))
				        .build();
				  }
				}
				""";
	}
}
