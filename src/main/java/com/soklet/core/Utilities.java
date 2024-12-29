/*
 * Copyright 2022-2025 Revetware LLC.
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

import com.soklet.internal.spring.LinkedCaseInsensitiveMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.lang.Thread.UncaughtExceptionHandler;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.UnsupportedCharsetException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Locale.LanguageRange;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
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
	}

	private Utilities() {
		// Non-instantiable
	}

	/**
	 * Does the platform runtime support virtual threads (either Java 19 and 20 w/preview enabled or Java 21+)?
	 *
	 * @return {@code true} if the runtime supports virtual threads, {@code false} otherwise
	 */
	@Nonnull
	public static Boolean virtualThreadsAvailable() {
		return VIRTUAL_THREADS_AVAILABLE;
	}

	/**
	 * Provides a virtual-thread-per-task executor service if supported by the runtime.
	 * <p>
	 * In order to support Soklet users who are not yet ready to enable virtual threads (those <strong>not</strong> running either Java 19 and 20 w/preview enabled or Java 21+),
	 * we compile Soklet with a source level &lt; 19 and avoid any hard references to virtual threads by dynamically creating our executor service via {@link MethodHandle} references.
	 * <p>
	 * <strong>You should not call this method if {@link Utilities#virtualThreadsAvailable()} is {@code false}.</strong>
	 * <pre>{@code  // This method is effectively equivalent to this code
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
	@Nonnull
	public static ExecutorService createVirtualThreadsNewThreadPerTaskExecutor(@Nonnull String threadNamePrefix,
																																						 @Nonnull UncaughtExceptionHandler uncaughtExceptionHandler) {
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
		Map<String, Set<String>> queryParameters = new LinkedHashMap<>();

		for (String queryParameterComponent : queryParameterComponents) {
			String[] queryParameterNameAndValue = queryParameterComponent.split("=");
			String name = queryParameterNameAndValue.length > 0 ? trimAggressivelyToNull(queryParameterNameAndValue[0]) : null;

			if (name == null)
				continue;

			String value = queryParameterNameAndValue.length > 1 ? trimAggressivelyToNull(queryParameterNameAndValue[1]) : null;

			if (value == null)
				continue;

			Set<String> values = queryParameters.computeIfAbsent(name, k -> new LinkedHashSet<>());

			values.add(value);
		}

		return queryParameters;
	}

	@Nonnull
	public static Map<String, Set<String>> extractCookiesFromHeaders(@Nonnull Map<String, Set<String>> headers) {
		requireNonNull(headers);

		Map<String, Set<String>> cookies = new LinkedCaseInsensitiveMap<>();

		for (Map.Entry<String, Set<String>> entry : headers.entrySet()) {
			if (entry.getKey().equals("Cookie")) {
				Set<String> values = entry.getValue();

				for (String value : values) {
					value = trimAggressivelyToNull(value);

					if (value == null)
						continue;

					String[] cookieComponents = value.split(";");

					for (String cookieComponent : cookieComponents) {
						cookieComponent = trimAggressivelyToNull(cookieComponent);

						if (cookieComponent == null)
							continue;

						String[] cookiePair = cookieComponent.split("=");

						if (cookiePair.length != 1 && cookiePair.length != 2)
							continue;

						String cookieName = trimAggressivelyToNull(cookiePair[0]);
						String cookieValue = cookiePair.length == 1 ? null : trimAggressivelyToNull(cookiePair[1]);

						if (cookieName == null)
							continue;

						Set<String> cookieValues = cookies.get(cookieName);

						if (cookieValues == null) {
							cookieValues = new LinkedHashSet<>();
							cookies.put(cookieName, cookieValues);
						}

						if (cookieValue != null)
							cookieValues.add(cookieValue);
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
	@Nonnull
	public static Optional<String> extractClientUrlPrefixFromHeaders(@Nonnull Map<String, Set<String>> headers) {
		requireNonNull(headers);

		// Host                   developer.mozilla.org OR developer.mozilla.org:443
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

		// Host: developer.mozilla.org OR developer.mozilla.org:443
		Set<String> hostHeaders = headers.get("Host");

		if (hostHeaders != null && hostHeaders.size() > 0) {
			String hostHeader = trimAggressivelyToNull(hostHeaders.stream().findFirst().get());

			if (hostHeader != null) {
				if (hostHeader.contains(":")) {
					String[] hostHeaderComponents = hostHeader.split(":");
					if (hostHeaderComponents.length == 2) {
						host = trimAggressivelyToNull(hostHeaderComponents[0]);
						portAsString = trimAggressivelyToNull(hostHeaderComponents[1]);
					}
				} else {
					host = hostHeader;
				}
			}
		}

		// Forwarded: by=<identifier>;for=<identifier>;host=<host>;proto=<http|https> (can be repeated if comma-separated, e.g. for=12.34.56.78;host=example.com;proto=https, for=23.45.67.89)
		Set<String> forwardedHeaders = headers.get("Forwarded");

		if (forwardedHeaders != null && forwardedHeaders.size() > 0) {
			String forwardedHeader = trimAggressivelyToNull(forwardedHeaders.stream().findFirst().get());

			// If there are multiple comma-separated components, pick the first one
			String[] forwardedHeaderComponents = forwardedHeader.split(",");
			forwardedHeader = trimAggressivelyToNull(forwardedHeaderComponents[0]);

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

					// e.g. "by"
					String name = trimAggressivelyToNull(forwardedHeaderFieldNameAndValue[0]);
					// e.g. "<identifier>"
					String value = trimAggressivelyToNull(forwardedHeaderFieldNameAndValue[1]);

					if (name == null || value == null)
						continue;

					// We only care about the "Host" and "Proto" components here.
					if ("host".equalsIgnoreCase(name)) {
						if (host == null)
							host = value;
					} else if ("proto".equalsIgnoreCase(name)) {
						if (protocol == null)
							protocol = value;
					}
				}
			}
		}

		// Origin: null OR <scheme>://<hostname> OR <scheme>://<hostname>:<port>
		if (protocol == null || host == null || portAsString == null) {
			Set<String> originHeaders = headers.get("Origin");

			if (originHeaders != null && originHeaders.size() > 0) {
				String originHeader = trimAggressivelyToNull(originHeaders.stream().findFirst().get());
				String[] originHeaderComponents = originHeader.split("://");

				if (originHeaderComponents.length == 2) {
					protocol = trimAggressivelyToNull(originHeaderComponents[0]);
					String originHostAndMaybePort = trimAggressivelyToNull(originHeaderComponents[1]);

					if (originHostAndMaybePort != null) {
						if (originHostAndMaybePort.contains(":")) {
							String[] originHostAndPortComponents = originHostAndMaybePort.split(":");

							if (originHostAndPortComponents.length == 2) {
								host = trimAggressivelyToNull(originHostAndPortComponents[0]);
								portAsString = trimAggressivelyToNull(originHostAndPortComponents[1]);
							}
						} else {
							host = originHostAndMaybePort;
						}
					}
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

		// X-Forwarded-Host: id42.example-cdn.com
		if (host == null) {
			Set<String> xForwardedHostHeaders = headers.get("X-Forwarded-Host");
			if (xForwardedHostHeaders != null && xForwardedHostHeaders.size() > 0) {
				String xForwardedHostHeader = trimAggressivelyToNull(xForwardedHostHeaders.stream().findFirst().get());
				host = xForwardedHostHeader;
			}
		}

		// X-Forwarded-Port: 443
		if (portAsString == null) {
			Set<String> xForwardedPortHeaders = headers.get("X-Forwarded-Port");
			if (xForwardedPortHeaders != null && xForwardedPortHeaders.size() > 0) {
				String xForwardedPortHeader = trimAggressivelyToNull(xForwardedPortHeaders.stream().findFirst().get());
				portAsString = xForwardedPortHeader;
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

		if (protocol != null && host != null && port == null)
			return Optional.of(format("%s://%s", protocol, host));

		if (protocol != null && host != null && port != null) {
			boolean usingDefaultPort = ("http".equalsIgnoreCase(protocol) && port.equals(80))
					|| ("https".equalsIgnoreCase(protocol) && port.equals(443));

			// Only include the port number if it's nonstandard for the protocol
			String clientUrlPrefix = usingDefaultPort
					? format("%s://%s", protocol, host)
					: format("%s://%s:%s", protocol, host, port);

			return Optional.of(clientUrlPrefix);
		}

		return Optional.empty();
	}

	@Nonnull
	public static Optional<String> extractContentTypeFromHeaders(@Nonnull Map<String, Set<String>> headers) {
		requireNonNull(headers);

		Set<String> contentTypeHeaderValues = headers.get("Content-Type");

		if (contentTypeHeaderValues == null || contentTypeHeaderValues.size() == 0)
			return Optional.empty();

		return extractContentTypeFromHeaderValue(contentTypeHeaderValues.stream().findFirst().get());
	}

	@Nonnull
	public static Optional<String> extractContentTypeFromHeaderValue(@Nullable String contentTypeHeaderValue) {
		contentTypeHeaderValue = trimAggressivelyToNull(contentTypeHeaderValue);

		if (contentTypeHeaderValue == null)
			return Optional.empty();

		// Examples
		// Content-Type: text/html; charset=utf-8
		// Content-Type: multipart/form-data; boundary=something

		int indexOfSemicolon = contentTypeHeaderValue.indexOf(";");

		// Simple case, e.g. "text/html"
		if (indexOfSemicolon == -1)
			return Optional.ofNullable(trimAggressivelyToNull(contentTypeHeaderValue));

		// More complex case, e.g. "text/html; charset=utf-8"
		return Optional.ofNullable(trimAggressivelyToNull(contentTypeHeaderValue.substring(0, indexOfSemicolon)));
	}

	@Nonnull
	public static Optional<Charset> extractCharsetFromHeaders(@Nonnull Map<String, Set<String>> headers) {
		requireNonNull(headers);

		Set<String> contentTypeHeaderValues = headers.get("Content-Type");

		if (contentTypeHeaderValues == null || contentTypeHeaderValues.size() == 0)
			return Optional.empty();

		return extractCharsetFromHeaderValue(contentTypeHeaderValues.stream().findFirst().get());
	}

	@Nonnull
	public static Optional<Charset> extractCharsetFromHeaderValue(@Nullable String contentTypeHeaderValue) {
		contentTypeHeaderValue = trimAggressivelyToNull(contentTypeHeaderValue);

		if (contentTypeHeaderValue == null)
			return Optional.empty();

		// Examples
		// Content-Type: text/html; charset=utf-8
		// Content-Type: multipart/form-data; boundary=something

		int indexOfSemicolon = contentTypeHeaderValue.indexOf(";");

		// Simple case, e.g. "text/html"
		if (indexOfSemicolon == -1)
			return Optional.empty();

		// More complex case, e.g. "text/html; charset=utf-8" or "multipart/form-data; charset=utf-8; boundary=something"
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

		// Handle case where charset is the end of the string, e.g. "whatever;charset=utf-8"
		if (!finishedCharsetName) {
			String potentialCharset = trimAggressivelyToNull(buffer.toString());
			if (potentialCharset != null && potentialCharset.startsWith("charset=")) {
				finishedCharsetName = true;
				charsetName = potentialCharset;
			}
		}

		if (finishedCharsetName) {
			// e.g. "charset=utf-8" -> "utf-8"
			charsetName = trimAggressivelyToNull(charsetName.replace("charset=", ""));

			if (charsetName != null) {
				try {
					return Optional.of(Charset.forName(charsetName));
				} catch (IllegalCharsetNameException | UnsupportedCharsetException ignored) {
					return Optional.empty();
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
