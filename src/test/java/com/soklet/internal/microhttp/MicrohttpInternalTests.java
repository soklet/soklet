package com.soklet.internal.microhttp;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketOption;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static java.nio.file.StandardOpenOption.READ;

public class MicrohttpInternalTests {
	@Test
	public void compactRetainsCapacityWhenRequestFullyConsumed() {
		ByteTokenizer tokenizer = new ByteTokenizer();
		byte[] request = ascii("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n");

		add(tokenizer, request);
		int capacity = tokenizer.capacity();
		RequestParser parser = new RequestParser(tokenizer, remoteAddress(), 1024);

		Assertions.assertTrue(parser.parse());
		Assertions.assertEquals(request.length, tokenizer.position());

		tokenizer.compact();

		Assertions.assertEquals(capacity, tokenizer.capacity());
		Assertions.assertEquals(0, tokenizer.position());
		Assertions.assertEquals(0, tokenizer.size());
		Assertions.assertEquals(0, tokenizer.remaining());
	}

	@Test
	public void compactResetsLogicalPositionForPipelinedRequests() {
		ByteTokenizer tokenizer = new ByteTokenizer();
		byte[] first = ascii("GET /one HTTP/1.1\r\nHost: localhost\r\n\r\n");
		byte[] second = ascii("GET /two HTTP/1.1\r\nHost: localhost\r\n\r\n");
		byte[] pipelined = new byte[first.length + second.length];
		System.arraycopy(first, 0, pipelined, 0, first.length);
		System.arraycopy(second, 0, pipelined, first.length, second.length);

		add(tokenizer, pipelined);
		RequestParser parser = new RequestParser(tokenizer, remoteAddress(), 64);

		Assertions.assertTrue(parser.parse());
		Assertions.assertEquals(first.length, tokenizer.position());
		tokenizer.compact();
		parser.reset();

		Assertions.assertEquals(0, tokenizer.position());
		Assertions.assertEquals(second.length, tokenizer.size());
		Assertions.assertEquals(second.length, tokenizer.remaining());
		Assertions.assertTrue(parser.parse());
		Assertions.assertEquals("/two", parser.request().uri());
		Assertions.assertEquals(second.length, tokenizer.position());
	}

	@Test
	public void parserResetDoesNotMutatePreviouslyReturnedRequest() {
		ByteTokenizer tokenizer = new ByteTokenizer();
		byte[] first = ascii("POST /one HTTP/1.1\r\nHost: localhost\r\nContent-Length: 5\r\nX-Request-Id: first\r\n\r\nfirst");
		byte[] second = ascii("GET /two HTTP/1.1\r\nHost: localhost\r\nX-Request-Id: second\r\n\r\n");

		add(tokenizer, first);
		RequestParser parser = new RequestParser(tokenizer, remoteAddress(), 1024);
		Assertions.assertTrue(parser.parse());
		MicrohttpRequest firstRequest = parser.request();
		tokenizer.compact();
		parser.reset();

		add(tokenizer, second);
		Assertions.assertTrue(parser.parse());
		MicrohttpRequest secondRequest = parser.request();

		Assertions.assertEquals("POST", firstRequest.method());
		Assertions.assertEquals("/one", firstRequest.uri());
		Assertions.assertEquals("first", new String(firstRequest.body(), StandardCharsets.US_ASCII));
		Assertions.assertEquals("first", firstRequest.header("X-Request-Id"));
		Assertions.assertEquals(3, firstRequest.headers().size());

		Assertions.assertEquals("GET", secondRequest.method());
		Assertions.assertEquals("/two", secondRequest.uri());
		Assertions.assertEquals("second", secondRequest.header("X-Request-Id"));
		Assertions.assertEquals(2, secondRequest.headers().size());
	}

	@Test
	public void parserPreservesNonCanonicalHeaderNameCasing() {
		ByteTokenizer tokenizer = new ByteTokenizer();
		byte[] request = ascii("GET / HTTP/1.1\r\nhost: localhost\r\nx-request-id: abc123\r\n\r\n");

		add(tokenizer, request);
		RequestParser parser = new RequestParser(tokenizer, remoteAddress(), 1024);
		Assertions.assertTrue(parser.parse());

		MicrohttpRequest microhttpRequest = parser.request();
		Assertions.assertEquals("host", microhttpRequest.headers().get(0).name());
		Assertions.assertEquals("x-request-id", microhttpRequest.headers().get(1).name());
		Assertions.assertEquals("localhost", microhttpRequest.header("Host"));
		Assertions.assertEquals("abc123", microhttpRequest.header("X-Request-Id"));
	}

