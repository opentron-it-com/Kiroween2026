import { useEffect, useRef, useState } from 'react';
import { useLocation } from 'react-router-dom';
import {
  Archive,
  ArrowUpRight,
  Check,
  Clock3,
  FileText,
  LoaderCircle,
  Ghost,
  Sparkles,
  Upload,
} from 'lucide-react';
import { ChatArea } from '../../components/Chat/ChatArea';
import { useAppStore } from '../../lib/store';
import {
  fetchArchiveJobStatus,
  getArchiveDisplayStatus,
  isSupportedArchiveFile,
  searchArchiveEvidence,
  uploadArchiveFiles,
  type ArchiveDisplayStatus,
  type ArchiveEvidence,
} from './archive';
import './ghostbook.css';

const GHOSTBOOK_CONVERSATION_KEY = 'OpenTron-ghostbook-conversation';
const SAMPLE_ARCHIVES = [
  { format: 'CSV', archive: 'ghostbook-sample-archive.csv', questions: 'ghostbook-sample-archive-csv-questions.txt' },
  { format: 'PDF', archive: 'ghostbook-sample-archive.pdf', questions: 'ghostbook-sample-archive-pdf-questions.txt' },
  { format: 'DOCX', archive: 'ghostbook-sample-archive.docx', questions: 'ghostbook-sample-archive-docx-questions.txt' },
  { format: 'Markdown', archive: 'ghostbook-sample-archive.md', questions: 'ghostbook-sample-archive-md-questions.txt' },
  { format: 'Text', archive: 'ghostbook-sample-archive.txt', questions: 'ghostbook-sample-archive-txt-questions.txt' },
] as const;

interface ImportedArchive {
  id: string;
  name: string;
  status: ArchiveDisplayStatus;
}

function waitForPoll(signal: AbortSignal): Promise<boolean> {
  return new Promise((resolve) => {
    if (signal.aborted) {
      resolve(false);
      return;
    }

    const timer = window.setTimeout(() => {
      signal.removeEventListener('abort', handleAbort);
      resolve(true);
    }, 1200);
    const handleAbort = () => {
      window.clearTimeout(timer);
      resolve(false);
    };
    signal.addEventListener('abort', handleAbort, { once: true });
  });
}

function formatFileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

