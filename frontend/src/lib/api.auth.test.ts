import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// Regression for #266: the frontend must send the local API key as a Bearer
// token on /v1 + /api requests, or `Tron serve` with a key configured 401s
// every data-plane call. These tests cover the pure helpers (getApiKey,
// authHeaders) that source the key and build the header.

const SETTINGS_KEY = 'OpenTron-settings';

// Minimal in-memory localStorage stub so the helpers can run under node
// (no jsdom dependency).
class MemoryStorage {
  private store = new Map<string, string>();
  getItem(k: string): string | null {
    return this.store.has(k) ? (this.store.get(k) as string) : null;
  }
  setItem(k: string, v: string): void {
    this.store.set(k, String(v));
  }
  removeItem(k: string): void {
    this.store.delete(k);
  }
  clear(): void {
    this.store.clear();
  }
}

beforeEach(() => {
  (globalThis as unknown as { localStorage: MemoryStorage }).localStorage =
    new MemoryStorage();
});

afterEach(() => {
  (globalThis as unknown as { localStorage?: MemoryStorage }).localStorage =
    undefined;
  vi.unstubAllGlobals();
});

function stubWebCrypto() {
  const encrypted = new Uint8Array([9, 8, 7]).buffer;
  vi.stubGlobal('crypto', {
    getRandomValues: vi.fn((value: Uint8Array) => {
      value.fill(5);
      return value;
    }),
    subtle: {
      importKey: vi.fn().mockResolvedValue({}),
      generateKey: vi.fn().mockResolvedValue({}),
      exportKey: vi.fn().mockResolvedValue(new Uint8Array([1, 2, 3]).buffer),
      encrypt: vi.fn().mockResolvedValue(encrypted),
    },
  });
}

async function freshApi() {
  // Re-import to pick up the current localStorage stub.
  return await import('./api');
}

describe('getApiKey', () => {
  it('returns empty string when no key is configured', async () => {
    const { getApiKey } = await freshApi();
    expect(getApiKey()).toBe('');
  });

  it('reads apiKey from the OpenTron-settings localStorage blob', async () => {
    localStorage.setItem(
      SETTINGS_KEY,
      JSON.stringify({ apiUrl: 'http://x', apiKey: 'sk-local-123' }),
    );
    const { getApiKey } = await freshApi();
    expect(getApiKey()).toBe('sk-local-123');
  });

  it('returns empty string when the blob has no apiKey field', async () => {
    localStorage.setItem(SETTINGS_KEY, JSON.stringify({ apiUrl: 'http://x' }));
    const { getApiKey } = await freshApi();
    expect(getApiKey()).toBe('');
  });
});

describe('authHeaders', () => {
  it('omits Authorization when no key is set (keyless default unchanged)', async () => {
    const { authHeaders } = await freshApi();
    expect(authHeaders().has('Authorization')).toBe(false);
  });

  it('adds a Bearer Authorization header when a key is set', async () => {
    localStorage.setItem(SETTINGS_KEY, JSON.stringify({ apiKey: 'sk-local-123' }));
    const { authHeaders } = await freshApi();
    expect(authHeaders().get('Authorization')).toBe('Bearer sk-local-123');
  });

  it('merges extra headers alongside Authorization', async () => {
    localStorage.setItem(SETTINGS_KEY, JSON.stringify({ apiKey: 'sk-local-123' }));
    const { authHeaders } = await freshApi();
    const headers = authHeaders({ 'Content-Type': 'application/json' });
    expect(headers.get('Content-Type')).toBe('application/json');
    expect(headers.get('Authorization')).toBe('Bearer sk-local-123');
  });
});

describe('apiFetch', () => {
  it('encrypts coordinator bodies and sensitive headers for local execution', async () => {
    localStorage.setItem('OpenTron-anthropic-key', 'sk-ant-test');
    localStorage.setItem('OpenTron-openai-key', 'sk-openai-test');
    localStorage.setItem(SETTINGS_KEY, JSON.stringify({ apiKey: 'sk-local-test' }));
    stubWebCrypto();

    const fetchMock = vi.fn(async (input: RequestInfo | URL, _init?: RequestInit) => {
      const url = String(input);
      if (url.endsWith('/v1/confidential/execution-mode')) {
        return new Response(JSON.stringify({ mode: 'local-envelope' }), { status: 200 });
      }
      if (url.endsWith('/v1/confidential/local-envelope-key')) {
        return new Response(JSON.stringify({ keyId: 'test-key', publicKey: 'AQID' }), { status: 200 });
      }
      return new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } });
    });
    vi.stubGlobal('fetch', fetchMock);

    const { apiFetch } = await freshApi();
    await apiFetch('/v1/agents/coordinate', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ request: 'private prompt' }),
    });

    expect(fetchMock).toHaveBeenCalledTimes(3);
    const [url, init] = fetchMock.mock.calls[2];
    expect(String(url)).toBe('/v1/agents/coordinate');
    const headers = new Headers(init?.headers);
    expect(headers.get('X-Confidential-Envelope')).toBe('v1');
    expect(headers.has('Authorization')).toBe(false);
    expect(headers.has('X-API-Keys')).toBe(false);
    expect(String(init?.body)).not.toContain('private prompt');
    expect(String(init?.body)).not.toContain('sk-ant-test');
    expect(String(init?.body)).not.toContain('sk-openai-test');
    expect(String(init?.body)).not.toContain('sk-local-test');
  });

  it('leaves non-confidential API reads unchanged', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response('{}', { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);

    const { apiFetch } = await freshApi();
    await apiFetch('/v1/models');

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock).toHaveBeenCalledWith('/v1/models', expect.any(Object));
  });

  it('does not transmit confidential query parameters in the request URL', async () => {
    stubWebCrypto();
    const fetchMock = vi.fn(async (input: RequestInfo | URL, _init?: RequestInit) => {
      const url = String(input);
      if (url.endsWith('/v1/confidential/execution-mode')) {
        return new Response(JSON.stringify({ mode: 'local-envelope' }), { status: 200 });
      }
      if (url.endsWith('/v1/confidential/local-envelope-key')) {
        return new Response(JSON.stringify({ keyId: 'test-key', publicKey: 'AQID' }), { status: 200 });
      }
      return new Response('{}', { status: 200 });
    });
    vi.stubGlobal('fetch', fetchMock);

    const { apiFetch } = await freshApi();
    await apiFetch('/v1/orchestrate/task?task=private%20task&context=private%20context', {
      method: 'POST',
    });

    const [url, init] = fetchMock.mock.calls[2];
    expect(String(url)).toBe('/v1/orchestrate/task');
    expect(String(init?.body)).not.toContain('private%20task');
    expect(String(init?.body)).not.toContain('private%20context');
  });

  it('refuses confidential requests when the backend has no execution policy', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ mode: 'unavailable' }), { status: 200 }),
    );
    vi.stubGlobal('fetch', fetchMock);

    const { apiFetch } = await freshApi();
    await expect(apiFetch('/v1/agents/coordinate', { method: 'POST' }))
      .rejects.toThrow('exactly one execution policy');

    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});

