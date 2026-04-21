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

import java.io.IOException;
import java.util.Optional;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * Thrown when a response stream has been canceled.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public class StreamingResponseCanceledException extends IOException {
	private static final long serialVersionUID = 1L;

	@NonNull
	private final StreamingResponseCancelationReason cancelationReason;

	/**
	 * Creates an exception with the given cancelation reason.
	 *
	 * @param cancelationReason the cancelation reason
	 */
	public StreamingResponseCanceledException(@NonNull StreamingResponseCancelationReason cancelationReason) {
		this(cancelationReason, null);
	}

	/**
	 * Creates an exception with the given cancelation reason and cause.
	 *
	 * @param cancelationReason the cancelation reason
	 * @param cancelationCause  the underlying cause, or {@code null} if unavailable
	 */
	public StreamingResponseCanceledException(@NonNull StreamingResponseCancelationReason cancelationReason,
																						@Nullable Throwable cancelationCause) {
		super(format("Streaming response was canceled: %s", requireNonNull(cancelationReason).name()), cancelationCause);
		this.cancelationReason = cancelationReason;
	}

	/**
	 * The cancelation reason.
	 *
	 * @return the cancelation reason
	 */
	@NonNull
	public StreamingResponseCancelationReason getCancelationReason() {
		return this.cancelationReason;
	}

	/**
	 * The underlying cancelation cause, if available.
	 *
	 * @return the underlying cancelation cause
	 */
	@NonNull
	public Optional<Throwable> getCancelationCause() {
		return Optional.ofNullable(getCause());
	}
}
