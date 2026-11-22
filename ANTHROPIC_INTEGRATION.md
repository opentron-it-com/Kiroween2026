# Anthropic Integration in Hybrid Orchestration

## Status: ✅ COMPLETE

Your OpenTron backend has full Anthropic (Claude) integration into the hybrid orchestration system. This document confirms the implementation and provides setup instructions.

---

## Architecture Overview

```
┌──────────────────────────────────────────────────────────────┐
│                 OpenTron Java Backend                         │
│                  (Hybrid Orchestration)                       │
└────────────────┬─────────────────────────────────────────────┘
                 │
    ┌────────────┴────────────┐
    ▼                         ▼
┌─────────────────┐    ┌──────────────────┐
│  ComplexityClassifier  │    CloudModelService   │
│  (Ollama Local)        │  (Anthropic + Google)  │
│  qwen3:4b - Triage      │  Gemini 4 + Claude 5.5 │
│  SIMPLE/COMPLEX/EXTREME│  API Keys + Rate Limits│
└─────────────────┘    └──────────────────┘
    │                         │
    │ Local-only              │ Cloud key available
    ▼                         ▼
┌──────────────────┐    ┌─────────────────────┐
│ OllamaModelClient│    │ CloudModelClient    │
│ (Local, Free)    │    │ + AnthropicClient   │
│ role-specific    │    │ Haiku 5.5 / Sonnet  │
│ local models     │    │ 5.5 / Fable 5.2     │
└──────────────────┘    └─────────────────────┘
         │
         │ Single Unified Response
         ▼
    ┌─────────────┐
    │ Frontend    │
    │ User Output │
    └─────────────┘
```

---

## Component Breakdown

### 1. **ComplexityClassifierNode** (Fast Local Router)
- **File**: `orchestration/ComplexityClassifierNode.java`
- **Purpose**: Uses local `qwen3:4b` to classify requests as SIMPLE, COMPLEX, or EXTREME
- **Classification Logic**:
  - **SIMPLE**: Routine requests (→ Haiku 5.5 or Qwen2.5 Coder)
  - **COMPLEX**: Substantial engineering or synthesis work (→ Sonnet 5.5 or DeepSeek R1)
  - **EXTREME**: Rare anomalies and unusually difficult investigations (→ Fable 5.2)
- **Cost**: ~200ms latency, zero API cost (runs locally)

### 2. **ConditionalRouter** (Smart Orchestrator)
- **File**: `orchestration/ConditionalRouter.java`
- **Purpose**: Routes requests to the right model client based on complexity and domain
- **Features**:
  - Checks for Anthropic API key availability
  - Falls back to Ollama if Claude not available
  - Supports explicit model selection (user can request Claude directly)
  - Domain-aware model selection (backend, frontend, qa, devops, knowledge)
  - Fallback chain: explicit model → classified route → Ollama safety net

### 3. **ModelClient Interface** (Abstraction)
- **File**: `llm/ModelClient.java`
- **Purpose**: Unified interface for all model providers
- **Implementations**:
  - `OllamaModelClient`: Local inference (free)
  - `CloudModelClient`: Unified cloud provider adapter
  - `AnthropicClient`: Direct Anthropic Messages API adapter

### 4. **CloudModelService** (Multi-Provider API Handler)
- **File**: `util/CloudModelService.java`
- **Purpose**: Routes cloud API calls to correct provider
- **Supports**:
  - **Anthropic (Claude)**: Messages API v1, prompt caching, streaming
  - **Google (Gemini)**: generativelanguage.googleapis.com
  - **OpenAI**: chat/completions endpoint
  - **OpenRouter**: Proxy routing to multiple models
- **API Key Management**:
  - Reads from environment variables: `ANTHROPIC_API_KEY`
  - Falls back to frontend overrides (via X-API-Keys header)
  - Gracefully degrades if API key missing

### 5. **AnthropicClient** (Direct Anthropic Integration)
- **File**: `llm/anthropic/AnthropicClient.java`
- **Features**:
  - Full streaming support (server-sent events)
  - Prompt caching enabled (90% input token cost reduction)
  - Pricing calculation (Sonnet model cost tracking)
  - Health checks
  - Cache metrics tracking (cache_creation_tokens, cache_read_tokens)

### 6. **AnthropicResponse** (DTO)
- **File**: `llm/anthropic/AnthropicResponse.java`
- **Purpose**: Type-safe deserialization of Anthropic API responses
- **Tracks**: input_tokens, output_tokens, cache_creation_input_tokens, cache_read_input_tokens

---

## Request Flow (Detailed)

### Scenario 1: SIMPLE Request
```
User Request: "What is a Java virtual thread?"
    ↓
ConditionalRouter.route()
    ↓
ComplexityClassifier classifies as SIMPLE (~200ms)
    ↓
With Anthropic configured, ConditionalRouter selects Haiku 5.5; otherwise it selects a local role model
    ↓
AnthropicClient or OllamaModelClient
    ↓
Response: text, provider and token cost metadata
```

### Scenario 2: COMPLEX Request (Claude Path)
```
User Request: "Design a multi-tenant database schema with RLS for Spring Boot"
    ↓
ConditionalRouter.route()
    ↓
ComplexityClassifier classifies as COMPLEX (~200ms)
    ↓
ConditionalRouter detects ANTHROPIC_API_KEY → selects Sonnet 5.5
    ↓
CloudModelClient → AnthropicClient → POST https://api.anthropic.com/v1/messages
    ↓
Response: text, ~2000 tokens, ~$0.03 cost
```

### Scenario 3: Extreme task or anomaly
```
Classifier identifies an unusually difficult task or anomaly
    ↓
ConditionalRouter selects "claude-fable-5-2"
    ↓
Fable is reserved for EXTREME classification; ordinary complex tasks use Sonnet 5.5
    ↓
CloudModelClient → AnthropicClient → Anthropic API
```

