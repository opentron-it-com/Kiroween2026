import { useMemo, useState } from 'react';
import { Download, Loader2 } from 'lucide-react';
import { extractCodeFiles } from '../../lib/codeArchive';
import { downloadCodeAsZip } from '../../lib/downloadZip';

interface Props {
  content: string;
}

/**
 * Shown under any assistant message that contains fenced code blocks -
 * bundles them into a .zip with the folder structure the agents intended
 * (see codeArchive.ts) and downloads it. Renders nothing when the message
 * has no extractable code.
 */
export function DownloadCodeButton({ content }: Props) {
  const [busy, setBusy] = useState(false);
  const files = useMemo(() => extractCodeFiles(content), [content]);

  if (files.length === 0) return null;

  const handleDownload = async () => {
    setBusy(true);
    try {
      const popup = window.open('', '_blank', 'noopener,noreferrer');
      await downloadCodeAsZip(files, 'tron-code', popup);
      console.log('Downloaded code files: ', files.length);
    } catch (e) {
      console.error('Failed to build code zip', e);
    } finally {
      setBusy(false);
    }
  };

  return (
    <button
      type="button"
      onClick={handleDownload}
      disabled={busy}
      className="flex items-center gap-1.5 px-2.5 py-1 rounded text-xs font-medium cursor-pointer transition-colors"
      style={{
        color: 'var(--color-text-secondary)',
        border: '1px solid var(--color-border)',
        background: 'var(--color-bg-secondary)',
      }}
      title={`Download code file${files.length === 1 ? '' : 's'} as .zip`}
    >
      {busy ? <Loader2 size={12} className="animate-spin" /> : <Download size={12} />}
      {busy ? 'Zipping…' : `Download Code`}
    </button>
    
  );
}
