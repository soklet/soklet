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

package com.soklet.internal.mcp.protocol;

import com.soklet.McpRequestContext;
import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.ThreadSafe;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.ToLongFunction;

import static java.util.Objects.requireNonNull;

/**
 * Runtime bridge seam for localizing a framework-owned catalog response.
 * <p>
 * The transport owns request admission, capacity, and the response ceiling; the
 * public server layer owns the configured localizer, the canonical slot plan,
 * and every application callback. This interface is the only coupling between
 * them, so the transport never sees a localizer, a provider, or a locale.
 * <p>
 * An implementation is installed only when a localizer is configured. Without
 * one the transport publishes its existing precomputed objects untouched, which
 * is what keeps wire bytes golden-identical.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public interface McpRuntimeCatalogLocalizer {
	/** Which framework-owned catalog response is being rendered. */
	enum ResponseKind {
		DISCOVERY, TOOLS_LIST, PROMPTS_LIST, RESOURCES_LIST,
		RESOURCE_TEMPLATES_LIST, SUBSCRIPTION_TERMINAL
	}

	/** What the transport should publish. */
	enum Disposition {
		/** Publish the existing canonical object untouched. */
		CANONICAL,
		/** Publish the returned localized document. */
		LOCALIZED,
		/** Return the fixed sanitized internal error. */
		FAIL_REQUEST
	}

	/**
	 * Localization outcome. The document is meaningful only when localized, and
	 * a present content language names the canonical BCP 47 tag the transport
	 * emits as {@code Content-Language} for this representation.
	 */
	@ThreadSafe
	record Outcome(@NonNull Disposition disposition,
			@NonNull McpJsonObject document,
			@NonNull Optional<@NonNull String> contentLanguage) {
		public Outcome {
			requireNonNull(disposition, "disposition");
			requireNonNull(document, "document");
			requireNonNull(contentLanguage, "contentLanguage");
		}

		@NonNull
		public static Outcome canonical(@NonNull McpJsonObject document) {
			return new Outcome(Disposition.CANONICAL, document, Optional.empty());
		}

		/** @return redacted rendering; the document may hold localized text */
		@Override
		@NonNull
		public String toString() {
			return "Outcome{disposition=" + disposition + "}";
		}
	}

	/**
	 * Everything the public layer needs, captured before any provider callback.
	 *
	 * @param endpointPath canonical endpoint path owning this response
	 * @param responseKind which catalog response is being rendered
	 * @param requestContext admitted immutable public request context
	 * @param canonicalDocument canonical prevalidated result document
	 * @param canonicalEncodedBytes encoded length of the canonical document
	 * @param envelopeBytes exact request-specific envelope and request-ID bytes
	 * @param maximumResponseBytes production response ceiling
	 * @param maximumReplacementCharacters production JSON string character limit
	 * @param encodedLength exact encoded length under the production encoder
	 * @param acceptLanguageValues raw unparsed {@code Accept-Language} values, in
	 *        exact wire encounter order
	 * @param resourceListCursor validated opaque custom resource-list cursor
	 * @param terminalBoundary whether the request has already become terminal
	 */
	@ThreadSafe
	record Input(@NonNull String endpointPath, @NonNull ResponseKind responseKind,
			@NonNull McpRequestContext requestContext,
			@NonNull McpJsonObject canonicalDocument, long canonicalEncodedBytes,
			long envelopeBytes, long maximumResponseBytes,
			long maximumReplacementCharacters,
			@NonNull ToLongFunction<@NonNull McpJsonObject> encodedLength,
			@NonNull List<@NonNull String> acceptLanguageValues,
			@NonNull List<@NonNull String> resourceListCursor,
			@NonNull BooleanSupplier terminalBoundary) {
		public Input {
			requireNonNull(endpointPath, "endpointPath");
			requireNonNull(responseKind, "responseKind");
			requireNonNull(requestContext, "requestContext");
			requireNonNull(canonicalDocument, "canonicalDocument");
			requireNonNull(encodedLength, "encodedLength");
			// Order is load-bearing: Locale.LanguageRange.parse keeps the FIRST
			// occurrence of a repeated range, so scrambling could flip a q=0
			// exclusion into a top preference.
			acceptLanguageValues = List.copyOf(
					requireNonNull(acceptLanguageValues, "acceptLanguageValues"));
			// A single-element list carries a present cursor, including the
			// empty string; an empty list means the operation has no cursor.
			resourceListCursor = List.copyOf(
					requireNonNull(resourceListCursor, "resourceListCursor"));
			requireNonNull(terminalBoundary, "terminalBoundary");
		}

		/** @return redacted rendering; header values and cursor are private data */
		@Override
		@NonNull
		public String toString() {
			return "Input{endpointPath=" + endpointPath
					+ ", responseKind=" + responseKind + "}";
		}
	}

	/**
	 * Localizes one framework-owned catalog response.
	 *
	 * @param input immutable inputs captured before any provider callback
	 * @return what the transport should publish; never {@code null}
	 */
	@NonNull
	Outcome localizeCatalog(@NonNull Input input);

	/**
	 * Names the framework catalog response kinds this endpoint actually
	 * localizes, so list-change advertisement, subscription filters, and
	 * invalidation delivery stay truthful per surface.
	 *
	 * @return immutable localized response kinds; never {@code null}
	 */
	@NonNull
	Set<@NonNull ResponseKind> localizedResponseKinds();
}
