package com.soklet.internal.microhttp;

import java.util.ArrayList;
import java.util.List;

/**
 * Accumulates byte arrays, coalescing small fragments so chunk framing cannot
 * amplify retained heap into one object and list entry per payload byte.
 */
class ByteMerger {

    private static final int BLOCK_SIZE = 8192;
    private final List<byte[]> arrays = new ArrayList<>();
    private byte[] pending;
    private int pendingLength;
    private long size;

    void add(byte[] array) {
        size += array.length;
        int offset = 0;
        while (offset < array.length) {
            if (pending == null && array.length - offset >= BLOCK_SIZE) {
                if (offset == 0) {
                    arrays.add(array);
                    return;
                }
                pending = new byte[BLOCK_SIZE];
            }
            if (pending == null)
                pending = new byte[BLOCK_SIZE];
            int length = Math.min(array.length - offset, BLOCK_SIZE - pendingLength);
            System.arraycopy(array, offset, pending, pendingLength, length);
            offset += length;
            pendingLength += length;
            if (pendingLength == BLOCK_SIZE) {
                arrays.add(pending);
                pending = null;
                pendingLength = 0;
            }
        }
    }

    byte[] merge() {
        if (size > Integer.MAX_VALUE)
            throw new IllegalStateException("Merged byte array length exceeds maximum supported size.");
        return merge(Integer.MAX_VALUE);
    }

    byte[] merge(int maxLength) {
        if (maxLength < 0)
            throw new IllegalArgumentException("Maximum length must be >= 0.");
        if (size > maxLength)
            throw new RequestTooLargeException();
        byte[] result = new byte[Math.toIntExact(size)];
        int offset = 0;
        for (byte[] array : arrays) {
            System.arraycopy(array, 0, result, offset, array.length);
            offset += array.length;
        }
        if (pendingLength > 0)
            System.arraycopy(pending, 0, result, offset, pendingLength);
        return result;
    }

    long sumOfLengths() {
        return size;
    }
}
