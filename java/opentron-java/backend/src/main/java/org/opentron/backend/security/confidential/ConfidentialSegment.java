package org.opentron.backend.security.confidential;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Objects;

/**
 * Owns UTF-8 bytes in native memory and clears that memory before releasing it.
 */
public final class ConfidentialSegment implements AutoCloseable {
    private final Arena arena;
    private final boolean ownsArena;
    private MemorySegment segment;
    private long byteSize;
    private boolean closed;

    private ConfidentialSegment(Arena arena, MemorySegment segment, long byteSize) {
        this(arena, segment, byteSize, true);
    }

    private ConfidentialSegment(
            Arena arena, MemorySegment segment, long byteSize, boolean ownsArena) {
        this.arena = arena;
        this.segment = segment;
        this.byteSize = byteSize;
        this.ownsArena = ownsArena;
    }

    public static ConfidentialSegment fromUtf8(String value) {
        Objects.requireNonNull(value, "value");
        long byteSize = utf8Length(value);
        Arena arena = Arena.ofConfined();
        MemorySegment segment = null;
        try {
            segment = arena.allocate(byteSize, 1);
            ByteBuffer output = segment.asByteBuffer();
            var encoder = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE)
                    .replaceWith(new byte[] {'?'});
            try {
                var encoded = encoder.encode(CharBuffer.wrap(value), output, true);
                if (encoded.isError()) {
                    encoded.throwException();
                }
                var flushed = encoder.flush(output);
                if (flushed.isError()) {
                    flushed.throwException();
                }
            } catch (CharacterCodingException exception) {
                throw new IllegalStateException("Unable to encode UTF-8 into native memory.", exception);
            }
            if (output.position() != byteSize) {
                throw new IllegalStateException("UTF-8 byte length changed during native encoding.");
            }
            return new ConfidentialSegment(arena, segment, byteSize);
        } catch (RuntimeException | Error failure) {
            if (segment != null) {
                segment.fill((byte) 0);
            }
            try {
                arena.close();
            } catch (RuntimeException | Error closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    public static ConfidentialSegment allocate(long byteSize) {
        if (byteSize < 0) {
            throw new IllegalArgumentException("Segment size must not be negative.");
        }
        Arena arena = Arena.ofConfined();
        try {
            return new ConfidentialSegment(arena, arena.allocate(byteSize, 1), byteSize);
        } catch (RuntimeException | Error failure) {
            try {
                arena.close();
            } catch (RuntimeException | Error closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    public MemorySegment memory() {
        ensureOpen();
        return segment;
    }

    public long byteSize() {
        ensureOpen();
        return byteSize;
    }

    public ConfidentialSegment slice(long offset, long length) {
        ensureOpen();
        checkRange(offset, length, byteSize);
        return new ConfidentialSegment(arena, segment.asSlice(offset, length), length, false);
    }

    public void truncate(long newByteSize) {
        ensureOpen();
        if (newByteSize < 0 || newByteSize > byteSize) {
            throw new IllegalArgumentException("Truncated segment size is invalid.");
        }
        segment.asSlice(newByteSize, byteSize - newByteSize).fill((byte) 0);
        segment = segment.asSlice(0, newByteSize);
        byteSize = newByteSize;
    }

    public void writeByte(long offset, byte value) {
        ensureOpen();
        checkRange(offset, 1, byteSize);
        segment.set(ValueLayout.JAVA_BYTE, offset, value);
    }

    public byte readByte(long offset) {
        ensureOpen();
        checkRange(offset, 1, byteSize);
        return segment.get(ValueLayout.JAVA_BYTE, offset);
    }

    public void writeInt(long offset, int value) {
        ensureOpen();
        checkRange(offset, Integer.BYTES, byteSize);
        for (int index = 0; index < Integer.BYTES; index++) {
            segment.set(ValueLayout.JAVA_BYTE, offset + index,
                    (byte) (value >>> (index * Byte.SIZE)));
        }
    }

    public int readInt(long offset) {
        ensureOpen();
        checkRange(offset, Integer.BYTES, byteSize);
        int value = 0;
        for (int index = 0; index < Integer.BYTES; index++) {
            value |= Byte.toUnsignedInt(segment.get(ValueLayout.JAVA_BYTE, offset + index))
                    << (index * Byte.SIZE);
        }
        return value;
    }

    public void writeLong(long offset, long value) {
        ensureOpen();
        checkRange(offset, Long.BYTES, byteSize);
        for (int index = 0; index < Long.BYTES; index++) {
            segment.set(ValueLayout.JAVA_BYTE, offset + index,
                    (byte) (value >>> (index * Byte.SIZE)));
        }
    }

    public long readLong(long offset) {
        ensureOpen();
        checkRange(offset, Long.BYTES, byteSize);
        long value = 0;
        for (int index = 0; index < Long.BYTES; index++) {
            value |= (long) Byte.toUnsignedInt(
                    segment.get(ValueLayout.JAVA_BYTE, offset + index))
                    << (index * Byte.SIZE);
        }
        return value;
    }

    public void copyTo(
            ConfidentialSegment target, long sourceOffset, long targetOffset, long length) {
        ensureOpen();
        Objects.requireNonNull(target, "target").ensureOpen();
        checkRange(sourceOffset, length, byteSize);
        checkRange(targetOffset, length, target.byteSize);
        target.segment.asSlice(targetOffset, length)
                .copyFrom(segment.asSlice(sourceOffset, length));
    }

    public int readFrom(
            FileChannel source, long sourceOffset, long targetOffset, int length)
            throws IOException {
        ensureOpen();
        Objects.requireNonNull(source, "source");
        checkRange(targetOffset, length, byteSize);
        if (sourceOffset < 0 || length < 0) {
            throw new IndexOutOfBoundsException("Requested file range is invalid.");
        }
        if (length == 0) {
            return 0;
        }
        MemorySegment destination = segment.asSlice(targetOffset, length);
        destination.fill((byte) 0);
        return source.read(destination.asByteBuffer(), sourceOffset);
    }

    public void updateDigest(MessageDigest digest, long offset, long length) {
        ensureOpen();
        Objects.requireNonNull(digest, "digest");
        checkRange(offset, length, byteSize);
        if (length > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Digest slice exceeds ByteBuffer capacity.");
        }
        digest.update(segment.asSlice(offset, length).asByteBuffer());
    }

    public void writeUtf8(String value, long targetOffset) {
        ensureOpen();
        Objects.requireNonNull(value, "value");
        long requiredBytes = utf8Length(value);
        checkRange(targetOffset, requiredBytes, byteSize);
        long offset = targetOffset;
        for (int index = 0; index < value.length();) {
            char current = value.charAt(index++);
            int codePoint;
            if (Character.isHighSurrogate(current)
                    && index < value.length()
                    && Character.isLowSurrogate(value.charAt(index))) {
                codePoint = Character.toCodePoint(current, value.charAt(index++));
            } else if (Character.isSurrogate(current)) {
                writeByte(offset++, (byte) '?');
                continue;
            } else {
                codePoint = current;
            }
            if (codePoint <= 0x7f) {
                writeByte(offset++, (byte) codePoint);
            } else if (codePoint <= 0x7ff) {
                writeByte(offset++, (byte) (0xc0 | (codePoint >>> 6)));
                writeByte(offset++, (byte) (0x80 | (codePoint & 0x3f)));
            } else if (codePoint <= 0xffff) {
                writeByte(offset++, (byte) (0xe0 | (codePoint >>> 12)));
                writeByte(offset++, (byte) (0x80 | ((codePoint >>> 6) & 0x3f)));
                writeByte(offset++, (byte) (0x80 | (codePoint & 0x3f)));
            } else {
                writeByte(offset++, (byte) (0xf0 | (codePoint >>> 18)));
                writeByte(offset++, (byte) (0x80 | ((codePoint >>> 12) & 0x3f)));
                writeByte(offset++, (byte) (0x80 | ((codePoint >>> 6) & 0x3f)));
                writeByte(offset++, (byte) (0x80 | (codePoint & 0x3f)));
            }
        }
    }

    public String toUtf8String() {
        ensureOpen();
        byte[] encoded = segment.toArray(ValueLayout.JAVA_BYTE);
        try {
            return new String(encoded, StandardCharsets.UTF_8);
        } finally {
            Arrays.fill(encoded, (byte) 0);
        }
    }

    public static long utf8Length(String value) {
        Objects.requireNonNull(value, "value");
        long length = 0;
        for (int offset = 0; offset < value.length();) {
            char current = value.charAt(offset++);
            if (Character.isHighSurrogate(current)
                    && offset < value.length()
                    && Character.isLowSurrogate(value.charAt(offset))) {
                offset++;
                length += 4;
            } else if (Character.isSurrogate(current) || current <= 0x7f) {
                length++;
            } else if (current <= 0x7ff) {
                length += 2;
            } else {
                length += 3;
            }
        }
        return length;
    }

    public void zeroize() {
        ensureOpen();
        segment.fill((byte) 0);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        segment.fill((byte) 0);
        if (ownsArena) {
            arena.close();
        }
        closed = true;
    }

    private static void checkRange(long offset, long length, long capacity) {
        if (offset < 0 || length < 0 || offset > capacity - length) {
            throw new IndexOutOfBoundsException("Requested range exceeds the segment bounds.");
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Confidential segment is closed.");
        }
    }
}
