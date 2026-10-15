package com.soklet.internal.microhttp;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class ByteMergerTests {
    @Test
    void tinyFragmentsRetainBlocksInsteadOfIndividualArrays() throws Exception {
        ByteMerger merger = new ByteMerger();
        byte[] expected = new byte[100_000];
        new Random(42).nextBytes(expected);
        for (byte value : expected)
            merger.add(new byte[]{value});
        assertEquals(expected.length, merger.sumOfLengths());
        assertArrayEquals(expected, merger.merge());
        assertArrayEquals(expected, merger.merge());
        Field field = ByteMerger.class.getDeclaredField("arrays");
        field.setAccessible(true);
        assertTrue(((List<?>) field.get(merger)).size() <= 13);
        assertThrows(RequestTooLargeException.class, () -> merger.merge(expected.length - 1));
    }

    @Test
    void mixedFragmentsRemainOrderedAcrossBlockBoundaries() {
        ByteMerger merger = new ByteMerger();
        byte[] expected = new byte[100_000];
        new Random(7).nextBytes(expected);
        int offset = 0;
        for (int size : new int[]{0, 1, 9000, 8000, 8192, 0, 1, 50_000, 24_806}) {
            merger.add(java.util.Arrays.copyOfRange(expected, offset, offset + size));
            offset += size;
        }
        assertEquals(expected.length, offset);
        assertArrayEquals(expected, merger.merge(expected.length));
        assertThrows(IllegalArgumentException.class, () -> merger.merge(-1));
    }
}
