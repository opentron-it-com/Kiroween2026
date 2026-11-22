package org.opentron.backend.security.confidential.gateway.aws;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.Semaphore;

public final class LinuxVsockHealthBridge implements AutoCloseable {
    private static final System.Logger LOGGER =
            System.getLogger(LinuxVsockHealthBridge.class.getName());
    private static final int AF_VSOCK = 40;
    private static final int SOCK_STREAM = 1;
    private static final int VMADDR_CID_ANY = -1;
    private static final int SHUT_RDWR = 2;
    private static final int SOCKET_ADDRESS_SIZE = 16;
    private static final int MAX_REQUEST_LINE_BYTES = 1024;
    private static final int MAX_RESPONSE_BYTES = 65536;
    private static final int MAX_CLIENTS = 8;
    private static final byte[] BUSY_RESPONSE = httpResponse(
            503, "{\"status\":\"unavailable\"}".getBytes(StandardCharsets.UTF_8));
    private static final byte[] BAD_REQUEST_RESPONSE = httpResponse(
            400, "{\"status\":\"bad_request\"}".getBytes(StandardCharsets.UTF_8));
    private static final Linker LINKER = Linker.nativeLinker();
    private static final FunctionDescriptor SOCKET_DESCRIPTOR = FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT);
    private static final FunctionDescriptor BIND_DESCRIPTOR = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT);
    private static final FunctionDescriptor LISTEN_DESCRIPTOR =
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT);
    private static final FunctionDescriptor ACCEPT_DESCRIPTOR = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS);
    private static final FunctionDescriptor IO_DESCRIPTOR = FunctionDescriptor.of(
            ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG);
    private static final FunctionDescriptor SHUTDOWN_DESCRIPTOR =
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT);
    private static final FunctionDescriptor CLOSE_DESCRIPTOR =
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT);

    private final int parentCid;
    private final int vsockPort;
    private final HealthResponseProvider healthResponseProvider;
    private final HealthResponseProvider benchmarkResponseProvider;
    private final Semaphore activeClients = new Semaphore(MAX_CLIENTS);
    private volatile boolean closed;
    private volatile int serverFd = -1;
    private Thread acceptThread;

    public LinuxVsockHealthBridge(
            int parentCid, int vsockPort, HealthResponseProvider healthResponseProvider) {
        this(parentCid, vsockPort, healthResponseProvider, null);
    }

    public LinuxVsockHealthBridge(
            int parentCid,
            int vsockPort,
            HealthResponseProvider healthResponseProvider,
            HealthResponseProvider benchmarkResponseProvider) {
        if (parentCid < 0 || vsockPort < 1 || vsockPort > 65535) {
            throw new IllegalArgumentException("AWS Nitro health bridge values are invalid.");
        }
        this.parentCid = parentCid;
        this.vsockPort = vsockPort;
        this.healthResponseProvider =
                Objects.requireNonNull(healthResponseProvider, "healthResponseProvider");
        this.benchmarkResponseProvider = benchmarkResponseProvider;
    }

    public synchronized void start() {
        if (acceptThread != null) {
            throw new IllegalStateException("AWS Nitro health vsock bridge is already started.");
        }
        requireLinux();
        try (Arena arena = Arena.ofConfined()) {
            NativeMethods methods = nativeMethods();
            int fd = (int) methods.socket().invokeExact(AF_VSOCK, SOCK_STREAM, 0);
            if (fd < 0) {
                throw new AwsNitroEnclaveException("Unable to open health-only vsock listener.");
            }
            serverFd = fd;
            MemorySegment address = arena.allocate(SOCKET_ADDRESS_SIZE, Long.BYTES);
            address.fill((byte) 0);
            address.set(ValueLayout.JAVA_SHORT, 0, (short) AF_VSOCK);
            address.set(ValueLayout.JAVA_INT, 4, vsockPort);
            address.set(ValueLayout.JAVA_INT, 8, VMADDR_CID_ANY);
            if ((int) methods.bind().invokeExact(fd, address, SOCKET_ADDRESS_SIZE) != 0) {
                throw new AwsNitroEnclaveException("Unable to bind health-only vsock port.");
            }
            if ((int) methods.listen().invokeExact(fd, MAX_CLIENTS) != 0) {
                throw new AwsNitroEnclaveException("Unable to listen on health-only vsock port.");
            }
            acceptThread = Thread.ofVirtual()
                    .name("cjae-health-vsock-accept")
                    .start(this::acceptLoop);
            LOGGER.log(
                    System.Logger.Level.INFO,
                    "AWS Nitro health-only vsock listener started (port={0}, parentCid={1}).",
                    vsockPort,
                    parentCid);
        } catch (AwsNitroEnclaveException exception) {
            closeServerSocket();
            throw exception;
        } catch (Throwable failure) {
            closeServerSocket();
            throw new AwsNitroEnclaveException(
                    "Unable to start AWS Nitro health-only vsock bridge.", failure);
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        closeServerSocket();
        if (acceptThread != null) {
            try {
                acceptThread.join(2_000);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            acceptThread = null;
        }
    }

    static boolean isHealthRequestLine(String requestLine) {
        return "GET /health HTTP/1.0".equals(requestLine)
                || "GET /health HTTP/1.1".equals(requestLine);
    }

    static boolean isBenchmarkResultsRequestLine(String requestLine) {
        return "GET /benchmark/results HTTP/1.0".equals(requestLine)
                || "GET /benchmark/results HTTP/1.1".equals(requestLine);
    }

    private void acceptLoop() {
        NativeMethods methods = nativeMethods();
        while (!closed) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment peerAddress = arena.allocate(SOCKET_ADDRESS_SIZE, Long.BYTES);
                MemorySegment addressLength = arena.allocate(ValueLayout.JAVA_INT);
                peerAddress.fill((byte) 0);
                addressLength.set(ValueLayout.JAVA_INT, 0, SOCKET_ADDRESS_SIZE);
                int clientFd = (int) methods.accept().invokeExact(
                        serverFd, peerAddress, addressLength);
                if (clientFd < 0) {
                    if (!closed) {
                        LOGGER.log(
                                System.Logger.Level.ERROR,
                                "AWS Nitro health vsock accept failed.");
                        throw new AwsNitroEnclaveException(
                                "AWS Nitro health vsock accept failed.");
                    }
                    return;
                }
                int peerFamily = peerAddress.get(ValueLayout.JAVA_SHORT, 0);
                int peerAddressSize = addressLength.get(ValueLayout.JAVA_INT, 0);
                int peerCid = peerAddress.get(ValueLayout.JAVA_INT, 8);
                if (peerFamily != AF_VSOCK
                        || peerAddressSize != SOCKET_ADDRESS_SIZE
                        || peerCid != parentCid) {
                    LOGGER.log(
                            System.Logger.Level.WARNING,
                            "AWS Nitro health vsock rejected peer (family={0}, cid={1}, "
                                    + "addressLength={2}, expectedParentCid={3}).",
                            peerFamily,
                            peerCid,
                            peerAddressSize,
                            parentCid);
                    closeSocket(methods, clientFd);
                    continue;
                }
                if (!activeClients.tryAcquire()) {
                    closeSocket(methods, clientFd);
                    continue;
                }
                try {
                    Thread.ofVirtual()
                            .name("cjae-health-vsock-client")
                            .start(() -> serveClient(methods, clientFd));
                } catch (RuntimeException failure) {
                    activeClients.release();
                    closeSocket(methods, clientFd);
                    throw failure;
                }
            } catch (AwsNitroEnclaveException exception) {
                if (!closed) {
                    throw exception;
                }
                return;
            } catch (Throwable failure) {
                if (!closed) {
                    throw new AwsNitroEnclaveException(
                            "AWS Nitro health vsock accept failed.", failure);
                }
                return;
            }
        }
    }

    private void serveClient(NativeMethods methods, int clientFd) {
        try {
            String requestLine = readRequestLine(methods, clientFd);
            boolean healthRequest = isHealthRequestLine(requestLine);
            boolean benchmarkRequest = benchmarkResponseProvider != null
                    && isBenchmarkResultsRequestLine(requestLine);
            if (!healthRequest && !benchmarkRequest) {
                writeResponse(methods, clientFd, BAD_REQUEST_RESPONSE.clone());
                return;
            }
            HealthResponse health = (healthRequest
                    ? healthResponseProvider
                    : benchmarkResponseProvider).get();
            byte[] body = health.body();
            try {
                if (body.length > MAX_RESPONSE_BYTES) {
                    throw new IOException("Worker health response exceeds its size limit.");
                }
                int statusCode = health.statusCode() == 200 || health.statusCode() == 503
                        ? health.statusCode()
                        : 503;
                writeResponse(methods, clientFd, httpResponse(statusCode, body));
            } finally {
                Arrays.fill(body, (byte) 0);
            }
        } catch (IOException failure) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "AWS Nitro health vsock probe could not produce worker health "
                            + "(failureType={0}).",
                    failure.getClass().getSimpleName());
            try {
                writeResponse(methods, clientFd, BUSY_RESPONSE.clone());
            } catch (IOException writeFailure) {
                LOGGER.log(System.Logger.Level.DEBUG,
                        "AWS Nitro health vsock probe response could not be sent.");
            }
        } catch (RuntimeException failure) {
            LOGGER.log(
                    System.Logger.Level.ERROR,
                    "AWS Nitro health vsock probe failed (failureType={0}).",
                    failure.getClass().getSimpleName());
            try {
                writeResponse(methods, clientFd, BUSY_RESPONSE.clone());
            } catch (IOException writeFailure) {
                LOGGER.log(System.Logger.Level.DEBUG,
                        "AWS Nitro health vsock probe response could not be sent.");
            }
        } finally {
            shutdown(methods, clientFd);
            closeSocket(methods, clientFd);
            activeClients.release();
        }
    }

    private static String readRequestLine(NativeMethods methods, int fd) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocate(MAX_REQUEST_LINE_BYTES, 8);
            try {
                long total = 0;
                while (total < MAX_REQUEST_LINE_BYTES) {
                    long count;
                    try {
                        count = (long) methods.read().invokeExact(
                                fd,
                                buffer.asSlice(total),
                                (long) MAX_REQUEST_LINE_BYTES - total);
                    } catch (Throwable failure) {
                        throw new IOException("Unable to read health probe request.", failure);
                    }
                    if (count <= 0) {
                        throw new IOException("Health probe request ended before its request line.");
                    }
                    long end = total + count;
                    for (long index = total; index < end; index++) {
                        if (buffer.get(ValueLayout.JAVA_BYTE, index) == (byte) '\n') {
                            if (index < 1
                                    || buffer.get(ValueLayout.JAVA_BYTE, index - 1) != (byte) '\r') {
                                throw new IOException("Health probe request line is malformed.");
                            }
                            byte[] requestLine =
                                    buffer.asSlice(0, index - 1).toArray(ValueLayout.JAVA_BYTE);
                            try {
                                return new String(requestLine, StandardCharsets.US_ASCII);
                            } finally {
                                Arrays.fill(requestLine, (byte) 0);
                            }
                        }
                    }
                    total = end;
                }
                throw new IOException("Health probe request line exceeds the allowed length.");
            } finally {
                buffer.fill((byte) 0);
            }
        }
    }

    static byte[] httpResponse(int statusCode, byte[] body) {
        String reason = switch (statusCode) {
            case 200 -> "OK";
            case 400 -> "Bad Request";
            default -> "Service Unavailable";
        };
        byte[] header = ("HTTP/1.1 " + statusCode + " " + reason
                + "\r\nContent-Type: application/json; charset=utf-8"
                + "\r\nContent-Length: " + body.length
                + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        byte[] response = Arrays.copyOf(header, header.length + body.length);
        System.arraycopy(body, 0, response, header.length, body.length);
        return response;
    }

    private static void writeResponse(
            NativeMethods methods, int fd, byte[] response) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocate(response.length, 8);
            buffer.copyFrom(MemorySegment.ofArray(response));
            long offset = 0;
            while (offset < buffer.byteSize()) {
                long written;
                try {
                    written = (long) methods.write().invokeExact(
                            fd, buffer.asSlice(offset), buffer.byteSize() - offset);
                } catch (Throwable failure) {
                    throw new IOException("Unable to write health probe response.", failure);
                }
                if (written <= 0) {
                    throw new IOException("Health probe response write did not complete.");
                }
                offset += written;
            }
            buffer.fill((byte) 0);
        }
    }

    private void closeServerSocket() {
        int fd = serverFd;
        serverFd = -1;
        if (fd >= 0) {
            shutdown(nativeMethods(), fd);
            closeSocket(nativeMethods(), fd);
        }
    }

    private static void shutdown(NativeMethods methods, int fd) {
        try {
            int result = (int) methods.shutdown().invokeExact(fd, SHUT_RDWR);
            if (result != 0) {
                LOGGER.log(System.Logger.Level.DEBUG,
                        "Unable to shut down AWS Nitro health socket.");
            }
        } catch (Throwable failure) {
            LOGGER.log(System.Logger.Level.DEBUG,
                    "Unable to shut down AWS Nitro health socket: {0}",
                    failure.getClass().getSimpleName());
        }
    }

    private static void closeSocket(NativeMethods methods, int fd) {
        try {
            int result = (int) methods.close().invokeExact(fd);
            if (result != 0) {
                LOGGER.log(System.Logger.Level.DEBUG,
                        "Unable to close AWS Nitro health socket.");
            }
        } catch (Throwable failure) {
            LOGGER.log(System.Logger.Level.DEBUG,
                    "Unable to close AWS Nitro health socket: {0}",
                    failure.getClass().getSimpleName());
        }
    }

    private static void requireLinux() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("linux")) {
            throw new AwsNitroEnclaveException(
                    "AWS Nitro health vsock bridge is supported only on Linux.");
        }
    }

    private static MethodHandle downcall(
            SymbolLookup symbols, String name, FunctionDescriptor descriptor) {
        MemorySegment address = symbols.find(name)
                .orElseThrow(() -> new UnsatisfiedLinkError(
                        "Linux libc symbol is unavailable: " + name));
        return LINKER.downcallHandle(address, descriptor);
    }

    private static NativeMethods nativeMethods() {
        return NativeMethodsHolder.METHODS;
    }

    private static final class NativeMethodsHolder {
        private static final NativeMethods METHODS = loadMethods();

        private static NativeMethods loadMethods() {
            SymbolLookup libc = LINKER.defaultLookup();
            return new NativeMethods(
                    downcall(libc, "socket", SOCKET_DESCRIPTOR),
                    downcall(libc, "bind", BIND_DESCRIPTOR),
                    downcall(libc, "listen", LISTEN_DESCRIPTOR),
                    downcall(libc, "accept", ACCEPT_DESCRIPTOR),
                    downcall(libc, "read", IO_DESCRIPTOR),
                    downcall(libc, "write", IO_DESCRIPTOR),
                    downcall(libc, "shutdown", SHUTDOWN_DESCRIPTOR),
                    downcall(libc, "close", CLOSE_DESCRIPTOR));
        }
    }

    private record NativeMethods(
            MethodHandle socket,
            MethodHandle bind,
            MethodHandle listen,
            MethodHandle accept,
            MethodHandle read,
            MethodHandle write,
            MethodHandle shutdown,
            MethodHandle close) {
    }

    @FunctionalInterface
    public interface HealthResponseProvider {
        HealthResponse get() throws IOException;
    }

    public record HealthResponse(int statusCode, byte[] body) {
        public HealthResponse {
            body = Objects.requireNonNull(body, "body").clone();
        }

        @Override
        public byte[] body() {
            return body.clone();
        }
    }
}
