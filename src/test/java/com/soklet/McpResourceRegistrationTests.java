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

import org.junit.jupiter.api.Test;

import javax.annotation.concurrent.ThreadSafe;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the public staged MCP resource-registration surface.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
class McpResourceRegistrationTests {
	@Test
	void exactRegistrationPreservesItsImmutableDescriptorAndReadPolicy() {
		URI uri = URI.create("catalog://items/42");
		McpIcon icon = McpIcon.withSource(
				URI.create("https://example.com/item.png")).build();
		McpContentAnnotations annotations = McpContentAnnotations.builder()
				.audience(McpRole.USER)
				.priority(0.75)
				.build();
		McpCachePolicy cachePolicy = McpCachePolicy.fromPublicTimeToLive(
				Duration.ofMinutes(5));
		McpJsonObject metadata = McpJsonObject.builder()
				.put("owner", "catalog")
				.build();
		McpResourceReadHandler handler = resourceHandler();
		McpResourceRegistration resource = McpResourceRegistration
				.withUriAndName(uri, "catalog-item")
				.handler(handler)
				.title("Catalog item")
				.description("One catalog item")
				.mimeType("application/json")
				.icon(icon)
				.annotations(annotations)
				.size(42L)
				.cachePolicy(cachePolicy)
				.metadata(metadata)
				.build();

		assertEquals(McpResourceAddressType.URI, resource.getAddressType());
		assertEquals(uri, resource.getUri().orElseThrow());
		assertTrue(resource.getUriTemplate().isEmpty());
		assertEquals("catalog-item", resource.getName());
		assertEquals("Catalog item", resource.getTitle().orElseThrow());
		assertEquals("One catalog item", resource.getDescription().orElseThrow());
		assertEquals("application/json", resource.getMimeType().orElseThrow());
		assertEquals(List.of(icon), resource.getIcons());
		assertSame(annotations, resource.getAnnotations().orElseThrow());
		assertEquals(Long.valueOf(42), resource.getSize().orElseThrow());
		assertSame(cachePolicy, resource.getCachePolicy());
		assertSame(metadata, resource.getMetadata());
		assertSame(handler, resource.getHandler());
		assertThrows(UnsupportedOperationException.class,
				() -> resource.getIcons().clear());
	}

	@Test
	void templateRegistrationHasNoSizeBuilderAndSharesCommonMetadata() {
		McpResourceReadHandler handler = resourceHandler();
		Object exactBuilder = McpResourceRegistration
				.withUriAndName(URI.create("catalog://items/42"), "exact")
				.handler(handler);
		Object templateBuilder = McpResourceRegistration
				.withUriTemplateAndName("catalog://items/{itemId}", "template")
				.handler(handler);
		McpResourceRegistration resource = McpResourceRegistration
				.withUriTemplateAndName("catalog://items/{itemId}", "template")
				.handler(handler)
				.title("Catalog template")
				.description("One templated catalog item")
				.mimeType("application/json")
				.cachePolicy(McpCachePolicy.fromPrivateTimeToLive(
						Duration.ofSeconds(30)))
				.build();

		assertTrue(Arrays.stream(exactBuilder.getClass().getMethods())
				.anyMatch(method -> method.getName().equals("size")));
		assertFalse(Arrays.stream(templateBuilder.getClass().getMethods())
				.anyMatch(method -> method.getName().equals("size")));
		assertEquals(McpResourceAddressType.URI_TEMPLATE,
				resource.getAddressType());
		assertTrue(resource.getUri().isEmpty());
		assertEquals("catalog://items/{itemId}",
				resource.getUriTemplate().orElseThrow());
		assertTrue(resource.getSize().isEmpty());
		assertEquals("Catalog template", resource.getTitle().orElseThrow());
		assertEquals(McpCacheScope.PRIVATE,
				resource.getCachePolicy().getScope());
	}

