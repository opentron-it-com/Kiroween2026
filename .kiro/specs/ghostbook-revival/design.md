# Design: Ghostbook archive revival

## Overview

Add a self-contained Ghostbook feature to the existing desktop experience. A dedicated route presents a haunted personal-organizer workspace, while the existing OpenTron services continue to own file ingestion, retrieval, and agent responses.

## Frontend

- Add the page and feature components under `frontend/src/features/ghostbook/`.
- Register a dedicated route in `frontend/src/App.tsx`; preserve the shared `Layout` and all existing routes.
- Keep presentation, loading/error state, and Ghostbook-specific API mapping inside the feature. Reuse shared UI and API helpers where suitable.
- Build the visual language around a modernized PDA/organizer: archive list or timeline, clear import action, and a conversation/evidence panel. Use original assets and CSS; do not use unlicensed media or borrowed product marks.
- Provide keyboard-accessible controls, visible focus, sufficient contrast, and clear empty/loading/error/success states.

## Service integration

1. Inspect the existing connector upload endpoint and frontend API conventions before coding.
2. Send supported files through the existing upload/ingest API; do not introduce a parallel parser or persist file bodies in browser storage.
3. Inspect the actual chat and retrieval response shape before rendering source evidence. Reuse returned metadata; if the API does not expose evidence, add a minimal typed contract only after tracing the backend flow.
4. Report upload, backend, and model errors explicitly. Never render a success state after an unsuccessful request.

## Privacy and data

- Keep demo fixtures synthetic and checked in only after review.
- Do not add analytics containing archive names, contents, prompts, or personally identifying details.
- Describe the configured backend/model route accurately; local indexing does not necessarily mean inference remains on-device.
- Avoid new persistence until a tested user need requires it.

## Testing

- Add focused tests for accepted/rejected file handling, visible failures, and citation rendering/no-citation behavior.
- Run the feature tests through the Kiro hook after edits under the Ghostbook feature directory.
- Run the frontend TypeScript/Vite production build after route integration.
- If backend contracts change, add targeted Java tests for the changed contract and run the relevant Maven test selector.

## Release constraints

Ghostbook must be newly implemented during the submission window. The public submission repository must include its source, run instructions, root `.kiro/`, and a visible OSI-approved license. Resolve the current repository license conflict before publication; do not assume the present repository is eligible as-is.
