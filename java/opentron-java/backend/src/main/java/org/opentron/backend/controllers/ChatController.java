package org.opentron.backend.controllers;

import java.util.Map;
import java.util.List;
import java.util.HashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.opentron.backend.util.EngineRouting;
import org.opentron.backend.util.OllamaCliService;
import org.opentron.backend.util.HuggingFaceService;
import org.opentron.backend.util.CloudModelService;
import org.opentron.backend.util.ResilienceUtil;
import org.springframework.core.io.buffer.DataBuffer;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/v1/chat")
public class ChatController {
    private static final Logger logger = LoggerFactory.getLogger(ChatController.class);

    private final WebClient webClient;
    private final EngineRouting engineRouting;
    private final OllamaCliService ollamaCliService;
    private final HuggingFaceService huggingFaceService;
    private final CloudModelService cloudModelService;
    private final org.opentron.backend.pricing.PricingService pricingService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ChatController(WebClient webClient, EngineRouting engineRouting, OllamaCliService ollamaCliService, HuggingFaceService huggingFaceService, CloudModelService cloudModelService, org.opentron.backend.pricing.PricingService pricingService) {
        this.webClient = webClient;
        this.engineRouting = engineRouting;
        this.ollamaCliService = ollamaCliService;
        this.huggingFaceService = huggingFaceService;
        this.cloudModelService = cloudModelService;
        this.pricingService = pricingService;
    }

    private boolean isCloudModel(String model) {
        if (model == null || model.isBlank()) return false;
        String lower = model.toLowerCase();
        return lower.startsWith("gpt-") || lower.startsWith("gpt4") || lower.startsWith("o1-") ||
               lower.startsWith("o3-") || lower.startsWith("o4-") || lower.startsWith("chatgpt-") ||
               lower.startsWith("claude-") || lower.startsWith("gemini-") || 
               lower.startsWith("openrouter/") || lower.startsWith("minimax-");
    }

    /**
     * Extract API keys from request headers (format: X-API-Keys: {"openai":"key","anthropic":"key"})
     */
    private Map<String, String> extractApiKeysFromRequest(HttpServletRequest request) {
        Map<String, String> apiKeys = new HashMap<>();
        try {
            String apiKeysHeader = request.getHeader("X-API-Keys");
            if (apiKeysHeader != null && !apiKeysHeader.isBlank()) {
                @SuppressWarnings("unchecked")
                Map<String, String> parsed = objectMapper.readValue(apiKeysHeader, Map.class);
                apiKeys.putAll(parsed);
                logger.debug("Received API keys for: {}", parsed.keySet());
            }
        } catch (Exception e) {
            logger.warn("Could not parse API keys header", e);
        }
        return apiKeys;
    }

