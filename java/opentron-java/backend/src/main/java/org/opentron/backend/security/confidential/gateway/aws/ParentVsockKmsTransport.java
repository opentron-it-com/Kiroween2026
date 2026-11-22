package org.opentron.backend.security.confidential.gateway.aws;

/**
 * Explicit enclave-to-parent-vsock KMS transport boundary.
 *
 * <p>Nitro Enclaves have no external networking. A production implementation
 * must send the bounded request through the parent instance's vsock proxy to
 * AWS KMS and validate/parse the AWS response. No transport implementation or
 * provisioning integration is included in this package.</p>
 */
@FunctionalInterface
public interface ParentVsockKmsTransport {
    AwsNitroKmsDecryptResponse decrypt(AwsNitroKmsDecryptRequest request);
}
