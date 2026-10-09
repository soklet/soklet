package com.soklet.internal.microhttp;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RequestParserHardeningTests {
    private static final String CHUNKED_PREFIX = "POST / HTTP/1.1\r\nHost: a\r\nTransfer-Encoding: chunked\r\n\r\n";

    @Test
    void targetsRejectControlsAndVersionsRequireExactHttpPrefix() {
        for (char control : new char[]{0, 1, '\r', '\n', 0x1b, 0x7f}) {
            ByteTokenizer tokenizer = new ByteTokenizer();
            add(tokenizer, "GET /a" + control + "b HTTP/1.1\r\nHost: a\r\n\r\n");
            assertThrows(MalformedRequestException.class, new RequestParser(tokenizer)::parse);
        }
        for (String version : List.of("http/1.1", "Http/1.1", "HTTP/1.1")) {
            ByteTokenizer tokenizer = new ByteTokenizer();
            add(tokenizer, "GET / HTTP/1.1\r\nHost: a\r\n\r\n".replace("HTTP/1.1", version));
            if (version.equals("HTTP/1.1")) assertTrue(new RequestParser(tokenizer).parse());
            else assertThrows(MalformedRequestException.class, new RequestParser(tokenizer)::parse);
        }
    }

    @Test
    void http10TransferEncodingIsMalformedRegardlessOfKeepAliveOrContentLength() {
        for (String fields : List.of("Transfer-Encoding: chunked\r\n",
                "Transfer-Encoding: chunked\r\nContent-Length: 0\r\n",
                "Transfer-Encoding: identity\r\n", "Transfer-Encoding:\r\n")) {
            ByteTokenizer tokenizer = new ByteTokenizer();
            add(tokenizer, "POST / HTTP/1.0\r\nConnection: keep-alive\r\n" + fields + "\r\n0\r\n\r\n");
            assertThrows(MalformedRequestException.class, new RequestParser(tokenizer)::parse, fields);
        }
    }

    @Test
    void http10ContentLengthAndPipeliningStillWork() {
        ByteTokenizer tokenizer = new ByteTokenizer();
        add(tokenizer, "POST /one HTTP/1.0\r\nContent-Length: 1\r\nConnection: keep-alive\r\n\r\nx"
                + "GET /two HTTP/1.0\r\n\r\n");
        RequestParser parser = new RequestParser(tokenizer);
        assertTrue(parser.parse());
        assertArrayEquals(new byte[]{'x'}, parser.request().body());
        tokenizer.compact();
        parser.reset();
        assertTrue(parser.parse());
        assertEquals("/two", parser.request().uri());
    }

    @Test
    void chunkSizeAndExtensionGrammarRejectsInvalidBytesAndIncompleteSyntax() {
        for (String line : List.of(" 1", "1 ", "\t1", "+1", "-1", "1g", "1;", "1;=value", "1;name=",
                "1;name=two words", "1;name=\"unclosed", "1;name=\"closed\"tail", "1;name=\"escape\\\"",
                "1;name=a\nb", "1;name=a\rb", "1;name=a\u0000b", "1;name=a\u007fb",
                "1;name=\"a\u0001b\"", "1;name=\"a\\\u0000b\"", "1;\u00e9=value", "1;name=\u00e9")) {
            ByteTokenizer tokenizer = new ByteTokenizer();
            add(tokenizer, CHUNKED_PREFIX + line + "\r\nx\r\n0\r\n\r\n");
            assertThrows(MalformedRequestException.class, new RequestParser(tokenizer)::parse,
                    "chunk line: " + line.replace('\r', '?').replace('\n', '?'));
        }
    }

    @Test
    void validChunkExtensionsAndPipelineSurviveEveryReadSplitAndCompaction() {
        String first = "GET /first HTTP/1.1\r\nHost: a\r\n\r\n";
        String request = CHUNKED_PREFIX + "01 \t; flag; name \t= token; quoted=\"a; b, c\\\"\\\\\t\u00e9\"\r\nx\r\n"
                + "0;last=\"\"\r\nX-Trailer: ok\r\n\r\n";
        String following = "GET /following HTTP/1.1\r\nHost: a\r\n\r\n";
        for (int split = 0; split <= request.length(); split++) {
            ByteTokenizer tokenizer = new ByteTokenizer();
            RequestParser parser = new RequestParser(tokenizer);
            add(tokenizer, first + request.substring(0, split));
            assertTrue(parser.parse());
            tokenizer.compact();
            parser.reset();
            boolean complete = parser.parse();
            add(tokenizer, request.substring(split) + following);
            if (!complete)
                assertTrue(parser.parse(), "read split=" + split);
            assertArrayEquals(new byte[]{'x'}, parser.request().body());
            tokenizer.compact();
            parser.reset();
            assertTrue(parser.parse());
            assertEquals("/following", parser.request().uri());
        }
    }

    @Test
    void methodTokenIsBoundedBeforeItsDelimiterArrives() {
        ByteTokenizer tokenizer = new ByteTokenizer();
        RequestParser parser = new RequestParser(tokenizer);
        for (int i = 0; i < 64; i++) {
            add(tokenizer, "A");
            assertFalse(parser.parse());
        }
        add(tokenizer, "A GET /following-secret HTTP/1.1\r\nHost: secret\r\n\r\n");
        assertThrows(MalformedRequestException.class, parser::parse);
        ByteTokenizer.CapturedPrefix capture = tokenizer.capturePrefixAndRelease(parser.failureBoundaryExclusive(), 1024);
        assertEquals("A".repeat(65), new String(capture.bytes(), StandardCharsets.US_ASCII));

        ByteTokenizer atLimit = new ByteTokenizer();
        add(atLimit, "A".repeat(64) + " / HTTP/1.1\r\nHost: a\r\n\r\n");
        RequestParser accepted = new RequestParser(atLimit);
        assertTrue(accepted.parse());
        assertEquals(64, accepted.request().method().length());
    }

    @Test
    void methodLimitDoesNotCaptureBytesFromAFollowingPipelinedRequest() {
        ByteTokenizer tokenizer = new ByteTokenizer();
        String malformedLine = "A".repeat(60) + "\r\n";
        add(tokenizer, malformedLine + "SECRET /following HTTP/1.1\r\nHost: secret\r\n\r\n");
        RequestParser parser = new RequestParser(tokenizer);
        assertThrows(MalformedRequestException.class, parser::parse);
        ByteTokenizer.CapturedPrefix capture = tokenizer.capturePrefixAndRelease(parser.failureBoundaryExclusive(), 1024);
        assertEquals(malformedLine, new String(capture.bytes(), StandardCharsets.US_ASCII));
    }

    @Test
    void chunkLineLimitIncludesExtensionsAndIsIndependentOfReadFragmentation() {
        String atLimit = "1;name=" + "a".repeat(8192 - 7);
        for (int fragmentSize : new int[]{1, 127, 8192}) {
            ByteTokenizer tokenizer = new ByteTokenizer();
            RequestParser parser = new RequestParser(tokenizer);
            add(tokenizer, CHUNKED_PREFIX);
            assertFalse(parser.parse());
            for (int offset = 0; offset < atLimit.length(); offset += fragmentSize) {
                add(tokenizer, atLimit.substring(offset, Math.min(atLimit.length(), offset + fragmentSize)));
                assertFalse(parser.parse());
            }
            add(tokenizer, "\r");
            assertFalse(parser.parse(), "A pending CR at the limit can still complete CRLF.");
            add(tokenizer, "\nx\r\n0\r\n\r\n");
            assertTrue(parser.parse());
            assertArrayEquals(new byte[]{'x'}, parser.request().body());
        }
        ByteTokenizer tokenizer = new ByteTokenizer();
        RequestParser parser = new RequestParser(tokenizer);
        add(tokenizer, CHUNKED_PREFIX + atLimit);
        assertFalse(parser.parse());
        add(tokenizer, "a\r\nx\r\n0\r\n\r\nGET /following-secret HTTP/1.1\r\nHost: secret\r\n\r\n");
        assertThrows(MalformedRequestException.class, parser::parse);
        ByteTokenizer.CapturedPrefix capture = tokenizer.capturePrefixAndRelease(parser.failureBoundaryExclusive(), Integer.MAX_VALUE);
        assertEquals(CHUNKED_PREFIX + atLimit + "a", new String(capture.bytes(), StandardCharsets.US_ASCII));
    }

    @Test
    void tokenizerFindsSplitOverlappingAndChangedDelimitersAfterGrowthAndReset() {
        ByteTokenizer tokenizer = new ByteTokenizer();
        byte[] delimiter = "aba".getBytes(StandardCharsets.US_ASCII);
        for (String fragment : List.of("xx", "a", "b")) {
            add(tokenizer, fragment);
            assertEquals(-1, tokenizer.indexOf(delimiter));
            assertEquals(-1, tokenizer.indexOf(new byte[]{' '}));
        }
        add(tokenizer, "aba ");
        assertEquals(2, tokenizer.indexOf(delimiter));
        assertEquals(2, tokenizer.indexOf(delimiter));
        assertEquals(7, tokenizer.indexOf(new byte[]{' '}));
        delimiter[0] = 'b';
        assertEquals(-1, tokenizer.indexOf(delimiter));
        tokenizer.advanceTo(5);
        tokenizer.compact();
        assertEquals(-1, tokenizer.indexOf(delimiter));
        add(tokenizer, "bba");
        assertEquals(tokenizer.rawPosition() + 3, tokenizer.indexOf(delimiter));
        tokenizer.capturePrefixAndRelease(tokenizer.rawPosition() + tokenizer.remaining(), 1024);
        add(tokenizer, "bba");
        assertEquals(0, tokenizer.indexOf(delimiter));
        assertEquals(0, tokenizer.indexOf(new byte[0]));
    }

    private static void add(ByteTokenizer tokenizer, String text) {
        tokenizer.add(ByteBuffer.wrap(text.getBytes(StandardCharsets.ISO_8859_1)));
    }
}