---

## Setup Instructions

### Step 1: Get Anthropic API Key
1. Visit https://console.anthropic.com/
2. Sign up or log in
3. Navigate to **API Keys**
4. Create a new API key
5. Copy the key (starts with `sk-ant-...`)

### Step 2: Configure Environment Variable
```bash
# Linux/macOS
export ANTHROPIC_API_KEY="sk-ant-..."

# Windows PowerShell
$env:ANTHROPIC_API_KEY = "sk-ant-..."

# Docker (.env file in backend)
ANTHROPIC_API_KEY=sk-ant-...
```

### Step 3: Verify Setup
```bash
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "query": "Architect a multi-tenant SaaS backend",
    "domain": "backend",
    "selectedModel": "claude-sonnet-5-5"
  }'
```

### Step 4: Monitor Logs
```bash
# Watch for "Routed to CLAUDE" messages
grep -i "claude\|anthropic" logs/backend.log

# Check cost tracking
grep -i "cost_usd" logs/backend.log
```

---

## Cost Optimization

Standard rates per 1M tokens, checked 2026-11:

| Model | Input | Output | Routing |
|-------|------:|-------:|---------|
| Claude Haiku 5.5 | $0.10 | $0.50 | SIMPLE |
| Claude Sonnet 5.5 | $2.00 | $10.00 | COMPLEX |
| Claude Fable 5.2 | $8.00 | $40.00 | EXTREME/anomaly only |

Fable is not selected for ordinary complex work. The exact model rates are also
maintained in `PricingService`.

---

## Model Selection Strategy

### Current Model Roles

| Task | Anthropic | Local Ollama |
|------|-----------|--------------|
| Triage | — | `qwen3:4b` |
| Simple requests | `claude-haiku-5-5` | `qwen2.5-coder:7b` for code; `gemma3:4b` for knowledge |
| Complex requests | `claude-sonnet-5-5` | `deepseek-r1:8b` for code; `gemma3:4b` for knowledge |
| Extreme tasks/anomalies | `claude-fable-5-2` | `deepseek-r1:8b` |
| Google cloud | Gemini 4 Carbon, Argon, Neon, Hydrogen |

### Fallback Chain
1. **Anthropic**: Haiku for SIMPLE, Sonnet for COMPLEX, Fable only for EXTREME
2. **Google**: current Gemini 4 model when selected and configured
3. **Local Ollama**: role-specific models when no cloud provider is selected

---

## Configuration Files

### File: `orchestration/ConditionalRouter.java`
- Line 45-46: Check Anthropic API key availability
- Line 125+: Explicit Claude model handling
- Line 145+: Classification-based routing
- Line 210+: Model selection per domain

### File: `util/CloudModelService.java`
- Line 41-54: `getCloudProvider()` - Identifies provider by model prefix
- Line 100+: `getApiKey()` - Retrieves API key from env or frontend
- Line 200+: `callAnthropic()` - Main Anthropic API call logic
- Line 230+: `buildAnthropicPayload()` - Request payload construction

### File: `llm/anthropic/AnthropicClient.java`
- Line 50-70: Direct streaming implementation
- Line 75-85: Prompt caching headers
- Line 140+: Cost calculation logic

---

## Troubleshooting

### Issue: "Anthropic API key not configured"
**Solution**: Set `ANTHROPIC_API_KEY` environment variable or pass via frontend

### Issue: Requests always routed to Ollama despite COMPLEX classification
**Solution**: Verify Anthropic API key is set and CloudModelService is injected

### Issue: High token costs
**Solution**: Enable prompt caching (already enabled in AnthropicClient)
- Reuse system prompts across requests
- CloudModelClient automatically handles caching headers

### Issue: Slow responses from Claude
**Solution**: 
- Expected latency: 2-5s for Claude (vs 500ms for Ollama)
- Use SIMPLE requests for real-time use cases
- Cache system prompts to reduce input tokens

---

## API Endpoints

### POST `/api/chat`
Routes a request through the hybrid orchestration.

**Request**:
```json
{
  "query": "Your question here",
  "domain": "backend",
  "selectedModel": "claude-sonnet-5-5",
  "apiKeys": {
    "anthropic": "sk-ant-..."
  }
}
```

**Response**:
```json
{
  "status": "completed",
  "response": "Claude's detailed response...",
  "model": "claude-sonnet-5-5",
  "provider": "anthropic",
  "router": "CLAUDE",
  "classification": "COMPLEX",
  "tokens_used": 2345,
  "input_tokens": 1567,
  "output_tokens": 778,
  "cache_creation_tokens": 0,
  "cache_read_tokens": 0,
  "cost_usd": 0.0168,
  "elapsed_ms": 3421
}
```

---

## Next Steps

1. **Set API Key**: Add `ANTHROPIC_API_KEY` to your environment
2. **Build & Deploy**: `mvn clean package -DskipTests`
3. **Test**: Verify SIMPLE, COMPLEX, and EXTREME classifications route to the intended tiers
4. **Monitor**: Watch logs for selected model, cost, and request classification

---

## Summary

✅ **Anthropic is fully integrated into your hybrid orchestration.**

- Qwen3 4B handles local triage
- Qwen2.5 Coder, Gemma 3, and DeepSeek R1 handle local specialist work
- Haiku 5.5 and Sonnet 5.5 handle routine and complex Anthropic work
- Fable 5.2 is reserved for rare extreme cases and anomalies
- Gemini 4 Carbon, Argon, Neon, and Hydrogen are available for Google routing
- Fallback-safe: works without API key (degrades to Ollama)
- Production-ready streaming and error handling
