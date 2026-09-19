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
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import static com.soklet.McpAppResourceMetadata.Permission.CAMERA;
import static com.soklet.McpAppResourceMetadata.Permission.CLIPBOARD_WRITE;
import static com.soklet.McpAppResourceMetadata.Permission.GEOLOCATION;
import static com.soklet.McpAppResourceMetadata.Permission.MICROPHONE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for the immutable, not-yet-attached Apps resource metadata values. */
@ThreadSafe
class McpAppResourceMetadataTests {
	@Test
	void defaultsPreserveOmissionAndImmutableEmptyCollections() {
		McpAppResourceMetadata metadata = McpAppResourceMetadata.builder().build();
		assertTrue(metadata.getContentSecurityPolicy().isEmpty());
		assertTrue(metadata.getPermissions().isEmpty());
		assertTrue(metadata.getDomain().isEmpty());
		assertTrue(metadata.getPrefersBorder().isEmpty());
		assertThrows(UnsupportedOperationException.class,
				() -> metadata.getPermissions().add(CAMERA));

		McpAppResourceMetadata.ContentSecurityPolicy policy =
				McpAppResourceMetadata.ContentSecurityPolicy.builder().build();
		for (Set<String> origins : List.of(policy.getConnectDomains(),
				policy.getResourceDomains(), policy.getFrameDomains(),
				policy.getBaseUriDomains())) {
			assertTrue(origins.isEmpty());
			assertThrows(UnsupportedOperationException.class,
					() -> origins.add("https://example.com"));
		}
		assertNotEquals(metadata, McpAppResourceMetadata.builder()
				.contentSecurityPolicy(policy).build());
		assertNotEquals(metadata, McpAppResourceMetadata.builder()
				.prefersBorder(false).build());
	}

	@Test
	void permissionsAreIndependentSnapshotsInEnumDeclarationOrder() {
		Set<McpAppResourceMetadata.Permission> source = new LinkedHashSet<>(
				List.of(CLIPBOARD_WRITE, GEOLOCATION, MICROPHONE, CAMERA));
		McpAppResourceMetadata.Builder builder = McpAppResourceMetadata.builder();
		assertSame(builder, builder.permissions(source));
		McpAppResourceMetadata first = builder.build();
		source.clear();
		builder.permissions(Set.of(CLIPBOARD_WRITE));

		assertEquals(List.of(CAMERA, MICROPHONE, GEOLOCATION, CLIPBOARD_WRITE),
				List.copyOf(first.getPermissions()));
		assertEquals(Set.of(CLIPBOARD_WRITE), builder.build().getPermissions());
		assertThrows(UnsupportedOperationException.class,
				() -> first.getPermissions().remove(CAMERA));
		assertTrue(builder.permissions(Set.of()).build().getPermissions().isEmpty());
		assertTrue(first.getContentSecurityPolicy().isEmpty());
		assertTrue(first.getDomain().isEmpty());
		assertTrue(first.getPrefersBorder().isEmpty());
	}

	@Test
	void optionalHintsAreIndependentAndBuiltValuesDoNotFollowBuilderChanges() {
		McpAppResourceMetadata.ContentSecurityPolicy policy =
				McpAppResourceMetadata.ContentSecurityPolicy.builder()
						.resourceDomains(Set.of("https://assets.example.com")).build();
		McpAppResourceMetadata.Builder builder = McpAppResourceMetadata.builder();
		assertSame(builder, builder.contentSecurityPolicy(policy));
		assertSame(builder, builder.domain("first.example.com"));
		assertSame(builder, builder.prefersBorder(false));
		McpAppResourceMetadata first = builder.build();
		builder.contentSecurityPolicy(
				McpAppResourceMetadata.ContentSecurityPolicy.builder().build())
				.domain("second.example.com").prefersBorder(true);

		assertSame(policy, first.getContentSecurityPolicy().orElseThrow());
		assertEquals("first.example.com", first.getDomain().orElseThrow());
		assertEquals(Boolean.FALSE, first.getPrefersBorder().orElseThrow());
		assertTrue(first.getPermissions().isEmpty());
		assertEquals("second.example.com", builder.build().getDomain().orElseThrow());
		assertEquals(Boolean.TRUE, builder.build().getPrefersBorder().orElseThrow());
	}

