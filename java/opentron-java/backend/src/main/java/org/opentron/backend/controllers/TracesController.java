package org.opentron.backend.controllers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.*;
import org.opentron.backend.storage.service.StorageService;
import org.opentron.backend.storage.entities.TraceLog;

@RestController
@RequestMapping("/v1/traces")
public class TracesController {

    private static final Logger logger = LoggerFactory.getLogger(TracesController.class);

    @Autowired
    private StorageService storageService;

    private Map<String, Object> buildStepFromTrace(TraceLog trace) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (trace.getModelName() != null) data.put("model", trace.getModelName());
        if (trace.getModelProvider() != null) data.put("provider", trace.getModelProvider());
        if (trace.getInputTokens() != null && trace.getInputTokens() > 0) data.put("input_tokens", trace.getInputTokens());
        if (trace.getOutputTokens() != null && trace.getOutputTokens() > 0) data.put("output_tokens", trace.getOutputTokens());
        if (trace.getCostUsd() != null && trace.getCostUsd() > 0) data.put("cost_usd", trace.getCostUsd());
        if (trace.getClassification() != null) data.put("classification", trace.getClassification());
        if (trace.getRoutingReason() != null) data.put("routing_reason", trace.getRoutingReason());

        Map<String, Object> step = new LinkedHashMap<>();
        step.put("step_type", "request");
        step.put("duration_ms", trace.getDurationMs() != null ? trace.getDurationMs() : 0);
        step.put("data", data);
        return step;
    }

    private Map<String, Object> traceToMap(TraceLog dbTrace) {
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("id", "trace-" + dbTrace.getId());
        trace.put("query", dbTrace.getInput());
        trace.put("created_at", dbTrace.getTimestamp().toString());
        trace.put("steps", List.of(buildStepFromTrace(dbTrace)));
        trace.put("outcome", "success");
        trace.put("duration_ms", dbTrace.getDurationMs());
        trace.put("is_compressed", dbTrace.getIsCompressed());
        return trace;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> getTraces(
            @RequestParam(defaultValue = "50") int limit) {
        try {
            List<TraceLog> dbTraces = storageService.loadTraces("coordinator", Math.min(limit, 1000));
            List<Map<String, Object>> traces = new ArrayList<>();
            for (TraceLog dbTrace : dbTraces) {
                traces.add(traceToMap(dbTrace));
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("traces", traces);
            response.put("count", traces.size());
            response.put("source", "postgresql");
            response.put("timestamp", System.currentTimeMillis());

            logger.info("Loaded {} traces from database", traces.size());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error loading traces from database", e);
            Map<String, Object> errorBody = new LinkedHashMap<>();
            errorBody.put("error", "traces_unavailable");
            errorBody.put("message", "Could not load traces from the database. See server logs for details.");
            errorBody.put("timestamp", System.currentTimeMillis());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(errorBody);
        }
    }

    @GetMapping("/{traceId}")
    public ResponseEntity<Map<String, Object>> getTrace(@PathVariable String traceId) {
        Long id = parseTraceId(traceId);
        if (id == null) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "invalid_trace_id",
                "message", "Expected an ID of the form 'trace-<number>', got: " + traceId
            ));
        }

        Optional<TraceLog> found = storageService.getTraceById(id);
        if (found.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "error", "trace_not_found",
                "message", "No trace found with ID " + traceId
            ));
        }

        return ResponseEntity.ok(traceToMap(found.get()));
    }

    private Long parseTraceId(String traceId) {
        String numeric = traceId.startsWith("trace-") ? traceId.substring("trace-".length()) : traceId;
        try {
            return Long.parseLong(numeric);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Get traces from database for a specific agent
     */
    @GetMapping("/agent/{agentId}")
    public ResponseEntity<Map<String, Object>> getAgentTraces(
            @PathVariable String agentId,
            @RequestParam(defaultValue = "50") int limit) {
        try {
            List<TraceLog> traces = storageService.loadTraces(agentId, Math.min(limit, 1000));
            List<Map<String, Object>> traceList = new ArrayList<>();

            for (TraceLog trace : traces) {
                Map<String, Object> traceMap = new HashMap<>();
                traceMap.put("id", trace.getId());
                traceMap.put("agent", trace.getAgent());
                traceMap.put("input", trace.getInput() != null ? trace.getInput().substring(0, Math.min(50, trace.getInput().length())) + "..." : "");
                traceMap.put("duration_ms", trace.getDurationMs());
                traceMap.put("timestamp", trace.getTimestamp().toString());
                traceMap.put("is_compressed", trace.getIsCompressed());
                traceList.add(traceMap);
            }

            Map<String, Object> response = new HashMap<>();
            response.put("traces", traceList);
            response.put("count", traceList.size());
            response.put("agent", agentId);
            response.put("source", "postgresql");

            logger.info("Loaded {} traces for {}", traceList.size(), agentId);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error loading traces for agent {}", agentId, e);
            Map<String, Object> errorMap = new HashMap<>();
            errorMap.put("error", e.getMessage());
            return ResponseEntity.status(500).body(errorMap);
        }
    }
}