    @PostMapping(value = "/completions")
    public Mono<ResponseEntity<?>> completionsStream(HttpServletRequest servletRequest, @RequestBody org.opentron.backend.dto.ChatCompletionRequest payload) {
        servletRequest.setAttribute("org.apache.catalina.ASYNC_TIMEOUT", -1L);
        try {
            org.springframework.context.ApplicationContext ctx = org.springframework.web.context.support.WebApplicationContextUtils.getRequiredWebApplicationContext(servletRequest.getServletContext());
            org.opentron.backend.telemetry.TelemetryService ts = ctx.getBean(org.opentron.backend.telemetry.TelemetryService.class);
            if (ts != null) ts.recordRequest();
        } catch (Exception ignored) {}

        boolean stream = Boolean.TRUE.equals(payload.getStream());
        String model = payload.getModel();
        
        logger.info("Chat request: model={}, stream={}", model, stream);
        
        // Extract API keys from request headers
        Map<String, String> apiKeys = extractApiKeysFromRequest(servletRequest);
        
        // Check if cloud model and route accordingly
        if (isCloudModel(model)) {
            logger.info("Cloud model detected: {}", model);
            return handleCloudModelChat(model, payload.getMessages(), stream, servletRequest, apiKeys);
        }
        
        // Check for Hugging Face mode
        String hfMode = System.getenv("HF_MODE");
        if ("local".equalsIgnoreCase(hfMode) || "api".equalsIgnoreCase(hfMode)) {
            logger.info("Using Hugging Face ({})", hfMode);
            return handleHuggingFaceChat(payload.getParams() == null ? java.util.Map.of() : payload.getParams(), stream, servletRequest);
        }
        
        // Fall back to Ollama for local models
        if (engineRouting.getEffectiveEngineType() == EngineRouting.EngineType.OLLAMA) {
            try {
                servletRequest.setAttribute("org.apache.catalina.ASYNC_TIMEOUT", 180000L);
            } catch (Exception e) {
                logger.warn("Could not set async timeout", e);
            }
            logger.info("Using Ollama CLI");
            Map<String, Object> ollamaPayload = new HashMap<>();
            if (payload.getParams() != null) {
                ollamaPayload.putAll(payload.getParams());
            }
            if (model != null) {
                ollamaPayload.put("model", model);
            }
            if (payload.getMessages() != null) {
                ollamaPayload.put("messages", toMessageMaps(payload.getMessages()));
            }
            Object messages = ollamaPayload.get("messages");
            if (!(messages instanceof List<?> messageList) || messageList.isEmpty()) {
                return Mono.just(ResponseEntity.badRequest().body(Map.of("error", "messages-required")));
            }
            return handleOllamaCliChat(ollamaPayload, stream, servletRequest);
        }

        String targetPath = engineRouting.translateRequestPath("/v1/chat/completions");
        Mono<Map<String, Object>> resolvedPayload = resolveOllamaModel(payload.getParams() == null ? java.util.Map.of() : payload.getParams());
        WebClient.RequestBodySpec requestBuilder = webClient.post()
                .uri(targetPath)
                .contentType(MediaType.APPLICATION_JSON);

        if (stream) {
            requestBuilder = requestBuilder.accept(MediaType.TEXT_EVENT_STREAM);
        }

        HttpHeaders forwardHeaders = new HttpHeaders();
        if (servletRequest.getHeaderNames() != null) {
            for (String headerName : java.util.Collections.list(servletRequest.getHeaderNames())) {
                if (headerName.equalsIgnoreCase(HttpHeaders.CONTENT_LENGTH)
                        || headerName.equalsIgnoreCase(HttpHeaders.AUTHORIZATION)
                        || headerName.equalsIgnoreCase(HttpHeaders.HOST)
                        || headerName.equalsIgnoreCase(HttpHeaders.CONNECTION)) {
                    continue;
                }
                for (String headerValue : java.util.Collections.list(servletRequest.getHeaders(headerName))) {
                    forwardHeaders.add(headerName, headerValue);
                }
            }
        }
        requestBuilder.headers(http -> http.addAll(forwardHeaders));

        logger.info("Forwarding to engine {} stream={}", targetPath, stream);

        WebClient.RequestBodySpec reqSpecBase = requestBuilder;

        @SuppressWarnings("deprecation")
        Mono<ResponseEntity<?>> result = resolvedPayload.flatMap(resolved -> reqSpecBase.bodyValue(resolved).exchange())
            .flatMap(response -> buildResponseMono(response, stream, servletRequest))
            .onErrorResume(e -> {
                logger.error("Chat exchange error", e);
                return Mono.just(ResponseEntity.status(502).body((Object)"{\"error\":\"chat-failed\"}"));
            });

        if (stream) {
            return ResilienceUtil.withResilienceStreaming(result);
        } else {
            return ResilienceUtil.withResilienceNonStreaming(result);
        }
    }

