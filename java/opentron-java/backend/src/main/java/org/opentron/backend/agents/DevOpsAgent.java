package org.opentron.backend.agents;

import java.util.*;

public class DevOpsAgent extends SpecializedAgent {
    public DevOpsAgent(AgentLLMBridge llmBridge) {
        super(llmBridge);
        this.name   = "DevOps";
        this.skills = Arrays.asList("performance_monitoring", "log_aggregation", "metrics_collection",
            "alerting", "health_checks", "capacity_planning",
            "resource_optimization", "skill_synchronization",
            "web_research", "api_integration", "fetch_external_content");
    }

    @Override public Object process(AgentMessage msg) {
        long start = System.currentTimeMillis();
        @SuppressWarnings("unchecked") Map<String, Object> p = (Map<String, Object>) msg.payload();
        Map<String, Object> r = invokeScopedLLM("DevOps monitoring expert",
            str(p, "task", "request"), str(p, "relevant_context", "context"),
            asStringList(p.get("focus")), asStringList(p.get("ignore")), asStringList(p.get("constraints")),
            "devops", msg.streamCallback());
        this.lastExecutedTime = System.currentTimeMillis() - start;
        return handleEmptyResponse(r, "DevOps");
    }
    private String str(Map<String, Object> p, String k1, String k2) {
        return String.valueOf(p.getOrDefault(k1, p.getOrDefault(k2, "")));
    }
}
