package com.soklet.internal.microhttp;

import com.soklet.internal.util.HostHeaderValidator;

import java.net.InetSocketAddress;
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
    private static final String HEADER_HOST = "Host";
    private static final String HEADER_EXPECT = "Expect";
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
    private final InetSocketAddress remoteAddress;
    private final int maxRequestSize;

    private State state = State.METHOD;
    private int contentLength;
    private boolean contentLengthHeaderPresent;
    private long contentLengthHeader;
    private boolean transferEncodingHeaderPresent;
    private List<String> transferEncodings;
    private int hostHeaderCount;
    private String hostHeaderValue;
    private boolean expectHeaderPresent;
    private int chunkSize;
    private long chunkBodySize;
    private ByteMerger chunks;

    private String method;
    private String uri;
    private String version;
    private List<Header> headers;
    private byte[] body;

    RequestParser(ByteTokenizer tokenizer) {
        this(tokenizer, null, Integer.MAX_VALUE);
    }

    RequestParser(ByteTokenizer tokenizer, InetSocketAddress remoteAddress) {
        this(tokenizer, remoteAddress, Integer.MAX_VALUE);
    }

    RequestParser(ByteTokenizer tokenizer, InetSocketAddress remoteAddress, int maxRequestSize) {
        if (maxRequestSize < 1) {
            throw new IllegalArgumentException("Maximum request size must be > 0");
        }
        this.tokenizer = tokenizer;
        this.remoteAddress = remoteAddress;
        this.maxRequestSize = maxRequestSize;
        reset();
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
        return new MicrohttpRequest(method, uri, version, headers, body, false, remoteAddress);
    }

    void reset() {
        state = State.METHOD;
        contentLength = 0;
        contentLengthHeaderPresent = false;
        contentLengthHeader = 0L;
        transferEncodingHeaderPresent = false;
        transferEncodings = null;
        hostHeaderCount = 0;
        hostHeaderValue = null;
        expectHeaderPresent = false;
        chunkSize = 0;
        chunkBodySize = 0L;
        chunks = null;
        method = null;
        uri = null;
        version = null;
        headers = new ArrayList<>();
        body = null;
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
        if (!version.equalsIgnoreCase("HTTP/1.0") && !version.equalsIgnoreCase("HTTP/1.1")) {
            throw new MalformedRequestException("unsupported http version");
        }
        state = State.HEADER;
    }

    private void parseHeader(byte[] token) {
        if (token.length == 0) { // CR-LF on own line, end of headers
            validateHostHeaderIfRequired();
            rejectExpectHeaderIfPresent();

            if (transferEncodingHeaderPresent && (transferEncodings == null || transferEncodings.isEmpty())) {
                throw new MalformedRequestException("invalid transfer-encoding header value");
            }

            if (contentLengthHeaderPresent && transferEncodingHeaderPresent) {
                throw new MalformedRequestException("multiple message lengths");
            }

            if (!contentLengthHeaderPresent) {
                if (transferEncodingHeaderPresent) {
                    if (!hasOnlyChunkedEncoding(transferEncodings)) {
                        throw new MalformedRequestException("unsupported transfer-encoding");
                    }
                    state = State.CHUNK_SIZE;
                } else {
                    body = EMPTY_BODY;
                    state = State.DONE;
                }
            } else {
                if (contentLengthHeader > maxRequestSize) {
                    throw new RequestTooLargeException();
                }
                this.contentLength = Math.toIntExact(contentLengthHeader);
                state = State.BODY;
            }
        } else {
            Header header = parseHeaderLine(token);
            headers.add(header);
            observeHeader(header);
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
            if (!isTchar(b)) {
                throw new MalformedRequestException("invalid header name");
            }
        }
        int spaceIndex = colonIndex + 1;
        while (spaceIndex < line.length && (line[spaceIndex] == ' ' || line[spaceIndex] == '\t')) { // advance beyond variable-length space prefix
            spaceIndex++;
        }
        for (int i = spaceIndex; i < line.length; i++) {
            int b = line[i] & 0xFF;
            if ((b < 0x20 && b != '\t') || b == 0x7F) {
                throw new MalformedRequestException("invalid header value");
            }
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

    private void validateHostHeaderIfRequired() {
        if (version == null || !version.equalsIgnoreCase("HTTP/1.1")) {
            return;
        }

        if (hostHeaderCount == 0) {
            throw new MalformedRequestException("missing host header");
        }

        if (hostHeaderCount > 1) {
            throw new MalformedRequestException("multiple host headers");
        }

        if (!HostHeaderValidator.isValidHostHeaderValue(hostHeaderValue)) {
            throw new MalformedRequestException("invalid host header value");
        }
    }

    private void rejectExpectHeaderIfPresent() {
        if (expectHeaderPresent) {
            throw new MalformedRequestException("unsupported expect header");
        }
    }

    private static boolean isTchar(int b) {
        char c = (char) b;
        return c == '!' || c == '#' || c == '$' || c == '%' || c == '&' || c == '\'' || c == '*' || c == '+' ||
                c == '-' || c == '.' || c == '^' || c == '_' || c == '`' || c == '|' || c == '~' ||
                (c >= '0' && c <= '9') ||
                (c >= 'A' && c <= 'Z') ||
                (c >= 'a' && c <= 'z');
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
            long parsedChunkSize = Long.parseLong(sizeToken, RADIX_HEX);
            if (parsedChunkSize > maxRequestSize) {
                throw new RequestTooLargeException();
            }
            chunkSize = Math.toIntExact(parsedChunkSize);
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
        long newChunkBodySize = chunkBodySize + token.length;
        if (newChunkBodySize > maxRequestSize) {
            throw new RequestTooLargeException();
        }
        chunkBodySize = newChunkBodySize;
        if (chunks == null) {
            chunks = new ByteMerger();
        }
        chunks.add(token);
        state = State.CHUNK_DATA_END;
    }

    private void parseChunkDateEnd() {
        state = State.CHUNK_SIZE;
    }

    private void parseChunkTrailer(byte[] token) {
        if (token.length == 0) { // blank line indicates end of trailers
            body = chunks == null ? EMPTY_BODY : chunks.merge(maxRequestSize);
            state = State.DONE;
        } else {
            state = State.CHUNK_TRAILER;
        }
    }

    private void parseBody(byte[] token) {
        body = token;
        state = State.DONE;
    }

    private void observeHeader(Header header) {
        if (header.name().equalsIgnoreCase(HEADER_CONTENT_LENGTH)) {
            observeContentLengthHeader(header);
        } else if (header.name().equalsIgnoreCase(HEADER_TRANSFER_ENCODING)) {
            observeTransferEncodingHeader(header);
        } else if (header.name().equalsIgnoreCase(HEADER_HOST)) {
            hostHeaderCount++;
            if (hostHeaderCount == 1) {
                hostHeaderValue = header.value();
            }
        } else if (header.name().equalsIgnoreCase(HEADER_EXPECT)) {
            expectHeaderPresent = true;
        }
    }

    private void observeContentLengthHeader(Header header) {
        try {
            if (contentLengthHeaderPresent) {
                throw new MalformedRequestException("multiple content-length headers");
            }

            String value = header.value() == null ? "" : header.value().trim();
            long parsed = Long.parseLong(value);
            if (parsed < 0) {
                throw new MalformedRequestException("invalid content-length header value");
            }
            contentLengthHeader = parsed;
            contentLengthHeaderPresent = true;
        } catch (NumberFormatException e) {
            throw new MalformedRequestException("invalid content-length header value");
        }
    }

    private void observeTransferEncodingHeader(Header header) {
        transferEncodingHeaderPresent = true;

        String value = header.value();
        if (value == null) {
            return;
        }

        for (String part : value.split(",")) {
            String normalized = normalizeTransferEncoding(part);
            if (normalized != null) {
                if (transferEncodings == null) {
                    transferEncodings = new ArrayList<>(2);
                }
                transferEncodings.add(normalized);
            }
        }
    }

    private static String normalizeTransferEncoding(String value) {
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
        return transferEncodings != null && transferEncodings.size() == 1 && CHUNKED.equals(transferEncodings.get(0));
    }

}