	@Test
	public void eventLoopAcceptIOExceptionIsRecordedWithoutStoppingLoop() throws Exception {
		List<String> failureEvents = new ArrayList<>();
		List<Throwable> failures = new ArrayList<>();
		int[] acceptFailures = {0};
		IOException acceptFailure = new IOException("transient accept failure");
		Logger logger = new Logger() {
			@Override
			public boolean enabled() {
				return false;
			}

			@Override
			public boolean failureEnabled() {
				return true;
			}

			@Override
			public void log(LogEntry... entries) {
				// Trace logging is disabled for this test.
			}

			@Override
			public void log(Exception e, LogEntry... entries) {
				// Trace logging is disabled for this test.
			}

			@Override
			public void logFailure(Exception e, LogEntry... entries) {
				failures.add(e);
				for (LogEntry entry : entries) {
					if ("event".equals(entry.key()))
						failureEvents.add(entry.value());
				}
			}
		};
		ConnectionListener connectionListener = new ConnectionListener() {
			@Override
			public void willAcceptConnection(InetSocketAddress remoteAddress) {
				// No-op
			}

			@Override
			public void didAcceptConnection(InetSocketAddress remoteAddress) {
				// No-op
			}

			@Override
			public void didFailToAcceptConnection(InetSocketAddress remoteAddress) {
				// No-op
			}

			@Override
			public void didFailToAcceptConnection(InetSocketAddress remoteAddress,
																						Throwable throwable) {
				acceptFailures[0]++;
				Assertions.assertSame(acceptFailure, throwable);
			}
		};
		EventLoop eventLoop = new EventLoop(Options.builder()
				.withHost("127.0.0.1")
				.withPort(0)
				.withConcurrency(1)
				.withResolution(Duration.ofMillis(10))
				.build(), logger, (request, callback) -> {}, connectionListener);

		try {
			Assertions.assertFalse(eventLoop.acceptReadyConnection(() -> {
				throw acceptFailure;
			}));
			Assertions.assertFalse(eventLoop.isStopped());
			Assertions.assertEquals(1, acceptFailures[0]);
			Assertions.assertEquals(List.of("accept_loop_error"), failureEvents);
			Assertions.assertEquals(List.of(acceptFailure), failures);
		} finally {
			eventLoop.start();
			eventLoop.stop();
			eventLoop.join();
		}
	}

	@Test
	public void parserRejectsObsFoldHeaderLines() {
		ByteTokenizer tokenizer = new ByteTokenizer();
		byte[] request = ascii("GET / HTTP/1.1\r\nHost: localhost\r\n X-Folded: nope\r\n\r\n");

		add(tokenizer, request);
		RequestParser parser = new RequestParser(tokenizer, remoteAddress(), 1024);

		Assertions.assertThrows(MalformedRequestException.class, parser::parse);
	}

	@Test
	public void parserRejectsTooManyHeaders() {
		ByteTokenizer tokenizer = new ByteTokenizer();
		byte[] request = ascii("GET / HTTP/1.1\r\nHost: localhost\r\nX-Test: abc\r\n\r\n");

		add(tokenizer, request);
		RequestParser parser = new RequestParser(tokenizer, remoteAddress(), 1024, 1, 1024);

		Assertions.assertThrows(RequestTooLargeException.class, parser::parse);
	}

	@Test
	public void parserRejectsTooLargeHeaderSection() {
		ByteTokenizer tokenizer = new ByteTokenizer();
		byte[] request = ascii("GET / HTTP/1.1\r\nHost: localhost\r\nX-Test: abc\r\n\r\n");

		add(tokenizer, request);
		RequestParser parser = new RequestParser(tokenizer, remoteAddress(), 1024, 100, 19, 1024);

		Assertions.assertThrows(RequestTooLargeException.class, parser::parse);
	}

	@Test
	public void parserRejectsIncompleteHeaderLineThatExceedsHeaderSectionLimit() {
		ByteTokenizer tokenizer = new ByteTokenizer();
		byte[] request = ascii("GET / HTTP/1.1\r\nHost: localhost\r\nX-Test: abc");

		add(tokenizer, request);
		RequestParser parser = new RequestParser(tokenizer, remoteAddress(), 1024, 100, 19, 1024);

		Assertions.assertThrows(RequestTooLargeException.class, parser::parse);
	}

	@Test
	public void parserRejectsTooLongRequestTarget() {
		ByteTokenizer tokenizer = new ByteTokenizer();
		byte[] request = ascii("GET /too-long HTTP/1.1\r\nHost: localhost\r\n\r\n");

		add(tokenizer, request);
		RequestParser parser = new RequestParser(tokenizer, remoteAddress(), 1024, 100, 4);

		Assertions.assertThrows(RequestTooLargeException.class, parser::parse);
	}

	@Test
	public void microhttpRequestHeaderHelpersTolerateNullHeaderRecordFields() {
		MicrohttpRequest request = new MicrohttpRequest(
				"GET",
				"/",
				"HTTP/1.1",
				List.of(
						new Header(null, "ignored"),
						new Header("X-Null", null),
						new Header("X-Test", "abc")),
				new byte[0],
				false,
				remoteAddress());

		Assertions.assertNull(request.header(null));
		Assertions.assertNull(request.header("X-Null"));
		Assertions.assertEquals("abc", request.header("x-test"));
		Assertions.assertFalse(request.hasHeader(null, "ignored"));
		Assertions.assertFalse(request.hasHeader("X-Null", null));
		Assertions.assertFalse(request.hasHeader("X-Null", "abc"));
		Assertions.assertTrue(request.hasHeader("x-test", "ABC"));
	}

	@Test
	public void byteTokenizerCapacityExpansionRejectsIntegerOverflow() {
		Assertions.assertThrows(RequestTooLargeException.class, () ->
				ByteTokenizer.expandedCapacity(Integer.MAX_VALUE, Integer.MAX_VALUE - 1, 2));
	}

