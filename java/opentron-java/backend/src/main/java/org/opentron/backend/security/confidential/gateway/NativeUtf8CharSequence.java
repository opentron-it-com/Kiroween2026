package org.opentron.backend.security.confidential.gateway;

import java.util.Objects;

import org.opentron.backend.security.confidential.ConfidentialSegment;

public final class NativeUtf8CharSequence implements CharSequence {
    private final ConfidentialSegment segment;
    private final int[] byteOffsets;
    private final int start;
    private final int end;

    public NativeUtf8CharSequence(ConfidentialSegment segment) {
        this(segment, 0, buildOffsets(Objects.requireNonNull(segment, "segment")), -1);
    }

    private NativeUtf8CharSequence(
            ConfidentialSegment segment, int start, int[] byteOffsets, int end) {
        this.segment = segment;
        this.start = start;
        this.end = end < 0 ? byteOffsets.length - 1 : end;
        this.byteOffsets = byteOffsets;
    }

    @Override
    public int length() {
        return end - start;
    }

    @Override
    public char charAt(int index) {
        if (index < 0 || index >= length()) {
            throw new IndexOutOfBoundsException(index);
        }
        int absolute = start + index;
        int codePoint = codePointAt(absolute);
        if (codePoint <= Character.MAX_VALUE) {
            return (char) codePoint;
        }
        return byteOffsets[absolute + 1] == byteOffsets[absolute]
                ? Character.highSurrogate(codePoint)
                : Character.lowSurrogate(codePoint);
    }

    @Override
    public CharSequence subSequence(int beginIndex, int endIndex) {
        if (beginIndex < 0 || endIndex < beginIndex || endIndex > length()) {
            throw new IndexOutOfBoundsException();
        }
        return new NativeUtf8CharSequence(
                segment, start + beginIndex, byteOffsets, start + endIndex);
    }

    @Override
    public String toString() {
        char[] characters = new char[length()];
        for (int index = 0; index < characters.length; index++) {
            characters[index] = charAt(index);
        }
        return new String(characters);
    }

    private int codePointAt(int charOffset) {
        long byteOffset = byteOffsets[charOffset];
        int first = Byte.toUnsignedInt(segment.readByte(byteOffset));
        if (first < 0x80) {
            return first;
        }
        int continuationCount = first < 0xE0 ? 1 : first < 0xF0 ? 2 : 3;
        int codePoint = first & (continuationCount == 1 ? 0x1F
                : continuationCount == 2 ? 0x0F : 0x07);
        for (int index = 1; index <= continuationCount; index++) {
            codePoint = (codePoint << 6)
                    | (Byte.toUnsignedInt(segment.readByte(byteOffset + index)) & 0x3F);
        }
        return codePoint;
    }

    private static int[] buildOffsets(ConfidentialSegment segment) {
        if (segment.byteSize() > Integer.MAX_VALUE - 1) {
            throw new IllegalArgumentException("Native text exceeds supported CharSequence size.");
        }
        int characterCount = 0;
        for (long offset = 0; offset < segment.byteSize();) {
            int first = Byte.toUnsignedInt(segment.readByte(offset));
            int byteCount = utf8Length(first);
            int codePoint = readCodePoint(segment, offset, first, byteCount);
            characterCount += Character.charCount(codePoint);
            offset += byteCount;
        }

        int[] offsets = new int[characterCount + 1];
        int charOffset = 0;
        long byteOffset = 0;
        while (byteOffset < segment.byteSize()) {
            offsets[charOffset++] = Math.toIntExact(byteOffset);
            int first = Byte.toUnsignedInt(segment.readByte(byteOffset));
            int byteCount = utf8Length(first);
            int codePoint = readCodePoint(segment, byteOffset, first, byteCount);
            if (codePoint > Character.MAX_VALUE) {
                offsets[charOffset++] = Math.toIntExact(byteOffset);
            }
            byteOffset += byteCount;
        }
        offsets[charOffset] = Math.toIntExact(byteOffset);
        return java.util.Arrays.copyOf(offsets, charOffset + 1);
    }

    private static int readCodePoint(
            ConfidentialSegment segment, long byteOffset, int first, int byteCount) {
        int codePoint = first & (byteCount == 1 ? 0x7F
                : byteCount == 2 ? 0x1F : byteCount == 3 ? 0x0F : 0x07);
        for (int continuation = 1; continuation < byteCount; continuation++) {
            if (byteOffset + continuation >= segment.byteSize()) {
                throw new IllegalArgumentException("Native text contains incomplete UTF-8.");
            }
            int next = Byte.toUnsignedInt(segment.readByte(byteOffset + continuation));
            if ((next & 0xC0) != 0x80) {
                throw new IllegalArgumentException("Native text contains invalid UTF-8.");
            }
            codePoint = (codePoint << 6) | (next & 0x3F);
        }
        if (!validCodePoint(codePoint, byteCount)) {
            throw new IllegalArgumentException("Native text contains invalid UTF-8.");
        }
        return codePoint;
    }

    private static int utf8Length(int firstByte) {
        if (firstByte < 0x80) {
            return 1;
        }
        if (firstByte >= 0xC2 && firstByte <= 0xDF) {
            return 2;
        }
        if (firstByte >= 0xE0 && firstByte <= 0xEF) {
            return 3;
        }
        if (firstByte >= 0xF0 && firstByte <= 0xF4) {
            return 4;
        }
        throw new IllegalArgumentException("Native text contains invalid UTF-8.");
    }

    private static boolean validCodePoint(int codePoint, int byteCount) {
        return codePoint <= Character.MAX_CODE_POINT
                && (codePoint < 0xD800 || codePoint > 0xDFFF)
                && !(byteCount == 2 && codePoint < 0x80)
                && !(byteCount == 3 && codePoint < 0x800)
                && !(byteCount == 4 && codePoint < 0x10000);
    }
}