    /**
     * Handle cloud model chat (OpenAI, Claude, Gemini, etc.)
     */
    @SuppressWarnings("unchecked")
    private Mono<ResponseEntity<?>> handleCloudModelChat(String model, List<org.opentron.backend.dto.ChatCompletionRequest.Message> messages, boolean stream, HttpServletRequest request, Map<String, String> apiKeys) {
        List<Map<String, String>> formattedMessages = toMessageMaps(messages);
        return cloudModelService.callCloudModel(
            model,
            formattedMessages,
            apiKeys
        )
        .<ResponseEntity<?>>map(result -> {
            try {
                // Record token usage if available
                try {
                    Object usageObj = result.get("usage");
                    if (usageObj instanceof Map) {
                        Map<String, Object> usage = (Map<String, Object>) usageObj;
                      
                        Object promptObj = usage.get("prompt_tokens");
                        Object completionObj = usage.get("completion_tokens");
                        long promptTokens = promptObj instanceof Number n ? n.longValue() : 0;
                        long completionTokens = completionObj instanceof Number n ? n.longValue() : 0;
                        if (promptTokens > 0 || completionTokens > 0) {
                            try {
                                org.springframework.context.ApplicationContext ctx = org.springframework.web.context.support.WebApplicationContextUtils
                                    .getRequiredWebApplicationContext(request.getServletContext());
                                org.opentron.backend.telemetry.TelemetryService ts = ctx.getBean(org.opentron.backend.telemetry.TelemetryService.class);
                                double costUsd = pricingService != null ? pricingService.costUsd(model, promptTokens, completionTokens).orElse(0.0) : 0.0;
                                if (ts != null) ts.addTokenUsage(promptTokens, completionTokens, costUsd);
                            } catch (Exception e) {
                                logger.warn("Cloud model - could not record tokens", e);
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Cloud model - error extracting usage", e);
                }
                
                if (stream) {
                    // Transform to streaming format
                    List<Map<String, Object>> choices = (List<Map<String, Object>>) result.get("choices");
                    if (choices == null || choices.isEmpty()) {
                        return ResponseEntity.status(500).body((Object)"{\"error\":\"no-choices\"}");
                    }
                    
                    Map<String, Object> choice = choices.get(0);
                    Map<String, Object> message = (Map<String, Object>) choice.get("message");
                    String content = message != null ? (String) message.get("content") : "";
                    
                    StringBuilder sseBuilder = new StringBuilder();
                    String[] words = content.split(" ");
                    
                    for (int i = 0; i < words.length; i++) {
                        Map<String, Object> chunk = new java.util.HashMap<>();
                        chunk.put("model", result.get("model"));
                        chunk.put("created", result.get("created"));
                        
                        List<Map<String, Object>> chunkChoices = new java.util.ArrayList<>();
                        Map<String, Object> chunkChoice = new java.util.HashMap<>();
                        chunkChoice.put("index", 0);
                        
                        Map<String, Object> delta = new java.util.HashMap<>();
                        if (i == 0) {
                            delta.put("role", "assistant");
                        }
                        delta.put("content", words[i] + (i < words.length - 1 ? " " : ""));
                        chunkChoice.put("delta", delta);
                        
                        if (i == words.length - 1) {
                            chunkChoice.put("finish_reason", "stop");
                        } else {
                            chunkChoice.put("finish_reason", null);
                        }
                        
                        chunkChoices.add(chunkChoice);
                        chunk.put("choices", chunkChoices);
                        
                        String json = objectMapper.writeValueAsString(chunk);
                        sseBuilder.append("data: ").append(json).append("\n\n");
                    }
                    
                    sseBuilder.append("data: [DONE]\n\n");
                    
                    return ResponseEntity.ok()
                            .contentType(MediaType.TEXT_EVENT_STREAM)
                            .body((Object)sseBuilder.toString());
                } else {
                    String json = objectMapper.writeValueAsString(result);
                    return ResponseEntity.ok()
                            .contentType(MediaType.APPLICATION_JSON)
                            .body((Object)json);
                }
            } catch (Exception e) {
                logger.warn("Cloud model - Serialization error", e);
                return ResponseEntity.status(500).body((Object)"{\"error\":\"serialization-failed\"}");
            }
        })
        .onErrorResume(e -> {
            logger.error("Cloud model error", e);
            String errMsg = e.getMessage() != null ? e.getMessage().replace("\"", "\\\"") : "unknown error";
            return Mono.<ResponseEntity<?>>just(ResponseEntity.status(502)
                    .body((Object)("{\"error\":\"cloud-model-failed\",\"message\":\"" + errMsg + "\"}")));
        });
    }

    private List<Map<String, String>> toMessageMaps(List<org.opentron.backend.dto.ChatCompletionRequest.Message> messages) {
        List<Map<String, String>> formattedMessages = new java.util.ArrayList<>();
        if (messages != null) {
            for (org.opentron.backend.dto.ChatCompletionRequest.Message message : messages) {
                Map<String, String> item = new java.util.HashMap<>();
                item.put("role", message.getRole());
                item.put("content", message.getContent());
                if (message.getName() != null) {
                    item.put("name", message.getName());
                }
                formattedMessages.add(item);
            }
        }
        return formattedMessages;
    }

    /**
     * Handle Hugging Face chat (faster than Ollama)
     */
    @SuppressWarnings("unchecked")
    private Mono<ResponseEntity<?>> handleHuggingFaceChat(Map<String, Object> payload, boolean stream, HttpServletRequest request) {
        return huggingFaceService.chatCompletion(
            (String) payload.getOrDefault("model", "mistralai/Mistral-7B-Instruct-v0.1"),
            (List<Map<String, String>>) payload.get("messages")
        )
        .<ResponseEntity<?>>map(result -> {
            try {
                // Record token usage if available
                try {
                    Object usageObj = result.get("usage");
                    if (usageObj instanceof Map) {
                        Map<String, Object> usage = (Map<String, Object>) usageObj;
                        
                        Object promptObj = usage.get("prompt_tokens");
                        Object completionObj = usage.get("completion_tokens");
                        long promptTokens = promptObj instanceof Number n ? n.longValue() : 0;
                        long completionTokens = completionObj instanceof Number n ? n.longValue() : 0;
                        if (promptTokens > 0 || completionTokens > 0) {
                            try {
                                org.springframework.context.ApplicationContext ctx = org.springframework.web.context.support.WebApplicationContextUtils
                                    .getRequiredWebApplicationContext(request.getServletContext());
                                org.opentron.backend.telemetry.TelemetryService ts = ctx.getBean(org.opentron.backend.telemetry.TelemetryService.class);
                                double costUsd = 0.0 /* HF pricing not yet in PricingService */;
                                if (ts != null) ts.addTokenUsage(promptTokens, completionTokens, costUsd);
                            } catch (Exception e) {
                                logger.warn("HuggingFace - could not record tokens", e);
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.warn("HuggingFace - error extracting usage", e);
                }
                
                if (stream) {
                    // Transform to streaming format
                    List<Map<String, Object>> choices = (List<Map<String, Object>>) result.get("choices");
                    if (choices == null || choices.isEmpty()) {
                        return ResponseEntity.status(500).body((Object)"{\"error\":\"no-choices\"}");
                    }
                    
                    Map<String, Object> choice = choices.get(0);
                    Map<String, Object> message = (Map<String, Object>) choice.get("message");
                    String content = message != null ? (String) message.get("content") : "";
                    
                    StringBuilder sseBuilder = new StringBuilder();
                    String[] words = content.split(" ");
                    
                    for (int i = 0; i < words.length; i++) {
                        Map<String, Object> chunk = new java.util.HashMap<>();
                        chunk.put("model", result.get("model"));
                        chunk.put("created", result.get("created"));
                        
                        List<Map<String, Object>> chunkChoices = new java.util.ArrayList<>();
                        Map<String, Object> chunkChoice = new java.util.HashMap<>();
                        chunkChoice.put("index", 0);
                        
                        Map<String, Object> delta = new java.util.HashMap<>();
                        if (i == 0) {
                            delta.put("role", "assistant");
                        }
                        delta.put("content", words[i] + (i < words.length - 1 ? " " : ""));
                        chunkChoice.put("delta", delta);
                        
                        if (i == words.length - 1) {
                            chunkChoice.put("finish_reason", "stop");
                        } else {
                            chunkChoice.put("finish_reason", null);
                        }
                        
                        chunkChoices.add(chunkChoice);
                        chunk.put("choices", chunkChoices);
                        
                        String json = objectMapper.writeValueAsString(chunk);
                        sseBuilder.append("data: ").append(json).append("\n\n");
                    }
                    
                    sseBuilder.append("data: [DONE]\n\n");
                    
                    return ResponseEntity.ok()
                            .contentType(MediaType.TEXT_EVENT_STREAM)
                            .body((Object)sseBuilder.toString());
                } else {
                    String json = objectMapper.writeValueAsString(result);
                    return ResponseEntity.ok()
                            .contentType(MediaType.APPLICATION_JSON)
                            .body((Object)json);
                }
            } catch (Exception e) {
                logger.warn("HuggingFace - serialization error", e);
                return ResponseEntity.status(500).body((Object)"{\"error\":\"serialization-failed\"}");
            }
        })
        .onErrorResume(e -> {
            logger.error("HuggingFace error", e);
            String errMsg = e.getMessage() != null ? e.getMessage().replace("\"", "\\\"") : "unknown error";
            return Mono.<ResponseEntity<?>>just(ResponseEntity.status(502)
                    .body((Object)("{\"error\":\"huggingface-failed\",\"message\":\"" + errMsg + "\"}")));
        });
    }

    @SuppressWarnings("unchecked")
    private Mono<ResponseEntity<?>> handleOllamaCliChat(Map<String, Object> payload, boolean stream, HttpServletRequest request) {
        return ollamaCliService.chatCompletion(
            (String) payload.getOrDefault("model", "gemma3:4b"),
            (List<Map<String, String>>) payload.get("messages")
        )
        .<ResponseEntity<?>>map(result -> {
            try {
                // Record token usage if available. Local Ollama inference has no
                // per-token price, so cost is genuinely $0 here.
                try {
                    Object usageObj = result.get("usage");
                    if (usageObj instanceof Map) {
                        Map<String, Object> usage = (Map<String, Object>) usageObj;
                        Object promptObj = usage.get("prompt_tokens");
                        Object completionObj = usage.get("completion_tokens");
                        long promptTokens = promptObj instanceof Number n ? n.longValue() : 0;
                        long completionTokens = completionObj instanceof Number n ? n.longValue() : 0;
                        if (promptTokens > 0 || completionTokens > 0) {
                            try {
                                org.springframework.context.ApplicationContext ctx = org.springframework.web.context.support.WebApplicationContextUtils
                                    .getRequiredWebApplicationContext(request.getServletContext());
                                org.opentron.backend.telemetry.TelemetryService ts = ctx.getBean(org.opentron.backend.telemetry.TelemetryService.class);
                                if (ts != null) ts.addTokenUsage(promptTokens, completionTokens, 0.0);
                            } catch (Exception e) {
                                logger.warn("Ollama - could not record tokens", e);
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Ollama - error extracting usage", e);
                }
                
                if (stream) {
                    // Transform the non-streaming response into OpenAI streaming format with deltas
                    List<Map<String, Object>> choices = (List<Map<String, Object>>) result.get("choices");
                    if (choices == null || choices.isEmpty()) {
                        return ResponseEntity.status(500).body((Object)"{\"error\":\"no-choices\"}");
                    }
                    
                    Map<String, Object> choice = choices.get(0);
                    Map<String, Object> message = (Map<String, Object>) choice.get("message");
                    String content = message != null ? (String) message.get("content") : "";
                    
                    // Break content into word chunks and create streaming chunks
                    StringBuilder sseBuilder = new StringBuilder();
                    String[] words = content.split(" ");
                    
                    for (int i = 0; i < words.length; i++) {
                        Map<String, Object> chunk = new java.util.HashMap<>();
                        chunk.put("model", result.get("model"));
                        chunk.put("created", result.get("created"));
                        
                        List<Map<String, Object>> chunkChoices = new java.util.ArrayList<>();
                        Map<String, Object> chunkChoice = new java.util.HashMap<>();
                        chunkChoice.put("index", 0);
                        
                        Map<String, Object> delta = new java.util.HashMap<>();
                        if (i == 0) {
                            delta.put("role", "assistant");
                        }
                        delta.put("content", words[i] + (i < words.length - 1 ? " " : ""));
                        chunkChoice.put("delta", delta);
                        
                        if (i == words.length - 1) {
                            chunkChoice.put("finish_reason", "stop");
                        } else {
                            chunkChoice.put("finish_reason", null);
                        }
                        
                        chunkChoices.add(chunkChoice);
                        chunk.put("choices", chunkChoices);
                        
                        String json = objectMapper.writeValueAsString(chunk);
                        sseBuilder.append("data: ").append(json).append("\n\n");
                    }
                    
                    sseBuilder.append("data: [DONE]\n\n");
                    logger.debug("Transformed response to OpenAI streaming format");
                    
                    return ResponseEntity.ok()
                            .contentType(MediaType.TEXT_EVENT_STREAM)
                            .body((Object)sseBuilder.toString());
                } else {
                    // Non-streaming: return as-is (already in OpenAI format with message field)
                    String json = objectMapper.writeValueAsString(result);
                    logger.debug("Returning non-streaming OpenAI format response");
                    return ResponseEntity.ok()
                            .contentType(MediaType.APPLICATION_JSON)
                            .body((Object)json);
                }
            } catch (Exception e) {
                logger.warn("Ollama - serialization error", e);
                return ResponseEntity.status(500).body((Object)"{\"error\":\"serialization-failed\"}");
            }
        })
        .onErrorResume(e -> {
            logger.error("Ollama CLI error", e);
            String errMsg = e.getMessage() != null ? e.getMessage().replace("\"", "\\\"") : "unknown error";
            return Mono.<ResponseEntity<?>>just(ResponseEntity.status(502)
                    .body((Object)("{\"error\":\"ollama-cli-failed\",\"message\":\"" + errMsg + "\"}")));
        });
    }

    @SuppressWarnings("unchecked")
    private Mono<ResponseEntity<?>> buildResponseMono(ClientResponse response, boolean stream, HttpServletRequest request) {
        HttpHeaders responseHeaders = new HttpHeaders();
        response.headers().asHttpHeaders().forEach((name, values) -> {
            if (!name.equalsIgnoreCase(HttpHeaders.CONTENT_LENGTH) &&
                !name.equalsIgnoreCase(HttpHeaders.CONTENT_TYPE)) {
                responseHeaders.put(name, values);
            }
        });

        if (stream) {
            logger.info("Proxying streaming response from engine");
            Flux<DataBuffer> bodyFlux = response.bodyToFlux(DataBuffer.class);
            return Mono.just(
                ResponseEntity.status(response.statusCode())
                    .headers(responseHeaders)
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .body(bodyFlux)
            );
        } else {
            logger.info("Proxying non-streaming response from engine");
            return response.bodyToMono(String.class)
                    .map(bodyText -> {
                        // Extract and record token usage. This proxies directly to a
                        // local engine (Ollama-compatible), so cost is $0.
                        try {
                            Map<String, Object> parsedBody = objectMapper.readValue(bodyText, Map.class);
                            Map<String, Object> usage = (Map<String, Object>) parsedBody.get("usage");
                            if (usage != null) {
                                Object promptObj = usage.get("prompt_tokens");
                                Object completionObj = usage.get("completion_tokens");
                                long promptTokens = promptObj instanceof Number n ? n.longValue() : 0;
                                long completionTokens = completionObj instanceof Number n ? n.longValue() : 0;
                                if (promptTokens > 0 || completionTokens > 0) {
                                    try {
                                        org.springframework.context.ApplicationContext ctx = 
                                            org.springframework.web.context.support.WebApplicationContextUtils
                                                .getRequiredWebApplicationContext(request.getServletContext());
                                        org.opentron.backend.telemetry.TelemetryService ts = ctx.getBean(org.opentron.backend.telemetry.TelemetryService.class);
                                        if (ts != null) ts.addTokenUsage(promptTokens, completionTokens, 0.0);
                                    } catch (Exception e) {
                                        logger.warn("Could not record tokens from proxied response", e);
                                    }
                                }
                            }
                        } catch (Exception e) {
                            logger.warn("Error parsing proxied response for tokens", e);
                        }
                        return ResponseEntity.status(response.statusCode())
                                .headers(responseHeaders)
                                .contentType(MediaType.APPLICATION_JSON)
                                .body((Object)bodyText);
                    });
        }
    }

    private Mono<Map<String, Object>> resolveOllamaModel(Map<String, Object> payload) {
        if (engineRouting.getEffectiveEngineType() != org.opentron.backend.util.EngineRouting.EngineType.OLLAMA) {
            return Mono.just(payload);
        }

        Object m = payload.get("model");
        String requestedModel = m == null ? null : String.valueOf(m);
        if (requestedModel == null || requestedModel.isBlank()) {
            return Mono.just(payload);
        }

        return engineRouting.hasModel(requestedModel)
                .flatMap(has -> {
                    if (Boolean.TRUE.equals(has)) {
                        return Mono.just(payload);
                    }
                    return engineRouting.pickFirstAvailableModel()
                            .map(model -> {
                                if (model != null && !model.isBlank()) {
                                    logger.info("Requested model '{}' not found, switching to '{}'", requestedModel, model);
                                    Map<String, Object> replaced = new java.util.HashMap<>(payload);
                                    replaced.put("model", model);
                                    return replaced;
                                }
                                return payload;
                            });
                })
                .onErrorResume(e -> {
                    logger.error("Model resolution error", e);
                    return Mono.just(payload);
                });
    }
}
