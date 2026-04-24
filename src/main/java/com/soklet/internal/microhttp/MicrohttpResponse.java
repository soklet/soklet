package com.soklet.internal.microhttp;

import com.soklet.StreamTerminationReason;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
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
    private final boolean streaming;

    public MicrohttpResponse(int status, String reason, List<Header> headers, byte[] body) {
        this(status, reason, headers, requireNonNull(body).length, () -> new ByteBufferWritableSource(ByteBuffer.wrap(body)), body, false);
    }

    private MicrohttpResponse(int status, String reason, List<Header> headers, long bodyLength,
                              BodySourceFactory bodySourceFactory, byte[] body, boolean streaming) {
        this.status = status;
        this.reason = requireNonNull(reason);
        this.headers = requireNonNull(headers);
        this.bodyLength = bodyLength;
        this.bodySourceFactory = requireNonNull(bodySourceFactory);
        this.body = body;
        this.streaming = streaming;
    }

    public static MicrohttpResponse withFileBody(int status, String reason, List<Header> headers,
                                                 Path path, Long offset, Long count) {
        requireNonNull(path);
        requireNonNull(offset);
        requireNonNull(count);

        return new MicrohttpResponse(status, reason, headers, count,
                () -> new FileChannelWritableSource(FileChannel.open(path, READ), offset, count, true),
                null,
                false);
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
                null,
                false);
    }

    public static MicrohttpResponse withByteBufferBody(int status, String reason, List<Header> headers,
                                                       ByteBuffer byteBuffer) {
        requireNonNull(byteBuffer);
        ByteBuffer responseBuffer = byteBuffer.asReadOnlyBuffer();

        return new MicrohttpResponse(status, reason, headers, responseBuffer.remaining(),
                () -> new ByteBufferWritableSource(responseBuffer),
                null,
                false);
    }

    static MicrohttpResponse withStreamingBody(int status, String reason, List<Header> headers,
                                               BodySourceFactory bodySourceFactory) {
        requireNonNull(bodySourceFactory);
        return new MicrohttpResponse(status, reason, headers, 0L, bodySourceFactory, null, true);
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
        return new MicrohttpResponse(status, reason, headers, bodyLength, bodySourceFactory, body, streaming);
    }

    public boolean streaming() {
        return streaming;
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
    static final byte[] CACHE_CONTROL_HEADER_NAME = "Cache-Control".getBytes(StandardCharsets.US_ASCII);
    static final byte[] CONNECTION_HEADER_NAME = "Connection".getBytes(StandardCharsets.US_ASCII);
    static final byte[] CONTENT_LENGTH_HEADER_NAME = "Content-Length".getBytes(StandardCharsets.US_ASCII);
    static final byte[] CONTENT_TYPE_HEADER_NAME = "Content-Type".getBytes(StandardCharsets.US_ASCII);
    static final byte[] DATE_HEADER_NAME = "Date".getBytes(StandardCharsets.US_ASCII);
    static final byte[] ETAG_HEADER_NAME = "ETag".getBytes(StandardCharsets.US_ASCII);
    static final byte[] LAST_MODIFIED_HEADER_NAME = "Last-Modified".getBytes(StandardCharsets.US_ASCII);
    static final byte[] LOCATION_HEADER_NAME = "Location".getBytes(StandardCharsets.US_ASCII);
    static final byte[] SERVER_HEADER_NAME = "Server".getBytes(StandardCharsets.US_ASCII);
    static final byte[] SET_COOKIE_HEADER_NAME = "Set-Cookie".getBytes(StandardCharsets.US_ASCII);
    static final byte[] VARY_HEADER_NAME = "Vary".getBytes(StandardCharsets.US_ASCII);

    byte[] serialize(String version, List<Header> headers) {
        byte[] head = serializeHead(version, headers);
        byte[] responseBody = body();
        byte[] result = Arrays.copyOf(head, head.length + responseBody.length);
        System.arraycopy(responseBody, 0, result, head.length, responseBody.length);
        return result;
    }

    byte[] serializeHead(String version, List<Header> headers) {
        HeadWriter writer = new HeadWriter(initialHeadCapacity((long) headers.size() + this.headers.size()));
        appendHead(version, headers, writer);
        return writer.toByteArray();
    }

    WritableSource writableSource(byte[] serializedHead) throws IOException {
        return new CompositeWritableSource(List.of(
                new ByteBufferWritableSource(ByteBuffer.wrap(serializedHead)),
                bodySourceFactory.create()));
    }

    void closeStreamingBody(StreamTerminationReason cancelationReason, Throwable cause) throws IOException {
        if (!streaming) {
            return;
        }

        WritableSource source = bodySourceFactory.create();
        source.close(cancelationReason, cause);
    }

    private void appendHead(String version, List<Header> headers, HeadWriter writer) {
        writer.writeAscii(version);
        writer.write(SPACE);
        writer.writeAscii(Integer.toString(status));
        writer.write(SPACE);
        writer.writeLatin1(reason);
        writer.write(CRLF);
        appendHeaders(writer, headers);
        appendHeaders(writer, this.headers);
        writer.write(CRLF);
    }

    private static void appendHeaders(HeadWriter writer, List<Header> headers) {
        for (Header header : headers) {
            writeHeaderName(writer, header.name());
            writer.write(COLON_SPACE);
            writer.writeLatin1(header.value());
            writer.write(CRLF);
        }
    }

    private static void writeHeaderName(HeadWriter writer, String name) {
        switch (name) {
            case "Cache-Control" -> writer.write(CACHE_CONTROL_HEADER_NAME);
            case "Connection" -> writer.write(CONNECTION_HEADER_NAME);
            case "Content-Length" -> writer.write(CONTENT_LENGTH_HEADER_NAME);
            case "Content-Type" -> writer.write(CONTENT_TYPE_HEADER_NAME);
            case "Date" -> writer.write(DATE_HEADER_NAME);
            case "ETag" -> writer.write(ETAG_HEADER_NAME);
            case "Last-Modified" -> writer.write(LAST_MODIFIED_HEADER_NAME);
            case "Location" -> writer.write(LOCATION_HEADER_NAME);
            case "Server" -> writer.write(SERVER_HEADER_NAME);
            case "Set-Cookie" -> writer.write(SET_COOKIE_HEADER_NAME);
            case "Vary" -> writer.write(VARY_HEADER_NAME);
            default -> writer.writeAscii(name);
        }
    }

    private static int initialHeadCapacity(long headerCount) {
        return (int) Math.min(8192L, 96L + (Math.max(0L, headerCount) * 32L));
    }

    private static final class HeadWriter {
        private byte[] bytes;
        private int size;

        private HeadWriter(int initialCapacity) {
            this.bytes = new byte[Math.max(64, initialCapacity)];
        }

        private void write(byte[] bytes) {
            ensureCapacity(bytes.length);
            writeUnchecked(bytes);
        }

        private void writeUnchecked(byte[] bytes) {
            System.arraycopy(bytes, 0, this.bytes, this.size, bytes.length);
            this.size += bytes.length;
        }

        private void writeAscii(String value) {
            for (int i = 0; i < value.length(); i++) {
                if (value.charAt(i) > 0x7F) {
                    write(value.getBytes(StandardCharsets.US_ASCII));
                    return;
                }
            }

            ensureCapacity(value.length());
            writeLowBytesUnchecked(value);
        }

        private void writeLatin1(String value) {
            for (int i = 0; i < value.length(); i++) {
                if (value.charAt(i) > 0xFF) {
                    write(value.getBytes(StandardCharsets.ISO_8859_1));
                    return;
                }
            }

            ensureCapacity(value.length());
            writeLowBytesUnchecked(value);
        }

        @SuppressWarnings("deprecation")
        private void writeLowBytesUnchecked(String value) {
            value.getBytes(0, value.length(), this.bytes, this.size);
            this.size += value.length();
        }

        private void ensureCapacity(int additionalBytes) {
            long requiredCapacity = (long) this.size + additionalBytes;

            if (requiredCapacity > Integer.MAX_VALUE)
                throw new IllegalStateException("Serialized response head length exceeds maximum supported size.");

            if (requiredCapacity <= this.bytes.length)
                return;

            int newCapacity = this.bytes.length;

            while (newCapacity < requiredCapacity)
                newCapacity = newCapacity <= Integer.MAX_VALUE / 2 ? newCapacity * 2 : Integer.MAX_VALUE;

            this.bytes = Arrays.copyOf(this.bytes, newCapacity);
        }

        private byte[] toByteArray() {
            return Arrays.copyOf(this.bytes, this.size);
        }
    }

    @FunctionalInterface
    interface BodySourceFactory {
        WritableSource create() throws IOException;
    }
}
