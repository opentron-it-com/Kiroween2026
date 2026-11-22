import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { downloadCodeAsZip } from './downloadZip';

describe('downloadCodeAsZip', () => {
  beforeEach(() => {
    vi.stubGlobal('URL', {
      createObjectURL: vi.fn(() => 'blob:demo-zip'),
      revokeObjectURL: vi.fn(),
    } as any);

    vi.stubGlobal('window', {
      open: vi.fn(),
      MouseEvent: class MouseEvent {
        defaultPrevented = false;
        constructor(public type: string, public init?: any) {}
        dispatchEvent() {
          return true;
        }
      },
    } as any);

    const body = {
      appendChild: vi.fn(),
      removeChild: vi.fn(),
    };

    vi.stubGlobal('document', {
      body,
      createElement: vi.fn((tagName: string) => {
        if (tagName === 'a') {
          return {
            href: '',
            download: '',
            rel: '',
            style: {},
            parentNode: body,
            click: vi.fn(),
            dispatchEvent: vi.fn(() => true),
          } as any;
        }
        return {} as any;
      }),
    } as any);
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('falls back to dispatching a click when the anchor click is blocked', async () => {
    const clickSpy = vi.fn();
    const dispatchSpy = vi.fn(() => true);

    const anchor = {
      href: '',
      download: '',
      rel: '',
      style: {},
      parentNode: { removeChild: vi.fn() },
      click: clickSpy,
      dispatchEvent: dispatchSpy,
    };

    const doc = globalThis.document as any;
    doc.createElement = vi.fn(() => anchor);

    await downloadCodeAsZip([{ path: 'src/app.ts', content: 'console.log("hi")' }], 'tron-code');

    expect(clickSpy).toHaveBeenCalledTimes(1);
    expect(dispatchSpy).not.toHaveBeenCalled();
  });
});
