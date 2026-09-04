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

import com.soklet.internal.mcp.protocol.McpJsonCodec;
import com.soklet.internal.mcp.protocol.McpJsonLimits;
import com.soklet.internal.mcp.protocol.McpJsonObject;
import com.soklet.internal.mcp.protocol.McpJsonString;
import com.soklet.internal.mcp.protocol.McpJsonValue;
import org.junit.jupiter.api.Test;

import javax.annotation.concurrent.ThreadSafe;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fail-atomic rendering: exact budgeting, boundary checks around every provider
 * callback, and whole-response failure behavior.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
class McpLocalizationRendererTests {
	private static final McpJsonCodec CODEC =
			new McpJsonCodec(McpJsonLimits.productionDefaults());
	private static final long GENEROUS_CEILING = 1_000_000L;
	private static final long ENVELOPE_BYTES = 64L;
	private static final long MAXIMUM_REPLACEMENT_CHARACTERS = 10_000L;

	@Test
	void everyLocalizedSlotIsAppliedToTheCandidate() {
		McpLocalizationRenderer.Outcome outcome = render(GENEROUS_CEILING,
				text -> McpLocalizationResult.localized(
						"L:" + text.getDefaultText()));

		assertEquals(McpLocalizationRenderer.Disposition.LOCALIZED,
				outcome.disposition());
		assertEquals("L:Canonical instructions",
				stringAt(outcome.document(), "instructions"));
		assertEquals("L:Canonical title",
				stringAt(outcome.document(), "title"));
	}

	@Test
	void anAllDefaultTextRenderReturnsTheCanonicalInstanceForByteParity() {
		McpJsonObject canonical = catalog();
		McpLocalizationRenderer.Outcome outcome = render(canonical,
				GENEROUS_CEILING, text -> McpLocalizationResult.useDefaultText());

		assertEquals(McpLocalizationRenderer.Disposition.CANONICAL,
				outcome.disposition());
		assertSame(canonical, outcome.document(),
				"An untouched render must reuse the canonical document exactly.");
	}

	@Test
	void aReplacementIdenticalToTheDefaultKeepsTheCanonicalInstance() {
		McpJsonObject canonical = catalog();
		McpLocalizationRenderer.Outcome outcome = render(canonical,
				GENEROUS_CEILING, text -> McpLocalizationResult.localized(
						text.getDefaultText()));

		assertEquals(McpLocalizationRenderer.Disposition.CANONICAL,
				outcome.disposition());
		assertSame(canonical, outcome.document());
	}

	@Test
	void aFailureResultTakesTheConfiguredWholeResponsePathAndStopsLaterSlots() {
		List<String> observed = new ArrayList<>();

		McpLocalizationRenderer.Outcome useDefault = render(catalog(),
				GENEROUS_CEILING, McpLocalizationFailurePolicy.USE_DEFAULT_TEXT,
				() -> false, text -> {
					observed.add(text.getDefaultText());
					return McpLocalizationResult.failure();
				});

		assertEquals(McpLocalizationRenderer.Disposition.DEFAULT_TEXT,
				useDefault.disposition());
		assertEquals(1, observed.size(),
				"The first failure must stop scheduling every later slot.");

		McpLocalizationRenderer.Outcome failRequest = render(catalog(),
				GENEROUS_CEILING, McpLocalizationFailurePolicy.FAIL_REQUEST,
				() -> false, text -> McpLocalizationResult.failure());

		assertEquals(McpLocalizationRenderer.Disposition.FAIL_REQUEST,
				failRequest.disposition());
	}

	@Test
	void aProviderExceptionIsContainedAndTakesTheWholeResponsePath() {
		List<String> observed = new ArrayList<>();

		McpLocalizationRenderer.Outcome outcome = render(catalog(),
				GENEROUS_CEILING, McpLocalizationFailurePolicy.USE_DEFAULT_TEXT,
				() -> false, text -> {
					observed.add(text.getDefaultText());
					throw new IllegalStateException("provider detail must not escape");
				});

		assertEquals(McpLocalizationRenderer.Disposition.DEFAULT_TEXT,
				outcome.disposition());
		assertEquals(1, observed.size());
	}

	@Test
	void aTerminalBoundaryBeforeTheFirstCallbackInvokesNoProviderCode() {
		List<String> observed = new ArrayList<>();

		McpLocalizationRenderer.Outcome outcome = render(catalog(),
				GENEROUS_CEILING, McpLocalizationFailurePolicy.USE_DEFAULT_TEXT,
				() -> true, text -> {
					observed.add(text.getDefaultText());
					return McpLocalizationResult.localized("never");
				});

		assertEquals(McpLocalizationRenderer.Disposition.DEFAULT_TEXT,
				outcome.disposition());
		assertEquals(List.of(), observed);
	}

