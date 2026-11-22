# OpenTron Hybrid Model Orchestration Architecture (Updated: Model Routing, Nov 2026)

## 1. HIGH-LEVEL SYSTEM FLOW

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         USER REQUEST ENTRY POINT                             │
│  "Summarize the key architectural differences between PostgreSQL's JSON     │
│   and JSONB data types."                                                     │
└────────────────────────────┬────────────────────────────────────────────────┘
                             │
                             ▼
         ┌───────────────────────────────────────────────────┐
         │   DomainDetector (Intent-aware domain routing)    │
         │   ─────────────────────────────────────────────   │
         │   • Explicit domain labels ("Backend:", "QA -")   │
         │     always win, for multi-role breakdowns         │
         │   • Conceptual/explanatory intent (summarize,     │
         │     explain, compare, what is, ...) WITHOUT       │
         │     actionable intent (implement, build, debug,   │
         │     my code, ...) -> forced to "knowledge",       │
         │     even if it names a backend/frontend/etc. tech │
         │   • Otherwise: keyword match against every domain │
         │     independently (backend/frontend/qa/devops),   │
         │     falls back to "knowledge" if none match        │
         └────────┬────────────────────────────────────────┘
                  │
       ┌──────────┴──────────┐
       │                     │
       ▼                     ▼
  ConditionalRouter    ComplexityClassifierNode
  (per detected domain)  (qwen3:4b local triage,
                          SIMPLE/COMPLEX/EXTREME)
                  │
                  ▼
   ┌──────────────────────────────────────────────────────┐
   │        Domain-aware model tier selection              │
   │   (see Section 7 for the full matrix)                 │
   │                                                        │
   │  Anthropic: Haiku 5.5 (SIMPLE), Sonnet 5.5 (COMPLEX)   │
   │             Fable 5.2 (EXTREME/anomalies only)         │
   │  Local code: Qwen2.5 Coder / DeepSeek R1; knowledge: Gemma 3 │
   └──────────────────────────────────────────────────────┘