	@Test
	public void byteTokenizerCapacityExpansionCapsOverflowingDouble() {
		Assertions.assertEquals(Integer.MAX_VALUE,
				ByteTokenizer.expandedCapacity(Integer.MAX_VALUE - 4, Integer.MAX_VALUE - 8, 8));
	}

	@Test
	public void chunkSizeOverflowIsMalformed() {
		Assertions.assertThrows(MalformedRequestException.class, () ->
				RequestParser.parseChunkSizeToken("80000000", Long.MAX_VALUE));
	}

	@Test
	public void chunkSizeWithLeadingPlusIsMalformed() {
		Assertions.assertThrows(MalformedRequestException.class, () ->
				RequestParser.parseChunkSizeToken("+5", Long.MAX_VALUE));
	}

	@Test
	public void chunkDataTerminatorRejectsBytesBeforeCrLf() {
		ByteTokenizer tokenizer = new ByteTokenizer();
		byte[] request = ascii("POST / HTTP/1.1\r\n"
				+ "Host: localhost\r\n"
				+ "Transfer-Encoding: chunked\r\n"
				+ "\r\n"
				+ "3\r\n"
				+ "abcx\r\n"
				+ "0\r\n"
				+ "\r\n");
		add(tokenizer, request);
		RequestParser parser = new RequestParser(tokenizer, remoteAddress(), 1024);

		Assertions.assertThrows(MalformedRequestException.class, parser::parse);
	}

	@Test
	public void chunkTrailerRejectsMalformedHeaderLine() {
		ByteTokenizer tokenizer = new ByteTokenizer();
		byte[] request = ascii("POST / HTTP/1.1\r\n"
				+ "Host: localhost\r\n"
				+ "Transfer-Encoding: chunked\r\n"
				+ "\r\n"
				+ "3\r\n"
				+ "abc\r\n"
				+ "0\r\n"
				+ "GARBAGE\r\n"
				+ "\r\n");
		add(tokenizer, request);
		RequestParser parser = new RequestParser(tokenizer, remoteAddress(), 1024);

		Assertions.assertThrows(MalformedRequestException.class, parser::parse);
	}

	@Test
	public void byteBufferWritableSourceHonorsWriteBudget() throws IOException {
		ByteBufferWritableSource source = new ByteBufferWritableSource(ByteBuffer.wrap(ascii("abcdef")));
		PartialWriteSocketChannel channel = new PartialWriteSocketChannel(10);

		Assertions.assertEquals(3L, source.writeTo(channel, 3L));
		Assertions.assertTrue(source.hasRemaining());
		Assertions.assertEquals("abc", ascii(channel.getWrittenBytes()));

		Assertions.assertEquals(3L, source.writeTo(channel, 10L));
		Assertions.assertFalse(source.hasRemaining());
		Assertions.assertEquals("abcdef", ascii(channel.getWrittenBytes()));
	}

	@Test
	public void byteBufferWritableSourceAllowsSocketPartialWrites() throws IOException {
		ByteBufferWritableSource source = new ByteBufferWritableSource(ByteBuffer.wrap(ascii("abcdef")));
		PartialWriteSocketChannel channel = new PartialWriteSocketChannel(2);

		Assertions.assertEquals(2L, source.writeTo(channel, 6L));
		Assertions.assertTrue(source.hasRemaining());
		Assertions.assertEquals("ab", ascii(channel.getWrittenBytes()));

		Assertions.assertEquals(2L, source.writeTo(channel, 6L));
		Assertions.assertTrue(source.hasRemaining());
		Assertions.assertEquals("abcd", ascii(channel.getWrittenBytes()));

		Assertions.assertEquals(2L, source.writeTo(channel, 6L));
		Assertions.assertFalse(source.hasRemaining());
		Assertions.assertEquals("abcdef", ascii(channel.getWrittenBytes()));
	}

	@Test
	public void compositeWritableSourceWritesAcrossChildrenWithinBudget() throws IOException {
		CompositeWritableSource source = new CompositeWritableSource(List.of(
				new ByteBufferWritableSource(ByteBuffer.wrap(ascii("ab"))),
				new ByteBufferWritableSource(ByteBuffer.wrap(ascii("cdef")))
		));
		PartialWriteSocketChannel channel = new PartialWriteSocketChannel(10);

		Assertions.assertEquals(4L, source.writeTo(channel, 4L));
		Assertions.assertTrue(source.hasRemaining());
		Assertions.assertEquals("abcd", ascii(channel.getWrittenBytes()));

		Assertions.assertEquals(2L, source.writeTo(channel, 10L));
		Assertions.assertFalse(source.hasRemaining());
		Assertions.assertEquals("abcdef", ascii(channel.getWrittenBytes()));
	}

	@Test
	public void compositeWritableSourceSkipsEmptyChildren() throws IOException {
		CompositeWritableSource source = new CompositeWritableSource(List.of(
				new ByteBufferWritableSource(ByteBuffer.wrap(new byte[0])),
				new ByteBufferWritableSource(ByteBuffer.wrap(ascii("abc")))
		));
		PartialWriteSocketChannel channel = new PartialWriteSocketChannel(10);

		Assertions.assertEquals(3L, source.writeTo(channel, 10L));
		Assertions.assertFalse(source.hasRemaining());
		Assertions.assertEquals("abc", ascii(channel.getWrittenBytes()));
	}

