import { useState, useRef, useCallback, useEffect } from 'react';
import { Send, Square, Paperclip, Search, Bot } from 'lucide-react';
import { toast } from 'sonner';
import { useAppStore, generateId } from '../../lib/store';
import { streamChat, streamResearch, chatCompletionSimple } from '../../lib/sse';
import { apiFetch, fetchSavings, getBase, coordinateAgents, detectDomains } from '../../lib/api';
import { parseCoordinatorResponse } from '../../lib/coordinator-helpers';
import { listConnectors, getSyncStatus } from '../../lib/connectors-api';
import { MicButton } from './MicButton';
import { useSpeech } from '../../hooks/useSpeech';
import { AvatarOrb } from './AvatarOrb';
import { getChatAvatarState } from './ChatVoiceIntegration';

import type {
  ChatMessage,
  MessageTelemetry,
  ResearchSearchTrace,
  ResearchSource,
  TokenUsage,
  ToolCallInfo,
} from '../../types';

function useResearchCorpusSync(enabled: boolean): {
  syncing: boolean;
  itemsSynced: number;
} {
  const [state, setState] = useState({ syncing: false, itemsSynced: 0 });

  useEffect(() => {
    if (!enabled) {
      setState({ syncing: false, itemsSynced: 0 });
      return;
    }
    let cancelled = false;

    const poll = async () => {
      try {
        const list = await listConnectors();
        const connected = list.filter((c) => c.connected);
        if (connected.length === 0) {
          if (!cancelled) setState({ syncing: false, itemsSynced: 0 });
          return;
        }
        const results = await Promise.all(
          connected.map(async (c) => {
            try {
              return await getSyncStatus(c.connector_id);
            } catch {
              return null;
            }
          }),
        );
        let syncing = false;
        let itemsSynced = 0;
        for (const r of results) {
          if (!r) continue;
          if (r.state === 'syncing') syncing = true;
          itemsSynced += r.items_synced ?? 0;
        }
        if (!cancelled) setState({ syncing, itemsSynced });
      } catch {
        // Network blip - leave previous state intact.
      }
    };

    poll();
    const interval = setInterval(poll, 5000);
    return () => {
      cancelled = true;
      clearInterval(interval);
    };
  }, [enabled]);

  return state;
}

