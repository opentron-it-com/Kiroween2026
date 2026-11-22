---
inclusion: always
---

# Ghostbook code organization

- Keep the new feature under `frontend/src/features/ghostbook/`, including its page, UI components, API adapter, and focused tests.
- Integrate it through the existing React Router in `frontend/src/App.tsx`; do not fork the app shell or duplicate the shared layout.
- Reuse existing frontend API helpers and shared UI conventions where their contracts fit. Keep feature-specific presentation and state inside the feature folder.
- Add backend code only if the existing upload, chat, or retrieval contracts cannot satisfy a verified requirement. Keep persistence and orchestration in their existing service layers.
- Keep synthetic demo data separate from user data and do not add generated or private files to source control.