	@Test
	void resourceDescriptorsAndPagesDefensivelyCopyOrderedValues() {
		McpResourceDescriptor descriptor = McpResourceDescriptor
				.withUriAndName(URI.create("catalog://readme"), "readme")
				.title("Catalog README")
				.description("Catalog usage guide")
				.mimeType("text/plain")
				.size(128L)
				.metadata(McpJsonObject.builder().put("revision", 1).build())
				.build();
		List<McpResourceDescriptor> mutable = new ArrayList<>(List.of(descriptor));
		McpJsonObject pageMetadata = McpJsonObject.builder()
				.put("catalogRevision", "one")
				.build();
		McpResourcePage page = McpResourcePage.builder()
				.resources(mutable)
				.nextCursor("")
				.cacheTimeToLiveOverride(Duration.ofMillis(250))
				.metadata(pageMetadata)
				.build();
		mutable.clear();

		assertEquals(URI.create("catalog://readme"), descriptor.getUri());
		assertEquals("readme", descriptor.getName());
		assertEquals("Catalog README", descriptor.getTitle().orElseThrow());
		assertEquals("Catalog usage guide",
				descriptor.getDescription().orElseThrow());
		assertEquals("text/plain", descriptor.getMimeType().orElseThrow());
		assertEquals(Long.valueOf(128), descriptor.getSize().orElseThrow());
		assertEquals(List.of(descriptor), page.getResources());
		assertEquals("", page.getNextCursor().orElseThrow());
		assertEquals(Duration.ofMillis(250),
				page.getCacheTimeToLiveOverride().orElseThrow());
		assertSame(pageMetadata, page.getMetadata());
		assertThrows(UnsupportedOperationException.class,
				() -> page.getResources().clear());
	}

	@Test
	void validatesAddressesNamesMimeTypesSizesDurationsAndErrors() {
		McpResourceReadHandler handler = resourceHandler();

		assertThrows(IllegalArgumentException.class, () -> McpResourceRegistration
				.withUriAndName(URI.create("relative"), "resource"));
		assertThrows(IllegalArgumentException.class, () -> McpResourceRegistration
				.withUriAndName(URI.create("catalog://item"), " "));
		assertThrows(IllegalArgumentException.class, () -> McpResourceRegistration
				.withUriTemplateAndName(" ", "resource"));
		assertThrows(IllegalArgumentException.class, () -> McpResourceRegistration
				.withUriTemplateAndName("catalog://items/{itemId}", ""));
		assertThrows(IllegalArgumentException.class, () -> McpResourceRegistration
				.withUriAndName(URI.create("catalog://item"), "resource")
				.handler(handler).mimeType(" "));
		assertThrows(IllegalArgumentException.class, () -> McpResourceRegistration
				.withUriAndName(URI.create("catalog://item"), "resource")
				.handler(handler).size(-1L));
		assertThrows(IllegalArgumentException.class, () -> McpResourceDescriptor
				.withUriAndName(URI.create("catalog://item"), "resource")
				.size(-1L));
		assertThrows(NullPointerException.class, () -> McpResourceRegistration
				.withUriAndName(URI.create("catalog://item"), "resource")
				.handler(handler).size(null));
		assertThrows(NullPointerException.class, () -> McpResourceDescriptor
				.withUriAndName(URI.create("catalog://item"), "resource")
				.size(null));
		assertThrows(NullPointerException.class, () -> McpResourceLink
				.withUriAndName(URI.create("catalog://item"), "resource")
				.size(null));
		assertThrows(IllegalArgumentException.class,
				() -> McpCachePolicy.fromPrivateTimeToLive(Duration.ofMillis(-1)));
		assertThrows(IllegalArgumentException.class,
				() -> McpCachePolicy.fromPublicTimeToLive(Duration.ofNanos(1)));
		assertThrows(IllegalArgumentException.class,
				() -> McpCachePolicy.fromPublicTimeToLive(
						Duration.ofSeconds(Long.MAX_VALUE)));
		assertThrows(IllegalArgumentException.class, () -> McpResourcePage.builder()
				.cacheTimeToLiveOverride(Duration.ofNanos(1)));
		assertThrows(IllegalArgumentException.class, () -> McpResourcePage.builder()
				.cacheTimeToLiveOverride(Duration.ofSeconds(Long.MAX_VALUE)));
		assertThrows(IllegalArgumentException.class, () -> McpResourceOutput.builder()
				.cacheTimeToLiveOverride(Duration.ofSeconds(Long.MAX_VALUE)));

		McpJsonRpcError error = McpJsonRpcError.fromInvalidParameters(
				"The resource-list cursor is invalid.");
		McpJsonRpcException exception = new McpJsonRpcException(error);
		assertSame(error, exception.getError());
	}