	@Test
	public void microhttpResponseWritableSourcePreservesSerializedBytes() throws IOException {
		MicrohttpResponse response = new MicrohttpResponse(
				200,
				"OK",
				List.of(new Header("X-Test", "one")),
				ascii("body"));
		List<Header> connectionHeaders = List.of(new Header("Content-Length", "4"));
		byte[] serializedHead = response.serializeHead("HTTP/1.1", connectionHeaders);
		WritableSource source = response.writableSource(serializedHead);
		PartialWriteSocketChannel channel = new PartialWriteSocketChannel(3);

		while (source.hasRemaining()) {
			source.writeTo(channel, 1024L);
		}

		Assertions.assertArrayEquals(
				response.serialize("HTTP/1.1", connectionHeaders),
				channel.getWrittenBytes());
		Assertions.assertEquals(
				"HTTP/1.1 200 OK\r\nContent-Length: 4\r\nX-Test: one\r\n\r\nbody",
				ascii(channel.getWrittenBytes()));
	}

	@Test
	public void fileChannelWritableSourceTransfersRequestedSlice(@TempDir Path tempDir) throws IOException {
		Path file = tempDir.resolve("example.txt");
		Files.writeString(file, "abcdef", StandardCharsets.US_ASCII);
		FileChannel fileChannel = FileChannel.open(file, READ);
		FileChannelWritableSource source = new FileChannelWritableSource(fileChannel, 1L, 4L, true);
		PartialWriteSocketChannel channel = new PartialWriteSocketChannel(2);

		Assertions.assertEquals(2L, source.writeTo(channel, 10L));
		Assertions.assertTrue(source.hasRemaining());
		Assertions.assertEquals("bc", ascii(channel.getWrittenBytes()));

		Assertions.assertEquals(2L, source.writeTo(channel, 10L));
		Assertions.assertFalse(source.hasRemaining());
		Assertions.assertEquals("bcde", ascii(channel.getWrittenBytes()));

		source.close();
		Assertions.assertFalse(fileChannel.isOpen());
	}

	@Test
	public void microhttpResponseWritableSourceWritesFileBody(@TempDir Path tempDir) throws IOException {
		Path file = tempDir.resolve("example.txt");
		Files.writeString(file, "abcdef", StandardCharsets.US_ASCII);
		MicrohttpResponse response = MicrohttpResponse.withFileBody(
				200,
				"OK",
				List.of(new Header("Content-Length", "4")),
				file,
				1L,
				4L);
		WritableSource source = response.writableSource(response.serializeHead("HTTP/1.1", List.of()));
		PartialWriteSocketChannel channel = new PartialWriteSocketChannel(3);

		while (source.hasRemaining()) {
			source.writeTo(channel, 1024L);
		}

		Assertions.assertEquals(
				"HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\nbcde",
				ascii(channel.getWrittenBytes()));
	}

	@Test
	public void microhttpResponseWritableSourceWritesByteBufferBody() throws IOException {
		ByteBuffer buffer = ByteBuffer.wrap(ascii("abcdef"));
		buffer.position(1);
		buffer.limit(5);
		MicrohttpResponse response = MicrohttpResponse.withByteBufferBody(
				200,
				"OK",
				List.of(new Header("Content-Length", "4")),
				buffer);
		WritableSource source = response.writableSource(response.serializeHead("HTTP/1.1", List.of()));
		PartialWriteSocketChannel channel = new PartialWriteSocketChannel(3);

		while (source.hasRemaining()) {
			source.writeTo(channel, 1024L);
		}

		Assertions.assertEquals(1, buffer.position());
		Assertions.assertEquals(5, buffer.limit());
		Assertions.assertEquals(
				"HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\nbcde",
				ascii(channel.getWrittenBytes()));
	}