	@Test
	void metadataSettersRejectNullsAndInvalidValuesWithoutChangingPreviousState() {
		McpAppResourceMetadata.ContentSecurityPolicy policy =
				McpAppResourceMetadata.ContentSecurityPolicy.builder().build();
		McpAppResourceMetadata.Builder builder = McpAppResourceMetadata.builder()
				.contentSecurityPolicy(policy).permissions(Set.of(CAMERA))
				.domain("safe.example.com").prefersBorder(false);
		McpAppResourceMetadata before = builder.build();

		assertThrows(NullPointerException.class, () -> builder.contentSecurityPolicy(null));
		assertThrows(NullPointerException.class, () -> builder.permissions(null));
		assertThrows(NullPointerException.class, () -> builder.permissions(
				new LinkedHashSet<>(Arrays.asList(MICROPHONE, null))));
		assertThrows(NullPointerException.class, () -> builder.domain(null));
		assertThrows(NullPointerException.class, () -> builder.prefersBorder(null));
		for (String invalid : List.of("", "UPPER.example", "example.com.",
				"*.example.com", "https://example.com", "example.com:443",
				"user@example.com", "first..example", "-first.example",
				"first-.example", "first_example.com", "éxample.com",
				"example.com/path", "example.com other", "example.com,other",
				"example.com;other", "example.com\n", "x".repeat(64) + ".com"))
			assertThrows(IllegalArgumentException.class, () -> builder.domain(invalid));

		assertEquals(before, builder.build());
	}

	@Test
	void allPolicyAllowlistsAreDefensiveSortedSnapshotsAndIndependent() {
		Set<String> source = new LinkedHashSet<>(List.of("https://z.example.com",
				"https://a.example.com:443", "https://*.example.com"));
		List<String> expected = List.of("https://*.example.com",
				"https://a.example.com:443", "https://z.example.com");
		McpAppResourceMetadata.ContentSecurityPolicy.Builder builder =
				McpAppResourceMetadata.ContentSecurityPolicy.builder();
		assertSame(builder, builder.connectDomains(source));
		assertSame(builder, builder.resourceDomains(source));
		assertSame(builder, builder.frameDomains(source));
		assertSame(builder, builder.baseUriDomains(source));
		McpAppResourceMetadata.ContentSecurityPolicy first = builder.build();
		source.clear();
		builder.connectDomains(Set.of("wss://connect.example.com"))
				.resourceDomains(Set.of("https://resource.example.com"))
				.frameDomains(Set.of("https://frame.example.com"))
				.baseUriDomains(Set.of("https://base.example.com"));

		for (Set<String> origins : List.of(first.getConnectDomains(),
				first.getResourceDomains(), first.getFrameDomains(),
				first.getBaseUriDomains())) {
			assertEquals(expected, List.copyOf(origins));
			assertThrows(UnsupportedOperationException.class,
					() -> origins.remove("https://z.example.com"));
		}
		McpAppResourceMetadata.ContentSecurityPolicy changed = builder.build();
		assertEquals(Set.of("wss://connect.example.com"), changed.getConnectDomains());
		assertEquals(Set.of("https://resource.example.com"), changed.getResourceDomains());
		assertEquals(Set.of("https://frame.example.com"), changed.getFrameDomains());
		assertEquals(Set.of("https://base.example.com"), changed.getBaseUriDomains());
		McpAppResourceMetadata.ContentSecurityPolicy emptied = builder
				.connectDomains(Set.of()).resourceDomains(Set.of())
				.frameDomains(Set.of()).baseUriDomains(Set.of()).build();
		assertEquals(McpAppResourceMetadata.ContentSecurityPolicy.builder().build(), emptied);
	}