```

---

## 2. DOMAIN-BASED AGENT TASK ORCHESTRATION

Anthropic routing uses the same complexity ladder across domains. Fable is reserved
for extreme tasks and anomalies; local code and knowledge tasks use role-specific
Ollama models:

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                        OPENTRON MULTI-DOMAIN AGENTS                          │
└──────────────────────────────────────────────────────────────────────────────┘

┌─────────────────┐  ┌─────────────────┐  ┌──────────────┐  ┌─────────────────┐
│   FRONTEND      │  │    BACKEND      │  │      QA      │  │     DEVOPS      │
│   AGENT         │  │     AGENT       │  │    AGENT     │  │     AGENT       │
│─────────────────│  │─────────────────│  │──────────────│  │─────────────────│
│                 │  │                 │  │              │  │                 │
│ React/Vue/TS    │  │ Java/Spring     │  │ Test Cases   │  │ Infrastructure  │
│ CSS/UI/Routing  │  │ Microservices   │  │ Automation   │  │ CI/CD Pipelines │
│ Responsive      │  │ DB Schema       │  │ Edge Cases   │  │ Kubernetes      │
│ Accessibility   │  │ Authentication  │  │ Performance  │  │ Docker          │
│                 │  │ Architecture    │  │ Security     │  │ Monitoring      │
│                 │  │                 │  │              │  │                 │
│ ┌─────────────┐ │  │ ┌─────────────┐ │  │ ┌──────────┐ │  │ ┌─────────────┐ │
│ │Complexity:  │ │  │ │Complexity:  │ │  │ │Complexity│ │  │ │Complexity:  │ │
│ │SIMPLE→      │ │  │ │SIMPLE→      │ │  │ │SIMPLE→   │ │  │ │SIMPLE→      │ │
│ │Haiku 5.5    │ │  │ │Haiku 5.5    │ │  │ │Haiku 5.5 │ │  │ │Haiku 5.5    │ │
│ │COMPLEX→     │ │  │ │COMPLEX→     │ │  │ │COMPLEX→  │ │  │ │COMPLEX→     │ │
│ │Sonnet 5.5   │ │  │ │Sonnet 5.5   │ │  │ │Sonnet 5.5│ │  │ │Sonnet 5.5   │ │
│ │(never Opus) │ │  │ │(Claude)     │ │  │ │(never    │ │  │ │(Claude)     │ │
│ │(via         │ │  │ │(via         │ │  │ │ Opus)    │ │  │ │(via         │ │
│ │AnthropicC.) │ │  │ │AnthropicC.) │ │  │ │(via      │ │  │ │AnthropicC.) │ │
│ │             │ │  │ │             │ │  │ │Anthrop.) │ │  │ │             │ │
│ └─────────────┘ │  │ └─────────────┘ │  │ └──────────┘ │  │ └─────────────┘ │
│                 │  │                 │  │              │  │                 │
│ ┌─────────────┐ │  │ ┌─────────────┐ │  │ ┌──────────┐ │  │ ┌─────────────┐ │
│ │  Primary    │ │  │ │  Primary    │ │  │ │ Primary  │ │  │ │  Primary    │ │
│ │  Models:    │ │  │ │  Models:    │ │  │ │ Models:  │ │  │ │  Models:    │ │
│ │ • Haiku 5.5 │ │  │ │ • Haiku 5.5 │ │  │ │• Haiku   │ │  │ │ • Haiku 5.5 │ │
│ │   (SIMPLE)  │ │  │ │   (SIMPLE)  │ │  │ │  4.5     │ │  │ │   (SIMPLE)  │ │
│ │ • Sonnet 5.5│ │  │ │ • Sonnet 5.5│ │  │ │• Sonnet  │ │  │ │ • Sonnet 5.5│ │
│ │   (COMPLEX) │ │  │ │   (COMPLEX) │ │  │ │  5.5     │ │  │ │   (COMPLEX) │ │
│ │             │ │  │ │             │ │  │ │ (COMPLEX)│ │  │ │             │ │
│ └─────────────┘ │  │ └─────────────┘ │  │ └──────────┘ │  │ └─────────────┘ │
│                 │  │                 │  │              │  │                 │
│ ┌─────────────┐ │  │ ┌─────────────┐ │  │ ┌──────────┐ │  │ ┌─────────────┐ │
│ │ Fallback:   │ │  │ │ Fallback:   │ │  │ │ Fallback:│ │  │ │ Fallback:   │ │
│ │ role-specific│ │  │ │ role-specific│ │  │ │ Gemma 3  │ │  │ │ role-specific│ │
│ │ (Ollama)    │ │  │ │ (Ollama)    │ │  │ │ (local)  │ │  │ │ (Ollama)    │ │
│ │ (emergency) │ │  │ │ (emergency) │ │  │ │(emerg.)  │ │  │ │ (emergency) │ │
│ └─────────────┘ │  │ └─────────────┘ │  │ └──────────┘ │  │ └─────────────┘ │
│                 │  │                 │  │              │  │                 │
└─────────────────┘  └─────────────────┘  └──────────────┘  └─────────────────┘
        │                   │                   │                   │
        └───────────────────┴───────────────────┴───────────────────┘
                            │
                            ▼
        ┌───────────────────────────────────────────────────────────┐
        │   KNOWLEDGE AGENT (dual-purpose)                          │
        │   ──────────────────────────────────────────────────────  │
        │   Reached via DomainDetector when:                        │
        │   • No domain keyword matches at all (default fallback), OR│
        │   • The request is conceptual/explanatory (summarize,    │
        │     explain, compare, what is, ...) WITHOUT actionable   │
        │     intent - even if it names a backend/frontend/etc.    │
        │     technology (e.g. "summarize the differences between  │
        │     PostgreSQL JSON and JSONB" no longer misroutes to    │
        │     Backend just because it says "PostgreSQL")           │
        │                                                            │
        │   Two jobs, one system prompt now distinguishes them:     │
        │   • GENERAL/CONCEPTUAL Q&A - answered from the model's   │
        │     own knowledge, no retrieval tools invoked             │
        │   • PERSONAL/CONTEXTUAL - search_memory/emails/docs/      │
        │     notes/calendar/contacts + Tavily web research         │
        │   • Cross-domain entity linking & pattern recognition      │
        │   • Recommendation synthesis                              │
        │   • Memory persistence (PostgreSQL)                      │
        │   • SIMPLE -> Haiku 5.5, COMPLEX -> Sonnet 5.5           │
        │   • EXTREME/anomalies -> Fable 5.2 only                  │
        │   • Local knowledge model: Gemma 3 4B                    │
        └───────────────────────────────────────────────────────────┘
```

