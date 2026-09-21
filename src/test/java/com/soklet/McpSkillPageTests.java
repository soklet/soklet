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

import java.lang.reflect.Modifier;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class McpSkillPageTests {
	@Test
	void emptyPageHasEmptyCollectionsAndAbsentOptionalValues() {
		McpSkillPage page = McpSkillPage.builder().build();
		assertTrue(page.getSkillRegistrations().isEmpty());
		assertSame(McpJsonObject.emptyInstance(), page.getMetadata());
		assertTrue(page.getNextCursor().isEmpty());
		assertTrue(page.getCacheTimeToLiveOverride().isEmpty());
		assertInstanceOf(McpOperationResult.class, page);
	}

	@Test
	void registrationSetterSnapshotsReplacesClearsAndRejectsNullsAtomically() {
		McpSkillRegistration first = registration("one"), second = registration("two");
		List<McpSkillRegistration> supplied = new ArrayList<>(List.of(first));
		McpSkillPage.Builder builder = McpSkillPage.builder().skillRegistrations(supplied);
		supplied.clear();
		McpSkillPage page = builder.build();
		assertSame(first, page.getSkillRegistrations().get(0));
		assertThrows(UnsupportedOperationException.class, () -> page.getSkillRegistrations().clear());
		assertThrows(NullPointerException.class, () -> builder.skillRegistrations(Arrays.asList(second, null)));
		assertEquals(List.of(first), builder.build().getSkillRegistrations());
		assertEquals(List.of(second), builder.skillRegistrations(List.of(second)).build().getSkillRegistrations());
		assertTrue(builder.skillRegistrations(null).build().getSkillRegistrations().isEmpty());
		assertTrue(builder.skillRegistrations(List.of()).build().getSkillRegistrations().isEmpty());
		assertEquals(List.of(first), page.getSkillRegistrations());
	}

	@Test
	void thirtyTwoEntriesAreInclusiveAndOversizedBuilderCanBeResetBeforeBuild() {
		List<McpSkillRegistration> registrations = new ArrayList<>();
		for (int index = 0; index < 33; ++index) registrations.add(registration("skill-" + index));
		assertEquals(32, McpSkillPage.builder().skillRegistrations(registrations.subList(0, 32)).build()
				.getSkillRegistrations().size());
		McpSkillPage.Builder builder = McpSkillPage.builder().skillRegistrations(registrations);
		IllegalStateException failure = assertThrows(IllegalStateException.class, builder::build);
		assertEquals("An MCP Skills page may contain at most 32 registrations.", failure.getMessage());
		assertNull(failure.getCause());
		assertTrue(builder.skillRegistrations(null).build().getSkillRegistrations().isEmpty());
	}

	@Test
	void cursorTextIsOpaqueAndEmptyRemainsPresent() {
		for (String cursor : List.of("", "  private-cursor-canary /% café  ")) {
			McpSkillPage page = McpSkillPage.builder().nextCursor(cursor).build();
			assertEquals(Optional.of(cursor), page.getNextCursor());
		}
		assertThrows(NullPointerException.class, () -> McpSkillPage.builder().nextCursor(null));
		assertThrows(NullPointerException.class, () -> McpSkillPage.builder().metadata(null));
	}

	@Test
	void ttlUsesNonnegativeWholeMillisecondsWithoutAlteringPriorValidValue() {
		McpSkillPage.Builder builder = McpSkillPage.builder().cacheTimeToLiveOverride(Duration.ofMillis(1));
		for (Duration invalid : List.of(Duration.ofMillis(-1), Duration.ofNanos(1), Duration.ofSeconds(Long.MAX_VALUE)))
			assertThrows(IllegalArgumentException.class, () -> builder.cacheTimeToLiveOverride(invalid));
		assertThrows(NullPointerException.class, () -> builder.cacheTimeToLiveOverride(null));
		assertEquals(Optional.of(Duration.ofMillis(1)), builder.build().getCacheTimeToLiveOverride());
		assertEquals(Optional.of(Duration.ZERO), builder.cacheTimeToLiveOverride(Duration.ZERO).build().getCacheTimeToLiveOverride());
		assertEquals(Optional.of(Duration.ofMillis(Long.MAX_VALUE)),
				builder.cacheTimeToLiveOverride(Duration.ofMillis(Long.MAX_VALUE)).build().getCacheTimeToLiveOverride());
	}

	@Test
	void structuralEqualityIncludesOrderMetadataCursorAndTtlWithoutExposingData() {
		McpSkillRegistration first = registration("one"), second = registration("two");
		McpJsonObject metadata = McpJsonObject.builder().put("private-metadata-canary", "secret").build();
		McpSkillPage page = McpSkillPage.builder().skillRegistrations(List.of(first, second)).metadata(metadata)
				.nextCursor("private-cursor-canary").cacheTimeToLiveOverride(Duration.ZERO).build();
		McpSkillPage equal = McpSkillPage.builder().skillRegistrations(List.of(registration("one"), registration("two")))
				.metadata(metadata).nextCursor("private-cursor-canary").cacheTimeToLiveOverride(Duration.ZERO).build();
		assertEquals(page, equal);
		assertEquals(page.hashCode(), equal.hashCode());
		assertSame(metadata, page.getMetadata());
		assertNotEquals(page, McpSkillPage.builder().skillRegistrations(List.of(second, first)).metadata(metadata)
				.nextCursor("private-cursor-canary").cacheTimeToLiveOverride(Duration.ZERO).build());
		assertNotEquals(page, McpSkillPage.builder().skillRegistrations(List.of(first, second))
				.nextCursor("private-cursor-canary").cacheTimeToLiveOverride(Duration.ZERO).build());
		assertNotEquals(page, McpSkillPage.builder().skillRegistrations(List.of(first, second)).metadata(metadata)
				.cacheTimeToLiveOverride(Duration.ZERO).build());
		assertNotEquals(page, McpSkillPage.builder().skillRegistrations(List.of(first, second)).metadata(metadata)
				.nextCursor("private-cursor-canary").build());
		assertNotEquals(page, null);
		assertNotEquals(page, "page");
		assertEquals("McpSkillPage[redacted]", page.toString());
	}

	@Test
	void contextDistinguishesPresentEmptyFirstPageFromPresentEmptyContinuationCursor() {
		McpSkillRegistration registration = registration("one");
		List<McpSkillRegistration> supplied = new ArrayList<>(List.of(registration));
		McpSkillListContext first = McpSkillListContext.from(Optional.empty(), Optional.of(supplied));
		supplied.clear();
		assertTrue(first.getCursor().isEmpty());
		assertSame(registration, first.getInitialSkillRegistrations().orElseThrow().get(0));
		assertThrows(UnsupportedOperationException.class, () -> first.getInitialSkillRegistrations().orElseThrow().clear());
		assertEquals(Optional.of(List.of()), McpSkillListContext.from(Optional.empty(), Optional.of(List.of()))
				.getInitialSkillRegistrations());
		for (String cursor : List.of("", " private-cursor-canary ")) {
			McpSkillListContext continuation = McpSkillListContext.from(Optional.of(cursor), Optional.empty());
			assertEquals(Optional.of(cursor), continuation.getCursor());
			assertTrue(continuation.getInitialSkillRegistrations().isEmpty());
			assertEquals("McpSkillListContext[redacted]", continuation.toString());
		}
	}

	@Test
	void contextRejectsInconsistentPhasesAndNullValues() {
		assertThrows(NullPointerException.class, () -> McpSkillListContext.from(null, Optional.of(List.of())));
		assertThrows(NullPointerException.class, () -> McpSkillListContext.from(Optional.empty(), null));
		assertThrows(NullPointerException.class, () -> McpSkillListContext.from(Optional.empty(),
				Optional.of(Arrays.asList((McpSkillRegistration) null))));
		for (Optional<String> cursor : List.of(Optional.<String>empty(), Optional.of("private-cursor-canary"))) {
			Optional<List<McpSkillRegistration>> initial = cursor.isPresent() ? Optional.of(List.of()) : Optional.empty();
			IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
					() -> McpSkillListContext.from(cursor, initial));
			assertEquals("Invalid MCP Skills list context phase.", failure.getMessage());
			assertNull(failure.getCause());
		}
	}

	@Test
	void endpointDefaultsResetsAndSubscriptionOverlayPreservePaginationConfiguration() {
		McpSkillListHandler handler = (request, context, features) -> { throw new AssertionError("Must not invoke during construction"); };
		McpSkillListHandler replacement = (request, context, features) -> McpSkillPage.builder().build();
		McpCachePolicy policy = McpCachePolicy.fromPublicTimeToLive(Duration.ofSeconds(5));
		McpEndpoint defaults = endpointBuilder().build();
		assertTrue(defaults.getSkillListHandler().isEmpty());
		assertSame(McpCachePolicy.privateNoCacheInstance(), defaults.getSkillListCachePolicy());
		McpEndpoint.Builder builder = endpointBuilder().skillListHandler(handler).skillListCachePolicy(policy);
		McpEndpoint configured = builder.build();
		assertSame(handler, configured.getSkillListHandler().orElseThrow());
		assertSame(policy, configured.getSkillListCachePolicy());
		assertSame(replacement, builder.skillListHandler(replacement).build().getSkillListHandler().orElseThrow());
		McpEndpoint reset = builder.skillListHandler(null).skillListCachePolicy(null).build();
		assertTrue(reset.getSkillListHandler().isEmpty());
		assertSame(McpCachePolicy.privateNoCacheInstance(), reset.getSkillListCachePolicy());
		McpSubscriptionConfig subscription = McpSubscriptionConfig.withEventPublisherAndNotificationTypes(
				McpSubscriptionEventPublisher.fromInMemoryDefaults(), Set.of(McpSubscriptionNotificationType.RESOURCES_LIST_CHANGED)).build();
		McpEndpoint overlay = configured.withSubscriptionConfig(subscription);
		assertSame(handler, overlay.getSkillListHandler().orElseThrow());
		assertSame(policy, overlay.getSkillListCachePolicy());
		assertSame(handler, configured.getSkillListHandler().orElseThrow());
	}

	@Test
	void resultFamilyIsSealedAndConstructionSurfacesArePrivate() {
		assertTrue(McpOperationResult.class.isSealed());
		assertEquals(Set.of(McpCompleteResult.class, McpInputRequiredResult.class, McpTaskCreatedResult.class,
				McpResourcePage.class, McpArgumentCompletionResult.class, McpSkillPage.class),
				Set.of(McpOperationResult.class.getPermittedSubclasses()));
		for (Class<?> type : List.of(McpSkillPage.class, McpSkillPage.Builder.class, McpSkillListContext.class)) {
			assertTrue(Modifier.isFinal(type.getModifiers()));
			assertTrue(Arrays.stream(type.getDeclaredConstructors()).allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers())));
		}
	}

	private static McpEndpoint.Builder endpointBuilder() {
		return McpEndpoint.withPath("/skills", McpImplementation.withNameAndVersion("test", "1").build());
	}

	private static McpSkillRegistration registration(String name) {
		byte[] root = ("---\nname: " + name + "\ndescription: Synthetic description\n---\nOpaque body.\n").getBytes(StandardCharsets.UTF_8);
		return McpSkillRegistration.withUriAndSkillBundle(URI.create("skill://host.invalid/" + name + "/SKILL.md"),
				McpSkillBundle.fromFiles(Map.of("SKILL.md", root))).build();
	}
}