	@Test
	void everyPolicySetterRejectsInvalidReplacementAtomically() {
		McpAppResourceMetadata.ContentSecurityPolicy.Builder builder =
				McpAppResourceMetadata.ContentSecurityPolicy.builder()
						.connectDomains(Set.of("https://connect.example.com"))
						.resourceDomains(Set.of("https://resource.example.com"))
						.frameDomains(Set.of("https://frame.example.com"))
						.baseUriDomains(Set.of("https://base.example.com"));
		McpAppResourceMetadata.ContentSecurityPolicy before = builder.build();
		List<Function<Set<String>, McpAppResourceMetadata.ContentSecurityPolicy.Builder>>
				setters = List.of(builder::connectDomains, builder::resourceDomains,
						builder::frameDomains, builder::baseUriDomains);
		for (Function<Set<String>, McpAppResourceMetadata.ContentSecurityPolicy.Builder>
				setter : setters) {
			assertThrows(NullPointerException.class, () -> setter.apply(null));
			assertThrows(NullPointerException.class, () -> setter.apply(
					new LinkedHashSet<>(Arrays.asList("https://valid.example.com", null))));
			for (String invalid : List.of("*", "https:", "'self'", "'none'",
					"'unsafe-inline'", "'unsafe-eval'", "data:text/html,test",
					"blob:https://example.com/123", "https://example.com/",
					"https://example.com?x=1", "https://example.com#fragment",
					"https://user@example.com", "https://example.com:0",
					"https://example.com:065", "https://example.com:65536",
					"http://external.example.com", "https://EXAMPLE.com",
					"https://a.*.example.com", "https://example.com other",
					"https://example.com,other", "https://example.com;other",
					"https://example.com\\other", "https://%65xample.com",
					"https://example.com\n")) {
				Set<String> replacement = new LinkedHashSet<>(
						List.of("https://valid.example.com", invalid));
				assertThrows(IllegalArgumentException.class, () -> setter.apply(replacement));
			}
			assertEquals(before, builder.build());
		}
	}

	@Test
	void websocketOriginsAreRestrictedToConnectionAllowlists() {
		McpAppResourceMetadata.ContentSecurityPolicy.Builder builder =
				McpAppResourceMetadata.ContentSecurityPolicy.builder();
		Set<String> websocketOrigins = Set.of("wss://example.com", "ws://127.0.0.1:8080");
		assertEquals(websocketOrigins, builder.connectDomains(websocketOrigins).build()
				.getConnectDomains());
		assertThrows(IllegalArgumentException.class,
				() -> builder.resourceDomains(websocketOrigins));
		assertThrows(IllegalArgumentException.class,
				() -> builder.frameDomains(websocketOrigins));
		assertThrows(IllegalArgumentException.class,
				() -> builder.baseUriDomains(websocketOrigins));
		assertThrows(IllegalArgumentException.class,
				() -> builder.connectDomains(Set.of("ws://external.example.com")));
	}

	@Test
	void equalityIncludesEveryMetadataPropertyWithoutDependingOnSetOrder() {
		McpAppResourceMetadata.ContentSecurityPolicy policy =
				McpAppResourceMetadata.ContentSecurityPolicy.builder()
						.resourceDomains(Set.of("https://a.example.com", "https://b.example.com"))
						.build();
		McpAppResourceMetadata metadata = McpAppResourceMetadata.builder()
				.contentSecurityPolicy(policy).permissions(Set.of(CAMERA, MICROPHONE))
				.domain("private.example.com").prefersBorder(false).build();
		McpAppResourceMetadata equal = McpAppResourceMetadata.builder()
				.contentSecurityPolicy(McpAppResourceMetadata.ContentSecurityPolicy.builder()
						.resourceDomains(new LinkedHashSet<>(List.of(
								"https://b.example.com", "https://a.example.com"))).build())
				.permissions(new LinkedHashSet<>(List.of(MICROPHONE, CAMERA)))
				.domain("private.example.com").prefersBorder(false).build();
		assertEquals(metadata, metadata);
		assertEquals(metadata, equal);
		assertEquals(metadata.hashCode(), equal.hashCode());
		assertFalse(metadata.equals(null));
		assertFalse(metadata.equals("other"));
		assertNotEquals(metadata, McpAppResourceMetadata.builder()
				.permissions(Set.of(CAMERA, MICROPHONE))
				.domain("private.example.com").prefersBorder(false).build());
		assertNotEquals(metadata, McpAppResourceMetadata.builder()
				.contentSecurityPolicy(policy).permissions(Set.of(CAMERA))
				.domain("private.example.com").prefersBorder(false).build());
		assertNotEquals(metadata, McpAppResourceMetadata.builder()
				.contentSecurityPolicy(policy).permissions(Set.of(CAMERA, MICROPHONE))
				.domain("other.example.com").prefersBorder(false).build());
		assertNotEquals(metadata, McpAppResourceMetadata.builder()
				.contentSecurityPolicy(policy).permissions(Set.of(CAMERA, MICROPHONE))
				.domain("private.example.com").prefersBorder(true).build());
	}

