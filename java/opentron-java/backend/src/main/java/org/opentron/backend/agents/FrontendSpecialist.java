package org.opentron.backend.agents;

import java.util.*;

public class FrontendSpecialist extends SpecializedAgent {
    public FrontendSpecialist(AgentLLMBridge llmBridge) {
        super(llmBridge);
        this.name   = "Frontend";
        this.skills = Arrays.asList("react_optimization", "component_design", "state_management",
            "performance_tuning", "css_optimization", "accessibility",
            "responsive_design", "bundle_optimization",
            "web_research", "api_integration", "fetch_external_content");
    }

    @Override public Object process(AgentMessage msg) {
        long start = System.currentTimeMillis();
        @SuppressWarnings("unchecked") Map<String, Object> p = (Map<String, Object>) msg.payload();
        Map<String, Object> r = invokeScopedLLM("frontend React expert",
            str(p, "task", "request"), str(p, "relevant_context", "context"),
            asStringList(p.get("focus")), asStringList(p.get("ignore")), asStringList(p.get("constraints")),
            "frontend", msg.streamCallback());
        this.lastExecutedTime = System.currentTimeMillis() - start;
        return handleEmptyResponse(r, "Frontend");
    }
    private String str(Map<String, Object> p, String k1, String k2) {
        return String.valueOf(p.getOrDefault(k1, p.getOrDefault(k2, "")));
    }
}
