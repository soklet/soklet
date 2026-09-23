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

import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class McpSkillPolicyEvaluatorTests {
	@Test
	void discoveryChecksAccessBeforeDiscoverabilityAndPreservesStandaloneOrder() throws Exception {
		McpSkillRegistration visible = skill("visible", null), denied = skill("denied", null), hidden = skill("hidden", null);
		McpEndpoint endpoint = endpoint(List.of(visible, denied, hidden), List.of());
		List<String> calls = new ArrayList<>();
		McpSkillAccessPolicy policy = McpSkillAccessPolicy.fromEvaluators((request, skill, features) -> {
			calls.add("access:" + skill.getSkillBundle().getName()); return skill != denied;
		}, (request, skill, features) -> {
			calls.add("discover:" + skill.getSkillBundle().getName()); return skill != hidden;
		});
		List<McpSkillRegistration> result = discover(endpoint, policy, null, List.of());
		assertEquals(List.of(visible), result);
		assertEquals(List.of("access:visible", "discover:visible", "access:denied", "access:hidden", "discover:hidden"), calls);
		assertThrows(UnsupportedOperationException.class, result::clear);
	}

	@Test
	void standaloneLocaleIsDescriptiveNotAutomaticallyNegotiated() throws Exception {
		McpSkillRegistration registration = skill("one", Locale.FRENCH);
		assertEquals(List.of(registration), discover(endpoint(List.of(registration), List.of()),
				allowAll(), null, ranges("*;q=0")));
	}

	@Test
	void singletonExclusionsUseSpecificityAndBasicSubtagBoundariesNotLocaleFilter() throws Exception {
		McpSkillRegistration registration = skill("one", Locale.forLanguageTag("pt-BR"));
		McpEndpoint endpoint = endpoint(List.of(), List.of(group("one", registration)));
		for (String header : List.of("pt-BR, pt;q=0", "pt-BR;q=0.1, *;q=0", "fr;q=0", "p;q=0", "en", "pt-BR-*;q=0"))
			assertEquals(List.of(registration), discover(endpoint, allowAll(), null, ranges(header)), header);
		for (String header : List.of("pt;q=0", "*;q=0", "pt-BR;q=0, pt;q=1"))
			assertTrue(discover(endpoint, allowAll(), null, ranges(header)).isEmpty(), header);
		assertEquals(List.of(registration), discover(endpoint, allowAll(), null, List.of()));
		assertTrue(discover(endpoint, allowAll(), null, List.of(new Locale.LanguageRange("pt", 0),
				new Locale.LanguageRange("pt", 1))).isEmpty());
		assertEquals(List.of(registration), discover(endpoint, allowAll(), null,
				List.of(new Locale.LanguageRange("pt", 1), new Locale.LanguageRange("pt", 0))));
	}

	@Test
	void undeclaredSingletonRequiresEmptyBoundedPreferencesIncludingIntentionalParseFallback() throws Exception {
		McpSkillRegistration registration = skill("one", null);
		McpEndpoint endpoint = endpoint(List.of(), List.of(group("one", registration)));
		assertTrue(discover(endpoint, allowAll(), null, ranges("en")).isEmpty());
		assertTrue(discover(endpoint, allowAll(), null, ranges("*")).isEmpty());
		for (String header : List.of("", "en;q=invalid", "a".repeat(4097))) {
			assertTrue(ranges(header).isEmpty());
			assertEquals(List.of(registration), discover(endpoint, allowAll(), null, ranges(header)));
		}
	}

	@Test
	void selectorSeesOnlyEligibleIdentityCandidatesAndSamePreferencesFeaturesAndLocalization() throws Exception {
		McpSkillRegistration standalone = skill("standalone", null);
		McpSkillRegistration denied = variant("one", "denied", Locale.GERMAN);
		McpSkillRegistration hidden = variant("one", "hidden", Locale.ENGLISH);
		McpSkillRegistration selected = variant("one", "selected", Locale.FRENCH);
		McpSkillRegistration singleton = skill("singleton", null);
		McpEndpoint endpoint = endpoint(List.of(standalone), List.of(group("filtered", denied, hidden, selected),
				group("singleton", singleton), group("empty")));
		McpRequestContext request = request(endpoint);
		McpLocalizationContext localization = McpLocalizationContext.withLocale(Locale.FRENCH,
				text -> { throw new AssertionError("Skills selection must not translate authored bytes."); }).build();
		McpInvocationFeatures features = McpInvocationFeatures.fromFeatures(Map.of(CancelationToken.class,
				token(new AtomicBoolean()), McpLocalizationContext.class, localization));
		List<Locale.LanguageRange> ranges = ranges("fr, de;q=0");
		List<String> calls = new ArrayList<>();
		McpSkillVariantSelector selector = (ctx, selection, invocation) -> {
			assertSame(request, ctx); assertSame(features, invocation);
			assertSame(localization, invocation.require(McpLocalizationContext.class));
			assertEquals(ranges, selection.getLanguageRanges());
			assertSame(ranges.get(0), selection.getLanguageRanges().get(0));
			calls.add(selection.getSkillGroupKey());
			McpSkillRegistration expected = selection.getSkillGroupKey().equals("filtered") ? selected : singleton;
			assertEquals(List.of(expected), selection.getSkillRegistrations());
			assertSame(expected, selection.getSkillRegistrations().get(0));
			return Optional.of(expected);
		};
		McpSkillAccessPolicy policy = McpSkillAccessPolicy.fromEvaluators((ctx, skill, invocation) -> skill != denied,
				(ctx, skill, invocation) -> skill != hidden);
		assertEquals(List.of(standalone, selected, singleton), new McpSkillPolicyEvaluator(endpoint, policy, selector)
				.discover(request, ranges, features, () -> false));
		assertEquals(List.of("filtered", "singleton"), calls);
	}

	@Test
	void omittedAndFullyFilteredGroupsDoNotPickFirstOrInvokeEmptySelector() throws Exception {
		McpSkillRegistration registration = skill("one", Locale.ENGLISH);
		McpEndpoint endpoint = endpoint(List.of(), List.of(group("one", registration)));
		AtomicInteger calls = new AtomicInteger();
		McpSkillVariantSelector selector = (ctx, selection, features) -> { calls.incrementAndGet(); return Optional.empty(); };
		assertTrue(discover(endpoint, allowAll(), selector, List.of()).isEmpty());
		assertEquals(1, calls.get());
		McpSkillAccessPolicy deny = McpSkillAccessPolicy.fromEvaluators((ctx, skill, features) -> false,
				(ctx, skill, features) -> { throw new AssertionError(); });
		assertTrue(discover(endpoint, deny, selector, List.of()).isEmpty());
		assertEquals(1, calls.get());
	}

	@Test
	void multivariantConfigurationCannotUseCallerFilteringAsImplicitSelector() {
		McpEndpoint endpoint = endpoint(List.of(), List.of(group("one", variant("one", "en", Locale.ENGLISH),
				variant("one", "fr", Locale.FRENCH))));
		assertThrows(IllegalStateException.class, () -> new McpSkillPolicyEvaluator(endpoint,
				McpSkillAccessPolicy.fromEvaluators((ctx, skill, features) -> false, (ctx, skill, features) -> false), null));
	}

	@Test
	void selectorRejectsEqualCopiesForeignGroupsNullAndFailuresWithoutLeakingDiagnostics() {
		McpSkillRegistration registration = skill("one", Locale.ENGLISH), equal = skill("one", Locale.ENGLISH);
		McpSkillRegistration other = skill("other", null);
		assertEquals(registration, equal); assertNotSame(registration, equal);
		McpEndpoint endpoint = endpoint(List.of(other), List.of(group("one", registration)));
		List<McpSkillVariantSelector> selectors = List.of((ctx, selection, features) -> Optional.of(equal),
				(ctx, selection, features) -> Optional.of(other), (ctx, selection, features) -> null,
				(ctx, selection, features) -> { throw new Exception("private-policy-canary"); },
				(ctx, selection, features) -> { throw new AssertionError("private-policy-canary"); });
		for (McpSkillVariantSelector selector : selectors)
			assertRedacted(() -> discover(endpoint, allowAll(), selector, List.of()));
	}

	@Test
	void accessAndDiscoveryNullsAndFailuresNeverPublishPartialLists() {
		McpSkillRegistration first = skill("first", null), second = skill("second", null);
		McpEndpoint endpoint = endpoint(List.of(first, second), List.of());
		List<McpSkillAccessPolicy> policies = List.of(
				McpSkillAccessPolicy.fromEvaluators((ctx, skill, features) -> skill == first ? true : null,
						(ctx, skill, features) -> true),
				McpSkillAccessPolicy.fromEvaluators((ctx, skill, features) -> true,
						(ctx, skill, features) -> skill == first ? true : null),
				McpSkillAccessPolicy.fromEvaluators((ctx, skill, features) -> { throw new Exception("private-policy-canary"); },
						(ctx, skill, features) -> true),
				McpSkillAccessPolicy.fromEvaluators((ctx, skill, features) -> true,
						(ctx, skill, features) -> { throw new AssertionError("private-policy-canary"); }));
		for (McpSkillAccessPolicy policy : policies) assertRedacted(() -> discover(endpoint, policy, null, List.of()));
	}

	@Test
	void exactSkillLookupBypassesDiscoveryAndSelectionAndRechecksAfterRevocation() throws Exception {
		McpSkillRegistration registration = skill("one", Locale.FRENCH);
		McpEndpoint endpoint = endpoint(List.of(), List.of(group("one", registration)));
		AtomicBoolean allowed = new AtomicBoolean(true);
		AtomicInteger checks = new AtomicInteger();
		McpSkillPolicyEvaluator evaluator = new McpSkillPolicyEvaluator(endpoint, McpSkillAccessPolicy.fromEvaluators(
				(ctx, skill, features) -> { assertSame(registration, skill); checks.incrementAndGet(); return allowed.get(); },
				(ctx, skill, features) -> { throw new AssertionError("Direct lookup must ignore discovery."); }),
				(ctx, selection, features) -> { throw new AssertionError("Direct lookup must ignore language selection."); });
		McpRequestContext request = request(endpoint);
		assertSame(registration, evaluator.findAccessibleSkill(URI.create(registration.getUri().toString()
				.replace("skill://host", "SKILL://HOST")), request, features(), () -> false).orElseThrow());
		allowed.set(false);
		assertTrue(evaluator.findAccessibleSkill(registration.getUri(), request, features(), () -> false).isEmpty());
		assertTrue(evaluator.findAccessibleSkill(URI.create("skill://unknown/SKILL.md"), request, features(), () -> false).isEmpty());
		assertEquals(2, checks.get());
	}

	@Test
	void sharedFileAllowsEitherOwnerButSeparateSkillStillRequiresItsExactGrant() throws Exception {
		List<McpSkillRegistration> family = family();
		for (McpSkillRegistration permitted : family) {
			McpEndpoint endpoint = endpoint(family, List.of());
			List<McpSkillRegistration> checked = new ArrayList<>();
			McpSkillPolicyEvaluator evaluator = new McpSkillPolicyEvaluator(endpoint, McpSkillAccessPolicy.fromEvaluators(
					(ctx, skill, features) -> { checked.add(skill); return skill == permitted; },
					(ctx, skill, features) -> { throw new AssertionError("Reads do not evaluate discovery."); }), null);
			McpRequestContext request = request(endpoint);
			assertSame(endpoint.skillIndex().findFile(family.get(1).getUri()).orElseThrow(),
					evaluator.findAccessibleFile(family.get(1).getUri(), request, features(), () -> false).orElseThrow());
			assertEquals(family, checked);
			assertEquals(permitted == family.get(1), evaluator.findAccessibleSkill(family.get(1).getUri(),
					request, features(), () -> false).isPresent());
		}
	}

	@Test
	void sharedOwnerFailureBeatsGrantInEitherOrderAndEveryOwnerRunsOnce() {
		List<McpSkillRegistration> family = family();
		for (List<McpSkillRegistration> order : List.of(family, List.of(family.get(1), family.get(0)))) {
			McpEndpoint endpoint = endpoint(order, List.of());
			for (int kind = 0; kind < 3; ++kind) {
				int failureKind = kind;
				List<McpSkillRegistration> checked = new ArrayList<>();
				McpSkillPolicyEvaluator evaluator = new McpSkillPolicyEvaluator(endpoint, McpSkillAccessPolicy.fromEvaluators(
						(ctx, skill, features) -> {
							checked.add(skill);
							if (skill == family.get(0)) return true;
							if (failureKind == 0) return null;
							if (failureKind == 1) throw new Exception("private-policy-canary");
							throw new AssertionError("private-policy-canary");
						}, (ctx, skill, features) -> true), null);
				assertRedacted(() -> evaluator.findAccessibleFile(family.get(1).getUri(), request(endpoint), features(), () -> false));
				assertEquals(order, checked);
			}
		}
	}

	@Test
	void sharedReadsRecheckAllOwnersAndUnknownAndAllDeniedAreNeutral() throws Exception {
		List<McpSkillRegistration> family = family();
		McpEndpoint endpoint = endpoint(family, List.of());
		AtomicBoolean allowed = new AtomicBoolean(true);
		AtomicInteger calls = new AtomicInteger();
		McpSkillPolicyEvaluator evaluator = new McpSkillPolicyEvaluator(endpoint, McpSkillAccessPolicy.fromEvaluators(
				(ctx, skill, features) -> { calls.incrementAndGet(); return allowed.get(); }, (ctx, skill, features) -> true), null);
		assertTrue(evaluator.findAccessibleFile(family.get(1).getUri(), request(endpoint), features(), () -> false).isPresent());
		allowed.set(false);
		assertTrue(evaluator.findAccessibleFile(family.get(1).getUri(), request(endpoint), features(), () -> false).isEmpty());
		assertTrue(evaluator.findAccessibleFile(URI.create("skill://unknown/SKILL.md"), request(endpoint), features(), () -> false).isEmpty());
		assertEquals(4, calls.get());
	}

	@Test
	void deadlineCheckedBeforeAndAfterCallbacksAndSharedOwnersUseOneBoundary() {
		List<McpSkillRegistration> family = family();
		McpEndpoint endpoint = endpoint(family, List.of());
		AtomicBoolean expired = new AtomicBoolean(true);
		AtomicInteger calls = new AtomicInteger();
		McpSkillPolicyEvaluator evaluator = new McpSkillPolicyEvaluator(endpoint, McpSkillAccessPolicy.fromEvaluators(
				(ctx, skill, features) -> { calls.incrementAndGet(); expired.set(true); return true; },
				(ctx, skill, features) -> { fail("No discovery callback after deadline."); return true; }), null);
		assertThrows(InterruptedException.class, () -> evaluator.discover(request(endpoint), List.of(), features(), expired::get));
		assertEquals(0, calls.get());
		expired.set(false);
		assertThrows(InterruptedException.class, () -> evaluator.findAccessibleFile(family.get(1).getUri(),
				request(endpoint), features(), expired::get));
		assertEquals(1, calls.get());
		expired.set(false);
		assertThrows(InterruptedException.class, () -> evaluator.discover(request(endpoint), List.of(), features(), expired::get));
		assertEquals(2, calls.get());
	}

	@Test
	void selectionCannotPublishAfterOverallDeadline() {
		McpSkillRegistration registration = skill("one", null);
		McpEndpoint endpoint = endpoint(List.of(), List.of(group("one", registration)));
		AtomicBoolean expired = new AtomicBoolean();
		McpSkillPolicyEvaluator evaluator = new McpSkillPolicyEvaluator(endpoint, allowAll(), (ctx, selection, features) -> {
			expired.set(true); return Optional.of(registration);
		});
		assertThrows(InterruptedException.class, () -> evaluator.discover(request(endpoint), List.of(), features(), expired::get));
	}

	@Test
	void cancellationStopsBeforeCallbacksAndCallbackInterruptsArePreservedWithoutCanaries() {
		McpSkillRegistration registration = skill("one", null);
		McpEndpoint endpoint = endpoint(List.of(registration), List.of());
		AtomicInteger calls = new AtomicInteger();
		McpSkillPolicyEvaluator evaluator = new McpSkillPolicyEvaluator(endpoint, McpSkillAccessPolicy.fromEvaluators(
				(ctx, skill, features) -> { calls.incrementAndGet(); throw new InterruptedException("private-policy-canary"); },
				(ctx, skill, features) -> true), null);
		McpInvocationFeatures canceled = McpInvocationFeatures.fromFeatures(Map.of(CancelationToken.class, token(new AtomicBoolean(true))));
		assertThrows(InterruptedException.class, () -> evaluator.discover(request(endpoint), List.of(), canceled, () -> false));
		assertEquals(0, calls.get());
		try {
			InterruptedException failure = assertThrows(InterruptedException.class,
					() -> evaluator.discover(request(endpoint), List.of(), features(), () -> false));
			assertTrue(Thread.currentThread().isInterrupted());
			assertEquals("MCP Skills policy evaluation was canceled.", failure.getMessage());
			assertNull(failure.getCause());
		} finally { Thread.interrupted(); }
		assertEquals(1, calls.get());
	}

	@Test
	void selectorInterruptIsPreservedAndRedacted() {
		McpSkillRegistration registration = skill("one", null);
		McpEndpoint endpoint = endpoint(List.of(), List.of(group("one", registration)));
		McpSkillPolicyEvaluator evaluator = new McpSkillPolicyEvaluator(endpoint, allowAll(), (ctx, selection, features) -> {
			throw new InterruptedException("private-policy-canary");
		});
		try {
			InterruptedException failure = assertThrows(InterruptedException.class,
					() -> evaluator.discover(request(endpoint), List.of(), features(), () -> false));
			assertTrue(Thread.currentThread().isInterrupted());
			assertEquals("MCP Skills policy evaluation was canceled.", failure.getMessage());
			assertNull(failure.getCause());
		} finally { Thread.interrupted(); }
	}

	@Test
	void anotherEndpointCannotSupplyTheAdmittedRequestContext() {
		McpEndpoint endpoint = endpoint(List.of(skill("one", null)), List.of());
		McpSkillPolicyEvaluator evaluator = new McpSkillPolicyEvaluator(endpoint, allowAll(), null);
		assertRedacted(() -> evaluator.discover(request(endpoint(List.of(), List.of())), List.of(), features(), () -> false));
	}

	private static List<McpSkillRegistration> discover(McpEndpoint endpoint, McpSkillAccessPolicy policy,
			McpSkillVariantSelector selector, List<Locale.LanguageRange> ranges) throws Exception {
		return new McpSkillPolicyEvaluator(endpoint, policy, selector).discover(request(endpoint), ranges, features(), () -> false);
	}
	private static McpSkillAccessPolicy allowAll() { return McpSkillAccessPolicy.allowAllInstance(); }
	private static List<Locale.LanguageRange> ranges(String header) { return McpLocaleSupport.boundedLanguageRanges(List.of(header)); }
	private static McpSkillGroup group(String key, McpSkillRegistration... registrations) {
		return McpSkillGroup.fromKeyAndSkillRegistrations(key, List.of(registrations));
	}
	private static McpEndpoint endpoint(List<McpSkillRegistration> registrations, List<McpSkillGroup> groups) {
		return McpEndpoint.withPath("/skills", McpImplementation.withNameAndVersion("test", "1").build())
				.skillRegistrations(registrations).skillGroups(groups).build();
	}
	private static McpSkillRegistration skill(String name, Locale locale) { return variant(name, "version", locale); }
	private static McpSkillRegistration variant(String name, String prefix, Locale locale) {
		McpSkillRegistration.Builder builder = McpSkillRegistration.withUriAndSkillBundle(
				URI.create("skill://host/" + prefix + "/" + name + "/SKILL.md"), McpSkillBundle.fromFiles(Map.of("SKILL.md", root(name))));
		if (locale != null) builder.locale(locale);
		return builder.build();
	}
	private static List<McpSkillRegistration> family() {
		return List.of(McpSkillRegistration.withUriAndSkillBundle(URI.create("skill://host/parent/SKILL.md"),
				McpSkillBundle.fromFiles(Map.of("SKILL.md", root("parent"), "child/SKILL.md", root("child")))).build(),
				McpSkillRegistration.withUriAndSkillBundle(URI.create("skill://host/parent/child/SKILL.md"),
						McpSkillBundle.fromFiles(Map.of("SKILL.md", root("child")))).build());
	}
	private static byte[] root(String name) {
		return ("---\nname: " + name + "\ndescription: Test description\n---\nOriginal bytes.\n").getBytes(StandardCharsets.UTF_8);
	}
	private static McpRequestContext request(McpEndpoint endpoint) {
		return (McpRequestContext) Proxy.newProxyInstance(McpRequestContext.class.getClassLoader(),
				new Class<?>[]{McpRequestContext.class}, (proxy, method, args) -> {
					if (method.getName().equals("getEndpoint")) return endpoint;
					throw new AssertionError("Policy core must reuse supplied context, not renegotiate or reparse headers.");
				});
	}
	private static McpInvocationFeatures features() {
		return McpInvocationFeatures.fromFeatures(Map.of(CancelationToken.class, token(new AtomicBoolean())));
	}
	private static CancelationToken token(AtomicBoolean canceled) {
		return new CancelationToken() {
			public Boolean isCanceled() { return canceled.get(); }
			public Optional<StreamTerminationReason> getCancelationReason() { return Optional.empty(); }
			public Optional<Throwable> getCancelationCause() { return Optional.empty(); }
			public CallbackRegistration onCancel(Runnable callback) { throw new AssertionError(); }
		};
	}
	private static void assertRedacted(Executable operation) {
		IllegalStateException failure = assertThrows(IllegalStateException.class, operation);
		assertEquals("MCP Skills policy evaluation failed.", failure.getMessage());
		assertNull(failure.getCause());
		assertEquals(0, failure.getSuppressed().length);
	}
}
