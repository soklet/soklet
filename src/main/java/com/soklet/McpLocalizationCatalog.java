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

import static java.util.Objects.requireNonNull;

/**
 * Immutable deterministic extraction view of configured framework-owned MCP
 * presentation text.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpLocalizationCatalog {
	@NonNull
	private final List<@NonNull McpLocalizableText> texts;

	/**
	 * Extracts the canonical localizable catalog from the final endpoint
	 * registry. Annotated, generated, and programmatic registrations therefore
	 * share one extraction authority. Extraction rejects an external-key
	 * collision between unequal coordinates instead of silently merging fields.
	 *
	 * @param endpointRegistry final immutable endpoint registry
	 * @return deterministic localization catalog
	 * @throws NullPointerException if {@code endpointRegistry} is null
	 * @throws IllegalStateException if unequal coordinates produce the same
	 * external key
	 */
	@NonNull
	public static McpLocalizationCatalog fromEndpointRegistry(
			@NonNull McpEndpointRegistry endpointRegistry) {
		return new McpLocalizationCatalog(
				DefaultMcpLocalizationCatalogExtractor.extract(
						requireNonNull(endpointRegistry)));
	}

	private McpLocalizationCatalog(
			@NonNull List<@NonNull McpLocalizableText> texts) {
		this.texts = List.copyOf(texts);
	}

	/**
	 * Returns every configured eligible source field exactly once in stable
	 * external-key order.
	 *
	 * @return immutable extracted text list
	 */
	@NonNull
	public List<@NonNull McpLocalizableText> getTexts() {
		return this.texts;
	}

	/** @return whether the extracted text lists are structurally equal */
	@Override
	public boolean equals(@Nullable Object other) {
		return this == other
				|| other instanceof McpLocalizationCatalog catalog
				&& this.texts.equals(catalog.texts);
	}

	/** @return structural localization-catalog hash code */
	@Override
	public int hashCode() {
		return this.texts.hashCode();
	}
}
