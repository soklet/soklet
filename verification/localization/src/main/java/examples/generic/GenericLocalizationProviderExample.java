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

package examples.generic;

import com.soklet.McpEndpoint;
import com.soklet.McpEndpointRegistry;
import com.soklet.McpImplementation;
import com.soklet.McpLocalizableText;
import com.soklet.McpLocalizationCatalog;
import com.soklet.McpLocalizationContext;
import com.soklet.McpLocalizationContextProvider;
import com.soklet.McpLocalizationRequest;
import com.soklet.McpLocalizationResult;
import com.soklet.McpLocalizer;
import com.soklet.McpRequestContext;
import com.soklet.McpTextCoordinate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Objects.requireNonNull;

/**
 * Library-neutral localization provider example, compiled against the Soklet
 * candidate artifact alone.
 *
 * <p>This is application code, not a Soklet type. It proves that the public
 * localization seam is usable without a translation library and that both
 * documented key strategies are expressible from the published jar.</p>
 */
public final class GenericLocalizationProviderExample
		implements McpLocalizationContextProvider {
	/** Which deterministic key a catalog is authored against. */
	public enum KeyStrategy {
		SOURCE_TEXT,
		CONTEXTUAL_COORDINATE
	}

	private final AtomicReference<Map<Locale, Map<String, String>>> catalogs;
	private final KeyStrategy keyStrategy;
	private final Locale fallbackLocale;

	public GenericLocalizationProviderExample(
			Map<Locale, Map<String, String>> catalogs,
			KeyStrategy keyStrategy, Locale fallbackLocale) {
		this.catalogs = new AtomicReference<>(
				immutableCatalogs(catalogs));
		this.keyStrategy = requireNonNull(keyStrategy, "keyStrategy");
		this.fallbackLocale = requireNonNull(fallbackLocale, "fallbackLocale");
	}

	/**
	 * Atomically installs a validated replacement catalog. The application
	 * calls {@code McpServer.getLocalizationControl().catalogsChanged()} on
	 * every applicable instance afterward.
	 */
	public void installCatalogs(Map<Locale, Map<String, String>> replacement) {
		this.catalogs.set(immutableCatalogs(replacement));
	}

	private static Map<Locale, Map<String, String>> immutableCatalogs(
			Map<Locale, Map<String, String>> catalogs) {
		Map<Locale, Map<String, String>> copied = new LinkedHashMap<>();
		for (Map.Entry<Locale, Map<String, String>> entry
				: requireNonNull(catalogs, "catalogs").entrySet())
			copied.put(requireNonNull(entry.getKey(), "catalog locale"),
					Map.copyOf(requireNonNull(entry.getValue(), "locale catalog")));
		return Map.copyOf(copied);
	}

	@Override
	public McpLocalizationContext createContext(McpLocalizationRequest request) {
		// One atomic read keeps every localized field in a response on the same
		// immutable application catalog snapshot.
		Map<Locale, Map<String, String>> snapshot = this.catalogs.get();
		Locale selected = request.getContinuationLocale()
				.orElseGet(() -> select(request.getLanguageRanges(), snapshot));
		Map<String, String> catalog = snapshot.getOrDefault(selected, Map.of());

		return new McpLocalizationContext() {
			@Override
			public Locale getLocale() {
				return selected;
			}

			@Override
			public McpLocalizationResult localize(McpLocalizableText text) {
				String translated = catalog.get(key(text));
				return translated == null
						? McpLocalizationResult.useDefaultText()
						: McpLocalizationResult.localized(translated);
			}
		};
	}

	private String key(McpLocalizableText text) {
		if (this.keyStrategy == KeyStrategy.SOURCE_TEXT)
			return text.getDefaultText();

		McpTextCoordinate coordinate = text.getCoordinate();
		return coordinate.toExternalKey();
	}

	private Locale select(List<Locale.LanguageRange> ranges,
			Map<Locale, Map<String, String>> snapshot) {
		Locale matched = Locale.lookup(ranges, snapshot.keySet().stream()
				.sorted(java.util.Comparator.comparing(Locale::toLanguageTag))
				.toList());
		return matched == null ? this.fallbackLocale : matched;
	}

	/** Compile-and-run smoke against only the candidate jar. */
	public static void main(String[] arguments) {
		Locale frenchCanadian = Locale.forLanguageTag("fr-CA");
		Map<String, String> initialFrench = new LinkedHashMap<>();
		initialFrench.put("Search", "Rechercher");
		Map<Locale, Map<String, String>> initialCatalogs = new LinkedHashMap<>();
		initialCatalogs.put(frenchCanadian, initialFrench);
		GenericLocalizationProviderExample provider =
				new GenericLocalizationProviderExample(
						initialCatalogs,
						KeyStrategy.SOURCE_TEXT, Locale.ENGLISH);
		McpLocalizer localizer = McpLocalizer.withFallbackLocale(Locale.ENGLISH)
				.contextProvider(provider)
				.build();

		if (!Locale.ENGLISH.equals(localizer.getFallbackLocale()))
			throw new AssertionError("Unexpected fallback locale.");

		McpLocalizableText search = searchText();
		McpLocalizationRequest request = frenchCanadianRequest(frenchCanadian);
		initialFrench.put("Search", "Mutated initial text");
		initialCatalogs.clear();
		assertLocalizedText(provider.createContext(request).localize(search),
				"Rechercher");

		Map<String, String> installedFrench = new LinkedHashMap<>();
		installedFrench.put("Search", "Chercher");
		Map<Locale, Map<String, String>> installedCatalogs = new LinkedHashMap<>();
		installedCatalogs.put(frenchCanadian, installedFrench);
		provider.installCatalogs(installedCatalogs);
		installedFrench.put("Search", "Mutated installed text");
		installedCatalogs.clear();
		assertLocalizedText(provider.createContext(request).localize(search),
				"Chercher");

		System.out.println("Generic localization provider example is usable "
				+ "against the candidate artifact.");
	}

	private static McpLocalizableText searchText() {
		McpEndpoint endpoint = McpEndpoint.withPath("/localization-verification")
				.serverInformation(McpImplementation.withNameAndVersion(
						"localization-verification", "1.0")
						.title("Search")
						.build())
				.build();
		List<McpLocalizableText> texts = McpLocalizationCatalog
				.fromEndpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.getTexts();
		if (texts.size() != 1)
			throw new AssertionError("Unexpected extracted localization catalog.");
		return texts.get(0);
	}

	private static McpLocalizationRequest frenchCanadianRequest(Locale locale) {
		return new McpLocalizationRequest() {
			@Override
			public McpRequestContext getRequestContext() {
				throw new AssertionError("The generic smoke does not need request metadata.");
			}

			@Override
			public List<Locale.LanguageRange> getLanguageRanges() {
				return Locale.LanguageRange.parse(locale.toLanguageTag());
			}

			@Override
			public Optional<Locale> getContinuationLocale() {
				return Optional.empty();
			}

			@Override
			public Optional<String> getResourceListCursor() {
				return Optional.empty();
			}

			@Override
			public Locale getFallbackLocale() {
				return Locale.ENGLISH;
			}
		};
	}

	private static void assertLocalizedText(McpLocalizationResult result,
			String expected) {
		if (!(result instanceof McpLocalizationResult.Localized localized)
				|| !expected.equals(localized.text()))
			throw new AssertionError("Caller mutation changed a published catalog.");
	}
}
