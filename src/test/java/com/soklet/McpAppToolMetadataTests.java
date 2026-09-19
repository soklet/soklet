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

import java.lang.reflect.Modifier;
import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static com.soklet.McpAppToolMetadata.Visibility.APP;
import static com.soklet.McpAppToolMetadata.Visibility.MODEL;
import static org.junit.jupiter.api.Assertions.*;

class McpAppToolMetadataTests {

	@Test
	void defaultsHaveBothAudiencesButNoResourceAssociation() {
		McpAppToolMetadata metadata = McpAppToolMetadata.builder().build();
		assertTrue(metadata.getResourceUri().isEmpty());
		assertEquals(List.of(MODEL, APP), List.copyOf(metadata.getVisibility()));
		assertThrows(UnsupportedOperationException.class, () -> metadata.getVisibility().clear());
		assertEquals(List.of(MODEL, APP), Arrays.asList(McpAppToolMetadata.Visibility.values()));
	}

	@Test
	void explicitEmptyAndAppOnlyRemainIndependentOfResourceAssociation() {
		assertTrue(McpAppToolMetadata.builder().visibility(Set.of()).build().getVisibility().isEmpty());
		McpAppToolMetadata helper = McpAppToolMetadata.builder().visibility(Set.of(APP)).build();
		assertEquals(Set.of(APP), helper.getVisibility());
		assertTrue(helper.getResourceUri().isEmpty());
		URI uri = URI.create("ui://orders/dashboard");
		McpAppToolMetadata hidden = McpAppToolMetadata.builder().resourceUri(uri).visibility(Set.of()).build();
		assertEquals(uri, hidden.getResourceUri().orElseThrow());
		assertTrue(hidden.getVisibility().isEmpty());
	}

	@Test
	void snapshotsAreImmutableOrderedAndIndependentAcrossBuilds() {
		Set<McpAppToolMetadata.Visibility> input = new LinkedHashSet<>(List.of(APP, MODEL));
		McpAppToolMetadata.Builder builder = McpAppToolMetadata.builder().visibility(input);
		input.clear();
		McpAppToolMetadata first = builder.build();
		McpAppToolMetadata second = builder.visibility(Set.of(APP)).resourceUri(URI.create("ui://orders/new")).build();
		assertEquals(List.of(MODEL, APP), List.copyOf(first.getVisibility()));
		assertTrue(first.getResourceUri().isEmpty());
		assertEquals(Set.of(APP), second.getVisibility());
		assertThrows(UnsupportedOperationException.class, () -> second.getVisibility().add(MODEL));
	}

	@Test
	void rejectedSettersLeaveTheBuilderUnchanged() {
		URI original = URI.create("ui://orders/dashboard");
		McpAppToolMetadata.Builder builder = McpAppToolMetadata.builder().resourceUri(original).visibility(Set.of(MODEL));
		assertThrows(NullPointerException.class, () -> builder.resourceUri(null));
		assertThrows(IllegalArgumentException.class, () -> builder.resourceUri(URI.create("https://orders/dashboard")));
		assertThrows(NullPointerException.class, () -> builder.visibility(null));
		Set<McpAppToolMetadata.Visibility> invalid = new LinkedHashSet<>();
		invalid.add(APP);
		invalid.add(null);
		assertThrows(NullPointerException.class, () -> builder.visibility(invalid));
		assertEquals(original, builder.build().getResourceUri().orElseThrow());
		assertEquals(Set.of(MODEL), builder.build().getVisibility());
	}

	@Test
	void structuralEqualityIncludesOptionalUriAndEffectiveVisibility() {
		McpAppToolMetadata omitted = McpAppToolMetadata.builder().build();
		McpAppToolMetadata explicit = McpAppToolMetadata.builder().visibility(Set.of(APP, MODEL)).build();
		assertEquals(omitted, omitted);
		assertEquals(omitted, explicit);
		assertEquals(explicit, omitted);
		assertEquals(omitted.hashCode(), explicit.hashCode());
		assertNotEquals(omitted, McpAppToolMetadata.builder().visibility(Set.of()).build());
		assertNotEquals(omitted, McpAppToolMetadata.builder().resourceUri(URI.create("ui://orders/dashboard")).build());
		McpAppToolMetadata first = McpAppToolMetadata.builder().resourceUri(URI.create("ui://orders/dashboard")).build();
		McpAppToolMetadata copy = McpAppToolMetadata.builder().resourceUri(first.getResourceUri().orElseThrow()).visibility(first.getVisibility()).build();
		assertEquals(first, copy);
		assertEquals(first.hashCode(), copy.hashCode());
		assertNotEquals(first, McpAppToolMetadata.builder().resourceUri(URI.create("ui://orders/other")).build());
		assertNotEquals(omitted, null);
		assertNotEquals(omitted, "metadata");
	}

	@Test
	void diagnosticsDoNotExposeConfigurationAndConstructionStaysPrivate() {
		McpAppToolMetadata metadata = McpAppToolMetadata.builder().resourceUri(URI.create("ui://private-tenant/secret")).visibility(Set.of(APP)).build();
		assertEquals("McpAppToolMetadata{resourceUri=<redacted>, visibility=<redacted>}", metadata.toString());
		for (Class<?> type : List.of(McpAppToolMetadata.class, McpAppToolMetadata.Builder.class)) {
			assertTrue(Modifier.isFinal(type.getModifiers()));
			assertTrue(Arrays.stream(type.getDeclaredConstructors()).allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers())));
		}
	}
}