export function InputArea({
  showAvatar = true,
  forceTronMode = false,
  messageInputLabel,
}: {
  showAvatar?: boolean;
  forceTronMode?: boolean;
  messageInputLabel?: string;
}) {
  const [input, setInput] = useState('');
  const [useTronMode, setUseTronMode] = useState(true);
  useEffect(() => {
    if (forceTronMode) setUseTronMode(true);
  }, [forceTronMode]);
  // When Tron Mode is off and a message looks like it spans multiple
  // specialist domains, sendMessage() pauses and populates this instead of
  // sending immediately, so we can offer a one-click "run this through Tron"
  // suggestion rather than silently answering with a single generalist
  // model. Detection is a cheap, no-LLM-call preview (see lib/api.ts
  // detectDomains) - it never blocks or replaces the actual send.
  const [domainSuggestion, setDomainSuggestion] = useState<{
    content: string;
    domains: string[];
    agentCount: number;
  } | null>(null);
  // Set right before re-invoking sendMessage() from "send as regular chat"
  // so the same message isn't re-flagged in a loop.
  const skipDomainCheckRef = useRef(false);
  const accumulatedContentRef = useRef('');
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const abortRef = useRef<AbortController | null>(null);
  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const agentSectionsRef = useRef<Record<string, string>>({});
  const agentMessageIdsRef = useRef<Record<string, string>>({})
  const agentModelsRef = useRef<Record<string, string>>({})
  // Short human-readable progress lines per agent (routing/model-resolved/
  // querying/response-received), populated live from 'agent_step' SSE events.
  const agentStepsRef = useRef<Record<string, string[]>>({})
  // Running sum of cost_usd across every agent_done this run (specialists +
  // synthesis), for the "Coordination complete" telemetry footer.
  const totalCostRef = useRef(0)


  const activeId = useAppStore((s) => s.activeId);
  const selectedModel = useAppStore((s) => s.selectedModel);
  const streamState = useAppStore((s) => s.streamState);
  const messages = useAppStore((s) => s.messages);
  const speechEnabled = useAppStore((s) => s.settings.speechEnabled);
  const maxTokens = useAppStore((s) => s.settings.maxTokens);
  const temperature = useAppStore((s) => s.settings.temperature);
  const createConversation = useAppStore((s) => s.createConversation);
  const addMessage = useAppStore((s) => s.addMessage);
  const updateLastAssistant = useAppStore((s) => s.updateLastAssistant);
  const setStreamState = useAppStore((s) => s.setStreamState);
  const resetStream = useAppStore((s) => s.resetStream);
  const modelLoading = useAppStore((s) => s.modelLoading);
  const deepResearch = useAppStore((s) => s.deepResearch);
  const setDeepResearch = useAppStore((s) => s.setDeepResearch);
  const corpusSync = useResearchCorpusSync(deepResearch);
  const streamingEnabled = useAppStore((s) => s.settings.streamingEnabled);

  const {
    state: speechState,
    error: speechError,
    available: speechAvailable,
    startRecording,
    stopRecording,
  } = useSpeech();

  const prevModelRef = useRef(selectedModel);
  useEffect(() => {
    if (prevModelRef.current !== selectedModel && streamState.isStreaming) {
      abortRef.current?.abort();
      if (timerRef.current) {
        clearInterval(timerRef.current);
        timerRef.current = null;
      }
      resetStream();
      abortRef.current = null;
    }
    prevModelRef.current = selectedModel;
  }, [selectedModel, streamState.isStreaming, resetStream]);

  const micDisabled = !speechEnabled || !speechAvailable || streamState.isStreaming;
  const micReason: 'not-enabled' | 'no-backend' | 'streaming' | undefined =
    !speechEnabled ? 'not-enabled'
    : !speechAvailable ? 'no-backend'
    : streamState.isStreaming ? 'streaming'
    : undefined;

  useEffect(() => {
    if (speechError) {
      toast.error(speechError, { duration: 8000 });
    }
  }, [speechError]);

  const handleMicClick = useCallback(async () => {
    if (speechState === 'recording') {
      try {
        const text = await stopRecording();
        if (text) {
          setInput((prev) => (prev ? prev + ' ' + text : text));
        }
      } catch {
        // Error is captured in useSpeech
      }
    } else {
      await startRecording();
    }
  }, [speechState, startRecording, stopRecording]);

  useEffect(() => {
    const el = textareaRef.current;
    if (!el) return;
    el.style.height = 'auto';
    el.style.height = Math.min(el.scrollHeight, 200) + 'px';
  }, [input]);

  const stopStreaming = useCallback(() => {
    abortRef.current?.abort();
    if (timerRef.current) {
      clearInterval(timerRef.current);
      timerRef.current = null;
    }
    resetStream();
  }, [resetStream]);

  const sendMessage = useCallback(async () => {
    const content = input.trim();
    if (!content || streamState.isStreaming) return;
    if (!useTronMode && !selectedModel) {
      toast.error('Pick a model first (?K)');
      return;
    }

    if (!useTronMode && !skipDomainCheckRef.current) {
      try {
        const preview = await detectDomains(content);
        if (preview.multi_domain) {
          setDomainSuggestion({ content, domains: preview.domains, agentCount: preview.agent_count });
          return;
        }
      } catch {
        // Best-effort nudge only - never block sending because the preview call failed.
      }
    }
    skipDomainCheckRef.current = false;

    setInput('');

    let convId = activeId;
    if (!convId) {
      convId = createConversation(useTronMode ? 'Tron' : selectedModel);
    }

    const priorMessages = useAppStore.getState().messages;
    const contextHistory = priorMessages
      .map((m) => `${m.role === 'assistant' ? 'Assistant' : 'User'}: ${m.content}`)
      .join('\n\n');

    const userMsg: ChatMessage = {
      id: generateId(),
      role: 'user',
      content,
      timestamp: Date.now(),
    };
    addMessage(convId, userMsg);

    const assistantMsg: ChatMessage = {
      id: generateId(),
      role: 'assistant',
      content: '',
      timestamp: Date.now(),
      isResearch: !useTronMode && (deepResearch || undefined),
    };
    addMessage(convId, assistantMsg);

    const startTime = Date.now();
    const timer = setInterval(() => {
      setStreamState({ elapsedMs: Date.now() - startTime });
    }, 100);
    timerRef.current = timer;

    const controller = new AbortController();
    abortRef.current = controller;

    let accumulatedContent = '';
    let usage: TokenUsage | undefined;
    let ttftMs: number | undefined;

    try {
      if (useTronMode) {
        // === TRON COORDINATOR MODE ===
        setStreamState({
          isStreaming: true,
          phase: 'Coordinating with specialists...',
          elapsedMs: 0,
          activeToolCalls: [],
          content: '',
        });

        useAppStore.getState().addLogEntry({
          timestamp: Date.now(),
          level: 'info',
          category: 'chat',
          message: `Tron: "${content.slice(0, 80)}${content.length > 80 ? '...' : ''}"`,
        });

        try {
          ttftMs = Date.now() - startTime;
          accumulatedContent = '';
          accumulatedContentRef.current = '';
          agentSectionsRef.current = {};
          agentMessageIdsRef.current = {};
          agentStepsRef.current = {};
          totalCostRef.current = 0;

          // Renders (or updates) an agent's "thinking" placeholder message from
          // its current model + accumulated step trace. Shared by agent_start,
          // agent_model_resolved and agent_step so all three stay consistent.
          const renderThinkingPlaceholder = (agent: string, agentName: string, model: string) => {
            const steps = agentStepsRef.current[agent] || [];
            const stepsBlock = steps.length ? '\n\n' + steps.map((s) => `  ${s}`).join('\n') : '';
            const placeholderText = `🤔 ${agentName} agent is thinking${model ? ' (' + model + ')' : ''}...${stepsBlock}`;
            const existingId = agentMessageIdsRef.current[agent];
            if (existingId) {
              useAppStore.getState().updateMessage(convId, existingId, placeholderText);
            } else {
              const agentMsg: ChatMessage = {
                id: generateId(),
                role: 'assistant',
                content: placeholderText,
                timestamp: Date.now(),
              };
              addMessage(convId, agentMsg);
              agentMessageIdsRef.current[agent] = agentMsg.id;
            }
          };

          const coordinatorResponse = await coordinateAgents(content, contextHistory, (event) => {
            if (event.type === 'status') {
              setStreamState({ phase: event.message || 'Coordinating...', agent: undefined, model: undefined });
            } else if (event.type === 'agent_start') {
              const agent = event.agent || 'specialist';
              // This model is only ever this agent's static per-domain default -
              // ConditionalRouter hasn't run yet at agent_start time, so the real
              // model isn't known until 'agent_model_resolved' arrives shortly
              // after. Keep it as a provisional label only.
              const model = (event as any).selected_model || (event as any).model || agentModelsRef.current[agent] || '';
              agentModelsRef.current[agent] = model || agentModelsRef.current[agent];

              const agentName = agent.charAt(0).toUpperCase() + agent.slice(1);
              agentSectionsRef.current[agent] = '';
              agentStepsRef.current[agent] = [];

              renderThinkingPlaceholder(agent, agentName, model);

              setStreamState({ phase: `🤔 ${agentName}${model ? ' - ' + model : ''} thinking...`, agent, model });
            } else if (event.type === 'agent_model_resolved') {
              // The real, routed model is now known - this supersedes whatever
              // provisional default agent_start reported.
              const agent = event.agent || 'specialist';
              const model = (event as any).selected_model || (event as any).model || '';
              if (!model) return;
              agentModelsRef.current[agent] = model;
              const agentName = agent.charAt(0).toUpperCase() + agent.slice(1);
              renderThinkingPlaceholder(agent, agentName, model);
              setStreamState({ phase: `🤔 ${agentName} - ${model} thinking...`, agent, model });
            } else if (event.type === 'agent_step') {
               const agent = event.agent || 'specialist';
              const message = (event as any).message || '';
              if (!message) return;
              const steps = agentStepsRef.current[agent] || [];
              agentStepsRef.current[agent] = [...steps, message];
              const agentName = agent.charAt(0).toUpperCase() + agent.slice(1);
              const model = agentModelsRef.current[agent] || '';
              renderThinkingPlaceholder(agent, agentName, model);
              setStreamState({ phase: `💬 ${agentName}: ${message}`, agent, model });
            } else if (event.type === 'agent_chunk') {
              const agent = event.agent || 'specialist';
              let chunk = event.chunk || '';
              chunk = chunk.replace(/\u0000/g, '').replace(/\r/g, '');
              if (!chunk.trim()) {
                return;
              }
              const current = agentSectionsRef.current[agent] || '';
              const next = current + chunk;
              agentSectionsRef.current[agent] = next;
              const agentName = agent.charAt(0).toUpperCase() + agent.slice(1);
              const model = (event as any).selected_model || (event as any).model || agentModelsRef.current[agent] || '';
              if (model) agentModelsRef.current[agent] = model;
              setStreamState({ phase: `💭 ${agentName}${model ? ' - ' + model : ''} thinking...`, agent, model });
            } else if (event.type === 'agent_done') {
              const preview = event.preview || '';
              const agent = event.agent || 'specialist';

              let finalContent = agentSectionsRef.current[agent] || '';
              if (!finalContent && preview) {
                const cleanPreview = preview.replace(/\u0000/g, '').replace(/\r/g, '').trim();
                finalContent = cleanPreview;
              }
              finalContent = finalContent
                .replace(/\n?\s*===BLOCKING_ISSUES===[\s\S]*?===END_BLOCKING_ISSUES===/g, '')
                .replace(/\n?\s*===BLOCKING_ISSUES===[\s\S]*$/g, '');
              finalContent = finalContent.trim();
              agentSectionsRef.current[agent] = finalContent;

              const agentName = agent.charAt(0).toUpperCase() + agent.slice(1);
              const model = (event as any).selected_model || (event as any).model || agentModelsRef.current[agent] || '';
              if (model) agentModelsRef.current[agent] = model;
              const msgId = agentMessageIdsRef.current[agent];
              const result = (event as any).result || {};
              if (typeof result.cost_usd === 'number' && result.cost_usd > 0) {
                totalCostRef.current += result.cost_usd;
              }
              if (msgId) {
                const fullContent = `✓ ${agentName} Agent${model ? ' (' + model + ')' : ''}\n\n${finalContent || '(No response)'}`;

                const agentTelemetry: MessageTelemetry | undefined =
                  (typeof result.cost_usd === 'number' && result.cost_usd > 0) ||
                  (typeof result.elapsed_ms === 'number' && result.elapsed_ms > 0)
                    ? {
                        engine: 'tron-specialist',
                        model_id: model || undefined,
                        total_ms: typeof result.elapsed_ms === 'number' ? result.elapsed_ms : undefined,
                        total_cost_usd: typeof result.cost_usd === 'number' && result.cost_usd > 0 ? result.cost_usd : undefined,
                      }
                    : undefined;

                const hasRealSplit =
                  typeof result.input_tokens === 'number' &&
                  typeof result.output_tokens === 'number' &&
                  (result.input_tokens > 0 || result.output_tokens > 0);
                const agentUsage: TokenUsage | undefined = hasRealSplit
                  ? {
                      prompt_tokens: result.input_tokens,
                      completion_tokens: result.output_tokens,
                      total_tokens:
                        typeof result.tokens_used === 'number' && result.tokens_used > 0
                          ? result.tokens_used
                          : result.input_tokens + result.output_tokens,
                    }
                  : undefined;

                useAppStore.getState().updateMessage(convId, msgId, fullContent, agentTelemetry, agentUsage);
              }

              setStreamState({ phase: '' });
            } else if (event.type === 'agent_error') {
              const agent = event.agent || 'specialist';
              const agentName = agent.charAt(0).toUpperCase() + agent.slice(1);
              const model = (event as any).model || agentModelsRef.current[agent] || '';
              const msgId = agentMessageIdsRef.current[agent];
              if (msgId) {
                useAppStore.getState().updateMessage(
                  convId,
                  msgId,
                  `❌ ${agentName} Agent${model ? ' (' + model + ')' : ''}\n\nEncountered an error while processing your request.`
                );
              }
              setStreamState({ phase: '' });
            }
          });

          const parsed = parseCoordinatorResponse(coordinatorResponse);

          useAppStore.getState().addLogEntry({
            timestamp: Date.now(),
            level: 'info',
            category: 'chat',
            message: `Tron coordinated: ${parsed.agentsUsed.join(', ') || 'specialist agents'} in ${parsed.elapsedMs}ms`,
          });

          const telemetry: MessageTelemetry = {
            engine: 'tron-coordinator',
            model_id: `Tron (${parsed.agentsUsed.join(', ') || 'specialist agents'})`,
            total_ms: parsed.elapsedMs,
            ttft_ms: ttftMs,
            total_cost_usd: totalCostRef.current > 0 ? totalCostRef.current : undefined,
          };

          setStreamState({ content: '', phase: 'Generating final summary...' });

          // Each specialist (and any cross-domain synthesis step) already
          // streamed its full answer as its own message above via the
          // agent_start/agent_chunk/agent_done handlers. parsed.tronResponse
          // is buildTronChatResponse()'s server-side recap of those SAME
          // answers concatenated again - posting it verbatim as a second
          // message duplicated every specialist's full text a second time
          // in the transcript. Only fall back to posting the full recap when
          // there were no live per-agent messages to begin with (e.g. an
          // older/non-streaming coordinator response) - otherwise post a
          // short completion note instead, since the actual content already
          // exists in the conversation.
          const hadLiveAgentMessages = Object.keys(agentSectionsRef.current).length > 0;

          if (hadLiveAgentMessages) {
            const agentCount = parsed.agentsUsed.length || Object.keys(agentSectionsRef.current).length;
            // Reassemble every specialist's raw streamed answer (backend,
            // frontend, qa, devops, ...) into one "**{Agent} Agent:**" per
            // section blob - not for display (each agent already has its own
            // bubble above), only so the completion message's Download Code
            // button can offer a single zip of the whole run's code instead
            // of forcing the user to download each agent's bubble one at a
            // time. Same "**X Agent:**" shape MessageBubble/codeArchive.ts
            // already parse for folder-per-domain fallback naming.
            const codeArchiveContent = Object.entries(agentSectionsRef.current)
              .filter(([, text]) => !!text && text.trim().length > 0)
              .map(([agent, text]) => {
                const agentName = agent.charAt(0).toUpperCase() + agent.slice(1);
                return `**${agentName} Agent:**\n\n${text}`;
              })
              .join('\n\n');
            // agentSectionsRef.current[domain] holds each specialist's LATEST
            // streamed content - if the backend's fix loop revised a
            // specialist to resolve a synthesis-flagged blocker, agent_start
            // resets that domain's buffer and the revision's own agent_done
            // overwrites it before this 'done' handler runs, so
            // codeArchiveContent (built just above) already reflects the
            // fixed code, not the original blocker-containing version.
            const fixNote = parsed.fixIterations > 0
              ? ` (auto-fixed ${parsed.blockersFixed} cross-domain blocker${parsed.blockersFixed === 1 ? '' : 's'} in ${parsed.fixIterations} round${parsed.fixIterations === 1 ? '' : 's'})`
              : '';
            const completionMsg: ChatMessage = {
              id: generateId(),
              role: 'assistant',
              content: `✅ Tron coordinated ${agentCount} specialist agent${agentCount === 1 ? '' : 's'} in ${parsed.elapsedMs}ms${fixNote}.`,
              timestamp: Date.now(),
              telemetry,
              codeArchiveContent: codeArchiveContent || undefined,
            };
            addMessage(convId, completionMsg);
            accumulatedContent = 'Coordination complete';
          } else if (parsed.tronResponse && parsed.tronResponse !== 'Processing complete.' && parsed.tronResponse.trim()) {
            const summaryMsg: ChatMessage = {
              id: generateId(),
              role: 'assistant',
              content: `## 🎯 Analysis Results\n\n${parsed.tronResponse}`,
              timestamp: Date.now(),
              telemetry,
            };
            addMessage(convId, summaryMsg);
            // Set accumulatedContent to prevent "No response" error in finally block
            accumulatedContent = 'Coordinator analysis complete';
          } else {
            // Even if no explicit summary, mark as complete
            accumulatedContent = 'Coordination complete';
          }

          setStreamState({ content: '', phase: '' });
        } catch (err: any) {
          console.error('[InputArea] Coordinator error:', err);
          const errMsg = err?.response?.data?.error || err?.message || String(err);
          accumulatedContent = `Error coordinating with Tron: ${errMsg}`;
          setStreamState({ content: accumulatedContent, phase: '' });
          updateLastAssistant(convId, accumulatedContent);
          useAppStore.getState().addLogEntry({
            timestamp: Date.now(),
            level: 'error',
            category: 'chat',
            message: `Tron error: ${errMsg}`,
          });
          toast.error(errMsg, { duration: 8000 });
        }
      } else {
        // === REGULAR CHAT MODE (Original behavior) ===
        const currentMessages = useAppStore.getState().messages;
        const apiMessages = currentMessages.map((m) => ({
          role: m.role,
          content: m.content,
        }));

        let complexity: { score: number; tier: string; suggested_max_tokens: number } | undefined;
        const toolCalls: ToolCallInfo[] = [];
        const researchTraces: ResearchSearchTrace[] = [];
        const researchSourcesByRef = new Map<number, ResearchSource>();
        const flushSources = () =>
          Array.from(researchSourcesByRef.values()).sort((a, b) => a.ref - b.ref);
        let lastFlush = 0;

        setStreamState({
          isStreaming: true,
          phase: deepResearch ? 'Researching...' : 'Generating...',
          elapsedMs: 0,
          activeToolCalls: [],
          content: '',
        });

        useAppStore.getState().addLogEntry({
          timestamp: Date.now(),
          level: 'info',
          category: 'chat',
          message: deepResearch
            ? `Research: "${content.slice(0, 80)}${content.length > 80 ? '...' : ''}"`
            : `Request: "${content.slice(0, 80)}${content.length > 80 ? '...' : ''}" - ${selectedModel}`,
        });

        if (deepResearch) {
          for await (const ev of streamResearch(content, controller.signal)) {
            if (ev.type === 'search_call') {
              const trace: ResearchSearchTrace = {
                id: generateId(),
                query: ev.arguments?.query ?? '',
                person: ev.arguments?.person,
                timeRange: ev.arguments?.time_range,
                status: 'pending',
              };
              researchTraces.push(trace);
              setStreamState({ phase: `Searching: ${trace.query}` });
              updateLastAssistant(
                convId,
                accumulatedContent,
                undefined,
                undefined,
                undefined,
                undefined,
                [...researchTraces],
                flushSources(),
              );
            } else if (ev.type === 'search_result') {
              const pending = [...researchTraces].reverse().find((t) => t.status === 'pending');
              if (pending) {
                pending.status = 'complete';
                pending.numHits = ev.num_hits;
                pending.topTitles = ev.top_titles;
              }
              if (ev.sources) {
                for (const src of ev.sources) {
                  if (src && typeof src.ref === 'number' && !researchSourcesByRef.has(src.ref)) {
                    researchSourcesByRef.set(src.ref, src);
                  }
                }
              }
              updateLastAssistant(
                convId,
                accumulatedContent,
                undefined,
                undefined,
                undefined,
                undefined,
                [...researchTraces],
                flushSources(),
              );
            } else if (ev.type === 'synthesis') {
              if (!ttftMs) ttftMs = Date.now() - startTime;
              accumulatedContent += ev.text;
              setStreamState({ content: accumulatedContent, phase: '' });
              const now = Date.now();
              if (now - lastFlush >= 80) {
                updateLastAssistant(
                  convId,
                  accumulatedContent,
                  undefined,
                  undefined,
                  undefined,
                  undefined,
                  [...researchTraces],
                  flushSources(),
                );
                lastFlush = now;
              }
            } else if (ev.type === 'system_metrics') {
              useAppStore.getState().setLiveEnergy({
                power_w: ev.power_w,
                energy_j: ev.energy_j,
                duration_s: ev.duration_s,
              });
            } else if (ev.type === 'error') {
              const msg = ev.message || 'Research failed (no detail provided)';
              accumulatedContent = accumulatedContent
                ? `${accumulatedContent}\n\n**Research stopped:** ${msg}`
                : `**Research failed:** ${msg}`;
              setStreamState({ content: accumulatedContent, phase: '' });
              useAppStore.getState().addLogEntry({
                timestamp: Date.now(),
                level: 'error',
                category: 'chat',
                message: `Deep Research error: ${msg}`,
              });
              toast.error(msg, { duration: 8000 });
            } else if (ev.type === 'done') {
              if (ev.usage) {
                usage = {
                  prompt_tokens: ev.usage.prompt_tokens ?? 0,
                  completion_tokens: ev.usage.completion_tokens ?? 0,
                  total_tokens:
                    ev.usage.total_tokens ??
                    (ev.usage.prompt_tokens ?? 0) +
                      (ev.usage.completion_tokens ?? 0),
                };
                useAppStore.getState().incrementSavings(usage);
              }
              window.setTimeout(() => {
                useAppStore.getState().setLiveEnergy(null);
              }, 1500);
              break;
            }
          }
        } else {
          for await (const sseEvent of streamChat(
            { model: selectedModel, messages: apiMessages, stream: true, temperature, max_tokens: maxTokens },
            controller.signal,
          )) {
            const eventName = sseEvent.event;

            if (eventName === 'agent_turn_start') {
              setStreamState({ phase: 'Agent thinking...' });
            } else if (eventName === 'inference_start') {
              setStreamState({ phase: 'Generating...' });
              useAppStore.getState().addLogEntry({
                timestamp: Date.now(), level: 'info', category: 'chat',
                message: `Generating with ${selectedModel}...`,
              });
            } else if (eventName === 'tool_call_start') {
              try {
                const data = JSON.parse(sseEvent.data);
                const tc: ToolCallInfo = {
                  id: generateId(),
                  tool: data.tool,
                  arguments: data.arguments || '',
                  status: 'running',
                };
                toolCalls.push(tc);
                setStreamState({
                  phase: `Calling ${data.tool}...`,
                  activeToolCalls: [...toolCalls],
                });
                updateLastAssistant(convId, accumulatedContent, [...toolCalls]);
                useAppStore.getState().addLogEntry({
                  timestamp: Date.now(), level: 'info', category: 'tool',
                  message: `Calling ${data.tool}(${data.arguments || ''})`,
                });
              } catch {}
            } else if (eventName === 'tool_call_end') {
              try {
                const data = JSON.parse(sseEvent.data);
                const tc = toolCalls.find(
                  (t) => t.tool === data.tool && t.status === 'running',
                );
                if (tc) {
                  tc.status = data.success ? 'success' : 'error';
                  tc.latency = data.latency;
                  tc.result = data.result;
                }
                setStreamState({
                  phase: 'Generating...',
                  activeToolCalls: [...toolCalls],
                });
                updateLastAssistant(convId, accumulatedContent, [...toolCalls]);
              } catch {}
            } else {
              try {
                const data = JSON.parse(sseEvent.data);
                const delta = data.choices?.[0]?.delta;
                if (data.usage) usage = data.usage;
                if (data.complexity) complexity = data.complexity;
                if (delta?.content) {
                  if (!ttftMs) ttftMs = Date.now() - startTime;
                  accumulatedContent += delta.content;
                  setStreamState({ content: accumulatedContent, phase: '' });

                  const now = Date.now();
                  if (now - lastFlush >= 80) {
                    updateLastAssistant(
                      convId,
                      accumulatedContent,
                      toolCalls.length > 0 ? [...toolCalls] : undefined,
                    );
                    lastFlush = now;
                  }
                }
              } catch {}
            }
          }
        }

        const totalMs = Date.now() - startTime;
        const _CLOUD_PREFIXES = ['gpt-', 'o1-', 'o3-', 'o4-', 'claude-', 'gemini-', 'openrouter/', 'MiniMax-', 'chatgpt-'];
        const engineLabel = _CLOUD_PREFIXES.some(p => selectedModel.startsWith(p)) ? 'cloud' : 'ollama';
        const telemetry: MessageTelemetry = {
          engine: engineLabel,
          model_id: selectedModel,
          total_ms: totalMs,
          ttft_ms: ttftMs,
          tokens_per_sec: usage?.completion_tokens
            ? usage.completion_tokens / (totalMs / 1000)
            : undefined,
          complexity_score: complexity?.score,
          complexity_tier: complexity?.tier,
          suggested_max_tokens: complexity?.suggested_max_tokens,
        };

        let audioMeta: { url: string } | undefined;
        try {
          const digestRes = await fetch(`${getBase()}/api/digest`);
          if (digestRes.ok) {
            const digest = await digestRes.json();
            if (digest.audio_available) {
              audioMeta = { url: `${getBase()}/api/digest/audio` };
            }
          }
        } catch {
          // Not a digest response or server unavailable
        }

        updateLastAssistant(
          convId,
          accumulatedContent,
          toolCalls.length > 0 ? toolCalls : undefined,
          usage,
          telemetry,
          audioMeta,
          researchTraces.length > 0 ? researchTraces : undefined,
          researchSourcesByRef.size > 0 ? flushSources() : undefined,
        );

        if (!deepResearch) {
          fetchSavings()
            .then((data) => useAppStore.getState().setSavings(data))
            .catch(() => {});
        }
      }
    } catch (err: any) {
      if (err.name === 'AbortError') {
        if (!accumulatedContent) accumulatedContent = '(Generation stopped)';
      } else {
        const errMsg = err?.message || String(err);
        accumulatedContent = accumulatedContent || `Error: ${errMsg}`;
        useAppStore.getState().addLogEntry({
          timestamp: Date.now(), level: 'error', category: 'chat',
          message: `Stream error: ${errMsg}`,
        });
      }
      useAppStore.getState().setLiveEnergy(null);
    } finally {
      if (!accumulatedContent) {
        accumulatedContent = 'No response was generated. Please try again.';
      }

      updateLastAssistant(convId, accumulatedContent);

      if (timerRef.current) {
        clearInterval(timerRef.current);
        timerRef.current = null;
      }
      resetStream();

      useAppStore.getState().addLogEntry({
        timestamp: Date.now(), level: 'info', category: 'chat',
        message: `Response: ${accumulatedContent.length} chars`,
      });
      abortRef.current = null;
    }
  }, [
    input,
    activeId,
    selectedModel,
    streamState.isStreaming,
    useTronMode,
    createConversation,
    addMessage,
    updateLastAssistant,
    setStreamState,
    resetStream,
    deepResearch,
    temperature,
    maxTokens,
  ]);

  // sendMessage is recreated (via useCallback) whenever input/useTronMode
  // change, so a ref keeps the accept/dismiss handlers below from closing
  // over a stale version when they re-trigger it right after flipping
  // useTronMode.
  const sendMessageRef = useRef(sendMessage);
  useEffect(() => {
    sendMessageRef.current = sendMessage;
  }, [sendMessage]);

  const acceptDomainSuggestion = useCallback(() => {
    if (!domainSuggestion) return;
    setDomainSuggestion(null);
    setUseTronMode(true);
    // Let the useTronMode update commit and sendMessage be rebuilt with it
    // before re-invoking, so this actually runs through the coordinator
    // instead of racing the stale (Tron-off) closure.
    requestAnimationFrame(() => sendMessageRef.current());
  }, [domainSuggestion]);

  const dismissDomainSuggestion = useCallback(() => {
    if (!domainSuggestion) return;
    setDomainSuggestion(null);
    skipDomainCheckRef.current = true;
    requestAnimationFrame(() => sendMessageRef.current());
  }, [domainSuggestion]);

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      sendMessage();
    }
  };

  const handleTextareaPaste = (e: React.ClipboardEvent<HTMLTextAreaElement>) => {
    const items = e.clipboardData?.items;
    if (!items) return;

    for (let i = 0; i < items.length; i++) {
      if (items[i].kind === 'file' && items[i].type.startsWith('image/')) {
        e.preventDefault();
        const file = items[i].getAsFile();
        if (file) {
          const reader = new FileReader();
          reader.onload = async (ev) => {
            const base64 = ev.target?.result as string;
            try {
              toast.loading('Analyzing screenshot (this may take up to 2 minutes)...', { id: 'screenshot-analysis', duration: Infinity });
              const res = await apiFetch('/v1/analyze-screenshot', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                  image_base64: base64,
                  prompt: 'Analyze this screenshot and suggest improvements',
                  context: '',
                }),
                signal: AbortSignal.timeout(150000), // 2.5 minute timeout
              });
              if (!res.ok) throw new Error(`Analysis failed: ${res.status}`);
              const result = await res.json();
              console.log('[Paste] Full response:', result);
              toast.dismiss('screenshot-analysis');
              
              if (result.status === 'completed') {
                const analysis = result.analysis || '';
                const suggestions = result.suggestions || [];
                const suggestionsText = suggestions.length > 0 
                  ? '\n\nSuggestions:\n' + suggestions.map((s: string, i: number) => `${i + 1}. ${s}`).join('\n')
                  : '';
                const fullAnalysis = `Screenshot Analysis:\n${analysis}${suggestionsText}`;
                setInput(fullAnalysis);
                toast.success('Screenshot analyzed - ready to send!');
              } else {
                toast.error(result.error || 'Analysis failed');
              }
            } catch (err: any) {
              toast.dismiss('screenshot-analysis');
              toast.error(err?.message || 'Failed to analyze screenshot');
              console.error('[Paste Analysis] Error:', err);
            }
          };
          reader.readAsDataURL(file);
        }
        break;
      }
    }
  };



  const avatarState = getChatAvatarState(streamState.isStreaming, speechState);

  return (
    <div className="px-4 pb-4 pt-2" style={{ maxWidth: 'var(--chat-max-width)', margin: '0 auto', width: '100%' }}>
      {showAvatar && (
        <div className="flex justify-center mb-4">
          <AvatarOrb state={avatarState} />
        </div>
      )}

      <div className="mb-2 flex flex-col gap-1">
        <div className="flex items-center gap-2">
          <button
            type="button"
            onClick={() => setUseTronMode(!useTronMode)}
            disabled={streamState.isStreaming || forceTronMode}
            aria-pressed={useTronMode}
            className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs transition-colors cursor-pointer disabled:cursor-default disabled:opacity-50"
            style={{
              background: useTronMode ? 'var(--color-accent-subtle)' : 'transparent',
              border: `1px solid ${useTronMode ? 'var(--color-accent)' : 'var(--color-border)'}`,
              color: useTronMode ? 'var(--color-accent)' : 'var(--color-text-tertiary)',
            }}
            title={forceTronMode
              ? 'Ghostbook uses the coordinator to retrieve archive context'
              : useTronMode ? 'Tron Coordinator: on' : 'Tron Coordinator: off'}
          >
            <Bot size={12} />
            Tron Coordinator
          </button>
          <button
            type="button"
            onClick={() => useAppStore.getState().updateSettings({ streamingEnabled: !streamingEnabled })}
            disabled={streamState.isStreaming}
            aria-pressed={streamingEnabled}
            className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs transition-colors cursor-pointer disabled:cursor-default disabled:opacity-50"
            style={{
              background: streamingEnabled ? 'var(--color-accent-subtle)' : 'transparent',
              border: `1px solid ${streamingEnabled ? 'var(--color-accent)' : 'var(--color-border)'}`,
              color: streamingEnabled ? 'var(--color-accent)' : 'var(--color-text-tertiary)',
            }}
            title={streamingEnabled ? 'Streaming: on' : 'Streaming: off'}
          >
            S
            Streaming
          </button>
          <button
            type="button"
            onClick={() => setDeepResearch(!deepResearch)}
            disabled={streamState.isStreaming || useTronMode}
            aria-pressed={deepResearch}
            className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs transition-colors cursor-pointer disabled:cursor-default disabled:opacity-50"
            style={{
              background: deepResearch ? 'var(--color-accent-subtle)' : 'transparent',
              border: `1px solid ${deepResearch ? 'var(--color-accent)' : 'var(--color-border)'}`,
              color: deepResearch ? 'var(--color-accent)' : 'var(--color-text-tertiary)',
            }}
            title={useTronMode ? 'Deep Research disabled in Tron mode' : deepResearch ? 'Deep Research: on' : 'Deep Research: off'}
          >
            <Search size={12} />
            Deep Research
          </button>
        </div>
        {deepResearch && corpusSync.syncing && corpusSync.itemsSynced > 0 && (
          <div
            className="text-[11px] leading-snug"
            style={{ color: 'var(--color-text-tertiary)' }}
          >
            Searching over{' '}
            <span key={corpusSync.itemsSynced} className="sync-bump" style={{ color: 'var(--color-text-secondary)' }}>
              {corpusSync.itemsSynced.toLocaleString()}
            </span>{' '}
            items - sync in progress, results will improve as more data is indexed.
          </div>
        )}
      </div>
      {domainSuggestion && (
        <div
          role="status"
          aria-live="polite"
          className="mb-2 flex flex-wrap items-center gap-2 rounded-xl px-3 py-2 text-xs"
          style={{
            background: 'var(--color-accent-subtle)',
            border: '1px solid var(--color-accent)',
            color: 'var(--color-text)',
          }}
        >
          <Bot size={14} style={{ color: 'var(--color-accent)', flexShrink: 0 }} />
          <span>
            This looks like it spans{' '}
            <strong>{domainSuggestion.domains.join(', ')}</strong> - run it through Tron's{' '}
            {domainSuggestion.agentCount} specialist agent{domainSuggestion.agentCount === 1 ? '' : 's'} in
            parallel instead of a single reply?
          </span>
          <div className="ml-auto flex items-center gap-2">
            <button
              type="button"
              onClick={acceptDomainSuggestion}
              className="px-2.5 py-1 rounded-full cursor-pointer"
              style={{ background: 'var(--color-accent)', color: 'var(--color-on-accent)' }}
            >
              Use Tron agents
            </button>
            <button
              type="button"
              onClick={dismissDomainSuggestion}
              className="px-2.5 py-1 rounded-full cursor-pointer"
              style={{ background: 'transparent', border: '1px solid var(--color-border)', color: 'var(--color-text-tertiary)' }}
            >
              Send as regular chat
            </button>
          </div>
        </div>
      )}
      <div
        className="flex items-center gap-2 rounded-2xl px-4 py-3 transition-shadow"
        style={{
          background: 'var(--color-input-bg)',
          border: '1px solid var(--color-input-border)',
          boxShadow: 'var(--shadow-sm)',
        }}
      >
        <textarea
          ref={textareaRef}
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={handleKeyDown}
          onPaste={handleTextareaPaste}
          placeholder={useTronMode ? 'Message Tron...' : selectedModel ? 'Message OpenTron...' : 'Pick a model first (?K)...'}
          aria-label={messageInputLabel}
          rows={1}
          className="flex-1 bg-transparent outline-none resize-none text-sm leading-relaxed"
          style={{ color: 'var(--color-text)', maxHeight: '200px' }}
          disabled={streamState.isStreaming || modelLoading}
        />
        {streamState.isStreaming ? (
          <button
            onClick={stopStreaming}
            className="p-2 rounded-xl transition-colors shrink-0 cursor-pointer"
            style={{ background: 'var(--color-error)', color: 'var(--color-on-accent)' }}
            title="Stop generating"
          >
            <Square size={16} />
          </button>
        ) : (
          <div className="flex items-center gap-1">
            {!useTronMode && (
              <MicButton
                state={speechState}
                onClick={handleMicClick}
                disabled={micDisabled}
                reason={micReason}
              />
            )}

            <button
              onClick={sendMessage}
              disabled={!input.trim() || modelLoading || (!useTronMode && !selectedModel)}
              title={useTronMode ? 'Send to Tron' : selectedModel ? 'Send message' : 'Pick a model first (?K)'}
              className="p-2 rounded-xl transition-colors shrink-0 cursor-pointer disabled:opacity-30 disabled:cursor-default"
              style={{
                background: input.trim() ? 'var(--color-accent)' : 'var(--color-bg-tertiary)',
                color: input.trim() ? 'white' : 'var(--color-text-tertiary)',
              }}
            >
              <Send size={16} />
            </button>
          </div>
        )}
      </div>
      <div className="flex items-center justify-center mt-2 text-[11px]" style={{ color: 'var(--color-text-tertiary)' }}>
        <span>
          <kbd className="font-mono">Enter</kbd> to send &middot;{' '}
          <kbd className="font-mono">Shift+Enter</kbd> for new line
        </span>
      </div>
    </div>
  );
}










