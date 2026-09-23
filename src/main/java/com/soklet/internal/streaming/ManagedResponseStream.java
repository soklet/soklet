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

package com.soklet.internal.streaming;

import com.soklet.CallbackRegistration;
import com.soklet.CancelationToken;
import com.soklet.Request;
import com.soklet.ResponseStream;
import com.soklet.StreamResourceFactory;
import com.soklet.StreamTerminationReason;
import com.soklet.StreamingResponseCanceledException;
import com.soklet.StreamingResponseWriter;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static java.util.Objects.requireNonNull;
import static java.util.Objects.checkFromIndexSize;

/**
 * Shared producer-confined ownership and output lifetime. The enclosing runtime owns admission,
 * token dispatch, deadline supervision, and physical-work accounting; this class creates no workers.
 */
public final class ManagedResponseStream implements ResponseStream {
	/**
	 * The transport/simulator sink. Writes advance the supplied internal buffer by the prefix copied or accepted,
	 * including on interruption or failure; successful writes consume its remaining bytes. Public caller buffers
	 * are duplicated before reaching this interface.
	 */
	public interface Output {
		void write(@NonNull ByteBuffer byteBuffer) throws IOException, InterruptedException;
		void flush() throws IOException, InterruptedException;
		boolean isOpen();
		default int stagingCapacityInBytes() { return 8192; }
		/** Records scalar-byte acceptance; implementations may coalesce activity bookkeeping. */
		default void didStageBytes() {}
	}

	private enum Phase { NEW, ACTIVE, FINALIZING, CLOSED }
	private enum AbortMode { CLOSE, SEPARATE, NONE }

	private final Request request;
	private final CancelationToken cancelationToken;
	private final Instant deadline;
	private final Duration idleTimeout;
	private final Output output;
	private final Runnable beginFinalization;
	private final Consumer<Throwable> failureConsumer;
	private final Consumer<Throwable> cleanupFailureConsumer;
	private final AtomicBoolean finalizationStarted = new AtomicBoolean();
	private final Object failureLock = new Object();
	private final List<Owned<?>> owned = new ArrayList<>();
	private final IdentityHashMap<AutoCloseable, Owned<?>> identities = new IdentityHashMap<>();
	private volatile Phase phase = Phase.NEW;
	private volatile Thread owner;
	private volatile Throwable failure;
	private volatile Throwable pendingLexicalFailure;
	private int cleanupDepth;
	private byte[] staging;
	private int stagedBytes;

	public ManagedResponseStream(@NonNull Request request,
			@NonNull CancelationToken cancelationToken,
			@Nullable Instant deadline,
			@Nullable Duration idleTimeout,
			@NonNull Output output,
			@NonNull Runnable beginFinalization,
			@NonNull Consumer<Throwable> failureConsumer,
			@NonNull Consumer<Throwable> cleanupFailureConsumer) {
		this.request = requireNonNull(request);
		this.cancelationToken = requireNonNull(cancelationToken);
		this.deadline = deadline;
		this.idleTimeout = idleTimeout;
		this.output = requireNonNull(output);
		this.beginFinalization = requireNonNull(beginFinalization);
		this.failureConsumer = requireNonNull(failureConsumer);
		this.cleanupFailureConsumer = requireNonNull(cleanupFailureConsumer);
	}

	/** Executes once, retaining the producer obligation until all managed finalization has returned. */
	public void run(@NonNull StreamingResponseWriter streamingResponseWriter) throws Exception {
		requireNonNull(streamingResponseWriter);
		synchronized (this) {
			if (this.phase != Phase.NEW)
				throw new IllegalStateException("This response stream has already been run");
			this.owner = Thread.currentThread();
			this.phase = Phase.ACTIVE;
		}

		boolean restoreInterrupt = false;
		try {
			this.cancelationToken.throwIfCanceled();
			checkInterrupted();
			streamingResponseWriter.writeTo(this);
			checkInterrupted();
		} catch (Throwable throwable) {
			recordFailure(throwable);
		} finally {
			restoreInterrupt |= Thread.interrupted();
			this.phase = Phase.FINALIZING;
			startFinalization();
			try {
				closeFrom(0);
				if (this.failure == null && !this.cancelationToken.isCanceled()) {
					try {
						flush();
					} catch (Throwable throwable) {
						recordFailure(throwable);
					}
				}
			} finally {
				discardStaging();
				this.phase = Phase.CLOSED;
				restoreInterrupt |= Thread.interrupted();
				if (restoreInterrupt)
					Thread.currentThread().interrupt();
			}
		}
		rethrow(outcome());
	}

	@Override
	@NonNull
	public Request getRequest() { return this.request; }

