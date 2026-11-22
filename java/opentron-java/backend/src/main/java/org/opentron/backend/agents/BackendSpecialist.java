package org.opentron.backend.agents;

import java.util.*;

public class BackendSpecialist extends SpecializedAgent {
    public BackendSpecialist(AgentLLMBridge llmBridge) {
        super(llmBridge);
        this.name   = "Backend";
        this.skills = Arrays.asList("java_optimization", "spring_boot_configuration",
            "database_query_optimization", "api_design", "persistence_layer",
            "cache_optimization", "concurrent_programming", "error_handling",
            "web_research", "api_integration", "fetch_external_content");
    }

    @Override public Object process(AgentMessage msg) {
        long start = System.currentTimeMillis();
        @SuppressWarnings("unchecked") Map<String, Object> p = (Map<String, Object>) msg.payload();
        Map<String, Object> r = invokeScopedLLM("backend optimization expert",
            str(p, "task", "request"), str(p, "relevant_context", "context"),
            asl(p.get("focus")), asl(p.get("ignore")), asl(p.get("constraints")),
            "backend", msg.streamCallback());
        this.lastExecutedTime = System.currentTimeMillis() - start;
        return handleEmptyResponse(r, "Backend");
    }
    private String str(Map<String, Object> p, String k1, String k2) {
        return String.valueOf(p.getOrDefault(k1, p.getOrDefault(k2, "")));
    }
    private List<String> asl(Object v) { return asStringList(v); }
}
