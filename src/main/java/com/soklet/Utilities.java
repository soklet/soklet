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

import com.soklet.exception.IllegalRequestException;
import com.soklet.internal.spring.LinkedCaseInsensitiveMap;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.ThreadSafe;
import java.io.ByteArrayOutputStream;
import java.lang.Thread.UncaughtExceptionHandler;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Locale.LanguageRange;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * A non-instantiable collection of utility methods.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class Utilities {
	@NonNull
	private static final boolean VIRTUAL_THREADS_AVAILABLE;
	@NonNull
	private static final byte[] EMPTY_BYTE_ARRAY;
	@NonNull
	private static final Map<@NonNull String, @NonNull Locale> LOCALES_BY_LANGUAGE_RANGE_RANGE;
	@NonNull
	private static final Pattern HEAD_WHITESPACE_PATTERN;
	@NonNull
	private static final Pattern TAIL_WHITESPACE_PATTERN;
	@NonNull
	private static final Pattern HEADER_PERCENT_ENCODING_PATTERN;

	static {
		EMPTY_BYTE_ARRAY = new byte[0];

		Locale[] locales = Locale.getAvailableLocales();
		Map<String, Locale> localesByLanguageRangeRange = new LinkedHashMap<>(locales.length);

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

		HEADER_PERCENT_ENCODING_PATTERN = Pattern.compile("%([0-9A-Fa-f]{2})");
	}

	private Utilities() {
		// Non-instantiable
	}

	/**
	 * Does the platform runtime support virtual threads (either Java 19 and 20 w/preview enabled or Java 21+)?
	 *
	 * @return {@code true} if the runtime supports virtual threads, {@code false} otherwise
	 */
	@NonNull
	static Boolean virtualThreadsAvailable() {
		return VIRTUAL_THREADS_AVAILABLE;
	}

	/**
	 * Provides a virtual-thread-per-task executor service if supported by the runtime.
	 * <p>
	 * In order to support Soklet users who are not yet ready to enable virtual threads (those <strong>not</strong> running either Java 19 and 20 w/preview enabled or Java 21+),
	 * we compile Soklet with a source level &lt; 19 and avoid any hard references to virtual threads by dynamically creating our executor service via {@link MethodHandle} references.
	 * <p>
	 * <strong>You should not call this method if {@link Utilities#virtualThreadsAvailable()} is {@code false}.</strong>
	 * <pre>{@code // This method is effectively equivalent to this code
	 * return Executors.newThreadPerTaskExecutor(
	 *   Thread.ofVirtual()
	 *    .name(threadNamePrefix)
	 *    .uncaughtExceptionHandler(uncaughtExceptionHandler)
	 *    .factory()
	 * );}</pre>
	 *
	 * @param threadNamePrefix         thread name prefix for the virtual thread factory builder
	 * @param uncaughtExceptionHandler uncaught exception handler for the virtual thread factory builder
	 * @return a virtual-thread-per-task executor service
	 * @throws IllegalStateException if the runtime environment does not support virtual threads
	 */
	@NonNull
	static ExecutorService createVirtualThreadsNewThreadPerTaskExecutor(@NonNull String threadNamePrefix,
																																			@NonNull UncaughtExceptionHandler uncaughtExceptionHandler) {
		requireNonNull(threadNamePrefix);
		requireNonNull(uncaughtExceptionHandler);

		if (!virtualThreadsAvailable())
			throw new IllegalStateException("Virtual threads are not available. Please confirm you are using Java 19-20 with the '--enable-preview' javac parameter specified or Java 21+");

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

	/**
	 * Returns a shared zero-length {@code byte[]} instance.
	 * <p>
	 * Useful as a sentinel when you need a non-{@code null} byte array but have no content.
	 *
	 * @return a zero-length byte array (never {@code null})
	 */
	@NonNull
	static byte[] emptyByteArray() {
		return EMPTY_BYTE_ARRAY;
	}

	/**
	 * Parses a query string such as {@code "a=1&b=2&c=%20"} into a multimap of names to values.
	 * <p>
	 * Decodes percent-escapes using UTF-8, which is usually what you want (see {@link #extractQueryParametersFromQuery(String, QueryFormat, Charset)} if you need to specify a different charset).
	 * <p>
	 * Pairs missing a name are ignored.
	 * <p>
	 * Multiple occurrences of the same name are collected into a {@link Set} in insertion order (duplicates are de-duplicated).
	 *
	 * @param query       a raw query string such as {@code "a=1&b=2&c=%20"}
	 * @param queryFormat how to decode: {@code application/x-www-form-urlencoded} or "strict" RFC 3986
	 * @return a map of parameter names to their distinct values, preserving first-seen name order; empty if none
	 * @throws IllegalRequestException if the query string contains malformed percent-encoding
	 */
	@NonNull
	public static Map<@NonNull String, @NonNull Set<@NonNull String>> extractQueryParametersFromQuery(@NonNull String query,
																																				 @NonNull QueryFormat queryFormat) {
		requireNonNull(query);
		requireNonNull(queryFormat);

		return extractQueryParametersFromQuery(query, queryFormat, StandardCharsets.UTF_8);
	}

	/**
	 * Parses a query string such as {@code "a=1&b=2&c=%20"} into a multimap of names to values.
	 * <p>
	 * Decodes percent-escapes using the specified charset.
	 * <p>
	 * Pairs missing a name are ignored.
	 * <p>
	 * Multiple occurrences of the same name are collected into a {@link Set} in insertion order (duplicates are de-duplicated).
	 *
	 * @param query       a raw query string such as {@code "a=1&b=2&c=%20"}
	 * @param queryFormat how to decode: {@code application/x-www-form-urlencoded} or "strict" RFC 3986
	 * @param charset     the charset to use when decoding percent-escapes
	 * @return a map of parameter names to their distinct values, preserving first-seen name order; empty if none
	 * @throws IllegalRequestException if the query string contains malformed percent-encoding
	 */
	@NonNull
	public static Map<@NonNull String, @NonNull Set<@NonNull String>> extractQueryParametersFromQuery(@NonNull String query,
																																				 @NonNull QueryFormat queryFormat,
																																				 @NonNull Charset charset) {
		requireNonNull(query);
		requireNonNull(queryFormat);
		requireNonNull(charset);

		// For form parameters, body will look like "One=Two&Three=Four" ...a query string.
		String syntheticUrl = format("https://soklet.invalid?%s", query); // avoid referencing real domain
		return extractQueryParametersFromUrl(syntheticUrl, queryFormat, charset);
	}

	/**
	 * Parses query strings from relative or absolute URLs such as {@code "/example?a=a=1&b=2&c=%20"} or {@code "https://www.soklet.com/example?a=1&b=2&c=%20"} into a multimap of names to values.
	 * <p>
	 * Decodes percent-escapes using UTF-8, which is usually what you want (see {@link #extractQueryParametersFromUrl(String, QueryFormat, Charset)} if you need to specify a different charset).
	 * <p>
	 * Pairs missing a name are ignored.
	 * <p>
	 * Multiple occurrences of the same name are collected into a {@link Set} in insertion order (duplicates are de-duplicated).
	 *
	 * @param url         a relative or absolute URL/URI string
	 * @param queryFormat how to decode: {@code application/x-www-form-urlencoded} or "strict" RFC 3986
	 * @return a map of parameter names to their distinct values, preserving first-seen name order; empty if none
	 * @throws IllegalRequestException if the URL or query contains malformed percent-encoding
	 */
	@NonNull
	public static Map<@NonNull String, @NonNull Set<@NonNull String>> extractQueryParametersFromUrl(@NonNull String url,
																																			 @NonNull QueryFormat queryFormat) {
		requireNonNull(url);
		requireNonNull(queryFormat);

		return extractQueryParametersFromUrl(url, queryFormat, StandardCharsets.UTF_8);
	}

	/**
	 * Parses query strings from relative or absolute URLs such as {@code "/example?a=a=1&b=2&c=%20"} or {@code "https://www.soklet.com/example?a=1&b=2&c=%20"} into a multimap of names to values.
	 * <p>
	 * Decodes percent-escapes using the specified charset.
	 * <p>
	 * Pairs missing a name are ignored.
	 * <p>
	 * Multiple occurrences of the same name are collected into a {@link Set} in insertion order (duplicates are de-duplicated).
	 *
	 * @param url         a relative or absolute URL/URI string
	 * @param queryFormat how to decode: {@code application/x-www-form-urlencoded} or "strict" RFC 3986
	 * @param charset     the charset to use when decoding percent-escapes
	 * @return a map of parameter names to their distinct values, preserving first-seen name order; empty if none
	 * @throws IllegalRequestException if the URL or query contains malformed percent-encoding
	 */
	@NonNull
	public static Map<@NonNull String, @NonNull Set<@NonNull String>> extractQueryParametersFromUrl(@NonNull String url,
																																			 @NonNull QueryFormat queryFormat,
																																			 @NonNull Charset charset) {
		requireNonNull(url);
		requireNonNull(queryFormat);
		requireNonNull(charset);

		URI uri;

		try {
			uri = new URI(url);
		} catch (URISyntaxException e) {
			throw new IllegalRequestException(format("Invalid URL '%s'", url), e);
		}

		String query = trimAggressivelyToNull(uri.getRawQuery());

		if (query == null)
			return Map.of();

		Map<String, Set<String>> queryParameters = new LinkedHashMap<>();
		for (String pair : query.split("&")) {
			if (pair.isEmpty())
				continue;

			String[] nv = pair.split("=", 2);
			String rawName = trimAggressivelyToNull(nv.length > 0 ? nv[0] : null);
			String rawValue = trimAggressivelyToNull(nv.length > 1 ? nv[1] : null);

			if (rawName == null)
				continue;

			// Preserve empty values; it's what users probably expect
			if (rawValue == null)
				rawValue = "";

			String name = decodeQueryComponent(rawName, queryFormat, charset);
			String value = decodeQueryComponent(rawValue, queryFormat, charset);

			queryParameters.computeIfAbsent(name, k -> new LinkedHashSet<>()).add(value);
		}

		return queryParameters;
	}

	/**
	 * Decodes a single key or value using the given mode and charset.
	 */
	@NonNull
	private static String decodeQueryComponent(@NonNull String string,
																						 @NonNull QueryFormat queryFormat,
																						 @NonNull Charset charset) {
		requireNonNull(string);
		requireNonNull(queryFormat);
		requireNonNull(charset);

		if (string.isEmpty())
			return "";

		// Step 1: in form mode, '+' means space
		String prepped = (queryFormat == QueryFormat.X_WWW_FORM_URLENCODED) ? string.replace('+', ' ') : string;
		// Step 2: percent-decode bytes, then interpret bytes with the provided charset
		return percentDecode(prepped, charset);
	}

	/**
	 * Percent-decodes a string into bytes, then constructs a String using the provided charset.
	 * One pass only: invalid %xy sequences trigger an exception.
	 */
	@NonNull
	private static String percentDecode(@NonNull String s, @NonNull Charset charset) {
		requireNonNull(s);
		requireNonNull(charset);

		if (s.isEmpty())
			return "";

		StringBuilder sb = new StringBuilder(s.length());
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();

		for (int i = 0; i < s.length(); ) {
			char c = s.charAt(i);

			if (c == '%') {
				// Consume one or more consecutive %xx triplets into bytes
				bytes.reset();
				int j = i;

				while (j < s.length() && s.charAt(j) == '%') {
					if (j + 2 >= s.length())
						throw new IllegalRequestException("Invalid percent-encoding in URL component");

					int hi = hex(s.charAt(j + 1));
					int lo = hex(s.charAt(j + 2));
					if (hi < 0 || lo < 0)
						throw new IllegalRequestException("Invalid percent-encoding in URL component");

					bytes.write((hi << 4) | lo);
					j += 3;
				}

				sb.append(new String(bytes.toByteArray(), charset));
				i = j;
				continue;
			}

			// Non-'%' char: append it as-is.
			// This preserves surrogate pairs naturally as the loop hits both chars.
			sb.append(c);
			i++;
		}

		return sb.toString();
	}

	private static int hex(char c) {
		if (c >= '0' && c <= '9') return c - '0';
		if (c >= 'A' && c <= 'F') return c - 'A' + 10;
		if (c >= 'a' && c <= 'f') return c - 'a' + 10;
		return -1;
	}

	/**
	 * Parses {@code Cookie} request headers into a map of cookie names to values.
	 * <p>
	 * Header name matching is case-insensitive ({@code "Cookie"} vs {@code "cookie"}), but <em>cookie names are case-sensitive</em>.
	 * Values are parsed per the following liberal rules:
	 * <ul>
	 *   <li>Components are split on {@code ';'} unless inside a quoted string.</li>
	 *   <li>Quoted values have surrounding quotes removed and common backslash escapes unescaped.</li>
	 *   <li>Percent-escapes are decoded as UTF-8. {@code '+'} is <strong>not</strong> treated specially.</li>
	 * </ul>
	 * Multiple occurrences of the same cookie name are collected into a {@link Set} in insertion order.
	 *
	 * @param headers request headers as a multimap of header name to values (must be non-{@code null})
	 * @return a map of cookie name to distinct values; empty if no valid cookies are present
	 */
	@NonNull
	public static Map<@NonNull String, @NonNull Set<@NonNull String>> extractCookiesFromHeaders(@NonNull Map<@NonNull String, @NonNull Set<@NonNull String>> headers) {
		requireNonNull(headers);

		// Cookie *names* must be case-sensitive; keep LinkedHashMap (NOT case-insensitive)
		Map<String, Set<String>> cookies = new LinkedHashMap<>();

		for (Entry<String, Set<String>> entry : headers.entrySet()) {
			String headerName = entry.getKey();
			if (headerName == null || !"cookie".equalsIgnoreCase(headerName.trim()))
				continue;

			Set<String> values = entry.getValue();
			if (values == null) continue;

			for (String headerValue : values) {
				headerValue = trimAggressivelyToNull(headerValue);
				if (headerValue == null) continue;

				// Split on ';' only when NOT inside a quoted string
				List<String> cookieComponents = splitCookieHeaderRespectingQuotes(headerValue);

				for (String cookieComponent : cookieComponents) {
					cookieComponent = trimAggressivelyToNull(cookieComponent);
					if (cookieComponent == null) continue;

					String[] cookiePair = cookieComponent.split("=", 2);
					String rawName = trimAggressivelyToNull(cookiePair[0]);
					String rawValue = (cookiePair.length == 2 ? trimAggressivelyToNull(cookiePair[1]) : null);

					if (rawName == null) continue;

					// DO NOT decode the name; cookie names are case-sensitive and rarely encoded
					String cookieName = rawName;

					String cookieValue = null;
					if (rawValue != null) {
						// If it's quoted, unquote+unescape first, then percent-decode (still no '+' -> space)
						String unquoted = unquoteCookieValueIfNeeded(rawValue);
						cookieValue = percentDecodeCookieValue(unquoted);
					}

					cookies.computeIfAbsent(cookieName, key -> new LinkedHashSet<>());
					if (cookieValue != null)
						cookies.get(cookieName).add(cookieValue);
				}
			}
		}

		return cookies;
	}

	/**
	 * Percent-decodes %HH to bytes->UTF-8. Does NOT treat '+' specially.
	 */
	@NonNull
	private static String percentDecodeCookieValue(@NonNull String cookieValue) {
		requireNonNull(cookieValue);

		ByteArrayOutputStream out = new ByteArrayOutputStream(cookieValue.length());

		for (int i = 0; i < cookieValue.length(); ) {
			char c = cookieValue.charAt(i);
			if (c == '%' && i + 2 < cookieValue.length()) {
				int hi = Character.digit(cookieValue.charAt(i + 1), 16);
				int lo = Character.digit(cookieValue.charAt(i + 2), 16);
				if (hi >= 0 && lo >= 0) {
					out.write((hi << 4) + lo);
					i += 3;
					continue;
				}
			}

			out.write((byte) c);
			i++;
		}

		return out.toString(StandardCharsets.UTF_8);
	}

	/**
	 * Splits a Cookie header string into components on ';' but ONLY when not inside a quoted value.
	 * Supports backslash-escaped quotes within quoted strings.
	 */
	private static List<@NonNull String> splitCookieHeaderRespectingQuotes(@NonNull String headerValue) {
		List<String> parts = new ArrayList<>();
		StringBuilder cur = new StringBuilder(headerValue.length());
		boolean inQuotes = false;
		boolean escape = false;

		for (int i = 0; i < headerValue.length(); i++) {
			char c = headerValue.charAt(i);

			if (escape) {
				// keep escaped char literally (e.g., \" \; \\)
				cur.append(c);
				escape = false;
				continue;
			}

			if (c == '\\') {
				escape = true;
				// keep the backslash for now; unquote step will handle unescaping
				cur.append(c);
				continue;
			}

			if (c == '"') {
				inQuotes = !inQuotes;
				cur.append(c);
				continue;
			}

			if (c == ';' && !inQuotes) {
				parts.add(cur.toString());
				cur.setLength(0);
				continue;
			}

			cur.append(c);
		}

		if (cur.length() > 0)
			parts.add(cur.toString());

		return parts;
	}

	/**
	 * If the cookie value is a quoted-string, remove surrounding quotes and unescape \" \\ and \; .
	 * Otherwise returns the input as-is.
	 */
	@NonNull
	private static String unquoteCookieValueIfNeeded(@NonNull String rawValue) {
		requireNonNull(rawValue);

		if (rawValue.length() >= 2 && rawValue.charAt(0) == '"' && rawValue.charAt(rawValue.length() - 1) == '"') {
			// Strip the surrounding quotes
			String inner = rawValue.substring(1, rawValue.length() - 1);

			// Unescape \" \\ and \; (common patterns seen in the wild)
			// Order matters: unescape backslash-escape sequences, then leave other chars intact.
			StringBuilder sb = new StringBuilder(inner.length());
			boolean escape = false;

			for (int i = 0; i < inner.length(); i++) {
				char c = inner.charAt(i);
				if (escape) {
					// Only special-case a few common escapes; otherwise keep the char
					if (c == '"' || c == '\\' || c == ';')
						sb.append(c);
					else
						sb.append(c); // unknown escape -> keep literally (liberal in what we accept)

					escape = false;
				} else if (c == '\\') {
					escape = true;
				} else {
					sb.append(c);
				}
			}

			// If string ended with a dangling backslash, keep it literally
			if (escape)
				sb.append('\\');

			return sb.toString();
		}

		return rawValue;
	}

	/**
	 * Normalizes a URL or path into a canonical request path and optionally performs percent-decoding on the path.
	 * <p>
	 * For example, {@code "https://www.soklet.com/ab%20c?one=two"} would be normalized to {@code "/ab c"}.
	 * <p>
	 * The {@code OPTIONS *} special case returns {@code "*"}.
	 * <p>
	 * Behavior:
	 * <ul>
	 *   <li>If input starts with {@code http://} or {@code https://}, the path portion is extracted.</li>
	 *   <li>Ensures the result begins with {@code '/'}.</li>
	 *   <li>Removes any trailing {@code '/'} (except for the root path {@code '/'}).</li>
	 *   <li>Safely normalizes path traversals, e.g. path {@code '/a/../b'} would be normalized to {@code '/b'}</li>
	 *   <li>Strips any query string.</li>
	 *   <li>Applies aggressive trimming of Unicode whitespace.</li>
	 *   <li>Rejects malformed percent-encoding when decoding is enabled.</li>
	 * </ul>
	 *
	 * @param url             a URL or path to normalize
	 * @param performDecoding {@code true} if decoding should be performed on the path (e.g. replace {@code %20} with a space character), {@code false} otherwise
	 * @return the normalized path, {@code "/"} for empty input
	 */
	@NonNull
	public static String extractPathFromUrl(@NonNull String url,
																					@NonNull Boolean performDecoding) {
		requireNonNull(url);

		url = trimAggressivelyToEmpty(url);

		// Special case for OPTIONS * requests
		if (url.equals("*"))
			return "*";

		// Parse with java.net.URI to isolate raw path; then percent-decode only the path
		try {
			URI uri = new URI(url);

			String rawPath = uri.getRawPath(); // null => "/"

			if (rawPath == null || rawPath.isEmpty())
				rawPath = "/";

			String decodedPath = performDecoding ? percentDecode(rawPath, StandardCharsets.UTF_8) : rawPath;

			// Sanitize path traversal (e.g. /a/../b -> /b)
			decodedPath = removeDotSegments(decodedPath);

			// Normalize trailing slashes like normalizedPathForUrl currently does
			if (!decodedPath.startsWith("/"))
				decodedPath = "/" + decodedPath;

			if (!"/".equals(decodedPath))
				while (decodedPath.endsWith("/"))
					decodedPath = decodedPath.substring(0, decodedPath.length() - 1);

			return decodedPath;
		} catch (URISyntaxException e) {
			// If it's not an absolute URL, treat the whole string as a path and percent-decode
			String path = url;
			int q = path.indexOf('?');

			if (q != -1)
				path = path.substring(0, q);

			String decodedPath = performDecoding ? percentDecode(path, StandardCharsets.UTF_8) : path;

			// Sanitize path traversal (e.g. /a/../b -> /b)
			decodedPath = removeDotSegments(decodedPath);

			if (!decodedPath.startsWith("/"))
				decodedPath = "/" + decodedPath;

			if (!"/".equals(decodedPath))
				while (decodedPath.endsWith("/"))
					decodedPath = decodedPath.substring(0, decodedPath.length() - 1);

			return decodedPath;
		}
	}

	/**
	 * Extracts the raw (un-decoded) query component from a URL.
	 * <p>
	 * For example, {@code "/path?a=b&c=d%20e"} would return {@code "a=b&c=d%20e"}.
	 *
	 * @param url a raw URL or path
	 * @return the raw query component, or {@link Optional#empty()} if none
	 */
	@NonNull
	public static Optional<@NonNull String> extractRawQueryFromUrl(@NonNull String url) {
		requireNonNull(url);

		url = trimAggressivelyToEmpty(url);

		if ("*".equals(url))
			return Optional.empty();

		try {
			URI uri = new URI(url);
			return Optional.ofNullable(trimAggressivelyToNull(uri.getRawQuery()));
		} catch (URISyntaxException e) {
			// Not a valid URI, try to extract query manually
			int q = url.indexOf('?');
			if (q == -1)
				return Optional.empty();

			String query = trimAggressivelyToNull(url.substring(q + 1));
			return Optional.ofNullable(query);
		}
	}

	/**
	 * Encodes decoded query parameters into a raw query string.
	 * <p>
	 * For example, given {@code {a=[b], c=[d e]}} and {@link QueryFormat#RFC_3986_STRICT},
	 * returns {@code "a=b&c=d%20e"}.
	 *
	 * @param queryParameters the decoded query parameters
	 * @param queryFormat     the encoding strategy
	 * @return the encoded query string, or the empty string if no parameters
	 */
	@NonNull
	public static String encodeQueryParameters(@NonNull Map<@NonNull String, @NonNull Set<@NonNull String>> queryParameters,
																						 @NonNull QueryFormat queryFormat) {
		requireNonNull(queryParameters);
		requireNonNull(queryFormat);

		if (queryParameters.isEmpty())
			return "";

		StringBuilder sb = new StringBuilder();
		boolean first = true;

		for (Entry<String, Set<String>> entry : queryParameters.entrySet()) {
			String encodedName = encodeQueryComponent(entry.getKey(), queryFormat);

			for (String value : entry.getValue()) {
				if (!first)
					sb.append('&');

				sb.append(encodedName);
				sb.append('=');
				sb.append(encodeQueryComponent(value, queryFormat));

				first = false;
			}
		}

		return sb.toString();
	}

	@NonNull
	static String encodeQueryComponent(@NonNull String queryComponent,
																		 @NonNull QueryFormat queryFormat) {
		requireNonNull(queryComponent);
		requireNonNull(queryFormat);

		String encoded = URLEncoder.encode(queryComponent, StandardCharsets.UTF_8);

		if (queryFormat == QueryFormat.RFC_3986_STRICT)
			encoded = encoded.replace("+", "%20");

		return encoded;
	}

	@NonNull
	static String encodePath(@NonNull String path) {
		requireNonNull(path);

		if ("*".equals(path))
			return path;

		// Encode each path segment individually, preserving '/' separators.
		// RFC 3986 is used for path encoding (spaces as %20, not +).
		return Arrays.stream(path.split("/", -1))
				.map(segment -> URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"))
				.collect(Collectors.joining("/"));
	}

	/**
	 * Parses an {@code Accept-Language} header value into a best-effort ordered list of {@link Locale}s.
	 * <p>
	 * Quality weights are honored by {@link Locale.LanguageRange#parse(String)}; results are then mapped to available
	 * JVM locales. Unknown or unavailable language ranges are skipped. On parse failure, an empty list is returned.
	 *
	 * @param acceptLanguageHeaderValue the raw header value (must be non-{@code null})
	 * @return locales in descending preference order; empty if none could be resolved
	 */
	@NonNull
	public static List<@NonNull Locale> extractLocalesFromAcceptLanguageHeaderValue(@NonNull String acceptLanguageHeaderValue) {
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
	 * Best-effort attempt to determine a client's URL prefix by examining request headers.
	 * <p>
	 * A URL prefix in this context is defined as {@code <scheme>://host<:optional port>}, but no path or query components.
	 * <p>
	 * Soklet is generally the "last hop" behind a load balancer/reverse proxy and does get accessed directly by clients.
	 * <p>
	 * Normally a load balancer/reverse proxy/other upstream proxies will provide information about the true source of the
	 * request through headers like the following:
	 * <ul>
	 *   <li>{@code Host}</li>
	 *   <li>{@code Forwarded}</li>
	 *   <li>{@code Origin}</li>
	 *   <li>{@code X-Forwarded-Proto}</li>
	 *   <li>{@code X-Forwarded-Protocol}</li>
	 *   <li>{@code X-Url-Scheme}</li>
	 *   <li>{@code Front-End-Https}</li>
	 *   <li>{@code X-Forwarded-Ssl}</li>
	 *   <li>{@code X-Forwarded-Host}</li>
	 *   <li>{@code X-Forwarded-Port}</li>
	 * </ul>
	 * <p>
	 * This method may take these and other headers into account when determining URL prefix.
	 * <p>
	 * For example, the following would be legal URL prefixes returned from this method:
	 * <ul>
	 *   <li>{@code https://www.soklet.com}</li>
	 *   <li>{@code http://www.fake.com:1234}</li>
	 * </ul>
	 * <p>
	 * The following would NOT be legal URL prefixes:
	 * <ul>
	 *   <li>{@code www.soklet.com} (missing protocol) </li>
	 *   <li>{@code https://www.soklet.com/} (trailing slash)</li>
	 *   <li>{@code https://www.soklet.com/test} (trailing slash, path)</li>
	 *   <li>{@code https://www.soklet.com/test?abc=1234} (trailing slash, path, query)</li>
	 * </ul>
	 *
	 * @param headers HTTP request headers
	 * @return the URL prefix, or {@link Optional#empty()} if it could not be determined
	 */
	@NonNull
	public static Optional<@NonNull String> extractClientUrlPrefixFromHeaders(@NonNull Map<@NonNull String, @NonNull Set<@NonNull String>> headers) {
		requireNonNull(headers);

		// Host                   developer.mozilla.org OR developer.mozilla.org:443 OR [2001:db8::1]:8443
		// Forwarded              by=<identifier>;for=<identifier>;host=<host>;proto=<http|https> (can be repeated if comma-separated, e.g. for=12.34.56.78;host=example.com;proto=https, for=23.45.67.89)
		// Origin                 null OR <scheme>://<hostname> OR <scheme>://<hostname>:<port>
		// X-Forwarded-Proto      https
		// X-Forwarded-Protocol   https (Microsoft's alternate name)
		// X-Url-Scheme           https (Microsoft's alternate name)
		// Front-End-Https        on (Microsoft's alternate name)
		// X-Forwarded-Ssl        on (Microsoft's alternate name)
		// X-Forwarded-Host       id42.example-cdn.com
		// X-Forwarded-Port       443

		String protocol = null;
		String host = null;
		String portAsString = null;
		Boolean portExplicit = false;

		// Host: developer.mozilla.org OR developer.mozilla.org:443 OR [2001:db8::1]:8443
		Set<String> hostHeaders = headers.get("Host");

		if (hostHeaders != null && !hostHeaders.isEmpty()) {
			HostPort hostPort = parseHostPort(hostHeaders.iterator().next()).orElse(null);

			if (hostPort != null) {
				host = hostPort.getHost();

				if (hostPort.getPort().isPresent()) {
					portAsString = String.valueOf(hostPort.getPort().get());
					portExplicit = true;
				}
			}
		}

		// Forwarded: by=<identifier>;for=<identifier>;host=<host>;proto=<http|https>
		Set<String> forwardedHeaders = headers.get("Forwarded");
		if (forwardedHeaders != null && forwardedHeaders.size() > 0) {
			String forwardedHeader = trimAggressivelyToNull(forwardedHeaders.stream().findFirst().get());

			// If there are multiple comma-separated components, pick the first one
			String[] forwardedHeaderComponents = forwardedHeader != null ? forwardedHeader.split(",") : new String[0];
			forwardedHeader = forwardedHeaderComponents.length > 0 ? trimAggressivelyToNull(forwardedHeaderComponents[0]) : null;

			if (forwardedHeader != null) {
				// Each field component might look like "by=<identifier>"
				String[] forwardedHeaderFieldComponents = forwardedHeader.split(";");
				for (String forwardedHeaderFieldComponent : forwardedHeaderFieldComponents) {
					forwardedHeaderFieldComponent = trimAggressivelyToNull(forwardedHeaderFieldComponent);
					if (forwardedHeaderFieldComponent == null)
						continue;

					// Break "by=<identifier>" into "by" and "<identifier>" pieces
					String[] forwardedHeaderFieldNameAndValue = forwardedHeaderFieldComponent.split(Pattern.quote("=" /* escape special Regex char */));
					if (forwardedHeaderFieldNameAndValue.length != 2)
						continue;

					String name = trimAggressivelyToNull(forwardedHeaderFieldNameAndValue[0]);
					String value = trimAggressivelyToNull(forwardedHeaderFieldNameAndValue[1]);
					if (name == null || value == null)
						continue;

					if ("host".equalsIgnoreCase(name)) {
						if (host == null) {
							HostPort hostPort = parseHostPort(value).orElse(null);

							if (hostPort != null) {
								host = hostPort.getHost();

								if (hostPort.getPort().isPresent()) {
									portAsString = String.valueOf(hostPort.getPort().get());
									portExplicit = true;
								}
							}
						}
					} else if ("proto".equalsIgnoreCase(name)) {
						if (protocol == null)
							protocol = stripOptionalQuotes(value);
					}
				}
			}
		}

		// Origin: null OR <scheme>://<hostname> OR <scheme>://<hostname>:<port> (IPv6 supported)
		if (protocol == null || host == null || portAsString == null) {
			Set<String> originHeaders = headers.get("Origin");

			if (originHeaders != null && !originHeaders.isEmpty()) {
				String originHeader = trimAggressivelyToNull(originHeaders.iterator().next());
				try {
					URI o = new URI(originHeader);
					String sch = trimAggressivelyToNull(o.getScheme());
					String h = o.getHost(); // may be bracketed already on some JDKs
					int p = o.getPort(); // -1 if absent

					if (sch != null)
						protocol = sch;

					if (h != null) {
						boolean alreadyBracketed = h.startsWith("[") && h.endsWith("]");
						boolean isIpv6Like = h.indexOf(':') >= 0; // contains colon(s)
						host = (isIpv6Like && !alreadyBracketed) ? "[" + h + "]" : h;
					}

					if (p >= 0) {
						portAsString = String.valueOf(p);
						portExplicit = true;
					}
				} catch (URISyntaxException ignored) {
					// no-op
				}
			}
		}

		// X-Forwarded-Proto: https
		if (protocol == null) {
			Set<String> xForwardedProtoHeaders = headers.get("X-Forwarded-Proto");
			if (xForwardedProtoHeaders != null && xForwardedProtoHeaders.size() > 0) {
				String xForwardedProtoHeader = trimAggressivelyToNull(xForwardedProtoHeaders.stream().findFirst().get());
				protocol = xForwardedProtoHeader;
			}
		}

		// X-Forwarded-Protocol: https (Microsoft's alternate name)
		if (protocol == null) {
			Set<String> xForwardedProtocolHeaders = headers.get("X-Forwarded-Protocol");
			if (xForwardedProtocolHeaders != null && xForwardedProtocolHeaders.size() > 0) {
				String xForwardedProtocolHeader = trimAggressivelyToNull(xForwardedProtocolHeaders.stream().findFirst().get());
				protocol = xForwardedProtocolHeader;
			}
		}

		// X-Url-Scheme: https (Microsoft's alternate name)
		if (protocol == null) {
			Set<String> xUrlSchemeHeaders = headers.get("X-Url-Scheme");
			if (xUrlSchemeHeaders != null && xUrlSchemeHeaders.size() > 0) {
				String xUrlSchemeHeader = trimAggressivelyToNull(xUrlSchemeHeaders.stream().findFirst().get());
				protocol = xUrlSchemeHeader;
			}
		}

		// Front-End-Https: on (Microsoft's alternate name)
		if (protocol == null) {
			Set<String> frontEndHttpsHeaders = headers.get("Front-End-Https");
			if (frontEndHttpsHeaders != null && frontEndHttpsHeaders.size() > 0) {
				String frontEndHttpsHeader = trimAggressivelyToNull(frontEndHttpsHeaders.stream().findFirst().get());

				if (frontEndHttpsHeader != null)
					protocol = "on".equalsIgnoreCase(frontEndHttpsHeader) ? "https" : "http";
			}
		}

		// X-Forwarded-Ssl: on (Microsoft's alternate name)
		if (protocol == null) {
			Set<String> xForwardedSslHeaders = headers.get("X-Forwarded-Ssl");
			if (xForwardedSslHeaders != null && xForwardedSslHeaders.size() > 0) {
				String xForwardedSslHeader = trimAggressivelyToNull(xForwardedSslHeaders.stream().findFirst().get());

				if (xForwardedSslHeader != null)
					protocol = "on".equalsIgnoreCase(xForwardedSslHeader) ? "https" : "http";
			}
		}

		// X-Forwarded-Host: id42.example-cdn.com (or with port / IPv6)
		if (host == null) {
			Set<String> xForwardedHostHeaders = headers.get("X-Forwarded-Host");
			if (xForwardedHostHeaders != null && xForwardedHostHeaders.size() > 0) {
				HostPort hostPort = parseHostPort(xForwardedHostHeaders.iterator().next()).orElse(null);

				if (hostPort != null) {
					host = hostPort.getHost();

					if (hostPort.getPort().isPresent() && portAsString == null) {
						portAsString = String.valueOf(hostPort.getPort().get());
						portExplicit = true;
					}
				}
			}
		}

		// X-Forwarded-Port: 443
		if (portAsString == null) {
			Set<String> xForwardedPortHeaders = headers.get("X-Forwarded-Port");
			if (xForwardedPortHeaders != null && xForwardedPortHeaders.size() > 0) {
				String xForwardedPortHeader = trimAggressivelyToNull(xForwardedPortHeaders.stream().findFirst().get());
				portAsString = xForwardedPortHeader;

				if (xForwardedPortHeader != null)
					portExplicit = true;
			}
		}

		Integer port = null;

		if (portAsString != null) {
			try {
				port = Integer.parseInt(portAsString, 10);
			} catch (Exception ignored) {
				// Not an integer; ignore it
			}
		}

		if (protocol != null && host != null && port == null) {
			return Optional.of(format("%s://%s", protocol, host));
		}

		if (protocol != null && host != null && port != null) {
			boolean usingDefaultPort =
					("http".equalsIgnoreCase(protocol) && port.equals(80)) ||
							("https".equalsIgnoreCase(protocol) && port.equals(443));

			// Keep default ports if the client/proxy explicitly sent them
			String clientUrlPrefix = (usingDefaultPort && !portExplicit)
					? format("%s://%s", protocol, host)
					: format("%s://%s:%s", protocol, host, port);

			return Optional.of(clientUrlPrefix);
		}

		return Optional.empty();
	}

	/**
	 * Extracts the media type (without parameters) from the first {@code Content-Type} header.
	 * <p>
	 * For example, {@code "text/html; charset=UTF-8"} → {@code "text/html"}.
	 *
	 * @param headers request/response headers (must be non-{@code null})
	 * @return the media type if present; otherwise {@link Optional#empty()}
	 * @see #extractContentTypeFromHeaderValue(String)
	 */
	@NonNull
	public static Optional<@NonNull String> extractContentTypeFromHeaders(@NonNull Map<@NonNull String, @NonNull Set<@NonNull String>> headers) {
		requireNonNull(headers);

		Set<String> contentTypeHeaderValues = headers.get("Content-Type");

		if (contentTypeHeaderValues == null || contentTypeHeaderValues.size() == 0)
			return Optional.empty();

		return extractContentTypeFromHeaderValue(contentTypeHeaderValues.stream().findFirst().get());
	}

	/**
	 * Extracts the media type (without parameters) from a {@code Content-Type} header value.
	 * <p>
	 * For example, {@code "application/json; charset=UTF-8"} → {@code "application/json"}.
	 *
	 * @param contentTypeHeaderValue the raw header value; may be {@code null} or blank
	 * @return the media type if present; otherwise {@link Optional#empty()}
	 */
	@NonNull
	public static Optional<@NonNull String> extractContentTypeFromHeaderValue(@Nullable String contentTypeHeaderValue) {
		contentTypeHeaderValue = trimAggressivelyToNull(contentTypeHeaderValue);

		if (contentTypeHeaderValue == null)
			return Optional.empty();

		// Examples
		// Content-Type: text/html; charset=UTF-8
		// Content-Type: multipart/form-data; boundary=something

		int indexOfSemicolon = contentTypeHeaderValue.indexOf(";");

		// Simple case, e.g. "text/html"
		if (indexOfSemicolon == -1)
			return Optional.ofNullable(trimAggressivelyToNull(contentTypeHeaderValue));

		// More complex case, e.g. "text/html; charset=UTF-8"
		return Optional.ofNullable(trimAggressivelyToNull(contentTypeHeaderValue.substring(0, indexOfSemicolon)));
	}

	/**
	 * Extracts the {@link Charset} from the first {@code Content-Type} header, if present and valid.
	 * <p>
	 * Tolerates additional parameters and arbitrary whitespace. Invalid or unknown charset tokens yield {@link Optional#empty()}.
	 *
	 * @param headers request/response headers (must be non-{@code null})
	 * @return the charset declared by the header; otherwise {@link Optional#empty()}
	 * @see #extractCharsetFromHeaderValue(String)
	 */
	@NonNull
	public static Optional<@NonNull Charset> extractCharsetFromHeaders(@NonNull Map<@NonNull String, @NonNull Set<@NonNull String>> headers) {
		requireNonNull(headers);

		Set<String> contentTypeHeaderValues = headers.get("Content-Type");

		if (contentTypeHeaderValues == null || contentTypeHeaderValues.size() == 0)
			return Optional.empty();

		return extractCharsetFromHeaderValue(contentTypeHeaderValues.stream().findFirst().get());
	}

	/**
	 * Extracts the {@code charset=...} parameter from a {@code Content-Type} header value.
	 * <p>
	 * Parsing is forgiving: parameters may appear in any order and with arbitrary spacing. If a charset is found,
	 * it is validated via {@link Charset#forName(String)}; invalid names result in {@link Optional#empty()}.
	 *
	 * @param contentTypeHeaderValue the raw header value; may be {@code null} or blank
	 * @return the resolved charset if present and valid; otherwise {@link Optional#empty()}
	 */
	@NonNull
	public static Optional<@NonNull Charset> extractCharsetFromHeaderValue(@Nullable String contentTypeHeaderValue) {
		contentTypeHeaderValue = trimAggressivelyToNull(contentTypeHeaderValue);

		if (contentTypeHeaderValue == null)
			return Optional.empty();

		// Examples
		// Content-Type: text/html; charset=UTF-8
		// Content-Type: multipart/form-data; boundary=something

		int indexOfSemicolon = contentTypeHeaderValue.indexOf(";");

		// Simple case, e.g. "text/html"
		if (indexOfSemicolon == -1)
			return Optional.empty();

		// More complex case, e.g. "text/html; charset=UTF-8" or "multipart/form-data; charset=UTF-8; boundary=something"
		boolean finishedContentType = false;
		boolean finishedCharsetName = false;
		StringBuilder buffer = new StringBuilder();
		String charsetName = null;

		for (int i = 0; i < contentTypeHeaderValue.length(); i++) {
			char c = contentTypeHeaderValue.charAt(i);

			if (Character.isWhitespace(c))
				continue;

			if (c == ';') {
				// No content type yet?  This just be it...
				if (!finishedContentType) {
					finishedContentType = true;
					buffer = new StringBuilder();
				} else if (!finishedCharsetName) {
					if (buffer.indexOf("charset=") == 0) {
						charsetName = buffer.toString();
						finishedCharsetName = true;
						break;
					}
				}
			} else {
				buffer.append(Character.toLowerCase(c));
			}
		}

		// Handle case where charset is the end of the string, e.g. "whatever;charset=UTF-8"
		if (!finishedCharsetName) {
			String potentialCharset = trimAggressivelyToNull(buffer.toString());
			if (potentialCharset != null && potentialCharset.startsWith("charset=")) {
				finishedCharsetName = true;
				charsetName = potentialCharset;
			}
		}

		if (finishedCharsetName) {
			// e.g. charset=UTF-8 or charset="UTF-8" or charset='UTF-8'
			String possibleCharsetName = trimAggressivelyToNull(charsetName.replace("charset=", ""));

			if (possibleCharsetName != null) {
				// strip optional surrounding quotes
				if ((possibleCharsetName.length() >= 2) &&
						((possibleCharsetName.charAt(0) == '"' && possibleCharsetName.charAt(possibleCharsetName.length() - 1) == '"') ||
								(possibleCharsetName.charAt(0) == '\'' && possibleCharsetName.charAt(possibleCharsetName.length() - 1) == '\''))) {
					possibleCharsetName = possibleCharsetName.substring(1, possibleCharsetName.length() - 1);
					possibleCharsetName = trimAggressivelyToNull(possibleCharsetName);
				}

				if (possibleCharsetName != null) {
					try {
						return Optional.of(Charset.forName(possibleCharsetName));
					} catch (IllegalCharsetNameException | UnsupportedCharsetException ignored) {
						return Optional.empty();
					}
				}
			}
		}

		return Optional.empty();
	}

	/**
	 * A "stronger" version of {@link String#trim()} which discards any kind of whitespace or invisible separator.
	 * <p>
	 * In a web environment with user-supplied inputs, this is the behavior we want the vast majority of the time.
	 * For example, users copy-paste URLs from Microsoft Word or Outlook and it's easy to accidentally include a {@code U+202F
	 * "Narrow No-Break Space (NNBSP)"} character at the end, which might break parsing.
	 * <p>
	 * See <a href="https://www.compart.com/en/unicode/U+202F">https://www.compart.com/en/unicode/U+202F</a> for details.
	 *
	 * @param string the string to trim
	 * @return the trimmed string, or {@code null} if the input string is {@code null} or the trimmed representation is of length {@code 0}
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

	/**
	 * Aggressively trims Unicode whitespace from the given string and returns {@code null} if the result is empty.
	 * <p>
	 * See {@link #trimAggressively(String)} for details on which code points are removed.
	 *
	 * @param string the input string; may be {@code null}
	 * @return a trimmed, non-empty string; or {@code null} if input was {@code null} or trimmed to empty
	 */
	@Nullable
	public static String trimAggressivelyToNull(@Nullable String string) {
		if (string == null)
			return null;

		string = trimAggressively(string);
		return string.length() == 0 ? null : string;
	}

	/**
	 * Aggressively trims Unicode whitespace from the given string and returns {@code ""} if the input is {@code null}.
	 * <p>
	 * See {@link #trimAggressively(String)} for details on which code points are removed.
	 *
	 * @param string the input string; may be {@code null}
	 * @return a trimmed string (never {@code null}); {@code ""} if input was {@code null}
	 */
	@NonNull
	public static String trimAggressivelyToEmpty(@Nullable String string) {
		if (string == null)
			return "";

		return trimAggressively(string);
	}

	static void validateHeaderNameAndValue(@Nullable String name,
																				 @Nullable String value) {
		// First, validate name:
		name = trimAggressivelyToNull(name);

		if (name == null)
			throw new IllegalArgumentException("Header name is blank");

		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			// RFC 9110 tchar: "!" / "#" / "$" / "%" / "&" / "'" / "*" / "+" / "-" / "." / "^" / "_" / "`" / "|" / "~" / DIGIT / ALPHA
			if (c > 0x7F || !(c == '!' || c == '#' || c == '$' || c == '%' || c == '&' || c == '\'' || c == '*' || c == '+' ||
					c == '-' || c == '.' || c == '^' || c == '_' || c == '`' || c == '|' || c == '~' ||
					Character.isLetterOrDigit(c))) {
				throw new IllegalArgumentException(format("Illegal header name '%s'. Offending character: '%s'", name, printableChar(c)));
			}
		}

		// Then, validate value:
		if (value == null)
			return;

		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (c == '\r' || c == '\n' || c == 0x00 || c > 0xFF || (c >= 0x00 && c < 0x20 && c != '\t')) {
				throw new IllegalArgumentException(format("Illegal header value '%s' for header name '%s'. Offending character: '%s'", value, name, printableChar(c)));
			}
		}

		// Percent-encoded control sequence checks
		Matcher m = HEADER_PERCENT_ENCODING_PATTERN.matcher(value);

		while (m.find()) {
			int b = Integer.parseInt(m.group(1), 16);
			if (b == 0x0D || b == 0x0A || b == 0x00 || (b >= 0x00 && b < 0x20 && b != 0x09)) {
				throw new IllegalArgumentException(format(
						"Illegal (percent-encoded) header value '%s' for header name '%s'. Offending octet: 0x%02X",
						value, name, b));
			}
		}
	}

	@NonNull
	static String printableString(@NonNull String input) {
		requireNonNull(input);

		StringBuilder out = new StringBuilder(input.length() + 16);

		for (int i = 0; i < input.length(); i++)
			out.append(printableChar(input.charAt(i)));

		return out.toString();
	}

	@NonNull
	static String printableChar(char c) {
		if (c == '\r') return "\\r";
		if (c == '\n') return "\\n";
		if (c == '\t') return "\\t";
		if (c == '\f') return "\\f";
		if (c == '\b') return "\\b";
		if (c == '\\') return "\\\\";
		if (c == '\'') return "\\'";
		if (c == '\"') return "\\\"";
		if (c == 0) return "\\0";

		if (c < 0x20 || c == 0x7F)  // control chars
			return String.format("\\u%04X", (int) c);

		if (Character.isISOControl(c) || Character.getType(c) == Character.FORMAT)
			return String.format("\\u%04X", (int) c);

		return String.valueOf(c);
	}

	@NonNull
	private static final Set<String> COMMA_JOINABLE_HEADER_NAMES = Set.of(
			// Common list-type headers (RFC 7230/9110)
			"accept",
			"accept-encoding",
			"accept-language",
			"cache-control",
			"pragma",
			"vary",
			"connection",
			"transfer-encoding",
			"upgrade",
			"allow",
			"via",
			"warning"
			// intentionally NOT: set-cookie, authorization, cookie, content-disposition, location
	);

	/**
	 * Given a list of raw HTTP header lines, convert them into a normalized case-insensitive, order-preserving map which "inflates" comma-separated headers into distinct values where permitted according to RFC 7230/9110.
	 * <p>
	 * For example, given these raw header lines:
	 * <pre>{@code List<String> lines = List.of(
	 *   "Cache-Control: no-cache, no-store",
	 *   "Set-Cookie: a=b; Path=/; HttpOnly",
	 *   "Set-Cookie: c=d; Expires=Wed, 21 Oct 2015 07:28:00 GMT; Path=/"
	 * );}</pre>
	 * The result of parsing would look like this:
	 * <pre>{@code result.get("cache-control") -> [
	 *   "no-cache",
	 *   "no-store"
	 * ]
	 * result.get("set-cookie") -> [
	 *   "a=b; Path=/; HttpOnly",
	 *   "c=d; Expires=Wed, 21 Oct 2015 07:28:00 GMT; Path=/"
	 * ]}</pre>
	 * <p>
	 * Keys in the returned map are case-insensitive and are guaranteed to be in the same order as encountered in {@code rawHeaderLines}.
	 * <p>
	 * Values in the returned map are guaranteed to be in the same order as encountered in {@code rawHeaderLines}.
	 *
	 * @param rawHeaderLines the raw HTTP header lines to parse
	 * @return a normalized mapping of header name keys to values
	 */
	@NonNull
	public static Map<@NonNull String, @NonNull Set<@NonNull String>> extractHeadersFromRawHeaderLines(@NonNull List<@NonNull String> rawHeaderLines) {
		requireNonNull(rawHeaderLines);

		// 1) Unfold obsolete folded lines (obs-fold): lines beginning with SP/HT are continuations
		List<String> lines = unfold(rawHeaderLines);

		// 2) Parse into map
		Map<String, Set<String>> headers = new LinkedCaseInsensitiveMap<>();

		for (String raw : lines) {
			String line = trimAggressivelyToNull(raw);

			if (line == null)
				continue;

			int idx = line.indexOf(':');

			if (idx <= 0)
				continue; // skip malformed

			String key = trimAggressivelyToEmpty(line.substring(0, idx)); // keep original case for display
			String keyLowercase = key.toLowerCase(Locale.ROOT);
			String value = trimAggressivelyToNull(line.substring(idx + 1));

			if (value == null)
				continue;

			Set<String> bucket = headers.computeIfAbsent(key, k -> new LinkedHashSet<>());

			if (COMMA_JOINABLE_HEADER_NAMES.contains(keyLowercase)) {
				for (String part : splitCommaAware(value)) {
					String v = trimAggressivelyToNull(part);
					if (v != null)
						bucket.add(v);
				}
			} else {
				bucket.add(value.trim());
			}
		}

		return headers;
	}

	/**
	 * Header parsing helper
	 */
	@NonNull
	private static List<String> unfold(@NonNull List<String> raw) {
		requireNonNull(raw);
		if (raw.isEmpty()) return List.of();

		List<String> out = new ArrayList<>(raw.size());
		StringBuilder cur = null;
		boolean curIsHeader = false;

		for (String line : raw) {
			if (line == null) continue;

			boolean isContinuation = !line.isEmpty() && (line.charAt(0) == ' ' || line.charAt(0) == '\t');
			if (isContinuation) {
				if (cur != null && curIsHeader) {
					cur.append(' ').append(line.trim());
				} else {
					// Do not fold into a non-header; flush previous and start anew
					if (cur != null) out.add(cur.toString());
					cur = new StringBuilder(line);
					curIsHeader = line.indexOf(':') > 0; // almost certainly false for leading-space lines
				}
			} else {
				if (cur != null) out.add(cur.toString());
				cur = new StringBuilder(line);
				curIsHeader = line.indexOf(':') > 0;
			}
		}
		if (cur != null) out.add(cur.toString());
		return out;
	}

	/**
	 * Header parsing helper: split on commas that are not inside a quoted-string; supports \" escapes inside quotes.
	 */
	@NonNull
	private static List<String> splitCommaAware(@NonNull String string) {
		requireNonNull(string);

		List<String> out = new ArrayList<>(4);
		StringBuilder cur = new StringBuilder();
		boolean inQuotes = false;
		boolean escaped = false;

		for (int i = 0; i < string.length(); i++) {
			char c = string.charAt(i);

			if (escaped) {
				// Preserve the escaped char as-is
				cur.append(c);
				escaped = false;
			} else if (c == '\\') {
				if (inQuotes) {
					// Preserve the backslash itself, then mark next char as escaped
					cur.append('\\');       // ← keep the backslash
					escaped = true;
				} else {
					cur.append('\\');       // literal backslash outside quotes
				}
			} else if (c == '"') {
				inQuotes = !inQuotes;
				cur.append('"');
			} else if (c == ',' && !inQuotes) {
				out.add(cur.toString());
				cur.setLength(0);
			} else {
				cur.append(c);
			}
		}
		out.add(cur.toString());
		return out;
	}

	/**
	 * Remove a single pair of surrounding quotes if present.
	 */
	@NonNull
	private static String stripOptionalQuotes(@NonNull String string) {
		requireNonNull(string);

		if (string.length() >= 2) {
			char first = string.charAt(0), last = string.charAt(string.length() - 1);

			if ((first == '"' && last == '"') || (first == '\'' && last == '\''))
				return string.substring(1, string.length() - 1);
		}

		return string;
	}

	/**
	 * Parse host[:port] with IPv6 support: "[v6](:port)?" or "host(:port)?".
	 * Returns host (with brackets for v6) and port (nullable).
	 */
	@ThreadSafe
	private static final class HostPort {
		@NonNull
		private final String host;
		@Nullable
		private final Integer port;

		HostPort(@NonNull String host,
						 @Nullable Integer port) {
			this.host = host;
			this.port = port;
		}

		@NonNull
		public String getHost() {
			return this.host;
		}

		@NonNull
		public Optional<Integer> getPort() {
			return Optional.ofNullable(this.port);
		}
	}

	@NonNull
	private static Optional<HostPort> parseHostPort(@Nullable String input) {
		input = trimAggressivelyToNull(input);

		if (input == null)
			return Optional.empty();

		input = stripOptionalQuotes(input);

		if (input.startsWith("[")) {
			int close = input.indexOf(']');

			if (close > 0) {
				String core = input.substring(1, close); // IPv6 literal without brackets
				String rest = input.substring(close + 1); // maybe ":port"
				String host = "[" + core + "]";
				Integer port = null;

				if (rest.startsWith(":")) {
					String ps = trimAggressivelyToNull(rest.substring(1));
					if (ps != null) {
						try {
							port = Integer.parseInt(ps, 10);
						} catch (Exception ignored) {
							// Nothing to do
						}
					}
				}

				return Optional.of(new HostPort(host, port));
			}
		}

		int colon = input.indexOf(':');

		if (colon > 0 && input.indexOf(':', colon + 1) == -1) {
			// exactly one ':' -> host:port (IPv4/hostname)
			String h = trimAggressivelyToNull(input.substring(0, colon));
			String ps = trimAggressivelyToNull(input.substring(colon + 1));
			Integer p = null;

			if (ps != null) {
				try {
					p = Integer.parseInt(ps, 10);
				} catch (Exception ignored) {
					// Nothing to do
				}
			}
			if (h != null)
				return Optional.of(new HostPort(h, p));
		}

		// no port
		return Optional.of(new HostPort(input, null));
	}

	@NonNull
	private static String removeDotSegments(@NonNull String path) {
		requireNonNull(path);

		Deque<String> stack = new ArrayDeque<>();

		for (String seg : path.split("/")) {
			if (seg.isEmpty() || ".".equals(seg))
				continue;

			if ("..".equals(seg)) {
				if (!stack.isEmpty())
					stack.removeLast();
			} else {
				stack.addLast(seg);
			}
		}

		return "/" + String.join("/", stack);
	}
}
