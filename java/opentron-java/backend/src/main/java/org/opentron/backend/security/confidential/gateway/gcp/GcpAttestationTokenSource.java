package org.opentron.backend.security.confidential.gateway.gcp;

/**
 * Deployment-supplied source of a GCA-issued compact JWT bound to the requested
 * audience and nonce. Google documents the token claims and the launcher flow,
 * but this adapter intentionally does not assume an undocumented metadata URL.
 * The deployment must supply an implementation using its approved acquisition
 * mechanism; it must not return caller-supplied or unverified evidence.
 */
@FunctionalInterface
public interface GcpAttestationTokenSource {
    String fetch(String audience, String nonce);
}