---

## 3. EXECUTION STATE & ROUTING DECISION TREE

```
                    START: New User Request
                            │
                            ▼
              ┌──────────────────────────┐
              │ DomainDetector.detectDomains()│
              │ (shared by MultiAgentCoordinator│
              │  and /v1/orchestrate/detect-domains)│
              └──────────┬───────────────┘
                         │
       ┌─────────────────┼─────────────────────┐
       │                 │                      │
       ▼                 ▼                      ▼
  Explicit label    Conceptual intent      Keyword match
  found ("Backend:",  matched AND no        against backend/
  "QA -", etc.)       actionable intent     frontend/qa/devops
       │              found                 patterns
       │                 │                      │
       ▼                 ▼                      ▼
  Use labelled     domain = ["knowledge"]  Any match? -> those
  domain(s)        (even if it names a     domains (independently
  (can be          backend/frontend/etc.   evaluated, so a request
  multi-domain)    technology by name)     can span 1-4 domains)
                                            No match? -> ["knowledge"]
                            │
                            ▼
              ┌──────────────────────────┐
              │ Route Decision Point     │
              │ (per detected domain)    │
              └──────────┬───────────────┘
                         │
           ┌─────────────┼─────────────┐
           │             │             │
           ▼             ▼             ▼
      (A)          (B)           (C)
   Explicit      Check        Classify
   Model      API Key      Complexity
   Selection?  Available?    (If no explicit)
       │          │              │
    YES│       YES│          SIMPLE│COMPLEX
       │          │              │
       ▼          ▼              ▼
   ┌────────┐ ┌─────────┐   ┌────────────┐
   │ Use    │ │ Use     │   │ Decide per │
   │ Claude │ │ Route   │   │ domain +   │
   │ as     │ │ to      │   │ complexity │
   │ Specified         Router  │ (Section 7)│
   │        │ │ Node    │   │            │
   └───┬────┘ └────┬────┘   └────┬───────┘
       │           │             │
       └───────────┴─────────────┘
               │
               ▼
   ┌──────────────────────────────────────────┐
   │ ComplexityClassifierNode                 │
   │ qwen3:4b (local triage)                  │
   │ SIMPLE / COMPLEX / EXTREME               │
   │ Cost: $0.00                              │
   └────┬───────────────────────────────────┘
        │
    ┌───┴────┐
    │        │
    ▼        ▼        ▼
 SIMPLE   COMPLEX   EXTREME
    │        │         │
    ▼        ▼         ▼
 Haiku    Sonnet     Fable
  5.5      5.5        5.2
    │        │         │
    └────────┴─────────┘
                    │
                    ▼
┌────────────────────────────────────────────────────────────┐
│ Execute & Return                                           │
│ Unified Response                                           │
│ • Response text                                            │
│ • Tokens used (input + output)                             │
│ • Cost (varies by model: Haiku/Sonnet/Opus)               │
│ • Latency                                                  │
│ • Model used (Haiku/Sonnet/Opus)                          │
│ • Cache effectiveness                                      │
└────────────────────────────────────────────────────────────┘
    │
    ▼
SEND TO USER
```

---

## 4. STATE FLOW: AgentState Record (Java 21)

