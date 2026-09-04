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
import java.util.Optional;
import java.util.Set;
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
				.withUriAndName(TEXT_URI, "Static text")
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
				.withUriAndName(BINARY_URI, "Static binary")
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
				.withUriAndName(SPECIAL_URI, "Special exact resource")
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
				.withUriTemplateAndName(TEMPLATE_URI, "Template resource")
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
				.addResource(text)
				.addResource(binary)
				.addResource(exactSpecial)
				.addResource(template)
				.resourceListCachePolicy(McpCachePolicy.fromPublicTimeToLive(
						Duration.ofMillis(100)))
				.resourceTemplateListCachePolicy(
						McpCachePolicy.fromPrivateTimeToLive(Duration.ofMillis(200)))
				.build();
		McpServer server = McpServer.withPort(0, McpEndpointRegistry.fromEndpoints(List.of(endpoint)), context -> {
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
			Assertions.assertTrue(stages.isEmpty(), stages.toString());
			Assertions.assertEquals(2, templateInvocations.get());

			stages.clear();
			HttpResponse<String> unknown = read(port, "read-unknown",
					"test://unknown-resource");
			assertError(unknown, 400, -32602, "read-unknown");
			assertContains(unknown.body(),
					"\"data\":{\"uri\":\"test://unknown-resource\"}");
			Assertions.assertTrue(stages.isEmpty(), stages.toString());
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
				.withUriAndName(URI.create("test://registered"), "Registered")
				.handler((request, resource, features) ->
						completeText(resource.getUri(), "registered", "text/plain"))
				.build();
		McpResourceRegistration template = McpResourceRegistration
				.withUriTemplateAndName("test://dynamic/{id}", "Dynamic")
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
				return page.addResources(list.getRegisteredResourceDescriptors())
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
				.addResource(exact)
				.addResource(template)
				.resourceListHandler(listHandler)
				.resourceListCachePolicy(McpCachePolicy.fromPrivateTimeToLive(
						Duration.ofMillis(500)))
				.build();
		McpServer server = McpServer.withPort(0, McpEndpointRegistry.fromEndpoints(List.of(endpoint)), context -> {
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
				.withUriAndName(URI.create("test://tenant/alpha"), "Alpha")
				.handler(resourceHandler())
				.build();
		McpResourceRegistration beta = McpResourceRegistration
				.withUriAndName(URI.create("test://tenant/beta"), "Beta")
				.handler(resourceHandler())
				.build();
		McpEndpoint endpoint = endpointBuilder()
				.addResource(alpha)
				.addResource(beta)
				.resourceListHandler((request, list, features) -> {
					String tenant = (String) request.getAdmissionIdentity()
							.getPrincipal().orElseThrow();
					Assertions.assertEquals("auth-" + tenant,
							request.getAdmissionIdentity()
									.getAuthorizationPartitionKey().orElseThrow());
					return McpResourcePage.builder()
							.addResources(list.getRegisteredResourceDescriptors().stream()
									.filter(resource -> resource.getUri().toString()
											.endsWith("/" + tenant))
									.toList())
							.build();
				})
				.resourceListCachePolicy(McpCachePolicy.fromPrivateTimeToLive(
						Duration.ofMillis(250)))
				.build();
		McpServer server = McpServer.withPort(0, McpEndpointRegistry.fromEndpoints(List.of(endpoint)), context -> {
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
		McpResourceRegistration exact = McpResourceRegistration
				.withUriAndName(URI.create("test://registered"), "Registered")
				.handler((request, resource, features) ->
						completeText(resource.getUri(), "registered", "text/plain"))
				.build();
		McpResourceRegistration template = McpResourceRegistration
				.withUriTemplateAndName("test://dynamic/{id}", "Dynamic")
				.handler((request, resource, features) ->
						completeText(resource.getUri(), "dynamic", "text/plain"))
				.build();
		McpResourceRegistration invalidContentMetadata = McpResourceRegistration
				.withUriAndName(URI.create("test://invalid-content-metadata"),
						"Invalid content metadata")
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
						.addResource(McpResourceDescriptor.withUriAndName(
								URI.create("test://dynamic/visible"), "Visible")
								.build())
						.build();
				case "duplicate" -> McpResourcePage.builder()
						.addResource(exactDescriptor)
						.addResource(exactDescriptor)
						.build();
				case "unreadable" -> McpResourcePage.builder()
						.addResource(McpResourceDescriptor.withUriAndName(
								URI.create("secret://not-registered"), "Secret")
								.build())
						.build();
				case "reserved-metadata" -> McpResourcePage.builder()
						.addResource(McpResourceDescriptor.withUriAndName(
								URI.create("test://registered"), "Registered")
								.metadata(McpJsonObject.builder()
										.put("dev.mcp/secret", "must-not-leak")
										.build())
								.build())
						.build();
				default -> throw new AssertionError("Unexpected cursor");
			};
		};
		McpEndpoint endpoint = endpointBuilder()
				.addResource(exact)
				.addResource(template)
				.addResource(invalidContentMetadata)
				.resourceListHandler(listHandler)
				.build();
		McpServer server = McpServer.withPort(0, McpEndpointRegistry.fromEndpoints(List.of(endpoint)), McpAdmissionController.acceptAllInstance())
				.host(LOOPBACK)
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(LOOPBACK))
				.build();
		Soklet soklet = managedSoklet(server);

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
					.addResource(templateRegistration(invalidTemplate))
					.build();
			Assertions.assertThrows(IllegalArgumentException.class,
					() -> serverBuilder(endpoint).build(), invalidTemplate);
		}

		McpEndpoint overlapping = endpointBuilder()
				.addResource(templateRegistration("test://items/{id}"))
				.addResource(templateRegistration("test://items/{slug}"))
				.build();
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> serverBuilder(overlapping).build());

		McpEndpoint exactPrecedence = endpointBuilder()
				.addResource(McpResourceRegistration.withUriAndName(
						URI.create("test://items/special"), "Special")
						.handler(resourceHandler()).build())
				.addResource(templateRegistration("test://items/{id}"))
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
				.addResource(templateRegistration(boundaryTemplate)).build();
		Assertions.assertDoesNotThrow(
				() -> serverBuilder(acceptedTemplate).build());

		McpEndpoint oversizedTemplate = endpointBuilder()
				.addResource(templateRegistration(boundaryTemplate + "a")).build();
		IllegalArgumentException templateFailure = Assertions.assertThrows(
				IllegalArgumentException.class,
				() -> serverBuilder(oversizedTemplate).build());
		assertContains(templateFailure.getMessage(),
				"at most 8192 UTF-8 bytes");

		String boundaryUri = prefix + "a".repeat(1_048_576 - prefix.length());
		McpEndpoint acceptedUri = endpointBuilder()
				.addResource(McpResourceRegistration.withUriAndName(
						URI.create(boundaryUri), "Boundary URI")
						.handler(resourceHandler()).build())
				.build();
		Assertions.assertDoesNotThrow(() -> serverBuilder(acceptedUri).build());

		McpEndpoint oversizedUri = endpointBuilder()
				.addResource(McpResourceRegistration.withUriAndName(
						URI.create(boundaryUri + "a"), "Oversized URI")
						.handler(resourceHandler()).build())
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
						.addResource(first)
						.addResource(second)
						.build();
				case "legal" -> McpResourcePage.builder()
						.addResource(first)
						.build();
				default -> throw new AssertionError("Unexpected cursor");
			};
		};
		McpEndpoint endpoint = endpointBuilder()
				.addResource(McpResourceRegistration.withUriTemplateAndName(
						"test://aggregate-page/{id}", "Aggregate page resource")
						.handler(resourceHandler())
						.build())
				.resourceListHandler(listHandler)
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
		McpResourceOutput.Builder aggregateOutput = McpResourceOutput.withContent(
				McpBlobResourceContents.withUriAndData(
						aggregateUri, new byte[700_000]).build());
		for (int index = 1; index < 5; ++index)
			aggregateOutput.addContent(McpBlobResourceContents.withUriAndData(
					aggregateUri, new byte[700_000]).build());
		McpResourceOutput aggregateContents = aggregateOutput.build();
		McpEndpoint endpoint = endpointBuilder()
					.addResource(McpResourceRegistration
						.withUriAndName(boundaryUri, "Boundary blob")
						.handler((request, resource, features) ->
								McpCompleteResult.fromResourceOutput(
										McpResourceOutput.withContent(boundaryContents)
												.build()))
						.build())
				.addResource(McpResourceRegistration
						.withUriAndName(oversizedUri, "Oversized blob")
						.handler((request, resource, features) ->
								McpCompleteResult.fromResourceOutput(
										McpResourceOutput.withContent(oversizedContents)
												.build()))
							.build())
					.addResource(McpResourceRegistration
							.withUriAndName(aggregateUri, "Aggregate oversized blobs")
							.handler((request, resource, features) ->
									McpCompleteResult.fromResourceOutput(aggregateContents))
							.build())
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
		for (int index = 0; index < 5; ++index) {
			URI uri = URI.create("test://large-static-resource/" + index);
			endpoint.addResource(McpResourceRegistration.withUriAndName(
						uri, "Large resource " + index)
					.handler(resourceHandler())
					.description(largeDescription)
					.build());
		}

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
				.withName("aggregate-catalog-first")
				.jsonObjectArguments()
				.handler((request, arguments, features) ->
						McpCompleteResult.fromToolText("first"))
				.metadata(metadata)
				.build();
		McpToolRegistration<McpJsonObject> second = McpToolRegistration
				.withName("aggregate-catalog-second")
				.jsonObjectArguments()
				.handler((request, arguments, features) ->
						McpCompleteResult.fromToolText("second"))
				.metadata(metadata)
				.build();
		McpEndpoint oversized = endpointBuilder()
				.addTool(first)
				.addTool(second)
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
				.addTool(first)
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
				.withUriAndName(URI.create("test://custom-list/first"), "First")
				.handler(resourceHandler())
				.metadata(metadata)
				.build();
		McpResourceRegistration second = McpResourceRegistration
				.withUriAndName(URI.create("test://custom-list/second"), "Second")
				.handler(resourceHandler())
				.metadata(metadata)
				.build();
		McpEndpoint endpoint = endpointBuilder()
				.addResource(first)
				.addResource(second)
				.resourceListHandler((request, list, features) ->
						McpResourcePage.builder().build())
				.build();

		McpServer server = Assertions.assertDoesNotThrow(
				() -> serverBuilder(endpoint).build());
		Assertions.assertEquals(McpServerStatus.NOT_STARTED,
				server.getDiagnostics().getStatus());
	}

	private static McpEndpoint.Builder endpointBuilder() {
		return McpEndpoint.withPath(MCP_PATH, McpImplementation.withNameAndVersion(
						"resource-public-runtime-test", "4.0.0").build());
	}

	private static McpEndpoint endpointWithTemplateRegistrations(int count) {
		McpEndpoint.Builder endpoint = endpointBuilder();
		for (int index = 0; index < count; ++index)
			endpoint.addResource(templateRegistration(
					"test:///bounded/route-" + index + "/{value}"));
		return endpoint.build();
	}

	private static McpServer.Builder serverBuilder(McpEndpoint endpoint) {
		return McpServer.withPort(0, McpEndpointRegistry.fromEndpoints(List.of(endpoint)), McpAdmissionController.acceptAllInstance())
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

	private static McpResourceRegistration templateRegistration(String uriTemplate) {
		return McpResourceRegistration
				.withUriTemplateAndName(uriTemplate, "Template")
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
