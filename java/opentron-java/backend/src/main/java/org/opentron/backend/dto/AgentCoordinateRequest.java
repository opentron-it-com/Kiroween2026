package org.opentron.backend.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;

public class AgentCoordinateRequest {
    @NotBlank
    private String request;
    private String context;
    private Map<String, String> apiKeyOverrides;  // API keys from UI settings

    public AgentCoordinateRequest() {}

    public AgentCoordinateRequest(String request, String context) {
        this.request = request;
        this.context = context;
    }

    public AgentCoordinateRequest(String request, String context, Map<String, String> apiKeyOverrides) {
        this.request = request;
        this.context = context;
        this.apiKeyOverrides = apiKeyOverrides;
    }

    public String getRequest() {
        return request;
    }

    public void setRequest(String request) {
        this.request = request;
    }

    public String getContext() {
        return context;
    }

    public void setContext(String context) {
        this.context = context;
    }

    public Map<String, String> getApiKeyOverrides() {
        return apiKeyOverrides;
    }

    public void setApiKeyOverrides(Map<String, String> apiKeyOverrides) {
        this.apiKeyOverrides = apiKeyOverrides;
    }
}
