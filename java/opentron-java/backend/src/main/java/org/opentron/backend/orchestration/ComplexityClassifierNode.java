package org.opentron.backend.orchestration;

import org.opentron.backend.util.OllamaCliService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Map;
import reactor.core.publisher.Mono;

/**
 * ComplexityClassifierNode - Fast local triage using Ollama
 * 
 * Uses qwen3:4b locally to classify incoming requests before model routing.
 * 
 * SIMPLE: Routine requests (Haiku or Qwen2.5 Coder)
 * COMPLEX: Substantial work (Sonnet or DeepSeek R1)
 * EXTREME: Rare anomalies and unusually difficult investigations (Fable)
 * 
 * This classification happens in ~200ms on a local model, saving expensive
 * Claude API calls for routine tasks.
 */
public class ComplexityClassifierNode {
    
    private static final Logger logger = LoggerFactory.getLogger(ComplexityClassifierNode.class);
    
    private final OllamaCliService ollamaService;
    private final String classifierModel;
    
    public ComplexityClassifierNode(OllamaCliService ollamaService) {
        this.ollamaService = ollamaService;
        this.classifierModel = "qwen3:4b";
    }
    
    /**
     * Classify a request as SIMPLE, COMPLEX, or EXTREME.
     * Returns a record with the classification and reasoning.
     */
    public Mono<ComplexityClassification> classify(String userQuery) {
        String classificationPrompt = """
            Classify the request as exactly one of SIMPLE, COMPLEX, or EXTREME.
            
            SIMPLE criteria:
            - Basic factual questions (definitions, dates, facts)
            - Simple text formatting or summarization
            - Straightforward retrieval tasks
            - Questions answerable in 1-2 sentences
            - Syntax lookups or simple examples
            
            COMPLEX criteria:
            - Multi-step code architecture or design reviews
            - Complex debugging or troubleshooting with multiple interconnected issues
            - High-level strategic decisions or planning
            - Deep reasoning or analysis requiring context synthesis
            - Code generation, refactoring, or optimization
            - Systems design with multiple moving parts
            - Security, performance, or scalability analysis

            EXTREME criteria (rare; do not use for ordinary complex coding):
            - Anomalies or production incidents with contradictory evidence and broad impact
            - Novel, unusually difficult cross-system investigations requiring the deepest reasoning
            - High-stakes problems where a normal complex-task response is insufficient
            
            Query: %s
            
            Respond with ONLY:
            CLASSIFICATION: [SIMPLE, COMPLEX, or EXTREME]
            CONFIDENCE: [HIGH, MEDIUM, LOW]
            REASONING: [One sentence explaining why]
            """.formatted(userQuery);
        
        return ollamaService.chatCompletion(classifierModel, java.util.List.of(
                Map.of("role", "user", "content", classificationPrompt)
        )).map(response -> parseClassification(response, userQuery));
    }
    
    private ComplexityClassification parseClassification(Map<String, Object> response, String userQuery) {
        try {
            @SuppressWarnings("unchecked")
            java.util.List<Map<String, Object>> choices = (java.util.List<Map<String, Object>>) response.get("choices");
            if (choices == null || choices.isEmpty()) {
                logger.warn("Empty response from classifier, defaulting to COMPLEX for safety");
                return new ComplexityClassification("COMPLEX", "LOW", "No classifier response", userQuery);
            }
            
            Map<String, Object> choice = choices.get(0);
            @SuppressWarnings("unchecked")
            Map<String, Object> message = (Map<String, Object>) choice.get("message");
            String content = message != null ? (String) message.get("content") : "";
            
            if (content == null || content.isBlank()) {
                logger.warn("Empty content from classifier, defaulting to COMPLEX for safety");
                return new ComplexityClassification("COMPLEX", "LOW", "Empty classifier response", userQuery);
            }
            
            // Parse structured response
            String classification = "COMPLEX"; // Conservative default
            String confidence = "LOW";
            String reasoning = "";
            
            for (String line : content.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("CLASSIFICATION:")) {
                    String value = trimmed.substring("CLASSIFICATION:".length()).trim();
                    String normalized = value.toUpperCase(java.util.Locale.ROOT);
                    if (normalized.startsWith("EXTREME") || normalized.startsWith("ANOMALY")) {
                        classification = "EXTREME";
                    } else if (normalized.startsWith("SIMPLE")) {
                        classification = "SIMPLE";
                    } else if (normalized.startsWith("COMPLEX")) {
                        classification = "COMPLEX";
                    }
                } else if (trimmed.startsWith("CONFIDENCE:")) {
                    String value = trimmed.substring("CONFIDENCE:".length()).trim();
                    if (value.contains("HIGH")) confidence = "HIGH";
                    else if (value.contains("MEDIUM")) confidence = "MEDIUM";
                    else if (value.contains("LOW")) confidence = "LOW";
                } else if (trimmed.startsWith("REASONING:")) {
                    reasoning = trimmed.substring("REASONING:".length()).trim();
                }
            }
            
            ComplexityClassification result = new ComplexityClassification(classification, confidence, reasoning, userQuery);
            logger.info("Classified query as {}: {} (confidence: {})", classification, reasoning, confidence);
            return result;
            
        } catch (Exception e) {
            logger.error("Error parsing classifier response", e);
            return new ComplexityClassification("COMPLEX", "LOW", "Classifier error: " + e.getMessage(), userQuery);
        }
    }
    
    /**
     * ComplexityClassification record - immutable result of classification
     */
    public record ComplexityClassification(
            String classification,      // "SIMPLE", "COMPLEX", or "EXTREME"
            String confidence,          // "HIGH", "MEDIUM", "LOW"
            String reasoning,           // Explanation
            String originalQuery        // Original user query for tracing
    ) {
        public boolean isComplex() {
            return "COMPLEX".equals(classification) || isExtreme();
        }
        
        public boolean isSimple() {
            return "SIMPLE".equals(classification);
        }
        
        public boolean isExtreme() {
            return "EXTREME".equals(classification);
        }

        public boolean isHighConfidence() {
            return "HIGH".equals(confidence);
        }
    }
}
