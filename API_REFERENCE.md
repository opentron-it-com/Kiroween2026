# OpenTron REST API reference

This document summarizes the primary REST API exposed under `/v1` by the backend.

## Base path

- `/v1`

## Authentication

- API keys are accepted via an `Authorization: Bearer ...` header for configured server-side keys.
- Cloud provider keys can also be supplied by the frontend in the `X-API-Keys` header as a JSON object, for example:

```json
{
  "openai": "...",
  "anthropic": "...",
  "google": "...",
  "openrouter": "..."
}
```

## Chat and model endpoints

### POST `/v1/chat/completions`
Primary model generation endpoint.

Request shape:

```json
{
  "model": "llama3.2",
  "stream": false,
  "messages": [
    { "role": "user", "content": "Hello" }
  ],
  "params": {
    "temperature": 0.7,
    "max_tokens": 4096
  }
}
```

Behavior:
- detects cloud models and routes them separately
- detects local Ollama or OpenAI-compatible provider settings
- supports streaming responses
- records token usage and cost when available

### GET `/v1/models`
Returns a normalized model list for the UI.

### POST `/v1/models/pull`
Starts an async model pull job.

Request:

```json
{
  "name": "llama3.2"
}
```

Response:

```json
{
  "job_id": "...",
  "status": "pending"
}
```

### GET `/v1/models/pull/{jobId}`
Returns job status and metadata.

### GET `/v1/models/pull/{jobId}/events`
SSE end-point for pull progress updates.

## Agent endpoints

### POST `/v1/agents/coordinate`
Runs a multi-agent coordination flow and streams events via SSE.

Request:

```json
{
  "request": "Analyze this task and break it into agent steps",
  "context": "Optional previous context"
}
```

Stream events may include:
- `status`
- `agent_start`
- `agent_chunk`
- `agent_done`
- `agent_error`
- `done`

### GET `/v1/agents/status`
Returns each agent’s current status and metadata.

### POST `/v1/agents/task`
Submits a task to a specific agent asynchronously.

Request:

```json
{
  "agent": "research-agent",
  "task": "Find and summarize the latest relevant information"
}
```

Response includes a `task_id` and a poll URL.

### GET `/v1/agents/task/{taskId}`
Polls for the eventual result of the queued task.

## Memory endpoints

### GET `/v1/memory/stats`
Returns aggregate memory and trace counts.

### GET `/v1/memory/config`
Returns memory configuration values.

### POST `/v1/memory/store`
Stores a new memory record.

Request:

```json
{
  "agent_name": "assistant",
  "content": "User asked about project planning",
  "summary": "Discussed project planning"
}
```

### POST `/v1/memory/search`
Searches for memory entries by query.

Request:

```json
{
  "query": "project planning",
  "agent_name": "assistant",
  "top_k": 5
}
```

### POST `/v1/memory/index`
Indexing request for files or folders.

### GET `/v1/memory/agent/{agentId}`
Returns memory records for a given agent.

## Settings and policy endpoints

### GET `/v1/settings/network`
Returns whether internet access is allowed.

### POST `/v1/settings/network`
Sets the network policy.

```json
{
  "allow_internet": false
}
```

### GET `/v1/settings/search-key`
Returns whether a search key is configured and masks the value.

### POST `/v1/settings/search-key`
Saves a search key for a provider.

```json
{
  "provider": "serpapi",
  "key": "secret-key"
}
```

### POST `/v1/settings/search/query`
Performs a web search through the configured search service.

## Workflow and orchestration endpoints

These endpoints are intended for bigger system-level task execution and agent coordination patterns.

### POST `/v1/orchestrate/task`
Starts an orchestration task.

### POST `/v1/orchestrate/batch`
Starts a batch orchestration job.

### GET `/v1/orchestrate/agents`
Returns orchestrator agent metadata and routing information.

### GET `/v1/orchestrate/domains`
Returns discovered domain categories or routing map metadata.

## Other API groups

- `/v1/connectors` — connector management and third-party service integrations
- `/v1/tools` — tool execution and tool metadata
- `/v1/workflow` — workflow runner endpoints
- `/v1/learning` — learning and spec-search related actions
- `/v1/traces` — execution trace retrieval and diagnostics
- `/v1/telemetry` — metrics and usage reporting
- `/v1/speech` — voice-related endpoints
- `/v1/jarvis` — Jarvis-style voice or assistant endpoints
- `/v1/screenshot` — screenshot analysis related endpoints

## Notes

- Many endpoints intentionally mirror OpenAI-style request formats for compatibility with standard tooling.
- The project also supports SSE streams for long-running operations such as model pulls, agent coordination, and task updates.
- Request routing is partially engine-aware, meaning the backend can adapt to local Ollama deployments or cloud/OpenAI-compatible providers dynamically.

## Suggested client usage pattern

1. Fetch `/v1/models` to populate model choices.
2. Set provider keys if the user is using cloud models.
3. Call `/v1/chat/completions` with the selected model.
4. For multi-agent runs, use `/v1/agents/coordinate` and consume SSE updates.
5. Persist and retrieve memory through `/v1/memory/*` endpoints.
6. Monitor token usage and runtime telemetry via `/v1/telemetry` and related metrics endpoints.