	@Test
	void policyEqualityIncludesAllFourAllowlists() {
		Set<String> origins = Set.of("https://private.example.com");
		McpAppResourceMetadata.ContentSecurityPolicy policy =
				McpAppResourceMetadata.ContentSecurityPolicy.builder()
						.connectDomains(origins).resourceDomains(origins)
						.frameDomains(origins).baseUriDomains(origins).build();
		McpAppResourceMetadata.ContentSecurityPolicy equal =
				McpAppResourceMetadata.ContentSecurityPolicy.builder()
						.connectDomains(origins).resourceDomains(origins)
						.frameDomains(origins).baseUriDomains(origins).build();
		assertEquals(policy, policy);
		assertEquals(policy, equal);
		assertEquals(policy.hashCode(), equal.hashCode());
		assertFalse(policy.equals(null));
		assertFalse(policy.equals("other"));
		assertNotEquals(policy, McpAppResourceMetadata.ContentSecurityPolicy.builder()
				.resourceDomains(origins).frameDomains(origins).baseUriDomains(origins).build());
		assertNotEquals(policy, McpAppResourceMetadata.ContentSecurityPolicy.builder()
				.connectDomains(origins).frameDomains(origins).baseUriDomains(origins).build());
		assertNotEquals(policy, McpAppResourceMetadata.ContentSecurityPolicy.builder()
				.connectDomains(origins).resourceDomains(origins).baseUriDomains(origins).build());
		assertNotEquals(policy, McpAppResourceMetadata.ContentSecurityPolicy.builder()
				.connectDomains(origins).resourceDomains(origins).frameDomains(origins).build());
	}

	@Test
	void diagnosticStringsNeverExposeApplicationOriginsDomainOrPermissions() {
		McpAppResourceMetadata.ContentSecurityPolicy policy =
				McpAppResourceMetadata.ContentSecurityPolicy.builder()
						.connectDomains(Set.of("https://secret-connect.example.com"))
						.resourceDomains(Set.of("https://secret-resource.example.com"))
						.frameDomains(Set.of("https://secret-frame.example.com"))
						.baseUriDomains(Set.of("https://secret-base.example.com")).build();
		McpAppResourceMetadata metadata = McpAppResourceMetadata.builder()
				.contentSecurityPolicy(policy).domain("secret-domain.example.com")
				.permissions(Set.of(CAMERA, CLIPBOARD_WRITE)).prefersBorder(false).build();
		for (String diagnostic : List.of(policy.toString(), metadata.toString())) {
			assertTrue(diagnostic.contains("<redacted>"));
			assertFalse(diagnostic.contains("secret-"));
			assertFalse(diagnostic.contains("CAMERA"));
			assertFalse(diagnostic.contains("CLIPBOARD_WRITE"));
			assertFalse(diagnostic.contains("false"));
		}
	}

	@Test
	void valueAndBuilderConstructorsStayPrivateAndClosedPermissionOrderIsExact() {
		for (Class<?> type : List.of(McpAppResourceMetadata.class,
				McpAppResourceMetadata.Builder.class,
				McpAppResourceMetadata.ContentSecurityPolicy.class,
				McpAppResourceMetadata.ContentSecurityPolicy.Builder.class)) {
			assertTrue(Modifier.isFinal(type.getModifiers()));
			assertTrue(Arrays.stream(type.getDeclaredConstructors())
					.allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers())));
		}
		assertEquals(List.of(CAMERA, MICROPHONE, GEOLOCATION, CLIPBOARD_WRITE),
				List.of(McpAppResourceMetadata.Permission.values()));
	}
}
