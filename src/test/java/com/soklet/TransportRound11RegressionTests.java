package com.soklet;

import com.soklet.annotation.SseEventSource;
import com.soklet.internal.microhttp.MicrohttpResponse;
import com.soklet.internal.microhttp.StreamingMicrohttpResponses;
import com.soklet.internal.microhttp.WritableSource;
import com.soklet.internal.microhttp.StreamLifecycleCoordinator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketOption;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Bounds memory/input work and preserves wire framing without depending on socket-buffer sizes. */
@Timeout(60)
class TransportRound11RegressionTests {
	@Test void tinyWritesCoalesceAndKeepTheirExactPayload() throws Exception {
		CountDownLatch produced = new CountDownLatch(1);
		try (QueueFixture fixture = new QueueFixture(4096, 1024, stream -> {
			for (int i = 0; i < 4096; i++) stream.write(new byte[]{(byte) ('a' + i % 26)});
			stream.flush(); produced.countDown();
		})) {
			fixture.source.start();
			assertTrue(produced.await(2, TimeUnit.SECONDS));
			assertTrue(fixture.chunkCount() <= 5, "Tiny writes must not retain one framing object per byte");
			MemoryChannel channel = new MemoryChannel(0);
			fixture.drain(channel);
			StringBuilder expected = new StringBuilder();
			for (int i = 0; i < 4096; i++) expected.append((char) ('a' + i % 26));
			assertEquals(expected.toString(), decodedChunks(channel.output.toByteArray()));
		}
	}

	@Test void explicitOneByteChunksHaveASeparateMetadataBoundAndCancelPromptly() throws Exception {
		AtomicInteger accepted = new AtomicInteger();
		CountDownLatch atBound = new CountDownLatch(1);
		try (QueueFixture fixture = new QueueFixture(1_048_576, 1, stream -> {
			for (int i = 0; i < 100_000; i++) {
				stream.write(new byte[]{'x'});
				if (accepted.incrementAndGet() == 4096) atBound.countDown();
			}
		})) {
			fixture.source.start();
			assertTrue(atBound.await(2, TimeUnit.SECONDS));
			Thread.sleep(100);
			assertEquals(4096, accepted.get(), "Payload bytes alone must not admit unlimited framing objects");
			assertEquals(4096, fixture.chunkCount());
			fixture.source.close(StreamTerminationReason.APPLICATION_CANCELED, null);
		}
	}

	@Test void appendingPendingDataCannotRewriteAnInFlightChunkHeader() throws Exception {
		CountDownLatch first = new CountDownLatch(1), continueProducer = new CountDownLatch(1), produced = new CountDownLatch(1);
		try (QueueFixture fixture = new QueueFixture(64, 16, stream -> {
			stream.write("abc".getBytes(StandardCharsets.US_ASCII)); stream.flush(); first.countDown();
			assertTrue(continueProducer.await(2, TimeUnit.SECONDS));
			stream.write("d".getBytes(StandardCharsets.US_ASCII));
			stream.write("ef".getBytes(StandardCharsets.US_ASCII)); stream.flush(); produced.countDown();
		})) {
			try {
				fixture.source.start(); assertTrue(first.await(2, TimeUnit.SECONDS));
				MemoryChannel channel = new MemoryChannel(0);
				assertEquals(2, fixture.source.writeTo(channel, 2));
				assertEquals("3\r", channel.output.toString(StandardCharsets.US_ASCII));
				continueProducer.countDown(); assertTrue(produced.await(2, TimeUnit.SECONDS));
				fixture.drain(channel);
				assertEquals("abcdef", decodedChunks(channel.output.toByteArray()));
			} finally { continueProducer.countDown(); }
		}
	}

	@Test void oversizedHandshakeAttributionRejectsUnvalidatedHeadersAndDropsPartialLines() {
		DefaultSseServer server = (DefaultSseServer) SseServer.withPort(0).build();
		for (String headers : List.of("Host: a\r\nHost: b\r\n", "Host: bad host\r\n", "Host: a\r\nBad Name: x\r\n",
				"Host: a\r\nX: a\u0000b\r\n", "Host: a\r\nX: a\u001bb\r\n", "Host: a\r\n folded: x\r\n"))
			assertTrue(server.parseTooLargeRequestForRawRequest("GET /events HTTP/1.1\r\n" + headers).isEmpty(), headers);
		Request partial = server.parseTooLargeRequestForRawRequest("GET /events HTTP/1.1\r\nHost: a\r\nX-Partial: untrusted").orElseThrow();
		assertFalse(partial.getHeaders().containsKey("X-Partial"));
	}

