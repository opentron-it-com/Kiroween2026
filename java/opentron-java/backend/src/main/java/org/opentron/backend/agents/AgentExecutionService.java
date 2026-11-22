package org.opentron.backend.agents;

import org.opentron.backend.security.confidential.ConfidentialAI;
import org.springframework.stereotype.Service;

@Service
public class AgentExecutionService {
    @ConfidentialAI
    public Object execute(SpecializedAgent agent, AgentMessage message) {
        if (agent == null) {
            throw new IllegalArgumentException("The requested agent is not available.");
        }
        return agent.process(message);
    }
}
