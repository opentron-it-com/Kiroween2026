import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const makeIdentity = (partial: Partial<Record<string, unknown>> = {}) => ({
  enabled: true,
  anon_id: 'anon-123',
  host: 'https://analytics.example.com',
  key: 'ph_public_XXXXXXXXXXXXXX',
  ...partial,
});

describe('initAnalytics', () => {
  beforeEach(() => {
    vi.resetModules();
    vi.stubGlobal('fetch', vi.fn());
    vi.stubGlobal('window', { __TAURI_INTERNALS__: undefined } as any);
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('disables analytics when the backend returns the placeholder host/key', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => makeIdentity(),
    });
    vi.stubGlobal('fetch', fetchMock);

    const posthogInit = vi.fn();
    vi.doMock('posthog-js', () => ({
      default: {
        init: posthogInit,
        identify: vi.fn(),
        register: vi.fn(),
        capture: vi.fn(),
      },
    }));

    const { initAnalytics, isAnalyticsEnabled } = await import('./analytics');
    await initAnalytics();

    expect(posthogInit).not.toHaveBeenCalled();
    expect(isAnalyticsEnabled()).toBe(false);
  });

  it('allows analytics when a real host and key are configured', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => makeIdentity({
        host: 'https://us.i.posthog.com',
        key: 'phc_real_key_123',
      }),
    });
    vi.stubGlobal('fetch', fetchMock);

    const posthogInit = vi.fn();
    vi.doMock('posthog-js', () => ({
      default: {
        init: posthogInit,
        identify: vi.fn(),
        register: vi.fn(),
        capture: vi.fn(),
      },
    }));

    const { initAnalytics, isAnalyticsEnabled } = await import('./analytics');
    await initAnalytics();

    expect(posthogInit).toHaveBeenCalledTimes(1);
    expect(isAnalyticsEnabled()).toBe(true);
  });
});
