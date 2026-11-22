package org.opentron.backend.agents;

import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.opentron.backend.telemetry.TelemetryService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

public class CoordinatorAgent extends SpecializedAgent {
    private static final Logger logger = LoggerFactory.getLogger(CoordinatorAgent.class);

    // Priority order for picking which specialist drafts the shared contract
    // when a request spans multiple domains - mirrors DomainDetector's
    // ORDERED_DOMAINS. Backend/frontend are the domains that actually define
    // data/type/API shapes most often; qa/devops mostly consume those shapes,
    // so they're only picked as contract-author when neither backend nor
    // frontend is in play.
    private static final List<String> CONTRACT_OWNER_PRIORITY = List.of("backend", "frontend", "qa", "devops");

    // How many synthesis->revise->re-synthesize rounds to allow before giving
    // up and shipping the best answer so far. Bounded low (not unbounded)
    // because each round is a real LLM call per affected specialist plus a
    // re-synthesis call - this is a safety net for genuine cross-domain
    // conflicts, not a general-purpose retry loop.
    private static final int MAX_FIX_ITERATIONS = 2;

    private static final Pattern BLOCKING_ISSUES_PATTERN =
            Pattern.compile("===BLOCKING_ISSUES===(.*?)===END_BLOCKING_ISSUES===", Pattern.DOTALL);
    // Falls back to this when the response got cut off mid-block (hit the
    // model's token limit before emitting the closing marker) - captures
    // everything after the opening marker instead of finding nothing at all.
    private static final Pattern BLOCKING_ISSUES_OPEN_PATTERN =
            Pattern.compile("===BLOCKING_ISSUES===(.*)$", Pattern.DOTALL);
    private static final ObjectMapper JSON = new ObjectMapper();

    public CoordinatorAgent(AgentLLMBridge llmBridge) {
        super(llmBridge);
        this.name = "Coordinator";
        this.skills = Arrays.asList(
            "analyze_requirements", "delegate_to_specialists",
            "monitor_progress", "aggregate_results", "error_recovery");
    }

    private void recordTelemetry(MultiAgentCoordinator coordinator, Map<String, Object> result) {
        TelemetryService ts = coordinator != null ? coordinator.telemetryService : null;
        if (ts == null || result == null) return;
        Object inputObj = result.get("input_tokens");
        Object outputObj = result.get("output_tokens");
        long inputTokens = inputObj instanceof Number n ? n.longValue() : 0;
        long outputTokens = outputObj instanceof Number n ? n.longValue() : 0;
        if (inputTokens <= 0 && outputTokens <= 0) {
            // No real split on this result (e.g. an error response) 
            return;
        }
        Object costObj = result.get("cost_usd");
        double costUsd = costObj instanceof Number n ? n.doubleValue() : 0.0;
        ts.addTokenUsage(inputTokens, outputTokens, costUsd);

        // Also attribute this real cost to a provider family (Anthropic/
        // OpenAI/Google/OpenRouter), so the UI can show actual spend per
        // provider instead of only a blended total. Grouped by the model
        // that was actually used for this call and only
        // recorded when there's a real cost to attribute (local Ollama
        // calls correctly contribute nothing here).
        if (costUsd > 0 && coordinator.pricingService != null) {
            Object modelObj = result.get("model");
            String providerGroup = coordinator.pricingService.providerGroupFor(
                    modelObj != null ? String.valueOf(modelObj) : null);
            if (providerGroup != null) {
                ts.addProviderCost(providerGroup, costUsd);
            }
        }
    }

    @Override
    public Object process(AgentMessage msg) {
        return Map.of("status", "ok", "message", "Coordinator received message");
    }

