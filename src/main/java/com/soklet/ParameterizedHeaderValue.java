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

package com.soklet;

import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * Represents a single HTTP header field value that includes a name and may include semicolon-delimited parameters - encoding rules per RFC specifications are strictly enforced.
 * <p>
 * Many HTTP header field values are of the form:
 * <pre>
 * name *( OWS ";" OWS parameter )
 * </pre>
 * where each {@code parameter} is a {@code name=value} pair.
 * <p>
 * This class provides a small builder that makes it easy to construct parameterized header values
 * using the formal HTTP grammar terms:
 * <ul>
 *   <li>{@link Builder#tokenParameter(String, String)} adds a parameter whose value is a {@code token} (RFC 9110).</li>
 *   <li>{@link Builder#quotedParameter(String, String)} adds a parameter whose value is a {@code quoted-string} (RFC 9110).</li>
 *   <li>{@link Builder#rfc8187Parameter(String, String)} adds an <em>extended parameter</em> ({@code name*=})
 *       whose value is an {@code ext-value} encoded per RFC 8187 (UTF-8, percent-encoded).</li>
 * </ul>
 * <p>
 * Example {@code Content-Disposition} header value:
 * <pre>{@code
 * String contentDisposition = ParameterizedHeaderValue.withName("attachment")
 *     .quotedParameter("filename", "resume.pdf")
 *     .rfc8187Parameter("filename", "résumé.pdf")
 *     .stringValue();
 *
 * // contentDisposition =>
 * // attachment; filename="resume.pdf"; filename*=UTF-8''r%C3%A9sum%C3%A9.pdf
 * }</pre>
 * <p>
 * The {@code name} must be ISO-8859-1 and must not contain the {@code ';'} parameter delimiter.
 * <p>
 * This class is immutable and thread-safe. The {@link Builder} is not thread-safe.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class ParameterizedHeaderValue {
	@NonNull
	private final String name;
	@NonNull
	private final List<Parameter> parameters;

	@NonNull
	public static Builder withName(@NonNull String value) {
		requireNonNull(value);
		return new Builder(value);
	}

	private ParameterizedHeaderValue(@NonNull Builder builder) {
		requireNonNull(builder);
		this.name = requireNonNull(builder.name);
		this.parameters = Collections.unmodifiableList(new ArrayList<>(builder.parameters));
	}

	/**
	 * Returns the name (non-parameter) portion of this header value.
	 */
	@NonNull
	public String getName() {
		return this.name;
	}

	/**
	 * Returns the HTTP <em>wire format</em> string for this header field value: the name followed by any
	 * semicolon-delimited parameters.
	 * <p>
	 * This is the official string form of {@link ParameterizedHeaderValue}. No guarantees are made about {@link #toString()}.
	 *
	 * @return the wire-format header field value
	 */
	@NonNull
	public String getStringValue() {
		return render(this.name, this.parameters);
	}

	/**
	 * Returns the parameters (including their kinds and unencoded values) that make up this header value.
	 * <p>
	 * The returned list is immutable.
	 */
	@NonNull
	public List<Parameter> getParameters() {
		return this.parameters;
	}

	/**
	 * Returns a debug representation of this instance and its internal state.
	 * <p>
	 * No wire-format or stability guarantees are made about this output; use {@link #getStringValue()} for the
	 * wire-format header field value.
	 */
	@Override
	@NonNull
	public String toString() {
		return format("ParameterizedHeaderValue{name=%s, parameters=%s}", getName(), getParameters());
	}

	@Override
	public boolean equals(Object other) {
		if (this == other)
			return true;
		if (!(other instanceof ParameterizedHeaderValue))
			return false;

		ParameterizedHeaderValue that = (ParameterizedHeaderValue) other;
		return this.name.equals(that.name)
				&& this.parameters.equals(that.parameters);
	}

	@Override
	public int hashCode() {
		return Objects.hash(this.name, this.parameters);
	}

	@NonNull
	private static String render(@NonNull String value,
															 @NonNull List<Parameter> parameters) {
		requireNonNull(value);
		requireNonNull(parameters);

		if (parameters.isEmpty())
			return value;

		StringBuilder sb = new StringBuilder(value.length() + (parameters.size() * 16));
		sb.append(value);

		for (Parameter parameter : parameters)
			sb.append("; ").append(parameter.getEncodedFragment());

		return sb.toString();
	}

	/**
	 * A single header-value parameter: given a header value like {@code attachment; filename="resume.pdf"; filename*=UTF-8''r%C3%A9sum%C3%A9.pdf}, there are two {@code filename} parameter name-value pairs.
	 */
	@ThreadSafe
	public static final class Parameter {
		/**
		 * What kind of header-value parameter this is: {@link #TOKEN}, {@link #QUOTED}, or {@link #RFC_8187}.
		 */
		public enum Kind {
			/**
			 * {@code name=value} where value is an HTTP token (RFC 9110).
			 */
			TOKEN,
			/**
			 * {@code name="value"} where value is an HTTP quoted-string (RFC 9110).
			 */
			QUOTED,
			/**
			 * {@code name*=UTF-8''...} where value is an RFC 8187 ext-value.
			 */
			RFC_8187
		}

		@NonNull
		private final Kind kind;
		@NonNull
		private final String name;
		@NonNull
		private final String value; // unencoded/original value (for debugging/state)
		@NonNull
		private final String encodedFragment; // already encoded "name=value" or "name*=ext-value"

		private Parameter(@NonNull Kind kind,
											@NonNull String name,
											@NonNull String value,
											@NonNull String encodedFragment) {
			this.kind = requireNonNull(kind);
			this.name = requireNonNull(name);
			this.value = requireNonNull(value);
			this.encodedFragment = requireNonNull(encodedFragment);
		}

		/**
		 * Gets the kind of this parameter.
		 *
		 * @return the parameter kind
		 */
		@NonNull
		public Kind getKind() {
			return kind;
		}

		/**
		 * Gets the parameter name.
		 *
		 * @return the parameter name
		 */
		@NonNull
		public String getName() {
			return name;
		}

		/**
		 * Gets the unencoded/original parameter value.
		 *
		 * @return the parameter value
		 */
		@NonNull
		public String getValue() {
			return value;
		}

		/**
		 * Gets the encoded fragment in wire format (e.g. {@code name=value} or {@code name*=...}).
		 *
		 * @return the encoded parameter fragment
		 */
		@NonNull
		String getEncodedFragment() {
			return encodedFragment;
		}

		@Override
		@NonNull
		public String toString() {
			return "Parameter{"
					+ "kind=" + kind
					+ ", name=" + name
					+ ", value=" + value
					+ ", encodedFragment=" + encodedFragment
					+ '}';
		}

		@Override
		public boolean equals(Object other) {
			if (this == other)
				return true;
			if (!(other instanceof Parameter))
				return false;

			Parameter that = (Parameter) other;
			return this.kind == that.kind
					&& this.name.equals(that.name)
					&& this.value.equals(that.value)
					&& this.encodedFragment.equals(that.encodedFragment);
		}

		@Override
		public int hashCode() {
			return Objects.hash(kind, name, value, encodedFragment);
		}
	}

	/**
	 * Builder used to construct instances of {@link ParameterizedHeaderValue} via {@link ParameterizedHeaderValue#withName(String)}.
	 * <p>
	 * This class is intended for use by a single thread.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static final class Builder {
		@NonNull
		private final String name;
		@NonNull
		private final List<Parameter> parameters;

		private Builder(@NonNull String name) {
			requireNonNull(name);
			this.name = sanitizeValue(name);
			this.parameters = new ArrayList<>();
		}

		/**
		 * Adds a parameter whose value is encoded as an HTTP {@code token} (RFC 9110).
		 * <p>
		 * Both the parameter name and value must be valid {@code token}s.
		 * This method fails fast if {@code name} or {@code value} are invalid.
		 *
		 * @param name  parameter name (token)
		 * @param value parameter value (token)
		 * @return this builder
		 * @throws IllegalArgumentException if {@code name} or {@code value} are not valid tokens or contain control chars
		 */
		@NonNull
		public Builder tokenParameter(@NonNull String name,
																	@NonNull String value) {
			requireNonNull(name);
			requireNonNull(value);

			String n = sanitizeParameterName(name);
			String v = sanitizeTokenValue(value);

			String encoded = n + "=" + v;
			this.parameters.add(new Parameter(Parameter.Kind.TOKEN, n, v, encoded));
			return this;
		}

		/**
		 * Adds a parameter whose value is encoded as an HTTP {@code quoted-string} (RFC 9110).
		 * <p>
		 * The parameter name must be a valid {@code token}. The value must be ASCII and must not contain control
		 * characters. Double-quotes and backslashes are escaped as required for {@code quoted-string}.
		 * <p>
		 * This method fails fast if illegal data is provided. For non-ASCII values, use {@link #rfc8187Parameter(String, String)}.
		 *
		 * @param name  parameter name (token)
		 * @param value parameter value (quoted-string content)
		 * @return this builder
		 * @throws IllegalArgumentException if {@code name} is not a valid token or {@code value} is non-ASCII or contains control chars
		 */
		@NonNull
		public Builder quotedParameter(@NonNull String name,
																	 @NonNull String value) {
			requireNonNull(name);
			requireNonNull(value);

			String n = sanitizeParameterName(name);
			String v = sanitizeQuotedValue(value); // fail-fast (ASCII, no CTLs)

			String encodedValue = encodeQuotedString(v);
			String encoded = n + "=" + encodedValue;

			this.parameters.add(new Parameter(Parameter.Kind.QUOTED, n, v, encoded));
			return this;
		}

		/**
		 * Adds an <em>extended parameter</em> (denoted by the {@code *} suffix on the parameter name) whose value is
		 * encoded as an RFC 8187 {@code ext-value} (UTF-8, percent-encoded).
		 * <p>
		 * This produces a fragment of the form:
		 * <pre>
		 * name*=UTF-8''percent-encoded-value
		 * </pre>
		 * where the percent-encoded bytes are the UTF-8 encoding of {@code value}.
		 *
		 * @param name  parameter name (token). The {@code *} is appended automatically.
		 * @param value parameter value to encode as an RFC 8187 {@code ext-value}
		 * @return this builder
		 * @throws IllegalArgumentException if {@code name} is not a valid token or {@code value} contains control chars
		 */
		@NonNull
		public Builder rfc8187Parameter(@NonNull String name,
																		@NonNull String value) {
			requireNonNull(name);
			requireNonNull(value);

			String n = sanitizeParameterName(name);
			if (n.indexOf('*') != -1)
				throw new IllegalArgumentException("RFC 8187 parameter name must not contain '*': " + n);
			String v = sanitizeRfc8187Value(value);

			String extValue = encodeRfc8187ExtValue(v);
			String encoded = n + "*=" + extValue;

			this.parameters.add(new Parameter(Parameter.Kind.RFC_8187, n, v, encoded));
			return this;
		}

		/**
		 * Builds an immutable {@link ParameterizedHeaderValue}.
		 */
		@NonNull
		public ParameterizedHeaderValue build() {
			return new ParameterizedHeaderValue(this);
		}

		/**
		 * Returns the HTTP wire-format string for this header field value.
		 * <p>
		 * This is equivalent to {@code build().getStringValue()} but avoids creating an intermediate {@link ParameterizedHeaderValue}.
		 *
		 * @return the wire-format header field value
		 */
		@NonNull
		public String stringValue() {
			return ParameterizedHeaderValue.render(this.name, this.parameters);
		}

		/* --------------------------- internals --------------------------- */

		@NonNull
		private static String sanitizeValue(@NonNull String string) {
			requireNonNull(string);

			// We don't attempt to fully validate "primary-value" because its grammar is header-specific.
			// We do enforce a security baseline: no control characters.
			assertNoControlCharacters(string, "value");

			String trimmed = string.trim();

			if (trimmed.isEmpty())
				throw new IllegalArgumentException("Value must not be empty");

			for (int i = 0; i < trimmed.length(); i++) {
				char ch = trimmed.charAt(i);
				if (ch > 0xFF)
					throw new IllegalArgumentException("Non-Latin-1 character not permitted in value");
				if (ch == ';')
					throw new IllegalArgumentException("Value must not contain ';' parameter delimiters");
			}

			return trimmed;
		}

		@NonNull
		private static String sanitizeParameterName(@NonNull String name) {
			requireNonNull(name);

			assertNoControlCharacters(name, "name");

			String trimmed = name.trim();

			if (trimmed.isEmpty())
				throw new IllegalArgumentException("Parameter name must not be empty");

			if (!isToken(trimmed))
				throw new IllegalArgumentException("Invalid parameter name token: " + trimmed);

			return trimmed;
		}

		@NonNull
		private static String sanitizeTokenValue(@NonNull String value) {
			requireNonNull(value);

			assertNoControlCharacters(value, "value");

			String trimmed = value.trim();

			if (trimmed.isEmpty())
				throw new IllegalArgumentException("Token value must not be empty");

			if (!isToken(trimmed))
				throw new IllegalArgumentException("Invalid token value: " + trimmed);

			return trimmed;
		}

		@NonNull
		private static String sanitizeQuotedValue(@NonNull String value) {
			requireNonNull(value);

			// Fail-fast: quoted-string values should be ASCII and must not include CTLs.
			assertNoControlCharacters(value, "value");

			for (int i = 0; i < value.length(); i++) {
				char ch = value.charAt(i);

				// Disallow non-ASCII.
				if (ch > 0x7E)
					throw new IllegalArgumentException("Non-ASCII character not permitted in quoted-string; use rfc8187() instead");

				// We also disallow other ASCII control characters already handled by assertNoControlCharacters().
				// Here we allow SP (0x20) through '~' (0x7E).
				if (ch < 0x20)
					throw new IllegalArgumentException("Control character not permitted in quoted-string; use rfc8187() if needed");
			}

			return value;
		}

		@NonNull
		private static String sanitizeRfc8187Value(@NonNull String value) {
			// RFC 8187 values are encoded as UTF-8 bytes + percent-encoding.
			// We still fail-fast on ASCII control characters to prevent header injection.
			assertNoControlCharacters(value, "value");
			return value;
		}

		@NonNull
		private static String encodeQuotedString(@NonNull String value) {
			// RFC 9110 quoted-string: DQUOTE *( qdtext / quoted-pair ) DQUOTE
			// We implement a safe subset: escape backslash and double quote.
			String escaped = value
					.replace("\\", "\\\\")
					.replace("\"", "\\\"");

			return "\"" + escaped + "\"";
		}

		@NonNull
		private static String encodeRfc8187ExtValue(@NonNull String value) {
			requireNonNull(value);

			// RFC 8187 ext-value: charset "'" [ language ] "'" value-chars
			// We always use UTF-8 and omit language (empty).
			byte[] bytes = value.getBytes(StandardCharsets.UTF_8);

			StringBuilder sb = new StringBuilder("UTF-8''".length() + bytes.length * 3);
			sb.append("UTF-8''");

			for (byte b : bytes) {
				int c = b & 0xFF;

				if (isAttrChar(c)) {
					sb.append((char) c);
				} else {
					sb.append('%');
					sb.append(HEX[(c >>> 4) & 0x0F]);
					sb.append(HEX[c & 0x0F]);
				}
			}

			return sb.toString();
		}

		private static void assertNoControlCharacters(@NonNull String string,
																									@NonNull String fieldName) {
			requireNonNull(string);
			requireNonNull(fieldName);

			for (int i = 0; i < string.length(); i++) {
				char ch = string.charAt(i);
				// Disallow ASCII CTLs (including CR/LF) and DEL.
				if (ch <= 0x1F || ch == 0x7F)
					throw new IllegalArgumentException("Control character not permitted in " + fieldName + " (index " + i + ")");
			}
		}

		/**
		 * Returns {@code true} if {@code string} is an HTTP {@code token} per RFC 9110.
		 */
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

		/**
		 * RFC 9110 tchar:
		 * "!" / "#" / "$" / "%" / "&" / "'" / "*" / "+" / "-" / "." / "^" / "_" / "`" / "|" / "~" / DIGIT / ALPHA
		 */
		private static boolean isTchar(char ch) {
			if (ch >= '0' && ch <= '9') return true;
			if (ch >= 'A' && ch <= 'Z') return true;
			if (ch >= 'a' && ch <= 'z') return true;

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

		/**
		 * RFC 8187 attr-char:
		 * ALPHA / DIGIT / "!" / "#" / "$" / "&" / "+" / "-" / "." / "^" / "_" / "`" / "|" / "~"
		 */
		private static boolean isAttrChar(int c) {
			// ALPHA
			if (c >= 'A' && c <= 'Z') return true;
			if (c >= 'a' && c <= 'z') return true;
			// DIGIT
			if (c >= '0' && c <= '9') return true;

			return c == '!' || c == '#' || c == '$' || c == '&' || c == '+'
					|| c == '-' || c == '.' || c == '^' || c == '_' || c == '`'
					|| c == '|' || c == '~';
		}

		private static final char[] HEX = "0123456789ABCDEF".toCharArray();
	}
}
