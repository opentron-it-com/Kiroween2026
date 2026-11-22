package org.opentron.backend.security.confidential.gateway.api;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.opentron.backend.security.confidential.ConfidentialSegment;

final class NativePromptJsonReader {
    private static final int MAX_PROMPT_CHARS = 1_000_000;
    private static final int MAX_PROMPT_BYTES = MAX_PROMPT_CHARS * 4;
    private static final int MAX_JSON_BYTES = MAX_PROMPT_CHARS * 6 + 64;
    private static final int MAX_BATCH_SEGMENTS = 100;
    private static final int MAX_BATCH_CHARS = 5_000_000;
    private static final int MAX_BATCH_BYTES = MAX_BATCH_CHARS * 4;
    private static final int MAX_SECURE_CONTEXT_CHARS = 2_000_000;
    private static final int MAX_BATCH_JSON_BYTES =
            MAX_BATCH_CHARS * 6 + MAX_SECURE_CONTEXT_CHARS + 1024;

    private NativePromptJsonReader() {
    }

    static ConfidentialSegment readText(InputStream input, long contentLength) throws IOException {
        if (contentLength > MAX_JSON_BYTES) {
            throw new IllegalArgumentException("Request body exceeds the supported size.");
        }
        int promptCapacity = contentLength >= 0
                ? (int) Math.min(contentLength, MAX_PROMPT_BYTES)
                : MAX_PROMPT_BYTES;
        Parser parser = new Parser(
                Objects.requireNonNull(input, "input"), MAX_JSON_BYTES);
        return parser.readText(promptCapacity);
    }

    static NativePromptBatch readSegments(InputStream input, long contentLength)
            throws IOException {
        if (contentLength > MAX_BATCH_JSON_BYTES) {
            throw new IllegalArgumentException("Request body exceeds the supported size.");
        }
        int storageCapacity = contentLength >= 0
                ? (int) Math.min(contentLength, MAX_BATCH_BYTES)
                : MAX_BATCH_BYTES;
        return new Parser(
                Objects.requireNonNull(input, "input"), MAX_BATCH_JSON_BYTES)
                .readSegments(storageCapacity);
    }

    static NativeDesanitizeRequest readDesanitize(InputStream input, long contentLength)
            throws IOException {
        if (contentLength > MAX_JSON_BYTES + MAX_SECURE_CONTEXT_CHARS) {
            throw new IllegalArgumentException("Request body exceeds the supported size.");
        }
        int capacity = contentLength >= 0
                ? (int) Math.min(contentLength, MAX_PROMPT_BYTES)
                : MAX_PROMPT_BYTES;
        return new Parser(
                Objects.requireNonNull(input, "input"),
                MAX_JSON_BYTES + MAX_SECURE_CONTEXT_CHARS)
                .readDesanitize(capacity);
    }

    static NativeBatchDesanitizeRequest readBatchDesanitize(
            InputStream input, long contentLength) throws IOException {
        if (contentLength > MAX_BATCH_JSON_BYTES) {
            throw new IllegalArgumentException("Request body exceeds the supported size.");
        }
        int capacity = contentLength >= 0
                ? (int) Math.min(contentLength, MAX_BATCH_BYTES)
                : MAX_BATCH_BYTES;
        return new Parser(
                Objects.requireNonNull(input, "input"), MAX_BATCH_JSON_BYTES)
                .readBatchDesanitize(capacity);
    }

    static final class NativePromptBatch implements AutoCloseable {
        private final ConfidentialSegment storage;
        private final List<ConfidentialSegment> segments;

        private NativePromptBatch(
                ConfidentialSegment storage, List<ConfidentialSegment> segments) {
            this.storage = Objects.requireNonNull(storage, "storage");
            this.segments = List.copyOf(segments);
        }

        List<ConfidentialSegment> segments() {
            return segments;
        }

        @Override
        public void close() {
            try {
                for (ConfidentialSegment segment : segments) {
                    segment.close();
                }
            } finally {
                storage.close();
            }
        }
    }

    static final class NativeDesanitizeRequest implements AutoCloseable {
        private final ConfidentialSegment response;
        private final String secureContext;

        private NativeDesanitizeRequest(
                ConfidentialSegment response, String secureContext) {
            this.response = response;
            this.secureContext = secureContext;
        }

        ConfidentialSegment response() {
            return response;
        }

        String secureContext() {
            return secureContext;
        }

        @Override
        public void close() {
            response.close();
        }
    }

    static final class NativeBatchDesanitizeRequest implements AutoCloseable {
        private final NativePromptBatch responses;
        private final String secureContext;

        private NativeBatchDesanitizeRequest(
                NativePromptBatch responses, String secureContext) {
            this.responses = responses;
            this.secureContext = secureContext;
        }

        NativePromptBatch responses() {
            return responses;
        }

