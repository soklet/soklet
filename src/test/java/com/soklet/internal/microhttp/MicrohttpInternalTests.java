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
	public void parserExposesContinueExpectationWhenWaitingForBody() {
		ByteTokenizer tokenizer = new ByteTokenizer();
		byte[] headers = ascii("POST / HTTP/1.1\r\nHost: localhost\r\nExpect: 100-continue\r\nContent-Length: 5\r\n\r\n");

		add(tokenizer, headers);
		RequestParser parser = new RequestParser(tokenizer, remoteAddress(), 1024);

		Assertions.assertFalse(parser.parse());
		Assertions.assertTrue(parser.readingBody());
		Assertions.assertTrue(parser.consumeContinueExpectation());
		Assertions.assertFalse(parser.consumeContinueExpectation());

		add(tokenizer, ascii("hello"));

		Assertions.assertTrue(parser.parse());
		Assertions.assertEquals("hello", new String(parser.request().body(), StandardCharsets.US_ASCII));
	}

	@Test
	public void parserDoesNotExposeContinueExpectationForHttpOneDotZero() {
		ByteTokenizer tokenizer = new ByteTokenizer();
		byte[] headers = ascii("POST / HTTP/1.0\r\nExpect: 100-continue\r\nContent-Length: 5\r\n\r\n");

		add(tokenizer, headers);
		RequestParser parser = new RequestParser(tokenizer, remoteAddress(), 1024);

		Assertions.assertFalse(parser.parse());
		Assertions.assertTrue(parser.readingBody());
		Assertions.assertFalse(parser.consumeContinueExpectation());

		add(tokenizer, ascii("hello"));

		Assertions.assertTrue(parser.parse());
		Assertions.assertEquals("hello", new String(parser.request().body(), StandardCharsets.US_ASCII));
	}

	@Test
	public void parserRejectsUnsupportedExpectation() {
		ByteTokenizer tokenizer = new ByteTokenizer();
		byte[] request = ascii("POST / HTTP/1.1\r\nHost: localhost\r\nExpect: 100-continue, wait\r\nContent-Length: 5\r\n\r\n");

		add(tokenizer, request);
		RequestParser parser = new RequestParser(tokenizer, remoteAddress(), 1024);

		Assertions.assertThrows(ExpectationFailedException.class, parser::parse);
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
	public void acceptBackoffEscalatesAndLoggingCoalesces() throws Exception {
		// The escalation schedule and power-of-two coalescing predicate are unit-tested in
		// com.soklet.internal.util.AcceptLoopBackoffTests; this test covers EventLoop's wiring.

		// Behavioral: repeated accept() failures keep the loop alive, fire the per-failure callback
		// every time, but log only at milestones, and reset after a recovery.
		List<String> failureEvents = new ArrayList<>();
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
			}
		};
		EventLoop eventLoop = new EventLoop(Options.builder()
				.withHost("127.0.0.1")
				.withPort(0)
				.withConcurrency(1)
				.withResolution(Duration.ofMillis(10))
				.build(), logger, (request, callback) -> {}, connectionListener);

		try {
			// Three consecutive accept() failures.
			for (int i = 0; i < 3; i++)
				Assertions.assertFalse(eventLoop.acceptReadyConnection(() -> {
					throw acceptFailure;
				}));

			Assertions.assertEquals(3, acceptFailures[0]);
			// Logged only at counts 1 and 2 (3 is not a power of two).
			Assertions.assertEquals(List.of("accept_loop_error", "accept_loop_error"), failureEvents);

			// A non-throwing accept() (no pending connection) marks recovery and resets the run.
			Assertions.assertFalse(eventLoop.acceptReadyConnection(() -> null));

			// The next failure is treated as the first again, so it logs.
			Assertions.assertFalse(eventLoop.acceptReadyConnection(() -> {
				throw acceptFailure;
			}));

			Assertions.assertEquals(4, acceptFailures[0]);
			Assertions.assertEquals(3, failureEvents.size());
			Assertions.assertFalse(eventLoop.isStopped());
		} finally {
			eventLoop.start();
			eventLoop.stop();
			eventLoop.join();
		}
	}

	@Test
	public void connectionSetupFailuresEscalateAndCoalesce() throws Exception {
		// A connection listener that throws on every accepted connection (e.g. a buggy user
		// callback) drives the RuntimeException path, which shares the escalating backoff and
		// power-of-two log coalescing with the accept() IOException path.
		List<String> failureEvents = new ArrayList<>();
		int[] setupFailures = {0};
		RuntimeException setupFailure = new RuntimeException("listener failure");
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
				for (LogEntry entry : entries) {
					if ("event".equals(entry.key()))
						failureEvents.add(entry.value());
				}
			}
		};
		ConnectionListener connectionListener = new ConnectionListener() {
			@Override
			public void willAcceptConnection(InetSocketAddress remoteAddress) {
				throw setupFailure;
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
				setupFailures[0]++;
			}
		};
		EventLoop eventLoop = new EventLoop(Options.builder()
				.withHost("127.0.0.1")
				.withPort(0)
				.withConcurrency(1)
				.withResolution(Duration.ofMillis(10))
				.build(), logger, (request, callback) -> {}, connectionListener);

		List<SocketChannel> acceptedChannels = new ArrayList<>();

		try {
			// Three consecutive setup failures on successfully-accepted connections.
			for (int i = 0; i < 3; i++)
				Assertions.assertFalse(eventLoop.acceptReadyConnection(() -> {
					SocketChannel socketChannel = SocketChannel.open();
					acceptedChannels.add(socketChannel);
					return socketChannel;
				}));

			Assertions.assertEquals(3, setupFailures[0]);
			// Logged only at counts 1 and 2 (3 is not a power of two).
			Assertions.assertEquals(List.of("connection_setup_error", "connection_setup_error"), failureEvents);

			// The failure path must close each accepted channel.
			for (SocketChannel acceptedChannel : acceptedChannels)
				Assertions.assertFalse(acceptedChannel.isOpen());

			// A non-throwing accept() (no pending connection) marks recovery and resets the run.
			Assertions.assertFalse(eventLoop.acceptReadyConnection(() -> null));

			// The next failure is treated as the first again, so it logs.
			Assertions.assertFalse(eventLoop.acceptReadyConnection(() -> {
				SocketChannel socketChannel = SocketChannel.open();
				acceptedChannels.add(socketChannel);
				return socketChannel;
			}));

			Assertions.assertEquals(4, setupFailures[0]);
			Assertions.assertEquals(3, failureEvents.size());
			Assertions.assertFalse(eventLoop.isStopped());
		} finally {
			for (SocketChannel acceptedChannel : acceptedChannels)
				if (acceptedChannel.isOpen())
					acceptedChannel.close();

			eventLoop.start();
			eventLoop.stop();
			eventLoop.join();
		}
	}

	@Test
	public void stopEndsInFlightBackoffSleepPromptly() throws Exception {
		// An in-flight backoff sleep (up to 1s during a sustained accept failure) must observe
		// stop() promptly instead of running to completion and delaying shutdown.
		EventLoop eventLoop = new EventLoop(Options.builder()
				.withHost("127.0.0.1")
				.withPort(0)
				.withConcurrency(1)
				.withResolution(Duration.ofMillis(10))
				.build(), NoopLogger.instance(), (request, callback) -> {}, NoopConnectionListener.instance());

		try {
			CountDownLatch sleeperStarted = new CountDownLatch(1);
			Thread sleeper = new Thread(() -> {
				sleeperStarted.countDown();
				eventLoop.sleepBeforeRetry(10_000L);
			});

			sleeper.start();
			Assertions.assertTrue(sleeperStarted.await(5, TimeUnit.SECONDS));
			// Give the sleeper a moment to actually enter its backoff sleep.
			Thread.sleep(100L);

			long stopNanos = System.nanoTime();
			eventLoop.stop();
			sleeper.join(5_000L);
			long elapsedMillis = (System.nanoTime() - stopNanos) / 1_000_000L;

			Assertions.assertFalse(sleeper.isAlive(), "Backoff sleep should have ended after stop()");
			Assertions.assertTrue(elapsedMillis < 2_000L,
					"Expected prompt exit from backoff sleep after stop(), took " + elapsedMillis + "ms");
		} finally {
			eventLoop.start();
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

		RequestTooLargeException exception = Assertions.assertThrows(RequestTooLargeException.class, parser::parse);
		Assertions.assertEquals(RequestTooLargeException.Reason.HEADERS, exception.reason());
	}

	@Test
	public void parserRejectsTooLargeHeaderSection() {
		ByteTokenizer tokenizer = new ByteTokenizer();
		byte[] request = ascii("GET / HTTP/1.1\r\nHost: localhost\r\nX-Test: abc\r\n\r\n");

		add(tokenizer, request);
		RequestParser parser = new RequestParser(tokenizer, remoteAddress(), 1024, 100, 19, 1024);

		RequestTooLargeException exception = Assertions.assertThrows(RequestTooLargeException.class, parser::parse);
		Assertions.assertEquals(RequestTooLargeException.Reason.HEADERS, exception.reason());
	}

	@Test
	public void parserRejectsIncompleteHeaderLineThatExceedsHeaderSectionLimit() {
		ByteTokenizer tokenizer = new ByteTokenizer();
		byte[] request = ascii("GET / HTTP/1.1\r\nHost: localhost\r\nX-Test: abc");

		add(tokenizer, request);
		RequestParser parser = new RequestParser(tokenizer, remoteAddress(), 1024, 100, 19, 1024);

		RequestTooLargeException exception = Assertions.assertThrows(RequestTooLargeException.class, parser::parse);
		Assertions.assertEquals(RequestTooLargeException.Reason.HEADERS, exception.reason());
	}

	@Test
	public void parserRejectsTooLongRequestTarget() {
		ByteTokenizer tokenizer = new ByteTokenizer();
		byte[] request = ascii("GET /too-long HTTP/1.1\r\nHost: localhost\r\n\r\n");

		add(tokenizer, request);
		RequestParser parser = new RequestParser(tokenizer, remoteAddress(),
				1024, 100, 4);

		RequestTooLargeException exception = Assertions.assertThrows(RequestTooLargeException.class, parser::parse);
		Assertions.assertEquals(RequestTooLargeException.Reason.URI_TOO_LONG, exception.reason());
	}

	@Test
	public void parserRejectsUnterminatedRequestTargetAtFirstOverLimitByte() {
		ByteTokenizer tokenizer = new ByteTokenizer();
		byte[] request = ascii("GET /1234");

		add(tokenizer, request);
		RequestParser parser = new RequestParser(tokenizer, remoteAddress(), 1024, 100, 4);

		RequestTooLargeException exception = Assertions.assertThrows(
				RequestTooLargeException.class, parser::parse);
		Assertions.assertEquals(RequestTooLargeException.Reason.URI_TOO_LONG,
				exception.reason());
		Assertions.assertEquals(request.length, parser.failureBoundaryExclusive());
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
	public void chunkDataTerminatorStopsCaptureAtFirstMismatchingByte() {
		ByteTokenizer tokenizer = new ByteTokenizer();
		String safePrefix = "POST / HTTP/1.1\r\n"
				+ "Host: localhost\r\n"
				+ "Transfer-Encoding: chunked\r\n"
				+ "\r\n"
				+ "3\r\n"
				+ "abcX";
		byte[] request = ascii(safePrefix
				+ "GET /pipelined-secret HTTP/1.1\r\n"
				+ "Authorization: must-not-be-captured\r\n\r\n");
		add(tokenizer, request);
		RequestParser parser = new RequestParser(tokenizer, remoteAddress(), 1024);

		Assertions.assertThrows(MalformedRequestException.class, parser::parse);
		ByteTokenizer.CapturedPrefix capture = tokenizer.capturePrefixAndRelease(
				parser.failureBoundaryExclusive(), Integer.MAX_VALUE);
		Assertions.assertEquals(safePrefix, ascii(capture.bytes()));
		Assertions.assertEquals(safePrefix.length(), capture.observedByteCount());
		Assertions.assertFalse(capture.truncated());
	}

	@Test
	public void chunkDataTerminatorValidatesSecondByteIncrementally() {
		ByteTokenizer tokenizer = new ByteTokenizer();
		String partial = "POST / HTTP/1.1\r\n"
				+ "Host: localhost\r\n"
				+ "Transfer-Encoding: chunked\r\n"
				+ "\r\n"
				+ "3\r\n"
				+ "abc\r";
		add(tokenizer, ascii(partial));
		RequestParser parser = new RequestParser(tokenizer, remoteAddress(), 1024);

		Assertions.assertFalse(parser.parse());
		add(tokenizer, ascii("XGET /pipelined-secret HTTP/1.1\r\n\r\n"));
		Assertions.assertThrows(MalformedRequestException.class, parser::parse);

		String safePrefix = partial + "X";
		ByteTokenizer.CapturedPrefix capture = tokenizer.capturePrefixAndRelease(
				parser.failureBoundaryExclusive(), Integer.MAX_VALUE);
		Assertions.assertEquals(safePrefix, ascii(capture.bytes()));
		Assertions.assertEquals(safePrefix.length(), capture.observedByteCount());
		Assertions.assertFalse(capture.truncated());
	}

	@Test
	public void httpVersionStopsCaptureAtFirstMismatchingByte() {
		ByteTokenizer tokenizer = new ByteTokenizer();
		String safePrefix = "GET / HTTX";
		add(tokenizer, ascii(safePrefix
				+ "GET /pipelined-secret HTTP/1.1\r\n"
				+ "Authorization: must-not-be-captured\r\n\r\n"));
		RequestParser parser = new RequestParser(tokenizer, remoteAddress(), 1024);

		Assertions.assertThrows(MalformedRequestException.class, parser::parse);
		ByteTokenizer.CapturedPrefix capture = tokenizer.capturePrefixAndRelease(
				parser.failureBoundaryExclusive(), Integer.MAX_VALUE);
		Assertions.assertEquals(safePrefix, ascii(capture.bytes()));
		Assertions.assertEquals(safePrefix.length(), capture.observedByteCount());
		Assertions.assertFalse(capture.truncated());
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
	public void chunkTrailerSectionOverLimitIsHeadersTooLarge() {
		ByteTokenizer tokenizer = new ByteTokenizer();
		byte[] request = ascii("POST / HTTP/1.1\r\n"
				+ "Host: a\r\n"
				+ "Transfer-Encoding: chunked\r\n"
				+ "\r\n"
				+ "3\r\n"
				+ "abc\r\n"
				+ "0\r\n"
				+ "X-Trailer: abcdefghijklmnopqrstuvwxyz0123456789\r\n"
				+ "\r\n");
		add(tokenizer, request);
		RequestParser parser = new RequestParser(tokenizer, remoteAddress(), 1024, 100, 40, 1024);

		RequestTooLargeException exception = Assertions.assertThrows(RequestTooLargeException.class, parser::parse);
		Assertions.assertEquals(RequestTooLargeException.Reason.HEADERS, exception.reason());
	}

	@Test
	public void incompleteChunkTrailerSectionOverLimitIsHeadersTooLarge() {
		ByteTokenizer tokenizer = new ByteTokenizer();
		byte[] request = ascii("POST / HTTP/1.1\r\n"
				+ "Host: a\r\n"
				+ "Transfer-Encoding: chunked\r\n"
				+ "\r\n"
				+ "3\r\n"
				+ "abc\r\n"
				+ "0\r\n"
				+ "X-Trailer: abcdefghijklmnopqrstuvwxyz0123456789");
		add(tokenizer, request);
		RequestParser parser = new RequestParser(tokenizer, remoteAddress(), 1024, 100, 40, 1024);

		RequestTooLargeException exception = Assertions.assertThrows(RequestTooLargeException.class, parser::parse);
		Assertions.assertEquals(RequestTooLargeException.Reason.HEADERS, exception.reason());
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
	public void fileTransferIdentifiesSinkFailuresWithoutSilencingSourceFailures(@TempDir Path directory) throws Exception {
		Path file = directory.resolve("source"); Files.writeString(file, "abc");
		IOException original = new IOException("an arbitrary non-English socket failure");
		try (FileChannel channel = FileChannel.open(file, READ)) {
			FileChannelWritableSource source = new FileChannelWritableSource(channel, 0, 3, false);
			SocketChannel socket = new PartialWriteSocketChannel(1) {
				@Override public int write(ByteBuffer bytes) throws IOException { throw original; }
			};
			SocketChannelIo.SocketIoException failure = Assertions.assertThrows(SocketChannelIo.SocketIoException.class,
					() -> source.writeTo(socket, 3));
			Assertions.assertSame(original, failure.getCause());
			Assertions.assertTrue(channel.isOpen());
		}
		try (FileChannel channel = FileChannel.open(file, READ); SocketChannel socket = SocketChannel.open()) {
			socket.close();
			FileChannelWritableSource source = new FileChannelWritableSource(channel, 0, 3, false);
			SocketChannelIo.SocketIoException failure = Assertions.assertThrows(SocketChannelIo.SocketIoException.class,
					() -> source.writeTo(socket, 3));
			Assertions.assertInstanceOf(java.nio.channels.ClosedChannelException.class, failure.getCause());
			Assertions.assertTrue(channel.isOpen());
		}
		FileChannel closed = FileChannel.open(file, READ); closed.close();
		FileChannelWritableSource unavailable = new FileChannelWritableSource(closed, 0, 3, false);
		Assertions.assertThrows(ResponseBodySourceException.class, () -> unavailable.writeTo(new PartialWriteSocketChannel(3), 3));
		try (FileChannel channel = FileChannel.open(file, READ)) {
			FileChannelWritableSource truncated = new FileChannelWritableSource(channel, 0, 4, false);
			SocketChannel socket = new PartialWriteSocketChannel(4);
			Assertions.assertEquals(3, truncated.writeTo(socket, 4));
			Assertions.assertThrows(ResponseBodySourceException.class, () -> truncated.writeTo(socket, 4));
		}
	}

	@Test
	public void customFileTransferKeepsTheTypedSocketTargetAndRequestedBytes(@TempDir Path directory) throws Exception {
		byte[] bytes = new byte[16_419];
		for (int index = 0; index < bytes.length; index++)
			bytes[index] = (byte) (index * 31);
		Path file = directory.resolve("source");
		Files.write(file, bytes);
		try (TransferProbeFileChannel channel = new TransferProbeFileChannel(FileChannel.open(file, READ), null)) {
			FileChannelWritableSource source = new FileChannelWritableSource(channel, 7, 16_401, false);
			PartialWriteSocketChannel socket = new PartialWriteSocketChannel(97);
			long transferred = 0;
			while (source.hasRemaining()) {
				long written = source.writeTo(socket, 1_441);
				Assertions.assertTrue(written > 0 && written <= 1_441);
				transferred += written;
			}
			Assertions.assertNotSame(socket, channel.transferTarget,
					"A custom file channel must write through the typed socket boundary.");
			Assertions.assertEquals(0, channel.probeReads, "Successful delivery must not probe or copy a byte.");
			Assertions.assertEquals(16_401, transferred);
			Assertions.assertArrayEquals(java.util.Arrays.copyOfRange(bytes, 7, 16_408), socket.getWrittenBytes());
		}
	}

	@Test
	public void readableFileDoesNotHideAnAmbiguousNativeTransferFailure(@TempDir Path directory) throws Exception {
		Path file = directory.resolve("source");
		Files.writeString(file, "abc");
		for (int socketWriteLimit : List.of(0, 1)) {
			IOException nativeFailure = new IOException("A native file transfer failed for an unknown reason");
			try (FileChannel channel = FileChannel.open(file, READ)) {
				FileChannelWritableSource source = new FileChannelWritableSource(channel, 0, 3, false);
				java.util.concurrent.atomic.AtomicInteger writes = new java.util.concurrent.atomic.AtomicInteger();
				PartialWriteSocketChannel socket = new PartialWriteSocketChannel(socketWriteLimit) {
					@Override public int write(ByteBuffer bytes) throws IOException {
						if (writes.getAndIncrement() == 0)
							throw nativeFailure;
						return super.write(bytes);
					}
				};
				ResponseBodySourceException failure = Assertions.assertThrows(ResponseBodySourceException.class,
						() -> source.writeTo(socket, 3));
				Assertions.assertSame(nativeFailure, failure.getCause());
				Assertions.assertEquals(2, writes.get(), "Native delivery uses the raw socket before the failure-only probe.");
				Assertions.assertEquals(socketWriteLimit, socket.getWrittenBytes().length,
						"A failed native delivery probes at most one byte and always stops afterwards.");
				Assertions.assertTrue(source.hasRemaining(), "No successful transfer count was returned.");
			}
		}
	}

	@Test
	public void customFileTransferFailureDoesNotProbeAReadableSourceOrSocket(@TempDir Path directory) throws Exception {
		Path file = directory.resolve("source");
		Files.writeString(file, "abc");
		IOException sourceFailure = new IOException("The custom transfer could not read the source");
		try (TransferProbeFileChannel channel = new TransferProbeFileChannel(FileChannel.open(file, READ), sourceFailure)) {
			FileChannelWritableSource source = new FileChannelWritableSource(channel, 0, 3, false);
			PartialWriteSocketChannel socket = new PartialWriteSocketChannel(3);
			ResponseBodySourceException failure = Assertions.assertThrows(ResponseBodySourceException.class,
					() -> source.writeTo(socket, 3));
			Assertions.assertSame(sourceFailure, failure.getCause());
			Assertions.assertEquals(0, failure.getSuppressed().length);
			Assertions.assertEquals(0, channel.probeReads);
			Assertions.assertEquals(0, socket.getWrittenBytes().length);
			Assertions.assertEquals(0, sourceFailure.getSuppressed().length,
					"Classification must not mutate an application-supplied failure.");
		}
	}

	@Test
	public void customFileTransferFailureAfterPartialProgressCannotCompleteACorruptBody(@TempDir Path directory) throws Exception {
		byte[] bytes = new byte[4_110];
		for (int index = 0; index < bytes.length; index++)
			bytes[index] = (byte) (index * 31);
		bytes[7] = 'A';
		bytes[4_102] = '!';
		Path file = directory.resolve("source");
		Files.write(file, bytes);
		for (int partialCount : List.of(1, 2_048, 4_095)) {
			IOException originalFailure = new IOException("A custom source failed after writing part of its slice");
			try (TransferProbeFileChannel channel = new TransferProbeFileChannel(FileChannel.open(file, READ), originalFailure)) {
				channel.bytesBeforeFailure = partialCount;
				FileChannelWritableSource source = new FileChannelWritableSource(channel, 7, 4_096, false);
				PartialWriteSocketChannel socket = new PartialWriteSocketChannel(97);
				ResponseBodySourceException failure = Assertions.assertThrows(ResponseBodySourceException.class,
						() -> source.writeTo(socket, 4_096));
				Assertions.assertSame(originalFailure, failure.getCause());
				Assertions.assertEquals(0, channel.probeReads, "Partial native progress on a custom channel is unknowable.");
				Assertions.assertEquals(partialCount, socket.getWrittenBytes().length,
						"A last-byte source failure must remain detectably truncated, not be filled with a wrong probe byte.");
				Assertions.assertArrayEquals(java.util.Arrays.copyOfRange(bytes, 7, 7 + partialCount), socket.getWrittenBytes());
				Assertions.assertTrue(source.hasRemaining());
			}
		}
	}

	@Test
	public void customFileTransferRetainsTheOriginalTypedSocketFailure(@TempDir Path directory) throws Exception {
		Path file = directory.resolve("source");
		Files.writeString(file, "abc");
		IOException originalFailure = new IOException("A socket write failed at the custom transfer's sink");
		try (TransferProbeFileChannel channel = new TransferProbeFileChannel(FileChannel.open(file, READ), null)) {
			FileChannelWritableSource source = new FileChannelWritableSource(channel, 0, 3, false);
			SocketChannel socket = new PartialWriteSocketChannel(1) {
				@Override public int write(ByteBuffer bytes) throws IOException { throw originalFailure; }
			};
			SocketChannelIo.SocketIoException failure = Assertions.assertThrows(SocketChannelIo.SocketIoException.class,
					() -> source.writeTo(socket, 3));
			Assertions.assertSame(originalFailure, failure.getCause());
			Assertions.assertEquals(0, failure.getSuppressed().length);
			Assertions.assertEquals(0, channel.probeReads);
			Assertions.assertTrue(channel.isOpen());
		}
	}

	@Test
	public void customFileTransferUnwrapsDecoratedTypedSocketFailureWithoutProbing(@TempDir Path directory) throws Exception {
		Path file = directory.resolve("source");
		Files.writeString(file, "abc");
		IOException originalFailure = new IOException("A socket write failed inside a decorating channel");
		try (TransferProbeFileChannel channel = new TransferProbeFileChannel(FileChannel.open(file, READ), null)) {
			channel.wrapSinkFailures = true;
			FileChannelWritableSource source = new FileChannelWritableSource(channel, 0, 3, false);
			java.util.concurrent.atomic.AtomicInteger writes = new java.util.concurrent.atomic.AtomicInteger();
			SocketChannel socket = new PartialWriteSocketChannel(1) {
				@Override public int write(ByteBuffer bytes) throws IOException {
					writes.incrementAndGet();
					throw originalFailure;
				}
			};
			SocketChannelIo.SocketIoException failure = Assertions.assertThrows(SocketChannelIo.SocketIoException.class,
					() -> source.writeTo(socket, 3));
			Assertions.assertSame(originalFailure, failure.getCause(),
					"Terminal observations must receive the original socket cause, not the channel's decorating wrapper.");
			Assertions.assertNotNull(channel.decoratedSinkFailure);
			Assertions.assertNotSame(failure, channel.decoratedSinkFailure);
			Assertions.assertSame(failure, channel.decoratedSinkFailure.getCause());
			Assertions.assertEquals(0, failure.getSuppressed().length);
			Assertions.assertEquals(0, channel.probeReads);
			Assertions.assertEquals(1, writes.get(), "A failed transfer must not write a later probe byte.");
			Assertions.assertTrue(source.hasRemaining());
			source.close();
			Assertions.assertTrue(channel.isOpen(), "The response borrows this application-owned file channel.");
		}
	}

	@Test
	public void customFileTransferKeepsSourceFailuresAndBoundedCauseChainsDiagnostic(@TempDir Path directory) throws Exception {
		Path file = directory.resolve("source");
		Files.writeString(file, "abc");
		SocketChannelIo.SocketIoException socketFailure = new SocketChannelIo.SocketIoException(
				new IOException("A separately recorded socket failure"));
		IOException independentSourceFailure = new ResponseBodySourceException("The file source failed", socketFailure);
		IOException decoratedSourceFailure = new IOException("A channel decorated a source failure", independentSourceFailure);
		IOException untypedFailure = new IOException("An untyped failure does not prove socket provenance",
				new java.net.SocketException("Broken pipe"));
		IOException cycleFirst = new IOException("First cyclic cause");
		IOException cycleSecond = new IOException("Second cyclic cause");
		cycleFirst.initCause(cycleSecond);
		cycleSecond.initCause(cycleFirst);
		IOException deepFailure = socketFailure;
		for (int depth = 0; depth < 16; depth++)
			deepFailure = new IOException("Cause wrapper " + depth, deepFailure);
		for (IOException originalFailure : List.of(independentSourceFailure, decoratedSourceFailure,
				untypedFailure, cycleFirst, deepFailure)) {
			try (TransferProbeFileChannel channel = new TransferProbeFileChannel(FileChannel.open(file, READ), originalFailure)) {
				FileChannelWritableSource source = new FileChannelWritableSource(channel, 0, 3, false);
				PartialWriteSocketChannel socket = new PartialWriteSocketChannel(3);
				ResponseBodySourceException failure = Assertions.assertThrows(ResponseBodySourceException.class,
						() -> source.writeTo(socket, 3));
				Assertions.assertSame(originalFailure, failure.getCause());
				Assertions.assertEquals(0, failure.getSuppressed().length);
				Assertions.assertEquals(0, originalFailure.getSuppressed().length);
				Assertions.assertEquals(0, channel.probeReads);
				Assertions.assertEquals(0, socket.getWrittenBytes().length);
				Assertions.assertTrue(source.hasRemaining());
			}
		}
		IOException withinBound = socketFailure;
		for (int depth = 0; depth < 15; depth++)
			withinBound = new IOException("Cause wrapper " + depth, withinBound);
		try (TransferProbeFileChannel channel = new TransferProbeFileChannel(FileChannel.open(file, READ), withinBound)) {
			FileChannelWritableSource source = new FileChannelWritableSource(channel, 0, 3, false);
			PartialWriteSocketChannel socket = new PartialWriteSocketChannel(3);
			SocketChannelIo.SocketIoException failure = Assertions.assertThrows(SocketChannelIo.SocketIoException.class,
					() -> source.writeTo(socket, 3));
			Assertions.assertSame(socketFailure, failure, "The sixteenth cause remains within the bounded inspection.");
			Assertions.assertEquals(0, channel.probeReads);
			Assertions.assertEquals(0, socket.getWrittenBytes().length);
		}
	}

	@Test
	public void nativeFileProbeReadFailurePreservesBothCausesAndDoesNotWriteAgain(@TempDir Path directory) throws Exception {
		Path file = directory.resolve("source");
		Files.writeString(file, "abc");
		IOException nativeFailure = new IOException("The native transfer failed before reporting progress");
		boolean previouslyInterrupted = Thread.interrupted();
		try (FileChannel channel = FileChannel.open(file, READ)) {
			Assertions.assertEquals("sun.nio.ch.FileChannelImpl", channel.getClass().getName(),
					"This test must cover the actual JDK transfer path.");
			FileChannelWritableSource source = new FileChannelWritableSource(channel, 0, 3, false);
			java.util.concurrent.atomic.AtomicInteger writes = new java.util.concurrent.atomic.AtomicInteger();
			PartialWriteSocketChannel socket = new PartialWriteSocketChannel(1) {
				@Override public int write(ByteBuffer bytes) throws IOException {
					writes.incrementAndGet();
					Thread.currentThread().interrupt();
					throw nativeFailure;
				}
			};
			ResponseBodySourceException failure = Assertions.assertThrows(ResponseBodySourceException.class,
					() -> source.writeTo(socket, 3));
			Assertions.assertSame(nativeFailure, failure.getCause());
			Assertions.assertEquals(1, failure.getSuppressed().length);
			Assertions.assertInstanceOf(java.nio.channels.ClosedByInterruptException.class, failure.getSuppressed()[0]);
			Assertions.assertEquals(0, nativeFailure.getSuppressed().length,
					"Secondary evidence belongs to Soklet's wrapper, not the original failure.");
			Assertions.assertEquals(1, writes.get(), "A failing source probe must not attempt a second socket write.");
			Assertions.assertEquals(0, socket.getWrittenBytes().length);
			Assertions.assertTrue(source.hasRemaining());
			Assertions.assertFalse(channel.isOpen(), "The JDK closes the file channel on its interrupted read.");
			source.close();
		} finally {
			Thread.interrupted();
			if (previouslyInterrupted)
				Thread.currentThread().interrupt();
		}
	}

	@Test
	public void failedSocketProbePreservesNativeCauseAndIndependentSinkEvidence(@TempDir Path directory) throws Exception {
		Path file = directory.resolve("source");
		Files.writeString(file, "abc");
		IOException nativeFailure = new IOException("The native transfer failed without identifying its boundary");
		IOException socketFailure = new IOException("A direct socket write independently failed");
		try (FileChannel channel = FileChannel.open(file, READ)) {
			FileChannelWritableSource source = new FileChannelWritableSource(channel, 0, 3, false);
			java.util.concurrent.atomic.AtomicInteger writes = new java.util.concurrent.atomic.AtomicInteger();
			SocketChannel socket = new PartialWriteSocketChannel(1) {
				@Override public int write(ByteBuffer bytes) throws IOException {
					throw writes.getAndIncrement() == 0 ? nativeFailure : socketFailure;
				}
			};
			SocketChannelIo.SocketIoException failure = Assertions.assertThrows(SocketChannelIo.SocketIoException.class,
					() -> source.writeTo(socket, 3));
			Assertions.assertSame(nativeFailure, failure.getCause());
			Assertions.assertEquals(1, failure.getSuppressed().length);
			Assertions.assertInstanceOf(SocketChannelIo.SocketIoException.class, failure.getSuppressed()[0]);
			Assertions.assertSame(socketFailure, failure.getSuppressed()[0].getCause());
			Assertions.assertEquals(2, writes.get());
		}
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
	public void headBodyOmissionPreservesLengthAndClosesOwnedFileChannel(@TempDir Path tempDir) throws IOException {
		Path file = tempDir.resolve("owned.txt");
		Files.writeString(file, "abcdef", StandardCharsets.US_ASCII);
		FileChannel fileChannel = FileChannel.open(file, READ);
		MicrohttpResponse response = MicrohttpResponse.withFileChannelBody(
				200,
				"OK",
				List.of(new Header("X-Test", "yes")),
				fileChannel,
				1L,
				4L,
				true);

		MicrohttpResponse headResponse = response.withBodyOmittedForHead();

		Assertions.assertFalse(fileChannel.isOpen());
		Assertions.assertEquals(0L, headResponse.bodyLength());
		Assertions.assertArrayEquals(new byte[0], headResponse.body());
		Assertions.assertEquals(List.of("4"), headResponse.headers().stream()
				.filter(header -> header.name().equalsIgnoreCase("Content-Length"))
				.map(Header::value)
				.toList());
		Assertions.assertTrue(headResponse.headers().stream()
				.anyMatch(header -> header.name().equals("X-Test")
						&& header.value().equals("yes")));
	}

	@Test
	public void headBodyOmissionLeavesBorrowedFileChannelOpen(@TempDir Path tempDir) throws IOException {
		Path file = tempDir.resolve("borrowed.txt");
		Files.writeString(file, "abcdef", StandardCharsets.US_ASCII);

		try (FileChannel fileChannel = FileChannel.open(file, READ)) {
			MicrohttpResponse response = MicrohttpResponse.withFileChannelBody(
					200,
					"OK",
					List.of(new Header("Content-Length", "4")),
					fileChannel,
					1L,
					4L,
					false);

			MicrohttpResponse headResponse = response.withBodyOmittedForHead();

			Assertions.assertTrue(fileChannel.isOpen());
			Assertions.assertEquals(List.of("4"), headResponse.headers().stream()
					.filter(header -> header.name().equalsIgnoreCase("Content-Length"))
					.map(Header::value)
					.toList());
		}
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
			Assertions.assertFalse(logger.failureCauses.isEmpty());
			Assertions.assertTrue(logger.failureCauses.stream().anyMatch(IOException.class::isInstance));
			Assertions.assertTrue(logger.failureCauses.stream().noneMatch(SocketChannelIo.SocketIoException.class::isInstance));
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
	public void transportAddsFreshDateAndPreservesExplicitDateWithoutDuplicates() throws Exception {
		String explicitDate = "Thu, 01 Jan 1970 00:00:00 GMT";
		Options options = OptionsBuilder.newBuilder().withPort(0).withConcurrency(1).build();
		EventLoop eventLoop = new EventLoop(options, new RecordingLogger(), (request, callback) -> {
			List<Header> headers = "/explicit".equals(request.uri())
					? List.of(new Header("dAtE", explicitDate)) : List.of();
			callback.accept(new MicrohttpResponse(200, "OK", headers, ascii("pong")));
		});
		eventLoop.start();
		try {
			Thread.sleep(1_100L);
			java.time.Instant beforeRequest = java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
			for (String path : List.of("/fresh", "/explicit")) {
				String response = sendRequestAndReadResponse(eventLoop.getPort(),
						"GET " + path + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
				List<String> dates = response.substring(0, response.indexOf("\r\n\r\n")).lines()
						.filter(line -> line.regionMatches(true, 0, "Date:", 0, 5))
						.map(line -> line.substring(5).trim()).toList();
				Assertions.assertEquals(1, dates.size(), response);
				if ("/explicit".equals(path)) {
					Assertions.assertEquals(explicitDate, dates.get(0));
				} else {
					java.time.Instant date = com.soklet.HttpDate.fromHeaderValue(dates.get(0)).orElseThrow();
					Assertions.assertFalse(date.isBefore(beforeRequest), "Date was cached before response generation");
					Assertions.assertFalse(date.isAfter(java.time.Instant.now()));
				}
			}
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
			String fallback = response.substring(response.indexOf("HTTP/1.1 400 Bad Request"));
			String date = fallback.lines().filter(line -> line.startsWith("Date: "))
					.findFirst().orElseThrow().substring("Date: ".length());
			Assertions.assertTrue(com.soklet.HttpDate.fromHeaderValue(date).isPresent(), date);
			Assertions.assertTrue(Math.abs(Duration.between(
					com.soklet.HttpDate.fromHeaderValue(date).orElseThrow(),
					java.time.Instant.now()).toSeconds()) <= 2, date);
			Assertions.assertTrue(logger.containsFailureEvent("malformed_request"), logger.events().toString());
			Assertions.assertFalse(logger.containsFailureEvent("write_error"), logger.events().toString());
		} finally {
			eventLoop.stop();
			eventLoop.join();
		}
	}

	@Test
	public void bodylessStatusSuppressesBodyBeforeNextPipelinedResponse() throws Exception {
		Options options = OptionsBuilder.newBuilder()
				.withPort(0)
				.withResolution(Duration.ofMillis(10))
				.withRequestHeaderTimeout(Duration.ofSeconds(2))
				.withRequestBodyTimeout(Duration.ofSeconds(2))
				.withConcurrency(1)
				.build();
		RecordingLogger logger = new RecordingLogger();
		EventLoop eventLoop = new EventLoop(options, logger, (request, callback) -> {
			if ("/bodyless".equals(request.uri())) {
				callback.accept(new MicrohttpResponse(204, "No Content", List.of(
						new Header("Content-Length", "14"),
						new Header("Transfer-Encoding", "chunked"),
						new Header("X-Test", "yes")), ascii("must-not-write")));
				return;
			}

			callback.accept(new MicrohttpResponse(200, "OK", List.of(), ascii("pong")));
		});

		eventLoop.start();

		try {
			String response = sendRequestAndReadResponse(eventLoop.getPort(), "GET /bodyless HTTP/1.1\r\n"
					+ "Host: localhost\r\n"
					+ "\r\n"
					+ "GET /ok HTTP/1.1\r\n"
					+ "Host: localhost\r\n"
					+ "Connection: close\r\n"
					+ "\r\n");

			Assertions.assertTrue(response.startsWith("HTTP/1.1 204 No Content"), response);
			String firstHead = response.substring(0, response.indexOf("\r\n\r\n"));
			Assertions.assertFalse(firstHead.contains("Content-Length"), firstHead);
			Assertions.assertFalse(firstHead.contains("Transfer-Encoding"), firstHead);
			Assertions.assertTrue(firstHead.contains("X-Test: yes"), firstHead);
			Assertions.assertTrue(response.contains("\r\n\r\nHTTP/1.1 200 OK"), response);
			Assertions.assertFalse(response.contains("must-not-write"), response);
			Assertions.assertTrue(response.endsWith("pong"), response);
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
		private final List<Throwable> failureCauses = Collections.synchronizedList(new ArrayList<>());

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
			failureCauses.add(e);
			record(failureEvents, entries);
		}

		@Override
		public void logFailure(Throwable throwable, LogEntry... entries) {
			failureCauses.add(throwable);
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

	private static final class TransferProbeFileChannel extends FileChannel {
		private final FileChannel delegate;
		private final IOException transferFailure;
		private boolean wrapSinkFailures;
		private IOException decoratedSinkFailure;
		private int bytesBeforeFailure;
		private java.nio.channels.WritableByteChannel transferTarget;
		private int probeReads;

		private TransferProbeFileChannel(FileChannel delegate, IOException transferFailure) {
			this.delegate = delegate;
			this.transferFailure = transferFailure;
		}

		@Override public long transferTo(long position, long count, java.nio.channels.WritableByteChannel target) throws IOException {
			transferTarget = target;
			if (transferFailure != null) {
				if (bytesBeforeFailure > 0) {
					ByteBuffer bytes = ByteBuffer.allocate((int) Math.min(count, bytesBeforeFailure));
					while (bytes.hasRemaining())
						Assertions.assertTrue(delegate.read(bytes, position + bytes.position()) > 0);
					bytes.flip();
					while (bytes.hasRemaining())
						Assertions.assertTrue(target.write(bytes) > 0);
				}
				throw transferFailure;
			}
			try {
				return delegate.transferTo(position, count, target);
			} catch (IOException failure) {
				if (!wrapSinkFailures)
					throw failure;
				decoratedSinkFailure = new IOException("The decorating file channel could not complete its transfer", failure);
				throw decoratedSinkFailure;
			}
		}
		@Override public int read(ByteBuffer buffer, long position) throws IOException {
			probeReads++;
			Assertions.assertEquals(1, buffer.remaining());
			return delegate.read(buffer, position);
		}
		@Override public long size() throws IOException { return delegate.size(); }
		@Override protected void implCloseChannel() throws IOException { delegate.close(); }
		@Override public int read(ByteBuffer buffer) throws IOException { return delegate.read(buffer); }
		@Override public long read(ByteBuffer[] buffers, int offset, int length) throws IOException { return delegate.read(buffers, offset, length); }
		@Override public int write(ByteBuffer buffer) throws IOException { return delegate.write(buffer); }
		@Override public long write(ByteBuffer[] buffers, int offset, int length) throws IOException { return delegate.write(buffers, offset, length); }
		@Override public int write(ByteBuffer buffer, long position) throws IOException { return delegate.write(buffer, position); }
		@Override public long position() throws IOException { return delegate.position(); }
		@Override public FileChannel position(long position) throws IOException { delegate.position(position); return this; }
		@Override public FileChannel truncate(long size) throws IOException { delegate.truncate(size); return this; }
		@Override public void force(boolean metadata) throws IOException { delegate.force(metadata); }
		@Override public long transferFrom(java.nio.channels.ReadableByteChannel source, long position, long count) throws IOException { return delegate.transferFrom(source, position, count); }
		@Override public java.nio.MappedByteBuffer map(MapMode mode, long position, long size) throws IOException { return delegate.map(mode, position, size); }
		@Override public java.nio.channels.FileLock lock(long position, long size, boolean shared) throws IOException { return delegate.lock(position, size, shared); }
		@Override public java.nio.channels.FileLock tryLock(long position, long size, boolean shared) throws IOException { return delegate.tryLock(position, size, shared); }
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
