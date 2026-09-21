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

import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class McpSkillAccessPolicyTests {
	private final McpRequestContext requestContext = (McpRequestContext)
			Proxy.newProxyInstance(McpRequestContext.class.getClassLoader(), new Class<?>[]{McpRequestContext.class},
					(proxy, method, arguments) -> { throw new AssertionError("Unexpected context access"); });
	private final McpInvocationFeatures invocationFeatures = McpInvocationFeatures.fromFeatures(Map.of());
	private final McpSkillRegistration skillRegistration = registration("one");

	@Test
	void defaultIsSharedAndPermitsAccessAndDiscovery() throws Exception {
		McpSkillAccessPolicy policy = McpSkillAccessPolicy.allowAllInstance();
		assertSame(policy, McpSkillAccessPolicy.allowAllInstance());
		assertTrue(policy.isSkillAccessible(requestContext, skillRegistration, invocationFeatures));
		assertTrue(policy.isSkillDiscoverable(requestContext, skillRegistration, invocationFeatures));
	}

	@Test
	void factoryRequiresBothEvaluators() {
		assertThrows(NullPointerException.class, () -> McpSkillAccessPolicy.fromEvaluators(null,
				(context, registration, features) -> true));
		assertThrows(NullPointerException.class, () -> McpSkillAccessPolicy.fromEvaluators(
				(context, registration, features) -> true, null));
	}

	@Test
	void dispatchRejectsNullInputsBeforeInvokingEitherEvaluator() {
		McpSkillAccessPolicy policy = McpSkillAccessPolicy.fromEvaluators(
				(context, registration, features) -> { throw new AssertionError("Invalid access input reached evaluator"); },
				(context, registration, features) -> { throw new AssertionError("Invalid discovery input reached evaluator"); });
		assertThrows(NullPointerException.class, () -> policy.isSkillAccessible(null, skillRegistration, invocationFeatures));
		assertThrows(NullPointerException.class, () -> policy.isSkillAccessible(requestContext, null, invocationFeatures));
		assertThrows(NullPointerException.class, () -> policy.isSkillAccessible(requestContext, skillRegistration, null));
		assertThrows(NullPointerException.class, () -> policy.isSkillDiscoverable(null, skillRegistration, invocationFeatures));
		assertThrows(NullPointerException.class, () -> policy.isSkillDiscoverable(requestContext, null, invocationFeatures));
		assertThrows(NullPointerException.class, () -> policy.isSkillDiscoverable(requestContext, skillRegistration, null));
	}

	@Test
	void independentEvaluatorsReceiveExactInputsOnceAndMayDeny() throws Exception {
		AtomicInteger accessCalls = new AtomicInteger(), discoveryCalls = new AtomicInteger();
		McpSkillAccessPolicy policy = McpSkillAccessPolicy.fromEvaluators((context, registration, features) -> {
			assertSame(requestContext, context);
			assertSame(skillRegistration, registration);
			assertSame(invocationFeatures, features);
			accessCalls.incrementAndGet();
			return true;
		}, (context, registration, features) -> {
			assertSame(requestContext, context);
			assertSame(skillRegistration, registration);
			assertSame(invocationFeatures, features);
			discoveryCalls.incrementAndGet();
			return false;
		});
		assertTrue(policy.isSkillAccessible(requestContext, skillRegistration, invocationFeatures));
		assertEquals(0, discoveryCalls.get());
		assertFalse(policy.isSkillDiscoverable(requestContext, skillRegistration, invocationFeatures));
		assertEquals(1, accessCalls.get());
		assertEquals(1, discoveryCalls.get());
	}

	@Test
	void nullResultsAndCheckedExceptionsNeverBecomePermission() {
		McpSkillAccessPolicy nullPolicy = McpSkillAccessPolicy.fromEvaluators(
				(context, registration, features) -> null, (context, registration, features) -> null);
		NullPointerException accessFailure = assertThrows(NullPointerException.class,
				() -> nullPolicy.isSkillAccessible(requestContext, skillRegistration, invocationFeatures));
		NullPointerException discoveryFailure = assertThrows(NullPointerException.class,
				() -> nullPolicy.isSkillDiscoverable(requestContext, skillRegistration, invocationFeatures));
		assertEquals("The MCP skill access evaluator returned null.", accessFailure.getMessage());
		assertEquals("The MCP skill discovery evaluator returned null.", discoveryFailure.getMessage());
		assertNull(accessFailure.getCause());
		assertNull(discoveryFailure.getCause());
		Exception expected = new Exception("private callback failure");
		McpSkillAccessPolicy failing = McpSkillAccessPolicy.fromEvaluators(
				(context, registration, features) -> { throw expected; },
				(context, registration, features) -> { throw expected; });
		assertSame(expected, assertThrows(Exception.class,
				() -> failing.isSkillAccessible(requestContext, skillRegistration, invocationFeatures)));
		assertSame(expected, assertThrows(Exception.class,
				() -> failing.isSkillDiscoverable(requestContext, skillRegistration, invocationFeatures)));
	}

	@Test
	void identityAndRenderingDoNotInspectEvaluatorCapabilities() {
		McpSkillAccessPolicy.AccessEvaluator access = new McpSkillAccessPolicy.AccessEvaluator() {
			@Override public Boolean isSkillAccessible(McpRequestContext context, McpSkillRegistration registration,
					McpInvocationFeatures features) { return true; }
			@Override public String toString() { throw new AssertionError("Evaluator rendering is private"); }
			@Override public int hashCode() { throw new AssertionError("Evaluator hashing is private"); }
			@Override public boolean equals(Object other) { throw new AssertionError("Evaluator equality is private"); }
		};
		McpSkillAccessPolicy.DiscoveryEvaluator discovery = (context, registration, features) -> true;
		McpSkillAccessPolicy first = McpSkillAccessPolicy.fromEvaluators(access, discovery);
		McpSkillAccessPolicy second = McpSkillAccessPolicy.fromEvaluators(access, discovery);
		assertNotEquals(first, second);
		assertEquals(first, first);
		assertEquals(System.identityHashCode(first), first.hashCode());
		assertEquals("McpSkillAccessPolicy{evaluators=<redacted>}", first.toString());
	}

	@Test
	void selectionContextSnapshotsExactCandidateIdentityAndLanguagePreferences() {
		McpSkillRegistration other = registration("two");
		List<McpSkillRegistration> candidates = new ArrayList<>(List.of(skillRegistration, other));
		List<Locale.LanguageRange> ranges = new ArrayList<>(List.of(
				new Locale.LanguageRange("fr-ca", 0.75), new Locale.LanguageRange("fr", 0), new Locale.LanguageRange("*", 0.1)));
		McpSkillVariantSelectionContext context = McpSkillVariantSelectionContext.from(" opaque-group ", candidates, ranges);
		List<Locale.LanguageRange> expectedRanges = List.copyOf(ranges);
		candidates.clear();
		ranges.clear();
		assertEquals(" opaque-group ", context.getSkillGroupKey());
		assertSame(skillRegistration, context.getSkillRegistrations().get(0));
		assertSame(other, context.getSkillRegistrations().get(1));
		assertEquals(expectedRanges, context.getLanguageRanges());
		assertEquals(0, context.getLanguageRanges().get(1).getWeight());
		assertThrows(UnsupportedOperationException.class, () -> context.getSkillRegistrations().clear());
		assertThrows(UnsupportedOperationException.class, () -> context.getLanguageRanges().clear());
		assertEquals("McpSkillVariantSelectionContext[redacted]", context.toString());
	}

	@Test
	void selectionContextRejectsNullInputsAndMembersButPreservesEmptyLists() {
		assertThrows(NullPointerException.class, () -> McpSkillVariantSelectionContext.from(null, List.of(), List.of()));
		assertThrows(NullPointerException.class, () -> McpSkillVariantSelectionContext.from("group", null, List.of()));
		assertThrows(NullPointerException.class, () -> McpSkillVariantSelectionContext.from("group", List.of(), null));
		assertThrows(NullPointerException.class, () -> McpSkillVariantSelectionContext.from("group",
				Arrays.asList(skillRegistration, null), List.of()));
		assertThrows(NullPointerException.class, () -> McpSkillVariantSelectionContext.from("group", List.of(),
				Arrays.asList(new Locale.LanguageRange("en"), null)));
		McpSkillVariantSelectionContext empty = McpSkillVariantSelectionContext.from("group", List.of(), List.of());
		assertTrue(empty.getSkillRegistrations().isEmpty());
		assertTrue(empty.getLanguageRanges().isEmpty());
	}

	@Test
	void selectorContractSupportsExactSelectionOmissionAndCheckedFailure() throws Exception {
		McpSkillVariantSelectionContext context = McpSkillVariantSelectionContext.from("group",
				List.of(skillRegistration), List.of());
		McpSkillVariantSelector selecting = (request, supplied, features) -> {
			assertSame(requestContext, request);
			assertSame(context, supplied);
			assertSame(invocationFeatures, features);
			return Optional.of(supplied.getSkillRegistrations().get(0));
		};
		assertSame(skillRegistration, selecting.select(requestContext, context, invocationFeatures).orElseThrow());
		McpSkillVariantSelector omitting = (request, supplied, features) -> Optional.empty();
		assertTrue(omitting.select(requestContext, context, invocationFeatures).isEmpty());
		Exception expected = new Exception("private selector failure");
		McpSkillVariantSelector failing = (request, supplied, features) -> { throw expected; };
		assertSame(expected, assertThrows(Exception.class, () -> failing.select(requestContext, context, invocationFeatures)));
	}

	private static McpSkillRegistration registration(String directory) {
		byte[] root = "---\nname: sample\ndescription: Synthetic description\n---\nOpaque body.\n".getBytes(StandardCharsets.UTF_8);
		return McpSkillRegistration.withUriAndSkillBundle(URI.create("skill://host.invalid/" + directory + "/sample/SKILL.md"),
				McpSkillBundle.fromFiles(Map.of("SKILL.md", root))).build();
	}
}
