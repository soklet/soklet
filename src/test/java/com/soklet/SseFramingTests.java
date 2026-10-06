package com.soklet;

import com.soklet.exception.IllegalRequestException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SseFramingTests {
    @Test
    void bareCarriageReturnsCannotCreateRequestLinesOrHeaders() {
        DefaultSseServer server = (DefaultSseServer) SseServer.withPort(0).build();
        for (String raw : List.of("GET /events HTTP/1.1\rHost: a\r\n\r\n",
                "GET /events HTTP/1.1\r\nHost: a\r\nX-Note: a\rX-Authenticated-User: admin\r\n\r\n",
                "GET /events HTTP/1.1\r\nHost: a\r\nX-Note: a\r\r\n\r\n",
                "GET /events HTTP/1.1\r\nHost: a\r", "\rGET /events HTTP/1.1\r\nHost: a\r\n\r\n")) {
            assertThrows(IllegalRequestException.class, () -> server.parseRequest(raw, null));
        }
    }

    @Test
    void crlfAndExistingLfOnlyRequestsKeepTheSameHeaderFields() throws Exception {
        DefaultSseServer server = (DefaultSseServer) SseServer.withPort(0).build();
        String raw = "GET /events HTTP/1.1\r\nHost: a\r\nX-Note: a\r\nX-Empty:\r\n\r\n";
        assertEquals(server.parseRequest(raw, null).getHeaders(),
                server.parseRequest(raw.replace("\r", ""), null).getHeaders());
    }

    @Test
    void whitespaceOnlyHeaderLinesCannotHideLaterFramingFields() {
        DefaultSseServer server = (DefaultSseServer) SseServer.withPort(0).build();
        for (String line : List.of(" ", "\t", " \t "))
            assertThrows(IllegalRequestException.class, () -> server.parseRequest(
                    "GET /events HTTP/1.1\r\nHost: a\r\n" + line + "\r\nContent-Length: 1\r\n\r\nx", null));
    }

    @Test
    void sseBodyFramingRequiresUnsignedDigitsAndRejectsRepeatedOrCompetingFields() throws Exception {
        DefaultSseServer server = (DefaultSseServer) SseServer.withPort(0).build();
        for (String fields : List.of("Content-Length: +0\r\n", "Content-Length: -0\r\n", "Content-Length:\r\n",
                "Content-Length: 0,0\r\n", "Content-Length: 0\r\nContent-Length: 0\r\n",
                "Content-Length: 0\r\nTransfer-Encoding: chunked\r\n", "Transfer-Encoding:\r\n")) {
            Request request = server.parseRequest("GET /events HTTP/1.1\r\nHost: a\r\n" + fields + "\r\n", null);
            assertThrows(IllegalRequestException.class, () -> server.validateNoRequestBodyHeaders(request), fields);
        }
        for (String value : List.of("0", "000", " \t0\t ")) {
            Request request = server.parseRequest("GET /events HTTP/1.1\r\nHost: a\r\nContent-Length: " + value + "\r\n\r\n", null);
            assertDoesNotThrow(() -> server.validateNoRequestBodyHeaders(request));
        }
    }

    @Test
    void rejectedAndOrdinaryResponsesOwnTheirFramingAndPreserveApplicationFields() throws Exception {
        DefaultSseServer server = (DefaultSseServer) SseServer.withPort(0).build();
        MarshaledResponse response = MarshaledResponse.withStatusCode(403).body("denied".getBytes(StandardCharsets.UTF_8))
                .headers(Map.of("cOnNeCtIoN", List.of("keep-alive, X-Hop"), "Keep-Alive", List.of("timeout=60"),
                        "Content-Length", List.of("999", "999"), "Transfer-Encoding", List.of("chunked"),
                        "TE", List.of("trailers"), "Trailer", List.of("X-End"), "Upgrade", List.of("websocket"),
                        "Proxy-Connection", List.of("keep-alive"), "X-Hop", List.of("remove"),
                        "WWW-Authenticate", List.of("Bearer realm=\"first\"", "Bearer realm=\"second\"")))
                .cookies(List.of(ResponseCookie.with("session", "rejected").build())).build();
        for (boolean rejected : List.of(false, true)) {
            HttpRequestResult result = HttpRequestResult.withMarshaledResponse(response)
                    .sseHandshakeResult(rejected ? SseHandshakeResult.rejectWithResponse(Response.withStatusCode(403).build()) : null).build();
            String wire = serialize(server, result);
            Map<String, List<String>> headers = headers(wire);
            assertEquals(List.of("close"), headers.get("Connection"), wire);
            assertEquals(List.of("6"), headers.get("Content-Length"), wire);
            for (String name : List.of("Keep-Alive", "Transfer-Encoding", "TE", "Trailer", "Upgrade", "Proxy-Connection", "X-Hop"))
                assertFalse(headers.containsKey(name), name + ": " + wire);
            assertEquals(List.of("Bearer realm=\"first\"", "Bearer realm=\"second\""), headers.get("WWW-Authenticate"));
            assertEquals(1, headers.get("Set-Cookie").size());
            assertEquals(1, headers.get("Date").size());
            assertEquals("denied", wire.substring(wire.indexOf("\r\n\r\n") + 4));
        }
    }

    @Test
    void emptyAndBodylessResponsesHaveCorrectTransportLengths() throws Exception {
        DefaultSseServer server = (DefaultSseServer) SseServer.withPort(0).build();
        for (int status : List.of(403, 204, 205, 304)) {
            MarshaledResponse response = MarshaledResponse.withStatusCode(status)
                    .body(status == 403 ? null : "discard".getBytes(StandardCharsets.UTF_8))
                    .headers(Map.of("Content-Length", List.of("999"), "Transfer-Encoding", List.of("chunked"))).build();
            String wire = serialize(server, HttpRequestResult.withMarshaledResponse(response).build());
            assertEquals("", wire.substring(wire.indexOf("\r\n\r\n") + 4));
            assertFalse(headers(wire).containsKey("Transfer-Encoding"));
            assertEquals(status == 204 || status == 304 ? null : List.of("0"), headers(wire).get("Content-Length"));
        }
    }

    private static String serialize(DefaultSseServer server, HttpRequestResult result) throws Exception {
        Method serializer = DefaultSseServer.class.getDeclaredMethod("createHandshakeHttpResponse", HttpRequestResult.class);
        serializer.setAccessible(true);
        return new String((byte[]) serializer.invoke(server, result), StandardCharsets.ISO_8859_1);
    }

    private static Map<String, List<String>> headers(String wire) {
        return Utilities.extractHeadersFromRawHeaderLines(Arrays.asList(wire.substring(0, wire.indexOf("\r\n\r\n")).split("\r\n")));
    }
}