export function GhostbookPage() {
  const createConversation = useAppStore((state) => state.createConversation);
  const selectConversation = useAppStore((state) => state.selectConversation);
  const messages = useAppStore((state) => state.messages);
  const isStreaming = useAppStore((state) => state.streamState.isStreaming);
  const location = useLocation();
  const [selectedFiles, setSelectedFiles] = useState<File[]>([]);
  const [archives, setArchives] = useState<ImportedArchive[]>([]);
  const [evidence, setEvidence] = useState<ArchiveEvidence[]>([]);
  const [evidenceQuery, setEvidenceQuery] = useState('');
  const [evidenceLoading, setEvidenceLoading] = useState(false);
  const [evidenceError, setEvidenceError] = useState('');
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const fileInputRef = useRef<HTMLInputElement>(null);
  const jobPollers = useRef(new Map<string, AbortController>());
  const lastEvidenceExchange = useRef('');
  const latestMessage = messages[messages.length - 1];
  let latestUserMessage: (typeof messages)[number] | undefined;
  let latestAssistantMessage: (typeof messages)[number] | undefined;
  for (let index = messages.length - 1; index >= 0; index -= 1) {
    const message = messages[index];
    if (!latestAssistantMessage && message.role === 'assistant') {
      latestAssistantMessage = message;
    }
    if (message.role === 'user') {
      latestUserMessage = message;
      break;
    }
  }

  useEffect(() => () => {
    jobPollers.current.forEach((controller) => controller.abort());
    jobPollers.current.clear();
  }, []);

  useEffect(() => {
    if (latestMessage?.role === 'user') {
      setEvidenceQuery('');
      setEvidence([]);
      setEvidenceError('');
      setEvidenceLoading(false);
      return;
    }

    if (
      isStreaming ||
      latestAssistantMessage?.role !== 'assistant' ||
      latestUserMessage?.role !== 'user' ||
      !latestUserMessage.content.trim()
    ) {
      return;
    }

    const exchangeId = `${latestUserMessage.id}:${latestAssistantMessage.id}`;
    if (lastEvidenceExchange.current === exchangeId) return;
    lastEvidenceExchange.current = exchangeId;

    const controller = new AbortController();
    const query = latestUserMessage.content.trim();
    setEvidenceQuery(query);
    setEvidence([]);
    setEvidenceError('');
    setEvidenceLoading(true);
    let settled = false;

    void searchArchiveEvidence(query, controller.signal)
      .then(setEvidence)
      .catch((searchError: unknown) => {
        if (!controller.signal.aborted) {
          setEvidenceError(
            searchError instanceof Error
              ? searchError.message
              : 'Could not search the imported archive.',
          );
        }
      })
      .finally(() => {
        settled = true;
        if (!controller.signal.aborted) setEvidenceLoading(false);
      });

    return () => {
      controller.abort();
      if (!settled && lastEvidenceExchange.current === exchangeId) {
        lastEvidenceExchange.current = '';
      }
    };
  }, [
    isStreaming,
    latestAssistantMessage?.id,
    latestAssistantMessage?.role,
    latestMessage?.role,
    latestUserMessage?.content,
    latestUserMessage?.id,
    latestUserMessage?.role,
  ]);

  useEffect(() => {
    const state = useAppStore.getState();
    let savedId: string | null = null;
    try {
      savedId = localStorage.getItem(GHOSTBOOK_CONVERSATION_KEY);
    } catch {
      // The conversation can still be used for this visit if storage is unavailable.
    }

    const conversation =
      state.conversations.find((item) => item.id === savedId) ??
      state.conversations.find((item) => item.title === 'Ghostbook Archive');

    if (conversation) {
      if (state.activeId !== conversation.id) selectConversation(conversation.id);
      return;
    }

    const id = createConversation(undefined, 'Ghostbook Archive');
    try {
      localStorage.setItem(GHOSTBOOK_CONVERSATION_KEY, id);
    } catch {
      // Keep the new conversation active for this visit.
    }
  }, [createConversation, selectConversation, location.pathname]);

  const handleFileSelection = (files: File[]) => {
    setError('');
    setNotice('');

    const unsupported = files.filter((file) => !isSupportedArchiveFile(file.name));
    if (unsupported.length > 0) {
      setSelectedFiles([]);
      setError(
        `Unsupported file type${unsupported.length === 1 ? '' : 's'}: ${unsupported
          .map((file) => file.name)
          .join(', ')}. Choose PDF, DOCX, CSV, Markdown, or text files.`,
      );
      if (fileInputRef.current) fileInputRef.current.value = '';
      return;
    }

    setSelectedFiles(files);
  };

  const monitorArchiveJob = async (jobId: string, archiveIds: string[]) => {
    const controller = new AbortController();
    jobPollers.current.set(jobId, controller);

    try {
      for (let attempt = 0; attempt < 60; attempt += 1) {
        if (!await waitForPoll(controller.signal)) return;
        const job = await fetchArchiveJobStatus(jobId, controller.signal);
        const status = getArchiveDisplayStatus(job);
        setArchives((current) =>
          current.map((archive) =>
            archiveIds.includes(archive.id) ? { ...archive, status } : archive,
          ),
        );

        if (job.status === 'completed') {
          if (job.itemsFailed > 0) {
            setNotice('');
            setError(
              `${job.itemsProcessed} file${job.itemsProcessed === 1 ? '' : 's'} indexed; ${job.itemsFailed} file${job.itemsFailed === 1 ? '' : 's'} could not be read. ${job.message || ''}`.trim(),
            );
          } else {
            setError('');
            setNotice(
              `${job.itemsProcessed} file${job.itemsProcessed === 1 ? '' : 's'} indexed into ${job.chunksCreated} searchable archive chunks.`,
            );
          }
          return;
        }

        if (job.status === 'failed' || job.status === 'cancelled') {
          setNotice('');
          setError(job.message || `Archive indexing ${job.status}.`);
          return;
        }
      }

      setError(
        'Indexing status could not be confirmed after 72 seconds. It may still be running; avoid re-uploading the same files until you check the archive again.',
      );
    } catch (pollError) {
      if (!controller.signal.aborted) {
        setError(
          pollError instanceof Error
            ? pollError.message
            : 'Could not check archive indexing status.',
        );
      }
    } finally {
      if (jobPollers.current.get(jobId) === controller) {
        jobPollers.current.delete(jobId);
      }
    }
  };

  const handleImport = async () => {
    if (selectedFiles.length === 0 || uploading) return;
    setUploading(true);
    setError('');
    setNotice('');

    try {
      const result = await uploadArchiveFiles(selectedFiles);
      const importedAt = Date.now();
      const acceptedFiles = selectedFiles.slice(0, result.filesReceived);
      const archiveIds = acceptedFiles.map((file, index) => `${importedAt}-${index}-${file.name}`);
      setArchives((current) => [
        ...acceptedFiles.map((file, index) => ({
          id: archiveIds[index],
          name: file.name,
          status: 'queued' as const,
        })),
        ...current,
      ]);
      setNotice(
        `${result.filesReceived} file${result.filesReceived === 1 ? '' : 's'} queued for indexing. It may take a moment before they appear in archive answers.`,
      );
      setSelectedFiles([]);
      if (fileInputRef.current) fileInputRef.current.value = '';
      void monitorArchiveJob(result.jobId, archiveIds);
    } catch (importError) {
      setError(
        importError instanceof Error
          ? importError.message
          : 'Archive import failed. Check the backend connection and try again.',
      );
    } finally {
      setUploading(false);
    }
  };

  return (
    <div className="ghostbook-page">
      <header className="ghostbook-header">
        <div className="ghostbook-brand">
          <div className="ghostbook-mark" aria-hidden="true">
            <Ghost size={21} />
          </div>
          <div>
            <div className="ghostbook-eyebrow">PERSONAL ARCHIVE TERMINAL</div>
            <h1>Ghostbook</h1>
          </div>
        </div>
        <div className="ghostbook-status">ARCHIVE WORKSPACE</div>
      </header>

      <div className="ghostbook-workspace">
        <section className="ghostbook-archive-panel" aria-labelledby="archive-title">
          <div className="ghostbook-section-heading">
            <div>
              <div className="ghostbook-eyebrow">THE MEMORY VAULT</div>
              <h2 id="archive-title">Bring the past back</h2>
            </div>
            <Archive size={20} aria-hidden="true" />
          </div>
          <p className="ghostbook-intro">
            Import exported notes and records. Ghostbook sends them to your configured OpenTron
            backend for indexing.
          </p>

          <label className="ghostbook-dropzone">
            <input
              ref={fileInputRef}
              type="file"
              multiple
              accept=".csv,.docx,.md,.pdf,.txt"
              disabled={uploading}
              onChange={(event) => handleFileSelection(Array.from(event.target.files ?? []))}
              aria-label="Choose archive files"
              aria-describedby="ghostbook-file-types"
            />
            <span className="ghostbook-upload-icon">
              <Upload size={19} aria-hidden="true" />
            </span>
            <span className="ghostbook-upload-title">Select files to import</span>
            <span id="ghostbook-file-types" className="ghostbook-upload-hint">
              PDF · DOCX · CSV · MD · TXT
            </span>
          </label>

          {selectedFiles.length > 0 && (
            <div className="ghostbook-selection" aria-live="polite">
              {selectedFiles.map((file) => (
                <div className="ghostbook-file-row" key={`${file.name}-${file.size}`}>
                  <FileText size={15} aria-hidden="true" />
                  <span className="ghostbook-file-name">{file.name}</span>
                  <span className="ghostbook-file-size">{formatFileSize(file.size)}</span>
                </div>
              ))}
              <button
                type="button"
                className="ghostbook-import-button"
                onClick={() => void handleImport()}
                disabled={uploading}
                aria-busy={uploading}
              >
                {uploading ? (
                  <>
                    <LoaderCircle size={16} className="ghostbook-spin" aria-hidden="true" />
                    Sending to archive…
                  </>
                ) : (
                  <>
                    <Upload size={16} aria-hidden="true" />
                    Import {selectedFiles.length} file{selectedFiles.length === 1 ? '' : 's'}
                  </>
                )}
              </button>
            </div>
          )}

          {error && (
            <div className="ghostbook-feedback ghostbook-error" role="alert">
              {error}
            </div>
          )}
          {notice && (
            <div className="ghostbook-feedback ghostbook-success" role="status" aria-live="polite">
              <Check size={15} aria-hidden="true" />
              <span>{notice}</span>
            </div>
          )}

          <div className="ghostbook-privacy-note">
            <Sparkles size={15} aria-hidden="true" />
            <span>
              Your selected model route may be local or cloud-based. Review your OpenTron settings
              before importing sensitive files.
            </span>
          </div>

          <section className="ghostbook-archive-list" aria-labelledby="ghostbook-archive-list-title">
            <h3 id="ghostbook-archive-list-title" className="ghostbook-list-heading">
              <span>RECENTLY SUMMONED</span>
              <span aria-label={`${archives.length} imported ${archives.length === 1 ? 'file' : 'files'}`}>
                {archives.length.toString().padStart(2, '0')}
              </span>
            </h3>
            {archives.length === 0 ? (
              <div className="ghostbook-empty-archive">
                <Clock3 size={17} aria-hidden="true" />
                <span>Your archive is quiet. Imported files will appear here.</span>
              </div>
            ) : (
              archives.map((archive) => (
                <div
                  className="ghostbook-file-row ghostbook-imported-row"
                  key={archive.id}
                  role="status"
                  aria-live="polite"
                  aria-atomic="true"
                >
                  <FileText size={15} aria-hidden="true" />
                  <span className="ghostbook-file-name">{archive.name}</span>
                  <span className={`ghostbook-file-size ghostbook-job-${archive.status}`}>
                    {archive.status === 'completed'
                      ? 'INDEXED'
                      : archive.status === 'partial'
                        ? 'BATCH PARTIAL'
                        : archive.status.toUpperCase()}
                  </span>
                </div>
              ))
            )}
          </section>

          <section className="ghostbook-sample-downloads" aria-labelledby="ghostbook-samples-title">
            <h3 id="ghostbook-samples-title" className="ghostbook-eyebrow">SYNTHETIC SAMPLE ARCHIVES</h3>
            <p>Each archive has a matching question sheet.</p>
            {SAMPLE_ARCHIVES.map((sample) => (
              <div className="ghostbook-sample-row" key={sample.format}>
                <a
                  className="ghostbook-sample-link"
                  href={`${import.meta.env.BASE_URL}${sample.archive}`}
                  download
                  aria-label={`Download synthetic ${sample.format} archive sample`}
                >
                  {sample.format} archive
                  <ArrowUpRight size={14} aria-hidden="true" />
                </a>
                <a
                  className="ghostbook-sample-questions-link"
                  href={`${import.meta.env.BASE_URL}${sample.questions}`}
                  download
                  aria-label={`Download ${sample.format} archive questions`}
                >
                  Questions (.txt)
                </a>
              </div>
            ))}
          </section>
        </section>

        <section className="ghostbook-chat-panel" aria-label="Ask your archive">
          <div className="ghostbook-chat-heading">
            <div>
              <div className="ghostbook-eyebrow">ASK THE ARCHIVE</div>
              <h2>What did the past leave behind?</h2>
            </div>
            <span className="ghostbook-chat-indicator">
              <span className="ghostbook-status-dot" />
              ARCHIVE QUERY
            </span>
          </div>
          <div className="ghostbook-chat-content">
            <div className="ghostbook-chat-area">
              <ChatArea
                emptyStateTitle="Ask your archive"
                emptyStateMessage="Import a file, then ask a question. OpenTron answers using your configured model and looks up matching archive passages for inspection."
                messageInputLabel="Ask a question about your archive"
                showDataSourcesBanner={false}
                showSystemPanelToggle={false}
                showAvatar={false}
                showEmptyIcon={false}
                forceTronMode
              />
            </div>
            {evidenceQuery && (
              <section className="ghostbook-evidence" aria-labelledby="ghostbook-evidence-title">
                <div className="ghostbook-evidence-heading">
                  <div>
                    <div className="ghostbook-eyebrow">RETRIEVED FROM YOUR ARCHIVE</div>
                    <h3 id="ghostbook-evidence-title">Evidence matches</h3>
                  </div>
                </div>
                <p className="ghostbook-evidence-note">
                  These passages are archive search matches for “{evidenceQuery}”; they are not a
                  claim that the assistant used each passage in its answer.
                </p>
                {evidenceLoading ? (
                  <div className="ghostbook-evidence-loading" role="status" aria-live="polite">
                    <LoaderCircle size={16} className="ghostbook-spin" aria-hidden="true" />
                    <span>Searching archive matches…</span>
                  </div>
                ) : evidenceError ? (
                  <div className="ghostbook-feedback ghostbook-error" role="alert">
                    {evidenceError}
                  </div>
                ) : evidence.length === 0 ? (
                  <p className="ghostbook-evidence-empty" role="status">
                    No matching imported passages were found.
                  </p>
                ) : (
                  <ul className="ghostbook-evidence-list" aria-live="polite">
                    {evidence.map((item, index) => (
                      <li
                        className="ghostbook-evidence-item"
                        key={`${item.fileName}-${item.chunkIndex ?? index}`}
                      >
                        <div className="ghostbook-evidence-source">
                          <FileText size={14} aria-hidden="true" />
                          <span>{item.fileName}</span>
                          {item.chunkIndex !== null && <span>CHUNK {item.chunkIndex + 1}</span>}
                        </div>
                        <p>{item.excerpt}</p>
                      </li>
                    ))}
                  </ul>
                )}
              </section>
            )}
          </div>
        </section>
      </div>
    </div>
  );
}
