package com.soklet.internal.streaming;

import com.soklet.*;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class ManagedResponseStreamCancelationRaceTests {
	@Test void outputClosingDuringWritableCheckPreservesTheElectedReason() {
		Token token = new Token();
		List<Throwable> cleanupFailures = new ArrayList<>();
		ManagedResponseStream stream = stream(token, new ManagedResponseStream.Output() {
			@Override public void write(ByteBuffer bytes) { fail("Closed output accepted bytes"); }
			@Override public void flush() { fail("Closed output flushed"); }
			@Override public boolean isOpen() { token.canceled.set(true); return false; }
		}, cleanupFailures);
		StreamingResponseCanceledException failure = assertThrows(StreamingResponseCanceledException.class,
				() -> stream.run(writer -> writer.write(new byte[]{1})));
		assertEquals(StreamTerminationReason.CLIENT_DISCONNECTED, failure.getCancelationReason());
		assertTrue(cleanupFailures.isEmpty());
	}

	@Test void ownedTailWriteCanceledWhileWritingIsDiscardedAndResourceCloseReturns() throws Exception {
		for (boolean interrupted : List.of(false, true)) {
			Token token = new Token();
			List<Throwable> cleanupFailures = new ArrayList<>();
			AtomicInteger closes = new AtomicInteger();
			ManagedResponseStream stream = stream(token, new ManagedResponseStream.Output() {
				@Override public void write(ByteBuffer bytes) throws InterruptedException, StreamingResponseCanceledException {
					token.canceled.set(true);
					if (interrupted) throw new InterruptedException();
					token.throwIfCanceled();
				}
				@Override public void flush() {}
				@Override public boolean isOpen() { return !token.isCanceled(); }
			}, cleanupFailures);
			try {
				assertThrows(StreamingResponseCanceledException.class, () -> stream.run(writer -> writer.own((AutoCloseable) () -> {
					writer.write(new byte[]{1});
					closes.incrementAndGet();
				})));
				assertEquals(1, closes.get());
				assertTrue(cleanupFailures.isEmpty());
			} finally { Thread.interrupted(); }
		}
	}

	@Test void canceledJdkZipEntryDoesNotMisclassifyTheEndedDeflaterRetryAsIndependentCleanup() {
		Token token = new Token();
		List<Throwable> cleanupFailures = new ArrayList<>();
		ManagedResponseStream stream = stream(token, new ManagedResponseStream.Output() {
			@Override public void write(ByteBuffer bytes) throws StreamingResponseCanceledException {
				token.canceled.set(true);
				token.throwIfCanceled();
			}
			@Override public void flush() {}
			@Override public boolean isOpen() { return !token.isCanceled(); }
		}, cleanupFailures);
		assertThrows(StreamingResponseCanceledException.class, () -> stream.run(writer -> {
			ZipOutputStream zip = writer.own(new ZipOutputStream(writer.asOutputStream()));
			zip.putNextEntry(new ZipEntry("entry"));
			zip.write(new byte[]{1, 2, 3});
			zip.closeEntry();
		}));
		assertTrue(cleanupFailures.isEmpty(), cleanupFailures.toString());
	}

	private static ManagedResponseStream stream(Token token, ManagedResponseStream.Output output, List<Throwable> cleanup) {
		return new ManagedResponseStream(Request.fromPath(HttpMethod.GET, "/stream"), token, () -> false,
				null, null, output, () -> {}, ignored -> {}, cleanup::add);
	}
	private static final class Token implements CancelationToken {
		final AtomicBoolean canceled = new AtomicBoolean();
		@Override public Boolean isCanceled() { return canceled.get(); }
		@Override public Optional<StreamTerminationReason> getCancelationReason() {
			return canceled.get() ? Optional.of(StreamTerminationReason.CLIENT_DISCONNECTED) : Optional.empty();
		}
		@Override public Optional<Throwable> getCancelationCause() { return Optional.empty(); }
		@Override public CallbackRegistration onCancel(Runnable callback) { return () -> {}; }
	}
}
