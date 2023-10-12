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
import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.net.HttpCookie;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * HTTP Cookie representation which supports both request {@code Cookie} and response {@code Set-Cookie} header encoding.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class Cookie {
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

	private Cookie(@Nonnull Builder builder) {
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

	@Nonnull
	public static Set<Cookie> fromSetCookieHeaderRepresentation(@Nullable String setCookieHeaderRepresentation) {
		setCookieHeaderRepresentation = setCookieHeaderRepresentation == null ? null : setCookieHeaderRepresentation.trim();

		if (setCookieHeaderRepresentation == null)
			return Set.of();

		return HttpCookie.parse(setCookieHeaderRepresentation).stream()
				.map(httpCookie -> new Builder(httpCookie.getName(), httpCookie.getValue())
						.maxAge(Duration.ofSeconds(httpCookie.getMaxAge()))
						.domain(httpCookie.getDomain())
						.httpOnly(httpCookie.isHttpOnly())
						.secure(httpCookie.getSecure())
						.path(httpCookie.getPath())
						.build())
				.collect(Collectors.toSet());
	}

	@Nonnull
	public String toCookieHeaderRepresentation() {
		HttpCookie httpCookie = new HttpCookie(getName(), getValue().orElse(null));
		httpCookie.setMaxAge(getMaxAge().isPresent() ? getMaxAge().get().toSeconds() : null);
		httpCookie.setDomain(getDomain().orElse(null));
		httpCookie.setHttpOnly(getHttpOnly());
		httpCookie.setSecure(getSecure());
		httpCookie.setPath(getPath().orElse(null));

		return httpCookie.toString();
	}

	@Nonnull
	public String toSetCookieHeaderRepresentation() {
		// See https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Set-Cookie

		List<String> components = new ArrayList<>();

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

		if (!(object instanceof Cookie cookie))
			return false;

		return Objects.equals(getName(), cookie.getName())
				&& Objects.equals(getValue(), cookie.getValue())
				&& Objects.equals(getMaxAge(), cookie.getMaxAge())
				&& Objects.equals(getDomain(), cookie.getDomain())
				&& Objects.equals(getPath(), cookie.getPath())
				&& Objects.equals(getSecure(), cookie.getSecure())
				&& Objects.equals(getHttpOnly(), cookie.getHttpOnly())
				&& Objects.equals(getSameSite(), cookie.getSameSite());
	}

	@Override
	@Nonnull
	public String toString() {
		return toSetCookieHeaderRepresentation();
	}

	@Nonnull
	public String getName() {
		return this.name;
	}

	@Nonnull
	public Optional<String> getValue() {
		return Optional.ofNullable(this.value);
	}

	@Nonnull
	public Optional<Duration> getMaxAge() {
		return Optional.ofNullable(this.maxAge);
	}

	@Nonnull
	public Optional<String> getDomain() {
		return Optional.ofNullable(this.domain);
	}

	@Nonnull
	public Optional<String> getPath() {
		return Optional.ofNullable(this.path);
	}

	@Nonnull
	public Boolean getSecure() {
		return this.secure;
	}

	@Nonnull
	public Boolean getHttpOnly() {
		return this.httpOnly;
	}

	@Nonnull
	public Optional<SameSite> getSameSite() {
		return Optional.ofNullable(this.sameSite);
	}

	public enum SameSite {
		STRICT("Strict"),
		LAX("Lax"),
		NONE("None");

		@Nonnull
		private final String headerRepresentation;

		SameSite(@Nonnull String headerRepresentation) {
			requireNonNull(headerRepresentation);
			this.headerRepresentation = headerRepresentation;
		}

		@Nonnull
		public static Optional<SameSite> fromHeaderRepresentation(@Nonnull String headerRepresentation) {
			requireNonNull(headerRepresentation);

			headerRepresentation = headerRepresentation.trim();

			for (SameSite sameSite : values())
				if (headerRepresentation.equalsIgnoreCase(sameSite.getHeaderRepresentation()))
					return Optional.of(sameSite);

			return Optional.empty();
		}

		@Nonnull
		public String getHeaderRepresentation() {
			return this.headerRepresentation;
		}
	}

	/**
	 * Builder used to construct instances of {@link Cookie}.
	 * <p>
	 * This class is intended for use by a single thread.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static class Builder {
		@Nonnull
		private final String name;
		@Nullable
		private final String value;
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

		public Builder(@Nonnull String name,
									 @Nullable String value) {
			requireNonNull(name);
			this.name = name;
			this.value = value;
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
		public Cookie build() {
			return new Cookie(this);
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