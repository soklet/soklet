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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
				"exactResource(resource, features.require(com.soklet.CancelationToken.class), features.find(com.soklet.McpProgressReporter.class), features)"),
				generatedSource);
		Assertions.assertTrue(generatedSource.contains(
				"templateResource(request, java.util.Objects.requireNonNull(resource.getUriTemplateVariables().get(\"identifier\")), resource, java.util.Objects.requireNonNull(resource.getUriTemplateVariables().get(\"section\")), features.require(com.soklet.CancelationToken.class), features.find(com.soklet.McpProgressReporter.class), features)"),
				generatedSource);
		Assertions.assertTrue(generatedSource.contains(
				"resources(features, features.require(com.soklet.CancelationToken.class), list, features.find(com.soklet.McpProgressReporter.class), request)"),
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
			Assertions.assertEquals(7, exact.getSize().orElseThrow());
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
			Assertions.assertTrue(template.getSize().isEmpty());
			Assertions.assertEquals(Duration.ofMillis(250),
					template.getCachePolicy().getTimeToLive());

			CancelationToken cancelationToken = uncanceledToken();
			McpProgressReporter progressReporter = update -> {};
			McpInvocationFeatures features = McpInvocationFeatures.fromFeatures(
					Map.of(CancelationToken.class, cancelationToken,
							McpProgressReporter.class, progressReporter));
			McpRequestContext readRequest = requestContext(endpoint,
					"resources/read");
			McpOperationResult exactResult = exact.getHandler().handle(readRequest,
					readContext(URI.create("test://catalog/static"), Map.of()),
					features);
			McpResourceOutput exactOutput = Assertions.assertInstanceOf(
					McpResourceOutput.class, Assertions.assertInstanceOf(
							McpCompleteResult.class, exactResult).getPayload());
			McpTextResourceContents exactContents = Assertions.assertInstanceOf(
					McpTextResourceContents.class, exactOutput.getContents().get(0));
			Assertions.assertEquals("exact|test://catalog/static|true",
					exactContents.getText());

			URI templateUri = URI.create("test://catalog/item/42/summary");
			McpOperationResult templateResult = template.getHandler().handle(
					readRequest, readContext(templateUri,
							Map.of("identifier", "42", "section", "summary")),
					features);
			McpResourceOutput templateOutput = Assertions.assertInstanceOf(
					McpResourceOutput.class, Assertions.assertInstanceOf(
							McpCompleteResult.class, templateResult).getPayload());
			McpTextResourceContents templateContents = Assertions.assertInstanceOf(
					McpTextResourceContents.class,
					templateOutput.getContents().get(0));
			Assertions.assertEquals(
					"42|summary|test://catalog/item/42/summary|true|true",
					templateContents.getText());

			McpResourceDescriptor exactDescriptor = McpResourceDescriptor
					.withUriAndName(exact.getUri().orElseThrow(), exact.getName())
					.build();
			McpResourceListContext listContext = listContext("cursor-1",
					List.of(exactDescriptor));
			McpResourcePage page = endpoint.getResourceListHandler().orElseThrow()
					.handle(requestContext(endpoint, "resources/list"), listContext,
							features);
			Assertions.assertEquals(List.of(exactDescriptor), page.getResources());
			Assertions.assertEquals("cursor-1-next",
					page.getNextCursor().orElseThrow());
			Assertions.assertEquals(Duration.ofMillis(25),
					page.getCacheTimeToLiveOverride().orElseThrow());
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
			@Override
			public Optional<McpLogLevel> getDeprecatedLogLevel() {
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
				    resourceListCacheTtlMs = 300,
				    resourceListCacheScope = McpCacheScope.PUBLIC,
				    resourceTemplateListCacheTtlMs = 450)
				public final class ResourceEndpoint {
				  public ResourceEndpoint() {}

				  @McpResource(
				      uri = "test://catalog/static",
				      name = "static-catalog",
				      title = "Static catalog",
				      description = "Static catalog contents",
				      mimeType = "text/plain",
				      size = 7,
				      cacheTtlMs = 125,
				      cacheScope = McpCacheScope.PUBLIC)
				  public McpResourceOutput exactResource(
				      McpResourceReadContext resource,
				      CancelationToken cancelationToken,
				      java.util.Optional<McpProgressReporter> progressReporter,
				      McpInvocationFeatures features) {
				    return McpResourceOutput.builder()
				        .content(McpTextResourceContents.withUriAndText(
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
				      cacheTtlMs = 250)
				  public McpCompleteResult templateResource(
				      McpRequestContext request,
				      @McpResourceUriParameter String identifier,
				      McpResourceReadContext resource,
				      @McpResourceUriParameter("section") String part,
				      CancelationToken cancelationToken,
				      java.util.Optional<McpProgressReporter> progressReporter,
				      McpInvocationFeatures features) {
				    McpResourceOutput output = McpResourceOutput.builder()
				        .content(McpTextResourceContents.withUriAndText(
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
				        .resources(list.getRegisteredResourceDescriptors())
				        .nextCursor(list.getCursor().orElse("missing") + "-next")
				        .cacheTimeToLiveOverride(Duration.ofMillis(25))
				        .build();
				  }
				}
				""";
	}
}
