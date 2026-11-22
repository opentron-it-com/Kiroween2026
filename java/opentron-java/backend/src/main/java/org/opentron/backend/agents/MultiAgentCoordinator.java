package org.opentron.backend.agents;

import java.util.*;
import java.util.concurrent.*;
import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.opentron.backend.telemetry.TelemetryService;
import org.opentron.backend.util.CloudModelService;
import org.opentron.backend.util.OllamaCliService;
import org.opentron.backend.util.HuggingFaceService;
import org.opentron.backend.memory.MemoryService;
import org.opentron.backend.pricing.PricingService;
import org.opentron.backend.services.ModelSelectorService;
import org.opentron.backend.util.GeminiCodeModelService;
import org.opentron.backend.security.confidential.ConfidentialAI;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Multi-Agent Coordinator System (refactored)
 * This class now focuses on agent lifecycle and orchestration. Specialized
 * agent implementations live in their own classes under the same package.
 */
@Component
public class MultiAgentCoordinator {

    private static final Logger logger = LoggerFactory.getLogger(MultiAgentCoordinator.class);
    private final OllamaCliService ollamaService;
    private final HuggingFaceService huggingFaceService;
    private final CloudModelService cloudModelService;
    private final ModelSelectorService modelSelectorService;
    private final org.opentron.backend.services.WebSearchService webSearchService;
    // Package-visible (not private) like telemetryService below, so
    // CoordinatorAgent which receives this coordinator instance as a
    // parameter, can read pricing/provider-grouping data when recording 
    // per-provider cost telemetry.
    public final PricingService pricingService;

    @Autowired(required = false)
    public TelemetryService telemetryService;
    private final MemoryService memoryService;
    @SuppressWarnings("unused")
    @Autowired(required = false)
    private GeminiCodeModelService geminiService;

    private final Map<String, SpecializedAgent> agents = new ConcurrentHashMap<>();
    private final BlockingQueue<AgentMessage> messageQueue = new LinkedBlockingQueue<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public MultiAgentCoordinator(OllamaCliService ollamaService, HuggingFaceService huggingFaceService,
                                 ModelSelectorService modelSelectorService, CloudModelService cloudModelService,
                                 org.opentron.backend.services.WebSearchService webSearchService,
                                 PricingService pricingService, MemoryService memoryService) {
        this.ollamaService = ollamaService;
        this.huggingFaceService = huggingFaceService;
        this.modelSelectorService = modelSelectorService;
        this.cloudModelService = cloudModelService;
        this.webSearchService = webSearchService;
        this.pricingService = pricingService;
        this.memoryService = memoryService;
        try {
            initializeAgents();
            startMessageProcessor();
            logger.info("Initialized with intelligent model selection");
        } catch (Exception e) {
            logger.error("Initialization error", e);
            if (agents.isEmpty()) {
                initializeAgentsWithDefaults();
            }
        }
    }

    private void initializeAgentsWithDefaults() {
        logger.info("Using fallback initialization with default models");
        AgentLLMBridge coordinatorBridge = new AgentLLMBridge(
            ollamaService, huggingFaceService, cloudModelService, "qwen3:4b", null, webSearchService, "coordinator");
        AgentLLMBridge codeBridge = new AgentLLMBridge(
            ollamaService, huggingFaceService, cloudModelService, "qwen2.5-coder:7b", null, webSearchService, "backend");
        AgentLLMBridge frontendBridge = new AgentLLMBridge(
            ollamaService, huggingFaceService, cloudModelService, "qwen2.5-coder:7b", null, webSearchService, "frontend");
        AgentLLMBridge devopsBridge = new AgentLLMBridge(
            ollamaService, huggingFaceService, cloudModelService, "qwen2.5-coder:7b", null, webSearchService, "devops");
        AgentLLMBridge qaBridge = new AgentLLMBridge(
            ollamaService, huggingFaceService, cloudModelService, "qwen2.5-coder:7b", null, webSearchService, "qa");
        AgentLLMBridge knowledgeBridge = new AgentLLMBridge(
            ollamaService, huggingFaceService, cloudModelService, "gemma3:4b", null, webSearchService, "knowledge");
        agents.put("coordinator", new CoordinatorAgent(coordinatorBridge));
        agents.put("backend", new BackendSpecialist(codeBridge));
        agents.put("frontend", new FrontendSpecialist(frontendBridge));
        agents.put("devops", new DevOpsAgent(devopsBridge));
        agents.put("qa", new QAAgent(qaBridge));
        agents.put("knowledge", new KnowledgeAgent(knowledgeBridge, memoryService));
    }

    private void initializeAgents() {
        initializeAgents(null);
    }

