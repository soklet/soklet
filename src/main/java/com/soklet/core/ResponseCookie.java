/*
 * Copyright 2022-2024 Revetware LLC.
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
import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.net.HttpCookie;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * HTTP "response" Cookie representation which supports {@code Set-Cookie} header encoding.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class ResponseCookie {
	@Nonnull
	private final String name;
	@Nullable
	private final String value;
	@Nullable
	private final Duration maxAge;
	@Nullable
	private final String domain;
	@Nullable
	private final String path;
	@Nonnull
	private final Boolean secure;
	@Nonnull
	private final Boolean httpOnly;
	@Nullable
	private final SameSite sameSite;

	/**
	 * Acquires a builder for {@link ResponseCookie} instances.
	 *
	 * @param name  the cookie name
	 * @param value the cookie value
	 * @return the builder
	 */
	@Nonnull
	public static Builder with(@Nonnull String name,
														 @Nullable String value) {
		requireNonNull(name);
		return new Builder(name, value);
	}

	/**
	 * Acquires a builder for {@link ResponseCookie} instances without specifying the cookie's value.
	 *
	 * @param name the cookie name
	 * @return the builder
	 */
	@Nonnull
	public static Builder withName(@Nonnull String name) {
		requireNonNull(name);
		return new Builder(name);
	}

	/**
	 * Vends a mutable copier seeded with this instance's data, suitable for building new instances.
	 *
	 * @return a copier for this instance
	 */
	@Nonnull
	public Copier copy() {
		return new Copier(this);
	}

	/**
	 * Given a {@code Set-Cookie} header representation, provide a {@link ResponseCookie} that matches it.
	 * <p>
	 * An example of a {@code Set-Cookie} header representation is {@code Set-Cookie: <cookie-name>=<cookie-value>; Domain=<domain-value>; Secure; HttpOnly}
	 * <p>
	 * Note: while the spec does not forbid multiple cookie name/value pairs to be specified in the same {@code Set-Cookie} header, this format is unusual - Soklet does not currently support parsing these kinds of cookies.
	 * <p>
	 * See <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Set-Cookie">https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Set-Cookie</a> for details.
	 *
	 * @param setCookieHeaderRepresentation a {@code Set-Cookie} header representation
	 * @return a {@link ResponseCookie} representation of the {@code Set-Cookie} header, or {@link Optional#empty()} if the header is null, empty, or does not include cookie data
	 * @throws IllegalArgumentException if the {@code Set-Cookie} header representation is malformed
	 */
	@Nonnull
	public static Optional<ResponseCookie> fromSetCookieHeaderRepresentation(@Nullable String setCookieHeaderRepresentation) {
		setCookieHeaderRepresentation = setCookieHeaderRepresentation == null ? null : setCookieHeaderRepresentation.trim();

		if (setCookieHeaderRepresentation == null || setCookieHeaderRepresentation.length() == 0)
			return Optional.empty();

		List<HttpCookie> cookies = HttpCookie.parse(setCookieHeaderRepresentation);

		if (cookies.size() == 0)
			return Optional.empty();

		// Technically OK per the spec to "fold" multiple cookie name/value pairs into the same header but this is
		// unusual and we don't support it here.  Pick the first cookie and use it.
		HttpCookie httpCookie = cookies.get(0);

		return Optional.of(ResponseCookie.with(httpCookie.getName(), httpCookie.getValue())
				.maxAge(Duration.ofSeconds(httpCookie.getMaxAge()))
				.domain(httpCookie.getDomain())
				.httpOnly(httpCookie.isHttpOnly())
				.secure(httpCookie.getSecure())
				.path(httpCookie.getPath())
				.build());
	}

	protected ResponseCookie(@Nonnull Builder builder) {
		requireNonNull(builder);

		this.name = builder.name;
		this.value = builder.value;
		this.maxAge = builder.maxAge;
		this.domain = builder.domain;
		this.path = builder.path;
		this.secure = builder.secure == null ? false : builder.secure;
		this.httpOnly = builder.httpOnly == null ? false : builder.httpOnly;
		this.sameSite = builder.sameSite;

		Rfc6265Utils.validateCookieName(getName());
		Rfc6265Utils.validateCookieValue(getValue().orElse(null));
		Rfc6265Utils.validateDomain(getDomain().orElse(null));
		Rfc6265Utils.validatePath(getPath().orElse(null));
	}

	/**
	 * Generates a {@code Set-Cookie} header representation of this response cookie, for example {@code Set-Cookie: <cookie-name>=<cookie-value>; Domain=<domain-value>; Secure; HttpOnly}
	 * <p>
	 * See <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Set-Cookie">https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Set-Cookie</a> for details.
	 *
	 * @return this response cookie in {@code Set-Cookie} header format
	 */
	@Nonnull
	public String toSetCookieHeaderRepresentation() {
		List<String> components = new ArrayList<>(8);

		components.add(format("%s=%s", getName(), getValue().orElse("")));

		if (getPath().isPresent())
			components.add(format("Path=%s", getPath().get()));

		if (getDomain().isPresent())
			components.add(format("Domain=%s", getDomain().get()));

		long maxAge = getMaxAge().isPresent() ? getMaxAge().get().toSeconds() : -1;

		if (maxAge >= 0)
			components.add(format("Max-Age=%d", maxAge));

		if (getSecure())
			components.add("Secure");

		if (getHttpOnly())
			components.add("HttpOnly");

		if (getSameSite().isPresent())
			components.add(format("SameSite=%s", getSameSite().get().getHeaderRepresentation()));

		return components.stream().collect(Collectors.joining("; "));
	}

	@Override
	public int hashCode() {
		return Objects.hash(
				getName(),
				getValue(),
				getMaxAge(),
				getDomain(),
				getPath(),
				getSecure(),
				getHttpOnly(),
				getSameSite()
		);
	}

	@Override
	public boolean equals(@Nullable Object object) {
		if (this == object)
			return true;

		if (!(object instanceof ResponseCookie responseCookie))
			return false;

		return Objects.equals(getName(), responseCookie.getName())
				&& Objects.equals(getValue(), responseCookie.getValue())
				&& Objects.equals(getMaxAge(), responseCookie.getMaxAge())
				&& Objects.equals(getDomain(), responseCookie.getDomain())
				&& Objects.equals(getPath(), responseCookie.getPath())
				&& Objects.equals(getSecure(), responseCookie.getSecure())
				&& Objects.equals(getHttpOnly(), responseCookie.getHttpOnly())
				&& Objects.equals(getSameSite(), responseCookie.getSameSite());
	}

	@Override
	@Nonnull
	public String toString() {
		return toSetCookieHeaderRepresentation();
	}

	/**
	 * Gets the cookie's name.
	 *
	 * @return the name of the cookie
	 */
	@Nonnull
	public String getName() {
		return this.name;
	}

	/**
	 * Gets the cookie's value, if present.
	 *
	 * @return the value of the cookie, or {@link Optional#empty()} if there is none
	 */
	@Nonnull
	public Optional<String> getValue() {
		return Optional.ofNullable(this.value);
	}

	/**
	 * Gets the cookie's {@code Max-Age} value expressed as a {@link Duration}, if present.
	 *
	 * @return the {@code Max-Age} value of the cookie, or {@link Optional#empty()} if there is none
	 */
	@Nonnull
	public Optional<Duration> getMaxAge() {
		return Optional.ofNullable(this.maxAge);
	}

	/**
	 * Gets the cookie's {@code Domain} value, if present.
	 *
	 * @return the {@code Domain} value of the cookie, or {@link Optional#empty()} if there is none
	 */
	@Nonnull
	public Optional<String> getDomain() {
		return Optional.ofNullable(this.domain);
	}

	/**
	 * Gets the cookie's {@code Path} value, if present.
	 *
	 * @return the {@code Path} value of the cookie, or {@link Optional#empty()} if there is none
	 */
	@Nonnull
	public Optional<String> getPath() {
		return Optional.ofNullable(this.path);
	}

	/**
	 * Gets the cookie's {@code Secure} flag, if present.
	 *
	 * @return {@code true} if the {@code Secure} flag of the cookie is present, {@code false} otherwise
	 */
	@Nonnull
	public Boolean getSecure() {
		return this.secure;
	}

	/**
	 * Gets the cookie's {@code HttpOnly} flag, if present.
	 *
	 * @return {@code true} if the {@code HttpOnly} flag of the cookie is present, {@code false} otherwise
	 */
	@Nonnull
	public Boolean getHttpOnly() {
		return this.httpOnly;
	}

	/**
	 * Gets the cookie's {@code SameSite} value, if present.
	 *
	 * @return the {@code SameSite} value of the cookie, or {@link Optional#empty()} if there is none
	 */
	@Nonnull
	public Optional<SameSite> getSameSite() {
		return Optional.ofNullable(this.sameSite);
	}

	/**
	 * Values which control whether or not a response cookie is sent with cross-site requests, providing some protection against cross-site request forgery attacks (CSRF).
	 * <p>
	 * See <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Set-Cookie#samesitesamesite-value">https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Set-Cookie#samesitesamesite-value</a> for details.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	public enum SameSite {
		/**
		 * Means that the browser sends the cookie only for same-site requests, that is, requests originating from the same site that set the cookie.
		 * If a request originates from a different domain or scheme (even with the same domain), no cookies with the {@code SameSite=Strict} attribute are sent.
		 */
		STRICT("Strict"),
		/**
		 * Means that the cookie is not sent on cross-site requests, such as on requests to load images or frames, but is sent when a user is navigating to the origin site from an external site (for example, when following a link).
		 * This is the default behavior if the {@code SameSite} attribute is not specified.
		 */
		LAX("Lax"),
		/**
		 * Means that the browser sends the cookie with both cross-site and same-site requests.
		 * The {@code Secure} attribute must also be set when setting this value, like so {@code SameSite=None; Secure}. If {@code Secure} is missing, an error will be logged.
		 */
		NONE("None");

		@Nonnull
		private final String headerRepresentation;

		SameSite(@Nonnull String headerRepresentation) {
			requireNonNull(headerRepresentation);
			this.headerRepresentation = headerRepresentation;
		}

		/**
		 * Returns the {@link SameSite} enum value that matches the corresponding {@code SameSite} response header value representation (one of {@code Strict}, {@code Lax}, or {@code None} - case-insensitive).
		 *
		 * @param headerRepresentation a case-insensitive HTTP header value - one of {@code Strict}, {@code Lax}, or {@code None}
		 * @return the enum value that corresponds to the given the header representation, or {@link Optional#empty()} if none matches
		 */
		@Nonnull
		public static Optional<SameSite> fromHeaderRepresentation(@Nonnull String headerRepresentation) {
			requireNonNull(headerRepresentation);

			headerRepresentation = headerRepresentation.trim();

			for (SameSite sameSite : values())
				if (headerRepresentation.equalsIgnoreCase(sameSite.getHeaderRepresentation()))
					return Optional.of(sameSite);

			return Optional.empty();
		}

		/**
		 * The HTTP header value that corresponds to this enum value - one of {@code Strict}, {@code Lax}, or {@code None}.
		 *
		 * @return the HTTP header value for this enum
		 */
		@Nonnull
		public String getHeaderRepresentation() {
			return this.headerRepresentation;
		}
	}

	/**
	 * Builder used to construct instances of {@link ResponseCookie} via {@link ResponseCookie#withName(String)}
	 * or {@link ResponseCookie#with(String, String)}.
	 * <p>
	 * This class is intended for use by a single thread.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static class Builder {
		@Nonnull
		private String name;
		@Nullable
		private String value;
		@Nullable
		private Duration maxAge;
		@Nullable
		private String domain;
		@Nullable
		private String path;
		@Nullable
		private Boolean secure;
		@Nullable
		private Boolean httpOnly;
		@Nullable
		private SameSite sameSite;

		protected Builder(@Nonnull String name) {
			requireNonNull(name);
			this.name = name;
		}

		protected Builder(@Nonnull String name,
											@Nullable String value) {
			requireNonNull(name);
			this.name = name;
			this.value = value;
		}

		@Nonnull
		public Builder name(@Nonnull String name) {
			requireNonNull(name);
			this.name = name;
			return this;
		}

		@Nonnull
		public Builder value(@Nullable String value) {
			this.value = value;
			return this;
		}

		@Nonnull
		public Builder maxAge(@Nullable Duration maxAge) {
			this.maxAge = maxAge;
			return this;
		}

		@Nonnull
		public Builder domain(@Nullable String domain) {
			this.domain = domain;
			return this;
		}

		@Nonnull
		public Builder path(@Nullable String path) {
			this.path = path;
			return this;
		}

		@Nonnull
		public Builder secure(@Nullable Boolean secure) {
			this.secure = secure;
			return this;
		}

		@Nonnull
		public Builder httpOnly(@Nullable Boolean httpOnly) {
			this.httpOnly = httpOnly;
			return this;
		}

		@Nonnull
		public Builder sameSite(@Nullable SameSite sameSite) {
			this.sameSite = sameSite;
			return this;
		}

		@Nonnull
		public ResponseCookie build() {
			return new ResponseCookie(this);
		}
	}

	/**
	 * Builder used to copy instances of {@link ResponseCookie} via {@link ResponseCookie#copy()}.
	 * <p>
	 * This class is intended for use by a single thread.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static class Copier {
		@Nonnull
		private final Builder builder;

		Copier(@Nonnull ResponseCookie responseCookie) {
			requireNonNull(responseCookie);

			this.builder = new Builder(responseCookie.getName())
					.value(responseCookie.getValue().orElse(null))
					.maxAge(responseCookie.getMaxAge().orElse(null))
					.domain(responseCookie.getDomain().orElse(null))
					.path(responseCookie.getPath().orElse(null))
					.secure(responseCookie.getSecure())
					.httpOnly(responseCookie.getHttpOnly())
					.sameSite(responseCookie.getSameSite().orElse(null));
		}

		@Nonnull
		public Copier name(@Nonnull String name) {
			requireNonNull(name);
			this.builder.name(name);
			return this;
		}

		@Nonnull
		public Copier value(@Nullable String value) {
			this.builder.value(value);
			return this;
		}

		@Nonnull
		public Copier maxAge(@Nullable Duration maxAge) {
			this.builder.maxAge(maxAge);
			return this;
		}

		@Nonnull
		public Copier domain(@Nullable String domain) {
			this.builder.domain(domain);
			return this;
		}

		@Nonnull
		public Copier path(@Nullable String path) {
			this.builder.path(path);
			return this;
		}

		@Nonnull
		public Copier secure(@Nullable Boolean secure) {
			this.builder.secure(secure);
			return this;
		}

		@Nonnull
		public Copier httpOnly(@Nullable Boolean httpOnly) {
			this.builder.httpOnly(httpOnly);
			return this;
		}

		@Nonnull
		public Copier sameSite(@Nullable SameSite sameSite) {
			this.builder.sameSite(sameSite);
			return this;
		}

		@Nonnull
		public ResponseCookie finish() {
			return this.builder.build();
		}
	}

	/**
	 * See https://github.com/spring-projects/spring-framework/blob/main/spring-web/src/main/java/org/springframework/http/ResponseCookie.java
	 * <p>
	 * Copyright 2002-2023 the original author or authors.
	 * <p>
	 * Licensed under the Apache License, Version 2.0 (the "License");
	 * you may not use this file except in compliance with the License.
	 * You may obtain a copy of the License at
	 * <p>
	 * https://www.apache.org/licenses/LICENSE-2.0
	 * <p>
	 * Unless required by applicable law or agreed to in writing, software
	 * distributed under the License is distributed on an "AS IS" BASIS,
	 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
	 * See the License for the specific language governing permissions and
	 * limitations under the License.
	 * <p>
	 *
	 * @author Rossen Stoyanchev
	 * @author Brian Clozel
	 */
	@ThreadSafe
	private static class Rfc6265Utils {
		@Nonnull
		private static final String SEPARATOR_CHARS = new String(new char[]{
				'(', ')', '<', '>', '@', ',', ';', ':', '\\', '"', '/', '[', ']', '?', '=', '{', '}', ' '
		});

		@Nonnull
		private static final String DOMAIN_CHARS =
				"0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ.-";

		public static void validateCookieName(@Nonnull String name) {
			requireNonNull(name);

			for (int i = 0; i < name.length(); i++) {
				char c = name.charAt(i);
				// CTL = <US-ASCII control chars (octets 0 - 31) and DEL (127)>
				if (c <= 0x1F || c == 0x7F) {
					throw new IllegalArgumentException(
							name + ": RFC2616 token cannot have control chars");
				}
				if (SEPARATOR_CHARS.indexOf(c) >= 0) {
					throw new IllegalArgumentException(
							name + ": RFC2616 token cannot have separator chars such as '" + c + "'");
				}
				if (c >= 0x80) {
					throw new IllegalArgumentException(
							name + ": RFC2616 token can only have US-ASCII: 0x" + Integer.toHexString(c));
				}
			}
		}

		public static void validateCookieValue(@Nullable String value) {
			if (value == null) {
				return;
			}
			int start = 0;
			int end = value.length();
			if (end > 1 && value.charAt(0) == '"' && value.charAt(end - 1) == '"') {
				start = 1;
				end--;
			}
			for (int i = start; i < end; i++) {
				char c = value.charAt(i);
				if (c < 0x21 || c == 0x22 || c == 0x2c || c == 0x3b || c == 0x5c || c == 0x7f) {
					throw new IllegalArgumentException(
							"RFC2616 cookie value cannot have '" + c + "'");
				}
				if (c >= 0x80) {
					throw new IllegalArgumentException(
							"RFC2616 cookie value can only have US-ASCII chars: 0x" + Integer.toHexString(c));
				}
			}
		}

		public static void validateDomain(@Nullable String domain) {
			if (domain == null || domain.trim().length() == 0) {
				return;
			}
			int char1 = domain.charAt(0);
			int charN = domain.charAt(domain.length() - 1);
			if (char1 == '-' || charN == '.' || charN == '-') {
				throw new IllegalArgumentException("Invalid first/last char in cookie domain: " + domain);
			}
			for (int i = 0, c = -1; i < domain.length(); i++) {
				int p = c;
				c = domain.charAt(i);
				if (DOMAIN_CHARS.indexOf(c) == -1 || (p == '.' && (c == '.' || c == '-')) || (p == '-' && c == '.')) {
					throw new IllegalArgumentException(domain + ": invalid cookie domain char '" + (char) c + "'");
				}
			}
		}

		public static void validatePath(@Nullable String path) {
			if (path == null) {
				return;
			}
			for (int i = 0; i < path.length(); i++) {
				char c = path.charAt(i);
				if (c < 0x20 || c > 0x7E || c == ';') {
					throw new IllegalArgumentException(path + ": Invalid cookie path char '" + c + "'");
				}
			}
		}
	}
}