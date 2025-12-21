package com.soklet.internal.microhttp;

import java.nio.charset.StandardCharsets;
import java.util.List;

public record MicrohttpResponse(
        int status,
        String reason,
        List<Header> headers,
        byte[] body) {

    public boolean hasHeader(String name) {
        for (Header header : headers) {
            if (header.name().equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    static final byte[] COLON_SPACE = ": ".getBytes(StandardCharsets.US_ASCII);
    static final byte[] SPACE = " ".getBytes(StandardCharsets.US_ASCII);
    static final byte[] CRLF = "\r\n".getBytes(StandardCharsets.US_ASCII);

    byte[] serialize(String version, List<Header> headers) {
        ByteMerger merger = new ByteMerger();
        merger.add(version.getBytes(StandardCharsets.US_ASCII));
        merger.add(SPACE);
        merger.add(Integer.toString(status).getBytes(StandardCharsets.US_ASCII));
        merger.add(SPACE);
        merger.add(reason.getBytes(StandardCharsets.ISO_8859_1));
        merger.add(CRLF);
        appendHeaders(merger, headers);
        appendHeaders(merger, this.headers);
        merger.add(CRLF);
        merger.add(body);
        return merger.merge();
    }

    private static void appendHeaders(ByteMerger merger, List<Header> headers) {
        for (Header header : headers) {
            merger.add(header.name().getBytes(StandardCharsets.US_ASCII));
            merger.add(COLON_SPACE);
            merger.add(header.value().getBytes(StandardCharsets.ISO_8859_1));
            merger.add(CRLF);
        }
    }

}
