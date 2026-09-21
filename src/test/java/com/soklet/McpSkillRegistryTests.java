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
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class McpSkillRegistryTests {
	private static final AtomicBoolean ENDPOINT_INITIALIZED = new AtomicBoolean();

	@Test
	void replacementSnapshotsClearsAndPreservesOrderAndProvenance() {
		McpSkillRegistration originalSkill = registration("original");
		McpSkillRegistration first = registration("first");
		McpSkillRegistration second = registration("second");
		McpEndpoint original = builder("/selected").skillRegistrations(List.of(originalSkill)).build();
		McpEndpoint before = builder("/before").build();
		McpEndpoint appended = builder("/appended").build();
		Map<Class<?>, McpEndpoint> generated = new LinkedHashMap<>();
		generated.put(OtherEndpoint.class, before);
		generated.put(SelectedEndpoint.class, original);
		McpEndpointRegistry registry = new McpEndpointRegistry(generated).withEndpoint(appended);
		List<McpSkillRegistration> supplied = new ArrayList<>(List.of(second, first));

		assertFalse(ENDPOINT_INITIALIZED.get());
		McpEndpointRegistry replaced = registry.withSkillRegistrations(SelectedEndpoint.class, supplied);
		supplied.clear();
		assertFalse(ENDPOINT_INITIALIZED.get());
		assertEquals(List.of(before, original, appended), registry.getEndpoints());
		assertEquals(List.of(originalSkill), original.getSkillRegistrations());
		assertSame(before, replaced.getEndpoints().get(0));
		assertSame(appended, replaced.getEndpoints().get(2));
		McpEndpoint selected = replaced.getEndpoints().get(1);
		assertNotSame(original, selected);
		assertEquals(List.of(second, first), selected.getSkillRegistrations());
		assertEquals(List.of(second, first), selected.skillIndex().registrations());
		assertTrue(selected.skillIndex().findFile(originalSkill.getUri()).isEmpty());
		assertTrue(selected.skillIndex().findFile(first.getUri()).isPresent());
		assertThrows(UnsupportedOperationException.class, () -> selected.getSkillRegistrations().clear());
		assertThrows(UnsupportedOperationException.class, () -> replaced.getEndpoints().clear());

		McpEndpointRegistry cleared = replaced.withSkillRegistrations(SelectedEndpoint.class, List.of());
		assertTrue(cleared.getEndpoints().get(1).getSkillRegistrations().isEmpty());
		assertTrue(cleared.getEndpoints().get(1).skillIndex().registrations().isEmpty());
		McpEndpointRegistry chained = cleared.withSkillRegistrations(OtherEndpoint.class, List.of(first))
				.withEndpoint(builder("/last").build())
				.withSkillRegistrations(SelectedEndpoint.class, List.of(second));
		assertEquals(List.of("/before", "/selected", "/appended", "/last"),
				chained.getEndpoints().stream().map(McpEndpoint::getPath).toList());
		assertEquals(List.of(first), chained.getEndpoints().get(0).getSkillRegistrations());
		assertEquals(List.of(second), chained.getEndpoints().get(1).getSkillRegistrations());
		assertEquals(List.of(second, first), selected.getSkillRegistrations());
	}

	@Test
	void groupReplacementSnapshotsClearsAndPreservesStandaloneRegistrationsAndOrder() {
		McpSkillRegistration standalone = registration("standalone");
		McpSkillGroup originalGroup = group("original");
		McpSkillGroup first = group("first");
		McpSkillGroup second = group("second");
		McpEndpoint original = builder("/selected").skillRegistrations(List.of(standalone))
				.skillGroups(List.of(originalGroup)).build();
		McpEndpoint before = builder("/before").build();
		McpEndpoint appended = builder("/appended").build();
		Map<Class<?>, McpEndpoint> generated = new LinkedHashMap<>();
		generated.put(OtherEndpoint.class, before);
		generated.put(SelectedEndpoint.class, original);
		McpEndpointRegistry registry = new McpEndpointRegistry(generated).withEndpoint(appended);
		List<McpSkillGroup> supplied = new ArrayList<>(List.of(second, first));

		assertFalse(ENDPOINT_INITIALIZED.get());
		McpEndpointRegistry replaced = registry.withSkillGroups(SelectedEndpoint.class, supplied);
		supplied.clear();
		assertFalse(ENDPOINT_INITIALIZED.get());
		assertEquals(List.of(before, original, appended), registry.getEndpoints());
		assertEquals(List.of(originalGroup), original.getSkillGroups());
		assertSame(before, replaced.getEndpoints().get(0));
		assertSame(appended, replaced.getEndpoints().get(2));
		McpEndpoint selected = replaced.getEndpoints().get(1);
		assertNotSame(original, selected);
		assertSame(original.getSkillRegistrations(), selected.getSkillRegistrations());
		assertEquals(List.of(second, first), selected.getSkillGroups());
		assertEquals(List.of(standalone, second.getSkillRegistrations().get(0), first.getSkillRegistrations().get(0)),
				selected.skillIndex().registrations());
		assertTrue(selected.skillIndex().findFile(originalGroup.getSkillRegistrations().get(0).getUri()).isEmpty());
		assertTrue(selected.skillIndex().findFile(first.getSkillRegistrations().get(0).getUri()).isPresent());
		assertThrows(UnsupportedOperationException.class, () -> selected.getSkillGroups().clear());

		McpEndpointRegistry cleared = replaced.withSkillGroups(SelectedEndpoint.class, List.of());
		assertTrue(cleared.getEndpoints().get(1).getSkillGroups().isEmpty());
		assertEquals(List.of(standalone), cleared.getEndpoints().get(1).skillIndex().registrations());
		McpEndpointRegistry chained = cleared.withSkillGroups(OtherEndpoint.class, List.of(first))
				.withEndpoint(builder("/last").build())
				.withSkillGroups(SelectedEndpoint.class, List.of(second));
		assertEquals(List.of("/before", "/selected", "/appended", "/last"),
				chained.getEndpoints().stream().map(McpEndpoint::getPath).toList());
		assertEquals(List.of(first), chained.getEndpoints().get(0).getSkillGroups());
		assertEquals(List.of(second), chained.getEndpoints().get(1).getSkillGroups());
		assertEquals(List.of(second, first), selected.getSkillGroups());
	}

	@Test
	void groupReplacementIndexesEveryLocaleWithoutSelectingOrTranslatingIt() {
		McpSkillBundle bundle = registration("guide").getSkillBundle();
		McpSkillRegistration english = McpSkillRegistration.withUriAndSkillBundle(
				URI.create("skill://host.invalid/en/guide/SKILL.md"), bundle).locale(Locale.ENGLISH).build();
		McpSkillRegistration german = McpSkillRegistration.withUriAndSkillBundle(
				URI.create("skill://host.invalid/de/guide/SKILL.md"), bundle).locale(Locale.GERMAN).build();
		McpSkillGroup group = McpSkillGroup.fromKeyAndSkillRegistrations("guide", List.of(german, english));
		McpEndpoint original = builder("/selected").build();
		McpEndpoint endpoint = new McpEndpointRegistry(Map.of(SelectedEndpoint.class, original))
				.withSkillGroups(SelectedEndpoint.class, List.of(group)).getEndpoints().get(0);
		assertSame(group, endpoint.getSkillGroups().get(0));
		assertEquals(List.of(german, english), endpoint.skillIndex().registrations());
		for (McpSkillRegistration registration : group.getSkillRegistrations()) {
			assertSame(registration, endpoint.skillIndex().findRegistration(registration.getUri()).orElseThrow());
			assertEquals(List.of(registration), endpoint.skillIndex().findFile(registration.getUri()).orElseThrow().owners());
		}
		assertTrue(original.skillIndex().registrations().isEmpty());
	}

	@Test
	void skillsAndSubscriptionOverlaysPreserveEveryOtherEndpointValueInEveryOrder() {
		McpToolRegistration<McpJsonObject> tool = McpToolRegistration.withName("tool").jsonObjectArguments()
				.handler((request, arguments, features) -> { throw new AssertionError("Must not invoke"); }).build();
		McpPromptRegistration prompt = McpPromptRegistration.withName("prompt")
				.handler((request, arguments, features) -> { throw new AssertionError("Must not invoke"); }).build();
		McpResourceRegistration resource = resource("test://ordinary");
		McpSkillGroup group = McpSkillGroup.fromKeyAndSkillRegistrations("group", List.of(registration("grouped")));
		McpSkillListHandler skillList = (request, context, features) -> { throw new AssertionError("Must not invoke"); };
		McpResourceListHandler resourceList = (request, context, features) -> { throw new AssertionError("Must not invoke"); };
		McpRateLimiter limiter = context -> { throw new AssertionError("Must not invoke"); };
		McpSubscriptionConfig subscriptions = McpSubscriptionConfig.withEventPublisherAndNotificationTypes(
				McpSubscriptionEventPublisher.fromInMemoryDefaults(), Set.of(McpSubscriptionNotificationType.RESOURCES_LIST_CHANGED)).build();
		McpSkillRegistration skill = registration("single");
		McpEndpoint original = builder("/selected").serverInfoIncluded(false).instructions("Instructions")
				.toolRegistrations(List.of(tool)).promptRegistrations(List.of(prompt)).resourceRegistrations(List.of(resource))
				.skillGroups(List.of(group("previous-group"))).skillListHandler(skillList)
				.skillListCachePolicy(McpCachePolicy.fromPublicTimeToLive(Duration.ofSeconds(3)))
				.resourceListHandler(resourceList)
				.resourceListCachePolicy(McpCachePolicy.fromPrivateTimeToLive(Duration.ofSeconds(5)))
				.resourceTemplateListCachePolicy(McpCachePolicy.fromPublicTimeToLive(Duration.ofSeconds(7)))
				.toolRateLimiter(limiter).build();
		McpEndpointRegistry registry = new McpEndpointRegistry(Map.of(SelectedEndpoint.class, original));
		for (McpEndpointRegistry overlaid : List.of(
				registry.withSkillRegistrations(SelectedEndpoint.class, List.of(skill))
						.withSubscriptionConfig(SelectedEndpoint.class, subscriptions)
						.withSkillGroups(SelectedEndpoint.class, List.of(group)),
				registry.withSkillRegistrations(SelectedEndpoint.class, List.of(skill))
						.withSkillGroups(SelectedEndpoint.class, List.of(group))
						.withSubscriptionConfig(SelectedEndpoint.class, subscriptions),
				registry.withSubscriptionConfig(SelectedEndpoint.class, subscriptions)
						.withSkillRegistrations(SelectedEndpoint.class, List.of(skill))
						.withSkillGroups(SelectedEndpoint.class, List.of(group)),
				registry.withSubscriptionConfig(SelectedEndpoint.class, subscriptions)
						.withSkillGroups(SelectedEndpoint.class, List.of(group))
						.withSkillRegistrations(SelectedEndpoint.class, List.of(skill)),
				registry.withSkillGroups(SelectedEndpoint.class, List.of(group))
						.withSkillRegistrations(SelectedEndpoint.class, List.of(skill))
						.withSubscriptionConfig(SelectedEndpoint.class, subscriptions),
				registry.withSkillGroups(SelectedEndpoint.class, List.of(group))
						.withSubscriptionConfig(SelectedEndpoint.class, subscriptions)
						.withSkillRegistrations(SelectedEndpoint.class, List.of(skill)))) {
			McpEndpoint selected = overlaid.getEndpoints().get(0);
			assertEquals(original.getPath(), selected.getPath());
			assertSame(original.getServerInfo(), selected.getServerInfo());
			assertEquals(original.isServerInfoIncluded(), selected.isServerInfoIncluded());
			assertEquals(original.getInstructions(), selected.getInstructions());
			assertSame(original.getToolRegistrations(), selected.getToolRegistrations());
			assertSame(original.getPromptRegistrations(), selected.getPromptRegistrations());
			assertSame(original.getResourceRegistrations(), selected.getResourceRegistrations());
			assertEquals(List.of(group), selected.getSkillGroups());
			assertSame(skillList, selected.getSkillListHandler().orElseThrow());
			assertSame(original.getSkillListCachePolicy(), selected.getSkillListCachePolicy());
			assertSame(resourceList, selected.getResourceListHandler().orElseThrow());
			assertSame(original.getResourceListCachePolicy(), selected.getResourceListCachePolicy());
			assertSame(original.getResourceTemplateListCachePolicy(), selected.getResourceTemplateListCachePolicy());
			assertSame(limiter, selected.getToolRateLimiter().orElseThrow());
			assertTrue(selected.getToolRateLimiterName().isEmpty());
			assertSame(subscriptions, selected.getSubscriptionConfig().orElseThrow());
			assertEquals(List.of(skill, group.getSkillRegistrations().get(0)), selected.skillIndex().registrations());
			McpEndpoint cleared = overlaid.withSkillRegistrations(SelectedEndpoint.class, List.of()).getEndpoints().get(0);
			assertSame(selected.getSkillGroups(), cleared.getSkillGroups());
			assertEquals(group.getSkillRegistrations(), cleared.skillIndex().registrations());
			assertSame(subscriptions, cleared.getSubscriptionConfig().orElseThrow());
			McpEndpoint groupsCleared = overlaid.withSkillGroups(SelectedEndpoint.class, List.of()).getEndpoints().get(0);
			assertSame(selected.getSkillRegistrations(), groupsCleared.getSkillRegistrations());
			assertEquals(List.of(skill), groupsCleared.skillIndex().registrations());
			assertSame(subscriptions, groupsCleared.getSubscriptionConfig().orElseThrow());
		}
		McpEndpoint named = builder("/named").toolRateLimiterName("named-limiter").build();
		McpEndpoint namedCopy = new McpEndpointRegistry(Map.of(SelectedEndpoint.class, named))
				.withSkillGroups(SelectedEndpoint.class, List.of(group))
				.withSkillRegistrations(SelectedEndpoint.class, List.of(skill)).getEndpoints().get(0);
		assertEquals(named.getToolRateLimiterName(), namedCopy.getToolRateLimiterName());
		assertTrue(namedCopy.getToolRateLimiter().isEmpty());
		assertTrue(original.getSkillRegistrations().isEmpty());
		assertTrue(original.getSubscriptionConfig().isEmpty());
	}

	@Test
	void invalidAndNullReplacementsLeaveTheOriginalRegistryUnchanged() {
		McpSkillRegistration initial = registration("initial");
		McpSkillRegistration replacement = registration("replacement");
		McpEndpoint endpoint = builder("/selected").skillRegistrations(List.of(initial)).build();
		McpEndpointRegistry registry = new McpEndpointRegistry(Map.of(SelectedEndpoint.class, endpoint));
		assertThrows(NullPointerException.class, () -> registry.withSkillRegistrations(null, List.of()));
		assertThrows(NullPointerException.class, () -> registry.withSkillRegistrations(SelectedEndpoint.class, null));
		assertThrows(NullPointerException.class, () -> registry.withSkillRegistrations(SelectedEndpoint.class,
				Arrays.asList(replacement, null)));
		assertThrows(IllegalStateException.class, () -> registry.withSkillRegistrations(SelectedEndpoint.class,
				List.of(replacement, replacement)));
		assertSame(endpoint, registry.getEndpoints().get(0));
		assertEquals(List.of(initial), endpoint.getSkillRegistrations());
		assertEquals(List.of(initial), endpoint.skillIndex().registrations());
		assertEquals(List.of(replacement), registry.withSkillRegistrations(SelectedEndpoint.class,
				List.of(replacement)).getEndpoints().get(0).getSkillRegistrations());
	}

	@Test
	void invalidAndNullGroupReplacementsLeaveTheOriginalRegistryUnchanged() {
		McpSkillGroup initial = group("initial");
		McpSkillGroup replacement = group("replacement");
		McpEndpoint endpoint = builder("/selected").skillGroups(List.of(initial)).build();
		McpEndpointRegistry registry = new McpEndpointRegistry(Map.of(SelectedEndpoint.class, endpoint));
		assertThrows(NullPointerException.class, () -> registry.withSkillGroups(null, List.of()));
		assertThrows(NullPointerException.class, () -> registry.withSkillGroups(SelectedEndpoint.class, null));
		assertThrows(NullPointerException.class, () -> registry.withSkillGroups(SelectedEndpoint.class,
				Arrays.asList(replacement, null)));
		McpSkillGroup duplicateKey = McpSkillGroup.fromKeyAndSkillRegistrations(replacement.getKey(), List.of());
		assertThrows(IllegalStateException.class, () -> registry.withSkillGroups(SelectedEndpoint.class,
				List.of(replacement, duplicateKey)));
		McpSkillGroup duplicateMember = McpSkillGroup.fromKeyAndSkillRegistrations("another-key",
				replacement.getSkillRegistrations());
		assertThrows(IllegalStateException.class, () -> registry.withSkillGroups(SelectedEndpoint.class,
				List.of(replacement, duplicateMember)));
		assertSame(endpoint, registry.getEndpoints().get(0));
		assertEquals(List.of(initial), endpoint.getSkillGroups());
		assertEquals(initial.getSkillRegistrations(), endpoint.skillIndex().registrations());
		assertEquals(List.of(replacement), registry.withSkillGroups(SelectedEndpoint.class,
				List.of(replacement)).getEndpoints().get(0).getSkillGroups());
	}

	@Test
	void overlaysRequireOwnedGeneratedProvenanceNotJustAMatchingPath() {
		McpEndpoint endpoint = builder("/selected").build();
		McpEndpointRegistry generated = new McpEndpointRegistry(Map.of(SelectedEndpoint.class, endpoint));
		assertThrows(IllegalArgumentException.class, () -> generated.withSkillRegistrations(OtherEndpoint.class, List.of()));
		assertThrows(IllegalArgumentException.class, () -> generated.withSkillRegistrations(String.class, List.of()));
		assertThrows(IllegalArgumentException.class, () -> McpEndpointRegistry.fromEndpoints(List.of(endpoint))
				.withSkillRegistrations(SelectedEndpoint.class, List.of()));
		assertThrows(IllegalArgumentException.class, () -> generated.withEndpoint(builder("/other").build())
				.withSkillRegistrations(OtherEndpoint.class, List.of()));
		assertThrows(IllegalArgumentException.class, () -> generated.withSkillGroups(OtherEndpoint.class, List.of()));
		assertThrows(IllegalArgumentException.class, () -> generated.withSkillGroups(String.class, List.of()));
		assertThrows(IllegalArgumentException.class, () -> McpEndpointRegistry.fromEndpoints(List.of(endpoint))
				.withSkillGroups(SelectedEndpoint.class, List.of()));
		assertThrows(IllegalArgumentException.class, () -> generated.withEndpoint(builder("/other").build())
				.withSkillGroups(OtherEndpoint.class, List.of()));
	}

	@Test
	void replacementRevalidatesExactAndTemplateResourceCollisions() {
		McpSkillRegistration skill = registration("collision");
		McpResourceRegistration template = McpResourceRegistration.withUriTemplateAndName(
				"skill://host.invalid/collision/{file}", "template")
				.handler((request, read, features) -> { throw new AssertionError("Must not invoke"); }).build();
		for (McpResourceRegistration resource : List.of(resource(skill.getUri().toString()),
				resource("skill://host.invalid/collision/support.txt"), template)) {
			McpEndpoint endpoint = builder("/selected").resourceRegistrations(List.of(resource)).build();
			McpEndpointRegistry registry = new McpEndpointRegistry(Map.of(SelectedEndpoint.class, endpoint));
			assertThrows(IllegalStateException.class, () -> registry.withSkillRegistrations(SelectedEndpoint.class, List.of(skill)));
			assertThrows(IllegalStateException.class, () -> registry.withSkillGroups(SelectedEndpoint.class,
					List.of(McpSkillGroup.fromKeyAndSkillRegistrations("group", List.of(skill)))));
			assertSame(endpoint, registry.getEndpoints().get(0));
			assertTrue(endpoint.getSkillRegistrations().isEmpty());
			assertTrue(endpoint.getSkillGroups().isEmpty());
			assertTrue(endpoint.skillIndex().registrations().isEmpty());
		}
	}

	@Test
	void replacementRevalidatesCollisionsWithPreservedGroups() {
		McpSkillRegistration member = registration("grouped");
		McpSkillGroup group = McpSkillGroup.fromKeyAndSkillRegistrations("group", List.of(member));
		McpEndpoint endpoint = builder("/selected").skillGroups(List.of(group)).build();
		McpEndpointRegistry registry = new McpEndpointRegistry(Map.of(SelectedEndpoint.class, endpoint));
		assertThrows(IllegalStateException.class, () -> registry.withSkillRegistrations(SelectedEndpoint.class, List.of(member)));
		McpSkillRegistration sameName = McpSkillRegistration.withUriAndSkillBundle(
				URI.create("skill://host.invalid/alternate/grouped/SKILL.md"), member.getSkillBundle()).build();
		assertThrows(IllegalStateException.class, () -> registry.withSkillRegistrations(SelectedEndpoint.class, List.of(sameName)));
		assertSame(endpoint, registry.getEndpoints().get(0));
		assertEquals(List.of(member), endpoint.skillIndex().registrations());
	}

	@Test
	void groupReplacementRevalidatesCollisionsWithPreservedStandaloneRegistrations() {
		McpSkillRegistration standalone = registration("standalone");
		McpEndpoint endpoint = builder("/selected").skillRegistrations(List.of(standalone)).build();
		McpEndpointRegistry registry = new McpEndpointRegistry(Map.of(SelectedEndpoint.class, endpoint));
		McpSkillRegistration sameName = McpSkillRegistration.withUriAndSkillBundle(
				URI.create("skill://host.invalid/alternate/standalone/SKILL.md"), standalone.getSkillBundle()).build();
		for (McpSkillRegistration conflicting : List.of(standalone, sameName))
			assertThrows(IllegalStateException.class, () -> registry.withSkillGroups(SelectedEndpoint.class,
					List.of(McpSkillGroup.fromKeyAndSkillRegistrations("group", List.of(conflicting)))));
		assertSame(endpoint, registry.getEndpoints().get(0));
		assertTrue(endpoint.getSkillGroups().isEmpty());
		assertEquals(List.of(standalone), endpoint.skillIndex().registrations());
	}

	@Test
	void replacementRerunsAutomaticPagePreflightAndHonorsAnExistingPageHandler() {
		List<McpSkillRegistration> skills = new ArrayList<>();
		for (int index = 0; index < 33; ++index) skills.add(registration("skill-" + index));
		McpEndpoint endpoint = builder("/selected").build();
		McpEndpointRegistry registry = new McpEndpointRegistry(Map.of(SelectedEndpoint.class, endpoint));
		assertThrows(IllegalArgumentException.class, () -> registry.withSkillRegistrations(SelectedEndpoint.class, skills));
		assertSame(endpoint, registry.getEndpoints().get(0));
		assertEquals(32, registry.withSkillRegistrations(SelectedEndpoint.class, skills.subList(0, 32))
				.getEndpoints().get(0).getSkillRegistrations().size());
		McpSkillListHandler handler = (request, context, features) -> { throw new AssertionError("Must not invoke"); };
		McpEndpoint paged = builder("/selected").skillListHandler(handler).build();
		McpEndpoint copied = new McpEndpointRegistry(Map.of(SelectedEndpoint.class, paged))
				.withSkillRegistrations(SelectedEndpoint.class, skills).getEndpoints().get(0);
		assertEquals(skills, copied.getSkillRegistrations());
		assertSame(handler, copied.getSkillListHandler().orElseThrow());
	}

	@Test
	void groupReplacementRerunsAutomaticPagePreflightAndHonorsAnExistingPageHandler() {
		List<McpSkillGroup> groups = new ArrayList<>();
		for (int index = 0; index < 33; ++index) groups.add(group("skill-" + index));
		McpEndpoint endpoint = builder("/selected").build();
		McpEndpointRegistry registry = new McpEndpointRegistry(Map.of(SelectedEndpoint.class, endpoint));
		assertThrows(IllegalArgumentException.class, () -> registry.withSkillGroups(SelectedEndpoint.class, groups));
		assertSame(endpoint, registry.getEndpoints().get(0));
		assertTrue(endpoint.getSkillGroups().isEmpty());
		assertEquals(32, registry.withSkillGroups(SelectedEndpoint.class, groups.subList(0, 32))
				.getEndpoints().get(0).getSkillGroups().size());
		McpSkillListHandler handler = (request, context, features) -> { throw new AssertionError("Must not invoke"); };
		McpEndpoint paged = builder("/selected").skillListHandler(handler).build();
		McpEndpoint copied = new McpEndpointRegistry(Map.of(SelectedEndpoint.class, paged))
				.withSkillGroups(SelectedEndpoint.class, groups).getEndpoints().get(0);
		assertEquals(groups, copied.getSkillGroups());
		assertSame(handler, copied.getSkillListHandler().orElseThrow());
	}

	private static McpEndpoint.Builder builder(String path) {
		return McpEndpoint.withPath(path, McpImplementation.withNameAndVersion("test", "1").build());
	}

	private static McpSkillGroup group(String name) {
		return McpSkillGroup.fromKeyAndSkillRegistrations(name, List.of(registration(name)));
	}

	private static McpSkillRegistration registration(String name) {
		byte[] root = ("---\nname: " + name + "\ndescription: Synthetic description\n---\nOpaque body.\n")
				.getBytes(StandardCharsets.UTF_8);
		return McpSkillRegistration.withUriAndSkillBundle(URI.create("skill://host.invalid/" + name + "/SKILL.md"),
				McpSkillBundle.fromFiles(Map.of("SKILL.md", root, "support.txt", new byte[]{65}))).build();
	}

	private static McpResourceRegistration resource(String uri) {
		return McpResourceRegistration.withUriAndName(URI.create(uri), "ordinary")
				.handler((request, read, features) -> { throw new AssertionError("Must not invoke"); }).build();
	}

	private static final class SelectedEndpoint {
		static { ENDPOINT_INITIALIZED.set(true); }
	}

	private static final class OtherEndpoint { }
}