    private void initializeAgents(Map<String, String> apiKeyOverrides) {
        logger.info("Initializing with intelligent model selection...");

        String coordinatorModel = "qwen3:4b";
        String backendModel  = modelSelectorService.selectBestModel("backend",  apiKeyOverrides);
        String frontendModel = modelSelectorService.selectBestModel("frontend", apiKeyOverrides);
        String qaModel       = modelSelectorService.selectBestModel("qa",       apiKeyOverrides);
        String devopsModel   = modelSelectorService.selectBestModel("devops",   apiKeyOverrides);
        String knowledgeModel = modelSelectorService.selectBestModel("knowledge", apiKeyOverrides);

        logger.info("Model assignments: Backend={}, Frontend={}, QA={}, DevOps={}, Knowledge={}",
                backendModel, frontendModel, qaModel, devopsModel, knowledgeModel);

        AgentLLMBridge coordinatorBridge = new AgentLLMBridge(ollamaService, huggingFaceService, cloudModelService, coordinatorModel, apiKeyOverrides, webSearchService, "coordinator", pricingService);
        agents.put("coordinator", new CoordinatorAgent(coordinatorBridge));

        AgentLLMBridge backendBridge = new AgentLLMBridge(ollamaService, huggingFaceService, cloudModelService, backendModel, apiKeyOverrides, webSearchService, "backend", pricingService);
        agents.put("backend", new BackendSpecialist(backendBridge));

        AgentLLMBridge frontendBridge = new AgentLLMBridge(ollamaService, huggingFaceService, cloudModelService, frontendModel, apiKeyOverrides, webSearchService, "frontend", pricingService);
        agents.put("frontend", new FrontendSpecialist(frontendBridge));

        AgentLLMBridge qaBridge = new AgentLLMBridge(ollamaService, huggingFaceService, cloudModelService, qaModel, apiKeyOverrides, webSearchService, "qa", pricingService);
        agents.put("qa", new QAAgent(qaBridge));

        AgentLLMBridge devopsBridge = new AgentLLMBridge(ollamaService, huggingFaceService, cloudModelService, devopsModel, apiKeyOverrides, webSearchService, "devops", pricingService);
        agents.put("devops", new DevOpsAgent(devopsBridge));

        AgentLLMBridge knowledgeBridge = new AgentLLMBridge(ollamaService, huggingFaceService, cloudModelService, knowledgeModel, apiKeyOverrides, webSearchService, "knowledge", pricingService);
        agents.put("knowledge", new KnowledgeAgent(knowledgeBridge, memoryService));

        logger.info("Initialized {} agents with optimized models", agents.size());
    }

    private void startMessageProcessor() {
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));
        executor.submit(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    AgentMessage msg = messageQueue.poll(100, TimeUnit.MILLISECONDS);
                    if (msg != null) {
                        SpecializedAgent agent = agents.get(msg.targetAgent());
                        if (agent != null) {
                            long start = System.currentTimeMillis();
                            Object result = agent.process(msg);
                            long elapsed = System.currentTimeMillis() - start;
                            logger.debug("[{}] Processed in {}ms:{}", msg.targetAgent(), elapsed, result);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
    }

    @PreDestroy
    public void shutdown() {
        try {
            executor.shutdownNow();
        } catch (Exception e) {
            logger.warn("Error shutting down MultiAgentCoordinator executor", e);
        }
    }

    // -------------------------------------------------------------------------
    // processRequest API
    // -------------------------------------------------------------------------

    @ConfidentialAI
    public Map<String, Object> processRequest(String userRequest, String context) {
        return processRequest(userRequest, context, null, null);
    }

    @ConfidentialAI
    public Map<String, Object> processRequest(String userRequest, String context,
                                               StreamingCoordinatorCallback callback) {
        return processRequest(userRequest, context, callback, null);
    }

    @ConfidentialAI
    public Map<String, Object> processRequest(String userRequest, String context,
                                               StreamingCoordinatorCallback callback,
                                               Map<String, String> apiKeyOverrides) {
        logger.info("Processing coordinator request with overrides={}", apiKeyOverrides);
        long start = System.currentTimeMillis();
        try {
            if (apiKeyOverrides != null && !apiKeyOverrides.isEmpty()) {
                logger.info("Reinitializing agent models using API-key overrides");
                agents.clear();
                initializeAgents(apiKeyOverrides);
            } else if (agents.isEmpty()) {
                initializeAgentsWithDefaults();
            }

            CoordinatorAgent coordinator = (CoordinatorAgent) agents.get("coordinator");
            if (coordinator == null) throw new RuntimeException("Coordinator agent not found");

            Map<String, Object> result = coordinator.coordinate(userRequest, context, this, callback);
            long totalTime = System.currentTimeMillis() - start;

            Map<String, Object> response = new HashMap<>();
            response.put("result",      result);
            response.put("elapsed_ms",  totalTime);
            response.put("agents_used", result.get("agents_used"));
            logger.info("Coordinator completed in {}ms", totalTime);
            return response;
        } catch (Exception e) {
            logger.error("Coordinator error", e);
            long totalTime = System.currentTimeMillis() - start;
            Map<String, Object> response = new HashMap<>();
            response.put("error",      e.getMessage());
            response.put("elapsed_ms", totalTime);
            response.put("status",     "error");
            return response;
        }
    }

    public void sendMessage(AgentMessage msg) {
        try {
            messageQueue.offer(msg);
        } catch (Exception e) {
            logger.error("Coordinator message error", e);
        }
    }

    public SpecializedAgent getAgent(String name) {
        return agents.get(name);
    }

    public Map<String, Object> getAgentStatuses() {
        try {
            if (agents.isEmpty()) initializeAgentsWithDefaults();
            Map<String, Object> statuses = new HashMap<>();
            for (Map.Entry<String, SpecializedAgent> entry : agents.entrySet()) {
                statuses.put(entry.getKey(), entry.getValue().getStatus());
            }
            return statuses;
        } catch (Exception e) {
            logger.error("Error building agent statuses", e);
            return new HashMap<>();
        }
    }
}
