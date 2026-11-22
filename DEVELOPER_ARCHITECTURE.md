# Developer architecture diagram

## System overview

```mermaid
flowchart LR
  UI[React Frontend\nVite + Zustand]\n  DESKTOP[Tauri Desktop Shell\nWindows/macOS/Linux]\n  API[Spring Boot Backend\nREST + SSE + WebSocket]\n  ROUTE[Engine Routing\nOllama / OpenAI-compatible / Cloud]\n  LLM[Local/Remote LLM Providers]\n  DB[(PostgreSQL / Storage)]
  MEM[Memory + Trace + Logs]
  AGENTS[Multi-Agent Coordinator]\n  CONNECTORS[External Connectors\nEmail / Calendar / Docs / Search / Tools]
  OBS[Telemetry + Pricing + Monitoring]

  UI --> DESKTOP
  DESKTOP --> API
  UI -->|HTTP / SSE / WebSocket| API

  API --> ROUTE
  API --> AGENTS
  API --> MEM
  API --> CONNECTORS
  API --> OBS

  ROUTE --> LLM
  AGENTS --> LLM
  AGENTS --> CONNECTORS

  MEM --> DB
  OBS --> DB
  CONNECTORS --> DB

  LLM -->|responses| API
  API -->|streamed UI updates| UI
```

## Runtime responsibilities

### 1. Frontend layer
- user chat and settings UI
- model selection and server config
- dashboards and system monitoring
- live updates from server-sent events

### 2. Desktop shell
- starts and manages the Java backend process
- monitors health and restarts when needed
- exposes local runtime config and environment-specific behavior

### 3. Backend API layer
- serves `/v1` endpoints for chat, models, memory, workflows, and agents
- handles auth, request normalization, and provider routing
- manages streaming responses and asynchronous processing

### 4. Engine routing layer
- detects whether the active model engine is Ollama or OpenAI-compatible
- forwards provider-specific requests correctly
- supports local-first model resolution and cloud fallback logic

### 5. Agent and workflow layer
- coordinates agent tasks and long-running flows
- produces streaming status updates per agent
- captures traces and token usage for orchestration runs

### 6. Persistence and memory layer
- stores conversation traces, tool results, and memory entries
- supports retrieval of agent-specific or user-scoped context
- keeps a durable audit trail for system actions

### 7. Observability layer
- tracks telemetry, token accounting, and cost estimates
- surfaces monitoring and logs to the UI
- supports operational health checks and debugging

## Dependency direction

The practical dependency structure is:

- UI depends on backend API contracts
- Desktop shell controls the backend lifecycle
- Backend depends on routing, storage, telemetry, and orchestration services
- Routing and agents depend on model providers and connected tools
- Storage and telemetry are supporting infrastructure, not business logic

## Design intent

This architecture reflects a local-first personal AI platform rather than a simple single-chat app. The design favors:

- local execution for privacy and cost control
- component isolation for routing and orchestration
- streaming status for long-running tasks
- backend-managed lifecycle for desktop reliability
- extensible memory and connector systems for future integrations
