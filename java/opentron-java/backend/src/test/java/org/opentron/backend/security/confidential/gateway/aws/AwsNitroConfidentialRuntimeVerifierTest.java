package org.opentron.backend.security.confidential.gateway.aws;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.security.KeyFactory;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;

import org.bouncycastle.asn1.DERNull;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.RSAESOAEPparams;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.cms.CMSAlgorithm;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSEnvelopedDataGenerator;
import org.bouncycastle.cms.jcajce.JceCMSContentEncryptorBuilder;
import org.bouncycastle.cms.jcajce.JceKeyTransRecipientInfoGenerator;
import org.junit.jupiter.api.Test;

class AwsNitroConfidentialRuntimeVerifierTest {
    @Test
    void failsClosedWhenKmsPolicyGatedRecipientReleaseFails() {
        AwsNitroEnclaveKeyReleaseService service = new AwsNitroEnclaveKeyReleaseService(
                (nonce, publicKey) -> new byte[] { 1 },
                request -> {
                    throw new IllegalStateException("No KMS release");
                },
                new SecureRandom(),
                "key",
                new byte[] { 1 },
                "context-key");
        AwsNitroConfidentialRuntimeVerifier verifier =
                new AwsNitroConfidentialRuntimeVerifier(service);

        assertThrows(AwsNitroEnclaveException.class, verifier::verifyCurrentRuntime);
    }

    @Test
    void permitsOnlySuccessfulKeyRelease() {
        byte[] secret = new byte[32];
        byte[][] recipientPublicKey = new byte[1][];
        AwsNitroEnclaveKeyReleaseService service = new AwsNitroEnclaveKeyReleaseService(
                (nonce, publicKey) -> {
                    recipientPublicKey[0] = publicKey.clone();
                    return new byte[] { 1 };
                },
                request -> {
                    try {
                        var recipient = KeyFactory.getInstance("RSA").generatePublic(
                                new X509EncodedKeySpec(recipientPublicKey[0]));
                        var generator = new CMSEnvelopedDataGenerator();
                        AlgorithmIdentifier sha256 = new AlgorithmIdentifier(
                                NISTObjectIdentifiers.id_sha256, DERNull.INSTANCE);
                        var oaep = new RSAESOAEPparams(
                                sha256,
                                new AlgorithmIdentifier(PKCSObjectIdentifiers.id_mgf1, sha256),
                                new AlgorithmIdentifier(
                                        PKCSObjectIdentifiers.id_pSpecified,
                                        new DEROctetString(new byte[0])));
                        generator.addRecipientInfoGenerator(new JceKeyTransRecipientInfoGenerator(
                                new byte[] { 1 },
                                new AlgorithmIdentifier(PKCSObjectIdentifiers.id_RSAES_OAEP, oaep),
                                recipient));
                        byte[] envelope = generator.generate(
                                        new CMSProcessableByteArray(secret),
                                        new JceCMSContentEncryptorBuilder(CMSAlgorithm.AES256_CBC)
                                                .build())
                                .getEncoded();
                        return new AwsNitroKmsDecryptResponse(envelope);
                    } catch (Exception e) {
                        throw new AssertionError("Test KMS transport failed.", e);
                    }
                },
                new SecureRandom(),
                "key",
                new byte[] { 1 },
                "context-key");
        AwsNitroConfidentialRuntimeVerifier verifier =
                new AwsNitroConfidentialRuntimeVerifier(service);

        assertDoesNotThrow(verifier::verifyCurrentRuntime);
    }
}
