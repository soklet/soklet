/*
 * Copyright 2022-2026 Revetware LLC.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package com.soklet.internal.microhttp;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MicrohttpRequestParsedStateTests {
    @Test
    void partialDiagnosticSnapshotRetainsAbsentComponents() {
        MicrohttpRequest partial = new MicrohttpRequest(null, null, null, null,
                null, false, null);
        assertNull(partial.method());
        assertNull(partial.uri());
        assertNull(partial.version());
        assertNull(partial.headers());
        assertNull(partial.body());
        assertThrows(IllegalStateException.class, partial::parsedMethod);
        assertThrows(IllegalStateException.class, partial::parsedUri);
        assertThrows(IllegalStateException.class, partial::parsedVersion);
        assertThrows(IllegalStateException.class, partial::parsedHeaders);
        assertThrows(IllegalStateException.class, partial::parsedBody);
    }

    @Test
    void parsedComponentsRemainAvailableWhileBodyIsIncomplete() {
        ByteTokenizer tokenizer = new ByteTokenizer();
        tokenizer.add(ascii("POST /upload HTTP/1.1\r\nHost: localhost\r\nContent-Length: 4\r\n\r\na"));
        RequestParser parser = new RequestParser(tokenizer);
        assertFalse(parser.parse());
        MicrohttpRequest partial = parser.request();
        assertEquals("POST", partial.parsedMethod());
        assertEquals("/upload", partial.parsedUri());
        assertEquals("HTTP/1.1", partial.parsedVersion());
        assertEquals(List.of(new Header("Host", "localhost"), new Header("Content-Length", "4")),
                partial.parsedHeaders());
        assertNull(partial.body());
        assertThrows(IllegalStateException.class, partial::parsedBody);
    }

    @Test
    void completeRequestReturnsTheOwnedParserComponents() {
        ByteTokenizer tokenizer = new ByteTokenizer();
        tokenizer.add(ascii("POST /upload HTTP/1.1\r\nHost: localhost\r\nContent-Length: 4\r\n\r\ndata"));
        RequestParser parser = new RequestParser(tokenizer);
        assertTrue(parser.parse());
        MicrohttpRequest complete = parser.request();
        assertSame(complete.method(), complete.parsedMethod());
        assertSame(complete.uri(), complete.parsedUri());
        assertSame(complete.version(), complete.parsedVersion());
        assertSame(complete.headers(), complete.parsedHeaders());
        assertSame(complete.body(), complete.parsedBody());
        assertArrayEquals("data".getBytes(StandardCharsets.US_ASCII), complete.parsedBody());
        assertEquals("localhost", complete.header("HOST"));
        assertTrue(complete.hasHeader("Host", "LOCALHOST"));
    }

    @Test
    void completeBodylessRequestExposesAnEmptyBody() {
        ByteTokenizer tokenizer = new ByteTokenizer();
        tokenizer.add(ascii("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n"));
        RequestParser parser = new RequestParser(tokenizer);
        assertTrue(parser.parse());
        assertArrayEquals(new byte[0], parser.request().parsedBody());
    }

    private static ByteBuffer ascii(String value) {
        return ByteBuffer.wrap(value.getBytes(StandardCharsets.US_ASCII));
    }
}
