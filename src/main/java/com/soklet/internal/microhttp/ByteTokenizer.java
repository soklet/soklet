package com.soklet.internal.microhttp;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * ByteTokenizer is an expandable, first-in first-out byte array that supports tokenization.
 * Bytes are added at the tail and tokenization occurs at the head.
 */
class ByteTokenizer {
    private byte[] array = new byte[0];
    private int base;
    private int position;
    private int size;

    int size() {
        return size - base;
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
            array = Arrays.copyOf(array, Math.max(size + bufferLen, array.length * 2));
        }
        buffer.get(array, size, bufferLen);
        size += bufferLen;
    }

    byte[] next(int length) {
        if (size - position < length) {
            return null;
        }
        byte[] result = Arrays.copyOfRange(array, position, position + length);
        position += length;
        return result;
    }

    byte[] next(byte[] delimiter) {
        int index = indexOf(delimiter);
        if (index < 0) {
            return null;
        }
        byte[] result = Arrays.copyOfRange(array, position, index);
        position = index + delimiter.length;
        return result;
    }

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
        for (int i = position; i <= size - delimiter.length; i++) {
            if (Arrays.equals(delimiter, 0, delimiter.length, array, i, i + delimiter.length)) {
                return i;
            }
        }
        return -1;
    }

    private void compactInPlace() {
        if (base == 0) {
            return;
        }

        int newSize = size - base;
        int newPosition = position - base;
        System.arraycopy(array, base, array, 0, newSize);
        base = 0;
        position = newPosition;
        size = newSize;
    }

}
