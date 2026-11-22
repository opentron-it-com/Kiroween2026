package org.opentron.backend.security.confidential.gateway.aws;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.DERNull;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.RSAESOAEPparams;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.cms.CMSAlgorithm;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSEnvelopedDataGenerator;
import org.bouncycastle.cms.jcajce.JceCMSContentEncryptorBuilder;
import org.bouncycastle.cms.jcajce.JceKeyTransRecipientInfoGenerator;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Test;
import org.opentron.backend.security.confidential.gateway.SecureContextKey;

class AwsNitroEnclaveKeyReleaseServiceTest {
    private static final byte[] AES_KEY = new byte[32];
    private static final byte[] CIPHERTEXT_BLOB = "kms-wrapped-context-key".getBytes(
            StandardCharsets.UTF_8);

    @Test
    void createsFreshNonceAndRecipientKeyAndUsesKmsRecipientResponseForKey() throws Exception {
        Arrays.fill(AES_KEY, (byte) 0x5a);
        List<byte[]> seenNonces = new ArrayList<>();
        List<byte[]> seenPublicKeys = new ArrayList<>();
        List<byte[]> seenDocuments = new ArrayList<>();
        List<AwsNitroKmsDecryptRequest> requests = new ArrayList<>();

        AwsNitroEnclaveAttestationSource nsmTestBoundary = (nonce, publicKey) -> {
            assertEquals(32, nonce.length);
            seenNonces.add(nonce.clone());
            seenPublicKeys.add(publicKey.clone());
            return fixtureDocument(nonce, publicKey);
        };
        ParentVsockKmsTransport kmsTestBoundary = request -> {
            requests.add(request);
            seenDocuments.add(request.recipient().attestationDocument());
            return recipientResponse(seenPublicKeys.get(requests.size() - 1), AES_KEY);
        };
        AwsNitroEnclaveKeyReleaseService release = service(nsmTestBoundary, kmsTestBoundary);
        AwsNitroAttestedSecureContextKeyProvider provider =
                new AwsNitroAttestedSecureContextKeyProvider(release, "context-key");

        SecureContextKey key = provider.currentKey();
        assertEquals("context-key", key.id());
        assertArrayEquals(AES_KEY, key.key().getEncoded());
        assertEquals(1, requests.size());
        AwsNitroKmsDecryptRequest request = requests.get(0);
        assertEquals("arn:aws:kms:us-east-1:123456789012:key/test", request.keyId());
        assertArrayEquals(CIPHERTEXT_BLOB, request.ciphertextBlob());
        assertEquals(
                AwsNitroKmsDecryptRequest.RSAES_OAEP_SHA_256,
                request.recipient().keyEncryptionAlgorithm());
        assertArrayEquals(fixtureDocument(seenNonces.get(0), seenPublicKeys.get(0)),
                request.recipient().attestationDocument());
        assertArrayEquals(seenDocuments.get(0), request.recipient().attestationDocument());

        PublicKey recipientPublicKey = KeyFactory.getInstance("RSA").generatePublic(
                new X509EncodedKeySpec(seenPublicKeys.get(0)));
        assertEquals("RSA", recipientPublicKey.getAlgorithm());
        assertEquals(2048, ((java.security.interfaces.RSAPublicKey) recipientPublicKey)
                .getModulus().bitLength());
        assertTrue(provider.findKey("context-key").isPresent());
        assertTrue(provider.findKey("other-key").isEmpty());
        assertEquals(2, seenNonces.size());
        assertNotEquals(Arrays.toString(seenNonces.get(0)), Arrays.toString(seenNonces.get(1)));
        assertFalse(Arrays.equals(seenPublicKeys.get(0), seenPublicKeys.get(1)));
    }

    @Test
    void releasesArbitraryBoundedSecretForTheEnclaveTlsIdentity() {
        byte[] tlsSecret = "encrypted-tls-keystore-payload".getBytes(StandardCharsets.UTF_8);
        AwsNitroEnclaveKeyReleaseService release = service(
                (nonce, publicKey) -> new byte[] { 1 },
                request -> recipientResponse(latestPublicKey.get(), tlsSecret));

        byte[] plaintext = release.releaseSecret("tls-ciphertext".getBytes(StandardCharsets.UTF_8));

        assertArrayEquals(tlsSecret, plaintext);
        Arrays.fill(plaintext, (byte) 0);
    }

