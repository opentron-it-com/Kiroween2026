# Requirements: Ghostbook archive revival

## Introduction

Ghostbook is a new desktop experience in the Resurrection category. It reimagines the personal digital organizer as a haunted, searchable archive powered by OpenTron's existing ingestion and agent capabilities. The experience is separate from the existing chat and dashboard routes.

## User stories and acceptance criteria

### 1. Import an archive

As a user with old personal exports, I want to import supported files so I can rediscover their contents in one place.

**Acceptance criteria**

1. The Ghostbook workspace accepts only formats supported by the ingestion pipeline: PDF, DOCX, CSV, Markdown, and plain text.
2. The workspace uses the existing ingestion contract and shows a visible success result after an accepted import.
3. If a file is unsupported or ingestion fails, the user sees an actionable error and can retry; the UI must not report success.
4. The demo uses synthetic archive files only.

### 2. Ask about imported material

As a user, I want to ask a natural-language question about my archive and understand where the answer came from.

**Acceptance criteria**

1. A user can submit a question from the Ghostbook workspace and see the assistant response or a visible error.
2. When retrieval provides source metadata, the answer exposes the available source evidence in the UI.
3. The UI does not fabricate citations or imply an answer is grounded when source evidence is unavailable.
4. The experience uses the existing OpenTron chat/retrieval flow rather than maintaining a second agent stack.

### 3. Navigate a resurrected organizer

As a user, I want a recognizable, polished organizer interface so that browsing an old archive feels approachable and memorable.

**Acceptance criteria**

1. Ghostbook has a dedicated route and does not change the behavior of existing routes.
2. Its haunted PDA-inspired visual style is legible, keyboard accessible, and responsive at the supported desktop window size.
3. Loading, empty, success, and error states are visually distinct.

### 4. Protect archive privacy

As a user, I want to know how my archive is handled before I import personal material.

**Acceptance criteria**

1. Ghostbook states that imports use the configured OpenTron backend and model route; it does not promise fully offline processing unless verified.
2. Archive contents, prompts, and credentials are not written to application logs or sample files.
3. The user can distinguish an unavailable backend/model from an empty archive.

## Out of scope for the first submission

- Native parsing of proprietary Palm/PDA binary databases or unverified calendar/contact export formats.
- New cloud connectors, social features, or public sharing of personal archives.
- Replacing the existing OpenTron interface or refactoring unrelated features.
- Claims of complete offline operation, perfect recall, or citations unsupported by the actual response contract.