	@Test void mixedLfThenCrlfHeaderTerminatorIsDetectedWithoutWaitingForTimeout() {
		for (String prefix : List.of("GET /events HTTP/1.1\nHost: a\n\r\n", "GET /events HTTP/1.1\r\nHost: a\n\r\n")) {
			DefaultSseServer.HandshakeHeaderScanner scanner = new DefaultSseServer.HandshakeHeaderScanner();
			for (int i = 1; i < prefix.length(); i++) assertEquals(-1, scanner.scan(prefix.substring(0, i)));
			assertEquals(prefix.length(), scanner.scan(prefix));
		}
	}

	@Test void establishedSsePeerInputHasAFiniteBudget() throws Exception {
		DefaultSseServer server = (DefaultSseServer) SseServer.withPort(0).maximumRequestSizeInBytes(1024).build();
		ResourceMethodResolver resolver = ResourceMethodResolver.fromClasses(Set.of(Events.class));
		Request request = Request.fromPath(HttpMethod.GET, "/events");
		ResourceMethod resource = resolver.resourceMethodForRequest(request, ServerType.SSE).orElseThrow();
		StreamLifecycleCoordinator coordinator = new StreamLifecycleCoordinator(1, 1, Duration.ofSeconds(1), ignored -> {});
		try (MemoryChannel channel = new MemoryChannel(1_000_000)) {
			Class<?> type = Class.forName("com.soklet.DefaultSseServer$DefaultSseConnection");
			Constructor<?> constructor = type.getDeclaredConstructor(Request.class, ResourceMethod.class, Object.class, Integer.class, SocketChannel.class, StreamLifecycleCoordinator.Reservation.class);
			constructor.setAccessible(true);
			Object connection = constructor.newInstance(request, resource, null, 1, channel, coordinator.tryReserve());
			Method monitor = DefaultSseServer.class.getDeclaredMethod("monitorClientDisconnect", type); monitor.setAccessible(true);
			monitor.invoke(server, connection);
			assertTrue(channel.readBytes <= 1025, "Monitor drained " + channel.readBytes + " bytes after handshake");
			assertFalse(channel.isOpen());
		} finally {
			coordinator.force(); assertTrue(coordinator.awaitTermination(System.nanoTime() + TimeUnit.SECONDS.toNanos(2)));
		}
	}


	@Test void rejectedHandshakeKeepsItsReasonWhenThePeerClosesDuringResponseWrite() throws Exception {
		DefaultSseServer server = (DefaultSseServer) SseServer.withPort(0).writeTimeout(Duration.ZERO).build();
		AtomicReference<SseConnection.HandshakeFailureReason> reason = new AtomicReference<>();
		AtomicReference<Throwable> observedCause = new AtomicReference<>();
		IOException peerClose = new IOException("Connection reset by peer");
		server.initialize(SokletConfig.forSimulatorTesting().lifecycleObserver(new LifecycleObserver() {
			@Override public void didFailToEstablishSseConnection(Request request, ResourceMethod resource,
					SseConnection.HandshakeFailureReason failureReason, Throwable cause) {
				reason.set(failureReason); observedCause.set(cause);
			}
		}).build(), (request, consumer) -> {
			HttpTransportResponseFeedback feedback = HttpTransportResponseFeedback.forConsumer(consumer);
			assertNotNull(feedback); feedback.claimLifecycleConsumer();
			try { consumer.accept(HttpRequestResult.fromMarshaledResponse(MarshaledResponse.withStatusCode(403).build())); }
			catch (IllegalStateException failure) { assertSame(peerClose, failure.getCause()); }
			finally { feedback.finish(); }
		});
		Class<?> contextType = Class.forName("com.soklet.DefaultSseServer$HandshakeContext");
		Constructor<?> constructor = contextType.getDeclaredConstructor(InetSocketAddress.class); constructor.setAccessible(true);
		Object context = constructor.newInstance((InetSocketAddress) null);
		Field requestRef = contextType.getDeclaredField("requestRef"); requestRef.setAccessible(true);
		@SuppressWarnings("unchecked") AtomicReference<Request> request = (AtomicReference<Request>) requestRef.get(context);
		request.set(Request.fromPath(HttpMethod.GET, "/events"));
		Method handle = DefaultSseServer.class.getDeclaredMethod("handleClientSocketChannel", SocketChannel.class, contextType);
		handle.setAccessible(true);
		try (MemoryChannel channel = new MemoryChannel(0)) {
			channel.writeFailure = peerClose;
			handle.invoke(server, channel, context);
			assertSame(peerClose, observedCause.get(), "The test must exercise the actual socket-write failure");
			assertEquals(SseConnection.HandshakeFailureReason.HANDSHAKE_REJECTED, reason.get());
			assertFalse(channel.isOpen());
		}
	}

