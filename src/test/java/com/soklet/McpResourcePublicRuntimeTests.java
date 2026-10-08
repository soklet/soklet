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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Black-box real-listener coverage for public MCP resource registrations.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Timeout(60)
public class McpResourcePublicRuntimeTests {
	private static final String LOOPBACK = "127.0.0.1";
	private static final String MCP_PATH = "/mcp";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final String JSON_MEDIA_TYPE = "application/json";
	private static final URI TEXT_URI = URI.create("test://static-text");
	private static final URI BINARY_URI = URI.create("test://static-binary");
	private static final URI SPECIAL_URI =
			URI.create("test://template/special/data");
	private static final String TEMPLATE_URI = "test://template/{id}/data";

	@Test
	public void staticCatalogsAndReadsUseThePublicPipeline() throws Exception {
		List<String> stages = Collections.synchronizedList(new ArrayList<>());
		AtomicInteger handlerInvocations = new AtomicInteger();
		AtomicInteger exactSpecialInvocations = new AtomicInteger();
		AtomicInteger templateInvocations = new AtomicInteger();
		AtomicInteger toolLimiterInvocations = new AtomicInteger();
		AtomicReference<McpRequestContext> exactRequest = new AtomicReference<>();
		AtomicReference<McpResourceReadContext> templateRead = new AtomicReference<>();

		McpResourceRegistration text = McpResourceRegistration
				.withUriAndName(TEXT_URI, "Static text", java.util.Set.of(com.soklet.McpProtocolVersion.V2026_07_28))
				.handler((request, resource, features) -> {
					stages.add("handler:" + resource.getUri());
					handlerInvocations.incrementAndGet();
					return completeText(resource.getUri(), "static text", "text/plain");
				})
				.title("Static text resource")
				.description("A deterministic text resource")
				.mimeType("text/plain")
				.sizeInBytes(11L)
				.cachePolicy(McpCachePolicy.fromPublicTimeToLive(
						Duration.ofMillis(50)))
				.metadata(McpJsonObject.builder().put("kind", "text").build())
				.build();
		McpResourceRegistration binary = McpResourceRegistration
				.withUriAndName(BINARY_URI, "Static binary", java.util.Set.of(com.soklet.McpProtocolVersion.V2026_07_28))
				.handler((request, resource, features) -> {
					stages.add("handler:" + resource.getUri());
					handlerInvocations.incrementAndGet();
					return McpCompleteResult.fromResourceOutput(McpResourceOutput.withContent(McpBlobResourceContents.withUriAndData(
									resource.getUri(), new byte[] { 1, 2, 3 })
									.mimeType("application/octet-stream")
									.build())
							.build());
				})
				.mimeType("application/octet-stream")
				.sizeInBytes(3L)
				.cachePolicy(McpCachePolicy.fromPrivateTimeToLive(
						Duration.ofMillis(60)))
				.build();
		McpResourceRegistration exactSpecial = McpResourceRegistration
				.withUriAndName(SPECIAL_URI, "Special exact resource", java.util.Set.of(com.soklet.McpProtocolVersion.V2026_07_28))
				.handler((request, resource, features) -> {
					stages.add("handler:" + resource.getUri());
					handlerInvocations.incrementAndGet();
					exactSpecialInvocations.incrementAndGet();
					exactRequest.set(request);
					return completeText(resource.getUri(), "exact-special", "text/plain");
				})
				.cachePolicy(McpCachePolicy.fromPrivateTimeToLive(
						Duration.ofMillis(70)))
				.build();
		McpResourceRegistration template = McpResourceRegistration
				.withUriTemplateAndName(TEMPLATE_URI, "Template resource", java.util.Set.of(com.soklet.McpProtocolVersion.V2026_07_28))
				.handler((request, resource, features) -> {
					stages.add("handler:" + resource.getUri());
					handlerInvocations.incrementAndGet();
					templateInvocations.incrementAndGet();
					templateRead.set(resource);
					return completeText(resource.getUri(), "template:"
							+ resource.getUriTemplateVariables().get("id"),
							"text/plain");
				})
				.description("A Level-1 URI template")
				.mimeType("text/plain")
				.cachePolicy(McpCachePolicy.fromPublicTimeToLive(
						Duration.ofMillis(80)))
				.build();
		McpEndpoint endpoint = endpointBuilder()
				.resourceRegistrations(java.util.List.of(text, binary, exactSpecial, template))
				.resourceListCachePolicy(McpCachePolicy.fromPublicTimeToLive(
						Duration.ofMillis(100)))
				.resourceTemplateListCachePolicy(
						McpCachePolicy.fromPrivateTimeToLive(Duration.ofMillis(200)))
				.build();
		McpServer server = McpServer.withPort(0).endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint))).admissionController(context -> {
					stages.add("admission:"
							+ context.getOperationName().orElse("-"));
					return McpAdmissionDecision.accepted();
				})
				.host(LOOPBACK)
				.requestRateLimiter(context -> {
					Assertions.assertEquals(McpRateLimitTarget.REQUEST,
							context.getTarget());
					stages.add("request:"
							+ context.getOperationName().orElse("-"));
					return McpRateLimitDecision.allowed();
				})
				.toolRateLimiter(context -> {
					toolLimiterInvocations.incrementAndGet();
					return McpRateLimitDecision.allowed();
				})
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(LOOPBACK))
				.build();
		Soklet soklet = managedSoklet(server);

		try {
			soklet.start();
			int port = server.getDiagnostics().getBoundAddress()
					.orElseThrow().getPort();

			HttpResponse<String> discover = send(port,
					request("discover", "server/discover", ""),
					"server/discover");
			assertSuccess(discover, "discover");
			assertContains(discover.body(), "\"resources\":{");
			stages.clear();

			HttpResponse<String> resources = send(port,
					request("resources", "resources/list", ""),
					"resources/list");
			assertSuccess(resources, "resources");
			String resourcesBody = resources.body();
			assertContains(resourcesBody, "\"ttlMs\":100");
			assertContains(resourcesBody, "\"cacheScope\":\"public\"");
			assertContains(resourcesBody, "\"uri\":\"" + TEXT_URI + "\"");
			assertContains(resourcesBody, "\"uri\":\"" + BINARY_URI + "\"");
			assertContains(resourcesBody, "\"uri\":\"" + SPECIAL_URI + "\"");
			assertContains(resourcesBody, "\"size\":11");
			assertContains(resourcesBody, "\"kind\":\"text\"");
			Assertions.assertFalse(resourcesBody.contains(TEMPLATE_URI), resourcesBody);
			Assertions.assertFalse(resourcesBody.contains("\"nextCursor\""),
					resourcesBody);
			assertOrdered(resourcesBody, TEXT_URI.toString(), BINARY_URI.toString(),
					SPECIAL_URI.toString());
			Assertions.assertEquals(0, handlerInvocations.get());
			Assertions.assertEquals(List.of("admission:-", "request:-"), stages);

			stages.clear();
			HttpResponse<String> templates = send(port,
					request("templates", "resources/templates/list", ""),
					"resources/templates/list");
			assertSuccess(templates, "templates");
			String templatesBody = templates.body();
			assertContains(templatesBody, "\"ttlMs\":200");
			assertContains(templatesBody, "\"cacheScope\":\"private\"");
			assertContains(templatesBody, "\"uriTemplate\":\""
					+ TEMPLATE_URI + "\"");
			Assertions.assertFalse(templatesBody.contains(
					"\"uri\":\"" + TEXT_URI + "\""), templatesBody);
			Assertions.assertEquals(List.of("admission:-", "request:-"), stages);

			stages.clear();
			HttpResponse<String> staticCursor = send(port,
					request("static-cursor", "resources/list", ",\"cursor\":\"\""),
					"resources/list");
			assertError(staticCursor, 400, -32602, "static-cursor");
			Assertions.assertTrue(stages.isEmpty(), stages.toString());

			stages.clear();
			HttpResponse<String> textRead = read(port, "read-text", TEXT_URI.toString());
			assertSuccess(textRead, "read-text");
			assertContains(textRead.body(), "\"text\":\"static text\"");
			assertContains(textRead.body(), "\"ttlMs\":50");
			assertContains(textRead.body(), "\"cacheScope\":\"public\"");
			Assertions.assertEquals(List.of("admission:" + TEXT_URI,
					"request:" + TEXT_URI, "handler:" + TEXT_URI), stages);

			stages.clear();
			HttpResponse<String> binaryRead = read(port, "read-binary",
					BINARY_URI.toString());
			assertSuccess(binaryRead, "read-binary");
			assertContains(binaryRead.body(), "\"blob\":\"AQID\"");
			assertContains(binaryRead.body(), "\"ttlMs\":60");
			assertContains(binaryRead.body(), "\"cacheScope\":\"private\"");

			HttpResponse<String> exactRead = read(port, "read-exact",
					SPECIAL_URI.toString());
			assertSuccess(exactRead, "read-exact");
			assertContains(exactRead.body(), "\"text\":\"exact-special\"");
			Assertions.assertEquals(1, exactSpecialInvocations.get());
			Assertions.assertEquals(0, templateInvocations.get());
				Assertions.assertEquals("resources/read",
						exactRequest.get().getJsonRpcMethod());
				Assertions.assertSame(endpoint, exactRequest.get().getEndpoint());
				HttpResponse<String> equivalentExactRead = read(port,
						"read-exact-equivalent", "TEST://TEMPLATE/special/data");
				assertSuccess(equivalentExactRead, "read-exact-equivalent");
				assertContains(equivalentExactRead.body(),
						"\"text\":\"exact-special\"");
				Assertions.assertEquals(2, exactSpecialInvocations.get());
				Assertions.assertEquals(0, templateInvocations.get());

				String cafeUri = "test://template/caf%c3%a9/data";
			HttpResponse<String> templateReadResponse = read(port, "read-template",
					cafeUri);
			assertSuccess(templateReadResponse, "read-template");
			assertContains(templateReadResponse.body(), "\"text\":\"template:café\"");
			assertContains(templateReadResponse.body(), "\"ttlMs\":80");
			assertContains(templateReadResponse.body(), "\"cacheScope\":\"public\"");
			Assertions.assertEquals(URI.create(cafeUri), templateRead.get().getUri());
			Assertions.assertEquals(Map.of("id", "café"),
					templateRead.get().getUriTemplateVariables());
			Assertions.assertEquals(1, templateInvocations.get());

			String encodedSlashUri = "test://template/a%2Fb/data";
			HttpResponse<String> encodedSlashRead = read(port,
					"read-template-encoded-slash", encodedSlashUri);
			assertSuccess(encodedSlashRead, "read-template-encoded-slash");
			assertContains(encodedSlashRead.body(), "\"text\":\"template:a/b\"");
			Assertions.assertEquals(Map.of("id", "a/b"),
					templateRead.get().getUriTemplateVariables());
			Assertions.assertEquals(2, templateInvocations.get());

			stages.clear();
			HttpResponse<String> rawSlashRead = read(port,
					"read-template-raw-slash", "test://template/a/b/data");
			assertError(rawSlashRead, 400, -32602, "read-template-raw-slash");
			Assertions.assertEquals(List.of("admission:test://template/a/b/data"), stages);
			Assertions.assertEquals(2, templateInvocations.get());

			stages.clear();
			HttpResponse<String> unknown = read(port, "read-unknown",
					"test://unknown-resource");
			assertError(unknown, 400, -32602, "read-unknown");
			assertContains(unknown.body(),
					"\"data\":{\"uri\":\"test://unknown-resource\"}");
			Assertions.assertEquals(List.of("admission:test://unknown-resource"), stages);
			Assertions.assertEquals(6, handlerInvocations.get());
			Assertions.assertEquals(0, toolLimiterInvocations.get());
		} finally {
			soklet.close();
		}
	}

	@Test
	public void dynamicListPreservesApplicationCursorsAndConfiguredBounds()
			throws Exception {
		AtomicInteger admissions = new AtomicInteger();
		AtomicInteger requestLimiterInvocations = new AtomicInteger();
		AtomicInteger toolLimiterInvocations = new AtomicInteger();
		AtomicInteger listHandlerInvocations = new AtomicInteger();
		List<Optional<String>> observedCursors =
				Collections.synchronizedList(new ArrayList<>());
		AtomicReference<McpResourceListContext> firstListContext =
				new AtomicReference<>();
		AtomicReference<McpRequestContext> firstRequestContext =
				new AtomicReference<>();

		McpResourceRegistration exact = McpResourceRegistration
				.withUriAndName(URI.create("test://registered"), "Registered", java.util.Set.of(com.soklet.McpProtocolVersion.V2026_07_28))
				.handler((request, resource, features) ->
						completeText(resource.getUri(), "registered", "text/plain"))
				.build();
		McpResourceRegistration template = McpResourceRegistration
				.withUriTemplateAndName("test://dynamic/{id}", "Dynamic", java.util.Set.of(com.soklet.McpProtocolVersion.V2026_07_28))
				.handler((request, resource, features) ->
						completeText(resource.getUri(), "dynamic", "text/plain"))
				.build();
		McpResourceListHandler listHandler = (request, list, features) -> {
			listHandlerInvocations.incrementAndGet();
			observedCursors.add(list.getCursor());
			if (firstListContext.compareAndSet(null, list))
				firstRequestContext.set(request);

			McpResourcePage.Builder page = McpResourcePage.builder();
			if (list.getCursor().isEmpty())
				return page.resourceDescriptors(list.getRegisteredResourceDescriptors())
						.metadata(McpJsonObject.builder().put("page", 1).build())
						.cacheTimeToLiveOverride(Duration.ofMillis(125))
						.nextCursor("世界")
						.build();

			return switch (list.getCursor().orElseThrow()) {
				case "" -> page.nextCursor("").build();
				case "世界" -> page.build();
				case "bad" -> throw new McpJsonRpcException(
						McpJsonRpcError.fromInvalidParameters(
								"The resource-list cursor is invalid.",
								McpJsonObject.builder().put("reason", "expired").build()));
				case "big" -> page.nextCursor("世界語").build();
				default -> throw new AssertionError("Unexpected cursor");
			};
		};
		McpEndpoint endpoint = endpointBuilder()
				.resourceRegistrations(java.util.List.of(exact, template))
				.resourceListHandler(listHandler, java.util.Set.of(com.soklet.McpProtocolVersion.V2026_07_28))
				.resourceListCachePolicy(McpCachePolicy.fromPrivateTimeToLive(
						Duration.ofMillis(500)))
				.build();
		McpServer server = McpServer.withPort(0).endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint))).admissionController(context -> {
					admissions.incrementAndGet();
					return McpAdmissionDecision.accepted();
				})
				.host(LOOPBACK)
				.maximumCursorSizeInBytes(8)
				.requestRateLimiter(context -> {
					Assertions.assertEquals(McpRateLimitTarget.REQUEST,
							context.getTarget());
					requestLimiterInvocations.incrementAndGet();
					return McpRateLimitDecision.allowed();
				})
				.toolRateLimiter(context -> {
					toolLimiterInvocations.incrementAndGet();
					return McpRateLimitDecision.allowed();
				})
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(LOOPBACK))
				.build();
		Soklet soklet = managedSoklet(server);

		try {
			soklet.start();
			int port = server.getDiagnostics().getBoundAddress()
					.orElseThrow().getPort();

			HttpResponse<String> first = send(port,
					request("first", "resources/list", ""), "resources/list");
			assertSuccess(first, "first");
			assertContains(first.body(), "\"uri\":\"test://registered\"");
			Assertions.assertFalse(first.body().contains("test://dynamic/{id}"),
					first.body());
			assertContains(first.body(), "\"nextCursor\":\"世界\"");
			assertContains(first.body(), "\"ttlMs\":125");
			assertContains(first.body(), "\"cacheScope\":\"private\"");
			assertContains(first.body(), "\"page\":1");
			McpResourceListContext capturedList = firstListContext.get();
			Assertions.assertEquals(List.of(URI.create("test://registered")),
					capturedList.getRegisteredResourceDescriptors().stream()
							.map(McpResourceDescriptor::getUri).toList());
			Assertions.assertThrows(UnsupportedOperationException.class,
					() -> capturedList.getRegisteredResourceDescriptors().clear());
			Assertions.assertEquals("resources/list",
					firstRequestContext.get().getJsonRpcMethod());
			Assertions.assertSame(endpoint, firstRequestContext.get().getEndpoint());

			HttpResponse<String> empty = send(port,
					request("empty", "resources/list", ",\"cursor\":\"\""),
					"resources/list");
			assertSuccess(empty, "empty");
			assertContains(empty.body(), "\"nextCursor\":\"\"");
			assertContains(empty.body(), "\"ttlMs\":500");
			assertContains(empty.body(), "\"cacheScope\":\"private\"");

			HttpResponse<String> unicode = send(port,
					request("unicode", "resources/list", ",\"cursor\":\"世界\""),
					"resources/list");
			assertSuccess(unicode, "unicode");
			Assertions.assertFalse(unicode.body().contains("\"nextCursor\""),
					unicode.body());
			assertContains(unicode.body(), "\"ttlMs\":500");
			assertContains(unicode.body(), "\"cacheScope\":\"private\"");

			HttpResponse<String> rejected = send(port,
					request("rejected", "resources/list", ",\"cursor\":\"bad\""),
					"resources/list");
			assertError(rejected, 400, -32602, "rejected");
			assertContains(rejected.body(), "The resource-list cursor is invalid.");
			assertContains(rejected.body(), "\"reason\":\"expired\"");

			HttpResponse<String> oversizedOutput = send(port,
					request("oversized-output", "resources/list",
							",\"cursor\":\"big\""), "resources/list");
			assertError(oversizedOutput, 500, -32603, "oversized-output");
			Assertions.assertFalse(oversizedOutput.body().contains("世界語"),
					oversizedOutput.body());

			Assertions.assertEquals(List.of(Optional.empty(), Optional.of(""),
					Optional.of("世界"), Optional.of("bad"), Optional.of("big")),
					observedCursors);
			Assertions.assertEquals(5, admissions.get());
			Assertions.assertEquals(5, requestLimiterInvocations.get());
			Assertions.assertEquals(5, listHandlerInvocations.get());

			HttpResponse<String> oversizedInput = send(port,
					request("oversized-input", "resources/list",
							",\"cursor\":\"世界語\""), "resources/list");
			assertError(oversizedInput, 400, -32602, "oversized-input");
			HttpResponse<String> wrongType = send(port,
					request("wrong-type", "resources/list", ",\"cursor\":7"),
					"resources/list");
			assertError(wrongType, 400, -32602, "wrong-type");
			Assertions.assertEquals(5, admissions.get());
			Assertions.assertEquals(5, requestLimiterInvocations.get());
			Assertions.assertEquals(5, listHandlerInvocations.get());
			Assertions.assertEquals(0, toolLimiterInvocations.get());
		} finally {
			soklet.close();
		}
	}

	@Test
	public void dynamicListCanVaryByAdmittedIdentity() throws Exception {
		McpResourceRegistration alpha = McpResourceRegistration
				.withUriAndName(URI.create("test://tenant/alpha"), "Alpha", java.util.Set.of(com.soklet.McpProtocolVersion.V2026_07_28))
				.handler(resourceHandler())
				.build();
		McpResourceRegistration beta = McpResourceRegistration
				.withUriAndName(URI.create("test://tenant/beta"), "Beta", java.util.Set.of(com.soklet.McpProtocolVersion.V2026_07_28))
				.handler(resourceHandler())
				.build();
		McpEndpoint endpoint = endpointBuilder()
				.resourceRegistrations(java.util.List.of(alpha, beta))
				.resourceListHandler((request, list, features) -> {
					String tenant = (String) request.getAdmissionIdentity()
							.getPrincipal().orElseThrow();
					Assertions.assertEquals("auth-" + tenant,
							request.getAdmissionIdentity()
									.getAuthorizationPartitionKey().orElseThrow());
					return McpResourcePage.builder()
							.resourceDescriptors(list.getRegisteredResourceDescriptors().stream()
									.filter(resource -> resource.getUri().toString()
											.endsWith("/" + tenant))
									.toList())
							.build();
				}, java.util.Set.of(com.soklet.McpProtocolVersion.V2026_07_28))
				.resourceListCachePolicy(McpCachePolicy.fromPrivateTimeToLive(
						Duration.ofMillis(250)))
				.build();
		McpServer server = McpServer.withPort(0).endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint))).admissionController(context -> {
					String authorization = context.getRequest()
							.getHeader("Authorization").orElseThrow();
					String tenant = authorization.substring("Bearer ".length());
					return McpAdmissionDecision.accepted(
							McpAdmissionIdentity.withRateLimitPartitionKey(
										"rate-" + tenant)
									.authorizationPartitionKey("auth-" + tenant)
									.principal(tenant)
									.build());
				})
				.host(LOOPBACK)
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(LOOPBACK))
				.build();
		Soklet soklet = managedSoklet(server);

		try {
			soklet.start();
			int port = server.getDiagnostics().getBoundAddress()
					.orElseThrow().getPort();
			HttpResponse<String> alphaPage = sendWithAuthorization(port,
					request("alpha", "resources/list", ""), "resources/list",
					"Bearer alpha");
			assertSuccess(alphaPage, "alpha");
			assertContains(alphaPage.body(), "test://tenant/alpha");
			Assertions.assertFalse(alphaPage.body().contains("test://tenant/beta"),
					alphaPage.body());
			assertContains(alphaPage.body(), "\"ttlMs\":250");
			assertContains(alphaPage.body(), "\"cacheScope\":\"private\"");

			HttpResponse<String> betaPage = sendWithAuthorization(port,
					request("beta", "resources/list", ""), "resources/list",
					"Bearer beta");
			assertSuccess(betaPage, "beta");
			assertContains(betaPage.body(), "test://tenant/beta");
			Assertions.assertFalse(betaPage.body().contains("test://tenant/alpha"),
					betaPage.body());
			assertContains(betaPage.body(), "\"ttlMs\":250");
			assertContains(betaPage.body(), "\"cacheScope\":\"private\"");
		} finally {
			soklet.close();
		}
	}

	@Test
	public void dynamicListRejectsInvalidApplicationOutputSafely() throws Exception {
		BlockingQueue<Throwable> resourceListFailures = new LinkedBlockingQueue<>();
		LifecycleObserver observer = new LifecycleObserver() {
			@Override
			public void didFinishMcpRequestHandling(McpRequestContext context,
					McpRequestOutcome outcome, McpJsonRpcError error,
					Duration duration, List<Throwable> throwables) {
				if ("resources/list".equals(context.getJsonRpcMethod())
						&& !throwables.isEmpty())
					resourceListFailures.add(throwables.get(0));
			}
		};
		McpResourceRegistration exact = McpResourceRegistration
				.withUriAndName(URI.create("test://registered"), "Registered", java.util.Set.of(com.soklet.McpProtocolVersion.V2026_07_28))
				.handler((request, resource, features) ->
						completeText(resource.getUri(), "registered", "text/plain"))
				.build();
		McpResourceRegistration template = McpResourceRegistration
				.withUriTemplateAndName("test://dynamic/{id}", "Dynamic", java.util.Set.of(com.soklet.McpProtocolVersion.V2026_07_28))
				.handler((request, resource, features) ->
						completeText(resource.getUri(), "dynamic", "text/plain"))
				.build();
		McpResourceRegistration invalidContentMetadata = McpResourceRegistration
				.withUriAndName(URI.create("test://invalid-content-metadata"),
						"Invalid content metadata", java.util.Set.of(com.soklet.McpProtocolVersion.V2026_07_28))
				.handler((request, resource, features) ->
						McpCompleteResult.fromResourceOutput(McpResourceOutput.withContent(McpTextResourceContents
										.withUriAndText(resource.getUri(), "secret")
										.metadata(McpJsonObject.builder()
												.put("dev.mcp/secret", "must-not-leak")
												.build())
										.build())
								.build()))
				.build();
		McpResourceDescriptor exactDescriptor = McpResourceDescriptor
				.withUriAndName(URI.create("test://registered"), "Registered")
				.build();
		McpResourceListHandler listHandler = (request, list, features) -> {
			String cursor = list.getCursor().orElseThrow();
			return switch (cursor) {
				case "template" -> McpResourcePage.builder()
						.resourceDescriptors(java.util.List.of(McpResourceDescriptor.withUriAndName(
								URI.create("test://dynamic/visible"), "Visible")
								.build()))
						.build();
				case "duplicate" -> McpResourcePage.builder()
						.resourceDescriptors(java.util.List.of(exactDescriptor, exactDescriptor))
						.build();
				case "unreadable" -> McpResourcePage.builder()
						.resourceDescriptors(java.util.List.of(McpResourceDescriptor.withUriAndName(
								URI.create("secret://not-registered"), "Secret")
								.build()))
						.build();
				case "reserved-metadata" -> McpResourcePage.builder()
						.resourceDescriptors(java.util.List.of(McpResourceDescriptor.withUriAndName(
								URI.create("test://registered"), "Registered")
								.metadata(McpJsonObject.builder()
										.put("dev.mcp/secret", "must-not-leak")
										.build())
								.build()))
						.build();
				default -> throw new AssertionError("Unexpected cursor");
			};
		};
		McpEndpoint endpoint = endpointBuilder()
				.resourceRegistrations(java.util.List.of(exact, template, invalidContentMetadata))
				.resourceListHandler(listHandler, java.util.Set.of(com.soklet.McpProtocolVersion.V2026_07_28))
				.build();
		McpServer server = McpServer.withPort(0).endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.host(LOOPBACK)
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(LOOPBACK))
				.build();
		Soklet soklet = managedSoklet(server, observer);

		try {
			soklet.start();
			int port = server.getDiagnostics().getBoundAddress()
					.orElseThrow().getPort();
			HttpResponse<String> templatePage = send(port,
					request("template-page", "resources/list",
							",\"cursor\":\"template\""), "resources/list");
			assertSuccess(templatePage, "template-page");
			assertContains(templatePage.body(), "test://dynamic/visible");

			for (String cursor : List.of("duplicate", "unreadable",
					"reserved-metadata")) {
				HttpResponse<String> response = send(port,
						request(cursor, "resources/list",
								",\"cursor\":\"" + cursor + "\""),
						"resources/list");
				assertError(response, 500, -32603, cursor);
				Assertions.assertFalse(response.body().contains("secret"),
						response.body());
				Throwable failure = resourceListFailures.poll(5, TimeUnit.SECONDS);
				Assertions.assertNotNull(failure,
						"Missing resource-list failure for " + cursor);
				if ("duplicate".equals(cursor))
					Assertions.assertEquals(
							"A resource-list page for endpoint '/mcp' contains a duplicate "
									+ "URI 'test://registered'.",
							failure.getMessage());
				else if ("unreadable".equals(cursor))
					Assertions.assertEquals(
							"A resource-list page for endpoint '/mcp' contains an unreadable "
									+ "URI 'secret://not-registered'.",
							failure.getMessage());
			}

			HttpResponse<String> invalidContent = read(port,
					"invalid-content-metadata", "test://invalid-content-metadata");
			assertError(invalidContent, 500, -32603,
					"invalid-content-metadata");
			Assertions.assertFalse(invalidContent.body().contains("secret"),
					invalidContent.body());
		} finally {
			soklet.close();
		}
	}

	@Test
	public void invalidResourceRoutingFailsDuringPublicServerBuild() {
		for (String invalidTemplate : List.of(
				"test://items/{+id}",
				"items/{id}",
				"test://items/{id}/{id}",
				"test://items/{first}{second}")) {
			McpEndpoint endpoint = endpointBuilder()
					.resourceRegistrations(java.util.List.of(templateRegistration(invalidTemplate)))
					.build();
			Assertions.assertThrows(IllegalArgumentException.class,
					() -> serverBuilder(endpoint).build(), invalidTemplate);
		}

		McpEndpoint overlapping = endpointBuilder()
				.resourceRegistrations(java.util.List.of(templateRegistration("test://items/{id}"), templateRegistration("test://items/{slug}")))
				.build();
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> serverBuilder(overlapping).build());

		McpEndpoint exactPrecedence = endpointBuilder()
				.resourceRegistrations(java.util.List.of(McpResourceRegistration.withUriAndName(
						URI.create("test://items/special"), "Special", java.util.Set.of(com.soklet.McpProtocolVersion.V2026_07_28))
						.handler(resourceHandler()).build(), templateRegistration("test://items/{id}")))
				.build();
		McpServer server = Assertions.assertDoesNotThrow(
				() -> serverBuilder(exactPrecedence).build());
		Assertions.assertEquals(McpServerStatus.NOT_STARTED,
				server.getDiagnostics().getStatus());
	}

	@Test
	public void publicServerConstructionEnforcesResourceTemplateCountBound() {
		McpEndpoint boundary = endpointWithTemplateRegistrations(256);
		McpServer server = Assertions.assertDoesNotThrow(
				() -> serverBuilder(boundary).build());
		Assertions.assertEquals(McpServerStatus.NOT_STARTED,
				server.getDiagnostics().getStatus());

		McpEndpoint oversized = endpointWithTemplateRegistrations(257);
		IllegalArgumentException exception = Assertions.assertThrows(
				IllegalArgumentException.class,
				() -> serverBuilder(oversized).build());
		assertContains(exception.getMessage(),
				"at most 256 resource URI templates");
	}

	@Test
	public void publicResourceRoutingEnforcesExactFiniteUriBounds() {
		String prefix = "test:///";
		String expression = "{value}";
		String boundaryTemplate = prefix + "a".repeat(
				8_192 - prefix.length() - expression.length()) + expression;
		McpEndpoint acceptedTemplate = endpointBuilder()
				.resourceRegistrations(java.util.List.of(templateRegistration(boundaryTemplate))).build();
		Assertions.assertDoesNotThrow(
				() -> serverBuilder(acceptedTemplate).build());

		McpEndpoint oversizedTemplate = endpointBuilder()
				.resourceRegistrations(java.util.List.of(templateRegistration(boundaryTemplate + "a"))).build();
		IllegalArgumentException templateFailure = Assertions.assertThrows(
				IllegalArgumentException.class,
				() -> serverBuilder(oversizedTemplate).build());
		assertContains(templateFailure.getMessage(),
				"at most 8192 UTF-8 bytes");

		String boundaryUri = prefix + "a".repeat(1_048_576 - prefix.length());
		McpEndpoint acceptedUri = endpointBuilder()
				.resourceRegistrations(java.util.List.of(McpResourceRegistration.withUriAndName(
						URI.create(boundaryUri), "Boundary URI", java.util.Set.of(com.soklet.McpProtocolVersion.V2026_07_28))
						.handler(resourceHandler()).build()))
				.build();
		Assertions.assertDoesNotThrow(() -> serverBuilder(acceptedUri).build());

		McpEndpoint oversizedUri = endpointBuilder()
				.resourceRegistrations(java.util.List.of(McpResourceRegistration.withUriAndName(
						URI.create(boundaryUri + "a"), "Oversized URI", java.util.Set.of(com.soklet.McpProtocolVersion.V2026_07_28))
						.handler(resourceHandler()).build()))
				.build();
		IllegalArgumentException uriFailure = Assertions.assertThrows(
				IllegalArgumentException.class,
				() -> serverBuilder(oversizedUri).build());
		assertContains(uriFailure.getMessage(),
				"at most 1048576 UTF-8 bytes");
	}

	@Test
	public void aggregateDynamicResourcePageFailsClosedWithoutCanaryAndRecovers()
			throws Exception {
		String canary = "AGGREGATE-RESOURCE-PAGE-CANARY";
		AtomicInteger listHandlerInvocations = new AtomicInteger();
		McpJsonArray padding = McpJsonArray.fromElements(
				Collections.nCopies(50_000, McpJsonNull.INSTANCE));
		McpJsonObject metadata = McpJsonObject.builder()
				.put("aggregateCanary", canary)
				.put("padding", padding)
				.build();
		McpResourceDescriptor first = McpResourceDescriptor
				.withUriAndName(URI.create("test://aggregate-page/first"), "First")
				.metadata(metadata)
				.build();
		McpResourceDescriptor second = McpResourceDescriptor
				.withUriAndName(URI.create("test://aggregate-page/second"), "Second")
				.metadata(metadata)
				.build();
		McpResourceListHandler listHandler = (request, list, features) -> {
			listHandlerInvocations.incrementAndGet();
			return switch (list.getCursor().orElseThrow()) {
				case "oversized" -> McpResourcePage.builder()
						.resourceDescriptors(java.util.List.of(first, second))
						.build();
				case "legal" -> McpResourcePage.builder()
						.resourceDescriptors(java.util.List.of(first))
						.build();
				default -> throw new AssertionError("Unexpected cursor");
			};
		};
		McpEndpoint endpoint = endpointBuilder()
				.resourceRegistrations(java.util.List.of(McpResourceRegistration.withUriTemplateAndName(
						"test://aggregate-page/{id}", "Aggregate page resource", java.util.Set.of(com.soklet.McpProtocolVersion.V2026_07_28))
						.handler(resourceHandler())
						.build()))
				.resourceListHandler(listHandler, java.util.Set.of(com.soklet.McpProtocolVersion.V2026_07_28))
				.build();
		McpServer server = serverBuilder(endpoint).build();
		Soklet soklet = managedSoklet(server);

		try {
			soklet.start();
			int port = server.getDiagnostics().getBoundAddress()
					.orElseThrow().getPort();
			HttpResponse<String> oversized = send(port,
					request("aggregate-page", "resources/list",
							",\"cursor\":\"oversized\""),
					"resources/list");
			assertError(oversized, 500, -32603, "aggregate-page");
			Assertions.assertEquals(
					"{\"jsonrpc\":\"2.0\",\"id\":\"aggregate-page\","
							+ "\"error\":{\"code\":-32603,"
							+ "\"message\":\"Internal error\"}}",
					oversized.body());
			Assertions.assertFalse(oversized.body().contains(canary),
					oversized.body());
			Assertions.assertFalse(oversized.body().contains("aggregate-page/first"),
					oversized.body());

			HttpResponse<String> recovered = send(port,
					request("legal-page", "resources/list",
							",\"cursor\":\"legal\""),
					"resources/list");
			assertSuccess(recovered, "legal-page");
			assertContains(recovered.body(), canary);
			assertContains(recovered.body(), "test://aggregate-page/first");
			Assertions.assertEquals(2, listHandlerInvocations.get());
		} finally {
			soklet.close();
		}
	}

	@Test
	public void blobOutputHonorsTheProductionJsonStringBound() throws Exception {
		URI boundaryUri = URI.create("test://blob-boundary");
		URI oversizedUri = URI.create("test://blob-oversized");
		URI aggregateUri = URI.create("test://blob-aggregate-oversized");
		McpBlobResourceContents boundaryContents = McpBlobResourceContents
				.withUriAndData(boundaryUri, new byte[786_432])
				.mimeType("application/octet-stream")
				.build();
		McpBlobResourceContents oversizedContents = McpBlobResourceContents
				.withUriAndData(oversizedUri, new byte[786_433])
				.mimeType("application/octet-stream")
					.build();
		List<McpResourceContents> aggregateOutput = new ArrayList<>();
		for (int index = 0; index < 5; ++index)
			aggregateOutput.add(McpBlobResourceContents.withUriAndData(
					aggregateUri, new byte[700_000]).build());
		McpResourceOutput aggregateContents = McpResourceOutput.fromContents(aggregateOutput);
		McpEndpoint endpoint = endpointBuilder()
					.resourceRegistrations(java.util.List.of(McpResourceRegistration
						.withUriAndName(boundaryUri, "Boundary blob", java.util.Set.of(com.soklet.McpProtocolVersion.V2026_07_28))
						.handler((request, resource, features) ->
								McpCompleteResult.fromResourceOutput(
										McpResourceOutput.withContent(boundaryContents)
												.build()))
						.build(), McpResourceRegistration
						.withUriAndName(oversizedUri, "Oversized blob", java.util.Set.of(com.soklet.McpProtocolVersion.V2026_07_28))
						.handler((request, resource, features) ->
								McpCompleteResult.fromResourceOutput(
										McpResourceOutput.withContent(oversizedContents)
												.build()))
							.build(), McpResourceRegistration
							.withUriAndName(aggregateUri, "Aggregate oversized blobs", java.util.Set.of(com.soklet.McpProtocolVersion.V2026_07_28))
							.handler((request, resource, features) ->
									McpCompleteResult.fromResourceOutput(aggregateContents))
							.build()))
					.build();
		McpServer server = serverBuilder(endpoint).build();
		Soklet soklet = managedSoklet(server);

		try {
			soklet.start();
			int port = server.getDiagnostics().getBoundAddress()
					.orElseThrow().getPort();
			HttpResponse<String> boundary = read(port, "blob-boundary",
					boundaryUri.toString());
			assertSuccess(boundary, "blob-boundary");
			assertContains(boundary.body(), "\"blob\":\"");
			Assertions.assertTrue(boundary.body().length() > 1_048_576,
					Integer.toString(boundary.body().length()));

			HttpResponse<String> oversized = read(port, "blob-oversized",
					oversizedUri.toString());
			assertError(oversized, 500, -32603, "blob-oversized");
				Assertions.assertTrue(oversized.body().length() < 1_000,
						Integer.toString(oversized.body().length()));

				HttpResponse<String> aggregateOversized = read(port,
						"blob-aggregate-oversized", aggregateUri.toString());
				assertError(aggregateOversized, 500, -32603,
						"blob-aggregate-oversized");
				Assertions.assertTrue(aggregateOversized.body().length() < 1_000,
						Integer.toString(aggregateOversized.body().length()));
		} finally {
			soklet.close();
		}
	}

	@Test
	public void oversizedStaticResourceCatalogFailsDuringPublicServerBuild() {
		String largeDescription = "x".repeat(900_000);
		McpEndpoint.Builder endpoint = endpointBuilder();
		List<McpResourceRegistration> resourceRegistrations = new ArrayList<>();
		for (int index = 0; index < 5; ++index) {
			URI uri = URI.create("test://large-static-resource/" + index);
			resourceRegistrations.add(McpResourceRegistration.withUriAndName(
						uri, "Large resource " + index, java.util.Set.of(com.soklet.McpProtocolVersion.V2026_07_28))
					.handler(resourceHandler())
					.description(largeDescription)
					.build());
		}
		endpoint.resourceRegistrations(resourceRegistrations);

		IllegalArgumentException exception = Assertions.assertThrows(
				IllegalArgumentException.class,
				() -> serverBuilder(endpoint.build()).build());
		assertContains(exception.getMessage(), "'resources/list'");
		assertContains(exception.getMessage(), "maximum UTF-8 bytes: 4194304");
	}

	@Test
	public void aggregateToolCatalogNodeBudgetFailsAtBuildWithoutCanaryAndRecovers() {
		String canary = "AGGREGATE-TOOL-CATALOG-CANARY";
		McpJsonObject metadata = McpJsonObject.builder()
				.put("aggregateCanary", canary)
				.put("padding", McpJsonArray.fromElements(
						Collections.nCopies(50_000, McpJsonNull.INSTANCE)))
				.build();
		McpToolRegistration<McpJsonObject> first = McpToolRegistration
				.withName("aggregate-catalog-first", java.util.Set.of(com.soklet.McpProtocolVersion.V2026_07_28))
				.jsonObjectArguments()
				.handler((request, arguments, features) ->
						McpCompleteResult.fromToolText("first"))
				.metadata(metadata)
				.build();
		McpToolRegistration<McpJsonObject> second = McpToolRegistration
				.withName("aggregate-catalog-second", java.util.Set.of(com.soklet.McpProtocolVersion.V2026_07_28))
				.jsonObjectArguments()
				.handler((request, arguments, features) ->
						McpCompleteResult.fromToolText("second"))
				.metadata(metadata)
				.build();
		McpEndpoint oversized = endpointBuilder()
				.toolRegistrations(java.util.List.of(first, second))
				.build();

		IllegalArgumentException exception = Assertions.assertThrows(
				IllegalArgumentException.class,
				() -> serverBuilder(oversized)
						.toolRateLimiter(context ->
								McpRateLimitDecision.allowed())
						.build());
		assertContains(exception.getMessage(), "MCP tool catalog");
		assertContains(exception.getMessage(), "JSON node limit");
		Assertions.assertFalse(exception.getMessage().contains(canary),
				exception.getMessage());

		McpEndpoint individuallyLegal = endpointBuilder()
				.toolRegistrations(java.util.List.of(first))
				.build();
		McpServer recovered = Assertions.assertDoesNotThrow(
				() -> serverBuilder(individuallyLegal)
						.toolRateLimiter(context ->
								McpRateLimitDecision.allowed())
						.build());
		Assertions.assertEquals(McpServerStatus.NOT_STARTED,
				recovered.getDiagnostics().getStatus());
	}

	@Test
	public void customResourceListDoesNotBudgetUnpublishedStaticCatalog() {
		McpJsonObject metadata = McpJsonObject.builder()
				.put("padding", McpJsonArray.fromElements(
						Collections.nCopies(50_000, McpJsonNull.INSTANCE)))
				.build();
		McpResourceRegistration first = McpResourceRegistration
				.withUriAndName(URI.create("test://custom-list/first"), "First", java.util.Set.of(com.soklet.McpProtocolVersion.V2026_07_28))
				.handler(resourceHandler())
				.metadata(metadata)
				.build();
		McpResourceRegistration second = McpResourceRegistration
				.withUriAndName(URI.create("test://custom-list/second"), "Second", java.util.Set.of(com.soklet.McpProtocolVersion.V2026_07_28))
				.handler(resourceHandler())
				.metadata(metadata)
				.build();
		McpEndpoint endpoint = endpointBuilder()
				.resourceRegistrations(java.util.List.of(first, second))
				.resourceListHandler((request, list, features) ->
						McpResourcePage.builder().build(), java.util.Set.of(com.soklet.McpProtocolVersion.V2026_07_28))
				.build();

		McpServer server = Assertions.assertDoesNotThrow(
				() -> serverBuilder(endpoint).build());
		Assertions.assertEquals(McpServerStatus.NOT_STARTED,
				server.getDiagnostics().getStatus());
	}

	@Test
	public void legacyCatalogsAndReadsRespectRevisionViewsAndWireSchemas() throws Exception {
		Set<McpProtocolVersion> versions = Set.of(McpProtocolVersion.V2025_06_18,
				McpProtocolVersion.V2025_11_25, McpProtocolVersion.V2026_07_28);
		AtomicInteger reads = new AtomicInteger();
		AtomicInteger modernOnlyReads = new AtomicInteger();
		List<String> stages = Collections.synchronizedList(new ArrayList<>());
		McpResourceRegistration text = McpResourceRegistration.withUriAndName(TEXT_URI, "Text", versions)
				.handler((requestContext, resourceReadContext, invocationFeatures) -> {
					reads.incrementAndGet();
					stages.add("handler");
					return completeText(resourceReadContext.getUri(), "resource-value", "text/plain");
				}).title("A title").description("A description").mimeType("text/plain")
				.icons(List.of(McpIcon.withSource(URI.create("https://example.com/icon.png")).build()))
				.metadata(McpJsonObject.builder().put("example/kind", "ordinary").build())
				.cachePolicy(McpCachePolicy.fromPublicTimeToLive(Duration.ofSeconds(1))).build();
		McpResourceRegistration binary = McpResourceRegistration.withUriAndName(BINARY_URI, "Binary", versions)
				.handler((requestContext, resourceReadContext, invocationFeatures) ->
						McpCompleteResult.fromResourceOutput(McpResourceOutput.fromContent(McpBlobResourceContents
								.withUriAndData(resourceReadContext.getUri(), new byte[] {1, 2, 3}).build()))).build();
		McpResourceRegistration template = McpResourceRegistration.withUriTemplateAndName(TEMPLATE_URI, "Template", versions)
				.handler((requestContext, resourceReadContext, invocationFeatures) ->
						completeText(resourceReadContext.getUri(), resourceReadContext.getUriTemplateVariables().get("id"), "text/plain"))
				.icons(List.of(McpIcon.withSource(URI.create("https://example.com/template.png")).build())).build();
		McpResourceRegistration modernOnly = McpResourceRegistration.withUriAndName(SPECIAL_URI, "Modern only",
				Set.of(McpProtocolVersion.V2026_07_28)).handler((requestContext, resourceReadContext, invocationFeatures) -> {
			modernOnlyReads.incrementAndGet();
			return completeText(resourceReadContext.getUri(), "modern", "text/plain");
		}).build();
		McpResourceRegistration modernTemplate = McpResourceRegistration.withUriTemplateAndName("test://modern/{id}", "Modern template",
				Set.of(McpProtocolVersion.V2026_07_28)).handler((requestContext, resourceReadContext, invocationFeatures) -> {
			modernOnlyReads.incrementAndGet();
			return completeText(resourceReadContext.getUri(), "modern", "text/plain");
		}).build();
		McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH, McpImplementation.withNameAndVersion("resources", "1").build(), versions)
				.resourceRegistrations(List.of(text, binary, template, modernOnly, modernTemplate)).build();
		McpServer server = serverBuilder(endpoint).admissionController(admissionContext -> {
			stages.add("admission");
			return McpAdmissionDecision.accepted();
		}).requestRateLimiter(rateLimitContext -> {
			stages.add("limiter");
			return McpRateLimitDecision.allowed();
		}).localizer(McpLocalizer.withFallbackLocale(Locale.ENGLISH, localizationRequest ->
				McpLocalizationContext.withLocale(Locale.FRENCH, textValue ->
						McpLocalizationResult.localized("FR:" + textValue.getDefaultText())).build()).build())
				.handlerInterceptor((requestContext, invocationFeatures, continuation) -> {
					stages.add("interceptor-before");
					McpOperationResult result = continuation.proceed();
					stages.add("interceptor-after");
					return result;
				}).build();
		Soklet soklet = managedSoklet(server);
		try {
			soklet.start();
			int port = server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
			for (String revision : List.of("2025-06-18", "2025-11-25")) {
				HttpResponse<String> initialize = sendLegacy(port, revision, "initialize",
						"\"protocolVersion\":\"" + revision + "\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}", null);
				assertContains(initialize.body(), "\"resources\":{}");
				HttpResponse<String> catalog = sendLegacy(port, revision, "resources/list", "", null);
				assertSuccess(catalog, "legacy");
				assertContains(catalog.body(), "\"title\":\"FR:A title\"");
				assertContains(catalog.body(), "\"example/kind\":\"ordinary\"");
				Assertions.assertFalse(catalog.body().contains("Modern only"), catalog.body());
				Assertions.assertEquals(revision.equals("2025-11-25"), catalog.body().contains("\"icons\""));
				HttpResponse<String> templates = sendLegacy(port, revision, "resources/templates/list", "", null);
				assertSuccess(templates, "legacy");
				assertContains(templates.body(), TEMPLATE_URI);
				Assertions.assertFalse(templates.body().contains("test://modern"), templates.body());
				Assertions.assertEquals(revision.equals("2025-11-25"), templates.body().contains("\"icons\""));
				stages.clear();
				HttpResponse<String> exact = sendLegacy(port, revision, "resources/read", "\"uri\":\"" + TEXT_URI + "\"", null);
				assertSuccess(exact, "legacy");
				assertContains(exact.body(), "resource-value");
				Assertions.assertEquals(List.of("admission", "limiter", "interceptor-before", "handler", "interceptor-after"), stages);
				HttpResponse<String> blob = sendLegacy(port, revision, "resources/read", "\"uri\":\"" + BINARY_URI + "\"", null);
				assertContains(blob.body(), "\"blob\":\"AQID\"");
				HttpResponse<String> unicode = sendLegacy(port, revision, "resources/read", "\"uri\":\"test://template/caf%C3%A9/data\"", null);
				assertContains(unicode.body(), "\"text\":\"café\"");
				HttpResponse<String> slash = sendLegacy(port, revision, "resources/read", "\"uri\":\"test://template/a%2Fb/data\"", null);
				assertContains(slash.body(), "\"text\":\"a/b\"");
				// An exact route assigned only to 2026 cannot shadow a legacy template.
				HttpResponse<String> shadow = sendLegacy(port, revision, "resources/read", "\"uri\":\"" + SPECIAL_URI + "\"", null);
				assertContains(shadow.body(), "\"text\":\"special\"");
				assertError(sendLegacy(port, revision, "resources/read", "\"uri\":\"test://modern/item\"", null), 200, -32002, "legacy");
				assertError(sendLegacy(port, revision, "resources/read", "\"uri\":\"test://missing\"", null), 200, -32002, "legacy");
				assertError(sendLegacy(port, revision, "resources/list", "\"cursor\":\"\"", null), 200, -32602, "legacy");
				assertError(sendLegacy(port, revision, "resources/templates/list", "\"cursor\":\"next\"", null), 200, -32602, "legacy");
				for (HttpResponse<String> response : List.of(catalog, templates, exact, blob, unicode)) {
					Assertions.assertFalse(response.body().contains("resultType"), response.body());
					Assertions.assertFalse(response.body().contains("cacheScope"), response.body());
					Assertions.assertFalse(response.body().contains("ttlMs"), response.body());
				}
			}
			Assertions.assertEquals(2, reads.get());
			Assertions.assertEquals(0, modernOnlyReads.get());
			assertContains(read(port, "modern", SPECIAL_URI.toString()).body(), "\"text\":\"modern\"");
			Assertions.assertEquals(1, modernOnlyReads.get());
		} finally {
			soklet.close();
		}
	}

	@Test
	public void legacyDynamicPagesPreserveOpaqueCursorsAndValidateSelectedRoutes() throws Exception {
		Set<McpProtocolVersion> versions = Set.of(McpProtocolVersion.V2025_06_18, McpProtocolVersion.V2025_11_25, McpProtocolVersion.V2026_07_28);
		AtomicInteger admissions = new AtomicInteger();
		AtomicInteger pages = new AtomicInteger();
		McpResourceRegistration shared = McpResourceRegistration.withUriAndName(TEXT_URI, "Shared", versions).handler(resourceHandler()).build();
		McpResourceRegistration modern = McpResourceRegistration.withUriAndName(BINARY_URI, "Modern", Set.of(McpProtocolVersion.V2026_07_28)).handler(resourceHandler()).build();
		McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH, McpImplementation.withNameAndVersion("pages", "1").build(), versions)
				.resourceRegistrations(List.of(shared, modern)).resourceListHandler((requestContext, resourceListContext, invocationFeatures) -> {
					pages.incrementAndGet();
					Assertions.assertEquals("reader", requestContext.getAdmissionIdentity().getPrincipal().orElseThrow());
					McpResourcePage.Builder page = McpResourcePage.builder().metadata(McpJsonObject.builder().put("example/page", true).build());
					if (resourceListContext.getCursor().isEmpty())
						return page.resourceDescriptors(resourceListContext.getRegisteredResourceDescriptors()).nextCursor("世界").build();
					return switch (resourceListContext.getCursor().orElseThrow()) {
						case "世界" -> page.nextCursor("").build();
						case "" -> page.build();
						case "bad" -> page.resourceDescriptors(List.of(McpResourceDescriptor.withUriAndName(BINARY_URI, "Modern").build())).build();
						case "big" -> page.nextCursor("世界語").build();
						default -> throw new IllegalStateException("private canary");
					};
				}, versions).build();
		McpServer server = serverBuilder(endpoint).maximumCursorSizeInBytes(6).admissionController(admissionContext -> {
			admissions.incrementAndGet();
			if (!admissionContext.getRequest().getHeader("Authorization").orElse("").equals("Bearer reader"))
				return McpAdmissionDecision.rejected(McpAdmissionRejection.withStatusCodeAndError(403, McpJsonRpcError.fromApplication(-31903, "Denied")).build());
			return McpAdmissionDecision.accepted(McpAdmissionIdentity.withRateLimitPartitionKey("reader").authorizationPartitionKey("reader").principal("reader").build());
		}).build();
		Soklet soklet = managedSoklet(server);
		try {
			soklet.start();
			int port = server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
			for (String revision : List.of("2025-06-18", "2025-11-25")) {
				HttpResponse<String> first = sendLegacy(port, revision, "resources/list", "", "Bearer reader");
				assertSuccess(first, "legacy");
				assertContains(first.body(), "\"nextCursor\":\"世界\"");
				assertContains(first.body(), "\"example/page\":true");
				Assertions.assertFalse(first.body().contains(BINARY_URI.toString()), first.body());
				assertContains(sendLegacy(port, revision, "resources/list", "\"cursor\":\"世界\"", "Bearer reader").body(), "\"nextCursor\":\"\"");
				HttpResponse<String> last = sendLegacy(port, revision, "resources/list", "\"cursor\":\"\"", "Bearer reader");
				assertSuccess(last, "legacy");
				Assertions.assertFalse(last.body().contains("nextCursor"), last.body());
				assertError(sendLegacy(port, revision, "resources/list", "\"cursor\":\"世界語\"", "Bearer reader"), 200, -32602, "legacy");
				for (String cursor : List.of("bad", "big", "throw")) {
					HttpResponse<String> failure = sendLegacy(port, revision, "resources/list", "\"cursor\":\"" + cursor + "\"", "Bearer reader");
					assertError(failure, 200, -32603, "legacy");
					Assertions.assertFalse(failure.body().contains(BINARY_URI.toString()), failure.body());
					Assertions.assertFalse(failure.body().contains("private canary"), failure.body());
				}
				int before = pages.get();
				Assertions.assertEquals(403, sendLegacy(port, revision, "resources/list", "\"cursor\":\"世界\"", null).statusCode());
				Assertions.assertEquals(403, sendLegacy(port, revision, "resources/read", "\"uri\":\"" + TEXT_URI + "\"", null).statusCode());
				Assertions.assertEquals(before, pages.get());
			}
			Assertions.assertEquals(12, pages.get());
			Assertions.assertEquals(16, admissions.get());
		} finally {
			soklet.close();
		}
	}

	@Test
	public void legacyReadsRejectAppsOutputAndHonorResponseByteBounds() throws Exception {
		Set<McpProtocolVersion> versions = Set.of(McpProtocolVersion.V2025_06_18, McpProtocolVersion.V2025_11_25);
		McpResourceRegistration apps = McpResourceRegistration.withUriAndName(TEXT_URI, "Ordinary declaration", versions)
				.handler((requestContext, resourceReadContext, invocationFeatures) ->
						completeText(resourceReadContext.getUri(), "private Apps canary", "text/html;profile=mcp-app")).build();
		McpResourceRegistration oversized = McpResourceRegistration.withUriAndName(BINARY_URI, "Oversized", versions)
				.handler((requestContext, resourceReadContext, invocationFeatures) ->
						completeText(resourceReadContext.getUri(), "\0".repeat(750_000), "text/plain")).build();
		McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH, McpImplementation.withNameAndVersion("bounds", "1").build(), versions)
				.resourceRegistrations(List.of(apps, oversized)).build();
		McpServer server = serverBuilder(endpoint).build();
		Soklet soklet = managedSoklet(server);
		try {
			soklet.start();
			int port = server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
			for (String revision : List.of("2025-06-18", "2025-11-25"))
				for (URI uri : List.of(TEXT_URI, BINARY_URI)) {
					HttpResponse<String> failure = sendLegacy(port, revision, "resources/read", "\"uri\":\"" + uri + "\"", null);
					assertError(failure, 200, -32603, "legacy");
					Assertions.assertFalse(failure.body().contains("private Apps canary"), failure.body());
					Assertions.assertTrue(failure.body().getBytes(StandardCharsets.UTF_8).length <= 4_194_304);
				}
		} finally {
			soklet.close();
		}
	}

	@Test
	public void modernOnlyResourceEndpointsExposeNoLegacyResourceSurface() throws Exception {
		Set<McpProtocolVersion> versions = Set.of(McpProtocolVersion.V2025_06_18,
				McpProtocolVersion.V2025_11_25, McpProtocolVersion.V2026_07_28);
		AtomicInteger invocations = new AtomicInteger();
		McpResourceRegistration resource = McpResourceRegistration.withUriAndName(TEXT_URI, "Modern",
				Set.of(McpProtocolVersion.V2026_07_28)).handler((requestContext, resourceReadContext, invocationFeatures) -> {
			invocations.incrementAndGet();
			return completeText(resourceReadContext.getUri(), "modern", "text/plain");
		}).build();
		McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH,
				McpImplementation.withNameAndVersion("modern-resources", "1").build(), versions)
				.resourceRegistrations(List.of(resource)).build();
		McpServer server = serverBuilder(endpoint).build();
		try (Soklet owner = managedSoklet(server)) {
			owner.start();
			int port = server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
			for (String revision : List.of("2025-06-18", "2025-11-25")) {
				HttpResponse<String> initialize = sendLegacy(port, revision, "initialize",
						"\"protocolVersion\":\"" + revision + "\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}", null);
				assertSuccess(initialize, "legacy");
				Assertions.assertFalse(initialize.body().contains("\"resources\""), initialize.body());
				for (String method : List.of("resources/list", "resources/templates/list", "resources/read"))
					assertError(sendLegacy(port, revision, method,
							method.equals("resources/read") ? "\"uri\":\"" + TEXT_URI + "\"" : "", null),
							200, -32601, "legacy");
			}
			Assertions.assertEquals(0, invocations.get());
			assertContains(read(port, "modern", TEXT_URI.toString()).body(), "\"text\":\"modern\"");
			Assertions.assertEquals(1, invocations.get());
		}
	}

	@Test
	public void legacyEmptyCustomListCannotReachModernSkillsReadFallback() throws Exception {
		Set<McpProtocolVersion> versions = Set.of(McpProtocolVersion.V2025_06_18,
				McpProtocolVersion.V2025_11_25, McpProtocolVersion.V2026_07_28);
		URI skillUri = URI.create("skill://example/test-skill/SKILL.md");
		String document = "---\nname: test-skill\ndescription: Test skill\n---\nprivate skill canary\n";
		McpSkillRegistration skill = McpSkillRegistration.withUriAndSkillBundle(skillUri,
				McpSkillBundle.fromFiles(Map.of("SKILL.md", document.getBytes(StandardCharsets.UTF_8))),
				Set.of(McpProtocolVersion.V2026_07_28)).build();
		McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH,
				McpImplementation.withNameAndVersion("skill-isolation", "1").build(), versions)
				.skillRegistrations(List.of(skill))
				.resourceListHandler((requestContext, resourceListContext, invocationFeatures) -> {
					Assertions.assertTrue(resourceListContext.getRegisteredResourceDescriptors().isEmpty());
					return McpResourcePage.builder().build();
				}, versions).build();
		McpServer server = serverBuilder(endpoint).build();
		try (Soklet owner = managedSoklet(server)) {
			owner.start();
			int port = server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
			for (String revision : List.of("2025-06-18", "2025-11-25")) {
				HttpResponse<String> list = sendLegacy(port, revision, "resources/list", "", null);
				assertSuccess(list, "legacy");
				assertContains(list.body(), "\"resources\":[]");
				HttpResponse<String> unavailable = sendLegacy(port, revision, "resources/read",
						"\"uri\":\"" + skillUri + "\"", null);
				assertError(unavailable, 200, -32002, "legacy");
				Assertions.assertFalse(unavailable.body().contains("private skill canary"), unavailable.body());
			}
			HttpResponse<String> modern = read(port, "modern-skill", skillUri.toString());
			assertSuccess(modern, "modern-skill");
			assertContains(modern.body(), "private skill canary");
		}
	}

	@Test
	public void handlerResourceNotFoundUsesTheSelectedRevisionWithoutExposingInterceptorErrors() throws Exception {
		Set<McpProtocolVersion> versions = Set.of(McpProtocolVersion.V2025_06_18, McpProtocolVersion.V2025_11_25, McpProtocolVersion.V2026_07_28);
		AtomicInteger interceptorMode = new AtomicInteger();
		AtomicInteger handlerEntries = new AtomicInteger();
		McpResourceReadHandler missing = (requestContext, resourceReadContext, invocationFeatures) -> {
			handlerEntries.incrementAndGet();
			throw new McpJsonRpcException(McpJsonRpcError.fromResourceNotFound(resourceReadContext.getUri()));
		};
		McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH,
				McpImplementation.withNameAndVersion("not-found", "1").build(), versions)
				.resourceRegistrations(List.of(
						McpResourceRegistration.withUriAndName(TEXT_URI, "Exact", versions).handler(missing).build(),
						McpResourceRegistration.withUriTemplateAndName(TEMPLATE_URI, "Template", versions).handler(missing).build(),
						McpResourceRegistration.withUriAndName(BINARY_URI, "Invalid parameters", versions)
								.handler((requestContext, resourceReadContext, invocationFeatures) -> {
									throw new McpJsonRpcException(McpJsonRpcError.fromInvalidParameters(
											"Resource not found", McpJsonObject.builder().put("uri", BINARY_URI.toString()).build()));
								}).build()))
				.build();
		McpServer server = serverBuilder(endpoint).handlerInterceptor((requestContext, invocationFeatures, chain) -> {
			if (interceptorMode.get() == 1)
				throw new McpJsonRpcException(McpJsonRpcError.fromResourceNotFound(URI.create("test://private-interceptor")));
			if (interceptorMode.get() == 2) {
				try { return chain.proceed(); }
				finally { throw new McpJsonRpcException(McpJsonRpcError.fromResourceNotFound(URI.create("test://private-interceptor"))); }
			}
			return chain.proceed();
		}).build();
		try (Soklet owner = managedSoklet(server)) {
			owner.start();
			int port = server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
			for (McpProtocolVersion version : List.of(McpProtocolVersion.V2025_06_18, McpProtocolVersion.V2025_11_25, McpProtocolVersion.V2026_07_28)) {
				boolean modern = version == McpProtocolVersion.V2026_07_28;
				for (String uri : List.of(TEXT_URI.toString(), "test://template/missing/data", "test://unregistered")) {
					HttpResponse<String> response = modern ? read(port, "missing", uri)
							: sendLegacy(port, version.getWireValue(), "resources/read", "\"uri\":\"" + uri + "\"", null);
					assertError(response, modern ? 400 : 200, modern ? -32602 : -32002, modern ? "missing" : "legacy");
					assertContains(response.body(), "\"data\":{\"uri\":\"" + uri + "\"}");
				}
				HttpResponse<String> invalid = modern ? read(port, "invalid", BINARY_URI.toString())
						: sendLegacy(port, version.getWireValue(), "resources/read", "\"uri\":\"" + BINARY_URI + "\"", null);
				assertError(invalid, modern ? 400 : 200, -32602, modern ? "invalid" : "legacy");
				for (int mode : List.of(1, 2)) {
					interceptorMode.set(mode);
					int before = handlerEntries.get();
					HttpResponse<String> response = modern ? read(port, "private", TEXT_URI.toString())
							: sendLegacy(port, version.getWireValue(), "resources/read", "\"uri\":\"" + TEXT_URI + "\"", null);
					assertError(response, modern ? 500 : 200, -32603, modern ? "private" : "legacy");
					Assertions.assertFalse(response.body().contains("private-interceptor"), response.body());
					Assertions.assertEquals(before + (mode == 2 ? 1 : 0), handlerEntries.get());
				}
				interceptorMode.set(0);
			}
		}
	}

	@Test
	public void fileTemplateVariablesEncodeSlashesAndDecodeExactlyOnceOnEveryRevision() throws Exception {
		Set<McpProtocolVersion> versions = Set.of(McpProtocolVersion.V2025_06_18,
				McpProtocolVersion.V2025_11_25, McpProtocolVersion.V2026_07_28);
		AtomicInteger templateReads = new AtomicInteger();
		AtomicInteger exactReads = new AtomicInteger();
		AtomicReference<McpResourceReadContext> observed = new AtomicReference<>();
		McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH,
				McpImplementation.withNameAndVersion("file-template-contract", "1").build(), versions)
				.resourceRegistrations(List.of(
						McpResourceRegistration.withUriTemplateAndName("file:///{path}", "Files", versions)
								.handler((requestContext, resourceReadContext, invocationFeatures) -> {
									templateReads.incrementAndGet();
									observed.set(resourceReadContext);
									return completeText(resourceReadContext.getUri(), "template", "text/plain");
								}).build(),
						McpResourceRegistration.withUriAndName(URI.create("file:///src/pinned.rs"), "Pinned", versions)
								.handler((requestContext, resourceReadContext, invocationFeatures) -> {
									exactReads.incrementAndGet();
									Assertions.assertTrue(resourceReadContext.getUriTemplateVariables().isEmpty());
									return completeText(resourceReadContext.getUri(), "exact", "text/plain");
								}).build())).build();
		McpServer server = serverBuilder(endpoint).requestRateLimiter(rateLimitContext -> McpRateLimitDecision.allowed()).build();
		try (Soklet owner = managedSoklet(server)) {
			owner.start();
			int port = server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
			for (McpProtocolVersion version : List.of(McpProtocolVersion.V2025_06_18,
					McpProtocolVersion.V2025_11_25, McpProtocolVersion.V2026_07_28)) {
				boolean modern = version == McpProtocolVersion.V2026_07_28;
				int before = templateReads.get();
				HttpResponse<String> rawPath = fileRead(port, version, "file:///src/main.rs");
				assertError(rawPath, modern ? 400 : 200, modern ? -32602 : -32002, modern ? "file" : "legacy");
				Assertions.assertEquals(before, templateReads.get());
				for (Map.Entry<String, String> example : Map.of(
						"file:///src%2Fmain.rs", "src/main.rs",
						"file:///src%2fmain.rs", "src/main.rs",
						"file:///src%252Fmain.rs", "src%2Fmain.rs",
						"file:///src%2Fcaf%C3%A9.rs", "src/café.rs",
						"file:///README.md", "README.md").entrySet()) {
					HttpResponse<String> response = fileRead(port, version, example.getKey());
					Assertions.assertEquals(200, response.statusCode(), response.body());
					assertContains(response.body(), "\"uri\":\"" + example.getKey() + "\"");
					Assertions.assertEquals(URI.create(example.getKey()), observed.get().getUri());
					Assertions.assertEquals(Map.of("path", example.getValue()), observed.get().getUriTemplateVariables());
					Assertions.assertThrows(UnsupportedOperationException.class,
							() -> observed.get().getUriTemplateVariables().clear());
				}
				Assertions.assertEquals(before + 5, templateReads.get());
				HttpResponse<String> exact = fileRead(port, version, "file:///src/pinned.rs");
				Assertions.assertEquals(200, exact.statusCode(), exact.body());
				assertContains(exact.body(), "\"text\":\"exact\"");
			}
			Assertions.assertEquals(15, templateReads.get());
			Assertions.assertEquals(3, exactReads.get());
		}
		for (String unsupported : List.of("file:///{+path}", "file:///{path*}", "file:///{path:5}")) {
			McpEndpoint unsupportedEndpoint = McpEndpoint.withPath(MCP_PATH,
					McpImplementation.withNameAndVersion("unsupported-template", "1").build(), versions)
					.resourceRegistrations(List.of(McpResourceRegistration.withUriTemplateAndName(unsupported, "Unsupported", versions)
							.handler(resourceHandler()).build())).build();
			Assertions.assertThrows(IllegalArgumentException.class, () -> serverBuilder(unsupportedEndpoint).build());
		}
	}

	private static HttpResponse<String> fileRead(int port, McpProtocolVersion protocolVersion, String uri) throws Exception {
		return protocolVersion == McpProtocolVersion.V2026_07_28 ? read(port, "file", uri)
				: sendLegacy(port, protocolVersion.getWireValue(), "resources/read", "\"uri\":\"" + uri + "\"", null);
	}

	private static HttpResponse<String> sendLegacy(int port, String revision, String method,
			String parameters, String authorization) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder().uri(URI.create("http://" + LOOPBACK + ":" + port + MCP_PATH))
				.timeout(Duration.ofSeconds(5)).header("Content-Type", JSON_MEDIA_TYPE)
				.header("Accept", JSON_MEDIA_TYPE + ", text/event-stream").header("MCP-Protocol-Version", revision);
		if (authorization != null) request.header("Authorization", authorization);
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"legacy\",\"method\":\"" + method + "\",\"params\":{" + parameters + "}}";
		return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build().send(request
				.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	private static McpEndpoint.Builder endpointBuilder() {
		return McpEndpoint.withPath(MCP_PATH, McpImplementation.withNameAndVersion(
						"resource-public-runtime-test", "4.0.0").build(), java.util.Set.of(com.soklet.McpProtocolVersion.V2026_07_28));
	}

	private static McpEndpoint endpointWithTemplateRegistrations(int count) {
		McpEndpoint.Builder endpoint = endpointBuilder();
		List<McpResourceRegistration> resourceRegistrations = new ArrayList<>();
		for (int index = 0; index < count; ++index)
			resourceRegistrations.add(templateRegistration(
					"test:///bounded/route-" + index + "/{value}"));
		return endpoint.resourceRegistrations(resourceRegistrations).build();
	}

	private static McpServer.Builder serverBuilder(McpEndpoint endpoint) {
		return McpServer.withPort(0).endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.host(LOOPBACK)
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(LOOPBACK));
	}

	private static Soklet managedSoklet(McpServer server) {
		return Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(
						ResourceMethodResolver.fromMethods(Set.of()))
				.build());
	}

	private static Soklet managedSoklet(McpServer server,
			LifecycleObserver lifecycleObserver) {
		return Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(
						ResourceMethodResolver.fromMethods(Set.of()))
				.lifecycleObservers(List.of(lifecycleObserver))
				.build());
	}

	private static McpResourceRegistration templateRegistration(String uriTemplate) {
		return McpResourceRegistration
				.withUriTemplateAndName(uriTemplate, "Template", java.util.Set.of(com.soklet.McpProtocolVersion.V2026_07_28))
				.handler(resourceHandler())
				.build();
	}

	private static McpResourceReadHandler resourceHandler() {
		return (request, resource, features) ->
				completeText(resource.getUri(), "value", "text/plain");
	}

	private static McpCompleteResult completeText(URI uri, String text,
			String mimeType) {
		return McpCompleteResult.fromResourceOutput(McpResourceOutput.withContent(McpTextResourceContents.withUriAndText(uri, text)
						.mimeType(mimeType)
						.build())
				.build());
	}

	private static HttpResponse<String> read(int port, String id, String uri)
			throws Exception {
		return send(port, request(id, "resources/read", ",\"uri\":\""
				+ uri + "\""), "resources/read", uri);
	}

	private static HttpResponse<String> send(int port, String body,
			String method) throws Exception {
		return send(port, body, method, Optional.empty());
	}

	private static HttpResponse<String> send(int port, String body,
			String method, String operationName) throws Exception {
		return send(port, body, method, Optional.of(operationName));
	}

	private static HttpResponse<String> send(int port, String body,
			String method, Optional<String> operationName) throws Exception {
		return send(port, body, method, operationName, Optional.empty());
	}

	private static HttpResponse<String> sendWithAuthorization(int port,
			String body, String method, String authorization) throws Exception {
		return send(port, body, method, Optional.empty(),
				Optional.of(authorization));
	}

	private static HttpResponse<String> send(int port, String body,
			String method, Optional<String> operationName,
			Optional<String> authorization) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder()
				.uri(URI.create("http://" + LOOPBACK + ":" + port + MCP_PATH))
				.timeout(Duration.ofSeconds(5))
				.header("Content-Type", JSON_MEDIA_TYPE + "; charset=UTF-8")
				.header("Accept", JSON_MEDIA_TYPE + ", text/event-stream")
				.header("MCP-Protocol-Version", PROTOCOL_VERSION)
				.header("Mcp-Method", method);
		operationName.ifPresent(value -> request.header("Mcp-Name", value));
		authorization.ifPresent(value -> request.header("Authorization", value));
		return HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(5))
				.version(HttpClient.Version.HTTP_1_1)
				.build()
				.send(request.POST(HttpRequest.BodyPublishers.ofString(
						body, StandardCharsets.UTF_8)).build(),
						HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	private static String request(String id, String method,
			String additionalParameters) {
		return "{\"jsonrpc\":\"2.0\",\"id\":\"" + id
				+ "\",\"method\":\"" + method + "\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}}"
				+ additionalParameters + "}}";
	}

	private static void assertSuccess(HttpResponse<String> response,
			String expectedId) {
		Assertions.assertEquals(200, response.statusCode(), response.body());
		Assertions.assertEquals(JSON_MEDIA_TYPE,
				response.headers().firstValue("Content-Type").orElseThrow());
		Assertions.assertEquals("no-store",
				response.headers().firstValue("Cache-Control").orElseThrow());
		assertContains(response.body(), "\"id\":\"" + expectedId + "\"");
	}

	private static void assertError(HttpResponse<String> response, int status,
			int code, String expectedId) {
		Assertions.assertEquals(status, response.statusCode(), response.body());
		assertContains(response.body(), "\"code\":" + code);
		assertContains(response.body(), "\"id\":\"" + expectedId + "\"");
	}

	private static void assertOrdered(String text, String... values) {
		int previous = -1;
		for (String value : values) {
			int index = text.indexOf(value);
			Assertions.assertTrue(index > previous, text);
			previous = index;
		}
	}

	private static void assertContains(String text, String expected) {
		Assertions.assertTrue(text.contains(expected), () ->
				"Expected <" + text + "> to contain <" + expected + ">.");
	}
}
