package com.soklet.internal.microhttp;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

class RequestParserFramingTests {

	private static final String PRECEDING_REQUEST = "GET / HTTP/1.1\r\nHost: a\r\n\r\n";
	@Test
	void headerLimitIsIndependentOfEveryPipelinedReadSplit() {
		String requestLine = "GET / HTTP/1.1\r\n";
		String headers = "Host: aa\r\nX: " + "a".repeat(25) + "\r\n\r\n";
		assertSectionLimitAtEverySplit(requestLine + headers, headers.length());
	}

	@Test
	void trailerLimitIsIndependentOfEveryPipelinedReadSplit() {
		String prefix = "POST / HTTP/1.1\r\nHost: a\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n";
		String trailers = "X: " + "a".repeat(60) + "\r\n\r\n";
		assertSectionLimitAtEverySplit(prefix + trailers, trailers.length());
	}

	@Test
	void incompleteSectionsStayBoundedAfterCompaction() {
		assertIncompleteSectionRejected("GET / HTTP/1.1\r\nHost: aa\r\nX: ",
				"a".repeat(25), 32);
		assertIncompleteSectionRejected(
				"POST / HTTP/1.1\r\nHost: a\r\nTransfer-Encoding: chunked\r\n\r\n0\r\nX: ",
				"a".repeat(62), 64);
	}

	@Test
	void headerLimitSurvivesRepeatedPipelinedCompactions() {
		ByteTokenizer tokenizer = new ByteTokenizer();
		RequestParser parser = parser(tokenizer, 32);
		add(tokenizer, PRECEDING_REQUEST + "GET /next HTTP/1.1\r\nHost: a\r\nX: ");
		Assertions.assertTrue(parser.parse());
		for (int iteration = 0; iteration < 6; iteration++) {
			tokenizer.compact();
			parser.reset();
			Assertions.assertFalse(parser.parse());
			int previousPosition = tokenizer.rawPosition();
			// The next request forces another allocation/rebase while the current
			// request is paused in its headers. All six share the same tokenizer.
			String nextPrefix = "GET /" + "n".repeat(tokenizer.capacity() + 1)
					+ " HTTP/1.1\r\nHost: a\r\nX: ";
			add(tokenizer, "ok\r\n\r\n" + nextPrefix);
			Assertions.assertTrue(tokenizer.rawPosition() < previousPosition);
			Assertions.assertTrue(parser.parse());
		}
		tokenizer.compact();
		parser.reset();
		Assertions.assertFalse(parser.parse());
		add(tokenizer, "a".repeat(25) + "\r\n\r\n");
		RequestTooLargeException failure = Assertions.assertThrows(
				RequestTooLargeException.class, parser::parse);
		Assertions.assertEquals(RequestTooLargeException.Reason.HEADERS, failure.reason());
	}

	@Test
	void contentLengthRejectsAnythingOtherThanAsciiDigits() {
		for (String value : new String[]{"+1", "-0", "-1", "1 0", "1\t0", "", "\t ",
				"\u00b2", "\u0661", "\uff11", "1,1", "0x1"}) {
			ByteTokenizer tokenizer = new ByteTokenizer();
			// UTF-8 deliberately exercises actual non-ASCII wire bytes, without
			// the US-ASCII encoder silently replacing them with question marks.
			tokenizer.add(ByteBuffer.wrap(("POST / HTTP/1.1\r\nHost: a\r\nContent-Length: "
					+ value + "\r\n\r\nx").getBytes(StandardCharsets.UTF_8)));
			Assertions.assertThrows(MalformedRequestException.class,
					new RequestParser(tokenizer)::parse, "Content-Length: " + value);
		}
	}

	@Test
	void contentLengthAcceptsDigitsLeadingZerosAndSurroundingOws() {
		for (String value : new String[]{"0", "000", "1", "0001", " \t0001\t "}) {
			ByteTokenizer tokenizer = new ByteTokenizer();
			add(tokenizer, "POST / HTTP/1.1\r\nHost: a\r\nContent-Length: "
					+ value + "\r\n\r\nx");
			RequestParser parser = new RequestParser(tokenizer);
			Assertions.assertTrue(parser.parse(), "Content-Length: " + value);
			Assertions.assertEquals(Integer.parseInt(value.trim()), parser.request().body().length);
		}
	}

