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

import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.util.Locale;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Immutable, node-local localization context for one admitted
 * localization-capable MCP operation.
 * <p>
 * This is not a distributed session. It has no ID, is never serialized or
 * recovered on another node, and requires no routing affinity. The context
 * captures one locale and one immutable translation snapshot. A later request
 * on another node creates a new context from portable request or continuation
 * facts.
 * <p>
 * The context is not closeable and must not own resources whose correctness
 * depends on an exact close callback. Its lookup must be safe for concurrent
 * calls. Locale and revision remain equal for its full lifetime; equal
 * localizable-text inputs must produce equal results independently of lookup
 * order, including under concurrent calls.
 * <p>
 * This is a borrowed invocation-scoped feature. An application handler or
 * interceptor must not retain it or its feature carrier after invocation
 * termination. The lookup should capture only the minimum immutable
 * snapshot and must not capture the request or application object graph. Every
 * localization lookup must use that already-loaded snapshot and perform
 * bounded, in-memory, nonblocking work without remote TMS/network I/O, lazy
 * unbounded loading, or unbounded-executor dispatch.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpLocalizationContext {
	@NonNull
	private final Locale locale;
	@Nullable
	private final McpLocalizationRevision revision;
	@NonNull
	private final McpLocalizationLookup localizationLookup;

	/**
	 * Vends a context builder primed with its required construction values.
	 *
	 * @param locale canonical selected catalog locale
	 * @param localizationLookup request-local immutable-snapshot lookup
	 * @return localization-context builder
	 * @throws NullPointerException if an argument is null
	 * @throws IllegalArgumentException if {@code locale} is not a canonical,
	 * non-root BCP 47 locale of at most 255 ASCII bytes
	 */
	@NonNull
	public static Builder withLocale(@NonNull Locale locale,
			@NonNull McpLocalizationLookup localizationLookup) {
		return new Builder(locale, localizationLookup);
	}

	private McpLocalizationContext(@NonNull Builder builder) {
		this.locale = builder.locale;
		this.revision = builder.revision;
		this.localizationLookup = builder.localizationLookup;
	}

	/** @return canonical selected catalog locale */
	@NonNull
	public Locale getLocale() {
		return this.locale;
	}

	/**
	 * Returns optional non-secret identity for the captured catalog snapshot.
	 * Revisions are not MCP wire values or distributed-session identifiers.
	 *
	 * @return immutable snapshot revision, or empty
	 */
	@NonNull
	public Optional<@NonNull McpLocalizationRevision> getRevision() {
		return Optional.ofNullable(this.revision);
	}

	/**
	 * Localizes one framework-owned source-text field against this context's
	 * captured snapshot. The lookup must be bounded, in-memory, and nonblocking;
	 * it must not perform remote I/O or unbounded loading.
	 * <p>
	 * The localization lookup reports an unexpected failure with
	 * {@link McpLocalizationResult#failure()}; it must not throw to report an
	 * operational lookup failure. If an unchecked contract violation nevertheless
	 * escapes while Soklet invokes this callback, Soklet treats it as untrusted
	 * localization data and does not forward it through framework-owned
	 * lifecycle, simulation, logging, response-throwable, or cause surfaces.
	 * Direct application invocation is application-owned and has normal
	 * application failure semantics.
	 *
	 * @param text structured coordinate and canonical source text
	 * @return non-null localization result
	 * @throws NullPointerException if {@code text} is null or the configured
	 * lookup returns null
	 */
	@NonNull
	public McpLocalizationResult localize(@NonNull McpLocalizableText text) {
		return requireNonNull(
				this.localizationLookup.localize(requireNonNull(text, "text")),
				"The MCP localization lookup returned null.");
	}

	/** @return redacted diagnostic rendering */
	@Override
	@NonNull
	public String toString() {
		return "McpLocalizationContext{locale=<redacted>, "
				+ "revision=<redacted>, localizationLookup=<redacted>}";
	}

	/**
	 * Single-threaded builder for an immutable request-local context.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static final class Builder {
		@NonNull
		private final Locale locale;
		@Nullable
		private McpLocalizationRevision revision;
		@NonNull
		private final McpLocalizationLookup localizationLookup;

		private Builder(@NonNull Locale locale,
				@NonNull McpLocalizationLookup localizationLookup) {
			this.locale = McpLocaleSupport.requireCanonicalCatalogLocale(
					locale, "locale");
			this.localizationLookup = requireNonNull(localizationLookup);
		}

		/**
		 * Sets the non-secret identity for the captured catalog snapshot.
		 *
		 * @param revision immutable snapshot revision, or null to clear it
		 * @return this builder
		 */
		@NonNull
		public Builder revision(@Nullable McpLocalizationRevision revision) {
			this.revision = revision;
			return this;
		}

		/**
		 * Builds an immutable localization context.
		 *
		 * @return immutable request-local context
		 */
		@NonNull
		public McpLocalizationContext build() {
			return new McpLocalizationContext(this);
		}
	}
}
