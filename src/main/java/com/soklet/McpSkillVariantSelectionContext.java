/*
 * Copyright 2022-2026 Revetware LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
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

import static java.util.Objects.requireNonNull;

/**
 * Immutable, framework-supplied input to a Skills variant selector.
 *
 * <p>Only accessible, discoverable registrations are exposed, in their configured
 * group order. The application-local group key is available without exposing the
 * unfiltered group. Candidate identity is retained so a selector can return an
 * exact supplied registration. This context is selection input, not an access
 * grant or a second localization negotiation.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpSkillVariantSelectionContext {
	@NonNull
	private final String skillGroupKey;
	@NonNull
	private final List<@NonNull McpSkillRegistration> skillRegistrations;
	@NonNull
	private final List<Locale.@NonNull LanguageRange> languageRanges;

	private McpSkillVariantSelectionContext(@NonNull String skillGroupKey,
			@NonNull List<@NonNull McpSkillRegistration> skillRegistrations,
			@NonNull List<Locale.@NonNull LanguageRange> languageRanges) {
		this.skillGroupKey = requireNonNull(skillGroupKey);
		this.skillRegistrations = List.copyOf(requireNonNull(skillRegistrations));
		this.languageRanges = List.copyOf(requireNonNull(languageRanges));
	}

	@NonNull
	static McpSkillVariantSelectionContext from(@NonNull String skillGroupKey,
			@NonNull List<@NonNull McpSkillRegistration> skillRegistrations,
			@NonNull List<Locale.@NonNull LanguageRange> languageRanges) {
		return new McpSkillVariantSelectionContext(skillGroupKey, skillRegistrations, languageRanges);
	}

	/** @return exact application-defined, endpoint-local group key */
	@NonNull
	public String getSkillGroupKey() { return this.skillGroupKey; }

	/** @return immutable accessible, discoverable candidates in configured group order */
	@NonNull
	public List<@NonNull McpSkillRegistration> getSkillRegistrations() { return this.skillRegistrations; }

	/**
	 * Returns the immutable bounded client language preferences and exclusions.
	 * The original parsed order and weights, including zero weights, are retained.
	 * An empty list indicates no express language constraint under the bounded
	 * request parsing rules, not a newly selected or inferred locale.
	 *
	 * @return immutable bounded language ranges
	 */
	@NonNull
	public List<Locale.@NonNull LanguageRange> getLanguageRanges() { return this.languageRanges; }

	/** @return diagnostic rendering without group, candidate, or language data */
	@Override
	@NonNull
	public String toString() { return "McpSkillVariantSelectionContext[redacted]"; }
}
