package com.soklet.internal.microhttp;

import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * ByteTokenizer is an expandable, first-in first-out byte array that supports tokenization.
 * Bytes are added at the tail and tokenization occurs at the head.
 */
class ByteTokenizer {
    private byte[] array = new byte[0];
    private int base;
    private int position;
    private int size;
    private long totalBytesAdded;
    // The HTTP parser alternates between SPACE and CRLF at the same position.
    // Remember both searches so fragmented tokens only scan newly added bytes.
    private final List<DelimiterSearch> delimiterSearches = new ArrayList<>(2);

    int size() {
        return size - base;
    }

    /**
     * Monotonic count of all bytes ever added to this tokenizer, unaffected by tokenization or
     * compaction. Lets callers detect whether NEW bytes arrived during a window by comparing
     * against an earlier snapshot of this value.
     */
    long totalBytesAdded() {
        return totalBytesAdded;
    }

    int capacity() {
        return array.length;
    }

    int remaining() {
        return size - position;
    }

    int position() {
        return position - base;
    }

    void compact() {
        delimiterSearches.clear();
        if (position == size) {
            base = 0;
            position = 0;
            size = 0;
            return;
        }

        base = position;

        if (base > array.length / 2) {
            compactInPlace();
        }
    }

    void add(ByteBuffer buffer) {
        int bufferLen = buffer.remaining();
        if (array.length - size < bufferLen) {
            compactInPlace();
        }
        if (array.length - size < bufferLen) {
            array = Arrays.copyOf(array, expandedCapacity(array.length, size, bufferLen));
        }
        buffer.get(array, size, bufferLen);
        size += bufferLen;
        totalBytesAdded += bufferLen;
    }

    CapturedPrefix capturePrefixAndRelease(int endExclusive, int maximumBytes) {
        if (endExclusive < base || endExclusive > size) {
            throw new IllegalArgumentException("Capture boundary is outside the buffered request.");
        }
        if (maximumBytes < 0) {
            throw new IllegalArgumentException("Maximum capture size must not be negative.");
        }

        int observedByteCount = endExclusive - base;
        int capturedByteCount = Math.min(observedByteCount, maximumBytes);
        byte[] capturedBytes = Arrays.copyOfRange(array, base, base + capturedByteCount);

        array = new byte[0];
        base = 0;
        position = 0;
        size = 0;
        delimiterSearches.clear();

        return new CapturedPrefix(capturedBytes, observedByteCount,
                capturedByteCount < observedByteCount);
    }

    byte @Nullable [] next(int length) {
        if (size - position < length) {
            return null;
        }
        byte[] result = Arrays.copyOfRange(array, position, position + length);
        position += length;
        return result;
    }

    byte @Nullable [] next(byte[] delimiter) {
        int index = indexOf(delimiter);
        if (index < 0) {
            return null;
        }
        byte[] result = Arrays.copyOfRange(array, position, index);
        position = index + delimiter.length;
        return result;
    }

    @Nullable
    String nextAsciiString(byte[] delimiter, String field) {
        int index = indexOf(delimiter);
        if (index < 0) {
            return null;
        }

        for (int i = position; i < index; i++) {
            if ((array[i] & 0x80) != 0) {
                throw new MalformedRequestException("non-ascii " + field);
            }
        }

        String result = string(position, index, StandardCharsets.US_ASCII);
        position = index + delimiter.length;
        return result;
    }

    int nextLength(byte[] delimiter) {
        int index = indexOf(delimiter);
        if (index < 0) {
            return -1;
        }

        int result = index - position;
        position = index + delimiter.length;
        return result;
    }

    int rawPosition() {
        return position;
    }

    byte rawByte(int index) {
        return array[index];
    }

    String string(int startInclusive, int endExclusive, Charset charset) {
        return new String(array, startInclusive, endExclusive - startInclusive, charset);
    }

    boolean asciiEquals(int startInclusive, int endExclusive, String value) {
        int length = endExclusive - startInclusive;
        if (value.length() != length) {
            return false;
        }

        for (int i = 0; i < length; i++) {
            if ((array[startInclusive + i] & 0xFF) != value.charAt(i)) {
                return false;
            }
        }

        return true;
    }

    void advanceTo(int index) {
        position = index;
    }

    int indexOf(byte[] delimiter) {
        if (delimiter.length == 0)
            return position;

        DelimiterSearch search = null;
        for (DelimiterSearch candidate : delimiterSearches) {
            if (Arrays.equals(candidate.delimiter, delimiter)) {
                search = candidate;
                break;
            }
        }
        // Other tokenizer callers may use arbitrary delimiters; keep the cache bounded.
        if (search == null && delimiterSearches.size() < 2) {
            search = new DelimiterSearch(delimiter);
            delimiterSearches.add(search);
        }

        int searchStart = position;
        if (search != null && search.position == position) {
            if (search.foundIndex >= 0)
                return search.foundIndex;
            searchStart = search.nextSearchPosition;
        }

        for (int i = searchStart; i <= size - delimiter.length; i++) {
            if (Arrays.equals(delimiter, 0, delimiter.length, array, i, i + delimiter.length)) {
                if (search != null) {
                    search.position = position;
                    search.foundIndex = i;
                }
                return i;
            }
        }
        if (search != null) {
            search.position = position;
            search.foundIndex = -1;
            // A delimiter may straddle the next read: keep its incomplete suffix.
            search.nextSearchPosition = Math.max(position, size - delimiter.length + 1);
        }
        return -1;
    }

    private static final class DelimiterSearch {
        private final byte[] delimiter;
        private int position = -1;
        private int nextSearchPosition;
        private int foundIndex = -1;

        private DelimiterSearch(byte[] delimiter) {
            this.delimiter = delimiter.clone();
        }
    }

    private void compactInPlace() {
        if (base == 0) {
            return;
        }

        delimiterSearches.clear();

        int newSize = size - base;
        int newPosition = position - base;
        System.arraycopy(array, base, array, 0, newSize);
        base = 0;
        position = newPosition;
        size = newSize;
    }

    static int expandedCapacity(int currentCapacity, int currentSize, int additionalBytes) {
        if (currentCapacity < 0 || currentSize < 0 || additionalBytes < 0) {
            throw new IllegalArgumentException("Capacity, size, and additional bytes must be >= 0.");
        }

        long requiredCapacity = (long) currentSize + additionalBytes;
        if (requiredCapacity > Integer.MAX_VALUE) {
            throw new RequestTooLargeException();
        }

        int doubledCapacity = currentCapacity <= Integer.MAX_VALUE / 2
                ? currentCapacity * 2
                : Integer.MAX_VALUE;
        return Math.max((int) requiredCapacity, doubledCapacity);
    }

    record CapturedPrefix(byte[] bytes, long observedByteCount, boolean truncated) {
    }

}
