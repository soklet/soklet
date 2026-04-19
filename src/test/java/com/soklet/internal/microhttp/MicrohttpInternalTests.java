package com.soklet.internal.microhttp;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
import java.util.List;
import java.util.Set;

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

	private static void add(ByteTokenizer tokenizer, byte[] bytes) {
		tokenizer.add(ByteBuffer.wrap(bytes));
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
