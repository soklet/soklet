package com.soklet.internal.microhttp;

import com.soklet.internal.util.HostHeaderValidator;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

class RequestParser {

    private static final byte[] CRLF = "\r\n".getBytes();
    private static final byte[] SPACE = " ".getBytes();

    private static final String HEADER_CONTENT_LENGTH = "Content-Length";
    private static final String HEADER_TRANSFER_ENCODING = "Transfer-Encoding";
    private static final String HEADER_HOST = "Host";
    private static final String HEADER_EXPECT = "Expect";
    private static final String HEADER_ACCEPT = "Accept";
    private static final String HEADER_ACCEPT_ENCODING = "Accept-Encoding";
    private static final String HEADER_ACCEPT_LANGUAGE = "Accept-Language";
    private static final String HEADER_CACHE_CONTROL = "Cache-Control";
    private static final String HEADER_CONNECTION = "Connection";
    private static final String HEADER_CONTENT_TYPE = "Content-Type";
    private static final String HEADER_COOKIE = "Cookie";
    private static final String HEADER_ORIGIN = "Origin";
    private static final String HEADER_USER_AGENT = "User-Agent";
    private static final String CHUNKED = "chunked";
    private static final byte[] EMPTY_BODY = new byte[]{};

    private static final int RADIX_HEX = 16;

    enum State {
        METHOD,
        URI,
        VERSION,
        HEADER,
        BODY,
        CHUNK_SIZE,
        CHUNK_DATA,
        CHUNK_DATA_END,
        CHUNK_TRAILER,
        DONE
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
            if (!parseCurrentState()) {
                return false;
            }
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

    private boolean parseCurrentState() {
        switch (state) {
            case METHOD:
                return parseMethod();
            case URI:
                return parseUri();
            case VERSION:
                return parseVersion();
            case HEADER:
                return parseHeader();
            case BODY:
                return parseBody();
            case CHUNK_SIZE:
                return parseChunkSize();
            case CHUNK_DATA:
                return parseChunkData();
            case CHUNK_DATA_END:
                return parseChunkDataEnd();
            case CHUNK_TRAILER:
                return parseChunkTrailer();
            case DONE:
                return true;
            default:
                throw new IllegalStateException("Unsupported parser state: " + state);
        }
    }

    private boolean parseMethod() {
        String token = tokenizer.nextAsciiString(SPACE, "method");
        if (token == null) {
            return false;
        }
        method = token;
        state = State.URI;
        return true;
    }

    private boolean parseUri() {
        String token = tokenizer.nextAsciiString(SPACE, "uri");
        if (token == null) {
            return false;
        }
        uri = token;
        state = State.VERSION;
        return true;
    }

    private boolean parseVersion() {
        String token = tokenizer.nextAsciiString(CRLF, "version");
        if (token == null) {
            return false;
        }
        version = token;
        if (!version.equalsIgnoreCase("HTTP/1.0") && !version.equalsIgnoreCase("HTTP/1.1")) {
            throw new MalformedRequestException("unsupported http version");
        }
        state = State.HEADER;
        return true;
    }

    private boolean parseHeader() {
        int start = tokenizer.rawPosition();
        int end = tokenizer.indexOf(CRLF);
        if (end < 0) {
            return false;
        }

        if (end == start) { // CR-LF on own line, end of headers
            tokenizer.advanceTo(end + CRLF.length);
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
            Header header = parseHeaderLine(start, end);
            tokenizer.advanceTo(end + CRLF.length);
            headers.add(header);
            observeHeader(header);
        }

        return true;
    }

    private Header parseHeaderLine(int start, int end) {
        int colonIndex = indexOfColon(start, end);
        if (colonIndex <= start) {
            throw new MalformedRequestException("malformed header line");
        }
        for (int i = start; i < colonIndex; i++) {
            int b = tokenizer.rawByte(i) & 0xFF;
            if (b > 0x7F) {
                throw new MalformedRequestException("non-ascii header name");
            }
            if (!isTchar(b)) {
                throw new MalformedRequestException("invalid header name");
            }
        }
        int spaceIndex = colonIndex + 1;
        while (spaceIndex < end && (tokenizer.rawByte(spaceIndex) == ' ' || tokenizer.rawByte(spaceIndex) == '\t')) { // advance beyond variable-length space prefix
            spaceIndex++;
        }
        for (int i = spaceIndex; i < end; i++) {
            int b = tokenizer.rawByte(i) & 0xFF;
            if ((b < 0x20 && b != '\t') || b == 0x7F) {
                throw new MalformedRequestException("invalid header value");
            }
        }
        return new Header(
                parseHeaderName(start, colonIndex),
                tokenizer.string(spaceIndex, end, StandardCharsets.ISO_8859_1));
    }

