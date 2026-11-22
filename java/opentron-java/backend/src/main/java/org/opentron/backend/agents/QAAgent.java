package org.opentron.backend.agents;

import java.util.*;

public class QAAgent extends SpecializedAgent {
    public QAAgent(AgentLLMBridge llmBridge) {
        super(llmBridge);
        this.name   = "QA";
        this.skills = Arrays.asList("unit_testing", "integration_testing", "debugging",
            "code_review", "regression_testing", "performance_testing",
            "security_testing", "compatibility_testing",
            "web_research", "api_integration", "fetch_external_content");
    }

    @Override public Object process(AgentMessage msg) {
        long start = System.currentTimeMillis();
        @SuppressWarnings("unchecked") Map<String, Object> p = (Map<String, Object>) msg.payload();
        Map<String, Object> r = invokeScopedLLM("QA testing expert",
            str(p, "task", "request"), str(p, "relevant_context", "context"),
            asStringList(p.get("focus")), asStringList(p.get("ignore")), asStringList(p.get("constraints")),
            "qa", msg.streamCallback());
        this.lastExecutedTime = System.currentTimeMillis() - start;
        return handleEmptyResponse(r, "QA");
    }
    private String str(Map<String, Object> p, String k1, String k2) {
        return String.valueOf(p.getOrDefault(k1, p.getOrDefault(k2, "")));
    }
}