```
┌─────────────────────────────────────────────────────────────────────────┐
│                     AgentState (Immutable Record)                        │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  Field              Type           Flow                                 │
│  ───────────────────────────────────────────────────────────────────   │
│                                                                          │
│  userQuery          String         Input: "Design a database schema"   │
│                                                                          │
│  routeTarget        String         Router Node Decision:                │
│                                    • "CLAUDE_SONNET" (default)         │
│                                    • "CLAUDE_OPUS" (BACKEND/DEVOPS      │
│                                    •   COMPLEX only)                    │
│                                    • "CLAUDE_HAIKU" (FRONTEND/QA        │
│                                    •   SIMPLE only)                     │
│                                    • null (initial state)              │
│                                                                          │
│  finalResponse      String         Execution Result:                   │
│                                    • Complete response text             │
│                                    • JSON payload from model             │
│                                    • null (until execution)             │
│                                                                          │
│  metadata           Map<String,    Tracking Data:                      │
│                     Object>        • tokens_used: 2345                 │
│                                    • input_tokens: 1567                │
│                                    • output_tokens: 778                │
│                                    • cost_usd: varies (Haiku/Sonnet/   │
│                                    •           Opus pricing)           │
│                                    • elapsed_ms: 300-5000ms            │
│                                    • model_used: "claude-haiku-5-5",   │
│                                    •              "claude-sonnet-5-5", │
│                                    •              "claude-fable-5-2"  │
│                                    • domain: "backend", "knowledge",   │
│                                    •         etc. (from DomainDetector)│
│                                    • cache_hit: true/false             │
│                                    • provider: "CLAUDE"                │
│                                                                          │
│  Methods:                                                               │
│  ────────                                                               │
│  withRoute(target)      → new AgentState with updated routeTarget      │
│  withResponse(text)     → new AgentState with finalResponse            │
│  withMetadata(key,val)  → new AgentState with updated metadata         │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 5. VIRTUAL THREAD ORCHESTRATION (Java 21)

```
┌────────────────────────────────────────────────────────────────┐
│         GraphOrchestrator (Virtual Thread Executor)             │
├────────────────────────────────────────────────────────────────┤
│                                                                 │
│  executor.submit(() -> {                                       │
│                                                                 │
│    ┌──────────────────────────────────────────────────────┐   │
│    │ STEP 1: Initialize State                            │   │
│    │ initialState = new AgentState(                       │   │
│    │   userQuery,                                        │   │
│    │   null,           // routeTarget (not yet set)      │   │
│    │   null,           // finalResponse (not yet set)    │   │
│    │   Map.of()        // metadata (empty initially)     │   │
│    │ )                                                    │   │
│    └──────────────────────────────────────────────────────┘   │
│                       │                                        │
│                       ▼                                        │
│    ┌──────────────────────────────────────────────────────┐   │
│    │ STEP 1.5: Detect Domain(s)                          │   │
│    │ domains = DomainDetector.detectDomains(query, ctx)  │   │
│    │                                                      │   │
│    │ Runs in-process (pure regex, no I/O) - not a         │   │
│    │ virtual-thread-relevant step, but gates which        │   │
│    │ specialist(s) STEP 2 below dispatches to.            │   │
│    └──────────────────────────────────────────────────────┘   │
│                       │                                        │
│                       ▼                                        │
│    ┌──────────────────────────────────────────────────────┐   │
│    │ STEP 2: Route via Classifier                        │   │
│    │ routedState = routerNode.execute(initialState)      │   │
│    │                                                      │   │
│    │ • Virtual Thread UNMOUNTS during Ollama HTTP I/O     │   │
│    │ • OS thread released while waiting for classifier    │   │
│    │ • Other tasks can execute on freed thread            │   │
│    │ • Virtual Thread REMOUNTS when response arrives      │   │
│    │                                                      │   │
│    │ Returns: AgentState with routeTarget set            │   │
│    │ ("CLAUDE_SONNET", "CLAUDE_OPUS", or                │   │
│    │  "CLAUDE_HAIKU" - domain-dependent, see Section 7)  │   │
│    └──────────────────────────────────────────────────────┘   │
│                       │                                        │
│            ┌──────────┴──────────┐                             │
│            │                     │                             │
│            ▼                     ▼                             │
│     SIMPLE PATH          COMPLEX PATH                          │
│     (Fast)               (Deep Reasoning, backend/devops only) │
│            │                     │                             │
│            ▼                     ▼                             │
│    ┌──────────────────┐  ┌──────────────────────────┐         │
│    │ AnthropicNode    │  │ AnthropicNode            │         │
│    │ EXECUTES         │  │ EXECUTES Fable           │         │
│    │ Haiku/Sonnet     │  │ (EXTREME/anomalies only) │         │
│    │ by classification│  │                          │         │
│    │                  │  │                          │         │
│    │ POST to          │  │                          │         │
│    │ api.anthropic.com│  │ Virtual thread I/O       │         │
│    │ (unmounts OS     │  │ to api.anthropic.com     │         │
│    │ thread during I/O)   │ (unmounts OS thread      │         │
│    │                  │  │ during wait)             │         │
│    │ Time: ~300-500ms │  │                          │         │
│    │ Model: Haiku 5.5 │  │ Time: 2-5s               │         │
│    │ or Sonnet 5.5    │  │ Model: Fable 5.2         │         │
│    │ Provider:        │  │ Provider:                │         │
│    │ AnthropicClient  │  │ AnthropicClient          │         │
│    └────────┬─────────┘  └──────────┬───────────────┘         │
│             │                        │                         │
│             └──────────┬─────────────┘                          │
│                        │                                       │
│                        ▼                                       │
│    ┌──────────────────────────────────────────────────────┐   │
│    │ STEP 3: Return Final State                          │   │
│    │ finalState has all:                                 │   │
│    │ • routeTarget (model selection)                     │   │
│    │ • finalResponse (the actual response text)          │   │
│    │ • metadata (cost, tokens, latency, model, etc.)    │   │
│    └──────────────────────────────────────────────────────┘   │
│                                                                 │
│  }).get()  // Block until complete, return AgentState         │
│                                                                 │
└────────────────────────────────────────────────────────────────┘
```

---

## 6. HISTORICAL COST OPTIMIZATION MATRIX (Aug 2026)

> The estimates in this legacy example use retired model assignments and prices.
> For current model routing see Section 7; current rates are in `PricingService`.

```
┌──────────────────────────────────────────────────────────────────────────────┐
│  REQUEST CLASSIFICATION & COST IMPACT (domain-aware tiering)                 │
├──────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  Classification Criteria                                                    │
│  ────────────────────────                                                   │
│                                                                              │
│  SIMPLE:                          COMPLEX:                                  │
│  • Basic factual questions        • Multi-step code architecture           │
│  • Text formatting                • Complex debugging                       │
│  • Summaries                      • High-level reasoning                   │
│  • Quick lookups                  • Security design                        │
│  • Code snippets                  • Database schema (RLS, multi-tenant)    │
│  • README generation              • System design review                  │
│                                   • Performance optimization               │
│                                                                              │
│  ┌──────────────────────────────────────────────────────────────────────┐  │
│  │ Cost Analysis per Request Category (Anthropic Pricing - Aug 2026)   │  │
│  ├──────────────────────────────────────────────────────────────────────┤  │
│  │                                                                      │  │
│  │ BACKEND/DEVOPS SIMPLE -> Claude Sonnet 5:                           │  │
│  │ • Avg Tokens: 800 input + 300 output                               │  │
│  │ • Cost: (0.8×$3.00/1M) + (0.3×$15.00/1M) = $0.0069                │  │
│  │ • Cache Hit: $0.0051                                                │  │
│  │                                                                      │  │
│  │ BACKEND/DEVOPS COMPLEX -> Claude Opus 5:                            │  │
│  │ • Avg Tokens: 2,000 input + 1,000 output                          │  │
│  │ • Cost: (2.0×$15.00/1M) + (1.0×$60.00/1M) = $0.090                │  │
│  │ • Cache Hit: $0.063                                                 │  │
│  │                                                                      │  │
│  │ FRONTEND/QA SIMPLE -> Claude Haiku 4.5:                             │  │
│  │ • Avg Tokens: 500 input + 200 output                              │  │
│  │ • Cost: (0.5×$0.80/1M) + (0.2×$4.00/1M) = $0.00048                │  │
│  │ • Cache Hit: $0.00040                                               │  │
│  │                                                                      │  │
│  │ FRONTEND/QA COMPLEX -> Claude Sonnet 5 (never Opus):                │  │
│  │ • Avg Tokens: 1,200 input + 600 output                             │  │
│  │ • Cost: (1.2×$3.00/1M) + (0.6×$15.00/1M) = $0.0126                │  │
│  │ • Cache Hit: ~$0.0090                                               │  │
│  │                                                                      │  │
│  │ KNOWLEDGE (tiered) -> Haiku (SIMPLE) / Sonnet (COMPLEX):            │  │
│  │ • SIMPLE Avg Tokens: 500 input + 200 output (Haiku)                 │  │
│  │ • SIMPLE Cost: (0.5×$0.80/1M) + (0.2×$4.00/1M) = $0.00048            │  │
│  │ • COMPLEX Avg Tokens: 700 input + 350 output (Sonnet)               │  │
│  │ • COMPLEX Cost: (0.7×$3.00/1M) + (0.35×$15.00/1M) = $0.0074         │  │
│  │ • Cache Hit (COMPLEX): ~$0.0053                                     │  │
│  │                                                                      │  │
│  └──────────────────────────────────────────────────────────────────────┘  │
│                                                                              │
│  ┌──────────────────────────────────────────────────────────────────────┐  │
│  │ 1,000 Request Scenario - domain-aware tiering vs. old uniform tiering│  │
│  ├──────────────────────────────────────────────────────────────────────┤  │
│  │                                                                      │  │
│  │ Assume the old strategy: EVERY domain sent COMPLEX to Opus.         │  │
│  │ Mix: 20% backend/devops COMPLEX, 15% frontend/qa COMPLEX,           │  │
│  │      45% SIMPLE (any domain), 20% knowledge (any complexity)        │  │
│  │                                                                      │  │
│  │ OLD (uniform Opus-for-COMPLEX, no domain awareness):                │  │
│  │ • 350 Opus (backend/devops + frontend/qa COMPLEX all on Opus)      │  │
│  │ • 450 Sonnet (SIMPLE, non-knowledge)                                │  │
│  │ • 200 Haiku (knowledge only, old tiering)                          │  │
│  │ • Total (no cache): 350×$0.090 + 450×$0.0069 + 200×$0.00048        │  │
│  │   = $31.50 + $3.11 + $0.096 = $34.71                               │  │
│  │                                                                      │  │
│  │ NEW (domain-aware - Opus only for backend/devops COMPLEX):         │  │
│  │ • 200 Opus  (backend/devops COMPLEX only)                         │  │
│  │ • 150 Sonnet (frontend/qa COMPLEX, escalated from Haiku)          │  │
│  │ • 250 Sonnet (backend/devops SIMPLE)                               │  │
│  │ • 200 Sonnet (knowledge, all complexity - pinned tier)            │  │
│  │ • 200 Haiku  (frontend/qa SIMPLE)                                  │  │
│  │ • Total (no cache): 200×$0.090 + (150+250+200)×$0.0069(ish blend) │  │
│  │                     + 200×$0.00048                                  │  │
│  │   ≈ $18.00 + $4.14 + $0.096 = $22.24 (36% cheaper than old)        │  │
│  │                                                                      │  │
│  └──────────────────────────────────────────────────────────────────────┘  │
│                                                                              │
│  KEY BENEFIT: Domain-aware four-tier strategy concentrates the expensive   │
│  Opus tier only where it earns its cost                                   │
│  • Haiku 4.5: frontend/QA SIMPLE work (cheap, high-volume)                │
│  • Sonnet 5: backend/devops SIMPLE + frontend/QA COMPLEX + all knowledge  │
│  • Opus 5: backend/devops COMPLEX ONLY - real architecture/code reasoning │
│                                                                              │
└──────────────────────────────────────────────────────────────────────────────┘
```

---

## 7. MODEL SELECTION DECISION MATRIX

| Classification | Anthropic | Local code agents | Local knowledge agent |
|---|---|---|---|
| SIMPLE | `claude-haiku-5-5` | `qwen2.5-coder:7b` | `gemma3:4b` |
| COMPLEX | `claude-sonnet-5-5` | `deepseek-r1:8b` | `gemma3:4b` |
| EXTREME/anomaly | `claude-fable-5-2` | `deepseek-r1:8b` | `gemma3:4b` |

`qwen3:4b` is used for local triage. Fable is reserved for rare, unusually
difficult investigations and anomalies; ordinary complex requests use Sonnet.
Local requests use `qwen2.5-coder:7b` for simple code work, `deepseek-r1:8b`
for complex code work, and `gemma3:4b` for knowledge tasks.

The available current Gemini 4 models are `gemini-4-carbon`, `gemini-4-argon`,
`gemini-4-neon`, and `gemini-4-hydrogen`. Current provider rates are maintained
in `PricingService`.

### DomainDetector: how a request reaches "knowledge" now

`DomainDetector.detectDomains()` sends a request to the Knowledge specialist in
two distinct cases:

1. **No domain keyword matches at all** - the original fallback behavior.
2. **Conceptual/explanatory intent without actionable intent** - a request
   phrased as "summarize", "explain", "describe", "compare", "what is",
   "difference(s) between", "pros and cons", "trade-offs", "overview of", or
   "how does" is routed to Knowledge even if it names a backend/frontend/qa/
   devops technology (e.g. "PostgreSQL", "React", "Kubernetes"), **unless** the
   request also contains actionable/implementation language ("implement",
   "build", "create", "write", "refactor", "debug", "fix", "my code/app/
   project/service/api/database/schema/query/component", "test plan", "edge
   cases", "deploy", "configure", "set up", "design a/the", "review my").

   This closes a real bug: "Summarize the key architectural differences between
   PostgreSQL's JSON and JSONB data types" used to route to Backend purely
   because it said "PostgreSQL", even though it asks for an explanation, not
   implementation work. "Explain how to implement JWT auth in Spring Boot"
   still correctly routes to Backend, because "implement" is actionable intent.

The Knowledge agent's system prompt and skill tags were updated to match: it
now explicitly distinguishes GENERAL/CONCEPTUAL questions (answered from its
own knowledge, no retrieval tools invoked) from PERSONAL/CONTEXTUAL questions
(search_memory/emails/documents/notes/calendar/contacts + Tavily web research),
and its skill list includes `concept_explanation`, `general_qa`,
`technical_comparison`, and `cross_domain_synthesis` alongside the retrieval
tool tags.

---

## 8. KEY FEATURES & BENEFITS

### ✅ Complexity-Based Model Tiers
```
• Haiku 5.5: SIMPLE requests
• Sonnet 5.5: ordinary COMPLEX requests
• Fable 5.2: EXTREME requests and anomalies only
• Local models: Qwen3 triage; Qwen2.5 Coder / DeepSeek R1 for code;
  Gemma 3 for knowledge