	@Override
	@NonNull
	public CancelationToken getCancelationToken() { return this.cancelationToken; }

	@Override
	@NonNull
	public Optional<@NonNull Instant> getDeadline() { return Optional.ofNullable(this.deadline); }

	@Override
	@NonNull
	public Optional<@NonNull Duration> getIdleTimeout() { return Optional.ofNullable(this.idleTimeout); }

	@Override
	public void write(byte @NonNull [] bytes) throws IOException, InterruptedException {
		requireNonNull(bytes);
		writeInternal(ByteBuffer.wrap(bytes));
	}

	@Override
	public void write(byte @NonNull [] bytes, @NonNull Integer offset, @NonNull Integer length) throws IOException, InterruptedException {
		requireNonNull(bytes);
		requireNonNull(offset);
		requireNonNull(length);
		checkFromIndexSize(offset, length, bytes.length);
		writeInternal(ByteBuffer.wrap(bytes, offset, length));
	}

	@Override
	public void write(@NonNull ByteBuffer byteBuffer) throws IOException, InterruptedException {
		requireNonNull(byteBuffer);
		writeInternal(byteBuffer.duplicate());
	}

	private void writeInternal(ByteBuffer byteBuffer) throws IOException, InterruptedException {
		checkOutputLifetime();
		try {
			checkWritable();
			drainStaging();
			if (byteBuffer.hasRemaining())
				this.output.write(byteBuffer);
		} catch (InterruptedException interruptedException) {
			outputInterrupted(interruptedException);
		} catch (IOException | RuntimeException | Error throwable) {
			recordFailure(throwable);
			throw throwable;
		}
	}

	@Override
	@NonNull
	public OutputStream asOutputStream() {
		checkOutputLifetime();
		return new OutputView();
	}

	@Override
	public void flush() throws IOException, InterruptedException {
		checkOutputLifetime();
		try {
			checkWritable();
			drainStaging();
			this.output.flush();
		} catch (InterruptedException interruptedException) {
			outputInterrupted(interruptedException);
		} catch (IOException | RuntimeException | Error throwable) {
			recordFailure(throwable);
			throw throwable;
		}
	}

	private void writeScalar(int value) throws IOException, InterruptedException {
		checkOutputLifetime();
		try {
			checkWritable();
			if (this.staging == null) {
				int capacity = this.output.stagingCapacityInBytes();
				if (capacity <= 0)
					throw new IllegalStateException("Response output staging capacity must be positive");
				this.staging = new byte[capacity];
			}
			if (this.stagedBytes == this.staging.length)
				drainStaging();
			this.staging[this.stagedBytes++] = (byte) value;
			this.output.didStageBytes();
		} catch (InterruptedException interruptedException) {
			outputInterrupted(interruptedException);
		} catch (IOException | RuntimeException | Error throwable) {
			recordFailure(throwable);
			throw throwable;
		}
	}

	private void drainStaging() throws IOException, InterruptedException {
		if (this.stagedBytes == 0)
			return;
		// Failed drains are terminal. Their accepted prefix is never replayed, and the remainder is discarded.
		this.output.write(ByteBuffer.wrap(this.staging, 0, this.stagedBytes));
		this.stagedBytes = 0;
	}

	private void discardStaging() {
		this.staging = null;
		this.stagedBytes = 0;
	}

	private void outputInterrupted(InterruptedException interruptedException) throws IOException, InterruptedException {
		// Inspect the elected reason before recordFailure can classify an unclaimed interruption itself.
		try {
			this.cancelationToken.throwIfCanceled();
		} catch (StreamingResponseCanceledException canceledException) {
			Thread.currentThread().interrupt();
			addSuppressed(canceledException, interruptedException);
			recordFailure(canceledException);
			throw canceledException;
		}
		recordFailure(interruptedException);
		throw interruptedException;
	}

	private final class OutputView extends OutputStream {
		private boolean closed;

		@Override
		public void write(int value) throws IOException {
			checkLifetime();
			try {
				writeScalar(value);
			} catch (InterruptedException interruptedException) {
				throw interruptedIOException(interruptedException, 0);
			}
		}

		@Override
		public void write(byte[] bytes, int offset, int length) throws IOException {
			requireNonNull(bytes);
			checkFromIndexSize(offset, length, bytes.length);
			checkLifetime();
			ByteBuffer byteBuffer = ByteBuffer.wrap(bytes, offset, length);
			try {
				writeInternal(byteBuffer);
			} catch (InterruptedException interruptedException) {
				throw interruptedIOException(interruptedException, byteBuffer.position() - offset);
			}
		}

