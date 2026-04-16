package com.soklet.internal.microhttp;

import java.util.ArrayList;
import java.util.List;

/**
 * ByteMerger is a utility that accumulates a sequence of byte arrays and merges them into a single flat array upon request.
 */
class ByteMerger {

    private final List<byte[]> arrays = new ArrayList<>();

    void add(byte[] array) {
        arrays.add(array);
    }

    byte[] merge() {
        long size = sumOfLengths();
        if (size > Integer.MAX_VALUE) {
            throw new IllegalStateException("Merged byte array length exceeds maximum supported size.");
        }
        byte[] result = new byte[Math.toIntExact(size)];
        int offset = 0;
        for (byte[] array : arrays) {
            System.arraycopy(array, 0, result, offset, array.length);
            offset += array.length;
        }
        return result;
    }

    byte[] merge(int maxLength) {
        if (maxLength < 0) {
            throw new IllegalArgumentException("Maximum length must be >= 0.");
        }

        long size = sumOfLengths();
        if (size > maxLength) {
            throw new RequestTooLargeException();
        }

        byte[] result = new byte[Math.toIntExact(size)];
        int offset = 0;
        for (byte[] array : arrays) {
            System.arraycopy(array, 0, result, offset, array.length);
            offset += array.length;
        }
        return result;
    }

    long sumOfLengths() {
        long sum = 0L;
        for (byte[] array : arrays) {
            sum += array.length;
        }
        return sum;
    }

}
