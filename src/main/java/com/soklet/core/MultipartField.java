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

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

import static com.soklet.core.Utilities.trimAggressivelyToNull;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * Encapsulates an HTML form element name, binary and {@link String} representations of its value, and other attributes as encoded according to the <a href="https://datatracker.ietf.org/doc/html/rfc7578">{@code multipart/form-data}</a> specification.
 * <p>
 * Full documentation is available at <a href="https://www.soklet.com/docs/request-handling#multipart-form-data">https://www.soklet.com/docs/request-handling#multipart-form-data</a>.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class MultipartField {
	@Nonnull
	private static final Charset DEFAULT_CHARSET;

	static {
		DEFAULT_CHARSET = StandardCharsets.UTF_8;
	}

	@Nonnull
	private final String name;
	@Nullable
	private final byte[] data;
	@Nullable
	private String dataAsString;
	@Nullable
	private final String filename;
	@Nullable
	private final String contentType;
	@Nullable
	private final Charset charset;
	@Nonnull
	private final ReentrantLock lock;

	/**
	 * Acquires a builder for {@link MultipartField} instances.
	 *
	 * @param name the name of this field
	 * @return the builder
	 */
	@Nonnull
	public static Builder withName(@Nonnull String name) {
		requireNonNull(name);
		return new Builder(name);
	}

	/**
	 * Acquires a builder for {@link MultipartField} instances.
	 *
	 * @param name  the name of this field
	 * @param value the optional value for this field
	 * @return the builder
	 */
	@Nonnull
	public static Builder with(@Nonnull String name,
														 @Nullable byte[] value) {
		requireNonNull(name);
		return new Builder(name, value);
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

	protected MultipartField(@Nonnull Builder builder) {
		requireNonNull(builder);

		String name = trimAggressivelyToNull(builder.name);
		String filename = trimAggressivelyToNull(builder.filename);
		String contentType = trimAggressivelyToNull(builder.contentType);
		byte[] data = builder.data == null ? null : (builder.data.length == 0 ? null : builder.data);

		if (name == null)
			throw new IllegalArgumentException("Multipart field name is required");

		this.name = name;
		this.filename = filename;
		this.contentType = contentType;
		this.charset = builder.charset;
		this.data = data;
		this.lock = new ReentrantLock();
	}

	@Override
	public String toString() {
		return format("%s{name=%s, filename=%s, contentType=%s, data=%s}",
				getClass().getSimpleName(), getName(),
				getFilename().orElse("[not available]"),
				getContentType().orElse("[not available]"),
				(getData().isPresent()
						? (getFilename().isPresent() ? format("[%d bytes]", getData().get().length) : getDataAsString().orElse("[not available]"))
						: "[not available]"));
	}

	@Override
	public boolean equals(@Nullable Object object) {
		if (this == object)
			return true;

		if (!(object instanceof MultipartField multipartField))
			return false;

		return Objects.equals(getName(), multipartField.getName())
				&& Objects.equals(getFilename(), multipartField.getFilename())
				&& Objects.equals(getContentType(), multipartField.getContentType())
				&& Objects.equals(getCharset(), multipartField.getCharset())
				&& Objects.equals(getData(), multipartField.getData());
	}

	@Override
	public int hashCode() {
		return Objects.hash(getName(), getFilename(), getContentType(), getCharset(), getData());
	}

	/**
	 * Builder used to construct instances of {@link MultipartField} via {@link MultipartField#withName(String)}
	 * or {@link MultipartField#with(String, byte[])}.
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
		private byte[] data;
		@Nullable
		private String filename;
		@Nullable
		private String contentType;
		@Nullable
		private Charset charset;

		protected Builder(@Nonnull String name) {
			this(name, null);
		}

		protected Builder(@Nonnull String name,
											@Nullable byte[] data) {
			requireNonNull(name);
			requireNonNull(data);

			this.name = name;
			this.data = data;
		}

		@Nonnull
		public Builder name(@Nonnull String name) {
			requireNonNull(name);
			this.name = name;
			return this;
		}

		@Nonnull
		public Builder data(@Nullable byte[] data) {
			this.data = data;
			return this;
		}

		@Nonnull
		public Builder filename(@Nullable String filename) {
			this.filename = filename;
			return this;
		}

		@Nonnull
		public Builder contentType(@Nullable String contentType) {
			this.contentType = contentType;
			return this;
		}

		@Nonnull
		public Builder charset(@Nullable Charset charset) {
			this.charset = charset;
			return this;
		}

		@Nonnull
		public MultipartField build() {
			return new MultipartField(this);
		}
	}

	/**
	 * Builder used to copy instances of {@link MultipartField} via {@link MultipartField#copy()}.
	 * <p>
	 * This class is intended for use by a single thread.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static class Copier {
		@Nonnull
		private final Builder builder;

		Copier(@Nonnull MultipartField multipartField) {
			requireNonNull(multipartField);

			this.builder = new Builder(multipartField.getName(), multipartField.getData().orElse(null))
					.filename(multipartField.getFilename().orElse(null))
					.contentType(multipartField.getContentType().orElse(null))
					.charset(multipartField.getCharset().orElse(null));
		}

		@Nonnull
		public Copier name(@Nonnull String name) {
			requireNonNull(name);
			this.builder.name(name);
			return this;
		}

		@Nonnull
		public Copier data(@Nullable byte[] data) {
			this.builder.data(data);
			return this;
		}

		@Nonnull
		public Copier filename(@Nullable String filename) {
			this.builder.filename(filename);
			return this;
		}

		@Nonnull
		public Copier contentType(@Nullable String contentType) {
			this.builder.contentType(contentType);
			return this;
		}

		@Nonnull
		public Copier contentType(@Nullable Charset charset) {
			this.builder.charset(charset);
			return this;
		}

		@Nonnull
		public MultipartField finish() {
			return this.builder.build();
		}
	}

	/**
	 * The value of this field represented as a string, if available.
	 *
	 * @return the string value, or {@link Optional#empty()} if not available
	 */
	@Nonnull
	public Optional<String> getDataAsString() {
		// Lazily instantiate a string instance using double-checked locking
		if (this.data != null && this.dataAsString == null) {
			getLock().lock();
			try {
				if (this.data != null && this.dataAsString == null)
					this.dataAsString = new String(this.data, getCharset().orElse(DEFAULT_CHARSET));
			} finally {
				getLock().unlock();
			}
		}

		return Optional.ofNullable(this.dataAsString);
	}

	/**
	 * The name of this field.
	 *
	 * @return the name of this field
	 */
	@Nonnull
	public String getName() {
		return this.name;
	}

	/**
	 * The filename associated with this field, if available.
	 *
	 * @return the filename, or {@link Optional#empty()} if not available
	 */
	@Nonnull
	public Optional<String> getFilename() {
		return Optional.ofNullable(this.filename);
	}

	/**
	 * The content type for this field, if available (for example, {@code image/png} for an image file).
	 *
	 * @return the content type, or {@link Optional#empty()} if not available
	 */
	@Nonnull
	public Optional<String> getContentType() {
		return Optional.ofNullable(this.contentType);
	}

	/**
	 * The charset used to encode this field, if applicable.
	 *
	 * @return the charset, or {@link Optional#empty()} if not available
	 */
	@Nonnull
	public Optional<Charset> getCharset() {
		return Optional.ofNullable(this.charset);
	}

	/**
	 * The binary value of this field, if available.
	 *
	 * @return the binary value, or {@link Optional#empty()} if not available
	 */
	@Nonnull
	public Optional<byte[]> getData() {
		return Optional.ofNullable(this.data);
	}

	@Nonnull
	protected ReentrantLock getLock() {
		return this.lock;
	}
}
