package org.opentron.backend.security.confidential.gateway;

import java.io.IOException;
import java.io.OutputStream;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.opentron.backend.security.confidential.ConfidentialSegment;

public final class ConfidentialGatewayService {
    private static final String TOKEN_PREFIX = "<OJE_";
    private static final String TOKEN_SUFFIX = ">";
    private static final int TOKEN_RANDOM_BYTES = 16;
    private static final int MAX_TEXT_LENGTH = 1_000_000;
    private static final int MAX_BATCH_SEGMENTS = 100;
    private static final int MAX_BATCH_TEXT_LENGTH = 5_000_000;
    private static final int MAX_ENTITIES = 10_000;

    private final PiiDetector piiDetector;
    private final AesGcmSecureContextCodec contextCodec;
    private final Duration contextLifetime;
    private final SecureRandom secureRandom;
    private final Clock clock;
    private final ConfidentialOperationGovernor operationGovernor;
    private final SecureContextConsumptionStore contextConsumptionStore;

    public ConfidentialGatewayService(
            PiiDetector piiDetector,
            AesGcmSecureContextCodec contextCodec,
            Duration contextLifetime,
            SecureRandom secureRandom,
            Clock clock) {
        this(piiDetector, contextCodec, contextLifetime, secureRandom, clock,
                new ConfidentialOperationGovernor(64), (context, expiresAt) -> true);
    }

    public ConfidentialGatewayService(
            PiiDetector piiDetector,
            AesGcmSecureContextCodec contextCodec,
            Duration contextLifetime,
            SecureRandom secureRandom,
            Clock clock,
            ConfidentialOperationGovernor operationGovernor) {
        this(piiDetector, contextCodec, contextLifetime, secureRandom, clock,
                operationGovernor, (context, expiresAt) -> true);
    }

    public ConfidentialGatewayService(
            PiiDetector piiDetector,
            AesGcmSecureContextCodec contextCodec,
            Duration contextLifetime,
            SecureRandom secureRandom,
            Clock clock,
            ConfidentialOperationGovernor operationGovernor,
            SecureContextConsumptionStore contextConsumptionStore) {
        if (contextLifetime == null || contextLifetime.isZero() || contextLifetime.isNegative()) {
            throw new IllegalArgumentException("Secure context lifetime must be positive.");
        }
        this.piiDetector = piiDetector;
        this.contextCodec = contextCodec;
        this.contextLifetime = contextLifetime;
        this.secureRandom = secureRandom;
        this.clock = clock;
        this.operationGovernor = Objects.requireNonNull(operationGovernor, "operationGovernor");
        this.contextConsumptionStore =
                Objects.requireNonNull(contextConsumptionStore, "contextConsumptionStore");
    }

    public SanitizeResult sanitize(String tenantId, String text) {
        if (text == null || text.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("Text is missing or exceeds the supported size.");
        }
        return operationGovernor.execute(() -> {
            try (ConfidentialSegment source = ConfidentialSegment.fromUtf8(text)) {
                return sanitizeNativeSegmentWithinPermit(tenantId, source);
            }
        });
    }

    public SanitizeResult sanitize(String tenantId, ConfidentialSegment source) {
        Objects.requireNonNull(source, "source");
        return operationGovernor.execute(
                () -> sanitizeNativeSegmentWithinPermit(tenantId, source));
    }

    public ConfidentialOperationGovernor.Permit acquireOperationPermit() {
        return operationGovernor.acquire();
    }

    public SanitizeResult sanitize(
            String tenantId,
            ConfidentialSegment source,
            ConfidentialOperationGovernor.Permit permit) {
        Objects.requireNonNull(source, "source");
        return operationGovernor.execute(permit,
                () -> sanitizeNativeSegmentWithinPermit(tenantId, source));
    }

    private SanitizeResult sanitizeNativeSegmentWithinPermit(
            String tenantId, ConfidentialSegment source) {
        try (NativeSecretMappings tokenMappings = new NativeSecretMappings()) {
            try (ConfidentialSegment sanitized = sanitizeToNative(source, tokenMappings)) {
                Instant expiresAt = clock.instant().plus(contextLifetime);
                String secureContext = contextCodec.sealNative(
                        tenantId, tokenMappings.values(), expiresAt);
                return new SanitizeResult(
                        sanitized.toUtf8String(), secureContext, piiDetector.version(), expiresAt);
            }
        }
    }