		@Override
		public void flush() throws IOException {
			checkLifetime();
			try {
				ManagedResponseStream.this.flush();
			} catch (InterruptedException interruptedException) {
				throw interruptedIOException(interruptedException, 0);
			}
		}

		@Override
		public void close() throws IOException {
			checkOwner();
			if (this.closed)
				return;
			try {
				flush();
			} finally {
				this.closed = true;
			}
		}

		private void checkLifetime() throws IOException {
			checkOwner();
			if (this.closed)
				throw new IOException("Response output view is closed");
			if (phase != Phase.ACTIVE && phase != Phase.FINALIZING)
				throw new IOException("Response output view is outside its lifetime");
		}
	}

	private static InterruptedIOException interruptedIOException(InterruptedException interruptedException, int acceptedBytes) {
		Thread.currentThread().interrupt();
		InterruptedIOException interruptedIOException = new InterruptedIOException("Response output was interrupted");
		interruptedIOException.bytesTransferred = acceptedBytes;
		interruptedIOException.initCause(interruptedException);
		return interruptedIOException;
	}

	@Override
	@NonNull
	public Boolean isOpen() {
		Phase currentPhase = this.phase;
		return (currentPhase == Phase.ACTIVE || currentPhase == Phase.FINALIZING)
				&& this.failure == null && !this.cancelationToken.isCanceled() && this.output.isOpen();
	}

	@Override
	@NonNull
	public <T extends AutoCloseable> T open(@NonNull StreamResourceFactory<? extends T> streamResourceFactory) throws Exception {
		return acquire(streamResourceFactory, AbortMode.CLOSE, null);
	}

	@Override
	@NonNull
	public <T extends AutoCloseable> T open(@NonNull StreamResourceFactory<? extends T> streamResourceFactory,
			@NonNull ResourceAborter<? super T> resourceAborter) throws Exception {
		return acquire(streamResourceFactory, AbortMode.SEPARATE, requireNonNull(resourceAborter));
	}

	@Override
	@NonNull
	public <T extends AutoCloseable> T own(@NonNull T resource) throws Exception {
		requireNonNull(resource);
		checkOwner();
		if (this.phase != Phase.ACTIVE || this.cleanupDepth != 0)
			throw new IllegalStateException("Resource adoption is outside its lifetime; ownership remains with the caller");
		return adopt(resource, AbortMode.NONE, null);
	}

	@Override
	public <T extends AutoCloseable> void using(@NonNull StreamResourceFactory<? extends T> streamResourceFactory,
			@NonNull ResourceConsumer<? super T> resourceConsumer) throws Exception {
		use(streamResourceFactory, AbortMode.CLOSE, null, resourceConsumer);
	}

	@Override
	public <T extends AutoCloseable> void using(@NonNull StreamResourceFactory<? extends T> streamResourceFactory,
			@NonNull ResourceAborter<? super T> resourceAborter,
			@NonNull ResourceConsumer<? super T> resourceConsumer) throws Exception {
		use(streamResourceFactory, AbortMode.SEPARATE, requireNonNull(resourceAborter), resourceConsumer);
	}

	private <T extends AutoCloseable> T acquire(StreamResourceFactory<? extends T> streamResourceFactory,
			AbortMode abortMode, ResourceAborter<? super T> resourceAborter) throws Exception {
		requireNonNull(streamResourceFactory);
		checkAcquisition();
		T resource = requireNonNull(streamResourceFactory.open(), "Resource factory returned null");
		return adopt(resource, abortMode, resourceAborter);
	}

	private <T extends AutoCloseable> T adopt(T resource, AbortMode abortMode,
			ResourceAborter<? super T> resourceAborter) throws Exception {
		if (this.identities.containsKey(resource))
			throw new IllegalArgumentException("Resource is already owned by this response stream");
		Owned<T> entry = new Owned<>(resource, abortMode, resourceAborter);
		Throwable invalid = acquisitionFailure();
		if (invalid != null) {
			if (this.phase != Phase.CLOSED)
				recordFailure(invalid);
			entry.finish(true);
			addSuppressed(invalid, entry.cleanupFailure);
			rethrow(invalid);
			throw new AssertionError("Unreachable");
		}

		this.owned.add(entry);
		this.identities.put(resource, entry);
		try {
			if (abortMode != AbortMode.NONE)
				entry.registration = this.cancelationToken.onCancel(entry::cancel);
			this.cancelationToken.throwIfCanceled();
		} catch (Throwable throwable) {
			recordFailure(throwable);
			rethrow(throwable);
		}
		return resource;
	}

