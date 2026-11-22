package org.opentron.backend.security.confidential.gateway.gcp;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

/** Injectable bounded HTTP boundary used for metadata, JWKS, STS, and KMS calls. */
@FunctionalInterface
public interface GcpHttpTransport {
    HttpResponse postOrGet(
            String method,
            URI uri,
            Map<String, String> headers,
            byte[] body,
            Duration timeout,
            int maximumResponseBytes);

    record HttpResponse(int statusCode, byte[] body) {
        public HttpResponse {
            body = body == null ? new byte[0] : body.clone();
        }

        @Override
        public byte[] body() {
            return body.clone();
        }

        public void clear() {
            java.util.Arrays.fill(body, (byte) 0);
        }
    }
}
