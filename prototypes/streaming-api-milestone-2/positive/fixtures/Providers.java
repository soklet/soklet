package fixtures;

import com.soklet.CancelationToken;
import com.soklet.SseEvent;
import com.soklet.StreamTermination;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.util.Iterator;
import java.util.function.Consumer;

/**
 * Compile-only local provider contracts. These types do not represent a real
 * third-party SDK and provide no executable implementation. Their ownership and
 * concurrency promises are deliberate fixture assumptions, not properties that
 * Soklet can infer from AutoCloseable.
 */
public final class Providers {
	public TextStream stream(String prompt) throws ProviderException {
		throw compileOnly();
	}

	/** This provider observes the forwarded token while acquiring the stream too. */
	public TextStream stream(String prompt, CancelationToken cancelationToken) throws ProviderException {
		throw compileOnly();
	}

	/** Creates an inert handle before execute starts network acquisition. */
	public StreamingCall newStreamingCall(String prompt) throws ProviderException {
		throw compileOnly();
	}

	public Page openPage(int pageNumber) throws IOException {
		throw compileOnly();
	}

	public DetailedPage openDetailedPage() throws IOException {
		throw compileOnly();
	}

	/** The returned parser borrows the page; closing it does not close the page. */
	public RowParser openParser(Page page) throws IOException {
		throw compileOnly();
	}

	public AbortablePage openAbortablePage(int pageNumber) throws ProviderException {
		throw compileOnly();
	}

	public Subscription subscribe(String topic, Consumer<SseEvent> sseEventConsumer) throws IOException {
		throw compileOnly();
	}

	/** Supplies an input stream that supports close racing a blocked read. */
	public InputStream openInputStream() throws IOException {
		throw compileOnly();
	}

	/** Supplies a reader that supports close racing a blocked read. */
	public Reader openReader() throws IOException {
		throw compileOnly();
	}

	public void recordTermination(StreamTermination streamTermination) {
		throw compileOnly();
	}

	/** close safely aborts concurrent consumption and is physically attempted once. */
	public static final class TextStream implements Iterable<String>, AutoCloseable {
		@Override
		public Iterator<String> iterator() {
			throw compileOnly();
		}

		@Override
		public void close() throws IOException {
			throw compileOnly();
		}
	}

	/** Separate abort and final close; neither means the other operation occurred. */
	public interface AbortableResource extends AutoCloseable {
		void cancel() throws IOException;
		@Override void close() throws IOException;
	}

	/**
	 * cancel supports both cancel-before-execute and cancel-during-execute, and may
	 * race normal close. close does not also close an independently owned TextStream.
	 */
	public static final class StreamingCall implements AbortableResource {
		public TextStream execute() throws ProviderException {
			throw compileOnly();
		}

		@Override
		public void cancel() throws IOException {
			throw compileOnly();
		}

		@Override
		public void close() throws IOException {
			throw compileOnly();
		}
	}

	/** close safely aborts concurrent page consumption, including through a parser. */
	public static class Page implements Iterable<String>, AutoCloseable {
		public int getPageNumber() {
			throw compileOnly();
		}

		@Override
		public Iterator<String> iterator() {
			throw compileOnly();
		}

		@Override
		public void close() throws IOException {
			throw compileOnly();
		}
	}

	/** A concrete subtype used to verify that var retains factory return inference. */
	public static final class DetailedPage extends Page {
		public String getDetail() {
			throw compileOnly();
		}
	}

	/** Producer-thread-only finalization; deliberately neither owns nor closes Page. */
	public static final class RowParser implements Iterable<String>, AutoCloseable {
		@Override
		public Iterator<String> iterator() {
			throw compileOnly();
		}

		@Override
		public void close() throws IOException {
			throw compileOnly();
		}
	}

	/** cancel aborts reads and may race close; close is not used as the abort action. */
	public static final class AbortablePage implements AbortableResource, Iterable<String> {
		@Override
		public Iterator<String> iterator() {
			throw compileOnly();
		}

		@Override
		public void cancel() throws IOException {
			throw compileOnly();
		}

		@Override
		public void close() throws IOException {
			throw compileOnly();
		}
	}

	/** close/unsubscribe is safe against concurrent event-delivery callbacks. */
	public static final class Subscription implements AutoCloseable {
		@Override
		public void close() throws IOException {
			throw compileOnly();
		}
	}

	public static final class ProviderException extends Exception {
		private static final long serialVersionUID = 1L;
	}

	private static UnsupportedOperationException compileOnly() {
		return new UnsupportedOperationException("Compile-only provider fixture; do not execute");
	}
}
