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

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.util.Objects;
import java.util.Optional;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * An informational "loggable" event that occurs during Soklet's internal processing - for example, if an error occurs while handling a request.
 * <p>
 * These events are exposed via {@link LifecycleInterceptor#didReceiveLogEvent(LogEvent)}.
 * <p>
 * Instances can be acquired via the {@link #with(LogEventType, String)} builder factory method.
 * <p>
 * Documentation is available at <a href="https://www.soklet.com/docs/request-lifecycle#event-logging">https://www.soklet.com/docs/request-lifecycle#event-logging</a>.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class LogEvent {
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

	/**
	 * Acquires a builder for {@link LogEvent} instances.
	 *
	 * @param logEventType what kind of log event this is
	 * @param message      the message for this log event
	 * @return the builder
	 */
	@Nonnull
	public static Builder with(@Nonnull LogEventType logEventType,
														 @Nonnull String message) {
		requireNonNull(logEventType);
		requireNonNull(message);

		return new Builder(logEventType, message);
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

	/**
	 * The type of log event this is.
	 *
	 * @return the log event type
	 */
	@Nonnull
	public LogEventType getLogEventType() {
		return this.logEventType;
	}

	/**
	 * The message for this log event.
	 *
	 * @return the message
	 */
	@Nonnull
	public String getMessage() {
		return this.message;
	}

	/**
	 * The throwable for this log event, if available.
	 *
	 * @return the throwable, or {@link Optional#empty()} if not available
	 */
	@Nonnull
	public Optional<Throwable> getThrowable() {
		return Optional.ofNullable(this.throwable);
	}

	/**
	 * The request associated with this log event, if available.
	 *
	 * @return the request, or {@link Optional#empty()} if not available
	 */
	@Nonnull
	public Optional<Request> getRequest() {
		return Optional.ofNullable(this.request);
	}

	/**
	 * The <em>Resource Method</em> associated with this log event, if available.
	 *
	 * @return the <em>Resource Method</em>, or {@link Optional#empty()} if not available
	 */
	@Nonnull
	public Optional<ResourceMethod> getResourceMethod() {
		return Optional.ofNullable(this.resourceMethod);
	}

	/**
	 * The response associated with this log event, if available.
	 *
	 * @return the response, or {@link Optional#empty()} if not available
	 */
	@Nonnull
	public Optional<MarshaledResponse> getMarshaledResponse() {
		return Optional.ofNullable(this.marshaledResponse);
	}

	/**
	 * Builder used to construct instances of {@link LogEvent} via {@link LogEvent#with(LogEventType, String)}.
	 * <p>
	 * This class is intended for use by a single thread.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static final class Builder {
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

	/**
	 * Builder used to copy instances of {@link LogEvent} via {@link LogEvent#copy()}.
	 * <p>
	 * This class is intended for use by a single thread.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static final class Copier {
		@Nonnull
		private final Builder builder;

		Copier(@Nonnull LogEvent logEvent) {
			requireNonNull(logEvent);

			this.builder = new Builder(logEvent.getLogEventType(), logEvent.getMessage())
					.throwable(logEvent.getThrowable().orElse(null))
					.request(logEvent.getRequest().orElse(null))
					.resourceMethod(logEvent.getResourceMethod().orElse(null))
					.marshaledResponse(logEvent.getMarshaledResponse().orElse(null));
		}

		@Nonnull
		public Copier logEventType(@Nonnull LogEventType logEventType) {
			requireNonNull(logEventType);
			this.builder.logEventType(logEventType);
			return this;
		}

		@Nonnull
		public Copier message(@Nonnull String message) {
			requireNonNull(message);
			this.builder.message(message);
			return this;
		}

		@Nonnull
		public Copier throwable(@Nullable Throwable throwable) {
			this.builder.throwable(throwable);
			return this;
		}

		@Nonnull
		public Copier request(@Nullable Request request) {
			this.builder.request(request);
			return this;
		}

		@Nonnull
		public Copier resourceMethod(@Nullable ResourceMethod resourceMethod) {
			this.builder.resourceMethod(resourceMethod);
			return this;
		}

		@Nonnull
		public Copier marshaledResponse(@Nullable MarshaledResponse marshaledResponse) {
			this.builder.marshaledResponse(marshaledResponse);
			return this;
		}

		@Nonnull
		public LogEvent finish() {
			return this.builder.build();
		}
	}
}
