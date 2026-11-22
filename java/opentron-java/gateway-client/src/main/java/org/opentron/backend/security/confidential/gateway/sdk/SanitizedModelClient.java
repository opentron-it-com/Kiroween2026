package org.opentron.backend.security.confidential.gateway.sdk;

/** Model adapter that receives sanitized prompt text only. */
@FunctionalInterface
public interface SanitizedModelClient {
    String complete(String sanitizedPrompt);
}
