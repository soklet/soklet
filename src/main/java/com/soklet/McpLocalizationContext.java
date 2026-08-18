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
import java.util.function.Function;

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
 * depends on an exact close callback. Its localizer must be safe for concurrent
 * calls. Locale and revision remain equal for its full lifetime; equal
 * localizable-text inputs must produce equal results independently of lookup
 * order, including under concurrent calls.
 * <p>
 * This is a borrowed invocation-scoped feature. An application handler or
 * interceptor must not retain it or its feature carrier after invocation
 * termination. The localizer should capture only the minimum immutable lookup
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
	private final Function<@NonNull McpLocalizableText,
			@NonNull McpLocalizationResult> localizer;

	/**
	 * Vends a context builder primed with the selected catalog locale.
	 *
	 * @param locale canonical selected catalog locale
	 * @return localization-context builder
	 * @throws NullPointerException if {@code locale} is null
	 * @throws IllegalArgumentException if {@code locale} is not a canonical,
	 * non-root BCP 47 locale of at most 255 ASCII bytes
	 */
	@NonNull
	public static Builder withLocale(@NonNull Locale locale) {
		return new Builder(locale);
	}

	private McpLocalizationContext(@NonNull Builder builder) {
		this.locale = builder.locale;
		this.revision = builder.revision;
		this.localizer = requireNonNull(builder.localizer);
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
	 * The localizer reports an unexpected lookup failure with
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
	 * localizer returns null
	 */
	@NonNull
	public McpLocalizationResult localize(@NonNull McpLocalizableText text) {
		return requireNonNull(this.localizer.apply(requireNonNull(text, "text")),
				"The MCP localization context localizer returned null.");
	}

	/** @return redacted diagnostic rendering */
	@Override
	@NonNull
	public String toString() {
		return "McpLocalizationContext{locale=<redacted>, "
				+ "revision=<redacted>, localizer=<redacted>}";
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
		@Nullable
		private Function<@NonNull McpLocalizableText,
				@NonNull McpLocalizationResult> localizer;

		private Builder(@NonNull Locale locale) {
			this.locale = McpLocaleSupport.requireCanonicalCatalogLocale(
					locale, "locale");
		}

		/**
		 * Sets the non-secret identity for the captured catalog snapshot.
		 *
		 * @param revision immutable snapshot revision
		 * @return this builder
		 * @throws NullPointerException if {@code revision} is null
		 */
		@NonNull
		public Builder revision(@NonNull McpLocalizationRevision revision) {
			this.revision = requireNonNull(revision);
			return this;
		}

		/**
		 * Sets the thread-safe lookup function for the captured immutable
		 * translation snapshot. The callback must obey the bounded, nonblocking,
		 * deterministic contract documented by
		 * {@link McpLocalizationContext#localize(McpLocalizableText)}.
		 *
		 * @param localizer request-local snapshot lookup
		 * @return this builder
		 * @throws NullPointerException if {@code localizer} is null
		 */
		@NonNull
		public Builder localizer(@NonNull Function<@NonNull McpLocalizableText,
				@NonNull McpLocalizationResult> localizer) {
			this.localizer = requireNonNull(localizer);
			return this;
		}

		/**
		 * Builds an immutable localization context.
		 *
		 * @return immutable request-local context
		 * @throws IllegalStateException if no localizer was supplied
		 */
		@NonNull
		public McpLocalizationContext build() {
			if (this.localizer == null)
				throw new IllegalStateException(
						"An MCP localization context localizer must be configured.");
			return new McpLocalizationContext(this);
		}
	}
}