	@Test
	void aBoundaryThatTurnsTerminalDuringTheFirstCallbackStopsLaterSlots() {
		List<String> observed = new ArrayList<>();
		boolean[] terminal = {false};

		McpLocalizationRenderer.Outcome outcome = render(catalog(),
				GENEROUS_CEILING, McpLocalizationFailurePolicy.USE_DEFAULT_TEXT,
				() -> terminal[0], text -> {
					observed.add(text.getDefaultText());
					terminal[0] = true;
					return McpLocalizationResult.localized("late");
				});

		assertEquals(McpLocalizationRenderer.Disposition.DEFAULT_TEXT,
				outcome.disposition());
		assertEquals(1, observed.size(),
				"The post-callback boundary check must stop the remaining slots.");
	}

	@Test
	void anUntouchedResponseThatCannotFitFailsBeforeAnyCallback() {
		List<String> observed = new ArrayList<>();
		McpJsonObject canonical = catalog();
		long canonicalBytes = CODEC.toUtf8Bytes(canonical).length;

		McpLocalizationRenderer.Outcome outcome = render(canonical,
				canonicalBytes + ENVELOPE_BYTES - 1,
				McpLocalizationFailurePolicy.USE_DEFAULT_TEXT, () -> false,
				text -> {
					observed.add(text.getDefaultText());
					return McpLocalizationResult.localized("never");
				});

		assertEquals(McpLocalizationRenderer.Disposition.DEFAULT_TEXT,
				outcome.disposition());
		assertEquals(List.of(), observed,
				"An impossible response must not call application code at all.");
	}

	@Test
	void anOverCeilingReplacementIsAbandonedBeforeItIsRetained() {
		List<String> observed = new ArrayList<>();
		McpJsonObject canonical = catalog();
		long canonicalBytes = CODEC.toUtf8Bytes(canonical).length;

		// Exactly enough headroom for a four byte growth, then ask for more.
		McpLocalizationRenderer.Outcome outcome = render(canonical,
				canonicalBytes + ENVELOPE_BYTES + 4,
				McpLocalizationFailurePolicy.USE_DEFAULT_TEXT, () -> false,
				text -> {
					observed.add(text.getDefaultText());
					return McpLocalizationResult.localized(
							text.getDefaultText() + "0123456789");
				});

		assertEquals(McpLocalizationRenderer.Disposition.DEFAULT_TEXT,
				outcome.disposition());
		assertSame(canonical, outcome.document());
		assertEquals(1, observed.size(),
				"Budget exhaustion must stop scheduling every later slot.");
	}

	@Test
	void aShorterReplacementReclaimsBudgetForALaterLongerOne() {
		McpJsonObject canonical = catalog();
		long canonicalBytes = CODEC.toUtf8Bytes(canonical).length;

		// No headroom at all: the second slot only fits because the first shrank.
		McpLocalizationRenderer.Outcome outcome = render(canonical,
				canonicalBytes + ENVELOPE_BYTES,
				McpLocalizationFailurePolicy.FAIL_REQUEST, () -> false,
				text -> McpLocalizationResult.localized(
						"Canonical instructions".equals(text.getDefaultText())
								? "x"
								: text.getDefaultText() + "ABCDEFGHIJKLMNOPQRSTU"));

		assertEquals(McpLocalizationRenderer.Disposition.LOCALIZED,
				outcome.disposition());
		assertEquals("x", stringAt(outcome.document(), "instructions"));
		assertTrue(CODEC.toUtf8Bytes(outcome.document()).length + ENVELOPE_BYTES
				<= canonicalBytes + ENVELOPE_BYTES);
	}

	@Test
	void anErrorThrownByLocalizeIsContainedAndStopsLaterSlots() {
		List<String> observed = new ArrayList<>();

		McpLocalizationRenderer.Outcome outcome = render(catalog(),
				GENEROUS_CEILING, McpLocalizationFailurePolicy.USE_DEFAULT_TEXT,
				() -> false, text -> {
					observed.add(text.getDefaultText());
					throw new AssertionError("secret-provider-detail");
				});

		assertEquals(McpLocalizationRenderer.Disposition.DEFAULT_TEXT,
				outcome.disposition());
		assertEquals(1, observed.size());
	}

