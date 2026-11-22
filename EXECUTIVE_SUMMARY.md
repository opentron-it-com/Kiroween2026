# OpenTron executive summary

OpenTron is a local-first desktop AI runtime for running chat, memory, tools, and multi-agent workflows from a single application. It combines a React + Tauri frontend with a Java Spring Boot backend and a model-routing layer that can use local Ollama deployments, OpenAI-compatible endpoints, or cloud providers such as OpenAI, Anthropic, Gemini, and OpenRouter.

The project is designed around a private-by-default operating model: local inference and local storage are primary, while optional cloud connectivity and external integrations are used when needed. The app exposes a structured API surface under `/v1` for chat, model management, agents, memory, settings, telemetry, connectors, and workflows.

At a high level, the platform has three layers:

1. Desktop shell and UX
   - React frontend with Zustand state management
   - Tauri process management for the local backend
   - user-facing dashboards, chat, settings, logs, and agent orchestration

2. Backend runtime
   - Spring Boot service layer
   - REST controllers and SSE endpoints
   - orchestration, memory, telemetry, and workflow logic

3. Model and data integration layer
   - Ollama or OpenAI-compatible engine routing
   - cloud model access via provider keys
   - connector and storage integrations
   - memory and trace persistence

The platform’s main value is not just chat: it is a stateful personal AI environment with long-lived context, tool-aware orchestration, agent task flows, and local-first deployment characteristics. This makes it more similar to a local AI operating system than a standard chatbot wrapper.

## Core architectural themes

- Local-first execution with optional cloud fallback
- OpenAI-compatible LLM interface abstraction
- Multi-agent coordination and streaming task updates
- Memory and trace persistence for continuity
- Tauri-managed desktop runtime and backend lifecycle
- Observability and cost telemetry

## Best starting points

- [README.md](README.md)
- [java/opentron-java/backend/src/main/java/org/opentron/backend/OpentronBackendApplication.java](java/opentron-java/backend/src/main/java/org/opentron/backend/OpentronBackendApplication.java)
- [java/opentron-java/backend/src/main/java/org/opentron/backend/util/EngineRouting.java](java/opentron-java/backend/src/main/java/org/opentron/backend/util/EngineRouting.java)
- [frontend/src/App.tsx](frontend/src/App.tsx)
- [frontend/src-tauri/src/lib.rs](frontend/src-tauri/src/lib.rs)
