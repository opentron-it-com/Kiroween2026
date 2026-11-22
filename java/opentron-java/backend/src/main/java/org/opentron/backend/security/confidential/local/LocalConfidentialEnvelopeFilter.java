package org.opentron.backend.security.confidential.local;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

public final class LocalConfidentialEnvelopeFilter implements Filter {
    private static final String ENVELOPE_HEADER = "X-Confidential-Envelope";
    private static final int MAX_ENVELOPE_BYTES = 32 * 1024 * 1024;
    private static final Set<String> CONFIDENTIAL_POST_PATHS = Set.of(
            "/v1/agents/coordinate",
            "/v1/agents/task",
            "/v1/orchestrate/task",
            "/v1/orchestrate/batch",
            "/v1/analyze-screenshot");

    private final LocalConfidentialEnvelopeService envelopeService;
    private final ObjectMapper objectMapper;

    public LocalConfidentialEnvelopeFilter(
            LocalConfidentialEnvelopeService envelopeService,
            ObjectMapper objectMapper) {
        this.envelopeService = envelopeService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void doFilter(
            jakarta.servlet.ServletRequest servletRequest,
            jakarta.servlet.ServletResponse servletResponse,
            FilterChain chain) throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) servletRequest;
        HttpServletResponse response = (HttpServletResponse) servletResponse;
        String path = request.getRequestURI();

        if ("OPTIONS".equalsIgnoreCase(request.getMethod())
                || !"POST".equalsIgnoreCase(request.getMethod())
                || !CONFIDENTIAL_POST_PATHS.contains(path)) {
            chain.doFilter(request, response);
            return;
        }

        if (!"v1".equals(request.getHeader(ENVELOPE_HEADER))) {
            writeError(response, HttpServletResponse.SC_BAD_REQUEST,
                    "encrypted_request_required",
                    "This agent endpoint requires a local confidential request envelope.");
            return;
        }

        byte[] encodedEnvelope = request.getInputStream().readNBytes(MAX_ENVELOPE_BYTES + 1);
        if (encodedEnvelope.length > MAX_ENVELOPE_BYTES) {
            writeError(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
                    "envelope_too_large", "The confidential request envelope is too large.");
            return;
        }

        LocalConfidentialEnvelopeService.DecryptedRequest decrypted;
        try {
            decrypted = envelopeService.decrypt(encodedEnvelope, request.getMethod(), path);
        } catch (LocalConfidentialEnvelopeService.StaleEnvelopeKeyException exception) {
            response.setHeader("X-Confidential-Envelope-Error", "stale-key");
            writeError(response, HttpServletResponse.SC_CONFLICT,
                    "stale_envelope_key", exception.getMessage());
            return;
        } catch (LocalConfidentialEnvelopeService.EnvelopeException exception) {
            writeError(response, HttpServletResponse.SC_BAD_REQUEST,
                    "invalid_envelope", exception.getMessage());
            return;
        }

        LocalConfidentialRequestContext.Permit permit =
                LocalConfidentialRequestContext.createPermit(envelopeService.publicKey().keyId());
        DecryptedRequestWrapper wrapped;
        try {
            wrapped = new DecryptedRequestWrapper(request, decrypted);
        } catch (IllegalArgumentException exception) {
            writeError(response, HttpServletResponse.SC_BAD_REQUEST,
                    "invalid_envelope", "Invalid confidential request query parameters.");
            return;
        }
        wrapped.setAttribute(LocalConfidentialRequestContext.REQUEST_ATTRIBUTE, permit);
        try (LocalConfidentialRequestContext.Scope ignored =
                     LocalConfidentialRequestContext.enter(permit)) {
            chain.doFilter(wrapped, response);
        }
    }

    private void writeError(
            HttpServletResponse response,
            int status,
            String code,
            String message) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json");
        objectMapper.writeValue(response.getOutputStream(), Map.of("error", code, "message", message));
    }

    private static final class DecryptedRequestWrapper extends HttpServletRequestWrapper {
        private final byte[] body;
        private final String query;
        private final Map<String, String> sensitiveHeaders;
        private final Map<String, String[]> parameters;

        private DecryptedRequestWrapper(
                HttpServletRequest request,
                LocalConfidentialEnvelopeService.DecryptedRequest decrypted) {
            super(request);
            this.body = decrypted.body();
            this.query = decrypted.query();
            this.sensitiveHeaders = decrypted.headers();
            this.parameters = parseParameters(request.getParameterMap(), query);
        }

        @Override
        public String getHeader(String name) {
            String decryptedValue = sensitiveHeaders.get(name.toLowerCase(java.util.Locale.ROOT));
            return decryptedValue != null ? decryptedValue : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            String decryptedValue = sensitiveHeaders.get(name.toLowerCase(java.util.Locale.ROOT));
            if (decryptedValue != null) {
                return Collections.enumeration(List.of(decryptedValue));
            }
            return super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            List<String> names = Collections.list(super.getHeaderNames());
            for (String name : sensitiveHeaders.keySet()) {
                if (names.stream().noneMatch(existing -> existing.equalsIgnoreCase(name))) {
                    names.add(name);
                }
            }
            return Collections.enumeration(names);
        }

        @Override
        public String getQueryString() {
            return query.isEmpty() ? null : query;
        }

        @Override
        public String getParameter(String name) {
            String[] values = parameters.get(name);
            return values == null || values.length == 0 ? null : values[0];
        }

        @Override
        public String[] getParameterValues(String name) {
            String[] values = parameters.get(name);
            return values == null ? null : values.clone();
        }

        @Override
        public Enumeration<String> getParameterNames() {
            return Collections.enumeration(parameters.keySet());
        }

        @Override
        public Map<String, String[]> getParameterMap() {
            Map<String, String[]> copy = new LinkedHashMap<>();
            parameters.forEach((name, values) -> copy.put(name, values.clone()));
            return Collections.unmodifiableMap(copy);
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return input.read();
                }

                @Override
                public boolean isFinished() {
                    return input.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    if (readListener == null) {
                        throw new IllegalArgumentException("ReadListener must not be null.");
                    }
                    try {
                        if (input.available() > 0) {
                            readListener.onDataAvailable();
                        }
                        if (input.available() == 0) {
                            readListener.onAllDataRead();
                        }
                    } catch (IOException exception) {
                        readListener.onError(exception);
                    }
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), getCharacterEncoding() == null
                    ? StandardCharsets.UTF_8
                    : java.nio.charset.Charset.forName(getCharacterEncoding())));
        }

        private static Map<String, String[]> parseParameters(
                Map<String, String[]> original,
                String query) {
            Map<String, List<String>> values = new LinkedHashMap<>();
            original.forEach((name, entries) ->
                    values.put(name, new ArrayList<>(List.of(entries))));

            if (!query.isEmpty()) {
                for (String pair : query.split("&")) {
                    if (pair.isEmpty()) {
                        continue;
                    }
                    int separator = pair.indexOf('=');
                    String name = decode(separator < 0 ? pair : pair.substring(0, separator));
                    String value = decode(separator < 0 ? "" : pair.substring(separator + 1));
                    values.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
                }
            }

            Map<String, String[]> result = new LinkedHashMap<>();
            values.forEach((name, entries) -> result.put(name, entries.toArray(String[]::new)));
            return Collections.unmodifiableMap(result);
        }

        private static String decode(String value) {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        }
    }
}
