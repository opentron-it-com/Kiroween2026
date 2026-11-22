package org.opentron.backend.services;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * NetworkPolicyService - central source of truth for whether outbound internet
 * access to cloud providers is allowed. Reads default from configuration and
 * allows runtime override via setter (used by SettingsController).
 */
@Service
public class NetworkPolicyService {

    private static final Path POLICY_FILE = Path.of(System.getProperty("user.home"), ".opentron", "network-policy.properties");
    private volatile boolean allowInternet;

    public NetworkPolicyService(@Value("${security.allow_internet:false}") boolean allowInternet) {
        this.allowInternet = loadPersistedPolicy(allowInternet);
    }

    public boolean isInternetAllowed() {
        return allowInternet;
    }

    public void setAllowInternet(boolean allowInternet) {
        this.allowInternet = allowInternet;
        persistPolicy(allowInternet);
    }

    private boolean loadPersistedPolicy(boolean defaultValue) {
        try {
            if (Files.exists(POLICY_FILE)) {
                String value = Files.readString(POLICY_FILE).trim();
                if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
                    return Boolean.parseBoolean(value);
                }
            }
        } catch (IOException ignored) {
            // Keep the configured default when local persistence is unavailable.
        }
        return defaultValue;
    }

    private void persistPolicy(boolean value) {
        try {
            Files.createDirectories(POLICY_FILE.getParent());
            Files.writeString(POLICY_FILE, Boolean.toString(value) + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException ignored) {
            // The in-memory value remains active if persistence is unavailable.
        }
    }
}
