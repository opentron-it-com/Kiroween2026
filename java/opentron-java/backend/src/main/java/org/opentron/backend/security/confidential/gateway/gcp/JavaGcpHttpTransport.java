package org.opentron.backend.security.confidential.gateway.gcp;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.ResponseInfo;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Java HTTP transport with redirect denial, endpoint restrictions, and body bounds. */
public final class JavaGcpHttpTransport implements GcpHttpTransport {
    private final HttpClient client;

    public JavaGcpHttpTransport(Duration connectTimeout) {
        client = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public GcpHttpTransport.HttpResponse postOrGet(
            String method,
            URI uri,
            Map<String, String> headers,
            byte[] body,
            Duration timeout,
            int maximumResponseBytes) {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new GcpAttestationException("External GCP endpoints must use HTTPS.");
        }
        if (maximumResponseBytes < 1 || timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("HTTP response bound and timeout must be positive.");
        }
        CompletableFuture<?> pending = null;
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(timeout);
            headers.forEach(builder::header);
            if ("GET".equals(method)) {
                builder.GET();
            } else if ("POST".equals(method)) {
                builder.POST(HttpRequest.BodyPublishers.ofByteArray(body == null ? new byte[0] : body));
            } else {
                throw new IllegalArgumentException("Unsupported HTTP method.");
            }
            var responseFuture = client.sendAsync(
                    builder.build(), boundedBodyHandler(maximumResponseBytes));
            pending = responseFuture;
            var response = responseFuture.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            byte[] responseBody = response.body();
            try {
                return new GcpHttpTransport.HttpResponse(response.statusCode(), responseBody);
            } finally {
                java.util.Arrays.fill(responseBody, (byte) 0);
            }
        } catch (TimeoutException e) {
            if (pending != null) {
                pending.cancel(true);
            }
            throw new GcpAttestationException("GCP security endpoint request timed out.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GcpAttestationException("GCP security endpoint request was interrupted.", e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new GcpAttestationException("GCP security endpoint request failed.", e.getCause());
        }
    }

    private static BodyHandler<byte[]> boundedBodyHandler(int maximumBytes) {
        return (ResponseInfo ignored) -> new BodySubscriber<>() {
            private final CompletableFuture<byte[]> body = new CompletableFuture<>();
            private final WipingByteArrayOutputStream output = new WipingByteArrayOutputStream(
                    Math.min(maximumBytes, 8192));
            private Flow.Subscription subscription;

            @Override
            public java.util.concurrent.CompletionStage<byte[]> getBody() {
                return body;
            }

            @Override
            public void onSubscribe(Flow.Subscription value) {
                subscription = value;
                subscription.request(1);
            }

            @Override
            public void onNext(List<ByteBuffer> buffers) {
                long incoming = buffers.stream().mapToLong(ByteBuffer::remaining).sum();
                if ((long) output.size() + incoming > maximumBytes) {
                    subscription.cancel();
                    output.wipe();
                    body.completeExceptionally(new GcpAttestationException(
                            "GCP security endpoint response exceeded its size limit."));
                    return;
                }
                for (ByteBuffer buffer : buffers) {
                    byte[] chunk = new byte[buffer.remaining()];
                    buffer.get(chunk);
                    output.write(chunk, 0, chunk.length);
                    java.util.Arrays.fill(chunk, (byte) 0);
                }
                subscription.request(1);
            }

            @Override
            public void onError(Throwable error) {
                output.wipe();
                body.completeExceptionally(error);
            }

            @Override
            public void onComplete() {
                body.complete(output.drain());
            }
        };
    }

    private static final class WipingByteArrayOutputStream extends ByteArrayOutputStream {
        private WipingByteArrayOutputStream(int size) {
            super(size);
        }

        private byte[] drain() {
            byte[] result = toByteArray();
            wipe();
            return result;
        }

        private void wipe() {
            java.util.Arrays.fill(buf, (byte) 0);
            reset();
        }
    }
}
