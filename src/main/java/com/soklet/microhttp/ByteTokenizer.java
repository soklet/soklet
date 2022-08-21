/**
 * MIT License
 *
 * Copyright (c) 2022 Elliot Barlas
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.soklet.microhttp;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * ByteTokenizer is an expandable, first-in first-out byte array that supports tokenization.
 * Bytes are added at the tail and tokenization occurs at the head.
 */
class ByteTokenizer {
    private byte[] array = new byte[0];
    private int position;
    private int size;

    int size() {
        return size;
    }

    int capacity() {
        return array.length;
    }

    int remaining() {
        return size - position;
    }

    void compact() {
        array = Arrays.copyOfRange(array, position, size);
        size = size - position;
        position = 0;
    }

    void add(ByteBuffer buffer) {
        int bufferLen = buffer.remaining();
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

    private int indexOf(byte[] delimiter) {
        for (int i = position; i <= size - delimiter.length; i++) {
            if (Arrays.equals(delimiter, 0, delimiter.length, array, i, i + delimiter.length)) {
                return i;
            }
        }
        return -1;
    }

}