    @Test
    void loadsKmsReleasedPasswordlessPkcs12IdentityInMemory() throws Exception {
        byte[] pkcs12 = createPasswordlessPkcs12();
        byte[] encrypted = "kms-ciphertext".getBytes(StandardCharsets.UTF_8);
        AwsNitroEnclaveKeyReleaseService release = service(
                (nonce, publicKey) -> new byte[] { 1 },
                request -> recipientResponse(latestPublicKey.get(), pkcs12));

        AwsNitroTlsKeyStoreProvider provider = new AwsNitroTlsKeyStoreProvider(
                release, Base64.getEncoder().encodeToString(encrypted));
        AwsNitroGatewayTlsConfiguration tlsConfiguration =
                new AwsNitroGatewayTlsConfiguration();
        var sslBundle = tlsConfiguration.awsNitroGatewaySslBundle(provider);

        assertTrue(provider.getKeyStore().isKeyEntry("tls"));
        assertTrue(provider.getKeyStore().getCertificate("tls") instanceof X509Certificate);
        assertTrue(sslBundle.createSslContext() != null);
        Arrays.fill(pkcs12, (byte) 0);
    }

    @Test
    void rejectsMissingOrMalformedRecipientCiphertext() {
        assertThrows(NullPointerException.class, () -> new AwsNitroKmsDecryptResponse(null));
        assertThrows(IllegalArgumentException.class,
                () -> new AwsNitroKmsDecryptResponse(new byte[0]));
        assertThrows(IllegalArgumentException.class,
                () -> new AwsNitroKmsDecryptResponse(
                        new byte[AwsNitroKmsDecryptResponse.MAX_CIPHERTEXT_FOR_RECIPIENT_BYTES + 1]));

        AwsNitroEnclaveKeyReleaseService missingResponse = service(
                (nonce, publicKey) -> new byte[] { 1 },
                request -> null);
        assertThrows(AwsNitroEnclaveException.class, missingResponse::releaseContextKey);
    }

    @Test
    void rejectsRecipientCiphertextThatCannotBeUnwrappedByThisEnclaveKey() {
        AwsNitroEnclaveKeyReleaseService service = service(
                (nonce, publicKey) -> new byte[] { 7 },
                request -> new AwsNitroKmsDecryptResponse(new byte[256]));
        assertThrows(AwsNitroEnclaveException.class, service::releaseContextKey);
    }

    @Test
    void rejectsNonAesPlaintextAfterRecipientUnwrap() {
        AwsNitroEnclaveKeyReleaseService service = service(
                (nonce, publicKey) -> new byte[] { 7 },
                request -> recipientResponse(latestPublicKey.get(), new byte[15]));
        assertThrows(AwsNitroEnclaveException.class, service::releaseContextKey);
    }

    @Test
    void rejectsAesKeysOtherThan256Bits() {
        for (int length : List.of(16, 24)) {
            AwsNitroEnclaveKeyReleaseService service = service(
                    (nonce, publicKey) -> new byte[] { 7 },
                    request -> recipientResponse(latestPublicKey.get(), new byte[length]));
            assertThrows(AwsNitroEnclaveException.class, service::releaseContextKey);
        }
    }

    @Test
    void failsClosedWhenNsmOrParentVsockKmsTransportFails() {
        AwsNitroEnclaveKeyReleaseService nsmFailure = service(
                (nonce, publicKey) -> {
                    throw new IllegalStateException("NSM unavailable");
                },
                request -> null);
        assertThrows(AwsNitroEnclaveException.class, nsmFailure::releaseContextKey);

        AwsNitroEnclaveKeyReleaseService transportFailure = service(
                (nonce, publicKey) -> new byte[] { 1 },
                request -> {
                    throw new IllegalStateException("vsock unavailable");
                });
        assertThrows(AwsNitroEnclaveException.class, transportFailure::releaseContextKey);
    }