        String secureContext() {
            return secureContext;
        }

        @Override
        public void close() {
            responses.close();
        }
    }

    private static final class Parser {
        private final InputStream input;
        private final int maxJsonBytes;
        private int bytesRead;
        private int pending = -2;

        private Parser(InputStream input, int maxJsonBytes) {
            this.input = input;
            this.maxJsonBytes = maxJsonBytes;
        }

        private ConfidentialSegment readText(int promptCapacity) throws IOException {
            ConfidentialSegment prompt = ConfidentialSegment.allocate(promptCapacity);
            try {
                whitespace();
                expect('{');
                boolean textSeen = false;
                while (true) {
                    whitespace();
                    if (peek() != '"') {
                        throw invalidRequest();
                    }
                    String field = readKey();
                    whitespace();
                    expect(':');
                    whitespace();
                    if ("text".equals(field) && !textSeen && peek() == '"') {
                        readString(prompt);
                        textSeen = true;
                    } else if ("tenantId".equals(field) && peek() == '"') {
                        skipString();
                    } else {
                        throw invalidRequest();
                    }
                    whitespace();
                    int delimiter = read();
                    if (delimiter == '}') {
                        break;
                    }
                    if (delimiter != ',') {
                        throw invalidRequest();
                    }
                }
                if (!textSeen) {
                    throw invalidRequest();
                }
                whitespace();
                if (read() != -1) {
                    throw invalidRequest();
                }
                return prompt;
            } catch (RuntimeException | IOException | Error failure) {
                prompt.close();
                throw failure;
            }
        }

        private NativeDesanitizeRequest readDesanitize(int responseCapacity)
                throws IOException {
            ConfidentialSegment response = ConfidentialSegment.allocate(responseCapacity);
            try {
                whitespace();
                expect('{');
                boolean responseSeen = false;
                String secureContext = null;
                while (true) {
                    whitespace();
                    if (peek() == '}') {
                        read();
                        break;
                    }
                    String field = readKey();
                    whitespace();
                    expect(':');
                    whitespace();
                    if ("modelResponse".equals(field) && !responseSeen && peek() == '"') {
                        readString(response);
                        responseSeen = true;
                    } else if ("secureContext".equals(field)
                            && secureContext == null && peek() == '"') {
                        secureContext = readCapturedString(MAX_SECURE_CONTEXT_CHARS);
                    } else {
                        throw invalidRequest();
                    }
                    whitespace();
                    int delimiter = read();
                    if (delimiter == '}') {
                        break;
                    }
                    if (delimiter != ',') {
                        throw invalidRequest();
                    }
                }
                if (!responseSeen || secureContext == null || secureContext.isEmpty()) {
                    throw invalidRequest();
                }
                whitespace();
                if (read() != -1) {
                    throw invalidRequest();
                }
                return new NativeDesanitizeRequest(response, secureContext);
            } catch (RuntimeException | IOException | Error failure) {
                response.close();
                throw failure;
            }
        }

        private NativeBatchDesanitizeRequest readBatchDesanitize(int responseCapacity)
                throws IOException {
            ConfidentialSegment storage = ConfidentialSegment.allocate(responseCapacity);
            List<Span> spans = new ArrayList<>();
            try {
                whitespace();
                expect('{');
                boolean responsesSeen = false;
                String secureContext = null;
                while (true) {
                    whitespace();
                    if (peek() == '}') {
                        read();
                        break;
                    }
                    String field = readKey();
                    whitespace();
                    expect(':');
                    whitespace();
                    if ("modelResponses".equals(field) && !responsesSeen) {
                        readSegmentArray(storage, spans, 0);
                        responsesSeen = true;
                    } else if ("secureContext".equals(field)
                            && secureContext == null && peek() == '"') {
                        secureContext = readCapturedString(MAX_SECURE_CONTEXT_CHARS);
                    } else {
                        throw invalidRequest();
                    }
                    whitespace();
                    int delimiter = read();
                    if (delimiter == '}') {
                        break;
                    }
                    if (delimiter != ',') {
                        throw invalidRequest();
                    }
                }
                if (!responsesSeen || spans.isEmpty()
                        || secureContext == null || secureContext.isEmpty()) {
                    throw invalidRequest();
                }
                whitespace();
                if (read() != -1) {
                    throw invalidRequest();
                }
                long usedBytes = spans.get(spans.size() - 1).endOffset();
                storage.truncate(usedBytes);
                List<ConfidentialSegment> segments = new ArrayList<>(spans.size());
                for (Span span : spans) {
                    segments.add(storage.slice(span.offset(), span.length()));
                }
                return new NativeBatchDesanitizeRequest(
                        new NativePromptBatch(storage, segments), secureContext);
            } catch (RuntimeException | IOException | Error failure) {
                storage.close();
                throw failure;
            }
        }

