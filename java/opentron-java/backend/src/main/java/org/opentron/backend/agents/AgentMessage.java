package org.opentron.backend.agents;

public record AgentMessage(
        String targetAgent,
        String type,
        Object payload,
        String replyTo,
        StreamingCoordinatorCallback streamCallback,
        long timestamp
) {
    public AgentMessage(String targetAgent, String type, Object payload) {
        this(targetAgent, type, payload, null, null, System.currentTimeMillis());
    }

    public AgentMessage(String targetAgent, String type, Object payload, String replyTo) {
        this(targetAgent, type, payload, replyTo, null, System.currentTimeMillis());
    }

    public AgentMessage(String targetAgent, String type, Object payload, StreamingCoordinatorCallback streamCallback) {
        this(targetAgent, type, payload, null, streamCallback, System.currentTimeMillis());
    }
}
