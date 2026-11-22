package org.opentron.backend.security.confidential.gateway;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.opentron.backend.security.confidential.ConfidentialSegment;

public final class AesGcmSecureContextCodec {
    private static final String VERSION = "v3";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int MAX_CONTEXT_LENGTH = 2_000_000;
    private static final int MAX_MAPPING_COUNT = 10_000;
    private static final int MAX_TOKEN_BYTES = 512;
    private static final long HEADER_BYTES = Long.BYTES + Integer.BYTES;
    private static final long MAPPING_HEADER_BYTES = 2L * Integer.BYTES;
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final SecureContextKeyProvider keyProvider;
    private final SecureRandom secureRandom;
    private final Clock clock;

    public AesGcmSecureContextCodec(
            SecureContextKeyProvider keyProvider,
            ObjectMapper objectMapper,
            SecureRandom secureRandom,
            Clock clock) {
        this.keyProvider = Objects.requireNonNull(keyProvider, "keyProvider");
        Objects.requireNonNull(objectMapper, "objectMapper");
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public String sealNative(
            String contextBinding,
            Map<String, ConfidentialSegment> tokenMappings,
            Instant expiresAt) {
        validateContextBinding(contextBinding);
        if (tokenMappings == null || expiresAt == null || !expiresAt.isAfter(clock.instant())) {
            throw new IllegalArgumentException("Secure context parameters are invalid.");
        }
        if (tokenMappings.size() > MAX_MAPPING_COUNT) {
            throw new IllegalArgumentException("Secure context has too many mappings.");
        }

        long plaintextSize = HEADER_BYTES;
        for (Map.Entry<String, ConfidentialSegment> entry : tokenMappings.entrySet()) {
            String token = Objects.requireNonNull(entry.getKey(), "token");
            ConfidentialSegment value = Objects.requireNonNull(entry.getValue(), "value");
            long tokenLength = ConfidentialSegment.utf8Length(token);
            if (tokenLength == 0 || tokenLength > MAX_TOKEN_BYTES) {
                throw new IllegalArgumentException("Secure context token is invalid.");
            }
            long entrySize = MAPPING_HEADER_BYTES + tokenLength + value.byteSize();
            if (entrySize > MAX_CONTEXT_LENGTH - plaintextSize) {
                throw new IllegalArgumentException("Secure context exceeds the supported size.");
            }
            plaintextSize += entrySize;
        }

        byte[] nonce = new byte[NONCE_BYTES];
        try (ConfidentialSegment plaintext = ConfidentialSegment.allocate(plaintextSize);
                ConfidentialSegment ciphertext =
                        ConfidentialSegment.allocate(plaintextSize + TAG_BITS / Byte.SIZE)) {
            SecureContextKey key = keyProvider.currentKey();
            secureRandom.nextBytes(nonce);
            plaintext.writeLong(0, expiresAt.toEpochMilli());
            plaintext.writeInt(Long.BYTES, tokenMappings.size());
            long offset = HEADER_BYTES;
            for (Map.Entry<String, ConfidentialSegment> entry : tokenMappings.entrySet()) {
                byte[] tokenBytes = entry.getKey().getBytes(StandardCharsets.UTF_8);
                ConfidentialSegment value = entry.getValue();
                try {
                    plaintext.writeInt(offset, tokenBytes.length);
                    plaintext.writeInt(
                            offset + Integer.BYTES, Math.toIntExact(value.byteSize()));
                    offset += MAPPING_HEADER_BYTES;
                    writeBytes(plaintext, offset, tokenBytes);
                    offset += tokenBytes.length;
                    value.copyTo(plaintext, 0, offset, value.byteSize());
                    offset += value.byteSize();
                } finally {
                    Arrays.fill(tokenBytes, (byte) 0);
                }
            }

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key.key(), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(contextBinding, key.id()));
            ByteBuffer output = ciphertext.memory().asByteBuffer();
            int encryptedLength = cipher.doFinal(
                    plaintext.memory().asByteBuffer(), output);
            ciphertext.truncate(encryptedLength);
            byte[] encryptedBytes = ciphertext.memory().toArray(
                    java.lang.foreign.ValueLayout.JAVA_BYTE);
            try {
                String secureContext = String.join(
                        ".",
                        VERSION,
                        key.id(),
                        ENCODER.encodeToString(nonce),
                        ENCODER.encodeToString(encryptedBytes));
                if (secureContext.length() > MAX_CONTEXT_LENGTH) {
                    throw new IllegalArgumentException(
                            "Secure context exceeds the supported size.");
                }
                return secureContext;
            } finally {
                Arrays.fill(encryptedBytes, (byte) 0);
            }
        } catch (GeneralSecurityException | RuntimeException e) {
            if (e instanceof IllegalArgumentException argumentException) {
                throw argumentException;
            }
            throw new SecureContextException();
        } finally {
            Arrays.fill(nonce, (byte) 0);
        }
    }

