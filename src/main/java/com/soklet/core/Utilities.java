/*
 * Copyright 2022-2023 Revetware LLC.
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

package com.soklet.core;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.lang.Thread.UncaughtExceptionHandler;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.net.HttpCookie;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Locale.LanguageRange;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class Utilities {
	@Nonnull
	private static final boolean VIRTUAL_THREADS_AVAILABLE;
	@Nonnull
	private static final byte[] EMPTY_BYTE_ARRAY;
	@Nonnull
	private static final Map<String, Locale> LOCALES_BY_LANGUAGE_RANGE_RANGE;
	@Nonnull
	private static final Pattern HEAD_WHITESPACE_PATTERN;
	@Nonnull
	private static final Pattern TAIL_WHITESPACE_PATTERN;

	static {
		EMPTY_BYTE_ARRAY = new byte[0];

		Locale[] locales = Locale.getAvailableLocales();
		Map<String, Locale> localesByLanguageRangeRange = new HashMap<>(locales.length);

		for (Locale locale : locales) {
			LanguageRange languageRange = new LanguageRange(locale.toLanguageTag());
			localesByLanguageRangeRange.put(languageRange.getRange(), locale);
		}

		LOCALES_BY_LANGUAGE_RANGE_RANGE = Collections.unmodifiableMap(localesByLanguageRangeRange);

		boolean virtualThreadsAvailable = false;

		try {
			// Detect if Virtual Threads are usable by feature testing via reflection.
			// Hat tip to https://github.com/javalin/javalin for this technique
			Class.forName("java.lang.Thread$Builder$OfVirtual");
			virtualThreadsAvailable = true;
		} catch (Exception ignored) {
			// We don't care why this failed, but if we're here we know JVM does not support virtual threads
		}

		VIRTUAL_THREADS_AVAILABLE = virtualThreadsAvailable;

		// See https://www.regular-expressions.info/unicode.html
		// \p{Z} or \p{Separator}: any kind of whitespace or invisible separator.
		//
		// First pattern matches all whitespace at the head of a string, second matches the same for tail.
		// Useful for a "stronger" trim() function, which is almost always what we want in a web context
		// with user-supplied input.
		HEAD_WHITESPACE_PATTERN = Pattern.compile("^(\\p{Z})+");
		TAIL_WHITESPACE_PATTERN = Pattern.compile("(\\p{Z})+$");
	}

	private Utilities() {
		// Non-instantiable
	}

	@Nonnull
	public static Boolean virtualThreadsAvailable() {
		return VIRTUAL_THREADS_AVAILABLE;
	}

	/**
	 * Method handle-based invocation to provide a Java 19+ virtual-thread-per-task executor service.
	 * <p>
	 * In order to support Soklet users who are not yet ready to enable virtual threads (Java 19+ w/preview features),
	 * we compile Soklet with a source level &lt; 19 and avoid any hard references to virtual threads by dynamically creating
	 * our executor service via method handles.
	 * <p>
	 * You should not call this method if {@link Utilities#virtualThreadsAvailable()} is {@code false}.
	 *
	 * <pre>
	 * {@code
	 *   // This method is equivalent to this code
	 *   Executors.newThreadPerTaskExecutor(Thread.ofVirtual()
	 *    .name(threadNamePrefix)
	 * 		.uncaughtExceptionHandler(uncaughtExceptionHandler)
	 * 		.factory());
	 * }
	 * </pre>
	 *
	 * @param threadNamePrefix         thread name prefix for the virtual thread factory builder
	 * @param uncaughtExceptionHandler uncaught exception handler for the virtual thread factory builder
	 * @return a Java 19+ virtual-thread-per-task executor service
	 * @throws IllegalStateException if the runtime environment does not support virtual threads
	 */
	@Nonnull
	public static ExecutorService createVirtualThreadsNewThreadPerTaskExecutor(@Nonnull String threadNamePrefix,
																																						 @Nonnull UncaughtExceptionHandler uncaughtExceptionHandler) {
		requireNonNull(threadNamePrefix);
		requireNonNull(uncaughtExceptionHandler);

		if (!virtualThreadsAvailable())
			throw new IllegalStateException("Virtual threads are not available. Please confirm you are using Java 19+ with '--enable-preview' javac parameter specified");

		// Hat tip to https://github.com/javalin/javalin for this technique
		Class<?> threadBuilderOfVirtualClass;

		try {
			threadBuilderOfVirtualClass = Class.forName("java.lang.Thread$Builder$OfVirtual");
		} catch (ClassNotFoundException e) {
			throw new IllegalStateException("Unable to load virtual thread builder class", e);
		}

		Lookup lookup = MethodHandles.publicLookup();

		MethodHandle methodHandleThreadOfVirtual;
		MethodHandle methodHandleThreadBuilderOfVirtualName;
		MethodHandle methodHandleThreadBuilderOfVirtualUncaughtExceptionHandler;
		MethodHandle methodHandleThreadBuilderOfVirtualFactory;
		MethodHandle methodHandleExecutorsNewThreadPerTaskExecutor;

		try {
			methodHandleThreadOfVirtual = lookup.findStatic(Thread.class, "ofVirtual", MethodType.methodType(threadBuilderOfVirtualClass));
			methodHandleThreadBuilderOfVirtualName = lookup.findVirtual(threadBuilderOfVirtualClass, "name", MethodType.methodType(threadBuilderOfVirtualClass, String.class, long.class));
			methodHandleThreadBuilderOfVirtualUncaughtExceptionHandler = lookup.findVirtual(threadBuilderOfVirtualClass, "uncaughtExceptionHandler", MethodType.methodType(threadBuilderOfVirtualClass, UncaughtExceptionHandler.class));
			methodHandleThreadBuilderOfVirtualFactory = lookup.findVirtual(threadBuilderOfVirtualClass, "factory", MethodType.methodType(ThreadFactory.class));
			methodHandleExecutorsNewThreadPerTaskExecutor = lookup.findStatic(Executors.class, "newThreadPerTaskExecutor", MethodType.methodType(ExecutorService.class, ThreadFactory.class));
		} catch (NoSuchMethodException | IllegalAccessException e) {
			throw new IllegalStateException("Unable to load method handle for virtual thread factory", e);
		}

		try {
			// Thread.ofVirtual()
			Object virtualThreadBuilder = methodHandleThreadOfVirtual.invoke();
			// .name(threadNamePrefix, start)
			methodHandleThreadBuilderOfVirtualName.invoke(virtualThreadBuilder, threadNamePrefix, 1);
			// .uncaughtExceptionHandler(uncaughtExceptionHandler)
			methodHandleThreadBuilderOfVirtualUncaughtExceptionHandler.invoke(virtualThreadBuilder, uncaughtExceptionHandler);
			// .factory();
			ThreadFactory threadFactory = (ThreadFactory) methodHandleThreadBuilderOfVirtualFactory.invoke(virtualThreadBuilder);

			// return Executors.newThreadPerTaskExecutor(threadFactory);
			return (ExecutorService) methodHandleExecutorsNewThreadPerTaskExecutor.invoke(threadFactory);
		} catch (Throwable t) {
			throw new IllegalStateException("Unable to create virtual thread executor service", t);
		}
	}

	@Nonnull
	public static byte[] emptyByteArray() {
		return EMPTY_BYTE_ARRAY;
	}

	@Nonnull
	public static Map<String, Set<String>> extractQueryParametersFromQuery(@Nonnull String query) {
		requireNonNull(query);

		// For form parameters, body will look like "One=Two&Three=Four" ...a query string.
		String syntheticUrl = format("https://www.soklet.com?%s", query);
		return extractQueryParametersFromUrl(syntheticUrl);
	}

	@Nonnull
	public static Map<String, Set<String>> extractQueryParametersFromUrl(@Nonnull String url) {
		requireNonNull(url);

		URI uri;

		try {
			uri = new URI(url);
		} catch (URISyntaxException e) {
			return Map.of();
		}

		String query = trimAggressivelyToNull(uri.getQuery());

		if (query == null)
			return Map.of();

		String[] queryParameterComponents = query.split("&");
		Map<String, Set<String>> queryParameters = new HashMap<>();

		for (String queryParameterComponent : queryParameterComponents) {
			String[] queryParameterNameAndValue = queryParameterComponent.split("=");
			String name = queryParameterNameAndValue.length > 0 ? trimAggressivelyToNull(queryParameterNameAndValue[0]) : null;

			if (name == null)
				continue;

			String value = queryParameterNameAndValue.length > 1 ? trimAggressivelyToNull(queryParameterNameAndValue[1]) : null;

			if (value == null)
				continue;

			Set<String> values = queryParameters.computeIfAbsent(name, k -> new HashSet<>());

			values.add(value);
		}

		return queryParameters;
	}

	@Nonnull
	public static Map<String, Set<String>> extractCookiesFromHeaders(@Nonnull Map<String, Set<String>> headers) {
		requireNonNull(headers);

		// ResponseCookie names are case-sensitive *in practice*, do not need case-insensitive map
		Map<String, Set<String>> cookies = new HashMap<>();

		for (Map.Entry<String, Set<String>> entry : headers.entrySet()) {
			if (entry.getKey().equals("ResponseCookie")) {
				Set<String> values = entry.getValue();

				for (String value : values) {
					// Note: while this parser handles Set-ResponseCookie (response) headers,
					// because ResponseCookie (request) header is a subset of those, it will work for our purposes.
					List<HttpCookie> httpCookies = HttpCookie.parse(value);

					for (HttpCookie httpCookie : httpCookies) {
						Set<String> cookieValues = cookies.get(httpCookie.getName());

						if (cookieValues == null) {
							cookieValues = new HashSet<>();
							cookies.put(httpCookie.getName(), cookieValues);
						}

						cookieValues.add(httpCookie.getValue());
					}
				}
			}
		}

		return cookies;
	}

	@Nonnull
	public static String normalizedPathForUrl(@Nonnull String url) {
		requireNonNull(url);

		url = trimAggressively(url);

		if (url.length() == 0)
			return "/";

		if (url.startsWith("http://") || url.startsWith("https://")) {
			try {
				URL absoluteUrl = new URL(url);
				url = absoluteUrl.getPath();
			} catch (MalformedURLException e) {
				throw new RuntimeException(format("Malformed URL: %s", url), e);
			}
		}

		if (!url.startsWith("/"))
			url = format("/%s", url);

		if ("/".equals(url))
			return url;

		while (url.endsWith("/"))
			url = url.substring(0, url.length() - 1);

		int queryIndex = url.indexOf("?");

		if (queryIndex != -1)
			url = url.substring(0, queryIndex);

		return url;
	}

	@Nonnull
	public static List<Locale> localesFromAcceptLanguageHeaderValue(@Nonnull String acceptLanguageHeaderValue) {
		requireNonNull(acceptLanguageHeaderValue);

		try {
			List<LanguageRange> languageRanges = LanguageRange.parse(acceptLanguageHeaderValue);

			return languageRanges.stream()
					.map(languageRange -> LOCALES_BY_LANGUAGE_RANGE_RANGE.get(languageRange.getRange()))
					.filter(locale -> locale != null)
					.collect(Collectors.toList());
		} catch (Exception ignored) {
			return List.of();
		}
	}

	/**
	 * A "stronger" version of {@link String#trim()} which discards any kind of whitespace or invisible separator.
	 * <p>
	 * In a web environment with user-supplied inputs, this is the behavior we want the vast majority of the time.
	 * For example, users copy-paste URLs from Word or Outlook and it's easy to accidentally include a U+202F
	 * "Narrow No-Break Space (NNBSP)" character at the end, which might break parsing.
	 * <p>
	 * See <a href="https://www.compart.com/en/unicode/U+202F">https://www.compart.com/en/unicode/U+202F</a> for details.
	 *
	 * @param string (nullable) the string to trim
	 * @return (nullable) the trimmed string
	 */
	@Nullable
	public static String trimAggressively(@Nullable String string) {
		if (string == null)
			return null;

		string = HEAD_WHITESPACE_PATTERN.matcher(string).replaceAll("");

		if (string.length() == 0)
			return string;

		string = TAIL_WHITESPACE_PATTERN.matcher(string).replaceAll("");

		return string;
	}

	@Nullable
	public static String trimAggressivelyToNull(@Nullable String string) {
		if (string == null)
			return null;

		string = trimAggressively(string);
		return string.length() == 0 ? null : string;
	}

	@Nonnull
	public static String trimAggressivelyToEmpty(@Nullable String string) {
		if (string == null)
			return "";

		return trimAggressively(string);
	}
}