    public Map<String, Object> coordinate(String userRequest, String context,
                                           MultiAgentCoordinator coordinator,
                                           StreamingCoordinatorCallback callback) {
        long start = System.currentTimeMillis();
        List<String> agentsUsed  = new ArrayList<>();
        Map<String, Object> taskResults = new HashMap<>();

        TelemetryService ts = coordinator.telemetryService;
        if (ts != null) ts.recordRequest();
        if (callback != null) callback.onStatus("Analyzing request and routing to specialist agents...");

        List<String> requiredAgents = analyzeRequest(userRequest, context);
        agentsUsed.addAll(requiredAgents);
        logger.info("Required agents: {}", requiredAgents);

        // CONTRACT-FIRST PHASE: when a request genuinely spans multiple
        // specialist domains, they're about to run in parallel with no
        // visibility into each other. Left alone, each one independently
        // invents its own type/class/API shapes for the same shared
        // artifact, producing incompatible outputs that can't be merged
        // (e.g. backend defines one `Table<T>`/response shape, frontend
        // defines a different one, qa writes tests against a third). Before
        // dispatching the specialists, ask ONE of them (the domain most
        // likely to own the data/API shape) to draft ONLY the shared
        // contract - type/interface signatures, no implementation - and
        // inject that verbatim into every specialist's context below so
        // they implement/test against the same names instead of each
        // inventing their own.
        String sharedContract = null;
        if (requiredAgents.size() > 1) {
            sharedContract = draftSharedContract(userRequest, context, requiredAgents, coordinator, callback,
                    taskResults, agentsUsed, ts);
        }

        List<Future<Map<String, Object>>> futures = new ArrayList<>();
        final String contractForSpecialists = sharedContract;
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (String agentName : requiredAgents) {
                futures.add(executor.submit(() -> {
                        SpecializedAgent agent = coordinator.getAgent(agentName);
                    String modelName = agent != null ? agent.llmBridge.getModel() : "unknown";
                    if (callback != null) callback.onAgentStart(agentName, modelName);
                    if (agent != null) {
                        List<String> focusedSkills =
                                inferSkillsForAgent(agentName, userRequest, context);
                        Map<String, Object> payload =
                                buildSpecialistTaskPayload(userRequest, context, agentName, focusedSkills,
                                        contractForSpecialists);
                        AgentMessage msg = new AgentMessage(agentName, "task", payload, callback);
                        @SuppressWarnings("unchecked")
                        Map<String, Object> result = (Map<String, Object>) agent.process(msg);
                        if (callback != null) {
                            String agentModel = (String) result.getOrDefault("selected_model", result.getOrDefault("model", modelName));
                            if (agentModel == null || agentModel.isBlank()) {
                                agentModel = modelName;
                            }
                            if ("error".equals(result.get("status")))
                                callback.onAgentError(agentName, (String) result.getOrDefault("error", "unknown"), agentModel);
                            else {
                                callback.onAgentDone(agentName, result, agentModel);
                            }
                        }
                        return result;
                    }
                    if (callback != null) callback.onAgentError(agentName, "Agent not found", "unknown");
                    return Map.of("error", "Agent not found");
                }));
            }

            for (int i = 0; i < futures.size(); i++) {
                try {
                    Map<String, Object> result = futures.get(i).get();
                    taskResults.put(requiredAgents.get(i), result);
                    if (ts != null) {
                        recordTelemetry(coordinator, result);
                    }
                } catch (Exception e) {
                    logger.error("{} agent error", requiredAgents.get(i), e);
                    taskResults.put(requiredAgents.get(i),
                            Map.of("error", e.getMessage() != null ? e.getMessage() : "unknown"));
                }
            }
        }

        int fixIterations = 0;
        int blockersFixed = 0;

        if (requiredAgents.size() > 1) {
            Map<String, Object> synthesisResult = runSynthesis(userRequest, context, requiredAgents, taskResults,
                    sharedContract, coordinator, callback, ts);
            String synthesisResponseText = null;
            if (synthesisResult != null) {
                taskResults.put("synthesis", synthesisResult);
                agentsUsed.add("synthesis");
                synthesisResponseText = String.valueOf(synthesisResult.getOrDefault("response", ""));
            }

            // FIX LOOP: the synthesis step above can flag genuine cross-domain
            // blockers via a machine-readable BLOCKING_ISSUES block appended to
            // its response (see buildBlockingIssuesInstruction). When it does,
            // dispatch a targeted revision to exactly the specialist(s) named,
            // then re-run synthesis to confirm the revision actually closed the
            // gap - rather than shipping a final response the synthesis step
            // itself said was unsafe. Bounded by MAX_FIX_ITERATIONS, and a
            // signature check breaks early if a round makes no measurable
            // progress (same blockers reported again) so a model that can't
            // actually resolve something doesn't burn every round restating it.
            List<Map<String, String>> blockers = parseBlockingIssues(synthesisResponseText);
            String lastSignature = null;
            while (!blockers.isEmpty() && fixIterations < MAX_FIX_ITERATIONS) {
                Map<String, List<String>> byDomain = groupByDomain(blockers, requiredAgents);
                if (byDomain.isEmpty()) {
                    logger.info("Blockers reported but none map to an actionable domain in this run, stopping fix loop");
                    break;
                }
                String signature = blockerSignature(blockers);
                if (signature.equals(lastSignature)) {
                    logger.info("Blocker set unchanged after revision round, stopping fix loop");
                    break;
                }
                lastSignature = signature;
                fixIterations++;
                if (callback != null) {
                    callback.onStatus("Fixing " + byDomain.size() + " cross-domain blocker"
                            + (byDomain.size() == 1 ? "" : "s") + " (round " + fixIterations + ")...");
                }

                // Immutable snapshot so the revision workers below (running on
                // their own virtual threads) never read taskResults while this
                // thread is concurrently writing it a few lines further down.
                Map<String, Object> priorSnapshot = Map.copyOf(taskResults);
                List<Future<Map.Entry<String, Map<String, Object>>>> revisionFutures = new ArrayList<>();
                try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                    for (Map.Entry<String, List<String>> entry : byDomain.entrySet()) {
                        String domain = entry.getKey();
                        List<String> issues = entry.getValue();
                        SpecializedAgent agent = coordinator.getAgent(domain);
                        if (agent == null) continue;
                        revisionFutures.add(executor.submit(() -> Map.entry(domain,
                                reviseSpecialist(agent, domain, userRequest, context, priorSnapshot, issues,
                                        contractForSpecialists, callback))));
                    }
                    for (Future<Map.Entry<String, Map<String, Object>>> future : revisionFutures) {
                        try {
                            Map.Entry<String, Map<String, Object>> entry = future.get();
                            String domain = entry.getKey();
                            Map<String, Object> revised = entry.getValue();
                            // Only replace the specialist's prior (working) answer
                            // when the revision actually produced something usable -
                            // a failed or blank revision must never overwrite a good
                            // answer with a worse one.
                            if (isUsableResult(revised)) {
                                taskResults.put(domain, revised);
                                blockersFixed += byDomain.getOrDefault(domain, List.of()).size();
                            } else {
                                logger.warn("Revision for {} produced no usable result, keeping prior answer", domain);
                            }
                        } catch (Exception e) {
                            logger.error("Revision task failed", e);
                        }
                    }
                }

                Map<String, Object> resynthesis = runSynthesis(userRequest, context, requiredAgents, taskResults,
                        sharedContract, coordinator, callback, ts);
                if (resynthesis == null) break;
                taskResults.put("synthesis", resynthesis);
                synthesisResponseText = String.valueOf(resynthesis.getOrDefault("response", ""));
                blockers = parseBlockingIssues(synthesisResponseText);
            }