    public SecureContextMappings openNative(String contextBinding, String secureContext) {
        validateContextBinding(contextBinding);
        ConfidentialSegment encryptedSegment = null;
        ConfidentialSegment plaintext = null;
        try {
            if (secureContext == null || secureContext.length() > MAX_CONTEXT_LENGTH) {
                throw new SecureContextException();
            }
            String[] parts = secureContext.split("\\.", -1);
            if (parts.length != 4 || !VERSION.equals(parts[0])) {
                throw new SecureContextException();
            }

            Optional<SecureContextKey> key = keyProvider.findKey(parts[1]);
            if (key.isEmpty()) {
                throw new SecureContextException();
            }

            byte[] nonce = DECODER.decode(parts[2]);
            byte[] ciphertext = DECODER.decode(parts[3]);
            try {
                if (nonce.length != NONCE_BYTES || ciphertext.length <= TAG_BITS / 8) {
                    throw new SecureContextException();
                }

                encryptedSegment = ConfidentialSegment.allocate(ciphertext.length);
                writeBytes(encryptedSegment, 0, ciphertext);
                plaintext = ConfidentialSegment.allocate(
                        encryptedSegment.byteSize() - TAG_BITS / Byte.SIZE);
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(
                        Cipher.DECRYPT_MODE,
                        key.get().key(),
                        new GCMParameterSpec(TAG_BITS, nonce));
                cipher.updateAAD(aad(contextBinding, parts[1]));
                int plaintextLength = cipher.doFinal(
                        encryptedSegment.memory().asByteBuffer(),
                        plaintext.memory().asByteBuffer());
                plaintext.truncate(plaintextLength);
                SecureContextMappings mappings = parseMappings(plaintext);
                plaintext = null;
                return mappings;
            } finally {
                Arrays.fill(nonce, (byte) 0);
                Arrays.fill(ciphertext, (byte) 0);
            }
        } catch (GeneralSecurityException | RuntimeException e) {
            throw new SecureContextException();
        } finally {
            if (encryptedSegment != null) {
                encryptedSegment.close();
            }
            if (plaintext != null) {
                plaintext.close();
            }
        }
    }

    private SecureContextMappings parseMappings(ConfidentialSegment plaintext) {
        if (plaintext.byteSize() < HEADER_BYTES) {
            throw new SecureContextException();
        }
        long expiresAtEpochMillis = plaintext.readLong(0);
        Instant expiresAt;
        try {
            expiresAt = Instant.ofEpochMilli(expiresAtEpochMillis);
        } catch (RuntimeException failure) {
            throw new SecureContextException();
        }
        int count = plaintext.readInt(Long.BYTES);
        if (count < 0 || count > MAX_MAPPING_COUNT) {
            throw new SecureContextException();
        }
        Map<String, ConfidentialSegment> mappings = new LinkedHashMap<>();
        long offset = HEADER_BYTES;
        try {
            for (int index = 0; index < count; index++) {
                requireRange(offset, MAPPING_HEADER_BYTES, plaintext.byteSize());
                int tokenLength = plaintext.readInt(offset);
                int valueLength = plaintext.readInt(offset + Integer.BYTES);
                offset += MAPPING_HEADER_BYTES;
                if (tokenLength < 1 || tokenLength > MAX_TOKEN_BYTES || valueLength < 0) {
                    throw new SecureContextException();
                }
                requireRange(offset, (long) tokenLength + valueLength, plaintext.byteSize());
                String token = readToken(plaintext, offset, tokenLength);
                offset += tokenLength;
                ConfidentialSegment value = plaintext.slice(offset, valueLength);
                if (mappings.putIfAbsent(token, value) != null) {
                    value.close();
                    throw new SecureContextException();
                }
                offset += valueLength;
            }
            if (offset != plaintext.byteSize()
                    || !expiresAt.isAfter(clock.instant())) {
                throw new SecureContextException();
            }
            return new SecureContextMappings(plaintext, mappings, expiresAt);
        } catch (RuntimeException failure) {
            closeMappings(mappings);
            throw failure;
        }
    }

    private static String readToken(
            ConfidentialSegment segment, long offset, int length) {
        byte[] bytes = new byte[length];
        try {
            for (int index = 0; index < length; index++) {
                bytes[index] = segment.readByte(offset + index);
            }
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException failure) {
            throw new SecureContextException();
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private static void writeBytes(
            ConfidentialSegment destination, long offset, byte[] bytes) {
        for (int index = 0; index < bytes.length; index++) {
            destination.writeByte(offset + index, bytes[index]);
        }
    }

    private static void requireRange(long offset, long length, long capacity) {
        if (offset < 0 || length < 0 || offset > capacity - length) {
            throw new SecureContextException();
        }
    }

    private static void closeMappings(Map<String, ConfidentialSegment> mappings) {
        RuntimeException failure = null;
        for (ConfidentialSegment segment : mappings.values()) {
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

    private static byte[] aad(String contextBinding, String keyId) {
        return (VERSION + "\u0000" + contextBinding + "\u0000" + keyId)
                .getBytes(StandardCharsets.UTF_8);
    }

    private static void validateContextBinding(String contextBinding) {
        if (contextBinding == null || contextBinding.isBlank()
                || contextBinding.length() > 600) {
            throw new IllegalArgumentException("Secure context binding is invalid.");
        }
    }

    public static final class SecureContextMappings implements AutoCloseable {
        private final ConfidentialSegment plaintext;
        private final Map<String, ConfidentialSegment> values;
        private final Instant expiresAt;
        private boolean closed;

        private SecureContextMappings(
                ConfidentialSegment plaintext,
                Map<String, ConfidentialSegment> values,
                Instant expiresAt) {
            this.plaintext = plaintext;
            this.values = Map.copyOf(values);
            this.expiresAt = expiresAt;
        }

        public Map<String, ConfidentialSegment> values() {
            if (closed) {
                throw new IllegalStateException("Secure context mappings are closed.");
            }
            return values;
        }

        public Instant expiresAt() {
            if (closed) {
                throw new IllegalStateException("Secure context mappings are closed.");
            }
            return expiresAt;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                closeMappings(values);
            } finally {
                plaintext.close();
            }
        }
    }
}
