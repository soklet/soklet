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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import static java.util.Objects.requireNonNull;

/**
 * Per-dispatch feedback shared by a built-in transport writer and its lifecycle.
 * The exact internal Consumer exposes its channel privately. Opaque public
 * decorators retain the legacy stackless bridge, since they can replace every
 * request/result carrier or move callbacks to another thread.
 */
@ThreadSafe
final class HttpTransportResponseFeedback implements AutoCloseable {
	private final AtomicReference<@Nullable HttpTransportResponseOutcome> outcome =
			new AtomicReference<>(HttpTransportResponseOutcome.accepted());
	private final Object lifecycleConsumerLock = new Object();
	private volatile boolean lifecycleConsumerClaimed;

	@NonNull
	static HttpTransportResponseFeedback create() {
		return new HttpTransportResponseFeedback();
	}

	private HttpTransportResponseFeedback() {}

	void claimLifecycleConsumer() {
		synchronized (this.lifecycleConsumerLock) {
			this.lifecycleConsumerClaimed = true;
		}
	}

	@Nullable
	static HttpTransportResponseFeedback forConsumer(@NonNull Consumer<HttpRequestResult> consumer) {
		return consumer instanceof Writer writer ? writer.feedback : null;
	}

	@NonNull
	static Consumer<HttpRequestResult> fromWriter(@NonNull HttpTransportResponseFeedback feedback,
			@NonNull Function<HttpRequestResult, HttpTransportResponseOutcome> writer) {
		return new Writer(feedback, writer);
	}

	private static final class Writer implements Consumer<HttpRequestResult> {
		private final HttpTransportResponseFeedback feedback;
		private final Function<HttpRequestResult, HttpTransportResponseOutcome> writer;

		private Writer(@NonNull HttpTransportResponseFeedback feedback,
				@NonNull Function<HttpRequestResult, HttpTransportResponseOutcome> writer) {
			this.feedback = requireNonNull(feedback);
			this.writer = requireNonNull(writer);
		}

		@Override
		public void accept(HttpRequestResult requestResult) {
			HttpTransportResponseOutcome outcome = requireNonNull(this.writer.apply(requireNonNull(requestResult)));
			boolean recorded = this.feedback.record(outcome);
			switch (outcome) {
				case HttpTransportResponseOutcome.Accepted ignored -> {}
				case HttpTransportResponseOutcome.NotWritten notWritten -> notWritten.rethrowWriteFailure();
				case HttpTransportResponseOutcome.Replaced replacement -> {
					// Only the exact Consumer proves which lifecycle owns this channel.
					// Public wrappers may erase both request and result identities, so
					// preserve their legacy control boundary without ambient state.
					if (!recorded || !this.feedback.lifecycleConsumerClaimed)
						throw UnclaimedReplacement.fromOutcome(replacement);
					// Real write failures interrupt the direct callback immediately.
					replacement.rethrowWriteFailure();
				}
			}
		}
	}

	private boolean record(@NonNull HttpTransportResponseOutcome outcome) {
		return switch (outcome) {
			case HttpTransportResponseOutcome.Accepted ignored -> true;
			case HttpTransportResponseOutcome.Replaced replacement -> recordTerminalOutcome(replacement);
			case HttpTransportResponseOutcome.NotWritten notWritten -> recordTerminalOutcome(notWritten);
		};
	}

	private boolean recordTerminalOutcome(@NonNull HttpTransportResponseOutcome outcome) {
		// The first terminal outcome owns feedback. A closed channel cannot retain
		// late feedback; replacements use the bridge, and not-written failures are
		// always thrown at the Consumer boundary even without a claimed channel.
		HttpTransportResponseOutcome current = this.outcome.get();
		while (current instanceof HttpTransportResponseOutcome.Accepted
				&& !this.outcome.compareAndSet(current, outcome))
			current = this.outcome.get();
		return current != null;
	}

	@NonNull
	HttpTransportResponseOutcome finish() {
		HttpTransportResponseOutcome completed = this.outcome.getAndSet(null);
		return completed == null ? HttpTransportResponseOutcome.accepted() : completed;
	}

	static final class UnclaimedReplacement extends RuntimeException {
		private static final long serialVersionUID = 1L;
		private final HttpTransportResponseOutcome.Replaced outcome;

		@NonNull
		private static UnclaimedReplacement fromOutcome(HttpTransportResponseOutcome.@NonNull Replaced outcome) {
			return new UnclaimedReplacement(outcome);
		}

		private UnclaimedReplacement(HttpTransportResponseOutcome.@NonNull Replaced outcome) {
			super("HTTP transport replaced the response.", null, false, false);
			this.outcome = requireNonNull(outcome);
		}

		HttpTransportResponseOutcome.@NonNull Replaced getOutcome() {
			return this.outcome;
		}
	}

	@Override
	public void close() {
		// Also called by transports when admission rejects before Soklet can
		// consume the channel. A claimed lifecycle can outlive a non-joining
		// decorator's dispatch scope and owns cleanup through finish(). Serializing
		// claim and close prevents close from erasing an in-flight replacement.
		synchronized (this.lifecycleConsumerLock) {
			if (!this.lifecycleConsumerClaimed)
				this.outcome.set(null);
		}
	}
}