• Gemini 4 Carbon, Argon, Neon, and Hydrogen are available
```

### ✅ Intent-Aware Domain Detection
```
• DomainDetector evaluates every domain independently (not else-if), so a
  single request can span backend + frontend + qa + devops at once
• Explicit domain labels ("Backend:", "- QA -") always win, for deliberate
  multi-role breakdowns
• Conceptual/explanatory phrasing overrides keyword-only matches so
  "explain/summarize/compare X" doesn't misroute to a specialist just
  because it names that specialist's technology
• Falls back to Knowledge when nothing matches
```

### ✅ Domain-Aware Cost Optimization
```
• Fable spend is limited to EXTREME requests and anomalies
• Sonnet handles ordinary complex work; Haiku handles simple work
• Local knowledge requests use Gemma 3 4B
• Prompt caching is enabled for supported Anthropic requests
• Per-domain local routing selects code or knowledge specialists
• Transparent cost tracking (PostgreSQL)
```

### ✅ High-Density Concurrency
```
• Java 21 Virtual Threads (10,000+ concurrent tasks)
• Non-blocking I/O during all API calls
• OS thread resource efficiency
• Horizontal scalability without process forking
• Anthropic connection pooling via shared HTTP client
```

### ✅ Production-Ready State Management
```
• Immutable AgentState (Java 21 Records)
• PostgreSQL persistence
• Virtual thread safety guarantees
• Full execution audit trail
• Unified provider experience (AnthropicClient everywhere)
```

### ✅ Intelligent Fallback System
```
• Selected cloud provider → role-specific Ollama fallback
• Automatic downgrade on API failures
• Classifier failure falls back to Sonnet when Anthropic is available;
  local code uses DeepSeek R1 and knowledge uses Gemma 3