	@Test
	public void connectionEventLoopSurvivesUncheckedResponseTaskFailure() throws Exception {
		Options options = OptionsBuilder.newBuilder()
				.withPort(0)
				.withResolution(Duration.ofMillis(10))
				.withRequestHeaderTimeout(Duration.ofSeconds(2))
				.withRequestBodyTimeout(Duration.ofSeconds(2))
				.withConcurrency(1)
				.build();
		RecordingLogger logger = new RecordingLogger();
		EventLoop eventLoop = new EventLoop(options, logger, (request, callback) -> {
			if ("/boom".equals(request.uri())) {
				callback.accept(MicrohttpResponse.withStreamingBody(200, "OK", List.of(), () -> new WritableSource() {
					@Override
					public void start() {
						throw new AssertionError("boom");
					}

					@Override
					public long writeTo(SocketChannel socketChannel, long maxBytes) {
						return 0L;
					}

					@Override
					public boolean hasRemaining() {
						return true;
					}

					@Override
					public void close() {
						// no-op
					}
				}));
				return;
			}

			callback.accept(new MicrohttpResponse(200, "OK", List.of(), ascii("pong")));
		});

		eventLoop.start();

		try {
			sendRequestAndReadResponse(eventLoop.getPort(), "GET /boom HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
			String response = sendRequestAndReadResponse(eventLoop.getPort(), "GET /ok HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");

			Assertions.assertTrue(response.startsWith("HTTP/1.1 200 OK"), response);
			Assertions.assertTrue(response.endsWith("pong"), response);
			Assertions.assertTrue(logger.containsFailureEvent("response_ready_error"), logger.events().toString());
		} finally {
			eventLoop.stop();
			eventLoop.join();
		}
	}

	@Test
	public void requestReadTimeoutWithoutRequestProgressClosesQuietly() throws Exception {
		Options options = OptionsBuilder.newBuilder()
				.withPort(0)
				.withResolution(Duration.ofMillis(10))
				.withRequestHeaderTimeout(Duration.ofMillis(50))
				.withRequestBodyTimeout(Duration.ofMillis(50))
				.withConcurrency(1)
				.build();
		RecordingLogger logger = new RecordingLogger();
		EventLoop eventLoop = new EventLoop(options, logger, (request, callback) ->
				callback.accept(new MicrohttpResponse(200, "OK", List.of(), ascii("pong"))));

		eventLoop.start();

		try (Socket socket = new Socket("localhost", eventLoop.getPort())) {
			socket.setSoTimeout(2_000);

			waitForSocketClose(socket);

			Assertions.assertFalse(logger.containsFailureEvent("request_timeout"), logger.events().toString());
			Assertions.assertTrue(logger.events().isEmpty(), logger.events().toString());
		} finally {
			eventLoop.stop();
			eventLoop.join();
		}
	}

	@Test
	public void idleKeepAliveReadTimeoutAfterCompletedRequestClosesQuietly() throws Exception {
		Options options = OptionsBuilder.newBuilder()
				.withPort(0)
				.withResolution(Duration.ofMillis(10))
				.withRequestHeaderTimeout(Duration.ofMillis(50))
				.withRequestBodyTimeout(Duration.ofMillis(50))
				.withConcurrency(1)
				.build();
		RecordingLogger logger = new RecordingLogger();
		EventLoop eventLoop = new EventLoop(options, logger, (request, callback) ->
				callback.accept(new MicrohttpResponse(200, "OK", List.of(), ascii("pong"))));

		eventLoop.start();

		try (Socket socket = new Socket("localhost", eventLoop.getPort())) {
			socket.setSoTimeout(2_000);
			OutputStream outputStream = socket.getOutputStream();
			outputStream.write(ascii("GET /ok HTTP/1.1\r\nHost: localhost\r\n\r\n"));
			outputStream.flush();

			String response = readUntil(socket.getInputStream(), "pong");

			Assertions.assertTrue(response.startsWith("HTTP/1.1 200 OK"), response);
			Assertions.assertTrue(response.endsWith("pong"), response);

			waitForSocketClose(socket);

			Assertions.assertFalse(logger.containsFailureEvent("request_timeout"), logger.events().toString());
			Assertions.assertTrue(logger.events().isEmpty(), logger.events().toString());
		} finally {
			eventLoop.stop();
			eventLoop.join();
		}
	}

	@Test
	public void remoteResetWithoutRequestDataInFlightClosesQuietly() throws Exception {
		Options options = OptionsBuilder.newBuilder()
				.withPort(0)
				.withResolution(Duration.ofMillis(10))
				.withRequestHeaderTimeout(Duration.ofSeconds(2))
				.withRequestBodyTimeout(Duration.ofSeconds(2))
				.withMaxConnections(1)
				.withConcurrency(1)
				.build();
		RecordingLogger logger = new RecordingLogger();
		EventLoop eventLoop = new EventLoop(options, logger, (request, callback) ->
				callback.accept(new MicrohttpResponse(200, "OK", List.of(), ascii("pong"))));

		eventLoop.start();

		try {
			try (Socket socket = new Socket("localhost", eventLoop.getPort())) {
				socket.setSoTimeout(2_000);
				OutputStream outputStream = socket.getOutputStream();
				outputStream.write(ascii("GET /ok HTTP/1.1\r\nHost: localhost\r\n\r\n"));
				outputStream.flush();

				String response = readUntil(socket.getInputStream(), "pong");

				Assertions.assertTrue(response.startsWith("HTTP/1.1 200 OK"), response);
				Assertions.assertTrue(response.endsWith("pong"), response);

				socket.setSoLinger(true, 0);
			}

			String response = awaitSuccessfulResponse(eventLoop.getPort(),
					"GET /after-reset HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");

			Assertions.assertTrue(response.startsWith("HTTP/1.1 200 OK"), response);
			Assertions.assertFalse(logger.containsFailureEvent("read_error"), logger.events().toString());
		} finally {
			eventLoop.stop();
			eventLoop.join();
		}
	}

	@Test
	public void remoteResetWithPartialRequestDataRecordsReadError() throws Exception {
		Options options = OptionsBuilder.newBuilder()
				.withPort(0)
				.withResolution(Duration.ofMillis(10))
				.withRequestHeaderTimeout(Duration.ofSeconds(2))
				.withRequestBodyTimeout(Duration.ofSeconds(2))
				.withConcurrency(1)
				.build();
		RecordingLogger logger = new RecordingLogger(true);
		EventLoop eventLoop = new EventLoop(options, logger, (request, callback) ->
				callback.accept(new MicrohttpResponse(200, "OK", List.of(), ascii("pong"))));

		eventLoop.start();

		try (Socket socket = new Socket("localhost", eventLoop.getPort())) {
			socket.setSoTimeout(2_000);
			OutputStream outputStream = socket.getOutputStream();
			outputStream.write(ascii("GET /partial HTTP/1.1\r\nHo"));
			outputStream.flush();

			Assertions.assertTrue(logger.awaitTraceEvent("read_bytes"), logger.traceEvents().toString());

			socket.setSoLinger(true, 0);
			socket.close();

			Assertions.assertTrue(logger.awaitFailureEvent("read_error"), logger.events().toString());
		} finally {
			eventLoop.stop();
			eventLoop.join();
		}
	}

	@Test
	public void pipelinedPartialRequestThenIdleReadTimeoutRecordsTransportFailure() throws Exception {
		Options options = OptionsBuilder.newBuilder()
				.withPort(0)
				.withResolution(Duration.ofMillis(10))
				.withRequestHeaderTimeout(Duration.ofMillis(50))
				.withRequestBodyTimeout(Duration.ofMillis(50))
				.withConcurrency(1)
				.build();
		RecordingLogger logger = new RecordingLogger();
		EventLoop eventLoop = new EventLoop(options, logger, (request, callback) ->
				callback.accept(new MicrohttpResponse(200, "OK", List.of(), ascii("pong"))));

		eventLoop.start();

		try (Socket socket = new Socket("localhost", eventLoop.getPort())) {
			socket.setSoTimeout(2_000);
			OutputStream outputStream = socket.getOutputStream();
			// A complete request with the partial start of a pipelined second request behind it, in one write
			outputStream.write(ascii("GET /ok HTTP/1.1\r\nHost: localhost\r\n\r\nGET /pipelined HTTP/1.1\r\nHo"));
			outputStream.flush();

			String response = readUntil(socket.getInputStream(), "pong");

			Assertions.assertTrue(response.startsWith("HTTP/1.1 200 OK"), response);
			Assertions.assertTrue(response.endsWith("pong"), response);

			// The buffered partial pipelined request is request data in flight; stalling on it must be
			// recorded as a request timeout (quiet closes are reserved for connections with NO request
			// data in flight), otherwise slow clients could hold connection slots invisibly.
			waitForSocketClose(socket);

			Assertions.assertTrue(logger.containsFailureEvent("request_timeout"), logger.events().toString());
		} finally {
			eventLoop.stop();
			eventLoop.join();
		}
	}

	@Test
	public void pipelinedMalformedChunkAfterResponseReturnsBadRequest() throws Exception {
		Options options = OptionsBuilder.newBuilder()
				.withPort(0)
				.withResolution(Duration.ofMillis(10))
				.withRequestHeaderTimeout(Duration.ofSeconds(2))
				.withRequestBodyTimeout(Duration.ofSeconds(2))
				.withConcurrency(1)
				.build();
		RecordingLogger logger = new RecordingLogger();
		EventLoop eventLoop = new EventLoop(options, logger, (request, callback) ->
				callback.accept(new MicrohttpResponse(200, "OK", List.of(), ascii("pong"))));

		eventLoop.start();

		try {
			String response = sendRequestAndReadResponse(eventLoop.getPort(), "GET /ok HTTP/1.1\r\n"
					+ "Host: localhost\r\n"
					+ "\r\n"
					+ "POST /bad HTTP/1.1\r\n"
					+ "Host: localhost\r\n"
					+ "Transfer-Encoding: chunked\r\n"
					+ "\r\n"
					+ "3\r\n"
					+ "abcx\r\n"
					+ "0\r\n"
					+ "\r\n");

			Assertions.assertTrue(response.startsWith("HTTP/1.1 200 OK"), response);
			Assertions.assertTrue(response.contains("\r\n\r\npongHTTP/1.1 400 Bad Request"), response);
			Assertions.assertTrue(logger.containsFailureEvent("malformed_request"), logger.events().toString());
			Assertions.assertFalse(logger.containsFailureEvent("write_error"), logger.events().toString());
		} finally {
			eventLoop.stop();
			eventLoop.join();
		}
	}

	@Test
	public void partialRequestReadTimeoutRecordsTransportFailure() throws Exception {
		Options options = OptionsBuilder.newBuilder()
				.withPort(0)
				.withResolution(Duration.ofMillis(10))
				.withRequestHeaderTimeout(Duration.ofMillis(50))
				.withRequestBodyTimeout(Duration.ofMillis(50))
				.withConcurrency(1)
				.build();
		RecordingLogger logger = new RecordingLogger();
		EventLoop eventLoop = new EventLoop(options, logger, (request, callback) ->
				callback.accept(new MicrohttpResponse(200, "OK", List.of(), ascii("pong"))));

		eventLoop.start();

		try (Socket socket = new Socket("localhost", eventLoop.getPort())) {
			socket.setSoTimeout(2_000);
			OutputStream outputStream = socket.getOutputStream();
			outputStream.write(ascii("GET /partial HTTP/1.1\r\nHo"));
			outputStream.flush();

			waitForSocketClose(socket);

			Assertions.assertTrue(logger.containsFailureEvent("request_timeout"), logger.events().toString());
		} finally {
			eventLoop.stop();
			eventLoop.join();
		}
	}

	@Test
	public void requestBodyReadTimeoutRecordsTransportFailure() throws Exception {
		Options options = OptionsBuilder.newBuilder()
				.withPort(0)
				.withResolution(Duration.ofMillis(10))
				.withRequestHeaderTimeout(Duration.ofMillis(50))
				.withRequestBodyTimeout(Duration.ofMillis(50))
				.withConcurrency(1)
				.build();
		RecordingLogger logger = new RecordingLogger();
		EventLoop eventLoop = new EventLoop(options, logger, (request, callback) ->
				callback.accept(new MicrohttpResponse(200, "OK", List.of(), ascii("pong"))));

		eventLoop.start();

		try (Socket socket = new Socket("localhost", eventLoop.getPort())) {
			socket.setSoTimeout(2_000);
			OutputStream outputStream = socket.getOutputStream();
			outputStream.write(ascii("POST /partial-body HTTP/1.1\r\nHost: localhost\r\nContent-Length: 4\r\n\r\nab"));
			outputStream.flush();

			waitForSocketClose(socket);

			Assertions.assertTrue(logger.containsFailureEvent("request_timeout"), logger.events().toString());
		} finally {
			eventLoop.stop();
			eventLoop.join();
		}
	}

	@Test
	public void responseWriteIdleTimeoutClosesNonStreamingResponseWithoutProgress() throws Exception {
		CountDownLatch stalledWriteAttempted = new CountDownLatch(1);
		Options options = OptionsBuilder.newBuilder()
				.withPort(0)
				.withResolution(Duration.ofMillis(10))
				.withRequestHeaderTimeout(Duration.ofSeconds(2))
				.withRequestBodyTimeout(Duration.ofSeconds(2))
				.withResponseWriteIdleTimeout(Duration.ofMillis(50))
				.withMaxConnections(1)
				.withConcurrency(1)
				.build();
		RecordingLogger logger = new RecordingLogger();
		EventLoop eventLoop = new EventLoop(options, logger, (request, callback) -> {
			if ("/stall".equals(request.uri())) {
				callback.accept(MicrohttpResponse.withWritableSourceBody(200, "OK", List.of(), 1L, () -> new WritableSource() {
					@Override
					public long writeTo(SocketChannel socketChannel, long maxBytes) {
						stalledWriteAttempted.countDown();
						return 0L;
					}

					@Override
					public boolean hasRemaining() {
						return true;
					}

					@Override
					public boolean isReadyToWrite() {
						return false;
					}

					@Override
					public void close() {
						// no-op
					}
				}));
				return;
			}

			callback.accept(new MicrohttpResponse(200, "OK", List.of(), ascii("pong")));
		});

		eventLoop.start();

		try (Socket stalledSocket = new Socket("localhost", eventLoop.getPort())) {
			stalledSocket.getOutputStream().write(ascii("GET /stall HTTP/1.1\r\nHost: localhost\r\n\r\n"));
			stalledSocket.getOutputStream().flush();
			Assertions.assertTrue(stalledWriteAttempted.await(2, TimeUnit.SECONDS), "Timed out waiting for stalled response write attempt");

			String response = awaitSuccessfulResponse(eventLoop.getPort(),
					"GET /ok HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");

			Assertions.assertTrue(response.startsWith("HTTP/1.1 200 OK"), response);
			Assertions.assertTrue(response.endsWith("pong"), response);
			Assertions.assertTrue(logger.containsFailureEvent("response_write_idle_timeout"), logger.events().toString());
		} finally {
			eventLoop.stop();
			eventLoop.join();
		}
	}

	private static void add(ByteTokenizer tokenizer, byte[] bytes) {
		tokenizer.add(ByteBuffer.wrap(bytes));
	}

	private static String sendRequestAndReadResponse(int port, String request) throws IOException {
		try (Socket socket = new Socket("localhost", port)) {
			socket.setSoTimeout(2_000);
			OutputStream outputStream = socket.getOutputStream();
			outputStream.write(ascii(request));
			outputStream.flush();

			ByteArrayOutputStream response = new ByteArrayOutputStream();
			InputStream inputStream = socket.getInputStream();
			byte[] buffer = new byte[256];
			int read;

			while ((read = inputStream.read(buffer)) >= 0)
				response.write(buffer, 0, read);

			return ascii(response.toByteArray());
		}
	}

	private static String awaitSuccessfulResponse(int port, String request) throws Exception {
		Throwable lastFailure = null;
		long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();

		while (System.nanoTime() < deadline) {
			try {
				String response = sendRequestAndReadResponse(port, request);

				if (response.startsWith("HTTP/1.1 200 OK"))
					return response;

				lastFailure = new AssertionError(response);
			} catch (IOException e) {
				lastFailure = e;
			}

			Thread.sleep(25L);
		}

		AssertionError timeout = new AssertionError("Timed out waiting for successful response");

		if (lastFailure != null)
			timeout.initCause(lastFailure);

		throw timeout;
	}

	private static String readUntil(InputStream inputStream, String expectedSuffix) throws IOException {
		ByteArrayOutputStream response = new ByteArrayOutputStream();
		byte[] buffer = new byte[64];

		while (true) {
			int read = inputStream.read(buffer);

			if (read < 0)
				return ascii(response.toByteArray());

			response.write(buffer, 0, read);

			String value = ascii(response.toByteArray());
			if (value.endsWith(expectedSuffix))
				return value;
		}
	}

	private static void waitForSocketClose(Socket socket) throws IOException {
		InputStream inputStream = socket.getInputStream();

		while (inputStream.read() >= 0) {
			// Drain until the server closes the connection.
		}
	}

	private static byte[] ascii(String value) {
		return value.getBytes(StandardCharsets.US_ASCII);
	}

	private static String ascii(byte[] bytes) {
		return new String(bytes, StandardCharsets.US_ASCII);
	}

	private static InetSocketAddress remoteAddress() {
		return new InetSocketAddress("127.0.0.1", 12345);
	}

	private static class RecordingLogger implements Logger {
		private final boolean traceEnabled;
		private final List<String> traceEvents;
		private final List<String> failureEvents;

		private RecordingLogger() {
			this(false);
		}

		private RecordingLogger(boolean traceEnabled) {
			this.traceEnabled = traceEnabled;
			this.traceEvents = Collections.synchronizedList(new ArrayList<>());
			this.failureEvents = Collections.synchronizedList(new ArrayList<>());
		}

		@Override
		public boolean enabled() {
			return traceEnabled;
		}

		@Override
		public boolean failureEnabled() {
			return true;
		}

		@Override
		public void log(LogEntry... entries) {
			record(traceEvents, entries);
		}

		@Override
		public void log(Exception e, LogEntry... entries) {
			record(traceEvents, entries);
		}

		@Override
		public void logFailure(LogEntry... entries) {
			record(failureEvents, entries);
		}

		@Override
		public void logFailure(Exception e, LogEntry... entries) {
			record(failureEvents, entries);
		}

		@Override
		public void logFailure(Throwable throwable, LogEntry... entries) {
			record(failureEvents, entries);
		}

		boolean containsFailureEvent(String event) {
			return containsEvent(failureEvents, event);
		}

		boolean awaitTraceEvent(String event) throws InterruptedException {
			return awaitEvent(traceEvents, event);
		}

		boolean awaitFailureEvent(String event) throws InterruptedException {
			return awaitEvent(failureEvents, event);
		}

		List<String> events() {
			return events(failureEvents);
		}

		List<String> traceEvents() {
			return events(traceEvents);
		}

		private boolean awaitEvent(List<String> events, String event) throws InterruptedException {
			long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();

			while (System.nanoTime() < deadline) {
				if (containsEvent(events, event))
					return true;

				Thread.sleep(10L);
			}

			return containsEvent(events, event);
		}

		private boolean containsEvent(List<String> events, String event) {
			synchronized (events) {
				return events.contains(event);
			}
		}

		private List<String> events(List<String> events) {
			synchronized (events) {
				return List.copyOf(events);
			}
		}

		private void record(List<String> events, LogEntry... entries) {
			if (entries == null)
				return;

			for (LogEntry entry : entries) {
				if (entry != null && "event".equals(entry.key())) {
					events.add(entry.value());
					return;
				}
			}
		}
	}

	private static class PartialWriteSocketChannel extends SocketChannel {
		private final ByteArrayOutputStream output;
		private final int maxBytesPerWrite;

		protected PartialWriteSocketChannel(int maxBytesPerWrite) {
			super(SelectorProvider.provider());
			this.output = new ByteArrayOutputStream();
			this.maxBytesPerWrite = maxBytesPerWrite;
		}

		byte[] getWrittenBytes() {
			return output.toByteArray();
		}

		@Override
		public int write(ByteBuffer src) throws IOException {
			int remaining = src.remaining();
			if (remaining == 0)
				return 0;

			int toWrite = Math.min(remaining, maxBytesPerWrite);
			byte[] buf = new byte[toWrite];
			src.get(buf);
			output.write(buf);
			return toWrite;
		}

		@Override
		public long write(ByteBuffer[] srcs, int offset, int length) throws IOException {
			long total = 0L;
			for (int i = offset; i < offset + length; i++)
				total += write(srcs[i]);
			return total;
		}

		@Override
		public int read(ByteBuffer dst) {
			throw new UnsupportedOperationException();
		}

		@Override
		public long read(ByteBuffer[] dsts, int offset, int length) {
			throw new UnsupportedOperationException();
		}

		@Override
		public SocketChannel bind(SocketAddress local) {
			return this;
		}

		@Override
		public <T> SocketChannel setOption(SocketOption<T> name, T value) {
			return this;
		}

		@Override
		public <T> T getOption(SocketOption<T> name) {
			throw new UnsupportedOperationException();
		}

		@Override
		public Set<SocketOption<?>> supportedOptions() {
			return Set.of();
		}

		@Override
		public SocketChannel shutdownInput() {
			return this;
		}

		@Override
		public SocketChannel shutdownOutput() {
			return this;
		}

		@Override
		public Socket socket() {
			throw new UnsupportedOperationException();
		}

		@Override
		public boolean isConnected() {
			return true;
		}

		@Override
		public boolean isConnectionPending() {
			return false;
		}

		@Override
		public boolean connect(SocketAddress remote) {
			return true;
		}

		@Override
		public boolean finishConnect() {
			return true;
		}

		@Override
		public SocketAddress getRemoteAddress() {
			return null;
		}

		@Override
		public SocketAddress getLocalAddress() {
			return null;
		}

		@Override
		protected void implCloseSelectableChannel() {
			// nothing to close
		}

		@Override
		protected void implConfigureBlocking(boolean block) {
			// no-op
		}
	}
}