        private NativePromptBatch readSegments(int storageCapacity) throws IOException {
            ConfidentialSegment storage = ConfidentialSegment.allocate(storageCapacity);
            List<Span> spans = new ArrayList<>();
            try {
                whitespace();
                expect('{');
                boolean segmentsSeen = false;
                int totalChars = 0;
                while (true) {
                    whitespace();
                    if (peek() == '}') {
                        read();
                        break;
                    }
                    String field = readKey();
                    whitespace();
                    expect(':');
                    whitespace();
                    if ("segments".equals(field) && !segmentsSeen) {
                        totalChars = readSegmentArray(storage, spans, totalChars);
                        segmentsSeen = true;
                    } else if ("tenantId".equals(field) && peek() == '"') {
                        skipString();
                    } else {
                        throw invalidRequest();
                    }
                    whitespace();
                    int delimiter = read();
                    if (delimiter == '}') {
                        break;
                    }
                    if (delimiter != ',') {
                        throw invalidRequest();
                    }
                }
                if (!segmentsSeen || spans.isEmpty()) {
                    throw invalidRequest();
                }
                whitespace();
                if (read() != -1) {
                    throw invalidRequest();
                }
                long usedBytes = spans.get(spans.size() - 1).endOffset();
                storage.truncate(usedBytes);
                List<ConfidentialSegment> segments = new ArrayList<>(spans.size());
                for (Span span : spans) {
                    segments.add(storage.slice(span.offset(), span.length()));
                }
                return new NativePromptBatch(storage, segments);
            } catch (RuntimeException | IOException | Error failure) {
                storage.close();
                throw failure;
            }
        }

        private int readSegmentArray(
                ConfidentialSegment storage, List<Span> spans, int totalChars)
                throws IOException {
            expect('[');
            while (true) {
                whitespace();
                if (peek() == ']') {
                    read();
                    return totalChars;
                }
                if (spans.size() == MAX_BATCH_SEGMENTS || peek() != '"') {
                    throw invalidRequest();
                }
                long start = spans.isEmpty()
                        ? 0
                        : spans.get(spans.size() - 1).endOffset();
                ParsedString parsed = readStringAt(
                        storage, start, false, false, MAX_PROMPT_CHARS);
                if (parsed.characters() > MAX_PROMPT_CHARS
                        || totalChars > MAX_BATCH_CHARS - parsed.characters()) {
                    throw new IllegalArgumentException(
                            "Text segments exceed the supported size.");
                }
                totalChars += parsed.characters();
                spans.add(new Span(start, parsed.byteLength()));
                whitespace();
                int delimiter = read();
                if (delimiter == ']') {
                    return totalChars;
                }
                if (delimiter != ',') {
                    throw invalidRequest();
                }
                whitespace();
                if (peek() != '"') {
                    throw invalidRequest();
                }
            }
        }

        private int readString(ConfidentialSegment target) throws IOException {
            return readStringAt(
                    target, 0, true, target == null, target == null ? 64 : MAX_PROMPT_CHARS)
                    .characters();
        }

        private void skipString() throws IOException {
            readStringAt(null, 0, true, false, maxJsonBytes);
        }

        private ParsedString readStringAt(
                ConfidentialSegment target,
                long startOffset,
                boolean truncate,
                boolean capture,
                int maximumCharacters)
                throws IOException {
            expect('"');
            StringBuilder key = capture ? new StringBuilder() : null;
            long writtenBytes = startOffset;
            long initialOffset = startOffset;
            int characters = 0;
            while (true) {
                int value = read();
                if (value < 0) {
                    throw invalidRequest();
                }
                if (value == '"') {
                    if (capture) {
                        returnString = key.toString();
                        return new ParsedString(characters, writtenBytes - initialOffset);
                    }
                    if (truncate && target != null) {
                        target.truncate(writtenBytes);
                    }
                    return new ParsedString(characters, writtenBytes - initialOffset);
                }

                int codePoint;
                if (value == '\\') {
                    int escape = read();
                    codePoint = switch (escape) {
                        case '"', '\\', '/' -> escape;
                        case 'b' -> '\b';
                        case 'f' -> '\f';
                        case 'n' -> '\n';
                        case 'r' -> '\r';
                        case 't' -> '\t';
                        case 'u' -> readEscapedCodePoint();
                        default -> throw invalidRequest();
                    };
                } else if (value < 0x20) {
                    throw invalidRequest();
                } else if (value < 0x80) {
                    codePoint = value;
                } else {
                    codePoint = readUtf8CodePoint(value);
                }

                int utf16Length = Character.charCount(codePoint);
                if (characters > maximumCharacters - utf16Length) {
                    throw invalidRequest();
                }
                characters += utf16Length;
                if (capture) {
                    if (codePoint > 0x7f) {
                        throw invalidRequest();
                    }
                    key.append((char) codePoint);
                } else if (target != null) {
                    try {
                        writtenBytes = writeUtf8(target, writtenBytes, codePoint);
                    } catch (IndexOutOfBoundsException capacityExceeded) {
                        throw invalidRequest();
                    }
                }
            }
        }

