package org.opentron.backend.storage.entities;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * JPA Entity for storing trace logs from agent execution
 * Extended with token tracking and cost accounting for hybrid Ollama/Claude orchestration
 */
@Entity
@Table(name = "trace_logs")
public class TraceLog {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false, length = 255)
    private String agent;
    
    @Column(nullable = false)
    private LocalDateTime timestamp = LocalDateTime.now();
    
    @Column(columnDefinition = "TEXT")
    private String input;
    
    @Column(columnDefinition = "TEXT")
    private String output;
    
    @Column(name = "tools_used", columnDefinition = "TEXT")
    private String toolsUsed;
    
    @Column(name = "duration_ms")
    private Integer durationMs;
    
    @Column(name = "is_compressed", columnDefinition = "BOOLEAN DEFAULT FALSE")
    private Boolean isCompressed = false;
    
    @Column(name = "compressed_data", nullable = true)
    private byte[] compressedData = null;
    
    // Token tracking and cost accounting
    @Column(name = "model_name")
    private String modelName;
    
    @Column(name = "model_provider")
    private String modelProvider; // "ollama", "anthropic", "google", etc.
    
    @Column(name = "input_tokens")
    private Integer inputTokens = 0;
    
    @Column(name = "output_tokens")
    private Integer outputTokens = 0;
    
    @Column(name = "cache_creation_tokens")
    private Integer cacheCreationTokens = 0;
    
    @Column(name = "cache_read_tokens")
    private Integer cacheReadTokens = 0;
    
    @Column(name = "cost_usd")
    private Double costUsd = 0.0;
    
    @Column(name = "classification")
    private String classification; // "SIMPLE", "COMPLEX", "UNKNOWN"
    
    @Column(name = "routing_reason", columnDefinition = "TEXT")
    private String routingReason; // Why this model was chosen
    
    // Constructors
    public TraceLog() {
        this.timestamp = LocalDateTime.now();
        this.compressedData = null;
    }
    
    public TraceLog(String agent, String input, String output, Integer durationMs) {
        this.agent = agent;
        this.input = input;
        this.output = output;
        this.durationMs = durationMs;
        this.timestamp = LocalDateTime.now();
        this.compressedData = null;
    }
    
    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    
    public String getAgent() { return agent; }
    public void setAgent(String agent) { this.agent = agent; }
    
    public String getInput() { return input; }
    public void setInput(String input) { this.input = input; }
    
    public String getOutput() { return output; }
    public void setOutput(String output) { this.output = output; }
    
    public String getToolsUsed() { return toolsUsed; }
    public void setToolsUsed(String toolsUsed) { this.toolsUsed = toolsUsed; }
    
    public Integer getDurationMs() { return durationMs; }
    public void setDurationMs(Integer durationMs) { this.durationMs = durationMs; }
    
    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }
    
    public Boolean getIsCompressed() { return isCompressed; }
    public void setIsCompressed(Boolean isCompressed) { this.isCompressed = isCompressed; }
    
    public byte[] getCompressedData() { return compressedData; }
    public void setCompressedData(byte[] compressedData) { 
        this.compressedData = compressedData;
        if (compressedData != null) {
            this.isCompressed = true;
        }
    }
    
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    
    public String getModelProvider() { return modelProvider; }
    public void setModelProvider(String modelProvider) { this.modelProvider = modelProvider; }
    
    public Integer getInputTokens() { return inputTokens; }
    public void setInputTokens(Integer inputTokens) { this.inputTokens = inputTokens; }
    
    public Integer getOutputTokens() { return outputTokens; }
    public void setOutputTokens(Integer outputTokens) { this.outputTokens = outputTokens; }
    
    public Integer getCacheCreationTokens() { return cacheCreationTokens; }
    public void setCacheCreationTokens(Integer cacheCreationTokens) { this.cacheCreationTokens = cacheCreationTokens; }
    
    public Integer getCacheReadTokens() { return cacheReadTokens; }
    public void setCacheReadTokens(Integer cacheReadTokens) { this.cacheReadTokens = cacheReadTokens; }
    
    public Double getCostUsd() { return costUsd; }
    public void setCostUsd(Double costUsd) { this.costUsd = costUsd; }
    
    public String getClassification() { return classification; }
    public void setClassification(String classification) { this.classification = classification; }
    
    public String getRoutingReason() { return routingReason; }
    public void setRoutingReason(String routingReason) { this.routingReason = routingReason; }
}
