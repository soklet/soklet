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
import java.util.Objects;
import java.util.Optional;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * A loggable event that occurs during Soklet's internal processing.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class LogEvent {
	@Nonnull
	private final LogEventType logEventType;
	@Nonnull
	private final String message;
	@Nullable
	private final Throwable throwable;
	@Nullable
	private final Request request;
	@Nullable
	private final ResourceMethod resourceMethod;
	@Nullable
	private final MarshaledResponse marshaledResponse;

	@Nonnull
	public static Builder with(@Nonnull LogEventType logEventType,
														 @Nonnull String message) {
		requireNonNull(logEventType);
		requireNonNull(message);

		return new Builder(logEventType, message);
	}

	protected LogEvent(@Nonnull Builder builder) {
		requireNonNull(builder);

		this.logEventType = builder.logEventType;
		this.message = builder.message;
		this.throwable = builder.throwable;
		this.request = builder.request;
		this.resourceMethod = builder.resourceMethod;
		this.marshaledResponse = builder.marshaledResponse;
	}

	@Override
	@Nonnull
	public String toString() {
		return format("%s{logEventType=%s, message=%s, throwable=%s}", getClass().getSimpleName(),
				getLogEventType(), getMessage(), getThrowable().orElse(null));
	}

	@Override
	public boolean equals(@Nullable Object object) {
		if (this == object)
			return true;

		if (!(object instanceof LogEvent logEvent))
			return false;

		return Objects.equals(getLogEventType(), logEvent.getLogEventType())
				&& Objects.equals(getMessage(), logEvent.getMessage())
				&& Objects.equals(getThrowable(), logEvent.getThrowable())
				&& Objects.equals(getRequest(), logEvent.getRequest())
				&& Objects.equals(getResourceMethod(), logEvent.getResourceMethod())
				&& Objects.equals(getMarshaledResponse(), logEvent.getMarshaledResponse());
	}

	@Override
	public int hashCode() {
		return Objects.hash(getLogEventType(), getMessage(), getThrowable(), getRequest(), getResourceMethod(), getMarshaledResponse());
	}

	@Nonnull
	public LogEventType getLogEventType() {
		return this.logEventType;
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

	@Nonnull
	public Optional<MarshaledResponse> getMarshaledResponse() {
		return Optional.ofNullable(this.marshaledResponse);
	}

	/**
	 * Builder used to construct instances of {@link LogEvent}.
	 * <p>
	 * This class is intended for use by a single thread.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static class Builder {
		@Nonnull
		private LogEventType logEventType;
		@Nonnull
		private String message;
		@Nullable
		private Throwable throwable;
		@Nullable
		private Request request;
		@Nullable
		private ResourceMethod resourceMethod;
		@Nullable
		private MarshaledResponse marshaledResponse;

		protected Builder(@Nonnull LogEventType logEventType,
											@Nonnull String message) {
			requireNonNull(logEventType);
			requireNonNull(message);

			this.logEventType = logEventType;
			this.message = message;
		}

		@Nonnull
		public Builder logEventType(@Nonnull LogEventType logEventType) {
			requireNonNull(logEventType);
			this.logEventType = logEventType;
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
		public Builder marshaledResponse(@Nullable MarshaledResponse marshaledResponse) {
			this.marshaledResponse = marshaledResponse;
			return this;
		}

		@Nonnull
		public LogEvent build() {
			return new LogEvent(this);
		}
	}
}
