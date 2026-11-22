package org.opentron.backend.security.confidential.gateway.gcp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/** Acquires nonce-bound GCA evidence using Google's vTPM attestation tool. */
public final class GoTpmGcpAttestationTokenSource implements GcpAttestationTokenSource {
    private static final int MAX_TOKEN_BYTES = 16 * 1024;
    private static final int MAX_DIAGNOSTIC_BYTES = 32 * 1024;
    private static final Pattern AUDIENCE = Pattern.compile("[^\\p{Cntrl}\\s]{1,2048}");
    private static final Pattern NONCE = Pattern.compile("[A-Za-z0-9_-]{20,64}");
    private static final Pattern COMPACT_JWT = Pattern.compile(
            "[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");

    private final Path executable;
    private final String teeTechnology;
    private final Path eventLog;
    private final Duration timeout;
    private final CommandExecutor commandExecutor;

    public GoTpmGcpAttestationTokenSource(
            Path executable,
            String teeTechnology,
            Path eventLog,
            Duration timeout) {
        this(executable, teeTechnology, eventLog, timeout, GoTpmGcpAttestationTokenSource::execute);
    }

    GoTpmGcpAttestationTokenSource(
            Path executable,
            String teeTechnology,
            Path eventLog,
            Duration timeout,
            CommandExecutor commandExecutor) {
        this.executable = Objects.requireNonNull(executable, "executable");
        if (teeTechnology == null
                || (!teeTechnology.isEmpty() && !"sev-snp".equals(teeTechnology)
                        && !"tdx".equals(teeTechnology))) {
            throw new IllegalArgumentException("GCA TEE technology must be empty, sev-snp, or tdx.");
        }
        this.eventLog = Objects.requireNonNull(eventLog, "eventLog");
        if (!eventLog.isAbsolute()) {
            throw new IllegalArgumentException("GCA event log path must be absolute.");
        }
        this.teeTechnology = teeTechnology;
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofMinutes(2)) > 0) {
            throw new IllegalArgumentException("GCA token acquisition timeout must be in (0, 2 minutes].");
        }
        this.commandExecutor = Objects.requireNonNull(commandExecutor, "commandExecutor");
    }

    @Override
    public String fetch(String audience, String nonce) {
        if (audience == null || !AUDIENCE.matcher(audience).matches()) {
            throw new GcpAttestationException("GCA audience is missing or invalid.");
        }
        if (nonce == null || !NONCE.matcher(nonce).matches()) {
            throw new GcpAttestationException("GCA nonce is missing or invalid.");
        }
        List<String> command = new ArrayList<>(List.of(
                executable.toString(),
                "token",
                "--audience",
                audience,
                "--custom-nonce",
                nonce,
                "--event-log",
                eventLog.toString()));
        if (!teeTechnology.isEmpty()) {
            command.add("--tee-technology");
            command.add(teeTechnology);
        }
        String token = commandExecutor.execute(command, timeout);
        if (token == null || !COMPACT_JWT.matcher(token).matches()) {
            throw new GcpAttestationException("Google gotpm did not return a compact attestation JWT.");
        }
        return token;
    }

    private static String execute(List<String> command, Duration timeout) {
        Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (IOException exception) {
            throw new GcpAttestationException("Unable to start Google's GCA token acquisition tool.", exception);
        }

        byte[] stdout = null;
        byte[] stderr = null;
        try (var readers = Executors.newVirtualThreadPerTaskExecutor()) {
            var stdoutFuture = readers.submit(() -> readBounded(process.getInputStream(), MAX_TOKEN_BYTES));
            var stderrFuture = readers.submit(() -> readBounded(process.getErrorStream(), MAX_DIAGNOSTIC_BYTES));
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new GcpAttestationException("Google GCA token acquisition timed out.");
            }
            stdout = stdoutFuture.get();
            stderr = stderrFuture.get();
            if (process.exitValue() != 0) {
                throw new GcpAttestationException(
                        "Google GCA token acquisition failed with exit code " + process.exitValue() + ".");
            }
            return new String(stdout, StandardCharsets.US_ASCII).trim();
        } catch (InterruptedException exception) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new GcpAttestationException("Google GCA token acquisition was interrupted.", exception);
        } catch (ExecutionException exception) {
            process.destroyForcibly();
            throw new GcpAttestationException("Unable to read Google GCA token acquisition output.", exception);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
            if (stdout != null) {
                Arrays.fill(stdout, (byte) 0);
            }
            if (stderr != null) {
                Arrays.fill(stderr, (byte) 0);
            }
        }
    }

    private static byte[] readBounded(InputStream input, int maximumBytes) throws IOException {
        try (input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            try {
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > maximumBytes) {
                        throw new IOException("GCA command output exceeded its configured limit.");
                    }
                    output.write(buffer, 0, count);
                }
                return output.toByteArray();
            } finally {
                Arrays.fill(buffer, (byte) 0);
            }
        }
    }

    @FunctionalInterface
    interface CommandExecutor {
        String execute(List<String> command, Duration timeout);
    }
}
