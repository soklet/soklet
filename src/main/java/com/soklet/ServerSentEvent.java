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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * Encapsulates a <a href="https://developer.mozilla.org/en-US/docs/Web/API/Server-sent_events/Using_server-sent_events">Server-Sent Event</a> payload that can be sent across the wire to a client.
 * <p>
 * For example:
 * <pre>{@code  ServerSentEvent event = ServerSentEvent.withEvent("example")
 *   .data("""
 *     {
 *       "testing": 123,
 *       "value": "abc"
 *     }
 *     """)
 *   .id(UUID.randomUUID().toString())
 *   .retry(Duration.ofSeconds(5))
 *   .build();}</pre>
 * <p>
 * Threadsafe instances can be acquired via these builder factory methods:
 * <ul>
 *   <li>{@link #withEvent(String)} (builder primed with an event value)</li>
 *   <li>{@link #withData(String)} (builder primed with a data value)</li>
 *   <li>{@link #withDefaults()} ("empty" builder suitable for constructing special cases like {@code retry}-only or {@code id}-only events.)</li>
 * </ul>
 * <p>
 * See <a href="https://www.soklet.com/docs/server-sent-events">https://www.soklet.com/docs/server-sent-events</a> for detailed documentation.
 * <p>
 * Formal specification is available at <a href="https://html.spec.whatwg.org/multipage/server-sent-events.html#server-sent-events">https://html.spec.whatwg.org/multipage/server-sent-events.html#server-sent-events</a>.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class ServerSentEvent {
	@Nullable
	private final String id;
	@Nullable
	private final String event;
	@Nullable
	private final String data;
	@Nullable
	private final Duration retry;

	/**
	 * Acquires a builder for {@link ServerSentEvent} instances, seeded with an {@code event} value.
	 *
	 * @param event the {@code event} value for the instance
	 * @return the builder
	 */
	@Nonnull
	public static Builder withEvent(@Nullable String event) {
		return new Builder().event(event);
	}

	/**
	 * Acquires a builder for {@link ServerSentEvent} instances, seeded with a {@code data} value.
	 *
	 * @param data the {@code data} value for the instance
	 * @return the builder
	 */
	@Nonnull
	public static Builder withData(@Nullable String data) {
		return new Builder().data(data);
	}

	/**
	 * Acquires an "empty" builder for {@link ServerSentEvent} instances, useful for creating special cases like {@code retry}-only or {@code id}-only events.
	 *
	 * @return the builder
	 */
	@Nonnull
	public static Builder withDefaults() {
		return new Builder();
	}

	protected ServerSentEvent(@Nonnull Builder builder) {
		requireNonNull(builder);

		this.id = builder.id;
		this.event = builder.event;
		this.data = builder.data;
		this.retry = builder.retry;

		// Ensure legal construction

		if (this.retry != null && this.retry.isNegative())
			throw new IllegalArgumentException(format("%s 'retry' values must be non-negative. You supplied '%s'",
					ServerSentEvent.class.getSimpleName(), this.retry));

		if (this.event != null && containsLineBreaks(this.event))
			throw new IllegalArgumentException(format("%s 'event' values must not contain CR or LF characters. You supplied '%s'",
					ServerSentEvent.class.getSimpleName(), Utilities.printableString(this.event)));

		if (this.id != null && (containsLineBreaks(this.id) || this.id.contains("\u0000")))
			throw new IllegalArgumentException(format("%s 'id' values must not contain NUL (\\u0000), CR, or LF characters. You supplied '%s'",
					ServerSentEvent.class.getSimpleName(), Utilities.printableString(this.id)));
	}

	@Nonnull
	private Boolean containsLineBreaks(@Nonnull String string) {
		requireNonNull(string);
		return string.indexOf('\n') >= 0 || string.indexOf('\r') >= 0;
	}

	/**
	 * Builder used to construct instances of {@link ServerSentEvent} via {@link ServerSentEvent#withEvent(String)}, {@link ServerSentEvent#withData(String)}, or {@link ServerSentEvent#withDefaults()}.
	 * <p>
	 * This class is intended for use by a single thread.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static final class Builder {
		@Nullable
		private String id;
		@Nullable
		private String event;
		@Nullable
		private String data;
		@Nullable
		private Duration retry;

		protected Builder() {
			// Nothing to do
		}

		@Nonnull
		public Builder id(@Nullable String id) {
			this.id = id;
			return this;
		}

		@Nonnull
		public Builder event(@Nullable String event) {
			this.event = event;
			return this;
		}

		@Nonnull
		public Builder data(@Nullable String data) {
			this.data = data;
			return this;
		}

		@Nonnull
		public Builder retry(@Nullable Duration retry) {
			this.retry = retry;
			return this;
		}

		@Nonnull
		public ServerSentEvent build() {
			return new ServerSentEvent(this);
		}
	}

	@Override
	@Nonnull
	public String toString() {
		List<String> components = new ArrayList<>(4);

		if (this.event != null)
			components.add(format("event=%s", this.event));
		if (this.id != null)
			components.add(format("id=%s", this.id));
		if (this.retry != null)
			components.add(format("retry=%s", this.retry));
		if (this.data != null)
			components.add(format("data=%s", this.data.trim()));

		return format("%s{%s}", getClass().getSimpleName(), components.stream().collect(Collectors.joining(", ")));
	}

	/**
	 * The {@code id} for this Server-Sent Event, used by clients to populate the {@code Last-Event-ID} request header should a reconnect occur.
	 * <p>
	 * Formal specification is available at <a href="https://html.spec.whatwg.org/multipage/server-sent-events.html#server-sent-events">https://html.spec.whatwg.org/multipage/server-sent-events.html#server-sent-events</a>.
	 *
	 * @return the optional {@code id} for this Server-Sent Event
	 */
	@Nonnull
	public Optional<String> getId() {
		return Optional.ofNullable(this.id);
	}

	/**
	 * The {@code event} value for this Server-Sent Event.
	 * <p>
	 * Formal specification is available at <a href="https://html.spec.whatwg.org/multipage/server-sent-events.html#server-sent-events">https://html.spec.whatwg.org/multipage/server-sent-events.html#server-sent-events</a>.
	 *
	 * @return the optional {@code event} value for this Server-Sent Event
	 */
	@Nonnull
	public Optional<String> getEvent() {
		return Optional.ofNullable(this.event);
	}

	/**
	 * The {@code data} payload for this Server-Sent Event.
	 * <p>
	 * Formal specification is available at <a href="https://html.spec.whatwg.org/multipage/server-sent-events.html#server-sent-events">https://html.spec.whatwg.org/multipage/server-sent-events.html#server-sent-events</a>.
	 *
	 * @return the optional {@code data} payload for this Server-Sent Event
	 */
	@Nonnull
	public Optional<String> getData() {
		return Optional.ofNullable(this.data);
	}

	/**
	 * The {@code retry} duration for this Server-Sent Event.
	 * <p>
	 * Formal specification is available at <a href="https://html.spec.whatwg.org/multipage/server-sent-events.html#server-sent-events">https://html.spec.whatwg.org/multipage/server-sent-events.html#server-sent-events</a>.
	 *
	 * @return the optional {@code retry} duration for this Server-Sent Event
	 */
	@Nonnull
	public Optional<Duration> getRetry() {
		return Optional.ofNullable(this.retry);
	}
}
