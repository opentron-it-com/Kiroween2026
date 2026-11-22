package org.opentron.backend.security.confidential.gateway;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Objects;

final class NativeJsonStringWriter implements AutoCloseable {
    private static final byte[] HEX = "0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    private final OutputStream output;
    private final byte[] buffer = new byte[4096];
    private int position;

    NativeJsonStringWriter(OutputStream output) {
        this.output = Objects.requireNonNull(output, "output");
    }

    void writeAscii(String value) throws IOException {
        for (int index = 0; index < value.length(); index++) {
            writeByte((byte) value.charAt(index));
        }
    }

    void writeJsonString(CharSequence value) throws IOException {
        writeByte((byte) '"');
        writeJsonStringContent(value);
        writeByte((byte) '"');
    }

    void writeJsonStringContent(CharSequence value) throws IOException {
        for (int index = 0; index < value.length();) {
            char current = value.charAt(index++);
            int codePoint;
            if (Character.isHighSurrogate(current)
                    && index < value.length()
                    && Character.isLowSurrogate(value.charAt(index))) {
                codePoint = Character.toCodePoint(current, value.charAt(index++));
            } else {
                codePoint = current;
            }
            if (codePoint == '"' || codePoint == '\\') {
                writeByte((byte) '\\');
                writeByte((byte) codePoint);
            } else if (codePoint < 0x20) {
                writeByte((byte) '\\');
                writeByte((byte) 'u');
                writeByte((byte) '0');
                writeByte((byte) '0');
                writeByte(HEX[(codePoint >>> 4) & 0x0f]);
                writeByte(HEX[codePoint & 0x0f]);
            } else {
                writeUtf8(codePoint);
            }
        }
    }

    @Override
    public void close() throws IOException {
        try {
            flush();
        } finally {
            Arrays.fill(buffer, (byte) 0);
        }
    }

    private void writeUtf8(int codePoint) throws IOException {
        if (codePoint <= 0x7f) {
            writeByte((byte) codePoint);
        } else if (codePoint <= 0x7ff) {
            writeByte((byte) (0xc0 | (codePoint >>> 6)));
            writeByte((byte) (0x80 | (codePoint & 0x3f)));
        } else if (codePoint <= 0xffff) {
            writeByte((byte) (0xe0 | (codePoint >>> 12)));
            writeByte((byte) (0x80 | ((codePoint >>> 6) & 0x3f)));
            writeByte((byte) (0x80 | (codePoint & 0x3f)));
        } else {
            writeByte((byte) (0xf0 | (codePoint >>> 18)));
            writeByte((byte) (0x80 | ((codePoint >>> 12) & 0x3f)));
            writeByte((byte) (0x80 | ((codePoint >>> 6) & 0x3f)));
            writeByte((byte) (0x80 | (codePoint & 0x3f)));
        }
    }

    private void writeByte(byte value) throws IOException {
        buffer[position++] = value;
        if (position == buffer.length) {
            flush();
        }
    }

    private void flush() throws IOException {
        if (position > 0) {
            int written = position;
            try {
                output.write(buffer, 0, written);
            } finally {
                Arrays.fill(buffer, 0, written, (byte) 0);
                position = 0;
            }
        }
    }
}
