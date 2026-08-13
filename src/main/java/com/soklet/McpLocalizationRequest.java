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

import javax.annotation.concurrent.ThreadSafe;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Immutable, bounded inputs supplied when creating one localization context
 * for an admitted MCP operation.
 * <p>
 * The effective language ranges initially derive from HTTP
 * {@code Accept-Language}. This value intentionally does not define or reserve
 * an MCP localization extension. For custom {@code resources/list}, the
 * separate cursor accessor exposes the same opaque application cursor that the
 * list handler receives, so locale selection can authenticate
 * application-owned pagination state before context creation.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public interface McpLocalizationRequest {
	/** @return the admitted semantic MCP request context */
	@NonNull
	McpRequestContext getRequestContext();

	/**
	 * Returns bounded effective client language preferences. The list contains
	 * at most 32 ranges after JDK alias expansion, preserves zero-weight
	 * exclusions, and is empty for missing, malformed, or over-limit HTTP input.
	 * Applications should use this view instead of reparsing the raw header.
	 *
	 * @return immutable language-range list, possibly empty
	 */
	@NonNull
	List<Locale.@NonNull LanguageRange> getLanguageRanges();

	/**
	 * Returns the verified locale carried by framework-protected continuation
	 * state. A present value is a required selection rather than a hint.
	 *
	 * @return required continuation locale, or empty for an independent request
	 */
	@NonNull
	Optional<@NonNull Locale> getContinuationLocale();

	/**
	 * Returns the opaque application-owned cursor for a custom
	 * {@code resources/list} operation. A present empty string is preserved.
	 * This is empty for every other operation. Soklet does not decode, rewrite,
	 * sign, bind, or otherwise interpret the value.
	 *
	 * @return custom resource-list cursor, or empty
	 */
	@NonNull
	Optional<@NonNull String> getResourceListCursor();

	/**
	 * Returns the configured locale of every canonical default text hosted by
	 * this server. This default-text fallback is independent of any terminal
	 * fallback configured in the provider's localization library.
	 *
	 * @return configured canonical fallback locale
	 */
	@NonNull
	Locale getFallbackLocale();
}
