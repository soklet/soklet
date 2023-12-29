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
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

import static com.soklet.core.Utilities.trimAggressivelyToNull;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class MultipartField {
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
	@Nonnull
	private final ReentrantLock lock;

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
				&& Objects.equals(getData(), multipartField.getData());
	}

	@Override
	public int hashCode() {
		return Objects.hash(getName(), getFilename(), getContentType(), getData());
	}

	/**
	 * Builder used to construct instances of {@link MultipartField}.
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
		private final byte[] data;
		@Nullable
		private String filename;
		@Nullable
		private String contentType;

		public Builder(@Nonnull String name) {
			this(name, null);
		}

		public Builder(@Nonnull String name,
									 @Nullable byte[] data) {
			requireNonNull(name);
			requireNonNull(data);

			this.name = name;
			this.data = data;
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
		public MultipartField build() {
			return new MultipartField(this);
		}
	}

	@Nonnull
	public Optional<String> getDataAsString() {
		// Lazily instantiate a string instance using double-checked locking
		if (this.data != null && this.dataAsString == null) {
			getLock().lock();
			try {
				if (this.data != null && this.dataAsString == null)
					this.dataAsString = new String(this.data, StandardCharsets.UTF_8);
			} finally {
				getLock().unlock();
			}
		}

		return Optional.ofNullable(this.dataAsString);
	}

	@Nonnull
	public String getName() {
		return this.name;
	}

	@Nonnull
	public Optional<String> getFilename() {
		return Optional.ofNullable(this.filename);
	}

	@Nonnull
	public Optional<String> getContentType() {
		return Optional.ofNullable(this.contentType);
	}

	@Nonnull
	public Optional<byte[]> getData() {
		return Optional.ofNullable(this.data);
	}

	@Nonnull
	protected ReentrantLock getLock() {
		return this.lock;
	}
}
