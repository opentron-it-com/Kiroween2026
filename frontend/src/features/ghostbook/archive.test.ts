import { beforeEach, describe, expect, it, vi } from 'vitest';
import { apiFetch } from '../../lib/api';
import {
  fetchArchiveJobStatus,
  getArchiveDisplayStatus,
  isSupportedArchiveFile,
  searchArchiveEvidence,
  uploadArchiveFiles,
} from './archive';

vi.mock('../../lib/api', () => ({ apiFetch: vi.fn() }));

beforeEach(() => {
  vi.mocked(apiFetch).mockReset();
});

describe('isSupportedArchiveFile', () => {
  it('accepts supported archive formats without case sensitivity', () => {
    for (const fileName of [
      'journal.csv',
      'notes.DOCX',
      'letters.md',
      'scan.PDF',
      'diary.txt',
    ]) {
      expect(isSupportedArchiveFile(fileName)).toBe(true);
    }
  });

  it('rejects unsupported, missing, and trailing extensions', () => {
    for (const fileName of ['calendar.ics', 'contacts.vcf', 'README', 'archive.']) {
      expect(isSupportedArchiveFile(fileName)).toBe(false);
    }
  });
});

describe('searchArchiveEvidence', () => {
  it('returns only evidence with source names supplied by the backend', async () => {
    vi.mocked(apiFetch).mockResolvedValueOnce(
      new Response(JSON.stringify({
        query: 'lantern fair',
        results: [
          {
            file_name: 'journal.csv',
            excerpt: 'Mara and I watched the paper lanterns drift across the lake.',
            chunk_index: 0,
          },
          {
            file_name: 'journal.csv',
            excerpt: 'Mara and I watched the paper lanterns drift across the lake.',
            chunk_index: 0,
          },
        ],
      }), { status: 200 }),
    );

    await expect(searchArchiveEvidence('lantern fair')).resolves.toEqual([{
      fileName: 'journal.csv',
      excerpt: 'Mara and I watched the paper lanterns drift across the lake.',
      chunkIndex: 0,
    }]);
  });

  it('rejects malformed evidence instead of inventing source details', async () => {
    vi.mocked(apiFetch).mockResolvedValueOnce(
      new Response(JSON.stringify({
        results: [{ excerpt: 'No source name supplied.' }],
      }), { status: 200 }),
    );

    await expect(searchArchiveEvidence('lantern fair')).rejects.toThrow(
      'The backend returned invalid archive evidence.',
    );
  });
});

describe('uploadArchiveFiles', () => {
  it('returns job ID and file count on a valid backend response', async () => {
    vi.mocked(apiFetch).mockResolvedValueOnce(
      new Response(JSON.stringify({
        status: 'queued',
        job_id: 'abc-123',
        files_received: 2,
      }), { status: 200 }),
    );

    const files = [
      new File(['content'], 'notes.txt', { type: 'text/plain' }),
      new File(['data'], 'records.csv', { type: 'text/csv' }),
    ];
    await expect(uploadArchiveFiles(files)).resolves.toEqual({
      jobId: 'abc-123',
      filesReceived: 2,
    });
  });

  it('throws without making a request when the file list is empty', async () => {
    await expect(uploadArchiveFiles([])).rejects.toThrow('Choose at least one archive file.');
    expect(vi.mocked(apiFetch)).not.toHaveBeenCalled();
  });

  it('throws without making a request when any file has an unsupported extension', async () => {
    const files = [
      new File(['x'], 'photo.jpg'),
      new File(['y'], 'notes.txt'),
    ];
    await expect(uploadArchiveFiles(files)).rejects.toThrow('Unsupported file type: photo.jpg');
    expect(vi.mocked(apiFetch)).not.toHaveBeenCalled();
  });

  it('throws on a non-OK HTTP response without reporting success', async () => {
    vi.mocked(apiFetch).mockResolvedValueOnce(
      new Response(JSON.stringify({ message: 'Storage unavailable.' }), { status: 503 }),
    );

    const files = [new File(['x'], 'diary.txt')];
    await expect(uploadArchiveFiles(files)).rejects.toThrow('Storage unavailable.');
  });

  it('throws on a response that is queued but has an unexpected shape', async () => {
    vi.mocked(apiFetch).mockResolvedValueOnce(
      new Response(JSON.stringify({ status: 'queued', job_id: '', files_received: 1 }), { status: 200 }),
    );

    const files = [new File(['x'], 'notes.md')];
    await expect(uploadArchiveFiles(files)).rejects.toThrow(
      'The backend returned an invalid archive import response.',
    );
  });
});

describe('fetchArchiveJobStatus', () => {
  it('returns a completed job status with all counters', async () => {
    vi.mocked(apiFetch).mockResolvedValueOnce(
      new Response(JSON.stringify({
        status: 'completed',
        message: 'Processed 1 files into 3 chunks',
        items_processed: 1,
        items_failed: 0,
        chunks_created: 3,
      }), { status: 200 }),
    );

    await expect(fetchArchiveJobStatus('job-1')).resolves.toEqual({
      status: 'completed',
      message: 'Processed 1 files into 3 chunks',
      itemsProcessed: 1,
      itemsFailed: 0,
      chunksCreated: 3,
    });
  });

  it('defaults missing numeric counters to zero', async () => {
    vi.mocked(apiFetch).mockResolvedValueOnce(
      new Response(JSON.stringify({ status: 'running' }), { status: 200 }),
    );

    const result = await fetchArchiveJobStatus('job-2');
    expect(result.itemsProcessed).toBe(0);
    expect(result.itemsFailed).toBe(0);
    expect(result.chunksCreated).toBe(0);
    expect(result.message).toBeNull();
  });

  it('throws with the backend message on a non-OK response', async () => {
    vi.mocked(apiFetch).mockResolvedValueOnce(
      new Response(JSON.stringify({ detail: 'Job not found.' }), { status: 404 }),
    );

    await expect(fetchArchiveJobStatus('missing-job')).rejects.toThrow('Job not found.');
  });

  it('throws on an unrecognised status value', async () => {
    vi.mocked(apiFetch).mockResolvedValueOnce(
      new Response(JSON.stringify({ status: 'unknown_state' }), { status: 200 }),
    );

    await expect(fetchArchiveJobStatus('job-3')).rejects.toThrow(
      'The backend returned an invalid archive job status.',
    );
  });
});

describe('getArchiveDisplayStatus', () => {
  it('distinguishes partial success from a completed job where every file failed', () => {
    expect(getArchiveDisplayStatus({
      status: 'completed',
      message: null,
      itemsProcessed: 1,
      itemsFailed: 1,
      chunksCreated: 1,
    })).toBe('partial');

    expect(getArchiveDisplayStatus({
      status: 'completed',
      message: null,
      itemsProcessed: 0,
      itemsFailed: 1,
      chunksCreated: 0,
    })).toBe('failed');
  });

  it('preserves queued and running jobs and marks a clean completion indexed', () => {
    expect(getArchiveDisplayStatus({
      status: 'queued',
      message: null,
      itemsProcessed: 0,
      itemsFailed: 0,
      chunksCreated: 0,
    })).toBe('queued');

    expect(getArchiveDisplayStatus({
      status: 'running',
      message: null,
      itemsProcessed: 0,
      itemsFailed: 0,
      chunksCreated: 0,
    })).toBe('running');

    expect(getArchiveDisplayStatus({
      status: 'completed',
      message: null,
      itemsProcessed: 1,
      itemsFailed: 0,
      chunksCreated: 1,
    })).toBe('completed');
  });
});
