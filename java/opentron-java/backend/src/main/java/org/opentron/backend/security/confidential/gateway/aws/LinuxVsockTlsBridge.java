package org.opentron.backend.security.confidential.gateway.aws;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.net.InetAddress;
import java.net.Socket;
import java.util.Arrays;

/**
 * Relays encrypted TLS records from a parent-enclave vsock to loopback Tomcat.
 */
public final class LinuxVsockTlsBridge implements AutoCloseable {
    private static final System.Logger LOGGER =
            System.getLogger(LinuxVsockTlsBridge.class.getName());
    private static final int AF_VSOCK = 40;
    private static final int SOCK_STREAM = 1;
    private static final int VMADDR_CID_ANY = -1;
    private static final int SHUT_WR = 1;
    private static final int SHUT_RDWR = 2;
    private static final int SOCKET_ADDRESS_SIZE = 16;
    private static final int BUFFER_BYTES = 16 * 1024;
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

    private final int vsockPort;
    private final int tlsPort;
    private volatile boolean closed;
    private volatile int serverFd = -1;
    private Thread acceptThread;

    public LinuxVsockTlsBridge(int vsockPort, int tlsPort) {
        if (vsockPort < 1 || vsockPort > 65535 || tlsPort < 1 || tlsPort > 65535) {
            throw new IllegalArgumentException("AWS Nitro TLS bridge port values are invalid.");
        }
        this.vsockPort = vsockPort;
        this.tlsPort = tlsPort;
    }

    public synchronized void start() {
        if (acceptThread != null) {
            throw new IllegalStateException("AWS Nitro TLS vsock bridge is already started.");
        }
        requireLinux();
        try (Arena arena = Arena.ofConfined()) {
            NativeMethods methods = nativeMethods();
            int fd = (int) methods.socket().invokeExact(AF_VSOCK, SOCK_STREAM, 0);
            if (fd < 0) {
                throw new AwsNitroEnclaveException("Unable to open gateway vsock listener.");
            }
            serverFd = fd;
            MemorySegment address = arena.allocate(SOCKET_ADDRESS_SIZE, Long.BYTES);
            address.fill((byte) 0);
            address.set(ValueLayout.JAVA_SHORT, 0, (short) AF_VSOCK);
            address.set(ValueLayout.JAVA_INT, 4, vsockPort);
            address.set(ValueLayout.JAVA_INT, 8, VMADDR_CID_ANY);
            int bindResult = (int) methods.bind().invokeExact(
                    fd, address, SOCKET_ADDRESS_SIZE);
            if (bindResult != 0) {
                throw new AwsNitroEnclaveException("Unable to bind AWS Nitro gateway vsock port.");
            }
            int listenResult = (int) methods.listen().invokeExact(fd, 64);
            if (listenResult != 0) {
                throw new AwsNitroEnclaveException("Unable to listen on AWS Nitro gateway vsock.");
            }
            acceptThread = Thread.ofVirtual()
                    .name("cjae-gateway-vsock-accept")
                    .start(this::acceptLoop);
        } catch (AwsNitroEnclaveException exception) {
            closeServerSocket();
            throw exception;
        } catch (Throwable failure) {
            closeServerSocket();
            throw new AwsNitroEnclaveException(
                    "Unable to start AWS Nitro gateway vsock bridge.", failure);
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

    private void acceptLoop() {
        NativeMethods methods = nativeMethods();
        while (!closed) {
            final int clientFd;
            try {
                clientFd = (int) methods.accept().invokeExact(
                        serverFd, MemorySegment.NULL, MemorySegment.NULL);
            } catch (Throwable failure) {
                if (!closed) {
                    throw new AwsNitroEnclaveException(
                            "AWS Nitro gateway vsock accept failed.", failure);
                }
                return;
            }
            if (clientFd >= 0) {
                Thread.ofVirtual()
                        .name("cjae-gateway-vsock-client")
                        .start(() -> relayConnection(clientFd));
            }
        }
    }

    private void relayConnection(int clientFd) {
        NativeMethods methods = nativeMethods();
        try (Socket tlsSocket = new Socket(
                InetAddress.getLoopbackAddress(), tlsPort)) {
            tlsSocket.setTcpNoDelay(true);
            Thread toVsock = Thread.ofVirtual().start(() -> {
                try {
                    InputStream input = tlsSocket.getInputStream();
                    copyToVsock(input, methods, clientFd);
                } catch (IOException failure) {
                    LOGGER.log(System.Logger.Level.WARNING,
                            "AWS Nitro TLS bridge failed while relaying client data.");
                    shutdown(methods, clientFd, SHUT_RDWR);
                }
            });
            try {
                OutputStream output = tlsSocket.getOutputStream();
                copyToSocket(methods, clientFd, output);
                tlsSocket.shutdownOutput();
            } catch (IOException failure) {
                LOGGER.log(System.Logger.Level.WARNING,
                        "AWS Nitro TLS bridge failed while relaying enclave data.");
                shutdown(methods, clientFd, SHUT_RDWR);
            }
            try {
                toVsock.join();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                shutdown(methods, clientFd, SHUT_RDWR);
            }
        } catch (IOException failure) {
            LOGGER.log(System.Logger.Level.DEBUG,
                    "AWS Nitro TLS bridge could not connect to loopback TLS service: {0}",
                    failure.getClass().getSimpleName());
            shutdown(methods, clientFd, SHUT_RDWR);
        } finally {
            closeSocket(methods, clientFd);
        }
    }

    private static void copyToSocket(NativeMethods methods, int fd, OutputStream output)
            throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocate(BUFFER_BYTES, 8);
            while (true) {
                long count = (long) methods.read().invokeExact(
                        fd, buffer, (long) BUFFER_BYTES);
                if (count == 0) {
                    output.flush();
                    return;
                }
                if (count < 0) {
                    throw new IOException("AWS Nitro gateway vsock read failed.");
                }
                byte[] bytes = buffer.asSlice(0, count).toArray(ValueLayout.JAVA_BYTE);
                try {
                    output.write(bytes);
                    output.flush();
                } finally {
                    Arrays.fill(bytes, (byte) 0);
                }
            }
        } catch (IOException exception) {
            throw exception;
        } catch (Throwable failure) {
            throw new IOException("AWS Nitro gateway vsock read failed.", failure);
        }
    }