	@Test
	void aReplacementOverTheCharacterLimitFailsBeforeRetentionAndLaterSlots() {
		List<String> observed = new ArrayList<>();

		McpLocalizationRenderer.Outcome outcome = McpLocalizationRenderer.render(
				catalog(), CODEC.toUtf8Bytes(catalog()).length, ENVELOPE_BYTES,
				GENEROUS_CEILING, 8L, slots(), context(text -> {
					observed.add(text.getDefaultText());
					return McpLocalizationResult.localized("123456789");
				}), McpLocalizationFailurePolicy.USE_DEFAULT_TEXT, () -> false,
				document -> CODEC.toUtf8Bytes(document).length);

		assertEquals(McpLocalizationRenderer.Disposition.DEFAULT_TEXT,
				outcome.disposition());
		assertEquals(1, observed.size(),
				"A nine-character replacement against an eight-character limit "
						+ "must stop scheduling every later slot.");
	}

	@Test
	void anEscapeExpandedTokenOverTheLimitStopsLaterSlotsBeforeFinalEncoding() {
		List<String> observed = new ArrayList<>();
		String escapeHeavy = "\0".repeat(3);

		McpLocalizationRenderer.Outcome outcome = McpLocalizationRenderer.render(
				catalog(), CODEC.toUtf8Bytes(catalog()).length, ENVELOPE_BYTES,
				GENEROUS_CEILING, 17L, slots(), context(text -> {
					observed.add(text.getDefaultText());
					return McpLocalizationResult.localized(escapeHeavy);
				}), McpLocalizationFailurePolicy.USE_DEFAULT_TEXT, () -> false,
				document -> CODEC.toUtf8Bytes(document).length);

		assertEquals(McpLocalizationRenderer.Disposition.DEFAULT_TEXT,
				outcome.disposition());
		assertEquals(1, observed.size(),
				"Three decoded characters expand to eighteen token characters, "
						+ "so the second provider callback must never run.");
	}

	private static McpLocalizationContext localeContext(Locale locale,
			McpLocalizationLookup localizationLookup) {
		return McpLocalizationContext.withLocale(locale, localizationLookup)
				.build();
	}

	private static McpLocalizationRenderer.Outcome render(long ceiling,
			McpLocalizationLookup localizationLookup) {
		return render(catalog(), ceiling, localizationLookup);
	}

	private static McpLocalizationRenderer.Outcome render(McpJsonObject canonical,
			long ceiling, McpLocalizationLookup localizationLookup) {
		return render(canonical, ceiling,
				McpLocalizationFailurePolicy.USE_DEFAULT_TEXT, () -> false,
				localizationLookup);
	}

	private static McpLocalizationRenderer.Outcome render(McpJsonObject canonical,
			long ceiling, McpLocalizationFailurePolicy failurePolicy,
			McpLocalizationRenderer.TerminalBoundary boundary,
			McpLocalizationLookup localizationLookup) {
		return McpLocalizationRenderer.render(canonical,
				CODEC.toUtf8Bytes(canonical).length, ENVELOPE_BYTES, ceiling,
				MAXIMUM_REPLACEMENT_CHARACTERS, slots(), context(localizationLookup),
				failurePolicy, boundary,
				document -> CODEC.toUtf8Bytes(document).length);
	}

	private static List<McpCanonicalLocalizationPlan.Slot> slots() {
		return List.of(
				new McpCanonicalLocalizationPlan.Slot(
						text("Canonical instructions", "/instructions"),
						"/instructions"),
				new McpCanonicalLocalizationPlan.Slot(
						text("Canonical title", "/title"), "/title"));
	}

	private static McpLocalizableText text(String defaultText, String memberPath) {
		return new McpLocalizableText(new McpTextCoordinate("/mcp",
				McpTextOwnerType.ENDPOINT, "endpoint", memberPath),
				defaultText);
	}

	private static McpLocalizationContext context(
			McpLocalizationLookup localizationLookup) {
		return localeContext(Locale.FRENCH, localizationLookup);
	}

	private static McpJsonObject catalog() {
		Map<String, McpJsonValue> members = new LinkedHashMap<>();
		members.put("instructions", new McpJsonString("Canonical instructions"));
		members.put("title", new McpJsonString("Canonical title"));
		return new McpJsonObject(members);
	}

	private static String stringAt(McpJsonObject document, String member) {
		return ((McpJsonString) document.members().get(member)).value();
	}
}