    private String parseHeaderName(int start, int end) {
        switch (end - start) {
            case 4:
                if (tokenizer.asciiEquals(start, end, HEADER_HOST)) return HEADER_HOST;
                break;
            case 6:
                if (tokenizer.asciiEquals(start, end, HEADER_ACCEPT)) return HEADER_ACCEPT;
                if (tokenizer.asciiEquals(start, end, HEADER_COOKIE)) return HEADER_COOKIE;
                if (tokenizer.asciiEquals(start, end, HEADER_EXPECT)) return HEADER_EXPECT;
                if (tokenizer.asciiEquals(start, end, HEADER_ORIGIN)) return HEADER_ORIGIN;
                break;
            case 10:
                if (tokenizer.asciiEquals(start, end, HEADER_CONNECTION)) return HEADER_CONNECTION;
                if (tokenizer.asciiEquals(start, end, HEADER_USER_AGENT)) return HEADER_USER_AGENT;
                break;
            case 12:
                if (tokenizer.asciiEquals(start, end, HEADER_CONTENT_TYPE)) return HEADER_CONTENT_TYPE;
                break;
            case 13:
                if (tokenizer.asciiEquals(start, end, HEADER_CACHE_CONTROL)) return HEADER_CACHE_CONTROL;
                break;
            case 14:
                if (tokenizer.asciiEquals(start, end, HEADER_CONTENT_LENGTH)) return HEADER_CONTENT_LENGTH;
                break;
            case 15:
                if (tokenizer.asciiEquals(start, end, HEADER_ACCEPT_ENCODING)) return HEADER_ACCEPT_ENCODING;
                if (tokenizer.asciiEquals(start, end, HEADER_ACCEPT_LANGUAGE)) return HEADER_ACCEPT_LANGUAGE;
                break;
            case 17:
                if (tokenizer.asciiEquals(start, end, HEADER_TRANSFER_ENCODING)) return HEADER_TRANSFER_ENCODING;
                break;
            default:
                break;
        }

        return tokenizer.string(start, end, StandardCharsets.US_ASCII);
    }

    private int indexOfColon(int start, int end) {
        for (int i = start; i < end; i++) {
            if (tokenizer.rawByte(i) == ':') {
                return i;
            }
        }
        return -1;
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

    private boolean parseChunkSize() {
        int start = tokenizer.rawPosition();
        int end = tokenizer.indexOf(CRLF);
        if (end < 0) {
            return false;
        }

        int sizeEnd = end;
        for (int i = start; i < end; i++) {
            if (tokenizer.rawByte(i) == ';') {
                sizeEnd = i;
                break;
            }
        }
        String sizeToken = tokenizer.string(start, sizeEnd, StandardCharsets.US_ASCII).trim();
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
        tokenizer.advanceTo(end + CRLF.length);
        return true;
    }

    private boolean parseChunkData() {
        byte[] token = tokenizer.next(chunkSize);
        if (token == null) {
            return false;
        }
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
        return true;
    }

    private boolean parseChunkDataEnd() {
        int length = tokenizer.nextLength(CRLF);
        if (length < 0) {
            return false;
        }
        state = State.CHUNK_SIZE;
        return true;
    }

    private boolean parseChunkTrailer() {
        int length = tokenizer.nextLength(CRLF);
        if (length < 0) {
            return false;
        }
        if (length == 0) { // blank line indicates end of trailers
            body = chunks == null ? EMPTY_BODY : chunks.merge(maxRequestSize);
            state = State.DONE;
        } else {
            state = State.CHUNK_TRAILER;
        }
        return true;
    }

    private boolean parseBody() {
        byte[] token = tokenizer.next(contentLength);
        if (token == null) {
            return false;
        }
        body = token;
        state = State.DONE;
        return true;
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
