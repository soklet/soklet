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

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.ThreadSafe;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Immutable request-local inputs handed to an application localization context
 * provider exactly once per admitted localization-capable operation.
 * <p>
 * Every field is captured before the provider runs, so the provider cannot
 * observe or mutate later request state. The cursor is carried verbatim: a
 * present empty string stays distinct from absence, and Soklet never decodes,
 * rewrites, signs, or logs it.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class DefaultMcpLocalizationRequest implements McpLocalizationRequest {
	@NonNull
	private final McpRequestContext requestContext;
	@NonNull
	private final List<Locale.@NonNull LanguageRange> languageRanges;
	@Nullable
	private final Locale continuationLocale;
	@Nullable
	private final String resourceListCursor;
	@NonNull
	private final Locale fallbackLocale;

	DefaultMcpLocalizationRequest(@NonNull McpRequestContext requestContext,
			@NonNull List<Locale.@NonNull LanguageRange> languageRanges,
			@Nullable Locale continuationLocale,
			@Nullable String resourceListCursor,
			@NonNull Locale fallbackLocale) {
		this.requestContext = requireNonNull(requestContext, "requestContext");
		this.languageRanges = List.copyOf(
				requireNonNull(languageRanges, "languageRanges"));
		this.continuationLocale = continuationLocale;
		this.resourceListCursor = resourceListCursor;
		this.fallbackLocale = requireNonNull(fallbackLocale, "fallbackLocale");
	}

	@Override
	@NonNull
	public McpRequestContext getRequestContext() {
		return this.requestContext;
	}

	@Override
	@NonNull
	public List<Locale.@NonNull LanguageRange> getLanguageRanges() {
		return this.languageRanges;
	}

	@Override
	@NonNull
	public Optional<@NonNull Locale> getContinuationLocale() {
		return Optional.ofNullable(this.continuationLocale);
	}

	@Override
	@NonNull
	public Optional<@NonNull String> getResourceListCursor() {
		return Optional.ofNullable(this.resourceListCursor);
	}

	@Override
	@NonNull
	public Locale getFallbackLocale() {
		return this.fallbackLocale;
	}

	@Override
	@NonNull
	public String toString() {
		// Deliberately omits ranges, cursor, and locales: none may become a log field.
		return "DefaultMcpLocalizationRequest{}";
	}
}
