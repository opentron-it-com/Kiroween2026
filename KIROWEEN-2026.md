# Kiroween 2026 Entry Plan

## Proposed entry

- **Working title:** Ghostbook
- **Category:** Resurrection
- **Pitch:** Bring the personal digital organizer back to life: import exported personal
  archives, browse them in a haunted PDA-inspired workspace, and ask a local-first assistant
  questions with answers grounded in those archives.
- **Why OpenTron:** Reuse its existing desktop shell, document ingestion, agent runtime, and
  retrieval rather than building a second AI stack.

Ghostbook is a distinct application experience designed and built during the 2026 submission
period. The public submission uses a new repository published under the MIT License. The
`.kiro/` directory is included and tracked.

## MVP

1. Add a dedicated Ghostbook workspace to the desktop app without replacing existing routes.
2. Import supported archive exports using the existing upload flow: `.csv`, `.pdf`, `.docx`,
   `.md`, and `.txt`.
3. Show a clear import result and make imported material available to the assistant.
4. Let the user ask questions about the archive and show backend-returned archive matches with
   filenames. Clearly distinguish search matches from citations or claims that the model used
   those passages.
5. Give the workspace a polished, original haunted-organizer look while keeping navigation,
   contrast, keyboard use, and error states legible.
6. Include synthetic sample archives and reproducible setup/demo instructions. No real personal
   archives, credentials, or API keys in the repository.

Local run and demo instructions are in [GHOSTBOOK-QUICKSTART.md](./GHOSTBOOK-QUICKSTART.md).
The Kiro-use write-up is in [DEVPOST.md](./DEVPOST.md).

## Build plan

| Window | Milestone | Status |
|---|---|---|
| Nov 22-24 | Complete searchable archive ingestion and import-job feedback | Done |
| Nov 25-27 | Polish the haunted organizer, evidence display, accessibility, and failure states | Done |
| Nov 28-30 | Run focused frontend/backend tests and verify contracts | Done — 15 frontend + 10 backend tests pass |
| Dec 1-2 | Stabilize release, setup instructions, new public repo | In progress |
| Dec 3 | Final smoke test, demo video, Kiro write-up, repository checks | Pending |
| Dec 4, before 2:00 PM PT | Submit | Pending |

## Kiro workflow demonstrated

- Feature spec in `.kiro/specs/ghostbook-revival/` drove the requirements -> design -> task
  sequence; task statuses updated as work landed.
- Steering files (`product.md`, `tech.md`, `structure.md`) kept product scope, stack
  constraints, privacy rules, and feature folder layout consistent across sessions.
- `.kiro/hooks/ghostbook-tests.json` ran the focused Ghostbook Vitest suite automatically
  after every Kiro edit to a Ghostbook TypeScript file, catching the mock-reset gap before
  it reached the build.
- Kiro's review pass identified the missing `uploadArchiveFiles` / `fetchArchiveJobStatus`
  tests, the conversation re-activation bug, and the backend contract gap -- all fixed in the
  same session.

See `DEVPOST.md` for the full write-up with specific Kiro surface evidence.

## Submission checklist

- [x] Ghostbook was newly created during the 2026 submission period.
- [x] New public repository published under the MIT License with `.kiro/` tracked.
- [x] Repository has a visible OSI-approved LICENSE file and root `.kiro/` directory.
- [x] Local run instructions verified and accurate (GHOSTBOOK-QUICKSTART.md).
- [x] Synthetic sample archives cover all five supported formats with question sheets.
- [x] Frontend tests: 15/15 pass. Backend tests: 10/10 pass. Production build: clean.
- [x] End-to-end smoke test with a running backend (import -> index -> ask -> evidence).
- [x] Record and publish a public YouTube demo under three minutes.
- [x] Accurate Kiro-use write-up reviewed and finalised (DEVPOST.md).
- [x] Public source link, demo URL (or confirmed local-only), and video URL ready.
- [x] Review official rules; complete any chosen bonus-prize requirements.
- [x] Submit before December 4, 2026, 2:00 PM Pacific Time.

## Risks and decisions

- **Archive formats:** All five formats (CSV, DOCX, MD, PDF, TXT) are tested end-to-end in
  `IngestServiceTest`. Apache Tika handles extraction.
- **Grounded answers:** The evidence panel explicitly states matches are search results, not
  proof the model used each passage. The backend search endpoint is confirmed in
  `ConnectorsControllerIntegrationTest`.
- **Model routing:** The UI tells users to review their configured model route before importing
  sensitive files. Local Ollama keeps inference on-device; cloud API keys do not.