	@Test
	void endpointPreservesResourceOrderPoliciesAndSoleListAuthority() {
		McpResourceRegistration exact = McpResourceRegistration
				.withUriAndName(URI.create("catalog://readme"), "readme")
				.handler(resourceHandler())
				.build();
		McpResourceRegistration template = McpResourceRegistration
				.withUriTemplateAndName("catalog://items/{itemId}", "item")
				.handler(resourceHandler())
				.build();
		McpResourceListHandler listHandler = (request, list, features) ->
				McpResourcePage.builder()
						.resources(list.getRegisteredResourceDescriptors())
						.build();
		McpCachePolicy listPolicy = McpCachePolicy.fromPrivateTimeToLive(
				Duration.ofSeconds(15));
		McpCachePolicy templatePolicy = McpCachePolicy.fromPublicTimeToLive(
				Duration.ofMinutes(1));
		McpEndpoint endpoint = endpointBuilder()
				.resource(exact)
				.resources(List.of(template))
				.resourceListHandler(listHandler)
				.resourceListCachePolicy(listPolicy)
				.resourceTemplateListCachePolicy(templatePolicy)
				.build();

		assertEquals(List.of(exact, template), endpoint.getResources());
		assertSame(listHandler, endpoint.getResourceListHandler().orElseThrow());
		assertSame(listPolicy, endpoint.getResourceListCachePolicy());
		assertSame(templatePolicy,
				endpoint.getResourceTemplateListCachePolicy());
		assertThrows(UnsupportedOperationException.class,
				() -> endpoint.getResources().clear());
		assertThrows(IllegalStateException.class, () -> endpointBuilder()
				.resourceListHandler(listHandler)
				.resourceListHandler(listHandler));

		McpEndpoint defaults = endpointBuilder().build();
		assertSame(McpCachePolicy.privateNoCacheInstance(),
				defaults.getResourceListCachePolicy());
		assertSame(McpCachePolicy.privateNoCacheInstance(),
				defaults.getResourceTemplateListCachePolicy());
	}

	@Test
	void endpointRejectsDuplicateExactUrisAndTemplates() {
		McpResourceRegistration firstExact = McpResourceRegistration
				.withUriAndName(URI.create("catalog://readme"), "first")
				.handler(resourceHandler()).build();
		McpResourceRegistration secondExact = McpResourceRegistration
				.withUriAndName(URI.create("catalog://readme"), "second")
				.handler(resourceHandler()).build();
		McpResourceRegistration firstTemplate = McpResourceRegistration
				.withUriTemplateAndName("catalog://items/{itemId}", "first")
				.handler(resourceHandler()).build();
		McpResourceRegistration secondTemplate = McpResourceRegistration
				.withUriTemplateAndName("catalog://items/{itemId}", "second")
				.handler(resourceHandler()).build();

		assertThrows(IllegalStateException.class, () -> endpointBuilder()
				.resource(firstExact).resource(secondExact).build());
		assertThrows(IllegalStateException.class, () -> endpointBuilder()
				.resource(firstTemplate).resource(secondTemplate).build());
	}

	@Test
	void serverCursorLimitDefaultsTo4096AndMustBePositive() {
		McpEndpointRegistry registry = McpEndpointRegistry.fromEndpoints(
				List.of(endpointBuilder().build()));
		McpServer defaultServer = McpServer.withPort(0)
				.endpointRegistry(registry)
				.admissionController(
						McpAdmissionController.acceptAllInstance())
				.build();
		McpServer customServer = McpServer.withPort(0)
				.maximumCursorSizeInBytes(17)
				.endpointRegistry(registry)
				.admissionController(
						McpAdmissionController.acceptAllInstance())
				.build();

		assertEquals(Integer.valueOf(4_096),
				defaultServer.getMaximumCursorSizeInBytes());
		assertEquals(Integer.valueOf(17),
				customServer.getMaximumCursorSizeInBytes());
		assertThrows(IllegalArgumentException.class,
				() -> McpServer.withPort(0).maximumCursorSizeInBytes(0));
		assertThrows(IllegalArgumentException.class,
				() -> McpServer.withPort(0).maximumCursorSizeInBytes(-1));
		assertThrows(NullPointerException.class,
				() -> McpServer.withPort(0).maximumCursorSizeInBytes(null));
	}

	private static McpResourceReadHandler resourceHandler() {
		return (request, resource, features) ->
				McpCompleteResult.fromResourceOutput(McpResourceOutput.builder()
						.content(McpTextResourceContents
								.withUriAndText(resource.getUri(), "value")
								.build())
						.build());
	}

	private static McpEndpoint.Builder endpointBuilder() {
		return McpEndpoint.withPath("/mcp")
				.serverInformation(McpImplementation.withNameAndVersion(
						"resource-tests", "4.0.0-SNAPSHOT").build());
	}
}
