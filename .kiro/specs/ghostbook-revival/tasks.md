# Implementation tasks

## 1. Confirm the release boundary

- [x] New public repository created under MIT License; `.kiro/` tracked at root.
- [x] Confirm the target platform, supported demo flow, and permitted archive formats.
- [x] Inspect the existing chat/retrieval path and file-ingestion API.

## 2. Build the dedicated workspace

- [x] Add the Ghostbook feature folder and route without changing existing navigation behavior.
- [x] Implement the PDA-inspired archive workspace with empty, loading, success, and error states.
- [x] Add a synthetic sample archive (all five supported formats with matching question sheets).

## 3. Connect archive and assistant flows

- [x] Integrate supported file import through the existing ingestion API.
- [x] Integrate questions through the existing OpenTron chat/retrieval flow.
- [x] Render archive evidence returned by the backend, with typed API validation and a clear
      distinction between matches and answer citations.
- [x] Add visible failure handling and model/backend privacy copy.

## 4. Verify and package

- [x] Add focused tests for supported formats, ingestion persistence, and natural-language
      memory search (IngestServiceTest 3, MemoryServiceTest 2).
- [x] Add focused tests for uploadArchiveFiles and fetchArchiveJobStatus API paths (frontend).
- [x] Add beforeEach mock reset so all 15 frontend tests are properly isolated.
- [x] Run targeted frontend tests: 15/15 pass.
- [x] Run frontend production build: clean (no errors).
- [x] Run targeted backend tests: 10/10 pass
      (IngestServiceTest 3, MemoryServiceTest 2, ConnectorsControllerIntegrationTest 5).
- [x] Fix GhostbookPage conversation re-activation on return navigation
      (location.pathname dependency added).
- [x] Add backend integration tests for upload-rejection (empty files -> 400) and
      job-not-found (unknown jobId -> 404).
- [x] Write reproducible local setup instructions (GHOSTBOOK-QUICKSTART.md) — encoding
      fixed, Windows and Unix commands included, model setup and privacy notes added.
- [x] Write Kiro-use write-up with concrete Kiro surface evidence (DEVPOST.md).
- [x] Update KIROWEEN-2026.md — encoding fixed, submission checklist updated.
- [x] Add MIT LICENSE-MIT file for the new public repository.
- [ ] End-to-end smoke test with a running backend: import -> index -> ask -> evidence.
- [ ] Record and publish a public YouTube demo video under three minutes.
- [ ] Finalise DEVPOST.md write-up (fill in demo URL and video URL once published).
- [ ] Review official rules; complete any chosen bonus-prize requirements.
- [ ] Submit before December 4, 2026, 2:00 PM Pacific Time.
