package org.opentron.backend.security.confidential.gateway.aws;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Arrays;
import java.util.Objects;

/**
 * Calls the AWS Nitro Secure Module directly through Linux's {@code /dev/nsm}
 * ioctl interface using Panama.
 */
public final class PanamaNitroEnclaveAttestationSource
        implements AwsNitroEnclaveAttestationSource {
    private static final int NONCE_BYTES = 32;
    private static final int MAX_PUBLIC_KEY_BYTES = 16 * 1024;
    private static final int MAX_ATTESTATION_DOCUMENT_BYTES = 0x3000;
    private static final int MAX_NSM_REQUEST_BYTES = 0x1000;
    private static final int NSM_IOCTL_MAGIC = 0x0A;
    private static final int NSM_IOCTL_NUMBER = 0;
    private static final int IOVEC_BYTES = 2 * Long.BYTES;
    private static final int NSM_MESSAGE_BYTES = 2 * IOVEC_BYTES;
    private static final int LINUX_O_RDWR = 2;
    private static final long NSM_IOCTL_REQUEST = (3L << 30)
            | ((long) NSM_MESSAGE_BYTES << 16)
            | ((long) NSM_IOCTL_MAGIC << 8)
            | NSM_IOCTL_NUMBER;

    private final NativeOperations nativeOperations;

    public PanamaNitroEnclaveAttestationSource() {
        this.nativeOperations = new PanamaNativeOperations();
    }

    PanamaNitroEnclaveAttestationSource(NativeOperations nativeOperations) {
        this.nativeOperations = Objects.requireNonNull(nativeOperations, "nativeOperations");
    }

    @Override
    public byte[] attest(byte[] nonce, byte[] ephemeralRsaPublicKey) {
        if (nonce == null || nonce.length != NONCE_BYTES) {
            throw new IllegalArgumentException("AWS NSM requires a 256-bit nonce.");
        }
        if (ephemeralRsaPublicKey == null || ephemeralRsaPublicKey.length == 0
                || ephemeralRsaPublicKey.length > MAX_PUBLIC_KEY_BYTES) {
            throw new IllegalArgumentException("AWS NSM recipient public key is invalid.");
        }
        byte[] document = nativeOperations.attest(nonce, ephemeralRsaPublicKey);
        if (document == null || document.length == 0
                || document.length > MAX_ATTESTATION_DOCUMENT_BYTES) {
            if (document != null) {
                Arrays.fill(document, (byte) 0);
            }
            throw new AwsNitroEnclaveException(
                    "AWS NSM returned an invalid attestation document.");
        }
        return document;
    }

    @FunctionalInterface
    interface NativeOperations {
        byte[] attest(byte[] nonce, byte[] ephemeralRsaPublicKey);
    }

    private static final class PanamaNativeOperations implements NativeOperations {
        @Override
        public byte[] attest(byte[] nonce, byte[] ephemeralRsaPublicKey) {
            requireSupportedPlatform();
            byte[] request;
            try {
                request = NsmCbor.encodeAttestationRequest(nonce, ephemeralRsaPublicKey);
            } catch (IOException failure) {
                throw new AwsNitroEnclaveException(
                        "Unable to encode the AWS NSM attestation request.", failure);
            }
            if (request.length > MAX_NSM_REQUEST_BYTES) {
                Arrays.fill(request, (byte) 0);
                throw new IllegalArgumentException("AWS NSM request exceeds its size limit.");
            }

            try (Arena arena = Arena.ofConfined()) {
                MemorySegment nativeRequest = arena.allocate(request.length, 1)
                        .copyFrom(MemorySegment.ofArray(request));
                MemorySegment nativeResponse = arena.allocate(MAX_ATTESTATION_DOCUMENT_BYTES, 1);
                MemorySegment nativeMessage = arena.allocate(NSM_MESSAGE_BYTES, Long.BYTES);
                try {
                    nativeMessage.set(ValueLayout.ADDRESS, 0, nativeRequest);
                    nativeMessage.set(ValueLayout.JAVA_LONG, Long.BYTES, request.length);
                    nativeMessage.set(ValueLayout.ADDRESS, IOVEC_BYTES, nativeResponse);
                    nativeMessage.set(
                            ValueLayout.JAVA_LONG,
                            IOVEC_BYTES + Long.BYTES,
                            MAX_ATTESTATION_DOCUMENT_BYTES);
                    try (NsmDevice device = NsmDevice.open()) {
                        int status = invokeIoctl(device.fileDescriptor(), nativeMessage);
                        long responseLength = nativeMessage.get(
                                ValueLayout.JAVA_LONG, IOVEC_BYTES + Long.BYTES);
                        if (status != 0 || responseLength <= 0
                                || responseLength > MAX_ATTESTATION_DOCUMENT_BYTES) {
                            throw new AwsNitroEnclaveException(
                                    "AWS NSM attestation ioctl returned an invalid response.");
                        }
                        byte[] encodedResponse = nativeResponse.asSlice(0, responseLength)
                                .toArray(ValueLayout.JAVA_BYTE);
                        try {
                            return NsmCbor.decodeAttestationResponse(encodedResponse);
                        } catch (IOException failure) {
                            throw new AwsNitroEnclaveException(
                                    "AWS NSM returned an invalid CBOR attestation response.",
                                    failure);
                        } finally {
                            Arrays.fill(encodedResponse, (byte) 0);
                        }
                    }
                } finally {
                    nativeRequest.fill((byte) 0);
                    nativeResponse.fill((byte) 0);
                    nativeMessage.fill((byte) 0);
                }
            } finally {
                Arrays.fill(request, (byte) 0);
            }
        }

        private static int invokeIoctl(int fileDescriptor, MemorySegment message) {
            try {
                return (int) NativeBindings.IOCTL.invokeExact(
                        fileDescriptor, NSM_IOCTL_REQUEST, message);
            } catch (Throwable failure) {
                throw new AwsNitroEnclaveException(
                        "Unable to invoke the AWS NSM ioctl.", failure);
            }
        }

        private static void requireSupportedPlatform() {
            if (!"Linux".equalsIgnoreCase(System.getProperty("os.name", ""))
                    || ValueLayout.ADDRESS.byteSize() != Long.BYTES) {
                throw new AwsNitroEnclaveException(
                        "Direct AWS NSM access requires a 64-bit Linux runtime.");
            }
        }
    }

    private static final class NsmDevice implements AutoCloseable {
        private final int fileDescriptor;
        private boolean closed;

        private NsmDevice(int fileDescriptor) {
            this.fileDescriptor = fileDescriptor;
        }

        static NsmDevice open() {
            try (Arena arena = Arena.ofConfined()) {
                int fd = (int) NativeBindings.OPEN.invokeExact(
                        arena.allocateUtf8String("/dev/nsm"), LINUX_O_RDWR);
                if (fd < 0) {
                    throw new AwsNitroEnclaveException(
                            "Unable to open the AWS NSM device at /dev/nsm.");
                }
                return new NsmDevice(fd);
            } catch (AwsNitroEnclaveException failure) {
                throw failure;
            } catch (Throwable failure) {
                throw new AwsNitroEnclaveException(
                        "Unable to open the AWS NSM device at /dev/nsm.", failure);
            }
        }

        int fileDescriptor() {
            return fileDescriptor;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                int status = (int) NativeBindings.CLOSE.invokeExact(fileDescriptor);
                if (status != 0) {
                    throw new AwsNitroEnclaveException(
                            "Unable to close the AWS NSM device.");
                }
            } catch (AwsNitroEnclaveException failure) {
                throw failure;
            } catch (Throwable failure) {
                throw new AwsNitroEnclaveException(
                        "Unable to close the AWS NSM device.", failure);
            }
        }
    }

    private static final class NativeBindings {
        private static final Linker LINKER = Linker.nativeLinker();
        private static final SymbolLookup SYMBOLS = LINKER.defaultLookup();
        private static final MethodHandle OPEN = bind(
                "open",
                FunctionDescriptor.of(
                        ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        private static final MethodHandle CLOSE = bind(
                "close",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        private static final MethodHandle IOCTL = bind(
                "ioctl",
                FunctionDescriptor.of(
                        ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG,
                        ValueLayout.ADDRESS),
                Linker.Option.firstVariadicArg(2));

        private NativeBindings() {}

        private static MethodHandle bind(
                String name,
                FunctionDescriptor descriptor,
                Linker.Option... options) {
            try {
                MemorySegment symbol = SYMBOLS.find(name)
                        .orElseThrow(() -> new UnsatisfiedLinkError(
                                "Linux libc symbol is unavailable: " + name));
                return LINKER.downcallHandle(symbol, descriptor, options);
            } catch (Throwable failure) {
                throw new AwsNitroEnclaveException(
                        "Unable to bind Linux libc symbol: " + name, failure);
            }
        }
    }
}
