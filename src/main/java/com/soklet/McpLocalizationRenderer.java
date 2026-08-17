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

import com.soklet.internal.mcp.protocol.McpJsonObject;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.ThreadSafe;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static java.util.Objects.requireNonNull;

/**
 * Fail-atomic rendering of one framework-owned catalog response.
 * <p>
 * The renderer either publishes a complete localized candidate or discards it
 * entirely; it never emits a partially localized response. Budget is tracked
 * exactly, starting from the canonical encoded length plus the request-specific
 * envelope and request-ID bytes, so an over-ceiling replacement is abandoned
 * before it is retained and before any further application callback runs.
 * <p>
 * The absolute deadline and cancelation boundary is checked immediately before
 * and after every {@code localize(...)} call. Expiry, cancelation, a provider
 * failure result, or a provider exception stops scheduling every later slot.
 * <p>
 * No provider text, locale, or coordinate ever reaches the outcome, because
 * these values may not become framework log fields, exception text, or metric
 * labels.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class McpLocalizationRenderer {
	private McpLocalizationRenderer() {
	}

	/** Whether the request has already become terminal. */
	@FunctionalInterface
	interface TerminalBoundary {
		boolean isTerminal();
	}

	/** Exact encoded length of a candidate document, per the production encoder. */
	@FunctionalInterface
	interface EncodedLength {
		long of(@NonNull McpJsonObject document);
	}

	/** What the caller should publish. */
	enum Disposition {
		/** Rendering succeeded but replaced nothing; publish the canonical object. */
		CANONICAL,
		/** Publish the returned document, which is fully localized. */
		LOCALIZED,
		/** Localization failed; publish canonical source text. */
		DEFAULT_TEXT,
		/** Localization failed; return the fixed sanitized internal error. */
		FAIL_REQUEST
	}

	/**
	 * Rendering outcome; the document is canonical unless localized, and the
	 * selected locale is present exactly when provider selection succeeded.
	 */
	record Outcome(@NonNull Disposition disposition,
			@NonNull McpJsonObject document,
			@Nullable Locale selectedLocale) {
		Outcome {
			requireNonNull(disposition, "disposition");
			requireNonNull(document, "document");
		}
	}

	/**
	 * Renders one response through a temporary copy-on-write overlay.
	 *
	 * @param canonicalDocument canonical prevalidated publication document
	 * @param canonicalEncodedBytes precomputed encoded length of that document
	 * @param envelopeBytes exact request-specific envelope and request-ID bytes
	 * @param maximumResponseBytes production response ceiling
	 * @param maximumReplacementCharacters stricter of the production decoded-string
	 *        and serialized-token character limits
	 * @param slots precompiled slots for this response, in plan order
	 * @param context request-scoped provider context
	 * @param failurePolicy configured whole-response failure behavior
	 * @param terminalBoundary deadline and cancelation boundary
	 * @param encodedLength production encoder used as the authoritative check
	 * @return the disposition and the document to publish
	 */
	@NonNull
	static Outcome render(@NonNull McpJsonObject canonicalDocument,
			long canonicalEncodedBytes, long envelopeBytes,
			long maximumResponseBytes, long maximumReplacementCharacters,
			@NonNull List<McpCanonicalLocalizationPlan.@NonNull Slot> slots,
			@NonNull McpLocalizationContext context,
			@NonNull McpLocalizationFailurePolicy failurePolicy,
			@NonNull TerminalBoundary terminalBoundary,
			@NonNull EncodedLength encodedLength) {
		requireNonNull(canonicalDocument, "canonicalDocument");
		requireNonNull(slots, "slots");
		requireNonNull(context, "context");
		requireNonNull(failurePolicy, "failurePolicy");
		requireNonNull(terminalBoundary, "terminalBoundary");
		requireNonNull(encodedLength, "encodedLength");

		long projectedBytes = canonicalEncodedBytes + envelopeBytes;

		// Fail before the first callback if even the untouched response cannot fit.
		if (projectedBytes > maximumResponseBytes)
			return failed(canonicalDocument, failurePolicy);

		if (terminalBoundary.isTerminal())
			return failed(canonicalDocument, failurePolicy);

		// The selected locale is provider data: it must be canonical and non-root.
		Locale selectedLocale;

		try {
			selectedLocale = McpLocaleSupport.requireCanonicalCatalogLocale(
					requireNonNull(context.getLocale()), "selectedLocale");
		} catch (Throwable exception) {
			if (exception instanceof InterruptedException)
				Thread.currentThread().interrupt();

			return failed(canonicalDocument, failurePolicy);
		}

		if (terminalBoundary.isTerminal())
			return failed(canonicalDocument, failurePolicy);

		List<McpLocalizationOverlay.Replacement> replacements = new ArrayList<>();

		for (McpCanonicalLocalizationPlan.Slot slot : slots) {
			if (terminalBoundary.isTerminal())
				return failed(canonicalDocument, failurePolicy);

			McpLocalizationResult result;

			try {
				result = context.localize(slot.text());
			} catch (Throwable exception) {
				// The whole throwable is untrusted localization data - Errors and
				// sneaky-thrown checked exceptions included - and must never reach
				// a framework observation, lifecycle, or log surface.
				if (exception instanceof InterruptedException)
					Thread.currentThread().interrupt();

				return failed(canonicalDocument, failurePolicy);
			}

			if (terminalBoundary.isTerminal())
				return failed(canonicalDocument, failurePolicy);

			String replacementText;

			if (result instanceof McpLocalizationResult.Localized localized) {
				replacementText = localized.text();
			} else if (result instanceof McpLocalizationResult.UseDefaultText) {
				continue;
			} else {
				// A Failure result, or a null the contract forbids.
				return failed(canonicalDocument, failurePolicy);
			}

			// Reject before retention when the replacement can never encode. The
			// production writer independently bounds decoded characters and serialized
			// token characters; escaping means neither is implied by the byte ceiling.
			try {
				if (replacementText.length() > maximumReplacementCharacters
						|| McpLocalizationByteAccounting.serializedTokenCharacters(
								replacementText) > maximumReplacementCharacters)
					return failed(canonicalDocument, failurePolicy);
			} catch (RuntimeException exception) {
				return failed(canonicalDocument, failurePolicy);
			}

			String defaultText = slot.text().getDefaultText();

			// An identical replacement keeps the canonical subtree shared.
			if (replacementText.equals(defaultText))
				continue;

			long delta;

			try {
				delta = McpLocalizationByteAccounting.replacementByteDelta(
						defaultText, replacementText);
			} catch (RuntimeException exception) {
				return failed(canonicalDocument, failurePolicy);
			}

			projectedBytes += delta;

			// Abandon the candidate before retaining an over-ceiling replacement.
			if (projectedBytes > maximumResponseBytes)
				return failed(canonicalDocument, failurePolicy);

			replacements.add(new McpLocalizationOverlay.Replacement(
					slot.targetPointer(), replacementText));
		}

		// Nothing to replace: the caller publishes its existing canonical object,
		// which makes no-op byte parity structural rather than incidental.
		if (replacements.isEmpty())
			return new Outcome(Disposition.CANONICAL, canonicalDocument,
					selectedLocale);

		McpJsonObject candidate;

		try {
			candidate = McpLocalizationOverlay.withReplacements(canonicalDocument,
					replacements);
		} catch (RuntimeException exception) {
			return failed(canonicalDocument, failurePolicy);
		}

		// The production encoder is the authoritative aggregate check.
		try {
			if (encodedLength.of(candidate) + envelopeBytes > maximumResponseBytes)
				return failed(canonicalDocument, failurePolicy);
		} catch (RuntimeException exception) {
			return failed(canonicalDocument, failurePolicy);
		}

		return new Outcome(Disposition.LOCALIZED, candidate, selectedLocale);
	}

	@NonNull
	private static Outcome failed(@NonNull McpJsonObject canonicalDocument,
			@NonNull McpLocalizationFailurePolicy failurePolicy) {
		return new Outcome(
				failurePolicy == McpLocalizationFailurePolicy.USE_DEFAULT_TEXT
						? Disposition.DEFAULT_TEXT
						: Disposition.FAIL_REQUEST,
				canonicalDocument, null);
	}
}