            // Never let the raw machine-readable marker block reach a user -
            // strip it from whatever synthesis text ends up stored, regardless
            // of whether the loop above ran, exited early, or the model closed
            // the block with malformed JSON that parseBlockingIssues gave up on.
            Object storedSynthesis = taskResults.get("synthesis");
            if (storedSynthesis instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> synthesisMap = (Map<String, Object>) storedSynthesis;
                Object resp = synthesisMap.get("response");
                if (resp != null) {
                    Map<String, Object> cleaned = new HashMap<>(synthesisMap);
                    cleaned.put("response", stripBlockingIssuesBlock(String.valueOf(resp)));
                    taskResults.put("synthesis", cleaned);
                }
            }
        }

        long elapsed = System.currentTimeMillis() - start;
        String tronResponse = buildTronChatResponse(userRequest, agentsUsed, taskResults, elapsed, fixIterations);

        Map<String, Object> finalResult = new HashMap<>();
        finalResult.put("status",      "completed");
        finalResult.put("agents_used", agentsUsed);
        finalResult.put("results",     taskResults);
        finalResult.put("elapsed_ms",  elapsed);
        finalResult.put("tron_response", tronResponse);
        finalResult.put("message",     tronResponse);
        finalResult.put("fix_iterations", fixIterations);
        finalResult.put("blockers_fixed", blockersFixed);
        this.lastExecutedTime = elapsed;
        return finalResult;
    }

    /**
     * Runs (or re-runs) the cross-domain synthesis step against whatever is
     * currently in taskResults. Extracted so the fix loop above can call it
     * again after a revision round to confirm the revision actually closed
     * the gap, without duplicating the synthesis-call plumbing. Returns null
     * only when the knowledge agent itself is unavailable - a failed LLM call
     * still returns a Map with an "error" key so callers can tell the
     * difference between "nothing ran" and "it ran and failed".
     */
    private Map<String, Object> runSynthesis(String userRequest, String context, List<String> requiredAgents,
                                              Map<String, Object> taskResults, String sharedContract,
                                              MultiAgentCoordinator coordinator, StreamingCoordinatorCallback callback,
                                              TelemetryService ts) {
        SpecializedAgent knowledgeAgent = coordinator.getAgent("knowledge");
        String synthesisModel = knowledgeAgent != null && knowledgeAgent.llmBridge != null
                ? knowledgeAgent.llmBridge.getModel() : "unknown";
        if (knowledgeAgent == null) {
            logger.warn("Knowledge agent unavailable, skipping cross-domain synthesis");
            return null;
        }

        if (callback != null) callback.onAgentStart("synthesis", synthesisModel);
        try {
            String synthesisTask = buildSynthesisTask(requiredAgents, sharedContract != null);
            String synthesisContext = buildSynthesisContext(userRequest, context, requiredAgents, taskResults,
                    sharedContract);
            List<String> focus = new ArrayList<>(List.of(
                    "cross-domain conflicts", "shared assumptions", "integration risk",
                    "security boundary violations"));
            List<String> constraints = new ArrayList<>(List.of(
                    "Only report risks that emerge from combining 2+ specialists' outputs",
                    "Be specific about which domains conflict and why, referencing what each specialist actually said",
                    "If no genuine cross-domain risk exists, say so plainly instead of inventing one"));
            if (sharedContract != null) {
                focus.add("contract conformance violations");
                constraints.add("A shared contract was established before the specialists ran (included in "
                        + "the context below). Explicitly check whether any specialist's output redefines, "
                        + "renames, or contradicts a type/class/method/signature from that contract instead "
                        + "of implementing or testing against it as-is. Call out every such violation by "
                        + "name - this is the single most important thing to check.");
            }
            constraints.add(buildBlockingIssuesInstruction(requiredAgents));

            Map<String, Object> synthesisResult = knowledgeAgent.invokeScopedLLM(
                    "cross-domain risk reviewer synthesizing findings from multiple specialist agents",
                    synthesisTask, synthesisContext, focus, List.of(), constraints,
                    "knowledge",
                    "synthesis",
                    callback);
            synthesisResult = knowledgeAgent.handleEmptyResponse(synthesisResult, "Synthesis");

            if (ts != null) {
                recordTelemetry(coordinator, synthesisResult);
            }

            if (callback != null) {
                String agentModel = (String) synthesisResult.getOrDefault(
                        "selected_model", synthesisResult.getOrDefault("model", synthesisModel));
                if ("error".equals(synthesisResult.get("status"))) {
                    callback.onAgentError("synthesis", (String) synthesisResult.getOrDefault("error", "unknown"), agentModel);
                } else {
                    callback.onAgentDone("synthesis", synthesisResult, agentModel);
                }
            }
            return synthesisResult;
        } catch (Exception e) {
            logger.error("Cross-domain synthesis failed", e);
            if (callback != null) callback.onAgentError("synthesis", e.getMessage(), synthesisModel);
            return Map.of("error", e.getMessage() != null ? e.getMessage() : "unknown");
        }
    }

    /**
     * Dispatches a targeted revision to a single specialist: "here's your
     * prior answer, here's specifically what's wrong with it, fix it and
     * return your complete corrected answer." Streams through the same
     * onAgentStart/onAgentChunk/onAgentDone callback sequence as the initial
     * dispatch and reuses the same streamAgentKey (the domain name), so on
     * the frontend this naturally resets and re-renders that specialist's
     * existing chat bubble in place rather than creating a duplicate one.
     */
    private Map<String, Object> reviseSpecialist(SpecializedAgent agent, String domain, String userRequest,
                                                  String context, Map<String, Object> priorSnapshot,
                                                  List<String> issues, String sharedContract,
                                                  StreamingCoordinatorCallback callback) {
        String model = agent.llmBridge != null ? agent.llmBridge.getModel() : "unknown";
        if (callback != null) callback.onAgentStart(domain, model);
        try {
            String priorAnswer = "";
            Object priorObj = priorSnapshot.get(domain);
            if (priorObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> priorMap = (Map<String, Object>) priorObj;
                Object resp = priorMap.get("response");
                if (resp != null) priorAnswer = String.valueOf(resp);
            }

            StringBuilder issuesBlock = new StringBuilder();
            for (String issue : issues) {
                if (issue == null || issue.isBlank()) continue;
                issuesBlock.append("- ").append(issue.trim()).append("\n");
            }

            String task = "Cross-domain synthesis flagged blocking issue(s) in your previous answer below. Revise "
                    + "your answer to resolve every issue listed. Keep everything from your previous answer that is "
                    + "still correct - only change what's needed to close these specific gaps. Return your "
                    + "complete, corrected answer, not just a diff or a description of the fix.";

            String revisionContext = "Original request:\n" + (userRequest == null ? "" : userRequest) + "\n\n"
                    + (context != null && !context.isBlank() ? "Conversation context:\n" + context + "\n\n" : "")
                    + (sharedContract != null && !sharedContract.isBlank()
                            ? "=== SHARED CONTRACT (do NOT redefine, rename, or contradict anything below) ===\n"
                                    + sharedContract.trim() + "\n=== END SHARED CONTRACT ===\n\n"
                            : "")
                    + "Your previous answer:\n" + priorAnswer + "\n\n"
                    + "Blocking issue(s) to fix:\n" + issuesBlock;

            List<String> focus = List.of("resolving the specific blocking issue(s) listed",
                    "not regressing anything else in the previous answer");
            List<String> constraints = new ArrayList<>(List.of(
                    "Return your full corrected answer, not a partial diff",
                    "Address every listed issue explicitly"));
            constraints.add("CRITICAL: For every fenced code block that represents a real project file, make the "
                    + "very first line inside the fence exactly \"FILE: <relative/path/to/filename.ext>\" (no "
                    + "comment slashes, no extra text on that line), using a realistic project-relative path.");

            Map<String, Object> result = agent.invokeScopedLLM(
                    "specialist revising a prior answer to close a cross-domain blocking issue",
                    task, revisionContext, focus, List.of(), constraints, domain, domain, callback);
            result = agent.handleEmptyResponse(result, domain);

            if (callback != null) {
                String agentModel = (String) result.getOrDefault("selected_model", result.getOrDefault("model", model));
                if ("error".equals(result.get("status"))) {
                    callback.onAgentError(domain, (String) result.getOrDefault("error", "unknown"), agentModel);
                } else {
                    callback.onAgentDone(domain, result, agentModel);
                }
            }
            return result;
        } catch (Exception e) {
            logger.error("Revision failed for {}", domain, e);
            if (callback != null) callback.onAgentError(domain, e.getMessage(), model);
            return Map.of("error", e.getMessage() != null ? e.getMessage() : "unknown");
        }
    }

    private String buildBlockingIssuesInstruction(List<String> requiredAgents) {
        return "If, and only if, you find genuine cross-domain blocking issue(s) that must be fixed before this "
                + "solution is safe to ship, append the following machine-readable block to the very end of your "
                + "response, after everything else (nothing may follow it):\n"
                + "===BLOCKING_ISSUES===\n"
                + "[{\"domain\": \"<one of: " + String.join(", ", requiredAgents) + ">\", \"issue\": \"<concise, "
                + "specific description of the blocking issue>\"}]\n"
                + "===END_BLOCKING_ISSUES===\n"
                + "The block must be valid JSON (a list of objects, each with exactly \"domain\" and \"issue\" "
                + "string fields). Use only the exact domain names listed above - never a domain that isn't in "
                + "that list. Omit this block entirely when there are no blocking issues; do not emit it with an "
                + "empty array.";
    }

    /**
     * Lenient parse of the BLOCKING_ISSUES block: tolerates the block being
     * wrapped in a markdown code fence, missing its closing marker (response
     * truncated at the model's token limit), or containing malformed JSON -
     * in every one of those cases this degrades to "no blockers" rather than
     * throwing, since a parse failure here must never crash the coordinator.
     */
    private List<Map<String, String>> parseBlockingIssues(String synthesisResponse) {
        if (synthesisResponse == null || synthesisResponse.isBlank()) return List.of();

        String raw;
        Matcher closed = BLOCKING_ISSUES_PATTERN.matcher(synthesisResponse);
        if (closed.find()) {
            raw = closed.group(1);
        } else {
            Matcher open = BLOCKING_ISSUES_OPEN_PATTERN.matcher(synthesisResponse);
            if (!open.find()) return List.of();
            raw = open.group(1);
        }

        raw = raw.trim();
        raw = raw.replaceAll("^```(?:json)?\\s*", "").replaceAll("```\\s*$", "").trim();
        if (raw.isEmpty()) return List.of();

        try {
            List<Map<String, Object>> parsed = JSON.readValue(raw, new TypeReference<List<Map<String, Object>>>() {});
            List<Map<String, String>> result = new ArrayList<>();
            for (Map<String, Object> item : parsed) {
                if (item == null) continue;
                Object domain = item.get("domain");
                Object issue = item.get("issue");
                if (domain == null || issue == null) continue;
                String domainStr = String.valueOf(domain).trim();
                String issueStr = String.valueOf(issue).trim();
                if (domainStr.isEmpty() || issueStr.isEmpty()) continue;
                result.add(Map.of("domain", domainStr, "issue", issueStr));
            }
            return result;
        } catch (Exception e) {
            logger.warn("Could not parse BLOCKING_ISSUES block, treating as no blockers: {}", e.getMessage());
            return List.of();
        }
    }

    private String stripBlockingIssuesBlock(String text) {
        if (text == null) return text;
        String stripped = BLOCKING_ISSUES_PATTERN.matcher(text).replaceAll("");
        stripped = BLOCKING_ISSUES_OPEN_PATTERN.matcher(stripped).replaceAll("");
        return stripped.trim();
    }

    /**
     * Filters blockers down to ones this run can actually act on: the domain
     * must be a real specialist that's part of THIS run's requiredAgents
     * (not a stale/hallucinated domain name, and not "knowledge" itself -
     * the synthesizer can't revise its own synthesis), and groups their
     * issue text by domain so each affected specialist gets one revision
     * call covering every issue raised against it.
     */
    private Map<String, List<String>> groupByDomain(List<Map<String, String>> blockers, List<String> requiredAgents) {
        Set<String> actionable = new LinkedHashSet<>(requiredAgents);
        actionable.remove("knowledge");
        Map<String, List<String>> byDomain = new LinkedHashMap<>();
        for (Map<String, String> blocker : blockers) {
            String domain = blocker.get("domain");
            if (domain == null) continue;
            String normalized = domain.trim().toLowerCase(Locale.ROOT);
            if (!actionable.contains(normalized)) continue;
            byDomain.computeIfAbsent(normalized, k -> new ArrayList<>()).add(blocker.get("issue"));
        }
        return byDomain;
    }

    /**
     * Stable signature for a blocker set (sorted domain::issue pairs) used to
     * detect when a revision round made no measurable progress - the
     * re-synthesis reported the exact same blockers again - so the fix loop
     * can stop instead of burning its remaining iterations restating an issue
     * it can't actually resolve.
     */
    private String blockerSignature(List<Map<String, String>> blockers) {
        List<String> parts = new ArrayList<>();
        for (Map<String, String> b : blockers) {
            parts.add(b.getOrDefault("domain", "") + "::" + b.getOrDefault("issue", ""));
        }
        Collections.sort(parts);
        return String.join("||", parts);
    }

    /**
     * Gates whether a revision result is allowed to replace a specialist's
     * prior (working) answer in taskResults - a failed call or a blank
     * response must never overwrite a good prior answer with a worse one.
     */
    private boolean isUsableResult(Map<String, Object> result) {
        if (result == null) return false;
        if ("error".equals(result.get("status"))) return false;
        Object response = result.get("response");
        return response != null && !String.valueOf(response).isBlank();
    }

    /**
     * Drafts a single canonical contract (type/interface/API signatures only,
     * no implementation) before any specialist starts writing code, so the
     * parallel fan-out below implements/tests against one shared shape
     * instead of each specialist independently inventing its own. Returns
     * null (and leaves taskResults/agentsUsed untouched) if the owning agent
     * is unavailable or the call fails - contract drafting is a best-effort
     * enhancement, never a hard blocker for the rest of the coordination
     * flow.
     */
    private String draftSharedContract(String userRequest, String context, List<String> requiredAgents,
                                        MultiAgentCoordinator coordinator, StreamingCoordinatorCallback callback,
                                        Map<String, Object> taskResults, List<String> agentsUsed,
                                        TelemetryService ts) {
        String ownerDomain = null;
        for (String candidate : CONTRACT_OWNER_PRIORITY) {
            if (requiredAgents.contains(candidate)) {
                ownerDomain = candidate;
                break;
            }
        }
        if (ownerDomain == null) {
            return null;
        }

        SpecializedAgent owner = coordinator.getAgent(ownerDomain);
        if (owner == null) {
            logger.warn("Contract owner agent '{}' unavailable, skipping contract-first phase", ownerDomain);
            return null;
        }

        String ownerModel = owner.llmBridge != null ? owner.llmBridge.getModel() : "unknown";
        if (callback != null) callback.onAgentStart("contract", ownerModel);
        try {
            String contractTask = buildContractTask(requiredAgents);
            String contractContext = "Original request:\n" + (userRequest == null ? "" : userRequest) + "\n\n"
                    + (context != null && !context.isBlank() ? "Conversation context:\n" + context + "\n\n" : "");
            List<String> focus = List.of(
                    "type/interface definitions", "shared data shapes", "public method signatures",
                    "naming that every downstream specialist must reuse exactly");
            List<String> constraints = List.of(
                    "Output ONLY the contract - type/interface/class/function signatures - no implementation bodies",
                    "This contract will be handed verbatim to " + (requiredAgents.size() - 1)
                            + " other specialist(s) (" + String.join(", ", otherDomains(requiredAgents, ownerDomain))
                            + ") who will implement/test against it - name things clearly enough that they don't "
                            + "need to guess or invent alternatives",
                    "Keep it as short as possible while still being unambiguous - this is a contract, not a design doc");

            Map<String, Object> contractResult = owner.invokeScopedLLM(
                    "API/type contract designer establishing the single shared interface multiple specialists will "
                            + "build against",
                    contractTask, contractContext, focus, List.of(), constraints,
                    ownerDomain, "contract", callback);
            contractResult = owner.handleEmptyResponse(contractResult, "Contract");
            taskResults.put("contract", contractResult);
            agentsUsed.add("contract");

            if (ts != null) {
                recordTelemetry(coordinator, contractResult);
            }

            if (callback != null) {
                String agentModel = (String) contractResult.getOrDefault("selected_model",
                        contractResult.getOrDefault("model", ownerModel));
                if ("error".equals(contractResult.get("status"))) {
                    callback.onAgentError("contract", (String) contractResult.getOrDefault("error", "unknown"), agentModel);
                } else {
                    callback.onAgentDone("contract", contractResult, agentModel);
                }
            }

            Object response = contractResult != null ? contractResult.get("response") : null;
            if (response == null || String.valueOf(response).isBlank()
                    || "error".equals(contractResult.get("status"))) {
                logger.warn("Contract drafting produced no usable output, specialists will run without a shared contract");
                return null;
            }
            return String.valueOf(response);
        } catch (Exception e) {
            logger.error("Contract drafting failed, specialists will run without a shared contract", e);
            taskResults.put("contract", Map.of("error", e.getMessage() != null ? e.getMessage() : "unknown"));
            if (callback != null) callback.onAgentError("contract", e.getMessage(), ownerModel);
            return null;
        }
    }

    private String buildContractTask(List<String> requiredAgents) {
        return "This request will be implemented by " + requiredAgents.size() + " specialist agents ("
                + String.join(", ", requiredAgents) + ") working in parallel with no visibility into each "
                + "other's output. Before any of them write implementation code, define the single shared "
                + "contract they must all build against: the core type(s)/class(es), their public method "
                + "signatures, and any shared data shapes that more than one specialist will touch. Do not "
                + "implement anything - only declare the shapes (e.g. TypeScript interface/type/class "
                + "declarations with method signatures and no bodies, or the equivalent for the request's "
                + "language). Every other specialist will be told to implement or test against these exact "
                + "names rather than defining their own.";
    }

    private List<String> otherDomains(List<String> requiredAgents, String ownerDomain) {
        List<String> others = new ArrayList<>(requiredAgents);
        others.remove(ownerDomain);
        return others;
    }

    private Map<String, Object> buildSpecialistTaskPayload(String userRequest, String context,
                                                            String agentName,
                                                            List<String> focusedSkills,
                                                            String sharedContract) {
        String normalizedContext = context == null ? "" : context.trim();
        String requestSummary    = userRequest == null ? "" : userRequest.trim();
        List<String> focus       = new ArrayList<>();
        List<String> ignore      = new ArrayList<>();
        List<String> constraints = new ArrayList<>(Arrays.asList(
                "stay concise", "address only the relevant domain", "avoid unrelated suggestions"));
        boolean needsWeb = requestNeedsWebResearch(requestSummary) || requestNeedsWebResearch(normalizedContext);

        switch (agentName) {
            case "backend"    -> {
                focus.addAll(List.of("thread-safety", "race conditions", "synchronization", "API design", "database access", "caching", "performance", "error handling"));
                constraints.add("CRITICAL: Always analyze code for thread-safety bugs - race conditions, atomicity violations, visibility issues, and deadlock risks. Show specific scenarios where 2+ threads cause failures. Provide fixed code with synchronization explanation.");
                ignore.addAll(List.of("UI", "React", "CSS", "visual design"));
            }
            case "frontend"   -> {
                focus.addAll(List.of("React", "component design", "state flow", "stale closures", "memory leaks", "responsive behavior", "accessibility"));
                constraints.add("CRITICAL: Check for React state management issues - stale closures, missing useEffect dependencies, memory leaks in subscriptions, and proper cleanup. Provide working component examples with TypeScript.");
                ignore.addAll(List.of("backend services", "database schema", "deployment"));
            }
            case "qa"         -> {
                focus.addAll(List.of("edge cases", "unit tests", "integration tests", "debugging", "regression risks", "security vulnerabilities", "error handling", "verification steps"));
                constraints.add("CRITICAL: Provide specific test cases with assertions. Identify ALL edge cases and failure scenarios. Show reproducible bug scenarios. Explain root causes, not just symptoms.");
                ignore.addAll(List.of("visual styling", "deployment automation"));
            }
            case "devops"     -> {
                focus.addAll(List.of("resource optimization", "monitoring", "observability", "health checks", "reliability", "scalability", "resource usage"));
                constraints.add("CRITICAL: Analyze resource usage patterns. Suggest infrastructure improvements with observability metrics. Include health check endpoints and alerting thresholds. Provide Docker/Kubernetes examples.");
                ignore.addAll(List.of("UI implementation", "business logic"));
            }
            case "knowledge"  -> {
                focus.addAll(List.of("personal data", "emails", "documents", "notes", "calendar", "contacts", "internet access", "external APIs", "user memory"));
                ignore.addAll(List.of("code generation", "infrastructure"));
                needsWeb = false;
            }
            default           -> focus.add("core request");
        }
        if (needsWeb) {
            focus.add("internet access");
            focus.add("external APIs");
        }
        if (!focusedSkills.isEmpty()) focus.addAll(focusedSkills);

        if (!"knowledge".equals(agentName)) {
            // The chat UI offers a "download code as .zip" button on the final
            // response that reconstructs a real project folder structure from
            // the fenced code blocks in the answer. It can only place a file
            // at the right path if the specialist says what that path is, so
            // require a machine-readable marker as the first line of every
            // code fence. This is additive - it doesn't change what code gets
            // written, only how the surrounding fence is labeled - so it's
            // safe to apply unconditionally whenever a specialist might emit
            // code.
            constraints.add("CRITICAL: For every fenced code block that represents a real project file, make the "
                    + "very first line inside the fence exactly \"FILE: <relative/path/to/filename.ext>\" (no "
                    + "comment slashes, no extra text on that line), using a realistic project-relative path "
                    + "(e.g. src/main/java/com/example/Foo.java, src/components/Widget.tsx, tests/foo.test.ts). "
                    + "Put the marker on its own line before any real code. Omit the marker only for short "
                    + "illustrative snippets that are not meant to be saved as a file (e.g. a one-line shell "
                    + "command or a diff fragment).");
        }

        if (sharedContract != null && !sharedContract.isBlank() && !"knowledge".equals(agentName)) {
            // Prepend the canonical contract to this specialist's context and
            // replace the generic "stay in your lane" framing with an
            // explicit instruction not to redefine it. This is what actually
            // closes the "no shared contract" gap: every specialist sees the
            // exact same type/signature text, up front, before it starts
            // generating anything.
            normalizedContext = "=== SHARED CONTRACT (established before any specialist ran - do NOT redefine, "
                    + "rename, or invent alternate versions of anything below; implement or test against it "
                    + "exactly as given) ===\n" + sharedContract.trim()
                    + "\n=== END SHARED CONTRACT ===\n\n" + normalizedContext;
            constraints.add("CRITICAL: A shared contract is included above. Do not redeclare, rename, or invent "
                    + "your own competing version of any type/class/method it defines - implement or test against "
                    + "those exact names and signatures.");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("agent",            agentName);
        payload.put("task",             requestSummary);
        payload.put("request_summary",  requestSummary);
        payload.put("request",          requestSummary);
        payload.put("focus",            focus);
        payload.put("ignore",           ignore);
        payload.put("relevant_context", normalizedContext);
        payload.put("constraints",      constraints);
        payload.put("domain",           agentName);
        return payload;
    }

    private List<String> inferSkillsForAgent(String agentName, String request, String context) {
        String combined = (request == null ? "" : request) + " " + (context == null ? "" : context);
        String lower    = combined.toLowerCase(Locale.ROOT);
        return switch (agentName) {
            case "backend"   -> {
                List<String> s = new ArrayList<>();
                if (lower.contains("spring") || lower.contains("java") || lower.contains("api")
                        || lower.contains("database") || lower.contains("cache") || lower.contains("thread") || lower.contains("concurr")) {
                    s.add("spring_boot_configuration");
                    s.add("database_query_optimization");
                    s.add("concurrent_programming");
                }
                yield s;
            }
            case "frontend"  -> {
                List<String> s = new ArrayList<>();
                if (lower.contains("react") || lower.contains("ui") || lower.contains("component")
                        || lower.contains("typescript") || lower.contains("frontend")) {
                    s.add("react_optimization");
                    s.add("component_design");
                }
                yield s;
            }
            case "qa"        -> {
                List<String> s = new ArrayList<>();
                if (lower.contains("test") || lower.contains("debug")
                        || lower.contains("fix") || lower.contains("review")) {
                    s.add("debugging");
                    s.add("integration_testing");
                }
                yield s;
            }
            case "devops"    -> {
                List<String> s = new ArrayList<>();
                if (lower.contains("monitor") || lower.contains("metric")
                        || lower.contains("health") || lower.contains("deploy")
                        || lower.contains("performance")) {
                    s.add("performance_monitoring");
                    s.add("health_checks");
                }
                yield s;
            }
            case "knowledge" -> {
                List<String> s = new ArrayList<>();
                s.add("search_memory");
                s.add("search_documents");
                s.add("web_research");
                yield s;
            }
            default          -> List.of();
        };
    }

    private String buildTronChatResponse(String userRequest, List<String> agentsUsed,
                                          Map<String, Object> taskResults, long elapsedMs, int fixIterations) {
        StringBuilder sb = new StringBuilder();
        sb.append("I've analyzed your request and coordinated with my specialist agents.\n\n");

        if (taskResults.containsKey("contract")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> contract = (Map<String, Object>) taskResults.get("contract");
            if (contract != null && contract.containsKey("response")
                    && !String.valueOf(contract.get("response")).isBlank()) {
                sb.append("**Shared Contract (established before implementation):**\n\n");
                sb.append(contract.get("response"));
                sb.append("\n\n");
            }
        }

        sb.append("**Specialist Findings:**\n\n");
        int specialistCount = 0;
        for (String agent : agentsUsed) {
            if ("synthesis".equals(agent) || "contract".equals(agent)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) taskResults.get(agent);
            if (result != null) {
                specialistCount++;
                sb.append("**").append(Character.toUpperCase(agent.charAt(0)))
                  .append(agent.substring(1)).append(" Agent:**\n");
                if (result.containsKey("response"))
                    sb.append(result.get("response"));
                else if (result.containsKey("error"))
                    sb.append("Error: ").append(result.get("error"));
                else
                    sb.append(result);
                sb.append("\n\n");
            }
        }

        boolean hasSynthesis = taskResults.containsKey("synthesis");
        if (hasSynthesis) {
            @SuppressWarnings("unchecked")
            Map<String, Object> synthesis = (Map<String, Object>) taskResults.get("synthesis");
            sb.append("**Cross-Domain Synthesis:**\n\n");
            if (synthesis != null && synthesis.containsKey("response")) {
                sb.append(synthesis.get("response"));
            } else if (synthesis != null && synthesis.containsKey("error")) {
                sb.append("Synthesis unavailable: ").append(synthesis.get("error"));
            }
            sb.append("\n\n");
        }

        sb.append("*Processed by: Tron with ").append(specialistCount)
          .append(" specialist agent").append(specialistCount == 1 ? "" : "s")
          .append(taskResults.containsKey("contract") ? " + shared contract" : "")
          .append(hasSynthesis ? " + cross-domain synthesis" : "")
          .append(fixIterations > 0 ? " + " + fixIterations + " blocker-fix round" + (fixIterations == 1 ? "" : "s") : "")
          .append(" in ").append(elapsedMs).append("ms*");
        return sb.toString();
    }

    private String buildSynthesisTask(List<String> requiredAgents, boolean hasContract) {
        String base = "You are reviewing findings from " + requiredAgents.size() + " specialist agents ("
                + String.join(", ", requiredAgents) + ") who each independently answered part of the "
                + "same request. Identify CROSS-DOMAIN RISKS: places where one specialist's assumption, "
                + "recommendation, or implementation detail could silently break another specialist's "
                + "guarantee (for example, a frontend optimistic-update or caching assumption that could "
                + "bypass a backend row-level-security check, or a DevOps deployment step that skips a "
                + "migration a QA test plan depends on). For each risk, name the two domains involved, "
                + "the concrete failure scenario, and how to close the gap. Do not restate each "
                + "specialist's content - only synthesize what is new at the intersections between them.";
        if (hasContract) {
            base += " A shared contract (type/interface signatures) was established before the specialists "
                    + "ran, and is included in the context below - as your first and highest-priority check, "
                    + "verify each specialist actually implemented/tested against it rather than silently "
                    + "redefining, renaming, or contradicting it; call out every such violation explicitly, "
                    + "by specialist and by name.";
        }
        return base;
    }

    private String buildSynthesisContext(String userRequest, String context, List<String> requiredAgents,
                                          Map<String, Object> taskResults, String sharedContract) {
        StringBuilder sb = new StringBuilder();
        sb.append("Original request:\n").append(userRequest == null ? "" : userRequest).append("\n\n");
        if (context != null && !context.isBlank()) {
            sb.append("Conversation context:\n").append(context).append("\n\n");
        }
        if (sharedContract != null && !sharedContract.isBlank()) {
            sb.append("### Shared contract established before the specialists ran:\n")
              .append(sharedContract.trim()).append("\n\n");
        }
        for (String agentName : requiredAgents) {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) taskResults.get(agentName);
            if (result == null) continue;
            Object response = result.get("response");
            if (response == null || String.valueOf(response).isBlank()) continue;
            sb.append("### ").append(Character.toUpperCase(agentName.charAt(0))).append(agentName.substring(1))
              .append(" agent findings:\n").append(response).append("\n\n");
        }
        return sb.toString();
    }

    private List<String> analyzeRequest(String request, String context) {
        return org.opentron.backend.orchestration.DomainDetector.detectDomains(request, context);
    }
}
