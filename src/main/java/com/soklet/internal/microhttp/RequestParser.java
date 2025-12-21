package com.soklet.internal.microhttp;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Function;

class RequestParser {

    private static final byte[] CRLF = "\r\n".getBytes();
    private static final byte[] SPACE = " ".getBytes();

    private static final String HEADER_CONTENT_LENGTH = "Content-Length";
    private static final String HEADER_TRANSFER_ENCODING = "Transfer-Encoding";
    private static final String CHUNKED = "chunked";
    private static final byte[] EMPTY_BODY = new byte[]{};

    private static final int RADIX_HEX = 16;

    enum State {
        METHOD(p -> p.tokenizer.next(SPACE), RequestParser::parseMethod),
        URI(p -> p.tokenizer.next(SPACE), RequestParser::parseUri),
        VERSION(p -> p.tokenizer.next(CRLF), RequestParser::parseVersion),
        HEADER(p -> p.tokenizer.next(CRLF), RequestParser::parseHeader),
        BODY(p -> p.tokenizer.next(p.contentLength), RequestParser::parseBody),
        CHUNK_SIZE(p -> p.tokenizer.next(CRLF), RequestParser::parseChunkSize),
        CHUNK_DATA(p -> p.tokenizer.next(p.chunkSize), RequestParser::parseChunkData),
        CHUNK_DATA_END(p -> p.tokenizer.next(CRLF), (rp, token) -> rp.parseChunkDateEnd()),
        CHUNK_TRAILER(p -> p.tokenizer.next(CRLF), RequestParser::parseChunkTrailer),
        DONE(null, null);

        final Function<RequestParser, byte[]> tokenSupplier;
        final BiConsumer<RequestParser, byte[]> tokenConsumer;

        State(Function<RequestParser, byte[]> tokenSupplier, BiConsumer<RequestParser, byte[]> tokenConsumer) {
            this.tokenSupplier = tokenSupplier;
            this.tokenConsumer = tokenConsumer;
        }
    }

    private final ByteTokenizer tokenizer;

    private State state = State.METHOD;
    private int contentLength;
    private int chunkSize;
    private ByteMerger chunks = new ByteMerger();

    private String method;
    private String uri;
    private String version;
    private List<Header> headers = new ArrayList<>();
    private byte[] body;

    RequestParser(ByteTokenizer tokenizer) {
        this.tokenizer = tokenizer;
    }

    boolean parse() {
        while (state != State.DONE) {
            byte[] token = state.tokenSupplier.apply(this);
            if (token == null) {
                return false;
            }
            state.tokenConsumer.accept(this, token);
        }
        return true;
    }

    MicrohttpRequest request() {
        return new MicrohttpRequest(method, uri, version, headers, body, false);
    }

    private void parseMethod(byte[] token) {
        requireAscii(token, "method");
        method = new String(token, StandardCharsets.US_ASCII);
        state = State.URI;
    }

    private void parseUri(byte[] token) {
        requireAscii(token, "uri");
        uri = new String(token, StandardCharsets.US_ASCII);
        state = State.VERSION;
    }

    private void parseVersion(byte[] token) {
        requireAscii(token, "version");
        version = new String(token, StandardCharsets.US_ASCII);
        state = State.HEADER;
    }

    private void parseHeader(byte[] token) {
        if (token.length == 0) { // CR-LF on own line, end of headers
            Integer contentLength = findContentLength();
            boolean hasTransferEncodingHeader = hasTransferEncodingHeader();
            List<String> transferEncodings = findTransferEncodings();

            if (hasTransferEncodingHeader && transferEncodings.isEmpty()) {
                throw new MalformedRequestException("invalid transfer-encoding header value");
            }

            if (contentLength != null && hasTransferEncodingHeader) {
                throw new MalformedRequestException("multiple message lengths");
            }

            if (contentLength == null) {
                if (hasTransferEncodingHeader) {
                    if (!hasOnlyChunkedEncoding(transferEncodings)) {
                        throw new MalformedRequestException("unsupported transfer-encoding");
                    }
                    state = State.CHUNK_SIZE;
                } else {
                    body = EMPTY_BODY;
                    state = State.DONE;
                }
            } else {
                this.contentLength = contentLength;
                state = State.BODY;
            }
        } else {
            headers.add(parseHeaderLine(token));
        }
    }

