package org.opentron.backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonAlias;

/**
 * Metadata about which agent is currently processing a request.
 * Returned in streaming responses so the frontend can display agent attribution.
 */
public class AgentAttribution {
    @JsonProperty("agent_name")
    @JsonAlias({"agent", "name"})
    private String agentName;
    
    @JsonProperty("agent_status")
    @JsonAlias({"status", "agentStatus"})
    private String agentStatus; // "thinking", "responding", "done"
    
    @JsonProperty("agent_model")
    @JsonAlias({"model", "agentModel"})
    private String agentModel; // e.g., "qwen2.5-coder:7b", "gemma3:4b"
    
    @JsonProperty("thinking_phase")
    @JsonAlias({"phase", "thinkingPhase"})
    private String thinkingPhase; // e.g., "analyzing_request", "searching_memory", "formulating_response"

    public AgentAttribution() {}

    public AgentAttribution(String agentName, String agentStatus) {
        this.agentName = agentName;
        this.agentStatus = agentStatus;
    }

    public AgentAttribution(String agentName, String agentStatus, String agentModel, String thinkingPhase) {
        this.agentName = agentName;
        this.agentStatus = agentStatus;
        this.agentModel = agentModel;
        this.thinkingPhase = thinkingPhase;
    }

    // Getters and setters
    public String getAgentName() { return agentName; }
    public void setAgentName(String agentName) { this.agentName = agentName; }

    public String getAgentStatus() { return agentStatus; }
    public void setAgentStatus(String agentStatus) { this.agentStatus = agentStatus; }

    public String getAgentModel() { return agentModel; }
    public void setAgentModel(String agentModel) { this.agentModel = agentModel; }

    public String getThinkingPhase() { return thinkingPhase; }
    public void setThinkingPhase(String thinkingPhase) { this.thinkingPhase = thinkingPhase; }
}