    public void writeSanitizeJson(
            String tenantId,
            ConfidentialSegment source,
            ConfidentialOperationGovernor.Permit permit,
            OutputStream output) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(output, "output");
        try {
            operationGovernor.execute(permit, () -> {
                try (NativeSecretMappings tokenMappings = new NativeSecretMappings();
                        ConfidentialSegment sanitized = sanitizeToNative(source, tokenMappings);
                        NativeJsonStringWriter writer = new NativeJsonStringWriter(output)) {
                    Instant expiresAt = clock.instant().plus(contextLifetime);
                    String secureContext = contextCodec.sealNative(
                            tenantId, tokenMappings.values(), expiresAt);
                    writeSanitizeResponseJson(
                            writer, sanitized, secureContext, piiDetector.version(), expiresAt);
                    return null;
                } catch (IOException exception) {
                    throw new GatewayResponseWriteException(exception);
                }
            });
        } catch (GatewayResponseWriteException exception) {
            throw exception.ioException();
        }
    }

    public void writeSanitizeBatchJson(
            String tenantId,
            List<ConfidentialSegment> sources,
            ConfidentialOperationGovernor.Permit permit,
            OutputStream output) throws IOException {
        if (sources == null || sources.isEmpty() || sources.size() > MAX_BATCH_SEGMENTS) {
            throw new IllegalArgumentException(
                    "Request must contain between 1 and 100 text segments.");
        }
        Objects.requireNonNull(output, "output");
        try {
            operationGovernor.execute(permit, () -> {
                try (NativeSecretMappings tokenMappings = new NativeSecretMappings();
                        NativeJsonStringWriter writer = new NativeJsonStringWriter(output)) {
                    List<ConfidentialSegment> sanitized = new ArrayList<>(sources.size());
                    long totalCharacters = 0;
                    try {
                        for (ConfidentialSegment source : sources) {
                            NativeUtf8CharSequence text = new NativeUtf8CharSequence(
                                    Objects.requireNonNull(source, "source"));
                            totalCharacters += text.length();
                            if (text.length() > MAX_TEXT_LENGTH
                                    || totalCharacters > MAX_BATCH_TEXT_LENGTH) {
                                throw new IllegalArgumentException(
                                        "Text segments exceed the supported size.");
                            }
                            sanitized.add(sanitizeToNative(source, tokenMappings));
                        }
                        Instant expiresAt = clock.instant().plus(contextLifetime);
                        String secureContext = contextCodec.sealNative(
                                tenantId, tokenMappings.values(), expiresAt);
                        writer.writeAscii("{\"sanitizedSegments\":[");
                        for (int index = 0; index < sanitized.size(); index++) {
                            if (index > 0) {
                                writer.writeAscii(",");
                            }
                            writer.writeJsonString(new NativeUtf8CharSequence(
                                    sanitized.get(index)));
                        }
                        writer.writeAscii("],\"secureContext\":");
                        writer.writeJsonString(secureContext);
                        writer.writeAscii(",\"detectorVersion\":");
                        writer.writeJsonString(piiDetector.version());
                        writer.writeAscii(",\"expiresAt\":");
                        writer.writeJsonString(expiresAt.toString());
                        writer.writeAscii("}");
                        return null;
                    } finally {
                        closeSegments(sanitized);
                    }
                } catch (IOException exception) {
                    throw new GatewayResponseWriteException(exception);
                }
            });
        } catch (GatewayResponseWriteException exception) {
            throw exception.ioException();
        }
    }

    public BatchSanitizeResult sanitizeSegments(String tenantId, List<String> segments) {
        validateSegments(segments);
        return operationGovernor.execute(() -> sanitizeSegmentsWithinPermit(tenantId, segments));
    }

    public BatchSanitizeResult sanitizeNativeSegments(
            String tenantId, List<ConfidentialSegment> segments) {
        if (segments == null || segments.isEmpty() || segments.size() > MAX_BATCH_SEGMENTS) {
            throw new IllegalArgumentException(
                    "Request must contain between 1 and 100 text segments.");
        }
        return operationGovernor.execute(
                () -> sanitizeNativeSegmentsWithinPermit(tenantId, segments));
    }

    public BatchSanitizeResult sanitizeNativeSegments(
            String tenantId,
            List<ConfidentialSegment> segments,
            ConfidentialOperationGovernor.Permit permit) {
        if (segments == null || segments.isEmpty() || segments.size() > MAX_BATCH_SEGMENTS) {
            throw new IllegalArgumentException(
                    "Request must contain between 1 and 100 text segments.");
        }
        return operationGovernor.execute(permit,
                () -> sanitizeNativeSegmentsWithinPermit(tenantId, segments));
    }

    private BatchSanitizeResult sanitizeNativeSegmentsWithinPermit(
            String tenantId, List<ConfidentialSegment> segments) {
        try (NativeSecretMappings tokenMappings = new NativeSecretMappings()) {
            List<String> sanitizedSegments = new ArrayList<>(segments.size());
            long totalCharacters = 0;
            for (ConfidentialSegment segment : segments) {
                NativeUtf8CharSequence text = new NativeUtf8CharSequence(
                        Objects.requireNonNull(segment, "segment"));
                totalCharacters += text.length();
                if (text.length() > MAX_TEXT_LENGTH
                        || totalCharacters > MAX_BATCH_TEXT_LENGTH) {
                    throw new IllegalArgumentException(
                            "Text segments exceed the supported size.");
                }
                sanitizedSegments.add(sanitizeText(segment, tokenMappings));
            }
            Instant expiresAt = clock.instant().plus(contextLifetime);
            String secureContext = contextCodec.sealNative(
                    tenantId, tokenMappings.values(), expiresAt);
            return new BatchSanitizeResult(
                    List.copyOf(sanitizedSegments),
                    secureContext,
                    piiDetector.version(),
                    expiresAt);
        }
    }

    private BatchSanitizeResult sanitizeSegmentsWithinPermit(
            String tenantId, List<String> segments) {
        List<ConfidentialSegment> sources = new ArrayList<>(segments.size());
        try {
            try (NativeSecretMappings tokenMappings = new NativeSecretMappings()) {
                List<String> sanitizedSegments = new ArrayList<>(segments.size());
                for (String segment : segments) {
                    ConfidentialSegment source = ConfidentialSegment.fromUtf8(segment);
                    sources.add(source);
                    sanitizedSegments.add(sanitizeText(source, tokenMappings));
                }
                Instant expiresAt = clock.instant().plus(contextLifetime);
                String secureContext = contextCodec.sealNative(
                        tenantId, tokenMappings.values(), expiresAt);
                return new BatchSanitizeResult(
                        List.copyOf(sanitizedSegments),
                        secureContext,
                        piiDetector.version(),
                        expiresAt);
            }
        } finally {
            for (ConfidentialSegment source : sources) {
                source.close();
            }
        }
    }

    public String desanitize(String tenantId, String modelResponse, String secureContext) {
        if (modelResponse == null || modelResponse.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("Model response is missing or exceeds the supported size.");
        }
        return operationGovernor.execute(() -> {
            try (ConfidentialSegment response =
                    ConfidentialSegment.fromUtf8(modelResponse)) {
                return desanitizeNativeWithinPermit(tenantId, response, secureContext);
            }
        });
    }

    public String desanitize(
            String tenantId,
            ConfidentialSegment modelResponse,
            String secureContext,
            ConfidentialOperationGovernor.Permit permit) {
        Objects.requireNonNull(modelResponse, "modelResponse");
        return operationGovernor.execute(permit,
                () -> desanitizeNativeWithinPermit(tenantId, modelResponse, secureContext));
    }

    private String desanitizeNativeWithinPermit(
            String tenantId, ConfidentialSegment modelResponse, String secureContext) {
        NativeUtf8CharSequence response = new NativeUtf8CharSequence(modelResponse);
        if (response.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException(
                    "Model response exceeds the supported size.");
        }
        try (AesGcmSecureContextCodec.SecureContextMappings tokenMappings =
                contextCodec.openNative(tenantId, secureContext)) {
            consumeSecureContext(secureContext, tokenMappings.expiresAt());
            return restoreText(modelResponse, tokenMappings.values());
        }
    }

    public void writeDesanitizeJson(
            String tenantId,
            ConfidentialSegment modelResponse,
            String secureContext,
            ConfidentialOperationGovernor.Permit permit,
            OutputStream output) throws IOException {
        Objects.requireNonNull(modelResponse, "modelResponse");
        Objects.requireNonNull(output, "output");
        try {
            operationGovernor.execute(permit, () -> {
                NativeUtf8CharSequence response = new NativeUtf8CharSequence(modelResponse);
                if (response.length() > MAX_TEXT_LENGTH) {
                    throw new IllegalArgumentException(
                            "Model response exceeds the supported size.");
                }
                try (AesGcmSecureContextCodec.SecureContextMappings tokenMappings =
                                contextCodec.openNative(tenantId, secureContext);
                        NativeJsonStringWriter writer = new NativeJsonStringWriter(output)) {
                    consumeSecureContext(secureContext, tokenMappings.expiresAt());
                    writer.writeAscii("{\"desanitizedText\":");
                    writeRestoredJsonString(writer, response, tokenMappings.values());
                    writer.writeAscii("}");
                    return null;
                } catch (IOException exception) {
                    throw new GatewayResponseWriteException(exception);
                }
            });
        } catch (GatewayResponseWriteException exception) {
            throw exception.ioException();
        }
    }

    public List<String> desanitizeSegments(
            String tenantId, List<String> modelResponses, String secureContext) {
        validateSegments(modelResponses);
        return operationGovernor.execute(() -> {
            try (AesGcmSecureContextCodec.SecureContextMappings tokenMappings =
                    contextCodec.openNative(tenantId, secureContext)) {
                consumeSecureContext(secureContext, tokenMappings.expiresAt());
                List<String> restored = new ArrayList<>(modelResponses.size());
                for (String response : modelResponses) {
                    try (ConfidentialSegment nativeResponse =
                            ConfidentialSegment.fromUtf8(response)) {
                        restored.add(restoreText(nativeResponse, tokenMappings.values()));
                    }
                }
                return List.copyOf(restored);
            }
        });
    }

    public List<String> desanitizeNativeSegments(
            String tenantId,
            List<ConfidentialSegment> modelResponses,
            String secureContext,
            ConfidentialOperationGovernor.Permit permit) {
        if (modelResponses == null || modelResponses.isEmpty()
                || modelResponses.size() > MAX_BATCH_SEGMENTS) {
            throw new IllegalArgumentException(
                    "Request must contain between 1 and 100 text segments.");
        }
        return operationGovernor.execute(permit, () -> {
            long totalCharacters = 0;
            for (ConfidentialSegment response : modelResponses) {
                int length = new NativeUtf8CharSequence(
                        Objects.requireNonNull(response, "modelResponse")).length();
                totalCharacters += length;
                if (length > MAX_TEXT_LENGTH || totalCharacters > MAX_BATCH_TEXT_LENGTH) {
                    throw new IllegalArgumentException(
                            "Model responses exceed the supported size.");
                }
            }
            try (AesGcmSecureContextCodec.SecureContextMappings tokenMappings =
                    contextCodec.openNative(tenantId, secureContext)) {
                consumeSecureContext(secureContext, tokenMappings.expiresAt());
                List<String> restored = new ArrayList<>(modelResponses.size());
                for (ConfidentialSegment response : modelResponses) {
                    restored.add(restoreText(response, tokenMappings.values()));
                }
                return List.copyOf(restored);
            }
        });
    }

    public void writeDesanitizeBatchJson(
            String tenantId,
            List<ConfidentialSegment> modelResponses,
            String secureContext,
            ConfidentialOperationGovernor.Permit permit,
            OutputStream output) throws IOException {
        if (modelResponses == null || modelResponses.isEmpty()
                || modelResponses.size() > MAX_BATCH_SEGMENTS) {
            throw new IllegalArgumentException(
                    "Request must contain between 1 and 100 text segments.");
        }
        Objects.requireNonNull(output, "output");
        try {
            operationGovernor.execute(permit, () -> {
                long totalCharacters = 0;
                List<NativeUtf8CharSequence> responses =
                        new ArrayList<>(modelResponses.size());
                for (ConfidentialSegment response : modelResponses) {
                    NativeUtf8CharSequence text = new NativeUtf8CharSequence(
                            Objects.requireNonNull(response, "modelResponse"));
                    totalCharacters += text.length();
                    if (text.length() > MAX_TEXT_LENGTH
                            || totalCharacters > MAX_BATCH_TEXT_LENGTH) {
                        throw new IllegalArgumentException(
                                "Model responses exceed the supported size.");
                    }
                    responses.add(text);
                }
                try (AesGcmSecureContextCodec.SecureContextMappings tokenMappings =
                                contextCodec.openNative(tenantId, secureContext);
                        NativeJsonStringWriter writer = new NativeJsonStringWriter(output)) {
                    consumeSecureContext(secureContext, tokenMappings.expiresAt());
                    writer.writeAscii("{\"desanitizedResponses\":[");
                    for (int index = 0; index < responses.size(); index++) {
                        if (index > 0) {
                            writer.writeAscii(",");
                        }
                        writeRestoredJsonString(
                                writer, responses.get(index), tokenMappings.values());
                    }
                    writer.writeAscii("]}");
                    return null;
                } catch (IOException exception) {
                    throw new GatewayResponseWriteException(exception);
                }
            });
        } catch (GatewayResponseWriteException exception) {
            throw exception.ioException();
        }
    }

    private String sanitizeText(
            ConfidentialSegment source, NativeSecretMappings tokenMappings) {
        try (ConfidentialSegment sanitized = sanitizeToNative(source, tokenMappings)) {
            return sanitized.toUtf8String();
        }
    }

    private void consumeSecureContext(String secureContext, Instant expiresAt) {
        if (!contextConsumptionStore.consume(secureContext, expiresAt)) {
            throw new SecureContextException();
        }
    }

    private ConfidentialSegment sanitizeToNative(
            ConfidentialSegment source, NativeSecretMappings tokenMappings) {
        NativeUtf8CharSequence text = new NativeUtf8CharSequence(source);
        if (text.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("Text is missing or exceeds the supported size.");
        }
        List<DetectedEntity> entities = piiDetector.detect(text);
        validateEntities(text, entities);
        if (tokenMappings.size() + entities.size() > MAX_ENTITIES) {
            throw new IllegalArgumentException("Request contains too many supported entities.");
        }

        int[] utf8Offsets = utf8Offsets(text);
        List<TokenizedEntity> replacements = new ArrayList<>(entities.size());
        long sanitizedSize = source.byteSize();
        for (DetectedEntity entity : entities) {
            if (splitsSurrogatePair(text, entity.start())
                    || splitsSurrogatePair(text, entity.end())) {
                throw new IllegalStateException(
                        "PII detector returned an entity boundary inside a surrogate pair.");
            }
            String token;
            do {
                token = newToken(entity.type());
            } while (contains(text, token) || tokenMappings.containsToken(token));
            tokenMappings.put(token, source.slice(
                    utf8Offsets[entity.start()],
                    utf8Offsets[entity.end()] - utf8Offsets[entity.start()]));
            long tokenByteLength = ConfidentialSegment.utf8Length(token);
            replacements.add(new TokenizedEntity(
                    entity.start(), entity.end(), token, utf8Offsets[entity.start()],
                    utf8Offsets[entity.end()], tokenByteLength));
            sanitizedSize += tokenByteLength
                    - (long) (utf8Offsets[entity.end()] - utf8Offsets[entity.start()]);
        }

        ConfidentialSegment sanitized = ConfidentialSegment.allocate(sanitizedSize);
        try {
            long sourceOffset = 0;
            long targetOffset = 0;
            for (TokenizedEntity replacement : replacements) {
                long unchangedLength = replacement.utf8Start() - sourceOffset;
                source.copyTo(sanitized, sourceOffset, targetOffset, unchangedLength);
                targetOffset += unchangedLength;
                sanitized.writeUtf8(replacement.token(), targetOffset);
                targetOffset += replacement.tokenByteLength();
                sourceOffset = replacement.utf8End();
            }
            source.copyTo(
                    sanitized, sourceOffset, targetOffset, source.byteSize() - sourceOffset);
            return sanitized;
        } catch (RuntimeException | Error failure) {
            sanitized.close();
            throw failure;
        }
    }

    private static void writeSanitizeResponseJson(
            NativeJsonStringWriter writer,
            ConfidentialSegment sanitized,
            String secureContext,
            String detectorVersion,
            Instant expiresAt) throws IOException {
        writer.writeAscii("{\"sanitizedText\":");
        writer.writeJsonString(new NativeUtf8CharSequence(sanitized));
        writer.writeAscii(",\"secureContext\":");
        writer.writeJsonString(secureContext);
        writer.writeAscii(",\"detectorVersion\":");
        writer.writeJsonString(detectorVersion);
        writer.writeAscii(",\"expiresAt\":");
        writer.writeJsonString(expiresAt.toString());
        writer.writeAscii("}");
    }

    private static void closeSegments(List<ConfidentialSegment> segments) {
        RuntimeException failure = null;
        for (ConfidentialSegment segment : segments) {
            try {
                segment.close();
            } catch (RuntimeException closeFailure) {
                if (failure == null) {
                    failure = closeFailure;
                } else {
                    failure.addSuppressed(closeFailure);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static int[] utf8Offsets(CharSequence text) {
        int[] offsets = new int[text.length() + 1];
        int charOffset = 0;
        int byteOffset = 0;
        while (charOffset < text.length()) {
            char current = text.charAt(charOffset);
            if (Character.isHighSurrogate(current)
                    && charOffset + 1 < text.length()
                    && Character.isLowSurrogate(text.charAt(charOffset + 1))) {
                offsets[charOffset + 1] = byteOffset;
                byteOffset += 4;
                charOffset += 2;
                offsets[charOffset] = byteOffset;
            } else {
                byteOffset += Character.isSurrogate(current)
                        ? 1
                        : current <= 0x7f ? 1 : current <= 0x7ff ? 2 : 3;
                offsets[++charOffset] = byteOffset;
            }
        }
        return offsets;
    }

    private static boolean splitsSurrogatePair(CharSequence text, int charOffset) {
        return charOffset > 0
                && charOffset < text.length()
                && Character.isHighSurrogate(text.charAt(charOffset - 1))
                && Character.isLowSurrogate(text.charAt(charOffset));
    }

    private static String restoreText(
            ConfidentialSegment response, Map<String, ConfidentialSegment> tokenMappings) {
        CharSequence text = new NativeUtf8CharSequence(response);
        StringBuilder restored = new StringBuilder(text.length());
        int cursor = 0;
        while (cursor < text.length()) {
            int tokenStart = indexOf(text, TOKEN_PREFIX, cursor);
            if (tokenStart < 0) {
                append(restored, text, cursor, text.length());
                break;
            }
            int tokenEnd = indexOf(text, TOKEN_SUFFIX, tokenStart + TOKEN_PREFIX.length());
            if (tokenEnd < 0) {
                append(restored, text, cursor, text.length());
                break;
            }

            append(restored, text, cursor, tokenStart);
            String token = text.subSequence(tokenStart, tokenEnd + TOKEN_SUFFIX.length()).toString();
            ConfidentialSegment value = tokenMappings.get(token);
            restored.append(value == null ? token : new NativeUtf8CharSequence(value));
            cursor = tokenEnd + TOKEN_SUFFIX.length();
        }
        return restored.toString();
    }

    private static void writeRestoredJsonString(
            NativeJsonStringWriter writer,
            CharSequence text,
            Map<String, ConfidentialSegment> tokenMappings) throws IOException {
        writer.writeAscii("\"");
        int cursor = 0;
        while (cursor < text.length()) {
            int tokenStart = indexOf(text, TOKEN_PREFIX, cursor);
            if (tokenStart < 0) {
                writer.writeJsonStringContent(text.subSequence(cursor, text.length()));
                break;
            }
            int tokenEnd = indexOf(text, TOKEN_SUFFIX, tokenStart + TOKEN_PREFIX.length());
            if (tokenEnd < 0) {
                writer.writeJsonStringContent(text.subSequence(cursor, text.length()));
                break;
            }
            writer.writeJsonStringContent(text.subSequence(cursor, tokenStart));
            String token = text.subSequence(
                    tokenStart, tokenEnd + TOKEN_SUFFIX.length()).toString();
            ConfidentialSegment value = tokenMappings.get(token);
            if (value == null) {
                writer.writeJsonStringContent(text.subSequence(
                        tokenStart, tokenEnd + TOKEN_SUFFIX.length()));
            } else {
                writer.writeJsonStringContent(new NativeUtf8CharSequence(value));
            }
            cursor = tokenEnd + TOKEN_SUFFIX.length();
        }
        writer.writeAscii("\"");
    }

    private static int indexOf(CharSequence text, String sought, int start) {
        int lastStart = text.length() - sought.length();
        for (int offset = start; offset <= lastStart; offset++) {
            int index = 0;
            while (index < sought.length()
                    && text.charAt(offset + index) == sought.charAt(index)) {
                index++;
            }
            if (index == sought.length()) {
                return offset;
            }
        }
        return -1;
    }

    private static void append(StringBuilder target, CharSequence source, int start, int end) {
        for (int index = start; index < end; index++) {
            target.append(source.charAt(index));
        }
    }

    private static void validateSegments(List<String> segments) {
        if (segments == null || segments.isEmpty() || segments.size() > MAX_BATCH_SEGMENTS) {
            throw new IllegalArgumentException(
                    "Request must contain between 1 and 100 text segments.");
        }
        long totalLength = 0;
        for (String segment : segments) {
            if (segment == null) {
                throw new IllegalArgumentException("Text segments must not be null.");
            }
            totalLength += segment.length();
            if (segment.length() > MAX_TEXT_LENGTH || totalLength > MAX_BATCH_TEXT_LENGTH) {
                throw new IllegalArgumentException("Text segments exceed the supported size.");
            }
        }
    }

    private String newToken(String type) {
        byte[] bytes = new byte[TOKEN_RANDOM_BYTES];
        secureRandom.nextBytes(bytes);
        return TOKEN_PREFIX + type + "_" + java.util.HexFormat.of().formatHex(bytes) + TOKEN_SUFFIX;
    }

    private static void validateEntities(CharSequence text, List<DetectedEntity> entities) {
        if (entities == null) {
            throw new IllegalStateException("PII detector returned no result.");
        }

        int previousEnd = 0;
        for (DetectedEntity entity : entities) {
            if (entity == null || entity.end() > text.length()
                    || entity.start() < previousEnd || entity.end() <= entity.start()) {
                throw new IllegalStateException("PII detector returned invalid entity offsets.");
            }
            previousEnd = entity.end();
        }
    }

    private static boolean contains(CharSequence text, String value) {
        int lastStart = text.length() - value.length();
        for (int start = 0; start <= lastStart; start++) {
            int index = 0;
            while (index < value.length() && text.charAt(start + index) == value.charAt(index)) {
                index++;
            }
            if (index == value.length()) {
                return true;
            }
        }
        return false;
    }

    public record SanitizeResult(
            String sanitizedText,
            String secureContext,
            String detectorVersion,
            Instant expiresAt) {
    }

    public record BatchSanitizeResult(
            List<String> sanitizedSegments,
            String secureContext,
            String detectorVersion,
            Instant expiresAt) {
    }

    private record TokenizedEntity(
            int start, int end, String token, int utf8Start, int utf8End, long tokenByteLength) {
    }

    private static final class GatewayResponseWriteException extends RuntimeException {
        private final IOException ioException;

        private GatewayResponseWriteException(IOException ioException) {
            super(ioException);
            this.ioException = ioException;
        }

        private IOException ioException() {
            return ioException;
        }
    }
}
