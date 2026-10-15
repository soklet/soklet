package com.soklet;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SseHeaderScannerTests {
	@Test
	void fragmentedAndCoalescedHeadersKeepTheFirstTerminatorAndHeaderByteCount() {
		for (String request : List.of(
				"GET /events HTTP/1.1\r\nHost: localhost\r\n\r\nignored\n\n",
				"GET /events HTTP/1.1\nHost: localhost\n\nignored\r\n\r\n",
				"GET /events HTTP/1.1\r\nHost: localhost\r\n\nignored",
				"GET /events HTTP/1.1\rHost: localhost\r\n\r\n",
				"GET /events HTTP/1.1", "\r\n\r\n", "\n\n")) {
			for (int fragmentSize = 1; fragmentSize <= request.length(); fragmentSize++)
				assertFragmented(request, fragmentSize);
		}
	}

	@Test
	void boundaryDetectionMatchesCompletePrefixScanningForArbitraryLineEndings() {
		Random random = new Random(0x5EED);
		char[] alphabet = {'a', 'b', '\r', '\n'};
		for (int sample = 0; sample < 2_000; sample++) {
			StringBuilder request = new StringBuilder();
			for (int index = 0; index < 40; index++) request.append(alphabet[random.nextInt(alphabet.length)]);
			assertFragmented(request.toString(), 1 + random.nextInt(8));
		}
	}

	@Test
	void oneByteFragmentsRequireLinearInspectionEvenBeforeTheRequestLineEnds() {
		for (String request : List.of("a".repeat(65_536), "GET / HTTP/1.1\r\nX: " + "a".repeat(65_536))) {
			CountingSequence sequence = new CountingSequence(request);
			DefaultSseServer.HandshakeHeaderScanner scanner = new DefaultSseServer.HandshakeHeaderScanner();
			for (int length = 1; length <= request.length(); length++) {
				sequence.length = length;
				assertEquals(-1, scanner.scan(sequence));
			}
			assertTrue(sequence.reads <= 5L * request.length(), "Repeated fragments rescanned an existing prefix");
		}
	}

	private static void assertFragmented(String request, int fragmentSize) {
		DefaultSseServer.HandshakeHeaderScanner scanner = new DefaultSseServer.HandshakeHeaderScanner();
		StringBuilder prefix = new StringBuilder();
		for (int start = 0; start < request.length(); start += fragmentSize) {
			prefix.append(request, start, Math.min(start + fragmentSize, request.length()));
			String text = prefix.toString();
			int crlf = text.indexOf("\r\n\r\n"), lf = text.indexOf("\n\n");
			int expectedEnd = crlf == -1 ? (lf == -1 ? -1 : lf + 2)
					: (lf == -1 ? crlf + 4 : Math.min(crlf + 4, lf + 2));
			assertEquals(expectedEnd, scanner.scan(prefix), text);
			int attributedLength = expectedEnd == -1 ? text.length() : expectedEnd;
			int requestLineEnd = -1;
			for (int index = 0; index < attributedLength; index++) {
				char character = text.charAt(index);
				if (character == '\r' || character == '\n') {
					requestLineEnd = index + 1;
					if (character == '\r' && requestLineEnd < attributedLength && text.charAt(requestLineEnd) == '\n')
						requestLineEnd++;
					break;
				}
			}
			assertEquals(requestLineEnd == -1 ? 0 : attributedLength - requestLineEnd,
					scanner.headerSectionLength(), text);
		}
	}

	private static final class CountingSequence implements CharSequence {
		private final String value;
		private int length;
		private long reads;
		private CountingSequence(String value) { this.value = value; }
		@Override public int length() { return this.length; }
		@Override public char charAt(int index) { this.reads++; return this.value.charAt(index); }
		@Override public CharSequence subSequence(int start, int end) { return this.value.subSequence(start, end); }
	}
}
