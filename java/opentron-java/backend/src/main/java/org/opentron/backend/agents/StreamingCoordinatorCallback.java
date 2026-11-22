package org.opentron.backend.agents;

import java.util.Map;

public interface StreamingCoordinatorCallback {
    void onAgentStart(String agent, String model);
    void onAgentChunk(String agent, String chunk, String model);
    void onAgentDone(String agent, Map<String, Object> result, String model);
    void onAgentError(String agent, String error, String model);
    void onStatus(String status);
    default void onAgentModelResolved(String agent, String model) {}
    default void onAgentStep(String agent, String message) {}
}
