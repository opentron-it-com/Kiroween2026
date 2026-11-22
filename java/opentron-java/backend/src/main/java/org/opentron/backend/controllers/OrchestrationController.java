package org.opentron.backend.controllers;

import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;
import org.opentron.backend.agents.SkillBasedOrchestrator;

/**
 * OpenClaw-based orchestration endpoints
 * Routes tasks to specialized agents based on skills
 */
@RestController
@RequestMapping("/v1/orchestrate")
public class OrchestrationController {

        private static final Logger logger = LoggerFactory.getLogger(OrchestrationController.class);

    private final SkillBasedOrchestrator orchestrator;

    public OrchestrationController(SkillBasedOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    /**
     * POST /v1/orchestrate/task
     * Skill-based task orchestration
     * 
     * Request:
     * {
     *   "task": "optimize the cache layer for high throughput scenarios",
     *   "context": "we have 2M daily requests and database queries are the bottleneck"
     * }
     * 
     * Response:
     * {
     *   "status": "completed",
     *   "code": [
     *     {
     *       "agent": "backend",
     *       "match_score": 0.95,
     *       "recommendations": "..."
     *     }
     *   ],
     *   "frontend": [],
     *   "webdesign": [],
     *   "qa": [],
     *   "devops": [],
     *   "agents_used": ["backend(CODE)"],
     *   "elapsed_ms": 2340
     * }
     */
    @PostMapping("/task")
    public Map<String, Object> orchestrateTask(
            @RequestParam String task,
            @RequestParam(required = false, defaultValue = "") String context) {

        logger.info("Orchestrating task: {}", task);

        try {
            Map<String, Object> result = orchestrator.orchestrateTask(task, context);
            result.put("status", "success");
            return result;
        } catch (Exception e) {
                        logger.error("Error orchestrating task {}", task, e);
            return Map.of(
                    "status", "error",
                    "error", e.getMessage(),
                    "task", task
            );
        }
    }

    /**
     * POST /v1/orchestrate/batch
     * Orchestrate multiple tasks in sequence
     * 
     * Request:
     * {
     *   "tasks": [
     *     { "task": "optimize cache", "context": "..." },
     *     { "task": "review component design", "context": "..." }
     *   ]
     * }
     */
        @PostMapping("/batch")
        public Map<String, Object> orchestrateBatch(@RequestBody org.opentron.backend.dto.OrchestrationBatchRequest request) {
                List<org.opentron.backend.dto.OrchestrationTaskRequest> tasks = request.getTasks();
                List<Map<String, Object>> results = new ArrayList<>();

                long start = System.currentTimeMillis();

                for (org.opentron.backend.dto.OrchestrationTaskRequest taskData : tasks) {
                        String task = taskData.getTask();
                        String context = taskData.getContext() == null ? "" : taskData.getContext();

                        Map<String, Object> result = orchestrator.orchestrateTask(task, context);
                        results.add(result);
                }

                long elapsed = System.currentTimeMillis() - start;

                return Map.of(
                                "status", "completed",
                                "tasks_processed", tasks.size(),
                                "results", results,
                                "total_elapsed_ms", elapsed
                );
        }

    /**
     * GET /v1/orchestrate/agents
     * List all available agents and their skills
     */
    @GetMapping("/agents")
    public Map<String, Object> listAgents() {
        Map<String, Object> agents = new LinkedHashMap<>();

        agents.put("backend", Map.of(
                "domain", "CODE",
                "skills", Arrays.asList(
                        "java_optimization",
                        "spring_boot_configuration",
                        "database_query_optimization",
                        "cache_optimization"
                )
        ));

        agents.put("frontend", Map.of(
                "domain", "FRONTEND/WEBDESIGN",
                "skills", Arrays.asList(
                        "react_optimization",
                        "component_design",
                        "css_optimization",
                        "responsive_design"
                )
        ));

        agents.put("qa", Map.of(
                "domain", "QA",
                "skills", Arrays.asList(
                        "unit_testing",
                        "integration_testing",
                        "debugging",
                        "code_review"
                )
        ));

        agents.put("devops", Map.of(
                "domain", "DEVOPS",
                "skills", Arrays.asList(
                        "performance_monitoring",
                        "metrics_collection",
                        "health_checks",
                        "alerting"
                )
        ));

        // NOTE: this "knowledge" entry was missing entirely - DomainDetector has always been
        // able to return ["knowledge"] as the fallback domain (and, since the conceptual-intent
        // fix, routes purely explanatory/comparison questions here even when they mention a
        // backend/frontend/etc. technology name), but this legacy catalog never listed it, so
        // API consumers of GET /v1/orchestrate/agents had no way to discover the Knowledge
        // specialist existed. Skills mirror MultiAgentCoordinator.KnowledgeAgent.
        agents.put("knowledge", Map.of(
                "domain", "KNOWLEDGE",
                "skills", Arrays.asList(
                        "concept_explanation",
                        "general_qa",
                        "technical_comparison",
                        "cross_domain_synthesis",
                        "search_memory",
                        "web_research"
                )
        ));

        return Map.of(
                "agents", agents,
                "total", agents.size(),
                "domains", Arrays.asList("CODE", "FRONTEND", "WEBDESIGN", "QA", "DEVOPS", "KNOWLEDGE")
        );
    }

    /**
     * GET /v1/orchestrate/detect-domains
     * Lightweight, no-LLM-call preview of which specialist domain(s) a piece
     * of text spans. Backed by the same DomainDetector used by
     * MultiAgentCoordinator.CoordinatorAgent.analyzeRequest() to pick which
     * agents to dispatch, so this endpoint's answer always matches what a
     * real /v1/agents/coordinate call would actually do - it's meant for the
     * frontend to preview that decision (and suggest enabling Tron mode)
     * before spending a real, multi-agent coordinator run.
     */
    @GetMapping("/detect-domains")
    public Map<String, Object> detectDomains(
            @RequestParam String request,
            @RequestParam(required = false, defaultValue = "") String context) {

        List<String> domains = org.opentron.backend.orchestration.DomainDetector.detectDomains(request, context);
        boolean multiDomain = domains.size() > 1;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("domains", domains);
        result.put("multi_domain", multiDomain);
        result.put("agent_count", domains.size());
        return result;
    }

    /**
     * GET /v1/orchestrate/domains
     * List skill domains and example keywords
     */
    @GetMapping("/domains")
    public Map<String, Object> listDomains() {
        Map<String, List<String>> domains = new LinkedHashMap<>();

        domains.put("CODE", Arrays.asList(
                "java", "spring", "backend", "cache", "optimize", "performance", "database"
        ));

        domains.put("FRONTEND", Arrays.asList(
                "react", "component", "ui", "performance", "bundle", "state management"
        ));

        domains.put("WEBDESIGN", Arrays.asList(
                "design", "layout", "accessibility", "ux", "responsive", "css"
        ));

        domains.put("QA", Arrays.asList(
                "test", "debug", "review", "bug", "qa", "coverage"
        ));

        domains.put("DEVOPS", Arrays.asList(
                "monitor", "metric", "health", "alert", "performance", "observability"
        ));

        // NOTE: this was missing KNOWLEDGE entirely, even though it's DomainDetector's fallback
        // domain and (since the conceptual-intent fix) the explicit target for explanatory/
        // comparison questions. Keywords mirror DomainDetector.CONCEPTUAL_INTENT_PATTERN.
        domains.put("KNOWLEDGE", Arrays.asList(
                "summarize", "explain", "describe", "compare", "difference", "what is", "overview"
        ));

        return Map.of(
                "domains", domains,
                "strategy", "Tasks are analyzed for keywords and routed to matching domains (1-2 agents per domain)"
        );
    }
}