        private String readKey() throws IOException {
            String key = readCapturedString(64);
            return key;
        }

        private String readCapturedString(int maximumCharacters) throws IOException {
            readStringAt(null, 0, true, true, maximumCharacters);
            String key = returnString;
            returnString = null;
            return key;
        }

        private String returnString;

        private record ParsedString(int characters, long byteLength) {
        }

        private record Span(long offset, long length) {
            private long endOffset() {
                return offset + length;
            }
        }

        private int readEscapedCodePoint() throws IOException {
            int first = readHexCodeUnit();
            if (Character.isHighSurrogate((char) first)) {
                if (read() != '\\' || read() != 'u') {
                    throw invalidRequest();
                }
                int second = readHexCodeUnit();
                if (!Character.isLowSurrogate((char) second)) {
                    throw invalidRequest();
                }
                return Character.toCodePoint((char) first, (char) second);
            }
            if (Character.isLowSurrogate((char) first)) {
                throw invalidRequest();
            }
            return first;
        }

        private int readHexCodeUnit() throws IOException {
            int value = 0;
            for (int index = 0; index < 4; index++) {
                int digit = Character.digit((char) read(), 16);
                if (digit < 0) {
                    throw invalidRequest();
                }
                value = (value << 4) | digit;
            }
            return value;
        }

        private int readUtf8CodePoint(int first) throws IOException {
            int count;
            int codePoint;
            if (first >= 0xC2 && first <= 0xDF) {
                count = 1;
                codePoint = first & 0x1F;
            } else if (first >= 0xE0 && first <= 0xEF) {
                count = 2;
                codePoint = first & 0x0F;
            } else if (first >= 0xF0 && first <= 0xF4) {
                count = 3;
                codePoint = first & 0x07;
            } else {
                throw invalidRequest();
            }
            for (int index = 0; index < count; index++) {
                int next = read();
                if ((next & 0xC0) != 0x80) {
                    throw invalidRequest();
                }
                codePoint = (codePoint << 6) | (next & 0x3F);
            }
            if ((count == 1 && codePoint < 0x80)
                    || (count == 2 && codePoint < 0x800)
                    || (count == 3 && codePoint < 0x10000)
                    || codePoint > Character.MAX_CODE_POINT
                    || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
                throw invalidRequest();
            }
            return codePoint;
        }

        private static long writeUtf8(
                ConfidentialSegment target, long offset, int codePoint) {
            if (codePoint <= 0x7F) {
                target.writeByte(offset, (byte) codePoint);
                return offset + 1;
            }
            if (codePoint <= 0x7FF) {
                target.writeByte(offset++, (byte) (0xC0 | (codePoint >>> 6)));
                target.writeByte(offset++, (byte) (0x80 | (codePoint & 0x3F)));
                return offset;
            }
            if (codePoint <= 0xFFFF) {
                target.writeByte(offset++, (byte) (0xE0 | (codePoint >>> 12)));
                target.writeByte(offset++, (byte) (0x80 | ((codePoint >>> 6) & 0x3F)));
                target.writeByte(offset++, (byte) (0x80 | (codePoint & 0x3F)));
                return offset;
            }
            target.writeByte(offset++, (byte) (0xF0 | (codePoint >>> 18)));
            target.writeByte(offset++, (byte) (0x80 | ((codePoint >>> 12) & 0x3F)));
            target.writeByte(offset++, (byte) (0x80 | ((codePoint >>> 6) & 0x3F)));
            target.writeByte(offset++, (byte) (0x80 | (codePoint & 0x3F)));
            return offset;
        }

        private int peek() throws IOException {
            if (pending == -2) {
                pending = readUnderlying();
            }
            return pending;
        }

        private int read() throws IOException {
            int value;
            if (pending != -2) {
                value = pending;
                pending = -2;
            } else {
                value = readUnderlying();
            }
            return value;
        }

        private int readUnderlying() throws IOException {
            int value = input.read();
            if (value >= 0 && ++bytesRead > maxJsonBytes) {
                throw new IllegalArgumentException("Request body exceeds the supported size.");
            }
            return value;
        }

        private void expect(int expected) throws IOException {
            if (read() != expected) {
                throw invalidRequest();
            }
        }

        private void whitespace() throws IOException {
            while (true) {
                int value = peek();
                if (value != ' ' && value != '\t' && value != '\r' && value != '\n') {
                    return;
                }
                read();
            }
        }

        private static IllegalArgumentException invalidRequest() {
            return new NativePromptFormatException();
        }
    }
}
