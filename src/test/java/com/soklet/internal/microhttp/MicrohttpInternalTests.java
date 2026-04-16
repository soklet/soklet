package com.soklet.internal.microhttp;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

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

	private static void add(ByteTokenizer tokenizer, byte[] bytes) {
		tokenizer.add(ByteBuffer.wrap(bytes));
	}

	private static byte[] ascii(String value) {
		return value.getBytes(StandardCharsets.US_ASCII);
	}

	private static InetSocketAddress remoteAddress() {
		return new InetSocketAddress("127.0.0.1", 12345);
	}
}