    private static Header parseHeaderLine(byte[] line) {
        int colonIndex = indexOfColon(line);
        if (colonIndex <= 0) {
            throw new MalformedRequestException("malformed header line");
        }
        for (int i = 0; i < colonIndex; i++) {
            int b = line[i] & 0xFF;
            if (b > 0x7F) {
                throw new MalformedRequestException("non-ascii header name");
            }
        }
        int spaceIndex = colonIndex + 1;
        while (spaceIndex < line.length && line[spaceIndex] == ' ') { // advance beyond variable-length space prefix
            spaceIndex++;
        }
        return new Header(
                new String(line, 0, colonIndex, StandardCharsets.US_ASCII),
                new String(line, spaceIndex, line.length - spaceIndex, StandardCharsets.ISO_8859_1));
    }

    private static int indexOfColon(byte[] line) {
        for (int i = 0; i < line.length; i++) {
            if (line[i] == ':') {
                return i;
            }
        }
        return -1;
    }

    private static void requireAscii(byte[] token, String field) {
        for (byte b : token) {
            if ((b & 0x80) != 0) {
                throw new MalformedRequestException("non-ascii " + field);
            }
        }
    }

    private void parseChunkSize(byte[] token) {
        int end = token.length;
        for (int i = 0; i < token.length; i++) {
            if (token[i] == ';') {
                end = i;
                break;
            }
        }
        String sizeToken = new String(token, 0, end).trim();
        if (sizeToken.isEmpty()) {
            throw new MalformedRequestException("invalid chunk size");
        }
        try {
            chunkSize = Integer.parseInt(sizeToken, RADIX_HEX);
        } catch (NumberFormatException e) {
            throw new MalformedRequestException("invalid chunk size");
        }
        if (chunkSize < 0) {
            throw new MalformedRequestException("invalid chunk size");
        }
        state = chunkSize == 0
                ? State.CHUNK_TRAILER
                : State.CHUNK_DATA;
    }

    private void parseChunkData(byte[] token) {
        chunks.add(token);
        state = State.CHUNK_DATA_END;
    }

    private void parseChunkDateEnd() {
        state = State.CHUNK_SIZE;
    }

    private void parseChunkTrailer(byte[] token) {
        if (token.length == 0) { // blank line indicates end of trailers
            body = chunks.merge();
            state = State.DONE;
        } else {
            state = State.CHUNK_TRAILER;
        }
    }

    private void parseBody(byte[] token) {
        body = token;
        state = State.DONE;
    }

    private Integer findContentLength() {
        try {
            Integer contentLength = null;
            for (Header header : headers) {
                if (header.name().equalsIgnoreCase(HEADER_CONTENT_LENGTH)) {
                    if (contentLength != null) {
                        throw new MalformedRequestException("multiple content-length headers");
                    }
                    String value = header.value() == null ? "" : header.value().trim();
                    int parsed = Integer.parseInt(value);
                    if (parsed < 0) {
                        throw new MalformedRequestException("invalid content-length header value");
                    }
                    contentLength = parsed;
                }
            }
            return contentLength;
        } catch (NumberFormatException e) {
            throw new MalformedRequestException("invalid content-length header value");
        }
    }

    private boolean hasTransferEncodingHeader() {
        for (Header header : headers) {
            if (header.name().equalsIgnoreCase(HEADER_TRANSFER_ENCODING)) {
                return true;
            }
        }
        return false;
    }

    private List<String> findTransferEncodings() {
        List<String> transferEncodings = new ArrayList<>();
        for (Header header : headers) {
            if (!header.name().equalsIgnoreCase(HEADER_TRANSFER_ENCODING)) {
                continue;
            }
            String value = header.value();
            if (value == null) {
                continue;
            }
            for (String part : value.split(",")) {
                String normalized = normalizeTransferEncoding(part);
                if (normalized != null) {
                    transferEncodings.add(normalized);
                }
            }
        }
        return transferEncodings;
    }

    private String normalizeTransferEncoding(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        int semicolon = trimmed.indexOf(';');
        String token = semicolon == -1 ? trimmed : trimmed.substring(0, semicolon);
        token = token.trim();
        if (token.isEmpty()) {
            return null;
        }
        return token.toLowerCase(Locale.ROOT);
    }

    private boolean hasOnlyChunkedEncoding(List<String> transferEncodings) {
        return transferEncodings.size() == 1 && CHUNKED.equals(transferEncodings.get(0));
    }

}