    private static void copyToVsock(InputStream input, NativeMethods methods, int fd)
            throws IOException {
        byte[] bytes = new byte[BUFFER_BYTES];
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocate(BUFFER_BYTES, 8);
            try {
                int count;
                while ((count = input.read(bytes)) >= 0) {
                    if (count == 0) {
                        continue;
                    }
                    buffer.asSlice(0, count).copyFrom(
                            MemorySegment.ofArray(bytes).asSlice(0, count));
                    writeFully(methods, fd, buffer.asSlice(0, count));
                    Arrays.fill(bytes, 0, count, (byte) 0);
                    buffer.asSlice(0, count).fill((byte) 0);
                }
                shutdown(methods, fd, SHUT_WR);
            } finally {
                buffer.fill((byte) 0);
            }
        } catch (IOException exception) {
            throw exception;
        } catch (Throwable failure) {
            throw new IOException("AWS Nitro gateway vsock write failed.", failure);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private static void writeFully(NativeMethods methods, int fd, MemorySegment buffer)
            throws Throwable {
        long offset = 0;
        while (offset < buffer.byteSize()) {
            long written = (long) methods.write().invokeExact(
                    fd, buffer.asSlice(offset), buffer.byteSize() - offset);
            if (written <= 0) {
                throw new IOException("AWS Nitro gateway vsock write did not complete.");
            }
            offset += written;
        }
    }

    private void closeServerSocket() {
        int fd = serverFd;
        serverFd = -1;
        if (fd >= 0) {
            shutdown(nativeMethods(), fd, SHUT_RDWR);
            closeSocket(nativeMethods(), fd);
        }
    }

    private static void shutdown(NativeMethods methods, int fd, int direction) {
        try {
            methods.shutdown().invokeExact(fd, direction);
        } catch (Throwable failure) {
            LOGGER.log(System.Logger.Level.DEBUG,
                    "Unable to shut down AWS Nitro gateway socket: {0}",
                    failure.getClass().getSimpleName());
        }
    }

    private static void closeSocket(NativeMethods methods, int fd) {
        try {
            methods.close().invokeExact(fd);
        } catch (Throwable failure) {
            LOGGER.log(System.Logger.Level.DEBUG,
                    "Unable to close AWS Nitro gateway socket: {0}",
                    failure.getClass().getSimpleName());
        }
    }

    private static void requireLinux() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("linux")) {
            throw new AwsNitroEnclaveException(
                    "AWS Nitro gateway vsock bridge is supported only on Linux.");
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
}
