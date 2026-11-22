# Ghostbook — Kiroween 2026 Devpost Write-up

## Submission links and readiness

- **Working app/demo URL:** Not deployed. Ghostbook currently runs locally; do not submit a
  localhost URL as a public demo.
- **Demo video:** https://www.youtube.com/watch?v=sW4fFDWKprM (public YouTube watch page; 14 seconds).
- **Public source repository:** https://github.com/opentron-it-com/Kiroween2026 (public github repo)
  Its GitHub license metadata is MIT.

## What it does

Ghostbook revives the personal digital organizer. You import exported notes, journals, and
records (CSV, PDF, DOCX, Markdown, plain text) into a haunted PDA-inspired workspace. The
OpenTron backend extracts and indexes them. You then ask the assistant natural-language
questions, and the workspace shows you both the answer and the specific archive passages that
match your question — with a clear disclaimer that displayed passages are search matches, not
a claim the model cited each one.

The demo flow: download the synthetic sample CSV archive from the page, import it, ask
"What did Mara and I watch at the Lantern Fair?" — the answer comes back and the Evidence
matches panel shows the exact excerpt from `ghostbook-sample-archive.csv`.

## How I built it

Ghostbook is a new feature added to the OpenTron desktop app (React 19 + TypeScript + Vite
frontend, Java 21 Spring Boot backend). It reuses OpenTron's existing file ingestion,
memory/retrieval, and chat APIs rather than building a second stack.

### New code

| Path | What it is |
|---|---|
| `frontend/src/features/ghostbook/GhostbookPage.tsx` | The full Ghostbook workspace: file picker, import flow, archive list, evidence panel, chat |
| `frontend/src/features/ghostbook/archive.ts` | Typed API adapter for upload, job polling, and evidence search with runtime response validation |
| `frontend/src/features/ghostbook/ghostbook.css` | PDA-inspired visual language scoped entirely to the Ghostbook page |
| `frontend/src/features/ghostbook/archive.test.ts` | 15 Vitest tests covering format validation, upload, job status, evidence parsing, and display-status mapping |

The route `/ghostbook` was added to `App.tsx` and the sidebar inside the existing `<Layout>`,
leaving all other routes untouched.

Backend additions were limited to two new integration test cases
(`ConnectorsControllerIntegrationTest`) confirming the upload-rejection and job-not-found
paths the frontend depends on. The ingestion, memory, and search endpoints already existed.

### How Kiro was used

**Spec-driven workflow.** The `.kiro/specs/ghostbook-revival/` directory held
`requirements.md`, `design.md`, and `tasks.md` from the start. Each session began by reading
the spec; tasks were checked off as work landed. This kept scope tight across multiple
sessions — no unrequested refactors crept in.

**Steering files.** Three always-on steering documents (`product.md`, `tech.md`,
`structure.md`) gave Kiro consistent rules for every edit:
- Keep the feature under `frontend/src/features/ghostbook/`.
- Reuse existing APIs after inspecting their actual contracts — do not duplicate ingestion.
- Keep privacy copy accurate: never claim local-only inference unless verified.
- Keep `.kiro/` tracked and visible.

These meant the frontend API adapter was written only after Kiro read `ConnectorsController.java`
and `IngestService.java` directly, confirming the exact field names (`files_received`,
`job_id`, `items_processed`, `items_failed`, `chunks_created`, `file_name`, `chunk_index`,
`excerpt`) before any type was written.

**Post-implementation review.** After the feature was built, Kiro reviewed the entire
implementation against the spec. It identified four concrete gaps:
1. `uploadArchiveFiles` and `fetchArchiveJobStatus` had no tests — the existing test file only
   covered the pure functions.
2. The `vi.mock` was shared across describe blocks without a `beforeEach` reset, so test
   isolation was broken (would have caused false positives in CI).
3. `GhostbookPage` re-selecting the Ghostbook conversation only on mount — navigating away
   and back left the wrong conversation active.
4. The backend integration test only had 3 cases; the upload-rejection and job-not-found paths
   the frontend relied on were untested.

All four were fixed in the same session.

**Focused test hook.** `.kiro/hooks/ghostbook-tests.json` configured a `PostFileSave` hook
that ran `npm test -- src/features/ghostbook` after every Kiro edit to a Ghostbook TypeScript
file. When the `beforeEach` mock-reset fix was applied, the hook ran immediately and confirmed
15/15 pass before the session ended.

**Specific Kiro surfaces used:**
- Kiro IDE chat with the workspace open (all code edits)
- Spec task tracking (`.kiro/specs/ghostbook-revival/tasks.md`)
- Always-on steering (`.kiro/steering/`)
- PostFileSave hook (`.kiro/hooks/ghostbook-tests.json`)
- Kiro's review pass (identified the four gaps above)

Crew, MCP, custom agents, Powers, and PBT were not used in this build.

## Challenges

**Contract verification before coding.** The design doc required inspecting the actual backend
contracts before writing any frontend types. Kiro read `ConnectorsController.java` and
`IngestService.java` to confirm field names, status values, and error shapes. Without that,
the response validator in `archive.ts` would have been written against assumptions.

**Evidence disclaimer.** The spec required the UI to distinguish archive search matches from
model citations. The wording — "These passages are archive search matches for '...'; they are
not a claim that the assistant used each passage in its answer" — required care to be accurate
without being confusing.

**Test isolation.** The `vi.mock` / `vi.fn()` pattern in Vitest shares mock state across
`describe` blocks in the same file unless `beforeEach(() => vi.mocked(fn).mockReset())` is
added. The gap was caught by the review pass; the hook confirmed the fix immediately.

## What I learned

Reading the actual backend source before writing frontend types is faster than discovering
mismatches in integration. Having the spec and steering in `.kiro/` meant that constraint was
enforced automatically — Kiro referenced the design doc's "inspect before coding" instruction
every session.

## What's next

- [x] Verify a live import -> Ollama answer -> archive-evidence flow.
- [x] Record and publish a short demo video.
- [x] Resolve the public app URL, public source repository, and license before submission.
- Explore drag-and-drop import and a persistent archive list (currently session-only).

## Built with

React 19, TypeScript, Vite, Tailwind CSS, Java 21, Spring Boot, Apache Tika, H2 (embedded),
Lucide icons, Vitest, JUnit 5, Kiro IDE.

## Repository

`.kiro/` is tracked at the repository root and contains the full spec, steering, and hook
used during the build. Run instructions are in `GHOSTBOOK-QUICKSTART.md`.
