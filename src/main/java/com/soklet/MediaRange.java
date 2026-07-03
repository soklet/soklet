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
import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * An immutable representation of a single media range from an HTTP {@code Accept} header value, e.g.
 * {@code text/html;level=1;q=0.7}, as defined by
 * <a href="https://www.rfc-editor.org/rfc/rfc9110.html#section-12.5.1">RFC 9110, Section 12.5.1</a>.
 * <p>
 * The {@linkplain #getType() type} and {@linkplain #getSubtype() subtype} are normalized to lowercase and either
 * may be the {@code *} wildcard (a wildcard type requires a wildcard subtype). The {@linkplain #getQuality() quality}
 * is the {@code q} weight parameter, defaulting to {@code 1} when absent and clamped to the range
 * {@code [0, 1]}. {@linkplain #getParameters() Parameters} are the media-type parameters that appear
 * <em>before</em> {@code q}; accept-ext parameters that appear after {@code q} are ignored.
 * <p>
 * See {@link Request#getMediaRanges()} for the ordered media ranges of a request's {@code Accept} header value[s].
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class MediaRange {
	@NonNull
	private static final BigDecimal DEFAULT_QUALITY = BigDecimal.ONE;

	@NonNull
	private final String type;
	@NonNull
	private final String subtype;
	@NonNull
	private final BigDecimal quality;
	@NonNull
	private final Map<@NonNull String, @NonNull String> parameters;

	private MediaRange(@NonNull String type,
										 @NonNull String subtype,
										 @NonNull BigDecimal quality,
										 @NonNull Map<@NonNull String, @NonNull String> parameters) {
		requireNonNull(type);
		requireNonNull(subtype);
		requireNonNull(quality);
		requireNonNull(parameters);

		this.type = type;
		this.subtype = subtype;
		// Normalize scale (e.g. 0.50 -> 0.5) so equals/hashCode can use plain BigDecimal equality
		this.quality = quality.stripTrailingZeros();
		// Preserve parameter order (Map.copyOf does not)
		this.parameters = Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
	}

	/**
	 * Parses a single media range, e.g. {@code text/html;level=1;q=0.7}, per
	 * <a href="https://www.rfc-editor.org/rfc/rfc9110.html#section-12.5.1">RFC 9110, Section 12.5.1</a>.
	 * <p>
	 * Parsing is lenient: a malformed representation yields {@link Optional#empty()} rather than an exception.
	 * A representation is considered malformed if it lacks a {@code type/subtype} structure, uses invalid
	 * HTTP tokens for the type, subtype, or parameter names, pairs a wildcard type with a concrete subtype
	 * (e.g. {@code *&#47;html}), or carries an unparseable {@code q} value.
	 * Quality values outside {@code [0, 1]} are clamped. If multiple {@code q} parameters are present, all but
	 * the first are ignored. Quoted parameter values have their surrounding quotes removed and quoted-pair
	 * escape sequences (e.g. {@code \"}) unescaped.
	 *
	 * @param mediaRange the media range header representation to parse, e.g. {@code text/html;q=0.7}
	 * @return the parsed media range, or {@link Optional#empty()} if the input is missing or malformed
	 */
	@NonNull
	public static Optional<MediaRange> fromHeaderRepresentation(@Nullable String mediaRange) {
		if (mediaRange == null)
			return Optional.empty();

		String normalized = mediaRange.trim();

		if (normalized.isEmpty())
			return Optional.empty();

		List<String> segments = Utilities.splitSemicolonAware(normalized);

		if (segments.isEmpty())
			return Optional.empty();

		String[] typeAndSubtype = segments.get(0).trim().toLowerCase(Locale.ROOT).split("/", 2);

		if (typeAndSubtype.length != 2)
			return Optional.empty();

		String type = typeAndSubtype[0].trim();
		String subtype = typeAndSubtype[1].trim();

		if (!isToken(type) || !isToken(subtype))
			return Optional.empty();

		// A wildcard type with a concrete subtype (e.g. "*/html") is not a valid media range
		if ("*".equals(type) && !"*".equals(subtype))
			return Optional.empty();

		BigDecimal quality = DEFAULT_QUALITY;
		Map<String, String> parameters = new LinkedHashMap<>();
		boolean qualityEncountered = false;

		for (int i = 1; i < segments.size(); i++) {
			String segment = segments.get(i).trim();
			int equalsIndex = segment.indexOf('=');

			if (equalsIndex == -1)
				continue;

			String name = segment.substring(0, equalsIndex).trim().toLowerCase(Locale.ROOT);
			String value = unquote(segment.substring(equalsIndex + 1).trim());

			if (!isToken(name))
				return Optional.empty();

			if ("q".equals(name)) {
				// RFC 9110: if multiple "q" parameters are present, all but the first are ignored
				if (!qualityEncountered) {
					try {
						quality = clampQuality(new BigDecimal(value));
					} catch (NumberFormatException e) {
						return Optional.empty();
					}

					qualityEncountered = true;
				}
			} else if (!qualityEncountered && !name.isEmpty()) {
				// Media-type parameters appear before "q"; anything after it is accept-ext, which we ignore
				parameters.put(name, value);
			}
		}

		return Optional.of(new MediaRange(type, subtype, quality, parameters));
	}

	private static boolean isToken(@NonNull String string) {
		requireNonNull(string);

		if (string.isEmpty())
			return false;

		for (int i = 0; i < string.length(); i++) {
			char ch = string.charAt(i);

			if (!isTchar(ch))
				return false;
		}

		return true;
	}

	private static boolean isTchar(char ch) {
		if (ch >= '0' && ch <= '9')
			return true;

		if (ch >= 'A' && ch <= 'Z')
			return true;

		if (ch >= 'a' && ch <= 'z')
			return true;

		switch (ch) {
			case '!':
			case '#':
			case '$':
			case '%':
			case '&':
			case '\'':
			case '*':
			case '+':
			case '-':
			case '.':
			case '^':
			case '_':
			case '`':
			case '|':
			case '~':
				return true;
			default:
				return false;
		}
	}

	@NonNull
	private static String unquote(@NonNull String value) {
		requireNonNull(value);

		if (value.length() < 2 || value.charAt(0) != '"' || value.charAt(value.length() - 1) != '"')
			return value;

		String inner = value.substring(1, value.length() - 1);

		// Unescape RFC 9110 quoted-pair sequences (e.g. \" and \\); liberal in what we accept
		StringBuilder unescaped = new StringBuilder(inner.length());
		boolean escaped = false;

		for (int i = 0; i < inner.length(); i++) {
			char c = inner.charAt(i);

			if (escaped) {
				unescaped.append(c);
				escaped = false;
			} else if (c == '\\') {
				escaped = true;
			} else {
				unescaped.append(c);
			}
		}

		return unescaped.toString();
	}

	@NonNull
	private static BigDecimal clampQuality(@NonNull BigDecimal quality) {
		requireNonNull(quality);

		if (quality.compareTo(BigDecimal.ZERO) < 0)
			return BigDecimal.ZERO;

		if (quality.compareTo(BigDecimal.ONE) > 0)
			return BigDecimal.ONE;

		return quality;
	}

	@Override
	public boolean equals(@Nullable Object object) {
		if (this == object)
			return true;

		if (!(object instanceof MediaRange mediaRange))
			return false;

		// Quality is scale-normalized in the constructor, so plain equality is scale-insensitive
		return Objects.equals(getType(), mediaRange.getType())
				&& Objects.equals(getSubtype(), mediaRange.getSubtype())
				&& Objects.equals(getQuality(), mediaRange.getQuality())
				&& Objects.equals(getParameters(), mediaRange.getParameters());
	}

	@Override
	public int hashCode() {
		return Objects.hash(getType(), getSubtype(), getQuality(), getParameters());
	}

	@Override
	@NonNull
	public String toString() {
		return "%s{type=%s, subtype=%s, quality=%s, parameters=%s}".formatted(
				getClass().getSimpleName(), getType(), getSubtype(), getQuality(), getParameters());
	}

	/**
	 * The type portion of this media range (e.g. {@code text} in {@code text/html}), lowercase; may be the
	 * {@code *} wildcard.
	 *
	 * @return the media range's type
	 */
	@NonNull
	public String getType() {
		return this.type;
	}

	/**
	 * The subtype portion of this media range (e.g. {@code html} in {@code text/html}), lowercase; may be the
	 * {@code *} wildcard.
	 *
	 * @return the media range's subtype
	 */
	@NonNull
	public String getSubtype() {
		return this.subtype;
	}

	/**
	 * The {@code q} weight of this media range in the range {@code [0, 1]}, defaulting to {@code 1} when
	 * unspecified. A quality of {@code 0} means "not acceptable".
	 *
	 * @return the media range's quality weight
	 */
	@NonNull
	public BigDecimal getQuality() {
		return this.quality;
	}

	/**
	 * The media-type parameters of this media range (e.g. {@code level=1} in {@code text/html;level=1;q=0.7}),
	 * with lowercase names in their original order. Excludes the {@code q} parameter and any accept-ext
	 * parameters that follow it.
	 *
	 * @return an unmodifiable view of the media range's parameters, or an empty map if none were specified
	 */
	@NonNull
	public Map<@NonNull String, @NonNull String> getParameters() {
		return this.parameters;
	}

	/**
	 * Is this media range's type the {@code *} wildcard (i.e. {@code *}{@code /*})?
	 *
	 * @return {@code true} if the type is a wildcard, {@code false} otherwise
	 */
	@NonNull
	public Boolean isWildcardType() {
		return "*".equals(getType());
	}

	/**
	 * Is this media range's subtype the {@code *} wildcard (e.g. {@code text/*})?
	 *
	 * @return {@code true} if the subtype is a wildcard, {@code false} otherwise
	 */
	@NonNull
	public Boolean isWildcardSubtype() {
		return "*".equals(getSubtype());
	}
}