	private <T extends AutoCloseable> void use(StreamResourceFactory<? extends T> streamResourceFactory,
			AbortMode abortMode, ResourceAborter<? super T> resourceAborter,
			ResourceConsumer<? super T> resourceConsumer) throws Exception {
		requireNonNull(streamResourceFactory);
		requireNonNull(resourceConsumer);
		checkAcquisition();
		int mark = this.owned.size();
		Throwable lexicalFailure = null;
		Throwable previousLexicalFailure = this.pendingLexicalFailure;
		try {
			resourceConsumer.accept(acquire(streamResourceFactory, abortMode, resourceAborter));
		} catch (Throwable throwable) {
			lexicalFailure = throwable;
			this.pendingLexicalFailure = throwable;
		} finally {
			try {
				closeFrom(mark);
			} finally {
				this.pendingLexicalFailure = previousLexicalFailure;
			}
		}
		Throwable outcome = outcome();
		if (outcome != null) {
			addSuppressed(outcome, lexicalFailure);
			rethrow(outcome);
		}
		rethrow(lexicalFailure);
	}

	private void checkOwner() {
		if (Thread.currentThread() != this.owner)
			throw new IllegalStateException("Response stream operations belong to the producer thread");
	}

	private void checkOutputLifetime() {
		checkOwner();
		if (this.phase != Phase.ACTIVE && this.phase != Phase.FINALIZING)
			throw new IllegalStateException("Response stream output is outside its lifetime");
	}

	private void checkWritable() throws IOException, InterruptedException {
		this.cancelationToken.throwIfCanceled();
		if (this.failure != null)
			throw new IOException("Response production has failed", this.failure);
		checkInterrupted();
		if (!this.output.isOpen())
			throw new IOException("Response output is no longer open");
	}

	private void checkAcquisition() throws Exception {
		checkOwner();
		rethrow(acquisitionFailure());
	}

	private Throwable acquisitionFailure() {
		if (this.phase != Phase.ACTIVE || this.cleanupDepth != 0)
			return new IllegalStateException("Resource acquisition is outside its lifetime");
		Throwable outcome = outcome();
		if (outcome != null)
			return outcome;
		return null;
	}

	private static void checkInterrupted() throws InterruptedException {
		if (Thread.currentThread().isInterrupted())
			throw new InterruptedException("Response producer is interrupted");
	}

	private void closeFrom(int mark) {
		this.cleanupDepth++;
		boolean restoreInterrupt = Thread.interrupted();
		try {
			while (this.owned.size() > mark) {
				Owned<?> entry = this.owned.remove(this.owned.size() - 1);
				AutoCloseable resource = entry.resource;
				try {
					entry.finish(false);
				} finally {
					this.identities.remove(resource);
				}
			}
		} finally {
			this.cleanupDepth--;
			restoreInterrupt |= Thread.interrupted();
			if (restoreInterrupt)
				Thread.currentThread().interrupt();
		}
	}

	private void startFinalization() {
		if (this.finalizationStarted.compareAndSet(false, true)) {
			try {
				this.beginFinalization.run();
			} catch (Throwable throwable) {
				recordFailure(throwable);
			}
		}
	}

	private void recordFailure(Throwable throwable) {
		if (this.phase == Phase.CLOSED)
			return;
		if (Thread.currentThread() == this.owner)
			discardStaging();
		boolean first;
		synchronized (this.failureLock) {
			first = this.failure == null;
			if (first)
				this.failure = throwable;
			else
				addSuppressed(this.failure, throwable);
		}
		if (first && this.phase != Phase.CLOSED) {
			startFinalization();
			try {
				this.failureConsumer.accept(throwable);
			} catch (Throwable hookFailure) {
				addSuppressed(throwable, hookFailure);
			}
		}
	}

	private void recordCleanupFailure(Throwable throwable) {
		if (this.phase != Phase.CLOSED) {
			Throwable lexicalFailure = this.pendingLexicalFailure;
			if (lexicalFailure != null) {
				addSuppressed(lexicalFailure, throwable);
				recordFailure(lexicalFailure);
			} else {
				recordFailure(throwable);
			}
		}
		try {
			this.cleanupFailureConsumer.accept(throwable);
		} catch (Throwable hookFailure) {
			addSuppressed(throwable, hookFailure);
		}
	}

	private Throwable outcome() {
		Throwable primary = this.failure;
		StreamTerminationReason reason = this.cancelationToken.getCancelationReason().orElse(null);
		if (reason != null && (reason != StreamTerminationReason.PRODUCER_FAILED || primary == null)) {
			StreamingResponseCanceledException canceled = new StreamingResponseCanceledException(reason,
					this.cancelationToken.getCancelationCause().orElse(null));
			addSuppressed(canceled, primary);
			return canceled;
		}
		return primary;
	}

