package org.opentron.backend.security.confidential.gateway.aws;

import java.net.InetAddress;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundleKey;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.ssl.SslOptions;
import org.springframework.boot.ssl.SslStoreBundle;
import org.springframework.boot.ssl.NoSuchSslBundleException;
import org.springframework.boot.web.server.Ssl;
import org.springframework.boot.web.servlet.server.ConfigurableServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("aws-nitro & gateway-oidc & !gcp-cvm")
@ConditionalOnProperty(name = "confidential.gateway.enabled", havingValue = "true")
public class AwsNitroGatewayTlsConfiguration {
    private static final String SSL_BUNDLE_NAME = "cjae-nitro-gateway";

    @Bean
    public AwsNitroTlsKeyStoreProvider awsNitroTlsKeyStoreProvider(
            AwsNitroEnclaveKeyReleaseService keyReleaseService,
            @Value("${confidential.aws.nitro.tls.keystore-ciphertext-base64}")
                    String encryptedKeyStore) {
        return new AwsNitroTlsKeyStoreProvider(keyReleaseService, encryptedKeyStore);
    }

    @Bean
    public SslBundle awsNitroGatewaySslBundle(
            AwsNitroTlsKeyStoreProvider keyStoreProvider) {
        SslStoreBundle stores =
                SslStoreBundle.of(keyStoreProvider.getKeyStore(), "", null);
        SslOptions options = SslOptions.of(
                new String[0], new String[] {"TLSv1.3", "TLSv1.2"});
        return SslBundle.of(stores, SslBundleKey.of(""), options);
    }

    @Bean
    public WebServerFactoryCustomizer<ConfigurableServletWebServerFactory>
            awsNitroGatewayTlsCustomizer(
                    SslBundle awsNitroGatewaySslBundle,
                    @Value("${confidential.aws.nitro.gateway.tls-port:8443}") int tlsPort) {
        if (tlsPort < 1 || tlsPort > 65535) {
            throw new IllegalStateException("AWS Nitro gateway TLS port is invalid.");
        }
        return factory -> {
            SslBundles bundles = name -> {
                if (!SSL_BUNDLE_NAME.equals(name)) {
                    throw new NoSuchSslBundleException(
                            name, "Only the configured CJAE TLS bundle is available.");
                }
                return awsNitroGatewaySslBundle;
            };
            factory.setSslBundles(bundles);
            Ssl ssl = Ssl.forBundle(SSL_BUNDLE_NAME);
            ssl.setEnabled(true);
            factory.setSsl(ssl);
            factory.setPort(tlsPort);
            factory.setAddress(InetAddress.getLoopbackAddress());
        };
    }
}
