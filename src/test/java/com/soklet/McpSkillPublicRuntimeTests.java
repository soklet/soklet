/*
 * Copyright 2022-2026 Revetware LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
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
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Real-listener contracts for public Skills discovery, lookup, and file reads. */
@Timeout(60)
public class McpSkillPublicRuntimeTests {
	private static final String LOOPBACK = "127.0.0.1";
	private static final String MCP_PATH = "/mcp";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final String JSON_MEDIA_TYPE = "application/json";
	private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(5))
			.version(HttpClient.Version.HTTP_1_1)
			.build();

	@Test
	public void automaticListExactGetAndSkillsOnlyResourceReadsPreserveManifestBytes() throws Exception {
		byte[] root = skillDocument("test-skill", "Original description");
		byte[] binary = {0, 1, (byte) 0xff, 3};
		McpSkillRegistration registration = registration("skill://example/test-skill/SKILL.md",
				Map.of("SKILL.md", root, "assets/sample.bin", binary));
		McpEndpoint endpoint = endpointBuilder().skillRegistrations(List.of(registration)).build();
		McpServer server = serverBuilder(endpoint).build();
		Soklet owner = managedSoklet(server);
		try {
			owner.start();
			int port = port(server);
			HttpResponse<String> discover = send(port, "discover", "server/discover", "", null, null);
			assertSuccess(discover, "discover");
			assertContains(discover.body(), "\"resources\":{");
			assertContains(discover.body(), "\"io.modelcontextprotocol/skills\":{");
			Assertions.assertFalse(discover.body().contains("\"directoryRead\":true"), discover.body());

			HttpResponse<String> list = send(port, "list", "skills/list", "", null, null);
			assertSuccess(list, "list");
			assertContains(list.body(), "\"resultType\":\"complete\"");
			assertContains(list.body(), "\"skills\":[{");
			assertContains(list.body(), "\"uri\":\"" + registration.getUri() + "\"");
			assertContains(list.body(), "\"name\":\"test-skill\"");
			assertContains(list.body(), "\"description\":\"Original description\"");
			assertContains(list.body(), "\"digest\":\"" + digest(root) + "\"");
			assertContains(list.body(), "\"digest\":\"" + digest(binary) + "\"");
			assertContains(list.body(), "\"size\":" + root.length);
			assertContains(list.body(), "\"size\":" + binary.length);
			assertContains(list.body(), "\"ttlMs\":0");
			assertContains(list.body(), "\"cacheScope\":\"private\"");
			Assertions.assertFalse(list.body().contains("\"nextCursor\""), list.body());

			HttpResponse<String> get = skillGet(port, "get", registration.getUri());
			assertSuccess(get, "get");
			assertContains(get.body(), "\"skill\":{");
			assertContains(get.body(), "\"digest\":\"" + digest(root) + "\"");
			assertContains(get.body(), "\"digest\":\"" + digest(binary) + "\"");

			HttpResponse<String> ordinary = send(port, "ordinary", "resources/list", "", null, null);
			assertSuccess(ordinary, "ordinary");
			assertContains(ordinary.body(), "\"resources\":[]");
			Assertions.assertFalse(ordinary.body().contains(registration.getUri().toString()), ordinary.body());

			HttpResponse<String> rootRead = resourceRead(port, "root-read", registration.getUri());
			assertSuccess(rootRead, "root-read");
			assertContains(rootRead.body(), "\"mimeType\":\"text/markdown\"");
			assertContains(rootRead.body(), "\"text\":");
			assertContains(rootRead.body(), "name: test-skill");
			URI binaryUri = URI.create("skill://example/test-skill/assets/sample.bin");
			HttpResponse<String> binaryRead = resourceRead(port, "binary-read", binaryUri);
			assertSuccess(binaryRead, "binary-read");
			assertContains(binaryRead.body(), "\"blob\":\"AAH/Aw==\"");
		} finally {
			owner.close();
		}
	}

	@Test
	public void accessRevocationAndSharedNestedFileOwnersAreCheckedOnEveryRead() throws Exception {
		byte[] parentRoot = skillDocument("parent", "Parent description");
		byte[] childRoot = skillDocument("child", "Child description");
		McpSkillRegistration parent = registration("skill://shared/parent/SKILL.md",
				Map.of("SKILL.md", parentRoot, "child/SKILL.md", childRoot));
		McpSkillRegistration child = registration("skill://shared/parent/child/SKILL.md",
				Map.of("SKILL.md", childRoot));
		AtomicBoolean parentAllowed = new AtomicBoolean(false);
		AtomicBoolean childAllowed = new AtomicBoolean(true);
		McpSkillAccessPolicy policy = McpSkillAccessPolicy.fromEvaluators(
				(request, candidate, features) -> {
					if (candidate == parent) return parentAllowed.get();
					if (candidate == child) return childAllowed.get();
					throw new AssertionError("Unexpected registration");
				},
				(request, candidate, features) -> true);
		McpEndpoint endpoint = endpointBuilder().skillRegistrations(List.of(parent, child)).build();
		McpServer server = serverBuilder(endpoint).skillAccessPolicy(policy).build();
		Soklet owner = managedSoklet(server);
		try {
			owner.start();
			int port = port(server);
			assertSuccess(resourceRead(port, "child-grant", child.getUri()), "child-grant");
			parentAllowed.set(true);
			childAllowed.set(false);
			assertSuccess(resourceRead(port, "parent-grant", child.getUri()), "parent-grant");
			HttpResponse<String> deniedGet = skillGet(port, "child-denied", child.getUri());
			Assertions.assertNotEquals(200, deniedGet.statusCode(), deniedGet.body());
			HttpResponse<String> parentGet = skillGet(port, "parent-allowed", parent.getUri());
			assertSuccess(parentGet, "parent-allowed");
			parentAllowed.set(false);
			HttpResponse<String> deniedRead = resourceRead(port, "both-denied", child.getUri());
			HttpResponse<String> unknownRead = resourceRead(port, "unknown-read",
					URI.create("skill://shared/unknown/SKILL.md"));
			Assertions.assertEquals(unknownRead.statusCode(), deniedRead.statusCode());
			Assertions.assertNotEquals(200, deniedRead.statusCode(), deniedRead.body());
			Assertions.assertFalse(deniedRead.body().contains("Child description"), deniedRead.body());
			HttpResponse<String> hiddenList = send(port, "hidden", "skills/list", "", null, null);
			assertSuccess(hiddenList, "hidden");
			assertContains(hiddenList.body(), "\"skills\":[]");
		} finally {
			owner.close();
		}
	}

	@Test
	public void groupsSelectOneAccessibleVariantAndVaryWithoutLocalizer() throws Exception {
		McpSkillRegistration french = registration("skill://variants/fr/greet/SKILL.md",
				Map.of("SKILL.md", skillDocument("greet", "Bonjour")), Locale.FRENCH);
		McpSkillRegistration english = registration("skill://variants/en/greet/SKILL.md",
				Map.of("SKILL.md", skillDocument("greet", "Hello")), Locale.ENGLISH);
		McpSkillGroup group = McpSkillGroup.fromKeyAndSkillRegistrations("greeting", List.of(french, english));
		AtomicInteger selections = new AtomicInteger();
		McpSkillVariantSelector selector = (request, context, features) -> {
			selections.incrementAndGet();
			Assertions.assertEquals("greeting", context.getSkillGroupKey());
			Assertions.assertEquals(List.of(french, english), context.getSkillRegistrations());
			return Optional.of(context.getLanguageRanges().stream()
					.anyMatch(range -> range.getRange().startsWith("fr")) ? french : english);
		};
		McpEndpoint endpoint = endpointBuilder().skillGroups(List.of(group))
				.skillListCachePolicy(McpCachePolicy.fromPublicTimeToLive(Duration.ofMinutes(1))).build();
		McpServer server = serverBuilder(endpoint).skillVariantSelector(selector).build();
		Soklet owner = managedSoklet(server);
		try {
			owner.start();
			int port = port(server);
			HttpResponse<String> selected = send(port, "selected", "skills/list", "", null, "fr");
			assertSuccess(selected, "selected");
			assertContains(selected.body(), french.getUri().toString());
			Assertions.assertFalse(selected.body().contains(english.getUri().toString()), selected.body());
			assertContains(selected.body(), "\"cacheScope\":\"private\"");
			assertContains(selected.body(), "\"ttlMs\":0");
			assertContains(selected.headers().firstValue("Vary").orElse(""), "Accept-Language");
			Assertions.assertEquals(1, selections.get());
			assertSuccess(skillGet(port, "direct-other", english.getUri()), "direct-other");
			Assertions.assertEquals(1, selections.get(), "Direct lookup must bypass selection");
		} finally {
			owner.close();
		}
	}

	@Test
	public void customPaginationPreservesPresentEmptyCursorAndDoesNotReselect() throws Exception {
		McpSkillRegistration standalone = registration("skill://pages/standalone/SKILL.md",
				Map.of("SKILL.md", skillDocument("standalone", "Standalone")));
		McpSkillRegistration french = registration("skill://pages/fr/greet/SKILL.md",
				Map.of("SKILL.md", skillDocument("greet", "Bonjour")), Locale.FRENCH);
		McpSkillRegistration english = registration("skill://pages/en/greet/SKILL.md",
				Map.of("SKILL.md", skillDocument("greet", "Hello")), Locale.ENGLISH);
		McpSkillGroup group = McpSkillGroup.fromKeyAndSkillRegistrations("group", List.of(french, english));
		AtomicInteger selections = new AtomicInteger();
		AtomicReference<List<McpSkillRegistration>> snapshot = new AtomicReference<>();
		List<Optional<String>> observedCursors = new ArrayList<>();
		List<Boolean> observedFirstPages = new ArrayList<>();
		McpSkillListHandler handler = (request, context, features) -> {
			observedCursors.add(context.getCursor());
			observedFirstPages.add(context.getInitialSkillRegistrations().isPresent());
			if (context.getInitialSkillRegistrations().isPresent()) {
				List<McpSkillRegistration> initial = context.getInitialSkillRegistrations().orElseThrow();
				Assertions.assertEquals(2, initial.size());
				snapshot.set(initial);
				return McpSkillPage.builder().skillRegistrations(List.of(initial.get(0)))
						.nextCursor("").metadata(McpJsonObject.builder().put("page", 1).build())
						.build();
			}
			Assertions.assertEquals(Optional.of(""), context.getCursor());
			return McpSkillPage.builder().skillRegistrations(List.of(snapshot.get().get(1))).build();
		};
		McpEndpoint endpoint = endpointBuilder().skillRegistrations(List.of(standalone))
				.skillGroups(List.of(group)).skillListHandler(handler).build();
		McpServer server = serverBuilder(endpoint).skillVariantSelector((request, context, features) -> {
			selections.incrementAndGet();
			return Optional.of(french);
		}).build();
		Soklet owner = managedSoklet(server);
		try {
			owner.start();
			int port = port(server);
			HttpResponse<String> first = send(port, "first", "skills/list", "", null, "fr");
			assertSuccess(first, "first");
			assertContains(first.body(), "\"nextCursor\":\"\"");
			assertContains(first.body(), "\"page\":1");
			HttpResponse<String> continuation = send(port, "continuation", "skills/list",
					",\"cursor\":\"\"", null, "en");
			assertSuccess(continuation, "continuation");
			assertContains(continuation.body(), french.getUri().toString());
			Assertions.assertFalse(continuation.body().contains(english.getUri().toString()), continuation.body());
			Assertions.assertEquals(1, selections.get());
			Assertions.assertEquals(List.of(Optional.empty(), Optional.of("")), observedCursors);
			Assertions.assertEquals(List.of(true, false), observedFirstPages);
		} finally {
			owner.close();
		}
	}

	@Test
	public void continuationRechecksAccessAndDiscoveryWithoutReselectingVariants() throws Exception {
		McpSkillRegistration french = registration("skill://continuation/fr/private-skill/SKILL.md",
				Map.of("SKILL.md", skillDocument("private-skill", "private-continuation-canary")),
				Locale.FRENCH);
		McpSkillRegistration english = registration("skill://continuation/en/private-skill/SKILL.md",
				Map.of("SKILL.md", skillDocument("private-skill", "English")), Locale.ENGLISH);
		McpSkillGroup group = McpSkillGroup.fromKeyAndSkillRegistrations(
				"private-skill", List.of(french, english));
		AtomicBoolean accessible = new AtomicBoolean(true);
		AtomicBoolean discoverable = new AtomicBoolean(true);
		AtomicInteger accessChecks = new AtomicInteger();
		AtomicInteger discoveryChecks = new AtomicInteger();
		AtomicInteger selections = new AtomicInteger();
		AtomicInteger handlerCalls = new AtomicInteger();
		AtomicReference<McpSkillRegistration> selected = new AtomicReference<>();
		McpSkillAccessPolicy policy = McpSkillAccessPolicy.fromEvaluators(
				(request, candidate, features) -> {
					accessChecks.incrementAndGet();
					return accessible.get();
				},
				(request, candidate, features) -> {
					discoveryChecks.incrementAndGet();
					return discoverable.get();
				});
		McpEndpoint endpoint = endpointBuilder().skillGroups(List.of(group))
				.skillListHandler((request, context, features) -> {
					handlerCalls.incrementAndGet();
					if (context.getInitialSkillRegistrations().isPresent()) {
						selected.set(context.getInitialSkillRegistrations().orElseThrow().get(0));
						return McpSkillPage.builder().nextCursor("continue").build();
					}
					Assertions.assertEquals(Optional.of("continue"), context.getCursor());
					return McpSkillPage.builder().skillRegistrations(List.of(selected.get())).build();
				})
				.build();
		McpServer server = serverBuilder(endpoint).skillAccessPolicy(policy)
				.skillVariantSelector((request, context, features) -> {
					selections.incrementAndGet();
					return Optional.of(french);
				})
				.build();
		Soklet owner = managedSoklet(server);
		try {
			owner.start();
			int port = port(server);
			HttpResponse<String> first = send(port, "first", "skills/list", "", null, "fr");
			assertSuccess(first, "first");
			assertContains(first.body(), "\"nextCursor\":\"continue\"");
			Assertions.assertEquals(1, selections.get());

			accessible.set(false);
			HttpResponse<String> revoked = send(port, "revoked", "skills/list",
					",\"cursor\":\"continue\"", null, "fr");
			assertError(revoked, 500, -32603, "revoked");
			Assertions.assertFalse(revoked.body().contains("private-continuation-canary"), revoked.body());
			Assertions.assertFalse(revoked.body().contains(french.getUri().toString()), revoked.body());

			accessible.set(true);
			discoverable.set(false);
			HttpResponse<String> hidden = send(port, "hidden", "skills/list",
					",\"cursor\":\"continue\"", null, "fr");
			assertError(hidden, 500, -32603, "hidden");
			Assertions.assertFalse(hidden.body().contains("private-continuation-canary"), hidden.body());
			Assertions.assertFalse(hidden.body().contains(french.getUri().toString()), hidden.body());
			Assertions.assertEquals(1, selections.get(), "Continuation must not rerun variant selection");
			Assertions.assertEquals(3, handlerCalls.get());
			Assertions.assertEquals(4, accessChecks.get());
			Assertions.assertEquals(3, discoveryChecks.get());
		} finally {
			owner.close();
		}
	}

	@Test
	public void customListTimeToLiveOverrideCannotWidenThePrivateCacheClamp() throws Exception {
		McpSkillRegistration registration = registration("skill://cache/private-cache/SKILL.md",
				Map.of("SKILL.md", skillDocument("private-cache", "Private cache")));
		McpEndpoint endpoint = endpointBuilder().skillRegistrations(List.of(registration))
				.skillListCachePolicy(McpCachePolicy.fromPublicTimeToLive(Duration.ofSeconds(30)))
				.skillListHandler((request, context, features) -> McpSkillPage.builder()
						.skillRegistrations(context.getInitialSkillRegistrations().orElseThrow())
						.cacheTimeToLiveOverride(Duration.ofHours(1))
						.build())
				.build();
		McpServer server = serverBuilder(endpoint).build();
		Soklet owner = managedSoklet(server);
		try {
			owner.start();
			HttpResponse<String> response = send(port(server), "cache", "skills/list", "", null, null);
			assertSuccess(response, "cache");
			assertContains(response.body(), "\"ttlMs\":0");
			assertContains(response.body(), "\"cacheScope\":\"private\"");
			Assertions.assertFalse(response.body().contains("\"ttlMs\":3600000"), response.body());
		} finally {
			owner.close();
		}
	}

	@Test
	public void skillsListCursorBoundsApplyBeforeCallbacksAndRedactInvalidOutput() throws Exception {
		McpSkillRegistration registration = registration("skill://cursors/cursor-test/SKILL.md",
				Map.of("SKILL.md", skillDocument("cursor-test", "Cursor test")));
		AtomicInteger handlerCalls = new AtomicInteger();
		McpEndpoint endpoint = endpointBuilder().skillRegistrations(List.of(registration))
				.skillListHandler((request, context, features) -> {
					handlerCalls.incrementAndGet();
					return switch (context.getCursor().orElseThrow()) {
						case "exact" -> McpSkillPage.builder().nextCursor("世界").build();
						case "big" -> McpSkillPage.builder().nextCursor("世界語").build();
						case "unicode" -> McpSkillPage.builder()
								.nextCursor(new String(new char[]{Character.MIN_HIGH_SURROGATE})
										+ "private-cursor-canary")
								.build();
						default -> throw new AssertionError("Unexpected cursor");
					};
				})
				.build();
		McpServer server = serverBuilder(endpoint).maximumCursorSizeInBytes(8).build();
		Soklet owner = managedSoklet(server);
		try {
			owner.start();
			int port = port(server);
			HttpResponse<String> oversizedInput = send(port, "oversized-input", "skills/list",
					",\"cursor\":\"世界語\"", null, null);
			assertError(oversizedInput, 400, -32602, "oversized-input");
			HttpResponse<String> wrongType = send(port, "wrong-type", "skills/list",
					",\"cursor\":7", null, null);
			assertError(wrongType, 400, -32602, "wrong-type");
			Assertions.assertEquals(0, handlerCalls.get(), "Invalid inputs must not enter the callback");

			HttpResponse<String> exactOutput = send(port, "exact-output", "skills/list",
					",\"cursor\":\"exact\"", null, null);
			assertSuccess(exactOutput, "exact-output");
			assertContains(exactOutput.body(), "\"nextCursor\":\"世界\"");
			HttpResponse<String> oversizedOutput = send(port, "oversized-output", "skills/list",
					",\"cursor\":\"big\"", null, null);
			assertError(oversizedOutput, 500, -32603, "oversized-output");
			Assertions.assertFalse(oversizedOutput.body().contains("世界語"), oversizedOutput.body());
			HttpResponse<String> malformedOutput = send(port, "malformed-output", "skills/list",
					",\"cursor\":\"unicode\"", null, null);
			assertError(malformedOutput, 500, -32603, "malformed-output");
			Assertions.assertFalse(malformedOutput.body().contains("private-cursor-canary"),
					malformedOutput.body());
			Assertions.assertEquals(3, handlerCalls.get());
		} finally {
			owner.close();
		}
	}

	@Test
	public void ordinaryCustomResourceListCannotExposeAnIndexedSkillFile() throws Exception {
		McpSkillRegistration registration = registration("skill://resource-list/private-resource/SKILL.md",
				Map.of("SKILL.md", skillDocument("private-resource", "private-resource-canary")));
		AtomicInteger handlerCalls = new AtomicInteger();
		McpEndpoint endpoint = endpointBuilder().skillRegistrations(List.of(registration))
				.resourceListHandler((request, context, features) -> {
					handlerCalls.incrementAndGet();
					return McpResourcePage.builder().resourceDescriptors(List.of(
							McpResourceDescriptor.withUriAndName(registration.getUri(),
									"private-resource-list-canary").build()))
						.build();
				})
				.build();
		McpServer server = serverBuilder(endpoint).build();
		Soklet owner = managedSoklet(server);
		try {
			owner.start();
			HttpResponse<String> response = send(port(server), "ordinary", "resources/list", "", null, null);
			assertError(response, 500, -32603, "ordinary");
			Assertions.assertFalse(response.body().contains("private-resource-list-canary"), response.body());
			Assertions.assertFalse(response.body().contains("private-resource-canary"), response.body());
			Assertions.assertFalse(response.body().contains(registration.getUri().toString()), response.body());
			Assertions.assertEquals(1, handlerCalls.get());
		} finally {
			owner.close();
		}
	}

	@Test
	public void skillFileInterceptionRunsAfterAuthorizationAndCannotChangeCanonicalContents() throws Exception {
		byte[] root = skillDocument("intercepted-skill", "Canonical description");
		String canonicalText = new String(root, StandardCharsets.UTF_8);
		McpSkillRegistration registration = registration("skill://interceptor/intercepted-skill/SKILL.md",
				Map.of("SKILL.md", root));
		AtomicBoolean accessible = new AtomicBoolean(true);
		AtomicReference<String> mode = new AtomicReference<>("pass");
		AtomicInteger interceptorCalls = new AtomicInteger();
		McpSkillAccessPolicy policy = McpSkillAccessPolicy.fromEvaluators(
				(request, candidate, features) -> accessible.get(),
				(request, candidate, features) -> true);
		McpEndpoint endpoint = endpointBuilder().skillRegistrations(List.of(registration)).build();
		McpServer server = serverBuilder(endpoint).skillAccessPolicy(policy)
				.handlerInterceptor((context, features, continuation) -> {
					interceptorCalls.incrementAndGet();
					Assertions.assertEquals("resources/read", context.getJsonRpcMethod());
					Assertions.assertEquals(Optional.of(registration.getUri().toString()),
							context.getOperationName());
					return switch (mode.get()) {
						case "pass" -> continuation.proceed();
						case "bytes" -> textResourceResult(registration.getUri(),
								"private-mutated-bytes-canary");
						case "uri" -> textResourceResult(
								URI.create("skill://interceptor/changed/SKILL.md"), canonicalText);
						case "fabricate" -> textResourceResult(registration.getUri(),
								"fabricated-access-canary");
						default -> throw new AssertionError("Unexpected interceptor mode");
					};
				})
				.build();
		Soklet owner = managedSoklet(server);
		try {
			owner.start();
			int port = port(server);
			assertSuccess(send(port, "list", "skills/list", "", null, null), "list");
			assertSuccess(skillGet(port, "get", registration.getUri()), "get");
			Assertions.assertEquals(0, interceptorCalls.get(),
					"Automatic Skills catalogs and get must bypass handler interception");

			HttpResponse<String> read = resourceRead(port, "read", registration.getUri());
			assertSuccess(read, "read");
			assertContains(read.body(), "name: intercepted-skill");
			Assertions.assertEquals(1, interceptorCalls.get());

			mode.set("bytes");
			HttpResponse<String> changedBytes = resourceRead(port, "changed-bytes", registration.getUri());
			assertFixedInternalError(changedBytes, "changed-bytes");
			Assertions.assertFalse(changedBytes.body().contains("private-mutated-bytes-canary"),
					changedBytes.body());
			mode.set("uri");
			HttpResponse<String> changedUri = resourceRead(port, "changed-uri", registration.getUri());
			assertFixedInternalError(changedUri, "changed-uri");
			Assertions.assertFalse(changedUri.body().contains("skill://interceptor/changed/SKILL.md"),
					changedUri.body());
			Assertions.assertEquals(3, interceptorCalls.get());

			accessible.set(false);
			mode.set("fabricate");
			HttpResponse<String> denied = resourceRead(port, "denied", registration.getUri());
			assertError(denied, 400, -32602, "denied");
			Assertions.assertFalse(denied.body().contains("fabricated-access-canary"), denied.body());
			Assertions.assertEquals(3, interceptorCalls.get(),
					"Denied Skill files must not enter an interceptor that could fabricate access");
		} finally {
			owner.close();
		}
	}

	@Test
	public void foreignReorderedAndDuplicateCustomPagesFailBeforePublication() throws Exception {
		McpSkillRegistration first = registration("skill://invalid-pages/first/SKILL.md",
				Map.of("SKILL.md", skillDocument("first", "First")));
		McpSkillRegistration second = registration("skill://invalid-pages/second/SKILL.md",
				Map.of("SKILL.md", skillDocument("second", "Second")));
		McpSkillRegistration reconstructed = registration("skill://invalid-pages/first/SKILL.md",
				Map.of("SKILL.md", skillDocument("first", "First")));
		AtomicReference<String> mode = new AtomicReference<>("foreign");
		McpSkillListHandler handler = (request, context, features) -> {
			List<McpSkillRegistration> initial = context.getInitialSkillRegistrations().orElseThrow();
			return switch (mode.get()) {
				case "foreign" -> McpSkillPage.builder().skillRegistrations(List.of(reconstructed)).build();
				case "reordered" -> McpSkillPage.builder().skillRegistrations(List.of(initial.get(1), initial.get(0))).build();
				case "duplicate" -> McpSkillPage.builder().skillRegistrations(List.of(initial.get(0), initial.get(0))).build();
				default -> throw new AssertionError("Unexpected mode");
			};
		};
		McpEndpoint endpoint = endpointBuilder().skillRegistrations(List.of(first, second))
				.skillListHandler(handler).build();
		McpServer server = serverBuilder(endpoint).build();
		Soklet owner = managedSoklet(server);
		try {
			owner.start();
			int port = port(server);
			for (String invalid : List.of("foreign", "reordered", "duplicate")) {
				mode.set(invalid);
				HttpResponse<String> response = send(port, invalid, "skills/list", "", null, null);
				Assertions.assertEquals(500, response.statusCode(), response.body());
				assertContains(response.body(), "\"code\":-32603");
				Assertions.assertFalse(response.body().contains("First"), response.body());
				Assertions.assertFalse(response.body().contains("Second"), response.body());
			}
		} finally {
			owner.close();
		}
	}

	@Test
	public void localizationProviderSeesOperationSpecificFirstAndPresentEmptyContinuationCursor() throws Exception {
		McpSkillRegistration registration = registration("skill://locale/test-skill/SKILL.md",
				Map.of("SKILL.md", skillDocument("test-skill", "Test")));
		List<Optional<String>> providerCursors = new ArrayList<>();
		McpLocalizer localizer = McpLocalizer.withFallbackLocale(Locale.ENGLISH, request -> {
			providerCursors.add(request.getSkillListCursor());
			Assertions.assertTrue(request.getResourceListCursor().isEmpty());
			return McpLocalizationContext.withLocale(Locale.ENGLISH,
					text -> McpLocalizationResult.useDefaultText()).build();
		}).build();
		McpEndpoint endpoint = endpointBuilder().skillRegistrations(List.of(registration))
				.skillListHandler((request, context, features) -> context.getInitialSkillRegistrations().isPresent()
						? McpSkillPage.builder().skillRegistrations(List.of(registration)).nextCursor("").build()
						: McpSkillPage.builder().build())
				.build();
		McpServer server = serverBuilder(endpoint).localizer(localizer).build();
		Soklet owner = managedSoklet(server);
		try {
			owner.start();
			int port = port(server);
			HttpResponse<String> first = send(port, "first", "skills/list", "", null, "en");
			assertSuccess(first, "first");
			Assertions.assertTrue(first.headers().allValues("Content-Language").isEmpty(),
					first.headers().map().toString());
			HttpResponse<String> continuation = send(port, "continuation", "skills/list",
					",\"cursor\":\"\"", null, "en");
			assertSuccess(continuation, "continuation");
			Assertions.assertTrue(continuation.headers().allValues("Content-Language").isEmpty(),
					continuation.headers().map().toString());
			Assertions.assertEquals(List.of(Optional.empty(), Optional.of("")), providerCursors);
		} finally {
			owner.close();
		}
	}

	private static byte[] skillDocument(String name, String description) {
		return ("---\nname: " + name + "\ndescription: " + description + "\n---\nInstructions.\n")
				.getBytes(StandardCharsets.UTF_8);
	}

	private static McpSkillRegistration registration(String uri, Map<String, byte[]> files) {
		return registration(uri, files, null);
	}

	private static McpSkillRegistration registration(String uri, Map<String, byte[]> files,
			Locale locale) {
		McpSkillRegistration.Builder builder = McpSkillRegistration.withUriAndSkillBundle(
				URI.create(uri), McpSkillBundle.fromFiles(files));
		if (locale != null) builder.locale(locale);
		return builder.build();
	}

	private static String digest(byte[] bytes) throws Exception {
		return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
	}

	private static McpCompleteResult textResourceResult(URI uri, String text) {
		return McpCompleteResult.fromResourceOutput(McpResourceOutput.withContent(
				McpTextResourceContents.withUriAndText(uri, text)
						.mimeType("text/markdown")
						.build())
				.build());
	}

	private static McpEndpoint.Builder endpointBuilder() {
		return McpEndpoint.withPath(MCP_PATH, McpImplementation.withNameAndVersion(
				"skill-public-runtime-test", "4.0.0").build());
	}

	private static McpServer.Builder serverBuilder(McpEndpoint endpoint) {
		return McpServer.withPort(0)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.host(LOOPBACK)
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(LOOPBACK));
	}

	private static Soklet managedSoklet(McpServer server) {
		return Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.build());
	}

	private static int port(McpServer server) {
		return server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
	}

	private static HttpResponse<String> skillGet(int port, String id, URI uri) throws Exception {
		return send(port, id, "skills/get", ",\"uri\":\"" + uri + "\"", null, null);
	}

	private static HttpResponse<String> resourceRead(int port, String id, URI uri) throws Exception {
		return send(port, id, "resources/read", ",\"uri\":\"" + uri + "\"", uri.toString(), null);
	}

	private static HttpResponse<String> send(int port, String id, String method,
			String additionalParameters, String nameHeader, String language) throws Exception {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\",\"method\":\"" + method
				+ "\",\"params\":{\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\",\"io.modelcontextprotocol/clientCapabilities\":{}}"
				+ additionalParameters + "}}";
		HttpRequest.Builder request = HttpRequest.newBuilder()
				.uri(URI.create("http://" + LOOPBACK + ":" + port + MCP_PATH))
				.timeout(Duration.ofSeconds(5))
				.header("Content-Type", JSON_MEDIA_TYPE + "; charset=UTF-8")
				.header("Accept", JSON_MEDIA_TYPE + ", text/event-stream")
				.header("MCP-Protocol-Version", PROTOCOL_VERSION)
				.header("Mcp-Method", method);
		if (nameHeader != null) request.header("Mcp-Name", nameHeader);
		if (language != null) request.header("Accept-Language", language);
		return HTTP_CLIENT.send(request.POST(HttpRequest.BodyPublishers.ofString(body,
				StandardCharsets.UTF_8)).build(),
				HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	private static void assertSuccess(HttpResponse<String> response, String id) {
		Assertions.assertEquals(200, response.statusCode(), response.body());
		Assertions.assertEquals(JSON_MEDIA_TYPE,
				response.headers().firstValue("Content-Type").orElseThrow());
		Assertions.assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
		assertContains(response.body(), "\"id\":\"" + id + "\"");
	}

	private static void assertError(HttpResponse<String> response, int status,
			int code, String expectedId) {
		Assertions.assertEquals(status, response.statusCode(), response.body());
		assertContains(response.body(), "\"code\":" + code);
		assertContains(response.body(), "\"id\":\"" + expectedId + "\"");
	}

	private static void assertFixedInternalError(HttpResponse<String> response, String expectedId) {
		assertError(response, 500, -32603, expectedId);
		Assertions.assertEquals("{\"jsonrpc\":\"2.0\",\"id\":\"" + expectedId
				+ "\",\"error\":{\"code\":-32603,\"message\":\"Internal error\"}}",
				response.body());
	}

	private static void assertContains(String text, String fragment) {
		Assertions.assertTrue(text.contains(fragment),
				() -> "Expected <" + text + "> to contain <" + fragment + ">.");
	}
}
