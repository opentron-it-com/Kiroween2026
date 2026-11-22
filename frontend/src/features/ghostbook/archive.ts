import { apiFetch } from '../../lib/api';

const SUPPORTED_EXTENSIONS = ['.csv', '.docx', '.md', '.pdf', '.txt'] as const;

export function isSupportedArchiveFile(fileName: string): boolean {
  const extension = fileName.slice(fileName.lastIndexOf('.')).toLowerCase();
  return SUPPORTED_EXTENSIONS.some((supported) => supported === extension);
}

export interface ArchiveImportResult {
  filesReceived: number;
  jobId: string;
}

export interface ArchiveJobStatus {
  status: 'queued' | 'running' | 'completed' | 'failed' | 'cancelled';
  message: string | null;
  itemsProcessed: number;
  itemsFailed: number;
  chunksCreated: number;
}

export type ArchiveDisplayStatus = 'queued' | 'running' | 'completed' | 'partial' | 'failed';

export function getArchiveDisplayStatus(job: ArchiveJobStatus): ArchiveDisplayStatus {
  if (job.status === 'completed') {
    if (job.itemsFailed === 0) return 'completed';
    return job.itemsProcessed > 0 ? 'partial' : 'failed';
  }
  if (job.status === 'failed' || job.status === 'cancelled') return 'failed';
  return job.status;
}

export interface ArchiveEvidence {
  fileName: string;
  excerpt: string;
  chunkIndex: number | null;
}

function isArchiveJobStatus(status: unknown): status is ArchiveJobStatus['status'] {
  return (
    status === 'queued' ||
    status === 'running' ||
    status === 'completed' ||
    status === 'failed' ||
    status === 'cancelled'
  );
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

async function getResponseMessage(response: Response): Promise<string | null> {
  const body: unknown = await response.json().catch(() => null);
  if (!isRecord(body)) return null;

  for (const key of ['detail', 'message', 'error']) {
    if (typeof body[key] === 'string' && body[key].trim()) {
      return body[key].trim();
    }
  }
  return null;
}

export async function uploadArchiveFiles(files: File[]): Promise<ArchiveImportResult> {
  if (files.length === 0) {
    throw new Error('Choose at least one archive file.');
  }
  const unsupported = files.filter((file) => !isSupportedArchiveFile(file.name));
  if (unsupported.length > 0) {
    throw new Error(`Unsupported file type: ${unsupported.map((file) => file.name).join(', ')}`);
  }

  const formData = new FormData();
  for (const file of files) formData.append('files', file);

  const response = await apiFetch('/v1/connectors/upload/ingest/files', {
    method: 'POST',
    body: formData,
  });

  if (!response.ok) {
    const message = await getResponseMessage(response);
    throw new Error(message || `Archive import failed (HTTP ${response.status}).`);
  }

  const body: unknown = await response.json();
  if (!isRecord(body) || body.status !== 'queued') {
    throw new Error('The backend returned an unexpected archive import response.');
  }

  if (
    typeof body.files_received !== 'number' ||
    !Number.isInteger(body.files_received) ||
    body.files_received !== files.length ||
    typeof body.job_id !== 'string' ||
    !body.job_id.trim()
  ) {
    throw new Error('The backend returned an invalid archive import response.');
  }

  return {
    filesReceived: body.files_received,
    jobId: body.job_id,
  };
}

export async function fetchArchiveJobStatus(
  jobId: string,
  signal?: AbortSignal,
): Promise<ArchiveJobStatus> {
  const response = await apiFetch(
    `/v1/connectors/upload/ingest/jobs/${encodeURIComponent(jobId)}`,
    { signal },
  );
  if (!response.ok) {
    const message = await getResponseMessage(response);
    throw new Error(message || `Could not check archive indexing status (HTTP ${response.status}).`);
  }

  const body: unknown = await response.json();
  if (!isRecord(body) || !isArchiveJobStatus(body.status)) {
    throw new Error('The backend returned an invalid archive job status.');
  }

  return {
    status: body.status,
    message: typeof body.message === 'string' ? body.message : null,
    itemsProcessed: typeof body.items_processed === 'number' ? body.items_processed : 0,
    itemsFailed: typeof body.items_failed === 'number' ? body.items_failed : 0,
    chunksCreated: typeof body.chunks_created === 'number' ? body.chunks_created : 0,
  };
}

export async function searchArchiveEvidence(
  query: string,
  signal?: AbortSignal,
): Promise<ArchiveEvidence[]> {
  const response = await apiFetch(
    `/v1/connectors/upload/ingest/search?q=${encodeURIComponent(query)}&limit=5`,
    { signal },
  );
  if (!response.ok) {
    const message = await getResponseMessage(response);
    throw new Error(message || `Could not search the imported archive (HTTP ${response.status}).`);
  }

  const body: unknown = await response.json();
  if (!isRecord(body) || !Array.isArray(body.results)) {
    throw new Error('The backend returned an invalid archive evidence response.');
  }

  const evidence = body.results.map((item): ArchiveEvidence => {
    if (
      !isRecord(item) ||
      typeof item.file_name !== 'string' ||
      typeof item.excerpt !== 'string' ||
      (item.chunk_index !== undefined &&
        item.chunk_index !== null &&
        (typeof item.chunk_index !== 'number' || !Number.isInteger(item.chunk_index)))
    ) {
      throw new Error('The backend returned invalid archive evidence.');
    }

    return {
      fileName: item.file_name,
      excerpt: item.excerpt,
      chunkIndex: typeof item.chunk_index === 'number' ? item.chunk_index : null,
    };
  });

  const seen = new Set<string>();
  return evidence.filter((item) => {
    const key = JSON.stringify([item.fileName, item.chunkIndex, item.excerpt]);
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}
