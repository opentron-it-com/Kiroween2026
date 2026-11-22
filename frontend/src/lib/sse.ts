import type { ResearchEvent, SSEEvent } from '../types';
import { getBase, authHeaders } from './api';

export interface ChatRequest {
  model: string;
  messages: Array<{ role: string; content: string }>;
  stream?: boolean;
  temperature?: number;
  max_tokens?: number;
}

/**
 * Collect cloud provider API keys from localStorage
 */
const CLOUD_KEY_STORAGE_MAP: Array<[string, string]> = [
  ['OpenTron-openai-key', 'openai'],
  ['OpenTron-anthropic-key', 'anthropic'],
  ['OpenTron-gemini-key', 'google'],
  ['OpenTron-openrouter-key', 'openrouter'],
];

function getCloudApiKeys(): Record<string, string> {
  const keys: Record<string, string> = {};

  try {
    for (const [storageKey, provider] of CLOUD_KEY_STORAGE_MAP) {
      const value = localStorage.getItem(storageKey);
      if (value) {
        keys[provider] = value;
      } else if (localStorage.getItem(`${storageKey}:cleared`) === '1') {
        // The user explicitly removed this key in Settings - tell the backend
        // so it doesn't silently fall back to a server-side environment variable.
        keys[provider] = '';
      }
    }
  } catch (e) {
    console.error('[getCloudApiKeys] Error reading API keys from localStorage:', e);
  }

  return keys;
}

/**
 * Simple non-streaming chat completion.
 * Returns the full response at once (no SSE parsing).
 */
export async function chatCompletionSimple(
  request: ChatRequest,
  signal?: AbortSignal,
): Promise<any> {
  const base = getBase();
  const apiKeys = getCloudApiKeys();
  const headers = authHeaders({ 'Content-Type': 'application/json' });
  
  if (Object.keys(apiKeys).length > 0) {
    headers.set('X-API-Keys', JSON.stringify(apiKeys));
  }

  const response = await fetch(`${base}/v1/chat/completions`, {
    method: 'POST',
    headers,
    body: JSON.stringify({ ...request, stream: false }),
    signal,
  });

  if (!response.ok) {
    const errText = await response.text();
    throw new Error(`Chat request failed: ${response.status} - ${errText}`);
  }

  return response.json();
}

export async function* streamChat(
  request: ChatRequest,
  signal?: AbortSignal,
): AsyncGenerator<SSEEvent> {
  const base = getBase();
  const apiKeys = getCloudApiKeys();
  const headers = authHeaders({ 'Content-Type': 'application/json' });
  
  if (Object.keys(apiKeys).length > 0) {
    headers.set('X-API-Keys', JSON.stringify(apiKeys));
  }
  
  const response = await fetch(`${base}/v1/chat/completions`, {
    method: 'POST',
    headers,
    body: JSON.stringify(request),
    signal,
  });

  if (!response.ok) {
    const errText = await response.text();
    throw new Error(`Chat request failed: ${response.status} - ${errText}`);
  }

  if (!response.body) {
    throw new Error('Response has no body');
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) {
        break;
      }

      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split('\n');
      buffer = lines.pop() || '';

      let currentEvent: string | undefined;

      for (const line of lines) {
        if (line.startsWith('event: ')) {
          currentEvent = line.slice(7).trim();
        } else if (line.startsWith('data: ')) {
          const data = line.slice(6);
          if (data === '[DONE]') {
            return;
          }
          yield { event: currentEvent, data };
          currentEvent = undefined;
        } else if (line.trim() === '') {
          currentEvent = undefined;
        }
      }
    }
  } finally {
    reader.releaseLock();
  }
}

export async function* streamResearch(
  query: string,
  signal?: AbortSignal,
): AsyncGenerator<ResearchEvent> {
  // /api/research is mounted at the server root — strip any trailing /v1
  // from the base so configurations like "http://host:8000/v1" still resolve.
  const base = getBase().replace(/\/v1\/?$/, '');
  const response = await fetch(`${base}/api/research`, {
    method: 'POST',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ query }),
    signal,
  });

  if (!response.ok) {
    throw new Error(`Research request failed: ${response.status}`);
  }

  const reader = response.body!.getReader();
  const decoder = new TextDecoder();
  let buffer = '';

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;

      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split('\n');
      buffer = lines.pop() || '';

      for (const line of lines) {
        if (!line.startsWith('data: ')) continue;
        const data = line.slice(6);
        if (data === '[DONE]') return;
        try {
          const parsed = JSON.parse(data) as ResearchEvent;
          yield parsed;
          if (parsed.type === 'done') return;
        } catch {
          // skip malformed chunks
        }
      }
    }
  } finally {
    reader.releaseLock();
  }
}

export async function* streamPullEvents(
  jobId: string,
  signal?: AbortSignal,
): AsyncGenerator<{ data: string } | { error: string }> {
  const base = getBase();
  const response = await fetch(`${base}/v1/models/pull/${encodeURIComponent(jobId)}/events`, {
    method: 'GET',
    headers: authHeaders(),
    signal,
  });

  if (!response.ok) {
    throw new Error(`Pull events request failed: ${response.status}`);
  }

  const reader = response.body!.getReader();
  const decoder = new TextDecoder();
  let buffer = '';

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;

      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split('\n');
      buffer = lines.pop() || '';

      for (const line of lines) {
        if (!line.startsWith('data: ')) continue;
        const data = line.slice(6);
        if (data === '[DONE]') return;
        try {
          const parsed = JSON.parse(data);
          yield { data: parsed } as any;
        } catch {
          yield { data } as any;
        }
      }
    }
  } finally {
    reader.releaseLock();
  }
}
