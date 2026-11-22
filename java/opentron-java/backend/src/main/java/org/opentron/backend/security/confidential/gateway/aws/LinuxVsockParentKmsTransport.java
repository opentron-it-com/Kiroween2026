package org.opentron.backend.security.confidential.gateway.aws;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Sends a bounded, length-prefixed JSON KMS Decrypt request to a parent vsock
 * proxy. The proxy is responsible for AWS credentials and KMS HTTPS access.
 */
public final class LinuxVsockParentKmsTransport implements ParentVsockKmsTransport {
    private static final int AF_VSOCK = 40;
    private static final int SOCK_STREAM = 1;
    private static final int SOL_SOCKET = 1;
    private static final int SO_RCVTIMEO = 20;
    private static final int SO_SNDTIMEO = 21;
    private static final long IO_TIMEOUT_SECONDS = 30;
    private static final int HEADER_SIZE = Integer.BYTES;
    private static final int MAX_FRAME_BYTES =
            AwsNitroKmsDecryptRequest.MAX_REQUEST_BYTES + 16 * 1024;
    private static final Linker LINKER = Linker.nativeLinker();
    private static final FunctionDescriptor SOCKET_DESCRIPTOR = FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT);
    private static final FunctionDescriptor CONNECT_DESCRIPTOR = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT);
    private static final FunctionDescriptor SOCKET_OPTION_DESCRIPTOR = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT);
    private static final FunctionDescriptor IO_DESCRIPTOR = FunctionDescriptor.of(
            ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG);
    private static final FunctionDescriptor CLOSE_DESCRIPTOR = FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT);

    private final int parentCid;
    private final int proxyPort;
    private final ObjectMapper objectMapper;
    private volatile NativeMethods nativeMethods;

    public LinuxVsockParentKmsTransport(
            int parentCid, int proxyPort, ObjectMapper objectMapper) {
        if (parentCid < 0 || parentCid == 0xffff_ffff || proxyPort < 1 || proxyPort > 65535) {
            throw new IllegalArgumentException("Parent vsock CID or proxy port is invalid.");
        }
        this.parentCid = parentCid;
        this.proxyPort = proxyPort;
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public AwsNitroKmsDecryptResponse decrypt(AwsNitroKmsDecryptRequest request) {
        Objects.requireNonNull(request, "request");
        if (!System.getProperty("os.name", "").toLowerCase().contains("linux")) {
            throw new AwsNitroEnclaveException(
                    "AWS Nitro parent-vsock transport is supported only on Linux.");
        }
        NativeMethods methods = nativeMethods();

        byte[] requestFrame = null;
        byte[] responseFrame = null;
        try {
            requestFrame = encodeRequest(request);
            try (Arena arena = Arena.ofConfined()) {
                int fd = openSocket(methods);
                try {
                    configureTimeouts(arena, methods, fd);
                    connectToParent(arena, methods, fd);
                    writeFrame(arena, methods, fd, requestFrame);
                    responseFrame = readFrame(arena, methods, fd);
                } finally {
                    closeSocket(methods, fd);
                }
            }
            return decodeResponse(responseFrame);
        } catch (AwsNitroEnclaveException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new AwsNitroEnclaveException(
                    "AWS KMS parent-vsock exchange failed.", exception);
        } finally {
            clear(requestFrame);
            clear(responseFrame);
        }
    }

    byte[] encodeRequest(AwsNitroKmsDecryptRequest request) throws IOException {
        byte[] ciphertext = request.ciphertextBlob();
        byte[] attestationDocument = request.recipient().attestationDocument();
        try {
            return objectMapper.writeValueAsBytes(new ProxyRequest(
                    1,
                    "kms:Decrypt",
                    new KmsRequest(
                            request.keyId(),
                            Base64.getEncoder().encodeToString(ciphertext),
                            new Recipient(
                                    Base64.getEncoder().encodeToString(attestationDocument),
                                    request.recipient().keyEncryptionAlgorithm()))));
        } finally {
            clear(ciphertext);
            clear(attestationDocument);
        }
    }

    AwsNitroKmsDecryptResponse decodeResponse(byte[] response) throws IOException {
        JsonNode root = objectMapper.readTree(response);
        if (root.path("version").asInt(-1) != 1) {
            throw new AwsNitroEnclaveException("Parent-vsock proxy protocol version is invalid.");
        }
        JsonNode responseNode = root.path("response");
        String encoded = responseNode.path("CiphertextForRecipient").asText(null);
        if (encoded == null || encoded.isBlank()) {
            throw new AwsNitroEnclaveException(
                    "Parent-vsock KMS response omitted CiphertextForRecipient.");
        }
        byte[] ciphertext;
        try {
            ciphertext = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException exception) {
            throw new AwsNitroEnclaveException(
                    "Parent-vsock KMS response contains invalid base64.", exception);
        }
        try {
            return new AwsNitroKmsDecryptResponse(ciphertext);
        } finally {
            clear(ciphertext);
        }
    }

    private int openSocket(NativeMethods methods) {
        try {
            int fd = (int) methods.socket().invokeExact(AF_VSOCK, SOCK_STREAM, 0);
            if (fd < 0) {
                throw new AwsNitroEnclaveException("Unable to open parent-vsock connection.");
            }
            return fd;
        } catch (AwsNitroEnclaveException exception) {
            throw exception;
        } catch (Throwable failure) {
            throw nativeFailure("Unable to open parent-vsock connection.", failure);
        }
    }

    private void configureTimeouts(Arena arena, NativeMethods methods, int fd) {
        MemorySegment timeout = arena.allocate(2L * Long.BYTES, Long.BYTES);
        timeout.set(ValueLayout.JAVA_LONG, 0, IO_TIMEOUT_SECONDS);
        timeout.set(ValueLayout.JAVA_LONG, Long.BYTES, 0L);
        setSocketOption(methods, fd, SO_RCVTIMEO, timeout);
        setSocketOption(methods, fd, SO_SNDTIMEO, timeout);
    }

    private void setSocketOption(NativeMethods methods, int fd, int option, MemorySegment value) {
        try {
            int result = (int) methods.setSocketOption().invokeExact(
                    fd, SOL_SOCKET, option, value, (int) (2L * Long.BYTES));
            if (result != 0) {
                throw new AwsNitroEnclaveException("Unable to configure vsock I/O timeout.");
            }
        } catch (AwsNitroEnclaveException exception) {
            throw exception;
        } catch (Throwable failure) {
            throw nativeFailure("Unable to configure vsock I/O timeout.", failure);
        }
    }

    private void connectToParent(Arena arena, NativeMethods methods, int fd) {
        MemorySegment address = arena.allocate(16, 8);
        address.fill((byte) 0);
        address.set(ValueLayout.JAVA_SHORT, 0, (short) AF_VSOCK);
        address.set(ValueLayout.JAVA_INT, 4, proxyPort);
        address.set(ValueLayout.JAVA_INT, 8, parentCid);
        try {
            int result = (int) methods.connect().invokeExact(fd, address, 16);
            if (result != 0) {
                throw new AwsNitroEnclaveException("Unable to connect to parent vsock proxy.");
            }
        } catch (AwsNitroEnclaveException exception) {
            throw exception;
        } catch (Throwable failure) {
            throw nativeFailure("Unable to connect to parent vsock proxy.", failure);
        }
    }

    private void writeFrame(Arena arena, NativeMethods methods, int fd, byte[] payload) {
        byte[] header = ByteBuffer.allocate(HEADER_SIZE)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(payload.length)
                .array();
        MemorySegment nativeHeader = copyToNative(arena, header);
        MemorySegment nativePayload = copyToNative(arena, payload);
        try {
            writeFully(methods, fd, nativeHeader);
            writeFully(methods, fd, nativePayload);
        } finally {
            nativeHeader.fill((byte) 0);
            nativePayload.fill((byte) 0);
            clear(header);
        }
    }

    static MemorySegment copyToNative(Arena arena, byte[] bytes) {
        MemorySegment nativeBuffer = arena.allocate(bytes.length, 1);
        return nativeBuffer.copyFrom(MemorySegment.ofArray(bytes));
    }

    private byte[] readFrame(Arena arena, NativeMethods methods, int fd) {
        MemorySegment header = arena.allocate(HEADER_SIZE, 1);
        readFully(methods, fd, header);
        byte[] headerBytes = header.toArray(ValueLayout.JAVA_BYTE);
        int length;
        try {
            length = ByteBuffer.wrap(headerBytes).order(ByteOrder.BIG_ENDIAN).getInt();
        } finally {
            clear(headerBytes);
            header.fill((byte) 0);
        }
        if (length < 1 || length > MAX_FRAME_BYTES) {
            throw new AwsNitroEnclaveException("Parent-vsock response frame exceeds its size bound.");
        }
        MemorySegment body = arena.allocate(length, 1);
        readFully(methods, fd, body);
        return body.toArray(ValueLayout.JAVA_BYTE);
    }

    private void writeFully(NativeMethods methods, int fd, MemorySegment buffer) {
        long offset = 0;
        while (offset < buffer.byteSize()) {
            try {
                long written = (long) methods.write().invokeExact(
                        fd, buffer.asSlice(offset), buffer.byteSize() - offset);
                if (written <= 0) {
                    throw new AwsNitroEnclaveException("Parent-vsock write did not complete.");
                }
                offset += written;
            } catch (AwsNitroEnclaveException exception) {
                throw exception;
            } catch (Throwable failure) {
                throw nativeFailure("Parent-vsock write failed.", failure);
            }
        }
    }

    private void readFully(NativeMethods methods, int fd, MemorySegment buffer) {
        long offset = 0;
        while (offset < buffer.byteSize()) {
            try {
                long received = (long) methods.read().invokeExact(
                        fd, buffer.asSlice(offset), buffer.byteSize() - offset);
                if (received <= 0) {
                    throw new AwsNitroEnclaveException(
                            "Parent-vsock closed before the full response was received.");
                }
                offset += received;
            } catch (AwsNitroEnclaveException exception) {
                throw exception;
            } catch (Throwable failure) {
                throw nativeFailure("Parent-vsock read failed.", failure);
            }
        }
    }

    private void closeSocket(NativeMethods methods, int fd) {
        try {
            int result = (int) methods.close().invokeExact(fd);
            if (result != 0) {
                throw new AwsNitroEnclaveException("Unable to close parent-vsock socket.");
            }
        } catch (AwsNitroEnclaveException exception) {
            throw exception;
        } catch (Throwable failure) {
            throw nativeFailure("Unable to close parent-vsock socket.", failure);
        }
    }

    private static MethodHandle downcall(
            SymbolLookup symbols, String name, FunctionDescriptor descriptor) {
        MemorySegment address = symbols.find(name)
                .orElseThrow(() -> new UnsatisfiedLinkError(
                        "Linux libc symbol is unavailable: " + name));
        return LINKER.downcallHandle(address, descriptor);
    }

    private NativeMethods nativeMethods() {
        NativeMethods methods = nativeMethods;
        if (methods != null) {
            return methods;
        }
        synchronized (this) {
            methods = nativeMethods;
            if (methods == null) {
                SymbolLookup libc = LINKER.defaultLookup();
                methods = new NativeMethods(
                        downcall(libc, "socket", SOCKET_DESCRIPTOR),
                        downcall(libc, "connect", CONNECT_DESCRIPTOR),
                        downcall(libc, "setsockopt", SOCKET_OPTION_DESCRIPTOR),
                        downcall(libc, "read", IO_DESCRIPTOR),
                        downcall(libc, "write", IO_DESCRIPTOR),
                        downcall(libc, "close", CLOSE_DESCRIPTOR));
                nativeMethods = methods;
            }
        }
        return methods;
    }

    private static AwsNitroEnclaveException nativeFailure(String message, Throwable failure) {
        return new AwsNitroEnclaveException(message, failure);
    }

    private static void clear(byte[] bytes) {
        if (bytes != null) {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private record ProxyRequest(int version, String operation, KmsRequest request) {
    }

    private record KmsRequest(String KeyId, String CiphertextBlob, Recipient Recipient) {
    }

    private record Recipient(String AttestationDocument, String KeyEncryptionAlgorithm) {
    }

    private record NativeMethods(
            MethodHandle socket,
            MethodHandle connect,
            MethodHandle setSocketOption,
            MethodHandle read,
            MethodHandle write,
            MethodHandle close) {
    }
}