	public static class Events { @SseEventSource("/events") public SseHandshakeResult events() { return SseHandshakeResult.accept(); } }

	private static String decodedChunks(byte[] wire) {
		String value = new String(wire, StandardCharsets.US_ASCII); StringBuilder decoded = new StringBuilder(); int cursor = 0;
		while (true) {
			int end = value.indexOf("\r\n", cursor); assertTrue(end >= cursor, value);
			int length = Integer.parseInt(value.substring(cursor, end), 16); cursor = end + 2;
			if (length == 0) { assertEquals("\r\n", value.substring(cursor)); return decoded.toString(); }
			decoded.append(value, cursor, cursor + length); cursor += length;
			assertEquals("\r\n", value.substring(cursor, cursor + 2)); cursor += 2;
		}
	}

	private static final class QueueFixture implements AutoCloseable {
		final ExecutorService producer = Executors.newSingleThreadExecutor();
		final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
		final WritableSource source;
		QueueFixture(int capacity, int chunkSize, StreamingResponseWriter writer) throws Exception {
			MicrohttpResponse response = StreamingMicrohttpResponses.withStreamingBody(200, "OK", List.of(),
					Request.withPath(HttpMethod.GET, "/stream").build(), StreamingResponseBody.fromWriter(writer), producer, timer,
					capacity, chunkSize, null, null, (at, duration, reason, cause) -> {}, failure -> {});
			Method factory = MicrohttpResponse.class.getDeclaredMethod("newBodySource"); factory.setAccessible(true);
			this.source = (WritableSource) factory.invoke(response);
		}
		int chunkCount() throws Exception {
			Field lock = source.getClass().getDeclaredField("lock"); lock.setAccessible(true);
			Field chunks = source.getClass().getDeclaredField("chunks"); chunks.setAccessible(true);
			synchronized (lock.get(source)) { return ((Queue<?>) chunks.get(source)).size(); }
		}
		void drain(MemoryChannel channel) throws Exception {
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
			while (source.hasRemaining() && System.nanoTime() - deadline < 0) {
				if (source.writeTo(channel, 65_536) == 0) Thread.yield();
			}
			assertFalse(source.hasRemaining());
		}
		@Override public void close() throws Exception {
			source.close(StreamTerminationReason.APPLICATION_CANCELED, null); producer.shutdownNow(); timer.shutdownNow();
			assertTrue(producer.awaitTermination(2, TimeUnit.SECONDS)); assertTrue(timer.awaitTermination(2, TimeUnit.SECONDS));
		}
	}

	private static final class MemoryChannel extends SocketChannel {
		final ByteArrayOutputStream output = new ByteArrayOutputStream(); final int inputBytes; int readBytes; IOException writeFailure;
		MemoryChannel(int inputBytes) { super(SelectorProvider.provider()); this.inputBytes = inputBytes; }
		@Override public int read(ByteBuffer dst) { if (readBytes == inputBytes) return -1; int count = Math.min(dst.remaining(), inputBytes - readBytes); dst.position(dst.position() + count); readBytes += count; return count; }
		@Override public long read(ByteBuffer[] dsts, int offset, int length) { throw new UnsupportedOperationException(); }
		@Override public int write(ByteBuffer src) throws IOException { if (writeFailure != null) throw writeFailure; int count = src.remaining(); byte[] bytes = new byte[count]; src.get(bytes); output.writeBytes(bytes); return count; }
		@Override public long write(ByteBuffer[] srcs, int offset, int length) throws IOException { long count = 0; for (int i=offset;i<offset+length;i++) count += write(srcs[i]); return count; }
		@Override public SocketChannel bind(SocketAddress local) { return this; }
		@Override public <T> SocketChannel setOption(SocketOption<T> name,T value) { return this; }
		@Override public <T> T getOption(SocketOption<T> name) { throw new UnsupportedOperationException(); }
		@Override public Set<SocketOption<?>> supportedOptions() { return Set.of(); }
		@Override public SocketChannel shutdownInput() { return this; }
		@Override public SocketChannel shutdownOutput() { return this; }
		@Override public Socket socket() { throw new UnsupportedOperationException(); }
		@Override public boolean isConnected() { return true; }
		@Override public boolean isConnectionPending() { return false; }
		@Override public boolean connect(SocketAddress remote) { return true; }
		@Override public boolean finishConnect() { return true; }
		@Override public SocketAddress getRemoteAddress() { return null; }
		@Override public SocketAddress getLocalAddress() { return null; }
		@Override protected void implCloseSelectableChannel() { }
		@Override protected void implConfigureBlocking(boolean block) { }
	}
}
