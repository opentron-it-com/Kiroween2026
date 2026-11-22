package org.opentron.backend.security.confidential.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.opentron.backend.security.confidential.ConfidentialSegment;

class NativeUtf8CharSequenceTest {
    @Test
    void exposesUtf16CharactersAndSubsequencesWithoutDecodingWholePrompt() {
        try (ConfidentialSegment segment = ConfidentialSegment.fromUtf8("A😀Ω")) {
            NativeUtf8CharSequence text = new NativeUtf8CharSequence(segment);

            assertEquals(4, text.length());
            assertEquals("A😀Ω", text.toString());
            assertEquals("😀", text.subSequence(1, 3).toString());
            assertEquals(0xD83D, text.charAt(1));
            assertEquals(0xDE00, text.charAt(2));
        }
    }

    @Test
    void rejectsMalformedUtf8InNativeMemory() {
        try (ConfidentialSegment segment = ConfidentialSegment.allocate(1)) {
            segment.writeByte(0, (byte) 0xC0);

            assertThrows(IllegalArgumentException.class,
                    () -> new NativeUtf8CharSequence(segment));
        }
    }
}