	private final class Owned<T extends AutoCloseable> {
		private T resource;
		private final AbortMode abortMode;
		private ResourceAborter<? super T> resourceAborter;
		private CallbackRegistration registration;
		private boolean closeClaimed;
		private boolean closeFinished;
		private boolean abortClaimed;
		private boolean abortFinished;
		private Throwable cleanupFailure;

		private Owned(T resource, AbortMode abortMode, ResourceAborter<? super T> resourceAborter) {
			this.resource = resource;
			this.abortMode = abortMode;
			this.resourceAborter = resourceAborter;
		}

		private void cancel() {
			T claimedResource;
			ResourceAborter<? super T> claimedAborter;
			synchronized (this) {
				if (this.resource == null || this.closeFinished || this.abortMode == AbortMode.NONE)
					return;
				if (this.abortMode == AbortMode.CLOSE) {
					if (this.closeClaimed)
						return;
					this.closeClaimed = true;
				} else {
					if (this.abortClaimed)
						return;
					this.abortClaimed = true;
				}
				claimedResource = this.resource;
				claimedAborter = this.resourceAborter;
			}
			if (this.abortMode == AbortMode.CLOSE) {
				closeResource(claimedResource);
			} else {
				try {
					claimedAborter.abort(claimedResource);
				} catch (Throwable throwable) {
					cleanupFailed(throwable);
				} finally {
					synchronized (this) {
						this.abortFinished = true;
						notifyAll();
					}
				}
			}
		}

		private void finish(boolean forceAbort) {
			boolean restoreInterrupt = Thread.interrupted();
			try {
				if (forceAbort || cancelationToken.isCanceled())
					cancel();
				T claimedResource = null;
				synchronized (this) {
					while ((this.abortClaimed && !this.abortFinished) || (this.closeClaimed && !this.closeFinished)) {
						try {
							wait();
						} catch (InterruptedException ignored) {
							restoreInterrupt = true;
						}
					}
					if (!this.closeClaimed) {
						this.closeClaimed = true;
						claimedResource = this.resource;
					}
				}
				if (claimedResource != null)
					closeResource(claimedResource);
				CallbackRegistration currentRegistration = this.registration;
				if (currentRegistration != null) {
					try {
						currentRegistration.close();
					} catch (Throwable throwable) {
						cleanupFailed(throwable);
					}
				}
				synchronized (this) {
					while (this.abortClaimed && !this.abortFinished) {
						try {
							wait();
						} catch (InterruptedException ignored) {
							restoreInterrupt = true;
						}
					}
					this.resource = null;
					this.resourceAborter = null;
					this.registration = null;
				}
			} finally {
				restoreInterrupt |= Thread.interrupted();
				if (restoreInterrupt)
					Thread.currentThread().interrupt();
			}
		}

		private void closeResource(T claimedResource) {
			try {
				claimedResource.close();
			} catch (Throwable throwable) {
				cleanupFailed(throwable);
			} finally {
				synchronized (this) {
					this.closeFinished = true;
					notifyAll();
				}
			}
		}

		private void cleanupFailed(Throwable throwable) {
			if (throwable instanceof InterruptedException)
				Thread.currentThread().interrupt();
			synchronized (this) {
				if (this.cleanupFailure == null)
					this.cleanupFailure = throwable;
				else
					addSuppressed(this.cleanupFailure, throwable);
			}
			recordCleanupFailure(throwable);
		}
	}

	private static void addSuppressed(Throwable primary, Throwable secondary) {
		if (secondary == null || containsThrowable(primary, secondary) || containsThrowable(secondary, primary))
			return;
		primary.addSuppressed(secondary);
	}

	private static boolean containsThrowable(Throwable root, Throwable target) {
		IdentityHashMap<Throwable, Boolean> visited = new IdentityHashMap<>();
		Deque<Throwable> remaining = new ArrayDeque<>();
		remaining.push(root);
		while (!remaining.isEmpty()) {
			Throwable current = remaining.pop();
			if (current == target)
				return true;
			if (visited.put(current, Boolean.TRUE) != null)
				continue;
			Throwable cause = current.getCause();
			if (cause != null)
				remaining.push(cause);
			for (Throwable suppressed : current.getSuppressed())
				remaining.push(suppressed);
		}
		return false;
	}

	private static void rethrow(Throwable throwable) throws Exception {
		if (throwable instanceof Exception exception)
			throw exception;
		if (throwable instanceof Error error)
			throw error;
		if (throwable != null)
			throw new RuntimeException(throwable);
	}
}
