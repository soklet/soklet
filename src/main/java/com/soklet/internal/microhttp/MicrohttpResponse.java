package com.soklet.internal.microhttp;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static java.nio.file.StandardOpenOption.READ;
import static java.util.Objects.requireNonNull;

public final class MicrohttpResponse {
    private final int status;
    private final String reason;
    private final List<Header> headers;
    private final long bodyLength;
    private final BodySourceFactory bodySourceFactory;
    private final byte[] body;

    public MicrohttpResponse(int status, String reason, List<Header> headers, byte[] body) {
        this(status, reason, headers, requireNonNull(body).length, () -> new ByteBufferWritableSource(ByteBuffer.wrap(body)), body);
    }

    private MicrohttpResponse(int status, String reason, List<Header> headers, long bodyLength,
                              BodySourceFactory bodySourceFactory, byte[] body) {
        this.status = status;
        this.reason = requireNonNull(reason);
        this.headers = requireNonNull(headers);
        this.bodyLength = bodyLength;
        this.bodySourceFactory = requireNonNull(bodySourceFactory);
        this.body = body;
    }

    public static MicrohttpResponse withFileBody(int status, String reason, List<Header> headers,
                                                 Path path, Long offset, Long count) {
        requireNonNull(path);
        requireNonNull(offset);
        requireNonNull(count);

        return new MicrohttpResponse(status, reason, headers, count,
                () -> new FileChannelWritableSource(FileChannel.open(path, READ), offset, count, true),
                null);
    }

    public static MicrohttpResponse withFileChannelBody(int status, String reason, List<Header> headers,
                                                        FileChannel fileChannel, Long offset, Long count,
                                                        Boolean closeOnComplete) {
        requireNonNull(fileChannel);
        requireNonNull(offset);
        requireNonNull(count);
        requireNonNull(closeOnComplete);

        return new MicrohttpResponse(status, reason, headers, count,
                () -> new FileChannelWritableSource(fileChannel, offset, count, closeOnComplete),
                null);
    }

    public static MicrohttpResponse withByteBufferBody(int status, String reason, List<Header> headers,
                                                       ByteBuffer byteBuffer) {
        requireNonNull(byteBuffer);
        ByteBuffer responseBuffer = byteBuffer.asReadOnlyBuffer();

        return new MicrohttpResponse(status, reason, headers, responseBuffer.remaining(),
                () -> new ByteBufferWritableSource(responseBuffer),
                null);
    }

    public int status() {
        return status;
    }

    public String reason() {
        return reason;
    }

    public List<Header> headers() {
        return headers;
    }

    public long bodyLength() {
        return bodyLength;
    }

    public byte[] body() {
        if (body == null) {
            throw new IllegalStateException("Response body is not byte-array-backed.");
        }
        return body;
    }

    public MicrohttpResponse withHeaders(List<Header> headers) {
        return new MicrohttpResponse(status, reason, headers, bodyLength, bodySourceFactory, body);
    }

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
        appendHead(version, headers, merger);
        merger.add(body());
        return merger.merge();
    }

    byte[] serializeHead(String version, List<Header> headers) {
        ByteMerger merger = new ByteMerger();
        appendHead(version, headers, merger);
        return merger.merge();
    }

    WritableSource writableSource(byte[] serializedHead) throws IOException {
        return new CompositeWritableSource(List.of(
                new ByteBufferWritableSource(ByteBuffer.wrap(serializedHead)),
                bodySourceFactory.create()));
    }

    private void appendHead(String version, List<Header> headers, ByteMerger merger) {
        merger.add(version.getBytes(StandardCharsets.US_ASCII));
        merger.add(SPACE);
        merger.add(Integer.toString(status).getBytes(StandardCharsets.US_ASCII));
        merger.add(SPACE);
        merger.add(reason.getBytes(StandardCharsets.ISO_8859_1));
        merger.add(CRLF);
        appendHeaders(merger, headers);
        appendHeaders(merger, this.headers);
        merger.add(CRLF);
    }

    private static void appendHeaders(ByteMerger merger, List<Header> headers) {
        for (Header header : headers) {
            merger.add(header.name().getBytes(StandardCharsets.US_ASCII));
            merger.add(COLON_SPACE);
            merger.add(header.value().getBytes(StandardCharsets.ISO_8859_1));
            merger.add(CRLF);
        }
    }

    @FunctionalInterface
    private interface BodySourceFactory {
        WritableSource create() throws IOException;
    }
}
