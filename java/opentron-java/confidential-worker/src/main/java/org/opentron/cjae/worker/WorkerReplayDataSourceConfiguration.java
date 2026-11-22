package org.opentron.cjae.worker;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("gateway-oidc")
@ConditionalOnProperty(name = "confidential.gateway.enabled", havingValue = "true")
public class WorkerReplayDataSourceConfiguration {
    @Bean(destroyMethod = "close")
    public HikariDataSource replayDataSource(
            @Value("${confidential.gateway.replay.jdbc-url}") String jdbcUrl,
            @Value("${confidential.gateway.replay.username}") String username,
            @Value("${confidential.gateway.replay.password}") String password) {
        String normalizedUrl = jdbcUrl.toLowerCase(java.util.Locale.ROOT);
        if (!jdbcUrl.startsWith("jdbc:postgresql://")
                || !normalizedUrl.contains("sslmode=verify-full")
                || !normalizedUrl.contains("sslrootcert=")
                || username.isBlank() || password.isBlank()) {
            throw new IllegalStateException(
                    "PostgreSQL replay protection requires credentials and a JDBC URL with sslmode=verify-full and sslrootcert.");
        }

        HikariConfig config = new HikariConfig();
        config.setPoolName("cjae-context-replay");
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(8);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(10_000);
        config.setInitializationFailTimeout(10_000);
        return new HikariDataSource(config);
    }
}
