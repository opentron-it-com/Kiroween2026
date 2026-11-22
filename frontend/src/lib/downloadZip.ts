import JSZip from 'jszip';
import type { ExtractedCodeFile } from './codeArchive';

/**
 * Bundles extracted code files into a .zip and triggers a download.
 *
 * Some Tauri/browser runtimes ignore the bare anchor-click path after the
 * async ZIP generation completes, so we fall back to `window.open` and an
 * explicit `location.assign` if needed. Errors are surfaced to the console so
 * the app doesn't silently do nothing.
 */
export async function downloadCodeAsZip(
  files: ExtractedCodeFile[],
  zipName = 'tron-code',
  popup?: Window | null,
): Promise<void> {
  if (!files.length) return;
  if (typeof window === 'undefined' || typeof document === 'undefined') {
    return;
  }

  try {
    const zip = new JSZip();
    for (const file of files) {
      zip.file(file.path, file.content);
    }

    const blob = await zip.generateAsync({ type: 'blob' });
    const url = URL.createObjectURL(blob);
    const finalName = 'tron-code.zip';

    if (popup) {
      popup.location.href = url;
      popup.document.title = finalName;
      setTimeout(() => URL.revokeObjectURL(url), 1000);
      return;
    }

    const a = document.createElement('a');
    a.href = url;
    a.download = finalName;
    a.rel = 'noopener noreferrer';
    a.style.display = 'none';

    const body = document.body;
    if (body) body.appendChild(a);

    try {
      a.click();
      setTimeout(() => {
        if (body && a.parentNode) body.removeChild(a);
        URL.revokeObjectURL(url);
      }, 1000);
      return;
    } catch (e) {
      console.error('[downloadZip] direct anchor click failed', e);
    }

    const fallback = window.open('', '_blank', 'noopener,noreferrer');
    if (fallback) {
      fallback.location.href = url;
      fallback.document.title = finalName;
    } else {
      window.location.href = url;
    }

    setTimeout(() => {
      if (body && a.parentNode) body.removeChild(a);
      URL.revokeObjectURL(url);
    }, 1000);
  } catch (error) {
    console.error('[downloadZip] failed to build or trigger zip download', error);
    throw error;
  }
}
