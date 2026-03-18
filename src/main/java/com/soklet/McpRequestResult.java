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
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * Result of simulator-mode MCP request handling.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public sealed interface McpRequestResult permits McpRequestResult.ResponseCompleted, McpRequestResult.StreamOpened {
	/**
	 * Provides the low-level Soklet request result associated with the simulated MCP request.
	 *
	 * @return the low-level request result
	 */
	@NonNull
	HttpRequestResult getHttpRequestResult();

	/**
	 * MCP request completed without leaving an open stream.
	 */
	@ThreadSafe
	final class ResponseCompleted implements McpRequestResult {
		@NonNull
		private final HttpRequestResult requestResult;

		ResponseCompleted(@NonNull HttpRequestResult requestResult) {
			requireNonNull(requestResult);
			this.requestResult = requestResult;
		}

		@NonNull
		@Override
		public HttpRequestResult getHttpRequestResult() {
			return this.requestResult;
		}
	}

	/**
	 * MCP request left an open simulated stream.
	 */
	@ThreadSafe
	final class StreamOpened implements McpRequestResult {
		@NonNull
		private final HttpRequestResult requestResult;
		@NonNull
		private final AtomicReference<@Nullable Consumer<Throwable>> streamErrorHandler;
		@NonNull
		private final ReentrantLock lock;
		@NonNull
		private final List<@NonNull McpObject> bufferedMessages;
		@NonNull
		private final Boolean closeAfterBufferedReplay;
		@NonNull
		private final AtomicBoolean closed;
		@NonNull
		private final AtomicReference<@Nullable Runnable> onClose;
		@Nullable
		private volatile Consumer<McpObject> messageConsumer;

		StreamOpened(@NonNull HttpRequestResult requestResult,
								 @Nullable AtomicReference<@Nullable Consumer<Throwable>> streamErrorHandler,
								 @NonNull Boolean closeAfterBufferedReplay) {
			requireNonNull(requestResult);
			requireNonNull(closeAfterBufferedReplay);

			this.requestResult = requestResult;
			this.streamErrorHandler = streamErrorHandler == null ? new AtomicReference<>() : streamErrorHandler;
			this.lock = new ReentrantLock();
			this.bufferedMessages = new CopyOnWriteArrayList<>();
			this.closeAfterBufferedReplay = closeAfterBufferedReplay;
			this.closed = new AtomicBoolean(false);
			this.onClose = new AtomicReference<>();
			this.messageConsumer = null;
		}

		/**
		 * Registers the consumer that should receive MCP messages from the open simulated stream.
		 *
		 * @param messageConsumer the message consumer
		 */
		public void registerMessageConsumer(@NonNull Consumer<McpObject> messageConsumer) {
			requireNonNull(messageConsumer);

			this.lock.lock();

			try {
				if (this.messageConsumer != null)
					throw new IllegalStateException(format("You cannot specify more than one message consumer for the same %s", StreamOpened.class.getSimpleName()));

				this.messageConsumer = messageConsumer;

				for (McpObject bufferedMessage : this.bufferedMessages) {
					try {
						messageConsumer.accept(bufferedMessage);
					} catch (Throwable throwable) {
						handleMessageConsumerError(throwable);
					}
				}

				this.bufferedMessages.clear();

				if (this.closeAfterBufferedReplay)
					terminate();
			} finally {
				this.lock.unlock();
			}
		}

		/**
		 * Simulates client-side closure of the open MCP stream.
		 */
		public void close() {
			if (!this.closed.compareAndSet(false, true))
				return;

			Runnable onClose = this.onClose.get();

			if (onClose != null)
				onClose.run();
		}

		/**
		 * Indicates whether the simulated stream has been closed.
		 *
		 * @return {@code true} if the stream is closed
		 */
		@NonNull
		public Boolean isClosed() {
			return this.closed.get();
		}

		@NonNull
		@Override
		public HttpRequestResult getHttpRequestResult() {
			return this.requestResult;
		}

		void emitMessage(@NonNull McpObject message) {
			requireNonNull(message);

			if (isClosed())
				return;

			Consumer<McpObject> messageConsumer = this.messageConsumer;

			if (messageConsumer == null) {
				this.bufferedMessages.add(message);
				return;
			}

			try {
				messageConsumer.accept(message);
			} catch (Throwable throwable) {
				handleMessageConsumerError(throwable);
			}
		}

		void terminate() {
			this.closed.set(true);
		}

		void onClose(@Nullable Runnable onClose) {
			this.onClose.set(onClose);
		}

		private void handleMessageConsumerError(@NonNull Throwable throwable) {
			requireNonNull(throwable);

			Consumer<Throwable> onStreamError = this.streamErrorHandler.get();

			if (onStreamError != null)
				onStreamError.accept(throwable);
			else
				throw new IllegalStateException("Unhandled exception thrown by MCP stream consumer", throwable);
		}

		@Override
		public boolean equals(@Nullable Object other) {
			if (this == other)
				return true;

			if (!(other instanceof StreamOpened streamOpened))
				return false;

			return getHttpRequestResult().equals(streamOpened.getHttpRequestResult())
					&& isClosed().equals(streamOpened.isClosed());
		}

		@Override
		public int hashCode() {
			return Objects.hash(getHttpRequestResult(), isClosed());
		}
	}
}
