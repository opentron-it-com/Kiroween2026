package org.opentron.backend.security.confidential;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class ConfidentialSegmentTest {
    @Test
    void copiesUtf8IntoNativeMemoryAndReportsItsSize() {
        byte[] expected = "A\u03a9".getBytes(StandardCharsets.UTF_8);

        try (ConfidentialSegment confidential = ConfidentialSegment.fromUtf8("A\u03a9")) {
            assertEquals(expected.length, confidential.byteSize());
            for (int i = 0; i < expected.length; i++) {
                assertEquals(expected[i], confidential.memory().get(ValueLayout.JAVA_BYTE, i));
            }
        }
    }

    @Test
    void encodesUtf8DirectlyIntoNativeMemoryWithCompatibleMalformedSurrogateHandling() {
        String value = "A\u03a9\ud83d\ude00\ud800";
        byte[] expected = value.getBytes(StandardCharsets.UTF_8);

        try (ConfidentialSegment confidential = ConfidentialSegment.fromUtf8(value)) {
            assertEquals(expected.length, confidential.byteSize());
            assertEquals(expected.length, ConfidentialSegment.utf8Length(value));
            assertArrayEquals(expected, confidential.memory().toArray(ValueLayout.JAVA_BYTE));
        }
    }

    @Test
    void zeroizeClearsTheOwnedMemory() {
        try (ConfidentialSegment confidential = ConfidentialSegment.fromUtf8("sensitive")) {
            MemorySegment memory = confidential.memory();
            confidential.zeroize();

            for (long i = 0; i < confidential.byteSize(); i++) {
                assertEquals((byte) 0, memory.get(ValueLayout.JAVA_BYTE, i));
            }
        }
    }

    @Test
    void closeZeroizesAndInvalidatesPreviouslyObtainedMemory() {
        ConfidentialSegment confidential = ConfidentialSegment.fromUtf8("sensitive");
        MemorySegment memory = confidential.memory();

        confidential.close();
        confidential.close();

        assertThrows(IllegalStateException.class,
                () -> memory.get(ValueLayout.JAVA_BYTE, 0));
        assertThrows(IllegalStateException.class, confidential::memory);
        assertThrows(IllegalStateException.class, confidential::zeroize);
        assertFalse(memory.scope().isAlive());
    }

    @Test
    void rejectsNullInput() {
        assertThrows(NullPointerException.class, () -> ConfidentialSegment.fromUtf8(null));
    }

    @Test
    void rejectsNegativeAllocationSize() {
        assertThrows(IllegalArgumentException.class, () -> ConfidentialSegment.allocate(-1));
    }
}
