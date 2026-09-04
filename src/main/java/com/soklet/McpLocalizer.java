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

import static java.util.Objects.requireNonNull;

/**
 * Immutable server-wide MCP localization behavior and policy.
 * <p>
 * The configured fallback locale is the language of every canonical
 * annotation and builder string hosted by the server and the locale of
 * canonical default text. It is independent of a provider or localization
 * library's terminal fallback locale. The application-owned context provider
 * performs request-specific locale selection and fallback resolution and
 * captures one immutable translation snapshot.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpLocalizer {
	private static final int DEFAULT_MAXIMUM_LOCALIZABLE_TEXT_COUNT_PER_RESPONSE =
			32_768;
	private static final int MAXIMUM_SUPPORTED_LOCALIZABLE_TEXT_COUNT_PER_RESPONSE =
			100_000;
	@NonNull
	private final Locale fallbackLocale;
	@NonNull
	private final McpLocalizationContextProvider contextProvider;
	@NonNull
	private final McpLocalizationFailurePolicy failurePolicy;
	private final int maximumLocalizableTextCountPerResponse;

	/**
	 * Vends a builder primed with the canonical non-root fallback locale and
	 * application-owned context provider.
	 *
	 * @param fallbackLocale canonical BCP 47 fallback locale
	 * @param localizationContextProvider request context provider
	 * @return localizer builder
	 * @throws NullPointerException if an argument is null
	 * @throws IllegalArgumentException if {@code fallbackLocale} is not a
	 * canonical, non-root BCP 47 locale of at most 255 ASCII bytes
	 */
	@NonNull
	public static Builder withFallbackLocale(@NonNull Locale fallbackLocale,
			@NonNull McpLocalizationContextProvider
					localizationContextProvider) {
		Locale requiredFallbackLocale =
				McpLocaleSupport.requireCanonicalCatalogLocale(
						fallbackLocale, "fallbackLocale");
		return new Builder(requiredFallbackLocale, localizationContextProvider);
	}

	private McpLocalizer(@NonNull Builder builder) {
		this.fallbackLocale = builder.fallbackLocale;
		this.contextProvider = builder.contextProvider;
		this.failurePolicy = builder.failurePolicy;
		this.maximumLocalizableTextCountPerResponse =
				builder.maximumLocalizableTextCountPerResponse;
	}

	/** @return configured canonical non-root fallback locale */
	@NonNull
	public Locale getFallbackLocale() {
		return this.fallbackLocale;
	}

	/** @return application-owned request context provider */
	@NonNull
	public McpLocalizationContextProvider getContextProvider() {
		return this.contextProvider;
	}

	/**
	 * Returns the whole-response framework-catalog failure policy. It does not
	 * authorize a synthetic context when application context creation fails.
	 *
	 * @return framework-catalog localization failure policy
	 */
	@NonNull
	public McpLocalizationFailurePolicy getFailurePolicy() {
		return this.failurePolicy;
	}

	/**
	 * Returns the maximum number of provider text lookups permitted while
	 * rendering one framework response.
	 *
	 * @return positive bounded response-local lookup limit
	 */
	@NonNull
	public Integer getMaximumLocalizableTextCountPerResponse() {
		return this.maximumLocalizableTextCountPerResponse;
	}

	/**
	 * Single-threaded builder for an immutable localizer.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static final class Builder {
		@NonNull
		private final Locale fallbackLocale;
		@NonNull
		private final McpLocalizationContextProvider contextProvider;
		@NonNull
		private McpLocalizationFailurePolicy failurePolicy;
		private int maximumLocalizableTextCountPerResponse;

		private Builder(@NonNull Locale fallbackLocale,
				@NonNull McpLocalizationContextProvider
						localizationContextProvider) {
			this.fallbackLocale = fallbackLocale;
			this.contextProvider = requireNonNull(localizationContextProvider);
			this.failurePolicy =
					McpLocalizationFailurePolicy.USE_DEFAULT_TEXT;
			this.maximumLocalizableTextCountPerResponse =
					DEFAULT_MAXIMUM_LOCALIZABLE_TEXT_COUNT_PER_RESPONSE;
		}

		/**
		 * Sets the whole-response behavior for unexpected framework-catalog
		 * provider or replacement validation failure. The default uses canonical
		 * fallback text. Application context-creation failure remains a sanitized
		 * failure before handler/interceptor entry under either policy.
		 *
		 * @param failurePolicy failure policy, or null to restore the default
		 * @return this builder
		 */
		@NonNull
		public Builder failurePolicy(
				@Nullable McpLocalizationFailurePolicy failurePolicy) {
			this.failurePolicy = failurePolicy == null
					? McpLocalizationFailurePolicy.USE_DEFAULT_TEXT
					: failurePolicy;
			return this;
		}

		/**
		 * Sets the maximum provider lookup count for one framework response.
		 * The default is 32,768 and the supported range is 1 through 100,000.
		 *
		 * @param maximumLocalizableTextCountPerResponse positive bounded limit, or
		 *                                               null to restore the default
		 * @return this builder
		 * @throws IllegalArgumentException if the value is outside the supported
		 * range
		 */
		@NonNull
		public Builder maximumLocalizableTextCountPerResponse(
				@Nullable Integer maximumLocalizableTextCountPerResponse) {
			if (maximumLocalizableTextCountPerResponse == null) {
				this.maximumLocalizableTextCountPerResponse =
						DEFAULT_MAXIMUM_LOCALIZABLE_TEXT_COUNT_PER_RESPONSE;
				return this;
			}
			int requiredMaximum = maximumLocalizableTextCountPerResponse;
			if (requiredMaximum < 1
					|| requiredMaximum
					> MAXIMUM_SUPPORTED_LOCALIZABLE_TEXT_COUNT_PER_RESPONSE)
				throw new IllegalArgumentException(
						"Maximum localizable text count per response must be "
								+ "between 1 and 100000.");
			this.maximumLocalizableTextCountPerResponse = requiredMaximum;
			return this;
		}

		/** @return immutable server localizer */
		@NonNull
		public McpLocalizer build() {
			return new McpLocalizer(this);
		}
	}
}