	@Test
	void contentLengthPreservesOverflowAndConfiguredLimitClassification() {
		for (String value : new String[]{"9223372036854775808", "99999999999999999999"}) {
			ByteTokenizer tokenizer = new ByteTokenizer();
			add(tokenizer, "POST / HTTP/1.1\r\nHost: a\r\nContent-Length: " + value + "\r\n\r\n");
			Assertions.assertThrows(MalformedRequestException.class,
					new RequestParser(tokenizer)::parse);
		}
		for (String value : new String[]{"1025", "2147483648", "9223372036854775807"}) {
			ByteTokenizer tokenizer = new ByteTokenizer();
			add(tokenizer, "POST / HTTP/1.1\r\nHost: a\r\nContent-Length: " + value + "\r\n\r\n");
			RequestTooLargeException failure = Assertions.assertThrows(RequestTooLargeException.class,
					new RequestParser(tokenizer, null, 1024)::parse);
			Assertions.assertEquals(RequestTooLargeException.Reason.CONTENT, failure.reason());
		}
		ByteTokenizer tokenizer = new ByteTokenizer();
		add(tokenizer, "POST / HTTP/1.1\r\nHost: a\r\nContent-Length: 001024\r\n\r\n" + "x".repeat(1024));
		RequestParser parser = new RequestParser(tokenizer, null, 1024);
		Assertions.assertTrue(parser.parse());
		Assertions.assertEquals(1024, parser.request().body().length);
	}

	@Test
	void contentLengthStillRejectsDuplicateAndCompetingFraming() {
		for (String headers : new String[]{"Content-Length: 1\r\nContent-Length: 01\r\n",
				"Content-Length: 1\r\nTransfer-Encoding: chunked\r\n",
				"Transfer-Encoding: chunked\r\nContent-Length: 1\r\n"}) {
			ByteTokenizer tokenizer = new ByteTokenizer();
			add(tokenizer, "POST / HTTP/1.1\r\nHost: a\r\n" + headers + "\r\nx");
			Assertions.assertThrows(MalformedRequestException.class,
					new RequestParser(tokenizer)::parse);
		}
	}

	private static void assertSectionLimitAtEverySplit(String request, int sectionLength) {
		for (int limit : new int[]{sectionLength, sectionLength - 1}) {
			for (int split = 0; split <= request.length(); split++) {
				ByteTokenizer tokenizer = new ByteTokenizer();
				RequestParser parser = parser(tokenizer, limit);
				add(tokenizer, PRECEDING_REQUEST + request.substring(0, split));
				Assertions.assertTrue(parser.parse());
				tokenizer.compact();
				parser.reset();
				boolean rejected = false;
				try {
					boolean complete = parser.parse();
					if (!complete) {
						add(tokenizer, request.substring(split)
								+ "GET /following-secret HTTP/1.1\r\nHost: secret\r\n\r\n");
						Assertions.assertTrue(parser.parse());
					}
				} catch (RequestTooLargeException failure) {
					rejected = true;
					Assertions.assertEquals(RequestTooLargeException.Reason.HEADERS, failure.reason());
					ByteTokenizer.CapturedPrefix capture = tokenizer.capturePrefixAndRelease(
							parser.failureBoundaryExclusive(), Integer.MAX_VALUE);
					String captured = new String(capture.bytes(), StandardCharsets.US_ASCII);
					Assertions.assertTrue(request.startsWith(captured));
					Assertions.assertTrue(captured.length() > request.indexOf("\r\n") + 2);
					Assertions.assertFalse(captured.contains("secret"));
					Assertions.assertEquals(captured.length(), capture.observedByteCount());
				}
				Assertions.assertEquals(limit < sectionLength, rejected,
						"section limit=" + limit + ", read split=" + split);
			}
		}
	}

	private static void assertIncompleteSectionRejected(String prefix, String suffix, int limit) {
		ByteTokenizer tokenizer = new ByteTokenizer();
		RequestParser parser = parser(tokenizer, limit);
		add(tokenizer, PRECEDING_REQUEST + prefix);
		Assertions.assertTrue(parser.parse());
		tokenizer.compact();
		parser.reset();
		Assertions.assertFalse(parser.parse());
		int previousPosition = tokenizer.rawPosition();
		add(tokenizer, suffix);
		Assertions.assertTrue(tokenizer.rawPosition() < previousPosition);
		RequestTooLargeException failure = Assertions.assertThrows(RequestTooLargeException.class, parser::parse);
		Assertions.assertEquals(RequestTooLargeException.Reason.HEADERS, failure.reason());
		ByteTokenizer.CapturedPrefix capture = tokenizer.capturePrefixAndRelease(
				parser.failureBoundaryExclusive(), Integer.MAX_VALUE);
		Assertions.assertEquals(prefix + suffix, new String(capture.bytes(), StandardCharsets.US_ASCII));
	}

	private static RequestParser parser(ByteTokenizer tokenizer, int headerLimit) {
		return new RequestParser(tokenizer, null, Integer.MAX_VALUE, 100, headerLimit, Integer.MAX_VALUE);
	}

	private static void add(ByteTokenizer tokenizer, String text) {
		tokenizer.add(ByteBuffer.wrap(text.getBytes(StandardCharsets.US_ASCII)));
	}
}
