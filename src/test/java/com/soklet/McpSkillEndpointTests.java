/*
 * Copyright 2022-2026 Revetware LLC.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.soklet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class McpSkillEndpointTests {
	@Test
	void emptyEndpointHasSeparateEmptySkillCollectionsAndNoOwnedFiles() {
		McpEndpoint endpoint = builder().build();
		assertTrue(endpoint.getSkillRegistrations().isEmpty());
		assertTrue(endpoint.getSkillGroups().isEmpty());
		assertTrue(endpoint.skillIndex().registrations().isEmpty());
		assertTrue(endpoint.skillIndex().findFile(URI.create("skill://host.invalid/absent/SKILL.md")).isEmpty());
	}

	@Test
	void wholeCollectionSettersSnapshotReplaceClearAndRejectNullElementsAtomically() {
		McpSkillRegistration standalone = standalone("single");
		McpSkillGroup group = group("variants", "translated");
		List<McpSkillRegistration> registrations = new ArrayList<>(List.of(standalone));
		List<McpSkillGroup> groups = new ArrayList<>(List.of(group));
		McpEndpoint.Builder builder = builder().skillRegistrations(registrations).skillGroups(groups);
		registrations.clear();
		groups.clear();
		McpEndpoint first = builder.build();
		assertEquals(List.of(standalone), first.getSkillRegistrations());
		assertEquals(List.of(group), first.getSkillGroups());
		assertThrows(NullPointerException.class, () -> builder.skillRegistrations(Arrays.asList(standalone, null)));
		assertThrows(NullPointerException.class, () -> builder.skillGroups(Arrays.asList(group, null)));
		assertEquals(first.getSkillRegistrations(), builder.build().getSkillRegistrations());
		assertEquals(first.getSkillGroups(), builder.build().getSkillGroups());
		McpSkillRegistration replacement = standalone("replacement");
		assertEquals(List.of(replacement), builder.skillRegistrations(List.of(replacement)).build().getSkillRegistrations());
		assertTrue(builder.skillRegistrations(null).skillGroups(null).build().skillIndex().registrations().isEmpty());
		assertTrue(builder.skillRegistrations(List.of()).skillGroups(List.of()).build().skillIndex().registrations().isEmpty());
		assertEquals(List.of(standalone), first.getSkillRegistrations());
		assertThrows(UnsupportedOperationException.class, () -> first.getSkillRegistrations().clear());
		assertThrows(UnsupportedOperationException.class, () -> first.getSkillGroups().clear());
	}

	@Test
	void aggregateOrderIsStandaloneThenGroupMemberOrderRegardlessOfSetterOrder() {
		McpSkillRegistration first = standalone("first"), second = standalone("second");
		McpSkillGroup group = group("variants", "translated");
		McpEndpoint endpoint = builder().skillGroups(List.of(group)).skillRegistrations(List.of(first, second)).build();
		List<McpSkillRegistration> expected = new ArrayList<>(List.of(first, second));
		expected.addAll(group.getSkillRegistrations());
		assertEquals(expected, endpoint.skillIndex().registrations());
		assertEquals(List.of(first, second), endpoint.getSkillRegistrations());
		assertThrows(UnsupportedOperationException.class, () -> endpoint.skillIndex().registrations().clear());
		assertEquals(expected, builder().skillRegistrations(List.of(first, second)).skillGroups(List.of(group))
				.build().skillIndex().registrations());
	}

	@Test
	void duplicateRegistrationUrisRejectAcrossStandaloneAndGroupedSources() {
		McpSkillRegistration registration = standalone("same");
		assertInvalid(() -> builder().skillRegistrations(List.of(registration, registration)).build());
		McpSkillGroup group = McpSkillGroup.fromKeyAndSkillRegistrations("group", List.of(registration));
		assertInvalid(() -> builder().skillRegistrations(List.of(registration)).skillGroups(List.of(group)).build());
	}

	@Test
	void eachSkillNameHasExactlyOneStandaloneOrGroupListingSlot() {
		McpSkillRegistration first = registration("same", "skill://host.invalid/one/same/SKILL.md", Map.of(), null, noCache());
		McpSkillRegistration second = registration("same", "skill://host.invalid/two/same/SKILL.md", Map.of(), Locale.FRENCH, noCache());
		assertInvalid(() -> builder().skillRegistrations(List.of(first, second)).build());
		McpSkillGroup group = McpSkillGroup.fromKeyAndSkillRegistrations("group", List.of(second));
		assertInvalid(() -> builder().skillRegistrations(List.of(first)).skillGroups(List.of(group)).build());
		assertInvalid(() -> builder().skillGroups(List.of(group,
				McpSkillGroup.fromKeyAndSkillRegistrations("other-group", List.of(first)))).build());
	}

	@Test
	void duplicateGroupKeysRejectButEmptyGroupsClaimNoSkillName() {
		McpSkillGroup empty = McpSkillGroup.fromKeyAndSkillRegistrations("private-key-canary", List.of());
		assertInvalid(() -> builder().skillGroups(List.of(empty, empty)).build());
		McpSkillRegistration standalone = standalone("same");
		McpEndpoint endpoint = builder().skillRegistrations(List.of(standalone)).skillGroups(List.of(empty,
				McpSkillGroup.fromKeyAndSkillRegistrations("other-empty", List.of()))).build();
		assertEquals(List.of(standalone), endpoint.skillIndex().registrations());
	}

	@Test
	void completeNestedSnapshotsShareOneImmutableCanonicalFileAndOrderedOwners() {
		List<McpSkillRegistration> family = family(noCache(), noCache(), Map.of());
		McpEndpoint endpoint = builder().skillRegistrations(family).build();
		URI sharedUri = URI.create("skill://host.invalid/parent/child/shared.bin");
		var shared = endpoint.skillIndex().findFile(sharedUri).orElseThrow();
		assertEquals(sharedUri, shared.uri());
		assertEquals(family, shared.owners());
		assertThrows(UnsupportedOperationException.class, () -> shared.owners().clear());
		assertThrows(UnsupportedOperationException.class, () -> shared.readResult().members().clear());
		assertEquals(List.of(family.get(0)), endpoint.skillIndex()
				.findFile(family.get(0).getUri()).orElseThrow().owners());
		assertEquals(family, endpoint.skillIndex().findFile(family.get(1).getUri()).orElseThrow().owners());
	}

	@Test
	void parentMustIncludeDescendantRootAndEverySupportingFileWithoutPartialMerge() {
		McpSkillRegistration child = registration("child", "skill://host.invalid/parent/child/SKILL.md",
				Map.of("shared.bin", new byte[]{1, 2}), null, noCache());
		for (Map<String, byte[]> incomplete : List.of(Map.<String, byte[]>of(),
				Map.of("child/shared.bin", new byte[]{1, 2}), Map.of("child/SKILL.md", root("child")))) {
			McpSkillRegistration parent = registration("parent", "skill://host.invalid/parent/SKILL.md",
					incomplete, null, noCache());
			assertInvalid(() -> builder().skillRegistrations(List.of(parent, child)).build());
			assertInvalid(() -> builder().skillRegistrations(List.of(child, parent)).build());
		}
	}

	@Test
	void sharedContentConflictRejectsRegardlessOfRegistrationOrder() {
		List<McpSkillRegistration> family = family(noCache(), noCache(), Map.of("child/shared.bin", new byte[]{1, 3}));
		assertInvalid(() -> builder().skillRegistrations(family).build());
		assertInvalid(() -> builder().skillRegistrations(List.of(family.get(1), family.get(0))).build());
	}

	@Test
	void deeperDirectoryAncestryIsCompleteAndPrefixLookalikesAreNotDescendants() {
		List<McpSkillRegistration> chain = nestedChain(3);
		McpEndpoint nested = builder().skillRegistrations(chain).build();
		URI deepestFile = URI.create(chain.get(2).getUri().toString().replace("SKILL.md", "shared.bin"));
		assertEquals(chain, nested.skillIndex().findFile(deepestFile).orElseThrow().owners());
		McpEndpoint separate = builder().skillRegistrations(List.of(standalone("parent"), standalone("parent-extra"))).build();
		assertEquals(2, separate.skillIndex().registrations().size());
	}

	@Test
	void ancestryUsesUriIdentityForOriginsAndEscapesIncludingAuthorityRootParents() {
		for (List<String> roots : List.of(
				List.of("SKILL://HOST.invalid/%7a/parent/SKILL.md", "skill://host.invalid/%7A/parent/child/SKILL.md"),
				List.of("skill://parent/SKILL.md", "skill://parent/child/SKILL.md"),
				List.of("skill:///parent/SKILL.md", "skill:/parent/child/SKILL.md"))) {
			McpSkillRegistration child = registration("child", roots.get(1), Map.of(), null, noCache());
			McpSkillRegistration incomplete = registration("parent", roots.get(0), Map.of(), null, noCache());
			assertInvalid(() -> builder().skillRegistrations(List.of(incomplete, child)).build());
			McpSkillRegistration complete = registration("parent", roots.get(0),
					Map.of("child/SKILL.md", root("child")), null, noCache());
			var file = builder().skillRegistrations(List.of(complete, child)).build().skillIndex()
					.findFile(child.getUri()).orElseThrow();
			assertEquals(List.of(complete, child), file.owners());
		}
		McpSkillRegistration encoded = registration("parent", "skill://host.invalid/%7A/parent/SKILL.md",
				Map.of(), null, noCache());
		McpSkillRegistration literal = registration("child", "skill://host.invalid/z/parent/child/SKILL.md",
				Map.of(), null, noCache());
		assertEquals(2, builder().skillRegistrations(List.of(encoded, literal)).build().skillIndex().registrations().size());
	}

	@Test
	void sharedCachePolicyUsesPrivateScopeAndMinimumTtlAcrossAllOwners() {
		List<McpSkillRegistration> family = family(McpCachePolicy.fromPublicTimeToLive(Duration.ofSeconds(30)),
				McpCachePolicy.fromPrivateTimeToLive(Duration.ofSeconds(60)), Map.of());
		var shared = builder().skillRegistrations(family).build().skillIndex()
				.findFile(URI.create("skill://host.invalid/parent/child/shared.bin")).orElseThrow();
		assertEquals(McpCacheScope.PRIVATE, shared.cachePolicy().getScope());
		assertEquals(Duration.ofSeconds(30), shared.cachePolicy().getTimeToLive());
		assertEquals(new com.soklet.internal.mcp.protocol.McpJsonString("private"), shared.readResult().members().get("cacheScope"));
		assertEquals(new com.soklet.internal.mcp.protocol.McpJsonNumber(30_000), shared.readResult().members().get("ttlMs"));
		List<McpSkillRegistration> publicFamily = family(McpCachePolicy.fromPublicTimeToLive(Duration.ofSeconds(30)),
				McpCachePolicy.fromPublicTimeToLive(Duration.ofSeconds(10)), Map.of());
		var publicShared = builder().skillRegistrations(publicFamily).build().skillIndex()
				.findFile(URI.create("skill://host.invalid/parent/child/shared.bin")).orElseThrow();
		assertEquals(McpCacheScope.PUBLIC, publicShared.cachePolicy().getScope());
		assertEquals(Duration.ofSeconds(10), publicShared.cachePolicy().getTimeToLive());
	}

	@Test
	void uriEqualsLookupRetainsFirstOwnerSpellingInCanonicalReads() {
		McpSkillRegistration parent = registration("parent", "SKILL://HOST.invalid/parent/SKILL.md",
				Map.of("child/SKILL.md", root("child"), "child/shared.bin", new byte[]{1, 2}), null, noCache());
		McpSkillRegistration child = registration("child", "skill://host.invalid/parent/child/SKILL.md",
				Map.of("shared.bin", new byte[]{1, 2}), null, noCache());
		var index = builder().skillRegistrations(List.of(parent, child)).build().skillIndex();
		var file = index.findFile(URI.create("skill://host.invalid/parent/child/SKILL.md")).orElseThrow();
		assertEquals("SKILL://HOST.invalid/parent/child/SKILL.md", file.uri().toString());
		assertSame(file, index.findFile(URI.create("SKILL://HOST.invalid/parent/child/SKILL.md")).orElseThrow());
		var contents = (com.soklet.internal.mcp.protocol.McpJsonArray) file.readResult().members().get("contents");
		var content = (com.soklet.internal.mcp.protocol.McpJsonObject) contents.values().get(0);
		assertEquals(new com.soklet.internal.mcp.protocol.McpJsonString(file.uri().toString()), content.members().get("uri"));
	}

	@Test
	void fileOwnerCeilingAllowsSixteenAndRejectsSeventeenCompleteNestedOwners() {
		List<McpSkillRegistration> sixteen = nestedChain(16);
		var index = builder().skillRegistrations(sixteen).build().skillIndex();
		URI deepestRoot = sixteen.get(sixteen.size() - 1).getUri();
		assertEquals(16, index.findFile(deepestRoot).orElseThrow().owners().size());
		assertInvalid(() -> builder().skillRegistrations(nestedChain(17)).build());
	}

	@Test
	void ordinaryExactResourceCannotShadowRootOrSupportingSkillFile() {
		McpSkillRegistration registration = registration("one", "skill://host.invalid/one/SKILL.md",
				Map.of("data.bin", new byte[]{1}), null, noCache());
		for (URI uri : List.of(registration.getUri(), URI.create("SKILL://HOST.invalid/one/data.bin")))
			assertInvalid(() -> builder().skillRegistrations(List.of(registration))
					.resourceRegistrations(List.of(exactResource(uri))).build());
	}

	@Test
	void matchingOrdinaryTemplatesRejectButUnrelatedOrdinaryRoutesRemainValid() {
		McpSkillRegistration registration = standalone("one");
		assertInvalid(() -> builder().skillRegistrations(List.of(registration))
				.resourceRegistrations(List.of(templateResource("skill://host.invalid/one/{file}"))).build());
		McpEndpoint endpoint = builder().skillRegistrations(List.of(registration)).resourceRegistrations(List.of(
				templateResource("skill://host.invalid/elsewhere/{file}"), exactResource(URI.create("test://ordinary")))).build();
		assertEquals(2, endpoint.getResourceRegistrations().size());
		assertTrue(endpoint.skillIndex().findFile(registration.getUri()).isPresent());
	}

	@Test
	void subscriptionOverlayRetainsImmutableSkillCollectionsAndOwnerIndex() {
		McpSkillRegistration standalone = standalone("single");
		McpSkillGroup group = group("variants", "translated");
		McpEndpoint original = builder().skillRegistrations(List.of(standalone)).skillGroups(List.of(group)).build();
		McpSubscriptionConfig config = McpSubscriptionConfig.withEventPublisherAndNotificationTypes(
				McpSubscriptionEventPublisher.fromInMemoryDefaults(), Set.of(McpSubscriptionNotificationType.RESOURCES_LIST_CHANGED)).build();
		McpEndpoint overlay = original.withSubscriptionConfig(config);
		assertSame(original.getSkillRegistrations(), overlay.getSkillRegistrations());
		assertSame(original.getSkillGroups(), overlay.getSkillGroups());
		assertSame(original.skillIndex(), overlay.skillIndex());
		assertSame(config, overlay.getSubscriptionConfig().orElseThrow());
		assertTrue(original.getSubscriptionConfig().isEmpty());
	}

	@Test
	void serverAndSimulatorConfigureSkillsWithoutInvokingApplicationCallbacksOrAllocatingExecutors() {
		AtomicInteger executorCalls = new AtomicInteger();
		McpSkillAccessPolicy policy = McpSkillAccessPolicy.fromEvaluators(
				(request, registration, features) -> {
					throw new AssertionError("Construction must not invoke access policy.");
				}, (request, registration, features) -> {
					throw new AssertionError("Construction must not invoke discovery policy.");
				});
		McpSkillVariantSelector selector = (request, selection, features) -> {
			throw new AssertionError("Construction must not invoke variant selection.");
		};
		McpEndpoint standalone = builder().skillRegistrations(List.of(standalone("one"))).build();
		McpEndpoint grouped = builder().skillGroups(List.of(group("variants", "translated"))).build();
		for (McpEndpoint endpoint : List.of(standalone, grouped)) {
			McpServer server = assertDoesNotThrow(() -> McpServer.withPort(0)
					.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
					.skillAccessPolicy(policy).skillVariantSelector(selector)
					.requestHandlerExecutorServiceSupplier(() -> {
						executorCalls.incrementAndGet();
						throw new AssertionError("Construction must not allocate a request executor.");
					}).build());
			assertSame(policy, server.getSkillAccessPolicy());
			assertSame(selector, server.getSkillVariantSelector().orElseThrow());
			SimulatorConfig simulator = assertDoesNotThrow(() -> SimulatorConfig.fromSokletConfig(
					SokletConfig.withMcpServer(server).build()));
			assertNotNull(simulator.simulatedMcpServer());
			assertSame(endpoint, simulator.simulatedMcpServer().getEndpointRegistry()
					.getEndpoints().iterator().next());
		}
		assertEquals(0, executorCalls.get());
		McpEndpoint empty = builder().skillGroups(List.of(McpSkillGroup.fromKeyAndSkillRegistrations("empty", List.of()))).build();
		McpServer server = assertDoesNotThrow(() -> McpServer.withPort(0)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(empty))).build());
		SimulatorConfig configured = assertDoesNotThrow(() -> SimulatorConfig.withSokletConfig(
				SokletConfig.withMcpServer(server).build()).configureMcpServer(mcp -> mcp
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(standalone)))
				.skillAccessPolicy(policy).skillVariantSelector(selector)).build());
		assertEquals(List.of(standalone), configured.simulatedMcpServer()
				.getEndpointRegistry().getEndpoints().stream().toList());
	}

	private static McpEndpoint.Builder builder() {
		return McpEndpoint.withPath("/skills", McpImplementation.withNameAndVersion("test", "1").build());
	}

	private static McpSkillRegistration standalone(String name) {
		return registration(name, "skill://host.invalid/" + name + "/SKILL.md", Map.of(), null, noCache());
	}

	private static McpSkillGroup group(String key, String name) {
		return McpSkillGroup.fromKeyAndSkillRegistrations(key, List.of(
				registration(name, "skill://host.invalid/fr/" + name + "/SKILL.md", Map.of(), Locale.FRENCH, noCache()),
				registration(name, "skill://host.invalid/en/" + name + "/SKILL.md", Map.of(), Locale.ENGLISH, noCache())));
	}

	private static McpSkillRegistration registration(String name, String uri, Map<String, byte[]> supporting,
			Locale locale, McpCachePolicy cachePolicy) {
		Map<String, byte[]> files = new LinkedHashMap<>();
		files.put("SKILL.md", root(name));
		files.putAll(supporting);
		McpSkillRegistration.Builder builder = McpSkillRegistration.withUriAndSkillBundle(URI.create(uri),
				McpSkillBundle.fromFiles(files)).cachePolicy(cachePolicy);
		if (locale != null) builder.locale(locale);
		return builder.build();
	}

	private static byte[] root(String name) {
		return ("---\nname: " + name + "\ndescription: Synthetic description\n---\nOpaque body.\n")
				.getBytes(StandardCharsets.UTF_8);
	}

	private static List<McpSkillRegistration> family(McpCachePolicy parentPolicy, McpCachePolicy childPolicy,
			Map<String, byte[]> parentReplacements) {
		Map<String, byte[]> parentFiles = new LinkedHashMap<>();
		parentFiles.put("child/SKILL.md", root("child"));
		parentFiles.put("child/shared.bin", new byte[]{1, 2});
		parentFiles.putAll(parentReplacements);
		return List.of(registration("parent", "skill://host.invalid/parent/SKILL.md", parentFiles, null, parentPolicy),
				registration("child", "skill://host.invalid/parent/child/SKILL.md", Map.of("shared.bin", new byte[]{1, 2}), null, childPolicy));
	}

	private static List<McpSkillRegistration> nestedChain(int count) {
		List<McpSkillRegistration> registrations = new ArrayList<>();
		String directory = "skill://host.invalid/";
		for (int parent = 0; parent < count; ++parent) {
			directory += "level-" + parent + "/";
			Map<String, byte[]> files = new LinkedHashMap<>();
			String descendantPath = "";
			for (int child = parent + 1; child < count; ++child) {
				descendantPath += "level-" + child + "/";
				files.put(descendantPath + "SKILL.md", root("level-" + child));
			}
			files.put(descendantPath + "shared.bin", new byte[]{1, 2});
			registrations.add(registration("level-" + parent, directory + "SKILL.md", files, null, noCache()));
		}
		return List.copyOf(registrations);
	}

	private static McpResourceRegistration exactResource(URI uri) {
		return McpResourceRegistration.withUriAndName(uri, "ordinary").handler((request, read, features) -> {
			throw new AssertionError("Construction must not invoke a resource handler.");
		}).build();
	}

	private static McpResourceRegistration templateResource(String template) {
		return McpResourceRegistration.withUriTemplateAndName(template, "ordinary-template").handler((request, read, features) -> {
			throw new AssertionError("Construction must not invoke a resource handler.");
		}).build();
	}

	private static McpCachePolicy noCache() { return McpCachePolicy.privateNoCacheInstance(); }

	private static void assertInvalid(Executable executable) {
		IllegalStateException failure = assertThrows(IllegalStateException.class, executable);
		assertNotNull(failure.getMessage());
		assertNull(failure.getCause());
		assertFalse(failure.getMessage().contains("private-key-canary"));
		assertFalse(failure.getMessage().contains("skill://"));
	}
}
