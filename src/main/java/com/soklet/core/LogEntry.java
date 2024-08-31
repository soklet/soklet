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
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class LogEntry {
	@Nonnull
	private final LogEntryType logEntryType;
	@Nonnull
	private final String message;
	@Nullable
	private final Throwable throwable;
	@Nullable
	private final Request request;
	@Nullable
	private final ResourceMethod resourceMethod;

	@Nonnull
	public static Builder with(@Nonnull LogEntryType logEntryType,
														 @Nonnull String message) {
		requireNonNull(logEntryType);
		requireNonNull(message);

		return new Builder(logEntryType, message);
	}

	protected LogEntry(@Nonnull Builder builder) {
		requireNonNull(builder);
		this.logEntryType = builder.logEntryType;
		this.message = builder.message;
		this.throwable = builder.throwable;
		this.request = builder.request;
		this.resourceMethod = builder.resourceMethod;
	}

	@Nonnull
	public LogEntryType getLogEntryType() {
		return this.logEntryType;
	}

	@Nonnull
	public String getMessage() {
		return this.message;
	}

	@Nonnull
	public Optional<Throwable> getThrowable() {
		return Optional.ofNullable(this.throwable);
	}

	@Nonnull
	public Optional<Request> getRequest() {
		return Optional.ofNullable(this.request);
	}

	@Nonnull
	public Optional<ResourceMethod> getResourceMethod() {
		return Optional.ofNullable(this.resourceMethod);
	}

	/**
	 * Builder used to construct instances of {@link LogEntry}.
	 * <p>
	 * This class is intended for use by a single thread.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static class Builder {
		@Nonnull
		private LogEntryType logEntryType;
		@Nonnull
		private String message;
		@Nullable
		private Throwable throwable;
		@Nullable
		private Request request;
		@Nullable
		private ResourceMethod resourceMethod;

		protected Builder(@Nonnull LogEntryType logEntryType,
											@Nonnull String message) {
			requireNonNull(logEntryType);
			requireNonNull(message);

			this.logEntryType = logEntryType;
			this.message = message;
		}

		@Nonnull
		public Builder logEntryType(@Nonnull LogEntryType logEntryType) {
			requireNonNull(logEntryType);
			this.logEntryType = logEntryType;
			return this;
		}

		@Nonnull
		public Builder message(@Nonnull String message) {
			requireNonNull(message);
			this.message = message;
			return this;
		}

		@Nonnull
		public Builder throwable(@Nullable Throwable throwable) {
			this.throwable = throwable;
			return this;
		}

		@Nonnull
		public Builder request(@Nullable Request request) {
			this.request = request;
			return this;
		}

		@Nonnull
		public Builder resourceMethod(@Nullable ResourceMethod resourceMethod) {
			this.resourceMethod = resourceMethod;
			return this;
		}

		@Nonnull
		public LogEntry build() {
			return new LogEntry(this);
		}
	}
}