    @Test
    void boundsRequestAndDefensivelyCopiesRecipientData() {
        byte[] ciphertext = { 1, 2 };
        byte[] document = { 3, 4 };
        AwsNitroKmsDecryptRequest request = new AwsNitroKmsDecryptRequest(
                "key",
                ciphertext,
                new AwsNitroKmsRecipient(
                        document, AwsNitroKmsDecryptRequest.RSAES_OAEP_SHA_256));
        ciphertext[0] = 9;
        document[0] = 9;
        assertArrayEquals(new byte[] { 1, 2 }, request.ciphertextBlob());
        assertArrayEquals(new byte[] { 3, 4 }, request.recipient().attestationDocument());
        assertThrows(IllegalArgumentException.class, () -> new AwsNitroKmsDecryptRequest(
                "key",
                new byte[AwsNitroKmsDecryptRequest.MAX_CIPHERTEXT_BLOB_BYTES + 1],
                new AwsNitroKmsRecipient(
                        new byte[] { 1 }, AwsNitroKmsDecryptRequest.RSAES_OAEP_SHA_256)));
        assertThrows(IllegalArgumentException.class,
                () -> new AwsNitroKmsRecipient(new byte[] { 1 }, "PLAINTEXT"));
    }

    private static final ThreadLocal<byte[]> latestPublicKey = new ThreadLocal<>();

    private static AwsNitroEnclaveKeyReleaseService service(
            AwsNitroEnclaveAttestationSource attestationSource,
            ParentVsockKmsTransport kmsTransport) {
        AwsNitroEnclaveAttestationSource capturingSource = (nonce, publicKey) -> {
            latestPublicKey.set(publicKey.clone());
            return attestationSource.attest(nonce, publicKey);
        };
        return new AwsNitroEnclaveKeyReleaseService(
                capturingSource,
                kmsTransport,
                new SecureRandom(),
                "arn:aws:kms:us-east-1:123456789012:key/test",
                CIPHERTEXT_BLOB,
                "context-key");
    }

    private static byte[] fixtureDocument(byte[] nonce, byte[] publicKey) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(nonce);
        out.writeBytes(publicKey);
        return out.toByteArray();
    }

    private static byte[] createPasswordlessPkcs12() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        Instant now = Instant.now();
        X500Name name = new X500Name("CN=CJAE Test");
        var certificateBuilder = new JcaX509v3CertificateBuilder(
                name,
                BigInteger.ONE,
                Date.from(now.minusSeconds(60)),
                Date.from(now.plusSeconds(3600)),
                name,
                pair.getPublic());
        X509Certificate certificate = new JcaX509CertificateConverter().getCertificate(
                certificateBuilder.build(
                        new JcaContentSignerBuilder("SHA256withRSA")
                                .build(pair.getPrivate())));
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, new char[0]);
        keyStore.setKeyEntry("tls", pair.getPrivate(), new char[0],
                new java.security.cert.Certificate[] {certificate});
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        keyStore.store(output, new char[0]);
        return output.toByteArray();
    }

    private static AwsNitroKmsDecryptResponse recipientResponse(
            byte[] publicKey,
            byte[] plaintext) {
        try {
            PublicKey recipient = KeyFactory.getInstance("RSA").generatePublic(
                    new X509EncodedKeySpec(publicKey));
            CMSEnvelopedDataGenerator generator = new CMSEnvelopedDataGenerator();
            generator.addRecipientInfoGenerator(new JceKeyTransRecipientInfoGenerator(
                    new byte[] { 1 },
                    rsaOaepSha256Identifier(),
                    recipient));
            var encryptor =
                    new JceCMSContentEncryptorBuilder(CMSAlgorithm.AES256_CBC).build();
            byte[] encodedEnvelope = generator
                    .generate(new CMSProcessableByteArray(plaintext), encryptor)
                    .getEncoded();
            return new AwsNitroKmsDecryptResponse(encodedEnvelope);
        } catch (Exception e) {
            throw new AssertionError("Test KMS transport could not form recipient ciphertext.", e);
        }
    }

    private static AlgorithmIdentifier rsaOaepSha256Identifier() {
        AlgorithmIdentifier sha256 =
                new AlgorithmIdentifier(NISTObjectIdentifiers.id_sha256, DERNull.INSTANCE);
        RSAESOAEPparams parameters = new RSAESOAEPparams(
                sha256,
                new AlgorithmIdentifier(PKCSObjectIdentifiers.id_mgf1, sha256),
                new AlgorithmIdentifier(
                        PKCSObjectIdentifiers.id_pSpecified, new DEROctetString(new byte[0])));
        return new AlgorithmIdentifier(PKCSObjectIdentifiers.id_RSAES_OAEP, parameters);
    }
}