• No single point of failure
• Graceful degradation with logging
```

---

## 9. CONFIGURATION & DEPLOYMENT

```
Docker Compose Stack:
├── postgres:16-alpine
│   └── opentron_db (state + metrics)
├── ollama:latest
│   ├── qwen3:4b (triage)
│   ├── gemma3:4b (knowledge)
│   ├── qwen2.5-coder:7b (simple code tasks)
│   └── deepseek-r1:8b (complex code tasks)
└── opentron-backend (Java 21 Spring Boot)
    ├── GraphOrchestrator
    ├── DomainDetector (intent-aware domain routing)
    ├── ConditionalRouter (Haiku 5.5 / Sonnet 5.5 / Fable 5.2)
    ├── ModelSelectorService (specialist-agent defaults, kept in sync with
    │   ConditionalRouter's tiering; also owns the model-availability cache)
    ├── AnthropicClient (Anthropic adapter)
    ├── ComplexityClassifierNode
    └── Virtual Thread Executor

Configuration Variables:
├── ANTHROPIC_API_KEY (optional cloud provider)
├── GEMINI_API_KEY (optional Google provider)
└── OLLAMA_HOST (local model runtime)

Model Stack:
├── SIMPLE: claude-haiku-5-5 or local Qwen2.5 Coder
├── COMPLEX: claude-sonnet-5-5 or local DeepSeek R1
├── EXTREME/anomalies: claude-fable-5-2
├── KNOWLEDGE: local Gemma 3 4B
└── Triage: local Qwen3 4B
```
The backend warms all four local models at startup with a five-minute keep-alive;
Ollama can evict models earlier when memory is needed.

---

## Summary

**OpenTron's Hybrid Orchestration** is a production-grade multi-model system that:

1. **Detects domain by intent, not just keywords** - DomainDetector separates
   conceptual/explanatory questions from real specialist work, so naming a
   technology doesn't misroute a pure explanation request
2. **Routes by complexity and role** - Fable only for extreme cases; local
   code and knowledge tasks use their assigned specialists
3. **Orchestrates per-domain agents** (frontend, backend, QA, DevOps, knowledge)
4. **Executes concurrently** via Java 21 Virtual Threads (non-blocking I/O)
5. **Optimizes costs** by reserving Fable for rare, extreme requests
6. **Maintains state durably** in PostgreSQL with full audit trails
7. **Scales efficiently** on modest hardware
8. **Supports multiple providers** with local Ollama fallback

The system is **fully containerized**, **API-ready**, and **production-deployable**.

### Key Update: Model Routing (Nov 2026)
- **Anthropic tiers:** Haiku 5.5 for SIMPLE, Sonnet 5.5 for COMPLEX, and
  Fable 5.2 only for EXTREME tasks or anomalies.
- **Local roles:** Qwen3 4B for triage, Gemma 3 4B for knowledge, Qwen2.5
  Coder 7B for simple code work, and DeepSeek R1 8B for complex code work.
- **Gemini:** Gemini 4 Carbon, Argon, Neon, and Hydrogen are available.
- **Preload policy:** all four local models are warmed at startup and may be
  evicted by Ollama under memory pressure.
